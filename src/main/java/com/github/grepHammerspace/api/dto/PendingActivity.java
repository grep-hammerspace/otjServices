package com.github.grepHammerspace.api.dto;

import com.github.grepHammerspace.db.model.ActivityLog;
import org.bson.types.ObjectId;

import java.time.format.DateTimeFormatter;

/** One unposted activity row as the client sees it.
 *
 * <p>Deliberately narrower than {@link ActivityLog}. {@code tailscaleUserId} is omitted because
 * the client never receives the server-minted userId; {@code posted} because it is false by
 * construction of every endpoint that returns this; {@code learnerId} because it is a per-user
 * constant the client already knows; {@code unitId} and {@code activityType} because nothing
 * has ever set them to anything but {@code ""} and {@code 0}.
 */
public record PendingActivity(
        String id,
        String activityDate,
        String activityTime,
        int hours,
        int minutes,
        String activityImpact,
        String createdAt
) {

    /** Maps a stored log onto the wire shape.
     *
     * <p>{@code createdAt} is derived from the ObjectId's embedded timestamp rather than a stored
     * field — free, and second precision is enough for "added 5 minutes ago". Requires an id, so
     * this only accepts logs that have been read back from Mongo.
     */
    public static PendingActivity from(ActivityLog log) {
        if (log.id() == null) {
            throw new IllegalArgumentException(
                    "Cannot map an ActivityLog with no id — was it read back from MongoDB?");
        }
        return new PendingActivity(
                log.id(),
                log.activityDate(),
                // The model may omit a start time. Empty string is the existing convention on this
                // field and what the mobile ActivityRow type encodes; null would break it.
                log.activityTime() == null ? "" : log.activityTime(),
                log.hours(),
                log.minutes(),
                log.activityImpact(),
                DateTimeFormatter.ISO_INSTANT.format(new ObjectId(log.id()).getDate().toInstant())
        );
    }
}
