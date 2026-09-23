package com.aiinterview.interviewplatform.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiinterview.interviewplatform.interview.api.CompletionReason;
import com.aiinterview.interviewplatform.interview.api.InterviewService;
import com.aiinterview.interviewplatform.interview.api.InterviewState;
import com.aiinterview.interviewplatform.interview.api.InterviewStatus;
import com.aiinterview.interviewplatform.interview.api.StartInterviewCommand;
import com.aiinterview.interviewplatform.interview.api.SubmitAnswerCommand;
import com.aiinterview.interviewplatform.interview.api.TurnKind;
import com.aiinterview.interviewplatform.interview.api.TurnStatus;
import com.aiinterview.interviewplatform.shared.error.ApplicationException;
import com.aiinterview.interviewplatform.support.AbstractDatabaseTest;
import com.aiinterview.interviewplatform.support.SchemaFixtures;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The interview engine against a real PostgreSQL.
 *
 * <p>Covers what unit tests cannot: that the plan actually materialises, that
 * the database's own constraints are what make repeated commands safe, and that
 * the follow-up graph is built correctly enough for a later milestone to merge
 * its verdicts.
 */
@DisplayName("Interview engine, end to end")
class InterviewEngineIntegrationTest extends AbstractDatabaseTest {

    private static final String ANSWER =
            "HashMap is not synchronised, so concurrent writes can corrupt the table.";

    @Autowired
    private InterviewService interviews;

    private SchemaFixtures fixtures;
    private UUID javaSkillId;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
        javaSkillId = fixtures.skillId("JAVA");
    }

    // ------------------------------------------------------------ fixtures

    /** A published template plus a pool deep enough to fill its three slots. */
    private String publishTemplateWithBank(String label) {
        String unique = label + "-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            publishQuestion("q-%s-%d".formatted(unique, i));
        }
        String templateKey = "tpl-" + unique;
        fixtures.insertPublishedTemplate(templateKey, 1, javaSkillId);
        return templateKey;
    }

    /**
     * A question whose rubric {@link #ANSWER} <em>partially</em> covers.
     *
     * <p>The mix matters. An answer that matches nothing at all is judged
     * off-topic, and the engine deliberately does not probe an off-topic answer
     * — so a rubric of generic filler would produce no follow-ups and prove
     * nothing. A real answer covers some criteria and misses others, which is
     * exactly the condition a follow-up exists for.
     */
    private UUID publishQuestion(String key) {
        UUID versionId = fixtures.insertDraftQuestionVersion(javaSkillId, key);
        addCriterion(versionId, "THREAD_SAFETY", 0,
                "States that HashMap is not synchronised for concurrent writes", 3334);
        addCriterion(versionId, "NULL_HANDLING", 1,
                "States that ConcurrentHashMap forbids null keys and values", 3333);
        addCriterion(versionId, "RESIZING", 2,
                "Describes the resizing behaviour of the internal bucket array", 3333);
        fixtures.publishQuestionVersion(versionId);
        return versionId;
    }

    private void addCriterion(UUID versionId, String code, int order, String expectation,
                              int weightBp) {
        jdbc.update("""
                INSERT INTO app.rubric_criteria
                    (id, question_version_id, code, label, expectation, weight_bp, tier,
                     follow_up_prompt, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, 'CORE', ?, ?)
                """, UUID.randomUUID(), versionId, code, code, expectation, weightBp,
                "Can you say more about %s?".formatted(code), (short) order);
    }

    private UUID candidate(String label) {
        return fixtures.insertUser(label + "-" + UUID.randomUUID());
    }

    /** Answers and grades every core turn, leaving the attempt ready to advance. */
    private InterviewState answerAndGradeAllCore(UUID interviewId) {
        InterviewState state = interviews.advance(interviewId);
        while (state.hasQuestion()
                && state.currentQuestion().kind() == TurnKind.CORE) {
            UUID turnId = state.currentQuestion().interviewQuestionId();
            interviews.submitAnswer(SubmitAnswerCommand.of(interviewId, turnId, ANSWER));
            interviews.evaluateAnswer(interviewId, turnId);
            state = interviews.advance(interviewId);
        }
        return state;
    }

    // --------------------------------------------------------------- start

    @Test
    @DisplayName("starting materialises the whole plan up front")
    void startMaterialisesThePlan() {
        String templateKey = publishTemplateWithBank("start");
        UUID candidateId = candidate("start");

        InterviewState state = interviews.start(
                StartInterviewCommand.of(candidateId, templateKey));

        assertThat(state.status()).isEqualTo(InterviewStatus.IN_PROGRESS);
        assertThat(state.progress().coreTotal()).isEqualTo(3);
        assertThat(state.timeline()).hasSize(3);
        assertThat(state.timeline()).allMatch(t -> t.status() == TurnStatus.PENDING);

        // Deciding every question at start is what keeps grading off the
        // candidate's critical path later.
        Integer planned = jdbc.queryForObject(
                "SELECT count(*) FROM app.interview_questions WHERE interview_id = ?",
                Integer.class, state.interviewId());
        assertThat(planned).isEqualTo(3);
    }

    @Test
    @DisplayName("every planned turn pins a published question version")
    void everyTurnPinsAVersion() {
        String templateKey = publishTemplateWithBank("pin");
        InterviewState state = interviews.start(
                StartInterviewCommand.of(candidate("pin"), templateKey));

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT q.question_version_id, v.status, q.skill_id, q.weight_bp, q.kind
                  FROM app.interview_questions q
                  JOIN app.question_versions v ON v.id = q.question_version_id
                 WHERE q.interview_id = ?
                """, state.interviewId());

        assertThat(rows).hasSize(3);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("question_version_id")).isNotNull();
            assertThat(row.get("status")).isEqualTo("PUBLISHED");
            // Snapshotted so the score stays recomputable from attempt rows alone.
            assertThat(row.get("skill_id")).isEqualTo(javaSkillId);
            assertThat((Integer) row.get("weight_bp")).isPositive();
            assertThat(row.get("kind")).isEqualTo("CORE");
        }
    }

    @Test
    @DisplayName("no question appears twice in one plan")
    void planContainsNoDuplicates() {
        String templateKey = publishTemplateWithBank("unique");
        InterviewState state = interviews.start(
                StartInterviewCommand.of(candidate("unique"), templateKey));

        Integer distinct = jdbc.queryForObject("""
                SELECT count(DISTINCT v.question_id)
                  FROM app.interview_questions q
                  JOIN app.question_versions v ON v.id = q.question_version_id
                 WHERE q.interview_id = ?
                """, Integer.class, state.interviewId());

        assertThat(distinct).isEqualTo(3);
    }

    @Test
    @DisplayName("starting again resumes the live attempt instead of creating a second")
    void startIsIdempotent() {
        String templateKey = publishTemplateWithBank("resume");
        UUID candidateId = candidate("resume");

        InterviewState first = interviews.start(
                StartInterviewCommand.of(candidateId, templateKey));
        InterviewState second = interviews.start(
                StartInterviewCommand.of(candidateId, templateKey));

        assertThat(second.interviewId()).isEqualTo(first.interviewId());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.interviews WHERE candidate_user_id = ?",
                Integer.class, candidateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a template with too small a bank refuses to start rather than shrink")
    void planningFailsRatherThanServingAShortInterview() {
        String unique = "thin-" + UUID.randomUUID();
        // A skill of its own, so the pool really is empty — every other test in
        // this class publishes JAVA questions into the same database.
        UUID lonelySkill = UUID.randomUUID();
        jdbc.update("INSERT INTO app.skills (id, code, name) VALUES (?, ?, ?)",
                lonelySkill, "SKILL_" + unique.toUpperCase().replace("-", "_"), "Lonely skill");

        String templateKey = "tpl-" + unique;
        fixtures.insertPublishedTemplate(templateKey, 1, lonelySkill);
        UUID candidateId = candidate("thin");

        // A candidate who answers six questions when the template promised
        // eight has a score that means something different from everyone else's.
        assertThatThrownBy(() -> interviews.start(
                StartInterviewCommand.of(candidateId, templateKey)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("No published question is available");

        // Nothing half-planned is left behind: the candidate has no attempt.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.interviews WHERE candidate_user_id = ?",
                Integer.class, candidateId)).isZero();
    }

    // ------------------------------------------------------------- advance

    @Test
    @DisplayName("advancing serves the lowest open turn, and repeating returns the same one")
    void advanceIsIdempotent() {
        String templateKey = publishTemplateWithBank("advance");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("advance"), templateKey)).interviewId();

        InterviewState first = interviews.advance(interviewId);
        InterviewState second = interviews.advance(interviewId);

        assertThat(first.currentQuestion()).isNotNull();
        assertThat(second.currentQuestion().interviewQuestionId())
                .isEqualTo(first.currentQuestion().interviewQuestionId());
        assertThat(first.currentQuestion().position()).isEqualTo(1);

        // Two callers converge on one question rather than consuming two.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app.interview_questions
                 WHERE interview_id = ? AND status = 'ASKED'
                """, Integer.class, interviewId)).isEqualTo(1);
    }

    @Test
    @DisplayName("re-serving a turn does not reset the candidate's clock")
    void askTimeIsPreserved() {
        String templateKey = publishTemplateWithBank("clock");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("clock"), templateKey)).interviewId();

        var firstAsked = interviews.advance(interviewId).currentQuestion().askedAt();
        var secondAsked = interviews.advance(interviewId).currentQuestion().askedAt();

        assertThat(secondAsked).isEqualTo(firstAsked);
    }

    // ------------------------------------------------------------- answers

    @Test
    @DisplayName("an answer is stored before anything grades it")
    void answerIsDurableBeforeGrading() {
        String templateKey = publishTemplateWithBank("answer");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("answer"), templateKey)).interviewId();
        UUID turnId = interviews.advance(interviewId).currentQuestion().interviewQuestionId();

        InterviewService.SubmitAnswerResult result = interviews.submitAnswer(
                SubmitAnswerCommand.of(interviewId, turnId, ANSWER));

        assertThat(result.duplicate()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT content_text FROM app.answers WHERE interview_question_id = ?",
                String.class, turnId)).isEqualTo(ANSWER);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.interview_questions WHERE id = ?",
                String.class, turnId)).isEqualTo("ANSWERED");
    }

    @Test
    @DisplayName("submitting the same answer twice keeps exactly one")
    void submitIsIdempotent() {
        String templateKey = publishTemplateWithBank("dup");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("dup"), templateKey)).interviewId();
        UUID turnId = interviews.advance(interviewId).currentQuestion().interviewQuestionId();

        interviews.submitAnswer(SubmitAnswerCommand.of(interviewId, turnId, ANSWER));
        InterviewService.SubmitAnswerResult second = interviews.submitAnswer(
                SubmitAnswerCommand.of(interviewId, turnId, "a different answer"));

        assertThat(second.duplicate()).isTrue();
        // The answer table's primary key, not application bookkeeping.
        assertThat(jdbc.queryForObject(
                "SELECT content_text FROM app.answers WHERE interview_question_id = ?",
                String.class, turnId)).isEqualTo(ANSWER);
    }

    @Test
    @DisplayName("a turn from another interview is simply not found")
    void turnsAreScopedToTheirInterview() {
        String templateKey = publishTemplateWithBank("scope");
        UUID first = interviews.start(
                StartInterviewCommand.of(candidate("scope-a"), templateKey)).interviewId();
        UUID second = interviews.start(
                StartInterviewCommand.of(candidate("scope-b"), templateKey)).interviewId();
        UUID foreignTurn = interviews.advance(second).currentQuestion().interviewQuestionId();

        assertThatThrownBy(() -> interviews.submitAnswer(
                SubmitAnswerCommand.of(first, foreignTurn, ANSWER)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("not part of interview");
    }

    @Test
    @DisplayName("a skip is recorded honestly rather than hidden")
    void skipIsRecorded() {
        String templateKey = publishTemplateWithBank("skip");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("skip"), templateKey)).interviewId();
        UUID turnId = interviews.advance(interviewId).currentQuestion().interviewQuestionId();

        interviews.skip(interviewId, turnId);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.interview_questions WHERE id = ?",
                String.class, turnId)).isEqualTo("SKIPPED");
        // A skip carries no answer, which is what makes it different from a
        // grading failure.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.answers WHERE interview_question_id = ?",
                Integer.class, turnId)).isZero();
    }

    // ------------------------------------------------------------- grading

    @Test
    @DisplayName("grading runs through the evaluation module and settles the turn")
    void gradingDelegatesToEvaluation() {
        String templateKey = publishTemplateWithBank("grade");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("grade"), templateKey)).interviewId();
        UUID turnId = interviews.advance(interviewId).currentQuestion().interviewQuestionId();
        interviews.submitAnswer(SubmitAnswerCommand.of(interviewId, turnId, ANSWER));

        interviews.evaluateAnswer(interviewId, turnId);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.interview_questions WHERE id = ?",
                String.class, turnId)).isEqualTo("EVALUATED");

        // The evaluation belongs to the evaluation module's tables, and grades
        // against the version the turn pinned.
        Map<String, Object> evaluation = jdbc.queryForMap("""
                SELECT e.question_version_id, e.status
                  FROM app.evaluations e
                 WHERE e.answer_id = ? AND e.is_current
                """, turnId);
        assertThat(evaluation.get("status")).isEqualTo("SUCCEEDED");
        assertThat(evaluation.get("question_version_id")).isEqualTo(jdbc.queryForObject(
                "SELECT question_version_id FROM app.interview_questions WHERE id = ?",
                UUID.class, turnId));
    }

    @Test
    @DisplayName("re-grading does not call the provider again")
    void gradingIsIdempotent() {
        String templateKey = publishTemplateWithBank("regrade");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("regrade"), templateKey)).interviewId();
        UUID turnId = interviews.advance(interviewId).currentQuestion().interviewQuestionId();
        interviews.submitAnswer(SubmitAnswerCommand.of(interviewId, turnId, ANSWER));

        interviews.evaluateAnswer(interviewId, turnId);
        interviews.evaluateAnswer(interviewId, turnId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.evaluations WHERE answer_id = ?",
                Integer.class, turnId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.ai_invocations WHERE answer_id = ?",
                Integer.class, turnId)).isEqualTo(1);
    }

    @Test
    @DisplayName("grading an unanswered turn is refused, not faked")
    void gradingRequiresAnAnswer() {
        String templateKey = publishTemplateWithBank("nograde");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("nograde"), templateKey)).interviewId();
        UUID turnId = interviews.advance(interviewId).currentQuestion().interviewQuestionId();

        assertThatThrownBy(() -> interviews.evaluateAnswer(interviewId, turnId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("no answer to grade");
    }

    // ----------------------------------------------------------- follow-ups

    @Test
    @DisplayName("follow-ups are chosen after the core plan, and form a correct graph")
    void followUpsBuildTheParentChildGraph() {
        String templateKey = publishTemplateWithBank("followup");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("followup"), templateKey)).interviewId();

        InterviewState afterCore = answerAndGradeAllCore(interviewId);

        List<Map<String, Object>> followUps = jdbc.queryForList("""
                SELECT id, parent_id, weight_bp, follow_up_criterion_id, prompt_text,
                       question_version_id, skill_id
                  FROM app.interview_questions
                 WHERE interview_id = ? AND kind = 'FOLLOW_UP'
                 ORDER BY position
                """, interviewId);

        assertThat(followUps).isNotEmpty();
        for (Map<String, Object> followUp : followUps) {
            // The graph M5 needs to merge a probe's verdict back into its parent.
            assertThat(followUp.get("parent_id")).isNotNull();
            assertThat(followUp.get("follow_up_criterion_id")).isNotNull();
            assertThat(followUp.get("prompt_text")).isNotNull();
            // Zero weight: a probe must not let a weak topic dominate the score
            // purely because it was asked about twice.
            assertThat(followUp.get("weight_bp")).isEqualTo(0);
        }

        // Every parent is a core turn of this same interview.
        Integer orphaned = jdbc.queryForObject("""
                SELECT count(*) FROM app.interview_questions c
                 WHERE c.interview_id = ? AND c.kind = 'FOLLOW_UP'
                   AND NOT EXISTS (SELECT 1 FROM app.interview_questions p
                                    WHERE p.id = c.parent_id
                                      AND p.interview_id = c.interview_id
                                      AND p.kind = 'CORE')
                """, Integer.class, interviewId);
        assertThat(orphaned).isZero();

        assertThat(afterCore.progress().followUpsAsked()).isEqualTo(followUps.size());
    }

    @Test
    @DisplayName("the follow-up budget is respected")
    void followUpBudgetIsRespected() {
        String templateKey = publishTemplateWithBank("budget");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("budget"), templateKey)).interviewId();

        answerAndGradeAllCore(interviewId);

        Integer total = jdbc.queryForObject("""
                SELECT count(*) FROM app.interview_questions
                 WHERE interview_id = ? AND kind = 'FOLLOW_UP'
                """, Integer.class, interviewId);
        Integer maxPerParent = jdbc.queryForObject("""
                SELECT coalesce(max(c), 0) FROM (
                    SELECT count(*) AS c FROM app.interview_questions
                     WHERE interview_id = ? AND kind = 'FOLLOW_UP'
                     GROUP BY parent_id) counts
                """, Integer.class, interviewId);

        assertThat(total).isLessThanOrEqualTo(4);
        assertThat(maxPerParent).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("the follow-up phase runs at most once")
    void followUpSelectionIsIdempotent() {
        String templateKey = publishTemplateWithBank("once");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("once"), templateKey)).interviewId();

        answerAndGradeAllCore(interviewId);
        Integer after = jdbc.queryForObject("""
                SELECT count(*) FROM app.interview_questions
                 WHERE interview_id = ? AND kind = 'FOLLOW_UP'
                """, Integer.class, interviewId);

        interviews.advance(interviewId);
        interviews.advance(interviewId);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app.interview_questions
                 WHERE interview_id = ? AND kind = 'FOLLOW_UP'
                """, Integer.class, interviewId)).isEqualTo(after);

        // The marker is what distinguishes "chose none" from "not yet looked".
        assertThat(jdbc.queryForObject(
                "SELECT follow_ups_selected_at FROM app.interviews WHERE id = ?",
                java.time.OffsetDateTime.class, interviewId)).isNotNull();
    }

    @Test
    @DisplayName("ungraded answers skip the follow-up phase rather than block on it")
    void unsettledGradingSkipsFollowUps() {
        String templateKey = publishTemplateWithBank("unsettled");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("unsettled"), templateKey)).interviewId();

        // Answer everything but grade nothing.
        InterviewState state = interviews.advance(interviewId);
        while (state.hasQuestion()) {
            UUID turnId = state.currentQuestion().interviewQuestionId();
            interviews.submitAnswer(SubmitAnswerCommand.of(interviewId, turnId, ANSWER));
            state = interviews.advance(interviewId);
        }

        // Waiting would put a model call back on the candidate's critical path.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app.interview_questions
                 WHERE interview_id = ? AND kind = 'FOLLOW_UP'
                """, Integer.class, interviewId)).isZero();
        assertThat(state.status()).isEqualTo(InterviewStatus.COMPLETING);
    }

    @Test
    @DisplayName("an off-topic answer is not probed")
    void offTopicAnswersAreNotProbed() {
        String templateKey = publishTemplateWithBank("offtopic");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("offtopic"), templateKey)).interviewId();

        // Matches no criterion at all, which the grader reports as off topic.
        String irrelevant = "I would like to talk about my holiday plans for next summer.";
        InterviewState state = interviews.advance(interviewId);
        while (state.hasQuestion()) {
            UUID turnId = state.currentQuestion().interviewQuestionId();
            interviews.submitAnswer(SubmitAnswerCommand.of(interviewId, turnId, irrelevant));
            interviews.evaluateAnswer(interviewId, turnId);
            state = interviews.advance(interviewId);
        }

        // There is no weakness to explore in an answer to a different question,
        // and re-engaging with one would waste the candidate's remaining time.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app.interview_questions
                 WHERE interview_id = ? AND kind = 'FOLLOW_UP'
                """, Integer.class, interviewId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT bool_and(answer_off_topic) FROM app.evaluations e
                  JOIN app.interview_questions q ON q.id = e.answer_id
                 WHERE q.interview_id = ? AND e.is_current
                """, Boolean.class, interviewId)).isTrue();
    }

    // ---------------------------------------------------------- completion

    @Test
    @DisplayName("finishing early records the unreached questions as unanswered")
    void earlyFinishClosesRemainingQuestions() {
        String templateKey = publishTemplateWithBank("earlyclose");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("earlyclose"), templateKey)).interviewId();
        UUID turnId = interviews.advance(interviewId).currentQuestion().interviewQuestionId();
        interviews.submitAnswer(SubmitAnswerCommand.of(interviewId, turnId, ANSWER));
        interviews.evaluateAnswer(interviewId, turnId);

        InterviewState state = interviews.complete(interviewId);

        assertThat(state.status()).isEqualTo(InterviewStatus.COMPLETED);
        // Counted as skipped rather than dropped: excluding them would inflate
        // the score of someone who simply stopped.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app.interview_questions
                 WHERE interview_id = ? AND status = 'SKIPPED'
                """, Integer.class, interviewId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app.interview_questions
                 WHERE interview_id = ? AND status IN ('PENDING','ASKED')
                """, Integer.class, interviewId)).isZero();
    }

    @Test
    @DisplayName("an attempt closes once every turn has settled")
    void completionRequiresSettledGrading() {
        String templateKey = publishTemplateWithBank("complete");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("complete"), templateKey)).interviewId();

        answerAndGradeAllCore(interviewId);

        // Answer and grade the follow-ups too.
        InterviewState state = interviews.advance(interviewId);
        while (state.hasQuestion()) {
            UUID turnId = state.currentQuestion().interviewQuestionId();
            interviews.submitAnswer(SubmitAnswerCommand.of(interviewId, turnId, ANSWER));
            interviews.evaluateAnswer(interviewId, turnId);
            state = interviews.advance(interviewId);
        }

        assertThat(state.status()).isEqualTo(InterviewStatus.COMPLETED);
        assertThat(state.completionReason()).isEqualTo(CompletionReason.ALL_ANSWERED);
        assertThat(jdbc.queryForObject(
                "SELECT completed_at FROM app.interviews WHERE id = ?",
                java.time.OffsetDateTime.class, interviewId)).isNotNull();
    }

    @Test
    @DisplayName("finishing early holds at COMPLETING while grading is outstanding")
    void completingWaitsForGrading() {
        String templateKey = publishTemplateWithBank("early");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("early"), templateKey)).interviewId();
        UUID turnId = interviews.advance(interviewId).currentQuestion().interviewQuestionId();
        interviews.submitAnswer(SubmitAnswerCommand.of(interviewId, turnId, ANSWER));

        InterviewState state = interviews.complete(interviewId);

        // "Preparing your report" is an honest thing to say here; "done" is not.
        assertThat(state.status()).isEqualTo(InterviewStatus.COMPLETING);
        assertThat(state.completionReason()).isEqualTo(CompletionReason.CANDIDATE_FINISHED);

        interviews.evaluateAnswer(interviewId, turnId);
        assertThat(interviews.finalizeCompletion(interviewId).status())
                .isEqualTo(InterviewStatus.COMPLETED);
    }

    @Test
    @DisplayName("completing an already-completed attempt changes nothing")
    void completeIsIdempotent() {
        String templateKey = publishTemplateWithBank("recomplete");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("recomplete"), templateKey)).interviewId();
        UUID turnId = interviews.advance(interviewId).currentQuestion().interviewQuestionId();
        interviews.skip(interviewId, turnId);
        interviews.complete(interviewId);

        InterviewState first = interviews.getState(interviewId);
        InterviewState second = interviews.complete(interviewId);

        assertThat(second.status()).isEqualTo(first.status());
        assertThat(second.completionReason()).isEqualTo(first.completionReason());
    }

    @Test
    @DisplayName("a completed attempt accepts no further answers")
    void completedAttemptsRejectAnswers() {
        String templateKey = publishTemplateWithBank("closed");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("closed"), templateKey)).interviewId();
        UUID turnId = interviews.advance(interviewId).currentQuestion().interviewQuestionId();
        interviews.abandon(interviewId, CompletionReason.ABANDONED_BY_CANDIDATE, null);

        assertThatThrownBy(() -> interviews.submitAnswer(
                SubmitAnswerCommand.of(interviewId, turnId, ANSWER)))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    @DisplayName("abandoning releases the candidate's live-attempt slot")
    void abandonReleasesTheLiveSlot() {
        String templateKey = publishTemplateWithBank("abandon");
        UUID candidateId = candidate("abandon");
        UUID first = interviews.start(
                StartInterviewCommand.of(candidateId, templateKey)).interviewId();

        interviews.abandon(first, CompletionReason.ABANDONED_BY_CANDIDATE, null);
        UUID second = interviews.start(
                StartInterviewCommand.of(candidateId, templateKey)).interviewId();

        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.interviews WHERE id = ?", String.class, first))
                .isEqualTo("ABANDONED");
    }

    // ------------------------------------------------------ derived state

    @Test
    @DisplayName("state is rebuilt from the turn rows, so a refresh restores exactly")
    void stateIsDerived() {
        String templateKey = publishTemplateWithBank("derived");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("derived"), templateKey)).interviewId();
        UUID turnId = interviews.advance(interviewId).currentQuestion().interviewQuestionId();
        interviews.submitAnswer(SubmitAnswerCommand.of(interviewId, turnId, ANSWER));

        InterviewState reread = interviews.getState(interviewId);

        assertThat(reread.progress().answeredTotal()).isEqualTo(1);
        assertThat(reread.progress().evaluatedTotal()).isZero();
        assertThat(reread.progress().coreAnswered()).isEqualTo(1);
        // The next open turn, not the one just answered.
        assertThat(reread.currentQuestion().interviewQuestionId()).isNotEqualTo(turnId);
    }

    @Test
    @DisplayName("interview state never exposes a score mid-interview")
    void stateCarriesNoFeedback() {
        String templateKey = publishTemplateWithBank("nofeedback");
        UUID interviewId = interviews.start(
                StartInterviewCommand.of(candidate("nofeedback"), templateKey)).interviewId();
        UUID turnId = interviews.advance(interviewId).currentQuestion().interviewQuestionId();
        interviews.submitAnswer(SubmitAnswerCommand.of(interviewId, turnId, ANSWER));
        interviews.evaluateAnswer(interviewId, turnId);

        InterviewState state = interviews.getState(interviewId);

        // Seeing a score would let later answers be tuned to earlier feedback.
        assertThat(state.timeline()).allSatisfy(turn ->
                assertThat(turn.getClass().getRecordComponents())
                        .noneMatch(c -> c.getName().toLowerCase().contains("score")));
        assertThat(state.timeline().get(0).status()).isEqualTo(TurnStatus.EVALUATED);
    }
}
