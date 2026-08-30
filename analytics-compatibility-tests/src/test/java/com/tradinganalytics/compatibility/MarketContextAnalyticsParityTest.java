package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.marketdata.MarketContextAnalytics;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketContextAnalyticsParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test
    void contextPanelsMatchFrozenWireOutput() throws Exception {
        JsonNode expected;
        try (InputStream stream = getClass().getResourceAsStream("/oracles/market-context-v1.json")) {
            assertThat(stream).isNotNull(); expected = JSON.readTree(stream);
        }

        ArrayNode onchain = JSON.createArrayNode();
        for (int index = 0; index < 31; index++) {
            onchain.addObject().put("time", "2026-07-" + String.format("%02d", index + 1))
                    .put("CapMVRVCur", 1.5 + index / 100.0).put("CapMrktCurUSD", 1000 + index * 10)
                    .put("FlowInExUSD", 20 + index).put("FlowOutExUSD", 10 + index)
                    .put("SplyExNtv", 100 - index).put("SplyCur", 1000);
        }
        ArrayNode cb = (ArrayNode) JSON.readTree("[{\"date\":\"2026-01-01\",\"close\":99},{\"date\":\"2026-01-02\",\"close\":98},{\"date\":\"2026-01-03\",\"close\":97}]");
        ArrayNode bn = (ArrayNode) JSON.readTree("[{\"date\":\"2026-01-01\",\"close\":100},{\"date\":\"2026-01-02\",\"close\":100},{\"date\":\"2026-01-03\",\"close\":100}]");
        ArrayNode usdt = (ArrayNode) JSON.readTree("[{\"date\":\"2026-01-01\",\"close\":1},{\"date\":\"2026-01-02\",\"close\":1},{\"date\":\"2026-01-03\",\"close\":1}]");
        ArrayNode oi = JSON.createArrayNode();
        for (int index = 0; index < 80; index++) {
            String date = LocalDate.of(2026, 1, 1).plusDays(index).toString();
            oi.addObject().put("date", date).put("sum_open_interest_value", 100 + index);
            oi.addObject().put("date", date).put("sum_open_interest_value", 90 + index);
        }
        ArrayNode breadth = (ArrayNode) JSON.readTree("[{\"close\":11,\"sma200\":10},{\"close\":9,\"sma200\":10},{\"close\":10,\"sma200\":10}]");
        List<Double> vol = List.of(10d, 11d, 12d, 13d, 14d);
        List<Double> cef = new ArrayList<>();
        List<Double> reference = new ArrayList<>();
        for (int index = 0; index < 12; index++) { cef.add(100.0 + index); reference.add(100.0); }

        ObjectNode actual = JSON.createObjectNode();
        actual.set("onchain", MarketContextAnalytics.onchainDistributionBlock(onchain));
        actual.set("onchain_empty", MarketContextAnalytics.onchainDistributionBlock(JSON.createArrayNode()));
        actual.set("premium", MarketContextAnalytics.coinbasePremiumBlock(cb, bn, usdt));
        actual.set("premium_empty", MarketContextAnalytics.coinbasePremiumBlock(
                JSON.createArrayNode(), JSON.createArrayNode(), JSON.createArrayNode()));
        actual.set("oi", MarketContextAnalytics.oi90dBlock(oi));
        actual.set("oi_empty", MarketContextAnalytics.oi90dBlock(JSON.createArrayNode()));
        actual.set("breadth", MarketContextAnalytics.breadth200Block(breadth, 3.0, "2026-01-01", 95));
        actual.set("breadth_low", MarketContextAnalytics.breadth200Block(breadth, 10.0, "2026-01-01", 95));
        actual.set("sentiment", MarketContextAnalytics.sentimentProxyBlock(vol, cef, reference, 5, 10));
        assertThat(NodePrettyJson.write(actual)).isEqualTo(NodePrettyJson.write(expected));
    }
}
