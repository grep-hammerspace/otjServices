package com.github.grepHammerspace.db;

import com.github.grepHammerspace.db.model.InviteCode;
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
    void create_roundTripsAndIsImmediatelyClaimable() {
        InviteCode created = repository.create("CODE-CREATED", "for sam",
                NOW.plus(Duration.ofDays(7)), "admin@example.com");

        assertEquals("CODE-CREATED", created.code());
        assertEquals("admin@example.com", created.createdBy());
        assertFalse(created.used());
        assertEquals(InviteCode.Status.ACTIVE, created.statusAt(NOW));

        Document doc = find("CODE-CREATED");
        assertEquals("for sam", doc.getString("note"));
        assertEquals("admin@example.com", doc.getString("createdBy"));
        assertFalse(doc.getBoolean("used"));
        assertTrue(repository.claim("CODE-CREATED", "user-created"),
                "a freshly minted code must be usable without any further setup");
    }

    /**
     * The point of revocation: {@code claim} is never told about it, so this proves the expiry
     * rewrite is what actually stops the code.
     */
    @Test
    void revoke_unusedCode_blocksTheSubsequentClaim() {
        repository.create("CODE-REVOKED", "oops", NOW.plus(Duration.ofDays(7)), "admin@example.com");

        assertEquals(InviteCodeRepository.RevokeResult.REVOKED,
                repository.revoke("CODE-REVOKED", "admin@example.com"));
        assertFalse(repository.claim("CODE-REVOKED", "too-late"),
                "a revoked code must not be claimable");

        Document doc = find("CODE-REVOKED");
        assertEquals(Date.from(NOW), doc.getDate("revokedAt"));
        assertEquals("admin@example.com", doc.getString("revokedBy"));
        assertEquals(Date.from(NOW), doc.getDate("expiresAt"), "revocation pulls expiry back to now");
        assertFalse(doc.getBoolean("used"), "revoking is not claiming — nobody used this code");
    }

    @Test
    void revoke_unknownCode_reportsNotFound() {
        assertEquals(InviteCodeRepository.RevokeResult.NOT_FOUND,
                repository.revoke("CODE-NEVER-EXISTED", "admin@example.com"));
    }

    /**
     * Refusing rather than silently succeeding matters: the account the code created still
     * exists, so reporting success would tell an operator they had undone something they hadn't.
     */
    @Test
    void revoke_alreadyClaimedCode_isRefused() {
        repository.create("CODE-CLAIMED", "", NOW.plus(Duration.ofDays(7)), "admin@example.com");
        assertTrue(repository.claim("CODE-CLAIMED", "user-9"));

        assertEquals(InviteCodeRepository.RevokeResult.ALREADY_USED,
                repository.revoke("CODE-CLAIMED", "admin@example.com"));

        Document doc = find("CODE-CLAIMED");
        assertNull(doc.getDate("revokedAt"), "a refused revocation must not write an audit trail");
        assertEquals("user-9", doc.getString("usedBy"));
    }

    @Test
    void revoke_isIdempotentEnoughToRepeat() {
        repository.create("CODE-TWICE", "", NOW.plus(Duration.ofDays(7)), "admin@example.com");

        assertEquals(InviteCodeRepository.RevokeResult.REVOKED, repository.revoke("CODE-TWICE", "admin@example.com"));
        assertEquals(InviteCodeRepository.RevokeResult.REVOKED, repository.revoke("CODE-TWICE", "admin@example.com"),
                "re-revoking an unclaimed code is harmless — it is already unusable either way");
    }

    @Test
    void list_returnsNewestFirstWithDerivedStatus() {
        InviteCodeRepository earlier = repositoryAt(NOW.minus(Duration.ofHours(2)));
        earlier.create("CODE-LIST-OLD", "old", NOW.plus(Duration.ofDays(7)), "admin@example.com");
        repository.create("CODE-LIST-NEW", "new", NOW.plus(Duration.ofDays(7)), "admin@example.com");

        List<InviteCode> listed = repository.list();
        List<String> codes = listed.stream().map(InviteCode::code).toList();

        assertTrue(codes.indexOf("CODE-LIST-NEW") < codes.indexOf("CODE-LIST-OLD"),
                "newest first — an operator wants the code they just minted at the top");

        InviteCode fresh = listed.stream().filter(c -> c.code().equals("CODE-LIST-NEW")).findFirst().orElseThrow();
        assertEquals(InviteCode.Status.ACTIVE, fresh.statusAt(NOW));
    }

    @Test
    void statusAt_reflectsWhatHappenedToTheCode() {
        repository.create("CODE-STATUS-ACTIVE", "", NOW.plus(Duration.ofDays(7)), "admin@example.com");
        repository.create("CODE-STATUS-USED", "", NOW.plus(Duration.ofDays(7)), "admin@example.com");
        repository.create("CODE-STATUS-REVOKED", "", NOW.plus(Duration.ofDays(7)), "admin@example.com");
        repository.create("CODE-STATUS-EXPIRED", "", NOW.minus(Duration.ofSeconds(1)), "admin@example.com");

        repository.claim("CODE-STATUS-USED", "user-status");
        repository.revoke("CODE-STATUS-REVOKED", "admin@example.com");

        assertEquals(InviteCode.Status.ACTIVE, statusOf("CODE-STATUS-ACTIVE"));
        assertEquals(InviteCode.Status.USED, statusOf("CODE-STATUS-USED"));
        assertEquals(InviteCode.Status.REVOKED, statusOf("CODE-STATUS-REVOKED"));
        assertEquals(InviteCode.Status.EXPIRED, statusOf("CODE-STATUS-EXPIRED"));
    }

    /** A claimed code stays USED once its original expiry passes — the account it made is real. */
    @Test
    void statusAt_usedBeatsExpired() {
        repository.create("CODE-STATUS-BOTH", "", NOW.plus(Duration.ofDays(1)), "admin@example.com");
        repository.claim("CODE-STATUS-BOTH", "user-both");

        InviteCode code = repository.list().stream()
                .filter(c -> c.code().equals("CODE-STATUS-BOTH")).findFirst().orElseThrow();

        assertEquals(InviteCode.Status.USED, code.statusAt(NOW.plus(Duration.ofDays(30))));
    }

    private static InviteCode.Status statusOf(String code) {
        return repository.list().stream()
                .filter(c -> c.code().equals(code))
                .findFirst().orElseThrow()
                .statusAt(NOW);
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
