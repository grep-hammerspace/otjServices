package integration;

import com.github.grepHammerspace.admin.AdminAllowlist;
import com.github.grepHammerspace.crypto.CredentialKeyRing;
import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.llm.LlmResult;
import com.github.grepHammerspace.llm.LlmService;
import com.github.grepHammerspace.web.AzurePush;
import com.github.grepHammerspace.web.Driver;
import com.github.grepHammerspace.web.Keycloak;
import com.github.grepHammerspace.web.PrepareResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Module
public class TestAppModule {
    private final String mongoUri;

    public TestAppModule(String mongoUri) {
        this.mongoUri = mongoUri;
    }

    @Provides @Singleton
    AdminAllowlist provideAdminAllowlist() { return AdminAllowlist.parse(ServerHooks.ADMIN_LOGIN); }

    @Provides @Singleton
    CredentialKeyRing provideCredentialKeyRing() {
        return new CredentialKeyRing(ServerHooks.IDENTITY_SEED);
    }

    @Provides @Singleton
    MongoClient provideMongoClient() { return MongoClients.create(mongoUri); }

    @Provides @Singleton
    MongoDatabase provideMongoDatabase(MongoClient client) {
        return client.getDatabase("otjdb");
    }

    @Provides @Singleton
    LlmService provideLlmService() {
        return (diff, today, userId, learnerId) -> {
            List<ActivityLog> ok = Arrays.stream(diff.split("\n"))
                    .filter(line -> !line.isBlank())
                    .map(line -> new ActivityLog(userId, learnerId, line.trim(), "2026/06/01", "10:00", 1, 0, false, null))
                    .collect(Collectors.toList());
            return new LlmResult(ok, List.of());
        };
    }

    // @Singleton so a step and the running resource see the same fake.

    @Provides @Singleton @Keycloak
    Driver provideKeycloakDriver(@Keycloak FakeDriver fake) { return fake; }

    @Provides @Singleton @AzurePush
    Driver provideAzurePushDriver(@AzurePush FakeDriver fake) { return fake; }

    @Provides @Singleton @Keycloak
    FakeDriver provideKeycloakFake() { return new FakeDriver(PrepareResult.otpRequired()); }

    @Provides @Singleton @AzurePush
    FakeDriver provideAzurePushFake() { return new FakeDriver(PrepareResult.pushSent(null)); }
}
