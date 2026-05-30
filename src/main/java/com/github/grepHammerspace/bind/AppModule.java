package com.github.grepHammerspace.bind;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.github.grepHammerspace.llm.LlmService;
import com.github.grepHammerspace.llm.LlmServiceImpl;
import com.github.grepHammerspace.stateStore.UserStateStore;
import com.github.grepHammerspace.tailscale.TailscaleIdentityService;
import com.github.grepHammerspace.tailscale.TailscaleIdentityServiceImpl;
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

    @Provides
    @Singleton
    TailscaleIdentityService provideTailscaleIdentityService(TailscaleIdentityServiceImpl impl) {
        return impl;
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
}
