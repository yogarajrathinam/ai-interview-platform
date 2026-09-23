package com.aiinterview.interviewplatform.interview.infrastructure;

import com.aiinterview.interviewplatform.interview.domain.AnswerEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Answers are keyed by their turn, so the identifier here <em>is</em> the
 * {@code interviewQuestionId}. Insert-and-read only: content is immutable by
 * trigger and there is no update path by design.
 */
public interface AnswerRepository extends JpaRepository<AnswerEntity, UUID> {

    @Query("""
            select a from AnswerEntity a, InterviewQuestionEntity q
             where a.interviewQuestionId = q.id
               and q.interviewId = :interviewId
            """)
    List<AnswerEntity> findByInterviewId(@Param("interviewId") UUID interviewId);
}
