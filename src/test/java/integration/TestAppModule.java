package integration;

import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.llm.LlmResult;
import com.github.grepHammerspace.llm.LlmService;
import com.github.grepHammerspace.stateStore.UserStateStore;
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
 *   <li><b>{@link com.github.grepHammerspace.llm.LlmService}</b> — a fake that parses the diff
 *       locally, so tests never call the Anthropic API.</li>
 * </ul>
 * All other bindings ({@link com.github.grepHammerspace.stateStore.UserStateStore},
 * {@link com.github.grepHammerspace.db.UserRepository},
 * {@link com.github.grepHammerspace.auth.SessionTokenService}, etc.) are the real production
 * classes. Scenarios authenticate with real bearer tokens issued by {@link ServerHooks}.
 */
@Module
public class TestAppModule {
    private final String mongoUri;

    public TestAppModule(String mongoUri) {
        this.mongoUri = mongoUri;
    }

    @Provides @Singleton
    UserStateStore provideUserStateStore() { return new UserStateStore(); }

    @Provides @Singleton
    MongoClient provideMongoClient() { return MongoClients.create(mongoUri); }

    @Provides @Singleton
    MongoDatabase provideMongoDatabase(MongoClient client) {
        return client.getDatabase("otjdb");
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
                    .map(line -> new ActivityLog(userId, learnerId, line.trim(), "", today, "10:00", 0, 1, 0, false, null))
                    .collect(Collectors.toList());
            return new LlmResult(ok, List.of());
        };
    }
}
