package com.github.grepHammerspace.sessions;

import org.openqa.selenium.WebDriver;

public record Session(String userId, WebDriver driver) {}
