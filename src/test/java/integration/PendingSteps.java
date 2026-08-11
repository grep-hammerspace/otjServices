package integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import static org.junit.jupiter.api.Assertions.*;

/** Glue for {@code pending.feature}. Assertions on the response shape live here rather than as
 *  raw substring matches, because the point of these endpoints is the shape. */
public class PendingSteps {

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode lastBody() throws Exception {
        return MAPPER.readTree((String) ScenarioContext.get("lastResponseBody"));
    }

    @Then("the pending list has {int} activity/activities")
    public void pendingListHas(int expected) throws Exception {
        JsonNode activities = lastBody().get("activities");
        assertEquals(expected, activities.size(), "activities array size");
        assertEquals(expected, lastBody().get("count").asInt(), "count must agree with the array");
    }

    @And("the pending list totals {int} minutes")
    public void pendingListTotals(int expected) throws Exception {
        assertEquals(expected, lastBody().get("totalMinutes").asInt());
    }

    @And("the first pending activity has impact {string}")
    public void firstPendingHasImpact(String expected) throws Exception {
        assertEquals(expected, lastBody().get("activities").get(0).get("activityImpact").asText());
    }

    @And("every pending activity has an id")
    public void everyPendingHasAnId() throws Exception {
        JsonNode activities = lastBody().get("activities");
        assertTrue(activities.size() > 0, "nothing to assert on — the list was empty");
        for (JsonNode activity : activities) {
            JsonNode id = activity.get("id");
            assertNotNull(id, "id must be present");
            assertFalse(id.isNull(), "id must never be null — it is the delete handle");
            assertEquals(24, id.asText().length(), "an ObjectId hex string is 24 chars");
            assertFalse(activity.get("createdAt").asText().isBlank(), "createdAt must be populated");
        }
    }

    @And("the response body does not contain {string}")
    public void responseBodyDoesNotContain(String needle) {
        String body = (String) ScenarioContext.get("lastResponseBody");
        assertFalse(body.contains(needle), "response body should not contain '" + needle + "': " + body);
    }

    /** Reads the id out of the previous response and deletes it. This is the step that proves the
     *  id is a usable delete handle rather than decoration. */
    @When("I DELETE the first pending activity")
    public void deleteFirstPendingActivity() throws Exception {
        String id = lastBody().get("activities").get(0).get("id").asText();
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = HttpSteps.authenticated(new Request.Builder()
                .url(base + "/otj-services/pending/" + id))
                .delete()
                .build();
        okhttp3.Response response = HTTP.newCall(req).execute();
        String responseBody = response.body() != null ? response.body().string() : "";
        ScenarioContext.put("lastResponseCode", response.code());
        ScenarioContext.put("lastResponseBody", responseBody);
    }
}
