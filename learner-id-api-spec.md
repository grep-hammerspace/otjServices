# Correcting a learner ID — API spec

Spec for the two endpoints the mobile app's **Submit** tab needs to show, and repair, the
learner ID captured at signup (`otj-mobile/src/lib/profile-api.ts`, already written against
this contract).

Branch: `add-learner-id-endpoint`, off `staging`. Standalone — depends on nothing beyond what
is on `staging` today. **Implemented on that branch**; this document is the reasoning behind
the shape, kept because the *why* of each decision is not recoverable from the code.

## Why

`learnerId` is typed once, in the fourth field of the signup form, and then never shown
again. Nothing reads it back — not the app, not the CLI — and nothing validates it beyond
`@NotBlank`, because only OneAdvanced knows what a real learner ID looks like.

So a typo in it is invisible. The account works, login works, `log-activities` writes rows
happily, and the first sign of trouble is a submission run in which OneAdvanced rejects every
row. `AuthResource:97` does `body.learnerId().strip()` and `UserRepository` stores the result
verbatim; `OtjDriver:236` and `AzureIdDriver:556` `strip()` it again on the way out and put it
straight in the payload. Nothing in that path can tell a wrong ID from a right one.

### `POST /otj-services/register` is not the fix

It looks like one — it is authenticated, it takes a `learnerId`, and `OtjServicesResource:110`
writes it. But it calls `userRepository.save(...)` with a **freshly constructed `User`**, and
`save` is a `replaceOne` with `upsert(true)`. So it overwrites the whole document: it re-hashes
whatever `password` it was handed into `appPasswordHash`, resets `appUsername` to whatever
`username` it was handed, and stamps `createdAt` to `Instant.now()`.

Correcting a learner ID through it therefore means retyping the app password exactly right —
get it wrong and you have silently changed your own login — and losing the signup date either
way. It is a leftover from the pre-signup registration flow, and `@NotBlank` on all three
fields means it cannot be called with only the field you want to change. Leave it alone; it is
not what this needs.

Today the real fix is a Mongo shell. That is a reasonable answer for the person who runs the
database and no answer at all for anyone else, so: two endpoints, one to read the account and
one to correct the learner ID in it.

---

## Where these live

**Not on `AuthResource`.** That class's javadoc makes a point of carrying no `@Authenticated`
annotation — "these are the endpoints a caller reaches before holding a token, so they are
anonymous by construction rather than by omission". Both endpoints here require a token, and
`@Authenticated` binds per class, so adding them there would either break that property or
force a method-level annotation that makes the class's own doc false.

New class, `api/AccountResource.java`:

```java
@Path("/auth/me")
@Produces("application/json")
@Consumes("application/json")
@Authenticated
@Singleton
public class AccountResource {
    private final UserRepository userRepository;
    …
}
```

`@Path("/auth/me")` on the class with no `@Path` on the methods — JAX-RS matches the class
path exactly, so `GET` and `PATCH` land on the two methods below. The URL stays under `/auth`
because that is where the client's mental model puts it; only the Java class is separate.

It injects `UserRepository` and nothing else. In particular **not `UserStateStore`** — there
is no `resolveUserState(sc)` here. That helper exists to park a browser driver for the submit
flow; reading or writing a field on the user document needs no driver, and calling it would
allocate one on every profile read. Use `sc.getUserPrincipal().getName()` directly.

---

## `GET /auth/me`

The signed-in account.

**200 response:**

```json
{
  "username": "asad",
  "learnerId": "L1234567"
}
```

`username` is `appUsername` — this app's own login. It is **not** the OneAdvanced username,
which never reaches the server at all. Renamed on the way out because `appUsername` is a name
that exists to stop *server-side* confusion between the two, and the client has only ever had
one username of its own; `PendingActivity` already sets the precedent of a DTO shaped for the
client rather than mirroring the stored document.

Field order is not significant, but the DTO carries exactly these two. **Do not add
`userId`, `createdAt` or anything derived from `appPasswordHash`.** `userId` is an internal
identifier the client has no use for — it is not in any URL, since every endpoint takes the
caller from the token — and a hash has no business in a response body even in a derived form.

| Status | Body | When |
|---|---|---|
| 200 | the account | Found |
| 401 | filter's own body | No or expired bearer token; `AuthenticationFilter` rejects before the method runs |
| 404 | `{"error": "No account found."}` | `findByUserId` returned null |

The 404 is close to unreachable: the token was issued against a userId, so the document was
there at login. It is possible if an account is deleted while a token is live. Return it
rather than NPEing — the client treats it as "could not read your learner ID" and offers a
retry, which is the right thing for both readings.

---

## `PATCH /auth/me`

Corrects the learner ID.

**Request:**

```json
{ "learnerId": "L7654321" }
```

**200 response** — the account as it now stands, the same shape `GET` returns:

```json
{
  "username": "asad",
  "learnerId": "L7654321"
}
```

Returning the account rather than 204 lets the client write it straight into its react-query
cache and redraw as the editor closes, instead of flashing the old value until a refetch lands
— the same reasoning behind `PUT /pending/{id}` returning the row.

**PATCH, not PUT.** The body carries one field and the resource has two. `username` is not
changeable here — it is the unique index the login path reads, and renaming an account is a
different feature with its own collision handling — and a password change is a third. A `PUT`
of the whole profile would have to either accept fields it then refuses to act on, or define
absent-means-unchanged, which is PATCH wearing the wrong verb.

### Validation

There is exactly one rule, and it is deliberately thin:

| Field | Rule |
|---|---|
| `learnerId` | Non-blank after `strip()`, 1–64 chars |

**No format check.** Signup applies none — `SignupRequest` has `@NotBlank String learnerId`
and nothing else — and this endpoint must not be stricter than the door the value came in
through, or an account created through signup could hold a value its own correction endpoint
rejects. More to the point, nothing in this repo knows the real format: the ID is OneAdvanced's,
and the only true validation is a submission run. The 64 is a bound to stop an absurd string
reaching Mongo, not a claim about the format.

**`strip()` before storing**, matching `AuthResource:96`. Both drivers `strip()` again on the
way out, so a stored trailing space would not break a submission — but it would sit in the
database forever and show up in the client's card, and the signup path already normalises.

**Validate by hand; don't reach for `@Valid`.** Same reason as `PUT /pending/{id}`: the mobile
client's `errorMessage()` (`otj-mobile/src/lib/api.ts`) prefers the server's own
`{"error": "..."}` body, and bean-validation violations do not use that shape, so a constraint
annotation reaches the user as a generic "Please check the details you entered and try again."
A missing key deserialises to `null`, which the blank check must therefore handle.

| Status | Body | When |
|---|---|---|
| 200 | the updated account | Updated |
| 400 | `{"error": "Learner ID cannot be blank."}` | Missing, null, or blank after strip |
| 400 | `{"error": "Learner ID is too long."}` | Over 64 chars after strip |
| 401 | filter's own body | No or expired bearer token |
| 404 | `{"error": "No account found."}` | The update matched no document |

### What this does *not* do

**Existing rows keep the old learner ID.** `learnerId` is copied onto each `ActivityLog` when
the row is written — `log-activities` reads it off the user document at that moment and hands
it to the parser (`OtjServicesResource:144`) — so correcting it applies to what is logged next
and cannot reach what is already queued. Rows sitting in Pending will post under the old value.

That is the right behaviour — a back-fill would silently rewrite rows the user has not looked
at, and the posted ones cannot be rewritten at OneAdvanced anyway — but it is surprising, so
**the client says so**: the editor shows a warning naming how many activities are queued while
the field is open. Do not add a back-fill without changing that text.

---

## Repository

`UserRepository` has `save(User)`, which is a `replaceOne` with `upsert(true)`. **Do not use
it for this.** It would make the change a read-modify-write that clobbers any concurrent write
to the document, and its upsert would insert a fresh user if the read had raced a deletion —
turning a 404 into a half-built account with no password hash. Add a targeted update instead:

```java
/** Sets {@code learnerId} on one user, leaving every other field alone.
 *
 *  <p>Returns the updated user, or {@code null} when no document matched — the account was
 *  deleted while a token for it was still live. A `$set` of the one field rather than
 *  {@code save(User)}: that method replaces the whole document and upserts, so a lost race
 *  would either clobber a concurrent write or resurrect a deleted account without a password
 *  hash. */
public User updateLearnerId(String userId, String learnerId) {
    Document updated = collection.findOneAndUpdate(
            Filters.eq("userId", userId),
            Updates.set("learnerId", learnerId),
            new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));

    if (updated == null) {
        log.info("No user {} found to update learnerId for", userId);
        return null;
    }
    log.info("Updated learnerId for user {}", userId);
    return fromDocument(updated);
}
```

`ReturnDocument.AFTER` so the endpoint answers with the new state without a follow-up read.
The log line records *that* the ID changed and for whom, never the value — a learner ID is not
a secret, but the log has no use for it and `AuthResource` sets the precedent of logging the
identifier and not the payload.

`findByUserId` already exists and covers the `GET`.

## Resource and DTOs

```java
@GET
public Response me(@Context SecurityContext sc) { … }

@PATCH
public Response updateMe(UpdateLearnerIdRequest body, @Context SecurityContext sc) { … }
```

`GET`: `sc.getUserPrincipal().getName()` → `findByUserId` → 404 if null → `AccountResponse.from(user)`.

`PATCH`: `sc.getUserPrincipal().getName()` → validate the body → `updateLearnerId` → 404 if
null → `AccountResponse.from(updated)`.

New DTOs in `api/dto/`:

```java
public record AccountResponse(String username, String learnerId) {
    public static AccountResponse from(User user) {
        return new AccountResponse(user.appUsername(), user.learnerId());
    }
}

public record UpdateLearnerIdRequest(String learnerId) {}
```

`AccountResponse.from` is the seam that keeps `appUsername` from leaking as a name and
`appPasswordHash` from leaking at all — the same job `PendingActivity.from` does for
`ActivityLog`. Never serialise `User` directly.

## Tests

`src/test/resources/features/account.feature` with glue in `integration/AccountSteps.java`, and
three cases on `UserRepositoryIT`. 16 scenarios in all — 12 plus a four-row outline for the blank
forms:

- signup, then `GET /auth/me` → 200 with the username and learner ID that were signed up with
- the response body has **exactly** the keys `username, learnerId` — the guard on
  `appPasswordHash` never appearing, and the one assertion a refactor that serialised `User`
  directly would fail while every `contains` check kept passing. Worth writing first
- `GET /auth/me` with no token → 401
- two accounts, then a `GET` with the first one's token → the first account, never the second's
- `PATCH` with a new ID → 200 with the new value; a following `GET` and the stored document agree
- `PATCH` leaves the rest of the account alone — checked by **logging in again with the original
  password**, not by asserting the hash still looks like bcrypt. A hash of some *other* password
  would pass that
- `PATCH` with `"  L-TRIMMED  "` → stored stripped
- `PATCH` with `""`, `"   "`, `{}` and `{"learnerId":null}` → 400 each, blank message, and the
  stored value unchanged
- `PATCH` with 65 chars → 400, too-long message
- `PATCH` with `"not even slightly an id"` → **200**. Pins the no-format-check rule
- `PATCH` with no token → 401
- `PATCH`, then log an activity → the new row carries the new `learnerId`
- a row logged *before* the `PATCH` still carries the old one — the no-back-fill rule, pinned

On `UserRepositoryIT`, against a real Mongo: the update returns the new user; every other field
survives it; an unknown userId returns null **and inserts nothing** — the assertion that would
catch someone swapping the `$set` back to `save(User)`.

Cucumber glue is global, so `AccountSteps` reuses the existing status, `contains`, `does not
contain` and users-collection-fields steps rather than redefining them; a duplicate is a hard
error across the whole suite, not just the feature that introduced it. Its own steps
authenticate with the **signup token**, not the hook's seeded `test-user-id` one — these
endpoints read and write the caller's user document, and no signup ever created that userId.

## Client, for reference

Already implemented on `otj-mobile@add-submit-flow` against this contract. It was written first
and 404ed against `staging`; this branch is what it was waiting for.

| File | Role |
|---|---|
| `src/lib/profile-api.ts` | `getProfile()`, `updateLearnerId(id)`, the `Profile` type and the react-query key |
| `src/app/(tabs)/submit.tsx` | `LearnerIdCard` — the display card, the inline editor, the queued-rows warning |

The client trims before sending and refuses an empty field without a round trip. As always,
the server must not rely on that; the CLI in `scripts/otj` and curl exist too.
