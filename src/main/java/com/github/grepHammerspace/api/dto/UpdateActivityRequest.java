package com.github.grepHammerspace.api.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

public record UpdateActivityRequest(
        String activityDate,
        String activityTime,
        int hours,
        int minutes,
        String activityImpact
) {
    private static final Pattern DATE = Pattern.compile("^\\d{4}/\\d{2}/\\d{2}$");
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    // STRICT with uuuu, so 2026/02/30 is rejected rather than quietly resolved.
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT);

    private static final LocalTime EARLIEST = LocalTime.of(9, 0);
    private static final LocalTime LATEST = LocalTime.of(18, 0);

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
        if (activityDate == null || !DATE.matcher(activityDate).matches()) {
            return "'activityDate' must be a date in YYYY/MM/DD form. Got: " + activityDate + ".";
        }
        LocalDate date;
        try {
            date = LocalDate.parse(activityDate, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return "'" + activityDate + "' is not a real calendar date.";
        }
        if (date.isAfter(LocalDate.now())) {
            return "'activityDate' cannot be in the future. Got: " + activityDate + ".";
        }

        if (activityTime == null) {
            return "'activityTime' must be a time in HH:MM form, or empty for no start time.";
        }
        if (!activityTime.isEmpty()) {
            if (!TIME.matcher(activityTime).matches()) {
                return "'activityTime' must be a time in HH:MM form, or empty for no start time. " +
                        "Got: " + activityTime + ".";
            }
            LocalTime time = LocalTime.parse(activityTime);
            if (time.isBefore(EARLIEST) || time.isAfter(LATEST)) {
                return "'activityTime' must be between 09:00 and 18:00. Got: " + activityTime + ".";
            }
        }

        // A sanity bound, not a duration ceiling: the parser applies none either.
        if (hours < 0 || hours > 24) {
            return "'hours' must be between 0 and 24. Got: " + hours + ".";
        }
        if (minutes < 0 || minutes > 59) {
            return "'minutes' must be between 0 and 59 — carry 60 or more into 'hours'. " +
                    "Got: " + minutes + ".";
        }
        if (hours * 60 + minutes <= 0) {
            return "An activity must last longer than zero minutes. " +
                    "Got: hours=" + hours + ", minutes=" + minutes + ".";
        }

        if (activityImpact == null || activityImpact.isBlank()) {
            return "'activityImpact' is missing or empty. Expected a description of what you did.";
        }
        if (activityImpact.length() > MAX_IMPACT_CHARS) {
            return "'activityImpact' must be " + MAX_IMPACT_CHARS + " characters or fewer. " +
                    "Got: " + activityImpact.length() + ".";
        }

        return null;
    }
}
