package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Public statistical contract matrices for phase, gene, selection and evidence boundaries. */
final class StrategyStatisticalV5BoundaryMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final String DATASET = JsonHashes.sha256("boundary-dataset");

    @Test
    void geneSpaceOperatorsNormalizeDefaultsAndTypedNeighbours() {
        ObjectNode space = object(); ArrayNode genes = space.putArray("genes");
        genes.addObject().put("name", "threshold").put("type", "continuous")
                .put("min", 0).put("max", 10).put("default", 4);
        genes.addObject().put("name", "window").put("type", "ordered-discrete")
                .putArray("values").add(1).add(3).add(5);
        genes.addObject().put("name", "side").put("type", "categorical")
                .putArray("values").add("long").add("short");
        genes.addObject().put("name", "exit").put("type", "structural")
                .putArray("values").addObject().put("kind", "time");

        ObjectNode center = object().put("threshold", 4).put("window", 3).put("side", "long");
        center.putObject("exit").put("kind", "time");
        ArrayNode neighbours = StrategyStatisticalV5.enumerateDirectNeighbours(space, center);
        assertThat(neighbours).hasSize(4);
        assertThat(neighbours).anyMatch(row -> row.path("threshold").asDouble() == 3.5);
        assertThat(neighbours).anyMatch(row -> row.path("threshold").asDouble() == 4.5);
        assertThat(neighbours).anyMatch(row -> row.path("window").asInt() == 1);
        assertThat(neighbours).anyMatch(row -> row.path("window").asInt() == 5);

        ArrayNode edge = StrategyStatisticalV5.enumerateDirectNeighbours(space,
                center.put("threshold", 0).put("window", 1));
        assertThat(edge).hasSize(2);
        assertThat(edge).anyMatch(row -> row.path("threshold").asDouble() == .5
                && row.path("window").asInt() == 1);
        assertThat(edge).anyMatch(row -> row.path("threshold").asDouble() == 0
                && row.path("window").asInt() == 3);

        ObjectNode duplicate = object(); ArrayNode duplicateGenes = duplicate.putArray("genes");
        duplicateGenes.addObject().put("name", "x").put("type", "continuous").put("min", 0).put("max", 1)
                .put("default", 0);
        duplicateGenes.addObject().put("name", "x").put("type", "continuous").put("min", 0).put("max", 1);
        assertThatThrownBy(() -> StrategyStatisticalV5.enumerateDirectNeighbours(duplicate, object()))
                .hasMessage("duplicate gene x");
        ObjectNode structural = object(); structural.putArray("genes").addObject()
                .put("name", "mode").put("type", "structural").putArray("values").add("invalid");
        assertThatThrownBy(() -> StrategyStatisticalV5.enumerateDirectNeighbours(structural, object()))
                .hasMessage("mode.structural values must be objects");
    }

    @Test
    void evaluationPhasesNormalizeCutoffsIntentFormsAndEffectiveBehavior() {
        for (String phase : List.of("TRAIN_ONLY", "TRAIN_CONFIRMATION", "SEARCH")) {
            ObjectNode request = evaluationRequest(phase);
            ObjectNode result = StrategyStatisticalV5.makeEvaluationArtifact(request);
            assertThat(result.path("phase").asText()).isEqualTo(phase);
            assertThat(result.path("weighting").asText())
                    .isEqualTo(phase.startsWith("TRAIN") ? "TRAIN_HALF_LIFE" : "UNWEIGHTED_OOS");
            assertThat(result.path("fit_cutoff").asText()).isEqualTo("2026-02-01T00:00:00Z");
            assertThat(result.path("evaluation_cutoff").asText()).isEqualTo("2026-02-01T00:00:00Z");
        }

        ObjectNode inner = evaluationRequest("INNER_VALIDATION");
        inner.put("fitCutoff", "2026-01-01T00:00:00Z").put("cutoff", "2026-02-01T00:00:00Z");
        inner.put("weighting", "UNWEIGHTED_VALIDATION");
        ObjectNode innerResult = StrategyStatisticalV5.makeEvaluationArtifact(inner);
        assertThat(innerResult.path("weighting").asText()).isEqualTo("UNWEIGHTED_VALIDATION");
        assertThat(innerResult.path("fit_cutoff").asText()).isEqualTo("2026-01-01T00:00:00Z");

        ObjectNode outer = evaluationRequest("OUTER_OOS"); outer.putNull("cutoff");
        ObjectNode outerResult = StrategyStatisticalV5.makeEvaluationArtifact(outer);
        assertThat(outerResult.path("fit_cutoff").isNull()).isTrue();
        assertThat(outerResult.path("evaluation_cutoff").isNull()).isTrue();
        assertThat(outerResult.path("weighting").asText()).isEqualTo("UNWEIGHTED_OOS");

        ObjectNode mapIntent = evaluationRequest("SEARCH");
        mapIntent.putObject("signalIntentVector").put("e1", true).put("e2", 0.25);
        ObjectNode supplied = mapIntent.putObject("behaviorContracts");
        for (String key : List.of("signal_semantics_sha256", "evaluator_sha256", "predictor_sha256", "lifecycle_sha256")) {
            supplied.put(key, JsonHashes.sha256("contract-" + key));
        }
        ObjectNode mapResult = StrategyStatisticalV5.makeEvaluationArtifact(mapIntent);
        assertThat(mapResult.path("signal_intent_vector").get(0).path("intent").asBoolean()).isTrue();
        assertThat(mapResult.path("signal_intent_vector").get(1).path("intent").asDouble()).isEqualTo(.25);

        ObjectNode definition = object().put("direction", "long").put("active", true)
                .put("unused_diagnostic", "ignored");
        definition.putObject("risk").put("used_for_execution", false);
        JsonNode effective = StrategyStatisticalV5.effectiveExecutionBehavior(definition);
        assertThat(effective.path("unused_diagnostic").isMissingNode()).isTrue();
        assertThat(effective.path("risk").isMissingNode()).isTrue();
        assertThat(StrategyStatisticalV5.effectiveExecutionBehavior(null).isNull()).isTrue();

        ObjectNode invalidInner = evaluationRequest("INNER_VALIDATION");
        invalidInner.put("fitCutoff", "2026-02-01T00:00:00Z").put("cutoff", "2026-02-01T00:00:00Z");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(invalidInner))
                .hasMessage("inner validation requires a later evaluation cutoff than its immutable fit cutoff");
        ObjectNode badIntent = evaluationRequest("SEARCH"); badIntent.put("signalIntentVector", 4);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(badIntent))
                .hasMessage("signal intent vector must cover the evaluation scope");
    }

    @Test
    void pboCpcvHandlesTimestampAndEpisodePanelsWithBoundaryOutcomes() {
        ArrayNode folds = MAPPER.createArrayNode();
        for (int index = 0; index < 4; index++) {
            ObjectNode fold = folds.addObject();
            fold.putObject("candidate_means").put("a", index % 2 == 0 ? .4 : -.2)
                    .put("b", index % 2 == 0 ? .1 : .2);
            fold.put("test_start", "2026-0" + (index + 1) + "-01T00:00:00Z");
            fold.put("test_end", "2026-0" + (index + 1) + "-02T00:00:00Z");
        }
        ObjectNode timestampOptions = object().put("purgeDays", 0).put("embargoDays", 0)
                .put("requireTimestamps", true);
        ObjectNode timestamp = StrategyStatisticalV5.pboFromFolds(folds, "a", timestampOptions);
        assertThat(timestamp.path("combinations_total").asInt()).isEqualTo(6);
        assertThat(timestamp.path("valid_combinations").asInt()).isEqualTo(6);
        assertThat(timestamp.path("purge_ms").asLong()).isZero();
        assertThat(timestamp.path("embargo_ms").asLong()).isZero();

        ArrayNode observed = MAPPER.createArrayNode();
        for (int index = 0; index < 4; index++) {
            ObjectNode fold = observed.addObject(); ObjectNode row = fold.putArray("observations").addObject();
            row.put("episode_id", "obs-" + index).put("decision_time", "2026-0" + (index + 1)
                    + "-01T00:00:00Z");
            if (index != 0) row.put("resolution_time", "2026-0" + (index + 1) + "-02T00:00:00Z");
            row.putObject("candidate_means").put("a", index < 2 ? .4 : -.2).put("b", index < 2 ? .1 : .2);
        }
        ObjectNode episode = StrategyStatisticalV5.pboFromFolds(observed, "a",
                object().put("purgeDays", 0).put("embargoDays", 0));
        assertThat(episode.path("method").asText()).isEqualTo("EPISODE_LEVEL_PURGED_CPCV_TRAIN_WINNER_TEST_RANK_LOGIT");
        assertThat(episode.path("valid_combinations").asInt()).isEqualTo(6);

        ArrayNode noTimes = folds.deepCopy(); noTimes.forEach(row -> {
            ((ObjectNode) row).remove("test_start"); ((ObjectNode) row).remove("test_end");
        });
        ObjectNode degraded = StrategyStatisticalV5.pboFromFolds(noTimes, "a", object());
        assertThat(degraded.path("valid_combinations").asInt()).isEqualTo(6);
        assertThat(StrategyStatisticalV5.pboFromFolds(folds, "missing", timestampOptions)
                .path("pbo").isNull()).isTrue();
        ArrayNode tooFew = folds.deepCopy(); tooFew.remove(3);
        assertThat(StrategyStatisticalV5.pboFromFolds(tooFew, "a", timestampOptions)).isNull();

        ArrayNode overlap = folds.deepCopy(); ((ObjectNode) overlap.get(1)).put("test_start", "2026-01-01T12:00:00Z");
        assertThatThrownBy(() -> StrategyStatisticalV5.pboFromFolds(overlap, "a", timestampOptions))
                .hasMessage("PBO fold test intervals overlap");
    }

    @Test
    void stressPortfolioAggregateAndSelectionPoliciesBindInputs() {
        ObjectNode stress = object().put("lineage_sha256", JsonHashes.sha256("stress-lineage"))
                .put("sourceArtifactSha256", JsonHashes.sha256("stress-source"))
                .put("selectedCandidateId", "candidate-1").put("pass", false);
        ObjectNode generated = StrategyStatisticalV5.makeStressDecision(stress);
        assertThat(generated.path("scenarios")).hasSize(13);
        assertThat(generated.path("pass").asBoolean()).isFalse();
        assertThat(generated.path("scenario_inventory_sha256").asText())
                .isEqualTo(StrategyStatisticalV5.hash(generated.path("scenarios")));

        ArrayNode custom = MAPPER.createArrayNode();
        for (String id : List.of("DOUBLED_COST", "DELAYED_ENTRY", "ADVERSE_COLLISION", "GAP", "LIQUIDITY",
                "CAPACITY", "OUTAGE", "FUNDING", "EXPIRY", "LIQUIDATION", "LEAVE_ONE_ASSET",
                "LEAVE_ONE_REGIME", "LEAVE_ONE_CONTEXT")) custom.addObject().put("id", id).put("pass", true)
                .put("digest", JsonHashes.sha256("custom-stress-" + id));
        ObjectNode customRequest = stress.deepCopy(); customRequest.set("scenarios", custom); customRequest.put("pass", true);
        assertThat(StrategyStatisticalV5.makeStressDecision(customRequest).path("pass").asBoolean()).isTrue();
        ObjectNode incomplete = customRequest.deepCopy(); incomplete.withArray("scenarios").remove(0);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStressDecision(incomplete))
                .hasMessage("stress scenario inventory is incomplete");

        ObjectNode artifact = artifact(); ObjectNode portfolio = object().put("lineage_sha256", JsonHashes.sha256("portfolio-lineage"))
                .put("pass", true).set("artifact", artifact);
        portfolio.putArray("assetDecisions").addObject().put("asset", "btc").put("pass", true);
        portfolio.putArray("returnIncrements").addObject().put("episode_id", "e1").put("asset", "btc").put("net_r", .2);
        portfolio.put("riskDigest", JsonHashes.sha256("declared-risk"));
        ObjectNode portfolioResult = StrategyStatisticalV5.makePortfolioDecision(portfolio);
        assertThat(portfolioResult.path("source_artifact_sha256").asText()).isEqualTo(artifact.path("content_sha256").asText());
        assertThat(portfolioResult.path("risk_digest_sha256").asText()).isEqualTo(JsonHashes.sha256("declared-risk"));
        assertThatThrownBy(() -> StrategyStatisticalV5.makePortfolioDecision(object()))
                .hasMessage("portfolio decision requires recomputed asset decisions and aligned return increments");

        ArrayNode noEvidence = MAPPER.createArrayNode().add(assetFold("2026-01-01T00:00:00Z", 0, false, true));
        ObjectNode aggregate = StrategyStatisticalV5.aggregateAssetDecision(noEvidence);
        assertThat(aggregate.path("asset_gates").path("procedure_validation").asBoolean()).isTrue();
        assertThat(aggregate.path("asset_gates").path("pbo").asBoolean()).isTrue();
        assertThat(aggregate.path("asset_gates").path("recent_oos_positive").asBoolean()).isFalse();
        assertThat(aggregate.path("metrics").path("traded_count").asInt()).isZero();
    }

    @Test
    void hardFeasibilityAndDominanceCoverExactThresholdsAndViolations() {
        ObjectNode policy = object().put("minEpisodes", 1).put("minExpectancy", 0)
                .put("minProfitFactor", 1).put("maxDrawdownR", 2).put("maxCostR", .1)
                .put("minCoverage", .95).put("requireCapacityPass", true);
        ObjectNode scales = policy.putObject("violation_scales");
        for (String key : List.of("episodes", "expectancy", "drawdown", "costs", "coverage", "capacity", "profit_factor")) {
            scales.put(key, 1);
        }
        ObjectNode exact = object().put("traded_count", 1).put("expectancy_r", .01).put("cost_r", .1)
                .put("coverage_fraction", .95).put("capacity_pass", true).put("max_drawdown_r", -2)
                .put("profit_factor", 1);
        ObjectNode feasible = StrategyStatisticalV5.hardFeasible(exact, policy);
        assertThat(feasible.path("feasible").asBoolean()).isTrue();
        assertThat(feasible.path("violations")).isEmpty();

        ObjectNode broken = exact.deepCopy().put("traded_count", 0).put("expectancy_r", 0)
                .put("cost_r", .2).put("coverage_fraction", .8).put("capacity_pass", false)
                .put("max_drawdown_r", -3).put("profit_factor", .5);
        ObjectNode infeasible = StrategyStatisticalV5.hardFeasible(broken, policy);
        assertThat(infeasible.path("feasible").asBoolean()).isFalse();
        List<String> violations = new ArrayList<>();
        infeasible.path("violations").forEach(value -> violations.add(value.asText()));
        assertThat(violations).containsExactlyInAnyOrder("EPISODES", "EXPECTANCY", "DRAWDOWN", "COSTS",
                "COVERAGE", "CAPACITY", "PROFIT_FACTOR");
        assertThat(infeasible.path("total_violation").asDouble()).isEqualTo(3.75);
        assertThat(StrategyStatisticalV5.constrainedDominates(feasible, infeasible)).isTrue();
        assertThat(StrategyStatisticalV5.constrainedDominates(infeasible, feasible)).isFalse();
        assertThat(StrategyStatisticalV5.constrainedDominates(infeasible, infeasible)).isFalse();
    }

    @Test
    void checkpointDefaultsRoundTripAndRejectStaleBindings() {
        Bundle bundle = bundle(); ObjectNode config = object().put("population", 2).put("generations", 1)
                .put("minGenerations", 1).put("plateauGenerations", 1).put("crossoverProbability", .9)
                .putNull("mutationProbability").put("halfLifeMonths", 18)
                .put("operator", "NSGA_II_TYPED").put("scheduler_ordering", "SEED_GENERATION_ORDINAL")
                .put("mode", "FIXTURE");
        config.putArray("seeds").add(11).add(23).add(47);
        ObjectNode request = object(); request.set("exposureHead", bundle.head()); request.set("artifact", bundle.artifact());
        request.set("geneSpace", bundle.geneSpace()); request.put("seed", 11).put("generation", 0).put("foldId", "outer-1");
        request.set("config", config); request.putArray("population"); request.putArray("history");
        ObjectNode checkpoint = StrategyStatisticalV5.makeGeneticCheckpoint(request);
        assertThat(checkpoint.path("schema").asText()).isEqualTo(StrategyStatisticalV5.STAT_SCHEMA.get("checkpoint"));
        assertThat(checkpoint.path("seed_index").asInt()).isZero();
        assertThat(checkpoint.path("rng_state").isNull()).isTrue();
        assertThat(StrategyStatisticalV5.validateGeneticCheckpoint(checkpoint, request)).isTrue();

        ObjectNode stale = request.deepCopy();
        ObjectNode changed = bundle.artifact().deepCopy();
        ((ObjectNode) changed.path("episodes").get(0)).put("eligible", false);
        stale.set("artifact", StrategyStatisticalV5.withHash(changed));
        assertThatThrownBy(() -> StrategyStatisticalV5.validateGeneticCheckpoint(checkpoint, stale))
                .hasMessage("checkpoint artifact lineage mismatch");
        ObjectNode badSeed = request.deepCopy().put("seed", 1.5);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeGeneticCheckpoint(badSeed))
                .hasMessage("checkpoint seed/generation is invalid");
    }

    private static ObjectNode evaluationRequest(String phase) {
        ObjectNode signal = object().put("schema", "strategy-v5-statistical-signal-view/1")
                .put("source_artifact_sha256", JsonHashes.sha256("evaluation-source"));
        signal.putArray("episodes").addObject().put("episode_id", "e1").put("asset", "btc");
        signal.withArray("episodes").addObject().put("episode_id", "e2").put("asset", "eth");
        ObjectNode value = object(); value.set("signalArtifact", signal); value.putArray("episodeIds").add("e1").add("e2");
        value.putObject("candidateReturns").putObject("e1").put("net_r", .2).put("traded", true);
        ((ObjectNode) value.path("candidateReturns")).putObject("e2").put("net_r", 0).put("traded", false);
        value.putObject("metrics").put("cost_r", .01).put("coverage_fraction", 1).put("capacity_pass", true);
        value.put("phase", phase).put("foldId", "fold-1");
        if ("OUTER_OOS".equals(phase)) value.putNull("cutoff"); else value.put("cutoff", "2026-02-01T00:00:00Z");
        ArrayNode intent = value.putArray("signalIntentVector");
        intent.addObject().put("episode_id", "e1").put("intent", true);
        intent.addObject().put("episode_id", "e2").put("intent", .25);
        return value;
    }

    private static ObjectNode assetFold(String decision, double net, boolean traded, boolean stressPass) {
        ObjectNode row = object().put("fold_id", "fold-1");
        row.putObject("metrics").put("expectancy_r", net).put("cost_r", .01).put("coverage_fraction", 1)
                .put("capacity_pass", true).put("max_drawdown_r", 0).put("profit_factor", traded ? 2 : 0)
                .put("traded_count", traded ? 1 : 0);
        row.putObject("stress").put("pass", stressPass);
        row.putArray("selected_return_vector").addObject().put("episode_id", "e1").put("asset", "btc")
                .put("decision_time", decision).put("resolution_time", decision.replace("T00:00:00Z", "T00:01:00Z"))
                .put("net_r", net).put("traded", traded).put("eligible", true);
        return StrategyStatisticalV5.withHash(row);
    }

    private static Bundle bundle() {
        String behavior = JsonHashes.sha256("checkpoint-behavior");
        ObjectNode headOptions = object().put("hypothesisFamily", "checkpoint").put("datasetSha256", DATASET);
        headOptions.putArray("entries").addObject().put("behavior_sha256", behavior).put("dataset_sha256", DATASET);
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headOptions);
        ObjectNode args = object().set("exposureHead", head);
        ObjectNode lineage = args.putObject("lineage").put("dataset_sha256", DATASET);
        for (String key : List.of("candidate_set_sha256", "feature_set_sha256", "label_set_sha256", "execution_set_sha256")) {
            lineage.put(key, JsonHashes.sha256("checkpoint-" + key));
        }
        args.putArray("candidates").addObject().put("candidate_id", "candidate-1").put("behavior_sha256", behavior);
        args.putArray("episodes").addObject().put("episode_id", "e1").put("asset", "btc")
                .put("decision_time", "2026-01-01T00:00:00Z").put("resolution_time", "2026-01-02T00:00:00Z")
                .put("eligible", true).putObject("candidate_returns").putObject("candidate-1").put("net_r", .2).put("traded", true);
        args.withArray("episodes").addObject().put("episode_id", "e2").put("asset", "eth")
                .put("decision_time", "2026-01-03T00:00:00Z").put("resolution_time", "2026-01-04T00:00:00Z")
                .put("eligible", true).putObject("candidate_returns").putObject("candidate-1").put("net_r", 0).put("traded", false);
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(args);
        ObjectNode geneSpace = object(); geneSpace.putArray("genes").addObject().put("name", "threshold")
                .put("type", "continuous").put("min", 0).put("max", 1).put("step", .5).put("default", .5);
        return new Bundle(head, artifact, geneSpace);
    }

    private record Bundle(ObjectNode head, ObjectNode artifact, ObjectNode geneSpace) {
        @Override public ObjectNode head() { return head.deepCopy(); }
        @Override public ObjectNode artifact() { return artifact.deepCopy(); }
        @Override public ObjectNode geneSpace() { return geneSpace.deepCopy(); }
    }

    private static ObjectNode artifact() { return bundle().artifact(); }
    private static ObjectNode object() { return MAPPER.createObjectNode(); }
}
