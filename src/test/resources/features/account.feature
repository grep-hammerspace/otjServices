Feature: The account

  The self-hosted API has one account, created on boot with no learner ID. GET /auth/me reads it
  back and PATCH /auth/me sets or corrects the learner ID, without touching anything else on the
  document — and without back-filling rows that were written under the old value.

  Scenario: A fresh account has no learner ID yet
    Given the account has no learner ID
    When I GET "/auth/me"
    Then the response status is 200
    And the response body contains "\"username\":\"self-hosted\""
    And the response body contains "\"learnerId\":null"

  Scenario: The account response carries nothing but the username and learner ID
    Given the account has learnerId "L-NARROW"
    When I GET "/auth/me"
    Then the response status is 200
    And the response body has exactly the keys "username, learnerId"

  Scenario: Setting the learner ID returns the new value and persists it
    Given the account has no learner ID
    When I PATCH "/auth/me" with learnerId "L-FIXED"
    Then the response status is 200
    And the response body contains "L-FIXED"
    When I GET "/auth/me"
    Then the response status is 200
    And the response body contains "L-FIXED"
    And the account in the users collection has fields:
      | learnerId | L-FIXED |

  Scenario: Correcting the learner ID leaves the rest of the account alone
    Given the account has learnerId "L-BEFORE"
    When I PATCH "/auth/me" with learnerId "L-AFTER"
    Then the response status is 200
    And the account in the users collection has fields:
      | appUsername | self-hosted |
      | learnerId   | L-AFTER     |
    And there is exactly one account

  Scenario: The learner ID is stripped before it is stored
    Given the account has learnerId "L-OLD"
    When I PATCH "/auth/me" with learnerId "  L-TRIMMED  "
    Then the response status is 200
    And the account in the users collection has fields:
      | learnerId | L-TRIMMED |

  Scenario Outline: A blank learner ID is rejected
    Given the account has learnerId "L-KEEP"
    When I PATCH "/auth/me" with body '<body>'
    Then the response status is 400
    And the response body contains "Learner ID cannot be blank."
    And the account in the users collection has fields:
      | learnerId | L-KEEP |

    Examples:
      | body                |
      | {"learnerId":""}    |
      | {"learnerId":"   "} |
      | {}                  |
      | {"learnerId":null}  |

  Scenario: A learner ID over 64 characters is rejected
    Given the account has learnerId "L-KEEP"
    When I PATCH "/auth/me" with a learner ID of 65 characters
    Then the response status is 400
    And the response body contains "Learner ID is too long."
    And the account in the users collection has fields:
      | learnerId | L-KEEP |

  Scenario: A learner ID in an unexpected format is still accepted
    Given the account has learnerId "L-KEEP"
    When I PATCH "/auth/me" with learnerId "not even slightly an id"
    Then the response status is 200
    And the account in the users collection has fields:
      | learnerId | not even slightly an id |

  Scenario: A new activity carries the corrected learner ID
    Given the account has learnerId "L-BEFORE"
    When I PATCH "/auth/me" with learnerId "L-AFTER"
    Then the response status is 200
    When I POST "/otj-services/log-activities" with content "Spent 2 hours pairing"
    Then the response status is 200
    And the newest activity log has learnerId "L-AFTER"

  Scenario: An activity logged before the correction keeps the old learner ID
    Given the account has learnerId "L-BEFORE"
    When I POST "/otj-services/log-activities" with content "Spent 2 hours pairing"
    Then the response status is 200
    When I PATCH "/auth/me" with learnerId "L-AFTER"
    Then the response status is 200
    And the newest activity log has learnerId "L-BEFORE"
