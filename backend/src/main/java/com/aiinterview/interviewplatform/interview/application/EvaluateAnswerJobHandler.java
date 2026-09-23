package com.aiinterview.interviewplatform.interview.application;

import com.aiinterview.interviewplatform.interview.api.InterviewService;
import com.aiinterview.interviewplatform.shared.error.ApplicationException;
import com.aiinterview.interviewplatform.shared.jobs.api.JobExecutionException;
import com.aiinterview.interviewplatform.shared.jobs.api.JobHandler;
import com.aiinterview.interviewplatform.shared.jobs.api.JobType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs one answer's grading asynchronously.
 *
 * <p>Lives in the interview module rather than in {@code shared} because the
 * queue must not know what an evaluation is — {@code shared} defines the
 * handler interface and business modules supply implementations. The dependency
 * points inward, so {@code shared → interview} never appears.
 *
 * <p>Delegates to {@link InterviewService#evaluateAnswer}, which already
 * orchestrates grading and applies the outcome to the turn. The job adds
 * <em>when</em> that happens, not <em>what</em> happens, so synchronous and
 * asynchronous grading cannot drift apart.
 *
 * <p>Idempotent by inheritance: the evaluation module will not re-call a
 * provider for an answer that already has a current evaluation, so a duplicate
 * delivery or a redelivered claim costs nothing and produces no second result.
 */
@Component
public class EvaluateAnswerJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(EvaluateAnswerJobHandler.class);

    private final InterviewService interviews;
    private final ObjectMapper objectMapper;

    public EvaluateAnswerJobHandler(InterviewService interviews, ObjectMapper objectMapper) {
        this.interviews = interviews;
        this.objectMapper = objectMapper;
    }

    @Override
    public JobType jobType() {
        return JobType.EVALUATE_ANSWER;
    }

    @Override
    public void handle(JobContext context) {
        Payload payload = parse(context.payload());

        try {
            interviews.evaluateAnswer(payload.interviewId(), payload.interviewQuestionId());
            log.info("Evaluated answer for turn {} via job {}",
                    payload.interviewQuestionId(), context.jobId());

        } catch (ApplicationException e) {
            // A domain rejection is a statement about the data, not about our
            // luck. A deleted interview or an unanswered turn will still be
            // deleted and unanswered on the fourth attempt, so retrying only
            // burns attempts and hides the real problem.
            throw JobExecutionException.permanent(
                    "Answer %s cannot be graded: %s".formatted(
                            payload.interviewQuestionId(), e.getMessage()), e);

        } catch (RuntimeException e) {
            // Everything else — a dropped connection, an exhausted pool, a
            // provider outage surfacing as an infrastructure fault — is worth
            // another try. Note that a *provider* failure does not reach here
            // at all: the evaluation module records it as FAILED_PROVIDER and
            // returns normally, so the job succeeds while the evaluation is
            // marked failed. That separation is deliberate; see the docs.
            throw JobExecutionException.retryable(
                    "Grading turn %s failed: %s".formatted(
                            payload.interviewQuestionId(), e.toString()), e);
        }
    }

    /** Ids and nothing else — never candidate content. */
    private Payload parse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            UUID interviewId = UUID.fromString(node.get("interviewId").asText());
            UUID turnId = UUID.fromString(node.get("interviewQuestionId").asText());
            return new Payload(interviewId, turnId);
        } catch (Exception e) {
            // A payload we cannot read will not become readable later.
            throw JobExecutionException.permanent("Malformed EVALUATE_ANSWER payload", e);
        }
    }

    private record Payload(UUID interviewId, UUID interviewQuestionId) {}

    /** The natural key: one active grading job per turn, enforced by the index. */
    public static String dedupeKeyFor(UUID interviewQuestionId) {
        return JobType.EVALUATE_ANSWER + ":" + interviewQuestionId;
    }

    /** Correlation ids only — this value is stored and logged. */
    public static String payloadFor(UUID interviewId, UUID interviewQuestionId) {
        return """
                {"interviewId":"%s","interviewQuestionId":"%s"}"""
                .formatted(interviewId, interviewQuestionId);
    }

}
