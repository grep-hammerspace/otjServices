package com.github.grepHammerspace.web;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OtjDriver {
    private static final Logger log = LoggerFactory.getLogger(OtjDriver.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");

    // Entry point for the OneAdvanced SSO/Keycloak discovery flow.
    // Double-encoded redirectUri passes through two layers of redirect before landing
    // back at education.oneadvanced.com after a successful login.
    private static final String DISCOVER_URL =
            "https://auth.identity.oneadvanced.com/auth/discover"
            + "?redirectUri=https%3A%2F%2Feducation.oneadvanced.com%2Fparseauth"
            + "%3FredirectUri%3Dhttps%253A%252F%252Feducation.oneadvanced.com%252F";

    private static final String ACTIVITY_LOG_API =
            "https://education.oneadvanced.com/api/cloud-education/v1/learner/%s/activity-log";

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:151.0) Gecko/20100101 Firefox/151.0";

    private final OkHttpClient httpClient;
    private String mfaActionUrl;
    private final ActivityLogRepository activityLogRepository;
    private final ObjectMapper mapper;

    @Inject
    public OtjDriver(ActivityLogRepository activityLogRepository) {
        this.activityLogRepository = activityLogRepository;
        this.mapper = new ObjectMapper();

        this.httpClient = new OkHttpClient.Builder()
                .cookieJar(new InMemoryCookieJar())
                .followRedirects(true)
                .build();
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
    }

    /**
     * Performs the multi-step Keycloak OIDC login flow over plain HTTP, stopping at the
     * TOTP/MFA page. The session cookies and the MFA form action URL are retained in
     * this instance so the caller can supply the OTP code separately.
     */
    public OtjDriver prepareBrowser(String username, String password) throws IOException {
        boolean isEmail = username.contains("@");

        Request initialRequest = new Request.Builder()
                .url(DISCOVER_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();

        Response resp = httpClient.newCall(initialRequest).execute();
        String currentUrl = resp.request().url().toString();
        String currentBody = resp.body().string();
        resp.close();

        for (int step = 1; step <= 5; step++) {
            Document doc = Jsoup.parse(currentBody, currentUrl);

            if (doc.selectFirst("input[name=otp]") != null) {
                Element form = doc.selectFirst("form");
                if (form == null) throw new IOException("MFA page has no form — URL: " + currentUrl);
                mfaActionUrl = form.absUrl("action");
                log.info("MFA page reached after {} step(s), action={}", step - 1, mfaActionUrl);
                return this;
            }

            Element form = doc.selectFirst("form");
            if (form == null) {
                throw new IOException("No form found at step " + step + " — URL: " + currentUrl);
            }

            FormBody.Builder formBody = new FormBody.Builder();
            for (Element input : form.select("input")) {
                String name = input.attr("name");
                String type = input.attr("type").toLowerCase();
                if (name.isEmpty()) continue;

                if (type.equals("password")) {
                    formBody.add(name, password);
                } else if (name.equals("emailOrUsername")) {
                    // The discovery page's JS renames this field to "email" or "username"
                    // before submitting, depending on the format of the input value.
                    formBody.add(isEmail ? "email" : "username", username);
                } else if (name.equals("username")) {
                    formBody.add(name, username);
                } else if (type.equals("hidden")) {
                    formBody.add(name, input.val());
                }
            }

            String action = form.absUrl("action");
            log.info("Step {} — POSTing to {}", step, action);

            Request postRequest = new Request.Builder()
                    .url(action)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .post(formBody.build())
                    .build();

            resp = httpClient.newCall(postRequest).execute();
            currentUrl = resp.request().url().toString();
            currentBody = resp.body().string();
            resp.close();
        }

        throw new IOException("Did not reach MFA page after 5 steps — last URL: " + currentUrl);
    }

    /**
     * Completes login by POSTing the TOTP code to the stored MFA form action URL.
     * Keycloak follows the OIDC callback chain and sets the final session cookies,
     * which the cookie jar carries automatically into subsequent API calls.
     */
    public void submitMfaToken(String mfaToken) throws IOException {
        if (mfaActionUrl == null) {
            throw new IllegalStateException("No MFA action URL — call prepareBrowser first");
        }

        FormBody body = new FormBody.Builder().add("otp", mfaToken).build();
        Request request = new Request.Builder()
                .url(mfaActionUrl)
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            response.body().string(); // consume to complete the redirect chain
            log.info("MFA submitted, landing URL: {}", response.request().url());
        }
    }

    /**
     * Fetches all unposted OTJs from MongoDB and POSTs each one to the activity-log API.
     * The httpClient already carries the authenticated session cookies from the login flow.
     */
    public OtjSubmitResult LogAllPendingOtjs(String userId) {
        List<ActivityLog> pending = activityLogRepository.getUnpostedActivityLogsFor(userId);

        if (pending.isEmpty()) {
            log.info("No unposted OTJs found for user {}", userId);
            return new OtjSubmitResult(List.of(), List.of());
        }

        String postUrl = String.format(ACTIVITY_LOG_API, pending.get(0).learnerId());
        log.info("Submitting {} pending OTJ(s) to {} for user {}", pending.size(), postUrl, userId);

        List<String> posted = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (ActivityLog activityLog : pending) {
            try {
                String json = mapper.writeValueAsString(buildPayload(activityLog));

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
                        log.warn("Failed to post activity log {} — HTTP {} body: {}",
                                activityLog.id(), response.code(),
                                response.body() != null ? response.body().string() : "null");
                    }
                }
            } catch (Exception e) {
                failed.add(activityLog.id());
                log.error("Exception posting activity log {}: {}", activityLog.id(), e.getMessage());
            }
        }

        log.info("Done — {}/{} posted, {} failed", posted.size(), pending.size(), failed.size());
        return new OtjSubmitResult(posted, failed);
    }

    private Map<String, Object> buildPayload(ActivityLog log) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("learnerId", log.learnerId());
        payload.put("activityImpact", log.activityImpact());
        payload.put("unitId", "ef974f73-5d9d-447e-8652-379ba9535229");
        payload.put("activityDate", log.activityDate().replace("/", "-"));
        payload.put("activityTime", "T" + log.activityTime() + ":00");
        payload.put("activityType", log.activityType());
        payload.put("hours", log.hours());
        payload.put("minutes", String.format("%02d", log.minutes()));
        OtjDriver.log.info("Posting payload: {}", payload);
        return payload;
    }
}
