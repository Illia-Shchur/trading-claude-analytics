package com.tradinganalytics.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class SnapshotPanelsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void missingSnapshotProducesMinimalUnavailablePanel() {
        var result = SnapshotPanels.proximityPanel(null);
        assertThat(result.path("note").asText()).isEqualTo("no snapshot");
        assertThat(result.path("items").isEmpty()).isTrue();
    }

    @Test
    void proximitySortsNearestEdgesAndMarksMetricSpecificNearBands() throws Exception {
        var snapshot = JSON.readTree("""
                {
                  "spot":{"canonical":80},
                  "high_1y":{"pct_below":22,"value":100},
                  "weekly":{"sma_200w":{"pct_vs_spot":9,"value":73.4},"rsi14":{"rsi":39}},
                  "trend":{"ma200_slope20_pct":0.3},
                  "sentiment":{"avg_3d":24}
                }
                """);
        var result = SnapshotPanels.proximityPanel(snapshot);
        assertThat(result.path("nearest").asText()).isEqualTo("ma200_slope_sign_flip");
        assertThat(result.path("near_count").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(result.path("items").findValuesAsText("id"))
                .contains("fr_channel_A_eligibility", "fk_gate6_200w_band",
                        "fk_momentum_band_edge", "frb_weekly_rsi50_qualifier",
                        "fk_sentiment_band_edge");
    }

    @Test
    void tripwireIgnoresMacroAndEmitsOnlyActualCrossings() throws Exception {
        ObjectNode previous = (ObjectNode) JSON.readTree("""
                {"btc":{
                  "spot":{"canonical":80},
                  "sentiment":{"avg_3d":49,"streaks_daily_prints":{"le15":6}},
                  "weekly":{"rsi14":{"rsi":59},"sma_200w":{"within_8pct":false}},
                  "trend":{"ma200_falling":true,"price_below_ma200":true,"bounce_pct":12,
                    "rsi14":44,"bounce_age_sessions":7},
                  "high_1y":{"pct_below":21},
                  "funding":{"mean_annualized_pct":-1,"sustained3_below_minus5":false},
                  "daily":{"adr5":{"adr":4}}
                }}
                """);
        ObjectNode next = (ObjectNode) JSON.readTree("""
                {"btc":{
                  "spot":{"canonical":87},
                  "sentiment":{"avg_3d":61,"streaks_daily_prints":{"le15":7}},
                  "weekly":{"rsi14":{"rsi":66},"sma_200w":{"within_8pct":true}},
                  "trend":{"ma200_falling":true,"price_below_ma200":true,"bounce_pct":19,
                    "rsi14":53,"bounce_age_sessions":8},
                  "high_1y":{"pct_below":19},
                  "funding":{"mean_annualized_pct":1,"sustained3_below_minus5":true},
                  "daily":{"adr5":{"adr":4}}
                },"macro":{"ignored":true}}
                """);
        ObjectNode checkpoints = (ObjectNode) JSON.readTree("{\"btc\":{\"line\":90}}");
        var result = SnapshotPanels.tripwireDiff(previous, next, checkpoints);
        assertThat(result.path("n_crossings").asInt()).isGreaterThan(8);
        assertThat(result.path("crossings").findValuesAsText("type"))
                .contains("fk_gate1_streak_le15_ge7", "fr_channel_routing", "fr_phase_cycle_cap",
                        "fr_gate8_sustained_negative", "checkpoint_adr_distance");
    }

    @Test
    void noSharedAssetProducesNoCrossings() {
        ObjectNode previous = JSON.createObjectNode();
        ObjectNode next = JSON.createObjectNode();
        next.putObject("macro");
        var result = SnapshotPanels.tripwireDiff(previous, next, JSON.createObjectNode());
        assertThat(result.path("n_crossings").asInt()).isZero();
    }
}
