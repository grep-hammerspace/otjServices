package com.github.grepHammerspace.stateStore;

import com.github.grepHammerspace.web.Driver;

public class UserState {
    private final String userId;
    private Driver driver = null;

    public UserState(String userId, Driver driver){
        this.userId = userId;
        if (driver != null) {
            this.driver = driver;
        }
    }

    public void setDriver(Driver newDriver){
        this.driver = newDriver;
    }
    public Driver getDriver(){
        return this.driver;
    }
}
