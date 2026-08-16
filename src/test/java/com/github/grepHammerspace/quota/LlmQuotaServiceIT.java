package com.github.grepHammerspace.quota;

import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link LlmQuotaService} against a real MongoDB.
 *
 * <p>Follows the house IT conventions: the container is started once in {@code @BeforeAll} and
 * never cleaned between tests, so each test uses its own {@code userId} for isolation.
 */
class LlmQuotaServiceIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    static MongoDatabase database;

    static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    @BeforeAll
    static void startMongo() {
        MONGO.start();
        database = MongoClients.create(MONGO.getConnectionString()).getDatabase("testdb");
    }

    /** Mirrors {@code InviteCodeRepositoryIT.repositoryAt} — the quota's state is in Mongo, so a
     *  different day is expressed by a new service at a new instant rather than a moving clock. */
    private static LlmQuotaService serviceAt(Instant instant) {
        return new LlmQuotaService(database, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static Document quotaDoc(String userId) {
        return database.getCollection("llmQuota").find(Filters.eq("userId", userId)).first();
    }

    @Test
    void consumesUpToTheDailyLimit() {
        LlmQuotaService service = serviceAt(NOW);
        for (int i = 1; i <= LlmQuotaService.DAILY_LIMIT; i++) {
            assertTrue(service.tryConsume("quota-limit"), "call " + i + " should be within quota");
        }
    }

    @Test
    void refusesPastTheDailyLimit() {
        LlmQuotaService service = serviceAt(NOW);
        for (int i = 0; i < LlmQuotaService.DAILY_LIMIT; i++) service.tryConsume("quota-over");

        assertFalse(service.tryConsume("quota-over"), "the 11th call is over quota");
        assertFalse(service.tryConsume("quota-over"), "and it stays over");
    }

    @Test
    void isPerUser() {
        LlmQuotaService service = serviceAt(NOW);
        for (int i = 0; i <= LlmQuotaService.DAILY_LIMIT; i++) service.tryConsume("quota-noisy");
        assertFalse(service.tryConsume("quota-noisy"));

        assertTrue(service.tryConsume("quota-quiet"), "one user's spending is not another's");
    }

    @Test
    void startsFreshAfterTheDayRolls() {
        for (int i = 0; i <= LlmQuotaService.DAILY_LIMIT; i++) {
            serviceAt(NOW).tryConsume("quota-rollover");
        }
        assertFalse(serviceAt(NOW).tryConsume("quota-rollover"));

        assertTrue(serviceAt(NOW.plus(Duration.ofDays(1))).tryConsume("quota-rollover"),
                "a new day is a new counter");

        assertEquals(2, database.getCollection("llmQuota")
                        .countDocuments(Filters.eq("userId", "quota-rollover")),
                "one document per user per day");
    }

    /** The boundary is UTC midnight, not the box's local midnight — that is what the API promises. */
    @Test
    void rollsOverAtUtcMidnight() {
        serviceAt(Instant.parse("2026-08-16T23:59:59Z")).tryConsume("quota-midnight");
        serviceAt(Instant.parse("2026-08-17T00:00:00Z")).tryConsume("quota-midnight");

        assertEquals(2, database.getCollection("llmQuota")
                .countDocuments(Filters.eq("userId", "quota-midnight")));
    }

    @Test
    void reportsSecondsUntilUtcMidnight() {
        assertEquals(Duration.ofHours(12).toSeconds(), serviceAt(NOW).secondsUntilReset());
    }

    @Test
    void setsAnExpiryOnlyWhenTheDocumentIsCreated() {
        LlmQuotaService service = serviceAt(NOW);
        service.tryConsume("quota-ttl");
        Object first = quotaDoc("quota-ttl").get("expiresAt");

        service.tryConsume("quota-ttl");
        assertEquals(first, quotaDoc("quota-ttl").get("expiresAt"),
                "a later call in the same day must not push the expiry out");
    }

    /**
     * The race the retry exists for: with no document yet, every thread's upsert tries to insert
     * and the unique index lets exactly one through.
     *
     * <p>Three assertions, each earning its place. No thread throwing covers the retry itself —
     * {@code Future.get} would rethrow a duplicate-key error. Exactly ten trues is the quota.
     * And the stored count matching the caller count is what a retry that double-counted, or
     * swallowed an increment, would break.
     */
    @Test
    void underContentionConsumesExactlyTheLimit() throws Exception {
        LlmQuotaService service = serviceAt(NOW);
        int threads = 12;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        startGate.await();
                        return service.tryConsume("quota-race");
                    }))
                    .toList();
            startGate.countDown();

            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) allowed++;
            }

            assertEquals(LlmQuotaService.DAILY_LIMIT, allowed,
                    "exactly the limit may be consumed, no matter the race");
            assertEquals(threads, quotaDoc("quota-race").getInteger("count"),
                    "every caller increments exactly once, including the one that lost the insert");
        } finally {
            pool.shutdownNow();
        }
    }
}
