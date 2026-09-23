package com.aiinterview.interviewplatform.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiinterview.interviewplatform.evaluation.api.EvaluationPhase;
import com.aiinterview.interviewplatform.evaluation.api.EvaluationStatus;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The evaluation lifecycle.
 *
 * <p>The phase is derived, never stored — the approved schema already carries
 * every fact it needs, and a stored copy would be a second source of truth able
 * to disagree with the evaluation rows themselves.
 */
@DisplayName("Evaluation lifecycle")
class EvaluationPhaseTest {

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
            "PENDING,PROCESSING",
            "PROCESSING,COMPLETED",
            "PROCESSING,FAILED",
            // Re-evaluation: append-only, audited, and explicitly permitted.
            "COMPLETED,PROCESSING",
            "FAILED,PROCESSING"})
    @DisplayName("legal transitions")
    void legalTransitions(EvaluationPhase from, EvaluationPhase to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @CsvSource({
            // Grading cannot be skipped.
            "PENDING,COMPLETED",
            "PENDING,FAILED",
            // An answer awaiting its first grading is a state that cannot recur.
            "PROCESSING,PENDING",
            "COMPLETED,PENDING",
            "FAILED,PENDING",
            // A terminal result is replaced by re-running, never edited in place.
            "COMPLETED,FAILED",
            "FAILED,COMPLETED"})
    @DisplayName("illegal transitions")
    void illegalTransitions(EvaluationPhase from, EvaluationPhase to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(EvaluationPhase.class)
    @DisplayName("no phase transitions to itself")
    void selfTransitionIsNotATransition(EvaluationPhase phase) {
        assertThat(phase.canTransitionTo(phase)).isFalse();
        assertThat(phase.canTransitionTo(null)).isFalse();
    }

    @Test
    @DisplayName("terminal phases are exactly COMPLETED and FAILED")
    void terminalPhases() {
        Set<EvaluationPhase> terminal = EnumSet.noneOf(EvaluationPhase.class);
        for (EvaluationPhase phase : EvaluationPhase.values()) {
            if (phase.isTerminal()) {
                terminal.add(phase);
            }
        }
        assertThat(terminal)
                .containsExactlyInAnyOrder(EvaluationPhase.COMPLETED, EvaluationPhase.FAILED);
    }

    @Test
    @DisplayName("an answer with no current evaluation is PENDING")
    void derivationWithoutEvaluation() {
        assertThat(EvaluationPhase.of(false, null)).isEqualTo(EvaluationPhase.PENDING);
        // A status without a current row must not be believed.
        assertThat(EvaluationPhase.of(false, EvaluationStatus.SUCCEEDED))
                .isEqualTo(EvaluationPhase.PENDING);
    }

    @ParameterizedTest(name = "{0} derives {1}")
    @CsvSource({
            "SUCCEEDED,COMPLETED",
            "FAILED_VALIDATION,FAILED",
            "FAILED_PROVIDER,FAILED"})
    @DisplayName("phase derives from the current evaluation's status")
    void derivationFromStatus(EvaluationStatus status, EvaluationPhase expected) {
        assertThat(EvaluationPhase.of(true, status)).isEqualTo(expected);
    }
}
