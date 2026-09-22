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
 * An authored interview definition, versioned like a question.
 * <strong>Immutable once PUBLISHED</strong> ({@code trg_tpl_20_guard}).
 *
 * <p>An attempt pins one of these <em>version</em> rows, which is what keeps
 * skill weights — and therefore the meaning of an overall score — stable
 * forever. Publishing is gated by {@code trg_tpl_10_validate}: skill weights
 * must sum to 10000 bp, slot count must equal {@code coreQuestionCount},
 * positions must be contiguous, and every pooled slot's skill must be
 * weighted by the template.
 *
 * <p>Engine configuration is held in flat columns rather than JSON because
 * every one of these values is queried, validated and displayed.
 */
@Entity
@Table(name = "interview_templates", schema = "app")
public class InterviewTemplateEntity {

    public enum Level { ENTRY, JUNIOR, MID, SENIOR }

    public enum Status { DRAFT, PUBLISHED, ARCHIVED }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "template_key", nullable = false)
    private String templateKey;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "summary")
    private String summary;

    @Column(name = "description")
    private String description;

    @Column(name = "instructions")
    private String instructions;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false)
    private Level level;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "core_question_count", nullable = false)
    private Short coreQuestionCount;

    @Column(name = "max_follow_ups_total", nullable = false)
    private Short maxFollowUpsTotal;

    @Column(name = "max_follow_ups_per_parent", nullable = false)
    private Short maxFollowUpsPerParent;

    @Column(name = "target_duration_min", nullable = false)
    private Short targetDurationMin;

    @Column(name = "hard_duration_min", nullable = false)
    private Short hardDurationMin;

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

    protected InterviewTemplateEntity() {
        // for JPA
    }

    public UUID getId() { return id; }
    public String getTemplateKey() { return templateKey; }
    public Integer getVersion() { return version; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public String getDescription() { return description; }
    public String getInstructions() { return instructions; }
    public Level getLevel() { return level; }
    public Status getStatus() { return status; }
    public Short getCoreQuestionCount() { return coreQuestionCount; }
    public Short getMaxFollowUpsTotal() { return maxFollowUpsTotal; }
    public Short getMaxFollowUpsPerParent() { return maxFollowUpsPerParent; }
    public Short getTargetDurationMin() { return targetDurationMin; }
    public Short getHardDurationMin() { return hardDurationMin; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public UUID getPublishedBy() { return publishedBy; }
    public OffsetDateTime getArchivedAt() { return archivedAt; }
    public UUID getArchivedBy() { return archivedBy; }
    public UUID getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
