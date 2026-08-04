package com.github.grepHammerspace.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.llm.exception.LlmAuthException;
import com.github.grepHammerspace.llm.exception.LlmException;
import com.github.grepHammerspace.llm.exception.LlmJsonParseException;
import com.github.grepHammerspace.llm.exception.LlmRateLimitException;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Singleton
public class LlmServiceImpl implements LlmService {
    private static final Logger log = LoggerFactory.getLogger(LlmServiceImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON_TYPE = MediaType.get("application/json");

    private static final int MAX_TOKENS = 2048;

    private final OkHttpClient httpClient;
    private final LlmConfig config;
    private final String systemPrompt;

    @Inject
    public LlmServiceImpl(OkHttpClient httpClient, LlmConfig config) {
        this.httpClient = httpClient;
        this.config = config;
        this.systemPrompt = loadPrompt();
    }

    @Override
    public LlmResult parseActivities(String diff, String today, String userId, String learnerId) {

        String userMessage = "Today's date: " + today + "\n\nNew activity content to log:\n" + diff;

        log.info("Sending request to LLM (model: {})", config.model());
        log.debug("User message sent to LLM:\n{}", userMessage);

        String responseText = extractContent(callCompletions(userMessage));

        log.debug("Raw LLM response:\n{}", responseText);

        try {
            return processResponse(responseText, userId, learnerId);
        } catch (JsonProcessingException e) {
            String msg = "The LLM returned a response that could not be parsed as JSON. " +
                    "Expected: a JSON array of row objects. " +
                    "Got a response that failed JSON parsing at: " + e.getMessage() + ". " +
                    "Check llm_prompt.txt to ensure the model is instructed to return only raw JSON.";
            log.error(msg);
            throw new LlmJsonParseException(msg, e);
        }
    }

    /**
     * POSTs the prompt to the provider's chat-completions endpoint and returns the raw response body.
     *
     * <p>Maps transport failures onto the {@link LlmException} hierarchy that
     * {@link com.github.grepHammerspace.api.OtjServicesResource} branches on: 401/403 become
     * {@link LlmAuthException}, 429 becomes {@link LlmRateLimitException}, everything else
     * becomes a plain {@link LlmException}.
     */
    private String callCompletions(String userMessage) {
        String payload;
        try {
            payload = MAPPER.writeValueAsString(Map.of(
                    "model", config.model(),
                    "max_tokens", MAX_TOKENS,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userMessage))));
        } catch (JsonProcessingException e) {
            throw new LlmException("Failed to serialise the LLM request body: " + e.getMessage(), e);
        }

        Request request = new Request.Builder()
                .url(config.baseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + config.apiKey())
                .post(RequestBody.create(payload, JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (response.isSuccessful()) {
                return body;
            }

            if (response.code() == 401 || response.code() == 403) {
                String msg = "LLM API authentication failed. " +
                        "Expected: a valid LLM_API_KEY for " + config.baseUrl() + ". " +
                        "Got: HTTP " + response.code() + " " + body + ". " +
                        "Check that LLM_API_KEY is set correctly and the key is active.";
                log.error(msg);
                throw new LlmAuthException(msg, null);
            }

            if (response.code() == 429) {
                String msg = "LLM API rate limit hit. " +
                        "The API rejected the request because too many requests were made in a short period. " +
                        "Got: " + body + ". Wait a moment and retry.";
                log.error(msg);
                throw new LlmRateLimitException(msg, null);
            }

            String msg = "LLM API returned HTTP " + response.code() + " for model '" + config.model() + "'. " +
                    "Got: " + body + ". " +
                    "Check that LLM_MODEL names a model this provider currently serves.";
            log.error(msg);
            throw new LlmException(msg, null);
        } catch (IOException e) {
            String msg = "Could not reach the LLM API at " + config.baseUrl() + ": " + e.getMessage();
            log.error(msg, e);
            throw new LlmException(msg, e);
        }
    }

    /**
     * Pulls the assistant message out of a chat-completions response.
     *
     * <p>Free-tier providers sometimes answer HTTP 200 with a top-level {@code error} object and no
     * {@code choices} when the upstream model is saturated, so a successful status alone is not
     * enough to assume there is content to read.
     */
    private String extractContent(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            String msg = "The LLM API returned a body that is not valid JSON. Got: " + body;
            log.error(msg);
            throw new LlmException(msg, e);
        }

        if (root.has("error")) {
            String msg = "The LLM API reported an error despite a success status: " + root.get("error") + ". " +
                    "Free-tier models are often unavailable when upstream capacity is saturated — " +
                    "retry, or set LLM_MODEL to a different model.";
            log.error(msg);
            throw new LlmException(msg, null);
        }

        JsonNode content = root.at("/choices/0/message/content");
        if (content.isMissingNode() || !content.isTextual()) {
            String msg = "The LLM API response contained no message content. " +
                    "Expected: choices[0].message.content. Got: " + body;
            log.error(msg);
            throw new LlmException(msg, null);
        }

        return content.asText();
    }

    /** Visible for testing: strips fences, parses the JSON array, and maps rows to ActivityLog/LlmParseError. */
    LlmResult processResponse(String responseText, String userId, String learnerId) throws JsonProcessingException {
        String cleaned = responseText.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(cleaned.indexOf('\n') + 1);
            int fence = cleaned.lastIndexOf("```");
            if (fence >= 0) cleaned = cleaned.substring(0, fence).trim();
            log.debug("Stripped markdown fences. Cleaned response:\n{}", cleaned);
        }

        JsonNode array = MAPPER.readTree(cleaned);
        log.info("LLM returned {} row(s)", array.size());

        List<ActivityLog> ok = new ArrayList<>();
        List<LlmParseError> errors = new ArrayList<>();

        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            if (node.has("error")) {
                LlmParseError err = MAPPER.treeToValue(node, LlmParseError.class);
                log.warn("LLM could not parse input line — {}: {}", err.error(), err.raw());
                errors.add(err);
            } else {
                log.debug("  Row {}: {}", i + 1, node);
                ok.add(toActivityLog(node, userId, learnerId));
            }
        }

        return new LlmResult(ok, errors);
    }

    private ActivityLog toActivityLog(JsonNode node, String userId, String learnerId) {
        String date = node.has("date") ? node.get("date").asText() : "";
        String[] hoursMinutes = parseTimeSpent(node.has("time-spent") ? node.get("time-spent").asText("0:00") : "0:00");
        String startTime = node.has("start-time") ? node.get("start-time").asText("") : "";
        String comments = node.has("comments") ? node.get("comments").asText() : "";

        return new ActivityLog(userId, learnerId, comments, "", date, startTime, 0,
                Integer.parseInt(hoursMinutes[0]), Integer.parseInt(hoursMinutes[1]), false, null);
    }

    private static String[] parseTimeSpent(String timeSpent) {
        String[] parts = timeSpent.split(":");
        if (parts.length != 2) return new String[]{"0", "0"};
        return parts;
    }

    private static String loadPrompt() {
        try (InputStream is = LlmServiceImpl.class.getClassLoader().getResourceAsStream("llm_prompt.txt")) {
            if (is == null) {
                String msg = "Prompt file not found at classpath:llm_prompt.txt. " +
                        "Ensure the file exists in src/main/resources/ and is included in the build.";
                throw new LlmException(msg, new FileNotFoundException("llm_prompt.txt"));
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LlmException("Failed to read llm_prompt.txt from classpath: " + e.getMessage(), e);
        }
    }
}
