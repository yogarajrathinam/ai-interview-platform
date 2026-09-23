package com.aiinterview.interviewplatform.ai.api;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Operational read access to AI accounting.
 *
 * <p>Reads the {@code ai_invocations} rows the recorder already writes. There
 * is deliberately no second accounting system: a separate counter would drift
 * from the table, and the table is the one an auditor would ask to see.
 *
 * <p>Answers "which provider failed, and how often?" — the question that
 * matters when grading starts going wrong and it is unclear whether the fault
 * is ours or the vendor's.
 */
public interface AiUsageReadModel {

    /** Usage grouped by provider and model over a window. */
    List<ProviderUsage> usageSince(OffsetDateTime since);

    /**
     * @param failed      calls that did not return a usable result
     * @param costMicros  integer micro-USD; null costs contribute nothing
     *                    rather than being counted as zero spend
     */
    record ProviderUsage(String provider, String model, long total, long succeeded,
                         long failed, long costMicros, Long inputTokens, Long outputTokens) {

        /** Share of calls that failed, as a percentage; 0 when nothing ran. */
        public double failureRatePercent() {
            return total == 0 ? 0.0 : (failed * 100.0) / total;
        }
    }
}
