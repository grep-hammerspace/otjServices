package com.github.grepHammerspace.api.dto;

/**
 * The one and only time a raw session token is visible — it is not recoverable afterwards
 * (see {@link com.github.grepHammerspace.auth.SessionTokenService}).
 */
public record TokenResponse(String token) {}
