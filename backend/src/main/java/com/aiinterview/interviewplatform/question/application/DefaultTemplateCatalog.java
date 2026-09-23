package com.aiinterview.interviewplatform.question.application;

import com.aiinterview.interviewplatform.question.api.TemplateCatalog;
import com.aiinterview.interviewplatform.question.domain.InterviewTemplateEntity;
import com.aiinterview.interviewplatform.question.domain.TemplateQuestionSlotEntity;
import com.aiinterview.interviewplatform.question.domain.SkillEntity;
import com.aiinterview.interviewplatform.question.infrastructure.InterviewTemplateRepository;
import com.aiinterview.interviewplatform.question.infrastructure.SkillRepository;
import com.aiinterview.interviewplatform.question.infrastructure.TemplateQuestionSlotRepository;
import com.aiinterview.interviewplatform.question.infrastructure.TemplateSkillRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps template entities to the published views.
 *
 * <p>Read-only. Its real job is making sure entities stop here: nothing outside
 * the question module ever holds an {@link InterviewTemplateEntity}.
 */
@Service
public class DefaultTemplateCatalog implements TemplateCatalog {

    private final InterviewTemplateRepository templates;
    private final TemplateQuestionSlotRepository slots;
    private final TemplateSkillRepository templateSkills;
    private final SkillRepository skills;

    public DefaultTemplateCatalog(InterviewTemplateRepository templates,
                                  TemplateQuestionSlotRepository slots,
                                  TemplateSkillRepository templateSkills,
                                  SkillRepository skills) {
        this.templates = templates;
        this.slots = slots;
        this.templateSkills = templateSkills;
        this.skills = skills;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TemplateView> findTemplate(UUID templateId) {
        return templates.findById(templateId).map(DefaultTemplateCatalog::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TemplateView> findPublishedTemplateByKey(String templateKey) {
        return templates
                .findByTemplateKeyAndStatus(templateKey, InterviewTemplateEntity.Status.PUBLISHED)
                .map(DefaultTemplateCatalog::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuestionSlotView> findSlots(UUID templateId) {
        return slots.findByTemplateIdOrderByPositionAsc(templateId).stream()
                .map(DefaultTemplateCatalog::toView)
                .toList();
    }

    /**
     * Skill weights with their names resolved in one extra query rather than
     * one per skill. A template carries a handful of skills, so the join is
     * done here instead of pushing a projection into the repository.
     */
    @Override
    @Transactional(readOnly = true)
    public List<TemplateSkillView> findSkills(UUID templateId) {
        List<com.aiinterview.interviewplatform.question.domain.TemplateSkillEntity> weights =
                templateSkills.findByTemplateId(templateId);
        if (weights.isEmpty()) {
            return List.of();
        }

        Map<UUID, SkillEntity> byId = skills
                .findByIdIn(weights.stream()
                        .map(com.aiinterview.interviewplatform.question.domain
                                .TemplateSkillEntity::getSkillId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(SkillEntity::getId, Function.identity()));

        return weights.stream()
                .map(weight -> {
                    SkillEntity skill = byId.get(weight.getSkillId());
                    return new TemplateSkillView(
                            weight.getSkillId(),
                            skill == null ? null : skill.getCode(),
                            skill == null ? null : skill.getName(),
                            weight.getWeightBp());
                })
                .toList();
    }

    private static TemplateView toView(InterviewTemplateEntity entity) {
        return new TemplateView(
                entity.getId(),
                entity.getTemplateKey(),
                entity.getVersion(),
                entity.getTitle(),
                entity.getLevel() == null ? null : entity.getLevel().name(),
                entity.getStatus() == null ? null : entity.getStatus().name(),
                entity.getCoreQuestionCount(),
                entity.getMaxFollowUpsTotal(),
                entity.getMaxFollowUpsPerParent(),
                entity.getTargetDurationMin(),
                entity.getHardDurationMin(),
                entity.getInstructions());
    }

    private static QuestionSlotView toView(TemplateQuestionSlotEntity entity) {
        return new QuestionSlotView(
                entity.getId(),
                entity.getPosition(),
                entity.getQuestionId(),
                entity.getSkillId(),
                entity.getDifficulty() == null ? null : entity.getDifficulty().name(),
                entity.getQuestionType() == null ? null : entity.getQuestionType().name(),
                entity.getWeightBp());
    }
}
