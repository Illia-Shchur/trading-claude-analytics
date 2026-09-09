package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the frozen D design before any generator is permitted to open outcomes. */
class OperatingCharacteristicsPlanV3Test {
    @Test
    void v003IsAnImmutableConditionalFixedStagePlan() throws Exception {
        Path path = Path.of("strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-plan-v004.json");
        if (!Files.exists(path)) path = Path.of("..", path.toString()).normalize();
        ObjectNode plan = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(path));

        assertThat(plan.path("schema").asText()).isEqualTo("strategy-evaluator-operating-characteristics-plan/4");
        assertThat(plan.path("version").asInt()).isEqualTo(4);
        assertThat(plan.path("frozen_before_outcomes").asBoolean()).isTrue();
        assertThat(plan.path("outcomes_opened").asBoolean()).isFalse();
        assertThat(plan.path("promotion_eligible").asBoolean()).isFalse();
        assertThat(plan.path("binding_fixed_evaluator").asText())
                .isEqualTo("StrategyFixedBaselineV5+TradeLifecycleV5+StrategyResearchImprovementV1.disposition");

        assertThat(plan.path("generator").path("raw_counts").path("raw_event_count").asInt()).isEqualTo(50);
        assertThat(plan.path("generator").path("raw_counts").path("raw_control_target_count").asInt()).isEqualTo(50);
        assertThat(plan.path("generator").path("timeline").path("control_lag_days").asInt()).isEqualTo(21);
        assertThat(plan.path("generator").path("cluster_geometry").path("minimum_block_spacing_days").asInt())
                .isEqualTo(42);
        assertThat(plan.path("generator").path("setup_shock").path("treated_event_final_4h_return").asText())
                .isEqualTo("-0.09 exactly before minute expansion; this is a predeclared mechanism fixture above the frozen <=-0.08 threshold");
        assertThat(plan.path("resource_budget").path("expected_generated_minute_bars").asLong())
                .isEqualTo(288_000_000L);
        assertThat(plan.path("targets").path("minimum_independent_units_per_replication").asInt()).isEqualTo(30);
        assertThat(plan.path("targets").path("replication_denominator").asText()).contains("All planned replications");
        assertThat(plan.path("targets").path("power_acceptance").asText()).contains("0.02").contains("0.04");
        assertThat(plan.path("scenarios").get(1).path("effect_sizes").toString()).contains("0.0", "0.02", "0.04");
        assertThat(plan.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(plan));
    }
}
