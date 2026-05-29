package com.github.grepHammerspace.db.model;

/** Wrapper around a pure otj. Has all the ke fields + tailscaleUserId and posted for easier querying
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
        boolean posted
) {}
