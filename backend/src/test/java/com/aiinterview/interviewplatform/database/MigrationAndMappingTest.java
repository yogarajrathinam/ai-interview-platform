package com.aiinterview.interviewplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiinterview.interviewplatform.support.AbstractDatabaseTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the foundation: migrations apply to an empty database, and the JPA
 * mapping matches the result.
 *
 * <p>Reaching any test body already proves a great deal. The Spring context
 * only starts if Flyway migrated cleanly and Hibernate's
 * {@code ddl-auto=validate} then agreed that all 18 entities match the
 * migrated tables — a mismatch in any column name or type fails the context.
 */
@DisplayName("Flyway migrations and JPA mapping")
class MigrationAndMappingTest extends AbstractDatabaseTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "ai_invocations", "answers", "evaluation_criterion_results", "evaluations",
            "interview_questions", "interview_reports", "interview_templates", "interviews",
            "jobs", "profiles", "question_versions", "questions", "report_skill_scores",
            "rubric_criteria", "skills", "template_question_slots", "template_skills", "users");

    @Test
    @DisplayName("context starts, which means Flyway migrated and ddl-auto=validate passed")
    void contextStartsWithValidatedMapping() {
        Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
        assertThat(one).isOne();
    }

    @Test
    @DisplayName("all migrations succeeded")
    void allMigrationsSucceeded() {
        List<String> failed = jdbc.queryForList(
                "SELECT version FROM app.flyway_schema_history WHERE success = false",
                String.class);
        assertThat(failed).isEmpty();

        List<String> applied = jdbc.queryForList(
                "SELECT version FROM app.flyway_schema_history WHERE version IS NOT NULL "
                        + "ORDER BY installed_rank", String.class);
        assertThat(applied).containsExactly("1", "2");
    }

    @Test
    @DisplayName("exactly the 18 approved tables exist — no more, no fewer")
    void schemaContainsExactlyTheApprovedTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'app' AND table_type = 'BASE TABLE'
                   AND table_name <> 'flyway_schema_history'
                 ORDER BY table_name
                """, String.class);

        // Guards against silently reintroducing audit_log, organizations,
        // follow_up_prompts, answer_drafts or idempotency_keys.
        assertThat(tables).containsExactlyElementsOf(EXPECTED_TABLES);
    }

    @Test
    @DisplayName("V2 seeds the three Phase 0 skills")
    void seedSkillsApplied() {
        List<String> codes = jdbc.queryForList(
                "SELECT code FROM app.skills ORDER BY sort_order", String.class);
        assertThat(codes).containsExactly("JAVA", "SPRING_BOOT", "SQL");
    }

    @Test
    @DisplayName("every immutability and publish-gate trigger is installed")
    void triggersInstalled() {
        List<String> triggers = jdbc.queryForList("""
                SELECT tgname FROM pg_trigger t
                  JOIN pg_class c ON c.oid = t.tgrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'app' AND NOT t.tgisinternal
                 ORDER BY tgname
                """, String.class);

        assertThat(triggers).contains(
                "trg_qv_10_validate", "trg_qv_20_guard",
                "trg_tpl_10_validate", "trg_tpl_20_guard",
                "trg_rc_20_guard", "trg_ts_20_guard", "trg_tqs_20_guard",
                "trg_answers_20_immutable", "trg_ecr_20_immutable", "trg_rss_20_immutable",
                "trg_eval_20_append_only", "trg_report_20_append_only");
    }

    @Test
    @DisplayName("the partial unique indexes that carry the invariants exist")
    void partialUniqueIndexesInstalled() {
        List<String> indexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                 WHERE schemaname = 'app' ORDER BY indexname
                """, String.class);

        assertThat(indexes).contains(
                "uq_interviews_one_live",       // one live attempt per candidate
                "uq_evaluations_one_current",   // one current evaluation per answer
                "uq_reports_one_current",       // one current report per interview
                "uq_qv_one_published",          // one published version per question
                "uq_tpl_one_published",         // one published version per template
                "uq_jobs_active_dedupe",        // one active job per dedupe key
                "uq_users_email");              // case-insensitive email uniqueness
    }
}
