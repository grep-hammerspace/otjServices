package com.github.grepHammerspace.db;

import com.github.grepHammerspace.db.model.Session;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB-backed store for bearer sessions.
 *
 * <p>Two indexes back the collection: a unique one on {@code tokenHash}, which is also the only
 * field sessions are ever looked up by, and a TTL index on {@code expiresAt} that lets Mongo reap
 * dead sessions without the application having to sweep. Expiry <em>policy</em> — how long a
 * window is and when it slides — lives in
 * {@link com.github.grepHammerspace.auth.SessionTokenService}; this class only stores what it is
 * told.
 */
@Singleton
public class SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionRepository.class);

    private final MongoCollection<Document> collection;

    @Inject
    public SessionRepository(MongoDatabase database) {
        this.collection = database.getCollection("sessions");
        collection.createIndex(Indexes.ascending("tokenHash"), new IndexOptions().unique(true));
        collection.createIndex(Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    public void insert(Session session) {
        collection.insertOne(toDocument(session));
        log.debug("Stored session for user {}", session.userId());
    }

    /** Looks up a session by token hash, or null if there is none. Does not check expiry. */
    public Session findByTokenHash(String tokenHash) {
        return fromDocument(collection.find(Filters.eq("tokenHash", tokenHash)).first());
    }

    public void extendExpiry(String sessionId, Instant newExpiry) {
        collection.updateOne(
                Filters.eq("_id", new ObjectId(sessionId)),
                Updates.set("expiresAt", Date.from(newExpiry))
        );
        log.debug("Extended session {} to {}", sessionId, newExpiry);
    }

    /** Deletes the session with this token hash. Returns true if one was found and deleted. */
    public boolean deleteByTokenHash(String tokenHash) {
        return collection.deleteOne(Filters.eq("tokenHash", tokenHash)).getDeletedCount() > 0;
    }

    private static Document toDocument(Session session) {
        return new Document()
                .append("tokenHash", session.tokenHash())
                .append("userId", session.userId())
                .append("createdAt", session.createdAt() == null ? null : Date.from(session.createdAt()))
                .append("expiresAt", session.expiresAt() == null ? null : Date.from(session.expiresAt()));
    }

    private static Session fromDocument(Document doc) {
        if (doc == null) return null;
        Date createdAt = doc.getDate("createdAt");
        Date expiresAt = doc.getDate("expiresAt");
        return new Session(
                doc.getObjectId("_id").toHexString(),
                doc.getString("tokenHash"),
                doc.getString("userId"),
                createdAt == null ? null : createdAt.toInstant(),
                expiresAt == null ? null : expiresAt.toInstant()
        );
    }
}
