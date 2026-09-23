package com.aiinterview.interviewplatform.interview.api;

/**
 * Why an attempt stopped.
 *
 * <p>Separate from {@link InterviewStatus} because these describe a cause, not
 * a condition the attempt sits in. Keeping them apart is what lets four states
 * cover seven distinct endings without a combinatorial state machine — and it
 * keeps the difference between "ran out of time with answers" and "ran out of
 * time with none" expressible.
 */
public enum CompletionReason {

    /** The candidate chose to finish, with questions possibly remaining. */
    CANDIDATE_FINISHED,

    /** Every turn reached a terminal status. */
    ALL_ANSWERED,

    /** The hard deadline passed while answers existed. */
    TIME_EXPIRED,

    /** All turns terminal and the candidate went idle; swept to completion. */
    AUTO_COMPLETED,

    ABANDONED_BY_CANDIDATE,

    /** The deadline passed with nothing answered — there is nothing to score. */
    ABANDONED_EXPIRED,

    ABANDONED_BY_ADMIN;

    /** Whether this reason ends the attempt with no result at all. */
    public boolean isAbandonment() {
        return this == ABANDONED_BY_CANDIDATE
                || this == ABANDONED_EXPIRED
                || this == ABANDONED_BY_ADMIN;
    }

    public InterviewStatus terminalStatus() {
        return isAbandonment() ? InterviewStatus.ABANDONED : InterviewStatus.COMPLETED;
    }
}
