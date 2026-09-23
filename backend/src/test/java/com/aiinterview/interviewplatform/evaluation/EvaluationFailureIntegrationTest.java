package com.aiinterview.interviewplatform.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiinterview.interviewplatform.evaluation.api.AnswerEvaluationService;
import com.aiinterview.interviewplatform.evaluation.api.AnswerEvaluationService.EvaluationOutcome;
import com.aiinterview.interviewplatform.evaluation.api.EvaluateAnswerCommand;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationPhase;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationProvider;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationProviderException;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationRequest;
import com.aiinterview.interviewplatform.evaluation.api.ProviderEvaluation;
import com.aiinterview.interviewplatform.support.AbstractDatabaseTest;
import com.aiinterview.interviewplatform.support.SchemaFixtures;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * What a provider outage leaves behind.
 *
 * <p>The requirement is not that failures are rare; it is that they are
 * survivable and visible. The candidate's answer was stored before grading
 * began, so nothing they wrote can be lost — and what must remain afterwards is
 * an honest record: a failed evaluation, no fabricated verdicts, and an
 * accounting row showing the call happened.
 *
 * <p>A separate context is used so the failing provider can take
 * {@code @Primary} without affecting the rest of the suite.
 */
@DisplayName("Provider failure handling")
@Import(EvaluationFailureIntegrationTest.AlwaysTimesOut.class)
class EvaluationFailureIntegrationTest extends AbstractDatabaseTest {

    @TestConfiguration
    static class AlwaysTimesOut {

        @Bean
        @Primary
        EvaluationProvider failingProvider() {
            return new EvaluationProvider() {
                @Override
                public ProviderEvaluation evaluate(EvaluationRequest request) {
                    throw new EvaluationProviderException(
                            EvaluationProviderException.Reason.TIMEOUT,
                            "read timed out after 30000ms");
                }

                @Override
                public String providerName() {
                    return "always-times-out";
                }
            };
        }
    }

    private static final String ANSWER =
            "HashMap is not synchronised, so concurrent writes can corrupt the table.";

    @Autowired
    private AnswerEvaluationService evaluationService;

    private SchemaFixtures fixtures;
    private UUID javaSkillId;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
        javaSkillId = fixtures.skillId("JAVA");
    }

    private EvaluationOutcome evaluateFailing(String label) {
        String unique = label + "-" + UUID.randomUUID();
        UUID versionId = fixtures.insertPublishedQuestionVersion(javaSkillId, "q-" + unique);
        UUID candidate = fixtures.insertUser(unique);
        UUID template = fixtures.insertPublishedTemplate("tpl-" + unique, 1, javaSkillId);
        UUID interview = fixtures.insertLiveInterview(candidate, template);
        UUID turn = fixtures.insertCoreTurn(interview, versionId, javaSkillId, 1);
        fixtures.insertAnswer(turn, ANSWER);

        return evaluationService.evaluateAnswer(
                EvaluateAnswerCommand.of(interview, turn, turn, versionId, ANSWER));
    }

    @Test
    @DisplayName("a provider timeout is recorded as a failed evaluation, not an exception")
    void failureIsRecorded() {
        EvaluationOutcome outcome = evaluateFailing("timeout");

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.phase()).isEqualTo(EvaluationPhase.FAILED);
        assertThat(outcome.derivedScore()).isNull();

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT status, error_code, derived_score, is_current"
                        + " FROM app.evaluations WHERE id = ?", outcome.evaluationId());

        assertThat(stored.get("status")).isEqualTo("FAILED_PROVIDER");
        assertThat(stored.get("error_code")).isEqualTo("TIMEOUT");
        assertThat(stored.get("derived_score")).isNull();
        assertThat(stored.get("is_current")).isEqualTo(true);
    }

    @Test
    @DisplayName("a failed evaluation stores no criterion results")
    void noVerdictsAreFabricated() {
        EvaluationOutcome outcome = evaluateFailing("no-verdicts");

        assertThat(outcome.criterionCount()).isZero();
        // Recording verdicts we never obtained would be evidence of grading
        // that did not happen.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.evaluation_criterion_results WHERE evaluation_id = ?",
                Integer.class, outcome.evaluationId())).isZero();
    }

    @Test
    @DisplayName("the failed call still appears in the accounting table")
    void failedCallIsStillAccountedFor() {
        EvaluationOutcome outcome = evaluateFailing("accounting");

        assertThat(outcome.aiInvocationId()).isNotNull();
        Map<String, Object> invocation = jdbc.queryForMap(
                "SELECT status, provider, error_detail FROM app.ai_invocations WHERE id = ?",
                outcome.aiInvocationId());

        // A provider that fails 30% of the time must be visible in this table.
        assertThat(invocation.get("status")).isEqualTo("TIMEOUT");
        assertThat(invocation.get("provider")).isEqualTo("always-times-out");
        assertThat(invocation.get("error_detail")).asString().contains("timed out");
    }

    @Test
    @DisplayName("the candidate's answer survives the failure untouched")
    void answerIsNeverLost() {
        EvaluationOutcome outcome = evaluateFailing("answer-safe");

        assertThat(jdbc.queryForObject(
                "SELECT content_text FROM app.answers WHERE interview_question_id = ?",
                String.class, outcome.answerId())).isEqualTo(ANSWER);
    }

    @Test
    @DisplayName("a failed evaluation can be re-driven once the provider recovers")
    void failureIsRetryable() {
        EvaluationOutcome first = evaluateFailing("redrive");

        assertThat(evaluationService.phaseOf(first.answerId()))
                .isEqualTo(EvaluationPhase.FAILED);
        assertThat(EvaluationPhase.FAILED.canTransitionTo(EvaluationPhase.PROCESSING)).isTrue();
    }
}
