Feature: Admin invite code management

  The admin API runs as a separate server on its own port, reachable in production only through
  `tailscale serve`. Callers are identified by the Tailscale-User-Login header that serve injects
  and must appear in ADMIN_ALLOWED_LOGINS — being on the tailnet is not enough, since phones join
  the tailnet to use the app.

  Scenario: An allowlisted admin can mint a code
    When the admin mints an invite code
    Then the response status is 201
    And the minted code looks like an invite code
    And the response body has invite status "ACTIVE"

  Scenario: A minted code can be redeemed for an account
    When the admin mints an invite code
    And I sign up with the minted code as "minted-user"
    Then the response status is 201
    And the response body contains "token"

  Scenario: A code can only be redeemed once, however it was minted
    When the admin mints an invite code
    And I sign up with the minted code as "first-minted"
    Then the response status is 201
    When I sign up with the minted code as "second-minted"
    Then the response status is 403

  Scenario: Revoking a code stops it being redeemed
    When the admin mints an invite code
    And the admin revokes the minted code
    Then the response status is 204
    When I sign up with the minted code as "too-late"
    Then the response status is 403
    And the response body contains "Invalid, used or expired invite code"

  Scenario: A revoked code reads as REVOKED, not EXPIRED
    When the admin mints an invite code
    And the admin revokes the minted code
    And the admin lists invite codes
    Then the response status is 200
    And the minted code is listed with status "REVOKED"

  Scenario: A redeemed code reads as USED
    When the admin mints an invite code
    And I sign up with the minted code as "used-status-user"
    And the admin lists invite codes
    Then the minted code is listed with status "USED"

  Scenario: Revoking an already-redeemed code is refused
    When the admin mints an invite code
    And I sign up with the minted code as "already-claimed"
    Then the response status is 201
    When the admin revokes the minted code
    Then the response status is 409
    And the response body contains "already been claimed"

  Scenario: Revoking a code that never existed is a 404
    When the admin revokes the code "OTJ-NOPE-NOPE"
    Then the response status is 404

  Scenario: A tailnet user who is not on the allowlist is refused
    When "intruder@test.tailnet" mints an invite code
    Then the response status is 403
    And the response body contains "Not an admin identity"

  Scenario: A request with no identity header is refused
    When an anonymous caller mints an invite code
    Then the response status is 403

  Scenario: The health endpoint stays open so the container health check works
    When the admin requests "/health" on the admin server
    Then the response status is 200

  Scenario: A zero-day expiry is rejected rather than minting a dead code
    When the admin mints an invite code expiring in 0 days
    Then the response status is 400

  Scenario: An absurd expiry is capped rather than minting a code that never dies
    When the admin mints an invite code expiring in 4000 days
    Then the response status is 400

  Scenario: A code minted with an explicit expiry is still usable
    When the admin mints an invite code expiring in 30 days
    Then the response status is 201
    And the response body has invite status "ACTIVE"
