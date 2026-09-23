package com.aiinterview.interviewplatform.ai.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Records every call to a model provider — including the ones that failed.
 *
 * <p>The ai module owns {@code ai_invocations}; this is how anything else
 * writes to it. Keeping it behind a port means the evaluation engine never
 * knows how accounting is stored, and that the table stays the single place
 * unit economics can be answered from: cost per interview, cost per evaluation,
 * token mix, model mix, failure rate.
 *
 * <p>Failures are recorded, not just successes. A timeout still consumed
 * latency and sometimes money, and a provider that fails 30% of the time is
 * something we must be able to see.
 */
public interface AiInvocationRecorder {

    /**
     * Writes one invocation and returns its id.
     *
     * <p>Never throws for an accounting problem: losing a cost row must not
     * fail an evaluation that otherwise succeeded. Implementations log and
     * return null if they cannot write.
     */
    UUID record(AiInvocation invocation);

    /**
     * A fingerprint of what was asked, for duplicate detection and as a cache
     * key when re-running an evaluation.
     *
     * <p>Hashing rather than storing the inputs is deliberate: the answer text
     * is personal data, and this column exists for correlation, not content.
     */
    static String fingerprint(String promptVersion, String model, UUID questionVersionId,
                              String answerText) {
        String source = String.join("\u0000",
                promptVersion == null ? "" : promptVersion,
                model == null ? "" : model,
                questionVersionId == null ? "" : questionVersionId.toString(),
                answerText == null ? "" : answerText.strip());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK specification", e);
        }
    }
}
