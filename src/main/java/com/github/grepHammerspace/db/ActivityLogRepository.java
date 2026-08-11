package com.github.grepHammerspace.db;

import com.github.grepHammerspace.db.model.ActivityLog;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
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

        Bson filter = Filters.and(
            Filters.eq("tailscaleUserId", userId),
            Filters.eq("posted", false)
        );

        return collection.find(filter)
                .map(document -> fromDoc(document))
                .into(new ArrayList<>());
    }

    /** Unposted logs for the user, newest first (_id descending).
     *
     *  <p>Deliberately separate from {@link #getUnpostedActivityLogsFor}: that one feeds
     *  {@code OtjDriver:188} and {@code AzureIdDriver:508}, which use its order to decide the
     *  order rows reach OneAdvanced. Adding a sort there would silently reorder submissions. */
    public List<ActivityLog> findUnpostedNewestFirst(String userId) {
        Bson filter = Filters.and(
            Filters.eq("tailscaleUserId", userId),
            Filters.eq("posted", false)
        );

        return collection.find(filter)
                .sort(Sorts.descending("_id"))
                .map(this::fromDoc)
                .into(new ArrayList<>());
    }

    /** Deletes one unposted log owned by {@code userId}.
     *
     *  <p>Returns {@code false} when the filter matched nothing — unknown id, someone else's id,
     *  or already posted. Ownership is part of the filter rather than a check after the read, so
     *  a caller can never learn that an id they do not own exists. */
    public boolean deleteUnpostedById(String userId, ObjectId id) {
        Document deleted = collection.findOneAndDelete(
                Filters.and(
                        Filters.eq("_id", id),
                        Filters.eq("tailscaleUserId", userId),
                        Filters.eq("posted", false)
                )
        );
        if (deleted != null) {
            log.info("Deleted activity log {} for user {}", id.toHexString(), userId);
            return true;
        }
        log.info("No unposted activity log {} found to delete for user {}", id.toHexString(), userId);
        return false;
    }

    /** Deletes the most recently inserted unposted activity log for the user. Returns {@code true} if one was found and deleted. */
    public boolean deleteLastActivityLog(String userId) {
        Document deleted = collection.findOneAndDelete(
                Filters.and(Filters.eq("tailscaleUserId", userId), Filters.eq("posted", false)),
                new com.mongodb.client.model.FindOneAndDeleteOptions().sort(Sorts.descending("_id"))
        );
        if (deleted != null) {
            log.info("Deleted last activity log for user {}", userId);
            return true;
        }
        log.info("No unposted activity log found to delete for user {}", userId);
        return false;
    }

    /** Inserts the log and returns it with the generated {@code _id} populated, so a caller can
     *  hand the client a row it can immediately address with {@code DELETE /pending/{id}}. */
    public ActivityLog saveActivityLog(ActivityLog activityLog){
        Document doc = new Document()
                .append("tailscaleUserId", activityLog.tailscaleUserId())
                .append("learnerId", activityLog.learnerId())
                .append("activityImpact", activityLog.activityImpact())
                .append("unitId", activityLog.unitId())
                .append("activityDate", activityLog.activityDate())
                .append("activityTime", activityLog.activityTime())
                .append("activityType", activityLog.activityType())
                .append("hours", activityLog.hours())
                .append("minutes", activityLog.minutes())
                .append("posted", activityLog.posted());

        // insertOne mutates doc with the generated _id, so no follow-up read is needed.
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

    private ActivityLog fromDoc(Document doc) {
        return new ActivityLog(
                doc.getString("tailscaleUserId"),
                doc.getString("learnerId"),
                doc.getString("activityImpact"),
                doc.getString("unitId"),
                doc.getString("activityDate"),
                doc.getString("activityTime"),
                doc.getInteger("activityType"),
                doc.getInteger("hours"),
                doc.getInteger("minutes"),
                doc.getBoolean("posted"),
                doc.getObjectId("_id").toHexString()
        );
    }
}
