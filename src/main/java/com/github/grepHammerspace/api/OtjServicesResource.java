package com.github.grepHammerspace.api;

import com.github.grepHammerspace.api.dto.ActivityLogRequest;
import com.github.grepHammerspace.api.dto.ActivityLogResponse;
import com.github.grepHammerspace.api.dto.RegisterRequest;
import com.github.grepHammerspace.api.dto.SubmitWithMfaRequest;
import com.github.grepHammerspace.db.ActivityLogRepository;
import com.github.grepHammerspace.db.UserRepository;
import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.db.model.User;
import com.github.grepHammerspace.llm.ContentDiffer;
import com.github.grepHammerspace.llm.exception.LlmException;
import com.github.grepHammerspace.llm.exception.LlmRateLimitException;
import com.github.grepHammerspace.llm.LlmResult;
import com.github.grepHammerspace.llm.LlmService;
import com.github.grepHammerspace.stateStore.UserStateStore;
import com.github.grepHammerspace.tailscale.TailscaleIdentityService;
import com.github.grepHammerspace.web.OtjDriver;
import com.github.grepHammerspace.web.OtjSubmitResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.validation.Valid;
import javax.inject.Inject;
import java.io.IOException;
import java.time.LocalDate;

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
    private final LlmService llmService;

    @Inject
    public OtjServicesResource(UserStateStore userStateStore, UserRepository userRepository,
                               TailscaleIdentityService tailscaleIdentityService,
                               ActivityLogRepository activityLogRepository,
                               LlmService llmService) {
        this.userStateStore = userStateStore;
        this.userRepository = userRepository;
        this.activityLogRepository = activityLogRepository;
        this.tailscaleIdentityService = tailscaleIdentityService;
        this.llmService = llmService;
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
    public Response logActivtiesWithLlmHelp(@Valid ActivityLogRequest body, @Context HttpServletRequest request) {
        String userId;
        try {
            userId = resolveUserState(request);
        } catch (IOException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }

        String content = body.content() == null ? "" : body.content().strip();
        if (content.isBlank()) {
            String msg = "The 'content' field is missing or empty. " +
                    "Expected a non-empty string in the 'content' key of the JSON body. " +
                    "Got: content=" + body.content() + ". " +
                    "This field must contain the activity text to be logged.";
            log.warn(msg);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + msg + "\"}").build();
        }

        User user = userRepository.findByUserId(userId);
        if (user == null) {
            String msg = "No registered user found for this Tailscale identity. " +
                    "Call POST /otj-services/register first.";
            log.warn("User {} not found in repository", userId);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + msg + "\"}").build();
        }

        String lastContent = userRepository.getLastContent(userId);
        String diff = ContentDiffer.computeDiff(lastContent, content);

        if (diff == null) {
            String msg = "No new content detected. " +
                    "The incoming 'content' field is identical to what was last processed. " +
                    "Send content that includes new lines to trigger logging.";
            log.info(msg);
            return Response.ok("{\"status\": \"no new content\", \"detail\": \"" + msg + "\"}").build();
        }

        log.info("Diff contains new content ({} chars), calling LLM", diff.length());

        LlmResult result;
        try {
            result = llmService.parseActivities(diff, LocalDate.now().toString(), userId, user.learnerId());
        } catch (LlmRateLimitException e) {
            return Response.status(429)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (LlmException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (Exception e) {
            String msg = "Unexpected error calling LLM: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error(msg, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"" + msg + "\"}").build();
        }

        for (ActivityLog entry : result.ok()) {
            activityLogRepository.saveActivityLog(entry);
        }

        userRepository.saveLastContent(userId, content);

        log.info("Request complete — {} row(s) written, {} error(s)", result.ok().size(), result.errors().size());

        ActivityLogResponse responseBody = new ActivityLogResponse(
                "ok",
                result.ok().size(),
                result.ok(),
                result.errors().isEmpty() ? null : result.errors()
        );

        return Response.ok(responseBody).build();
    }

    @DELETE
    @Path("/delete-last-row")
    public Response deleteLastRow(@Context HttpServletRequest request) {
        String userId;
        try {
            userId = resolveUserState(request);
        } catch (IOException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }

        boolean deleted = activityLogRepository.deleteLastActivityLog(userId);
        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"No unposted activity log found for this user.\"}").build();
        }
        return Response.ok("{\"status\": \"ok\"}").build();
    }

    @DELETE
    @Path("/reset-notes")
    public Response resetNotes(@Context HttpServletRequest request) {
        String userId;
        try {
            userId = resolveUserState(request);
        } catch (IOException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }

        userRepository.clearLastContent(userId);
        return Response.ok("{\"status\": \"ok\"}").build();
    }

    @POST
    @Path("/submit-with-mfa")
    public Response useMfaCodeToSubmitUnSubmittedOTJs(@Valid SubmitWithMfaRequest body, @Context HttpServletRequest request){

        String userId;
        OtjDriver driver;
        try {
            userId = resolveUserState(request);
            log.info("Received submit-with-mfa request from user {}", userId);
            driver = userStateStore.getStateForUser(userId).driver();
            if (driver == null) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"No prepared browser session found for user, call /prepare-browser first\"}").build();
            }
        } catch (IOException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }

        try {
            driver.submitMfaToken(body.mfaCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Thread interrupted while waiting for MFA submission.\"}").build();
        }

        OtjSubmitResult result = driver.LogAllPendingOtjs(userId);

        if (result.nothingToPost()) {
            return Response.ok("{\"status\": \"nothing_to_post\", \"detail\": \"No unposted OTJs found.\"}").build();
        }
        if (result.allPosted()) {
            return Response.ok("{\"status\": \"ok\", \"posted\": " + result.posted().size() + "}").build();
        }
        if (result.allFailed()) {
            return Response.status(502)
                    .entity("{\"status\": \"all_failed\", \"total\": " + result.failed().size() + ", \"failed\": " + result.failed().size() + "}").build();
        }
        // partial success
        return Response.status(207)
                .entity("{\"status\": \"partial\", \"posted\": " + result.posted().size() + ", \"failed\": " + result.failed().size() + "}").build();
    }

    /** Resolves the Tailscale login name and lazily initialises per-user state if it doesn't exist yet. */
    private String resolveUserState(HttpServletRequest request) throws IOException {
        String userId = tailscaleIdentityService.getUser(request);
        userStateStore.createUserState(userId);
        return userId;
    }
}
