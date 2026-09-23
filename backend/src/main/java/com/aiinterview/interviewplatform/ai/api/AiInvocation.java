package com.aiinterview.interviewplatform.ai.api;

import java.util.UUID;

/**
 * One call to a model provider, as recorded for accounting and forensics.
 *
 * <p>Mirrors {@code ai_invocations}. Costs are integer micro-USD: money is
 * never a floating-point number.
 *
 * <p>Token and cost fields are nullable and must stay null when they are not
 * genuinely known. A local deterministic provider consumed no tokens, and
 * writing zeros there would quietly corrupt the unit-economics figures this
 * table exists to produce.
 */
public record AiInvocation(

        Purpose purpose,

        /** e.g. {@code deterministic}, later {@code anthropic}. */
        String provider,

        /** The exact pinned model id, never an alias such as "latest". */
        String model,

        String promptVersion,

        /** See {@link AiInvocationRecorder#fingerprint}. */
        String requestFingerprint,

        Status status,

        Integer httpStatus,

        Integer latencyMs,

        Integer inputTokens,

        Integer outputTokens,

        /** Integer micro-USD. Null when not applicable. */
        Long costMicros,

        /** 0 for the first attempt. */
        int retryIndex,

        /** Raw provider payload as JSON. Purged after 30 days: it holds answer text. */
        String rawResponseJson,

        String errorDetail,

        UUID interviewId,

        UUID answerId,

        UUID userId
) {

    public AiInvocation {
        if (purpose == null) {
            throw new IllegalArgumentException("purpose is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (costMicros != null && costMicros < 0) {
            throw new IllegalArgumentException("cost cannot be negative");
        }
    }

    /** Constrained by {@code ck_ai_purpose}. */
    public enum Purpose {
        ANSWER_EVALUATION,
        REPORT_SUMMARY
    }

    /** Constrained by {@code ck_ai_status}. */
    public enum Status {
        SUCCEEDED,
        TIMEOUT,
        PROVIDER_ERROR,
        RATE_LIMITED,
        INVALID_OUTPUT
    }

    public static Builder builder(Purpose purpose, String provider, String model) {
        return new Builder(purpose, provider, model);
    }

    /** Enough fields that positional construction would be unreadable. */
    public static final class Builder {
        private final Purpose purpose;
        private final String provider;
        private final String model;
        private String promptVersion = "n/a";
        private String requestFingerprint = "";
        private Status status = Status.SUCCEEDED;
        private Integer httpStatus;
        private Integer latencyMs;
        private Integer inputTokens;
        private Integer outputTokens;
        private Long costMicros;
        private int retryIndex;
        private String rawResponseJson;
        private String errorDetail;
        private UUID interviewId;
        private UUID answerId;
        private UUID userId;

        private Builder(Purpose purpose, String provider, String model) {
            this.purpose = purpose;
            this.provider = provider;
            this.model = model;
        }

        public Builder promptVersion(String value) {
            this.promptVersion = value;
            return this;
        }

        public Builder requestFingerprint(String value) {
            this.requestFingerprint = value;
            return this;
        }

        public Builder status(Status value) {
            this.status = value;
            return this;
        }

        public Builder latencyMs(Integer value) {
            this.latencyMs = value;
            return this;
        }

        public Builder tokens(Integer input, Integer output) {
            this.inputTokens = input;
            this.outputTokens = output;
            return this;
        }

        public Builder costMicros(Long value) {
            this.costMicros = value;
            return this;
        }

        public Builder retryIndex(int value) {
            this.retryIndex = value;
            return this;
        }

        public Builder rawResponseJson(String value) {
            this.rawResponseJson = value;
            return this;
        }

        public Builder errorDetail(String value) {
            this.errorDetail = value;
            return this;
        }

        public Builder correlation(UUID interviewId, UUID answerId, UUID userId) {
            this.interviewId = interviewId;
            this.answerId = answerId;
            this.userId = userId;
            return this;
        }

        public Builder httpStatus(Integer value) {
            this.httpStatus = value;
            return this;
        }

        public AiInvocation build() {
            return new AiInvocation(purpose, provider, model, promptVersion, requestFingerprint,
                    status, httpStatus, latencyMs, inputTokens, outputTokens, costMicros,
                    retryIndex, rawResponseJson, errorDetail, interviewId, answerId, userId);
        }
    }
}
