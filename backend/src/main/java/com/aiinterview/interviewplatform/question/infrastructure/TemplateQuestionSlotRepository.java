package com.aiinterview.interviewplatform.question.infrastructure;

import com.aiinterview.interviewplatform.question.domain.TemplateQuestionSlotEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateQuestionSlotRepository
        extends JpaRepository<TemplateQuestionSlotEntity, UUID> {

    /**
     * The plan in author order. Positions are contiguous from 1 and unique per
     * template ({@code uq_tqs_position}, checked again at publish), so this
     * ordering is total.
     */
    List<TemplateQuestionSlotEntity> findByTemplateIdOrderByPositionAsc(UUID templateId);
}
