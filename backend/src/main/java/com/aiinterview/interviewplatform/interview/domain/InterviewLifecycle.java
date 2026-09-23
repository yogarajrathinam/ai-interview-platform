package com.aiinterview.interviewplatform.interview.domain;

import com.aiinterview.interviewplatform.interview.api.CompletionReason;
import com.aiinterview.interviewplatform.interview.api.InterviewStatus;
import com.aiinterview.interviewplatform.interview.api.TurnStatus;

/**
 * Which attempt and turn transitions are legal.
 *
 * <p>A pure function of the current state — no Spring, no database, no clock —
 * so every path can be enumerated in a unit test. The rules live here rather
 * than scattered through the service so that "can this happen?" has one answer.
 *
 * <p>These sit <em>above</em> the database constraints, not instead of them.
 * {@code ck_int_terminal} and {@code ck_int_reason_when} still have the last
 * word; this class exists so a violation surfaces as a clear domain error
 * rather than a constraint violation from three layers down.
 */
public final class InterviewLifecycle {

    private InterviewLifecycle() {
    }

    /**
     * Attempt transitions.
     *
     * <p>{@code COMPLETING} is reachable only from {@code IN_PROGRESS}, and
     * {@code COMPLETED} only from {@code COMPLETING} — an attempt cannot jump
     * straight to a result, because "grading has settled" is a real condition
     * that must be observed rather than assumed.
     *
     * <p>Abandonment is reachable from either live state: an administrator must
     * be able to end an attempt that is stuck waiting for grading.
     */
    public static boolean canTransition(InterviewStatus from, InterviewStatus to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        return switch (from) {
            case IN_PROGRESS -> to == InterviewStatus.COMPLETING
                    || to == InterviewStatus.ABANDONED;
            case COMPLETING -> to == InterviewStatus.COMPLETED
                    || to == InterviewStatus.ABANDONED;
            case COMPLETED, ABANDONED -> false;
        };
    }

    /** Guards the reason/status pairing that {@code ck_int_reason_when} enforces. */
    public static boolean isReasonValidFor(InterviewStatus status, CompletionReason reason) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case IN_PROGRESS -> reason == null;
            case COMPLETING -> true;
            case COMPLETED -> reason != null && !reason.isAbandonment();
            case ABANDONED -> reason != null && reason.isAbandonment();
        };
    }

    /**
     * Turn transitions.
     *
     * <p>{@code EVALUATED} and {@code EVAL_FAILED} can both return to
     * {@code ANSWERED}: re-grading is an explicit, append-only operation, and
     * the turn goes back to awaiting a verdict while it runs. Nothing returns
     * to {@code PENDING} — a question once shown cannot become unshown.
     */
    public static boolean canTransition(TurnStatus from, TurnStatus to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        return switch (from) {
            // PENDING -> SKIPPED is the completion sweep: when a candidate
            // finishes early, questions they never reached are recorded as
            // unanswered rather than left open. They score zero and are
            // counted, because excluding them would inflate the score of
            // someone who simply stopped.
            case PENDING -> to == TurnStatus.ASKED || to == TurnStatus.SKIPPED;
            case ASKED -> to == TurnStatus.ANSWERED || to == TurnStatus.SKIPPED;
            case ANSWERED -> to == TurnStatus.EVALUATED || to == TurnStatus.EVAL_FAILED;
            case EVALUATED, EVAL_FAILED -> to == TurnStatus.ANSWERED;
            case SKIPPED -> false;
        };
    }

    /**
     * Whether the attempt can close.
     *
     * <p>Requires every turn settled. An attempt that closes while an answer is
     * still awaiting grading would produce a report missing a question the
     * candidate did answer — worse than making them wait.
     */
    public static boolean canFinalize(InterviewStatus status, boolean allTurnsSettled) {
        return status == InterviewStatus.COMPLETING && allTurnsSettled;
    }
}
