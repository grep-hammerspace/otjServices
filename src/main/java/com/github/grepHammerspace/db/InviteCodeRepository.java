package com.github.grepHammerspace.db;

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
import java.time.Clock;
import java.util.Date;

/**
 * MongoDB-backed store for signup invite codes.
 *
 * <p>Codes are minted by hand in the Atlas UI — there is no admin endpoint, so the only
 * production surface here is the claim. A document looks like:
 *
 * <pre>{@code
 * { "code": "OTJ-7F3K-9QMX", "used": false, "note": "for Sam",
 *   "createdAt": ISODate, "expiresAt": ISODate, "usedBy": null, "usedAt": null }
 * }</pre>
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
}
