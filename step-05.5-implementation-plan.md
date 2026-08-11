# Step 05.5 — Pending activities API: implementation plan

Implements `step-05.5-pending-api-spec.md`. Backend (`otjServices`) only — the mobile
work in §5 of the spec is a separate PR in `otj-mobile` once these endpoints are on
`staging`.

**Option (a) is no longer in scope.** Content diffing has been removed
(`remove-content-diffing-plan.md`), so the trap §3 of the spec described cannot occur and the
line-provenance workaround it recommended is unnecessary. No `sourceLine` on `ActivityLog`, no
`raw` on `ParsedActivities.Entry`, no `removeLineFromLastContent`. The commit that plumbed
`sourceLine` end to end through 7 call sites is gone, so this plan now starts at the repository
layer.

## Branch and working copy

- Worktree off a fresh `staging`, branch `05.5-pending-api`, so the main checkout stays free.
- CI is master-only; the gate runs locally before the PR (see [Verification](#verification)).
- Regular merge/rebase into `staging`, not squash.

---

## Commit 1 — repository reads and addressed delete

**`db/ActivityLogRepository.java`**, two methods:

```java
/** Newest first (_id descending). Separate from getUnpostedActivityLogsFor by design —
 *  see the note below. */
public List<ActivityLog> findUnpostedNewestFirst(String userId)

/** Deletes one unposted row owned by userId. False when the filter matched nothing
 *  (unknown id, someone else's id, or already posted). */
public boolean deleteUnpostedById(String userId, ObjectId id)
```

`deleteUnpostedById` is one `findOneAndDelete` with ownership in the filter, exactly as the
spec spells out:

```java
Filters.and(Filters.eq("_id", id),
            Filters.eq("tailscaleUserId", userId),
            Filters.eq("posted", false))
```

`boolean` matches the spec's file list. An earlier draft returned the deleted row so the
handler could pull its `sourceLine` out of `lastContent`; with diffing gone there is nothing
to pull, so `deleteLastActivityLog` also keeps its existing `boolean` and `UserRepository` is
untouched by this step.

**Do not touch `getUnpostedActivityLogsFor`.** `OtjDriver:188` and `AzureIdDriver:508` use it
to decide submission order; adding a sort there silently reorders what reaches OneAdvanced.

---

## Commit 2 — DTOs and the two endpoints

**`api/dto/PendingActivity.java`** — `id`, `activityDate`, `activityTime`, `hours`,
`minutes`, `activityImpact`, `createdAt`, in the spec's field order. No `tailscaleUserId`,
`posted`, `learnerId`, `unitId` or `activityType`, each for the reason the spec gives.

`createdAt` is derived, not stored:

```java
DateTimeFormatter.ISO_INSTANT.format(new ObjectId(log.id()).getDate().toInstant())
```

ObjectId timestamps are second-precision, so this yields `2026-08-07T18:22:12Z` — exactly the
spec's shape, no truncation needed.

**`api/dto/PendingResponse.java`** — `{activities, count, totalMinutes}` with a
`static PendingResponse from(List<ActivityLog>)` that maps the rows and computes
`count = size` and `totalMinutes = sum(hours * 60 + minutes)`. Keeping the arithmetic in the
DTO keeps the resource a thin handler, matching how the other endpoints read.

**`api/OtjServicesResource.java`** — two handlers, placed next to `deleteLastRow` (line 183):

```java
@GET  @Path("/pending")        getPending(@Context SecurityContext sc)
@DELETE @Path("/pending/{id}") deletePending(@PathParam("id") String id, @Context SecurityContext sc)
```

- `getPending`: `resolveUserState`, `findUnpostedNewestFirst`, `Response.ok(PendingResponse.from(rows))`.
  **No `userRepository.findByUserId` check** — the spec is explicit that reading needs nothing
  from the user document, unlike `log-activities:127` which needs `learnerId`. An unregistered
  caller gets `{"activities": [], "count": 0, "totalMinutes": 0}`.
- `deletePending`: validate the id, delete, 204.

  ```java
  if (!ObjectId.isValid(id)) return 400;
  if (!activityLogRepository.deleteUnpostedById(userId, new ObjectId(id))) return 404;
  return Response.noContent().build();                   // unknown / not yours / already posted
  ```

  **Minor deviation:** `ObjectId.isValid` instead of catching `IllegalArgumentException` from
  the constructor. Same outcome — a 400 rather than a 500 — without exception control flow.
- `deleteLastRow` is unchanged. Its response stays `200 {"status":"ok"}` — the `scripts/otj`
  CLI on the `tailscale` branch reads it, and this is not the place to break it.

The 404 body follows the existing error shape (`{"error": "..."}`), and says the same thing for
all three miss cases — confirming an id exists is the leak the login and invite handlers avoid.

---

## Commit 3 — tests

**`ActivityLogRepositoryIT`** (extends the existing class, whose fixtures already use
per-test user ids like `user-3` to stay independent):

| Test | Asserts |
|---|---|
| `findUnpostedNewestFirst_returnsNewestFirst` | three saves come back `_id` descending |
| `findUnpostedNewestFirst_excludesPosted` | a `markAsPosted` row is invisible |
| `deleteUnpostedById_removesOnlyTheTarget` | middle of three goes; the other two remain |
| `deleteUnpostedById_otherUsersId_returnsFalseAndLeavesRow` | cross-user delete is a miss *and* the row survives |
| `deleteUnpostedById_postedRow_returnsFalse` | already-posted is a miss |

**`src/test/resources/features/pending.feature`** — resource-level behaviour over real HTTP,
which is how the other endpoints are covered (there is no unit-level resource test in this repo):

- empty queue → 200 with `count: 0` (not 404)
- `GET /otj-services/pending` without a token → 401
- `DELETE /otj-services/pending/not-a-hex-id` → 400
- `DELETE /otj-services/pending/<24-hex that doesn't exist>` → 404
- add two → list shows two newest-first with a non-null `id` each → delete the head → list shows one

The "delete a line, retype it, get a row" regression that earlier drafts put here is gone with
the differ. `log_activities.feature` pins that a resubmission always produces a row, and this
screen has no way to reintroduce the trap.

**`src/test/java/integration/PendingSteps.java`** — new glue. `HttpSteps` already provides
`I GET {string}`, `I GET {string} without a token`, `I DELETE {string}` and
`the response status is {int}`, and `LogActivitiesSteps` provides
`a registered user with learnerId {string}` and `I have already logged {string}`. New steps:

- `the pending list has {int} activities`
- `the pending list totals {int} minutes`
- `the first pending activity has impact {string}` (ordering assertion)
- `I DELETE the first pending activity` (reads the id out of the last response — this is the
  step that proves the id is a usable delete handle)

---

## Commit 4 — docs

`AGENTS.md`: add `GET /pending` and `DELETE /pending/{id}` to the authenticated endpoint list
(line ~132), and add `ActivityLogRepositoryIT`-adjacent nothing else — the Surefire invocation
at line 196 already names `CucumberIT` and `ActivityLogRepositoryIT`.

---

## Verification

```bash
mvn -B clean test                                        # unit

DOCKER_HOST=unix:///run/user/1000/podman/podman.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -B test -Dtest='CucumberIT,UserRepositoryIT,ActivityLogRepositoryIT,SessionTokenServiceIT,InviteCodeRepositoryIT,SessionRepositoryIT'
```

Integration classes must be named explicitly — Surefire's default includes skip `*IT`.
Then a manual smoke against a local Mongo + `curl`, mainly to eyeball the `createdAt` format
and the 204-with-no-body that the mobile `apiJson` relies on.

## Risks

| Risk | Mitigation |
|---|---|
| A new sort leaks into `getUnpostedActivityLogsFor` and reorders what reaches OneAdvanced | Separate read method; the existing one is not touched. `OtjDriver:188` and `AzureIdDriver:508` are the callers to check |
| `deletePending` confirms an id exists to a non-owner | Ownership is in the `findOneAndDelete` filter, not a check after the read, and all three miss cases share one 404 body |
| `createdAt` derived from the ObjectId drifts from a stored timestamp later | Second precision is enough for "added 5 minutes ago"; if a real timestamp is ever needed the DTO is the only thing that changes |

## Out of scope

Editing a pending row, paging, listing posted history, and the `otj-mobile` UI.

Migrating `log-activities` off raw `ActivityLog` records was originally deferred here as a
breaking client change. It was pulled into this branch instead, since `PendingActivity` made it
near-free — see the final commit. It **is** still breaking, and the client updates are the
follow-up it implies.
