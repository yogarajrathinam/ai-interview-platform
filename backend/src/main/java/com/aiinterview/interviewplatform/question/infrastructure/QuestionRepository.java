package com.aiinterview.interviewplatform.question.infrastructure;

import com.aiinterview.interviewplatform.question.domain.QuestionEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepository extends JpaRepository<QuestionEntity, UUID> {

    /**
     * Lookup by the human-stable key.
     *
     * <p>Unique by {@code uq_questions_key}, which is what makes seeding and
     * re-running an import idempotent.
     */
    Optional<QuestionEntity> findByQuestionKey(String questionKey);

    boolean existsByQuestionKey(String questionKey);
}
