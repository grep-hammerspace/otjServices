package com.github.grepHammerspace.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /auth/session} — the login. */
public record SessionRequest(
        @NotBlank String username,
        @NotBlank String password
) {}
