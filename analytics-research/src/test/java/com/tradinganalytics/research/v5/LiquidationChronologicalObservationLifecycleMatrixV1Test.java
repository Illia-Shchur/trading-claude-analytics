package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Covers independent timestamp-presence invariants for the four chronological outcome states. */
class LiquidationChronologicalObservationLifecycleMatrixV1Test {
    private static final Instant DECISION = Instant.parse("2024-02-01T12:00:00Z");
    private static final Instant FILL = DECISION.plusSeconds(60);
    private static final Instant EXIT = FILL.plusSeconds(120);

    @Test
    void acceptedStateRepresentativesHaveOnlyTheirContractualTimestampShapes() {
        assertDoesNotThrow(() -> new LiquidationChronologicalPolicyV1.Observation(
                "unresolved", DECISION, LiquidationChronologicalPolicyV1.OutcomeState.UNRESOLVED_NO_FILL,
                null, null, null));
        assertDoesNotThrow(() -> new LiquidationChronologicalPolicyV1.Observation(
                "open", DECISION, LiquidationChronologicalPolicyV1.OutcomeState.OPEN_TRADE,
                null, FILL, null));
        assertDoesNotThrow(() -> new LiquidationChronologicalPolicyV1.Observation(
                "resolved-zero", DECISION, LiquidationChronologicalPolicyV1.OutcomeState.RESOLVED_NO_TRADE,
                EXIT, null, null));
        assertDoesNotThrow(() -> new LiquidationChronologicalPolicyV1.Observation(
                "closed", DECISION, LiquidationChronologicalPolicyV1.OutcomeState.CLOSED_TRADE,
                EXIT, FILL, EXIT));
    }

    @Test
    void openAndResolvedZeroStatesRejectEachForbiddenTimestampIndependently() {
        var open = LiquidationChronologicalPolicyV1.OutcomeState.OPEN_TRADE;
        assertThrows(IllegalArgumentException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("open-available", DECISION, open, FILL, FILL, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("open-exit", DECISION, open, null, FILL, EXIT));
        assertThrows(IllegalArgumentException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("open-no-fill", DECISION, open, null, null, null));

        var zero = LiquidationChronologicalPolicyV1.OutcomeState.RESOLVED_NO_TRADE;
        assertThrows(IllegalArgumentException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("zero-no-resolution", DECISION, zero, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("zero-fill", DECISION, zero, EXIT, FILL, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("zero-exit", DECISION, zero, EXIT, null, EXIT));
    }

    @Test
    void closedStateRequiresAResolvedExitAndAvailabilityAfterItsLifecycle() {
        var closed = LiquidationChronologicalPolicyV1.OutcomeState.CLOSED_TRADE;
        assertThrows(IllegalArgumentException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("closed-no-availability", DECISION, closed, null, FILL, EXIT));
        assertThrows(IllegalArgumentException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("closed-no-fill", DECISION, closed, EXIT, null, EXIT));
        assertThrows(IllegalArgumentException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("closed-no-exit", DECISION, closed, EXIT, FILL, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("closed-availability-before-exit", DECISION,
                        closed, EXIT.minusNanos(1), FILL, EXIT));
        assertThrows(IllegalArgumentException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("closed-exit-before-fill", DECISION,
                        closed, EXIT, EXIT, FILL));
        assertThrows(IllegalArgumentException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("closed-fill-before-decision", DECISION,
                        closed, EXIT, DECISION.minusSeconds(1), EXIT));
    }
}
