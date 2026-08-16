package com.github.grepHammerspace.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testutil.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private static final Instant START = Instant.parse("2026-08-15T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int LIMIT = 10;

    private MutableClock clock;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        limiter = new RateLimiter(clock);
    }

    private OptionalLong acquire(String key) {
        return limiter.tryAcquire(key, LIMIT, WINDOW);
    }

    @Test
    void allowsUpToTheLimit() {
        for (int i = 1; i <= LIMIT; i++) {
            assertTrue(acquire("alice").isEmpty(), "attempt " + i + " should be allowed");
        }
    }

    @Test
    void deniesTheAttemptPastTheLimit() {
        for (int i = 0; i < LIMIT; i++) acquire("alice");
        assertTrue(acquire("alice").isPresent(), "the 11th attempt should be limited");
    }

    @Test
    void reportsSecondsUntilTheOldestHitAgesOut() {
        for (int i = 0; i < LIMIT; i++) acquire("alice");

        clock.advance(Duration.ofMinutes(5));
        // Oldest hit was at START, so it leaves the window 15 min after that — 10 min from now.
        assertEquals(Duration.ofMinutes(10).toSeconds(), acquire("alice").getAsLong());
    }

    /** Rounded up, so a caller that obeys the answer is never told to retry a moment too early. */
    @Test
    void roundsPartialSecondsUp() {
        for (int i = 0; i < LIMIT; i++) acquire("alice");

        clock.advance(WINDOW.minusMillis(1500));
        assertEquals(2, acquire("alice").getAsLong());
    }

    @Test
    void windowSlidesSoTheKeyRecovers() {
        for (int i = 0; i < LIMIT; i++) acquire("alice");
        assertTrue(acquire("alice").isPresent());

        clock.advance(WINDOW.plusSeconds(1));
        assertTrue(acquire("alice").isEmpty(), "every hit has aged out, so the key is usable again");
    }

    /**
     * The window slides continuously rather than resetting on a boundary — otherwise straddling
     * one would hand an attacker 2 x limit attempts back to back.
     */
    @Test
    void onlyTheAgedOutHitsAreForgiven() {
        for (int i = 0; i < LIMIT; i++) {
            acquire("alice");
            clock.advance(Duration.ofMinutes(1));
        }
        // 10 minutes on: the oldest hit is 10 min old, so nothing has left a 15-minute window.
        assertTrue(acquire("alice").isPresent());

        // Far enough for exactly the first hit to age out, freeing exactly one slot.
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertTrue(acquire("alice").isEmpty(), "one slot should have freed up");
        assertTrue(acquire("alice").isPresent(), "but only one");
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < LIMIT; i++) acquire("alice");
        assertTrue(acquire("alice").isPresent());
        assertTrue(acquire("bob").isEmpty(), "bob should not pay for alice's attempts");
    }

    @Test
    void evictsKeysThatHaveGoneQuiet() {
        acquire("alice");
        acquire("bob");
        assertEquals(2, limiter.trackedKeys());

        // The sweep runs at most once per MAX_WINDOW, and only when a call comes in.
        clock.advance(RateLimiter.MAX_WINDOW.plusSeconds(1));
        acquire("carol");

        assertEquals(1, limiter.trackedKeys(), "only the active key should remain");
    }

    @Test
    void keepsKeysThatAreStillActiveThroughASweep() {
        acquire("alice");

        // Far enough to trigger a sweep, but alice hits again on the way through.
        clock.advance(RateLimiter.MAX_WINDOW.plusSeconds(1));
        acquire("alice");
        assertEquals(1, limiter.trackedKeys());

        // Her earlier hit aged out, so she still has the full allowance.
        for (int i = 1; i < LIMIT; i++) {
            assertTrue(acquire("alice").isEmpty(), "attempt " + i + " should survive the sweep");
        }
    }

    /**
     * The sweep is global but the window is a per-call argument, so it prunes against
     * {@code MAX_WINDOW}. A caller asking for more than that would have its live counters swept
     * away; fail loudly on the first call rather than silently under-counting later.
     */
    @Test
    void rejectsAWindowLongerThanTheSweepAssumes() {
        assertThrows(IllegalArgumentException.class,
                () -> limiter.tryAcquire("alice", LIMIT, RateLimiter.MAX_WINDOW.plusMinutes(1)));
    }

    /** Concurrent callers on one key must together get exactly {@code limit} through. */
    @Test
    void underContentionAllowsExactlyTheLimit() throws Exception {
        int threads = 32;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        startGate.await();
                        return acquire("alice").isEmpty();
                    }))
                    .toList();
            startGate.countDown();

            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) allowed++;
            }
            assertEquals(LIMIT, allowed, "exactly the limit may get through, no matter the race");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The key is caller-supplied and unbounded, so it is truncated. Two absurd usernames sharing
     * a bucket is fine — they are attack traffic either way — but unbounded map keys are not.
     */
    @Test
    void truncatesOverlongKeys() {
        String base = "x".repeat(500);
        for (int i = 0; i < LIMIT; i++) acquire(base + "-one");
        assertTrue(acquire(base + "-two").isPresent(),
                "keys past the cap share a bucket rather than growing the map");
        assertEquals(1, limiter.trackedKeys());
    }

    @Test
    void aLimitedCallDoesNotExtendTheWindow() {
        for (int i = 0; i < LIMIT; i++) acquire("alice");

        clock.advance(Duration.ofMinutes(14));
        assertTrue(acquire("alice").isPresent(), "still inside the window");

        // If the denied call above had recorded a hit, this would still be limited.
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertFalse(acquire("alice").isPresent(), "denied attempts must not push the window out");
    }
}
