package com.aiinterview.interviewplatform.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiinterview.interviewplatform.evaluation.api.AnswerEvaluationService;
import com.aiinterview.interviewplatform.evaluation.api.AnswerEvaluationService.EvaluationOutcome;
import com.aiinterview.interviewplatform.evaluation.api.EvaluateAnswerCommand;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationPhase;
import com.aiinterview.interviewplatform.support.AbstractDatabaseTest;
import com.aiinterview.interviewplatform.support.SchemaFixtures;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The evaluation pipeline against a real PostgreSQL.
 *
 * <p>Proves the parts a unit test cannot: that criterion results actually land
 * in their table, that the database's own idempotency constraint is what stops
 * a duplicate evaluation, and that an attempt grades against the question
 * version it pinned rather than whatever is published today.
 */
@DisplayName("Answer evaluation, end to end")
class AnswerEvaluationIntegrationTest extends AbstractDatabaseTest {

    /** A strong answer: it carries the terms every criterion expects. */
    private static final String STRONG_ANSWER = """
            HashMap is not synchronised, so concurrent writes can corrupt the table.
            ConcurrentHashMap forbids null keys and null values entirely.""";

    /** Covers the first criterion only. */
    private static final String PARTIAL_ANSWER =
            "HashMap is not synchronised and is unsafe for concurrent writes.";

    @Autowired
    private AnswerEvaluationService evaluationService;

    private SchemaFixtures fixtures;
    private UUID javaSkillId;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
        javaSkillId = fixtures.skillId("JAVA");
    }

    // ------------------------------------------------------------ fixtures

    /** A published question version whose rubric the deterministic provider can read. */
    private UUID publishQuestion(String key, Map<String, String> criteria) {
        UUID versionId = fixtures.insertDraftQuestionVersion(javaSkillId, key);
        int weight = 10000 / criteria.size();
        int remainder = 10000 - weight * criteria.size();
        int index = 0;
        for (Map.Entry<String, String> entry : criteria.entrySet()) {
            jdbc.update("""
                    INSERT INTO app.rubric_criteria
                        (id, question_version_id, code, label, expectation, weight_bp, tier,
                         sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, 'CORE', ?)
                    """, UUID.randomUUID(), versionId, entry.getKey(), entry.getKey(),
                    entry.getValue(), weight + (index == 0 ? remainder : 0), (short) index);
            index++;
        }
        fixtures.publishQuestionVersion(versionId);
        return versionId;
    }

    /**
     * Three criteria, because the database publish gate requires 3–10 — the
     * schema refusing a two-criterion rubric is the trigger doing its job.
     *
     * <p>Ordered, so weights land predictably: the remainder goes to the first.
     */
    private static Map<String, String> standardRubric() {
        Map<String, String> rubric = new LinkedHashMap<>();
        rubric.put("THREAD_SAFETY",
                "States that HashMap is not synchronised for concurrent writes");
        rubric.put("NULL_HANDLING",
                "States that ConcurrentHashMap forbids null keys and values");
        rubric.put("FAILURE_MODE",
                "Mentions the table can corrupt under concurrent writes");
        return rubric;
    }

    private record Attempt(UUID interviewId, UUID turnId, UUID answerId, UUID questionVersionId) {}

    private Attempt attemptWith(String label, UUID questionVersionId, String answerText) {
        String unique = label + "-" + UUID.randomUUID();
        UUID candidate = fixtures.insertUser(unique);
        UUID template = fixtures.insertPublishedTemplate("tpl-" + unique, 1, javaSkillId);
        UUID interview = fixtures.insertLiveInterview(candidate, template);
        UUID turn = fixtures.insertCoreTurn(interview, questionVersionId, javaSkillId, 1);
        fixtures.insertAnswer(turn, answerText);
        return new Attempt(interview, turn, turn, questionVersionId);
    }

    private EvaluateAnswerCommand commandFor(Attempt attempt, String answerText) {
        return EvaluateAnswerCommand.of(attempt.interviewId(), attempt.turnId(),
                attempt.answerId(), attempt.questionVersionId(), answerText);
    }

    // --------------------------------------------------------- end to end

    @Test
    @DisplayName("an answer is graded, scored and persisted with its criterion results")
    void gradesAndPersists() {
        UUID version = publishQuestion("q-e2e-" + UUID.randomUUID(), standardRubric());
        Attempt attempt = attemptWith("e2e", version, STRONG_ANSWER);

        EvaluationOutcome outcome =
                evaluationService.evaluateAnswer(commandFor(attempt, STRONG_ANSWER));

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.phase()).isEqualTo(EvaluationPhase.COMPLETED);
        assertThat(outcome.derivedScore()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(outcome.criterionCount()).isEqualTo(3);

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT * FROM app.evaluations WHERE id = ?", outcome.evaluationId());
        assertThat(stored.get("status")).isEqualTo("SUCCEEDED");
        assertThat(stored.get("is_current")).isEqualTo(true);
        assertThat(stored.get("answer_id")).isEqualTo(attempt.answerId());

        Integer criterionRows = jdbc.queryForObject(
                "SELECT count(*) FROM app.evaluation_criterion_results WHERE evaluation_id = ?",
                Integer.class, outcome.evaluationId());
        assertThat(criterionRows).isEqualTo(3);
    }

    @Test
    @DisplayName("every score carries the provenance needed to explain it later")
    void provenanceIsRecorded() {
        UUID version = publishQuestion("q-prov-" + UUID.randomUUID(), standardRubric());
        Attempt attempt = attemptWith("prov", version, STRONG_ANSWER);

        EvaluationOutcome outcome =
                evaluationService.evaluateAnswer(commandFor(attempt, STRONG_ANSWER));

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT evaluation_version, prompt_version, rubric_version, question_version_id,"
                        + " provider, model FROM app.evaluations WHERE id = ?",
                outcome.evaluationId());

        assertThat(stored.get("evaluation_version")).isEqualTo("eval-1.0.0");
        assertThat(stored.get("prompt_version")).isEqualTo("deterministic@1.0.0");
        assertThat(stored.get("rubric_version")).isEqualTo(1);
        assertThat(stored.get("question_version_id")).isEqualTo(version);
        assertThat(stored.get("provider")).isEqualTo("deterministic");
        assertThat(stored.get("model")).isEqualTo("deterministic-rubric-matcher-1");
    }

    @Test
    @DisplayName("evidence is stored with offsets that address the candidate's own words")
    void evidenceIsTraceableToTheAnswer() {
        UUID version = publishQuestion("q-evid-" + UUID.randomUUID(), standardRubric());
        Attempt attempt = attemptWith("evid", version, STRONG_ANSWER);

        EvaluationOutcome outcome =
                evaluationService.evaluateAnswer(commandFor(attempt, STRONG_ANSWER));

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT evidence_quote, evidence_start, evidence_end
                  FROM app.evaluation_criterion_results
                 WHERE evaluation_id = ? AND evidence_quote IS NOT NULL
                """, outcome.evaluationId());

        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            int start = (Integer) row.get("evidence_start");
            int end = (Integer) row.get("evidence_end");
            assertThat(end).isGreaterThan(start);
            // The stored offsets must select the stored quote from the answer.
            assertThat(STRONG_ANSWER.substring(start, end))
                    .isEqualTo(row.get("evidence_quote"));
        }
    }

    @Test
    @DisplayName("a weaker answer scores lower, from the same rubric")
    void weakerAnswerScoresLower() {
        UUID version = publishQuestion("q-weak-" + UUID.randomUUID(), standardRubric());
        Attempt strong = attemptWith("weak-strong", version, STRONG_ANSWER);
        Attempt partial = attemptWith("weak-partial", version, PARTIAL_ANSWER);

        BigDecimal strongScore = evaluationService
                .evaluateAnswer(commandFor(strong, STRONG_ANSWER)).derivedScore();
        BigDecimal partialScore = evaluationService
                .evaluateAnswer(commandFor(partial, PARTIAL_ANSWER)).derivedScore();

        assertThat(partialScore).isLessThan(strongScore);
    }

    // --------------------------------------------------------- idempotency

    @Test
    @DisplayName("evaluating the same answer twice does not create a second current evaluation")
    void repeatedEvaluationIsANoOp() {
        UUID version = publishQuestion("q-idem-" + UUID.randomUUID(), standardRubric());
        Attempt attempt = attemptWith("idem", version, STRONG_ANSWER);

        EvaluationOutcome first =
                evaluationService.evaluateAnswer(commandFor(attempt, STRONG_ANSWER));
        EvaluationOutcome second =
                evaluationService.evaluateAnswer(commandFor(attempt, STRONG_ANSWER));

        assertThat(second.alreadyEvaluated()).isTrue();
        assertThat(second.evaluationId()).isEqualTo(first.evaluationId());

        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM app.evaluations WHERE answer_id = ?",
                Integer.class, attempt.answerId());
        assertThat(total).isEqualTo(1);
    }

    @Test
    @DisplayName("a retried evaluation does not call the provider again")
    void repeatedEvaluationDoesNotSpendAgain() {
        UUID version = publishQuestion("q-nospend-" + UUID.randomUUID(), standardRubric());
        Attempt attempt = attemptWith("nospend", version, STRONG_ANSWER);

        evaluationService.evaluateAnswer(commandFor(attempt, STRONG_ANSWER));
        evaluationService.evaluateAnswer(commandFor(attempt, STRONG_ANSWER));

        Integer invocations = jdbc.queryForObject(
                "SELECT count(*) FROM app.ai_invocations WHERE answer_id = ?",
                Integer.class, attempt.answerId());
        assertThat(invocations).isEqualTo(1);
    }

    @Test
    @DisplayName("re-evaluation supersedes rather than overwrites")
    void reevaluationIsAppendOnly() {
        UUID version = publishQuestion("q-reeval-" + UUID.randomUUID(), standardRubric());
        Attempt attempt = attemptWith("reeval", version, STRONG_ANSWER);
        UUID admin = fixtures.insertUser("admin-" + UUID.randomUUID());

        EvaluationOutcome first =
                evaluationService.evaluateAnswer(commandFor(attempt, STRONG_ANSWER));

        EvaluationOutcome second = evaluationService.evaluateAnswer(new EvaluateAnswerCommand(
                attempt.interviewId(), attempt.turnId(), attempt.answerId(), version,
                STRONG_ANSWER, "TEXT", true, admin));

        assertThat(second.alreadyEvaluated()).isFalse();
        assertThat(second.supersededId()).isEqualTo(first.evaluationId());
        assertThat(second.evaluationId()).isNotEqualTo(first.evaluationId());

        // Both rows survive; exactly one is current.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.evaluations WHERE answer_id = ?",
                Integer.class, attempt.answerId())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.evaluations WHERE answer_id = ? AND is_current",
                Integer.class, attempt.answerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT is_current FROM app.evaluations WHERE id = ?",
                Boolean.class, first.evaluationId())).isFalse();
        // The original result is intact, not rewritten.
        assertThat(jdbc.queryForObject(
                "SELECT triggered_by FROM app.evaluations WHERE id = ?",
                UUID.class, second.evaluationId())).isEqualTo(admin);
    }

    @Test
    @DisplayName("phase is derived from what is stored")
    void phaseReflectsStoredState() {
        UUID version = publishQuestion("q-phase-" + UUID.randomUUID(), standardRubric());
        Attempt attempt = attemptWith("phase", version, STRONG_ANSWER);

        assertThat(evaluationService.phaseOf(attempt.answerId()))
                .isEqualTo(EvaluationPhase.PENDING);

        evaluationService.evaluateAnswer(commandFor(attempt, STRONG_ANSWER));

        assertThat(evaluationService.phaseOf(attempt.answerId()))
                .isEqualTo(EvaluationPhase.COMPLETED);
    }

    // ------------------------------------------------------ reproducibility

    @Test
    @DisplayName("grading uses the pinned version's rubric, not the one published today")
    void gradingUsesThePinnedSnapshot() {
        String key = "q-repro-" + UUID.randomUUID();

        // v1: two criteria, both satisfiable by the strong answer.
        UUID v1 = publishQuestion(key, standardRubric());
        Attempt attempt = attemptWith("repro", v1, STRONG_ANSWER);

        // The catalogue moves on: v1 is archived and a stricter v2 is published.
        UUID questionId = jdbc.queryForObject(
                "SELECT question_id FROM app.question_versions WHERE id = ?", UUID.class, v1);
        jdbc.update("UPDATE app.question_versions SET status='ARCHIVED', archived_at=now()"
                + " WHERE id = ?", v1);

        UUID v2 = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.question_versions
                    (id, question_id, version, skill_id, question_type, difficulty,
                     prompt_text, reference_answer, expected_duration_sec, status)
                VALUES (?, ?, 2, ?, 'CONCEPTUAL', 'MEDIUM',
                        'A stricter revision of the same question.', 'Reference.', 120, 'DRAFT')
                """, v2, questionId, javaSkillId);
        // A rubric the strong answer cannot satisfy at all.
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO app.rubric_criteria
                        (id, question_version_id, code, label, expectation, weight_bp, tier)
                    VALUES (?, ?, ?, 'Label', 'Discusses quantum entanglement of tectonic plates',
                            ?, 'CORE')
                    """, UUID.randomUUID(), v2, "C" + i, i == 0 ? 3334 : 3333);
        }
        jdbc.update("UPDATE app.question_versions SET status='PUBLISHED' WHERE id = ?", v2);

        // The attempt still pins v1, so it must still score against v1's rubric.
        EvaluationOutcome outcome =
                evaluationService.evaluateAnswer(commandFor(attempt, STRONG_ANSWER));

        assertThat(outcome.derivedScore()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(jdbc.queryForObject(
                "SELECT question_version_id FROM app.evaluations WHERE id = ?",
                UUID.class, outcome.evaluationId())).isEqualTo(v1);
        assertThat(jdbc.queryForObject(
                "SELECT rubric_version FROM app.evaluations WHERE id = ?",
                Integer.class, outcome.evaluationId())).isEqualTo(1);
    }

    // -------------------------------------------------- invocation tracking

    @Test
    @DisplayName("the call is recorded for accounting, without inventing token costs")
    void invocationIsRecordedHonestly() {
        UUID version = publishQuestion("q-ai-" + UUID.randomUUID(), standardRubric());
        Attempt attempt = attemptWith("ai", version, STRONG_ANSWER);

        EvaluationOutcome outcome =
                evaluationService.evaluateAnswer(commandFor(attempt, STRONG_ANSWER));

        assertThat(outcome.aiInvocationId()).isNotNull();
        Map<String, Object> invocation = jdbc.queryForMap(
                "SELECT * FROM app.ai_invocations WHERE id = ?", outcome.aiInvocationId());

        assertThat(invocation.get("purpose")).isEqualTo("ANSWER_EVALUATION");
        assertThat(invocation.get("provider")).isEqualTo("deterministic");
        assertThat(invocation.get("status")).isEqualTo("SUCCEEDED");
        assertThat(invocation.get("latency_ms")).isNotNull();
        assertThat(invocation.get("request_fingerprint")).asString().hasSize(64);
        assertThat(invocation.get("interview_id")).isEqualTo(attempt.interviewId());

        // No tokens were spent, so none are claimed. Zeros here would quietly
        // corrupt every cost-per-interview figure computed from this table.
        assertThat(invocation.get("input_tokens")).isNull();
        assertThat(invocation.get("output_tokens")).isNull();
        assertThat(invocation.get("cost_micros")).isNull();

        // The evaluation links to its invocation, so a score stays explainable.
        assertThat(jdbc.queryForObject(
                "SELECT ai_invocation_id FROM app.evaluations WHERE id = ?",
                UUID.class, outcome.evaluationId())).isEqualTo(outcome.aiInvocationId());
    }
}
