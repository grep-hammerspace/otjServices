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
 * wired via {@link TestAppComponent} / {@link TestAppModule}. After the scenario the server
 * is shut down. The MongoDB Testcontainer is shared across all scenarios in the suite — it is
 * started lazily on the first scenario and left running for the remainder of the test run.
 * Note that because the database is not wiped between scenarios, tests should not depend on
 * the collection being empty.
 */
public class ServerHooks {
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    private HttpServer server;

    /**
     * Starts the MongoDB container if not already running, boots a Grizzly server on a random
     * free port, and publishes {@code "baseUrl"} and {@code "db"} into {@link ScenarioContext}
     * for use by step definitions.
     */
    @Before
    public void start() throws IOException {
        if (!MONGO.isRunning()) MONGO.start();

        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }

        TestAppModule module = new TestAppModule(MONGO.getConnectionString(), "test-user-id");
        TestAppComponent component = DaggerTestAppComponent.builder()
            .testAppModule(module).build();

        server = ServerBootstrap.start(port, component.otjServicesResource());
        ScenarioContext.init();
        ScenarioContext.put("baseUrl", "http://localhost:" + port);
        ScenarioContext.put("db", component.mongoDatabase());
    }

    /** Shuts down the Grizzly server after each scenario. */
    @After
    public void stop() {
        if (server != null) server.shutdownNow();
    }
}
