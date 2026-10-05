# AGENTS.md

Context for coding agents working on the **`tailscale` branch**. Read this first.

## What this is

`otjServices` is a Java REST API that automates the login and submission flow for **OTJ
(off-the-job) activity logs** on OneAdvanced's cloud-education platform
(`education.oneadvanced.com`):

1. The user writes free-text notes about what they did.
2. An LLM turns them into structured activity-log entries (date, duration, description).
3. The service logs into OneAdvanced on the user's behalf (holding the session open across the
   ~30 s MFA window) and POSTs the entries to the platform's activity-log JSON API.

**This branch is the self-hosted, single-user copy.** It runs on the owner's own machine under
`podman-compose`, reached only through `tailscale serve` on their tailnet. `master` is the hosted,
multi-user service (invites, bearer sessions, sealed credentials, AWS, HAProxy, Ansible). This
branch deliberately has none of that.

The client is the same **Expo** app (`/home/asad/Projects/personal/java/otj/otj-mobile`). Its
sign-up screen has a "Hosting the backend yourself?" link. That stores an `https://*.ts.net` base
URL on the phone, skips sign-in, and sends every call there. When changing an endpoint's shape,
the app has to change with it, and it must keep working against `master` as well.

## Stack

- **Java 25**, Maven, shaded uber-jar (`target/app.jar`, main class
  `com.github.grepHammerspace.Main`)
- **Jersey 3.1 (JAX-RS)** on **Grizzly**, port **8945**
- **Dagger 2** for DI (`bind/AppModule`, `bind/AppComponent`)
- **MongoDB** (`otjdb`), containerised next to the app
- **OkHttp + jsoup** for driving the OneAdvanced/Azure AD login chains
- **Anthropic Java SDK** for the notes → activity-log parsing
- **JUnit 5 + Cucumber + Testcontainers** for tests

## Directory structure

```
src/main/java/com/github/grepHammerspace/
  Main.java               entry point; creates the one account, starts the server
  SingleUser.java         the constant userId / username every handler uses
  ServerBootstrap.java    Grizzly/Jersey setup (thread-per-task executor: login/submit block)
  api/                    JAX-RS resources
    OtjServicesResource     /otj-services: all automation endpoints
    AccountResource         /auth/me: read and set the learner ID
    HealthResource          /health
    dto/                    request/response records
  db/                     UserRepository, ActivityLogRepository; model/
  llm/                    LlmService/LlmServiceImpl, LlmResult, exception/
  stateStore/             LoginSessions: the parked login between prepare and complete
  bind/                   AppModule + AppComponent
  web/                    Driver + OtjDriver (Keycloak), AzureIdDriver (Azure push), SafeUrl

src/main/resources/       app.properties, llm_prompt.txt (system prompt), logback.xml
src/test/java/            unit tests, *IT integration tests, integration/ Cucumber glue
src/test/resources/features/  Cucumber .feature files

docker/                   otjService.Dockerfile, start.sh
deploy/                   podman-compose.yaml, bootstrap.sh, shell.nix, README.md (the
                          self-host guide and its trust model)
.env.example              every variable the stack needs
.github/workflows/build.yml  unit tests + jar + image build on push/PR to tailscale
```

## API surface

There is no authentication. Every request acts on the single account.

| Method | Path | Body → Result |
|---|---|---|
| GET | `/health` | 200 |
| GET | `/auth/me` | → 200 `{username, learnerId}` (`learnerId` is null until set) |
| PATCH | `/auth/me` | `{learnerId}` → 200 `{username, learnerId}` |
| POST | `/otj-services/prepare-browser` | `{username, password}` → 200 `{status, message}` |
| POST | `/otj-services/azure-id/prepare` | `{username, password}` → 200 `{status, message, challengeNumber?}` |
| POST | `/otj-services/submit-with-mfa` | `{mfaCode}` → 200/207/502 `{status, posted, failed}` |
| GET | `/otj-services/azure-id/complete` | → 200/207/408/502 `{status, posted, failed}` |
| POST | `/otj-services/log-activities` | `{content}` → 200 `ActivityLogResponse` |
| GET | `/otj-services/pending` | → 200 `PendingResponse` |
| PUT | `/otj-services/pending/{id}` | `UpdateActivityRequest` → 200 `PendingActivity`, or 404 |
| DELETE | `/otj-services/pending/{id}` | → 204 |

The paths and response shapes match `master`'s, so the app's screens work against either. The
differences:
- The prepare bodies are plain `OneAdvancedCredentials` rather than a `SealedEnvelope`.
- There is no `/auth/signup`, `/auth/session` or `/otj-services/crypto/public-key`.
- There is no daily LLM quota.

## Why no auth, and why plain credentials

- **The trust boundary is the tailnet plus loopback.** Both containers publish on `127.0.0.1`
  only, so `tailscale serve` is the only way in. Publishing `8945` wider, or adding
  `tailscale funnel`, hands the owner's OneAdvanced account to whoever finds it. `deploy/README.md`
  tells owners who share a tailnet to restrict it with ACLs.
- **CORS widens that boundary to three web origins, and no further.** `api/CorsFilter` (shared
  with `master`) lets the app's web build, a PWA on Vercel, call this server from a browser on the
  tailnet. With no auth here, any page served from an origin in `ALLOWED_ORIGINS` can drive the
  server from such a browser, so that set is part of the trust boundary: exact origins only, never
  a pattern (anyone can deploy to `*.vercel.app`), and adding one is a decision, not a convenience.
  It is `@PreMatching`, and the response half stamps error responses too, so the app can read
  them.
- **Credentials are not sealed here.** Sealing exists on `master` because Cloudflare terminates
  TLS in front of it. Here TLS ends at `tailscale serve` on the owner's own machine. Sealing also
  couldn't work: the store app pins the identity key paired with the hosted seed. The app skips
  sealing *only* when a self-hosted URL is set.
- The credentials are still **not stored**. They exist as a local for the length of the call, go
  straight into `Driver.prepare`, and never reach a log line or a response body. Don't add a
  field, a cache, or a "remember me" for them.

## Behaviour worth knowing

- **One account, created on boot.** `UserRepository.ensureSingleUser()` is an upsert with
  `$setOnInsert` only, so a restart never clobbers the learner ID. Every repository call uses
  `SingleUser.USER_ID`. The `userId`/`tailscaleUserId` fields stay on the documents so the schema
  matches `master`'s.
- **The learner ID is read from the account at submit time,** not from the rows being posted. A
  correction through `PATCH /auth/me` therefore reaches activities already queued. The value
  stored on each row is left alone as a record of what was intended when it was written. Prepare
  answers 409 while no learner ID is set, before any login, so an Azure user isn't made to approve
  a push for nothing.
- **Login is a two-phase dance** because MFA codes live ~30 s. `prepare*` opens the login and
  parks it in `LoginSessions`; `submit-with-mfa` or `azure-id/complete` finishes it.
  - The two halves are **not interchangeable**. A `LoginSession` records its `LoginFlow`, and the
    wrong complete gets a 409. Without that, an Azure session would start a second Microsoft poll.
  - A parked session expires after `LoginSession.TTL` (5 min) and is dropped as soon as it's
    spent. It holds live OneAdvanced cookies.
- **`PUT /pending/{id}`**: ownership and `posted: false` are in the Mongo filter, so unknown,
  another user's (old data) and already-posted all give the same 404 as the delete. Its rules are
  hand-written, not `@Valid`, so the app gets a real `{"error": "..."}` message.
- **A row is stored only when complete**: a past weekday, a start time inside 09:00–18:00, a
  non-zero duration and a description of at most 500 characters, all in `db/model/ActivityRules`.
  The LLM path drops a failing entry as a parse error, PUT answers 400, `ActivityLogRepository`
  throws as the backstop, and `OneAdvancedDriver` won't post a row stored before a rule existed.
  `log-activities` refuses any line over 500 characters before calling the model. Test fixtures
  need weekday dates; the suite must pass when run at a weekend.
- **Error bodies are `ApiError` records,** one fixed constant per failure, never a driver's
  `e.getMessage()`: those carry login-chain URLs with the username in them.
- **Unknown JSON properties are a 400.** `prepare_and_submit.feature` pins that a `learnerId` on
  the prepare body is refused, so the learner ID can only come from the account.

## Never log credentials

Nothing logs OneAdvanced credentials, MFA codes, Microsoft flow tokens, cookie values or learner
IDs. This is enforced:
- `web/SafeUrl` strips query strings from every URL the drivers log or put in an exception.
- The drivers log named fields, never a raw upstream body. Those carry Microsoft's `FlowToken`,
  which is bearer-equivalent.
- `logback.xml` doesn't pin the drivers to DEBUG. `OTJ_DRIVER_LOG_LEVEL=DEBUG` enables it on
  purpose, until the next restart.
- `ServerHooks` captures every log event at TRACE and fails any scenario in which a sentinel
  credential appears. Adding a leak breaks the suite.

## Build, run, test

```bash
nix-shell                            # jdk25, maven, podman
mvn -q package -DskipTests           # build target/app.jar
mvn -B clean test                    # unit tests only
```

Integration tests are `*IT` classes. Surefire's default includes **skip** them, so name them
explicitly. They need a container runtime:

```bash
DOCKER_HOST=unix:///run/user/1000/podman/podman.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -B test -Dtest='CucumberIT,UserRepositoryIT,ActivityLogRepositoryIT'
```

The whole stack: `cp .env.example .env`, fill it in, then `cd deploy && nix-shell` and
`./bootstrap.sh` (`--prod` to detach, `--stop` to stop).

**The container runtime is Podman, not Docker.** Use `podman`/`podman-compose` in anything you
write.

## Environment

Set in `.env` at the repo root (gitignored, holds real secrets; never print, commit, or echo it).
`bootstrap.sh` refuses to start while any required variable is empty:

- `MONGO_USER`, `MONGO_PASSWORD`: Mongo's root user. compose builds `MONGO_URI` from them.
- `ANTHROPIC_API_KEY`: **required to boot**, even for flows that never touch the LLM.
  `AnthropicOkHttpClient.fromEnv()` throws at construction.
- `OTJ_DRIVER_LOG_LEVEL`: optional, defaults to `INFO`.

The app reads the process environment only (`dotenv-java` is declared but unused), so running the
jar outside compose needs the variables exported, `MONGO_URI` included.

## Branches and keeping up with master

`tailscale` is **protected**: changes go through a PR, merged with a regular merge, **not
squash**, so `master`'s history stays in its ancestry. To bring it up to date:

```bash
git checkout -b tailscale-sync origin/tailscale
git merge origin/master
```

Expect these conflicts, and resolve them this way:
- **modify/delete** on anything this branch removed (`aws/`, `deploy/ansible/`, `deploy/haproxy/`,
  `admin/`, `auth/`, `crypto/`, `quota/`, the extra workflows, …): keep the deletion with
  `git rm`.
- **`OtjServicesResource`, `AccountResource`, the Cucumber glue and features**: these drop
  `SecurityContext`, tokens and sealing here. Take master's logic change and keep this branch's
  single-user shape.
- **`AGENTS.md`, `README.md`, `deploy/`**: keep this branch's versions, and port anything that
  applies to a self-hoster.

Then run the unit and integration tests above before opening the PR. CI here only builds and
runs the unit tests.
