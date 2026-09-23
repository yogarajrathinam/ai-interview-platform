package com.aiinterview.interviewplatform.evaluation.api;

import java.util.UUID;

/**
 * Instruction to grade a stored answer and record the result.
 *
 * <p>The caller supplies the answer text and the pinned question version rather
 * than the evaluation module reading them: the answer belongs to the interview
 * module, and reaching into another module's tables is exactly the coupling the
 * architecture forbids. In M4 the job handler that owns the turn will build
 * this; in M2 the tests do.
 *
 * <p>{@code questionVersionId} must be the version the turn pinned, not the
 * currently published one. That is what makes a completed interview reproduce
 * the same result after the question is revised.
 */
public record EvaluateAnswerCommand(

        UUID interviewId,

        UUID interviewQuestionId,

        /** Also the primary key of {@code answers}. */
        UUID answerId,

        /** The version pinned by the turn — never the current published one. */
        UUID questionVersionId,

        String answerText,

        String inputMode,

        /**
         * When true, supersede the existing current evaluation and grade again.
         * This is the admin re-evaluation path: append-only, audited, and never
         * destructive. When false, an answer that already has a current
         * evaluation is returned untouched.
         */
        boolean reevaluate,

        /** The admin who asked for a re-evaluation; null when automatic. */
        UUID triggeredBy
) {

    public EvaluateAnswerCommand {
        if (answerId == null) {
            throw new IllegalArgumentException("answerId is required");
        }
        if (questionVersionId == null) {
            throw new IllegalArgumentException("questionVersionId is required");
        }
        if (answerText == null) {
            throw new IllegalArgumentException("answerText is required");
        }
    }

    /** The ordinary automatic path: grade once, do nothing if already graded. */
    public static EvaluateAnswerCommand of(UUID interviewId, UUID interviewQuestionId,
                                           UUID answerId, UUID questionVersionId,
                                           String answerText) {
        return new EvaluateAnswerCommand(interviewId, interviewQuestionId, answerId,
                questionVersionId, answerText, "TEXT", false, null);
    }
}
