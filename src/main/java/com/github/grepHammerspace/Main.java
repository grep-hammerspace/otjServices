package com.github.grepHammerspace;

import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.core.UriBuilder;
import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import java.net.URI;
import java.util.concurrent.Executors;

public class Main {
    static void main() throws Exception{
        URI baseUri = UriBuilder.fromUri("http://0.0.0.0/").port(8945).build();

        ResourceConfig config = new ResourceConfig(OtjServicesApp.class);
        HttpServer server = JdkHttpServerFactory.createHttpServer(baseUri, config, false);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.start();
        System.out.println("Server started at " + baseUri);


    }
}
