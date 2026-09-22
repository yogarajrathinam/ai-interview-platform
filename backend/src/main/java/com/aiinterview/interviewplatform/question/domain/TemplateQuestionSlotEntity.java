package com.aiinterview.interviewplatform.question.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One ordered slot in a template's question plan.
 *
 * <p>A slot is either <em>pinned</em> (a specific question family) or
 * <em>pooled</em> (pick a published version matching skill and difficulty).
 * The mode is not stored: it is derived from which reference is set, and
 * {@code ck_tqs_mode} enforces {@code num_nonnulls(question_id, skill_id) = 1}.
 * Storing a separate mode column would allow it to disagree with the data.
 *
 * <p>{@code weightBp} is the question's share of the interview, letting a
 * system-design question legitimately count more than a definition question.
 */
@Entity
@Table(name = "template_question_slots", schema = "app")
public class TemplateQuestionSlotEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Column(name = "position", nullable = false)
    private Short position;

    /** Set for a pinned slot; mutually exclusive with {@code skillId}. */
    @Column(name = "question_id")
    private UUID questionId;

    /** Set for a pooled slot; mutually exclusive with {@code questionId}. */
    @Column(name = "skill_id")
    private UUID skillId;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty")
    private QuestionVersionEntity.Difficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type")
    private QuestionVersionEntity.QuestionType questionType;

    @Column(name = "weight_bp", nullable = false)
    private Integer weightBp;

    protected TemplateQuestionSlotEntity() {
        // for JPA
    }

    public UUID getId() { return id; }
    public UUID getTemplateId() { return templateId; }
    public Short getPosition() { return position; }
    public UUID getQuestionId() { return questionId; }
    public UUID getSkillId() { return skillId; }
    public QuestionVersionEntity.Difficulty getDifficulty() { return difficulty; }
    public QuestionVersionEntity.QuestionType getQuestionType() { return questionType; }
    public Integer getWeightBp() { return weightBp; }

    /** True when this slot names an exact question rather than a pool. */
    public boolean isPinned() {
        return questionId != null;
    }
}
