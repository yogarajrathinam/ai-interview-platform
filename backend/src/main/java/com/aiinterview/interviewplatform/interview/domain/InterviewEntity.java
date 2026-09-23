package com.aiinterview.interviewplatform.interview.domain;

import com.aiinterview.interviewplatform.interview.api.CompletionReason;
import com.aiinterview.interviewplatform.interview.api.InterviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One candidate's interview attempt — the historical record.
 *
 * <p>Four states only. Expiry, auto-completion and admin abandonment are
 * <em>reasons</em>, not states, which is why {@link CompletionReason} exists
 * as a separate column.
 *
 * <p>{@code templateId} points at a template <em>version</em> row, not a
 * family. Both parent references are {@code ON DELETE RESTRICT}, so neither
 * the candidate nor the pinned template version can be deleted while this
 * attempt exists — one of the four mechanisms guaranteeing that a completed
 * interview stays reproducible.
 *
 * <p>There is no {@code finalScore} column: the current
 * {@code interview_reports} row holds the score, and its absence on a
 * COMPLETED attempt is exactly the NOT_SCORED case.
 */
@Entity
@Table(name = "interviews", schema = "app")
public class InterviewEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "candidate_user_id", nullable = false, updatable = false)
    private UUID candidateUserId;

    /** The pinned template VERSION row. */
    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InterviewStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_reason")
    private CompletionReason completionReason;

    @Column(name = "hard_deadline_at", nullable = false)
    private OffsetDateTime hardDeadlineAt;

    @Column(name = "last_activity_at", nullable = false)
    private OffsetDateTime lastActivityAt;

    /**
     * Non-null once the deferred follow-up selector has run. Distinguishes
     * "chose zero follow-ups" from "has not looked yet", which is what makes
     * the advance operation idempotent.
     */
    @Column(name = "follow_ups_selected_at")
    private OffsetDateTime followUpsSelectedAt;

    /** Entry into COMPLETING; drives the 15-minute report ceiling. */
    @Column(name = "completing_at")
    private OffsetDateTime completingAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "completed_by")
    private UUID completedBy;

    @Column(name = "engine_version", nullable = false)
    private String engineVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected InterviewEntity() {
        // for JPA
    }

    /**
     * Starts an attempt. Creation and start are one operation — there is no
     * state between them that anything could observe.
     */
    public static InterviewEntity start(UUID id, UUID candidateUserId, UUID templateId,
                                        OffsetDateTime hardDeadlineAt, String engineVersion,
                                        OffsetDateTime now) {
        InterviewEntity entity = new InterviewEntity();
        entity.id = id;
        entity.candidateUserId = candidateUserId;
        entity.templateId = templateId;
        entity.status = InterviewStatus.IN_PROGRESS;
        entity.hardDeadlineAt = hardDeadlineAt;
        entity.lastActivityAt = now;
        entity.engineVersion = engineVersion;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    /** Keeps the idle sweeper honest about whether anyone is still here. */
    public void touchActivity(OffsetDateTime now) {
        this.lastActivityAt = now;
    }

    /**
     * The candidate is done; grading and reporting are still settling.
     *
     * <p>The reason is recorded now, at the moment it is known, rather than
     * held in memory until the attempt finally closes.
     */
    public void beginCompleting(CompletionReason reason, OffsetDateTime now) {
        this.status = InterviewStatus.COMPLETING;
        this.completionReason = reason;
        this.completingAt = now;
        this.lastActivityAt = now;
    }

    /**
     * Closes the attempt.
     *
     * <p>{@code completedAt} is what {@code ck_int_terminal} keys on, so it is
     * set here and only here.
     */
    public void finish(CompletionReason reason, OffsetDateTime now, UUID completedBy) {
        this.status = reason.terminalStatus();
        this.completionReason = reason;
        this.completedAt = now;
        this.completedBy = completedBy;
        this.lastActivityAt = now;
        if (this.completingAt == null && this.status == InterviewStatus.COMPLETED) {
            this.completingAt = now;
        }
    }

    /**
     * Marks the deferred follow-up phase as having run.
     *
     * <p>Non-null means "the selector looked", which is what distinguishes
     * choosing zero follow-ups from not having chosen yet — and is what makes
     * advancing idempotent.
     */
    public void markFollowUpsSelected(OffsetDateTime now) {
        this.followUpsSelectedAt = now;
    }

    public boolean hasSelectedFollowUps() {
        return followUpsSelectedAt != null;
    }

    public boolean isExpiredAt(OffsetDateTime now) {
        return now.isAfter(hardDeadlineAt);
    }

    public UUID getId() { return id; }
    public UUID getCandidateUserId() { return candidateUserId; }
    public UUID getTemplateId() { return templateId; }
    public InterviewStatus getStatus() { return status; }
    public CompletionReason getCompletionReason() { return completionReason; }
    public OffsetDateTime getHardDeadlineAt() { return hardDeadlineAt; }
    public OffsetDateTime getLastActivityAt() { return lastActivityAt; }
    public OffsetDateTime getFollowUpsSelectedAt() { return followUpsSelectedAt; }
    public OffsetDateTime getCompletingAt() { return completingAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public UUID getCompletedBy() { return completedBy; }
    public String getEngineVersion() { return engineVersion; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
