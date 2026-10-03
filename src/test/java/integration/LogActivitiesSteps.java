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

    // The hook has already created the account; the suite's Mongo isn't wiped between scenarios.
    @Given("the account has learnerId {string}")
    public void accountHasLearnerId(String learnerId) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        db.getCollection("users").updateOne(
                new org.bson.Document("userId", ServerHooks.TEST_USER_ID),
                new org.bson.Document("$set", new org.bson.Document("learnerId", learnerId)));
    }

    @Given("the account has no learner ID")
    public void accountHasNoLearnerId() {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        db.getCollection("users").updateOne(
                new org.bson.Document("userId", ServerHooks.TEST_USER_ID),
                new org.bson.Document("$unset", new org.bson.Document("learnerId", "")));
    }

    @Given("I have already logged {string}")
    public void alreadyLogged(String content) throws Exception {
        postLogActivities(content);
    }

    @When("I POST {string} with content {string}")
    public void postLogActivitiesTo(String path, String content) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        String body = MAPPER.writeValueAsString(java.util.Map.of("content", content));
        Request req = new Request.Builder()
                .url(base + path)
                .post(RequestBody.create(body, JSON))
                .build();
        Response response = HTTP.newCall(req).execute();
        String responseBody = response.body() != null ? response.body().string() : "";
        ScenarioContext.put("lastResponseCode", response.code());
        ScenarioContext.put("lastResponseBody", responseBody);
    }

    @And("there is/are {int} activity log(s) in the database")
    public void checkActivityLogCount(int expectedCount) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        long count = db.getCollection("activitylogs")
                .countDocuments(new org.bson.Document("tailscaleUserId", ServerHooks.TEST_USER_ID));
        assertEquals(expectedCount, count,
                "Expected " + expectedCount + " activity log(s) but found " + count);
    }

    private void postLogActivities(String content) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        String body = MAPPER.writeValueAsString(java.util.Map.of("content", content));
        Request req = new Request.Builder()
                .url(base + "/otj-services/log-activities")
                .post(RequestBody.create(body, JSON))
                .build();
        Response response = HTTP.newCall(req).execute();
        String responseBody = response.body() != null ? response.body().string() : "";
        ScenarioContext.put("lastResponseCode", response.code());
        ScenarioContext.put("lastResponseBody", responseBody);
    }
}
