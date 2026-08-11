package com.github.grepHammerspace.api.dto;

import com.github.grepHammerspace.db.model.ActivityLog;

import java.util.List;

/** Body of {@code GET /otj-services/pending}.
 *
 * <p>An empty queue is {@code {"activities": [], "count": 0, "totalMinutes": 0}} with a 200, not
 * a 404 — an empty queue is the normal steady state, and a 404 would make the mobile client's
 * {@code apiJson} throw on the happy path.
 */
public record PendingResponse(
        List<PendingActivity> activities,
        int count,
        int totalMinutes
) {

    /** {@code totalMinutes} lives here so the screen can show "3h 45m queued" without the client
     *  re-deriving it, and so the resource stays a thin handler. */
    public static PendingResponse from(List<ActivityLog> logs) {
        List<PendingActivity> activities = logs.stream().map(PendingActivity::from).toList();
        int totalMinutes = logs.stream().mapToInt(log -> log.hours() * 60 + log.minutes()).sum();
        return new PendingResponse(activities, activities.size(), totalMinutes);
    }
}
