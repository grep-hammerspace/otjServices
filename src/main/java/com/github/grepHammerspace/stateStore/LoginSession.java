package com.github.grepHammerspace.stateStore;

import com.github.grepHammerspace.web.Driver;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Future;

// One immutable value, so a re-prepare swaps driver, flow and login together. login is the
// background Microsoft poll for AZURE_PUSH, already done otherwise; cancelling it interrupts the poll.
public record LoginSession(LoginFlow flow,
                           Driver driver,
                           Future<?> login,
                           Instant startedAt) {
    // Past this the session is abandoned; it holds live OneAdvanced and Microsoft cookies.
    public static final Duration TTL = Duration.ofMinutes(5);

    public boolean isExpired(Instant now) {
        return startedAt.plus(TTL).isBefore(now);
    }
}
