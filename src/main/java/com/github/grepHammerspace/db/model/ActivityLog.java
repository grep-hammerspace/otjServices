package com.github.grepHammerspace.db.model;

public record ActivityLog(
        String learnerId,
        String activityImpact,
        String unitId,
        String activityDate,
        String activityTime,
        int activityType,
        int hours,
        String minutes
) {}
