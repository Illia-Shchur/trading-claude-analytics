package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

/** Each re-sealed metadata assumption mutation reaches its own reopened-receipt validation. */
class LiquidationMetadataAssumptionReceiptFieldMatrixV1Test {
    private static final long SOURCE = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
    private static final long DECISION = Instant.parse("2022-11-11T00:00:00Z").toEpochMilli();
    private static final long END = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli();
    private static final String ERROR = "metadata requires a reopened, hash-bound historical-approximation assumption receipt";

    @TempDir Path temporary;

    @Test
    void acceptedReceiptThenEachIndependentResealedFieldMutationIsRejected() throws Exception {
        Fixture fixture = fixture("metadata-assumption-fields");
        assertDoesNotThrow(() -> LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options()));

        reject(fixture, ERROR, row -> row.put("schema", "liquidation-v2-metadata-assumption/0"));
        reject(fixture, ERROR, row -> row.put("version", 2));
        reject(fixture, ERROR, row -> row.put("source_path", "raw/another-source.jsonl"));
        reject(fixture, ERROR, row -> row.put("source_byte_sha256", sha("different-source")));
        reject(fixture, ERROR, row -> row.put("window_start", "2022-08-12T00:00:00Z"));
        reject(fixture, ERROR, row -> row.put("window_end_exclusive", "2026-09-19T00:00:00Z"));
        reject(fixture, ERROR, row -> row.put("source_class", "PUBLIC_ARCHIVE"));
        reject(fixture, ERROR, row -> row.put("historical_availability_proven", true));
        reject(fixture, ERROR, row -> row.put("historical_revision_proven", true));
        reject(fixture, ERROR, row -> row.put("authoritative", true));
        reject(fixture, ERROR, row -> row.put("assumption_description", "too short"));
        reject(fixture, ERROR, row -> row.put("content_sha256", sha("not-the-own-hash")), false);
    }

    private void reject(Fixture fixture, String expected, Consumer<ObjectNode> mutation) throws Exception {
        reject(fixture, expected, mutation, true);
    }

    private void reject(Fixture fixture, String expected, Consumer<ObjectNode> mutation, boolean reseal) throws Exception {
        ObjectNode partition = fixture.metadataPartition();
        Path receiptPath = fixture.root().resolve(partition.path("assumption_path").asText());
        byte[] original = Files.readAllBytes(receiptPath);
        ObjectNode receipt = (ObjectNode) JsonHashes.mapper().readTree(original);
        mutation.accept(receipt);
        if (reseal) receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        byte[] changed = JsonHashes.canonicalBytes(receipt);
        Files.write(receiptPath, changed);
        partition.put("assumption_sha256", JsonHashes.sha256(changed));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options()));
        assertTrue(error.getMessage().contains(expected), "expected [" + expected + "], got [" + error.getMessage() + "]");
        Files.write(receiptPath, original);
        partition.put("assumption_sha256", JsonHashes.sha256(original));
    }

    private Fixture fixture(String name) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name)).toRealPath();
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        add(root, inputs, "feature", "daily", "COINALYZE_PROXY", List.of(daily()), SOURCE, SOURCE + 86_400_000L, null);
        add(root, inputs, "feature", "price", "PUBLIC_ARCHIVE", List.of(price()), SOURCE, SOURCE + 14_400_000L, null);
        add(root, inputs, "label", "label", "DERIVED_OUTCOME_LABEL", List.of(JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("episode_id", "assumption-fixture").put("decision_time", DECISION).put("label", "UNINSPECTED")),
                DECISION, DECISION + 60_000L, null);
        add(root, inputs, "execution", "execution", "PUBLIC_ARCHIVE", List.of(JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("open_time", DECISION).put("open", 100).put("high", 101)
                .put("low", 99).put("close", 100).put("base_volume", 10)), DECISION, DECISION + 60_000L, null);
        add(root, inputs, "mark", "mark", "PUBLIC_ARCHIVE", List.of(JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("timestamp", DECISION).put("open", 100).put("high", 101)
                .put("low", 99).put("close", 100)), DECISION, DECISION + 60_000L, null);
        add(root, inputs, "funding", "funding", "PUBLIC_ARCHIVE", List.of(JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("settlement_time", DECISION + 7).put("event_id", "assumption-funding")
                .put("funding_rate", 0).put("mark_price", 100).put("funding_interval_hours", 8)),
                DECISION, DECISION + 8 * 3_600_000L, null);
        ObjectNode metadata = JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", 1)
                .put("effective_from", SOURCE).put("effective_until", END).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0.0001)
                .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", 1_000_000_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true);
        ObjectNode assumption = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-metadata-assumption/1")
                .put("version", 1).put("source_class", "HISTORICAL_APPROXIMATION")
                .put("assumption_description", "Present contract fields are disclosed as a historical approximation.")
                .put("historical_availability_proven", false).put("historical_revision_proven", false).put("authoritative", false);
        add(root, inputs, "metadata", "metadata", "PUBLIC_ARCHIVE", List.of(metadata), SOURCE, END, assumption);
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        options.set("profile", LiquidationDailyStressProfileV1.frozenContract()); options.set("inputs", inputs);
        return new Fixture(root, options, (ObjectNode) inputs.path("metadata").path("partitions").get(0));
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
    private record Fixture(Path root, ObjectNode options, ObjectNode metadataPartition) {}
}
