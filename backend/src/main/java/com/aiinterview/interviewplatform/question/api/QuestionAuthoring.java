package com.aiinterview.interviewplatform.question.api;

import java.util.List;
import java.util.UUID;

/**
 * Authoring side of the question bank.
 *
 * <p>Deliberately separate from {@link QuestionCatalog}. The catalogue is a
 * consumer contract — the interview engine reads published content through it
 * and should never gain the ability to create or publish anything. Keeping
 * write operations out of it means a reader cannot accidentally become a
 * writer, and the two can evolve without dragging each other along.
 *
 * <p>Every command carries an {@code authorId}. There is no authenticated admin
 * identity yet (see the milestone documentation), so the caller supplies it and
 * it is recorded as provenance — honest attribution, not enforcement.
 */
public interface QuestionAuthoring {

    /**
     * Creates a question family and its first draft version.
     *
     * @throws com.aiinterview.interviewplatform.shared.error.ApplicationException
     *         if {@code questionKey} is already taken
     */
    DraftVersion createQuestion(CreateQuestionCommand command);

    /** Edits a draft. Refused with a clear error once the version is published. */
    DraftVersion updateDraft(UpdateQuestionDraftCommand command);

    /**
     * Replaces the whole rubric of a draft version.
     *
     * <p>Whole-set replacement rather than per-criterion CRUD: the weights must
     * sum to exactly 10000, so validating a complete set once is both simpler
     * and safer than validating after each of six row operations, each of which
     * would leave the rubric transiently invalid.
     */
    DraftVersion replaceRubric(ReplaceRubricCommand command);

    /**
     * Validates and freezes the version.
     *
     * <p>Fails with every problem at once rather than the first, because an
     * author fixing one issue only to be told about the next wastes a round
     * trip each time.
     */
    PublishedVersion publish(PublishQuestionCommand command);

    /**
     * Opens the next draft version of an already-published question.
     *
     * <p>This is how a published question is "edited": the existing version is
     * never touched, so every interview that pinned it keeps grading against
     * exactly the words its candidate saw.
     */
    DraftVersion createNextVersion(UUID questionId, UUID authorId);

    // ------------------------------------------------------------ commands

    record CreateQuestionCommand(String questionKey, UUID skillId, String questionType,
                                 String difficulty, String promptText, String contextText,
                                 String referenceAnswer, Integer expectedDurationSec,
                                 UUID authorId) {

        public CreateQuestionCommand {
            if (questionKey == null || questionKey.isBlank()) {
                throw new IllegalArgumentException("questionKey is required");
            }
            if (skillId == null) {
                throw new IllegalArgumentException("skillId is required");
            }
        }
    }

    record UpdateQuestionDraftCommand(UUID questionVersionId, UUID skillId, String questionType,
                                      String difficulty, String promptText, String contextText,
                                      String referenceAnswer, Integer expectedDurationSec,
                                      UUID authorId) {

        public UpdateQuestionDraftCommand {
            if (questionVersionId == null) {
                throw new IllegalArgumentException("questionVersionId is required");
            }
        }
    }

    record ReplaceRubricCommand(UUID questionVersionId, List<CriterionDraft> criteria,
                                UUID authorId) {

        public ReplaceRubricCommand {
            if (questionVersionId == null) {
                throw new IllegalArgumentException("questionVersionId is required");
            }
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
        }
    }

    /**
     * @param expectation the observable claim an answer either makes or does
     *                    not — never a topic label, which two graders would
     *                    read differently
     * @param followUpPrompt the curated probe asked when this criterion scores
     *                       weakly; without one, the criterion can never be
     *                       followed up, because a model may not author what a
     *                       candidate is shown
     */
    record CriterionDraft(String code, String label, String expectation, int weightBp,
                          String tier, String followUpPrompt) {
    }

    record PublishQuestionCommand(UUID questionVersionId, UUID authorId) {
    }

    // ------------------------------------------------------------- results

    record DraftVersion(UUID questionId, UUID questionVersionId, String questionKey,
                        int version, String status, List<QuestionCatalog.RubricCriterionView> rubric) {
    }

    record PublishedVersion(UUID questionId, UUID questionVersionId, String questionKey,
                            int version) {
    }
}
