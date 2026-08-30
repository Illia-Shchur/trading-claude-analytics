package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.array;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static org.assertj.core.api.Assertions.assertThat;

class LegacyStrategyResearchNodeOracleTest {
    @Test
    void programmaticApiCoversBothExportsFromCommandModule() {
        Set<String> methods = Arrays.stream(LegacyStrategyResearch.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertThat(methods).contains("v3Stress", "evaluateLocalV3");
    }

    @Test
    void v3StressRecomputesSpotAndDerivativeScenarios() {
        ObjectNode contract = LegacyResearchV3.makeAcceptanceContract();
        ArrayNode trades = array()
                .add(trade("historical", "2020-03-13T00:00:00Z", 0.8))
                .add(trade("current", "2026-01-01T00:00:00Z", 1.2));
        ObjectNode spot = LegacyStrategyResearch.v3Stress(trades, contract, false, "a".repeat(64));
        ObjectNode derivative = LegacyStrategyResearch.v3Stress(trades, contract, true, "b".repeat(64));
        assertThat(spot.path("schema").asText()).isEqualTo("strategy-stress-result/1");
        assertThat(spot.path("suite_sha256").asText()).matches("[0-9a-f]{64}");
        assertThat(spot.path("derivatives_required").asBoolean()).isFalse();
        assertThat(derivative.path("derivatives_required").asBoolean()).isTrue();
        assertThat(derivative.path("experiment_sha256").asText()).isEqualTo("b".repeat(64));
        assertThat(derivative.path("scenarios")).hasSameSizeAs(spot.path("scenarios"));
        assertThat(derivative.path("provenance").asText()).isEqualTo("AUTHORITATIVE_RECOMPUTED");
    }

    @Test
    void v3StressFailsClosedWhenInputsAreMissingOrCapacityFails() {
        ObjectNode contract = LegacyResearchV3.makeAcceptanceContract();
        ArrayNode trades = array()
                .add(object().put("trade_id", "missing").put("net_r", 0.5)
                        .put("entry_time", "2026-01-01T00:00:00Z")
                        .put("exit_time", "2026-01-02T00:00:00Z").put("venue", "public"))
                .add(trade("over-capacity", "2026-02-01T00:00:00Z", 0.2)
                        .put("notional", 600));
        ObjectNode result = LegacyStrategyResearch.v3Stress(trades, contract, true, null);
        assertThat(result.path("pass").asBoolean()).isFalse();
        assertThat(result.path("model_completeness").asBoolean()).isFalse();
        assertThat(result.path("scenarios").toString()).contains("missing", "over-capacity");
    }

    private static ObjectNode trade(String id, String time, double netR) {
        ObjectNode trade = object().put("trade_id", id).put("net_r", netR)
                .put("risk_dollars", 100).put("fee_r", 0.01)
                .put("slippage_r", 0.02).put("funding_debit_r", 0.03)
                .put("notional", 100).put("available_liquidity_notional", 10_000)
                .put("adverse_gap_r", 0.1).put("venue", "public")
                .put("entry_time", time).put("exit_time", time);
        ObjectNode settlement = object().put("amount", -3).put("event_id", "fund-" + id)
                .put("source", "fixture").put("venue", "public").put("instrument", "BTC-PERP");
        trade.set("funding_settlements", array().add(settlement));
        return trade;
    }
}
