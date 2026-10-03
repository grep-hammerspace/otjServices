package com.github.grepHammerspace.admin.dto;

import com.github.grepHammerspace.db.model.InviteCode;

import java.time.Instant;

// Timestamps are formatted here so the wire format can't depend on jsr310 happening to be on the
// classpath.
public record InviteResponse(
        String code,
        String note,
        String status,
        String createdAt,
        String expiresAt,
        String createdBy,
        String usedBy,
        String usedAt,
        String revokedAt,
        String revokedBy
) {
    public static InviteResponse of(InviteCode invite, Instant now) {
        return new InviteResponse(
                invite.code(),
                invite.note(),
                invite.statusAt(now).name(),
                iso(invite.createdAt()),
                iso(invite.expiresAt()),
                invite.createdBy(),
                invite.usedBy(),
                iso(invite.usedAt()),
                iso(invite.revokedAt()),
                invite.revokedBy());
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
