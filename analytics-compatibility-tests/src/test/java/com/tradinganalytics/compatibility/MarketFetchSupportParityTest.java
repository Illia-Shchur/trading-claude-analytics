package com.tradinganalytics.compatibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.marketdata.MarketFetchSupport;
import org.junit.jupiter.api.Test;

class MarketFetchSupportParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test
    void completedBoundaryAndWeeklyIndicatorsMatchFrozenContract() throws Exception {
        long week = 7L * 86_400_000L;
        ObjectNode input = JSON.createObjectNode();
        input.put("bar_ms", week);
        input.put("now", 201 * week + week / 2);
        input.put("spot", 204.0);
        ArrayNode candles = input.putArray("candles");
        for (int index = 0; index < 203; index++) {
            candles.addObject().put("t", index * week).put("date", "w" + index)
                    .put("close", 100.0 + index * 0.5);
        }
        JsonNode expected = CompatibilityFixtures.readJson(JSON, "market-fetch-support-v1.json");

        ObjectNode actual = JSON.createObjectNode();
        actual.set("completed", MarketFetchSupport.completedCandles(candles, week, input.path("now").asLong()));
        actual.set("weekly", MarketFetchSupport.weeklyBlock(candles, 204.0, input.path("now").asLong()));
        CompatibilityFixtures.assertWireEqual(expected, actual);
    }
}
