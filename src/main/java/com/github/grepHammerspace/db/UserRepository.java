package com.github.grepHammerspace.db;

import com.github.grepHammerspace.db.model.User;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class UserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final MongoCollection<Document> collection;

    @Inject
    public UserRepository(MongoDatabase database) {
        this.collection = database.getCollection("users");
    }

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
}
