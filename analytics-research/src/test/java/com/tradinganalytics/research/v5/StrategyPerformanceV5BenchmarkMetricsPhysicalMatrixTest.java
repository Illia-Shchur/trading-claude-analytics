package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.marketdata.research.ResearchData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Metrics-specific fixtures cover required and optional field validation through public replay APIs. */
class StrategyPerformanceV5BenchmarkMetricsPhysicalMatrixTest {
    private static final ObjectMapper JSON = StrategyPerformanceV5.jsonMapper();

    @TempDir Path temporary;
    private int fixtureSequence;

    @Test
    void validMetricsReplaySupportsRawEventTimeFallbackAndReportsPhysicalRows() throws Exception {
        Fixture fixture = fixture();
        ObjectNode row = readRow(fixture);
        row.remove("event_time");
        row.put("raw_event_time", fixture.plan.path("series").get(0).path("start_at").asText());
        Files.writeString(fixture.staging.resolve("metrics.jsonl"), JSON.writeValueAsString(row) + "\n",
                StandardCharsets.UTF_8);
        refreshSourceMetadata(fixture);
        rebindParquetSource(fixture);
        ObjectNode result = runWithSource(fixture);
        assertThat(result.path("semantic").path("scanned_rows").asInt()).isEqualTo(1);
        assertThat(result.path("runtime").path("acquisition_source_rows").asInt()).isEqualTo(1);
        assertThat(result.path("semantic").path("source_complete").asBoolean()).isTrue();
    }

    @Test
    void sourceMetricsReplayRejectsMissingAndNonFiniteRequiredFields() throws Exception {
        assertSourceRejected("JSONL metrics semantic diagnostic", row -> row.remove("open_interest"));
        assertSourceRejected("JSONL metrics semantic diagnostic", row -> row.put("open_interest", "invalid"));
        assertSourceRejected("JSONL metrics semantic diagnostic", row -> row.put("open_interest_value", "invalid"));
    }

    @Test
    void parquetMetricsReplayRejectsMissingColumnsAndInvalidRequiredValues() throws Exception {
        assertParquetRejected("Parquet metrics semantic schema diagnostic", row -> row.remove("open_interest"));
        assertParquetRejected("Parquet metrics semantic value diagnostic", row -> row.putNull("open_interest"));
    }

    private void assertSourceRejected(String message, Consumer<ObjectNode> mutation) throws Exception {
        Fixture fixture = fixture();
        ObjectNode row = readRow(fixture);
        mutation.accept(row);
        Files.writeString(fixture.staging.resolve("metrics.jsonl"), JSON.writeValueAsString(row) + "\n",
                StandardCharsets.UTF_8);
        refreshSourceMetadata(fixture);
        rebindParquetSource(fixture);
        assertThatThrownBy(() -> runWithSource(fixture)).hasMessageContaining(message);
    }

    private void assertParquetRejected(String message, Consumer<ObjectNode> mutation) throws Exception {
        Fixture fixture = fixture();
        ObjectNode row = readRow(fixture);
        mutation.accept(row);
        Path alteredInput = fixture.staging.resolve("altered-metrics.jsonl");
        Files.writeString(alteredInput, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8);
        Path alteredParquet = fixture.parquetRoot.resolve("altered-metrics.parquet");
        ResearchData.ParquetArtifact physical = ResearchData.writeParquet(alteredInput, alteredParquet);
        ObjectNode parquet = fixture.parquet.deepCopy();
        ObjectNode partition = (ObjectNode) parquet.path("captures").get(0).path("partition");
        partition.put("path", "altered-metrics.parquet").put("sha256", physical.sha256()).put("bytes", physical.bytes())
                .put("schema_sha256", parquetSchemaHash(alteredParquet));
        parquet.put("dataset_root_sha256", recomputedDatasetRoot(parquet));
        parquet.put("content_sha256", StrategyPerformanceV5.ownHash(parquet));
        assertThatThrownBy(() -> runPhysicalOnly(fixture, parquet)).hasMessageContaining(message);
    }

    private ObjectNode runWithSource(Fixture fixture) {
        ObjectNode options = options(fixture);
        return StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(options);
    }

    private ObjectNode runPhysicalOnly(Fixture fixture, ObjectNode parquet) {
        ObjectNode options = options(fixture);
        options.remove("acquisitionRoot");
        options.set("parquetManifestPath", parquet);
        return StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(options);
    }

    private ObjectNode options(Fixture fixture) {
        ObjectNode options = object();
        options.set("planPath", fixture.plan);
        options.set("acquisitionManifestPath", fixture.acquisition);
        options.set("parquetManifestPath", fixture.parquet);
        return options.put("acquisitionRoot", fixture.staging.toString()).put("parquetRoot", fixture.parquetRoot.toString())
                .put("samplePartitions", 1).put("chunkBytes", 97);
    }

    private ObjectNode readRow(Fixture fixture) throws Exception {
        return (ObjectNode) JSON.readTree(Files.readString(fixture.staging.resolve("metrics.jsonl")));
    }

    private Fixture fixture() throws Exception {
        Path root = temporary.resolve("metrics-v5-" + fixtureSequence++);
        Path staging = root.resolve("staging");
        Path parquetRoot = root.resolve("parquet");
        Files.createDirectories(staging);
        Files.createDirectories(parquetRoot);

        ObjectNode base = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(
                object().put("asOf", "2026-08-24T00:00:00.000Z"));
        ObjectNode series = null;
        for (JsonNode raw : base.path("series")) {
            if ("metrics_events".equals(raw.path("series_type").asText())) {
                series = ((ObjectNode) raw).deepCopy();
                break;
            }
        }
        if (series == null) throw new IllegalStateException("five-year plan has no metrics series");
        String start = series.path("start_at").asText();
        String availability = Instant.ofEpochMilli(Instant.parse(start).toEpochMilli()
                + series.path("expected_step_ms").asLong() - 1).toString();
        series.put("end_at", start).put("availability_cutoff_at", availability).put("expected_event_count", 1);
        series.set("metric_required_fields", array().add("open_interest"));
        series.put("required", true);
        ObjectNode plan = base.deepCopy().set("series", array().add(series));
        plan.put("content_sha256", StrategyResearchDataV5.ownHash(plan));

        ObjectNode row = object().put("asset", series.path("asset").asText()).put("venue", series.path("venue").asText())
                .put("instrument", series.path("instrument").asText()).put("symbol", series.path("symbol").asText())
                .put("interval", series.path("interval").asText()).put("series_type", series.path("series_type").asText())
                .put("series_role", series.path("series_role").asText()).put("event_time", start)
                .put("availability_time", availability).put("open_interest", 42.0);
        Path jsonl = staging.resolve("metrics.jsonl");
        Files.writeString(jsonl, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8);
        byte[] bytes = Files.readAllBytes(jsonl);
        ObjectNode sourcePartition = object().put("path", "metrics.jsonl")
                .put("sha256", StrategyPerformanceV5.hashV5Performance(bytes)).put("bytes", bytes.length)
                .put("row_count", 1).put("format", "JSONL").put("storage_role", "STAGING").put("authoritative", false);
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

        Path parquetPath = parquetRoot.resolve("metrics.parquet");
        ResearchData.ParquetArtifact physical = ResearchData.writeParquet(jsonl, parquetPath);
        ObjectNode parquetPartition = object().put("path", "metrics.parquet").put("sha256", physical.sha256())
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
        ObjectNode dataset = object().put("source_manifest_sha256", acquisition.path("content_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText());
        String identity = series.path("asset").asText().toLowerCase(Locale.ROOT) + "|"
                + series.path("instrument").asText().toUpperCase(Locale.ROOT) + "|"
                + series.path("symbol").asText().toUpperCase(Locale.ROOT) + "|" + series.path("interval").asText()
                + "|" + series.path("series_type").asText().toLowerCase(Locale.ROOT);
        dataset.set("captures", array().add(object().put("identity", identity).set("partition", parquetPartition.deepCopy())));
        parquet.put("dataset_root_sha256", StrategyPerformanceV5.hashV5Performance(dataset));
        parquet.put("content_sha256", StrategyPerformanceV5.ownHash(parquet));
        return new Fixture(plan, acquisition, parquet, staging, parquetRoot);
    }

    private static String recomputedDatasetRoot(ObjectNode parquet) {
        ObjectNode dataset = object().put("source_manifest_sha256", parquet.path("source_manifest_sha256").asText())
                .put("plan_sha256", parquet.path("plan_sha256").asText());
        ArrayNode captures = array();
        for (JsonNode raw : parquet.path("captures")) {
            ObjectNode capture = (ObjectNode) raw;
            String identity = capture.path("asset").asText().toLowerCase(Locale.ROOT) + "|"
                    + capture.path("instrument").asText().toUpperCase(Locale.ROOT) + "|"
                    + capture.path("symbol").asText().toUpperCase(Locale.ROOT) + "|"
                    + capture.path("interval").asText() + "|" + capture.path("series_type").asText().toLowerCase(Locale.ROOT);
            captures.add(object().put("identity", identity).set("partition", capture.path("partition").deepCopy()));
        }
        dataset.set("captures", captures);
        return StrategyPerformanceV5.hashV5Performance(dataset);
    }

    private static void refreshSourceMetadata(Fixture fixture) throws Exception {
        Path source = fixture.staging.resolve("metrics.jsonl");
        byte[] bytes = Files.readAllBytes(source);
        ObjectNode partition = (ObjectNode) fixture.acquisition.path("captures").get(0).path("partition");
        partition.put("sha256", StrategyPerformanceV5.hashV5Performance(bytes)).put("bytes", bytes.length);
        fixture.acquisition.put("content_sha256", StrategyResearchDataV5.ownHash(fixture.acquisition));
    }

    private static void rebindParquetSource(Fixture fixture) {
        String sourceSha = fixture.acquisition.path("captures").get(0).path("partition").path("sha256").asText();
        ObjectNode partition = (ObjectNode) fixture.parquet.path("captures").get(0).path("partition");
        partition.put("source_jsonl_sha256", sourceSha);
        fixture.parquet.put("source_manifest_sha256", fixture.acquisition.path("content_sha256").asText());
        fixture.parquet.put("dataset_root_sha256", recomputedDatasetRoot(fixture.parquet));
        fixture.parquet.put("content_sha256", StrategyResearchDataV5.ownHash(fixture.parquet));
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
