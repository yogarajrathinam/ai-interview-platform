package com.aiinterview.interviewplatform.question.infrastructure;

import com.aiinterview.interviewplatform.question.domain.InterviewTemplateEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewTemplateRepository
        extends JpaRepository<InterviewTemplateEntity, UUID> {

    /**
     * The live version of a template family. Unique by
     * {@code uq_tpl_one_published}, so at most one row can match.
     */
    Optional<InterviewTemplateEntity> findByTemplateKeyAndStatus(
            String templateKey, InterviewTemplateEntity.Status status);
}
