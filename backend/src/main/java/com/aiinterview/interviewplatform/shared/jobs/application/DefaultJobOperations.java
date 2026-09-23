package com.aiinterview.interviewplatform.shared.jobs.application;

import com.aiinterview.interviewplatform.shared.jobs.api.JobOperations;
import com.aiinterview.interviewplatform.shared.jobs.api.JobStatus;
import com.aiinterview.interviewplatform.shared.jobs.api.JobType;
import com.aiinterview.interviewplatform.shared.jobs.domain.JobEntity;
import com.aiinterview.interviewplatform.shared.jobs.infrastructure.JobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Counts the queue. Read-only, and every figure is derived from stored rows. */
@Service
public class DefaultJobOperations implements JobOperations {

    private final JobRepository jobs;
    private final Clock clock;

    public DefaultJobOperations(JobRepository jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public QueueSnapshot snapshot() {
        Map<String, Long> byTypeAndStatus = new LinkedHashMap<>();
        for (JobType type : JobType.values()) {
            for (JobStatus status : JobStatus.values()) {
                long count = jobs.countByJobTypeAndStatus(type, status);
                if (count > 0) {
                    // Absent rather than zero: an operator scanning this map
                    // should see what exists, not every combination that could.
                    byTypeAndStatus.put(type + "/" + status, count);
                }
            }
        }

        Optional<OffsetDateTime> oldest = jobs.findOldestCreatedAt(JobStatus.QUEUED);
        Long ageSeconds = oldest
                .map(at -> Duration.between(at, OffsetDateTime.now(clock)).toSeconds())
                .orElse(null);

        return new QueueSnapshot(
                jobs.countByStatus(JobStatus.QUEUED),
                jobs.countByStatus(JobStatus.RUNNING),
                jobs.countByStatus(JobStatus.SUCCEEDED),
                jobs.countByStatus(JobStatus.FAILED),
                jobs.countByStatus(JobStatus.DEAD),
                Map.copyOf(byTypeAndStatus),
                oldest.orElse(null),
                ageSeconds,
                jobs.totalAttempts());
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobSummary> deadJobs(int limit) {
        return jobs.findByStatusOrderByUpdatedAtDesc(JobStatus.DEAD).stream()
                .limit(Math.max(1, limit))
                .map(DefaultJobOperations::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobSummary> retryingJobs(int limit) {
        return jobs.findRetrying(JobStatus.QUEUED).stream()
                .limit(Math.max(1, limit))
                .map(DefaultJobOperations::toSummary)
                .toList();
    }

    private static JobSummary toSummary(JobEntity job) {
        return new JobSummary(job.getId(), job.getJobType(), job.getDedupeKey(),
                job.getStatus(), job.getAttempts(), job.getMaxAttempts(),
                job.getRunAfter(), job.getUpdatedAt(), job.getLastError());
    }
}
