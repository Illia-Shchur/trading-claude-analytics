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

/** Deterministic mutations exercise authoritative coverage-manifest binding guards. */
class StrategyPerformanceV5CoverageManifestMatrixTest {
    private static final ObjectMapper JSON = StrategyPerformanceV5.jsonMapper();

    @TempDir Path temporary;

    @Test
    void acceptsUntamperedAuthoritativeCoverageAndRejectsBrokenTopLevelLinks() throws Exception {
        Fixture fixture = fixture();
        ObjectNode options = options(fixture);
        ObjectNode coverage = coverageManifest(fixture);
        ObjectNode result = run(options, coverage);
        assertThat(result.path("semantic").path("coverage_complete").asBoolean()).isTrue();

        assertRejected(fixture, coverage, "plan_sha256", StrategyPerformanceV5.hashV5Performance("wrong-plan"),
                "coverage manifest plan_sha256 is missing or not linked to the supplied manifest");
        assertRejected(fixture, coverage, "acquisition_sha256", StrategyPerformanceV5.hashV5Performance("wrong-acquisition"),
                "coverage manifest acquisition_sha256 is missing or not linked to the supplied manifest");
        assertRejected(fixture, coverage, "parquet_sha256", StrategyPerformanceV5.hashV5Performance("wrong-parquet"),
                "coverage manifest parquet_sha256 is missing or not linked to the supplied manifest");
        assertRejected(fixture, coverage, "dataset_root_sha256", StrategyPerformanceV5.hashV5Performance("wrong-root"),
                "coverage manifest dataset_root_sha256 is missing or not linked to the supplied manifest");
    }

    @Test
    void bindsWindowAssetsSeriesFieldsFlagsAndExpectedRowsToThePlan() throws Exception {
        Fixture fixture = fixture();
        ObjectNode coverage = coverageManifest(fixture);

        ObjectNode window = coverage.path("window").deepCopy();
        window.put("start_at", "2025-01-01T00:00:00.000Z");
        ObjectNode windowMismatch = coverage.deepCopy();
        windowMismatch.set("window", window);
        assertRejected(fixture, windowMismatch,
                "OBSERVED_COMPLETE coverage window is not bound to the frozen plan");

        ObjectNode assets = coverage.deepCopy();
        ((ArrayNode) assets.path("assets")).remove(0);
        assertRejected(fixture, assets, "OBSERVED_COMPLETE coverage assets are not bound to the frozen plan");

        ObjectNode duplicate = coverage.deepCopy();
        ((ArrayNode) duplicate.path("series")).add(((ArrayNode) duplicate.path("series")).get(0).deepCopy());
        assertRejected(fixture, duplicate, "coverage series inventory contains duplicate identity");

        ObjectNode unknown = coverage.deepCopy();
        ((ObjectNode) unknown.path("series").get(0)).put("symbol", "NOT_DECLARED");
        assertRejected(fixture, unknown, "coverage series is not declared by the frozen plan");

        ObjectNode staleField = coverage.deepCopy();
        ((ObjectNode) staleField.path("series").get(0)).put("requested_start_at", "2026-01-01T00:00:00Z");
        assertRejected(fixture, staleField, "coverage series does not match frozen plan field start_at");

        ObjectNode flags = coverage.deepCopy();
        ObjectNode flagRow = (ObjectNode) flags.path("series").get(0);
        flagRow.put("required", !flagRow.path("required").asBoolean());
        assertRejected(fixture, flags, "coverage series flags do not match the frozen plan");

        ObjectNode expectedRows = coverage.deepCopy();
        ((ObjectNode) expectedRows.path("series").get(0)).put("expected_rows", 2);
        assertRejected(fixture, expectedRows, "coverage expected row count is stale");
    }

    @Test
    void bindsPhysicalProjectionAndDatedInventoryContracts() throws Exception {
        Fixture fixture = fixture();
        ObjectNode coverage = coverageManifest(fixture);

        ObjectNode missingProjection = coverage.deepCopy();
        ((ObjectNode) missingProjection.path("series").get(0).path("jsonl_partition")).put("path", "missing.jsonl");
        assertRejected(fixture, missingProjection, "coverage JSONL partition is not physically bound");

        ObjectNode staleProjection = coverage.deepCopy();
        ((ObjectNode) staleProjection.path("series").get(0).path("parquet_partition")).put("bytes", 1);
        assertRejected(fixture, staleProjection, "coverage Parquet partition is not physically bound");

        ObjectNode malformedDated = coverage.deepCopy();
        ObjectNode contract = object().put("asset", "btc").put("symbol", "BTCUSDT_FAKE")
                        .put("contract_type", "QUARTERLY_EXPIRED_OR_HISTORICAL").put("venue", "BINANCE")
                        .put("instrument", "BINANCE_USDM_DATED_FUTURE")
                        .put("first_bar_at", "2025-01-01T00:00:00.000Z").put("last_bar_at", "2025-01-02T00:00:00.000Z")
                        .putNull("expiry_observed_date_utc").putNull("expiry_at")
                        .put("expiry_binding_status", "UNAVAILABLE").put("contract_spec_status", "UNAVAILABLE")
                        .put("history_status", "SIGNAL_HISTORY_AVAILABLE")
                        .put("archive_ingestion_status", "ARCHIVE_DISCOVERED_NOT_INGESTED")
                        .put("archive_coverage_complete", false).put("tradeable", false)
                        .put("source_prefix", "fixture/").putNull("source_raw_byte_sha256");
        contract.set("source_listing_response_byte_sha256", array());
        contract.set("source_receipt_sha256", array());
        ObjectNode dated = object().put("asset", "btc").put("instrument", "BINANCE_USDM_DATED_FUTURE")
                .putNull("symbol").put("history_status", "SIGNAL_HISTORY_AVAILABLE").put("tradeable", false);
        dated.set("contracts", array().add(contract));
        dated.set("limitations", array());
        ((ArrayNode) malformedDated.path("dated_futures")).add(dated);
        assertRejected(fixture, malformedDated, "coverage dated-futures contract is not declared by the frozen plan");
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

    private void assertRejected(Fixture fixture, ObjectNode original, String field, String replacement, String message) {
        ObjectNode coverage = original.deepCopy();
        if (field != null) coverage.put(field, replacement);
        coverage.put("content_sha256", StrategyPerformanceV5.ownHash(coverage));
        assertThatThrownBy(() -> run(options(fixture), coverage)).hasMessageContaining(message);
    }

    private void assertRejected(Fixture fixture, ObjectNode original, String message) {
        ObjectNode coverage = original.deepCopy();
        coverage.put("content_sha256", StrategyPerformanceV5.ownHash(coverage));
        assertThatThrownBy(() -> run(options(fixture), coverage)).hasMessageContaining(message);
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
