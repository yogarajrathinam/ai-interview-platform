package com.aiinterview.interviewplatform.interview.api;

import java.util.UUID;

/**
 * Assembles the candidate's result once an attempt has finished.
 *
 * <p>Separate from {@link InterviewService} because it is a different kind of
 * operation: every method on that interface can change an attempt, and this one
 * cannot change anything. Keeping the read out of the lifecycle interface means
 * a result can never be fetched in a way that also advances an interview.
 */
public interface InterviewResultService {

    /**
     * The finished attempt's result.
     *
     * <p>Refuses while the attempt can still accept answers. A partial score
     * shown mid-interview would let a candidate tune later answers to earlier
     * feedback, which is the one thing the read model has been protecting
     * since M3 — exposing it through a second endpoint would undo that.
     *
     * @throws com.aiinterview.interviewplatform.shared.error.ApplicationException
     *         {@code NOT_FOUND} if no such attempt exists,
     *         {@code RESULT_NOT_AVAILABLE} if it is still in progress
     */
    InterviewResult getResult(UUID interviewId);
}
