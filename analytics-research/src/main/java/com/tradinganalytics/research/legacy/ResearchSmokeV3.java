package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.StrictJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

/** Programmatic port of the hermetic {@code tools/research-smoke.mjs} check. */
public final class ResearchSmokeV3 {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ResearchSmokeV3() {}

    public static ObjectNode run(Path repositoryRoot, Clock clock) throws Exception {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(clock, "clock");
        Path universePath = repositoryRoot.resolve("strategy-research/config/research-universe-v3.json");
        JsonNode universe = StrictJson.parse(
                Files.readString(universePath, StandardCharsets.UTF_8), universePath.getFileName().toString());
        JsonNode expectedUniverse = JSON.valueToTree(LegacyResearchV3.CORE_UNIVERSE);
        if (!expectedUniverse.equals(universe.path("tradable_assets"))) {
            throw new IllegalStateException("v3 smoke universe drift");
        }
        boolean dogeExcluded = false;
        for (JsonNode asset : universe.path("excluded_assets")) {
            if ("doge".equals(asset.asText())) dogeExcluded = true;
        }
        if (!dogeExcluded) throw new IllegalStateException("DOGE must remain excluded");

        ObjectNode acceptance = LegacyResearchV3.makeAcceptanceContract();
        LegacyResearchV3.validateAcceptanceContract(acceptance);
        ObjectNode metricOptions = JSON.createObjectNode();
        metricOptions.put("candidateId", "smoke-zero");
        metricOptions.put("asset", "btc");
        metricOptions.put("candidateCount", 1);
        ObjectNode zeros = LegacyResearchV3.computeCandidateMetrics(JSON.createArrayNode(), metricOptions);
        if (zeros.path("completed_trades").asInt() != 0 || !zeros.path("expectancy_r").isNull()) {
            throw new IllegalStateException("zero-trade metric row is not explicit");
        }

        ObjectNode experimentOptions = JSON.createObjectNode();
        experimentOptions.put("experimentId", "v3-smoke");
        experimentOptions.put("precommitSha256", "a".repeat(64));
        experimentOptions.put("definitionSha256", "b".repeat(64));
        experimentOptions.put("candidateSetSha256", "c".repeat(64));
        experimentOptions.put("dataManifestSha256", "d".repeat(64));
        experimentOptions.set("acceptanceContract", acceptance);
        experimentOptions.set("requiredAssets", expectedUniverse.deepCopy());
        ObjectNode chronology = experimentOptions.putObject("chronology");
        chronology.put("timezone", "UTC");
        chronology.put("bar_convention", "completed-bar-next-open");
        chronology.putArray("seeds").add(1);
        ObjectNode experiment = LegacyResearchV3.makeExperimentV3(experimentOptions, clock);
        LegacyResearchV3.validateExperimentV3(experiment, acceptance, null);
        ObjectNode decision = LegacyResearchV3.evaluateAcceptance(zeros, acceptance);
        if (!"REJECTED".equals(decision.path("decision").asText())) {
            throw new IllegalStateException("smoke failed to reject zero-trade candidate");
        }

        ObjectNode output = JSON.createObjectNode();
        output.put("ok", true);
        output.set("assets", expectedUniverse.deepCopy());
        output.put("doge", "excluded");
        output.set("zero_trade", decision);
        output.put("experiment", experiment.path("content_sha256").asText());
        return output;
    }

    public static Path repositoryRoot(Path start) {
        Path path = start.toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) path = path.getParent();
        while (path != null
                && !Files.isRegularFile(path.resolve("strategy-research/config/research-universe-v3.json"))) {
            path = path.getParent();
        }
        if (path == null) throw new IllegalStateException("strategy research repository root not found");
        return path;
    }
}
