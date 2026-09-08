package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Nested-WFO scope variants that exercise deterministic asset admission and provider binding. */
final class StrategyStatisticalV5NestedScopeMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @Test
    void nestedWfoAcceptsExplicitTradeReplicationAndContextScope() {
        ObjectNode options = nestedFixture(false);
        options.putObject("assetScope").putArray("trade_assets").add("btc");
        ((ObjectNode) options.path("assetScope")).putArray("replication_assets").add("eth");
        ((ObjectNode) options.path("assetScope")).putArray("context_assets").add("macro-regime");
        ObjectNode scope = (ObjectNode) options.path("assetScope");
        scope.put("schema", "strategy-v5-statistical-asset-scope/1").put("version", 1)
                .put("source_sha256", JsonHashes.sha256("precommitted-scope"));
        scope.put("content_sha256", StrategyStatisticalV5.ownHash(scope));
        ((ObjectNode) options.path("config")).set("assetScope", scope);

        ObjectNode result = run(options);
        assertThat(result.path("assetScope").path("trade_assets").size()).isEqualTo(1);
        assertThat(result.path("assetScope").path("trade_assets").get(0).asText()).isEqualTo("btc");
        assertThat(result.path("assetScope").path("replication_assets").size()).isEqualTo(1);
        assertThat(result.path("assetScope").path("replication_assets").get(0).asText()).isEqualTo("eth");
        assertThat(result.path("assetScope").path("context_assets").size()).isEqualTo(1);
        assertThat(result.path("assetScope").path("context_assets").get(0).asText()).isEqualTo("macro-regime");
        assertThat(result.path("run").path("fold_count").asInt()).isEqualTo(8);
        assertThat(StrategyStatisticalV5.validateNestedWfoArtifact(result.path("run"))).isTrue();
    }

    @Test
    void nestedWfoRecordsInsufficientTrainingForObservedTradeAsset() {
        ObjectNode options = nestedFixture(true);
        ObjectNode scope = options.putObject("assetScope");
        scope.putArray("trade_assets").add("btc").add("eth");
        scope.putArray("replication_assets"); scope.putArray("context_assets");
        scope.put("schema", "strategy-v5-statistical-asset-scope/1").put("version", 1).putNull("source_sha256");
        scope.put("content_sha256", StrategyStatisticalV5.ownHash(scope));
        ((ObjectNode) options.path("config")).set("assetScope", scope);

        ObjectNode result = run(options);
        assertThat(result.path("assetScope").path("trade_assets")).hasSize(2);
        JsonNode firstDecision = result.path("run").path("asset_decisions_final").get(0);
        JsonNode secondDecision = result.path("run").path("asset_decisions_final").get(1);
        assertThat(firstDecision.path("asset").asText()).isEqualTo("btc");
        assertThat(secondDecision.path("asset").asText()).isEqualTo("eth");
        assertThat(secondDecision.path("pass").asBoolean()).isFalse();
        assertThat(secondDecision.path("reason").asText()).isEqualTo("INSUFFICIENT_TRAIN_EPISODES");
    }

    @Test
    void nestedWfoRejectsScopeOmissionsAndCategoryOverlapBeforeProvidersRun() {
        ObjectNode omitted = nestedFixture(true);
        ObjectNode scope = omitted.putObject("assetScope"); scope.putArray("trade_assets").add("btc");
        scope.putArray("replication_assets"); scope.putArray("context_assets");
        scope.put("schema", "strategy-v5-statistical-asset-scope/1").put("version", 1).putNull("source_sha256");
        scope.put("content_sha256", StrategyStatisticalV5.ownHash(scope));
        ((ObjectNode) omitted.path("config")).set("assetScope", scope);
        assertThatThrownBy(() -> run(omitted, throwingEvaluator(), throwingProvider(), throwingProvider(), throwingProvider()))
                .hasMessageContaining("asset scope omits canonical artifact asset(s): eth");

        ObjectNode overlap = nestedFixture(false); ObjectNode overlapping = overlap.putObject("assetScope");
        overlapping.putArray("trade_assets").add("btc"); overlapping.putArray("replication_assets").add("btc");
        overlapping.putArray("context_assets"); overlapping.put("schema", "strategy-v5-statistical-asset-scope/1")
                .put("version", 1).putNull("source_sha256").put("content_sha256", StrategyStatisticalV5.ownHash(overlapping));
        ((ObjectNode) overlap.path("config")).set("assetScope", overlapping);
        assertThatThrownBy(() -> run(overlap, throwingEvaluator(), throwingProvider(), throwingProvider(), throwingProvider()))
                .hasMessageContaining("asset scope overlaps trade_assets and replication_assets: btc");
    }

    private static ObjectNode run(ObjectNode options) {
        return StrategyStatisticalV5.runNestedWfoV5(options, evaluator(), stressProvider(), portfolioProvider(), vectorProvider());
    }

    private static ObjectNode run(ObjectNode options, StrategyEvaluatorV5.Evaluator evaluator,
            StrategyStatisticalV5.StatisticalProvider stress, StrategyStatisticalV5.StatisticalProvider portfolio,
            StrategyStatisticalV5.StatisticalProvider vectors) {
        return StrategyStatisticalV5.runNestedWfoV5(options, evaluator, stress, portfolio, vectors);
    }

    private static ObjectNode nestedFixture(boolean extraAsset) {
        String behavior = JsonHashes.sha256("nested-scope-behavior");
        ObjectNode headOptions = object().put("hypothesisFamily", "nested-scope")
                .put("datasetSha256", JsonHashes.sha256("nested-scope-data"));
        headOptions.putArray("entries").addObject().put("behavior_sha256", behavior)
                .put("dataset_sha256", JsonHashes.sha256("nested-scope-data"));
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headOptions);
        ObjectNode artifactOptions = object(); ObjectNode lineage = artifactOptions.putObject("lineage");
        lineage.put("dataset_sha256", JsonHashes.sha256("nested-scope-data"));
        for (String key : List.of("candidate_set_sha256", "feature_set_sha256", "label_set_sha256", "execution_set_sha256")) {
            lineage.put(key, JsonHashes.sha256("nested-scope-" + key));
        }
        artifactOptions.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", behavior); artifactOptions.set("exposureHead", head);
        ArrayNode episodes = artifactOptions.putArray("episodes"); ZonedDateTime start = ZonedDateTime.parse("2022-01-01T00:00:00Z");
        for (int index = 0; index < 48; index++) {
            ZonedDateTime decision = start.plusMonths(index);
            episode(episodes.addObject(), "w" + String.format("%02d", index + 1), "btc",
                    decision.toInstant().toString(), decision.plusDays(1).toInstant().toString());
        }
        if (extraAsset) episode(episodes.addObject(), "e49", "eth", "2025-12-15T00:00:00Z",
                "2025-12-16T00:00:00Z");
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactOptions);
        ObjectNode options = object(); options.set("artifact", artifact); options.set("exposureHead", head);
        options.put("mode", "FIXTURE").put("endAt", "2026-01-01T00:00:00Z");
        ObjectNode geneSpace = options.putObject("geneSpace");
        geneSpace.putArray("genes").addObject().put("name", "side").put("type", "categorical")
                .putArray("values").add("long");
        ObjectNode constraints = options.putObject("constraints").put("minEpisodes", 0).put("minExpectancy", -10)
                .put("minProfitFactor", 0).put("maxDrawdownR", 100).put("maxCostR", 10)
                .put("minCoverage", 0).put("requireCapacityPass", true);
        ObjectNode config = options.putObject("config").put("population", 2).put("generations", 1)
                .put("minGenerations", 1).put("plateauGenerations", 1).put("crossoverProbability", .9)
                .put("mutationProbability", 0).put("halfLifeMonths", 18).put("bootstrapIterations", 8)
                .put("seed", 11).put("prospectiveCutoff", "2026-01-01T00:00:00Z");
        config.set("constraints", constraints.deepCopy()); config.putArray("seeds").add(11).add(23).add(47);
        return options;
    }

    private static void episode(ObjectNode row, String id, String asset, String decision, String resolution) {
        row.put("episode_id", id).put("asset", asset).put("decision_time", decision)
                .put("resolution_time", resolution).put("eligible", true);
        row.putObject("candidate_returns").putObject("candidate-1").put("net_r", .2).put("traded", true);
    }

    private static StrategyEvaluatorV5.Evaluator evaluator() {
        return task -> {
            ObjectNode result = object(); ObjectNode returns = result.putObject("candidate_returns");
            for (JsonNode id : task.path("episode_ids")) returns.putObject(id.asText()).put("net_r", .2).put("traded", true);
            result.putObject("metrics").put("cost_r", .01).put("coverage_fraction", 1).put("capacity_pass", true)
                    .put("max_drawdown_r", -.1).put("profit_factor", 2).put("turnover", 1).put("complexity", 1);
            result.putObject("required").put("bootstrapIterations", 8).put("seed", 11); return result;
        };
    }

    private static StrategyStatisticalV5.StatisticalProvider stressProvider() {
        return task -> StrategyStatisticalV5.makeStressDecision(object()
                .put("lineage_sha256", task.path("lineage_sha256").asText()).put("pass", false)
                .put("sourceArtifactSha256", task.path("artifact").path("content_sha256").asText())
                .put("selectedCandidateId", task.path("selected_candidate_id").asText()));
    }

    private static StrategyStatisticalV5.StatisticalProvider portfolioProvider() {
        return task -> {
            ObjectNode args = object().put("lineage_sha256", task.path("lineage_sha256").asText())
                    .put("pass", false).put("sourceArtifactSha256", task.path("artifact").path("content_sha256").asText());
            args.set("assetDecisions", task.path("asset_decisions")); ArrayNode increments = args.putArray("returnIncrements");
            for (JsonNode decision : task.path("asset_decisions")) for (JsonNode row : decision.path("selected_return_vector")) {
                if (row.path("traded").asBoolean()) increments.addObject().put("episode_id", row.path("episode_id").asText())
                        .put("asset", row.path("asset").asText()).put("net_r", row.path("net_r").asDouble());
            }
            if (increments.isEmpty()) increments.addObject().put("episode_id", task.path("artifact").path("episodes").path(0)
                    .path("episode_id").asText()).put("asset", "btc").put("net_r", 0);
            return StrategyStatisticalV5.makePortfolioDecision(args);
        };
    }

    private static StrategyStatisticalV5.StatisticalProvider vectorProvider() {
        return task -> {
            ObjectNode args = object(); args.set("exposureHead", task.path("exposureHead"));
            args.set("episodeIds", task.path("episode_ids")); ObjectNode vectors = args.putObject("vectors");
            for (JsonNode entry : task.path("exposureHead").path("entries")) {
                ArrayNode rows = vectors.putArray(entry.path("behavior_sha256").asText());
                for (JsonNode id : task.path("episode_ids")) rows.addObject().put("episode_id", id.asText())
                        .put("net_r", .2).put("traded", true).put("eligible", true);
            }
            return StrategyStatisticalV5.makeVectorInventory(args);
        };
    }

    private static StrategyEvaluatorV5.Evaluator throwingEvaluator() {
        return task -> { throw new AssertionError("provider invoked before scope admission"); };
    }

    private static StrategyStatisticalV5.StatisticalProvider throwingProvider() {
        return task -> { throw new AssertionError("provider invoked before scope admission"); };
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
}
