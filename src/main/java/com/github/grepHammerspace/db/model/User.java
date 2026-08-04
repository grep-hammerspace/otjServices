package com.github.grepHammerspace.db.model;

import java.time.Instant;

/**
 * A registered account for this application.
 *
 * <p>{@code appUsername} and {@code appPasswordHash} are this app's own login — deliberately
 * named so they can never be confused with OneAdvanced credentials, which are never stored
 * server-side. {@code userId} is a generated UUID; everything downstream treats it as an
 * opaque string. {@code learnerId} is captured once at signup — it is not a secret and is
 * embedded in every activity log row.
 */
public record User(
        String userId,
        String appUsername,
        String appPasswordHash,
        String learnerId,
        Instant createdAt
) {}
