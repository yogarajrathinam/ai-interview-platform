package com.aiinterview.interviewplatform.evaluation.api;

/**
 * The judgement a grader returns for one rubric criterion.
 *
 * <p>Four values, not a score. The provider's job is reading comprehension —
 * "does this answer assert the claim?" — and the backend turns the answers into
 * a number. That inversion is what makes the score reproducible.
 *
 * <p>{@link #CONTRADICTED} is deliberately distinct from {@link #MISSING}:
 * asserting something false is worse than omitting it, and the two warrant
 * different credit and very different feedback.
 */
public enum Verdict {

    /** The answer clearly asserts the claim. */
    MET,

    /** Gestures at it — imprecise, incomplete or hedged. */
    PARTIAL,

    /** Not addressed at all. */
    MISSING,

    /** The answer asserts something incompatible with the claim. */
    CONTRADICTED;

    /**
     * The verdict one step weaker than this one, used when evidence fails
     * validation (gate G4). A grader that cannot quote the answer cannot have
     * read the claim there, so the credit is reduced rather than trusted.
     *
     * <p>{@code CONTRADICTED} degrades to {@code MISSING} rather than to
     * {@code PARTIAL}: an unevidenced accusation of error must not cost the
     * candidate anything.
     */
    public Verdict downgraded() {
        return switch (this) {
            case MET -> PARTIAL;
            case PARTIAL, MISSING, CONTRADICTED -> MISSING;
        };
    }

    /** Whether a verdict of this kind is required to quote the answer. */
    public boolean requiresEvidence() {
        return this == MET || this == PARTIAL || this == CONTRADICTED;
    }
}
