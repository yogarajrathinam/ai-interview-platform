package com.aiinterview.interviewplatform.question.application;

import com.aiinterview.interviewplatform.question.api.QuestionCatalog;
import com.aiinterview.interviewplatform.question.domain.QuestionVersionEntity;
import com.aiinterview.interviewplatform.question.domain.RubricCriterionEntity;
import com.aiinterview.interviewplatform.question.infrastructure.QuestionVersionRepository;
import com.aiinterview.interviewplatform.question.infrastructure.RubricCriterionRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps catalogue entities to the published views.
 *
 * <p>Thin and read-only. Its one real job is making sure entities stop here:
 * nothing outside the question module ever holds a
 * {@link QuestionVersionEntity}.
 */
@Service
public class DefaultQuestionCatalog implements QuestionCatalog {

    private final QuestionVersionRepository versions;
    private final RubricCriterionRepository criteria;

    public DefaultQuestionCatalog(QuestionVersionRepository versions,
                                  RubricCriterionRepository criteria) {
        this.versions = versions;
        this.criteria = criteria;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QuestionVersionView> findVersion(UUID questionVersionId) {
        return versions.findById(questionVersionId).map(DefaultQuestionCatalog::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RubricCriterionView> findRubric(UUID questionVersionId) {
        return criteria.findByQuestionVersionIdOrderBySortOrderAscIdAsc(questionVersionId)
                .stream()
                .map(DefaultQuestionCatalog::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QuestionVersionView> findPublishedVersionOfQuestion(UUID questionId) {
        return versions
                .findByQuestionIdAndStatus(questionId, QuestionVersionEntity.Status.PUBLISHED)
                .map(DefaultQuestionCatalog::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuestionVersionView> findPublishedVersionsForPool(UUID skillId, String difficulty,
                                                                  String questionType) {
        return versions.findForPool(
                        QuestionVersionEntity.Status.PUBLISHED,
                        skillId,
                        parse(QuestionVersionEntity.Difficulty.class, difficulty),
                        parse(QuestionVersionEntity.QuestionType.class, questionType))
                .stream()
                .map(DefaultQuestionCatalog::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findQuestionIdsForVersions(Collection<UUID> questionVersionIds) {
        if (questionVersionIds == null || questionVersionIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(versions.findQuestionIdsByIdIn(questionVersionIds));
    }

    /**
     * A null narrowing means "any". An unrecognised value is treated the same
     * way rather than throwing: a slot asking for a difficulty that no longer
     * exists should widen the pool, not fail the interview.
     */
    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static QuestionVersionView toView(QuestionVersionEntity entity) {
        return new QuestionVersionView(
                entity.getId(),
                entity.getQuestionId(),
                entity.getVersion(),
                entity.getSkillId(),
                entity.getQuestionType() == null ? null : entity.getQuestionType().name(),
                entity.getDifficulty() == null ? null : entity.getDifficulty().name(),
                entity.getPromptText(),
                entity.getContextText(),
                entity.getReferenceAnswer(),
                entity.getStatus() == null ? null : entity.getStatus().name());
    }

    private static RubricCriterionView toView(RubricCriterionEntity entity) {
        return new RubricCriterionView(
                entity.getId(),
                entity.getCode(),
                entity.getLabel(),
                entity.getExpectation(),
                entity.getWeightBp(),
                entity.getTier() == null ? null : entity.getTier().name(),
                entity.getFollowUpPrompt(),
                entity.getSortOrder() == null ? 0 : entity.getSortOrder());
    }
}
