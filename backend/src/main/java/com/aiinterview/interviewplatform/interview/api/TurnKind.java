package com.aiinterview.interviewplatform.interview.api;

/**
 * Whether a turn is part of the planned interview or a probe into an earlier
 * answer.
 *
 * <p>The database enforces the whole shape difference through
 * {@code ck_iq_shape}: a CORE turn has no parent, carries positive weight and
 * no prompt override; a FOLLOW_UP turn has a parent, a targeted rubric
 * criterion, its own prompt text, and <strong>zero weight</strong>.
 *
 * <p>The zero weight is the important part. A follow-up re-probes a criterion
 * the candidate under-answered; giving it independent weight would let a topic
 * they struggled with dominate the score purely because they were asked about
 * it twice.
 */
public enum TurnKind {

    /** Planned from a template slot at interview start. */
    CORE,

    /** Created after the core phase to probe a specific weak criterion. */
    FOLLOW_UP;

    public boolean carriesWeight() {
        return this == CORE;
    }
}
