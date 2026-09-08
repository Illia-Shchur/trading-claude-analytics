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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Deterministic SHADOW WFO contract fixtures and fail-closed invariant checks. */
final class StrategyStatisticalV5ShadowValidationTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final String ALIAS = JsonHashes.sha256("shadow-wfo-behavior");
    private static final String DATASET = JsonHashes.sha256("shadow-wfo-dataset");

    @Test
    void validShadowWfoBindsAllOuterFoldAndFinalEvidence() {
        ObjectNode wfo = shadowWfo();
        assertThat(wfo.path("decision").asText()).isEqualTo("SHADOW");
        assertThat(wfo.path("gate_pass").asBoolean()).isTrue();
        assertThat(wfo.path("folds")).hasSize(8);
        assertThat(wfo.path("oos_episode_ids")).isNotEmpty();
        assertThat(StrategyStatisticalV5.validateNestedWfoArtifact(wfo)).isTrue();
        assertThat(StrategyStatisticalV5.validateContractSchema(wfo)).isTrue();
    }

    @Test
    void shadowWfoRejectsTopLevelInventoryAndDecisionContradictions() {
        ObjectNode missingOos = shadowWfo().deepCopy();
        missingOos.putArray("oos_episode_ids");
        assertFailure(missingOos, "nested WFO SHADOW OOS episode inventory is empty or duplicated");

        ObjectNode duplicateOos = shadowWfo().deepCopy();
        duplicateOos.withArray("oos_episode_ids").add(duplicateOos.path("oos_episode_ids").get(0));
        assertFailure(duplicateOos, "nested WFO SHADOW OOS episode inventory is empty or duplicated");

        ObjectNode wrongDecision = shadowWfo().deepCopy().put("decision", "REJECTED");
        assertFailure(wrongDecision, "nested WFO decision is not fail-closed");

        ObjectNode wrongWeighting = shadowWfo().deepCopy().put("oos_weighting", "TRAIN_HALF_LIFE");
        assertFailure(wrongWeighting, "nested WFO cumulative/search weighting contract is invalid");

        ObjectNode missingFinalAssets = shadowWfo().deepCopy();
        missingFinalAssets.putArray("asset_decisions_final");
        assertFailure(missingFinalAssets,
                "nested WFO SHADOW asset decision inventory is empty, incomplete, or not authoritative");

        ObjectNode missingOuterAssets = shadowWfo().deepCopy();
        missingOuterAssets.putArray("asset_decisions");
        assertFailure(missingOuterAssets, "nested WFO SHADOW outer asset-decision inventory is missing or tampered");

        ObjectNode missingPortfolio = shadowWfo().deepCopy().putNull("portfolio_decision");
        assertFailure(missingPortfolio, "nested WFO SHADOW portfolio decision is not authoritative:");

        ObjectNode badAudit = shadowWfo().deepCopy();
        ((ObjectNode) badAudit.get("audit")).put("pass", false);
        badAudit.set("audit", StrategyStatisticalV5.withHash((ObjectNode) badAudit.get("audit")));
        ObjectNode reboundRefit = (ObjectNode) badAudit.get("development_refit");
        reboundRefit.put("validation_audit_sha256", badAudit.path("audit").path("content_sha256").asText());
        badAudit.set("development_refit", StrategyStatisticalV5.withHash(reboundRefit));
        assertFailure(badAudit, "nested WFO SHADOW audit is not semantically passing");

        ObjectNode badRefit = shadowWfo().deepCopy();
        ((ObjectNode) badRefit.get("development_refit")).put("status", "REJECTED");
        badRefit.set("development_refit", StrategyStatisticalV5.withHash((ObjectNode) badRefit.get("development_refit")));
        assertFailure(badRefit, "nested WFO SHADOW development refit inventory is incomplete or not selected for prospective shadow");
    }

    @Test
    void shadowWfoRejectsOuterFoldInventoryAndRetentionMismatches() {
        ObjectNode badStatus = mutateFold(shadowWfo(), 0, fold -> fold.put("status", "SKIPPED"));
        assertFailure(badStatus, "nested WFO SHADOW fold inventory is incomplete, overlapping, or not purged/embargoed");

        ObjectNode emptyTrain = mutateFold(shadowWfo(), 0, fold -> fold.putArray("train_episode_ids"));
        assertFailure(emptyTrain, "nested WFO SHADOW fold inventory is incomplete, overlapping, or not purged/embargoed");

        ObjectNode overlapping = mutateFold(shadowWfo(), 0, fold -> {
            ArrayNode test = fold.withArray("test_episode_ids");
            test.removeAll();
            test.add(fold.path("train_episode_ids").get(0));
        });
        assertFailure(overlapping, "nested WFO SHADOW fold inventory is incomplete, overlapping, or not purged/embargoed");

        ObjectNode badPurge = mutateFold(shadowWfo(), 0, fold -> fold.put("purge_ms", 0));
        assertFailure(badPurge, "nested WFO SHADOW fold inventory is incomplete, overlapping, or not purged/embargoed");

        ObjectNode weightedTest = mutateFold(shadowWfo(), 0,
                fold -> ((ObjectNode) fold.path("test")).put("weighted_recency", true));
        assertFailure(weightedTest, "nested WFO SHADOW fold test is weighted or lacks its OOS vector binding");

        ObjectNode missingTestVector = mutateFold(shadowWfo(), 0,
                fold -> ((ObjectNode) fold.path("test")).remove("vector_inventory_sha256"));
        assertFailure(missingTestVector, "nested WFO SHADOW fold test is weighted or lacks its OOS vector binding");

        ObjectNode oosMismatch = shadowWfo().deepCopy();
        oosMismatch.withArray("oos_episode_ids").set(0, "outside-oos");
        assertFailure(oosMismatch, "nested WFO SHADOW OOS episode inventory does not equal the retained outer test inventory");

        ObjectNode finalAssetPass = shadowWfo().deepCopy();
        ObjectNode finalAsset = (ObjectNode) finalAssetPass.withArray("asset_decisions_final").get(0);
        finalAsset.put("pass", false);
        finalAssetPass.withArray("asset_decisions_final").set(0, StrategyStatisticalV5.withHash(finalAsset));
        assertFailure(finalAssetPass,
                "nested WFO SHADOW asset decision inventory is empty, incomplete, or not authoritative");
    }

    @Test
    void shadowFoldRejectsVectorPortfolioDecisionAndReturnBindingBreaks() {
        ObjectNode badAssets = mutateOuter(shadowWfo(), 0, outer -> outer.putObject("asset_decisions"));
        assertFailure(badAssets, "nested WFO SHADOW fold outer-1 asset inventory is incomplete");

        ObjectNode badVector = mutateOuter(shadowWfo(), 0, outer -> outer.with("vector").put("content_sha256", hash("wrong-vector")));
        assertFailure(badVector, "nested WFO SHADOW fold outer-1 vector inventory is not exactly bound");

        ObjectNode badPortfolio = mutateOuter(shadowWfo(), 0, outer -> outer.with("portfolio").put("provenance", "FIXTURE"));
        assertFailure(badPortfolio, "nested WFO SHADOW fold outer-1 portfolio is not exactly bound");

        ObjectNode missingReturns = mutateOuter(shadowWfo(), 0, outer -> {
            ObjectNode decisions = (ObjectNode) outer.path("asset_decisions");
            ObjectNode decision = (ObjectNode) decisions.path("btc");
            decision.putArray("selected_return_vector");
            decisions.set("btc", StrategyStatisticalV5.withHash(decision));
            outer.set("asset_decisions", decisions);
        });
        assertFailure(missingReturns, "nested WFO SHADOW fold outer-1 return inventory does not cover its exact test episode set");

        ObjectNode unknownReturn = mutateOuter(shadowWfo(), 0, outer -> {
            ObjectNode decisions = (ObjectNode) outer.path("asset_decisions");
            ObjectNode decision = (ObjectNode) decisions.path("btc");
            ObjectNode row = (ObjectNode) decision.withArray("selected_return_vector").get(0);
            row.put("episode_id", "unknown-episode");
            decisions.set("btc", StrategyStatisticalV5.withHash(decision));
            outer.set("asset_decisions", decisions);
        });
        assertFailure(unknownReturn, "nested WFO SHADOW fold outer-1/btc return inventory is incomplete or cross-boundary");

        ObjectNode selectedAbsent = mutateOuter(shadowWfo(), 0, outer -> {
            ObjectNode decisions = (ObjectNode) outer.path("asset_decisions");
            ObjectNode decision = (ObjectNode) decisions.path("btc");
            decision.put("selected_behavior_alias_sha256", hash("missing-alias"));
            decisions.set("btc", StrategyStatisticalV5.withHash(decision));
            outer.set("asset_decisions", decisions);
        });
        assertFailure(selectedAbsent, "selected behavior is absent from its vector inventory");

        ObjectNode stressMismatch = mutateOuter(shadowWfo(), 0, outer -> {
            ObjectNode decisions = (ObjectNode) outer.path("asset_decisions");
            ObjectNode decision = (ObjectNode) decisions.path("btc");
            ObjectNode stress = (ObjectNode) decision.path("stress");
            stress.put("selected_candidate_id", hash("other-selected"));
            decisions.set("btc", StrategyStatisticalV5.withHash(decision));
            outer.set("asset_decisions", decisions);
        });
        assertFailure(stressMismatch, "stress is not bound to its selected decision");

        ObjectNode duplicateReturns = mutateOuter(shadowWfo(), 0, outer -> {
            ObjectNode decisions = (ObjectNode) outer.path("asset_decisions");
            ObjectNode decision = (ObjectNode) decisions.path("btc");
            ArrayNode returns = decision.withArray("selected_return_vector");
            returns.add(returns.get(0));
            decisions.set("btc", StrategyStatisticalV5.withHash(decision));
            outer.set("asset_decisions", decisions);
        });
        assertFailure(duplicateReturns, "return inventory is incomplete or cross-boundary");
    }

    private static ObjectNode shadowWfo() {
        return ShadowHolder.VALUE.deepCopy();
    }

    private static final class ShadowHolder {
        private static final ObjectNode VALUE = buildShadowWfo();
    }

    private static ObjectNode buildShadowWfo() {
        ObjectNode headArgs = object().put("hypothesisFamily", "shadow-wfo-family").put("datasetSha256", DATASET);
        headArgs.putArray("entries").addObject().put("behavior_sha256", ALIAS).put("dataset_sha256", DATASET);
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
        ObjectNode artifactArgs = object();
        ObjectNode lineage = artifactArgs.putObject("lineage").put("dataset_sha256", DATASET);
        for (String key : List.of("candidate_set_sha256", "feature_set_sha256", "label_set_sha256", "execution_set_sha256")) {
            lineage.put(key, hash("shadow-" + key));
        }
        artifactArgs.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", ALIAS);
        artifactArgs.set("exposureHead", head);
        ArrayNode episodes = artifactArgs.putArray("episodes");
        ZonedDateTime start = ZonedDateTime.parse("2022-01-01T00:00:00Z");
        for (int index = 0; index < 48; index++) {
            Instant decision = start.plusMonths(index).toInstant();
            Instant resolution = start.plusMonths(index).plusDays(1).toInstant();
            ObjectNode row = episodes.addObject();
            row.put("episode_id", String.format("w%02d", index + 1)).put("asset", "btc")
                    .put("decision_time", decision.toString()).put("resolution_time", resolution.toString())
                    .put("eligible", true);
            row.putObject("candidate_returns").putObject("candidate-1").put("net_r", valueForIndex(index))
                    .put("traded", true);
        }
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactArgs);
        ObjectNode options = object().set("artifact", artifact); options.set("exposureHead", head);
        options.put("mode", "FIXTURE").put("endAt", "2026-01-01T00:00:00Z");
        ObjectNode space = options.putObject("geneSpace");
        // An ordered discrete control gives the public confirmation stage
        // real direct neighbours (categorical genes intentionally have none).
        ObjectNode holdingPeriod = space.putArray("genes").addObject().put("name", "holding_period")
                .put("type", "ordered-discrete").put("default", 2);
        holdingPeriod.putArray("values").add(1).add(2).add(3);
        ObjectNode constraints = options.putObject("constraints").put("minEpisodes", 1)
                .put("minExpectancy", -10).put("minProfitFactor", 0).put("maxDrawdownR", 100)
                .put("maxCostR", 10).put("minCoverage", 0).put("requireCapacityPass", true);
        ObjectNode config = options.putObject("config").put("population", 2).put("generations", 1)
                .put("minGenerations", 1).put("plateauGenerations", 1).put("crossoverProbability", .9)
                .put("mutationProbability", 0).put("halfLifeMonths", 18).put("bootstrapIterations", 8)
                .put("maxStatIterations", 16).put("seed", 11).put("minPositiveFolds", 1)
                .put("minPositiveYears", 1).put("minTradesPerYear", 1).put("minPlateau", 1)
                .put("minNeighbourFraction", 0).put("minSeedCount", 1).put("minEpisodes", 1)
                .put("prospectiveCutoff", "2026-01-01T00:00:00Z");
        config.set("constraints", constraints.deepCopy());
        config.putArray("seeds").add(11).add(23).add(47);
        config.put("nullIterations", 32).put("nullSequentialBatchSize", 8);
        config.set("selectionBudget", selectionBudget());
        ObjectNode result = StrategyStatisticalV5.runNestedWfoV5(options, shadowEvaluator(), shadowStressProvider(),
                shadowPortfolioProvider(), shadowVectorProvider(), shadowReplaySuite(), null);
        return ((ObjectNode) result.path("run")).deepCopy();
    }

    private static StrategyEvaluatorV5.Evaluator shadowEvaluator() {
        return task -> {
            ObjectNode result = object(); ObjectNode returns = result.putObject("candidate_returns");
            int index = 0;
            for (JsonNode id : task.path("episode_ids")) {
                returns.putObject(id.asText()).put("net_r", valueForEpisode(id.asText())).put("traded", true);
                index++;
            }
            result.putObject("metrics").put("cost_r", .01).put("coverage_fraction", 1)
                    .put("capacity_pass", true).put("max_drawdown_r", -.1).put("profit_factor", 2)
                    .put("turnover", 1).put("complexity", 1);
            result.putObject("required").put("bootstrapIterations", 8).put("seed", 11);
            return result;
        };
    }

    private static StrategyStatisticalV5.StatisticalProvider shadowStressProvider() {
        return task -> StrategyStatisticalV5.makeStressDecision(object()
                .put("lineage_sha256", task.path("lineage_sha256").asText()).put("pass", true)
                .put("sourceArtifactSha256", task.path("artifact").path("content_sha256").asText())
                .put("selectedCandidateId", task.path("selected_candidate_id").asText()));
    }

    private static StrategyStatisticalV5.StatisticalProvider shadowPortfolioProvider() {
        return task -> {
            ObjectNode args = object().put("lineage_sha256", task.path("lineage_sha256").asText())
                    .put("pass", true).put("sourceArtifactSha256", task.path("artifact").path("content_sha256").asText());
            args.set("assetDecisions", task.path("asset_decisions"));
            ArrayNode increments = args.putArray("returnIncrements");
            for (JsonNode decision : task.path("asset_decisions")) for (JsonNode row : decision.path("selected_return_vector")) {
                if (row.path("traded").asBoolean(false)) increments.addObject().put("episode_id", row.path("episode_id").asText())
                        .put("asset", row.path("asset").asText()).put("net_r", row.path("net_r").asDouble());
            }
            return StrategyStatisticalV5.makePortfolioDecision(args);
        };
    }

    private static StrategyStatisticalV5.StatisticalProvider shadowVectorProvider() {
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

    private static StrategyStatisticalV5.NullReplaySuite shadowReplaySuite() {
        Map<String, StrategyStatisticalV5.NullReplayMethod> methods = new LinkedHashMap<>();
        for (String method : List.of("block_permuted_labels", "timestamp_shifted_outcomes",
                "frequency_matched_random_intents", "winners_curse_selection")) {
            methods.put(method, args -> {
                ObjectNode artifact = ((ObjectNode) args.path("artifact")).deepCopy();
                String selected = args.path("selected_candidate_id").asText();
                for (JsonNode episode : artifact.path("episodes")) {
                    JsonNode returnRow = episode.path("candidate_returns").path(selected);
                    if (returnRow.isObject()) ((ObjectNode) returnRow).put("net_r", 0).put("traded", false);
                }
                return StrategyStatisticalV5.withHash(artifact);
            });
        }
        return new StrategyStatisticalV5.NullReplaySuite(methods);
    }

    private static ObjectNode selectionBudget() {
        ObjectNode value = object().put("population", 2).put("generations", 1);
        value.putArray("seeds").add(11).add(23).add(47); return value;
    }

    private interface FoldMutation { void apply(ObjectNode fold); }
    private interface OuterMutation { void apply(ObjectNode outer); }

    private static ObjectNode mutateFold(ObjectNode original, int index, FoldMutation mutation) {
        ObjectNode copy = original.deepCopy(); ArrayNode folds = copy.withArray("folds");
        ObjectNode fold = ((ObjectNode) folds.get(index)).deepCopy(); mutation.apply(fold);
        folds.set(index, StrategyStatisticalV5.withHash(fold)); return StrategyStatisticalV5.withHash(copy);
    }

    private static ObjectNode mutateOuter(ObjectNode original, int index, OuterMutation mutation) {
        ObjectNode copy = original.deepCopy(); ArrayNode outers = copy.withArray("asset_decisions");
        ObjectNode outer = ((ObjectNode) outers.get(index)).deepCopy(); mutation.apply(outer);
        outers.set(index, outer); return StrategyStatisticalV5.withHash(copy);
    }

    private static void assertFailure(ObjectNode value, String message) {
        ObjectNode canonical = StrategyStatisticalV5.withHash(value);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateNestedWfoArtifact(canonical))
                .hasMessageContaining(message);
    }

    private static double valueForIndex(int index) {
        return List.of(.12, .42, .19, .33, .15, .37, .24, .29).get(index % 8);
    }

    private static double valueForEpisode(String id) {
        if (id.length() < 2) return .2;
        try { return valueForIndex(Integer.parseInt(id.substring(1)) - 1); }
        catch (NumberFormatException ignored) { return .2; }
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static String hash(String value) { return JsonHashes.sha256(value); }
}
