# Steps 04–08: Detailed Implementation Plan

Continuation of `auth-multiuser-plan.html`. Steps 01–03 are done:
01 merged to master (PR #12), 02 in PR #14 (`02-session-token-core` → `staging`),
03 in PR #16 (`03-user-model-bcrypt`, stacked on #14).

**Merge order before starting:** merge #14, then #16 (GitHub retargets it to
`staging` automatically). Use regular merge or rebase — **not squash** — for the
stacked pair. Every step below branches off fresh `staging` after the previous
step merges.

**Test gate for every step** (CI is master-only, so run locally):

```bash
# unit
mvn -B clean test
# integration (surefire default includes skip *IT classes — must name them)
DOCKER_HOST=unix:///run/user/1000/podman/podman.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -B test -Dtest='CucumberIT,UserRepositoryIT,ActivityLogRepositoryIT,SessionTokenServiceIT,InviteCodeRepositoryIT,LlmQuotaServiceIT'
```

(Add each new `*IT` class to that list as it appears.)

---

## Step 04 — Invite-gated signup (`04-invite-signup`)

### 04.1 `db/InviteCodeRepository.java` (new)

`@Singleton`, `@Inject` ctor taking `MongoDatabase`; collection `inviteCodes`;
create unique index on `code` in the ctor (same pattern as `UserRepository`).

Document shape (minted by hand in the Atlas UI — there is no admin endpoint):

```json
{ "code": "OTJ-7F3K-9QMX", "used": false, "note": "for Sam",
  "createdAt": ISODate, "expiresAt": ISODate, "usedBy": null, "usedAt": null }
```

One public method — the atomic claim:

```java
/** Atomically claims an unused, unexpired code. Returns true if this call won the claim. */
public boolean claim(String code, String usedByUserId) {
    Document claimed = collection.findOneAndUpdate(
        Filters.and(
            Filters.eq("code", code),
            Filters.eq("used", false),
            Filters.gt("expiresAt", new Date())),
        Updates.combine(
            Updates.set("used", true),
            Updates.set("usedBy", usedByUserId),
            Updates.set("usedAt", new Date())));
    return claimed != null;
}
```

The `findOneAndUpdate` filter is what stops two people racing the same code —
no read-then-write gap. Add a test-only/ops-free `mint(...)` only if the tests
need it (they can also insert documents directly via the db handle — prefer that
to keep production surface minimal).

Take a `Clock` in a package-private ctor (delegating `@Inject` ctor uses
`Clock.systemUTC()`) so expiry is testable — same pattern as
`SessionTokenService`.

### 04.2 DTOs (new, in `api/dto/`)

```java
public record SignupRequest(@NotBlank String inviteCode, @NotBlank String username,
                            @NotBlank String password, @NotBlank String learnerId) {}
public record SessionRequest(@NotBlank String username, @NotBlank String password) {}
public record TokenResponse(String token) {}
```

### 04.3 `api/AuthResource.java` (new)

**No `@Authenticated` annotation** — anonymous by construction.
`@Path("/auth")`, `@Produces/@Consumes("application/json")`, `@Singleton`,
`@Inject` ctor taking `UserRepository`, `InviteCodeRepository`,
`SessionTokenService`, `PasswordHasher`.

```
POST   /auth/signup    { inviteCode, username, password, learnerId } -> 201 { token }
POST   /auth/session   { username, password }                       -> 200 { token }
DELETE /auth/session   (Authorization: Bearer <token>)              -> 204
```

**Signup logic, in this order:**
1. `String userId = UUID.randomUUID().toString();`
2. `inviteCodeRepository.claim(body.inviteCode().strip(), userId)` — false → **403**
   `{"error": "Invalid, used or expired invite code"}` (one message for all three
   cases; don't leak which).
3. Build `new User(userId, body.username().strip(), passwordHasher.hash(body.password()),
   body.learnerId().strip(), Instant.now())` and call `userRepository.insert(user)`
   — false (duplicate username) → **409**. *Known accepted quirk:* the code was
   already burned; the plan's Atlas-UI admin model treats that as "mint another"
   territory. If it bothers you, check `findByAppUsername` first, then claim,
   then insert — the race remnant is only that a code can burn on a
   simultaneously-taken username, which is harmless.
4. `String token = sessionTokenService.issue(userId);` → **201** `TokenResponse` —
   signup and login are one round trip.

**Login logic:**
1. `User user = userRepository.findByAppUsername(body.username().strip());`
2. If `user == null` → still run `passwordHasher.verify(body.password(),
   "$2a$12$" + dummy-hash-constant)` before returning, so unknown-user and
   wrong-password take the same time (no username-enumeration timing oracle).
   Both failures → **401** `{"error": "Invalid username or password"}`.
3. Match → `sessionTokenService.issue(user.userId())` → **200** `TokenResponse`.
4. **Never log the password; never log the raw token.** Log username-only at info.

**Logout logic:** read `@HeaderParam(HttpHeaders.AUTHORIZATION) String header`
manually (the resource is anonymous, so there is no `SecurityContext` here);
strip the `Bearer ` prefix; `sessionTokenService.revoke(raw)`; return **204**
whether or not it deleted anything (idempotent).

### 04.4 Wiring

- `AppComponent`: add `AuthResource authResource();`
- `Main`: `ServerBootstrap.start(8945, component.otjServicesResource(),
  component.authResource(), component.authenticationFilter());`
- `TestAppComponent`: add `AuthResource authResource();` and
  `InviteCodeRepository inviteCodeRepository();` (test seeding)
- `ServerHooks`: pass `component.authResource()` to `ServerBootstrap.start`.
  Keep issuing the seeded token for `test-user-id` — the OTJ features still use
  it until step 05 switches them to the real signup path.

### 04.5 Tests

**New `signup.feature`** (rename/replace nothing yet — `registration.feature`
survives until step 05):

```gherkin
Feature: Signup and login

  Scenario: Signup with a valid invite code returns a token
    Given an unused invite code "OTJ-TEST-0001" expiring in 7 days
    When I POST "/auth/signup" with inviteCode "OTJ-TEST-0001", username "newuser", password "pw", learnerId "L9"
    Then the response status is 201
    And the response body contains "token"

  Scenario: Signup with an unknown invite code is rejected
    When I POST "/auth/signup" with inviteCode "OTJ-NOPE", username "u2", password "pw", learnerId "L9"
    Then the response status is 403

  Scenario: An invite code cannot be redeemed twice
    Given an unused invite code "OTJ-TEST-0002" expiring in 7 days
    When I POST "/auth/signup" with inviteCode "OTJ-TEST-0002", username "first", password "pw", learnerId "L9"
    And I POST "/auth/signup" with inviteCode "OTJ-TEST-0002", username "second", password "pw", learnerId "L9"
    Then the response status is 403

  Scenario: Signup with an expired invite code is rejected
    Given an unused invite code "OTJ-TEST-0003" that expired yesterday
    When I POST "/auth/signup" with inviteCode "OTJ-TEST-0003", username "u3", password "pw", learnerId "L9"
    Then the response status is 403

  Scenario: Signup with a taken username is rejected
    Given an unused invite code "OTJ-TEST-0004" expiring in 7 days
    And an unused invite code "OTJ-TEST-0005" expiring in 7 days
    When I POST "/auth/signup" with inviteCode "OTJ-TEST-0004", username "dupe", password "pw", learnerId "L9"
    And I POST "/auth/signup" with inviteCode "OTJ-TEST-0005", username "dupe", password "pw", learnerId "L9"
    Then the response status is 409

  Scenario: Login with correct credentials returns a token
    ... (signup, then POST /auth/session, 200, body contains token)

  Scenario: Login with wrong password is rejected
    ... (401)

  Scenario: Login with unknown username is rejected
    ... (401)

  Scenario: Logout revokes the token
    ... (signup capturing token, DELETE /auth/session with that token -> 204,
         then use that token on /otj-services/reset-notes -> 401)
```

**New `SignupSteps.java`**: the `Given an unused invite code ...` steps insert
documents directly into `inviteCodes` via the `db` handle from
`ScenarioContext` (that *is* the production minting procedure). The signup/login
`When` steps POST without auth headers and stash the returned token in
`ScenarioContext` under e.g. `"signupToken"` for later steps.

**New `InviteCodeRepositoryIT`**: happy claim; unknown code false; already-used
false; expired false; **contention test** — insert one code, run 8 threads
against `claim` via `ExecutorService` + `CountDownLatch` start gate, assert
exactly 1 true. Also assert the unique index rejects inserting the same code
twice.

**Manual smoke:** none needed beyond the suite — nothing browser-driven changed.

---

## Step 05 — Stop storing OneAdvanced credentials (`05-stop-storing-creds`)

### 05.1 New DTOs

```java
public record PrepareBrowserRequest(@NotBlank String oneAdvancedUsername,
                                    @NotBlank String oneAdvancedPassword) {}
public record AzureIdPrepareRequest(@NotBlank String oneAdvancedUsername,
                                    @NotBlank String oneAdvancedPassword) {}
```

### 05.2 `OtjServicesResource` changes

- **`/prepare-browser`**: `@GET` → `@POST`, add `@Valid PrepareBrowserRequest body`.
  Restore the pre-501 driver flow (see git history of the 02 branch, commit
  `51bd46c`), but feed `driver.prepare(body.oneAdvancedUsername(),
  body.oneAdvancedPassword())` — the driver signatures already take credentials
  as parameters, so drivers need no change. Keep the 502-on-IOException mapping.
  No `findByUserId` needed — the user record plays no part.
- **`/azure-id/prepare`**: same transformation. Restore the full MFA-push flow
  from `51bd46c` (login future, virtual thread, challenge number), minus the
  `findByUserId` null-check — the flow no longer touches the `User` record.
- **Delete `POST /register`** and `api/dto/RegisterRequest.java`. Registration
  as a concept is now signup (04) + on-device credential storage.
  `PasswordHasher` remains injected? — after deleting register, the resource no
  longer needs it; remove the field/ctor param (AuthResource is its only caller).
- **Delete the MFA log line** in `useMfaCodeToSubmitUnSubmittedOTJs`:
  `log.info("Received submit-with-mfa request from user {} and mfa code {}", ...)`
  — the preceding "Received request from user X to do submit-with-mfa" line
  already covers the audit need.
- **Log audit** (now that passwords transit request bodies): grep every
  `log.` call in `api/` and `web/` for body fields. Known-clean pattern is
  logging `userId` + action only. Check `AzureIdDriver`/`OtjDriver` for any
  logging of the credentials they type.

### 05.3 Test rewiring — the big one

`/register` is gone, so the Cucumber identity bridge must become the real flow:

- **`ServerHooks`**: stop seeding `test-user-id`. Instead, before each scenario:
  insert an invite code, POST `/auth/signup` (unique username per scenario, e.g.
  `"user-" + UUID`), capture `{token}`, look up the created user's UUID from the
  `users` collection, and publish `"authToken"` **and** `"userId"` into
  `ScenarioContext`. (Signup via HTTP exercises the production path on every
  scenario — that's a feature, not overhead.)
- **`LogActivitiesSteps`**: delete the `a registered user with learnerId` step's
  HTTP call to `/register`; the hook's signup already created the user. If
  scenarios still want to control `learnerId`, make the step update the user
  document directly in Mongo.
- **Feature files** (`log_activities`, `delete_last_row`, `reset_notes`):
  replace `for user "test-user-id"` assertions with `for the current user`; the
  step reads `"userId"` from `ScenarioContext`. Same for
  `there are no activity logs for the test user`.
- **Delete `registration.feature`** and prune `RegistrationSteps` to the steps
  still used (`the response status is`, `the response body contains`, the users
  collection assertions — move the survivors into `HttpSteps`/`SignupSteps`
  and delete the class if it empties).
- **New scenario** (in a `credentials.feature` or appended to authentication):
  POST `/otj-services/prepare-browser` with a blank `oneAdvancedUsername` → 400
  (bean validation), proving creds are required in-body. Don't integration-test
  the real browser flow — that stays manual.

### 05.4 Manual smoke on localhost (first one that matters end-to-end)

```bash
podman run -d --name otj-mongo -p 27017:27017 mongo:8
mvn -q package -DskipTests
MONGO_URI=mongodb://localhost:27017 ANTHROPIC_API_KEY=dummy java -jar target/app.jar
# mint a code:
mongosh otjdb --eval 'db.inviteCodes.insertOne({code:"OTJ-SMOKE-1", used:false,
  createdAt:new Date(), expiresAt:new Date(Date.now()+86400000), usedBy:null, usedAt:null})'
# then:
curl -s localhost:8945/health                                     # 200
curl -s -X POST localhost:8945/otj-services/log-activities \
     -H 'Content-Type: application/json' -d '{"content":"x"}'     # 401
TOKEN=$(curl -s -X POST localhost:8945/auth/signup -H 'Content-Type: application/json' \
  -d '{"inviteCode":"OTJ-SMOKE-1","username":"asad","password":"pw","learnerId":"L1"}' | jq -r .token)
curl -s -X DELETE localhost:8945/otj-services/reset-notes \
     -H "Authorization: Bearer $TOKEN"                            # 200
# verify server log contains neither "pw" nor any mfa/password value
```

---

## Step 06 — Rate limiting (`06-rate-limiting`)

### 06.1 `auth/RateLimiter.java` (new)

In-memory sliding window — correct, not a shortcut, because there is exactly one
app instance; resets on restart (accepted).

```java
@Singleton
public class RateLimiter {
    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;   // @Inject ctor -> systemUTC; package-private ctor for tests

    /** Returns empty if allowed (and records the hit); or seconds to wait if limited. */
    public OptionalLong tryAcquire(String key, int limit, Duration window) { ... }
}
```

Implementation notes:
- Per key keep a deque of timestamps; on `tryAcquire`, prune entries older than
  `now - window`, then if `size < limit` add `now` and allow, else return
  `oldest + window - now` in seconds for `Retry-After`. Synchronize per-deque
  (`computeIfAbsent` + `synchronized (deque)`).
- Eviction: on every Nth call (or a `lastSweep` timestamp check), drop map
  entries whose deque is empty after pruning — prevents unbounded growth from
  one-off IPs.

### 06.2 Apply in `AuthResource` only

Inject `RateLimiter` + `@Context HttpServletRequest`. Helper:

```java
private String clientIp(HttpServletRequest req) {
    String xff = req.getHeader("X-Forwarded-For");
    // First hop = original client. Caddy (step 09) must be configured to set this;
    // getRemoteAddr() alone would see only the proxy and rate-limit everyone as one.
    return xff != null && !xff.isBlank() ? xff.split(",")[0].strip() : req.getRemoteAddr();
}
```

Limits (constants at top of the class, tune later):
- `POST /auth/session`: per-username `10 / 15 min` (**the one that matters** —
  usernames are public, they're the brute-force surface), per-IP `30 / 15 min`.
  Key the username limit as `"login:user:" + username`, IP as `"login:ip:" + ip`.
  **Check the limiter before verifying the password**, and count failures and
  successes alike (simpler, and successful floods are still floods).
- `POST /auth/signup`: per-IP `5 / hour` (`"signup:ip:" + ip`) — stops
  invite-code guessing.

Limited → **429** with `Retry-After: <seconds>` header and a JSON error body.

### 06.3 Tests

- **`RateLimiterTest`** (unit, fixed/steppable Clock): allows up to limit;
  denies limit+1 with correct retry seconds; window slides (advance clock past
  oldest hit → allowed again); distinct keys independent; eviction removes idle
  keys.
- **Cucumber** (`rate_limiting.feature`): scenario "11 wrong-password logins →
  429 with Retry-After" — POST `/auth/session` in a loop step; assert final
  status 429 and header present. Per-scenario server means no cross-scenario
  bleed. Keep the loop at the limit boundary (limit is a constant — consider
  reading it from a system property in tests, or just hardcode 10 in the
  feature and accept the coupling).

---

## Step 07 — Per-user LLM quota (`07-llm-quota`)

### 07.1 `quota/LlmQuotaService.java` (new package)

```java
@Singleton
public class LlmQuotaService {
    static final int DAILY_LIMIT = 10;
    // collection "llmQuota", unique compound index {userId: 1, date: 1} created in ctor
    // @Inject ctor (MongoDatabase); package-private (MongoDatabase, Clock) for tests

    /** Atomically consumes one call. Returns true if within quota. */
    public boolean tryConsume(String userId) {
        String date = LocalDate.now(clock).toString();   // "2026-08-03"
        Document doc = collection.findOneAndUpdate(
            Filters.and(Filters.eq("userId", userId), Filters.eq("date", date)),
            Updates.inc("count", 1),
            new FindOneAndUpdateOptions().upsert(true)
                .returnDocument(ReturnDocument.AFTER));
        return doc.getInteger("count") <= DAILY_LIMIT;
    }
}
```

Atomic `$inc` + unique index means two concurrent calls can't both think they
were the 10th. (An over-limit call still increments — harmless, the day rolls
over.)

### 07.2 Hook into `logActivtiesWithLlmHelp`

Insert **after** the `diff == null` no-op check, **before**
`llmService.parseActivities(...)` — identical resubmitted content never consumes
quota:

```java
if (!llmQuotaService.tryConsume(userId)) {
    return Response.status(429)
        .entity("{\"error\": \"Daily LLM quota of 10 calls reached. Resets at midnight UTC.\"}")
        .build();
}
```

Inject the service into the resource; add nothing to any other endpoint — after
step 01 this is the only LLM call site.

### 07.3 Tests

- **`LlmQuotaServiceIT`**: 10 consumes true, 11th false; different user
  unaffected; roll clock to next day → true again; concurrency sanity (10
  threads, one user, limit 10, exactly 10 trues... actually 10 threads all
  succeed — use 12 threads, assert exactly 10 true).
- **Cucumber** (`llm_quota.feature`): pre-set the quota document to `count: 10`
  for the current user directly in Mongo (Given step), POST fresh content →
  429. Second scenario: identical content with quota exhausted → still the
  "no new content" 200 (proves the ordering).
- **Manual backstop** (not code): set a spend limit in the Anthropic console —
  the only cap that holds against server-side retry bugs.

---

## Step 08 — Test sweep + verification (`08-tests`)

### 08.1 New `MongoIndexesIT`

Boot each repository/service against a fresh Testcontainer database and assert
via `collection.listIndexes()`:
- `users`: unique on `appUsername`
- `sessions`: unique on `tokenHash`; TTL (`expireAfterSeconds: 0`) on `expiresAt`
- `inviteCodes`: unique on `code`
- `llmQuota`: unique compound on `{userId, date}`

### 08.2 Cross-user isolation scenario (`isolation.feature`)

- Sign up users A and B (two invite codes, two tokens — extend `SignupSteps`
  to hold a named-token map in `ScenarioContext`).
- A logs an activity. B calls `DELETE /otj-services/delete-last-row` → **404**;
  the count of A's logs is still 1; B's count is 0.
- This is the regression test for the plan's "a token belonging to user A cannot
  read or mutate user B's activity logs".

### 08.3 Make the integration suite un-skippable (recommended)

`mvn test` silently skips every `*IT` class (surefire default includes). Now
that the auth suite is the security boundary, add to the surefire config in
`pom.xml`:

```xml
<configuration>
  <includes>
    <include>**/*Test.java</include>
    <include>**/*IT.java</include>
  </includes>
  <excludes>
    <exclude>**/AzureIdDriverIT.java</exclude>  <!-- drives a real browser/login -->
  </excludes>
</configuration>
```

Note this makes `mvn test` need a container runtime — true locally (Podman) and
on GitHub runners (Docker preinstalled), so master CI keeps working and finally
tests something real.

### 08.4 Run the plan's verification checklist

From `auth-multiuser-plan.html`, each with where it's now proven:

| Check | Proven by |
|---|---|
| No-token request → 401; valid token succeeds | `authentication.feature` (02) |
| A's token can't touch B's logs | `isolation.feature` (08.2) |
| Signup without valid code → 403; no double redemption incl. concurrent | `signup.feature` + `InviteCodeRepositoryIT` (04) |
| `grep -ri "PASSWORD_ENCRYPTION_KEY\|PasswordCipher\|Tailscale" src/` empty | grep in 08 (the `tailscaleUserId` Mongo field name is the one legacy remnant — rename field + migration, or accept and document) |
| No recoverable password in `users` | `registration.feature` assertion (03) → moves to signup scenarios in 05 |
| Full cycle logs contain no password / MFA code | manual smoke (05.4) + log-audit grep |
| 11th `/log-activities` in a day → 429; next-day rollover | `llm_quota.feature` + `LlmQuotaServiceIT` (07) |
| Bad-password flood trips limiter | `rate_limiting.feature` (06) |
| All four index sets exist | `MongoIndexesIT` (08.1) |
| End-to-end from a phone over public HTTPS | blocked on step 09 (domain) — manual |

### 08.5 Data reset (one-time, manual, before first real use)

No migration: drop `users` (and orphaned `activitylogs` keyed by the old
tailnet login) in Atlas, mint invite codes, sign up fresh. Plan doc explicitly
accepts this — the app has no users other than its author.

---

## Sequencing summary

| Order | Branch | PR into | Blocking dependency |
|---|---|---|---|
| 1 | `04-invite-signup` | staging | #14 + #16 merged |
| 2 | `05-stop-storing-creds` | staging | 04 merged (signup replaces register in tests) |
| 3 | `06-rate-limiting` | staging | 04 merged (limits live in AuthResource); independent of 05 in principle, keep the order anyway |
| 4 | `07-llm-quota` | staging | none hard; keep order |
| 5 | `08-tests` | staging | 04–07 merged |

Step 09 (Caddy/EIP/domain/CDK) stays parked until a domain is registered;
nothing in 04–08 needs it. When 08 is green and the checklist passes, one PR
`staging → master` ships the whole thing (and triggers the real deploy).
