package com.aiinterview.interviewplatform.shared.jobs.application;

import com.aiinterview.interviewplatform.shared.config.AppProperties;
import com.aiinterview.interviewplatform.shared.jobs.api.JobHandler;
import com.aiinterview.interviewplatform.shared.jobs.domain.JobEntity;
import com.aiinterview.interviewplatform.shared.jobs.infrastructure.JobRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database write the worker performs, each in its own short transaction.
 *
 * <p>Separate from {@link JobWorker} so the transaction boundary is structural
 * rather than a comment someone deletes. The worker holds <strong>no</strong>
 * transaction while a handler runs, which is what stops a provider call from
 * pinning a connection and a row lock for its duration.
 *
 * <pre>
 *   tx: claim          commit
 *       (handler runs — no transaction, no lock held)
 *   tx: settle outcome commit
 * </pre>
 */
@Component
public class JobClaimService {

    private static final Logger log = LoggerFactory.getLogger(JobClaimService.class);

    private final JobRepository jobs;
    private final AppProperties properties;
    private final Clock clock;

    public JobClaimService(JobRepository jobs, AppProperties properties, Clock clock) {
        this.jobs = jobs;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Claims up to a batch of jobs.
     *
     * <p>{@code REQUIRES_NEW} so a claim is never entangled with anything the
     * caller is doing: the point of this transaction is to be short and to
     * commit before the handler is invoked.
     *
     * <p>The attempt counter is incremented <em>here</em>, at claim time rather
     * than at completion. A worker that crashes mid-handler has still consumed
     * an attempt, so a job that reliably kills its worker eventually dies
     * instead of cycling forever.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<JobHandler.JobContext> claimBatch(String workerId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<JobEntity> claimable =
                jobs.selectClaimable(now, properties.jobs().batchSize());

        List<JobHandler.JobContext> claimed = new ArrayList<>(claimable.size());
        for (JobEntity job : claimable) {
            job.claim(workerId, now);
            claimed.add(new JobHandler.JobContext(job.getId(), job.getJobType(),
                    job.getDedupeKey(), job.getPayload(), job.getAttempts(),
                    job.getMaxAttempts()));
            log.info("Job claimed: id={} type={} key={} attempt={}/{} worker={}",
                    job.getId(), job.getJobType(), job.getDedupeKey(),
                    job.getAttempts(), job.getMaxAttempts(), workerId);
        }
        jobs.saveAll(claimable);
        return claimed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(UUID jobId) {
        jobs.findById(jobId).ifPresent(job -> {
            job.markSucceeded(OffsetDateTime.now(clock));
            jobs.save(job);
            log.info("Job succeeded: id={} type={} key={} attempts={}",
                    job.getId(), job.getJobType(), job.getDedupeKey(), job.getAttempts());
        });
    }

    /**
     * Records a failure, rescheduling or burying it.
     *
     * @param retryable the handler's judgement; a permanent failure is buried
     *                  immediately rather than burning the remaining attempts
     *                  on work that provably cannot succeed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID jobId, boolean retryable, String error) {
        jobs.findById(jobId).ifPresent(job -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            String safeError = truncate(error);

            if (!retryable) {
                job.markDead(safeError, now);
                log.warn("Job permanently failed: id={} type={} key={} reason={}",
                        job.getId(), job.getJobType(), job.getDedupeKey(), safeError);
            } else if (job.hasExhaustedAttempts()) {
                job.markDead(safeError, now);
                log.warn("Job dead after {} attempts: id={} type={} key={} reason={}",
                        job.getAttempts(), job.getId(), job.getJobType(),
                        job.getDedupeKey(), safeError);
            } else {
                OffsetDateTime nextAttempt = now.plusSeconds(backoffSeconds(job.getAttempts()));
                job.scheduleRetry(nextAttempt, safeError, now);
                log.info("Job retry scheduled: id={} type={} key={} attempt={}/{} nextAttemptAt={}",
                        job.getId(), job.getJobType(), job.getDedupeKey(),
                        job.getAttempts(), job.getMaxAttempts(), nextAttempt);
            }
            jobs.save(job);
        });
    }

    /**
     * Returns abandoned work to the queue and buries anything out of attempts.
     *
     * <p>Both steps are single UPDATE statements, so recovery is itself atomic
     * and two instances running it together cannot revive the same row twice.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStaleWork() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime cutoff = now.minusSeconds(properties.jobs().leaseSeconds());

        int reclaimed = jobs.reclaimExpiredLeases(cutoff, now);
        if (reclaimed > 0) {
            log.warn("Reclaimed {} job(s) with expired leases", reclaimed);
        }
        int buried = jobs.buryExhausted(now);
        if (buried > 0) {
            log.warn("Buried {} job(s) that had exhausted their attempts", buried);
        }
        return reclaimed;
    }

    /**
     * Exponential backoff, doubling and capped.
     *
     * <p>Bounded on both ends deliberately: no immediate retry, so a failing
     * provider is not hammered, and a ceiling so a long-lived queue does not
     * schedule work days away.
     */
    long backoffSeconds(int attempts) {
        long initial = properties.jobs().initialBackoffSeconds();
        long max = properties.jobs().maxBackoffSeconds();
        int exponent = Math.max(0, attempts - 1);
        long delay = initial << Math.min(exponent, 20);
        return Math.min(Math.max(delay, initial), max);
    }

    /** The column is for diagnosis, not prose; provider messages can be long. */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
