package integration;

import com.mongodb.client.MongoDatabase;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import okhttp3.*;
import org.bson.Document;

public class HttpSteps {

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");

    /** Attaches the scenario's bearer token (issued by {@link ServerHooks}) if one is present. */
    static Request.Builder authenticated(Request.Builder builder) {
        String token = (String) ScenarioContext.get("authToken");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private static void record(Response response) throws Exception {
        String responseBody = response.body() != null ? response.body().string() : "";
        ScenarioContext.put("lastResponseCode", response.code());
        ScenarioContext.put("lastResponseBody", responseBody);
    }

    @When("I DELETE {string}")
    public void sendDelete(String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = authenticated(new Request.Builder().url(base + path)).delete().build();
        record(HTTP.newCall(req).execute());
    }

    @When("I GET {string}")
    public void sendGet(String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = authenticated(new Request.Builder().url(base + path)).get().build();
        record(HTTP.newCall(req).execute());
    }

    @When("I DELETE {string} without a token")
    public void sendDeleteWithoutToken(String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = new Request.Builder().url(base + path).delete().build();
        record(HTTP.newCall(req).execute());
    }

    @When("I PUT {string} with body:")
    public void sendPut(String path, String body) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = authenticated(new Request.Builder().url(base + path))
                .put(RequestBody.create(body, JSON))
                .build();
        record(HTTP.newCall(req).execute());
    }

    @When("I PUT {string} without a token with body:")
    public void sendPutWithoutToken(String path, String body) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = new Request.Builder().url(base + path)
                .put(RequestBody.create(body, JSON))
                .build();
        record(HTTP.newCall(req).execute());
    }

    @When("I GET {string} without a token")
    public void sendGetWithoutToken(String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = new Request.Builder().url(base + path).get().build();
        record(HTTP.newCall(req).execute());
    }

    @When("I GET {string} with token {string}")
    public void sendGetWithToken(String path, String token) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = new Request.Builder()
                .url(base + path)
                .header("Authorization", "Bearer " + token)
                .get()
                .build();
        record(HTTP.newCall(req).execute());
    }

    @Given("there are no activity logs for the test user")
    public void clearTestUserActivityLogs() {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        db.getCollection("activitylogs").deleteMany(new Document("tailscaleUserId", ServerHooks.TEST_USER_ID));
    }
}
