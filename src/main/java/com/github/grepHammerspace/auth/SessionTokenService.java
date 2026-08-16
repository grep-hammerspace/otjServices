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

/**
 * Issues and resolves opaque bearer session tokens.
 *
 * <p>A token is 32 bytes of {@link SecureRandom}, base64url-encoded. Only its SHA-256 hash is
 * persisted — the raw token is returned once at issue time and never stored, the same model as a
 * GitHub PAT. A database compromise therefore leaks nothing that can authenticate a request.
 *
 * <p>Expiry is sliding: a resolve pushes {@code expiresAt} back out to {@code now + 30 days},
 * but only when less than half the window remains, so an active user never re-authenticates
 * while the collection avoids a write on every request. A token unused for 30 days dies on its
 * own — enforced here at resolve time, and by the TTL index {@link SessionRepository} declares.
 */
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

    /** Visible for tests — lets expiry and sliding-extension behaviour be driven by a fixed clock. */
    SessionTokenService(SessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * Issues a new token for {@code userId} and returns the raw value. The caller must hand it
     * to the client immediately — it cannot be recovered afterwards.
     */
    public String issue(String userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant now = clock.instant();
        sessions.insert(new Session(null, sha256(rawToken), userId, now, now.plus(TOKEN_WINDOW)));
        log.info("Issued session token for user {}", userId);
        return rawToken;
    }

    /**
     * Resolves a raw token to its userId, or empty if the token is unknown or expired.
     * Extends the sliding window when less than half of it remains.
     */
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

    /** Deletes the session for {@code rawToken}, revoking it immediately. */
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
