package com.github.grepHammerspace.auth;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

// In-memory is correct, not a shortcut: there is exactly one app instance.
@Singleton
public class RateLimiter {
    // Keys are caller-supplied (the submitted username), so they are truncated to bound memory.
    private static final int MAX_KEY_LENGTH = 200;

    // The sweep is global and prunes against this, so no caller may use a longer window.
    static final Duration MAX_WINDOW = Duration.ofHours(1);

    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    private volatile Instant lastSweep;

    @Inject
    public RateLimiter() {
        this(Clock.systemUTC());
    }

    RateLimiter(Clock clock) {
        this.clock = clock;
        this.lastSweep = clock.instant();
    }

    public OptionalLong tryAcquire(String key, int limit, Duration window) {
        if (window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "window must be at most " + MAX_WINDOW + " — the sweep's cutoff assumes it");
        }
        Instant now = clock.instant();
        sweepIfDue(now);

        String bucket = key.length() > MAX_KEY_LENGTH ? key.substring(0, MAX_KEY_LENGTH) : key;

        while (true) {
            Deque<Instant> timestamps = hits.computeIfAbsent(bucket, k -> new ArrayDeque<>());

            synchronized (timestamps) {
                // The sweep can evict this deque between computeIfAbsent and this lock; recording
                // onto the orphan would grant a free attempt.
                if (hits.get(bucket) != timestamps) {
                    continue;
                }

                prune(timestamps, now, window);

                if (timestamps.size() < limit) {
                    timestamps.addLast(now);
                    return OptionalLong.empty();
                }

                // Round up, so a caller that obeys is never told to retry too early.
                Instant retryAt = timestamps.peekFirst().plus(window);
                long seconds = Math.max(1, ceilSeconds(Duration.between(now, retryAt)));
                return OptionalLong.of(seconds);
            }
        }
    }

    private static void prune(Deque<Instant> timestamps, Instant now, Duration window) {
        Instant cutoff = now.minus(window);
        while (!timestamps.isEmpty() && !timestamps.peekFirst().isAfter(cutoff)) {
            timestamps.removeFirst();
        }
    }

    private static long ceilSeconds(Duration duration) {
        return (duration.toNanos() + 999_999_999L) / 1_000_000_000L;
    }

    // The key is the submitted username, so every invented name adds an entry; this evicts the
    // quiet ones.
    private void sweepIfDue(Instant now) {
        if (Duration.between(lastSweep, now).compareTo(MAX_WINDOW) < 0) {
            return;
        }
        synchronized (this) {
            if (Duration.between(lastSweep, now).compareTo(MAX_WINDOW) < 0) {
                return;
            }
            lastSweep = now;
        }

        for (Iterator<Map.Entry<String, Deque<Instant>>> it = hits.entrySet().iterator(); it.hasNext(); ) {
            Deque<Instant> timestamps = it.next().getValue();
            synchronized (timestamps) {
                prune(timestamps, now, MAX_WINDOW);
                if (timestamps.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    int trackedKeys() {
        return hits.size();
    }
}
