package integration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.cucumber.java.en.Given;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Step definitions for {@code llm_quota.feature}, and the quota reset that
 * {@code log_activities.feature} needs.
 *
 * <p>Cucumber glue is global, so both features share these.
 */
public class QuotaSteps {

    @Given("the test user has used {int} LLM calls today")
    public void seedQuota(int used) {
        collection().updateOne(
                Filters.and(Filters.eq("userId", ServerHooks.TEST_USER_ID), Filters.eq("date", today())),
                Updates.set("count", used),
                // upsert rather than insertOne: the suite's Mongo is not wiped between scenarios,
                // so a document for today may already exist and insertOne would hit the unique
                // index on {userId, date}.
                new UpdateOptions().upsert(true));
    }

    /**
     * Clears the counter across every date, not just today's.
     *
     * <p>Deliberately date-blind so the step cannot itself be wrong about the boundary — the one
     * bug it exists to prevent.
     */
    @Given("the test user has used no LLM calls today")
    public void resetQuota() {
        collection().deleteMany(Filters.eq("userId", ServerHooks.TEST_USER_ID));
    }

    private static com.mongodb.client.MongoCollection<org.bson.Document> collection() {
        MongoDatabase db = (MongoDatabase) ScenarioContext.get("db");
        return db.getCollection("llmQuota");
    }

    /**
     * UTC, matching {@code LlmQuotaService}. Using the system zone here would seed the document
     * under the wrong date for an hour a day on a UK box, and the scenario would fail with no
     * visible cause.
     */
    private static String today() {
        return LocalDate.now(Clock.systemUTC()).toString();
    }
}
