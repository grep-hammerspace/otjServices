package com.github.grepHammerspace.stateStore;

import org.openqa.selenium.WebDriver;

/**
 * DTO Record class to group useful information about a user and their web driver.
 * @param userId
 * @param driver
 */
public record UserState(String userId, WebDriver driver) {}
