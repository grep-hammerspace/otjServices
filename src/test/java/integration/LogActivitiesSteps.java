package integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import okhttp3.*;

import static org.junit.jupiter.api.Assertions.*;

public class LogActivitiesSteps {
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Upserted: the suite's Mongo isn't wiped between scenarios.
    @Given("a registered user with learnerId {string}")
    public void registerUser(String learnerId) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        db.getCollection("users").replaceOne(
                new org.bson.Document("userId", ServerHooks.TEST_USER_ID),
                new org.bson.Document("userId", ServerHooks.TEST_USER_ID)
                        .append("appUsername", "testuser")
                        .append("appPasswordHash", "$2a$12$fakehashfortesting")
                        .append("learnerId", learnerId)
                        .append("createdAt", new java.util.Date()),
                new com.mongodb.client.model.ReplaceOptions().upsert(true));
    }

    @Given("I have already logged {string}")
    public void alreadyLogged(String content) throws Exception {
        postLogActivities(content);
    }

    @When("I POST {string} with content {string}")
    public void postLogActivitiesTo(String path, String content) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        String body = MAPPER.writeValueAsString(java.util.Map.of("content", content));
        Request req = HttpSteps.authenticated(new Request.Builder()
                .url(base + path))
                .post(RequestBody.create(body, JSON))
                .build();
        Response response = HTTP.newCall(req).execute();
        String responseBody = response.body() != null ? response.body().string() : "";
        ScenarioContext.put("lastResponseCode", response.code());
        ScenarioContext.put("lastResponseBody", responseBody);
    }

    @And("there is/are {int} activity log(s) in the database for user {string}")
    public void checkActivityLogCount(int expectedCount, String userId) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        long count = db.getCollection("activitylogs")
                .countDocuments(new org.bson.Document("tailscaleUserId", userId));
        assertEquals(expectedCount, count,
                "Expected " + expectedCount + " activity log(s) for user " + userId + " but found " + count);
    }

    private void postLogActivities(String content) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        String body = MAPPER.writeValueAsString(java.util.Map.of("content", content));
        Request req = HttpSteps.authenticated(new Request.Builder()
                .url(base + "/otj-services/log-activities"))
                .post(RequestBody.create(body, JSON))
                .build();
        Response response = HTTP.newCall(req).execute();
        String responseBody = response.body() != null ? response.body().string() : "";
        ScenarioContext.put("lastResponseCode", response.code());
        ScenarioContext.put("lastResponseBody", responseBody);
    }
}
