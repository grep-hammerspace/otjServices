# Remove content diffing — implementation plan

Deletes `ContentDiffer`, the `lastContent` snapshot, and `DELETE /reset-notes`.

`log-activities` becomes: take `content`, hand it to the LLM, write the rows. No state
carried between submissions.

## Why

The differ was built for the Apple Shortcuts prototype, where the client posted the entire
contents of a notes file on every invocation and the server had to work out which lines it
had not seen. **No current client does that.**

- **Mobile** (`otj-mobile/src/lib/activities-api.ts:70`) joins the composer's boxes and sends
  only the current batch. Its own docstring: "Sending only the current batch — not a running
  document — is what makes that dedup line up with what the user sees."
- **CLI** (`scripts/otj` on `tailscale`) sends one `"text"` argument per invocation, and
  already ships a `--fresh` flag whose entire job is to call `/reset-notes` first — a bypass
  switch for the differ.

So `lastContent` is no longer a document, it is *the previous request body*, and the dedup
window is exactly one submission deep: send A, then B, then A again, and A goes through.

What that residual dedup buys is not worth what it costs:

- **A repeated activity is silently dropped.** Log "Daily standup" Monday; Tuesday's batch is
  "Daily standup" again → `{"status": "no new content"}`, no row. This was rare when the file
  kept growing; with small batches an identical-to-previous submission is *common*. The
  false-negative rate went **up** when the client changed.
- **It gets the retry case wrong** — the one case it looks designed for. Server writes the
  rows, response is lost, client retries: it gets back `"no new content"` and shows the user
  nothing was added, while the rows exist.
- **It blocks step 05.5.** §3 of `step-05.5-pending-api-spec.md` — delete a pending row,
  retype the line, server refuses — is caused by the differ. Option (a) exists solely to keep
  the differ alive next to a delete button.
- **Two landmines ride along.** `UserRepository.save()` is a `replaceOne` and `toDocument`
  omits `lastContent` (`UserRepository.java:40,100`), so re-registering silently wipes the
  snapshot; and `saveLastContent` runs unconditionally at `OtjServicesResource.java:169` even
  when every line errored, burning unparseable lines so they are never retried.

Duplicates stop being invisible and become **visible and swipeable** once the Pending tab
lands. That is a better answer than silent refusal.

---

## Gate 0 — before writing any code

**Confirm no Apple Shortcut is still pointed at `/log-activities`.** Neither repo contains a
whole-document client, but the Shortcut lives on a phone, not in git. If one is still in use,
removing the differ turns every invocation into a full re-log of the notes file. Check the
Shortcuts app; if one exists, retire or rewrite it first.

Nothing else in this plan is reversible-by-inspection, so this is the only hard prerequisite.

## Sequencing against step 05.5

**Do this before 05.5, not after.** Then 05.5 ships without option (a) at all — no
`sourceLine` on `ActivityLog`, no `raw` on `ParsedActivities.Entry`, no
`removeLineFromLastContent`, and Commit 1 of `step-05.5-implementation-plan.md` (the wide
7-call-site fanout) disappears. The option-(a) Cucumber regression becomes unnecessary
because the trap it guards against cannot occur.

If 05.5 has already landed by the time this runs, add a commit that drops
`removeLineFromLastContent` and its two call sites. **Keep `sourceLine` and `Entry.raw`** —
provenance is independently useful for debugging and for showing "from: <line>" in the UI,
and deleting a stored field is more churn than leaving it.

## Branch and working copy

- Worktree off a fresh `staging`, branch `remove-content-diffing`, so the main checkout stays
  free.
- Regular merge/rebase into `staging`, **not squash**.
- CI is master-only; run the gate locally before the PR (see [Verification](#verification)).

---

## Commit 1 — remove the diff from the request path

**`api/OtjServicesResource.java`:**

- Drop the import at `:13`.
- Replace `:136-145` (the `getLastContent` / `computeDiff` / `"no new content"` early return)
  with nothing. `content` — already stripped and non-blank-checked at `:116-125` — goes
  straight to `llmService.parseActivities(...)` at `:151`.
- Drop `saveLastContent` at `:169`.
- Delete `resetNotes()` at `:197-205` and its `@DELETE @Path("/reset-notes")`.

The `log.info("Diff contains new content ({} chars), calling LLM", diff.length())` at `:147`
becomes `log.info("Calling LLM with {} chars", content.length())`.

Everything else in the handler stays: the blank-content 400, the `findByUserId` 400 (it needs
`learnerId`), the LLM exception ladder, the save loop, and `ActivityLogResponse`.

**`db/UserRepository.java`** — delete `getLastContent` / `saveLastContent` /
`clearLastContent` (`:78-98`). `toDocument`/`fromDocument` are untouched; they never knew
about the field.

**`llm/ContentDiffer.java`** — delete the file.

After this commit `ActivityLogResponse` is the only 200 shape `log-activities` can return.

## Commit 2 — tests

**Delete `llm/ContentDiffTest.java`** (8 tests, entirely about the deleted class).

**`db/UserRepositoryIT.java`** — delete the three `lastContent` tests at `:96`, `:104`, `:113`.

**`features/reset_notes.feature`** — delete the file.

**`features/log_activities.feature`** — the two scenarios that assert diffing behaviour go:

- `Identical content returns no new content` (`:18-22`)
- `Appended content processes only the new line` (`:24-28`)

Replace the first with the inverse, which is the behaviour change this PR makes and the thing
worth pinning:

```gherkin
  Scenario: Resubmitting identical content logs it again
    Given I have already logged "Worked 2 hours on assignment from 10:00"
    When I POST "/otj-services/log-activities" with content "Worked 2 hours on assignment from 10:00"
    Then the response status is 200
    And the response body contains "\"rowsAdded\":1"
    And there are 2 activity logs in the database for user "test-user-id"
```

`LogActivitiesSteps` already provides `I have already logged {string}` (`:28`) and
`I POST {string} with content {string}`. The count step at `:47` already takes an `int` and
does a plain `countDocuments`, so only its Gherkin phrasing is singular. Widen the expression
in place — Cucumber alternation and optional text keep every existing scenario matching:

```
@And("there is/are {int} activity log(s) in the database for user {string}")
```

Do not add a second step definition for the plural.

**`features/signup.feature` — this one will bite.** Two scenarios use
`DELETE /otj-services/reset-notes` as a generic "is this token accepted" probe:

- `:66` — after logout, expects **401**
- `:79` — "A token from signup works on a protected endpoint", expects **200**

Both need a different endpoint. Pick by what has landed:

- **If 05.5 is in:** use `GET /otj-services/pending` — authenticated, 200 on an empty queue,
  a genuinely better probe than a mutating DELETE.
- **If not:** use `GET /otj-services/prepare-browser` and change the second scenario's
  expectation to **501**. It is authenticated and deterministic; 501-not-401 still proves the
  token was accepted. Leave a comment saying so, because a bare `Then the response status is
  501` reads like a bug otherwise.

Do not substitute `DELETE /delete-last-row` — it 404s when the queue is empty, so the
"token works" scenario would fail for the wrong reason.

## Commit 3 — docs

**`AGENTS.md`:**

- `:64` — drop `ContentDiffer (old vs new notes)` from the `llm/` layout line.
- `:133` — remove `DELETE /reset-notes` from the authenticated endpoint list.

**`step-05.5-pending-api-spec.md`** — §3 ("The `lastContent` interaction — read this before
implementing") and the option (a)/(b)/(c) decision are now moot. Replace the section body with
a one-paragraph note that content diffing was removed and the trap no longer exists, and strip
the option-(a) rows from the §4 file list. Same for the corresponding commits in
`step-05.5-implementation-plan.md`. Leaving stale "recommended" guidance in a spec someone
implements later is how the machinery gets rebuilt by accident.

## Commit 4 (optional) — drop the dead Mongo field

Existing user documents keep an inert `lastContent` string. Nothing reads it. Either leave it,
or:

```bash
mongosh "$MONGO_URI" --eval 'db.users.updateMany({}, {$unset: {lastContent: ""}})'
```

Run against prod only after the new image is deployed, so a rollback still has its snapshot.
This is housekeeping, not a migration — the code is correct either way.

---

## Follow-ups in other repos

Not in this PR; both are separate repos/branches.

**`otj-mobile`** — the `"no new content"` arm becomes unreachable. Nothing breaks if it is
left in place, so this can lag the backend safely:

```
src/lib/activities-api.ts:39-44   drop the union arm; the :63-68 docstring is now wrong
src/app/(tabs)/index.tsx:70-79    drop the LastResult branch
src/components/activity-composer.tsx:300-308  drop the Outcome branch
src/components/result-banner.tsx:7            the comment cites "no new content" as an amber case
```

While in there: **confirm the Log button is disabled while a submit is in flight.** With the
differ gone, a double-tap creates duplicate rows instead of being absorbed. That is the one
user-visible regression, and client-side debounce is the cheap half of the fix.

**`scripts/otj` (tailscale branch)** — `reset` (`:38`, `:179`) and `--fresh` (`:36`, `:173`)
call the deleted endpoint, and the `:53` help text describes diffing. Must be updated in the
same merge that carries this change to `tailscale`, since that branch is defined as
`master` + the CLI. The calls discard output (`>/dev/null`) so a stale CLI degrades to a
harmless 404 rather than an error, but do not rely on that.

---

## Verification

```bash
mvn -B clean test                                        # unit; ContentDiffTest is gone

DOCKER_HOST=unix:///run/user/1000/podman/podman.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -B test -Dtest='CucumberIT,UserRepositoryIT,ActivityLogRepositoryIT,SessionTokenServiceIT,InviteCodeRepositoryIT,SessionRepositoryIT'
```

Integration classes must be named explicitly — Surefire's default includes skip `*IT`.
The container runtime here is Podman.

Manual smoke against local Mongo: POST the same line twice and confirm two rows, then
`DELETE /otj-services/reset-notes` and confirm 404.

## Risks

| Risk | Mitigation |
|---|---|
| A live Apple Shortcut still posts a whole file → full re-log, real LLM spend | Gate 0. Check before starting |
| Double-tap now creates duplicate rows | Real, accepted. Client-side in-flight disable; the Pending tab makes it visible and deletable, which is the point |
| Retry after a lost response duplicates rows | Today it produces a *wrong client state* instead, which is worse. Proper fix is an idempotency key — out of scope, recorded below |
| `signup.feature` auth probes silently weaken | Both scenarios are rewritten deliberately, not deleted |
| A deployed CLI calls `/reset-notes` | Degrades to 404 with discarded output; tracked as a tailscale follow-up |

## Out of scope

- **Idempotency keys.** If double-submit protection is wanted back, the correct mechanism is a
  client-generated key per submit with a short server-side key→response cache, which handles
  the lost-response retry correctly. That is a feature, not part of this removal, and the
  Pending tab may make it unnecessary.
- Step 05.5 itself.
- `log-activities` still echoing raw `ActivityLog` records including `tailscaleUserId` — a
  known leak, a breaking client change, tracked in the 05.5 spec.
