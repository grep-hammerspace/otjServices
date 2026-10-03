package com.github.grepHammerspace.db.model;

import java.time.Instant;

// OneAdvanced credentials are never stored.
public record User(
        String userId,
        String appUsername,
        String learnerId,
        Instant createdAt
) {}
