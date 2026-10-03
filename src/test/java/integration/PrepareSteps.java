package integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.grepHammerspace.web.PrepareResult;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PrepareSteps {
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static FakeDriver driver(String which) {
        String key = which.equals("keycloak") ? "keycloakDriver" : "azurePushDriver";
        FakeDriver fake = (FakeDriver) ScenarioContext.get(key);
        assertNotNull(fake, "no " + which + " fake driver in the scenario context");
        return fake;
    }

    @Given("the {string} driver will require a number match of {int}")
    public void driverWillRequireNumberMatch(String which, int number) {
        driver(which).willReturn(PrepareResult.pushSent(number));
    }

    @Given("the {string} driver will complete the login without MFA")
    public void driverWillCompleteWithoutMfa(String which) {
        driver(which).willReturn(PrepareResult.loggedIn());
    }

    @Given("the {string} driver will reject the credentials")
    public void driverWillRejectCredentials(String which) {
        // Shaped like the real failure: the URL carries login_hint=<username>, which the endpoint
        // must not pass through.
        driver(which).willFail(new IOException(
                "Expected MFA page (ConvergedTFA), got pgid=ConvergedSignIn — URL: "
                        + "https://login.microsoftonline.com/common/oauth2/authorize"
                        + "?login_hint=" + ServerHooks.OA_USERNAME + ". Credentials may be wrong."));
    }

    @When("I POST {string} with the OneAdvanced credentials")
    public void postWithCredentials(String path) throws Exception {
        postJson(path, MAPPER.writeValueAsString(credentials(ServerHooks.OA_USERNAME, ServerHooks.OA_PASSWORD)));
    }

    @When("I POST {string} with a blank OneAdvanced password")
    public void postWithBlankPassword(String path) throws Exception {
        postJson(path, MAPPER.writeValueAsString(credentials(ServerHooks.OA_USERNAME, "")));
    }

    @When("I POST {string} with the OneAdvanced credentials and learnerId {string}")
    public void postWithCredentialsAndLearnerId(String path, String learnerId) throws Exception {
        Map<String, Object> body = credentials(ServerHooks.OA_USERNAME, ServerHooks.OA_PASSWORD);
        body.put("learnerId", learnerId);
        postJson(path, MAPPER.writeValueAsString(body));
    }

    @When("I POST {string} with the MFA code")
    public void postMfaCode(String path) throws Exception {
        postJson(path, MAPPER.writeValueAsString(Map.of("mfaCode", ServerHooks.OA_MFA_CODE)));
    }

    @Then("the {string} driver received the OneAdvanced credentials")
    public void driverReceivedCredentials(String which) {
        FakeDriver fake = driver(which);
        assertEquals(ServerHooks.OA_USERNAME, fake.preparedUsername(),
                "the username on the request should reach the driver unchanged");
        assertEquals(ServerHooks.OA_PASSWORD, fake.preparedPassword(),
                "the password on the request should reach the driver unchanged");
    }

    @And("the {string} driver submitted with learnerId {string}")
    public void driverSubmittedWithLearnerId(String which, String expected) {
        assertEquals(expected, driver(which).submittedLearnerId(),
                "the learner ID should come from the account at submit time");
    }

    @And("the {string} driver was not called")
    public void driverWasNotCalled(String which) {
        assertEquals(0, driver(which).prepareCalls(),
                "a request rejected on validation should never reach the driver");
    }

    private static Map<String, Object> credentials(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return body;
    }

    private void postJson(String path, String json) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request request = new Request.Builder()
                .url(base + path)
                .post(RequestBody.create(json, JSON))
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            ScenarioContext.put("lastResponseCode", response.code());
            ScenarioContext.put("lastResponseBody", response.body() != null ? response.body().string() : "");
        }
    }
}
