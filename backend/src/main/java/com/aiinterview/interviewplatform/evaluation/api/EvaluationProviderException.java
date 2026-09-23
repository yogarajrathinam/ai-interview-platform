package com.aiinterview.interviewplatform.evaluation.api;

/**
 * A provider could not produce a result at all.
 *
 * <p>Distinct from a result that fails validation: this is our infrastructure
 * problem, not a judgement about the answer. It becomes
 * {@code FAILED_PROVIDER}, is retryable, and — critically — never costs the
 * candidate a mark. The answer is already stored before grading begins, so no
 * failure here can lose their work.
 */
public class EvaluationProviderException extends RuntimeException {

    private final Reason reason;

    public EvaluationProviderException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public EvaluationProviderException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** Maps onto {@code ai_invocations.status}. */
    public enum Reason {
        TIMEOUT,
        RATE_LIMITED,
        PROVIDER_ERROR,

        /** Output arrived but was unusable — unparseable or schema-violating. */
        INVALID_OUTPUT;

        /** Whether a retry could plausibly succeed. */
        public boolean isRetryable() {
            return this != INVALID_OUTPUT;
        }
    }
}
