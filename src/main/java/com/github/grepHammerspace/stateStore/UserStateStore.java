package com.github.grepHammerspace.stateStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import  java.util.concurrent.ConcurrentHashMap;
/**
 * Simple ConcurrentHashMap-based state store. It will be used to maintain state for active Users, we do this so that everyone cna have their own
 * WebDriver instance ready to use when they need to log in, this is mostly for speed because MFA tokens are only alive for 30 seconds.
 */

public class UserStateStore {

    private static final Logger log = LoggerFactory.getLogger(UserStateStore.class);

    private final  ConcurrentHashMap<String, UserState> usersToStates = new ConcurrentHashMap<>();

    public void createUserState(String userId){
        // If user already has a UserState, return and do nothing
        if (usersToStates.containsKey(userId)){
            return;
        }

        //otherwise create one and return it
        UserState newUserState = new UserState(userId, null);
        usersToStates.put(userId, newUserState);
        log.info("Created state for user {}", userId);
    }

    public UserState getStateForUser(String userId){
            return usersToStates.get(userId);
    }

}
