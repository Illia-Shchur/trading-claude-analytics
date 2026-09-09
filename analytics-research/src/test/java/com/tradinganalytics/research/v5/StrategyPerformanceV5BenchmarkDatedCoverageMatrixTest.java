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
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dated-futures coverage inventories prove archive status and physical references through the public verifier. */
class StrategyPerformanceV5BenchmarkDatedCoverageMatrixTest {
    private static final ObjectMapper JSON = StrategyPerformanceV5.jsonMapper();

    @TempDir Path temporary;
    private int fixtureSequence;

    @Test
    void acceptsIngestedDatedInventoryAndBindsBoundsArchiveProofAndPhysicalRefs() throws Exception {
        Fixture fixture = fixture(false);
        ObjectNode result = run(fixture, coverage(fixture, false));
        assertThat(result.path("semantic").path("coverage_complete").asBoolean()).isTrue();
        assertThat(result.path("semantic").path("scanned_rows").asInt()).isEqualTo(2);

        ObjectNode staleBounds = coverage(fixture, false);
        ((ObjectNode) staleBounds.path("dated_futures").get(0).path("contracts").get(0))
                .put("last_bar_at", "2026-02-01T00:00:00.000Z");
        assertRejected(fixture, staleBounds,
                "coverage dated-futures contract does not match frozen bounds");

        ObjectNode missingArchiveProof = coverage(fixture, false);
        ObjectNode contract = (ObjectNode) missingArchiveProof.path("dated_futures").get(0).path("contracts").get(0);
        contract.put("archive_ingestion_status", "ARCHIVE_DISCOVERED_NOT_INGESTED").put("archive_coverage_complete", false);
        assertRejected(fixture, missingArchiveProof,
                "coverage dated-futures available contract lacks complete archive proof");

        ObjectNode staleRefs = coverage(fixture, false);
        ((ObjectNode) staleRefs.path("dated_futures").get(0).path("contracts").get(0)
                        .path("archive_physical_capture_refs"))
                .put("dataset_root_sha256", StrategyPerformanceV5.hashV5Performance("stale-root"));
        assertRejected(fixture, staleRefs, "coverage dated-futures physical refs are not bound");
    }

    @Test
    void rejectsDuplicateDatedInventoryAndMissingPlanInventory() throws Exception {
        Fixture fixture = fixture(false);
        ObjectNode duplicate = coverage(fixture, false);
        ArrayNode contracts = (ArrayNode) duplicate.path("dated_futures").get(0).path("contracts");
        contracts.add(contracts.get(0).deepCopy());
        assertRejected(fixture, duplicate,
                "coverage dated-futures inventory contains duplicate identity");

        ObjectNode missing = coverage(fixture, false);
        ((ArrayNode) missing.path("dated_futures")).remove(0);
        assertRejected(fixture, missing, "coverage dated-futures inventory is missing a plan capture");
    }

    @Test
    void acceptsOptionalUnavailableDatedSeriesAndRejectsFalseIngestionClaim() throws Exception {
        Fixture fixture = fixture(true);
        ObjectNode coverage = coverage(fixture, true);
        ObjectNode result = run(fixture, coverage);
        assertThat(result.path("semantic").path("coverage_complete").asBoolean()).isTrue();
        assertThat(result.path("semantic").path("scanned_rows").asInt()).isEqualTo(1);

        ObjectNode falseIngestion = coverage.deepCopy();
        ObjectNode contract = (ObjectNode) falseIngestion.path("dated_futures").get(0).path("contracts").get(0);
        contract.put("archive_ingestion_status", "ARCHIVE_INGESTED").put("archive_coverage_complete", true);
        contract.set("archive_physical_capture_refs", object()
                .put("jsonl_partition_sha256", "0".repeat(64))
                .put("parquet_partition_sha256", "0".repeat(64))
                .put("dataset_root_sha256", "0".repeat(64)));
        assertRejected(fixture, falseIngestion,
                "coverage dated-futures unavailable contract claims physical ingestion");
    }

    private ObjectNode run(Fixture fixture, ObjectNode coverage) {
        ObjectNode options = object();
        options.set("planPath", fixture.plan);
        options.set("acquisitionManifestPath", fixture.acquisition);
        options.set("parquetManifestPath", fixture.parquet);
        options.set("coveragePath", coverage);
        return StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(options
                .put("acquisitionRoot", fixture.staging.toString())
                .put("parquetRoot", fixture.parquetRoot.toString())
                .put("samplePartitions", 2)
                .put("chunkBytes", 97));
    }

    private void assertRejected(Fixture fixture, ObjectNode coverage, String message) {
        coverage.put("content_sha256", StrategyPerformanceV5.ownHash(coverage));
        assertThatThrownBy(() -> run(fixture, coverage)).hasMessageContaining(message);
    }

    private ObjectNode coverage(Fixture fixture, boolean unavailableDated) {
        ArrayNode rows = array();
        ObjectNode spot = (ObjectNode) fixture.plan.path("series").get(0);
        rows.add(coverageRow(spot, fixture.sourceCapture(0), fixture.parquetCapture(0), false));
        ObjectNode dated = (ObjectNode) fixture.plan.path("series").get(1);
        rows.add(coverageRow(dated, fixture.sourceCapture(1), unavailableDated ? null : fixture.parquetCapture(1), unavailableDated));

        ObjectNode contract = datedContract(dated, fixture, unavailableDated);
        ObjectNode datedInventory = object().put("asset", dated.path("asset").asText())
                .put("instrument", "BINANCE_USDM_DATED_FUTURE").put("symbol", dated.path("symbol").asText())
                .put("history_status", "SIGNAL_HISTORY_AVAILABLE").put("tradeable", false);
        datedInventory.set("contracts", array().add(contract));
        datedInventory.set("limitations", array());

        ObjectNode result = object().put("schema", StrategyPerformanceV5.AUTHORITATIVE_COVERAGE_SCHEMA)
                .put("version", 1).put("status", "OBSERVED_COMPLETE")
                .put("mode", "LOCAL_RAW_REPLAY_AND_AUTHORITATIVE_REOPEN")
                .put("captured_at", "2026-08-24T00:00:00.000Z")
                .putNull("catalog_sha256")
                .put("plan_sha256", fixture.plan.path("content_sha256").asText())
                .put("acquisition_sha256", fixture.acquisition.path("content_sha256").asText())
                .put("parquet_sha256", fixture.parquet.path("content_sha256").asText())
                .put("dataset_root_sha256", fixture.parquet.path("dataset_root_sha256").asText());
        result.set("window", window(fixture.plan));
        result.set("assets", fixture.plan.path("assets").deepCopy());
        result.set("series", rows);
        result.set("dated_futures", array().add(datedInventory));
        result.set("source_receipt_sha256", array());
        result.set("source_receipt_byte_sha256", array());
        result.set("raw_receipt_sha256", array());
        result.set("raw_receipt_byte_sha256", array());
        result.set("limitations", array());
        return result.put("content_sha256", StrategyPerformanceV5.ownHash(result));
    }

    private static ObjectNode coverageRow(ObjectNode series, ObjectNode sourceCapture,
                                          ObjectNode parquetCapture, boolean unavailable) {
        ObjectNode row = object();
        for (String field : new String[]{"asset", "venue", "instrument", "symbol", "interval", "series_type", "series_role"})
            row.set(field, series.get(field).deepCopy());
        row.put("requested_start_at", series.path("start_at").asText())
                .put("requested_end_at", series.path("end_at").asText())
                .put("availability_cutoff_at", series.path("availability_cutoff_at").asText())
                .put("required", series.path("required").asBoolean(false))
                .put("tradeable", series.path("tradeable").asBoolean(false))
                .put("observed_rows", unavailable ? 0 : 1).put("expected_rows", 1)
                .put("complete", !unavailable);
        if (unavailable) {
            row.putNull("observed_min_event_time").putNull("observed_max_event_time")
                    .putNull("observed_min_availability_time").putNull("observed_max_availability_time");
        } else {
            row.put("observed_min_event_time", series.path("start_at").asText())
                    .put("observed_max_event_time", series.path("end_at").asText())
                    .put("observed_min_availability_time", series.path("start_at").asText())
                    .put("observed_max_availability_time", series.path("start_at").asText());
        }
        row.set("gaps", unavailable ? array().add("UNAVAILABLE") : array());
        row.set("raw_receipt_sha256", array());
        row.set("raw_receipt_byte_sha256", array());
        row.set("source_receipt_sha256", array());
        row.set("source_receipt_byte_sha256", array());
        row.set("limitations", array());
        if (!unavailable) {
            row.set("jsonl_partition", projection(sourceCapture.path("partition"), "JSONL"));
            row.set("parquet_partition", projection(parquetCapture.path("partition"), "PARQUET"));
        }
        return row;
    }

    private static ObjectNode datedContract(ObjectNode series, Fixture fixture, boolean unavailable) {
        ObjectNode contract = object().put("asset", series.path("asset").asText())
                .put("symbol", series.path("symbol").asText())
                .put("contract_type", "QUARTERLY_EXPIRED_OR_HISTORICAL")
                .put("venue", "BINANCE").put("instrument", "BINANCE_USDM_DATED_FUTURE")
                .put("first_bar_at", series.path("start_at").asText()).put("last_bar_at", series.path("end_at").asText())
                .putNull("expiry_observed_date_utc").putNull("expiry_at")
                .put("expiry_binding_status", "UNAVAILABLE").put("contract_spec_status", "UNAVAILABLE")
                .put("history_status", "SIGNAL_HISTORY_AVAILABLE")
                .put("archive_ingestion_status", unavailable ? "ARCHIVE_DISCOVERED_NOT_INGESTED" : "ARCHIVE_INGESTED")
                .put("archive_coverage_complete", !unavailable).put("tradeable", false)
                .put("source_prefix", "fixture/").putNull("source_raw_byte_sha256");
        contract.set("source_listing_response_byte_sha256", array());
        contract.set("source_receipt_sha256", array());
        if (!unavailable) {
            ObjectNode sourcePartition = fixture.sourceCapture(1).path("partition").deepCopy();
            ObjectNode parquetPartition = fixture.parquetCapture(1).path("partition").deepCopy();
            contract.set("archive_physical_capture_refs", object()
                    .put("jsonl_partition_sha256", sourcePartition.path("sha256").asText())
                    .put("parquet_partition_sha256", parquetPartition.path("sha256").asText())
                    .put("dataset_root_sha256", fixture.parquet.path("dataset_root_sha256").asText()));
        }
        return contract;
    }

    private Fixture fixture(boolean unavailableDated) throws Exception {
        Path root = temporary.resolve("dated-coverage-v5-" + fixtureSequence++);
        Path staging = root.resolve("staging");
        Path parquetRoot = root.resolve("parquet");
        Files.createDirectories(staging);
        Files.createDirectories(parquetRoot);

        ObjectNode base = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(
                object().put("asOf", "2026-08-24T00:00:00.000Z"));
        ObjectNode spot = ((ObjectNode) base.path("series").get(0)).deepCopy();
        configureSeries(spot, spot.path("symbol").asText(), true);
        ObjectNode dated = spot.deepCopy();
        configureSeries(dated, "BTCUSDT_260101", !unavailableDated);
        dated.put("instrument", "BINANCE_USDM_DATED_FUTURE");
        ObjectNode plan = base.deepCopy().set("series", array().add(spot).add(dated));
        plan.put("content_sha256", StrategyResearchDataV5.ownHash(plan));

        ArrayNode captures = array();
        ArrayNode parquetCaptures = array();
        Path[] sourcePaths = new Path[2];
        for (int index = 0; index < 2; index++) {
            ObjectNode series = index == 0 ? spot : dated;
            boolean unavailable = indexEqualsUnavailable(index, unavailableDated);
            ObjectNode row = barRow(series);
            sourcePaths[index] = staging.resolve(index == 0 ? "spot.jsonl" : "dated.jsonl");
            ObjectNode partition = null;
            if (!unavailable) {
                Files.writeString(sourcePaths[index], JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8);
                byte[] sourceBytes = Files.readAllBytes(sourcePaths[index]);
                partition = object().put("path", sourcePaths[index].getFileName().toString())
                        .put("sha256", StrategyPerformanceV5.hashV5Performance(sourceBytes)).put("bytes", sourceBytes.length)
                        .put("row_count", 1).put("format", "JSONL").put("storage_role", "STAGING")
                        .put("authoritative", false);
            }
            ObjectNode capture = series.deepCopy();
            capture.remove("trade_scope");
            capture.put("required", series.path("required").asBoolean())
                    .put("series_sha256", StrategyPerformanceV5.hashV5Performance(series));
            capture.set("coverage", object().put("complete", !unavailable).put("observed_rows", unavailable ? 0 : 1));
            if (unavailable) capture.put("unavailable", true);
            else capture.set("partition", partition);
            captures.add(capture);

            if (index == 0 || !unavailableDated) {
                Path parquetPath = parquetRoot.resolve(index == 0 ? "spot.parquet" : "dated.parquet");
                ResearchData.ParquetArtifact physical = ResearchData.writeParquet(sourcePaths[index], parquetPath);
                ObjectNode parquetPartition = object().put("path", parquetPath.getFileName().toString())
                        .put("sha256", physical.sha256()).put("bytes", physical.bytes()).put("row_count", 1)
                        .put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true)
                        .put("source_jsonl_sha256", partition.path("sha256").asText())
                        .put("schema_sha256", parquetSchemaHash(parquetPath));
                ObjectNode promoted = capture.deepCopy();
                promoted.remove("unavailable");
                promoted.set("coverage", object().put("complete", true).put("expected_rows", 1).put("observed_rows", 1)
                        .put("min_event_time", series.path("start_at").asText()).put("max_event_time", series.path("end_at").asText()));
                promoted.set("partition", parquetPartition);
                parquetCaptures.add(promoted);
            }
        }

        ObjectNode acquisition = object().put("schema", "strategy-v5-authoritative-acquisition/1").put("version", 1)
                .put("status", "STAGING_COMPLETE").put("plan_sha256", plan.path("content_sha256").asText())
                .put("root_reference", staging.toString()).put("staging_format", "JSONL")
                .put("storage_role", "STAGING").put("authoritative", false).put("base_complete", true)
                .put("declared_complete", !unavailableDated).put("full_plan_complete", !unavailableDated)
                .put("completion_scope", unavailableDated ? "BASE_ONLY" : "ALL_DECLARED")
                .put("required_series_count", unavailableDated ? 1 : 2).put("required_complete_count", unavailableDated ? 1 : 2)
                .put("optional_series_count", unavailableDated ? 1 : 0).put("optional_complete_count", 0)
                .put("optional_complete", !unavailableDated);
        acquisition.set("captures", captures);
        acquisition.set("unavailable_required", array());
        acquisition.set("unavailable_optional", unavailableDated
                ? array().add("btc|binance_usdm_dated_future|btcusdt_260101|4h|signal_bars") : array());
        acquisition.put("content_sha256", StrategyResearchDataV5.ownHash(acquisition));

        ObjectNode parquet = object().put("schema", "strategy-v5-parquet-conversion/1").put("version", 1)
                .put("status", "AUTHORITATIVE_PARQUET").put("source_manifest_sha256", acquisition.path("content_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText()).put("output_root_reference", parquetRoot.toString())
                .put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true)
                .put("threads", 1);
        parquet.set("captures", parquetCaptures);
        ObjectNode dataset = object().put("source_manifest_sha256", acquisition.path("content_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText());
        ArrayNode datasetCaptures = array();
        for (JsonNode raw : parquetCaptures) {
            ObjectNode capture = (ObjectNode) raw;
            datasetCaptures.add(object().put("identity", identity(capture)).set("partition", capture.path("partition").deepCopy()));
        }
        dataset.set("captures", datasetCaptures);
        parquet.put("dataset_root_sha256", StrategyPerformanceV5.hashV5Performance(dataset));
        parquet.put("content_sha256", StrategyResearchDataV5.ownHash(parquet));
        return new Fixture(plan, acquisition, parquet, staging, parquetRoot, captures, parquetCaptures);
    }

    private static boolean indexEqualsUnavailable(int index, boolean unavailableDated) {
        return index == 1 && unavailableDated;
    }

    private static void configureSeries(ObjectNode series, String symbol, boolean required) {
        String start = series.path("start_at").asText();
        series.put("symbol", symbol).put("end_at", start).put("availability_cutoff_at", start)
                .put("expected_event_count", 1).put("required", required).put("tradeable", false)
                .put("completed_bars_only", false);
    }

    private static ObjectNode barRow(ObjectNode series) {
        String start = series.path("start_at").asText();
        return object().put("asset", series.path("asset").asText()).put("venue", series.path("venue").asText())
                .put("instrument", series.path("instrument").asText()).put("symbol", series.path("symbol").asText())
                .put("interval", series.path("interval").asText()).put("series_type", series.path("series_type").asText())
                .put("series_role", series.path("series_role").asText()).put("event_time", start)
                .put("availability_time", start).put("open", 100).put("high", 101).put("low", 99).put("close", 100);
    }

    private static ObjectNode projection(JsonNode partition, String format) {
        return object().put("path", partition.path("path").asText()).put("byte_sha256", partition.path("sha256").asText())
                .put("bytes", partition.path("bytes").asLong()).put("row_count", partition.path("row_count").asLong())
                .put("format", format).put("authoritative", "PARQUET".equals(format));
    }

    private static ObjectNode window(ObjectNode plan) {
        ObjectNode result = object();
        for (String field : new String[]{"years", "start_at", "end_at", "completed_through_at"})
            result.set(field, plan.path("window").get(field).deepCopy());
        return result;
    }

    private static String identity(ObjectNode value) {
        return value.path("asset").asText().toLowerCase(Locale.ROOT) + "|"
                + value.path("instrument").asText().toUpperCase(Locale.ROOT) + "|"
                + value.path("symbol").asText().toUpperCase(Locale.ROOT) + "|"
                + value.path("interval").asText() + "|" + value.path("series_type").asText().toLowerCase(Locale.ROOT);
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
            result.close(); statement.close();
            return StrategyPerformanceV5.hashV5Performance(rows);
        }
    }

    private record Fixture(ObjectNode plan, ObjectNode acquisition, ObjectNode parquet, Path staging, Path parquetRoot,
                           ArrayNode sourceCaptures, ArrayNode parquetCaptures) {
        private ObjectNode sourceCapture(int index) { return (ObjectNode) sourceCaptures.get(index); }
        private ObjectNode parquetCapture(int index) { return (ObjectNode) parquetCaptures.get(index); }
    }
    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static ArrayNode array() { return JSON.createArrayNode(); }
}
