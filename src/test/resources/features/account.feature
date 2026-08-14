Feature: The signed-in account

  The learner ID is typed once at signup and never shown again, so a typo in it is invisible
  until OneAdvanced rejects every row of a submission. GET /auth/me reads the account back and
  PATCH /auth/me corrects the learner ID, without touching anything else on the document — and
  without back-filling rows that were written under the old value.

  Scenario: Reading the account returns the username and learner ID it was signed up with
    Given an unused invite code "OTJ-ACC-0001" expiring in 7 days
    And I sign up with inviteCode "OTJ-ACC-0001", username "readme", password "pw", learnerId "L-READ"
    When I GET "/auth/me" with the signup token
    Then the response status is 200
    And the response body contains "readme"
    And the response body contains "L-READ"

  # The assertion worth writing first: AccountResponse is the seam that keeps the hash off the
  # wire, and a future refactor that serialises User directly would pass every other scenario here.
  Scenario: The account response carries nothing but the username and learner ID
    Given an unused invite code "OTJ-ACC-0002" expiring in 7 days
    And I sign up with inviteCode "OTJ-ACC-0002", username "narrow", password "pw", learnerId "L-NARROW"
    When I GET "/auth/me" with the signup token
    Then the response status is 200
    And the response body has exactly the keys "username, learnerId"

  Scenario: Reading the account without a token is rejected
    When I GET "/auth/me" without a token
    Then the response status is 401

  Scenario: A token reads its own account, never another user's
    Given an unused invite code "OTJ-ACC-0003" expiring in 7 days
    And an unused invite code "OTJ-ACC-0004" expiring in 7 days
    And I sign up with inviteCode "OTJ-ACC-0003", username "firstacct", password "pw", learnerId "L-FIRST"
    And I remember the signup token as "first"
    And I sign up with inviteCode "OTJ-ACC-0004", username "secondacct", password "pw", learnerId "L-SECOND"
    When I GET "/auth/me" with the remembered token "first"
    Then the response status is 200
    And the response body contains "L-FIRST"
    And the response body does not contain "L-SECOND"

  Scenario: Correcting the learner ID returns the new value and persists it
    Given an unused invite code "OTJ-ACC-0005" expiring in 7 days
    And I sign up with inviteCode "OTJ-ACC-0005", username "corrector", password "pw", learnerId "L-TYPO"
    When I PATCH "/auth/me" with learnerId "L-FIXED" using the signup token
    Then the response status is 200
    And the response body contains "L-FIXED"
    When I GET "/auth/me" with the signup token
    Then the response status is 200
    And the response body contains "L-FIXED"
    And user "corrector" in the users collection has fields:
      | learnerId | L-FIXED |

  Scenario: Correcting the learner ID leaves the rest of the account alone
    Given an unused invite code "OTJ-ACC-0006" expiring in 7 days
    And I sign up with inviteCode "OTJ-ACC-0006", username "intact", password "pw", learnerId "L-BEFORE"
    When I PATCH "/auth/me" with learnerId "L-AFTER" using the signup token
    Then the response status is 200
    And user "intact" in the users collection has fields:
      | appUsername     | intact  |
      | appPasswordHash | $2a$    |
      | learnerId       | L-AFTER |
    And the account "intact" can still log in with password "pw"

  Scenario: The learner ID is stripped before it is stored
    Given an unused invite code "OTJ-ACC-0007" expiring in 7 days
    And I sign up with inviteCode "OTJ-ACC-0007", username "spacey", password "pw", learnerId "L-OLD"
    When I PATCH "/auth/me" with learnerId "  L-TRIMMED  " using the signup token
    Then the response status is 200
    And user "spacey" in the users collection has fields:
      | learnerId | L-TRIMMED |

  Scenario Outline: A blank learner ID is rejected
    Given an unused invite code "<code>" expiring in 7 days
    And I sign up with inviteCode "<code>", username "<user>", password "pw", learnerId "L-KEEP"
    When I PATCH "/auth/me" with body '<body>' using the signup token
    Then the response status is 400
    And the response body contains "Learner ID cannot be blank."
    And user "<user>" in the users collection has fields:
      | learnerId | L-KEEP |

    Examples:
      | code          | user      | body                    |
      | OTJ-ACC-0008  | blankone  | {"learnerId":""}        |
      | OTJ-ACC-0009  | blanktwo  | {"learnerId":"   "}     |
      | OTJ-ACC-0010  | blankthree| {}                      |
      | OTJ-ACC-0011  | blankfour | {"learnerId":null}      |

  Scenario: A learner ID over 64 characters is rejected
    Given an unused invite code "OTJ-ACC-0012" expiring in 7 days
    And I sign up with inviteCode "OTJ-ACC-0012", username "toolong", password "pw", learnerId "L-KEEP"
    When I PATCH "/auth/me" with a learner ID of 65 characters using the signup token
    Then the response status is 400
    And the response body contains "Learner ID is too long."
    And user "toolong" in the users collection has fields:
      | learnerId | L-KEEP |

  # No format check, deliberately: signup applies none, so this endpoint must not be stricter than
  # the door the value came in through. Only OneAdvanced knows what a real learner ID looks like.
  Scenario: A learner ID in an unexpected format is still accepted
    Given an unused invite code "OTJ-ACC-0013" expiring in 7 days
    And I sign up with inviteCode "OTJ-ACC-0013", username "oddformat", password "pw", learnerId "L-KEEP"
    When I PATCH "/auth/me" with learnerId "not even slightly an id" using the signup token
    Then the response status is 200
    And user "oddformat" in the users collection has fields:
      | learnerId | not even slightly an id |

  Scenario: Correcting the learner ID without a token is rejected
    When I PATCH "/auth/me" with learnerId "L-NOPE" without a token
    Then the response status is 401

  Scenario: A new activity carries the corrected learner ID
    Given an unused invite code "OTJ-ACC-0014" expiring in 7 days
    And I sign up with inviteCode "OTJ-ACC-0014", username "newrows", password "pw", learnerId "L-BEFORE"
    When I PATCH "/auth/me" with learnerId "L-AFTER" using the signup token
    Then the response status is 200
    When I POST "/otj-services/log-activities" with content "Spent 2 hours pairing" using the signup token
    Then the response status is 200
    And the newest activity log for user "newrows" has learnerId "L-AFTER"

  # The no-back-fill rule, pinned. A correction applies to what is logged next; rows already in
  # the queue keep the old value and will post under it. The mobile client warns about exactly
  # this while the field is open — if that ever changes, this scenario is what has to change first.
  Scenario: An activity logged before the correction keeps the old learner ID
    Given an unused invite code "OTJ-ACC-0015" expiring in 7 days
    And I sign up with inviteCode "OTJ-ACC-0015", username "oldrows", password "pw", learnerId "L-BEFORE"
    When I POST "/otj-services/log-activities" with content "Spent 2 hours pairing" using the signup token
    Then the response status is 200
    When I PATCH "/auth/me" with learnerId "L-AFTER" using the signup token
    Then the response status is 200
    And the newest activity log for user "oldrows" has learnerId "L-BEFORE"
