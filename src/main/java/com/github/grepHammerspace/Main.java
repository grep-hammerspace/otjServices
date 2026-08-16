package com.github.grepHammerspace;

import com.github.grepHammerspace.bind.AppComponent;
import com.github.grepHammerspace.bind.DaggerAppComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application entry point. */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        AppComponent component = DaggerAppComponent.create();

        ServerBootstrap.start(8945, component.otjServicesResource(), component.authResource(),
                component.accountResource(), component.authenticationFilter());

        log.info("Server started at http://0.0.0.0:8945");
        Thread.currentThread().join();
    }
}
