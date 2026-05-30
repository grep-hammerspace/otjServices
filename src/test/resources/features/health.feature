Feature: Health check

  Scenario: Health endpoint returns 200
    When I GET "/health"
    Then the response status is 200
