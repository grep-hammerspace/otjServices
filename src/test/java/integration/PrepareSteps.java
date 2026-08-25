package integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.grepHammerspace.api.dto.CredentialKeyResponse;
import com.github.grepHammerspace.api.dto.SealedEnvelope;
import com.github.grepHammerspace.crypto.TestSealer;
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
import java.util.Base64;
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
 * <p>Every prepare request here is sealed, because the endpoints no longer accept anything else.
 * {@link #sealed} fetches the server's announced key over HTTP first — the same two-step the app
 * does — so these scenarios exercise the key endpoint on every run without asserting on it, and a
 * broken announcement fails the prepare scenarios rather than only the crypto ones.
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
        postJson(path, MAPPER.writeValueAsString(sealed(ServerHooks.OA_USERNAME, ServerHooks.OA_PASSWORD)));
    }

    @When("I POST {string} with a blank OneAdvanced password using the signup token")
    public void postWithBlankPassword(String path) throws Exception {
        postJson(path, MAPPER.writeValueAsString(sealed(ServerHooks.OA_USERNAME, "")));
    }

    /**
     * The pre-encryption body shape. Kept as a step so the cutover is pinned: this used to be the
     * only shape these endpoints took, and "we stopped accepting it" is the security property the
     * whole change rests on.
     */
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

    /** One flipped bit in the ciphertext — what a rewrite anywhere on the path would look like. */
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

    /**
     * A learner ID smuggled into the sealed payload rather than into the outer body. Encryption
     * moved where an injected field would have to go, so this is where the check belongs now — the
     * outer-body variant below stays as well, because both doors have to be shut.
     */
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
        // Not sealed: the TOTP is a one-shot code with about thirty seconds of life, and it is
        // already useless by the time anything could act on a copy of it. Sealing it would add a
        // key fetch to the most time-critical call in the app for no gain.
        postJson(path, MAPPER.writeValueAsString(Map.of("mfaCode", ServerHooks.OA_MFA_CODE)));
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

    /** Seals a pair to whatever key the server is currently announcing. */
    private static SealedEnvelope sealed(String username, String password) throws Exception {
        CredentialKeyResponse key = fetchKey();
        return TestSealer.seal(key.publicKey(), key.keyId(), username, password);
    }

    /**
     * Fetches the announcement, cached for the scenario.
     *
     * <p>Cached because a scenario that prepares twice must seal to the same key both times to be
     * testing what it says it is; re-fetching would also work today, since the key only rotates
     * after 24 hours, but that is a property of the ring rather than something these steps should
     * lean on.
     */
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
