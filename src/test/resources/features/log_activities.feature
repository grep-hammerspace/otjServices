Feature: Log activities via LLM

  # The quota reset is required: llm_quota.feature leaves the counter at its limit, and Mongo isn't
  # reset between scenarios.
  Background:
    Given a registered user with learnerId "L001"
    And there are no activity logs for the test user
    And the test user has used no LLM calls today

  Scenario: Blank content returns 400
    When I POST "/otj-services/log-activities" with content ""
    Then the response status is 400
    And the response body contains "content"

  Scenario: Valid content returns 200 with rows saved to MongoDB
    When I POST "/otj-services/log-activities" with content "Worked 2 hours on assignment from 10:00"
    Then the response status is 200
    And the response body contains "\"status\":\"ok\""
    And the response body contains "\"rowsAdded\":1"
    And there is 1 activity log in the database for user "test-user-id"

  Scenario: Logged rows come back addressable and without the server-minted userId
    When I POST "/otj-services/log-activities" with content "Worked 2 hours on assignment from 10:00"
    Then the response status is 200
    And the response body does not contain "tailscaleUserId"
    And the response body does not contain "learnerId"

  Scenario: Resubmitting identical content logs it again
    Given I have already logged "Worked 2 hours on assignment from 10:00"
    When I POST "/otj-services/log-activities" with content "Worked 2 hours on assignment from 10:00"
    Then the response status is 200
    And the response body contains "\"rowsAdded\":1"
    And there are 2 activity logs in the database for user "test-user-id"
