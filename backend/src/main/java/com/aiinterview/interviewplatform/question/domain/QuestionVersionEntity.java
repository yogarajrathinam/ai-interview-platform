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
