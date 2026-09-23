package com.aiinterview.interviewplatform.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One AI provider invocation — including the ones that failed.
 *
 * <p>Named for what it records. "Usage" would imply successful consumption;
 * a timeout costs latency and sometimes money, and must be visible.
 *
 * <p>This is the unit-economics fact table: cost per interview, cost per
 * evaluation, tokens in/out, model and provider mix, and failure rates all
 * come from here. {@code costMicros} is an integer count of micro-USD —
 * money is never a floating-point number.
 *
 * <p>{@code rawResponse} contains candidate answer text and is therefore
 * nulled 30 days after creation by the MAINTENANCE job; the accounting row
 * itself survives, which is why {@code provider} and {@code model} are also
 * copied onto the evaluation.
 */
@Entity
@Table(name = "ai_invocations", schema = "app")
public class AiInvocationEntity {

    public enum Purpose { ANSWER_EVALUATION, REPORT_SUMMARY }

    public enum Status { SUCCEEDED, TIMEOUT, PROVIDER_ERROR, RATE_LIMITED, INVALID_OUTPUT }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false)
    private Purpose purpose;

    @Column(name = "provider", nullable = false)
    private String provider;

    /** The exact pinned model id — never an alias such as "latest". */
    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    /** sha256 of prompt template + model + inputs. Duplicate detection. */
    @Column(name = "request_fingerprint", nullable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "http_status")
    private Short httpStatus;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    /** Integer micro-USD. */
    @Column(name = "cost_micros")
    private Long costMicros;

    @Column(name = "retry_index", nullable = false)
    private Short retryIndex;

    /** Provider-shaped, never queried by field. Purged after 30 days. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private String rawResponse;

    @Column(name = "error_detail")
    private String errorDetail;

    // Correlation columns. Nullable, ON DELETE SET NULL: accounting must
    // survive deletion of the content it refers to.
    @Column(name = "interview_id")
    private UUID interviewId;

    @Column(name = "answer_id")
    private UUID answerId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AiInvocationEntity() {
        // for JPA
    }

    /**
     * Creates a row for one completed call attempt.
     *
     * <p>Package-private construction is deliberate: invocations are written by
     * the ai module's recorder and nowhere else, so the table cannot drift out
     * of step with the port that owns it. Token and cost arguments must be null
     * when not genuinely known — zeros would corrupt the unit-economics figures
     * this table exists to answer.
     */
    public static AiInvocationEntity create(
            UUID id, Purpose purpose, String provider, String model, String promptVersion,
            String requestFingerprint, Status status, Short httpStatus, Integer latencyMs,
            Integer inputTokens, Integer outputTokens, Long costMicros, short retryIndex,
            String rawResponse, String errorDetail, UUID interviewId, UUID answerId,
            UUID userId, String traceId, OffsetDateTime createdAt) {

        AiInvocationEntity entity = new AiInvocationEntity();
        entity.id = id;
        entity.purpose = purpose;
        entity.provider = provider;
        entity.model = model;
        entity.promptVersion = promptVersion;
        entity.requestFingerprint = requestFingerprint;
        entity.status = status;
        entity.httpStatus = httpStatus;
        entity.latencyMs = latencyMs;
        entity.inputTokens = inputTokens;
        entity.outputTokens = outputTokens;
        entity.costMicros = costMicros;
        entity.retryIndex = retryIndex;
        entity.rawResponse = rawResponse;
        entity.errorDetail = errorDetail;
        entity.interviewId = interviewId;
        entity.answerId = answerId;
        entity.userId = userId;
        entity.traceId = traceId;
        entity.createdAt = createdAt;
        return entity;
    }

    public UUID getId() { return id; }
    public Purpose getPurpose() { return purpose; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public Status getStatus() { return status; }
    public Short getHttpStatus() { return httpStatus; }
    public Integer getLatencyMs() { return latencyMs; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public Long getCostMicros() { return costMicros; }
    public Short getRetryIndex() { return retryIndex; }
    public String getRawResponse() { return rawResponse; }
    public String getErrorDetail() { return errorDetail; }
    public UUID getInterviewId() { return interviewId; }
    public UUID getAnswerId() { return answerId; }
    public UUID getUserId() { return userId; }
    public String getTraceId() { return traceId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
