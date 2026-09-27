package com.github.grepHammerspace.stateStore;

import com.github.grepHammerspace.web.Driver;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

// One immutable value, so a re-prepare swaps driver, flow and future together.
public record LoginSession(LoginFlow flow,
                           Driver driver,
                           CompletableFuture<Void> future,
                           Instant startedAt) {
    // Past this the session is abandoned; it holds live OneAdvanced and Microsoft cookies.
    public static final Duration TTL = Duration.ofMinutes(5);

    public boolean isExpired(Instant now) {
        return startedAt.plus(TTL).isBefore(now);
    }
}
