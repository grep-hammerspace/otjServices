package com.github.grepHammerspace.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

// The model sees these field descriptions: they are part of the prompt.
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
                    + "Never empty: a line with no start time is a missing_start_time error, "
                    + "not an entry.")
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

    // An enum on purpose: as free text, the value differed between models for the same input.
    public enum ErrorCode {
        missing_duration,
        missing_description,
        missing_start_time,
        outside_working_hours,
        invalid_date,
        weekend,
        description_too_long
    }
}
