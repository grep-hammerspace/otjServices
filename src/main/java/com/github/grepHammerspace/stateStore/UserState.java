package com.github.grepHammerspace.stateStore;

import com.github.grepHammerspace.web.Driver;

import java.util.concurrent.CompletableFuture;

public class UserState {
    private final String userId;
    private volatile Driver driver = null;
    private volatile CompletableFuture<Void> loginFuture = null;

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

    public void setLoginFuture(CompletableFuture<Void> future) {
        this.loginFuture = future;
    }

    public CompletableFuture<Void> getLoginFuture() {
        return loginFuture;
    }
}
