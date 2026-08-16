# Mobile signup flow — verification runbook

How to verify the step 04 auth endpoints by hand, and what to build in
`otj-mobile` to turn them into a working signup screen.

Companion to `steps-04-08-implementation-plan.md`. Everything in Part A works
against the `04-invite-signup` branch as committed; Part B is the mobile work
that follows.

---

## Part A — Verify the backend

### A1. Start Mongo and the server

```bash
podman run -d --name otj-mongo -p 27017:27017 mongo:8

mvn -q package -DskipTests
MONGO_URI=mongodb://localhost:27017 ANTHROPIC_API_KEY=dummy java -jar target/app.jar
```

`ANTHROPIC_API_KEY` must be set to *something* — `AnthropicOkHttpClient.fromEnv()`
throws at construction, so the server will not boot without it. `dummy` is fine:
nothing in the signup flow reaches the LLM. Those two are the only variables the
app reads (`PasswordCipher` and `PASSWORD_ENCRYPTION_KEY` went away in step 03).

Leave it running; use a second terminal below.

### A2. Hand-mint an invite code

There is no admin endpoint by design. Minting is a manual insert — in the Atlas
UI for production, `mongosh` locally. Both produce the same document:

```bash
podman exec otj-mongo mongosh otjdb --quiet --eval '
db.inviteCodes.insertOne({
  code:      "OTJ-DEV-0001",
  used:      false,
  note:      "asad dev phone",
  createdAt: new Date(),
  expiresAt: new Date(Date.now() + 7*24*60*60*1000),
  usedBy:    null,
  usedAt:    null
})'
```

Three fields the claim filter matches on, so they have to be exactly right:

- `used` must be boolean `false`, not the string `"false"`
- `expiresAt` must be a real `Date` — the filter is `$gt: new Date()`, so a
  string never matches and the code silently reads as expired
- `code` must match what you type, whitespace included. The server strips the
  incoming value but not the stored one

Confirm it landed:

```bash
podman exec otj-mongo mongosh otjdb --quiet --eval 'db.inviteCodes.find().pretty()'
```

### A3. The endpoints

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/auth/signup` | `{inviteCode, username, password, learnerId}` | 201 `{token}` |
| POST | `/auth/session` | `{username, password}` | 200 `{token}` |
| DELETE | `/auth/session` | — (`Authorization: Bearer`) | 204 |

**Signup — creates the account and returns a token in one round trip:**

```bash
curl -s -X POST localhost:8945/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"inviteCode":"OTJ-DEV-0001","username":"asad","password":"pw","learnerId":"L1"}'
# -> 201 {"token":"..."}
```

**On `userId`:** the client never sends or receives it. The server mints a UUID
internally and binds it to the token; every downstream request derives it from
the `Authorization` header. To see it:

```bash
podman exec otj-mongo mongosh otjdb --quiet --eval \
  'db.users.find({}, {userId:1, appUsername:1, learnerId:1, appPasswordHash:1}).pretty()'
```

`appPasswordHash` should start `$2a$12$`, and no `password` field should exist.

**Login, use, revoke:**

```bash
TOKEN=$(curl -s -X POST localhost:8945/auth/session \
  -H 'Content-Type: application/json' \
  -d '{"username":"asad","password":"pw"}' | jq -r .token)

# prepare-browser always answers 501; 501-not-401 is what proves the token was accepted.
curl -s -o /dev/null -w '%{http_code}\n' \
  localhost:8945/otj-services/prepare-browser -H "Authorization: Bearer $TOKEN"  # 501

curl -s -o /dev/null -w '%{http_code}\n' -X DELETE \
  localhost:8945/auth/session -H "Authorization: Bearer $TOKEN"                  # 204

curl -s -o /dev/null -w '%{http_code}\n' \
  localhost:8945/otj-services/prepare-browser -H "Authorization: Bearer $TOKEN"  # 401
```

### A4. The negative cases

| Do this | Expect |
|---|---|
| Re-run the A3 signup with the same code | 403 — already burned |
| Signup with `"inviteCode":"OTJ-NOPE"` | 403, *identical* message |
| Mint a code with `expiresAt: new Date(Date.now()-1000)`, use it | 403 |
| Signup with a fresh code but `"username":"asad"` | 409, and the fresh code is burned |
| `/auth/session` with `"password":"wrong"` | 401 |
| `/auth/session` with `"username":"ghost"` | 401, same message, similar latency |

The last pair is the anti-enumeration property. Time them with
`curl -w '%{time_total}'` — both should pay full bcrypt cost and land within a
few ms of each other. A fast "unknown user" response is the bug this guards
against.

The 409 case burning a code is the plan's accepted quirk: the claim is the
atomic step and has to come first. Re-mint and move on.

Finally, scan the server log. Usernames and userIds are expected; a password or
a raw token is a defect.

---

## Part B — Mobile signup flow

Repo: `/home/asad/Projects/personal/java/otj-mobile`.

More scaffolding exists than the placeholder suggests — `useToken()`, the
SecureStore/localStorage split, the `api()` bearer wrapper, and a
`<Stack.Protected guard={!!token}>` router gate are all in place. Four things
stand between that and a working flow.

### B1. Point the app at your machine, not localhost

`localhost` on a physical device means the phone. Set in `.env`:

```
EXPO_PUBLIC_API_URL=http://100.123.154.21:8945
```

`npm run start:tailnet` is already pinned to that tailnet IP, so it is the
consistent choice; the LAN address works too. The server binds `0.0.0.0`, so
both are reachable. Restart Expo after editing — `EXPO_PUBLIC_*` is inlined at
build time, not read at runtime.

### B2. Fix `useToken` first, or signup will silently do nothing

**This is the one that costs an hour if hit blind.** `useToken()` holds
component-local `useState`. `_layout.tsx` calls it; if `signup.tsx` calls it
too, that is a second, independent copy. Signing in from the signup screen
writes SecureStore and updates the *signup screen's* state, while the layout's
`token` stays `null` — so the guard keeps rendering signup and the app appears
stuck on a form that just worked.

Convert it to shared context in `src/lib/auth.ts`, keeping the existing
`getToken`/`setToken`/`clearToken` as they are:

```tsx
const AuthContext = createContext<ReturnType<typeof useTokenState> | null>(null);

function useTokenState() { /* the current useToken body, unchanged */ }

export function AuthProvider({ children }: { children: React.ReactNode }) {
  return <AuthContext.Provider value={useTokenState()}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}
```

In `_layout.tsx` the provider must sit **outside** the `if (loading) return null`
early return — split it into an outer component rendering `<AuthProvider>` and an
inner one calling `useAuth()`.

### B3. Add the auth calls — deliberately not through `api()`

`api()` calls `clearToken()` on any 401. A failed login returns 401, so routing
login through it would wipe the token of an already-signed-in user who is
re-authenticating. Use plain `fetch` in a new `src/lib/authApi.ts`:

```ts
const BASE = process.env.EXPO_PUBLIC_API_URL;

async function post(path: string, body: object) {
  const res = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(JSON.parse(text)?.error ?? `HTTP ${res.status}`);
  return JSON.parse(text).token as string;
}

export const signup = (inviteCode: string, username: string, password: string, learnerId: string) =>
  post("/auth/signup", { inviteCode, username, password, learnerId });

export const login = (username: string, password: string) =>
  post("/auth/session", { username, password });
```

Surfacing `error` from the body is what puts "Invalid, used or expired invite
code" on screen instead of a bare 403.

### B4. Build the screen

Replace the `signup.tsx` placeholder with four `TextInput`s — invite code,
username, password (`secureTextEntry`), learner ID — plus a toggle to
"I already have an account" that hides the invite code and learner ID and calls
`login()` instead.

On success call `signIn(token)` from `useAuth()` and **do not navigate**. The
`<Stack.Protected>` guard flips on its own once the shared token state updates —
that is the entire point of B2.

Set `autoCapitalize="none"` and `autoCorrect={false}` on the username and invite
code, or the phone will silently capitalise them into non-matches. The server
strips whitespace but preserves case.

### B5. Sign-out

A button on a tab screen that calls `DELETE /auth/session` **with** the bearer
header — via `api()` this time, which attaches it — then `signOut()`. Server-side
revocation is what actually kills the token; clearing local storage alone leaves
it valid for the full 30-day window.

### B6. Verify end to end

1. Mint a second invite code (A2) — the first is burned by the curl testing
2. `npm run start:tailnet`, open in Expo Go
3. Tap **Check backend /health** first — proves reachability before debugging a form
4. Sign up → should land on the tabs immediately
5. Force-quit and reopen → still signed in, from SecureStore
6. Sign out → back to signup; confirm the old token now 401s from curl
7. Sign back in through the login toggle

---

## Known limits at this point

`/prepare-browser` and `/azure-id/prepare` return **501** until step 05 teaches
them to accept OneAdvanced credentials in the request body, so the submit tab
cannot be finished yet. Signup, login, logout and `/log-activities` are live.

Android blocks cleartext HTTP in release builds. Expo Go over `http://` is fine
for development; a standalone build needs HTTPS, which is step 09's Caddy work.
