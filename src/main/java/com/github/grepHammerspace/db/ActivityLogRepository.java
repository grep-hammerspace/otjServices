package com.github.grepHammerspace.db;

import com.github.grepHammerspace.db.model.ActivityLog;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
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

    public void saveActivityLog(ActivityLog activityLog){
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

        collection.insertOne(doc);
        log.info("Saved activity log for user {}", activityLog.tailscaleUserId());
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
                doc.getBoolean("posted")
        );
    }
}
