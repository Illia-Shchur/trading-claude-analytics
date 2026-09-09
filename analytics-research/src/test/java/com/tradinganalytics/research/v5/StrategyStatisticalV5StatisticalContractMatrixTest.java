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

/** Deterministic public statistical contract boundaries and arithmetic invariants. */
final class StrategyStatisticalV5StatisticalContractMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @Test
    void evaluationRejectsMissingMalformedAndOutOfScopeInputs() {
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(null))
                .hasMessage("evaluation artifact requires a signal view, scope, returns, and metrics");

        ObjectNode wrongSchema = evaluation("SEARCH");
        wrongSchema.with("signalArtifact").put("schema", "strategy-v5-other-view/1");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(wrongSchema))
                .hasMessage("evaluation artifact requires a signal view, scope, returns, and metrics");

        ObjectNode missingScope = evaluation("SEARCH");
        missingScope.remove("episodeIds");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(missingScope))
                .hasMessage("evaluation artifact requires a signal view, scope, returns, and metrics");

        ObjectNode missingReturns = evaluation("SEARCH");
        missingReturns.remove("candidateReturns");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(missingReturns))
                .hasMessage("evaluation artifact requires a signal view, scope, returns, and metrics");

        ObjectNode outside = evaluation("SEARCH");
        outside.withArray("episodeIds").set(1, "unknown");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(outside))
                .hasMessage("evaluation scope is outside the signal view");

        ObjectNode duplicateScope = evaluation("SEARCH");
        duplicateScope.withArray("episodeIds").set(1, "e1");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(duplicateScope))
                .hasMessage("evaluation scope is outside the signal view");
    }

    @Test
    void evaluationBindsExplicitAliasAndRejectsPhaseCutoffWeightingDrift() {
        ObjectNode valid = evaluation("SEARCH");
        ObjectNode produced = StrategyStatisticalV5.makeEvaluationArtifact(valid);
        assertThat(produced.path("phase").asText()).isEqualTo("SEARCH");
        assertThat(produced.path("fit_cutoff").asText()).isEqualTo("2026-02-01T00:00:00Z");
        assertThat(produced.path("evaluation_cutoff").asText()).isEqualTo("2026-02-01T00:00:00Z");
        assertThat(produced.path("weighting").asText()).isEqualTo("UNWEIGHTED_OOS");

        ObjectNode aliasMismatch = valid.deepCopy().put("behaviorAliasSha256", JsonHashes.sha256("wrong-alias"));
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(aliasMismatch))
                .hasMessage("behavior alias does not match the frozen semantic contracts");

        ObjectNode badOuterWeight = evaluation("OUTER_OOS").put("weighting", "TRAIN_HALF_LIFE");
        badOuterWeight.putNull("cutoff");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(badOuterWeight))
                .hasMessage("evaluation phase/cutoff weighting contract is invalid");

        ObjectNode badInnerWeight = evaluation("INNER_VALIDATION");
        badInnerWeight.put("fitCutoff", "2026-01-01T00:00:00Z").put("cutoff", "2026-03-01T00:00:00Z")
                .put("weighting", "TRAIN_HALF_LIFE");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(badInnerWeight))
                .hasMessage("evaluation phase/cutoff weighting contract is invalid");

        ObjectNode malformedCutoff = evaluation("SEARCH").put("cutoff", "not-a-timestamp");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(malformedCutoff))
                .hasMessage("fit_cutoff must be an ISO-8601 UTC timestamp");
    }

    @Test
    void evaluationIntentFormsAreCanonicalAndRejectUnknownDuplicateOrOutcomeRows() {
        ObjectNode map = evaluation("SEARCH");
        ObjectNode mapValues = map.putObject("signalIntentVector");
        mapValues.put("e1", true).put("e2", .25);
        ObjectNode mapped = StrategyStatisticalV5.makeEvaluationArtifact(map);
        assertThat(mapped.path("signal_intent_vector").get(0).path("intent").asBoolean()).isTrue();
        assertThat(mapped.path("signal_intent_vector").get(1).path("intent").asDouble()).isEqualTo(.25);

        ObjectNode unknown = evaluation("SEARCH");
        ArrayNode unknownRows = unknown.putArray("signalIntentVector");
        unknownRows.addObject().put("episode_id", "unknown").put("intent", true);
        unknownRows.addObject().put("episode_id", "e2").put("intent", false);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(unknown))
                .hasMessage("signal intent vector has duplicate or unknown episode");

        ObjectNode duplicate = evaluation("SEARCH");
        ArrayNode duplicateRows = duplicate.putArray("signalIntentVector");
        duplicateRows.addObject().put("episode_id", "e1").put("intent", true);
        duplicateRows.addObject().put("episode_id", "e1").put("intent", false);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(duplicate))
                .hasMessage("signal intent vector has duplicate or unknown episode");

        ObjectNode outcome = evaluation("SEARCH");
        outcome.putArray("signalIntentVector").addObject().put("episode_id", "e1").put("intent", true)
                .put("net_r", .2);
        outcome.withArray("signalIntentVector").addObject().put("episode_id", "e2").put("intent", false);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(outcome))
                .hasMessage("signal intent vector contains outcome fields");

        ObjectNode nonFinite = evaluation("SEARCH");
        nonFinite.putArray("signalIntentVector").addObject().put("episode_id", "e1").put("intent", "yes");
        nonFinite.withArray("signalIntentVector").addObject().put("episode_id", "e2").put("intent", false);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(nonFinite))
                .hasMessage("signal intent must be boolean or finite numeric");
    }

    @Test
    void connectedPlateauComputesNeighbourCountFractionAndPassBoundary() {
        String selectedAlias = JsonHashes.sha256("selected-alias");
        ObjectNode ga = geneticPlateau(selectedAlias, true, .4);
        ArrayNode neighbours = ga.putArray("neighbours");
        String up = JsonHashes.sha256("selected-up");
        String down = JsonHashes.sha256("selected-down");
        String bad = JsonHashes.sha256("selected-bad");
        neighbours.add(neighbour(up, 2, "long", true, .2));
        neighbours.add(neighbour(down, 0, "long", true, .1));
        neighbours.add(neighbour(bad, 1, "short", false, .9));
        neighbours.add(neighbour(up, 2, "long", true, .8));
        ObjectNode ignored = object().put("behavior_alias_sha256", JsonHashes.sha256("selected-ignored"));
        ignored.putObject("chromosome").put("only", 1);
        neighbours.add(ignored);

        ObjectNode result = StrategyStatisticalV5.connectedPlateau(ga, selectedAlias, 3, 2d / 3d);
        assertThat(result.path("pass").asBoolean()).isTrue();
        assertThat(result.path("connected_profitable_plateau_size").asInt()).isEqualTo(3);
        assertThat(result.path("profitable_neighbour_fraction").asDouble()).isEqualTo(2d / 3d);
        assertThat(result.path("selected_alias").asText()).isEqualTo(selectedAlias);

        ObjectNode stricter = StrategyStatisticalV5.connectedPlateau(ga, selectedAlias, 4, 2d / 3d);
        assertThat(stricter.path("pass").asBoolean()).isFalse();
        ObjectNode fraction = StrategyStatisticalV5.connectedPlateau(ga, selectedAlias, 3, .9);
        assertThat(fraction.path("pass").asBoolean()).isFalse();
    }

    @Test
    void connectedPlateauFailClosedForMismatchMissingSelectionAndUnprofitableRows() {
        String alias = JsonHashes.sha256("plateau-mismatch");
        ObjectNode mismatch = StrategyStatisticalV5.connectedPlateau(object(), alias, 1, 0);
        assertThat(mismatch.path("pass").asBoolean()).isFalse();
        assertThat(mismatch.path("reason").asText()).isEqualTo("SELECTED_GENETIC_ARTIFACT_MISMATCH");
        assertThat(mismatch.path("size").asInt()).isZero();

        ObjectNode missing = geneticPlateau(alias, false, .5);
        missing.putArray("neighbours").add(neighbour(JsonHashes.sha256("plateau-missing-neighbour"), 2, "long", true, .2));
        ObjectNode missingResult = StrategyStatisticalV5.connectedPlateau(missing, alias, 1, 1);
        assertThat(missingResult.path("pass").asBoolean()).isFalse();
        assertThat(missingResult.path("connected_profitable_plateau_size").asInt()).isZero();

        ObjectNode unprofitable = geneticPlateau(alias, true, 0);
        unprofitable.putArray("neighbours").add(neighbour(JsonHashes.sha256("plateau-unprofitable-neighbour"), 2, "long", false, .2));
        ObjectNode unprofitableResult = StrategyStatisticalV5.connectedPlateau(unprofitable, alias, 1, 0);
        assertThat(unprofitableResult.path("pass").asBoolean()).isFalse();
        assertThat(unprofitableResult.path("connected_profitable_plateau_size").asInt()).isZero();
        assertThat(unprofitableResult.path("profitable_neighbour_fraction").asDouble()).isZero();
    }

    @Test
    void pboReportsTimestampAndEpisodePanelBoundaryContracts() {
        ArrayNode folds = timestampFolds();
        ObjectNode defaults = StrategyStatisticalV5.pboFromFolds(folds, "a");
        assertThat(defaults.path("combinations_total").asInt()).isEqualTo(6);
        assertThat(defaults.path("valid_combinations").asInt()).isEqualTo(6);
        assertThat(defaults.path("purge_days").asDouble()).isEqualTo(30);
        assertThat(defaults.path("embargo_days").asDouble()).isEqualTo(7);
        assertThat(defaults.path("purge_ms").isNull()).isTrue();

        ArrayNode invalidChronology = timestampFolds();
        ((ObjectNode) invalidChronology.get(0)).put("test_end", "2026-01-01T00:00:00Z");
        ObjectNode timestampOptions = object().put("purgeDays", 0).put("embargoDays", 0)
                .put("requireTimestamps", true);
        assertThatThrownBy(() -> StrategyStatisticalV5.pboFromFolds(invalidChronology, "a", timestampOptions))
                .hasMessage("PBO fold test interval is not chronological");

        ArrayNode missingTimestamp = timestampFolds();
        ((ObjectNode) missingTimestamp.get(0)).remove("test_start");
        assertThatThrownBy(() -> StrategyStatisticalV5.pboFromFolds(missingTimestamp, "a", timestampOptions))
                .hasMessage("pbo[0].test_start must be an ISO-8601 UTC timestamp");

        ArrayNode invalidCandidate = timestampFolds();
        ((ObjectNode) invalidCandidate.get(0).path("candidate_means")).put("b", "unknown");
        ObjectNode noCandidate = StrategyStatisticalV5.pboFromFolds(invalidCandidate, "b",
                object().put("requireTimestamps", false));
        assertThat(noCandidate.path("pbo").isNull()).isTrue();
        assertThat(noCandidate.path("valid_combinations").asInt()).isZero();

        ArrayNode tooFew = timestampFolds(); tooFew.remove(3);
        assertThat(StrategyStatisticalV5.pboFromFolds(tooFew, "a", timestampOptions)).isNull();
    }

    @Test
    void episodeLevelPboExposesPurgeAndEmbargoedTrainingRows() {
        ArrayNode folds = MAPPER.createArrayNode();
        String[] starts = {"2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01"};
        for (int index = 0; index < starts.length; index++) {
            ObjectNode row = folds.addObject();
            ObjectNode observation = row.putArray("observations").addObject();
            observation.put("episode_id", "episode-" + index).put("decision_time", starts[index] + "T00:00:00Z")
                    .put("resolution_time", starts[index] + "T00:01:00Z");
            observation.putObject("candidate_means").put("a", index < 2 ? .4 : -.2)
                    .put("b", index < 2 ? .1 : .2);
        }
        ObjectNode result = StrategyStatisticalV5.pboFromFolds(folds, "a",
                object().put("purgeDays", 35).put("embargoDays", 40));
        assertThat(result.path("method").asText())
                .isEqualTo("EPISODE_LEVEL_PURGED_CPCV_TRAIN_WINNER_TEST_RANK_LOGIT");
        assertThat(result.path("combinations_total").asInt()).isEqualTo(6);
        assertThat(result.path("valid_combinations").asInt()).isEqualTo(2);
        assertThat(result.path("degraded_combinations").asInt()).isEqualTo(2);
        assertThat(result.path("pbo").asDouble()).isEqualTo(1);
        assertThat(result.path("details").get(0).path("train_folds").toString()).isEqualTo("[0,1]");
        assertThat(result.path("details").get(0).path("purged_train_episode_ids").toString())
                .isEqualTo("[\"episode-1\"]");
        assertThat(result.path("details").get(1).path("train_folds").toString()).isEqualTo("[2,3]");
        assertThat(result.path("details").get(1).path("embargoed_train_episode_ids").toString())
                .isEqualTo("[\"episode-2\"]");
    }

    @Test
    void vectorValidationRejectsLineageOrderAliasAndPreDiscoveryCorruption() {
        ObjectNode head = head();
        String alias = head.path("entries").get(0).path("behavior_sha256").asText();
        ObjectNode options = object().set("exposureHead", head);
        options.putArray("episodeIds").add("e1").add("e2");
        ArrayNode rows = options.putObject("vectors").putArray(alias);
        rows.addObject().put("episode_id", "e1").put("net_r", .2).put("traded", true).put("eligible", true);
        rows.addObject().put("episode_id", "e2").put("net_r", 0).put("traded", false).put("eligible", true);
        ObjectNode inventory = StrategyStatisticalV5.makeVectorInventory(options);
        assertThat(StrategyStatisticalV5.validateVectorInventory(inventory, head, options.path("episodeIds"))).isTrue();

        ObjectNode reordered = inventory.deepCopy();
        ArrayNode ids = (ArrayNode) reordered.path("episode_ids");
        JsonNode first = ids.remove(0); ids.add(first);
        reordered.put("content_sha256", StrategyStatisticalV5.ownHash(reordered));
        assertThatThrownBy(() -> StrategyStatisticalV5.validateVectorInventory(reordered, head, options.path("episodeIds")))
                .hasMessage("vector inventory episode binding mismatch");

        ObjectNode badEligibility = inventory.deepCopy();
        ((ObjectNode) badEligibility.path("vectors").path(alias).get(1)).put("eligible", false).put("net_r", .1);
        badEligibility.put("content_sha256", StrategyStatisticalV5.ownHash(badEligibility));
        assertThatThrownBy(() -> StrategyStatisticalV5.validateVectorInventory(badEligibility, head,
                options.path("episodeIds"))).hasMessage("vector " + alias + " is incomplete or misaligned");

        ObjectNode extraAlias = inventory.deepCopy();
        extraAlias.with("vectors").putArray(JsonHashes.sha256("unexpected"));
        extraAlias.put("content_sha256", StrategyStatisticalV5.ownHash(extraAlias));
        assertThatThrownBy(() -> StrategyStatisticalV5.validateVectorInventory(extraAlias, head,
                options.path("episodeIds"))).hasMessage("vector inventory is a subset or superset of the exposure head");

        ObjectNode wrongHead = head.deepCopy().put("content_sha256", JsonHashes.sha256("wrong-head"));
        assertThatThrownBy(() -> StrategyStatisticalV5.validateVectorInventory(inventory, wrongHead,
                options.path("episodeIds"))).hasMessage("vector inventory/exposure head lineage mismatch");
    }

    @Test
    void evaluationContractsAreRegisteredAndPreserveProvidedMetrics() {
        ObjectNode result = StrategyStatisticalV5.makeEvaluationArtifact(evaluation("TRAIN_CONFIRMATION"));
        assertThat(result.path("weighting").asText()).isEqualTo("TRAIN_HALF_LIFE");
        assertThat(result.path("metrics").path("capacity_pass").asBoolean()).isTrue();
        assertThat(result.path("schema").asText()).isEqualTo("strategy-v5-statistical-evaluation/1");

        ObjectNode noPhase = evaluation("");
        noPhase.remove("phase");
        ObjectNode normalized = StrategyStatisticalV5.makeEvaluationArtifact(noPhase);
        assertThat(normalized.path("phase").asText()).isEqualTo("undefined");
        assertThat(normalized.path("weighting").asText()).isEqualTo("UNWEIGHTED_OOS");
    }

    private static ObjectNode evaluation(String phase) {
        ObjectNode signal = object().put("schema", "strategy-v5-statistical-signal-view/1")
                .put("source_artifact_sha256", JsonHashes.sha256("evaluation-source"));
        signal.putArray("episodes").addObject().put("episode_id", "e1").put("asset", "btc");
        signal.withArray("episodes").addObject().put("episode_id", "e2").put("asset", "eth");
        ObjectNode value = object().set("signalArtifact", signal);
        value.putArray("episodeIds").add("e1").add("e2");
        value.putObject("candidateReturns").putObject("e1").put("net_r", .2).put("traded", true);
        value.with("candidateReturns").putObject("e2").put("net_r", 0).put("traded", false);
        value.putObject("metrics").put("cost_r", .01).put("coverage_fraction", 1).put("capacity_pass", true)
                .put("max_drawdown_r", 0).put("profit_factor", 2).put("turnover", 1).put("complexity", 1);
        value.put("phase", phase).put("foldId", "fold-1");
        if ("OUTER_OOS".equals(phase)) value.putNull("cutoff");
        else value.put("cutoff", "2026-02-01T00:00:00Z");
        value.putArray("signalIntentVector").addObject().put("episode_id", "e1").put("intent", true);
        value.withArray("signalIntentVector").addObject().put("episode_id", "e2").put("intent", false);
        return value;
    }

    private static ObjectNode geneticPlateau(String alias, boolean selected, double expectancy) {
        ObjectNode ga = object().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("genetic"))
                .put("selected_behavior_alias_sha256", alias);
        if (selected) {
            ObjectNode chosen = ga.putObject("selected");
            chosen.putObject("chromosome").put("threshold", 1).put("side", "long");
            chosen.putObject("fitness").put("feasible", expectancy > 0).putObject("metrics")
                    .put("expectancy_r", expectancy);
        } else ga.putNull("selected");
        ga.putArray("neighbours");
        return ga;
    }

    private static ObjectNode neighbour(String alias, int threshold, String side, boolean feasible, double expectancy) {
        ObjectNode value = object().put("behavior_alias_sha256", alias);
        value.putObject("chromosome").put("threshold", threshold).put("side", side);
        value.put("feasible", feasible).put("expectancy_r", expectancy);
        return value;
    }

    private static ArrayNode timestampFolds() {
        ArrayNode folds = MAPPER.createArrayNode();
        for (int index = 0; index < 4; index++) {
            ObjectNode fold = folds.addObject();
            fold.putObject("candidate_means").put("a", index % 2 == 0 ? .4 : -.2)
                    .put("b", index % 2 == 0 ? .1 : .2);
            fold.put("test_start", "2026-0" + (index + 1) + "-01T00:00:00Z");
            fold.put("test_end", "2026-0" + (index + 1) + "-02T00:00:00Z");
        }
        return folds;
    }

    private static ObjectNode head() {
        String dataset = JsonHashes.sha256("vector-contract-data");
        ObjectNode args = object().put("hypothesisFamily", "vector-contract").put("datasetSha256", dataset);
        args.putArray("entries").addObject().put("behavior_sha256", JsonHashes.sha256("vector-contract-alias"))
                .put("dataset_sha256", dataset);
        return StrategyStatisticalV5.makeExposureHead(args);
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
}
