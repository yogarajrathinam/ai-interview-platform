package com.aiinterview.interviewplatform.shared.jobs.infrastructure;

import com.aiinterview.interviewplatform.shared.jobs.api.JobStatus;
import com.aiinterview.interviewplatform.shared.jobs.api.JobType;
import com.aiinterview.interviewplatform.shared.jobs.domain.JobEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<JobEntity, UUID> {

    /**
     * Selects claimable jobs, taking a row lock and skipping anything another
     * worker already holds.
     *
     * <p>This is the whole concurrency story, and it is native SQL for a
     * reason: {@code FOR UPDATE SKIP LOCKED} has no JPQL equivalent, and
     * without {@code SKIP LOCKED} a second worker would <em>block</em> on the
     * first's rows instead of moving past them — turning parallel workers into
     * a queue of one.
     *
     * <p>Two workers cannot claim the same row. The first holds a write lock
     * for the rest of its (short) claim transaction; the second never sees the
     * row at all; and by the time the lock releases the row is no longer
     * {@code QUEUED}, so it fails the predicate anyway. No application-level
     * check-then-set is involved, which is what makes it correct rather than
     * merely unlikely to collide.
     *
     * <p>Served by the partial index {@code ix_jobs_claim}, which covers only
     * queued rows and so stays small regardless of how much history accrues.
     */
    @Query(value = """
            SELECT * FROM app.jobs
             WHERE status = 'QUEUED'
               AND run_after <= :now
             ORDER BY priority, run_after
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<JobEntity> selectClaimable(@Param("now") OffsetDateTime now,
                                    @Param("batchSize") int batchSize);

    /**
     * Reclaims jobs whose owner went silent.
     *
     * <p>A worker can die between claiming and finishing; without this its work
     * would sit {@code RUNNING} forever. The attempt is <em>not</em> rolled
     * back — a crash still counts as a try, or a job that reliably kills its
     * worker would be retried without end.
     *
     * <p>Expressed as a single UPDATE so recovery is itself atomic: two
     * reapers running together cannot both revive the same row.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE app.jobs
               SET status = 'QUEUED',
                   locked_by = NULL,
                   locked_at = NULL,
                   last_error = 'Lease expired; reclaimed from ' || coalesce(locked_by, 'unknown'),
                   updated_at = :now
             WHERE status = 'RUNNING'
               AND locked_at < :cutoff
            """, nativeQuery = true)
    int reclaimExpiredLeases(@Param("cutoff") OffsetDateTime cutoff,
                             @Param("now") OffsetDateTime now);

    /**
     * Buries jobs that have already used every attempt.
     *
     * <p>Needed because a reclaimed lease returns work to the queue: without
     * this, a job whose worker crashes every time would cycle forever.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE app.jobs
               SET status = 'DEAD', updated_at = :now
             WHERE status = 'QUEUED'
               AND attempts >= max_attempts
            """, nativeQuery = true)
    int buryExhausted(@Param("now") OffsetDateTime now);

    Optional<JobEntity> findByDedupeKeyAndStatusIn(String dedupeKey, List<JobStatus> statuses);

    long countByStatus(JobStatus status);

    long countByJobTypeAndStatus(JobType jobType, JobStatus status);

    /** Oldest queued work — the backlog-age signal an operator actually needs. */
    @Query("select min(j.createdAt) from JobEntity j where j.status = :status")
    Optional<OffsetDateTime> findOldestCreatedAt(@Param("status") JobStatus status);

    List<JobEntity> findByStatusOrderByUpdatedAtDesc(JobStatus status);

    /** Jobs that have failed at least once and are still being retried. */
    @Query("""
            select j from JobEntity j
             where j.attempts > 0 and j.status = :status
             order by j.attempts desc, j.updatedAt desc
            """)
    List<JobEntity> findRetrying(@Param("status") JobStatus status);

    @Query("select coalesce(sum(j.attempts), 0) from JobEntity j")
    long totalAttempts();
}
