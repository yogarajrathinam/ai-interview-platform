package com.aiinterview.interviewplatform.shared.jobs.application;

import com.aiinterview.interviewplatform.shared.config.AppProperties;
import com.aiinterview.interviewplatform.shared.id.IdGenerator;
import com.aiinterview.interviewplatform.shared.jobs.api.JobExecutionException;
import com.aiinterview.interviewplatform.shared.jobs.api.JobHandler;
import com.aiinterview.interviewplatform.shared.jobs.api.JobType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Polls for work and runs it.
 *
 * <p>Deliberately a poller rather than anything cleverer.
 * {@code LISTEN/NOTIFY} does not survive a transaction-mode connection pooler,
 * and a broker would add infrastructure to operate for a workload that is a
 * handful of jobs per interview. One indexed query per second against a partial
 * index covering only queued rows is cheap enough to be uninteresting.
 *
 * <p>Runs on the caller's scheduling thread and processes its batch serially.
 * No thread pool is created: concurrency, when it is needed, comes from running
 * more instances — which the claim mechanism already supports — rather than
 * from threads inside one, where a crash loses the whole batch at once.
 *
 * <p>Holds no transaction while a handler runs. That property is enforced by
 * delegating every write to {@link JobClaimService}.
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private static final String MDC_JOB_ID = "jobId";

    private final JobClaimService claims;
    private final AppProperties properties;
    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);

    /** Distinguishes this instance's claims in the database and in logs. */
    private final String workerId;

    /** Flipped on shutdown so no further batch is claimed. */
    private final AtomicBoolean accepting = new AtomicBoolean(false);

    private final AtomicInteger inFlight = new AtomicInteger();

    public JobWorker(JobClaimService claims, AppProperties properties,
                     List<JobHandler> availableHandlers, IdGenerator idGenerator,
                     @Value("${HOSTNAME:local}") String hostname) {
        this.claims = claims;
        this.properties = properties;
        this.workerId = hostname + "-" + idGenerator.newId().toString().substring(0, 8);

        for (JobHandler handler : availableHandlers) {
            JobHandler previous = handlers.put(handler.jobType(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two handlers registered for " + handler.jobType());
            }
        }
    }

    /**
     * Starts accepting work only once the application is fully up.
     *
     * <p>Claiming during startup would risk a handler running against
     * half-initialised beans, and the work is never so urgent that a second's
     * delay matters.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // Whether this instance does work is decided by whether the scheduler
        // bean exists (@ConditionalOnProperty on app.jobs.worker-enabled), not
        // here. Gating in both places would mean a worker that is willing but
        // unreachable, or reachable but unwilling — and only one of those is
        // ever the intent.
        accepting.set(true);
        log.info("Job worker {} accepting work; handlers={} pollIntervalMs={} batchSize={}",
                workerId, handlers.keySet(), properties.jobs().pollIntervalMs(),
                properties.jobs().batchSize());
    }

    /**
     * Stops accepting new work, letting anything in flight finish.
     *
     * <p>Only the <em>claiming</em> stops. An in-flight handler is allowed to
     * complete, because interrupting one between its provider call and its
     * write is precisely how a job ends up permanently stuck. Anything that
     * does not finish before the process exits keeps its lease and is reclaimed
     * later — the crash path and the shutdown path are deliberately the same.
     */
    public void stop() {
        if (accepting.compareAndSet(true, false)) {
            log.info("Job worker {} stopped accepting work; {} in flight", workerId, inFlight.get());
        }
    }

    public boolean isAccepting() {
        return accepting.get();
    }

    public String workerId() {
        return workerId;
    }

    /**
     * One polling tick: recover abandoned work, then claim and run a batch.
     *
     * @return how many jobs were processed, which lets tests drive the worker
     *         deterministically instead of waiting on a schedule
     */
    public int pollOnce() {
        if (!accepting.get()) {
            return 0;
        }

        try {
            claims.recoverStaleWork();
        } catch (RuntimeException e) {
            // Recovery failing must not stop the worker doing its actual job.
            log.error("Stale-work recovery failed on worker {}", workerId, e);
        }

        List<JobHandler.JobContext> batch;
        try {
            batch = claims.claimBatch(workerId);
        } catch (RuntimeException e) {
            log.error("Failed to claim work on worker {}", workerId, e);
            return 0;
        }

        int processed = 0;
        for (JobHandler.JobContext context : batch) {
            // Re-checked each iteration so shutdown takes effect mid-batch
            // rather than only between polls.
            if (!accepting.get()) {
                log.info("Shutting down; releasing {} unprocessed claim(s) to their lease",
                        batch.size() - processed);
                break;
            }
            execute(context);
            processed++;
        }
        return processed;
    }

    private void execute(JobHandler.JobContext context) {
        inFlight.incrementAndGet();
        MDC.put(MDC_JOB_ID, context.jobId().toString());
        try {
            JobHandler handler = handlers.get(context.jobType());
            if (handler == null) {
                // A queued type nobody handles cannot be fixed by retrying, and
                // leaving it queued would have it re-claimed on every poll.
                claims.markFailed(context.jobId(), false,
                        "No handler registered for job type " + context.jobType());
                return;
            }

            log.info("Job started: id={} type={} attempt={}/{}",
                    context.jobId(), context.jobType(), context.attempt(), context.maxAttempts());

            handler.handle(context);
            claims.markSucceeded(context.jobId());

        } catch (JobExecutionException e) {
            claims.markFailed(context.jobId(), e.isRetryable(), e.getMessage());
        } catch (RuntimeException e) {
            // Unrecognised faults are treated as retryable: an unexpected error
            // is more often transient than permanent, and burying work on a
            // surprise loses a candidate's grade for good. Attempts are bounded,
            // so a genuinely broken job still dies.
            log.error("Job {} threw an unexpected exception", context.jobId(), e);
            claims.markFailed(context.jobId(), true, e.toString());
        } finally {
            MDC.remove(MDC_JOB_ID);
            inFlight.decrementAndGet();
        }
    }
}
