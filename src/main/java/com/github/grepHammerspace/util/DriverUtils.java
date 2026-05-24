package com.github.grepHammerspace.util;

import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.function.Function;

public class DriverUtils {

    public static void waitForUrlChange(WebDriver driver, String originalUrl) throws InterruptedException {
        waitForUrlChange(driver, originalUrl, 30, 500);
    }

    public static void waitForUrlChange(WebDriver driver, String originalUrl, int timeoutSeconds, long pollIntervalMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!driver.getCurrentUrl().equals(originalUrl)) {
                return;
            }
            Thread.sleep(pollIntervalMs);
        }
        throw new TimeoutException("URL did not change from " + originalUrl + " within " + timeoutSeconds + "s — MFA may have failed or timed out");
    }

    public static WebElement waitForElement(WebDriver driver, By by) throws InterruptedException {
        return waitForElement(driver, by, 10, 250);
    }

    public static WebElement waitForElement(WebDriver driver, Function<String, By> by, String selector) throws InterruptedException {
        return waitForElement(driver, by.apply(selector), 10, 250);
    }

    public static WebElement waitForElement(WebDriver driver, Function<String, By> by, String selector, int timeoutSeconds, long pollIntervalMs) throws InterruptedException {
        return waitForElement(driver, by.apply(selector), timeoutSeconds, pollIntervalMs);
    }

    public static WebElement waitForElement(WebDriver driver, By by, int timeoutSeconds, long pollIntervalMs) throws InterruptedException {
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
