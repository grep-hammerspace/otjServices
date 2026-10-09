package com.github.grepHammerspace.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.grepHammerspace.api.dto.ActivityLogRequest;
import com.github.grepHammerspace.api.dto.ActivityLogResponse;
import com.github.grepHammerspace.api.dto.ApiError;
import com.github.grepHammerspace.api.dto.CryptoError;
import com.github.grepHammerspace.api.dto.OneAdvancedCredentials;
import com.github.grepHammerspace.api.dto.PendingActivity;
import com.github.grepHammerspace.api.dto.PendingResponse;
import com.github.grepHammerspace.api.dto.PrepareResponse;
import com.github.grepHammerspace.api.dto.SealedEnvelope;
import com.github.grepHammerspace.api.dto.SubmitResponse;
import com.github.grepHammerspace.api.dto.SubmitWithMfaRequest;
import com.github.grepHammerspace.api.dto.UpdateActivityRequest;
import com.github.grepHammerspace.auth.Authenticated;
import com.github.grepHammerspace.crypto.CredentialKeyRing;
import com.github.grepHammerspace.crypto.SealedCredentialsException;
import com.github.grepHammerspace.db.ActivityLogRepository;
import com.github.grepHammerspace.db.UserRepository;
import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.db.model.ActivityRules;
import com.github.grepHammerspace.db.model.User;
import com.github.grepHammerspace.llm.LlmResult;
import com.github.grepHammerspace.llm.LlmService;
import com.github.grepHammerspace.llm.exception.LlmRateLimitException;
import com.github.grepHammerspace.quota.LlmQuotaService;
import com.github.grepHammerspace.stateStore.LoginFlow;
import com.github.grepHammerspace.stateStore.LoginSession;
import com.github.grepHammerspace.stateStore.LoginSessions;
import com.github.grepHammerspace.web.AzurePush;
import com.github.grepHammerspace.web.Driver;
import com.github.grepHammerspace.web.Keycloak;
import com.github.grepHammerspace.web.LoginChainException;
import com.github.grepHammerspace.web.MicrosoftAccountException;
import com.github.grepHammerspace.web.SecurityInfoRequiredException;
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

import javax.inject.Inject;
import javax.inject.Provider;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Path("/otj-services")
@Produces("application/json")
@Consumes("application/json")
@Authenticated
public class OtjServicesResource {
    private static final Logger log = LoggerFactory.getLogger(OtjServicesResource.class);

    private static final ObjectMapper CREDENTIALS_JSON = new ObjectMapper();

    // Its futures interrupt on cancel, so replacing or dropping a session stops the Microsoft poll.
    private static final ExecutorService LOGIN_POLLS = Executors.newVirtualThreadPerTaskExecutor();

    private static final int UNPROCESSABLE_ENTITY = 422;

    // Fixed messages only: a driver's or the LLM's own message can carry login-chain URLs with the
    // username in them, or upstream detail.
    private static final ApiError LOGIN_FAILED = new ApiError(
            "Could not sign in to OneAdvanced. Check the username and password and try again.");
    private static final ApiError SECURITY_INFO_REQUIRED = new ApiError(
            "Microsoft wants you to add or confirm your security info before it will sign you in. "
                    + "Sign in at https://mysignins.microsoft.com/security-info, follow its prompts, "
                    + "then submit again.");
    private static final ApiError USES_MICROSOFT = new ApiError(
            "This account signs in with Microsoft, not a OneAdvanced code. "
                    + "Choose Microsoft on the Submit screen and try again.");
    private static final ApiError ONEADVANCED_UNAVAILABLE = new ApiError(
            "Could not complete the OneAdvanced sign-in. Try again in a moment.");
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
    private static final ApiError NO_ACCOUNT = new ApiError(
            "No account found for this session. Sign up again.");
    private static final ApiError CONTENT_MISSING = new ApiError(
            "The 'content' field is missing or empty.");
    private static final ApiError ENTRY_TOO_LONG = new ApiError(
            "Each entry must be " + ActivityRules.MAX_IMPACT_CHARS + " characters or fewer.");
    private static final ApiError QUOTA_EXHAUSTED = new ApiError(
            "Daily limit of " + LlmQuotaService.DAILY_LIMIT
                    + " AI requests reached. It resets at midnight UTC.");
    private static final ApiError LLM_BUSY = new ApiError(
            "The AI service is busy. Wait a minute and try again.");
    private static final ApiError LLM_FAILED = new ApiError(
            "The AI could not process that. Try again, or send fewer lines at once.");
    private static final ApiError BODY_MISSING = new ApiError("A JSON body is required.");
    // One body for all three misses, so a caller can't confirm that an id it doesn't own exists.
    private static final ApiError NO_SUCH_PENDING = new ApiError(
            "No unposted activity log with that id for this user.");

    private final LoginSessions loginSessions;
    private final UserRepository userRepository;
    private final ActivityLogRepository activityLogRepository;
    private final LlmService llmService;
    private final LlmQuotaService llmQuotaService;
    private final CredentialKeyRing credentialKeyRing;
    private final Provider<Driver> keycloakDriverProvider;
    private final Provider<Driver> azurePushDriverProvider;

    @Inject
    public OtjServicesResource(LoginSessions loginSessions, UserRepository userRepository,
                               ActivityLogRepository activityLogRepository,
                               LlmService llmService,
                               LlmQuotaService llmQuotaService,
                               CredentialKeyRing credentialKeyRing,
                               @Keycloak Provider<Driver> keycloakDriverProvider,
                               @AzurePush Provider<Driver> azurePushDriverProvider) {
        this.loginSessions = loginSessions;
        this.userRepository = userRepository;
        this.activityLogRepository = activityLogRepository;
        this.llmService = llmService;
        this.llmQuotaService = llmQuotaService;
        this.credentialKeyRing = credentialKeyRing;
        this.keycloakDriverProvider = keycloakDriverProvider;
        this.azurePushDriverProvider = azurePushDriverProvider;
    }

    @POST
    @Path("/prepare-browser")
    public Response prepareBrowser(SealedEnvelope body, @Context SecurityContext sc) {
        return prepare(userId(sc), body, LoginFlow.KEYCLOAK_TOTP);
    }

    @POST
    @Path("/log-activities")
    public Response logActivtiesWithLlmHelp(ActivityLogRequest body, @Context SecurityContext sc) {
        String userId = userId(sc);

        String content = body == null || body.content() == null ? "" : body.content().strip();
        if (content.isEmpty()) {
            return error(Response.Status.BAD_REQUEST, CONTENT_MISSING);
        }
        if (content.lines().anyMatch(line -> line.strip().length() > ActivityRules.MAX_IMPACT_CHARS)) {
            return error(Response.Status.BAD_REQUEST, ENTRY_TOO_LONG);
        }

        User user = userRepository.findByUserId(userId);
        if (user == null) {
            log.warn("User {} not found in repository", userId);
            return error(Response.Status.BAD_REQUEST, NO_ACCOUNT);
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
            // No Retry-After: its absence is how a client tells this 429 from the quota one.
            return Response.status(429).entity(LLM_BUSY).build();
        } catch (RuntimeException e) {
            // LlmServiceImpl has already logged the detail.
            log.warn("LLM call failed for user {} — {}", userId, e.getClass().getSimpleName());
            return error(Response.Status.INTERNAL_SERVER_ERROR, LLM_FAILED);
        }

        List<PendingActivity> saved = result.ok().stream()
                .map(entry -> PendingActivity.from(activityLogRepository.saveActivityLog(entry)))
                .toList();

        log.info("Request complete — {} row(s) written, {} error(s)", saved.size(), result.errors().size());

        return Response.ok(new ActivityLogResponse(
                "ok",
                saved.size(),
                saved,
                result.errors().isEmpty() ? null : result.errors()
        )).build();
    }

    @GET
    @Path("/pending")
    public Response getPending(@Context SecurityContext sc) {
        List<ActivityLog> rows = activityLogRepository.findUnpostedNewestFirst(userId(sc));
        return Response.ok(PendingResponse.from(rows)).build();
    }

    @DELETE
    @Path("/pending/{id}")
    public Response deletePending(@PathParam("id") String id, @Context SecurityContext sc) {
        if (!ObjectId.isValid(id)) {
            return error(Response.Status.BAD_REQUEST, invalidId(id));
        }
        if (!activityLogRepository.deleteUnpostedById(userId(sc), new ObjectId(id))) {
            return error(Response.Status.NOT_FOUND, NO_SUCH_PENDING);
        }
        return Response.noContent().build();
    }

    @PUT
    @Path("/pending/{id}")
    public Response updatePending(@PathParam("id") String id, UpdateActivityRequest body,
                                  @Context SecurityContext sc) {
        String userId = userId(sc);

        if (!ObjectId.isValid(id)) {
            return error(Response.Status.BAD_REQUEST, invalidId(id));
        }
        if (body == null) {
            return error(Response.Status.BAD_REQUEST, BODY_MISSING);
        }

        UpdateActivityRequest edit = body.normalised();
        String problem = edit.validationError();
        if (problem != null) {
            log.warn("Rejected edit of {} for user {}: {}", id, userId, problem);
            return error(Response.Status.BAD_REQUEST, new ApiError(problem));
        }

        ActivityLog updated = activityLogRepository.updateUnpostedById(userId, new ObjectId(id),
                edit.activityDate(), edit.activityTime(), edit.hours(), edit.minutes(),
                edit.activityImpact());
        if (updated == null) {
            return error(Response.Status.NOT_FOUND, NO_SUCH_PENDING);
        }
        return Response.ok(PendingActivity.from(updated)).build();
    }

    @POST
    @Path("/submit-with-mfa")
    public Response useMfaCodeToSubmitUnSubmittedOTJs(SubmitWithMfaRequest body, @Context SecurityContext sc) {
        String userId = userId(sc);
        // The code itself stays out of every log: it is a live credential.

        if (body == null || isBlank(body.mfaCode())) {
            return error(Response.Status.BAD_REQUEST, MFA_CODE_MISSING);
        }

        LoginSession session = loginSessions.get(userId);
        if (session == null) {
            return error(Response.Status.CONFLICT, NO_SESSION);
        }
        // Without this, an Azure session would be accepted here and start a second Microsoft poll.
        if (session.flow() != LoginFlow.KEYCLOAK_TOTP) {
            return error(Response.Status.CONFLICT, WRONG_FLOW_AZURE);
        }

        try {
            session.driver().completeMfa(body.mfaCode());
        } catch (IllegalStateException | IOException e) {
            log.warn("MFA completion failed for user {} — {}", userId, failureReason(e));
            return error(Response.Status.BAD_REQUEST, MFA_REJECTED);
        }

        return submitPending(userId, session);
    }

    @POST
    @Path("/azure-id/prepare")
    public Response azureIdPrepare(SealedEnvelope body, @Context SecurityContext sc) {
        return prepare(userId(sc), body, LoginFlow.AZURE_PUSH);
    }

    private Response prepare(String userId, SealedEnvelope envelope, LoginFlow flow) {
        OneAdvancedCredentials body;
        try {
            body = unseal(envelope);
        } catch (SealedCredentialsException e) {
            // The reason code only, never the envelope's bytes.
            log.warn("Rejected sealed credentials from user {} on {} — {}",
                    userId, flow, e.reason().code());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new CryptoError(e.reason().message(), e.reason().code())).build();
        }

        if (isBlank(body.username()) || isBlank(body.password())) {
            return error(Response.Status.BAD_REQUEST, CREDENTIALS_MISSING);
        }

        // Before the login, so an Azure user isn't made to approve a push with nothing to post
        // under.
        User user = userRepository.findByUserId(userId);
        if (user == null || isBlank(user.learnerId())) {
            return error(Response.Status.CONFLICT, NO_LEARNER_ID);
        }

        Driver driver = flow == LoginFlow.KEYCLOAK_TOTP
                ? keycloakDriverProvider.get()
                : azurePushDriverProvider.get();

        // Never 401: that status means "your bearer token is dead" and the app signs out on it. A
        // LoginChainException is the chain not going where a good login goes, which is what a wrong
        // password looks like from here; anything else is the network or a page we failed to parse,
        // and blaming the password for that sends someone to retype one that was fine.
        PrepareResult result;
        try {
            result = driver.prepare(body.username().strip(), body.password());
        } catch (SecurityInfoRequiredException e) {
            log.warn("Prepare stopped for user {} on {} — {}", userId, flow, failureReason(e));
            return error(Response.Status.CONFLICT, SECURITY_INFO_REQUIRED);
        } catch (MicrosoftAccountException e) {
            log.warn("Prepare stopped for user {} on {} — {}", userId, flow, failureReason(e));
            return error(Response.Status.CONFLICT, USES_MICROSOFT);
        } catch (LoginChainException e) {
            log.warn("Prepare failed for user {} on {} — {}", userId, flow, failureReason(e));
            return error(UNPROCESSABLE_ENTITY, LOGIN_FAILED);
        } catch (IOException | RuntimeException e) {
            log.warn("Prepare failed for user {} on {} — {}", userId, flow, failureReason(e));
            return error(Response.Status.BAD_GATEWAY, ONEADVANCED_UNAVAILABLE);
        }

        // A push is approved on the phone, not here: a background poll finishes the login, and
        // azure-id/complete waits on it.
        Future<?> login = result.status() == PrepareResult.Status.PUSH_SENT
                ? LOGIN_POLLS.submit(() -> { driver.completeMfa(""); return null; })
                : CompletableFuture.completedFuture(null);
        loginSessions.put(userId, new LoginSession(flow, driver, login, Instant.now()));

        return Response.ok(PrepareResponse.from(result)).build();
    }

    @GET
    @Path("/azure-id/complete")
    public Response azureIdComplete(@Context SecurityContext sc) {
        String userId = userId(sc);

        LoginSession session = loginSessions.get(userId);
        if (session == null) {
            return error(Response.Status.CONFLICT, NO_SESSION);
        }
        if (session.flow() != LoginFlow.AZURE_PUSH) {
            return error(Response.Status.CONFLICT, WRONG_FLOW_OTP);
        }

        try {
            // Past the driver's 40 x 3 s poll budget, so the poller reports its own timeout.
            session.login().get(125, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return Response.status(408).entity(MFA_TIMED_OUT).build();
        } catch (ExecutionException e) {
            log.warn("Azure ID complete failed for user {} — {}", userId, failureReason(e.getCause()));
            // The push was approved and Microsoft then asked for security info: not a bad password,
            // and something only the user can fix, at Microsoft.
            if (e.getCause() instanceof SecurityInfoRequiredException) {
                return error(Response.Status.CONFLICT, SECURITY_INFO_REQUIRED);
            }
            return error(Response.Status.BAD_REQUEST, LOGIN_FAILED);
        } catch (CancellationException e) {
            return error(Response.Status.CONFLICT, NO_SESSION);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(Response.Status.INTERNAL_SERVER_ERROR,
                    new ApiError("Interrupted while waiting for approval."));
        }

        return submitPending(userId, session);
    }

    // Read from the account at submit time, so a PATCH /auth/me correction reaches rows already
    // queued.
    private Response submitPending(String userId, LoginSession session) {
        User user = userRepository.findByUserId(userId);
        if (user == null || isBlank(user.learnerId())) {
            return error(Response.Status.CONFLICT, NO_LEARNER_ID);
        }

        OtjSubmitResult result;
        try {
            result = session.driver().submitPendingOtjs(userId, user.learnerId());
        } finally {
            // Dropped once spent: it holds live OneAdvanced cookies.
            loginSessions.remove(userId, session);
        }

        int posted = result.posted().size();
        int failed = result.failed().size();
        if (failed == 0) {
            return Response.ok(new SubmitResponse(posted == 0 ? "nothing_to_post" : "ok", posted, 0)).build();
        }
        if (posted == 0) {
            return Response.status(502).entity(new SubmitResponse("all_failed", 0, failed)).build();
        }
        return Response.status(207).entity(new SubmitResponse("partial", posted, failed)).build();
    }

    // Unparseable plaintext reports as a malformed envelope: a distinct message would tell a prober
    // their guess decrypted.
    private OneAdvancedCredentials unseal(SealedEnvelope envelope) throws SealedCredentialsException {
        byte[] plaintext = credentialKeyRing.open(envelope);
        try {
            OneAdvancedCredentials credentials =
                    CREDENTIALS_JSON.readValue(plaintext, OneAdvancedCredentials.class);
            credentialKeyRing.checkFreshness(credentials.iat());
            return credentials;
        } catch (IOException e) {
            throw new SealedCredentialsException(
                    SealedCredentialsException.Reason.MALFORMED_ENVELOPE);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static ApiError invalidId(String id) {
        return new ApiError("'" + id + "' is not a valid activity id.");
    }

    // For log lines only, never a response body. A LoginChainException's message is built from
    // redacted URLs and Microsoft's page ids, so it's logged; any other message is the leak channel,
    // so only its type is.
    private static String failureReason(Throwable e) {
        if (e == null) return "unknown";
        return e instanceof LoginChainException
                ? e.getClass().getSimpleName() + ": " + e.getMessage()
                : e.getClass().getSimpleName();
    }

    private static Response error(Response.Status status, ApiError error) {
        return Response.status(status).entity(error).build();
    }

    // For statuses Response.Status has no constant for.
    private static Response error(int status, ApiError error) {
        return Response.status(status).entity(error).build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String userId(SecurityContext sc) {
        return sc.getUserPrincipal().getName();
    }
}
