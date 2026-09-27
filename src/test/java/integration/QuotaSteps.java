package integration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.cucumber.java.en.Given;

import java.time.Clock;
import java.time.LocalDate;

public class QuotaSteps {
    @Given("the test user has used {int} LLM calls today")
    public void seedQuota(int used) {
        collection().updateOne(
                Filters.and(Filters.eq("userId", ServerHooks.TEST_USER_ID), Filters.eq("date", today())),
                Updates.set("count", used),
                new UpdateOptions().upsert(true));
    }

    @Given("the test user has used no LLM calls today")
    public void resetQuota() {
        collection().deleteMany(Filters.eq("userId", ServerHooks.TEST_USER_ID));
    }

    private static com.mongodb.client.MongoCollection<org.bson.Document> collection() {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        return db.getCollection("llmQuota");
    }

    // UTC, matching LlmQuotaService.
    private static String today() {
        return LocalDate.now(Clock.systemUTC()).toString();
    }
}
