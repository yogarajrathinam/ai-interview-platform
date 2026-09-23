package com.aiinterview.interviewplatform.evaluation.application;

import com.aiinterview.interviewplatform.evaluation.api.EvaluateAnswerCommand;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationResult;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationResult.CriterionOutcome;
import com.aiinterview.interviewplatform.evaluation.domain.EvaluationCriterionResultEntity;
import com.aiinterview.interviewplatform.evaluation.domain.EvaluationEntity;
import com.aiinterview.interviewplatform.evaluation.domain.ScoringPolicy;
import com.aiinterview.interviewplatform.evaluation.infrastructure.EvaluationCriterionResultRepository;
import com.aiinterview.interviewplatform.evaluation.infrastructure.EvaluationRepository;
import com.aiinterview.interviewplatform.shared.id.IdGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an evaluation and its criterion results in one transaction.
 *
 * <p>Separate from {@link DefaultAnswerEvaluationService} for a specific
 * reason: the provider call must happen <em>outside</em> a transaction. A model
 * call can take ten seconds, and holding a database connection and row locks
 * for its duration is how a working application becomes an unavailable one
 * under load. Keeping the write in its own bean makes that boundary explicit
 * rather than a comment someone deletes.
 *
 * <p>Superseding and inserting happen together, so there is never a moment when
 * an answer has no current evaluation.
 */
@Component
class EvaluationWriter {

    private final EvaluationRepository evaluations;
    private final EvaluationCriterionResultRepository criterionResults;
    private final IdGenerator idGenerator;
    private final Clock clock;

    EvaluationWriter(EvaluationRepository evaluations,
                     EvaluationCriterionResultRepository criterionResults,
                     IdGenerator idGenerator, Clock clock) {
        this.evaluations = evaluations;
        this.criterionResults = criterionResults;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * @param supersede when true, clear the existing current marker first
     * @return the id of the evaluation written, and the one it replaced
     */
    @Transactional
    WriteOutcome write(EvaluateAnswerCommand command, EvaluationResult result,
                       int rubricVersion, UUID aiInvocationId, boolean supersede) {

        UUID supersededId = null;
        if (supersede) {
            supersededId = evaluations.findByAnswerIdAndCurrentTrue(command.answerId())
                    .map(EvaluationEntity::getId)
                    .orElse(null);
            // Targeted update: the append-only trigger permits only is_current
            // to change, which a Hibernate dirty-update would violate.
            evaluations.supersedeCurrent(command.answerId());
        }

        UUID evaluationId = idGenerator.newId();
        EvaluationEntity entity = EvaluationEntity
                .builder(evaluationId, command.answerId(), result.status())
                .score(result.derivedScore(), result.modelReportedScore(), result.confidence())
                .summary(result.summary())
                .followUp(result.followUpNeeded(), result.followUpReason(),
                        result.followUpTargetCriterionId())
                .flags(result.injectionSuspected(), result.answerOffTopic())
                .provenance(ScoringPolicy.VERSION,
                        result.metadata() == null ? "n/a" : result.metadata().promptVersion(),
                        rubricVersion, command.questionVersionId())
                .provider(result.metadata() == null ? null : result.metadata().provider(),
                        result.metadata() == null ? null : result.metadata().model(),
                        aiInvocationId)
                .triggeredBy(command.triggeredBy())
                .error(result.errorCode(), result.errorDetail())
                .createdAt(OffsetDateTime.now(clock))
                .build();

        evaluations.save(entity);

        // A failed evaluation has no criterion results: recording verdicts we
        // never obtained would fabricate evidence of grading that did not happen.
        if (result.isSuccess() && !result.criteria().isEmpty()) {
            criterionResults.saveAll(toEntities(evaluationId, result.criteria()));
        }

        return new WriteOutcome(evaluationId, supersededId,
                result.isSuccess() ? result.criteria().size() : 0);
    }

    private List<EvaluationCriterionResultEntity> toEntities(UUID evaluationId,
                                                            List<CriterionOutcome> criteria) {
        List<EvaluationCriterionResultEntity> entities = new ArrayList<>(criteria.size());
        for (CriterionOutcome outcome : criteria) {
            EvaluationResult.EvidenceSpan evidence = outcome.evidence();
            entities.add(EvaluationCriterionResultEntity.create(
                    idGenerator.newId(),
                    evaluationId,
                    outcome.criterionId(),
                    outcome.verdict(),
                    outcome.credit(),
                    outcome.weightBp(),
                    outcome.confidence(),
                    evidence == null ? null : evidence.quote(),
                    evidence == null ? null : evidence.start(),
                    evidence == null ? null : evidence.end(),
                    outcome.evidenceRejected(),
                    outcome.comment()));
        }
        return entities;
    }

    record WriteOutcome(UUID evaluationId, UUID supersededId, int criterionCount) {
    }
}
