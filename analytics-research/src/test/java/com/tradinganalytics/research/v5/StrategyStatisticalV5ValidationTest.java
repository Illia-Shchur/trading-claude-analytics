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

/** Direct contract and boundary checks for public statistical APIs. */
final class StrategyStatisticalV5ValidationTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @Test
    void contractSchemaDispatchCoversCoreStatisticalArtifactsAndRejectsUnknownSchemas() {
        String alias = hash("contract-alias");
        ObjectNode head = exposure(alias);
        ObjectNode artifact = artifact(head, alias, "e1", true, .25);
        ObjectNode vectors = vectorInventory(head, List.of("e1"), .25, true, true);
        ObjectNode audit = auditArtifact();
        ObjectNode stress = StrategyStatisticalV5.makeStressDecision(object()
                .put("lineage_sha256", hash("lineage"))
                .put("sourceArtifactSha256", hash("artifact"))
                .put("selectedCandidateId", "candidate-1")
                .put("pass", false));
        ObjectNode portfolioOptions = object().put("lineage_sha256", hash("lineage"))
                .put("sourceArtifactSha256", hash("artifact")).put("pass", false);
        ArrayNode assetDecisions = MAPPER.createArrayNode();
        assetDecisions.addObject().put("asset", "btc").put("pass", false);
        portfolioOptions.set("assetDecisions", assetDecisions);
        ArrayNode returnIncrements = MAPPER.createArrayNode();
        returnIncrements.addObject().put("episode_id", "e1").put("asset", "btc").put("net_r", .25);
        portfolioOptions.set("returnIncrements", returnIncrements);
        ObjectNode portfolio = StrategyStatisticalV5.makePortfolioDecision(portfolioOptions);

        assertThat(StrategyStatisticalV5.validateContractSchema(head)).isTrue();
        ObjectNode inputOptions = object();
        inputOptions.set("exposureHead", head);
        assertThat(StrategyStatisticalV5.validateContractSchema(artifact, inputOptions)).isTrue();
        ObjectNode vectorValidationOptions = object();
        vectorValidationOptions.set("exposureHead", head);
        assertThat(StrategyStatisticalV5.validateContractSchema(vectors, vectorValidationOptions)).isTrue();
        assertThat(StrategyStatisticalV5.validateContractSchema(audit)).isTrue();
        assertThat(StrategyStatisticalV5.validateContractSchema(stress)).isTrue();
        assertThat(StrategyStatisticalV5.validateContractSchema(portfolio)).isTrue();
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(object()
                .put("schema", "strategy-v5-unknown/1")))
                .hasMessage("unknown statistical contract schema strategy-v5-unknown/1");
    }

    @Test
    void vectorInventoryRejectsScopeAndPreDiscoveryCorruption() {
        String alias = hash("vector-alias");
        ObjectNode head = exposure(alias);

        ObjectNode duplicateScope = vectorOptions(head, List.of("e1", "e1"), .1, true, true);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeVectorInventory(duplicateScope))
                .hasMessage("vector inventory requires unique episode IDs");

        ObjectNode missingAlias = vectorOptions(head, List.of("e1"), .1, true, true);
        missingAlias.remove("vectors");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeVectorInventory(missingAlias))
                .hasMessage("vector inventory aliases must exactly equal the exposure head");

        ObjectNode preDiscovery = vectorOptions(head, List.of("e1"), .1, false, false);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeVectorInventory(preDiscovery))
                .hasMessage("vector " + alias + " has an invalid pre-discovery row");

        ObjectNode duplicateRows = vectorOptions(head, List.of("e1", "e2"), 0, true, true);
        ObjectNode duplicateVectors = duplicateRows.with("vectors");
        ArrayNode rows = duplicateVectors.withArray(alias);
        ((ObjectNode) rows.get(1)).put("episode_id", "e1");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeVectorInventory(duplicateRows))
                .hasMessage("vector " + alias + " has duplicate episode IDs");

        ObjectNode valid = vectorOptions(head, List.of("e1", "e2"), .2, true, true);
        ObjectNode inventory = StrategyStatisticalV5.makeVectorInventory(valid);
        ObjectNode tampered = inventory.deepCopy();
        ObjectNode tamperedVectors = tampered.with("vectors");
        ((ObjectNode) tamperedVectors.withArray(alias).get(0)).put("net_r", 9);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateVectorInventory(tampered, head,
                valid.path("episodeIds"))).hasMessage("vector inventory is missing or hash-tampered");
        assertThat(StrategyStatisticalV5.validateVectorInventory(inventory, head,
                valid.path("episodeIds"))).isTrue();
    }

    @Test
    void statisticalArtifactGenesisAndSubsetRulesAreExplicit() {
        ObjectNode emptyHead = StrategyStatisticalV5.makeExposureHead(object()
                .put("hypothesisFamily", "genesis")
                .put("datasetSha256", hash("genesis-data")));
        ObjectNode genesisOptions = lineageOptions(emptyHead);
        genesisOptions.put("genesis", true);
        genesisOptions.putArray("candidates");
        ObjectNode genesisEpisode = genesisOptions.putArray("episodes").addObject();
        episode(genesisEpisode, "genesis-e1", "btc", false, 0);
        ObjectNode genesis = StrategyStatisticalV5.makeStatisticalArtifactSet(genesisOptions);
        assertThat(genesis.path("metadata").path("artifact_role").asText()).isEqualTo("GENESIS");
        assertThat(StrategyStatisticalV5.validateStatisticalArtifactSet(genesis)).isTrue();

        String first = hash("subset-first");
        String second = hash("subset-second");
        ObjectNode fullHead = exposure(first, second);
        ObjectNode subsetOptions = lineageOptions(fullHead);
        subsetOptions.put("allowSubset", true);
        subsetOptions.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", first);
        ObjectNode subsetEpisode = episode(subsetOptions.putArray("episodes").addObject(),
                "subset-e1", "btc", true, .3);
        ObjectNode subsetReturns = subsetEpisode.with("candidate_returns");
        ObjectNode subsetReturn = subsetReturns.putObject("candidate-1");
        subsetReturn.put("net_r", .3).put("traded", true);
        ObjectNode subset = StrategyStatisticalV5.makeStatisticalArtifactSet(subsetOptions);
        ObjectNode subsetValidation = object();
        subsetValidation.set("exposureHead", fullHead);
        subsetValidation.put("allowSubset", true);
        assertThat(StrategyStatisticalV5.validateStatisticalArtifactSet(subset, subsetValidation)).isTrue();
        ObjectNode strictValidation = object();
        strictValidation.set("exposureHead", fullHead);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateStatisticalArtifactSet(subset, strictValidation))
                .hasMessageContaining("subset of the cumulative exposure head");

        ObjectNode missingCandidate = lineageOptions(fullHead);
        missingCandidate.putArray("candidates");
        episode(missingCandidate.putArray("episodes").addObject(), "missing-e1", "btc", false, 0);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(missingCandidate))
                .hasMessage("statistical artifact requires candidates");
    }

    @Test
    void evaluationPhaseContractsNormalizeIntentFormsAndRejectInvalidBindings() {
        ObjectNode signal = signalView("e1");
        ObjectNode train = evaluationOptions(signal, "TRAIN_ONLY", "2026-01-01T00:00:00Z", null);
        train.putObject("signalIntentVector").put("e1", true);
        ObjectNode trainResult = StrategyStatisticalV5.makeEvaluationArtifact(train);
        assertThat(trainResult.path("weighting").asText()).isEqualTo("TRAIN_HALF_LIFE");
        assertThat(trainResult.path("fit_cutoff").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(trainResult.path("evaluation_cutoff").asText()).isEqualTo("2026-01-01T00:00:00Z");

        ObjectNode inner = evaluationOptions(signal, "INNER_VALIDATION", "2026-03-01T00:00:00Z",
                "2026-01-01T00:00:00Z");
        inner.putArray("signalIntentVector").addObject().put("episode_id", "e1").put("intent", 1.0);
        ObjectNode innerResult = StrategyStatisticalV5.makeEvaluationArtifact(inner);
        assertThat(innerResult.path("weighting").asText()).isEqualTo("UNWEIGHTED_VALIDATION");
        assertThat(innerResult.path("evaluation_cutoff").asText()).isEqualTo("2026-03-01T00:00:00Z");
        assertThat(innerResult.path("signal_intent_vector").get(0).path("intent").asDouble()).isEqualTo(1.0);

        ObjectNode stripped = object();
        stripped.putObject("execution").put("threshold", 2);
        stripped.putObject("diagnostic").put("ignored", true);
        ObjectNode withDefinition = evaluationOptions(signal, "OUTER_OOS", null, null);
        withDefinition.set("candidateDefinition", stripped);
        withDefinition.putArray("signalIntentVector").addObject().put("episode_id", "e1").put("intent", false);
        ObjectNode result = StrategyStatisticalV5.makeEvaluationArtifact(withDefinition);
        assertThat(result.path("candidate_definition").path("execution").path("threshold").asInt()).isEqualTo(2);
        JsonNode effective = StrategyStatisticalV5.effectiveExecutionBehavior(stripped);
        assertThat(effective.path("execution").path("threshold").asInt()).isEqualTo(2);
        assertThat(effective.has("diagnostic")).isFalse();

        ObjectNode badOuter = evaluationOptions(signal, "OUTER_OOS", null, null);
        badOuter.putArray("signalIntentVector").addObject().put("episode_id", "e1").put("intent", true);
        badOuter.put("fitCutoff", "2026-01-01T00:00:00Z");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(badOuter))
                .hasMessage("evaluation phase/cutoff weighting contract is invalid");

        ObjectNode badIntent = evaluationOptions(signal, "OUTER_OOS", null, null);
        badIntent.putArray("signalIntentVector").addObject().put("episode_id", "e1")
                .put("intent", true).put("net_r", .4);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeEvaluationArtifact(badIntent))
                .hasMessage("signal intent vector contains outcome fields");
    }

    @Test
    void hardPolicyAndFeasibilityCoverSnakeCaseScalesMissingMetricsAndBoundaries() {
        ObjectNode policy = policy("violation_scales");
        assertThat(StrategyStatisticalV5.requireFrozenHardPolicy(policy)).isEqualTo(policy);

        ObjectNode missing = StrategyStatisticalV5.hardFeasible(object(), policy);
        assertThat(missing.path("feasible").asBoolean()).isFalse();
        assertThat(missing.path("violations").toString()).contains("MISSING_TRADED_COUNT");
        assertThat(missing.path("violation_details").path("capacity").asDouble()).isEqualTo(1.0);

        ObjectNode metrics = object().put("traded_count", 3).put("expectancy_r", .1)
                .put("cost_r", .1).put("coverage_fraction", 1).put("capacity_pass", true)
                .put("max_drawdown_r", -1).put("profit_factor", 2);
        assertThat(StrategyStatisticalV5.hardFeasible(metrics, policy).path("feasible").asBoolean()).isTrue();

        ObjectNode overCoverage = metrics.deepCopy().put("coverage_fraction", 1.01);
        assertThat(StrategyStatisticalV5.hardFeasible(overCoverage, policy).path("feasible").asBoolean()).isFalse();
        assertThatThrownBy(() -> StrategyStatisticalV5.requireFrozenHardPolicy(policy.deepCopy()
                .put("requireCapacityPass", false))).hasMessageContaining("capacity_pass=true");
        ObjectNode badScale = policy.deepCopy();
        badScale.with("violation_scales").put("coverage", 0);
        assertThatThrownBy(() -> StrategyStatisticalV5.requireFrozenHardPolicy(badScale))
                .hasMessageContaining("invalid coverage violation normalization scale");
    }

    @Test
    void marketClustersAndPboRejectAmbiguousIntervals() {
        ArrayNode episodes = MAPPER.createArrayNode();
        clusterEpisode(episodes.addObject(), "btc-a", "btc", "2026-01-01T00:00:00Z", "2026-01-03T00:00:00Z");
        clusterEpisode(episodes.addObject(), "btc-b", "btc", "2026-01-02T00:00:00Z", "2026-01-04T00:00:00Z");
        clusterEpisode(episodes.addObject(), "eth-c", "eth", "2026-01-10T00:00:00Z", "2026-01-11T00:00:00Z");
        assertThat(StrategyStatisticalV5.marketEpisodeClusterDiagnostics(episodes)).hasSize(3);
        assertThat(StrategyStatisticalV5.collapseMarketEpisodeRows(MAPPER.createArrayNode(), episodes)).isEmpty();

        ArrayNode duplicate = episodes.deepCopy();
        ((ObjectNode) duplicate.get(1)).put("episode_id", "btc-a");
        assertThatThrownBy(() -> StrategyStatisticalV5.marketEpisodeClusterDiagnostics(duplicate))
                .hasMessage("market cluster identity received duplicate episode IDs");
        ArrayNode backwards = episodes.deepCopy();
        ((ObjectNode) backwards.get(0)).put("resolution_time", "2025-12-31T00:00:00Z");
        assertThatThrownBy(() -> StrategyStatisticalV5.marketEpisodeClusterDiagnostics(backwards))
                .hasMessage("market cluster episode btc-a has invalid interval");
        ArrayNode wrongIdentity = episodes.deepCopy();
        ((ObjectNode) wrongIdentity.get(0)).put("market_cluster_id", "forged");
        assertThatThrownBy(() -> StrategyStatisticalV5.marketEpisodeClusters(wrongIdentity))
                .hasMessage("episode btc-a has a non-canonical market cluster identity");

        ArrayNode tooFewFolds = MAPPER.createArrayNode();
        for (int i = 0; i < 3; i++) tooFewFolds.addObject().putObject("candidate_means").put("a", 1);
        assertThat(StrategyStatisticalV5.pboFromFolds(tooFewFolds, "a")).isNull();
        ArrayNode overlapping = MAPPER.createArrayNode();
        for (int i = 0; i < 4; i++) {
            overlapping.addObject().putObject("candidate_means").put("a", .2).put("b", .1);
            ((ObjectNode) overlapping.get(i)).put("test_start", "2026-01-0" + (i + 1) + "T00:00:00Z")
                    .put("test_end", "2026-01-10T00:00:00Z");
        }
        ObjectNode pboOptions = object().put("requireTimestamps", true).put("purgeDays", 0).put("embargoDays", 0);
        assertThatThrownBy(() -> StrategyStatisticalV5.pboFromFolds(overlapping, "a", pboOptions))
                .hasMessage("PBO fold test intervals overlap");
    }

    private static ObjectNode exposure(String... aliases) {
        ObjectNode options = object().put("hypothesisFamily", "validation-tests")
                .put("datasetSha256", hash("dataset"));
        ArrayNode entries = options.putArray("entries");
        for (String alias : aliases) entries.addObject().put("behavior_sha256", alias)
                .put("dataset_sha256", hash("dataset"));
        return StrategyStatisticalV5.makeExposureHead(options);
    }

    private static ObjectNode artifact(ObjectNode head, String alias, String id, boolean eligible, double net) {
        ObjectNode options = lineageOptions(head);
        options.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", alias);
        ObjectNode row = episode(options.putArray("episodes").addObject(), id, "btc", eligible, net);
        ObjectNode returns = row.with("candidate_returns");
        ObjectNode returnRow = returns.putObject("candidate-1");
        returnRow.put("net_r", net).put("traded", eligible && net != 0);
        return StrategyStatisticalV5.makeStatisticalArtifactSet(options);
    }

    private static ObjectNode lineageOptions(ObjectNode head) {
        ObjectNode options = object();
        options.set("exposureHead", head);
        ObjectNode lineage = options.putObject("lineage");
        for (String key : List.of("dataset_sha256", "candidate_set_sha256", "feature_set_sha256",
                "label_set_sha256", "execution_set_sha256")) lineage.put(key, hash("lineage-" + key));
        return options;
    }

    private static ObjectNode vectorInventory(ObjectNode head, List<String> ids, double net,
            boolean eligible, boolean traded) {
        return StrategyStatisticalV5.makeVectorInventory(vectorOptions(head, ids, net, eligible, traded));
    }

    private static ObjectNode vectorOptions(ObjectNode head, List<String> ids, double net,
            boolean eligible, boolean traded) {
        String alias = head.path("entries").get(0).path("behavior_sha256").asText();
        ObjectNode options = object();
        options.set("exposureHead", head);
        ArrayNode episodeIds = options.putArray("episodeIds"); ids.forEach(episodeIds::add);
        ArrayNode rows = options.putObject("vectors").putArray(alias);
        for (String id : ids) rows.addObject().put("episode_id", id).put("net_r", net)
                .put("traded", traded).put("eligible", eligible);
        return options;
    }

    private static ObjectNode signalView(String id) {
        ObjectNode signal = object().put("schema", "strategy-v5-statistical-signal-view/1")
                .put("source_artifact_sha256", hash("source"));
        signal.putArray("episodes").addObject().put("episode_id", id).put("asset", "btc");
        return signal;
    }

    private static ObjectNode evaluationOptions(ObjectNode signal, String phase, String cutoff, String fitCutoff) {
        ObjectNode options = object();
        options.set("signalArtifact", signal);
        options.putArray("episodeIds").add("e1");
        options.put("phase", phase);
        if (cutoff == null) options.putNull("cutoff"); else options.put("cutoff", cutoff);
        if (fitCutoff != null) options.put("fitCutoff", fitCutoff);
        options.putObject("candidateReturns").putObject("e1").put("net_r", .25).put("traded", true);
        options.putObject("metrics").put("cost_r", .01).put("coverage_fraction", 1)
                .put("capacity_pass", true).put("max_drawdown_r", 0).put("profit_factor", 2)
                .put("turnover", 1).put("complexity", 1);
        return options;
    }

    private static ObjectNode policy(String scalesKey) {
        ObjectNode policy = object().put("minEpisodes", 3).put("minExpectancy", 0)
                .put("minProfitFactor", 1).put("maxDrawdownR", 2).put("maxCostR", .2)
                .put("minCoverage", .9).put("requireCapacityPass", true);
        ObjectNode scales = policy.putObject(scalesKey);
        for (String key : List.of("episodes", "expectancy", "drawdown", "costs", "coverage",
                "capacity", "profit_factor")) scales.put(key, 1);
        return policy;
    }

    private static ObjectNode auditArtifact() {
        ObjectNode value = object().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("audit"))
                .put("version", 1).put("fail_closed_missing_inputs", true).put("pass", false)
                .put("decision", "REJECTED").put("independent_opportunity_count", 0)
                .put("independent_trade_count", 0).put("market_cluster_inventory_sha256", hash("clusters"));
        ObjectNode gates = value.putObject("gates");
        for (String gate : List.of("hard_metrics", "baseline_comparison", "bootstrap_p20_positive",
                "weighted_bootstrap_p20_positive", "max_statistic", "search_adjusted_expectancy_positive",
                "dsr", "pbo", "minimum_independent_episodes", "recent_oos_positive", "earlier_blocks",
                "positive_years", "positive_outer_folds", "plateau", "neighbour_fraction", "seed_stability",
                "null_controls", "stress_ablation", "asset_decisions", "portfolio")) gates.put(gate, false);
        return StrategyStatisticalV5.withHash(value);
    }

    private static ObjectNode episode(ObjectNode row, String id, String asset, boolean eligible, double net) {
        row.put("episode_id", id).put("asset", asset)
                .put("decision_time", "2026-01-01T00:00:00Z")
                .put("resolution_time", "2026-01-02T00:00:00Z")
                .put("eligible", eligible).putObject("candidate_returns");
        return row;
    }

    private static void clusterEpisode(ObjectNode row, String id, String asset, String decision, String resolution) {
        row.put("episode_id", id).put("asset", asset).put("decision_time", decision)
                .put("resolution_time", resolution).put("eligible", true);
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }

    private static String hash(String value) { return JsonHashes.sha256(value); }
}
