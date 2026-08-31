package com.tradinganalytics.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

class FundingAnalyticsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void emptyInputIsExplicitlyInsufficient() {
        assertThat(FundingAnalytics.fundingBlock(JSON.createArrayNode(), 45).path("n_intervals").asInt()).isZero();
    }

    @Test
    void trailingWindowSeparatesNegativePrintsFromStrictAnnualizedGate() throws Exception {
        ArrayNode intervals = (ArrayNode) JSON.readTree("""
                [{"fundingTime":0,"fundingRate":"-0.00001"},
                 {"fundingTime":28800000,"fundingRate":"-0.00005"},
                 {"fundingTime":57600000,"fundingRate":"-0.00005"},
                 {"fundingTime":86400000,"fundingRate":"-0.00008"}]
                """);
        var result = FundingAnalytics.fundingBlock(intervals, 3);
        assertThat(result.path("n_intervals").asInt()).isEqualTo(3);
        assertThat(result.path("longest_negative_run_intervals").asInt()).isEqualTo(3);
        assertThat(result.path("sustained3_below_minus5").asBoolean()).isTrue();
        assertThat(result.path("single_interval_below_minus7").asBoolean()).isTrue();
        assertThat(result.path("most_recent_below_minus7_intervals_ago").asInt()).isZero();
    }

    @Test
    void alternatingPrintsUseJavascriptLeftFoldAtRoundingBoundary() {
        ArrayNode rows = JSON.createArrayNode();
        for (int index = 0; index < 60; index++) rows.addObject()
                .put("fundingRate", index % 2 == 0 ? "0.0001" : "-0.00005")
                .put("fundingTime", index * 28_800_000L);
        assertThat(FundingAnalytics.fundingBlock(rows, 45).path("mean_annualized_pct").asDouble())
                .isEqualTo(2.55);
    }
}
