package com.aiinterview.interviewplatform.interview.api;

import java.util.UUID;

/**
 * The interview engine's published entry point.
 *
 * <p>Owns the attempt lifecycle, question sequencing and turn orchestration. It
 * calls the evaluation module through that module's public contract and never
 * reproduces any of its scoring: the dependency runs one way, from interview to
 * evaluation, and an ArchUnit rule fails the build if it ever runs back.
 *
 * <p>There is no HTTP surface in M3. These operations are what a controller
 * will call in a later milestone, and what tests call today.
 *
 * <p>Every operation here is safe to repeat. Where the database already
 * guarantees that — one live attempt, one answer per turn — the guarantee is
 * used rather than re-implemented.
 */
public interface InterviewService {

    /**
     * Starts an attempt and materialises its question plan.
     *
     * <p>Idempotent per candidate: if a live attempt already exists, it is
     * returned untouched rather than a second one being created. The partial
     * unique index {@code uq_interviews_one_live} is what makes that true even
     * under a double-clicked button.
     *
     * @throws com.aiinterview.interviewplatform.shared.error.ApplicationException
     *         if the template is not published or cannot be planned
     */
    InterviewState start(StartInterviewCommand command);

    /** The attempt as it stands. The only read a runner needs. */
    InterviewState getState(UUID interviewId);

    /**
     * Serves the next question, marking it asked.
     *
     * <p>Idempotent: calling it twice returns the same turn and preserves the
     * original ask time. Two concurrent callers converge on one turn rather
     * than consuming two, because the turn served is always the lowest-position
     * open one.
     *
     * <p>When the core plan is exhausted this runs the deferred follow-up
     * phase; when nothing remains it moves the attempt toward completion.
     */
    InterviewState advance(UUID interviewId);

    /**
     * Records an answer.
     *
     * <p>Does not grade it. The answer is durable before any provider is
     * involved, so no grading failure can lose the candidate's work.
     *
     * <p>Idempotent through the answer table's primary key: a repeated submit
     * returns the stored answer rather than creating a second one or failing.
     */
    SubmitAnswerResult submitAnswer(SubmitAnswerCommand command);

    /** Records a deliberate skip. Scores zero and is counted, never hidden. */
    InterviewState skip(UUID interviewId, UUID interviewQuestionId);

    /**
     * Grades one answered turn through the evaluation module and applies the
     * outcome to the turn.
     *
     * <p>Separate from {@link #submitAnswer} on purpose: the provider call must
     * not sit inside the transaction that stores the answer, and in M4 a job
     * worker will drive this instead of the request thread.
     *
     * <p>Safe to repeat — an already-graded answer is not re-sent to the
     * provider.
     */
    InterviewState evaluateAnswer(UUID interviewId, UUID interviewQuestionId);

    /**
     * Ends the attempt at the candidate's request.
     *
     * <p>Moves to {@code COMPLETING}; the attempt closes once grading settles.
     * Completing an already-finished attempt returns its state unchanged.
     */
    InterviewState complete(UUID interviewId);

    /**
     * Closes an attempt whose turns have all settled.
     *
     * <p>Where report generation will be triggered in M5. Today it performs the
     * {@code COMPLETING → COMPLETED} transition and nothing else.
     */
    InterviewState finalizeCompletion(UUID interviewId);

    /** Ends the attempt with no result, releasing the live-attempt slot. */
    InterviewState abandon(UUID interviewId, CompletionReason reason, UUID actingUserId);

    /**
     * @param duplicate true when this submission matched one already stored, in
     *                  which case the stored answer is returned unchanged
     */
    record SubmitAnswerResult(UUID interviewQuestionId, boolean duplicate,
                              InterviewState state) {
    }
}
