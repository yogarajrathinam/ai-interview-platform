package com.aiinterview.interviewplatform.shared.jobs.domain;

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

    /**
     * Follow-up selection is deliberately absent: it is pure computation over
     * rows already stored, and making it a job would add latency at the one
     * point where the candidate is actually waiting.
     */
    public enum JobType { EVALUATE_ANSWER, GENERATE_REPORT, MAINTENANCE }

    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, DEAD }

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
    private Status status;

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

    public UUID getId() { return id; }
    public JobType getJobType() { return jobType; }
    public String getDedupeKey() { return dedupeKey; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
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
