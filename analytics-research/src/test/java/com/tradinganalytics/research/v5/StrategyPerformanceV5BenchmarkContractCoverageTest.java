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

/** Small production-shaped V5 manifest/Parquet contracts without research-scale data. */
class StrategyPerformanceV5BenchmarkContractCoverageTest {
    private static final ObjectMapper JSON = StrategyPerformanceV5.jsonMapper();

    @TempDir Path temporary;

    @Test
    void fundingSegmentHonorsCoverageAndTopLevelToleranceAtBoundaries() {
        ObjectNode capture = object();
        ArrayNode segments = array().add(object().put("effective_from", "2026-01-01T00:00:00Z")
                        .put("effective_to", "2026-01-02T00:00:00Z").put("cadence_ms", 28_800_000))
                .add(object().put("effective_from", "2026-01-02T00:00:00Z")
                        .put("effective_to", "2026-01-03T00:00:00Z").put("cadence_ms", 14_400_000));
        capture.set("coverage", object().put("slot_tolerance_ms", 60_000).set("cadence_segments", segments));

        assertThat(StrategyPerformanceV5Benchmark.productionFundingSegment(capture,
                Instant.parse("2026-01-01T00:00:30Z").toEpochMilli()).path("cadence_ms").asLong())
                .isEqualTo(28_800_000);
        assertThat(StrategyPerformanceV5Benchmark.productionFundingSegment(capture,
                Instant.parse("2026-01-02T00:00:00Z").toEpochMilli()).path("cadence_ms").asLong())
                .isEqualTo(14_400_000);
        assertThat(StrategyPerformanceV5Benchmark.productionFundingSegment(capture,
                Instant.parse("2026-01-03T00:00:30Z").toEpochMilli()).path("cadence_ms").asLong())
                .isEqualTo(14_400_000);

        ObjectNode topLevel = object().put("slot_tolerance_ms", "60000")
                .set("coverage", object().set("cadence_segments", array()
                        .add(object().put("effective_from", "2026-01-01T00:00:00Z")
                                .put("effective_to", "2026-01-03T00:00:00Z").put("cadence_ms", 28_800_000))));
        ObjectNode selected = StrategyPerformanceV5Benchmark.productionFundingSegment(topLevel,
                Instant.parse("2025-12-31T23:59:30Z").toEpochMilli());
        assertThat(selected.path("cadence_ms").asLong()).isEqualTo(28_800_000);
        selected.put("cadence_ms", 1);
        assertThat(topLevel.path("coverage").path("cadence_segments").get(0).path("cadence_ms").asLong())
                .isEqualTo(28_800_000);
        assertThat(StrategyPerformanceV5Benchmark.productionFundingSegment(null, 0)).isNull();
    }

    @Test
    void fixtureCommandClampsSmallParametersAndRejectsFullWithoutPhysicalInputs() {
        ObjectNode result = StrategyPerformanceV5Benchmark.runBenchmarkV5(
                "ignored-token", "--chromosomes=0", "--episodes", "1", "--full=false");
        assertThat(result.path("schema").asText()).isEqualTo(StrategyPerformanceV5Benchmark.BENCHMARK_SCHEMA);
        assertThat(result.path("shape").path("sample_chromosomes").asInt()).isEqualTo(1);
        assertThat(result.path("shape").path("episodes_per_asset").asInt()).isEqualTo(8);
        assertThat(result.path("checkpoint_resume_smoke").path("status").asText()).isEqualTo("PASS");

        assertThatThrownBy(() -> StrategyPerformanceV5Benchmark.runBenchmarkV5("--full=true"))
                .hasMessage("--full requires frozen plan, acquisition manifest, and Parquet manifest inputs");
    }

    @Test
    void objectManifestInputsReopenOneRowParquetAndRemainBoundToEveryManifest() throws Exception {
        Fixture fixture = fixture();
        ObjectNode options = object();
        options.set("planPath", fixture.plan);
        options.set("acquisitionManifestPath", fixture.acquisition);
        options.set("parquetManifestPath", fixture.parquet);
        options.put("acquisitionRoot", fixture.staging.toString()).put("parquetRoot", fixture.parquetRoot.toString())
                .put("samplePartitions", 1).put("chunkBytes", 97);

        ObjectNode result = StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(options);
        assertThat(result.path("semantic").path("mode").asText()).isEqualTo("SAMPLED");
        assertThat(result.path("semantic").path("scanned_partition_count").asInt()).isEqualTo(1);
        assertThat(result.path("semantic").path("scanned_rows").asInt()).isEqualTo(1);
        assertThat(result.path("readiness").path("data_plane").path("status").asText())
                .isEqualTo("NON_PRODUCTION_SAMPLED_SCAN");

        ObjectNode coverage = coverageManifest(fixture);
        ObjectNode coverageOptions = options.deepCopy();
        coverageOptions.set("coveragePath", coverage);
        ObjectNode withCoverage = StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(coverageOptions);
        assertThat(withCoverage.path("semantic").path("coverage_complete").asBoolean()).isTrue();
        assertThat(withCoverage.path("inputs").path("coverage").path("path").isNull()).isTrue();
        assertThat(withCoverage.path("inputs").path("coverage").path("content_sha256").asText())
                .isEqualTo(coverage.path("content_sha256").asText());
        assertThat(withCoverage.path("readiness").path("data_plane").path("status").asText())
                .isEqualTo("NON_PRODUCTION_SAMPLED_SCAN");

        ObjectNode staleParquet = fixture.parquet.deepCopy();
        staleParquet.put("source_manifest_sha256", StrategyPerformanceV5.hashV5Performance("stale"));
        staleParquet.put("content_sha256", StrategyPerformanceV5.ownHash(staleParquet));
        ObjectNode staleOptions = options.deepCopy();
        staleOptions.set("parquetManifestPath", staleParquet);
        assertThatThrownBy(() -> StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(staleOptions))
                .hasMessageContaining("not bound to the supplied acquisition manifest");
        assertThatThrownBy(() -> StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(
                options.deepCopy().put("full", true)))
                .hasMessageContaining("requires file-backed plan, acquisition, Parquet, and coverage inputs");
    }

    private Fixture fixture() throws Exception {
        Path root = temporary.resolve("one-row-v5");
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

    private static ObjectNode coverageManifest(Fixture fixture) {
        ObjectNode series = (ObjectNode) fixture.plan.path("series").get(0);
        ObjectNode sourceCapture = (ObjectNode) fixture.acquisition.path("captures").get(0);
        ObjectNode parquetCapture = (ObjectNode) fixture.parquet.path("captures").get(0);
        ObjectNode row = object();
        for (String field : new String[]{"asset", "venue", "instrument", "symbol", "interval", "series_type", "series_role"})
            row.set(field, series.get(field).deepCopy());
        row.put("requested_start_at", series.path("start_at").asText())
                .put("requested_end_at", series.path("end_at").asText())
                .put("availability_cutoff_at", series.path("availability_cutoff_at").asText())
                .put("required", series.path("required").asBoolean(false))
                .put("tradeable", series.path("tradeable").asBoolean(false)).put("observed_rows", 1)
                .put("expected_rows", series.path("expected_event_count").asLong())
                .put("observed_min_event_time", series.path("start_at").asText())
                .put("observed_max_event_time", series.path("end_at").asText())
                .put("observed_min_availability_time", series.path("availability_cutoff_at").asText())
                .put("observed_max_availability_time", series.path("availability_cutoff_at").asText())
                .put("complete", true);
        row.set("gaps", array());
        row.set("raw_receipt_sha256", array()); row.set("raw_receipt_byte_sha256", array());
        row.set("source_receipt_sha256", array()); row.set("source_receipt_byte_sha256", array());
        row.set("jsonl_partition", coveragePartition(sourceCapture.path("partition"), "JSONL"));
        row.set("parquet_partition", coveragePartition(parquetCapture.path("partition"), "PARQUET"));
        row.set("limitations", array());

        ObjectNode coverage = object().put("schema", StrategyPerformanceV5.AUTHORITATIVE_COVERAGE_SCHEMA)
                .put("version", 1).put("status", "OBSERVED_COMPLETE")
                .put("mode", "LOCAL_RAW_REPLAY_AND_AUTHORITATIVE_REOPEN")
                .put("captured_at", "2026-08-24T00:00:00.000Z")
                .put("plan_sha256", fixture.plan.path("content_sha256").asText())
                .putNull("catalog_sha256").put("acquisition_sha256", fixture.acquisition.path("content_sha256").asText())
                .put("parquet_sha256", fixture.parquet.path("content_sha256").asText())
                .put("dataset_root_sha256", fixture.parquet.path("dataset_root_sha256").asText());
        coverage.set("window", seriesWindow(fixture.plan));
        coverage.set("assets", fixture.plan.path("assets").deepCopy());
        coverage.set("series", array().add(row)); coverage.set("dated_futures", array());
        coverage.set("source_receipt_sha256", array()); coverage.set("source_receipt_byte_sha256", array());
        coverage.set("raw_receipt_sha256", array()); coverage.set("raw_receipt_byte_sha256", array());
        coverage.set("limitations", array());
        coverage.put("content_sha256", StrategyPerformanceV5.ownHash(coverage));
        return coverage;
    }

    private static ObjectNode seriesWindow(ObjectNode plan) {
        ObjectNode window = object();
        for (String field : new String[]{"years", "start_at", "end_at", "completed_through_at"})
            window.set(field, plan.path("window").get(field).deepCopy());
        return window;
    }

    private static ObjectNode coveragePartition(com.fasterxml.jackson.databind.JsonNode partition, String format) {
        return object().put("path", partition.path("path").asText())
                .put("byte_sha256", partition.path("sha256").asText()).put("bytes", partition.path("bytes").asLong())
                .put("row_count", partition.path("row_count").asLong()).put("format", format)
                .put("authoritative", "PARQUET".equals(format));
    }

    private record Fixture(ObjectNode plan, ObjectNode acquisition, ObjectNode parquet, Path staging, Path parquetRoot) {}
    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static ArrayNode array() { return JSON.createArrayNode(); }
}
