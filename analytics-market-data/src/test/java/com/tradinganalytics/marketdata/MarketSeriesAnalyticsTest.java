package com.tradinganalytics.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import java.util.Map;
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
    void spotPanelExcludesNullAndNonPositiveQuotes() throws Exception {
        ArrayNode quotes = (ArrayNode) JSON.readTree("""
                [{"source":"valid","value":100,"ts":1000000,"ts_kind":"venue"},
                 {"source":"null","value":null,"ts":1000000,"ts_kind":"venue"},
                 {"source":"zero","value":0,"ts":1000000,"ts_kind":"venue"}]
                """);

        var output = MarketSeriesAnalytics.spotPanel(quotes, 1_000_000L, 120, 0.5);

        assertThat(output.path("canonical").asDouble()).isEqualTo(100.0);
        assertThat(output.path("n_synchronized").asInt()).isEqualTo(1);
        assertThat(output.path("spread_pct").asDouble()).isZero();
        assertThat(output.path("excluded")).hasSize(2);
        assertThat(output.path("excluded").get(0).path("reason").asText()).contains("finite and positive");
    }

    @Test
    void spotPanelExcludesQuotesBeyondFutureClockSkew() throws Exception {
        ArrayNode quotes = (ArrayNode) JSON.readTree("""
                [{"source":"future","value":999,"ts":9000000,"ts_kind":"venue"}]
                """);

        var output = MarketSeriesAnalytics.spotPanel(quotes, 1_000_000L, 120, 0.5);

        assertThat(output.path("canonical").isNull()).isTrue();
        assertThat(output.path("n_synchronized").asInt()).isZero();
        assertThat(output.path("excluded").get(0).path("reason").asText()).contains("in the future");
    }

    @Test
    void spotPanelExcludesUnknownAgeFromSynchronizedMedian() throws Exception {
        ArrayNode quotes = (ArrayNode) JSON.readTree("""
                [{"source":"undated","value":99,"ts":null,"ts_kind":"receipt"},
                 {"source":"missing","value":98,"ts_kind":"venue"},
                 {"source":"known","value":100,"ts":1000000,"ts_kind":"venue"}]
                """);

        var output = MarketSeriesAnalytics.spotPanel(quotes, 1_000_000L, 120, 0.5);

        assertThat(output.path("canonical").asDouble()).isEqualTo(100.0);
        assertThat(output.path("n_synchronized").asInt()).isEqualTo(1);
        assertThat(output.path("low_confidence").asBoolean()).isTrue();
        assertThat(output.path("excluded").findValuesAsText("reason"))
                .allMatch(reason -> reason.contains("freshness is unknown"));
    }

    @Test
    void spotPanelFailsClosedWhenEveryQuoteIsStaleOrHistorical() throws Exception {
        ArrayNode quotes = (ArrayNode) JSON.readTree("""
                [{"source":"stale","value":99,"ts":-10000000,"ts_kind":"venue"},
                 {"source":"daily","value":100,"ts":1000000,"ts_kind":"bar_close"}]
                """);

        var output = MarketSeriesAnalytics.spotPanel(quotes, 1_000_000L, 120, 0.5);

        assertThat(output.path("canonical").isNull()).isTrue();
        assertThat(output.path("n_synchronized").asInt()).isZero();
        assertThat(output.path("low_confidence").asBoolean()).isTrue();
        assertThat(output.path("excluded").findValuesAsText("reason"))
                .contains("frozen bar close — never enters the median");
    }

    @Test
    void spotPanelRequiresRecognizedTimestampKindEvenWhenTimestampExists() throws Exception {
        ArrayNode quotes = (ArrayNode) JSON.readTree("""
                [{"source":"unclassified","value":100,"ts":1000000},
                 {"source":"venue","value":101,"ts":1000000,"ts_kind":"venue"}]
                """);

        var output = MarketSeriesAnalytics.spotPanel(quotes, 1_000_000L, 120, 0.5);

        assertThat(output.path("n_synchronized").asInt()).isEqualTo(1);
        assertThat(output.path("low_confidence").asBoolean()).isTrue();
        assertThat(output.path("excluded").findValuesAsText("reason"))
                .containsExactly("EXCLUDED — quote freshness is unknown (timestamp and recognized timestamp kind are required)");
    }

    @Test
    void spotAssemblerDoesNotFallBackToANonPositiveSource() throws Exception {
        var coinGecko = JSON.readTree("""
                {"bitcoin":{"usd":0,"last_updated_at":1800000000}}
                """);
        var output = new SpotSnapshotAssembler(JSON).assemble(
                MarketFetchSupport.ASSETS.get("btc"), coinGecko,
                JSON.createArrayNode(), JSON.createArrayNode(), Map.of(), 1_800_000_000_000L);

        assertThat(output.path("canonical").isNull()).isTrue();
        assertThat(output.path("sources")).isEmpty();
        assertThat(output.path("canonical_source").asText()).isEqualTo("unavailable");
        assertThat(output.path("contextual_fallback").path("value").isNull()).isTrue();
    }

    @Test
    void spotAssemblerKeepsHistoricalFallbackOutOfCanonicalWhenPanelIsUnavailable() throws Exception {
        var coinGecko = JSON.readTree("""
                {"bitcoin":{"usd":100,"last_updated_at":1}}
                """);
        var daily = (ArrayNode) JSON.readTree("""
                [{"date":"2026-08-28","close":90}]
                """);
        var output = new SpotSnapshotAssembler(JSON).assemble(
                MarketFetchSupport.ASSETS.get("btc"), coinGecko, daily,
                JSON.createArrayNode(), Map.of(), 1_800_000_000_000L);

        assertThat(output.path("canonical").isNull()).isTrue();
        assertThat(output.path("canonical_source").asText()).isEqualTo("unavailable");
        assertThat(output.path("contextual_fallback").path("value").asDouble()).isEqualTo(100.0);
        assertThat(output.path("contextual_fallback").path("eligible_for_scoring").asBoolean()).isFalse();
    }

    @Test
    void spotAssemblerUsesVerifiedMedianOnlyForCanonicalSpot() throws Exception {
        var output = new SpotSnapshotAssembler(JSON).assemble(
                MarketFetchSupport.ASSETS.get("btc"), null, JSON.createArrayNode(),
                JSON.createArrayNode(), Map.of(
                        "binanceQ", JSON.readTree("{\"source\":\"Binance\",\"value\":100,\"ts\":1800000000000,\"ts_kind\":\"venue\"}"),
                        "coinbaseQ", JSON.readTree("{\"source\":\"Coinbase\",\"value\":101,\"ts\":1800000000000,\"ts_kind\":\"venue\"}")),
                1_800_000_000_000L);

        assertThat(output.path("canonical").asDouble()).isEqualTo(100.5);
        assertThat(output.path("canonical_source").asText()).isEqualTo("panel_median");
        assertThat(output.path("contextual_fallback").isNull()).isTrue();
    }

    @Test
    void spotAssemblerLabelsSingleFreshVenueAsContextOnly() throws Exception {
        var output = new SpotSnapshotAssembler(JSON).assemble(
                MarketFetchSupport.ASSETS.get("btc"), null, JSON.createArrayNode(),
                JSON.createArrayNode(), Map.of(
                        "binanceQ", JSON.readTree("{\"source\":\"Binance\",\"value\":100,\"ts\":1800000000000,\"ts_kind\":\"venue\"}")),
                1_800_000_000_000L);

        assertThat(output.path("canonical").isNull()).isTrue();
        assertThat(output.path("contextual_fallback").path("source").asText())
                .isEqualTo("panel_insufficient_sources");
        assertThat(output.path("contextual_fallback").path("value").asDouble()).isEqualTo(100.0);
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
