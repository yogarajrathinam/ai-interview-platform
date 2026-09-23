package com.aiinterview.interviewplatform.evaluation.infrastructure;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationProvider;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationRequest;
import com.aiinterview.interviewplatform.evaluation.api.ProviderEvaluation;
import com.aiinterview.interviewplatform.evaluation.api.Verdict;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A provider that grades by term overlap instead of by understanding.
 *
 * <p><strong>This is not the AI implementation and is not a stand-in for one.</strong>
 * It exists so the whole pipeline — validation, evidence location, scoring,
 * persistence, idempotency, accounting — can be proven correct and
 * reproducible before a single model call is made. Every defect found here is
 * one that would otherwise have been mistaken for a prompt problem later.
 *
 * <p>It is honest about what it is: term overlap is not comprehension, it will
 * mark a fluent wrong answer as correct, and it can never return
 * {@link Verdict#CONTRADICTED} because detecting a false claim requires knowing
 * what is true. Those are exactly the jobs the real provider will do.
 *
 * <p>Fully deterministic: the same answer and rubric always yield the same
 * verdicts, which is what makes it usable in assertions.
 *
 * <p>Selected by {@code app.ai.provider=deterministic}, which is the default: a
 * fresh checkout, every test and a misconfigured environment all grade with this
 * rather than reaching for a paid credential. The real provider is gated on the
 * same key, so exactly one {@link EvaluationProvider} bean ever exists and
 * nothing in the engine, the validator or the scorer changes between them —
 * which is the point of the port.
 */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "provider",
                       havingValue = "deterministic", matchIfMissing = true)
public class DeterministicEvaluationProvider implements EvaluationProvider {

    public static final String PROVIDER_NAME = "deterministic";
    public static final String MODEL = "deterministic-rubric-matcher-1";
    public static final String PROMPT_VERSION = "deterministic@1.0.0";

    /** Share of a criterion's significant terms that must appear for MET. */
    private static final double MET_THRESHOLD = 0.60;

    /** Below MET but at or above this is a partial answer. */
    private static final double PARTIAL_THRESHOLD = 0.30;

    private static final int MIN_TERM_LENGTH = 4;

    private static final Set<String> STOPWORDS = Set.of(
            "that", "this", "with", "from", "they", "them", "then", "than", "when", "what",
            "which", "while", "where", "have", "has", "had", "been", "being", "does", "doing",
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "its",
            "into", "over", "such", "only", "also", "states", "state", "describes", "describe",
            "names", "name", "gives", "give", "mentions", "mention", "answer", "explains");

    /**
     * Phrases that attempt to talk to the grader rather than answer the
     * question. A real provider reports this from the model; here it is a fixed
     * list, so the downstream handling can still be exercised.
     */
    private static final List<String> INJECTION_MARKERS = List.of(
            "ignore previous", "ignore the previous", "ignore all previous",
            "disregard the", "you are now", "system prompt", "award full marks",
            "give me a 10", "score this 10", "act as");

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public ProviderEvaluation evaluate(EvaluationRequest request) {
        String answer = request.answer().text();
        String haystack = answer.toLowerCase(Locale.ROOT);

        List<ProviderEvaluation.CriterionJudgement> judgements = new ArrayList<>();
        UUID heaviestWeakCriterion = null;
        int heaviestWeakWeight = 0;
        boolean anyMatch = false;

        for (EvaluationRequest.RubricCriterion criterion : request.rubric()) {
            Set<String> terms = significantTerms(criterion.expectation());
            long matched = terms.stream().filter(haystack::contains).count();
            double ratio = terms.isEmpty() ? 0.0 : (double) matched / terms.size();

            Verdict verdict = ratio >= MET_THRESHOLD ? Verdict.MET
                    : ratio >= PARTIAL_THRESHOLD ? Verdict.PARTIAL
                    : Verdict.MISSING;

            if (verdict != Verdict.MISSING) {
                anyMatch = true;
            } else if (criterion.weightBp() > heaviestWeakWeight) {
                heaviestWeakCriterion = criterion.id();
                heaviestWeakWeight = criterion.weightBp();
            }

            judgements.add(new ProviderEvaluation.CriterionJudgement(
                    criterion.id(),
                    verdict,
                    confidenceFor(ratio),
                    verdict.requiresEvidence() ? bestSentence(answer, terms) : null,
                    "Matched %d of %d expected terms.".formatted(matched, terms.size())));
        }

        boolean injectionSuspected = INJECTION_MARKERS.stream().anyMatch(haystack::contains);
        boolean offTopic = !anyMatch && answer.strip().length() > 40;

        return new ProviderEvaluation(
                judgements,
                summaryFor(judgements),
                overallConfidence(judgements),
                heaviestWeakCriterion != null,
                heaviestWeakCriterion == null ? null : "A weakly covered criterion remains.",
                heaviestWeakCriterion,
                // Deliberately absent: this provider is not a model, and
                // inventing a holistic score would put a fabricated number into
                // the divergence signal that exists to catch fabricated numbers.
                null,
                injectionSuspected,
                offTopic,
                new ProviderEvaluation.ProviderMetadata(PROVIDER_NAME, MODEL, PROMPT_VERSION,
                        // No tokens were consumed and nothing was spent. Null,
                        // never zero: zero would be a claim, null is the truth.
                        null, null, null));
    }

    /** Content words of the expectation, lowercased and de-duplicated. */
    private Set<String> significantTerms(String expectation) {
        if (expectation == null || expectation.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(expectation.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(term -> term.length() >= MIN_TERM_LENGTH)
                .filter(term -> !STOPWORDS.contains(term))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The sentence containing the most expected terms, offered as evidence.
     *
     * <p>Returned verbatim from the answer, because anything else would be
     * rejected by the evidence gate — as it should be.
     */
    private String bestSentence(String answer, Set<String> terms) {
        String best = null;
        long bestHits = 0;
        for (String sentence : answer.split("(?<=[.!?])\\s+")) {
            String trimmed = sentence.strip();
            if (trimmed.length() < 5) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            long hits = terms.stream().filter(lower::contains).count();
            if (hits > bestHits) {
                bestHits = hits;
                best = trimmed;
            }
        }
        if (best == null) {
            return null;
        }
        return best.length() <= 300 ? best : best.substring(0, 300);
    }

    /** Confidence tracks how decisive the overlap was, never exceeding 0.95. */
    private BigDecimal confidenceFor(double ratio) {
        double distanceFromBoundary = Math.min(
                Math.abs(ratio - MET_THRESHOLD), Math.abs(ratio - PARTIAL_THRESHOLD));
        double confidence = Math.min(0.95, 0.60 + distanceFromBoundary);
        return BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal overallConfidence(
            List<ProviderEvaluation.CriterionJudgement> judgements) {
        if (judgements.isEmpty()) {
            return null;
        }
        BigDecimal total = judgements.stream()
                .map(ProviderEvaluation.CriterionJudgement::confidence)
                .filter(c -> c != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(judgements.size()), 3, RoundingMode.HALF_UP);
    }

    private String summaryFor(List<ProviderEvaluation.CriterionJudgement> judgements) {
        long met = judgements.stream().filter(j -> j.verdict() == Verdict.MET).count();
        return "Deterministic term-overlap grading: %d of %d criteria matched."
                .formatted(met, judgements.size());
    }
}
