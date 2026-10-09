package com.github.grepHammerspace.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.grepHammerspace.db.ActivityLogRepository;
import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// QMUL's Azure AD federation. OneAdvanced discover only recognises se24.qmul.ac.uk emails, so the
// PKCE cookies discover would set are generated here instead.
public class AzureIdDriver extends OneAdvancedDriver {
    private static final String KEYCLOAK_AUTH_URL =
            "https://identity.oneadvanced.com/auth/realms/queen-mary-university-london"
            + "/protocol/openid-connect/auth";

    private static final String OA_REDIRECT_URI =
            "https://auth.identity.oneadvanced.com/auth/redirect";

    // No authenticationDomain: including one routes to a per-customer cookie-bounce host that
    // doesn't exist for education.oneadvanced.com.
    private static final String STATE_JSON =
            "{\"clientId\":\"advancedsso\","
            + "\"redirectUri\":\"https://education.oneadvanced.com/parseauth?redirectUri=https://education.oneadvanced.com/\","
            + "\"organizationRef\":\"queen-mary-university-london\"}";

    private static final String MS_SAML_URL =
            "https://login.microsoftonline.com/569df091-b013-40e3-86ee-bd9cb9e25814/saml2";

    // "Not now" on the security-info prompt, as a browser sends it (seen in a recorded sign-in).
    private static final String SKIP_PROOF_UP_URL =
            "https://login.microsoftonline.com/569df091-b013-40e3-86ee-bd9cb9e25814/resume"
            + "?skipmfaregistration=1";

    private static final String BEGIN_AUTH_URL =
            "https://login.microsoftonline.com/common/SAS/BeginAuth";

    private static final String END_AUTH_URL =
            "https://login.microsoftonline.com/common/SAS/EndAuth";

    private static final String PROCESS_AUTH_URL =
            "https://login.microsoftonline.com/common/SAS/ProcessAuth";

    private static final int MAX_POLL_ATTEMPTS = 40; // 40 × 3 s = 2 min
    private static final long POLL_INTERVAL_MS = 3_000L;

    private String mfaCtx;
    private String mfaFlowToken;
    private String mfaCanary;
    private String mfaSessionId;
    // The one credential-derived value kept between requests: ProcessAuth needs it in completeMfa.
    // Never log it.
    private String mfaLogin;
    private boolean loginComplete = false;

    @Inject
    public AzureIdDriver(ActivityLogRepository activityLogRepository) {
        super(activityLogRepository);
    }

    private static String cfg(String html, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static final Pattern ERROR_CODE =
            Pattern.compile("\"(?:iErrorCode|sErrorCode)\"\\s*:\\s*\"?(\\d+)");

    // Structural fields only, never the body or its text: a Microsoft page's $Config carries sFT and
    // sCtx. urlPost is where the page would send the browser next, e.g. /kmsi for "Stay signed in?".
    static String describePage(String html, String baseUrl) {
        String urlPost = cfg(html, "urlPost");
        HttpUrl base = HttpUrl.parse(baseUrl);
        HttpUrl next = urlPost == null || base == null ? null : base.resolve(urlPost);

        Matcher error = ERROR_CODE.matcher(html);
        return "pgid=" + cfg(html, "pgid")
                + ", forms=" + Jsoup.parse(html, baseUrl).select("form").size()
                + ", urlPost=" + (urlPost == null ? "none" : SafeUrl.redact(next))
                + (error.find() ? ", errorCode=" + error.group(1) : "");
    }

    static final String PROOF_UP_PGID = "ConvergedProofUpRedirect";

    private static final Pattern CONFIG_BLOCK =
            Pattern.compile("\\$Config\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);
    private static final Pattern CONFIG_KEY = Pattern.compile("\"(\\w+)\"\\s*:");

    // The names in a page's $Config, sorted, with no values: the values include sFT and sCtx, and on
    // a proof-up page the user's masked phone number and email. Names are Microsoft's, so they say
    // what the page offers (a skip link, say) without saying anything about the user.
    static String configKeys(String html) {
        Matcher block = CONFIG_BLOCK.matcher(html);
        if (!block.find()) return "none";
        TreeSet<String> keys = new TreeSet<>();
        Matcher key = CONFIG_KEY.matcher(block.group(1));
        while (key.find()) keys.add(key.group(1));
        return String.join(",", keys);
    }

    // The page a request landed on: its HTML and where it ended up after redirects.
    record Page(String html, String url) {}

    // The form "Not now" posts, built from the proof-up page's own $Config, or null when the page
    // lacks any of it. Values are the page's, so never logged.
    static FormBody proofUpSkipForm(String html) {
        String flowToken = cfg(html, "sFT");
        String ctx       = cfg(html, "sCtx");
        String canary    = cfg(html, "canary");
        if (flowToken == null || ctx == null || canary == null) return null;
        return new FormBody.Builder()
                .add("flowtoken", flowToken)
                .add("ctx",       ctx)
                .add("canary",    canary)
                .build();
    }

    // Microsoft's proof-up interrupt is a nudge with a "Not now" button, so press it and carry on
    // from whatever page comes next — SAML after the MFA, the MFA page itself before it. Checked
    // wherever it has appeared: before the MFA page, and after ProcessAuth. When Microsoft won't
    // let it be skipped (the nudge has run out of snoozes, or registration is compulsory), the user
    // has to visit mysignins, so SecurityInfoRequiredException says so; the key names are logged
    // to show what the page offered instead.
    private Page skipProofUp(Page page) throws IOException {
        if (!PROOF_UP_PGID.equals(cfg(page.html(), "pgid"))) return page;

        FormBody skip = proofUpSkipForm(page.html());
        if (skip == null) {
            log.warn("Microsoft wants security info registered and offers no skip — {} — "
                    + "$Config keys: {}", describePage(page.html(), page.url()), configKeys(page.html()));
            throw new SecurityInfoRequiredException();
        }

        Response resp = post(SKIP_PROOF_UP_URL, skip);
        Page next = new Page(resp.body().string(), resp.request().url().toString());
        resp.close();

        if (PROOF_UP_PGID.equals(cfg(next.html(), "pgid"))) {
            log.warn("Microsoft refused to skip the security-info prompt — {} — $Config keys: {}",
                    describePage(next.html(), next.url()), configKeys(next.html()));
            throw new SecurityInfoRequiredException();
        }
        log.info("Skipped Microsoft's security-info prompt — now at: {}",
                describePage(next.html(), next.url()));
        return next;
    }

    @Override
    public PrepareResult prepare(String username, String password) throws IOException {
        this.mfaLogin = username;
        this.loginComplete = false;

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
        // currentUrl carries login_hint=<username>; SafeUrl keeps the parameter names only.
        log.info("Keycloak page: {}", SafeUrl.redact(currentUrl));

        Document doc = Jsoup.parse(body, currentUrl);
        Element brokerAnchor = doc.selectFirst("a[href*=broker/asso-qm-aad]");
        if (brokerAnchor == null) {
            throw new LoginChainException("Azure AD broker link not found on Keycloak page — URL: " + SafeUrl.redact(currentUrl));
        }
        resp = get(brokerAnchor.absUrl("href"));
        currentUrl = resp.request().url().toString();
        body = resp.body().string();
        resp.close();
        log.info("Broker page: {}", SafeUrl.redact(currentUrl));

        doc = Jsoup.parse(body, currentUrl);
        Element samlFormEl = doc.selectFirst("form");
        if (samlFormEl == null) throw new LoginChainException("SAML form not found on broker page — URL: " + SafeUrl.redact(currentUrl));
        resp = post(samlFormEl.absUrl("action"), hiddenInputsOf(samlFormEl));
        currentUrl = resp.request().url().toString();
        body = resp.body().string();
        resp.close();

        // Microsoft's SSO interstitial re-POSTs oPostParams with ?sso_reload=true to pick up the
        // canary.
        if (body.contains("oPostParams")) {
            log.info("Handling Microsoft SSO interstitial");
            Matcher m = Pattern.compile("\"oPostParams\"\\s*:\\s*\\{([^}]+)\\}").matcher(body);
            if (!m.find()) throw new LoginChainException("oPostParams block not found in Microsoft redirect page");

            FormBody.Builder reloadForm = new FormBody.Builder();
            Matcher kv = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(m.group(1));
            while (kv.find()) reloadForm.add(kv.group(1), kv.group(2));

            resp = post(MS_SAML_URL + "?sso_reload=true", reloadForm.build());
            currentUrl = resp.request().url().toString();
            body = resp.body().string();
            resp.close();
            log.info("After sso_reload, at: {}", SafeUrl.redact(currentUrl));
        }

        if (body.contains("name=\"SAMLResponse\"") || body.contains("name='SAMLResponse'")) {
            log.info("Existing Microsoft SSO session detected — completing login without MFA");
            completeSamlChain(body, currentUrl);
            loginComplete = true;
            return PrepareResult.loggedIn();
        }

        // The sign-in page is JS-rendered with no <form>: POST to $Config's urlPost directly.
        String pgid = cfg(body, "pgid");
        if (!"ConvergedSignIn".equals(pgid)) {
            throw new LoginChainException("Expected Microsoft login page (ConvergedSignIn), got pgid=" + pgid
                    + " — URL: " + SafeUrl.redact(currentUrl));
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
        log.debug("After cred POST: pgid={} url={}", cfg(body, "pgid"), SafeUrl.redact(currentUrl));

        Page afterCreds = skipProofUp(new Page(body, currentUrl));
        body = afterCreds.html();
        currentUrl = afterCreds.url();

        if (body.contains("name=\"SAMLResponse\"") || body.contains("name='SAMLResponse'")) {
            log.info("Microsoft returned SAMLResponse directly — completing login without MFA");
            completeSamlChain(body, currentUrl);
            loginComplete = true;
            return PrepareResult.loggedIn();
        }

        String mfaPgid = cfg(body, "pgid");
        if (!"ConvergedTFA".equals(mfaPgid)) {
            throw new LoginChainException("Expected MFA page (ConvergedTFA), got pgid=" + mfaPgid
                    + " — URL: " + SafeUrl.redact(currentUrl) + ". Credentials may be wrong.");
        }

        mfaCtx       = cfg(body, "sCtx");
        mfaFlowToken = cfg(body, "sFT");
        mfaCanary    = cfg(body, "canary");
        mfaSessionId = cfg(body, "sessionId");

        if (mfaCtx == null || mfaFlowToken == null) {
            throw new LoginChainException("Could not extract sCtx/sFT from Microsoft MFA page");
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

        PrepareResult result;
        try (Response beginResp = httpClient.newCall(beginReq).execute()) {
            String rawBegin = beginResp.body().string();
            JsonNode json = decodeSasResponse(rawBegin);
            // Named fields only. The raw response carries FlowToken, which is bearer-equivalent
            // for this MFA session — anyone holding it can drive the approval to completion.
            log.debug("BeginAuth status={} Success={} ResultValue={}",
                    beginResp.code(), json.path("Success").asBoolean(), json.path("ResultValue").asText());
            if (!json.path("Success").asBoolean()) {
                String rv = json.path("ResultValue").asText();
                String hint = "UserAuthFailedDuplicateRequest".equals(rv)
                        ? " — a push is already pending; deny it on your phone (or wait ~60 s) then retry"
                        : "";
                throw new LoginChainException("BeginAuth failed: " + rv + hint);
            }
            String updated = json.path("FlowToken").asText(null);
            if (updated != null && !updated.isEmpty()) mfaFlowToken = updated;

            int entropy = json.path("Entropy").asInt(-1);
            result = PrepareResult.pushSent(entropy >= 0 ? entropy : null);
        }
        log.info("Phone push sent — waiting for user to approve on Microsoft Authenticator");
        return result;
    }

    @Override
    public void completeMfa(String ignoredToken) throws IOException {
        if (loginComplete) {
            log.info("Login already completed via SSO session — completeMfa is a no-op");
            return;
        }
        if (mfaCtx == null) {
            throw new IllegalStateException("No MFA state — call prepare first");
        }

        long lastPollStart = 0, lastPollEnd = 0;
        boolean approved = false;

        for (int poll = 1; poll <= MAX_POLL_ATTEMPTS; poll++) {
            lastPollStart = System.currentTimeMillis();

            Map<String, Object> endMap = new LinkedHashMap<>();
            endMap.put("AuthMethodId", "PhoneAppNotification");
            endMap.put("Method",       "EndAuth");
            endMap.put("ctx",          mfaCtx);
            endMap.put("flowToken",    mfaFlowToken);
            endMap.put("PollCount",    poll);
            endMap.put("sessionId",    mfaSessionId != null ? mfaSessionId : "");
            if (poll > 1) {
                endMap.put("lastPollStart", lastPollStart);
                endMap.put("lastPollEnd",   lastPollEnd);
            }
            String endBody = mapper.writeValueAsString(endMap);
            // endBody holds ctx and flowToken — never logged.
            log.debug("EndAuth poll={}/{} sending", poll, MAX_POLL_ATTEMPTS);

            Request pollReq = new Request.Builder()
                    .url(END_AUTH_URL)
                    .header("User-Agent",        USER_AGENT)
                    .header("Accept",            "application/json")
                    .header("Content-type",      "application/json; charset=UTF-8")
                    .header("hpgrequestid",      mfaSessionId != null ? mfaSessionId : "")
                    .header("canary",            mfaCanary    != null ? mfaCanary    : "")
                    .header("client-request-id", UUID.randomUUID().toString())
                    .post(RequestBody.create(endBody, JSON_TYPE))
                    .build();

            try (Response pollResp = httpClient.newCall(pollReq).execute()) {
                String rawPoll = pollResp.body().string();
                JsonNode json = decodeSasResponse(rawPoll);
                lastPollEnd = System.currentTimeMillis();

                String updated = json.path("FlowToken").asText(null);
                if (updated != null && !updated.isEmpty()) mfaFlowToken = updated;

                if (json.path("Success").asBoolean()) {
                    log.info("Approved after {} poll(s)", poll);
                    approved = true;
                    break;
                }
                String result = json.path("ResultValue").asText();
                log.debug("EndAuth poll={} status={} Success={} ResultValue={}",
                        poll, pollResp.code(), json.path("Success").asBoolean(), result);
                if (!"AuthenticationPending".equals(result)) {
                    // ResultValue alone. The full body would carry the refreshed FlowToken, and
                    // this message is echoed to the HTTP caller.
                    throw new LoginChainException("Unexpected MFA poll result: " + result);
                }
                log.debug("Poll {}/{}: pending", poll, MAX_POLL_ATTEMPTS);
            }

            try { Thread.sleep(POLL_INTERVAL_MS); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LoginChainException("Interrupted while waiting for MFA approval", e);
            }
        }

        if (!approved) {
            throw new LoginChainException("Timed out waiting for Microsoft Authenticator approval");
        }

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
        int processStatus  = processResp.code();
        String processHtml = processResp.body().string();
        processResp.close();
        log.info("ProcessAuth at: {} — HTTP {}, {}", SafeUrl.redact(processUrl), processStatus,
                describePage(processHtml, processUrl));

        Page afterMfa = skipProofUp(new Page(processHtml, processUrl));
        completeSamlChain(afterMfa.html(), afterMfa.url());
    }

    private void completeSamlChain(String html, String baseUrl) throws IOException {
        Document doc = Jsoup.parse(html, baseUrl);
        Element samlForm = doc.selectFirst("form");
        if (samlForm == null) {
            throw new LoginChainException("SAMLResponse form not found — URL: " + SafeUrl.redact(baseUrl)
                    + " — " + describePage(html, baseUrl));
        }
        String action = samlForm.absUrl("action");
        if (samlForm.selectFirst("input[name=SAMLResponse]") == null) {
            log.warn("Form posting to {} has no SAMLResponse input — posting it anyway",
                    SafeUrl.redact(action));
        }
        log.info("Posting SAMLResponse to: {}", SafeUrl.redact(action));
        Response finalResp = post(action, hiddenInputsOf(samlForm));
        String landingUrl = finalResp.request().url().toString();
        int landingStatus = finalResp.code();
        finalResp.body().string();
        finalResp.close();

        log.info("Login complete — landing URL: {} — HTTP {}", SafeUrl.redact(landingUrl), landingStatus);
        if (!landingUrl.contains("education.oneadvanced.com")) {
            throw new LoginChainException("Login failed — unexpected landing URL: " + SafeUrl.redact(landingUrl));
        }
    }

    private static FormBody hiddenInputsOf(Element form) {
        FormBody.Builder b = new FormBody.Builder();
        for (Element input : form.select("input[type=hidden]")) {
            if (!input.attr("name").isEmpty()) b.add(input.attr("name"), input.val());
        }
        return b.build();
    }

    private JsonNode decodeSasResponse(String raw) throws IOException {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return mapper.readTree(trimmed);
        }
        // Some endpoints return base64-encoded JSON.
        byte[] bytes = Base64.getMimeDecoder().decode(trimmed);
        return mapper.readTree(bytes);
    }
}
