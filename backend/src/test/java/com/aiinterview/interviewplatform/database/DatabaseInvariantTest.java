package com.aiinterview.interviewplatform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiinterview.interviewplatform.support.AbstractDatabaseTest;
import com.aiinterview.interviewplatform.support.SchemaFixtures;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Proves that <strong>PostgreSQL</strong> rejects each approved invariant
 * violation.
 *
 * <p>Every test writes with plain JDBC, bypassing JPA and Bean Validation
 * entirely. That is the point: an invariant enforced only in Java is one
 * forgotten service method away from being violated, and these rules protect
 * candidate scores.
 *
 * <p>No {@code @Transactional}: statements autocommit, so a rejected write
 * cannot poison a surrounding transaction and each assertion observes the real
 * engine behaviour.
 */
@DisplayName("Database-enforced invariants")
class DatabaseInvariantTest extends AbstractDatabaseTest {

    private SchemaFixtures fixtures;
    private UUID javaSkillId;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
        javaSkillId = fixtures.skillId("JAVA");
    }

    private static String sqlState(Throwable thrown) {
        Throwable cursor = thrown;
        while (cursor != null) {
            if (cursor instanceof java.sql.SQLException sqlException) {
                return sqlException.getSQLState();
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    // ------------------------------------------------------------------ 1

    @Test
    @DisplayName("1. a candidate cannot hold two live interviews")
    void twoLiveInterviewsAreRejected() {
        UUID candidate = fixtures.insertUser("live-" + UUID.randomUUID());
        UUID template = fixtures.insertPublishedTemplate(
                "tpl-live-" + UUID.randomUUID(), 1, javaSkillId);

        fixtures.insertLiveInterview(candidate, template);

        assertThatThrownBy(() -> fixtures.insertLiveInterview(candidate, template))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));
    }

    @Test
    @DisplayName("1b. the same candidate may start again once the first attempt is terminal")
    void anotherInterviewIsAllowedAfterCompletion() {
        UUID candidate = fixtures.insertUser("relive-" + UUID.randomUUID());
        UUID template = fixtures.insertPublishedTemplate(
                "tpl-relive-" + UUID.randomUUID(), 1, javaSkillId);

        UUID first = fixtures.insertLiveInterview(candidate, template);
        jdbc.update("""
                UPDATE app.interviews
                   SET status = 'COMPLETED', completion_reason = 'CANDIDATE_FINISHED',
                       completed_at = now()
                 WHERE id = ?
                """, first);

        // The partial index covers only live states, so this must succeed.
        assertThat(fixtures.insertLiveInterview(candidate, template)).isNotNull();
    }

    // ------------------------------------------------------------------ 2

    @Test
    @DisplayName("2. a turn cannot have two answers")
    void twoAnswersForOneTurnAreRejected() {
        UUID turn = aTurnWithAnswer("dup-answer").turnId();

        assertThatThrownBy(() -> fixtures.insertAnswer(turn, "a second answer"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));
    }

    // ------------------------------------------------------------------ 3

    @Test
    @DisplayName("3. an answer cannot have two current evaluations")
    void twoCurrentEvaluationsAreRejected() {
        Fixture f = aTurnWithAnswer("dup-eval");
        fixtures.insertEvaluation(f.turnId(), f.questionVersionId(), true);

        assertThatThrownBy(() -> fixtures.insertEvaluation(f.turnId(), f.questionVersionId(), true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));
    }

    @Test
    @DisplayName("3b. a superseded evaluation may coexist with the current one")
    void supersededEvaluationsAreAllowed() {
        Fixture f = aTurnWithAnswer("supersede");
        UUID first = fixtures.insertEvaluation(f.turnId(), f.questionVersionId(), true);

        // Re-evaluation is append-only: flip the old row, then insert the new.
        jdbc.update("UPDATE app.evaluations SET is_current = false WHERE id = ?", first);
        UUID second = fixtures.insertEvaluation(f.turnId(), f.questionVersionId(), true);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.evaluations WHERE answer_id = ?", Integer.class,
                f.turnId())).isEqualTo(2);
        assertThat(second).isNotEqualTo(first);
    }

    // ------------------------------------------------------------------ 4

    @Test
    @DisplayName("4. a template family cannot have two published versions")
    void twoPublishedTemplateVersionsAreRejected() {
        String key = "tpl-twice-" + UUID.randomUUID();
        fixtures.insertPublishedTemplate(key, 1, javaSkillId);

        UUID second = fixtures.insertDraftTemplate(key, 2);
        fixtures.addTemplateSkill(second, javaSkillId, 10000);
        fixtures.addPooledSlots(second, javaSkillId, 3);

        assertThatThrownBy(() -> fixtures.publishTemplate(second))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));
    }

    @Test
    @DisplayName("4b. a question family cannot have two published versions")
    void twoPublishedQuestionVersionsAreRejected() {
        UUID first = fixtures.insertPublishedQuestionVersion(
                javaSkillId, "q-twice-" + UUID.randomUUID());
        UUID questionId = jdbc.queryForObject(
                "SELECT question_id FROM app.question_versions WHERE id = ?",
                UUID.class, first);

        UUID second = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.question_versions
                    (id, question_id, version, skill_id, question_type, difficulty,
                     prompt_text, reference_answer, expected_duration_sec, status)
                VALUES (?, ?, 2, ?, 'CONCEPTUAL', 'MEDIUM',
                        'A revised wording of the same question.',
                        'Reference answer.', 120, 'DRAFT')
                """, second, questionId, javaSkillId);
        fixtures.addRubricCriteria(second, 3);

        assertThatThrownBy(() -> fixtures.publishQuestionVersion(second))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));
    }

    // ------------------------------------------------------------------ 5

    @Test
    @DisplayName("5. a published question version cannot be mutated")
    void publishedQuestionVersionIsImmutable() {
        UUID versionId = fixtures.insertPublishedQuestionVersion(
                javaSkillId, "q-immutable-" + UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE app.question_versions SET prompt_text = ? WHERE id = ?",
                "A silently rewritten question that would invalidate history.", versionId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("5b. a published rubric cannot be mutated")
    void publishedRubricIsImmutable() {
        UUID versionId = fixtures.insertPublishedQuestionVersion(
                javaSkillId, "q-rubric-lock-" + UUID.randomUUID());
        UUID criterionId = fixtures.firstCriterionOf(versionId);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE app.rubric_criteria SET weight_bp = 9999 WHERE id = ?", criterionId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("5c. a published version may still be archived")
    void publishedQuestionVersionMayBeArchived() {
        UUID versionId = fixtures.insertPublishedQuestionVersion(
                javaSkillId, "q-archive-" + UUID.randomUUID());

        jdbc.update("""
                UPDATE app.question_versions
                   SET status = 'ARCHIVED', archived_at = now()
                 WHERE id = ?
                """, versionId);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.question_versions WHERE id = ?", String.class, versionId))
                .isEqualTo("ARCHIVED");
    }

    // ------------------------------------------------------------------ 6

    @Test
    @DisplayName("6. an answer cannot be mutated")
    void answerContentIsImmutable() {
        UUID turn = aTurnWithAnswer("immutable-answer").turnId();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE app.answers SET content_text = ? WHERE interview_question_id = ?",
                "a rewritten answer", turn))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("6b. a criterion result cannot be mutated")
    void criterionResultIsImmutable() {
        Fixture f = aTurnWithAnswer("immutable-ecr");
        UUID evaluationId = fixtures.insertEvaluation(f.turnId(), f.questionVersionId(), true);
        UUID criterionId = fixtures.firstCriterionOf(f.questionVersionId());

        UUID resultId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.evaluation_criterion_results
                    (id, evaluation_id, rubric_criterion_id, verdict, credit, weight_bp)
                VALUES (?, ?, ?, 'MET', 1.000, 3334)
                """, resultId, evaluationId, criterionId);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE app.evaluation_criterion_results SET verdict = 'MISSING' WHERE id = ?",
                resultId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("6c. an evaluation accepts only an is_current change")
    void evaluationIsAppendOnly() {
        Fixture f = aTurnWithAnswer("append-only");
        UUID evaluationId = fixtures.insertEvaluation(f.turnId(), f.questionVersionId(), true);

        // Permitted: flipping the current marker.
        jdbc.update("UPDATE app.evaluations SET is_current = false WHERE id = ?", evaluationId);

        // Forbidden: rewriting the score a candidate already saw.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE app.evaluations SET derived_score = 9.99 WHERE id = ?", evaluationId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    // ------------------------------------------------------------------ 7

    @Test
    @DisplayName("7. a template cannot be published when skill weights do not sum to 10000 bp")
    void templatePublishRequiresFullSkillWeighting() {
        UUID templateId = fixtures.insertDraftTemplate("tpl-weights-" + UUID.randomUUID(), 1);
        fixtures.addTemplateSkill(templateId, javaSkillId, 6000);   // short by 4000 bp
        fixtures.addPooledSlots(templateId, javaSkillId, 3);

        assertThatThrownBy(() -> fixtures.publishTemplate(templateId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("10000");
    }

    @Test
    @DisplayName("7b. a template cannot be published with the wrong number of slots")
    void templatePublishRequiresMatchingSlotCount() {
        UUID templateId = fixtures.insertDraftTemplate("tpl-slots-" + UUID.randomUUID(), 1);
        fixtures.addTemplateSkill(templateId, javaSkillId, 10000);
        fixtures.addPooledSlots(templateId, javaSkillId, 2);   // declares 3

        assertThatThrownBy(() -> fixtures.publishTemplate(templateId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("slots");
    }

    // ------------------------------------------------------------------ 8

    @Test
    @DisplayName("8. a question cannot be published with too few rubric criteria")
    void questionPublishRequiresEnoughCriteria() {
        UUID versionId = fixtures.insertDraftQuestionVersion(
                javaSkillId, "q-thin-rubric-" + UUID.randomUUID());
        fixtures.addRubricCriteria(versionId, 2);   // minimum is 3

        assertThatThrownBy(() -> fixtures.publishQuestionVersion(versionId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("3-10 rubric criteria");
    }

    @Test
    @DisplayName("8b. a question cannot be published when criterion weights miss 10000 bp")
    void questionPublishRequiresFullRubricWeighting() {
        UUID versionId = fixtures.insertDraftQuestionVersion(
                javaSkillId, "q-bad-weights-" + UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO app.rubric_criteria
                        (id, question_version_id, code, label, expectation, weight_bp, tier)
                    VALUES (?, ?, ?, 'Label', 'States the claim', 1000, 'CORE')
                    """, UUID.randomUUID(), versionId, "C" + i);   // sums to 3000
        }

        assertThatThrownBy(() -> fixtures.publishQuestionVersion(versionId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("10000");
    }

    // ------------------------------------------------------------------ 9

    /**
     * SQLSTATE 23001 is {@code restrict_violation}, raised specifically by
     * {@code ON DELETE RESTRICT}. The weaker default, {@code NO ACTION},
     * raises 23503 ({@code foreign_key_violation}) instead — so asserting
     * 23001 proves the stronger clause is genuinely in force rather than
     * merely that some foreign key exists.
     */
    private static final String RESTRICT_VIOLATION = "23001";

    @Test
    @DisplayName("9. a question version pinned by an interview cannot be deleted")
    void pinnedQuestionVersionCannotBeDeleted() {
        Fixture f = aTurnWithAnswer("pinned");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM app.question_versions WHERE id = ?", f.questionVersionId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(RESTRICT_VIOLATION));
    }

    @Test
    @DisplayName("9b. a template version pinned by an interview cannot be deleted")
    void pinnedTemplateCannotBeDeleted() {
        Fixture f = aTurnWithAnswer("pinned-template");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM app.interview_templates WHERE id = ?", f.templateId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(RESTRICT_VIOLATION));
    }

    // ----------------------------------------------------------------- 10

    @Test
    @DisplayName("10. a follow-up turn cannot carry score weight")
    void followUpMustHaveZeroWeight() {
        Fixture f = aTurnWithAnswer("follow-up-weight");
        UUID criterionId = fixtures.firstCriterionOf(f.questionVersionId());

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO app.interview_questions
                    (id, interview_id, position, kind, parent_id, question_version_id,
                     skill_id, weight_bp, follow_up_criterion_id, prompt_text, status, asked_at)
                VALUES (?, ?, 2, 'FOLLOW_UP', ?, ?, ?, 1000, ?, 'Can you say more?', 'ASKED', now())
                """, UUID.randomUUID(), f.interviewId(), f.turnId(), f.questionVersionId(),
                javaSkillId, criterionId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
    }

    @Test
    @DisplayName("10b. a well-formed follow-up turn is accepted")
    void wellFormedFollowUpIsAccepted() {
        Fixture f = aTurnWithAnswer("follow-up-ok");
        UUID criterionId = fixtures.firstCriterionOf(f.questionVersionId());
        UUID followUpId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO app.interview_questions
                    (id, interview_id, position, kind, parent_id, question_version_id,
                     skill_id, weight_bp, follow_up_criterion_id, prompt_text, status, asked_at)
                VALUES (?, ?, 2, 'FOLLOW_UP', ?, ?, ?, 0, ?, 'Can you say more?', 'ASKED', now())
                """, followUpId, f.interviewId(), f.turnId(), f.questionVersionId(),
                javaSkillId, criterionId);

        assertThat(jdbc.queryForObject(
                "SELECT weight_bp FROM app.interview_questions WHERE id = ?",
                Integer.class, followUpId)).isZero();
    }

    @Test
    @DisplayName("10c. a core turn cannot carry a parent or follow-up prompt")
    void coreTurnShapeIsEnforced() {
        Fixture f = aTurnWithAnswer("core-shape");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO app.interview_questions
                    (id, interview_id, position, kind, parent_id, question_version_id,
                     skill_id, weight_bp, status, asked_at)
                VALUES (?, ?, 3, 'CORE', ?, ?, ?, 3333, 'ASKED', now())
                """, UUID.randomUUID(), f.interviewId(), f.turnId(), f.questionVersionId(),
                javaSkillId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
    }

    // ----------------------------------------------------------------- 11

    @Test
    @DisplayName("11. a second active job with the same dedupe key is rejected")
    void duplicateActiveJobIsRejected() {
        String key = "EVALUATE_ANSWER:" + UUID.randomUUID();
        fixtures.insertJob(key, "QUEUED");

        assertThatThrownBy(() -> fixtures.insertJob(key, "QUEUED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));
    }

    @Test
    @DisplayName("11b. the same work CAN be re-enqueued once the predecessor finished")
    void completedJobMayBeReEnqueued() {
        String key = "EVALUATE_ANSWER:" + UUID.randomUUID();
        UUID first = fixtures.insertJob(key, "QUEUED");
        jdbc.update("UPDATE app.jobs SET status = 'SUCCEEDED' WHERE id = ?", first);

        // This is why the dedupe index is PARTIAL. A full unique index would
        // permanently block admin re-evaluation of this answer.
        UUID second = fixtures.insertJob(key, "QUEUED");

        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.jobs WHERE dedupe_key = ?", Integer.class, key))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("11c. a RUNNING job must name its owner")
    void runningJobRequiresLockOwner() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO app.jobs (id, job_type, dedupe_key, payload, status, locked_by)
                VALUES (?, 'EVALUATE_ANSWER', ?, '{}'::jsonb, 'RUNNING', NULL)
                """, UUID.randomUUID(), "orphan-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
    }

    // -------------------------------------------------------------- shared

    private record Fixture(UUID interviewId, UUID turnId, UUID questionVersionId,
                           UUID templateId) {}

    /** A published template and question, a live interview, one answered turn. */
    private Fixture aTurnWithAnswer(String label) {
        String unique = label + "-" + UUID.randomUUID();
        UUID candidate = fixtures.insertUser(unique);
        UUID template = fixtures.insertPublishedTemplate("tpl-" + unique, 1, javaSkillId);
        UUID questionVersion = fixtures.insertPublishedQuestionVersion(
                javaSkillId, "q-" + unique);
        UUID interview = fixtures.insertLiveInterview(candidate, template);
        UUID turn = fixtures.insertCoreTurn(interview, questionVersion, javaSkillId, 1);
        fixtures.insertAnswer(turn, "HashMap is not synchronised; ConcurrentHashMap is.");
        return new Fixture(interview, turn, questionVersion, template);
    }
}
