package com.github.grepHammerspace.api;

import com.github.grepHammerspace.api.dto.ActivityLogRequest;
import com.github.grepHammerspace.api.dto.ActivityLogResponse;
import com.github.grepHammerspace.api.dto.ApiError;
import com.github.grepHammerspace.api.dto.OneAdvancedCredentials;
import com.github.grepHammerspace.api.dto.PendingActivity;
import com.github.grepHammerspace.api.dto.PendingResponse;
import com.github.grepHammerspace.api.dto.PrepareResponse;
import com.github.grepHammerspace.api.dto.RegisterRequest;
import com.github.grepHammerspace.api.dto.SubmitResponse;
import com.github.grepHammerspace.api.dto.SubmitWithMfaRequest;
import com.github.grepHammerspace.auth.Authenticated;
import com.github.grepHammerspace.auth.PasswordHasher;
import com.github.grepHammerspace.db.ActivityLogRepository;
import com.github.grepHammerspace.db.UserRepository;
import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.db.model.User;
import com.github.grepHammerspace.llm.exception.LlmException;
import com.github.grepHammerspace.llm.exception.LlmRateLimitException;
import com.github.grepHammerspace.llm.LlmResult;
import com.github.grepHammerspace.llm.LlmService;
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

    /*
     * One constant per failure, in the style of AuthResource's INVITE_REJECTED /
     * CREDENTIALS_REJECTED. A driver's own exception message is never forwarded: it carries URLs
     * from the login chain, and those chains put the username in a query parameter.
     */
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

    private final UserStateStore userStateStore;
    private final UserRepository userRepository;
    private final ActivityLogRepository activityLogRepository;
    private final LlmService llmService;
    private final PasswordHasher passwordHasher;
    private final Provider<Driver> keycloakDriverProvider;
    private final Provider<Driver> azurePushDriverProvider;

    @Inject
    public OtjServicesResource(UserStateStore userStateStore, UserRepository userRepository,
                               ActivityLogRepository activityLogRepository,
                               LlmService llmService,
                               PasswordHasher passwordHasher,
                               @Keycloak Provider<Driver> keycloakDriverProvider,
                               @AzurePush Provider<Driver> azurePushDriverProvider) {
        this.userStateStore = userStateStore;
        this.userRepository = userRepository;
        this.activityLogRepository = activityLogRepository;
        this.llmService = llmService;
        this.passwordHasher = passwordHasher;
        this.keycloakDriverProvider = keycloakDriverProvider;
        this.azurePushDriverProvider = azurePushDriverProvider;
    }

    /**
     * Signs in to OneAdvanced through Keycloak and stops at the OTP prompt.
     *
     * <p>Takes the user's OneAdvanced credentials in the request body — this service does not
     * store them. They are used for the length of this call and handed straight to the driver.
     *
     * <p>The half-finished session is parked in
     * {@link com.github.grepHammerspace.stateStore.UserStateStore} because a TOTP is only valid
     * for about 30 seconds, which is not long enough to log in from scratch afterwards. Follow
     * with {@code POST /submit-with-mfa}.
     */
    @POST
    @Path("/prepare-browser")
    public Response prepareBrowser(OneAdvancedCredentials body, @Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "prepare-browser");
        return prepare(userId, body, LoginFlow.KEYCLOAK_TOTP);
    }

    @POST
    @Path("/register")
    public Response register(@Valid RegisterRequest body, @Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "register");
        // That a learner ID was set is worth recording; the value is not — see
        // UserRepository.updateLearnerId, which follows the same rule.
        log.info("Registering user {} with a learner ID", userId);
        userRepository.save(new User(userId, body.username().strip(),
                passwordHasher.hash(body.password()), body.learnerId().strip(), Instant.now()));
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

        // Map from the saved row, not the parsed one: only the saved row has an id, which is what
        // lets the client delete a line it just added without refetching /pending.
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

    @GET
    @Path("/pending")
    public Response getPending(@Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "pending");

        // No findByUserId check: unlike log-activities, which needs learnerId, reading needs
        // nothing from the user document. An unregistered caller simply has no rows.
        List<ActivityLog> rows = activityLogRepository.findUnpostedNewestFirst(userId);
        return Response.ok(PendingResponse.from(rows)).build();
    }

    @DELETE
    @Path("/pending/{id}")
    public Response deletePending(@PathParam("id") String id, @Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {} for {}", userId, "delete-pending", id);

        // ObjectId.isValid rather than catching IllegalArgumentException from the constructor:
        // same 400-not-500 outcome, without exception control flow.
        if (!ObjectId.isValid(id)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"'" + id + "' is not a valid activity id.\"}").build();
        }

        if (!activityLogRepository.deleteUnpostedById(userId, new ObjectId(id))) {
            // One body for all three misses — unknown id, someone else's, already posted.
            // Distinguishing them would confirm that an id the caller does not own exists.
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"No unposted activity log with that id for this user.\"}").build();
        }
        return Response.noContent().build();
    }

    /** Completes the Keycloak flow with a typed OTP, then posts everything pending. */
    @POST
    @Path("/submit-with-mfa")
    public Response useMfaCodeToSubmitUnSubmittedOTJs(SubmitWithMfaRequest body, @Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "submit-with-mfa");
        // The code itself is deliberately absent from this log line — it is a live credential
        // for the ~30 s it remains valid.

        if (body == null || isBlank(body.mfaCode())) {
            return Response.status(Response.Status.BAD_REQUEST).entity(MFA_CODE_MISSING).build();
        }

        LoginSession session = userStateStore.getStateForUser(userId).getSession();
        if (session == null) {
            return Response.status(Response.Status.CONFLICT).entity(NO_SESSION).build();
        }
        // Without this check an Azure session would be accepted here, and AzureIdDriver ignores
        // the token it is given — it would start a second background poll racing the first over
        // the same Microsoft flow token.
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

    /**
     * Logs in to OneAdvanced's cloud-education platform via the QMUL Azure AD path.
     *
     * <p>Takes the user's OneAdvanced credentials in the request body — this service does not
     * store them. Sends a Microsoft Authenticator push and returns immediately, along with the
     * number to tap when Microsoft asks for a number match. Approval is then waited on by a
     * background poll, so call {@code GET /azure-id/complete} to pick up the result.
     */
    @POST
    @Path("/azure-id/prepare")
    public Response azureIdPrepare(OneAdvancedCredentials body, @Context SecurityContext sc) {
        String userId = resolveUserState(sc);
        log.info("Received request from user {} to do {}", userId, "azure-id/prepare");
        return prepare(userId, body, LoginFlow.AZURE_PUSH);
    }

    /**
     * The shared body of both prepare endpoints.
     *
     * <p>The credentials never leave this method: they go from the request record into
     * {@link Driver#prepare} and are not stored, logged, or echoed. On failure the driver's own
     * message is dropped in favour of {@link #LOGIN_FAILED}, because that message embeds URLs
     * from the login chain and the chain carries the username in {@code login_hint}.
     */
    private Response prepare(String userId, OneAdvancedCredentials body, LoginFlow flow) {
        if (body == null || isBlank(body.username()) || isBlank(body.password())) {
            return Response.status(Response.Status.BAD_REQUEST).entity(CREDENTIALS_MISSING).build();
        }

        // Checked before the login rather than after: the Azure flow would otherwise have the
        // user approve a push, wait two minutes, and only then discover there is nothing to
        // post under. The learner ID is server-side and is never accepted from the request.
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
            // Nothing to wait on — this flow does not progress until the user posts a code, so
            // the session's future is already complete.
            userState.setSession(new LoginSession(
                    flow, driver, CompletableFuture.completedFuture(null), Instant.now()));
            return Response.ok(PrepareResponse.otpRequired(
                    "Enter the current code from your authenticator app.")).build();
        }

        // Azure: Microsoft is polled in the background so the client is not held open for the
        // full two minutes. /azure-id/complete waits on this future.
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

    /**
     * Waits for the background EndAuth poll (started by /azure-id/prepare) to complete.
     * Returns as soon as the user approves in Microsoft Authenticator.
     */
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
            // Five seconds past the driver's own 40 x 3 s poll budget, so the poller gets to
            // report its own timeout rather than being pre-empted by this one.
            session.future().get(125, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return Response.status(408).entity(MFA_TIMED_OUT).build();
        } catch (ExecutionException e) {
            log.warn("Azure ID complete failed for user {} — {}", userId,
                    e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
            return Response.status(Response.Status.BAD_REQUEST).entity(LOGIN_FAILED).build();
        } catch (CancellationException e) {
            // A newer prepare superseded this session.
            return Response.status(Response.Status.CONFLICT).entity(NO_SESSION).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ApiError("Interrupted while waiting for approval.")).build();
        }

        return submitPending(userId, session);
    }

    /**
     * Posts everything pending over a completed login, then retires the session.
     *
     * <p>The learner ID is read from the account here, at submit time, rather than taken from the
     * rows being posted. That is what makes a correction through {@code PATCH /auth/me} reach
     * activities that were already queued when the typo was noticed.
     */
    private Response submitPending(String userId, LoginSession session) {
        User user = userRepository.findByUserId(userId);
        if (user == null || isBlank(user.learnerId())) {
            return Response.status(Response.Status.CONFLICT).entity(NO_LEARNER_ID).build();
        }

        OtjSubmitResult result;
        try {
            result = session.driver().submitPendingOtjs(userId, user.learnerId());
        } finally {
            // One prepare, one submit. The session holds live OneAdvanced cookies, so it is
            // dropped as soon as it has been spent rather than left for the TTL to collect.
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
