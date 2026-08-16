package com.github.grepHammerspace.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /auth/signup}. */
public record SignupRequest(
        @NotBlank String inviteCode,
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String learnerId
) {}
