package com.github.grepHammerspace.db.model;

import java.time.Instant;

/**
 * A persisted bearer session.
 *
 * <p>{@code tokenHash} is the SHA-256 hex digest of the raw token — the raw value is returned to
 * the client once at issue time and never stored, so nothing here can authenticate a request.
 * {@code expiresAt} slides forward as the session is used and is also the key of a TTL index, so
 * an abandoned session deletes itself.
 */
public record Session(
        String id,   // nullable; null for new records, populated when read from MongoDB
        String tokenHash,
        String userId,
        Instant createdAt,
        Instant expiresAt
) {}
