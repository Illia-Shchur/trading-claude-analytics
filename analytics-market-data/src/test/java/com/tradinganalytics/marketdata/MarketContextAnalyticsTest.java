package com.tradinganalytics.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketContextAnalyticsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void onchainBlockFailsClosedThenBuildsReconstructedPanel() {
        assertThat(MarketContextAnalytics.onchainDistributionBlock(JSON.createArrayNode()).path("available").asBoolean())
                .isFalse();
        ArrayNode rows = JSON.createArrayNode();
        for (int index = 0; index < 31; index++) {
            rows.addObject()
                    .put("time", "2026-07-" + String.format("%02d", index + 1))
                    .put("CapMVRVCur", 1.5 + index / 100.0)
                    .put("CapMrktCurUSD", 1000 + index * 10)
                    .put("FlowInExUSD", 20 + index)
                    .put("FlowOutExUSD", 10 + index)
                    .put("SplyExNtv", 100 - index)
                    .put("SplyCur", 1000);
        }
        var block = MarketContextAnalytics.onchainDistributionBlock(rows);
        assertThat(block.path("available").asBoolean()).isTrue();
        assertThat(block.path("history_days").asInt()).isEqualTo(31);
        assertThat(block.path("exchange_flows").path("days_used").asInt()).isEqualTo(30);
        assertThat(block.path("lth").path("status").asText()).isEqualTo("PROVIDER_GATED");
    }

    @Test
    void premiumRequiresThreeAlignedDaysAndTracksNegativeRun() throws Exception {
        ArrayNode cb = (ArrayNode) JSON.readTree("[{\"date\":\"2026-01-01\",\"close\":99},{\"date\":\"2026-01-02\",\"close\":98},{\"date\":\"2026-01-03\",\"close\":97}]");
        ArrayNode bn = (ArrayNode) JSON.readTree("[{\"date\":\"2026-01-01\",\"close\":100},{\"date\":\"2026-01-02\",\"close\":100},{\"date\":\"2026-01-03\",\"close\":100}]");
        ArrayNode usdt = (ArrayNode) JSON.readTree("[{\"date\":\"2026-01-01\",\"close\":1},{\"date\":\"2026-01-02\",\"close\":1},{\"date\":\"2026-01-03\",\"close\":1}]");
        var block = MarketContextAnalytics.coinbasePremiumBlock(cb, bn, usdt);
        assertThat(block.path("available").asBoolean()).isTrue();
        assertThat(block.path("negative_3_completed_days").asBoolean()).isTrue();
        assertThat(block.path("consecutive_negative_completed_days").asInt()).isEqualTo(3);
        assertThat(MarketContextAnalytics.coinbasePremiumBlock(JSON.createArrayNode(), bn, usdt)
                .path("available").asBoolean()).isFalse();
    }

    @Test
    void oiPanelNeedsEnoughDistinctDaysAndUsesDailyMaximum() {
        ArrayNode rows = JSON.createArrayNode();
        for (int index = 0; index < 80; index++) {
            String date = java.time.LocalDate.of(2026, 1, 1).plusDays(index).toString();
            rows.addObject().put("date", date).put("sum_open_interest_value", 100 + index);
            rows.addObject().put("date", date).put("sum_open_interest_value", 90 + index);
        }
        var block = MarketContextAnalytics.oi90dBlock(rows);
        assertThat(block.path("available").asBoolean()).isTrue();
        assertThat(block.path("history_days").asInt()).isEqualTo(80);
        assertThat(block.path("high_90d_usd").asDouble()).isEqualTo(179);
    }

    @Test
    void breadthChecksCoverageBeforeComputingTheFraction() throws Exception {
        ArrayNode rows = (ArrayNode) JSON.readTree("[{\"close\":11,\"sma200\":10},{\"close\":9,\"sma200\":10},{\"close\":10,\"sma200\":10}]");
        assertThat(MarketContextAnalytics.breadth200Block(rows, 3.0, "2026-01-01", 95)
                .path("pct_above_200dma").asDouble()).isEqualTo(33.33);
        assertThat(MarketContextAnalytics.breadth200Block(rows, 10.0, "2026-01-01", 95)
                .path("available").asBoolean()).isFalse();
    }

    @Test
    void sentimentProxiesRemainExplicitlyUnscored() {
        List<Double> vol = List.of(10d, 11d, 12d, 13d, 14d);
        List<Double> cef = new ArrayList<>();
        List<Double> reference = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            cef.add(100.0 + index);
            reference.add(100.0);
        }
        var block = MarketContextAnalytics.sentimentProxyBlock(vol, cef, reference, 5, 10);
        assertThat(block.path("scored").asBoolean()).isFalse();
        assertThat(block.path("vol_index").path("last").asDouble()).isEqualTo(14);
        assertThat(block.path("cef_premium").path("sign").asText()).isEqualTo("PREMIUM");
    }
}
