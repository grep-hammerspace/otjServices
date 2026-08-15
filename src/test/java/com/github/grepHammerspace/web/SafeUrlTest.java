package com.github.grepHammerspace.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeUrlTest {

    @Test
    void dropsTheUsernameFromTheKeycloakLoginHint() {
        String redacted = SafeUrl.redact(
                "https://identity.oneadvanced.com/auth/realms/qmul/protocol/openid-connect/auth"
                        + "?client_id=advancedsso&login_hint=ec24598%40qmul.ac.uk&code_challenge=abc123");

        assertFalse(redacted.contains("qmul.ac.uk"), redacted);
        assertFalse(redacted.contains("abc123"), redacted);
        assertTrue(redacted.startsWith(
                "https://identity.oneadvanced.com/auth/realms/qmul/protocol/openid-connect/auth"));
    }

    @Test
    void keepsParameterNamesSoAFlowChangeIsStillDiagnosable() {
        String redacted = SafeUrl.redact("https://example.test/auth?login_hint=x&code_challenge=y");
        assertTrue(redacted.contains("login_hint"), redacted);
        assertTrue(redacted.contains("code_challenge"), redacted);
    }

    @Test
    void dropsKeycloakSessionParameters() {
        String redacted = SafeUrl.redact(
                "https://identity.oneadvanced.com/login-actions/authenticate"
                        + "?session_code=SECRET-SESSION&execution=SECRET-EXEC&tab_id=SECRET-TAB");

        assertFalse(redacted.contains("SECRET-SESSION"), redacted);
        assertFalse(redacted.contains("SECRET-EXEC"), redacted);
        assertFalse(redacted.contains("SECRET-TAB"), redacted);
    }

    @Test
    void leavesAQuerylessUrlAlone() {
        assertEquals("https://education.oneadvanced.com/dashboard",
                SafeUrl.redact("https://education.oneadvanced.com/dashboard"));
    }

    @Test
    void dropsTheFragmentToo() {
        String redacted = SafeUrl.redact("https://example.test/cb#access_token=SECRET-TOKEN");
        assertFalse(redacted.contains("SECRET-TOKEN"), redacted);
    }

    /** The whole point of the class: what cannot be parsed cannot be proven safe. */
    @Test
    void neverEchoesBackAnUnparseableUrl() {
        String redacted = SafeUrl.redact("not a url at all ?login_hint=leaked@example.test");
        assertFalse(redacted.contains("leaked@example.test"), redacted);
        assertEquals("<unparseable url>", redacted);
    }

    @Test
    void handlesNull() {
        assertEquals("<null url>", SafeUrl.redact((String) null));
    }
}
