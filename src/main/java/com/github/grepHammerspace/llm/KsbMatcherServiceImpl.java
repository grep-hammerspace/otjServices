package com.github.grepHammerspace.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.List;

@Singleton
public class KsbMatcherServiceImpl implements KsbMatcherService {
    private static final Logger log = LoggerFactory.getLogger(KsbMatcherServiceImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnthropicClient client;
    private final String systemPrompt;

    @Inject
    public KsbMatcherServiceImpl(AnthropicClient client) {
        this.client = client;
        this.systemPrompt = loadResource("ksb_matching_prompt.txt") + loadResource("ksb_list.txt");
    }

    @Override
    public KsbMatchResult matchActivitiesToKsbs(List<ActivityLog> logs, String userId, String learnerId) {
        String userMessage;
        try {
            userMessage = "Activity logs to match against the KSB list:\n" + MAPPER.writeValueAsString(logs.stream().map(this::toPromptEntry).toList());
        } catch (JsonProcessingException e) {
            throw new LlmException("Failed to serialize activity logs for the LLM request: " + e.getMessage(), e);
        }

        log.info("Sending KSB matching request to LLM (model: {}, {} activities)", Model.CLAUDE_SONNET_4_6, logs.size());
        log.debug("User message sent to LLM:\n{}", userMessage);

        Message response;
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_SONNET_4_6)
                    .maxTokens(8192)
                    .system(systemPrompt)
                    .addUserMessage(userMessage)
                    .build();

            response = client.messages().create(params);
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

        if (response.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            String msg = "The KSB matching report was cut off because the LLM hit its output token limit. " +
                    "Reduce the number of activity logs being matched, or raise maxTokens in KsbMatcherServiceImpl.";
            log.error(msg);
            throw new LlmException(msg, null);
        }

        String responseText = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(tb -> tb.text())
                .collect(java.util.stream.Collectors.joining());

        log.debug("Raw LLM response:\n{}", responseText);

        try {
            return processResponse(responseText, logs.size());
        } catch (JsonProcessingException e) {
            String msg = "The LLM returned a response that could not be parsed as JSON. " +
                    "Expected: a JSON array of match objects. " +
                    "Got a response that failed JSON parsing at: " + e.getMessage() + ". " +
                    "Check ksb_matching_prompt.txt to ensure the model is instructed to return only raw JSON.";
            log.error(msg);
            throw new LlmJsonParseException(msg, e);
        }
    }

    /** Visible for testing: parses the LLM's JSON array response into a {@link KsbMatchResult}. */
    KsbMatchResult processResponse(String responseText, int totalActivitiesConsidered) throws JsonProcessingException {
        String cleaned = responseText.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(cleaned.indexOf('\n') + 1);
            int fence = cleaned.lastIndexOf("```");
            if (fence >= 0) cleaned = cleaned.substring(0, fence).trim();
            log.debug("Stripped markdown fences. Cleaned response:\n{}", cleaned);
        }

        List<KsbMatch> matches = List.of(MAPPER.readValue(cleaned, KsbMatch[].class));
        log.info("LLM matched {} of {} activities to a KSB", matches.size(), totalActivitiesConsidered);

        return new KsbMatchResult(matches, totalActivitiesConsidered);
    }

    private PromptActivityEntry toPromptEntry(ActivityLog log) {
        return new PromptActivityEntry(log.activityDate(), log.hours(), log.minutes(), log.activityImpact());
    }

    private record PromptActivityEntry(String activityDate, int hours, int minutes, String activityImpact) {}

    private static String loadResource(String name) {
        try (InputStream is = KsbMatcherServiceImpl.class.getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                String msg = "Prompt resource not found at classpath:" + name + ". " +
                        "Ensure the file exists in src/main/resources/ and is included in the build.";
                throw new LlmException(msg, new FileNotFoundException(name));
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LlmException("Failed to read " + name + " from classpath: " + e.getMessage(), e);
        }
    }
}
