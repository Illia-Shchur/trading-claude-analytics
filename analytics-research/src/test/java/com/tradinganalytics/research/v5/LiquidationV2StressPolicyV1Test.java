package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LiquidationV2StressPolicyV1Test {
    @Test
    void reopensOnlyTheExactFrozenFiveScenarioPolicyAndHashesItDeterministically() throws Exception {
        ObjectNode frozen = frozenPrecommit();
        LiquidationV2StressPolicyV1.Policy first = LiquidationV2StressPolicyV1.reopen(frozen);
        LiquidationV2StressPolicyV1.Policy fromPath = LiquidationV2StressPolicyV1.reopen(frozenPrecommitPath());
        LiquidationV2StressPolicyV1.Policy second = LiquidationV2StressPolicyV1.reopen(frozen.deepCopy());

        assertEquals(LiquidationV2StressPolicyV1.REQUIRED_SCENARIO_COUNT, first.scenarios().size());
        assertEquals("POLICY_ONLY_NOT_RUN", first.status());
        assertEquals(LiquidationV2StressPolicyV1.FROZEN_PRECOMMIT_SHA256, first.precommitSha256());
        assertEquals(2.0, first.feeSlippage().costMultiplier());
        assertEquals(2.0, first.fundingCarry().debitMultiplier());
        assertEquals(1.0, first.fundingCarry().creditMultiplier());
        assertEquals(0.25, first.adverseExecutionGap().debitR());
        assertEquals(0.01, first.liquidityCapacity().maximumParticipationRate());
        assertEquals(3600, first.venueOutage().intervalAfterEvent().toSeconds());
        assertEquals(first.contentSha256(), second.contentSha256());
        assertEquals(first.contentSha256(), fromPath.contentSha256());
        assertEquals(first.toJson(), second.toJson());
        assertTrue(first.validate());
        assertEquals(first.contentSha256(), JsonHashes.ownHash(first.toJson()));
        assertFalse(first.toJson().path("outcome_rows_emitted").asBoolean());
    }

    @Test
    void tamperedFrozenScenarioValuesCannotBeReopenedEvenWithARecomputedCallerHash() throws Exception {
        ObjectNode changedParameter = frozenPrecommit();
        ((ObjectNode) changedParameter.path("experiment").path("acceptance").path("stress")
                .path("required_scenarios").get(0)).put("multiplier", 3);
        changedParameter.put("content_sha256", JsonHashes.ownHash(changedParameter));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2StressPolicyV1.reopen(changedParameter));

        ObjectNode changedInventory = frozenPrecommit();
        ((ObjectNode) changedInventory.path("experiment").path("acceptance").path("stress")
                .path("required_scenarios").get(4)).put("id", "venue_outage");
        changedInventory.put("content_sha256", JsonHashes.ownHash(changedInventory));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2StressPolicyV1.reopen(changedInventory));
    }

    @Test
    void pathReopenRejectsMissingMalformedAndNonObjectInputs() throws Exception {
        Path directory = Files.createTempDirectory("stress-policy-invalid-");
        Path missing = directory.resolve("missing.json");
        Path malformed = directory.resolve("malformed.json");
        Path array = directory.resolve("array.json");
        try {
            Files.writeString(malformed, "{");
            Files.writeString(array, "[]");
            assertThrows(IllegalArgumentException.class, () -> LiquidationV2StressPolicyV1.reopen(missing));
            assertThrows(IllegalArgumentException.class, () -> LiquidationV2StressPolicyV1.reopen(malformed));
            assertThrows(IllegalArgumentException.class, () -> LiquidationV2StressPolicyV1.reopen(array));
        } finally {
            Files.deleteIfExists(missing);
            Files.deleteIfExists(malformed);
            Files.deleteIfExists(array);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void signedFundingDoublesOnlyDebitsForBothRateSignsAndSides() throws Exception {
        LiquidationV2StressPolicyV1.Policy policy = LiquidationV2StressPolicyV1.reopen(frozenPrecommit());
        var longPositive = policy.stressFunding(20, 100, 0.001, LiquidationV2StressPolicyV1.PositionSide.LONG).toJson();
        var shortPositive = policy.stressFunding(20, 100, 0.001, LiquidationV2StressPolicyV1.PositionSide.SHORT).toJson();
        var longNegative = policy.stressFunding(20, 100, -0.001, LiquidationV2StressPolicyV1.PositionSide.LONG).toJson();
        var shortNegative = policy.stressFunding(20, 100, -0.001, LiquidationV2StressPolicyV1.PositionSide.SHORT).toJson();

        assertEquals(2.0, longPositive.path("source_signed_cost_usdt").asDouble());
        assertEquals(4.0, longPositive.path("stressed_signed_cost_usdt").asDouble());
        assertEquals(-2.0, shortPositive.path("source_signed_cost_usdt").asDouble());
        assertEquals(-2.0, shortPositive.path("stressed_signed_cost_usdt").asDouble());
        assertEquals(-2.0, longNegative.path("source_signed_cost_usdt").asDouble());
        assertEquals(-2.0, longNegative.path("stressed_signed_cost_usdt").asDouble());
        assertEquals(2.0, shortNegative.path("source_signed_cost_usdt").asDouble());
        assertEquals(4.0, shortNegative.path("stressed_signed_cost_usdt").asDouble());
        assertFalse(shortPositive.path("is_debit").asBoolean());
        assertTrue(longPositive.path("is_debit").asBoolean());
    }

    @Test
    void componentCostGapAndCapacityTransformsAreDeterministicAndSelfHashed() throws Exception {
        LiquidationV2StressPolicyV1.Policy policy = LiquidationV2StressPolicyV1.reopen(frozenPrecommit());
        assertHashed(policy.scaleTradingCosts(1, 3), policy.scaleTradingCosts(1, 3));
        var cost = policy.scaleTradingCosts(1, 3).toJson();
        assertEquals(2.0, cost.path("stressed_fee_cost_usdt").asDouble());
        assertEquals(6.0, cost.path("stressed_slippage_cost_usdt").asDouble());
        assertHashed(policy.scaleTradingCostRates(.0004, .0006), policy.scaleTradingCostRates(.0004, .0006));
        var rates = policy.scaleTradingCostRates(.0004, .0006).toJson();
        assertEquals("DECIMAL_RATE", rates.path("fee_rate_unit").asText());
        assertEquals("DECIMAL_RATE", rates.path("slippage_rate_unit").asText());
        assertEquals(.0008, rates.path("stressed_fee_rate").asDouble(), 1e-12);
        assertEquals(.0012, rates.path("stressed_slippage_rate").asDouble(), 1e-12);

        assertHashed(policy.adverseGapDebit(200), policy.adverseGapDebit(200));
        assertHashed(policy.adverseGapDebit(1000), policy.adverseGapDebit(1000));
        assertEquals(50.0, policy.adverseGapDebit(200).toJson().path("position_gap_debit_usdt").asDouble());
        assertEquals(250.0, policy.adverseGapDebit(1000).toJson().path("position_gap_debit_usdt").asDouble());
        assertEquals("ONCE_PER_POSITION", policy.adverseGapDebit(1000).toJson().path("application_scope").asText());

        assertHashed(policy.capacityLimit(1234), policy.capacityLimit(1234));
        var capacity = policy.capacityLimit(1234).toJson();
        assertEquals(12.34, capacity.path("maximum_base_quantity").asDouble(), 1e-12);
        assertEquals("LAST_COMPLETED_MINUTE_BASE_VOLUME", policy.toJson().path("scenarios").get(3)
                .path("volume_source").asText());
        assertThrows(IllegalArgumentException.class, () -> policy.scaleTradingCosts(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> policy.scaleTradingCosts(0, -1));
        assertThrows(IllegalArgumentException.class, () -> policy.scaleTradingCosts(Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> policy.scaleTradingCostRates(-.0001, 0));
        assertThrows(IllegalArgumentException.class, () -> policy.scaleTradingCostRates(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> policy.capacityLimit(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> policy.capacityLimit(-1));
        assertThrows(IllegalArgumentException.class, () -> policy.adverseGapDebit(0));
        assertThrows(IllegalArgumentException.class, () -> policy.adverseGapDebit(Double.POSITIVE_INFINITY));
    }

    @Test
    void invalidFundingAndOutageInputsFailClosed() throws Exception {
        LiquidationV2StressPolicyV1.Policy policy = LiquidationV2StressPolicyV1.reopen(frozenPrecommit());
        assertThrows(IllegalArgumentException.class, () -> policy.stressFunding(0, 100, .001,
                LiquidationV2StressPolicyV1.PositionSide.LONG));
        assertThrows(IllegalArgumentException.class, () -> policy.stressFunding(20, 0, .001,
                LiquidationV2StressPolicyV1.PositionSide.LONG));
        assertThrows(IllegalArgumentException.class, () -> policy.stressFunding(20, 100, Double.NaN,
                LiquidationV2StressPolicyV1.PositionSide.LONG));
        assertThrows(NullPointerException.class, () -> policy.stressFunding(20, 100, .001, null));
        Instant event = Instant.parse("2024-02-01T12:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> policy.outageDecision(" ", event, event));
        assertThrows(NullPointerException.class, () -> policy.outageDecision("event", null, event));
        assertThrows(NullPointerException.class, () -> policy.outageDecision("event", event, null));
    }

    @Test
    void oneHourOutageIsActiveAtTheEventAndUntilTheExclusiveBoundary() throws Exception {
        LiquidationV2StressPolicyV1.Policy policy = LiquidationV2StressPolicyV1.reopen(frozenPrecommit());
        Instant event = Instant.parse("2024-02-01T12:00:00Z");
        Instant end = event.plus(policy.venueOutage().intervalAfterEvent());
        var before = policy.outageDecision("event-1", event, event.minusNanos(1));
        var atStart = policy.outageDecision("event-1", event, event);
        var beforeEnd = policy.outageDecision("event-1", event, end.minusNanos(1));
        var atEnd = policy.outageDecision("event-1", event, end);

        assertFalse(before.toJson().path("blocked").asBoolean());
        assertTrue(atStart.toJson().path("blocked").asBoolean());
        assertTrue(beforeEnd.toJson().path("blocked").asBoolean());
        assertFalse(atEnd.toJson().path("blocked").asBoolean());
        assertTrue(atStart.toJson().path("economics_retained_during_outage").asBoolean());
        assertHashed(atStart, policy.outageDecision("event-1", event, event));
    }

    private static void assertHashed(LiquidationV2StressPolicyV1.Transform first,
            LiquidationV2StressPolicyV1.Transform second) {
        assertTrue(first.validate());
        assertTrue(second.validate());
        assertEquals(first.contentSha256(), second.contentSha256());
        assertEquals(first.toJson(), second.toJson());
        assertFalse(first.toJson().path("outcome_row_emitted").asBoolean(true));
    }

    private static ObjectNode frozenPrecommit() throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readString(frozenPrecommitPath()));
    }

    private static Path frozenPrecommitPath() {
        return repositoryRoot().resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json");
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("repository root is unavailable to stress policy test");
        return cursor;
    }
}
