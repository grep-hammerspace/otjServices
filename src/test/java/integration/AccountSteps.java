package integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import okhttp3.*;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AccountSteps {
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @When("I PATCH {string} with learnerId {string}")
    public void patchLearnerId(String path, String learnerId) throws Exception {
        patchWithBody(path, MAPPER.writeValueAsString(Map.of("learnerId", learnerId)));
    }

    @When("I PATCH {string} with body {string}")
    public void patchRawBody(String path, String body) throws Exception {
        patchWithBody(path, body);
    }

    @When("I PATCH {string} with a learner ID of {int} characters")
    public void patchOverlongLearnerId(String path, int length) throws Exception {
        patchLearnerId(path, "L".repeat(length));
    }

    @Then("the response body has exactly the keys {string}")
    public void responseBodyHasExactlyKeys(String expected) throws Exception {
        List<String> want = Arrays.stream(expected.split(",")).map(String::strip).sorted().toList();

        JsonNode root = MAPPER.readTree((String) ScenarioContext.get("lastResponseBody"));
        List<String> got = new ArrayList<>();
        root.fieldNames().forEachRemaining(got::add);
        got.sort(String::compareTo);

        assertEquals(want, got, "response body keys differ\nActual body: " + ScenarioContext.get("lastResponseBody"));
    }

    @And("the account in the users collection has fields:")
    public void accountHasFields(DataTable table) {
        Document doc = account();
        table.asMap().forEach((field, expected) ->
                assertEquals(expected, doc.getString(field), "Field '" + field + "' mismatch"));
    }

    @And("there is exactly one account")
    public void exactlyOneAccount() {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        assertEquals(1, db.getCollection("users").countDocuments());
    }

    @And("the newest activity log has learnerId {string}")
    public void newestActivityLogHasLearnerId(String expected) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        Document row = db.getCollection("activitylogs")
                .find(new Document("tailscaleUserId", ServerHooks.TEST_USER_ID))
                .sort(new Document("_id", -1))
                .first();
        assertNotNull(row, "Expected an activity log but found none");
        assertEquals(expected, row.getString("learnerId"));
    }

    private static Document account() {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        Document doc = db.getCollection("users").find(new Document("userId", ServerHooks.TEST_USER_ID)).first();
        assertNotNull(doc, "Expected the single account in MongoDB but found none");
        return doc;
    }

    private void patchWithBody(String path, String body) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = new Request.Builder().url(base + path).patch(RequestBody.create(body, JSON)).build();
        try (Response response = HTTP.newCall(req).execute()) {
            ScenarioContext.put("lastResponseCode", response.code());
            ScenarioContext.put("lastResponseBody", response.body() != null ? response.body().string() : "");
        }
    }
}
