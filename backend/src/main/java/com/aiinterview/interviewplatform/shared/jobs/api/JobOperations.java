package com.aiinterview.interviewplatform.shared.jobs.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What an operator needs to know about the queue.
 *
 * <p>Every figure here is counted from rows that already exist. Nothing is
 * sampled, estimated or accumulated in memory — a restart must not change the
 * answer, and a number an operator cannot reproduce with SQL is a number they
 * will not trust at three in the morning.
 *
 * <p>Deliberately not a metrics system. The questions it answers are the ones a
 * person asks when something looks wrong, not a time series.
 */
public interface JobOperations {

    QueueSnapshot snapshot();

    /**
     * Work that will never run without intervention.
     *
     * <p>The first thing to look at when a candidate says their result never
     * appeared.
     */
    List<JobSummary> deadJobs(int limit);

    /**
     * Jobs that have failed at least once and are still being retried.
     *
     * <p>A job on its third attempt is a warning that something downstream is
     * unhealthy, well before it becomes a dead one.
     */
    List<JobSummary> retryingJobs(int limit);

    /**
     * @param byTypeAndStatus counts keyed {@code "TYPE/STATUS"}, so a type with
     *                        no work in a state is simply absent rather than
     *                        reported as a fabricated zero
     * @param oldestQueuedAt  null when nothing is waiting; the backlog-age
     *                        signal that matters more than depth alone
     * @param totalAttempts   attempts ever made, including retries — the
     *                        difference between this and job count is the
     *                        system's retry load
     */
    record QueueSnapshot(long queued, long running, long succeeded, long failed, long dead,
                         Map<String, Long> byTypeAndStatus, OffsetDateTime oldestQueuedAt,
                         Long oldestQueuedAgeSeconds, long totalAttempts) {

        public long backlog() {
            return queued + running;
        }

        public boolean hasDeadWork() {
            return dead > 0;
        }
    }

    /**
     * One job, safe to log and display.
     *
     * <p>{@code dedupeKey} and {@code payload} carry correlation ids only —
     * never candidate content — so this record can be shown to an operator
     * without exposing anyone's answer.
     */
    record JobSummary(UUID jobId, JobType jobType, String dedupeKey, JobStatus status,
                      int attempts, int maxAttempts, OffsetDateTime runAfter,
                      OffsetDateTime updatedAt, String lastError) {
    }
}
