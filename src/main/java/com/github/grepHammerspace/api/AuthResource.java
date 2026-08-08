package com.github.grepHammerspace.api;

import com.github.grepHammerspace.api.dto.SessionRequest;
import com.github.grepHammerspace.api.dto.SignupRequest;
import com.github.grepHammerspace.api.dto.TokenResponse;
import com.github.grepHammerspace.auth.PasswordHasher;
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
import java.time.Instant;
import java.util.UUID;

/**
 * Anonymous authentication endpoints: signup, login and logout.
 *
 * <p>Deliberately carries <b>no</b> {@link com.github.grepHammerspace.auth.Authenticated}
 * annotation — these are the endpoints a caller reaches before holding a token, so they are
 * anonymous by construction rather than by omission. Everything else in the API is
 * {@code @Authenticated}.
 *
 * <p>Passwords arrive in request bodies here and nowhere else. Nothing in this class logs a
 * password or a raw token; the username is the most that reaches the log.
 */
// Auth stuff makes sense to me
@Path("/auth")
@Produces("application/json")
@Consumes("application/json")
@Singleton
public class AuthResource {
    private static final Logger log = LoggerFactory.getLogger(AuthResource.class);

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * A real bcrypt hash of a passphrase no account uses. Verifying against it costs the same
     * ~2^12 rounds as a genuine check, so an unknown username and a wrong password take the same
     * time — without it, response latency would tell an attacker which usernames exist.
     */
    private static final String DUMMY_HASH =
            "$2a$12$KmAXjDu8YKcsMbZIRfgItOfwgykh/XjK3U3DiLkff2tssjtqNtSdm";

    /** One message for invalid, used and expired codes alike — never leak which it was. */
    private static final String INVITE_REJECTED = "{\"error\": \"Invalid, used or expired invite code\"}";
    private static final String CREDENTIALS_REJECTED = "{\"error\": \"Invalid username or password\"}";

    private final UserRepository userRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final SessionTokenService sessionTokenService;
    private final PasswordHasher passwordHasher;

    @Inject
    public AuthResource(UserRepository userRepository,
                        InviteCodeRepository inviteCodeRepository,
                        SessionTokenService sessionTokenService,
                        PasswordHasher passwordHasher) {
        this.userRepository = userRepository;
        this.inviteCodeRepository = inviteCodeRepository;
        this.sessionTokenService = sessionTokenService;
        this.passwordHasher = passwordHasher;
    }

    /**
     * Redeems an invite code and creates the account, returning a session token so that signup
     * and login are a single round trip.
     *
     * <p>The code is claimed <em>before</em> the user is inserted, because the claim is the
     * atomic step that decides who wins a contested code. A duplicate username therefore burns
     * the code — accepted, since codes are minted by hand and re-minting is a one-liner in the
     * Atlas UI.
     */
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

    /** Exchanges username and password for a session token. */
    @POST
    @Path("/session")
    public Response login(@Valid SessionRequest body) {
        String username = body.username().strip();
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

    /**
     * Revokes the presented token. Anonymous, so the token is read straight off the header
     * rather than from a {@code SecurityContext}, and the response is 204 whether or not
     * anything was deleted — logging out twice is not an error.
     */
    @DELETE
    @Path("/session")
    public Response logout(@HeaderParam(HttpHeaders.AUTHORIZATION) String header) {
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            sessionTokenService.revoke(header.substring(BEARER_PREFIX.length()).strip());
        }
        return Response.noContent().build();
    }
}
