package com.aiinterview.interviewplatform.evaluation.api;

/**
 * Grades one answer and returns the result. Persists nothing.
 *
 * <p>Split from {@link AnswerEvaluationService} on purpose. This half is the
 * business value — call a provider, validate what comes back, derive a score —
 * and it needs no database, no interview and no stored answer. That makes it
 * directly usable by the admin rubric dry-run tool (M3), where an author pastes
 * a sample answer and wants to see how it would be graded without leaving a
 * trace anywhere.
 *
 * <p>Never throws for a provider or validation failure: those are outcomes, and
 * are reported as a non-success {@link EvaluationResult} carrying the reason.
 * An exception from here means a genuine programming fault.
 */
public interface EvaluationEngine {

    EvaluationResult evaluate(EvaluationRequest request);
}
