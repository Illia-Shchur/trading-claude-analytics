package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parser, row-contract, and frozen daily-gap boundary vectors independent of archive downloads. */
class LiquidationPhysicalBoundaryV1Test {
    private static final long DAY_MS = 86_400_000L;
    private static final long START = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();

    @Test
    void acceptsOnlyCanonicalMillisecondTimesAndFrozenCadences() {
        LiquidationV2PhysicalDataV1.validateRows("label", List.of(label("2024-01-01T00:00:00.000Z")));
        LiquidationV2PhysicalDataV1.validateRows("label", List.of(label(START)));

        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.validateRows("label", List.of(label(START + 0.5))));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.validateRows("label", List.of(label("2024-01-01T00:00:00+00:00"))));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.validateRows("label", List.of(label("2024-01-01T00:00:00.000000001Z"))));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.validateRows("label", List.of(label("not-an-instantZ"))));
        ObjectNode missing = label(START);
        missing.putNull("decision_time");
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.validateRows("label", List.of(missing)));

        for (String timeframe : List.of("1m", "1h", "4h", "1d")) {
            long cadence = switch (timeframe) {
                case "1m" -> 60_000L;
                case "1h" -> 3_600_000L;
                case "4h" -> 14_400_000L;
                default -> DAY_MS;
            };
            ObjectNode feature = price(timeframe, START, cadence);
            assertFalse(LiquidationV2PhysicalDataV1.partitionCoverage("feature", List.of(feature))
                    .path("groups").isEmpty());
        }
        ObjectNode unsupported = price("2h", START, 7_200_000L);
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.partitionCoverage("feature", List.of(unsupported)));

        ObjectNode duplicateA = label(START), duplicateB = label(START + 1_000L);
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.validateRows("label", List.of(duplicateA, duplicateB)));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.validateRows("unfrozen-role", List.of(label(START))));
    }

    @Test
    void rejectsInvalidFundingSlotsAndIntervalsBeforeCoverageCanQualifyThem() {
        ObjectNode first = funding(START + 7, "funding-1", 8);
        ObjectNode second = funding(START + 8 * 3_600_000L + 7, "funding-2", 8);
        LiquidationV2PhysicalDataV1.validateRows("funding", List.of(first, second));

        ObjectNode repeatedSlot = funding(START + 7, "funding-other-id", 8);
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.validateRows("funding", List.of(first, repeatedSlot)));
        for (double invalidHours : List.of(0.0, 1.5, 25.0)) {
            ObjectNode invalid = funding(START + 7, "bad-hours-" + invalidHours, invalidHours);
            assertThrows(IllegalArgumentException.class,
                    () -> LiquidationV2PhysicalDataV1.validateRows("funding", List.of(invalid)));
        }
        ObjectNode blankId = funding(START + 7, "", 8);
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.validateRows("funding", List.of(blankId)));
        ObjectNode nullInterval = funding(START + 7, "funding-null-interval", 8);
        nullInterval.putNull("funding_interval_hours");
        LiquidationV2PhysicalDataV1.validateRows("funding", List.of(nullInterval));
    }

    @Test
    void frozenAaveCoverageAcceptsExactlyDeclaredMissingDaysAndRejectsBoundaryDrift() {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode plan = LiquidationV2PhysicalDataV1.frozenPlan(profile);
        long requiredStart = Instant.parse(plan.path("source_start").asText()).toEpochMilli();
        long requiredEnd = Instant.parse(plan.path("decision_end_exclusive").asText()).minusSeconds(2 * 86_400L).toEpochMilli();
        String aaveKey = "feature/AAVE/daily_liquidation_usd/1d/LONG";
        CoverageFixture aave = exactDailyCoverage(plan, aaveKey, requiredStart, requiredEnd, true);
        assertTrue(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(aave.coverage(), aaveKey,
                requiredStart, requiredEnd, plan));

        ObjectNode noGroups = JsonHashes.mapper().createObjectNode();
        noGroups.putArray("groups");
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(noGroups, aaveKey,
                requiredStart, requiredEnd, plan));
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(aave.coverage(), aaveKey,
                requiredStart, requiredStart, plan));
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(aave.coverage(), aaveKey,
                requiredStart + 1, requiredEnd, plan));

        ObjectNode rowCountTamper = aave.coverage().deepCopy();
        ((ObjectNode) rowCountTamper.path("groups").get(0)).put("row_count", aave.expectedRows() - 1);
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(rowCountTamper, aaveKey,
                requiredStart, requiredEnd, plan));

        ObjectNode segmentTamper = aave.coverage().deepCopy();
        ArrayNode segments = (ArrayNode) segmentTamper.path("groups").get(0).path("segments");
        ((ArrayNode) segments.get(0)).set(1, JsonHashes.mapper().getNodeFactory()
                .numberNode(segments.get(0).path(1).asLong() - DAY_MS));
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(segmentTamper, aaveKey,
                requiredStart, requiredEnd, plan));

        String btcKey = "feature/BTC/daily_liquidation_usd/1d/LONG";
        CoverageFixture btc = exactDailyCoverage(plan, btcKey, requiredStart, requiredEnd, false);
        assertTrue(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(btc.coverage(), btcKey,
                requiredStart, requiredEnd, plan), "other assets must inventory a continuous full daily envelope");
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(btc.coverage(), aaveKey,
                requiredStart, requiredEnd, plan), "AAVE exclusions cannot be borrowed by another frozen asset");
    }

    private static CoverageFixture exactDailyCoverage(ObjectNode plan, String key, long start, long end,
            boolean aaveGaps) {
        ArrayList<Long> allMissing = new ArrayList<>();
        if (aaveGaps) plan.path("daily_feature_gap_exclusions").get(0).path("missing_event_days_utc")
                .forEach(day -> allMissing.add(LocalDate.parse(day.asText()).atStartOfDay(ZoneOffset.UTC)
                        .toInstant().toEpochMilli()));
        List<Long> missing = allMissing.stream().filter(day -> day >= start && day < end).sorted().toList();
        long totalDays = (end - start) / DAY_MS;
        long expectedRows = totalDays - missing.size();
        ObjectNode group = JsonHashes.mapper().createObjectNode().put("key", key).put("row_count", expectedRows);
        ArrayNode segments = group.putArray("segments");
        long cursor = start;
        for (long gap : missing) {
            if (gap > cursor) segments.addArray().add(cursor).add(gap).add((gap - cursor) / DAY_MS);
            cursor = gap + DAY_MS;
        }
        if (cursor < end) segments.addArray().add(cursor).add(end).add((end - cursor) / DAY_MS);
        ObjectNode coverage = JsonHashes.mapper().createObjectNode();
        coverage.putArray("groups").add(group);
        return new CoverageFixture(coverage, expectedRows);
    }

    private static ObjectNode price(String timeframe, long event, long duration) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "price_ohlc").put("timeframe", timeframe).put("event_time", event)
                .put("availability_time", event + duration).put("open", 100).put("high", 102).put("low", 98)
                .put("close", 101).put("base_volume", 1);
    }

    private static ObjectNode label(Object time) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("episode_id", "label-1").put("label", "UNINSPECTED");
        if (time instanceof String value) row.put("decision_time", value);
        else if (time instanceof Long value) row.put("decision_time", value.longValue());
        else if (time instanceof Integer value) row.put("decision_time", value.longValue());
        else if (time instanceof Number value) row.put("decision_time", value.doubleValue());
        else throw new IllegalArgumentException("unsupported test time type");
        return row;
    }

    private static ObjectNode funding(long time, String eventId, double intervalHours) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("settlement_time", time)
                .put("event_id", eventId).put("funding_rate", 0.001).put("mark_price", 100)
                .put("funding_interval_hours", intervalHours);
    }

    private record CoverageFixture(ObjectNode coverage, long expectedRows) {}
}
