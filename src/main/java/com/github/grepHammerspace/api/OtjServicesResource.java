package com.github.grepHammerspace.api;

import com.github.grepHammerspace.api.dto.ActivityLogRequest;
import com.github.grepHammerspace.api.dto.ActivityLogResponse;
import com.github.grepHammerspace.api.dto.ApiError;
import com.github.grepHammerspace.api.dto.OneAdvancedCredentials;
import com.github.grepHammerspace.api.dto.PendingActivity;
import com.github.grepHammerspace.api.dto.PendingResponse;
import com.github.grepHammerspace.api.dto.PrepareResponse;
import com.github.grepHammerspace.api.dto.SubmitResponse;
import com.github.grepHammerspace.api.dto.SubmitWithMfaRequest;
import com.github.grepHammerspace.api.dto.UpdateActivityRequest;
import com.github.grepHammerspace.auth.Authenticated;
import com.github.grepHammerspace.db.ActivityLogRepository;
import com.github.grepHammerspace.db.UserRepository;
import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.db.model.User;
import com.github.grepHammerspace.llm.exception.LlmException;
import com.github.grepHammerspace.llm.exception.LlmRateLimitException;
import com.github.grepHammerspace.llm.LlmResult;
import com.github.grepHammerspace.llm.LlmService;
import com.github.grepHammerspace.quota.LlmQuotaService;
import com.github.grepHammerspace.stateStore.LoginFlow;
import com.github.grepHammerspace.stateStore.LoginSession;
import com.github.grepHammerspace.stateStore.UserState;
import com.github.grepHammerspace.stateStore.UserStateStore;
import com.github.grepHammerspace.web.AzurePush;
import com.github.grepHammerspace.web.Driver;
import com.github.grepHammerspace.web.Keycloak;
import com.github.grepHammerspace.web.OtjSubmitResult;
import com.github.grepHammerspace.web.PrepareResult;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.validation.Valid;
import javax.inject.Inject;
import javax.inject.Provider;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Path("/otj-services")
@Produces("application/json")
@Consumes("application/json")
@Authenticated
public class OtjServicesResource {
    private static final Logger log = LoggerFactory.getLogger(OtjServicesResource.class);

    // A driver's own message is never forwarded: it carries login-chain URLs with the username in
    // them.
    private static final ApiError LOGIN_FAILED = new ApiError(
            "Could not sign in to OneAdvanced. Check the username and password and try again.");
    private static final ApiError CREDENTIALS_MISSING = new ApiError(
            "Both 'username' and 'password' are required.");
    private static final ApiError MFA_CODE_MISSING = new ApiError(
            "The 'mfaCode' field is missing or empty.");
    private static final ApiError MFA_REJECTED = new ApiError(
            "That code was not accepted. Use a fresh one and try again.");
    private static final ApiError MFA_TIMED_OUT = new ApiError(
            "Timed out waiting for Microsoft Authenticator approval.");
    private static final ApiError NO_SESSION = new ApiError(
            "No prepared session — call a prepare endpoint first.");
    private static final ApiError WRONG_FLOW_AZURE = new ApiError(
            "This session uses Microsoft Authenticator — call GET /otj-services/azure-id/complete instead.");
    private static final ApiError WRONG_FLOW_OTP = new ApiError(
            "This session expects a typed code — call POST /otj-services/submit-with-mfa instead.");
    private static final ApiError NO_LEARNER_ID = new ApiError(
            "No learner ID on this account. Set one via PATCH /auth/me before submitting.");
    private static final ApiError QUOTA_EXHAUSTED = new ApiError(
            "Daily limit of " + LlmQuotaService.DAILY_LIMIT
                    + " AI requests reached. It resets at midnight UTC.");

    private final UserStateStore userStateStore;
    private final UserRepository userRepository;
    private final ActivityLogRepository activityLogRepository;
    private final LlmService llmService;
    private final LlmQuotaService llmQuotaService;
    private final Provider<Driver> keycloakDriverProvider;
    private final Provider<Driver> azurePushDriverProvider;

    @Inject
    public OtjServicesResource(UserStateStore userStateStore, UserRepository userRepository,
                               ActivityLogRepository activityLogRepository,
                               LlmService llmService,
                               LlmQuotaService llmQuotaService,
                               @Keycloak Provider<Driver> keycloakDriverProvider,
                               @AzurePush Provider<Driver> azurePushDriverProvider) {
        this.userStateStore = userStateStore;
        this.userRepository = userRepository;
        this.activityLogRepository = activityLogRepository;
        this.llmService = llmService;
        this.llmQuotaService = llmQuotaService;
        this.keycloakDriverProvider = keycloakDriverProvider;
        this.azurePushDriverProvider = azurePushDriverProvider;
    }

    @POST
    @Path("/prepare-browser")
    public Response prepareBrowser(OneAdvancedCredentials body, @Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "prepare-browser");
        return prepare(userId, body, LoginFlow.KEYCLOAK_TOTP);
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
            String msg = "No account found for this session. Sign up again.";
            log.warn("User {} not found in repository", userId);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + msg + "\"}").build();
        }

        // After the 400s so a malformed request never spends quota; before the call so a refused
        // one never reaches the model.
        if (!llmQuotaService.tryConsume(userId)) {
            log.info("Rejected log-activities for user {} — daily LLM quota reached", userId);
            return Response.status(429)
                    .header(HttpHeaders.RETRY_AFTER, llmQuotaService.secondsUntilReset())
                    .entity(QUOTA_EXHAUSTED)
                    .build();
        }

        log.info("Calling LLM with {} chars", content.length());

        LlmResult result;
        try {
            result = llmService.parseActivities(content, LocalDate.now().toString(), userId, user.learnerId());
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

        List<PendingActivity> saved = new ArrayList<>();
        for (ActivityLog entry : result.ok()) {
            saved.add(PendingActivity.from(activityLogRepository.saveActivityLog(entry)));
        }

        log.info("Request complete — {} row(s) written, {} error(s)", saved.size(), result.errors().size());

        ActivityLogResponse responseBody = new ActivityLogResponse(
                "ok",
                saved.size(),
                saved,
                result.errors().isEmpty() ? null : result.errors()
        );

        return Response.ok(responseBody).build();
    }

    @GET
    @Path("/pending")
    public Response getPending(@Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "pending");

        List<ActivityLog> rows = activityLogRepository.findUnpostedNewestFirst(userId);
        return Response.ok(PendingResponse.from(rows)).build();
    }

    @DELETE
    @Path("/pending/{id}")
    public Response deletePending(@PathParam("id") String id, @Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {} for {}", userId, "delete-pending", id);

        if (!ObjectId.isValid(id)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"'" + id + "' is not a valid activity id.\"}").build();
        }

        if (!activityLogRepository.deleteUnpostedById(userId, new ObjectId(id))) {
            // One body for all three misses, so a caller can't confirm that an id it doesn't own
            // exists.
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"No unposted activity log with that id for this user.\"}").build();
        }
        return Response.noContent().build();
    }

    @PUT
    @Path("/pending/{id}")
    public Response updatePending(@PathParam("id") String id, UpdateActivityRequest body,
                                  @Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {} for {}", userId, "update-pending", id);

        if (!ObjectId.isValid(id)) {
            return jsonError(Response.Status.BAD_REQUEST, "'" + id + "' is not a valid activity id.");
        }
        if (body == null) {
            return jsonError(Response.Status.BAD_REQUEST, "A JSON body is required.");
        }

        UpdateActivityRequest edit = body.normalised();
        String problem = edit.validationError();
        if (problem != null) {
            log.warn("Rejected edit of {} for user {}: {}", id, userId, problem);
            return jsonError(Response.Status.BAD_REQUEST, problem);
        }

        ActivityLog updated = activityLogRepository.updateUnpostedById(userId, new ObjectId(id),
                edit.activityDate(), edit.activityTime(), edit.hours(), edit.minutes(),
                edit.activityImpact());
        if (updated == null) {
            // Same body as deletePending's: the three misses must stay indistinguishable.
            return jsonError(Response.Status.NOT_FOUND,
                    "No unposted activity log with that id for this user.");
        }
        return Response.ok(PendingActivity.from(updated)).build();
    }

    @POST
    @Path("/submit-with-mfa")
    public Response useMfaCodeToSubmitUnSubmittedOTJs(SubmitWithMfaRequest body, @Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "submit-with-mfa");
        // The code itself stays out of this log: it is a live credential.

        if (body == null || isBlank(body.mfaCode())) {
            return Response.status(Response.Status.BAD_REQUEST).entity(MFA_CODE_MISSING).build();
        }

        LoginSession session = userStateStore.getStateForUser(userId).getSession();
        if (session == null) {
            return Response.status(Response.Status.CONFLICT).entity(NO_SESSION).build();
        }
        // Without this, an Azure session would be accepted here and start a second Microsoft poll.
        if (session.flow() != LoginFlow.KEYCLOAK_TOTP) {
            return Response.status(Response.Status.CONFLICT).entity(WRONG_FLOW_AZURE).build();
        }

        try {
            session.driver().completeMfa(body.mfaCode());
        } catch (IllegalStateException | IOException e) {
            log.warn("MFA completion failed for user {} — {}", userId, e.getClass().getSimpleName());
            return Response.status(Response.Status.BAD_REQUEST).entity(MFA_REJECTED).build();
        }

        return submitPending(userId, session);
    }

    @POST
    @Path("/azure-id/prepare")
    public Response azureIdPrepare(OneAdvancedCredentials body, @Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "azure-id/prepare");
        return prepare(userId, body, LoginFlow.AZURE_PUSH);
    }

    private Response prepare(String userId, OneAdvancedCredentials body, LoginFlow flow) {
        if (body == null || isBlank(body.username()) || isBlank(body.password())) {
            return Response.status(Response.Status.BAD_REQUEST).entity(CREDENTIALS_MISSING).build();
        }

        // Before the login, so an Azure user isn't made to approve a push with nothing to post
        // under.
        User user = userRepository.findByUserId(userId);
        if (user == null || isBlank(user.learnerId())) {
            return Response.status(Response.Status.CONFLICT).entity(NO_LEARNER_ID).build();
        }

        Driver driver = flow == LoginFlow.KEYCLOAK_TOTP
                ? keycloakDriverProvider.get()
                : azurePushDriverProvider.get();

        PrepareResult result;
        try {
            result = driver.prepare(body.username().strip(), body.password());
        } catch (IOException | RuntimeException e) {
            // Type only. The message is the leak channel.
            log.warn("Prepare failed for user {} on {} — {}", userId, flow, e.getClass().getSimpleName());
            return Response.status(Response.Status.UNAUTHORIZED).entity(LOGIN_FAILED).build();
        }

        UserState userState = userStateStore.getStateForUser(userId);

        if (!result.requiresMfa()) {
            userState.setSession(new LoginSession(
                    flow, driver, CompletableFuture.completedFuture(null), Instant.now()));
            return Response.ok(PrepareResponse.loginComplete(result.userMessage())).build();
        }

        if (flow == LoginFlow.KEYCLOAK_TOTP) {
            userState.setSession(new LoginSession(
                    flow, driver, CompletableFuture.completedFuture(null), Instant.now()));
            return Response.ok(PrepareResponse.otpRequired(
                    "Enter the current code from your authenticator app.")).build();
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
        userState.setSession(new LoginSession(flow, driver, loginFuture, Instant.now()));

        Integer challengeNumber = result.status() == PrepareResult.Status.MFA_NUMBER_MATCH
                ? result.challengeNumber()
                : null;
        return Response.ok(PrepareResponse.pushSent(result.userMessage(), challengeNumber)).build();
    }

    @GET
    @Path("/azure-id/complete")
    public Response azureIdComplete(@Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "azure-id/complete");

        LoginSession session = userStateStore.getStateForUser(userId).getSession();
        if (session == null) {
            return Response.status(Response.Status.CONFLICT).entity(NO_SESSION).build();
        }
        if (session.flow() != LoginFlow.AZURE_PUSH) {
            return Response.status(Response.Status.CONFLICT).entity(WRONG_FLOW_OTP).build();
        }

        try {
            // Past the driver's 40 x 3 s poll budget, so the poller reports its own timeout.
            session.future().get(125, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return Response.status(408).entity(MFA_TIMED_OUT).build();
        } catch (ExecutionException e) {
            log.warn("Azure ID complete failed for user {} — {}", userId,
                    e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
            return Response.status(Response.Status.BAD_REQUEST).entity(LOGIN_FAILED).build();
        } catch (CancellationException e) {
            return Response.status(Response.Status.CONFLICT).entity(NO_SESSION).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ApiError("Interrupted while waiting for approval.")).build();
        }

        return submitPending(userId, session);
    }

    // Read from the account at submit time, so a PATCH /auth/me correction reaches rows already
    // queued.
    private Response submitPending(String userId, LoginSession session) {
        User user = userRepository.findByUserId(userId);
        if (user == null || isBlank(user.learnerId())) {
            return Response.status(Response.Status.CONFLICT).entity(NO_LEARNER_ID).build();
        }

        OtjSubmitResult result;
        try {
            result = session.driver().submitPendingOtjs(userId, user.learnerId());
        } finally {
            // Dropped once spent: it holds live OneAdvanced cookies.
            userStateStore.getStateForUser(userId).clearSession();
        }

        if (result.nothingToPost()) {
            return Response.ok(new SubmitResponse("nothing_to_post", 0, 0)).build();
        }
        if (result.allPosted()) {
            return Response.ok(new SubmitResponse("ok", result.posted().size(), 0)).build();
        }
        if (result.allFailed()) {
            return Response.status(502)
                    .entity(new SubmitResponse("all_failed", 0, result.failed().size())).build();
        }
        return Response.status(207)
                .entity(new SubmitResponse("partial", result.posted().size(), result.failed().size())).build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // Escaped: validation messages quote the caller's input back.
    private static Response jsonError(Response.Status status, String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return Response.status(status).entity("{\"error\": \"" + escaped + "\"}").build();
    }

    private String resolveUserState(SecurityContext sc) {
        String userId = sc.getUserPrincipal().getName();
        userStateStore.createUserState(userId);
        return userId;
    }
}
