package com.github.grepHammerspace.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentDiffTest {

    @Test
    void nullLastContent_returnsAllNewContent() {
        String result = ContentDiffer.computeDiff(null, "line one\nline two");
        assertEquals("line one\nline two", result);
    }

    @Test
    void emptyLastContent_returnsAllNewContent() {
        String result = ContentDiffer.computeDiff("", "line one\nline two");
        assertEquals("line one\nline two", result);
    }

    @Test
    void blankLastContent_returnsAllNewContent() {
        String result = ContentDiffer.computeDiff("   ", "line one");
        assertEquals("line one", result);
    }

    @Test
    void identicalContent_returnsNull() {
        String content = "worked 2 hours on assignment";
        assertNull(ContentDiffer.computeDiff(content, content));
    }

    @Test
    void newLineAppended_returnsOnlyNewLine() {
        String old = "line one";
        String updated = "line one\nnew second line";
        assertEquals("new second line", ContentDiffer.computeDiff(old, updated));
    }

    @Test
    void multipleNewLines_returnsAllNew() {
        String old = "line one";
        String updated = "line one\nnew line two\nnew line three";
        String diff = ContentDiffer.computeDiff(old, updated);
        assertTrue(diff.contains("new line two"));
        assertTrue(diff.contains("new line three"));
        assertFalse(diff.contains("line one"));
    }

    @Test
    void blankLinesInNewContent_areFiltered() {
        String old = "line one";
        String updated = "line one\n\n   \nnew line";
        assertEquals("new line", ContentDiffer.computeDiff(old, updated));
    }

    @Test
    void oldLineReusedInNewContent_notTreatedAsNew() {
        String old = "repeated line\nother line";
        String updated = "repeated line\nother line\nrepeated line";
        assertNull(ContentDiffer.computeDiff(old, updated));
    }
}
