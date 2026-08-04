package com.github.grepHammerspace.llm;

/**
 * Connection details for the chat-completions endpoint backing {@link LlmService}.
 *
 * <p>Any OpenAI-compatible provider works — OpenRouter (the default), Groq, Nvidia NIM,
 * or a self-hosted Ollama — so switching providers is an environment change rather than
 * a code change. See {@link com.github.grepHammerspace.bind.AppModule} for the bindings.
 *
 * @param baseUrl API root, without a trailing slash (e.g. {@code https://openrouter.ai/api/v1})
 * @param model   provider-specific model slug (e.g. {@code meta-llama/llama-3.3-70b-instruct:free})
 * @param apiKey  bearer token for the provider
 */
public record LlmConfig(String baseUrl, String model, String apiKey) {
}
