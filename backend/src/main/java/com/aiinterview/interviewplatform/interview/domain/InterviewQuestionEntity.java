package com.aiinterview.interviewplatform.interview.domain;

import com.aiinterview.interviewplatform.interview.api.TurnKind;
import com.aiinterview.interviewplatform.interview.api.TurnStatus;
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

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "interview_id", nullable = false, updatable = false)
    private UUID interviewId;

    @Column(name = "position", nullable = false)
    private Integer position;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private TurnKind kind;

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
    private TurnStatus status;

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

    /**
     * A planned turn, resolved from a template slot at interview start.
     *
     * <p>{@code skillId} and {@code weightBp} are copied in rather than joined
     * later: the score must stay recomputable from attempt-scoped rows alone,
     * so archiving a question cannot alter a past result.
     */
    public static InterviewQuestionEntity core(UUID id, UUID interviewId, int position,
                                               UUID questionVersionId, UUID skillId,
                                               int weightBp, OffsetDateTime now) {
        InterviewQuestionEntity entity = new InterviewQuestionEntity();
        entity.id = id;
        entity.interviewId = interviewId;
        entity.position = position;
        entity.kind = TurnKind.CORE;
        entity.questionVersionId = questionVersionId;
        entity.skillId = skillId;
        entity.weightBp = weightBp;
        entity.status = TurnStatus.PENDING;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    /**
     * A probe into a criterion an earlier answer covered weakly.
     *
     * <p>Weight is zero by construction — {@code ck_iq_shape} would reject
     * anything else — so a follow-up moves no average. It carries its own
     * prompt text because the curated probe is not the parent question.
     */
    public static InterviewQuestionEntity followUp(UUID id, UUID interviewId, int position,
                                                   UUID parentId, UUID questionVersionId,
                                                   UUID skillId, UUID followUpCriterionId,
                                                   String promptText, OffsetDateTime now) {
        InterviewQuestionEntity entity = new InterviewQuestionEntity();
        entity.id = id;
        entity.interviewId = interviewId;
        entity.position = position;
        entity.kind = TurnKind.FOLLOW_UP;
        entity.parentId = parentId;
        entity.questionVersionId = questionVersionId;
        entity.skillId = skillId;
        entity.weightBp = 0;
        entity.followUpCriterionId = followUpCriterionId;
        entity.promptText = promptText;
        entity.status = TurnStatus.PENDING;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    /**
     * Serves the turn. Idempotent: re-serving keeps the original
     * {@code asked_at}, so a refreshed browser does not reset the clock.
     */
    public void markAsked(OffsetDateTime now) {
        if (status == TurnStatus.PENDING) {
            this.status = TurnStatus.ASKED;
        }
        if (this.askedAt == null) {
            this.askedAt = now;
        }
    }

    /** {@code ck_iq_asked} requires an ask time on any non-pending turn. */
    public void markAnswered(Integer timeSpentSec, OffsetDateTime now) {
        markAsked(now);
        this.status = TurnStatus.ANSWERED;
        if (timeSpentSec != null && timeSpentSec >= 0) {
            this.timeSpentSec = timeSpentSec;
        }
    }

    public void markSkipped(OffsetDateTime now) {
        markAsked(now);
        this.status = TurnStatus.SKIPPED;
    }

    /** Applied by the orchestrator once grading settles; never by evaluation. */
    public void markEvaluated() {
        this.status = TurnStatus.EVALUATED;
    }

    public void markEvalFailed() {
        this.status = TurnStatus.EVAL_FAILED;
    }

    public boolean isFollowUp() {
        return kind == TurnKind.FOLLOW_UP;
    }

    public UUID getId() { return id; }
    public UUID getInterviewId() { return interviewId; }
    public Integer getPosition() { return position; }
    public TurnKind getKind() { return kind; }
    public UUID getParentId() { return parentId; }
    public UUID getQuestionVersionId() { return questionVersionId; }
    public UUID getSkillId() { return skillId; }
    public Integer getWeightBp() { return weightBp; }
    public UUID getFollowUpCriterionId() { return followUpCriterionId; }
    public String getPromptText() { return promptText; }
    public TurnStatus getStatus() { return status; }
    public OffsetDateTime getAskedAt() { return askedAt; }
    public Integer getTimeSpentSec() { return timeSpentSec; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
