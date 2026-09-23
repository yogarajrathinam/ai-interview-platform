package com.aiinterview.interviewplatform.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationResult.EvidenceSpan;
import com.aiinterview.interviewplatform.evaluation.application.EvidenceValidator;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The anti-hallucination gate.
 *
 * <p>Evidence is the difference between a score a candidate can argue with and
 * a number they have to take on faith, so this is tested harder than its size
 * suggests. The property that matters: a quote that is not in the answer must
 * never be accepted, no matter how plausible it looks.
 */
@DisplayName("Evidence validation")
class EvidenceValidatorTest {

    private static final String ANSWER = """
            HashMap isn't synchronised so concurrent puts can corrupt the table.
            ConcurrentHashMap is safe to use from multiple threads because it locks \
            individual buckets rather than the whole map.""";

    private final EvidenceValidator validator = new EvidenceValidator();

    @Test
    @DisplayName("an exact quote is located, with offsets into the original text")
    void exactQuoteIsLocated() {
        Optional<EvidenceSpan> span =
                validator.locate(ANSWER, "concurrent puts can corrupt the table");

        assertThat(span).isPresent();
        EvidenceSpan evidence = span.get();
        // The offsets must address the very string a report will render.
        assertThat(ANSWER.substring(evidence.start(), evidence.end()))
                .isEqualTo(evidence.quote())
                .isEqualTo("concurrent puts can corrupt the table");
    }

    @Test
    @DisplayName("case differences do not invalidate a real quote")
    void matchingIsCaseInsensitive() {
        Optional<EvidenceSpan> span =
                validator.locate(ANSWER, "CONCURRENT PUTS CAN CORRUPT THE TABLE");

        assertThat(span).isPresent();
        // The stored quote is the candidate's casing, not the provider's.
        assertThat(span.get().quote()).isEqualTo("concurrent puts can corrupt the table");
    }

    @Test
    @DisplayName("a collapsed line break does not invalidate a real quote")
    void matchingToleratesWhitespaceDifferences() {
        Optional<EvidenceSpan> span = validator.locate(
                ANSWER, "corrupt the table. ConcurrentHashMap is safe");

        assertThat(span).isPresent();
        assertThat(ANSWER.substring(span.get().start(), span.get().end()))
                .contains("corrupt the table")
                .contains("ConcurrentHashMap is safe");
    }

    @Test
    @DisplayName("a fabricated quote is rejected")
    void fabricatedQuoteIsRejected() {
        assertThat(validator.locate(ANSWER,
                "ConcurrentHashMap forbids null keys and values")).isEmpty();
    }

    @Test
    @DisplayName("a paraphrase is not evidence")
    void paraphraseIsRejected() {
        // Every idea here is in the answer; these exact words are not.
        assertThat(validator.locate(ANSWER,
                "the map is not thread safe for parallel writes")).isEmpty();
    }

    @Test
    @DisplayName("a quote too short to be meaningful is rejected")
    void tooShortIsRejected() {
        assertThat(validator.locate(ANSWER, "the")).isEmpty();
        assertThat(validator.locate(ANSWER, "")).isEmpty();
        assertThat(validator.locate(ANSWER, "   ")).isEmpty();
    }

    @Test
    @DisplayName("a quote beyond the length cap is rejected")
    void tooLongIsRejected() {
        String overlong = "x".repeat(301);
        assertThat(validator.locate(overlong + ANSWER, overlong)).isEmpty();
    }

    @Test
    @DisplayName("null inputs are handled without throwing")
    void nullsAreSafe() {
        assertThat(validator.locate(null, "anything")).isEmpty();
        assertThat(validator.locate(ANSWER, null)).isEmpty();
        assertThat(validator.locate(null, null)).isEmpty();
    }

    @Test
    @DisplayName("the whole answer can be its own evidence")
    void wholeAnswerIsValidEvidence() {
        String shortAnswer = "HashMap is not thread-safe.";
        Optional<EvidenceSpan> span = validator.locate(shortAnswer, shortAnswer);

        assertThat(span).isPresent();
        assertThat(span.get().start()).isZero();
        assertThat(span.get().end()).isEqualTo(shortAnswer.length());
    }

    @Test
    @DisplayName("a span always has end after start")
    void spanOrderingIsEnforced() {
        Optional<EvidenceSpan> span = validator.locate(ANSWER, "individual buckets");

        assertThat(span).isPresent();
        assertThat(span.get().end()).isGreaterThan(span.get().start());
        assertThat(span.get().length()).isEqualTo("individual buckets".length());
    }
}
