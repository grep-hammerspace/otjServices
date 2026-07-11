# Implement `submitPendingOtjs` on `SmartAssessorDriver`

## Context

`SmartAssessorDriver` (`src/main/java/com/github/grepHammerspace/web/SmartAssessorDriver.java`) already implements the `Driver` login flow (PKCE bypass → Keycloak → Azure AD → MFA push → SmartAssessor session cookies), but `submitPendingOtjs()` is a stub that throws `UnsupportedOperationException`. The sibling `OtjDriver` (same package) already has a complete, working implementation of this method against a *different* system (OneAdvanced's JSON REST API). We're now wiring the same "post unposted activity logs, mark posted on success" behavior into `SmartAssessorDriver`, but against SmartAssessor's ASP.NET MVC form endpoint (`POST https://www.smartassessor.co.uk/ETimeSheet/Form`), which needs an antiforgery token and a page-load's worth of hidden fields rather than a plain JSON body.

Key differences from `OtjDriver.submitPendingOtjs()` that shape this plan:
- SmartAssessor's form POST requires a `__RequestVerificationToken` (CSRF) and various ASP.NET model-binder hidden fields (`Id`, `IsNewEntry`, `DateCreated`, `OriginId`, etc.) that must be scraped from a fresh `GET` of the form page immediately before each submission — they are not static.
- The submission is `application/x-www-form-urlencoded` (OkHttp `FormBody`), not JSON.
- `TimeWithAssessorId`, `UnitId`, and `ParentActivityId` are being treated as fixed constants for now (mirroring how `OtjDriver` already hardcodes its `unitId`). `UnitId` reuses the *same* GUID `OtjDriver` hardcodes (`ef974f73-5d9d-447e-8652-379ba9535229`), just SmartAssessor's model binder expects it wrapped in curly braces.
- SmartAssessor's `Comments` field maps directly onto the existing `ActivityLog.activityImpact()` field — that field is already repurposed across the codebase (`LlmServiceImpl.toActivityLog()`, `OtjDriver.buildPayload()`) to hold free-text description, not a true "impact category." **No `ActivityLog` schema change is needed.**
- Both drivers will continue to share the single `posted` boolean on `ActivityLog` — no new flag, since SmartAssessor is intended to supersede the OneAdvanced/OTJDriver integration rather than run alongside it.

## Implementation

### 1. `SmartAssessorDriver.java` — add the submission logic

- Change the constructor to `@Inject public SmartAssessorDriver(ActivityLogRepository activityLogRepository)`, storing it as a field — mirrors `OtjDriver`'s constructor. Dagger will resolve `ActivityLogRepository` automatically since it already has an `@Inject` constructor and is `@Singleton`.
- Add constants:
  - `SMART_ASSESSOR_FORM_URL = "https://www.smartassessor.co.uk/ETimeSheet/Form"`
  - `UNIT_ID = "{ef974f73-5d9d-447e-8652-379ba9535229}"` (same GUID as `OtjDriver`, curly-brace wrapped)
  - `PARENT_ACTIVITY_ID = "13"`
  - `TIME_WITH_ASSESSOR_ID = "501133f8-46bf-4565-b6b1-82c4d038f437"` — flag with a short comment that this is currently a fixed constant and may need to become per-user/config if used beyond a single assessor relationship.
- Replace the stub with:
  ```java
  @Override
  public OtjSubmitResult submitPendingOtjs(String userId) {
      List<ActivityLog> pending = activityLogRepository.getUnpostedActivityLogsFor(userId);
      if (pending.isEmpty()) return new OtjSubmitResult(List.of(), List.of());

      List<String> posted = new ArrayList<>();
      List<String> failed = new ArrayList<>();

      for (ActivityLog activityLog : pending) {
          try {
              Response formResp = get(SMART_ASSESSOR_FORM_URL);
              String formHtml = formResp.body().string();
              String formUrl = formResp.request().url().toString();
              formResp.close();

              Document doc = Jsoup.parse(formHtml, formUrl);
              Element form = doc.selectFirst("form");
              if (form == null) throw new IOException("ETimeSheet form not found — URL: " + formUrl);

              Map<String, String> fields = hiddenInputMapOf(form);
              fields.put("Date", activityLog.activityDate());
              fields.put("ParentActivityId", PARENT_ACTIVITY_ID);
              fields.put("UnitId", UNIT_ID);
              fields.put("TimeWithAssessorId", TIME_WITH_ASSESSOR_ID);
              fields.put("OnTheJob", String.valueOf(activityLog.hours()));
              fields.put("TimeValue", String.format("%02d:%02d", activityLog.hours(), activityLog.minutes()));
              fields.put("ActivityStartTimeValue", activityLog.activityTime());
              fields.put("Comments", activityLog.activityImpact());

              FormBody.Builder bodyBuilder = new FormBody.Builder();
              fields.forEach(bodyBuilder::add);

              String postUrl = form.absUrl("action");
              if (postUrl.isEmpty()) postUrl = SMART_ASSESSOR_FORM_URL;

              Request request = new Request.Builder()
                      .url(postUrl)
                      .header("User-Agent", USER_AGENT)
                      .header("Referer", formUrl)
                      .post(bodyBuilder.build())
                      .build();

              try (Response response = httpClient.newCall(request).execute()) {
                  String respBody = response.body() != null ? response.body().string() : "";
                  boolean backOnForm = respBody.contains("__RequestVerificationToken");
                  if (response.isSuccessful() && !backOnForm) {
                      activityLogRepository.markAsPosted(activityLog);
                      posted.add(activityLog.id());
                      log.info("Posted activity log {} ({})", activityLog.id(), activityLog.activityDate());
                  } else {
                      failed.add(activityLog.id());
                      log.warn("Failed to post activity log {} — HTTP {} backOnForm={}",
                              activityLog.id(), response.code(), backOnForm);
                  }
              }
          } catch (Exception e) {
              failed.add(activityLog.id());
              log.error("Exception posting activity log {}: {}", activityLog.id(), e.getMessage());
          }
      }

      log.info("Done — {}/{} posted, {} failed", posted.size(), pending.size(), failed.size());
      return new OtjSubmitResult(posted, failed);
  }
  ```
- Add a small helper next to the existing `hiddenInputsOf(Element)`:
  ```java
  private static Map<String, String> hiddenInputMapOf(Element form) {
      Map<String, String> map = new LinkedHashMap<>();
      for (Element input : form.select("input[type=hidden]")) {
          if (!input.attr("name").isEmpty()) map.put(input.attr("name"), input.val());
      }
      return map;
  }
  ```
- Import additions: `com.github.grepHammerspace.db.ActivityLogRepository`, `com.github.grepHammerspace.db.model.ActivityLog`, `java.util.LinkedHashMap`.

**Note on the success check:** ASP.NET MVC create forms often return HTTP 200 while re-rendering the form on a validation error (rather than 4xx/5xx). The `backOnForm` heuristic (checking whether `__RequestVerificationToken` reappears in the response) guards against silently treating a validation failure as success — this can't be fully verified without hitting the live endpoint, so it should be checked/adjusted during end-to-end testing.

### 2. `OtjServicesResource.java` — wire it into `/smart-assessor/complete`

Currently `smartAssessorComplete()` (line ~326) just waits on `loginFuture` and returns `{"status": "logged_in"}` — it never calls `submitPendingOtjs`, so the new method would be unreachable via the API. Mirror the pattern already used in `/submit-with-mfa` (line 258): after `loginFuture.get(...)` succeeds, call `driver.submitPendingOtjs(userId)` (fetch the driver via `userStateStore.getStateForUser(userId).getDriver()`, cast/store as needed) and return the same status shape (`nothingToPost`/`allPosted`/`allFailed`/`partial`) used by `/submit-with-mfa`, for response consistency.

## Verification

- `mvn test` (or the project's usual build command) to confirm no compile breakage from the constructor signature change.
- Manual end-to-end test: hit `/smart-assessor/prepare`, approve MFA, then `/smart-assessor/complete` with at least one unposted `ActivityLog` for the test user, and confirm:
  - The activity appears correctly in SmartAssessor's timesheet after submission.
  - `activitylogs` collection in Mongo shows `posted: true` for the submitted record(s).
  - Re-running `/smart-assessor/complete` with no pending logs returns `nothing_to_post` (no duplicate POSTs).
- Since this depends on a live third-party form (token/field names, `TimeWithAssessorId` validity, and the validation-error detection heuristic), treat the first live run as the real test of the request-shape assumptions in this plan — inspect the actual response body/status if the first submission fails.
