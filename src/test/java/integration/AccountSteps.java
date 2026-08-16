package integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import okhttp3.*;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step definitions for {@code account.feature} — {@code GET} and {@code PATCH /auth/me}.
 *
 * <p>Everything here authenticates with the <b>signup token</b> rather than the hook's seeded
 * {@code test-user-id} token. These endpoints read and write the caller's user document, and the
 * seeded token belongs to a userId that no signup ever created — so scenarios need an account that
 * actually exists, which means the token the signup handed back.
 *
 * <p>Cucumber glue is global, so shared assertions are not redefined here: status and
 * {@code contains} come from {@link RegistrationSteps}, {@code does not contain} from
 * {@link PendingSteps}, and the users-collection field check from {@link RegistrationSteps}. Only
 * the steps this feature is the first to need are below.
 */
public class AccountSteps {
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parks the current signup token under a name, so a later scenario step can use it after a
     * second signup has overwritten {@code "signupToken"}. What proves one account cannot read
     * another's.
     */
    @Given("I remember the signup token as {string}")
    public void rememberSignupToken(String name) {
        Object token = ScenarioContext.get("signupToken");
        assertNotNull(token, "no signup token to remember — did the signup step run?");
        ScenarioContext.put("token:" + name, token);
    }

    @When("I GET {string} with the remembered token {string}")
    public void getWithRememberedToken(String path, String name) throws Exception {
        send(request(path, name).get());
    }

    @When("I PATCH {string} with learnerId {string} using the signup token")
    public void patchLearnerId(String path, String learnerId) throws Exception {
        patchWithBody(path, MAPPER.writeValueAsString(Map.of("learnerId", learnerId)));
    }

    /** The raw-body form, for the bodies a typed step cannot express: {@code {}} and a null field. */
    @When("I PATCH {string} with body {string} using the signup token")
    public void patchRawBody(String path, String body) throws Exception {
        patchWithBody(path, body);
    }

    @When("I PATCH {string} with a learner ID of {int} characters using the signup token")
    public void patchOverlongLearnerId(String path, int length) throws Exception {
        patchLearnerId(path, "L".repeat(length));
    }

    @When("I PATCH {string} with learnerId {string} without a token")
    public void patchWithoutToken(String path, String learnerId) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        String body = MAPPER.writeValueAsString(Map.of("learnerId", learnerId));
        send(new Request.Builder().url(base + path).patch(RequestBody.create(body, JSON)));
    }

    @When("I POST {string} with content {string} using the signup token")
    public void postContentWithSignupToken(String path, String content) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("content", content));
        send(request(path, null).post(RequestBody.create(body, JSON)));
    }

    /**
     * The narrowness assertion. {@code AccountResponse} is the seam that keeps
     * {@code appPasswordHash} and the server-minted {@code userId} off the wire, and a refactor
     * that serialised {@code User} directly would satisfy every {@code contains} check in the file.
     */
    @Then("the response body has exactly the keys {string}")
    public void responseBodyHasExactlyKeys(String expected) throws Exception {
        List<String> want = Arrays.stream(expected.split(",")).map(String::strip).sorted().toList();

        JsonNode root = MAPPER.readTree((String) ScenarioContext.get("lastResponseBody"));
        List<String> got = new ArrayList<>();
        root.fieldNames().forEachRemaining(got::add);
        got.sort(String::compareTo);

        assertEquals(want, got, "response body keys differ\nActual body: " + ScenarioContext.get("lastResponseBody"));
    }

    /**
     * Proves a learner ID change did not disturb the password hash. Checking the stored hash still
     * starts with {@code $2a$} would pass against a hash of some *other* password; only a login
     * shows the original one still works.
     */
    @Then("the account {string} can still log in with password {string}")
    public void canStillLogIn(String username, String password) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        String body = MAPPER.writeValueAsString(Map.of("username", username, "password", password));
        Request req = new Request.Builder()
                .url(base + "/auth/session")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = HTTP.newCall(req).execute()) {
            assertEquals(200, response.code(), "login after the learner ID change should still work");
        }
    }

    /**
     * Reads the row back from Mongo rather than from a response body: {@code PendingActivity} omits
     * {@code learnerId} on purpose, so the stored document is the only place this is visible — and
     * the stored document is what the submission drivers actually send.
     */
    @And("the newest activity log for user {string} has learnerId {string}")
    public void newestActivityLogHasLearnerId(String appUsername, String expected) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");

        Document user = db.getCollection("users").find(new Document("appUsername", appUsername)).first();
        assertNotNull(user, "Expected user '" + appUsername + "' in MongoDB but found none");

        Document row = db.getCollection("activitylogs")
                .find(new Document("tailscaleUserId", user.getString("userId")))
                .sort(new Document("_id", -1))
                .first();
        assertNotNull(row, "Expected an activity log for user '" + appUsername + "' but found none");
        assertEquals(expected, row.getString("learnerId"));
    }

    private void patchWithBody(String path, String body) throws Exception {
        send(request(path, null).patch(RequestBody.create(body, JSON)));
    }

    /** A builder for {@code path}, bearing the remembered token named {@code name}, or the signup token. */
    private static Request.Builder request(String path, String name) {
        String base = (String) ScenarioContext.get("baseUrl");
        Object token = ScenarioContext.get(name == null ? "signupToken" : "token:" + name);
        assertNotNull(token, "no token available — did the signup step run?");
        return new Request.Builder().url(base + path).header("Authorization", "Bearer " + token);
    }

    private static void send(Request.Builder builder) throws Exception {
        try (Response response = HTTP.newCall(builder.build()).execute()) {
            ScenarioContext.put("lastResponseCode", response.code());
            ScenarioContext.put("lastResponseBody", response.body() != null ? response.body().string() : "");
        }
    }
}
