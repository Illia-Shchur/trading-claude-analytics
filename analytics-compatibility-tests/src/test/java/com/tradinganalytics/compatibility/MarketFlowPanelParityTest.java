package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.marketdata.MarketFlowPanel;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class MarketFlowPanelParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test
    void composedPanelMatchesFrozenWireContractForCompleteAndUnavailableInputs() throws Exception {
        JsonNode expected;
        try (InputStream stream = getClass().getResourceAsStream("/oracles/market-flow-panel-v1.json")) {
            assertThat(stream).isNotNull();
            expected = JSON.readTree(stream);
        }

        long start = java.time.Instant.parse("2026-08-20T00:00:00Z").toEpochMilli();
        ArrayNode spot = JSON.createArrayNode();
        ArrayNode futures = JSON.createArrayNode();
        ArrayNode openInterest = JSON.createArrayNode();
        ArrayNode funding = JSON.createArrayNode();
        for (int index = 0; index < 20; index++) {
            long time = start + index * 4L * 3_600_000L;
            spot.addObject().put("time", time).put("buy_usd", 90 + index)
                    .put("sell_usd", 105 + index).put("close", 100 + index);
            futures.addObject().put("time", time).put("buy_usd", 130 + index)
                    .put("sell_usd", 80 + index).put("close", 100 + index);
            openInterest.addObject().put("time", time).put("open", 1000 + index * 5)
                    .put("high", 1002 + index * 5).put("low", 998 + index * 5)
                    .put("close", 1000 + index * 5).put("samples", 8)
                    .put("expected_samples", 8).put("sampling_complete", true)
                    .put("min_contract_coverage", index == 4 ? 2 : 3).put("expected_contracts", 3)
                    .put("contract_coverage_complete", index != 4);
            funding.addObject().put("time", time).put("open", .00001 + index * .000001)
                    .put("high", .000012 + index * .000001).put("low", .000009 + index * .000001)
                    .put("close", .000011 + index * .000001);
        }

        ObjectNode actual = JSON.createObjectNode();
        actual.set("full", MarketFlowPanel.build(spot, futures, openInterest, funding, 4, "BTC top-3"));
        actual.set("unavailable", MarketFlowPanel.build(JSON.createArrayNode(), JSON.createArrayNode(),
                JSON.createArrayNode(), JSON.createArrayNode(), 4, "unknown"));
        assertThat(NodePrettyJson.write(actual)).isEqualTo(NodePrettyJson.write(expected));
    }
}
