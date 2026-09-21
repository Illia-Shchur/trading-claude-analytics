package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import org.junit.jupiter.api.Test;

class LiquidationStagedPerpetualLifecycleV1Test {
    private static final long T = 1_680_000_000_000L;

    @Test
    void threeStagesShareWeightedPositionAndFundingUsesQuantityOpenAtSettlement() {
        ObjectNode request = request();
        add(request, 1, T, 100, 10_000, "CONTINUATION", 100);
        add(request, 2, T + 3_600_000, 105, 10_000, "CONTINUATION", 105);
        add(request, 3, T + 7_200_000, 110, 10_000, "CONTINUATION", 110);
        funding(request, "funding-after-stage3", T + 10_800_000, 0.001, 110);

        ObjectNode result = LiquidationStagedPerpetualLifecycleV1.accountSyntheticFixture(request);
        assertEquals(65, result.path("quantity").asDouble(), 1e-10, result.toPrettyString());
        assertEquals(6_850.0 / 65.0, result.path("weighted_entry").asDouble(), 1e-10);
        assertEquals(1_000, result.path("open_stop_risk_usdt").asDouble(), 1e-10);
        assertEquals(300, result.path("gross_unrealized_pnl_usdt").asDouble(), 1e-10);
        assertEquals(-7.15, result.path("funding_pnl_usdt").asDouble(), 1e-10);
        assertEquals(65, result.path("funding_events").get(0).path("charged_quantity_at_settlement").asDouble(), 1e-10);
        assertEquals(6_850.0 / 3.0, result.path("isolated_margin_locked_usdt").asDouble(), 1e-10);
        assertFalse(result.path("authoritative").asBoolean());
    }

    @Test
    void fundingDebitBeforeThirdStageReducesRemainingRiskWithoutFundingCreditsMintingRisk() {
        ObjectNode request = request();
        add(request, 1, T, 100, 10_000, "CONTINUATION", 100);
        add(request, 2, T + 3_600_000, 105, 10_000, "CONTINUATION", 105);
        funding(request, "funding-before-stage3", T + 5_400_000, 0.001, 105);
        add(request, 3, T + 7_200_000, 110, 10_000, "CONTINUATION", 110);

        ObjectNode result = LiquidationStagedPerpetualLifecycleV1.accountSyntheticFixture(request);

        assertEquals(64.79, result.path("quantity").asDouble(), 1e-10);
        assertEquals(4.2, result.path("funding_debits_for_risk_headroom_usdt").asDouble(), 1e-10);
        assertEquals(1_000, result.path("risk_committed_usdt").asDouble(), 1e-8);

        ObjectNode credit = request();
        add(credit, 1, T, 100, 10_000, "CONTINUATION", 100);
        add(credit, 2, T + 3_600_000, 105, 10_000, "CONTINUATION", 105);
        funding(credit, "funding-credit", T + 5_400_000, -0.001, 105);
        add(credit, 3, T + 7_200_000, 110, 10_000, "CONTINUATION", 110);
        ObjectNode creditResult = LiquidationStagedPerpetualLifecycleV1.accountSyntheticFixture(credit);
        assertEquals(25, creditResult.path("fills").get(2).path("quantity").asDouble(), 1e-10);
        assertEquals(0, creditResult.path("funding_debits_for_risk_headroom_usdt").asDouble(), 1e-10);
    }

    @Test
    void reversalAdditionMustRetainOneRToTheUnchangedTargetAndCannotAddAfterTarget() {
        ObjectNode request = request();
        request.put("recovery_target", 120);
        add(request, 1, T, 100, 10_000, "REVERSAL", 100);
        add(request, 2, T + 3_600_000, 110, 10_000, "REVERSAL", 110);
        ObjectNode result = LiquidationStagedPerpetualLifecycleV1.accountSyntheticFixture(request);

        assertEquals(1, result.path("fills").size());
        assertTrue(containsReason(result, "REVERSAL_ADDITION_RR_BELOW_1R_OR_TARGET_REACHED"));

        ObjectNode reached = request();
        reached.put("recovery_target", 120);
        add(reached, 1, T, 100, 10_000, "REVERSAL", 100);
        add(reached, 2, T + 3_600_000, 105, 10_000, "REVERSAL", 120);
        ObjectNode reachedResult = LiquidationStagedPerpetualLifecycleV1.accountSyntheticFixture(reached);
        assertTrue(containsReason(reachedResult, "REVERSAL_TARGET_ALREADY_REACHED"));
    }

    @Test
    void volumeCapacityAndEntryHourAreDeterministicAndOnePositionCannotSkipOrAddFourthStage() {
        ObjectNode request = request();
        add(request, 1, T, 100, 1_000, "CONTINUATION", 100);
        add(request, 2, T + 30_000, 101, 10_000, "CONTINUATION", 101);
        add(request, 3, T + 3_600_000, 102, 10_000, "CONTINUATION", 102);
        add(request, 4, T + 7_200_000, 103, 10_000, "CONTINUATION", 103);
        ObjectNode result = LiquidationStagedPerpetualLifecycleV1.accountSyntheticFixture(request);

        assertEquals(10, result.path("fills").get(0).path("quantity").asDouble(), 1e-10);
        assertTrue(containsReason(result, "ENTRY_HOUR_ALREADY_USED"));
        assertTrue(containsReason(result, "INVALID_OR_NONSEQUENTIAL_STAGE"));
        assertEquals(1, result.path("fills").size());
    }

    @Test
    void signedFundingAndStopAccountingWorkForShortPositionsAndZeroSettlementsAreRetained() {
        ObjectNode request = request();
        request.put("direction", "SHORT").put("common_stop", 110).put("recovery_target", 80).put("initial_mark_price", 100);
        add(request, 1, T, 100, 10_000, "CONTINUATION", 100);
        funding(request, "zero-rate", T + 3_600_000, 0, 100);
        funding(request, "short-credit", T + 7_200_000, 0.001, 100);

        ObjectNode result = LiquidationStagedPerpetualLifecycleV1.accountSyntheticFixture(request);

        assertEquals(20, result.path("quantity").asDouble(), 1e-10);
        assertEquals(200, result.path("open_stop_risk_usdt").asDouble(), 1e-10);
        assertEquals(2, result.path("funding_events").size());
        assertEquals("ZERO_RATE_RETAINED", result.path("funding_events").get(0).path("status").asText());
        assertEquals(2, result.path("funding_pnl_usdt").asDouble(), 1e-10);
        assertEquals(200, result.path("risk_committed_usdt").asDouble(), 1e-10);
    }

    @Test
    void wholePositionExitReleasesCollateralAndBooksExitCostsAndCashPnlOnce() {
        ObjectNode request = request();
        request.put("taker_fee_rate", 0.001);
        add(request, 1, T, 100, 10_000, "CONTINUATION", 100);
        ((ArrayNode) request.path("exit_events")).addObject().put("time", T + 60_000).put("price", 110).put("reason", "CONTINUATION_TRAIL_STOP");

        ObjectNode result = LiquidationStagedPerpetualLifecycleV1.accountSyntheticFixture(request);

        assertEquals("CLOSED", result.path("status").asText());
        assertEquals(0, result.path("quantity").asDouble(), 1e-10);
        assertEquals(196.2, result.path("realized_gross_pnl_usdt").asDouble(), 1e-10);
        assertEquals(1.962, result.path("entry_costs_usdt").asDouble(), 1e-10);
        assertEquals(2.1582, result.path("exit_costs_usdt").asDouble(), 1e-10);
        assertEquals(0, result.path("isolated_margin_locked_usdt").asDouble(), 1e-10);
        assertEquals(20_192.0798, result.path("mark_to_market_equity_usdt").asDouble(), 1e-8);
        assertEquals("CONTINUATION_TRAIL_STOP", result.path("exits").get(0).path("reason").asText());
    }

    private static ObjectNode request() {
        ObjectNode value = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-lifecycle-fixture/1")
                .put("asset", "BTC").put("direction", "LONG").put("initial_equity_usdt", 20_000)
                .put("first_fill_reference_equity_usdt", 20_000).put("portfolio_existing_stop_risk_usdt", 0)
                .put("portfolio_existing_locked_margin_usdt", 0).put("reserved_costs_usdt", 0)
                .put("common_stop", 90).put("recovery_target", 130).put("lot_size", 0.01)
                .put("minimum_notional", 5).put("taker_fee_rate", 0).put("slippage_rate", 0)
                .put("initial_mark_price", 100);
        value.putArray("add_requests"); value.putArray("funding_events"); value.putArray("exit_events");
        return value;
    }

    private static void add(ObjectNode request, int stage, long time, double price, double volume, String mode, double mark) {
        ((ArrayNode) request.path("add_requests")).addObject().put("stage", stage).put("time", time).put("price", price)
                .put("previous_completed_minute_base_volume", volume).put("mode", mode).put("decision_mark", mark);
    }

    private static void funding(ObjectNode request, String id, long time, double rate, double mark) {
        ((ArrayNode) request.path("funding_events")).addObject().put("event_id", id).put("settlement_time", time)
                .put("funding_rate", rate).put("mark_price", mark);
    }

    private static boolean containsReason(ObjectNode result, String reason) {
        for (JsonNode event : result.path("events")) if (reason.equals(event.path("reason").asText())) return true;
        return false;
    }
}
