package com.aiinterview.interviewplatform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiinterview.interviewplatform.interview.api.InterviewService;
import com.aiinterview.interviewplatform.shared.jobs.api.JobQueue;
import com.aiinterview.interviewplatform.shared.jobs.api.JobType;
import com.aiinterview.interviewplatform.support.AbstractDatabaseTest;
import com.aiinterview.interviewplatform.support.SchemaFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The candidate HTTP API against a real database.
 *
 * <p>Exercised through the full servlet stack — security chain, Jackson, the
 * exception handler — rather than by calling the controller as an object. The
 * things most likely to break here are the things a direct call cannot see: a
 * 401 from a filter, a serialisation that drops a field, an error shaped
 * differently from every other error.
 *
 * <p>Nothing here calls a model. Grading runs through the deterministic
 * provider, because what is under test is the API's behaviour around evaluation
 * — queued, settled, failed — not the quality of a verdict.
 */
@DisplayName("Candidate interview API")
@AutoConfigureMockMvc
class InterviewApiIntegrationTest extends AbstractDatabaseTest {

    private static final String ANSWER =
            "HashMap is not synchronised, so concurrent writes can corrupt the table. "
                    + "ConcurrentHashMap locks per bin instead of the whole map.";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private InterviewService interviews;

    @Autowired
    private JobQueue jobs;

    private SchemaFixtures fixtures;
    private UUID skillId;
    private UUID candidate;
    private String templateKey;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
        skillId = fixtures.skillId("JAVA");
        candidate = fixtures.insertUser("api-" + UUID.randomUUID());
        templateKey = publishTemplate();
    }

    // ------------------------------------------------------------ fixtures

    private String publishTemplate() {
        for (int i = 0; i < 5; i++) {
            UUID versionId = fixtures.insertDraftQuestionVersion(
                    skillId, "api-q-%s-%d".formatted(UUID.randomUUID(), i));
            fixtures.addRubricCriteria(versionId, 3);
            fixtures.publishQuestionVersion(versionId);
        }
        String key = "api-tpl-" + UUID.randomUUID();
        fixtures.insertPublishedTemplate(key, 1, skillId);
        return key;
    }

    private JsonNode startInterview() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/interviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"candidateUserId":"%s","templateKey":"%s"}"""
                                .formatted(candidate, templateKey)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode advance(UUID interviewId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/interviews/{id}/advance", interviewId))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult submit(UUID interviewId, UUID turnId, String text) throws Exception {
        return mvc.perform(post("/api/v1/interviews/{id}/answers", interviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"interviewQuestionId":"%s","contentText":"%s"}"""
                                .formatted(turnId, text)))
                .andReturn();
    }

    /** Drains the queue the way the worker would, without a background thread. */
    private void gradePending(UUID interviewId) {
        jobs.enqueue(JobQueue.JobRequest.of(JobType.MAINTENANCE, "noop-" + UUID.randomUUID(), "{}"));
        interviews.getState(interviewId).timeline().stream()
                .filter(turn -> turn.status().hasAnswer())
                .forEach(turn -> {
                    try {
                        interviews.evaluateAnswer(interviewId, turn.interviewQuestionId());
                    } catch (RuntimeException ignored) {
                        // Already graded, or not gradable. Either way the API
                        // behaviour under test is unaffected.
                    }
                });
    }

    // -------------------------------------------------------------- start

    @Nested
    @DisplayName("Start")
    class Start {

        @Test
        @DisplayName("returns the planned attempt with its first question not yet served")
        void startsAnAttempt() throws Exception {
            JsonNode body = startInterview();

            assertThat(body.get("interviewId").asText()).isNotBlank();
            assertThat(body.get("status").asText()).isEqualTo("IN_PROGRESS");
            assertThat(body.get("progress").get("coreTotal").asInt()).isEqualTo(3);
            assertThat(body.get("timeline")).hasSize(3);

            // The first turn is visible but PENDING, and askedAt is null: planned
            // and not yet shown. Advancing is what records that the candidate saw
            // it, which is the distinction that makes "they abandoned at question
            // five" answerable later.
            assertThat(body.get("currentQuestion").isNull()).isFalse();
            assertThat(body.get("currentQuestion").get("askedAt").isNull()).isTrue();
            assertThat(body.get("timeline").get(0).get("status").asText()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("never exposes a score")
        void carriesNoScore() throws Exception {
            String body = startInterview().toString();

            // The whole reason state and result are different endpoints.
            assertThat(body).doesNotContain("score").doesNotContain("verdict");
        }

        @Test
        @DisplayName("a repeated start resumes rather than creating a second attempt")
        void repeatedStartResumes() throws Exception {
            String first = startInterview().get("interviewId").asText();
            String second = startInterview().get("interviewId").asText();

            assertThat(second)
                    .as("a double-clicked start button must not consume the "
                            + "candidate's one live attempt slot twice")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("an unknown template is a 404 with a usable code")
        void unknownTemplate() throws Exception {
            mvc.perform(post("/api/v1/interviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"candidateUserId":"%s","templateKey":"no-such-template"}"""
                                    .formatted(candidate)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("a missing field is rejected before anything is created")
        void validationFailure() throws Exception {
            mvc.perform(post("/api/v1/interviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"templateKey":"%s"}""".formatted(templateKey)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("candidateUserId"));
        }

        @Test
        @DisplayName("a syntactically broken body is a 400, not a 422")
        void malformedBody() throws Exception {
            mvc.perform(post("/api/v1/interviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
    }

    // ------------------------------------------------------------ question

    @Nested
    @DisplayName("Advance")
    class Advance {

        @Test
        @DisplayName("serves the first question")
        void servesAQuestion() throws Exception {
            UUID interviewId = UUID.fromString(startInterview().get("interviewId").asText());

            JsonNode body = advance(interviewId);
            JsonNode question = body.get("currentQuestion");

            assertThat(question.isNull()).isFalse();
            assertThat(question.get("position").asInt()).isEqualTo(1);
            assertThat(question.get("promptText").asText()).isNotBlank();
            assertThat(question.get("isFollowUp").asBoolean()).isFalse();
            assertThat(question.get("askedAt").isNull()).isFalse();
        }

        @Test
        @DisplayName("is idempotent — a refresh does not consume a question")
        void repeatedAdvanceReturnsTheSameTurn() throws Exception {
            UUID interviewId = UUID.fromString(startInterview().get("interviewId").asText());

            String first = advance(interviewId).get("currentQuestion")
                    .get("interviewQuestionId").asText();
            String second = advance(interviewId).get("currentQuestion")
                    .get("interviewQuestionId").asText();

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("an unknown interview is a 404")
        void unknownInterview() throws Exception {
            mvc.perform(post("/api/v1/interviews/{id}/advance", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("a malformed id is a validation failure, not a 500")
        void malformedId() throws Exception {
            mvc.perform(get("/api/v1/interviews/{id}", "not-a-uuid"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    // ------------------------------------------------------------- answers

    @Nested
    @DisplayName("Answer")
    class Answers {

        @Test
        @DisplayName("is accepted and returns immediately, without waiting for grading")
        void acceptsAnAnswer() throws Exception {
            UUID interviewId = UUID.fromString(startInterview().get("interviewId").asText());
            UUID turnId = UUID.fromString(advance(interviewId).get("currentQuestion")
                    .get("interviewQuestionId").asText());

            MvcResult result = submit(interviewId, turnId, ANSWER);
            JsonNode body = json.readTree(result.getResponse().getContentAsString());

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body.get("duplicate").asBoolean()).isFalse();
            // ANSWERED, not EVALUATED: the model has not been called yet, and
            // the request did not wait for it.
            assertThat(body.get("state").get("progress").get("answeredTotal").asInt()).isEqualTo(1);
            assertThat(body.get("state").get("progress").get("evaluatedTotal").asInt()).isZero();
            assertThat(body.get("state").get("progress").get("gradingSettled").asBoolean())
                    .isFalse();
        }

        @Test
        @DisplayName("a duplicate submission succeeds and keeps the stored answer")
        void duplicateSubmissionIsSafe() throws Exception {
            UUID interviewId = UUID.fromString(startInterview().get("interviewId").asText());
            UUID turnId = UUID.fromString(advance(interviewId).get("currentQuestion")
                    .get("interviewQuestionId").asText());

            submit(interviewId, turnId, ANSWER);
            MvcResult second = submit(interviewId, turnId, "A completely different answer.");
            JsonNode body = json.readTree(second.getResponse().getContentAsString());

            assertThat(second.getResponse().getStatus())
                    .as("a double-click is not an error; returning one would invite "
                            + "the client to retry into a loop")
                    .isEqualTo(200);
            assertThat(body.get("duplicate").asBoolean()).isTrue();
            assertThat(body.get("state").get("progress").get("answeredTotal").asInt())
                    .as("the second submission must not create a second answer")
                    .isEqualTo(1);

            String stored = jdbc.queryForObject(
                    "SELECT content_text FROM app.answers WHERE interview_question_id = ?",
                    String.class, turnId);
            assertThat(stored)
                    .as("the first answer wins; a retry must not overwrite what was graded")
                    .isEqualTo(ANSWER);
        }

        @Test
        @DisplayName("enqueues exactly one grading job however many times it is submitted")
        void queuesGradingOnce() throws Exception {
            UUID interviewId = UUID.fromString(startInterview().get("interviewId").asText());
            UUID turnId = UUID.fromString(advance(interviewId).get("currentQuestion")
                    .get("interviewQuestionId").asText());

            submit(interviewId, turnId, ANSWER);
            submit(interviewId, turnId, ANSWER);

            Integer queued = jdbc.queryForObject("""
                    SELECT count(*) FROM app.jobs
                    WHERE job_type = 'EVALUATE_ANSWER' AND dedupe_key LIKE ?
                    """, Integer.class, "%" + turnId);
            assertThat(queued)
                    .as("the partial unique dedupe index is what makes this true")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a turn from another interview is refused")
        void foreignTurnIsRefused() throws Exception {
            UUID interviewId = UUID.fromString(startInterview().get("interviewId").asText());
            advance(interviewId);

            MvcResult result = submit(interviewId, UUID.randomUUID(), ANSWER);

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).contains("NOT_FOUND");
        }

        @Test
        @DisplayName("answering a finished interview is a conflict, not a silent no-op")
        void answeringAfterCompletionIsRefused() throws Exception {
            UUID interviewId = UUID.fromString(startInterview().get("interviewId").asText());
            UUID turnId = UUID.fromString(advance(interviewId).get("currentQuestion")
                    .get("interviewQuestionId").asText());
            mvc.perform(post("/api/v1/interviews/{id}/completion", interviewId))
                    .andExpect(status().isOk());

            MvcResult result = submit(interviewId, turnId, ANSWER);

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString())
                    .contains("INTERVIEW_NOT_IN_PROGRESS");
        }

        @Test
        @DisplayName("an over-long answer is rejected by validation")
        void oversizedAnswer() throws Exception {
            UUID interviewId = UUID.fromString(startInterview().get("interviewId").asText());
            UUID turnId = UUID.fromString(advance(interviewId).get("currentQuestion")
                    .get("interviewQuestionId").asText());

            mvc.perform(post("/api/v1/interviews/{id}/answers", interviewId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(java.util.Map.of(
                                    "interviewQuestionId", turnId.toString(),
                                    "contentText", "x".repeat(20_001)))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    // -------------------------------------------------------------- result

    @Nested
    @DisplayName("Result")
    class Result {

        @Test
        @DisplayName("is refused while the interview is still in progress")
        void refusedWhileInProgress() throws Exception {
            UUID interviewId = UUID.fromString(startInterview().get("interviewId").asText());

            mvc.perform(get("/api/v1/interviews/{id}/result", interviewId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RESULT_NOT_AVAILABLE"));
        }

        @Test
        @DisplayName("carries the backend's score, verdicts and the candidate's own words")
        void completedInterviewHasAScore() throws Exception {
            UUID interviewId = UUID.fromString(startInterview().get("interviewId").asText());

            for (int i = 0; i < 3; i++) {
                JsonNode question = advance(interviewId).get("currentQuestion");
                if (question.isNull()) {
                    break;
                }
                submit(interviewId,
                        UUID.fromString(question.get("interviewQuestionId").asText()), ANSWER);
            }
            gradePending(interviewId);
            mvc.perform(post("/api/v1/interviews/{id}/completion", interviewId));
            gradePending(interviewId);
            mvc.perform(post("/api/v1/interviews/{id}/completion", interviewId));

            MvcResult result = mvc.perform(get("/api/v1/interviews/{id}/result", interviewId))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode body = json.readTree(result.getResponse().getContentAsString());

            assertThat(body.get("status").asText()).isIn("COMPLETED", "COMPLETING");
            assertThat(body.get("questions")).isNotEmpty();

            JsonNode graded = null;
            for (JsonNode question : body.get("questions")) {
                if (question.get("graded").asBoolean()) {
                    graded = question;
                    break;
                }
            }
            assertThat(graded).as("at least one question should have been graded").isNotNull();
            assertThat(graded.get("criteria")).isNotEmpty();
            assertThat(graded.get("criteria").get(0).get("verdict").asText())
                    .isIn("MET", "PARTIAL", "MISSING", "CONTRADICTED");
            assertThat(body.get("overallScore").isNull())
                    .as("a graded interview must produce a score from the backend")
                    .isFalse();
        }

        @Test
        @DisplayName("an unknown interview is a 404")
        void unknownInterview() throws Exception {
            mvc.perform(get("/api/v1/interviews/{id}/result", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    // ------------------------------------------------------------ contract

    @Nested
    @DisplayName("Error contract")
    class ErrorContract {

        @Test
        @DisplayName("every error carries a code and a traceId the user can quote")
        void errorsAreCorrelatable() throws Exception {
            MvcResult result = mvc.perform(get("/api/v1/interviews/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andReturn();
            JsonNode body = json.readTree(result.getResponse().getContentAsString());

            assertThat(body.get("code").asText()).isEqualTo("NOT_FOUND");
            assertThat(body.get("traceId").asText()).isNotBlank();
            assertThat(result.getResponse().getHeader("X-Trace-Id"))
                    .as("the browser must be able to read the id it should quote")
                    .isEqualTo(body.get("traceId").asText());
        }

        @Test
        @DisplayName("never leaks a stack trace or a SQL fragment")
        void noInternalsLeak() throws Exception {
            MvcResult result = mvc.perform(get("/api/v1/interviews/{id}", UUID.randomUUID()))
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("Exception")
                    .doesNotContain("SELECT")
                    .doesNotContain("org.springframework");
        }
    }

    // ------------------------------------------------------------ template

    @Nested
    @DisplayName("Template")
    class Template {

        @Test
        @DisplayName("describes what the start screen needs")
        void describesTheTemplate() throws Exception {
            mvc.perform(get("/api/v1/interview-templates/{key}", templateKey))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.templateKey").value(templateKey))
                    .andExpect(jsonPath("$.title").isNotEmpty())
                    .andExpect(jsonPath("$.coreQuestionCount").value(3));
        }

        @Test
        @DisplayName("an unpublished or unknown key is a 404")
        void unknownTemplate() throws Exception {
            mvc.perform(get("/api/v1/interview-templates/{key}", "nope"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    // --------------------------------------------------------- concurrency

    @Test
    @DisplayName("concurrent submissions of the same answer store exactly one")
    void concurrentSubmitStoresOne() throws Exception {
        UUID interviewId = UUID.fromString(startInterview().get("interviewId").asText());
        UUID turnId = UUID.fromString(advance(interviewId).get("currentQuestion")
                .get("interviewQuestionId").asText());

        // Two in-flight submits, as a flaky connection plus an impatient user
        // produces. The answer table's primary key is the arbiter.
        List<Thread> threads = List.of(
                new Thread(() -> quietSubmit(interviewId, turnId)),
                new Thread(() -> quietSubmit(interviewId, turnId)));
        threads.forEach(Thread::start);
        for (Thread thread : threads) {
            thread.join();
        }

        Integer stored = jdbc.queryForObject(
                "SELECT count(*) FROM app.answers WHERE interview_question_id = ?",
                Integer.class, turnId);
        assertThat(stored).isEqualTo(1);
    }

    private void quietSubmit(UUID interviewId, UUID turnId) {
        try {
            submit(interviewId, turnId, ANSWER);
        } catch (Exception ignored) {
            // A losing racer may surface a constraint violation; the assertion
            // is about what ended up in the table, not about who won.
        }
    }
}
