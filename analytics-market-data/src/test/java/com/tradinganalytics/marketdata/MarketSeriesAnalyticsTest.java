package com.tradinganalytics.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketSeriesAnalyticsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void spotPanelKeepsOnlySynchronizedVenueQuotesInMedian() throws Exception {
        long now = 1_800_000_000_000L;
        ArrayNode quotes = (ArrayNode) JSON.readTree("""
                [
                  {"source":"primary","value":100,"ts":1799999700000,"ts_kind":"venue"},
                  {"source":"second","value":101,"ts":1799999400000,"ts_kind":"receipt"},
                  {"source":"stale-close","value":100.2,"ts":1799989200000,"ts_kind":"venue"},
                  {"source":"daily","value":90,"ts":1799913600000,"ts_kind":"bar_close"}
                ]
                """);
        var output = MarketSeriesAnalytics.spotPanel(quotes, now, 120, 0.5);
        assertThat(output.path("canonical").asDouble()).isEqualTo(100.5);
        assertThat(output.path("n_synchronized").asInt()).isEqualTo(2);
        assertThat(output.path("excluded")).hasSize(2);
        assertThat(output.path("excluded").get(0).path("reason").isNull()).isTrue();
        assertThat(output.path("spread_gt_0_5pct").asBoolean()).isTrue();
    }

    @Test
    void emptySpotPanelFailsClosedWithoutThrowing() {
        var output = MarketSeriesAnalytics.spotPanel(JSON.createArrayNode(), 0L, 120, 0.5);
        assertThat(output.path("canonical").isNull()).isTrue();
        assertThat(output.path("low_confidence").asBoolean()).isTrue();
        assertThat(output.path("warning").asText()).isEqualTo("no usable spot quotes");
    }

    @Test
    void rollingSeriesUseOnlyInformationAvailableAtEachPoint() {
        List<Double> closes = List.of(10.0, 12.0, 11.0, 15.0, 14.0, 16.0);
        assertThat(MarketSeriesAnalytics.percentChange(closes, 2)).isEqualTo(6.67);
        assertThat(MarketSeriesAnalytics.smaSlope(closes, 3, 2)).isEqualTo(18.42);
        assertThat(MarketSeriesAnalytics.rollingDrawdownFromAth(closes))
                .containsExactly(0.0, 0.0, 8.33, 0.0, 6.67, 0.0);
        assertThat(MarketSeriesAnalytics.rollingSmaDistance(closes, 3))
                .containsExactly(0.0, 18.42, 5.0, 6.67);
        assertThat(MarketSeriesAnalytics.rollingBouncePercent(closes, 3))
                .containsExactly(10.0, 36.36, 27.27, 14.29);
        assertThat(MarketSeriesAnalytics.rollingTrailingHighDistance(closes, 3))
                .containsExactly(8.33, 0.0, 6.67, 0.0);
    }

    @Test
    void runUtilitiesCoverBothDirectionsAndInvalidWindows() {
        assertThat(MarketSeriesAnalytics.consecutiveRun(List.of(1, 2, 3, 4), value -> value >= 3, true)).isEqualTo(2);
        assertThat(MarketSeriesAnalytics.consecutiveRun(List.of(1, 2, 3, 4), value -> value <= 2, false)).isEqualTo(2);
        assertThat(MarketSeriesAnalytics.percentChange(List.of(0.0, 1.0), 1)).isNull();
        assertThat(MarketSeriesAnalytics.smaSlope(List.of(1.0, 2.0), 3, 1)).isNull();
    }
}
