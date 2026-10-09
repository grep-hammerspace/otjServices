package com.github.grepHammerspace.web;

import okhttp3.HttpUrl;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OtjDriverTest {
    private static final String REALM =
            "https://identity.oneadvanced.com/auth/realms/queen-mary-university-london/login-actions/authenticate";

    @Test
    void startsAtQmulsRealmNotDiscover() {
        HttpUrl url = HttpUrl.parse(new OtjDriver(null).qmulLoginUrl(null));

        assertNotNull(url);
        assertEquals("identity.oneadvanced.com", url.host());
        assertEquals("/auth/realms/queen-mary-university-london/protocol/openid-connect/auth", url.encodedPath());
        assertEquals("advancedsso", url.queryParameter("client_id"));
        assertEquals("S256", url.queryParameter("code_challenge_method"));
        // The OneAdvanced route lets the realm's own form ask for the username.
        assertNull(url.queryParameter("login_hint"));
    }

    // The final redirect lands on auth.identity.oneadvanced.com, which redeems the code with the
    // verifier from this cookie — so the cookie must be the challenge's pre-image.
    @Test
    void setsTheVerifierCookieTheChallengeWasMadeFrom() throws Exception {
        OtjDriver driver = new OtjDriver(null);
        HttpUrl url = HttpUrl.parse(driver.qmulLoginUrl(null));

        List<okhttp3.Cookie> cookies = driver.cookieJar.loadForRequest(
                HttpUrl.parse("https://auth.identity.oneadvanced.com/auth/authenticate"));
        String verifier = cookies.stream().filter(c -> c.name().equals("CODE_VERIFIER"))
                .findFirst().orElseThrow().value();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));

        assertEquals(challenge, url.queryParameter("code_challenge"));
        assertFalse(cookies.stream().filter(c -> c.name().equals("STATE")).toList().isEmpty());
    }

    @Test
    void azureRoutePassesTheLoginHint() {
        HttpUrl url = HttpUrl.parse(new OtjDriver(null).qmulLoginUrl("someone@se24.qmul.ac.uk"));
        assertEquals("someone@se24.qmul.ac.uk", url.queryParameter("login_hint"));
    }

    @Test
    void usernameAndPasswordStepsAreDifferentForms() {
        assertNotEquals(signature(usernameForm("exec-1")), signature(passwordForm("exec-2")));
    }

    // Keycloak re-renders a refused password page with a fresh execution id: still the same form.
    @Test
    void aRefusedPasswordPageIsTheSameForm() {
        assertEquals(signature(passwordForm("exec-2")), signature(passwordForm("exec-3")));
    }

    @Test
    void signatureCarriesNoValues() {
        String sig = signature(passwordForm("SECRET-EXECUTION"));
        assertFalse(sig.contains("SECRET"), sig);
        assertEquals("/auth/realms/queen-mary-university-london/login-actions/authenticate [credentialId, password]",
                sig);
    }

    private static String signature(String html) {
        Element form = Jsoup.parse(html, REALM).selectFirst("form");
        return OtjDriver.formSignature(form);
    }

    private static String usernameForm(String execution) {
        return "<form id=kc-form-login action=\"" + REALM + "?session_code=SC&execution=" + execution + "\">"
                + "<input name=username type=text value=\"\"></form>";
    }

    private static String passwordForm(String execution) {
        return "<form id=kc-form-login action=\"" + REALM + "?session_code=SC&execution=" + execution + "\">"
                + "<input name=password type=password><input type=hidden name=credentialId value=\"SECRET-CRED\"></form>";
    }
}
