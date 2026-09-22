package com.tradinganalytics.research.v5;

import static com.tradinganalytics.research.v5.LiquidationStructureRouterV1.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Checks reason-coded rejection paths in the diagnostic-only daily entry audit. */
class LiquidationStructureRouterAuditGeometryTest {
    private static final LocalDate STRESS_DAY = LocalDate.of(2024, 1, 1);
    private static final Instant STRESS_START = STRESS_DAY.atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(8));
    private static final Instant DECISION_END = STRESS_DAY.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final String PRICE = "btc-4h-v1";
    private static final String OI = "btc-oi-v1";

    @Test
    void entryAuditExplainsUnqualifiedAndRejectedPriceOiGeometry() {
        List<GeometryCase> cases = List.of(
                new GeometryCase("oi decline below gate", LiquidationStructureRouterAuditGeometryTest::reduceOiDecline,
                        "fast_failure_reasons", "FAST_OI_DECLINE_BELOW_GATE", "GEOMETRY_REJECTED_NO_FAST_OR_SLOW_MATCH"),
                new GeometryCase("liquidation side mismatch", LiquidationStructureRouterAuditGeometryTest::reverseStressShock,
                        "fast_failure_reasons", "FAST_DIRECTION_MISMATCHES_LIQUIDATION_SIDE", "GEOMETRY_REJECTED_NO_FAST_OR_SLOW_MATCH"),
                new GeometryCase("undefined stress direction", LiquidationStructureRouterAuditGeometryTest::flattenStressDay,
                        "fast_failure_reasons", "FAST_SHOCK_DIRECTION_UNDEFINED", "GEOMETRY_REJECTED_NO_FAST_OR_SLOW_MATCH"),
                new GeometryCase("unavailable pre-event ATR", LiquidationStructureRouterAuditGeometryTest::removeAtrWarmup,
                        "fast_failure_reasons", "FAST_PRE_EVENT_ATR_UNAVAILABLE", "GEOMETRY_REJECTED_NO_FAST_OR_SLOW_MATCH"),
                new GeometryCase("selected move fails prior range break", LiquidationStructureRouterAuditGeometryTest::keepShockInsidePriorRange,
                        "selected_geometry_failure_reasons", "SELECTED_SHOCK_FAILED_PRIOR_24H_RANGE_BREAK",
                        "GEOMETRY_REJECTED_SELECTED_GEOMETRY"));

        for (GeometryCase testCase : cases) {
            List<Observation> observations = new ArrayList<>(LiquidationStructureRouterV1Test.fixture(
                    true, LiquidationStructureRouterV1Test.OiBoundary.VALID, false));
            testCase.mutation().accept(observations);

            JsonNode event = onlyStressEvent(observations);
            assertEquals(testCase.disposition(), event.path("disposition").asText(), testCase.name());
            assertTrue(contains(event.path("price_oi_geometry").path(testCase.failureList()), testCase.reason()),
                    testCase.name() + " should retain the specific geometry failure reason: " + event);
        }
    }

    private static JsonNode onlyStressEvent(List<Observation> observations) {
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        for (Observation observation : sorted(observations)) router.accept(observation);
        Instant windowStart = STRESS_DAY.minusDays(90).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(48));
        JsonNode events = router.entryRuleAuditSnapshot(windowStart, DECISION_END.plusNanos(1))
                .path("liquidation_stress_event_trace");
        assertEquals(1, events.size(), "fixture should produce exactly one stress event");
        return events.path(0);
    }

    private static boolean contains(JsonNode values, String expected) {
        for (JsonNode value : values) if (expected.equals(value.asText())) return true;
        return false;
    }

    private static void reduceOiDecline(List<Observation> observations) {
        Instant endpointSample = STRESS_START.plus(Duration.ofHours(4)).minus(Duration.ofMinutes(7));
        for (int index = 0; index < observations.size(); index++) {
            Observation observation = observations.get(index);
            if (observation instanceof OpenInterest oi && oi.observedAt().equals(endpointSample)) {
                observations.set(index, new OpenInterest(oi.asset(), oi.observedAt(), oi.availableAt(), 97, oi.seriesId()));
                return;
            }
        }
        throw new AssertionError("fixture fast-event OI endpoint is missing");
    }

    private static void reverseStressShock(List<Observation> observations) {
        replaceBar(observations, STRESS_START, 100, 103.2, 99.8, 103);
    }

    private static void flattenStressDay(List<Observation> observations) {
        Instant start = STRESS_DAY.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = start.plus(Duration.ofDays(1));
        List<Instant> starts = observations.stream().filter(Bar.class::isInstance).map(Bar.class::cast)
                .filter(bar -> bar.timeframe() == Timeframe.FOUR_HOUR
                        && !bar.startTime().isBefore(start) && bar.startTime().isBefore(end))
                .map(Bar::startTime).toList();
        for (Instant barStart : starts) replaceBar(observations, barStart, 100, 100.2, 99.8, 100);
    }

    private static void removeAtrWarmup(List<Observation> observations) {
        Instant stressDayStart = STRESS_DAY.atStartOfDay(ZoneOffset.UTC).toInstant();
        observations.removeIf(observation -> observation instanceof Bar bar
                && bar.timeframe() == Timeframe.FOUR_HOUR && bar.startTime().isBefore(stressDayStart));
    }

    private static void keepShockInsidePriorRange(List<Observation> observations) {
        // A single wider pre-event candle lifts the ATR while leaving a low boundary below the
        // qualifying shock close. The move clears the ATR gate but fails the range-break rule.
        replaceBar(observations, STRESS_START.minus(Duration.ofHours(24)), 100, 100.5, 89.9, 100);
        replaceBar(observations, STRESS_START, 100, 100.2, 89.7, 90);
    }

    private static void replaceBar(List<Observation> observations, Instant start,
            double open, double high, double low, double close) {
        observations.removeIf(observation -> observation instanceof Bar bar
                && bar.timeframe() == Timeframe.FOUR_HOUR && bar.startTime().equals(start));
        observations.add(new Bar("BTC", Timeframe.FOUR_HOUR, start, start.plus(Duration.ofHours(4)),
                open, high, low, close, PRICE));
    }

    private static List<Observation> sorted(List<Observation> observations) {
        return observations.stream().sorted(Comparator
                .comparing(LiquidationStructureRouterAuditGeometryTest::processingTime)
                .thenComparing(Observation::eventTime)
                .thenComparingInt(LiquidationStructureRouterAuditGeometryTest::priority)
                .thenComparing(Observation::asset)
                .thenComparing(Observation::seriesId)).toList();
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

    private record GeometryCase(String name, Consumer<List<Observation>> mutation,
            String failureList, String reason, String disposition) {}
}
