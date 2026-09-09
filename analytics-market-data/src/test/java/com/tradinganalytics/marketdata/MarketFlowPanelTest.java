package com.tradinganalytics.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

class MarketFlowPanelTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void emptyInputsRemainUnavailableAndDoNotCreateInterpretations() {
        var panel = MarketFlowPanel.build(JSON.createArrayNode(), JSON.createArrayNode(),
                JSON.createArrayNode(), JSON.createArrayNode(), 4, "test");
        assertThat(panel.path("schema").asText()).isEqualTo("market-flow/1");
        assertThat(panel.path("available").asBoolean()).isFalse();
        assertThat(panel.path("interpretation_candidates")).isEmpty();
        assertThat(panel.path("relationship_signs_24h").path("price").asText())
                .isEqualTo("flat_or_unavailable");
    }

    @Test
    void completedFlowsAndCandlesProduceRelationshipLabels() throws Exception {
        ArrayNode spot = (ArrayNode) JSON.readTree("""
                [
                  {"time":0,"buy_usd":100,"sell_usd":100,"close":100},
                  {"time":14400,"buy_usd":50,"sell_usd":150,"close":101},
                  {"time":28800,"buy_usd":50,"sell_usd":150,"close":102},
                  {"time":43200,"buy_usd":50,"sell_usd":150,"close":103},
                  {"time":57600,"buy_usd":50,"sell_usd":150,"close":104},
                  {"time":72000,"buy_usd":50,"sell_usd":150,"close":105},
                  {"time":86400,"buy_usd":50,"sell_usd":150,"close":106}
                ]
                """);
        ArrayNode futures = (ArrayNode) JSON.readTree("""
                [
                  {"time":0,"buy_usd":100,"sell_usd":100,"close":100},
                  {"time":14400,"buy_usd":150,"sell_usd":50,"close":101},
                  {"time":28800,"buy_usd":150,"sell_usd":50,"close":102},
                  {"time":43200,"buy_usd":150,"sell_usd":50,"close":103},
                  {"time":57600,"buy_usd":150,"sell_usd":50,"close":104},
                  {"time":72000,"buy_usd":150,"sell_usd":50,"close":105},
                  {"time":86400,"buy_usd":150,"sell_usd":50,"close":106}
                ]
                """);
        ArrayNode oi = (ArrayNode) JSON.readTree("""
                [{"time":0,"open":100,"high":100,"low":100,"close":100},{"time":86400,"open":110,"high":110,"low":110,"close":110}]
                """);
        ArrayNode funding = (ArrayNode) JSON.readTree("""
                [{"time":0,"open":0.001,"high":0.001,"low":0.001,"close":0.001},{"time":86400,"open":0.002,"high":0.002,"low":0.002,"close":0.002}]
                """);
        var panel = MarketFlowPanel.build(spot, futures, oi, funding, 4, "BTC");
        assertThat(panel.path("available").asBoolean()).isTrue();
        assertThat(panel.path("interpretation_candidates").toString()).contains("LEVERAGE_LED_RALLY");
        assertThat(panel.path("oi_weighted_funding").path("oi_weighted").asBoolean()).isTrue();
        assertThat(panel.path("as_of").asText()).isEqualTo("1970-01-02T00:00:00.000Z");
    }

    @Test
    void sampledCandlesDiscloseIncompleteSamplingAndCoverage() throws Exception {
        ArrayNode rows = (ArrayNode) JSON.readTree("""
                [{"time":0,"open":1,"high":2,"low":1,"close":2,"samples":4,"expected_samples":8,"sampling_complete":false,"min_contract_coverage":1,"expected_contracts":2,"contract_coverage_complete":false}]
                """);
        var summary = MarketFlowPanel.candleSummary(rows, 4, false);
        assertThat(summary.path("sampling_quality").path("incomplete_bars").asInt()).isEqualTo(1);
        assertThat(summary.path("ohlc_method").asText()).contains("sampled observations");
    }

    @Test
    void fundingDirectionPreservesJavascriptLeftFoldCancellationSign() {
        ArrayNode funding = JSON.createArrayNode();
        // The last six values left-fold to -5.55e-17 in JavaScript while a
        // compensated stream sum returns exactly zero.
        double[] closes = {1.0, -0.1, -0.2, 0.3, 0.0, 0.0, 0.0};
        for (int index = 0; index < closes.length; index++) funding.addObject()
                .put("time", index * 14_400_000L)
                .put("open", closes[index]).put("high", closes[index])
                .put("low", closes[index]).put("close", closes[index]);
        var output = MarketFlowPanel.build(JSON.createArrayNode(), JSON.createArrayNode(),
                JSON.createArrayNode(), funding, 4, "fixture");
        assertThat(output.path("oi_weighted_funding").path("direction_24h").asText())
                .isEqualTo("negative");
    }
}
