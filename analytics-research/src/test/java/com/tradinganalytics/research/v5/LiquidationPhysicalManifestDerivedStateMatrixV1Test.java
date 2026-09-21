package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Accepted proxy fixture followed by mutations of independently recomputed manifest state. */
class LiquidationPhysicalManifestDerivedStateMatrixV1Test {
    private static final long SOURCE_START = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
    private static final long DECISION_START = Instant.parse("2022-11-11T00:00:00Z").toEpochMilli();
    private static final long EXECUTION_END = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli();

    @TempDir Path temporary;

    @Test
    void acceptedProxyManifestReopensBeforeEachEnvelopeAndFrozenPlanMutation() throws Exception {
        Fixture fixture = fixture("envelope");
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        ObjectNode valid = withManifest(fixture.options(), manifest);
        assertDoesNotThrow(() -> LiquidationV2PhysicalDataV1.verifyDevelopment(valid));

        String envelope = "unsupported, modified, or falsely authoritative v002 development manifest";
        reject(fixture, manifest, envelope, row -> row.put("schema", "old/0"));
        reject(fixture, manifest, envelope, row -> row.put("version", 2));
        reject(fixture, manifest, envelope, row -> row.put("status", "SEALED"));
        reject(fixture, manifest, envelope, row -> row.put("authoritative", true));
        reject(fixture, manifest, envelope, row -> row.put("source_qualification_permitted", true));
        reject(fixture, manifest, envelope, row -> row.put("authoritative_evaluation_permitted", true));
        reject(fixture, manifest, envelope, row -> row.put("full_historical_hydration_claimed", true));
        reject(fixture, manifest, envelope, row -> row.put("profile_sha256", sha("another-profile")));
        reject(fixture, manifest, envelope, row -> row.put("precommit_sha256", sha("another-precommit")));

        reject(fixture, manifest, "v002 development manifest is not bound to the frozen physical plan",
                row -> row.put("plan_sha256", sha("another-plan")));
        reject(fixture, manifest, "v002 development manifest is not bound to the frozen physical plan",
                row -> ((ObjectNode) row.path("plan")).put("content_sha256", sha("another-plan-content")));
        reject(fixture, manifest, "v002 development manifest is not bound to the frozen physical plan",
                row -> ((ObjectNode) row.path("plan")).put("minimum_inner_purge_days", 50));
        reject(fixture, manifest, "v002 development manifest must have all six physical roles",
                row -> ((ObjectNode) row.path("artifacts")).remove("funding"));
        reject(fixture, manifest, "v002 development manifest must have all six physical roles",
                row -> ((ObjectNode) row.path("artifacts")).put("unused", JsonHashes.mapper().createObjectNode()));

        ObjectNode staleHash = manifest.deepCopy().put("content_sha256", sha("stale-content"));
        expect(fixture, staleHash, envelope);
    }

    @Test
    void everyRolePartitionInventoryAndPhysicalPathUniquenessAreReopened() throws Exception {
        Fixture fixture = fixture("partition-inventory");
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        reject(fixture, manifest, "invalid v002 role partition inventory: feature",
                row -> ((ObjectNode) row.path("artifacts").path("feature")).put("role", "LABEL"));
        reject(fixture, manifest, "invalid v002 role partition inventory: feature",
                row -> ((ObjectNode) row.path("artifacts").path("feature")).put("format", "JSONL"));
        reject(fixture, manifest, "invalid v002 role partition inventory: feature",
                row -> ((ObjectNode) row.path("artifacts").path("feature")).put("partition_count", 9));
        reject(fixture, manifest, "invalid v002 role partition inventory: feature",
                row -> ((ObjectNode) row.path("artifacts").path("feature")).set("partitions", JsonHashes.mapper().createObjectNode()));
        reject(fixture, manifest, "invalid v002 role partition inventory: feature",
                row -> ((ObjectNode) row.path("artifacts").path("feature")).putArray("partitions"));

        reject(fixture, manifest, "v002 physical partition paths are not distinct", row -> {
            ArrayNode entries = (ArrayNode) row.path("artifacts").path("feature").path("partitions");
            ((ObjectNode) entries.get(1)).put("path", entries.get(0).path("path").asText());
        });
        reject(fixture, manifest, "v002 physical partition paths are not distinct", row -> {
            ObjectNode entry = firstFeature(row);
            entry.put("normalized_path", entry.path("source_path").asText());
        });
        reject(fixture, manifest, "v002 physical partition paths are not distinct", row -> {
            ArrayNode entries = (ArrayNode) row.path("artifacts").path("feature").path("partitions");
            ((ObjectNode) entries.get(1)).put("normalized_path", entries.get(0).path("normalized_path").asText());
        });
    }

    @Test
    void rawNormalizedReceiptAndNormalizationSummaryMustAgreeAcrossReopen() throws Exception {
        Fixture fixture = fixture("receipt-summary");
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        String receiptBytes = "feature source, normalized, or normalization receipt bytes changed";
        reject(fixture, manifest, receiptBytes, row -> firstFeature(row).put("normalized_byte_sha256", sha("other-normalized")));
        reject(fixture, manifest, receiptBytes, row -> firstFeature(row).put("source_byte_sha256", sha("other-source")));
        reject(fixture, manifest, receiptBytes, row -> firstFeature(row).put("normalization_receipt_byte_sha256", sha("other-receipt")));
        reject(fixture, manifest, "source classification or transformation differs from reopened normalization receipt",
                row -> firstFeature(row).put("source_class", "PUBLIC_ARCHIVE"));
        reject(fixture, manifest, "source classification or transformation differs from reopened normalization receipt",
                row -> firstFeature(row).put("normalizer_id", "another-copy-normalizer/1"));
        reject(fixture, manifest, "source classification or transformation differs from reopened normalization receipt",
                row -> firstFeature(row).put("normalizer_sha256", sha("another-normalizer-hash")));
        reject(fixture, manifest, "source classification or transformation differs from reopened normalization receipt",
                row -> firstFeature(row).put("availability_basis", "MODELED_NEXT_COMPLETED_NYSE_SESSION"));
        reject(fixture, manifest, "source classification or transformation differs from reopened normalization receipt",
                row -> firstFeature(row).putArray("series_scope").add("sp500_close"));
        reject(fixture, manifest, "source classification or transformation differs from reopened normalization receipt",
                row -> firstFeature(row).put("transformation_status", "UNVERIFIED_TRANSFORMATION"));
    }

    @Test
    void coverageAndReadinessAreRecomputedFromRowsNotTrustedFromManifest() throws Exception {
        Fixture fixture = fixture("coverage-state");
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        reject(fixture, manifest, "v002 physical coverage differs from reopened rows: feature", row ->
                firstFeature(row).with("coverage").put("row_count", 99));
        reject(fixture, manifest, "v002 coverage summary does not match reopened physical rows", row ->
                ((ObjectNode) row.path("coverage").path("groups").get(0)).put("row_count", 99));

        String readiness = "v002 replay readiness is not derived from reopened coverage and source transforms";
        reject(fixture, manifest, readiness, row -> row.put("minimum_contiguous_hydration_proven", true));
        reject(fixture, manifest, readiness, row -> row.put("coverage_inventory_complete", true));
        reject(fixture, manifest, readiness, row -> row.put("all_source_transformations_recomputed", false));
        reject(fixture, manifest, readiness, row -> row.put("development_replay_permitted", true));
    }

    @Test
    void sourceModeIsValidatedAfterAllPhysicalRowsAndReceiptsHaveBeenReopened() throws Exception {
        Fixture fixture = fixture("source-mode");
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        reject(fixture, manifest, "unsupported v002 physical source mode", row -> row.put("source_mode", "UNREVIEWED"));
        reject(fixture, manifest, "synthetic development manifest has non-synthetic source receipts",
                row -> row.put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY"));
    }

    private void reject(Fixture fixture, ObjectNode baseline, String message, Consumer<ObjectNode> mutation) {
        ObjectNode changed = baseline.deepCopy();
        mutation.accept(changed);
        changed.put("content_sha256", JsonHashes.ownHash(changed));
        expect(fixture, changed, message);
    }

    private void expect(Fixture fixture, ObjectNode manifest, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.verifyDevelopment(withManifest(fixture.options(), manifest)));
        assertTrue(error.getMessage().contains(message), "expected [" + message + "], got [" + error.getMessage() + "]");
    }

    private Fixture fixture(String id) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(id)).toRealPath();
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        long dayEnd = SOURCE_START + 86_400_000L;
        addPartition(root, inputs, "feature", "liquidations", "COINALYZE_PROXY", List.of(dailyRow()), SOURCE_START, dayEnd, null);
        addPartition(root, inputs, "feature", "price", "PUBLIC_ARCHIVE", List.of(priceRow()), SOURCE_START, SOURCE_START + 14_400_000L, null);
        addPartition(root, inputs, "label", "labels", "DERIVED_OUTCOME_LABEL", List.of(JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("episode_id", "manifest-fixture").put("decision_time", DECISION_START).put("label", "UNINSPECTED")),
                DECISION_START, DECISION_START + 60_000L, null);
        addPartition(root, inputs, "execution", "execution", "PUBLIC_ARCHIVE", List.of(JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("open_time", DECISION_START).put("open", 100).put("high", 101).put("low", 99)
                .put("close", 100).put("base_volume", 10)), DECISION_START, DECISION_START + 60_000L, null);
        addPartition(root, inputs, "mark", "mark", "PUBLIC_ARCHIVE", List.of(JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("timestamp", DECISION_START).put("open", 100).put("high", 101).put("low", 99)
                .put("close", 100)), DECISION_START, DECISION_START + 60_000L, null);
        addPartition(root, inputs, "funding", "funding", "PUBLIC_ARCHIVE", List.of(JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("settlement_time", DECISION_START + 7).put("event_id", "manifest-funding")
                .put("funding_rate", 0).put("mark_price", 100).put("funding_interval_hours", 8)),
                DECISION_START, DECISION_START + 8L * 3_600_000L, null);
        ObjectNode metadata = JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", 1)
                .put("effective_from", SOURCE_START).put("effective_until", EXECUTION_END).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0.0001)
                .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", 1_000_000_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true);
        ObjectNode assumption = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-metadata-assumption/1")
                .put("version", 1).put("source_class", "HISTORICAL_APPROXIMATION")
                .put("assumption_description", "Present contract fields are disclosed as a historical approximation.")
                .put("historical_availability_proven", false).put("historical_revision_proven", false).put("authoritative", false);
        addPartition(root, inputs, "metadata", "metadata", "PUBLIC_ARCHIVE", List.of(metadata), SOURCE_START, EXECUTION_END, assumption);
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        options.set("profile", LiquidationDailyStressProfileV1.frozenContract());
        options.set("inputs", inputs);
        return new Fixture(root, options);
    }

    private void addPartition(Path root, ObjectNode inputs, String role, String name, String sourceClass,
            List<ObjectNode> rows, long start, long end, ObjectNode assumptionTemplate) throws Exception {
        String normalizedPath = "normalized/" + name + ".jsonl", sourcePath = "raw/" + name + ".jsonl";
        String receiptPath = "receipts/" + name + ".json";
        byte[] normalized = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl(role, rows), source = normalized.clone();
        write(root, normalizedPath, normalized); write(root, sourcePath, source);
        ObjectNode receipt = LiquidationV2PhysicalDataV1.createRoleJsonlCopyReceipt(role, normalizedPath, normalized,
                sourcePath, source, Instant.ofEpochMilli(start).toString(), Instant.ofEpochMilli(end).toString(), sourceClass);
        byte[] receiptBytes = JsonHashes.canonicalBytes(receipt); write(root, receiptPath, receiptBytes);
        ObjectNode partition = JsonHashes.mapper().createObjectNode().put("path", normalizedPath)
                .put("sha256", JsonHashes.sha256(normalized)).put("source_path", sourcePath)
                .put("source_sha256", JsonHashes.sha256(source)).put("normalization_receipt_path", receiptPath)
                .put("normalization_receipt_sha256", JsonHashes.sha256(receiptBytes))
                .put("window_start", Instant.ofEpochMilli(start).toString()).put("window_end_exclusive", Instant.ofEpochMilli(end).toString());
        if (assumptionTemplate != null) {
            ObjectNode assumption = assumptionTemplate.deepCopy().put("source_path", sourcePath)
                    .put("source_byte_sha256", JsonHashes.sha256(source)).put("window_start", Instant.ofEpochMilli(start).toString())
                    .put("window_end_exclusive", Instant.ofEpochMilli(end).toString());
            assumption.put("content_sha256", JsonHashes.ownHash(assumption));
            String assumptionPath = "receipts/" + name + "-assumption.json";
            byte[] bytes = JsonHashes.canonicalBytes(assumption); write(root, assumptionPath, bytes);
            partition.put("assumption_path", assumptionPath).put("assumption_sha256", JsonHashes.sha256(bytes));
        }
        if (!inputs.has(role)) inputs.putObject(role).putArray("partitions");
        ((ArrayNode) inputs.path(role).path("partitions")).add(partition);
    }

    private static ObjectNode dailyRow() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT_PERP.A")
                .put("series_id", "daily_liquidation_usd").put("timeframe", "1d").put("side", "LONG")
                .put("event_time", SOURCE_START).put("availability_time", SOURCE_START + 172_800_000L).put("value", 100);
    }

    private static ObjectNode priceRow() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "price_ohlc").put("timeframe", "4h").put("event_time", SOURCE_START)
                .put("availability_time", SOURCE_START + 14_400_000L).put("open", 100).put("high", 101)
                .put("low", 99).put("close", 100).put("base_volume", 10);
    }

    private static ObjectNode firstFeature(ObjectNode manifest) {
        return (ObjectNode) manifest.path("artifacts").path("feature").path("partitions").get(0);
    }

    private static ObjectNode withManifest(ObjectNode base, ObjectNode manifest) {
        ObjectNode options = base.deepCopy(); options.set("manifest", manifest.deepCopy()); return options;
    }

    private static void write(Path root, String relative, byte[] bytes) throws Exception {
        Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.write(path, bytes);
    }

    private static String sha(String text) { return JsonHashes.sha256(text); }
    private record Fixture(Path root, ObjectNode options) {}
}
