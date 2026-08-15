package com.github.grepHammerspace.api.dto;

/**
 * The single error shape every endpoint returns: {@code {"error": "..."}}.
 *
 * <p>Exists so error bodies stop being built by string concatenation. The hand-built form
 * interpolated {@code e.getMessage()} straight into JSON, which both leaked driver internals
 * (URLs carrying the username, Microsoft flow tokens) to the caller and let any quote in the
 * message break out of the string. Letting Jackson serialise a record closes both at once.
 */
public record ApiError(String error) {}
