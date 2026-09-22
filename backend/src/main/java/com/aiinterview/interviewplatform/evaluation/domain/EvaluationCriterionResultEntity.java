package com.aiinterview.interviewplatform.evaluation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One criterion judgement with its supporting evidence.
 *
 * <p>This is the table that makes the product an assessment instrument rather
 * than a chatbot, and it is relational for concrete reasons: the whole score
 * recomputes in one {@code GROUP BY} with no AI call, the criterion reference
 * has referential integrity, and "which concept do candidates miss most?" is a
 * single query. A {@code jsonb} blob here would have been the most damaging
 * shortcut available in the schema.
 *
 * <p>{@code weightBp} and {@code credit} are snapshots taken at evaluation
 * time, so the arithmetic behind a historical report is reproducible from this
 * row alone.
 *
 * <p>Evidence offsets are computed by <em>us</em> against the answer text, not
 * taken from the model. A quote that is not a verbatim substring fails gate
 * G4, the verdict is downgraded, and {@code evidenceRejected} records it —
 * hallucinated credit is the most damaging failure this system could produce.
 *
 * <p>Immutable: {@code trg_ecr_20_immutable} rejects every UPDATE.
 */
@Entity
@Table(name = "evaluation_criterion_results", schema = "app")
public class EvaluationCriterionResultEntity {

    /**
     * CONTRADICTED is deliberately distinct from MISSING: asserting something
     * false is worse than omitting it, and the two deserve different credit
     * and very different feedback.
     */
    public enum Verdict { MET, PARTIAL, MISSING, CONTRADICTED }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "evaluation_id", nullable = false, updatable = false)
    private UUID evaluationId;

    @Column(name = "rubric_criterion_id", nullable = false, updatable = false)
    private UUID rubricCriterionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, updatable = false)
    private Verdict verdict;

    /** Snapshot of the credit table: MET 1.0, PARTIAL 0.5, MISSING 0, CONTRADICTED -0.25. */
    @Column(name = "credit", nullable = false, updatable = false)
    private BigDecimal credit;

    /** Snapshot of {@code rubric_criteria.weight_bp}. */
    @Column(name = "weight_bp", nullable = false, updatable = false)
    private Integer weightBp;

    @Column(name = "confidence", updatable = false)
    private BigDecimal confidence;

    /** Verbatim substring of the candidate's answer. */
    @Column(name = "evidence_quote", updatable = false)
    private String evidenceQuote;

    @Column(name = "evidence_start", updatable = false)
    private Integer evidenceStart;

    @Column(name = "evidence_end", updatable = false)
    private Integer evidenceEnd;

    /** True when gate G4 rejected the model's quote and downgraded the verdict. */
    @Column(name = "evidence_rejected", nullable = false, updatable = false)
    private boolean evidenceRejected;

    @Column(name = "comment", updatable = false)
    private String comment;

    protected EvaluationCriterionResultEntity() {
        // for JPA
    }

    public UUID getId() { return id; }
    public UUID getEvaluationId() { return evaluationId; }
    public UUID getRubricCriterionId() { return rubricCriterionId; }
    public Verdict getVerdict() { return verdict; }
    public BigDecimal getCredit() { return credit; }
    public Integer getWeightBp() { return weightBp; }
    public BigDecimal getConfidence() { return confidence; }
    public String getEvidenceQuote() { return evidenceQuote; }
    public Integer getEvidenceStart() { return evidenceStart; }
    public Integer getEvidenceEnd() { return evidenceEnd; }
    public boolean isEvidenceRejected() { return evidenceRejected; }
    public String getComment() { return comment; }
}
