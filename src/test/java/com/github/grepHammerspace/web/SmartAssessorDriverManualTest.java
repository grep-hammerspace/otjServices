package com.github.grepHammerspace.web;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Live integration test for SmartAssessorDriver.
 *
 * No Tailscale, no Dagger, no Mongo — just the driver hitting the real network.
 *
 * Run from the IDE or with:
 *   mvn test -Dtest=SmartAssessorDriverManualTest -pl .
 *
 * When the test reaches completeMfa() it will block (polling Microsoft every 3 s)
 * until you approve the push notification on your Microsoft Authenticator app.
 * Approve within ~2 minutes or the test will time out.
 */
class SmartAssessorDriverManualTest {

    private static final String USERNAME = "ec24968@qmul.ac.uk";
    private static final String PASSWORD = "Apollocrocodile!";

    @Test
    void loginAndPrintSmartAssessorCookies() throws Exception {
        SmartAssessorDriver driver = new SmartAssessorDriver();

        System.out.println("=== Step 1: logging in (email → Keycloak → Azure AD → credentials) ===");
        PrepareResult result = driver.prepare(USERNAME, PASSWORD);
        System.out.println("=== prepare() status: " + result.status() + " ===");
        System.out.println("=== " + result.userMessage() + " ===");

        if (result.requiresMfa()) {
            System.out.println("=== Step 2: polling for approval (up to 2 min) ===");
            driver.completeMfa("");
        }

        System.out.println("=== Login complete. SmartAssessor cookies: ===");
        List<String> cookies = driver.cookiesFor("smartassessor.co.uk");
        if (cookies.isEmpty()) {
            System.out.println("  (none found — login may have landed on a different domain)");
        } else {
            cookies.forEach(c -> System.out.println("  " + c));
        }

        System.out.println("=== All cookies collected during session: ===");
        driver.allCookies().forEach(c -> System.out.println("  " + c));
    }
}
