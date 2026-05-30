package com.github.grepHammerspace.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.grepHammerspace.db.model.ActivityLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmServiceTest {

    private LlmServiceImpl service;

    @BeforeEach
    void setUp() {
        // null client — processResponse does not call it
        service = new LlmServiceImpl(null);
    }

    @Test
    void validRow_mapsToActivityLog() throws JsonProcessingException {
        String json = """
                [{"date":"2026/05/30","time-spent":"2:00","start-time":"10:00","comments":"Worked on assignment","posted":""}]
                """;
        LlmResult result = service.processResponse(json, "user1", "learner1");

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
    }

    @Test
    void errorRow_mapsToLlmParseError() throws JsonProcessingException {
        String json = """
                [{"error":"missing_duration","message":"No duration found","raw":"did some work today"}]
                """;
        LlmResult result = service.processResponse(json, "user1", "learner1");

        assertEquals(0, result.ok().size());
        assertEquals(1, result.errors().size());

        LlmParseError err = result.errors().get(0);
        assertEquals("missing_duration", err.error());
        assertEquals("did some work today", err.raw());
    }

    @Test
    void mixedRows_splitCorrectly() throws JsonProcessingException {
        String json = """
                [
                  {"date":"2026/05/30","time-spent":"1:30","start-time":"09:00","comments":"Reading","posted":""},
                  {"error":"missing_description","message":"No description","raw":"1 hour"},
                  {"date":"2026/05/30","time-spent":"0:45","start-time":"14:00","comments":"Meeting","posted":""}
                ]
                """;
        LlmResult result = service.processResponse(json, "u", "l");

        assertEquals(2, result.ok().size());
        assertEquals(1, result.errors().size());
    }

    @Test
    void markdownFencesStripped() throws JsonProcessingException {
        String json = """
                ```json
                [{"date":"2026/05/30","time-spent":"1:00","start-time":"09:00","comments":"Test","posted":""}]
                ```""";
        LlmResult result = service.processResponse(json, "u", "l");
        assertEquals(1, result.ok().size());
    }

    @Test
    void markdownFencesStripped_noLanguageTag() throws JsonProcessingException {
        String json = "```\n[{\"date\":\"2026/05/30\",\"time-spent\":\"1:00\",\"start-time\":\"09:00\",\"comments\":\"Test\",\"posted\":\"\"}]\n```";
        LlmResult result = service.processResponse(json, "u", "l");
        assertEquals(1, result.ok().size());
    }

    @Test
    void invalidJson_throwsJsonProcessingException() {
        assertThrows(JsonProcessingException.class,
                () -> service.processResponse("not valid json at all", "u", "l"));
    }

    @Test
    void timeSpent_parsedCorrectly_hours() throws JsonProcessingException {
        String json = "[{\"date\":\"2026/05/30\",\"time-spent\":\"4:00\",\"start-time\":\"08:00\",\"comments\":\"Work\",\"posted\":\"\"}]";
        LlmResult result = service.processResponse(json, "u", "l");
        assertEquals(4, result.ok().get(0).hours());
        assertEquals(0, result.ok().get(0).minutes());
    }

    @Test
    void timeSpent_parsedCorrectly_minutes() throws JsonProcessingException {
        String json = "[{\"date\":\"2026/05/30\",\"time-spent\":\"0:45\",\"start-time\":\"\",\"comments\":\"Quick task\",\"posted\":\"\"}]";
        LlmResult result = service.processResponse(json, "u", "l");
        assertEquals(0, result.ok().get(0).hours());
        assertEquals(45, result.ok().get(0).minutes());
    }

    @Test
    void timeSpent_parsedCorrectly_mixed() throws JsonProcessingException {
        String json = "[{\"date\":\"2026/05/30\",\"time-spent\":\"1:30\",\"start-time\":\"13:00\",\"comments\":\"Task\",\"posted\":\"\"}]";
        LlmResult result = service.processResponse(json, "u", "l");
        assertEquals(1, result.ok().get(0).hours());
        assertEquals(30, result.ok().get(0).minutes());
    }

    @Test
    void emptyArray_returnsEmptyResult() throws JsonProcessingException {
        LlmResult result = service.processResponse("[]", "u", "l");
        assertEquals(0, result.ok().size());
        assertEquals(0, result.errors().size());
    }
}
