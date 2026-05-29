package com.github.grepHammerspace.api;

import com.github.grepHammerspace.db.ActivityLogRepository;
import com.github.grepHammerspace.api.dto.ActivityLogRequest;
import com.github.grepHammerspace.api.dto.RegisterRequest;
import com.github.grepHammerspace.api.dto.SubmitWithMfaRequest;
import com.github.grepHammerspace.db.*;
import com.github.grepHammerspace.db.model.User;
import com.github.grepHammerspace.stateStore.UserStateStore;
import com.github.grepHammerspace.tailscale.TailscaleIdentityService;
import com.github.grepHammerspace.webDriver.OtjDriver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final ActivityLogRepository activityLogRepository;
    private final TailscaleIdentityService tailscaleIdentityService;

    @Inject
    public OtjServicesResource(UserStateStore userStateStore, UserRepository userRepository,
                               TailscaleIdentityService tailscaleIdentityService, ActivityLogRepository activityLogRepository) {
        this.userStateStore = userStateStore;
        this.userRepository = userRepository;
        this.activityLogRepository = activityLogRepository;
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

        // TODO: Get username and password for user from Mongo, decrypt them and create OtjDriver and call prepareBrowser, store the driver in the UserState so it can be used for later steps
        // TODO: Ensure response code contains word "ready"
        return Response.ok().build();
    }

    @POST
    @Path("/register")
    public Response register(@Valid RegisterRequest body, @Context HttpServletRequest request) {
        try {
            String userId = resolveUserState(request);
            log.info("Received registration request from user {}, registering them with learnerId {}", userId, body.learnerId());
            userRepository.save(new User(userId, body.username(), body.password(), body.learnerId()));
            userStateStore.createUserState(userId);
            return Response.status(Response.Status.CREATED).build();
        } catch (IOException e) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/log-activities")
    public Response logActivtiesWithLlmHelp(@Valid ActivityLogRequest body, @Context HttpServletRequest request){
        // TODO Implement method
        try {
            String userid = resolveUserState(request);
        } catch (IOException e) {
            log.error("Failed to resolve user state for request to /submit-with-mfa, likely because the request did not come from an authenticated Tailscale user");
            log.error("{}",e.getStackTrace());
            return Response.status(Response.Status.UNAUTHORIZED).entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
        return null;
    }

    @POST
    @Path("/submit-with-mfa")
    public Response useMfaCodeToSubmitUnSubmittedOTJs(@Valid SubmitWithMfaRequest body, @Context HttpServletRequest request){

        int totalPending;
        int sucessfullyPosted;

        try {
            String userId = resolveUserState(request);
            log.info("Received submit-with-mfa request from user {}", userId);
            OtjDriver driver = userStateStore.getStateForUser(userId).driver();
            if (driver == null){
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("{\"error\": \"No prepared browser session found for user, call /prepare-browser first\"}").build();
            }

            driver.submitMfaToken(body.mfaCode());
            driver.LogAllPendingOtjs();

        } catch (IOException e) {
            log.error("Failed to resolve user state for request to /submit-with-mfa, likely because the request did not come from an authenticated Tailscale user");
            log.error("{}",e.getStackTrace());
            return Response.status(Response.Status.UNAUTHORIZED).entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (InterruptedException e) {
            log.error("Thread was interrupted when attempting to log otjs. {}", e.getMessage());
            log.error("{}",e.getStackTrace());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("{\"error\": \"An unexpected error occurred while using MFA to log activities\"}").build();
        }
        return null;
    }

    /** Resolves the Tailscale login name and lazily initialises per-user state if it doesn't exist yet. */
    private String resolveUserState(HttpServletRequest request) throws IOException {
        String userId = tailscaleIdentityService.getUser(request);
        userStateStore.createUserState(userId);
        return userId;
    }
}
