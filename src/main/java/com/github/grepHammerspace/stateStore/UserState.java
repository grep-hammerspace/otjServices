package com.github.grepHammerspace.stateStore;

import java.time.Instant;

// One session per user: a second prepare replaces the first and cancels its background poll.
public class UserState {
    private final String userId;
    private volatile LoginSession session = null;

    public UserState(String userId) {
        this.userId = userId;
    }

    public String userId() {
        return userId;
    }

    public synchronized void setSession(LoginSession newSession) {
        LoginSession previous = this.session;
        if (previous != null && previous.future() != null) {
            previous.future().cancel(true);
        }
        this.session = newSession;
    }

    // Cleared, not just hidden, so an expired session's cookies don't linger.
    public synchronized LoginSession getSession() {
        LoginSession current = this.session;
        if (current == null) return null;
        if (current.isExpired(Instant.now())) {
            clearSession();
            return null;
        }
        return current;
    }

    public synchronized void clearSession() {
        LoginSession current = this.session;
        if (current != null && current.future() != null) {
            current.future().cancel(true);
        }
        this.session = null;
    }
}
