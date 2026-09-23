package com.aiinterview.interviewplatform.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiinterview.interviewplatform.interview.api.InterviewService;
import com.aiinterview.interviewplatform.interview.api.InterviewState;
import com.aiinterview.interviewplatform.interview.api.StartInterviewCommand;
import com.aiinterview.interviewplatform.interview.api.SubmitAnswerCommand;
import com.aiinterview.interviewplatform.question.api.QuestionAuthoring;
import com.aiinterview.interviewplatform.question.api.QuestionAuthoring.CriterionDraft;
import com.aiinterview.interviewplatform.question.api.QuestionCatalog;
import com.aiinterview.interviewplatform.question.api.TemplateAuthoring;
import com.aiinterview.interviewplatform.question.api.TemplateAuthoring.SkillWeight;
import com.aiinterview.interviewplatform.question.api.TemplateAuthoring.SlotDraft;
import com.aiinterview.interviewplatform.question.api.TemplateCatalog;
import com.aiinterview.interviewplatform.shared.error.ApplicationException;
import com.aiinterview.interviewplatform.support.AbstractDatabaseTest;
import com.aiinterview.interviewplatform.support.SchemaFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Catalogue authoring, and its compatibility with the interview engine.
 *
 * <p>The final test is the one that matters most: content authored through M4's
 * APIs is consumed unchanged by M3. Authoring that produces data the engine
 * cannot start an interview from would be worse than no authoring at all.
 */
@DisplayName("Catalogue authoring")
class CatalogueAuthoringIntegrationTest extends AbstractDatabaseTest {

    @Autowired
    private QuestionAuthoring questionAuthoring;

    @Autowired
    private TemplateAuthoring templateAuthoring;

    @Autowired
    private QuestionCatalog questionCatalog;

    @Autowired
    private TemplateCatalog templateCatalog;

    @Autowired
    private InterviewService interviews;

    private SchemaFixtures fixtures;
    private UUID javaSkillId;
    private UUID author;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
        javaSkillId = fixtures.skillId("JAVA");
        author = fixtures.insertUser("author-" + UUID.randomUUID());
    }

    // ------------------------------------------------------------ fixtures

    private static List<CriterionDraft> validRubric() {
        return List.of(
                new CriterionDraft("THREAD_SAFETY", "Thread safety",
                        "States that HashMap is not synchronised for concurrent writes",
                        4000, "CORE", "Which part is unsafe, exactly?"),
                new CriterionDraft("NULL_HANDLING", "Null handling",
                        "States that ConcurrentHashMap forbids null keys and values",
                        3000, "CORE", "What happens if you insert a null key?"),
                new CriterionDraft("FAILURE_MODE", "Failure mode",
                        "Mentions the table can corrupt under concurrent writes",
                        3000, "DEPTH", "What goes wrong in practice?"));
    }

    private QuestionAuthoring.CreateQuestionCommand newQuestion(String key) {
        return new QuestionAuthoring.CreateQuestionCommand(key, javaSkillId, "CONCEPTUAL",
                "MEDIUM", "What is the difference between HashMap and ConcurrentHashMap?",
                null, "HashMap is not thread-safe; ConcurrentHashMap locks per bin.",
                120, author);
    }

    /** Authors and publishes one complete question. */
    private QuestionAuthoring.PublishedVersion publishQuestion(String key) {
        var draft = questionAuthoring.createQuestion(newQuestion(key));
        questionAuthoring.replaceRubric(new QuestionAuthoring.ReplaceRubricCommand(
                draft.questionVersionId(), validRubric(), author));
        return questionAuthoring.publish(new QuestionAuthoring.PublishQuestionCommand(
                draft.questionVersionId(), author));
    }

    // ------------------------------------------------------------ question

    @Nested
    @DisplayName("Question")
    class Questions {

        @Test
        @DisplayName("a new question starts as an editable draft")
        void createStartsAsDraft() {
            var draft = questionAuthoring.createQuestion(newQuestion("q-" + UUID.randomUUID()));

            assertThat(draft.status()).isEqualTo("DRAFT");
            assertThat(draft.version()).isEqualTo(1);
            assertThat(draft.rubric()).isEmpty();
        }

        @Test
        @DisplayName("a duplicate question key is refused")
        void duplicateKeyIsRefused() {
            String key = "q-dup-" + UUID.randomUUID();
            questionAuthoring.createQuestion(newQuestion(key));

            assertThatThrownBy(() -> questionAuthoring.createQuestion(newQuestion(key)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        @DisplayName("a draft can be edited")
        void draftIsEditable() {
            var draft = questionAuthoring.createQuestion(newQuestion("q-" + UUID.randomUUID()));

            questionAuthoring.updateDraft(new QuestionAuthoring.UpdateQuestionDraftCommand(
                    draft.questionVersionId(), null, null, "HARD", "A revised prompt.",
                    null, null, null, author));

            var version = questionCatalog.findVersion(draft.questionVersionId()).orElseThrow();
            assertThat(version.promptText()).isEqualTo("A revised prompt.");
            assertThat(version.difficulty()).isEqualTo("HARD");
        }

        @Test
        @DisplayName("publishing without a rubric is refused, with a reason")
        void publishRequiresARubric() {
            var draft = questionAuthoring.createQuestion(newQuestion("q-" + UUID.randomUUID()));

            assertThatThrownBy(() -> questionAuthoring.publish(
                    new QuestionAuthoring.PublishQuestionCommand(draft.questionVersionId(), author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("rubric criteria");
        }

        @Test
        @DisplayName("publishing without a reference answer is refused")
        void publishRequiresAReferenceAnswer() {
            var draft = questionAuthoring.createQuestion(
                    new QuestionAuthoring.CreateQuestionCommand("q-" + UUID.randomUUID(),
                            javaSkillId, "CONCEPTUAL", "MEDIUM", "A prompt with no reference.",
                            null, null, 120, author));
            questionAuthoring.replaceRubric(new QuestionAuthoring.ReplaceRubricCommand(
                    draft.questionVersionId(), validRubric(), author));

            assertThatThrownBy(() -> questionAuthoring.publish(
                    new QuestionAuthoring.PublishQuestionCommand(draft.questionVersionId(), author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("reference answer");
        }

        @Test
        @DisplayName("a published question is immutable")
        void publishedQuestionIsImmutable() {
            var published = publishQuestion("q-immutable-" + UUID.randomUUID());

            assertThatThrownBy(() -> questionAuthoring.updateDraft(
                    new QuestionAuthoring.UpdateQuestionDraftCommand(
                            published.questionVersionId(), null, null, null,
                            "Silently rewritten.", null, null, null, author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("cannot be edited");

            // Unchanged on disk, not merely refused in memory.
            assertThat(questionCatalog.findVersion(published.questionVersionId()).orElseThrow()
                    .promptText()).doesNotContain("Silently rewritten");
        }

        @Test
        @DisplayName("a published rubric is immutable")
        void publishedRubricIsImmutable() {
            var published = publishQuestion("q-rubric-lock-" + UUID.randomUUID());

            assertThatThrownBy(() -> questionAuthoring.replaceRubric(
                    new QuestionAuthoring.ReplaceRubricCommand(
                            published.questionVersionId(), validRubric(), author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("cannot be edited");
        }

        @Test
        @DisplayName("changing a published question creates version 2, leaving v1 intact")
        void editingPublishedContentCreatesANewVersion() {
            var v1 = publishQuestion("q-versioned-" + UUID.randomUUID());

            var v2 = questionAuthoring.createNextVersion(v1.questionId(), author);

            assertThat(v2.version()).isEqualTo(2);
            assertThat(v2.status()).isEqualTo("DRAFT");
            // The rubric is copied so the author starts from what exists.
            assertThat(v2.rubric()).hasSize(3);
            // v1 is untouched: every interview that pinned it still grades the
            // same way.
            assertThat(questionCatalog.findVersion(v1.questionVersionId()).orElseThrow().status())
                    .isEqualTo("PUBLISHED");
            assertThat(v2.questionVersionId()).isNotEqualTo(v1.questionVersionId());
        }
    }

    // -------------------------------------------------------------- rubric

    @Nested
    @DisplayName("Rubric")
    class Rubric {

        private UUID draftVersion() {
            return questionAuthoring.createQuestion(newQuestion("q-" + UUID.randomUUID()))
                    .questionVersionId();
        }

        @Test
        @DisplayName("a valid rubric is stored in author order")
        void validRubricIsStoredInOrder() {
            UUID versionId = draftVersion();

            var draft = questionAuthoring.replaceRubric(
                    new QuestionAuthoring.ReplaceRubricCommand(versionId, validRubric(), author));

            assertThat(draft.rubric()).hasSize(3);
            assertThat(draft.rubric().get(0).code()).isEqualTo("THREAD_SAFETY");
            assertThat(draft.rubric().get(2).code()).isEqualTo("FAILURE_MODE");
            assertThat(draft.rubric().get(0).followUpPrompt()).isNotBlank();
        }

        @Test
        @DisplayName("weights that do not total 10000 are refused")
        void weightsMustTotalTenThousand() {
            UUID versionId = draftVersion();
            List<CriterionDraft> wrong = List.of(
                    new CriterionDraft("A", "A", "States A", 3000, "CORE", null),
                    new CriterionDraft("B", "B", "States B", 3000, "CORE", null),
                    new CriterionDraft("C", "C", "States C", 3000, "CORE", null));

            assertThatThrownBy(() -> questionAuthoring.replaceRubric(
                    new QuestionAuthoring.ReplaceRubricCommand(versionId, wrong, author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("10000")
                    .hasMessageContaining("9000");
        }

        @Test
        @DisplayName("too few criteria are refused")
        void tooFewCriteriaAreRefused() {
            UUID versionId = draftVersion();
            List<CriterionDraft> thin = List.of(
                    new CriterionDraft("A", "A", "States A", 5000, "CORE", null),
                    new CriterionDraft("B", "B", "States B", 5000, "CORE", null));

            assertThatThrownBy(() -> questionAuthoring.replaceRubric(
                    new QuestionAuthoring.ReplaceRubricCommand(versionId, thin, author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("3-10 criteria");
        }

        @Test
        @DisplayName("a criterion without an expectation is refused")
        void expectationIsRequired() {
            UUID versionId = draftVersion();
            List<CriterionDraft> vague = List.of(
                    new CriterionDraft("A", "A", null, 4000, "CORE", null),
                    new CriterionDraft("B", "B", "States B", 3000, "CORE", null),
                    new CriterionDraft("C", "C", "States C", 3000, "CORE", null));

            // A topic label is not gradable; two graders would read it differently.
            assertThatThrownBy(() -> questionAuthoring.replaceRubric(
                    new QuestionAuthoring.ReplaceRubricCommand(versionId, vague, author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("expectation");
        }

        @Test
        @DisplayName("duplicate criterion codes are refused")
        void duplicateCodesAreRefused() {
            UUID versionId = draftVersion();
            List<CriterionDraft> clashing = List.of(
                    new CriterionDraft("SAME", "A", "States A", 4000, "CORE", null),
                    new CriterionDraft("SAME", "B", "States B", 3000, "CORE", null),
                    new CriterionDraft("C", "C", "States C", 3000, "CORE", null));

            assertThatThrownBy(() -> questionAuthoring.replaceRubric(
                    new QuestionAuthoring.ReplaceRubricCommand(versionId, clashing, author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("Duplicate criterion code");
        }

        @Test
        @DisplayName("replacing a rubric leaves no orphaned criteria")
        void replacingRubricIsClean() {
            UUID versionId = draftVersion();
            questionAuthoring.replaceRubric(
                    new QuestionAuthoring.ReplaceRubricCommand(versionId, validRubric(), author));
            questionAuthoring.replaceRubric(
                    new QuestionAuthoring.ReplaceRubricCommand(versionId, validRubric(), author));

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM app.rubric_criteria WHERE question_version_id = ?",
                    Integer.class, versionId)).isEqualTo(3);
        }
    }

    // ------------------------------------------------------------ template

    @Nested
    @DisplayName("Template")
    class Templates {

        private TemplateAuthoring.DraftTemplate draftTemplate(String key, int slotCount) {
            return templateAuthoring.createTemplate(new TemplateAuthoring.CreateTemplateCommand(
                    key, "Java Fundamentals", null, null, null, "MID",
                    slotCount, 4, 2, 18, 45, author));
        }

        private void weightAndPlan(UUID templateId, int slotCount) {
            templateAuthoring.replaceSkills(new TemplateAuthoring.ReplaceTemplateSkillsCommand(
                    templateId, List.of(new SkillWeight(javaSkillId, 10000)), author));
            templateAuthoring.replaceSlots(new TemplateAuthoring.ReplaceTemplateSlotsCommand(
                    templateId, pooledSlots(slotCount), author));
        }

        private List<SlotDraft> pooledSlots(int count) {
            return java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> new SlotDraft(null, javaSkillId, null, null, 10000 / count))
                    .toList();
        }

        @Test
        @DisplayName("a new template starts as an editable draft")
        void createStartsAsDraft() {
            var draft = draftTemplate("tpl-" + UUID.randomUUID(), 3);

            assertThat(draft.status()).isEqualTo("DRAFT");
            assertThat(draft.version()).isEqualTo(1);
            assertThat(draft.slots()).isEmpty();
        }

        @Test
        @DisplayName("slots are added and positioned from their order")
        void slotsArePositionedInOrder() {
            var draft = draftTemplate("tpl-" + UUID.randomUUID(), 3);

            var withPlan = templateAuthoring.replaceSlots(
                    new TemplateAuthoring.ReplaceTemplateSlotsCommand(
                            draft.templateId(), pooledSlots(3), author));

            assertThat(withPlan.slots()).hasSize(3);
            assertThat(withPlan.slots()).extracting(TemplateCatalog.QuestionSlotView::position)
                    .containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("reordering renumbers in one pass without a position clash")
        void reorderingIsSafe() {
            var draft = draftTemplate("tpl-" + UUID.randomUUID(), 3);
            UUID other = fixtures.skillId("SQL");
            templateAuthoring.replaceSlots(new TemplateAuthoring.ReplaceTemplateSlotsCommand(
                    draft.templateId(), pooledSlots(3), author));

            // A different plan entirely: uq_tqs_position would reject any
            // intermediate state where two slots shared a position.
            var reordered = templateAuthoring.replaceSlots(
                    new TemplateAuthoring.ReplaceTemplateSlotsCommand(draft.templateId(),
                            List.of(new SlotDraft(null, other, null, null, 5000),
                                    new SlotDraft(null, javaSkillId, null, null, 5000)),
                            author));

            assertThat(reordered.slots()).hasSize(2);
            assertThat(reordered.slots().get(0).skillId()).isEqualTo(other);
        }

        @Test
        @DisplayName("skill weights that do not total 10000 are refused")
        void skillWeightsMustTotalTenThousand() {
            var draft = draftTemplate("tpl-" + UUID.randomUUID(), 3);

            assertThatThrownBy(() -> templateAuthoring.replaceSkills(
                    new TemplateAuthoring.ReplaceTemplateSkillsCommand(draft.templateId(),
                            List.of(new SkillWeight(javaSkillId, 6000)), author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("10000");
        }

        @Test
        @DisplayName("a slot must be pinned or pooled, never both")
        void slotModeIsExclusive() {
            var draft = draftTemplate("tpl-" + UUID.randomUUID(), 3);

            assertThatThrownBy(() -> templateAuthoring.replaceSlots(
                    new TemplateAuthoring.ReplaceTemplateSlotsCommand(draft.templateId(),
                            List.of(new SlotDraft(UUID.randomUUID(), javaSkillId, null, null, 10000)),
                            author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("exactly one");
        }

        @Test
        @DisplayName("a slot count that contradicts the declared plan is refused")
        void slotCountMustMatchDeclaration() {
            var draft = draftTemplate("tpl-" + UUID.randomUUID(), 5);
            for (int i = 0; i < 5; i++) {
                publishQuestion("q-" + UUID.randomUUID());
            }
            weightAndPlan(draft.templateId(), 3);

            assertThatThrownBy(() -> templateAuthoring.publish(
                    new TemplateAuthoring.PublishTemplateCommand(draft.templateId(), author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("declares 5 core questions but has 3 slots");
        }

        @Test
        @DisplayName("a template the question bank cannot satisfy is refused")
        void unsatisfiablePlanIsRefused() {
            UUID lonelySkill = UUID.randomUUID();
            jdbc.update("INSERT INTO app.skills (id, code, name) VALUES (?, ?, ?)",
                    lonelySkill, "SK_" + UUID.randomUUID().toString().substring(0, 8), "Lonely");

            var draft = draftTemplate("tpl-" + UUID.randomUUID(), 3);
            templateAuthoring.replaceSkills(new TemplateAuthoring.ReplaceTemplateSkillsCommand(
                    draft.templateId(), List.of(new SkillWeight(lonelySkill, 10000)), author));
            templateAuthoring.replaceSlots(new TemplateAuthoring.ReplaceTemplateSlotsCommand(
                    draft.templateId(),
                    List.of(new SlotDraft(null, lonelySkill, null, null, 3334),
                            new SlotDraft(null, lonelySkill, null, null, 3333),
                            new SlotDraft(null, lonelySkill, null, null, 3333)),
                    author));

            // Failing here is far better than failing in front of a candidate.
            assertThatThrownBy(() -> templateAuthoring.publish(
                    new TemplateAuthoring.PublishTemplateCommand(draft.templateId(), author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("could not be planned");
        }

        @Test
        @DisplayName("a slot on an unweighted skill is refused")
        void slotSkillMustBeWeighted() {
            var draft = draftTemplate("tpl-" + UUID.randomUUID(), 3);
            for (int i = 0; i < 3; i++) {
                publishQuestion("q-" + UUID.randomUUID());
            }
            templateAuthoring.replaceSkills(new TemplateAuthoring.ReplaceTemplateSkillsCommand(
                    draft.templateId(), List.of(new SkillWeight(fixtures.skillId("SQL"), 10000)),
                    author));
            templateAuthoring.replaceSlots(new TemplateAuthoring.ReplaceTemplateSlotsCommand(
                    draft.templateId(), pooledSlots(3), author));

            assertThatThrownBy(() -> templateAuthoring.publish(
                    new TemplateAuthoring.PublishTemplateCommand(draft.templateId(), author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("does not weight");
        }

        @Test
        @DisplayName("a published template is immutable")
        void publishedTemplateIsImmutable() {
            String key = "tpl-immutable-" + UUID.randomUUID();
            var draft = draftTemplate(key, 3);
            for (int i = 0; i < 4; i++) {
                publishQuestion("q-" + UUID.randomUUID());
            }
            weightAndPlan(draft.templateId(), 3);
            templateAuthoring.publish(
                    new TemplateAuthoring.PublishTemplateCommand(draft.templateId(), author));

            assertThatThrownBy(() -> templateAuthoring.updateDraft(
                    new TemplateAuthoring.UpdateTemplateDraftCommand(draft.templateId(),
                            "Renamed", null, null, null, null, null, null, null, null, null,
                            author)))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("cannot be edited");
        }

        @Test
        @DisplayName("changing a published template creates version 2 with its plan copied")
        void newTemplateVersionCopiesComposition() {
            String key = "tpl-versioned-" + UUID.randomUUID();
            var draft = draftTemplate(key, 3);
            for (int i = 0; i < 4; i++) {
                publishQuestion("q-" + UUID.randomUUID());
            }
            weightAndPlan(draft.templateId(), 3);
            templateAuthoring.publish(
                    new TemplateAuthoring.PublishTemplateCommand(draft.templateId(), author));

            var v2 = templateAuthoring.createNextVersion(key, author);

            assertThat(v2.version()).isEqualTo(2);
            assertThat(v2.status()).isEqualTo("DRAFT");
            assertThat(v2.slots()).hasSize(3);
            assertThat(v2.skills()).hasSize(1);
            // v1 stays published and startable until v2 replaces it.
            assertThat(templateCatalog.findTemplate(draft.templateId()).orElseThrow().status())
                    .isEqualTo("PUBLISHED");
        }
    }

    // ------------------------------------------------ authored -> consumed

    @Test
    @DisplayName("content authored by M4 is consumed unchanged by the interview engine")
    void authoredCatalogueDrivesARealInterview() {
        // 1. Author and publish four questions.
        String suffix = UUID.randomUUID().toString();
        for (int i = 0; i < 4; i++) {
            publishQuestion("q-flow-%s-%d".formatted(suffix, i));
        }

        // 2. Author, weight, plan and publish a template.
        String templateKey = "tpl-flow-" + suffix;
        var draft = templateAuthoring.createTemplate(
                new TemplateAuthoring.CreateTemplateCommand(templateKey, "Authored Template",
                        null, null, null, "MID", 3, 4, 2, 18, 45, author));
        templateAuthoring.replaceSkills(new TemplateAuthoring.ReplaceTemplateSkillsCommand(
                draft.templateId(), List.of(new SkillWeight(javaSkillId, 10000)), author));
        templateAuthoring.replaceSlots(new TemplateAuthoring.ReplaceTemplateSlotsCommand(
                draft.templateId(),
                List.of(new SlotDraft(null, javaSkillId, null, null, 3334),
                        new SlotDraft(null, javaSkillId, null, null, 3333),
                        new SlotDraft(null, javaSkillId, null, null, 3333)),
                author));
        var published = templateAuthoring.publish(
                new TemplateAuthoring.PublishTemplateCommand(draft.templateId(), author));

        // 3. The interview engine starts from it, unmodified.
        UUID candidate = fixtures.insertUser("candidate-" + suffix);
        InterviewState state = interviews.start(
                StartInterviewCommand.of(candidate, templateKey));

        assertThat(state.progress().coreTotal()).isEqualTo(3);
        assertThat(state.interviewId()).isNotNull();

        // The attempt pinned the exact template version M4 published.
        assertThat(jdbc.queryForObject(
                "SELECT template_id FROM app.interviews WHERE id = ?",
                UUID.class, state.interviewId())).isEqualTo(published.templateId());

        // Every turn pins a version authored above, and it is published.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app.interview_questions q
                  JOIN app.question_versions v ON v.id = q.question_version_id
                 WHERE q.interview_id = ? AND v.status = 'PUBLISHED'
                """, Integer.class, state.interviewId())).isEqualTo(3);

        // 4. And the whole grading path works on authored content.
        state = interviews.advance(state.interviewId());
        UUID turnId = state.currentQuestion().interviewQuestionId();
        interviews.submitAnswer(SubmitAnswerCommand.of(state.interviewId(), turnId,
                "HashMap is not synchronised, so concurrent writes can corrupt the table."));
        interviews.evaluateAnswer(state.interviewId(), turnId);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.interview_questions WHERE id = ?",
                String.class, turnId)).isEqualTo("EVALUATED");
        // Graded against the rubric M4 authored, criterion by criterion.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app.evaluation_criterion_results r
                  JOIN app.evaluations e ON e.id = r.evaluation_id
                 WHERE e.answer_id = ? AND e.is_current
                """, Integer.class, turnId)).isEqualTo(3);
    }
}
