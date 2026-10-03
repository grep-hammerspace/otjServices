package com.github.grepHammerspace.db.model;

import java.time.Instant;

public record Session(
        String id,
        String tokenHash,
        String userId,
        Instant createdAt,
        Instant expiresAt
) {}
