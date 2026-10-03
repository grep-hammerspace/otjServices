package com.github.grepHammerspace.api.dto;

import com.github.grepHammerspace.db.model.ActivityRules;

import java.util.regex.Pattern;

public record UpdateActivityRequest(
        String activityDate,
        String activityTime,
        int hours,
        int minutes,
        String activityImpact
) {
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private static final int MAX_IMPACT_CHARS = 1000;

    public UpdateActivityRequest normalised() {
        return new UpdateActivityRequest(
                activityDate == null ? null : activityDate.strip(),
                activityTime == null ? null : activityTime.strip(),
                hours,
                minutes,
                activityImpact == null ? null
                        : WHITESPACE_RUN.matcher(activityImpact.strip()).replaceAll(" "));
    }

    // Hand-written rather than bean validation, so the specific reason reaches the user as
    // {"error": ...}.
    public String validationError() {
        ActivityRules.Violation violation =
                ActivityRules.check(activityDate, activityTime, hours, minutes, activityImpact);
        if (violation != null) {
            return violation.message();
        }
        if (activityImpact.length() > MAX_IMPACT_CHARS) {
            return "'activityImpact' must be " + MAX_IMPACT_CHARS + " characters or fewer. " +
                    "Got: " + activityImpact.length() + ".";
        }
        return null;
    }
}
