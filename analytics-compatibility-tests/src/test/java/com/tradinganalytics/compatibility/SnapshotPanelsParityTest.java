package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.marketdata.SnapshotPanels;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class SnapshotPanelsParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIXTURE = """
            {
              "proximity": {
                "spot": {"canonical": 80},
                "high_1y": {"pct_below": 22, "value": 100},
                "weekly": {"sma_200w": {"pct_vs_spot": 9, "value": 73.4}, "rsi14": {"rsi": 39}},
                "trend": {"insufficient": null, "ma200_slope20_pct": 0.3},
                "sentiment": {"avg_3d": 24}
              },
              "previous": {
                "btc": {
                  "spot": {"canonical": 80},
                  "sentiment": {"avg_3d": 49, "streaks_daily_prints": {"le15": 6}},
                  "weekly": {"rsi14": {"rsi": 59}, "sma_200w": {"within_8pct": false}},
                  "trend": {"insufficient": null, "ma200_falling": true, "price_below_ma200": true,
                    "bounce_pct": 12, "rsi14": 44, "bounce_age_sessions": 7},
                  "high_1y": {"pct_below": 21},
                  "funding": {"mean_annualized_pct": -1, "sustained3_below_minus5": false},
                  "daily": {"adr5": {"adr": 4}}
                },
                "eth": {
                  "sentiment": {"avg_3d": 20},
                  "weekly": {"rsi14": {"rsi": 40}},
                  "trend": {"insufficient": null, "bounce_pct": 9, "rsi14": 44, "bounce_age_sessions": 9},
                  "high_1y": {"pct_below": 30}
                }
              },
              "next": {
                "btc": {
                  "spot": {"canonical": 87},
                  "sentiment": {"avg_3d": 61, "streaks_daily_prints": {"le15": 7}},
                  "weekly": {"rsi14": {"rsi": 66}, "sma_200w": {"within_8pct": true}},
                  "trend": {"insufficient": null, "ma200_falling": true, "price_below_ma200": true,
                    "bounce_pct": 19, "rsi14": 53, "bounce_age_sessions": 8},
                  "high_1y": {"pct_below": 19},
                  "funding": {"mean_annualized_pct": 1, "sustained3_below_minus5": true},
                  "daily": {"adr5": {"adr": 4}}
                },
                "eth": {
                  "sentiment": {"avg_3d": 20},
                  "weekly": {"rsi14": {"rsi": 49}},
                  "trend": {"insufficient": null, "bounce_pct": 9, "rsi14": 53, "bounce_age_sessions": 9},
                  "high_1y": {"pct_below": 30}
                },
                "macro": {"ignored": true}
              },
              "checkpoints": {"btc": {"line": 90}}
            }
            """;

    @Test
    void proximityAndTripwirePanelsMatchFrozenWireContract() throws Exception {
        JsonNode expected;
        try (InputStream stream = getClass().getResourceAsStream("/oracles/snapshot-panels-v1.json")) {
            assertThat(stream).isNotNull();
            expected = JSON.readTree(stream);
        }
        JsonNode fixture = JSON.readTree(FIXTURE);

        ObjectNode actual = JSON.createObjectNode();
        actual.set("proximity", SnapshotPanels.proximityPanel(fixture.get("proximity")));
        actual.set("proximity_empty", SnapshotPanels.proximityPanel(null));
        actual.set("tripwire", SnapshotPanels.tripwireDiff((ObjectNode) fixture.get("previous"),
                (ObjectNode) fixture.get("next"), (ObjectNode) fixture.get("checkpoints")));
        assertThat(NodePrettyJson.write(actual)).isEqualTo(NodePrettyJson.write(expected));
    }
}
