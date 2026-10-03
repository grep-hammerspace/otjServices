package integration;

import com.mongodb.client.MongoDatabase;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import okhttp3.*;
import org.bson.Document;

public class HttpSteps {
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");

    private static void record(Response response) throws Exception {
        String responseBody = response.body() != null ? response.body().string() : "";
        ScenarioContext.put("lastResponseCode", response.code());
        ScenarioContext.put("lastResponseBody", responseBody);
        ScenarioContext.put("lastResponseHeaders", response.headers());
    }

    @When("I DELETE {string}")
    public void sendDelete(String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = new Request.Builder().url(base + path).delete().build();
        record(HTTP.newCall(req).execute());
    }

    @When("I GET {string}")
    public void sendGet(String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = new Request.Builder().url(base + path).get().build();
        record(HTTP.newCall(req).execute());
    }

    @When("I PUT {string} with body:")
    public void sendPut(String path, String body) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = new Request.Builder().url(base + path)
                .put(RequestBody.create(body, JSON))
                .build();
        record(HTTP.newCall(req).execute());
    }

    @Given("there are no activity logs for the test user")
    public void clearTestUserActivityLogs() {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        db.getCollection("activitylogs").deleteMany(new Document("tailscaleUserId", ServerHooks.TEST_USER_ID));
    }
}
