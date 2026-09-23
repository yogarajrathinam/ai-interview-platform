package com.aiinterview.interviewplatform.interview.api;

import java.util.UUID;

/**
 * Request to begin an attempt.
 *
 * <p>Identifies the template by <strong>family key</strong> rather than version
 * id: a candidate starts "the Java backend interview", and resolving that to
 * the version published right now is exactly what start-time pinning means.
 * From then on the attempt holds a version id and never re-resolves.
 *
 * @param resumeIfLive when true, an existing live attempt is returned instead
 *                     of being rejected — the behaviour a "Resume interview"
 *                     button needs, and the safe default for a retried request
 */
public record StartInterviewCommand(UUID candidateUserId, String templateKey,
                                    boolean resumeIfLive) {

    public StartInterviewCommand {
        if (candidateUserId == null) {
            throw new IllegalArgumentException("candidateUserId is required");
        }
        if (templateKey == null || templateKey.isBlank()) {
            throw new IllegalArgumentException("templateKey is required");
        }
    }

    public static StartInterviewCommand of(UUID candidateUserId, String templateKey) {
        return new StartInterviewCommand(candidateUserId, templateKey, true);
    }
}
