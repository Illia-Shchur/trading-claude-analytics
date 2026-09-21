package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiquidationV2EvidenceMathEdgeCasesTest {
    @Test
    void binary64IntervalsAcceptOnlyOverlappingFinitePositiveValues() {
        double equity = new BigDecimal("73282.9075072922063823855").doubleValue();
        double risk = new BigDecimal("3664.145375364610319119275").doubleValue();

        assertTrue(LiquidationV2EvidenceMathV1.scaledRoundedBinary64IntervalsOverlap(
                equity, risk, new BigDecimal("0.05")));
        assertFalse(LiquidationV2EvidenceMathV1.scaledRoundedBinary64IntervalsOverlap(
                equity, Math.nextDown(risk), new BigDecimal("0.05")));
        assertTrue(LiquidationV2EvidenceMathV1.roundedBinary64IntervalsOverlap(risk, Math.nextUp(risk)));
        assertFalse(LiquidationV2EvidenceMathV1.roundedBinary64IntervalsOverlap(risk, risk * 1.01));
        assertFalse(LiquidationV2EvidenceMathV1.roundedBinary64IntervalsOverlap(0.0, 0.0));
        assertFalse(LiquidationV2EvidenceMathV1.roundedBinary64IntervalsOverlap(Double.POSITIVE_INFINITY, 1.0));
        assertFalse(LiquidationV2EvidenceMathV1.roundedBinary64IntervalsOverlap(Double.MAX_VALUE, Double.MAX_VALUE));
        assertFalse(LiquidationV2EvidenceMathV1.scaledRoundedBinary64IntervalsOverlap(equity, risk, null));
        assertFalse(LiquidationV2EvidenceMathV1.scaledRoundedBinary64IntervalsOverlap(equity, risk, BigDecimal.ZERO));
    }

    @Test
    void completedEpisodesConnectByDecisionPurgeOrOverlappingHoldingAndIgnoreUnresolvedRows() {
        Instant start = Instant.parse("2023-01-01T00:00:00Z");
        var first = interval("a", start, start.plusSeconds(60), start.plus(Duration.ofDays(2)));
        var decisionConnected = interval("b", start.plus(Duration.ofDays(10)),
                start.plus(Duration.ofDays(10)).plusSeconds(60), start.plus(Duration.ofDays(11)));
        var separate = interval("c", start.plus(Duration.ofDays(100)),
                start.plus(Duration.ofDays(100)).plusSeconds(60), start.plus(Duration.ofDays(205)));
        var holdingConnected = interval("d", start.plus(Duration.ofDays(200)),
                start.plus(Duration.ofDays(200)).plusSeconds(60), start.plus(Duration.ofDays(201)));
        var lastSeparate = interval("e", start.plus(Duration.ofDays(300)),
                start.plus(Duration.ofDays(300)).plusSeconds(60), start.plus(Duration.ofDays(301)));
        var unresolved = new LiquidationV2EvidenceMathV1.MarketInterval("open", "BTC", start.plus(Duration.ofDays(400)),
                start.plus(Duration.ofDays(400)).plusSeconds(60), null);

        assertEquals(3, LiquidationV2EvidenceMathV1.independentCompletedEpisodes(
                List.of(first, decisionConnected, separate, holdingConnected, lastSeparate, unresolved), 67));
        assertEquals(0, LiquidationV2EvidenceMathV1.independentCompletedEpisodes(List.of(unresolved), 67));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2EvidenceMathV1.independentCompletedEpisodes(List.of(first), 0));
    }

    @Test
    void intervalAndBlockValidationRejectMalformedChronologyAndDuplicateIdentity() {
        Instant time = Instant.parse("2023-01-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> interval("bad-fill", time,
                time.minusSeconds(1), time.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> interval("bad-exit", time,
                time.plusSeconds(1), time));

        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        Instant start = Instant.parse(profile.path("windows").path("decision_start").asText());
        Instant end = Instant.parse(profile.path("windows").path("decision_end_exclusive").asText());
        var valid = interval("duplicate", start, start.plusSeconds(60), start.plus(Duration.ofDays(1)));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2EvidenceMathV1.marketTimeBlocks(profile, List.of(valid, valid)));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2EvidenceMathV1.marketTimeBlocks(profile,
                List.of(interval("outside-start", start.minusNanos(1), null, null))));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2EvidenceMathV1.marketTimeBlocks(profile,
                List.of(interval("outside-end", end, null, null))));
    }

    @Test
    void synchronizedMeansKeepEmptyAndUnmappedDrawsAsZeroRatherThanDroppingDraws() {
        Instant start = Instant.parse("2023-01-01T00:00:00Z");
        var firstBlock = new LiquidationChronologicalPolicyV1.MarketTimeBlock("b1", start,
                start.plus(Duration.ofDays(67)), List.of("a"));
        var secondBlock = new LiquidationChronologicalPolicyV1.MarketTimeBlock("b2",
                start.plus(Duration.ofDays(67)), start.plus(Duration.ofDays(134)), List.of("missing"));
        var sample = new LiquidationChronologicalPolicyV1.SynchronizedBlockSample("profile", "blocks", 67,
                20260920L, 2, List.of(new LiquidationChronologicalPolicyV1.BlockDraw(0, List.of("b1", "unknown")),
                        new LiquidationChronologicalPolicyV1.BlockDraw(1, List.of("b2"))));

        assertEquals(List.of(2.0, 0.0), LiquidationV2EvidenceMathV1.synchronizedBlockMeans(sample,
                List.of(firstBlock, secondBlock), Map.of("a", 2.0)));
        assertEquals(0.0, LiquidationV2EvidenceMathV1.mean(List.of()));
        assertEquals(0.0, LiquidationV2EvidenceMathV1.percentile20(List.of()));
        assertEquals(1.0, LiquidationV2EvidenceMathV1.percentile20(List.of(1.0, 2.0, 3.0, 4.0, 5.0)));
        assertEquals(1.0, LiquidationV2EvidenceMathV1.adjustedPValue(List.of(), 1.0));
        assertEquals(0.75, LiquidationV2EvidenceMathV1.adjustedPValue(List.of(0.0, 2.0, 3.0), 2.0));
        assertTrue(LiquidationV2EvidenceMathV1.zeroVariance(List.of()));
        assertTrue(LiquidationV2EvidenceMathV1.zeroVariance(List.of(1.0, 1.0, 1.0)));
        assertFalse(LiquidationV2EvidenceMathV1.zeroVariance(List.of(1.0, 2.0)));
    }

    @Test
    void paidPositionCostsRequireEveryFiniteNonnegativeComponent() {
        assertNull(LiquidationV2EvidenceMathV1.positionCostR(null, BigDecimal.TEN));
        assertNull(LiquidationV2EvidenceMathV1.positionCostR(costs(1.0, 1.0, 1.0), BigDecimal.ZERO));
        assertNull(LiquidationV2EvidenceMathV1.positionCostR(costs(-1.0, 1.0, 1.0), BigDecimal.TEN));
        assertNull(LiquidationV2EvidenceMathV1.positionCostR(costs(1.0, Double.NaN, 1.0), BigDecimal.TEN));
        assertNull(LiquidationV2EvidenceMathV1.positionCostR(costs(1.0, 1.0, Double.POSITIVE_INFINITY), BigDecimal.TEN));
        assertNull(LiquidationV2EvidenceMathV1.positionCostR(costs(1.0, 1.0, null), BigDecimal.TEN));
        assertEquals(0.3, LiquidationV2EvidenceMathV1.positionCostR(costs(1.0, 1.0, 1.0), BigDecimal.TEN), 1e-12);
    }

    private static LiquidationV2EvidenceMathV1.MarketInterval interval(String id,
            Instant decision, Instant fill, Instant exit) {
        return new LiquidationV2EvidenceMathV1.MarketInterval(id, "BTC", decision, fill, exit);
    }

    private static ObjectNode costs(double entry, double exit, Double fundingDebit) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("entry_costs_usdt", entry)
                .put("exit_costs_usdt", exit);
        if (fundingDebit == null) row.putNull("funding_debits_for_risk_headroom_usdt");
        else if (fundingDebit.isNaN() || fundingDebit.isInfinite()) row.set("funding_debits_for_risk_headroom_usdt", DoubleNode.valueOf(fundingDebit));
        else row.put("funding_debits_for_risk_headroom_usdt", fundingDebit);
        return row;
    }
}
