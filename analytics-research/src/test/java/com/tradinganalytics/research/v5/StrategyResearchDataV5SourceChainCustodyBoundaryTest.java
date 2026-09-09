package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public source-chain entry point with physical acquisition custody. */
final class StrategyResearchDataV5SourceChainCustodyBoundaryTest {
    private static final String PLAN_SHA = "a".repeat(64);

    @Test
    void publicSourceChainReopensAcquisitionPartitionAndRawReceipt(@TempDir Path root) throws Exception {
        ObjectNode fixture = acquisitionFixture(root);
        ObjectNode result = verify((ObjectNode) fixture.path("manifest"), (ObjectNode) fixture.path("reference"), root);
        assertThat(result.path("bundle").isNull()).isTrue();
        assertThat(result.path("acquisition").path("status").asText()).isEqualTo("STAGING_COMPLETE");
        assertThat(result.path("acquisition").path("captures")).hasSize(1);
    }

    @Test
    void publicSourceChainRejectsPhysicalPartitionTampering(@TempDir Path root) throws Exception {
        ObjectNode fixture = acquisitionFixture(root);
        Path partition = root.resolve("staging/signal.jsonl");
        Files.writeString(partition, "tampered\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verify((ObjectNode) fixture.path("manifest"), (ObjectNode) fixture.path("reference"), root))
                .hasMessageContaining("staging partition is missing or tampered: staging/signal.jsonl");
    }

    @Test
    void publicSourceChainRejectsAReferenceBoundToTheWrongPlan(@TempDir Path root) throws Exception {
        ObjectNode fixture = acquisitionFixture(root);
        ObjectNode options = object();
        options.put("root", root.toString()).set("reference", fixture.path("reference"));
        options.put("expectedContentSha256", fixture.path("manifest").path("content_sha256").asText())
                .put("planSha256", "b".repeat(64)).put("label", "source-chain plan boundary");
        assertThatThrownBy(() -> StrategyResearchDataV5.verifyAuthoritativeSourceChain(options))
                .hasMessageContaining("source-chain plan boundary is bound to a different plan");
    }

    @Test
    void publicAcquisitionRebaseCopiesBoundCustodyAndWritesAHashableCheckpoint(
            @TempDir Path sourceRoot, @TempDir Path targetRoot) throws Exception {
        ObjectNode fixture = acquisitionFixture(sourceRoot);
        ObjectNode options = object().set("manifest", fixture.path("manifest"));
        options.put("sourceRoot", sourceRoot.toString()).put("targetRoot", targetRoot.toString())
                .put("expectedPlanSha256", PLAN_SHA).put("targetRootReference", "rebased-source-chain");

        ObjectNode checkpoint = StrategyResearchDataV5.rebaseAcquisitionCheckpoint(options);
        assertThat(checkpoint.path("schema").asText()).isEqualTo("strategy-v5-data-checkpoint/1");
        assertThat(checkpoint.path("content_sha256").asText()).isEqualTo(StrategyResearchDataV5.ownHash(checkpoint));
        assertThat(checkpoint.path("completed").fieldNames().hasNext()).isTrue();
        assertThat(Files.readAllBytes(targetRoot.resolve("staging/signal.jsonl")))
                .isEqualTo(Files.readAllBytes(sourceRoot.resolve("staging/signal.jsonl")));
        assertThat(Files.readAllBytes(targetRoot.resolve("receipts/source.json")))
                .isEqualTo(Files.readAllBytes(sourceRoot.resolve("receipts/source.json")));
        assertThat(Files.readAllBytes(targetRoot.resolve("raw/response.bin")))
                .isEqualTo(Files.readAllBytes(sourceRoot.resolve("raw/response.bin")));
        assertThat(Files.exists(targetRoot.resolve("checkpoint.json"))).isTrue();
        ObjectNode reopened = (ObjectNode) JsonHashes.mapper().readTree(
                Files.readAllBytes(targetRoot.resolve("checkpoint.json")));
        assertThat(reopened.path("content_sha256").asText()).isEqualTo(
                StrategyResearchDataV5.ownHash(reopened));
        assertThat(reopened.path("content_sha256").asText())
                .isEqualTo(checkpoint.path("content_sha256").asText());
    }

    @Test
    void publicAcquisitionRebaseRejectsSameRootAndWrongExpectedPlan(@TempDir Path root) throws Exception {
        ObjectNode fixture = acquisitionFixture(root);
        ObjectNode sameRoot = object().set("manifest", fixture.path("manifest"));
        sameRoot.put("sourceRoot", root.toString()).put("targetRoot", root.toString());
        assertThatThrownBy(() -> StrategyResearchDataV5.rebaseAcquisitionCheckpoint(sameRoot))
                .hasMessageContaining("acquisition rebase requires distinct source and target roots");

        ObjectNode wrongPlan = object().set("manifest", fixture.path("manifest"));
        wrongPlan.put("sourceRoot", root.toString()).put("targetRoot", root.resolve("other").toString())
                .put("expectedPlanSha256", "b".repeat(64));
        assertThatThrownBy(() -> StrategyResearchDataV5.rebaseAcquisitionCheckpoint(wrongPlan))
                .hasMessageContaining("acquisition rebase manifest is bound to a different frozen plan");
    }

    @Test
    void fixtureRoleReceiptSeamCannotPretendToBeAuthoritative() {
        assertThatThrownBy(() -> StrategyResearchDataV5.emitRoleDerivationReceipt(
                object().put("fixtureOnly", false)))
                .hasMessageContaining("emitRoleDerivationReceipt is FIXTURE_ONLY");
    }

    private static ObjectNode verify(ObjectNode manifest, ObjectNode reference, Path root) {
        ObjectNode options = object();
        options.put("root", root.toString()).set("reference", reference);
        options.put("expectedContentSha256", manifest.path("content_sha256").asText())
                .put("planSha256", PLAN_SHA).put("label", "source-chain fixture");
        return StrategyResearchDataV5.verifyAuthoritativeSourceChain(options);
    }

    private static ObjectNode acquisitionFixture(Path root) throws Exception {
        byte[] partitionBytes = "{\"event_time\":\"2026-01-01T00:00:00.000Z\",\"availability_time\":\"2026-01-01T00:01:00.000Z\"}\n"
                .getBytes(StandardCharsets.UTF_8);
        Path partition = root.resolve("staging/signal.jsonl");
        Files.createDirectories(partition.getParent());
        Files.write(partition, partitionBytes);

        byte[] rawBytes = "source-chain-retained-response".getBytes(StandardCharsets.UTF_8);
        Path raw = root.resolve("raw/response.bin");
        Files.createDirectories(raw.getParent());
        Files.write(raw, rawBytes);
        String rawSha = StrategyResearchDataV5.hash(rawBytes);
        ObjectNode rawReceipt = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1)
                .put("path", "raw/response.bin").put("source", "SOURCE_CHAIN_FIXTURE")
                .put("byte_sha256", rawSha).put("bytes", rawBytes.length).put("format", "RAW_BYTES")
                .put("storage_role", "RAW_IGNORED").put("authoritative", false);
        rawReceipt.set("request", object().put("endpoint", "fixture://source-chain").put("response_sha256", rawSha));
        rawReceipt = StrategyResearchDataV5.withHash(rawReceipt);

        ObjectNode normalized = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1)
                .put("status", "PUBLIC_OBSERVED").put("captured_at", "2026-01-01T00:02:00.000Z");
        normalized.set("request", object().put("endpoint", "fixture://source-chain"));
        normalized.set("response_sha256", array().add(rawSha));
        normalized.set("source_byte_sha256", array().add(rawSha));
        normalized.set("raw_receipts", array().add(rawReceipt));
        normalized.set("coverage", object().put("complete", true));
        normalized = StrategyResearchDataV5.withHash(normalized);
        Path receipt = root.resolve("receipts/source.json");
        Files.createDirectories(receipt.getParent());
        Files.write(receipt, JsonHashes.mapper().writeValueAsBytes(normalized));

        ObjectNode summary = object().put("schema", "strategy-v5-source-receipt/1")
                .put("path", "receipts/source.json").put("sha256", text(normalized, "content_sha256"))
                .put("content_sha256", text(normalized, "content_sha256")).put("byte_sha256", rawSha)
                .put("raw_count", 1).put("status", "PUBLIC_OBSERVED");
        ObjectNode capture = object().put("asset", "btc").put("venue", "BINANCE")
                .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT").put("interval", "4h")
                .put("series_type", "signal_bars").put("required", true)
                .put("adapter_code_sha256", StrategyResearchDataV5.javaAdapterCodeSha256())
                .put("producer_code_sha256", StrategyResearchDataV5.javaProducerCodeSha256());
        capture.set("partition", object().put("path", "staging/signal.jsonl")
                .put("sha256", StrategyResearchDataV5.hash(partitionBytes)).put("bytes", partitionBytes.length)
                .put("row_count", 1).put("format", "JSONL").put("storage_role", "STAGING")
                .put("authoritative", false));
        capture.set("source_receipts", array().add(summary));
        capture.set("coverage", object().put("complete", true));

        ObjectNode manifest = object().put("schema", "strategy-v5-authoritative-acquisition/1")
                .put("version", 1).put("status", "STAGING_COMPLETE").put("plan_sha256", PLAN_SHA)
                .put("root_reference", "source-chain-fixture").put("staging_format", "JSONL")
                .put("storage_role", "STAGING").put("authoritative", false).put("fixture_only", false)
                .put("provenance", "PUBLIC_ADAPTER_RECOMPUTED");
        manifest.set("captures", array().add(capture));
        manifest.set("source_receipts", array().add("receipts/source.json"));
        manifest.set("source_receipt_sha256", array().add(text(normalized, "content_sha256")));
        manifest.set("source_receipt_byte_sha256", array().add(rawSha));
        manifest.set("limitations", array());
        manifest = StrategyResearchDataV5.withHash(manifest);
        Path manifestPath = root.resolve("lineage/acquisition.json");
        Files.createDirectories(manifestPath.getParent());
        byte[] manifestBytes = JsonHashes.mapper().writeValueAsBytes(manifest);
        Files.write(manifestPath, manifestBytes);
        ObjectNode reference = object().put("path", "lineage/acquisition.json")
                .put("content_sha256", text(manifest, "content_sha256"))
                .put("byte_sha256", StrategyResearchDataV5.hash(manifestBytes));
        ObjectNode result = object();
        result.set("manifest", manifest);
        result.set("reference", reference);
        return result;
    }

    private static String text(ObjectNode value, String field) { return value.path(field).asText(); }
    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
}
