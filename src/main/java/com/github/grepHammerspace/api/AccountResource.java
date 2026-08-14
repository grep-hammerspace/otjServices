package com.github.grepHammerspace.api;

import com.github.grepHammerspace.api.dto.AccountResponse;
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

/**
 * The signed-in account itself: read it, and correct the learner ID captured at signup.
 *
 * <p>Separate from {@link AuthResource} rather than two more methods on it. That class's contract
 * is that it carries <b>no</b> {@link Authenticated} annotation — its endpoints are the ones a
 * caller reaches before holding a token, "anonymous by construction rather than by omission".
 * These two require a token, and the annotation binds per class, so putting them there would make
 * that statement false. Only the Java class is separate; the URL stays under {@code /auth}.
 *
 * <p>Injects {@link UserRepository} and nothing else. In particular there is no
 * {@code resolveUserState(sc)} here — that helper parks a browser driver in the
 * {@link com.github.grepHammerspace.stateStore.UserStateStore} for the submit flow, and reading or
 * writing one field on a user document needs no driver. Calling it would allocate one on every
 * profile read.
 *
 * <p>Why the learner ID needs an endpoint at all: it is typed once, in the last field of the
 * signup form, and never shown again. Nothing validates it beyond non-blankness, because only
 * OneAdvanced knows what a real one looks like — so a typo is invisible until a submission run in
 * which OneAdvanced rejects every row.
 */
@Path("/auth/me")
@Produces("application/json")
@Consumes("application/json")
@Authenticated
@Singleton
public class AccountResource {
    private static final Logger log = LoggerFactory.getLogger(AccountResource.class);

    /**
     * A bound to stop an absurd string reaching Mongo, not a claim about the format. There is no
     * format check: signup applies none, and an endpoint that is stricter than the door the value
     * came in through would refuse to correct an account it had itself allowed to be created.
     */
    private static final int MAX_LEARNER_ID_LENGTH = 64;

    private static final String NO_ACCOUNT = "{\"error\": \"No account found.\"}";

    private final UserRepository userRepository;

    @Inject
    public AccountResource(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** The signed-in account. */
    @GET
    public Response me(@Context SecurityContext sc) {
        String userId = sc.getUserPrincipal().getName();

        User user = userRepository.findByUserId(userId);
        if (user == null) {
            // Close to unreachable: the token was issued against this userId, so the document was
            // there at login. Reachable if the account is deleted while a token for it is live.
            log.info("No account found for user {}", userId);
            return Response.status(Response.Status.NOT_FOUND).entity(NO_ACCOUNT).build();
        }
        return Response.ok(AccountResponse.from(user)).build();
    }

    /**
     * Corrects the learner ID.
     *
     * <p>PATCH rather than PUT: the body carries one field and the resource has two. The username
     * is not changeable here — it is the unique index the login path reads, and renaming an account
     * is a different feature with its own collision handling — so a full replacement would have to
     * accept a field it then refuses to act on.
     *
     * <p>Answers with the account rather than 204 so the client can write it straight into its
     * cache and redraw as the editor closes, instead of showing the old value until a refetch lands.
     *
     * <p><b>This does not touch rows already written.</b> {@code learnerId} is copied onto each
     * activity log as the row is created, so a correction applies to what is logged next and cannot
     * reach what is already queued. That is deliberate — a back-fill would silently rewrite rows the
     * user has not looked at, and posted rows cannot be rewritten at OneAdvanced anyway. The mobile
     * client says so, naming the queued count, while the field is open.
     */
    @PATCH
    public Response updateLearnerId(UpdateLearnerIdRequest body, @Context SecurityContext sc) {
        String userId = sc.getUserPrincipal().getName();

        // Hand-checked rather than @Valid, so the reason reaches the user: see UpdateLearnerIdRequest.
        // A missing key deserialises to null, which is the same fault as a blank string.
        String learnerId = body == null || body.learnerId() == null ? "" : body.learnerId().strip();
        if (learnerId.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Learner ID cannot be blank.\"}").build();
        }
        if (learnerId.length() > MAX_LEARNER_ID_LENGTH) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Learner ID is too long.\"}").build();
        }

        // Stored stripped, matching AuthResource's signup path. Both drivers strip() again on the
        // way out, so a stored trailing space would not break a submission — but it would sit in the
        // database forever and show up in the client's card.
        User updated = userRepository.updateLearnerId(userId, learnerId);
        if (updated == null) {
            log.info("No account found to update learnerId for user {}", userId);
            return Response.status(Response.Status.NOT_FOUND).entity(NO_ACCOUNT).build();
        }
        return Response.ok(AccountResponse.from(updated)).build();
    }
}
