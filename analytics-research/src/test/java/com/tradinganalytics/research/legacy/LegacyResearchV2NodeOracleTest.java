package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.array;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyResearchV2NodeOracleTest {
    @Test
    void publicApiCoversEveryV2Export() {
        Set<String> methods = Arrays.stream(LegacyResearchV2.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertThat(methods).contains(
                "stable", "hash", "clone", "ownHash", "withHash", "isCryptoInstrument",
                "validateCryptoUniverse", "validatePrecommit", "freezePrecommit",
                "validateFeatureIndependence", "validateDefinitionV2", "validateExperimentV2",
                "validateCandidateSetV2", "makeV2Definition", "designCandidates",
                "designContextAblations", "plateauDiagnostics", "validatePlateauSelection",
                "effectiveIndependentEpisodeCount", "deterministicBlocks",
                "blockBootstrapExpectancy", "candidateSetMaxStatisticPValue",
                "searchAdjustedExpectancyHeuristic", "searchAdjustedExpectancyHeuristicR",
                "validateRobustStats", "joinCompletedBarAsOf", "validateMultiTimeframeContract",
                "compareProspectiveExpectation", "monitorProspective", "validateStressSuite",
                "runStressSuite", "renderPremiseMarkdown", "makeV2Run", "makeAuthoritativeRun",
                "validateV2Document", "validateDataManifest", "validateParameterTopology",
                "validateEvaluationChronology", "validateCanonicalTrades",
                "computeRiskDiagnostics", "computeGlobalRobustness", "behavioralFingerprint",
                "validateFrozenSelection", "evaluateAuthoritative", "validateEvidenceBundle");
        assertThat(LegacyResearchV2.EXECUTOR_ADAPTERS).containsExactly("swing-engine/1");
        assertThat(LegacyResearchV2.STAGES).containsExactly(
                "CORE_PREMISE", "ENTRY_TIMING", "RISK_LIFECYCLE",
                "INDEPENDENT_CONTEXT", "COMPOSITE_SCORE");
    }

    @Test
    void stableHashOwnHashAndWithHashAreCanonical() {
        ObjectNode value = object().put("schema", "fixture/1").put("z", 2).put("a", "é");
        value.set("nested", object().put("b", true).putNull("a"));
        assertThat(LegacyResearchV2.stable(value))
                .isEqualTo("{\"a\":\"é\",\"nested\":{\"a\":null,\"b\":true},\"schema\":\"fixture/1\",\"z\":2}");
        assertThat(LegacyResearchV2.hash(value)).matches("[0-9a-f]{64}");
        assertThat(LegacyResearchV2.ownHash(value)).isEqualTo(LegacyResearchV2.hash(value));
        assertThat(LegacyResearchV2.withHash(value).path("content_sha256").asText())
                .isEqualTo(LegacyResearchV2.hash(value));
    }

    @Test
    void deterministicBlocksBootstrapAndMaxStatisticAreRepeatable() {
        ArrayNode rows = array()
                .add(object().put("episode_id", "b").put("timestamp", 2).put("net_r", -0.5))
                .add(object().put("episode_id", "a").put("timestamp", 1).put("net_r", 1.0))
                .add(object().put("episode_id", "a").put("timestamp", 3).put("net_r", 0.5));
        ObjectNode options = object().put("iterations", 64).put("seed", 17)
                .put("blockSize", 2).put("alpha", 0.2);
        assertThat(LegacyResearchV2.deterministicBlocks(rows, options))
                .isEqualTo(LegacyResearchV2.deterministicBlocks(rows, options));
        assertThat(LegacyResearchV2.blockBootstrapExpectancy(rows, options))
                .isEqualTo(LegacyResearchV2.blockBootstrapExpectancy(rows, options));

        ArrayNode candidates = array()
                .add(object().put("candidate_id", "a").set("rows", rows))
                .add(object().put("candidate_id", "b").set("rows", array()
                        .add(object().put("episode_id", "a").put("net_r", -0.2))
                        .add(object().put("episode_id", "b").put("net_r", 0.4))));
        ObjectNode statistic = LegacyResearchV2.candidateSetMaxStatisticPValue(candidates, options);
        assertThat(statistic).isEqualTo(
                LegacyResearchV2.candidateSetMaxStatisticPValue(candidates, options));
        assertThat(statistic.path("p_value").asDouble()).isBetween(0.0, 1.0);
    }

    @Test
    void completedBarJoinAndProspectiveMonitoringRespectTimeBoundaries() {
        ObjectNode join = object();
        join.set("decisions", array().add(object().put("asset", "btc")
                .put("decision_time", "2026-01-02T00:00:00Z")));
        join.set("higher", array()
                .add(object().put("asset", "btc").put("availability_time",
                        "2026-01-01T00:00:00Z").put("value", 1))
                .add(object().put("asset", "btc").put("availability_time",
                        "2026-01-03T00:00:00Z").put("value", 2)));
        join.set("setup", array().add(object().put("asset", "btc")
                .put("availability_time", "2026-01-01T12:00:00Z").put("value", 3)));
        join.set("lower", array().add(object().put("asset", "btc")
                .put("availability_time", "2026-01-01T23:00:00Z").put("value", 4)));
        join.put("includeLower", true);
        ArrayNode joined = LegacyResearchV2.joinCompletedBarAsOf(join);
        assertThat(joined).hasSize(1);
        assertThat(joined.get(0).toString()).contains("1", "3", "4").doesNotContain("\"value\":2");

        ObjectNode profile = object();
        profile.set("frequency", object().put("min", 1).put("max", 5));
        profile.set("win_rate", object().put("min", 0.4).put("max", 0.8));
        profile.set("expectancy_r", object().put("min", 0).put("max", 1));
        profile.put("minimum_trades", 2).put("max_loss_run", 3);
        ObjectNode evidence = object()
                .put("monitoring_start", "2026-01-01T00:00:00Z")
                .put("monitoring_end", "2026-01-31T00:00:00Z")
                .put("prospective_start", "2026-01-01T00:00:00Z");
        evidence.set("trades", array()
                .add(object().put("signal_id", "a")
                        .put("signal_time", "2026-01-05T00:00:00Z").put("net_r", 1))
                .add(object().put("signal_id", "b")
                        .put("signal_time", "2026-01-10T00:00:00Z").put("net_r", -0.2)));
        ObjectNode comparison = LegacyResearchV2.compareProspectiveExpectation(profile, evidence);
        assertThat(comparison.path("win_rate").path("actual").asDouble()).isEqualTo(.5);
        assertThat(comparison.path("expectancy_r").path("actual").asDouble()).isEqualTo(.4);
        assertThat(comparison.path("status").asText()).isNotBlank();
    }

    @Test
    void riskAndGlobalRobustnessAreAuthoritativelyDerived() {
        ArrayNode trades = array()
                .add(trade("a", 1, 2, 1.0, 100))
                .add(trade("b", 3, 4, -0.5, -50))
                .add(trade("c", 5, 7, 0.25, 25))
                .add(trade("d", 8, 9, -0.1, -10));
        ObjectNode riskOptions = object().put("initialEquity", 1_000)
                .put("bootstrapIterations", 32).put("seed", 9)
                .put("horizon", 4).put("blockSize", 2).put("ruinThreshold", 0.25);
        ObjectNode risk = LegacyResearchV2.computeRiskDiagnostics(trades, riskOptions);
        assertThat(risk).isEqualTo(LegacyResearchV2.computeRiskDiagnostics(trades, riskOptions));
        assertThat(risk.toString()).contains("drawdown");

        ArrayNode metricRows = array()
                .add(object().put("asset", "btc").set("metrics",
                        object().put("expectancy_r", 0.4).put("exposure", 0.2)))
                .add(object().put("asset", "eth").set("metrics",
                        object().put("expectancy_r", -0.1).put("exposure", 0.4)));
        ObjectNode globalOptions = object().put("behavioralK", 2);
        globalOptions.set("trades", trades);
        globalOptions.set("bars", array().add(object().put("time", 0))
                .add(object().put("time", 10)));
        ObjectNode robustness = LegacyResearchV2.computeGlobalRobustness(metricRows, globalOptions);
        assertThat(robustness.isObject()).isTrue();
        assertThat(robustness.toString()).contains("btc", "eth");
    }

    @Test
    void failClosedValidationRejectsNonCryptoAndIncompleteTimeframes() {
        ObjectNode universe = object();
        universe.set("instruments", array().add(object()
                .put("asset", "SPY").put("asset_class", "equity")
                .put("instrument_type", "spot")));
        assertThatThrownBy(() -> LegacyResearchV2.validateCryptoUniverse(universe))
                .hasMessageContaining("crypto");

        ObjectNode timeframe = object();
        timeframe.set("higher_timeframe", object().put("completed_bar_only", true));
        timeframe.set("setup_timeframe", object().put("completed_bar_only", false));
        assertThatThrownBy(() -> LegacyResearchV2.validateMultiTimeframeContract(timeframe))
                .hasMessageContaining("completed_bar_only");
    }

    @Test
    void evidenceReconciliationValidationRejectsTampering() throws Exception {
        ObjectNode bundle = minimalEvidenceBundle();
        assertThat(LegacyResearchV2.validateEvidenceBundle(bundle)).isTrue();

        ((ObjectNode) bundle.get("reconciliation"))
                .put("all_trades_sha256", LegacyResearchV2.hash("tampered"));
        bundle.put("content_sha256", LegacyResearchV2.ownHash(bundle));
        assertThatThrownBy(() -> LegacyResearchV2.validateEvidenceBundle(bundle))
                .hasMessageContaining("reconciliation");
    }

    private static ObjectNode minimalEvidenceBundle() throws Exception {
        String valueHash = LegacyResearchV2.hash("z");
        ArrayNode metrics = array(), trades = array(), selected = array();
        ObjectNode sources = object()
                .put("swing_engine", classHash(com.tradinganalytics.research.swing.SwingEngine.class))
                .put("authoritative_evaluator", classHash(LegacyResearchV2.class))
                .put("portfolio_simulator", classHash(LegacyResearchV2.class));
        ObjectNode executor = object()
                .put("source_file_sha256", valueHash)
                .put("feature_store_sha256", valueHash)
                .put("feature_store_artifact_sha256", valueHash)
                .put("data_manifest_sha256", valueHash)
                .put("data_manifest_artifact_sha256", valueHash)
                .put("package_lock_sha256", classHash(LegacyResearchV2.class))
                .put("environment_sha256", valueHash)
                .put("code_config_sha256", valueHash)
                .put("identity_sha256", valueHash);
        executor.set("source_files", sources);
        ObjectNode reconciliation = object()
                .put("candidate_trade_set_sha256", LegacyResearchV2.hash(array()))
                .put("all_trades_sha256", LegacyResearchV2.hash(trades))
                .put("selected_trades_sha256", LegacyResearchV2.hash(selected))
                .put("derived_metrics_sha256", LegacyResearchV2.hash(metrics))
                .put("stress_result_sha256", LegacyResearchV2.hash(NullNode.instance))
                .put("portfolio_source_sha256", valueHash)
                .put("portfolio_result_sha256", LegacyResearchV2.hash(NullNode.instance));
        ObjectNode bundle = object()
                .put("schema", LegacyResearchV2.EVIDENCE_BUNDLE_SCHEMA)
                .put("bundle_version", 1)
                .put("provenance", "AUTHORITATIVE_RECOMPUTED")
                .put("evidence_phase", "DEVELOPMENT")
                .put("experiment_id", "fixture-experiment")
                .put("strategy_id", "fixture-strategy")
                .put("precommit_sha256", valueHash)
                .put("definition_sha256", valueHash)
                .put("experiment_sha256", valueHash)
                .put("candidate_set_sha256", valueHash)
                .put("data_manifest_sha256", valueHash)
                .put("feature_store_sha256", valueHash);
        bundle.set("candidate_accounting", object());
        bundle.set("required_assets", array().add("btc"));
        bundle.set("executor", executor);
        bundle.set("metrics", metrics);
        bundle.set("trades", trades);
        bundle.set("selected_trades", selected);
        bundle.putNull("stress");
        bundle.putNull("portfolio");
        bundle.set("reconciliation", reconciliation);
        bundle.set("decisions", object().set("per_asset", array()));
        ((ObjectNode) bundle.get("decisions")).set("portfolio", object().put("status", "SHADOW"));
        bundle.set("failures", array());
        bundle.set("activation", object().put("authorized", false).put("status", "SHADOW"));
        return LegacyResearchV2.withHash(bundle);
    }

    private static ObjectNode trade(
            String id, long entry, long exit, double netR, double pnl) {
        return object().put("trade_id", id).put("candidate_id", "candidate")
                .put("asset", "btc").put("entry_time", entry).put("exit_time", exit)
                .put("net_r", netR).put("net_pnl", pnl).put("regime", "RANGE");
    }

    private static String classHash(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("class bytes missing: " + resource);
            return LegacyResearchSupport.hash(input.readAllBytes());
        }
    }
}
