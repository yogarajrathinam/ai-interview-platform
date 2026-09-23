package com.aiinterview.interviewplatform.evaluation.application;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationResult.EvidenceSpan;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Verifies that quoted evidence actually occurs in the candidate's answer.
 *
 * <p>This is the anti-hallucination gate, and the single most important
 * validation in the product. A grader that cannot quote the answer cannot have
 * read the claim there — and hallucinated credit is the most damaging failure
 * this system could produce, because it is invisible: the score looks
 * defensible and the evidence looks real.
 *
 * <p>Offsets are computed <em>here</em>, never taken from the provider. Models
 * have no reliable notion of character positions, and an offset we did not
 * compute is an offset we cannot trust to highlight in the candidate's report.
 *
 * <p>Matching tolerates the differences that do not change meaning — case, and
 * runs of whitespace — because a grader re-typing a quote with a collapsed line
 * break has still read the answer. It tolerates nothing else: a paraphrase is
 * not evidence.
 */
@Component
public class EvidenceValidator {

    /** Shorter than this is not a quote; it is a coincidence. */
    static final int MIN_QUOTE_LENGTH = 5;

    /** Matches the provider output schema and keeps reports readable. */
    static final int MAX_QUOTE_LENGTH = 300;

    /**
     * Locates the quote in the answer.
     *
     * @return the span with offsets into the original answer text, or empty if
     *         the quote does not occur in it
     */
    public Optional<EvidenceSpan> locate(String answerText, String claimedQuote) {
        if (answerText == null || claimedQuote == null) {
            return Optional.empty();
        }
        String trimmedQuote = claimedQuote.strip();
        if (trimmedQuote.length() < MIN_QUOTE_LENGTH
                || trimmedQuote.length() > MAX_QUOTE_LENGTH) {
            return Optional.empty();
        }

        Normalised answer = normalise(answerText);
        Normalised quote = normalise(trimmedQuote);
        String needle = quote.text().strip();
        if (needle.length() < MIN_QUOTE_LENGTH) {
            return Optional.empty();
        }

        int at = answer.text().indexOf(needle);
        if (at < 0) {
            return Optional.empty();
        }

        // Map back to offsets in the text the report will actually render.
        int start = answer.originalIndex()[at];
        int end = answer.originalIndex()[at + needle.length() - 1] + 1;
        if (end <= start || end > answerText.length()) {
            return Optional.empty();
        }

        return Optional.of(new EvidenceSpan(answerText.substring(start, end), start, end));
    }

    /**
     * Lowercased, with whitespace runs collapsed to a single space, alongside a
     * map from each normalised position back to its original index.
     */
    private static Normalised normalise(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int[] map = new int[source.length()];
        boolean previousWasSpace = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                if (previousWasSpace) {
                    continue;
                }
                out.append(' ');
                map[out.length() - 1] = i;
                previousWasSpace = true;
            } else {
                out.append(Character.toLowerCase(c));
                map[out.length() - 1] = i;
                previousWasSpace = false;
            }
        }
        return new Normalised(out.toString(), map);
    }

    private record Normalised(String text, int[] originalIndex) {
    }
}
