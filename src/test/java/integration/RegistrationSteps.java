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
        Request req = HttpSteps.authenticated(new Request.Builder()
            .url(base + path))
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
     * Queries MongoDB for a user document by {@code appUsername} and asserts that each field named
     * in the DataTable matches the stored value. The DataTable is a two-column map of
     * {@code field | expected value} — only the listed fields are checked, so scenarios only
     * need to specify the fields they care about.
     *
     * <p>The {@code appPasswordHash} field is a bcrypt hash with a random salt each time, so it
     * can never match an exact expected value — it is checked with {@code startsWith} instead of
     * equality.
     */
    @And("user {string} in the users collection has fields:")
    public void userHasFields(String appUsername, io.cucumber.datatable.DataTable table) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        org.bson.Document doc = db.getCollection("users")
            .find(new org.bson.Document("appUsername", appUsername))
            .first();
        assertNotNull(doc, "Expected user '" + appUsername + "' in MongoDB but found none");
        table.asMap().forEach((field, expected) -> {
            if (field.equals("appPasswordHash")) {
                assertTrue(doc.getString(field).startsWith(expected),
                    "Field 'appPasswordHash' should start with '" + expected + "' for user '" + appUsername + "'");
            } else {
                assertEquals(expected, doc.getString(field),
                    "Field '" + field + "' mismatch for user '" + appUsername + "'");
            }
        });
    }

    /** Asserts the stored document holds no field from which a password could be recovered. */
    @And("no recoverable password is stored for user {string}")
    public void noRecoverablePassword(String appUsername) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        org.bson.Document doc = db.getCollection("users")
            .find(new org.bson.Document("appUsername", appUsername))
            .first();
        assertNotNull(doc, "Expected user '" + appUsername + "' in MongoDB but found none");
        assertNull(doc.get("password"), "legacy recoverable 'password' field must not exist");
        assertTrue(doc.getString("appPasswordHash").startsWith("$2a$"),
            "appPasswordHash must be a bcrypt hash");
    }
}
