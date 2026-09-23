package com.aiinterview.interviewplatform.shared.jobs.api;

import java.util.UUID;

/**
 * Executes one kind of job.
 *
 * <p>This is the inversion that lets the worker live in {@code shared} without
 * knowing what an evaluation is. {@code shared} owns the mechanics — claiming,
 * retrying, leasing — and business modules contribute handlers. The dependency
 * points inward, so the queue never learns about the interview module.
 *
 * <p>Implementations must be <strong>idempotent</strong>. A worker can die
 * after its handler committed but before the job was marked done, so any
 * handler may run twice for the same work. Relying on an existing uniqueness
 * constraint is the intended way to achieve this, not bookkeeping of one's own.
 *
 * <p>Implementations must <strong>not</strong> hold a transaction across a
 * provider call. The worker holds none while calling a handler, and a handler
 * that opened one around a model call would reintroduce exactly the problem the
 * queue exists to avoid.
 */
public interface JobHandler {

    /** The single job type this handler is dispatched for. */
    JobType jobType();

    /**
     * Runs the work.
     *
     * <p>Returning normally means success. Failure is expressed by throwing
     * {@link JobExecutionException}, whose {@code retryable} flag decides
     * whether the job is rescheduled or buried. Any other exception is treated
     * as retryable — an unexpected fault is more likely transient than
     * permanent, and burying work on an unrecognised error loses it.
     */
    void handle(JobContext context);

    /**
     * What the handler is given.
     *
     * @param attempt 1 for the first execution, so logs and messages read the
     *                way a human counts
     */
    record JobContext(UUID jobId, JobType jobType, String dedupeKey, String payload,
                      int attempt, int maxAttempts) {

        public boolean isFinalAttempt() {
            return attempt >= maxAttempts;
        }
    }
}
