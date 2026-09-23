package com.aiinterview.interviewplatform.question.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The question module's published surface for interview templates.
 *
 * <p>Added in M3. The interview module needs a template's configuration and
 * question plan to start an attempt, and must not reach into
 * {@code interview_templates} or {@code template_question_slots} to get them.
 *
 * <p>Everything here is addressed by <strong>template version id</strong> — the
 * row an attempt pins — not by {@code template_key}. Resolving a family to its
 * current published version happens once, at start, and is the caller's
 * explicit decision rather than something that quietly happens on every read.
 */
public interface TemplateCatalog {

    /** The pinned template version, whatever its status is now. */
    Optional<TemplateView> findTemplate(UUID templateId);

    /**
     * Resolves a family to the version currently published.
     *
     * <p>For <em>starting</em> an attempt only. Once started, the attempt holds
     * a version id and must be read with {@link #findTemplate}.
     */
    Optional<TemplateView> findPublishedTemplateByKey(String templateKey);

    /** The ordered question plan, by slot position. */
    List<QuestionSlotView> findSlots(UUID templateId);

    /**
     * The template's skill weights, with the skill named for display.
     *
     * <p>Added in M5A. Aggregating an interview score needs the weight each
     * skill carries in the pinned template version — deriving it from the
     * questions that happened to be planned would silently re-weight the
     * interview whenever a pooled slot picked differently.
     */
    List<TemplateSkillView> findSkills(UUID templateId);

    /**
     * Template configuration and identity.
     *
     * @param coreQuestionCount     how many core turns the plan must produce
     * @param maxFollowUpsTotal     ceiling across the whole attempt
     * @param maxFollowUpsPerParent ceiling per answered core turn
     * @param hardDurationMin       wall-clock ceiling from start
     */
    record TemplateView(UUID id, String templateKey, int version, String title, String level,
                        String status, int coreQuestionCount, int maxFollowUpsTotal,
                        int maxFollowUpsPerParent, int targetDurationMin, int hardDurationMin,
                        String instructions) {

        public boolean isPublished() {
            return "PUBLISHED".equals(status);
        }
    }

    /**
     * One slot in the plan.
     *
     * <p>A slot is either <em>pinned</em> to a question family or <em>pooled</em>
     * over a skill; {@code ck_tqs_mode} guarantees exactly one is set, so the
     * mode is derived rather than stored and cannot disagree with the data.
     */
    record QuestionSlotView(UUID id, int position, UUID questionId, UUID skillId,
                            String difficulty, String questionType, int weightBp) {

        public boolean isPinned() {
            return questionId != null;
        }
    }

    /** One skill's share of a template, named so a result can be read by a human. */
    record TemplateSkillView(UUID skillId, String code, String name, int weightBp) {
    }
}
