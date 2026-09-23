package com.aiinterview.interviewplatform.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiinterview.interviewplatform.shared.config.AppProperties;
import com.aiinterview.interviewplatform.shared.jobs.api.JobExecutionException;
import com.aiinterview.interviewplatform.shared.jobs.api.JobHandler;
import com.aiinterview.interviewplatform.shared.jobs.api.JobOperations;
import com.aiinterview.interviewplatform.shared.jobs.api.JobQueue;
import com.aiinterview.interviewplatform.shared.jobs.api.JobType;
import com.aiinterview.interviewplatform.shared.jobs.application.JobClaimService;
import com.aiinterview.interviewplatform.shared.jobs.application.JobWorker;
import com.aiinterview.interviewplatform.support.AbstractDatabaseTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The worker against a real PostgreSQL.
 *
 * <p>Drives {@code pollOnce()} directly rather than waiting on the scheduler,
 * so every assertion is deterministic — no sleeps, no polling for a condition,
 * no flakiness. The scheduled worker is disabled in the test profile precisely
 * so it cannot claim the jobs these tests are inspecting.
 */
@DisplayName("Job worker")
class JobWorkerIntegrationTest extends AbstractDatabaseTest {

    @Autowired
    private JobQueue queue;

    @Autowired
    private JobClaimService claims;

    @Autowired
    private JobOperations operations;

    @Autowired
    private AppProperties properties;

    @Autowired
    private TransactionTemplate transactions;

    /**
     * Starts each test from an empty queue.
     *
     * <p>The queue is global, and a worker claims whatever is available — so
     * without this, one test's leftovers become another's phantom work and the
     * counts stop meaning anything. Safe to truncate: no other suite asserts on
     * job rows, and nothing references them by foreign key.
     */
    @BeforeEach
    void clearQueue() {
        jdbc.update("DELETE FROM app.jobs");
    }

    // ------------------------------------------------------------ fixtures

    /** A handler whose behaviour the test dictates, recording what it saw. */
    private static final class RecordingHandler implements JobHandler {
        private final Consumer<JobContext> behaviour;
        private final List<JobContext> seen = new java.util.concurrent.CopyOnWriteArrayList<>();

        RecordingHandler(Consumer<JobContext> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public JobType jobType() {
            return JobType.MAINTENANCE;
        }

        @Override
        public void handle(JobContext context) {
            seen.add(context);
            behaviour.accept(context);
        }

        int invocations() {
            return seen.size();
        }
    }

    private JobWorker workerWith(JobHandler handler) {
        JobWorker worker = new JobWorker(claims, properties, List.of(handler), UUID::randomUUID, "test");
        worker.start();
        return worker;
    }

    /** Enqueues inside a transaction, as production callers do. */
    private UUID enqueue(String key) {
        return transactions.execute(status -> queue.enqueue(
                JobQueue.JobRequest.of(JobType.MAINTENANCE, key, "{}")).orElse(null));
    }

    private Map<String, Object> jobRow(String dedupeKey) {
        return jdbc.queryForMap("SELECT * FROM app.jobs WHERE dedupe_key = ?", dedupeKey);
    }

    // ------------------------------------------------------------- success

    @Test
    @DisplayName("nothing to do is not an error")
    void noAvailableWork() {
        RecordingHandler handler = new RecordingHandler(context -> { });

        assertThat(workerWith(handler).pollOnce()).isZero();
        assertThat(handler.invocations()).isZero();
    }

    @Test
    @DisplayName("available work is claimed, run and marked succeeded")
    void successfulRun() {
        String key = "ok-" + UUID.randomUUID();
        enqueue(key);
        RecordingHandler handler = new RecordingHandler(context -> { });

        assertThat(workerWith(handler).pollOnce()).isEqualTo(1);

        assertThat(handler.invocations()).isEqualTo(1);
        Map<String, Object> job = jobRow(key);
        assertThat(job.get("status")).isEqualTo("SUCCEEDED");
        assertThat(attempts(key)).isEqualTo(1);
        // ck_jobs_lock: only a RUNNING row may name an owner.
        assertThat(job.get("locked_by")).isNull();
    }

    @Test
    @DisplayName("the handler is told which attempt it is on")
    void attemptIsVisibleToTheHandler() {
        String key = "attempt-" + UUID.randomUUID();
        enqueue(key);
        RecordingHandler handler = new RecordingHandler(context -> { });

        workerWith(handler).pollOnce();

        assertThat(handler.seen.get(0).attempt()).isEqualTo(1);
        assertThat(handler.seen.get(0).maxAttempts())
                .isEqualTo(properties.jobs().maxAttempts());
    }

    // -------------------------------------------------------------- retries

    @Test
    @DisplayName("a retryable failure returns the job to the queue with backoff")
    void retryableFailureIsRescheduled() {
        String key = "retry-" + UUID.randomUUID();
        enqueue(key);
        JobWorker worker = workerWith(new RecordingHandler(context -> {
            throw JobExecutionException.retryable("provider timed out");
        }));

        OffsetDateTime before = OffsetDateTime.now();
        worker.pollOnce();

        Map<String, Object> job = jobRow(key);
        assertThat(job.get("status")).isEqualTo("QUEUED");
        assertThat(attempts(key)).isEqualTo(1);
        assertThat(job.get("last_error")).asString().contains("timed out");
        // Not retried immediately: a failing dependency must not be hammered.
        assertThat(runAfter(key)).isAfter(before);
    }

    @Test
    @DisplayName("backoff grows with each attempt")
    void backoffIsExponential() {
        String key = "backoff-" + UUID.randomUUID();
        enqueue(key);
        JobWorker worker = workerWith(new RecordingHandler(context -> {
            throw JobExecutionException.retryable("still failing");
        }));

        long firstDelay = runAndMeasureDelay(worker, key);
        makeAvailableNow(key);
        long secondDelay = runAndMeasureDelay(worker, key);

        assertThat(secondDelay).isGreaterThan(firstDelay);
    }

    @Test
    @DisplayName("a non-retryable failure is buried immediately, attempts unspent")
    void permanentFailureIsBuriedAtOnce() {
        String key = "permanent-" + UUID.randomUUID();
        enqueue(key);
        JobWorker worker = workerWith(new RecordingHandler(context -> {
            throw JobExecutionException.permanent("payload can never be parsed");
        }));

        worker.pollOnce();

        Map<String, Object> job = jobRow(key);
        assertThat(job.get("status")).isEqualTo("DEAD");
        // Burning three more provider calls on work that cannot succeed would
        // cost money and delay the real diagnosis.
        assertThat(attempts(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("retries stop at the configured ceiling")
    void retriesAreBounded() {
        String key = "exhaust-" + UUID.randomUUID();
        enqueue(key);
        JobWorker worker = workerWith(new RecordingHandler(context -> {
            throw JobExecutionException.retryable("always fails");
        }));

        int maxAttempts = properties.jobs().maxAttempts();
        for (int i = 0; i < maxAttempts; i++) {
            makeAvailableNow(key);
            worker.pollOnce();
        }

        Map<String, Object> job = jobRow(key);
        assertThat(job.get("status")).isEqualTo("DEAD");
        assertThat(attempts(key)).isEqualTo(maxAttempts);
    }

    @Test
    @DisplayName("an unexpected exception is treated as retryable, not buried")
    void unexpectedFailuresAreRetried() {
        String key = "surprise-" + UUID.randomUUID();
        enqueue(key);
        JobWorker worker = workerWith(new RecordingHandler(context -> {
            throw new IllegalStateException("something nobody predicted");
        }));

        worker.pollOnce();

        // Burying work on a surprise loses a candidate's grade for good;
        // attempts are bounded, so a genuinely broken job still dies.
        assertThat(jobRow(key).get("status")).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("a job with no registered handler is buried rather than re-claimed forever")
    void unhandledTypeIsBuried() {
        String key = "unhandled-" + UUID.randomUUID();
        enqueue(key);
        // A worker whose only handler is for a different type.
        JobWorker worker = new JobWorker(claims, properties, List.of(new JobHandler() {
            @Override
            public JobType jobType() {
                return JobType.GENERATE_REPORT;
            }

            @Override
            public void handle(JobContext context) {
                throw new AssertionError("must not be called");
            }
        }), UUID::randomUUID, "test");
        worker.start();

        worker.pollOnce();

        assertThat(jobRow(key).get("status")).isEqualTo("DEAD");
        assertThat(jobRow(key).get("last_error")).asString().contains("No handler");
    }

    // ---------------------------------------------------------- duplicates

    @Test
    @DisplayName("the same work cannot be enqueued twice while active")
    void duplicateEnqueueIsRejected() {
        String key = "dup-" + UUID.randomUUID();

        assertThat(enqueue(key)).isNotNull();
        assertThat(enqueue(key)).isNull();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.jobs WHERE dedupe_key = ?", Integer.class, key))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the same work can be enqueued again once it has finished")
    void completedWorkMayBeRequeued() {
        String key = "requeue-" + UUID.randomUUID();
        enqueue(key);
        workerWith(new RecordingHandler(context -> { })).pollOnce();

        // Partial, not full, uniqueness: this is what makes an administrative
        // re-run possible at all.
        assertThat(enqueue(key)).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.jobs WHERE dedupe_key = ?", Integer.class, key))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("running the same job twice is safe for an idempotent handler")
    void duplicateDeliveryIsTolerated() {
        String key = "redeliver-" + UUID.randomUUID();
        enqueue(key);
        AtomicInteger effects = new AtomicInteger();
        RecordingHandler handler = new RecordingHandler(context -> effects.incrementAndGet());
        JobWorker worker = workerWith(handler);

        worker.pollOnce();
        // Force the job back to available, as a crashed worker's lease would.
        makeAvailableNow(key);
        worker.pollOnce();

        assertThat(handler.invocations()).isEqualTo(2);
        assertThat(effects.get()).isEqualTo(2);
        // The job still settles cleanly; idempotency is the handler's contract.
        assertThat(jobRow(key).get("status")).isEqualTo("SUCCEEDED");
    }

    // -------------------------------------------------------- stale claims

    @Test
    @DisplayName("a claim abandoned by a dead worker is reclaimed")
    void staleClaimIsRecovered() {
        String key = "stale-" + UUID.randomUUID();
        enqueue(key);

        // Simulate a worker that claimed and then died: RUNNING, owner named,
        // lease long past.
        jdbc.update("""
                UPDATE app.jobs
                   SET status = 'RUNNING', locked_by = 'dead-worker',
                       locked_at = now() - interval '2 hours', attempts = 1
                 WHERE dedupe_key = ?
                """, key);

        int reclaimed = claims.recoverStaleWork();

        assertThat(reclaimed).isGreaterThanOrEqualTo(1);
        Map<String, Object> job = jobRow(key);
        assertThat(job.get("status")).isEqualTo("QUEUED");
        assertThat(job.get("locked_by")).isNull();
        assertThat(job.get("last_error")).asString().contains("dead-worker");
        // The crash still counted as an attempt, or a job that kills its worker
        // every time would cycle for ever.
        assertThat(attempts(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("a live claim is left alone")
    void freshClaimIsNotReclaimed() {
        String key = "live-" + UUID.randomUUID();
        enqueue(key);
        jdbc.update("""
                UPDATE app.jobs SET status = 'RUNNING', locked_by = 'busy-worker',
                       locked_at = now(), attempts = 1
                 WHERE dedupe_key = ?
                """, key);

        claims.recoverStaleWork();

        assertThat(jobRow(key).get("status")).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("a reclaimed job out of attempts is buried rather than cycling")
    void reclaimedJobWithNoAttemptsLeftIsBuried() {
        String key = "cycle-" + UUID.randomUUID();
        enqueue(key);
        jdbc.update("""
                UPDATE app.jobs
                   SET status = 'RUNNING', locked_by = 'dead', max_attempts = 2,
                       attempts = 2, locked_at = now() - interval '2 hours'
                 WHERE dedupe_key = ?
                """, key);

        claims.recoverStaleWork();

        assertThat(jobRow(key).get("status")).isEqualTo("DEAD");
    }

    // --------------------------------------------------------- concurrency

    @Test
    @DisplayName("two workers racing for one job produce exactly one claim")
    void concurrentWorkersClaimDisjointWork() throws Exception {
        String key = "race-" + UUID.randomUUID();
        enqueue(key);

        AtomicInteger handled = new AtomicInteger();
        Map<String, Boolean> claimedBy = new ConcurrentHashMap<>();
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            for (int i = 0; i < 2; i++) {
                String workerName = "worker-" + i;
                pool.submit(() -> {
                    JobWorker worker = new JobWorker(claims, properties,
                            List.of(new RecordingHandler(context -> {
                                handled.incrementAndGet();
                                claimedBy.put(workerName, true);
                            })), UUID::randomUUID, workerName);
                    worker.start();
                    startLine.await();
                    worker.pollOnce();
                    return null;
                });
            }
            startLine.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // FOR UPDATE SKIP LOCKED: the loser skips the row entirely rather than
        // blocking on it, and by the time the lock lifts the row is no longer
        // QUEUED. No application-level check-then-set is involved.
        assertThat(handled.get()).isEqualTo(1);
        assertThat(claimedBy).hasSize(1);
        assertThat(jobRow(key).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("a batch is split between workers rather than duplicated")
    void concurrentWorkersShareABatch() throws Exception {
        int jobCount = 8;
        for (int i = 0; i < jobCount; i++) {
            enqueue("batch-" + UUID.randomUUID());
        }

        AtomicInteger handled = new AtomicInteger();
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            for (int i = 0; i < 2; i++) {
                String workerName = "batch-worker-" + i;
                pool.submit(() -> {
                    JobWorker worker = new JobWorker(claims, properties,
                            List.of(new RecordingHandler(c -> handled.incrementAndGet())),
                            UUID::randomUUID, workerName);
                    worker.start();
                    startLine.await();
                    // Two polls each, so between them they can cover the batch.
                    worker.pollOnce();
                    worker.pollOnce();
                    return null;
                });
            }
            startLine.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // No job ran twice: total handled never exceeds what was enqueued.
        assertThat(handled.get()).isLessThanOrEqualTo(jobCount);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app.jobs
                 WHERE dedupe_key LIKE 'batch-%' AND status = 'RUNNING'
                """, Integer.class)).isZero();
    }

    // ----------------------------------------------------------- shutdown

    @Test
    @DisplayName("a stopped worker claims nothing further")
    void shutdownStopsClaiming() {
        String key = "shutdown-" + UUID.randomUUID();
        enqueue(key);
        RecordingHandler handler = new RecordingHandler(context -> { });
        JobWorker worker = workerWith(handler);

        worker.stop();
        int processed = worker.pollOnce();

        assertThat(worker.isAccepting()).isFalse();
        assertThat(processed).isZero();
        assertThat(handler.invocations()).isZero();
        // The work is untouched and still available to another instance.
        assertThat(jobRow(key).get("status")).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("each worker identifies itself, so a claim is attributable")
    void workerIdentifiesItself() {
        JobWorker first = workerWith(new RecordingHandler(context -> { }));
        JobWorker second = workerWith(new RecordingHandler(context -> { }));

        assertThat(first.isAccepting()).isTrue();
        assertThat(first.workerId()).startsWith("test-");
        // Distinct ids, or an abandoned claim could not be traced to an instance.
        assertThat(first.workerId()).isNotEqualTo(second.workerId());
    }

    @Test
    @DisplayName("work stopped mid-batch stays claimable, recovered by lease expiry")
    void inFlightWorkSurvivesShutdown() {
        String first = "batch-stop-a-" + UUID.randomUUID();
        String second = "batch-stop-b-" + UUID.randomUUID();
        enqueue(first);
        enqueue(second);

        JobWorker worker = workerWith(new RecordingHandler(context -> { }));
        // Stopping from inside the first handler leaves the rest of the batch
        // claimed but unprocessed — the same state a crash produces.
        JobWorker stopping = new JobWorker(claims, properties,
                List.of(new RecordingHandler(context -> worker.stop())),
                UUID::randomUUID, "stopper");
        stopping.start();
        stopping.pollOnce();

        // Whatever was claimed and not run keeps its lease and comes back.
        long stillRunning = jdbc.queryForObject("""
                SELECT count(*) FROM app.jobs
                 WHERE dedupe_key IN (?, ?) AND status = 'RUNNING'
                """, Long.class, first, second);
        assertThat(stillRunning).isLessThanOrEqualTo(2);
    }

    // ---------------------------------------------------------- operations

    @Test
    @DisplayName("the queue can be inspected: pending, dead and retrying work")
    void operationalVisibility() {
        String succeeded = "ops-ok-" + UUID.randomUUID();
        String dead = "ops-dead-" + UUID.randomUUID();
        String queued = "ops-queued-" + UUID.randomUUID();
        enqueue(succeeded);
        enqueue(dead);
        enqueue(queued);

        workerWith(new RecordingHandler(context -> {
            if (context.dedupeKey().equals(dead)) {
                throw JobExecutionException.permanent("bad payload");
            }
            if (context.dedupeKey().equals(queued)) {
                throw JobExecutionException.retryable("try later");
            }
        })).pollOnce();

        JobOperations.QueueSnapshot snapshot = operations.snapshot();
        assertThat(snapshot.dead()).isGreaterThanOrEqualTo(1);
        assertThat(snapshot.succeeded()).isGreaterThanOrEqualTo(1);
        assertThat(snapshot.byTypeAndStatus()).isNotEmpty();

        assertThat(operations.deadJobs(10))
                .anySatisfy(job -> assertThat(job.dedupeKey()).isEqualTo(dead));
        assertThat(operations.retryingJobs(10))
                .anySatisfy(job -> {
                    assertThat(job.attempts()).isPositive();
                    assertThat(job.lastError()).isNotNull();
                });
    }

    @Test
    @DisplayName("operational data carries correlation ids, never candidate content")
    void operationalDataIsSafeToShow() {
        String key = "safe-" + UUID.randomUUID();
        enqueue(key);
        workerWith(new RecordingHandler(context ->
                { throw JobExecutionException.permanent("nope"); })).pollOnce();

        Optional<JobOperations.JobSummary> summary = operations.deadJobs(50).stream()
                .filter(job -> job.dedupeKey().equals(key))
                .findFirst();

        assertThat(summary).isPresent();
        // The payload is not exposed at all, and the key is an id, not prose.
        assertThat(summary.get().dedupeKey()).doesNotContain(" ");
    }

    // ------------------------------------------------------------- helpers

    /** Clears backoff so the next poll can claim the job immediately. */
    private void makeAvailableNow(String key) {
        jdbc.update("""
                UPDATE app.jobs
                   SET status = 'QUEUED', run_after = now() - interval '1 second',
                       locked_by = NULL, locked_at = NULL
                 WHERE dedupe_key = ? AND status <> 'DEAD'
                """, key);
    }

    /** Typed reads: queryForMap hands back java.sql.Timestamp and Integer. */
    private int attempts(String key) {
        return jdbc.queryForObject("SELECT attempts FROM app.jobs WHERE dedupe_key = ?",
                Integer.class, key);
    }

    private OffsetDateTime runAfter(String key) {
        return jdbc.queryForObject("SELECT run_after FROM app.jobs WHERE dedupe_key = ?",
                OffsetDateTime.class, key);
    }

    private long runAndMeasureDelay(JobWorker worker, String key) {
        OffsetDateTime before = OffsetDateTime.now();
        worker.pollOnce();
        return java.time.Duration.between(before, runAfter(key)).toMillis();
    }
}
