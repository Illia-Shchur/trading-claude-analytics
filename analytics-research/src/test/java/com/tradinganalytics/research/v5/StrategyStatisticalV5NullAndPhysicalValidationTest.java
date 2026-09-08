package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Public null-control admission and physical-runner boundary coverage. */
final class StrategyStatisticalV5NullAndPhysicalValidationTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final List<String> METHODS = List.of(
            "block_permuted_labels", "timestamp_shifted_outcomes",
            "frequency_matched_random_intents", "winners_curse_selection");

    @Test
    void nullControlsFailInevitableAtBatchBoundaryWithExactWorkload() {
        ObjectNode options = controls(0.3);
        options.put("iterations", 8).put("sequentialBatchSize", 2).put("alpha", .05);
        ObjectNode result = StrategyStatisticalV5.runNullControlsV5(options, replaySame());

        assertThat(result.path("observed_expectancy_r").asDouble()).isEqualTo(.3);
        assertThat(result.path("pass").asBoolean()).isFalse();
        assertThat(result.path("iterations").asInt()).isEqualTo(8);
        assertThat(StrategyStatisticalV5.validateContractSchema(result)).isTrue();
        assertThat(result.path("tests")).hasSize(4);
        for (JsonNode row : result.path("tests")) {
            assertThat(row.path("iterations").asInt()).isEqualTo(2);
            assertThat(row.path("iterations_planned").asInt()).isEqualTo(8);
            assertThat(row.path("sequential_batch_size").asInt()).isEqualTo(2);
            assertThat(row.path("sequential_stopping_reason").asText())
                    .isEqualTo("FAIL_INEVITABLE_AT_FIXED_HORIZON");
            assertThat(row.path("p_value_lower_bound").asDouble()).isEqualTo(1d / 3d);
            assertThat(row.path("p_value_upper_bound").asDouble()).isEqualTo(1d);
            assertThat(row.path("evaluation_attempt_k").asInt()).isZero();
            assertThat(row.path("worker_evaluation_count").asInt()).isZero();
            assertThat(row.path("batch_count").asInt()).isZero();
            assertThat(row.path("checkpointed_iterations").asInt()).isZero();
            assertThat(row.path("worker_slots_used")).isEmpty();
        }
    }

    @Test
    void nullControlsUseNegativeDirectionAndSelectedOosVectorBinding() {
        ObjectNode options = controls(-.3);
        options.put("directionalHypothesis", "negative");
        options.putArray("selectedEpisodeIds").add("e1").add("e2").add("e3");
        ArrayNode selected = options.putArray("selectedOutcomeRows");
        selected.addObject().put("episode_id", "e1").put("net_r", -.2).put("traded", true);
        selected.addObject().put("episode_id", "e2").put("net_r", -.3).put("traded", true);
        selected.addObject().put("episode_id", "e3").put("net_r", -.4).put("traded", true);
        options.put("iterations", 4).put("sequentialBatchSize", 2).put("alpha", .05);

        ObjectNode result = StrategyStatisticalV5.runNullControlsV5(options, replaySame());
        assertThat(result.path("selected_candidate_id").asText()).isEqualTo("candidate-1:selected-oos");
        assertThat(result.path("observed_expectancy_r").asDouble()).isEqualTo(-.3);
        assertThat(result.path("directional_hypothesis").asText()).isEqualTo("negative");
        for (JsonNode row : result.path("tests")) {
            assertThat(row.path("directional_hypothesis").asText()).isEqualTo("negative");
            assertThat(row.path("iterations").asInt()).isEqualTo(2);
            assertThat(row.path("sequential_stopping_reason").asText())
                    .isEqualTo("FAIL_INEVITABLE_AT_FIXED_HORIZON");
        }

        ObjectNode duplicate = options.deepCopy();
        duplicate.withArray("selectedOutcomeRows").addObject()
                .put("episode_id", "e1").put("net_r", -.2).put("traded", true);
        assertThatThrownBy(() -> StrategyStatisticalV5.runNullControlsV5(duplicate, replaySame()))
                .hasMessage("selected OOS null vector is incomplete or duplicated");
        ObjectNode outside = options.deepCopy();
        outside.withArray("selectedOutcomeRows").removeAll().addObject()
                .put("episode_id", "outside").put("net_r", -.2).put("traded", true);
        assertThatThrownBy(() -> StrategyStatisticalV5.runNullControlsV5(outside, replaySame()))
                .hasMessage("selected OOS null vector is incomplete or duplicated");
    }

    @Test
    void nullControlsStopPassAtAttainableEnvelopeAndHandleUntradedRows() {
        ObjectNode options = controls(.3);
        options.put("iterations", 8).put("sequentialBatchSize", 1).put("alpha", .5);
        ObjectNode result = StrategyStatisticalV5.runNullControlsV5(options, replayZero());

        assertThat(result.path("pass").asBoolean()).isTrue();
        for (JsonNode row : result.path("tests")) {
            assertThat(row.path("iterations").asInt()).isEqualTo(5);
            assertThat(row.path("sequential_stopping_reason").asText())
                    .isEqualTo("PASS_INEVITABLE_AT_FIXED_HORIZON");
            assertThat(row.path("p_value_lower_bound").asDouble()).isEqualTo(1d / 9d);
            assertThat(row.path("p_value_upper_bound").asDouble()).isEqualTo(4d / 9d);
            assertThat(row.path("pass").asBoolean()).isTrue();
        }

        ObjectNode untraded = (ObjectNode) controls(0).path("artifact").deepCopy();
        for (JsonNode episode : untraded.withArray("episodes")) {
            ((ObjectNode) episode.path("candidate_returns").path("candidate-1"))
                    .put("net_r", 0).put("traded", false);
        }
        untraded = StrategyStatisticalV5.withHash(untraded);
        ObjectNode noTradeOptions = controlsFromArtifact(untraded);
        noTradeOptions.put("iterations", 1).put("sequentialBatchSize", 1);
        ObjectNode noTrade = StrategyStatisticalV5.runNullControlsV5(noTradeOptions, replaySame());
        assertThat(noTrade.path("observed_expectancy_r").asDouble()).isZero();
        assertThat(noTrade.path("tests")).allSatisfy(row -> assertThat(row.path("p_value").asDouble()).isEqualTo(1));
    }

    @Test
    void nullControlsAdmissionRejectsNondeterministicInputsBeforeReplay() {
        ObjectNode arrayArtifact = controls(.3);
        arrayArtifact.set("artifact", MAPPER.createArrayNode());
        assertThatThrownBy(() -> StrategyStatisticalV5.runNullControlsV5(arrayArtifact, replaySame()))
                .hasMessage("null controls require a canonical artifact and replay interface");

        ObjectNode noReplay = controls(.3).put("mode", "FIXTURE");
        assertThatThrownBy(() -> StrategyStatisticalV5.runNullControlsV5(noReplay))
                .hasMessage("fixture null controls require a replay interface");

        ObjectNode badDirection = controls(.3).put("directionalHypothesis", "two-sided");
        assertThatThrownBy(() -> StrategyStatisticalV5.runNullControlsV5(badDirection, replaySame()))
                .hasMessage("directional hypothesis must be positive or negative");

        for (ObjectNode invalid : List.of(
                controls(.3).put("iterations", 0),
                controls(.3).put("iterations", 1.5),
                controls(.3).put("sequentialBatchSize", 0),
                controls(.3).put("sequentialBatchSize", 1.5))) {
            assertThatThrownBy(() -> StrategyStatisticalV5.runNullControlsV5(invalid, replaySame()))
                    .hasMessage("null iterations and sequential batch size must be positive integers");
        }

        ObjectNode missingBudget = controls(.3);
        missingBudget.remove("selectionBudget");
        assertThatThrownBy(() -> StrategyStatisticalV5.runNullControlsV5(missingBudget, replaySame()))
                .hasMessage("winner’s-curse selection budget is incomplete or non-deterministic");
        ObjectNode missingMethod = controls(.3);
        Map<String, StrategyStatisticalV5.NullReplayMethod> methods = new LinkedHashMap<>();
        methods.put(METHODS.get(0), args -> (ObjectNode) args.path("artifact").deepCopy());
        assertThatThrownBy(() -> StrategyStatisticalV5.runNullControlsV5(missingMethod,
                new StrategyStatisticalV5.NullReplaySuite(methods)))
                .hasMessage("null replay method timestamp_shifted_outcomes is missing");
    }

    @Test
    void physicalRunnerFactoryRejectsEveryCallerOwnedTrustSurface() {
        ObjectNode options = MAPPER.createObjectNode();
        options.putObject("transformAndSelect").put("callback", true);
        assertThatThrownBy(() -> StrategyStatisticalV5.makePhysicalNullRunnerV5(options, null))
                .hasMessage("authoritative physical null factory does not accept caller transform callbacks; use the verified evaluator implementation");

        options.remove("transformAndSelect");
        assertThatThrownBy(() -> StrategyStatisticalV5.makePhysicalNullRunnerV5(options, null))
                .hasMessage("physical null runner requires the internal trust-marked physical worker evaluator");

        ObjectNode provenance = trustedLookingProvenance();
        StrategyEvaluatorV5.Evaluator unregistered = new StrategyEvaluatorV5.Evaluator() {
            @Override public ObjectNode evaluate(ObjectNode args) { return MAPPER.createObjectNode(); }
            @Override public ObjectNode workerProvenance() { return provenance.deepCopy(); }
            @Override public boolean physicalNullSelectionVerified() { return true; }
        };
        assertThat(StrategyEvaluatorV5.isVerifiedPhysicalEvaluator(unregistered)).isFalse();
        assertThatThrownBy(() -> StrategyStatisticalV5.makePhysicalNullRunnerV5(MAPPER.createObjectNode(), unregistered))
                .hasMessage("physical null runner requires the internal trust-marked physical worker evaluator");
    }

    private static ObjectNode controls(double net) {
        String alias = hash("null-boundary-alias");
        ObjectNode headOptions = object().put("hypothesisFamily", "null-boundary")
                .put("datasetSha256", hash("null-boundary-data"));
        headOptions.putArray("entries").addObject().put("behavior_sha256", alias)
                .put("dataset_sha256", hash("null-boundary-data"));
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headOptions);
        ObjectNode artifactOptions = object();
        artifactOptions.set("exposureHead", head);
        ObjectNode lineage = artifactOptions.putObject("lineage");
        for (String key : List.of("dataset_sha256", "candidate_set_sha256", "feature_set_sha256",
                "label_set_sha256", "execution_set_sha256")) lineage.put(key, hash("null-boundary-" + key));
        artifactOptions.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", alias);
        ArrayNode episodes = artifactOptions.putArray("episodes");
        addEpisode(episodes, "e1", "2026-01-01T00:00:00Z", net - .1);
        addEpisode(episodes, "e2", "2026-02-01T00:00:00Z", net);
        addEpisode(episodes, "e3", "2026-03-01T00:00:00Z", net + .1);
        return controlsFromArtifact(StrategyStatisticalV5.makeStatisticalArtifactSet(artifactOptions));
    }

    private static ObjectNode controlsFromArtifact(ObjectNode artifact) {
        ObjectNode options = object();
        options.set("artifact", artifact);
        options.put("mode", "FIXTURE").put("selectedCandidateId", "candidate-1")
                .put("seed", 11).put("alpha", .05);
        options.set("selectionBudget", selectionBudget());
        return options;
    }

    private static void addEpisode(ArrayNode episodes, String id, String decision, double net) {
        ObjectNode row = episodes.addObject().put("episode_id", id).put("asset", "btc")
                .put("decision_time", decision).put("resolution_time", decision)
                .put("eligible", true);
        row.putObject("candidate_returns").putObject("candidate-1")
                .put("net_r", net).put("traded", net != 0);
        // The canonical artifact requires a strictly later resolution timestamp.
        row.put("resolution_time", decision.replace("T00:00:00Z", "T00:01:00Z"));
    }

    private static StrategyStatisticalV5.NullReplaySuite replaySame() {
        return replay(value -> (ObjectNode) value.path("artifact").deepCopy());
    }

    private static StrategyStatisticalV5.NullReplaySuite replayZero() {
        return replay(value -> {
            ObjectNode artifact = (ObjectNode) value.path("artifact").deepCopy();
            for (JsonNode episode : artifact.path("episodes")) {
                JsonNode returns = episode.path("candidate_returns").path("candidate-1");
                if (returns.isObject()) ((ObjectNode) returns).put("net_r", 0).put("traded", false);
            }
            return StrategyStatisticalV5.withHash(artifact);
        });
    }

    private static StrategyStatisticalV5.NullReplaySuite replay(
            java.util.function.Function<ObjectNode, ObjectNode> callback) {
        Map<String, StrategyStatisticalV5.NullReplayMethod> methods = new LinkedHashMap<>();
        for (String method : METHODS) methods.put(method, args -> callback.apply(args));
        return new StrategyStatisticalV5.NullReplaySuite(methods);
    }

    private static ObjectNode selectionBudget() {
        ObjectNode value = object().put("population", 2).put("generations", 1);
        value.putArray("seeds").add(11).add(23).add(47);
        return value;
    }

    private static ObjectNode trustedLookingProvenance() {
        return object().put("schema", "strategy-v5-statistical-worker/1")
                .put("verified", true).put("deterministic", true)
                .put("artifact_paths_bound", true).put("physical_role_binding", true)
                .put("worker_count", 1).put("memory_budget_mb", 64)
                .put("feature_artifact_sha256", hash("feature"))
                .put("label_artifact_sha256", hash("label"))
                .put("execution_artifact_sha256", hash("execution"))
                .put("physical_null_code_sha256", hash("code"));
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static String hash(String value) { return JsonHashes.sha256(value); }
}
