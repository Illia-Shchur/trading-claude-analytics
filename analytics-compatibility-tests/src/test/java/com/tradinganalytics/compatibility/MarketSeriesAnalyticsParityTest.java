package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.marketdata.MarketSeriesAnalytics;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketSeriesAnalyticsParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test
    void completedBarSeriesAndSpotPanelMatchNode() throws Exception {
        JsonNode expected;
        try (InputStream stream = getClass().getResourceAsStream("/oracles/market-series-analytics-v1.json")) {
            assertThat(stream).isNotNull(); expected = JSON.readTree(stream);
        }
        ArrayNode quotes = (ArrayNode) JSON.readTree("""
                [
                  {"source":"primary","value":100,"ts":1799999700000,"ts_kind":"venue"},
                  {"source":"second","value":101,"ts":1799999400000,"ts_kind":"receipt"},
                  {"source":"stale-close","value":100.2,"ts":1799989200000,"ts_kind":"venue"},
                  {"source":"daily","value":90,"ts":1799913600000,"ts_kind":"bar_close"}
                ]
                """);
        List<Double> closes = List.of(10d, 12d, 11d, 15d, 14d, 16d, 17d, 16d, 19d, 18d,
                20d, 22d, 21d, 24d, 23d, 25d, 27d, 26d, 30d, 29d);
        ObjectNode actual = JSON.createObjectNode();
        actual.set("spot", MarketSeriesAnalytics.spotPanel(quotes, 1_800_000_000_000L, 120, 0.5));
        actual.set("empty", MarketSeriesAnalytics.spotPanel(JSON.createArrayNode(), 0L, 120, 0.5));
        ArrayNode pct = actual.putArray("pct");
        addNullable(pct, MarketSeriesAnalytics.percentChange(closes, 2));
        addNullable(pct, MarketSeriesAnalytics.percentChange(List.of(0d, 1d), 1));
        addNullable(pct, MarketSeriesAnalytics.percentChange(closes, 0));
        ArrayNode runs = actual.putArray("runs");
        runs.add(MarketSeriesAnalytics.consecutiveRun(List.of(1, 2, 3, 4), value -> value >= 3, true));
        runs.add(MarketSeriesAnalytics.consecutiveRun(List.of(1, 2, 3, 4), value -> value <= 2, false));
        addNullable(actual, "slope", MarketSeriesAnalytics.smaSlope(closes, 5, 3));
        actual.set("rv", JSON.valueToTree(MarketSeriesAnalytics.rollingRealizedVol(closes, 5, 365)));
        actual.set("rsi", JSON.valueToTree(MarketSeriesAnalytics.rollingWilderRsi(closes, 5)));
        actual.set("dd", JSON.valueToTree(MarketSeriesAnalytics.rollingDrawdownFromAth(closes)));
        actual.set("sma", JSON.valueToTree(MarketSeriesAnalytics.rollingSmaDistance(closes, 5)));
        actual.set("bounce", JSON.valueToTree(MarketSeriesAnalytics.rollingBouncePercent(closes, 5)));
        actual.set("high", JSON.valueToTree(MarketSeriesAnalytics.rollingTrailingHighDistance(closes, 5)));
        // Jackson distinguishes IntNode(1) from DoubleNode(1.0), while
        // ECMAScript JSON has one number type. Compare the actual wire form.
        assertThat(NodePrettyJson.write(actual)).isEqualTo(NodePrettyJson.write(expected));
    }

    private static void addNullable(ArrayNode target, Double value) {
        if (value == null) target.addNull(); else target.add(value);
    }

    private static void addNullable(ObjectNode target, String field, Double value) {
        if (value == null) target.putNull(field); else target.put(field, value);
    }
}
