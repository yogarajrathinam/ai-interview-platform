package com.aiinterview.interviewplatform.interview.application;

import com.aiinterview.interviewplatform.interview.api.InterviewState;
import com.aiinterview.interviewplatform.interview.api.TurnKind;
import com.aiinterview.interviewplatform.interview.api.TurnStatus;
import com.aiinterview.interviewplatform.interview.domain.InterviewEntity;
import com.aiinterview.interviewplatform.interview.domain.InterviewQuestionEntity;
import com.aiinterview.interviewplatform.question.api.QuestionCatalog;
import com.aiinterview.interviewplatform.question.api.TemplateCatalog;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Derives everything a client sees from the turn rows.
 *
 * <p>No state here is stored. "Which question am I on", "how many are graded",
 * "is the interview waiting for me" are all computed on every read, which is
 * why a refresh restores an interview exactly and two tabs cannot disagree.
 *
 * <p>Pure with respect to the database — it reads the catalogue for question
 * text but writes nothing — so ordering rules can be tested directly.
 */
@Component
public class TurnSequencer {

    private final QuestionCatalog questionCatalog;

    public TurnSequencer(QuestionCatalog questionCatalog) {
        this.questionCatalog = questionCatalog;
    }

    /**
     * The turn to serve next: the lowest-position turn still open.
     *
     * <p>Position order is total ({@code uq_iq_position}), so this is
     * deterministic — and it is what makes serving idempotent under
     * concurrency. Two simultaneous callers both compute the same lowest open
     * turn, so they converge on one question instead of consuming two.
     */
    public Optional<InterviewQuestionEntity> nextOpenTurn(List<InterviewQuestionEntity> turns) {
        return turns.stream()
                .filter(turn -> turn.getStatus().isOpen())
                .min((a, b) -> Integer.compare(a.getPosition(), b.getPosition()));
    }

    /** True once no turn is open, whatever became of each one. */
    public boolean allTurnsClosed(List<InterviewQuestionEntity> turns) {
        return turns.stream().noneMatch(turn -> turn.getStatus().isOpen());
    }

    /**
     * True once every turn has a settled grading outcome.
     *
     * <p>The condition for closing an attempt, and the condition the deferred
     * follow-up phase needs before it can judge which criteria were weak.
     */
    public boolean allGradingSettled(List<InterviewQuestionEntity> turns) {
        return turns.stream().allMatch(turn -> turn.getStatus().isGradingSettled());
    }

    public InterviewState toState(InterviewEntity interview,
                                  List<InterviewQuestionEntity> turns,
                                  TemplateCatalog.TemplateView template,
                                  OffsetDateTime now) {

        Optional<InterviewQuestionEntity> current = interview.getStatus().acceptsAnswers()
                ? nextOpenTurn(turns)
                : Optional.empty();

        return new InterviewState(
                interview.getId(),
                interview.getStatus(),
                interview.getCompletionReason(),
                interview.getCreatedAt(),
                interview.getHardDeadlineAt(),
                now,
                progressOf(turns, template),
                current.map(this::toCurrentQuestion).orElse(null),
                turns.stream().map(TurnSequencer::toSummary).toList());
    }

    private InterviewState.Progress progressOf(List<InterviewQuestionEntity> turns,
                                               TemplateCatalog.TemplateView template) {
        int coreTotal = 0;
        int coreAnswered = 0;
        int followUpsAsked = 0;
        int answeredTotal = 0;
        int evaluatedTotal = 0;

        for (InterviewQuestionEntity turn : turns) {
            boolean core = turn.getKind() == TurnKind.CORE;
            if (core) {
                coreTotal++;
            } else {
                followUpsAsked++;
            }
            if (turn.getStatus().hasAnswer() || turn.getStatus() == TurnStatus.SKIPPED) {
                answeredTotal++;
                if (core) {
                    coreAnswered++;
                }
            }
            if (turn.getStatus().isGradingSettled()) {
                evaluatedTotal++;
            }
        }

        int followUpBudget = template == null ? 0 : template.maxFollowUpsTotal();
        return new InterviewState.Progress(coreTotal, coreAnswered, followUpsAsked,
                Math.max(0, followUpBudget - followUpsAsked), answeredTotal, evaluatedTotal);
    }

    /**
     * A follow-up carries its own curated prompt; a core turn's text comes from
     * the pinned question version, never from whatever is published now.
     */
    private InterviewState.CurrentQuestion toCurrentQuestion(InterviewQuestionEntity turn) {
        Optional<QuestionCatalog.QuestionVersionView> version =
                questionCatalog.findVersion(turn.getQuestionVersionId());

        String promptText = turn.isFollowUp()
                ? turn.getPromptText()
                : version.map(QuestionCatalog.QuestionVersionView::promptText).orElse(null);

        return new InterviewState.CurrentQuestion(
                turn.getId(),
                turn.getPosition(),
                turn.getKind(),
                turn.getParentId(),
                turn.getSkillId(),
                turn.getQuestionVersionId(),
                promptText,
                turn.isFollowUp() ? null
                        : version.map(QuestionCatalog.QuestionVersionView::contextText)
                                .orElse(null),
                // Follow-ups are short probes; the parent's budget does not apply.
                turn.isFollowUp() ? 60 : 120,
                turn.getAskedAt());
    }

    private static InterviewState.TurnSummary toSummary(InterviewQuestionEntity turn) {
        return new InterviewState.TurnSummary(turn.getId(), turn.getPosition(), turn.getKind(),
                turn.getStatus(), turn.getSkillId(), turn.getParentId());
    }
}
