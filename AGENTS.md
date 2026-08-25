# AGENTS.md

Context for coding agents working in this repo. Read this first — it should save you
from re-reading the whole tree.

## What this is

`otjServices` is a Java REST API that automates the login and submission flow for
**OTJ (off-the-job) activity logs** on OneAdvanced's cloud-education platform
(`education.oneadvanced.com`). Apprentices/students have to log training hours by
hand through a slow multi-step web UI behind SSO + MFA; this service drives that
flow programmatically:

1. The user writes free-text notes about what they did.
2. An LLM turns the *diff* between old and new notes into structured activity-log
   entries (date, duration, description, category).
3. The service logs into OneAdvanced on the user's behalf (holding the browser/HTTP
   session open across the ~30 s MFA window) and POSTs the entries to the platform's
   activity-log JSON API.

**This API is the backend for a mobile app** — currently an **Expo Go** (React
Native + TypeScript) project living in a separate repo at
`/home/asad/Projects/personal/java/otj-mobile`. The mobile client is the only
intended consumer: it holds the bearer token, renders the notes editor, and calls
the endpoints below. `mobile-signup-runbook.md` documents the client-side signup
wiring (`EXPO_PUBLIC_API_URL`, auth context, API helpers). When changing an endpoint's
shape, assume there is an Expo client that has to change with it.

## Stack

- **Java 25**, Maven (`mvn`), shaded uber-jar (`target/app.jar`, main class
  `com.github.grepHammerspace.Main`)
- **Jersey 3.1 (JAX-RS)** on **Grizzly**, port **8945**
- **Dagger 2** for DI (`bind/AppModule`, `bind/AppComponent`)
- **MongoDB** (`otjdb`) via the sync driver; Atlas in prod, containerised locally
- **OkHttp + jsoup** for driving the OneAdvanced/Azure AD login chains
- **Anthropic Java SDK** for the notes → activity-log parsing
- **bcrypt** (`at.favre.lib`) for password hashing
- **JUnit 5 + Cucumber + Testcontainers** for tests

## Directory structure

```
src/main/java/com/github/grepHammerspace/
  Main.java               entry point for the main API; wires Dagger, starts the server
  ServerBootstrap.java    Grizzly/Jersey setup (thread-per-task executor — the
                          login/submit calls block, so carrier threads must not saturate)
  api/                    JAX-RS resources
    AuthResource            /auth  — signup, login, logout (anonymous by design)
    OtjServicesResource     /otj-services — all automation endpoints (@Authenticated)
    HealthResource          /health
    dto/                    request/response records
  admin/                  the SECOND server — see "Two processes" below
    AdminMain.java          entry point, port 8946
    AdminInviteResource     /admin/invites — mint, list, revoke invite codes
    AdminIdentityFilter     tailnet identity gate; @AdminIdentity name-binds it
    AdminAllowlist          ADMIN_ALLOWED_LOGINS, fails closed
    InviteCodeGenerator     OTJ-XXXX-XXXX, ambiguous glyphs removed
  auth/                   @Authenticated annotation + AuthenticationFilter (401 gate,
                          puts userId in the SecurityContext principal),
                          SessionTokenService (opaque bearer tokens), PasswordHasher
  db/                     repositories (User, Session, ActivityLog, InviteCode)
    model/                  User, Session, ActivityLog, InviteCode documents
  llm/                    LlmService/LlmServiceImpl, LlmResult / LlmParseError,
                          exception/ hierarchy
  stateStore/             UserStateStore — ConcurrentHashMap of userId → UserState,
                          keeps a logged-in Driver alive between prepare and MFA calls
    LoginSession, LoginFlow  one parked login: which flow, its driver, its background
                             login future, and when it started (5 min TTL)
  bind/                   AppModule + AppComponent (main API),
                          AdminModule + AdminComponent (admin API)
  web/                    Driver interface + implementations
    OtjDriver               direct OneAdvanced/Keycloak discover login
    AzureIdDriver           QMUL Azure AD federation path; bypasses discover with a
                            hand-built PKCE flow, then Microsoft Authenticator push
    Keycloak, AzurePush     Dagger qualifiers selecting between the two drivers, so the
                            resource names neither and tests can bind fakes
    SafeUrl                 strips query strings before a URL reaches a log or exception
    PrepareResult, OtjSubmitResult

src/main/resources/       app.properties, llm_prompt.txt (system prompt), logback.xml
src/test/java/            unit tests, *IT integration tests, integration/ Cucumber glue
src/test/resources/features/  Cucumber .feature files

aws/                      CDK (TypeScript): OtjServicesStack (VPC + EC2 + ECR),
                          GithubOidcStack (CI deploy role). node_modules/ is committed-ish noise —
                          ignore it when searching.
.github/workflows/ci-cd.yml  test on PR; on push to master, deploy CDK + build/push
                          image to ECR + roll out on the box via SSM
docker/                   otjService.Dockerfile, start.sh
deploy/                   self-host path: podman-compose.yaml, bootstrap.sh, shell.nix,
                          README.md (Tailscale trust model)
  prod/                   what is installed by hand on the AWS box: deploy.sh, the two Quadlet
                          templates, Caddyfile (public edge), README.md (provisioning runbook,
                          rate-limit rationale, the shared-IP problem)
scripts/                  (tailscale branch only) `otj` CLI for hand-testing the API
```

Docs worth knowing about (root): `deployment-checklist.md` (AWS bring-up),
`mobile-signup-runbook.md` (Expo client wiring), `steps-04-08-implementation-plan.md`
and `auth-multiuser-plan.html` (the multi-user rollout plan), `notes.md` (idea backlog).

## Two processes, one image

The jar has two entrypoints, selected by `APP_ROLE` in `docker/start.sh`:

| Role | Main class | Port | Graph | Exposure (AWS box) |
|---|---|---|---|---|
| `api` (default) | `Main` | 8945 | `AppComponent` | **public** — Cloudflare → Caddy on 443 → `127.0.0.1:8945`; also `tailscale serve --https=8444` |
| `admin` | `admin.AdminMain` | 8946 | `AdminComponent` | tailnet only — `tailscale serve --https=8443` |

Same image tag for both, so they cannot drift and a rollback moves them together. The admin
graph never constructs a driver, the LLM client or session handling — which is also why the
admin container needs no `ANTHROPIC_API_KEY` despite `AppModule` declaring that binding (Dagger
providers are lazy).

**The admin API's security rests on two independent things**, and changing either one breaks it:

1. It publishes to `127.0.0.1:8946` only, so `tailscale serve` is the sole process that can
   reach it. That is *why* the `Tailscale-User-Login` header it injects can be believed.
   Publish the port wider and `AdminIdentityFilter` becomes decorative.
2. That login must be in `ADMIN_ALLOWED_LOGINS`. Being on the tailnet is not being an operator
   — phones join the tailnet to use the app. An unset allowlist denies everyone.

Don't add a dev-mode bypass. Locally you pass the header with `curl -H`; reaching loopback at
all already means you're on the host, which is what the model assumes in production anyway.

## API surface

Anonymous:

| Method | Path | Body → Result |
|---|---|---|
| POST | `/auth/signup` | `{inviteCode, username, password, learnerId}` → 201 `{token}` |
| POST | `/auth/session` | `{username, password}` → 200 `{token}`, or 429 + `Retry-After` |
| DELETE | `/auth/session` | `Authorization: Bearer …` → 204 |
| GET | `/health` | 200 |

Authenticated (`Authorization: Bearer …`, all under `/otj-services`):

| Method | Path | Body → Result |
|---|---|---|
| GET | `/crypto/public-key` | → 200 `{algorithm, keyId, publicKey, expiresAt, signature}` |
| POST | `/prepare-browser` | `SealedEnvelope` → 200 `{status, message}` |
| POST | `/azure-id/prepare` | `SealedEnvelope` → 200 `{status, message, challengeNumber?}` |
| POST | `/submit-with-mfa` | `{mfaCode}` → 200/207/502 `{status, posted, failed}` |
| GET | `/azure-id/complete` | → 200/207/408/502 `{status, posted, failed}` |
| POST | `/log-activities` | `{content}` → 200 `ActivityLogResponse`, or 429 + `Retry-After` |
| POST | `/register` | `{username, password, learnerId}` → 201 |
| GET | `/pending` | → 200 `PendingResponse` |
| PUT | `/pending/{id}` | `UpdateActivityRequest` → 200 `PendingActivity`, or 404 |
| DELETE | `/pending/{id}` | → 204 |
| DELETE | `/delete-last-row` | → 200 |

The two prepare endpoints carry the user's **OneAdvanced** credentials, and they arrive
**encrypted to this process** rather than as plaintext JSON — see "Sealed credentials" below and
`credential-encryption-spec.md` for the format. They are still **not stored**: the multi-user
rollout deleted the encrypted-at-rest copy, so they have to arrive per request. They exist as a
local for the length of the call, go straight into `Driver.prepare`, and never reach a log line or
a response body. Do not add a field, a cache, or a "remember me" for them.

`prepare*` answers `status` of `login_complete`, `otp_required` or `push_sent`; `challengeNumber`
is present only for a Microsoft number match. The learner ID is **server-side** and is never
accepted on these bodies — Jackson rejects the unknown property with a 400, at both layers now
(the envelope and the JSON sealed inside it), and `prepare_and_submit.feature` pins both.

`/submit-with-mfa` is deliberately *not* sealed. A TOTP is six digits with about thirty seconds of
life and cannot be replayed after that, so a copy taken at the edge is worthless by the time
anyone could use it — while sealing it would put a key fetch in front of the most time-critical
call in the app.

Authenticated, outside `/otj-services` — the account itself, on `AccountResource`:

| Method | Path | Body → Result |
|---|---|---|
| GET | `/auth/me` | → 200 `{username, learnerId}` |
| PATCH | `/auth/me` | `{learnerId}` → 200 `{username, learnerId}` |

**These are not on `AuthResource`.** That class carries no `@Authenticated` annotation on purpose
— its endpoints are the ones you reach before holding a token — and the annotation binds per
class, so the two that need a token live in their own class under the same URL prefix. See
`learner-id-api-spec.md`.

Admin API (separate process/port, tailnet identity instead of bearer tokens):

| Method | Path | Result |
|---|---|---|
| POST | `/admin/invites` | `{note?, expiresInDays?}` → 201 `{code, status, expiresAt, …}` |
| GET | `/admin/invites` | 200, newest first, `status` ∈ ACTIVE/USED/REVOKED/EXPIRED |
| DELETE | `/admin/invites/{code}` | 204; 404 unknown; 409 already claimed |

## Sealed credentials

`otj-services.com` is proxied by Cloudflare: the visitor's TLS terminates at an edge node, which
opens its own connection to Caddy on the box. A password in a request body is therefore plaintext
inside Cloudflare for that hop. The bearer token can live with that — it is ours and revocable —
but the OneAdvanced password is the user's real institutional credential, and this service goes out
of its way not to store it precisely so that only one copy exists. So the two prepare endpoints
take a `SealedEnvelope` instead.

```
crypto/CredentialKeyRing   the identity key, the rotating X25519 key, and open()
crypto/Hkdf                HKDF-SHA256, hand-rolled, RFC 5869 vectors in HkdfTest
crypto/RawKeys             raw 32-byte keys ↔ the JDK's DER-encoded key specs
crypto/IdentityKeyTool     `generate` mints the pair; `verify` checks a live announcement
api/CryptoResource         GET /otj-services/crypto/public-key
credential-encryption-spec.md   the wire format — the contract with the Expo client
```

Five things not to undo:

- **The pinned identity key is the whole point.** The announcement crosses the same Cloudflare hop
  the credentials do, so a client that trusted the key it was handed would be defended against an
  edge that reads and not at all against one that answers. `CredentialKeyRing` signs the
  announcement with a long-lived Ed25519 key; the app carries the public half as a build constant
  and refuses to submit when a signature does not verify. Do not add the identity public key to the
  response "for convenience" — verifying a signature against a key from the same channel proves
  nothing.
- **There is no plaintext fallback and no flag for one.** A server that still accepted the old body
  would leave the plaintext path open to exactly the party being defended against, and no
  client-side setting can close a door the server holds open. The cutover is hard, which is why the
  backend and the app deploy together.
- **The service will not boot without `CREDENTIAL_IDENTITY_SEED`.** Fails closed, like
  `AdminAllowlist`. A generated key would publish announcements no released app can verify, and
  every submit in the field would fail with what looks like an attack.
- **Decryption failures are one answer.** Wrong key, flipped bit and truncated ciphertext all come
  back `undecryptable`, and the reason never quotes the envelope. Distinguishing them is a
  decryption oracle. `unknown_key` is the one code the client acts on: it re-fetches and seals
  again, once.
- **`iat` is not a replay defence.** Whatever holds the bearer token can replay the whole request.
  It bounds how long a captured envelope stays useful, and lives inside the ciphertext so nothing
  on the path can edit it.

Note that the Cucumber suite does not run under `mvn test` — surefire's default includes skip
`*IT`, and there is no failsafe plugin, so CI's `mvn -B test` never reaches `crypto.feature` or
`prepare_and_submit.feature`. Run it by hand: `mvn test -Dtest=CucumberIT`.

Notes:
- Tokens are 32 random bytes, base64url; only the SHA-256 hash is stored. Sliding
  30-day expiry, refreshed on resolve when less than half the window remains.
- `userId` is a server-minted UUID — the client never sends or receives it; every
  authenticated handler derives it from the token via `SecurityContext`.
- Signup is **invite-gated**; codes are minted through the admin API above.
- `PUT /pending/{id}` replaces the five editable fields of one unposted row and answers with
  the updated `PendingActivity`. Ownership and `posted: false` are in the Mongo filter, not a
  check after the read, so unknown / someone else's / already-posted all give the same 404 as
  the delete endpoint. Its rules are hand-written in `UpdateActivityRequest` rather than with
  `@Valid`, because only an `{"error": "..."}` body reaches the mobile user as a real message.
- Revocation sets `revokedAt` *and* pulls `expiresAt` back to now, so a revoked code dies
  through the existing claim filter. `InviteCodeRepository.claim` is a single atomic
  `findOneAndUpdate` and deliberately knows nothing about revocation — leave it that way.
- Login is a two-phase dance because MFA codes live ~30 s: `prepare*` opens and parks
  the session in `UserStateStore`, then `submit-with-mfa` / `azure-id/complete`
  finishes it. The two halves are **not interchangeable** — a `LoginSession` records which
  `LoginFlow` it belongs to and the wrong complete gets a 409. Without that check an Azure
  session would be accepted by `submit-with-mfa`, and `AzureIdDriver.completeMfa` ignores the
  token it is given, so it would start a second Microsoft poll racing the first.
- A parked session expires after `LoginSession.TTL` (5 min) and is dropped as soon as it is
  spent. It holds live OneAdvanced cookies, so it must not outlive its use.
- The learner ID used when posting is read from the **account at submit time**, not from the
  rows being posted, so a correction through `PATCH /auth/me` also rescues activities already
  queued. The value stored on each row is left alone as a record of what was intended when it
  was written.

## Branches

Two **protected** branches, both deployment targets:

- **`master`** — the AWS deployment. Pushes here run CI and then deploy: CDK
  (`OtjServicesStack`), image build → ECR, rollout on the EC2 box via SSM. This is
  the hosted instance backing the mobile app.
- **`tailscale`** — reserved for **self-hosters**. Tracks `master` closely (today it is
  `master` plus `scripts/otj`, the hand-testing CLI). The intent recorded in
  `notes.md` is that this branch stays runnable purely via `deploy/bootstrap.sh` +
  `podman-compose` on your own box, without the AWS infra path.

Do not push directly to either; open a PR.

`staging` was the working integration branch for the multi-user auth rollout
(steps 01–08). **That rollout has landed:** `staging` was merged into `master` by
PR #21 on 2026-08-16, so session tokens, bcrypt users, invite-gated signup and
`SessionRepository` are all on `master` now, and the old
`tailscale/TailscaleIdentity*` classes are gone from it. `master` is the branch that
deploys and is currently *ahead* of `staging` — reverse of the old advice. Treat
`master` as the source of truth unless you have a reason not to.

The one place `Tailscale-User-Login` is still trusted is `admin/AdminIdentityFilter`,
for the admin API on 8946, and that rests entirely on nothing but loopback and
`tailscale serve` being able to reach that port.

Step branches are cut fresh off the branch they target and merged back into it. For
stacked PRs use a regular merge or rebase, **not squash**.

## Build, run, test

```bash
mvn -q package -DskipTests           # build target/app.jar
MONGO_URI=mongodb://localhost:27017 ANTHROPIC_API_KEY=dummy \
CREDENTIAL_IDENTITY_SEED=$(openssl rand -base64 32) java -jar target/app.jar

mvn -B clean test                    # unit tests only
```

Any 32 bytes is a valid seed, so `openssl rand` is fine when the server is being poked at with
curl. It is **not** fine when the Expo app is pointed at it: the app pins the matching public key,
which only `IdentityKeyTool generate` prints. Use that, and put both halves where they belong.

Integration tests are `*IT` classes and Surefire's default includes **skip** them —
they must be named explicitly, and they need a container runtime:

```bash
DOCKER_HOST=unix:///run/user/1000/podman/podman.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -B test -Dtest='CucumberIT,UserRepositoryIT,ActivityLogRepositoryIT,SessionTokenServiceIT,InviteCodeRepositoryIT,SessionRepositoryIT,LlmQuotaServiceIT'
```

Local Mongo: `podman run -d --name otj-mongo -p 27017:27017 mongo:8`.

**The container runtime here is Podman, not Docker** — check `podman info`, and use
`podman`/`podman-compose` in anything you write.

CI only runs on `master` (push and PR), so run the gate locally on step branches.

## Environment

Read from the environment (`.env` locally, via `dotenv-java`; `~/otj-hours-api.env`
on the prod box):

- `MONGO_URI` — defaults to `mongodb://localhost:27017`
- `ANTHROPIC_API_KEY` — **required to boot**: `AnthropicOkHttpClient.fromEnv()`
  throws at construction, so the server will not start without it, even for flows
  that never touch the LLM. `dummy` is fine for auth work.
- `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL` — for the in-progress move to an
  OpenAI-compatible provider (OpenRouter/Groq/NIM) on the `use-free-ai` branch; not
  yet read by `AppModule` on `master`.
- `CREDENTIAL_IDENTITY_SEED` — **required to boot the api role**: the base64 32-byte Ed25519 seed
  that signs the credential key announcement. Mint it with
  `java -cp target/app.jar com.github.grepHammerspace.crypto.IdentityKeyTool generate`, which
  prints this and the public key the Expo app has to pin. The two are a pair — a mismatch fails
  every submit. Not needed by the admin role, which never builds the key ring.
- `APP_ROLE` — `api` (default) or `admin`; selects the entrypoint.
- `ADMIN_ALLOWED_LOGINS` — comma-separated tailnet logins allowed to mint/revoke invite
  codes. Admin process only. Unset means nobody.
- `ADMIN_PORT` — defaults to 8946.

`.env` is gitignored and contains real secrets — never print, commit, or echo it.

## Conventions and gotchas

- **Login is rate limited per username**, 10 attempts per 15 minutes, via `auth/RateLimiter` — an
  in-memory sliding window, correct because there is exactly one app instance. Things to preserve:
  - The check sits **above `findByAppUsername`**, so an unknown username is limited exactly like a
    real one and a 429 is never an account-existence oracle. Moving it below would create one.
  - It is also above the bcrypt verify, which is the cost being shed.
  - **Successes count too.** A flood of valid logins is still a flood, and counting only failures
    would leave an attacker holding a correct password an unmetered channel.
  - Keys are truncated and idle keys are swept, because the key is the *submitted* username and an
    attacker can otherwise grow the map one invented name at a time.
  - `tryAcquire` rejects a window longer than `RateLimiter.MAX_WINDOW`; the sweep is global and
    prunes against that bound, so a longer window would have its live counters collected.
- **There is still no signup rate limit in the app**, and the reasoning has changed. The app
  cannot key one: every request reaches it from `127.0.0.1` — through Caddy or through
  `tailscale serve` — and it does not read `X-Forwarded-For`, so it has no forgery-resistant
  per-client key. Keying on the invite code would let an attacker lock a legitimate invitee out
  of the only code they have, and a global limit would let anyone deny signup to everybody.
  Invite codes carry 40 bits, which `InviteCodeGenerator`'s javadoc rightly calls far past
  online guessing.
  What *has* changed is that the cap now exists a layer out: **Caddy rate limits
  `/auth/signup` and `/auth/session` per source IP** (10/min and 250/day — see
  `deploy/prod/Caddyfile`). If you ever want the limit inside the app instead, the prerequisite
  is trusting `X-Forwarded-For` from the loopback Caddy hop *only*, and that is a deliberate
  change to the trust model, not a one-line addition.
- **`log-activities` is capped at 10 LLM calls per user per day** by `quota/LlmQuotaService`,
  counted in the `llmQuota` collection, one document per user per day. Things to preserve:
  - The check sits after the 400s and before the model call, so a malformed request never spends
    quota and a refused one never reaches Anthropic. Content diffing is gone, so every request
    that gets past those 400s does cost a call — there is no resubmit-is-free path to lean on.
  - `tryConsume` is a single atomic `findOneAndUpdate` + `$inc` + upsert. **An over-limit call
    still increments** — that is what keeps it one round trip with no read-modify-write, so the
    count cannot be raced. The stored number is calls *attempted*; clamp it before showing it.
  - The upsert plus the unique `{userId, date}` index means two concurrent first calls race and
    one sees a duplicate key; there is a single bounded retry for exactly that.
  - The day is **UTC**, deliberately unlike `logActivtiesWithLlmHelp`'s system-zone
    `LocalDate.now()` for the date stamped on rows. UTC is DST-free and cannot silently give a
    23- or 25-hour quota window. They disagree for an hour under BST; both are right.
  - Two different 429s can come from this endpoint — quota, and the upstream Anthropic rate
    limit. Only the quota one carries `Retry-After`, which is how a client tells them apart.
  - **Any feature whose scenarios POST `/log-activities` must reset the quota in its
    `Background:`.** The suite's Mongo is not wiped between scenarios and `llm_quota.feature` sets
    the counter to its limit, so without the reset those scenarios start over quota and 429.
- Nothing logs OneAdvanced credentials, MFA codes, Microsoft flow tokens, cookie values or
  learner IDs. The app's own `userId` is the most that should reach a log line. This is
  enforced, not merely intended:
  - `web/SafeUrl` strips query strings from every URL the drivers log or put in an exception —
    Keycloak carries the username in `login_hint` and its own `session_code`, and driver
    exception messages are echoed to the caller.
  - The drivers log named fields (`Success`, `ResultValue`, HTTP status), never a raw upstream
    request or response body — those carry Microsoft's `FlowToken`, which is bearer-equivalent.
  - `logback.xml` deliberately does **not** pin the drivers to DEBUG. It used to, which is what
    put those tokens in the production log.
  - `ServerHooks` captures every log event at TRACE and fails any scenario in which a sentinel
    credential appears — in the message, the arguments, or the throwable chain. Adding a leak
    breaks the suite.
- Error bodies are `ApiError` records, never hand-built JSON strings, and never carry a driver's
  `e.getMessage()`. One fixed constant per failure, in the `AuthResource` style.
- `AuthResource` verifies against a dummy bcrypt hash on unknown usernames so that
  "no such user" and "wrong password" take the same time; invite rejections use one
  message for invalid/used/expired alike. Don't "helpfully" make these more specific.
- The shade plugin strips `META-INF/*.SF|DSA|RSA|EC` — signed bcrypt jars otherwise
  make the JVM reject the uber-jar. Don't remove that filter.
- Prod has **exactly two inbound ports, 80 and 443, and both terminate at Caddy** — not at the
  app. Caddy handles TLS and per-IP rate limiting, then proxies to `127.0.0.1:8945`. Shell
  access is still SSM Session Manager (no SSH port), and the admin API is still tailnet-only
  on 8443. **Both Quadlets still bind loopback, and that must not change**: it is the only
  reason `AdminIdentityFilter` can believe the `Tailscale-User-Login` header, and the only
  reason the public route cannot reach 8946. Read `deploy/prod/README.md` and `deploy/README.md`
  before touching networking.
- `dependency-reduced-pom.xml` is a shade-plugin artifact, not a file to edit.
