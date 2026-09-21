package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Re-seals each mutable normalization receipt before checking its independent frozen contract field. */
class LiquidationNormalizationReceiptFieldBoundaryMatrixV1Test {
    private static final long SOURCE = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
    private static final long DECISION = Instant.parse("2022-11-11T00:00:00Z").toEpochMilli();
    private static final long EXECUTION_END = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli();

    @TempDir Path temporary;

    @Test
    void acceptedReceiptBuildsBeforeSchemaRolePathAndByteBindingsAreMutatedIndependently() throws Exception {
        Fixture fixture = fixture("receipt-bindings");
        assertDoesNotThrow(() -> LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options()));
        String bindingFailure = "normalization receipt does not bind the reopened feature source and normalized rows";
        reject(fixture, bindingFailure, receipt -> receipt.put("schema", "old/0"));
        reject(fixture, bindingFailure, receipt -> receipt.put("version", 2));
        reject(fixture, bindingFailure, receipt -> receipt.put("role", "mark"));
        reject(fixture, bindingFailure, receipt -> receipt.put("normalized_path", "normalized/other.jsonl"));
        reject(fixture, bindingFailure, receipt -> receipt.put("normalized_byte_sha256", sha("other-normalized")));
        reject(fixture, bindingFailure, receipt -> receipt.put("source_path", "raw/other.jsonl"));
        reject(fixture, bindingFailure, receipt -> receipt.put("source_byte_sha256", sha("other-raw")));
        reject(fixture, bindingFailure, receipt -> receipt.put("row_count", 99));
        reject(fixture, bindingFailure, receipt -> receipt.put("window_start", Instant.ofEpochMilli(SOURCE + 60_000).toString()));
        reject(fixture, bindingFailure, receipt -> receipt.put("window_end_exclusive", Instant.ofEpochMilli(SOURCE + 2 * 60_000).toString()));
        reject(fixture, bindingFailure, receipt -> receipt.put("normalizer_sha256", "not-a-hash"));
        reject(fixture, "normalizer_id must be non-empty text", receipt -> receipt.put("normalizer_id", " "));
        reject(fixture, bindingFailure, receipt -> receipt.putArray("series_scope").add("sp500_close"));

        reject(fixture, bindingFailure, receipt -> {
            ((ObjectNode) receipt.path("physical_identity")).put("collateral", "BUSD");
            receipt.put("physical_identity_sha256", JsonHashes.canonicalSha256(receipt.path("physical_identity")));
        });
        reject(fixture, bindingFailure, receipt -> receipt.put("physical_identity_sha256", sha("wrong-identity-hash")));
    }

    @Test
    void modeledAvailabilitySourceClassAndPITClaimsHaveSeparateFailClosedBranches() throws Exception {
        Fixture fixture = fixture("receipt-provenance");
        LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        reject(fixture, "normalization receipt availability basis does not match its reopened feature-series scope",
                receipt -> receipt.put("availability_basis", "MODELED_NEXT_COMPLETED_NYSE_SESSION"));
        reject(fixture, "unsupported normalization source class UNREVIEWED_SOURCE",
                receipt -> receipt.put("source_class", "UNREVIEWED_SOURCE"));
        reject(fixture, "normalization receipt cannot assert PIT or authoritative provenance",
                receipt -> receipt.put("verified", true));
        reject(fixture, "normalization receipt cannot assert PIT or authoritative provenance",
                receipt -> receipt.put("authoritative", true));
        reject(fixture, "normalization receipt cannot assert PIT or authoritative provenance",
                receipt -> receipt.put("historical_availability_proven", true));
        reject(fixture, "normalization receipt cannot assert PIT or authoritative provenance",
                receipt -> receipt.put("historical_revision_proven", true));
        reject(fixture, "normalization receipt cannot assert PIT or authoritative provenance",
                receipt -> receipt.put("status", "VERIFIED"));
        reject(fixture, "normalization receipt cannot assert PIT or authoritative provenance",
                receipt -> receipt.put("qualification_status", "VERIFIED"));
    }

    @Test
    void proxySessionReadsOnlyIntersectingReopenedPartitionsAndPreservesRoleTimeSemantics() throws Exception {
        Fixture fixture = fixture("partitioned-reader");
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        fixture.options().set("manifest", manifest);
        var session = LiquidationV2PhysicalDataV1.openVerifiedDevelopment(fixture.options());

        assertTrue(session.partitioned());
        assertEquals(2, LiquidationV2PhysicalDataV1.readRoleRows(session, "feature", null, null, List.of("BTC")).size());
        assertTrue(LiquidationV2PhysicalDataV1.readRoleRows(session, "feature", null,
                "2022-08-11T00:00:00Z", List.of("BTC")).isEmpty());
        assertEquals(2, LiquidationV2PhysicalDataV1.readRoleRows(session, "feature",
                "2022-08-11T00:00:00Z", "2022-08-12T00:00:00Z", List.of("BTC")).size());
        assertEquals(1, LiquidationV2PhysicalDataV1.readRoleRows(session, "metadata",
                "2023-01-01T00:00:00Z", "2023-01-02T00:00:00Z", List.of("BTC")).size());
        assertTrue(LiquidationV2PhysicalDataV1.readRoleRows(session, "feature", null, null, List.of("SP500")).isEmpty());
    }

    private void reject(Fixture fixture, String message, Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode partition = fixture.partition();
        Path receiptPath = fixture.root().resolve(partition.path("normalization_receipt_path").asText());
        byte[] original = Files.readAllBytes(receiptPath);
        ObjectNode receipt = (ObjectNode) JsonHashes.mapper().readTree(original);
        mutation.accept(receipt);
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        byte[] changed = JsonHashes.canonicalBytes(receipt);
        Files.write(receiptPath, changed);
        partition.put("normalization_receipt_sha256", JsonHashes.sha256(changed));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options()));
        assertTrue(error.getMessage().contains(message), "expected [" + message + "], got [" + error.getMessage() + "]");
        Files.write(receiptPath, original);
        partition.put("normalization_receipt_sha256", JsonHashes.sha256(original));
    }

    private Fixture fixture(String name) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name)).toRealPath();
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        add(root, inputs, "feature", "daily", "COINALYZE_PROXY", List.of(daily()), SOURCE, SOURCE + 86_400_000L, null);
        add(root, inputs, "feature", "price", "PUBLIC_ARCHIVE", List.of(price()), SOURCE, SOURCE + 14_400_000L, null);
        add(root, inputs, "label", "label", "DERIVED_OUTCOME_LABEL", List.of(JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("episode_id", "receipt-fixture").put("decision_time", DECISION).put("label", "UNINSPECTED")),
                DECISION, DECISION + 60_000L, null);
        add(root, inputs, "execution", "execution", "PUBLIC_ARCHIVE", List.of(JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("open_time", DECISION).put("open", 100).put("high", 101)
                .put("low", 99).put("close", 100).put("base_volume", 10)), DECISION, DECISION + 60_000L, null);
        add(root, inputs, "mark", "mark", "PUBLIC_ARCHIVE", List.of(JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("timestamp", DECISION).put("open", 100).put("high", 101)
                .put("low", 99).put("close", 100)), DECISION, DECISION + 60_000L, null);
        add(root, inputs, "funding", "funding", "PUBLIC_ARCHIVE", List.of(JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("settlement_time", DECISION + 7).put("event_id", "receipt-funding")
                .put("funding_rate", 0).put("mark_price", 100).put("funding_interval_hours", 8)),
                DECISION, DECISION + 8 * 3_600_000L, null);
        ObjectNode metadata = JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", 1)
                .put("effective_from", SOURCE).put("effective_until", EXECUTION_END).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0.0001)
                .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", 1_000_000_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true);
        ObjectNode assumption = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-metadata-assumption/1")
                .put("version", 1).put("source_class", "HISTORICAL_APPROXIMATION")
                .put("assumption_description", "Present contract fields are disclosed as a historical approximation.")
                .put("historical_availability_proven", false).put("historical_revision_proven", false).put("authoritative", false);
        add(root, inputs, "metadata", "metadata", "PUBLIC_ARCHIVE", List.of(metadata), SOURCE, EXECUTION_END, assumption);
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        options.set("profile", LiquidationDailyStressProfileV1.frozenContract()); options.set("inputs", inputs);
        return new Fixture(root, options, (ObjectNode) inputs.path("feature").path("partitions").get(0));
    }

    private static void add(Path root, ObjectNode inputs, String role, String name, String sourceClass,
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
            String assumptionPath = "receipts/metadata-assumption.json";
            byte[] assumptionBytes = JsonHashes.canonicalBytes(assumption); write(root, assumptionPath, assumptionBytes);
            partition.put("assumption_path", assumptionPath).put("assumption_sha256", JsonHashes.sha256(assumptionBytes));
        }
        if (!inputs.has(role)) inputs.putObject(role).putArray("partitions");
        ((ArrayNode) inputs.path(role).path("partitions")).add(partition);
    }

    private static ObjectNode daily() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT_PERP.A")
                .put("series_id", "daily_liquidation_usd").put("timeframe", "1d").put("side", "LONG")
                .put("event_time", SOURCE).put("availability_time", SOURCE + 172_800_000L).put("value", 100);
    }

    private static ObjectNode price() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "price_ohlc").put("timeframe", "4h").put("event_time", SOURCE)
                .put("availability_time", SOURCE + 14_400_000L).put("open", 100).put("high", 101)
                .put("low", 99).put("close", 100).put("base_volume", 10);
    }

    private static void write(Path root, String relative, byte[] bytes) throws Exception {
        Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.write(path, bytes);
    }

    private static String sha(String value) { return JsonHashes.sha256(value); }
    private record Fixture(Path root, ObjectNode options, ObjectNode partition) {}
}
