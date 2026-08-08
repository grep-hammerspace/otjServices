package com.github.grepHammerspace.db;

import com.github.grepHammerspace.db.model.Session;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SessionRepositoryIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    static MongoDatabase database;
    static SessionRepository repository;

    static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");
    static final Instant LATER = NOW.plus(Duration.ofDays(30));

    @BeforeAll
    static void startMongo() {
        MONGO.start();
        database = MongoClients.create(MONGO.getConnectionString()).getDatabase("testdb");
        repository = new SessionRepository(database);
    }

    private static Session newSession(String tokenHash, String userId) {
        return new Session(null, tokenHash, userId, NOW, LATER);
    }

    @Test
    void insert_thenFindByTokenHash_roundTripsEveryField() {
        repository.insert(newSession("hash-round-trip", "user-1"));

        Session found = repository.findByTokenHash("hash-round-trip");

        assertNotNull(found);
        assertEquals("hash-round-trip", found.tokenHash());
        assertEquals("user-1", found.userId());
        assertEquals(NOW, found.createdAt());
        assertEquals(LATER, found.expiresAt());
        assertNotNull(found.id(), "id must be populated on read so extendExpiry has something to target");
    }

    @Test
    void findByTokenHash_unknownHash_returnsNull() {
        assertNull(repository.findByTokenHash("hash-does-not-exist"));
    }

    @Test
    void extendExpiry_writesNewExpiryAndLeavesTheRestAlone() {
        repository.insert(newSession("hash-extend", "user-2"));
        Session before = repository.findByTokenHash("hash-extend");

        Instant extended = LATER.plus(Duration.ofDays(30));
        repository.extendExpiry(before.id(), extended);

        Session after = repository.findByTokenHash("hash-extend");
        assertEquals(extended, after.expiresAt());
        assertEquals(before.id(), after.id());
        assertEquals("user-2", after.userId());
        assertEquals(NOW, after.createdAt(), "extending must not disturb createdAt");
    }

    @Test
    void deleteByTokenHash_returnsTrueOnceThenFalse() {
        repository.insert(newSession("hash-delete", "user-3"));

        assertTrue(repository.deleteByTokenHash("hash-delete"));
        assertNull(repository.findByTokenHash("hash-delete"));
        assertFalse(repository.deleteByTokenHash("hash-delete"), "second delete should find nothing");
    }

    @Test
    void uniqueIndex_rejectsDuplicateTokenHash() {
        repository.insert(newSession("hash-unique", "user-4"));

        MongoWriteException e = assertThrows(MongoWriteException.class,
                () -> repository.insert(newSession("hash-unique", "user-5")));
        assertEquals(com.mongodb.ErrorCategory.DUPLICATE_KEY, e.getError().getCategory());
    }

    @Test
    void ttlIndexOnExpiresAt_exists() {
        MongoCollection<Document> sessions = database.getCollection("sessions");
        boolean found = false;
        for (Document index : sessions.listIndexes()) {
            Document key = index.get("key", Document.class);
            Number expireAfter = index.get("expireAfterSeconds", Number.class);
            if (key != null && key.containsKey("expiresAt") && expireAfter != null && expireAfter.longValue() == 0L) {
                found = true;
            }
        }
        assertTrue(found, "sessions must carry a TTL index on 'expiresAt' so dead sessions self-reap");
    }
}
