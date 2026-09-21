package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import org.junit.jupiter.api.Test;

class LiquidationPortfolioAccountingV1Test {
    private static final long T = 1_680_000_000_000L;
    private static final long DAY = 86_400_000L;

    @Test
    void concurrentAssetsShareNoOffsetRiskAndCanonicalAdmissionOrderIgnoresInputOrder() {
        ObjectNode first = portfolioRequest();
        mark(first, "BTC", T + 3_600_000, 105); mark(first, "ETH", T + 3_600_000, 95); mark(first, "SOL", T + 3_600_000, 105);
        add(first, "BTC", 1, T, 100, 100_000, "LONG");
        add(first, "ETH", 1, T, 100, 100_000, "SHORT");
        add(first, "SOL", 1, T, 100, 100_000, "LONG");
        add(first, "BTC", 2, T + 3_600_000, 105, 100_000, "LONG");
        add(first, "ETH", 2, T + 3_600_000, 95, 100_000, "SHORT");
        add(first, "SOL", 2, T + 3_600_000, 105, 100_000, "LONG");
        mark(first, "BTC", T + 7_200_000, 110); mark(first, "ETH", T + 7_200_000, 90); mark(first, "SOL", T + 7_200_000, 110);
        add(first, "BTC", 3, T + 7_200_000, 110, 100_000, "LONG");
        add(first, "ETH", 3, T + 7_200_000, 90, 100_000, "SHORT");
        add(first, "SOL", 3, T + 7_200_000, 110, 100_000, "LONG");

        ObjectNode second = first.deepCopy();
        reverse((ArrayNode) second.path("events"));
        reverse((ArrayNode) second.path("positions"));

        ObjectNode a = LiquidationPortfolioAccountingV1.replayFixture(first);
        ObjectNode b = LiquidationPortfolioAccountingV1.replayFixture(second);

        assertEquals(JsonHashes.canonicalSha256(a), JsonHashes.canonicalSha256(b));
        assertEquals("BTC", a.path("positions").get(0).path("asset").asText());
        assertEquals("ETH", a.path("positions").get(1).path("asset").asText());
        assertEquals("SOL", a.path("positions").get(2).path("asset").asText());
        assertEquals(65, a.path("positions").get(0).path("quantity").asDouble(), 1e-10);
        assertEquals(44.5, a.path("positions").get(1).path("quantity").asDouble(), 1e-10, a.toPrettyString());
        assertEquals(40, a.path("positions").get(2).path("quantity").asDouble(), 1e-10);
        assertEquals(2_090, a.path("portfolio_stop_risk_usdt").asDouble(), 1e-8);
        assertTrue(hasRejectedStage3(a, "SOL"));
        assertTrue(hasRejectedStage3(a, "SOL"));
        assertFalse(a.path("risk_netting_credit").asBoolean());
    }

    @Test
    void closedLifecycleReleasesRiskAndLaterPositionStartsWithFreshStages() {
        ObjectNode request = portfolioRequest();
        add(request, "BTC", 1, T, 100, 10_000, "LONG");
        request.withArray("events").addObject().put("type", "EXIT").put("asset", "BTC").put("time", T + 60_000)
                .put("price", 100).put("reason", "FIXTURE_EXIT");
        add(request, "BTC", 1, T + 3_600_000, 100, 10_000, "LONG");

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);

        assertEquals(20, result.path("positions").get(0).path("quantity").asDouble(), 1e-10);
        assertEquals(1, result.path("positions").get(0).path("fills").size());
        assertEquals(1, result.path("closed_episodes").size());
        assertEquals(200, result.path("portfolio_stop_risk_usdt").asDouble(), 1e-10);
    }

    @Test
    void freshStageOneIntentRetriesFlatSetupAndReplacesRejectedGeometry() {
        ObjectNode request = portfolioRequest();
        ObjectNode first = request.withArray("events").addObject().put("type", "ADD").put("asset", "BTC")
                .put("time", T).put("intent_id", "btc-setup-confirmation-a").put("setup_id", "btc-daily-setup")
                .put("decision_time", T).put("stage", 1).put("direction", "LONG").put("mode", "CONTINUATION")
                .put("common_stop", 90).putNull("recovery_target").put("price", 100).put("decision_mark", 100)
                .put("previous_completed_minute_base_volume", 1);
        ObjectNode second = request.withArray("events").addObject().put("type", "ADD").put("asset", "BTC")
                .put("time", T + 3_600_000).put("intent_id", "btc-setup-confirmation-b").put("setup_id", "btc-daily-setup")
                .put("decision_time", T + 3_600_000).put("stage", 1).put("direction", "LONG").put("mode", "CONTINUATION")
                .put("common_stop", 95).putNull("recovery_target").put("price", 100).put("decision_mark", 100)
                .put("previous_completed_minute_base_volume", 100_000);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);

        assertEquals("RISK_CAPACITY_COLLATERAL_OR_MINIMUM_NOTIONAL", result.path("events").get(0).path("reason").asText());
        assertEquals("ADD_FILLED", result.path("events").get(1).path("type").asText());
        assertEquals(95, result.path("positions").get(0).path("common_stop").asDouble(), 1e-10);
        assertEquals(40, result.path("positions").get(0).path("quantity").asDouble(), 1e-10);
        assertEquals(1, result.path("positions").get(0).path("fills").size());
        assertEquals(T + 3_600_000, result.path("positions").get(0).path("fills").get(0).path("time").asLong());
    }

    @Test
    void filledAndClosedSetupCannotBeReopenedByAnotherIntent() {
        ObjectNode request = portfolioRequest();
        addSetup(request, "immutable-btc-setup", "BTC", "LONG", 90, null, 1, T, 100);
        ((ObjectNode) request.path("events").get(0)).put("intent_id", "first-intent").put("decision_time", T);
        request.withArray("events").addObject().put("type", "EXIT").put("asset", "BTC").put("time", T + 60_000)
                .put("price", 100).put("reason", "FIXTURE_EXIT");
        addSetup(request, "immutable-btc-setup", "BTC", "LONG", 90, null, 1, T + 3_600_000, 100);
        ((ObjectNode) request.path("events").get(2)).put("intent_id", "second-intent").put("decision_time", T + 3_600_000);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);

        assertEquals("ADD_FILLED", result.path("events").get(0).path("type").asText());
        assertEquals("EXIT_FILLED", result.path("events").get(1).path("type").asText());
        assertEquals("STAGE_ONE_SETUP_ALREADY_FILLED", result.path("events").get(2).path("reason").asText());
        assertEquals(1, result.path("closed_episodes").size());
        assertEquals(0, result.path("positions").get(0).path("quantity").asDouble(), 1e-10);
    }

    @Test
    void fundingDebitsAvailableBalanceThenIsolatedMarginAndLiquidatesAtMaintenanceBoundary() {
        ObjectNode request = portfolioRequest();
        request.put("reserved_costs_usdt", 19_000);
        ObjectNode btc = (ObjectNode) request.path("positions").get(0);
        btc.put("liquidation_fee_rate", 0.001);
        btc.putArray("maintenance_tiers").addObject().put("effective_from", T).put("effective_until", T + 86_400_000)
                .put("tier_notional_cap", 100_000).put("maintenance_margin_rate", 0.05).put("maintenance_deduction", 0);
        add(request, "BTC", 1, T, 100, 10_000, "LONG");
        request.withArray("events").addObject().put("type", "FUNDING").put("asset", "BTC").put("time", T + 60_000)
                .put("event_id", "funding-shortfall").put("funding_rate", 0.45).put("mark_price", 100);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);

        ObjectNode position = (ObjectNode) result.path("positions").get(0);
        assertEquals(0, position.path("quantity").asDouble(), 1e-10);
        assertEquals("LIQUIDATION", position.path("exits").get(0).path("reason").asText());
        assertEquals(900, position.path("funding_events").get(0).path("isolated_margin_debit_usdt").asDouble(), 1e-8);
        assertEquals(20, position.path("exits").get(0).path("quantity").asDouble(), 1e-8);
        assertTrue(result.path("events").get(1).path("liquidated").asBoolean());
        assertEquals("UNKNOWN_PROXY_ONLY", result.path("events").get(1).path("historical_mechanics_qualification").asText());
        assertTrue(result.path("free_balance_usdt").asDouble() >= 0);
    }

    @Test
    void stopRatchetAppliesOnlyAtItsSourceCloseAndCannotLoosen() {
        ObjectNode request = portfolioRequest();
        add(request, "BTC", 1, T, 100, 10_000, "LONG");
        request.withArray("events").addObject().put("type", "MARK").put("asset", "BTC").put("time", T + 3_600_000).put("price", 110);
        request.withArray("events").addObject().put("type", "STOP_UPDATE").put("asset", "BTC").put("time", T + 3_600_000)
                .put("source_bar_close_time", T + 3_600_000).put("stop", 95);
        request.withArray("events").addObject().put("type", "STOP_UPDATE").put("asset", "BTC").put("time", T + 7_200_000)
                .put("source_bar_close_time", T + 7_200_000).put("stop", 92);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);

        assertEquals(95, result.path("positions").get(0).path("common_stop").asDouble(), 1e-10);
        assertEquals(100, result.path("portfolio_stop_risk_usdt").asDouble(), 1e-10);
        assertEquals("STOP_UPDATE_REJECTED", result.path("events").get(3).path("type").asText());
    }

    @Test
    void stopOrderModificationIsNotAppliedDuringVenueBlackout() {
        ObjectNode request = portfolioRequest();
        add(request, "BTC", 1, T, 100, 10_000, "LONG");
        request.withArray("events").addObject().put("type", "STOP_UPDATE").put("asset", "BTC").put("time", T + 60_000)
                .put("source_bar_close_time", T + 60_000).put("source_available_at", T + 60_000)
                .put("stop", 95).put("venue_execution_blocked", true);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);

        assertEquals("STOP_UPDATE_BLOCKED_VENUE_BLACKOUT", result.path("events").get(1).path("type").asText());
        assertEquals(90, result.path("positions").get(0).path("common_stop").asDouble(), 1e-10);
        assertTrue(result.path("events").get(1).path("venue_execution_blocked").asBoolean());
    }

    @Test
    void minuteOpenPhasePrecedesFillsAndStopWinsIntrabarTargetCollision() {
        ObjectNode request = portfolioRequest();
        ((ObjectNode) request.path("positions").get(0)).put("recovery_target", 120);
        add(request, "BTC", 1, T, 100, 10_000, "LONG");
        bar(request, "BAR_PRE", T + 60_000, 100, 100, 100, 100, 100, 100, 100, 100);
        bar(request, "BAR_POST", T + 120_000, 100, 130, 80, 110, 100, 105, 80, 101);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);

        assertEquals("STOP", result.path("positions").get(0).path("exits").get(0).path("reason").asText());
        assertEquals(90, result.path("positions").get(0).path("exits").get(0).path("price").asDouble(), 1e-10);
        assertEquals(19_800, result.path("mark_to_market_equity_usdt").asDouble(), 1e-8);
        assertEquals(0.02, result.path("maximum_event_path_drawdown_fraction").asDouble(), 1e-10);
        assertTrue(result.path("marked_drawdown_assumption").asText().contains("NOT_OBSERVED_SYNCHRONIZED_PATH"));
        assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-10);
    }

    @Test
    void stagedAdverseGapUsesFullFivePercentReferenceRiskEvenWithOnlyStageOneFilled() {
        ObjectNode request = portfolioRequest();
        addSetup(request, "staged-gap-setup", "BTC", "LONG", 90, null, 1, T, 100);
        ((ObjectNode) request.path("events").get(0)).put("full_position_reference_risk_usdt", 1_000);
        bar(request, "BAR_PRE", T + 60_000, 90, 91, 89, 90, 90, 91, 89, 90).put("adverse_gap_r", 0.25);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);
        ObjectNode exitEvent = (ObjectNode) result.path("events").get(1);
        ObjectNode position = (ObjectNode) result.path("positions").get(0);

        assertEquals(1_000, position.path("full_position_reference_risk_usdt").asDouble(), 1e-10);
        assertEquals(1_000, exitEvent.path("execution_gap_reference_risk_usdt").asDouble(), 1e-10);
        assertEquals(250, exitEvent.path("execution_gap_debit_usdt").asDouble(), 1e-10);
        assertEquals(-450, position.path("realized_gross_pnl_usdt").asDouble(), 1e-10);
        assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-8);
    }

    @Test
    void venueBlackoutLatchesStopThroughPriceRecoveryAndKeepsFundingExposureUntilResume() {
        ObjectNode request = portfolioRequest();
        add(request, "BTC", 1, T, 100, 10_000, "LONG");
        bar(request, "BAR_PRE", T + 60_000, 100, 102, 99, 101, 100, 102, 99, 101)
                .put("venue_execution_blocked", true);
        bar(request, "BAR_POST", T + 120_000, 100, 105, 89, 101, 100, 105, 89, 101)
                .put("venue_execution_blocked", true);
        funding(request, "blackout-funding", "BTC", T + 150_000, 0.001, 100);
        bar(request, "BAR_PRE", T + 180_000, 100, 102, 99, 101, 100, 102, 99, 101);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);
        ObjectNode position = (ObjectNode) result.path("positions").get(0);

        assertEquals(0, position.path("quantity").asDouble(), 1e-10);
        assertEquals(1, position.path("funding_events").size());
        assertEquals(19_998, result.path("free_balance_usdt").asDouble(), 1e-8);
        assertEquals("VENUE_OUTAGE_DEFERRED_STOP", position.path("exits").get(0).path("reason").asText());
        assertEquals(100, position.path("exits").get(0).path("price").asDouble(), 1e-10);
        assertEquals("STOP", position.path("exits").get(0).path("outage_trigger_reason").asText());
        assertEquals("STOP_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT",
                position.path("exits").get(0).path("outage_trigger_status").asText());
        assertEquals(T + 120_000, position.path("exits").get(0).path("outage_trigger_observed_time").asLong());
        assertEquals(T + 180_000, position.path("exits").get(0).path("outage_execution_resume_time").asLong());
        assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-8);
    }

    @Test
    void venueBlackoutLatchesLiquidationAheadOfStopAndExecutesItAtFirstResumeOpen() {
        ObjectNode request = portfolioRequest();
        ObjectNode btc = (ObjectNode) request.path("positions").get(0);
        btc.put("liquidation_fee_rate", 0.001);
        btc.putArray("maintenance_tiers").addObject().put("effective_from", T).put("effective_until", T + DAY)
                .put("tier_notional_cap", 100_000).put("maintenance_margin_rate", 0.05).put("maintenance_deduction", 0);
        add(request, "BTC", 1, T, 100, 10_000, "LONG");
        bar(request, "BAR_PRE", T + 60_000, 100, 102, 99, 101, 100, 102, 99, 101)
                .put("venue_execution_blocked", true);
        bar(request, "BAR_POST", T + 120_000, 100, 105, 80, 101, 100, 105, 50, 101)
                .put("venue_execution_blocked", true);
        funding(request, "blackout-maintenance-funding", "BTC", T + 150_000, 0.001, 100);
        bar(request, "BAR_PRE", T + 180_000, 100, 102, 99, 101, 100, 102, 99, 101);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);
        ObjectNode position = (ObjectNode) result.path("positions").get(0);

        assertEquals(0, position.path("quantity").asDouble(), 1e-10);
        assertEquals("LIQUIDATION", position.path("exits").get(0).path("reason").asText());
        assertEquals("LIQUIDATION", position.path("exits").get(0).path("outage_trigger_reason").asText());
        assertEquals("LIQUIDATION_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT",
                position.path("exits").get(0).path("outage_trigger_status").asText());
        assertEquals(0, position.path("exits").get(0).path("outage_trigger_priority").asInt());
        assertEquals(T + 180_000, position.path("exits").get(0).path("outage_execution_resume_time").asLong());
        assertEquals(1, position.path("funding_events").size());
        assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-8);
    }

    @Test
    void fundingTriggeredMaintenanceBreachDuringBlackoutLatchesUntilFirstOpenAfterResume() {
        ObjectNode request = portfolioRequest().put("reserved_costs_usdt", 19_000);
        ObjectNode btc = (ObjectNode) request.path("positions").get(0);
        btc.put("liquidation_fee_rate", 0.001);
        btc.putArray("maintenance_tiers").addObject().put("effective_from", T).put("effective_until", T + DAY)
                .put("tier_notional_cap", 100_000).put("maintenance_margin_rate", 0.05).put("maintenance_deduction", 0);
        add(request, "BTC", 1, T, 100, 10_000, "LONG");
        funding(request, "blackout-funding-liquidation", "BTC", T + 60_000, 0.45, 100);
        ((ObjectNode) request.path("events").get(request.path("events").size() - 1)).put("venue_execution_blocked", true);
        funding(request, "reopen-boundary-funding", "BTC", T + 120_000, 0.01, 100);
        bar(request, "BAR_PRE", T + 120_000, 99, 101, 98, 100, 99, 101, 98, 100);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);
        ObjectNode funding = (ObjectNode) result.path("events").get(1);
        ObjectNode boundaryFunding = (ObjectNode) result.path("events").get(2);
        ObjectNode position = (ObjectNode) result.path("positions").get(0);

        assertTrue(funding.path("venue_execution_blocked").asBoolean());
        assertTrue(funding.path("liquidation_trigger_latched_during_venue_blackout").asBoolean());
        assertEquals("LIQUIDATION", funding.path("latched_exit_reason").asText());
        assertFalse(funding.path("liquidated").asBoolean());
        assertTrue(boundaryFunding.path("liquidation_latch_preserved_until_open").asBoolean());
        assertEquals("LIQUIDATION_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT",
                boundaryFunding.path("outage_trigger_status").asText());
        assertFalse(boundaryFunding.path("liquidated").asBoolean());
        assertEquals(0, position.path("quantity").asDouble(), 1e-10);
        assertEquals("LIQUIDATION", position.path("exits").get(0).path("reason").asText());
        assertEquals(T + 60_000, position.path("exits").get(0).path("outage_trigger_observed_time").asLong());
        assertEquals(T + 120_000, position.path("exits").get(0).path("outage_execution_resume_time").asLong());
        assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-8);
    }

    @Test
    void reopeningLiquidationPreservesEarlierBlackoutStopTriggerDisclosure() {
        ObjectNode request = portfolioRequest();
        ObjectNode btc = (ObjectNode) request.path("positions").get(0);
        btc.put("common_stop", 99.99);
        btc.putArray("maintenance_tiers").addObject().put("effective_from", T).put("effective_until", T + DAY)
                .put("tier_notional_cap", 100_000).put("maintenance_margin_rate", 0.20).put("maintenance_deduction", 0);
        add(request, "BTC", 1, T, 100, 100_000, "LONG");
        bar(request, "BAR_PRE", T + 60_000, 100, 101, 99, 100, 100, 101, 99.5, 100)
                .put("venue_execution_blocked", true);
        bar(request, "BAR_POST", T + 120_000, 100, 101, 99, 100, 100, 101, 99.5, 100)
                .put("venue_execution_blocked", true);
        bar(request, "BAR_PRE", T + 180_000, 30, 31, 29, 30, 30, 31, 29, 30);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);
        ObjectNode position = (ObjectNode) result.path("positions").get(0);
        ObjectNode exit = (ObjectNode) position.path("exits").get(0);

        assertEquals("LIQUIDATION", exit.path("reason").asText(), "maintenance liquidation keeps priority at reopen");
        assertEquals("STOP", exit.path("outage_trigger_reason").asText(), "the earlier stop touch remains auditable");
        assertEquals("STOP_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT", exit.path("outage_trigger_status").asText());
        assertEquals(T + 120_000, exit.path("outage_trigger_observed_time").asLong());
        assertEquals(T + 180_000, exit.path("outage_execution_resume_time").asLong());
        assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-8);
    }

    @Test
    void venueBlackoutLatchesSixtyDayTimeoutUntilExecutionCanResume() {
        ObjectNode request = portfolioRequest();
        add(request, "BTC", 1, T, 100, 10_000, "LONG");
        long deadline = T + 60L * 86_400_000L;
        bar(request, "BAR_PRE", deadline + 60_000, 100, 102, 99, 101, 100, 102, 99, 101)
                .put("venue_execution_blocked", true);
        bar(request, "BAR_PRE", deadline + 3_660_000, 100, 102, 99, 101, 100, 102, 99, 101);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);
        ObjectNode exit = (ObjectNode) result.path("positions").get(0).path("exits").get(0);

        assertEquals("VENUE_OUTAGE_DEFERRED_SIXTY_DAY_LIFECYCLE", exit.path("reason").asText());
        assertEquals("SIXTY_DAY_LIFECYCLE", exit.path("outage_trigger_reason").asText());
        assertEquals(deadline + 60_000, exit.path("outage_trigger_observed_time").asLong());
        assertEquals(deadline + 3_660_000, exit.path("time").asLong());
        assertEquals(3_660_000, exit.path("outage_execution_resume_time").asLong() - deadline);
    }

    @Test
    void daySixtyExitUsesFirstPermittedOpenBeforeLaterIntraminuteTarget() {
        ObjectNode request = portfolioRequest();
        ((ObjectNode) request.path("positions").get(0)).put("recovery_target", 120);
        add(request, "BTC", 1, T, 100, 10_000, "LONG");
        long deadline = T + 60L * 86_400_000L;
        bar(request, "BAR_PRE", deadline, 100, 110, 99, 105, 100, 110, 99, 105);
        bar(request, "BAR_POST", deadline + 60_000, 100, 110, 99, 105, 100, 110, 99, 105);
        // No minute is present at deadline+60s; the first permitted open is two minutes late.
        bar(request, "BAR_PRE", deadline + 120_000, 104, 110, 103, 108, 104, 110, 103, 108);
        bar(request, "BAR_POST", deadline + 180_000, 104, 110, 103, 108, 104, 110, 103, 108);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);

        assertEquals("SIXTY_DAY_LIFECYCLE", result.path("positions").get(0).path("exits").get(0).path("reason").asText());
        assertEquals(104, result.path("positions").get(0).path("exits").get(0).path("price").asDouble(), 1e-10);
        assertEquals(deadline + 120_000, result.path("positions").get(0).path("exits").get(0).path("time").asLong());
        assertEquals(120_000, result.path("events").get(3).path("deadline_execution_overrun_ms").asLong());
    }

    @Test
    void fundingDeficitBooksLiabilityOnceAndLaterCreditRepaysItBeforeFreeCash() {
        ObjectNode request = portfolioRequest();
        ObjectNode btc = (ObjectNode) request.path("positions").get(0);
        btc.putArray("maintenance_tiers").addObject().put("effective_from", T).put("effective_until", T + 86_400_000)
                .put("tier_notional_cap", 100_000).put("maintenance_margin_rate", 0).put("maintenance_deduction", 0);
        ((ObjectNode) request.path("positions").get(1)).put("common_stop", 110).put("direction", "SHORT");
        add(request, "BTC", 1, T, 100, 10_000, "LONG"); add(request, "ETH", 1, T, 100, 10_000, "SHORT");
        funding(request, "large-btc-debit", "BTC", T + 60_000, 15, 100);
        funding(request, "eth-credit", "ETH", T + 120_000, 10, 100);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);

        assertEquals(11_000, result.path("events").get(2).path("account_unpaid_funding_liability_usdt").asDouble(), 1e-8);
        assertEquals(-10_000, result.path("events").get(2).path("portfolio_equity_usdt").asDouble(), 1e-8);
        assertEquals(10_000, result.path("mark_to_market_equity_usdt").asDouble(), 1e-8);
        assertEquals(0, result.path("unpaid_funding_liability_usdt").asDouble(), 1e-10);
        assertEquals(9_000, result.path("free_balance_usdt").asDouble(), 1e-8);
        for (var event : result.path("events")) assertEquals(0, event.path("reconciliation_delta_usdt").asDouble(), 1e-8);
    }

    @Test
    void maintenanceUsesSmallestApplicableNotionalTier() {
        ObjectNode request = portfolioRequest().put("reserved_costs_usdt", 19_950);
        ObjectNode btc = (ObjectNode) request.path("positions").get(0);
        btc.putArray("maintenance_tiers").addObject().put("effective_from", T).put("effective_until", T + 86_400_000)
                .put("tier_notional_cap", 5_000).put("maintenance_margin_rate", 0.5).put("maintenance_deduction", 0);
        ((ArrayNode) btc.path("maintenance_tiers")).addObject().put("effective_from", T).put("effective_until", T + 86_400_000)
                .put("tier_notional_cap", 10_000).put("maintenance_margin_rate", 0.1).put("maintenance_deduction", 0);
        add(request, "BTC", 1, T, 100, 100, "LONG");
        funding(request, "maintenance-tier-boundary", "BTC", T + 60_000, 0.3, 100);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);

        assertTrue(result.path("events").get(1).path("liquidated").asBoolean());
        assertEquals(0, result.path("positions").get(0).path("quantity").asDouble(), 1e-10);
        assertEquals(0, result.path("reconciliation_delta_usdt").asDouble(), 1e-10);
    }

    @Test
    void aNewOppositeDirectionEpisodeGetsFreshGeometryAndIndependentAccounting() {
        ObjectNode request = portfolioRequest();
        addSetup(request, "long-setup", "BTC", "LONG", 90, null, 1, T, 100);
        request.withArray("events").addObject().put("type", "EXIT").put("asset", "BTC").put("time", T + 60_000)
                .put("price", 100).put("reason", "FIXTURE_CLOSE");
        addSetup(request, "short-setup", "BTC", "SHORT", 110, 80.0, 1, T + 3_600_000, 100);

        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);

        assertEquals(1, result.path("closed_episodes").size());
        assertEquals("LONG", result.path("closed_episodes").get(0).path("direction").asText());
        assertEquals("SHORT", result.path("positions").get(0).path("direction").asText());
        assertEquals(110, result.path("positions").get(0).path("common_stop").asDouble(), 1e-10);
        assertEquals(80, result.path("positions").get(0).path("recovery_target").asDouble(), 1e-10);
        assertEquals(20, result.path("positions").get(0).path("quantity").asDouble(), 1e-10);
    }

    @Test
    void streamingAccountMatchesFixtureLedgerHashWithoutRetainingMinuteEventRows() {
        ObjectNode request = portfolioRequest();
        add(request, "BTC", 1, T, 100, 10_000, "LONG");
        funding(request, "stream-funding", "BTC", T + 60_000, 0.001, 100);
        mark(request, "BTC", T + 120_000, 101);
        ObjectNode retained = LiquidationPortfolioAccountingV1.replayFixture(request);
        java.util.List<ObjectNode> streamed = new java.util.ArrayList<>();
        LiquidationPortfolioAccountingV1.AccountSession session = LiquidationPortfolioAccountingV1.startStreamingSession(request, streamed::add);
        for (var row : request.path("events")) session.accept((ObjectNode) row);
        ObjectNode bounded = session.snapshot();

        assertEquals(retained.path("event_stream_sha256").asText(), bounded.path("event_stream_sha256").asText());
        assertEquals(retained.path("event_count").asInt(), bounded.path("event_count").asInt());
        assertTrue(bounded.path("events").isEmpty());
        assertEquals(1, bounded.path("marked_equity_curve").size());
        assertEquals(retained.path("positions").get(0).path("quantity").asDouble(),
                bounded.path("positions").get(0).path("quantity").asDouble(), 1e-10);
        assertEquals(3, streamed.size());
        ObjectNode immutableSnapshot = bounded.deepCopy();
        assertEquals(JsonHashes.canonicalSha256(bounded), JsonHashes.canonicalSha256(session.snapshot()));
        ObjectNode next = JsonHashes.mapper().createObjectNode().put("type", "MARK").put("asset", "BTC")
                .put("time", T + 86_400_000L).put("price", 102);
        session.accept(next);
        assertEquals(JsonHashes.canonicalSha256(immutableSnapshot), JsonHashes.canonicalSha256(bounded));
        assertEquals(1, bounded.path("marked_equity_curve").size());
        assertEquals(2, session.snapshot().path("marked_equity_curve").size());
    }

    @Test
    void publicReplayMayStartFlatWithoutInventedDirectionOrStopGeometry() {
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-public-replay-account/1")
                .put("initial_equity_usdt", 20_000).put("reserved_costs_usdt", 0);
        request.putArray("positions").addObject().put("asset", "BTC").put("direction", "UNCONFIGURED")
                .put("lot_size", 0.01).put("minimum_notional", 5).put("taker_fee_rate", 0)
                .put("slippage_rate", 0).put("liquidation_fee_rate", 0).put("initial_mark_price", 100);
        request.putArray("events");
        ObjectNode flat = LiquidationPortfolioAccountingV1.startStreamingSession(request, ignored -> {}).snapshot();
        assertTrue(flat.path("positions").get(0).path("common_stop").isNull());
        assertTrue(flat.path("positions").get(0).path("recovery_target").isNull());
        assertEquals("UNCONFIGURED", flat.path("positions").get(0).path("direction").asText());
    }

    private static ObjectNode portfolioRequest() {
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-shared-account-fixture/1")
                .put("initial_equity_usdt", 20_000).put("reserved_costs_usdt", 0);
        ArrayNode positions = request.putArray("positions");
        positions.add(position("BTC", "LONG", 90)); positions.add(position("ETH", "SHORT", 110)); positions.add(position("SOL", "LONG", 90));
        request.putArray("events");
        return request;
    }

    private static ObjectNode position(String asset, String direction, double stop) {
        return JsonHashes.mapper().createObjectNode().put("asset", asset).put("direction", direction).put("common_stop", stop)
                .putNull("recovery_target").put("lot_size", 0.01).put("minimum_notional", 5).put("taker_fee_rate", 0)
                .put("slippage_rate", 0).put("liquidation_fee_rate", 0).put("initial_mark_price", 100);
    }

    private static void add(ObjectNode request, String asset, int stage, long time, double price, double volume, String direction) {
        request.withArray("events").addObject().put("type", "ADD").put("asset", asset).put("time", time)
                .put("stage", stage).put("price", price).put("decision_mark", price)
                .put("previous_completed_minute_base_volume", volume).put("mode", "CONTINUATION")
                .put("direction", direction);
    }

    private static void addSetup(ObjectNode request, String setupId, String asset, String direction, double stop, Double target,
            int stage, long time, double price) {
        ObjectNode row = request.withArray("events").addObject().put("type", "ADD").put("asset", asset).put("time", time)
                .put("setup_id", setupId).put("stage", stage).put("direction", direction).put("common_stop", stop)
                .put("price", price).put("decision_mark", price).put("previous_completed_minute_base_volume", 10_000).put("mode", "CONTINUATION");
        if (target == null) row.putNull("recovery_target"); else row.put("recovery_target", target);
    }

    private static void mark(ObjectNode request, String asset, long time, double price) {
        request.withArray("events").addObject().put("type", "MARK").put("asset", asset).put("time", time).put("price", price);
    }

    private static ObjectNode bar(ObjectNode request, String type, long time, double tradeOpen, double tradeHigh, double tradeLow,
            double tradeClose, double markOpen, double markHigh, double markLow, double markClose) {
        long start = "BAR_PRE".equals(type) ? time : time - 60_000L;
        return request.withArray("events").addObject().put("type", type).put("asset", "BTC").put("time", time).put("bar_start_time", start)
                .put("trade_open", tradeOpen).put("trade_high", tradeHigh).put("trade_low", tradeLow).put("trade_close", tradeClose)
                .put("mark_open", markOpen).put("mark_high", markHigh).put("mark_low", markLow).put("mark_close", markClose);
    }

    private static void funding(ObjectNode request, String id, String asset, long time, double rate, double mark) {
        request.withArray("events").addObject().put("type", "FUNDING").put("asset", asset).put("time", time)
                .put("event_id", id).put("funding_rate", rate).put("mark_price", mark);
    }

    private static void reverse(ArrayNode array) {
        java.util.List<com.fasterxml.jackson.databind.JsonNode> copy = new java.util.ArrayList<>();
        array.forEach(item -> copy.add(item.deepCopy())); array.removeAll();
        for (int index = copy.size() - 1; index >= 0; index--) array.add(copy.get(index));
    }

    private static boolean hasRejectedStage3(ObjectNode ledger, String asset) {
        for (var event : ledger.path("events")) if (asset.equals(event.path("asset").asText())
                && event.path("type").asText().equals("ADD_REJECTED") && event.path("stage").asInt() == 3) return true;
        return false;
    }
}
