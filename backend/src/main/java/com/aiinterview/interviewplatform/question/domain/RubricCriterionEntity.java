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
 * One rubric criterion: an <em>observable claim</em> an answer either makes,
 * partially makes, omits, or contradicts — never a topic label.
 *
 * <p>Versioned with its question version, because a rubric change <em>is</em>
 * a content change. Writable only while the parent version is DRAFT
 * ({@code trg_rc_20_guard}); weights must sum to 10000 bp to publish.
 *
 * <p>{@code followUpPrompt} absorbed the {@code follow_up_prompts} table
 * removed in review: at most one curated probe per criterion, authored in the
 * same form as the criterion itself.
 */
@Entity
@Table(name = "rubric_criteria", schema = "app")
public class RubricCriterionEntity {

    /** CORE = must have, DEPTH = senior signal, BONUS = nice to have. */
    public enum Tier { CORE, DEPTH, BONUS }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "question_version_id", nullable = false, updatable = false)
    private UUID questionVersionId;

    /** Stable key within the version, e.g. {@code THREAD_SAFETY}. */
    @Column(name = "code", nullable = false)
    private String code;

    /** Candidate-facing phrasing, shown in the report. */
    @Column(name = "label", nullable = false)
    private String label;

    /** The observable claim, written for the grader. */
    @Column(name = "expectation", nullable = false)
    private String expectation;

    /** Share of the question score, in basis points. All criteria sum to 10000. */
    @Column(name = "weight_bp", nullable = false)
    private Integer weightBp;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false)
    private Tier tier;

    /** Curated follow-up probe used when this criterion scores weakly. */
    @Column(name = "follow_up_prompt")
    private String followUpPrompt;

    @Column(name = "sort_order", nullable = false)
    private Short sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RubricCriterionEntity() {
        // for JPA
    }

    public UUID getId() { return id; }
    public UUID getQuestionVersionId() { return questionVersionId; }
    public String getCode() { return code; }
    public String getLabel() { return label; }
    public String getExpectation() { return expectation; }
    public Integer getWeightBp() { return weightBp; }
    public Tier getTier() { return tier; }
    public String getFollowUpPrompt() { return followUpPrompt; }
    public Short getSortOrder() { return sortOrder; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
