package com.github.grepHammerspace.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.llm.ParsedActivities.Entry;
import com.github.grepHammerspace.llm.ParsedActivities.ErrorCode;
import com.github.grepHammerspace.llm.ParsedActivities.ParseError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmServiceTest {
    private LlmServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LlmServiceImpl(null);
    }

    private static ParsedActivities of(List<Entry> entries, List<ParseError> errors) {
        return new ParsedActivities(entries, errors);
    }

    @Test
    void entryMapsToActivityLog() {
        ParsedActivities parsed = of(
                List.of(new Entry("2026/05/30", 2, 0, "10:00", "Worked on assignment")),
                List.of());

        LlmResult result = service.toResult(parsed, "user1", "learner1");

        assertEquals(1, result.ok().size());
        assertEquals(0, result.errors().size());

        ActivityLog log = result.ok().get(0);
        assertEquals("2026/05/30", log.activityDate());
        assertEquals("10:00", log.activityTime());
        assertEquals("Worked on assignment", log.activityImpact());
        assertEquals(2, log.hours());
        assertEquals(0, log.minutes());
        assertEquals("user1", log.tailscaleUserId());
        assertEquals("learner1", log.learnerId());
        assertFalse(log.posted());
        assertNull(log.id());
    }

    @Test
    void errorsPassThroughUnchanged() {
        ParsedActivities parsed = of(
                List.of(),
                List.of(new ParseError(ErrorCode.missing_duration,
                        "No duration found", "did some work today")));

        LlmResult result = service.toResult(parsed, "user1", "learner1");

        assertEquals(0, result.ok().size());
        assertEquals(1, result.errors().size());

        ParseError err = result.errors().get(0);
        assertEquals(ErrorCode.missing_duration, err.error());
        assertEquals("No duration found", err.message());
        assertEquals("did some work today", err.raw());
    }

    @Test
    void mixedEntriesAndErrorsBothMapped() {
        ParsedActivities parsed = of(
                List.of(new Entry("2026/05/30", 1, 30, "09:00", "Reading"),
                        new Entry("2026/05/30", 0, 45, "14:00", "Meeting")),
                List.of(new ParseError(ErrorCode.missing_description, "No description", "1 hour")));

        LlmResult result = service.toResult(parsed, "u", "l");

        assertEquals(2, result.ok().size());
        assertEquals(1, result.errors().size());
    }

    @Test
    void entryOrderIsPreserved() {
        ParsedActivities parsed = of(
                List.of(new Entry("2026/05/30", 1, 0, "09:00", "first"),
                        new Entry("2026/05/30", 2, 0, "11:00", "second"),
                        new Entry("2026/05/30", 3, 0, "14:00", "third")),
                List.of());

        LlmResult result = service.toResult(parsed, "u", "l");

        assertEquals("first", result.ok().get(0).activityImpact());
        assertEquals("second", result.ok().get(1).activityImpact());
        assertEquals("third", result.ok().get(2).activityImpact());
    }

    @Test
    void hoursAndMinutesCopiedVerbatim() {
        ParsedActivities parsed = of(
                List.of(new Entry("2026/05/30", 4, 0, "09:30", "Work"),
                        new Entry("2026/05/30", 0, 45, "11:15", "Quick task"),
                        new Entry("2026/05/30", 1, 30, "13:00", "Task")),
                List.of());

        LlmResult result = service.toResult(parsed, "u", "l");

        assertEquals(4, result.ok().get(0).hours());
        assertEquals(0, result.ok().get(0).minutes());
        assertEquals(0, result.ok().get(1).hours());
        assertEquals(45, result.ok().get(1).minutes());
        assertEquals(1, result.ok().get(2).hours());
        assertEquals(30, result.ok().get(2).minutes());
    }

    @Test
    void startTimeCopiedVerbatim() {
        ParsedActivities parsed = of(
                List.of(new Entry("2026/05/30", 1, 0, "09:00", "Started at nine")),
                List.of());

        LlmResult result = service.toResult(parsed, "u", "l");

        assertEquals("09:00", result.ok().get(0).activityTime());
    }

    @Test
    void allErrorCodesSerialiseToTheirWireStrings() throws Exception {
        ParsedActivities parsed = of(
                List.of(),
                List.of(new ParseError(ErrorCode.missing_duration, "m", "a"),
                        new ParseError(ErrorCode.missing_description, "m", "b"),
                        new ParseError(ErrorCode.missing_start_time, "m", "c"),
                        new ParseError(ErrorCode.outside_working_hours, "m", "d"),
                        new ParseError(ErrorCode.invalid_date, "m", "e")));

        LlmResult result = service.toResult(parsed, "u", "l");

        String json = new ObjectMapper().writeValueAsString(result.errors());
        for (String code : List.of("missing_duration", "missing_description", "missing_start_time",
                "outside_working_hours", "invalid_date")) {
            assertTrue(json.contains("\"error\":\"" + code + "\""), json);
        }
    }

    @Test
    void entryWithoutStartTimeIsDroppedWhenTheModelAlsoReportedIt() {
        ParsedActivities parsed = of(
                List.of(new Entry("2026/05/30", 2, 0, "", "Worked on assignment")),
                List.of(new ParseError(ErrorCode.missing_start_time, "No start time",
                        "spent 2 hours on the assignment")));

        LlmResult result = service.toResult(parsed, "u", "l");

        assertEquals(0, result.ok().size());
        assertEquals(1, result.errors().size());
        assertEquals("spent 2 hours on the assignment", result.errors().get(0).raw());
    }

    @Test
    void entryWithoutStartTimeBecomesAnErrorWhenTheModelDidNotReportIt() {
        ParsedActivities parsed = of(
                List.of(new Entry("2026/05/30", 2, 0, " ", "Worked on assignment"),
                        new Entry("2026/05/30", 1, 0, null, "Reading")),
                List.of());

        LlmResult result = service.toResult(parsed, "u", "l");

        assertEquals(0, result.ok().size());
        assertEquals(List.of("missing_start_time", "missing_start_time"),
                result.errors().stream().map(e -> e.error().name()).toList());
        assertEquals("Worked on assignment", result.errors().get(0).raw());
    }

    @Test
    void oneReportedErrorCoversOnlyOneDroppedEntry() {
        ParsedActivities parsed = of(
                List.of(new Entry("2026/05/30", 2, 0, "", "first"),
                        new Entry("2026/05/30", 1, 0, "", "second")),
                List.of(new ParseError(ErrorCode.missing_start_time, "m", "first line")));

        LlmResult result = service.toResult(parsed, "u", "l");

        assertEquals(0, result.ok().size());
        assertEquals(List.of("first line", "second"),
                result.errors().stream().map(ParseError::raw).toList());
    }

    @Test
    void zeroDurationEntryIsDropped() {
        ParsedActivities parsed = of(
                List.of(new Entry("2026/05/30", 0, 0, "10:00", "Did some work"),
                        new Entry("2026/05/30", 1, 0, "11:00", "Reading")),
                List.of(new ParseError(ErrorCode.missing_duration, "m", "did some work at 10")));

        LlmResult result = service.toResult(parsed, "u", "l");

        assertEquals(List.of("Reading"), result.ok().stream().map(ActivityLog::activityImpact).toList());
        assertEquals(1, result.errors().size());
    }

    @Test
    void everyIncompleteEntryIsDroppedWithTheMatchingCode() {
        ParsedActivities parsed = of(
                List.of(new Entry("2026/05/30", 1, 0, "10:00", " "),
                        new Entry("2026/05/30", 1, 0, "07:00", "Early start"),
                        new Entry("2099/01/01", 1, 0, "10:00", "Time travel"),
                        new Entry("30/05/2026", 1, 0, "10:00", "Wrong form"),
                        new Entry("2026/05/30", 1, 0, "9:00", "Single-digit hour")),
                List.of());

        LlmResult result = service.toResult(parsed, "u", "l");

        assertEquals(0, result.ok().size());
        assertEquals(List.of("missing_description", "outside_working_hours", "invalid_date",
                        "invalid_date", "missing_start_time"),
                result.errors().stream().map(e -> e.error().name()).toList());
    }

    @Test
    void emptyResultReturnsEmptyLists() {
        LlmResult result = service.toResult(of(List.of(), List.of()), "u", "l");

        assertEquals(0, result.ok().size());
        assertEquals(0, result.errors().size());
    }
}
