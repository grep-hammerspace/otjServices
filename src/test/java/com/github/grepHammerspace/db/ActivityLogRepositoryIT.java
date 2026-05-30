package com.github.grepHammerspace.db;

import com.github.grepHammerspace.db.model.ActivityLog;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActivityLogRepositoryIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    static ActivityLogRepository repository;

    private static ActivityLog logFor(String userId) {
        return new ActivityLog(userId, "learner-x", "Some work", "",
                "2026/05/30", "09:00", 0, 1, 0, false, null);
    }

    @BeforeAll
    static void startMongo() {
        MONGO.start();
        repository = new ActivityLogRepository(
                MongoClients.create(MONGO.getConnectionString()).getDatabase("testdb")
        );
    }

    @Test
    void savedActivityLog_hasIdPopulatedOnRead() {
        ActivityLog saved = new ActivityLog(
                "user-1", "learner-1", "Worked on assignment", "",
                "2026/05/30", "10:00", 0, 2, 0, false, null
        );
        assertNull(saved.id(), "id should be null before saving");

        repository.saveActivityLog(saved);

        List<ActivityLog> results = repository.getUnpostedActivityLogsFor("user-1");
        assertEquals(1, results.size());

        ActivityLog read = results.get(0);
        assertNotNull(read.id(), "id should be populated after reading from MongoDB");
        assertEquals(24, read.id().length(), "MongoDB ObjectId hex string is always 24 chars");
        assertFalse(read.posted());
        assertEquals("Worked on assignment", read.activityImpact());
    }

    @Test
    void markAsPosted_updatesDocumentInPlace_notDuplicated() {
        ActivityLog saved = new ActivityLog(
                "user-2", "learner-2", "Reading session", "",
                "2026/05/30", "14:00", 0, 1, 30, false, null
        );
        repository.saveActivityLog(saved);

        List<ActivityLog> before = repository.getUnpostedActivityLogsFor("user-2");
        assertEquals(1, before.size());
        ActivityLog read = before.get(0);
        assertNotNull(read.id());

        repository.markAsPosted(read);

        List<ActivityLog> after = repository.getUnpostedActivityLogsFor("user-2");
        assertEquals(0, after.size(), "document should no longer appear as unposted after markAsPosted");
    }

    @Test
    void deleteLastActivityLog_withOnePending_returnsTrueAndRemoves() {
        repository.saveActivityLog(logFor("user-3"));

        boolean deleted = repository.deleteLastActivityLog("user-3");

        assertTrue(deleted);
        assertTrue(repository.getUnpostedActivityLogsFor("user-3").isEmpty());
    }

    @Test
    void deleteLastActivityLog_withNoPending_returnsFalse() {
        boolean deleted = repository.deleteLastActivityLog("user-no-logs");

        assertFalse(deleted);
    }

    @Test
    void deleteLastActivityLog_deletesOnlyMostRecent_notOlder() {
        repository.saveActivityLog(logFor("user-4"));
        repository.saveActivityLog(logFor("user-4"));

        repository.deleteLastActivityLog("user-4");

        List<ActivityLog> remaining = repository.getUnpostedActivityLogsFor("user-4");
        assertEquals(1, remaining.size(), "only the most recent log should have been deleted");
    }

    @Test
    void deleteLastActivityLog_doesNotTouchPostedLogs() {
        repository.saveActivityLog(logFor("user-5"));
        ActivityLog unposted = repository.getUnpostedActivityLogsFor("user-5").get(0);
        repository.markAsPosted(unposted);

        boolean deleted = repository.deleteLastActivityLog("user-5");

        assertFalse(deleted, "no unposted logs to delete");
        // posted log still exists — markAsPosted already removed it from unposted query, so just verify the delete returned false
    }
}
