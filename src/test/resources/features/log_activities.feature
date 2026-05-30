Feature: Log activities via LLM

  Background:
    Given a registered user with learnerId "L001"

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

  Scenario: Identical content returns no new content
    Given I have already logged "Worked 2 hours on assignment from 10:00"
    When I POST "/otj-services/log-activities" with content "Worked 2 hours on assignment from 10:00"
    Then the response status is 200
    And the response body contains "no new content"

  Scenario: Appended content processes only the new line
    Given I have already logged "Worked 2 hours on assignment from 10:00"
    When I POST "/otj-services/log-activities" with content "Worked 2 hours on assignment from 10:00\nDid reading for 1 hour"
    Then the response status is 200
    And the response body contains "\"rowsAdded\":1"
