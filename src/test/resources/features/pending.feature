Feature: List and delete pending activities

  # Scenarios share one Mongo database with no per-scenario reset, so every count below is only
  # meaningful if each scenario starts from an empty log collection.
  Background:
    Given a registered user with learnerId "L001"
    And there are no activity logs for the test user

  Scenario: An empty queue is a 200, not a 404
    When I GET "/otj-services/pending"
    Then the response status is 200
    And the response body contains "\"count\":0"
    And the response body contains "\"totalMinutes\":0"

  Scenario: Listing without a token is rejected
    When I GET "/otj-services/pending" without a token
    Then the response status is 401

  Scenario: Pending rows carry an id, a createdAt and a total
    Given I have already logged "Worked 2 hours on assignment from 10:00"
    When I GET "/otj-services/pending"
    Then the response status is 200
    And the pending list has 1 activity
    And the pending list totals 60 minutes
    And every pending activity has an id
    And the response body does not contain "tailscaleUserId"

  Scenario: Newest first
    Given I have already logged "Worked 1 hour on the first thing from 09:00"
    And I have already logged "Worked 1 hour on the second thing from 11:00"
    When I GET "/otj-services/pending"
    Then the pending list has 2 activities
    And the first pending activity has impact "Worked 1 hour on the second thing from 11:00"

  Scenario: Deleting by id removes only that row
    Given I have already logged "Worked 1 hour on the first thing from 09:00"
    And I have already logged "Worked 1 hour on the second thing from 11:00"
    When I GET "/otj-services/pending"
    And I DELETE the first pending activity
    Then the response status is 204
    When I GET "/otj-services/pending"
    Then the pending list has 1 activity
    And the first pending activity has impact "Worked 1 hour on the first thing from 09:00"

  Scenario: A malformed id is a 400
    When I DELETE "/otj-services/pending/not-a-hex-id"
    Then the response status is 400

  Scenario: An unknown id is a 404
    When I DELETE "/otj-services/pending/68f3c1a49b2e4d0012ab34cd"
    Then the response status is 404
