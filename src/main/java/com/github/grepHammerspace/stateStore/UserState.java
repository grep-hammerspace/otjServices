package com.github.grepHammerspace.stateStore;

import com.github.grepHammerspace.web.OtjDriver;

/**
 * DTO Record class to group useful information about a user and their web driver.
 * @param userId
 * @param driver
 */
public record UserState(String userId, OtjDriver driver) {}
