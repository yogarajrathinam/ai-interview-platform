package com.aiinterview.interviewplatform.evaluation.application;

import com.aiinterview.interviewplatform.ai.api.AiInvocation;
import com.aiinterview.interviewplatform.ai.api.AiInvocationRecorder;
import com.aiinterview.interviewplatform.evaluation.api.AnswerEvaluationService;
import com.aiinterview.interviewplatform.evaluation.api.EvaluateAnswerCommand;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationEngine;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationPhase;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationRequest;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationResult;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationStatus;
import com.aiinterview.interviewplatform.evaluation.domain.EvaluationEntity;
import com.aiinterview.interviewplatform.evaluation.infrastructure.EvaluationRepository;
import com.aiinterview.interviewplatform.question.api.QuestionCatalog;
import com.aiinterview.interviewplatform.shared.error.ApplicationException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grades a stored answer and records the outcome.
 *
 * <p>Sequenced deliberately: read the snapshot, call the provider <em>outside
 * any transaction</em>, then write. A ten-second model call inside a
 * transaction would hold a connection and row locks for its duration.
 *
 * <p>Idempotency comes from the database, not from a framework. The partial
 * unique index {@code uq_evaluations_one_current} makes two current evaluations
 * for one answer impossible, so the race between two concurrent callers is
 * settled by PostgreSQL and the loser simply returns what the winner wrote.
 */
@Service
public class DefaultAnswerEvaluationService implements AnswerEvaluationService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultAnswerEvaluationService.class);

    private final EvaluationEngine engine;
    private final EvaluationRepository evaluations;
    private final EvaluationWriter writer;
    private final QuestionCatalog questionCatalog;
    private final AiInvocationRecorder invocationRecorder;
    private final Clock clock;

    public DefaultAnswerEvaluationService(EvaluationEngine engine,
                                          EvaluationRepository evaluations,
                                          EvaluationWriter writer,
                                          QuestionCatalog questionCatalog,
                                          AiInvocationRecorder invocationRecorder,
                                          Clock clock) {
        this.engine = engine;
        this.evaluations = evaluations;
        this.writer = writer;
        this.questionCatalog = questionCatalog;
        this.invocationRecorder = invocationRecorder;
        this.clock = clock;
    }

    @Override
    public EvaluationOutcome evaluateAnswer(EvaluateAnswerCommand command) {
        Optional<EvaluationEntity> existing =
                evaluations.findByAnswerIdAndCurrentTrue(command.answerId());

        // The idempotent no-op: already graded and no re-run requested. The
        // provider is never called, so a retried job costs nothing.
        if (existing.isPresent() && !command.reevaluate()) {
            return unchanged(existing.get());
        }

        EvaluationRequest request = buildRequest(command);
        int rubricVersion = request.rubricVersion();

        long startedAt = clock.millis();
        EvaluationResult result = engine.evaluate(request);
        int latencyMs = (int) Math.min(Integer.MAX_VALUE, clock.millis() - startedAt);

        // Recorded before the evaluation is written, in its own transaction, so
        // a failed call still leaves an accounting trail.
        UUID aiInvocationId = recordInvocation(command, request, result, latencyMs);

        try {
            EvaluationWriter.WriteOutcome written = writer.write(
                    command, result, rubricVersion, aiInvocationId, command.reevaluate());

            return new EvaluationOutcome(written.evaluationId(), command.answerId(),
                    phaseOf(result.status()), result.derivedScore(), written.criterionCount(),
                    false, written.supersededId(), aiInvocationId, result);

        } catch (DataIntegrityViolationException e) {
            // Another caller won the race for uq_evaluations_one_current. Their
            // result is as valid as ours would have been; return it rather than
            // failing work that actually succeeded.
            log.info("Concurrent evaluation detected for answer {}; returning the stored result",
                    command.answerId());
            return evaluations.findByAnswerIdAndCurrentTrue(command.answerId())
                    .map(this::unchanged)
                    .orElseThrow(() -> e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public EvaluationPhase phaseOf(UUID answerId) {
        return evaluations.findByAnswerIdAndCurrentTrue(answerId)
                .map(e -> EvaluationPhase.of(true, e.getStatus()))
                .orElse(EvaluationPhase.PENDING);
    }

    /**
     * Builds the request from the <strong>pinned</strong> question version.
     *
     * <p>This is where reproducibility is enforced in code: the rubric is read
     * by version id, never by question family, so revising a question tomorrow
     * cannot change how an answer graded today would grade again.
     */
    private EvaluationRequest buildRequest(EvaluateAnswerCommand command) {
        QuestionCatalog.QuestionVersionView version = questionCatalog
                .findVersion(command.questionVersionId())
                .orElseThrow(() -> ApplicationException.notFound(
                        "Pinned question version %s no longer exists."
                                .formatted(command.questionVersionId())));

        List<QuestionCatalog.RubricCriterionView> rubric =
                questionCatalog.findRubric(command.questionVersionId());
        if (rubric.isEmpty()) {
            throw ApplicationException.validation(
                    "Question version %s has no rubric; it cannot be graded."
                            .formatted(command.questionVersionId()));
        }

        return new EvaluationRequest(
                command.interviewId(),
                command.interviewQuestionId(),
                command.answerId(),
                version.id(),
                version.version(),
                new EvaluationRequest.QuestionSnapshot(version.skillId(), version.promptText(),
                        version.contextText(), version.referenceAnswer()),
                rubric.stream()
                        .map(c -> new EvaluationRequest.RubricCriterion(c.id(), c.code(),
                                c.label(), c.expectation(), c.weightBp(), c.tier()))
                        .toList(),
                new EvaluationRequest.CandidateAnswer(command.answerText(), command.inputMode()));
    }

    private UUID recordInvocation(EvaluateAnswerCommand command, EvaluationRequest request,
                                  EvaluationResult result, int latencyMs) {
        var metadata = result.metadata();
        String provider = metadata == null ? "unknown" : metadata.provider();
        String model = metadata == null ? "unknown" : metadata.model();
        String promptVersion = metadata == null ? "n/a" : metadata.promptVersion();

        AiInvocation.Builder builder = AiInvocation
                .builder(AiInvocation.Purpose.ANSWER_EVALUATION, provider, model)
                .promptVersion(promptVersion)
                .requestFingerprint(AiInvocationRecorder.fingerprint(
                        promptVersion, model, request.questionVersionId(),
                        request.answer().text()))
                .status(statusOf(result))
                .latencyMs(latencyMs)
                .errorDetail(result.errorDetail())
                .correlation(command.interviewId(), command.answerId(), null);

        if (metadata != null) {
            // Left null when unknown: zeros here would silently corrupt the
            // unit-economics figures this table exists to answer.
            builder.tokens(metadata.inputTokens(), metadata.outputTokens())
                    .costMicros(metadata.costMicros());
        }

        return invocationRecorder.record(builder.build());
    }

    private AiInvocation.Status statusOf(EvaluationResult result) {
        if (result.status() == EvaluationStatus.SUCCEEDED) {
            return AiInvocation.Status.SUCCEEDED;
        }
        if (result.status() == EvaluationStatus.FAILED_VALIDATION) {
            return AiInvocation.Status.INVALID_OUTPUT;
        }
        return switch (result.errorCode() == null ? "" : result.errorCode()) {
            case "TIMEOUT" -> AiInvocation.Status.TIMEOUT;
            case "RATE_LIMITED" -> AiInvocation.Status.RATE_LIMITED;
            default -> AiInvocation.Status.PROVIDER_ERROR;
        };
    }

    private EvaluationPhase phaseOf(EvaluationStatus status) {
        return status.isSuccess() ? EvaluationPhase.COMPLETED : EvaluationPhase.FAILED;
    }

    private EvaluationOutcome unchanged(EvaluationEntity entity) {
        return new EvaluationOutcome(entity.getId(), entity.getAnswerId(),
                EvaluationPhase.of(true, entity.getStatus()), entity.getDerivedScore(),
                0, true, null, entity.getAiInvocationId(), null);
    }
}
