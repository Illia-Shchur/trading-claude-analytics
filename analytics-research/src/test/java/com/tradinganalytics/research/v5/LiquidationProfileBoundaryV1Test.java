package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Frozen profile validation and physical-assessment route checks for the non-authoritative synthetic lane. */
class LiquidationProfileBoundaryV1Test {
    @TempDir Path temporary;

    @Test
    void profileRejectsSchemaHashAndRehashedCapabilityEdits() {
        ObjectNode wrongSchema = LiquidationDailyStressProfileV1.frozenContract();
        wrongSchema.put("schema", "liquidation-daily-stress-profile/0");
        wrongSchema.put("content_sha256", JsonHashes.ownHash(wrongSchema));
        assertThrows(IllegalArgumentException.class, () -> LiquidationDailyStressProfileV1.validate(wrongSchema));

        ObjectNode badHash = LiquidationDailyStressProfileV1.frozenContract();
        badHash.put("content_sha256", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> LiquidationDailyStressProfileV1.validate(badHash));

        ObjectNode capabilityChange = LiquidationDailyStressProfileV1.frozenContract();
        capabilityChange.put("authoritative_wfo_permitted", true);
        capabilityChange.put("content_sha256", JsonHashes.ownHash(capabilityChange));
        assertThrows(IllegalArgumentException.class, () -> LiquidationDailyStressProfileV1.validate(capabilityChange));
    }

    @Test
    void physicalManifestAssessmentReopensSyntheticParquetAndStaysDevelopmentOnly() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("physical")).toRealPath();
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        Map<String, ObjectNode> rows = new LinkedHashMap<>();
        rows.put("feature", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "price_ohlc").put("timeframe", "4h").put("event_time", 1_680_307_200_000L)
                .put("availability_time", 1_680_321_600_000L).put("open", 99).put("high", 101).put("low", 98).put("close", 100));
        rows.put("label", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("episode_id", "fixture-1")
                .put("decision_time", 1_680_000_000_000L).put("label", "UNINSPECTED"));
        rows.put("execution", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("open_time", 1_680_000_000_000L)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("base_volume", 50));
        rows.put("mark", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("timestamp", 1_680_000_000_000L)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100));
        rows.put("funding", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("settlement_time", 1_680_000_000_000L)
                .put("event_id", "fixture-funding-1").put("funding_rate", 0).put("mark_price", 100));
        rows.put("metadata", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", "1")
                .put("effective_from", 1_679_900_000_000L).put("effective_until", 1_680_100_000_000L)
                .put("lot_size", 0.001).put("minimum_notional", 5).put("taker_fee_rate", 0.0005)
                .put("slippage_rate", 0).put("liquidation_fee_rate", 0).put("tier_notional_cap", 100_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true));

        for (Map.Entry<String, ObjectNode> entry : rows.entrySet()) {
            String relative = "inputs/" + entry.getKey() + ".jsonl";
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            byte[] bytes = (JsonHashes.canonicalString(entry.getValue()) + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            inputs.putObject(entry.getKey()).put("path", relative).put("sha256", JsonHashes.sha256(bytes));
        }
        ObjectNode build = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        build.set("profile", profile); build.set("inputs", inputs);
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(build);
        assertEquals("SYNTHETIC_DEVELOPMENT_ONLY", manifest.path("source_mode").asText());
        assertFalse(manifest.path("development_replay_permitted").asBoolean(true),
                "a small synthetic fixture cannot prove the frozen physical replay envelope");
        assertFalse(manifest.path("authoritative_evaluation_permitted").asBoolean(true));

        ObjectNode options = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        options.set("profile", profile); options.set("manifest", manifest);
        ObjectNode assessment = LiquidationDailyStressProfileV1.assessPhysicalManifest(profile, options);
        assertEquals("liquidation-v2-physical-manifest-assessment/1", assessment.path("schema").asText());
        assertEquals("REOPENED_DEVELOPMENT_ONLY", assessment.path("status").asText());
        assertEquals(6, assessment.path("role_count").asInt());
        assertEquals(60, assessment.path("duration_days").asInt());
        assertFalse(assessment.path("authoritative_evaluation_permitted").asBoolean(true));
        assertFalse(assessment.path("physical_verification").path("authoritative").asBoolean(true));

        ObjectNode noManifest = JsonHashes.mapper().createObjectNode();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> LiquidationDailyStressProfileV1.assessPhysicalManifest(profile, noManifest))
                .getMessage().contains("manifest must be an object"));
    }
}
