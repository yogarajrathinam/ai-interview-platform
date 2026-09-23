package com.aiinterview.interviewplatform.interview.application;

import com.aiinterview.interviewplatform.evaluation.api.AnswerEvaluationService;
import com.aiinterview.interviewplatform.evaluation.api.EvaluateAnswerCommand;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationPhase;
import com.aiinterview.interviewplatform.interview.api.CompletionReason;
import com.aiinterview.interviewplatform.interview.api.InterviewService;
import com.aiinterview.interviewplatform.interview.api.InterviewState;
import com.aiinterview.interviewplatform.interview.api.InterviewStatus;
import com.aiinterview.interviewplatform.interview.api.StartInterviewCommand;
import com.aiinterview.interviewplatform.interview.api.SubmitAnswerCommand;
import com.aiinterview.interviewplatform.interview.api.TurnStatus;
import com.aiinterview.interviewplatform.interview.domain.InterviewEntity;
import com.aiinterview.interviewplatform.interview.domain.InterviewLifecycle;
import com.aiinterview.interviewplatform.interview.domain.InterviewQuestionEntity;
import com.aiinterview.interviewplatform.interview.infrastructure.AnswerRepository;
import com.aiinterview.interviewplatform.interview.infrastructure.InterviewQuestionRepository;
import com.aiinterview.interviewplatform.interview.infrastructure.InterviewRepository;
import com.aiinterview.interviewplatform.question.api.TemplateCatalog;
import com.aiinterview.interviewplatform.shared.config.AppProperties;
import com.aiinterview.interviewplatform.shared.error.ApplicationException;
import com.aiinterview.interviewplatform.shared.error.ErrorCode;
import com.aiinterview.interviewplatform.shared.id.IdGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The interview engine.
 *
 * <p>Deliberately not {@code @Transactional}. Grading calls a provider, and the
 * M2 rule that no provider call happens inside a transaction is preserved here
 * by keeping every write in {@link InterviewWriter} and every provider call
 * between writes, never inside one.
 *
 * <p>Calls evaluation through its published contract and reproduces none of it:
 * no scoring, no verdicts, no rubric interpretation. What this class decides is
 * what to ask next and when to stop.
 */
@Service
public class DefaultInterviewService implements InterviewService {

    private static final Logger log = LoggerFactory.getLogger(DefaultInterviewService.class);

    private static final List<InterviewStatus> LIVE_STATUSES =
            List.of(InterviewStatus.IN_PROGRESS, InterviewStatus.COMPLETING);

    private final InterviewRepository interviews;
    private final InterviewQuestionRepository turns;
    private final AnswerRepository answers;
    private final InterviewWriter writer;
    private final InterviewPlanner planner;
    private final TurnSequencer sequencer;
    private final FollowUpSelector followUpSelector;
    private final TemplateCatalog templateCatalog;
    private final AnswerEvaluationService evaluationService;
    private final IdGenerator idGenerator;
    private final AppProperties properties;
    private final Clock clock;

    public DefaultInterviewService(InterviewRepository interviews,
                                   InterviewQuestionRepository turns,
                                   AnswerRepository answers,
                                   InterviewWriter writer,
                                   InterviewPlanner planner,
                                   TurnSequencer sequencer,
                                   FollowUpSelector followUpSelector,
                                   TemplateCatalog templateCatalog,
                                   AnswerEvaluationService evaluationService,
                                   IdGenerator idGenerator,
                                   AppProperties properties,
                                   Clock clock) {
        this.interviews = interviews;
        this.turns = turns;
        this.answers = answers;
        this.writer = writer;
        this.planner = planner;
        this.sequencer = sequencer;
        this.followUpSelector = followUpSelector;
        this.templateCatalog = templateCatalog;
        this.evaluationService = evaluationService;
        this.idGenerator = idGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- start

    @Override
    public InterviewState start(StartInterviewCommand command) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        Optional<InterviewEntity> live =
                interviews.findLiveForCandidate(command.candidateUserId(), LIVE_STATUSES);
        if (live.isPresent()) {
            if (command.resumeIfLive()) {
                return stateOf(live.get(), now);
            }
            throw ApplicationException.conflict(ErrorCode.LIVE_ATTEMPT_EXISTS,
                    "This candidate already has an interview in progress.");
        }

        TemplateCatalog.TemplateView template = templateCatalog
                .findPublishedTemplateByKey(command.templateKey())
                .orElseThrow(() -> ApplicationException.conflict(ErrorCode.NOT_FOUND,
                        "No published interview template '%s'.".formatted(command.templateKey())));

        UUID interviewId = idGenerator.newId();
        InterviewEntity interview = InterviewEntity.start(
                interviewId,
                command.candidateUserId(),
                // The resolved VERSION row. From here the attempt never
                // re-resolves, which is what pinning means.
                template.id(),
                now.plusMinutes(template.hardDurationMin()),
                properties.engineVersion(),
                now);

        List<InterviewQuestionEntity> plan =
                planner.planFor(interviewId, command.candidateUserId(), template, now);

        try {
            writer.startWithPlan(interview, plan);
        } catch (DataIntegrityViolationException e) {
            // uq_interviews_one_live: a concurrent start won. Theirs is as good
            // as ours would have been, so resume it rather than fail a click.
            log.info("Concurrent start for candidate {}; resuming the existing attempt",
                    command.candidateUserId());
            return interviews.findLiveForCandidate(command.candidateUserId(), LIVE_STATUSES)
                    .map(existing -> stateOf(existing, now))
                    .orElseThrow(() -> e);
        }

        log.info("Interview {} started for candidate {} on template {} v{} with {} core questions",
                interviewId, command.candidateUserId(), template.templateKey(),
                template.version(), plan.size());
        return stateOf(interview, now);
    }

    // ---------------------------------------------------------------- reads

    @Override
    public InterviewState getState(UUID interviewId) {
        return stateOf(requireInterview(interviewId), OffsetDateTime.now(clock));
    }

    // -------------------------------------------------------------- advance

    @Override
    public InterviewState advance(UUID interviewId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        InterviewEntity interview = requireInterview(interviewId);

        if (!interview.getStatus().acceptsAnswers()) {
            return stateOf(interview, now);
        }
        if (interview.isExpiredAt(now)) {
            return expire(interview, now);
        }

        List<InterviewQuestionEntity> current = loadTurns(interviewId);

        Optional<InterviewQuestionEntity> open = sequencer.nextOpenTurn(current);
        if (open.isPresent()) {
            writer.serveTurn(open.get().getId(), now);
            return stateOf(requireInterview(interviewId), now);
        }

        // The core plan is exhausted. Probe weaknesses once, then finish.
        if (!interview.hasSelectedFollowUps()) {
            runFollowUpPhase(interview, current, now);
            InterviewEntity refreshed = requireInterview(interviewId);
            Optional<InterviewQuestionEntity> probe = sequencer.nextOpenTurn(loadTurns(interviewId));
            if (probe.isPresent()) {
                writer.serveTurn(probe.get().getId(), now);
                return stateOf(requireInterview(interviewId), now);
            }
            return maybeComplete(refreshed, now);
        }

        return maybeComplete(interview, now);
    }

    /**
     * Runs the deferred follow-up phase.
     *
     * <p>Skipped entirely when grading has not settled. Waiting would put a
     * model call back on the candidate's critical path, and a missed probe is a
     * far smaller cost than a stalled interview.
     */
    private void runFollowUpPhase(InterviewEntity interview,
                                  List<InterviewQuestionEntity> current,
                                  OffsetDateTime now) {
        if (!sequencer.allGradingSettled(current)) {
            log.info("Interview {}: grading unsettled, skipping the follow-up phase",
                    interview.getId());
            return;
        }

        TemplateCatalog.TemplateView template = templateOf(interview);
        if (template == null) {
            return;
        }

        boolean ranHere = writer.appendFollowUpsOnce(interview.getId(),
                turnsNow -> followUpSelector.selectFor(interview.getId(), turnsNow, template, now),
                now);

        if (ranHere) {
            log.info("Interview {}: follow-up phase complete", interview.getId());
        }
    }

    // --------------------------------------------------------------- answers

    @Override
    public SubmitAnswerResult submitAnswer(SubmitAnswerCommand command) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        InterviewEntity interview = requireInterview(command.interviewId());
        requireAcceptingAnswers(interview, now);

        InterviewQuestionEntity turn = requireTurnOf(interview.getId(),
                command.interviewQuestionId());

        boolean stored;
        try {
            stored = writer.recordAnswer(turn.getId(), command.contentText(),
                    command.inputMode(), command.timeSpentSec(), now).isPresent();
        } catch (DataIntegrityViolationException e) {
            // The answer table's primary key settled a concurrent double-submit.
            log.info("Duplicate answer for turn {}; keeping the stored one", turn.getId());
            stored = false;
        }

        return new SubmitAnswerResult(turn.getId(), !stored,
                stateOf(requireInterview(command.interviewId()), now));
    }

    @Override
    public InterviewState skip(UUID interviewId, UUID interviewQuestionId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        InterviewEntity interview = requireInterview(interviewId);
        requireAcceptingAnswers(interview, now);

        writer.recordSkip(requireTurnOf(interviewId, interviewQuestionId).getId(), now);
        return stateOf(requireInterview(interviewId), now);
    }

    // ------------------------------------------------------------- grading

    @Override
    public InterviewState evaluateAnswer(UUID interviewId, UUID interviewQuestionId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        InterviewQuestionEntity turn = requireTurnOf(interviewId, interviewQuestionId);

        if (!turn.getStatus().hasAnswer()) {
            throw ApplicationException.conflict(ErrorCode.INTERVIEW_NOT_IN_PROGRESS,
                    "Question %s has no answer to grade.".formatted(interviewQuestionId));
        }

        String answerText = answers.findById(turn.getId())
                .orElseThrow(() -> ApplicationException.notFound(
                        "Answer for question %s is missing.".formatted(interviewQuestionId)))
                .getContentText();

        // Outside any transaction: this is the call that can take seconds. The
        // answer is already durable, so a failure here loses nothing.
        AnswerEvaluationService.EvaluationOutcome outcome = evaluationService.evaluateAnswer(
                EvaluateAnswerCommand.of(interviewId, turn.getId(), turn.getId(),
                        // The pinned version, never today's published one.
                        turn.getQuestionVersionId(), answerText));

        writer.applyGradingOutcome(turn.getId(), outcome.phase() == EvaluationPhase.COMPLETED);
        return stateOf(requireInterview(interviewId), now);
    }

    // ---------------------------------------------------------- completion

    @Override
    public InterviewState complete(UUID interviewId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        InterviewEntity interview = requireInterview(interviewId);

        if (interview.getStatus() != InterviewStatus.IN_PROGRESS) {
            return stateOf(interview, now);
        }

        List<InterviewQuestionEntity> current = loadTurns(interviewId);
        CompletionReason reason = sequencer.allTurnsClosed(current)
                ? CompletionReason.ALL_ANSWERED
                : CompletionReason.CANDIDATE_FINISHED;

        // Questions the candidate never reached are recorded as unanswered.
        // Leaving them open would mean the attempt could never satisfy the
        // "all grading settled" condition and would sit in COMPLETING forever.
        int closed = writer.closeUnansweredTurns(interviewId, now);
        if (closed > 0) {
            log.info("Interview {}: closed {} unanswered question(s) on early finish",
                    interviewId, closed);
        }

        writer.beginCompleting(interviewId, reason, now);
        return finalizeCompletion(interviewId);
    }

    @Override
    public InterviewState finalizeCompletion(UUID interviewId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        InterviewEntity interview = requireInterview(interviewId);

        if (interview.getStatus() != InterviewStatus.COMPLETING) {
            return stateOf(interview, now);
        }

        List<InterviewQuestionEntity> current = loadTurns(interviewId);
        if (!InterviewLifecycle.canFinalize(interview.getStatus(),
                sequencer.allGradingSettled(current))) {
            // Still grading. The attempt stays COMPLETING, which is exactly the
            // state that lets a client say "preparing your report" honestly.
            return stateOf(interview, now);
        }

        // M5 generates the report here, between COMPLETING and COMPLETED.
        writer.finish(interviewId, interview.getCompletionReason(), null, now);
        return stateOf(requireInterview(interviewId), now);
    }

    @Override
    public InterviewState abandon(UUID interviewId, CompletionReason reason, UUID actingUserId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!reason.isAbandonment()) {
            throw ApplicationException.validation(
                    "%s is not an abandonment reason.".formatted(reason));
        }
        writer.finish(interviewId, reason, actingUserId, now);
        return stateOf(requireInterview(interviewId), now);
    }

    // -------------------------------------------------------------- helpers

    /**
     * An expired attempt ends the way its content deserves: abandoned when
     * nothing was answered, completed when something was.
     */
    private InterviewState expire(InterviewEntity interview, OffsetDateTime now) {
        List<InterviewQuestionEntity> current = loadTurns(interview.getId());
        boolean anyAnswered = current.stream()
                .anyMatch(turn -> turn.getStatus().hasAnswer()
                        || turn.getStatus() == TurnStatus.SKIPPED);

        if (anyAnswered) {
            writer.beginCompleting(interview.getId(), CompletionReason.TIME_EXPIRED, now);
            return finalizeCompletion(interview.getId());
        }
        writer.finish(interview.getId(), CompletionReason.ABANDONED_EXPIRED, null, now);
        return stateOf(requireInterview(interview.getId()), now);
    }

    private InterviewState maybeComplete(InterviewEntity interview, OffsetDateTime now) {
        writer.beginCompleting(interview.getId(), CompletionReason.ALL_ANSWERED, now);
        return finalizeCompletion(interview.getId());
    }

    private void requireAcceptingAnswers(InterviewEntity interview, OffsetDateTime now) {
        if (!interview.getStatus().acceptsAnswers()) {
            throw ApplicationException.conflict(ErrorCode.INTERVIEW_NOT_IN_PROGRESS,
                    "Interview %s is %s.".formatted(interview.getId(), interview.getStatus()));
        }
        if (interview.isExpiredAt(now)) {
            throw ApplicationException.conflict(ErrorCode.INTERVIEW_NOT_IN_PROGRESS,
                    "Interview %s passed its deadline.".formatted(interview.getId()));
        }
    }

    private InterviewEntity requireInterview(UUID interviewId) {
        return interviews.findById(interviewId).orElseThrow(() -> ApplicationException.notFound(
                "Interview %s does not exist.".formatted(interviewId)));
    }

    /** Also the ownership check: a turn from another attempt is simply not found. */
    private InterviewQuestionEntity requireTurnOf(UUID interviewId, UUID turnId) {
        return turns.findById(turnId)
                .filter(turn -> turn.getInterviewId().equals(interviewId))
                .orElseThrow(() -> ApplicationException.notFound(
                        "Question %s is not part of interview %s."
                                .formatted(turnId, interviewId)));
    }

    private List<InterviewQuestionEntity> loadTurns(UUID interviewId) {
        return turns.findByInterviewIdOrderByPositionAsc(interviewId);
    }

    private TemplateCatalog.TemplateView templateOf(InterviewEntity interview) {
        return templateCatalog.findTemplate(interview.getTemplateId()).orElse(null);
    }

    private InterviewState stateOf(InterviewEntity interview, OffsetDateTime now) {
        return sequencer.toState(interview, loadTurns(interview.getId()),
                templateOf(interview), now);
    }
}
