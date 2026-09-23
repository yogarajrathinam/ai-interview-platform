package com.aiinterview.interviewplatform.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationProvider;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationProviderException;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationRequest;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationResult;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationStatus;
import com.aiinterview.interviewplatform.evaluation.api.ProviderEvaluation;
import com.aiinterview.interviewplatform.evaluation.api.Verdict;
import com.aiinterview.interviewplatform.evaluation.application.DefaultEvaluationEngine;
import com.aiinterview.interviewplatform.evaluation.application.EvidenceValidator;
import com.aiinterview.interviewplatform.evaluation.application.ProviderResultValidator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The engine's contract with an untrustworthy provider.
 *
 * <p>Stub providers are used rather than the deterministic one because the
 * behaviour under test is precisely what happens when a provider misbehaves —
 * fabricating evidence, inventing criteria, timing out, or claiming a score.
 * The deterministic provider is well-behaved by construction and cannot
 * exercise any of it.
 */
@DisplayName("Evaluation engine")
class EvaluationEngineTest {

    private static final String ANSWER =
            "HashMap is not synchronised, so concurrent writes can corrupt the table.";

    private static final UUID CRITERION_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID CRITERION_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final ProviderResultValidator validator =
            new ProviderResultValidator(new EvidenceValidator());

    // ------------------------------------------------------------ fixtures

    private static EvaluationRequest request(String answerText) {
        return new EvaluationRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1,
                new EvaluationRequest.QuestionSnapshot(UUID.randomUUID(),
                        "What is the difference between HashMap and ConcurrentHashMap?",
                        null, "HashMap is not thread-safe."),
                List.of(
                        new EvaluationRequest.RubricCriterion(CRITERION_A, "THREAD_SAFETY",
                                "HashMap is not thread-safe",
                                "States that HashMap is not thread-safe", 6000, "CORE"),
                        new EvaluationRequest.RubricCriterion(CRITERION_B, "NULL_HANDLING",
                                "ConcurrentHashMap rejects nulls",
                                "States that ConcurrentHashMap forbids null keys", 4000, "CORE")),
                new EvaluationRequest.CandidateAnswer(answerText, "TEXT"));
    }

    private static ProviderEvaluation.ProviderMetadata metadata() {
        return new ProviderEvaluation.ProviderMetadata("stub", "stub-1", "stub@1", null, null, null);
    }

    private static ProviderEvaluation evaluation(
            List<ProviderEvaluation.CriterionJudgement> judgements, BigDecimal modelScore) {
        return new ProviderEvaluation(judgements, "summary", new BigDecimal("0.9"),
                false, null, null, modelScore, false, false, metadata());
    }

    private static ProviderEvaluation.CriterionJudgement judge(
            UUID criterionId, Verdict verdict, String quote) {
        return new ProviderEvaluation.CriterionJudgement(
                criterionId, verdict, new BigDecimal("0.9"), quote, null);
    }

    /** A provider whose behaviour the test dictates entirely. */
    private DefaultEvaluationEngine engineReturning(
            Function<EvaluationRequest, ProviderEvaluation> behaviour) {
        return new DefaultEvaluationEngine(new EvaluationProvider() {
            @Override
            public ProviderEvaluation evaluate(EvaluationRequest request) {
                return behaviour.apply(request);
            }

            @Override
            public String providerName() {
                return "stub";
            }
        }, validator);
    }

    private DefaultEvaluationEngine engineThrowing(RuntimeException failure) {
        return engineReturning(request -> {
            throw failure;
        });
    }

    // ---------------------------------------------------------- happy path

    @Test
    @DisplayName("well-evidenced judgements produce a validated, scored result")
    void happyPath() {
        EvaluationResult result = engineReturning(request -> evaluation(List.of(
                judge(CRITERION_A, Verdict.MET, "HashMap is not synchronised"),
                judge(CRITERION_B, Verdict.MISSING, null)), null))
                .evaluate(request(ANSWER));

        assertThat(result.status()).isEqualTo(EvaluationStatus.SUCCEEDED);
        assertThat(result.derivedScore()).isEqualByComparingTo(new BigDecimal("6.00"));
        assertThat(result.criteria()).hasSize(2);
        assertThat(result.strengths()).hasSize(1);
        assertThat(result.weaknesses()).hasSize(1);
        assertThat(result.missingConcepts()).hasSize(1);
        assertThat(result.criteria().get(0).evidence()).isNotNull();
    }

    // ------------------------------------------------- the central property

    @Test
    @DisplayName("the provider's own score is ignored — the backend decides")
    void providerScoreNeverDeterminesTheResult() {
        EvaluationResult result = engineReturning(request -> evaluation(List.of(
                judge(CRITERION_A, Verdict.MISSING, null),
                judge(CRITERION_B, Verdict.MISSING, null)),
                // The provider insists on full marks.
                new BigDecimal("10.00")))
                .evaluate(request(ANSWER));

        assertThat(result.derivedScore()).isEqualByComparingTo(new BigDecimal("0.00"));
        // Retained only as a divergence signal, never as an input.
        assertThat(result.modelReportedScore()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("an out-of-range provider score is discarded rather than clamped")
    void impossibleProviderScoreIsDropped() {
        EvaluationResult result = engineReturning(request -> evaluation(
                List.of(judge(CRITERION_A, Verdict.MET, "HashMap is not synchronised"),
                        judge(CRITERION_B, Verdict.MISSING, null)),
                new BigDecimal("42.00")))
                .evaluate(request(ANSWER));

        assertThat(result.modelReportedScore()).isNull();
        assertThat(result.status()).isEqualTo(EvaluationStatus.SUCCEEDED);
    }

    // ------------------------------------------------------------ evidence

    @Test
    @DisplayName("a fabricated quote downgrades the verdict instead of earning credit")
    void hallucinatedEvidenceIsDowngraded() {
        EvaluationResult result = engineReturning(request -> evaluation(List.of(
                judge(CRITERION_A, Verdict.MET, "ConcurrentHashMap forbids null keys"),
                judge(CRITERION_B, Verdict.MISSING, null)), null))
                .evaluate(request(ANSWER));

        EvaluationResult.CriterionOutcome outcome = result.criteria().get(0);
        assertThat(outcome.verdict()).isEqualTo(Verdict.PARTIAL);
        assertThat(outcome.evidenceRejected()).isTrue();
        assertThat(outcome.evidence()).isNull();
        // 6000 x 0.5 / 10000 = 3.00, not the 6.00 an unchecked MET would have paid.
        assertThat(result.derivedScore()).isEqualByComparingTo(new BigDecimal("3.00"));
    }

    @Test
    @DisplayName("a met verdict with no quote at all is downgraded")
    void missingEvidenceIsDowngraded() {
        EvaluationResult result = engineReturning(request -> evaluation(List.of(
                judge(CRITERION_A, Verdict.MET, null),
                judge(CRITERION_B, Verdict.MISSING, null)), null))
                .evaluate(request(ANSWER));

        assertThat(result.criteria().get(0).verdict()).isEqualTo(Verdict.PARTIAL);
        assertThat(result.criteria().get(0).evidenceRejected()).isTrue();
    }

    @Test
    @DisplayName("an unevidenced contradiction costs the candidate nothing")
    void unevidencedContradictionBecomesMissing() {
        EvaluationResult result = engineReturning(request -> evaluation(List.of(
                judge(CRITERION_A, Verdict.CONTRADICTED, "something never written"),
                judge(CRITERION_B, Verdict.MISSING, null)), null))
                .evaluate(request(ANSWER));

        assertThat(result.criteria().get(0).verdict()).isEqualTo(Verdict.MISSING);
        assertThat(result.derivedScore()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    // --------------------------------------------- malformed criterion sets

    @Test
    @DisplayName("an incomplete criterion set invalidates the whole evaluation")
    void incompleteCriterionSetIsRejected() {
        EvaluationResult result = engineReturning(request -> evaluation(
                List.of(judge(CRITERION_A, Verdict.MET, "HashMap is not synchronised")), null))
                .evaluate(request(ANSWER));

        assertThat(result.status()).isEqualTo(EvaluationStatus.FAILED_VALIDATION);
        assertThat(result.errorCode()).isEqualTo("INCOMPLETE_CRITERIA");
        assertThat(result.derivedScore()).isNull();
        assertThat(result.criteria()).isEmpty();
    }

    @Test
    @DisplayName("an invented criterion invalidates the whole evaluation")
    void unknownCriterionIsRejected() {
        EvaluationResult result = engineReturning(request -> evaluation(List.of(
                judge(CRITERION_A, Verdict.MET, "HashMap is not synchronised"),
                judge(CRITERION_B, Verdict.MET, "HashMap is not synchronised"),
                judge(UUID.randomUUID(), Verdict.MET, "HashMap is not synchronised")), null))
                .evaluate(request(ANSWER));

        assertThat(result.status()).isEqualTo(EvaluationStatus.FAILED_VALIDATION);
        assertThat(result.errorCode()).isEqualTo("UNKNOWN_CRITERIA");
    }

    @Test
    @DisplayName("a repeated criterion invalidates the whole evaluation")
    void duplicateCriterionIsRejected() {
        EvaluationResult result = engineReturning(request -> evaluation(List.of(
                judge(CRITERION_A, Verdict.MET, "HashMap is not synchronised"),
                judge(CRITERION_A, Verdict.MISSING, null),
                judge(CRITERION_B, Verdict.MISSING, null)), null))
                .evaluate(request(ANSWER));

        assertThat(result.status()).isEqualTo(EvaluationStatus.FAILED_VALIDATION);
        assertThat(result.errorCode()).isEqualTo("DUPLICATE_CRITERIA");
    }

    @Test
    @DisplayName("a null result is rejected rather than dereferenced")
    void nullResultIsRejected() {
        EvaluationResult result = engineReturning(request -> null).evaluate(request(ANSWER));

        assertThat(result.status()).isEqualTo(EvaluationStatus.FAILED_VALIDATION);
        assertThat(result.errorCode()).isEqualTo("EMPTY_RESULT");
    }

    // ---------------------------------------------------- provider failures

    @Test
    @DisplayName("a provider timeout is an outcome, not an exception")
    void providerTimeoutIsReported() {
        EvaluationResult result = engineThrowing(new EvaluationProviderException(
                EvaluationProviderException.Reason.TIMEOUT, "read timed out after 30s"))
                .evaluate(request(ANSWER));

        assertThat(result.status()).isEqualTo(EvaluationStatus.FAILED_PROVIDER);
        assertThat(result.errorCode()).isEqualTo("TIMEOUT");
        assertThat(result.derivedScore()).isNull();
    }

    @Test
    @DisplayName("an unexpected provider exception does not escape the engine")
    void unexpectedProviderExceptionIsContained() {
        EvaluationResult result =
                engineThrowing(new IllegalStateException("SDK blew up")).evaluate(request(ANSWER));

        assertThat(result.status()).isEqualTo(EvaluationStatus.FAILED_PROVIDER);
        assertThat(result.errorCode()).isEqualTo("PROVIDER_ERROR");
    }

    @Test
    @DisplayName("rate limiting is distinguishable from a hard error")
    void rateLimitingIsReportedDistinctly() {
        EvaluationResult result = engineThrowing(new EvaluationProviderException(
                EvaluationProviderException.Reason.RATE_LIMITED, "429"))
                .evaluate(request(ANSWER));

        assertThat(result.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(EvaluationProviderException.Reason.RATE_LIMITED.isRetryable()).isTrue();
        assertThat(EvaluationProviderException.Reason.INVALID_OUTPUT.isRetryable()).isFalse();
    }

    // -------------------------------------------------------- other guards

    @Test
    @DisplayName("a blank answer scores zero without consulting the verdicts")
    void blankAnswerCannotEarnCredit() {
        EvaluationResult result = engineReturning(request -> evaluation(List.of(
                // A provider claiming full marks for an empty box.
                judge(CRITERION_A, Verdict.MET, "anything"),
                judge(CRITERION_B, Verdict.MET, "anything")), null))
                .evaluate(request("   "));

        assertThat(result.status()).isEqualTo(EvaluationStatus.SUCCEEDED);
        assertThat(result.derivedScore()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(result.criteria()).allMatch(c -> c.verdict() == Verdict.MISSING);
    }

    @Test
    @DisplayName("a follow-up target outside the rubric is dropped")
    void unknownFollowUpTargetIsDropped() {
        EvaluationResult result = engineReturning(request -> new ProviderEvaluation(
                List.of(judge(CRITERION_A, Verdict.MET, "HashMap is not synchronised"),
                        judge(CRITERION_B, Verdict.MISSING, null)),
                "summary", new BigDecimal("0.9"), true, "probe deeper",
                UUID.randomUUID(), null, false, false, metadata()))
                .evaluate(request(ANSWER));

        assertThat(result.followUpNeeded()).isTrue();
        assertThat(result.followUpTargetCriterionId()).isNull();
    }

    @Test
    @DisplayName("a valid follow-up target is preserved")
    void knownFollowUpTargetIsKept() {
        EvaluationResult result = engineReturning(request -> new ProviderEvaluation(
                List.of(judge(CRITERION_A, Verdict.MET, "HashMap is not synchronised"),
                        judge(CRITERION_B, Verdict.MISSING, null)),
                "summary", new BigDecimal("0.9"), true, "probe deeper",
                CRITERION_B, null, false, false, metadata()))
                .evaluate(request(ANSWER));

        assertThat(result.followUpTargetCriterionId()).isEqualTo(CRITERION_B);
    }

    @Test
    @DisplayName("out-of-range confidence is clamped, not trusted")
    void confidenceIsClamped() {
        List<ProviderEvaluation.CriterionJudgement> judgements = new ArrayList<>();
        judgements.add(new ProviderEvaluation.CriterionJudgement(
                CRITERION_A, Verdict.MET, new BigDecimal("7.5"),
                "HashMap is not synchronised", null));
        judgements.add(new ProviderEvaluation.CriterionJudgement(
                CRITERION_B, Verdict.MISSING, new BigDecimal("-3"), null, null));

        EvaluationResult result =
                engineReturning(request -> evaluation(judgements, null)).evaluate(request(ANSWER));

        assertThat(result.criteria().get(0).confidence())
                .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.criteria().get(1).confidence())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
