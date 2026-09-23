package com.aiinterview.interviewplatform.evaluation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregates question scores into skill scores and an overall interview score.
 *
 * <p>Pure, and deliberately weight-agnostic: every weight arrives as data read
 * from the pinned template version. Nothing about any particular skill is
 * encoded here, so adding React or System Design is a row, never a deploy.
 *
 * <pre>
 *   skillScore   = SUM(qWeight x qScore) / SUM(qWeight)      over graded questions of that skill
 *   overallScore = SUM(skillWeight x skillScore) / SUM(skillWeight)  over covered skills
 * </pre>
 *
 * <p>Two exclusion rules carry real meaning and are not symmetric:
 * <ul>
 *   <li>A <strong>skipped</strong> answer scores 0 and <em>is</em> counted. The
 *       candidate chose not to answer, and hiding that would corrupt
 *       comparability.</li>
 *   <li>An answer we <strong>failed to grade</strong> is excluded from both
 *       numerator and denominator, and reported as reduced coverage. Our
 *       outage is not the candidate's fault.</li>
 * </ul>
 *
 * <p>Overall score renormalises over covered skills only, so a skill that could
 * not be graded at all does not silently drag the total toward zero.
 *
 * <p>This lives in the evaluation module rather than reporting so the scoring
 * maths exists exactly once. Reporting (M5) consumes it to build and persist a
 * report; it does not reimplement it.
 */
public final class InterviewScorer {

    private InterviewScorer() {
    }

    /**
     * One graded turn.
     *
     * @param score {@code null} means the turn could not be graded and must be
     *              excluded from the average — distinct from a skipped answer,
     *              which is {@code 0.00} and counted.
     */
    public record ScoredQuestion(UUID interviewQuestionId, UUID skillId, int weightBp,
                                 BigDecimal score) {

        public ScoredQuestion {
            if (skillId == null) {
                throw new IllegalArgumentException("skillId is required");
            }
            if (weightBp < 0) {
                throw new IllegalArgumentException("question weight cannot be negative");
            }
        }

        /**
         * Follow-up turns carry zero weight by construction
         * ({@code ck_iq_shape}), so they influence no average. They re-probe a
         * criterion the candidate under-answered; giving them independent weight
         * would let a weak topic dominate purely because it was asked twice.
         */
        public boolean carriesWeight() {
            return weightBp > 0;
        }

        public boolean wasGraded() {
            return score != null;
        }
    }

    /** Per-skill result, including how much of its intended weight was graded. */
    public record SkillScore(UUID skillId, BigDecimal score, int skillWeightBp,
                             int gradedWeightBp, int intendedWeightBp, int questionCount) {

        /** Graded weight over intended weight, in basis points. */
        public int coverageBp() {
            if (intendedWeightBp == 0) {
                return 0;
            }
            return BigDecimal.valueOf(gradedWeightBp)
                    .multiply(BigDecimal.valueOf(ScoringPolicy.FULL_WEIGHT_BP))
                    .divide(BigDecimal.valueOf(intendedWeightBp), 0, RoundingMode.HALF_UP)
                    .intValue();
        }

        public boolean isCovered() {
            return gradedWeightBp > 0;
        }
    }

    /** The whole interview. {@code overallScore} is null when nothing was graded. */
    public record InterviewScore(BigDecimal overallScore, List<SkillScore> skillScores,
                                 int gradedWeightBp, int intendedWeightBp) {

        public boolean isScoreable() {
            return overallScore != null;
        }

        public int coverageBp() {
            if (intendedWeightBp == 0) {
                return 0;
            }
            return BigDecimal.valueOf(gradedWeightBp)
                    .multiply(BigDecimal.valueOf(ScoringPolicy.FULL_WEIGHT_BP))
                    .divide(BigDecimal.valueOf(intendedWeightBp), 0, RoundingMode.HALF_UP)
                    .intValue();
        }
    }

    /**
     * @param questions    every turn in the attempt, including zero-weight follow-ups
     * @param skillWeights skill id to weight in basis points, from the pinned
     *                     template version
     */
    public static InterviewScore score(List<ScoredQuestion> questions,
                                       Map<UUID, Integer> skillWeights) {
        Map<UUID, Accumulator> bySkill = new LinkedHashMap<>();
        for (ScoredQuestion question : questions == null ? List.<ScoredQuestion>of() : questions) {
            if (!question.carriesWeight()) {
                continue;
            }
            bySkill.computeIfAbsent(question.skillId(), id -> new Accumulator()).add(question);
        }

        List<SkillScore> skillScores = new ArrayList<>();
        BigDecimal weightedSkillTotal = BigDecimal.ZERO;
        BigDecimal coveredSkillWeight = BigDecimal.ZERO;
        int gradedWeightBp = 0;
        int intendedWeightBp = 0;

        for (Map.Entry<UUID, Accumulator> entry : bySkill.entrySet()) {
            UUID skillId = entry.getKey();
            Accumulator acc = entry.getValue();
            int skillWeightBp = skillWeights == null
                    ? 0 : skillWeights.getOrDefault(skillId, 0);

            BigDecimal skillScore = acc.average();
            skillScores.add(new SkillScore(skillId, skillScore, skillWeightBp,
                    acc.gradedWeight, acc.intendedWeight, acc.questionCount));

            gradedWeightBp += acc.gradedWeight;
            intendedWeightBp += acc.intendedWeight;

            if (skillScore != null && skillWeightBp > 0) {
                BigDecimal weight = BigDecimal.valueOf(skillWeightBp);
                weightedSkillTotal = weightedSkillTotal.add(skillScore.multiply(weight));
                coveredSkillWeight = coveredSkillWeight.add(weight);
            }
        }

        skillScores.sort(Comparator.comparing(s -> s.skillId().toString()));

        BigDecimal overall = coveredSkillWeight.signum() == 0
                ? null
                : ScoringPolicy.clampAndRound(
                        weightedSkillTotal.divide(coveredSkillWeight, 10, RoundingMode.HALF_UP));

        return new InterviewScore(overall, List.copyOf(skillScores),
                gradedWeightBp, intendedWeightBp);
    }

    private static final class Accumulator {
        private BigDecimal weightedScore = BigDecimal.ZERO;
        private int gradedWeight;
        private int intendedWeight;
        private int questionCount;

        void add(ScoredQuestion question) {
            intendedWeight += question.weightBp();
            questionCount++;
            if (question.wasGraded()) {
                gradedWeight += question.weightBp();
                weightedScore = weightedScore.add(
                        question.score().multiply(BigDecimal.valueOf(question.weightBp())));
            }
        }

        /** {@code null} when nothing in this skill could be graded. */
        BigDecimal average() {
            if (gradedWeight == 0) {
                return null;
            }
            return ScoringPolicy.clampAndRound(weightedScore.divide(
                    BigDecimal.valueOf(gradedWeight), 10, RoundingMode.HALF_UP));
        }
    }
}
