package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.marketdata.research.ResearchData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic Parquet mutations exercise public physical-lineage contracts. */
class StrategyPerformanceV5BenchmarkParquetLineageMatrixTest {
    private static final ObjectMapper JSON = StrategyPerformanceV5.jsonMapper();

    @TempDir Path temporary;
    private int fixtureSequence;

    @Test
    void parquetPartitionBytesMustMatchTheOnDiskArtifact() throws Exception {
        Fixture fixture = fixture();
        ObjectNode parquet = fixture.parquet.deepCopy();
        ObjectNode partition = (ObjectNode) parquet.path("captures").get(0).path("partition");
        partition.put("bytes", partition.path("bytes").asLong() + 1);
        rebindParquetManifest(parquet);
        assertThatThrownBy(() -> runPhysicalOnly(fixture, parquet))
                .hasMessageContaining("Parquet partition bytes/hash are invalid");
    }

    @Test
    void parquetPartitionHashMustMatchTheOnDiskArtifact() throws Exception {
        Fixture fixture = fixture();
        ObjectNode parquet = fixture.parquet.deepCopy();
        ((ObjectNode) parquet.path("captures").get(0).path("partition")).put("sha256", "0".repeat(64));
        rebindParquetManifest(parquet);
        assertThatThrownBy(() -> runPhysicalOnly(fixture, parquet))
                .hasMessageContaining("Parquet partition bytes/hash are invalid");
    }

    @Test
    void parquetPartitionSchemaHashMustMatchReopenedSchema() throws Exception {
        Fixture fixture = fixture();
        ObjectNode parquet = fixture.parquet.deepCopy();
        ((ObjectNode) parquet.path("captures").get(0).path("partition")).put("schema_sha256", "0".repeat(64));
        rebindParquetManifest(parquet);
        assertThatThrownBy(() -> runPhysicalOnly(fixture, parquet))
                .hasMessageContaining("reopened Parquet schema differs from the manifest");
    }

    @Test
    void parquetPartitionRowCountMustMatchReopenedRows() throws Exception {
        Fixture fixture = fixture();
        ObjectNode parquet = fixture.parquet.deepCopy();
        ObjectNode capture = (ObjectNode) parquet.path("captures").get(0);
        ((ObjectNode) capture.path("partition")).put("row_count", 2);
        ((ObjectNode) capture.path("coverage")).put("observed_rows", 2);
        rebindParquetManifest(parquet);
        assertThatThrownBy(() -> runPhysicalOnly(fixture, parquet))
                .hasMessageContaining("reopened Parquet row count differs from the manifest");
    }

    @Test
    void parquetPartitionMustRemainLinkedToAcquisitionBytes() throws Exception {
        Fixture fixture = fixture();
        ObjectNode parquet = fixture.parquet.deepCopy();
        ((ObjectNode) parquet.path("captures").get(0).path("partition")).put("source_jsonl_sha256", "0".repeat(64));
        rebindParquetManifest(parquet);
        assertThatThrownBy(() -> runPhysicalOnly(fixture, parquet))
                .hasMessageContaining("Parquet partition is not linked to the matching acquisition bytes");
    }

    @Test
    void parquetCaptureSeriesBindingMustBeCurrent() throws Exception {
        Fixture fixture = fixture();
        ObjectNode parquet = fixture.parquet.deepCopy();
        ((ObjectNode) parquet.path("captures").get(0)).put("series_sha256", "0".repeat(64));
        rebindParquetManifest(parquet);
        assertThatThrownBy(() -> runPhysicalOnly(fixture, parquet))
                .hasMessageContaining("Parquet capture series binding is stale");
    }

    @Test
    void parquetCaptureMustMatchFrozenPlanRole() throws Exception {
        Fixture fixture = fixture();
        ObjectNode parquet = fixture.parquet.deepCopy();
        ((ObjectNode) parquet.path("captures").get(0)).put("series_role", "MUTATED_ROLE");
        rebindParquetManifest(parquet);
        assertThatThrownBy(() -> runPhysicalOnly(fixture, parquet))
                .hasMessageContaining("Parquet capture does not match frozen plan field series_role");
    }

    @Test
    void parquetManifestCannotDeclareDuplicateSeries() throws Exception {
        Fixture fixture = fixture();
        ObjectNode parquet = fixture.parquet.deepCopy();
        ObjectNode duplicate = ((ObjectNode) parquet.path("captures").get(0)).deepCopy();
        ((ObjectNode) duplicate.path("partition")).put("path", "duplicate.parquet");
        ((ArrayNode) parquet.path("captures")).add(duplicate);
        rebindParquetManifest(parquet);
        assertThatThrownBy(() -> runPhysicalOnly(fixture, parquet))
                .hasMessageContaining("Parquet manifest contains duplicate series");
    }

    @Test
    void parquetPartitionSupportsTheParquetStoragePrefixFallback() throws Exception {
        Fixture fixture = fixture();
        ObjectNode parquet = fixture.parquet.deepCopy();
        ((ObjectNode) parquet.path("captures").get(0).path("partition")).put("path", "parquet/capture.parquet");
        rebindParquetManifest(parquet);
        ObjectNode result = runPhysicalOnly(fixture, parquet);
        assertThat(result.path("semantic").path("scanned_rows").asInt()).isEqualTo(1);
        assertThat(result.path("semantic").path("scanned_partition_count").asInt()).isEqualTo(1);
    }

    @Test
    void parquetPartitionRejectsBackslashReferences() throws Exception {
        Fixture fixture = fixture();
        ObjectNode parquet = fixture.parquet.deepCopy();
        ((ObjectNode) parquet.path("captures").get(0).path("partition")).put("path", "nested\\capture.parquet");
        rebindParquetManifest(parquet);
        assertThatThrownBy(() -> runPhysicalOnly(fixture, parquet))
                .hasMessageContaining("Parquet partition must be a relative path inside its root");
    }

    private ObjectNode options(Fixture fixture) {
        ObjectNode value = object();
        value.set("planPath", fixture.plan);
        value.set("acquisitionManifestPath", fixture.acquisition);
        value.set("parquetManifestPath", fixture.parquet);
        return value.put("acquisitionRoot", fixture.staging.toString()).put("parquetRoot", fixture.parquetRoot.toString())
                .put("samplePartitions", 1).put("chunkBytes", 97);
    }

    private ObjectNode run(Fixture fixture) {
        ObjectNode options = options(fixture);
        options.remove("coveragePath");
        return StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(options);
    }

    private ObjectNode runPhysicalOnly(Fixture fixture, ObjectNode parquet) {
        ObjectNode options = options(fixture);
        options.remove("acquisitionRoot");
        options.set("parquetManifestPath", parquet);
        options.remove("coveragePath");
        return StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(options);
    }

    private void rebindAcquisitionAndParquet(Fixture fixture) {
        fixture.acquisition.put("content_sha256", StrategyResearchDataV5.ownHash(fixture.acquisition));
        fixture.parquet.put("source_manifest_sha256", fixture.acquisition.path("content_sha256").asText());
        rebindParquetManifest(fixture.parquet);
    }

    private void rebindParquetManifest(ObjectNode parquet) {
        parquet.put("dataset_root_sha256", recomputedDatasetRoot(parquet));
        parquet.put("content_sha256", StrategyPerformanceV5.ownHash(parquet));
    }

    private static String recomputedDatasetRoot(ObjectNode parquet) {
        ObjectNode dataset = object().put("source_manifest_sha256", parquet.path("source_manifest_sha256").asText())
                .put("plan_sha256", parquet.path("plan_sha256").asText());
        ArrayNode captures = array();
        for (var raw : parquet.path("captures")) {
            ObjectNode capture = (ObjectNode) raw;
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

    private static String identity(ObjectNode value) {
        return value.path("asset").asText().toLowerCase(Locale.ROOT) + "|"
                + value.path("instrument").asText().toLowerCase(Locale.ROOT) + "|"
                + value.path("symbol").asText().toLowerCase(Locale.ROOT) + "|"
                + value.path("interval").asText().toLowerCase(Locale.ROOT) + "|"
                + value.path("series_type").asText().toLowerCase(Locale.ROOT);
    }

    private Fixture fixture() throws Exception {
        Path root = temporary.resolve("one-row-v5-" + fixtureSequence++);
        Path staging = root.resolve("staging");
        Path parquetRoot = root.resolve("parquet");
        Files.createDirectories(staging);
        Files.createDirectories(parquetRoot);

        ObjectNode base = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(
                object().put("asOf", "2026-08-24T00:00:00.000Z"));
        ObjectNode series = ((ObjectNode) base.path("series").get(0)).deepCopy();
        String start = series.path("start_at").asText();
        String availability = Instant.ofEpochMilli(Instant.parse(start).toEpochMilli()
                + series.path("expected_step_ms").asLong() - 1).toString();
        series.put("end_at", start).put("availability_cutoff_at", availability).put("expected_event_count", 1);
        ObjectNode plan = base.deepCopy().set("series", array().add(series));
        plan.put("content_sha256", StrategyResearchDataV5.ownHash(plan));

        ObjectNode row = object().put("asset", series.path("asset").asText())
                .put("venue", series.path("venue").asText()).put("instrument", series.path("instrument").asText())
                .put("symbol", series.path("symbol").asText()).put("interval", series.path("interval").asText())
                .put("series_type", series.path("series_type").asText()).put("series_role", series.path("series_role").asText())
                .put("event_time", start).put("availability_time", availability)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100);
        Path jsonl = staging.resolve("capture.jsonl");
        Files.writeString(jsonl, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8);
        byte[] jsonlBytes = Files.readAllBytes(jsonl);
        ObjectNode sourcePartition = object().put("path", "capture.jsonl")
                .put("sha256", StrategyPerformanceV5.hashV5Performance(jsonlBytes)).put("bytes", jsonlBytes.length)
                .put("row_count", 1).put("format", "JSONL").put("storage_role", "STAGING")
                .put("authoritative", false);
        ObjectNode capture = series.deepCopy();
        capture.remove("trade_scope");
        capture.put("required", true).put("series_sha256", StrategyPerformanceV5.hashV5Performance(series));
        capture.set("coverage", object().put("complete", true).put("observed_rows", 1));
        capture.set("partition", sourcePartition);
        ObjectNode acquisition = object().put("schema", "strategy-v5-authoritative-acquisition/1").put("version", 1)
                .put("status", "STAGING_COMPLETE").put("plan_sha256", plan.path("content_sha256").asText())
                .put("root_reference", staging.toString()).put("staging_format", "JSONL")
                .put("storage_role", "STAGING").put("authoritative", false).put("base_complete", true)
                .put("declared_complete", true).put("full_plan_complete", true).put("completion_scope", "ALL_DECLARED")
                .put("required_series_count", 1).put("required_complete_count", 1).put("optional_series_count", 0)
                .put("optional_complete_count", 0).put("optional_complete", true);
        acquisition.set("captures", array().add(capture));
        acquisition.set("unavailable_required", array());
        acquisition.set("unavailable_optional", array());
        acquisition.put("content_sha256", StrategyResearchDataV5.ownHash(acquisition));

        Path parquetPath = parquetRoot.resolve("capture.parquet");
        ResearchData.ParquetArtifact physical = ResearchData.writeParquet(jsonl, parquetPath);
        ObjectNode parquetPartition = object().put("path", "capture.parquet").put("sha256", physical.sha256())
                .put("bytes", physical.bytes()).put("row_count", 1).put("format", "PARQUET")
                .put("storage_role", "AUTHORITATIVE").put("authoritative", true)
                .put("source_jsonl_sha256", sourcePartition.path("sha256").asText())
                .put("schema_sha256", parquetSchemaHash(parquetPath));
        ObjectNode promoted = capture.deepCopy();
        promoted.set("coverage", object().put("complete", true).put("expected_rows", 1).put("observed_rows", 1)
                .put("min_event_time", start).put("max_event_time", start));
        promoted.set("partition", parquetPartition);
        ObjectNode parquet = object().put("schema", "strategy-v5-parquet-conversion/1").put("version", 1)
                .put("status", "AUTHORITATIVE_PARQUET").put("source_manifest_sha256", acquisition.path("content_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText()).put("output_root_reference", parquetRoot.toString())
                .put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true).put("threads", 1);
        parquet.set("captures", array().add(promoted));
        String identity = series.path("asset").asText().toLowerCase(Locale.ROOT) + "|"
                + series.path("instrument").asText().toUpperCase(Locale.ROOT) + "|"
                + series.path("symbol").asText().toUpperCase(Locale.ROOT) + "|" + series.path("interval").asText()
                + "|" + series.path("series_type").asText().toLowerCase(Locale.ROOT);
        ObjectNode dataset = object().put("source_manifest_sha256", acquisition.path("content_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText());
        dataset.set("captures", array().add(object().put("identity", identity).set("partition", parquetPartition.deepCopy())));
        parquet.put("dataset_root_sha256", StrategyPerformanceV5.hashV5Performance(dataset));
        parquet.put("content_sha256", StrategyResearchDataV5.ownHash(parquet));
        return new Fixture(plan, acquisition, parquet, staging, parquetRoot);
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

    private record Fixture(ObjectNode plan, ObjectNode acquisition, ObjectNode parquet, Path staging, Path parquetRoot) {}
    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static ArrayNode array() { return JSON.createArrayNode(); }
}
