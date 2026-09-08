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

/** Statistical audit selection, evidence, and temporal gate branch coverage. */
final class StrategyStatisticalV5AuditBranchMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @Test
    void auditComputesIndependentClustersYearsAndRecentWindowFromCandidateRows() {
        Fixture fixture = fixture();
        ObjectNode options = baseOptions(fixture, "candidate-1");
        options.put("trainingWeightedBootstrapP20", .12).putArray("trainingWeightedBootstrapP20s").add(.12).add(.08);
        options.with("config").withArray("outerTrainingPboEvidence").addObject()
                .put("source_phase", "OUTER_TRAIN_ONLY").put("outer_oos_bound", false)
                .put("candidate_count", 3).put("valid_combinations", 2).put("pbo", .1);
        options.putArray("folds").addObject().put("fold_id", "outer-1").put("test_expectancy_r", .2)
                .put("test_start", "2025-01-01T00:00:00Z").put("test_end", "2025-03-01T00:00:00Z");

        ObjectNode result = StrategyStatisticalV5.runStatisticalAuditV5(options);
        assertThat(result.path("selected_behavior_alias_sha256").asText()).isEqualTo(fixture.alias);
        assertThat(result.path("metrics").path("sample_count").asInt()).isEqualTo(7);
        assertThat(result.path("metrics").path("opportunity_count").asInt()).isEqualTo(9);
        assertThat(result.path("year_means")).hasSize(4);
        assertThat(result.path("independent_opportunity_count").asInt()).isEqualTo(9);
        assertThat(result.path("recent_window").path("rows").asInt()).isEqualTo(4);
        assertThat(result.path("recent_window").path("opportunity_rows").asInt()).isEqualTo(5);
        assertThat(result.path("year_means").get(0).path("year").asText()).isEqualTo("2023");
        assertThat(result.path("year_means").get(0).path("expectancy_r").asDouble()).isEqualTo(.05);
        assertThat(result.path("year_means").get(1).path("year").asText()).isEqualTo("2024");
        assertThat(result.path("year_means").get(1).path("expectancy_r").asDouble()).isEqualTo(.3);
        assertThat(result.path("year_means").get(2).path("year").asText()).isEqualTo("2025");
        assertThat(result.path("year_means").get(2).path("expectancy_r").asDouble()).isEqualTo(.25);
        assertThat(result.path("year_means").get(3).path("year").asText()).isEqualTo("2026");
        assertThat(result.path("year_means").get(3).path("expectancy_r").asDouble()).isEqualTo(.15);
        assertThat(result.path("pbo").path("aggregation").asText()).isEqualTo("WORST_OUTER_TRAIN_PANEL");
        assertThat(result.path("gates").path("pbo").asBoolean()).isTrue();
        assertThat(result.path("gates").path("minimum_independent_episodes").asBoolean()).isTrue();
        assertThat(StrategyStatisticalV5.validateContractSchema(result)).isTrue();
    }

    @Test
    void auditSelectsHeadAliasThroughACompleteVectorInventory() {
        Fixture fixture = fixture();
        ObjectNode options = baseOptions(fixture, fixture.alias);
        options.set("vectorInventory", vectorInventory(fixture));
        options.putNull("selectedOutcomeRows");
        ObjectNode result = StrategyStatisticalV5.runStatisticalAuditV5(options);
        assertThat(result.path("selected_candidate_id").asText()).isEqualTo(fixture.alias);
        assertThat(result.path("selected_behavior_alias_sha256").asText()).isEqualTo(fixture.alias);
        assertThat(result.path("vector_inventory_sha256").asText())
                .isEqualTo(options.path("vectorInventory").path("content_sha256").asText());
        assertThat(result.path("metrics").path("traded_count").asInt()).isEqualTo(7);
    }

    @Test
    void auditBuildsCanonicalAliasFromExplicitSelectedOosRows() {
        Fixture fixture = fixture();
        ObjectNode options = baseOptions(fixture, "external-candidate");
        ArrayNode selected = options.putArray("selectedOutcomeRows");
        for (JsonNode episode : fixture.artifact.path("episodes")) {
            ObjectNode candidate = (ObjectNode) episode.path("candidate_returns").path("candidate-1");
            selected.addObject().put("episode_id", episode.path("episode_id").asText())
                    .put("decision_time", episode.path("decision_time").asText())
                    .put("resolution_time", episode.path("resolution_time").asText())
                    .put("net_r", candidate.path("net_r").asDouble())
                    .put("traded", candidate.path("traded").asBoolean());
        }
        ObjectNode result = StrategyStatisticalV5.runStatisticalAuditV5(options);
        assertThat(result.path("selected_candidate_id").asText()).isEqualTo("external-candidate");
        assertThat(result.path("selected_behavior_alias_sha256").asText()).matches("[a-f0-9]{64}");
        assertThat(result.path("metrics").path("opportunity_count").asInt()).isEqualTo(9);

        ObjectNode missing = options.deepCopy();
        ((ObjectNode) missing.withArray("selectedOutcomeRows").get(0)).remove("resolution_time");
        assertThatThrownBy(() -> StrategyStatisticalV5.runStatisticalAuditV5(missing))
                .hasMessage("selected OOS vector is not canonical");
    }

    @Test
    void auditFailsClosedForUnknownSelectionAndDuplicateOrNonFiniteRows() {
        Fixture fixture = fixture();
        ObjectNode missing = baseOptions(fixture, "unknown-candidate");
        missing.putArray("selectedOutcomeRows");
        assertThatThrownBy(() -> StrategyStatisticalV5.runStatisticalAuditV5(missing))
                .hasMessage("selected OOS vector is missing");

        ObjectNode duplicate = baseOptions(fixture, "external-candidate");
        ArrayNode rows = selectedRows(duplicate, fixture);
        rows.add(rows.get(0).deepCopy());
        assertThatThrownBy(() -> StrategyStatisticalV5.runStatisticalAuditV5(duplicate))
                .hasMessage("selected OOS vector is not canonical");

        ObjectNode nonFinite = baseOptions(fixture, "external-candidate");
        ArrayNode nonFiniteRows = selectedRows(nonFinite, fixture);
        ((ObjectNode) nonFiniteRows.get(0)).put("net_r", "not-a-number");
        assertThatThrownBy(() -> StrategyStatisticalV5.runStatisticalAuditV5(nonFinite))
                .hasMessage("selected OOS vector is not canonical");
    }

    @Test
    void auditRequiresFrozenPolicyOutsideFixtureModeAndHandlesInvalidPboEvidence() {
        Fixture fixture = fixture();
        ObjectNode authoritative = baseOptions(fixture, "candidate-1");
        ObjectNode config = (ObjectNode) authoritative.path("config");
        config.put("mode", "AUTHORITATIVE");
        ObjectNode policy = config.putObject("constraints");
        policy.put("minEpisodes", 2).put("minExpectancy", -.5).put("minProfitFactor", 0)
                .put("maxDrawdownR", 2).put("maxCostR", .2).put("minCoverage", .9)
                .put("requireCapacityPass", true);
        ObjectNode scales = policy.putObject("violationScales");
        for (String key : List.of("episodes", "expectancy", "drawdown", "costs", "coverage", "capacity", "profit_factor")) {
            scales.put(key, 1);
        }
        config.putArray("outerTrainingPboEvidence").addObject()
                .put("source_phase", "OOS").put("outer_oos_bound", true)
                .put("candidate_count", 1).put("valid_combinations", 1).put("pbo", 1);
        ObjectNode result = StrategyStatisticalV5.runStatisticalAuditV5(authoritative);
        assertThat(result.path("pbo").path("pbo").isNull()).isTrue();
        assertThat(result.path("pbo").path("reason").asText())
                .isEqualTo("PBO_REQUIRES_COMPARABLE_MULTI_CANDIDATE_OUTER_TRAIN_EVIDENCE");
        assertThat(result.path("gates").path("pbo").asBoolean()).isFalse();

        ObjectNode invalidPolicy = baseOptions(fixture, "candidate-1");
        ObjectNode invalidConfig = (ObjectNode) invalidPolicy.with("config"); invalidConfig.put("mode", "AUTHORITATIVE");
        invalidConfig.putObject("constraints").put("minEpisodes", 2).put("minExpectancy", -.5)
                .put("minProfitFactor", 0).put("maxDrawdownR", 2).put("maxCostR", .2).put("minCoverage", 2)
                .put("requireCapacityPass", true);
        assertThatThrownBy(() -> StrategyStatisticalV5.runStatisticalAuditV5(invalidPolicy))
                .hasMessageContaining("invalid frozen thresholds");
    }

    @Test
    void auditUsesExplicitStressAbstractionAndFailClosedOptionalGates() {
        Fixture fixture = fixture();
        ObjectNode options = baseOptions(fixture, "candidate-1");
        ObjectNode config = (ObjectNode) options.path("config");
        config.put("requireStressInventory", false);
        options.putNull("trainingWeightedBootstrapP20").putArray("trainingWeightedBootstrapP20s").add(.2).add(-.1);
        options.putArray("geneticRuns").addObject();
        options.putArray("assetDecisions"); options.putArray("stressDecisions"); options.putNull("portfolioDecision");
        ObjectNode result = StrategyStatisticalV5.runStatisticalAuditV5(options);
        assertThat(result.path("gates").path("weighted_bootstrap_p20_positive").asBoolean()).isFalse();
        assertThat(result.path("gates").path("stress_ablation").asBoolean()).isTrue();
        assertThat(result.path("gates").path("plateau").asBoolean()).isFalse();
        assertThat(result.path("gates").path("asset_decisions").asBoolean()).isFalse();
        assertThat(result.path("gates").path("portfolio").asBoolean()).isFalse();
        assertThat(result.path("decision").asText()).isEqualTo("REJECTED");
    }

    private static ObjectNode baseOptions(Fixture fixture, String selectedId) {
        ObjectNode options = object(); options.set("artifact", fixture.artifact); options.set("exposureHead", fixture.head);
        options.put("selectedCandidateId", selectedId).putNull("selectedOutcomeRows").putNull("vectorInventory");
        options.putNull("trainingWeightedBootstrapP20").putArray("trainingWeightedBootstrapP20s");
        options.putArray("folds"); options.putArray("geneticRuns"); options.putArray("stressDecisions");
        options.putArray("assetDecisions");
        options.putNull("genetic").putNull("nullControls").putNull("portfolioDecision");
        ObjectNode config = options.putObject("config").put("mode", "FIXTURE")
                .put("bootstrapIterations", 16).put("maxStatIterations", 16).put("seed", 23)
                .put("halfLifeMonths", 18).put("minEpisodes", 2).put("minPositiveFolds", 1)
                .put("minPositiveYears", 1).put("minTradesPerYear", 1);
        config.putArray("outerTrainingPboEvidence");
        options.set("selectedMetrics", object().put("cost_r", .01).put("coverage_fraction", 1)
                .put("capacity_pass", true).put("max_drawdown_r", -.1).put("profit_factor", 2));
        options.set("nullControls", object().put("pass", true));
        return options;
    }

    private static ArrayNode selectedRows(ObjectNode options, Fixture fixture) {
        ArrayNode rows = options.putArray("selectedOutcomeRows");
        for (JsonNode episode : fixture.artifact.path("episodes")) {
            JsonNode candidate = episode.path("candidate_returns").path("candidate-1");
            rows.addObject().put("episode_id", episode.path("episode_id").asText())
                    .put("decision_time", episode.path("decision_time").asText())
                    .put("resolution_time", episode.path("resolution_time").asText())
                    .put("net_r", candidate.path("net_r").asDouble()).put("traded", candidate.path("traded").asBoolean());
        }
        return rows;
    }

    private static ObjectNode vectorInventory(Fixture fixture) {
        ObjectNode options = object(); options.set("exposureHead", fixture.head);
        ArrayNode ids = options.putArray("episodeIds"); ObjectNode vectors = options.putObject("vectors");
        ArrayNode rows = vectors.putArray(fixture.alias);
        for (JsonNode episode : fixture.artifact.path("episodes")) {
            ids.add(episode.path("episode_id").asText()); JsonNode candidate = episode.path("candidate_returns").path("candidate-1");
            rows.addObject().put("episode_id", episode.path("episode_id").asText()).put("net_r", candidate.path("net_r").asDouble())
                    .put("traded", candidate.path("traded").asBoolean()).put("eligible", true);
        }
        return StrategyStatisticalV5.makeVectorInventory(options);
    }

    private static Fixture fixture() {
        String alias = JsonHashes.sha256("audit-branch-alias"); String dataset = JsonHashes.sha256("audit-branch-data");
        ObjectNode headOptions = object().put("hypothesisFamily", "audit-branch").put("datasetSha256", dataset);
        headOptions.putArray("entries").addObject().put("behavior_sha256", alias).put("dataset_sha256", dataset);
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headOptions);
        ObjectNode input = object(); ObjectNode lineage = input.putObject("lineage");
        lineage.put("dataset_sha256", dataset);
        for (String key : List.of("candidate_set_sha256", "feature_set_sha256", "label_set_sha256", "execution_set_sha256")) {
            lineage.put(key, JsonHashes.sha256("audit-branch-" + key));
        }
        input.putArray("candidates").addObject().put("candidate_id", "candidate-1").put("behavior_sha256", alias);
        ArrayNode episodes = input.putArray("episodes");
        addEpisode(episodes, "e1", "2023-01-01T00:00:00Z", .2, true);
        addEpisode(episodes, "e2", "2023-06-01T00:00:00Z", -.1, true);
        addEpisode(episodes, "e3", "2024-02-01T00:00:00Z", .3, true);
        addEpisode(episodes, "e4", "2024-08-01T00:00:00Z", 0, false);
        addEpisode(episodes, "e5", "2025-01-01T00:00:00Z", .4, true);
        addEpisode(episodes, "e6", "2025-06-01T00:00:00Z", .1, true);
        addEpisode(episodes, "e7", "2026-01-01T00:00:00Z", -.2, true);
        addEpisode(episodes, "e8", "2026-03-01T00:00:00Z", .5, true);
        addEpisode(episodes, "e9", "2026-06-01T00:00:00Z", 0, false);
        input.set("exposureHead", head);
        return new Fixture(head, StrategyStatisticalV5.makeStatisticalArtifactSet(input), alias);
    }

    private static void addEpisode(ArrayNode episodes, String id, String decision, double net, boolean traded) {
        ObjectNode row = episodes.addObject().put("episode_id", id).put("asset", "btc")
                .put("decision_time", decision).put("resolution_time", decision.replace("T00:00:00Z", "T00:01:00Z"))
                .put("eligible", true);
        row.putObject("candidate_returns").putObject("candidate-1").put("net_r", net).put("traded", traded);
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private record Fixture(ObjectNode head, ObjectNode artifact, String alias) {}
}
