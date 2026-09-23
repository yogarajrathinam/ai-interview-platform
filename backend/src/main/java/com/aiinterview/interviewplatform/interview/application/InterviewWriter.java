package com.aiinterview.interviewplatform.interview.application;

import com.aiinterview.interviewplatform.interview.api.CompletionReason;
import com.aiinterview.interviewplatform.interview.api.InterviewStatus;
import com.aiinterview.interviewplatform.interview.api.TurnStatus;
import com.aiinterview.interviewplatform.interview.domain.AnswerEntity;
import com.aiinterview.interviewplatform.interview.domain.InterviewEntity;
import com.aiinterview.interviewplatform.interview.domain.InterviewLifecycle;
import com.aiinterview.interviewplatform.interview.domain.InterviewQuestionEntity;
import com.aiinterview.interviewplatform.interview.infrastructure.AnswerRepository;
import com.aiinterview.interviewplatform.interview.infrastructure.InterviewQuestionRepository;
import com.aiinterview.interviewplatform.interview.infrastructure.InterviewRepository;
import com.aiinterview.interviewplatform.shared.error.ApplicationException;
import com.aiinterview.interviewplatform.shared.error.ErrorCode;
import com.aiinterview.interviewplatform.shared.jobs.api.JobQueue;
import com.aiinterview.interviewplatform.shared.jobs.api.JobType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database mutation the interview engine performs.
 *
 * <p>Isolated in its own bean so that transaction boundaries are visible rather
 * than implied. The service that orchestrates grading is deliberately
 * <em>not</em> transactional: a provider call can take ten seconds, and holding
 * a connection and row locks for that long is how a working application becomes
 * an unavailable one. Every write is short and lives here.
 */
@Component
class InterviewWriter {

    private final InterviewRepository interviews;
    private final InterviewQuestionRepository turns;
    private final AnswerRepository answers;
    private final JobQueue jobQueue;

    InterviewWriter(InterviewRepository interviews, InterviewQuestionRepository turns,
                    AnswerRepository answers, JobQueue jobQueue) {
        this.interviews = interviews;
        this.turns = turns;
        this.answers = answers;
        this.jobQueue = jobQueue;
    }

    /** Persists the attempt and its whole plan atomically. */
    @Transactional
    InterviewEntity startWithPlan(InterviewEntity interview,
                                  List<InterviewQuestionEntity> plan) {
        InterviewEntity saved = interviews.save(interview);
        turns.saveAll(plan);
        return saved;
    }

    /**
     * Marks a turn as served.
     *
     * <p>Idempotent by construction: re-serving a turn that is already
     * {@code ASKED} keeps its original ask time, so a refreshed browser does
     * not silently reset the candidate's clock.
     */
    @Transactional
    void serveTurn(UUID turnId, OffsetDateTime now) {
        InterviewQuestionEntity turn = requireTurn(turnId);
        if (turn.getStatus().isOpen()) {
            turn.markAsked(now);
            turns.save(turn);
        }
        interviews.findById(turn.getInterviewId()).ifPresent(interview -> {
            interview.touchActivity(now);
            interviews.save(interview);
        });
    }

    /**
     * Stores the answer and moves the turn on.
     *
     * <p>The answer is written before anything grades it, so no provider
     * failure can lose the candidate's work.
     *
     * @return empty when an answer already existed — the idempotent path
     */
    @Transactional
    Optional<AnswerEntity> recordAnswer(UUID turnId, String contentText, String inputMode,
                                        Integer timeSpentSec, OffsetDateTime now) {
        // The primary key makes one-answer-per-turn structural; checking first
        // keeps the common case free of a constraint violation, and the caller
        // still handles the race.
        if (answers.existsById(turnId)) {
            return Optional.empty();
        }

        InterviewQuestionEntity turn = requireTurn(turnId);
        requireTransition(turn, TurnStatus.ANSWERED);

        AnswerEntity answer = answers.save(AnswerEntity.create(
                turnId, contentText, parseInputMode(inputMode), now));

        turn.markAnswered(timeSpentSec, now);
        turns.save(turn);
        touchInterview(turn.getInterviewId(), now);

        // Enqueued inside this same transaction. Either the answer and the work
        // to grade it both commit, or neither does — so there is no window in
        // which a stored answer has nothing scheduled to evaluate it, and no
        // outbox to drift. This is the whole reason the queue lives in the
        // database rather than in a broker.
        jobQueue.enqueue(JobQueue.JobRequest.of(
                JobType.EVALUATE_ANSWER,
                EvaluateAnswerJobHandler.dedupeKeyFor(turnId),
                EvaluateAnswerJobHandler.payloadFor(turn.getInterviewId(), turnId)));

        return Optional.of(answer);
    }

    /** A skip is recorded honestly: it scores zero and is counted. */
    @Transactional
    void recordSkip(UUID turnId, OffsetDateTime now) {
        InterviewQuestionEntity turn = requireTurn(turnId);
        if (turn.getStatus() == TurnStatus.SKIPPED) {
            return;
        }
        requireTransition(turn, TurnStatus.SKIPPED);
        turn.markSkipped(now);
        turns.save(turn);
        touchInterview(turn.getInterviewId(), now);
    }

    /**
     * Applies a grading outcome to the turn.
     *
     * <p>The interview module owns this row, which is why the evaluation module
     * reports an outcome instead of writing it — and why the evaluation engine
     * stays usable where no turn exists at all.
     */
    @Transactional
    void applyGradingOutcome(UUID turnId, boolean succeeded) {
        InterviewQuestionEntity turn = requireTurn(turnId);
        if (succeeded) {
            turn.markEvaluated();
        } else {
            turn.markEvalFailed();
        }
        turns.save(turn);
    }

    /**
     * Appends follow-up turns under a row lock on the attempt.
     *
     * <p>Serving a question is naturally idempotent; appending one is not. Two
     * concurrent advances could each decide to probe, and the attempt would
     * grow duplicate questions. The lock and the {@code follow_ups_selected_at}
     * marker together make that impossible: the second caller waits, then sees
     * the phase has already run.
     *
     * @return true if this call performed the selection
     */
    @Transactional
    boolean appendFollowUpsOnce(UUID interviewId, FollowUpProducer producer,
                                OffsetDateTime now) {
        InterviewEntity interview = interviews.findByIdForUpdate(interviewId)
                .orElseThrow(() -> ApplicationException.notFound(
                        "Interview %s no longer exists.".formatted(interviewId)));

        if (interview.hasSelectedFollowUps()) {
            return false;
        }

        List<InterviewQuestionEntity> followUps = producer.produce(
                turns.findByInterviewIdOrderByPositionAsc(interviewId));
        if (!followUps.isEmpty()) {
            turns.saveAll(followUps);
        }

        // Recorded even when nothing was chosen: "we looked and found nothing"
        // must be distinguishable from "we have not looked yet".
        interview.markFollowUpsSelected(now);
        interview.touchActivity(now);
        interviews.save(interview);
        return true;
    }

    /**
     * Records every unanswered turn as skipped, so an attempt that ends early
     * can close.
     *
     * <p>Without this a candidate who finishes after two of eight questions
     * would leave six turns open forever, and the attempt could never satisfy
     * the "all grading settled" condition for completion.
     *
     * <p>Marking them skipped rather than deleting them is the honest choice:
     * they score zero and are counted, because dropping them would inflate the
     * score of someone who simply stopped.
     *
     * @return how many turns were closed
     */
    @Transactional
    int closeUnansweredTurns(UUID interviewId, OffsetDateTime now) {
        List<InterviewQuestionEntity> open =
                turns.findByInterviewIdOrderByPositionAsc(interviewId).stream()
                        .filter(turn -> turn.getStatus().isOpen())
                        .toList();
        for (InterviewQuestionEntity turn : open) {
            turn.markSkipped(now);
        }
        turns.saveAll(open);
        return open.size();
    }

    @Transactional
    void beginCompleting(UUID interviewId, CompletionReason reason, OffsetDateTime now) {
        InterviewEntity interview = requireInterview(interviewId);
        if (interview.getStatus() == InterviewStatus.COMPLETING) {
            return;
        }
        requireTransition(interview, InterviewStatus.COMPLETING, reason);
        interview.beginCompleting(reason, now);
        interviews.save(interview);
    }

    @Transactional
    void finish(UUID interviewId, CompletionReason reason, UUID actingUserId,
                OffsetDateTime now) {
        InterviewEntity interview = requireInterview(interviewId);
        if (interview.getStatus().isTerminal()) {
            return;
        }
        requireTransition(interview, reason.terminalStatus(), reason);
        interview.finish(reason, now, actingUserId);
        interviews.save(interview);
    }

    // ------------------------------------------------------------- helpers

    private void touchInterview(UUID interviewId, OffsetDateTime now) {
        interviews.findById(interviewId).ifPresent(interview -> {
            interview.touchActivity(now);
            interviews.save(interview);
        });
    }

    private InterviewQuestionEntity requireTurn(UUID turnId) {
        return turns.findById(turnId).orElseThrow(() -> ApplicationException.notFound(
                "Interview question %s does not exist.".formatted(turnId)));
    }

    private InterviewEntity requireInterview(UUID interviewId) {
        return interviews.findById(interviewId).orElseThrow(() -> ApplicationException.notFound(
                "Interview %s does not exist.".formatted(interviewId)));
    }

    private void requireTransition(InterviewQuestionEntity turn, TurnStatus target) {
        if (!InterviewLifecycle.canTransition(turn.getStatus(), target)) {
            throw ApplicationException.conflict(ErrorCode.INTERVIEW_NOT_IN_PROGRESS,
                    "Question %s is %s and cannot become %s."
                            .formatted(turn.getId(), turn.getStatus(), target));
        }
    }

    private void requireTransition(InterviewEntity interview, InterviewStatus target,
                                   CompletionReason reason) {
        if (!InterviewLifecycle.canTransition(interview.getStatus(), target)) {
            throw ApplicationException.conflict(ErrorCode.INTERVIEW_NOT_IN_PROGRESS,
                    "Interview %s is %s and cannot become %s."
                            .formatted(interview.getId(), interview.getStatus(), target));
        }
        if (!InterviewLifecycle.isReasonValidFor(target, reason)) {
            throw ApplicationException.validation(
                    "Reason %s is not valid for status %s.".formatted(reason, target));
        }
    }

    private static AnswerEntity.InputMode parseInputMode(String inputMode) {
        if (inputMode == null || inputMode.isBlank()) {
            return AnswerEntity.InputMode.TEXT;
        }
        try {
            return AnswerEntity.InputMode.valueOf(inputMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApplicationException.validation(
                    "Unsupported input mode '%s'.".formatted(inputMode));
        }
    }

    /** Lets the selector run inside the lock without the writer knowing its rules. */
    @FunctionalInterface
    interface FollowUpProducer {
        List<InterviewQuestionEntity> produce(List<InterviewQuestionEntity> currentTurns);
    }
}
