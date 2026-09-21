package com.tradinganalytics.research.v5;

import static com.tradinganalytics.research.v5.LiquidationStructureRouterV1.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** Exercises the routed LONG-stage path and every short-circuit exit of the five-bar pivot-low rule. */
class LiquidationStructureRouterLongPivotLowPathMatrixTest {
    private static final LocalDate STRESS_DAY = LocalDate.of(2024, 1, 1);
    private static final Instant STRESS_START = utc(STRESS_DAY, 8);
    private static final Instant MODEL_AVAILABLE = utc(STRESS_DAY.plusDays(2), 0);
    private static final String PRICE = "btc-long-pivot-4h-v1";
    private static final String HOUR = "btc-long-pivot-1h-v1";
    private static final String OI = "btc-long-pivot-oi-v1";

    @Test
    void pivotLowRequiresBothRightBarsAndStrictlyLowerCenterThanEveryNeighbor() {
        List<Observation> truePivot = fixture(-1, false);
        Instant secondRightClose = utc(LocalDate.of(2024, 1, 4), 4);
        Run beforeSecondRightClose = route(truePivot, secondRightClose.minusNanos(1));
        assertTrue(beforeSecondRightClose.intents().stream().noneMatch(intent -> intent.stage() > 1),
                "the favorable center is not confirmed until the second right-hand H4 close");
        Run atSecondRightClose = route(truePivot, secondRightClose);
        assertTrue(atSecondRightClose.intents().stream().noneMatch(intent -> intent.stage() > 1),
                "the H1 bar that began before pivot availability cannot be admitted retroactively");

        Run run = route(truePivot, null);
        List<ConfirmedIntent> confirmed = run.intents();
        ConfirmedIntent initial = confirmed.stream().filter(intent -> intent.stage() == 1).findFirst().orElseThrow();
        assertEquals(Direction.LONG, initial.direction(), "up-stress continuation uses a long initial position");
        assertEquals(utc(LocalDate.of(2024, 1, 3), 9), initial.decisionTime());
        assertEquals(initial.decisionTime().plusNanos(1), initial.requestedExecutionAfter());
        assertEquals(utc(LocalDate.of(2024, 1, 3), 8), initial.confirmationBarStart());
        assertEquals(100.9, initial.confirmationClose());
        FillAck initialFill = run.fills().stream().filter(fill -> fill.stage() == 1).findFirst().orElseThrow();
        assertEquals(initial.requestedExecutionAfter().plusNanos(1), initialFill.fillTime());
        assertEquals(initial.intentId(), initialFill.intentId());
        List<ConfirmedIntent> additions = confirmed.stream().filter(intent -> intent.stage() == 2).toList();
        assertEquals(1, additions.size(), "a strict five-bar pivot low unlocks the later confirmed add");
        assertEquals(Direction.LONG, additions.get(0).direction());
        assertEquals(MacroState.NOT_REQUIRED, additions.get(0).macroState());
        assertTrue(additions.get(0).macroEligible());
        assertEquals(utc(LocalDate.of(2024, 1, 4), 4), additions.get(0).pivotConfirmedAt());
        assertEquals(utc(LocalDate.of(2024, 1, 4), 4), additions.get(0).confirmationBarStart());
        assertEquals(utc(LocalDate.of(2024, 1, 4), 5), additions.get(0).decisionTime());

        for (int failedNeighbor : List.of(0, 1, 3, 4)) {
            for (boolean equal : List.of(false, true)) {
                List<ConfirmedIntent> result = route(fixture(failedNeighbor, equal), null).intents();
                assertEquals(Direction.LONG,
                        result.stream().filter(intent -> intent.stage() == 1).findFirst().orElseThrow().direction());
                assertTrue(result.stream().noneMatch(intent -> intent.stage() == 2),
                        (equal ? "equal" : "lower") + " neighbor at index " + failedNeighbor + " prevents the strict pivot");
            }
        }
    }

    private static Run route(List<Observation> observations, Instant through) {
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION, MacroGatePolicy.STRUCTURE_ONLY);
        List<ConfirmedIntent> emitted = new ArrayList<>();
        List<FillAck> fills = new ArrayList<>();
        for (Observation observation : sorted(observations)) {
            if (through != null && processingTime(observation).isAfter(through)) continue;
            RouteResult result = router.accept(observation);
            emitted.addAll(result.intents());
            for (ConfirmedIntent intent : result.intents()) if (intent.stage() == 1) {
                FillAck fill = new FillAck(intent.intentId(), intent.setupId(), intent.asset(), 1,
                        intent.requestedExecutionAfter().plusNanos(1), intent.confirmationClose(), intent.initialStop());
                router.onFill(fill);
                fills.add(fill);
            }
        }
        return new Run(List.copyOf(emitted), List.copyOf(fills));
    }

    private record Run(List<ConfirmedIntent> intents, List<FillAck> fills) {}

    private static List<Observation> fixture(int failedNeighbor, boolean equalNeighbor) {
        List<Observation> result = new ArrayList<>();
        for (int lag = DAILY_LOOKBACK_DAYS; lag >= 1; lag--) {
            LocalDate date = STRESS_DAY.minusDays(lag);
            Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            result.add(new DailyLiquidation("BTC", date, 1, 1, start.plus(Duration.ofHours(24)), "daily-long-v1"));
        }
        Instant bucketStart = STRESS_DAY.atStartOfDay(ZoneOffset.UTC).toInstant();
        result.add(new DailyLiquidation("BTC", STRESS_DAY, 1, 2,
                bucketStart.plus(Duration.ofHours(24)), "daily-long-v1"));

        Instant firstH4 = STRESS_START.minus(Duration.ofHours(4L * 180));
        for (Instant start = firstH4; start.isBefore(MODEL_AVAILABLE.plus(Duration.ofHours(8))); start = start.plus(Duration.ofHours(4))) {
            double open = 100, high = 100.5, low = 99.5, close = 100;
            if (start.equals(STRESS_START)) { open = 100; high = 103.3; low = 99.8; close = 103; }
            if (start.equals(MODEL_AVAILABLE)) { open = 100.7; high = 101.2; low = 100.5; close = 101; }
            if (start.equals(MODEL_AVAILABLE.plus(Duration.ofHours(4)))) {
                open = 101; high = 101.3; low = failedNeighbor <= 1 && failedNeighbor >= 0 && !equalNeighbor
                        ? 100.2 : 100.5; close = 101.1;
            }
            result.add(bar(start, open, high, low, close, start.plus(Duration.ofHours(4)), PRICE));
        }
        // The event endpoints are each backed by a causally available 7-minute-lag OI sample.
        Instant startObserved = STRESS_START.minus(Duration.ofMinutes(7));
        Instant end = STRESS_START.plus(Duration.ofHours(4));
        Instant endObserved = end.minus(Duration.ofMinutes(7));
        result.add(new OpenInterest("BTC", startObserved, startObserved.plusSeconds(1), 100, OI));
        result.add(new OpenInterest("BTC", endObserved, endObserved.plusSeconds(1), 90, OI));

        // Branch arms after two completed H4 closes beyond the frozen prior high and confirms stage one.
        addHour(result, MODEL_AVAILABLE.plus(Duration.ofHours(6)), 100.2, 100.4, 100.1, 100.3);
        addHour(result, MODEL_AVAILABLE.plus(Duration.ofHours(7)), 100.3, 100.4, 100.2, 100.3);
        addHour(result, MODEL_AVAILABLE.plus(Duration.ofHours(8)), 100.4, 101.0, 100.3, 100.9);

        // Five completed H4 bars contain a strict center pivot at index two; mutate one neighbor at a time.
        double[] opens = {100.8, 101.2, 101.0, 101.1, 101.0};
        double[] highs = {101.3, 101.4, 101.3, 101.3, 101.4};
        double[] lows = {100.8, 100.7, 100.4, 100.6, 100.7};
        double[] closes = {101.2, 101.0, 101.1, 101.0, 101.2};
        if (failedNeighbor >= 0) lows[failedNeighbor] = equalNeighbor ? 100.4 : 100.3;
        Instant pivotStart = MODEL_AVAILABLE.plus(Duration.ofHours(8));
        for (int index = 0; index < opens.length; index++) {
            Instant start = pivotStart.plus(Duration.ofHours(4L * index));
            result.add(bar(start, opens[index], highs[index], lows[index], closes[index],
                    start.plus(Duration.ofHours(4)), PRICE));
        }
        // The hourly confirmation is causally after the final pivot H4 close and breaks its zone upward.
        addHour(result, utc(LocalDate.of(2024, 1, 4), 2), 100.3, 100.4, 100.2, 100.3);
        // This bar qualifies geometrically but starts before the pivot's second-right-close availability.
        addHour(result, utc(LocalDate.of(2024, 1, 4), 3), 100.6, 100.9, 100.3, 100.8);
        addHour(result, utc(LocalDate.of(2024, 1, 4), 4), 100.9, 101.2, 100.3, 101.0);
        return result;
    }

    private static Bar bar(Instant start, double open, double high, double low, double close,
            Instant available, String series) {
        return new Bar("BTC", Timeframe.FOUR_HOUR, start, available, open, high, low, close, series);
    }

    private static void addHour(List<Observation> observations, Instant start,
            double open, double high, double low, double close) {
        observations.add(new Bar("BTC", Timeframe.ONE_HOUR, start, start.plus(Duration.ofHours(1)),
                open, high, low, close, HOUR));
    }

    private static Instant utc(LocalDate date, int hour) {
        return date.atTime(LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC);
    }

    private static List<Observation> sorted(List<Observation> observations) {
        return observations.stream().sorted(Comparator
                .comparing(LiquidationStructureRouterLongPivotLowPathMatrixTest::processingTime)
                .thenComparing(Observation::eventTime)
                .thenComparingInt(LiquidationStructureRouterLongPivotLowPathMatrixTest::priority)
                .thenComparing(Observation::asset).thenComparing(Observation::seriesId)).toList();
    }

    private static Instant processingTime(Observation observation) {
        return observation instanceof DailyLiquidation daily ? daily.modeledAvailableAt() : observation.availableAt();
    }

    private static int priority(Observation observation) {
        if (observation instanceof DailyLiquidation) return 0;
        if (observation instanceof OpenInterest) return 2;
        return ((Bar) observation).timeframe() == Timeframe.FOUR_HOUR ? 3 : 4;
    }
}
