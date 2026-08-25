package com.github.grepHammerspace.api.dto;

/**
 * {@link ApiError} plus a machine-readable {@code code}, for the failures where the client has
 * something better to do than show the message.
 *
 * <p>Only one code really needs acting on: {@code unknown_key} means the client sealed to a key
 * this server no longer holds — the ordinary cause is a restart between the key fetch and the
 * submit, and the fix is to re-fetch the key and seal again, which the app does once, silently.
 * The others ({@code malformed_envelope}, {@code undecryptable}, {@code stale_envelope},
 * {@code unsupported_version}) are there so a bug report can say which of the five it was without
 * the server having to describe its own internals to the caller.
 *
 * <p>The {@code error} text stays the thing a person reads — the mobile client's
 * {@code errorMessage()} ladder still picks it up unchanged, because the key is still called
 * {@code error}.
 */
public record CryptoError(String error, String code) {}
