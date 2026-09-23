package com.aiinterview.interviewplatform.shared.jobs.api;

/**
 * The kinds of asynchronous work the platform runs.
 *
 * <p>Constrained by {@code ck_jobs_type}, so adding a value is a migration
 * rather than a code change alone — deliberately, because an unrecognised job
 * type sitting in the queue is a silent failure.
 *
 * <p>Follow-up selection is deliberately absent: it is pure computation over
 * rows already stored, and making it a job would add latency at the one point
 * where a candidate is actually waiting.
 */
public enum JobType {

    /** Grade one stored answer. The only type M4 dispatches. */
    EVALUATE_ANSWER,

    /** Reserved for M5; no handler exists yet. */
    GENERATE_REPORT,

    /** Housekeeping: lease recovery, expiry sweeps, retention. */
    MAINTENANCE
}
