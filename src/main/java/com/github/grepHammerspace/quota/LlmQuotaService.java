package com.github.grepHammerspace.quota;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Caps how many LLM calls one user can make in a day.
 *
 * <p>The only backstop the service itself can enforce. Every well-formed {@code log-activities}
 * request now reaches the model — content diffing was removed, so there is no longer any
 * resubmit-is-free path — which means a client stuck in a retry loop would otherwise bill the
 * Anthropic account without limit.
 *
 * <p>Counts live in the {@code llmQuota} collection, one document per user per day.
 */
@Singleton
public class LlmQuotaService {

    private static final Logger log = LoggerFactory.getLogger(LlmQuotaService.class);

    /**
     * Public so the API's error message can interpolate the number actually enforced, rather than
     * repeating "10" in prose that later drifts from the code.
     */
    public static final int DAILY_LIMIT = 10;

    /** How long a spent day's counter is kept before Mongo expires it. */
    private static final Duration RETENTION = Duration.ofDays(60);

    private final MongoCollection<Document> collection;
    private final Clock clock;

    @Inject
    public LlmQuotaService(MongoDatabase database) {
        this(database, Clock.systemUTC());
    }

    /** Visible for tests — lets the day boundary be driven by a fixed clock. */
    LlmQuotaService(MongoDatabase database, Clock clock) {
        this.collection = database.getCollection("llmQuota");
        this.clock = clock;
        // userId first, so the index also serves "every day for this user" and not just the
        // exact-match lookup below. The unique constraint is what makes the upsert in
        // tryConsume safe under concurrency.
        collection.createIndex(Indexes.ascending("userId", "date"), new IndexOptions().unique(true));
        // Self-cleaning, so the collection does not grow by one document per user per day
        // forever. Same idiom as SessionRepository's session expiry.
        collection.createIndex(Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    /**
     * Consumes one call against today's quota, atomically. Returns whether it was within it.
     *
     * <p>The day is the <b>UTC</b> date, so the quota resets at 00:00Z. Note this is deliberately
     * not the same boundary as the {@code LocalDate.now()} in
     * {@code OtjServicesResource.logActivtiesWithLlmHelp}, which uses the system zone for the date
     * it stamps on activity rows. Under BST they disagree for an hour: just after local midnight,
     * a row is dated to the new day while the quota still counts against the old one. Both are
     * right for their own purpose — UTC is DST-free and cannot silently produce a 23- or 25-hour
     * quota window, while the row date should match the user's own calendar. Read both before
     * "fixing" either.
     *
     * <p>An over-limit call still increments. That is what keeps this a single round trip with no
     * read-modify-write, which is the whole reason the count cannot be raced. The stored number is
     * therefore calls <em>attempted</em>, not calls served — so never render it to a user without
     * clamping it to {@link #DAILY_LIMIT}.
     */
    public boolean tryConsume(String userId) {
        String date = LocalDate.now(clock).toString();
        Date expiresAt = Date.from(clock.instant().plus(RETENTION));

        // Two concurrent first calls for the same {userId, date} both find no document and both
        // try to insert; the unique index lets exactly one through and the loser sees a duplicate
        // key. One retry is provably enough — the document exists by then, and nothing here ever
        // deletes one. The failed attempt's insert never landed, so the retry's $inc is that
        // call's only increment.
        MongoException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Document doc = collection.findOneAndUpdate(
                        Filters.and(Filters.eq("userId", userId), Filters.eq("date", date)),
                        Updates.combine(
                                Updates.inc("count", 1),
                                Updates.setOnInsert("expiresAt", expiresAt)),
                        new FindOneAndUpdateOptions()
                                .upsert(true)
                                .returnDocument(ReturnDocument.AFTER));

                int count = doc.getInteger("count");
                if (count > DAILY_LIMIT) {
                    log.info("User {} is over the daily LLM quota — {} calls attempted today",
                            userId, count);
                    return false;
                }
                return true;
            } catch (MongoException e) {
                if (!isDuplicateKey(e)) throw e;
                lastError = e;
            }
        }
        // Bounded rather than a loop: against a genuinely broken index, retrying forever would
        // spin inside a request thread.
        throw lastError;
    }

    /** Seconds until the quota rolls over, for {@code Retry-After}. */
    public long secondsUntilReset() {
        Instant now = clock.instant();
        Instant midnight = LocalDate.now(clock).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return Math.max(1, Duration.between(now, midnight).toSeconds());
    }

    /*
     * findOneAndUpdate is a command rather than a plain write, so the driver may surface a
     * duplicate key as either exception depending on the path taken. UserRepository.insert only
     * ever sees MongoWriteException; handling both here means a driver upgrade cannot silently
     * turn the retry above into a rethrow.
     */
    private static boolean isDuplicateKey(MongoException e) {
        if (e instanceof MongoWriteException write) {
            return write.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
        }
        if (e instanceof MongoCommandException command) {
            return ErrorCategory.fromErrorCode(command.getErrorCode()) == ErrorCategory.DUPLICATE_KEY;
        }
        return false;
    }
}
