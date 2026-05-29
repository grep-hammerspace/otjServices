package com.github.grepHammerspace.webDriver;

import org.openqa.selenium.*;
import org.openqa.selenium.firefox.FirefoxDriver;

import java.util.function.Function;

/**
 * Wrapper class around the basic Selenium WebDriver, it allows for individual WebDrivers to be created and used to login
 * in one clean package.
 */
public class OtjDriver {

    private WebDriver driver;
    private final String LOGIN_URL = "https://education.oneadvanced.com/";

    public OtjDriver(){
        this.driver = new FirefoxDriver();
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
        waitForElement(driver, By.name("username")).sendKeys(username + Keys.RETURN);
        waitForElement(driver, By.id("user_password")).sendKeys(password, Keys.RETURN);
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
        waitForUrlChange(driver, originalUrl);
    }

    /**
     * Get all unposted otjs from Mongo and post them one by one
     */
    public void LogAllPendingOtjs() {
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
