Feature: Delete last row

  Background:
    Given a registered user with learnerId "L001"
    And there are no activity logs for the test user

  Scenario: No activity logs returns 404
    When I DELETE "/otj-services/delete-last-row"
    Then the response status is 404

  Scenario: One unposted log is deleted and returns 200
    Given I have already logged "Test activity"
    When I DELETE "/otj-services/delete-last-row"
    Then the response status is 200
    And there is 0 activity log in the database for user "test-user-id"

  Scenario: Deletes only the most recent of multiple logs
    Given I have already logged "First activity"
    And I have already logged "Second activity"
    When I DELETE "/otj-services/delete-last-row"
    Then the response status is 200
    When I DELETE "/otj-services/delete-last-row"
    Then the response status is 200
    When I DELETE "/otj-services/delete-last-row"
    Then the response status is 404
