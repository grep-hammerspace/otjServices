package com.github.grepHammerspace.api.dto;

import com.github.grepHammerspace.db.model.ActivityLog;
import org.bson.types.ObjectId;

import java.time.format.DateTimeFormatter;

// Deliberately narrower than ActivityLog: the client never receives the server-minted userId.
public record PendingActivity(
        String id,
        String activityDate,
        String activityTime,
        int hours,
        int minutes,
        String activityImpact,
        String createdAt
) {
    public static PendingActivity from(ActivityLog log) {
        if (log.id() == null) {
            throw new IllegalArgumentException(
                    "Cannot map an ActivityLog with no id — was it read back from MongoDB?");
        }
        return new PendingActivity(
                log.id(),
                log.activityDate(),
                // Empty rather than null: the client's ActivityRow type expects a string.
                log.activityTime() == null ? "" : log.activityTime(),
                log.hours(),
                log.minutes(),
                log.activityImpact(),
                DateTimeFormatter.ISO_INSTANT.format(new ObjectId(log.id()).getDate().toInstant())
        );
    }
}
