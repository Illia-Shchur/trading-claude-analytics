package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Small package-local bridge used only by the Mac engineering harness.
 * It calls the production corrected artifact validator; it does not create
 * qualification receipts or alter the production resource gate.
 */
public final class StrictArtifactValidator {
    private StrictArtifactValidator() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("usage: plan artifact expected-run-id");
        Path planPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path artifactPath = Path.of(args[1]).toAbsolutePath().normalize();
        String expectedRunId = args[2];
        ObjectNode plan = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(planPath));
        ObjectNode artifact = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(artifactPath));
        if (!"COMPLETE".equals(artifact.path("status").asText())
                || !"COMPLETE".equals(artifact.path("row").path("status").asText())) {
            throw new IllegalArgumentException("worker artifact is not complete: " + artifactPath);
        }
        double effect = artifact.path("effect_size").asDouble(Double.NaN);
        StrategyOperatingCharacteristicsParallelV1.Slot slot =
                new StrategyOperatingCharacteristicsParallelV1.Slot(
                        plan.path("content_sha256").asText(), artifact.path("mode").asText(),
                        artifact.path("scenario").asText(), effect,
                        artifact.path("replication").asInt(-1), artifact.path("seed").asLong(Long.MIN_VALUE), 0);
        StrategyOperatingCharacteristicsParallelV1.validateCorrectedArtifactForTest(
                artifact, artifactPath, slot, plan, expectedRunId);
        ObjectNode row = (ObjectNode) artifact.path("row");
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("status", "PASS")
                .put("artifact", artifactPath.toString())
                .put("slot_id", slot.id())
                .put("portable_economic_sha256", artifact.path("portable_economic_sha256").asText())
                .put("corrected_economic_semantic_sha256",
                        row.path("raw_evaluator_result").path("corrected_economic_semantic_sha256").asText())
                .put("content_sha256", artifact.path("content_sha256").asText())
                .put("event_count", row.path("event_count").asInt(-1))
                .put("paired_count", row.path("paired_count").asInt(-1))
                .put("independent_units", row.path("independent_units").asInt(-1));
        System.out.println(JsonHashes.mapper().writeValueAsString(result));
    }
}
