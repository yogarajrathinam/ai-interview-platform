package com.aiinterview.interviewplatform.question.infrastructure;

import com.aiinterview.interviewplatform.question.domain.QuestionVersionEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Read access to question content, pinned and publishable. */
public interface QuestionVersionRepository extends JpaRepository<QuestionVersionEntity, UUID> {

    /**
     * The live version of a family. Unique by {@code uq_qv_one_published}, so
     * this can never return more than one row.
     */
    Optional<QuestionVersionEntity> findByQuestionIdAndStatus(
            UUID questionId, QuestionVersionEntity.Status status);

    /**
     * Eligible versions for a pooled slot.
     *
     * <p>Null difficulty or type means "any", so one query serves every slot
     * shape. Ordered by id purely so the result is stable — the actual choice
     * is made by the caller's selection policy, and a stable input is what lets
     * that policy be deterministic.
     *
     * <p>Served by the partial index {@code ix_qv_pool}, which covers only
     * published rows.
     */
    @Query("""
            select v from QuestionVersionEntity v
             where v.status = :status
               and v.skillId = :skillId
               and (:difficulty is null or v.difficulty = :difficulty)
               and (:questionType is null or v.questionType = :questionType)
             order by v.id
            """)
    List<QuestionVersionEntity> findForPool(
            @Param("status") QuestionVersionEntity.Status status,
            @Param("skillId") UUID skillId,
            @Param("difficulty") QuestionVersionEntity.Difficulty difficulty,
            @Param("questionType") QuestionVersionEntity.QuestionType questionType);

    /** Version id to family id, in one round trip rather than N. */
    @Query("select v.questionId from QuestionVersionEntity v where v.id in :ids")
    List<UUID> findQuestionIdsByIdIn(@Param("ids") Collection<UUID> ids);
}
