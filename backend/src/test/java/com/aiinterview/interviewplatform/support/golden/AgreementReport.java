package com.aiinterview.interviewplatform.support.golden;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationResult;
import com.aiinterview.interviewplatform.evaluation.api.Verdict;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Criterion-level agreement between the grader and the human labels.
 *
 * <p>The unit is a <em>criterion</em>, not a case or a score. Agreeing on a
 * score is not evidence of agreeing on the reading: two errors in opposite
 * directions cancel out and produce a number that looks perfect. Comparing each
 * criterion separately makes the cancellation impossible and tells us <em>which</em>
 * judgement went wrong, which is the part that can actually be fixed.
 *
 * <p>Agreement is exact rather than graded — {@code MET} against {@code PARTIAL}
 * counts as a miss, the same as {@code MET} against {@code MISSING}. Partial
 * credit for "nearly right" would be a second scoring policy invented inside the
 * measurement, and it would flatter the grader precisely on the hedged cases
 * where its reading matters most.
 *
 * <p>A criterion the provider never returned counts as a miss, not as an
 * exclusion: silently dropping it would shrink the denominator and raise the
 * score for failing to answer.
 */
public final class AgreementReport {

    /** @param actual null when the provider returned no verdict for the criterion */
    public record Comparison(String caseId, String code, Verdict expected, Verdict actual,
                             boolean evidenceRejected) {

        public boolean agreed() {
            return actual != null && actual == expected;
        }
    }

    private final List<Comparison> comparisons = new ArrayList<>();
    private final Map<String, List<String>> unexpectedCriteria = new LinkedHashMap<>();

    /**
     * Records one case by comparing against the <em>validated</em> outcome.
     *
     * <p>Deliberately measured after the gates rather than against the raw
     * provider claim. What a candidate is scored on is the post-validation
     * verdict, so that is what has to agree with a reviewer. A provider whose
     * verdicts are right but whose evidence is fabricated has not earned
     * agreement — the downgrade is the system working, and the metric should
     * show it.
     */
    public void record(GoldenCase testCase, EvaluationResult result) {
        Map<UUID, EvaluationResult.CriterionOutcome> outcomes = new LinkedHashMap<>();
        result.criteria().forEach(outcome -> outcomes.put(outcome.criterionId(), outcome));

        testCase.expectedById().forEach((criterionId, expected) -> {
            EvaluationResult.CriterionOutcome outcome = outcomes.remove(criterionId);
            comparisons.add(new Comparison(
                    testCase.id(),
                    testCase.codeOf(criterionId),
                    expected,
                    outcome == null ? null : outcome.verdict(),
                    outcome != null && outcome.evidenceRejected()));
        });

        // Anything left over was returned but never asked for. The validator
        // should already have rejected it; recorded so that a silent change
        // there shows up here rather than nowhere.
        if (!outcomes.isEmpty()) {
            unexpectedCriteria.put(testCase.id(),
                    outcomes.keySet().stream().map(UUID::toString).toList());
        }
    }

    public int total() {
        return comparisons.size();
    }

    public long agreed() {
        return comparisons.stream().filter(Comparison::agreed).count();
    }

    /** Exact criterion-level agreement in [0,1]; 0 for an empty report. */
    public double rate() {
        return comparisons.isEmpty() ? 0.0d : (double) agreed() / comparisons.size();
    }

    public List<Comparison> mismatches() {
        return comparisons.stream().filter(c -> !c.agreed()).toList();
    }

    public long evidenceRejections() {
        return comparisons.stream().filter(Comparison::evidenceRejected).count();
    }

    public Map<String, List<String>> unexpectedCriteria() {
        return Map.copyOf(unexpectedCriteria);
    }

    /**
     * A human-readable summary, printed on every run including passing ones.
     *
     * <p>The mismatch list is the useful output. A bare percentage above the
     * gate invites the reading "the grader is fine", which a set this size
     * cannot support; the per-criterion detail is what a reviewer can actually
     * act on.
     */
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("%n=== Golden set agreement ===%n".formatted());
        out.append("criteria compared : %d%n".formatted(total()));
        out.append("agreed            : %d%n".formatted(agreed()));
        out.append("agreement         : %.1f%%%n".formatted(rate() * 100));
        out.append("evidence rejected : %d%n".formatted(evidenceRejections()));

        Map<Verdict, Integer> missedByExpected = new EnumMap<>(Verdict.class);
        mismatches().forEach(m ->
                missedByExpected.merge(m.expected(), 1, Integer::sum));
        if (!missedByExpected.isEmpty()) {
            out.append("misses by expected verdict: %s%n".formatted(missedByExpected));
        }

        if (!mismatches().isEmpty()) {
            out.append("%nMismatches:%n".formatted());
            mismatches().forEach(m -> out.append("  %-34s %-18s expected %-13s got %s%n"
                    .formatted(m.caseId(), m.code(), m.expected(),
                            m.actual() == null ? "<not returned>" : m.actual())));
        }

        if (!unexpectedCriteria.isEmpty()) {
            out.append("%nCriteria returned but not in the rubric: %s%n"
                    .formatted(unexpectedCriteria));
        }
        return out.toString();
    }
}
