package com.aiinterview.interviewplatform.interview.infrastructure;

import com.aiinterview.interviewplatform.interview.api.TurnStatus;
import com.aiinterview.interviewplatform.interview.domain.InterviewQuestionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewQuestionRepository
        extends JpaRepository<InterviewQuestionEntity, UUID> {

    /**
     * Every turn in the attempt, in the order they are asked.
     *
     * <p>The runner's hot path, served entirely by {@code uq_iq_position}. An
     * attempt has at most a dozen turns, so loading them all and deciding in
     * memory is both simpler and faster than a query per question.
     */
    List<InterviewQuestionEntity> findByInterviewIdOrderByPositionAsc(UUID interviewId);

    long countByInterviewIdAndStatusIn(UUID interviewId, List<TurnStatus> statuses);

    /**
     * Question versions this candidate has already been served, across every
     * attempt.
     *
     * <p>Feeds exposure avoidance at planning time. Deliberately not filtered
     * by attempt status: seeing a question in an abandoned attempt still counts
     * as having seen it.
     */
    @Query("""
            select distinct q.questionVersionId
              from InterviewQuestionEntity q, InterviewEntity i
             where q.interviewId = i.id
               and i.candidateUserId = :candidateUserId
            """)
    List<UUID> findQuestionVersionIdsServedTo(@Param("candidateUserId") UUID candidateUserId);
}
