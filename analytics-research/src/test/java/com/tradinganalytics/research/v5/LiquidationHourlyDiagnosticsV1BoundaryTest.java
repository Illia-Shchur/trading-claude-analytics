package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Additional branch-focused diagnostics tests using only constructed synthetic rows. */
class LiquidationHourlyDiagnosticsV1BoundaryTest {
    private static final String CORE = "CORE_ONE_ENTRY";
    private static final Instant BASE = Instant.parse("2024-01-01T00:00:00Z");

    @Test
    void frozenPolicyRejectsEachIndependentStatisticAndInventoryDrift() {
        List<Consumer<ObjectNode>> mutations = List.of(
                policy -> policy.put("schema", "wrong"),
                policy -> policy.put("id", "other"),
                policy -> policy.put("evidence_phase", "CANDIDATE"),
                policy -> policy.put("promotion_allowed", true),
                policy -> policy.withArray("variants").remove(2),
                policy -> policy.withArray("variants").set(0, JsonHashes.mapper().getNodeFactory().textNode("STAGED_NO_MACRO")),
                policy -> policy.with("statistics").put("bootstrap_draws", 999),
                policy -> policy.with("statistics").put("seed", 7),
                policy -> policy.with("statistics").put("purge_days", 7),
                policy -> policy.with("statistics").put("minimum_groups_to_run", 1),
                policy -> policy.with("statistics").put("reference_minimum_groups", 29),
                policy -> policy.with("response_diagnostics").withArray("horizons_days").remove(2),
                policy -> policy.with("response_diagnostics").withArray("horizons_days").set(0,
                        JsonHashes.mapper().getNodeFactory().numberNode(2)));

        for (Consumer<ObjectNode> mutation : mutations) {
            ObjectNode changed = policy(5);
            mutation.accept(changed);
            assertThrows(IllegalArgumentException.class, () -> build(changed, Map.of(), List.of(), List.of(),
                    List.of(), Map.of(), Map.of(), coverage()), changed.toString());
        }
    }

    @Test
    void v004AcceptsOnlyFrozenNineAssetOrderAndDiagnosticOnlyEntryAudit() {
        List<Consumer<ObjectNode>> mutations = List.of(
                policy -> policy.withArray("assets").remove(8),
                policy -> policy.withArray("assets").set(0, JsonHashes.mapper().getNodeFactory().textNode("ETH")),
                policy -> policy.withArray("assets").set(8, JsonHashes.mapper().getNodeFactory().textNode("DOGE")),
                policy -> policy.with("entry_rule_audit").put("diagnostic_only", false),
                policy -> policy.with("entry_rule_audit").put("rule_changes", true),
                policy -> policy.with("entry_rule_audit").put("outcome_optimization", true),
                policy -> policy.with("entry_rule_audit").remove("diagnostic_only"),
                policy -> policy.with("entry_rule_audit").put("rule_changes", "false"),
                policy -> policy.with("entry_rule_audit").put("outcome_optimization", 0),
                policy -> policy.remove("entry_rule_audit"));

        for (Consumer<ObjectNode> mutation : mutations) {
            ObjectNode changed = v004Policy(5);
            mutation.accept(changed);
            assertThrows(IllegalArgumentException.class, () -> build(changed, Map.of(), List.of(), List.of(),
                    List.of(), Map.of(), Map.of(), coverageAt(BASE.plus(Duration.ofDays(5)))), changed.toString());
        }

        ObjectNode valid = v004Policy(5);
        ObjectNode result = build(valid, Map.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(),
                coverageAt(BASE.plus(Duration.ofDays(5))));
        assertEquals(LiquidationHourlyDiagnosticsV1.SCHEMA, result.path("schema").asText());
    }

    @Test
    void diagnosticsCalculationsMatchV003ForSharedSyntheticEvidenceAndKeepSmallLegacyScopes() {
        LiquidationHourlyDiagnosticsV1.Event qualified = event("same-event", "BTC", BASE, BASE, BASE,
                LiquidationStructureRouterV1.Direction.LONG);
        List<LiquidationHourlyDiagnosticsV1.Intent> intents = List.of(intent(CORE, "same-event", "BTC", BASE,
                LiquidationStructureRouterV1.Direction.LONG));
        List<LiquidationHourlyDiagnosticsV1.Position> positions = List.of(new LiquidationHourlyDiagnosticsV1.Position(
                CORE, "BTC", "same-event", BASE, BASE.plusSeconds(3_600), BASE.plus(Duration.ofDays(2)),
                java.math.BigDecimal.valueOf(125), true));
        Map<String, NavigableMap<Instant, Double>> prices = Map.of("BTC", hourly(BASE, 168));
        ObjectNode coverage = coverageAt(BASE.plus(Duration.ofDays(5)));

        ObjectNode legacyResult = build(policy(5), prices, List.of(qualified), intents, positions, Map.of(), Map.of(), coverage);
        ObjectNode expandedResult = build(v004Policy(5), prices, List.of(qualified), intents, positions, Map.of(), Map.of(), coverage);
        assertEquals(legacyResult.path("response_diagnostics"), expandedResult.path("response_diagnostics"));
        assertEquals(legacyResult.path("position_overlap_components"), expandedResult.path("position_overlap_components"));
        assertEquals(legacyResult.path("per_completed_position_metrics"), expandedResult.path("per_completed_position_metrics"));

        ObjectNode smallLegacy = policy(5);
        smallLegacy.withArray("assets").removeAll().add("BTC").add("ETH");
        assertEquals(LiquidationHourlyDiagnosticsV1.SCHEMA,
                build(smallLegacy, Map.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), coverage)
                        .path("schema").asText());
    }

    @Test
    void canonicalPricesDropInvalidOrOffHourRowsAndMissingPathEndpointsStayExplicit() {
        TreeMap<Instant, Double> btc = hourly(BASE, 168);
        btc.remove(BASE.plus(Duration.ofDays(1)));
        btc.put(BASE.plus(Duration.ofHours(10)), Double.NaN);
        btc.put(BASE.plus(Duration.ofHours(11)), 0.0);
        btc.put(BASE.plus(Duration.ofMinutes(30)), 200.0);
        Map<String, NavigableMap<Instant, Double>> prices = new HashMap<>();
        prices.put("btc", btc);
        prices.put("eth", null);
        List<LiquidationHourlyDiagnosticsV1.Event> events = List.of(
                event("missing-end", "BTC", BASE, BASE, BASE, LiquidationStructureRouterV1.Direction.LONG),
                event("missing-anchor", "ETH", BASE.plus(Duration.ofDays(20)), BASE.plus(Duration.ofDays(20)),
                        BASE.plus(Duration.ofDays(20)), LiquidationStructureRouterV1.Direction.SHORT));

        ObjectNode result = build(policy(140), prices, events, List.of(), List.of(), Map.of(), Map.of(), coverage());
        JsonNode oneDay = metric(result, "FIRST_OBSERVABLE_EVENT_AVAILABILITY", CORE, "SIGNED_SHOCK", 1);
        JsonNode btcBreakdown = assetBreakdown(oneDay, "BTC");
        JsonNode ethBreakdown = assetBreakdown(oneDay, "ETH");

        assertEquals(1, btcBreakdown.path("missing_endpoint_count").asInt());
        assertEquals(0, btcBreakdown.path("valid_response_count").asInt());
        assertEquals(1, ethBreakdown.path("missing_anchor_count").asInt());
        assertEquals(0, ethBreakdown.path("valid_response_count").asInt());
        assertThrows(IllegalArgumentException.class, () -> build(policy(140), Map.of(), List.of(
                event("duplicate", "BTC", BASE, BASE, BASE, LiquidationStructureRouterV1.Direction.LONG),
                event("duplicate", "ETH", BASE, BASE, BASE, LiquidationStructureRouterV1.Direction.LONG)), List.of(),
                List.of(), Map.of(), Map.of(), coverage()));
        assertThrows(IllegalArgumentException.class, () -> build(policy(140), Map.of("B-T-C", hourly(BASE, 1)),
                List.of(), List.of(), List.of(), Map.of(), Map.of(), coverage()));
    }

    @Test
    void intentAssociationIgnoresOrphansAndAssetMismatchesButRejectsFutureOrMalformedRoutes() {
        LiquidationHourlyDiagnosticsV1.Event event = event("qualified", "BTC", BASE, BASE, BASE,
                LiquidationStructureRouterV1.Direction.LONG);
        List<LiquidationHourlyDiagnosticsV1.Intent> ignored = List.of(
                intent(CORE, "orphan", "BTC", BASE, LiquidationStructureRouterV1.Direction.LONG),
                intent(CORE, "qualified", "ETH", BASE, LiquidationStructureRouterV1.Direction.LONG));
        ObjectNode withoutAssociation = build(policy(140), Map.of(), List.of(event), ignored, List.of(), Map.of(), Map.of(), coverage());
        assertEquals(0, metric(withoutAssociation, "FIRST_ROUTED_ENTRY_DECISION", CORE, "ROUTED_DIRECTION", 1)
                .path("observation_count").asInt());

        List<LiquidationHourlyDiagnosticsV1.Intent> wrongSetup = List.of(new LiquidationHourlyDiagnosticsV1.Intent(
                CORE, "qualified", "BTC", "different-setup", BASE, LiquidationStructureRouterV1.Direction.LONG,
                LiquidationStructureRouterV1.Branch.REVERSAL));
        assertThrows(IllegalArgumentException.class, () -> build(policy(140), Map.of(), List.of(event), wrongSetup,
                List.of(), Map.of(), Map.of(), coverage()));

        List<LiquidationHourlyDiagnosticsV1.Intent> beforeAvailability = List.of(intent(CORE, "qualified", "BTC",
                BASE.minusSeconds(1), LiquidationStructureRouterV1.Direction.LONG));
        assertThrows(IllegalArgumentException.class, () -> build(policy(140), Map.of(), List.of(event), beforeAvailability,
                List.of(), Map.of(), Map.of(), coverage()));

        List<LiquidationHourlyDiagnosticsV1.Intent> unknownVariant = List.of(intent("UNFROZEN", "qualified", "BTC",
                BASE, LiquidationStructureRouterV1.Direction.LONG));
        assertThrows(IllegalArgumentException.class, () -> build(policy(140), Map.of(), List.of(event), unknownVariant,
                List.of(), Map.of(), Map.of(), coverage()));
    }

    @Test
    void positionsWithoutFillsAreExcludedAndMissingClosedPnlIsReportedAsMissing() {
        LiquidationHourlyDiagnosticsV1.Event event = event("qualified", "BTC", BASE, BASE, BASE,
                LiquidationStructureRouterV1.Direction.LONG);
        LiquidationHourlyDiagnosticsV1.Position noFill = new LiquidationHourlyDiagnosticsV1.Position(CORE, "BTC", "never-filled",
                BASE, null, null, null, false);
        LiquidationHourlyDiagnosticsV1.Position noPnl = new LiquidationHourlyDiagnosticsV1.Position(CORE, "BTC", "qualified",
                BASE, BASE.plusSeconds(1), BASE.plusSeconds(2), null, true);
        ObjectNode result = build(policy(140), Map.of(), List.of(event), List.of(), List.of(noFill, noPnl), Map.of(), Map.of(), coverage());
        JsonNode overlap = result.path("position_overlap_components");
        JsonNode completed = result.path("per_completed_position_metrics").path("by_variant").path(CORE);

        assertEquals(1, overlap.path("positions_without_fill_excluded_count").asInt());
        assertEquals(1, overlap.path("position_count").asInt());
        assertEquals(1, completed.path("completed_position_count").asInt());
        assertEquals(1, completed.path("missing_net_pnl_count").asInt());
        assertTrue(completed.path("mean_net_pnl_usdt").isNull());

        assertThrows(IllegalArgumentException.class, () -> build(policy(140), Map.of(), List.of(event), List.of(),
                List.of(noPnl, noPnl), Map.of(), Map.of(), coverage()));
    }

    @Test
    void lastObservedDailyCurveCanProduceDefinedLagCorrelation() {
        ObjectNode account = JsonHashes.mapper().createObjectNode();
        ArrayNode curve = account.putArray("marked_equity_curve");
        String[] equity = {"20000", "21000", "23100", "24255", "29106"};
        for (int day = 0; day < equity.length; day++) {
            curve.addObject().put("time", BASE.plus(Duration.ofDays(day)).plus(Duration.ofHours(23)).toEpochMilli())
                    .put("equity_usdt", equity[day]);
        }
        ObjectNode result = build(policy(5), Map.of(), List.of(), List.of(), List.of(), Map.of(CORE, account), Map.of(), coverage());
        JsonNode lags = result.path("lagged_daily_portfolio_return_correlations").path("by_variant").path(CORE).path("lags");

        assertEquals(1, lags.path(0).path("lag_days").asInt());
        assertTrue(lags.path(0).path("defined").asBoolean());
        assertTrue(lags.path(0).path("correlation").isNumber());
        assertFalse(lags.path(1).path("defined").asBoolean());
        assertEquals("LAST_OBSERVED_ACCOUNT_CURVE_POINT_PER_UTC_DATE;NOT_NECESSARILY_00:00_CLOSE;NO_INTERPOLATION_ACROSS_HELD_MARK_GAPS",
                result.path("lagged_daily_portfolio_return_correlations").path("return_sampling").asText());
    }

    private static ObjectNode policy(int days) {
        ObjectNode policy = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-exploratory-policy/1")
                .put("id", "liquidation-exploratory-v003").put("evidence_phase", "DEVELOPMENT")
                .put("promotion_allowed", false).put("source_start", BASE.toString())
                .put("source_end_exclusive", BASE.plus(Duration.ofDays(days)).toString());
        policy.putArray("assets").add("BTC").add("ETH").add("SOL").add("AAVE");
        policy.putArray("variants").add("CORE_ONE_ENTRY").add("STAGED_NO_MACRO").add("STAGED_MACRO");
        policy.putObject("statistics").put("bootstrap_draws", 10_000).put("seed", 20_260_921L)
                .put("purge_days", 67).put("minimum_groups_to_run", 0).put("reference_minimum_groups", 30);
        policy.putObject("response_diagnostics").putArray("horizons_days").add(1).add(3).add(7);
        policy.putObject("account").put("initial_equity", 20_000);
        return policy;
    }

    private static ObjectNode v004Policy(int days) {
        ObjectNode policy = policy(days);
        policy.put("id", "liquidation-exploratory-v004");
        policy.withArray("assets").removeAll().add("BTC").add("ETH").add("SOL").add("AAVE")
                .add("UNI").add("BNB").add("LINK").add("ZEC").add("TRX");
        policy.putObject("entry_rule_audit").put("diagnostic_only", true).put("rule_changes", false)
                .put("outcome_optimization", false);
        return policy;
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

    private static TreeMap<Instant, Double> hourly(Instant start, int hours) {
        TreeMap<Instant, Double> out = new TreeMap<>();
        for (int hour = 0; hour <= hours; hour++) out.put(start.plus(Duration.ofHours(hour)), 100.0 + hour / 100.0);
        return out;
    }

    private static ObjectNode build(ObjectNode policy, Map<String, NavigableMap<Instant, Double>> prices,
            List<LiquidationHourlyDiagnosticsV1.Event> events, List<LiquidationHourlyDiagnosticsV1.Intent> intents,
            List<LiquidationHourlyDiagnosticsV1.Position> positions, Map<String, ObjectNode> accounts,
            Map<String, ObjectNode> funnel, ObjectNode coverage) {
        return LiquidationHourlyDiagnosticsV1.build(policy, prices, events, intents, positions, accounts, funnel, coverage);
    }

    private static ObjectNode coverage() {
        return coverageAt(BASE.plus(Duration.ofDays(140)));
    }

    private static ObjectNode coverageAt(Instant endExclusive) {
        return JsonHashes.mapper().createObjectNode().put("analysis_coverage_end_exclusive", endExclusive.toString());
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
}
