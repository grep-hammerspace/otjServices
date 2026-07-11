package com.github.grepHammerspace.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KsbMatcherServiceImplTest {

    private KsbMatcherServiceImpl service;

    @BeforeEach
    void setUp() {
        // null client — processResponse does not call it
        service = new KsbMatcherServiceImpl(null);
    }

    @Test
    void validMatch_mapsToKsbMatch() throws JsonProcessingException {
        String json = """
                [{"activityDate":"2026/05/30","activitySummary":"Debugged a failing test","matchedKsbs":["S1","B1"],"explanation":"Applied technical skills to resolve a defect."}]
                """;
        KsbMatchResult result = service.processResponse(json, 3);

        assertEquals(1, result.matches().size());
        assertEquals(3, result.totalActivitiesConsidered());

        KsbMatch match = result.matches().get(0);
        assertEquals("2026/05/30", match.activityDate());
        assertEquals("Debugged a failing test", match.activitySummary());
        assertEquals(2, match.matchedKsbs().size());
        assertTrue(match.matchedKsbs().contains("S1"));
        assertTrue(match.matchedKsbs().contains("B1"));
        assertEquals("Applied technical skills to resolve a defect.", match.explanation());
    }

    @Test
    void emptyArray_returnsEmptyResult() throws JsonProcessingException {
        KsbMatchResult result = service.processResponse("[]", 5);
        assertEquals(0, result.matches().size());
        assertEquals(5, result.totalActivitiesConsidered());
    }

    @Test
    void markdownFencesStripped() throws JsonProcessingException {
        String json = """
                ```json
                [{"activityDate":"2026/05/30","activitySummary":"Test","matchedKsbs":["K1"],"explanation":"Because."}]
                ```""";
        KsbMatchResult result = service.processResponse(json, 1);
        assertEquals(1, result.matches().size());
    }

    @Test
    void markdownFencesStripped_noLanguageTag() throws JsonProcessingException {
        String json = "```\n[{\"activityDate\":\"2026/05/30\",\"activitySummary\":\"Test\",\"matchedKsbs\":[\"K1\"],\"explanation\":\"Because.\"}]\n```";
        KsbMatchResult result = service.processResponse(json, 1);
        assertEquals(1, result.matches().size());
    }

    @Test
    void invalidJson_throwsJsonProcessingException() {
        assertThrows(JsonProcessingException.class,
                () -> service.processResponse("not valid json at all", 1));
    }

    @Test
    void mixedMatches_allParsed() throws JsonProcessingException {
        String json = """
                [
                  {"activityDate":"2026/05/30","activitySummary":"Task A","matchedKsbs":["K1"],"explanation":"Reason A"},
                  {"activityDate":"2026/05/31","activitySummary":"Task B","matchedKsbs":["S1","S2"],"explanation":"Reason B"}
                ]
                """;
        KsbMatchResult result = service.processResponse(json, 4);
        assertEquals(2, result.matches().size());
        assertEquals(4, result.totalActivitiesConsidered());
    }
}
