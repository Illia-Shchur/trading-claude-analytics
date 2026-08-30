package com.tradinganalytics.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

class MarketFlowAggregationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void flowRowsAggregateUsdAndGrossWeightedCloseWithCoverage() throws Exception {
        ArrayNode groups = (ArrayNode) JSON.readTree("""
                [
                  {"symbol":"BTCUSDT","rows":[{"time":1000,"buy_usd":100,"sell_usd":50,"close":10}]},
                  {"symbol":"ETHUSDT","rows":[{"time":1000,"buy_usd":25,"sell_usd":75,"close":20}]}
                ]
                """);
        var output = MarketFlowAggregation.aggregateFlowRows(groups);
        assertThat(output).hasSize(1);
        assertThat(output.get(0).path("time").asLong()).isEqualTo(1_000_000L);
        assertThat(output.get(0).path("buy_usd").asDouble()).isEqualTo(125);
        assertThat(output.get(0).path("close").asDouble()).isEqualTo(14);
        assertThat(output.get(0).path("coverage_complete").asBoolean()).isTrue();
    }

    @Test
    void valueAndFundingSnapshotsUseBoundedTimeBuckets() throws Exception {
        ArrayNode values = (ArrayNode) JSON.readTree("""
                [{"symbol":"BTC","rows":[{"time":1801,"value":10}]},{"symbol":"ETH","rows":[{"time":1802,"value":20}]}]
                """);
        var aggregate = MarketFlowAggregation.aggregateValueSnapshots(values, 1_800_000L);
        assertThat(aggregate.get(0).path("value").asDouble()).isEqualTo(30);
        assertThat(aggregate.get(0).path("coverage_complete").asBoolean()).isTrue();

        ArrayNode funding = (ArrayNode) JSON.readTree("""
                [{"symbol":"BTC","rows":[{"time":1000,"rate":0.01}]},{"symbol":"ETH","rows":[{"time":1000,"rate":-0.01}]}]
                """);
        var weighted = MarketFlowAggregation.oiWeightedFundingSnapshots(values, funding, 1_800_000L);
        assertThat(weighted).hasSize(1);
        assertThat(weighted.get(0).path("value").asDouble()).isEqualTo(-0.0033333333333333335);
        assertThat(weighted.get(0).path("total_oi_usd").asDouble()).isEqualTo(30);
    }

    @Test
    void resamplingDropsPartialBucketsAndReportsSamplingQuality() throws Exception {
        ArrayNode rows = (ArrayNode) JSON.readTree("""
                [
                  {"time":0,"value":10,"coverage_count":2,"expected_count":2},
                  {"time":1800,"value":12,"coverage_count":2,"expected_count":2},
                  {"time":3600,"value":11,"coverage_count":1,"expected_count":2},
                  {"time":7200,"value":99,"coverage_count":2,"expected_count":2}
                ]
                """);
        var candles = MarketFlowAggregation.resampleSnapshotsToCandles(rows, 1, 30, 10, 7_200_000L);
        assertThat(candles).hasSize(2);
        assertThat(candles.get(0).path("open").asDouble()).isEqualTo(10);
        assertThat(candles.get(0).path("close").asDouble()).isEqualTo(12);
        assertThat(candles.get(0).path("sampling_complete").asBoolean()).isTrue();
        assertThat(candles.get(1).path("contract_coverage_complete").asBoolean()).isFalse();
    }
}
