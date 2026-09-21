package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import org.junit.jupiter.api.Test;

/** Accepted account paths and conservative boundary behavior for the shared ledger. */
class LiquidationPortfolioAccountingAcceptancePathMatrixV1Test {
    private static final long T = 1_700_000_000_000L;

    @Test
    void threeSequentialStagesRespectTheirRiskBudgetsAndLeverageSchedule() {
        ObjectNode request = account(20_000, 0);
        add(request, 1, T, 100, 90, 100_000);
        add(request, 2, T + 3_600_000, 105, 90, 100_000);
        add(request, 3, T + 7_200_000, 105, 90, 100_000);

        ObjectNode ledger = LiquidationPortfolioAccountingV1.replayFixture(request);
        JsonNode position = ledger.path("positions").get(0);
        JsonNode fills = position.path("fills");

        assertEquals(3, fills.size());
        assertEquals(1, fills.get(0).path("stage").asInt());
        assertEquals(2, fills.get(1).path("stage").asInt());
        assertEquals(3, fills.get(2).path("stage").asInt());
        assertEquals(200, fills.get(0).path("planned_tranche_risk_usdt").asDouble(), 1e-10);
        assertEquals(300, fills.get(1).path("planned_tranche_risk_usdt").asDouble(), 1e-10);
        assertEquals(500, fills.get(2).path("planned_tranche_risk_usdt").asDouble(), 1e-10);
        assertEquals(2, fills.get(0).path("stage_leverage").asInt());
        assertEquals(2, fills.get(1).path("stage_leverage").asInt());
        assertEquals(3, fills.get(2).path("stage_leverage").asInt());
        assertEquals(4, position.path("next_stage").asInt());
        assertEquals(3, position.path("current_position_leverage").asInt());
        assertEquals(0, ledger.path("reconciliation_delta_usdt").asDouble(), 1e-10);
    }

    @Test
    void acceptedEntryReportsWhichIndependentCapacityBoundActuallyLimitsIt() {
        ObjectNode riskLimited = account(20_000, 0);
        add(riskLimited, 1, T, 100, 90, 100_000);
        ObjectNode riskLedger = LiquidationPortfolioAccountingV1.replayFixture(riskLimited);
        assertEquals("RISK_BUDGET", riskLedger.path("events").get(0).path("risk_limited_by").asText());
        assertEquals(20, riskLedger.path("positions").get(0).path("quantity").asDouble(), 1e-10);

        ObjectNode volumeLimited = account(20_000, 0);
        add(volumeLimited, 1, T, 100, 90, 1_000);
        ObjectNode volumeLedger = LiquidationPortfolioAccountingV1.replayFixture(volumeLimited);
        assertEquals("PRIOR_MINUTE_VOLUME_1_PERCENT", volumeLedger.path("events").get(0).path("risk_limited_by").asText());
        assertEquals(10, volumeLedger.path("positions").get(0).path("quantity").asDouble(), 1e-10);

        ObjectNode collateralLimited = account(20_000, 19_500);
        add(collateralLimited, 1, T, 100, 90, 100_000);
        ObjectNode collateralLedger = LiquidationPortfolioAccountingV1.replayFixture(collateralLimited);
        assertEquals("FREE_COLLATERAL", collateralLedger.path("events").get(0).path("risk_limited_by").asText());
        assertEquals(10, collateralLedger.path("positions").get(0).path("quantity").asDouble(), 1e-10);
        for (ObjectNode result : new ObjectNode[] {riskLedger, volumeLedger, collateralLedger}) {
            assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-10);
        }
    }

    @Test
    void historicalSinglePositionAdapterPreservesEventOrderAndRejectsInjectedPortfolioSnapshots() {
        ObjectNode request = lifecycleFixture();
        request.withArray("add_requests").addObject().put("stage", 1).put("time", T).put("price", 100)
                .put("previous_completed_minute_base_volume", 100_000).put("mode", "CONTINUATION").put("decision_mark", 100);
        request.withArray("funding_events").addObject().put("event_id", "fixture-funding").put("settlement_time", T + 60_000)
                .put("funding_rate", 0.001).put("mark_price", 100);
        request.withArray("exit_events").addObject().put("time", T + 120_000).put("price", 105).put("reason", "MATRIX_CLOSE");

        ObjectNode result = LiquidationPortfolioAccountingV1.singlePositionFixtureResult(request);
        assertEquals("CLOSED", result.path("status").asText());
        assertEquals(3, result.path("events").size());
        assertEquals("ADD_FILLED", result.path("events").get(0).path("type").asText());
        assertEquals("FUNDING_SETTLED", result.path("events").get(1).path("type").asText());
        assertEquals("EXIT_FILLED", result.path("events").get(2).path("type").asText());
        assertEquals(-2, result.path("funding_pnl_usdt").asDouble(), 1e-10);
        assertEquals(100, result.path("realized_gross_pnl_usdt").asDouble(), 1e-10);
        assertEquals(0, result.path("quantity").asDouble(), 1e-10);
        assertEquals(20_098, result.path("mark_to_market_equity_usdt").asDouble(), 1e-10);
        assertEquals(20_098, result.path("free_collateral_usdt").asDouble(), 1e-10);
        assertEquals(JsonHashes.ownHash(result), result.path("content_sha256").asText());

        ObjectNode staleRisk = lifecycleFixture().put("portfolio_existing_stop_risk_usdt", 1);
        assertThrowsWith("single-position fixture cannot inject stale external portfolio snapshots", staleRisk);
        ObjectNode staleMargin = lifecycleFixture().put("portfolio_existing_locked_margin_usdt", 1);
        assertThrowsWith("single-position fixture cannot inject stale external portfolio snapshots", staleMargin);
    }

    @Test
    void untriggeredVenueBlackoutKeepsOpenPositionThroughBothBarPhases() {
        ObjectNode request = account(20_000, 0);
        add(request, 1, T, 100, 90, 100_000);
        bar(request, "BAR_PRE", T + 60_000, T + 60_000, 100, 101, 99, 100, true);
        bar(request, "BAR_POST", T + 120_000, T + 60_000, 100, 105, 95, 103, true);
        bar(request, "BAR_PRE", T + 180_000, T + 180_000, 100, 102, 98, 101, false);

        ObjectNode ledger = LiquidationPortfolioAccountingV1.replayFixture(request);
        JsonNode events = ledger.path("events");
        assertEquals("VENUE_BLACKOUT_OPEN_POSITION_RETAINED", events.get(1).path("type").asText());
        assertEquals("VENUE_BLACKOUT_OPEN_POSITION_RETAINED", events.get(2).path("type").asText());
        assertEquals("BAR_OPEN_WITH_UNKNOWN_MAINTENANCE_TIER", events.get(3).path("type").asText());
        assertTrue(events.get(1).path("venue_execution_blocked").asBoolean());
        assertTrue(events.get(2).path("venue_execution_blocked").asBoolean());
        assertEquals(20, ledger.path("positions").get(0).path("quantity").asDouble(), 1e-10);
        assertTrue(ledger.path("positions").get(0).path("exits").isEmpty());
        assertFalse(events.get(1).path("liquidated").asBoolean());
        assertEquals(0, ledger.path("reconciliation_delta_usdt").asDouble(), 1e-10);
    }

    private static ObjectNode account(double equity, double reserve) {
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-shared-account-fixture/1")
                .put("initial_equity_usdt", equity).put("reserved_costs_usdt", reserve);
        request.putArray("positions").add(position());
        request.putArray("events");
        return request;
    }

    private static ObjectNode position() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("direction", "LONG")
                .put("common_stop", 90).putNull("recovery_target").put("lot_size", 0.01)
                .put("minimum_notional", 5).put("taker_fee_rate", 0).put("slippage_rate", 0)
                .put("liquidation_fee_rate", 0).put("initial_mark_price", 100);
    }

    private static ObjectNode lifecycleFixture() {
        ObjectNode value = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-lifecycle-fixture/1")
                .put("asset", "BTC").put("direction", "LONG").put("initial_equity_usdt", 20_000)
                .put("first_fill_reference_equity_usdt", 20_000).put("portfolio_existing_stop_risk_usdt", 0)
                .put("portfolio_existing_locked_margin_usdt", 0).put("reserved_costs_usdt", 0)
                .put("common_stop", 90).putNull("recovery_target").put("lot_size", 0.01)
                .put("minimum_notional", 5).put("taker_fee_rate", 0).put("slippage_rate", 0)
                .put("initial_mark_price", 100);
        value.putArray("add_requests"); value.putArray("funding_events"); value.putArray("exit_events");
        return value;
    }

    private static void add(ObjectNode request, int stage, long time, double price, double stop, double capacity) {
        ObjectNode row = request.withArray("events").addObject().put("type", "ADD").put("asset", "BTC")
                .put("time", time).put("stage", stage).put("setup_id", "setup-" + stage)
                .put("intent_id", "intent-" + stage).put("decision_time", time).put("direction", "LONG")
                .put("mode", "CONTINUATION").put("common_stop", stop).putNull("recovery_target")
                .put("price", price).put("decision_mark", price).put("previous_completed_minute_base_volume", capacity);
    }

    private static void bar(ObjectNode request, String type, long time, long start, double open, double high,
            double low, double close, boolean blocked) {
        request.withArray("events").addObject().put("type", type).put("asset", "BTC").put("time", time)
                .put("bar_start_time", start).put("trade_open", open).put("trade_high", high).put("trade_low", low)
                .put("trade_close", close).put("mark_open", open).put("mark_high", high).put("mark_low", low)
                .put("mark_close", close).put("venue_execution_blocked", blocked);
    }

    private static void assertThrowsWith(String text, ObjectNode request) {
        var failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioAccountingV1.singlePositionFixtureResult(request));
        assertTrue(failure.getMessage().contains(text), failure.getMessage());
    }
}
