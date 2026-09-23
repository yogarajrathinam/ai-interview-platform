package com.aiinterview.interviewplatform.interview.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Everything a client needs to render an interview, in one shape.
 *
 * <p>The single authoritative view of an attempt. There is deliberately no
 * second way to learn "which question am I on" — a client that holds that in
 * its own state loses it on refresh, diverges across two tabs, and
 * double-submits on a flaky connection. Everything here is derived from the
 * turn rows on every read, so a refresh restores exactly.
 *
 * <p>Note what is <strong>absent</strong>: no scores, no verdicts, no evaluation
 * detail. A candidate must not be able to see how they are doing mid-interview,
 * or later answers get tuned to earlier feedback.
 */
public record InterviewState(

        UUID interviewId,

        InterviewStatus status,

        CompletionReason completionReason,

        OffsetDateTime startedAt,

        OffsetDateTime hardDeadlineAt,

        /**
         * So the countdown is rendered against our clock. A device with a skewed
         * clock would otherwise show a phantom expiry.
         */
        OffsetDateTime serverTime,

        Progress progress,

        /** Null once no turn remains to serve. */
        CurrentQuestion currentQuestion,

        List<TurnSummary> timeline
) {

    public InterviewState {
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
    }

    public boolean hasQuestion() {
        return currentQuestion != null;
    }

    /**
     * @param followUpsRemaining budget left under the template's ceiling, which
     *                           is what stops an interview growing without end
     */
    public record Progress(int coreTotal, int coreAnswered, int followUpsAsked,
                           int followUpsRemaining, int answeredTotal, int evaluatedTotal) {

        /** Drives an honest "6 of 8 graded" instead of a spinner. */
        public boolean isGradingSettled() {
            return evaluatedTotal >= answeredTotal;
        }
    }

    /**
     * The turn to put in front of the candidate.
     *
     * @param promptText for a core turn this is the pinned question version's
     *                   text; for a follow-up it is the curated probe stored on
     *                   the turn itself
     * @param parentTurnId set on a follow-up, so the UI can show what it probes
     */
    public record CurrentQuestion(UUID interviewQuestionId, int position, TurnKind kind,
                                  UUID parentTurnId, UUID skillId, UUID questionVersionId,
                                  String promptText, String contextText,
                                  int expectedDurationSec, OffsetDateTime askedAt) {
    }

    /**
     * One row of the attempt's history.
     *
     * <p>Carries status but never a score — the timeline is navigation, not
     * feedback.
     */
    public record TurnSummary(UUID interviewQuestionId, int position, TurnKind kind,
                              TurnStatus status, UUID skillId, UUID parentTurnId) {
    }
}
