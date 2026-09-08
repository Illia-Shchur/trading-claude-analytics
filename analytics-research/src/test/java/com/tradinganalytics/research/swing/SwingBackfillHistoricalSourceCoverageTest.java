package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public contract coverage for injected historical source backfills. */
class SwingBackfillHistoricalSourceCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long BAR = SwingBackfill.BAR_MS;
    private static final long DAY = SwingBackfill.DAY_MS;

    @TempDir Path temporary;

    @Test
    void injectedHistoricalSourceProducesCompleteAlignedFrameworkDatasets() throws Exception {
        long now = Instant.parse("2026-08-22T12:00:00Z").toEpochMilli();
        FixtureSource source = new FixtureSource();
        SwingBackfill.Options options = new SwingBackfill.Options(.5, temporary.resolve("cache"), now);

        ObjectNode result = SwingBackfill.backfillAsset("sol", options, source);

        assertThat(result.path("asset").asText()).isEqualTo("sol");
        assertThat(result.path("coverage").asText()).isEqualTo("ALIGNED_MULTI_SOURCE");
        assertThat(result.path("point_in_time_safe").asBoolean()).isFalse();
        assertThat(result.path("bars")).isNotEmpty();
        assertThat(result.path("labels")).isNotEmpty();
        assertThat(result.path("datasets")).hasSize(3);
        assertThat(source.klinesAssets).containsExactly("sol", "sol", "btc");
        assertThat(source.futuresRequests).containsExactly(false, true, false);
        assertThat(source.fundingCalls).isEqualTo(1);
        assertThat(source.metricsCalls).isEqualTo(1);
        assertThat(source.macroCalls).isEqualTo(1);
        assertThat(source.sentimentCalls).isEqualTo(1);
        assertThat(source.valuationCalls).isEqualTo(1);

        List<String> coverageStatuses = new java.util.ArrayList<>();
        List<String> frameworks = new java.util.ArrayList<>();
        for (JsonNode dataset : result.path("datasets")) {
            coverageStatuses.add(dataset.path("coverage").asText());
            frameworks.add(dataset.path("framework").asText());
        }
        assertThat(coverageStatuses).containsExactly("COMPLETE", "COMPLETE", "COMPLETE");
        assertThat(frameworks).containsExactly("fallen_knives", "flying_rocket", "flying_rocket");
        for (JsonNode dataset : result.path("datasets")) {
            assertThat(dataset.path("features")).isNotEmpty();
            assertThat(dataset.path("labels")).isNotEmpty();
        }
        assertThat(result.path("datasets").get(0).path("coverage_meta").path("eligible_feature_bars").asInt())
                .isPositive();
        JsonNode firstFeature = result.path("datasets").get(0).path("features").get(0);
        assertThat(firstFeature.path("factors").path("technical")).isNotEmpty();
        assertThat(firstFeature.path("flow_panel").path("schema").asText()).isEqualTo("market-flow/1");
        assertThat(firstFeature.path("atr_20d").asDouble()).isPositive();
        assertThat(firstFeature.path("protective_controls").path("stop_valid").asBoolean()).isTrue();
    }

    @Test
    void decliningThenReboundingHistoryRetainsIndependentReturnAndFlowArithmetic() throws Exception {
        long now = Instant.parse("2026-08-22T12:00:00Z").toEpochMilli();
        FixtureSource source = FixtureSource.rebounding();
        ObjectNode result = SwingBackfill.backfillAsset("sol",
                new SwingBackfill.Options(.5, temporary.resolve("rebound-cache"), now), source);

        ArrayNode features = (ArrayNode) result.path("datasets").get(0).path("features");
        assertThat(features.size()).isGreaterThan(20);
        JsonNode first = features.get(0), selected = features.get(features.size() - 1);
        assertThat(first.path("factors").path("technical").path("return_24h").asDouble()).isNegative();
        assertThat(selected.path("factors").path("technical").path("return_24h").asDouble()).isPositive();

        long selectedTime = selected.path("time").asLong();
        double expectedFourHour = source.closeAt("sol", selectedTime, false)
                / source.closeAt("sol", selectedTime - BAR, false) - 1;
        double expectedDay = source.closeAt("sol", selectedTime, false)
                / source.closeAt("sol", selectedTime - 6 * BAR, false) - 1;
        assertThat(selected.path("factors").path("technical").path("return_4h").asDouble())
                .isCloseTo(expectedFourHour, within(1e-12));
        assertThat(selected.path("factors").path("technical").path("return_24h").asDouble())
                .isCloseTo(expectedDay, within(1e-12));

        double expectedSpotDelta = 0;
        for (int offset = 0; offset < 6; offset++) expectedSpotDelta += source.quoteAt(selectedTime - offset * BAR) * .10;
        JsonNode panel = selected.path("flow_panel");
        assertThat(panel.path("spot_cvd").path("delta_24h_usd").asDouble())
                .isCloseTo(expectedSpotDelta, within(1e-6));
        assertThat(panel.path("spot_cvd").path("direction_24h").asText()).isEqualTo("positive");
    }

    @Test
    void missingValuationUsesExplicitPriceProxyAndKeepsFeaturesComplete() throws Exception {
        long now = Instant.parse("2026-08-22T12:00:00Z").toEpochMilli();
        FixtureSource source = FixtureSource.withoutValuation();
        ObjectNode result = SwingBackfill.backfillAsset("sol",
                new SwingBackfill.Options(.5, temporary.resolve("proxy-cache"), now), source);

        assertThat(source.valuationCalls).isEqualTo(1);
        for (JsonNode dataset : result.path("datasets")) {
            assertThat(dataset.path("coverage").asText()).isEqualTo("COMPLETE");
            assertThat(dataset.path("features")).isNotEmpty();
            assertThat(dataset.path("coverage_meta").path("source_rows").path("valuation").asInt()).isZero();
            JsonNode valuation = dataset.path("features").get(0).path("factors").path("valuation");
            assertThat(valuation.path("mvrv").isNull()).isTrue();
            assertThat(valuation.path("distance_from_1y_high").isNumber()).isTrue();
            assertThat(dataset.path("features").get(0).path("leg_components").path("valuation")
                    .path("source").asText()).contains("price-derived");
        }
    }

    @Test
    void missingHistoricalInputsDowngradeCoverageWithoutFabricatingFeatures() throws Exception {
        long now = Instant.parse("2026-08-22T12:00:00Z").toEpochMilli();
        List<FixtureSource> sources = List.of(FixtureSource.withoutMacro(), FixtureSource.withoutSentiment(),
                FixtureSource.withoutMetrics());
        for (FixtureSource source : sources) {
            ObjectNode result = SwingBackfill.backfillAsset("sol",
                    new SwingBackfill.Options(.5, temporary.resolve("missing-cache-" + sources.indexOf(source)), now), source);
            assertThat(result.path("bars")).isNotEmpty();
            assertThat(result.path("labels")).isNotEmpty();
            for (JsonNode dataset : result.path("datasets")) {
                assertThat(dataset.path("coverage").asText()).isEqualTo("HISTORICAL_PROXY");
                assertThat(dataset.path("features")).isEmpty();
                assertThat(dataset.path("coverage_meta").path("eligible_feature_bars").asInt()).isZero();
                assertThat(dataset.path("labels").size()).isEqualTo(result.path("labels").size());
            }
        }
    }

    @Test
    void btcUsesItsSpotSeriesAsBenchmarkWithoutDuplicateKlineRequest() throws Exception {
        long now = Instant.parse("2026-08-22T12:00:00Z").toEpochMilli();
        FixtureSource source = new FixtureSource();
        ObjectNode result = SwingBackfill.backfillAsset("btc",
                new SwingBackfill.Options(.5, temporary.resolve("btc-cache"), now), source);

        assertThat(source.klinesAssets).containsExactly("btc", "btc");
        assertThat(source.futuresRequests).containsExactly(false, true);
        assertThat(result.path("datasets")).hasSize(3);
        assertThat(result.path("datasets").get(0).path("features")).isNotEmpty();
    }

    @Test
    void mechanicalTriggersAndSetupFamilyBoundariesExposePublicStates() {
        ArrayNode flat = bars(100, false);
        ObjectNode empty = SwingBackfill.setupFamiliesAt(flat, -1, "fallen_knives", null, null, null);
        assertThat(empty.path("primary").asText()).isEqualTo("UNSPECIFIED");
        assertThat(empty.path("families")).extracting(JsonNode::asText).containsExactly("UNSPECIFIED");

        ArrayNode reversal = bars(100, false);
        ((ObjectNode) reversal.get(79)).put("close", 90);
        ((ObjectNode) reversal.get(80)).put("close", 101).put("high", 102).put("low", 99);
        ObjectNode fkReversal = SwingBackfill.mechanicalTrigger(reversal, 80, "fallen_knives", null, 100, 40);
        assertThat(fkReversal.path("valid").asBoolean()).isTrue();
        assertThat(fkReversal.path("kind").asText()).isEqualTo("FK_REVERSAL_RECLAIM");

        ArrayNode support = bars(100, false);
        ((ObjectNode) support.get(80)).put("close", 101).put("high", 102).put("low", 98);
        ObjectNode fkSupport = SwingBackfill.mechanicalTrigger(support, 80, "fallen_knives", null, 102, 40);
        assertThat(fkSupport.path("valid").asBoolean()).isTrue();
        assertThat(fkSupport.path("kind").asText()).isEqualTo("FK_SUPPORT_RECLAIM");

        ArrayNode rejection = bars(100, false);
        ((ObjectNode) rejection.get(80)).put("close", 99).put("high", 101);
        ObjectNode frA = SwingBackfill.mechanicalTrigger(rejection, 80, "flying_rocket", "A", 100, 60);
        assertThat(frA.path("valid").asBoolean()).isTrue();
        assertThat(frA.path("kind").asText()).isEqualTo("FR_A_EUPHORIA_REJECTION");

        ArrayNode failure = bars(100, true);
        ((ObjectNode) failure.get(79)).put("close", 85);
        ((ObjectNode) failure.get(80)).put("close", 70).put("high", 80);
        ObjectNode frB = SwingBackfill.mechanicalTrigger(failure, 80, "flying_rocket", "B", 75, 40);
        assertThat(frB.path("valid").asBoolean()).isTrue();
        assertThat(frB.path("kind").asText()).isEqualTo("FR_B_BEAR_RALLY_FAILURE");

        ObjectNode context = setupContext();
        for (String framework : List.of("fallen_knives", "flying_rocket")) {
            List<String> channels = framework.equals("fallen_knives")
                    ? java.util.Collections.<String>singletonList(null) : List.of("A", "B");
            for (String channel : channels) {
                ObjectNode family = SwingBackfill.setupFamiliesAt(flat, 80, framework, channel,
                        object().put("kind", "NONE"), context);
                assertThat(family.path("flags")).isNotEmpty();
                assertThat(family.path("families")).isNotEmpty();
            }
        }
    }

    private static ObjectNode setupContext() {
        ObjectNode context = JSON.createObjectNode();
        ObjectNode technical = context.putObject("factors").putObject("technical");
        for (String field : List.of("return_24h", "return_4h", "return_24h_normalized", "return_3d_normalized",
                "close_location", "volume_z_90d", "return_3d_prior_percentile", "ema20", "ema50")) technical.put(field, 0);
        ObjectNode derivatives = ((ObjectNode) context.path("factors")).putObject("derivatives");
        for (String field : List.of("funding_mean_3d", "oi_change_3d_pct", "spot_cvd_24h_usd", "futures_cvd_24h_usd",
                "spot_cvd_24h_z", "futures_cvd_24h_z", "spot_futures_divergence_z", "oi_change_24h_z",
                "funding_mean_24h_z", "top_vs_global_positioning_z")) derivatives.put(field, 0);
        ObjectNode sentiment = ((ObjectNode) context.path("factors")).putObject("sentiment");
        sentiment.put("fear_greed", 50).put("fear_greed_3d_change", 0);
        ((ObjectNode) context.path("factors")).putObject("relative").put("return_4h_vs_btc", 0);
        return context;
    }

    private static ArrayNode bars(int count, boolean declining) {
        ArrayNode rows = JSON.createArrayNode();
        long start = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
        for (int index = 0; index < count; index++) {
            double close = declining ? 120 - index * .6 : 100;
            rows.add(JSON.createObjectNode().put("time", start + index * BAR).put("open", close)
                    .put("high", close + 1).put("low", close - 1).put("close", close)
                    .put("volume", 1000 + index));
        }
        return rows;
    }

    private static ObjectNode object() { return JSON.createObjectNode(); }

    private static final class FixtureSource implements SwingBackfill.HistoricalSource {
        private enum PriceProfile { STEADY, REBOUND }
        private static final long PRICE_ANCHOR_BAR = Math.floorDiv(
                Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), BAR);

        private final List<String> klinesAssets = new java.util.ArrayList<>();
        private final List<Boolean> futuresRequests = new java.util.ArrayList<>();
        private final PriceProfile priceProfile;
        private final boolean omitMacro;
        private final boolean omitSentiment;
        private final boolean omitValuation;
        private final boolean omitMetrics;
        private int fundingCalls;
        private int metricsCalls;
        private int macroCalls;
        private int sentimentCalls;
        private int valuationCalls;

        private FixtureSource() { this(PriceProfile.STEADY, false, false, false, false); }

        private FixtureSource(PriceProfile priceProfile, boolean omitMacro, boolean omitSentiment,
                boolean omitValuation, boolean omitMetrics) {
            this.priceProfile = priceProfile;
            this.omitMacro = omitMacro;
            this.omitSentiment = omitSentiment;
            this.omitValuation = omitValuation;
            this.omitMetrics = omitMetrics;
        }

        private static FixtureSource rebounding() {
            return new FixtureSource(PriceProfile.REBOUND, false, false, false, false);
        }

        private static FixtureSource withoutValuation() {
            return new FixtureSource(PriceProfile.STEADY, false, false, true, false);
        }

        private static FixtureSource withoutMacro() {
            return new FixtureSource(PriceProfile.STEADY, true, false, false, false);
        }

        private static FixtureSource withoutSentiment() {
            return new FixtureSource(PriceProfile.STEADY, false, true, false, false);
        }

        private static FixtureSource withoutMetrics() {
            return new FixtureSource(PriceProfile.STEADY, false, false, false, true);
        }

        private double closeAt(String asset, long time, boolean futures) {
            long index = Math.floorDiv(time, BAR) - PRICE_ANCHOR_BAR;
            double base = "btc".equals(asset) ? 100 : 200;
            double close;
            if (priceProfile == PriceProfile.REBOUND) {
                close = index < 3_000 ? base + 120 - index * .03 : base + 30 + (index - 3_000) * .08;
            } else {
                close = base + index * .02 + Math.sin(index / 9d) * 2;
            }
            return futures ? close + .1 : close;
        }

        private double quoteAt(long time) {
            long index = Math.floorDiv(time, BAR) - PRICE_ANCHOR_BAR;
            return 1_000_000 + index * 10;
        }

        @Override
        public ArrayNode klines(String asset, long start, long end, boolean futures, Path cache) {
            klinesAssets.add(asset);
            futuresRequests.add(futures);
            ArrayNode rows = JSON.createArrayNode();
            long first = Math.floorDiv(start, BAR) * BAR;
            for (long time = first; time < end; time += BAR) {
                long barIndex = Math.floorDiv(time, BAR) - PRICE_ANCHOR_BAR;
                double close = closeAt(asset, time, futures);
                double quote = quoteAt(time);
                rows.add(JSON.createObjectNode().put("time", time).put("open", close - .2)
                        .put("high", close + 1.5).put("low", close - 1.5).put("close", close)
                        .put("volume", 1_000 + Math.floorMod(barIndex, 50)).put("quote_volume", quote)
                        .put("taker_buy_quote", quote * .55).put("taker_sell_quote", quote * .45));
            }
            return rows;
        }

        @Override
        public ArrayNode funding(String asset, long start, long end, Path cache) {
            fundingCalls++;
            ArrayNode rows = JSON.createArrayNode();
            long first = Math.floorDiv(start, BAR) * BAR;
            int index = 0;
            for (long time = first; time < end; time += BAR) {
                rows.add(JSON.createObjectNode().put("time", time).put("rate", index % 2 == 0 ? .0001 : -.0001));
                index++;
            }
            return rows;
        }

        @Override
        public ArrayNode metrics(String asset, long start, long end, Path cache) {
            metricsCalls++;
            if (omitMetrics) return JSON.createArrayNode();
            ArrayNode rows = JSON.createArrayNode();
            long first = Math.floorDiv(start, BAR) * BAR;
            int index = 0;
            for (long time = first; time < end; time += BAR) {
                for (int sample = 0; sample < 4; sample++) {
                    rows.add(JSON.createObjectNode().put("time", time + sample * 1_000)
                            .put("value", 1_000_000 + index * 10 + sample)
                            .put("top_trader_account_ratio", 1.1 + sample * .01)
                            .put("top_trader_position_ratio", 1.2 + sample * .01)
                            .put("global_account_ratio", 1.0 + sample * .01)
                            .put("taker_long_short_ratio", 1.05 + sample * .01));
                }
                index++;
            }
            return rows;
        }

        @Override
        public ArrayNode macro(long start, long end, Path cache) {
            macroCalls++;
            if (omitMacro) return JSON.createArrayNode();
            ArrayNode rows = JSON.createArrayNode();
            long first = Math.floorDiv(start, DAY) * DAY - DAY;
            int index = 0;
            for (long time = first; time < end; time += DAY) {
                rows.add(JSON.createObjectNode().put("date", Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC)
                        .toLocalDate().toString()).put("dxy", 100 + index * .01)
                        .put("real_yield", 1 + Math.sin(index / 5d) * .1).put("available_at", time + DAY));
                index++;
            }
            return rows;
        }

        @Override
        public ArrayNode sentiment(long start, long end, Path cache) {
            sentimentCalls++;
            if (omitSentiment) return JSON.createArrayNode();
            ArrayNode rows = JSON.createArrayNode();
            long first = Math.floorDiv(start, DAY) * DAY - DAY;
            int index = 0;
            for (long time = first; time < end; time += DAY) {
                rows.add(JSON.createObjectNode().put("date", Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC)
                        .toLocalDate().toString()).put("value", 40 + index % 20)
                        .put("available_at", time + DAY));
                index++;
            }
            return rows;
        }

        @Override
        public ArrayNode valuation(String asset, long start, long end, Path cache) {
            valuationCalls++;
            if (omitValuation) return JSON.createArrayNode();
            ArrayNode rows = JSON.createArrayNode();
            long first = Math.floorDiv(start, DAY) * DAY - DAY;
            int index = 0;
            for (long time = first; time < end; time += DAY) {
                rows.add(JSON.createObjectNode().put("date", Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC)
                        .toLocalDate().toString()).put("mvrv", 1 + index % 10 * .01)
                        .put("available_at", time + DAY));
                index++;
            }
            return rows;
        }
    }
}
