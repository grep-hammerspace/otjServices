# Admin invite API + mobile signup — implementation plan

Two deliverables, sequenced so each one unblocks the next:

1. **Admin invite API** (`otjServices`, this repo) — a second JAX-RS server, reachable
   only over the tailnet, with endpoints to mint and revoke signup invite codes.
   Runs from the same image as the main API under a different entrypoint.
2. **Mobile signup flow** (`otj-mobile`) — turn the placeholder signup screen into a
   real signup/login/logout flow against `/auth/*`.

Codes are currently minted by hand (`mongosh` locally, the Atlas UI in prod). That is
the thing being replaced: without it, onboarding anyone onto the mobile app means
opening a database console.

---

## Step 1 — Admin invite API

### 1.1 Why a second server rather than a path on the existing one

The main API is reachable from the tailnet, and step 09 of the auth plan parks a real
domain and a reverse proxy in front of port 443. An `/admin` path on that server is one
proxy misconfiguration away from being internet-facing. A second server on its own port
cannot be exposed by accident — the box publishes nothing, and the only way in is a
`tailscale serve` mapping that has to be created deliberately.

It also keeps the blast radius small: the admin graph never constructs an
`OtjServicesResource`, so no browser driver, no LLM client, no user session handling.

### 1.2 Entrypoint

Same image, different command. `docker/start.sh` dispatches on `APP_ROLE`:

```bash
case "${APP_ROLE:-api}" in
  api)   exec java $JAVA_OPTS -cp /app/app.jar com.github.grepHammerspace.Main ;;
  admin) exec java $JAVA_OPTS -cp /app/app.jar com.github.grepHammerspace.admin.AdminMain ;;
esac
```

The shaded jar contains every class, so `-cp` plus an explicit main class is enough; the
manifest's `Main-Class` stays pointed at the API. One image tag for both containers means
they are provably the same build, and a rollback moves them together.

### 1.3 The trust model

Two independent layers, both required:

- **Where the request came from.** The admin container publishes to `127.0.0.1:8946`
  only. Nothing off-host can reach it. The host's `tailscale serve` is the sole process
  that can, and it terminates TLS for the tailnet.
- **Who is asking.** `tailscale serve` injects a `Tailscale-User-Login` header naming the
  authenticated tailnet user. `AdminIdentityFilter` requires that header and checks it
  against an `ADMIN_ALLOWED_LOGINS` allowlist. Anything else is a 403.

The header is only trustworthy because of the loopback binding — the same invariant
`deploy/README.md` already documents for the main API. **If the port binding or the
network topology changes, this assumption has to be re-examined.**

No dev-only bypass. Locally you set the header yourself with `curl -H`; the loopback
binding makes that exactly as safe as production, and it keeps one code path.

Every mint and revoke logs the tailnet login that performed it.

### 1.4 Endpoints

| Method | Path | Body | Success | Failures |
|---|---|---|---|---|
| POST | `/admin/invites` | `{note?, expiresInDays?}` | 201 `{code, note, createdAt, expiresAt}` | 400 bad `expiresInDays` |
| GET | `/admin/invites` | — | 200 `[{code, note, status, …}]` | — |
| DELETE | `/admin/invites/{code}` | — | 204 | 404 unknown, 409 already claimed |
| GET | `/health` | — | 200 | — |

`expiresInDays` defaults to 7 and is capped at 365. The server generates the code; the
caller cannot choose one. `GET` returns newest first and derives a `status` of
`active` / `used` / `revoked` / `expired` rather than storing it.

### 1.5 Code generation

`OTJ-XXXX-XXXX`, drawn from `SecureRandom` over a 32-character alphabet with `I`, `O`,
`0` and `1` removed — the codes get read aloud and typed on a phone. 8 characters over
that alphabet is 40 bits, which is far beyond brute-forcing through a single-instance
HTTP API, and the unique index on `code` catches a collision as a duplicate-key error.

### 1.6 Revocation

A revoked code sets `revokedAt` (audit: who killed it and when) **and** pushes
`expiresAt` back to now. That kills it through the existing claim filter
(`used:false AND expiresAt > now`) without touching `claim` itself — the one piece of
concurrency-sensitive code in the repository stays exactly as it is.

Revoking an already-claimed code is a 409, not a silent success: the account it created
already exists, and deleting the code would not unmake it.

### 1.7 Files

New, under `src/main/java/com/github/grepHammerspace/admin/`:

- `AdminMain.java` — boots `ServerBootstrap.start(8946, …)` (already variadic over resources)
- `AdminIdentityFilter.java` — the header + allowlist gate
- `AdminInviteResource.java` — the three endpoints
- `InviteCodeGenerator.java`
- `dto/CreateInviteRequest.java`, `dto/InviteResponse.java`

Plus `bind/AdminComponent.java` (`@Component(modules = AppModule.class)`), exposing only
the admin resource and filter. Dagger's providers are lazy, so the admin container never
constructs `AnthropicClient` and does not need `ANTHROPIC_API_KEY` to boot.

Changed: `InviteCodeRepository` gains `create` / `list` / `revoke` (and its javadoc stops
claiming no admin endpoint exists).

### 1.8 Tests

- `InviteCodeRepositoryIT` — create round-trips; revoke blocks a subsequent claim; revoke
  of a used code is refused; list ordering and status derivation; existing claim tests
  must still pass untouched.
- `AdminIdentityFilterTest` — missing header, blank header, non-allowlisted login,
  allowlist parsing (whitespace, case), empty allowlist denies everything.
- `admin_invites.feature` — mint a code over HTTP, sign up with it, then revoke a second
  code and prove signup with it fails. This is the interlock that matters: it exercises
  the admin API and the mobile signup path against each other.

---

## Step 2 — Local compose

`app` gets an explicit `image: localhost/otj-services:local`; the `admin` service reuses
that tag with no `build:` block of its own, so the Maven layer is built once.

`bootstrap.sh` waits on the admin health endpoint alongside the app's, and adds a second
serve mapping on a distinct HTTPS port:

```
tailscale serve --bg --https=8443 http://127.0.0.1:8946
```

Also in scope, because it is the same trust boundary: Mongo (`27017`) and mongo-express
(`8081`, `ME_CONFIG_BASICAUTH: false`) are currently published on `0.0.0.0`. Locking the
admin API to loopback while an unauthenticated database console sits open on the same
host would be theatre. Both get bound to `127.0.0.1`.

---

## Step 3 — EC2 deployment

- `deploy/prod/admin-api.container.template` — Quadlet unit, `PublishPort=127.0.0.1:8946:8946`,
  `EnvironmentFile=%h/otj-admin-api.env`.
- `deploy/prod/deploy.sh` — renders both units from the one image URI, restarts both,
  health-checks both before reporting success. A failure in either fails the deploy.
- `deployment-checklist.md` — the one-time `tailscale serve --https=8443` and the admin
  env file.
- `deploy/README.md` — currently points at `TailscaleIdentityHelper`, a class `staging`
  deleted. Rewritten around the admin API's trust model.

**Sequencing note:** `master` has no signup at all — sessions, users and invite codes live
on `staging`. This wiring lands now but deploys nothing useful until `staging` reaches
`master`.

---

## Step 4 — Mobile signup flow

### 4.1 A bug to fix first

`useToken()` holds auth state in per-call `useState`, so every caller gets an independent
copy, and `api.ts` clears the stored token on a 401 without telling any of them. Token
expiry therefore does not bounce the user to signup — the root layout's `token` stays
stale until the app restarts.

Fix: a single `AuthProvider` context owning the state, with `api.ts` notifying it on 401
through a registered callback.

### 4.2 Screens

- `signup.tsx` — invite code, username, password, learner ID. Error mapping: 403 →
  invalid/used/expired code, 409 → username taken, 400 → field validation, network
  failure gets its own message rather than being folded into "signup failed".
- `login.tsx` — username and password, with a link between the two screens. Both live in
  the `guard={!token}` group.
- Logout calls `DELETE /auth/session` then clears local state.

`lib/auth-api.ts` holds `signup` / `login` / `logout` and a typed `ApiError` that parses
the backend's `{"error": …}` bodies. Form hygiene throughout: `KeyboardAvoidingView`,
`secureTextEntry`, autocapitalize and autocorrect off, `autoComplete` hints.

### 4.3 Out of scope

The Log, Pending and Submit tabs stay placeholders. They need step 05 (OneAdvanced
credentials in request bodies rather than stored) and the 05.5 list/delete endpoints,
which do not exist yet. Building them against mocks now would mean rewriting them later.

---

## Order of execution

```
1. Admin API + tests            (branch 05-admin-invite-api off staging)
2. Compose wiring               verify locally with podman
3. EC2 templates + docs         lands now, deploys when staging -> master
4. Mobile signup                (branch off otj-mobile main)
```

Steps 1–2 give a working local loop: mint a code over the tailnet, sign up on the phone
with it.
