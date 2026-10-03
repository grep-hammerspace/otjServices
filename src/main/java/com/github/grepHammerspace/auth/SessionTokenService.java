package com.github.grepHammerspace.auth;

import com.github.grepHammerspace.db.SessionRepository;
import com.github.grepHammerspace.db.model.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

// Only the SHA-256 of a token is stored. Expiry slides, rewritten only when less than half the
// window remains.
@Singleton
public class SessionTokenService {
    private static final Logger log = LoggerFactory.getLogger(SessionTokenService.class);

    static final Duration TOKEN_WINDOW = Duration.ofDays(30);
    private static final int TOKEN_BYTES = 32;

    private final SessionRepository sessions;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Inject
    public SessionTokenService(SessionRepository sessions) {
        this(sessions, Clock.systemUTC());
    }

    SessionTokenService(SessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    public String issue(String userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant now = clock.instant();
        sessions.insert(new Session(null, sha256(rawToken), userId, now, now.plus(TOKEN_WINDOW)));
        log.info("Issued session token for user {}", userId);
        return rawToken;
    }

    public Optional<String> resolve(String rawToken) {
        Session session = sessions.findByTokenHash(sha256(rawToken));
        if (session == null) return Optional.empty();

        Instant now = clock.instant();
        Instant expiresAt = session.expiresAt();
        if (!expiresAt.isAfter(now)) return Optional.empty();

        if (Duration.between(now, expiresAt).compareTo(TOKEN_WINDOW.dividedBy(2)) < 0) {
            sessions.extendExpiry(session.id(), now.plus(TOKEN_WINDOW));
        }
        return Optional.of(session.userId());
    }

    public boolean revoke(String rawToken) {
        return sessions.deleteByTokenHash(sha256(rawToken));
    }

    private static String sha256(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
