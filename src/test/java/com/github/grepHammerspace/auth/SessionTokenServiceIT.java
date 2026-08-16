package com.github.grepHammerspace.auth;

import com.github.grepHammerspace.db.SessionRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionTokenServiceIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    static MongoDatabase database;

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeAll
    static void startMongo() {
        MONGO.start();
        database = MongoClients.create(MONGO.getConnectionString()).getDatabase("testdb");
    }

    private static SessionTokenService serviceAt(Instant instant) {
        return new SessionTokenService(new SessionRepository(database), Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void issue_then_resolve_returnsUserId() {
        SessionTokenService service = serviceAt(T0);
        String token = service.issue("user-1");

        assertEquals(Optional.of("user-1"), service.resolve(token));
    }

    @Test
    void resolve_unknownToken_returnsEmpty() {
        assertEquals(Optional.empty(), serviceAt(T0).resolve("no-such-token"));
    }

    @Test
    void resolve_expiredToken_returnsEmpty() {
        String token = serviceAt(T0).issue("user-2");

        SessionTokenService after31Days = serviceAt(T0.plus(Duration.ofDays(31)));
        assertEquals(Optional.empty(), after31Days.resolve(token));
    }

    @Test
    void resolve_pastHalfWindow_extendsExpiry() {
        String token = serviceAt(T0).issue("user-3");

        // 16 days in: less than half the 30-day window remains, so this resolve extends it
        SessionTokenService at16Days = serviceAt(T0.plus(Duration.ofDays(16)));
        assertEquals(Optional.of("user-3"), at16Days.resolve(token));

        // 40 days after issue — dead without the extension, alive with it (16d + 30d = 46d)
        SessionTokenService at40Days = serviceAt(T0.plus(Duration.ofDays(40)));
        assertEquals(Optional.of("user-3"), at40Days.resolve(token));
    }

    @Test
    void resolve_withMostOfWindowRemaining_doesNotWriteNewExpiry() {
        SessionTokenService service = serviceAt(T0);
        String token = service.issue("user-4");
        Instant originalExpiry = expiryOf("user-4");

        SessionTokenService oneDayLater = serviceAt(T0.plus(Duration.ofDays(1)));
        assertEquals(Optional.of("user-4"), oneDayLater.resolve(token));

        assertEquals(originalExpiry, expiryOf("user-4"), "expiresAt should be untouched while over half the window remains");
    }

    @Test
    void revoke_deletesSession() {
        SessionTokenService service = serviceAt(T0);
        String token = service.issue("user-5");

        assertTrue(service.revoke(token));
        assertEquals(Optional.empty(), service.resolve(token));
        assertFalse(service.revoke(token), "second revoke should find nothing to delete");
    }

    @Test
    void rawToken_isNeverPersisted() {
        SessionTokenService service = serviceAt(T0);
        String token = service.issue("user-6");

        Document session = database.getCollection("sessions").find(Filters.eq("userId", "user-6")).first();
        assertNotNull(session);
        String tokenHash = session.getString("tokenHash");
        assertNotEquals(token, tokenHash);
        assertEquals(64, tokenHash.length(), "tokenHash should be a SHA-256 hex digest");
        assertFalse(session.toJson().contains(token), "raw token must not appear anywhere in the stored document");
    }

    private static Instant expiryOf(String userId) {
        Document session = database.getCollection("sessions").find(Filters.eq("userId", userId)).first();
        assertNotNull(session);
        return session.getDate("expiresAt").toInstant();
    }
}
