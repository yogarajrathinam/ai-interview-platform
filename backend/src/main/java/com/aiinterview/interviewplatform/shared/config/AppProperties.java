package com.aiinterview.interviewplatform.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly typed application configuration.
 *
 * <p>Validated at startup: a missing or malformed value fails the context
 * refresh immediately rather than surfacing as a {@code null} in production.
 * Nothing secret belongs here — secrets arrive as environment variables and
 * are consumed by Spring Boot's own datasource/security configuration.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(

        /** Deployment environment name, used in logs and diagnostics. */
        @NotBlank
        @Pattern(regexp = "local|test|staging|prod",
                 message = "must be one of: local, test, staging, prod")
        String environment,

        /**
         * Build identifier of the interview engine, stamped onto every
         * interview attempt for forensics ({@code interviews.engine_version}).
         */
        @NotBlank
        String engineVersion,

        @Valid
        @DefaultValue
        Api api,

        @Valid
        @DefaultValue
        Jobs jobs,

        @Valid
        @DefaultValue
        Ai ai
) {

    public record Api(
            @NotBlank
            @Pattern(regexp = "/api/v\\d+", message = "must look like /api/v1")
            @DefaultValue("/api/v1")
            String basePath
    ) {}

    /**
     * Asynchronous worker settings.
     *
     * <p>Every value here is a deliberate Phase 0 choice rather than a tuned
     * one, and each is externalised so it can be changed without a deploy.
     */
    public record Jobs(

            /**
             * Whether this instance processes work. Lets the worker be split
             * into its own deployment later by configuration alone — it holds
             * no state, so the queue is the only coordination point.
             */
            @DefaultValue("true")
            boolean workerEnabled,

            /**
             * How often to look for work.
             *
             * <p>One second: a candidate is not waiting on grading, so faster
             * polling buys nothing, and an idle instance issues one indexed
             * query per second against a partial index covering only queued
             * rows. Notification-based wake-up was considered and rejected —
             * {@code LISTEN/NOTIFY} does not survive a transaction-mode
             * connection pooler.
             */
            @Min(100) @Max(60_000)
            @DefaultValue("1000")
            long pollIntervalMs,

            /**
             * Jobs claimed per poll. Bounded so one worker cannot take the
             * whole backlog and then lose it all by crashing.
             */
            @Min(1) @Max(100)
            @DefaultValue("5")
            int batchSize,

            /**
             * How long a claim is honoured before another worker may reclaim it.
             *
             * <p>Must comfortably exceed the slowest expected handler: a lease
             * that expires while work is still running invites two workers into
             * the same job, and only the handler's own idempotency would then
             * save the result. Five minutes against a 30-second provider
             * timeout leaves an order of magnitude of headroom.
             */
            @Min(30) @Max(3600)
            @DefaultValue("300")
            long leaseSeconds,

            /** Attempts before a job is buried. */
            @Min(1) @Max(10)
            @DefaultValue("4")
            int maxAttempts,

            /**
             * First retry delay. Subsequent delays double, capped by
             * {@link #maxBackoffSeconds} — 5s, 10s, 20s, 40s.
             */
            @Min(1) @Max(600)
            @DefaultValue("5")
            long initialBackoffSeconds,

            @Min(5) @Max(86_400)
            @DefaultValue("600")
            long maxBackoffSeconds,

            /**
             * Backlog depth above which the queue reports itself degraded.
             * Advisory only — a deep queue is a capacity signal, not a reason
             * to take the application out of service.
             */
            @Min(1)
            @DefaultValue("500")
            int backlogWarningThreshold
    ) {}

    /**
     * AI evaluation provider settings.
     *
     * <p>Vendor-neutral at this level: {@link #provider} selects an adapter and
     * the adapter interprets the rest. Adding a second vendor is a new adapter
     * plus a new value here, never a change to anything that scores.
     *
     * <p>{@code apiKey} is read from the environment and is never defaulted to
     * a real value, never logged, and never written to a file in this repository.
     */
    public record Ai(

            /**
             * Which adapter serves {@code EvaluationProvider}.
             *
             * <p>Defaults to {@code deterministic} deliberately: starting the
             * application must never require a paid credential, and a
             * misconfigured environment should fall back to something that
             * cannot spend money by accident.
             */
            @NotBlank
            @Pattern(regexp = "deterministic|anthropic",
                     message = "must be one of: deterministic, anthropic")
            @DefaultValue("deterministic")
            String provider,

            /**
             * Exact model id, never an alias.
             *
             * <p>An alias like "latest" would let a vendor upgrade silently
             * change what a score means, which is the drift the whole
             * provenance trail exists to catch.
             */
            @NotBlank
            @DefaultValue("claude-opus-5")
            String model,

            /** Credential. Supplied by the environment; absent in tests. */
            String apiKey,

            /**
             * Overridden only to point the adapter at a stub server in tests.
             * Empty means the vendor's own endpoint.
             */
            String baseUrl,

            /**
             * Per-call ceiling. Grading happens off the request thread, so this
             * bounds a worker's lease rather than anyone's page load — and it
             * sits an order of magnitude below the job lease (300s) so a slow
             * call cannot outlive its claim.
             */
            @Min(5) @Max(300)
            @DefaultValue("60")
            int timeoutSeconds,

            /** Output ceiling. A rubric verdict set is small; this is headroom. */
            @Min(256) @Max(64_000)
            @DefaultValue("8000")
            int maxOutputTokens,

            /**
             * Reasoning depth. Rubric grading is close reading rather than open
             * problem-solving, so the default is one step below the model's own
             * default — enough for careful comparison without paying for
             * exploration the task does not need.
             */
            @NotBlank
            @Pattern(regexp = "low|medium|high|xhigh|max")
            @DefaultValue("medium")
            String effort
    ) {

        public boolean isDeterministic() {
            return "deterministic".equals(provider);
        }

        /** Safe to log: everything except the credential. */
        public String describe() {
            return "provider=%s model=%s timeoutSeconds=%d effort=%s"
                    .formatted(provider, model, timeoutSeconds, effort);
        }
    }
}
