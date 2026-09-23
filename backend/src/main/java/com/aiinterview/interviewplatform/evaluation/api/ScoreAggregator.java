package com.aiinterview.interviewplatform.evaluation.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Published access to the aggregation maths.
 *
 * <p>Added in M5A so a result can be assembled without the scoring formula
 * existing twice. The maths itself lives in {@code evaluation.domain} and stays
 * there; this is the door onto it, because a consumer reaching into that
 * package would breach the module boundary that keeps scoring in one place.
 *
 * <p>Deliberately phrased in neutral terms — weighted items and skill weights,
 * not turns and templates. The evaluation module must not learn what an
 * interview is, and an ArchUnit rule fails the build if it does. The caller
 * knows what its items are; this only knows how to average them.
 *
 * <p>Stateless and side-effect free: nothing here reads or writes a row. The
 * caller supplies what was graded and receives the aggregate.
 */
public interface ScoreAggregator {

    /**
     * @param items        every graded item, including zero-weight ones
     * @param skillWeights skill id to weight in basis points, from the pinned
     *                     template version — never a constant in this module
     */
    AggregateScore aggregate(List<ScoredItem> items, Map<UUID, Integer> skillWeights);

    /**
     * One graded, weighted item.
     *
     * @param score null means it could not be graded and must be excluded from
     *              the average entirely — distinct from a skipped answer, which
     *              is {@code 0.00} and counted. Our outage is not the
     *              candidate's fault; their decision not to answer is theirs
     */
    record ScoredItem(UUID itemId, UUID skillId, int weightBp, BigDecimal score) {
    }

    /**
     * @param score null when nothing could be graded at all — which is reported
     *              as "no result", never as zero
     */
    record SkillAggregate(UUID skillId, BigDecimal score, int skillWeightBp,
                          int coverageBp, int questionCount) {
    }

    /**
     * @param overallScore renormalised over covered skills only, so a skill that
     *                     could not be graded does not drag the total toward zero
     * @param coverageBp   how much of the intended weight was actually graded
     */
    record AggregateScore(BigDecimal overallScore, List<SkillAggregate> skills,
                          int coverageBp) {

        public AggregateScore {
            skills = skills == null ? List.of() : List.copyOf(skills);
        }

        public boolean isScoreable() {
            return overallScore != null;
        }
    }
}
