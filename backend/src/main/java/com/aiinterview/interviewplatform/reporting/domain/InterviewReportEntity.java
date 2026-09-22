package com.aiinterview.interviewplatform.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The candidate-facing aggregate for one attempt.
 *
 * <p>Append-only with a current marker, so regenerating a report after a
 * re-evaluation never destroys the one the candidate already read.
 *
 * <p><strong>No row exists when coverage is zero.</strong> A COMPLETED
 * interview with no current report is exactly the NOT_SCORED case — the
 * alternative, a fabricated 0.0, would be a lie.
 *
 * <p>The coverage columns are the honesty mechanism: an answer that could not
 * be evaluated is excluded from the weighted average and reported as reduced
 * coverage rather than silently scored zero.
 *
 * <p>The three JSON columns are the one place JSON genuinely earns its place:
 * pure display output, derived from relational rows, regenerable at any time,
 * and never queried into. Every underlying fact stays relational.
 */
@Entity
@Table(name = "interview_reports", schema = "app")
public class InterviewReportEntity {

    public enum Band { NEEDS_WORK, DEVELOPING, SOLID, STRONG }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "interview_id", nullable = false, updatable = false)
    private UUID interviewId;

    /** The only mutable column on this table. */
    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Column(name = "overall_score", nullable = false, updatable = false)
    private BigDecimal overallScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "band", nullable = false, updatable = false)
    private Band band;

    /** Weight actually graded, versus the weight the template intended. */
    @Column(name = "evaluated_weight_bp", nullable = false, updatable = false)
    private Integer evaluatedWeightBp;

    @Column(name = "total_weight_bp", nullable = false, updatable = false)
    private Integer totalWeightBp;

    @Column(name = "questions_answered", nullable = false, updatable = false)
    private Short questionsAnswered;

    @Column(name = "questions_skipped", nullable = false, updatable = false)
    private Short questionsSkipped;

    @Column(name = "questions_failed", nullable = false, updatable = false)
    private Short questionsFailed;

    @Column(name = "duration_sec", updatable = false)
    private Integer durationSec;

    @Column(name = "summary", nullable = false, updatable = false)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strengths", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String strengths;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "improvements", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String improvements;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "study_recommendations", nullable = false,
            columnDefinition = "jsonb", updatable = false)
    private String studyRecommendations;

    @Column(name = "reporting_version", nullable = false, updatable = false)
    private String reportingVersion;

    /** Null when generated automatically; set for an admin regeneration. */
    @Column(name = "generated_by", updatable = false)
    private UUID generatedBy;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private OffsetDateTime generatedAt;

    protected InterviewReportEntity() {
        // for JPA
    }

    public UUID getId() { return id; }
    public UUID getInterviewId() { return interviewId; }
    public boolean isCurrent() { return current; }
    public BigDecimal getOverallScore() { return overallScore; }
    public Band getBand() { return band; }
    public Integer getEvaluatedWeightBp() { return evaluatedWeightBp; }
    public Integer getTotalWeightBp() { return totalWeightBp; }
    public Short getQuestionsAnswered() { return questionsAnswered; }
    public Short getQuestionsSkipped() { return questionsSkipped; }
    public Short getQuestionsFailed() { return questionsFailed; }
    public Integer getDurationSec() { return durationSec; }
    public String getSummary() { return summary; }
    public String getStrengths() { return strengths; }
    public String getImprovements() { return improvements; }
    public String getStudyRecommendations() { return studyRecommendations; }
    public String getReportingVersion() { return reportingVersion; }
    public UUID getGeneratedBy() { return generatedBy; }
    public OffsetDateTime getGeneratedAt() { return generatedAt; }
}
