package com.aiinterview.interviewplatform.interview.api;

import com.aiinterview.interviewplatform.interview.api.InterviewApiDtos.InterviewResultResponse;
import com.aiinterview.interviewplatform.interview.api.InterviewApiDtos.InterviewStateResponse;
import com.aiinterview.interviewplatform.interview.api.InterviewApiDtos.StartInterviewRequest;
import com.aiinterview.interviewplatform.interview.api.InterviewApiDtos.SubmitAnswerRequest;
import com.aiinterview.interviewplatform.interview.api.InterviewApiDtos.SubmitAnswerResponse;
import com.aiinterview.interviewplatform.interview.api.InterviewApiDtos.TemplateSummaryResponse;
import com.aiinterview.interviewplatform.question.api.TemplateCatalog;
import com.aiinterview.interviewplatform.shared.error.ApplicationException;
import com.aiinterview.interviewplatform.shared.error.ErrorCode;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The candidate-facing HTTP surface.
 *
 * <p>Deliberately thin. Every method maps a request, calls one application
 * operation, and maps the result — there is no branching on interview state, no
 * scoring, no persistence and no {@code try/catch}. Errors are raised as
 * {@code ApplicationException} by the services and turned into RFC 9457
 * responses by the single {@code GlobalExceptionHandler}, so the wire format
 * cannot drift endpoint by endpoint.
 *
 * <h2>Why this is smaller than a REST checklist suggests</h2>
 *
 * <p>Two endpoints a candidate API usually has are absent because the existing
 * contracts already make them redundant, and a redundant endpoint is a second
 * source of truth waiting to disagree:
 *
 * <ul>
 *   <li>There is no separate <em>create</em> then <em>start</em>. An attempt is
 *       created and planned in one operation and there is no state between the
 *       two, so a second call could only ever be a no-op.</li>
 *   <li>There is no {@code /current-question}. The current question is a field
 *       of the state every endpoint already returns. A client that fetched it
 *       separately would hold two answers to "which question am I on" and lose
 *       the race on a refresh.</li>
 * </ul>
 *
 * <h2>Evaluation is asynchronous, and this does not hide that</h2>
 *
 * <p>Submitting an answer stores it and enqueues grading in the same
 * transaction; the worker calls the model afterwards. So {@code POST /answers}
 * returns as soon as the answer is durable, and the client polls
 * {@code GET /{id}} until {@code progress.gradingSettled} is true. Blocking the
 * request until the model replied would put a multi-second provider call on the
 * candidate's connection and lose the answer if it dropped.
 *
 * <h2>Authentication</h2>
 *
 * <p>There is none, and this class is only reachable in {@code local} and
 * {@code test} environments — see {@code SecurityConfig}. The candidate is
 * named in the start request because there is no authenticated subject to take
 * it from. That is a development vertical slice, not a production posture, and
 * M6 replaces it.
 */
@RestController
@RequestMapping("/api/v1")
public class InterviewController {

    private final InterviewService interviews;
    private final InterviewResultService results;
    private final TemplateCatalog templates;

    public InterviewController(InterviewService interviews,
                               InterviewResultService results,
                               TemplateCatalog templates) {
        this.interviews = interviews;
        this.results = results;
        this.templates = templates;
    }

    /** What the start screen shows before an attempt exists. */
    @GetMapping("/interview-templates/{templateKey}")
    public TemplateSummaryResponse getTemplate(@PathVariable String templateKey) {
        TemplateCatalog.TemplateView template = templates
                .findPublishedTemplateByKey(templateKey)
                .orElseThrow(() -> ApplicationException.notFound(
                        "No published interview template '%s'.".formatted(templateKey)));

        return new TemplateSummaryResponse(
                template.templateKey(), template.version(), template.title(), template.level(),
                template.coreQuestionCount(), template.targetDurationMin(),
                template.hardDurationMin(), template.instructions());
    }

    /**
     * Starts an attempt, or returns the live one.
     *
     * <p>Answers {@code 200} rather than {@code 201} on purpose: the operation
     * is idempotent per candidate and a resumed attempt was not created by this
     * request. Claiming {@code 201} for it would be a lie a client could act on.
     */
    @PostMapping("/interviews")
    public ResponseEntity<InterviewStateResponse> start(
            @Valid @RequestBody StartInterviewRequest request) {

        InterviewState state = interviews.start(new StartInterviewCommand(
                request.candidateUserId(), request.templateKey(), true));

        return ResponseEntity.ok(InterviewStateResponse.from(state));
    }

    /**
     * The authoritative state. This is the polling endpoint.
     *
     * <p>Safe to call as often as a client likes: it mutates nothing, which is
     * precisely why serving the next question is a separate operation below.
     */
    @GetMapping("/interviews/{interviewId}")
    public InterviewStateResponse getState(@PathVariable UUID interviewId) {
        return InterviewStateResponse.from(interviews.getState(interviewId));
    }

    /**
     * Serves the next question — that is, records that it was shown.
     *
     * <p>Note that the state already <em>names</em> the next turn before this is
     * called; what it lacks is {@code askedAt}. The turn moves {@code PENDING →
     * ASKED} here, which is the distinction that makes "candidates abandon at
     * question five" answerable at all. A client that rendered the question
     * without advancing would silently destroy that signal.
     *
     * <p>A {@code POST} because it changes the attempt: besides marking the turn,
     * at the end of the plan it runs the follow-up phase and moves the attempt
     * toward completion. Behind a {@code GET} that would be replayable by any
     * proxy, prefetcher or browser.
     */
    @PostMapping("/interviews/{interviewId}/advance")
    public InterviewStateResponse advance(@PathVariable UUID interviewId) {
        return InterviewStateResponse.from(interviews.advance(interviewId));
    }

    /**
     * Records an answer and queues its grading.
     *
     * <p>The turn is named in the body rather than inferred from "the current
     * question", so a retry, a back button or a second tab submits the same
     * identifiable thing. A repeat returns {@code duplicate: true} with the
     * stored answer intact — guaranteed by the answer table's primary key, not
     * by anything invented in this layer.
     */
    @PostMapping("/interviews/{interviewId}/answers")
    public SubmitAnswerResponse submitAnswer(@PathVariable UUID interviewId,
                                             @Valid @RequestBody SubmitAnswerRequest request) {

        return SubmitAnswerResponse.from(interviews.submitAnswer(new SubmitAnswerCommand(
                interviewId, request.interviewQuestionId(), request.contentText(),
                "TEXT", request.timeSpentSec())));
    }

    /** The candidate chooses to finish early. Repeat calls are harmless. */
    @PostMapping("/interviews/{interviewId}/completion")
    public InterviewStateResponse complete(@PathVariable UUID interviewId) {
        return InterviewStateResponse.from(interviews.complete(interviewId));
    }

    /**
     * The result, once the attempt has finished.
     *
     * <p>Refuses with {@code RESULT_NOT_AVAILABLE} while answers are still
     * accepted. That is the rule keeping scores out of a candidate's hands
     * mid-interview, and it is enforced in the service so a second caller
     * cannot bypass it.
     */
    @GetMapping("/interviews/{interviewId}/result")
    public InterviewResultResponse getResult(@PathVariable UUID interviewId) {
        return InterviewResultResponse.from(results.getResult(interviewId));
    }

    /**
     * Referenced so the error contract's codes stay visibly owned by this API
     * even though the handler produces the responses.
     */
    static ErrorCode[] documentedErrors() {
        return new ErrorCode[]{
                ErrorCode.NOT_FOUND,
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.MALFORMED_REQUEST,
                ErrorCode.INTERVIEW_NOT_IN_PROGRESS,
                ErrorCode.LIVE_ATTEMPT_EXISTS,
                ErrorCode.RESULT_NOT_AVAILABLE,
                ErrorCode.INTERNAL_ERROR};
    }
}
