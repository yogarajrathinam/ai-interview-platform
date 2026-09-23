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
 * The stable <em>identity</em> of a question across content revisions.
 *
 * <p>Deliberately thin: all content lives on {@link QuestionVersionEntity}.
 * Template slots and analytics reference this family id, so a question keeps
 * one identity no matter how often its wording is revised.
 */
@Entity
@Table(name = "questions", schema = "app")
public class QuestionEntity {

    public enum Status { ACTIVE, ARCHIVED }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Human-stable key, e.g. {@code java.hashmap-vs-chm}; makes seeding idempotent. */
    @Column(name = "question_key", nullable = false)
    private String questionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected QuestionEntity() {
        // for JPA
    }

    /** A new question family. Content lives on its versions, never here. */
    public static QuestionEntity create(UUID id, String questionKey, UUID createdBy,
                                        OffsetDateTime now) {
        QuestionEntity entity = new QuestionEntity();
        entity.id = id;
        entity.questionKey = questionKey;
        entity.status = Status.ACTIVE;
        entity.createdBy = createdBy;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    /** Retires the family. Published versions stay readable to existing attempts. */
    public void archive(OffsetDateTime now) {
        this.status = Status.ARCHIVED;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getQuestionKey() { return questionKey; }
    public Status getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
