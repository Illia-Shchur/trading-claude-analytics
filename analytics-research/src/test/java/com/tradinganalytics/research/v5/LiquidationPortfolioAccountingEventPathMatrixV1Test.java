package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import org.junit.jupiter.api.Test;

/** Accepted short-side carry, leverage, maintenance-window, and minute-path controls. */
class LiquidationPortfolioAccountingEventPathMatrixV1Test {
    private static final long T = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;

    @Test
    void opposingFundingSettlementsAtFixedShortMarkNetToZeroWithoutErasingDebitRisk() {
        ObjectNode request = account("SHORT", 110, null);
        add(request, 1, T, 100, 110, 2_000);
        funding(request, "short-credit", T + 60_000, 0.001, 100);
        funding(request, "short-debit", T + 120_000, -0.001, 100);

        ObjectNode ledger = LiquidationPortfolioAccountingV1.replayFixture(request);
        JsonNode position = ledger.path("positions").get(0);
        JsonNode fills = position.path("fills");
        JsonNode fundingEvents = position.path("funding_events");

        assertEquals(1, fills.size());
        assertEquals(20, fills.get(0).path("quantity").asDouble(), 0.0);
        assertEquals(2, fundingEvents.get(0).path("amount_usdt").asDouble(), 0.0);
        assertEquals(-2, fundingEvents.get(1).path("amount_usdt").asDouble(), 0.0);
        assertEquals(2, position.path("funding_debits_for_risk_headroom_usdt").asDouble(), 0.0);
        assertEquals(0, ledger.path("funding_pnl_usdt").asDouble(), 0.0);
        assertEquals(0, position.path("gross_unrealized_pnl_usdt").asDouble(), 0.0);
        assertEquals(20_000, ledger.path("mark_to_market_equity_usdt").asDouble(), 0.0);
        assertEquals(19_000, ledger.path("free_balance_usdt").asDouble(), 0.0);
        assertEquals(0, ledger.path("reconciliation_delta_usdt").asDouble(), 0.0);
    }

    @Test
    void thirdStageChangesWholeShortCollateralToThreeXAtUnchangedMark() {
        ObjectNode request = account("SHORT", 110, null);
        add(request, 1, T, 100, 110, 100_000);
        add(request, 2, T + HOUR, 100, 110, 100_000);
        add(request, 3, T + 2 * HOUR, 100, 110, 100_000);

        ObjectNode ledger = LiquidationPortfolioAccountingV1.replayFixture(request);
        JsonNode position = ledger.path("positions").get(0);

        assertEquals(3, position.path("fills").size());
        assertEquals(2, position.path("fills").get(0).path("stage_leverage").asInt());
        assertEquals(2, position.path("fills").get(1).path("stage_leverage").asInt());
        assertEquals(3, position.path("fills").get(2).path("stage_leverage").asInt());
        assertEquals(100, position.path("quantity").asDouble(), 1e-10);
        double expectedMargin = 10_000.0 / 3.0;
        double expectedFree = 20_000.0 - expectedMargin;
        assertEquals(expectedMargin, position.path("isolated_margin_locked_usdt").asDouble(), Math.ulp(expectedMargin));
        assertEquals(expectedFree, ledger.path("free_balance_usdt").asDouble(), Math.ulp(expectedFree));
        assertEquals(0, position.path("realized_gross_pnl_usdt").asDouble(), 0.0);
        assertEquals(0, position.path("gross_unrealized_pnl_usdt").asDouble(), 0.0);
        assertEquals(20_000, ledger.path("mark_to_market_equity_usdt").asDouble(), 0.0);
        assertEquals(0, ledger.path("reconciliation_delta_usdt").asDouble(), 0.0);
    }

    @Test
    void shortFundingAndBothMinutePhasesRemainQualifiedInsideAnEffectiveTier() {
        ObjectNode request = account("SHORT", 110, null);
        tiers(request).addObject().put("effective_from", T).put("effective_until", T + HOUR)
                .put("tier_notional_cap", 100_000).put("maintenance_margin_rate", 0.01).put("maintenance_deduction", 0);
        add(request, 1, T, 100, 110, 100_000);
        funding(request, "tier-covered-debit", T + 60_000, -0.001, 100);
        bar(request, "BAR_PRE", T + 120_000, T + 120_000, 100, 105, 95, 102, 100, 105, 95, 102);
        bar(request, "BAR_POST", T + 180_000, T + 120_000, 102, 105, 99, 101, 102, 105, 99, 101);

        ObjectNode ledger = LiquidationPortfolioAccountingV1.replayFixture(request);
        JsonNode events = ledger.path("events");
        JsonNode position = ledger.path("positions").get(0);

        assertEquals("FUNDING_SETTLED", events.get(1).path("type").asText());
        assertFalse(events.get(1).has("maintenance_status"));
        assertEquals("BAR_OPEN_PROCESSED", events.get(2).path("type").asText());
        assertEquals("BAR_PROCESSED", events.get(3).path("type").asText());
        assertEquals(20, position.path("quantity").asDouble(), 1e-10);
        assertTrue(position.path("exits").isEmpty());
        assertEquals(0, ledger.path("reconciliation_delta_usdt").asDouble(), 1e-9);
    }

    @Test
    void maintenanceTierEndIsExclusiveForFundingQualification() {
        ObjectNode request = account("SHORT", 110, null);
        tiers(request).addObject().put("effective_from", T).put("effective_until", T + 60_000)
                .put("tier_notional_cap", 100_000).put("maintenance_margin_rate", 0.01).put("maintenance_deduction", 0);
        add(request, 1, T, 100, 110, 100_000);
        funding(request, "inside-effective-interval", T + 59_999, -0.001, 100);
        funding(request, "at-exclusive-effective-end", T + 60_000, -0.001, 100);

        ObjectNode ledger = LiquidationPortfolioAccountingV1.replayFixture(request);
        JsonNode events = ledger.path("events");

        assertEquals("FUNDING_SETTLED", events.get(1).path("type").asText());
        assertFalse(events.get(1).has("maintenance_status"));
        assertEquals("FUNDING_SETTLED", events.get(2).path("type").asText());
        assertEquals("UNQUALIFIED_NO_EFFECTIVE_TIER", events.get(2).path("maintenance_status").asText());
        assertEquals(0, ledger.path("reconciliation_delta_usdt").asDouble(), 1e-9);
    }

    @Test
    void shortIntrabarStopWinsCollisionAndSeparateTargetOnlyPathClosesAtTarget() {
        ObjectNode stopRequest = account("SHORT", 110, 80.0);
        add(stopRequest, 1, T, 100, 110, 100_000);
        bar(stopRequest, "BAR_POST", T + 60_000, T, 100, 112, 75, 90, 100, 112, 75, 90);
        ObjectNode stopped = LiquidationPortfolioAccountingV1.replayFixture(stopRequest);
        assertEquals("STOP", stopped.path("positions").get(0).path("exits").get(0).path("reason").asText());
        assertEquals(110, stopped.path("positions").get(0).path("exits").get(0).path("price").asDouble(), 1e-10);

        ObjectNode targetRequest = account("SHORT", 110, 80.0);
        add(targetRequest, 1, T, 100, 110, 100_000);
        bar(targetRequest, "BAR_POST", T + 60_000, T, 100, 105, 75, 90, 100, 105, 75, 90);
        ObjectNode targeted = LiquidationPortfolioAccountingV1.replayFixture(targetRequest);
        assertEquals("RECOVERY_TARGET", targeted.path("positions").get(0).path("exits").get(0).path("reason").asText());
        assertEquals(80, targeted.path("positions").get(0).path("exits").get(0).path("price").asDouble(), 1e-10);
        assertEquals(0, targeted.path("reconciliation_delta_usdt").asDouble(), 1e-9);
    }

    @Test
    void validLongAndShortReversalAdditionsRequireAndPreserveTheDirectionalTarget() {
        ObjectNode longRequest = account("LONG", 90, 125.0);
        add(longRequest, 1, T, 100, 90, 100_000).put("mode", "REVERSAL").put("decision_mark", 101);
        ObjectNode longLedger = LiquidationPortfolioAccountingV1.replayFixture(longRequest);
        assertEquals("ADD_FILLED", longLedger.path("events").get(0).path("type").asText());
        assertEquals(125, longLedger.path("positions").get(0).path("recovery_target").asDouble(), 1e-10);

        ObjectNode shortRequest = account("SHORT", 110, 75.0);
        add(shortRequest, 1, T, 100, 110, 100_000).put("mode", "REVERSAL").put("decision_mark", 99);
        ObjectNode shortLedger = LiquidationPortfolioAccountingV1.replayFixture(shortRequest);
        assertEquals("ADD_FILLED", shortLedger.path("events").get(0).path("type").asText());
        assertEquals(75, shortLedger.path("positions").get(0).path("recovery_target").asDouble(), 1e-10);
        assertEquals(0, shortLedger.path("reconciliation_delta_usdt").asDouble(), 1e-9);
    }

    @Test
    void historicalSinglePositionAdapterProjectsOptionalTargetAndDefaultsOmittedAddFields() {
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-lifecycle-fixture/1")
                .put("asset", "BTC").put("direction", "LONG").put("initial_equity_usdt", 20_000)
                .put("first_fill_reference_equity_usdt", 20_000).put("portfolio_existing_stop_risk_usdt", 0)
                .put("portfolio_existing_locked_margin_usdt", 0).put("reserved_costs_usdt", 0)
                .put("common_stop", 90).put("recovery_target", 120).put("lot_size", 0.01)
                .put("minimum_notional", 5).put("taker_fee_rate", 0).put("slippage_rate", 0)
                .put("liquidation_fee_rate", 0.001).put("initial_mark_price", 100);
        request.putArray("add_requests").addObject().put("stage", 1).put("time", T).put("price", 100)
                .put("previous_completed_minute_base_volume", 100_000);
        request.putArray("funding_events");
        request.putArray("exit_events");

        ObjectNode result = LiquidationPortfolioAccountingV1.singlePositionFixtureResult(request);

        assertEquals("OPEN", result.path("status").asText());
        assertEquals(120, result.path("recovery_target").asDouble(), 0.0);
        assertEquals(1, result.path("fills").size());
        assertEquals(20, result.path("quantity").asDouble(), 0.0);
        assertEquals(JsonHashes.ownHash(result), result.path("content_sha256").asText());
    }

    @Test
    void shortOpeningGapsUseTheShortStopAndTargetRules() {
        ObjectNode stopRequest = account("SHORT", 110, 80.0);
        add(stopRequest, 1, T, 100, 110, 100_000);
        bar(stopRequest, "BAR_PRE", T + 60_000, T + 60_000, 115, 116, 114, 115, 115, 116, 114, 115);
        ObjectNode stopped = LiquidationPortfolioAccountingV1.replayFixture(stopRequest);
        assertEquals("STOP_GAP", stopped.path("positions").get(0).path("exits").get(0).path("reason").asText());
        assertEquals(115, stopped.path("positions").get(0).path("exits").get(0).path("price").asDouble(), 0.0);

        ObjectNode targetRequest = account("SHORT", 110, 80.0);
        add(targetRequest, 1, T, 100, 110, 100_000);
        bar(targetRequest, "BAR_PRE", T + 60_000, T + 60_000, 75, 81, 74, 78, 75, 81, 74, 78);
        ObjectNode targeted = LiquidationPortfolioAccountingV1.replayFixture(targetRequest);
        assertEquals("RECOVERY_TARGET_GAP", targeted.path("positions").get(0).path("exits").get(0).path("reason").asText());
        assertEquals(75, targeted.path("positions").get(0).path("exits").get(0).path("price").asDouble(), 0.0);
        assertEquals(0, targeted.path("reconciliation_delta_usdt").asDouble(), 0.0);
    }

    @Test
    void admissionMinimumNotionalAndInvalidShortStopFailClosedWithoutPositionMutation() {
        ObjectNode minimumRequest = account("LONG", 90, null);
        ((ObjectNode) minimumRequest.path("positions").get(0)).put("minimum_notional", 1_500);
        add(minimumRequest, 1, T, 100, 90, 1_000);
        ObjectNode minimumResult = LiquidationPortfolioAccountingV1.replayFixture(minimumRequest);
        assertEquals("RISK_CAPACITY_COLLATERAL_OR_MINIMUM_NOTIONAL", minimumResult.path("events").get(0).path("reason").asText());
        assertEquals(0, minimumResult.path("positions").get(0).path("quantity").asDouble(), 0.0);

        ObjectNode shortStopRequest = account("SHORT", 90, null);
        add(shortStopRequest, 1, T, 100, 90, 100_000);
        ObjectNode shortStopResult = LiquidationPortfolioAccountingV1.replayFixture(shortStopRequest);
        assertEquals("INVALID_SHORT_STOP", shortStopResult.path("events").get(0).path("reason").asText());
        assertEquals(0, shortStopResult.path("positions").get(0).path("quantity").asDouble(), 0.0);
    }

    @Test
    void adverseGapStressRejectsAnExecutionPriceAtOrBelowZero() {
        ObjectNode request = account("LONG", 90, null);
        add(request, 1, T, 100, 90, 100_000);
        bar(request, "BAR_PRE", T + 60_000, T + 60_000, 90, 91, 89, 90, 90, 91, 89, 90)
                .put("adverse_gap_r", 10);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioAccountingV1.replayFixture(request));
        assertTrue(failure.getMessage().contains("adverse gap stress produced a nonpositive execution price"), failure.getMessage());
    }

    @Test
    void publicReplayMayHoldAnUnconfiguredFlatInventoryButSharedFixtureCannot() {
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-public-replay-account/1")
                .put("initial_equity_usdt", 20_000).put("reserved_costs_usdt", 0);
        request.putArray("positions").addObject().put("asset", "BTC").put("lot_size", 0.01)
                .put("minimum_notional", 5).put("taker_fee_rate", 0).put("slippage_rate", 0)
                .put("liquidation_fee_rate", 0).put("initial_mark_price", 100);
        request.putArray("events").addObject().put("type", "MARK").put("asset", "BTC").put("time", T).put("price", 101);

        ObjectNode accepted = LiquidationPortfolioAccountingV1.replayFixture(request);
        JsonNode position = accepted.path("positions").get(0);
        assertEquals("UNCONFIGURED", position.path("direction").asText());
        assertTrue(position.path("common_stop").isNull());
        assertEquals(101, position.path("last_mark_price").asDouble(), 0.0);
        assertEquals(0, accepted.path("reconciliation_delta_usdt").asDouble(), 0.0);

        ObjectNode sharedFixture = request.deepCopy().put("schema", "liquidation-v2-shared-account-fixture/1");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioAccountingV1.replayFixture(sharedFixture));
        assertTrue(failure.getMessage().contains("position direction must be LONG or SHORT"), failure.getMessage());
    }

    @Test
    void configuredEpisodeRejectsBadLongShortGeometryAndUnknownDirection() {
        ObjectNode longRequest = account("LONG", 90, 120.0);
        add(longRequest, 1, T, 100, 90, 100_000).put("common_stop", 130);
        IllegalArgumentException badLong = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioAccountingV1.replayFixture(longRequest));
        assertTrue(badLong.getMessage().contains("episode stop and recovery target are directionally incoherent"), badLong.getMessage());

        ObjectNode shortRequest = account("SHORT", 110, 80.0);
        add(shortRequest, 1, T, 100, 110, 100_000).put("common_stop", 90).put("recovery_target", 120);
        IllegalArgumentException badShort = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioAccountingV1.replayFixture(shortRequest));
        assertTrue(badShort.getMessage().contains("episode stop and recovery target are directionally incoherent"), badShort.getMessage());

        ObjectNode badDirection = account("LONG", 90, 120.0);
        add(badDirection, 1, T, 100, 90, 100_000).put("direction", "SIDEWAYS");
        IllegalArgumentException wrongDirection = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioAccountingV1.replayFixture(badDirection));
        assertTrue(wrongDirection.getMessage().contains("new episode direction must be LONG or SHORT"), wrongDirection.getMessage());
    }

    @Test
    void metadataWithoutAnOptionalEffectiveWindowAppliesBeforeTheFill() {
        ObjectNode request = account("LONG", 90, null);
        request.withArray("events").addObject().put("type", "METADATA").put("asset", "BTC").put("time", T)
                .put("lot_size", 0.001).put("minimum_notional", 5).put("taker_fee_rate", 0.001)
                .put("slippage_rate", 0.002).put("liquidation_fee_rate", 0.001);
        add(request, 1, T, 100, 90, 100_000);

        ObjectNode ledger = LiquidationPortfolioAccountingV1.replayFixture(request);
        assertEquals("METADATA_APPLIED", ledger.path("events").get(0).path("type").asText());
        assertEquals("ADD_FILLED", ledger.path("events").get(1).path("type").asText());
        assertTrue(ledger.path("positions").get(0).path("entry_costs_usdt").asDouble() > 0);
        assertEquals(0, ledger.path("reconciliation_delta_usdt").asDouble(), 0.0);
    }

    private static ObjectNode account(String direction, double stop, Double target) {
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-shared-account-fixture/1")
                .put("initial_equity_usdt", 20_000).put("reserved_costs_usdt", 0);
        ObjectNode position = request.putArray("positions").addObject().put("asset", "BTC").put("direction", direction)
                .put("common_stop", stop).put("lot_size", 0.01).put("minimum_notional", 5)
                .put("taker_fee_rate", 0).put("slippage_rate", 0).put("liquidation_fee_rate", 0)
                .put("initial_mark_price", 100);
        if (target == null) position.putNull("recovery_target"); else position.put("recovery_target", target);
        request.putArray("events");
        return request;
    }

    private static ObjectNode add(ObjectNode request, int stage, long time, double price, double stop, double capacity) {
        ObjectNode row = request.withArray("events").addObject().put("type", "ADD").put("asset", "BTC").put("time", time)
                .put("stage", stage).put("setup_id", "common-episode").put("intent_id", "intent-" + stage)
                .put("decision_time", time).put("direction", request.path("positions").get(0).path("direction").asText())
                .put("mode", "CONTINUATION").put("common_stop", stop);
        row.set("recovery_target", request.path("positions").get(0).path("recovery_target").deepCopy());
        return row.put("price", price).put("decision_mark", price).put("previous_completed_minute_base_volume", capacity);
    }

    private static void funding(ObjectNode request, String id, long time, double rate, double mark) {
        request.withArray("events").addObject().put("type", "FUNDING").put("asset", "BTC").put("time", time)
                .put("event_id", id).put("funding_rate", rate).put("mark_price", mark);
    }

    private static ObjectNode bar(ObjectNode request, String type, long time, long start,
            double tradeOpen, double tradeHigh, double tradeLow, double tradeClose,
            double markOpen, double markHigh, double markLow, double markClose) {
        return request.withArray("events").addObject().put("type", type).put("asset", "BTC").put("time", time)
                .put("bar_start_time", start).put("trade_open", tradeOpen).put("trade_high", tradeHigh)
                .put("trade_low", tradeLow).put("trade_close", tradeClose).put("mark_open", markOpen)
                .put("mark_high", markHigh).put("mark_low", markLow).put("mark_close", markClose);
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode tiers(ObjectNode request) {
        return ((ObjectNode) request.withArray("positions").get(0)).withArray("maintenance_tiers");
    }
}
