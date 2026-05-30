package com.github.grepHammerspace.stateStore;

import com.github.grepHammerspace.web.OtjDriver;

/**
 *  Data class to group useful information about a user and their web driver.
 */
public class UserState {
    private final String userId;
    private OtjDriver driver = null;

    public UserState(String userId, OtjDriver driver){
        this.userId = userId;
        if (driver != null) {
            this.driver = driver;
        }
    }

    public void setDriver(OtjDriver newDriver){
        this.driver = newDriver;
    }
    public OtjDriver getDriver(){
        return this.driver;
    }
}
