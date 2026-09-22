package com.aiinterview.interviewplatform.interview.domain;

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

    public enum Status { IN_PROGRESS, COMPLETING, COMPLETED, ABANDONED }

    public enum CompletionReason {
        CANDIDATE_FINISHED, ALL_ANSWERED, TIME_EXPIRED, AUTO_COMPLETED,
        ABANDONED_BY_CANDIDATE, ABANDONED_EXPIRED, ABANDONED_BY_ADMIN
    }

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
    private Status status;

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

    public UUID getId() { return id; }
    public UUID getCandidateUserId() { return candidateUserId; }
    public UUID getTemplateId() { return templateId; }
    public Status getStatus() { return status; }
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
