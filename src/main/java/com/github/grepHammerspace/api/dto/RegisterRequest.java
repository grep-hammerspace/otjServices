package com.github.grepHammerspace.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for the {@code POST /otj-services/register} endpoint. */
public record RegisterRequest(
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String learnerId
) {}
