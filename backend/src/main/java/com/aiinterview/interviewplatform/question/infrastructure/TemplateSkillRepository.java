package com.aiinterview.interviewplatform.question.infrastructure;

import com.aiinterview.interviewplatform.question.domain.TemplateSkillEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateSkillRepository
        extends JpaRepository<TemplateSkillEntity, TemplateSkillEntity.TemplateSkillId> {

    List<TemplateSkillEntity> findByTemplateId(UUID templateId);

    void deleteByTemplateId(UUID templateId);
}
