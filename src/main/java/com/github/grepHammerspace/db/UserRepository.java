package com.github.grepHammerspace.db;

import com.github.grepHammerspace.db.model.User;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

/** MongoDB-backed store for registered users. */
@Singleton
public class UserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final MongoCollection<Document> collection;

    @Inject
    public UserRepository(MongoDatabase database) {
        this.collection = database.getCollection("users");
    }

    /** Upserts the user record — re-registering the same Tailscale user updates rather than duplicates. */
    public void save(User user) {
        Document doc = new Document()
                .append("userId", user.userId())
                .append("username", user.username())
                .append("password", user.password())
                .append("learnerId", user.learnerId());

        // upsert so re-registering the same Tailscale user updates rather than duplicates
        collection.replaceOne(
                Filters.eq("userId", user.userId()),
                doc,
                new ReplaceOptions().upsert(true)
        );

        log.info("Saved user {}", user.userId());
    }

    public User findByUserId(String userId) {
        Document doc = collection.find(Filters.eq("userId", userId)).first();
        if (doc == null) return null;
        return new User(
                doc.getString("userId"),
                doc.getString("username"),
                doc.getString("password"),
                doc.getString("learnerId")
        );
    }

    public String getLastContent(String userId) {
        Document doc = collection.find(Filters.eq("userId", userId)).first();
        if (doc == null) return null;
        return doc.getString("lastContent");
    }

    public void saveLastContent(String userId, String content) {
        collection.updateOne(
                Filters.eq("userId", userId),
                Updates.set("lastContent", content)
        );
        log.debug("Saved lastContent for user {} ({} chars)", userId, content.length());
    }

    public void clearLastContent(String userId) {
        collection.updateOne(
                Filters.eq("userId", userId),
                Updates.unset("lastContent")
        );
        log.info("Cleared lastContent for user {}", userId);
    }
}
