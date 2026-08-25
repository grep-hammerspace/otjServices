package integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.grepHammerspace.api.dto.CredentialKeyResponse;
import com.github.grepHammerspace.crypto.CredentialKeyRing;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step definitions for {@code crypto.feature} — the key announcement.
 *
 * <p>The signature check here is the app's check, written out: rebuild the signed string from the
 * fields as they arrived, verify it against the identity key the client pins, refuse everything if
 * it does not verify. Keeping a second copy of that string next to
 * {@link CredentialKeyRing}'s own is deliberate — if someone changes the format on one side, this
 * fails, which is exactly what would happen to every phone in the field.
 */
public class CryptoSteps {

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @When("I GET the credential public key using the signup token")
    public void getPublicKey() throws Exception {
        get("Bearer " + ScenarioContext.get("signupToken"));
    }

    @When("I GET the credential public key without a token")
    public void getPublicKeyAnonymously() throws Exception {
        get(null);
    }

    @Then("the announcement verifies against the pinned identity key")
    public void announcementVerifies() throws Exception {
        CredentialKeyResponse key = lastAnnouncement();

        String signed = "otj-credential-key-v1|" + key.keyId() + "|" + key.publicKey()
                + "|" + key.expiresAt();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(ServerHooks.IDENTITY.getPublic());
        verifier.update(signed.getBytes(StandardCharsets.UTF_8));

        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(key.signature())),
                "an announcement the pinned key cannot verify makes the app refuse to submit");
    }

    @Then("the announcement names the sealed-credential algorithm")
    public void announcementNamesAlgorithm() throws Exception {
        assertEquals(CredentialKeyRing.ALGORITHM, lastAnnouncement().algorithm());
    }

    @Then("the announced key is {int} bytes")
    public void announcedKeyLength(int expected) throws Exception {
        assertEquals(expected,
                Base64.getUrlDecoder().decode(lastAnnouncement().publicKey()).length);
    }

    @Then("the announcement has not expired")
    public void announcementHasNotExpired() throws Exception {
        assertTrue(lastAnnouncement().expiresAt() > java.time.Instant.now().getEpochSecond(),
                "a client caches this until expiresAt — an expired one would be re-fetched forever");
    }

    private static CredentialKeyResponse lastAnnouncement() throws Exception {
        return MAPPER.readValue((String) ScenarioContext.get("lastResponseBody"),
                CredentialKeyResponse.class);
    }

    private void get(String authorization) throws Exception {
        Request.Builder request = new Request.Builder()
                .url(ScenarioContext.get("baseUrl") + "/otj-services/crypto/public-key")
                .get();
        if (authorization != null) request.header("Authorization", authorization);

        try (Response response = HTTP.newCall(request.build()).execute()) {
            ScenarioContext.put("lastResponseCode", response.code());
            ScenarioContext.put("lastResponseBody", response.body() != null ? response.body().string() : "");
        }
    }
}
