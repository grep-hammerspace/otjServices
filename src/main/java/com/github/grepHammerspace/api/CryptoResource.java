package com.github.grepHammerspace.api;

import com.github.grepHammerspace.api.dto.CredentialKeyResponse;
import com.github.grepHammerspace.auth.Authenticated;
import com.github.grepHammerspace.crypto.CredentialKeyRing;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import javax.inject.Inject;

// Authenticated, and under /otj-services, so HAProxy's anonymous-path limits stay the only ones
// for bcrypt-cost endpoints and this one falls in the authenticated bucket.
@Path("/otj-services/crypto")
@Produces("application/json")
@Authenticated
public class CryptoResource {

    private final CredentialKeyRing keyRing;

    @Inject
    public CryptoResource(CredentialKeyRing keyRing) {
        this.keyRing = keyRing;
    }

    // no-store: nothing in front, Cloudflare above all, should keep a copy to replay.
    @GET
    @Path("/public-key")
    public Response publicKey() {
        CredentialKeyResponse announcement = keyRing.announce();
        return Response.ok(announcement)
                .header("Cache-Control", "no-store")
                .build();
    }
}
