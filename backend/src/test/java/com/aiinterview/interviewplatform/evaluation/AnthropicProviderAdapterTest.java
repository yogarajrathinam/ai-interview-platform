package com.aiinterview.interviewplatform.evaluation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationProvider;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationProviderException;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationRequest;
import com.aiinterview.interviewplatform.evaluation.api.ProviderEvaluation;
import com.aiinterview.interviewplatform.evaluation.api.Verdict;
import com.aiinterview.interviewplatform.shared.config.AppProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * The vendor adapter, exercised over real HTTP against a stub server.
 *
 * <p>Deliberately not a mock of the SDK client. The failure modes that matter
 * here — a 429, a truncated response, a payload that does not match the schema —
 * are things the transport does, and a mocked client would only prove that the
 * adapter calls the method we told it to call. WireMock serves real responses
 * over a real socket, so the SDK's own parsing and error classification are
 * under test too.
 *
 * <p>Runs in CI: no credential, no network egress, no cost. The API key here is
 * a synthetic literal that never authenticates anything.
 */
@DisplayName("Anthropic provider adapter")
class AnthropicProviderAdapterTest {

    /** Obviously fake. WireMock does not authenticate, and neither does this. */
    private static final String FAKE_KEY = "sk-ant-test-not-a-real-key";

    private static final UUID CRITERION_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID CRITERION_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static final String ANSWER =
            "HashMap is not synchronised, so concurrent writes can corrupt the table.";

    private WireMockServer server;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (context != null) {
            context.close();
        }
        server.stop();
    }

    // ------------------------------------------------------------ fixtures

    /**
     * Builds the provider the way the application does.
     *
     * <p>Through the Spring configuration rather than by calling the
     * constructor, so the wiring itself — bean selection, credential check,
     * timeout, base URL — is covered by these tests instead of only the mapping.
     */
    private EvaluationProvider provider(int timeoutSeconds) {
        context = contextFor(new AppProperties.Ai("anthropic", "claude-opus-5", FAKE_KEY,
                server.baseUrl(), timeoutSeconds, 8000, "medium"));
        context.refresh();
        return context.getBean(EvaluationProvider.class);
    }

    /**
     * A context wired the way the application wires itself.
     *
     * <p>{@code app.ai.provider} is set as a real environment property rather
     * than only on the bean, because that is what {@code @ConditionalOnProperty}
     * reads. Registering the bean alone leaves the condition false and no
     * provider is created — which is the selection gate working, and worth
     * exercising here rather than discovering in an environment.
     */
    private AnnotationConfigApplicationContext contextFor(AppProperties.Ai ai) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("ai-selection", Map.of("app.ai.provider", ai.provider())));
        ctx.registerBean(AppProperties.class, () -> new AppProperties(
                "test", "test",
                new AppProperties.Api("/api/v1"),
                new AppProperties.Jobs(false, 1000, 5, 300, 4, 5, 600, 500),
                ai));
        ctx.register(
                com.aiinterview.interviewplatform.evaluation.infrastructure.anthropic
                        .EvaluationPrompt.class,
                com.aiinterview.interviewplatform.evaluation.infrastructure.anthropic
                        .AnthropicProviderConfiguration.class);
        return ctx;
    }

    private EvaluationProvider provider() {
        return provider(30);
    }

    private static EvaluationRequest request() {
        return new EvaluationRequest(
                null, null, null, UUID.randomUUID(), 1,
                new EvaluationRequest.QuestionSnapshot(UUID.randomUUID(),
                        "HashMap versus ConcurrentHashMap?", null,
                        "HashMap is not thread-safe."),
                List.of(
                        new EvaluationRequest.RubricCriterion(CRITERION_A, "THREAD_SAFETY",
                                "Thread safety", "States HashMap is not thread-safe",
                                6000, "CORE"),
                        new EvaluationRequest.RubricCriterion(CRITERION_B, "NULL_HANDLING",
                                "Nulls", "States ConcurrentHashMap forbids nulls",
                                4000, "CORE")),
                new EvaluationRequest.CandidateAnswer(ANSWER, "TEXT"));
    }

    /** A successful Messages response whose text block carries the grading JSON. */
    private void stubGrading(String gradingJson) {
        stubBody("""
                {
                  "id": "msg_test",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-opus-5",
                  "content": [{"type": "text", "text": %s}],
                  "stop_reason": "end_turn",
                  "stop_sequence": null,
                  "usage": {"input_tokens": 1234, "output_tokens": 567}
                }
                """.formatted(quote(gradingJson)));
    }

    private void stubBody(String body) {
        server.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void stubStatus(int status, String body) {
        server.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    /** JSON-encodes a string so it can be embedded as a text block's content. */
    private static String quote(String raw) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String grading(String criterionId, String verdict, String quote) {
        return """
                {"criteria":[{"criterionId":"%s","verdict":"%s","evidenceQuote":"%s",\
                "confidence":0.9,"reasoning":"because"}],"summary":"A summary.",\
                "injectionSuspected":false,"answerOffTopic":false,"followUpNeeded":false,\
                "followUpTargetCriterionId":""}"""
                .formatted(criterionId, verdict, quote);
    }

    // ------------------------------------------------------------ the request

    @Nested
    @DisplayName("sends a request that")
    class RequestShape {

        @Test
        @DisplayName("constrains the model to the grading schema")
        void carriesTheSchema() {
            stubGrading(grading(CRITERION_A.toString(), "MET", "not synchronised"));

            provider().evaluate(request());

            String body = sentBody();
            assertThat(body).contains("\"output_config\"");
            assertThat(body)
                    .as("without the schema the model may answer in prose and every "
                            + "evaluation fails as INVALID_OUTPUT")
                    .contains("json_schema")
                    .contains("criterionId")
                    .contains("evidenceQuote");
        }

        @Test
        @DisplayName("carries the schema and the effort setting together")
        void carriesSchemaAndEffort() {
            stubGrading(grading(CRITERION_A.toString(), "MET", "not synchronised"));

            provider().evaluate(request());

            // These two are set through the same whole-object field and silently
            // overwrite each other if merged wrongly. Asserted together because
            // either one alone would still look correct.
            String body = sentBody();
            assertThat(body).contains("json_schema");
            assertThat(body).contains("\"effort\":\"medium\"");
        }

        @Test
        @DisplayName("constrains verdicts to the four allowed values")
        void constrainsVerdicts() {
            stubGrading(grading(CRITERION_A.toString(), "MET", "not synchronised"));

            provider().evaluate(request());

            assertThat(sentBody())
                    .as("an enum in the schema makes an invalid verdict impossible "
                            + "rather than merely discouraged by the prompt")
                    .contains("CONTRADICTED");
        }

        @Test
        @DisplayName("gives the model nowhere to report an overall score")
        void offersNoScoreField() {
            stubGrading(grading(CRITERION_A.toString(), "MET", "not synchronised"));

            provider().evaluate(request());

            String schema = sentBody();
            assertThat(schema)
                    .as("the schema must not contain a score property; withholding "
                            + "the field beats discarding the value")
                    .doesNotContain("\"score\"")
                    .doesNotContain("overallScore");
        }

        @Test
        @DisplayName("withholds criterion weights from the grader")
        void withholdsWeights() {
            stubGrading(grading(CRITERION_A.toString(), "MET", "not synchronised"));

            provider().evaluate(request());

            assertThat(sentBody())
                    .as("a grader that knows which criteria are worth more will "
                            + "grade the heavy ones differently")
                    .doesNotContain("6000")
                    .doesNotContain("weightBp");
        }

        @Test
        @DisplayName("sends the pinned model id")
        void sendsPinnedModel() {
            stubGrading(grading(CRITERION_A.toString(), "MET", "not synchronised"));

            provider().evaluate(request());

            assertThat(sentBody()).contains("claude-opus-5");
        }

        private String sentBody() {
            List<LoggedRequest> requests =
                    server.findAll(postRequestedFor(urlPathEqualTo("/v1/messages")));
            assertThat(requests).hasSize(1);
            return requests.get(0).getBodyAsString();
        }
    }

    // ------------------------------------------------------------ mapping

    @Nested
    @DisplayName("maps a successful response so that it")
    class Mapping {

        @Test
        @DisplayName("carries verdicts, evidence and provenance")
        void mapsTheHappyPath() {
            stubGrading(grading(CRITERION_A.toString(), "MET", "not synchronised"));

            ProviderEvaluation evaluation = provider().evaluate(request());

            assertThat(evaluation.judgements()).singleElement().satisfies(judgement -> {
                assertThat(judgement.criterionId()).isEqualTo(CRITERION_A);
                assertThat(judgement.verdict()).isEqualTo(Verdict.MET);
                assertThat(judgement.evidenceQuote()).isEqualTo("not synchronised");
                assertThat(judgement.confidence()).isEqualByComparingTo("0.900");
            });
            assertThat(evaluation.summary()).isEqualTo("A summary.");
            assertThat(evaluation.metadata().provider()).isEqualTo("anthropic");
            assertThat(evaluation.metadata().model()).isEqualTo("claude-opus-5");
            assertThat(evaluation.metadata().promptVersion()).isEqualTo("answer-evaluation-v1");
        }

        @Test
        @DisplayName("records token usage for cost accounting")
        void recordsTokens() {
            stubGrading(grading(CRITERION_A.toString(), "MET", "not synchronised"));

            ProviderEvaluation evaluation = provider().evaluate(request());

            assertThat(evaluation.metadata().inputTokens()).isEqualTo(1234);
            assertThat(evaluation.metadata().outputTokens()).isEqualTo(567);
            assertThat(evaluation.metadata().costMicros())
                    .as("cost is derived where rates can be maintained, never from "
                            + "a price table compiled into the build")
                    .isNull();
        }

        @Test
        @DisplayName("never reports a model-supplied score")
        void neverReportsAModelScore() {
            stubGrading(grading(CRITERION_A.toString(), "MET", "not synchronised"));

            assertThat(provider().evaluate(request()).modelReportedScore()).isNull();
        }

        @Test
        @DisplayName("reads an empty evidence quote as absent rather than as evidence")
        void blankEvidenceBecomesNull() {
            stubGrading(grading(CRITERION_A.toString(), "MISSING", ""));

            ProviderEvaluation evaluation = provider().evaluate(request());

            assertThat(evaluation.judgements()).singleElement().satisfies(judgement -> {
                assertThat(judgement.verdict()).isEqualTo(Verdict.MISSING);
                assertThat(judgement.evidenceQuote())
                        .as("the schema requires a string, so absence arrives as \"\"")
                        .isNull();
            });
        }

        @Test
        @DisplayName("reads an empty follow-up target as no target")
        void blankFollowUpTargetBecomesNull() {
            stubGrading(grading(CRITERION_A.toString(), "MET", "not synchronised"));

            assertThat(provider().evaluate(request()).followUpTargetCriterionId()).isNull();
        }

        @Test
        @DisplayName("drops a criterion whose id will not parse")
        void dropsUnparseableCriterionId() {
            stubGrading(grading("not-a-uuid", "MET", "not synchronised"));

            ProviderEvaluation evaluation = provider().evaluate(request());

            assertThat(evaluation.judgements())
                    .as("the validator already rejects an incomplete criterion set; "
                            + "it has no rule for a null id")
                    .isEmpty();
        }

        @Test
        @DisplayName("passes the injection signal through without acting on it")
        void surfacesInjectionSignal() {
            stubGrading("""
                    {"criteria":[],"summary":"s","injectionSuspected":true,\
                    "answerOffTopic":true,"followUpNeeded":true,\
                    "followUpTargetCriterionId":"%s"}""".formatted(CRITERION_B));

            ProviderEvaluation evaluation = provider().evaluate(request());

            assertThat(evaluation.injectionSuspected()).isTrue();
            assertThat(evaluation.answerOffTopic()).isTrue();
            assertThat(evaluation.followUpNeeded()).isTrue();
            assertThat(evaluation.followUpTargetCriterionId()).isEqualTo(CRITERION_B);
        }
    }

    // ------------------------------------------------------------ failures

    @Nested
    @DisplayName("classifies failure so that")
    class Failures {

        @Test
        @DisplayName("a rate limit is retryable")
        void rateLimited() {
            stubStatus(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}");

            assertReason(EvaluationProviderException.Reason.RATE_LIMITED);
        }

        @Test
        @DisplayName("a server fault is retryable")
        void serverError() {
            stubStatus(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\"}}");

            assertReason(EvaluationProviderException.Reason.PROVIDER_ERROR);
        }

        @Test
        @DisplayName("a rejected request is not retryable")
        void badRequest() {
            stubStatus(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\"}}");

            // Retrying a malformed request burns the job's attempts to reach the
            // identical failure, so it must be classified permanent.
            EvaluationProviderException thrown = assertReason(
                    EvaluationProviderException.Reason.INVALID_OUTPUT);
            assertThat(thrown.reason().isRetryable()).isFalse();
        }

        @Test
        @DisplayName("a rejected credential is not retryable")
        void unauthorized() {
            stubStatus(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\"}}");

            EvaluationProviderException thrown = assertReason(
                    EvaluationProviderException.Reason.INVALID_OUTPUT);
            assertThat(thrown.reason().isRetryable())
                    .as("a bad credential will not fix itself between attempts")
                    .isFalse();
        }

        @Test
        @DisplayName("an unreachable provider is a timeout")
        void unreachable() {
            // Built while the server is up so the client has a real URL, then
            // the socket is taken away underneath it.
            EvaluationProvider subject = provider(5);
            server.stop();

            assertReason(subject, EvaluationProviderException.Reason.TIMEOUT);
        }

        @Test
        @DisplayName("a response with no content is unusable output")
        void noContent() {
            stubBody("""
                    {
                      "id": "msg_test", "type": "message", "role": "assistant",
                      "model": "claude-opus-5", "content": [],
                      "stop_reason": "refusal",
                      "usage": {"input_tokens": 10, "output_tokens": 0}
                    }
                    """);

            // A refusal is a successful HTTP response carrying nothing gradable.
            // Synthesising an empty verdict set would read as "met nothing".
            assertReason(EvaluationProviderException.Reason.INVALID_OUTPUT);
        }

        @Test
        @DisplayName("a payload that is not the grading schema is unusable output")
        void malformedPayload() {
            stubGrading("this is prose, not JSON");

            assertReason(EvaluationProviderException.Reason.INVALID_OUTPUT);
        }

        @Test
        @DisplayName("a failure never leaks the candidate's answer")
        void failureMessageCarriesNoAnswerText() {
            stubStatus(500, "{\"type\":\"error\",\"error\":{\"message\":\"echoed: " + ANSWER + "\"}}");

            EvaluationProviderException thrown = assertReason(
                    EvaluationProviderException.Reason.PROVIDER_ERROR);
            assertThat(thrown.getMessage())
                    .as("provider error text can echo the request, and the request "
                            + "contains the candidate's words")
                    .doesNotContain(ANSWER);
        }

        private EvaluationProviderException assertReason(
                EvaluationProviderException.Reason expected) {
            return assertReason(provider(5), expected);
        }

        private EvaluationProviderException assertReason(
                EvaluationProvider subject, EvaluationProviderException.Reason expected) {
            EvaluationRequest request = request();

            EvaluationProviderException thrown = catchThrowableOfType(
                    () -> subject.evaluate(request), EvaluationProviderException.class);

            assertThat(thrown)
                    .as("the engine has no failure category for a raw vendor exception")
                    .isNotNull();
            assertThat(thrown.reason()).isEqualTo(expected);
            return thrown;
        }
    }

    // ------------------------------------------------------------ wiring

    @Nested
    @DisplayName("wiring")
    class Wiring {

        @Test
        @DisplayName("refuses to start without a credential rather than failing later")
        void requiresCredential() {
            AnnotationConfigApplicationContext ctx = contextFor(
                    new AppProperties.Ai("anthropic", "claude-opus-5", "  ",
                            null, 30, 8000, "medium"));

            assertThatThrownBy(ctx::refresh)
                    .as("an environment that cannot grade must fail to start, not "
                            + "accept interviews and fail every evaluation later")
                    .rootCause()
                    .hasMessageContaining("app.ai.api-key");
            ctx.close();
        }

        @Test
        @DisplayName("names itself so invocations are attributable")
        void identifiesItself() {
            assertThat(provider().providerName()).isEqualTo("anthropic");
        }
    }
}
