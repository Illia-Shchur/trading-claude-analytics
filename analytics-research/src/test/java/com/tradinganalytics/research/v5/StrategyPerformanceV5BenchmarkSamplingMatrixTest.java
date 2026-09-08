package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.marketdata.research.ResearchData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public sampling and root-inference contracts over two independent spot partitions. */
class StrategyPerformanceV5BenchmarkSamplingMatrixTest {
    private static final ObjectMapper JSON = StrategyPerformanceV5.jsonMapper();

    @TempDir Path temporary;

    @Test
    void sampledReplayScansOnlyTheRequestedPartition() throws Exception {
        Fixture fixture = fixture();
        ObjectNode result = run(fixture, options(fixture).put("samplePartitions", 1));

        assertThat(result.path("semantic").path("mode").asText()).isEqualTo("SAMPLED");
        assertThat(result.path("semantic").path("scanned_partition_count").asInt()).isEqualTo(1);
        assertThat(result.path("semantic").path("scanned_rows").asInt()).isEqualTo(1);
        assertThat(result.path("runtime").path("acquisition_source_partitions").asInt()).isEqualTo(1);
        assertThat(result.path("runtime").path("acquisition_source_rows").asInt()).isEqualTo(1);
        assertThat(result.path("semantic").path("partitions").get(0).path("asset").asText())
                .isEqualTo("btc");
    }

    @Test
    void sampledReplayScansBothPartitionsWhenRequested() throws Exception {
        Fixture fixture = fixture();
        ObjectNode result = run(fixture, options(fixture).put("samplePartitions", 2));

        assertThat(result.path("semantic").path("scanned_partition_count").asInt()).isEqualTo(2);
        assertThat(result.path("semantic").path("scanned_rows").asInt()).isEqualTo(2);
        assertThat(result.path("runtime").path("acquisition_source_partitions").asInt()).isEqualTo(2);
        assertThat(result.path("runtime").path("acquisition_source_rows").asInt()).isEqualTo(2);
        assertThat(result.path("semantic").path("scanned_assets").toString())
                .contains("btc", "eth");
    }

    @Test
    void fileBackedFullReplayInfersAdjacentRoots() throws Exception {
        Fixture fixture = fixture();
        Path manifests = fixture.root.resolve("manifests");
        Files.createDirectories(manifests);
        Path planPath = manifests.resolve("plan.json");
        Files.writeString(planPath, JSON.writeValueAsString(fixture.plan), StandardCharsets.UTF_8);
        Path acquisitionPath = fixture.staging.resolve("acquisition.json");
        Path parquetPath = fixture.parquetRoot.resolve("parquet-manifest.json");
        Files.writeString(acquisitionPath, JSON.writeValueAsString(fixture.acquisition), StandardCharsets.UTF_8);
        Files.writeString(parquetPath, JSON.writeValueAsString(fixture.parquet), StandardCharsets.UTF_8);

        ObjectNode options = object().put("planPath", planPath.toString())
                .put("acquisitionManifestPath", acquisitionPath.toString())
                .put("parquetManifestPath", parquetPath.toString())
                .put("full", true).put("samplePartitions", 1).put("chunkBytes", 97);
        ObjectNode result = run(fixture, options);

        assertThat(result.path("semantic").path("mode").asText()).isEqualTo("FULL");
        assertThat(result.path("semantic").path("scanned_partition_count").asInt()).isEqualTo(2);
        assertThat(result.path("semantic").path("scanned_rows").asInt()).isEqualTo(2);
        assertThat(result.path("runtime").path("acquisition_source_partitions").asInt()).isEqualTo(2);
        assertThat(result.path("runtime").path("acquisition_source_rows").asInt()).isEqualTo(2);
        assertThat(result.path("readiness").path("data_plane").path("status").asText())
                .isEqualTo("BLOCKED_REQUIRES_AUTHORITATIVE_COVERAGE");
    }

    private ObjectNode run(Fixture fixture, ObjectNode options) {
        return StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(options);
    }

    private ObjectNode options(Fixture fixture) {
        ObjectNode options = object();
        options.set("planPath", fixture.plan);
        options.set("acquisitionManifestPath", fixture.acquisition);
        options.set("parquetManifestPath", fixture.parquet);
        return options.put("acquisitionRoot", fixture.staging.toString())
                .put("parquetRoot", fixture.parquetRoot.toString())
                .put("chunkBytes", 97);
    }

    private Fixture fixture() throws Exception {
        Path root = temporary.resolve("sampling-v5");
        Path staging = root.resolve("staging");
        Path parquetRoot = root.resolve("parquet");
        Files.createDirectories(staging);
        Files.createDirectories(parquetRoot);

        ObjectNode base = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(
                object().put("asOf", "2026-08-24T00:00:00.000Z"));
        List<ObjectNode> seriesRows = new ArrayList<>();
        for (JsonNode raw : base.path("series")) {
            if ("signal_bars".equals(raw.path("series_type").asText())
                    && "BINANCE_SPOT".equals(raw.path("instrument").asText())
                    && ("btc".equals(raw.path("asset").asText()) || "eth".equals(raw.path("asset").asText()))) {
                ObjectNode series = ((ObjectNode) raw).deepCopy();
                String start = series.path("start_at").asText();
                String availability = Instant.ofEpochMilli(Instant.parse(start).toEpochMilli()
                        + series.path("expected_step_ms").asLong() - 1).toString();
                series.put("end_at", start).put("availability_cutoff_at", availability)
                        .put("expected_event_count", 1);
                seriesRows.add(series);
            }
        }
        assertThat(seriesRows).hasSize(2);
        ObjectNode plan = base.deepCopy().set("series", array());
        for (ObjectNode series : seriesRows) ((ArrayNode) plan.get("series")).add(series);
        plan.put("content_sha256", StrategyResearchDataV5.ownHash(plan));

        ArrayNode captures = array();
        ArrayNode parquetCaptures = array();
        for (ObjectNode series : seriesRows) {
            String asset = series.path("asset").asText();
            String start = series.path("start_at").asText();
            String availability = series.path("availability_cutoff_at").asText();
            ObjectNode row = object().put("asset", asset).put("venue", series.path("venue").asText())
                    .put("instrument", series.path("instrument").asText()).put("symbol", series.path("symbol").asText())
                    .put("interval", series.path("interval").asText()).put("series_type", series.path("series_type").asText())
                    .put("series_role", series.path("series_role").asText()).put("event_time", start)
                    .put("availability_time", availability).put("open", asset.equals("btc") ? 100 : 200)
                    .put("high", asset.equals("btc") ? 101 : 201).put("low", asset.equals("btc") ? 99 : 199)
                    .put("close", asset.equals("btc") ? 100.5 : 200.5);
            Path source = staging.resolve(asset + ".jsonl");
            Files.writeString(source, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8);
            byte[] sourceBytes = Files.readAllBytes(source);
            ObjectNode sourcePartition = object().put("path", asset + ".jsonl")
                    .put("sha256", StrategyPerformanceV5.hashV5Performance(sourceBytes)).put("bytes", sourceBytes.length)
                    .put("row_count", 1).put("format", "JSONL").put("storage_role", "STAGING").put("authoritative", false);
            ObjectNode capture = series.deepCopy();
            capture.remove("trade_scope");
            capture.put("required", true).put("series_sha256", StrategyPerformanceV5.hashV5Performance(series));
            capture.set("coverage", object().put("complete", true).put("observed_rows", 1));
            capture.set("partition", sourcePartition);
            captures.add(capture);

            Path physicalPath = parquetRoot.resolve(asset + ".parquet");
            ResearchData.ParquetArtifact physical = ResearchData.writeParquet(source, physicalPath);
            ObjectNode parquetPartition = object().put("path", asset + ".parquet").put("sha256", physical.sha256())
                    .put("bytes", physical.bytes()).put("row_count", 1).put("format", "PARQUET")
                    .put("storage_role", "AUTHORITATIVE").put("authoritative", true)
                    .put("source_jsonl_sha256", sourcePartition.path("sha256").asText())
                    .put("schema_sha256", parquetSchemaHash(physicalPath));
            ObjectNode promoted = capture.deepCopy();
            promoted.set("coverage", object().put("complete", true).put("expected_rows", 1).put("observed_rows", 1)
                    .put("min_event_time", start).put("max_event_time", start));
            promoted.set("partition", parquetPartition);
            parquetCaptures.add(promoted);
        }

        ObjectNode acquisition = object().put("schema", "strategy-v5-authoritative-acquisition/1").put("version", 1)
                .put("status", "STAGING_COMPLETE").put("plan_sha256", plan.path("content_sha256").asText())
                .put("root_reference", staging.toString()).put("staging_format", "JSONL")
                .put("storage_role", "STAGING").put("authoritative", false).put("base_complete", true)
                .put("declared_complete", true).put("full_plan_complete", true).put("completion_scope", "ALL_DECLARED")
                .put("required_series_count", 2).put("required_complete_count", 2).put("optional_series_count", 0)
                .put("optional_complete_count", 0).put("optional_complete", true);
        acquisition.set("captures", captures);
        acquisition.set("unavailable_required", array());
        acquisition.set("unavailable_optional", array());
        acquisition.put("content_sha256", StrategyResearchDataV5.ownHash(acquisition));

        ObjectNode parquet = object().put("schema", "strategy-v5-parquet-conversion/1").put("version", 1)
                .put("status", "AUTHORITATIVE_PARQUET").put("source_manifest_sha256", acquisition.path("content_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText()).put("output_root_reference", parquetRoot.toString())
                .put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true).put("threads", 1);
        parquet.set("captures", parquetCaptures);
        parquet.put("dataset_root_sha256", recomputedDatasetRoot(parquet));
        parquet.put("content_sha256", StrategyResearchDataV5.ownHash(parquet));
        return new Fixture(root, plan, acquisition, parquet, staging, parquetRoot);
    }

    private static String recomputedDatasetRoot(ObjectNode parquet) {
        ObjectNode dataset = object().put("source_manifest_sha256", parquet.path("source_manifest_sha256").asText())
                .put("plan_sha256", parquet.path("plan_sha256").asText());
        ArrayNode captures = array();
        List<ObjectNode> sorted = new ArrayList<>();
        for (JsonNode raw : parquet.path("captures")) sorted.add((ObjectNode) raw);
        sorted.sort(java.util.Comparator.comparing(row -> text(row.path("partition").path("path"))));
        for (ObjectNode capture : sorted) {
            ObjectNode value = object().put("identity", datasetIdentity(capture));
            value.set("partition", capture.path("partition").deepCopy());
            captures.add(value);
        }
        dataset.set("captures", captures);
        return StrategyPerformanceV5.hashV5Performance(dataset);
    }

    private static String datasetIdentity(ObjectNode value) {
        return value.path("asset").asText().toLowerCase(Locale.ROOT) + "|"
                + value.path("instrument").asText().toUpperCase(Locale.ROOT) + "|"
                + value.path("symbol").asText().toUpperCase(Locale.ROOT) + "|"
                + value.path("interval").asText() + "|"
                + value.path("series_type").asText().toLowerCase(Locale.ROOT);
    }

    private static String parquetSchemaHash(Path parquet) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:duckdb:")) {
            var statement = connection.createStatement();
            var result = statement.executeQuery("DESCRIBE SELECT * FROM read_parquet('" + parquet.toString().replace("'", "''") + "')");
            ArrayNode rows = array();
            while (result.next()) {
                ArrayNode row = array();
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    Object value = result.getObject(column);
                    if (value == null) row.addNull(); else row.add(String.valueOf(value));
                }
                rows.add(row);
            }
            result.close();
            statement.close();
            return StrategyPerformanceV5.hashV5Performance(rows);
        }
    }

    private static String text(JsonNode node) { return node == null || node.isNull() ? "" : node.asText(); }
    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static ArrayNode array() { return JSON.createArrayNode(); }
    private record Fixture(Path root, ObjectNode plan, ObjectNode acquisition, ObjectNode parquet, Path staging, Path parquetRoot) {}
}
