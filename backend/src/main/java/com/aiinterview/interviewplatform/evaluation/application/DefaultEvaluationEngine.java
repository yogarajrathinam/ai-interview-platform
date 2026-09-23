package com.aiinterview.interviewplatform.evaluation.application;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationEngine;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationRequest;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationResult;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationResult.CriterionOutcome;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationStatus;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationProvider;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationProviderException;
import com.aiinterview.interviewplatform.evaluation.api.ProviderEvaluation;
import com.aiinterview.interviewplatform.evaluation.domain.AnswerScorer;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Grades one answer: ask the provider, validate what comes back, derive the score.
 *
 * <p>Persists nothing and touches no database, which is what lets the admin
 * dry-run tool reuse it unchanged.
 *
 * <p>Never throws for a provider or validation failure. Both are legitimate
 * outcomes of grading and are reported as a non-success result, because the
 * caller's job is to record what happened — not to handle an exception for
 * something that was always possible.
 */
@Service
public class DefaultEvaluationEngine implements EvaluationEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultEvaluationEngine.class);

    private final EvaluationProvider provider;
    private final ProviderResultValidator validator;

    public DefaultEvaluationEngine(EvaluationProvider provider,
                                   ProviderResultValidator validator) {
        this.provider = provider;
        this.validator = validator;
    }

    @Override
    public EvaluationResult evaluate(EvaluationRequest request) {
        ProviderEvaluation providerEvaluation;
        try {
            providerEvaluation = provider.evaluate(request);
        } catch (EvaluationProviderException e) {
            // Our problem, not the candidate's. The answer is already stored, so
            // nothing is lost and the attempt stays re-drivable.
            log.warn("Provider {} failed for questionVersion={}: {}",
                    provider.providerName(), request.questionVersionId(), e.reason());
            return failure(EvaluationStatus.FAILED_PROVIDER, e.reason().name(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("Provider {} threw an unexpected exception", provider.providerName(), e);
            return failure(EvaluationStatus.FAILED_PROVIDER, "PROVIDER_ERROR", e.getMessage());
        }

        ProviderResultValidator.ValidationOutcome validation =
                validator.validate(request, providerEvaluation);
        if (!validation.valid()) {
            log.warn("Provider {} returned an unusable result for questionVersion={}: {}",
                    provider.providerName(), request.questionVersionId(), validation.errorCode());
            return failure(EvaluationStatus.FAILED_VALIDATION,
                    validation.errorCode(), validation.errorDetail());
        }

        List<CriterionOutcome> criteria = validation.criteria();
        BigDecimal derivedScore = AnswerScorer.score(criteria.stream()
                .map(c -> new AnswerScorer.ScoredCriterion(
                        c.criterionId(), c.verdict(), c.credit(), c.weightBp()))
                .toList());

        BigDecimal modelScore = validator.sanitiseModelScore(
                providerEvaluation.modelReportedScore());
        if (validator.divergesFromModel(derivedScore, modelScore)) {
            // Logged, never acted on: our score is the score. This is a quality
            // signal about the provider, tracked so drift becomes visible.
            log.warn("Score divergence: derived={} modelReported={} questionVersion={}",
                    derivedScore, modelScore, request.questionVersionId());
        }

        return new EvaluationResult(
                EvaluationStatus.SUCCEEDED,
                derivedScore,
                criteria,
                providerEvaluation.summary(),
                providerEvaluation.confidence(),
                providerEvaluation.followUpNeeded(),
                providerEvaluation.followUpReason(),
                resolveFollowUpTarget(request, providerEvaluation),
                modelScore,
                providerEvaluation.injectionSuspected(),
                providerEvaluation.answerOffTopic(),
                providerEvaluation.metadata(),
                null,
                null);
    }

    /**
     * A follow-up target naming a criterion outside this rubric is dropped
     * rather than stored. It would otherwise become a foreign key to something
     * unrelated, and the interview engine would probe the wrong weakness.
     */
    private UUID resolveFollowUpTarget(EvaluationRequest request,
                                       ProviderEvaluation evaluation) {
        UUID target = evaluation.followUpTargetCriterionId();
        if (target == null) {
            return null;
        }
        Set<UUID> known = request.rubric().stream()
                .map(EvaluationRequest.RubricCriterion::id)
                .collect(Collectors.toSet());
        if (!known.contains(target)) {
            log.warn("Provider named an unknown follow-up criterion {}; dropping it", target);
            return null;
        }
        return target;
    }

    /**
     * A failure still carries provider identity.
     *
     * <p>Without it the accounting row would record {@code provider=unknown},
     * and "which provider is failing, and how often?" — the question the table
     * exists to answer — would be unanswerable precisely when it matters.
     * Model and prompt version are genuinely unknown here and say so.
     */
    private EvaluationResult failure(EvaluationStatus status, String code, String detail) {
        ProviderEvaluation.ProviderMetadata metadata = new ProviderEvaluation.ProviderMetadata(
                provider.providerName(), "unknown", "unknown", null, null, null);
        return new EvaluationResult(status, null, List.of(), null, null, false, null, null,
                null, false, false, metadata, code, truncate(detail));
    }

    /** Provider messages can be long; the column is for diagnosis, not prose. */
    private String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= 500 ? detail : detail.substring(0, 500);
    }
}
