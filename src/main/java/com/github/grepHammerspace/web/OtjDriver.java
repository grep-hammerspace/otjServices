package com.github.grepHammerspace.web;

import com.github.grepHammerspace.db.ActivityLogRepository;
import okhttp3.FormBody;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.inject.Inject;
import java.io.IOException;

public class OtjDriver extends OneAdvancedDriver {
    // redirectUri is double-encoded: it passes through two redirects.
    private static final String DISCOVER_URL =
            "https://auth.identity.oneadvanced.com/auth/discover"
            + "?redirectUri=https%3A%2F%2Feducation.oneadvanced.com%2Fparseauth"
            + "%3FredirectUri%3Dhttps%253A%252F%252Feducation.oneadvanced.com%252F";

    private String mfaActionUrl;

    @Inject
    public OtjDriver(ActivityLogRepository activityLogRepository) {
        super(activityLogRepository);
    }

    @Override
    public PrepareResult prepare(String username, String password) throws IOException {
        boolean isEmail = username.contains("@");

        Response resp = get(DISCOVER_URL);
        String currentUrl = resp.request().url().toString();
        String currentBody = resp.body().string();
        resp.close();

        for (int step = 1; step <= 5; step++) {
            Document doc = Jsoup.parse(currentBody, currentUrl);

            if (doc.selectFirst("input[name=otp]") != null) {
                Element form = doc.selectFirst("form");
                if (form == null) throw new LoginChainException("MFA page has no form — URL: " + SafeUrl.redact(currentUrl));
                mfaActionUrl = form.absUrl("action");
                // Keycloak action URLs carry session_code / execution / tab_id, which identify
                // this live authentication attempt.
                log.info("MFA page reached after {} step(s), action={}", step - 1, SafeUrl.redact(mfaActionUrl));
                return PrepareResult.otpRequired();
            }

            Element form = doc.selectFirst("form");
            if (form == null) {
                throw new LoginChainException("No form found at step " + step + " — URL: " + SafeUrl.redact(currentUrl));
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
            log.info("Step {} — POSTing to {}", step, SafeUrl.redact(action));

            resp = post(action, formBody.build());
            currentUrl = resp.request().url().toString();
            currentBody = resp.body().string();
            resp.close();
        }

        throw new LoginChainException("Did not reach MFA page after 5 steps — last URL: " + SafeUrl.redact(currentUrl));
    }

    @Override
    public void completeMfa(String mfaToken) throws IOException {
        if (mfaActionUrl == null) {
            throw new IllegalStateException("No MFA action URL — call prepare first");
        }

        try (Response response = post(mfaActionUrl, new FormBody.Builder().add("otp", mfaToken).build())) {
            response.body().string();
            String landingUrl = response.request().url().toString();
            log.info("MFA submitted, landing URL: {}", SafeUrl.redact(landingUrl));
            log.debug("Cookies after MFA: {}", cookieJar.cookieNames());
            if (!landingUrl.startsWith("https://education.oneadvanced.com")) {
                throw new LoginChainException("MFA code rejected — still on login page. Use a fresh OTP and try again.");
            }
        }
    }
}
