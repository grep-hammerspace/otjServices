package com.github.grepHammerspace.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CreateInviteRequest(
        @Size(max = 200) String note,
        @Min(1) @Max(365) Integer expiresInDays
) {
    public static final int DEFAULT_EXPIRY_DAYS = 7;

    public int expiryDaysOrDefault() {
        return expiresInDays == null ? DEFAULT_EXPIRY_DAYS : expiresInDays;
    }

    public String noteOrEmpty() {
        return note == null ? "" : note.strip();
    }
}
