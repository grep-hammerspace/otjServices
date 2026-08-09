package com.github.grepHammerspace.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAllowlistTest {

    @Test
    void permits_listedLogin() {
        AdminAllowlist allowlist = AdminAllowlist.parse("sam@example.com");

        assertTrue(allowlist.permits("sam@example.com"));
    }

    @Test
    void rejects_unlistedLogin() {
        AdminAllowlist allowlist = AdminAllowlist.parse("sam@example.com");

        assertFalse(allowlist.permits("mallory@example.com"));
    }

    @Test
    void parse_splitsOnCommasAndTrims() {
        AdminAllowlist allowlist = AdminAllowlist.parse("  sam@example.com ,alex@example.com,  ");

        assertTrue(allowlist.permits("sam@example.com"));
        assertTrue(allowlist.permits("alex@example.com"));
        assertFalse(allowlist.permits(""), "the trailing empty entry must not become a permitted login");
    }

    /** Mail addresses aren't case-sensitive in practice, and the header's casing isn't ours to control. */
    @Test
    void permits_ignoresCaseOnBothSides() {
        AdminAllowlist allowlist = AdminAllowlist.parse("Sam@Example.COM");

        assertTrue(allowlist.permits("sam@example.com"));
        assertTrue(allowlist.permits("SAM@EXAMPLE.COM"));
    }

    @Test
    void permits_toleratesSurroundingWhitespaceInTheHeader() {
        AdminAllowlist allowlist = AdminAllowlist.parse("sam@example.com");

        assertTrue(allowlist.permits(" sam@example.com "));
    }

    /**
     * The important one: a missing or empty {@code ADMIN_ALLOWED_LOGINS} must lock everyone out
     * rather than wave everyone through. A deploy that forgets the variable should be unusable,
     * not wide open to every device on the tailnet.
     */
    @Test
    void emptyAllowlist_permitsNobody() {
        assertTrue(AdminAllowlist.parse(null).isEmpty());
        assertTrue(AdminAllowlist.parse("").isEmpty());
        assertTrue(AdminAllowlist.parse("   ").isEmpty());
        assertTrue(AdminAllowlist.parse(",, ,").isEmpty());

        assertFalse(AdminAllowlist.parse(null).permits("sam@example.com"));
        assertFalse(AdminAllowlist.parse("").permits("sam@example.com"));
        assertFalse(AdminAllowlist.parse(",, ,").permits("sam@example.com"));
    }

    @Test
    void permits_nullLogin_isFalse() {
        assertFalse(AdminAllowlist.parse("sam@example.com").permits(null));
    }
}
