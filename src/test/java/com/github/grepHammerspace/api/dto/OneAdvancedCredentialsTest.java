package com.github.grepHammerspace.api.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards the {@code toString()} override.
 *
 * <p>A record's generated {@code toString()} prints every component, so without the override a
 * single {@code log.info("...{}", body)} would put the OneAdvanced password in the log. These
 * cover the shapes that reach {@code toString()} without anyone meaning to: SLF4J argument
 * substitution, and the record sitting inside a collection that gets logged.
 */
class OneAdvancedCredentialsTest {

    private static final String USERNAME = "leaktest@example.invalid";
    private static final String PASSWORD = "pw-DO-NOT-LOG-9f2a";

    private static final OneAdvancedCredentials CREDENTIALS =
            new OneAdvancedCredentials(USERNAME, PASSWORD);

    private static void assertRedacted(String rendered) {
        assertFalse(rendered.contains(USERNAME), rendered);
        assertFalse(rendered.contains(PASSWORD), rendered);
    }

    @Test
    void toStringHidesBothFields() {
        assertRedacted(CREDENTIALS.toString());
    }

    @Test
    void stringValueOfHidesBothFields() {
        assertRedacted(String.valueOf(CREDENTIALS));
    }

    @Test
    void nestedInACollectionHidesBothFields() {
        assertRedacted(List.of(CREDENTIALS).toString());
        assertRedacted(Map.of("body", CREDENTIALS).toString());
    }

    @Test
    void concatenationHidesBothFields() {
        assertRedacted("prepare failed for " + CREDENTIALS);
    }
}
