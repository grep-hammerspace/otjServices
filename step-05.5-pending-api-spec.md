# Step 05.5 — Pending activities API

Spec for the endpoints the mobile app's **Pending** tab needs
(`otj-mobile/src/app/(tabs)/pending.tsx`, today a placeholder).

Both repos already refer to this work as "05.5 list/delete". This is that.

## What the screen needs

The Pending tab shows every activity row that has been written to Mongo but not yet
posted to OneAdvanced, and lets the user delete individual wrong ones before running
the Submit flow. Concretely:

1. **List** the caller's unposted rows, with a stable id per row.
2. **Delete one row by id** — swipe-to-delete, so it must be *that* row, not "the last one".

`DELETE /otj-services/delete-last-row` already exists but is the wrong shape for a list:
it deletes by insertion recency, so deleting the third row down is impossible, and two
quick swipes race each other into deleting the wrong things. Keep it (the CLI in
`scripts/otj` uses it); add the addressed variant alongside.

Everything below is on the existing `@Authenticated` `OtjServicesResource`, so the userId
comes from `SecurityContext` and is never in the path or body — same as every other handler.

---

## 1. `GET /otj-services/pending`

Returns the caller's unposted activity logs.

**Request:** no body, no query params. `Authorization: Bearer …`.

**200 response:**

```json
{
  "activities": [
    {
      "id": "68f3c1a49b2e4d0012ab34cd",
      "activityDate": "2026/08/07",
      "activityTime": "14:00",
      "hours": 1,
      "minutes": 30,
      "activityImpact": "Worked through the Kafka consumer-group chapter",
      "createdAt": "2026-08-07T18:22:12Z"
    }
  ],
  "count": 1,
  "totalMinutes": 90
}
```

**Field notes:**

| Field | Type | Notes |
|---|---|---|
| `id` | string, **never null** | Mongo `_id` hex. This is the delete handle. |
| `activityDate` | string | `YYYY/MM/DD`, as stored. |
| `activityTime` | string | `HH:MM`, or `""` when the line gave no start time. Not null — the empty string is already the convention on this field and the mobile `ActivityRow` type encodes it. |
| `hours` / `minutes` | int | `minutes` is 0–59; the model carries into hours. |
| `activityImpact` | string | The description. Named for the OneAdvanced field, kept for continuity with `log-activities`. |
| `createdAt` | string, RFC 3339 UTC | **Derived from the ObjectId timestamp** (`ObjectId.getDate()`), not a new stored field. Free, and enough for "added 5 minutes ago" in the UI. |

**Deliberately not returned:**

- `tailscaleUserId` — AGENTS.md's rule is that the client never receives the server-minted
  userId. `log-activities` currently violates that by echoing whole `ActivityLog` records;
  don't propagate the leak into a new endpoint. (Fixing `log-activities` to use the same DTO
  is a sensible follow-up but is a breaking client change — out of scope here.)
- `posted` — always `false` by construction of this endpoint. A field that can only hold one
  value invites a client to branch on it.
- `learnerId` — per-user constant, the client already knows its own.
- `unitId` / `activityType` — `""` and `0` for every row ever written (`LlmServiceImpl.toResult`).
  Dead fields. Add them when something sets them.

**Ordering:** newest first — `_id` descending. Two reasons: it matches what the user just
did (add entries in the composer, switch tab, see them at the top), and the head of the list
is then exactly the row `delete-last-row` would remove, so the two endpoints don't disagree
about what "last" means. `activityDate` sorts correctly lexicographically in this format if a
date-ordered view is ever wanted, but that makes the list jump when a back-dated entry is
added — leave grouping-by-date to the client.

**Empty:** `200` with `{"activities": [], "count": 0, "totalMinutes": 0}`. Not 404 — an empty
queue is the normal steady state, and a 404 would make the mobile `apiJson` throw an `ApiError`
for the happy path.

**`totalMinutes`** is `sum(hours * 60 + minutes)`. It exists so the screen can show "3h 45m
queued" without the client re-deriving it, and because the header is the one place an aggregate
is genuinely wanted.

**Errors:** `401` from the auth filter (existing behaviour — the client clears the token and
bounces to login). Nothing else; an unregistered user simply has no rows. Note this differs
from `log-activities`, which 400s when `userRepository.findByUserId` misses — that check is
there because it needs `learnerId`. Reading needs nothing from the user document, so don't
add the check.

---

## 2. `DELETE /otj-services/pending/{id}`

Deletes one unposted row belonging to the caller.

- **204** — deleted.
- **404** — no unposted row with that id **for this user**. Same response for "doesn't exist",
  "belongs to someone else", and "already posted". Ownership must be part of the query filter,
  not a check after the read: `Filters.and(eq("_id", oid), eq("tailscaleUserId", userId), eq("posted", false))`
  in a single `findOneAndDelete`. Returning 403 for someone else's id would confirm the id
  exists, which is the same class of leak the login and invite handlers are careful about.
- **400** — `{id}` is not a valid 24-hex ObjectId. `new ObjectId(s)` throws
  `IllegalArgumentException`; catch it rather than letting it become a 500.

Deleting an already-posted row is not offered: it's gone to OneAdvanced and the app cannot
retract it, so 404 is honest.

---

## 3. The `lastContent` interaction — resolved, nothing to do

**This section described a trap that no longer exists.** Content diffing has been removed
(`remove-content-diffing-plan.md`): `ContentDiffer`, the `lastContent` snapshot and
`DELETE /reset-notes` are gone, and `log-activities` now hands `content` straight to the LLM
with no state carried between submissions.

The trap was that deleting a pending row did not touch `lastContent`, so retyping the same
line came back `{"status": "no new content"}` — the app silently refusing a line it was not
holding anywhere. With no snapshot, a retype is just a new submission and produces a row.

The three options this section used to weigh — (a) line provenance via `sourceLine`, (b)
clearing `lastContent` on delete, (c) telling the user to hit `reset-notes` — are all moot.
**Implement none of them.** Option (a) in particular existed only to keep the differ alive
next to a delete button; do not add `sourceLine` or `Entry.raw` on its account. If provenance
is wanted later it should be justified on its own merits (debugging, showing "from: <line>"
in the UI), not as diffing compensation.

---

## 4. Backend changes

```
api/OtjServicesResource.java     + getPending(), + deletePending(@PathParam("id"))
api/dto/PendingActivity.java     new — the response row above
api/dto/PendingResponse.java     new — {activities, count, totalMinutes}
db/ActivityLogRepository.java    + findUnpostedNewestFirst(userId)
                                 + deleteUnpostedById(userId, id) -> boolean
```

**Do not add the sort to the existing `getUnpostedActivityLogsFor`.** `OtjDriver:188` and
`AzureIdDriver:508` both call it to decide submission order; a new sort there silently changes
the order rows reach OneAdvanced. Add a separate read method.

## 5. Mobile changes this unblocks

- `src/lib/pending-api.ts` — `getPending()` / `deletePending(id)` over `apiJson`. Note
  `apiJson` already returns `undefined` for 204, so the delete call types as `Promise<void>`.
- `src/app/(tabs)/pending.tsx` — `FlatList` + swipe-to-delete, `formatDuration` reused from
  `activities-api.ts`, empty state, pull-to-refresh.
- `ActivityRow` in `activities-api.ts` stays as-is (it describes the `log-activities` response);
  the new `PendingActivity` type is separate until `log-activities` is migrated to the same DTO.

## 6. Tests

- `ActivityLogRepositoryIT` — newest-first ordering; delete-by-id removes only the target;
  delete-by-id with another user's id returns false and leaves the row; posted rows are
  invisible to both.
- Resource-level: 401 without a token; 404 for unknown and for cross-user ids; 400 for a
  malformed id; empty list is a 200.
- Cucumber: add → list shows it → delete → list is empty. The "resubmitting the same line
  produces a row again" regression is no longer needed here — with diffing gone it is pinned
  by `log_activities.feature` and cannot regress from this screen.

Integration tests need naming explicitly in the Surefire invocation — see AGENTS.md.

## 7. Out of scope

- Editing a pending row. Delete-and-retype covers it; an edit endpoint means deciding whether
  the LLM re-parses, and the answer isn't obvious.
- Paging. An unposted queue is a handful of rows between submissions; if that stops being true,
  the bug is in the submit flow.
- Listing *posted* history. Different screen, different question, and OneAdvanced is the
  system of record for it.
