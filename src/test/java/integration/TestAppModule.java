package integration;

import com.github.grepHammerspace.stateStore.UserStateStore;
import com.github.grepHammerspace.tailscale.TailscaleIdentityService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

/**
 * Dagger module for the integration test object graph.
 *
 * <p>Mirrors {@link com.github.grepHammerspace.bind.AppModule} but replaces two production
 * dependencies with test doubles:
 * <ul>
 *   <li><b>MongoDB URI</b> — supplied by the Testcontainer rather than read from the environment,
 *       so tests never touch a real database.</li>
 *   <li><b>{@link com.github.grepHammerspace.tailscale.TailscaleIdentityService}</b> — replaced
 *       with a lambda that returns a fixed {@code testUserId}, so tests do not require a running
 *       Tailscale daemon. All scenarios therefore appear to come from the same user.</li>
 * </ul>
 * All other bindings ({@link com.github.grepHammerspace.stateStore.UserStateStore},
 * {@link com.github.grepHammerspace.db.UserRepository}, etc.) are the real production classes.
 */
@Module
public class TestAppModule {
    private final String mongoUri;
    private final String testUserId;

    public TestAppModule(String mongoUri, String testUserId) {
        this.mongoUri = mongoUri;
        this.testUserId = testUserId;
    }

    @Provides @Singleton
    UserStateStore provideUserStateStore() { return new UserStateStore(); }

    @Provides @Singleton
    MongoClient provideMongoClient() { return MongoClients.create(mongoUri); }

    @Provides @Singleton
    MongoDatabase provideMongoDatabase(MongoClient client) {
        return client.getDatabase("otjdb");
    }

    @Provides @Singleton
    TailscaleIdentityService provideTailscaleIdentityService() {
        return request -> testUserId;
    }
}
