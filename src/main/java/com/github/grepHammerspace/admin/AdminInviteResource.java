package com.github.grepHammerspace.admin;

import com.github.grepHammerspace.admin.dto.CreateInviteRequest;
import com.github.grepHammerspace.admin.dto.InviteResponse;
import com.github.grepHammerspace.db.InviteCodeRepository;
import com.github.grepHammerspace.db.model.InviteCode;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Mint, list and revoke signup invite codes.
 *
 * <p>Runs on the admin server ({@link AdminMain}), not the main API, and every method is gated by
 * {@link AdminIdentity} — the caller is a tailnet login, not a session token holder. That identity
 * is recorded on each code it mints or revokes, so the collection doubles as the audit log.
 *
 * <p>There is no endpoint to read back a code after minting beyond {@code GET /admin/invites},
 * and none to un-revoke: mint a new one instead. Codes are cheap.
 */
@Path("/admin/invites")
@Produces("application/json")
@Consumes("application/json")
@AdminIdentity
@Singleton
public class AdminInviteResource {

    private static final Logger log = LoggerFactory.getLogger(AdminInviteResource.class);

    private final InviteCodeRepository inviteCodeRepository;
    private final InviteCodeGenerator generator;

    @Inject
    public AdminInviteResource(InviteCodeRepository inviteCodeRepository, InviteCodeGenerator generator) {
        this.inviteCodeRepository = inviteCodeRepository;
        this.generator = generator;
    }

    /**
     * Mints a code. The body is optional; {@code POST} with no body yields the default lifetime
     * and an empty note.
     */
    @POST
    public Response create(@Valid CreateInviteRequest body, @Context SecurityContext sc) {
        CreateInviteRequest request = body == null ? new CreateInviteRequest(null, null) : body;
        String admin = sc.getUserPrincipal().getName();

        String code = generator.generate();
        Instant expiresAt = Instant.now().plus(request.expiryDaysOrDefault(), ChronoUnit.DAYS);
        InviteCode created = inviteCodeRepository.create(code, request.noteOrEmpty(), expiresAt, admin);

        log.info("Admin {} minted an invite code expiring {}", admin, expiresAt);
        return Response.status(Response.Status.CREATED)
                .entity(InviteResponse.of(created, Instant.now()))
                .build();
    }

    /** Every code, newest first, each with its derived status. */
    @GET
    public Response list(@Context SecurityContext sc) {
        Instant now = Instant.now();
        List<InviteResponse> codes = inviteCodeRepository.list().stream()
                .map(invite -> InviteResponse.of(invite, now))
                .toList();

        log.info("Admin {} listed {} invite code(s)", sc.getUserPrincipal().getName(), codes.size());
        return Response.ok(codes).build();
    }

    /**
     * Revokes an unclaimed code.
     *
     * <p>A code that has already been claimed is a 409 rather than a silent success — the account
     * it created still exists, and pretending otherwise would let an operator believe they had
     * undone something they had not.
     */
    @DELETE
    @Path("/{code}")
    public Response revoke(@PathParam("code") String code, @Context SecurityContext sc) {
        String admin = sc.getUserPrincipal().getName();

        return switch (inviteCodeRepository.revoke(code.strip(), admin)) {
            case REVOKED -> Response.noContent().build();
            case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"No such invite code\"}").build();
            case ALREADY_USED -> Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\": \"That code has already been claimed and cannot be revoked\"}").build();
        };
    }
}
