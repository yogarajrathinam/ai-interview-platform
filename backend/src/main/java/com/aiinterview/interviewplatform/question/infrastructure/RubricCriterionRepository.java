package com.aiinterview.interviewplatform.question.infrastructure;

import com.aiinterview.interviewplatform.question.domain.RubricCriterionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read access to the rubric of a pinned question version. */
public interface RubricCriterionRepository extends JpaRepository<RubricCriterionEntity, UUID> {

    /**
     * Ordered so a report renders criteria the way their author arranged them.
     * The id tiebreak keeps the order stable when two criteria share a
     * {@code sort_order}, which matters because evidence is quoted against it.
     */
    List<RubricCriterionEntity> findByQuestionVersionIdOrderBySortOrderAscIdAsc(
            UUID questionVersionId);
}
