package com.aiinterview.interviewplatform.evaluation.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle of grading one answer, as a <em>derived</em> view.
 *
 * <p>No table or column stores this value, and none should. The approved schema
 * already carries every fact it needs:
 *
 * <table>
 *   <caption>Derivation</caption>
 *   <tr><th>Phase</th><th>Stored as</th></tr>
 *   <tr><td>{@link #PENDING}</td>
 *       <td>{@code interview_questions.status = ANSWERED}, no current evaluation</td></tr>
 *   <tr><td>{@link #PROCESSING}</td>
 *       <td>{@code jobs.status = RUNNING} for this answer (in-flight within the
 *           service call until the worker arrives in M4)</td></tr>
 *   <tr><td>{@link #COMPLETED}</td>
 *       <td>{@code evaluations.status = SUCCEEDED} and {@code is_current}</td></tr>
 *   <tr><td>{@link #FAILED}</td>
 *       <td>{@code evaluations.status = FAILED_*} and {@code is_current}</td></tr>
 * </table>
 *
 * <p>Adding a stored phase column would duplicate those facts and create a
 * second source of truth that can disagree with them. This enum exists to give
 * the transitions a name and to make them testable, not to be persisted.
 */
public enum EvaluationPhase {

    /** An answer exists and is awaiting grading. */
    PENDING,

    /** A grading attempt is in flight. */
    PROCESSING,

    /** A validated result is stored and current. */
    COMPLETED,

    /** The attempt failed terminally; the answer is never lost and may be re-driven. */
    FAILED;

    private static final Set<EvaluationPhase> TERMINAL = EnumSet.of(COMPLETED, FAILED);

    /**
     * Legal transitions.
     *
     * <p>{@code COMPLETED} and {@code FAILED} may both return to
     * {@code PROCESSING}: re-evaluation is an explicit, audited, append-only
     * operation, not a mutation. Nothing may go backwards to {@code PENDING} —
     * an answer awaiting grading for the first time is a state that cannot recur.
     */
    public boolean canTransitionTo(EvaluationPhase next) {
        if (next == null || next == this) {
            return false;
        }
        return switch (this) {
            case PENDING -> next == PROCESSING;
            case PROCESSING -> next == COMPLETED || next == FAILED;
            case COMPLETED, FAILED -> next == PROCESSING;
        };
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Derives the phase from what is actually stored. */
    public static EvaluationPhase of(boolean hasCurrentEvaluation, EvaluationStatus currentStatus) {
        if (!hasCurrentEvaluation || currentStatus == null) {
            return PENDING;
        }
        return currentStatus.isSuccess() ? COMPLETED : FAILED;
    }
}
