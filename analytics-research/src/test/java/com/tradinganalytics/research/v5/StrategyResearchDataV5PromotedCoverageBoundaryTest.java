package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Decision boundaries for promoted coverage without physical-data assumptions. */
final class StrategyResearchDataV5PromotedCoverageBoundaryTest {
    private static final String H = "a".repeat(64);
    private static final String AS_OF = "2026-08-25T00:00:00.000Z";

    @Test
    void unavailableCapturesRemainBlockedAndExposeBothAcquisitionAndParquetGaps() {
        ObjectNode plan = plan();
        ObjectNode acquisition = acquisition(plan, unavailableCaptures(plan));
        ObjectNode result = resolve(plan, acquisition, true);

        assertThat(result.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(result.path("base_complete").asBoolean()).isFalse();
        assertThat(result.path("series")).isNotEmpty();
        assertThat(result.path("limitations").toString()).contains("UNAVAILABLE", "PARQUET_NOT_PROMOTED");
    }

    @Test
    void missingCaptureIdentityIsReportedAsNotAcquiredWhenParquetIsOptional() {
        ObjectNode plan = plan();
        ObjectNode acquisition = acquisition(plan, array());
        ObjectNode result = resolve(plan, acquisition, false);

        assertThat(result.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(result.path("series")).isNotEmpty();
        assertThat(result.path("limitations").toString()).contains("NOT_ACQUIRED");
        assertThat(result.path("series").get(0).path("acquisition_complete").asBoolean()).isFalse();
    }

    @Test
    void completeStagingCaptureStillNeedsAnAuthoritativeParquetCounterpart() {
        ObjectNode plan = oneEventPlan();
        ArrayNode captures = unavailableCaptures(plan);
        ObjectNode complete = completeCapture((ObjectNode) plan.path("series").get(0));
        captures.set(0, complete);
        ObjectNode result = resolve(plan, acquisition(plan, captures), true);
        JsonNode first = result.path("series").get(0);

        assertThat(first.path("acquisition_complete").asBoolean()).isTrue();
        assertThat(first.path("parquet_complete").asBoolean()).isFalse();
        assertThat(first.path("complete").asBoolean()).isFalse();
        assertThat(first.path("gaps").toString()).contains("PARQUET_NOT_PROMOTED");
    }

    @Test
    void duplicatePromotedIdentityFailsBeforeCoverageCanBePublished() {
        ObjectNode plan = oneEventPlan();
        ObjectNode capture = parquetCapture((ObjectNode) plan.path("series").get(0));
        ArrayNode promoted = array().add(capture).add(capture.deepCopy());
        ObjectNode acquisition = acquisition(plan, array().add(completeCapture((ObjectNode) plan.path("series").get(0))));
        ObjectNode parquet = object().put("schema", "strategy-v5-parquet-conversion/1").put("version", 1)
                .put("status", "AUTHORITATIVE_PARQUET")
                .put("source_manifest_sha256", acquisition.path("content_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText()).put("output_root_reference", "fixture")
                .put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true)
                .put("threads", 1).put("dataset_root_sha256", H);
        parquet.set("captures", promoted);
        parquet.set("limitations", array());
        parquet = StrategyResearchDataV5.withHash(parquet);

        ObjectNode options = object();
        options.set("plan", plan);
        options.set("acquisition", acquisition);
        options.set("parquet", parquet);
        options.put("requireFrozenRequirements", false).put("requireParquet", true);
        assertThat(parquet.path("source_manifest_sha256").asText())
                .isEqualTo(acquisition.path("content_sha256").asText());
        assertThatThrownBy(() -> StrategyResearchDataV5.resolvePromotedCoverage(options))
                .hasMessageContaining("duplicate Parquet series identity");
    }

    private static ObjectNode plan() {
        return StrategyResearchDataV5.makeFiveYearAuthoritativePlan(
                object().put("asOf", AS_OF).put("rootReference", "coverage-boundary"));
    }

    /** A one-event plan keeps the metadata-only positive fixture truthful and cheap. */
    private static ObjectNode oneEventPlan() {
        ObjectNode result = plan();
        ObjectNode first = (ObjectNode) result.path("series").get(0);
        String firstEvent = first.path("start_at").asText();
        first.put("end_at", firstEvent).put("availability_cutoff_at", firstEvent)
                .put("expected_event_count", 1);
        return StrategyResearchDataV5.withHash(result);
    }

    private static ObjectNode resolve(ObjectNode plan, ObjectNode acquisition, boolean requireParquet) {
        ObjectNode options = object();
        options.set("plan", plan);
        options.set("acquisition", acquisition);
        options.put("requireFrozenRequirements", false).put("requireParquet", requireParquet);
        return StrategyResearchDataV5.resolvePromotedCoverage(options);
    }

    private static ObjectNode acquisition(ObjectNode plan, ArrayNode captures) {
        ObjectNode acquisition = object().put("schema", "strategy-v5-authoritative-acquisition/1")
                .put("version", 1).put("status", "STAGING_PARTIAL")
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("root_reference", "coverage-boundary").put("staging_format", "JSONL")
                .put("storage_role", "STAGING").put("authoritative", false);
        acquisition.set("captures", captures);
        acquisition.set("source_receipts", array());
        acquisition.set("source_receipt_sha256", array());
        acquisition.set("source_receipt_byte_sha256", array());
        acquisition.set("limitations", array());
        return StrategyResearchDataV5.withHash(acquisition);
    }

    private static ArrayNode unavailableCaptures(ObjectNode plan) {
        ArrayNode captures = array();
        for (JsonNode value : plan.path("series")) {
            ObjectNode capture = ((ObjectNode) value).deepCopy();
            capture.remove("trade_scope");
            capture.put("unavailable", true);
            capture.set("coverage", object().put("complete", false).put("reason", "NETWORK_UNAVAILABLE"));
            capture.set("limitations", array().add("NETWORK_UNAVAILABLE"));
            captures.add(capture);
        }
        return captures;
    }

    private static ObjectNode completeCapture(ObjectNode series) {
        ObjectNode capture = series.deepCopy();
        capture.remove("trade_scope");
        capture.put("required", true);
        ObjectNode sourceRow = object().put("asset", capture.path("asset").asText())
                .put("venue", capture.path("venue").asText()).put("instrument", capture.path("instrument").asText())
                .put("symbol", capture.path("symbol").asText()).put("interval", capture.path("interval").asText())
                .put("event_time", capture.path("start_at").asText()).put("open", 100)
                .put("high", 101).put("low", 99).put("close", 100).put("volume", 1);
        byte[] sourceBytes = (StrategyResearchDataV5.stable(sourceRow) + "\n").getBytes(StandardCharsets.UTF_8);
        String sourceSha = StrategyResearchDataV5.hash(sourceBytes);
        ObjectNode coverage = object().put("complete", true).put("expected_rows", 1)
                .put("observed_rows", 1).put("min_event_time", capture.path("start_at").asText())
                .put("max_event_time", capture.path("end_at").asText());
        capture.set("coverage", coverage);
        capture.set("partition", object().put("path", "staging/one-event.jsonl").put("sha256", sourceSha)
                .put("bytes", sourceBytes.length).put("row_count", 1).put("format", "JSONL")
                .put("storage_role", "STAGING").put("authoritative", false));
        return capture;
    }

    private static ObjectNode parquetCapture(ObjectNode series) {
        ObjectNode capture = series.deepCopy();
        capture.remove("trade_scope");
        capture.set("partition", object().put("path", "parquet/complete.parquet").put("sha256", H)
                .put("bytes", 1).put("row_count", 1).put("format", "PARQUET")
                .put("storage_role", "AUTHORITATIVE").put("authoritative", true)
                .put("source_jsonl_sha256", H).put("schema_sha256", H));
        capture.set("coverage", object().put("complete", true));
        return capture;
    }

    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
}
