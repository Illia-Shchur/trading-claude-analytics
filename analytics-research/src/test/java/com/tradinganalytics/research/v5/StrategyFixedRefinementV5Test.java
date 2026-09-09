package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StrategyFixedRefinementV5Test {
    @Test
    void publicFixedBaselineRejectsRefinementOverridesBeforeReadingInputs() {
        ObjectNode options = JsonHashes.mapper().createObjectNode()
                .put("refinement_member_id", "FK-DELEVERAGING-V002-R2")
                .put("refinement_shock_threshold", -0.10);
        assertThatThrownBy(() -> StrategyFixedBaselineV5.run(options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not accept refinement overrides");
    }

    @Test
    void freezeRejectsConflictingBudgetAndMemberMapping() throws Exception {
        ObjectNode input = read("strategy-research/experiments/fk-deleveraging-baseline-v002/refinement-input-v001.json");
        input.with("budget").put("max_attempts", 4);
        input.put("content_sha256", JsonHashes.ownHash(input));
        assertThatThrownBy(() -> StrategyResearchImprovementV1.freezeRefinementInventory(input))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("budget");

        ObjectNode mapped = read("strategy-research/experiments/fk-deleveraging-baseline-v002/refinement-input-v001.json");
        ((ObjectNode) mapped.withArray("members").get(0)).put("shock_threshold", -0.10);
        mapped.put("content_sha256", JsonHashes.ownHash(mapped));
        assertThatThrownBy(() -> StrategyResearchImprovementV1.freezeRefinementInventory(mapped))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported frozen dimension");
    }

    @Test
    void refinementPlanValidationHappensBeforeAnyMemberCanRun() throws Exception {
        ObjectNode plan = read("strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/refinement-plan-v001.json");
        plan.withArray("members").remove(2);
        plan.put("candidate_count", 2).put("max_attempts", 2).put("content_sha256", JsonHashes.ownHash(plan));
        Path bad = Files.createTempFile("refinement-short-", ".json");
        Files.writeString(bad, JsonHashes.mapper().writeValueAsString(plan));
        assertThatThrownBy(() -> StrategyFixedBaselineV5.runRefinement(
                JsonHashes.mapper().createObjectNode().put("refinement", bad.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema validation failed");
    }

    private static ObjectNode read(String relative) throws Exception {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("..", relative).normalize();
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readString(path));
    }
}
