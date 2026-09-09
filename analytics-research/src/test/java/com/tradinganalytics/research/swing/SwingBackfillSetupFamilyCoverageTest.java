package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Deterministic setup-family matrices for the public historical signal classifier. */
class SwingBackfillSetupFamilyCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long BAR = SwingBackfill.BAR_MS;

    @Test
    void distributionContextEmitsEveryIndependentChannelAReason() {
        ArrayNode rows = steadyBars(100);
        ((ObjectNode) rows.get(80)).put("open", 100).put("high", 102).put("low", 98).put("close", 99);
        ((ObjectNode) rows.get(79)).put("open", 100).put("high", 101).put("low", 99).put("close", 100);

        ObjectNode result = SwingBackfill.setupFamiliesAt(rows, 80, "flying_rocket", "A",
                JSON.createObjectNode().put("kind", "FR_A_EUPHORIA_REJECTION"), richContext());

        assertThat(result.path("primary").asText()).isEqualTo("FR_A_EUPHORIA_REJECTION");
        assertThat(result.path("families")).extracting(JsonNode::asText).contains(
                "FR_A_EUPHORIA_REJECTION", "FR_A_DISTRIBUTION", "FR_A_FAILED_BREAKOUT",
                "FR_A_DERIVATIVES_CROWDING", "FR_A_DISTRIBUTION_DIVERGENCE", "FR_A_SENTIMENT_ROLLOVER",
                "FR_A_LEVERAGED_REJECTION", "FR_A_CVD_DISTRIBUTION", "FR_A_TOP_CROWDING");
        assertThat(result.path("flags").path("FR_A_DISTRIBUTION").asBoolean()).isTrue();
        assertThat(result.path("flags").path("FR_A_DERIVATIVES_CROWDING").asBoolean()).isTrue();
        assertThat(result.path("flags").path("FR_A_TOP_CROWDING").asBoolean()).isTrue();
    }

    @Test
    void bearRallyContextEmitsLowerHighBreakdownAndFlowReasons() {
        ArrayNode rows = decliningBars(100);
        ((ObjectNode) rows.get(80)).put("open", 72.6).put("high", 74).put("low", 69).put("close", 70);
        ((ObjectNode) rows.get(79)).put("open", 73).put("high", 74).put("low", 72).put("close", 72.6);

        ObjectNode result = SwingBackfill.setupFamiliesAt(rows, 80, "flying_rocket", "B",
                JSON.createObjectNode().put("kind", "FR_B_BEAR_RALLY_FAILURE"), bearRallyContext());

        assertThat(result.path("primary").asText()).isEqualTo("FR_B_BEAR_RALLY_FAILURE");
        assertThat(result.path("families")).extracting(JsonNode::asText).contains(
                "FR_B_BEAR_RALLY_FAILURE", "FR_B_LOWER_HIGH", "FR_B_BREAKDOWN_RETEST",
                "FR_B_DERIVATIVES_RELOAD_FAILURE", "FR_B_FLOW_REJECTION", "FR_B_SENTIMENT_RELIEF_FAILURE",
                "FR_B_RALLY_FAILURE", "FR_B_BREAKDOWN_EXPANSION", "FR_B_WEAK_SPOT_RETEST");
        assertThat(result.path("flags").path("FR_B_BREAKDOWN_RETEST").asBoolean()).isTrue();
        assertThat(result.path("flags").path("FR_B_BREAKDOWN_EXPANSION").asBoolean()).isTrue();
        assertThat(result.path("flags").path("FR_B_WEAK_SPOT_RETEST").asBoolean()).isTrue();
    }

    private static ObjectNode richContext() {
        ObjectNode context = baseContext();
        ObjectNode technical = (ObjectNode) context.path("factors").path("technical");
        technical.put("return_24h", .01).put("return_4h", -.01).put("return_24h_normalized", .3)
                .put("return_3d_normalized", .8).put("close_location", .4).put("volume_z_90d", .7)
                .put("return_3d_prior_percentile", .1).put("ema20", 95).put("ema50", 90);
        ObjectNode derivatives = (ObjectNode) context.path("factors").path("derivatives");
        derivatives.put("funding_mean_3d", .01).put("oi_change_3d_pct", .02).put("spot_cvd_24h_usd", -10)
                .put("futures_cvd_24h_usd", -20).put("spot_cvd_24h_z", .6).put("futures_cvd_24h_z", .6)
                .put("spot_futures_divergence_z", -.6).put("oi_change_24h_z", .3).put("funding_mean_24h_z", .6)
                .put("top_vs_global_positioning_z", .7);
        ((ObjectNode) context.path("factors").path("sentiment")).put("fear_greed", 70).put("fear_greed_3d_change", -5);
        return context;
    }

    private static ObjectNode bearRallyContext() {
        ObjectNode context = baseContext();
        ObjectNode technical = (ObjectNode) context.path("factors").path("technical");
        technical.put("return_24h", .01).put("return_4h", -.01).put("return_24h_normalized", .3)
                .put("return_3d_normalized", .3).put("close_location", .4).put("volume_z_90d", .7)
                .put("return_3d_prior_percentile", .5).put("ema20", 71).put("ema50", 75);
        ObjectNode derivatives = (ObjectNode) context.path("factors").path("derivatives");
        derivatives.put("funding_mean_3d", .01).put("oi_change_3d_pct", .02).put("spot_cvd_24h_usd", -10)
                .put("futures_cvd_24h_usd", 10).put("spot_cvd_24h_z", -.1).put("futures_cvd_24h_z", -.6)
                .put("spot_futures_divergence_z", -.6).put("oi_change_24h_z", 0).put("funding_mean_24h_z", .6)
                .put("top_vs_global_positioning_z", 0);
        ((ObjectNode) context.path("factors").path("sentiment")).put("fear_greed", 50).put("fear_greed_3d_change", 1);
        return context;
    }

    private static ObjectNode baseContext() {
        ObjectNode context = JSON.createObjectNode();
        ObjectNode factors = context.putObject("factors");
        ObjectNode technical = factors.putObject("technical");
        for (String field : List.of("return_24h", "return_4h", "return_24h_normalized", "return_3d_normalized",
                "close_location", "volume_z_90d", "return_3d_prior_percentile", "ema20", "ema50")) technical.put(field, 0);
        ObjectNode derivatives = factors.putObject("derivatives");
        for (String field : List.of("funding_mean_3d", "oi_change_3d_pct", "spot_cvd_24h_usd", "futures_cvd_24h_usd",
                "spot_cvd_24h_z", "futures_cvd_24h_z", "spot_futures_divergence_z", "oi_change_24h_z",
                "funding_mean_24h_z", "top_vs_global_positioning_z")) derivatives.put(field, 0);
        factors.putObject("sentiment").put("fear_greed", 50).put("fear_greed_3d_change", 0);
        factors.putObject("relative").put("return_4h_vs_btc", 0);
        return context;
    }

    private static ArrayNode steadyBars(int count) {
        ArrayNode rows = JSON.createArrayNode();
        long start = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
        for (int i = 0; i < count; i++) rows.add(JSON.createObjectNode().put("time", start + i * BAR)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100));
        return rows;
    }

    private static ArrayNode decliningBars(int count) {
        ArrayNode rows = JSON.createArrayNode();
        long start = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
        for (int i = 0; i < count; i++) {
            double close = 120 - i * .6;
            rows.add(JSON.createObjectNode().put("time", start + i * BAR).put("open", close)
                    .put("high", close + 1).put("low", close - 1).put("close", close));
        }
        return rows;
    }
}
