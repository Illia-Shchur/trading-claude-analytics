package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Public funding segment lookup boundaries and defensive fallback semantics. */
class StrategyPerformanceV5BenchmarkFundingSegmentMatrixTest {
    @Test
    void absentCoverageHasNoDiscoveredSegment() {
        assertThat(StrategyPerformanceV5Benchmark.productionFundingSegment(object(), 0)).isNull();
    }

    @Test
    void negativeAndMalformedToleranceAreClampedBeforeSelection() {
        long start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        long end = Instant.parse("2026-01-02T00:00:00Z").toEpochMilli();
        ObjectNode segment = object().put("effective_from", "2026-01-01T00:00:00Z")
                .put("effective_to", "2026-01-02T00:00:00Z").put("cadence_ms", 28_800_000);
        ObjectNode capture = object().set("coverage", object().put("slot_tolerance_ms", -1)
                .set("cadence_segments", array().add(segment)));

        assertThat(StrategyPerformanceV5Benchmark.productionFundingSegment(capture, start)
                .path("cadence_ms").asLong()).isEqualTo(28_800_000);
        ((ObjectNode) capture.path("coverage")).put("slot_tolerance_ms", "malformed");
        assertThat(StrategyPerformanceV5Benchmark.productionFundingSegment(capture, end)
                .path("cadence_ms").asLong()).isEqualTo(28_800_000);
    }

    @Test
    void invalidIntermediateSegmentIsSkippedForTheFollowingValidSegment() {
        ObjectNode capture = object();
        capture.set("coverage", object().set("cadence_segments", array()
                .add(object().put("effective_from", "not-a-time").put("effective_to", "2026-01-02T00:00:00Z"))
                .add(object().put("effective_from", "2026-01-02T00:00:00Z")
                        .put("effective_to", "2026-01-03T00:00:00Z").put("cadence_ms", 14_400_000))));

        assertThat(StrategyPerformanceV5Benchmark.productionFundingSegment(capture,
                Instant.parse("2026-01-02T12:00:00Z").toEpochMilli()).path("cadence_ms").asLong())
                .isEqualTo(14_400_000);
    }

    @Test
    void nonObjectFallbackEntryDoesNotPretendToBeAResolvedSegment() {
        ObjectNode capture = object();
        ArrayNode segments = array().add(object().put("effective_from", "2026-01-01T00:00:00Z")
                .put("effective_to", "2026-01-02T00:00:00Z").put("cadence_ms", 28_800_000)).add(array().add(1));
        capture.set("coverage", object().set("cadence_segments", segments));

        assertThat(StrategyPerformanceV5Benchmark.productionFundingSegment(capture,
                Instant.parse("2026-01-03T00:00:00Z").toEpochMilli())).isNull();
    }

    private static ObjectNode object() { return StrategyPerformanceV5.jsonMapper().createObjectNode(); }
    private static ArrayNode array() { return StrategyPerformanceV5.jsonMapper().createArrayNode(); }
}
