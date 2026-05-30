package com.github.grepHammerspace.db;

import com.github.grepHammerspace.db.model.User;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;

import static org.junit.jupiter.api.Assertions.*;

class UserRepositoryIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    static UserRepository repository;
    static MongoDatabase database;

    @BeforeAll
    static void startMongo() {
        MONGO.start();
        database = MongoClients.create(MONGO.getConnectionString()).getDatabase("testdb");
        repository = new UserRepository(database);
    }

    @Test
    void save_then_findByUserId_returnsUser() {
        repository.save(new User("uid-1", "alice", "secret", "L001"));

        User found = repository.findByUserId("uid-1");
        assertNotNull(found);
        assertEquals("alice", found.username());
        assertEquals("secret", found.password());
        assertEquals("L001", found.learnerId());
    }

    @Test
    void save_twice_upsertsNotDuplicates() {
        repository.save(new User("uid-2", "bob", "pass1", "L002"));
        repository.save(new User("uid-2", "bob", "pass2", "L003"));

        User found = repository.findByUserId("uid-2");
        assertNotNull(found);
        assertEquals("pass2", found.password());
        assertEquals("L003", found.learnerId());

        long count = database.getCollection("users").countDocuments(Filters.eq("userId", "uid-2"));
        assertEquals(1, count, "upsert should not create a duplicate document");
    }

    @Test
    void findByUserId_unknownUser_returnsNull() {
        assertNull(repository.findByUserId("uid-does-not-exist"));
    }

    @Test
    void saveLastContent_then_getLastContent_returnsValue() {
        repository.save(new User("uid-3", "carol", "p", "L003"));
        repository.saveLastContent("uid-3", "my notes");

        assertEquals("my notes", repository.getLastContent("uid-3"));
    }

    @Test
    void clearLastContent_removesField() {
        repository.save(new User("uid-4", "dave", "p", "L004"));
        repository.saveLastContent("uid-4", "some content");
        repository.clearLastContent("uid-4");

        assertNull(repository.getLastContent("uid-4"), "getLastContent should return null after clear");
    }

    @Test
    void getLastContent_withNoContentSaved_returnsNull() {
        repository.save(new User("uid-5", "eve", "p", "L005"));

        assertNull(repository.getLastContent("uid-5"));
    }
}
