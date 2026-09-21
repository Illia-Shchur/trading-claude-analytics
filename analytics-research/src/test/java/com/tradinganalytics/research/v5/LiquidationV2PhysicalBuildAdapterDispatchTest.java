package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises both accepted physical-build input shapes through the public CLI adapter. */
class LiquidationV2PhysicalBuildAdapterDispatchTest {
    private static final long SOURCE_START = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
    private static final long DECISION = Instant.parse("2022-11-11T00:00:00Z").toEpochMilli();
    private static final long EXECUTION_END = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli();

    @TempDir Path temporary;

    @Test
    void publicAdapterDispatchesFlatRoleReferencesToSyntheticBuilder() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("flat")).toRealPath();
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode inputs = flatInputs(root);

        ObjectNode result = dispatch(root, profile, inputs, "flat");

        assertEquals("SYNTHETIC_FIXTURE_ONLY", result.path("coverage_scope").asText());
        assertFalse(result.path("development_replay_permitted").asBoolean());
        assertFalse(result.path("authoritative_evaluation_permitted").asBoolean());
    }

    @Test
    void publicAdapterDispatchesPartitionReferencesToReceiptReopeningBuilder() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("partitioned")).toRealPath();
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode inputs = partitionedInputs(root);

        ObjectNode result = dispatch(root, profile, inputs, "partitioned");

        assertEquals("PROXY_DISCLOSED_DEVELOPMENT_ONLY", result.path("source_mode").asText());
        assertTrue(result.path("all_source_transformations_recomputed").asBoolean());
        assertFalse(result.path("development_replay_permitted").asBoolean());
        assertTrue(result.path("artifacts").path("feature").path("partitions").isArray());
    }

    @Test
    void malformedFeaturePartitionShapeFallsThroughToSyntheticValidationAndFailsClosed() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("malformed")).toRealPath();
        ObjectNode inputs = flatInputs(root);
        inputs.putObject("feature").put("partitions", "not-an-array");
        Dispatch result = dispatchRaw(root, LiquidationDailyStressProfileV1.frozenContract(), inputs, "malformed");
        assertEquals(1, result.status());
        assertTrue(result.stderr().contains("path must be non-empty text"), result.stderr());
        assertTrue(result.stdout().isEmpty());
    }

    private ObjectNode dispatch(Path root, ObjectNode profile, ObjectNode inputs, String id) throws Exception {
        Dispatch result = dispatchRaw(root, profile, inputs, id);
        assertEquals(0, result.status(), result.stderr());
        return (ObjectNode) JsonHashes.mapper().readTree(result.stdout());
    }

    private Dispatch dispatchRaw(Path root, ObjectNode profile, ObjectNode inputs, String id) throws Exception {
        Path profilePath = root.resolve("profile-" + id + ".json");
        Path inputsPath = root.resolve("inputs-" + id + ".json");
        Files.write(profilePath, JsonHashes.canonicalBytes(profile));
        Files.write(inputsPath, JsonHashes.canonicalBytes(inputs));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream(), stderr = new ByteArrayOutputStream();
        int status;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            status = StrategyResearchV5CommandAdapter.run(new String[] {"liquidation-v2-physical-build",
                    "--profile", profilePath.toString(), "--inputs", inputsPath.toString(), "--root", root.toString()}, out, err);
        }
        return new Dispatch(status, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static ObjectNode flatInputs(Path root) throws Exception {
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        addFlat(root, inputs, "feature", List.of(dailyRow(SOURCE_START), priceRow(SOURCE_START)));
        addFlat(root, inputs, "label", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("episode_id", "adapter-dispatch").put("decision_time", DECISION).put("label", "UNINSPECTED")));
        addFlat(root, inputs, "execution", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("open_time", DECISION).put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("base_volume", 10)));
        addFlat(root, inputs, "mark", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("timestamp", DECISION).put("open", 100).put("high", 101).put("low", 99).put("close", 100)));
        addFlat(root, inputs, "funding", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("settlement_time", DECISION + 7).put("event_id", "adapter-funding").put("funding_rate", 0)
                .put("mark_price", 100).put("funding_interval_hours", 8)));
        addFlat(root, inputs, "metadata", List.of(metadataRow(SOURCE_START)));
        return inputs;
    }

    private static ObjectNode partitionedInputs(Path root) throws Exception {
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        addPartition(root, inputs, "feature", "feature-daily", "COINALYZE_PROXY", dailyRow(SOURCE_START),
                SOURCE_START, SOURCE_START + 86_400_000L, null);
        addPartition(root, inputs, "feature", "feature-price", "PUBLIC_ARCHIVE", priceRow(SOURCE_START),
                SOURCE_START, SOURCE_START + 14_400_000L, null);
        addPartition(root, inputs, "label", "label", "DERIVED_OUTCOME_LABEL",
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("episode_id", "adapter-dispatch")
                        .put("decision_time", DECISION).put("label", "UNINSPECTED"),
                DECISION, DECISION + 60_000L, null);
        addPartition(root, inputs, "execution", "execution", "PUBLIC_ARCHIVE",
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("open_time", DECISION)
                        .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("base_volume", 10),
                DECISION, DECISION + 60_000L, null);
        addPartition(root, inputs, "mark", "mark", "PUBLIC_ARCHIVE",
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("timestamp", DECISION)
                        .put("open", 100).put("high", 101).put("low", 99).put("close", 100),
                DECISION, DECISION + 60_000L, null);
        addPartition(root, inputs, "funding", "funding", "PUBLIC_ARCHIVE",
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("settlement_time", DECISION + 7)
                        .put("event_id", "adapter-funding").put("funding_rate", 0).put("mark_price", 100)
                        .put("funding_interval_hours", 8), DECISION, DECISION + 60_000L, null);
        ObjectNode assumption = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-metadata-assumption/1")
                .put("version", 1).put("source_class", "HISTORICAL_APPROXIMATION")
                .put("assumption_description", "Present metadata is a disclosed fixture approximation.")
                .put("historical_availability_proven", false).put("historical_revision_proven", false).put("authoritative", false);
        addPartition(root, inputs, "metadata", "metadata", "PUBLIC_ARCHIVE", metadataRow(SOURCE_START),
                SOURCE_START, EXECUTION_END, assumption);
        return inputs;
    }

    private static void addFlat(Path root, ObjectNode inputs, String role, List<ObjectNode> rows) throws Exception {
        byte[] bytes = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl(role, rows);
        String path = "inputs/" + role + ".jsonl";
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        inputs.putObject(role).put("path", path).put("sha256", JsonHashes.sha256(bytes));
    }

    private static void addPartition(Path root, ObjectNode inputs, String role, String name, String sourceClass,
            ObjectNode row, long start, long end, ObjectNode assumptionTemplate) throws Exception {
        String normalizedPath = "normalized/" + name + ".jsonl", sourcePath = "raw/" + name + ".jsonl";
        String receiptPath = "receipts/" + name + ".json";
        byte[] normalized = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl(role, List.of(row));
        byte[] source = normalized.clone();
        write(root, normalizedPath, normalized);
        write(root, sourcePath, source);
        ObjectNode receipt = LiquidationV2PhysicalDataV1.createRoleJsonlCopyReceipt(role, normalizedPath, normalized,
                sourcePath, source, Instant.ofEpochMilli(start).toString(), Instant.ofEpochMilli(end).toString(), sourceClass);
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
            byte[] assumptionBytes = JsonHashes.canonicalBytes(assumption);
            write(root, assumptionPath, assumptionBytes);
            partition.put("assumption_path", assumptionPath).put("assumption_sha256", JsonHashes.sha256(assumptionBytes));
        }
        if (!inputs.has(role)) inputs.putObject(role).putArray("partitions");
        ((com.fasterxml.jackson.databind.node.ArrayNode) inputs.path(role).path("partitions")).add(partition);
    }

    private static void write(Path root, String relative, byte[] bytes) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static ObjectNode dailyRow(long time) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT_PERP.A")
                .put("series_id", "daily_liquidation_usd").put("timeframe", "1d").put("side", "LONG")
                .put("event_time", time).put("availability_time", time + 172_800_000L).put("value", 100);
    }

    private static ObjectNode priceRow(long time) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "price_ohlc").put("timeframe", "4h").put("event_time", time)
                .put("availability_time", time + 14_400_000L).put("open", 99).put("high", 101)
                .put("low", 98).put("close", 100).put("base_volume", 10);
    }

    private static ObjectNode metadataRow(long start) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", 1)
                .put("effective_from", start).put("effective_until", EXECUTION_END).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0)
                .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", 1_000_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true);
    }

    private record Dispatch(int status, String stdout, String stderr) {}
}
