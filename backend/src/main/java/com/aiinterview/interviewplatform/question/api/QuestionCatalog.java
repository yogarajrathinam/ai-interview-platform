package com.aiinterview.interviewplatform.question.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The question module's published surface.
 *
 * <p>Other modules read catalogue content only through here, and only as the
 * views below — never as JPA entities, and never by querying
 * {@code question_versions} or {@code rubric_criteria} directly. That keeps the
 * catalogue free to change shape without breaking the modules that consume it.
 *
 * <p>Every lookup is <strong>by version id</strong>, never by question family.
 * There is deliberately no "give me the current version of this question"
 * method on this interface: an attempt pins a version, and grading against
 * anything else would silently rewrite history.
 */
public interface QuestionCatalog {

    /** The pinned version's content, whatever its status is now. */
    Optional<QuestionVersionView> findVersion(UUID questionVersionId);

    /** The rubric belonging to that exact version, in author order. */
    List<RubricCriterionView> findRubric(UUID questionVersionId);

    // ------------------------------------------------------------- planning
    //
    // Added in M3. The methods above read a version an attempt has already
    // pinned; the ones below resolve which version to pin in the first place.
    // The distinction is the whole reproducibility story, so it is drawn
    // explicitly rather than left to a caller to remember: nothing in the
    // grading path may use these, and nothing in the planning path may assume
    // the answer stays true afterwards.

    /**
     * The version of a question family that is published right now.
     *
     * <p>For resolving a pinned slot at interview start only. Empty when the
     * family has no published version, in which case the slot cannot be served.
     */
    Optional<QuestionVersionView> findPublishedVersionOfQuestion(UUID questionId);

    /**
     * Published versions eligible for a pooled slot, ordered deterministically.
     *
     * <p>{@code difficulty} and {@code questionType} are optional narrowings; a
     * null means "any". Returns every eligible version rather than choosing
     * one, because which to choose is interview policy — exposure history,
     * within-plan uniqueness — and belongs to the module that knows the
     * candidate.
     */
    List<QuestionVersionView> findPublishedVersionsForPool(UUID skillId, String difficulty,
                                                           String questionType);

    /**
     * Maps version ids back to their question families.
     *
     * <p>Lets the interview module avoid re-serving a question a candidate has
     * already seen, which is a property of the <em>family</em>: a revised
     * version of a question they answered last week is still the same question.
     */
    Set<UUID> findQuestionIdsForVersions(Collection<UUID> questionVersionIds);

    /**
     * Question content as published.
     *
     * @param version         the family version number, recorded as the rubric version
     * @param referenceAnswer grounding for a grader; never shown to the candidate
     */
    record QuestionVersionView(UUID id, UUID questionId, int version, UUID skillId,
                               String questionType, String difficulty, String promptText,
                               String contextText, String referenceAnswer, String status) {

        /** True once the version can no longer change (trigger-enforced). */
        public boolean isImmutable() {
            return !"DRAFT".equals(status);
        }
    }

    /** One rubric criterion of that version. */
    record RubricCriterionView(UUID id, String code, String label, String expectation,
                               int weightBp, String tier, String followUpPrompt,
                               int sortOrder) {
    }
}
