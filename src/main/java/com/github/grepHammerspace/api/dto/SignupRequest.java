package com.github.grepHammerspace.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SignupRequest(
        @NotBlank String inviteCode,
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String learnerId
) {}
