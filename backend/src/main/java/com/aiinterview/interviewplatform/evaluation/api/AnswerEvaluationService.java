package com.aiinterview.interviewplatform.evaluation.api;


import java.math.BigDecimal;
import java.util.UUID;

/**
 * Grades a stored answer and records the outcome.
 *
 * <p>The published entry point other modules use. In M4 the job handler calls
 * it; there is deliberately no HTTP surface in M2.
 *
 * <p>What it does <em>not</em> do is update the turn's status. That row belongs
 * to the interview module, and writing to it from here would both cross a
 * module boundary and make the engine useless for the dry-run case where no
 * turn exists. The outcome reports the phase reached, and the orchestrator
 * applies it.
 */
public interface AnswerEvaluationService {

    /**
     * Idempotent. Calling it twice for the same answer does not produce two
     * current evaluations, and does not call the provider twice.
     */
    EvaluationOutcome evaluateAnswer(EvaluateAnswerCommand command);

    /** The phase derived from what is currently stored for this answer. */
    EvaluationPhase phaseOf(UUID answerId);

    /**
     * Result of a grading request.
     *
     * @param alreadyEvaluated true when a current evaluation already existed and
     *                         was returned unchanged — the idempotent no-op path
     * @param supersededId     the evaluation this one replaced, on re-evaluation
     */
    record EvaluationOutcome(UUID evaluationId, UUID answerId, EvaluationPhase phase,
                             BigDecimal derivedScore, int criterionCount,
                             boolean alreadyEvaluated, UUID supersededId,
                             UUID aiInvocationId, EvaluationResult result) {

        public boolean isSuccess() {
            return phase == EvaluationPhase.COMPLETED;
        }
    }
}
