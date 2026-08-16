Feature: Preparing a OneAdvanced session and submitting pending activities

  The OneAdvanced username and password arrive in the request body — this service does not store
  them. These scenarios pin that they reach the driver unchanged, that nothing about them comes
  back out, and that the learner ID is taken from the account rather than from the request or from
  the rows being posted.

  The log guard in ServerHooks runs after every scenario in the suite, so any line that leaks the
  sentinel credentials below fails the scenario that produced it.

  Scenario: The Keycloak prepare hands the request credentials straight to the driver
    Given an unused invite code "OTJ-PRE-0001" expiring in 7 days
    And I sign up with inviteCode "OTJ-PRE-0001", username "prep1", password "pw", learnerId "L-1"
    When I POST "/otj-services/prepare-browser" with the OneAdvanced credentials using the signup token
    Then the response status is 200
    And the response body contains "otp_required"
    And the "keycloak" driver received the OneAdvanced credentials

  Scenario: The Azure prepare returns the number to tap
    Given an unused invite code "OTJ-PRE-0002" expiring in 7 days
    And I sign up with inviteCode "OTJ-PRE-0002", username "prep2", password "pw", learnerId "L-2"
    And the "azure" driver will require a number match of 42
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials using the signup token
    Then the response status is 200
    And the response body contains "push_sent"
    And the response body contains "42"
    And the "azure" driver received the OneAdvanced credentials

  Scenario: An existing SSO session finishes the login without MFA
    Given an unused invite code "OTJ-PRE-0003" expiring in 7 days
    And I sign up with inviteCode "OTJ-PRE-0003", username "prep3", password "pw", learnerId "L-3"
    And the "azure" driver will complete the login without MFA
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials using the signup token
    Then the response status is 200
    And the response body contains "login_complete"
    And the response body does not contain "challengeNumber"

  # The driver's own message names the URL it failed on, and that URL carries login_hint=<username>.
  Scenario: Rejected credentials give one fixed message and no detail
    Given an unused invite code "OTJ-PRE-0004" expiring in 7 days
    And I sign up with inviteCode "OTJ-PRE-0004", username "prep4", password "pw", learnerId "L-4"
    And the "azure" driver will reject the credentials
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials using the signup token
    Then the response status is 401
    And the response body contains "Could not sign in to OneAdvanced"
    And the response body does not contain "login_hint"
    And the response body does not contain "leaktest@example.invalid"

  Scenario: A missing password is refused before any login is attempted
    Given an unused invite code "OTJ-PRE-0005" expiring in 7 days
    And I sign up with inviteCode "OTJ-PRE-0005", username "prep5", password "pw", learnerId "L-5"
    When I POST "/otj-services/prepare-browser" with a blank OneAdvanced password using the signup token
    Then the response status is 400
    And the "keycloak" driver was not called

  Scenario: Completing without preparing is refused
    Given an unused invite code "OTJ-PRE-0006" expiring in 7 days
    And I sign up with inviteCode "OTJ-PRE-0006", username "prep6", password "pw", learnerId "L-6"
    When I POST "/otj-services/submit-with-mfa" with the MFA code using the signup token
    Then the response status is 409
    And the response body contains "No prepared session"

  # AzureIdDriver.completeMfa ignores the token it is handed. Without the flow check this call
  # would be accepted and start a second Microsoft poll racing the one prepare already started.
  Scenario: A typed code is refused against an Azure push session
    Given an unused invite code "OTJ-PRE-0007" expiring in 7 days
    And I sign up with inviteCode "OTJ-PRE-0007", username "prep7", password "pw", learnerId "L-7"
    And the "azure" driver will require a number match of 7
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials using the signup token
    Then the response status is 200
    When I POST "/otj-services/submit-with-mfa" with the MFA code using the signup token
    Then the response status is 409
    And the response body contains "Microsoft Authenticator"

  # The rule this replaces: a correction used to reach only what was logged after it, because the
  # drivers read the learner ID off the first pending row. It is now read from the account at
  # submit time, so queued rows post under the corrected value. The stored row is left alone as a
  # record of what was intended when it was written.
  Scenario: A corrected learner ID applies to activities already queued
    Given an unused invite code "OTJ-PRE-0008" expiring in 7 days
    And I sign up with inviteCode "OTJ-PRE-0008", username "prep8", password "pw", learnerId "L-TYPO"
    When I POST "/otj-services/log-activities" with content "Spent 2 hours pairing" using the signup token
    Then the response status is 200
    When I PATCH "/auth/me" with learnerId "L-FIXED" using the signup token
    Then the response status is 200
    And the "azure" driver will complete the login without MFA
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials using the signup token
    Then the response status is 200
    When I GET "/otj-services/azure-id/complete" with the signup token
    Then the response status is 200
    And the "azure" driver submitted with learnerId "L-FIXED"
    And the newest activity log for user "prep8" has learnerId "L-TYPO"

  # The learner ID is server-side, so the request has no business carrying one. Jersey's Jackson
  # provider rejects unknown properties by default, which turns "silently ignored" into "refused"
  # — the stronger of the two. Pinned here because a future ObjectMapper with
  # FAIL_ON_UNKNOWN_PROPERTIES disabled would quietly downgrade it.
  Scenario: A learner ID in the request body is refused
    Given an unused invite code "OTJ-PRE-0009" expiring in 7 days
    And I sign up with inviteCode "OTJ-PRE-0009", username "prep9", password "pw", learnerId "L-REAL"
    And the "azure" driver will complete the login without MFA
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials and learnerId "L-INJECTED" using the signup token
    Then the response status is 400
    And the "azure" driver was not called

  Scenario: A spent session cannot be replayed
    Given an unused invite code "OTJ-PRE-0010" expiring in 7 days
    And I sign up with inviteCode "OTJ-PRE-0010", username "prep10", password "pw", learnerId "L-10"
    And the "azure" driver will complete the login without MFA
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials using the signup token
    Then the response status is 200
    When I GET "/otj-services/azure-id/complete" with the signup token
    Then the response status is 200
    When I GET "/otj-services/azure-id/complete" with the signup token
    Then the response status is 409
