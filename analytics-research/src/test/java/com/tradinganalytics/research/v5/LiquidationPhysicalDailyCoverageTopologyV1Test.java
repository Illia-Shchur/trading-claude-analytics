package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests daily feature coverage as exact UTC-day topology, including frozen AAVE exclusions. */
class LiquidationPhysicalDailyCoverageTopologyV1Test {
    private static final long DAY = 86_400_000L;
    private static final String AAVE_KEY = "feature/AAVE/daily_liquidation_usd/1d/LONG";
    private static final String BTC_KEY = "feature/BTC/daily_liquidation_usd/1d/LONG";
    private static final long START = day("2024-01-01");
    private static final long END = day("2024-01-05");

    @Test
    void completeAndExactlyExcludedDailyEnvelopesAreAccepted() {
        ObjectNode noGaps = plan(List.of());
        assertTrue(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                coverage(BTC_KEY, 4, new long[][] {{START, END}}), BTC_KEY, START, END, noGaps));

        ObjectNode frozenGapInventory = plan(List.of(
                exclusion("AAVE", "2024-01-02", "2024-01-03", "2024-01-02"),
                exclusion("ETH", "2024-01-02")));
        ObjectNode exact = coverage(AAVE_KEY, 2,
                new long[][] {{START, day("2024-01-02")}, {day("2024-01-04"), END}});
        assertTrue(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                exact, AAVE_KEY, START, END, frozenGapInventory),
                "adjacent declared missing days merge into one exact gap, duplicates deduplicate, and other assets are ignored");

        ObjectNode withOutsideSegments = coverage(AAVE_KEY, 2, new long[][] {
                {START - DAY, START}, {START, day("2024-01-02")},
                {day("2024-01-04"), END}, {END, END + DAY}});
        assertTrue(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                withOutsideSegments, AAVE_KEY, START, END, frozenGapInventory),
                "segments wholly outside the required envelope do not distort in-envelope accounting");
    }

    @Test
    void malformedOrIncompleteDailyTopologyFailsClosedAtEachBoundary() {
        ObjectNode noGaps = plan(List.of());
        ObjectNode exact = coverage(BTC_KEY, 4, new long[][] {{START, END}});
        ObjectNode aaveWithGap = coverage(AAVE_KEY, 3,
                new long[][] {{START, day("2024-01-02")}, {day("2024-01-03"), END}});
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                aaveWithGap, AAVE_KEY, START, END, plan(List.of(exclusion("ETH", "2024-01-02")))),
                "an ETH exclusion cannot be silently borrowed by AAVE");
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                coverage(BTC_KEY, 3, new long[][] {{START, END}}), BTC_KEY, START, END, noGaps));
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                coverage(BTC_KEY, 4, new long[0][]), BTC_KEY, START, END, noGaps));
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                coverage(BTC_KEY, 4, new long[][] {{START - DAY, START}}), BTC_KEY, START, END, noGaps));
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                coverage(BTC_KEY, 4, new long[][] {{START, END - 1}}), BTC_KEY, START, END, noGaps));
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                coverage(BTC_KEY, 4, new long[][] {{START + 1, END}}), BTC_KEY, START, END, noGaps));
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                coverage(BTC_KEY, 4, new long[][] {{day("2024-01-02"), day("2024-01-02")}}), BTC_KEY, START, END, noGaps));
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                coverage(BTC_KEY, 4, new long[][] {{START, day("2024-01-03")},
                        {day("2024-01-02"), END}}), BTC_KEY, START, END, noGaps),
                "overlapping/reordered coverage cannot double-count market days");
        assertTrue(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                coverage(BTC_KEY, 4, new long[][] {{START, END + DAY}}), BTC_KEY, START, END, noGaps),
                "a continuous segment that extends beyond the frozen exclusive boundary is clipped to the requested envelope");
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                coverage(BTC_KEY, 4, new long[][] {{day("2024-01-02"), END}}), BTC_KEY, START, END, noGaps),
                "unexplained leading gaps are rejected even when the row count looks close");
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                exact, BTC_KEY, START + 1, END, noGaps));
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(
                exact, BTC_KEY, START, START, noGaps));
    }

    private static ObjectNode plan(List<ObjectNode> exclusions) {
        ObjectNode plan = JsonHashes.mapper().createObjectNode();
        ArrayNode rows = plan.putArray("daily_feature_gap_exclusions");
        exclusions.forEach(rows::add);
        return plan;
    }

    private static ObjectNode exclusion(String asset, String... dates) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("asset", asset)
                .put("series_id", "daily_liquidation_usd");
        ArrayNode days = row.putArray("missing_event_days_utc");
        for (String date : dates) days.add(date);
        return row;
    }

    private static ObjectNode coverage(String key, long rowCount, long[][] segments) {
        ObjectNode coverage = JsonHashes.mapper().createObjectNode().put("row_count", rowCount);
        ObjectNode group = coverage.putArray("groups").addObject().put("key", key).put("row_count", rowCount);
        ArrayNode output = group.putArray("segments");
        for (long[] segment : segments) output.addArray().add(segment[0]).add(segment[1]);
        return coverage;
    }

    private static long day(String text) {
        return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
