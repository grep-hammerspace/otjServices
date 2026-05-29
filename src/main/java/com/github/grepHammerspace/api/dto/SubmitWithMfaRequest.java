package com.github.grepHammerspace.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SubmitWithMfaRequest (
    @NotBlank String mfaCode
){}
