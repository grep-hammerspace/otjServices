package com.github.grepHammerspace.db.model;

public record ActivityLog(
        String tailscaleUserId,
        String learnerId,
        String activityImpact,
        String activityDate,
        String activityTime,
        int hours,
        int minutes,
        boolean posted,
        String id
) {}
