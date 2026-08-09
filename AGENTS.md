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
  llm/                    LlmService/LlmServiceImpl, ContentDiffer (old vs new notes),
                          LlmResult / LlmParseError, exception/ hierarchy
  stateStore/             UserStateStore — ConcurrentHashMap of userId → UserState,
                          keeps a logged-in Driver alive between prepare and MFA calls
  bind/                   AppModule + AppComponent (main API),
                          AdminModule + AdminComponent (admin API)
  web/                    Driver interface + implementations
    OtjDriver               direct OneAdvanced/Keycloak discover login
    AzureIdDriver           QMUL Azure AD federation path; bypasses discover with a
                            hand-built PKCE flow, then Microsoft Authenticator push
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
                          README.md (Tailscale trust model), prod/ (deploy.sh + Quadlet template)
scripts/                  (tailscale branch only) `otj` CLI for hand-testing the API
```

Docs worth knowing about (root): `deployment-checklist.md` (AWS bring-up),
`mobile-signup-runbook.md` (Expo client wiring), `steps-04-08-implementation-plan.md`
and `auth-multiuser-plan.html` (the multi-user rollout plan), `notes.md` (idea backlog).

## Two processes, one image

The jar has two entrypoints, selected by `APP_ROLE` in `docker/start.sh`:

| Role | Main class | Port | Graph | Exposure |
|---|---|---|---|---|
| `api` (default) | `Main` | 8945 | `AppComponent` | `tailscale serve --https=443` |
| `admin` | `admin.AdminMain` | 8946 | `AdminComponent` | `tailscale serve --https=8443` |

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
| POST | `/auth/session` | `{username, password}` → 200 `{token}` |
| DELETE | `/auth/session` | `Authorization: Bearer …` → 204 |
| GET | `/health` | 200 |

Authenticated (`Authorization: Bearer …`, all under `/otj-services`):
`GET /prepare-browser`, `POST /register`, `POST /log-activities`,
`DELETE /delete-last-row`, `DELETE /reset-notes`, `POST /submit-with-mfa`,
`GET /azure-id/prepare`, `GET /azure-id/complete`.

Admin API (separate process/port, tailnet identity instead of bearer tokens):

| Method | Path | Result |
|---|---|---|
| POST | `/admin/invites` | `{note?, expiresInDays?}` → 201 `{code, status, expiresAt, …}` |
| GET | `/admin/invites` | 200, newest first, `status` ∈ ACTIVE/USED/REVOKED/EXPIRED |
| DELETE | `/admin/invites/{code}` | 204; 404 unknown; 409 already claimed |

Notes:
- Tokens are 32 random bytes, base64url; only the SHA-256 hash is stored. Sliding
  30-day expiry, refreshed on resolve when less than half the window remains.
- `userId` is a server-minted UUID — the client never sends or receives it; every
  authenticated handler derives it from the token via `SecurityContext`.
- Signup is **invite-gated**; codes are minted through the admin API above.
- Revocation sets `revokedAt` *and* pulls `expiresAt` back to now, so a revoked code dies
  through the existing claim filter. `InviteCodeRepository.claim` is a single atomic
  `findOneAndUpdate` and deliberately knows nothing about revocation — leave it that way.
- Login is a two-phase dance because MFA codes live ~30 s: `prepare*` opens and parks
  the session in `UserStateStore`, then `submit-with-mfa` / `azure-id/complete`
  finishes it.

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

`staging` is the working integration branch for the in-flight multi-user auth
rollout (steps 01–08). It is well ahead of `master`: session tokens, bcrypt users,
invite-gated signup and `SessionRepository` all live there and have not reached
`master` yet. `master` still carries the older `tailscale/TailscaleIdentity*` classes
that `staging` removed — so if a file exists in one branch and not the other, check
which side you are on before concluding something is missing.

Step branches are cut fresh off `staging` and merged back into it. For stacked PRs
use a regular merge or rebase, **not squash**.

## Build, run, test

```bash
mvn -q package -DskipTests           # build target/app.jar
MONGO_URI=mongodb://localhost:27017 ANTHROPIC_API_KEY=dummy java -jar target/app.jar

mvn -B clean test                    # unit tests only
```

Integration tests are `*IT` classes and Surefire's default includes **skip** them —
they must be named explicitly, and they need a container runtime:

```bash
DOCKER_HOST=unix:///run/user/1000/podman/podman.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -B test -Dtest='CucumberIT,UserRepositoryIT,ActivityLogRepositoryIT,SessionTokenServiceIT,InviteCodeRepositoryIT,SessionRepositoryIT'
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
  yet read by `AppModule` on `staging`.
- `APP_ROLE` — `api` (default) or `admin`; selects the entrypoint.
- `ADMIN_ALLOWED_LOGINS` — comma-separated tailnet logins allowed to mint/revoke invite
  codes. Admin process only. Unset means nobody.
- `ADMIN_PORT` — defaults to 8946.

`.env` is gitignored and contains real secrets — never print, commit, or echo it.

## Conventions and gotchas

- Nothing logs passwords, MFA codes, or raw tokens. Username/userId is the most that
  should ever reach a log line. Keep it that way when adding logging.
- `AuthResource` verifies against a dummy bcrypt hash on unknown usernames so that
  "no such user" and "wrong password" take the same time; invite rejections use one
  message for invalid/used/expired alike. Don't "helpfully" make these more specific.
- The shade plugin strips `META-INF/*.SF|DSA|RSA|EC` — signed bcrypt jars otherwise
  make the JVM reject the uber-jar. Don't remove that filter.
- Prod has **no inbound ports**. Admin is SSM Session Manager; app traffic arrives via
  `tailscale serve` proxying to `127.0.0.1:8945`. The identity header trust model is
  only valid because of that loopback binding — see `deploy/README.md` before touching
  networking.
- `dependency-reduced-pom.xml` is a shade-plugin artifact, not a file to edit.
