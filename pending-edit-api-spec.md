# Editing a pending activity — API spec

Spec for the endpoint the mobile app's **Pending** tab needs to repair a row in place
(`otj-mobile/src/components/activity-editor.tsx`, already written against this contract).

Branch: `add-endpoint-for-manual-edits`. Follows on from 05.5 list/delete
(`step-05.5-pending-api-spec.md`) and reuses its shapes throughout.

## What the screen needs

A pending row is the model's reading of one line of free text. It gets the duration right
and the date wrong, or it clips the description at a clause boundary. Today the only repair
is delete-and-retype, which means re-running the LLM to fix a typo.

So: swipe a row right, correct any of the four things a person can reasonably want to
correct — date, start time, duration, description — and save.

One endpoint. Everything below is on the existing `@Authenticated` `OtjServicesResource`, so
the userId comes from `SecurityContext` and is never in the path or body.

---

## `PUT /otj-services/pending/{id}`

Replaces the editable fields of one **unposted** row owned by the caller.

**PUT, not PATCH.** The only client holds the whole row and puts every editable field on
screen at once, so a full replacement of the editable subset avoids the absent-versus-null
ambiguity a partial update carries. All five fields are required; `activityTime` may be `""`.

**Request:**

```json
{
  "activityDate": "2026/08/12",
  "activityTime": "09:00",
  "hours": 2,
  "minutes": 30,
  "activityImpact": "Paired on the auth filter and wrote its tests"
}
```

**200 response** — the updated row as a bare `PendingActivity`, the same object that appears
in `GET /pending`'s `activities` array:

```json
{
  "id": "68f3c1a49b2e4d0012ab34cd",
  "activityDate": "2026/08/12",
  "activityTime": "09:00",
  "hours": 2,
  "minutes": 30,
  "activityImpact": "Paired on the auth filter and wrote its tests",
  "createdAt": "2026-08-07T18:22:12Z"
}
```

Returning the row rather than 204 lets the client write it straight into its react-query
cache and redraw without a refetch — the same reasoning that made `log-activities` return
saved rows rather than just a count.

`createdAt` is still derived from the ObjectId timestamp, so **it does not move when a row is
edited**. "Added 3 hours ago" keeps meaning when the row was added, not when it was last
touched. If a "last edited" indicator is ever wanted, that needs a new stored field; don't
repurpose this one.

### Validation

Every rule here already exists somewhere in the system — in the JSON schema the model is
constrained to (`ParsedActivities.Entry`), in `llm_prompt.txt`, or in the payload `OtjDriver`
builds for OneAdvanced. None of it is currently reachable as code that can check an incoming
JSON body, so it has to be written here. The point is that a hand-edited row cannot end up
less valid than a parsed one.

| Field | Rule | Where the rule comes from |
|---|---|---|
| `activityDate` | Matches `^\d{4}/\d{2}/\d{2}$`, is a real calendar date, and is not in the future | Schema says `YYYY/MM/DD`; prompt rule 1 resolves relative dates to "the most recent matching date in the past — never a future date"; `OtjDriver:239` does `.replace("/", "-")` to build the OA payload |
| `activityTime` | Either `""`, or matches `^([01]\d\|2[0-3]):[0-5]\d$` **and** falls within 09:00–18:00 | Schema: "Start time as HH:MM with a two-digit hour… Empty string when the input gives no start time." Prompt rule 5 bounds a given start time to working hours |
| `hours` | Integer, 0–24 | Sanity bound only — see below |
| `minutes` | Integer, 0–59. Never 60+ | Schema: "Minutes spent, 0 to 59. Never 60 or more — carry into hours." `OtjDriver:245` formats it `%02d` |
| `hours` + `minutes` | Total must be > 0 | A zero-duration row is exactly what `missing_duration` exists to reject |
| `activityImpact` | Non-blank after `strip()`, whitespace runs collapsed to single spaces, 1–1000 chars | `missing_description`. The 1000 is a bound on the OA form field, not something this repo knows — worth confirming against the real form |

**No duration ceiling.** Deliberate: the parser applies none. It checks that a *start time*
sits inside 09:00–18:00 and never checks that the duration fits inside them, so a 12-hour
entry can be logged through `log-activities` today. The edit endpoint matching that keeps one
rule set across both paths; 0–24 on `hours` is only there to stop an absurd integer reaching
Mongo. If a real ceiling is ever wanted, apply it in **both** places at once.

**Validate by hand; don't reach for `@Valid`.** The mobile client's `errorMessage()`
(`otj-mobile/src/lib/api.ts`) prefers the server's own `{"error": "..."}` body and falls back
to a generic "Please check the details you entered and try again." for anything else.
Bean-validation violations don't use that shape, so a constraint annotation would reach the
user as the generic string and the specific reason would be lost. `logActivities` already
hand-checks blank `content` for the same reason.

### Responses

| Status | Body | When |
|---|---|---|
| 200 | the updated `PendingActivity` | Updated |
| 400 | `{"error": "'<id>' is not a valid activity id."}` | `ObjectId.isValid(id)` is false — same check, same wording as `deletePending` |
| 400 | `{"error": "<the specific field problem>"}` | Any field rule above fails. One message, naming the field and what was wrong with it |
| 401 | filter's own body | No or expired bearer token; `AuthenticationFilter` rejects before the method runs |
| 404 | `{"error": "No unposted activity log with that id for this user."}` | Unknown id, another user's, or already posted |

**The 404 is one body for all three misses**, byte-identical to the delete endpoint's.
Distinguishing them would confirm that an id the caller does not own exists. The client is
already written to treat a 404 here as "the row is gone" — it closes the sheet and refetches
rather than reporting a failure, exactly as it does for delete.

---

## Repository

Add to `ActivityLogRepository`:

```java
/** Replaces the editable fields of one unposted log owned by {@code userId}.
 *
 *  <p>Returns {@code null} when the filter matched nothing — unknown id, someone else's id, or
 *  already posted. Ownership and {@code posted} are part of the filter rather than a check after
 *  the read, so a caller can never learn that an id they do not own exists. */
public ActivityLog updateUnpostedById(String userId, ObjectId id,
                                      String activityDate, String activityTime,
                                      int hours, int minutes, String activityImpact) {
    Document updated = collection.findOneAndUpdate(
            Filters.and(
                    Filters.eq("_id", id),
                    Filters.eq("tailscaleUserId", userId),
                    Filters.eq("posted", false)),
            Updates.combine(
                    Updates.set("activityDate", activityDate),
                    Updates.set("activityTime", activityTime),
                    Updates.set("hours", hours),
                    Updates.set("minutes", minutes),
                    Updates.set("activityImpact", activityImpact)),
            new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));

    if (updated == null) {
        log.info("No unposted activity log {} found to update for user {}", id.toHexString(), userId);
        return null;
    }
    log.info("Updated activity log {} for user {}", id.toHexString(), userId);
    return fromDoc(updated);
}
```

Three things that are load-bearing:

- **`posted: false` is in the filter, not checked afterwards.** Besides the disclosure point,
  it settles the race where a submission run posts the row while the edit sheet is open: the
  write matches nothing, and the user gets the same "it's gone" 404 they would get for a
  deleted row.
- **`ReturnDocument.AFTER`** — the endpoint answers with the new state, so there is no
  follow-up read.
- **`Updates.combine` names exactly five fields.** `tailscaleUserId`, `learnerId`, `unitId`,
  `activityType` and `posted` are never the caller's to set, and nothing here replaces the
  whole document. `markAsPosted` is not a precedent to copy: it filters on `_id` alone with no
  ownership check, which is safe only because its input came from a per-user query.

## Resource

New method on `OtjServicesResource`, next to `deletePending`:

```java
@PUT
@Path("/pending/{id}")
public Response updatePending(@PathParam("id") String id, UpdateActivityRequest body,
                              @Context SecurityContext sc) { … }
```

Order of operations: `resolveUserState(sc)` → `ObjectId.isValid(id)` → validate the body →
`updateUnpostedById` → `PendingActivity.from(updated)` → 404 if null.

New DTO, `api/dto/UpdateActivityRequest.java`:

```java
public record UpdateActivityRequest(
        String activityDate,
        String activityTime,
        int hours,
        int minutes,
        String activityImpact
) {}
```

Note `hours` and `minutes` are primitive `int`, so a missing key deserialises to 0 rather than
null — which the "total must be > 0" rule then rejects with a real message. A boxed `Integer`
would let you tell "absent" from "zero", but there is no useful difference between them here.

## Tests

`src/test/resources/features/edit_pending.feature`, alongside `log_activities.feature`:

- a valid edit returns 200 with the new values
- the fields not in the request are unchanged afterwards (`learnerId`, `unitId`,
  `activityType`, `posted`, `createdAt`)
- another user's id → 404, with the exact shared message
- an already-posted row → 404
- a malformed id → 400
- each field rule → 400: bad date format, impossible date, future date, bad time format,
  time outside 09:00–18:00, minutes ≥ 60, zero total duration, blank description

The ownership-and-posted filter is the part worth proving first, in
`ActivityLogRepositoryIT` against a real Mongo, before any of the HTTP layer exists.

## Client, for reference

Already implemented on `otj-mobile@main` against this contract:

| File | Role |
|---|---|
| `src/lib/activity-edit.ts` | Normalisation and validation, mirroring the table above so a bad value is caught before the round trip |
| `src/lib/pending-api.ts` | `updatePending(id, update)` |
| `src/components/activity-editor.tsx` | The edit sheet |
| `src/app/(tabs)/pending.tsx` | Swipe right to open it; swipe left still deletes |

The client normalises before it sends — `2026-8-9` → `2026/08/12` style padding, `9:5` →
`09:05`, minutes ≥ 60 carried into hours, whitespace in the description collapsed. **The
server must not rely on any of that.** It is there so the user sees an error in the field
rather than after a round trip; the CLI in `scripts/otj` and curl exist too.

## Unrelated bug found while writing this

`OtjDriver:240` builds the OA payload as `"T" + log.activityTime() + ":00"`. When
`activityTime` is `""` — the normal case for any entry that never mentioned a time — that
produces `"T:00"`. Not caused by this work, but this endpoint makes clearing a start time a
deliberate user action rather than something that only happens by omission, so it is now
easier to reach.
