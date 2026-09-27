package com.github.grepHammerspace.api;

import com.github.grepHammerspace.api.dto.ApiError;
import com.github.grepHammerspace.api.dto.SessionRequest;
import com.github.grepHammerspace.api.dto.SignupRequest;
import com.github.grepHammerspace.api.dto.TokenResponse;
import com.github.grepHammerspace.auth.PasswordHasher;
import com.github.grepHammerspace.auth.RateLimiter;
import com.github.grepHammerspace.auth.SessionTokenService;
import com.github.grepHammerspace.db.InviteCodeRepository;
import com.github.grepHammerspace.db.UserRepository;
import com.github.grepHammerspace.db.model.User;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

// No @Authenticated, on purpose: these are the endpoints reached before holding a token.
@Path("/auth")
@Produces("application/json")
@Consumes("application/json")
@Singleton
public class AuthResource {
    private static final Logger log = LoggerFactory.getLogger(AuthResource.class);

    private static final String BEARER_PREFIX = "Bearer ";

    // Verifying against this makes an unknown username cost the same as a wrong password.
    private static final String DUMMY_HASH =
            "$2a$12$KmAXjDu8YKcsMbZIRfgItOfwgykh/XjK3U3DiLkff2tssjtqNtSdm";

    /** One message for invalid, used and expired codes alike — never leak which it was. */
    private static final String INVITE_REJECTED = "{\"error\": \"Invalid, used or expired invite code\"}";
    private static final String CREDENTIALS_REJECTED = "{\"error\": \"Invalid username or password\"}";

    // Successes count too: a flood of valid logins is still a flood.
    private static final int LOGIN_LIMIT = 10;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);

    private static final ApiError TOO_MANY_LOGINS = new ApiError(
            "Too many login attempts for that username. Wait a few minutes and try again.");

    private final UserRepository userRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final SessionTokenService sessionTokenService;
    private final PasswordHasher passwordHasher;
    private final RateLimiter rateLimiter;

    @Inject
    public AuthResource(UserRepository userRepository,
                        InviteCodeRepository inviteCodeRepository,
                        SessionTokenService sessionTokenService,
                        PasswordHasher passwordHasher,
                        RateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.inviteCodeRepository = inviteCodeRepository;
        this.sessionTokenService = sessionTokenService;
        this.passwordHasher = passwordHasher;
        this.rateLimiter = rateLimiter;
    }

    // Claim before insert: the atomic claim decides who wins a contested code. A duplicate username
    // burns the code.
    @POST
    @Path("/signup")
    public Response signup(@Valid SignupRequest body) {
        String userId = UUID.randomUUID().toString();
        String username = body.username().strip();

        if (!inviteCodeRepository.claim(body.inviteCode().strip(), userId)) {
            log.info("Signup rejected for username {} — invite code not claimable", username);
            return Response.status(Response.Status.FORBIDDEN).entity(INVITE_REJECTED).build();
        }

        User user = new User(userId, username, passwordHasher.hash(body.password()),
                body.learnerId().strip(), Instant.now());
        if (!userRepository.insert(user)) {
            log.info("Signup rejected — username {} is already taken", username);
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\": \"That username is already taken\"}").build();
        }

        log.info("Signed up user {} with username {}", userId, username);
        return Response.status(Response.Status.CREATED)
                .entity(new TokenResponse(sessionTokenService.issue(userId))).build();
    }

    @POST
    @Path("/session")
    public Response login(@Valid SessionRequest body) {
        String username = body.username().strip();

        // Before the lookup and the bcrypt verify: they are the cost being shed, and limiting
        // before the lookup means a 429 can't reveal whether the user exists.
        OptionalLong retryAfter = rateLimiter.tryAcquire(
                "login:user:" + username, LOGIN_LIMIT, LOGIN_WINDOW);
        if (retryAfter.isPresent()) {
            log.info("Rate-limited login attempt for username {}", username);
            // 429 as a raw int: Jakarta RS 3.1 has no TOO_MANY_REQUESTS constant.
            return Response.status(429)
                    .header(HttpHeaders.RETRY_AFTER, retryAfter.getAsLong())
                    .entity(TOO_MANY_LOGINS)
                    .build();
        }

        User user = userRepository.findByAppUsername(username);

        // Verify even when the user is unknown, so both failures cost the same bcrypt work.
        String hash = user == null ? DUMMY_HASH : user.appPasswordHash();
        boolean verified = passwordHasher.verify(body.password(), hash);

        if (user == null || !verified) {
            log.info("Failed login attempt for username {}", username);
            return Response.status(Response.Status.UNAUTHORIZED).entity(CREDENTIALS_REJECTED).build();
        }

        log.info("Login succeeded for username {}", username);
        return Response.ok(new TokenResponse(sessionTokenService.issue(user.userId()))).build();
    }

    @DELETE
    @Path("/session")
    public Response logout(@HeaderParam(HttpHeaders.AUTHORIZATION) String header) {
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            sessionTokenService.revoke(header.substring(BEARER_PREFIX.length()).strip());
        }
        return Response.noContent().build();
    }
}
