package com.github.grepHammerspace.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.grepHammerspace.db.ActivityLogRepository;
import org.openqa.selenium.firefox.FirefoxOptions;
import com.github.grepHammerspace.db.model.ActivityLog;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.openqa.selenium.*;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Wrapper class around the basic Selenium WebDriver, it allows for individual WebDrivers to be created and used to login
 * in one clean package.
 */
public class OtjDriver {
    private static final Logger log = LoggerFactory.getLogger(OtjDriver.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final String LOGIN_URL = "https://education.oneadvanced.com/";
    private static final String TIMELOG_URL = "https://education.oneadvanced.com/cloud-education/timelog";
    private static final String ACTIVITY_LOG_API = "https://education.oneadvanced.com/api/cloud-education/v1/learner/%s/activity-log";

    private final WebDriver driver;
    private final ActivityLogRepository activityLogRepository;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    @Inject
    public OtjDriver(ActivityLogRepository activityLogRepository){
        this.activityLogRepository = activityLogRepository;
        FirefoxOptions options = new FirefoxOptions();
        options.addArguments("--headless");
//        options.setBinary("Path to firefox if debugging locally");
        this.driver = new FirefoxDriver(options);
        this.http = new OkHttpClient();
        this.mapper = new ObjectMapper();
    }


    /**
     *  Follows log in process all the way up to the page where we submit the MFA token, it leaves the browser open and ready for the token to be submitted.
     * @param username
     * @param password
     * @return (hopefully) stateful browser session
     * @throws InterruptedException
     */
    public OtjDriver prepareBrowser(String username, String password) throws InterruptedException {
        driver.get(LOGIN_URL);
        waitForElement(driver, By.name("emailOrUsername")).sendKeys(username + Keys.RETURN);
        waitForElement(driver, By.name("username")).sendKeys(Keys.RETURN);
        waitForElement(driver, By.id("password")).sendKeys(password, Keys.RETURN);
        waitForElement(driver, By.id("otp"));

        return this;
    }

    /**
     * Use mfa token to sign in, when this exits, we should be fully authenticated and logged in.
     * @param mfaToken
     * @throws InterruptedException
     */
    public void submitMfaToken(String mfaToken) throws InterruptedException {
        String originalUrl = driver.getCurrentUrl();
        waitForElement(driver, By.id("otp")).sendKeys(mfaToken + Keys.RETURN);

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!driver.getCurrentUrl().equals(originalUrl)) return;
            try {
                WebElement error = driver.findElement(By.id("error_message"));
                if (error.isDisplayed()) throw new IllegalStateException("MFA code was incorrect: " + error.getText());
            } catch (NoSuchElementException ignored) {}
            Thread.sleep(500);
        }
        throw new TimeoutException("MFA timed out — URL did not change within 30s");
    }

    /**
     * Get all unposted OTJs from Mongo and post them one by one to the OTJ API using the
     * authenticated browser session cookies.
     */
    public OtjSubmitResult LogAllPendingOtjs(String userId) {
        List<ActivityLog> pending = activityLogRepository.getUnpostedActivityLogsFor(userId);

        if (pending.isEmpty()) {
            log.info("No unposted OTJs found for user {}", userId);
            return new OtjSubmitResult(List.of(), List.of());
        }

        driver.get(TIMELOG_URL);
        String cookieHeader = driver.manage().getCookies().stream()
                .map(c -> c.getName() + "=" + c.getValue())
                .collect(Collectors.joining("; "));

        String postUrl = String.format(ACTIVITY_LOG_API, pending.get(0).learnerId());
        log.info("Submitting {}  pending OTJ(s) to {} for user {}", pending.size(), postUrl, userId);

        List<String> posted = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (ActivityLog log : pending) {
            try {
                String json = mapper.writeValueAsString(buildPayload(log));

                Request request = new Request.Builder()
                        .url(postUrl)
                        .addHeader("Cookie", cookieHeader)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(json, JSON_TYPE))
                        .build();

                try (okhttp3.Response response = http.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        activityLogRepository.markAsPosted(log);
                        posted.add(log.id());
                        OtjDriver.log.info("Posted activity log {} ({})", log.id(), log.activityDate());
                    } else {
                        failed.add(log.id());
                        OtjDriver.log.warn("Failed to post activity log {} — HTTP {} body: {}", log.id(), response.code(), response.body() != null ? response.body().string() : "null");
                    }
                }
            } catch (Exception e) {
                failed.add(log.id());
                OtjDriver.log.error("Exception posting activity log {}: {}", log.id(), e.getMessage());
            }
        }

        OtjDriver.log.info("Done — {}/{} posted, {} failed", posted.size(), pending.size(), failed.size());
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

    private void waitForUrlChange(WebDriver driver, String originalUrl) throws InterruptedException {
        waitForUrlChange(driver, originalUrl, 30, 500);
    }

    private void waitForUrlChange(WebDriver driver, String originalUrl, int timeoutSeconds, long pollIntervalMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!driver.getCurrentUrl().equals(originalUrl)) {
                return;
            }
            Thread.sleep(pollIntervalMs);
        }
        throw new TimeoutException("URL did not change from " + originalUrl + " within " + timeoutSeconds + "s — MFA may have failed or timed out");
    }

    private WebElement waitForElement(WebDriver driver, By by) throws InterruptedException {
        return waitForElement(driver, by, 10, 250);
    }

    private WebElement waitForElement(WebDriver driver, Function<String, By> by, String selector) throws InterruptedException {
        return waitForElement(driver, by.apply(selector), 10, 250);
    }

    private WebElement waitForElement(WebDriver driver, Function<String, By> by, String selector, int timeoutSeconds, long pollIntervalMs) throws InterruptedException {
        return waitForElement(driver, by.apply(selector), timeoutSeconds, pollIntervalMs);
    }

    private WebElement waitForElement(WebDriver driver, By by, int timeoutSeconds, long pollIntervalMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (true) {
            try {
                return driver.findElement(by);
            } catch (NoSuchElementException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new TimeoutException("Element '" + by + "' not found within " + timeoutSeconds + "s");
                }
                Thread.sleep(pollIntervalMs);
            }
        }
    }
}
