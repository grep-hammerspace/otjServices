package com.github.grepHammerspace.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.llm.exception.LlmAuthException;
import com.github.grepHammerspace.llm.exception.LlmException;
import com.github.grepHammerspace.llm.exception.LlmJsonParseException;
import com.github.grepHammerspace.llm.exception.LlmRateLimitException;
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

@Singleton
public class LlmServiceImpl implements LlmService {
    private static final Logger log = LoggerFactory.getLogger(LlmServiceImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnthropicClient client;
    private final String systemPrompt;

    @Inject
    public LlmServiceImpl(AnthropicClient client) {
        this.client = client;
        this.systemPrompt = loadPrompt();
    }

    @Override
    public LlmResult parseActivities(String diff, String today, String userId, String learnerId) {

        String userMessage = "Today's date: " + today + "\n\nNew activity content to log:\n" + diff;

        log.info("Sending request to LLM (model: {})", Model.CLAUDE_HAIKU_4_5);
        log.debug("User message sent to LLM:\n{}", userMessage);

        String responseText;
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_HAIKU_4_5)
                    .maxTokens(2048)
                    .system(systemPrompt)
                    .addUserMessage(userMessage)
                    .build();

            responseText = client.messages().create(params).content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(tb -> tb.text())
                    .collect(java.util.stream.Collectors.joining());
        } catch (UnauthorizedException e) {
            String msg = "Anthropic API authentication failed. " +
                    "Expected: a valid ANTHROPIC_API_KEY set in the environment. " +
                    "Got: " + e.getMessage() + ". " +
                    "Check that ANTHROPIC_API_KEY is set correctly and the key is active.";
            log.error(msg);
            throw new LlmAuthException(msg, e);
        } catch (RateLimitException e) {
            String msg = "Anthropic API rate limit hit. " +
                    "The API rejected the request because too many requests were made in a short period. " +
                    "Got: " + e.getMessage() + ". Wait a moment and retry.";
            log.error(msg);
            throw new LlmRateLimitException(msg, e);
        } catch (Exception e) {
            String msg = "Unexpected error calling LLM: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error(msg, e);
            throw new LlmException(msg, e);
        }

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
                Integer.parseInt(hoursMinutes[0]), Integer.parseInt(hoursMinutes[1]), false);
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
