package com.tradinganalytics.research.v5;

import static com.tradinganalytics.research.v5.LiquidationStructureRouterV1.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Public routed stage-two macro assessment: missing, stale, discontinuous and unprovenanced samples fail closed. */
class LiquidationStructureRouterMacroAssessmentResidualMatrixTest {
    private static final LocalDate STRESS_DAY = LocalDate.of(2024, 1, 1);
    private static final Instant STRESS_START = utc(STRESS_DAY, 8);
    private static final Instant MODEL_AVAILABLE = utc(STRESS_DAY.plusDays(2), 0);
    private static final String PRICE = "macro-residual-h4-v1";
    private static final String HOUR = "macro-residual-h1-v1";
    private static final String OI = "macro-residual-oi-v1";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void allIncompleteMacroEvidenceModesRemainUnknownAndBlockTheSameCausalAdd() {
        assertUnknown(List.of(), "fewer than six close observations");
        assertUnknown(macroCloses(List.of(
                LocalDate.of(2023, 12, 11), LocalDate.of(2023, 12, 12), LocalDate.of(2023, 12, 13),
                LocalDate.of(2023, 12, 14), LocalDate.of(2023, 12, 15), LocalDate.of(2023, 12, 18)), -1),
                "six verified but stale daily closes");
        assertUnknown(macroCloses(List.of(
                LocalDate.of(2023, 12, 21), LocalDate.of(2023, 12, 22), LocalDate.of(2023, 12, 26),
                LocalDate.of(2023, 12, 28), LocalDate.of(2023, 12, 29), LocalDate.of(2024, 1, 2)), -1),
                "a skipped regular session in the six-close history");
        assertUnknown(macroCloses(List.of(
                LocalDate.of(2023, 12, 22), LocalDate.of(2023, 12, 26), LocalDate.of(2023, 12, 27),
                LocalDate.of(2023, 12, 28), LocalDate.of(2023, 12, 29), LocalDate.of(2024, 1, 2)), 3),
                "a latest-six close without point-in-time provenance");
    }

    @Test
    void contiguousProvenancedNeutralHistoryReachesTheSameStageTwoGeometry() {
        Run run = route(macroCloses(List.of(
                LocalDate.of(2023, 12, 22), LocalDate.of(2023, 12, 26), LocalDate.of(2023, 12, 27),
                LocalDate.of(2023, 12, 28), LocalDate.of(2023, 12, 29), LocalDate.of(2024, 1, 2)), -1), false);
        ConfirmedIntent stageTwo = run.intents().stream().filter(intent -> intent.stage() == 2)
                .findFirst().orElseThrow(() -> new AssertionError("valid neutral macro history must reach stage-two intent"));
        assertEquals(MacroState.NEUTRAL, stageTwo.macroState());
        assertTrue(stageTwo.macroEligible());
    }

    @Test
    void dailySeriesIdentityChangeIsRejectedBeforeGeometryEvaluation() {
        Run validBaseline = route(List.of(), false);
        assertEquals(1, validBaseline.events().size(), "the unchanged 90-row series reaches qualified geometry");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> route(List.of(), true));
        assertEquals("daily liquidation series identity changed for BTC", failure.getMessage());
    }

    private static void assertUnknown(List<Observation> macro, String description) {
        Run run = route(macro, false);
        assertEquals(1, run.intents().stream().filter(intent -> intent.stage() == 1).count(), description);
        RejectedOpportunity stageTwo = run.rejections().stream().filter(row -> row.stage() == 2).findFirst().orElseThrow(() -> new AssertionError(description));
        assertEquals(MacroState.UNKNOWN, stageTwo.macroState(), description);
        assertTrue(stageTwo.reasonCode().contains("MACRO_UNKNOWN_BLOCKS_STAGE_2"), description);
        assertTrue(run.intents().stream().noneMatch(intent -> intent.stage() == 2),
                "unknown macro evidence cannot produce a stage-two intent: " + description);
        assertTrue(stageTwo.sourceEvidence().stream().filter(source -> source.role().equals("SP500_MACRO")).count() <= 6,
                "the assessment retains no more than the exact six-close window");
    }

    private static Run route(List<Observation> macro, boolean dailySeriesMismatch) {
        List<Observation> all = fixture(dailySeriesMismatch);
        all.addAll(macro);
        all.addAll(postInitialFillBars());
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION, MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION);
        List<ConfirmedIntent> intents = new ArrayList<>();
        List<RejectedOpportunity> rejections = new ArrayList<>();
        List<QualifiedDailyStressEvent> events = new ArrayList<>();
        for (Observation observation : sorted(all)) {
            RouteResult result = router.accept(observation);
            intents.addAll(result.intents()); rejections.addAll(result.rejections());
            events.addAll(result.qualifiedDailyStressEvents());
            for (ConfirmedIntent intent : result.intents()) if (intent.stage() == 1) {
                router.onFill(new FillAck(intent.intentId(), intent.setupId(), intent.asset(), 1,
                        intent.requestedExecutionAfter().plusNanos(1), intent.confirmationClose(), intent.initialStop()));
            }
        }
        return new Run(List.copyOf(intents), List.copyOf(rejections), List.copyOf(events));
    }

    private record Run(List<ConfirmedIntent> intents, List<RejectedOpportunity> rejections,
            List<QualifiedDailyStressEvent> events) {}

    private static List<Observation> macroCloses(List<LocalDate> dates, int unprovenancedIndex) {
        List<Observation> result = new ArrayList<>();
        for (int index = 0; index < dates.size(); index++) {
            LocalDate date = dates.get(index);
            LocalDate next = nextTradingDate(date);
            result.add(new MacroClose(date.atTime(16, 0).atZone(NEW_YORK).toInstant(),
                    next.atTime(16, 0).atZone(NEW_YORK).toInstant(), 100.0,
                    index != unprovenancedIndex, "sp500-macro-residual-v1"));
        }
        return result;
    }

    private static List<Observation> fixture(boolean dailySeriesMismatch) {
        List<Observation> result = new ArrayList<>();
        for (int lag = DAILY_LOOKBACK_DAYS; lag >= 1; lag--) {
            LocalDate date = STRESS_DAY.minusDays(lag);
            Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            String series = dailySeriesMismatch && lag == 17 ? "other-daily-series" : "daily-macro-residual-v1";
            result.add(new DailyLiquidation("BTC", date, 1, 1, start.plus(Duration.ofHours(24)), series));
        }
        Instant dayStart = STRESS_DAY.atStartOfDay(ZoneOffset.UTC).toInstant();
        result.add(new DailyLiquidation("BTC", STRESS_DAY, 2, 2,
                dayStart.plus(Duration.ofHours(24)), "daily-macro-residual-v1"));

        Instant firstH4 = STRESS_START.minus(Duration.ofHours(4L * 180));
        Instant h4End = MODEL_AVAILABLE.plus(Duration.ofHours(8));
        for (Instant start = firstH4; start.isBefore(h4End); start = start.plus(Duration.ofHours(4))) {
            double open = 100, high = 100.5, low = 99.5, close = 100;
            if (start.equals(STRESS_START)) { open = 100; high = 100.2; low = 96.7; close = 97; }
            if (start.equals(MODEL_AVAILABLE.minus(Duration.ofHours(4)))) { open = 98; high = 98.5; low = 96.5; close = 97; }
            if (start.equals(MODEL_AVAILABLE)) { open = 97; high = 97.5; low = 96; close = 96.8; }
            if (start.equals(MODEL_AVAILABLE.plus(Duration.ofHours(4)))) { open = 96.8; high = 97.2; low = 95.8; close = 96.5; }
            result.add(new Bar("BTC", Timeframe.FOUR_HOUR, start, start.plus(Duration.ofHours(4)),
                    open, high, low, close, PRICE));
        }
        Instant startObserved = STRESS_START.minus(Duration.ofMinutes(7));
        Instant end = STRESS_START.plus(Duration.ofHours(4));
        Instant endObserved = end.minus(Duration.ofMinutes(7));
        result.add(new OpenInterest("BTC", startObserved, startObserved.plusSeconds(1), 100, OI));
        result.add(new OpenInterest("BTC", endObserved, endObserved.plusSeconds(1), 90, OI));
        addHour(result, MODEL_AVAILABLE.plus(Duration.ofHours(2)), 100, 100.2, 99.8, 100);
        addHour(result, MODEL_AVAILABLE.plus(Duration.ofHours(3)), 100, 100.2, 99.8, 100);
        result.add(new Bar("BTC", Timeframe.ONE_HOUR, MODEL_AVAILABLE.plus(Duration.ofHours(4)),
                MODEL_AVAILABLE.plus(Duration.ofHours(5)), 99.7, 99.8, 99.4, 99.45, HOUR));
        addHour(result, MODEL_AVAILABLE.plus(Duration.ofHours(5)), 100, 100.2, 99.8, 100);
        addHour(result, MODEL_AVAILABLE.plus(Duration.ofHours(6)), 100, 100.2, 99.8, 100);
        result.add(new Bar("BTC", Timeframe.ONE_HOUR, MODEL_AVAILABLE.plus(Duration.ofHours(7)),
                MODEL_AVAILABLE.plus(Duration.ofHours(8)), 100, 100.2, 99.8, 100, HOUR));
        result.add(new Bar("BTC", Timeframe.ONE_HOUR, MODEL_AVAILABLE.plus(Duration.ofHours(8)),
                MODEL_AVAILABLE.plus(Duration.ofHours(9)), 99.7, 99.8, 99.4, 99.45, HOUR));
        return result;
    }

    private static List<Observation> postInitialFillBars() {
        List<Observation> result = new ArrayList<>();
        double[][] h4 = {{96, 99.4, 94, 97}, {97, 99.5, 94.5, 98}, {98, 99.8, 94, 99},
                {99, 99.5, 93.5, 99.2}, {99.2, 99.4, 92.5, 99.3}};
        Instant firstStart = MODEL_AVAILABLE.plus(Duration.ofHours(8));
        for (int i = 0; i < h4.length; i++) {
            double[] v = h4[i]; Instant start = firstStart.plus(Duration.ofHours(4L * i));
            result.add(new Bar("BTC", Timeframe.FOUR_HOUR, start, start.plus(Duration.ofHours(4)),
                    v[0], v[1], v[2], v[3], PRICE));
        }
        Instant favorable = firstStart.plus(Duration.ofHours(20));
        result.add(new Bar("BTC", Timeframe.FOUR_HOUR, favorable, favorable.plus(Duration.ofHours(4)),
                99.3, 99.6, 98.5, 98.9, PRICE));
        addHour(result, utc(LocalDate.of(2024, 1, 4), 6), 100, 100.1, 99.9, 100);
        result.add(new Bar("BTC", Timeframe.ONE_HOUR, utc(LocalDate.of(2024, 1, 4), 7),
                utc(LocalDate.of(2024, 1, 4), 8), 99.9, 100, 99.5, 99.6, HOUR));
        double[][] laterH4 = {{93, 98.9, 91.5, 92}, {92, 99, 91, 91}, {91, 100.2, 90, 90},
                {90, 99.5, 89, 89}, {89, 99, 88, 88}};
        Instant laterStart = utc(STRESS_DAY.plusDays(4), 0);
        for (int i = 0; i < laterH4.length; i++) {
            double[] v = laterH4[i]; Instant start = laterStart.plus(Duration.ofHours(4L * i));
            result.add(new Bar("BTC", Timeframe.FOUR_HOUR, start, start.plus(Duration.ofHours(4)),
                    v[0], v[1], v[2], v[3], PRICE));
        }
        addHour(result, utc(LocalDate.of(2024, 1, 4), 20), 100, 100.1, 99.95, 100);
        result.add(new Bar("BTC", Timeframe.ONE_HOUR, utc(LocalDate.of(2024, 1, 4), 21),
                utc(LocalDate.of(2024, 1, 4), 22), 100, 100.1, 99.6, 99.7, HOUR));
        return result;
    }

    private static void addHour(List<Observation> out, Instant start, double open, double high, double low, double close) {
        out.add(new Bar("BTC", Timeframe.ONE_HOUR, start, start.plus(Duration.ofHours(1)),
                open, high, low, close, HOUR));
    }

    private static LocalDate nextTradingDate(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (next.getDayOfWeek().getValue() > 5 || next.equals(LocalDate.of(2024, 1, 1))
                || next.equals(LocalDate.of(2023, 12, 25))) next = next.plusDays(1);
        return next;
    }

    private static Instant utc(LocalDate date, int hour) {
        return date.atTime(LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC);
    }

    private static List<Observation> sorted(List<Observation> observations) {
        return observations.stream().sorted(Comparator.comparing(LiquidationStructureRouterMacroAssessmentResidualMatrixTest::processingTime)
                .thenComparing(Observation::eventTime).thenComparingInt(LiquidationStructureRouterMacroAssessmentResidualMatrixTest::priority)
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
