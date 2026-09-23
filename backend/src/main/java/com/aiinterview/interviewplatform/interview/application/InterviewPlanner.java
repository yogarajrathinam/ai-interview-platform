package com.aiinterview.interviewplatform.interview.application;

import com.aiinterview.interviewplatform.interview.domain.InterviewQuestionEntity;
import com.aiinterview.interviewplatform.interview.infrastructure.InterviewQuestionRepository;
import com.aiinterview.interviewplatform.question.api.QuestionCatalog;
import com.aiinterview.interviewplatform.question.api.QuestionCatalog.QuestionVersionView;
import com.aiinterview.interviewplatform.question.api.TemplateCatalog;
import com.aiinterview.interviewplatform.question.api.TemplateCatalog.QuestionSlotView;
import com.aiinterview.interviewplatform.shared.error.ApplicationException;
import com.aiinterview.interviewplatform.shared.id.IdGenerator;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a template's slots into the concrete turns a candidate will answer.
 *
 * <p>Runs once, at start. Every turn it produces pins a specific published
 * question version and snapshots that version's skill alongside the slot's
 * weight, which is what lets a score be recomputed years later from
 * attempt-scoped rows alone.
 *
 * <p>Planning is all-or-nothing. A slot that cannot be filled fails the start
 * rather than producing a shorter interview: a candidate who answers six
 * questions when the template promised eight has a score that means something
 * different from everyone else's, and silently allowing that would undermine
 * the comparability the whole product rests on.
 */
@Component
public class InterviewPlanner {

    private final TemplateCatalog templateCatalog;
    private final QuestionCatalog questionCatalog;
    private final QuestionSelector selector;
    private final InterviewQuestionRepository turns;
    private final IdGenerator idGenerator;

    public InterviewPlanner(TemplateCatalog templateCatalog, QuestionCatalog questionCatalog,
                            QuestionSelector selector, InterviewQuestionRepository turns,
                            IdGenerator idGenerator) {
        this.templateCatalog = templateCatalog;
        this.questionCatalog = questionCatalog;
        this.selector = selector;
        this.turns = turns;
        this.idGenerator = idGenerator;
    }

    /**
     * Resolves the whole plan.
     *
     * @param interviewId doubles as the selection seed, so replanning the same
     *                    attempt would produce the same questions
     */
    public List<InterviewQuestionEntity> planFor(UUID interviewId, UUID candidateUserId,
                                                 TemplateCatalog.TemplateView template,
                                                 OffsetDateTime now) {

        List<QuestionSlotView> slots = templateCatalog.findSlots(template.id());
        if (slots.isEmpty()) {
            throw ApplicationException.validation(
                    "Template %s has no question slots and cannot be started."
                            .formatted(template.templateKey()));
        }
        if (slots.size() != template.coreQuestionCount()) {
            // The publish gate already checks this; reaching here means the
            // template was modified outside it, and serving a mismatched plan
            // would be worse than refusing.
            throw ApplicationException.validation(
                    "Template %s declares %d core questions but has %d slots."
                            .formatted(template.templateKey(), template.coreQuestionCount(),
                                    slots.size()));
        }

        Set<UUID> previouslySeen = previouslySeenQuestionFamilies(candidateUserId);
        Set<UUID> chosen = new HashSet<>();
        List<InterviewQuestionEntity> plan = new ArrayList<>(slots.size());

        for (QuestionSlotView slot : slots) {
            Optional<QuestionVersionView> selected =
                    selector.selectFor(slot, chosen, previouslySeen, interviewId);

            QuestionVersionView version = selected.orElseThrow(() -> ApplicationException.validation(
                    ("No published question is available for slot %d of template %s. "
                            + "The question bank is too small for this template.")
                            .formatted(slot.position(), template.templateKey())));

            chosen.add(version.questionId());
            plan.add(InterviewQuestionEntity.core(
                    idGenerator.newId(),
                    interviewId,
                    slot.position(),
                    version.id(),
                    // The version's skill, not the slot's: a pinned slot has no
                    // skill of its own, and the content is the authority anyway.
                    version.skillId(),
                    slot.weightBp(),
                    now));
        }
        return plan;
    }

    /**
     * Question families this candidate has already met, in any attempt.
     *
     * <p>Resolved to families rather than versions because a revised version of
     * a question they answered last week is still the same question.
     */
    private Set<UUID> previouslySeenQuestionFamilies(UUID candidateUserId) {
        List<UUID> servedVersions = turns.findQuestionVersionIdsServedTo(candidateUserId);
        return servedVersions.isEmpty()
                ? Set.of()
                : questionCatalog.findQuestionIdsForVersions(servedVersions);
    }
}
