package com.aiinterview.interviewplatform.evaluation.api;

/**
 * Terminal outcome of one evaluation attempt, as stored in
 * {@code evaluations.status}.
 *
 * <p>There is no in-progress value on purpose: an evaluation row is written
 * only once the outcome is known. Writing a row per attempt would collide with
 * the partial unique index on {@code (answer_id) WHERE is_current}, and would
 * put transient provider noise into the candidate's permanent record. Interim
 * failures live in {@code ai_invocations} and {@code jobs.last_error}.
 */
public enum EvaluationStatus {

    /** A validated result was produced and a score derived. */
    SUCCEEDED,

    /** The provider answered, but the result failed our validation gates. */
    FAILED_VALIDATION,

    /** The provider could not be reached, timed out, or errored. */
    FAILED_PROVIDER;

    public boolean isSuccess() {
        return this == SUCCEEDED;
    }
}
