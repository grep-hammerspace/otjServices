package integration;

import com.mongodb.client.MongoDatabase;
import io.cucumber.java.en.And;
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

    @And("user {string} in the users collection has fields:")
    public void userHasFields(String appUsername, io.cucumber.datatable.DataTable table) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        org.bson.Document doc = db.getCollection("users")
            .find(new org.bson.Document("appUsername", appUsername))
            .first();
        assertNotNull(doc, "Expected user '" + appUsername + "' in MongoDB but found none");
        table.asMap().forEach((field, expected) -> {
            if (field.equals("appPasswordHash")) {
                assertTrue(doc.getString(field).startsWith(expected),
                    "Field 'appPasswordHash' should start with '" + expected + "' for user '" + appUsername + "'");
            } else {
                assertEquals(expected, doc.getString(field),
                    "Field '" + field + "' mismatch for user '" + appUsername + "'");
            }
        });
    }

    @And("no recoverable password is stored for user {string}")
    public void noRecoverablePassword(String appUsername) {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        org.bson.Document doc = db.getCollection("users")
            .find(new org.bson.Document("appUsername", appUsername))
            .first();
        assertNotNull(doc, "Expected user '" + appUsername + "' in MongoDB but found none");
        assertNull(doc.get("password"), "legacy recoverable 'password' field must not exist");
        assertTrue(doc.getString("appPasswordHash").startsWith("$2a$"),
            "appPasswordHash must be a bcrypt hash");
    }
}
