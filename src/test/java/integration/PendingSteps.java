package integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.bson.Document;
import org.bson.types.ObjectId;

import static org.junit.jupiter.api.Assertions.*;

/** Glue for {@code pending.feature}. Assertions on the response shape live here rather than as
 *  raw substring matches, because the point of these endpoints is the shape. */
public class PendingSteps {

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json");

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
        sendDelete(id);
    }

    // ---------------------------------------------------------------------------------------
    // PUT /pending/{id}
    // ---------------------------------------------------------------------------------------

    /** Edits the row the previous {@code GET /pending} listed first.
     *
     *  <p>Remembers the id and {@code createdAt} it had before the edit, so a later step can prove
     *  neither moved — {@code createdAt} comes from the ObjectId timestamp and must keep meaning
     *  "when this was added", not "when it was last touched". */
    @When("I PUT the first pending activity with body:")
    public void putFirstPendingActivity(String body) throws Exception {
        JsonNode row = lastBody().get("activities").get(0);
        ScenarioContext.put("editedId", row.get("id").asText());
        ScenarioContext.put("editedCreatedAt", row.get("createdAt").asText());
        sendPut(row.get("id").asText(), body);
    }

    /** Edits the row a {@code Given} seeded straight into Mongo — the rows whose ownership or
     *  {@code posted} flag the scenario needs to control, which the fake LLM cannot produce. */
    @When("I PUT the seeded activity with body:")
    public void putSeededActivity(String body) throws Exception {
        sendPut((String) ScenarioContext.get("seededId"), body);
    }

    @When("I DELETE the edited activity")
    public void deleteEditedActivity() throws Exception {
        sendDelete((String) ScenarioContext.get("editedId"));
    }

    @Then("the returned activity has field {string} with value {string}")
    public void returnedActivityHasField(String field, String expected) throws Exception {
        JsonNode value = lastBody().get(field);
        assertNotNull(value, "field '" + field + "' is missing from: " + lastBody());
        assertEquals(expected, value.asText(), "field '" + field + "'");
    }

    @Then("the edited activity keeps the id and createdAt it was returned with")
    public void editedActivityKeepsIdentity() throws Exception {
        assertEquals(ScenarioContext.get("editedId"), lastBody().get("id").asText(),
                "an edit must not move the row to a new id");
        assertEquals(ScenarioContext.get("editedCreatedAt"), lastBody().get("createdAt").asText(),
                "createdAt is when the row was added, not when it was last touched");
    }

    /** Asserts on the stored document rather than the response, because the point is what the edit
     *  did <em>not</em> write: only five fields are the caller's to set. */
    @Then("the edited activity in the database has fields:")
    public void editedActivityInDatabaseHasFields(DataTable table) {
        Document doc = activityLogById((String) ScenarioContext.get("editedId"));
        assertNotNull(doc, "the edited row should still be in the database");
        table.asMap().forEach((field, expected) ->
                // An empty DataTable cell arrives as null; unitId really is stored as "".
                assertEquals(expected == null ? "" : expected, String.valueOf(doc.get(field)),
                        "field '" + field + "' should not have been touched by the edit"));
    }

    @Given("another user has an unposted activity")
    public void anotherUserHasAnUnpostedActivity() {
        seed("some-other-user-id", false);
    }

    @Given("I have an already-posted activity")
    public void iHaveAnAlreadyPostedActivity() {
        seed(ServerHooks.TEST_USER_ID, true);
    }

    @Then("the seeded activity is unchanged in the database")
    public void seededActivityIsUnchanged() {
        Document doc = activityLogById((String) ScenarioContext.get("seededId"));
        assertNotNull(doc, "the seeded row should still be there");
        assertEquals(SEEDED_IMPACT, doc.getString("activityImpact"),
                "a miss must leave the row alone, not partially write it");
        assertEquals("2026/05/30", doc.getString("activityDate"));
    }

    private static final String SEEDED_IMPACT = "Seeded row";

    /** Writes a row straight into Mongo and publishes its id as {@code seededId}. */
    private void seed(String userId, boolean posted) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        Document doc = new Document()
                .append("tailscaleUserId", userId)
                .append("learnerId", "L999")
                .append("activityImpact", SEEDED_IMPACT)
                .append("unitId", "")
                .append("activityDate", "2026/05/30")
                .append("activityTime", "09:00")
                .append("activityType", 0)
                .append("hours", 1)
                .append("minutes", 0)
                .append("posted", posted);
        db.getCollection("activitylogs").insertOne(doc);
        ScenarioContext.put("seededId", doc.getObjectId("_id").toHexString());
    }

    private static Document activityLogById(String id) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        return db.getCollection("activitylogs").find(new Document("_id", new ObjectId(id))).first();
    }

    private static void sendPut(String id, String body) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = HttpSteps.authenticated(new Request.Builder()
                .url(base + "/otj-services/pending/" + id))
                .put(RequestBody.create(body, JSON))
                .build();
        record(HTTP.newCall(req).execute());
    }

    private static void sendDelete(String id) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = HttpSteps.authenticated(new Request.Builder()
                .url(base + "/otj-services/pending/" + id))
                .delete()
                .build();
        record(HTTP.newCall(req).execute());
    }

    private static void record(okhttp3.Response response) throws Exception {
        String responseBody = response.body() != null ? response.body().string() : "";
        ScenarioContext.put("lastResponseCode", response.code());
        ScenarioContext.put("lastResponseBody", responseBody);
    }
}
