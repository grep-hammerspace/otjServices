package com.github.grepHammerspace.auth;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import org.bson.Document;
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
import java.util.concurrent.TimeUnit;

/**
 * Issues and resolves opaque bearer session tokens.
 *
 * <p>A token is 32 bytes of {@link SecureRandom}, base64url-encoded. Only its SHA-256 hash is
 * persisted in the {@code sessions} collection — the raw token is returned once at issue time
 * and never stored, the same model as a GitHub PAT. A database compromise therefore leaks
 * nothing that can authenticate a request.
 *
 * <p>Expiry is sliding: a resolve pushes {@code expiresAt} back out to {@code now + 30 days},
 * but only when less than half the window remains, so an active user never re-authenticates
 * while the collection avoids a write on every request. A token unused for 30 days dies on its
 * own — enforced both at resolve time and by a Mongo TTL index.
 */
@Singleton
public class SessionTokenService {
    private static final Logger log = LoggerFactory.getLogger(SessionTokenService.class);

    static final Duration TOKEN_WINDOW = Duration.ofDays(30);
    private static final int TOKEN_BYTES = 32;

    private final MongoCollection<Document> sessions;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Inject
    public SessionTokenService(MongoDatabase database) {
        this(database, Clock.systemUTC());
    }

    /** Visible for tests — lets expiry and sliding-extension behaviour be driven by a fixed clock. */
    // Is is the establised pattern to inject a database instead of a Repository class like we do for activity logs nad users
    SessionTokenService(MongoDatabase database, Clock clock) {
        this.sessions = database.getCollection("sessions");
        this.clock = clock;
        sessions.createIndex(Indexes.ascending("tokenHash"), new IndexOptions().unique(true));
        sessions.createIndex(Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
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
        sessions.insertOne(new Document()
                .append("tokenHash", sha256(rawToken))
                .append("userId", userId)
                .append("createdAt", now)
                .append("expiresAt", now.plus(TOKEN_WINDOW)));
        log.info("Issued session token for user {}", userId);
        return rawToken;
    }

    /**
     * Resolves a raw token to its userId, or empty if the token is unknown or expired.
     * Extends the sliding window when less than half of it remains.
     */
    // So when making a session token, we create a hash of userId + 32 random bytes, and persist the hashed version, and return the raw version
    // then when we resolve, we take what comes in on the rquest, hash it and check if it matches sthg in the db, to make sure they match
    // How is this better than just taking hte hash of userId? it is better in the sense that you cant predict the hash of a userid, so you cant get it
    // ahead of time. this doenst stop people from sharing session tokens though, if they do do that, then it will be have happened willingly bc traffic is tls
    // protected. If you give someone ur session token, they can log things as you, they can delete unposte otjs, but they cant post anything
    public Optional<String> resolve(String rawToken) {
        Document session = sessions.find(Filters.eq("tokenHash", sha256(rawToken))).first();
        if (session == null) return Optional.empty();

        Instant now = clock.instant();
        Instant expiresAt = session.getDate("expiresAt").toInstant();
        if (!expiresAt.isAfter(now)) return Optional.empty();

        if (Duration.between(now, expiresAt).compareTo(TOKEN_WINDOW.dividedBy(2)) < 0) {
            sessions.updateOne(Filters.eq("_id", session.getObjectId("_id")),
                    Updates.set("expiresAt", now.plus(TOKEN_WINDOW)));
        }
        return Optional.of(session.getString("userId"));
    }

    /** Deletes the session for {@code rawToken}, revoking it immediately. */
    public boolean revoke(String rawToken) {
        return sessions.deleteOne(Filters.eq("tokenHash", sha256(rawToken))).getDeletedCount() > 0;
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
