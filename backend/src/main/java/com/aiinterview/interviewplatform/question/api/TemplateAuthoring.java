package com.aiinterview.interviewplatform.question.api;

import java.util.List;
import java.util.UUID;

/**
 * Authoring side of interview templates.
 *
 * <p>Separate from {@link TemplateCatalog} for the same reason
 * {@link QuestionAuthoring} is separate from {@link QuestionCatalog}: the
 * interview engine consumes templates and must never acquire the ability to
 * change one.
 */
public interface TemplateAuthoring {

    DraftTemplate createTemplate(CreateTemplateCommand command);

    DraftTemplate updateDraft(UpdateTemplateDraftCommand command);

    /**
     * Replaces the skill weighting.
     *
     * <p>Whole-set, because the weights must total 10000 and no intermediate
     * state does.
     */
    DraftTemplate replaceSkills(ReplaceTemplateSkillsCommand command);

    /**
     * Replaces the question plan.
     *
     * <p>Also whole-set: slot positions must stay contiguous from 1, and
     * {@code uq_tqs_position} would reject the transient collision that any
     * incremental insert or reorder would produce.
     */
    DraftTemplate replaceSlots(ReplaceTemplateSlotsCommand command);

    /**
     * Validates and freezes the template.
     *
     * <p>Checks that the question bank can actually satisfy the plan. A
     * template that publishes but cannot be started is worse than one that
     * refuses to publish, because the failure surfaces to a candidate instead
     * of to its author.
     */
    PublishedTemplate publish(PublishTemplateCommand command);

    /** Opens the next draft version of a published template. */
    DraftTemplate createNextVersion(String templateKey, UUID authorId);

    // ------------------------------------------------------------ commands

    record CreateTemplateCommand(String templateKey, String title, String summary,
                                 String description, String instructions, String level,
                                 Integer coreQuestionCount, Integer maxFollowUpsTotal,
                                 Integer maxFollowUpsPerParent, Integer targetDurationMin,
                                 Integer hardDurationMin, UUID authorId) {

        public CreateTemplateCommand {
            if (templateKey == null || templateKey.isBlank()) {
                throw new IllegalArgumentException("templateKey is required");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("title is required");
            }
        }
    }

    record UpdateTemplateDraftCommand(UUID templateId, String title, String summary,
                                      String description, String instructions, String level,
                                      Integer coreQuestionCount, Integer maxFollowUpsTotal,
                                      Integer maxFollowUpsPerParent, Integer targetDurationMin,
                                      Integer hardDurationMin, UUID authorId) {
    }

    record ReplaceTemplateSkillsCommand(UUID templateId, List<SkillWeight> skills,
                                        UUID authorId) {

        public ReplaceTemplateSkillsCommand {
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }

    record SkillWeight(UUID skillId, int weightBp) {
    }

    /** Slots are given in order; positions are assigned from their sequence. */
    record ReplaceTemplateSlotsCommand(UUID templateId, List<SlotDraft> slots, UUID authorId) {

        public ReplaceTemplateSlotsCommand {
            slots = slots == null ? List.of() : List.copyOf(slots);
        }
    }

    /**
     * One slot, either pinned or pooled.
     *
     * @param questionId set to pin an exact question family
     * @param skillId    set to draw from a skill pool; exactly one of the two
     */
    record SlotDraft(UUID questionId, UUID skillId, String difficulty, String questionType,
                     int weightBp) {

        public boolean isPinned() {
            return questionId != null;
        }
    }

    record PublishTemplateCommand(UUID templateId, UUID authorId) {
    }

    // ------------------------------------------------------------- results

    record DraftTemplate(UUID templateId, String templateKey, int version, String status,
                         List<SkillWeight> skills,
                         List<TemplateCatalog.QuestionSlotView> slots) {
    }

    record PublishedTemplate(UUID templateId, String templateKey, int version) {
    }
}
