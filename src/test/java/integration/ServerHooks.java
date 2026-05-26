package integration;

import com.github.grepHammerspace.ServerBootstrap;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import org.glassfish.grizzly.http.server.HttpServer;
import org.testcontainers.containers.MongoDBContainer;

import java.io.IOException;
import java.net.ServerSocket;

public class ServerHooks {
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    private HttpServer server;

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

    @After
    public void stop() {
        if (server != null) server.shutdownNow();
    }
}
