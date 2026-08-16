package integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.github.grepHammerspace.ServerBootstrap;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import org.glassfish.grizzly.http.server.HttpServer;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MongoDBContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Cucumber lifecycle hooks that manage infrastructure for each scenario.
 *
 * <p>Before each scenario two fresh Grizzly servers are started on random available ports and
 * wired via {@link TestAppComponent} / {@link TestAppModule}: the main API under
 * {@code "baseUrl"} and the admin API under {@code "adminBaseUrl"}. Because endpoints now require
 * a bearer token, the hook issues a real session token for {@code test-user-id} through the
 * production {@link com.github.grepHammerspace.auth.SessionTokenService} and publishes it as
 * {@code "authToken"} — step definitions attach it as {@code Authorization: Bearer}. After the
 * scenario both servers are shut down. The MongoDB Testcontainer is shared across all scenarios in
 * the suite — it is started lazily on the first scenario and left running for the remainder of
 * the test run. Note that because the database is not wiped between scenarios, tests should not
 * depend on the collection being empty.
 */
public class ServerHooks {
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    static final String TEST_USER_ID = "test-user-id";
    /** The one login {@link TestAppModule} allowlists — anything else must be refused. */
    static final String ADMIN_LOGIN = "admin@test.tailnet";

    /**
     * The OneAdvanced credentials and MFA code every scenario sends, and that no log line may
     * ever contain. Distinctive on purpose: a check against {@code "password"} would pass by
     * coincidence, whereas these strings can only appear if something logged the real value.
     */
    static final String OA_USERNAME = "leaktest@example.invalid";
    static final String OA_PASSWORD = "pw-DO-NOT-LOG-9f2a";
    static final String OA_MFA_CODE = "919191";
    static final List<String> SECRETS = List.of(OA_USERNAME, OA_PASSWORD, OA_MFA_CODE);

    private HttpServer server;
    private HttpServer adminServer;
    private ListAppender<ILoggingEvent> logCapture;
    private Level originalRootLevel;

    /**
     * Starts the MongoDB container if not already running, boots a Grizzly server on a random
     * free port with the authentication filter registered, and publishes {@code "baseUrl"},
     * {@code "db"} and {@code "authToken"} into {@link ScenarioContext} for use by step
     * definitions.
     */
    @Before
    public void start() throws IOException {
        if (!MONGO.isRunning()) MONGO.start();

        startLogCapture();

        int port = freePort();
        int adminPort = freePort();

        TestAppModule module = new TestAppModule(MONGO.getConnectionString());
        TestAppComponent component = DaggerTestAppComponent.builder()
            .testAppModule(module).build();

        String authToken = component.sessionTokenService().issue(TEST_USER_ID);

        server = ServerBootstrap.start(port, component.otjServicesResource(), component.authResource(),
            component.accountResource(), component.authenticationFilter());

        // The admin API is a genuinely separate server in production, so the tests run it as one
        // too — on its own port, with its own resources. Booting it inside the main server would
        // let a scenario pass while the real split was broken.
        adminServer = ServerBootstrap.start(adminPort, component.adminInviteResource(),
            component.adminIdentityFilter());

        ScenarioContext.init();
        ScenarioContext.put("baseUrl", "http://localhost:" + port);
        ScenarioContext.put("adminBaseUrl", "http://localhost:" + adminPort);
        ScenarioContext.put("db", component.mongoDatabase());
        ScenarioContext.put("authToken", authToken);
        ScenarioContext.put("keycloakDriver", component.keycloakDriver());
        ScenarioContext.put("azurePushDriver", component.azurePushDriver());
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    /** Shuts both Grizzly servers down after each scenario, then audits the captured log. */
    @After
    public void stop() {
        if (server != null) server.shutdownNow();
        if (adminServer != null) adminServer.shutdownNow();
        try {
            assertNothingLeaked();
        } finally {
            stopLogCapture();
        }
    }

    /**
     * Captures every log event the scenario produces, with the root logger opened up to TRACE.
     *
     * <p>TRACE rather than the configured level on purpose. Asserting at INFO would only prove
     * that the lines currently enabled are clean, not that the code never builds the string —
     * and the whole reason this guard exists is that {@code logback.xml} used to pin the two
     * drivers to DEBUG, which is what put Microsoft flow tokens into the production log.
     */
    private void startLogCapture() {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        originalRootLevel = root.getLevel();
        root.setLevel(Level.TRACE);
        logCapture = new ListAppender<>();
        logCapture.start();
        root.addAppender(logCapture);
    }

    private void stopLogCapture() {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (logCapture != null) {
            root.detachAppender(logCapture);
            logCapture.stop();
            logCapture = null;
        }
        root.setLevel(originalRootLevel);
    }

    /**
     * Fails the scenario if any sentinel credential reached the log.
     *
     * <p>Runs after every scenario rather than as an opt-in {@code Then} step, so a scenario
     * added later is covered without anyone remembering to ask for it.
     *
     * <p>Checks three places, because the formatted message alone would miss most of the real
     * leaks: the drivers put URLs into <em>exception</em> messages, and {@code log.warn(msg, e)}
     * keeps those in the throwable proxy rather than the message.
     */
    private void assertNothingLeaked() {
        if (logCapture == null) return;
        List<String> offenders = new ArrayList<>();

        for (ILoggingEvent event : List.copyOf(logCapture.list)) {
            StringBuilder haystack = new StringBuilder(event.getFormattedMessage());
            if (event.getArgumentArray() != null) {
                for (Object argument : event.getArgumentArray()) {
                    haystack.append(' ').append(String.valueOf(argument));
                }
            }
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                haystack.append(' ').append(t.getClassName()).append(' ').append(t.getMessage());
            }

            String text = haystack.toString();
            for (String secret : SECRETS) {
                if (text.contains(secret)) {
                    offenders.add(event.getLoggerName() + " [" + event.getLevel() + "] leaked "
                            + describe(secret) + ": " + event.getFormattedMessage());
                }
            }
        }

        if (!offenders.isEmpty()) {
            fail("Credentials reached the log:\n  " + String.join("\n  ", offenders));
        }
    }

    /** Names the secret without repeating it — this message itself ends up in CI output. */
    private static String describe(String secret) {
        if (secret.equals(OA_USERNAME)) return "the OneAdvanced username";
        if (secret.equals(OA_PASSWORD)) return "the OneAdvanced password";
        return "the MFA code";
    }
}
