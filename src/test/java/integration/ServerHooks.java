package integration;

import com.github.grepHammerspace.ServerBootstrap;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import org.glassfish.grizzly.http.server.HttpServer;
import org.testcontainers.containers.MongoDBContainer;

import java.io.IOException;
import java.net.ServerSocket;

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
    private HttpServer server;
    private HttpServer adminServer;

    /**
     * Starts the MongoDB container if not already running, boots a Grizzly server on a random
     * free port with the authentication filter registered, and publishes {@code "baseUrl"},
     * {@code "db"} and {@code "authToken"} into {@link ScenarioContext} for use by step
     * definitions.
     */
    @Before
    public void start() throws IOException {
        if (!MONGO.isRunning()) MONGO.start();

        int port = freePort();
        int adminPort = freePort();

        TestAppModule module = new TestAppModule(MONGO.getConnectionString());
        TestAppComponent component = DaggerTestAppComponent.builder()
            .testAppModule(module).build();

        String authToken = component.sessionTokenService().issue(TEST_USER_ID);

        server = ServerBootstrap.start(port, component.otjServicesResource(), component.authResource(),
            component.authenticationFilter());

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
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    /** Shuts both Grizzly servers down after each scenario. */
    @After
    public void stop() {
        if (server != null) server.shutdownNow();
        if (adminServer != null) adminServer.shutdownNow();
    }
}
