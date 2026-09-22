package com.tradinganalytics.research.v5;

import static com.tradinganalytics.research.v5.LiquidationStructureRouterV1.DailyPriceContext;
import static com.tradinganalytics.research.v5.LiquidationStructureRouterV1.Timeframe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class LiquidationDailyPriceContextV1Test {
    private static final String SERIES = "fixture-h1";

    @Test
    void mergesSupplementalBarsInsideTheCalculatorAndEmitsOnlyCompleteUtcDays() {
        LocalDate start = LocalDate.of(2024, 1, 1);
        List<LiquidationStructureRouterV1.Bar> supplemental = dailyBars("BTC", start, sequence(14, 1.0), -1);
        ArrayList<LiquidationStructureRouterV1.Bar> retained = new ArrayList<>(dailyBars(
                "BTC", start.plusDays(14), List.of(15.0), -1));
        retained.addAll(dailyBars("BTC", start.plusDays(15), List.of(16.0), 7));
        retained.addAll(dailyBars("BTC", start.plusDays(16), List.of(17.0), -1));
        retained.add(supplemental.get(0)); // an identical boundary duplicate contributes only one H1 observation

        List<DailyPriceContext> rows = LiquidationDailyPriceContextV1.build(retained, supplemental);
        Map<LocalDate, DailyPriceContext> byDay = byDay(rows, "BTC");

        assertEquals(16, byDay.size(), "the incomplete January 16 day must be omitted");
        assertEquals(15.0, byDay.get(start.plusDays(14)).dailyClose());
        assertEquals(100.0, byDay.get(start.plusDays(14)).rsi14());
        assertEquals(start.plusDays(15).atStartOfDay(ZoneOffset.UTC).toInstant(),
                byDay.get(start.plusDays(14)).availableAt());
        assertFalse(byDay.containsKey(start.plusDays(15)), "do not carry the prior close into a missing latest day");
        assertNull(byDay.get(start.plusDays(16)).rsi14(), "the first close after a gap starts a new RSI stream");
        assertNull(byDay.get(start.plusDays(16)).sma200(), "the first close after a gap starts a new SMA window");
    }

    @Test
    void rsiUsesFourteenChangesForOneWilderSeedThenRecursesWithoutRollingReseed() {
        LocalDate start = LocalDate.of(2024, 2, 1);
        ArrayList<Double> riseThenFall = new ArrayList<>(sequence(15, 1.0));
        riseThenFall.add(14.0);
        List<DailyPriceContext> rising = LiquidationDailyPriceContextV1.build(
                dailyBars("BTC", start, riseThenFall, -1), List.of());
        Map<LocalDate, DailyPriceContext> byDay = byDay(rising, "BTC");
        assertNull(byDay.get(start.plusDays(13)).rsi14(), "14 closes contain only 13 daily changes");
        assertEquals(100.0, byDay.get(start.plusDays(14)).rsi14());
        assertEquals(100.0 * 13.0 / 14.0, byDay.get(start.plusDays(15)).rsi14(), 1e-12,
                "the sixteenth close must update the original Wilder seed recursively");

        List<DailyPriceContext> falling = LiquidationDailyPriceContextV1.build(
                dailyBars("ETH", start, sequence(15, -1.0, 30.0), -1), List.of());
        assertEquals(0.0, byDay(falling, "ETH").get(start.plusDays(14)).rsi14());

        List<DailyPriceContext> flat = LiquidationDailyPriceContextV1.build(
                dailyBars("SOL", start, repeated(15, 12.5), -1), List.of());
        assertEquals(50.0, byDay(flat, "SOL").get(start.plusDays(14)).rsi14());

        ArrayList<Double> balanced = new ArrayList<>();
        balanced.add(100.0);
        for (int index = 0; index < 7; index++) {
            balanced.add(101.0);
            balanced.add(100.0);
        }
        List<DailyPriceContext> equalNonzero = LiquidationDailyPriceContextV1.build(
                dailyBars("LINK", start, balanced, -1), List.of());
        assertEquals(50.0, byDay(equalNonzero, "LINK").get(start.plusDays(14)).rsi14(),
                "equal nonzero smoothed gains and losses must be neutral");

        double[] shuffledChanges = {76, 14, 41, 4, 3, 4, 84, -41, -4, -84, -14, -4, -76, -3};
        ArrayList<Double> shuffledSeedPrices = new ArrayList<>();
        double close = 1_000.0;
        shuffledSeedPrices.add(close);
        for (double change : shuffledChanges) {
            close += change;
            shuffledSeedPrices.add(close);
        }
        List<DailyPriceContext> shuffledSeed = LiquidationDailyPriceContextV1.build(
                dailyBars("BNB", start, shuffledSeedPrices, -1), List.of());
        assertEquals(50.0, byDay(shuffledSeed, "BNB").get(start.plusDays(14)).rsi14(),
                "seed sums with equal gains and losses stay exactly neutral regardless of change order");
    }

    @Test
    void smaNeedsExactlyTwoHundredContiguousClosesAndRollsByOneClose() {
        LocalDate start = LocalDate.of(2023, 1, 1);
        List<DailyPriceContext> rows = LiquidationDailyPriceContextV1.build(
                dailyBars("BTC", start, sequence(201, 1.0), -1), List.of());
        Map<LocalDate, DailyPriceContext> byDay = byDay(rows, "BTC");
        assertNull(byDay.get(start.plusDays(198)).sma200(), "199 closes are one short of the SMA");
        assertEquals(100.5, byDay.get(start.plusDays(199)).sma200(), 1e-12);
        assertEquals(101.5, byDay.get(start.plusDays(200)).sma200(), 1e-12,
                "the 201st close removes the oldest observation");

        ArrayList<Double> balancedAround100 = new ArrayList<>();
        balancedAround100.add(150.0); // leaves the 200-day window after the next close
        for (int index = 0; index < 99; index++) {
            balancedAround100.add(99.0);
            balancedAround100.add(101.0);
        }
        balancedAround100.add(100.0);
        balancedAround100.add(100.0);
        DailyPriceContext equalAfterRoll = byDay(LiquidationDailyPriceContextV1.build(
                dailyBars("ETH", start, balancedAround100, -1), List.of()), "ETH").get(start.plusDays(200));
        assertEquals(100.0, equalAfterRoll.dailyClose());
        assertEquals(100.0, equalAfterRoll.sma200(), 0.0,
                "exact decimal accumulation must preserve a neutral close equals SMA boundary after rolling");
    }

    @Test
    void aMissingDayResetsBothIndicatorsAndRequiresFreshWarmup() {
        LocalDate start = LocalDate.of(2023, 1, 1);
        ArrayList<LiquidationStructureRouterV1.Bar> bars = new ArrayList<>();
        for (int index = 0; index < 220; index++) {
            if (index == 4) continue;
            bars.addAll(dailyBars("BTC", start.plusDays(index), List.of(index + 1.0), -1));
        }
        Map<LocalDate, DailyPriceContext> byDay = byDay(LiquidationDailyPriceContextV1.build(bars, List.of()), "BTC");
        LocalDate gap = start.plusDays(4);
        assertFalse(byDay.containsKey(gap));
        assertNull(byDay.get(gap.plusDays(1)).rsi14());
        assertNull(byDay.get(gap.plusDays(1)).sma200());
        assertNull(byDay.get(gap.plusDays(14)).rsi14(), "the fourteenth post-gap close still has only thirteen changes");
        assertEquals(100.0, byDay.get(gap.plusDays(15)).rsi14());
        assertNull(byDay.get(gap.plusDays(199)).sma200(), "199 post-gap closes are insufficient");
        assertEquals(105.5, byDay.get(gap.plusDays(200)).sma200(), 1e-12);
    }

    @Test
    void indicatorsAreAssetLocalAndFutureClosesDoNotChangeAnEarlierContextRow() {
        LocalDate start = LocalDate.of(2024, 3, 1);
        List<LiquidationStructureRouterV1.Bar> retained = new ArrayList<>(dailyBars("BTC", start, sequence(15, 1.0), -1));
        retained.addAll(dailyBars("ETH", start, sequence(15, -1.0, 30.0), -1));
        List<DailyPriceContext> before = LiquidationDailyPriceContextV1.build(retained, List.of());
        DailyPriceContext btcAtSeed = byDay(before, "BTC").get(start.plusDays(14));
        assertEquals(100.0, btcAtSeed.rsi14());
        assertEquals(0.0, byDay(before, "ETH").get(start.plusDays(14)).rsi14());

        retained.addAll(dailyBars("BTC", start.plusDays(15), List.of(1.0), -1));
        DailyPriceContext btcEarlierWithFuture = byDay(
                LiquidationDailyPriceContextV1.build(retained, List.of()), "BTC").get(start.plusDays(14));
        assertEquals(btcAtSeed, btcEarlierWithFuture);
    }

    @Test
    void availabilityIncludesTheJustCompletedDayAtMidnightButNotBeforeIt() {
        LocalDate day = LocalDate.of(2024, 5, 10);
        DailyPriceContext row = LiquidationDailyPriceContextV1.build(
                dailyBars("BTC", day, List.of(100.0), -1), List.of()).getFirst();
        Instant dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertEquals(dayEnd, row.availableAt());
        assertFalse(row.availableAt().isAfter(dayEnd), "midnight decision may use the just-completed day");
        assertTrue(row.availableAt().isAfter(dayEnd.minusNanos(1)), "the same row is unavailable one nanosecond earlier");
    }

    @Test
    void anHourlyClosePublishedAfterUtcDayEndCannotCompleteThatDailyContext() {
        LocalDate day = LocalDate.of(2024, 5, 20);
        ArrayList<LiquidationStructureRouterV1.Bar> bars = new ArrayList<>(dailyBars("BTC", day, List.of(100.0), -1));
        LiquidationStructureRouterV1.Bar last = bars.removeLast();
        bars.add(new LiquidationStructureRouterV1.Bar(last.asset(), last.timeframe(), last.startTime(),
                last.availableAt().plusSeconds(1), last.open(), last.high(), last.low(), last.close(), last.seriesId()));
        assertTrue(LiquidationDailyPriceContextV1.build(bars, List.of()).isEmpty(),
                "a daily close cannot be exposed at midnight if an input hour was not available yet");
    }

    @Test
    void conflictingDuplicateBarsAndNonHourlyInputsFailClosed() {
        LocalDate day = LocalDate.of(2024, 6, 1);
        LiquidationStructureRouterV1.Bar first = bar("BTC", day, 0, 100.0, Timeframe.ONE_HOUR, Duration.ofHours(1));
        LiquidationStructureRouterV1.Bar conflict = bar("BTC", day, 0, 101.0, Timeframe.ONE_HOUR, Duration.ofHours(1));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationDailyPriceContextV1.build(List.of(first), List.of(conflict)));
        LiquidationStructureRouterV1.Bar fourHour = bar("BTC", day, 0, 100.0, Timeframe.FOUR_HOUR, Duration.ofHours(4));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationDailyPriceContextV1.build(List.of(fourHour), List.of()));
        LiquidationStructureRouterV1.Bar sameCloseLaterAvailable = new LiquidationStructureRouterV1.Bar(
                first.asset(), first.timeframe(), first.startTime(), first.availableAt().plusSeconds(1),
                first.open(), first.high(), first.low(), first.close(), first.seriesId());
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationDailyPriceContextV1.build(List.of(first), List.of(sameCloseLaterAvailable)));
    }

    private static List<Double> sequence(int count, double step) {
        return sequence(count, step, 1.0);
    }

    private static List<Double> sequence(int count, double step, double initial) {
        ArrayList<Double> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) values.add(initial + index * step);
        return values;
    }

    private static List<Double> repeated(int count, double value) {
        return java.util.Collections.nCopies(count, value);
    }

    private static List<LiquidationStructureRouterV1.Bar> dailyBars(String asset, LocalDate start,
            List<Double> closes, int omittedHour) {
        ArrayList<LiquidationStructureRouterV1.Bar> result = new ArrayList<>();
        for (int day = 0; day < closes.size(); day++) {
            for (int hour = 0; hour < 24; hour++) {
                if (hour == omittedHour) continue;
                result.add(bar(asset, start.plusDays(day), hour, closes.get(day), Timeframe.ONE_HOUR, Duration.ofHours(1)));
            }
        }
        return result;
    }

    private static LiquidationStructureRouterV1.Bar bar(String asset, LocalDate day, int hour,
            double close, Timeframe timeframe, Duration duration) {
        Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(hour));
        return new LiquidationStructureRouterV1.Bar(asset, timeframe, start, start.plus(duration),
                close, close, close, close, SERIES);
    }

    private static Map<LocalDate, DailyPriceContext> byDay(Collection<DailyPriceContext> rows, String asset) {
        TreeMap<LocalDate, DailyPriceContext> result = new TreeMap<>();
        for (DailyPriceContext row : rows) {
            if (row.asset().equals(asset)) result.put(row.utcDay(), row);
        }
        return result;
    }
}
