package com.tradinganalytics.research.v5;

import static com.tradinganalytics.research.v5.LiquidationStructureRouterV1.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Public paired-control geometry and target boundaries retain two explicit arms per routed decision. */
class LiquidationStructureRouterPairedControlGeometryResidualMatrixTest {
    private static final LocalDate STRESS_DAY = LocalDate.of(2024, 1, 1);
    private static final Instant STRESS_START = utc(STRESS_DAY, 8);
    private static final Instant MODEL_AVAILABLE = utc(STRESS_DAY.plusDays(2), 0);
    private static final String PRICE = "paired-geometry-h4-v1";
    private static final String HOUR = "paired-geometry-h1-v1";
    private static final String OI = "paired-geometry-oi-v1";

    @Test
    void routedPairedGeometryAcceptsItsBaselineAndRejectsEachFrozenGeometryDrift() {
        PairedDecisionContext baseline = routedContext();
        PairedControlResult accepted = LiquidationStructureRouterV1.derivePairedControlArms(baseline);
        assertEquals(2, accepted.arms().size());
        assertTrue(accepted.arms().stream().allMatch(arm -> arm.intent() != null && arm.rejection() == null));

        List<Bar> bars = baseline.lastThreeCompletedHours();
        Bar first = bars.get(0);
        Instant decision = baseline.routedAnchor().decisionTime();

        List<Bar> wrongTimeframe = new ArrayList<>(bars);
        Instant fourHourStart = decision.minus(Duration.ofHours(5));
        wrongTimeframe.set(0, new Bar(first.asset(), Timeframe.FOUR_HOUR, fourHourStart,
                decision.minus(Duration.ofHours(1)), first.open(), first.high(), first.low(), first.close(), first.seriesId()));
        assertGeometryRejection(baseline, wrongTimeframe, "SHARED_CONTROL_HOURLY_GEOMETRY_NOT_KNOWN_AT_DECISION");

        List<Bar> wrongAsset = new ArrayList<>(bars);
        wrongAsset.set(0, new Bar("ETH", first.timeframe(), first.startTime(), first.availableAt(),
                first.open(), first.high(), first.low(), first.close(), first.seriesId()));
        assertGeometryRejection(baseline, wrongAsset, "SHARED_CONTROL_HOURLY_GEOMETRY_NOT_KNOWN_AT_DECISION");

        List<Bar> futureAvailable = new ArrayList<>(bars);
        futureAvailable.set(0, new Bar(first.asset(), first.timeframe(), first.startTime(), decision.plusNanos(1),
                first.open(), first.high(), first.low(), first.close(), first.seriesId()));
        assertGeometryRejection(baseline, futureAvailable, "SHARED_CONTROL_HOURLY_GEOMETRY_NOT_KNOWN_AT_DECISION");

        List<Bar> gap = new ArrayList<>(bars);
        Bar second = bars.get(1);
        gap.set(1, new Bar(second.asset(), second.timeframe(), second.startTime().plus(Duration.ofHours(1)),
                decision, second.open(), second.high(), second.low(), second.close(), second.seriesId()));
        assertGeometryRejection(baseline, gap, "SHARED_CONTROL_HOURLY_GEOMETRY_NOT_CONTIGUOUS");

        List<Bar> wrongSeries = new ArrayList<>(bars);
        wrongSeries.set(1, new Bar(second.asset(), second.timeframe(), second.startTime(), second.availableAt(),
                second.open(), second.high(), second.low(), second.close(), "other-hourly-series"));
        assertGeometryRejection(baseline, wrongSeries, "SHARED_CONTROL_HOURLY_GEOMETRY_NOT_CONTIGUOUS");

        List<Bar> shiftedButContinuous = new ArrayList<>();
        for (Bar bar : bars) shiftedButContinuous.add(new Bar(bar.asset(), bar.timeframe(),
                bar.startTime().minus(Duration.ofHours(1)), bar.availableAt(), bar.open(), bar.high(),
                bar.low(), bar.close(), bar.seriesId()));
        assertGeometryRejection(baseline, shiftedButContinuous,
                "SHARED_CONTROL_HOURLY_GEOMETRY_DOES_NOT_MATCH_ROUTED_CONFIRMATION");

        List<Bar> changedConfirmation = new ArrayList<>(bars);
        Bar finalBar = bars.get(2);
        double mismatchedClose = (finalBar.close() + finalBar.high()) / 2.0;
        assertTrue(Double.compare(mismatchedClose, finalBar.close()) != 0);
        changedConfirmation.set(2, new Bar(finalBar.asset(), finalBar.timeframe(), finalBar.startTime(),
                finalBar.availableAt(), finalBar.open(), finalBar.high(), finalBar.low(),
                mismatchedClose, finalBar.seriesId()));
        assertGeometryRejection(baseline, changedConfirmation,
                "SHARED_CONTROL_HOURLY_GEOMETRY_DOES_NOT_MATCH_ROUTED_CONFIRMATION");
    }

    @Test
    void reversalTargetMustBeAheadAndNotAlreadyReachedByTheSharedConfirmationBar() {
        PairedDecisionContext baseline = routedContext();
        Direction reversalDirection = baseline.routedAnchor().shockDirection().opposite();
        double close = baseline.routedAnchor().confirmationClose();
        Bar confirmation = baseline.lastThreeCompletedHours().get(2);
        double behindTarget = reversalDirection == Direction.LONG ? close / 2.0 : close * 2.0;
        double reachedTarget = reversalDirection == Direction.LONG
                ? (close + confirmation.high()) / 2.0 : (close + confirmation.low()) / 2.0;
        assertTrue(reversalDirection == Direction.LONG ? confirmation.high() > close : confirmation.low() < close);

        PairedControlResult behind = LiquidationStructureRouterV1.derivePairedControlArms(
                new PairedDecisionContext(baseline.routedAnchor(), behindTarget, baseline.lastThreeCompletedHours()));
        ControlArmOpportunity behindReversal = behind.arms().get(1);
        assertNull(behindReversal.intent());
        assertTrue(behindReversal.rejection().reasonCode().contains("REVERSAL_TARGET_AT_OR_BEHIND_SHARED_DECISION"));

        PairedControlResult alreadyReached = LiquidationStructureRouterV1.derivePairedControlArms(
                new PairedDecisionContext(baseline.routedAnchor(), reachedTarget, baseline.lastThreeCompletedHours()));
        ControlArmOpportunity reachedReversal = alreadyReached.arms().get(1);
        assertNull(reachedReversal.intent());
        assertTrue(reachedReversal.rejection().reasonCode().contains("REVERSAL_TARGET_REACHED_DURING_SHARED_CONFIRMATION_BAR"));
    }

    private static PairedDecisionContext routedContext() {
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION, MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION);
        for (Observation observation : sorted(fixture())) {
            RouteResult result = router.accept(observation);
            if (!result.pairedDecisionContexts().isEmpty()) return result.pairedDecisionContexts().get(0);
        }
        throw new AssertionError("accepted routed fixture must expose paired decision geometry");
    }

    private static void assertGeometryRejection(PairedDecisionContext baseline, List<Bar> bars, String reason) {
        PairedControlResult result = LiquidationStructureRouterV1.derivePairedControlArms(
                new PairedDecisionContext(baseline.routedAnchor(), baseline.immutableRecoveryTarget(), bars));
        assertEquals(2, result.arms().size());
        assertTrue(result.arms().stream().allMatch(arm -> arm.intent() == null && arm.rejection() != null
                && arm.rejection().reasonCode().contains(reason)), reason);
    }

    private static List<Observation> fixture() {
        List<Observation> observations = new ArrayList<>();
        for (int lag = DAILY_LOOKBACK_DAYS; lag >= 1; lag--) {
            LocalDate date = STRESS_DAY.minusDays(lag);
            Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            observations.add(new DailyLiquidation("BTC", date, 1, 1,
                    start.plus(Duration.ofHours(24)), "daily-paired-geometry-v1"));
        }
        Instant dayStart = STRESS_DAY.atStartOfDay(ZoneOffset.UTC).toInstant();
        observations.add(new DailyLiquidation("BTC", STRESS_DAY, 2, 2,
                dayStart.plus(Duration.ofHours(24)), "daily-paired-geometry-v1"));

        Instant firstH4 = STRESS_START.minus(Duration.ofHours(4L * 180));
        Instant h4End = MODEL_AVAILABLE.plus(Duration.ofHours(8));
        for (Instant start = firstH4; start.isBefore(h4End); start = start.plus(Duration.ofHours(4))) {
            double open = 100, high = 100.5, low = 99.5, close = 100;
            if (start.equals(STRESS_START)) { open = 100; high = 100.2; low = 96.7; close = 97; }
            if (start.equals(MODEL_AVAILABLE.minus(Duration.ofHours(4)))) { open = 98; high = 98.5; low = 96.5; close = 97; }
            if (start.equals(MODEL_AVAILABLE)) { open = 97; high = 97.5; low = 96; close = 96.8; }
            if (start.equals(MODEL_AVAILABLE.plus(Duration.ofHours(4)))) { open = 96.8; high = 97.2; low = 95.8; close = 96.5; }
            observations.add(new Bar("BTC", Timeframe.FOUR_HOUR, start, start.plus(Duration.ofHours(4)),
                    open, high, low, close, PRICE));
        }
        Instant startObserved = STRESS_START.minus(Duration.ofMinutes(7));
        Instant end = STRESS_START.plus(Duration.ofHours(4));
        Instant endObserved = end.minus(Duration.ofMinutes(7));
        observations.add(new OpenInterest("BTC", startObserved, startObserved.plusSeconds(1), 100, OI));
        observations.add(new OpenInterest("BTC", endObserved, endObserved.plusSeconds(1), 90, OI));
        addHour(observations, MODEL_AVAILABLE.plus(Duration.ofHours(2)), 100, 100.2, 99.8, 100);
        addHour(observations, MODEL_AVAILABLE.plus(Duration.ofHours(3)), 100, 100.2, 99.8, 100);
        observations.add(new Bar("BTC", Timeframe.ONE_HOUR, MODEL_AVAILABLE.plus(Duration.ofHours(4)),
                MODEL_AVAILABLE.plus(Duration.ofHours(5)), 99.7, 99.8, 99.4, 99.45, HOUR));
        addHour(observations, MODEL_AVAILABLE.plus(Duration.ofHours(5)), 100, 100.2, 99.8, 100);
        addHour(observations, MODEL_AVAILABLE.plus(Duration.ofHours(6)), 100, 100.2, 99.8, 100);
        observations.add(new Bar("BTC", Timeframe.ONE_HOUR, MODEL_AVAILABLE.plus(Duration.ofHours(7)),
                MODEL_AVAILABLE.plus(Duration.ofHours(8)), 100, 100.2, 99.8, 100, HOUR));
        observations.add(new Bar("BTC", Timeframe.ONE_HOUR, MODEL_AVAILABLE.plus(Duration.ofHours(8)),
                MODEL_AVAILABLE.plus(Duration.ofHours(9)), 99.7, 99.8, 99.4, 99.45, HOUR));
        return observations;
    }

    private static void addHour(List<Observation> out, Instant start, double open, double high, double low, double close) {
        out.add(new Bar("BTC", Timeframe.ONE_HOUR, start, start.plus(Duration.ofHours(1)),
                open, high, low, close, HOUR));
    }

    private static Instant utc(LocalDate date, int hour) {
        return date.atTime(LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC);
    }

    private static List<Observation> sorted(List<Observation> observations) {
        return observations.stream().sorted(Comparator.comparing(LiquidationStructureRouterPairedControlGeometryResidualMatrixTest::processingTime)
                .thenComparing(Observation::eventTime).thenComparingInt(LiquidationStructureRouterPairedControlGeometryResidualMatrixTest::priority)
                .thenComparing(Observation::asset).thenComparing(Observation::seriesId)).toList();
    }

    private static Instant processingTime(Observation observation) {
        return observation instanceof DailyLiquidation daily ? daily.modeledAvailableAt() : observation.availableAt();
    }

    private static int priority(Observation observation) {
        if (observation instanceof DailyLiquidation) return 0;
        if (observation instanceof MacroClose) return 1;
        if (observation instanceof OpenInterest) return 2;
        return ((Bar) observation).timeframe() == Timeframe.FOUR_HOUR ? 3 : 4;
    }
}
