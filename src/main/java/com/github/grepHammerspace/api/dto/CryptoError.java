package com.github.grepHammerspace.api.dto;

// ApiError plus a code; the client acts only on unknown_key, by re-fetching the key once.
public record CryptoError(String error, String code) {}
