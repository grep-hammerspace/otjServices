package com.github.grepHammerspace.db;

import com.github.grepHammerspace.db.model.User;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class UserRepositoryIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    static UserRepository repository;
    static MongoDatabase database;

    static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeAll
    static void startMongo() {
        MONGO.start();
        database = MongoClients.create(MONGO.getConnectionString()).getDatabase("testdb");
        repository = new UserRepository(database);
    }

    private static User user(String userId, String appUsername) {
        return new User(userId, appUsername, "$2a$12$fakehashfortesting", "L-" + userId, CREATED);
    }

    @Test
    void save_then_findByUserId_returnsUser() {
        repository.save(user("uid-1", "alice"));

        User found = repository.findByUserId("uid-1");
        assertNotNull(found);
        assertEquals("alice", found.appUsername());
        assertEquals("$2a$12$fakehashfortesting", found.appPasswordHash());
        assertEquals("L-uid-1", found.learnerId());
        assertEquals(CREATED, found.createdAt().truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void save_twice_upsertsNotDuplicates() {
        repository.save(new User("uid-2", "bob", "hash1", "L002", CREATED));
        repository.save(new User("uid-2", "bob", "hash2", "L003", CREATED));

        User found = repository.findByUserId("uid-2");
        assertNotNull(found);
        assertEquals("hash2", found.appPasswordHash());
        assertEquals("L003", found.learnerId());

        long count = database.getCollection("users").countDocuments(Filters.eq("userId", "uid-2"));
        assertEquals(1, count, "upsert should not create a duplicate document");
    }

    @Test
    void findByUserId_unknownUser_returnsNull() {
        assertNull(repository.findByUserId("uid-does-not-exist"));
    }

    @Test
    void findByAppUsername_returnsUser() {
        repository.save(user("uid-login", "login-name"));

        User found = repository.findByAppUsername("login-name");
        assertNotNull(found);
        assertEquals("uid-login", found.userId());
    }

    @Test
    void findByAppUsername_unknown_returnsNull() {
        assertNull(repository.findByAppUsername("nobody"));
    }

    @Test
    void insert_newUser_returnsTrue() {
        assertTrue(repository.insert(user("uid-new", "fresh-name")));
        assertNotNull(repository.findByUserId("uid-new"));
    }

    @Test
    void insert_duplicateAppUsername_failsClosed() {
        assertTrue(repository.insert(user("uid-first", "taken-name")));
        assertFalse(repository.insert(user("uid-second", "taken-name")),
                "second insert with the same appUsername must be rejected");

        assertNull(repository.findByUserId("uid-second"), "rejected insert must not persist anything");
    }

    @Test
    void saveLastContent_then_getLastContent_returnsValue() {
        repository.save(user("uid-3", "carol"));
        repository.saveLastContent("uid-3", "my notes");

        assertEquals("my notes", repository.getLastContent("uid-3"));
    }

    @Test
    void clearLastContent_removesField() {
        repository.save(user("uid-4", "dave"));
        repository.saveLastContent("uid-4", "some content");
        repository.clearLastContent("uid-4");

        assertNull(repository.getLastContent("uid-4"), "getLastContent should return null after clear");
    }

    @Test
    void getLastContent_withNoContentSaved_returnsNull() {
        repository.save(user("uid-5", "eve"));

        assertNull(repository.getLastContent("uid-5"));
    }

    @Test
    void storedDocument_containsNoRecoverablePassword() {
        repository.save(user("uid-6", "frank"));

        org.bson.Document doc = database.getCollection("users").find(Filters.eq("userId", "uid-6")).first();
        assertNotNull(doc);
        assertNull(doc.get("password"), "legacy recoverable password field must not exist");
        assertTrue(doc.getString("appPasswordHash").startsWith("$2a$"), "only a bcrypt hash may be stored");
    }
}
