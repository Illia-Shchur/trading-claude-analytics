package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import org.junit.jupiter.api.function.Executable;

/** Public physical receipt mutation matrix with an accepted seven-partition proxy baseline. */
class LiquidationPhysicalReceiptBoundaryMatrixV2Test {
    private static final long DAY = 86_400_000L;
    private static final long SOURCE_START = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
    private static final long DECISION_START = Instant.parse("2022-11-11T00:00:00Z").toEpochMilli();
    private static final long EXECUTION_END = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli();

    @TempDir Path temporary;

    @Test
    void acceptedProxyReceiptBuildsReopensAndKeepsPITClaimsFalse() throws Exception {
        Fixture fixture = fixture(temporary.resolve("positive"));
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        ObjectNode reopenOptions = withManifest(fixture.options(), manifest);
        ObjectNode verified = LiquidationV2PhysicalDataV1.verifyDevelopment(reopenOptions);
        assertEquals("PROXY_DISCLOSED_DEVELOPMENT_ONLY", manifest.path("source_mode").asText());
        assertEquals(7, manifest.path("source_receipt_count").asInt());
        assertEquals(7, verified.path("partition_count").asInt());
        assertTrue(manifest.path("all_source_transformations_recomputed").asBoolean());
        assertFalse(manifest.path("development_replay_permitted").asBoolean(),
                "a compact fixture is not full-envelope physical coverage");
        assertFalse(verified.path("authoritative").asBoolean());
    }

    @Test
    void malformedRoleAndPartitionReferencesFailBeforeAnySourceCanBeReclassified() throws Exception {
        Fixture fixture = fixture(temporary.resolve("references"));
        LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options()); // positive control

        expectBuild(fixture, "must be an object", options -> object(options.path("inputs")).remove("funding"));
        expectBuild(fixture, "each v002 role requires at least one physical partition", options -> {
            ObjectNode role = object(options.path("inputs").path("mark"));
            role.set("partitions", JsonHashes.mapper().createObjectNode());
        });
        expectBuild(fixture, "each v002 role requires at least one physical partition", options -> {
            ((ObjectNode) options.path("inputs").path("execution")).putArray("partitions");
        });
        expectBuild(fixture, "partition references must be objects", options -> {
            ArrayNode partitions = array(options.path("inputs").path("label").path("partitions"));
            partitions.set(0, JsonHashes.mapper().getNodeFactory().textNode("not-a-reference"));
        });
        expectBuild(fixture, "must be non-empty text", options -> {
            ObjectNode partition = partition(options, "feature", 0);
            partition.remove("source_path");
        });
        expectBuild(fixture, "must be non-empty text", options -> {
            ObjectNode partition = partition(options, "feature", 0);
            partition.put("normalization_receipt_path", " ");
        });
        expectBuild(fixture, "must be distinct physical files", options -> {
            ObjectNode partition = partition(options, "feature", 0);
            partition.put("source_path", partition.path("path").asText());
        });
        expectBuild(fixture, "globally distinct", options -> {
            ObjectNode first = partition(options, "feature", 0);
            ObjectNode second = partition(options, "feature", 1);
            second.put("path", first.path("path").asText());
        });
        expectBuild(fixture, "globally distinct", options -> {
            ObjectNode first = partition(options, "feature", 0);
            ObjectNode second = partition(options, "feature", 1);
            second.put("normalization_receipt_path", first.path("normalization_receipt_path").asText());
        });
        expectBuild(fixture, "source or normalized partition byte hash mismatch", options -> {
            partition(options, "mark", 0).put("sha256", sha("wrong-normalized-bytes"));
        });
        expectBuild(fixture, "normalization receipt byte hash mismatch", options -> {
            partition(options, "funding", 0).put("normalization_receipt_sha256", sha("wrong-receipt-bytes"));
        });
        expectBuild(fixture, "must be an integral epoch millisecond or ISO UTC timestamp", options -> {
            partition(options, "execution", 0).put("window_start", "not-a-time");
        });
        expectBuild(fixture, "window must have positive duration", options -> {
            ObjectNode row = partition(options, "execution", 0);
            row.put("window_end_exclusive", row.path("window_start").asText());
        });
        expectBuild(fixture, "falls outside its declared half-open partition window", options -> {
            ObjectNode row = partition(options, "feature", 1);
            row.put("window_start", Instant.ofEpochMilli(SOURCE_START + 3_600_000L).toString());
        });
        expectBuild(fixture, "metadata assumptions may only bind the metadata role", options -> {
            ObjectNode row = partition(options, "mark", 0);
            row.put("assumption_path", "receipts/metadata-assumption.json")
                    .put("assumption_sha256", sha("unrelated-metadata-assumption"));
        });
    }

    @Test
    void sourceClassNormalizerAndPITMutationsAreCheckedAgainstReopenedBytes() throws Exception {
        Fixture fixture = fixture(temporary.resolve("receipt-mutations"));
        LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options()); // valid receipt/source baseline

        mutateNormalizationReceipt(fixture, "feature", 0, receipt -> receipt.put("source_class", "PUBLIC_ARCHIVE"));
        expectBuild(fixture, "must keep Coinalyze daily liquidation proxy rows separate", ignored -> {});

        Fixture mixed = fixture(temporary.resolve("mixed-sources"));
        mutateNormalizationReceipt(mixed, "feature", 1, receipt -> receipt.put("source_class", "SYNTHETIC_FIXTURE"));
        expectBuild(mixed, "cannot mix synthetic fixture partitions", ignored -> {});

        Fixture verified = fixture(temporary.resolve("verified-claim"));
        mutateNormalizationReceipt(verified, "mark", 0, receipt -> receipt.put("verified", true));
        expectBuild(verified, "cannot assert PIT or authoritative provenance", ignored -> {});

        Fixture unknownTransform = fixture(temporary.resolve("unknown-normalizer"));
        mutateNormalizationReceipt(unknownTransform, "feature", 0, receipt -> receipt
                .put("normalizer_id", "unreviewed-vendor-transform/8")
                .put("normalizer_sha256", sha("unreviewed-vendor-transform/8 bytes")));
        ObjectNode unknownManifest = LiquidationV2PhysicalDataV1.buildDevelopment(unknownTransform.options());
        assertFalse(unknownManifest.path("all_source_transformations_recomputed").asBoolean());
        assertFalse(unknownManifest.path("development_replay_permitted").asBoolean());
        ObjectNode reopenedUnknown = LiquidationV2PhysicalDataV1.verifyDevelopment(
                withManifest(unknownTransform.options(), unknownManifest));
        assertFalse(reopenedUnknown.path("all_source_transformations_recomputed").asBoolean());
        assertEquals("UNVERIFIED_TRANSFORMATION", unknownManifest.path("artifacts").path("feature")
                .path("partitions").get(0).path("transformation_status").asText());

        Fixture badCopyHash = fixture(temporary.resolve("wrong-normalizer-hash"));
        mutateNormalizationReceipt(badCopyHash, "feature", 0, receipt ->
                receipt.put("normalizer_sha256", sha("not-the-frozen-copy-normalizer")));
        expectBuild(badCopyHash, "role JSONL copy normalizer hash is not the frozen implementation identity", ignored -> {});
    }

    @Test
    void metadataAssumptionsMustBindBytesAndDisclosedHistoricalApproximationScope() throws Exception {
        Fixture invalid = fixture(temporary.resolve("bad-assumption"));
        ObjectNode reference = partition(invalid.options(), "metadata", 0);
        Path path = Path.of(invalid.options().path("root").asText()).resolve(reference.path("assumption_path").asText());
        ObjectNode assumption = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
        assumption.put("historical_availability_proven", true);
        assumption.put("content_sha256", JsonHashes.ownHash(assumption));
        byte[] changed = JsonHashes.canonicalBytes(assumption);
        Files.write(path, changed);
        reference.put("assumption_sha256", JsonHashes.sha256(changed));
        expectBuild(invalid, "hash-bound historical-approximation assumption receipt", ignored -> {});

        Fixture missing = fixture(temporary.resolve("missing-assumption"));
        partition(missing.options(), "metadata", 0).remove("assumption_path");
        expectBuild(missing, "must be non-empty text", ignored -> {});
    }

    @Test
    void verifierChecksManifestClaimsRoleInventoryAndReopenedPartitionSummaries() throws Exception {
        Fixture fixture = fixture(temporary.resolve("manifest-mutations"));
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        ObjectNode validOptions = withManifest(fixture.options(), manifest);
        assertDoesNotThrow(() -> LiquidationV2PhysicalDataV1.verifyDevelopment(validOptions));

        rejectManifest(fixture, manifest, value -> value.put("schema", "physical-manifest/0"),
                true, "unsupported, modified, or falsely authoritative");
        rejectManifest(fixture, manifest, value -> value.put("version", 2),
                true, "unsupported, modified, or falsely authoritative");
        rejectManifest(fixture, manifest, value -> value.put("status", "SEALED"),
                true, "unsupported, modified, or falsely authoritative");
        rejectManifest(fixture, manifest, value -> value.put("authoritative", true),
                true, "unsupported, modified, or falsely authoritative");
        rejectManifest(fixture, manifest, value -> value.put("source_qualification_permitted", true),
                true, "unsupported, modified, or falsely authoritative");
        rejectManifest(fixture, manifest, value -> value.put("authoritative_evaluation_permitted", true),
                true, "unsupported, modified, or falsely authoritative");
        rejectManifest(fixture, manifest, value -> value.put("full_historical_hydration_claimed", true),
                true, "unsupported, modified, or falsely authoritative");
        rejectManifest(fixture, manifest, value -> value.put("profile_sha256", sha("other-profile")),
                true, "unsupported, modified, or falsely authoritative");
        rejectManifest(fixture, manifest, value -> value.put("precommit_sha256", sha("other-precommit")),
                true, "unsupported, modified, or falsely authoritative");
        rejectManifest(fixture, manifest, value -> value.put("plan_sha256", sha("other-plan")),
                true, "not bound to the frozen physical plan");
        rejectManifest(fixture, manifest, value -> object(value.path("artifacts")).remove("mark"),
                true, "must have all six physical roles");
        rejectManifest(fixture, manifest, value -> manifestPartition(value, "feature", 0).put("source_class", "tampered"),
                true, "source classification or transformation differs");
        rejectManifest(fixture, manifest, value -> manifestPartition(value, "execution", 0).put("row_count", 99),
                true, "Parquet partition fails canonical source reopen");
        rejectManifest(fixture, manifest, value -> object(manifestPartition(value, "feature", 0).path("coverage")).put("row_count", 999),
                true, "coverage differs from reopened rows");
        rejectManifest(fixture, manifest, value -> manifestPartition(value, "funding", 0).put("normalized_byte_sha256", sha("changed")),
                true, "source, normalized, or normalization receipt bytes changed");
        rejectManifest(fixture, manifest, value -> manifestPartition(value, "metadata", 0).put("assumption_source_class", "SYNTHETIC_FIXTURE"),
                true, "metadata assumption classification changed");
        rejectManifest(fixture, manifest, value -> value.put("dataset_root_sha256", sha("wrong-dataset")),
                true, "partition dataset root hash mismatch");
    }

    private Fixture fixture(Path root) throws Exception {
        Files.createDirectories(root);
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        add(root, inputs, "feature", "daily", "COINALYZE_PROXY", dailyRow(), SOURCE_START,
                SOURCE_START + DAY, null);
        add(root, inputs, "feature", "price", "PUBLIC_ARCHIVE", priceRow(), SOURCE_START,
                SOURCE_START + 14_400_000L, null);
        add(root, inputs, "label", "label", "DERIVED_OUTCOME_LABEL", labelRow(), DECISION_START,
                DECISION_START + 60_000L, null);
        add(root, inputs, "execution", "execution", "PUBLIC_ARCHIVE", executionRow(), DECISION_START,
                DECISION_START + 60_000L, null);
        add(root, inputs, "mark", "mark", "PUBLIC_ARCHIVE", markRow(), DECISION_START,
                DECISION_START + 60_000L, null);
        add(root, inputs, "funding", "funding", "PUBLIC_ARCHIVE", fundingRow(), DECISION_START,
                DECISION_START + 60_000L, null);
        ObjectNode metadata = metadataRow();
        ObjectNode assumption = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-metadata-assumption/1")
                .put("version", 1).put("source_class", "HISTORICAL_APPROXIMATION")
                .put("assumption_description", "Fixture metadata uses a disclosed historical approximation for tests.")
                .put("historical_availability_proven", false).put("historical_revision_proven", false)
                .put("authoritative", false);
        add(root, inputs, "metadata", "metadata", "PUBLIC_ARCHIVE", metadata, SOURCE_START,
                EXECUTION_END, assumption);
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("root", root.toAbsolutePath().normalize().toString());
        options.set("profile", LiquidationDailyStressProfileV1.frozenContract());
        options.set("inputs", inputs);
        return new Fixture(root, options);
    }

    private static void add(Path root, ObjectNode inputs, String role, String name, String sourceClass,
            ObjectNode row, long start, long end, ObjectNode assumptionTemplate) throws Exception {
        String normalizedPath = "normalized/" + name + ".jsonl";
        String sourcePath = "raw/" + name + ".jsonl";
        String receiptPath = "receipts/" + name + ".json";
        byte[] normalized = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl(role, List.of(row));
        byte[] source = normalized.clone();
        write(root, normalizedPath, normalized); write(root, sourcePath, source);
        ObjectNode receipt = LiquidationV2PhysicalDataV1.createRoleJsonlCopyReceipt(role, normalizedPath,
                normalized, sourcePath, source, Instant.ofEpochMilli(start).toString(),
                Instant.ofEpochMilli(end).toString(), sourceClass);
        byte[] receiptBytes = JsonHashes.canonicalBytes(receipt); write(root, receiptPath, receiptBytes);
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
            byte[] assumptionBytes = JsonHashes.canonicalBytes(assumption); write(root, assumptionPath, assumptionBytes);
            partition.put("assumption_path", assumptionPath).put("assumption_sha256", JsonHashes.sha256(assumptionBytes));
        }
        if (!inputs.has(role)) inputs.putObject(role).putArray("partitions");
        ((ArrayNode) inputs.path(role).path("partitions")).add(partition);
    }

    private static void mutateNormalizationReceipt(Fixture fixture, String role, int index,
            Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode reference = partition(fixture.options(), role, index);
        Path path = fixture.root().resolve(reference.path("normalization_receipt_path").asText());
        ObjectNode receipt = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
        mutation.accept(receipt);
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        byte[] bytes = JsonHashes.canonicalBytes(receipt);
        Files.write(path, bytes);
        reference.put("normalization_receipt_sha256", JsonHashes.sha256(bytes));
    }

    private void expectBuild(Fixture fixture, String expected, Consumer<ObjectNode> mutation) {
        ObjectNode options = fixture.options().deepCopy();
        mutation.accept(options);
        assertFailure(expected, () -> LiquidationV2PhysicalDataV1.buildDevelopment(options));
    }

    private void rejectManifest(Fixture fixture, ObjectNode valid, Consumer<ObjectNode> mutation,
            boolean reseal, String expected) {
        ObjectNode changed = valid.deepCopy();
        mutation.accept(changed);
        if (reseal) changed.put("content_sha256", JsonHashes.ownHash(changed));
        ObjectNode options = withManifest(fixture.options(), changed);
        assertFailure(expected, () -> LiquidationV2PhysicalDataV1.verifyDevelopment(options));
    }

    private void assertFailure(String expected, Executable action) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action);
        assertTrue(error.getMessage().contains(expected), "expected diagnostic [" + expected + "], got [" + error.getMessage() + "]");
    }

    private static ObjectNode withManifest(ObjectNode options, ObjectNode manifest) {
        ObjectNode result = options.deepCopy(); result.set("manifest", manifest.deepCopy()); return result;
    }

    private static ObjectNode partition(ObjectNode options, String role, int index) {
        return (ObjectNode) options.path("inputs").path(role).path("partitions").get(index);
    }

    private static ObjectNode manifestPartition(ObjectNode manifest, String role, int index) {
        return (ObjectNode) manifest.path("artifacts").path(role).path("partitions").get(index);
    }

    private static ObjectNode object(JsonNode value) { return (ObjectNode) value; }
    private static ArrayNode array(JsonNode value) { return (ArrayNode) value; }
    private static String sha(String value) { return JsonHashes.sha256(value); }

    private static ObjectNode dailyRow() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT_PERP.A")
                .put("series_id", "daily_liquidation_usd").put("timeframe", "1d").put("side", "LONG")
                .put("event_time", SOURCE_START).put("availability_time", SOURCE_START + 2 * DAY).put("value", 100);
    }

    private static ObjectNode priceRow() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "price_ohlc").put("timeframe", "4h").put("event_time", SOURCE_START)
                .put("availability_time", SOURCE_START + 14_400_000L).put("open", 99).put("high", 101)
                .put("low", 98).put("close", 100).put("base_volume", 10);
    }

    private static ObjectNode labelRow() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("episode_id", "fixture-label")
                .put("decision_time", DECISION_START).put("label", "UNINSPECTED");
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
                .put("event_id", "fixture-funding").put("funding_rate", 0).put("mark_price", 100)
                .put("funding_interval_hours", 8);
    }

    private static ObjectNode metadataRow() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", 1)
                .put("effective_from", SOURCE_START).put("effective_until", EXECUTION_END).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0)
                .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", 1_000_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true);
    }

    private static void write(Path root, String relative, byte[] bytes) throws Exception {
        Path file = root.resolve(relative); Files.createDirectories(file.getParent()); Files.write(file, bytes);
    }

    private record Fixture(Path root, ObjectNode options) {}
}
