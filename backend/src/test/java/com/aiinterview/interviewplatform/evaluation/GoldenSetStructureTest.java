package com.aiinterview.interviewplatform.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiinterview.interviewplatform.evaluation.api.Verdict;
import com.aiinterview.interviewplatform.support.golden.GoldenCase;
import com.aiinterview.interviewplatform.support.golden.GoldenSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the dataset, not the grader.
 *
 * <p>The agreement gate is only as trustworthy as the labels it measures
 * against, and every defect here would weaken that gate <em>silently</em> — an
 * unlabelled criterion shrinks the denominator, a duplicated case id overwrites
 * a label, an empty set passes vacuously. None of them fail anything on their
 * own, which is exactly why they are asserted here rather than left to review.
 *
 * <p>Runs in CI: it touches no provider and costs nothing.
 */
@DisplayName("Golden set structure")
class GoldenSetStructureTest {

    private final List<GoldenCase> cases = GoldenSet.load();

    @Test
    @DisplayName("is large enough and varied enough to be worth measuring")
    void datasetIsSubstantive() {
        assertThat(cases).hasSizeGreaterThanOrEqualTo(6);

        // A set of only clear-cut answers scores well and proves nothing, so the
        // hard verdicts have to be represented.
        Set<Verdict> labelled = cases.stream()
                .flatMap(c -> c.expected().values().stream())
                .collect(Collectors.toSet());
        assertThat(labelled)
                .as("every verdict must appear, especially CONTRADICTED — a set "
                        + "without it never tests wrong-versus-absent")
                .containsExactlyInAnyOrder(
                        Verdict.MET, Verdict.PARTIAL, Verdict.MISSING, Verdict.CONTRADICTED);

        assertThat(cases).extracting(GoldenCase::skill).contains("JAVA", "SPRING_BOOT", "SQL");
    }

    @Test
    @DisplayName("gives every case a unique id")
    void caseIdsAreUnique() {
        assertThat(cases).extracting(GoldenCase::id).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("labels every criterion it declares, and no others")
    void labelsAreComplete() {
        for (GoldenCase testCase : cases) {
            Set<String> declared = testCase.criteria().stream()
                    .map(GoldenCase.Criterion::code)
                    .collect(Collectors.toSet());

            assertThat(testCase.expected().keySet())
                    .as("case '%s' must label exactly the criteria it declares; an "
                            + "unlabelled criterion would quietly leave the denominator",
                            testCase.id())
                    .isEqualTo(declared);
        }
    }

    @Test
    @DisplayName("declares well-formed rubrics")
    void rubricsAreWellFormed() {
        for (GoldenCase testCase : cases) {
            assertThat(testCase.criteria())
                    .as("case '%s' needs at least one criterion", testCase.id())
                    .isNotEmpty();

            assertThat(testCase.criteria()).extracting(GoldenCase.Criterion::code)
                    .as("case '%s' repeats a criterion code", testCase.id())
                    .doesNotHaveDuplicates();

            int totalWeight = testCase.criteria().stream()
                    .mapToInt(GoldenCase.Criterion::weightBp)
                    .sum();
            assertThat(totalWeight)
                    .as("case '%s' rubric must sum to 10000 basis points like a "
                            + "published one, or it is not exercising real scoring",
                            testCase.id())
                    .isEqualTo(10_000);

            testCase.criteria().forEach(criterion -> {
                assertThat(criterion.expectation())
                        .as("criterion '%s' in case '%s' needs an expectation",
                                criterion.code(), testCase.id())
                        .isNotBlank();
                assertThat(criterion.weightBp()).isPositive();
            });
        }
    }

    @Test
    @DisplayName("carries a real question, answer and reviewer rationale")
    void contentIsPresent() {
        for (GoldenCase testCase : cases) {
            assertThat(testCase.question().promptText())
                    .as("case '%s'", testCase.id()).isNotBlank();
            assertThat(testCase.answer())
                    .as("case '%s'", testCase.id()).isNotBlank();
            assertThat(testCase.notes())
                    .as("case '%s' must record why it is labelled as it is — an "
                            + "unexplained label cannot be reviewed or defended",
                            testCase.id())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("converts to a request the engine accepts")
    void casesConvertToRequests() {
        for (GoldenCase testCase : cases) {
            // Construction is where EvaluationRequest enforces its invariants,
            // so this proves the dataset cannot reach the live gate malformed.
            assertThat(testCase.toRequest().rubric())
                    .as("case '%s'", testCase.id())
                    .hasSameSizeAs(testCase.criteria());
        }
    }

    @Test
    @DisplayName("derives criterion ids that are stable and collision-free")
    void criterionIdsAreStableAndDistinct() {
        for (GoldenCase testCase : cases) {
            List<String> codes = testCase.criteria().stream()
                    .map(GoldenCase.Criterion::code).toList();

            assertThat(codes.stream().map(testCase::criterionId).collect(Collectors.toSet()))
                    .as("case '%s' derives a duplicate criterion id", testCase.id())
                    .hasSameSizeAs(codes);

            // The provider echoes these ids back and the adapter parses them, so
            // repeated derivation must agree or a case would grade as unanswered.
            codes.forEach(code -> assertThat(testCase.criterionId(code))
                    .isEqualTo(testCase.criterionId(code)));
        }
    }
}
