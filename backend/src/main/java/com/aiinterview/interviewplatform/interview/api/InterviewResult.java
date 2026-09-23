package com.aiinterview.interviewplatform.interview.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the candidate is shown once the attempt is over.
 *
 * <p>Separate from {@link InterviewState} on purpose, and not merely for tidiness:
 * {@code InterviewState} is readable throughout the interview and therefore must
 * never carry a score, or later answers get tuned to earlier feedback. This type
 * carries nothing else <em>but</em> scores, and is only obtainable once the
 * attempt has stopped accepting answers.
 *
 * <p>Every number here is computed by the backend from stored verdicts. Nothing
 * in it is derivable by a client, which is the point — a score a browser could
 * recompute is a score a browser could disagree with.
 *
 * <p>Added in M5A as a read-only projection. It is <strong>not</strong> the M5
 * report: nothing here is persisted, there is no band, no narrative and no
 * versioning. Those belong to Reporting, and inventing them here would commit
 * that milestone's design ahead of time.
 */
public record InterviewResult(

        UUID interviewId,

        InterviewStatus status,

        CompletionReason completionReason,

        /**
         * Null when nothing could be graded. Reported as "no result" rather than
         * as zero — an attempt we failed to grade is not a failed attempt.
         */
        BigDecimal overallScore,

        /** How much of the intended weight was actually graded, in basis points. */
        int coverageBp,

        List<SkillResult> skills,

        List<QuestionResult> questions
) {

    public InterviewResult {
        skills = skills == null ? List.of() : List.copyOf(skills);
        questions = questions == null ? List.of() : List.copyOf(questions);
    }

    public boolean isScoreable() {
        return overallScore != null;
    }

    public record SkillResult(UUID skillId, String skillCode, String skillName,
                              BigDecimal score, int weightBp, int coverageBp,
                              int questionCount) {
    }

    /**
     * One turn and what grading concluded about it.
     *
     * @param score  null when the turn could not be graded, which the UI must
     *               show as unavailable rather than as a zero
     * @param graded false for a turn excluded from the average — our failure,
     *               reported honestly instead of being hidden
     */
    public record QuestionResult(UUID interviewQuestionId, int position, TurnKind kind,
                                 TurnStatus status, UUID skillId, String promptText,
                                 BigDecimal score, boolean graded,
                                 List<CriterionResult> criteria) {

        public QuestionResult {
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
        }
    }

    /**
     * One rubric criterion's outcome, with the candidate's own words behind it.
     *
     * @param evidenceQuote a verbatim span of the answer. The whole product
     *                      claim rests on this being present and real, so it is
     *                      carried through to the client rather than summarised
     */
    public record CriterionResult(UUID criterionId, String code, String label,
                                  com.aiinterview.interviewplatform.evaluation.api.Verdict verdict,
                                  BigDecimal credit, int weightBp, String evidenceQuote) {
    }
}
