package com.github.grepHammerspace.db;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
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
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class InviteCodeRepositoryIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    static MongoDatabase database;
    static InviteCodeRepository repository;

    static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");

    @BeforeAll
    static void startMongo() {
        MONGO.start();
        database = MongoClients.create(MONGO.getConnectionString()).getDatabase("testdb");
        repository = repositoryAt(NOW);
    }

    private static InviteCodeRepository repositoryAt(Instant instant) {
        return new InviteCodeRepository(database, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static void mint(String code, Instant expiresAt) {
        database.getCollection("inviteCodes").insertOne(new Document()
                .append("code", code)
                .append("used", false)
                .append("note", "test")
                .append("createdAt", Date.from(NOW))
                .append("expiresAt", Date.from(expiresAt))
                .append("usedBy", null)
                .append("usedAt", null));
    }

    private static Document find(String code) {
        return database.getCollection("inviteCodes").find(Filters.eq("code", code)).first();
    }

    @Test
    void claim_unusedUnexpiredCode_succeedsAndRecordsWho() {
        mint("CODE-HAPPY", NOW.plus(Duration.ofDays(7)));

        assertTrue(repository.claim("CODE-HAPPY", "user-1"));

        Document doc = find("CODE-HAPPY");
        assertNotNull(doc);
        assertTrue(doc.getBoolean("used"));
        assertEquals("user-1", doc.getString("usedBy"));
        assertEquals(Date.from(NOW), doc.getDate("usedAt"));
    }

    @Test
    void claim_unknownCode_returnsFalse() {
        assertFalse(repository.claim("CODE-DOES-NOT-EXIST", "user-2"));
    }

    @Test
    void claim_alreadyUsedCode_returnsFalse() {
        mint("CODE-REUSE", NOW.plus(Duration.ofDays(7)));

        assertTrue(repository.claim("CODE-REUSE", "user-3"));
        assertFalse(repository.claim("CODE-REUSE", "user-4"), "a code must not be redeemable twice");

        assertEquals("user-3", find("CODE-REUSE").getString("usedBy"),
                "the losing claim must not overwrite the winner");
    }

    @Test
    void claim_expiredCode_returnsFalse() {
        mint("CODE-EXPIRED", NOW.minus(Duration.ofSeconds(1)));

        assertFalse(repository.claim("CODE-EXPIRED", "user-5"));
        assertFalse(find("CODE-EXPIRED").getBoolean("used"), "a rejected claim must not burn the code");
    }

    @Test
    void claim_codeExpiringLater_stillWorksBeforeExpiry() {
        mint("CODE-CLOCK", NOW.plus(Duration.ofDays(1)));

        assertFalse(repositoryAt(NOW.plus(Duration.ofDays(2))).claim("CODE-CLOCK", "user-6"),
                "two days on, the code is past its expiry");
        assertTrue(repositoryAt(NOW.plus(Duration.ofHours(1))).claim("CODE-CLOCK", "user-7"),
                "an hour on, it is still live");
    }

    /**
     * The reason {@code claim} is a single {@code findOneAndUpdate} rather than a read followed by
     * a write: eight threads race the same code from a start gate, and exactly one may win.
     */
    @Test
    void claim_underContention_exactlyOneWinner() throws Exception {
        mint("CODE-RACE", NOW.plus(Duration.ofDays(7)));

        int threads = 8;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        startGate.await();
                        return repository.claim("CODE-RACE", "racer-" + i);
                    }))
                    .toList();

            startGate.countDown();

            long winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) winners++;
            }
            assertEquals(1, winners, "exactly one thread may claim a contested code");
        } finally {
            pool.shutdownNow();
        }

        Document doc = find("CODE-RACE");
        assertTrue(doc.getBoolean("used"));
        assertTrue(doc.getString("usedBy").startsWith("racer-"));
    }

    @Test
    void uniqueIndex_rejectsDuplicateCode() {
        mint("CODE-UNIQUE", NOW.plus(Duration.ofDays(7)));

        MongoWriteException e = assertThrows(MongoWriteException.class,
                () -> mint("CODE-UNIQUE", NOW.plus(Duration.ofDays(7))));
        assertEquals(com.mongodb.ErrorCategory.DUPLICATE_KEY, e.getError().getCategory());
    }

    @Test
    void uniqueIndexOnCode_exists() {
        MongoCollection<Document> codes = database.getCollection("inviteCodes");
        boolean found = false;
        for (Document index : codes.listIndexes()) {
            Document key = index.get("key", Document.class);
            if (key != null && key.containsKey("code") && Boolean.TRUE.equals(index.getBoolean("unique"))) {
                found = true;
            }
        }
        assertTrue(found, "inviteCodes must carry a unique index on 'code'");
    }
}
