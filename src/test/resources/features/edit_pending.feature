Feature: Editing a pending activity

  # A pending row is the model's reading of one line of free text: it gets the duration right and
  # the date wrong, or clips the description. Editing repairs it in place instead of forcing a
  # delete-and-retype, which would re-run the LLM to fix a typo.
  #
  # Scenarios share one Mongo database with no per-scenario reset, so the background clears the
  # test user's rows first.
  #
  # The quota reset is load-bearing for the same reason, and for the same reason it is in
  # log_activities.feature: nearly every scenario here seeds its row through "I have already
  # logged", which spends one of test-user-id's ten daily LLM calls. Without this line the
  # Examples table below runs out partway through and the later cases 429 instead of reaching
  # the endpoint under test.
  Background:
    Given a registered user with learnerId "L001"
    And there are no activity logs for the test user
    And the test user has used no LLM calls today

  Scenario: A valid edit returns the row in its new state
    Given I have already logged "Worked on the assignment"
    When I GET "/otj-services/pending"
    And I PUT the first pending activity with body:
      """
      {"activityDate": "2026/06/01", "activityTime": "14:30", "hours": 2, "minutes": 45,
       "activityImpact": "Paired on the auth filter and wrote its tests"}
      """
    Then the response status is 200
    And the returned activity has field "activityDate" with value "2026/06/01"
    And the returned activity has field "activityTime" with value "14:30"
    And the returned activity has field "hours" with value "2"
    And the returned activity has field "minutes" with value "45"
    And the returned activity has field "activityImpact" with value "Paired on the auth filter and wrote its tests"
    And the response body does not contain "tailscaleUserId"

  Scenario: The new values are what a later read gives back
    Given I have already logged "Worked on the assignment"
    When I GET "/otj-services/pending"
    And I PUT the first pending activity with body:
      """
      {"activityDate": "2026/06/01", "activityTime": "", "hours": 0, "minutes": 30,
       "activityImpact": "Corrected"}
      """
    Then the response status is 200
    When I GET "/otj-services/pending"
    Then the pending list has 1 activity
    And the pending list totals 30 minutes
    And the first pending activity has impact "Corrected"

  Scenario: An edit leaves the fields it does not name alone
    Given I have already logged "Worked on the assignment"
    When I GET "/otj-services/pending"
    And I PUT the first pending activity with body:
      """
      {"activityDate": "2026/06/01", "activityTime": "09:00", "hours": 1, "minutes": 0,
       "activityImpact": "Edited"}
      """
    Then the response status is 200
    And the edited activity in the database has fields:
      | learnerId    | L001         |
      | unitId       |              |
      | activityType | 0            |
      | posted       | false        |
    And the edited activity keeps the id and createdAt it was returned with

  Scenario: An edited row is still addressable by the same id
    Given I have already logged "Worked on the assignment"
    When I GET "/otj-services/pending"
    And I PUT the first pending activity with body:
      """
      {"activityDate": "2026/06/01", "activityTime": "09:00", "hours": 1, "minutes": 0,
       "activityImpact": "Edited"}
      """
    And I DELETE the edited activity
    Then the response status is 204

  Scenario: Another user's row is a 404, in the same words a missing row gets
    Given another user has an unposted activity
    When I PUT the seeded activity with body:
      """
      {"activityDate": "2026/06/01", "activityTime": "09:00", "hours": 1, "minutes": 0,
       "activityImpact": "Written by someone else"}
      """
    Then the response status is 404
    And the response body contains "No unposted activity log with that id for this user."
    And the seeded activity is unchanged in the database

  Scenario: An already-posted row is a 404
    Given I have an already-posted activity
    When I PUT the seeded activity with body:
      """
      {"activityDate": "2026/06/01", "activityTime": "09:00", "hours": 1, "minutes": 0,
       "activityImpact": "Too late"}
      """
    Then the response status is 404
    And the response body contains "No unposted activity log with that id for this user."

  Scenario: An unknown id is a 404
    When I PUT "/otj-services/pending/68f3c1a49b2e4d0012ab34cd" with body:
      """
      {"activityDate": "2026/06/01", "activityTime": "09:00", "hours": 1, "minutes": 0,
       "activityImpact": "Nothing to edit"}
      """
    Then the response status is 404

  Scenario: A malformed id is a 400
    When I PUT "/otj-services/pending/not-a-hex-id" with body:
      """
      {"activityDate": "2026/06/01", "activityTime": "09:00", "hours": 1, "minutes": 0,
       "activityImpact": "Some work"}
      """
    Then the response status is 400
    And the response body contains "is not a valid activity id."

  Scenario: Editing without a token is rejected
    When I PUT "/otj-services/pending/68f3c1a49b2e4d0012ab34cd" without a token with body:
      """
      {"activityDate": "2026/06/01", "activityTime": "09:00", "hours": 1, "minutes": 0,
       "activityImpact": "Some work"}
      """
    Then the response status is 401

  # Each rule gets a 400 whose body names what was wrong — the mobile client renders that string,
  # so a generic message would cost the user the reason.
  Scenario Outline: <case> is rejected with a message naming the problem
    Given I have already logged "Worked on the assignment"
    When I GET "/otj-services/pending"
    And I PUT the first pending activity with body:
      """
      {"activityDate": <date>, "activityTime": <time>, "hours": <hours>, "minutes": <minutes>,
       "activityImpact": <impact>}
      """
    Then the response status is 400
    And the response body contains "<expected>"

    Examples:
      | case                     | date           | time      | hours | minutes | impact        | expected             |
      | A date in the wrong form | "2026-06-01"   | "09:00"   | 1     | 0       | "Some work"   | activityDate         |
      | A date off the calendar  | "2026/02/30"   | "09:00"   | 1     | 0       | "Some work"   | real calendar date   |
      | A date in the future     | "2099/01/01"   | "09:00"   | 1     | 0       | "Some work"   | future               |
      | A time in the wrong form | "2026/06/01"   | "9:30"    | 1     | 0       | "Some work"   | activityTime         |
      | A time before 09:00      | "2026/06/01"   | "07:00"   | 1     | 0       | "Some work"   | 09:00 and 18:00      |
      | A time after 18:00       | "2026/06/01"   | "19:00"   | 1     | 0       | "Some work"   | 09:00 and 18:00      |
      | Sixty or more minutes    | "2026/06/01"   | "09:00"   | 1     | 60      | "Some work"   | minutes              |
      | A zero-length activity   | "2026/06/01"   | "09:00"   | 0     | 0       | "Some work"   | zero minutes         |
      | A blank description      | "2026/06/01"   | "09:00"   | 1     | 0       | "   "         | activityImpact       |

  Scenario: A rejected edit leaves the row as it was
    Given I have already logged "Worked on the assignment"
    When I GET "/otj-services/pending"
    And I PUT the first pending activity with body:
      """
      {"activityDate": "2099/01/01", "activityTime": "09:00", "hours": 1, "minutes": 0,
       "activityImpact": "Time travel"}
      """
    Then the response status is 400
    When I GET "/otj-services/pending"
    Then the first pending activity has impact "Worked on the assignment"
