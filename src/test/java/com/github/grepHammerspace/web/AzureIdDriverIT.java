package com.github.grepHammerspace.web;

import com.github.grepHammerspace.db.ActivityLogRepository;
import com.github.grepHammerspace.db.model.ActivityLog;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Manual, live end-to-end run of the Azure AD login + OTJ submission flow.
 *
 * <p>This hits the real QMUL Azure AD / OneAdvanced endpoints and blocks waiting for a real
 * Microsoft Authenticator approval on your phone, so it is intentionally named {@code *IT}
 * (this project has no failsafe plugin configured, so {@code mvn test} never picks it up) and
 * must be run by hand, e.g.:
 *
 * <pre>
 * OTJ_IT_USERNAME=you@qmul.ac.uk OTJ_IT_PASSWORD=… OTJ_IT_LEARNER_ID=… \
 *   mvn test -Dtest=AzureIdDriverIT
 * </pre>
 *
 * <p>Credentials come from the environment rather than constants. They used to be fields with a
 * "fill in locally" comment, which committed a real account name and left a guard that compared
 * the password against a placeholder it no longer held — so the assumption always passed and the
 * test fired a live login with an empty password.
 */
class AzureIdDriverIT {

    private static final String USERNAME   = System.getenv("OTJ_IT_USERNAME");
    private static final String PASSWORD   = System.getenv("OTJ_IT_PASSWORD");
    private static final String LEARNER_ID = System.getenv("OTJ_IT_LEARNER_ID");

    private static final String TEST_USER_ID = "azure-id-it-user";

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    static ActivityLogRepository repository;

    @BeforeAll
    static void seedMockedActivityLog() {
        MONGO.start();
        repository = new ActivityLogRepository(
                MongoClients.create(MONGO.getConnectionString()).getDatabase("testdb")
        );

        // A mocked-up activity log — mirrors what LlmServiceImpl.toActivityLog() would produce.
        ActivityLog mocked = new ActivityLog(
                TEST_USER_ID,
                LEARNER_ID,
                "Reviewed and refactored the Azure AD OTJ submission integration.",
                "",
                "2026/07/13",
                "09:00",
                0,
                1,
                30,
                false,
                null
        );
        repository.saveActivityLog(mocked);
        System.out.println("[IT] Seeded 1 mocked, unposted ActivityLog for user " + TEST_USER_ID);
    }

    @Test
    void login_thenSubmitMockedActivityLog() throws Exception {
        assumeTrue(USERNAME != null && !USERNAME.isBlank()
                        && PASSWORD != null && !PASSWORD.isBlank()
                        && LEARNER_ID != null && !LEARNER_ID.isBlank(),
                "Set OTJ_IT_USERNAME, OTJ_IT_PASSWORD and OTJ_IT_LEARNER_ID to run this manual test");

        AzureIdDriver driver = new AzureIdDriver(repository);

        System.out.println("[IT] Step 1/3 — starting login (PKCE -> Keycloak -> Azure AD)...");
        PrepareResult prepareResult = driver.prepare(USERNAME, PASSWORD);
        System.out.println("[IT] prepare() returned status=" + prepareResult.status() + " — " + prepareResult.userMessage());

        if (prepareResult.requiresMfa()) {
            System.out.println("[IT] Step 2/3 — approve the push on Microsoft Authenticator now. "
                    + "Waiting up to 2 minutes for approval...");
            driver.completeMfa("");
            System.out.println("[IT] MFA approved — education.oneadvanced.com session cookies acquired.");
        } else {
            System.out.println("[IT] Step 2/3 — existing Microsoft SSO session completed login, no MFA needed.");
        }

        System.out.println("[IT] Cookies on oneadvanced.com after login: " + driver.cookieNamesFor("oneadvanced"));

        System.out.println("[IT] Step 3/3 — posting the 1 mocked activity log to the OneAdvanced activity-log API...");
        OtjSubmitResult result = driver.submitPendingOtjs(TEST_USER_ID, LEARNER_ID);

        System.out.println("[IT] submitPendingOtjs() result — posted=" + result.posted() + " failed=" + result.failed());
        if (result.allPosted()) {
            System.out.println("[IT] SUCCESS — activity log posted and marked as posted in Mongo.");
        } else if (result.allFailed()) {
            System.out.println("[IT] FAILED — see AzureIdDriver logs above for the HTTP status. "
                    + "This is expected to need a look on the very first live run per the submission plan's notes.");
        }

        assertFalse(result.nothingToPost(), "expected the driver to attempt posting the seeded mocked activity log");

        List<ActivityLog> stillPending = repository.getUnpostedActivityLogsFor(TEST_USER_ID);
        System.out.println("[IT] Unposted logs remaining for user after run: " + stillPending.size());
    }
}
