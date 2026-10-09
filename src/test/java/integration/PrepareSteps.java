package integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.grepHammerspace.api.dto.CredentialKeyResponse;
import com.github.grepHammerspace.api.dto.SealedEnvelope;
import com.github.grepHammerspace.crypto.TestSealer;
import com.github.grepHammerspace.web.LoginChainException;
import com.github.grepHammerspace.web.PrepareResult;
import com.github.grepHammerspace.web.SecurityInfoRequiredException;
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
import java.util.Base64;
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
        // Shaped like AzureIdDriver's own: a LoginChainException's URL is SafeUrl-redacted, which is
        // why its message is allowed into the log.
        driver(which).willFail(new LoginChainException(
                "Expected MFA page (ConvergedTFA), got pgid=ConvergedSignIn — URL: "
                        + "https://login.microsoftonline.com/common/oauth2/authorize"));
    }

    @Given("the {string} driver will stop at Microsoft's security info prompt")
    public void driverWillStopAtProofUp(String which) {
        driver(which).willFail(new SecurityInfoRequiredException());
    }

    @Given("the {string} driver will stop at Microsoft's security info prompt after approval")
    public void driverWillStopAtProofUpAfterApproval(String which) {
        driver(which).willFailMfa(new SecurityInfoRequiredException());
    }

    @Given("the {string} driver will fail to reach OneAdvanced")
    public void driverWillFailToReachOneAdvanced(String which) {
        // A plain IOException's message is the leak channel: it can carry an unredacted login-chain
        // URL with login_hint=<username>, which must reach neither the response nor the log.
        driver(which).willFail(new IOException(
                "Connection reset fetching https://education.oneadvanced.com/?login_hint="
                        + ServerHooks.OA_USERNAME));
    }

    @When("I POST {string} with the OneAdvanced credentials using the signup token")
    public void postWithCredentials(String path) throws Exception {
        postJson(path, MAPPER.writeValueAsString(sealed(ServerHooks.OA_USERNAME, ServerHooks.OA_PASSWORD)));
    }

    @When("I POST {string} with a blank OneAdvanced password using the signup token")
    public void postWithBlankPassword(String path) throws Exception {
        postJson(path, MAPPER.writeValueAsString(sealed(ServerHooks.OA_USERNAME, "")));
    }

    // Pins the cutover: the old plaintext shape must be refused.
    @When("I POST {string} with plaintext OneAdvanced credentials using the signup token")
    public void postPlaintextCredentials(String path) throws Exception {
        postJson(path, MAPPER.writeValueAsString(
                credentials(ServerHooks.OA_USERNAME, ServerHooks.OA_PASSWORD)));
    }

    @When("I POST {string} with credentials sealed to an unknown key using the signup token")
    public void postSealedToUnknownKey(String path) throws Exception {
        SealedEnvelope real = sealed(ServerHooks.OA_USERNAME, ServerHooks.OA_PASSWORD);
        postJson(path, MAPPER.writeValueAsString(new SealedEnvelope(real.v(), "AAAAAAAAAAA",
                real.epk(), real.nonce(), real.ciphertext())));
    }

    @When("I POST {string} with a tampered credential envelope using the signup token")
    public void postTamperedEnvelope(String path) throws Exception {
        SealedEnvelope real = sealed(ServerHooks.OA_USERNAME, ServerHooks.OA_PASSWORD);
        byte[] ciphertext = Base64.getUrlDecoder().decode(real.ciphertext());
        ciphertext[0] ^= 0x01;
        postJson(path, MAPPER.writeValueAsString(new SealedEnvelope(real.v(), real.keyId(),
                real.epk(), real.nonce(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext))));
    }

    @When("I POST {string} with credentials sealed {int} minutes ago using the signup token")
    public void postStaleEnvelope(String path, int minutes) throws Exception {
        CredentialKeyResponse key = fetchKey();
        postJson(path, MAPPER.writeValueAsString(TestSealer.seal(key.publicKey(), key.keyId(),
                ServerHooks.OA_USERNAME, ServerHooks.OA_PASSWORD,
                java.time.Instant.now().minusSeconds(minutes * 60L).getEpochSecond())));
    }

    @When("I POST {string} with a learnerId sealed into the credentials using the signup token")
    public void postSealedLearnerId(String path) throws Exception {
        CredentialKeyResponse key = fetchKey();
        postJson(path, MAPPER.writeValueAsString(TestSealer.sealRaw(key.publicKey(), key.keyId(),
                "{\"username\":\"" + ServerHooks.OA_USERNAME + "\",\"password\":\""
                        + ServerHooks.OA_PASSWORD + "\",\"learnerId\":\"L-INJECTED\",\"iat\":"
                        + java.time.Instant.now().getEpochSecond() + "}")));
    }

    @When("I POST {string} with the OneAdvanced credentials and learnerId {string} using the signup token")
    public void postWithCredentialsAndLearnerId(String path, String learnerId) throws Exception {
        SealedEnvelope real = sealed(ServerHooks.OA_USERNAME, ServerHooks.OA_PASSWORD);
        Map<String, Object> body = MAPPER.convertValue(real, Map.class);
        body.put("learnerId", learnerId);
        postJson(path, MAPPER.writeValueAsString(body));
    }

    @When("I POST {string} with the MFA code using the signup token")
    public void postMfaCode(String path) throws Exception {
        // Not sealed: a ~30 s code isn't worth a key fetch on the most time-critical call.
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

    private static SealedEnvelope sealed(String username, String password) throws Exception {
        CredentialKeyResponse key = fetchKey();
        return TestSealer.seal(key.publicKey(), key.keyId(), username, password);
    }

    // Cached per scenario, so preparing twice seals to the same key.
    static CredentialKeyResponse fetchKey() throws Exception {
        CredentialKeyResponse cached = (CredentialKeyResponse) ScenarioContext.get("credentialKey");
        if (cached != null) return cached;

        Request request = new Request.Builder()
                .url(ScenarioContext.get("baseUrl") + "/otj-services/crypto/public-key")
                .header("Authorization", "Bearer " + ScenarioContext.get("signupToken"))
                .get()
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            assertEquals(200, response.code(), "the credential key endpoint should answer 200");
            CredentialKeyResponse key = MAPPER.readValue(response.body().string(),
                    CredentialKeyResponse.class);
            ScenarioContext.put("credentialKey", key);
            return key;
        }
    }

    private void postJson(String path, String json) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request request = new Request.Builder()
                .url(base + path)
                .header("Authorization", "Bearer " + ScenarioContext.get("signupToken"))
                .post(RequestBody.create(json, JSON))
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            ScenarioContext.put("lastResponseCode", response.code());
            ScenarioContext.put("lastResponseBody", response.body() != null ? response.body().string() : "");
        }
    }
}
