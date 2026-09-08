package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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

/** Funding JSONL replay fixtures exercise settlement, identity, duplicate, and cadence guards with acquisitionRoot present. */
class StrategyPerformanceV5BenchmarkFundingSourceReplayMatrixTest {
    private static final ObjectMapper JSON = StrategyPerformanceV5.jsonMapper();
    private static final long CADENCE = 8 * 60 * 60 * 1000L;

    @TempDir Path temporary;
    private int fixtureSequence;

    @Test
    void validFundingSourceReplayScansRowsAndBindsSettlementEvidence() throws Exception {
        Fixture fixture = fixture();
        assertThat(recomputedDatasetRoot(fixture.parquet)).isEqualTo(fixture.parquet.path("dataset_root_sha256").asText());
        ObjectNode result = runWithSource(fixture);
        assertThat(result.path("semantic").path("scanned_partition_count").asInt()).isEqualTo(1);
        assertThat(result.path("semantic").path("scanned_rows").asInt()).isEqualTo(3);
        assertThat(result.path("runtime").path("acquisition_source_rows").asInt()).isEqualTo(3);
        assertThat(result.path("semantic").path("source_complete").asBoolean()).isTrue();
    }

    @Test
    void sourceFundingReplayRejectsIdentitySettlementAndProvenanceViolations() throws Exception {
        assertSourceRejected("JSONL funding semantic diagnostic", rows -> ((ObjectNode) rows.get(0)).remove("event_id"));
        assertSourceRejected("JSONL funding semantic diagnostic", rows -> ((ObjectNode) rows.get(1)).put("settlement_slot", 0));
        assertSourceRejected("JSONL funding semantic diagnostic", rows -> ((ObjectNode) rows.get(0)).put("settlement_mark", -1));
        assertSourceRejected("JSONL funding semantic diagnostic", rows -> ((ObjectNode) rows.get(0))
                .put("settlement_mark_source_response_sha256", "bad"));
    }

    @Test
    void sourceFundingReplayRejectsDuplicateIdsAndUnexpectedCadence() throws Exception {
        assertSourceRejected("JSONL semantic duplicate diagnostic", rows -> ((ObjectNode) rows.get(2))
                .put("event_id", rows.get(1).path("event_id").asText()));
        assertSourceRejected("JSONL funding semantic diagnostic", rows -> ((ObjectNode) rows.get(1))
                .put("cadence_ms", CADENCE * 2));
        assertSourceRejected("funding semantic cadence diagnostic", rows -> {
            ObjectNode row = (ObjectNode) rows.get(1);
            long start = rows.get(0).path("event_time").asLong();
            long shifted = start + 7 * 60 * 60 * 1000L;
            row.put("event_time", shifted).put("availability_time", shifted)
                    .put("settlement_slot", shifted).put("settlement_mark_event_time", shifted)
                    .put("settlement_mark_availability_time", shifted);
        });
    }

    private void assertSourceRejected(String message, java.util.function.Consumer<ArrayNode> mutation) throws Exception {
        Fixture fixture = fixture();
        ArrayNode rows = array();
        for (String line : Files.readAllLines(fixture.staging.resolve("funding.jsonl")))
            if (!line.isBlank()) rows.add(JSON.readTree(line));
        mutation.accept(rows);
        Path alteredInput = fixture.staging.resolve("funding.jsonl");
        StringBuilder body = new StringBuilder();
        for (var row : rows) body.append(JSON.writeValueAsString(row)).append('\n');
        Files.writeString(alteredInput, body.toString(), StandardCharsets.UTF_8);
        refreshSourceMetadata(fixture);
        rebindParquetSource(fixture);
        assertThatThrownBy(() -> runWithSource(fixture)).hasMessageContaining(message);
    }

    private ObjectNode runWithSource(Fixture fixture) {
        ObjectNode options = object();
        options.set("planPath", fixture.plan);
        options.set("acquisitionManifestPath", fixture.acquisition);
        options.set("parquetManifestPath", fixture.parquet);
        options.put("acquisitionRoot", fixture.staging.toString()).put("parquetRoot", fixture.parquetRoot.toString())
                .put("samplePartitions", 1).put("chunkBytes", 97);
        return StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(options);
    }

    private Fixture fixture() throws Exception {
        Path root = temporary.resolve("two-row-funding-" + fixtureSequence++);
        Path staging = root.resolve("staging");
        Path parquetRoot = root.resolve("parquet");
        Files.createDirectories(staging);
        Files.createDirectories(parquetRoot);

        ObjectNode base = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(
                object().put("asOf", "2026-08-24T00:00:00.000Z"));
        ObjectNode sourceSeries = null;
        for (var value : base.path("series")) {
            if ("funding_events".equals(value.path("series_type").asText())) {
                sourceSeries = ((ObjectNode) value).deepCopy();
                break;
            }
        }
        if (sourceSeries == null) throw new IllegalStateException("five-year plan has no funding series");
        String start = "2026-01-01T00:00:00.000Z";
        String end = Instant.ofEpochMilli(Instant.parse(start).toEpochMilli() + 2 * CADENCE).toString();
        sourceSeries.put("start_at", start).put("end_at", end).put("availability_cutoff_at", end)
                .put("expected_event_count", 3);
        ObjectNode plan = base.deepCopy();
        plan.set("series", array().add(sourceSeries));
        plan.put("content_sha256", StrategyPerformanceV5.ownHash(plan));

        String sourceHash = StrategyPerformanceV5.hashV5Performance("source-response");
        ObjectNode coverage = fundingCoverage(start, end);
        String middle = Instant.ofEpochMilli(Instant.parse(start).toEpochMilli() + CADENCE).toString();
        ArrayNode rows = array().add(fundingRow(sourceSeries, start, "funding-1", 100, sourceHash))
                .add(fundingRow(sourceSeries, middle, "funding-2", 101, sourceHash))
                .add(fundingRow(sourceSeries, end, "funding-3", 102, sourceHash));
        Path jsonl = staging.resolve("funding.jsonl");
        StringBuilder body = new StringBuilder();
        for (var row : rows) body.append(JSON.writeValueAsString(row)).append('\n');
        Files.writeString(jsonl, body.toString(), StandardCharsets.UTF_8);
        byte[] jsonlBytes = Files.readAllBytes(jsonl);

        ObjectNode sourcePartition = object().put("path", "funding.jsonl")
                .put("sha256", StrategyPerformanceV5.hashV5Performance(jsonlBytes)).put("bytes", jsonlBytes.length)
                .put("row_count", 3).put("format", "JSONL").put("storage_role", "STAGING").put("authoritative", false);
        ObjectNode capture = sourceSeries.deepCopy();
        capture.remove("trade_scope");
        capture.put("required", true).put("series_sha256", StrategyPerformanceV5.hashV5Performance(sourceSeries));
        capture.set("coverage", coverage.deepCopy());
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
        acquisition.put("content_sha256", StrategyPerformanceV5.ownHash(acquisition));

        Path parquetPath = parquetRoot.resolve("funding.parquet");
        ResearchData.ParquetArtifact physical = ResearchData.writeParquet(jsonl, parquetPath);
        ObjectNode parquetPartition = object().put("path", "funding.parquet").put("sha256", physical.sha256())
                .put("bytes", physical.bytes()).put("row_count", 3).put("format", "PARQUET")
                .put("storage_role", "AUTHORITATIVE").put("authoritative", true)
                .put("source_jsonl_sha256", sourcePartition.path("sha256").asText())
                .put("schema_sha256", parquetSchemaHash(parquetPath));
        ObjectNode promoted = capture.deepCopy();
        promoted.set("coverage", coverage.deepCopy());
        promoted.set("partition", parquetPartition);
        ObjectNode parquet = object().put("schema", "strategy-v5-parquet-conversion/1").put("version", 1)
                .put("status", "AUTHORITATIVE_PARQUET").put("source_manifest_sha256", acquisition.path("content_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText()).put("output_root_reference", parquetRoot.toString())
                .put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true).put("threads", 1);
        parquet.set("captures", array().add(promoted));
        ObjectNode dataset = object().put("source_manifest_sha256", acquisition.path("content_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText());
        String identity = sourceSeries.path("asset").asText().toLowerCase(Locale.ROOT) + "|"
                + sourceSeries.path("instrument").asText().toUpperCase(Locale.ROOT) + "|"
                + sourceSeries.path("symbol").asText().toUpperCase(Locale.ROOT) + "|event|funding_events";
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
            captures.add(object().put("identity", datasetIdentity(capture))
                    .set("partition", capture.path("partition").deepCopy()));
        }
        dataset.set("captures", captures);
        return StrategyPerformanceV5.hashV5Performance(dataset);
    }

    private static void refreshSourceMetadata(Fixture fixture) throws Exception {
        Path source = fixture.staging.resolve("funding.jsonl");
        byte[] bytes = Files.readAllBytes(source);
        ObjectNode partition = (ObjectNode) fixture.acquisition.path("captures").get(0).path("partition");
        partition.put("sha256", StrategyPerformanceV5.hashV5Performance(bytes)).put("bytes", bytes.length)
                .put("row_count", 3);
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

    private static String datasetIdentity(ObjectNode value) {
        return value.path("asset").asText().toLowerCase(Locale.ROOT) + "|"
                + value.path("instrument").asText().toUpperCase(Locale.ROOT) + "|"
                + value.path("symbol").asText().toUpperCase(Locale.ROOT) + "|"
                + value.path("interval").asText() + "|"
                + value.path("series_type").asText().toLowerCase(Locale.ROOT);
    }

    private static ObjectNode fundingCoverage(String start, String end) {
        return object().put("complete", true).put("observed_rows", 3).put("observed_events", 3)
                .put("first_event_time", start).put("last_event_time", end).put("query_start_at", start)
                .put("query_end_at", end).put("source_pagination_complete", true).put("boundaries_covered", true)
                .put("slot_tolerance_ms", 60_000).set("cadence_segments", array().add(object()
                        .put("effective_from", start).put("effective_to", end).put("cadence_ms", CADENCE)
                        .put("origin_at", start).put("discovery", "OBSERVED_EVENT_GAPS")));
    }

    private static ObjectNode fundingRow(ObjectNode series, String event, String id, double mark, String sourceHash) {
        long epoch = Instant.parse(event).toEpochMilli();
        return object().put("asset", series.path("asset").asText()).put("venue", series.path("venue").asText())
                .put("instrument", series.path("instrument").asText()).put("symbol", series.path("symbol").asText())
                .put("interval", series.path("interval").asText()).put("series_type", series.path("series_type").asText())
                .put("series_role", series.path("series_role").asText()).put("event_time", epoch)
                .put("availability_time", epoch).put("event_id", id).put("funding_rate", .001)
                .put("cadence_ms", CADENCE).put("settlement_slot", epoch).put("settlement_mark", mark)
                .put("mark_price", mark).put("settlement_mark_event_time", epoch)
                .put("settlement_mark_availability_time", epoch).put("settlement_mark_source", "binance")
                .put("settlement_mark_source_response_sha256", sourceHash);
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

    private record Fixture(ObjectNode plan, ObjectNode acquisition, ObjectNode parquet, Path staging, Path parquetRoot) {}
    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static ArrayNode array() { return JSON.createArrayNode(); }
}
