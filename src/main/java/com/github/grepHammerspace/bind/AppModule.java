package com.github.grepHammerspace.bind;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.github.grepHammerspace.crypto.CredentialKeyRing;
import com.github.grepHammerspace.llm.LlmService;
import com.github.grepHammerspace.llm.LlmServiceImpl;
import com.github.grepHammerspace.stateStore.UserStateStore;
import com.github.grepHammerspace.web.AzureIdDriver;
import com.github.grepHammerspace.web.AzurePush;
import com.github.grepHammerspace.web.Driver;
import com.github.grepHammerspace.web.Keycloak;
import com.github.grepHammerspace.web.OtjDriver;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

/**
 * Dagger module to provide application-wide dependencies.
 */
@Module
public class AppModule {

    private static final String DB_NAME = "otjdb";

    @Provides
    @Singleton
    UserStateStore provideUserStateStore() {
        return new UserStateStore();
    }

    /**
     * One ring for the process. It generates a key at startup and rotates it in place, so a second
     * instance would publish a key that the first one cannot open envelopes for — and since both
     * prepare endpoints and the key endpoint would then disagree, half of every submit would fail.
     *
     * <p>Constructed eagerly at graph creation rather than lazily on the first submit: it throws
     * when {@code CREDENTIAL_IDENTITY_SEED} is missing, and that failure belongs at boot, where a
     * deploy can catch it, not at the moment a user tries to send their password.
     */
    @Provides
    @Singleton
    CredentialKeyRing provideCredentialKeyRing() {
        return new CredentialKeyRing();
    }

    @Provides
    @Singleton
    MongoClient provideMongoClient() {
        // MongoClient is a wrapper around a connection pool and it thread-safe. Designed to be created one and reused
        String uri = System.getenv().getOrDefault("MONGO_URI", "mongodb://localhost:27017");
        return MongoClients.create(uri);
    }

    @Provides
    @Singleton
    MongoDatabase provideMongoDatabase(MongoClient client) {
        return client.getDatabase(DB_NAME);
    }

    @Provides
    @Singleton
    AnthropicClient provideAnthropicClient() {
        return AnthropicOkHttpClient.fromEnv();
    }

    @Provides
    @Singleton
    LlmService provideLlmService(LlmServiceImpl impl) {
        return impl;
    }

    /*
     * The two drivers are bound behind the Driver interface rather than injected as concrete
     * types. That keeps the resource from naming implementations, and it is what lets the
     * integration tests substitute a fake — otherwise a scenario touching prepare would dial
     * Keycloak and Microsoft for real. Deliberately not @Singleton: each prepare needs its own
     * cookie jar.
     */

    @Provides
    @Keycloak
    Driver provideKeycloakDriver(OtjDriver driver) {
        return driver;
    }

    @Provides
    @AzurePush
    Driver provideAzurePushDriver(AzureIdDriver driver) {
        return driver;
    }
}
