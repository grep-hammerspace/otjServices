package com.github.grepHammerspace.db;

import com.github.grepHammerspace.SingleUser;
import com.github.grepHammerspace.db.model.User;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void emptyUsers() {
        database.getCollection("users").deleteMany(new org.bson.Document());
    }

    @Test
    void ensureSingleUser_createsTheAccountWithNoLearnerId() {
        repository.ensureSingleUser();

        User found = repository.findByUserId(SingleUser.USER_ID);
        assertNotNull(found);
        assertEquals(SingleUser.USERNAME, found.appUsername());
        assertNull(found.learnerId());
        assertNotNull(found.createdAt());
    }

    @Test
    void ensureSingleUser_isIdempotent() {
        repository.ensureSingleUser();
        repository.ensureSingleUser();

        assertEquals(1, database.getCollection("users").countDocuments());
    }

    @Test
    void ensureSingleUser_keepsALearnerIdAlreadySet() {
        repository.ensureSingleUser();
        repository.updateLearnerId(SingleUser.USER_ID, "L-KEEP");
        User before = repository.findByUserId(SingleUser.USER_ID);

        repository.ensureSingleUser();

        User after = repository.findByUserId(SingleUser.USER_ID);
        assertEquals("L-KEEP", after.learnerId());
        assertEquals(before.createdAt(), after.createdAt());
    }

    @Test
    void findByUserId_unknownUser_returnsNull() {
        assertNull(repository.findByUserId("no-such-user"));
    }

    @Test
    void updateLearnerId_returnsUpdatedUser() {
        repository.ensureSingleUser();

        User updated = repository.updateLearnerId(SingleUser.USER_ID, "L-NEW");

        assertNotNull(updated);
        assertEquals("L-NEW", updated.learnerId());
        assertEquals(SingleUser.USERNAME, updated.appUsername());
    }

    @Test
    void updateLearnerId_unknownUser_returnsNullAndInsertsNothing() {
        assertNull(repository.updateLearnerId("ghost", "L-NOPE"));
        assertEquals(0, database.getCollection("users").countDocuments());
    }
}
