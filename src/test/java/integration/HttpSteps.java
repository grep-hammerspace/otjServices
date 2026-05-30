package integration;

import com.mongodb.client.MongoDatabase;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import okhttp3.*;
import org.bson.Document;

public class HttpSteps {

    private static final OkHttpClient HTTP = new OkHttpClient();

    @When("I DELETE {string}")
    public void sendDelete(String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = new Request.Builder()
                .url(base + path)
                .delete()
                .build();
        Response response = HTTP.newCall(req).execute();
        String responseBody = response.body() != null ? response.body().string() : "";
        ScenarioContext.put("lastResponseCode", response.code());
        ScenarioContext.put("lastResponseBody", responseBody);
    }

    @When("I GET {string}")
    public void sendGet(String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = new Request.Builder()
                .url(base + path)
                .get()
                .build();
        Response response = HTTP.newCall(req).execute();
        String responseBody = response.body() != null ? response.body().string() : "";
        ScenarioContext.put("lastResponseCode", response.code());
        ScenarioContext.put("lastResponseBody", responseBody);
    }

    @Given("there are no activity logs for the test user")
    public void clearTestUserActivityLogs() {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        db.getCollection("activitylogs").deleteMany(new Document("tailscaleUserId", "test-user-id"));
    }
}
