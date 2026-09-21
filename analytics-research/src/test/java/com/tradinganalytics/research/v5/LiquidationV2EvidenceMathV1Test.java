package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiquidationV2EvidenceMathV1Test {
    @Test
    void cumulativeTradeDrawdownUsesTheOrderedNetRPathAndZerosDoNotDiluteIt() {
        assertEquals(-4.0, LiquidationV2EvidenceMathV1.cumulativeTradeDrawdownR(List.of(1.0, -2.0, 3.0, -4.0)));
        assertEquals(-4.0, LiquidationV2EvidenceMathV1.cumulativeTradeDrawdownR(
                List.of(1.0, -2.0, 3.0, -4.0, 0.0, 0.0, 0.0, 0.0)));
    }

    @Test
    void headlineRUsesEachFrozenDenominatorRatherThanACommonBudget() {
        ObjectNode core = JsonHashes.mapper().createObjectNode().put("outcome_state", "CLOSED_TRADE")
                .put("net_pnl_usdt", 200).put("reference_risk_usdt", 200)
                .put("full_position_reference_risk_usdt", 1000);
        ObjectNode staged = JsonHashes.mapper().createObjectNode().put("outcome_state", "CLOSED_TRADE")
                .put("net_pnl_usdt", 1000).put("reference_risk_usdt", 1000)
                .put("full_position_reference_risk_usdt", 1000);

        assertEquals(1.0, LiquidationV2StagedStatisticsV1.normalizedR(core, true));
        assertEquals(1.0, LiquidationV2StagedStatisticsV1.normalizedR(staged, false));
        assertEquals(0.0, LiquidationV2StagedStatisticsV1.normalizedR(staged, false)
                - LiquidationV2StagedStatisticsV1.normalizedR(core, true));
    }

    @Test
    void perPositionCostRIsTradeCountWeightedAndFundingCreditsDoNotCancelDebits() {
        ObjectNode first = positionCosts("10", "20", "10");
        first.put("funding_pnl_usdt", 1000); // separate credit attribution does not reduce paid debit cost
        ObjectNode second = positionCosts("20", "40", "30");
        Double firstR = LiquidationV2EvidenceMathV1.positionCostR(first, new BigDecimal("400"));
        Double secondR = LiquidationV2EvidenceMathV1.positionCostR(second, new BigDecimal("300"));

        assertEquals(0.1, firstR);
        assertEquals(0.3, secondR);
        assertEquals(0.2, LiquidationV2EvidenceMathV1.meanPositionCostR(List.of(firstR, secondR)), 1e-12);
        assertNull(LiquidationV2EvidenceMathV1.positionCostR(positionCosts("1", "1", null), new BigDecimal("100")));
    }

    @Test
    void eitherArmsLongerHoldingMergesMarketTimeBoundaries() {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        Instant start = Instant.parse(profile.path("windows").path("decision_start").asText());
        Instant boundary = start.plus(Duration.ofDays(67));
        Instant decision = boundary.minus(Duration.ofDays(4));
        // The later-exiting predecessor arm defines the union holding interval even if the staged arm is zero.
        List<LiquidationV2EvidenceMathV1.MarketInterval> paired = List.of(
                new LiquidationV2EvidenceMathV1.MarketInterval("pair-1", "BTC", decision,
                        decision.plusSeconds(60), boundary.plus(Duration.ofDays(1))));

        var blocks = LiquidationV2EvidenceMathV1.marketTimeBlocks(profile, paired);
        long nominal = Duration.between(start, Instant.parse(profile.path("windows").path("decision_end_exclusive").asText()))
                .toDays() / 67;
        assertNotNull(blocks);
        org.junit.jupiter.api.Assertions.assertTrue(blocks.size() < nominal,
                "the boundary crossed by either arm's held position must merge the synchronized blocks");
        assertEquals(List.of("pair-1"), blocks.get(0).synchronizedObservationIds());
    }

    private static ObjectNode positionCosts(String entry, String exit, String debits) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("entry_costs_usdt", new BigDecimal(entry))
                .put("exit_costs_usdt", new BigDecimal(exit));
        if (debits == null) row.putNull("funding_debits_for_risk_headroom_usdt");
        else row.put("funding_debits_for_risk_headroom_usdt", new BigDecimal(debits));
        return row;
    }
}
