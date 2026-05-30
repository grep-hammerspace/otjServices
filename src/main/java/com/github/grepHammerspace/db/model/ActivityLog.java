package com.github.grepHammerspace.db.model;

/** Wrapper around a pure otj. Has all the key fields + tailscaleUserId,posted, and id for easier querying
 *  We write one of these to Mongo for each activity log that someone submits*/
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
        String id   // nullable; null for new records, populated when read from MongoDB
) {}
