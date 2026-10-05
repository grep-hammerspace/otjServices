package integration;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// OkHttp sends Origin and OPTIONS as given, which is all a browser's side of CORS amounts to here.
public class CorsSteps {
    private static final OkHttpClient HTTP = new OkHttpClient();

    @When("a browser at {string} preflights a {string} to {string}")
    public void preflight(String origin, String method, String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request request = new Request.Builder().url(base + path)
            .method("OPTIONS", null)
            .header("Origin", origin)
            .header("Access-Control-Request-Method", method)
            .header("Access-Control-Request-Headers", "authorization, content-type")
            .build();
        record(request);
    }

    @When("a browser at {string} calls GET {string}")
    public void getFrom(String origin, String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        record(new Request.Builder().url(base + path).header("Origin", origin).get().build());
    }

    @When("a browser at {string} calls DELETE {string}")
    public void deleteFrom(String origin, String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        record(new Request.Builder().url(base + path).header("Origin", origin).delete().build());
    }

    @Then("the response header {string} is {string}")
    public void headerIs(String header, String expected) {
        assertEquals(expected, header(header), header + " on " + ScenarioContext.get("lastResponseCode"));
    }

    @Then("the response header {string} contains {string}")
    public void headerContains(String header, String expected) {
        String value = header(header);
        assertNotNull(value, "expected a " + header + " header, got none");
        assertTrue(value.contains(expected), header + " was \"" + value + "\"");
    }

    // On master this step lives in RateLimitSteps, which this branch doesn't have.
    @Then("the response has no {string} header")
    public void noHeader(String header) {
        assertNull(header(header), "did not expect a " + header + " header");
    }

    private static String header(String name) {
        return ((Headers) ScenarioContext.get("lastResponseHeaders")).get(name);
    }

    private static void record(Request request) throws Exception {
        try (Response response = HTTP.newCall(request).execute()) {
            ScenarioContext.put("lastResponseCode", response.code());
            ScenarioContext.put("lastResponseBody", response.body() != null ? response.body().string() : "");
            ScenarioContext.put("lastResponseHeaders", response.headers());
        }
    }
}
