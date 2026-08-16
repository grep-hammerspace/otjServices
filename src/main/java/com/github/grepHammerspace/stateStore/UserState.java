package com.github.grepHammerspace.stateStore;

import java.time.Instant;

/**
 * Per-user scratch space holding at most one prepared {@link LoginSession}.
 *
 * <p>One session per user is deliberate — a second prepare replaces the first, and the
 * replacement cancels the outgoing session's background poll so an orphaned virtual thread is
 * not left talking to Microsoft on behalf of a session nobody can reach.
 */
public class UserState {
    private final String userId;
    private volatile LoginSession session = null;

    public UserState(String userId) {
        this.userId = userId;
    }

    public String userId() {
        return userId;
    }

    /** Replaces the current session, cancelling the outgoing one's background login. */
    public synchronized void setSession(LoginSession newSession) {
        LoginSession previous = this.session;
        if (previous != null && previous.future() != null) {
            previous.future().cancel(true);
        }
        this.session = newSession;
    }

    /**
     * Returns the prepared session, or {@code null} if there is none or it has aged out.
     *
     * <p>An expired session is cleared on the way past rather than merely hidden, so the driver
     * and its cookies become garbage instead of lingering until the user happens to prepare again.
     */
    public synchronized LoginSession getSession() {
        LoginSession current = this.session;
        if (current == null) return null;
        if (current.isExpired(Instant.now())) {
            clearSession();
            return null;
        }
        return current;
    }

    /** Drops the session and cancels any background login still running for it. */
    public synchronized void clearSession() {
        LoginSession current = this.session;
        if (current != null && current.future() != null) {
            current.future().cancel(true);
        }
        this.session = null;
    }
}
