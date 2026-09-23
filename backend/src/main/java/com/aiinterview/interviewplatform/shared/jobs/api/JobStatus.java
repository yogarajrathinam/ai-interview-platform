package com.aiinterview.interviewplatform.shared.jobs.api;

/**
 * Execution state of a unit of asynchronous work.
 *
 * <p>Deliberately distinct from any business lifecycle. A job says whether the
 * <em>work</em> has run; whether the <em>evaluation</em> succeeded is recorded
 * by the evaluation module. Conflating them would mean a retried job could
 * rewrite a candidate's result, and a permanently dead job would have nowhere
 * to say "we never managed to grade this".
 *
 * <p>Maps one-to-one onto {@code ck_jobs_status}, which is the authority.
 */
public enum JobStatus {

    /** Available to claim once {@code run_after} has passed. */
    QUEUED,

    /**
     * Claimed and executing. {@code ck_jobs_lock} guarantees such a row names
     * its owner, so an orphaned claim is always attributable.
     */
    RUNNING,

    SUCCEEDED,

    /** Failed but retryable; returns to {@link #QUEUED} with a later run time. */
    FAILED,

    /**
     * Terminal failure — retries exhausted, or the request can never succeed.
     * Deliberately not deleted: a dead job is the record that some work was
     * asked for and never done, which is exactly what an operator needs to see.
     */
    DEAD;

    /** Occupies the active-dedupe slot, so no duplicate can be enqueued. */
    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == DEAD;
    }
}
