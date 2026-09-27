package com.github.grepHammerspace.bind;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
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
    MongoClient provideMongoClient() {
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

    // Behind Driver so tests can bind fakes. Not @Singleton: each prepare needs its own cookie jar.

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
