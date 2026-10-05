package integration;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        record(HttpSteps.authenticated(new Request.Builder().url(base + path))
            .header("Origin", origin).get().build());
    }

    @When("a browser at {string} calls GET {string} without a token")
    public void getFromWithoutToken(String origin, String path) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        record(new Request.Builder().url(base + path).header("Origin", origin).get().build());
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
