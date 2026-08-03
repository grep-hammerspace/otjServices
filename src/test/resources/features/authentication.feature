Feature: Bearer token authentication

  Protected endpoints must reject requests without a valid bearer token before any
  handler logic runs. The health endpoint carries no @Authenticated binding and must
  stay anonymous — a meaningful assertion that the filter does not over-apply.

  Scenario: Request without a token is rejected with 401
    When I DELETE "/otj-services/delete-last-row" without a token
    Then the response status is 401
    And the response body contains "bearer token"

  Scenario: Request with an unknown token is rejected with 401
    When I GET "/otj-services/azure-id/complete" with token "not-a-real-token"
    Then the response status is 401

  Scenario: Health endpoint requires no token
    When I GET "/health" without a token
    Then the response status is 200
