package com.github.grepHammerspace.db.model;

import java.time.Instant;

/**
 * A signup invite code.
 *
 * <p>Codes are single-use: {@code claim} flips {@code used} and records who won it. Revocation
 * is a separate concept — it sets {@code revokedAt} for the audit trail and pulls
 * {@code expiresAt} back to the moment of revocation, so a revoked code fails the claim filter
 * without that filter needing to know revocation exists.
 *
 * <p>{@code createdBy} and {@code revokedBy} hold tailnet logins, not app user IDs — codes are
 * minted through the admin API, whose callers are identified by {@code tailscale serve} rather
 * than by a session token.
 */
public record InviteCode(
        String code,
        String note,
        boolean used,
        String usedBy,
        Instant usedAt,
        Instant createdAt,
        Instant expiresAt,
        String createdBy,
        Instant revokedAt,
        String revokedBy
) {
    /** What an operator needs to know about a code at a glance. Derived, never stored. */
    public enum Status { ACTIVE, USED, REVOKED, EXPIRED }

    /**
     * Resolves the code's status as of {@code now}.
     *
     * <p>Order matters: a claimed code reads as {@code USED} forever, even once its original
     * expiry has passed, because the account it created still exists.
     */
    public Status statusAt(Instant now) {
        if (used) return Status.USED;
        if (revokedAt != null) return Status.REVOKED;
        if (expiresAt == null || !expiresAt.isAfter(now)) return Status.EXPIRED;
        return Status.ACTIVE;
    }
}
