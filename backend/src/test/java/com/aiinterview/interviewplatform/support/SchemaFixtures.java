package com.aiinterview.interviewplatform.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Minimal valid rows for the invariant tests.
 *
 * <p>Everything is inserted with plain JDBC on purpose. These tests must prove
 * that <em>PostgreSQL</em> rejects a violation; going through JPA would risk
 * proving only that Hibernate or Bean Validation rejected it first.
 *
 * <p>Every builder produces content that satisfies the publish gates, so a
 * test that fails does so because of the specific rule it targets.
 */
public final class SchemaFixtures {

    private final JdbcTemplate jdbc;

    public SchemaFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ identity

    public UUID insertUser(String emailLocalPart) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.users (id, auth_subject, auth_provider, email, email_verified,
                                       display_name, role, status)
                VALUES (?, ?, 'supabase', ?, true, 'Test User', 'CANDIDATE', 'ACTIVE')
                """, id, "sub-" + id, emailLocalPart + "@example.test");
        return id;
    }

    // ----------------------------------------------------------- catalogue

    public UUID skillId(String code) {
        return jdbc.queryForObject("SELECT id FROM app.skills WHERE code = ?", UUID.class, code);
    }

    /** A DRAFT question version with no rubric criteria yet. */
    public UUID insertDraftQuestionVersion(UUID skillId, String questionKey) {
        UUID questionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.questions (id, question_key, status)
                VALUES (?, ?, 'ACTIVE')
                """, questionId, questionKey);

        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.question_versions
                    (id, question_id, version, skill_id, question_type, difficulty,
                     prompt_text, reference_answer, expected_duration_sec, status)
                VALUES (?, ?, 1, ?, 'CONCEPTUAL', 'MEDIUM',
                        'What is the difference between HashMap and ConcurrentHashMap?',
                        'HashMap is not thread-safe; ConcurrentHashMap locks per bin.',
                        120, 'DRAFT')
                """, versionId, questionId, skillId);
        return versionId;
    }

    /** Adds {@code count} criteria whose weights sum to exactly 10000 bp. */
    public void addRubricCriteria(UUID questionVersionId, int count) {
        int each = 10000 / count;
        int remainder = 10000 - (each * count);
        for (int i = 0; i < count; i++) {
            int weight = each + (i == 0 ? remainder : 0);
            jdbc.update("""
                    INSERT INTO app.rubric_criteria
                        (id, question_version_id, code, label, expectation, weight_bp, tier,
                         follow_up_prompt, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, 'CORE', ?, ?)
                    """,
                    UUID.randomUUID(), questionVersionId,
                    "CRITERION_" + i,
                    "Criterion " + i,
                    "States the claim covered by criterion " + i,
                    weight,
                    "Can you say more about criterion " + i + "?",
                    (short) (10 * (i + 1)));
        }
    }

    public void publishQuestionVersion(UUID questionVersionId) {
        jdbc.update("UPDATE app.question_versions SET status = 'PUBLISHED' WHERE id = ?",
                questionVersionId);
    }

    /** A published question version with a valid three-criterion rubric. */
    public UUID insertPublishedQuestionVersion(UUID skillId, String questionKey) {
        UUID versionId = insertDraftQuestionVersion(skillId, questionKey);
        addRubricCriteria(versionId, 3);
        publishQuestionVersion(versionId);
        return versionId;
    }

    public UUID firstCriterionOf(UUID questionVersionId) {
        return jdbc.queryForObject("""
                SELECT id FROM app.rubric_criteria
                 WHERE question_version_id = ? ORDER BY sort_order LIMIT 1
                """, UUID.class, questionVersionId);
    }

    // ------------------------------------------------------------ template

    /** DRAFT template with three pooled slots on one skill. Not yet weighted. */
    public UUID insertDraftTemplate(String templateKey, int version) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.interview_templates
                    (id, template_key, version, title, level, status,
                     core_question_count, max_follow_ups_total, max_follow_ups_per_parent,
                     target_duration_min, hard_duration_min)
                VALUES (?, ?, ?, 'Test Template', 'MID', 'DRAFT', 3, 4, 2, 18, 45)
                """, id, templateKey, version);
        return id;
    }

    public void addTemplateSkill(UUID templateId, UUID skillId, int weightBp) {
        jdbc.update("""
                INSERT INTO app.template_skills (template_id, skill_id, weight_bp)
                VALUES (?, ?, ?)
                """, templateId, skillId, weightBp);
    }

    public void addPooledSlots(UUID templateId, UUID skillId, int count) {
        for (int position = 1; position <= count; position++) {
            jdbc.update("""
                    INSERT INTO app.template_question_slots
                        (id, template_id, position, skill_id, difficulty, weight_bp)
                    VALUES (?, ?, ?, ?, 'MEDIUM', ?)
                    """, UUID.randomUUID(), templateId, (short) position, skillId,
                    10000 / count);
        }
    }

    public void publishTemplate(UUID templateId) {
        jdbc.update("UPDATE app.interview_templates SET status = 'PUBLISHED' WHERE id = ?",
                templateId);
    }

    /** A fully valid published template: weights sum to 10000, three slots. */
    public UUID insertPublishedTemplate(String templateKey, int version, UUID skillId) {
        UUID templateId = insertDraftTemplate(templateKey, version);
        addTemplateSkill(templateId, skillId, 10000);
        addPooledSlots(templateId, skillId, 3);
        publishTemplate(templateId);
        return templateId;
    }

    // ------------------------------------------------------------- attempt

    public UUID insertLiveInterview(UUID candidateUserId, UUID templateId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.interviews
                    (id, candidate_user_id, template_id, status, hard_deadline_at,
                     last_activity_at, engine_version)
                VALUES (?, ?, ?, 'IN_PROGRESS', now() + interval '45 minutes', now(), 'test')
                """, id, candidateUserId, templateId);
        return id;
    }

    public UUID insertCoreTurn(UUID interviewId, UUID questionVersionId, UUID skillId,
                               int position) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.interview_questions
                    (id, interview_id, position, kind, question_version_id, skill_id,
                     weight_bp, status, asked_at)
                VALUES (?, ?, ?, 'CORE', ?, ?, 3333, 'ASKED', now())
                """, id, interviewId, position, questionVersionId, skillId);
        return id;
    }

    public void insertAnswer(UUID turnId, String text) {
        jdbc.update("""
                INSERT INTO app.answers (interview_question_id, content_text, input_mode)
                VALUES (?, ?, 'TEXT')
                """, turnId, text);
    }

    /** An evaluation row; {@code current} drives the one-current-per-answer index. */
    public UUID insertEvaluation(UUID answerId, UUID questionVersionId, boolean current) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.evaluations
                    (id, answer_id, is_current, status, derived_score, evaluation_version,
                     prompt_version, rubric_version, question_version_id, provider, model)
                VALUES (?, ?, ?, 'SUCCEEDED', 6.50, 'eval-1.0.0', 'rubric-eval@test.1',
                        1, ?, 'mock', 'mock-model-1')
                """, id, answerId, current, questionVersionId);
        return id;
    }

    // ---------------------------------------------------------------- jobs

    public UUID insertJob(String dedupeKey, String status) {
        UUID id = UUID.randomUUID();
        String lockedBy = "RUNNING".equals(status) ? "worker-1" : null;
        jdbc.update("""
                INSERT INTO app.jobs (id, job_type, dedupe_key, payload, status, locked_by)
                VALUES (?, 'EVALUATE_ANSWER', ?, '{"answerId":"x"}'::jsonb, ?, ?)
                """, id, dedupeKey, status, lockedBy);
        return id;
    }
}
