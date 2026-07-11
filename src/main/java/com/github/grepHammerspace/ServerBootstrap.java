package com.github.grepHammerspace;

import com.github.grepHammerspace.api.HealthResource;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.UriBuilder;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.servlet.GrizzlyWebContainerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

/** Configures and starts the Grizzly HTTP server. */
public class ServerBootstrap {
    /**
     * Starts the server on {@code port}, replacing Grizzly's default thread pool with a
     * virtual-thread-per-task executor so blocking login/submission HTTP calls don't saturate carrier threads.
     */
    public static HttpServer start(int port, Object... resources) throws IOException {
        URI baseUri = UriBuilder.fromUri("http://0.0.0.0/").port(port).build();
        Set<Object> singletons = new HashSet<>(Arrays.asList(resources));
        singletons.add(new HealthResource());
        ResourceConfig config = ResourceConfig.forApplication(new Application() {
            @Override public Set<Object> getSingletons() { return singletons; }
        });
        HttpServer server = GrizzlyWebContainerFactory.create(
            baseUri, new ServletContainer(config), null, null);
        server.getListener("grizzly").getTransport()
            .setWorkerThreadPool(Executors.newVirtualThreadPerTaskExecutor());
        return server;
    }
}
