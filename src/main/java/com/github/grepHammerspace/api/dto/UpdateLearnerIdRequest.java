package com.github.grepHammerspace.api.dto;

// No @NotBlank: the resource hand-checks it, so the message reaches the user.
public record UpdateLearnerIdRequest(String learnerId) {}
