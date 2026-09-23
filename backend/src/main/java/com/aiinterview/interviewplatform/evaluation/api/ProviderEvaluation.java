package com.aiinterview.interviewplatform.evaluation.api;


import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What an {@link EvaluationProvider} returns: observations, not a score.
 *
 * <p>The provider answers narrow, closed questions about a text — "does this
 * answer assert the claim, and which words show it?" — and nothing here is
 * trusted until it has been through the validation gates. In particular
 * {@code evidenceQuote} is a claim about the answer that we verify, and
 * {@code modelReportedScore} enters no calculation whatsoever.
 *
 * <p>Note what the provider cannot express: it cannot set a weight, cannot name
 * a criterion outside the rubric it was given, and cannot decide whether a
 * follow-up is asked. Those are all backend decisions.
 */
public record ProviderEvaluation(

        List<CriterionJudgement> judgements,

        String summary,

        /** The provider's self-reported confidence in the whole reading, 0–1. */
        BigDecimal confidence,

        /** A signal for the interview engine's follow-up policy — never a command. */
        boolean followUpNeeded,

        String followUpReason,

        /** Must name a criterion in the rubric, or be null. */
        UUID followUpTargetCriterionId,

        /**
         * Retained only so divergence from the backend-derived score can be
         * monitored as a quality signal. Never used to score anything.
         */
        BigDecimal modelReportedScore,

        /** The provider believes the answer tried to manipulate it. */
        boolean injectionSuspected,

        boolean answerOffTopic,

        /** Provider identity, recorded for reproducibility. */
        ProviderMetadata metadata
) {

    public ProviderEvaluation {
        judgements = judgements == null ? List.of() : List.copyOf(judgements);
    }

    /**
     * One reading of one criterion.
     *
     * @param criterionId    must match a criterion in the request's rubric
     * @param evidenceQuote  claimed verbatim substring of the answer; verified,
     *                       and the verdict is downgraded if it does not hold
     */
    public record CriterionJudgement(UUID criterionId, Verdict verdict, BigDecimal confidence,
                                     String evidenceQuote, String comment) {

        public CriterionJudgement {
            if (criterionId == null) {
                throw new IllegalArgumentException("criterionId is required");
            }
            if (verdict == null) {
                throw new IllegalArgumentException("verdict is required");
            }
        }
    }

    /**
     * @param provider      e.g. {@code deterministic}, later {@code anthropic}
     * @param model         the exact pinned model id, never an alias
     * @param promptVersion the immutable prompt resource that produced this
     */
    public record ProviderMetadata(String provider, String model, String promptVersion,
                                   Integer inputTokens, Integer outputTokens,
                                   Long costMicros) {

        public ProviderMetadata {
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("provider is required");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model is required");
            }
            if (promptVersion == null || promptVersion.isBlank()) {
                throw new IllegalArgumentException("promptVersion is required");
            }
        }
    }
}
