package com.github.grepHammerspace.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.grepHammerspace.db.ActivityLogRepository;
import com.github.grepHammerspace.db.model.ActivityLog;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// What both login flows share: the cookie jar a login fills, and the submit that spends it. Logs
// under the concrete driver's name, so logback.xml's per-driver levels still apply.
abstract class OneAdvancedDriver implements Driver {
    static final MediaType JSON_TYPE = MediaType.get("application/json; charset=UTF-8");

    static final String USER_AGENT =
            "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:151.0) Gecko/20100101 Firefox/151.0";

    private static final String ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    private static final String ACTIVITY_LOG_API =
            "https://education.oneadvanced.com/api/cloud-education/v1/learner/%s/activity-log";

    final Logger log = LoggerFactory.getLogger(getClass());
    final InMemoryCookieJar cookieJar = new InMemoryCookieJar();
    final OkHttpClient httpClient = new OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(true)
            .build();
    final ObjectMapper mapper = new ObjectMapper();
    private final ActivityLogRepository activityLogRepository;

    OneAdvancedDriver(ActivityLogRepository activityLogRepository) {
        this.activityLogRepository = activityLogRepository;
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
        // The API expects 16 here.
        payload.put("activityType", 16);
        payload.put("hours", activityLog.hours());
        payload.put("minutes", String.format("%02d", activityLog.minutes()));
        // Shape, not content: the payload carries the learner ID and the user's own notes.
        log.debug("Posting activity log {} — {}h{}m on {}", activityLog.id(),
                activityLog.hours(), activityLog.minutes(), activityLog.activityDate());
        return payload;
    }

    Response get(String url) throws IOException {
        return httpClient.newCall(browserRequest(url).build()).execute();
    }

    Response post(String url, RequestBody body) throws IOException {
        return httpClient.newCall(browserRequest(url).post(body).build()).execute();
    }

    private static Request.Builder browserRequest(String url) {
        return new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT_HTML)
                .header("Accept-Language", "en-US,en;q=0.9");
    }

    static final class InMemoryCookieJar implements CookieJar {
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

        // Names only: a cookie value is a replayable credential.
        synchronized List<String> cookieNames() {
            return store.stream().map(c -> "[" + c.domain() + "] " + c.name()).toList();
        }
    }
}
