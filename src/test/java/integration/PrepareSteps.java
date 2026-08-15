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

/**
 * Step definitions for {@code prepare_and_submit.feature}.
 *
 * <p>All requests use the signup token, for the same reason {@link AccountSteps} does: these
 * endpoints read the caller's user document for the learner ID, and the hook's seeded
 * {@code test-user-id} has no account behind it.
 *
 * <p>Shared assertions are not redefined here — status and {@code contains} come from
 * {@link RegistrationSteps}, {@code does not contain} from {@link PendingSteps}, and the
 * stored-row check from {@link AccountSteps}.
 */
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

    // ── Scripting the fake ────────────────────────────────────────────────────

    @Given("the {string} driver will require a number match of {int}")
    public void driverWillRequireNumberMatch(String which, int number) {
        driver(which).willReturn(PrepareResult.mfaNumberMatch(number));
    }

    @Given("the {string} driver will complete the login without MFA")
    public void driverWillCompleteWithoutMfa(String which) {
        driver(which).willReturn(PrepareResult.loginComplete());
    }

    @Given("the {string} driver will reject the credentials")
    public void driverWillRejectCredentials(String which) {
        // Shaped like the real failure: AzureIdDriver reports the page it landed on, and that URL
        // carries login_hint=<username>. The endpoint must not pass any of it through.
        driver(which).willFail(new IOException(
                "Expected MFA page (ConvergedTFA), got pgid=ConvergedSignIn — URL: "
                        + "https://login.microsoftonline.com/common/oauth2/authorize"
                        + "?login_hint=" + ServerHooks.OA_USERNAME + ". Credentials may be wrong."));
    }

    // ── Requests ──────────────────────────────────────────────────────────────

    @When("I POST {string} with the OneAdvanced credentials using the signup token")
    public void postWithCredentials(String path) throws Exception {
        post(path, credentials(ServerHooks.OA_USERNAME, ServerHooks.OA_PASSWORD));
    }

    @When("I POST {string} with a blank OneAdvanced password using the signup token")
    public void postWithBlankPassword(String path) throws Exception {
        post(path, credentials(ServerHooks.OA_USERNAME, ""));
    }

    @When("I POST {string} with the OneAdvanced credentials and learnerId {string} using the signup token")
    public void postWithCredentialsAndLearnerId(String path, String learnerId) throws Exception {
        Map<String, Object> body = credentials(ServerHooks.OA_USERNAME, ServerHooks.OA_PASSWORD);
        body.put("learnerId", learnerId);
        post(path, body);
    }

    @When("I POST {string} with the MFA code using the signup token")
    public void postMfaCode(String path) throws Exception {
        post(path, Map.of("mfaCode", ServerHooks.OA_MFA_CODE));
    }

    // ── Assertions on what the driver received ────────────────────────────────

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

    // ── Plumbing ──────────────────────────────────────────────────────────────

    private static Map<String, Object> credentials(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return body;
    }

    private void post(String path, Map<String, Object> body) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request request = new Request.Builder()
                .url(base + path)
                .header("Authorization", "Bearer " + ScenarioContext.get("signupToken"))
                .post(RequestBody.create(MAPPER.writeValueAsString(body), JSON))
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            ScenarioContext.put("lastResponseCode", response.code());
            ScenarioContext.put("lastResponseBody", response.body() != null ? response.body().string() : "");
        }
    }
}
