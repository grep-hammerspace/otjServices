package com.github.grepHammerspace.db.model;

import java.time.Instant;

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
    public enum Status { ACTIVE, USED, REVOKED, EXPIRED }

    // A claimed code reads USED forever: the account it created still exists.
    public Status statusAt(Instant now) {
        if (used) return Status.USED;
        if (revokedAt != null) return Status.REVOKED;
        if (expiresAt == null || !expiresAt.isAfter(now)) return Status.EXPIRED;
        return Status.ACTIVE;
    }
}
