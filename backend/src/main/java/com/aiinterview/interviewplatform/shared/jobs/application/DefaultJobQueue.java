package com.aiinterview.interviewplatform.shared.jobs.application;

import com.aiinterview.interviewplatform.shared.config.AppProperties;
import com.aiinterview.interviewplatform.shared.id.IdGenerator;
import com.aiinterview.interviewplatform.shared.jobs.api.JobQueue;
import com.aiinterview.interviewplatform.shared.jobs.api.JobStatus;
import com.aiinterview.interviewplatform.shared.jobs.domain.JobEntity;
import com.aiinterview.interviewplatform.shared.jobs.infrastructure.JobRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enqueues work into the database queue.
 *
 * <p>Joins the caller's transaction ({@code MANDATORY} would be too strict for
 * tests, but {@code REQUIRED} is the point): the job row and the business
 * change it depends on commit together or not at all. That is the entire reason
 * for a database-backed queue rather than a broker — there is no window in
 * which an answer exists with nothing scheduled to grade it, and no outbox to
 * drift out of step.
 */
@Service
public class DefaultJobQueue implements JobQueue {

    private static final Logger log = LoggerFactory.getLogger(DefaultJobQueue.class);

    private static final List<JobStatus> ACTIVE = List.of(JobStatus.QUEUED, JobStatus.RUNNING);

    private final JobRepository jobs;
    private final IdGenerator idGenerator;
    private final AppProperties properties;
    private final Clock clock;

    public DefaultJobQueue(JobRepository jobs, IdGenerator idGenerator,
                           AppProperties properties, Clock clock) {
        this.jobs = jobs;
        this.idGenerator = idGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Optional<UUID> enqueue(JobRequest request) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Checked first so the ordinary duplicate costs no constraint violation,
        // but the index below is what actually guarantees it under concurrency.
        Optional<JobEntity> active =
                jobs.findByDedupeKeyAndStatusIn(request.dedupeKey(), ACTIVE);
        if (active.isPresent()) {
            log.debug("Job {} already active; not enqueuing a duplicate", request.dedupeKey());
            return Optional.empty();
        }

        int maxAttempts = request.maxAttempts() > 0
                ? request.maxAttempts()
                : properties.jobs().maxAttempts();

        JobEntity job = JobEntity.enqueue(
                idGenerator.newId(),
                request.jobType(),
                request.dedupeKey(),
                request.payload(),
                (short) request.priority(),
                (short) maxAttempts,
                now.plusSeconds(request.delaySeconds()),
                now);

        try {
            UUID id = jobs.save(job).getId();
            log.debug("Enqueued {} job {} ({})", request.jobType(), id, request.dedupeKey());
            return Optional.of(id);
        } catch (DataIntegrityViolationException e) {
            // uq_jobs_active_dedupe settled a race between two enqueuers. The
            // other caller's job does the same work, so this is success, not
            // failure — rethrowing would fail a candidate's answer submission
            // for a duplicate that is already handled.
            log.debug("Concurrent enqueue for {}; the existing job stands", request.dedupeKey());
            return Optional.empty();
        }
    }
}
