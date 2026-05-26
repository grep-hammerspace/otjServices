package com.github.grepHammerspace.db;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request body for the {@code POST /otj-services/register} endpoint. */
public record RegisterRequest(
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String learnerId
) {}
