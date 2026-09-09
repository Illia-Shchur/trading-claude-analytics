package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exercises retained physical OOS binding with a complete synthetic SHADOW WFO. */
final class StrategyStatisticalV5EvaluatorValidationMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final String ALIAS = JsonHashes.sha256("retained-oos-binding-behavior");
    private static final String DATASET = JsonHashes.sha256("retained-oos-binding-dataset");

    @Test
    void nestedWfoRejectsEvaluatorWithoutTheRequiredEnvelope() {
        assertEvaluatorFailure(task -> object(), "evaluator must return candidate_returns and hard metrics");
    }

    @Test
    void nestedWfoRejectsEvaluatorWithAnIncompleteEpisodeInventory() {
        assertEvaluatorFailure(task -> {
            ObjectNode result = evaluator().evaluate(task);
            String first = task.path("episode_ids").get(0).asText();
            ((ObjectNode) result.path("candidate_returns")).remove(first);
            return result;
        }, "evaluator returned incomplete episode inventory");
    }

    @Test
    void nestedWfoRejectsEvaluatorWithAnInvalidEpisodeValue() {
        assertEvaluatorFailure(task -> {
            ObjectNode result = evaluator().evaluate(task);
            String first = task.path("episode_ids").get(0).asText();
            ((ObjectNode) result.path("candidate_returns").path(first)).remove("traded");
            return result;
        }, "evaluator returned invalid episode");
    }

    @Test
    void nestedWfoRejectsEvaluatorMissingARequiredHardMetric() {
        assertEvaluatorFailure(task -> {
            ObjectNode result = evaluator().evaluate(task);
            ((ObjectNode) result.path("metrics")).remove("cost_r");
            return result;
        }, "hard metric cost_r is missing");
    }

    private static void assertEvaluatorFailure(StrategyEvaluatorV5.Evaluator badEvaluator, String message) {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> StrategyStatisticalV5.runNestedWfoV5(fixture.options.deepCopy(), badEvaluator,
                stressProvider(), portfolioProvider(), vectorProvider(), replaySuite(), null))
                .hasMessageContaining(message);
    }

    private static Fixture fixture() {
        return ShadowHolder.VALUE;
    }

    private static final class ShadowHolder {
        private static final Fixture VALUE = buildFixture();
    }

    private static Fixture buildFixture() {
        ObjectNode headOptions = object().put("hypothesisFamily", "retained-oos-binding")
                .put("datasetSha256", DATASET);
        headOptions.putArray("entries").addObject().put("behavior_sha256", ALIAS).put("dataset_sha256", DATASET);
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headOptions);

        ObjectNode artifactOptions = object();
        ObjectNode lineage = artifactOptions.putObject("lineage").put("dataset_sha256", DATASET);
        for (String key : List.of("candidate_set_sha256", "feature_set_sha256", "label_set_sha256",
                "execution_set_sha256")) lineage.put(key, hash("retained-" + key));
        artifactOptions.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", ALIAS);
        artifactOptions.set("exposureHead", head);
        ArrayNode episodes = artifactOptions.putArray("episodes");
        ZonedDateTime start = ZonedDateTime.parse("2022-01-01T00:00:00Z");
        for (int index = 0; index < 48; index++) {
            Instant decision = start.plusMonths(index).toInstant();
            Instant resolution = start.plusMonths(index).plusDays(1).toInstant();
            episodes.addObject().put("episode_id", String.format("w%02d", index + 1)).put("asset", "btc")
                    .put("decision_time", decision.toString()).put("resolution_time", resolution.toString())
                    .put("eligible", true).putObject("candidate_returns").putObject("candidate-1")
                    .put("net_r", valueForIndex(index)).put("traded", true);
        }
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactOptions);

        ObjectNode options = object(); options.set("artifact", artifact); options.set("exposureHead", head);
        options.put("mode", "FIXTURE").put("endAt", "2026-01-01T00:00:00Z");
        ObjectNode space = options.putObject("geneSpace");
        space.putArray("genes").addObject().put("name", "holding_period").put("type", "ordered-discrete")
                .put("default", 2).putArray("values").add(1).add(2).add(3);
        ObjectNode constraints = options.putObject("constraints").put("minEpisodes", 1)
                .put("minExpectancy", -10).put("minProfitFactor", 0).put("maxDrawdownR", 100)
                .put("maxCostR", 10).put("minCoverage", 0).put("requireCapacityPass", true);
        ObjectNode config = options.putObject("config").put("population", 2).put("generations", 1)
                .put("minGenerations", 1).put("plateauGenerations", 1).put("crossoverProbability", .9)
                .put("mutationProbability", 0).put("halfLifeMonths", 18).put("bootstrapIterations", 8)
                .put("maxStatIterations", 16).put("seed", 11).put("minPositiveFolds", 1)
                .put("minPositiveYears", 1).put("minTradesPerYear", 1).put("minPlateau", 1)
                .put("minNeighbourFraction", 0).put("minSeedCount", 1).put("minEpisodes", 1)
                .put("prospectiveCutoff", "2026-01-01T00:00:00Z").put("nullIterations", 32)
                .put("nullSequentialBatchSize", 8);
        config.set("constraints", constraints.deepCopy());
        config.putArray("seeds").add(11).add(23).add(47);
        config.set("selectionBudget", selectionBudget());

        ObjectNode reusableOptions = options.deepCopy();
        ObjectNode output = StrategyStatisticalV5.runNestedWfoV5(options, evaluator(), stressProvider(),
                portfolioProvider(), vectorProvider(), replaySuite(), null);
        return new Fixture(((ObjectNode) output.path("run")).deepCopy(),
                ((ObjectNode) output.path("artifact")).deepCopy(),
                ((ObjectNode) output.path("vectorInventory")).deepCopy(),
                ((ObjectNode) output.path("exposureHead")).deepCopy(), reusableOptions);
    }

    private static StrategyEvaluatorV5.Evaluator evaluator() {
        return task -> {
            ObjectNode result = object();
            ObjectNode returns = result.putObject("candidate_returns");
            for (JsonNode id : task.path("episode_ids")) returns.putObject(id.asText())
                    .put("net_r", valueForEpisode(id.asText())).put("traded", true);
            result.putObject("metrics").put("cost_r", .01).put("coverage_fraction", 1)
                    .put("capacity_pass", true).put("max_drawdown_r", -.1).put("profit_factor", 2)
                    .put("turnover", 1).put("complexity", 1);
            result.putObject("required").put("bootstrapIterations", 8).put("seed", 11);
            return result;
        };
    }

    private static StrategyStatisticalV5.StatisticalProvider stressProvider() {
        return task -> StrategyStatisticalV5.makeStressDecision(object()
                .put("lineage_sha256", task.path("lineage_sha256").asText()).put("pass", true)
                .put("sourceArtifactSha256", task.path("artifact").path("content_sha256").asText())
                .put("selectedCandidateId", task.path("selected_candidate_id").asText()));
    }

    private static StrategyStatisticalV5.StatisticalProvider portfolioProvider() {
        return task -> {
            ObjectNode args = object().put("lineage_sha256", task.path("lineage_sha256").asText())
                    .put("sourceArtifactSha256", task.path("artifact").path("content_sha256").asText())
                    .put("pass", true);
            args.set("assetDecisions", task.path("asset_decisions"));
            ArrayNode increments = args.putArray("returnIncrements");
            for (JsonNode decision : task.path("asset_decisions")) for (JsonNode row : decision.path("selected_return_vector")) {
                if (row.path("traded").asBoolean(false)) increments.addObject().put("episode_id", row.path("episode_id").asText())
                        .put("asset", row.path("asset").asText()).put("net_r", row.path("net_r").asDouble());
            }
            return StrategyStatisticalV5.makePortfolioDecision(args);
        };
    }

    private static StrategyStatisticalV5.StatisticalProvider vectorProvider() {
        return task -> {
            ObjectNode args = object().set("exposureHead", task.path("exposureHead"));
            args.set("episodeIds", task.path("episode_ids")); ObjectNode vectors = args.putObject("vectors");
            for (JsonNode entry : task.path("exposureHead").path("entries")) {
                ArrayNode rows = vectors.putArray(entry.path("behavior_sha256").asText());
                for (JsonNode id : task.path("episode_ids")) rows.addObject().put("episode_id", id.asText())
                        .put("net_r", valueForEpisode(id.asText())).put("traded", true).put("eligible", true);
            }
            return StrategyStatisticalV5.makeVectorInventory(args);
        };
    }

    private static StrategyStatisticalV5.NullReplaySuite replaySuite() {
        Map<String, StrategyStatisticalV5.NullReplayMethod> methods = new LinkedHashMap<>();
        for (String method : List.of("block_permuted_labels", "timestamp_shifted_outcomes",
                "frequency_matched_random_intents", "winners_curse_selection")) {
            methods.put(method, args -> {
                ObjectNode replayed = ((ObjectNode) args.path("artifact")).deepCopy();
                String selected = args.path("selected_candidate_id").asText();
                for (JsonNode episode : replayed.path("episodes")) {
                    JsonNode row = episode.path("candidate_returns").path(selected);
                    if (row.isObject()) ((ObjectNode) row).put("net_r", 0).put("traded", false);
                }
                return StrategyStatisticalV5.withHash(replayed);
            });
        }
        return new StrategyStatisticalV5.NullReplaySuite(methods);
    }

    private static ObjectNode selectionBudget() {
        ObjectNode value = object().put("population", 2).put("generations", 1);
        value.putArray("seeds").add(11).add(23).add(47);
        return value;
    }

    private static double valueForIndex(int index) {
        return List.of(.12, .42, .19, .33, .15, .37, .24, .29).get(index % 8);
    }

    private static double valueForEpisode(String id) {
        if (id.length() < 2) return .2;
        try { return valueForIndex(Integer.parseInt(id.substring(1)) - 1); }
        catch (NumberFormatException ignored) { return .2; }
    }

    private static String hash(String value) { return JsonHashes.sha256(value); }
    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private record Fixture(ObjectNode run, ObjectNode artifact, ObjectNode vector, ObjectNode exposureHead,
                           ObjectNode options) {}
}
