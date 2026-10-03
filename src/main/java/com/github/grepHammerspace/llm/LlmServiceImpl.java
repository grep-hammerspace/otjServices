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
import com.github.grepHammerspace.db.model.ActivityRules;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Singleton
public class LlmServiceImpl implements LlmService {
    private static final Logger log = LoggerFactory.getLogger(LlmServiceImpl.class);

    private static final Model MODEL = Model.CLAUDE_HAIKU_4_5;

    // A ceiling, not a budget: output is billed per token generated, and a truncated response fails
    // the parse outright.
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
                .serviceTier(MessageCreateParams.ServiceTier.AUTO)
                .addUserMessage(userMessage)
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

    LlmResult toResult(ParsedActivities parsed, String userId, String learnerId) {
        log.info("LLM returned {} entry/entries and {} error(s)",
                parsed.entries().size(), parsed.errors().size());

        List<LlmParseError> errors = new ArrayList<>();
        Map<ParsedActivities.ErrorCode, Integer> reported = new EnumMap<>(ParsedActivities.ErrorCode.class);
        for (ParsedActivities.ParseError error : parsed.errors()) {
            log.warn("LLM could not parse input line — {}: {}", error.error(), error.raw());
            errors.add(new LlmParseError(error.error().name(), error.message(), error.raw()));
            reported.merge(error.error(), 1, Integer::sum);
        }

        // The prompt forbids incomplete entries, but the model sometimes emits one alongside the
        // error for the same line, and saving it leaves a near-duplicate once the user fixes it.
        List<ActivityLog> ok = new ArrayList<>();
        for (ParsedActivities.Entry entry : parsed.entries()) {
            log.debug("  Entry: {}", entry);
            ActivityLog row = new ActivityLog(userId, learnerId, entry.comments(), "", entry.date(),
                    entry.startTime(), 0, entry.hours(), entry.minutes(), false, null);
            ActivityRules.Violation violation = ActivityRules.check(row);
            if (violation == null) {
                ok.add(row);
                continue;
            }
            ParsedActivities.ErrorCode code = codeFor(violation.kind());
            log.warn("Dropped an incomplete LLM entry as {}", code);
            // Errors aren't linked to entries, so only synthesise one the model didn't report.
            if (reported.merge(code, -1, Integer::sum) < 0) {
                errors.add(new LlmParseError(code.name(), messageFor(code), entry.comments()));
            }
        }

        return new LlmResult(ok, errors);
    }

    private static ParsedActivities.ErrorCode codeFor(ActivityRules.Kind kind) {
        return switch (kind) {
            case DATE -> ParsedActivities.ErrorCode.invalid_date;
            case START_TIME -> ParsedActivities.ErrorCode.missing_start_time;
            case WORKING_HOURS -> ParsedActivities.ErrorCode.outside_working_hours;
            case DURATION -> ParsedActivities.ErrorCode.missing_duration;
            case DESCRIPTION -> ParsedActivities.ErrorCode.missing_description;
        };
    }

    private static String messageFor(ParsedActivities.ErrorCode code) {
        return switch (code) {
            case invalid_date -> "Could not work out a valid date";
            case missing_start_time -> "No start time found in input";
            case outside_working_hours -> "Start time is outside 09:00-18:00";
            case missing_duration -> "No duration found in input";
            case missing_description -> "No description found in input";
        };
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
