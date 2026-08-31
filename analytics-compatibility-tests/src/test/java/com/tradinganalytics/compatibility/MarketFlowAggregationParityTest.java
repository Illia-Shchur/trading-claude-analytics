package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.marketdata.MarketFlowAggregation;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class MarketFlowAggregationParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test
    void aggregationAndResamplingMatchFrozenWireContract() throws Exception {
        JsonNode expected;
        try (InputStream stream = getClass().getResourceAsStream("/oracles/market-flow-aggregation-v1.json")) {
            assertThat(stream).isNotNull();
            expected = JSON.readTree(stream);
        }
        ArrayNode flows = (ArrayNode) JSON.readTree("""
                [
                  {"symbol":"BTCUSDT","rows":[{"time":1000,"buy_usd":100,"sell_usd":50,"close":10},{"time":"bad","buy_usd":1,"sell_usd":1}]},
                  {"symbol":"ETHUSDT","rows":[{"time":1000,"buy_usd":25,"sell_usd":75,"close":20}]}
                ]
                """);
        ArrayNode values = (ArrayNode) JSON.readTree("[{\"symbol\":\"BTC\",\"rows\":[{\"time\":1801,\"value\":10}]},{\"symbol\":\"ETH\",\"rows\":[{\"time\":1802,\"value\":20}]}]");
        ArrayNode funding = (ArrayNode) JSON.readTree("[{\"symbol\":\"BTC\",\"rows\":[{\"time\":1000,\"rate\":0.01}]},{\"symbol\":\"ETH\",\"rows\":[{\"time\":1000,\"rate\":-0.01}]}]");
        ArrayNode samples = (ArrayNode) JSON.readTree("[{\"time\":0,\"value\":10,\"coverage_count\":2,\"expected_count\":2},{\"time\":1800,\"value\":12,\"coverage_count\":2,\"expected_count\":2},{\"time\":3600,\"value\":11,\"coverage_count\":1,\"expected_count\":2},{\"time\":7200,\"value\":99,\"coverage_count\":2,\"expected_count\":2}]");
        ObjectNode actual = JSON.createObjectNode();
        actual.set("flow", MarketFlowAggregation.aggregateFlowRows(flows));
        actual.set("values", MarketFlowAggregation.aggregateValueSnapshots(values, 1_800_000));
        actual.set("weighted", MarketFlowAggregation.oiWeightedFundingSnapshots(values, funding, 1_800_000));
        actual.set("candles", MarketFlowAggregation.resampleSnapshotsToCandles(samples, 1, 30, 10, 7_200_000));
        assertThat(NodePrettyJson.write(actual)).isEqualTo(NodePrettyJson.write(expected));
    }
}
