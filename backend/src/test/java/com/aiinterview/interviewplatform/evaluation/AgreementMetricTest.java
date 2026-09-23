package com.aiinterview.interviewplatform.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationResult;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationStatus;
import com.aiinterview.interviewplatform.evaluation.api.ProviderEvaluation;
import com.aiinterview.interviewplatform.evaluation.api.Verdict;
import com.aiinterview.interviewplatform.support.golden.AgreementReport;
import com.aiinterview.interviewplatform.support.golden.GoldenCase;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The measurement, measured.
 *
 * <p>The agreement number is the only evidence we have about grader quality, and
 * a bug in it would be invisible: a metric that quietly ignores criteria the
 * provider omitted reports a higher score for worse behaviour, and nothing else
 * in the suite would notice. So the arithmetic is driven with scripted verdicts
 * whose correct answer is known in advance.
 *
 * <p>Runs in CI — no provider, no credential, no cost.
 */
@DisplayName("Agreement metric")
class AgreementMetricTest {

    // ------------------------------------------------------------ fixtures

    private static GoldenCase testCase(Map<String, Verdict> expected) {
        List<GoldenCase.Criterion> criteria = new ArrayList<>();
        int count = expected.size();
        int each = 10_000 / count;
        int index = 0;
        for (String code : expected.keySet()) {
            // Last criterion absorbs the remainder so the rubric sums to 10000.
            int weight = ++index == count ? 10_000 - each * (count - 1) : each;
            criteria.add(new GoldenCase.Criterion(code, "expectation for " + code, weight, "CORE"));
        }
        return new GoldenCase("scripted", "JAVA", "metric fixture",
                new GoldenCase.Question("Question?", "Reference."),
                criteria, "The candidate answer.", expected);
    }

    private static EvaluationResult resultWith(GoldenCase testCase,
                                               Map<String, Verdict> actual,
                                               List<String> evidenceRejected) {
        List<EvaluationResult.CriterionOutcome> outcomes = actual.entrySet().stream()
                .map(entry -> new EvaluationResult.CriterionOutcome(
                        testCase.criterionId(entry.getKey()), entry.getKey(), entry.getKey(),
                        entry.getValue(), BigDecimal.ONE, 1000, null, null,
                        evidenceRejected.contains(entry.getKey()), null))
                .toList();

        return new EvaluationResult(EvaluationStatus.SUCCEEDED, new BigDecimal("1.00"),
                outcomes, "summary", null, false, null, null, null, false, false,
                new ProviderEvaluation.ProviderMetadata("stub", "stub-1", "stub@1",
                        null, null, null),
                null, null);
    }

    private static AgreementReport report(Map<String, Verdict> expected,
                                          Map<String, Verdict> actual,
                                          List<String> evidenceRejected) {
        GoldenCase testCase = testCase(expected);
        AgreementReport report = new AgreementReport();
        report.record(testCase, resultWith(testCase, actual, evidenceRejected));
        return report;
    }

    // ------------------------------------------------------------ tests

    @Test
    @DisplayName("scores perfect agreement as 100%")
    void perfectAgreement() {
        Map<String, Verdict> labels = Map.of("A", Verdict.MET, "B", Verdict.MISSING);

        AgreementReport report = report(labels, labels, List.of());

        assertThat(report.total()).isEqualTo(2);
        assertThat(report.rate()).isEqualTo(1.0d);
        assertThat(report.mismatches()).isEmpty();
    }

    @Test
    @DisplayName("counts each criterion separately rather than each case")
    void criterionLevelNotCaseLevel() {
        AgreementReport report = report(
                Map.of("A", Verdict.MET, "B", Verdict.MET, "C", Verdict.MET, "D", Verdict.MET),
                Map.of("A", Verdict.MET, "B", Verdict.MET, "C", Verdict.MET,
                        "D", Verdict.MISSING),
                List.of());

        // Case-level scoring would call this one wholly failed case: 0%.
        assertThat(report.rate()).isEqualTo(0.75d);
        assertThat(report.mismatches()).singleElement()
                .satisfies(m -> assertThat(m.code()).isEqualTo("D"));
    }

    @Test
    @DisplayName("treats a near miss as a miss")
    void agreementIsExactNotGraded() {
        AgreementReport report = report(
                Map.of("A", Verdict.MET),
                Map.of("A", Verdict.PARTIAL),
                List.of());

        assertThat(report.rate())
                .as("MET vs PARTIAL is a disagreement; partial credit here would "
                        + "flatter the grader exactly on the hedged answers")
                .isZero();
    }

    @Test
    @DisplayName("counts a criterion the provider omitted as a miss, not an exclusion")
    void omittedCriterionCountsAgainst() {
        AgreementReport report = report(
                Map.of("A", Verdict.MET, "B", Verdict.MET),
                Map.of("A", Verdict.MET),
                List.of());

        assertThat(report.total())
                .as("the denominator is the labels, never what the provider chose "
                        + "to return — otherwise omitting a hard criterion raises "
                        + "the score")
                .isEqualTo(2);
        assertThat(report.rate()).isEqualTo(0.5d);
        assertThat(report.mismatches()).singleElement()
                .satisfies(m -> {
                    assertThat(m.code()).isEqualTo("B");
                    assertThat(m.actual()).isNull();
                });
    }

    @Test
    @DisplayName("records criteria the provider invented")
    void unexpectedCriteriaAreRecorded() {
        GoldenCase testCase = testCase(Map.of("A", Verdict.MET));
        AgreementReport report = new AgreementReport();

        List<EvaluationResult.CriterionOutcome> outcomes = List.of(
                new EvaluationResult.CriterionOutcome(testCase.criterionId("A"), "A", "A",
                        Verdict.MET, BigDecimal.ONE, 10_000, null, null, false, null),
                new EvaluationResult.CriterionOutcome(UUID.randomUUID(), "INVENTED", "INVENTED",
                        Verdict.MET, BigDecimal.ONE, 0, null, null, false, null));

        report.record(testCase, new EvaluationResult(EvaluationStatus.SUCCEEDED,
                new BigDecimal("1.00"), outcomes, "s", null, false, null, null, null,
                false, false,
                new ProviderEvaluation.ProviderMetadata("stub", "stub-1", "stub@1",
                        null, null, null),
                null, null));

        assertThat(report.rate()).isEqualTo(1.0d);
        assertThat(report.unexpectedCriteria()).containsKey("scripted");
    }

    @Test
    @DisplayName("surfaces evidence rejections alongside agreement")
    void evidenceRejectionsAreVisible() {
        // A grader can reach the right verdict while fabricating the quote for
        // it. The verdict agrees; the reading was not earned, and the count is
        // reported so a run cannot look clean while that is happening.
        AgreementReport report = report(
                Map.of("A", Verdict.MET),
                Map.of("A", Verdict.MET),
                List.of("A"));

        assertThat(report.rate()).isEqualTo(1.0d);
        assertThat(report.evidenceRejections()).isEqualTo(1);
        assertThat(report.render()).contains("evidence rejected : 1");
    }

    @Test
    @DisplayName("reports an empty set as zero rather than as success")
    void emptyReportIsNotVacuouslyPerfect() {
        assertThat(new AgreementReport().rate())
                .as("an empty report must never clear the gate")
                .isZero();
    }

    @Test
    @DisplayName("renders the mismatches, not just the headline number")
    void renderNamesWhatWentWrong() {
        String rendered = report(
                Map.of("A", Verdict.MET, "B", Verdict.CONTRADICTED),
                Map.of("A", Verdict.MET, "B", Verdict.MISSING),
                List.of()).render();

        assertThat(rendered)
                .contains("agreement         : 50.0%")
                .contains("scripted")
                .contains("expected CONTRADICTED")
                .contains("got MISSING");
    }

    @Test
    @DisplayName("computes the rate as agreed over total")
    void rateArithmetic() {
        AgreementReport report = report(
                Map.of("A", Verdict.MET, "B", Verdict.MET, "C", Verdict.MET),
                Map.of("A", Verdict.MET, "B", Verdict.MET, "C", Verdict.MISSING),
                List.of());

        assertThat(report.agreed()).isEqualTo(2);
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.rate()).isCloseTo(2.0d / 3.0d, within(1e-9));
    }
}
