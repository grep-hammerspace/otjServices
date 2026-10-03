Feature: Login rate limiting

  Usernames are the brute-force surface: they are user-chosen, may be guessable, and there is no
  rate at which trying them is legitimate beyond a person mistyping. Login attempts are capped per
  username per 15-minute window, checked before the account lookup and before bcrypt so a flood
  costs the server nothing.

  ServerHooks builds a fresh Dagger component per scenario, so each scenario starts with an empty
  limiter and cannot inherit another's attempts.

  Scenario: Repeated wrong passwords are eventually rate-limited
    Given an unused invite code "OTJ-RL-0001" expiring in 7 days
    And I sign up with inviteCode "OTJ-RL-0001", username "bruteforced", password "correct-horse", learnerId "L-RL1"
    When I POST "/auth/session" with username "bruteforced", password "wrong" 10 times
    Then the response status is 401
    When I POST "/auth/session" with username "bruteforced", password "wrong"
    Then the response status is 429
    And the response header "Retry-After" is a positive integer
    And the response body contains "Too many login attempts"

  Scenario: An unknown username is limited the same way
    When I POST "/auth/session" with username "no-such-account", password "wrong" 10 times
    Then the response status is 401
    When I POST "/auth/session" with username "no-such-account", password "wrong"
    Then the response status is 429

  Scenario: One username being limited does not lock out another
    Given an unused invite code "OTJ-RL-0002" expiring in 7 days
    And I sign up with inviteCode "OTJ-RL-0002", username "bystander", password "correct-horse", learnerId "L-RL2"
    When I POST "/auth/session" with username "someone-else", password "wrong" 11 times
    Then the response status is 429
    When I POST "/auth/session" with username "bystander", password "correct-horse"
    Then the response status is 200

  Scenario: Successful logins count towards the limit
    Given an unused invite code "OTJ-RL-0003" expiring in 7 days
    And I sign up with inviteCode "OTJ-RL-0003", username "chatty", password "correct-horse", learnerId "L-RL3"
    When I POST "/auth/session" with username "chatty", password "correct-horse" 10 times
    Then the response status is 200
    When I POST "/auth/session" with username "chatty", password "correct-horse"
    Then the response status is 429

  Scenario: A normal failed login carries no Retry-After
    When I POST "/auth/session" with username "occasional-typo", password "wrong"
    Then the response status is 401
    And the response has no "Retry-After" header
