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

@Singleton
public class LlmQuotaService {
    private static final Logger log = LoggerFactory.getLogger(LlmQuotaService.class);

    public static final int DAILY_LIMIT = 10;

    private static final Duration RETENTION = Duration.ofDays(60);

    private final MongoCollection<Document> collection;
    private final Clock clock;

    @Inject
    public LlmQuotaService(MongoDatabase database) {
        this(database, Clock.systemUTC());
    }

    LlmQuotaService(MongoDatabase database, Clock clock) {
        this.collection = database.getCollection("llmQuota");
        this.clock = clock;
        // The unique constraint is what makes the upsert in tryConsume safe under concurrency.
        collection.createIndex(Indexes.ascending("userId", "date"), new IndexOptions().unique(true));
        collection.createIndex(Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    // The day is UTC on purpose (DST-free), unlike the system-zone date stamped on rows.
    //
    // An over-limit call still increments: that keeps it one atomic round trip. The count is
    // attempts, so clamp it before showing it.
    public boolean tryConsume(String userId) {
        String date = LocalDate.now(clock).toString();
        Date expiresAt = Date.from(clock.instant().plus(RETENTION));

        // Two concurrent first calls race on the unique index; the loser sees a duplicate key. One
        // retry is enough: the document exists by then.
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
        throw lastError;
    }

    public long secondsUntilReset() {
        Instant now = clock.instant();
        Instant midnight = LocalDate.now(clock).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return Math.max(1, Duration.between(now, midnight).toSeconds());
    }

    // The driver may surface a duplicate key as either exception here.
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
