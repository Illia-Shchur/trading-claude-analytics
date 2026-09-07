package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Ensures the frozen D workload has a real, self-bound pre-outcome boundary. */
class StrategyOperatingCharacteristicsV4Test {
    @Test
    void preflightBindsPlanAndCannotOpenOutcomes() throws Exception {
        Path path = Path.of("strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-plan-v004.json");
        if (!Files.exists(path)) path = Path.of("..", path.toString()).normalize();
        ObjectNode plan = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(path));

        ObjectNode receipt = StrategyOperatingCharacteristicsV4.preflight(plan);

        assertThat(receipt.path("status").asText()).isEqualTo("READY_PRE_OUTCOME");
        assertThat(receipt.path("plan_sha256").asText()).isEqualTo(plan.path("content_sha256").asText());
        assertThat(receipt.path("shared_physical_evaluator").asBoolean()).isTrue();
        assertThat(receipt.path("toy_statistic").asBoolean()).isFalse();
        assertThat(receipt.path("outcomes_opened").asBoolean()).isFalse();
        assertThat(receipt.path("promotion_eligible").asBoolean()).isFalse();
        assertThat(receipt.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(receipt));
    }

    @Test
    void changedPlanBytesAreRejectedBeforePreflight() throws Exception {
        Path path = Path.of("strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-plan-v004.json");
        if (!Files.exists(path)) path = Path.of("..", path.toString()).normalize();
        ObjectNode plan = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(path));
        plan.withObject("targets").put("minimum_independent_units_per_replication", 29);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> StrategyOperatingCharacteristicsV4.preflight(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("self-bound");
    }
}
