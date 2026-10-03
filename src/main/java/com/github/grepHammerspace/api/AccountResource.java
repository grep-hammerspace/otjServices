package com.github.grepHammerspace.api;

import com.github.grepHammerspace.api.dto.AccountResponse;
import com.github.grepHammerspace.api.dto.ApiError;
import com.github.grepHammerspace.api.dto.UpdateLearnerIdRequest;
import com.github.grepHammerspace.auth.Authenticated;
import com.github.grepHammerspace.db.UserRepository;
import com.github.grepHammerspace.db.model.User;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

// Not on AuthResource: that class must stay free of @Authenticated, which binds per class.
@Path("/auth/me")
@Produces("application/json")
@Consumes("application/json")
@Authenticated
@Singleton
public class AccountResource {
    private static final Logger log = LoggerFactory.getLogger(AccountResource.class);

    // A bound against absurd input, not a format check: signup applies none, so this mustn't
    // either.
    private static final int MAX_LEARNER_ID_LENGTH = 64;

    private static final ApiError NO_ACCOUNT = new ApiError("No account found.");
    private static final ApiError LEARNER_ID_BLANK = new ApiError("Learner ID cannot be blank.");
    private static final ApiError LEARNER_ID_TOO_LONG = new ApiError("Learner ID is too long.");

    private final UserRepository userRepository;

    @Inject
    public AccountResource(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GET
    public Response me(@Context SecurityContext sc) {
        String userId = sc.getUserPrincipal().getName();

        User user = userRepository.findByUserId(userId);
        if (user == null) {
            log.info("No account found for user {}", userId);
            return Response.status(Response.Status.NOT_FOUND).entity(NO_ACCOUNT).build();
        }
        return Response.ok(AccountResponse.from(user)).build();
    }

    // Stored rows keep their old copy; submission reads the account's current value, so rows
    // already queued still post under the corrected one.
    @PATCH
    public Response updateLearnerId(UpdateLearnerIdRequest body, @Context SecurityContext sc) {
        String userId = sc.getUserPrincipal().getName();

        // Hand-checked rather than @Valid, so the reason reaches the user.
        String learnerId = body == null || body.learnerId() == null ? "" : body.learnerId().strip();
        if (learnerId.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(LEARNER_ID_BLANK).build();
        }
        if (learnerId.length() > MAX_LEARNER_ID_LENGTH) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(LEARNER_ID_TOO_LONG).build();
        }

        User updated = userRepository.updateLearnerId(userId, learnerId);
        if (updated == null) {
            log.info("No account found to update learnerId for user {}", userId);
            return Response.status(Response.Status.NOT_FOUND).entity(NO_ACCOUNT).build();
        }
        return Response.ok(AccountResponse.from(updated)).build();
    }
}
