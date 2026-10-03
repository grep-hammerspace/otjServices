package com.github.grepHammerspace.admin;

import com.github.grepHammerspace.admin.dto.CreateInviteRequest;
import com.github.grepHammerspace.admin.dto.InviteResponse;
import com.github.grepHammerspace.api.dto.ApiError;
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

@Path("/admin/invites")
@Produces("application/json")
@Consumes("application/json")
@AdminIdentity
@Singleton
public class AdminInviteResource {
    private static final Logger log = LoggerFactory.getLogger(AdminInviteResource.class);

    private static final ApiError NO_SUCH_CODE = new ApiError("No such invite code");
    private static final ApiError ALREADY_CLAIMED = new ApiError(
            "That code has already been claimed and cannot be revoked");

    private final InviteCodeRepository inviteCodeRepository;
    private final InviteCodeGenerator generator;

    @Inject
    public AdminInviteResource(InviteCodeRepository inviteCodeRepository, InviteCodeGenerator generator) {
        this.inviteCodeRepository = inviteCodeRepository;
        this.generator = generator;
    }

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

    @GET
    public Response list(@Context SecurityContext sc) {
        Instant now = Instant.now();
        List<InviteResponse> codes = inviteCodeRepository.list().stream()
                .map(invite -> InviteResponse.of(invite, now))
                .toList();

        log.info("Admin {} listed {} invite code(s)", sc.getUserPrincipal().getName(), codes.size());
        return Response.ok(codes).build();
    }

    // 409 for a claimed code: the account it created exists, so revoking would undo nothing.
    @DELETE
    @Path("/{code}")
    public Response revoke(@PathParam("code") String code, @Context SecurityContext sc) {
        String admin = sc.getUserPrincipal().getName();

        return switch (inviteCodeRepository.revoke(code.strip(), admin)) {
            case REVOKED -> Response.noContent().build();
            case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND)
                    .entity(NO_SUCH_CODE).build();
            case ALREADY_USED -> Response.status(Response.Status.CONFLICT)
                    .entity(ALREADY_CLAIMED).build();
        };
    }
}
