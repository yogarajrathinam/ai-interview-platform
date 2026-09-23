package com.aiinterview.interviewplatform.evaluation.domain;

import com.aiinterview.interviewplatform.evaluation.api.Verdict;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Turns criterion judgements into a question score.
 *
 * <p>A pure function with no Spring, no database and no provider: the same
 * criterion rows always produce the same number, which is the whole point of
 * taking scoring away from the model.
 *
 * <pre>
 *   answerScore = 10 x clamp( SUM(weight_i x credit_i) / SUM(weight_i), 0, 1 )
 * </pre>
 *
 * <p>The clamp at zero is what stops a single {@code CONTRADICTED} criterion
 * from producing a negative question score while still costing more than
 * silence.
 */
public final class AnswerScorer {

    private AnswerScorer() {
    }

    /** One criterion's contribution: its weight and the credit it earned. */
    public record ScoredCriterion(UUID criterionId, Verdict verdict, BigDecimal credit, int weightBp) {

        public ScoredCriterion {
            if (credit == null) {
                throw new IllegalArgumentException("credit is required");
            }
            if (weightBp <= 0) {
                throw new IllegalArgumentException(
                        "criterion weight must be positive, got " + weightBp);
            }
        }

        BigDecimal weighted() {
            return credit.multiply(BigDecimal.valueOf(weightBp));
        }
    }

    /**
     * Scores one answer. Returns 0 when the rubric carries no weight at all,
     * which cannot happen for published content but must not divide by zero.
     */
    public static BigDecimal score(List<ScoredCriterion> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return ScoringPolicy.roundScore(BigDecimal.ZERO);
        }
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal earned = BigDecimal.ZERO;
        for (ScoredCriterion criterion : criteria) {
            totalWeight = totalWeight.add(BigDecimal.valueOf(criterion.weightBp()));
            earned = earned.add(criterion.weighted());
        }
        if (totalWeight.signum() == 0) {
            return ScoringPolicy.roundScore(BigDecimal.ZERO);
        }
        BigDecimal ratio = earned.divide(totalWeight, 10, RoundingMode.HALF_UP);
        return ScoringPolicy.clampAndRound(ratio.multiply(ScoringPolicy.MAX_SCORE));
    }

    /** True when the criterion weights form a complete rubric (10000 bp). */
    public static boolean isCompleteRubric(List<ScoredCriterion> criteria) {
        return criteria != null
                && criteria.stream().mapToInt(ScoredCriterion::weightBp).sum()
                        == ScoringPolicy.FULL_WEIGHT_BP;
    }
}
