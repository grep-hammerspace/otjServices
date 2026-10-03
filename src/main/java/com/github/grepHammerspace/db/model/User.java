package com.github.grepHammerspace.db.model;

import java.time.Instant;

// This app's own login. OneAdvanced credentials are never stored.
public record User(
        String userId,
        String appUsername,
        String appPasswordHash,
        String learnerId,
        Instant createdAt
) {}
