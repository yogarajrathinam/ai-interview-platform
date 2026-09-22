package com.aiinterview.interviewplatform.evaluation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One terminal grading outcome for one answer.
 *
 * <p>Append-only: a re-evaluation inserts a new row and flips
 * {@code isCurrent}; the previous row keeps its data
 * ({@code trg_eval_20_append_only} permits no other change). Re-evaluation
 * must never destroy the result a candidate already saw.
 *
 * <p>Rows are written <strong>only on a terminal outcome</strong>. Interim
 * retry failures live in {@code ai_invocations} and {@code jobs.last_error};
 * writing one per attempt would collide with the partial unique index on
 * {@code (answer_id) WHERE is_current}.
 *
 * <p>{@code derivedScore} is computed by the backend from the criterion
 * results. {@code modelReportedScore} is the model's own holistic number and
 * <strong>enters no calculation</strong> — it is retained purely so
 * divergence can be monitored as a quality signal.
 *
 * <p>The provenance quartet ({@code evaluationVersion}, {@code promptVersion},
 * {@code rubricVersion}, {@code questionVersionId}) plus {@code provider} and
 * {@code model} make every score explainable and reproducible years later.
 */
@Entity
@Table(name = "evaluations", schema = "app")
public class EvaluationEntity {

    public enum Status { SUCCEEDED, FAILED_VALIDATION, FAILED_PROVIDER }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "answer_id", nullable = false, updatable = false)
    private UUID answerId;

    /** The only mutable column on this table. */
    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false)
    private Status status;

    /** Computed by the backend. Null only when the evaluation failed. */
    @Column(name = "derived_score", updatable = false)
    private BigDecimal derivedScore;

    /** Divergence signal only. Never used in any calculation. */
    @Column(name = "model_reported_score", updatable = false)
    private BigDecimal modelReportedScore;

    @Column(name = "confidence", updatable = false)
    private BigDecimal confidence;

    @Column(name = "summary", updatable = false)
    private String summary;

    @Column(name = "follow_up_needed", nullable = false, updatable = false)
    private boolean followUpNeeded;

    @Column(name = "follow_up_reason", updatable = false)
    private String followUpReason;

    @Column(name = "follow_up_target_criterion_id", updatable = false)
    private UUID followUpTargetCriterionId;

    @Column(name = "injection_suspected", nullable = false, updatable = false)
    private boolean injectionSuspected;

    @Column(name = "answer_off_topic", nullable = false, updatable = false)
    private boolean answerOffTopic;

    // ------------------------------------------- reproducibility provenance

    /** Identifies the credit table and aggregation algorithm that ran. */
    @Column(name = "evaluation_version", nullable = false, updatable = false)
    private String evaluationVersion;

    @Column(name = "prompt_version", nullable = false, updatable = false)
    private String promptVersion;

    /** The {@code question_versions.version} whose rubric was applied. */
    @Column(name = "rubric_version", nullable = false, updatable = false)
    private Integer rubricVersion;

    @Column(name = "question_version_id", nullable = false, updatable = false)
    private UUID questionVersionId;

    /** Copied from the invocation so provenance survives the raw payload purge. */
    @Column(name = "provider", updatable = false)
    private String provider;

    @Column(name = "model", updatable = false)
    private String model;

    @Column(name = "ai_invocation_id", updatable = false)
    private UUID aiInvocationId;

    /** Null when the evaluation ran automatically; set for an admin re-run. */
    @Column(name = "triggered_by", updatable = false)
    private UUID triggeredBy;

    @Column(name = "error_code", updatable = false)
    private String errorCode;

    @Column(name = "error_detail", updatable = false)
    private String errorDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected EvaluationEntity() {
        // for JPA
    }

    public UUID getId() { return id; }
    public UUID getAnswerId() { return answerId; }
    public boolean isCurrent() { return current; }
    public Status getStatus() { return status; }
    public BigDecimal getDerivedScore() { return derivedScore; }
    public BigDecimal getModelReportedScore() { return modelReportedScore; }
    public BigDecimal getConfidence() { return confidence; }
    public String getSummary() { return summary; }
    public boolean isFollowUpNeeded() { return followUpNeeded; }
    public String getFollowUpReason() { return followUpReason; }
    public UUID getFollowUpTargetCriterionId() { return followUpTargetCriterionId; }
    public boolean isInjectionSuspected() { return injectionSuspected; }
    public boolean isAnswerOffTopic() { return answerOffTopic; }
    public String getEvaluationVersion() { return evaluationVersion; }
    public String getPromptVersion() { return promptVersion; }
    public Integer getRubricVersion() { return rubricVersion; }
    public UUID getQuestionVersionId() { return questionVersionId; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public UUID getAiInvocationId() { return aiInvocationId; }
    public UUID getTriggeredBy() { return triggeredBy; }
    public String getErrorCode() { return errorCode; }
    public String getErrorDetail() { return errorDetail; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
