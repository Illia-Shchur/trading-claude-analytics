package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Genetic checkpoint creation, immutable predecessor validation, and file round trips. */
final class StrategyStatisticalV5CheckpointMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    @TempDir
    Path temporary;

    @Test
    void checkpointRoundTripPreservesCanonicalStateAndCallerBindings() {
        ObjectNode args = checkpointArgs();
        ObjectNode checkpoint = StrategyStatisticalV5.makeGeneticCheckpoint(args);
        assertThat(checkpoint.path("checkpoint_status").asText()).isEqualTo("RUNNING");
        assertThat(StrategyStatisticalV5.validateGeneticCheckpoint(checkpoint, args)).isTrue();

        Path path = temporary.resolve("checkpoint.json");
        ObjectNode write = object().put("filePath", path.toString()).set("checkpoint", checkpoint);
        assertThat(StrategyStatisticalV5.writeGeneticCheckpointFile(write).path("content_sha256").asText())
                .isEqualTo(checkpoint.path("content_sha256").asText());
        assertThat(StrategyStatisticalV5.readGeneticCheckpointFile(path).path("state_sha256").asText())
                .isEqualTo(checkpoint.path("state_sha256").asText());
        assertThat(StrategyStatisticalV5.writeGeneticCheckpointFile(write).path("content_sha256").asText())
                .isEqualTo(checkpoint.path("content_sha256").asText());
    }

    @Test
    void checkpointValidationRejectsEachStaleImmutableBinding() {
        ObjectNode checkpoint = StrategyStatisticalV5.makeGeneticCheckpoint(checkpointArgs());

        ObjectNode wrongArtifact = checkpointArgs();
        wrongArtifact.set("artifact", artifact("other-dataset"));
        assertThatThrownBy(() -> StrategyStatisticalV5.validateGeneticCheckpoint(checkpoint, wrongArtifact))
                .hasMessage("checkpoint artifact lineage mismatch");

        ObjectNode wrongHead = checkpointArgs();
        wrongHead.set("exposureHead", head("other-head"));
        assertThatThrownBy(() -> StrategyStatisticalV5.validateGeneticCheckpoint(checkpoint, wrongHead))
                .hasMessage("checkpoint exposure predecessor is stale");

        ObjectNode wrongSpace = checkpointArgs();
        ((ObjectNode) wrongSpace.with("geneSpace").withArray("genes").get(0)).put("default", 3);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateGeneticCheckpoint(checkpoint, wrongSpace))
                .hasMessage("checkpoint gene space mismatch");

        ObjectNode wrongFold = checkpointArgs().put("foldId", "outer-2");
        assertThatThrownBy(() -> StrategyStatisticalV5.validateGeneticCheckpoint(checkpoint, wrongFold))
                .hasMessage("checkpoint fold mismatch");

        ObjectNode wrongConfig = checkpointArgs();
        wrongConfig.with("config").put("seed", 99);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateGeneticCheckpoint(checkpoint, wrongConfig))
                .hasMessage("checkpoint configuration mismatch");
    }

    @Test
    void checkpointCreationRejectsNonIntegralOrNegativeProgressValues() {
        ObjectNode fractionalSeed = checkpointArgs().put("seed", 1.5);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeGeneticCheckpoint(fractionalSeed))
                .hasMessage("checkpoint seed/generation is invalid");

        ObjectNode fractionalGeneration = checkpointArgs().put("generation", 1.5);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeGeneticCheckpoint(fractionalGeneration))
                .hasMessage("checkpoint seed/generation is invalid");

        ObjectNode negativeGeneration = checkpointArgs().put("generation", -1);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeGeneticCheckpoint(negativeGeneration))
                .hasMessage("checkpoint seed/generation is invalid");
    }

    @Test
    void checkpointWriterRejectsAStaleExpectedPredecessor() {
        ObjectNode checkpoint = StrategyStatisticalV5.makeGeneticCheckpoint(checkpointArgs());
        Path path = temporary.resolve("checkpoint.json");
        StrategyStatisticalV5.writeGeneticCheckpointFile(object().put("filePath", path.toString())
                .set("checkpoint", checkpoint));
        ObjectNode changed = checkpointArgs().put("generation", 1)
                .put("previousCheckpointSha256", checkpoint.path("content_sha256").asText());
        ObjectNode next = StrategyStatisticalV5.makeGeneticCheckpoint(changed);
        ObjectNode write = object().put("filePath", path.toString()).put("expectedCheckpointSha256",
                JsonHashes.sha256("wrong-existing")).set("checkpoint", next);
        assertThatThrownBy(() -> StrategyStatisticalV5.writeGeneticCheckpointFile(write))
                .hasMessage("stale or competing checkpoint predecessor");

        ObjectNode acceptedWrite = object().put("filePath", path.toString())
                .put("expectedCheckpointSha256", checkpoint.path("content_sha256").asText())
                .set("checkpoint", next);
        assertThat(StrategyStatisticalV5.writeGeneticCheckpointFile(acceptedWrite)
                .path("generation").asInt()).isEqualTo(1);
        assertThat(StrategyStatisticalV5.readGeneticCheckpointFile(path)
                .path("previous_checkpoint_sha256").asText())
                .isEqualTo(checkpoint.path("content_sha256").asText());
    }

    private static ObjectNode checkpointArgs() {
        ObjectNode head = head("checkpoint-dataset");
        ObjectNode args = object();
        args.set("artifact", artifact("checkpoint-dataset")); args.set("exposureHead", head);
        args.put("foldId", "outer-1").put("seed", 11).put("generation", 0);
        args.set("geneSpace", geneSpace());
        ObjectNode config = args.putObject("config").put("population", 2).put("generations", 1)
                .put("minGenerations", 1).put("plateauGenerations", 1).put("crossoverProbability", .9)
                .put("mutationProbability", 0).put("halfLifeMonths", 18)
                .put("operator", "ARITHMETIC_CROSSOVER_UNIFORM_MUTATION")
                .put("scheduler_ordering", "STABLE_SEED_GENERATION_CHROMOSOME_ORDER")
                .put("mode", "FIXTURE");
        config.putArray("seeds").add(11).add(23).add(47);
        args.putArray("population"); args.putArray("history"); args.putArray("seedFinalists"); args.putArray("seedMembership");
        return args;
    }

    private static ObjectNode head(String suffix) {
        String dataset = hash(suffix + "-dataset"); ObjectNode options = object()
                .put("hypothesisFamily", "checkpoint-family").put("datasetSha256", dataset);
        options.putArray("entries").addObject().put("behavior_sha256", hash(suffix + "-alias"))
                .put("dataset_sha256", dataset);
        return StrategyStatisticalV5.makeExposureHead(options);
    }

    private static ObjectNode artifact(String suffix) {
        ObjectNode head = head(suffix); String dataset = head.path("dataset_sha256").asText();
        ObjectNode args = object().set("exposureHead", head); ObjectNode lineage = args.putObject("lineage");
        lineage.put("dataset_sha256", dataset).put("candidate_set_sha256", hash(suffix + "-candidates"))
                .put("feature_set_sha256", hash(suffix + "-features")).put("label_set_sha256", hash(suffix + "-labels"))
                .put("execution_set_sha256", hash(suffix + "-execution"));
        String alias = head.path("entries").get(0).path("behavior_sha256").asText();
        args.putArray("candidates").addObject().put("candidate_id", "candidate-1").put("behavior_sha256", alias);
        args.putArray("episodes").addObject().put("episode_id", "e1").put("asset", "btc")
                .put("decision_time", "2026-01-01T00:00:00Z").put("resolution_time", "2026-01-02T00:00:00Z")
                .put("eligible", true).putObject("candidate_returns").putObject("candidate-1")
                .put("net_r", .2).put("traded", true);
        return StrategyStatisticalV5.makeStatisticalArtifactSet(args);
    }

    private static ObjectNode geneSpace() {
        ObjectNode space = object();
        space.putArray("genes").addObject().put("name", "holding_period").put("type", "ordered-discrete")
                .put("default", 2).putArray("values").add(1).add(2).add(3);
        return space;
    }

    private static String hash(String value) { return JsonHashes.sha256(value); }
    private static ObjectNode object() { return MAPPER.createObjectNode(); }
}
