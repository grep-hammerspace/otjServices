Feature: Publishing the key that OneAdvanced credentials are sealed to

  otj-services.com is proxied by Cloudflare, which terminates the visitor's TLS and opens its own
  connection to this origin. A password in a request body is therefore in the clear inside
  Cloudflare, however good the TLS on either side of it. The prepare endpoints take an encrypted
  envelope instead, and this endpoint publishes the key to seal it to.

  The signature is what makes that worth doing. This response crosses the same hop the credentials
  do, so a client that simply trusted the key it was handed would be defended against a passive
  edge and nothing else. The app pins the Ed25519 identity key at build time and refuses to submit
  when an announcement does not verify against it.

  Scenario: The announcement is signed by the identity key
    Given an unused invite code "OTJ-KEY-0001" expiring in 7 days
    And I sign up with inviteCode "OTJ-KEY-0001", username "key1", password "pw", learnerId "L-K1"
    When I GET the credential public key using the signup token
    Then the response status is 200
    And the announcement verifies against the pinned identity key
    And the announcement names the sealed-credential algorithm
    And the announced key is 32 bytes
    And the announcement has not expired

  # Not for secrecy — a public key is public. It keeps the anonymous surface at the two endpoints
  # the Caddyfile's @anon matcher names, and a caller with no token has nothing to seal anyway.
  Scenario: The key endpoint is behind the same bearer-token gate as everything else
    When I GET the credential public key without a token
    Then the response status is 401
