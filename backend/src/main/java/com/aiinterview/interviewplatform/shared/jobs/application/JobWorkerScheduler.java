package com.aiinterview.interviewplatform.shared.jobs.application;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link JobWorker} on a fixed delay.
 *
 * <p>Separated from the worker so the worker itself is a plain object with a
 * {@code pollOnce()} method. Tests drive that directly and assert on the result
 * rather than sleeping and hoping — which is the difference between a
 * deterministic suite and a flaky one.
 *
 * <p>{@code fixedDelayString} rather than {@code fixedRate}: the delay is
 * measured from the end of the previous run, so a slow batch cannot cause ticks
 * to pile up behind each other.
 *
 * <p>Spring's default scheduler is a single thread, which is exactly right
 * here — one poll at a time per instance, no unbounded thread creation, and
 * parallelism comes from running more instances.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.jobs.worker-enabled", havingValue = "true",
        matchIfMissing = true)
public class JobWorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobWorkerScheduler.class);

    private final JobWorker worker;

    public JobWorkerScheduler(JobWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.jobs.poll-interval-ms:1000}")
    public void tick() {
        try {
            worker.pollOnce();
        } catch (RuntimeException e) {
            // The scheduler silently stops rescheduling a task that throws, so
            // an escaping exception here would disable the worker for the life
            // of the process with nothing in the logs to say why.
            log.error("Job worker poll failed; continuing", e);
        }
    }

    /**
     * Stops claiming before the context tears down.
     *
     * <p>In-flight work is allowed to finish. Anything that does not is
     * reclaimed by lease expiry, so an abrupt kill and a clean shutdown end up
     * in the same recoverable state.
     */
    @PreDestroy
    public void shutdown() {
        worker.stop();
    }
}
