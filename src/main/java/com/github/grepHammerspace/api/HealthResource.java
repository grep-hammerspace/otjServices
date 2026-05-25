package com.github.grepHammerspace.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/health")
public class HealthResource {
    @GET
    public Response health() {
        return Response.ok().build();
    }
}
