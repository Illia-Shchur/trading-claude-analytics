package com.tradinganalytics.research.v5;

import static com.tradinganalytics.research.v5.LiquidationStructureRouterV1.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Draft only: paired-control and acknowledgement record contract vectors. */
class LiquidationStructureRouterPairedAcknowledgementContractMatrixV1Test {
    private static final Instant T = Instant.parse("2024-07-01T16:00:00Z");
    private static final String SERIES = "router-paired-ack-v1";

    @Test
    void controlArmRejectsEveryVariantAndPayloadIdentityMismatch() {
        RejectedOpportunity continuation = rejection(Variant.ALWAYS_CONTINUATION_CONTROL,
                Branch.CONTINUATION, Direction.SHORT);
        RejectedOpportunity reversal = rejection(Variant.ALWAYS_REVERSAL_CONTROL,
                Branch.REVERSAL, Direction.LONG);
        ControlArmOpportunity goodContinuation = new ControlArmOpportunity(
                Variant.ALWAYS_CONTINUATION_CONTROL, Branch.CONTINUATION, Direction.SHORT, null, continuation);
        ControlArmOpportunity goodReversal = new ControlArmOpportunity(
                Variant.ALWAYS_REVERSAL_CONTROL, Branch.REVERSAL, Direction.LONG, null, reversal);
        assertNotNull(goodContinuation.rejection());
        assertNotNull(goodReversal.rejection());

        assertArmFailure(() -> new ControlArmOpportunity(Variant.ROUTED_REVERSAL_CONTINUATION,
                Branch.CONTINUATION, Direction.SHORT, null, continuation),
                "paired arms must be one of the two frozen branch controls");
        assertArmFailure(() -> new ControlArmOpportunity(Variant.ALWAYS_CONTINUATION_CONTROL,
                Branch.CONTINUATION, Direction.SHORT, null, null),
                "a control arm must contain exactly one intent or rejection");
        assertArmFailure(() -> new ControlArmOpportunity(Variant.ALWAYS_CONTINUATION_CONTROL,
                Branch.CONTINUATION, Direction.SHORT, intent(Variant.ALWAYS_CONTINUATION_CONTROL,
                        Branch.CONTINUATION, Direction.SHORT), continuation),
                "a control arm must contain exactly one intent or rejection");

        assertArmFailure(() -> new ControlArmOpportunity(Variant.ALWAYS_CONTINUATION_CONTROL,
                Branch.CONTINUATION, Direction.SHORT, intent(Variant.ALWAYS_REVERSAL_CONTROL,
                        Branch.REVERSAL, Direction.LONG), null),
                "control intent does not match its forced-arm identity");
        assertArmFailure(() -> new ControlArmOpportunity(Variant.ALWAYS_CONTINUATION_CONTROL,
                Branch.CONTINUATION, Direction.SHORT, intent(Variant.ALWAYS_CONTINUATION_CONTROL,
                        Branch.REVERSAL, Direction.LONG), null),
                "control intent does not match its forced-arm identity");
        assertArmFailure(() -> new ControlArmOpportunity(Variant.ALWAYS_CONTINUATION_CONTROL,
                Branch.CONTINUATION, Direction.SHORT, intent(Variant.ALWAYS_CONTINUATION_CONTROL,
                        Branch.CONTINUATION, Direction.LONG), null),
                "control intent does not match its forced-arm identity");

        assertArmFailure(() -> new ControlArmOpportunity(Variant.ALWAYS_CONTINUATION_CONTROL,
                Branch.CONTINUATION, Direction.SHORT, null, reversal),
                "control rejection does not match its forced-arm identity");
        assertArmFailure(() -> new ControlArmOpportunity(Variant.ALWAYS_CONTINUATION_CONTROL,
                Branch.REVERSAL, Direction.LONG, null, rejection(Variant.ALWAYS_CONTINUATION_CONTROL,
                        Branch.CONTINUATION, Direction.SHORT)),
                "control rejection does not match its forced-arm identity");
        assertArmFailure(() -> new ControlArmOpportunity(Variant.ALWAYS_CONTINUATION_CONTROL,
                Branch.CONTINUATION, Direction.LONG, null, continuation),
                "control rejection does not match its forced-arm identity");
    }

    @Test
    void pairedResultRetainsOrderedContinuationAndReversalArmsOnly() {
        ControlArmOpportunity continuation = new ControlArmOpportunity(
                Variant.ALWAYS_CONTINUATION_CONTROL, Branch.CONTINUATION, Direction.SHORT, null,
                rejection(Variant.ALWAYS_CONTINUATION_CONTROL, Branch.CONTINUATION, Direction.SHORT));
        ControlArmOpportunity reversal = new ControlArmOpportunity(
                Variant.ALWAYS_REVERSAL_CONTROL, Branch.REVERSAL, Direction.LONG, null,
                rejection(Variant.ALWAYS_REVERSAL_CONTROL, Branch.REVERSAL, Direction.LONG));
        PairedControlResult valid = new PairedControlResult("pair", T, List.of(continuation, reversal));
        assertEquals(2, valid.arms().size());

        assertPairFailure(List.of());
        assertPairFailure(List.of(continuation));
        assertPairFailure(List.of(reversal, continuation));
        ControlArmOpportunity wrongFirstBranch = new ControlArmOpportunity(
                Variant.ALWAYS_CONTINUATION_CONTROL, Branch.REVERSAL, Direction.LONG, null,
                rejection(Variant.ALWAYS_CONTINUATION_CONTROL, Branch.REVERSAL, Direction.LONG));
        assertPairFailure(List.of(wrongFirstBranch, reversal));
        ControlArmOpportunity wrongSecondBranch = new ControlArmOpportunity(
                Variant.ALWAYS_REVERSAL_CONTROL, Branch.CONTINUATION, Direction.SHORT, null,
                rejection(Variant.ALWAYS_REVERSAL_CONTROL, Branch.CONTINUATION, Direction.SHORT));
        assertPairFailure(List.of(continuation, wrongSecondBranch));
        assertThrows(NullPointerException.class, () -> new PairedControlResult("pair", T, null));
    }

    @Test
    void pairedDecisionContextRejectsInvalidAnchorAndEveryPresentMidpointBoundary() {
        ConfirmedIntent routed = intent(Variant.ROUTED_REVERSAL_CONTINUATION, Branch.CONTINUATION, Direction.SHORT);
        assertDoesNotThrow(() -> new PairedDecisionContext(routed, null, List.of()));
        assertDoesNotThrow(() -> new PairedDecisionContext(routed, 100.0, List.of()));
        for (double invalid : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals("shared reversal midpoint must be finite and positive when present",
                    assertThrows(IllegalArgumentException.class,
                            () -> new PairedDecisionContext(routed, invalid, List.of())).getMessage());
        }
        assertEquals("paired-control context requires a routed stage-one anchor",
                assertThrows(IllegalArgumentException.class, () -> new PairedDecisionContext(
                        intent(Variant.ALWAYS_CONTINUATION_CONTROL, Branch.CONTINUATION, Direction.SHORT),
                        null, List.of())).getMessage());
        assertThrows(NullPointerException.class, () -> new PairedDecisionContext(null, null, List.of()));
        assertThrows(NullPointerException.class, () -> new PairedDecisionContext(routed, null, null));
    }

    @Test
    void routeResultCopiesEveryCollectionAndRejectsNullAtEachCanonicalSlot() {
        for (int nullSlot = 0; nullSlot < 7; nullSlot++) {
            int slot = nullSlot;
            assertThrows(NullPointerException.class, () -> routeWithNullSlot(slot),
                    "null collection slot " + nullSlot);
        }

        List<ConfirmedIntent> mutableIntents = new ArrayList<>(List.of(
                intent(Variant.ROUTED_REVERSAL_CONTINUATION, Branch.CONTINUATION, Direction.SHORT)));
        RouteResult frozen = routeResult(mutableIntents, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        mutableIntents.clear();
        assertEquals(1, frozen.intents().size());
        assertThrows(UnsupportedOperationException.class, () -> frozen.intents().clear());
    }

    @Test
    void acknowledgementRangesCoverBothSidesOfStageAndPositivePriceContracts() {
        assertDoesNotThrow(() -> new FillAck("i", "s", "BTC", 1, T, 100, 90));
        assertThrows(IllegalArgumentException.class, () -> new FillAck("i", "s", "BTC", 4, T, 100, 90));
        assertThrows(IllegalArgumentException.class, () -> new FillAck("i", "s", "BTC", 1, T, -1, 90));
        assertThrows(IllegalArgumentException.class, () -> new FillAck("i", "s", "BTC", 1, T, 100, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new FillAck("i", "s", "BTC", 1, T, 100, -1));

        assertDoesNotThrow(() -> new NoFillAck("i", "s", "BTC", 3, T, ""));
        assertThrows(IllegalArgumentException.class, () -> new NoFillAck("i", "s", "BTC", 0, T, "NO"));
        assertThrows(IllegalArgumentException.class, () -> new NoFillAck("i", "s", "BTC", 4, T, "NO"));

        assertDoesNotThrow(() -> new PendingCancellation("i", "s", "BTC", 3, T, "INVALIDATED", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PendingCancellation("i", "s", "BTC", 0, T, "INVALIDATED", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PendingCancellation("i", "s", "BTC", 3, T, " ", List.of()));

        assertDoesNotThrow(() -> new StopUpdateIntent("u", "s", "BTC", T,
                100, 99, Direction.LONG, true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StopUpdateIntent("u", "s", "BTC", T,
                100, Double.POSITIVE_INFINITY, Direction.LONG, true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StopUpdateIntent("u", "s", "BTC", T,
                100, -1, Direction.LONG, true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StopUpdateIntent("u", "s", "BTC", T,
                0, 99, Direction.LONG, true, List.of()));

        assertDoesNotThrow(() -> new QualifiedDailyStressEvent("event", "BTC", LocalDate.of(2024, 7, 1),
                T, T, Direction.SHORT, List.of()));
    }

    private static RejectedOpportunity rejection(Variant variant, Branch forced, Direction direction) {
        return new RejectedOpportunity("rejection", "setup", "pair", "BTC", variant,
                Branch.CONTINUATION, forced, direction, 1, T, "STRUCTURE", MacroState.NOT_REQUIRED,
                false, List.of());
    }

    private static ConfirmedIntent intent(Variant variant, Branch branch, Direction direction) {
        return new ConfirmedIntent("intent", "setup", "pair", "BTC", variant, branch,
                Branch.CONTINUATION, direction, Direction.SHORT, 1, T, T.plusNanos(1),
                T.minusSeconds(3600), 90, 90, 89, 91, null, null, null,
                2, 92, null, 1, .01, 2, 60, MacroState.NOT_REQUIRED, true,
                false, List.of(), List.of());
    }

    private static RouteResult routeWithNullSlot(int nullSlot) {
        List<ConfirmedIntent> intents = new ArrayList<>();
        List<RejectedOpportunity> rejections = new ArrayList<>();
        List<StopUpdateIntent> stops = new ArrayList<>();
        List<PairedDecisionContext> contexts = new ArrayList<>();
        List<PendingCancellation> cancellations = new ArrayList<>();
        List<QualifiedDailyStressEvent> events = new ArrayList<>();
        List<StageOneAnchorContext> anchors = new ArrayList<>();
        switch (nullSlot) {
            case 0 -> intents = null;
            case 1 -> rejections = null;
            case 2 -> stops = null;
            case 3 -> contexts = null;
            case 4 -> cancellations = null;
            case 5 -> events = null;
            case 6 -> anchors = null;
            default -> throw new AssertionError("slot outside the canonical RouteResult shape");
        }
        return routeResult(intents, rejections, stops, contexts, cancellations, events, anchors);
    }

    private static RouteResult routeResult(List<ConfirmedIntent> intents,
            List<RejectedOpportunity> rejections, List<StopUpdateIntent> stops,
            List<PairedDecisionContext> contexts, List<PendingCancellation> cancellations,
            List<QualifiedDailyStressEvent> events, List<StageOneAnchorContext> anchors) {
        return new RouteResult(intents, rejections, stops, contexts, cancellations, events, anchors);
    }

    private static void assertArmFailure(Executable construction, String expectedMessage) {
        assertEquals(expectedMessage, assertThrows(IllegalArgumentException.class, construction).getMessage());
    }

    private static void assertPairFailure(List<ControlArmOpportunity> arms) {
        assertEquals("paired result must retain continuation then reversal control arms",
                assertThrows(IllegalArgumentException.class,
                        () -> new PairedControlResult("pair", T, arms)).getMessage());
    }
}
