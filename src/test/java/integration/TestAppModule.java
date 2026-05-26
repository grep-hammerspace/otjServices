package integration;

import com.github.grepHammerspace.stateStore.UserStateStore;
import com.github.grepHammerspace.tailscale.TailscaleIdentityService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

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
