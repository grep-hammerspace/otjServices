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
 * <p>Before each scenario a fresh Grizzly server is started on a random available port and
 * wired via {@link TestAppComponent} / {@link TestAppModule}. Because endpoints now require a
 * bearer token, the hook issues a real session token for {@code test-user-id} through the
 * production {@link com.github.grepHammerspace.auth.SessionTokenService} and publishes it as
 * {@code "authToken"} — step definitions attach it as {@code Authorization: Bearer}. After the
 * scenario the server is shut down. The MongoDB Testcontainer is shared across all scenarios in
 * the suite — it is started lazily on the first scenario and left running for the remainder of
 * the test run. Note that because the database is not wiped between scenarios, tests should not
 * depend on the collection being empty.
 */
public class ServerHooks {
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    static final String TEST_USER_ID = "test-user-id";
    private HttpServer server;

    /**
     * Starts the MongoDB container if not already running, boots a Grizzly server on a random
     * free port with the authentication filter registered, and publishes {@code "baseUrl"},
     * {@code "db"} and {@code "authToken"} into {@link ScenarioContext} for use by step
     * definitions.
     */
    @Before
    public void start() throws IOException {
        if (!MONGO.isRunning()) MONGO.start();

        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }

        TestAppModule module = new TestAppModule(MONGO.getConnectionString());
        TestAppComponent component = DaggerTestAppComponent.builder()
            .testAppModule(module).build();

        String authToken = component.sessionTokenService().issue(TEST_USER_ID);

        server = ServerBootstrap.start(port, component.otjServicesResource(), component.authenticationFilter());
        ScenarioContext.init();
        ScenarioContext.put("baseUrl", "http://localhost:" + port);
        ScenarioContext.put("db", component.mongoDatabase());
        ScenarioContext.put("authToken", authToken);
    }

    /** Shuts down the Grizzly server after each scenario. */
    @After
    public void stop() {
        if (server != null) server.shutdownNow();
    }
}
