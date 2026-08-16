package com.github.grepHammerspace.llm;

import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.llm.ParsedActivities.Entry;
import com.github.grepHammerspace.llm.ParsedActivities.ErrorCode;
import com.github.grepHammerspace.llm.ParsedActivities.ParseError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the mapping from the model's typed output onto the persistence types.
 *
 * <p>These replace the previous tests against {@code processResponse}, which exercised
 * hand-rolled JSON handling (fence stripping, {@code time-spent} splitting, error-object
 * detection). Structured output removed that code, so what is left worth testing is the
 * field mapping itself.
 */
class LlmServiceTest {

    private LlmServiceImpl service;

    @BeforeEach
    void setUp() {
        // null client — toResult does not call it
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
    void errorMapsToLlmParseError() {
        ParsedActivities parsed = of(
                List.of(),
                List.of(new ParseError(ErrorCode.missing_duration,
                        "No duration found", "did some work today")));

        LlmResult result = service.toResult(parsed, "user1", "learner1");

        assertEquals(0, result.ok().size());
        assertEquals(1, result.errors().size());

        LlmParseError err = result.errors().get(0);
        assertEquals("missing_duration", err.error());
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
                List.of(new Entry("2026/05/30", 4, 0, "08:00", "Work"),
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
        // A line with no start time is now a missing_start_time error rather than an entry, so
        // every entry reaching this mapper carries a real HH:MM that OtjDriver can render as
        // "THH:MM:00". An empty one used to produce the unparseable "T:00" the API rejects.
        ParsedActivities parsed = of(
                List.of(new Entry("2026/05/30", 1, 0, "09:00", "Started at nine")),
                List.of());

        LlmResult result = service.toResult(parsed, "u", "l");

        assertEquals("09:00", result.ok().get(0).activityTime());
    }

    @Test
    void allErrorCodesMapToTheirWireStrings() {
        ParsedActivities parsed = of(
                List.of(),
                List.of(new ParseError(ErrorCode.missing_duration, "m", "a"),
                        new ParseError(ErrorCode.missing_description, "m", "b"),
                        new ParseError(ErrorCode.missing_start_time, "m", "c"),
                        new ParseError(ErrorCode.outside_working_hours, "m", "d")));

        LlmResult result = service.toResult(parsed, "u", "l");

        assertEquals(List.of("missing_duration", "missing_description", "missing_start_time",
                        "outside_working_hours"),
                result.errors().stream().map(LlmParseError::error).toList());
    }

    @Test
    void emptyResultReturnsEmptyLists() {
        LlmResult result = service.toResult(of(List.of(), List.of()), "u", "l");

        assertEquals(0, result.ok().size());
        assertEquals(0, result.errors().size());
    }

    @Test
    void unitIdAndActivityTypeAreLeftAtDefaults() {
        // OtjDriver supplies the real unitId and activityType when posting; the parser must not
        // invent them.
        ParsedActivities parsed = of(
                List.of(new Entry("2026/05/30", 1, 0, "09:00", "Work")),
                List.of());

        ActivityLog log = service.toResult(parsed, "u", "l").ok().get(0);

        assertEquals("", log.unitId());
        assertEquals(0, log.activityType());
    }
}
