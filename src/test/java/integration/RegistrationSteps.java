package integration;

import com.mongodb.client.MongoDatabase;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import okhttp3.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cucumber step definitions for the user registration feature.
 *
 * <p>Cucumber creates a new instance of this class for each scenario, so {@link #lastResponse}
 * is naturally isolated — there is no risk of a response from one scenario leaking into another.
 * Infrastructure handles (base URL, database) are read from {@link ScenarioContext}, which is
 * populated by {@link ServerHooks} before each scenario runs.
 */
public class RegistrationSteps {
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");

    private Response lastResponse;

    /** Sends a JSON POST to {@code path} with the given registration fields and stores the response. */
    @When("I POST {string} with username {string}, password {string}, learnerId {string}")
    public void postRegister(String path, String username, String password, String learnerId)
        throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        String body = String.format(
            "{\"username\":\"%s\",\"password\":\"%s\",\"learnerId\":\"%s\"}",
            username, password, learnerId);
        Request req = new Request.Builder()
            .url(base + path)
            .post(RequestBody.create(body, JSON))
            .build();
        lastResponse = HTTP.newCall(req).execute();
        ScenarioContext.put("lastResponseCode", lastResponse.code());
        String responseBody = lastResponse.body() != null ? lastResponse.body().string() : "";
        ScenarioContext.put("lastResponseBody", responseBody);
    }

    /** Asserts that the HTTP status code of the most recent response matches {@code expectedStatus}. */
    @Then("the response status is {int}")
    public void checkStatus(int expectedStatus) {
        assertEquals(expectedStatus, ScenarioContext.get("lastResponseCode"));
    }

    @Then("the response body contains {string}")
    public void responseBodyContains(String expected) {
        String body = (String) ScenarioContext.get("lastResponseBody");
        assertTrue(body != null && body.contains(expected),
                "Expected body to contain: " + expected + "\nActual: " + body);
    }

    /**
     * Queries MongoDB for a user document by {@code username} and asserts that each field named
     * in the DataTable matches the stored value. The DataTable is a two-column map of
     * {@code field | expected value} — only the listed fields are checked, so scenarios only
     * need to specify the fields they care about.
     */
    @And("user {string} in the users collection has fields:")
    public void userHasFields(String username, io.cucumber.datatable.DataTable table) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        org.bson.Document doc = db.getCollection("users")
            .find(new org.bson.Document("username", username))
            .first();
        assertNotNull(doc, "Expected user '" + username + "' in MongoDB but found none");
        table.asMap().forEach((field, expected) ->
            assertEquals(expected, doc.getString(field),
                "Field '" + field + "' mismatch for user '" + username + "'"));
    }
}
