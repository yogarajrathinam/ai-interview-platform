package com.aiinterview.interviewplatform.evaluation.api;

/**
 * The port between grading logic and whatever actually reads the answer.
 *
 * <p>This is the abstraction that keeps every model vendor out of the business
 * code. The engine depends on this interface and on nothing else; an
 * OpenAI/Gemini/Anthropic SDK may appear only inside an implementation in
 * {@code evaluation.infrastructure}, two layers away from anything that scores.
 *
 * <p>Deliberately narrow. A provider is asked to read a text against a fixed
 * rubric and report what it found. It cannot score, cannot weight, cannot
 * decide follow-ups, and cannot see the rest of the interview.
 *
 * <p>Implementations must be side-effect free with respect to our domain: they
 * persist nothing and mutate nothing. Recording the call in
 * {@code ai_invocations} is the caller's job, so that a provider that throws is
 * still accounted for.
 */
public interface EvaluationProvider {

    /**
     * Reads the answer against the rubric.
     *
     * <p>Implementations should throw {@link EvaluationProviderException} for
     * transport-level failure (timeout, rate limit, outage) and return a result
     * for anything the caller's validation gates should judge. The distinction
     * matters: the first is our problem and is retryable, the second is a
     * quality signal about the answer or the model.
     *
     * @throws EvaluationProviderException when the provider could not produce a result
     */
    ProviderEvaluation evaluate(EvaluationRequest request);

    /** Stable identifier, recorded on every invocation. */
    String providerName();
}
