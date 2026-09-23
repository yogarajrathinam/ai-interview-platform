package com.aiinterview.interviewplatform.interview.infrastructure;

import com.aiinterview.interviewplatform.interview.api.InterviewStatus;
import com.aiinterview.interviewplatform.interview.domain.InterviewEntity;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewRepository extends JpaRepository<InterviewEntity, UUID> {

    /**
     * Takes a row lock on the attempt for operations that are not naturally
     * idempotent.
     *
     * <p>Serving a turn is safe to repeat — two callers converge on the same
     * turn. Creating follow-ups is not: two concurrent advances could each
     * decide to append, and the attempt would grow a duplicate probe. Locking
     * the aggregate root makes that impossible rather than unlikely.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InterviewEntity i where i.id = :id")
    Optional<InterviewEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The candidate's live attempt, if any.
     *
     * <p>{@code uq_interviews_one_live} guarantees at most one, so this returns
     * an Optional rather than a list.
     */
    @Query("""
            select i from InterviewEntity i
             where i.candidateUserId = :candidateUserId
               and i.status in :liveStatuses
            """)
    Optional<InterviewEntity> findLiveForCandidate(
            @Param("candidateUserId") UUID candidateUserId,
            @Param("liveStatuses") List<InterviewStatus> liveStatuses);

    /** Attempts the sweeper should look at. Served by {@code ix_interviews_sweep}. */
    @Query("""
            select i from InterviewEntity i
             where i.status in :liveStatuses
               and i.hardDeadlineAt < :now
            """)
    List<InterviewEntity> findExpired(@Param("liveStatuses") List<InterviewStatus> liveStatuses,
                                      @Param("now") OffsetDateTime now);
}
