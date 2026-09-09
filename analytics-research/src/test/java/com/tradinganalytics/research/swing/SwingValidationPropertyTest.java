package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class SwingValidationPropertyTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Property(tries = 150)
    void coverageRatiosAndGapBoundsStayCoherent(@ForAll @IntRange(min = 1, max = 100) int rows,
            @ForAll @IntRange(min = 0, max = 12) int missingEvery) {
        ArrayNode input = MAPPER.createArrayNode(); long time = 1_700_000_000_000L;
        for (int index = 0; index < rows; index++) {
            if (missingEvery > 0 && index > 0 && index % missingEvery == 0) time += SwingEngine.BAR_MS;
            input.add(MAPPER.createObjectNode().put("time", time).put("timeframe", "4h").put("funding_rate", 0)
                    .put("funding_event_time", time).set("factors", MAPPER.createObjectNode().set("derivatives",
                            MAPPER.createObjectNode().put("top_vs_global_positioning_z", 0))));
            time += SwingEngine.BAR_MS;
        }
        ObjectNode coverage = SwingCrossValidator.coverageMetrics(input, List.of(), true);
        assertThat(coverage.path("observed_bars").asInt()).isEqualTo(rows);
        assertThat(coverage.path("expected_bars").asInt()).isGreaterThanOrEqualTo(rows);
        assertThat(coverage.path("coverage_4h").asDouble()).isBetween(0d, 1d);
        assertThat(coverage.path("raw_max_gap_bars").asInt()).isGreaterThanOrEqualTo(coverage.path("max_gap_bars").asInt());
        assertThat(coverage.path("derivatives_coverage").asDouble()).isEqualTo(1);
        assertThat(coverage.path("positioning_coverage").asDouble()).isEqualTo(1);
    }

    @Property(tries = 100)
    void candidateBoundsAdmitOnlyDeclaredSearchSpace(@ForAll @IntRange(min = -5, max = 10) int flow,
            @ForAll @IntRange(min = -3, max = 7) int technical,
            @ForAll @IntRange(min = -2, max = 5) int triggerBars) {
        ObjectNode candidate = MAPPER.createObjectNode().put("framework", "fallen_knives").put("direction", "long")
                .put("phase", "1A").put("trigger_window_bars", triggerBars).put("min_flow_aligned", flow).put("min_technical", technical);
        ArrayNode normalized = SwingCalibration.validCandidates(MAPPER.createArrayNode().add(candidate), "fallen_knives", null);
        boolean legal = triggerBars >= 1 && triggerBars <= 2 && flow >= 0 && flow <= 5 && technical >= 0 && technical <= 4;
        assertThat(normalized.size()).isEqualTo(legal ? 1 : 0);
        if (legal) {
            assertThat(normalized.get(0).path("threshold_offset").asInt()).isZero();
            assertThat(normalized.get(0).path("min_flow_aligned").asInt()).isEqualTo(flow);
        }
    }
}
