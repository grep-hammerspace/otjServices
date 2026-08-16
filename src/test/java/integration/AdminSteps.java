package integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import okhttp3.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step definitions for {@code admin_invites.feature}.
 *
 * <p>These talk to {@code adminBaseUrl} — a different server on a different port from every other
 * feature, which is the point: in production the admin API is a separate process reachable only
 * through its own {@code tailscale serve} mapping.
 *
 * <p>The identity header is set explicitly here because there is no {@code tailscale serve} in
 * front of the test server to inject it. That is exactly the production trust model with the
 * proxy removed, so scenarios can exercise an allowlisted login, an unlisted one and no header
 * at all.
 *
 * <p>A minted code is stashed under {@code "mintedCode"} so signup steps can redeem it — that
 * hand-off is what proves the admin API and the signup path actually fit together.
 */
public class AdminSteps {

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @When("the admin mints an invite code")
    public void mintInviteCode() throws Exception {
        mintAs(ServerHooks.ADMIN_LOGIN, "{\"note\":\"cucumber\"}");
    }

    @When("the admin mints an invite code expiring in {int} days")
    public void mintInviteCodeExpiringIn(int days) throws Exception {
        mintAs(ServerHooks.ADMIN_LOGIN, "{\"note\":\"cucumber\",\"expiresInDays\":" + days + "}");
    }

    @When("{string} mints an invite code")
    public void someoneMintsInviteCode(String login) throws Exception {
        mintAs(login, "{\"note\":\"cucumber\"}");
    }

    @When("an anonymous caller mints an invite code")
    public void anonymousMintsInviteCode() throws Exception {
        Request req = new Request.Builder()
                .url(adminBase() + "/admin/invites")
                .post(RequestBody.create("{\"note\":\"cucumber\"}", JSON))
                .build();
        record(HTTP.newCall(req).execute());
    }

    @When("the admin lists invite codes")
    public void listInviteCodes() throws Exception {
        Request req = new Request.Builder()
                .url(adminBase() + "/admin/invites")
                .header(AdminSteps.IDENTITY_HEADER, ServerHooks.ADMIN_LOGIN)
                .get()
                .build();
        record(HTTP.newCall(req).execute());
    }

    @When("the admin revokes the minted code")
    public void revokeMintedCode() throws Exception {
        revoke(mintedCode());
    }

    @When("the admin revokes the code {string}")
    public void revokeNamedCode(String code) throws Exception {
        revoke(code);
    }

    @When("I sign up with the minted code as {string}")
    public void signUpWithMintedCode(String username) throws Exception {
        String body = MAPPER.writeValueAsString(java.util.Map.of(
                "inviteCode", mintedCode(),
                "username", username,
                "password", "pw",
                "learnerId", "L9"));
        Request req = new Request.Builder()
                .url(ScenarioContext.get("baseUrl") + "/auth/signup")
                .post(RequestBody.create(body, JSON))
                .build();
        record(HTTP.newCall(req).execute());
    }

    @When("the admin requests {string} on the admin server")
    public void getOnAdminServer(String path) throws Exception {
        Request req = new Request.Builder().url(adminBase() + path).get().build();
        record(HTTP.newCall(req).execute());
    }

    @Then("the minted code looks like an invite code")
    public void mintedCodeLooksRight() {
        assertNotNull(mintedCode(), "no code was returned by the mint call");
        assertTrue(mintedCode().matches("OTJ-[A-Z2-9]{4}-[A-Z2-9]{4}"),
                "unexpected code shape: " + mintedCode());
    }

    @Then("the response body has invite status {string}")
    public void responseHasStatus(String status) throws Exception {
        JsonNode body = MAPPER.readTree((String) ScenarioContext.get("lastResponseBody"));
        assertEquals(status, body.get("status").asText());
    }

    @Then("the listed code {string} has status {string}")
    public void listedCodeHasStatus(String code, String status) throws Exception {
        JsonNode body = MAPPER.readTree((String) ScenarioContext.get("lastResponseBody"));
        for (JsonNode entry : body) {
            if (code.equals(entry.get("code").asText())) {
                assertEquals(status, entry.get("status").asText(), "wrong status for " + code);
                return;
            }
        }
        throw new AssertionError("code " + code + " was not in the listing: " + body);
    }

    @Then("the minted code is listed with status {string}")
    public void mintedCodeListedWithStatus(String status) throws Exception {
        listedCodeHasStatus(mintedCode(), status);
    }

    static final String IDENTITY_HEADER = "Tailscale-User-Login";

    private static String adminBase() {
        return (String) ScenarioContext.get("adminBaseUrl");
    }

    private static String mintedCode() {
        return (String) ScenarioContext.get("mintedCode");
    }

    private void mintAs(String login, String body) throws Exception {
        Request req = new Request.Builder()
                .url(adminBase() + "/admin/invites")
                .header(IDENTITY_HEADER, login)
                .post(RequestBody.create(body, JSON))
                .build();
        record(HTTP.newCall(req).execute());
    }

    private void revoke(String code) throws Exception {
        Request req = new Request.Builder()
                .url(adminBase() + "/admin/invites/" + code)
                .header(IDENTITY_HEADER, ServerHooks.ADMIN_LOGIN)
                .delete()
                .build();
        record(HTTP.newCall(req).execute());
    }

    /** Records status and body, lifting any minted {@code code} out for later steps. */
    private static void record(Response response) throws Exception {
        String body = response.body() != null ? response.body().string() : "";
        ScenarioContext.put("lastResponseCode", response.code());
        ScenarioContext.put("lastResponseBody", body);

        if (!body.isBlank() && body.trim().startsWith("{")) {
            JsonNode code = MAPPER.readTree(body).get("code");
            if (code != null && code.isTextual()) ScenarioContext.put("mintedCode", code.asText());
        }
    }
}
