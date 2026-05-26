Feature: User registration

  Scenario: Register a new user stores them in MongoDB
    When I POST "/otj-services/register" with username "alice", password "pw1", learnerId "550e8400-e29b-41d4-a716-446655440000"
    Then the response status is 201
    And user "alice" exists in the users collection

  Scenario: Register with malformed learnerId is rejected
    When I POST "/otj-services/register" with username "bob", password "pw2", learnerId "not-a-uuid"
    Then the response status is 400

  Scenario: Registering the same user twice upserts without error
    When I POST "/otj-services/register" with username "carol", password "pw3", learnerId "123e4567-e89b-12d3-a456-426614174000"
    And  I POST "/otj-services/register" with username "carol", password "pw3", learnerId "123e4567-e89b-12d3-a456-426614174000"
    Then the response status is 201
    And user "carol" exists in the users collection
