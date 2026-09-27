package integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RateLimitSteps {
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @When("I POST {string} with username {string}, password {string} {int} times")
    public void postLoginRepeatedly(String path, String username, String password, int times)
            throws Exception {
        for (int i = 0; i < times; i++) {
            login(path, username, password);
        }
    }

    @Then("the response header {string} is a positive integer")
    public void responseHeaderIsPositiveInteger(String header) {
        String value = headerValue(header);
        assertNotNull(value, "expected a " + header + " header, got none."
                + "\n  status:  " + ScenarioContext.get("lastResponseCode")
                + "\n  body:    " + ScenarioContext.get("lastResponseBody")
                + "\n  headers: " + ScenarioContext.get("lastResponseHeaders"));

        long seconds = Long.parseLong(value.strip());
        assertTrue(seconds > 0, header + " should be a positive number of seconds, was " + seconds);
    }

    @Then("the response has no {string} header")
    public void responseHasNoHeader(String header) {
        assertNull(headerValue(header), "did not expect a " + header + " header");
    }

    private static String headerValue(String header) {
        Headers headers = (Headers) ScenarioContext.get("lastResponseHeaders");
        return headers == null ? null : headers.get(header);
    }

    private void login(String path, String username, String password) throws Exception {
        String base = (String) ScenarioContext.get("baseUrl");
        Request request = new Request.Builder()
                .url(base + path)
                .post(RequestBody.create(
                        MAPPER.writeValueAsString(Map.of("username", username, "password", password)),
                        JSON))
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            ScenarioContext.put("lastResponseCode", response.code());
            ScenarioContext.put("lastResponseBody", response.body() != null ? response.body().string() : "");
            ScenarioContext.put("lastResponseHeaders", response.headers());
        }
    }
}
