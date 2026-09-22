package com.tradinganalytics.research.v5;

import static com.tradinganalytics.research.v5.LiquidationStructureRouterV1.DailyPriceContext;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Builds causal same-asset UTC daily price context from retained and supplemental H1 bars. */
public final class LiquidationDailyPriceContextV1 {
    public static final String SERIES_ID = "liquidation-daily-price-context-v1";
    public static final int RSI_PERIOD = 14;
    public static final int RSI_SEED_CLOSES = RSI_PERIOD + 1;
    public static final int SMA_PERIOD = 200;
    private static final int HOUR_COUNT = 24;
    private static final BigDecimal SMA_DIVISOR = BigDecimal.valueOf(SMA_PERIOD);

    private LiquidationDailyPriceContextV1() {}

    /**
     * Combines the frozen hourly stream and supplemental warmup only within this calculator.
     * Incomplete UTC days are omitted and reset both indicator warmups.
     */
    public static List<DailyPriceContext> build(
            Collection<LiquidationStructureRouterV1.Bar> retainedHourlyBars,
            Collection<LiquidationStructureRouterV1.Bar> supplementalWarmupBars) {
        Objects.requireNonNull(retainedHourlyBars, "retainedHourlyBars");
        Objects.requireNonNull(supplementalWarmupBars, "supplementalWarmupBars");
        TreeMap<String, NavigableMap<Instant, HourlyClose>> byAsset = new TreeMap<>();
        addBars(byAsset, retainedHourlyBars);
        addBars(byAsset, supplementalWarmupBars);

        ArrayList<DailyPriceContext> result = new ArrayList<>();
        for (Map.Entry<String, NavigableMap<Instant, HourlyClose>> entry : byAsset.entrySet()) {
            result.addAll(buildAsset(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    private static void addBars(Map<String, NavigableMap<Instant, HourlyClose>> byAsset,
            Collection<LiquidationStructureRouterV1.Bar> bars) {
        for (LiquidationStructureRouterV1.Bar bar : bars) {
            Objects.requireNonNull(bar, "hourly bar");
            if (bar.timeframe() != LiquidationStructureRouterV1.Timeframe.ONE_HOUR) {
                throw new IllegalArgumentException("daily price context accepts only one-hour bars");
            }
            NavigableMap<Instant, HourlyClose> series = byAsset.computeIfAbsent(bar.asset(), ignored -> new TreeMap<>());
            HourlyClose incoming = new HourlyClose(bar.close(), bar.availableAt());
            HourlyClose previous = series.putIfAbsent(bar.startTime(), incoming);
            if (previous != null && (!same(previous.close(), incoming.close())
                    || !previous.availableAt().equals(incoming.availableAt()))) {
                throw new IllegalArgumentException("conflicting same-asset hourly bars share a start time");
            }
        }
    }

    private static List<DailyPriceContext> buildAsset(String asset, NavigableMap<Instant, HourlyClose> hours) {
        TreeMap<LocalDate, HourlyClose[]> byDay = new TreeMap<>();
        for (Map.Entry<Instant, HourlyClose> entry : hours.entrySet()) {
            LocalDate day = entry.getKey().atZone(ZoneOffset.UTC).toLocalDate();
            int hour = entry.getKey().atZone(ZoneOffset.UTC).getHour();
            byDay.computeIfAbsent(day, ignored -> new HourlyClose[HOUR_COUNT])[hour] = entry.getValue();
        }

        ArrayList<DailyPriceContext> result = new ArrayList<>();
        IndicatorState state = new IndicatorState();
        LocalDate previousCalendarDay = null;
        for (Map.Entry<LocalDate, HourlyClose[]> entry : byDay.entrySet()) {
            LocalDate day = entry.getKey();
            if (previousCalendarDay != null && !day.equals(previousCalendarDay.plusDays(1))) {
                state.reset();
            }
            Instant availableAt = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            HourlyClose[] bars = entry.getValue();
            boolean complete = true;
            for (HourlyClose bar : bars) {
                if (bar == null || bar.availableAt().isAfter(availableAt)) {
                    complete = false;
                    break;
                }
            }
            if (!complete) {
                state.reset();
                previousCalendarDay = day;
                continue;
            }
            double dailyClose = bars[HOUR_COUNT - 1].close();
            IndicatorValues values = state.add(dailyClose);
            result.add(new DailyPriceContext(asset, day, availableAt, dailyClose,
                    values.rsi14(), values.sma200(), SERIES_ID));
            previousCalendarDay = day;
        }
        return result;
    }

    private static boolean same(double left, double right) {
        return Double.doubleToLongBits(left) == Double.doubleToLongBits(right);
    }

    private record HourlyClose(double close, Instant availableAt) {}
    private record IndicatorValues(Double rsi14, Double sma200) {}

    private static final class IndicatorState {
        private final Deque<Double> smaWindow = new ArrayDeque<>(SMA_PERIOD);
        private BigDecimal smaSum = BigDecimal.ZERO;
        private BigDecimal priorClose;
        private boolean hasPriorClose;
        private int seedChanges;
        private BigDecimal seedGainSum = BigDecimal.ZERO;
        private BigDecimal seedLossSum = BigDecimal.ZERO;
        private BigDecimal averageGain;
        private BigDecimal averageLoss;

        IndicatorValues add(double close) {
            Double rsi = addRsi(close);
            Double sma = addSma(close);
            return new IndicatorValues(rsi, sma);
        }

        private Double addRsi(double close) {
            Double value = null;
            BigDecimal currentClose = BigDecimal.valueOf(close);
            if (hasPriorClose) {
                BigDecimal change = currentClose.subtract(priorClose);
                BigDecimal gain = change.signum() > 0 ? change : BigDecimal.ZERO;
                BigDecimal loss = change.signum() < 0 ? change.negate() : BigDecimal.ZERO;
                if (averageGain == null) {
                    seedChanges++;
                    seedGainSum = seedGainSum.add(gain);
                    seedLossSum = seedLossSum.add(loss);
                    if (seedChanges == RSI_PERIOD) {
                        averageGain = seedGainSum.divide(BigDecimal.valueOf(RSI_PERIOD), MathContext.DECIMAL128);
                        averageLoss = seedLossSum.divide(BigDecimal.valueOf(RSI_PERIOD), MathContext.DECIMAL128);
                        value = rsiValue(averageGain, averageLoss);
                    }
                } else {
                    BigDecimal period = BigDecimal.valueOf(RSI_PERIOD);
                    BigDecimal retainedPeriods = BigDecimal.valueOf(RSI_PERIOD - 1L);
                    averageGain = averageGain.multiply(retainedPeriods).add(gain)
                            .divide(period, MathContext.DECIMAL128);
                    averageLoss = averageLoss.multiply(retainedPeriods).add(loss)
                            .divide(period, MathContext.DECIMAL128);
                    value = rsiValue(averageGain, averageLoss);
                }
            }
            priorClose = currentClose;
            hasPriorClose = true;
            return value;
        }

        private Double addSma(double close) {
            BigDecimal decimalClose = BigDecimal.valueOf(close);
            if (smaWindow.size() < SMA_PERIOD) {
                smaWindow.addLast(close);
                smaSum = smaSum.add(decimalClose);
            } else {
                double removed = smaWindow.removeFirst();
                smaWindow.addLast(close);
                smaSum = smaSum.subtract(BigDecimal.valueOf(removed)).add(decimalClose);
            }
            return smaWindow.size() == SMA_PERIOD
                    ? smaSum.divide(SMA_DIVISOR, MathContext.DECIMAL128).doubleValue()
                    : null;
        }

        void reset() {
            smaWindow.clear();
            smaSum = BigDecimal.ZERO;
            priorClose = null;
            hasPriorClose = false;
            seedChanges = 0;
            seedGainSum = BigDecimal.ZERO;
            seedLossSum = BigDecimal.ZERO;
            averageGain = null;
            averageLoss = null;
        }
    }

    private static double rsiValue(BigDecimal averageGain, BigDecimal averageLoss) {
        if (averageGain.signum() == 0 && averageLoss.signum() == 0) return 50.0;
        if (averageLoss.signum() == 0) return 100.0;
        if (averageGain.signum() == 0) return 0.0;
        BigDecimal relativeLoss = averageLoss.divide(averageGain, MathContext.DECIMAL128);
        return BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(relativeLoss), MathContext.DECIMAL128).doubleValue();
    }
}
