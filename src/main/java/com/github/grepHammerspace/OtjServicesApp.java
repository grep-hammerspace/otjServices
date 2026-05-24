package com.github.grepHammerspace;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;

/**
 * Main application server
 */

@ApplicationPath("/api")
@Produces("application/json")
@Consumes("application/json")
public class OtjServicesApp extends Application {



}
