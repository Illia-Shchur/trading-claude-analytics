package com.tradinganalytics.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

class MarketFetchSupportTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void assetCatalogCarriesAllSupportedAdaptationsAndAbsenceIsExplicit() {
        assertThat(MarketFetchSupport.ASSETS.keySet())
                .containsExactly("btc", "eth", "sol", "gold", "spx", "ndx");
        assertThat(MarketFetchSupport.ASSETS.get("btc").annualize()).isEqualTo(365);
        assertThat(MarketFetchSupport.ASSETS.get("gold").annualize()).isEqualTo(252);
        assertThat(MarketFetchSupport.ASSETS.get("gold").perpetualSymbol()).isNull();
        assertThat(MarketFetchSupport.ASSETS.get("gold").sentimentProxy().closedEndFundSymbol())
                .isEqualTo("PHYS");
    }

    @Test
    void completedCandlesDropsEveryTrailingOpenBarRatherThanOnlyOne() throws Exception {
        ArrayNode candles = (ArrayNode) JSON.readTree("""
                [{"t":0,"date":"1970-01-01","close":1},
                 {"t":100,"date":"1970-01-02","close":2},
                 {"t":200,"date":"1970-01-03","close":3}]
                """);
        assertThat(MarketFetchSupport.completedCandles(candles, 100, 250)).hasSize(2);
        assertThat(MarketFetchSupport.completedCandles(candles, 100, 50)).isEmpty();
    }

    @Test
    void weeklyBlockReportsInsufficientSmaWithoutFabricatingIt() {
        ArrayNode candles = JSON.createArrayNode();
        long week = 7L * 86_400_000L;
        for (int index = 0; index < 20; index++) {
            candles.addObject().put("t", index * week).put("date", "w" + index).put("close", 100 + index);
        }
        var block = MarketFetchSupport.weeklyBlock(candles, 120.0, 21 * week);
        assertThat(block.path("completed_closes").asInt()).isEqualTo(20);
        assertThat(block.path("rsi14").path("rsi").isNumber()).isTrue();
        assertThat(block.path("sma_200w").path("value").isNull()).isTrue();
        assertThat(block.path("sma_200w").path("note").asText()).contains("only 20");
    }

    @Test
    void dailyFundingSeriesUsesUtcCalendarDaysAndAnnualizedConvention() throws Exception {
        ArrayNode intervals = (ArrayNode) JSON.readTree("""
                [{"fundingTime":0,"fundingRate":"0.0001"},
                 {"fundingTime":28800000,"fundingRate":"0.0003"},
                 {"fundingTime":86400000,"fundingRate":"-0.0001"}]
                """);
        assertThat(MarketFetchSupport.dailyAnnualizedFundingSeries(intervals))
                .containsExactly(21.9, -10.95);
    }

    @Test
    void yahooParserKeepsVolumeAndSkipsNullCloses() throws Exception {
        var response = JSON.readTree("""
                {"chart":{"result":[{"timestamp":[0,86400],"indicators":{"quote":[{
                  "open":[1,2],"high":[2,3],"low":[0,1],"close":[1.5,null],"volume":[10,20]
                }]}}]}}
                """);
        ArrayNode candles = MarketFetchSupport.parseYahooChart(response, "TEST");
        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).path("date").asText()).isEqualTo("1970-01-01");
        assertThat(candles.get(0).path("volume").asInt()).isEqualTo(10);
        assertThatThrownBy(() -> MarketFetchSupport.parseYahooChart(JSON.createObjectNode(), "BAD"))
                .hasMessage("yahoo: empty result for BAD");
    }
}
