package com.aiinterview.interviewplatform.support.golden;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationRequest;
import com.aiinterview.interviewplatform.evaluation.api.Verdict;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One hand-labelled answer and the verdicts a reviewer expects for it.
 *
 * <p>Mirrors the JSON in {@code golden/cases/}. Deliberately not the same type
 * as {@link EvaluationRequest}: the dataset is a human artefact keyed by
 * readable criterion codes, and keeping it separate means the file stays
 * reviewable and does not have to change when the request contract does.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record GoldenCase(
        String id,
        String skill,
        String notes,
        Question question,
        List<Criterion> criteria,
        String answer,
        Map<String, Verdict> expected) {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Question(String promptText, String referenceAnswer) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Criterion(String code, String expectation, int weightBp, String tier) {}

    /**
     * A stable UUID for a criterion, derived from the case id and code.
     *
     * <p>So the dataset carries no opaque identifiers a human would have to
     * maintain, while the ids stay identical across runs — which matters
     * because the provider echoes them back and the mapper parses them.
     */
    public UUID criterionId(String code) {
        return UUID.nameUUIDFromBytes((id + ":" + code).getBytes(StandardCharsets.UTF_8));
    }

    /** The expected verdicts keyed by the id the provider will actually see. */
    public Map<UUID, Verdict> expectedById() {
        Map<UUID, Verdict> byId = new LinkedHashMap<>();
        expected.forEach((code, verdict) -> byId.put(criterionId(code), verdict));
        return byId;
    }

    public String codeOf(UUID criterionId) {
        return criteria.stream()
                .map(Criterion::code)
                .filter(code -> criterionId(code).equals(criterionId))
                .findFirst()
                .orElse("<unknown:" + criterionId + ">");
    }

    /**
     * The case as the engine would receive it.
     *
     * <p>The three attempt identifiers are null: a golden case is a dry run with
     * no interview and no stored answer, which is exactly the shape
     * {@link EvaluationRequest} already supports.
     */
    public EvaluationRequest toRequest() {
        List<EvaluationRequest.RubricCriterion> rubric = criteria.stream()
                .map(c -> new EvaluationRequest.RubricCriterion(
                        criterionId(c.code()), c.code(), c.code(),
                        c.expectation(), c.weightBp(), c.tier()))
                .toList();

        return new EvaluationRequest(
                null, null, null,
                // Pinned version identity, derived like the criterion ids so a
                // case is fully reproducible from its file alone.
                UUID.nameUUIDFromBytes(("version:" + id).getBytes(StandardCharsets.UTF_8)),
                1,
                new EvaluationRequest.QuestionSnapshot(
                        UUID.nameUUIDFromBytes(("skill:" + skill).getBytes(StandardCharsets.UTF_8)),
                        question.promptText(), null, question.referenceAnswer()),
                rubric,
                new EvaluationRequest.CandidateAnswer(answer, "TEXT"));
    }
}
