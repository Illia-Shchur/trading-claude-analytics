package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import org.junit.jupiter.api.Test;

/** Small accepted financial paths for carry liability, gap fallback, and target/no-position phases. */
class LiquidationPortfolioAccountingCarryAndPathMatrixV1Test {
    private static final long T = 1_700_000_000_000L;

    @Test
    void shortFundingDebitConsumesMarginThenLaterCreditRepaysLiabilityBeforeCash() {
        ObjectNode request = base("SHORT", 110, null, 19_000);
        add(request, "funding-short-setup", 1, T, "SHORT", 100, 110, null, "CONTINUATION", 100_000);
        funding(request, "large-debit", T + 60_000, -2.0);
        funding(request, "liability-credit", T + 120_000, 2.0);
        funding(request, "zero-rate-open", T + 180_000, 0.0);
        request.withArray("events").addObject().put("type", "EXIT").put("asset", "BTC")
                .put("time", T + 240_000).put("price", 100).put("reason", "MATRIX_CLOSE");

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);
        JsonNode fundingEvents = result.path("positions").get(0).path("funding_events");

        assertEquals(5, result.path("events").size());
        assertEquals(3, fundingEvents.size());
        assertEquals("UNQUALIFIED_NO_EFFECTIVE_TIER", result.path("events").get(1).path("maintenance_status").asText());
        assertEquals(3_000, fundingEvents.get(0).path("unfunded_deficit_usdt").asDouble(), 1e-8);
        assertEquals(3_000, fundingEvents.get(1).path("liability_paid_usdt").asDouble(), 1e-8);
        assertEquals("ZERO_RATE_RETAINED", fundingEvents.get(2).path("status").asText());
        assertEquals(0, result.path("unpaid_funding_liability_usdt").asDouble(), 1e-10);
        assertEquals(0, result.path("funding_pnl_usdt").asDouble(), 1e-8);
        assertEquals(1_000, result.path("free_balance_usdt").asDouble(), 1e-8);
        assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-8);
        assertFalse(result.path("events").get(1).path("liquidated").asBoolean());
    }

    @Test
    void stopGapUsesFilledTrancheRiskWhenNoFullPositionRiskWasDeclared() {
        ObjectNode request = base("LONG", 90, null, 0);
        add(request, "risk-fallback-setup", 1, T, "LONG", 100, 90, null, "CONTINUATION", 100_000);
        bar(request, "BAR_PRE", T + 60_000, T + 60_000, 90, 91, 89, 90, 90, 91, 89, 90)
                .put("adverse_gap_r", 0.25);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);
        JsonNode exitEvent = result.path("events").get(1);

        assertEquals("EXIT_STOP_GAP", exitEvent.path("type").asText());
        assertEquals(200, exitEvent.path("execution_gap_reference_risk_usdt").asDouble(), 1e-8);
        assertEquals(50, exitEvent.path("execution_gap_debit_usdt").asDouble(), 1e-8);
        assertEquals(87.5, exitEvent.path("exit_price").asDouble(), 1e-8);
        assertEquals(0, result.path("positions").get(0).path("quantity").asDouble(), 1e-10);
        assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-8);
    }

    @Test
    void targetGapAndCompletedTargetShareOneExitWhileLaterBarsKeepFlatMarks() {
        ObjectNode request = base("LONG", 90, 110.0, 0);
        add(request, "target-gap-setup", 1, T, "LONG", 100, 90, 110.0, "CONTINUATION", 100_000);
        bar(request, "BAR_PRE", T + 60_000, T + 60_000, 120, 121, 119, 120, 120, 121, 119, 120);
        bar(request, "BAR_PRE", T + 120_000, T + 120_000, 120, 122, 118, 121, 120, 122, 118, 121);
        bar(request, "BAR_POST", T + 180_000, T + 120_000, 121, 122, 118, 120, 121, 122, 118, 120);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);

        assertEquals("EXIT_TARGET_GAP", result.path("events").get(1).path("type").asText());
        assertEquals("RECOVERY_TARGET_GAP", result.path("positions").get(0).path("exits").get(0).path("reason").asText());
        assertEquals(120, result.path("positions").get(0).path("exits").get(0).path("price").asDouble(), 1e-10);
        assertEquals("BAR_OPEN_NO_POSITION", result.path("events").get(2).path("type").asText());
        assertEquals("BAR_CLOSE_NO_POSITION", result.path("events").get(3).path("type").asText());
        assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-8);
        assertTrue(result.path("positions").get(0).path("fills").size() == 1);
    }

    @Test
    void shortGapExitUsesTheShortSideAndTheFilledTrancheRiskFallback() {
        ObjectNode request = base("SHORT", 110, null, 0);
        add(request, "short-gap-setup", 1, T, "SHORT", 100, 110, null, "CONTINUATION", 100_000);
        bar(request, "BAR_PRE", T + 60_000, T + 60_000, 115, 116, 114, 115, 115, 116, 114, 115)
                .put("adverse_gap_r", 0.25);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);
        JsonNode exitEvent = result.path("events").get(1);
        JsonNode position = result.path("positions").get(0);

        assertEquals("EXIT_STOP_GAP", exitEvent.path("type").asText());
        assertEquals(200, exitEvent.path("execution_gap_reference_risk_usdt").asDouble(), 1e-8);
        assertEquals(50, exitEvent.path("execution_gap_debit_usdt").asDouble(), 1e-8);
        assertEquals(117.5, exitEvent.path("exit_price").asDouble(), 1e-8);
        assertEquals(-350, position.path("realized_gross_pnl_usdt").asDouble(), 1e-8);
        assertEquals(19_650, result.path("free_balance_usdt").asDouble(), 1e-8);
        assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-8);
    }

    @Test
    void positiveFundingWithoutDebtAddsCashAndSettlementsOutsideAnOpenPositionAreRetained() {
        ObjectNode request = base("SHORT", 110, null, 0);
        funding(request, "before-first-fill", T, 0.001);
        add(request, "funding-credit-setup", 1, T, "SHORT", 100, 110, null, "CONTINUATION", 100_000);
        funding(request, "positive-cash-credit", T + 60_000, 0.01);
        request.withArray("events").addObject().put("type", "EXIT").put("asset", "BTC")
                .put("time", T + 120_000).put("price", 100).put("reason", "MATRIX_CLOSE");
        funding(request, "after-position-close", T + 180_000, 0.001);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);
        JsonNode fundingEvents = result.path("positions").get(0).path("funding_events");

        assertEquals("FUNDING_NO_POSITION", result.path("events").get(0).path("type").asText());
        assertEquals("ADD_FILLED", result.path("events").get(1).path("type").asText());
        assertEquals("FUNDING_SETTLED", result.path("events").get(2).path("type").asText());
        assertEquals(20, fundingEvents.get(0).path("amount_usdt").asDouble(), 1e-8);
        assertEquals("FUNDING_NO_POSITION", result.path("events").get(4).path("type").asText());
        assertEquals(2, fundingEvents.size(), "the first pre-fill event stays in the portfolio event stream, not the reset position episode");
        assertEquals("NO_POSITION_AT_SETTLEMENT", fundingEvents.get(1).path("status").asText());
        assertEquals(0, result.path("unpaid_funding_liability_usdt").asDouble(), 1e-10);
        assertEquals(20_020, result.path("free_balance_usdt").asDouble(), 1e-8);
        assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-8);
    }

    @Test
    void effectiveMetadataAppliedBeforeFillChangesCostsAndClosesThroughTheUpdatedTerms() {
        ObjectNode request = base("LONG", 90, null, 0);
        request.withArray("events").addObject().put("type", "METADATA").put("asset", "BTC").put("time", T)
                .put("effective_from", T).put("effective_until", T + 300_000)
                .put("lot_size", 0.001).put("minimum_notional", 5)
                .put("taker_fee_rate", 0.001).put("slippage_rate", 0.002).put("liquidation_fee_rate", 0.001);
        add(request, "updated-metadata-setup", 1, T, "LONG", 100, 90, null, "CONTINUATION", 100_000);
        request.withArray("events").addObject().put("type", "EXIT").put("asset", "BTC")
                .put("time", T + 60_000).put("price", 105).put("reason", "MATRIX_CLOSE");

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);
        JsonNode position = result.path("positions").get(0);

        assertEquals("METADATA_APPLIED", result.path("events").get(0).path("type").asText());
        assertEquals("ADD_FILLED", result.path("events").get(1).path("type").asText());
        assertTrue(position.path("entry_costs_usdt").asDouble() > 0);
        assertTrue(position.path("exits").get(0).path("exit_costs_usdt").asDouble() > 0);
        assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-8);
    }

    private static ObjectNode base(String direction, double stop, Double target, double reserve) {
        ObjectNode request = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-shared-account-fixture/1")
                .put("initial_equity_usdt", 20_000).put("reserved_costs_usdt", reserve);
        ObjectNode position = request.putArray("positions").addObject().put("asset", "BTC")
                .put("direction", direction).put("common_stop", stop).put("lot_size", 0.01)
                .put("minimum_notional", 5).put("taker_fee_rate", 0).put("slippage_rate", 0)
                .put("liquidation_fee_rate", 0).put("initial_mark_price", 100);
        if (target == null) position.putNull("recovery_target"); else position.put("recovery_target", target);
        request.putArray("events");
        return request;
    }

    private static void add(ObjectNode request, String setup, int stage, long time, String direction,
            double price, double stop, Double target, String mode, double volume) {
        ObjectNode row = request.withArray("events").addObject().put("type", "ADD").put("asset", "BTC")
                .put("time", time).put("stage", stage).put("setup_id", setup).put("intent_id", setup + "-intent")
                .put("decision_time", time).put("direction", direction).put("mode", mode)
                .put("price", price).put("decision_mark", price).put("common_stop", stop)
                .put("previous_completed_minute_base_volume", volume);
        if (target == null) row.putNull("recovery_target"); else row.put("recovery_target", target);
    }

    private static void funding(ObjectNode request, String id, long time, double rate) {
        request.withArray("events").addObject().put("type", "FUNDING").put("asset", "BTC")
                .put("time", time).put("event_id", id).put("funding_rate", rate).put("mark_price", 100);
    }

    private static ObjectNode bar(ObjectNode request, String type, long time, long start,
            double tradeOpen, double tradeHigh, double tradeLow, double tradeClose,
            double markOpen, double markHigh, double markLow, double markClose) {
        return request.withArray("events").addObject().put("type", type).put("asset", "BTC")
                .put("time", time).put("bar_start_time", start)
                .put("trade_open", tradeOpen).put("trade_high", tradeHigh).put("trade_low", tradeLow).put("trade_close", tradeClose)
                .put("mark_open", markOpen).put("mark_high", markHigh).put("mark_low", markLow).put("mark_close", markClose);
    }
}
