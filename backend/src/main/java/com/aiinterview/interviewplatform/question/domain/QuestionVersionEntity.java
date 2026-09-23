package com.aiinterview.interviewplatform.question.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Question content. <strong>Immutable once PUBLISHED</strong>, enforced by the
 * database trigger {@code trg_qv_20_guard}.
 *
 * <p>This is the foundation of score comparability: an interview attempt pins
 * a version id per turn, so revising a question tomorrow cannot change what a
 * score earned today means. Publishing is gated by
 * {@code trg_qv_10_validate}, which requires 3–10 rubric criteria summing to
 * exactly 10000 basis points and a reference answer.
 *
 * <p>Skill, type and difficulty live here rather than on the family because
 * they are content attributes that may legitimately change with a revision.
 */
@Entity
@Table(name = "question_versions", schema = "app")
public class QuestionVersionEntity {

    public enum QuestionType { CONCEPTUAL, SCENARIO, DESIGN, TROUBLESHOOTING }

    public enum Difficulty { EASY, MEDIUM, HARD }

    public enum Status { DRAFT, PUBLISHED, ARCHIVED }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false)
    private QuestionType questionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false)
    private Difficulty difficulty;

    @Column(name = "prompt_text", nullable = false)
    private String promptText;

    @Column(name = "context_text")
    private String contextText;

    /** Grounding for evaluation and admin review; never shown mid-interview. */
    @Column(name = "reference_answer")
    private String referenceAnswer;

    @Column(name = "expected_duration_sec", nullable = false)
    private Integer expectedDurationSec;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "archived_by")
    private UUID archivedBy;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected QuestionVersionEntity() {
        // for JPA
    }

    /** A new editable version. Content may be incomplete until publish. */
    public static QuestionVersionEntity draft(UUID id, UUID questionId, int version,
                                              UUID skillId, QuestionType questionType,
                                              Difficulty difficulty, String promptText,
                                              String contextText, String referenceAnswer,
                                              int expectedDurationSec, UUID createdBy,
                                              OffsetDateTime now) {
        QuestionVersionEntity entity = new QuestionVersionEntity();
        entity.id = id;
        entity.questionId = questionId;
        entity.version = version;
        entity.skillId = skillId;
        entity.questionType = questionType;
        entity.difficulty = difficulty;
        entity.promptText = promptText;
        entity.contextText = contextText;
        entity.referenceAnswer = referenceAnswer;
        entity.expectedDurationSec = expectedDurationSec;
        entity.status = Status.DRAFT;
        entity.createdBy = createdBy;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    /**
     * Edits the draft.
     *
     * <p>Callers must check {@link #isDraft()} first. The database trigger
     * {@code trg_qv_20_guard} rejects a published edit regardless, but a domain
     * error explaining that version N+1 is the way forward is far more useful
     * than a constraint violation surfacing from three layers down.
     */
    public void updateDraft(UUID skillId, QuestionType questionType, Difficulty difficulty,
                            String promptText, String contextText, String referenceAnswer,
                            Integer expectedDurationSec, OffsetDateTime now) {
        this.skillId = skillId != null ? skillId : this.skillId;
        this.questionType = questionType != null ? questionType : this.questionType;
        this.difficulty = difficulty != null ? difficulty : this.difficulty;
        this.promptText = promptText != null ? promptText : this.promptText;
        // Nullable fields are replaced wholesale: passing null must be able to
        // clear optional content, not silently keep the previous value.
        this.contextText = contextText;
        this.referenceAnswer = referenceAnswer != null ? referenceAnswer : this.referenceAnswer;
        if (expectedDurationSec != null) {
            this.expectedDurationSec = expectedDurationSec;
        }
        this.updatedAt = now;
    }

    /**
     * Freezes this version.
     *
     * <p>From here the content is immutable, and every interview that pins it
     * will grade against exactly these words for as long as the row exists.
     */
    public void publish(UUID publishedBy, OffsetDateTime now) {
        this.status = Status.PUBLISHED;
        this.publishedAt = now;
        this.publishedBy = publishedBy;
        this.updatedAt = now;
    }

    /** Hides the version from new interviews; existing ones are untouched. */
    public void archive(UUID archivedBy, OffsetDateTime now) {
        this.status = Status.ARCHIVED;
        this.archivedAt = now;
        this.archivedBy = archivedBy;
        this.updatedAt = now;
    }

    public boolean isDraft() {
        return status == Status.DRAFT;
    }

    public boolean isPublished() {
        return status == Status.PUBLISHED;
    }

    public UUID getId() { return id; }
    public UUID getQuestionId() { return questionId; }
    public Integer getVersion() { return version; }
    public UUID getSkillId() { return skillId; }
    public QuestionType getQuestionType() { return questionType; }
    public Difficulty getDifficulty() { return difficulty; }
    public String getPromptText() { return promptText; }
    public String getContextText() { return contextText; }
    public String getReferenceAnswer() { return referenceAnswer; }
    public Integer getExpectedDurationSec() { return expectedDurationSec; }
    public Status getStatus() { return status; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public UUID getPublishedBy() { return publishedBy; }
    public OffsetDateTime getArchivedAt() { return archivedAt; }
    public UUID getArchivedBy() { return archivedBy; }
    public UUID getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
