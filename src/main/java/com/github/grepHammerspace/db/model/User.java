package com.github.grepHammerspace.db.model;

/** Represents a registered user keyed by their Tailscale identity ({@code userId}). */
public record User(
        String userId,
        String username,
        String password,
        String learnerId
) {}
