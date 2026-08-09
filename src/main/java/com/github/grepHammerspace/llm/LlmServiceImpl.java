package com.github.grepHammerspace.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
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

    private static final Model MODEL = Model.CLAUDE_HAIKU_4_5;

    /**
     * A ceiling, not a budget — output is billed on tokens actually generated, so lowering this
     * would save nothing. It only bounds a runaway generation. Kept generous because a truncated
     * response is invalid JSON that fails the typed parse outright (see the MAX_TOKENS check
     * below) — a worse failure than the cost it would avoid.
     */
    private static final long MAX_TOKENS = 2048L;

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

        log.info("Sending request to LLM (model: {})", MODEL);
        log.debug("User message sent to LLM:\n{}", userMessage);

        StructuredMessageCreateParams<ParsedActivities> params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .system(systemPrompt)
                // Opportunistic lower-latency routing; a no-op without a Priority Tier commitment.
                .serviceTier(MessageCreateParams.ServiceTier.AUTO)
                .addUserMessage(userMessage)
                // Constrains the response to ParsedActivities — there is no JSON to hand-parse.
                .outputConfig(ParsedActivities.class)
                .build();

        long startedAt = System.nanoTime();
        StructuredMessage<ParsedActivities> message;
        try {
            message = client.messages().create(params);
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
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        log.info("LLM call complete in {} ms (model: {}, {} in / {} out tokens)",
                elapsedMs, MODEL, message.usage().inputTokens(), message.usage().outputTokens());

        // A truncated response is unrecoverable: the payload is cut mid-JSON and deserialisation
        // fails with a far less obvious error than this one.
        if (message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            String msg = "The LLM response was cut off at the " + MAX_TOKENS + "-token limit, " +
                    "so the structured result is incomplete. " +
                    "Expected: the submitted content to produce fewer activity rows than the limit allows. " +
                    "Split the submission into smaller batches, or raise MAX_TOKENS in LlmServiceImpl.";
            log.error(msg);
            throw new LlmException(msg, null);
        }

        ParsedActivities parsed = message.content().stream()
                .flatMap(block -> block.text().stream())
                .findFirst()
                .map(block -> block.text())
                .orElseThrow(() -> {
                    String msg = "The LLM returned no content block to deserialise. " +
                            "Expected: one structured text block matching ParsedActivities. " +
                            "Got a response with " + message.content().size() + " block(s).";
                    log.error(msg);
                    return new LlmJsonParseException(msg, null);
                });

        return toResult(parsed, userId, learnerId);
    }

    /** Visible for testing: maps the model's typed output onto the persistence types. */
    LlmResult toResult(ParsedActivities parsed, String userId, String learnerId) {
        log.info("LLM returned {} entry/entries and {} error(s)",
                parsed.entries().size(), parsed.errors().size());

        List<ActivityLog> ok = new ArrayList<>();
        for (ParsedActivities.Entry entry : parsed.entries()) {
            log.debug("  Entry: {}", entry);
            ok.add(new ActivityLog(userId, learnerId, entry.comments(), "", entry.date(),
                    entry.startTime(), 0, entry.hours(), entry.minutes(), false, null));
        }

        List<LlmParseError> errors = new ArrayList<>();
        for (ParsedActivities.ParseError error : parsed.errors()) {
            log.warn("LLM could not parse input line — {}: {}", error.error(), error.raw());
            errors.add(new LlmParseError(error.error().name(), error.message(), error.raw()));
        }

        return new LlmResult(ok, errors);
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
