package com.aiinterview.interviewplatform.shared.jobs.api;

/**
 * A handler could not complete its work.
 *
 * <p>Carries the one decision the worker cannot make for itself: whether trying
 * again could plausibly help. Only the handler knows whether a provider timed
 * out (try again) or the request referenced a question version that no longer
 * exists (never going to work).
 *
 * <p>Getting this wrong is costly in both directions. Retrying a permanently
 * invalid request burns attempts and provider budget on work that cannot
 * succeed; burying a transient failure loses a candidate's grade for good. When
 * genuinely unsure, prefer retryable — attempts are bounded, and a job that
 * ends up dead after four tries is recoverable by an operator, whereas one
 * buried immediately looks deliberate.
 */
public class JobExecutionException extends RuntimeException {

    private final boolean retryable;

    private JobExecutionException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * Transient: a timeout, a rate limit, a dropped connection, an unavailable
     * dependency. The job returns to the queue with backoff.
     */
    public static JobExecutionException retryable(String message) {
        return new JobExecutionException(message, true, null);
    }

    public static JobExecutionException retryable(String message, Throwable cause) {
        return new JobExecutionException(message, true, cause);
    }

    /**
     * Permanent: a malformed request, a missing immutable reference, an
     * impossible domain state. The job is buried without further attempts and
     * <strong>without fabricating a result</strong>.
     */
    public static JobExecutionException permanent(String message) {
        return new JobExecutionException(message, false, null);
    }

    public static JobExecutionException permanent(String message, Throwable cause) {
        return new JobExecutionException(message, false, cause);
    }

    public boolean isRetryable() {
        return retryable;
    }
}
