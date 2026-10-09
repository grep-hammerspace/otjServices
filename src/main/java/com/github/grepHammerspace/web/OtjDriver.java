package com.github.grepHammerspace.web;

import com.github.grepHammerspace.db.ActivityLogRepository;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.inject.Inject;
import java.io.IOException;
import java.util.TreeSet;

// OneAdvanced's own login for a QMUL account: QMUL's Keycloak form asks for the username, then the
// password, then stops at the authenticator code. Starts at QMUL's realm rather than OneAdvanced's
// discover page, which only recognised se24.qmul.ac.uk emails and sent anything else back to itself.
public class OtjDriver extends OneAdvancedDriver {
    private static final int MAX_STEPS = 5;

    private String mfaActionUrl;

    @Inject
    public OtjDriver(ActivityLogRepository activityLogRepository) {
        super(activityLogRepository);
    }

    @Override
    public PrepareResult prepare(String username, String password) throws IOException {
        Response resp = get(qmulLoginUrl(null));
        String currentUrl = resp.request().url().toString();
        String currentBody = resp.body().string();
        resp.close();

        String previousForm = null;
        for (int step = 1; step <= MAX_STEPS; step++) {
            HttpUrl at = HttpUrl.parse(currentUrl);
            if (at != null && at.host().equals("login.microsoftonline.com")) {
                log.info("Keycloak sent the account to Microsoft at step {}", step);
                throw new MicrosoftAccountException();
            }

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

            // The same form again means Keycloak refused what it was just given; sending it again
            // would only repeat the refusal.
            String thisForm = formSignature(form);
            if (thisForm.equals(previousForm)) {
                throw new LoginChainException("Login form came back unchanged at step " + step
                        + " — " + thisForm + ". Credentials may be wrong.");
            }
            previousForm = thisForm;

            FormBody.Builder formBody = new FormBody.Builder();
            for (Element input : form.select("input")) {
                String name = input.attr("name");
                String type = input.attr("type").toLowerCase();
                if (name.isEmpty()) continue;

                if (type.equals("password")) {
                    formBody.add(name, password);
                } else if (name.equals("username")) {
                    formBody.add(name, username);
                } else if (type.equals("hidden")) {
                    formBody.add(name, input.val());
                }
            }

            String action = form.absUrl("action");
            log.info("Step {} — POSTing {} to {}", step, thisForm, SafeUrl.redact(action));

            resp = post(action, formBody.build());
            currentUrl = resp.request().url().toString();
            currentBody = resp.body().string();
            resp.close();
        }

        throw new LoginChainException("Did not reach MFA page after " + MAX_STEPS + " steps — last URL: "
                + SafeUrl.redact(currentUrl));
    }

    // What a form asks for, without anything identifying: its action path and its input names. Two
    // steps with the same signature are the same page — Keycloak's username-then-password pages share
    // an action path but not their fields.
    static String formSignature(Element form) {
        HttpUrl action = HttpUrl.parse(form.absUrl("action"));
        TreeSet<String> names = new TreeSet<>();
        for (Element input : form.select("input")) {
            if (!input.attr("name").isEmpty()) names.add(input.attr("name"));
        }
        return (action == null ? "?" : action.encodedPath()) + " " + names;
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
