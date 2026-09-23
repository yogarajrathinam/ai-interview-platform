package com.aiinterview.interviewplatform.evaluation.application;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationReadModel;
import com.aiinterview.interviewplatform.evaluation.domain.EvaluationCriterionResultEntity;
import com.aiinterview.interviewplatform.evaluation.domain.EvaluationEntity;
import com.aiinterview.interviewplatform.evaluation.infrastructure.EvaluationCriterionResultRepository;
import com.aiinterview.interviewplatform.evaluation.infrastructure.EvaluationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps stored evaluations to the published read views.
 *
 * <p>Read-only, and the boundary where evaluation entities stop.
 */
@Service
public class DefaultEvaluationReadModel implements EvaluationReadModel {

    private final EvaluationRepository evaluations;
    private final EvaluationCriterionResultRepository criterionResults;

    public DefaultEvaluationReadModel(EvaluationRepository evaluations,
                                      EvaluationCriterionResultRepository criterionResults) {
        this.evaluations = evaluations;
        this.criterionResults = criterionResults;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AnswerEvaluationView> findCurrentForAnswer(UUID answerId) {
        return evaluations.findByAnswerIdAndCurrentTrue(answerId).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnswerEvaluationView> findCurrentForAnswers(List<UUID> answerIds) {
        if (answerIds == null || answerIds.isEmpty()) {
            return List.of();
        }
        List<AnswerEvaluationView> views = new ArrayList<>(answerIds.size());
        for (UUID answerId : answerIds) {
            evaluations.findByAnswerIdAndCurrentTrue(answerId)
                    .map(this::toView)
                    .ifPresent(views::add);
        }
        return List.copyOf(views);
    }

    private AnswerEvaluationView toView(EvaluationEntity evaluation) {
        // A failed evaluation has no criterion rows by design, so this is empty
        // rather than absent — there is nothing to report, not nothing to find.
        List<CriterionView> criteria = criterionResults.findByEvaluationId(evaluation.getId())
                .stream()
                .map(DefaultEvaluationReadModel::toView)
                .toList();

        return new AnswerEvaluationView(
                evaluation.getId(),
                evaluation.getAnswerId(),
                evaluation.getStatus(),
                evaluation.getDerivedScore(),
                evaluation.getConfidence(),
                evaluation.isFollowUpNeeded(),
                evaluation.getFollowUpTargetCriterionId(),
                evaluation.isInjectionSuspected(),
                evaluation.isAnswerOffTopic(),
                evaluation.getQuestionVersionId(),
                criteria);
    }

    /**
     * {@code code} stays null here on purpose: it lives on the rubric criterion
     * in the question module, and reaching for it would either breach the module
     * boundary or add a lookup to a call that does not need one. Consumers that
     * display a criterion already hold the catalogue and join by id.
     */
    private static CriterionView toView(EvaluationCriterionResultEntity entity) {
        return new CriterionView(entity.getRubricCriterionId(), null, entity.getVerdict(),
                entity.getCredit(), entity.getWeightBp(), entity.getConfidence(),
                entity.getEvidenceQuote(), entity.isEvidenceRejected());
    }
}
