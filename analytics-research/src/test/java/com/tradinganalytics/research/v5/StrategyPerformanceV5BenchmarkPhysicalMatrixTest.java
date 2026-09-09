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

/** Deterministic source and Parquet mutations exercise the public data-plane validator. */
class StrategyPerformanceV5BenchmarkPhysicalMatrixTest {
    private static final ObjectMapper JSON = StrategyPerformanceV5.jsonMapper();

    @TempDir Path temporary;
    private int fixtureSequence;

    @Test
    void sourceSemanticGuardsRejectIdentityRoleBoundsAvailabilityAndValues() throws Exception {
        assertSourceRejected("JSONL semantic identity diagnostic", row -> row.put("symbol", "WRONG_SYMBOL"));
        assertSourceRejected("JSONL semantic role diagnostic", row -> row.put("series_role", "WRONG_ROLE"));
        assertSourceRejected("JSONL semantic event bound diagnostic", row -> row.put("event_time", "2099-01-01T00:00:00.000Z"));
        assertSourceRejected("JSONL semantic availability diagnostic", row -> row.put("availability_time", "2000-01-01T00:00:00.000Z"));
        assertSourceRejected("JSONL OHLC semantic diagnostic", row -> row.put("high", 90));
        assertSourceRejected("JSONL volume semantic diagnostic", row -> row.put("volume", -1));
        assertSourceRejected("JSONL completed-bar semantic diagnostic", row -> row.put("completed_bar", false));
    }

    @Test
    void parquetSemanticGuardsRejectMissingColumnsBoundsAndInvalidValues() throws Exception {
        assertParquetRejected("Parquet semantic schema diagnostic", row -> row.remove("venue"));
        assertParquetRejected("Parquet semantic event bounds diagnostic", row -> row.put("event_time", "2099-01-01T00:00:00.000Z"));
        assertParquetRejected("Parquet OHLC semantic value diagnostic", row -> row.put("high", 90));
        assertParquetRejected("Parquet volume semantic diagnostic", row -> row.put("volume", -1));
    }

    @Test
    void validPhysicalReplayReportsIndependentRowsAndBounds() throws Exception {
        Fixture fixture = fixture();
        ObjectNode result = runPhysicalOnly(fixture, fixture.parquet);
        assertThat(result.path("semantic").path("scanned_rows").asInt()).isEqualTo(1);
        assertThat(result.path("semantic").path("scanned_partition_count").asInt()).isEqualTo(1);
        assertThat(result.path("semantic").path("source_complete").asBoolean()).isTrue();
        assertThat(result.path("semantic").path("partitions").get(0).path("observed_min_event_time").asText())
                .isEqualTo(((ObjectNode) fixture.plan.path("series").get(0)).path("start_at").asText());
    }

    private void assertSourceRejected(String message, java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        Fixture fixture = fixture();
        Path source = fixture.staging.resolve("capture.jsonl");
        ObjectNode row = (ObjectNode) JSON.readTree(Files.readString(source));
        mutation.accept(row);
        Files.writeString(source, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> run(options(fixture), null)).hasMessageContaining(message);
    }

    private void assertParquetRejected(String message, java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        Fixture fixture = fixture();
        ObjectNode row = (ObjectNode) JSON.readTree(Files.readString(fixture.staging.resolve("capture.jsonl")));
        mutation.accept(row);
        Path alteredInput = fixture.staging.resolve("altered.jsonl");
        Files.writeString(alteredInput, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8);
        Path parquetPath = fixture.parquetRoot.resolve("altered.parquet");
        ResearchData.ParquetArtifact physical = ResearchData.writeParquet(alteredInput, parquetPath);
        ObjectNode parquet = fixture.parquet.deepCopy();
        ObjectNode partition = (ObjectNode) parquet.path("captures").get(0).path("partition");
        partition.put("path", "altered.parquet").put("sha256", physical.sha256()).put("bytes", physical.bytes())
                .put("schema_sha256", parquetSchemaHash(parquetPath));
        parquet.put("dataset_root_sha256", recomputedDatasetRoot(parquet));
        parquet.put("content_sha256", StrategyPerformanceV5.ownHash(parquet));
        assertThatThrownBy(() -> runPhysicalOnly(fixture, parquet)).hasMessageContaining(message);
    }

    private ObjectNode options(Fixture fixture) {
        ObjectNode value = object();
        value.set("planPath", fixture.plan);
        value.set("acquisitionManifestPath", fixture.acquisition);
        value.set("parquetManifestPath", fixture.parquet);
        return value.put("acquisitionRoot", fixture.staging.toString()).put("parquetRoot", fixture.parquetRoot.toString())
                .put("samplePartitions", 1).put("chunkBytes", 97);
    }

    private ObjectNode run(ObjectNode options, ObjectNode coverage) {
        ObjectNode value = options.deepCopy();
        value.set("coveragePath", coverage);
        return StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(value);
    }

    private ObjectNode runPhysicalOnly(Fixture fixture, ObjectNode parquet) {
        ObjectNode value = options(fixture);
        value.remove("acquisitionRoot");
        value.set("parquetManifestPath", parquet);
        value.remove("coveragePath");
        return StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(value);
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
