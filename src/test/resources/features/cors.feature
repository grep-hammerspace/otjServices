Feature: Cross-origin calls from the app's web build

  The PWA is served from Vercel, so in self-hosted mode a browser calls this server's ts.net address
  cross-origin and refuses to hand the app any response that does not name its origin. The native
  app sends no Origin header.

  Scenario: A preflight from the app's origin is granted without a token
    When a browser at "https://otj-mobile-pwa.vercel.app" preflights a "POST" to "/otj-services/log-activities"
    Then the response status is 204
    And the response header "Access-Control-Allow-Origin" is "https://otj-mobile-pwa.vercel.app"
    And the response header "Access-Control-Allow-Methods" contains "POST"
    And the response header "Access-Control-Allow-Headers" contains "Authorization"

  Scenario: A preflight from any other origin is not granted
    When a browser at "https://someone-else.vercel.app" preflights a "GET" to "/auth/me"
    Then the response status is 204
    And the response has no "Access-Control-Allow-Origin" header
    And the response has no "Access-Control-Allow-Methods" header

  Scenario: A call from the app's origin can be read
    When a browser at "https://otj-log-preview.vercel.app" calls GET "/otj-services/pending"
    Then the response status is 200
    And the response header "Access-Control-Allow-Origin" is "https://otj-log-preview.vercel.app"

  Scenario: An error can be read too, so the app can act on it
    When a browser at "https://otj-mobile-pwa.vercel.app" calls DELETE "/otj-services/pending/000000000000000000000000"
    Then the response status is 404
    And the response header "Access-Control-Allow-Origin" is "https://otj-mobile-pwa.vercel.app"

  Scenario: A call from any other origin carries no grant
    When a browser at "https://someone-else.vercel.app" calls GET "/otj-services/pending"
    Then the response has no "Access-Control-Allow-Origin" header

  Scenario: A native call is untouched
    When I GET "/health"
    Then the response status is 200
    And the response has no "Access-Control-Allow-Origin" header
