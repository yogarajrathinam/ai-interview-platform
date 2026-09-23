package com.aiinterview.interviewplatform.evaluation.infrastructure.anthropic;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Renders the versioned grading prompt.
 *
 * <p>The template is an immutable classpath resource, not a database row and not
 * a string in this file. Prompt wording changes what a score means, so it is
 * versioned alongside the code that produced it and reviewed like code — and
 * {@link #version()} is stamped onto every evaluation, so a score can always be
 * traced back to the exact instructions that produced it.
 *
 * <p>Editing the wording means adding {@code answer-evaluation-v2.md} and
 * bumping the version, never rewriting v1. Old evaluations must keep naming a
 * prompt that still says what they were graded against.
 */
@Component
public class EvaluationPrompt {

    /**
     * Recorded in {@code evaluations.prompt_version} and on every AI invocation.
     */
    public static final String VERSION = "answer-evaluation-v1";

    private static final String RESOURCE = "prompts/answer-evaluation-v1.md";

    private final String template;

    public EvaluationPrompt() {
        // Read once at startup: a missing or unreadable prompt must fail the
        // context, not the first candidate's evaluation.
        try {
            this.template = StreamUtils.copyToString(
                    new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Evaluation prompt " + RESOURCE + " is missing", e);
        }
    }

    public String version() {
        return VERSION;
    }

    /**
     * Fills the template for one answer.
     *
     * <p>The candidate's text is placed only inside the delimited block, and
     * any closing delimiter it contains is neutralised first — a candidate who
     * writes {@code </candidate_answer>} must not be able to end the data
     * section and have what follows read as instructions.
     */
    public String render(EvaluationRequest request) {
        return template
                .replace("{{QUESTION}}", nullSafe(request.question().promptText()))
                .replace("{{REFERENCE_ANSWER}}",
                        blankToPlaceholder(request.question().referenceAnswer()))
                .replace("{{CRITERIA}}", renderCriteria(request))
                .replace("{{ANSWER}}", sanitise(request.answer().text()));
    }

    private String renderCriteria(EvaluationRequest request) {
        StringBuilder out = new StringBuilder();
        for (EvaluationRequest.RubricCriterion criterion : request.rubric()) {
            // The id is what comes back, so it is stated plainly. Weight is
            // deliberately withheld: the grader must not know which criteria
            // are worth more, or it would grade the heavy ones more generously.
            out.append("- criterionId: ").append(criterion.id()).append('\n')
                    .append("  code: ").append(nullSafe(criterion.code())).append('\n')
                    .append("  expectation: ").append(nullSafe(criterion.expectation()))
                    .append("\n\n");
        }
        return out.toString().stripTrailing();
    }

    /**
     * Strips the delimiter sequence from candidate text.
     *
     * <p>Replaced rather than rejected: a candidate legitimately discussing XML
     * should still be gradable, and the content is preserved well enough to
     * quote as evidence.
     */
    private static String sanitise(String answer) {
        if (answer == null) {
            return "";
        }
        return answer.replace("</candidate_answer>", "[/candidate_answer]")
                .replace("<candidate_answer>", "[candidate_answer]");
    }

    private static String blankToPlaceholder(String value) {
        return value == null || value.isBlank() ? "(none supplied)" : value;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
