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

/** Deterministic source-row variants exercise optional JSONL semantic contracts. */
class StrategyPerformanceV5BenchmarkJsonlSemanticOptionalMatrixTest {
    private static final ObjectMapper JSON = StrategyPerformanceV5.jsonMapper();

    @TempDir Path temporary;
    private int fixtureSequence;

    @Test
    void sourceReplayAcceptsRawEventTimeFallback() throws Exception {
        Fixture fixture = fixture();
        ObjectNode row = (ObjectNode) JSON.readTree(Files.readString(fixture.staging.resolve("capture.jsonl")));
        row.put("raw_event_time", row.get("event_time"));
        row.remove("event_time");
        rebindSource(fixture, row);
        ObjectNode result = runSource(fixture);
        assertThat(result.path("semantic").path("source_complete").asBoolean()).isTrue();
        assertThat(result.path("semantic").path("scanned_rows").asInt()).isEqualTo(1);
        assertThat(result.path("semantic").path("partitions").get(0).path("observed_min_event_time").asText())
                .isEqualTo(((ObjectNode) fixture.plan.path("series").get(0)).path("start_at").asText());
    }

    @Test
    void sourceReplayAcceptsSettlementAvailabilityFallback() throws Exception {
        Fixture fixture = fixture();
        ObjectNode row = (ObjectNode) JSON.readTree(Files.readString(fixture.staging.resolve("capture.jsonl")));
        row.put("settlement_mark_availability_time", row.get("availability_time"));
        row.remove("availability_time");
        rebindSource(fixture, row);
        ObjectNode result = runSource(fixture);
        assertThat(result.path("semantic").path("source_complete").asBoolean()).isTrue();
        assertThat(result.path("semantic").path("scanned_rows").asInt()).isEqualTo(1);
    }

    @Test
    void sourceReplayAcceptsOptionalVolumeAndCompletedFlag() throws Exception {
        Fixture fixture = fixture();
        ObjectNode row = (ObjectNode) JSON.readTree(Files.readString(fixture.staging.resolve("capture.jsonl")));
        row.put("volume", 0).put("completed_bar", true);
        rebindSource(fixture, row);
        ObjectNode result = runSource(fixture);
        assertThat(result.path("semantic").path("source_complete").asBoolean()).isTrue();
        assertThat(result.path("semantic").path("scanned_rows").asInt()).isEqualTo(1);
    }

    @Test
    void sourceReplayRejectsRowsOutsideFrozenOrder() throws Exception {
        Fixture fixture = fixture();
        ObjectNode row = (ObjectNode) JSON.readTree(Files.readString(fixture.staging.resolve("capture.jsonl")));
        ObjectNode series = (ObjectNode) fixture.plan.path("series").get(0);
        long start = Instant.parse(series.path("start_at").asText()).toEpochMilli();
        long step = series.path("expected_step_ms").asLong();
        String laterTime = Instant.ofEpochMilli(start + step).toString();
        String laterAvailability = Instant.ofEpochMilli(start + 2 * step - 1).toString();
        ObjectNode later = row.deepCopy().put("event_time", laterTime).put("availability_time", laterAvailability);
        // Widen the frozen contract to two rows and create a valid chronological
        // Parquet conversion before corrupting only the JSONL source order.
        series.put("end_at", laterTime).put("availability_cutoff_at", laterAvailability);
        series.put("expected_event_count", 2);
        fixture.plan.put("content_sha256", StrategyResearchDataV5.ownHash(fixture.plan));
        ObjectNode capture = (ObjectNode) fixture.acquisition.path("captures").get(0);
        capture.put("end_at", laterTime).put("availability_cutoff_at", laterAvailability).put("expected_event_count", 2)
                .put("series_sha256", StrategyPerformanceV5.hashV5Performance(series));
        ((ObjectNode) capture.path("coverage")).put("expected_rows", 2).put("observed_rows", 2)
                .put("min_event_time", row.path("event_time").asText()).put("max_event_time", laterTime)
                .put("min_availability_time", row.path("availability_time").asText()).put("max_availability_time", laterAvailability);
        fixture.acquisition.put("plan_sha256", fixture.plan.path("content_sha256").asText());
        Path physicalInput = fixture.staging.resolve("chronological.jsonl");
        Files.writeString(physicalInput, JSON.writeValueAsString(row) + "\n" + JSON.writeValueAsString(later) + "\n", StandardCharsets.UTF_8);
        Path physicalParquet = fixture.parquetRoot.resolve("capture-two.parquet");
        ResearchData.ParquetArtifact physical = ResearchData.writeParquet(physicalInput, physicalParquet);
        ObjectNode promoted = (ObjectNode) fixture.parquet.path("captures").get(0);
        promoted.put("end_at", laterTime).put("availability_cutoff_at", laterAvailability).put("expected_event_count", 2)
                .put("series_sha256", StrategyPerformanceV5.hashV5Performance(series));
        promoted.set("coverage", object().put("complete", true).put("expected_rows", 2).put("observed_rows", 2)
                .put("min_event_time", row.path("event_time").asText()).put("max_event_time", laterTime));
        ObjectNode parquetPartition = (ObjectNode) promoted.path("partition");
        parquetPartition.put("path", "capture-two.parquet").put("sha256", physical.sha256()).put("bytes", physical.bytes())
                .put("row_count", 2).put("schema_sha256", parquetSchemaHash(physicalParquet));
        fixture.parquet.put("plan_sha256", fixture.plan.path("content_sha256").asText());
        Path source = fixture.staging.resolve("capture.jsonl");
        // The source now has the same two records in reverse order. Its hash is
        // rebound below, so the failure is caused by ordering rather than stale
        // partition metadata.
        Files.writeString(source, JSON.writeValueAsString(later) + "\n" + JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8);
        byte[] bytes = Files.readAllBytes(source);
        ObjectNode sourcePartition = (ObjectNode) capture.path("partition");
        sourcePartition.put("sha256", StrategyPerformanceV5.hashV5Performance(bytes)).put("bytes", bytes.length).put("row_count", 2);
        fixture.acquisition.put("content_sha256", StrategyResearchDataV5.ownHash(fixture.acquisition));
        parquetPartition.put("source_jsonl_sha256", sourcePartition.path("sha256").asText());
        fixture.parquet.put("source_manifest_sha256", fixture.acquisition.path("content_sha256").asText());
        fixture.parquet.put("dataset_root_sha256", recomputedDatasetRoot(fixture.parquet));
        fixture.parquet.put("content_sha256", StrategyPerformanceV5.ownHash(fixture.parquet));
        assertThatThrownBy(() -> runSource(fixture)).hasMessageContaining("JSONL semantic ordering diagnostic");
    }

    private ObjectNode options(Fixture fixture) {
        ObjectNode value = object();
        value.set("planPath", fixture.plan);
        value.set("acquisitionManifestPath", fixture.acquisition);
        value.set("parquetManifestPath", fixture.parquet);
        return value.put("acquisitionRoot", fixture.staging.toString()).put("parquetRoot", fixture.parquetRoot.toString())
                .put("samplePartitions", 1).put("chunkBytes", 97);
    }

    private ObjectNode runSource(Fixture fixture) {
        ObjectNode value = options(fixture);
        value.remove("coveragePath");
        return StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(value);
    }

    private void rebindSource(Fixture fixture, ObjectNode row) throws Exception {
        Path source = fixture.staging.resolve("capture.jsonl");
        Files.writeString(source, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8);
        byte[] bytes = Files.readAllBytes(source);
        ObjectNode sourcePartition = (ObjectNode) fixture.acquisition.path("captures").get(0).path("partition");
        sourcePartition.put("sha256", StrategyPerformanceV5.hashV5Performance(bytes)).put("bytes", bytes.length);
        fixture.acquisition.put("content_sha256", StrategyResearchDataV5.ownHash(fixture.acquisition));
        ObjectNode parquetPartition = (ObjectNode) fixture.parquet.path("captures").get(0).path("partition");
        parquetPartition.put("source_jsonl_sha256", sourcePartition.path("sha256").asText());
        fixture.parquet.put("source_manifest_sha256", fixture.acquisition.path("content_sha256").asText());
        fixture.parquet.put("dataset_root_sha256", recomputedDatasetRoot(fixture.parquet));
        fixture.parquet.put("content_sha256", StrategyResearchDataV5.ownHash(fixture.parquet));
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
