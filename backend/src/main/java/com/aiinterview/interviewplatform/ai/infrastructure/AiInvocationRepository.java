package com.aiinterview.interviewplatform.ai.infrastructure;

import com.aiinterview.interviewplatform.ai.domain.AiInvocationEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Write and aggregate access to the AI accounting table. */
public interface AiInvocationRepository extends JpaRepository<AiInvocationEntity, UUID> {

    /**
     * Total spend for one interview, in micro-USD.
     *
     * <p>Rows with a null cost — a local provider, or a failure before billing —
     * contribute nothing rather than breaking the sum.
     */
    @Query("select coalesce(sum(a.costMicros), 0) from AiInvocationEntity a "
            + "where a.interviewId = :interviewId")
    long totalCostMicrosForInterview(UUID interviewId);

    long countByInterviewId(UUID interviewId);

    /**
     * Usage grouped by provider and model.
     *
     * <p>Aggregated in SQL rather than by loading rows: this table grows with
     * every call ever made, and pulling it into memory to count would stop
     * working exactly when the numbers start mattering.
     *
     * <p>{@code coalesce} on the sums so a null cost or token count — which is
     * the honest value for a local provider — contributes nothing instead of
     * nulling the whole aggregate.
     */
    @Query("""
            select a.provider,
                   a.model,
                   count(a),
                   sum(case when a.status = com.aiinterview.interviewplatform.ai.domain
                                            .AiInvocationEntity.Status.SUCCEEDED
                            then 1L else 0L end),
                   sum(case when a.status <> com.aiinterview.interviewplatform.ai.domain
                                             .AiInvocationEntity.Status.SUCCEEDED
                            then 1L else 0L end),
                   coalesce(sum(a.costMicros), 0),
                   coalesce(sum(a.inputTokens), 0),
                   coalesce(sum(a.outputTokens), 0)
              from AiInvocationEntity a
             where a.createdAt >= :since
             group by a.provider, a.model
             order by count(a) desc
            """)
    List<Object[]> aggregateUsageSince(@Param("since") OffsetDateTime since);
}
