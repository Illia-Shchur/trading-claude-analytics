package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Small package-local bridge used only by the Mac engineering harness.
 * It calls the production corrected artifact validator; it does not create
 * qualification receipts or alter the production resource gate.
 */
public final class StrictArtifactValidator {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private StrictArtifactValidator() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && "--compare-normalized".equals(args[0])) {
            compareNormalized(Path.of(args[1]), Path.of(args[2]), args[3]);
            return;
        }
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
                .put("mode", artifact.path("mode").asText())
                .put("scenario", artifact.path("scenario").asText())
                .put("effect_size", effect)
                .put("replication", artifact.path("replication").asInt(-1))
                .put("seed", artifact.path("seed").asLong(Long.MIN_VALUE))
                .put("portable_economic_sha256", artifact.path("portable_economic_sha256").asText())
                .put("corrected_economic_semantic_sha256",
                        row.path("raw_evaluator_result").path("corrected_economic_semantic_sha256").asText())
                .put("content_sha256", artifact.path("content_sha256").asText())
                .put("event_count", row.path("event_count").asInt(-1))
                .put("paired_count", row.path("paired_count").asInt(-1))
                .put("independent_units", row.path("independent_units").asInt(-1));
        System.out.println(JsonHashes.mapper().writeValueAsString(result));
    }

    private static void compareNormalized(Path candidatePath, Path referenceBindingPath,
            String baselineEconomicSha256) throws Exception {
        ObjectNode candidate = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(candidatePath));
        ObjectNode referenceBinding = (ObjectNode) JsonHashes.mapper().readTree(
                Files.readAllBytes(referenceBindingPath));
        ObjectNode candidateRaw = rawResult(candidate, candidatePath);
        String portableBefore = candidate.path("portable_economic_sha256").asText("");
        String candidateBefore = StrategyFixedBaselineCorrectedV1
                .correctedEconomicSha256ForValidation(candidateRaw);
        if (portableBefore.isBlank() || !portableBefore.equals(candidateBefore)) {
            throw new IllegalArgumentException("candidate portable economic hash does not match before normalization");
        }
        JsonNode candidateBindingNode = candidateRaw.path("corrected_input_binding");
        if (!(candidateBindingNode instanceof ObjectNode candidateBinding)) {
            throw new IllegalArgumentException("corrected input binding is missing");
        }
        String physical = required(referenceBinding, "physical_input_sha256", referenceBindingPath);
        String bindingPhysical = required(referenceBinding,
                "corrected_input_binding_physical_input_sha256", referenceBindingPath);
        String producerPlan = required(referenceBinding,
                "physical_source_producer_plan_sha256", referenceBindingPath);
        if (!SHA256.matcher(baselineEconomicSha256).matches()) {
            throw new IllegalArgumentException("baseline economic hash is not a SHA-256");
        }
        candidateRaw.put("physical_input_sha256", physical);
        candidateBinding.put("physical_input_sha256", bindingPhysical);
        JsonNode candidateProducerNode = candidateRaw.path("physical_source_producer");
        if (!(candidateProducerNode instanceof ObjectNode candidateProducer)) {
            throw new IllegalArgumentException("candidate physical source producer is missing");
        }
        candidateProducer.put("plan_sha256", producerPlan);

        String candidateAfter = StrategyFixedBaselineCorrectedV1
                .correctedEconomicSha256ForValidation(candidateRaw);
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("status", candidateAfter.equals(baselineEconomicSha256) ? "PASS" : "MISMATCH")
                .put("candidate_portable_economic_sha256_before", portableBefore)
                .put("candidate_normalized_economic_sha256", candidateAfter)
                .put("reference_economic_sha256", baselineEconomicSha256)
                .put("normalization_applied_in_memory_only", true);
        var paths = result.putArray("normalized_paths");
        paths.add("/row/raw_evaluator_result/physical_input_sha256");
        paths.add("/row/raw_evaluator_result/corrected_input_binding/physical_input_sha256");
        paths.add("/row/raw_evaluator_result/physical_source_producer/plan_sha256");
        System.out.println(JsonHashes.mapper().writeValueAsString(result));
        if (!candidateAfter.equals(baselineEconomicSha256)) {
            throw new IllegalArgumentException("normalized candidate economics differ from frozen baseline");
        }
    }

    private static ObjectNode rawResult(ObjectNode artifact, Path path) {
        if (!(artifact.path("row").path("raw_evaluator_result") instanceof ObjectNode raw)) {
            throw new IllegalArgumentException("artifact has no raw evaluator result: " + path);
        }
        return raw;
    }

    private static String required(ObjectNode object, String field, Path path) {
        String value = object.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException("missing " + field + " in " + path);
        return value;
    }

}
