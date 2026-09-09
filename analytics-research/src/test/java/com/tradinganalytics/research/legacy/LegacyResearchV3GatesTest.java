package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.research.swing.SwingEngine;
import org.junit.jupiter.api.Test;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.array;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyResearchV3GatesTest {
    @Test
    void stressAcceptanceRunAndExperimentGatesPreserveTheOriginalBoundaries() {
        ObjectNode contract = LegacyResearchV3.makeAcceptanceContract();
        ObjectNode activeOutageContract = contractWithOutage(contract, 10, 15);
        ArrayNode completeTrades = array()
                .add(stressTrade("stress-outage-affected", 0.5, 11, 12)
                        .put("net_pnl", 50))
                .add(stressTrade("stress-1", 0.5, 20, 21).put("net_pnl", 50));
        ObjectNode complete = LegacyStrategyResearch.v3Stress(
                completeTrades, activeOutageContract, false, "d".repeat(64));
        assertThat(complete.path("pass").asBoolean()).isTrue();
        assertThat(scenario(complete, "VENUE_OUTAGE").path("affected_trade_ids"))
                .extracting(JsonNode::asText).containsExactly("stress-outage-affected");

        ArrayNode bad = array()
                .add(stressTrade("stress-bad", -1,
                        "2020-03-13T00:00:00Z", "2020-03-14T00:00:00Z")
                        .put("net_pnl", -100))
                .add(stressTrade("stress-bad-remaining", -1,
                        "2020-04-13T00:00:00Z", "2020-04-14T00:00:00Z")
                        .put("net_pnl", -100));
        assertThat(LegacyStrategyResearch.v3Stress(
                bad, contract, false, "d".repeat(64)).path("pass").asBoolean()).isFalse();
        ArrayNode missing = array().add(object().put("trade_id", "stress-missing")
                .put("net_r", 0.5).put("venue", "public")
                .put("entry_time", "2020-03-13T00:00:00Z")
                .put("exit_time", "2020-03-14T00:00:00Z"));
        assertThat(LegacyStrategyResearch.v3Stress(
                missing, contract, false, "d".repeat(64)).path("pass").asBoolean()).isFalse();
        ObjectNode defaultOutage = LegacyStrategyResearch.v3Stress(array().add(
                stressTrade("default-outage", 0.5,
                        "2020-03-13T00:00:00Z", "2020-03-14T00:00:00Z")),
                contract, false, "d".repeat(64));
        assertThat(scenario(defaultOutage, "VENUE_OUTAGE").path("pass").asBoolean())
                .isFalse();
        ObjectNode noLiquidity = stressTrade("no-liquidity", 0.5, 10, 20);
        noLiquidity.remove("available_liquidity_notional");
        assertThat(scenario(LegacyStrategyResearch.v3Stress(array().add(noLiquidity),
                contract, false, "d".repeat(64)), "LIQUIDITY_CAPACITY")
                .path("pass").asBoolean()).isFalse();
        assertThat(scenario(LegacyStrategyResearch.v3Stress(array().add(
                stressTrade("over-capacity", 0.5, 10, 20)
                        .put("available_liquidity_notional", 1_000)),
                contract, false, "d".repeat(64)), "LIQUIDITY_CAPACITY")
                .path("pass").asBoolean()).isFalse();
        ObjectNode outage = LegacyStrategyResearch.v3Stress(array().add(
                stressTrade("outage", 0.5, 11, 12)), activeOutageContract,
                false, "d".repeat(64));
        assertThat(outage.path("pass").asBoolean()).isFalse();
        assertThat(scenario(outage, "VENUE_OUTAGE").path("affected_trade_ids"))
                .extracting(JsonNode::asText).contains("outage");

        ObjectNode unknownOptions = object();
        ArrayNode unknownScenarios = contract.path("stress_scenarios").deepCopy();
        ObjectNode unknownOutage = findScenario(unknownScenarios, "VENUE_OUTAGE");
        ((ObjectNode) unknownOutage.path("parameters")).put("unexpected_bypass", true);
        unknownOptions.set("stressScenarios", unknownScenarios);
        assertThatThrownBy(() -> LegacyStrategyResearch.v3Stress(
                array(), LegacyResearchV3.makeAcceptanceContract(unknownOptions)))
                .hasMessageContaining("unknown fields");
        ObjectNode invalidOptions = object();
        ArrayNode invalidScenarios = contract.path("stress_scenarios").deepCopy();
        ObjectNode invalidOutage = findScenario(invalidScenarios, "VENUE_OUTAGE");
        ((ObjectNode) invalidOutage.path("parameters")).set("blackout_windows",
                array().add(object().put("venue", "public")
                        .put("start_time", 20).put("end_time", 10)));
        invalidOptions.set("stressScenarios", invalidScenarios);
        assertThatThrownBy(() -> LegacyStrategyResearch.v3Stress(
                array(), LegacyResearchV3.makeAcceptanceContract(invalidOptions)))
                .hasMessageContaining("start_time < end_time");

        ObjectNode metrics = passingMetrics();
        assertThat(LegacyResearchV3.evaluateAcceptance(metrics, contract)
                .path("decision").asText()).isEqualTo("SHADOW");
        ObjectNode lateEvidence = lateEvidence(contract);
        lateEvidence.put("phase", "CI_ATTESTED_CONFIRMATION");
        ObjectNode ci = LegacyResearchV3.evaluateAcceptance(metrics, contract, lateEvidence);
        assertThat(ci.path("decision").asText()).isEqualTo("REJECTED");
        assertThat(ci.path("failures")).extracting(JsonNode::asText)
                .contains("CI_ATTESTED_REQUIRES_PROSPECTIVE_REVIEW");
        ObjectNode prospectiveEvidence = lateEvidence.deepCopy()
                .put("phase", "PROSPECTIVE_LIVE");
        prospectiveEvidence.set("prospective", object().put("pass", true).put("frozen", true));
        assertThat(LegacyResearchV3.evaluateAcceptance(
                metrics, contract, prospectiveEvidence).path("decision").asText())
                .isEqualTo("CANDIDATE_REVIEW");

        assertThatThrownBy(() -> SwingEngine.normalizeCandidate(
                object().put("framework", "fallen_knives").put("max_concurrent", 2)))
                .hasMessageContaining("max_concurrent > 1 is unsupported");
        ObjectNode foldOptions = object().put("barDurationMs", 3_600_000);
        assertThatThrownBy(() -> LegacyResearchV3.validateWfoFolds(array().add(object()
                .put("train_start", "2026-01-01T00:00:00Z")
                .put("train_end", "2026-01-03T00:00:00Z")
                .put("test_start", "2026-01-03T00:00:00Z")
                .put("test_end", "2026-01-04T00:00:00Z")), foldOptions))
                .hasMessageContaining("chronological");

        ObjectNode experiment = experiment(contract, "run-id-test", "DEVELOPMENT", false);
        ObjectNode decisions = decisions("SHADOW");
        ObjectNode runOptions = object();
        runOptions.set("experiment", experiment);
        runOptions.set("evidenceBundle", LegacyNodeOracle.MAPPER.nullNode());
        runOptions.set("decisions", decisions);
        ObjectNode run = LegacyResearchV3.makeRunV3(runOptions);
        assertThat(run.path("run_id").asText()).isEqualTo(run.path("content_sha256").asText());
        assertThat(LegacyResearchV3.validateRunV3(run)).isTrue();

        ObjectNode ciExperiment = experiment(
                contract, "ci-shadow-test", "CI_ATTESTED_CONFIRMATION", true);
        ObjectNode evidenceOptions = object();
        evidenceOptions.set("experiment", ciExperiment);
        evidenceOptions.set("decision", object().put("status", "CANDIDATE_REVIEW"));
        assertThatThrownBy(() -> LegacyResearchV3.makeEvidenceBundle(evidenceOptions))
                .hasMessageMatching(".*(always SHADOW|unavailable).*?");
        ObjectNode ciRunOptions = object();
        ciRunOptions.set("experiment", ciExperiment);
        ciRunOptions.set("decisions", object().set("per_asset",
                array().add(object().put("asset", "btc")
                        .put("status", "CANDIDATE_REVIEW"))));
        assertThatThrownBy(() -> LegacyResearchV3.makeRunV3(ciRunOptions))
                .hasMessageMatching(".*(always SHADOW|unavailable).*?");

        ObjectNode predecessorOptions = baseExperimentOptions(contract, "entry-stage-test")
                .put("stage", "ENTRY_TIMING").put("predecessorStage", "CORE_PREMISE")
                .put("predecessorSha256", "1".repeat(64));
        assertThat(LegacyResearchV3.makeExperimentV3(predecessorOptions)
                .path("stage").asText()).isEqualTo("ENTRY_TIMING");
        ObjectNode invalidStage = baseExperimentOptions(contract, "composite-lead-test")
                .put("stage", "COMPOSITE_SCORE").put("predecessorStage", "CORE_PREMISE")
                .put("predecessorSha256", "1".repeat(64));
        assertThatThrownBy(() -> LegacyResearchV3.makeExperimentV3(invalidStage))
                .hasMessageContaining("must directly follow");
        ObjectNode doge = baseExperimentOptions(contract, "doge-test");
        doge.set("requiredAssets", array().add("doge"));
        assertThatThrownBy(() -> LegacyResearchV3.makeExperimentV3(doge))
                .hasMessageContaining("crypto-only");
    }

    @Test
    void pooledAndPerAssetWfoSelectionAndCompactAccountingAreDeterministic() {
        ArrayNode candidates = array()
                .add(object().put("candidate_id", "a"))
                .add(object().put("candidate_id", "b"));
        ArrayNode folds = array()
                .add(wfoFold("f1", 0, 10, 20, 30))
                .add(wfoFold("f2", 0, 30, 40, 50));
        ObjectNode options = object();
        options.set("trainingSelectionPolicy", LegacyResearchV3.makeTrainingSelectionPolicy());
        ObjectNode pooled = LegacyResearchV3.walkForwardV3(candidates, folds,
                (candidate, fold, index) -> object()
                        .put("expectancy_r", candidate.path("candidate_id").asText()
                                .equals("a") ? 1 : 0.5)
                        .put("completed_trades", 10),
                (candidate, fold, index) -> {
                    ObjectNode value = object();
                    value.set("metrics", object().put("expectancy_r", 0.2));
                    value.set("trades", array().add(object()
                            .put("episode_id", "same-oos-episode").put("net_r", 0.2)
                            .put("exit_time", 30)));
                    return value;
                }, null, options);
        assertThat(pooled.path("folds")).extracting(
                fold -> fold.path("train").path("winner").asText())
                .containsExactly("a", "a");
        assertThat(pooled.path("oos_episodes").asInt()).isOne();
        assertThat(pooled.path("positive_folds").asInt()).isEqualTo(2);

        ArrayNode assetFolds = array().add(wfoFold("asset-fold", 0, 10, 20, 30));
        ObjectNode assetOptions = object();
        assetOptions.set("trainingSelectionPolicy",
                LegacyResearchV3.makeTrainingSelectionPolicy());
        assetOptions.set("requiredAssets", array().add("btc").add("eth"));
        ObjectNode perAsset = LegacyResearchV3.walkForwardV3(candidates, assetFolds,
                (candidate, fold, index) -> perAssetTrain(candidate.path("candidate_id").asText()),
                (candidate, fold, index) -> perAssetTest(candidate.path("candidate_id").asText()),
                null, assetOptions);
        assertThat(perAsset.path("final_selection_by_asset").path("btc").asText())
                .isEqualTo("a");
        assertThat(perAsset.path("final_selection_by_asset").path("eth").asText())
                .isEqualTo("b");
        assertThat(perAsset.path("final_selection_metrics_by_asset")
                .path("btc").path("candidate_id").asText()).isEqualTo("a");
        assertThat(perAsset.path("final_selection_metrics_by_asset")
                .path("eth").path("candidate_id").asText()).isEqualTo("b");
        assertThat(perAsset.path("candidate_accounting")).anySatisfy(row -> {
            assertThat(row.path("phase").asText()).isEqualTo("TRAIN");
            assertThat(row.path("candidate_id").asText()).isEqualTo("a");
            assertThat(row.path("asset").asText()).isEqualTo("btc");
            assertThat(row.path("actual_trade_count").asInt()).isOne();
        });
        assertThat(perAsset.path("candidate_accounting")).anySatisfy(row -> {
            assertThat(row.path("phase").asText()).isEqualTo("TRAIN");
            assertThat(row.path("candidate_id").asText()).isEqualTo("b");
            assertThat(row.path("asset").asText()).isEqualTo("btc");
            assertThat(row.path("zero_trade").asBoolean()).isTrue();
        });
    }

    private static ObjectNode passingMetrics() {
        ObjectNode robust = object().put("effective_independent_episode_count", 100)
                .put("bootstrap_p20_expectancy_r", 0.2)
                .put("candidate_set_max_statistic_p_value", 0.01);
        ObjectNode value = object().put("completed_trades", 100)
                .put("expectancy_r", 0.3).put("search_adjusted_expectancy_r", 0.2)
                .put("profit_factor_r", 2).put("profit_factor_account", 2)
                .put("total_return", 0.2).put("max_drawdown_pct", 1)
                .put("coverage_fraction", 1).put("undeclared_gap_bars", 0)
                .put("funding_processed", true);
        value.set("robust_stats", robust);
        value.set("years", object()
                .set("2024", object().put("expectancy_r", 0.2).put("episodes", 10)));
        ((ObjectNode) value.path("years"))
                .set("2025", object().put("expectancy_r", 0.2).put("episodes", 10));
        value.set("chronological_blocks", array()
                .add(object().put("expectancy_r", 0.2))
                .add(object().put("expectancy_r", 0.1)));
        value.set("doubled_cost", object().put("expectancy_r", 0.1)
                .put("profit_factor_account", 1.2));
        return value;
    }

    private static ObjectNode lateEvidence(ObjectNode contract) {
        ObjectNode training = LegacyResearchV3.makeTrainingSelectionPolicy();
        ObjectNode finalPolicy = object().put("name", "fixture-final-selection")
                .put("train_only", true)
                .put("policy_sha256", training.path("content_sha256").asText())
                .putNull("experiment_sha256").put("basis", "fixture");
        ObjectNode selection = object().put("btc", "candidate-a");
        ObjectNode selectionMetrics = object();
        selectionMetrics.set("btc", object().put("candidate_id", "candidate-a")
                .put("fold_id", "f1").put("metrics_sha256", "b".repeat(64))
                .put("completed_trades", 30).put("expectancy_r", 0.2));
        ObjectNode accountingRow = object().put("phase", "TRAIN")
                .put("fold_id", "f1").put("candidate_id", "candidate-a")
                .put("asset", "btc").put("actual_trade_count", 30)
                .put("zero_episode_count", 0);
        accountingRow.set("window", object().put("start", 0).put("end", 1));
        ArrayNode accounting = array().add(accountingRow);
        ObjectNode wfo = object().put("oos_episodes", 30).put("positive_folds", 3)
                .put("effective_k", 2)
                .put("training_selection_policy_sha256",
                        training.path("content_sha256").asText());
        wfo.set("aggregate_oos_metrics", object().put("expectancy_r", 0.2)
                .put("search_adjusted_expectancy_r", 0.1)
                .put("bootstrap_p20_expectancy_r", 0.05)
                .put("candidate_set_max_statistic_p_value", 0.05));
        wfo.set("fold_hashes", array().add("f1"));
        wfo.set("winner_lineage", array().add(
                object().put("fold_id", "f1").put("winner", "candidate-a")));
        wfo.set("selection_policy", object().put("train_only", true)
                .put("policy_sha256", training.path("content_sha256").asText()));
        wfo.set("final_selection_policy", finalPolicy);
        wfo.set("final_selection_by_asset", selection);
        wfo.set("final_selection_metrics_by_asset", selectionMetrics);
        ObjectNode payload = object();
        payload.set("policy", finalPolicy);
        payload.set("selection_by_asset", selection);
        payload.set("selection_metrics_by_asset", selectionMetrics);
        wfo.put("final_selection_sha256", LegacyResearchV3.hash(payload));
        wfo.set("candidate_accounting", accounting);
        wfo.put("candidate_accounting_sha256", LegacyResearchV3.hash(accounting));

        ObjectNode stress = object().put("pass", true)
                .put("provenance", "AUTHORITATIVE_RECOMPUTED")
                .put("suite_sha256", "a".repeat(64));
        ArrayNode scenarios = array();
        for (JsonNode row : contract.path("stress_scenarios")) {
            scenarios.add(object().put("name", row.path("name").asText())
                    .put("pass", true).put("model_completeness", true));
        }
        stress.set("scenarios", scenarios);
        ObjectNode options = object().put("funding", true);
        options.set("wfo", wfo);
        options.set("stress", stress);
        options.set("portfolio", object().put("pass", true));
        options.set("coverage", object().put("price_fraction", 1)
                .put("derivatives_fraction", 1).put("verified", true));
        return options;
    }

    private static ObjectNode perAssetTrain(String candidate) {
        ObjectNode value = object();
        value.set("metrics", object().put("expectancy_r", 1).put("completed_trades", 10));
        value.set("by_asset", array()
                .add(object().put("asset", "btc")
                        .put("expectancy_r", candidate.equals("a") ? 2 : 1)
                        .put("completed_trades", 10))
                .add(object().put("asset", "eth")
                        .put("expectancy_r", candidate.equals("b") ? 2 : 1)
                        .put("completed_trades", 10)));
        ArrayNode trades = array();
        if (candidate.equals("a")) {
            trades.add(wfoTrade(candidate, "train-btc", "btc", 9, 0.5));
            trades.add(wfoTrade(candidate, "train-eth", "eth", 9, 0.5));
        }
        value.set("trades", trades);
        return value;
    }

    private static ObjectNode perAssetTest(String candidate) {
        ObjectNode value = object();
        value.set("trades", array()
                .add(wfoTrade(candidate, "oos-btc", "btc", 29, 0.2))
                .add(wfoTrade(candidate, "oos-eth", "eth", 29, 0.2)));
        return value;
    }

    private static ObjectNode wfoTrade(
            String candidate, String suffix, String asset, long exit, double netR) {
        return object().put("asset", asset).put("candidate_id", candidate)
                .put("trade_id", candidate + '-' + suffix)
                .put("episode_id", candidate + '-' + suffix)
                .put("net_r", netR).put("exit_time", exit);
    }

    private static ObjectNode wfoFold(
            String id, long trainStart, long trainEnd, long testStart, long testEnd) {
        return object().put("fold_id", id).put("train_start", trainStart)
                .put("train_end", trainEnd).put("test_start", testStart)
                .put("test_end", testEnd).put("bar_duration_ms", 1)
                .put("purge_bars", 2).put("embargo_bars", 8);
    }

    private static ObjectNode experiment(
            ObjectNode contract, String id, String phase, boolean frozen) {
        ObjectNode options = baseExperimentOptions(contract, id)
                .put("evidencePhase", phase);
        if (!"DEVELOPMENT".equals(phase)) {
            options.put("featureSetSha256", "e".repeat(64))
                    .put("labelSetSha256", "f".repeat(64));
        }
        ObjectNode chronology = object().put("timezone", "UTC")
                .put("bar_convention", "completed-bar-next-open")
                .put("frozen_selection", frozen);
        chronology.set("seeds", array().add(1));
        options.set("chronology", chronology);
        return LegacyResearchV3.makeExperimentV3(options);
    }

    private static ObjectNode baseExperimentOptions(ObjectNode contract, String id) {
        ObjectNode options = object().put("experimentId", id)
                .put("precommitSha256", "a".repeat(64))
                .put("definitionSha256", "b".repeat(64))
                .put("candidateSetSha256", "c".repeat(64))
                .put("dataManifestSha256", "d".repeat(64));
        options.set("requiredAssets", array().add("btc"));
        options.set("acceptanceContract", contract);
        options.set("chronology", object().put("timezone", "UTC")
                .put("bar_convention", "completed-bar-next-open")
                .set("seeds", array().add(1)));
        return options;
    }

    private static ObjectNode decisions(String status) {
        ObjectNode value = object();
        value.set("per_asset", array().add(
                object().put("asset", "btc").put("status", status)));
        value.set("portfolio", object().put("status", status));
        return value;
    }

    private static ObjectNode stressTrade(
            String id, double netR, Object entry, Object exit) {
        ObjectNode value = object().put("trade_id", id).put("net_r", netR)
                .put("risk_dollars", 100).put("fees", 1).put("slippage_debit", 1)
                .put("mae_pct", -1).put("notional", 100)
                .put("available_liquidity_notional", 5_000).put("venue", "public");
        if (entry instanceof Number number) value.put("entry_time", number.longValue());
        else value.put("entry_time", String.valueOf(entry));
        if (exit instanceof Number number) value.put("exit_time", number.longValue());
        else value.put("exit_time", String.valueOf(exit));
        return value;
    }

    private static ObjectNode contractWithOutage(
            ObjectNode contract, long start, long end) {
        ArrayNode scenarios = contract.path("stress_scenarios").deepCopy();
        ObjectNode outage = findScenario(scenarios, "VENUE_OUTAGE");
        ((ObjectNode) outage.path("parameters")).set("blackout_windows",
                array().add(object().put("venue", "public")
                        .put("start_time", start).put("end_time", end)));
        ObjectNode options = object();
        options.set("stressScenarios", scenarios);
        return LegacyResearchV3.makeAcceptanceContract(options);
    }

    private static ObjectNode scenario(ObjectNode result, String name) {
        return findScenario((ArrayNode) result.path("scenarios"), name);
    }

    private static ObjectNode findScenario(ArrayNode scenarios, String name) {
        for (JsonNode row : scenarios) {
            if (name.equals(row.path("name").asText())) return (ObjectNode) row;
        }
        throw new AssertionError("missing scenario " + name);
    }
}
