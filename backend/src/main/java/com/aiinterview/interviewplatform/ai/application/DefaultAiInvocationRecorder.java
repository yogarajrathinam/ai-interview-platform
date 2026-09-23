package com.aiinterview.interviewplatform.ai.application;

import com.aiinterview.interviewplatform.ai.api.AiInvocation;
import com.aiinterview.interviewplatform.ai.api.AiInvocationRecorder;
import com.aiinterview.interviewplatform.ai.domain.AiInvocationEntity;
import com.aiinterview.interviewplatform.ai.infrastructure.AiInvocationRepository;
import com.aiinterview.interviewplatform.shared.id.IdGenerator;
import com.aiinterview.interviewplatform.shared.web.TraceIdFilter;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes accounting rows for provider calls.
 *
 * <p>Two deliberate properties:
 *
 * <p><strong>It never propagates a failure.</strong> Losing a cost row is
 * annoying; failing an evaluation the candidate is waiting on because the
 * accounting insert hiccupped is not acceptable. Problems are logged and null
 * is returned.
 *
 * <p><strong>It runs in its own transaction</strong>
 * ({@code REQUIRES_NEW}). A provider failure typically rolls the caller's
 * transaction back, and the record of that failure is exactly what we must keep.
 * Sharing the caller's transaction would discard the evidence of every failed
 * call — the opposite of what this table is for.
 */
@Service
public class DefaultAiInvocationRecorder implements AiInvocationRecorder {

    private static final Logger log = LoggerFactory.getLogger(DefaultAiInvocationRecorder.class);

    private final AiInvocationRepository repository;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public DefaultAiInvocationRecorder(AiInvocationRepository repository,
                                       IdGenerator idGenerator, Clock clock) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID record(AiInvocation invocation) {
        try {
            AiInvocationEntity entity = AiInvocationEntity.create(
                    idGenerator.newId(),
                    AiInvocationEntity.Purpose.valueOf(invocation.purpose().name()),
                    invocation.provider(),
                    invocation.model(),
                    invocation.promptVersion(),
                    invocation.requestFingerprint(),
                    AiInvocationEntity.Status.valueOf(invocation.status().name()),
                    invocation.httpStatus() == null ? null : invocation.httpStatus().shortValue(),
                    invocation.latencyMs(),
                    invocation.inputTokens(),
                    invocation.outputTokens(),
                    invocation.costMicros(),
                    (short) invocation.retryIndex(),
                    invocation.rawResponseJson(),
                    invocation.errorDetail(),
                    invocation.interviewId(),
                    invocation.answerId(),
                    invocation.userId(),
                    MDC.get(TraceIdFilter.MDC_KEY),
                    OffsetDateTime.now(clock));

            return repository.save(entity).getId();
        } catch (RuntimeException e) {
            // Deliberately swallowed: accounting must never sink an evaluation.
            log.error("Failed to record AI invocation for provider={} model={} status={}",
                    invocation.provider(), invocation.model(), invocation.status(), e);
            return null;
        }
    }
}
