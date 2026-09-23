package com.aiinterview.interviewplatform.interview.api;

import java.util.UUID;

/**
 * An answer to one turn.
 *
 * <p>Addressed by {@code interviewQuestionId}, never by "the current question".
 * A positional submit would be unsafe against a retry, a back button, or a
 * second tab — the client would race the server's idea of where it is. Naming
 * the turn makes a repeated submit trivially recognisable as the same one.
 *
 * @param inputMode {@code TEXT} today. Voice will supply a transcript through
 *                  this same field, which is why the engine only ever sees
 *                  normalised text
 * @param timeSpentSec client-reported and advisory; recorded for analytics,
 *                     never used to enforce a limit, because a hostile or
 *                     broken client must not be able to buy itself more time
 */
public record SubmitAnswerCommand(UUID interviewId, UUID interviewQuestionId,
                                  String contentText, String inputMode,
                                  Integer timeSpentSec) {

    public SubmitAnswerCommand {
        if (interviewId == null) {
            throw new IllegalArgumentException("interviewId is required");
        }
        if (interviewQuestionId == null) {
            throw new IllegalArgumentException("interviewQuestionId is required");
        }
        if (contentText == null) {
            throw new IllegalArgumentException("contentText is required");
        }
        inputMode = inputMode == null ? "TEXT" : inputMode;
    }

    public static SubmitAnswerCommand of(UUID interviewId, UUID interviewQuestionId,
                                         String contentText) {
        return new SubmitAnswerCommand(interviewId, interviewQuestionId, contentText,
                "TEXT", null);
    }
}
