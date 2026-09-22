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
 * A turn: one question put to one candidate in one attempt.
 *
 * <p>The turn is the unit that makes deferred follow-ups, refresh-safety and
 * a future voice mode work without schema change.
 *
 * <p>{@code skillId} and {@code weightBp} are <strong>deliberate
 * snapshots</strong>. They let the reporting engine recompute a historical
 * score reading only attempt-scoped tables, so archiving a question or
 * refactoring the catalog can never alter a past result.
 *
 * <p>Core turns carry weight and no parent. Follow-up turns carry a parent, a
 * targeted rubric criterion, their own prompt text, and <strong>zero
 * weight</strong> — enforced by {@code ck_iq_shape}. A follow-up re-probes a
 * criterion the candidate under-answered; giving it independent weight would
 * let a weak topic dominate purely because it was asked about twice.
 */
@Entity
@Table(name = "interview_questions", schema = "app")
public class InterviewQuestionEntity {

    public enum Kind { CORE, FOLLOW_UP }

    public enum Status { PENDING, ASKED, ANSWERED, SKIPPED, EVALUATED, EVAL_FAILED }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "interview_id", nullable = false, updatable = false)
    private UUID interviewId;

    @Column(name = "position", nullable = false)
    private Integer position;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private Kind kind;

    /** The core turn a follow-up probes; null for core turns. */
    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    /** Pinned content. Immutable for the life of the attempt. */
    @Column(name = "question_version_id", nullable = false, updatable = false)
    private UUID questionVersionId;

    @Column(name = "skill_id", nullable = false, updatable = false)
    private UUID skillId;

    /** Zero for follow-ups. */
    @Column(name = "weight_bp", nullable = false, updatable = false)
    private Integer weightBp;

    /** The criterion this follow-up probes; null for core turns. */
    @Column(name = "follow_up_criterion_id", updatable = false)
    private UUID followUpCriterionId;

    /** Snapshot of the follow-up prompt actually shown; null for core turns. */
    @Column(name = "prompt_text", updatable = false)
    private String promptText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "asked_at")
    private OffsetDateTime askedAt;

    @Column(name = "time_spent_sec")
    private Integer timeSpentSec;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected InterviewQuestionEntity() {
        // for JPA
    }

    public UUID getId() { return id; }
    public UUID getInterviewId() { return interviewId; }
    public Integer getPosition() { return position; }
    public Kind getKind() { return kind; }
    public UUID getParentId() { return parentId; }
    public UUID getQuestionVersionId() { return questionVersionId; }
    public UUID getSkillId() { return skillId; }
    public Integer getWeightBp() { return weightBp; }
    public UUID getFollowUpCriterionId() { return followUpCriterionId; }
    public String getPromptText() { return promptText; }
    public Status getStatus() { return status; }
    public OffsetDateTime getAskedAt() { return askedAt; }
    public Integer getTimeSpentSec() { return timeSpentSec; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
