package integration;

import com.mongodb.client.MongoDatabase;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import okhttp3.*;

import static org.junit.jupiter.api.Assertions.*;

public class RegistrationSteps {
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");

    private Response lastResponse;

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
    }

    @Then("the response status is {int}")
    public void checkStatus(int expectedStatus) {
        assertEquals(expectedStatus, lastResponse.code());
    }

    @And("user {string} exists in the users collection")
    public void userExistsInMongo(String username) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        org.bson.Document doc = db.getCollection("users")
            .find(new org.bson.Document("username", username))
            .first();
        assertNotNull(doc, "Expected user '" + username + "' in MongoDB but found none");
        assertNotNull(doc.getString("userId"),   "Field 'userId' is null for user '" + username + "'");
        assertNotNull(doc.getString("password"), "Field 'password' is null for user '" + username + "'");
        assertNotNull(doc.getString("learnerId"), "Field 'learnerId' is null for user '" + username + "'");
    }
}
