package com.github.grepHammerspace.api;

import com.github.grepHammerspace.api.dto.ActivityLogRequest;
import com.github.grepHammerspace.api.dto.ActivityLogResponse;
import com.github.grepHammerspace.api.dto.RegisterRequest;
import com.github.grepHammerspace.api.dto.SubmitWithMfaRequest;
import com.github.grepHammerspace.auth.Authenticated;
import com.github.grepHammerspace.db.ActivityLogRepository;
import com.github.grepHammerspace.db.UserRepository;
import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.db.model.User;
import com.github.grepHammerspace.llm.ContentDiffer;
import com.github.grepHammerspace.llm.exception.LlmException;
import com.github.grepHammerspace.llm.exception.LlmRateLimitException;
import com.github.grepHammerspace.llm.LlmResult;
import com.github.grepHammerspace.llm.LlmService;
import com.github.grepHammerspace.stateStore.UserState;
import com.github.grepHammerspace.stateStore.UserStateStore;
import com.github.grepHammerspace.web.Driver;
import com.github.grepHammerspace.web.OtjDriver;
import com.github.grepHammerspace.web.OtjSubmitResult;
import com.github.grepHammerspace.web.PrepareResult;
import com.github.grepHammerspace.web.AzureIdDriver;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.validation.Valid;
import javax.inject.Inject;
import javax.inject.Provider;
import java.io.IOException;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Primary JAX-RS resource for OTJ automation endpoints.
 *
 * <p>All endpoints require a valid bearer token: {@link Authenticated} binds
 * {@link com.github.grepHammerspace.auth.AuthenticationFilter}, which rejects unauthenticated
 * requests with 401 before they reach this class and exposes the userId as the
 * {@link SecurityContext} principal.
 */
@Path("/otj-services")
@Produces("application/json")
@Consumes("application/json")
@Authenticated
public class OtjServicesResource {
    private static final Logger log = LoggerFactory.getLogger(OtjServicesResource.class);

    private final UserStateStore userStateStore;
    private final UserRepository userRepository;
    private final ActivityLogRepository activityLogRepository;
    private final LlmService llmService;
    private final Provider<OtjDriver> otjDriverProvider;
    private final Provider<AzureIdDriver> azureIdDriverProvider;

    @Inject
    public OtjServicesResource(UserStateStore userStateStore, UserRepository userRepository,
                               ActivityLogRepository activityLogRepository,
                               LlmService llmService,
                               Provider<OtjDriver> otjDriverProvider,
                               Provider<AzureIdDriver> azureIdDriverProvider) {
        this.userStateStore = userStateStore;
        this.userRepository = userRepository;
        this.activityLogRepository = activityLogRepository;
        this.llmService = llmService;
        this.otjDriverProvider = otjDriverProvider;
        this.azureIdDriverProvider = azureIdDriverProvider;
    }

    /**
     * Launches a Firefox browser, navigates to the OA login page, fills credentials,
     * and blocks at the OTP field. The browser stays open so the caller can supply the MFA token.
     * MFA tokens expire in ~30 s, so the browser session is kept alive in {@link com.github.grepHammerspace.stateStore.UserStateStore}
     * rather than being recreated on each request.
     */
    @GET
    @Path("/prepare-browser")
    public Response prepareBrowser(@Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "prepare-browser");
        try {
            User user = userRepository.findByUserId(userId);
            UserState userState = userStateStore.getStateForUser(userId);
            OtjDriver driver = otjDriverProvider.get();
            driver.prepare(user.username(), user.password());
            userState.setDriver(driver);
        } catch (IOException e) {
            return Response.status(Response.Status.BAD_GATEWAY).entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }

        return Response.ok("{\"status\": \"ready\"}").build();
    }

    @POST
    @Path("/register")
    public Response register(@Valid RegisterRequest body, @Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "register");
        log.info("Registering user {} with learnerId {}", userId, body.learnerId());
        userRepository.save(new User(userId, body.username().strip(), body.password(), body.learnerId().strip()));
        return Response.status(Response.Status.CREATED).build();
    }

    @POST
    @Path("/log-activities")
    public Response logActivtiesWithLlmHelp(@Valid ActivityLogRequest body, @Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "log-activities");

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
            String msg = "No registered user found for this account. " +
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
    public Response deleteLastRow(@Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "delete-last-row");

        boolean deleted = activityLogRepository.deleteLastActivityLog(userId);
        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"No unposted activity log found for this user.\"}").build();
        }
        return Response.ok("{\"status\": \"ok\"}").build();
    }

    @DELETE
    @Path("/reset-notes")
    public Response resetNotes(@Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "reset-notes");

        userRepository.clearLastContent(userId);
        return Response.ok("{\"status\": \"ok\"}").build();
    }

    @POST
    @Path("/submit-with-mfa")
    public Response useMfaCodeToSubmitUnSubmittedOTJs(@Valid SubmitWithMfaRequest body, @Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "submit-with-mfa");
        log.info("Received submit-with-mfa request from user {} and mfa code {}", userId, body.mfaCode());
        Driver driver = userStateStore.getStateForUser(userId).getDriver();
        if (driver == null) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"No prepared browser session found for user, call /prepare-browser first\"}").build();
        }

        try {
            driver.completeMfa(body.mfaCode());
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (IOException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }

        OtjSubmitResult result = driver.submitPendingOtjs(userId);

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

    /**
     * Logs in to OneAdvanced's cloud-education platform via the QMUL Azure AD path.
     * Sends a Microsoft Authenticator push notification to the user's phone and returns.
     * Call {@code POST /azure-id/complete} once the user has approved the push.
     */
    @GET
    @Path("/azure-id/prepare")
    public Response azureIdPrepare(@Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "azure-id/prepare");
        try {
            User user = userRepository.findByUserId(userId);
            if (user == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\": \"No registered user found — call POST /otj-services/register first\"}")
                        .build();
            }
            AzureIdDriver driver = azureIdDriverProvider.get();
            PrepareResult result = driver.prepare(user.username(), user.password());
            UserState userState = userStateStore.getStateForUser(userId);
            userState.setDriver(driver);
            if (!result.requiresMfa()) {
                userState.setLoginFuture(CompletableFuture.completedFuture(null));
                return Response.ok("{\"status\": \"login_complete\", \"message\": \"" + result.userMessage() + "\"}").build();
            }
            CompletableFuture<Void> loginFuture = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    driver.completeMfa("");
                    loginFuture.complete(null);
                } catch (Exception e) {
                    loginFuture.completeExceptionally(e);
                }
            });
            userState.setLoginFuture(loginFuture);
            String challengeField = result.status() == PrepareResult.Status.MFA_NUMBER_MATCH
                    ? ", \"challengeNumber\": " + result.challengeNumber()
                    : "";
            return Response.ok("{\"status\": \"push_sent\", \"message\": \"" + result.userMessage() + "\"" + challengeField + "}").build();
        } catch (IOException e) {
            log.warn("Azure ID prepare failed: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    /**
     * Waits for the background EndAuth poll (started by /azure-id/prepare) to complete.
     * Returns as soon as the user approves in Microsoft Authenticator.
     */
    @GET
    @Path("/azure-id/complete")
    public Response azureIdComplete(@Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "azure-id/complete");
        UserState userState = userStateStore.getStateForUser(userId);
        CompletableFuture<Void> loginFuture = userState.getLoginFuture();
        Driver driver = userState.getDriver();
        if (loginFuture == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"No active session — call /azure-id/prepare first\"}").build();
        }

        try {
            loginFuture.get(125, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return Response.status(408)
                    .entity("{\"error\": \"Timed out waiting for Microsoft Authenticator approval\"}").build();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.warn("Azure ID complete failed: {}", cause.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + cause.getMessage() + "\"}").build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Interrupted while waiting for approval\"}").build();
        }

        OtjSubmitResult result = driver.submitPendingOtjs(userId);

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

    /**
     * Reads the authenticated userId from the {@link SecurityContext} set by
     * {@link com.github.grepHammerspace.auth.AuthenticationFilter} and lazily initialises
     * per-user state if it doesn't exist yet.
     */
    private String resolveUserState(SecurityContext sc) {
        String userId = sc.getUserPrincipal().getName();
        userStateStore.createUserState(userId);
        return userId;
    }
}
