package com.aiinterview.interviewplatform.interview.application;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationReadModel;
import com.aiinterview.interviewplatform.evaluation.api.ScoreAggregator;
import com.aiinterview.interviewplatform.interview.api.InterviewResult;
import com.aiinterview.interviewplatform.interview.api.InterviewResultService;
import com.aiinterview.interviewplatform.interview.domain.InterviewEntity;
import com.aiinterview.interviewplatform.interview.domain.InterviewQuestionEntity;
import com.aiinterview.interviewplatform.interview.infrastructure.InterviewQuestionRepository;
import com.aiinterview.interviewplatform.interview.infrastructure.InterviewRepository;
import com.aiinterview.interviewplatform.question.api.QuestionCatalog;
import com.aiinterview.interviewplatform.question.api.TemplateCatalog;
import com.aiinterview.interviewplatform.shared.error.ApplicationException;
import com.aiinterview.interviewplatform.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the result a finished attempt earned.
 *
 * <p>Reads only. It joins three things the modules already own — the turns, the
 * stored verdicts, and the pinned template's weights — and hands them to the
 * published {@link ScoreAggregator}. It computes no average of its own: the
 * formula lives in the evaluation module and is reached through its contract,
 * so a score shown to a candidate and a score computed anywhere else cannot
 * disagree.
 *
 * <p>Added in M5A to give the candidate UI something authoritative to render.
 * It deliberately persists nothing. The M5 report is a stored, versioned
 * artefact with bands and narrative; writing rows here would commit that design
 * before it is made, and a projection that can always be recomputed from
 * verdicts cannot drift from them.
 */
@Service
public class DefaultInterviewResultService implements InterviewResultService {

    private final InterviewRepository interviews;
    private final InterviewQuestionRepository turns;
    private final EvaluationReadModel evaluations;
    private final ScoreAggregator aggregator;
    private final TemplateCatalog templateCatalog;
    private final QuestionCatalog questionCatalog;

    public DefaultInterviewResultService(InterviewRepository interviews,
                                         InterviewQuestionRepository turns,
                                         EvaluationReadModel evaluations,
                                         ScoreAggregator aggregator,
                                         TemplateCatalog templateCatalog,
                                         QuestionCatalog questionCatalog) {
        this.interviews = interviews;
        this.turns = turns;
        this.evaluations = evaluations;
        this.aggregator = aggregator;
        this.templateCatalog = templateCatalog;
        this.questionCatalog = questionCatalog;
    }

    @Override
    @Transactional(readOnly = true)
    public InterviewResult getResult(UUID interviewId) {
        InterviewEntity interview = interviews.findById(interviewId)
                .orElseThrow(() -> ApplicationException.notFound(
                        "Interview %s was not found.".formatted(interviewId)));

        if (interview.getStatus().acceptsAnswers()) {
            throw ApplicationException.conflict(ErrorCode.RESULT_NOT_AVAILABLE,
                    "This interview is still in progress. The result becomes available "
                            + "once it is finished.");
        }

        List<InterviewQuestionEntity> plan = turns.findByInterviewIdOrderByPositionAsc(interviewId);

        // One round trip for every evaluation. The answer id IS the turn id,
        // which is why no separate answer lookup is needed here.
        Map<UUID, EvaluationReadModel.AnswerEvaluationView> byTurn = new HashMap<>();
        evaluations.findCurrentForAnswers(plan.stream().map(InterviewQuestionEntity::getId).toList())
                .forEach(view -> byTurn.put(view.answerId(), view));

        List<InterviewResult.QuestionResult> questions = new ArrayList<>(plan.size());
        List<ScoreAggregator.ScoredItem> scored = new ArrayList<>(plan.size());

        for (InterviewQuestionEntity turn : plan) {
            EvaluationReadModel.AnswerEvaluationView evaluation = byTurn.get(turn.getId());
            BigDecimal score = scoreOf(turn, evaluation);

            scored.add(new ScoreAggregator.ScoredItem(
                    turn.getId(), turn.getSkillId(), weightOf(turn), score));

            questions.add(new InterviewResult.QuestionResult(
                    turn.getId(),
                    turn.getPosition() == null ? 0 : turn.getPosition(),
                    turn.getKind(),
                    turn.getStatus(),
                    turn.getSkillId(),
                    promptTextOf(turn),
                    score,
                    score != null,
                    criteriaOf(turn, evaluation)));
        }

        List<TemplateCatalog.TemplateSkillView> templateSkills =
                templateCatalog.findSkills(interview.getTemplateId());

        Map<UUID, Integer> skillWeights = new LinkedHashMap<>();
        templateSkills.forEach(skill -> skillWeights.put(skill.skillId(), skill.weightBp()));

        ScoreAggregator.AggregateScore aggregate = aggregator.aggregate(scored, skillWeights);

        return new InterviewResult(
                interview.getId(),
                interview.getStatus(),
                interview.getCompletionReason(),
                aggregate.overallScore(),
                aggregate.coverageBp(),
                skillResults(aggregate, templateSkills),
                questions);
    }

    /**
     * What this turn contributes to the average.
     *
     * <p>Three outcomes that must stay distinct: a skip is {@code 0.00} and
     * counted, because the candidate chose not to answer; a turn we failed to
     * grade is {@code null} and excluded entirely, because our outage is not
     * their fault; anything still unsettled is likewise excluded rather than
     * guessed at.
     */
    private static BigDecimal scoreOf(InterviewQuestionEntity turn,
                                      EvaluationReadModel.AnswerEvaluationView evaluation) {
        return switch (turn.getStatus()) {
            case SKIPPED -> BigDecimal.ZERO;
            case EVALUATED -> evaluation != null && evaluation.isSuccess()
                    ? evaluation.derivedScore()
                    : null;
            default -> null;
        };
    }

    /** Follow-ups carry zero weight by construction; the scorer skips them. */
    private static int weightOf(InterviewQuestionEntity turn) {
        return turn.getWeightBp() == null ? 0 : turn.getWeightBp();
    }

    /**
     * A follow-up stores its own probe text; a core turn's text lives on the
     * pinned question version, which is what keeps the result readable years
     * later even if the question has since been rewritten.
     */
    private String promptTextOf(InterviewQuestionEntity turn) {
        if (turn.getPromptText() != null && !turn.getPromptText().isBlank()) {
            return turn.getPromptText();
        }
        return questionCatalog.findVersion(turn.getQuestionVersionId())
                .map(QuestionCatalog.QuestionVersionView::promptText)
                .orElse(null);
    }

    /**
     * Criterion outcomes, named for a human.
     *
     * <p>The verdict and evidence come from the evaluation; the code and label
     * come from the rubric of the pinned version. The evaluation module stores
     * only the criterion id — resolving the name is the caller's job precisely
     * so grading never has to depend on the catalogue.
     */
    private List<InterviewResult.CriterionResult> criteriaOf(
            InterviewQuestionEntity turn,
            EvaluationReadModel.AnswerEvaluationView evaluation) {

        if (evaluation == null || evaluation.criteria().isEmpty()) {
            return List.of();
        }

        Map<UUID, QuestionCatalog.RubricCriterionView> rubric = new HashMap<>();
        questionCatalog.findRubric(turn.getQuestionVersionId())
                .forEach(criterion -> rubric.put(criterion.id(), criterion));

        return evaluation.criteria().stream()
                .map(criterion -> {
                    QuestionCatalog.RubricCriterionView named = rubric.get(criterion.criterionId());
                    return new InterviewResult.CriterionResult(
                            criterion.criterionId(),
                            named == null ? criterion.code() : named.code(),
                            named == null ? null : named.label(),
                            criterion.verdict(),
                            criterion.credit(),
                            criterion.weightBp(),
                            // Suppressed when the quote failed validation: a span
                            // that is not in the answer must never be shown as
                            // though the candidate wrote it.
                            criterion.evidenceRejected() ? null : criterion.evidenceQuote());
                })
                .toList();
    }

    private static List<InterviewResult.SkillResult> skillResults(
            ScoreAggregator.AggregateScore aggregate,
            List<TemplateCatalog.TemplateSkillView> templateSkills) {

        Map<UUID, TemplateCatalog.TemplateSkillView> named = new HashMap<>();
        templateSkills.forEach(skill -> named.put(skill.skillId(), skill));

        return aggregate.skills().stream()
                .map(skill -> {
                    TemplateCatalog.TemplateSkillView meta = named.get(skill.skillId());
                    return new InterviewResult.SkillResult(
                            skill.skillId(),
                            meta == null ? null : meta.code(),
                            meta == null ? null : meta.name(),
                            skill.score(),
                            skill.skillWeightBp(),
                            skill.coverageBp(),
                            skill.questionCount());
                })
                .toList();
    }
}
