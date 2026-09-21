package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Positive and mutation vectors for the reopened six-role proxy manifest contract. */
class LiquidationPhysicalManifestReopenMatrixV1Test {
    private static final long SOURCE_START = epoch("2022-08-11T00:00:00Z");
    private static final long DECISION_START = epoch("2022-11-11T00:00:00Z");
    private static final long EXECUTION_END = epoch("2026-09-20T00:00:00Z");

    @TempDir Path temporary;

    @Test
    void positiveProxyManifestReopensAllRolesAndEveryRehashedManifestMutationFailsAtItsOwnGate() throws Exception {
        Fixture fixture = fixture(temporary.toRealPath().resolve("valid-proxy"), false);
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        assertEquals("PROXY_DISCLOSED_DEVELOPMENT_ONLY", manifest.path("source_mode").asText());
        assertEquals(7, manifest.path("source_receipt_count").asInt());
        assertTrue(manifest.path("all_source_transformations_recomputed").asBoolean());
        assertFalse(manifest.path("authoritative_evaluation_permitted").asBoolean(true));
        ObjectNode options = fixture.options().deepCopy();
        options.set("manifest", manifest.deepCopy());
        ObjectNode verified = LiquidationV2PhysicalDataV1.verifyDevelopment(options);
        assertEquals(6, verified.path("role_count").asInt());
        assertEquals(7, verified.path("partition_count").asInt());
        assertEquals(manifest.path("dataset_root_sha256").asText(), verified.path("dataset_root_sha256").asText());

        rejectManifest(fixture, manifest, "unsupported, modified, or falsely authoritative",
                candidate -> candidate.put("authoritative_evaluation_permitted", true), false);
        rejectManifest(fixture, manifest, "frozen physical plan",
                candidate -> {
                    ObjectNode plan = (ObjectNode) candidate.path("plan");
                    plan.put("minimum_outer_purge_days", 60);
                    plan.put("content_sha256", JsonHashes.ownHash(plan));
                }, true);
        rejectManifest(fixture, manifest, "all six physical roles",
                candidate -> ((ObjectNode) candidate.path("artifacts")).remove("funding"), true);
        rejectManifest(fixture, manifest, "invalid v002 role partition inventory: execution",
                candidate -> ((ObjectNode) candidate.path("artifacts").path("execution")).put("partition_count", 2), true);
        rejectManifest(fixture, manifest, "dataset root hash mismatch",
                candidate -> candidate.put("dataset_root_sha256", sha("wrong-dataset-root")), true);
        rejectManifest(fixture, manifest, "readiness is not derived",
                candidate -> candidate.put("coverage_inventory_complete", true), true);
        rejectManifest(fixture, manifest, "unsupported v002 physical source mode",
                candidate -> candidate.put("source_mode", "UNREVIEWED"), true);
        rejectManifest(fixture, manifest, "synthetic development manifest has non-synthetic source receipts",
                candidate -> candidate.put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY"), true);
    }

    @Test
    void reopenedNormalizationReceiptRejectsClaimedPitAndUnknownTransformRemainsUnverified() throws Exception {
        Fixture fixture = fixture(temporary.toRealPath().resolve("receipt-mutants"), false);
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        ObjectNode candidate = manifest.deepCopy();
        JsonNode firstFeature = candidate.path("artifacts").path("feature").path("partitions").get(0);
        String receiptRelative = firstFeature.path("normalization_receipt_path").asText();
        Path receiptPath = fixture.root().resolve(receiptRelative);
        byte[] original = Files.readAllBytes(receiptPath);
        ObjectNode receipt = (ObjectNode) JsonHashes.mapper().readTree(original);
        receipt.put("historical_availability_proven", true);
        writeReceiptAndBind(candidate, firstFeature, receiptPath, receipt);
        assertVerifyFailure(fixture, candidate, "cannot assert PIT or authoritative provenance");
        Files.write(receiptPath, original);

        Fixture unknown = fixture(temporary.toRealPath().resolve("unknown-normalizer"), true);
        ObjectNode diagnostic = LiquidationV2PhysicalDataV1.buildDevelopment(unknown.options());
        assertFalse(diagnostic.path("all_source_transformations_recomputed").asBoolean(true));
        assertFalse(diagnostic.path("development_replay_permitted").asBoolean(true));
        ObjectNode diagnosticOptions = unknown.options().deepCopy();
        diagnosticOptions.set("manifest", diagnostic.deepCopy());
        ObjectNode reopened = LiquidationV2PhysicalDataV1.verifyDevelopment(diagnosticOptions);
        assertFalse(reopened.path("all_source_transformations_recomputed").asBoolean(true));
        assertFalse(reopened.path("development_replay_permitted").asBoolean(true));
    }

    private Fixture fixture(Path root, boolean unknownNormalizer) throws Exception {
        Files.createDirectories(root);
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        long dayEnd = SOURCE_START + 86_400_000L;
        addPartition(root, inputs, "feature", "liquidations", "COINALYZE_PROXY", dailyRow(), SOURCE_START, dayEnd, null);
        addPartition(root, inputs, "feature", "price", "PUBLIC_ARCHIVE", priceRow(), SOURCE_START,
                SOURCE_START + 14_400_000L, null);
        addPartition(root, inputs, "label", "labels", "DERIVED_OUTCOME_LABEL",
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("episode_id", "fixture-label")
                        .put("decision_time", DECISION_START).put("label", "UNINSPECTED"),
                DECISION_START, DECISION_START + 60_000L, null);
        addPartition(root, inputs, "execution", "execution", "PUBLIC_ARCHIVE", executionRow(),
                DECISION_START, DECISION_START + 60_000L, null);
        addPartition(root, inputs, "mark", "mark", "PUBLIC_ARCHIVE", markRow(),
                DECISION_START, DECISION_START + 60_000L, null);
        addPartition(root, inputs, "funding", "funding", "PUBLIC_ARCHIVE", fundingRow(),
                DECISION_START, DECISION_START + 8L * 3_600_000L, null);
        ObjectNode metadata = metadataRow();
        ObjectNode assumption = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-metadata-assumption/1")
                .put("version", 1).put("source_class", "HISTORICAL_APPROXIMATION")
                .put("assumption_description", "Present contract fields are disclosed as a historical approximation.")
                .put("historical_availability_proven", false).put("historical_revision_proven", false)
                .put("authoritative", false);
        addPartition(root, inputs, "metadata", "metadata", "PUBLIC_ARCHIVE", metadata,
                SOURCE_START, EXECUTION_END, assumption);

        if (unknownNormalizer) {
            ObjectNode feature = (ObjectNode) inputs.path("feature").path("partitions").get(0);
            Path receiptPath = root.resolve(feature.path("normalization_receipt_path").asText());
            ObjectNode receipt = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(receiptPath));
            receipt.put("normalizer_id", "unverified-fixture-transform/7")
                    .put("normalizer_sha256", sha("unverified-fixture-transform/7"))
                    .put("content_sha256", "");
            receipt.put("content_sha256", JsonHashes.ownHash(receipt));
            byte[] bytes = JsonHashes.canonicalBytes(receipt);
            Files.write(receiptPath, bytes);
            feature.put("normalization_receipt_sha256", JsonHashes.sha256(bytes));
        }

        ObjectNode options = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        options.set("profile", LiquidationDailyStressProfileV1.frozenContract());
        options.set("inputs", inputs);
        return new Fixture(root, options);
    }

    private void addPartition(Path root, ObjectNode inputs, String role, String name, String sourceClass,
            ObjectNode row, long start, long end, ObjectNode assumptionTemplate) throws Exception {
        String normalizedPath = "normalized/" + name + ".jsonl";
        String sourcePath = "raw/" + name + ".jsonl";
        String receiptPath = "receipts/" + name + ".json";
        byte[] normalized = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl(role, List.of(row));
        byte[] source = normalized.clone();
        write(root, normalizedPath, normalized);
        write(root, sourcePath, source);
        ObjectNode receipt = LiquidationV2PhysicalDataV1.createRoleJsonlCopyReceipt(role, normalizedPath,
                normalized, sourcePath, source, Instant.ofEpochMilli(start).toString(),
                Instant.ofEpochMilli(end).toString(), sourceClass);
        byte[] receiptBytes = JsonHashes.canonicalBytes(receipt);
        write(root, receiptPath, receiptBytes);
        ObjectNode partition = JsonHashes.mapper().createObjectNode().put("path", normalizedPath)
                .put("sha256", JsonHashes.sha256(normalized)).put("source_path", sourcePath)
                .put("source_sha256", JsonHashes.sha256(source)).put("normalization_receipt_path", receiptPath)
                .put("normalization_receipt_sha256", JsonHashes.sha256(receiptBytes))
                .put("window_start", Instant.ofEpochMilli(start).toString())
                .put("window_end_exclusive", Instant.ofEpochMilli(end).toString());
        if (assumptionTemplate != null) {
            ObjectNode assumption = assumptionTemplate.deepCopy().put("source_path", sourcePath)
                    .put("source_byte_sha256", JsonHashes.sha256(source))
                    .put("window_start", Instant.ofEpochMilli(start).toString())
                    .put("window_end_exclusive", Instant.ofEpochMilli(end).toString());
            assumption.put("content_sha256", JsonHashes.ownHash(assumption));
            String assumptionPath = "receipts/metadata-assumption.json";
            byte[] bytes = JsonHashes.canonicalBytes(assumption);
            write(root, assumptionPath, bytes);
            partition.put("assumption_path", assumptionPath).put("assumption_sha256", JsonHashes.sha256(bytes));
        }
        if (!inputs.has(role)) inputs.putObject(role).putArray("partitions");
        ((ArrayNode) inputs.path(role).path("partitions")).add(partition);
    }

    private void rejectManifest(Fixture fixture, ObjectNode baseline, String message,
            Consumer<ObjectNode> mutation, boolean rehash) {
        ObjectNode candidate = baseline.deepCopy();
        mutation.accept(candidate);
        if (rehash) candidate.put("content_sha256", JsonHashes.ownHash(candidate));
        assertVerifyFailure(fixture, candidate, message);
    }

    private void assertVerifyFailure(Fixture fixture, ObjectNode manifest, String message) {
        ObjectNode options = fixture.options().deepCopy();
        options.set("manifest", manifest);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.verifyDevelopment(options));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static void writeReceiptAndBind(ObjectNode manifest, JsonNode entryNode, Path receiptPath,
            ObjectNode receipt) throws Exception {
        ObjectNode entry = (ObjectNode) entryNode;
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        byte[] bytes = JsonHashes.canonicalBytes(receipt);
        Files.write(receiptPath, bytes);
        entry.put("normalization_receipt_byte_sha256", JsonHashes.sha256(bytes));
        manifest.put("content_sha256", JsonHashes.ownHash(manifest));
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

    private static ObjectNode executionRow() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("open_time", DECISION_START)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("base_volume", 10);
    }

    private static ObjectNode markRow() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("timestamp", DECISION_START)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100);
    }

    private static ObjectNode fundingRow() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("settlement_time", DECISION_START + 7)
                .put("event_id", "funding-1").put("funding_rate", 0).put("mark_price", 100)
                .put("funding_interval_hours", 8);
    }

    private static ObjectNode metadataRow() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", 1)
                .put("effective_from", SOURCE_START).put("effective_until", EXECUTION_END).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0.0001)
                .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", 1_000_000_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true);
    }

    private static void write(Path root, String relative, byte[] bytes) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private static long epoch(String text) { return Instant.parse(text).toEpochMilli(); }
    private static String sha(String value) { return JsonHashes.sha256(value); }

    private record Fixture(Path root, ObjectNode options) {}
}
