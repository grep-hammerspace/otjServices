package com.github.grepHammerspace.api.dto;

import com.github.grepHammerspace.db.model.ActivityLog;

import java.util.List;

public record PendingResponse(
        List<PendingActivity> activities,
        int count,
        int totalMinutes
) {
    public static PendingResponse from(List<ActivityLog> logs) {
        List<PendingActivity> activities = logs.stream().map(PendingActivity::from).toList();
        int totalMinutes = logs.stream().mapToInt(log -> log.hours() * 60 + log.minutes()).sum();
        return new PendingResponse(activities, activities.size(), totalMinutes);
    }
}
