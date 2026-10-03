Feature: Log activities via LLM

  Background:
    Given the account has learnerId "L001"
    And there are no activity logs for the test user

  Scenario: Blank content returns 400
    When I POST "/otj-services/log-activities" with content ""
    Then the response status is 400
    And the response body contains "content"

  Scenario: An entry of exactly 500 characters is accepted
    When I POST "/otj-services/log-activities" with one entry of 500 characters
    Then the response status is 200
    And there is 1 activity log in the database

  Scenario: One over-long entry rejects the whole request
    When I POST "/otj-services/log-activities" with one entry of 501 characters
    Then the response status is 400
    And the response body contains "500 characters"
    And there are 0 activity logs in the database

  Scenario: Valid content returns 200 with rows saved to MongoDB
    When I POST "/otj-services/log-activities" with content "Worked 2 hours on assignment from 10:00"
    Then the response status is 200
    And the response body contains "\"status\":\"ok\""
    And the response body contains "\"rowsAdded\":1"
    And there is 1 activity log in the database

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
    And there are 2 activity logs in the database
