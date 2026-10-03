package com.github.grepHammerspace.db;

import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.db.model.ActivityRules;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class ActivityLogRepository {
    private static final Logger log = LoggerFactory.getLogger(ActivityLogRepository.class);

    private final MongoCollection<Document> collection;

    @Inject
    public ActivityLogRepository(MongoDatabase database) {
        this.collection = database.getCollection("activitylogs");
    }

    public List<ActivityLog> getUnpostedActivityLogsFor(String userId){
        return collection.find(unposted(userId))
                .map(this::fromDoc)
                .into(new ArrayList<>());
    }

    // Separate from getUnpostedActivityLogsFor, whose order decides submission order: don't add a
    // sort there.
    public List<ActivityLog> findUnpostedNewestFirst(String userId) {
        return collection.find(unposted(userId))
                .sort(Sorts.descending("_id"))
                .map(this::fromDoc)
                .into(new ArrayList<>());
    }

    // Ownership is in the filter, so a caller can't learn that an id it doesn't own exists.
    public boolean deleteUnpostedById(String userId, ObjectId id) {
        Document deleted = collection.findOneAndDelete(Filters.and(Filters.eq("_id", id), unposted(userId)));
        if (deleted != null) {
            log.info("Deleted activity log {} for user {}", id.toHexString(), userId);
            return true;
        }
        log.info("No unposted activity log {} found to delete for user {}", id.toHexString(), userId);
        return false;
    }

    // Ownership and posted are in the filter: no id-existence oracle, and an edit racing a
    // submission matches nothing. Only the five editable fields are set.
    public ActivityLog updateUnpostedById(String userId, ObjectId id,
                                          String activityDate, String activityTime,
                                          int hours, int minutes, String activityImpact) {
        requireComplete(ActivityRules.check(activityDate, activityTime, hours, minutes, activityImpact));
        Document updated = collection.findOneAndUpdate(
                Filters.and(Filters.eq("_id", id), unposted(userId)),
                Updates.combine(
                        Updates.set("activityDate", activityDate),
                        Updates.set("activityTime", activityTime),
                        Updates.set("hours", hours),
                        Updates.set("minutes", minutes),
                        Updates.set("activityImpact", activityImpact)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));

        if (updated == null) {
            log.info("No unposted activity log {} found to update for user {}", id.toHexString(), userId);
            return null;
        }
        log.info("Updated activity log {} for user {}", id.toHexString(), userId);
        return fromDoc(updated);
    }

    public ActivityLog saveActivityLog(ActivityLog activityLog){
        requireComplete(ActivityRules.check(activityLog));
        Document doc = new Document()
                .append("tailscaleUserId", activityLog.tailscaleUserId())
                .append("learnerId", activityLog.learnerId())
                .append("activityImpact", activityLog.activityImpact())
                .append("activityDate", activityLog.activityDate())
                .append("activityTime", activityLog.activityTime())
                .append("hours", activityLog.hours())
                .append("minutes", activityLog.minutes())
                .append("posted", activityLog.posted());

        collection.insertOne(doc);
        log.info("Saved activity log for user {}", activityLog.tailscaleUserId());
        return fromDoc(doc);
    }

    public void markAsPosted(ActivityLog activityLog) {
        if (activityLog.id() == null) {
            throw new IllegalArgumentException("Cannot mark as posted: ActivityLog has no id (was it read from the database?)");
        }
        collection.updateOne(
                Filters.eq("_id", new ObjectId(activityLog.id())),
                Updates.set("posted", true)
        );
        log.info("Marked activity log {} as posted for user {}", activityLog.id(), activityLog.tailscaleUserId());
    }

    // Callers validate first for a friendly message; this keeps any path from storing an
    // incomplete row.
    private static void requireComplete(ActivityRules.Violation violation) {
        if (violation != null) {
            throw new IllegalArgumentException("Refusing to store an incomplete activity log: " + violation.message());
        }
    }

    private static Bson unposted(String userId) {
        return Filters.and(Filters.eq("tailscaleUserId", userId), Filters.eq("posted", false));
    }

    private ActivityLog fromDoc(Document doc) {
        return new ActivityLog(
                doc.getString("tailscaleUserId"),
                doc.getString("learnerId"),
                doc.getString("activityImpact"),
                doc.getString("activityDate"),
                doc.getString("activityTime"),
                doc.getInteger("hours"),
                doc.getInteger("minutes"),
                doc.getBoolean("posted"),
                doc.getObjectId("_id").toHexString()
        );
    }
}
