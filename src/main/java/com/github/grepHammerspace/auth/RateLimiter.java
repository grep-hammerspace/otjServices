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

/**
 * In-memory sliding-window rate limiter.
 *
 * <p>In-memory is correct here rather than a shortcut: there is exactly one app instance, so a
 * shared store would buy nothing. The counters reset on restart, which is accepted — a restart is
 * rare and an attacker cannot cause one.
 *
 * <p>Each key keeps a deque of hit timestamps. A call prunes everything older than one window,
 * then admits if fewer than {@code limit} remain. The window therefore slides continuously
 * instead of resetting on a fixed boundary, so an attacker cannot get {@code 2 × limit} attempts
 * by straddling a boundary.
 */
@Singleton
public class RateLimiter {

    /**
     * Keys longer than this are truncated before use.
     *
     * <p>Keys are built from caller-supplied values — a username on a login attempt — and nothing
     * bounds their length. Without a cap, a flood of very long usernames is a memory amplifier
     * that the sweep below only clears a window later. Distinct absurd usernames colliding on one
     * bucket is harmless; they are all attack traffic.
     */
    private static final int MAX_KEY_LENGTH = 200;

    /**
     * The longest window any caller may ask for.
     *
     * <p>The window is a per-call argument, but the sweep below is global and has to pick one
     * cutoff. Sweeping against the largest permitted window means it can never discard a hit that
     * some other caller's longer window still counts. Asking for more than this throws rather
     * than silently resetting that caller's counter.
     */
    static final Duration MAX_WINDOW = Duration.ofHours(1);

    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    private volatile Instant lastSweep;

    @Inject
    public RateLimiter() {
        this(Clock.systemUTC());
    }

    /** Visible for tests — lets the sliding window be driven by a steppable clock. */
    RateLimiter(Clock clock) {
        this.clock = clock;
        this.lastSweep = clock.instant();
    }

    /**
     * Records a hit against {@code key} and reports whether it is allowed.
     *
     * @return empty when allowed, or the whole seconds to wait before retrying when limited
     */
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

            // Per-key locking rather than one lock over the map: two users logging in at once have
            // no reason to contend.
            synchronized (timestamps) {
                // The sweep can evict this deque between the computeIfAbsent above and this lock.
                // It removes only while holding the same lock, so once we are inside, an identity
                // check settles it: if we lost the race we would be recording onto an orphan that
                // nothing will ever read, quietly granting a free attempt.
                if (hits.get(bucket) != timestamps) {
                    continue;
                }

                prune(timestamps, now, window);

                if (timestamps.size() < limit) {
                    timestamps.addLast(now);
                    return OptionalLong.empty();
                }

                // The oldest hit is what has to age out before a slot frees up. Round up, so a
                // caller that obeys the answer is never told to retry a moment too early.
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

    /**
     * Drops keys that have gone quiet, at most once per window.
     *
     * <p>This matters more than it looks: the key for a login is the <em>submitted</em> username,
     * so an attacker gets a fresh map entry for every name they invent. Without eviction the map
     * grows for the life of the process.
     *
     * <p>Once per {@link #MAX_WINDOW} is the natural cadence — a key with no hits in that long is
     * empty under any permitted window — and bounding it this way keeps the sweep's cost
     * independent of request rate. Note it only runs on a call, so a fully idle process never
     * sweeps; that is fine, because an idle process is not growing either.
     *
     * <p>{@code tryAcquire} prunes only keys that are touched again, which is exactly the set
     * that does not leak. The keys that leak are the ones never seen twice, and this is what
     * collects them.
     */
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

    /** Visible for tests — the number of keys currently held. */
    int trackedKeys() {
        return hits.size();
    }
}
