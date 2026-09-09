package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetainedFixedEvidenceSchemaTest {
    private static final Set<String> OPTIONAL_LARGE_RAW = Set.of(
            "fixed-baseline-e11-result.json",
            "full-result-20260906-v20-final.json",
            "full-result-20260906-v21-recompute.json",
            "refinement-results-v004/R1-result.json");

    @Test
    void retainedFixedArtifactsUseRegisteredAdditiveContracts() throws Exception {
        Path root = Path.of("strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004");
        if (!Files.exists(root)) root = Path.of("..", root.toString()).normalize();
        List<String> names = List.of(
                "inventory-v004.json",
                "refinement-input-v001.json",
                "non-trading-intervals.json",
                "producer-receipt.json",
                "fresh-physical-reconstruction-e11.json",
                "fixed-baseline-e11-result-summary.json",
                "fixed-baseline-e11-result.json",
                "full-result-20260906-v20-final-summary.json",
                "full-result-20260906-v21-recompute-summary.json",
                "operating-characteristics-prefix-v004d.json",
                "operating-characteristics-prefix-v004d-corrected.json",
                "operating-characteristics-prefix-v004e.json",
                "physical-input-closure-v004.json",
                "refinement-start-exposure-head-v001.json",
                "operating-characteristics-plan-v004.json",
                "full-result-20260906-v20-final.json",
                "refinement-results-v004/aggregate.json",
                "refinement-results-v004/R1-result-summary.json",
                "refinement-results-v004/R1-result.json",
                "refinement-results-v004-run6/FK-DELEVERAGING-V002-R1-summary.json",
                "refinement-results-v004/R2-result.json",
                "refinement-results-v004/R3-result.json");
        ResearchSchemaRegistry registry = ResearchSchemaRegistry.defaultRegistry();
        for (String name : names) {
            Path artifact = root.resolve(name);
            if (!Files.exists(artifact)) {
                // Large raw historical projections may be retained locally and ignored;
                // compact typed receipts remain the checkout contract.
                if (OPTIONAL_LARGE_RAW.contains(name)) continue;
                throw new AssertionError("missing retained compact artifact " + name);
            }
            JsonNode value = JsonHashes.mapper().readTree(Files.readString(artifact));
            assertThat(registry.hasContractSchema(value.path("schema").asText())).as(name).isTrue();
            assertThat(registry.validateKnownContractSchema(value)).as(name).isTrue();
        }

        Path experimentRoot = Path.of("strategy-research/experiments/fk-deleveraging-baseline-v002");
        if (!Files.exists(experimentRoot)) experimentRoot = Path.of("..", experimentRoot.toString()).normalize();
        for (String freezeName : List.of(
                "operating-characteristics-source-freeze-v004c.json",
                "operating-characteristics-source-freeze-v004d.json",
                "operating-characteristics-source-freeze-v004d-corrected.json",
                "operating-characteristics-source-freeze-v004e.json")) {
            Path freezePath = experimentRoot.resolve(freezeName);
            assertThat(Files.exists(freezePath)).as(freezeName).isTrue();
            JsonNode freeze = JsonHashes.mapper().readTree(Files.readString(freezePath));
            assertThat(registry.hasContractSchema(freeze.path("schema").asText())).isTrue();
            assertThat(registry.validateKnownContractSchema(freeze)).isTrue();
        }
    }
}
