package com.github.grepHammerspace.db;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterRequest(
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String learnerId
) {}
