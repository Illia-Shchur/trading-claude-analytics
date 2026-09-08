package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public staging-verifier custody boundaries for unavailable and fixture captures. */
final class StrategyResearchDataV5CaptureCustodyBoundaryTest {
    private static final String H = "a".repeat(64);

    @Test
    void publicVerifierAcceptsAnUnavailableCaptureWithoutPhysicalClaims(@TempDir Path root) {
        ObjectNode capture = unavailable("NETWORK_UNAVAILABLE");
        ObjectNode manifest = manifest(capture, false);
        assertThat(StrategyResearchDataV5.verifyAuthoritativeStaging(options(manifest, root))).isTrue();
    }

    @Test
    void publicVerifierReopensStagingPartitionAndItsNormalizedReceipt(@TempDir Path root) throws Exception {
        byte[] partitionBytes = "{\"event_time\":\"2026-01-01T00:00:00.000Z\",\"availability_time\":\"2026-01-01T00:01:00.000Z\"}\n"
                .getBytes(StandardCharsets.UTF_8);
        Path partitionPath = root.resolve("staging/signal.jsonl");
        Files.createDirectories(partitionPath.getParent());
        Files.write(partitionPath, partitionBytes);
        byte[] rawBytes = "retained-source-response".getBytes(StandardCharsets.UTF_8);
        Path rawPath = root.resolve("raw/response.bin");
        Files.createDirectories(rawPath.getParent());
        Files.write(rawPath, rawBytes);
        String rawHash = StrategyResearchDataV5.hash(rawBytes);
        ObjectNode raw = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1)
                .put("path", "raw/response.bin").put("source", "FIXTURE").put("byte_sha256", rawHash)
                .put("bytes", rawBytes.length).put("format", "RAW_BYTES").put("storage_role", "RAW_IGNORED")
                .put("authoritative", false);
        raw.set("request", object().put("endpoint", "fixture://response").put("response_sha256", rawHash));
        raw = StrategyResearchDataV5.withHash(raw);
        ObjectNode receipt = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1)
                .put("status", "PUBLIC_OBSERVED").put("captured_at", "2026-01-01T00:02:00.000Z");
        receipt.set("request", object().put("endpoint", "fixture://response"));
        receipt.set("response_sha256", array().add(rawHash));
        receipt.set("source_byte_sha256", array().add(rawHash));
        receipt.set("raw_receipts", array().add(raw));
        receipt.set("coverage", object().put("complete", true));
        receipt = StrategyResearchDataV5.withHash(receipt);
        Path receiptPath = root.resolve("receipts/source.json");
        Files.createDirectories(receiptPath.getParent());
        Files.write(receiptPath, JsonHashes.mapper().writeValueAsBytes(receipt));
        ObjectNode summary = object().put("path", "receipts/source.json")
                .put("sha256", text(receipt, "content_sha256")).put("content_sha256", text(receipt, "content_sha256"))
                .put("byte_sha256", rawHash).put("raw_count", 1).put("schema", "strategy-v5-source-receipt/1")
                .put("status", "PUBLIC_OBSERVED");
        ObjectNode capture = object().put("asset", "btc").put("venue", "BINANCE").put("instrument", "BINANCE_SPOT")
                .put("symbol", "BTCUSDT").put("interval", "4h").put("series_type", "signal_bars");
        capture.set("partition", object().put("path", "staging/signal.jsonl")
                .put("sha256", StrategyResearchDataV5.hash(partitionBytes)).put("bytes", partitionBytes.length)
                .put("row_count", 1).put("format", "JSONL").put("storage_role", "STAGING").put("authoritative", false));
        capture.set("source_receipts", array().add(summary));
        capture.set("coverage", object().put("complete", true));
        ObjectNode manifest = manifest(capture, false);
        manifest.set("source_receipts", JsonHashes.mapper().createArrayNode().add("receipts/source.json"));
        manifest.set("source_receipt_sha256", array().add(text(receipt, "content_sha256")));
        manifest = StrategyResearchDataV5.withHash(manifest);
        ObjectNode verifiedManifest = manifest;
        assertThat(StrategyResearchDataV5.verifyAuthoritativeStaging(options(verifiedManifest, root))).isTrue();

        Files.write(partitionPath, "tampered\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> StrategyResearchDataV5.verifyAuthoritativeStaging(options(verifiedManifest, root)))
                .hasMessageContaining("staging partition is missing or tampered");
    }

    @Test
    void unavailableCaptureCannotClaimAStagingPartition(@TempDir Path root) {
        ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(
                object().put("asOf", "2026-08-24T20:30:00.000Z"));
        ArrayNode captures = JsonHashes.mapper().createArrayNode();
        boolean forged = false;
        for (com.fasterxml.jackson.databind.JsonNode value : plan.path("series")) {
            ObjectNode capture = unavailable("NETWORK_UNAVAILABLE");
            for (String field : List.of("asset", "venue", "instrument", "symbol", "interval", "series_type", "series_role", "required")) {
                if (value.has(field)) capture.set(field, value.get(field).deepCopy());
            }
            if (!forged) {
                capture.set("partition", object().put("path", "staging/missing.jsonl").put("sha256", H)
                        .put("bytes", 0).put("row_count", 0).put("format", "JSONL").put("storage_role", "STAGING")
                        .put("authoritative", false));
                forged = true;
            }
            captures.add(capture);
        }
        ObjectNode manifest = manifest(captures, false, plan.path("content_sha256").asText());
        ObjectNode options = options(manifest, root);
        options.set("plan", plan);
        assertThatThrownBy(() -> StrategyResearchDataV5.verifyAuthoritativeStaging(options))
                .hasMessageContaining("unavailable acquisition capture claims physical custody");
    }

    @Test
    void fixtureOnlyStagingRequiresExplicitBoundaryOptIn(@TempDir Path root) {
        ObjectNode manifest = manifest(unavailable("FIXTURE_ONLY"), true);
        assertThatThrownBy(() -> StrategyResearchDataV5.verifyAuthoritativeStaging(options(manifest, root)))
                .hasMessageContaining("fixture-only staging evidence cannot enter an authoritative research boundary");
        ObjectNode allowed = options(manifest, root);
        allowed.put("allowFixture", true);
        assertThat(StrategyResearchDataV5.verifyAuthoritativeStaging(allowed)).isTrue();
    }

    @Test
    void publicLineageInspectorReportsUnavailableAndRejectsMissingPartition(@TempDir Path root) {
        ObjectNode unavailable = unavailable("NO_HISTORY");
        unavailable.put("producer_code_sha256", H).put("adapter_code_sha256", H);
        ObjectNode lineage = StrategyResearchDataV5.inspectCaptureLineage(unavailable, root);
        assertThat(lineage.path("producer_binding_status").asText()).isEqualTo("UNBOUND_LEGACY");
        assertThat(lineage.path("adapter_binding_status").asText()).isEqualTo("UNBOUND_LEGACY");
        assertThat(lineage.path("producer_code_sha256").asText()).isEqualTo(H);
        assertThat(lineage.path("adapter_code_sha256").asText()).isEqualTo(H);

        ObjectNode malformed = object().put("asset", "btc").put("instrument", "BINANCE_SPOT");
        assertThatThrownBy(() -> StrategyResearchDataV5.inspectCaptureLineage(malformed, root))
                .hasMessageContaining("capture lineage requires a partition");
        ObjectNode nullLineage = StrategyResearchDataV5.inspectCaptureLineage(null, root);
        assertThat(nullLineage.path("producer_binding_status").asText()).isEqualTo("UNBOUND_LEGACY");
        assertThat(nullLineage.path("adapter_binding_status").asText()).isEqualTo("UNBOUND_LEGACY");
    }

    private static ObjectNode options(ObjectNode manifest, Path root) {
        ObjectNode options = object();
        options.set("manifest", manifest);
        options.put("root", root.toString());
        return options;
    }

    private static ObjectNode manifest(ObjectNode capture, boolean fixtureOnly) {
        return manifest(JsonHashes.mapper().createArrayNode().add(capture), fixtureOnly, H);
    }

    private static ObjectNode manifest(ArrayNode captures, boolean fixtureOnly, String planSha) {
        ObjectNode manifest = object().put("schema", "strategy-v5-authoritative-acquisition/1").put("version", 1)
                .put("status", "STAGING_PARTIAL").put("plan_sha256", planSha).put("root_reference", "fixture")
                .put("staging_format", "JSONL").put("storage_role", "STAGING").put("authoritative", false)
                .put("fixture_only", fixtureOnly);
        manifest.set("captures", captures);
        manifest.set("source_receipts", JsonHashes.mapper().createArrayNode());
        manifest.set("source_receipt_sha256", JsonHashes.mapper().createArrayNode());
        return StrategyResearchDataV5.withHash(manifest);
    }

    private static ObjectNode unavailable(String reason) {
        ObjectNode capture = object().put("asset", "btc").put("venue", "BINANCE").put("instrument", "BINANCE_SPOT")
                .put("symbol", "BTCUSDT").put("interval", "4h").put("series_type", "signal_bars")
                .put("required", false).put("unavailable", true);
        capture.set("coverage", object().put("complete", false).put("reason", reason));
        capture.set("limitations", JsonHashes.mapper().createArrayNode().add(reason));
        return capture;
    }

    private static String text(ObjectNode value, String field) { return value.path(field).asText(); }

    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
}
