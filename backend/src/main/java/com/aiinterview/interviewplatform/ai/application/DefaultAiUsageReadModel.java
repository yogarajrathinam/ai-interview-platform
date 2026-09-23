package com.aiinterview.interviewplatform.ai.application;

import com.aiinterview.interviewplatform.ai.api.AiUsageReadModel;
import com.aiinterview.interviewplatform.ai.infrastructure.AiInvocationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aggregates the AI accounting table. Read-only; no second counter is kept. */
@Service
public class DefaultAiUsageReadModel implements AiUsageReadModel {

    private final AiInvocationRepository invocations;

    public DefaultAiUsageReadModel(AiInvocationRepository invocations) {
        this.invocations = invocations;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderUsage> usageSince(OffsetDateTime since) {
        return invocations.aggregateUsageSince(since).stream()
                .map(row -> new ProviderUsage(
                        (String) row[0],
                        (String) row[1],
                        toLong(row[2]),
                        toLong(row[3]),
                        toLong(row[4]),
                        toLong(row[5]),
                        toLong(row[6]),
                        toLong(row[7])))
                .toList();
    }

    /** JPA returns Long or BigInteger depending on the aggregate; normalise. */
    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
