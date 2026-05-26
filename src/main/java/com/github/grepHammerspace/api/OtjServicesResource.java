package com.github.grepHammerspace.api;

import com.github.grepHammerspace.db.RegisterRequest;
import com.github.grepHammerspace.db.UserRepository;
import com.github.grepHammerspace.db.model.User;
import com.github.grepHammerspace.stateStore.UserStateStore;
import com.github.grepHammerspace.tailscale.TailscaleIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.cdimascio.dotenv.Dotenv;
import org.openqa.selenium.Keys;

import jakarta.validation.Valid;
import javax.inject.Inject;
import java.io.IOException;

/** Primary JAX-RS resource for OTJ automation endpoints.
 */
@Path("/otj-services")
@Produces("application/json")
@Consumes("application/json")
public class OtjServicesResource {
    private static final Logger log = LoggerFactory.getLogger(OtjServicesResource.class);

    private final UserStateStore userStateStore;
    private final UserRepository userRepository;
    private final TailscaleIdentityService tailscaleIdentityService;

    @Inject
    public OtjServicesResource(UserStateStore userStateStore, UserRepository userRepository,
                                TailscaleIdentityService tailscaleIdentityService) {
        this.userStateStore = userStateStore;
        this.userRepository = userRepository;
        this.tailscaleIdentityService = tailscaleIdentityService;
    }

    /**
     * Launches a Firefox browser, navigates to the OA login page, fills credentials,
     * and blocks at the OTP field. The browser stays open so the caller can supply the MFA token.
     * MFA tokens expire in ~30 s, so the browser session is kept alive in {@link com.github.grepHammerspace.stateStore.UserStateStore}
     * rather than being recreated on each request.
     */
    @POST
    @Path("/prepare-browser")
    public Response prepareBrowser(@Context HttpServletRequest request) throws InterruptedException {
        String userId;
        try {
            userId = resolveUserState(request);
        } catch (IOException e) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }

        // TODO: Get username and password for user from Mongo, create OtjDriver and call prepareBrowser, store the driver in the UserState so it can be used for later steps

        return Response.ok().build();
    }

    @POST
    @Path("/register")
    public Response register(@Valid RegisterRequest body, @Context HttpServletRequest request) {
        try {
            log.info("Received request on /register");
            String userId = resolveUserState(request);
            log.info("Received registration request from user {}, registering them with learnerId {}", userId, body.learnerId());
            userRepository.save(new User(userId, body.username(), body.password(), body.learnerId()));
            userStateStore.createUserState(userId);
            return Response.status(Response.Status.CREATED).build();
        } catch (IOException e) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    /** Resolves the Tailscale login name and lazily initialises per-user state if it doesn't exist yet. */
    private String resolveUserState(HttpServletRequest request) throws IOException {
        String userId = tailscaleIdentityService.getUser(request);
        userStateStore.createUserState(userId);
        return userId;
    }
}
