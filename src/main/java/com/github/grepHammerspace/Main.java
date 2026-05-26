package com.github.grepHammerspace;

import com.github.grepHammerspace.api.OtjServicesResource;
import com.github.grepHammerspace.bind.AppComponent;
import com.github.grepHammerspace.bind.DaggerAppComponent;

public class Main {
    public static void main(String[] args) throws Exception {
        AppComponent component = DaggerAppComponent.create();
        OtjServicesResource resource = component.otjServicesResource();

        ServerBootstrap.start(8945, resource);

        System.out.println("Server started at http://0.0.0.0:8945");
        Thread.currentThread().join();
    }
}
