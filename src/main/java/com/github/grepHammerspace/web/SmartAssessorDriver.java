package com.github.grepHammerspace.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives the SmartAssessor login via the QMUL Azure AD federation path:
 *   PKCE bypass → Keycloak OIDC → Azure AD broker → Microsoft login → MFA push → SmartAssessor
 *
 * The OneAdvanced discover endpoint only recognises {@code se24.qmul.ac.uk} emails, not
 * {@code qmul.ac.uk}, so we replicate what discover does: generate PKCE code_verifier/challenge,
 * inject STATE and CODE_VERIFIER cookies, and go straight to Keycloak.
 *
 * Usage:
 *   1. prepare(username, password) — stops after sending the Microsoft Authenticator push
 *   2. completeMfa("") — polls until the user approves, then completes the redirect chain to SmartAssessor
 *      (no-op if an existing SSO session already finished login inside prepare)
 */
public class SmartAssessorDriver implements Driver {
    private static final Logger log = LoggerFactory.getLogger(SmartAssessorDriver.class);

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=UTF-8");

    // Keycloak OIDC endpoint for QMUL — bypasses OneAdvanced discover
    private static final String KEYCLOAK_AUTH_URL =
            "https://identity.oneadvanced.com/auth/realms/queen-mary-university-london"
            + "/protocol/openid-connect/auth";

    private static final String OA_REDIRECT_URI =
            "https://auth.identity.oneadvanced.com/auth/redirect";

    // STATE cookie value sent by the discover endpoint; we inject it ourselves.
    // Encodes: {"clientId":"advancedsso","redirectUri":"https://www.smartassessor.co.uk/Account",
    //           "organizationRef":"queen-mary-university-london","authenticationDomain":"smartassessor.co.uk"}
    private static final String STATE_JSON =
            "{\"clientId\":\"advancedsso\","
            + "\"redirectUri\":\"https://www.smartassessor.co.uk/Account\","
            + "\"organizationRef\":\"queen-mary-university-london\","
            + "\"authenticationDomain\":\"smartassessor.co.uk\"}";

    private static final String MS_SAML_URL =
            "https://login.microsoftonline.com/569df091-b013-40e3-86ee-bd9cb9e25814/saml2";

    private static final String BEGIN_AUTH_URL =
            "https://login.microsoftonline.com/common/SAS/BeginAuth";

    private static final String END_AUTH_URL =
            "https://login.microsoftonline.com/common/SAS/EndAuth";

    private static final String PROCESS_AUTH_URL =
            "https://login.microsoftonline.com/common/SAS/ProcessAuth";

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:151.0) Gecko/20100101 Firefox/151.0";

    private static final int MAX_POLL_ATTEMPTS = 40; // 40 × 3 s = 2 min
    private static final long POLL_INTERVAL_MS = 3_000L;

    private final InMemoryCookieJar cookieJar;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    // State held between prepare() and completeMfa()
    private String mfaCtx;
    private String mfaFlowToken;
    private String mfaCanary;
    private String mfaSessionId;
    private String mfaLogin;
    // Set to true when an existing MS SSO session completes the whole flow inside prepare()
    private boolean loginComplete = false;

    @Inject
    public SmartAssessorDriver() {
        this.mapper = new ObjectMapper();
        this.cookieJar = new InMemoryCookieJar();
        this.httpClient = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .followRedirects(true)
                .build();
    }

    /** Returns all cookies whose domain contains {@code domain}. */
    List<String> cookiesFor(String domain) {
        return cookieJar.allCookies().stream()
                .filter(c -> c.domain().contains(domain))
                .map(c -> "[" + c.domain() + "] " + c.name() + "=" + c.value())
                .toList();
    }

    /** Returns every cookie collected across all domains during this session. */
    List<String> allCookies() {
        return cookieJar.allCookies().stream()
                .map(c -> "[" + c.domain() + "] " + c.name() + "=" + c.value())
                .toList();
    }

    // ── Cookie jar ─────────────────────────────────────────────────────────────
    private static final class InMemoryCookieJar implements CookieJar {
        private final List<Cookie> store = new ArrayList<>();

        @Override
        public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            for (Cookie incoming : cookies) {
                store.removeIf(existing ->
                        existing.name().equals(incoming.name()) &&
                        existing.domain().equals(incoming.domain()) &&
                        existing.path().equals(incoming.path()));
                store.add(incoming);
            }
        }

        @Override
        public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            return store.stream().filter(c -> c.matches(url)).toList();
        }

        synchronized List<Cookie> allCookies() {
            return List.copyOf(store);
        }
    }

    // ── $Config extraction ──────────────────────────────────────────────────────
    // Microsoft embeds all page state in a $Config = {...} JS object.
    // We pull individual string values by key rather than parsing the full object.
    private static String cfg(String html, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    // ── Driver interface ────────────────────────────────────────────────────────

    /**
     * Executes the Azure AD login flow up to and including sending the Microsoft
     * Authenticator push notification. Stops and returns so the caller can
     * inform the user to approve on their phone, then call {@link #completeMfa(String)}.
     *
     * If an active Microsoft SSO session is detected the entire login completes
     * here and {@link #completeMfa(String)} becomes a no-op.
     */
    @Override
    public SmartAssessorDriver prepare(String username, String password) throws IOException {
        this.mfaLogin = username;
        this.loginComplete = false;

        // ── Steps 1-2: bypass OneAdvanced discover with hand-crafted PKCE ───
        // The discover endpoint just generates a PKCE code_verifier / code_challenge
        // pair, sets them as cookies on auth.identity.oneadvanced.com, and redirects
        // to Keycloak. We replicate that here so we can supply any Microsoft-format
        // username without needing the email to be registered in the discover service.

        // Generate PKCE verifier (32 random bytes → base64url, no padding)
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String codeVerifier  = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] challengeHash;
        try {
            challengeHash = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeHash);

        // Inject the STATE and CODE_VERIFIER cookies that discover would have set
        String stateValue = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(STATE_JSON.getBytes(StandardCharsets.UTF_8));
        HttpUrl authBase = HttpUrl.parse("https://auth.identity.oneadvanced.com/");
        cookieJar.saveFromResponse(authBase, List.of(
                new Cookie.Builder().domain("auth.identity.oneadvanced.com").path("/")
                        .name("CODE_VERIFIER").value(codeVerifier).httpOnly().secure().build(),
                new Cookie.Builder().domain("auth.identity.oneadvanced.com").path("/")
                        .name("STATE").value(stateValue).httpOnly().secure().build()
        ));
        log.info("PKCE ready — going directly to Keycloak");

        // ── Step 3: GET Keycloak OIDC auth page ────────────────────────────
        String keycloakUrl = KEYCLOAK_AUTH_URL
                + "?client_id=advancedsso"
                + "&response_type=code"
                + "&redirect_uri=" + URLEncoder.encode(OA_REDIRECT_URI, StandardCharsets.UTF_8)
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256"
                + "&scope=openid+email+profile"
                + "&login_hint=" + URLEncoder.encode(username, StandardCharsets.UTF_8);

        Response resp = get(keycloakUrl);
        String currentUrl = resp.request().url().toString();
        String body = resp.body().string();
        resp.close();
        log.info("Keycloak page: {}", currentUrl);

        Document doc = Jsoup.parse(body, currentUrl);
        Element brokerAnchor = doc.selectFirst("a[href*=broker/asso-qm-aad]");
        if (brokerAnchor == null) {
            throw new IOException("Azure AD broker link not found on Keycloak page — URL: " + currentUrl);
        }
        resp = get(brokerAnchor.absUrl("href"));
        currentUrl = resp.request().url().toString();
        body = resp.body().string();
        resp.close();
        log.info("Broker page: {}", currentUrl);

        // ── Step 4: POST SAMLRequest to Microsoft ───────────────────────────
        doc = Jsoup.parse(body, currentUrl);
        Element samlFormEl = doc.selectFirst("form");
        if (samlFormEl == null) throw new IOException("SAML form not found on broker page — URL: " + currentUrl);
        resp = post(samlFormEl.absUrl("action"), hiddenInputsOf(samlFormEl));
        currentUrl = resp.request().url().toString();
        body = resp.body().string();
        resp.close();

        // ── Step 5: Handle Microsoft SSO interstitial (oPostParams / sso_reload) ──
        // Microsoft returns a "Redirecting" page whose JS re-POSTs oPostParams to
        // the same endpoint with ?sso_reload=true, picking up the canary.
        if (body.contains("oPostParams")) {
            log.info("Handling Microsoft SSO interstitial");
            Matcher m = Pattern.compile("\"oPostParams\"\\s*:\\s*\\{([^}]+)\\}").matcher(body);
            if (!m.find()) throw new IOException("oPostParams block not found in Microsoft redirect page");

            FormBody.Builder reloadForm = new FormBody.Builder();
            Matcher kv = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(m.group(1));
            while (kv.find()) reloadForm.add(kv.group(1), kv.group(2));

            resp = post(MS_SAML_URL + "?sso_reload=true", reloadForm.build());
            currentUrl = resp.request().url().toString();
            body = resp.body().string();
            resp.close();
            log.info("After sso_reload, at: {}", currentUrl);
        }

        // ── Fast-path: existing MS SSO session already sent us the SAMLResponse ──
        if (body.contains("name=\"SAMLResponse\"") || body.contains("name='SAMLResponse'")) {
            log.info("Existing Microsoft SSO session detected — completing login without MFA");
            completeSamlChain(body, currentUrl);
            loginComplete = true;
            return this;
        }

        // ── Step 6: POST credentials to Microsoft login endpoint ────────────
        // The modern Microsoft ConvergedSignIn page has no HTML <form>; it's JS-rendered.
        // We extract $Config values and POST directly to urlPost.
        String pgid = cfg(body, "pgid");
        if (!"ConvergedSignIn".equals(pgid)) {
            throw new IOException("Expected Microsoft login page (ConvergedSignIn), got pgid=" + pgid + " — URL: " + currentUrl);
        }
        String urlPost      = cfg(body, "urlPost");
        String credCtx      = cfg(body, "sCtx");
        String credFT       = cfg(body, "sFT");
        String credCanary   = cfg(body, "canary");
        String credSessId   = cfg(body, "sessionId");
        if (urlPost == null) {
            urlPost = "https://login.microsoftonline.com/569df091-b013-40e3-86ee-bd9cb9e25814/login";
        }
        long credStart = System.currentTimeMillis();
        FormBody credBody = new FormBody.Builder()
                .add("loginfmt",         username)
                .add("login",            username)
                .add("passwd",           password)
                .add("ctx",              credCtx    != null ? credCtx    : "")
                .add("flowToken",        credFT     != null ? credFT     : "")
                .add("canary",           credCanary != null ? credCanary : "")
                .add("hpgrequestid",     credSessId != null ? credSessId : "")
                .add("type",             "11")
                .add("i13",              "0")
                .add("i19",              String.valueOf(System.currentTimeMillis() - credStart))
                .add("i21",              "0")
                .add("CookieDisclosure", "0")
                .add("LoginOptions",     "3")
                .add("ps",               "2")
                .add("IsFidoSupported",  "1")
                .add("NewUser",          "1")
                .add("fspost",           "0")
                .add("isSignupPost",     "0")
                .add("PPSX",             "")
                .add("FoundMSAs",        "")
                .add("DfpArtifact",      "")
                .add("lrt",              "")
                .add("lrtPartition",     "")
                .add("hisRegion",        "")
                .add("hisScaleUnit",     "")
                .build();
        resp = post(urlPost, credBody);
        currentUrl = resp.request().url().toString();
        body = resp.body().string();
        resp.close();
        log.debug("After cred POST: pgid={} url={}", cfg(body, "pgid"), currentUrl);

        // ── Fast-path: Microsoft responded with SAMLResponse immediately (no MFA) ──
        if (body.contains("name=\"SAMLResponse\"") || body.contains("name='SAMLResponse'")) {
            log.info("Microsoft returned SAMLResponse directly — completing login without MFA");
            completeSamlChain(body, currentUrl);
            loginComplete = true;
            return this;
        }

        // ── Step 7: Extract MFA page state and send BeginAuth ────────────────
        String mfaPgid = cfg(body, "pgid");
        if (!"ConvergedTFA".equals(mfaPgid)) {
            throw new IOException("Expected MFA page (ConvergedTFA), got pgid=" + mfaPgid
                    + " — URL: " + currentUrl + ". Credentials may be wrong.");
        }

        mfaCtx       = cfg(body, "sCtx");
        mfaFlowToken = cfg(body, "sFT");
        mfaCanary    = cfg(body, "canary");
        mfaSessionId = cfg(body, "sessionId");

        if (mfaCtx == null || mfaFlowToken == null) {
            throw new IOException("Could not extract sCtx/sFT from Microsoft MFA page");
        }

        String beginBody = mapper.writeValueAsString(Map.of(
                "AuthMethodId", "PhoneAppNotification",
                "Method",       "BeginAuth",
                "ctx",          mfaCtx,
                "flowToken",    mfaFlowToken
        ));
        Request beginReq = new Request.Builder()
                .url(BEGIN_AUTH_URL)
                .header("User-Agent",        USER_AGENT)
                .header("Accept",            "application/json")
                .header("Content-type",      "application/json; charset=UTF-8")
                .header("hpgrequestid",      mfaSessionId != null ? mfaSessionId : "")
                .header("canary",            mfaCanary    != null ? mfaCanary    : "")
                .header("client-request-id", UUID.randomUUID().toString())
                .post(RequestBody.create(beginBody, JSON_TYPE))
                .build();

        try (Response beginResp = httpClient.newCall(beginReq).execute()) {
            JsonNode json = decodeSasResponse(beginResp.body().string());
            if (!json.path("Success").asBoolean()) {
                throw new IOException("BeginAuth failed: " + json.path("ResultValue").asText());
            }
            String updated = json.path("FlowToken").asText(null);
            if (updated != null && !updated.isEmpty()) mfaFlowToken = updated;
        }
        log.info("Phone push sent — waiting for user to approve on Microsoft Authenticator");
        return this;
    }

    /**
     * Polls Microsoft until the phone push is approved, then completes the
     * Keycloak → SmartAssessor redirect chain. Blocks for up to ~2 minutes.
     * The {@code ignoredToken} parameter is not used.
     */
    @Override
    public void completeMfa(String ignoredToken) throws IOException {
        if (loginComplete) {
            log.info("Login already completed via SSO session — completeMfa is a no-op");
            return;
        }
        if (mfaCtx == null) {
            throw new IllegalStateException("No MFA state — call prepare first");
        }

        // ── Poll EndAuth ────────────────────────────────────────────────────
        long lastPollStart = 0, lastPollEnd = 0;
        boolean approved = false;

        for (int poll = 1; poll <= MAX_POLL_ATTEMPTS; poll++) {
            HttpUrl.Builder urlB = HttpUrl.parse(END_AUTH_URL).newBuilder()
                    .addQueryParameter("authMethodId", "PhoneAppNotification")
                    .addQueryParameter("pollCount",    String.valueOf(poll));
            if (poll > 1) {
                urlB.addQueryParameter("lastPollStart", String.valueOf(lastPollStart))
                    .addQueryParameter("lastPollEnd",   String.valueOf(lastPollEnd));
            }

            lastPollStart = System.currentTimeMillis();
            Request pollReq = new Request.Builder()
                    .url(urlB.build())
                    .header("User-Agent",   USER_AGENT)
                    .header("Accept",       "application/json")
                    .header("hpgrequestid", mfaSessionId != null ? mfaSessionId : "")
                    .header("canary",       mfaCanary    != null ? mfaCanary    : "")
                    .get()
                    .build();

            try (Response pollResp = httpClient.newCall(pollReq).execute()) {
                JsonNode json = decodeSasResponse(pollResp.body().string());
                lastPollEnd = System.currentTimeMillis();

                String updated = json.path("FlowToken").asText(null);
                if (updated != null && !updated.isEmpty()) mfaFlowToken = updated;

                if (json.path("Success").asBoolean()) {
                    log.info("Approved after {} poll(s)", poll);
                    approved = true;
                    break;
                }
                String result = json.path("ResultValue").asText();
                if (!"AuthenticationPending".equals(result)) {
                    throw new IOException("Unexpected MFA poll result: " + result);
                }
                log.debug("Poll {}/{}: pending", poll, MAX_POLL_ATTEMPTS);
            }

            try { Thread.sleep(POLL_INTERVAL_MS); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for MFA approval", e);
            }
        }

        if (!approved) {
            throw new IOException("Timed out waiting for Microsoft Authenticator approval");
        }

        // ── POST ProcessAuth ─────────────────────────────────────────────────
        FormBody processBody = new FormBody.Builder()
                .add("type",              "22")
                .add("mfaAuthMethod",     "PhoneAppNotification")
                .add("login",             mfaLogin)
                .add("flowToken",         mfaFlowToken)
                .add("request",           mfaCtx)
                .add("canary",            mfaCanary    != null ? mfaCanary    : "")
                .add("hpgrequestid",      mfaSessionId != null ? mfaSessionId : "")
                .add("mfaLastPollStart",  String.valueOf(lastPollStart))
                .add("mfaLastPollEnd",    String.valueOf(lastPollEnd))
                .add("i19",               String.valueOf(lastPollEnd - lastPollStart))
                .add("hideSmsInMfaProofs","false")
                .add("sacxt",             "")
                .build();

        Response processResp = post(PROCESS_AUTH_URL, processBody);
        String processUrl  = processResp.request().url().toString();
        String processHtml = processResp.body().string();
        processResp.close();
        log.info("ProcessAuth at: {}", processUrl);

        completeSamlChain(processHtml, processUrl);
    }

    /** Not yet implemented — SmartAssessor OTJ submission API is pending analysis. */
    @Override
    public OtjSubmitResult submitPendingOtjs(String userId) {
        throw new UnsupportedOperationException("SmartAssessor OTJ submission not yet implemented");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Scrapes the SAMLResponse auto-submit form and POSTs it to Keycloak, then follows
     *  the full redirect chain to SmartAssessor. */
    private void completeSamlChain(String html, String baseUrl) throws IOException {
        Document doc = Jsoup.parse(html, baseUrl);
        Element samlForm = doc.selectFirst("form");
        if (samlForm == null) throw new IOException("SAMLResponse form not found — URL: " + baseUrl);

        Response finalResp = post(samlForm.absUrl("action"), hiddenInputsOf(samlForm));
        String landingUrl = finalResp.request().url().toString();
        finalResp.body().string(); // consume to follow the full redirect chain
        finalResp.close();

        log.info("Login complete — landing URL: {}", landingUrl);
        if (!landingUrl.contains("smartassessor.co.uk")) {
            throw new IOException("Login failed — unexpected landing URL: " + landingUrl);
        }
    }

    /** Builds a FormBody from all hidden inputs in a form element. */
    private static FormBody hiddenInputsOf(Element form) {
        FormBody.Builder b = new FormBody.Builder();
        for (Element input : form.select("input[type=hidden]")) {
            if (!input.attr("name").isEmpty()) b.add(input.attr("name"), input.val());
        }
        return b.build();
    }

    /** Decodes a base64-encoded Microsoft SAS API response into a JsonNode. */
    private JsonNode decodeSasResponse(String raw) throws IOException {
        // Microsoft SAS responses are base64-encoded JSON; use MIME decoder which is
        // lenient about padding and whitespace.
        byte[] bytes = Base64.getMimeDecoder().decode(raw.trim());
        return mapper.readTree(bytes);
    }

    private Response get(String url) throws IOException {
        return httpClient.newCall(new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()).execute();
    }

    private Response post(String url, FormBody body) throws IOException {
        return httpClient.newCall(new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .post(body)
                .build()).execute();
    }
}
