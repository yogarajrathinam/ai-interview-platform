package com.aiinterview.interviewplatform.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationEngine;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationResult;
import com.aiinterview.interviewplatform.evaluation.application.DefaultEvaluationEngine;
import com.aiinterview.interviewplatform.evaluation.application.EvidenceValidator;
import com.aiinterview.interviewplatform.evaluation.application.ProviderResultValidator;
import com.aiinterview.interviewplatform.evaluation.infrastructure.anthropic.EvaluationPrompt;
import com.aiinterview.interviewplatform.shared.config.AppProperties;
import com.aiinterview.interviewplatform.support.golden.AgreementReport;
import com.aiinterview.interviewplatform.support.golden.GoldenCase;
import com.aiinterview.interviewplatform.support.golden.GoldenSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * The quality gate: does the real grader agree with a human reviewer?
 *
 * <p><strong>Opt-in and excluded from CI.</strong> It calls a paid external
 * service, and its result is not deterministic — the same answers can produce
 * slightly different verdicts between runs. A build that fails on someone else's
 * rate limit, or on a model's ordinary variance, teaches everyone to ignore red,
 * which is worse than having no gate. So CI proves the pipeline and the metric;
 * this proves the grader, deliberately, when a human asks it to.
 *
 * <pre>
 * ANTHROPIC_API_KEY=... mvn -P live-ai test -Dtest=GoldenSetLiveEvaluationTest
 * </pre>
 *
 * <p>It skips rather than fails without a credential: a missing key means "not
 * asked for", and a red build for that would be noise. Being tagged
 * {@code live-ai} means it is not run at all unless the profile selects it —
 * the skip is only the second line of defence.
 *
 * <p>Agreement is measured on the <em>validated</em> result, not the raw
 * provider response. That is what a candidate is actually scored on, so it is
 * what has to agree with a reviewer.
 */
@Tag("live-ai")
@DisplayName("Golden set — live provider")
class GoldenSetLiveEvaluationTest {

    /**
     * Criterion-level agreement below which the grader is not fit to score.
     *
     * <p>A stop-gate, not a target. Override with
     * {@code -Dgolden.agreement.threshold=0.8} when tightening it deliberately.
     */
    private static final double DEFAULT_THRESHOLD = 0.70d;

    private static double threshold() {
        return Double.parseDouble(
                System.getProperty("golden.agreement.threshold",
                        String.valueOf(DEFAULT_THRESHOLD)));
    }

    private static String apiKey() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key == null ? System.getProperty("anthropic.api.key") : key;
    }

    @Test
    @DisplayName("agrees with the human labels often enough to be trusted")
    void meetsAgreementThreshold() {
        String apiKey = apiKey();
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "No ANTHROPIC_API_KEY: the live gate was not asked for.");

        List<GoldenCase> cases = GoldenSet.load();
        AgreementReport report = new AgreementReport();

        try (AnnotationConfigApplicationContext context = context(apiKey)) {
            EvaluationEngine engine = new DefaultEvaluationEngine(
                    context.getBean(com.aiinterview.interviewplatform.evaluation.api
                            .EvaluationProvider.class),
                    new ProviderResultValidator(new EvidenceValidator()));

            for (GoldenCase testCase : cases) {
                EvaluationResult result = engine.evaluate(testCase.toRequest());

                // A provider failure is an infrastructure problem, not a
                // disagreement. Failing loudly here keeps it from being silently
                // averaged into the quality number as if the model had been wrong.
                assertThat(result.isSuccess())
                        .as("case '%s' did not evaluate: %s / %s",
                                testCase.id(), result.errorCode(), result.errorDetail())
                        .isTrue();

                report.record(testCase, result);
            }
        }

        // Printed on every run, passing or failing: the mismatch list is the
        // useful output, and a bare percentage above the gate invites the
        // reading "the grader is fine", which a set this size cannot support.
        System.out.println(report.render());

        assertThat(report.rate())
                .as("criterion-level agreement with the golden labels. Below this "
                        + "the grader is not fit to score a candidate — read the "
                        + "mismatches above before changing the threshold.")
                .isGreaterThanOrEqualTo(threshold());
    }

    private AnnotationConfigApplicationContext context(String apiKey) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("ai-selection", Map.of("app.ai.provider", "anthropic")));
        ctx.registerBean(AppProperties.class, () -> new AppProperties(
                "test", "test",
                new AppProperties.Api("/api/v1"),
                new AppProperties.Jobs(false, 1000, 5, 300, 4, 5, 600, 500),
                new AppProperties.Ai("anthropic", "claude-opus-5", apiKey,
                        null, 120, 8000, "medium")));
        ctx.register(EvaluationPrompt.class,
                com.aiinterview.interviewplatform.evaluation.infrastructure.anthropic
                        .AnthropicProviderConfiguration.class);
        ctx.refresh();
        return ctx;
    }
}
