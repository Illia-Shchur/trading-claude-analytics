package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwingBackfillNodeOracleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void constantsAndEveryPureExportMatchCapturedNodeOracle() throws Exception {
        JsonNode oracle = fixture();
        assertThat(SwingBackfill.BAR_MS).isEqualTo(4L * 60 * 60 * 1000);
        assertThat(SwingBackfill.DAY_MS).isEqualTo(24L * 60 * 60 * 1000);
        assertThat(SwingBackfill.DATA_VISION_BASE).isEqualTo("https://data.binance.vision/data/futures/um/daily/metrics");
        ArrayNode duplicate = MAPPER.createArrayNode().add(object("time", 2).put("v", "first"))
                .add(object("time", 1)).add(object("time", 2).put("v", "second"));
        ArrayNode javaFirst = MAPPER.createArrayNode();
        SwingBackfill.firstByTime(duplicate).forEach((time, row) -> javaFirst.add(MAPPER.createArrayNode().add(time).add(row)));
        assertJson(javaFirst, oracle.path("first"));

        ArrayNode observations = MAPPER.createArrayNode().add(object("available_at", 200).put("v", 1))
                .add(object("available_at", 100).put("v", 2)).add(object("available_at", 200).put("v", 3));
        assertJson(SwingBackfill.latestPrior(observations, 201), oracle.path("latest"));

        ArrayNode rows = bars(330);
        ObjectNode trigger = SwingBackfill.mechanicalTrigger(rows, 80, "fallen_knives", null,
                rows.get(80).path("close").asDouble() - .5, 40);
        assertJson(trigger, oracle.path("trigger"));

        ObjectNode context = MAPPER.createObjectNode(); ObjectNode factors = context.putObject("factors");
        factors.putObject("technical").put("return_4h", .01).put("return_24h", -.02).put("return_24h_normalized", -.5)
                .put("return_3d_normalized", -1).put("close_location", .7).put("volume_z_90d", 1).put("return_3d_prior_percentile", .1).put("ema20", 100).put("ema50", 105);
        factors.putObject("derivatives").put("funding_mean_3d", -.001).put("oi_change_3d_pct", -.02).put("spot_cvd_24h_usd", -10)
                .put("futures_cvd_24h_usd", -20).put("futures_cvd_24h_z", -1).put("spot_futures_divergence_z", 1)
                .put("oi_change_24h_z", -1).put("funding_mean_24h_z", -1.5).put("top_vs_global_positioning_z", .7);
        factors.putObject("sentiment").put("fear_greed", 20).put("fear_greed_3d_change", 4);
        factors.putObject("relative").put("return_4h_vs_btc", .02);
        assertJson(SwingBackfill.setupFamiliesAt(rows, 80, "fallen_knives", null, trigger, context), oracle.path("families"));
        assertJson(SwingBackfill.labelsForBars(rows), oracle.path("labels"));
    }

    @Test
    void cacheOnlyBackfillMatchesCapturedNodeOracleWithoutNetwork() throws Exception {
        Path cache = temporary.resolve("cache"); Files.createDirectories(cache);
        Files.writeString(cache.resolve("text-fred-dxy-real-yield.csv"), "observation_date,DTWEXBGS,DFII10\n");
        Files.writeString(cache.resolve("json-alternative-fng-all.json"), "{\"data\":[]}");
        long now = Instant.parse("2026-08-22T12:00:00Z").toEpochMilli();
        JsonNode java = SwingBackfill.backfillAsset("sol", new SwingBackfill.Options(-1, cache, now));
        assertJson(java, fixture().path("backfill"));
    }

    private static ArrayNode bars(int count) {
        ArrayNode rows = MAPPER.createArrayNode(); long start = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli(); double close = 100;
        for (int index = 0; index < count; index++) { close += Math.sin(index / 7d) * .4 + .02;
            rows.add(MAPPER.createObjectNode().put("time", start + index * SwingBackfill.BAR_MS).put("open", close - .2)
                    .put("high", close + 1 + index % 3 * .1).put("low", close - 1 - index % 2 * .1).put("close", close)
                    .put("volume", 1000 + index).put("oi_open", 1000 + index).put("oi_close", 999 + index)); }
        return rows;
    }
    private static ObjectNode object(String key, long value) { return MAPPER.createObjectNode().put(key, value); }
    private static JsonNode fixture() throws Exception {
        try (InputStream input = SwingBackfillNodeOracleTest.class.getResourceAsStream("/oracles/swing-backfill-v1.json")) {
            assertThat(input).as("frozen Swing backfill oracle").isNotNull();
            return MAPPER.readTree(input);
        }
    }
    private static void assertJson(JsonNode actual, JsonNode expected) { assertThat(CanonicalJson.canonicalize(actual)).isEqualTo(CanonicalJson.canonicalize(expected)); }
}
