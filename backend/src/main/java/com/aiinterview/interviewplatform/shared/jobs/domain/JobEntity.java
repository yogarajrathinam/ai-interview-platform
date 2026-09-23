package com.aiinterview.interviewplatform.shared.jobs.domain;

import com.aiinterview.interviewplatform.shared.jobs.api.JobStatus;
import com.aiinterview.interviewplatform.shared.jobs.api.JobType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A unit of asynchronous work, stored in the same database as the business
 * write that created it.
 *
 * <p>A database-backed queue was chosen over Redis/SQS/Kafka because the job
 * row is inserted in the <em>same transaction</em> as the answer it grades:
 * there is no outbox to drift, no lost job, and no extra infrastructure to
 * operate. {@code SELECT ... FOR UPDATE SKIP LOCKED} handles far more
 * throughput than Phase 0 will ever produce.
 *
 * <p>Two workers cannot claim the same row: the first holds a row-level write
 * lock inside its claim transaction, {@code SKIP LOCKED} makes the second skip
 * it entirely rather than block, and by the time the lock is released the row
 * is no longer {@code QUEUED}.
 *
 * <p>Deduplication uses a <strong>partial</strong> unique index over active
 * jobs only. A full unique index on {@code dedupeKey} would permanently block
 * re-enqueuing work after the first job succeeded — which would break admin
 * re-evaluation.
 *
 * <p>This table deliberately has <strong>no foreign keys</strong>: operational
 * rows must never block a domain delete, and the payload shape varies by type.
 */
@Entity
@Table(name = "jobs", schema = "app")
public class JobEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, updatable = false)
    private JobType jobType;

    /** Natural key, e.g. {@code EVALUATE_ANSWER:<turnId>}. */
    @Column(name = "dedupe_key", nullable = false, updatable = false)
    private String dedupeKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobStatus status;

    @Column(name = "priority", nullable = false)
    private Short priority;

    @Column(name = "attempts", nullable = false)
    private Short attempts;

    @Column(name = "max_attempts", nullable = false)
    private Short maxAttempts;

    /** Backoff: the job is invisible to claimers until this instant. */
    @Column(name = "run_after", nullable = false)
    private OffsetDateTime runAfter;

    @Column(name = "locked_by")
    private String lockedBy;

    /** Stale locks are reclaimed by the MAINTENANCE job after five minutes. */
    @Column(name = "locked_at")
    private OffsetDateTime lockedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected JobEntity() {
        // for JPA
    }

    /** Enqueues work, available immediately unless a delay is given. */
    public static JobEntity enqueue(UUID id, JobType jobType, String dedupeKey, String payload,
                                    short priority, short maxAttempts,
                                    OffsetDateTime runAfter, OffsetDateTime now) {
        JobEntity entity = new JobEntity();
        entity.id = id;
        entity.jobType = jobType;
        entity.dedupeKey = dedupeKey;
        entity.payload = payload;
        entity.status = JobStatus.QUEUED;
        entity.priority = priority;
        entity.attempts = 0;
        entity.maxAttempts = maxAttempts;
        entity.runAfter = runAfter == null ? now : runAfter;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    /**
     * Takes ownership of this job.
     *
     * <p>Only ever called on a row already locked by {@code FOR UPDATE SKIP
     * LOCKED}, which is what makes the claim atomic — this method records the
     * claim, it does not arbitrate it.
     *
     * <p>The attempt is counted here rather than at completion, so a worker
     * that dies mid-handler has still used one. Otherwise a job that reliably
     * crashes its worker would be retried for ever.
     */
    public void claim(String workerId, OffsetDateTime now) {
        this.status = JobStatus.RUNNING;
        this.lockedBy = workerId;
        this.lockedAt = now;
        this.attempts = (short) (this.attempts + 1);
        this.updatedAt = now;
    }

    public void markSucceeded(OffsetDateTime now) {
        this.status = JobStatus.SUCCEEDED;
        // Released so ck_jobs_lock holds: only a RUNNING row may name an owner.
        this.lockedBy = null;
        this.lockedAt = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    /**
     * Schedules another attempt.
     *
     * <p>Returns to {@code QUEUED} rather than staying {@code FAILED}: the
     * claim query looks only at queued rows, so a job parked in any other state
     * would never be retried. {@code FAILED} exists as the transient state a
     * handler reports, not as somewhere work rests.
     */
    public void scheduleRetry(OffsetDateTime nextAttemptAt, String error, OffsetDateTime now) {
        this.status = JobStatus.QUEUED;
        this.lockedBy = null;
        this.lockedAt = null;
        this.runAfter = nextAttemptAt;
        this.lastError = error;
        this.updatedAt = now;
    }

    /**
     * Gives up permanently.
     *
     * <p>The row is kept, never deleted: a dead job is the record that work was
     * asked for and never done, which is precisely what an operator needs.
     */
    public void markDead(String error, OffsetDateTime now) {
        this.status = JobStatus.DEAD;
        this.lockedBy = null;
        this.lockedAt = null;
        this.lastError = error;
        this.updatedAt = now;
    }

    /** True once this attempt would exceed the configured ceiling. */
    public boolean hasExhaustedAttempts() {
        return attempts >= maxAttempts;
    }

    /** A claim is stale when its owner has been silent past the lease. */
    public boolean isLeaseExpired(OffsetDateTime cutoff) {
        return status == JobStatus.RUNNING && lockedAt != null && lockedAt.isBefore(cutoff);
    }

    public UUID getId() { return id; }
    public JobType getJobType() { return jobType; }
    public String getDedupeKey() { return dedupeKey; }
    public String getPayload() { return payload; }
    public JobStatus getStatus() { return status; }
    public Short getPriority() { return priority; }
    public Short getAttempts() { return attempts; }
    public Short getMaxAttempts() { return maxAttempts; }
    public OffsetDateTime getRunAfter() { return runAfter; }
    public String getLockedBy() { return lockedBy; }
    public OffsetDateTime getLockedAt() { return lockedAt; }
    public String getLastError() { return lastError; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
