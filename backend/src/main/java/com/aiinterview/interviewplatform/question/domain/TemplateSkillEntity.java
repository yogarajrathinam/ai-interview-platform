package com.aiinterview.interviewplatform.question.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The many-to-many join between a template version and its skills, carrying
 * the weight that gives the overall score its meaning.
 *
 * <p>A natural composite key — there is nothing to identify here beyond the
 * pair, so a surrogate id would be noise. Weights must sum to 10000 basis
 * points before the template can be published.
 */
@Entity
@Table(name = "template_skills", schema = "app")
@IdClass(TemplateSkillEntity.TemplateSkillId.class)
public class TemplateSkillEntity {

    @Id
    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Id
    @Column(name = "skill_id", nullable = false, updatable = false)
    private UUID skillId;

    @Column(name = "weight_bp", nullable = false)
    private Integer weightBp;

    protected TemplateSkillEntity() {
        // for JPA
    }

    public UUID getTemplateId() { return templateId; }
    public UUID getSkillId() { return skillId; }
    public Integer getWeightBp() { return weightBp; }

    /** Composite identifier. Field names must match the entity's @Id fields. */
    public static class TemplateSkillId implements Serializable {

        private UUID templateId;
        private UUID skillId;

        public TemplateSkillId() {
        }

        public TemplateSkillId(UUID templateId, UUID skillId) {
            this.templateId = templateId;
            this.skillId = skillId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TemplateSkillId that)) {
                return false;
            }
            return Objects.equals(templateId, that.templateId)
                    && Objects.equals(skillId, that.skillId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(templateId, skillId);
        }
    }
}
