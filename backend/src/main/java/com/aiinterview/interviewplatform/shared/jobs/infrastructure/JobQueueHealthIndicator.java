package com.aiinterview.interviewplatform.shared.jobs.infrastructure;

import com.aiinterview.interviewplatform.shared.config.AppProperties;
import com.aiinterview.interviewplatform.shared.jobs.api.JobOperations;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the queue's condition to the existing actuator health endpoint.
 *
 * <p>The judgement that matters here is what counts as unhealthy.
 * <strong>A failed evaluation does not.</strong> One candidate's grading going
 * wrong is a work-item failure, and reporting DOWN for it would take a
 * perfectly functional application out of the load balancer — turning one
 * broken answer into an outage for everyone.
 *
 * <p>So:
 *
 * <ul>
 *   <li><strong>DOWN</strong> only when the queue itself cannot be read, which
 *       means the database is unreachable and nothing works anyway.</li>
 *   <li><strong>OUT_OF_SERVICE</strong> never — this component does not have
 *       the standing to remove the instance from traffic.</li>
 *   <li><strong>UP with detail</strong> for a deep backlog or dead work: real
 *       signals an operator should see, and reasons to look, not to fail over.</li>
 * </ul>
 *
 * <p>Registered under {@code jobQueue}; details are visible only where
 * {@code management.endpoint.health.show-details} permits, which is nowhere in
 * production by current configuration.
 */
@Component("jobQueue")
public class JobQueueHealthIndicator implements HealthIndicator {

    private final JobOperations operations;
    private final AppProperties properties;

    public JobQueueHealthIndicator(JobOperations operations, AppProperties properties) {
        this.operations = operations;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            JobOperations.QueueSnapshot snapshot = operations.snapshot();
            int threshold = properties.jobs().backlogWarningThreshold();
            boolean backlogged = snapshot.backlog() > threshold;

            Health.Builder builder = Health.up()
                    .withDetail("workerEnabled", properties.jobs().workerEnabled())
                    .withDetail("queued", snapshot.queued())
                    .withDetail("running", snapshot.running())
                    .withDetail("dead", snapshot.dead())
                    .withDetail("backlog", snapshot.backlog())
                    .withDetail("backlogThreshold", threshold);

            if (snapshot.oldestQueuedAgeSeconds() != null) {
                builder.withDetail("oldestQueuedAgeSeconds", snapshot.oldestQueuedAgeSeconds());
            }
            if (backlogged) {
                builder.withDetail("warning", "Backlog exceeds the configured threshold");
            }
            if (snapshot.hasDeadWork()) {
                builder.withDetail("warning", "Dead jobs require operator attention");
            }
            return builder.build();

        } catch (RuntimeException e) {
            // The queue being unreadable means the database is unreachable, and
            // the database health indicator will be saying so too. Reported
            // without the exception message, which can carry connection detail.
            return Health.down().withDetail("reason", "Job queue is not readable").build();
        }
    }
}
