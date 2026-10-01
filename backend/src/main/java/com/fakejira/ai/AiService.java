package com.fakejira.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Talks to Claude for the assistant features (drafting tasks, summaries, splitting epics, notes, search, estimates,
 * triage). Every answer is requested as structured JSON matching a Java record, so callers get typed data back.
 * The features are off unless an API key is configured; nothing is ever sent to Claude otherwise.
 *
 * <p>Requests opt into server-side fallbacks ({@code fallbacks: "default"}): if Claude declines a request, the API
 * re-runs it on Anthropic's recommended fallback model instead of returning the refusal. Turn it off with
 * {@code app.ai.fallbacks=false}.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    public enum Effort { LOW, MEDIUM, HIGH }

    public record Status(boolean enabled, String model) {
    }

    private final AnthropicClient client;
    private final String model;
    private final boolean fallbacks;
    private final int perHour;
    private final AuditLog audit;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final Map<Long, Deque<Instant>> recent = new ConcurrentHashMap<>();

    public AiService(@Value("${app.ai.api-key:}") String apiKey,
                     @Value("${app.ai.base-url:}") String baseUrl,
                     @Value("${app.ai.model:claude-opus-5-5}") String model,
                     @Value("${app.ai.fallbacks:true}") boolean fallbacks,
                     @Value("${app.ai.requests-per-hour:60}") int perHour,
                     @Value("${app.ai.timeout-seconds:120}") int timeoutSeconds,
                     AuditLog audit, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.model = model;
        this.fallbacks = fallbacks;
        this.perHour = perHour;
        this.audit = audit;
        this.json = json.copy().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        if (apiKey == null || apiKey.isBlank()) {
            this.client = null;
        } else {
            AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey.trim())
                    .timeout(Duration.ofSeconds(timeoutSeconds)).maxRetries(2);
            if (baseUrl != null && !baseUrl.isBlank()) {
                builder.baseUrl(baseUrl.trim());
            }
            this.client = builder.build();
        }
    }

    public boolean enabled() {
        return client != null;
    }

    public Status status() {
        return new Status(enabled(), enabled() ? model : null);
    }

    /**
     * Asks Claude and returns its answer parsed into {@code type}. {@code feature} names the request in the audit log.
     * Throws {@link ApiException} with a message fit for users on every failure.
     */
    public <T> T ask(User user, String feature, String system, String prompt, Class<T> type, Effort effort, long maxTokens) {
        if (client == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The Claude assistant is not set up on this server.");
        }
        throttle(user);
        // The typed builder derives the JSON schema from the record; effort is added to the same output config.
        MessageCreateParams structured = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(system)
                .addUserMessage(prompt)
                .outputConfig(type)
                .build()
                .rawParams();
        MessageCreateParams.Builder params = structured.toBuilder()
                .outputConfig(OutputConfig.builder()
                        .effort(switch (effort) {
                            case LOW -> OutputConfig.Effort.LOW;
                            case MEDIUM -> OutputConfig.Effort.MEDIUM;
                            case HIGH -> OutputConfig.Effort.HIGH;
                        })
                        .format(structured.outputConfig().flatMap(OutputConfig::format).orElseThrow())
                        .build());
        if (fallbacks) {
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        Message message;
        try {
            message = client.messages().create(params.build());
        } catch (RateLimitException e) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Claude is busy right now. Try again in a minute.");
        } catch (AnthropicServiceException e) {
            log.warn("Claude request for {} failed with status {}: {}", feature, e.statusCode(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Claude could not answer (error " + e.statusCode() + ").");
        } catch (AnthropicException e) {
            log.warn("Claude request for {} failed: {}", feature, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach Claude. Try again later.");
        }
        audit.record(user, "ai." + feature, null, "model " + message.model().asString());
        StopReason stop = message.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Claude declined to help with this request.");
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Claude's answer was too long. Try a narrower request.");
        }
        String text = message.content().stream().map(ContentBlock::text).flatMap(java.util.Optional::stream)
                .map(TextBlock::text).collect(Collectors.joining());
        try {
            return json.readValue(text, type);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Claude answer for {} did not match the expected shape: {}", feature, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Claude's answer could not be read. Try again.");
        }
    }

    /** At most {@code app.ai.requests-per-hour} requests per person. */
    private void throttle(User user) {
        Deque<Instant> times = recent.computeIfAbsent(user.getId(), id -> new ArrayDeque<>());
        synchronized (times) {
            Instant cutoff = Instant.now().minus(Duration.ofHours(1));
            while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
                times.pollFirst();
            }
            if (times.size() >= perHour) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "You have used the Claude assistant " + perHour + " times in the last hour. Try again later.");
            }
            times.addLast(Instant.now());
        }
    }
}
