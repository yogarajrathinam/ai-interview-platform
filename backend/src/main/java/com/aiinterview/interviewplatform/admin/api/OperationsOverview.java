package com.aiinterview.interviewplatform.admin.api;

import com.aiinterview.interviewplatform.ai.api.AiUsageReadModel;
import com.aiinterview.interviewplatform.shared.jobs.api.JobOperations;
import java.time.Duration;
import java.util.List;

/**
 * The operator's single view of how grading is going.
 *
 * <p>This is the admin module doing the one job it exists for: composing read
 * models from other modules through their published contracts. It owns no
 * tables and writes nothing, so it can never become a second source of truth —
 * and every figure it reports is one the owning module already stands behind.
 *
 * <p>Answers, in one call: how much work is waiting, how much is stuck, what is
 * failing repeatedly, and which provider is responsible.
 */
public interface OperationsOverview {

    /**
     * @param window how far back provider usage is aggregated; queue figures
     *               are current rather than windowed, because a backlog is a
     *               state and not a rate
     */
    Overview snapshot(Duration window);

    record Overview(JobOperations.QueueSnapshot queue,
                    List<JobOperations.JobSummary> deadJobs,
                    List<JobOperations.JobSummary> retryingJobs,
                    List<AiUsageReadModel.ProviderUsage> providerUsage) {

        /** Work an operator must act on: nothing else will move it. */
        public boolean needsAttention() {
            return queue.hasDeadWork();
        }

        /** Providers failing more than a fifth of their calls. */
        public List<AiUsageReadModel.ProviderUsage> strugglingProviders() {
            return providerUsage.stream()
                    .filter(usage -> usage.total() >= 5 && usage.failureRatePercent() > 20.0)
                    .toList();
        }
    }
}
