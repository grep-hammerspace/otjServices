package integration;

import io.cucumber.java.en.Then;

import static org.junit.jupiter.api.Assertions.*;

public class SharedSteps {
    @Then("the response status is {int}")
    public void checkStatus(int expectedStatus) {
        assertEquals(expectedStatus, ScenarioContext.get("lastResponseCode"));
    }

    @Then("the response body contains {string}")
    public void responseBodyContains(String expected) {
        String body = (String) ScenarioContext.get("lastResponseBody");
        assertTrue(body != null && body.contains(expected),
                "Expected body to contain: " + expected + "\nActual: " + body);
    }
}
