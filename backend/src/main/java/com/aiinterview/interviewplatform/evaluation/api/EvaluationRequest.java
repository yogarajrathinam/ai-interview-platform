package com.aiinterview.interviewplatform.evaluation.api;

import java.util.List;
import java.util.UUID;

/**
 * Everything needed to grade one answer, and nothing else.
 *
 * <p>Self-contained by design. The request carries a <em>snapshot</em> of the
 * question and rubric rather than ids to look up later, which has three
 * consequences that matter:
 *
 * <ol>
 *   <li>The evaluation module never reads the catalogue mid-grade, so it cannot
 *       accidentally pick up a newer version of a question than the attempt
 *       pinned.</li>
 *   <li>The engine has no dependency on the interview module at all — it is
 *       handed an answer, a question and a rubric.</li>
 *   <li>The same contract serves the admin dry-run tool (M3), where there is no
 *       interview, no turn and no stored answer. That is why the three attempt
 *       identifiers are nullable.</li>
 * </ol>
 *
 * <p>Immutable, and never built from JPA entities: the application layer maps
 * catalogue rows into these snapshots.
 */
public record EvaluationRequest(

        /** Null for a dry run. */
        UUID interviewId,

        /** Null for a dry run. */
        UUID interviewQuestionId,

        /** Null for a dry run; required to persist a result. */
        UUID answerId,

        /** The pinned question version. Reproducibility depends on this. */
        UUID questionVersionId,

        /** {@code question_versions.version}, recorded as the rubric version. */
        int rubricVersion,

        QuestionSnapshot question,

        List<RubricCriterion> rubric,

        CandidateAnswer answer
) {

    public EvaluationRequest {
        if (questionVersionId == null) {
            throw new IllegalArgumentException("questionVersionId is required");
        }
        if (question == null) {
            throw new IllegalArgumentException("question snapshot is required");
        }
        if (answer == null) {
            throw new IllegalArgumentException("answer is required");
        }
        if (rubric == null || rubric.isEmpty()) {
            throw new IllegalArgumentException("a rubric with at least one criterion is required");
        }
        rubric = List.copyOf(rubric);
    }

    /** True when this request may be persisted rather than merely computed. */
    public boolean isPersistable() {
        return answerId != null;
    }

    /**
     * The question as it was put to the candidate.
     *
     * @param referenceAnswer grounding for the grader; markedly improves the
     *                        PARTIAL/MISSING distinction. Never shown to the
     *                        candidate mid-interview, and never echoed back.
     */
    public record QuestionSnapshot(UUID skillId, String promptText, String contextText,
                                   String referenceAnswer) {

        public QuestionSnapshot {
            if (skillId == null) {
                throw new IllegalArgumentException("skillId is required");
            }
            if (promptText == null || promptText.isBlank()) {
                throw new IllegalArgumentException("promptText is required");
            }
        }
    }

    /**
     * One rubric criterion, as published.
     *
     * @param expectation the observable claim an answer either makes, partially
     *                    makes, omits or contradicts — never a topic label
     * @param weightBp    share of the question score; a complete rubric sums to 10000
     */
    public record RubricCriterion(UUID id, String code, String label, String expectation,
                                  int weightBp, String tier) {

        public RubricCriterion {
            if (id == null) {
                throw new IllegalArgumentException("criterion id is required");
            }
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("criterion code is required");
            }
            if (weightBp <= 0) {
                throw new IllegalArgumentException(
                        "criterion weight must be positive, got " + weightBp);
            }
        }
    }

    /**
     * The candidate's words.
     *
     * @param inputMode {@code TEXT} today; {@code VOICE} later supplies a
     *                  transcript through this same field, which is why the
     *                  engine only ever sees normalised text
     */
    public record CandidateAnswer(String text, String inputMode) {

        public CandidateAnswer {
            if (text == null) {
                throw new IllegalArgumentException("answer text is required");
            }
            inputMode = inputMode == null ? "TEXT" : inputMode;
        }

        public boolean isBlank() {
            return text.isBlank();
        }
    }
}
