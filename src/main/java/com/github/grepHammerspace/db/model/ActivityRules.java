package com.github.grepHammerspace.db.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

// What a row needs before it may be stored, whichever path writes it.
public final class ActivityRules {
    public enum Kind { DATE, START_TIME, WORKING_HOURS, DURATION, DESCRIPTION }

    public record Violation(Kind kind, String message) {}

    private static final Pattern DATE = Pattern.compile("^\\d{4}/\\d{2}/\\d{2}$");
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    // STRICT with uuuu, so 2026/02/30 is rejected rather than quietly resolved.
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT);

    private static final LocalTime EARLIEST = LocalTime.of(9, 0);
    private static final LocalTime LATEST = LocalTime.of(18, 0);

    private ActivityRules() {}

    public static Violation check(ActivityLog row) {
        return check(row.activityDate(), row.activityTime(), row.hours(), row.minutes(), row.activityImpact());
    }

    public static Violation check(String date, String time, int hours, int minutes, String impact) {
        if (date == null || !DATE.matcher(date).matches()) {
            return new Violation(Kind.DATE, "'activityDate' must be a date in YYYY/MM/DD form. Got: " + date + ".");
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(date, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return new Violation(Kind.DATE, "'" + date + "' is not a real calendar date.");
        }
        if (parsed.isAfter(LocalDate.now())) {
            return new Violation(Kind.DATE, "'activityDate' cannot be in the future. Got: " + date + ".");
        }

        if (time == null || time.isBlank()) {
            return new Violation(Kind.START_TIME,
                    "'activityTime' is missing or empty. Expected the HH:MM the activity started.");
        }
        if (!TIME.matcher(time).matches()) {
            return new Violation(Kind.START_TIME,
                    "'activityTime' must be a time in HH:MM form. Got: " + time + ".");
        }
        LocalTime start = LocalTime.parse(time);
        if (start.isBefore(EARLIEST) || start.isAfter(LATEST)) {
            return new Violation(Kind.WORKING_HOURS,
                    "'activityTime' must be between 09:00 and 18:00. Got: " + time + ".");
        }

        // A sanity bound, not a duration ceiling: the parser applies none either.
        if (hours < 0 || hours > 24) {
            return new Violation(Kind.DURATION, "'hours' must be between 0 and 24. Got: " + hours + ".");
        }
        if (minutes < 0 || minutes > 59) {
            return new Violation(Kind.DURATION,
                    "'minutes' must be between 0 and 59 — carry 60 or more into 'hours'. Got: " + minutes + ".");
        }
        if (hours * 60 + minutes <= 0) {
            return new Violation(Kind.DURATION, "An activity must last longer than zero minutes. " +
                    "Got: hours=" + hours + ", minutes=" + minutes + ".");
        }

        if (impact == null || impact.isBlank()) {
            return new Violation(Kind.DESCRIPTION,
                    "'activityImpact' is missing or empty. Expected a description of what you did.");
        }
        return null;
    }
}
