package com.github.grepHammerspace.db;

import com.github.grepHammerspace.db.model.InviteCode;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Singleton
public class InviteCodeRepository {
    private static final Logger log = LoggerFactory.getLogger(InviteCodeRepository.class);

    private final MongoCollection<Document> collection;
    private final Clock clock;

    @Inject
    public InviteCodeRepository(MongoDatabase database) {
        this(database, Clock.systemUTC());
    }

    InviteCodeRepository(MongoDatabase database, Clock clock) {
        this.collection = database.getCollection("inviteCodes");
        this.clock = clock;
        collection.createIndex(Indexes.ascending("code"), new IndexOptions().unique(true));
    }

    // One findOneAndUpdate: no gap for two people racing a code. It knows nothing about revocation
    // (revoke pulls expiresAt back instead); keep it that way.
    public boolean claim(String code, String usedByUserId) {
        Date now = Date.from(clock.instant());
        Document claimed = collection.findOneAndUpdate(
                Filters.and(
                        Filters.eq("code", code),
                        Filters.eq("used", false),
                        Filters.gt("expiresAt", now)),
                Updates.combine(
                        Updates.set("used", true),
                        Updates.set("usedBy", usedByUserId),
                        Updates.set("usedAt", now)));

        if (claimed == null) {
            log.info("Rejected invite code claim for user {}", usedByUserId);
            return false;
        }
        log.info("Invite code claimed by user {}", usedByUserId);
        return true;
    }

    // A duplicate code escapes rather than being retried: it means the generator is broken.
    public InviteCode create(String code, String note, Instant expiresAt, String createdBy) {
        InviteCode invite = new InviteCode(code, note, false, null, null,
                clock.instant(), expiresAt, createdBy, null, null);
        collection.insertOne(toDocument(invite));
        log.info("Invite code minted by {} expiring {}", createdBy, expiresAt);
        return invite;
    }

    public List<InviteCode> list() {
        List<InviteCode> codes = new ArrayList<>();
        collection.find().sort(Sorts.descending("createdAt")).forEach(doc -> codes.add(fromDocument(doc)));
        return codes;
    }

    public enum RevokeResult { REVOKED, NOT_FOUND, ALREADY_USED }

    // Pulling expiresAt back to now is what stops the code. Conditional on used: false, so a
    // concurrent claim either loses or yields ALREADY_USED.
    public RevokeResult revoke(String code, String revokedBy) {
        Date now = Date.from(clock.instant());
        Document revoked = collection.findOneAndUpdate(
                Filters.and(
                        Filters.eq("code", code),
                        Filters.eq("used", false)),
                Updates.combine(
                        Updates.set("revokedAt", now),
                        Updates.set("revokedBy", revokedBy),
                        Updates.set("expiresAt", now)));

        if (revoked != null) {
            log.info("Invite code revoked by {}", revokedBy);
            return RevokeResult.REVOKED;
        }

        boolean exists = collection.countDocuments(Filters.eq("code", code)) > 0;
        log.info("Invite code revocation by {} rejected — {}", revokedBy, exists ? "already used" : "unknown code");
        return exists ? RevokeResult.ALREADY_USED : RevokeResult.NOT_FOUND;
    }

    private static Document toDocument(InviteCode invite) {
        return new Document()
                .append("code", invite.code())
                .append("note", invite.note())
                .append("used", invite.used())
                .append("usedBy", invite.usedBy())
                .append("usedAt", toDate(invite.usedAt()))
                .append("createdAt", toDate(invite.createdAt()))
                .append("expiresAt", toDate(invite.expiresAt()))
                .append("createdBy", invite.createdBy())
                .append("revokedAt", toDate(invite.revokedAt()))
                .append("revokedBy", invite.revokedBy());
    }

    private static InviteCode fromDocument(Document doc) {
        if (doc == null) return null;
        return new InviteCode(
                doc.getString("code"),
                doc.getString("note"),
                Boolean.TRUE.equals(doc.getBoolean("used")),
                doc.getString("usedBy"),
                toInstant(doc.getDate("usedAt")),
                toInstant(doc.getDate("createdAt")),
                toInstant(doc.getDate("expiresAt")),
                doc.getString("createdBy"),
                toInstant(doc.getDate("revokedAt")),
                doc.getString("revokedBy"));
    }

    private static Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
