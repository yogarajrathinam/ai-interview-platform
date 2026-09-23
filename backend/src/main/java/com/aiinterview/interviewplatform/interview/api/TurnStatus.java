package com.aiinterview.interviewplatform.interview.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * The state of one turn, as stored in {@code interview_questions.status}.
 *
 * <p>Each value earns its place by being something the system must be able to
 * tell apart:
 *
 * <ul>
 *   <li>{@link #PENDING} vs {@link #ASKED} — materialised but never shown,
 *       versus shown and unanswered. Without the distinction "candidates
 *       abandon at question five" is unanswerable.</li>
 *   <li>{@link #ANSWERED} vs {@link #EVALUATED} — answered but not yet graded
 *       drives the "6 of 8 evaluated" progress and the completion condition.</li>
 *   <li>{@link #SKIPPED} vs {@link #EVAL_FAILED} — semantically opposite. A
 *       skip scores zero and counts; a grading failure is excluded from the
 *       average entirely, because our outage is not the candidate's fault.</li>
 * </ul>
 *
 * <p>There is no {@code EVALUATING}: nothing in the product distinguishes
 * queued from running, and the job row already knows.
 */
public enum TurnStatus {

    /** Planned at interview start, not yet served. */
    PENDING,

    /** Served to the candidate; {@code asked_at} is set. */
    ASKED,

    /** An answer exists and is awaiting grading. */
    ANSWERED,

    /** The candidate declined. Scores zero and is counted. */
    SKIPPED,

    /** Graded successfully. */
    EVALUATED,

    /** Grading failed terminally. Excluded from scoring; re-drivable. */
    EVAL_FAILED;

    private static final Set<TurnStatus> TERMINAL =
            EnumSet.of(SKIPPED, EVALUATED, EVAL_FAILED);

    /** No further candidate action is possible or expected. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Whether this turn still owes the candidate something. */
    public boolean isOpen() {
        return this == PENDING || this == ASKED;
    }

    /** An answer exists, whatever happened to it afterwards. */
    public boolean hasAnswer() {
        return this == ANSWERED || this == EVALUATED || this == EVAL_FAILED;
    }

    /** Grading has settled, successfully or not. */
    public boolean isGradingSettled() {
        return this == EVALUATED || this == EVAL_FAILED || this == SKIPPED;
    }
}
