package com.github.grepHammerspace;

import com.github.grepHammerspace.api.OtjServicesResource;
import com.github.grepHammerspace.bind.AppComponent;
import com.github.grepHammerspace.bind.DaggerAppComponent;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.UriBuilder;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.servlet.GrizzlyWebContainerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) throws Exception {
        URI baseUri = UriBuilder.fromUri("http://0.0.0.0/").port(8945).build();

        AppComponent component = DaggerAppComponent.create();
        OtjServicesResource resource = component.otjServicesResource();

        // Dagger builds the resource with all dependencies injected. We hand the pre-built instance
        // to Jersey via getSingletons() rather than registering the class, so that Jersey uses our
        // Dagger-managed instance instead of trying to instantiate it through HK2.
        ResourceConfig config = ResourceConfig.forApplication(new Application() {
            @Override
            public Set<Object> getSingletons() {
                return Set.of(resource);
            }
        });

        HttpServer server = GrizzlyWebContainerFactory.create(baseUri, new ServletContainer(config), null, null);
        server.getListener("grizzly").getTransport().setWorkerThreadPool(Executors.newVirtualThreadPerTaskExecutor());

        System.out.println("Server started at " + baseUri);
        Thread.currentThread().join();
    }
}
