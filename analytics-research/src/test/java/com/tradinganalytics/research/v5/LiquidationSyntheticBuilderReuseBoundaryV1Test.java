package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Immutable synthetic physical outputs may be reused only while their content-addressed bytes agree. */
class LiquidationSyntheticBuilderReuseBoundaryV1Test {
    private static final long SOURCE = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
    private static final long DECISION = Instant.parse("2022-11-11T00:00:00Z").toEpochMilli();
    private static final long END = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli();

    @TempDir Path temporary;

    @Test
    void repeatedBuildReopensIdenticalParquetAndExercisesSharedMetadataTierCoverage() throws Exception {
        ObjectNode options = fixture("repeat");
        ObjectNode first = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(options);
        ObjectNode second = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(options);

        assertEquals(JsonHashes.canonicalSha256(first), JsonHashes.canonicalSha256(second));
        ObjectNode metadata = (ObjectNode) first.path("artifacts").path("metadata");
        assertEquals(2, metadata.path("row_count").asInt());
        ObjectNode tierOne = metadata(1, 1_000, false);
        ObjectNode tierTwo = metadata(2, 2_000, true);
        ObjectNode coverage = LiquidationV2PhysicalDataV1.partitionCoverage("metadata", List.of(tierOne, tierTwo));
        ObjectNode group = (ObjectNode) coverage.path("groups").get(0);
        assertEquals(2, group.path("row_count").asInt());
        assertEquals(1, group.path("effective_intervals").size(),
                "two maintenance tiers at one effective interval contribute one coverage interval");

        options.set("manifest", second);
        assertEquals(6, LiquidationV2PhysicalDataV1.verifySyntheticDevelopment(options).path("role_count").asInt());
    }

    @Test
    void preexistingStagingBytesAndContentAddressedParquetRowsCannotBeReplaced() throws Exception {
        ObjectNode stagingOptions = fixture("staging-tamper");
        LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(stagingOptions);
        Path stagingDirectory = Path.of(stagingOptions.path("root").asText()).resolve("staging/liquidation-v2");
        Path featureStaging;
        try (var entries = Files.list(stagingDirectory)) {
            featureStaging = entries.filter(path -> path.getFileName().toString().startsWith("feature-"))
                    .findFirst().orElseThrow();
        }
        Files.writeString(featureStaging, "{}\n");
        IllegalArgumentException stagingError = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(stagingOptions));
        assertTrue(stagingError.getMessage().contains("immutable v002 staging path already contains different bytes"));

        ObjectNode parquetOptions = fixture("parquet-row-tamper");
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(parquetOptions);
        Path root = Path.of(parquetOptions.path("root").asText());
        Path feature = root.resolve(manifest.path("artifacts").path("feature").path("path").asText());
        Path label = root.resolve(manifest.path("artifacts").path("label").path("path").asText());
        Files.copy(label, feature, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        IllegalArgumentException parquetError = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(parquetOptions));
        assertTrue(parquetError.getMessage().contains("immutable v002 Parquet path contains different rows"));
    }

    private ObjectNode fixture(String id) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(id)).toRealPath();
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        addInput(root, inputs, "feature", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT_PERP.A")
                        .put("series_id", "daily_liquidation_usd").put("timeframe", "1d").put("side", "LONG")
                        .put("event_time", SOURCE).put("availability_time", SOURCE + 172_800_000L).put("value", 100),
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                        .put("series_id", "price_ohlc").put("timeframe", "4h").put("event_time", SOURCE)
                        .put("availability_time", SOURCE + 14_400_000L).put("open", 100).put("high", 101)
                        .put("low", 99).put("close", 100).put("base_volume", 10)));
        addInput(root, inputs, "label", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("episode_id", "builder-reuse").put("decision_time", DECISION).put("label", "UNINSPECTED")));
        addInput(root, inputs, "execution", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("open_time", DECISION).put("open", 100).put("high", 101).put("low", 99)
                .put("close", 100).put("base_volume", 10)));
        addInput(root, inputs, "mark", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("timestamp", DECISION).put("open", 100).put("high", 101).put("low", 99).put("close", 100)));
        addInput(root, inputs, "funding", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("settlement_time", DECISION + 7).put("event_id", "builder-reuse-funding")
                .put("funding_rate", 0).put("mark_price", 100).put("funding_interval_hours", 8)));
        ObjectNode metadataOne = metadata(1, 1_000, false);
        ObjectNode metadataTwo = metadata(2, 2_000, true);
        addInput(root, inputs, "metadata", List.of(metadataOne, metadataTwo));

        ObjectNode options = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        options.set("profile", LiquidationDailyStressProfileV1.frozenContract());
        options.set("inputs", inputs);
        return options;
    }

    private static ObjectNode metadata(int tier, double cap, boolean terminal) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", tier)
                .put("effective_from", SOURCE).put("effective_until", END).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0.0001)
                .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", cap)
                .put("maintenance_margin_rate", tier == 1 ? 0.005 : 0.01)
                .put("maintenance_deduction", 0).put("terminal_tier", terminal);
    }

    private static void addInput(Path root, ObjectNode inputs, String role, List<ObjectNode> rows) throws Exception {
        byte[] bytes = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl(role, rows);
        String relative = "inputs/" + role + ".jsonl";
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        inputs.putObject(role).put("path", relative).put("sha256", JsonHashes.sha256(bytes));
    }
}
