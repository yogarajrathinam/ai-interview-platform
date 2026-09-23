package com.aiinterview.interviewplatform.evaluation.api;



import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A validated, scored evaluation — the provider's reading after our gates have
 * had their say.
 *
 * <p>Distinct from {@link ProviderEvaluation}: verdicts here may have been
 * downgraded, evidence spans are ours rather than the provider's claim, and
 * {@code derivedScore} was computed by {@code AnswerScorer}, not supplied.
 *
 * <p>Strengths and weaknesses are <em>derived</em> from the criterion outcomes
 * rather than stored: they are the highest-weighted met and unmet criteria, so
 * they can never drift from the verdicts that produced the score.
 */
public record EvaluationResult(

        EvaluationStatus status,

        /** Computed by the backend. Null only when the evaluation failed. */
        BigDecimal derivedScore,

        List<CriterionOutcome> criteria,

        String summary,

        BigDecimal confidence,

        boolean followUpNeeded,

        String followUpReason,

        UUID followUpTargetCriterionId,

        /** Divergence signal only. */
        BigDecimal modelReportedScore,

        boolean injectionSuspected,

        boolean answerOffTopic,

        ProviderEvaluation.ProviderMetadata metadata,

        String errorCode,

        String errorDetail
) {

    public EvaluationResult {
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
    }

    public boolean isSuccess() {
        return status != null && status.isSuccess();
    }

    /** Met criteria, heaviest first — what the candidate demonstrated. */
    public List<CriterionOutcome> strengths() {
        return criteria.stream()
                .filter(c -> c.verdict() == Verdict.MET)
                .sorted((a, b) -> Integer.compare(b.weightBp(), a.weightBp()))
                .toList();
    }

    /** Unmet criteria, heaviest first — what to study next. */
    public List<CriterionOutcome> weaknesses() {
        return criteria.stream()
                .filter(c -> c.verdict() != Verdict.MET)
                .sorted((a, b) -> Integer.compare(b.weightBp(), a.weightBp()))
                .toList();
    }

    /** Concepts the answer never addressed, as opposed to got wrong. */
    public List<CriterionOutcome> missingConcepts() {
        return criteria.stream().filter(c -> c.verdict() == Verdict.MISSING).toList();
    }

    /**
     * One criterion after validation.
     *
     * @param credit           snapshot of the credit applied, so the arithmetic
     *                         stays reproducible even if the policy changes
     * @param evidence         null when none survived validation
     * @param evidenceRejected the provider claimed a quote that was not in the
     *                         answer, and the verdict was downgraded as a result
     */
    public record CriterionOutcome(UUID criterionId, String code, String label,
                                   Verdict verdict, BigDecimal credit, int weightBp,
                                   BigDecimal confidence, EvidenceSpan evidence,
                                   boolean evidenceRejected, String comment) {

        public boolean hasEvidence() {
            return evidence != null;
        }
    }

    /**
     * A verbatim span of the candidate's answer.
     *
     * <p>Offsets are computed by us against the exact text the evaluation
     * consumed — never taken from the provider, which has no reliable notion of
     * character positions. Start and end are always both present or both
     * absent, mirroring the {@code ck_ecr_span} constraint.
     */
    public record EvidenceSpan(String quote, int start, int end) {

        public EvidenceSpan {
            if (quote == null || quote.isBlank()) {
                throw new IllegalArgumentException("evidence quote is required");
            }
            if (start < 0) {
                throw new IllegalArgumentException("evidence start cannot be negative");
            }
            if (end <= start) {
                throw new IllegalArgumentException(
                        "evidence end (%d) must be after start (%d)".formatted(end, start));
            }
        }

        public int length() {
            return end - start;
        }
    }
}
