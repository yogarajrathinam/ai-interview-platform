package com.aiinterview.interviewplatform.shared.jobs.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Enqueues asynchronous work.
 *
 * <p>The queue lives in the same database as the business data, which is the
 * whole point: a job row is inserted in the <strong>same transaction</strong>
 * as the change that requires it. Either both commit or neither does, so there
 * is no outbox to drift and no window in which an answer exists with nothing
 * scheduled to grade it.
 *
 * <p>Callers are expected to enqueue from inside their own transaction. This
 * interface deliberately starts none of its own.
 */
public interface JobQueue {

    /**
     * Enqueues work, unless an identical job is already active.
     *
     * <p>Deduplication is by {@code dedupeKey} over queued and running rows
     * only ({@code uq_jobs_active_dedupe}). Work that already succeeded can
     * therefore be enqueued again — which is what makes an administrative
     * re-grade possible — while a double submit cannot produce two live jobs.
     *
     * @return the job id, or empty when an active duplicate already existed
     */
    Optional<UUID> enqueue(JobRequest request);

    /**
     * A unit of work.
     *
     * @param dedupeKey natural identity of the work, e.g.
     *                  {@code EVALUATE_ANSWER:<turnId>} — not a random value,
     *                  or deduplication would never fire
     * @param payload   JSON the handler needs. Ids and safe metadata only:
     *                  this column is not a place for candidate content
     * @param priority  lower runs first
     * @param delaySeconds initial delay; 0 means available immediately
     */
    record JobRequest(JobType jobType, String dedupeKey, String payload,
                      int priority, int maxAttempts, long delaySeconds) {

        public JobRequest {
            if (jobType == null) {
                throw new IllegalArgumentException("jobType is required");
            }
            if (dedupeKey == null || dedupeKey.isBlank()) {
                throw new IllegalArgumentException("dedupeKey is required");
            }
            if (payload == null || payload.isBlank()) {
                payload = "{}";
            }
        }

        /** The ordinary case: run as soon as a worker is free. */
        public static JobRequest of(JobType jobType, String dedupeKey, String payload) {
            return new JobRequest(jobType, dedupeKey, payload, 100, 4, 0);
        }
    }
}
