package com.aiinterview.interviewplatform.question.infrastructure;

import com.aiinterview.interviewplatform.question.domain.SkillEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Skills are reference data seeded by migration, so this is read-only in
 * practice: nothing in the application writes a skill, and adding one is a
 * migration rather than a call.
 */
public interface SkillRepository extends JpaRepository<SkillEntity, UUID> {

    List<SkillEntity> findByIdIn(List<UUID> ids);
}
