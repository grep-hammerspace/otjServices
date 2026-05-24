package com.github.grepHammerspace;

import com.github.grepHammerspace.app.OtjServicesResource;
import jakarta.ws.rs.core.UriBuilder;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.servlet.GrizzlyWebContainerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import java.net.URI;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) throws Exception {
        URI baseUri = UriBuilder.fromUri("http://0.0.0.0/").port(8945).build();

        ResourceConfig config = new ResourceConfig(OtjServicesResource.class);
        HttpServer server = GrizzlyWebContainerFactory.create(baseUri, new ServletContainer(config), null, null);
        server.getListener("grizzly").getTransport().setWorkerThreadPool(Executors.newVirtualThreadPerTaskExecutor());

        System.out.println("Server started at " + baseUri);
        Thread.currentThread().join();
    }
}
