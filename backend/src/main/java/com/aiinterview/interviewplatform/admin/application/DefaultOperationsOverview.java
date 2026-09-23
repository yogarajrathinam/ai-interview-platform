package com.aiinterview.interviewplatform.admin.application;

import com.aiinterview.interviewplatform.admin.api.OperationsOverview;
import com.aiinterview.interviewplatform.ai.api.AiUsageReadModel;
import com.aiinterview.interviewplatform.shared.jobs.api.JobOperations;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/**
 * Composes the operational view from other modules' published read models.
 *
 * <p>Holds no repository and touches no table directly — if it did, the admin
 * module would start owning data and become a second application rather than a
 * window onto the first.
 */
@Service
public class DefaultOperationsOverview implements OperationsOverview {

    /** Enough rows to see a pattern, few enough to read. */
    private static final int SAMPLE_LIMIT = 20;

    private final JobOperations jobOperations;
    private final AiUsageReadModel aiUsage;
    private final Clock clock;

    public DefaultOperationsOverview(JobOperations jobOperations, AiUsageReadModel aiUsage,
                                     Clock clock) {
        this.jobOperations = jobOperations;
        this.aiUsage = aiUsage;
        this.clock = clock;
    }

    @Override
    public Overview snapshot(Duration window) {
        Duration effective = window == null || window.isNegative() || window.isZero()
                ? Duration.ofDays(1)
                : window;
        OffsetDateTime since = OffsetDateTime.now(clock).minus(effective);

        return new Overview(
                jobOperations.snapshot(),
                jobOperations.deadJobs(SAMPLE_LIMIT),
                jobOperations.retryingJobs(SAMPLE_LIMIT),
                aiUsage.usageSince(since));
    }
}
