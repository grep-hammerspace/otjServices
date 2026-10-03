package com.github.grepHammerspace;

import com.github.grepHammerspace.bind.AppComponent;
import com.github.grepHammerspace.bind.DaggerAppComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        AppComponent component = DaggerAppComponent.create();
        component.userRepository().ensureSingleUser();

        ServerBootstrap.start(8945, component.otjServicesResource(), component.accountResource());

        log.info("Server started at http://0.0.0.0:8945");
        Thread.currentThread().join();
    }
}
