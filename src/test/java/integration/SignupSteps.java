package integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import okhttp3.*;
import org.bson.Document;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Step definitions for {@code signup.feature}.
 *
 * <p>The {@code Given an unused invite code} steps insert straight into the {@code inviteCodes}
 * collection via the {@link MongoDatabase} handle from {@link ScenarioContext} — that is exactly
 * how codes are minted in production (by hand in the Atlas UI), so the test seeds them the same
 * way rather than through an endpoint that does not exist.
 *
 * <p>Signup and login requests deliberately carry no {@code Authorization} header: {@code /auth}
 * is anonymous, and sending the hook's seeded token would hide a regression where the resource
 * accidentally starts requiring one. Tokens the endpoints hand back are stashed under
 * {@code "signupToken"} so later steps can use them.
 *
 * <p>Assertions on status and body live in {@link RegistrationSteps} — Cucumber glue is global,
 * so they are shared rather than duplicated here.
 */
public class SignupSteps {
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Given("an unused invite code {string} expiring in {int} days")
    public void unusedInviteCode(String code, int days) {
        insertCode(code, Instant.now().plus(days, ChronoUnit.DAYS));
    }

    @Given("an unused invite code {string} that expired yesterday")
    public void expiredInviteCode(String code) {
        insertCode(code, Instant.now().minus(1, ChronoUnit.DAYS));
    }

    @When("I POST {string} with inviteCode {string}, username {string}, password {string}, learnerId {string}")
    public void postSignup(String path, String inviteCode, String username, String password, String learnerId)
            throws Exception {
        post(path, Map.of("inviteCode", inviteCode, "username", username,
                "password", password, "learnerId", learnerId));
    }

    /**
     * Signs up and asserts it worked, for scenarios where the account is a precondition rather
     * than the thing under test. Failing loudly here stops a broken signup from surfacing as a
     * confusing 401 several steps later.
     */
    @Given("I sign up with inviteCode {string}, username {string}, password {string}, learnerId {string}")
    public void signUp(String inviteCode, String username, String password, String learnerId) throws Exception {
        postSignup("/auth/signup", inviteCode, username, password, learnerId);
        assertNotNull(ScenarioContext.get("signupToken"),
                "signup precondition failed (HTTP " + ScenarioContext.get("lastResponseCode")
                        + "): " + ScenarioContext.get("lastResponseBody"));
    }

    @When("I POST {string} with username {string}, password {string}")
    public void postLogin(String path, String username, String password) throws Exception {
        post(path, Map.of("username", username, "password", password));
    }

    @When("I DELETE {string} with the signup token")
    public void deleteWithSignupToken(String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = new Request.Builder()
                .url(base + path)
                .header("Authorization", "Bearer " + ScenarioContext.get("signupToken"))
                .delete()
                .build();
        record(HTTP.newCall(req).execute());
    }

    @When("I GET {string} with the signup token")
    public void getWithSignupToken(String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = new Request.Builder()
                .url(base + path)
                .header("Authorization", "Bearer " + ScenarioContext.get("signupToken"))
                .get()
                .build();
        record(HTTP.newCall(req).execute());
    }

    private void post(String path, Map<String, String> body) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request req = new Request.Builder()
                .url(base + path)
                .post(RequestBody.create(MAPPER.writeValueAsString(body), JSON))
                .build();
        record(HTTP.newCall(req).execute());
    }

    /**
     * Records status and body, and lifts any {@code token} out of the body so later steps can
     * authenticate with it.
     */
    private static void record(Response response) throws Exception {
        String body = response.body() != null ? response.body().string() : "";
        ScenarioContext.put("lastResponseCode", response.code());
        ScenarioContext.put("lastResponseBody", body);

        if (!body.isBlank() && body.trim().startsWith("{")) {
            JsonNode token = MAPPER.readTree(body).get("token");
            if (token != null && token.isTextual()) ScenarioContext.put("signupToken", token.asText());
        }
    }

    private static void insertCode(String code, Instant expiresAt) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        db.getCollection("inviteCodes").insertOne(new Document()
                .append("code", code)
                .append("used", false)
                .append("note", "cucumber")
                .append("createdAt", new Date())
                .append("expiresAt", Date.from(expiresAt))
                .append("usedBy", null)
                .append("usedAt", null));
    }
}
