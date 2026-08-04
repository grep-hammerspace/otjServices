package com.github.grepHammerspace.bind;

import com.github.grepHammerspace.crypto.PasswordCipher;
import com.github.grepHammerspace.llm.LlmConfig;
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
import okhttp3.OkHttpClient;

import javax.inject.Singleton;
import java.time.Duration;

/**
 * Dagger module to provide application-wide dependencies.
 */
@Module
public class AppModule {

    private static final String DB_NAME = "otjdb";

    private static final String DEFAULT_LLM_BASE_URL = "https://openrouter.ai/api/v1";
    // Free tiers are retired without notice — meta-llama/llama-3.3-70b-instruct:free went
    // paid-only and started answering 404. Verify a replacement returns a raw JSON array for
    // llm_prompt.txt before switching: many models wrap it in markdown fences or prose.
    private static final String DEFAULT_LLM_MODEL = "openai/gpt-oss-20b:free";

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
    OkHttpClient provideLlmHttpClient() {
        // Generous read timeout: free-tier endpoints queue behind paid traffic and are
        // considerably slower to first byte than a paid API would be.
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(90))
                .build();
    }

    @Provides
    @Singleton
    LlmConfig provideLlmConfig() {
        // Any OpenAI-compatible provider works — override LLM_BASE_URL for Groq, Nvidia NIM,
        // or a self-hosted Ollama. LLM_MODEL must name a non-reasoning model: reasoning models
        // emit chain-of-thought ahead of the answer, which breaks the JSON-only prompt contract.
        return new LlmConfig(
                envOrDefault("LLM_BASE_URL", DEFAULT_LLM_BASE_URL),
                envOrDefault("LLM_MODEL", DEFAULT_LLM_MODEL),
                System.getenv("LLM_API_KEY"));
    }

    /**
     * Reads {@code name} from the environment, falling back to {@code fallback} when it is unset,
     * blank, or still holds an unexpanded {@code ${...}} placeholder.
     *
     * <p>The placeholder case is not hypothetical: podman-compose does not implement Compose's
     * {@code ${VAR:-default}} operator and passes the literal text through as the value, so the
     * variable arrives *set* to {@code "${LLM_MODEL:-meta-llama/...}"}. A plain
     * {@code getOrDefault} only falls back on a missing key, so it would hand that string to
     * OkHttp and fail with "Expected URL scheme 'http' or 'https'" on the first LLM call.
     */
    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null) return fallback;

        value = value.strip();
        return value.isEmpty() || value.startsWith("${") ? fallback : value;
    }

    @Provides
    @Singleton
    LlmService provideLlmService(LlmServiceImpl impl) {
        return impl;
    }

    @Provides
    @Singleton
    PasswordCipher providePasswordCipher() {
        return new PasswordCipher();
    }
}
