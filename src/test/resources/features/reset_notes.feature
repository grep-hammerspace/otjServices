Feature: Reset notes

  Background:
    Given a registered user with learnerId "L001"

  Scenario: Reset with no prior content returns 200
    When I DELETE "/otj-services/reset-notes"
    Then the response status is 200

  Scenario: After reset, same content is treated as new
    Given I have already logged "Repeatable content"
    When I DELETE "/otj-services/reset-notes"
    Then the response status is 200
    When I POST "/otj-services/log-activities" with content "Repeatable content"
    Then the response status is 200
    And the response body contains "rowsAdded"
