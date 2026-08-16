Feature: Daily LLM quota

  Every well-formed log-activities request reaches the model — content diffing was removed, so
  there is no resubmit-is-free path any more — which makes a per-user daily cap the only backstop
  the service itself can enforce against a client stuck in a retry loop.

  # The limit is hardcoded at 10 here, matching LlmQuotaService.DAILY_LIMIT. Reading it from a
  # system property would make a production limit settable from the environment, which is more
  # surface than the coupling is worth.
  Background:
    Given a registered user with learnerId "L001"
    And there are no activity logs for the test user
    And the test user has used no LLM calls today

  # The zero-rows assertion is the short-circuit proof: the fake LLM emits one row per non-blank
  # line and those rows are saved unconditionally, so no rows means execution never reached it.
  Scenario: A request past the daily quota is refused before the model is called
    Given the test user has used 10 LLM calls today
    When I POST "/otj-services/log-activities" with content "Worked 2 hours on assignment from 10:00"
    Then the response status is 429
    And the response body contains "Daily limit of 10 AI requests"
    And there are 0 activity logs in the database for user "test-user-id"

  Scenario: The quota counts calls made over HTTP
    Given the test user has used 9 LLM calls today
    When I POST "/otj-services/log-activities" with content "Worked 2 hours on assignment from 10:00"
    Then the response status is 200
    When I POST "/otj-services/log-activities" with content "Worked 3 hours on revision from 14:00"
    Then the response status is 429

  # Replaces the original plan's scenario, which asserted that identical content would still get
  # the old "no new content" 200 while over quota. There is no such path any more, and
  # log_activities.feature pins that resubmitting logs the content again.
  # Log the content first, then exhaust the quota. The other order would have the "already
  # logged" step itself 429 unasserted, and the scenario would pass on a false premise.
  Scenario: Resubmitting identical content does not dodge the quota
    Given I have already logged "Worked 2 hours on assignment from 10:00"
    And the test user has used 10 LLM calls today
    When I POST "/otj-services/log-activities" with content "Worked 2 hours on assignment from 10:00"
    Then the response status is 429
    And there is 1 activity log in the database for user "test-user-id"

  Scenario: A blank request is rejected without spending quota
    Given the test user has used 10 LLM calls today
    When I POST "/otj-services/log-activities" with content ""
    Then the response status is 400
