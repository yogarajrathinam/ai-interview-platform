package com.aiinterview.interviewplatform.interview;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiinterview.interviewplatform.interview.api.CompletionReason;
import com.aiinterview.interviewplatform.interview.api.InterviewStatus;
import com.aiinterview.interviewplatform.interview.api.TurnStatus;
import com.aiinterview.interviewplatform.interview.domain.InterviewLifecycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The interview and turn state machines.
 *
 * <p>Pure rules, so every path is enumerable. These sit above the database
 * constraints rather than replacing them — {@code ck_int_terminal} and
 * {@code ck_iq_shape} still have the last word — but they are what turns an
 * illegal move into a clear domain error instead of a constraint violation
 * surfacing from three layers down.
 */
@DisplayName("Interview lifecycle")
class InterviewLifecycleTest {

    @Nested
    @DisplayName("Attempt transitions")
    class Attempt {

        @ParameterizedTest(name = "{0} -> {1} is allowed")
        @CsvSource({
                "IN_PROGRESS,COMPLETING",
                "IN_PROGRESS,ABANDONED",
                "COMPLETING,COMPLETED",
                // An administrator must be able to end an attempt stuck in grading.
                "COMPLETING,ABANDONED"})
        void legal(InterviewStatus from, InterviewStatus to) {
            assertThat(InterviewLifecycle.canTransition(from, to)).isTrue();
        }

        @ParameterizedTest(name = "{0} -> {1} is rejected")
        @CsvSource({
                // An attempt cannot jump to a result: "grading settled" is a
                // condition to observe, not to assume.
                "IN_PROGRESS,COMPLETED",
                "COMPLETING,IN_PROGRESS",
                "COMPLETED,IN_PROGRESS",
                "COMPLETED,COMPLETING",
                "COMPLETED,ABANDONED",
                "ABANDONED,IN_PROGRESS",
                "ABANDONED,COMPLETED"})
        void illegal(InterviewStatus from, InterviewStatus to) {
            assertThat(InterviewLifecycle.canTransition(from, to)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(InterviewStatus.class)
        void selfTransitionIsNotATransition(InterviewStatus status) {
            assertThat(InterviewLifecycle.canTransition(status, status)).isFalse();
            assertThat(InterviewLifecycle.canTransition(status, null)).isFalse();
        }

        @Test
        @DisplayName("only live attempts occupy the one-live-attempt slot")
        void liveStatuses() {
            assertThat(InterviewStatus.IN_PROGRESS.isLive()).isTrue();
            assertThat(InterviewStatus.COMPLETING.isLive()).isTrue();
            assertThat(InterviewStatus.COMPLETED.isLive()).isFalse();
            assertThat(InterviewStatus.ABANDONED.isLive()).isFalse();
        }

        @Test
        @DisplayName("only an in-progress attempt accepts answers")
        void onlyInProgressAcceptsAnswers() {
            assertThat(InterviewStatus.IN_PROGRESS.acceptsAnswers()).isTrue();
            assertThat(InterviewStatus.COMPLETING.acceptsAnswers()).isFalse();
            assertThat(InterviewStatus.COMPLETED.acceptsAnswers()).isFalse();
        }
    }

    @Nested
    @DisplayName("Completion reasons")
    class Reasons {

        @Test
        @DisplayName("an in-progress attempt carries no reason")
        void inProgressHasNoReason() {
            assertThat(InterviewLifecycle.isReasonValidFor(InterviewStatus.IN_PROGRESS, null))
                    .isTrue();
            assertThat(InterviewLifecycle.isReasonValidFor(
                    InterviewStatus.IN_PROGRESS, CompletionReason.ALL_ANSWERED)).isFalse();
        }

        @Test
        @DisplayName("a completing attempt may already carry its reason")
        void completingMayCarryItsReason() {
            // The reason is known when completion starts, so it is recorded then
            // rather than held in memory until the attempt finally closes.
            assertThat(InterviewLifecycle.isReasonValidFor(
                    InterviewStatus.COMPLETING, CompletionReason.CANDIDATE_FINISHED)).isTrue();
        }

        @Test
        @DisplayName("abandonment reasons and completion reasons are not interchangeable")
        void reasonsMatchTheirTerminalStatus() {
            assertThat(InterviewLifecycle.isReasonValidFor(
                    InterviewStatus.COMPLETED, CompletionReason.ALL_ANSWERED)).isTrue();
            assertThat(InterviewLifecycle.isReasonValidFor(
                    InterviewStatus.COMPLETED, CompletionReason.ABANDONED_BY_ADMIN)).isFalse();
            assertThat(InterviewLifecycle.isReasonValidFor(
                    InterviewStatus.ABANDONED, CompletionReason.ABANDONED_EXPIRED)).isTrue();
            assertThat(InterviewLifecycle.isReasonValidFor(
                    InterviewStatus.ABANDONED, CompletionReason.TIME_EXPIRED)).isFalse();
        }

        @Test
        @DisplayName("expiring with answers completes; expiring with none abandons")
        void expiryDependsOnWhetherAnythingWasAnswered() {
            assertThat(CompletionReason.TIME_EXPIRED.terminalStatus())
                    .isEqualTo(InterviewStatus.COMPLETED);
            assertThat(CompletionReason.ABANDONED_EXPIRED.terminalStatus())
                    .isEqualTo(InterviewStatus.ABANDONED);
        }
    }

    @Nested
    @DisplayName("Turn transitions")
    class Turn {

        @ParameterizedTest(name = "{0} -> {1} is allowed")
        @CsvSource({
                "PENDING,ASKED",
                // The completion sweep: a question never reached is recorded
                // as unanswered so the attempt can close.
                "PENDING,SKIPPED",
                "ASKED,ANSWERED",
                "ASKED,SKIPPED",
                "ANSWERED,EVALUATED",
                "ANSWERED,EVAL_FAILED",
                // Re-grading returns the turn to awaiting a verdict.
                "EVALUATED,ANSWERED",
                "EVAL_FAILED,ANSWERED"})
        void legal(TurnStatus from, TurnStatus to) {
            assertThat(InterviewLifecycle.canTransition(from, to)).isTrue();
        }

        @ParameterizedTest(name = "{0} -> {1} is rejected")
        @CsvSource({
                // A question once shown cannot become unshown.
                "ASKED,PENDING",
                "ANSWERED,PENDING",
                "EVALUATED,PENDING",
                // Answering requires having been asked.
                "PENDING,ANSWERED",
                // A skip is final: it is a decision, not a pause.
                "SKIPPED,ANSWERED",
                "SKIPPED,ASKED",
                // A verdict is replaced by re-running, never edited in place.
                "EVALUATED,EVAL_FAILED",
                "EVAL_FAILED,EVALUATED"})
        void illegal(TurnStatus from, TurnStatus to) {
            assertThat(InterviewLifecycle.canTransition(from, to)).isFalse();
        }

        @Test
        @DisplayName("a skip is settled but an unevaluated answer is not")
        void gradingSettledSemantics() {
            assertThat(TurnStatus.SKIPPED.isGradingSettled()).isTrue();
            assertThat(TurnStatus.EVALUATED.isGradingSettled()).isTrue();
            assertThat(TurnStatus.EVAL_FAILED.isGradingSettled()).isTrue();
            // The one that keeps an attempt in COMPLETING.
            assertThat(TurnStatus.ANSWERED.isGradingSettled()).isFalse();
        }

        @Test
        @DisplayName("a skip carries no answer; a failed grading still does")
        void answerPresence() {
            assertThat(TurnStatus.SKIPPED.hasAnswer()).isFalse();
            assertThat(TurnStatus.ANSWERED.hasAnswer()).isTrue();
            // The candidate's work survives our failure to grade it.
            assertThat(TurnStatus.EVAL_FAILED.hasAnswer()).isTrue();
        }
    }

    @Nested
    @DisplayName("Finalisation")
    class Finalisation {

        @Test
        @DisplayName("an attempt closes only from COMPLETING with grading settled")
        void finalizeRequiresSettledGrading() {
            assertThat(InterviewLifecycle.canFinalize(InterviewStatus.COMPLETING, true)).isTrue();
            // Closing early would produce a report missing a question the
            // candidate did answer.
            assertThat(InterviewLifecycle.canFinalize(InterviewStatus.COMPLETING, false)).isFalse();
            assertThat(InterviewLifecycle.canFinalize(InterviewStatus.IN_PROGRESS, true)).isFalse();
            assertThat(InterviewLifecycle.canFinalize(InterviewStatus.COMPLETED, true)).isFalse();
        }
    }
}
