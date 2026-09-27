package com.github.grepHammerspace.db.model;

public record ActivityLog(
        String tailscaleUserId,
        String learnerId,
        String activityImpact,
        String unitId,
        String activityDate,
        String activityTime,
        int activityType,
        int hours,
        int minutes,
        boolean posted,
        String id
) {}
