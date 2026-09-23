package com.aiinterview.interviewplatform.devtools;

import com.aiinterview.interviewplatform.question.api.QuestionAuthoring;
import com.aiinterview.interviewplatform.question.api.QuestionAuthoring.CriterionDraft;
import com.aiinterview.interviewplatform.question.api.TemplateAuthoring;
import com.aiinterview.interviewplatform.question.api.TemplateAuthoring.SkillWeight;
import com.aiinterview.interviewplatform.question.api.TemplateAuthoring.SlotDraft;
import com.aiinterview.interviewplatform.question.api.TemplateCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Puts a realistic interview in an empty development database.
 *
 * <p>M5A needs something to actually interview against: the migrations seed
 * skills, which are reference data, but questions and templates are content and
 * are deliberately kept out of migrations so they pass the same publish gates
 * that candidate-facing content does. This seeder therefore authors them
 * through {@link QuestionAuthoring} and {@link TemplateAuthoring} rather than
 * inserting rows — content that bypassed the gates could be unplannable, and the
 * seed would prove nothing about the real flow.
 *
 * <p>Off unless {@code app.dev-seed.enabled=true}, and it never runs twice: the
 * template key is checked first, so restarting a dev server does not accumulate
 * duplicate content.
 *
 * <p>The candidate row is inserted directly because no identity service exists
 * yet — user provisioning arrives with M6. That is the one shortcut here, it is
 * confined to this dev-only class, and the id is fixed so a developer can
 * restart the backend without the frontend losing its candidate.
 */
@Component
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    /** Stable so a restart does not strand the browser's candidate. */
    public static final UUID DEV_CANDIDATE_ID =
            UUID.fromString("00000000-0000-4000-8000-00000000d0c1");

    public static final String TEMPLATE_KEY = "backend-engineer";

    private final QuestionAuthoring questions;
    private final TemplateAuthoring templates;
    private final TemplateCatalog catalog;
    private final JdbcTemplate jdbc;

    public DevDataSeeder(QuestionAuthoring questions, TemplateAuthoring templates,
                         TemplateCatalog catalog, JdbcTemplate jdbc) {
        this.questions = questions;
        this.templates = templates;
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        if (catalog.findPublishedTemplateByKey(TEMPLATE_KEY).isPresent()) {
            log.info("Dev seed: template '{}' already published; nothing to do", TEMPLATE_KEY);
            return;
        }

        UUID author = seedUser(UUID.fromString("00000000-0000-4000-8000-00000000a471"),
                "dev-author@example.test", "ADMIN", "Dev Author");
        seedUser(DEV_CANDIDATE_ID, "dev-candidate@example.test", "CANDIDATE", "Dev Candidate");

        UUID java = skillId("JAVA");
        UUID spring = skillId("SPRING_BOOT");
        UUID sql = skillId("SQL");

        List<SlotDraft> slots = new ArrayList<>();
        // Six questions, evenly weighted. Pinned rather than pooled so the dev
        // interview is the same every time and a bug is reproducible.
        //
        // A pinned slot sets questionId and leaves skillId null: ck_tqs_mode
        // allows exactly one, so the mode is derived from the data and cannot
        // disagree with itself. The turn takes its skill from the question
        // version at planning time.
        for (Seed seed : seeds(java, spring, sql)) {
            UUID questionId = publish(seed, author);
            slots.add(new SlotDraft(questionId, null, null, null, 1667));
        }
        // Weights must total exactly 10000; the last slot absorbs the rounding.
        SlotDraft last = slots.get(slots.size() - 1);
        slots.set(slots.size() - 1, new SlotDraft(
                last.questionId(), null, null, null, 10_000 - 1667 * (slots.size() - 1)));

        // A previous run may have created the draft and then failed before
        // publishing it. Resuming beats failing on the unique key and leaving a
        // developer to clear the database by hand.
        UUID templateId = findDraftTemplate(TEMPLATE_KEY);
        if (templateId == null) {
            templateId = templates.createTemplate(
                    new TemplateAuthoring.CreateTemplateCommand(
                            TEMPLATE_KEY,
                            "Backend Engineer Interview",
                            "Java, Spring Boot and SQL fundamentals.",
                            "A short technical interview covering core backend topics. "
                                    + "Answers are graded against a published rubric.",
                            "Answer in your own words. There is no time limit per question.",
                            "MID",
                            slots.size(), 3, 1, 25, 60, author)).templateId();
        } else {
            log.info("Dev seed: resuming existing draft template '{}'", TEMPLATE_KEY);
        }

        templates.replaceSkills(new TemplateAuthoring.ReplaceTemplateSkillsCommand(
                templateId,
                List.of(new SkillWeight(java, 4000),
                        new SkillWeight(spring, 3000),
                        new SkillWeight(sql, 3000)),
                author));

        templates.replaceSlots(new TemplateAuthoring.ReplaceTemplateSlotsCommand(
                templateId, slots, author));

        templates.publish(new TemplateAuthoring.PublishTemplateCommand(
                templateId, author));

        log.info("Dev seed: published template '{}' with {} questions; candidate id {}",
                TEMPLATE_KEY, slots.size(), DEV_CANDIDATE_ID);
    }

    /**
     * Authors and publishes one question, tolerating a question that already
     * exists.
     *
     * <p>Seeding is several writes and the template is only checked at the end,
     * so a run that fails partway leaves questions behind. Without this, the
     * next start would fail on a duplicate key and a developer would have to
     * drop the database by hand to recover from a typo.
     */
    private UUID publish(Seed seed, UUID author) {
        UUID existing = findQuestionByKey(seed.key());
        if (existing != null) {
            if (findVersion(existing, "PUBLISHED") != null) {
                log.info("Dev seed: question '{}' already published; reusing it", seed.key());
                return existing;
            }
            // Authored but never published — a previous run failed between the
            // two. Finishing it is the only option: a template slot may already
            // reference the question, so it can be neither deleted nor recreated.
            UUID draftVersion = findVersion(existing, "DRAFT");
            if (draftVersion != null) {
                log.info("Dev seed: finishing half-authored question '{}'", seed.key());
                questions.replaceRubric(new QuestionAuthoring.ReplaceRubricCommand(
                        draftVersion, seed.rubric(), author));
                questions.publish(new QuestionAuthoring.PublishQuestionCommand(
                        draftVersion, author));
            }
            return existing;
        }

        QuestionAuthoring.DraftVersion draft = questions.createQuestion(
                new QuestionAuthoring.CreateQuestionCommand(
                        seed.key(), seed.skillId(), "CONCEPTUAL", "MEDIUM",
                        seed.prompt(), null, seed.reference(), 180, author));

        questions.replaceRubric(new QuestionAuthoring.ReplaceRubricCommand(
                draft.questionVersionId(), seed.rubric(), author));

        questions.publish(new QuestionAuthoring.PublishQuestionCommand(
                draft.questionVersionId(), author));

        return draft.questionId();
    }

    private UUID skillId(String code) {
        return jdbc.queryForObject("SELECT id FROM app.skills WHERE code = ?", UUID.class, code);
    }

    /** Dev-only lookup; the catalogue has no find-by-key and does not need one. */
    private UUID findQuestionByKey(String questionKey) {
        return jdbc.query("SELECT id FROM app.questions WHERE question_key = ?",
                        rs -> rs.next() ? rs.getObject(1, UUID.class) : null, questionKey);
    }

    /** Latest version of a question in the given status, or null. */
    private UUID findVersion(UUID questionId, String status) {
        return jdbc.query("""
                SELECT id FROM app.question_versions
                WHERE question_id = ? AND status = ?
                ORDER BY version DESC LIMIT 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, questionId, status);
    }

    /** The catalogue exposes published templates only, by design. */
    private UUID findDraftTemplate(String templateKey) {
        return jdbc.query("""
                SELECT id FROM app.interview_templates
                WHERE template_key = ? AND status = 'DRAFT'
                ORDER BY version DESC LIMIT 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, templateKey);
    }

    /** Idempotent: a restart must not fail on the unique auth subject. */
    private UUID seedUser(UUID id, String email, String role, String displayName) {
        jdbc.update("""
                INSERT INTO app.users (id, auth_subject, auth_provider, email, email_verified,
                                       display_name, role, status)
                VALUES (?, ?, 'dev', ?, true, ?, ?, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, id, "dev-" + id, email, displayName, role);
        return id;
    }

    private record Seed(String key, UUID skillId, String prompt, String reference,
                        List<CriterionDraft> rubric) {
    }

    /**
     * Real questions with real rubrics.
     *
     * <p>Each criterion is an observable claim an answer either makes or does
     * not — never a topic label — because that is what makes a verdict
     * defensible and a follow-up possible.
     */
    private static List<Seed> seeds(UUID java, UUID spring, UUID sql) {
        return List.of(
                new Seed("dev-java-concurrent-map", java,
                        "What is the difference between HashMap and ConcurrentHashMap, "
                                + "and when would you choose each?",
                        "HashMap is not thread-safe; concurrent structural modification can "
                                + "corrupt it. ConcurrentHashMap locks per bin rather than the "
                                + "whole map and forbids null keys and values.",
                        List.of(
                                new CriterionDraft("THREAD_SAFETY", "Thread safety",
                                        "States that HashMap is not thread-safe while "
                                                + "ConcurrentHashMap is",
                                        4000, "CORE",
                                        "Which of the two is safe to share between threads?"),
                                new CriterionDraft("LOCK_GRANULARITY", "Lock granularity",
                                        "Explains that ConcurrentHashMap locks per bin or "
                                                + "segment rather than the whole map",
                                        3000, "DEPTH",
                                        "How much of the map is locked during a write?"),
                                new CriterionDraft("NULL_HANDLING", "Null handling",
                                        "States that ConcurrentHashMap forbids null keys "
                                                + "and values",
                                        3000, "CORE",
                                        "What happens if you put a null key in?"))),

                new Seed("dev-java-equals-hashcode", java,
                        "Why must equals() and hashCode() be overridden together?",
                        "Objects that are equal must have the same hash code, or hash-based "
                                + "collections will fail to find them. Overriding one alone "
                                + "breaks the contract.",
                        List.of(
                                new CriterionDraft("CONTRACT", "The contract",
                                        "States that equal objects must produce the same "
                                                + "hash code",
                                        4000, "CORE",
                                        "What does the contract actually require?"),
                                new CriterionDraft("CONSEQUENCE", "Practical consequence",
                                        "Explains that hash-based collections fail to find "
                                                + "the object when the contract is broken",
                                        4000, "CORE",
                                        "What goes wrong in a HashMap if you break it?"),
                                new CriterionDraft("ASYMMETRY", "One-way implication",
                                        "Notes that equal hash codes do not imply equality, "
                                                + "so collisions are expected and legal",
                                        2000, "DEPTH",
                                        "Does a matching hash code mean the objects are "
                                                + "equal?"))),

                new Seed("dev-spring-transactional", spring,
                        "How does Spring's @Transactional work, and what are its "
                                + "common pitfalls?",
                        "It is implemented with AOP proxies. A self-invocation bypasses the "
                                + "proxy so the annotation has no effect. Rollback happens by "
                                + "default on unchecked exceptions only.",
                        List.of(
                                new CriterionDraft("PROXY_MECHANISM", "Proxy mechanism",
                                        "Identifies that @Transactional works through an AOP "
                                                + "proxy around the bean",
                                        3500, "CORE",
                                        "What actually starts the transaction?"),
                                new CriterionDraft("SELF_INVOCATION", "Self-invocation",
                                        "Explains that a self-invocation bypasses the proxy "
                                                + "so the annotation has no effect",
                                        3500, "DEPTH",
                                        "What happens if one method calls another in the "
                                                + "same class?"),
                                new CriterionDraft("ROLLBACK_RULES", "Rollback rules",
                                        "States that rollback happens by default on unchecked "
                                                + "exceptions but not checked ones",
                                        3000, "CORE",
                                        "Which exceptions trigger a rollback by default?"))),

                new Seed("dev-spring-di", spring,
                        "Why is constructor injection preferred over field injection "
                                + "in Spring?",
                        "Constructor injection makes dependencies explicit and mandatory, "
                                + "allows final fields, and permits construction in a test "
                                + "without a container.",
                        List.of(
                                new CriterionDraft("IMMUTABILITY", "Immutability",
                                        "States that constructor injection permits final "
                                                + "fields or immutable dependencies",
                                        3500, "CORE",
                                        "What can you do with the field declaration?"),
                                new CriterionDraft("TESTABILITY", "Testability",
                                        "States that the object can be constructed in a test "
                                                + "without a Spring context",
                                        3500, "CORE",
                                        "How would you build this class in a unit test?"),
                                new CriterionDraft("EXPLICIT_DEPS", "Explicit dependencies",
                                        "Explains that required dependencies become visible "
                                                + "in the signature rather than hidden",
                                        3000, "DEPTH",
                                        "How do you know what the class needs?"))),

                new Seed("dev-sql-index-diagnosis", sql,
                        "A query filtering on a non-indexed column has become slow as the "
                                + "table grew. How would you diagnose and fix it?",
                        "Use EXPLAIN ANALYZE to confirm a sequential scan, add an index on "
                                + "the filtered column with column order matching the "
                                + "predicates, and accept the write cost.",
                        List.of(
                                new CriterionDraft("MEASURE_FIRST", "Measure first",
                                        "Proposes inspecting the actual query plan before "
                                                + "changing anything",
                                        3500, "CORE",
                                        "How would you confirm the cause rather than guess?"),
                                new CriterionDraft("INDEX_CHOICE", "Index choice",
                                        "Proposes an index on the filtered column, noting "
                                                + "column order for composite indexes",
                                        3500, "CORE",
                                        "Which index exactly, and on what?"),
                                new CriterionDraft("WRITE_COST", "Write cost",
                                        "Acknowledges that indexes impose a cost on writes "
                                                + "or storage",
                                        3000, "DEPTH",
                                        "What does adding the index cost you?"))),

                new Seed("dev-sql-isolation", sql,
                        "Explain the difference between optimistic and pessimistic locking, "
                                + "and when you would use each.",
                        "Pessimistic locking takes a lock up front and blocks others; "
                                + "optimistic locking detects a conflicting change at commit "
                                + "via a version column and retries.",
                        List.of(
                                new CriterionDraft("PESSIMISTIC", "Pessimistic locking",
                                        "Explains that a lock is taken up front and blocks "
                                                + "other writers",
                                        3500, "CORE",
                                        "When is the lock acquired?"),
                                new CriterionDraft("OPTIMISTIC", "Optimistic locking",
                                        "Explains that the conflict is detected at commit, "
                                                + "typically with a version column",
                                        3500, "CORE",
                                        "How is the conflict actually noticed?"),
                                new CriterionDraft("TRADEOFF", "Choosing between them",
                                        "Relates the choice to contention: high contention "
                                                + "favours pessimistic, low favours optimistic",
                                        3000, "DEPTH",
                                        "Which would you pick under heavy contention?"))));
    }
}
