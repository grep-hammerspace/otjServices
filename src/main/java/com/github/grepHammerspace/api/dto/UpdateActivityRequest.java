package com.github.grepHammerspace.api.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

/** The editable fields of one pending activity, as {@code PUT /otj-services/pending/{id}} takes them.
 *
 * <p>PUT rather than PATCH: the only client holds the whole row and puts every editable field on
 * screen at once, so replacing the editable subset avoids the absent-versus-null ambiguity a
 * partial update carries. All five fields are required; {@code activityTime} may be {@code ""}.
 *
 * <p>{@code hours} and {@code minutes} are primitive {@code int}, so a missing key deserialises to
 * 0 rather than null — which the "total must be > 0" rule then rejects with a real message. A boxed
 * {@code Integer} would tell "absent" from "zero", but there is no useful difference between them
 * here.
 */
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

    /** STRICT with {@code uuuu} so that 2026/02/30 is rejected rather than quietly resolved. */
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT);

    /** Prompt rule 5 bounds a given start time to working hours. */
    private static final LocalTime EARLIEST = LocalTime.of(9, 0);
    private static final LocalTime LATEST = LocalTime.of(18, 0);

    private static final int MAX_IMPACT_CHARS = 1000;

    /** The same request with whitespace tidied: this is what gets stored, and what the length and
     *  blankness rules below are applied to.
     *
     *  <p>The mobile client normalises before it sends — zero-padding, minutes carried into hours,
     *  whitespace collapsed — so that a bad value shows up in the field rather than after a round
     *  trip. The server must not rely on any of that; {@code scripts/otj} and curl are callers too. */
    public UpdateActivityRequest normalised() {
        return new UpdateActivityRequest(
                activityDate == null ? null : activityDate.strip(),
                activityTime == null ? null : activityTime.strip(),
                hours,
                minutes,
                activityImpact == null ? null
                        : WHITESPACE_RUN.matcher(activityImpact.strip()).replaceAll(" "));
    }

    /** Returns the one thing wrong with this request, phrased for the user, or {@code null} when it
     *  is valid. Call on a {@link #normalised()} instance.
     *
     *  <p>Hand-written rather than bean validation on purpose: the mobile client's
     *  {@code errorMessage()} prefers the server's own {@code {"error": "..."}} body and falls back
     *  to a generic string for anything else, so a constraint violation would reach the user as
     *  "Please check the details you entered and try again." and the specific reason would be lost.
     *  {@code logActivities} hand-checks blank {@code content} for the same reason.
     *
     *  <p>Every rule here already exists elsewhere in the system — in the JSON schema the model is
     *  constrained to, in {@code llm_prompt.txt}, or in the payload {@code OtjDriver} builds — but
     *  none of it was reachable as code that can check an incoming body. The point is that a
     *  hand-edited row cannot end up less valid than a parsed one. */
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

        // Empty is the existing convention for "the entry never mentioned a start time", and
        // clearing the field is a thing the edit sheet lets a user do deliberately.
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

        // A sanity bound, not a duration ceiling: it only stops an absurd integer reaching Mongo.
        // The parser applies no ceiling — it bounds a *start time* to working hours and never checks
        // that the duration fits inside them, so a 12-hour entry can be logged through
        // log-activities today. Matching that keeps one rule set across both paths. If a real
        // ceiling is ever wanted, apply it in both places at once.
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
