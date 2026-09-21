package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public synthetic-manifest reopener: valid fixture first, then independently resealed mutations. */
class LiquidationSyntheticManifestReopenMatrixV1Test {
    private static final List<String> ROLES = List.of("feature", "label", "execution", "mark", "funding", "metadata");
    private static final long SOURCE_START = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
    private static final long DECISION = Instant.parse("2022-11-11T00:00:00Z").toEpochMilli();
    private static final long EXECUTION_END = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli();

    @TempDir Path temporary;

    @Test
    void acceptedSixRoleSyntheticManifestReopensWithoutPitOrAuthoritativeClaims() throws Exception {
        Fixture fixture = fixture("positive");
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(fixture.options());
        assertTrue(manifest.path("coverage_scope").asText().equals("SYNTHETIC_FIXTURE_ONLY"));
        assertFalse(manifest.path("development_replay_permitted").asBoolean());
        assertFalse(manifest.path("authoritative_evaluation_permitted").asBoolean());
        assertDoesNotThrow(() -> LiquidationV2PhysicalDataV1.verifySyntheticDevelopment(withManifest(fixture.options(), manifest)));
    }

    @Test
    void topLevelIdentityAndCapabilityClaimsFailBeforeAnyRoleIsReopened() throws Exception {
        Fixture fixture = fixture("top-level");
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(fixture.options());
        String topError = "unsupported, modified, or falsely authoritative v002 physical manifest";
        reject(fixture, manifest, topError, row -> row.put("schema", "liquidation-v2-physical-manifest/0"));
        reject(fixture, manifest, topError, row -> row.put("version", 2));
        reject(fixture, manifest, topError, row -> row.put("status", "SEALED"));
        reject(fixture, manifest, topError, row -> row.put("source_mode", "PROXY_DISCLOSED_DEVELOPMENT_ONLY"));
        reject(fixture, manifest, topError, row -> row.put("coverage_scope", "FULL_HISTORICAL"));
        reject(fixture, manifest, topError, row -> row.put("full_historical_hydration_claimed", true));
        reject(fixture, manifest, topError, row -> row.put("authoritative", true));
        reject(fixture, manifest, topError, row -> row.put("authoritative_evaluation_permitted", true));
        reject(fixture, manifest, topError, row -> row.put("development_replay_permitted", true));
        reject(fixture, manifest, topError, row -> row.put("profile_sha256", sha("other-profile")));
        reject(fixture, manifest, topError, row -> row.put("precommit_sha256", sha("other-precommit")));

        ObjectNode staleHash = manifest.deepCopy();
        staleHash.put("content_sha256", sha("stale-manifest"));
        expect(fixture.options(), staleHash, topError);
    }

    @Test
    void frozenPlanAndExactSixRoleInventoryAreReopenedAsPartOfManifest() throws Exception {
        Fixture fixture = fixture("frozen-plan");
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(fixture.options());
        reject(fixture, manifest, "v002 plan is not the frozen 60-day/67-day/7-day capability-bound plan",
                row -> ((ObjectNode) row.path("plan")).put("minimum_outer_purge_days", 60));
        reject(fixture, manifest, "v002 role set must be complete and exact",
                row -> ((ObjectNode) row.path("artifacts")).remove("funding"));
        reject(fixture, manifest, "v002 role set must be complete and exact",
                row -> ((ObjectNode) row.path("artifacts")).put("unexpected", JsonHashes.mapper().createObjectNode()));
    }

    @Test
    void partitionIdentityPathsSourceRowsAndAvailabilityScopeMustMatchReopenedBytes() throws Exception {
        Fixture fixture = fixture("partition-identity");
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(fixture.options());
        String roleError = "invalid v002 role metadata: feature";
        reject(fixture, manifest, roleError, row -> artifact(row, "feature").put("role", "LABEL"));
        reject(fixture, manifest, roleError, row -> artifact(row, "feature").put("format", "JSONL"));
        reject(fixture, manifest, roleError, row -> artifact(row, "feature").put("storage_role", "PUBLIC_ARCHIVE"));
        reject(fixture, manifest, roleError, row -> artifact(row, "feature").put("authoritative", true));
        reject(fixture, manifest, "v002 role paths are not distinct", row ->
                artifact(row, "label").put("path", artifact(row, "feature").path("path").asText()));
        reject(fixture, manifest, "v002 source input changed: feature", row ->
                artifact(row, "feature").put("input_byte_sha256", sha("different-raw-source")));
        reject(fixture, manifest, "v002 input rows or receipt-bound availability inventory changed: feature", row ->
                artifact(row, "feature").put("input_content_sha256", sha("different-normalized-values")));
        reject(fixture, manifest, "v002 input rows or receipt-bound availability inventory changed: feature", row ->
                artifact(row, "feature").putArray("series_scope").add("daily_liquidations_usd"));
        reject(fixture, manifest, "v002 input rows or receipt-bound availability inventory changed: feature", row ->
                artifact(row, "feature").put("availability_basis", "MODELED_NEXT_COMPLETED_NYSE_SESSION"));
    }

    @Test
    void parquetBytesRowsSchemaAndDatasetRootAreAllComparedAfterReopen() throws Exception {
        Fixture fixture = fixture("parquet-reopen");
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(fixture.options());
        reject(fixture, manifest, "v002 Parquet bytes changed: feature", row ->
                artifact(row, "feature").put("sha256", sha("wrong-parquet-bytes")));
        reject(fixture, manifest, "v002 Parquet bytes changed: feature", row ->
                artifact(row, "feature").put("bytes", artifact(row, "feature").path("bytes").asLong() + 1));
        reject(fixture, manifest, "v002 physical role fails source/Parquet reopen: feature", row ->
                artifact(row, "feature").put("row_count", artifact(row, "feature").path("row_count").asInt() + 1));
        reject(fixture, manifest, "v002 physical role fails source/Parquet reopen: feature", row ->
                artifact(row, "feature").put("rows_sha256", sha("wrong-reopened-rows")));
        reject(fixture, manifest, "v002 physical role fails source/Parquet reopen: feature", row ->
                artifact(row, "feature").put("schema_sha256", sha("wrong-parquet-schema")));

        ObjectNode badRoot = manifest.deepCopy();
        badRoot.put("dataset_root_sha256", sha("different-six-role-root"));
        badRoot.put("content_sha256", JsonHashes.ownHash(badRoot));
        expect(fixture.options(), badRoot, "v002 physical dataset root hash mismatch");
    }

    @Test
    void actualSourceAndParquetTamperingIsDetectedRatherThanMaskedByManifestResealing() throws Exception {
        Fixture source = fixture("source-tamper");
        ObjectNode sourceManifest = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(source.options());
        Path sourcePath = source.root().resolve(sourceManifest.path("artifacts").path("feature").path("input_path").asText());
        Files.writeString(sourcePath, "{}\n");
        expect(source.options(), sourceManifest, "v002 source input changed: feature");

        Fixture parquet = fixture("parquet-tamper");
        ObjectNode parquetManifest = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(parquet.options());
        Path parquetPath = parquet.root().resolve(parquetManifest.path("artifacts").path("feature").path("path").asText());
        byte[] bytes = Files.readAllBytes(parquetPath);
        bytes[0] ^= 1;
        Files.write(parquetPath, bytes);
        expect(parquet.options(), parquetManifest, "v002 Parquet bytes changed: feature");
    }

    private void reject(Fixture fixture, ObjectNode baseline, String message, Consumer<ObjectNode> mutation) {
        ObjectNode changed = baseline.deepCopy();
        mutation.accept(changed);
        changed.put("content_sha256", JsonHashes.ownHash(changed));
        expect(fixture.options(), changed, message);
    }

    private void expect(ObjectNode options, ObjectNode manifest, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.verifySyntheticDevelopment(withManifest(options, manifest)));
        assertTrue(error.getMessage().contains(message), "expected [" + message + "], got [" + error.getMessage() + "]");
    }

    private Fixture fixture(String id) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(id)).toRealPath();
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        addInput(root, inputs, "feature", List.of(
                dailyRow("BTC", "BTCUSDT_PERP.A", "LONG", SOURCE_START, 100),
                priceRow(SOURCE_START)));
        addInput(root, inputs, "label", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("episode_id", "manifest-fixture").put("decision_time", DECISION).put("label", "UNINSPECTED")));
        addInput(root, inputs, "execution", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("open_time", DECISION).put("open", 100).put("high", 101).put("low", 99).put("close", 100)
                .put("base_volume", 10)));
        addInput(root, inputs, "mark", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("timestamp", DECISION).put("open", 100).put("high", 101).put("low", 99).put("close", 100)));
        addInput(root, inputs, "funding", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("settlement_time", DECISION + 7).put("event_id", "fixture-funding")
                .put("funding_rate", 0).put("mark_price", 100).put("funding_interval_hours", 8)));
        addInput(root, inputs, "metadata", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("tier_index", 1).put("effective_from", SOURCE_START).put("effective_until", EXECUTION_END)
                .put("lot_size", 0.001).put("minimum_notional", 5).put("taker_fee_rate", 0.0005)
                .put("slippage_rate", 0).put("liquidation_fee_rate", 0.01).put("tier_notional_cap", 1_000_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true)));
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        options.set("profile", LiquidationDailyStressProfileV1.frozenContract());
        options.set("inputs", inputs);
        return new Fixture(root, options);
    }

    private static void addInput(Path root, ObjectNode inputs, String role, List<ObjectNode> rows) throws Exception {
        byte[] bytes = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl(role, rows);
        String relative = "inputs/" + role + ".jsonl";
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        inputs.putObject(role).put("path", relative).put("sha256", JsonHashes.sha256(bytes));
    }

    private static ObjectNode dailyRow(String asset, String symbol, String side, long time, double value) {
        return JsonHashes.mapper().createObjectNode().put("asset", asset).put("symbol", symbol)
                .put("series_id", "daily_liquidation_usd").put("timeframe", "1d").put("side", side)
                .put("event_time", time).put("availability_time", time + 172_800_000L).put("value", value);
    }

    private static ObjectNode priceRow(long time) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "price_ohlc").put("timeframe", "4h").put("event_time", time)
                .put("availability_time", time + 14_400_000L).put("open", 99).put("high", 101)
                .put("low", 98).put("close", 100).put("base_volume", 10);
    }

    private static ObjectNode artifact(ObjectNode manifest, String role) {
        return (ObjectNode) manifest.path("artifacts").path(role);
    }

    private static ObjectNode withManifest(ObjectNode base, ObjectNode manifest) {
        ObjectNode options = base.deepCopy();
        options.set("manifest", manifest.deepCopy());
        return options;
    }

    private static String sha(String value) { return JsonHashes.sha256(value); }
    private record Fixture(Path root, ObjectNode options) {}
}
