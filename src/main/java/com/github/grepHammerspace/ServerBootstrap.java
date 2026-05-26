package com.github.grepHammerspace;

import com.github.grepHammerspace.api.HealthResource;
import com.github.grepHammerspace.api.OtjServicesResource;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.UriBuilder;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.servlet.GrizzlyWebContainerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.Executors;

public class ServerBootstrap {
    public static HttpServer start(int port, OtjServicesResource resource) throws IOException {
        URI baseUri = UriBuilder.fromUri("http://0.0.0.0/").port(port).build();
        ResourceConfig config = ResourceConfig.forApplication(new Application() {
            @Override public Set<Object> getSingletons() { return Set.of(resource, new HealthResource()); }
        });
        HttpServer server = GrizzlyWebContainerFactory.create(
            baseUri, new ServletContainer(config), null, null);
        server.getListener("grizzly").getTransport()
            .setWorkerThreadPool(Executors.newVirtualThreadPerTaskExecutor());
        return server;
    }
}
