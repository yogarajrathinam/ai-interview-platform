package com.aiinterview.interviewplatform.evaluation.domain;

import com.aiinterview.interviewplatform.evaluation.api.Verdict;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The credit table and rounding rules — the parameters that decide what a score
 * <em>means</em>.
 *
 * <p>These are versioned together as {@link #VERSION}, which is stamped onto
 * every evaluation row. Changing any value here changes historical
 * comparability, so it is a deliberate, versioned act rather than a constant
 * edit: bump the version, and old evaluations keep recording the policy that
 * actually produced them.
 */
public final class ScoringPolicy {

    /** Stored in {@code evaluations.evaluation_version}. */
    public static final String VERSION = "eval-1.0.0";

    /** Scores are reported on a 0–10 scale. */
    public static final BigDecimal MAX_SCORE = BigDecimal.TEN;
    public static final BigDecimal MIN_SCORE = BigDecimal.ZERO;

    /** {@code numeric(4,2)} in the schema. */
    public static final int SCORE_SCALE = 2;

    /** {@code numeric(4,3)} in the schema. */
    public static final int CREDIT_SCALE = 3;

    /** Weights are integer basis points; a full set sums to this. */
    public static final int FULL_WEIGHT_BP = 10_000;

    /**
     * Below this, the grader is treated as unsure and its credit is pulled
     * toward the midpoint (see {@link #creditFor}).
     */
    public static final BigDecimal CONFIDENCE_FLOOR = new BigDecimal("0.5");

    private static final BigDecimal MET = BigDecimal.ONE;
    private static final BigDecimal PARTIAL = new BigDecimal("0.5");
    private static final BigDecimal MISSING = BigDecimal.ZERO;

    /**
     * A wrong claim costs more than silence, but not so much that one error
     * can drag a question below zero — the floor is applied at question level.
     */
    private static final BigDecimal CONTRADICTED = new BigDecimal("-0.25");

    private ScoringPolicy() {
    }

    /** Raw credit for a verdict, before any confidence adjustment. */
    public static BigDecimal baseCreditFor(Verdict verdict) {
        return switch (verdict) {
            case MET -> MET;
            case PARTIAL -> PARTIAL;
            case MISSING -> MISSING;
            case CONTRADICTED -> CONTRADICTED;
        };
    }

    /**
     * Credit for a verdict, shrunk toward the midpoint when the grader is
     * unsure.
     *
     * <p>{@code credit' = 0.5 + (credit - 0.5) x confidence / 0.5}, applied only
     * below {@link #CONFIDENCE_FLOOR}. An unsure grader therefore produces a
     * middling verdict rather than a confident wrong one — deliberately biased
     * toward the centre, because a wrongly harsh score destroys trust faster
     * than a slightly generous one.
     *
     * <p>A {@code null} confidence is treated as full confidence: the provider
     * declining to self-report must not silently soften every score.
     */
    public static BigDecimal creditFor(Verdict verdict, BigDecimal confidence) {
        BigDecimal base = baseCreditFor(verdict);
        if (confidence == null || confidence.compareTo(CONFIDENCE_FLOOR) >= 0) {
            return base.setScale(CREDIT_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal clamped = confidence.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        BigDecimal factor = clamped.divide(CONFIDENCE_FLOOR, 10, RoundingMode.HALF_UP);
        BigDecimal adjusted = PARTIAL.add(base.subtract(PARTIAL).multiply(factor));
        return adjusted.setScale(CREDIT_SCALE, RoundingMode.HALF_UP);
    }

    /** Rounds to the stored score scale, half-up. */
    public static BigDecimal roundScore(BigDecimal score) {
        return score.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    /** Clamps to the reportable range, then rounds. */
    public static BigDecimal clampAndRound(BigDecimal score) {
        return roundScore(score.max(MIN_SCORE).min(MAX_SCORE));
    }
}
