package com.aiinterview.interviewplatform.interview.application;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationReadModel;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationReadModel.AnswerEvaluationView;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationReadModel.CriterionView;
import com.aiinterview.interviewplatform.interview.api.TurnKind;
import com.aiinterview.interviewplatform.interview.api.TurnStatus;
import com.aiinterview.interviewplatform.interview.domain.InterviewQuestionEntity;
import com.aiinterview.interviewplatform.question.api.QuestionCatalog;
import com.aiinterview.interviewplatform.question.api.TemplateCatalog;
import com.aiinterview.interviewplatform.shared.id.IdGenerator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Chooses which weaknesses to probe once the core questions are done.
 *
 * <p>Follow-ups are <strong>deferred</strong>: they are selected after the core
 * plan is complete, not interleaved. That is what keeps grading off the
 * candidate's critical path — nobody ever waits on a model to be given their
 * next question, because the next question was decided at start.
 *
 * <p>Selection is a pure ranking over stored criterion results. The grader
 * contributes a {@code followUpNeeded} signal; whether to act on it is decided
 * here, against budgets the template sets. That split is deliberate — a model
 * that could summon extra questions could also be talked into summoning them.
 *
 * <p>If grading has not settled, <strong>no follow-ups are asked at all</strong>
 * and the interview proceeds to completion. Waiting would reintroduce exactly
 * the blocking this design removes, and a missed follow-up costs far less than
 * a stalled interview.
 */
@Component
public class FollowUpSelector {

    private static final Logger log = LoggerFactory.getLogger(FollowUpSelector.class);

    /** Below this the grader is too unsure to justify spending a question. */
    private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.6");

    private final EvaluationReadModel evaluations;
    private final QuestionCatalog questionCatalog;
    private final IdGenerator idGenerator;

    public FollowUpSelector(EvaluationReadModel evaluations, QuestionCatalog questionCatalog,
                            IdGenerator idGenerator) {
        this.evaluations = evaluations;
        this.questionCatalog = questionCatalog;
        this.idGenerator = idGenerator;
    }

    /**
     * Builds the follow-up turns to append. Never mutates anything.
     *
     * @param turns all existing turns, used for budgets and next position
     * @return possibly empty — an interview where everything was answered well
     *         needs no probing, and that is a good outcome rather than a gap
     */
    public List<InterviewQuestionEntity> selectFor(UUID interviewId,
                                                   List<InterviewQuestionEntity> turns,
                                                   TemplateCatalog.TemplateView template,
                                                   OffsetDateTime now) {

        int totalBudget = Math.min(template.maxFollowUpsTotal(),
                Math.max(0, template.maxFollowUpsTotal() - countFollowUps(turns)));
        if (totalBudget <= 0) {
            return List.of();
        }

        List<InterviewQuestionEntity> answeredCore = turns.stream()
                .filter(turn -> turn.getKind() == TurnKind.CORE)
                .filter(turn -> turn.getStatus() == TurnStatus.EVALUATED)
                .toList();
        if (answeredCore.isEmpty()) {
            return List.of();
        }

        List<Candidate> candidates = new ArrayList<>();
        for (InterviewQuestionEntity turn : answeredCore) {
            // The answer's id is the turn's id: answers are keyed by their turn.
            evaluations.findCurrentForAnswer(turn.getId())
                    .filter(AnswerEvaluationView::isSuccess)
                    .filter(this::isProbeworthy)
                    .ifPresent(view -> collectCandidates(turn, view, candidates));
        }

        // Heaviest unearned weight first: probe what cost the most, not what
        // happened to be graded last.
        candidates.sort(Comparator
                .comparing((Candidate c) -> c.criterion().unearnedWeight()).reversed()
                .thenComparing(c -> c.criterion().criterionId().toString()));

        return materialise(interviewId, candidates, turns, template, totalBudget, now);
    }

    /**
     * An answer worth probing at all.
     *
     * <p>An answer that tried to manipulate the grader, or that was off topic,
     * is not probed: there is no weakness to explore, and re-engaging with an
     * injection attempt is the one thing that could give it a second chance.
     */
    private boolean isProbeworthy(AnswerEvaluationView view) {
        if (view.injectionSuspected() || view.answerOffTopic()) {
            log.info("Skipping follow-up selection for answer {}: injection={} offTopic={}",
                    view.answerId(), view.injectionSuspected(), view.answerOffTopic());
            return false;
        }
        return true;
    }

    private void collectCandidates(InterviewQuestionEntity turn, AnswerEvaluationView view,
                                   List<Candidate> candidates) {
        Map<UUID, QuestionCatalog.RubricCriterionView> rubric =
                questionCatalog.findRubric(turn.getQuestionVersionId()).stream()
                        .collect(HashMap::new,
                                (map, criterion) -> map.put(criterion.id(), criterion),
                                HashMap::putAll);

        for (CriterionView criterion : view.criteria()) {
            if (!criterion.isWeak()) {
                continue;
            }
            if (criterion.confidence() != null
                    && criterion.confidence().compareTo(MIN_CONFIDENCE) < 0) {
                continue;
            }
            QuestionCatalog.RubricCriterionView authored = rubric.get(criterion.criterionId());
            // Only curated probes are asked. A model may not author the text
            // shown to a candidate as the platform's own question.
            if (authored == null || authored.followUpPrompt() == null
                    || authored.followUpPrompt().isBlank()) {
                continue;
            }
            candidates.add(new Candidate(turn, criterion, authored.followUpPrompt()));
        }
    }

    private List<InterviewQuestionEntity> materialise(UUID interviewId, List<Candidate> ranked,
                                                      List<InterviewQuestionEntity> turns,
                                                      TemplateCatalog.TemplateView template,
                                                      int totalBudget, OffsetDateTime now) {
        Map<UUID, Integer> perParent = new HashMap<>();
        for (InterviewQuestionEntity turn : turns) {
            if (turn.isFollowUp() && turn.getParentId() != null) {
                perParent.merge(turn.getParentId(), 1, Integer::sum);
            }
        }

        int nextPosition = turns.stream()
                .mapToInt(InterviewQuestionEntity::getPosition)
                .max()
                .orElse(0) + 1;

        List<InterviewQuestionEntity> created = new ArrayList<>();
        for (Candidate candidate : ranked) {
            if (created.size() >= totalBudget) {
                break;
            }
            UUID parentId = candidate.turn().getId();
            int usedForParent = perParent.getOrDefault(parentId, 0);
            if (usedForParent >= template.maxFollowUpsPerParent()) {
                continue;
            }

            created.add(InterviewQuestionEntity.followUp(
                    idGenerator.newId(),
                    interviewId,
                    nextPosition++,
                    parentId,
                    // The follow-up inherits the parent's pinned version, so its
                    // criterion reference stays inside that version's rubric.
                    candidate.turn().getQuestionVersionId(),
                    candidate.turn().getSkillId(),
                    candidate.criterion().criterionId(),
                    candidate.promptText(),
                    now));
            perParent.merge(parentId, 1, Integer::sum);
        }
        return created;
    }

    private int countFollowUps(List<InterviewQuestionEntity> turns) {
        return (int) turns.stream().filter(InterviewQuestionEntity::isFollowUp).count();
    }

    private record Candidate(InterviewQuestionEntity turn, CriterionView criterion,
                             String promptText) {
    }

    /** Exposed for the service's logging and for tests to assert intent. */
    public Optional<String> describe(List<InterviewQuestionEntity> selected) {
        return selected.isEmpty()
                ? Optional.empty()
                : Optional.of("%d follow-up(s) selected".formatted(selected.size()));
    }
}
