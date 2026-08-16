package com.github.grepHammerspace.stateStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple ConcurrentHashMap-based state store, keeping each user's logged-in
 * {@link com.github.grepHammerspace.web.Driver} session alive between the prepare call and the
 * one that completes MFA. That gap has to be crossed in-process because a TOTP is only valid for
 * about 30 seconds, and because the Azure push flow parks a half-finished Microsoft login.
 *
 * <p>Sessions age out after {@link LoginSession#TTL}; the map entries themselves are kept, since
 * one empty {@link UserState} per user is cheap and it is the session that holds the secrets.
 * Expired sessions are swept opportunistically on {@link #createUserState}, which every
 * authenticated request already calls — no scheduler, and no reliance on the owning user ever
 * coming back.
 *
 * <p>This is process-local, so it does not survive a restart and does not work behind more than
 * one instance. Both are fine today (single container) and both are the reason to move this to
 * the session collection before scaling out.
 */
public class UserStateStore {

    private static final Logger log = LoggerFactory.getLogger(UserStateStore.class);

    private final ConcurrentHashMap<String, UserState> usersToStates = new ConcurrentHashMap<>();

    /** Creates an empty {@link UserState} for {@code userId} if one doesn't already exist. Idempotent. */
    public void createUserState(String userId) {
        sweepExpired();
        usersToStates.computeIfAbsent(userId, id -> {
            log.info("Created state for user {}", id);
            return new UserState(id);
        });
    }

    /** Returns the {@link UserState} for {@code userId}, or {@code null} if none exists. */
    public UserState getStateForUser(String userId) {
        return usersToStates.get(userId);
    }

    /**
     * Drops sessions past their TTL across every user.
     *
     * <p>Reads through {@link UserState#getSession()}, whose own expiry check does the clearing —
     * so the sweep cannot disagree with what a request would have seen.
     */
    private void sweepExpired() {
        for (UserState state : usersToStates.values()) {
            state.getSession();
        }
    }
}
