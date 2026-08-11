package com.github.grepHammerspace.db;

import com.github.grepHammerspace.db.model.User;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Date;

/**
 * MongoDB-backed store for registered users.
 *
 * <p>Stores only the bcrypt hash of the app password — no field in the collection can be
 * decrypted back to any credential.
 */
@Singleton
public class UserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final MongoCollection<Document> collection;

    @Inject
    public UserRepository(MongoDatabase database) {
        this.collection = database.getCollection("users");
        collection.createIndex(Indexes.ascending("appUsername"), new IndexOptions().unique(true));
    }

    /** Upserts the user record keyed by {@code userId}. */
    public void save(User user) {
        collection.replaceOne(
                Filters.eq("userId", user.userId()),
                toDocument(user),
                new ReplaceOptions().upsert(true)
        );

        log.info("Saved user {}", user.userId());
    }

    /**
     * Inserts a new user, failing closed on a duplicate {@code appUsername}.
     *
     * @return true if the user was created; false if the username is already taken
     */
    public boolean insert(User user) {
        try {
            collection.insertOne(toDocument(user));
            log.info("Created user {}", user.userId());
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == com.mongodb.ErrorCategory.DUPLICATE_KEY) {
                log.info("Rejected duplicate appUsername for new user");
                return false;
            }
            throw e;
        }
    }

    public User findByUserId(String userId) {
        return fromDocument(collection.find(Filters.eq("userId", userId)).first());
    }

    /** Looks up a user by their app login name — the login path. */
    public User findByAppUsername(String appUsername) {
        return fromDocument(collection.find(Filters.eq("appUsername", appUsername)).first());
    }

    private static Document toDocument(User user) {
        return new Document()
                .append("userId", user.userId())
                .append("appUsername", user.appUsername())
                .append("appPasswordHash", user.appPasswordHash())
                .append("learnerId", user.learnerId())
                .append("createdAt", user.createdAt() == null ? null : Date.from(user.createdAt()));
    }

    private static User fromDocument(Document doc) {
        if (doc == null) return null;
        Date createdAt = doc.getDate("createdAt");
        return new User(
                doc.getString("userId"),
                doc.getString("appUsername"),
                doc.getString("appPasswordHash"),
                doc.getString("learnerId"),
                createdAt == null ? null : createdAt.toInstant()
        );
    }
}
