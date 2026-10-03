package com.github.grepHammerspace.db;

import com.github.grepHammerspace.SingleUser;
import com.github.grepHammerspace.db.model.User;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Date;

@Singleton
public class UserRepository {
    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final MongoCollection<Document> collection;

    @Inject
    public UserRepository(MongoDatabase database) {
        this.collection = database.getCollection("users");
        collection.createIndex(Indexes.ascending("userId"), new IndexOptions().unique(true));
    }

    // $setOnInsert only, so a restart never clobbers the learner ID set through PATCH /auth/me.
    public void ensureSingleUser() {
        collection.updateOne(
                Filters.eq("userId", SingleUser.USER_ID),
                Updates.setOnInsert(new Document()
                        .append("appUsername", SingleUser.USERNAME)
                        .append("createdAt", new Date())),
                new UpdateOptions().upsert(true));
    }

    public User findByUserId(String userId) {
        return fromDocument(collection.find(Filters.eq("userId", userId)).first());
    }

    // A $set of one field, never a whole-document replace, which could clobber a concurrent change
    // or resurrect a deleted account. Logs that it changed, never the value.
    public User updateLearnerId(String userId, String learnerId) {
        Document updated = collection.findOneAndUpdate(
                Filters.eq("userId", userId),
                Updates.set("learnerId", learnerId),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));

        if (updated == null) {
            log.info("No user {} found to update learnerId for", userId);
            return null;
        }
        log.info("Updated learnerId for user {}", userId);
        return fromDocument(updated);
    }

    private static User fromDocument(Document doc) {
        if (doc == null) return null;
        Date createdAt = doc.getDate("createdAt");
        return new User(
                doc.getString("userId"),
                doc.getString("appUsername"),
                doc.getString("learnerId"),
                createdAt == null ? null : createdAt.toInstant()
        );
    }
}
