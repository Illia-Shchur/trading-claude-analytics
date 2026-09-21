package com.tradinganalytics.research.v5;

import static com.tradinganalytics.research.v5.LiquidationStructureRouterV1.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract vectors for the router's public feature and event types. */
class LiquidationStructureRouterInputStateMatrixV1Test {
    private static final Instant T = Instant.parse("2024-07-01T16:00:00Z");
    private static final Instant H1 = Instant.parse("2024-07-01T15:00:00Z");
    private static final String SERIES = "fixture-v1";

    @Test
    void dailyRowsNormalizeSupportedAssetsAndRejectInvalidTotalsOrIdentity() {
        DailyLiquidation lower = new DailyLiquidation("btc", LocalDate.of(2024, 7, 1), 0, 0, T, SERIES);
        assertEquals("BTC", lower.asset());
        assertEquals(Instant.parse("2024-07-03T00:00:00Z"), lower.modeledAvailableAt());
        DailyLiquidation delayed = new DailyLiquidation("ETH", LocalDate.of(2024, 7, 1), 2, 3,
                Instant.parse("2024-07-04T00:00:00Z"), SERIES);
        assertEquals(delayed.availableAt(), delayed.modeledAvailableAt());
        for (double value : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            double bad = value;
            assertThrows(IllegalArgumentException.class, () -> new DailyLiquidation("BTC",
                    LocalDate.of(2024, 7, 1), bad, 0, T, SERIES));
            assertThrows(IllegalArgumentException.class, () -> new DailyLiquidation("BTC",
                    LocalDate.of(2024, 7, 1), 0, bad, T, SERIES));
        }
        assertThrows(IllegalArgumentException.class, () -> new DailyLiquidation("DOGE",
                LocalDate.of(2024, 7, 1), 1, 1, T, SERIES));
        assertThrows(IllegalArgumentException.class, () -> new DailyLiquidation("BTC",
                LocalDate.of(2024, 7, 1), 1, 1, T, " "));
        assertThrows(RuntimeException.class, () -> new DailyLiquidation("BTC", null, 1, 1, T, SERIES));
    }

    @Test
    void completedBarsEnforcePriceShapeUtcGridAndCloseAvailability() {
        Bar normalized = bar("eth", Timeframe.ONE_HOUR, H1, T, 100, 102, 98, 101, SERIES);
        assertEquals("ETH", normalized.asset());
        assertEquals(T, normalized.eventTime());
        assertEquals(Duration.ofHours(4), Timeframe.FOUR_HOUR.duration());
        assertEquals(Duration.ofHours(1), Timeframe.ONE_HOUR.duration());

        assertThrows(IllegalArgumentException.class, () -> bar("BTC", Timeframe.ONE_HOUR, H1, T, 0, 1, 0.5, 1, SERIES));
        assertThrows(IllegalArgumentException.class, () -> bar("BTC", Timeframe.ONE_HOUR, H1, T, 100, 99, 98, 100, SERIES));
        assertThrows(IllegalArgumentException.class, () -> bar("BTC", Timeframe.ONE_HOUR, H1, T, 100, 101, 102, 100, SERIES));
        assertThrows(IllegalArgumentException.class, () -> bar("BTC", Timeframe.ONE_HOUR, H1, T, 100, 101, 99, 102, SERIES));
        assertThrows(IllegalArgumentException.class, () -> bar("BTC", Timeframe.ONE_HOUR,
                H1.plusSeconds(1), T, 100, 101, 99, 100, SERIES));
        assertThrows(IllegalArgumentException.class, () -> bar("BTC", Timeframe.ONE_HOUR,
                H1, H1.plus(Duration.ofMinutes(59)), 100, 101, 99, 100, SERIES));
        assertThrows(RuntimeException.class, () -> new Bar("BTC", null, H1, T, 100, 101, 99, 100, SERIES));
        assertThrows(IllegalArgumentException.class, () -> bar("BTC", Timeframe.ONE_HOUR, H1, T, 100, 101, 99, 100, ""));
        assertThrows(IllegalArgumentException.class, () -> bar("XRP", Timeframe.ONE_HOUR, H1, T, 100, 101, 99, 100, SERIES));
    }

    @Test
    void oiAndMacroRowsKeepStrictObservationAndBoundedExchangeAvailability() {
        OpenInterest oi = new OpenInterest("sol", H1, T, 1, SERIES);
        assertEquals("SOL", oi.asset());
        assertEquals(H1, oi.eventTime());
        assertThrows(IllegalArgumentException.class, () -> new OpenInterest("BTC", H1, H1, 0, SERIES));
        assertThrows(IllegalArgumentException.class, () -> new OpenInterest("BTC", H1, H1.minusNanos(1), 1, SERIES));
        assertThrows(IllegalArgumentException.class, () -> new OpenInterest("BTC", H1, T, 1, " "));

        ZoneId ny = ZoneId.of("America/New_York");
        Instant regularClose = LocalDate.of(2024, 7, 1).atTime(16, 0).atZone(ny).toInstant();
        Instant nextSessionClose = LocalDate.of(2024, 7, 2).atTime(16, 0).atZone(ny).toInstant();
        MacroClose regular = new MacroClose(regularClose, nextSessionClose, 5000, true, SERIES);
        assertEquals("SP500", regular.asset());
        assertEquals(regularClose, regular.eventTime());
        assertTrue(regular.provenanceAvailable());

        Instant earlyClose = LocalDate.of(2024, 12, 24).atTime(13, 0).atZone(ny).toInstant();
        Instant afterChristmas = LocalDate.of(2024, 12, 26).atTime(16, 0).atZone(ny).toInstant();
        assertDoesNotThrow(() -> new MacroClose(earlyClose, afterChristmas, 5000, false, SERIES));
        assertThrows(IllegalArgumentException.class,
                () -> new MacroClose(regularClose, nextSessionClose.minusNanos(1), 5000, true, SERIES));
        assertThrows(IllegalArgumentException.class,
                () -> new MacroClose(regularClose.plusSeconds(1), nextSessionClose, 5000, true, SERIES));
        Instant saturday = LocalDate.of(2024, 7, 6).atTime(16, 0).atZone(ny).toInstant();
        assertThrows(IllegalArgumentException.class,
                () -> new MacroClose(saturday, saturday.plus(Duration.ofDays(3)), 5000, true, SERIES));
        Instant unsupportedYear = LocalDate.of(2027, 7, 1).atTime(16, 0).atZone(ny).toInstant();
        assertThrows(IllegalArgumentException.class,
                () -> new MacroClose(unsupportedYear, unsupportedYear.plus(Duration.ofDays(1)), 5000, true, SERIES));
        assertThrows(IllegalArgumentException.class,
                () -> new MacroClose(regularClose, nextSessionClose, Double.NaN, true, SERIES));
    }

    @Test
    void immutableAnchorSeedAndStageOneContextRejectIndividuallyForgedGeometry() {
        StageOneAnchorContext good = anchor();
        assertEquals("FAST", good.setupSeed().selectedGeometry());
        assertEquals(0.01, good.intent().trancheRiskFraction());
        assertEquals(2, good.intent().proposedLeverage());

        assertThrows(IllegalArgumentException.class, () -> seed("BTC", 0.0, 110, 90, 100,
                100, 99, 101, true, false, "FAST"));
        assertThrows(IllegalArgumentException.class, () -> seed("BTC", 2.0, 90, 110, 100,
                100, 99, 101, true, false, "FAST"));
        assertThrows(IllegalArgumentException.class, () -> seed("BTC", 2.0, 110, 90, 100,
                100, 102, 101, true, false, "FAST"));
        assertThrows(IllegalArgumentException.class, () -> seed("BTC", 2.0, 110, 90, 100,
                100, 99, 101, false, false, "FAST"));
        assertThrows(IllegalArgumentException.class, () -> seed("BTC", 2.0, 110, 90, 100,
                100, 99, 101, true, false, "SLOW"));

        ConfirmedIntent intent = good.intent();
        assertThrows(IllegalArgumentException.class, () -> new StageOneAnchorContext(
                with(intent, Branch.CONTINUATION, Direction.LONG, null, intent.trancheRiskFraction(),
                        intent.proposedLeverage(), intent.decisionTime(), intent.macroState(), true, intent.sourceEvidence()),
                good.setupSeed()));
        assertThrows(IllegalArgumentException.class, () -> new StageOneAnchorContext(
                with(intent, intent.branch(), intent.direction(), null, 0.02, 2, intent.decisionTime(),
                        MacroState.NOT_REQUIRED, true, intent.sourceEvidence()), good.setupSeed()));
        assertThrows(IllegalArgumentException.class, () -> new StageOneAnchorContext(
                with(intent, intent.branch(), intent.direction(), null, 0.01, 3, intent.decisionTime(),
                        MacroState.NOT_REQUIRED, true, intent.sourceEvidence()), good.setupSeed()));
        assertThrows(IllegalArgumentException.class, () -> new StageOneAnchorContext(
                with(intent, intent.branch(), intent.direction(), null, 0.01, 2, intent.decisionTime(),
                        MacroState.UNKNOWN, false, intent.sourceEvidence()), good.setupSeed()));

        SourceEvidence future = new SourceEvidence("FUTURE", "BTC", SERIES, T.plusSeconds(1), T.plusSeconds(2));
        assertThrows(IllegalArgumentException.class, () -> new StageOneAnchorContext(
                with(intent, intent.branch(), intent.direction(), null, 0.01, 2, intent.decisionTime(),
                        MacroState.NOT_REQUIRED, true, List.of(intent.sourceEvidence().get(0), future)), good.setupSeed()));
    }

    @Test
    void intentAndAcknowledgementRecordsRejectInvalidStagesAndChronology() {
        ConfirmedIntent good = anchor().intent();
        assertEquals(1, good.stage());
        assertThrows(IllegalArgumentException.class, () -> with(good, good.branch(), good.direction(), null,
                0.01, 2, good.decisionTime(), MacroState.NOT_REQUIRED, true, good.sourceEvidence(), 0, good.requestedExecutionAfter()));
        assertThrows(IllegalArgumentException.class, () -> with(good, good.branch(), good.direction(), null,
                0.01, 2, good.decisionTime(), MacroState.NOT_REQUIRED, true, good.sourceEvidence(), 4, good.requestedExecutionAfter()));
        assertThrows(IllegalArgumentException.class, () -> with(good, good.branch(), good.direction(), null,
                0.01, 2, good.decisionTime(), MacroState.NOT_REQUIRED, true, good.sourceEvidence(), 1, good.decisionTime()));
        assertThrows(IllegalArgumentException.class, () -> new FillAck("i", "s", "BTC", 0, T, 100, 90));
        assertThrows(IllegalArgumentException.class, () -> new FillAck("i", "s", "BTC", 1, T, 0, 90));
        assertThrows(IllegalArgumentException.class, () -> new FillAck("i", "s", "BTC", 1, T, 100, Double.NaN));
        assertDoesNotThrow(() -> new FillAck("i", "s", "BTC", 3, T, 100, 90));
        assertThrows(IllegalArgumentException.class, () -> new NoFillAck("i", "s", "BTC", 4, T, "X"));
        assertDoesNotThrow(() -> new NoFillAck("i", "s", "BTC", 2, T, ""));
        assertThrows(IllegalArgumentException.class, () -> new StopAck("s", "BTC", T, 0));
        assertThrows(IllegalArgumentException.class, () -> new PendingCancellation("i", "s", "BTC", 0, T, "X", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PendingCancellation("i", "s", "BTC", 1, T, " ", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new QualifiedDailyStressEvent(" ", "BTC", LocalDate.of(2024, 7, 1),
                T, T, Direction.LONG, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new QualifiedDailyStressEvent("e", "BTC", LocalDate.of(2024, 7, 1),
                T, T.minusNanos(1), Direction.LONG, List.of()));
        assertThrows(RuntimeException.class, () -> new SourceEvidence("ROLE", null, SERIES, T, T));
        assertThrows(IllegalArgumentException.class, () -> new SourceEvidence("ROLE", "BTC", " ", T, T));
        assertThrows(IllegalArgumentException.class, () -> new StopUpdateIntent("u", "s", "BTC", T,
                100, 99, Direction.LONG, false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StopUpdateIntent("u", "s", "BTC", T,
                Double.NaN, 99, Direction.LONG, true, List.of()));
        assertDoesNotThrow(() -> new StopUpdateIntent("u", "s", "BTC", T, 100, 99,
                Direction.LONG, true, List.of()));
    }

    @Test
    void routeContainerAndPairedArmTypesEnforceTheirClosedVariantShapes() {
        ConfirmedIntent routed = anchor().intent();
        RejectedOpportunity rejection = new RejectedOpportunity("r", "setup", "pair", "BTC",
                Variant.ALWAYS_REVERSAL_CONTROL, Branch.CONTINUATION, Branch.REVERSAL, Direction.LONG,
                1, T, "STRUCTURE", MacroState.NOT_REQUIRED, false, List.of());
        ControlArmOpportunity valid = new ControlArmOpportunity(Variant.ALWAYS_REVERSAL_CONTROL,
                Branch.REVERSAL, Direction.LONG, null, rejection);
        assertEquals(rejection, valid.rejection());
        assertThrows(IllegalArgumentException.class, () -> new ControlArmOpportunity(Variant.ROUTED_REVERSAL_CONTINUATION,
                Branch.REVERSAL, Direction.LONG, null, rejection));
        assertThrows(IllegalArgumentException.class, () -> new ControlArmOpportunity(Variant.ALWAYS_REVERSAL_CONTROL,
                Branch.REVERSAL, Direction.LONG, routed, rejection));
        assertThrows(IllegalArgumentException.class, () -> new ControlArmOpportunity(Variant.ALWAYS_REVERSAL_CONTROL,
                Branch.REVERSAL, Direction.LONG, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PairedControlResult("p", T, List.of(valid, valid)));
        assertThrows(IllegalArgumentException.class, () -> new PairedDecisionContext(routed, Double.NaN, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PairedDecisionContext(
                with(routed, routed.branch(), routed.direction(), null, 0.01, 2, T,
                        MacroState.NOT_REQUIRED, true, routed.sourceEvidence(), 2, T.plusNanos(1)), null, List.of()));

        assertEquals(0, RouteResult.empty().intents().size());
        assertEquals(0, new RouteResult(List.of(), List.of(), List.of(), List.of()).stageOneAnchorContexts().size());
        assertThrows(RuntimeException.class, () -> new RouteResult(null, List.of(), List.of(), List.of()));
        assertThrows(RuntimeException.class, () -> new RouteResult(List.of(), null, List.of(), List.of()));
    }

    @Test
    void routerCallbacksAndInputStreamsFailClosedOnMissingOrConflictingState() {
        Router empty = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        assertThrows(IllegalArgumentException.class, () -> empty.onFill(new FillAck("missing", "s", "BTC", 1, T, 100, 90)));
        assertThrows(IllegalArgumentException.class, () -> empty.onNoFill(new NoFillAck("missing", "s", "BTC", 1, T, "NO")));
        assertThrows(IllegalArgumentException.class, () -> empty.onClose(new CloseAck("s", "BTC", T, CloseReason.OTHER)));
        assertThrows(IllegalArgumentException.class, () -> empty.onStopUpdate(new StopAck("s", "BTC", T, 90)));

        assertDoesNotThrow(() -> new Router(Variant.ROUTED_REVERSAL_CONTINUATION)
                .accept(bar("BTC", Timeframe.ONE_HOUR, H1, T, 100, 101, 99, 100, SERIES)));
        Router dupDaily = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        DailyLiquidation daily = new DailyLiquidation("BTC", LocalDate.of(2024, 7, 1), 1, 1, T, SERIES);
        dupDaily.accept(daily);
        assertThrows(IllegalArgumentException.class, () -> dupDaily.accept(daily));
        Router otherDailySeries = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        otherDailySeries.accept(daily);
        assertThrows(IllegalArgumentException.class, () -> otherDailySeries.accept(new DailyLiquidation("BTC",
                LocalDate.of(2024, 7, 2), 1, 1, T.plus(Duration.ofDays(1)), "different")));
        Router otherOiSeries = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        otherOiSeries.accept(new OpenInterest("BTC", H1, T, 10, SERIES));
        assertThrows(IllegalArgumentException.class, () -> otherOiSeries.accept(new OpenInterest("BTC",
                H1.plus(Duration.ofHours(1)), T.plus(Duration.ofHours(1)), 10, "different")));
        Router otherBarSeries = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        otherBarSeries.accept(bar("BTC", Timeframe.ONE_HOUR, H1, T, 100, 101, 99, 100, SERIES));
        assertThrows(IllegalArgumentException.class, () -> otherBarSeries.accept(bar("BTC", Timeframe.ONE_HOUR,
                T, T.plus(Duration.ofHours(1)), 100, 101, 99, 100, "different")));
        Router duplicateClose = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        duplicateClose.accept(bar("BTC", Timeframe.ONE_HOUR, H1, T, 100, 101, 99, 100, SERIES));
        assertThrows(IllegalArgumentException.class, () -> duplicateClose.accept(bar("BTC", Timeframe.ONE_HOUR,
                H1, T, 100, 101, 99, 100, SERIES)));

        assertThrows(RuntimeException.class, () -> Router.replay(null, List.of()));
        assertThrows(RuntimeException.class, () -> Router.replay(Variant.ROUTED_REVERSAL_CONTINUATION,
                null, List.of()));
        assertThrows(RuntimeException.class, () -> Router.replay(Variant.ROUTED_REVERSAL_CONTINUATION,
                MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION, null));
        assertThrows(IllegalArgumentException.class, () -> Router.anchoredStaged(MacroGatePolicy.STRUCTURE_ONLY)
                .acceptFrozenInitialAnchor(null));

        Router macroDuplicate = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        MacroClose close = macro("2024-07-01T20:00:00Z", "2024-07-02T20:00:00Z", SERIES);
        macroDuplicate.accept(close);
        assertThrows(IllegalArgumentException.class, () -> macroDuplicate.accept(close));
        assertThrows(IllegalArgumentException.class, () -> macroDuplicate.accept(macro(
                "2024-07-02T20:00:00Z", "2024-07-03T20:00:00Z", "different-series")));

        Router clock = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        clock.accept(close);
        assertThrows(IllegalArgumentException.class,
                () -> clock.accept(new OpenInterest("BTC", H1, T, 10, "oi-after-clock")));

        Router sameTimeDifferentAssets = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        sameTimeDifferentAssets.accept(bar("BTC", Timeframe.ONE_HOUR, H1, T, 100, 101, 99, 100, SERIES));
        assertDoesNotThrow(() -> sameTimeDifferentAssets.accept(
                bar("AAVE", Timeframe.ONE_HOUR, H1, T, 10, 11, 9, 10, "aave-series")));
    }

    @Test
    void anchoredFillCloseLifecycleRejectsMismatchesAndAllowsDistinctSameTimeSuccessor() {
        StageOneAnchorContext firstAnchor = anchor();
        ObjectNode firstProjection = LiquidationStructureRouterV1.stageOneAnchorJson(firstAnchor);
        Router router = Router.anchoredStaged(MacroGatePolicy.STRUCTURE_ONLY);
        RouteResult accepted = router.acceptFrozenInitialAnchor(firstProjection);
        ConfirmedIntent first = accepted.intents().get(0);
        assertEquals(1, accepted.intents().size());
        assertTrue(accepted.rejections().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> router.onFill(new FillAck("other", first.setupId(),
                first.asset(), 1, first.requestedExecutionAfter().plusNanos(1), first.confirmationClose(), first.initialStop())));
        assertThrows(IllegalArgumentException.class, () -> router.onFill(new FillAck(first.intentId(), "other-setup",
                first.asset(), 1, first.requestedExecutionAfter().plusNanos(1), first.confirmationClose(), first.initialStop())));
        assertThrows(IllegalArgumentException.class, () -> router.onFill(new FillAck(first.intentId(), first.setupId(),
                "ETH", 1, first.requestedExecutionAfter().plusNanos(1), first.confirmationClose(), first.initialStop())));
        assertThrows(IllegalArgumentException.class, () -> router.onFill(new FillAck(first.intentId(), first.setupId(),
                first.asset(), 2, first.requestedExecutionAfter().plusNanos(1), first.confirmationClose(), first.initialStop())));
        assertThrows(IllegalArgumentException.class, () -> router.onFill(new FillAck(first.intentId(), first.setupId(),
                first.asset(), 1, first.requestedExecutionAfter(), first.confirmationClose(), first.initialStop())));

        Instant fillTime = first.requestedExecutionAfter().plusNanos(1);
        router.onFill(new FillAck(first.intentId(), first.setupId(), first.asset(), 1,
                fillTime, first.confirmationClose(), first.initialStop()));
        assertThrows(IllegalArgumentException.class, () -> router.onFill(new FillAck(first.intentId(), first.setupId(),
                first.asset(), 1, fillTime.plusNanos(1), first.confirmationClose(), first.initialStop())));
        assertThrows(IllegalArgumentException.class, () -> router.onStopUpdate(new StopAck(first.setupId(),
                first.asset(), fillTime.plusSeconds(1), first.initialStop() + 1)));
        router.onStopUpdate(new StopAck(first.setupId(), first.asset(), fillTime.plusSeconds(1), first.initialStop() - 1));

        Instant closeTime = T.plus(Duration.ofHours(1));
        assertThrows(IllegalArgumentException.class, () -> router.onClose(new CloseAck("different", first.asset(), closeTime,
                CloseReason.OTHER)));
        router.onClose(new CloseAck(first.setupId(), first.asset(), closeTime, CloseReason.OTHER));
        assertThrows(IllegalArgumentException.class, () -> router.onStopUpdate(new StopAck(first.setupId(),
                first.asset(), closeTime.plusSeconds(1), first.initialStop() - 2)));

        ObjectNode sameSetupLater = redated(firstProjection, first.setupId(), "later-pair", "later-intent", closeTime);
        RouteResult filledSetupRetry = router.acceptFrozenInitialAnchor(sameSetupLater);
        assertTrue(filledSetupRetry.intents().isEmpty());
        assertEquals("FROZEN_ANCHOR_SETUP_ALREADY_FILLED", filledSetupRetry.rejections().get(0).reasonCode());

        ObjectNode distinctAtClose = redated(firstProjection, "next-setup", "next-pair", "next-intent", closeTime);
        RouteResult sameTime = router.acceptFrozenInitialAnchor(distinctAtClose);
        assertEquals(1, sameTime.intents().size(), "exit-before-entry allows a distinct anchor at the close timestamp");
        assertTrue(sameTime.rejections().isEmpty());
    }

    private static Bar bar(String asset, Timeframe timeframe, Instant start, Instant available,
            double open, double high, double low, double close, String series) {
        return new Bar(asset, timeframe, start, available, open, high, low, close, series);
    }

    private static MacroClose macro(String close, String available, String series) {
        return new MacroClose(Instant.parse(close), Instant.parse(available), 5000, true, series);
    }

    private static StageOneAnchorContext anchor() {
        SourceEvidence geometry = new SourceEvidence("GEOMETRY", "BTC", SERIES,
                T.minus(Duration.ofHours(4)), T.minus(Duration.ofHours(4)));
        SourceEvidence confirm = new SourceEvidence("CONFIRMATION", "BTC", SERIES, T, T);
        AnchorSetupSeed seed = new AnchorSetupSeed("setup", "BTC", Direction.SHORT,
                T.minus(Duration.ofDays(1)), T.minus(Duration.ofHours(4)), T.minus(Duration.ofHours(4)),
                2, 110, 90, 90, 100, 89.5, 90.5, true, false, "FAST", List.of(geometry));
        ConfirmedIntent intent = new ConfirmedIntent("intent", "setup", "pair", "BTC",
                Variant.ROUTED_REVERSAL_CONTINUATION, Branch.CONTINUATION, Branch.CONTINUATION,
                Direction.SHORT, Direction.SHORT, 1, T, T.plusNanos(1), H1, 89.8,
                90, 89.5, 90.5, null, null, null, 2, 92, null, 1, .01, 2, 60,
                MacroState.NOT_REQUIRED, true, false, List.of(), List.of(confirm));
        return new StageOneAnchorContext(intent, seed);
    }

    private static AnchorSetupSeed seed(String asset, double atr, double priorHigh, double priorLow,
            double boundary, double recovery, double zoneLower, double zoneUpper,
            boolean fast, boolean slow, String selected) {
        SourceEvidence source = new SourceEvidence("GEOMETRY", asset, SERIES,
                T.minus(Duration.ofHours(4)), T.minus(Duration.ofHours(4)));
        return new AnchorSetupSeed("setup", asset, Direction.SHORT,
                T.minus(Duration.ofDays(1)), T.minus(Duration.ofHours(4)), T.minus(Duration.ofHours(4)),
                atr, priorHigh, priorLow, boundary, recovery, zoneLower, zoneUpper, fast, slow, selected, List.of(source));
    }

    private static ConfirmedIntent with(ConfirmedIntent base, Branch branch, Direction direction,
            Double target, double risk, int leverage, Instant decision, MacroState macro,
            boolean macroEligible, List<SourceEvidence> evidence) {
        return with(base, branch, direction, target, risk, leverage, decision, macro,
                macroEligible, evidence, base.stage(), base.requestedExecutionAfter());
    }

    private static ConfirmedIntent with(ConfirmedIntent base, Branch branch, Direction direction,
            Double target, double risk, int leverage, Instant decision, MacroState macro,
            boolean macroEligible, List<SourceEvidence> evidence, int stage, Instant executionAfter) {
        return new ConfirmedIntent(base.intentId(), base.setupId(), base.pairId(), base.asset(), base.variant(),
                branch, base.routedBranch(), direction, base.shockDirection(), stage, decision, executionAfter,
                base.confirmationBarStart(), base.confirmationClose(), base.zoneCenter(), base.zoneLower(),
                base.zoneUpper(), base.pivotPrice(), base.pivotTime(), base.pivotConfirmedAt(), base.preEventAtr(),
                base.initialStop(), target, base.maxChaseDistance(), risk, leverage, base.maximumHoldingDays(),
                macro, macroEligible, base.diagnosticOnly(), base.rejectionReasons(), evidence);
    }

    private static ObjectNode redated(ObjectNode source, String setupId, String pairId, String intentId, Instant decision) {
        ObjectNode copy = source.deepCopy();
        copy.put("setup_id", setupId).put("pair_id", pairId).put("intent_id", intentId)
                .put("decision_time", decision.toString())
                .put("requested_execution_after", decision.plusNanos(1).toString())
                .put("confirmation_bar_start", decision.minus(Duration.ofHours(1)).toString());
        ObjectNode seed = (ObjectNode) copy.path("setup_seed");
        seed.put("setup_id", setupId);
        reseal(seed);
        for (JsonNode sourceEvidence : copy.path("source_evidence")) {
            if ("CONFIRMATION".equals(sourceEvidence.path("role").asText())) {
                ((ObjectNode) sourceEvidence).put("event_time", decision.toString())
                        .put("available_at", decision.toString());
            }
        }
        reseal(copy);
        return copy;
    }

    private static void reseal(ObjectNode value) {
        value.remove("content_sha256");
        value.put("content_sha256", JsonHashes.ownHash(value));
    }
}
