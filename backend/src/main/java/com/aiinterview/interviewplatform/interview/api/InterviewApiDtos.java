package com.aiinterview.interviewplatform.interview.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The candidate API's wire contract.
 *
 * <p>Held together in one file because these types are meaningless apart: they
 * are one JSON schema expressed in Java, and splitting them across a dozen
 * files would hide that.
 *
 * <p>Deliberately distinct from {@link InterviewState} and {@link InterviewResult}
 * even where the shapes nearly match. Those are module contracts free to change
 * with the domain; these are a published wire format that a deployed browser
 * depends on. Collapsing them would make every internal rename a breaking API
 * change, and the mapping below is where that pressure is absorbed.
 *
 * <p>No persistence entity appears here, and no field exposes an internal
 * identifier a client has no use for.
 */
public final class InterviewApiDtos {

    private InterviewApiDtos() {
    }

    // ------------------------------------------------------------ requests

    /**
     * @param candidateUserId supplied by the client only because authentication
     *                        does not exist yet. It becomes the authenticated
     *                        subject in M6 and leaves this contract — which is
     *                        why the endpoint is unavailable outside local/test
     */
    public record StartInterviewRequest(
            @NotNull UUID candidateUserId,
            @NotBlank @Size(max = 100) String templateKey) {
    }

    /**
     * @param timeSpentSec advisory and client-reported; recorded for analytics
     *                     and never used to enforce a limit, because a broken
     *                     or hostile client must not be able to buy more time
     */
    public record SubmitAnswerRequest(
            @NotNull UUID interviewQuestionId,
            @NotNull @Size(max = 20_000) String contentText,
            @Positive Integer timeSpentSec) {
    }

    // ----------------------------------------------------------- responses

    /**
     * The whole client-visible interview, in one shape.
     *
     * <p>Every mutating endpoint returns this, so a client never has to make a
     * second call to learn what its own request did — and never has to maintain
     * its own idea of "which question am I on", which is what makes a refresh
     * or a second tab safe.
     *
     * <p>Carries no score. A candidate who could see their marks mid-interview
     * would tune later answers to earlier feedback.
     */
    public record InterviewStateResponse(
            UUID interviewId,
            InterviewStatus status,
            CompletionReason completionReason,
            OffsetDateTime startedAt,
            OffsetDateTime hardDeadlineAt,
            /** So a countdown runs against our clock, not a skewed device's. */
            OffsetDateTime serverTime,
            ProgressResponse progress,
            /** Null when no turn is waiting — the client must handle that. */
            CurrentQuestionResponse currentQuestion,
            List<TurnSummaryResponse> timeline) {

        public static InterviewStateResponse from(InterviewState state) {
            return new InterviewStateResponse(
                    state.interviewId(),
                    state.status(),
                    state.completionReason(),
                    state.startedAt(),
                    state.hardDeadlineAt(),
                    state.serverTime(),
                    ProgressResponse.from(state.progress()),
                    CurrentQuestionResponse.from(state.currentQuestion()),
                    state.timeline().stream().map(TurnSummaryResponse::from).toList());
        }
    }

    /**
     * @param gradingSettled every answer given so far has been graded. This is
     *                       the flag the UI polls on: it is derived here rather
     *                       than left to the client to infer from two counters
     */
    public record ProgressResponse(int coreTotal, int coreAnswered, int followUpsAsked,
                                   int followUpsRemaining, int answeredTotal,
                                   int evaluatedTotal, boolean gradingSettled) {

        static ProgressResponse from(InterviewState.Progress progress) {
            if (progress == null) {
                return null;
            }
            return new ProgressResponse(
                    progress.coreTotal(), progress.coreAnswered(), progress.followUpsAsked(),
                    progress.followUpsRemaining(), progress.answeredTotal(),
                    progress.evaluatedTotal(), progress.isGradingSettled());
        }
    }

    /**
     * @param isFollowUp so the UI can label a probe as such. Derived from the
     *                   kind rather than making every client know the enum
     */
    public record CurrentQuestionResponse(UUID interviewQuestionId, int position, TurnKind kind,
                                          boolean isFollowUp, UUID parentTurnId, UUID skillId,
                                          String promptText, String contextText,
                                          int expectedDurationSec, OffsetDateTime askedAt) {

        static CurrentQuestionResponse from(InterviewState.CurrentQuestion question) {
            if (question == null) {
                return null;
            }
            return new CurrentQuestionResponse(
                    question.interviewQuestionId(),
                    question.position(),
                    question.kind(),
                    question.kind() == TurnKind.FOLLOW_UP,
                    question.parentTurnId(),
                    question.skillId(),
                    question.promptText(),
                    question.contextText(),
                    question.expectedDurationSec(),
                    question.askedAt());
        }
    }

    /** Navigation only — never a score, matching the module contract. */
    public record TurnSummaryResponse(UUID interviewQuestionId, int position, TurnKind kind,
                                      TurnStatus status, UUID skillId, UUID parentTurnId) {

        static TurnSummaryResponse from(InterviewState.TurnSummary turn) {
            return new TurnSummaryResponse(turn.interviewQuestionId(), turn.position(),
                    turn.kind(), turn.status(), turn.skillId(), turn.parentTurnId());
        }
    }

    /**
     * @param duplicate this submission matched one already stored and the
     *                  original was kept. Reported as success, not as an error:
     *                  a double-clicked button is not a failure, and telling the
     *                  client otherwise would invite it to retry into a loop
     */
    public record SubmitAnswerResponse(UUID interviewQuestionId, boolean duplicate,
                                       InterviewStateResponse state) {

        public static SubmitAnswerResponse from(InterviewService.SubmitAnswerResult result) {
            return new SubmitAnswerResponse(result.interviewQuestionId(), result.duplicate(),
                    InterviewStateResponse.from(result.state()));
        }
    }

    // -------------------------------------------------------------- result

    /**
     * @param overallScore null when nothing could be graded. The client must
     *                     render that as "no result", never as zero
     */
    public record InterviewResultResponse(UUID interviewId, InterviewStatus status,
                                          CompletionReason completionReason,
                                          BigDecimal overallScore, int coverageBp,
                                          List<SkillResultResponse> skills,
                                          List<QuestionResultResponse> questions) {

        public static InterviewResultResponse from(InterviewResult result) {
            return new InterviewResultResponse(
                    result.interviewId(),
                    result.status(),
                    result.completionReason(),
                    result.overallScore(),
                    result.coverageBp(),
                    result.skills().stream().map(SkillResultResponse::from).toList(),
                    result.questions().stream().map(QuestionResultResponse::from).toList());
        }
    }

    public record SkillResultResponse(UUID skillId, String code, String name,
                                      BigDecimal score, int weightBp, int coverageBp,
                                      int questionCount) {

        static SkillResultResponse from(InterviewResult.SkillResult skill) {
            return new SkillResultResponse(skill.skillId(), skill.skillCode(), skill.skillName(),
                    skill.score(), skill.weightBp(), skill.coverageBp(), skill.questionCount());
        }
    }

    public record QuestionResultResponse(UUID interviewQuestionId, int position, TurnKind kind,
                                         boolean isFollowUp, TurnStatus status, UUID skillId,
                                         String promptText, BigDecimal score, boolean graded,
                                         List<CriterionResultResponse> criteria) {

        static QuestionResultResponse from(InterviewResult.QuestionResult question) {
            return new QuestionResultResponse(
                    question.interviewQuestionId(), question.position(), question.kind(),
                    question.kind() == TurnKind.FOLLOW_UP, question.status(), question.skillId(),
                    question.promptText(), question.score(), question.graded(),
                    question.criteria().stream().map(CriterionResultResponse::from).toList());
        }
    }

    /**
     * @param evidenceQuote the candidate's own words that justified the verdict,
     *                      or null. The product claim is that a score is always
     *                      traceable to these, so it is carried to the client
     *                      rather than summarised away
     */
    public record CriterionResultResponse(
            UUID criterionId, String code, String label,
            com.aiinterview.interviewplatform.evaluation.api.Verdict verdict,
            BigDecimal credit, int weightBp, String evidenceQuote) {

        static CriterionResultResponse from(InterviewResult.CriterionResult criterion) {
            return new CriterionResultResponse(criterion.criterionId(), criterion.code(),
                    criterion.label(), criterion.verdict(), criterion.credit(),
                    criterion.weightBp(), criterion.evidenceQuote());
        }
    }

    // ------------------------------------------------------------ template

    /** What the start screen needs before an attempt exists. */
    public record TemplateSummaryResponse(String templateKey, int version, String title,
                                          String level, int coreQuestionCount,
                                          int targetDurationMin, int hardDurationMin,
                                          String instructions) {
    }
}
