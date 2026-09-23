package com.aiinterview.interviewplatform.evaluation.infrastructure.anthropic;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationProvider;
import com.aiinterview.interviewplatform.shared.config.AppProperties;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the real provider, and only when it is asked for.
 *
 * <p>Selection is a single property. Nothing else in the application knows which
 * provider is active: the engine depends on {@link EvaluationProvider}, and both
 * implementations are gated on the same key so exactly one bean can ever exist.
 * Choosing between them with {@code @Primary} would have left the unused client
 * constructed and its credential required, which is the opposite of what a
 * default-safe configuration should do.
 *
 * <p>The default is {@code deterministic}, so a fresh checkout, a test run and a
 * misconfigured environment all start without a credential and without the
 * ability to spend money by accident. Reaching the paid provider takes a
 * deliberate act.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "anthropic")
public class AnthropicProviderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProviderConfiguration.class);

    /**
     * The SDK client.
     *
     * <p>The credential is required here rather than at the first call: an
     * environment that cannot grade should fail to start, loudly, instead of
     * accepting interviews and failing every evaluation an hour later. The
     * message names the property and never any part of the value.
     */
    @Bean
    public AnthropicClient anthropicClient(AppProperties properties) {
        AppProperties.Ai ai = properties.ai();

        if (ai.apiKey() == null || ai.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "app.ai.provider=anthropic requires app.ai.api-key "
                            + "(supply it through the ANTHROPIC_API_KEY environment variable)");
        }

        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(ai.apiKey())
                .timeout(Duration.ofSeconds(ai.timeoutSeconds()))
                // Retries are the job queue's responsibility. Letting the SDK
                // also retry would multiply the two budgets together and hide
                // rate-limiting from the metrics that are supposed to show it.
                .maxRetries(0);

        if (ai.baseUrl() != null && !ai.baseUrl().isBlank()) {
            builder.baseUrl(ai.baseUrl());
        }

        // Safe to log: describe() excludes the credential.
        log.info("AI evaluation provider active: {}", ai.describe());
        return builder.build();
    }

    @Bean
    public EvaluationProvider anthropicEvaluationProvider(AnthropicClient client,
                                                          EvaluationPrompt prompt,
                                                          AppProperties properties) {
        return new AnthropicEvaluationProvider(client, prompt, properties.ai());
    }
}
