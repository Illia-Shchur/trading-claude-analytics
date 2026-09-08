package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Deterministic audit selection, aggregate economics, and evidence-binding coverage. */
final class StrategyStatisticalV5AuditSelectionValidationTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @Test
    void auditUsesHeadAliasVectorInventoryAndBoundEvidence() {
        Fixture fixture = fixture();
        ObjectNode options = auditOptions(fixture, fixture.alias());
        ObjectNode inventory = vectorInventory(fixture);
        options.set("vectorInventory", inventory);
        options.put("selectedCandidateId", fixture.alias());
        options.put("trainingWeightedBootstrapP20", .12);
        options.putArray("trainingWeightedBootstrapP20s").add(.12).add(.08);
        options.putArray("folds").addObject().put("test_expectancy_r", .2)
                .put("fold_id", "outer-1").put("test_start", "2025-01-01T00:00:00Z")
                .put("test_end", "2025-03-01T00:00:00Z");
        ObjectNode pboEvidence = ((ObjectNode) options.path("config")).withArray("outerTrainingPboEvidence").addObject();
        pboEvidence.put("source_phase", "OUTER_TRAIN_ONLY").put("outer_oos_bound", false)
                .put("candidate_count", 3).put("valid_combinations", 2).put("pbo", .1);

        ObjectNode result = StrategyStatisticalV5.runStatisticalAuditV5(options);
        assertThat(result.path("selected_candidate_id").asText()).isEqualTo(fixture.alias());
        assertThat(result.path("selected_behavior_alias_sha256").asText()).isEqualTo(fixture.alias());
        assertThat(result.path("vector_inventory_sha256").asText())
                .isEqualTo(inventory.path("content_sha256").asText());
        assertThat(result.path("max_statistic").path("candidate_count").asInt()).isEqualTo(1);
        assertThat(result.path("pbo").path("source_phase").asText()).isEqualTo("OUTER_TRAIN_ONLY");
        assertThat(result.path("gates").path("weighted_bootstrap_p20_positive").asBoolean()).isTrue();
        assertThat(result.path("gates").path("pbo").asBoolean()).isTrue();
        assertThat(result.path("gates").path("portfolio").asBoolean()).isTrue();
        assertThat(StrategyStatisticalV5.validateContractSchema(result)).isTrue();
    }

    @Test
    void auditBindsSelectedOosRowsAndRejectsNonCanonicalSelection() {
        Fixture fixture = fixture();
        ObjectNode options = auditOptions(fixture, "selected-candidate");
        ArrayNode rows = options.putArray("selectedOutcomeRows");
        for (JsonNode episode : fixture.artifact().path("episodes")) {
            rows.addObject().put("episode_id", episode.path("episode_id").asText())
                    .put("decision_time", episode.path("decision_time").asText())
                    .put("resolution_time", episode.path("resolution_time").asText())
                    .put("net_r", episode.path("candidate_returns").path("candidate-1").path("net_r").asDouble())
                    .put("traded", true);
        }
        ObjectNode result = StrategyStatisticalV5.runStatisticalAuditV5(options);
        assertThat(result.path("selected_candidate_id").asText()).isEqualTo("selected-candidate");
        assertThat(result.path("selected_behavior_alias_sha256").asText()).matches("[a-f0-9]{64}");
        assertThat(result.path("opportunity_count").asInt()).isEqualTo(4);
        assertThat(result.path("gates").path("minimum_independent_episodes").asBoolean()).isTrue();

        ObjectNode missingTime = options.deepCopy();
        ((ObjectNode) missingTime.withArray("selectedOutcomeRows").get(0)).remove("decision_time");
        assertThatThrownBy(() -> StrategyStatisticalV5.runStatisticalAuditV5(missingTime))
                .hasMessage("selected OOS vector is not canonical");
        ObjectNode duplicate = options.deepCopy();
        duplicate.withArray("selectedOutcomeRows").add(duplicate.path("selectedOutcomeRows").get(0));
        assertThatThrownBy(() -> StrategyStatisticalV5.runStatisticalAuditV5(duplicate))
                .hasMessage("selected OOS vector is not canonical");
    }

    @Test
    void aggregateAssetDecisionSeparatesProceduralEvidenceAndMixedTradeEconomics() {
        ArrayNode rows = MAPPER.createArrayNode();
        rows.add(assetFold("outer-1", "2025-01-01T00:00:00Z", .4, true, true));
        rows.add(assetFold("outer-2", "2026-01-01T00:00:00Z", 0, false, false));
        ObjectNode required = policy();
        required.put("mode", "AUTHORITATIVE");
        ObjectNode aggregate = StrategyStatisticalV5.aggregateAssetDecision(rows, required);
        assertThat(aggregate.path("fold_summary").path("fold_count").asInt()).isEqualTo(2);
        assertThat(aggregate.path("fold_summary").path("positive_folds").asInt()).isEqualTo(1);
        assertThat(aggregate.path("metrics").path("traded_count").asInt()).isEqualTo(1);
        assertThat(aggregate.path("metrics").path("expectancy_r").asDouble()).isEqualTo(.4);
        assertThat(aggregate.path("asset_gates").path("procedure_validation").asBoolean()).isFalse();
        assertThat(aggregate.path("asset_gates").path("pbo").asBoolean()).isTrue();
        assertThat(aggregate.path("asset_gates").path("stress_survival").asBoolean()).isFalse();
        assertThat(aggregate.path("decision_type").asText()).isEqualTo("ASSET");
        assertThat(aggregate.path("provenance").asText()).isEqualTo("AUTHORITATIVE_RECOMPUTED");
        assertThat(aggregate.path("content_sha256").asText())
                .isEqualTo(JsonHashes.ownHash(aggregate));

        ObjectNode empty = StrategyStatisticalV5.aggregateAssetDecision(MAPPER.createArrayNode(), required);
        assertThat(empty.path("reason").asText()).isEqualTo("MISSING_ASSET_FOLDS");
        assertThat(empty.path("asset_gates").isMissingNode()).isTrue();
    }

    @Test
    void auditOptionalGatesHandleMissingPlateauAndInvalidWeightedEvidence() {
        Fixture fixture = fixture();
        ObjectNode options = auditOptions(fixture, "candidate-1");
        options.putNull("trainingWeightedBootstrapP20");
        options.putArray("trainingWeightedBootstrapP20s").add(.2).add(-.1);
        options.putArray("geneticRuns").addObject();
        options.putArray("stressDecisions");
        options.putArray("assetDecisions");
        options.putNull("portfolioDecision");
        ObjectNode result = StrategyStatisticalV5.runStatisticalAuditV5(options);
        assertThat(result.path("gates").path("weighted_bootstrap_p20_positive").asBoolean()).isFalse();
        assertThat(result.path("gates").path("plateau").asBoolean()).isFalse();
        assertThat(result.path("plateau").path("connected_profitable_plateau_size").asInt()).isZero();
        assertThat(result.path("gates").path("asset_decisions").asBoolean()).isFalse();
        assertThat(result.path("gates").path("portfolio").asBoolean()).isFalse();
        assertThat(result.path("decision").asText()).isEqualTo("REJECTED");
    }

    private static Fixture fixture() {
        String alias = hash("audit-selection-alias");
        ObjectNode headOptions = object().put("hypothesisFamily", "audit-selection")
                .put("datasetSha256", hash("audit-selection-data"));
        headOptions.putArray("entries").addObject().put("behavior_sha256", alias)
                .put("dataset_sha256", hash("audit-selection-data"));
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headOptions);
        ObjectNode input = object(); input.set("exposureHead", head);
        ObjectNode lineage = input.putObject("lineage");
        for (String key : List.of("dataset_sha256", "candidate_set_sha256", "feature_set_sha256",
                "label_set_sha256", "execution_set_sha256")) lineage.put(key, hash("audit-selection-" + key));
        input.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", alias);
        ArrayNode episodes = input.putArray("episodes");
        addEpisode(episodes, "e1", "2025-01-01T00:00:00Z", .2, true);
        addEpisode(episodes, "e2", "2025-04-01T00:00:00Z", .4, true);
        addEpisode(episodes, "e3", "2026-01-01T00:00:00Z", .1, true);
        addEpisode(episodes, "e4", "2026-04-01T00:00:00Z", -.1, true);
        return new Fixture(head, StrategyStatisticalV5.makeStatisticalArtifactSet(input), alias);
    }

    private static ObjectNode auditOptions(Fixture fixture, String selectedId) {
        ObjectNode options = object(); options.set("artifact", fixture.artifact());
        options.set("exposureHead", fixture.head()); options.put("selectedCandidateId", selectedId);
        options.putNull("selectedOutcomeRows"); options.putNull("vectorInventory");
        options.putNull("trainingWeightedBootstrapP20"); options.putArray("trainingWeightedBootstrapP20s");
        options.putArray("folds"); options.putNull("genetic"); options.putArray("geneticRuns");
        options.putArray("assetDecisions"); options.putArray("stressDecisions");
        options.putNull("nullControls"); options.putNull("portfolioDecision");
        ObjectNode config = options.putObject("config").put("mode", "FIXTURE")
                .put("bootstrapIterations", 16).put("maxStatIterations", 16).put("seed", 23)
                .put("halfLifeMonths", 36).put("minEpisodes", 2).put("minPositiveFolds", 1)
                .put("minPositiveYears", 1).put("minTradesPerYear", 1);
        config.putArray("outerTrainingPboEvidence");
        options.set("selectedMetrics", object().put("cost_r", .01).put("coverage_fraction", 1)
                .put("capacity_pass", true).put("max_drawdown_r", -.1).put("profit_factor", 2));
        options.set("nullControls", object().put("pass", true));
        ObjectNode asset = assetDecision("btc", true); options.putArray("assetDecisions").add(asset);
        ObjectNode stress = StrategyStatisticalV5.makeStressDecision(object()
                .put("lineage_sha256", hash("audit-lineage")).put("sourceArtifactSha256",
                        fixture.artifact().path("content_sha256").asText()).put("selectedCandidateId", selectedId)
                .put("pass", true));
        options.putArray("stressDecisions").add(stress);
        ObjectNode portfolioOptions = object().put("lineage_sha256", hash("audit-lineage"))
                .put("sourceArtifactSha256", fixture.artifact().path("content_sha256").asText()).put("pass", true);
        ArrayNode decisions = portfolioOptions.putArray("assetDecisions"); decisions.add(asset.deepCopy());
        portfolioOptions.putArray("returnIncrements").addObject().put("episode_id", "e1")
                .put("asset", "btc").put("net_r", .2);
        options.set("portfolioDecision", StrategyStatisticalV5.makePortfolioDecision(portfolioOptions));
        return options;
    }

    private static ObjectNode vectorInventory(Fixture fixture) {
        ObjectNode options = object(); options.set("exposureHead", fixture.head());
        ArrayNode ids = options.putArray("episodeIds");
        ArrayNode rows = options.putObject("vectors").putArray(fixture.alias());
        for (JsonNode episode : fixture.artifact().path("episodes")) {
            ids.add(episode.path("episode_id").asText());
            rows.addObject().put("episode_id", episode.path("episode_id").asText())
                    .put("net_r", episode.path("candidate_returns").path("candidate-1").path("net_r").asDouble())
                    .put("traded", true).put("eligible", true);
        }
        return StrategyStatisticalV5.makeVectorInventory(options);
    }

    private static ObjectNode assetFold(String fold, String decision, double net,
            boolean procedurePass, boolean stressPass) {
        ObjectNode row = object().put("fold_id", fold);
        row.putObject("metrics").put("expectancy_r", net).put("cost_r", .01)
                .put("coverage_fraction", 1).put("capacity_pass", true)
                .put("max_drawdown_r", -.1).put("profit_factor", net > 0 ? 2 : .5)
                .put("traded_count", net > 0 ? 1 : 0);
        row.putObject("stress").put("pass", stressPass);
        row.putObject("procedure_validation").put("pass", procedurePass);
        ObjectNode pbo = row.putObject("pbo").put("source_phase", "OUTER_TRAIN_ONLY")
                .put("outer_oos_bound", false).put("candidate_count", 3).put("valid_combinations", 2)
                .put("pbo", .1);
        row.put("pbo_pass", true);
        ArrayNode vector = row.putArray("selected_return_vector");
        vector.addObject().put("episode_id", fold + "-trade").put("asset", "btc")
                .put("decision_time", decision).put("resolution_time", decision.replace("T00:00:00Z", "T00:01:00Z"))
                .put("net_r", net).put("traded", net > 0).put("eligible", true);
        vector.addObject().put("episode_id", fold + "-no-trade").put("asset", "btc")
                .put("decision_time", decision.replace("T00:00:00Z", "T00:02:00Z"))
                .put("resolution_time", decision.replace("T00:00:00Z", "T00:03:00Z"))
                .put("net_r", 0).put("traded", false).put("eligible", true);
        row.put("content_sha256", JsonHashes.ownHash(row));
        return row;
    }

    private static ObjectNode assetDecision(String asset, boolean pass) {
        ObjectNode value = object().put("asset", asset).put("pass", pass)
                .put("decision_type", "ASSET").put("provenance", "AUTHORITATIVE_RECOMPUTED");
        return StrategyStatisticalV5.withHash(value);
    }

    private static ObjectNode policy() {
        ObjectNode value = object().put("minEpisodes", 1).put("minExpectancy", -.5)
                .put("minProfitFactor", 0).put("maxDrawdownR", 2).put("maxCostR", .2)
                .put("minCoverage", .9).put("requireCapacityPass", true);
        ObjectNode scales = value.putObject("violation_scales");
        for (String key : List.of("episodes", "expectancy", "drawdown", "costs", "coverage", "capacity", "profit_factor")) {
            scales.put(key, 1);
        }
        value.set("constraints", value.deepCopy());
        return value;
    }

    private static void addEpisode(ArrayNode episodes, String id, String decision, double net, boolean traded) {
        ObjectNode row = episodes.addObject().put("episode_id", id).put("asset", "btc")
                .put("decision_time", decision).put("resolution_time", decision.replace("T00:00:00Z", "T00:01:00Z"))
                .put("eligible", true);
        row.putObject("candidate_returns").putObject("candidate-1").put("net_r", net).put("traded", traded);
    }

    private record Fixture(ObjectNode head, ObjectNode artifact, String alias) {
        @Override public ObjectNode head() { return head.deepCopy(); }
        @Override public ObjectNode artifact() { return artifact.deepCopy(); }
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static String hash(String value) { return JsonHashes.sha256(value); }
}
