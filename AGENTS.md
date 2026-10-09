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
`/home/asad/Projects/personal/java/otj/otj-mobile`. The mobile client is the only
intended consumer: it holds the bearer token, renders the notes editor, and calls
the endpoints below; the API base URL comes from `EXPO_PUBLIC_API_URL`. When changing an
endpoint's shape, assume there is an Expo client that has to change with it.

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
    CryptoResource          /otj-services/crypto/public-key, the key to seal credentials to
    CorsFilter              lets the app's web build (a PWA on Vercel) call from a browser
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
  crypto/                 sealed OneAdvanced credentials (credential-encryption-spec.md)
  db/                     repositories (User, Session, ActivityLog, InviteCode)
    model/                  User, Session, ActivityLog, InviteCode documents
  llm/                    LlmService/LlmServiceImpl, LlmResult, ParsedActivities (the
                          structured-output schema), LlmException / LlmRateLimitException
  stateStore/             LoginSessions — ConcurrentHashMap of userId → LoginSession,
                          keeps a logged-in Driver alive between prepare and MFA calls
    LoginSession, LoginFlow  one parked login: which flow, its driver, its background
                             login future, and when it started (5 min TTL)
  bind/                   AppModule + AppComponent (main API),
                          AdminModule + AdminComponent (admin API)
  web/                    Driver interface + implementations
    OneAdvancedDriver       base of both real drivers: cookie jar, browser-like requests, and
                            the one submitPendingOtjs that posts the queued rows
    OtjDriver               direct OneAdvanced/Keycloak discover login
    AzureIdDriver           QMUL Azure AD federation path; bypasses discover with a
                            hand-built PKCE flow, then Microsoft Authenticator push
    Keycloak, AzurePush     Dagger qualifiers selecting between the two drivers, so the
                            resource names neither and tests can bind fakes
    SafeUrl                 strips query strings before a URL reaches a log or exception
    LoginChainException     a failure the driver detected itself; its message is safe to log
    PrepareResult, OtjSubmitResult

src/main/resources/       app.properties, llm_prompt.txt (system prompt), logback.xml
src/test/java/            unit tests, *IT integration tests, integration/ Cucumber glue
src/test/resources/features/  Cucumber .feature files

aws/                      CDK (TypeScript): OtjServicesStack (VPC + EC2 + ECR),
                          GithubOidcStack (CI deploy role). node_modules/ is committed-ish noise —
                          ignore it when searching.
.github/workflows/deploy.yml  build-and-test on PR; on push to master, every check again
                          (calls box.yml), then image → ECR, cdk deploy, and otj-converge on the
                          box through .github/scripts/box-run.sh
.github/workflows/box.yml    box-static (ansible-lint, syntax, shellcheck, haproxy -c) and
                          box-rehearsal: applies the playbook to a runner twice, then tests
                          rate limits, loopback-only ports and logs through HAProxy
.github/workflows/{converge,converge-check,rollback,restart}.yml
                          the ops buttons (workflow_dispatch); all share the `box` concurrency
                          group with deploy.yml's deploy job
docker/                   otjService.Dockerfile, start.sh
deploy/                   self-host path: podman-compose.yaml, bootstrap.sh, shell.nix,
                          README.md (Tailscale trust model)
  ansible/                the AWS box as code: site.yml, roles (base, otjapp, tailscale, edge,
                          app, observability, verify), group_vars (nothing secret), ci-vars.yml (rehearsal
                          only). README.md: how it runs, secrets, the edge, rate-limit rationale,
                          the shared-IP problem, Cloudflare ranges, reading logs
  haproxy/                haproxy.cfg (the public edge) and cloudflare-ips.lst
  bin/otj-converge        the one script on the box: pull a SHA's image, run its playbook
```

Docs worth knowing about: `README.md` (the architecture diagrams), `deploy/ansible/README.md`
(the box: how it runs, secrets, the edge, building a new one, reading logs and metrics),
`aws/README.md` (the stack), `notes.md` (idea backlog and open work). Finished plans and API specs
are deleted once they land; git history has them.

## Two processes, one image

The jar has two entrypoints, selected by `APP_ROLE` in `docker/start.sh`:

| Role | Main class | Port | Graph | Exposure (AWS box) |
|---|---|---|---|---|
| `api` (default) | `Main` | 8945 | `AppComponent` | **public** — Cloudflare → HAProxy on 443 → `127.0.0.1:8945`; also `tailscale serve --https=8444` |
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
| GET | `/pending` | → 200 `PendingResponse` |
| PUT | `/pending/{id}` | `UpdateActivityRequest` → 200 `PendingActivity`, or 404 |
| DELETE | `/pending/{id}` | → 204 |

The two prepare endpoints carry the user's **OneAdvanced** credentials, **sealed to this
process** (see "Sealed credentials" below). They are **not stored**: the multi-user rollout
deleted the encrypted-at-rest copy, so they have to arrive per request. They exist as a
local for the length of the call, go straight into `Driver.prepare`, and never reach a log line or
a response body. Do not add a field, a cache, or a "remember me" for them.

`prepare*` answers `status` of `login_complete`, `otp_required` or `push_sent`; `challengeNumber`
is present only for a Microsoft number match. The learner ID is **server-side** and is never
accepted on these bodies: Jackson rejects the unknown property with a 400 on the envelope and on
the JSON sealed inside it, and `prepare_and_submit.feature` pins both. The MFA code is not sealed:
it dies in ~30 s, and sealing it would put a key fetch in front of the most time-critical call.

**A failed OneAdvanced login is never a 401.** The app signs out on a 401 carrying
`"code": "invalid_token"`, which only `AuthenticationFilter` sends, and older app builds sign out
on *any* 401. Prepare used to answer 401 for every driver failure, so a mistyped OneAdvanced
password, or OneAdvanced hiccuping, signed people out of this app. Now a `LoginChainException`
(the chain didn't land where a good login does, which is what a wrong password looks like) is
**422** with the "check the username and password" message, and any other failure is **502**
"try again". Don't give either message to the other case.

Authenticated, outside `/otj-services` — the account itself, on `AccountResource`:

| Method | Path | Body → Result |
|---|---|---|
| GET | `/auth/me` | → 200 `{username, learnerId}` |
| PATCH | `/auth/me` | `{learnerId}` → 200 `{username, learnerId}` |

**These are not on `AuthResource`.** That class carries no `@Authenticated` annotation on purpose
— its endpoints are the ones you reach before holding a token — and the annotation binds per
class, so the two that need a token live in their own class under the same URL prefix.

Admin API (separate process/port, tailnet identity instead of bearer tokens):

| Method | Path | Result |
|---|---|---|
| POST | `/admin/invites` | `{note?, expiresInDays?}` → 201 `{code, status, expiresAt, …}` |
| GET | `/admin/invites` | 200, newest first, `status` ∈ ACTIVE/USED/REVOKED/EXPIRED |
| DELETE | `/admin/invites/{code}` | 204; 404 unknown; 409 already claimed |

## Sealed credentials

Cloudflare terminates TLS, so a request body is plaintext inside it. The OneAdvanced password is
the user's institutional credential, so the prepare endpoints take a `SealedEnvelope` that only
this process can open. Wire format and key handling: `credential-encryption-spec.md`. Code:
`crypto/` (`CredentialKeyRing`, `Hkdf`, `RawKeys`, `IdentityKeyTool`) and `api/CryptoResource`.

Things not to undo:

- **The pinned identity key is the point.** The key announcement crosses the same Cloudflare hop,
  so it is signed with an Ed25519 key whose public half is built into the app. Never add that
  public key to the response: verifying against a key from the same channel proves nothing.
- **No plaintext fallback, and no flag for one.** A server that still accepted the old body would
  leave the plaintext path open to the party being defended against.
- **The api role won't boot without `CREDENTIAL_IDENTITY_SEED`.** It fails closed, like
  `AdminAllowlist`: a generated key would sign announcements no released app can verify.
- **Every decryption failure is `undecryptable`**, and no reason quotes the envelope; telling them
  apart is an oracle. `unknown_key` is the one code the client acts on, by re-sealing once.
- **`iat` is not a replay defence.** It bounds how long a captured envelope is useful.

Notes:
- Tokens are 32 random bytes, base64url; only the SHA-256 hash is stored. Sliding
  30-day expiry, refreshed on resolve when less than half the window remains.
- `userId` is a server-minted UUID — the client never sends or receives it; every
  authenticated handler derives it from the token via `SecurityContext`.
- Signup is **invite-gated**; codes are minted through the admin API above.
- `PUT /pending/{id}` replaces the five editable fields of one unposted row and answers with
  the updated `PendingActivity`. Ownership and `posted: false` are in the Mongo filter, not a
  check after the read, so unknown / someone else's / already-posted all give the same 404 as
  the delete endpoint. Its rules are hand-written rather than with `@Valid`, because only an
  `{"error": "..."}` body reaches the mobile user as a real message.
- **A row is stored only when complete**: a past weekday, a start time inside 09:00–18:00, a
  non-zero duration and a description of at most 500 characters, all defined once in
  `db/model/ActivityRules`. The LLM path drops an incomplete entry as a parse error, PUT answers
  400, `ActivityLogRepository` throws as the backstop, and `OneAdvancedDriver` won't post a row
  that breaks a rule (one stored before the rule existed). `log-activities` also refuses any line
  over 500 characters, before the quota is spent. There is no "no start time" row any more: one
  used to be saved beside the model's warning, and fixing it in the app left a near-duplicate.
  Test fixtures need weekday dates; the suite must pass when run at a weekend.
- Revocation sets `revokedAt` *and* pulls `expiresAt` back to now, so a revoked code dies
  through the existing claim filter. `InviteCodeRepository.claim` is a single atomic
  `findOneAndUpdate` and deliberately knows nothing about revocation — leave it that way.
- Login is a two-phase dance because MFA codes live ~30 s: `prepare*` opens and parks
  the session in `LoginSessions`, then `submit-with-mfa` / `azure-id/complete`
  finishes it. The two halves are **not interchangeable** — a `LoginSession` records which
  `LoginFlow` it belongs to and the wrong complete gets a 409. Without that check an Azure
  session would be accepted by `submit-with-mfa`, and `AzureIdDriver.completeMfa` ignores the
  token it is given, so it would start a second Microsoft poll racing the first.
- A parked session expires after `LoginSession.TTL` (5 min) and is dropped as soon as it is
  spent. It holds live OneAdvanced cookies, so it must not outlive its use. Replacing or dropping
  one cancels its Azure poll, which stops only because the poll runs on an executor whose futures
  interrupt: `CompletableFuture.cancel(true)` never interrupts, and the poll used to run on for
  two minutes after its session was gone.
- The learner ID used when posting is read from the **account at submit time**, not from the
  rows being posted, so a correction through `PATCH /auth/me` also rescues activities already
  queued. The value stored on each row is left alone as a record of what was intended when it
  was written.

## Branches

Two **protected** branches, both deployment targets:

- **`master`** — the AWS deployment. Pushes here run every check again, then deploy
  (`deploy.yml`): image build → ECR, CDK (`OtjServicesStack`), and `otj-converge <sha>` on
  the box, which applies that release's app image and box config together. This is the
  hosted instance backing the mobile app. Only master's newest commit deploys; an older
  run that finishes second skips its converge rather than rolling the box back.
  On 2026-09-25 a merge replaced the EC2 instance (the AMI was re-resolved on every
  deploy) and terminated the hand-provisioned box; it was rebuilt with Ansible and back
  on 2026-09-27. The AMI is now pinned and a stack policy refuses instance replacement
  (`aws/README.md`). Don't unpin it or loosen the policy as a side effect of other work.
- **`tailscale`** — the **self-hosted, single-user** copy, run with `deploy/bootstrap.sh` +
  `podman-compose` on the owner's machine and reached only through `tailscale serve`. It is
  `master` with everything multi-user and hosted removed: no auth (one constant account), no
  admin API, no invites or sessions, no credential sealing (prepare takes plain
  `{username, password}`), no LLM quota, and no `aws/`, Ansible or HAProxy. The paths and
  response shapes match `master`'s, so the Expo app's self-hosted mode uses the same screens.
  Sync it by merging `master` into a branch off `tailscale`; its own `AGENTS.md` says how to
  resolve the conflicts that brings.

Do not push directly to either; open a PR.

The multi-user auth rollout (steps 01–07) landed on `master` through the old `staging`
branch (PR #21, 2026-08-16), which has since been deleted: session tokens, bcrypt users,
invite-gated signup and `SessionRepository` are all on `master`, and the old
`tailscale/TailscaleIdentity*` classes are gone. `master` is the source of truth.

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

A random seed is fine for curl. Pointing the Expo app at the server needs the pair from
`IdentityKeyTool generate`, since the app pins the public half.

Integration tests are `*IT` classes and Surefire's default includes **skip** them —
they must be named explicitly, and they need a container runtime:

```bash
DOCKER_HOST=unix:///run/user/1000/podman/podman.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -B test -Dtest='CucumberIT,UserRepositoryIT,ActivityLogRepositoryIT,SessionTokenServiceIT,InviteCodeRepositoryIT,SessionRepositoryIT,LlmQuotaServiceIT'
```

Local Mongo: `podman run -d --name otj-mongo -p 27017:27017 mongo:8`.

The box's config (`deploy/ansible/`) has its own checks, in `.github/workflows/box.yml`. Locally:

```bash
cd deploy/ansible && ansible-lint && ansible-playbook site.yml --syntax-check -e image_tag=ci
shellcheck deploy/bin/otj-converge deploy/ansible/roles/app/files/otj-render-env
```

The rehearsal (applying the playbook to a real systemd host, twice) only runs in CI. Write the
roles for ansible-core 2.16, the apt version the box runs, not whatever is newest.

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
- `CREDENTIAL_IDENTITY_SEED` — **required to boot the api role**: the 32-byte Ed25519 seed that
  signs the credential key announcement. `IdentityKeyTool generate` prints it together with the
  public key the Expo app pins; a mismatch fails every submit. Parameter Store:
  `/otj/prod/credential-identity-seed`. The admin role doesn't need it.
- `APP_ROLE` — `api` (default) or `admin`; selects the entrypoint.
- `ADMIN_ALLOWED_LOGINS` — comma-separated tailnet logins allowed to mint/revoke invite
  codes. Admin process only. Unset means nobody.
- `ADMIN_PORT` — defaults to 8946.

`.env` is gitignored and contains real secrets — never print, commit, or echo it.

## Conventions and gotchas

- **CORS is for the app's web build only, and only for exact origins.** `api/CorsFilter` lists the
  PWA's production and preview addresses on Vercel plus `expo start --web` in `ALLOWED_ORIGINS`.
  Adding an origin means a code change and a deploy, on purpose. Never put a pattern there: anyone
  can deploy to `*.vercel.app`. Things to preserve:
  - It is **`@PreMatching`**, so a preflight is answered before `AuthenticationFilter`. Browsers
    send `OPTIONS` without the `Authorization` header, and a 401 there blocks the real call.
  - The response half stamps **every** response, 401s included. The app has to be able to read a
    401 to notice an expired session; without the header, the browser reports a network error.
  - No `Allow-Credentials`: there are no cookies, and the bearer token is the only credential.
  - Responses HAProxy builds itself (its 429s and 413) carry no CORS headers, so a browser sees them
    as network errors. That is acceptable for the PWA; don't go copying the allowlist into HAProxy
    for it.
  - The admin server doesn't register it. Nothing calls that from a browser.
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
  cannot key one: every request reaches it from `127.0.0.1` — through HAProxy or through
  `tailscale serve` — and it does not read `X-Forwarded-For`, so it has no forgery-resistant
  per-client key. Keying on the invite code would let an attacker lock a legitimate invitee out
  of the only code they have, and a global limit would let anyone deny signup to everybody.
  Invite codes carry 40 bits, which `InviteCodeGenerator`'s javadoc rightly calls far past
  online guessing.
  What *has* changed is that the cap now exists a layer out: **HAProxy rate limits
  `/auth/signup` and `/auth/session` per source IP** (10/min and 250/day — see
  `deploy/haproxy/haproxy.cfg`). If you ever want the limit inside the app instead, the
  prerequisite is trusting `X-Forwarded-For` from the loopback HAProxy hop *only*, and that is a
  deliberate change to the trust model, not a one-line addition.
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
    An unexpected page is described by `AzureIdDriver.describePage`: `pgid`, form count, the
    redacted `urlPost` and a numeric error code, never its text.
  - Microsoft's "proof-up" interrupt (`pgid=ConvergedProofUpRedirect`, posting to
    `mysignins.microsoft.com/.../registerMfaMethods`) means the organisation wants security info
    registered before it lets the user in. `rejectProofUp` checks for it before the MFA page and
    after `ProcessAuth`, throws `SecurityInfoRequiredException`, and the resource answers **409**
    pointing the user at mysignins.microsoft.com. It is not a failed login, so it must never get
    the "check the username and password" message. The page is logged with
    `AzureIdDriver.configKeys`: its `$Config` key **names**, sorted, with no values, since the
    values carry `sFT` and the user's masked phone and email. The names are there to show whether
    Microsoft offers a skip link the driver could follow instead.
  - The resource logs a driver failure's message only for a `LoginChainException`, whose
    message the driver builds from those same safe parts. Any other exception is logged by type
    alone: an OkHttp or Jackson message can quote the upstream body. Throw `LoginChainException`
    for a failure the driver detects, and keep its message to redacted URLs and Microsoft's codes.
  - `logback.xml` deliberately does **not** pin the drivers to DEBUG. It used to, which is what
    put those tokens in the production log.
  - `ServerHooks` captures every log event at TRACE and fails any scenario in which a sentinel
    credential appears — in the message, the arguments, or the throwable chain. Adding a leak
    breaks the suite.
  - **The app's and the edge's logs are copied to Grafana Cloud** by Alloy
    (`deploy/ansible/roles/observability`), so a leak now reaches a third party, not just the box.
    Client addresses leave the box only truncated: HAProxy's `log-format` puts the /24 or /48
    first and the full address last as `full_src=`, which Alloy deletes (and drops any line still
    carrying it). Keep that field's name and position, and never capture the `Authorization`
    header. Where to read the logs: `deploy/ansible/README.md`, "Reading the logs".
- Error bodies are `ApiError` records, never hand-built JSON strings, and never carry a driver's
  `e.getMessage()`. One fixed constant per failure, in the `AuthResource` style.
- `AuthResource` verifies against a dummy bcrypt hash on unknown usernames so that
  "no such user" and "wrong password" take the same time; invite rejections use one
  message for invalid/used/expired alike. Don't "helpfully" make these more specific.
- The shade plugin strips `META-INF/*.SF|DSA|RSA|EC` — signed bcrypt jars otherwise
  make the JVM reject the uber-jar. Don't remove that filter.
- Prod has **exactly one inbound port, 443, from Cloudflare's ranges only, and it terminates at
  HAProxy** — not at the app. HAProxy handles TLS and per-IP rate limiting, then proxies to
  `127.0.0.1:8945`. Shell access is SSM Session Manager (no SSH port, sshd masked), and the admin
  API is tailnet-only on 8443. **Both Quadlets bind loopback, and that must not change**: it is
  the only reason `AdminIdentityFilter` can believe the `Tailscale-User-Login` header, and the
  only reason the public route cannot reach 8946. The `PublishPort=` lines are literals for that
  reason, and the playbook's verify role fails a deploy that leaves anything but 443 on a
  wildcard address. Read `deploy/ansible/README.md` and `deploy/README.md` before touching
  networking.
- **Don't change the box by hand; change `deploy/ansible/` and merge.** A converge undoes hand
  changes. Ansible never reads a secret (they reach the containers through `otj-render-env`
  at unit start), and the origin certificate is the one thing installed by hand and only
  `stat`ed. Any new task that could print a secret needs `no_log: true`: the deploy output
  goes to CloudWatch and the Actions job.
