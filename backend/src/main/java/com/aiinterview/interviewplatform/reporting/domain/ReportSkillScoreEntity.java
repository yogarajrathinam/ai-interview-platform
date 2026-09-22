package com.aiinterview.interviewplatform.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-skill breakdown of one report, and the seed of the long-term candidate
 * skill profile.
 *
 * <p>The "skill profile over time" the product vision calls for is a
 * <em>query</em> over this table joined to {@code interviews} — no separate
 * denormalised profile table exists until that query is measurably slow.
 *
 * <p>{@code weightBp} snapshots the template's skill weight so the report's
 * arithmetic stays checkable independently. {@code coverageBp} records how
 * much of the skill's intended weight was actually graded, which is what lets
 * a report say "your SQL weighting was excluded" instead of quietly scoring
 * an ungraded skill as zero.
 *
 * <p>Immutable: {@code trg_rss_20_immutable} rejects every UPDATE.
 */
@Entity
@Table(name = "report_skill_scores", schema = "app")
public class ReportSkillScoreEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "report_id", nullable = false, updatable = false)
    private UUID reportId;

    @Column(name = "skill_id", nullable = false, updatable = false)
    private UUID skillId;

    @Column(name = "score", nullable = false, updatable = false)
    private BigDecimal score;

    @Column(name = "weight_bp", nullable = false, updatable = false)
    private Integer weightBp;

    @Column(name = "question_count", nullable = false, updatable = false)
    private Short questionCount;

    /** Graded weight over intended weight for this skill, in basis points. */
    @Column(name = "coverage_bp", nullable = false, updatable = false)
    private Integer coverageBp;

    protected ReportSkillScoreEntity() {
        // for JPA
    }

    public UUID getId() { return id; }
    public UUID getReportId() { return reportId; }
    public UUID getSkillId() { return skillId; }
    public BigDecimal getScore() { return score; }
    public Integer getWeightBp() { return weightBp; }
    public Short getQuestionCount() { return questionCount; }
    public Integer getCoverageBp() { return coverageBp; }
}
