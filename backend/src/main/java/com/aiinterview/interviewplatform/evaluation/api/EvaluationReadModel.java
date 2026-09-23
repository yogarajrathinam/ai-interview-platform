package com.aiinterview.interviewplatform.evaluation.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to stored evaluations, for modules that need to act on them.
 *
 * <p>Added in M3. M2 noted that follow-up selection needs the parent/child turn
 * graph the interview module owns; the missing half was this — the interview
 * module knowing <em>which criterion</em> an answer covered weakly.
 *
 * <p>Exposed as a read model rather than by letting anyone query
 * {@code evaluation_criterion_results} directly, so the dependency keeps
 * pointing from interview to evaluation and never back.
 *
 * <p>Note what is not here: no way to write a verdict, no way to change a
 * score. A consumer can read what grading concluded and nothing more.
 */
public interface EvaluationReadModel {

    /** The evaluation a candidate would see, if grading has produced one. */
    Optional<AnswerEvaluationView> findCurrentForAnswer(UUID answerId);

    /**
     * The current evaluations for several answers, in one round trip.
     *
     * <p>Answers with no evaluation are simply absent — the caller is asking
     * what exists, not asserting that anything should.
     */
    List<AnswerEvaluationView> findCurrentForAnswers(List<UUID> answerIds);

    /**
     * What grading concluded about one answer.
     *
     * @param followUpNeeded      a signal from the grader, never a command; the
     *                            interview module decides whether to act on it
     * @param injectionSuspected  the answer tried to talk to the grader — a
     *                            reason to suppress follow-ups rather than probe
     */
    record AnswerEvaluationView(UUID evaluationId, UUID answerId, EvaluationStatus status,
                                BigDecimal derivedScore, BigDecimal confidence,
                                boolean followUpNeeded, UUID followUpTargetCriterionId,
                                boolean injectionSuspected, boolean answerOffTopic,
                                UUID questionVersionId, List<CriterionView> criteria) {

        public AnswerEvaluationView {
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
        }

        public boolean isSuccess() {
            return status != null && status.isSuccess();
        }
    }

    /**
     * One criterion outcome.
     *
     * @param credit           the credit actually applied, snapshotted at grading time
     * @param weightBp         the criterion's share of the question
     * @param evidenceQuote    the verbatim span of the candidate's own answer that
     *                         justified the verdict, or null when none survived
     *                         validation. Added in M5A: a result screen that shows a
     *                         verdict without the words behind it is exactly the
     *                         unaccountable score this product exists to replace
     * @param evidenceRejected the grader claimed a quote that was not in the answer
     *                         and the verdict was downgraded. Surfaced so a reviewer
     *                         can tell a weak answer from a bad grade
     */
    record CriterionView(UUID criterionId, String code, Verdict verdict, BigDecimal credit,
                         int weightBp, BigDecimal confidence, String evidenceQuote,
                         boolean evidenceRejected) {

        /**
         * How much of this question's weight the answer left on the table.
         *
         * <p>The ranking key for follow-up selection: a heavy criterion missed
         * entirely is worth probing before a light one answered partially.
         */
        public BigDecimal unearnedWeight() {
            BigDecimal earned = credit == null ? BigDecimal.ZERO : credit;
            return BigDecimal.valueOf(weightBp)
                    .multiply(BigDecimal.ONE.subtract(earned))
                    .max(BigDecimal.ZERO);
        }

        public boolean isWeak() {
            return verdict == Verdict.PARTIAL || verdict == Verdict.MISSING;
        }
    }
}
