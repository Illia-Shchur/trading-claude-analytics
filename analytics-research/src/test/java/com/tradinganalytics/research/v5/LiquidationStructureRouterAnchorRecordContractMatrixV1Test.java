package com.tradinganalytics.research.v5;

import static com.tradinganalytics.research.v5.LiquidationStructureRouterV1.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Independent constructor-boundary vectors for frozen staged-router anchors. */
class LiquidationStructureRouterAnchorRecordContractMatrixV1Test {
    private static final Instant DECISION = Instant.parse("2024-07-01T16:00:00Z");
    private static final Instant CONFIRM_START = DECISION.minus(Duration.ofHours(1));
    private static final Instant SEED_END = DECISION.minus(Duration.ofHours(4));
    private static final Instant SEED_AVAILABLE = SEED_END;
    private static final String SERIES = "anchor-contract-v1";

    @ParameterizedTest(name = "seed rejects {0}")
    @MethodSource("invalidSeedCases")
    void eachIndependentSeedGeometryInvariantRejectsItsOwnMutation(
            String description, Supplier<AnchorSetupSeed> mutatedSeed) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> mutatedSeed.get());
        assertEquals("anchor setup seed geometry is invalid", failure.getMessage(), description);
    }

    static Stream<Arguments> invalidSeedCases() {
        return Stream.of(
                seedCase("blank setup identity", " ", DECISION.minusSeconds(1), DECISION, DECISION,
                        2, 110, 90, 90, 100, 89.5, 90.5, true, false, "FAST", List.of(geometryEvidence())),
                seedCase("empty event interval", "setup", SEED_END, SEED_END, SEED_END,
                        2, 110, 90, 90, 100, 89.5, 90.5, true, false, "FAST", List.of(geometryEvidence())),
                seedCase("availability before event end", "setup", SEED_END.minusSeconds(1), SEED_END,
                        SEED_END.minusNanos(1), 2, 110, 90, 90, 100, 89.5, 90.5,
                        true, false, "FAST", List.of(geometryEvidence())),
                seedCase("nonpositive ATR", "setup", SEED_START, SEED_END, SEED_AVAILABLE,
                        0, 110, 90, 90, 100, 89.5, 90.5, true, false, "FAST", List.of(geometryEvidence())),
                seedCase("nonpositive prior high", "setup", SEED_START, SEED_END, SEED_AVAILABLE,
                        2, 0, 90, 90, 100, 89.5, 90.5, true, false, "FAST", List.of(geometryEvidence())),
                seedCase("nonpositive prior low", "setup", SEED_START, SEED_END, SEED_AVAILABLE,
                        2, 110, 0, 90, 100, 89.5, 90.5, true, false, "FAST", List.of(geometryEvidence())),
                seedCase("inverted prior range", "setup", SEED_START, SEED_END, SEED_AVAILABLE,
                        2, 80, 90, 90, 100, 89.5, 90.5, true, false, "FAST", List.of(geometryEvidence())),
                seedCase("nonpositive boundary", "setup", SEED_START, SEED_END, SEED_AVAILABLE,
                        2, 110, 90, 0, 100, 89.5, 90.5, true, false, "FAST", List.of(geometryEvidence())),
                seedCase("nonpositive recovery target", "setup", SEED_START, SEED_END, SEED_AVAILABLE,
                        2, 110, 90, 90, 0, 89.5, 90.5, true, false, "FAST", List.of(geometryEvidence())),
                seedCase("nonpositive lower zone", "setup", SEED_START, SEED_END, SEED_AVAILABLE,
                        2, 110, 90, 90, 100, 0, 90.5, true, false, "FAST", List.of(geometryEvidence())),
                seedCase("nonpositive upper zone", "setup", SEED_START, SEED_END, SEED_AVAILABLE,
                        2, 110, 90, 90, 100, 89.5, 0, true, false, "FAST", List.of(geometryEvidence())),
                seedCase("inverted zone", "setup", SEED_START, SEED_END, SEED_AVAILABLE,
                        2, 110, 90, 90, 100, 91, 90.5, true, false, "FAST", List.of(geometryEvidence())),
                seedCase("no qualifying timeframe", "setup", SEED_START, SEED_END, SEED_AVAILABLE,
                        2, 110, 90, 90, 100, 89.5, 90.5, false, false, "FAST", List.of(geometryEvidence())),
                seedCase("fast geometry label mismatch", "setup", SEED_START, SEED_END, SEED_AVAILABLE,
                        2, 110, 90, 90, 100, 89.5, 90.5, true, false, "SLOW", List.of(geometryEvidence())),
                seedCase("slow-only geometry label mismatch", "setup", SEED_START, SEED_END, SEED_AVAILABLE,
                        2, 110, 90, 90, 100, 89.5, 90.5, false, true, "FAST", List.of(geometryEvidence())),
                seedCase("missing geometry evidence", "setup", SEED_START, SEED_END, SEED_AVAILABLE,
                        2, 110, 90, 90, 100, 89.5, 90.5, true, false, "FAST", List.of())
        );
    }

    @Test
    void slowOnlySeedRemainsAValidFrozenGeometryChoice() {
        AnchorSetupSeed slow = seed("setup", SEED_START, SEED_END, SEED_AVAILABLE,
                2, 110, 90, 90, 100, 89.5, 90.5, false, true, "SLOW", List.of(geometryEvidence()));
        assertEquals("SLOW", slow.selectedGeometry());
        assertFalse(slow.fastQualifies());
        assertTrue(slow.slowQualifies());
    }

    @Test
    void stageOneAnchorRejectsEachIndependentFrozenIntentBindingMutation() {
        StageOneAnchorContext baseline = anchor();
        assertEquals("setup", baseline.setupSeed().setupId());
        assertEquals(DECISION, baseline.intent().decisionTime());

        expectAnchorMismatch(edit(d -> d.variant = Variant.ALWAYS_CONTINUATION_CONTROL), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.stage = 2), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.diagnosticOnly = true), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.setupId = "other-setup"), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.asset = "ETH"), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> { d.shockDirection = Direction.LONG; d.direction = Direction.LONG; }), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.direction = Direction.LONG), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.reversalTarget = 100.0), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> { d.branch = Branch.REVERSAL; d.direction = Direction.LONG; }), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> { d.branch = Branch.REVERSAL; d.direction = Direction.LONG; d.reversalTarget = 101.0; }), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> {
            d.decisionTime = SEED_AVAILABLE;
            d.requestedExecutionAfter = SEED_AVAILABLE.plusNanos(1);
            d.sourceEvidence = List.of(confirmationEvidence(SEED_AVAILABLE));
        }), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.sourceEvidence = List.of()), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.preEventAtr = 3.0), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.zoneLower = 89.4), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.zoneUpper = 90.6), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.trancheRiskFraction = 0.02), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.proposedLeverage = 3), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.maximumHoldingDays = 59), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.macroState = MacroState.UNKNOWN), baseline.setupSeed());
        expectAnchorMismatch(edit(d -> d.macroEligible = false), baseline.setupSeed());

        assertThrows(NullPointerException.class, () -> new StageOneAnchorContext(null, baseline.setupSeed()));
        assertThrows(NullPointerException.class, () -> new StageOneAnchorContext(baseline.intent(), null));
    }

    @Test
    void anchorRejectsIntentEvidenceAndSeedEvidenceThatArrivesAfterDecision() {
        StageOneAnchorContext baseline = anchor();
        SourceEvidence future = new SourceEvidence("LATE_AUDIT", "BTC", SERIES,
                DECISION.plusSeconds(1), DECISION.plusSeconds(2));
        IntentDraft futureIntent = new IntentDraft(baseline.intent());
        futureIntent.sourceEvidence = List.of(confirmationEvidence(DECISION), future);
        IllegalArgumentException intentFailure = assertThrows(IllegalArgumentException.class,
                () -> new StageOneAnchorContext(futureIntent.build(), baseline.setupSeed()));
        assertEquals("stage-one anchor evidence cannot become available after its decision", intentFailure.getMessage());

        AnchorSetupSeed lateSeed = seed("setup", SEED_START, SEED_END, SEED_AVAILABLE,
                2, 110, 90, 90, 100, 89.5, 90.5, true, false, "FAST", List.of(future));
        IllegalArgumentException seedFailure = assertThrows(IllegalArgumentException.class,
                () -> new StageOneAnchorContext(baseline.intent(), lateSeed));
        assertEquals("stage-one anchor evidence cannot become available after its decision", seedFailure.getMessage());
    }

    private static final Instant SEED_START = DECISION.minus(Duration.ofDays(1));

    private static Arguments seedCase(String name, String setupId, Instant eventStart, Instant eventEnd,
            Instant availableAt, double atr, double priorHigh, double priorLow, double boundary,
            double recovery, double zoneLower, double zoneUpper, boolean fast, boolean slow,
            String selected, List<SourceEvidence> evidence) {
        return Arguments.of(name, (Supplier<AnchorSetupSeed>) () -> seed(setupId, eventStart, eventEnd,
                availableAt, atr, priorHigh, priorLow, boundary, recovery, zoneLower, zoneUpper,
                fast, slow, selected, evidence));
    }

    private static AnchorSetupSeed seed(String setupId, Instant eventStart, Instant eventEnd,
            Instant availableAt, double atr, double priorHigh, double priorLow, double boundary,
            double recovery, double zoneLower, double zoneUpper, boolean fast, boolean slow,
            String selected, List<SourceEvidence> evidence) {
        return new AnchorSetupSeed(setupId, "BTC", Direction.SHORT, eventStart, eventEnd, availableAt,
                atr, priorHigh, priorLow, boundary, recovery, zoneLower, zoneUpper, fast, slow, selected, evidence);
    }

    private static StageOneAnchorContext anchor() {
        ConfirmedIntent intent = new IntentDraft().build();
        AnchorSetupSeed seed = seed("setup", SEED_START, SEED_END, SEED_AVAILABLE,
                2, 110, 90, 90, 100, 89.5, 90.5, true, false, "FAST", List.of(geometryEvidence()));
        return new StageOneAnchorContext(intent, seed);
    }

    private static SourceEvidence geometryEvidence() {
        return new SourceEvidence("GEOMETRY", "BTC", SERIES, SEED_START, SEED_START);
    }

    private static SourceEvidence confirmationEvidence(Instant available) {
        return new SourceEvidence("CONFIRMATION", "BTC", SERIES, DECISION, available);
    }

    private static ConfirmedIntent edit(Consumer<IntentDraft> edit) {
        IntentDraft draft = new IntentDraft();
        edit.accept(draft);
        return draft.build();
    }

    private static void expectAnchorMismatch(ConfirmedIntent intent, AnchorSetupSeed seed) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new StageOneAnchorContext(intent, seed));
        assertEquals("stage-one anchor and immutable setup seed disagree", failure.getMessage());
    }

    private static final class IntentDraft {
        private String intentId = "intent";
        private String setupId = "setup";
        private String pairId = "pair";
        private String asset = "BTC";
        private Variant variant = Variant.ROUTED_REVERSAL_CONTINUATION;
        private Branch branch = Branch.CONTINUATION;
        private Branch routedBranch = Branch.CONTINUATION;
        private Direction direction = Direction.SHORT;
        private Direction shockDirection = Direction.SHORT;
        private int stage = 1;
        private Instant decisionTime = DECISION;
        private Instant requestedExecutionAfter = DECISION.plusNanos(1);
        private Instant confirmationBarStart = CONFIRM_START;
        private double confirmationClose = 89.8;
        private double zoneCenter = 90;
        private double zoneLower = 89.5;
        private double zoneUpper = 90.5;
        private Double pivotPrice;
        private Instant pivotTime;
        private Instant pivotConfirmedAt;
        private double preEventAtr = 2;
        private double initialStop = 92;
        private Double reversalTarget;
        private double maxChaseDistance = 1;
        private double trancheRiskFraction = 0.01;
        private int proposedLeverage = 2;
        private int maximumHoldingDays = 60;
        private MacroState macroState = MacroState.NOT_REQUIRED;
        private boolean macroEligible = true;
        private boolean diagnosticOnly;
        private List<String> rejectionReasons = List.of();
        private List<SourceEvidence> sourceEvidence = List.of(confirmationEvidence(DECISION));

        private IntentDraft() {}

        private IntentDraft(ConfirmedIntent source) {
            intentId = source.intentId(); setupId = source.setupId(); pairId = source.pairId(); asset = source.asset();
            variant = source.variant(); branch = source.branch(); routedBranch = source.routedBranch();
            direction = source.direction(); shockDirection = source.shockDirection(); stage = source.stage();
            decisionTime = source.decisionTime(); requestedExecutionAfter = source.requestedExecutionAfter();
            confirmationBarStart = source.confirmationBarStart(); confirmationClose = source.confirmationClose();
            zoneCenter = source.zoneCenter(); zoneLower = source.zoneLower(); zoneUpper = source.zoneUpper();
            pivotPrice = source.pivotPrice(); pivotTime = source.pivotTime(); pivotConfirmedAt = source.pivotConfirmedAt();
            preEventAtr = source.preEventAtr(); initialStop = source.initialStop(); reversalTarget = source.reversalTarget();
            maxChaseDistance = source.maxChaseDistance(); trancheRiskFraction = source.trancheRiskFraction();
            proposedLeverage = source.proposedLeverage(); maximumHoldingDays = source.maximumHoldingDays();
            macroState = source.macroState(); macroEligible = source.macroEligible(); diagnosticOnly = source.diagnosticOnly();
            rejectionReasons = source.rejectionReasons(); sourceEvidence = source.sourceEvidence();
        }

        private ConfirmedIntent build() {
            return new ConfirmedIntent(intentId, setupId, pairId, asset, variant, branch, routedBranch,
                    direction, shockDirection, stage, decisionTime, requestedExecutionAfter,
                    confirmationBarStart, confirmationClose, zoneCenter, zoneLower, zoneUpper,
                    pivotPrice, pivotTime, pivotConfirmedAt, preEventAtr, initialStop, reversalTarget,
                    maxChaseDistance, trancheRiskFraction, proposedLeverage, maximumHoldingDays,
                    macroState, macroEligible, diagnosticOnly, rejectionReasons, sourceEvidence);
        }
    }
}
