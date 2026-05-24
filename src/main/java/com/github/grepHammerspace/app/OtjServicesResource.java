package com.github.grepHammerspace.app;

import com.github.grepHammerspace.tailscale.TailscaleIdentityHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@Path("/otj-services")
@Produces("application/json")
@Consumes("application/json")
public class OtjServicesResource {
    private static final Logger log = LoggerFactory.getLogger(OtjServicesResource.class);

    private final TailscaleIdentityHelper tailscaleIdHelper;

    public OtjServicesResource() {
        this.tailscaleIdHelper = new TailscaleIdentityHelper();
    }

    @Context
    private HttpServletRequest request;

    @GET
    public Response testGetTailscaleId() {
        try {
            log.info("Attempting to get identity for request from {}:{}", request.getRemoteAddr(), request.getRemotePort());
            String user = TailscaleIdentityHelper.getUser(request);
            return Response.ok("{\"user\": \"" + user + "\"}").build();
        } catch (IOException e) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }
}
