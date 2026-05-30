package com.github.grepHammerspace.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OtjSubmitResultTest {

    @Test
    void nothingToPost_true_whenBothListsEmpty() {
        assertTrue(new OtjSubmitResult(List.of(), List.of()).nothingToPost());
    }

    @Test
    void nothingToPost_false_whenPostedNonEmpty() {
        assertFalse(new OtjSubmitResult(List.of("id-1"), List.of()).nothingToPost());
    }

    @Test
    void allPosted_true_whenOnlyPostedNonEmpty() {
        assertTrue(new OtjSubmitResult(List.of("id-1"), List.of()).allPosted());
    }

    @Test
    void allPosted_false_whenFailedNonEmpty() {
        assertFalse(new OtjSubmitResult(List.of("id-1"), List.of("id-2")).allPosted());
    }

    @Test
    void allPosted_false_whenBothEmpty() {
        assertFalse(new OtjSubmitResult(List.of(), List.of()).allPosted());
    }

    @Test
    void allFailed_true_whenOnlyFailedNonEmpty() {
        assertTrue(new OtjSubmitResult(List.of(), List.of("id-1")).allFailed());
    }

    @Test
    void allFailed_false_whenPostedNonEmpty() {
        assertFalse(new OtjSubmitResult(List.of("id-1"), List.of("id-2")).allFailed());
    }

    @Test
    void allFailed_false_whenBothEmpty() {
        assertFalse(new OtjSubmitResult(List.of(), List.of()).allFailed());
    }

    @Test
    void partial_true_whenBothNonEmpty() {
        assertTrue(new OtjSubmitResult(List.of("id-1"), List.of("id-2")).partial());
    }

    @Test
    void partial_false_whenOnlyPosted() {
        assertFalse(new OtjSubmitResult(List.of("id-1"), List.of()).partial());
    }

    @Test
    void partial_false_whenBothEmpty() {
        assertFalse(new OtjSubmitResult(List.of(), List.of()).partial());
    }
}
