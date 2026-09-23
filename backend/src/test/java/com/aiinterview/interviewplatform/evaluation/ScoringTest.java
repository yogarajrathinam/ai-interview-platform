package com.aiinterview.interviewplatform.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiinterview.interviewplatform.evaluation.api.Verdict;
import com.aiinterview.interviewplatform.evaluation.domain.AnswerScorer;
import com.aiinterview.interviewplatform.evaluation.domain.AnswerScorer.ScoredCriterion;
import com.aiinterview.interviewplatform.evaluation.domain.InterviewScorer;
import com.aiinterview.interviewplatform.evaluation.domain.InterviewScorer.ScoredQuestion;
import com.aiinterview.interviewplatform.evaluation.domain.ScoringPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The scoring chain, proven without a database, a provider or a Spring context.
 *
 * <p>These are the calculations a candidate's result rests on, so they are
 * tested as pure functions with hand-computed expectations rather than through
 * the pipeline — if a number here is wrong, nothing above it can be right.
 */
@DisplayName("Backend-owned scoring")
class ScoringTest {

    private static final UUID JAVA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SQL = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private static ScoredCriterion criterion(Verdict verdict, int weightBp) {
        return new ScoredCriterion(UUID.randomUUID(), verdict,
                ScoringPolicy.creditFor(verdict, null), weightBp);
    }

    // ------------------------------------------------------- credit table

    @Nested
    @DisplayName("Credit for a verdict")
    class CreditTable {

        @ParameterizedTest(name = "{0} earns {1}")
        @CsvSource({"MET,1.000", "PARTIAL,0.500", "MISSING,0.000", "CONTRADICTED,-0.250"})
        @DisplayName("each verdict maps to its documented credit")
        void creditPerVerdict(Verdict verdict, String expected) {
            assertThat(ScoringPolicy.creditFor(verdict, null))
                    .isEqualByComparingTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("a wrong claim costs more than saying nothing")
        void contradictedCostsMoreThanMissing() {
            assertThat(ScoringPolicy.creditFor(Verdict.CONTRADICTED, null))
                    .isLessThan(ScoringPolicy.creditFor(Verdict.MISSING, null));
        }

        @Test
        @DisplayName("an unsure grader is pulled toward the middle, not trusted")
        void lowConfidenceShrinksTowardTheMidpoint() {
            // MET at confidence 0.25: 0.5 + (1.0 - 0.5) x (0.25 / 0.5) = 0.75
            assertThat(ScoringPolicy.creditFor(Verdict.MET, new BigDecimal("0.25")))
                    .isEqualByComparingTo(new BigDecimal("0.750"));

            // MISSING at confidence 0.25: 0.5 + (0.0 - 0.5) x 0.5 = 0.25
            assertThat(ScoringPolicy.creditFor(Verdict.MISSING, new BigDecimal("0.25")))
                    .isEqualByComparingTo(new BigDecimal("0.250"));
        }

        @Test
        @DisplayName("confidence at or above the floor leaves credit untouched")
        void confidentGradingIsNotAdjusted() {
            assertThat(ScoringPolicy.creditFor(Verdict.MET, new BigDecimal("0.50")))
                    .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(ScoringPolicy.creditFor(Verdict.MET, new BigDecimal("0.99")))
                    .isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("a provider declining to report confidence is not penalised")
        void nullConfidenceMeansFullCredit() {
            assertThat(ScoringPolicy.creditFor(Verdict.MET, null))
                    .isEqualByComparingTo(BigDecimal.ONE);
        }
    }

    // ---------------------------------------------------- question scoring

    @Nested
    @DisplayName("Question score")
    class QuestionScore {

        @Test
        @DisplayName("the worked example from the design docs scores 6.50")
        void workedExample() {
            List<ScoredCriterion> criteria = List.of(
                    criterion(Verdict.MET, 2500),        // 2500
                    criterion(Verdict.MET, 1500),        // 1500
                    criterion(Verdict.PARTIAL, 2000),    // 1000
                    criterion(Verdict.MISSING, 1000),    //    0
                    criterion(Verdict.MISSING, 1500),    //    0
                    criterion(Verdict.MET, 1500));       // 1500

            assertThat(AnswerScorer.isCompleteRubric(criteria)).isTrue();
            assertThat(AnswerScorer.score(criteria))
                    .isEqualByComparingTo(new BigDecimal("6.50"));
        }

        @Test
        @DisplayName("every criterion met scores the maximum")
        void allMet() {
            assertThat(AnswerScorer.score(List.of(
                    criterion(Verdict.MET, 5000), criterion(Verdict.MET, 5000))))
                    .isEqualByComparingTo(new BigDecimal("10.00"));
        }

        @Test
        @DisplayName("nothing met scores the minimum")
        void allMissing() {
            assertThat(AnswerScorer.score(List.of(
                    criterion(Verdict.MISSING, 5000), criterion(Verdict.MISSING, 5000))))
                    .isEqualByComparingTo(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("a contradicted criterion cannot drive the score negative")
        void contradictionIsFlooredAtZero() {
            BigDecimal score = AnswerScorer.score(List.of(
                    criterion(Verdict.CONTRADICTED, 10000)));

            assertThat(score).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(score.signum()).isNotNegative();
        }

        @Test
        @DisplayName("a contradiction still costs more than an omission")
        void contradictionCostsMoreThanOmission() {
            BigDecimal withOmission = AnswerScorer.score(List.of(
                    criterion(Verdict.MET, 7000), criterion(Verdict.MISSING, 3000)));
            BigDecimal withContradiction = AnswerScorer.score(List.of(
                    criterion(Verdict.MET, 7000), criterion(Verdict.CONTRADICTED, 3000)));

            assertThat(withContradiction).isLessThan(withOmission);
        }

        @Test
        @DisplayName("criterion weight decides how much a verdict matters")
        void weightsDominate() {
            BigDecimal heavyMissed = AnswerScorer.score(List.of(
                    criterion(Verdict.MISSING, 9000), criterion(Verdict.MET, 1000)));
            BigDecimal lightMissed = AnswerScorer.score(List.of(
                    criterion(Verdict.MET, 9000), criterion(Verdict.MISSING, 1000)));

            assertThat(heavyMissed).isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(lightMissed).isEqualByComparingTo(new BigDecimal("9.00"));
        }

        @Test
        @DisplayName("scores are rounded half-up to two decimals")
        void roundingIsHalfUpToTwoPlaces() {
            // 1/3 of the weight met -> 3.3333... -> 3.33
            BigDecimal score = AnswerScorer.score(List.of(
                    criterion(Verdict.MET, 3333), criterion(Verdict.MISSING, 6667)));

            assertThat(score).isEqualByComparingTo(new BigDecimal("3.33"));
            assertThat(score.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("an empty rubric scores zero rather than dividing by zero")
        void emptyRubricIsSafe() {
            assertThat(AnswerScorer.score(List.of()))
                    .isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(AnswerScorer.score(null))
                    .isEqualByComparingTo(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("a non-positive criterion weight is rejected at construction")
        void weightsMustBePositive() {
            assertThatThrownBy(() -> criterion(Verdict.MET, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }
    }

    // ------------------------------------------- skill and overall scoring

    @Nested
    @DisplayName("Skill and interview score")
    class Aggregation {

        @Test
        @DisplayName("skill score is the weighted mean of its questions")
        void skillScore() {
            InterviewScorer.InterviewScore result = InterviewScorer.score(
                    List.of(
                            new ScoredQuestion(UUID.randomUUID(), JAVA, 5000, new BigDecimal("8.00")),
                            new ScoredQuestion(UUID.randomUUID(), JAVA, 5000, new BigDecimal("6.00"))),
                    Map.of(JAVA, 10000));

            assertThat(result.skillScores()).hasSize(1);
            assertThat(result.skillScores().get(0).score())
                    .isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(result.overallScore()).isEqualByComparingTo(new BigDecimal("7.00"));
        }

        @Test
        @DisplayName("overall score weights skills by the pinned template weights")
        void overallScoreUsesSkillWeights() {
            InterviewScorer.InterviewScore result = InterviewScorer.score(
                    List.of(
                            new ScoredQuestion(UUID.randomUUID(), JAVA, 5000, new BigDecimal("8.00")),
                            new ScoredQuestion(UUID.randomUUID(), JAVA, 5000, new BigDecimal("6.00")),
                            new ScoredQuestion(UUID.randomUUID(), SQL, 10000, new BigDecimal("5.00"))),
                    Map.of(JAVA, 6000, SQL, 4000));

            // (6000 x 7.00 + 4000 x 5.00) / 10000 = 6.20
            assertThat(result.overallScore()).isEqualByComparingTo(new BigDecimal("6.20"));
            assertThat(result.coverageBp()).isEqualTo(10000);
        }

        @Test
        @DisplayName("a zero-weight follow-up changes nothing")
        void followUpsDoNotMoveTheScore() {
            ScoredQuestion core = new ScoredQuestion(
                    UUID.randomUUID(), JAVA, 10000, new BigDecimal("6.00"));
            ScoredQuestion followUp = new ScoredQuestion(
                    UUID.randomUUID(), JAVA, 0, new BigDecimal("10.00"));

            BigDecimal withoutFollowUp =
                    InterviewScorer.score(List.of(core), Map.of(JAVA, 10000)).overallScore();
            InterviewScorer.InterviewScore withFollowUp =
                    InterviewScorer.score(List.of(core, followUp), Map.of(JAVA, 10000));

            assertThat(withFollowUp.overallScore()).isEqualByComparingTo(withoutFollowUp);
            assertThat(withFollowUp.skillScores().get(0).questionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a skipped answer scores zero and is counted")
        void skippedAnswersCount() {
            InterviewScorer.InterviewScore result = InterviewScorer.score(
                    List.of(
                            new ScoredQuestion(UUID.randomUUID(), JAVA, 5000, new BigDecimal("8.00")),
                            new ScoredQuestion(UUID.randomUUID(), JAVA, 5000, BigDecimal.ZERO)),
                    Map.of(JAVA, 10000));

            assertThat(result.overallScore()).isEqualByComparingTo(new BigDecimal("4.00"));
            assertThat(result.coverageBp()).isEqualTo(10000);
        }

        @Test
        @DisplayName("an ungradable answer is excluded and reported as lost coverage")
        void ungradedAnswersReduceCoverageInsteadOfScoringZero() {
            InterviewScorer.InterviewScore result = InterviewScorer.score(
                    List.of(
                            new ScoredQuestion(UUID.randomUUID(), JAVA, 5000, new BigDecimal("8.00")),
                            new ScoredQuestion(UUID.randomUUID(), JAVA, 5000, null)),
                    Map.of(JAVA, 10000));

            // Our outage must not look like the candidate's failure.
            assertThat(result.overallScore()).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(result.coverageBp()).isEqualTo(5000);
            assertThat(result.skillScores().get(0).coverageBp()).isEqualTo(5000);
        }

        @Test
        @DisplayName("overall renormalises over covered skills only")
        void uncoveredSkillDoesNotDragTheTotalDown() {
            InterviewScorer.InterviewScore result = InterviewScorer.score(
                    List.of(
                            new ScoredQuestion(UUID.randomUUID(), JAVA, 10000, new BigDecimal("8.00")),
                            new ScoredQuestion(UUID.randomUUID(), SQL, 10000, null)),
                    Map.of(JAVA, 5000, SQL, 5000));

            assertThat(result.overallScore()).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(result.skillScores()).hasSize(2);
            assertThat(result.skillScores().stream().filter(s -> !s.isCovered())).hasSize(1);
        }

        @Test
        @DisplayName("nothing gradable yields no score rather than a fabricated zero")
        void nothingGradedIsNotScoreable() {
            InterviewScorer.InterviewScore result = InterviewScorer.score(
                    List.of(new ScoredQuestion(UUID.randomUUID(), JAVA, 10000, null)),
                    Map.of(JAVA, 10000));

            assertThat(result.isScoreable()).isFalse();
            assertThat(result.overallScore()).isNull();
        }

        @Test
        @DisplayName("an empty interview is handled without error")
        void emptyInterview() {
            InterviewScorer.InterviewScore result = InterviewScorer.score(List.of(), Map.of());

            assertThat(result.isScoreable()).isFalse();
            assertThat(result.skillScores()).isEmpty();
            assertThat(result.coverageBp()).isZero();
        }

        @Test
        @DisplayName("scores stay within 0 and 10 at both boundaries")
        void boundaryValues() {
            assertThat(InterviewScorer.score(
                    List.of(new ScoredQuestion(UUID.randomUUID(), JAVA, 10000, BigDecimal.ZERO)),
                    Map.of(JAVA, 10000)).overallScore())
                    .isEqualByComparingTo(new BigDecimal("0.00"));

            assertThat(InterviewScorer.score(
                    List.of(new ScoredQuestion(UUID.randomUUID(), JAVA, 10000, BigDecimal.TEN)),
                    Map.of(JAVA, 10000)).overallScore())
                    .isEqualByComparingTo(new BigDecimal("10.00"));
        }
    }
}
