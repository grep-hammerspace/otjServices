package com.github.grepHammerspace.admin.dto;

import com.github.grepHammerspace.db.model.InviteCode;

import java.time.Instant;

/**
 * An invite code as the admin API reports it.
 *
 * <p>{@code status} is derived at read time rather than stored, so a code that quietly ages out
 * of its expiry window reads as {@code EXPIRED} without anything having to run a sweep.
 *
 * <p>Timestamps are ISO-8601 strings rather than {@link Instant}s. Jackson only renders an
 * {@code Instant} as a readable string when {@code jackson-datatype-jsr310} is registered, and the
 * only thing putting that module on the classpath today is a transitive runtime dependency of the
 * Anthropic SDK — which the LLM-provider migration is set to remove. Formatting here means the
 * wire contract cannot quietly turn back into {@code 1786284972.508818510} when that happens.
 */
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

    /** {@link Instant#toString()} is already ISO-8601; this just tolerates nulls. */
    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
