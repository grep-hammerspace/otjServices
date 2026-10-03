Feature: Preparing a OneAdvanced session and submitting pending activities

  The OneAdvanced username and password arrive in the request body — this service does not store
  them, and on a self-hosted box they cross only the owner's tailnet, whose TLS ends at their own
  tailscale serve. These scenarios pin that the pair reaches the driver unchanged, that nothing
  about it comes back out, and that the learner ID is taken from the account rather than from the
  request or from the rows being posted.

  The log guard in ServerHooks runs after every scenario in the suite, so any line that leaks the
  sentinel credentials below fails the scenario that produced it.

  Background:
    Given the account has learnerId "L-1"
    And there are no activity logs for the test user

  Scenario: The Keycloak prepare hands the request credentials straight to the driver
    When I POST "/otj-services/prepare-browser" with the OneAdvanced credentials
    Then the response status is 200
    And the response body contains "otp_required"
    And the "keycloak" driver received the OneAdvanced credentials

  Scenario: The Azure prepare returns the number to tap
    Given the "azure" driver will require a number match of 42
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials
    Then the response status is 200
    And the response body contains "push_sent"
    And the response body contains "42"
    And the "azure" driver received the OneAdvanced credentials

  Scenario: An existing SSO session finishes the login without MFA
    Given the "azure" driver will complete the login without MFA
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials
    Then the response status is 200
    And the response body contains "login_complete"
    And the response body does not contain "challengeNumber"

  Scenario: Rejected credentials give one fixed message and no detail
    Given the "azure" driver will reject the credentials
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials
    Then the response status is 401
    And the response body contains "Could not sign in to OneAdvanced"
    And the response body does not contain "login_hint"
    And the response body does not contain "leaktest@example.invalid"

  Scenario: A missing password is refused before any login is attempted
    When I POST "/otj-services/prepare-browser" with a blank OneAdvanced password
    Then the response status is 400
    And the "keycloak" driver was not called

  Scenario: An account with no learner ID is refused before any login is attempted
    Given the account has no learner ID
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials
    Then the response status is 409
    And the response body contains "No learner ID"
    And the "azure" driver was not called

  Scenario: Completing without preparing is refused
    When I POST "/otj-services/submit-with-mfa" with the MFA code
    Then the response status is 409
    And the response body contains "No prepared session"

  Scenario: A typed code is refused against an Azure push session
    Given the "azure" driver will require a number match of 7
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials
    Then the response status is 200
    When I POST "/otj-services/submit-with-mfa" with the MFA code
    Then the response status is 409
    And the response body contains "Microsoft Authenticator"

  Scenario: A corrected learner ID applies to activities already queued
    Given the account has learnerId "L-TYPO"
    When I POST "/otj-services/log-activities" with content "Spent 2 hours pairing"
    Then the response status is 200
    When I PATCH "/auth/me" with learnerId "L-FIXED"
    Then the response status is 200
    And the "azure" driver will complete the login without MFA
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials
    Then the response status is 200
    When I GET "/otj-services/azure-id/complete"
    Then the response status is 200
    And the "azure" driver submitted with learnerId "L-FIXED"
    And the newest activity log has learnerId "L-TYPO"

  # Pinned: an ObjectMapper with FAIL_ON_UNKNOWN_PROPERTIES off would silently accept a learnerId
  # here.
  Scenario: A learner ID in the request body is refused
    Given the "azure" driver will complete the login without MFA
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials and learnerId "L-INJECTED"
    Then the response status is 400
    And the "azure" driver was not called

  Scenario: A spent session cannot be replayed
    Given the "azure" driver will complete the login without MFA
    When I POST "/otj-services/azure-id/prepare" with the OneAdvanced credentials
    Then the response status is 200
    When I GET "/otj-services/azure-id/complete"
    Then the response status is 200
    When I GET "/otj-services/azure-id/complete"
    Then the response status is 409
