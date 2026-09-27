package com.github.grepHammerspace.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.grepHammerspace.db.ActivityLogRepository;
import com.github.grepHammerspace.db.model.ActivityLog;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// QMUL's Azure AD federation. OneAdvanced discover only recognises se24.qmul.ac.uk emails, so the
// PKCE cookies discover would set are generated here instead.
public class AzureIdDriver implements Driver {
    private static final Logger log = LoggerFactory.getLogger(AzureIdDriver.class);

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=UTF-8");

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

    private static final String ACTIVITY_LOG_API =
            "https://education.oneadvanced.com/api/cloud-education/v1/learner/%s/activity-log";

    private final InMemoryCookieJar cookieJar;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    private final ActivityLogRepository activityLogRepository;

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
        this.activityLogRepository = activityLogRepository;
        this.mapper = new ObjectMapper();
        this.cookieJar = new InMemoryCookieJar();
        this.httpClient = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .followRedirects(true)
                .build();
    }

    // Names only: a cookie value here is a replayable credential.
    List<String> cookieNamesFor(String domain) {
        return cookieJar.allCookies().stream()
                .filter(c -> c.domain().contains(domain))
                .map(c -> "[" + c.domain() + "] " + c.name())
                .toList();
    }

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

    private static String cfg(String html, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
        return m.find() ? m.group(1) : null;
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
            throw new IOException("Azure AD broker link not found on Keycloak page — URL: " + SafeUrl.redact(currentUrl));
        }
        resp = get(brokerAnchor.absUrl("href"));
        currentUrl = resp.request().url().toString();
        body = resp.body().string();
        resp.close();
        log.info("Broker page: {}", SafeUrl.redact(currentUrl));

        doc = Jsoup.parse(body, currentUrl);
        Element samlFormEl = doc.selectFirst("form");
        if (samlFormEl == null) throw new IOException("SAML form not found on broker page — URL: " + SafeUrl.redact(currentUrl));
        resp = post(samlFormEl.absUrl("action"), hiddenInputsOf(samlFormEl));
        currentUrl = resp.request().url().toString();
        body = resp.body().string();
        resp.close();

        // Microsoft's SSO interstitial re-POSTs oPostParams with ?sso_reload=true to pick up the
        // canary.
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
            log.info("After sso_reload, at: {}", SafeUrl.redact(currentUrl));
        }

        if (body.contains("name=\"SAMLResponse\"") || body.contains("name='SAMLResponse'")) {
            log.info("Existing Microsoft SSO session detected — completing login without MFA");
            completeSamlChain(body, currentUrl);
            loginComplete = true;
            return PrepareResult.loginComplete();
        }

        // The sign-in page is JS-rendered with no <form>: POST to $Config's urlPost directly.
        String pgid = cfg(body, "pgid");
        if (!"ConvergedSignIn".equals(pgid)) {
            throw new IOException("Expected Microsoft login page (ConvergedSignIn), got pgid=" + pgid
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

        if (body.contains("name=\"SAMLResponse\"") || body.contains("name='SAMLResponse'")) {
            log.info("Microsoft returned SAMLResponse directly — completing login without MFA");
            completeSamlChain(body, currentUrl);
            loginComplete = true;
            return PrepareResult.loginComplete();
        }

        String mfaPgid = cfg(body, "pgid");
        if (!"ConvergedTFA".equals(mfaPgid)) {
            throw new IOException("Expected MFA page (ConvergedTFA), got pgid=" + mfaPgid
                    + " — URL: " + SafeUrl.redact(currentUrl) + ". Credentials may be wrong.");
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
                throw new IOException("BeginAuth failed: " + rv + hint);
            }
            String updated = json.path("FlowToken").asText(null);
            if (updated != null && !updated.isEmpty()) mfaFlowToken = updated;

            int entropy = json.path("Entropy").asInt(-1);
            result = entropy >= 0
                    ? PrepareResult.mfaNumberMatch(entropy)
                    : PrepareResult.mfaPushSent();
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

            java.util.Map<String, Object> endMap = new java.util.LinkedHashMap<>();
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
        log.info("ProcessAuth at: {}", SafeUrl.redact(processUrl));

        completeSamlChain(processHtml, processUrl);
    }

    @Override
    public OtjSubmitResult submitPendingOtjs(String userId, String learnerId) {
        List<ActivityLog> pending = activityLogRepository.getUnpostedActivityLogsFor(userId);

        if (pending.isEmpty()) {
            log.info("No unposted OTJs found for user {}", userId);
            return new OtjSubmitResult(List.of(), List.of());
        }

        String postUrl = String.format(ACTIVITY_LOG_API, learnerId.strip());
        // The URL is not logged: the learner ID is a path segment, and it identifies the student.
        log.info("Submitting {} pending OTJ(s) for user {}", pending.size(), userId);

        List<String> posted = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (ActivityLog activityLog : pending) {
            try {
                String json = mapper.writeValueAsString(buildPayload(activityLog, learnerId));

                Request request = new Request.Builder()
                        .url(postUrl)
                        .header("User-Agent", USER_AGENT)
                        .post(RequestBody.create(json, JSON_TYPE))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        activityLogRepository.markAsPosted(activityLog);
                        posted.add(activityLog.id());
                        log.info("Posted activity log {} ({})", activityLog.id(), activityLog.activityDate());
                    } else {
                        failed.add(activityLog.id());
                        // Status only. The WWW-Authenticate challenge and the response body are
                        // upstream material that can carry session and account detail.
                        log.warn("Failed to post activity log {} — HTTP {}", activityLog.id(), response.code());
                    }
                }
            } catch (Exception e) {
                failed.add(activityLog.id());
                // Type, not message: an OkHttp failure names the URL it was calling, and that URL
                // has the learner ID in its path.
                log.error("Exception posting activity log {}: {}", activityLog.id(), e.getClass().getSimpleName());
            }
        }

        log.info("Done — {}/{} posted, {} failed", posted.size(), pending.size(), failed.size());
        return new OtjSubmitResult(posted, failed);
    }

    private Map<String, Object> buildPayload(ActivityLog activityLog, String learnerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("learnerId", learnerId.strip());
        payload.put("activityImpact", activityLog.activityImpact());
        payload.put("unitId", "ef974f73-5d9d-447e-8652-379ba9535229");
        payload.put("activityDate", activityLog.activityDate().replace("/", "-"));
        payload.put("activityTime", "T" + activityLog.activityTime() + ":00");
        // The API expects 16 here; activityType is never set by the parser.
        payload.put("activityType", 16);
        payload.put("hours", activityLog.hours());
        payload.put("minutes", String.format("%02d", activityLog.minutes()));
        // Shape, not content: the payload carries the learner ID and the user's own notes.
        log.debug("Posting activity log {} — {}h{}m on {}", activityLog.id(),
                activityLog.hours(), activityLog.minutes(), activityLog.activityDate());
        return payload;
    }

    private void completeSamlChain(String html, String baseUrl) throws IOException {
        Document doc = Jsoup.parse(html, baseUrl);
        Element samlForm = doc.selectFirst("form");
        if (samlForm == null) throw new IOException("SAMLResponse form not found — URL: " + SafeUrl.redact(baseUrl));

        Response finalResp = post(samlForm.absUrl("action"), hiddenInputsOf(samlForm));
        String landingUrl = finalResp.request().url().toString();
        finalResp.body().string();
        finalResp.close();

        log.info("Login complete — landing URL: {}", SafeUrl.redact(landingUrl));
        if (!landingUrl.contains("education.oneadvanced.com")) {
            throw new IOException("Login failed — unexpected landing URL: " + SafeUrl.redact(landingUrl));
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
