package com.github.grepHammerspace.stateStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

// Process-local: fine for one instance, but it must move to the database before scaling out.
public class UserStateStore {
    private static final Logger log = LoggerFactory.getLogger(UserStateStore.class);

    private final ConcurrentHashMap<String, UserState> usersToStates = new ConcurrentHashMap<>();

    public void createUserState(String userId) {
        sweepExpired();
        usersToStates.computeIfAbsent(userId, id -> {
            log.info("Created state for user {}", id);
            return new UserState(id);
        });
    }

    public UserState getStateForUser(String userId) {
        return usersToStates.get(userId);
    }

    private void sweepExpired() {
        for (UserState state : usersToStates.values()) {
            state.getSession();
        }
    }
}
