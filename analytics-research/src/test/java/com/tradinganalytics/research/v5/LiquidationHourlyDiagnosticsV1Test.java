package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class LiquidationHourlyDiagnosticsV1Test {
    private static final String CORE = "CORE_ONE_ENTRY";
    private static final String NO_MACRO = "STAGED_NO_MACRO";
    private static final String MACRO = "STAGED_MACRO";
    private static final Instant BASE = Instant.parse("2024-01-01T00:00:00Z");

    @Test
    void unionsTouchingIntervalsTransitivelyAcrossAssets() {
        List<LiquidationHourlyDiagnosticsV1.Event> events = List.of(
                event("btc-a", "BTC", BASE, BASE.plus(Duration.ofDays(1)), BASE.plus(Duration.ofDays(1)), LiquidationStructureRouterV1.Direction.LONG),
                event("eth-b", "ETH", BASE.plus(Duration.ofDays(4)), BASE.plus(Duration.ofDays(5)), BASE.plus(Duration.ofDays(5)), LiquidationStructureRouterV1.Direction.SHORT),
                event("sol-c", "SOL", BASE.plus(Duration.ofDays(8)), BASE.plus(Duration.ofDays(9)), BASE.plus(Duration.ofDays(9)), LiquidationStructureRouterV1.Direction.LONG),
                event("aave-d", "AAVE", BASE.plus(Duration.ofDays(20)), BASE.plus(Duration.ofDays(20)), BASE.plus(Duration.ofDays(20)), LiquidationStructureRouterV1.Direction.SHORT));

        ObjectNode result = build(policy(140), Map.of(), events, List.of(), List.of(), Map.of(), Map.of(), coverage());
        JsonNode clusters = result.path("market_wide_shock_clusters");

        assertEquals(2, result.path("market_wide_shock_cluster_count").asInt());
        assertEquals(3, clusters.path(0).path("event_count").asInt());
        assertEquals(3, clusters.path(0).path("asset_count").asInt());
        assertEquals(1, clusters.path(1).path("event_count").asInt());
        assertFalse(clusters.path(0).path("independence_claim").asBoolean());
        assertEquals("INSUFFICIENT_EVIDENCE_WARNING_COMPUTATION_ALLOWED", result.path("cluster_count_status").asText());
        assertTrue(result.path("cluster_count_warning_nonblocking").asBoolean());
    }

    @Test
    void joinsSameMarketClustersAndActualHoldingOverlapsIntoDependenceComponents() {
        List<LiquidationHourlyDiagnosticsV1.Event> events = List.of(
                event("e1", "BTC", BASE, BASE, BASE, LiquidationStructureRouterV1.Direction.LONG),
                event("e2", "ETH", BASE.plus(Duration.ofDays(20)), BASE.plus(Duration.ofDays(20)), BASE.plus(Duration.ofDays(20)), LiquidationStructureRouterV1.Direction.SHORT),
                event("e3", "SOL", BASE.plus(Duration.ofDays(60)), BASE.plus(Duration.ofDays(60)), BASE.plus(Duration.ofDays(60)), LiquidationStructureRouterV1.Direction.LONG));
        List<LiquidationHourlyDiagnosticsV1.Intent> intents = List.of(
                intent(CORE, "e1", "BTC", BASE.plus(Duration.ofDays(1)), LiquidationStructureRouterV1.Direction.LONG),
                intent(NO_MACRO, "e2", "ETH", BASE.plus(Duration.ofDays(20)), LiquidationStructureRouterV1.Direction.SHORT),
                intent(MACRO, "e1", "BTC", BASE.plus(Duration.ofDays(1)), LiquidationStructureRouterV1.Direction.LONG),
                intent(CORE, "e3", "SOL", BASE.plus(Duration.ofDays(60)), LiquidationStructureRouterV1.Direction.LONG));
        List<LiquidationHourlyDiagnosticsV1.Position> positions = List.of(
                position(CORE, "BTC", "e1", BASE.plus(Duration.ofDays(1)), BASE.plus(Duration.ofDays(10)), BASE.plus(Duration.ofDays(25))),
                position(NO_MACRO, "ETH", "e2", BASE.plus(Duration.ofDays(20)), BASE.plus(Duration.ofDays(22)), BASE.plus(Duration.ofDays(30))),
                position(MACRO, "BTC", "e1", BASE.plus(Duration.ofDays(1)), BASE.plus(Duration.ofDays(40)), BASE.plus(Duration.ofDays(41))),
                position(CORE, "SOL", "e3", BASE.plus(Duration.ofDays(60)), BASE.plus(Duration.ofDays(100)), BASE.plus(Duration.ofDays(101))));

        ObjectNode result = build(policy(140), Map.of(), events, intents, positions, Map.of(), Map.of(), coverage());
        JsonNode overlap = result.path("position_overlap_components");

        assertEquals(2, overlap.path("component_count").asInt());
        assertEquals(4, overlap.path("position_count").asInt());
        assertTrue(overlap.path("actual_holding_overlap_edge_count").asInt() > 0);
        assertTrue(overlap.path("same_market_cluster_edge_count").asInt() > 0);
        assertEquals(3, overlap.path("components").path(0).path("position_count").asInt());
        assertTrue(overlap.path("components").path(0).path("actual_holding_overlap_present").asBoolean());
        assertFalse(overlap.path("independence_claim").asBoolean());
    }

    @Test
    void usesExactHourlyAnchorsAndExcludesAnyResponseWithAMissingInterveningClose() {
        Instant second = BASE.plus(Duration.ofHours(2));
        List<LiquidationHourlyDiagnosticsV1.Event> events = List.of(
                event("btc-gap", "BTC", BASE, BASE, BASE, LiquidationStructureRouterV1.Direction.LONG),
                event("eth-exact", "ETH", BASE.plus(Duration.ofDays(20)), BASE.plus(Duration.ofDays(20)),
                        BASE.plus(Duration.ofDays(20)), LiquidationStructureRouterV1.Direction.SHORT));
        List<LiquidationHourlyDiagnosticsV1.Intent> intents = List.of(
                intent(CORE, "btc-gap", "BTC", second, LiquidationStructureRouterV1.Direction.SHORT),
                intent(CORE, "eth-exact", "ETH", BASE.plus(Duration.ofDays(20)).plus(Duration.ofHours(2)),
                        LiquidationStructureRouterV1.Direction.LONG));
        TreeMap<Instant, Double> btc = hourlyPrices(BASE, 168, 100.0, 110.0);
        btc.remove(BASE.plus(Duration.ofHours(10)));
        Instant ethBase = BASE.plus(Duration.ofDays(20));
        TreeMap<Instant, Double> eth = hourlyPrices(ethBase, 168, 100.0, 120.0);
        eth.put(ethBase.plus(Duration.ofHours(2)), 200.0);
        eth.put(ethBase.plus(Duration.ofHours(26)), 220.0);
        Map<String, NavigableMap<Instant, Double>> prices = Map.of("BTC", btc, "ETH", eth);

        ObjectNode result = build(policy(140), prices, events, intents, List.of(), Map.of(), Map.of(), coverage());
        JsonNode btcEvent = metric(result, "FIRST_OBSERVABLE_EVENT_AVAILABILITY", CORE, "SIGNED_SHOCK", 1);
        JsonNode btcBreakdown = assetBreakdown(btcEvent, "BTC");
        JsonNode ethEntry = metric(result, "FIRST_ROUTED_ENTRY_DECISION", CORE, "ROUTED_DIRECTION", 1);

        assertEquals(0, btcBreakdown.path("valid_response_count").asInt());
        assertTrue(btcBreakdown.path("missing_intervening_hour_count").asInt() > 0);
        assertEquals(0, btcBreakdown.path("missing_endpoint_count").asInt());
        assertEquals("UNKNOWN_AT_EVENT_AVAILABILITY", btcEvent.path("branch_breakdown").path(0).path("branch").asText());
        assertEquals(0.10, ethEntry.path("mean_directional_return_fraction").asDouble(), 1e-12);
        assertEquals("REVERSAL", ethEntry.path("branch_breakdown").path(0).path("branch").asText());
        assertEquals(0, metric(result, "FIRST_OBSERVABLE_EVENT_AVAILABILITY", CORE, "ROUTED_DIRECTION", 1).size(),
                "route direction is not conditionally projected back to the event-availability anchor");
    }

    @Test
    void equalWeightsEventObservationsWithinClusterThenClustersAndSharesDeterministicBootstrap() {
        List<LiquidationHourlyDiagnosticsV1.Event> events = List.of(
                event("btc-a", "BTC", BASE, BASE, BASE, LiquidationStructureRouterV1.Direction.LONG),
                event("eth-a", "ETH", BASE.minus(Duration.ofHours(1)), BASE, BASE, LiquidationStructureRouterV1.Direction.LONG),
                event("sol-b", "SOL", BASE.plus(Duration.ofDays(10)), BASE.plus(Duration.ofDays(10)),
                        BASE.plus(Duration.ofDays(10)), LiquidationStructureRouterV1.Direction.LONG),
                event("aave-missing", "AAVE", BASE.plus(Duration.ofDays(20)), BASE.plus(Duration.ofDays(20)),
                        BASE.plus(Duration.ofDays(20)), LiquidationStructureRouterV1.Direction.LONG));
        TreeMap<Instant, Double> btc = hourlyPrices(BASE, 168, 100.0, 110.0);
        TreeMap<Instant, Double> eth = hourlyPrices(BASE, 168, 100.0, 110.0);
        TreeMap<Instant, Double> sol = hourlyPrices(BASE.plus(Duration.ofDays(10)), 168, 100.0, 95.0);
        btc.put(BASE.plus(Duration.ofDays(1)), 110.0);
        eth.put(BASE.plus(Duration.ofDays(1)), 110.0);
        sol.put(BASE.plus(Duration.ofDays(11)), 95.0);
        Map<String, NavigableMap<Instant, Double>> prices = Map.of("BTC", btc, "ETH", eth, "SOL", sol);

        ObjectNode first = build(policy(140), prices, events, List.of(), List.of(), Map.of(), Map.of(), coverage());
        ObjectNode second = build(policy(140), prices, events, List.of(), List.of(), Map.of(), Map.of(), coverage());
        JsonNode metric = metric(first, "FIRST_OBSERVABLE_EVENT_AVAILABILITY", CORE, "SIGNED_SHOCK", 1);
        JsonNode repeatedMetric = metric(second, "FIRST_OBSERVABLE_EVENT_AVAILABILITY", CORE, "SIGNED_SHOCK", 1);
        JsonNode stagedMetric = metric(first, "FIRST_OBSERVABLE_EVENT_AVAILABILITY", NO_MACRO, "SIGNED_SHOCK", 1);

        assertEquals(0.025, metric.path("mean_directional_return_fraction").asDouble(), 1e-12);
        assertFalse(metric.path("intent_conditioned").asBoolean());
        assertTrue(metric.path("direction_was_known_at_anchor").asBoolean());
        assertEquals(metric.path("p20_directional_return_fraction"), repeatedMetric.path("p20_directional_return_fraction"));
        assertEquals(metric.path("ci95_low_directional_return_fraction"), repeatedMetric.path("ci95_low_directional_return_fraction"));
        assertEquals(metric.path("p20_directional_return_fraction"), stagedMetric.path("p20_directional_return_fraction"));
        assertEquals(10_000, metric.path("bootstrap_draws").asInt());
        assertEquals(20_260_921L, metric.path("bootstrap_seed").asLong());
        assertEquals("SHARED_MARKET_WIDE_SHOCK_CLUSTER", metric.path("bootstrap_resampling_unit").asText());
        assertTrue(metric.path("bootstrap_valid_draw_count").asInt() < 10_000,
                "the shared draw universe includes a cluster with a missing metric outcome");
        JsonNode noMarks = assetBreakdown(metric, "AAVE");
        assertEquals(1, noMarks.path("missing_anchor_count").asInt());
        assertEquals(0, noMarks.path("valid_response_count").asInt());
        JsonNode untradedVariant = metric(first, "FIRST_ROUTED_ENTRY_DECISION", MACRO, "ROUTED_DIRECTION", 1);
        assertEquals(0, untradedVariant.path("observation_count").asInt());
        assertTrue(untradedVariant.path("intent_conditioned").asBoolean());
        assertTrue(untradedVariant.path("direction_was_known_at_anchor").asBoolean());
        assertTrue(untradedVariant.path("mean_directional_return_fraction").isNull(),
                "a nontrading routed variant has an empty response sample, not a zero return");
    }

    @Test
    void keepsOffHourEventAnchorsOutOfCompletedHourlyCloseResponses() {
        Instant offHour = BASE.plus(Duration.ofMinutes(30));
        LiquidationHourlyDiagnosticsV1.Event event = event("off-hour", "BTC", BASE, BASE, offHour,
                LiquidationStructureRouterV1.Direction.LONG);
        ObjectNode result = build(policy(140), Map.of("BTC", hourlyPrices(BASE, 168, 100.0, 110.0)),
                List.of(event), List.of(), List.of(), Map.of(), Map.of(), coverage());
        JsonNode response = metric(result, "FIRST_OBSERVABLE_EVENT_AVAILABILITY", CORE, "SIGNED_SHOCK", 1);

        assertEquals(1, response.path("observation_count").asInt());
        assertEquals(0, response.path("valid_response_count").asInt());
        assertEquals(1, response.path("non_hourly_anchor_count").asInt());
        assertTrue(response.path("mean_directional_return_fraction").isNull());
    }

    @Test
    void stopsDailyReturnAndBlockSeriesAtNonpositiveEquity() {
        ObjectNode account = JsonHashes.mapper().createObjectNode();
        ArrayNode curve = account.putArray("marked_equity_curve");
        curve.addObject().put("time", BASE.plus(Duration.ofHours(23)).toEpochMilli()).put("equity_usdt", "20000");
        curve.addObject().put("time", BASE.plus(Duration.ofDays(1)).plus(Duration.ofHours(23)).toEpochMilli())
                .put("equity_usdt", "0");

        ObjectNode result = build(policy(140), Map.of(), List.of(), List.of(), List.of(), Map.of(CORE, account),
                Map.of(), coverage());
        JsonNode daily = result.path("lagged_daily_portfolio_return_correlations").path("by_variant").path(CORE);
        JsonNode blocks = result.path("calendar_block_sensitivity").path("by_variant").path(CORE).path("blocks");

        assertEquals(2, daily.path("daily_return_observation_count").asInt());
        assertEquals(139, daily.path("nonpositive_equity_day_count").asInt());
        assertEquals("EXCLUDED_NONPOSITIVE_EQUITY_AFTER_RUIN", blocks.path(0).path("state").asText());
        assertEquals(0, result.path("calendar_block_sensitivity").path("common_complete_block_count").asInt());
    }

    @Test
    void heldMarkGapExcludesItsCalendarBlockButLeavesLaterCompleteBlocksAvailable() {
        ObjectNode account = JsonHashes.mapper().createObjectNode();
        ArrayNode curve = account.putArray("marked_equity_curve");
        for (int day = 0; day < 140; day++) {
            if (day == 10) continue;
            curve.addObject().put("time", BASE.plus(Duration.ofDays(day)).plus(Duration.ofHours(23)).toEpochMilli())
                    .put("equity_usdt", "20000");
        }
        LiquidationHourlyDiagnosticsV1.Position held = position(CORE, "BTC", "held", BASE, BASE,
                BASE.plus(Duration.ofDays(100)));

        ObjectNode result = build(policy(140), Map.of(), List.of(), List.of(), List.of(held), Map.of(CORE, account),
                Map.of(), coverage());
        JsonNode daily = result.path("lagged_daily_portfolio_return_correlations").path("by_variant").path(CORE);
        JsonNode blocks = result.path("calendar_block_sensitivity").path("by_variant").path(CORE).path("blocks");

        assertEquals(1, daily.path("held_mark_gap_day_count").asInt());
        assertEquals("EXCLUDED_OPEN_EXPOSURE_MARK_MISSING", blocks.path(0).path("state").asText());
        assertEquals("COMPLETE", blocks.path(1).path("state").asText());
        assertEquals(1, result.path("calendar_block_sensitivity").path("common_complete_block_count").asInt());
    }

    @Test
    void openHoldingOverlapEndsAtExplicitCommonCoverageBoundary() {
        Instant coverageEnd = BASE.plus(Duration.ofDays(20));
        LiquidationHourlyDiagnosticsV1.Position open = new LiquidationHourlyDiagnosticsV1.Position(CORE, "BTC", "open",
                BASE.plus(Duration.ofDays(1)), BASE.plus(Duration.ofDays(2)), null, null, false);

        ObjectNode result = build(policy(140), Map.of(), List.of(), List.of(), List.of(open), Map.of(), Map.of(), coverageAt(coverageEnd));
        JsonNode overlap = result.path("position_overlap_components");
        JsonNode member = overlap.path("components").path(0).path("positions").path(0);

        assertEquals(coverageEnd.toString(), overlap.path("analysis_end_exclusive_used_for_open_positions").asText());
        assertTrue(member.path("holding_end_is_coverage_bound").asBoolean());
        assertEquals(coverageEnd.toString(), member.path("overlap_end").asText());
    }

    @Test
    void derivesOpenPositionCoverageFromLatestPriceCloseOrPolicyFallback() {
        TreeMap<Instant, Double> btc = new TreeMap<>();
        btc.put(BASE.plus(Duration.ofDays(10)), 100.0);
        TreeMap<Instant, Double> eth = new TreeMap<>();
        Instant latestClose = BASE.plus(Duration.ofDays(11));
        eth.put(latestClose, 2_000.0);
        TreeMap<Instant, Double> emptySol = new TreeMap<>();
        ObjectNode noExplicitCoverage = JsonHashes.mapper().createObjectNode();
        LiquidationHourlyDiagnosticsV1.Position open = new LiquidationHourlyDiagnosticsV1.Position(CORE, "BTC", "open",
                BASE.plus(Duration.ofDays(1)), BASE.plus(Duration.ofDays(2)), null, null, false);

        ObjectNode priceBoundaryResult = build(policy(140), Map.of("BTC", btc, "ETH", eth, "SOL", emptySol),
                List.of(), List.of(), List.of(open), Map.of(), Map.of(), noExplicitCoverage);
        ObjectNode fallbackResult = build(policy(140), Map.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), noExplicitCoverage);

        assertEquals(latestClose.toString(), priceBoundaryResult.path("position_overlap_components")
                .path("analysis_end_exclusive_used_for_open_positions").asText());
        assertEquals(BASE.plus(Duration.ofDays(140)).minus(Duration.ofHours(1)).toString(),
                fallbackResult.path("position_overlap_components")
                        .path("analysis_end_exclusive_used_for_open_positions").asText());
    }

    @Test
    void reportsNullResponseIntervalsForNoClusteredObservationsAndExcludesPartialBlockTail() {
        LiquidationHourlyDiagnosticsV1.Event unclustered = new LiquidationHourlyDiagnosticsV1.Event("no-geometry", "BTC",
                null, null, BASE, LiquidationStructureRouterV1.Direction.LONG);
        ObjectNode result = build(policy(140), Map.of("BTC", hourlyPrices(BASE, 168, 100.0, 110.0)),
                List.of(unclustered), List.of(), List.of(), Map.of(), Map.of(), coverage());
        JsonNode response = metric(result, "FIRST_OBSERVABLE_EVENT_AVAILABILITY", CORE, "SIGNED_SHOCK", 1);
        JsonNode blocks = result.path("calendar_block_sensitivity");

        assertTrue(response.path("p20_directional_return_fraction").isNull());
        assertTrue(response.path("mean_directional_return_fraction").isNull());
        assertEquals(1, response.path("unclustered_geometry_count").asInt());
        assertEquals(2, blocks.path("block_count").asInt());
        assertTrue(blocks.path("partial_tail_excluded_from_67_day_bootstrap").path("present").asBoolean());
        assertEquals(6, blocks.path("partial_tail_excluded_from_67_day_bootstrap").path("calendar_days").asInt());
        assertEquals(2, blocks.path("common_complete_block_count").asInt());
    }

    private static ObjectNode build(ObjectNode policy, Map<String, NavigableMap<Instant, Double>> prices,
            List<LiquidationHourlyDiagnosticsV1.Event> events, List<LiquidationHourlyDiagnosticsV1.Intent> intents,
            List<LiquidationHourlyDiagnosticsV1.Position> positions, Map<String, ObjectNode> accounts,
            Map<String, ObjectNode> funnel, ObjectNode coverage) {
        return LiquidationHourlyDiagnosticsV1.build(policy, prices, events, intents, positions, accounts, funnel, coverage);
    }

    private static LiquidationHourlyDiagnosticsV1.Event event(String id, String asset, Instant start, Instant end,
            Instant availableAt, LiquidationStructureRouterV1.Direction direction) {
        return new LiquidationHourlyDiagnosticsV1.Event(id, asset, start, end, availableAt, direction);
    }

    private static LiquidationHourlyDiagnosticsV1.Intent intent(String variant, String eventId, String asset,
            Instant decision, LiquidationStructureRouterV1.Direction direction) {
        return new LiquidationHourlyDiagnosticsV1.Intent(variant, eventId, asset, eventId, decision, direction,
                LiquidationStructureRouterV1.Branch.REVERSAL);
    }

    private static LiquidationHourlyDiagnosticsV1.Position position(String variant, String asset, String setupId,
            Instant decision, Instant fill, Instant exit) {
        return new LiquidationHourlyDiagnosticsV1.Position(variant, asset, setupId, decision, fill, exit,
                new BigDecimal("10.00"), true);
    }

    private static TreeMap<Instant, Double> hourlyPrices(Instant start, int hours, double startPrice, double endPrice) {
        TreeMap<Instant, Double> out = new TreeMap<>();
        for (int hour = 0; hour <= hours; hour++) {
            double fraction = hour / (double) hours;
            out.put(start.plus(Duration.ofHours(hour)), startPrice + fraction * (endPrice - startPrice));
        }
        return out;
    }

    private static JsonNode metric(ObjectNode result, String anchor, String variant, String direction, int horizon) {
        for (JsonNode row : result.path("response_diagnostics")) {
            if (anchor.equals(row.path("anchor").asText()) && variant.equals(row.path("variant").asText())
                    && direction.equals(row.path("direction").asText()) && horizon == row.path("horizon_days").asInt()) return row;
        }
        return JsonHashes.mapper().createObjectNode();
    }

    private static JsonNode assetBreakdown(JsonNode metric, String asset) {
        for (JsonNode row : metric.path("asset_breakdown")) if (asset.equals(row.path("asset").asText())) return row;
        return JsonHashes.mapper().createObjectNode();
    }

    private static ObjectNode policy(int days) {
        ObjectNode policy = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-exploratory-policy/1")
                .put("id", "liquidation-exploratory-v003").put("evidence_phase", "DEVELOPMENT")
                .put("promotion_allowed", false)
                .put("source_start", BASE.toString())
                .put("source_end_exclusive", BASE.plus(Duration.ofDays(days)).toString());
        policy.putArray("assets").add("BTC").add("ETH").add("SOL").add("AAVE");
        policy.putArray("variants").add(CORE).add(NO_MACRO).add(MACRO);
        policy.putObject("statistics").put("bootstrap_draws", 10_000).put("seed", 20_260_921L)
                .put("purge_days", 67).put("minimum_groups_to_run", 0).put("reference_minimum_groups", 30);
        policy.putObject("response_diagnostics").putArray("horizons_days").add(1).add(3).add(7);
        policy.putObject("account").put("initial_equity", 20_000);
        return policy;
    }

    private static ObjectNode coverage() {
        return coverageAt(BASE.plus(Duration.ofDays(140)));
    }

    private static ObjectNode coverageAt(Instant endExclusive) {
        return JsonHashes.mapper().createObjectNode().put("analysis_coverage_end_exclusive", endExclusive.toString());
    }
}
