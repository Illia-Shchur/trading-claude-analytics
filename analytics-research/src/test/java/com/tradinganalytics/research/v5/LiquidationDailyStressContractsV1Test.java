package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class LiquidationDailyStressContractsV1Test {
    @TempDir Path temporary;

    @Test
    void profileFreezesSixtyDayLifecycleFourCoreVariantsAndNonPromotionBoundary() {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();

        assertTrue(LiquidationDailyStressProfileV1.validate(profile));
        assertTrue(ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(profile));
        assertEquals(60, profile.path("windows").path("maximum_lifecycle_days").asInt());
        assertEquals(67, profile.path("fold_contract").path("outer_and_inner_purge_days").asInt());
        assertEquals(7, profile.path("fold_contract").path("embargo_days").asInt());
        assertEquals(4, profile.path("declared_core_variant_count").asInt());
        assertFalse(profile.path("authoritative_wfo_permitted").asBoolean());
        assertFalse(profile.path("trade_authorization_permitted").asBoolean());
        assertEquals("liquidation-v2-physical-parquet-v1", profile.path("executor_capability").asText());
    }

    @Test
    void profileRejectsLegacyThirtyDayOverrideEvenWhenCallerRehashesIt() {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ((ObjectNode) profile.path("windows")).put("maximum_lifecycle_days", 30);
        profile.put("content_sha256", JsonHashes.ownHash(profile));

        assertThrows(IllegalArgumentException.class, () -> LiquidationDailyStressProfileV1.validate(profile));
    }

    @Test
    void qualificationInventoryRejectsCallerVerifiedAndRequiredScopeEditsAfterRehash() {
        ObjectNode receipt = fixtureReceipt();
        LiquidationInputQualificationV1.validateReceipt(receipt);

        ((ObjectNode) receipt.path("inputs").get(0)).put("qualification_status", "VERIFIED");
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        assertThrows(IllegalArgumentException.class, () -> LiquidationInputQualificationV1.validateReceipt(receipt));

        ObjectNode changedRequired = fixtureReceipt();
        ((ObjectNode) changedRequired.path("inputs").get(0)).put("required", false);
        changedRequired.put("content_sha256", JsonHashes.ownHash(changedRequired));
        ObjectNode finalReceipt = changedRequired;
        assertThrows(IllegalArgumentException.class, () -> LiquidationInputQualificationV1.validateReceipt(finalReceipt));
    }

    @Test
    void qualificationCannotBeRehashedToClaimCoverageOrChangeTheFrozenDates() {
        ObjectNode receipt = fixtureReceipt();
        ((ObjectNode) receipt.path("inputs").get(0)).put("coverage_sufficient_for_intended_use", true);
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        ObjectNode covered = receipt;
        assertThrows(IllegalArgumentException.class, () -> LiquidationInputQualificationV1.validateReceipt(covered));

        ObjectNode shifted = fixtureReceipt();
        shifted.put("decision_window_end_exclusive", "2026-08-15T00:00:00Z");
        shifted.put("content_sha256", JsonHashes.ownHash(shifted));
        assertThrows(IllegalArgumentException.class, () -> LiquidationInputQualificationV1.validateReceipt(shifted));
    }

    @Test
    void v002PhysicalAdapterBuildsAndReopensSixDistinctParquetRolesWithoutLegacyPlanRouting() throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode plan = LiquidationV2PhysicalDataV1.frozenPlan(profile);
        assertEquals(LiquidationV2PhysicalDataV1.PLAN_SCHEMA, plan.path("schema").asText());
        assertEquals(67, plan.path("minimum_outer_purge_days").asInt());
        assertEquals(67, plan.path("minimum_inner_purge_days").asInt());
        assertEquals(7, plan.path("embargo_days").asInt());
        assertEquals(60, plan.path("max_lifecycle_days").asInt());

        Map<String, ObjectNode> rows = new LinkedHashMap<>();
        rows.put("feature", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "price_ohlc").put("timeframe", "4h").put("event_time", 1_680_307_200_000L)
                .put("availability_time", 1_680_321_600_000L).put("open", 99).put("high", 101).put("low", 98).put("close", 100));
        rows.put("label", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("episode_id", "synthetic-1")
                .put("decision_time", 1_680_000_000_000L).put("label", "UNINSPECTED"));
        rows.put("execution", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("open_time", 1_680_000_000_000L)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("base_volume", 50));
        rows.put("mark", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("timestamp", 1_680_000_000_000L)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100));
        rows.put("funding", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("settlement_time", 1_680_000_000_000L)
                .put("event_id", "synthetic-funding-0").put("funding_rate", 0).put("mark_price", 100));
        ObjectNode metadata = JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", "1").put("effective_from", 1_679_900_000_000L)
                .put("effective_until", 1_680_100_000_000L).put("lot_size", 0.001).put("minimum_notional", 5)
                .put("taker_fee_rate", 0.0005).put("slippage_rate", 0).put("liquidation_fee_rate", 0)
                .put("tier_notional_cap", 100_000).put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0)
                .put("terminal_tier", true);
        rows.put("metadata", metadata);
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        for (Map.Entry<String, ObjectNode> entry : rows.entrySet()) {
            String path = "inputs/" + entry.getKey() + ".jsonl";
            Path file = temporary.resolve(path); Files.createDirectories(file.getParent());
            byte[] bytes = (JsonHashes.canonicalString(entry.getValue()) + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            inputs.putObject(entry.getKey()).put("path", path).put("sha256", JsonHashes.sha256(bytes));
        }

        ObjectNode build = JsonHashes.mapper().createObjectNode().put("root", temporary.toString());
        build.set("profile", profile); build.set("inputs", inputs);
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(build);
        assertEquals("DEVELOPMENT_SYNTHETIC", manifest.path("status").asText());
        assertEquals(6, manifest.path("artifacts").size());
        assertFalse(manifest.path("authoritative").asBoolean());
        ObjectNode verify = JsonHashes.mapper().createObjectNode().put("root", temporary.toString());
        verify.set("profile", profile); verify.set("manifest", manifest);
        ObjectNode verified = LiquidationV2PhysicalDataV1.verifySyntheticDevelopment(verify);
        assertEquals("REOPENED_DEVELOPMENT_ONLY", verified.path("status").asText());
        assertEquals(1, LiquidationV2PhysicalDataV1.readFeatureRows(verify).size());
        LiquidationV2PhysicalDataV1.VerifiedDevelopmentSession session = LiquidationV2PhysicalDataV1.openVerifiedDevelopment(verify);
        assertEquals(1, LiquidationV2PhysicalDataV1.readRoleRows(session, "label",
                "2023-03-28T00:00:00Z", "2023-03-29T00:00:00Z", java.util.List.of("BTC")).size());
        assertFalse(LiquidationInputQualificationV1.assessPhysicalDevelopment(verify).path("development_replay_permitted").asBoolean(true));

        ObjectNode forged = manifest.deepCopy();
        forged.put("authoritative", true);
        forged.put("content_sha256", JsonHashes.ownHash(forged));
        verify.set("manifest", forged);
        ObjectNode forgedManifest = forged;
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2PhysicalDataV1.verifySyntheticDevelopment(verify));
    }

    @Test
    void v002PhysicalAdapterRejectsCallerAuthoredFillsAndFeatureOutcomeLeakage() {
        ObjectNode fill = JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("open_time", 1_680_000_000_000L)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("base_volume", 50).put("fill_price", 100);
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2PhysicalDataV1.validateRows("execution", java.util.List.of(fill)));
        ObjectNode feature = JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("series_id", "price_ohlc").put("timeframe", "4h")
                .put("event_time", 1_680_000_000_000L).put("availability_time", 1_680_014_400_000L)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("outcome", 1);
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2PhysicalDataV1.validateRows("feature", java.util.List.of(feature)));
    }

    private static ObjectNode fixtureReceipt() {
        ObjectNode precommit = JsonHashes.mapper().createObjectNode().put("precommit_id", "liquidation-daily-stress-v002")
                .put("content_sha256", "a".repeat(64));
        ObjectNode acquisition = JsonHashes.mapper().createObjectNode().put("captured_at", "2026-09-20T09:53:53Z")
                .put("content_sha256", "b".repeat(64));
        byte[] precommitBytes = JsonHashes.canonicalBytes(precommit);
        byte[] acquisitionBytes = JsonHashes.canonicalBytes(acquisition);
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        return LiquidationInputQualificationV1.createReceipt(precommit, Path.of("frozen-precommit.json"),
                precommitBytes, acquisition, Path.of("coinalyze-acquisition.json"), acquisitionBytes, rows);
    }
}
