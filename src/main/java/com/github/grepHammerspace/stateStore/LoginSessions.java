package com.github.grepHammerspace.stateStore;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

// One parked login per user. Process-local: fine for one instance, but it must move to the database
// before scaling out.
@Singleton
public class LoginSessions {
    private final ConcurrentHashMap<String, LoginSession> byUser = new ConcurrentHashMap<>();

    @Inject
    public LoginSessions() {}

    // A second prepare replaces the first and stops its background poll.
    public void put(String userId, LoginSession session) {
        sweepExpired();
        stop(byUser.put(userId, session));
    }

    // Expired sessions are removed, not just hidden, so their cookies don't linger.
    public LoginSession get(String userId) {
        sweepExpired();
        return byUser.get(userId);
    }

    // Only this session: a prepare that raced in after it is left alone.
    public void remove(String userId, LoginSession session) {
        if (byUser.remove(userId, session)) stop(session);
    }

    private void sweepExpired() {
        Instant now = Instant.now();
        byUser.forEach((userId, session) -> {
            if (session.isExpired(now)) remove(userId, session);
        });
    }

    private static void stop(LoginSession session) {
        if (session != null) session.login().cancel(true);
    }
}
