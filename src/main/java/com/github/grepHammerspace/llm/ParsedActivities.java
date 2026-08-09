package com.github.grepHammerspace.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * The schema the model is constrained to when extracting OTJ entries from free text.
 *
 * <p>Passed to {@code MessageCreateParams.builder().outputConfig(ParsedActivities.class)},
 * which derives a JSON Schema from these records and guarantees the response parses into
 * this shape — so there is no hand-rolled JSON handling on the response path.
 *
 * <p>The field descriptions are part of the prompt: the model sees them, so they carry the
 * formatting rules that {@code llm_prompt.txt} used to spell out.
 */
public record ParsedActivities(

        @JsonPropertyDescription("One entry per input line that has both a duration and a description.")
        List<Entry> entries,

        @JsonPropertyDescription("One entry per input line that could not be logged.")
        List<ParseError> errors) {

    public record Entry(

            @JsonPropertyDescription("Date of the activity as YYYY/MM/DD, e.g. 2026/05/17.")
            String date,

            @JsonPropertyDescription("Whole hours spent. 0 when the activity took under an hour.")
            int hours,

            @JsonPropertyDescription("Minutes spent, 0 to 59. Never 60 or more — carry into hours.")
            int minutes,

            @JsonPropertyDescription("Start time as HH:MM with a two-digit hour, e.g. 09:00. "
                    + "Empty string when the input gives no start time.")
            String startTime,

            @JsonPropertyDescription("Plain description of what was done.")
            String comments) {
    }

    public record ParseError(

            @JsonPropertyDescription("Why this line could not be logged.")
            ErrorCode error,

            @JsonPropertyDescription("Short human-readable explanation, e.g. 'No duration found in input'.")
            String message,

            @JsonPropertyDescription("The original input line, unchanged.")
            String raw) {
    }

    /**
     * Constants are lower_snake_case so Jackson serialises them to exactly the strings the API
     * already returns to clients in {@code ActivityLogResponse} — no {@code @JsonProperty}
     * mapping to keep in sync, and the generated schema pins the same values.
     *
     * <p>Constraining this field is load-bearing: before it was an enum, Sonnet 4.6 returned
     * {@code "invalid duration, hours must be logged in working hours"} and Haiku 4.5 returned
     * {@code "invalid_duration"} for identical input, so the response body silently depended on
     * which model was configured.
     */
    public enum ErrorCode {
        missing_duration,
        missing_description,
        outside_working_hours
    }
}
