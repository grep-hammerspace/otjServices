package com.github.grepHammerspace.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SessionRequest(
        @NotBlank String username,
        @NotBlank String password
) {}
