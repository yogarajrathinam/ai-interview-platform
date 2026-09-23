package com.aiinterview.interviewplatform.evaluation.infrastructure.anthropic;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationProvider;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationProviderException;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationRequest;
import com.aiinterview.interviewplatform.evaluation.api.ProviderEvaluation;
import com.aiinterview.interviewplatform.evaluation.api.Verdict;
import com.aiinterview.interviewplatform.shared.config.AppProperties;
import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnprocessableEntityException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grades an answer with a real model.
 *
 * <p>The only class in the codebase that calls a vendor SDK, and an ArchUnit rule
 * fails the build if that stops being true. What it returns is a
 * {@code ProviderEvaluation} — the same contract the deterministic provider
 * satisfies — so nothing downstream can tell which one produced it.
 *
 * <p>What this class deliberately cannot do: persist anything, score anything,
 * touch interview state, or skip validation. It observes and reports; the
 * verdicts it returns are <em>proposals</em> that {@code ProviderResultValidator},
 * {@code EvidenceValidator} and {@code AnswerScorer} then accept, downgrade or
 * overrule. The model is an untrusted external evaluator, and this is the seam
 * that keeps it that way.
 */
public class AnthropicEvaluationProvider implements EvaluationProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicEvaluationProvider.class);

    public static final String PROVIDER_NAME = "anthropic";

    private final AnthropicClient client;
    private final EvaluationPrompt prompt;
    private final AppProperties.Ai config;
    private final OutputConfig.Effort effort;

    public AnthropicEvaluationProvider(AnthropicClient client, EvaluationPrompt prompt,
                                       AppProperties.Ai config) {
        this.client = Objects.requireNonNull(client);
        this.prompt = Objects.requireNonNull(prompt);
        this.config = Objects.requireNonNull(config);
        // Resolved once: a bad value must fail the context, not the first
        // candidate's evaluation. The property pattern already constrains it.
        this.effort = OutputConfig.Effort.of(config.effort().toLowerCase(Locale.ROOT));
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public ProviderEvaluation evaluate(EvaluationRequest request) {
        StructuredMessage<GradingResponse> message = call(buildParams(request));
        return toProviderEvaluation(message);
    }

    /**
     * Builds the request, carrying both the schema and the effort setting.
     *
     * <p>The two-step is not incidental. {@code outputConfig} is a whole-object
     * setter, so {@code outputConfig(Class)} and {@code outputConfig(OutputConfig)}
     * overwrite each other in <em>either</em> order — set effort after the class
     * and the schema is silently dropped, leaving the model free to return prose;
     * set it before and the effort is silently dropped. Both were verified
     * against the SDK rather than assumed. So the schema is generated first, read
     * back, and the effort merged into that same object via {@code toBuilder()}.
     *
     * <p>Generation costs a few milliseconds per call against a model call
     * measured in seconds, so it is not worth caching and the extra state it
     * would add is not worth owning.
     */
    private StructuredMessageCreateParams<GradingResponse> buildParams(EvaluationRequest request) {
        StructuredMessageCreateParams.Builder<GradingResponse> builder = MessageCreateParams.builder()
                .model(config.model())
                .maxTokens(config.maxOutputTokens())
                .addUserMessage(prompt.render(request))
                .outputConfig(GradingResponse.class);

        OutputConfig generated = builder.build().rawParams().outputConfig()
                .orElseThrow(() -> new IllegalStateException(
                        "SDK produced no output config for the grading schema"));

        return builder
                .outputConfig(generated.toBuilder().effort(effort).build())
                .build();
    }

    /**
     * Makes the call and maps every vendor failure onto our own vocabulary.
     *
     * <p>Ordered most-specific-first, since the SDK's exceptions form a
     * hierarchy. The retryable/permanent split is the decision that matters: a
     * 429 or a dropped connection deserves another attempt, while a malformed
     * request or a bad model id will fail identically forever and must not burn
     * the job's remaining attempts. {@code INVALID_OUTPUT} is the contract's
     * non-retryable reason, which is why permanent request faults map to it.
     */
    private StructuredMessage<GradingResponse> call(
            StructuredMessageCreateParams<GradingResponse> params) {
        try {
            return client.messages().create(params);

        } catch (RateLimitException e) {
            throw fail(EvaluationProviderException.Reason.RATE_LIMITED, "rate limited", e);

        } catch (UnauthorizedException | PermissionDeniedException e) {
            // A missing, revoked or unprivileged credential will not fix itself
            // between retries.
            throw fail(EvaluationProviderException.Reason.INVALID_OUTPUT,
                    "provider rejected our credentials", e);

        } catch (NotFoundException | BadRequestException | UnprocessableEntityException e) {
            // A wrong model id, an oversized prompt, or a schema the provider
            // will not accept. Permanent until the code or config changes.
            throw fail(EvaluationProviderException.Reason.INVALID_OUTPUT,
                    "provider rejected the request", e);

        } catch (AnthropicIoException e) {
            // Network failure, including the client's own timeout.
            throw fail(EvaluationProviderException.Reason.TIMEOUT,
                    "could not reach the provider", e);

        } catch (AnthropicServiceException e) {
            // 5xx and any other status the SDK treats as a service fault.
            throw fail(EvaluationProviderException.Reason.PROVIDER_ERROR,
                    "provider returned an error", e);

        } catch (RuntimeException e) {
            // Includes deserialization failures surfaced by the SDK. Caught so
            // an unexpected vendor exception cannot escape as something the
            // engine has no failure category for.
            throw fail(EvaluationProviderException.Reason.PROVIDER_ERROR,
                    "unexpected provider failure", e);
        }
    }

    /**
     * Maps the vendor response onto our contract.
     *
     * <p>Nothing here is trusted. A criterion whose id will not parse is dropped
     * rather than thrown on, so the validator sees a genuinely incomplete
     * criterion set — which it already knows how to reject — instead of a null id
     * it has no rule for.
     */
    private ProviderEvaluation toProviderEvaluation(StructuredMessage<GradingResponse> message) {
        GradingResponse response = extract(message);

        List<ProviderEvaluation.CriterionJudgement> judgements =
                new ArrayList<>(response.criteria().size());

        for (GradingResponse.CriterionVerdict verdict : response.criteria()) {
            UUID criterionId = parseUuid(verdict.criterionId());
            if (criterionId == null) {
                log.warn("Provider returned an unparseable criterionId; dropping that criterion");
                continue;
            }
            judgements.add(new ProviderEvaluation.CriterionJudgement(
                    criterionId,
                    // Schema-constrained to the enum, so null is the only
                    // remaining possibility and MISSING is the safe reading.
                    verdict.verdict() == null ? Verdict.MISSING : verdict.verdict(),
                    toConfidence(verdict.confidence()),
                    blankToNull(verdict.evidenceQuote()),
                    blankToNull(verdict.reasoning())));
        }

        return new ProviderEvaluation(
                judgements,
                blankToNull(response.summary()),
                averageConfidence(judgements),
                response.followUpNeeded(),
                null,
                parseUuid(response.followUpTargetCriterionId()),
                // No model-reported score exists: the schema gives the model
                // nowhere to put one, so there is nothing to discard.
                null,
                response.injectionSuspected(),
                response.answerOffTopic(),
                metadata(message));
    }

    /**
     * Pulls the structured payload out of the response.
     *
     * <p>A refusal, or a turn that hit the token ceiling, arrives as a successful
     * HTTP response carrying no usable content. Treated as unusable output rather
     * than a transport fault: retrying would produce the same refusal, and
     * synthesising an empty verdict set would let a refusal read as "the
     * candidate met nothing" — a real score, silently wrong.
     */
    private GradingResponse extract(StructuredMessage<GradingResponse> message) {
        try {
            return message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(text -> text.text())
                    .findFirst()
                    .orElseThrow(() -> new EvaluationProviderException(
                            EvaluationProviderException.Reason.INVALID_OUTPUT,
                            "provider returned no structured grading content (stopReason="
                                    + message.stopReason().map(Object::toString).orElse("unknown")
                                    + ")"));
        } catch (EvaluationProviderException e) {
            throw e;
        } catch (RuntimeException e) {
            // The typed accessor parses lazily, so a payload that satisfied the
            // transport but not the schema surfaces here rather than at create().
            throw fail(EvaluationProviderException.Reason.INVALID_OUTPUT,
                    "provider output did not match the grading schema", e);
        }
    }

    /**
     * Provenance for {@code ai_invocations}.
     *
     * <p>{@code costMicros} is deliberately left null. The response reports
     * tokens but not money, so a figure here could only come from a price table
     * compiled into the build — which would go stale silently and turn into
     * confidently wrong spend reporting. Tokens are recorded faithfully; cost is
     * derived downstream where the rates can be maintained.
     */
    private ProviderEvaluation.ProviderMetadata metadata(StructuredMessage<GradingResponse> message) {
        return new ProviderEvaluation.ProviderMetadata(
                PROVIDER_NAME,
                config.model(),
                prompt.version(),
                toIntExact(message.usage().inputTokens()),
                toIntExact(message.usage().outputTokens()),
                null);
    }

    private static Integer toIntExact(long tokens) {
        return tokens > Integer.MAX_VALUE || tokens < 0 ? null : (int) tokens;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Out-of-range confidence is clamped here; the validator clamps again. */
    private static BigDecimal toConfidence(Double value) {
        if (value == null || value.isNaN()) {
            return null;
        }
        return BigDecimal.valueOf(Math.clamp(value, 0.0d, 1.0d))
                .setScale(3, RoundingMode.HALF_UP);
    }

    private static BigDecimal averageConfidence(
            List<ProviderEvaluation.CriterionJudgement> judgements) {
        List<BigDecimal> present = judgements.stream()
                .map(ProviderEvaluation.CriterionJudgement::confidence)
                .filter(Objects::nonNull)
                .toList();
        if (present.isEmpty()) {
            return null;
        }
        return present.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(present.size()), 3, RoundingMode.HALF_UP);
    }

    /** The schema requires a string, so absence is {@code ""}. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Logs the failure without the vendor's message.
     *
     * <p>Provider error text can echo the request back, and the request contains
     * the candidate's answer. The category and the exception type are what an
     * operator needs to act; the rest is not worth putting in an ordinary log.
     * The cause is still attached to the exception for a deliberate investigation.
     */
    private EvaluationProviderException fail(EvaluationProviderException.Reason reason,
                                             String summary, Exception cause) {
        log.warn("Anthropic evaluation failed: reason={} cause={}",
                reason, cause.getClass().getSimpleName());
        return new EvaluationProviderException(reason, summary, cause);
    }
}
