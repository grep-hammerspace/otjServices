Feature: User registration

  Scenario: Register a new user stores them in MongoDB
    When I POST "/otj-services/register" with username "alice", password "pw1", learnerId "learner-alice"
    Then the response status is 201
    And user "alice" in the users collection has fields:
      | username  | alice        |
      | password  | pw1          |
      | learnerId | learner-alice |
      | userId    | test-user-id |

  Scenario: Register with missing learnerId is rejected
    When I POST "/otj-services/register" with username "bob", password "pw2", learnerId ""
    Then the response status is 400

  Scenario: Leading and trailing whitespace is stripped from username and learnerId before saving
    When I POST "/otj-services/register" with username " padded-user ", password "pw", learnerId " learner-padded "
    Then the response status is 201
    And user "padded-user" in the users collection has fields:
      | username  | padded-user    |
      | learnerId | learner-padded |

  Scenario: Registering the same user twice upserts the existing record
    When I POST "/otj-services/register" with username "carol", password "pw3", learnerId "learner-v1"
    And  I POST "/otj-services/register" with username "carol", password "pw-changed", learnerId "learner-v2"
    Then the response status is 201
    And user "carol" in the users collection has fields:
      | password  | pw-changed |
      | learnerId | learner-v2 |
