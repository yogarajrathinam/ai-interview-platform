package com.aiinterview.interviewplatform.evaluation.infrastructure;

import com.aiinterview.interviewplatform.evaluation.domain.EvaluationCriterionResultEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Criterion results are immutable once written
 * ({@code trg_ecr_20_immutable}), so this repository only ever inserts and
 * reads. There is no update path by design.
 */
public interface EvaluationCriterionResultRepository
        extends JpaRepository<EvaluationCriterionResultEntity, UUID> {

    List<EvaluationCriterionResultEntity> findByEvaluationId(UUID evaluationId);

    long countByEvaluationId(UUID evaluationId);
}
