package integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.github.grepHammerspace.ServerBootstrap;
import com.github.grepHammerspace.api.CorsFilter;
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

// The Mongo container is shared and never wiped between scenarios.
public class ServerHooks {
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    static final String TEST_USER_ID = "test-user-id";
    static final String ADMIN_LOGIN = "admin@test.tailnet";

    // Generated, not hard-coded: crypto.feature needs the public half, which the JDK can't derive
    // from a seed.
    static final java.security.KeyPair IDENTITY = generateIdentity();
    static final byte[] IDENTITY_SEED = seedOf(IDENTITY);

    private static java.security.KeyPair generateIdentity() {
        try {
            return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no Ed25519", e);
        }
    }

    private static byte[] seedOf(java.security.KeyPair pair) {
        byte[] encoded = pair.getPrivate().getEncoded();
        return java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    // Distinctive on purpose: these can only appear in a log if something logged the real value.
    static final String OA_USERNAME = "leaktest@example.invalid";
    static final String OA_PASSWORD = "pw-DO-NOT-LOG-9f2a";
    static final String OA_MFA_CODE = "919191";
    static final List<String> SECRETS = List.of(OA_USERNAME, OA_PASSWORD, OA_MFA_CODE);

    private HttpServer server;
    private HttpServer adminServer;
    private ListAppender<ILoggingEvent> logCapture;
    private Level originalRootLevel;

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
            component.accountResource(), component.cryptoResource(), component.authenticationFilter(),
            new CorsFilter());

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

    // TRACE, not the configured level: this proves the code never builds the string, not just that
    // the enabled lines are clean.
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

    // Checks message, arguments and the throwable chain: the drivers put URLs in exception
    // messages.
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

    // Names the secret without repeating it: this message ends up in CI output.
    private static String describe(String secret) {
        if (secret.equals(OA_USERNAME)) return "the OneAdvanced username";
        if (secret.equals(OA_PASSWORD)) return "the OneAdvanced password";
        return "the MFA code";
    }
}
