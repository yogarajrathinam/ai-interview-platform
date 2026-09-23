package com.aiinterview.interviewplatform.evaluation.infrastructure;

import com.aiinterview.interviewplatform.evaluation.domain.EvaluationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvaluationRepository extends JpaRepository<EvaluationEntity, UUID> {

    /**
     * The evaluation a candidate would see. Guaranteed unique by the partial
     * index {@code uq_evaluations_one_current}.
     */
    Optional<EvaluationEntity> findByAnswerIdAndCurrentTrue(UUID answerId);

    /** Full history for an answer, newest first — the admin audit view. */
    List<EvaluationEntity> findByAnswerIdOrderByCreatedAtDesc(UUID answerId);

    long countByAnswerId(UUID answerId);

    /**
     * Clears the current marker before a re-evaluation inserts its replacement.
     *
     * <p>Hand-written rather than loaded-and-mutated for a concrete reason:
     * Hibernate's dirty checking emits an UPDATE covering <em>every</em> mapped
     * column, and {@code trg_eval_20_append_only} rejects any statement that
     * changes a column other than {@code is_current}. A targeted JPQL update
     * touches exactly the one column the trigger permits.
     *
     * @return rows superseded — 0 or 1, since the partial index allows no more
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EvaluationEntity e set e.current = false "
            + "where e.answerId = :answerId and e.current = true")
    int supersedeCurrent(@Param("answerId") UUID answerId);
}
