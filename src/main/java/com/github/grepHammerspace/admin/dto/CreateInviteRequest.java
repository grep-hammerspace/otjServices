package com.github.grepHammerspace.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /admin/invites}. Both fields are optional — an empty body mints a
 * code with the default lifetime and no note.
 *
 * <p>The code itself is not settable. Operators would pick guessable ones.
 */
public record CreateInviteRequest(
        @Size(max = 200) String note,
        @Min(1) @Max(365) Integer expiresInDays
) {
    /** Long enough to hand someone a code and have them act on it; short enough that a leaked one dies. */
    public static final int DEFAULT_EXPIRY_DAYS = 7;

    public int expiryDaysOrDefault() {
        return expiresInDays == null ? DEFAULT_EXPIRY_DAYS : expiresInDays;
    }

    public String noteOrEmpty() {
        return note == null ? "" : note.strip();
    }
}
