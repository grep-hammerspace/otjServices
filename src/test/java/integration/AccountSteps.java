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

// Uses the signup token: the hook's seeded test-user-id has no account behind it.
public class AccountSteps {
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @Then("the response body has exactly the keys {string}")
    public void responseBodyHasExactlyKeys(String expected) throws Exception {
        List<String> want = Arrays.stream(expected.split(",")).map(String::strip).sorted().toList();

        JsonNode root = MAPPER.readTree((String) ScenarioContext.get("lastResponseBody"));
        List<String> got = new ArrayList<>();
        root.fieldNames().forEachRemaining(got::add);
        got.sort(String::compareTo);

        assertEquals(want, got, "response body keys differ\nActual body: " + ScenarioContext.get("lastResponseBody"));
    }

    // A login, not a $2a$ prefix check, is what proves the original password still works.
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
