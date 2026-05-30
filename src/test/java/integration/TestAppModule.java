package integration;

import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.llm.LlmResult;
import com.github.grepHammerspace.llm.LlmService;
import com.github.grepHammerspace.stateStore.UserStateStore;
import com.github.grepHammerspace.tailscale.TailscaleIdentityService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

    /**
     * Fake LLM service for integration tests — returns one ActivityLog per non-blank line
     * in the diff without calling the Anthropic API.
     */
    @Provides @Singleton
    LlmService provideLlmService() {
        return (diff, today, userId, learnerId) -> {
            List<ActivityLog> ok = Arrays.stream(diff.split("\n"))
                    .filter(line -> !line.isBlank())
                    .map(line -> new ActivityLog(userId, learnerId, line.trim(), "", today, "10:00", 0, 1, 0, false))
                    .collect(Collectors.toList());
            return new LlmResult(ok, List.of());
        };
    }
}
