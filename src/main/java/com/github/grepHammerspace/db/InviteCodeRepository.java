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

/**
 * MongoDB-backed store for signup invite codes.
 *
 * <p>Two callers, with very different exposure. {@link #claim} is reached anonymously from
 * {@code POST /auth/signup} and is the only method on the internet-facing path. The rest —
 * {@link #create}, {@link #list}, {@link #revoke} — back the admin API, which is reachable only
 * over the tailnet. A document looks like:
 *
 * <pre>{@code
 * { "code": "OTJ-7F3K-9QMX", "used": false, "note": "for Sam",
 *   "createdAt": ISODate, "expiresAt": ISODate, "usedBy": null, "usedAt": null,
 *   "createdBy": "sam@example.com", "revokedAt": null, "revokedBy": null }
 * }</pre>
 *
 * <p>{@code createdBy} and {@code revokedBy} are absent on codes minted by hand before the admin
 * API existed; every read tolerates that.
 */
@Singleton
public class InviteCodeRepository {

    private static final Logger log = LoggerFactory.getLogger(InviteCodeRepository.class);

    private final MongoCollection<Document> collection;
    private final Clock clock;

    @Inject
    public InviteCodeRepository(MongoDatabase database) {
        this(database, Clock.systemUTC());
    }

    /** Visible for tests — lets expiry be driven by a fixed clock. */
    InviteCodeRepository(MongoDatabase database, Clock clock) {
        this.collection = database.getCollection("inviteCodes");
        this.clock = clock;
        collection.createIndex(Indexes.ascending("code"), new IndexOptions().unique(true));
    }

    /**
     * Atomically claims an unused, unexpired code. Returns true if this call won the claim.
     *
     * <p>The match and the write happen in one {@code findOneAndUpdate}, so there is no
     * read-then-write gap for two people racing the same code to slip through.
     */
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

    /**
     * Inserts a freshly minted code.
     *
     * <p>Lets a {@link MongoWriteException} escape on a duplicate {@code code} rather than
     * swallowing it: the caller generates codes randomly, so a collision means the generator
     * is broken, and retrying around it would hide that.
     */
    public InviteCode create(String code, String note, Instant expiresAt, String createdBy) {
        InviteCode invite = new InviteCode(code, note, false, null, null,
                clock.instant(), expiresAt, createdBy, null, null);
        collection.insertOne(toDocument(invite));
        log.info("Invite code minted by {} expiring {}", createdBy, expiresAt);
        return invite;
    }

    /** All codes, newest first. The collection is operator-sized, so this is deliberately unpaged. */
    public List<InviteCode> list() {
        List<InviteCode> codes = new ArrayList<>();
        collection.find().sort(Sorts.descending("createdAt")).forEach(doc -> codes.add(fromDocument(doc)));
        return codes;
    }

    /** Outcome of a revocation — distinguished so the resource can map each to its own status. */
    public enum RevokeResult { REVOKED, NOT_FOUND, ALREADY_USED }

    /**
     * Kills an unclaimed code.
     *
     * <p>Sets {@code revokedAt}/{@code revokedBy} for the audit trail and pulls {@code expiresAt}
     * back to now, which is what actually stops it: {@link #claim} already refuses anything whose
     * expiry has passed, so revocation needs no change to that filter. The update is conditional
     * on {@code used: false} in the same operation, so a code being claimed concurrently either
     * loses the race and is revoked, or wins it and reports {@link RevokeResult#ALREADY_USED}.
     */
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

        // The filter matched nothing: either there is no such code, or it is already claimed.
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
