package com.aiinterview.interviewplatform.evaluation.application;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationRequest;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationResult.CriterionOutcome;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationResult.EvidenceSpan;
import com.aiinterview.interviewplatform.evaluation.api.ProviderEvaluation;
import com.aiinterview.interviewplatform.evaluation.api.Verdict;
import com.aiinterview.interviewplatform.evaluation.domain.ScoringPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Decides how much of a provider's reading to believe.
 *
 * <p>Nothing a provider returns is trusted until it has been through here. The
 * gates are ordered from structural to semantic, and they fail differently on
 * purpose: a malformed criterion set invalidates the whole evaluation, while
 * unsupported evidence merely costs that one criterion its credit.
 *
 * <p>That asymmetry matters. Rejecting an entire evaluation because one quote
 * was sloppy would lose good signal; accepting a criterion the provider could
 * not evidence would manufacture a score out of nothing.
 */
@Component
public class ProviderResultValidator {

    /** Below this, an answer is too short to have met anything substantive. */
    static final int MINIMUM_SUBSTANTIVE_ANSWER_LENGTH = 20;

    /** Divergence beyond this between our score and the model's is worth seeing. */
    static final BigDecimal DIVERGENCE_THRESHOLD = new BigDecimal("3.0");

    private final EvidenceValidator evidenceValidator;

    public ProviderResultValidator(EvidenceValidator evidenceValidator) {
        this.evidenceValidator = evidenceValidator;
    }

    /**
     * @param criteria one outcome per rubric criterion, in rubric order, when valid
     */
    public record ValidationOutcome(boolean valid, String errorCode, String errorDetail,
                                    List<CriterionOutcome> criteria) {

        static ValidationOutcome rejected(String code, String detail) {
            return new ValidationOutcome(false, code, detail, List.of());
        }

        static ValidationOutcome accepted(List<CriterionOutcome> criteria) {
            return new ValidationOutcome(true, null, null, List.copyOf(criteria));
        }
    }

    public ValidationOutcome validate(EvaluationRequest request, ProviderEvaluation evaluation) {
        if (evaluation == null) {
            return ValidationOutcome.rejected("EMPTY_RESULT", "Provider returned no result.");
        }

        Map<UUID, EvaluationRequest.RubricCriterion> rubric = new LinkedHashMap<>();
        for (EvaluationRequest.RubricCriterion criterion : request.rubric()) {
            rubric.put(criterion.id(), criterion);
        }

        // --- Gate: the criterion set must match the rubric exactly -----------
        // A provider that invents, drops or repeats a criterion has not done the
        // task it was given, and its other judgements cannot be relied on either.
        Map<UUID, ProviderEvaluation.CriterionJudgement> judgements = new LinkedHashMap<>();
        Set<UUID> duplicates = new HashSet<>();
        for (ProviderEvaluation.CriterionJudgement judgement : evaluation.judgements()) {
            if (judgements.putIfAbsent(judgement.criterionId(), judgement) != null) {
                duplicates.add(judgement.criterionId());
            }
        }
        if (!duplicates.isEmpty()) {
            return ValidationOutcome.rejected("DUPLICATE_CRITERIA",
                    "Provider judged %d criterion/criteria more than once.".formatted(
                            duplicates.size()));
        }
        Set<UUID> unknown = new HashSet<>(judgements.keySet());
        unknown.removeAll(rubric.keySet());
        if (!unknown.isEmpty()) {
            return ValidationOutcome.rejected("UNKNOWN_CRITERIA",
                    "Provider returned %d criterion id(s) not present in the rubric.".formatted(
                            unknown.size()));
        }
        Set<UUID> missing = new HashSet<>(rubric.keySet());
        missing.removeAll(judgements.keySet());
        if (!missing.isEmpty()) {
            return ValidationOutcome.rejected("INCOMPLETE_CRITERIA",
                    "Provider did not judge %d of %d rubric criteria.".formatted(
                            missing.size(), rubric.size()));
        }

        // --- Gate: a blank answer cannot have met anything -------------------
        // Cheaper and more certain than asking a model, and it closes the most
        // embarrassing possible failure: a score awarded for an empty box.
        boolean blankAnswer = request.answer().isBlank();
        String answerText = request.answer().text();

        List<CriterionOutcome> outcomes = new ArrayList<>(rubric.size());
        for (EvaluationRequest.RubricCriterion criterion : request.rubric()) {
            ProviderEvaluation.CriterionJudgement judgement = judgements.get(criterion.id());
            outcomes.add(blankAnswer
                    ? blankOutcome(criterion)
                    : validateCriterion(criterion, judgement, answerText));
        }

        return ValidationOutcome.accepted(outcomes);
    }

    private CriterionOutcome blankOutcome(EvaluationRequest.RubricCriterion criterion) {
        return new CriterionOutcome(criterion.id(), criterion.code(), criterion.label(),
                Verdict.MISSING, ScoringPolicy.creditFor(Verdict.MISSING, null),
                criterion.weightBp(), null, null, false, "No answer was provided.");
    }

    private CriterionOutcome validateCriterion(EvaluationRequest.RubricCriterion criterion,
                                               ProviderEvaluation.CriterionJudgement judgement,
                                               String answerText) {
        BigDecimal confidence = clampConfidence(judgement.confidence());
        Verdict verdict = judgement.verdict();
        EvidenceSpan evidence = null;
        boolean evidenceRejected = false;

        if (verdict.requiresEvidence()) {
            Optional<EvidenceSpan> located =
                    evidenceValidator.locate(answerText, judgement.evidenceQuote());
            if (located.isPresent()) {
                evidence = located.get();
            } else {
                // The claim is unsupported: downgrade rather than discard, and
                // record why, so the rate of this can be monitored.
                verdict = verdict.downgraded();
                evidenceRejected = true;
            }
        }

        // A short answer that supposedly met a criterion still had to be quoted,
        // and the gate above already enforced that. This only guards the case of
        // a criterion needing no evidence at all.
        if (verdict == Verdict.MET && !evidenceRejected && evidence == null
                && answerText.strip().length() < MINIMUM_SUBSTANTIVE_ANSWER_LENGTH) {
            verdict = Verdict.PARTIAL;
            evidenceRejected = true;
        }

        BigDecimal credit = ScoringPolicy.creditFor(verdict, confidence);

        return new CriterionOutcome(criterion.id(), criterion.code(), criterion.label(),
                verdict, credit, criterion.weightBp(), confidence, evidence,
                evidenceRejected, judgement.comment());
    }

    /** Out-of-range confidence is clamped, not trusted and not fatal. */
    private BigDecimal clampConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return null;
        }
        return confidence.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    /**
     * A model score wildly different from ours is not an error — our number
     * still stands — but it is a quality signal worth surfacing.
     */
    public boolean divergesFromModel(BigDecimal derivedScore, BigDecimal modelReportedScore) {
        if (derivedScore == null || modelReportedScore == null) {
            return false;
        }
        return derivedScore.subtract(modelReportedScore).abs().compareTo(DIVERGENCE_THRESHOLD) > 0;
    }

    /** Only a score inside the reportable range is retained as a signal. */
    public BigDecimal sanitiseModelScore(BigDecimal modelReportedScore) {
        if (modelReportedScore == null) {
            return null;
        }
        if (modelReportedScore.compareTo(ScoringPolicy.MIN_SCORE) < 0
                || modelReportedScore.compareTo(ScoringPolicy.MAX_SCORE) > 0) {
            return null;
        }
        return ScoringPolicy.roundScore(modelReportedScore);
    }
}
