package com.tradinganalytics.marketdata.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ResearchDataTest {
    @TempDir Path temporary;

    @Test
    void originalDockerParquetContractRunsLocallyAndIsIdempotent() throws Exception {
        Path input = temporary.resolve("docker-contract-bars.jsonl");
        Files.writeString(input, """
                {"asset":"btc","time":"2026-01-01T00:00:00Z","availability_time":"2026-01-01T04:00:00Z","open":100,"high":101,"low":99,"close":100,"timeframe":"4h"}
                {"asset":"btc","time":"2026-01-01T04:00:00Z","availability_time":"2026-01-01T08:00:00Z","open":101,"high":102,"low":100,"close":101,"timeframe":"4h"}
                {"asset":"btc","time":"2026-01-01T08:00:00Z","availability_time":"2026-01-01T12:00:00Z","open":102,"high":103,"low":101,"close":102,"timeframe":"4h"}
                """, StandardCharsets.UTF_8);
        ResearchData.SnapshotOptions options = new ResearchData.SnapshotOptions(
                input, temporary.resolve("docker-contract-lake"), "btc-bars", "btc",
                "binance", "spot", "T0_IMMUTABLE_EVENT", "FEATURE", "parquet",
                "public", true, null, null, null, null, null, null);
        ResearchData.SnapshotResult first = ResearchData.snapshot(options);
        ResearchData.SnapshotResult second = ResearchData.snapshot(options);
        ObjectNode left = (ObjectNode) JsonHashes.parse(
                Files.readAllBytes(first.manifest()), "first manifest");
        ObjectNode right = (ObjectNode) JsonHashes.parse(
                Files.readAllBytes(second.manifest()), "second manifest");
        assertThat(left.path("content_sha256").asText())
                .isEqualTo(right.path("content_sha256").asText());
        assertThat(ResearchData.validateManifest(left, new ResearchData.ValidationOptions(
                "WALK_FORWARD_OOS", List.of("btc"), first.root()))).isTrue();
        Path parquet = first.root().resolve(first.feature().path("path").asText());
        assertThat(ResearchData.queryParquet(parquet,
                new ResearchData.QueryOptions(null, null, List.of("btc"))))
                .hasSize(3);
    }

    @Test
    void pureAndFilePrimitivesMatchTheNodeOracleExactly() throws Exception {
        Path csv = temporary.resolve("fixture.csv");
        try (InputStream input = resource("/oracles/research-data-v1-input.csv")) {
            Files.copy(input, csv);
        }

        ObjectNode input = JsonHashes.mapper().createObjectNode();
        input.put("csv", csv.toAbsolutePath().toString());
        ObjectNode hashValue = input.putObject("hashValue");
        hashValue.put("z", 2);
        hashValue.putObject("a").put("nested", true);
        hashValue.put("content_sha256", "retained-value-is-excluded");
        ArrayNode raw = input.putArray("raw");
        raw.addObject().put("asset", "ETH").put("time", "2026-01-01T04:00:00Z")
                .put("availability_time", "2026-01-01T08:00:00Z").put("close", 2);
        raw.addObject().put("asset", "btc").put("time", "2026-01-01T00:00:00Z")
                .put("availability_time", "2026-01-01T04:00:00Z").put("close", 1);
        ObjectNode options = input.putObject("options");
        options.put("venue", "binance");
        options.put("instrument", "spot");
        options.put("timeframe", "4h");
        options.put("datasetId", "oracle-bars");
        options.put("pitTier", "T0_IMMUTABLE_EVENT");
        options.put("role", "FEATURE");
        options.put("source", "fixture-ohlc");
        ArrayNode splitInput = input.putArray("splitInput");
        splitInput.addObject().put("event_time", 1).put("asset", "btc")
                .put("dataset_id", "d").put("close", 10).put("forward_return", .2)
                .put("resolved_at", 2);
        ObjectNode lineage = input.putObject("lineage");
        lineage.put("adapter_sha256", "a".repeat(64));
        lineage.put("code_sha256", "b".repeat(64));
        lineage.put("container_sha256", "c".repeat(64));
        lineage.put("config_sha256", "d".repeat(64));

        JsonNode oracle = readJsonResource("/oracles/research-data-v1.json");

        assertCanonicalEqual(ResearchData.readRows(csv), oracle.path("rows"));
        assertThat(ResearchData.canonicalHash(hashValue)).isEqualTo(oracle.path("hash").asText());
        assertCanonicalEqual(ResearchData.withHash(hashValue), oracle.path("withHash"));

        List<ObjectNode> normalized = ResearchData.normalizeRows(nodes(raw),
                new ResearchData.NormalizeOptions(null, "binance", "spot", "4h",
                        "oracle-bars", "T0_IMMUTABLE_EVENT", "FEATURE",
                        "fixture-ohlc", "completed_bar"));
        assertCanonicalEqual(normalized, oracle.path("normalized"));
        ResearchData.SplitRows split = ResearchData.splitFeatureLabels(nodes(splitInput));
        ObjectNode splitValue = JsonHashes.mapper().createObjectNode();
        splitValue.set("features", JsonHashes.mapper().valueToTree(split.features()));
        splitValue.set("labels", JsonHashes.mapper().valueToTree(split.labels()));
        assertCanonicalEqual(splitValue, oracle.path("split"));

        ObjectNode manifest = ResearchData.buildManifest(new ResearchData.ManifestOptions(
                "oracle-manifest", normalized, List.of(), List.of(), null, "FEATURE",
                "fixture-ohlc", null, List.of(), true, lineage));
        assertCanonicalEqual(manifest, oracle.path("manifest"));
        ObjectNode feature = ResearchData.buildFeatureSet(new ResearchData.FeatureSetOptions(
                null, "a".repeat(64), "b".repeat(64), JsonHashes.mapper().createArrayNode(),
                2, JsonHashes.mapper().createObjectNode().put("btc", 1),
                JsonHashes.mapper().createArrayNode()));
        assertCanonicalEqual(feature, oracle.path("feature"));
        ObjectNode horizon = JsonHashes.mapper().createObjectNode().put("bars", 2).put("unit", "bars");
        ObjectNode label = ResearchData.buildLabelSet(new ResearchData.LabelSetOptions(
                null, "a".repeat(64), "c".repeat(64), horizon,
                JsonHashes.mapper().createArrayNode()));
        assertCanonicalEqual(label, oracle.path("label"));

        assertThat(ResearchData.DATASET_MANIFEST_SCHEMA).isEqualTo("strategy-data-manifest/2");
        assertThat(ResearchData.CORE_CRYPTO_ASSETS)
                .containsExactly("btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
    }

    @Test
    void normalizationAndManifestValidationFailClosedOnPitViolations() {
        ObjectNode future = JsonHashes.mapper().createObjectNode().put("asset", "btc")
                .put("time", 1).put("availability_time", 2).put("forward_return", 1);
        assertThatThrownBy(() -> ResearchData.normalizeRows(List.of(future),
                options("FEATURE", "ohlc", "T0_IMMUTABLE_EVENT")))
                .hasMessageContaining("future-label field forward_return");
        ObjectNode missingAvailability = JsonHashes.mapper().createObjectNode()
                .put("asset", "btc").put("time", 1);
        assertThatThrownBy(() -> ResearchData.normalizeRows(List.of(missingAvailability),
                options("FEATURE", "ohlc", "T0_IMMUTABLE_EVENT")))
                .hasMessageContaining("availability_time is required");
        ObjectNode nonCrypto = JsonHashes.mapper().createObjectNode().put("asset", "dxy")
                .put("asset_class", "index").put("time", 1).put("availability_time", 2);
        assertThatThrownBy(() -> ResearchData.normalizeRows(List.of(nonCrypto),
                options("FEATURE", "fred", "T1_PUBLICATION_VINTAGE")))
                .hasMessageContaining("non-crypto data cannot enter FEATURE");
        ObjectNode context = nonCrypto.deepCopy().put("asset_class", "context");
        assertThat(ResearchData.normalizeRows(List.of(context),
                options("CONTEXT", "fred", "T1_PUBLICATION_VINTAGE")).get(0)
                .path("asset_class").asText()).isEqualTo("context");
        assertThatThrownBy(() -> ResearchData.rowTime(
                JsonHashes.mapper().createObjectNode().put("time", "not-a-time")))
                .hasMessage("row event_time must be a valid timestamp");
        ObjectNode nested = JsonHashes.mapper().createObjectNode();
        nested.putObject("nested").put("target", 1);
        assertThat(ResearchData.findFutureLabel(nested))
                .isEqualTo("nested.target");
    }

    @Test
    void duckDbJdbcProducesRealImmutableParquetAndSupportsBoundedQueries() throws Exception {
        Path staging = temporary.resolve("staging.jsonl");
        Files.writeString(staging, """
                {"asset":"btc","event_time":0,"availability_time":1,"close":100.5}
                {"asset":"eth","event_time":14400000,"availability_time":14400001,"close":200.5}
                {"asset":"btc","event_time":28800000,"availability_time":28800001,"close":101.5}
                """, StandardCharsets.UTF_8);
        Path parquet = temporary.resolve("bars.parquet");
        ResearchData.ParquetArtifact first = ResearchData.writeParquet(staging, parquet);
        byte[] bytes = Files.readAllBytes(parquet);
        assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("PAR1");
        assertThat(new String(bytes, bytes.length - 4, 4, StandardCharsets.US_ASCII)).isEqualTo("PAR1");
        assertThat(first.sha256()).isEqualTo(JsonHashes.sha256(parquet));

        List<ObjectNode> rows = ResearchData.queryParquet(parquet,
                new ResearchData.QueryOptions(1L, 30_000_000L, List.of("BTC")));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("event_time").asLong()).isEqualTo(28_800_000L);
        assertThat(rows.get(0).path("close").asDouble()).isEqualTo(101.5);
        assertThat(ResearchData.writeParquet(staging, parquet).sha256()).isEqualTo(first.sha256());

        Files.writeString(staging,
                "{\"asset\":\"btc\",\"event_time\":0,\"availability_time\":1,\"close\":999}\n");
        assertThatThrownBy(() -> ResearchData.writeParquet(staging, parquet))
                .hasMessageContaining("immutable Parquet artifact collision");
        Path alias = temporary.resolve("bars-hardlink.parquet");
        Files.createLink(alias, parquet);
        assertThatThrownBy(() -> ResearchData.queryParquet(parquet))
                .hasMessageContaining("singly-linked");
    }

    @Test
    void snapshotIsImmutablePitValidatedAndConfinesPartitionComponents() throws Exception {
        Path input = temporary.resolve("labels.jsonl");
        Files.writeString(input, """
                {"asset":"btc","time":"2026-01-01T00:00:00Z","availability_time":"2026-01-01T04:00:00Z","venue":"../../escape","instrument":"spot","close":100,"forward_return":0.1,"resolution_bars":1}
                {"asset":"btc","time":"2026-01-01T04:00:00Z","availability_time":"2026-01-01T08:00:00Z","venue":"../../escape","instrument":"spot","close":101,"forward_return":0.2,"resolution_bars":1}
                """);
        Path lake = temporary.resolve("lake");
        ObjectNode horizon = JsonHashes.mapper().createObjectNode().put("bars", 1).put("unit", "bars");
        ResearchData.SnapshotOptions options = new ResearchData.SnapshotOptions(
                input, lake, "core", "btc", null, null, "T0_IMMUTABLE_EVENT",
                "FEATURE", "jsonl", "fixture-ohlc", true, null, null, null, null,
                horizon, null);
        ResearchData.SnapshotResult first = ResearchData.snapshot(options);
        ResearchData.SnapshotResult second = ResearchData.snapshot(options);
        ObjectNode manifest = (ObjectNode) JsonHashes.parse(
                Files.readAllBytes(first.manifest()), "manifest");
        ObjectNode again = (ObjectNode) JsonHashes.parse(
                Files.readAllBytes(second.manifest()), "manifest");
        assertThat(manifest.path("content_sha256").asText())
                .isEqualTo(again.path("content_sha256").asText());
        assertThat(ResearchData.validateManifest(manifest, new ResearchData.ValidationOptions(
                "DEVELOPMENT", List.of("btc"), first.root()))).isTrue();
        assertThatThrownBy(() -> ResearchData.validateManifest(manifest,
                new ResearchData.ValidationOptions("WALK_FORWARD_OOS", List.of("btc"), first.root())))
                .hasMessageContaining("JSONL/staging");
        manifest.path("feature_store").path("partitions").forEach(partition -> {
            assertThat(partition.path("path").asText()).doesNotContain("../", "../../escape");
            assertThat(Files.exists(first.root().resolve(partition.path("path").asText()))).isTrue();
        });

        Path feature = first.root().resolve(manifest.path("feature_store").path("path").asText());
        Path hardlink = first.root().resolve("feature-alias.jsonl");
        Files.createLink(hardlink, feature);
        assertThatThrownBy(() -> ResearchData.validateManifest(manifest,
                new ResearchData.ValidationOptions("DEVELOPMENT", List.of(), first.root())))
                .hasMessageContaining("singly-linked");
        Files.delete(hardlink);

        byte[] retained = Files.readAllBytes(feature);
        Path external = temporary.resolve("external.jsonl");
        Files.write(external, retained);
        Files.delete(feature);
        Files.createSymbolicLink(feature, external);
        assertThatThrownBy(() -> ResearchData.validateManifest(manifest,
                new ResearchData.ValidationOptions("DEVELOPMENT", List.of(), first.root())))
                .hasMessageContaining("symlink");
        Files.delete(feature);
        Files.write(feature, retained);
        Files.writeString(feature, "tampered\n");
        assertThatThrownBy(() -> ResearchData.snapshot(options))
                .hasMessageContaining("immutable artifact collision");
    }

    private static ResearchData.NormalizeOptions options(String role, String source, String tier) {
        return new ResearchData.NormalizeOptions(null, null, null, "4h", "dataset", tier,
                role, source, "completed_bar");
    }

    private static List<ObjectNode> nodes(ArrayNode values) {
        return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                .map(value -> ((ObjectNode) value).deepCopy()).toList();
    }

    private static void assertCanonicalEqual(Object actual, JsonNode expected) {
        assertThat(JsonHashes.canonicalString(actual))
                .isEqualTo(JsonHashes.canonicalString(expected));
    }

    private static InputStream resource(String name) throws IOException {
        return Objects.requireNonNull(ResearchDataTest.class.getResourceAsStream(name),
                "frozen oracle resource is missing: " + name);
    }

    private static JsonNode readJsonResource(String name) throws IOException {
        try (InputStream input = resource(name)) {
            return JsonHashes.mapper().readTree(input);
        }
    }
}
