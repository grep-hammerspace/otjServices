Feature: Signup and login

  Signup is gated on an invite code that is claimed atomically, so a code can be redeemed
  exactly once. Both signup and login hand back a session token, making them a single round
  trip. Failures never reveal which of the three invite-code faults occurred, nor whether a
  username exists.

  Scenario: Signup with a valid invite code returns a token
    Given an unused invite code "OTJ-TEST-0001" expiring in 7 days
    When I POST "/auth/signup" with inviteCode "OTJ-TEST-0001", username "newuser", password "pw", learnerId "L9"
    Then the response status is 201
    And the response body contains "token"
    And user "newuser" in the users collection has fields:
      | appUsername     | newuser |
      | appPasswordHash | $2a$    |
      | learnerId       | L9      |
    And no recoverable password is stored for user "newuser"

  Scenario: Signup with an unknown invite code is rejected
    When I POST "/auth/signup" with inviteCode "OTJ-NOPE", username "u2", password "pw", learnerId "L9"
    Then the response status is 403
    And the response body contains "Invalid, used or expired invite code"

  Scenario: An invite code cannot be redeemed twice
    Given an unused invite code "OTJ-TEST-0002" expiring in 7 days
    When I POST "/auth/signup" with inviteCode "OTJ-TEST-0002", username "first", password "pw", learnerId "L9"
    And I POST "/auth/signup" with inviteCode "OTJ-TEST-0002", username "second", password "pw", learnerId "L9"
    Then the response status is 403

  Scenario: Signup with an expired invite code is rejected
    Given an unused invite code "OTJ-TEST-0003" that expired yesterday
    When I POST "/auth/signup" with inviteCode "OTJ-TEST-0003", username "u3", password "pw", learnerId "L9"
    Then the response status is 403

  Scenario: Signup with a taken username is rejected
    Given an unused invite code "OTJ-TEST-0004" expiring in 7 days
    And an unused invite code "OTJ-TEST-0005" expiring in 7 days
    When I POST "/auth/signup" with inviteCode "OTJ-TEST-0004", username "dupe", password "pw", learnerId "L9"
    And I POST "/auth/signup" with inviteCode "OTJ-TEST-0005", username "dupe", password "pw", learnerId "L9"
    Then the response status is 409

  Scenario: Login with correct credentials returns a token
    Given an unused invite code "OTJ-TEST-0006" expiring in 7 days
    And I sign up with inviteCode "OTJ-TEST-0006", username "loginuser", password "correct-horse", learnerId "L9"
    When I POST "/auth/session" with username "loginuser", password "correct-horse"
    Then the response status is 200
    And the response body contains "token"

  Scenario: Login with wrong password is rejected
    Given an unused invite code "OTJ-TEST-0007" expiring in 7 days
    And I sign up with inviteCode "OTJ-TEST-0007", username "wrongpw", password "correct-horse", learnerId "L9"
    When I POST "/auth/session" with username "wrongpw", password "not-the-password"
    Then the response status is 401
    And the response body contains "Invalid username or password"

  Scenario: Login with unknown username is rejected
    When I POST "/auth/session" with username "no-such-user", password "pw"
    Then the response status is 401
    And the response body contains "Invalid username or password"

  Scenario: Logout revokes the token
    Given an unused invite code "OTJ-TEST-0008" expiring in 7 days
    And I sign up with inviteCode "OTJ-TEST-0008", username "logoutuser", password "pw", learnerId "L9"
    When I DELETE "/auth/session" with the signup token
    Then the response status is 204
    When I DELETE "/otj-services/reset-notes" with the signup token
    Then the response status is 401

  Scenario: Logging out twice is not an error
    Given an unused invite code "OTJ-TEST-0009" expiring in 7 days
    And I sign up with inviteCode "OTJ-TEST-0009", username "twicelogout", password "pw", learnerId "L9"
    When I DELETE "/auth/session" with the signup token
    And I DELETE "/auth/session" with the signup token
    Then the response status is 204

  Scenario: A token from signup works on a protected endpoint
    Given an unused invite code "OTJ-TEST-0010" expiring in 7 days
    And I sign up with inviteCode "OTJ-TEST-0010", username "tokenworks", password "pw", learnerId "L9"
    When I DELETE "/otj-services/reset-notes" with the signup token
    Then the response status is 200

  Scenario: Signup requires every field
    Given an unused invite code "OTJ-TEST-0011" expiring in 7 days
    When I POST "/auth/signup" with inviteCode "OTJ-TEST-0011", username "", password "pw", learnerId "L9"
    Then the response status is 400
