package com.aiinterview.interviewplatform.question.application;

import com.aiinterview.interviewplatform.question.api.QuestionCatalog;
import com.aiinterview.interviewplatform.question.api.TemplateAuthoring;
import com.aiinterview.interviewplatform.question.api.TemplateCatalog;
import com.aiinterview.interviewplatform.question.domain.InterviewTemplateEntity;
import com.aiinterview.interviewplatform.question.domain.QuestionVersionEntity;
import com.aiinterview.interviewplatform.question.domain.TemplateQuestionSlotEntity;
import com.aiinterview.interviewplatform.question.domain.TemplateSkillEntity;
import com.aiinterview.interviewplatform.question.infrastructure.InterviewTemplateRepository;
import com.aiinterview.interviewplatform.question.infrastructure.TemplateQuestionSlotRepository;
import com.aiinterview.interviewplatform.question.infrastructure.TemplateSkillRepository;
import com.aiinterview.interviewplatform.shared.error.ApplicationException;
import com.aiinterview.interviewplatform.shared.error.ErrorCode;
import com.aiinterview.interviewplatform.shared.id.IdGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoring for interview templates.
 *
 * <p>The publication check here does something the database triggers cannot:
 * it asks whether the <em>question bank can actually satisfy the plan</em>. A
 * template that publishes but cannot be started is worse than one that refuses
 * to publish, because the failure surfaces to a candidate rather than to the
 * person who can fix it.
 */
@Service
public class DefaultTemplateAuthoring implements TemplateAuthoring {

    private static final Logger log = LoggerFactory.getLogger(DefaultTemplateAuthoring.class);

    private static final int FULL_WEIGHT_BP = 10_000;

    /**
     * How many eligible questions a pooled slot wants beyond the bare minimum.
     *
     * <p>Exactly enough questions to fill the plan means every candidate gets an
     * identical interview, which defeats the point of a pool. Headroom is a
     * warning rather than a hard failure — a small bank should still be usable.
     */
    private static final int POOL_HEADROOM_FACTOR = 2;

    private final InterviewTemplateRepository templates;
    private final TemplateSkillRepository templateSkills;
    private final TemplateQuestionSlotRepository slots;
    private final QuestionCatalog questionCatalog;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public DefaultTemplateAuthoring(InterviewTemplateRepository templates,
                                    TemplateSkillRepository templateSkills,
                                    TemplateQuestionSlotRepository slots,
                                    QuestionCatalog questionCatalog,
                                    IdGenerator idGenerator, Clock clock) {
        this.templates = templates;
        this.templateSkills = templateSkills;
        this.slots = slots;
        this.questionCatalog = questionCatalog;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DraftTemplate createTemplate(CreateTemplateCommand command) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        InterviewTemplateEntity template = templates.save(InterviewTemplateEntity.draft(
                idGenerator.newId(), command.templateKey(), 1, command.title(),
                command.summary(), command.description(), command.instructions(),
                parseLevel(command.level()),
                shortOr(command.coreQuestionCount(), 8),
                shortOr(command.maxFollowUpsTotal(), 4),
                shortOr(command.maxFollowUpsPerParent(), 2),
                shortOr(command.targetDurationMin(), 18),
                shortOr(command.hardDurationMin(), 45),
                command.authorId(), now));

        log.info("Template draft created: key={} templateId={} author={}",
                command.templateKey(), template.getId(), command.authorId());
        return toDraft(template);
    }

    @Override
    @Transactional
    public DraftTemplate updateDraft(UpdateTemplateDraftCommand command) {
        InterviewTemplateEntity template = requireTemplate(command.templateId());
        requireDraft(template);

        template.updateDraft(command.title(), command.summary(), command.description(),
                command.instructions(), parseLevelOrNull(command.level()),
                boxShort(command.coreQuestionCount()), boxShort(command.maxFollowUpsTotal()),
                boxShort(command.maxFollowUpsPerParent()), boxShort(command.targetDurationMin()),
                boxShort(command.hardDurationMin()), OffsetDateTime.now(clock));
        templates.save(template);
        return toDraft(template);
    }

    @Override
    @Transactional
    public DraftTemplate replaceSkills(ReplaceTemplateSkillsCommand command) {
        InterviewTemplateEntity template = requireTemplate(command.templateId());
        requireDraft(template);

        List<String> problems = new ArrayList<>();
        int total = 0;
        Set<UUID> seen = new HashSet<>();
        for (SkillWeight weight : command.skills()) {
            if (!seen.add(weight.skillId())) {
                problems.add("Skill %s is weighted twice.".formatted(weight.skillId()));
            }
            if (weight.weightBp() <= 0) {
                problems.add("Skill %s needs a positive weight.".formatted(weight.skillId()));
            }
            total += Math.max(0, weight.weightBp());
        }
        if (!command.skills().isEmpty() && total != FULL_WEIGHT_BP) {
            problems.add("Skill weights must total %d basis points (got %d)."
                    .formatted(FULL_WEIGHT_BP, total));
        }
        if (!problems.isEmpty()) {
            throw ApplicationException.validation(String.join(" ", problems));
        }

        templateSkills.deleteAll(templateSkills.findByTemplateId(template.getId()));
        templateSkills.flush();
        templateSkills.saveAll(command.skills().stream()
                .map(w -> TemplateSkillEntity.create(template.getId(), w.skillId(), w.weightBp()))
                .toList());

        return toDraft(template);
    }

    @Override
    @Transactional
    public DraftTemplate replaceSlots(ReplaceTemplateSlotsCommand command) {
        InterviewTemplateEntity template = requireTemplate(command.templateId());
        requireDraft(template);

        List<String> problems = new ArrayList<>();
        for (SlotDraft slot : command.slots()) {
            boolean pinned = slot.questionId() != null;
            boolean pooled = slot.skillId() != null;
            if (pinned == pooled) {
                // Mirrors ck_tqs_mode: a slot names a question or a pool, never
                // both and never neither.
                problems.add("Each slot must set exactly one of questionId or skillId.");
            }
            if (slot.weightBp() <= 0) {
                problems.add("Each slot needs a positive weight.");
            }
        }
        if (!problems.isEmpty()) {
            throw ApplicationException.validation(String.join(" ", problems));
        }

        // Deleted and reinserted in one pass, not reordered in place:
        // uq_tqs_position would reject any intermediate state where two slots
        // briefly share a position.
        slots.deleteAll(slots.findByTemplateIdOrderByPositionAsc(template.getId()));
        slots.flush();

        short position = 1;
        List<TemplateQuestionSlotEntity> replacements = new ArrayList<>();
        for (SlotDraft slot : command.slots()) {
            replacements.add(slot.isPinned()
                    ? TemplateQuestionSlotEntity.pinned(idGenerator.newId(), template.getId(),
                            position++, slot.questionId(), slot.weightBp())
                    : TemplateQuestionSlotEntity.pooled(idGenerator.newId(), template.getId(),
                            position++, slot.skillId(), parseDifficulty(slot.difficulty()),
                            parseType(slot.questionType()), slot.weightBp()));
        }
        slots.saveAll(replacements);

        return toDraft(template);
    }

    @Override
    @Transactional
    public PublishedTemplate publish(PublishTemplateCommand command) {
        InterviewTemplateEntity template = requireTemplate(command.templateId());
        requireDraft(template);

        List<String> problems = validateForPublication(template);
        if (!problems.isEmpty()) {
            log.info("Template publish rejected: templateId={} problems={}",
                    template.getId(), problems.size());
            throw ApplicationException.validation(String.join(" ", problems));
        }

        template.publish(command.authorId(), OffsetDateTime.now(clock));
        templates.save(template);

        log.info("Template published: key={} templateId={} version={} author={}",
                template.getTemplateKey(), template.getId(), template.getVersion(),
                command.authorId());
        return new PublishedTemplate(template.getId(), template.getTemplateKey(),
                template.getVersion());
    }

    @Override
    @Transactional
    public DraftTemplate createNextVersion(String templateKey, UUID authorId) {
        InterviewTemplateEntity published = templates
                .findByTemplateKeyAndStatus(templateKey, InterviewTemplateEntity.Status.PUBLISHED)
                .orElseThrow(() -> ApplicationException.conflict(ErrorCode.CONFLICT,
                        "Template '%s' has no published version.".formatted(templateKey)));

        OffsetDateTime now = OffsetDateTime.now(clock);
        InterviewTemplateEntity next = templates.save(InterviewTemplateEntity.draft(
                idGenerator.newId(), templateKey, published.getVersion() + 1,
                published.getTitle(), published.getSummary(), published.getDescription(),
                published.getInstructions(), published.getLevel(),
                published.getCoreQuestionCount(), published.getMaxFollowUpsTotal(),
                published.getMaxFollowUpsPerParent(), published.getTargetDurationMin(),
                published.getHardDurationMin(), authorId, now));

        // Composition is copied so the published version keeps its own rows and
        // every running attempt sees an unchanged plan.
        templateSkills.saveAll(templateSkills.findByTemplateId(published.getId()).stream()
                .map(s -> TemplateSkillEntity.create(next.getId(), s.getSkillId(), s.getWeightBp()))
                .toList());

        List<TemplateQuestionSlotEntity> copies = new ArrayList<>();
        for (TemplateQuestionSlotEntity source
                : slots.findByTemplateIdOrderByPositionAsc(published.getId())) {
            copies.add(source.isPinned()
                    ? TemplateQuestionSlotEntity.pinned(idGenerator.newId(), next.getId(),
                            source.getPosition(), source.getQuestionId(), source.getWeightBp())
                    : TemplateQuestionSlotEntity.pooled(idGenerator.newId(), next.getId(),
                            source.getPosition(), source.getSkillId(), source.getDifficulty(),
                            source.getQuestionType(), source.getWeightBp()));
        }
        slots.saveAll(copies);

        return toDraft(next);
    }

    // ---------------------------------------------------------- validation

    private List<String> validateForPublication(InterviewTemplateEntity template) {
        List<String> problems = new ArrayList<>();

        List<TemplateSkillEntity> skills = templateSkills.findByTemplateId(template.getId());
        int skillTotal = skills.stream().mapToInt(TemplateSkillEntity::getWeightBp).sum();
        if (skills.isEmpty()) {
            problems.add("A published template needs at least one weighted skill.");
        } else if (skillTotal != FULL_WEIGHT_BP) {
            problems.add("Skill weights must total %d basis points (got %d)."
                    .formatted(FULL_WEIGHT_BP, skillTotal));
        }

        List<TemplateQuestionSlotEntity> plan =
                slots.findByTemplateIdOrderByPositionAsc(template.getId());
        if (plan.size() != template.getCoreQuestionCount()) {
            problems.add("The template declares %d core questions but has %d slots."
                    .formatted(template.getCoreQuestionCount(), plan.size()));
        }

        Set<UUID> weightedSkills = new HashSet<>();
        skills.forEach(s -> weightedSkills.add(s.getSkillId()));

        Map<UUID, Integer> pooledDemand = new HashMap<>();
        for (TemplateQuestionSlotEntity slot : plan) {
            if (slot.isPinned()) {
                // A pinned slot is unusable without a published version behind it.
                if (questionCatalog.findPublishedVersionOfQuestion(slot.getQuestionId()).isEmpty()) {
                    problems.add("Slot %d pins a question with no published version."
                            .formatted(slot.getPosition()));
                }
            } else {
                if (!weightedSkills.contains(slot.getSkillId())) {
                    problems.add("Slot %d draws on a skill the template does not weight."
                            .formatted(slot.getPosition()));
                }
                pooledDemand.merge(slot.getSkillId(), 1, Integer::sum);
            }
        }

        // The check the database cannot make: can the bank actually fill the plan?
        for (Map.Entry<UUID, Integer> demand : pooledDemand.entrySet()) {
            int available = questionCatalog
                    .findPublishedVersionsForPool(demand.getKey(), null, null).size();
            if (available < demand.getValue()) {
                problems.add(("Skill %s needs %d pooled question(s) but only %d are published; "
                        + "an interview could not be planned.")
                        .formatted(demand.getKey(), demand.getValue(), available));
            } else if (available < demand.getValue() * POOL_HEADROOM_FACTOR) {
                log.warn("Template {} has a thin pool for skill {}: {} published for {} slot(s); "
                                + "candidates will see near-identical interviews",
                        template.getTemplateKey(), demand.getKey(), available, demand.getValue());
            }
        }
        return problems;
    }

    // ------------------------------------------------------------- helpers

    private void requireDraft(InterviewTemplateEntity template) {
        if (!template.isDraft()) {
            throw ApplicationException.conflict(ErrorCode.VERSION_IMMUTABLE,
                    ("Template version %d is %s and cannot be edited. "
                            + "Create version %d instead.")
                            .formatted(template.getVersion(), template.getStatus(),
                                    template.getVersion() + 1));
        }
    }

    private InterviewTemplateEntity requireTemplate(UUID id) {
        return templates.findById(id).orElseThrow(() -> ApplicationException.notFound(
                "Template %s does not exist.".formatted(id)));
    }

    private DraftTemplate toDraft(InterviewTemplateEntity template) {
        List<SkillWeight> skills = templateSkills.findByTemplateId(template.getId()).stream()
                .map(s -> new SkillWeight(s.getSkillId(), s.getWeightBp()))
                .toList();

        List<TemplateCatalog.QuestionSlotView> planned =
                slots.findByTemplateIdOrderByPositionAsc(template.getId()).stream()
                        .map(s -> new TemplateCatalog.QuestionSlotView(s.getId(), s.getPosition(),
                                s.getQuestionId(), s.getSkillId(),
                                s.getDifficulty() == null ? null : s.getDifficulty().name(),
                                s.getQuestionType() == null ? null : s.getQuestionType().name(),
                                s.getWeightBp()))
                        .toList();

        return new DraftTemplate(template.getId(), template.getTemplateKey(),
                template.getVersion(), template.getStatus().name(), skills, planned);
    }

    private static short shortOr(Integer value, int fallback) {
        return (short) (value == null ? fallback : value);
    }

    private static Short boxShort(Integer value) {
        return value == null ? null : value.shortValue();
    }

    private static InterviewTemplateEntity.Level parseLevel(String value) {
        return value == null ? InterviewTemplateEntity.Level.MID
                : InterviewTemplateEntity.Level.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static InterviewTemplateEntity.Level parseLevelOrNull(String value) {
        return value == null ? null
                : InterviewTemplateEntity.Level.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static QuestionVersionEntity.Difficulty parseDifficulty(String value) {
        return value == null ? null
                : QuestionVersionEntity.Difficulty.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static QuestionVersionEntity.QuestionType parseType(String value) {
        return value == null ? null
                : QuestionVersionEntity.QuestionType.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
