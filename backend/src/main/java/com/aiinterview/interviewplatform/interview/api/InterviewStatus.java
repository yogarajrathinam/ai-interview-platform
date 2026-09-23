package com.aiinterview.interviewplatform.interview.api;

/**
 * The state of one interview attempt, as stored in {@code interviews.status}.
 *
 * <p>Four values, and deliberately no more. States that a client might expect —
 * "waiting for an answer", "evaluating", "ready for the next question" — are
 * all derivable from the turn rows, and storing them would create a second
 * source of truth able to disagree with the turns themselves.
 *
 * <p>There is no {@code CREATED} either: an attempt is created and started in
 * one operation, so a state between the two would never be observed.
 *
 * <p>Expiry, auto-completion and administrative abandonment are not states.
 * They are {@link CompletionReason} values, because they describe <em>why</em>
 * an attempt ended rather than a distinct condition it can sit in.
 */
public enum InterviewStatus {

    /** Accepting answers. The only state in which a turn may be served. */
    IN_PROGRESS,

    /**
     * The candidate is finished; grading and reporting are still settling.
     * Distinct from {@link #COMPLETED} so "your report is being prepared" can
     * be told apart from "your report is broken".
     */
    COMPLETING,

    /** Terminal and final. */
    COMPLETED,

    /** Terminal with no result. Releases the candidate's live-attempt slot. */
    ABANDONED;

    /** Live attempts occupy the {@code uq_interviews_one_live} slot. */
    public boolean isLive() {
        return this == IN_PROGRESS || this == COMPLETING;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == ABANDONED;
    }

    /** Only an in-progress attempt may be served turns or accept answers. */
    public boolean acceptsAnswers() {
        return this == IN_PROGRESS;
    }
}
