package com.github.grepHammerspace.api;

import com.github.grepHammerspace.api.dto.CredentialKeyResponse;
import com.github.grepHammerspace.auth.Authenticated;
import com.github.grepHammerspace.crypto.CredentialKeyRing;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import javax.inject.Inject;

/**
 * Publishes the key clients seal OneAdvanced credentials to.
 *
 * <p>Authenticated, though a public key needs no protecting. Two reasons, neither of them
 * secrecy: it keeps the anonymous surface at exactly the two endpoints {@code deploy/prod/Caddyfile}
 * names in its {@code @anon} matcher — anything else added there needs its own rate-limit zone and
 * its own bcrypt-cost argument — and a caller that has no token has nothing to seal, since every
 * endpoint taking credentials is behind the same gate.
 *
 * <p>Under {@code /otj-services} rather than at the root for the same reason: that path already
 * falls into Caddy's authenticated bucket, so this endpoint arrives rate-limited without the edge
 * config having to learn about it.
 */
@Path("/otj-services/crypto")
@Produces("application/json")
@Authenticated
public class CryptoResource {

    private final CredentialKeyRing keyRing;

    @Inject
    public CryptoResource(CredentialKeyRing keyRing) {
        this.keyRing = keyRing;
    }

    /**
     * The current key, with its signature.
     *
     * <p>Deliberately uncached at the HTTP layer. The client caches the announcement itself until
     * {@code expiresAt}, so this is one small request per app session, and a {@code Cache-Control}
     * header here would put a copy in whatever sits in front — including the CDN whose view of
     * this traffic is the entire reason the endpoint exists.
     */
    @GET
    @Path("/public-key")
    public Response publicKey() {
        CredentialKeyResponse announcement = keyRing.announce();
        return Response.ok(announcement)
                .header("Cache-Control", "no-store")
                .build();
    }
}
