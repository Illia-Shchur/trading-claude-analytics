package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Direct contract and boundary checks for public statistical APIs. */
final class StrategyStatisticalV5ValidationTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @TempDir
    Path temporary;

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

    @Test
    void physicalRunnerContractRejectsEveryUntrustedBinding() {
        ObjectNode valid = physicalRunnerContract();
        assertThat(StrategyStatisticalV5.validateContractSchema(valid)).isTrue();

        List<String> booleans = List.of("recomputes_label_execution", "reruns_nested_selection",
                "worker_backed", "physical_feature_label_execution");
        for (String field : booleans) {
            ObjectNode invalid = valid.deepCopy().put(field, false);
            assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(invalid))
                    .hasMessage("physical null runner contract is incomplete or not factory-bound");
        }
        Map<String, Object> scalarFailures = new LinkedHashMap<>();
        scalarFailures.put("version", 2);
        scalarFailures.put("factory", "CALLER");
        scalarFailures.put("integration_status", "FIXTURE");
        scalarFailures.put("code_sha256", "bad");
        scalarFailures.put("feature_artifact_sha256", "bad");
        scalarFailures.put("label_artifact_sha256", "bad");
        scalarFailures.put("execution_artifact_sha256", "bad");
        for (Map.Entry<String, Object> entry : scalarFailures.entrySet()) {
            ObjectNode invalid = valid.deepCopy();
            if (entry.getValue() instanceof Integer number) invalid.put(entry.getKey(), number);
            else invalid.put(entry.getKey(), (String) entry.getValue());
            assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(invalid))
                    .hasMessage("physical null runner contract is incomplete or not factory-bound");
        }
        ObjectNode missingMethod = valid.deepCopy();
        missingMethod.putArray("methods").add("block_permuted_labels");
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(missingMethod))
                .hasMessage("physical null runner contract is incomplete or not factory-bound");
        ObjectNode badSource = valid.deepCopy().put("source_manifest_sha256", "bad");
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(badSource))
                .hasMessage("physical null source manifest must be a SHA-256 hash");
    }

    @Test
    void physicalSelectionReferencesBindBytesContentAndSourceManifest(@TempDir Path root) throws Exception {
        byte[] bytes = "reference-payload".getBytes(StandardCharsets.UTF_8);
        Path payload = root.resolve("payload.bin");
        Files.write(payload, bytes);
        String content = hash("content-lineage");
        String source = hash("source-manifest");
        ObjectNode valid = physicalSelection(payload, content, source);
        assertThat(StrategyStatisticalV5.validateContractSchema(valid)).isTrue();

        ObjectNode missingPath = valid.deepCopy();
        ((ObjectNode) missingPath.get("transformed_label_ref")).remove("path");
        final ObjectNode missingPathCase = StrategyStatisticalV5.withHash(missingPath);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(missingPathCase))
                .hasMessage("transformed label is not a reopenable physical artifact reference");

        ObjectNode tamperedBytes = valid.deepCopy();
        ((ObjectNode) tamperedBytes.get("trace_ref")).put("byte_sha256", hash("other-bytes"));
        final ObjectNode tamperedBytesCase = StrategyStatisticalV5.withHash(tamperedBytes);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(tamperedBytesCase))
                .hasMessage("selection trace bytes are missing or tampered");

        ObjectNode wrongContent = valid.deepCopy();
        ((ObjectNode) wrongContent.get("recomputed_outcome_ref")).put("content_sha256", hash("other-content"));
        final ObjectNode wrongContentCase = StrategyStatisticalV5.withHash(wrongContent);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(wrongContentCase))
                .hasMessage("recomputed outcome content commitment differs from its reopenable reference");

        ObjectNode badCheckpoint = valid.deepCopy();
        ((ObjectNode) badCheckpoint.get("checkpoint_ref")).put("path", root.resolve("missing.bin").toString());
        final ObjectNode badCheckpointCase = StrategyStatisticalV5.withHash(badCheckpoint);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(badCheckpointCase))
                .hasMessageContaining("iteration checkpoint cannot be reopened:");

        // Cross-contract source binding is checked by the physical runner path, which supplies
        // the runner's source hash; schema-only validation treats this optional field as self-bound.
        ObjectNode noSource = valid.deepCopy();
        noSource.remove("source_manifest_sha256");
        noSource = StrategyStatisticalV5.withHash(noSource);
        assertThat(StrategyStatisticalV5.validateContractSchema(noSource)).isTrue();
    }

    @Test
    void statisticalMathAndGeneSpaceBoundariesStayFiniteAndFailClosed() {
        assertThat(StrategyStatisticalV5.drawdown(List.of())).isEqualTo(0);
        assertThat(StrategyStatisticalV5.drawdown(List.of(4, 3, 2))).isEqualTo(0);
        assertThat(StrategyStatisticalV5.drawdown(List.of(2, -3, 4))).isEqualTo(-3);

        ArrayNode constant = MAPPER.createArrayNode();
        for (double value : List.of(1d, 1d, 1d)) constant.addObject().put("value", value);
        assertThat(StrategyStatisticalV5.deflatedSharpe(constant, 4)).isNull();
        assertThat(StrategyStatisticalV5.deflatedSharpe(MAPPER.createArrayNode(), 4)).isNull();

        ArrayNode autocorrelated = MAPPER.createArrayNode();
        for (int i = 0; i < 12; i++) autocorrelated.addObject().put("value", i);
        assertThat(StrategyStatisticalV5.deflatedSharpe(autocorrelated, 4)
                .path("reason").asText()).isEqualTo("MATERIAL_AUTOCORRELATION_UNCORRECTED");

        ObjectNode continuous = object();
        continuous.putArray("genes").addObject().put("name", "threshold").put("type", "continuous")
                .put("min", 0).put("max", 10).put("step", 2).put("default", 4);
        ObjectNode categorical = continuous.withArray("genes").addObject().put("name", "side")
                .put("type", "categorical");
        categorical.putArray("values").add("long").add("short");
        categorical.put("default", "long");
        ObjectNode ordered = continuous.withArray("genes").addObject().put("name", "window")
                .put("type", "ordered-discrete");
        ordered.putArray("values").add(1).add(3).add(5);
        ordered.put("default", 3);
        ObjectNode structural = continuous.withArray("genes").addObject().put("name", "mode")
                .put("type", "structural");
        ArrayNode structuralValues = structural.putArray("values");
        structuralValues.addObject().put("kind", "a");
        structuralValues.addObject().put("kind", "b");
        structural.putObject("default").put("kind", "a");
        ObjectNode space = StrategyStatisticalV5.withHash(continuous);
        assertThat(StrategyStatisticalV5.enumerateDirectNeighbours(space,
                object().put("threshold", 4).put("side", "long").put("window", 3)
                        .putObject("mode").put("kind", "a"))).isNotEmpty();

        ObjectNode badContinuous = object();
        badContinuous.putArray("genes").addObject().put("name", "x").put("type", "continuous")
                .put("min", 1).put("max", 1);
        assertThatThrownBy(() -> StrategyStatisticalV5.enumerateDirectNeighbours(badContinuous, object()))
                .hasMessage("x range is invalid");
        ObjectNode badOrdered = object();
        badOrdered.putArray("genes").addObject().put("name", "x").put("type", "ordered-discrete")
                .putArray("values").add(1).add(1);
        ((ObjectNode) badOrdered.path("genes").get(0)).put("default", 1);
        assertThatThrownBy(() -> StrategyStatisticalV5.enumerateDirectNeighbours(badOrdered, object()))
                .hasMessage("x values must be unique");
    }

    @Test
    void artifactRowsRejectMalformedIdentityAvailabilityAndReturnStates() {
        String alias = hash("row-validation-alias");
        ObjectNode head = exposure(alias);

        ObjectNode nonObjectCandidate = lineageOptions(head);
        nonObjectCandidate.putArray("candidates").add("candidate-1");
        episode(nonObjectCandidate.putArray("episodes").addObject(), "e1", "btc", false, 0);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(nonObjectCandidate))
                .hasMessage("candidate 0 is not an object");

        ObjectNode emptyCandidateId = artifactInput(head, alias);
        ((ObjectNode) emptyCandidateId.path("candidates").get(0)).put("candidate_id", "");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(emptyCandidateId))
                .hasMessage("candidate IDs must be unique strings");

        ObjectNode duplicateCandidateIds = artifactInput(head, alias);
        duplicateCandidateIds.withArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", hash("second-alias"));
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(duplicateCandidateIds))
                .hasMessage("candidate IDs must be unique strings");

        ObjectNode duplicateBehaviors = artifactInput(head, alias);
        duplicateBehaviors.withArray("candidates").addObject().put("candidate_id", "candidate-2")
                .put("behavior_sha256", alias);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(duplicateBehaviors))
                .hasMessage("candidate behavior aliases must be unique in the current candidate set");

        ObjectNode absentBehavior = artifactInput(head, alias);
        ((ObjectNode) absentBehavior.path("candidates").get(0)).put("behavior_sha256", hash("absent"));
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(absentBehavior))
                .hasMessageContaining("is absent from the verified exposure head");

        ObjectNode noEpisodes = artifactInput(head, alias);
        noEpisodes.putArray("episodes");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(noEpisodes))
                .hasMessage("statistical artifact requires canonical episode records");

        ObjectNode nonObjectEpisode = artifactInput(head, alias);
        nonObjectEpisode.putArray("episodes").add("e1");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(nonObjectEpisode))
                .hasMessage("episode 0 is not an object");

        ObjectNode uppercaseAsset = artifactInput(head, alias);
        ((ObjectNode) uppercaseAsset.path("episodes").get(0)).put("asset", "BTC");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(uppercaseAsset))
                .hasMessage("episode e1.asset must be lowercase canonical crypto");

        ObjectNode unsupportedAsset = artifactInput(head, alias);
        ((ObjectNode) unsupportedAsset.path("episodes").get(0)).put("asset", "gold");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(unsupportedAsset))
                .hasMessage("asset gold is outside the crypto universe");

        ObjectNode malformedDecision = artifactInput(head, alias);
        ((ObjectNode) malformedDecision.path("episodes").get(0)).put("decision_time", "not-a-time");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(malformedDecision))
                .hasMessage("episode e1.decision_time must be an ISO-8601 UTC timestamp");

        ObjectNode malformedAvailability = artifactInput(head, alias);
        ((ObjectNode) malformedAvailability.path("episodes").get(0))
                .put("label_availability_time", "not-a-time");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(malformedAvailability))
                .hasMessage("episode e1.label_availability_time must be an ISO-8601 UTC timestamp");

        ObjectNode reversedInterval = artifactInput(head, alias);
        ((ObjectNode) reversedInterval.path("episodes").get(0))
                .put("resolution_time", "2025-12-31T00:00:00Z");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(reversedInterval))
                .hasMessage("episode e1 resolution must follow decision");

        ObjectNode nonBooleanEligible = artifactInput(head, alias);
        ((ObjectNode) nonBooleanEligible.path("episodes").get(0)).put("eligible", "true");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(nonBooleanEligible))
                .hasMessage("episode e1.eligible must be boolean");

        ObjectNode nonObjectReturns = artifactInput(head, alias);
        ((ObjectNode) nonObjectReturns.path("episodes").get(0)).putArray("candidate_returns");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(nonObjectReturns))
                .hasMessage("episode e1 candidate_returns must be an object");

        ObjectNode missingReturn = artifactInput(head, alias);
        ((ObjectNode) missingReturn.path("episodes").get(0)).putObject("candidate_returns");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(missingReturn))
                .hasMessage("episode e1 candidate return inventory is incomplete or has extras");

        ObjectNode nonObjectReturn = artifactInput(head, alias);
        ((ObjectNode) nonObjectReturn.path("episodes").get(0)).with("candidate_returns")
                .put("candidate-1", "return");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(nonObjectReturn))
                .hasMessage("episode e1/candidate-1 return record is incomplete");

        ObjectNode nonBooleanTraded = artifactInput(head, alias);
        ((ObjectNode) nonBooleanTraded.path("episodes").get(0)).with("candidate_returns")
                .with("candidate-1").put("traded", "yes");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(nonBooleanTraded))
                .hasMessage("episode e1/candidate-1 return record is incomplete");

        ObjectNode ineligibleReturn = artifactInput(head, alias);
        ObjectNode ineligibleRow = (ObjectNode) ineligibleReturn.path("episodes").get(0);
        ineligibleRow.with("candidate_returns").with("candidate-1").put("net_r", .1).put("traded", false);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(ineligibleReturn))
                .hasMessage("ineligible episode e1 must be an internal zero");

        ObjectNode untradedReturn = artifactInput(head, alias);
        ObjectNode untradedRow = (ObjectNode) untradedReturn.path("episodes").get(0);
        untradedRow.put("eligible", true);
        untradedRow.with("candidate_returns").with("candidate-1").put("net_r", .1).put("traded", false);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(untradedReturn))
                .hasMessage("untraded eligible episode e1 must have zero return");
    }

    @Test
    void auditStressPortfolioJournalAndCheckpointContractsFailClosed() {
        ObjectNode audit = auditArtifact();
        ObjectNode badAuditSemantics = audit.deepCopy().put("fail_closed_missing_inputs", false);
        assertAuditFailure(badAuditSemantics, "statistical audit semantic fields are missing or activation was attempted");
        ObjectNode badAuditGates = audit.deepCopy();
        badAuditGates.set("gates", MAPPER.createArrayNode());
        assertAuditFailure(badAuditGates, "statistical audit semantic fields are missing or activation was attempted");
        ObjectNode badAuditCounts = audit.deepCopy().put("independent_opportunity_count", "many");
        assertAuditFailure(badAuditCounts, "statistical audit is missing the canonical independent market-cluster inventory");
        ObjectNode missingGate = audit.deepCopy();
        missingGate.with("gates").remove("pbo");
        assertAuditFailure(missingGate, "statistical audit gate pbo is missing");
        ObjectNode passingRejected = audit.deepCopy().put("pass", true);
        assertAuditFailure(passingRejected, "only SHADOW may be emitted by a passing statistical audit");
        ObjectNode active = audit.deepCopy().put("decision", "ACTIVE");
        assertAuditFailure(active, "statistical audit semantic fields are missing or activation was attempted");

        ObjectNode stress = StrategyStatisticalV5.makeStressDecision(object().put("lineage_sha256", hash("lineage"))
                .put("sourceArtifactSha256", hash("source")).put("selectedCandidateId", "candidate-1")
                .put("pass", false));
        ObjectNode badStressPass = stress.deepCopy().put("pass", "false");
        assertContractFailure(badStressPass, "stress decision is missing or not lineage-bound");
        ObjectNode badStressProvenance = stress.deepCopy().put("provenance", "FIXTURE");
        assertContractFailure(badStressProvenance, "stress decision is missing or not lineage-bound");
        ObjectNode badStressScenario = stress.deepCopy();
        ((ObjectNode) badStressScenario.withArray("scenarios").get(0)).put("pass", "false");
        assertContractFailure(badStressScenario, "stress scenario pass is invalid");
        ObjectNode badStressDigest = stress.deepCopy();
        ((ObjectNode) badStressDigest.withArray("scenarios").get(0)).put("digest", "bad");
        assertContractFailure(badStressDigest, "stress scenario digest must be a SHA-256 hash");
        ObjectNode incompleteStress = stress.deepCopy();
        incompleteStress.withArray("scenarios").remove(0);
        assertContractFailure(incompleteStress, "stress decision is missing the authoritative stress scenario inventory");
        ObjectNode badStressSource = stress.deepCopy().put("source_artifact_sha256", "bad");
        assertContractFailure(badStressSource, "stress.source_artifact_sha256 must be a SHA-256 hash");

        ObjectNode portfolio = portfolioDecision();
        ObjectNode badPortfolioPass = portfolio.deepCopy().put("pass", "false");
        assertContractFailure(badPortfolioPass, "portfolio decision is missing the authoritative portfolio recomputation contract");
        ObjectNode badPortfolioProvenance = portfolio.deepCopy().put("provenance", "FIXTURE");
        assertContractFailure(badPortfolioProvenance, "portfolio decision is missing the authoritative portfolio recomputation contract");
        ObjectNode emptyDecisions = portfolio.deepCopy();
        emptyDecisions.putArray("asset_decisions");
        assertContractFailure(emptyDecisions, "portfolio decision is missing the authoritative portfolio recomputation contract");
        ObjectNode badDecisionHash = portfolio.deepCopy().put("asset_decisions_sha256", hash("wrong"));
        assertContractFailure(badDecisionHash, "portfolio decision is missing the authoritative portfolio recomputation contract");
        ObjectNode badRisk = portfolio.deepCopy().put("risk_digest_sha256", "bad");
        assertContractFailure(badRisk, "portfolio.risk_digest_sha256 must be a SHA-256 hash");
        ObjectNode badPortfolioSource = portfolio.deepCopy().put("source_artifact_sha256", "bad");
        assertContractFailure(badPortfolioSource, "portfolio.source_artifact_sha256 must be a SHA-256 hash");

        ObjectNode journal = registryJournal();
        assertThat(StrategyStatisticalV5.validateContractSchema(journal)).isTrue();
        ObjectNode badJournalStatus = StrategyStatisticalV5.withHash(journal.deepCopy().put("status", "ABORTED"));
        assertContractFailure(badJournalStatus, "registry journal contract is invalid");
        ObjectNode badJournalHead = journal.deepCopy();
        badJournalHead.set("next_head", MAPPER.createArrayNode());
        badJournalHead = StrategyStatisticalV5.withHash(badJournalHead);
        assertContractFailure(badJournalHead, "registry journal contract is invalid");
        ObjectNode badJournalHash = journal.deepCopy().put("next_head_sha256", hash("wrong"));
        badJournalHash = StrategyStatisticalV5.withHash(badJournalHash);
        assertContractFailure(badJournalHash, "registry journal contract is invalid");

        ObjectNode checkpoint = checkpointContract();
        assertThat(StrategyStatisticalV5.validateContractSchema(checkpoint)).isTrue();
        ObjectNode badCheckpointStatus = StrategyStatisticalV5.withHash(checkpoint.deepCopy()
                .put("checkpoint_status", "PAUSED"));
        assertContractFailure(badCheckpointStatus, "genetic checkpoint contract is invalid");
        ObjectNode badCheckpointIndex = StrategyStatisticalV5.withHash(checkpoint.deepCopy().put("seed_index", -1));
        assertContractFailure(badCheckpointIndex, "genetic checkpoint contract is invalid");
        ObjectNode badCheckpointGeneration = StrategyStatisticalV5.withHash(checkpoint.deepCopy()
                .put("generation", "one"));
        assertContractFailure(badCheckpointGeneration, "genetic checkpoint contract is invalid");
        ObjectNode badCheckpointState = StrategyStatisticalV5.withHash(checkpoint.deepCopy()
                .put("state_sha256", hash("wrong-state")));
        assertContractFailure(badCheckpointState, "genetic checkpoint contract is invalid");
    }

    @Test
    void hardAcceptanceThresholdsAndDominanceOrderingCoverBothSides() {
        ObjectNode validPolicy = policy("violation_scales");
        for (String required : List.of("minEpisodes", "minExpectancy", "minProfitFactor", "maxDrawdownR",
                "maxCostR", "minCoverage", "requireCapacityPass", "violation_scales")) {
            ObjectNode missing = validPolicy.deepCopy();
            missing.remove(required);
            assertThatThrownBy(() -> StrategyStatisticalV5.requireFrozenHardPolicy(missing))
                    .hasMessageContaining("hard acceptance policy");
        }
        ObjectNode invalidThreshold = validPolicy.deepCopy().put("minEpisodes", 0);
        assertThatThrownBy(() -> StrategyStatisticalV5.requireFrozenHardPolicy(invalidThreshold))
                .hasMessageContaining("invalid frozen thresholds");
        ObjectNode invalidCoverage = validPolicy.deepCopy().put("minCoverage", 1.1);
        assertThatThrownBy(() -> StrategyStatisticalV5.requireFrozenHardPolicy(invalidCoverage))
                .hasMessageContaining("invalid frozen thresholds");
        ObjectNode snakeCase = validPolicy.deepCopy();
        snakeCase.remove("violation_scales");
        snakeCase.putObject("violationScales").put("episodes", 1).put("expectancy", 1)
                .put("drawdown", 1).put("costs", 1).put("coverage", 1).put("capacity", 1)
                .put("profit_factor", 1);
        assertThat(StrategyStatisticalV5.requireFrozenHardPolicy(snakeCase)).isEqualTo(snakeCase);

        ObjectNode base = object().put("traded_count", 3).put("expectancy_r", .1).put("cost_r", .1)
                .put("coverage_fraction", 1).put("capacity_pass", true).put("max_drawdown_r", -1)
                .put("profit_factor", 2);
        Map<String, Object> failingMetrics = new LinkedHashMap<>();
        failingMetrics.put("traded_count", 2);
        failingMetrics.put("expectancy_r", 0);
        failingMetrics.put("cost_r", .3);
        failingMetrics.put("coverage_fraction", .89);
        failingMetrics.put("max_drawdown_r", -3);
        failingMetrics.put("profit_factor", .5);
        for (Map.Entry<String, Object> entry : failingMetrics.entrySet()) {
            ObjectNode metrics = base.deepCopy();
            if (entry.getValue() instanceof Integer number) metrics.put(entry.getKey(), number);
            else metrics.put(entry.getKey(), ((Number) entry.getValue()).doubleValue());
            assertThat(StrategyStatisticalV5.hardFeasible(metrics, validPolicy)
                    .path("feasible").asBoolean()).isFalse();
        }
        ObjectNode noCapacity = base.deepCopy().put("capacity_pass", false);
        assertThat(StrategyStatisticalV5.hardFeasible(noCapacity, validPolicy)
                .path("violations").toString()).contains("CAPACITY");
        ObjectNode highCoverage = base.deepCopy().put("coverage_fraction", 1.01);
        assertThat(StrategyStatisticalV5.hardFeasible(highCoverage, validPolicy)
                .path("violations").toString()).contains("COVERAGE");

        ObjectNode feasible = base.deepCopy().put("feasible", true).put("total_violation", 0);
        feasible.set("objectives", MAPPER.valueToTree(List.of(3, 2, 1)));
        ObjectNode infeasible = object().put("feasible", false).put("total_violation", 4)
                .set("violation_details", object().put("episodes", 4));
        assertThat(StrategyStatisticalV5.constrainedDominates(feasible, infeasible)).isTrue();
        assertThat(StrategyStatisticalV5.constrainedDominates(infeasible, feasible)).isFalse();
        ObjectNode lessViolation = infeasible.deepCopy().put("total_violation", 2);
        assertThat(StrategyStatisticalV5.constrainedDominates(lessViolation, infeasible)).isTrue();
        ObjectNode sameViolation = infeasible.deepCopy();
        assertThat(StrategyStatisticalV5.constrainedDominates(sameViolation, infeasible)).isFalse();
        ObjectNode weaker = feasible.deepCopy();
        weaker.set("objectives", MAPPER.valueToTree(List.of(2, 3, 1)));
        assertThat(StrategyStatisticalV5.constrainedDominates(feasible, weaker)).isFalse();
        ObjectNode equal = feasible.deepCopy();
        assertThat(StrategyStatisticalV5.constrainedDominates(feasible, equal)).isFalse();
    }

    @Test
    void aggregateAssetDecisionTracksFoldYearsProcedureAndStressGates() {
        ObjectNode empty = StrategyStatisticalV5.aggregateAssetDecision(MAPPER.createArrayNode());
        assertThat(empty.path("reason").asText()).isEqualTo("MISSING_ASSET_FOLDS");

        ArrayNode rows = MAPPER.createArrayNode();
        rows.add(assetFold("2024-01-01T00:00:00Z", .4, true, true));
        rows.add(assetFold("2025-01-01T00:00:00Z", -.2, true, false));
        ObjectNode required = object().put("minEpisodes", 1).put("minPositiveFolds", 1)
                .put("minPositiveYears", 1).put("minTradesPerYear", 1).put("bootstrapIterations", 8)
                .put("halfLifeMonths", 18);
        ObjectNode aggregate = StrategyStatisticalV5.aggregateAssetDecision(rows, required);
        assertThat(aggregate.path("fold_summary").path("fold_count").asInt()).isEqualTo(2);
        assertThat(aggregate.path("fold_summary").path("year_stats")).hasSize(2);
        assertThat(aggregate.path("asset_gates").path("procedure_validation").asBoolean()).isTrue();
        assertThat(aggregate.path("asset_gates").path("stress_survival").asBoolean()).isFalse();

        ObjectNode evidence = ((ObjectNode) rows.deepCopy().get(0)).deepCopy();
        evidence.putObject("procedure_validation").put("pass", false);
        evidence.putObject("pbo").put("source_phase", "WRONG").put("outer_oos_bound", true)
                .put("candidate_count", 1);
        evidence.put("pbo_pass", false);
        ArrayNode withEvidence = MAPPER.createArrayNode().add(evidence).add(rows.get(1));
        ObjectNode evidenceAggregate = StrategyStatisticalV5.aggregateAssetDecision(withEvidence, required);
        assertThat(evidenceAggregate.path("asset_gates").path("procedure_validation").asBoolean()).isFalse();
        assertThat(evidenceAggregate.path("asset_gates").path("pbo").asBoolean()).isFalse();

        ObjectNode authoritative = required.deepCopy().put("mode", "AUTHORITATIVE");
        authoritative.set("constraints", validPolicyForAggregate());
        assertThat(StrategyStatisticalV5.aggregateAssetDecision(rows, authoritative)
                .path("asset_gates").path("hard_metrics").asBoolean()).isFalse();
    }

    @Test
    void nullReplayAndNullControlContractsCoverAllDeterministicMethods() {
        String alias = hash("null-validation-alias");
        ObjectNode head = exposure(alias);
        ObjectNode artifact = artifact(head, alias, "null-e1", true, .25);
        ObjectNode selectionBudget = selectionBudget();
        for (String method : List.of("block_permuted_labels", "timestamp_shifted_outcomes",
                "frequency_matched_random_intents", "winners_curse_selection")) {
            ObjectNode transformation = transformation(method, selectionBudget);
            ObjectNode options = object();
            options.set("artifact", artifact);
            options.put("method", method);
            options.set("transformation", transformation);
            if ("winners_curse_selection".equals(method)) options.set("selectionBudget", selectionBudget);
            ObjectNode replay = StrategyStatisticalV5.makeNullReplayArtifact(options);
            assertThat(StrategyStatisticalV5.validateContractSchema(replay)).isTrue();
        }

        ObjectNode controls = object();
        controls.set("artifact", artifact);
        controls.put("mode", "FIXTURE").put("selectedCandidateId", "candidate-1");
        controls.set("selectionBudget", selectionBudget);
        controls.put("iterations", 1).put("sequentialBatchSize", 1).put("alpha", .05).put("seed", 11);
        ObjectNode nulls = StrategyStatisticalV5.runNullControlsV5(controls, replaySuite());
        assertThat(nulls.path("tests")).hasSize(4);
        assertThat(StrategyStatisticalV5.validateContractSchema(nulls)).isTrue();

        ObjectNode malformedPValue = nulls.deepCopy();
        ((ObjectNode) malformedPValue.withArray("tests").get(0)).put("p_value", -1);
        assertContractFailure(malformedPValue, "null control test is malformed");
        ObjectNode malformedWorkload = nulls.deepCopy();
        ((ObjectNode) malformedWorkload.withArray("tests").get(0)).put("iterations", -1);
        assertContractFailure(malformedWorkload, "null control workload field iterations is invalid");
        ObjectNode malformedEnvelope = nulls.deepCopy();
        ((ObjectNode) malformedEnvelope.withArray("tests").get(0)).put("p_value_lower_bound", .9)
                .put("p_value_upper_bound", .1);
        assertContractFailure(malformedEnvelope, "null control sequential decision envelope is invalid");
        ObjectNode malformedSlots = nulls.deepCopy();
        ((ObjectNode) malformedSlots.withArray("tests").get(0)).put("worker_slots_used", "worker-1");
        assertContractFailure(malformedSlots, "null control worker slot accounting is invalid");
        ObjectNode incomplete = nulls.deepCopy();
        incomplete.putArray("tests").addObject();
        assertContractFailure(incomplete, "null control artifact is incomplete");

        ObjectNode badBlock = object();
        badBlock.set("artifact", artifact);
        badBlock.put("method", "block_permuted_labels");
        badBlock.putObject("transformation").put("method", "block_permuted_labels").put("block_length", 0)
                .put("permutation_sha256", hash("permutation")).put("labels_source_sha256", hash("labels"));
        assertThatThrownBy(() -> StrategyStatisticalV5.makeNullReplayArtifact(badBlock))
                .hasMessage("block permutation proof is incomplete");
        ObjectNode badTimestamp = object();
        badTimestamp.set("artifact", artifact);
        badTimestamp.put("method", "timestamp_shifted_outcomes");
        badTimestamp.putObject("transformation").put("method", "timestamp_shifted_outcomes").put("shift_ms", 0)
                .put("shift_map_sha256", hash("shift"));
        assertThatThrownBy(() -> StrategyStatisticalV5.makeNullReplayArtifact(badTimestamp))
                .hasMessage("timestamp shift proof is incomplete");
        ObjectNode badFrequency = object();
        badFrequency.set("artifact", artifact);
        badFrequency.put("method", "frequency_matched_random_intents");
        badFrequency.putObject("transformation").put("method", "frequency_matched_random_intents")
                .put("target_trade_count", -1).put("intent_vector_sha256", hash("intent"));
        assertThatThrownBy(() -> StrategyStatisticalV5.makeNullReplayArtifact(badFrequency))
                .hasMessage("random intent proof is incomplete");
        ObjectNode badWinner = object();
        badWinner.set("artifact", artifact);
        badWinner.put("method", "winners_curse_selection");
        badWinner.putObject("transformation").put("method", "winners_curse_selection")
                .put("selection_budget_sha256", hash("wrong-budget")).put("rerun_sha256", hash("rerun"));
        assertThatThrownBy(() -> StrategyStatisticalV5.makeNullReplayArtifact(badWinner))
                .hasMessage("winner curse proof is incomplete");
    }

    private static ObjectNode exposure(String... aliases) {
        ObjectNode options = object().put("hypothesisFamily", "validation-tests")
                .put("datasetSha256", hash("dataset"));
        ArrayNode entries = options.putArray("entries");
        for (String alias : aliases) entries.addObject().put("behavior_sha256", alias)
                .put("dataset_sha256", hash("dataset"));
        return StrategyStatisticalV5.makeExposureHead(options);
    }

    private static ObjectNode physicalRunnerContract() {
        ObjectNode value = object().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("physicalNullRunner"))
                .put("version", 1).put("factory", "INTERNAL_VERIFIED_PHYSICAL_FACTORY")
                .put("integration_status", "WIRED_PRODUCTION")
                .put("code_sha256", hash("physical-code"))
                .put("feature_artifact_sha256", hash("physical-feature"))
                .put("label_artifact_sha256", hash("physical-label"))
                .put("execution_artifact_sha256", hash("physical-execution"))
                .put("recomputes_label_execution", true).put("reruns_nested_selection", true)
                .put("worker_backed", true).put("physical_feature_label_execution", true);
        value.set("methods", MAPPER.valueToTree(List.of("block_permuted_labels", "timestamp_shifted_outcomes",
                "frequency_matched_random_intents", "winners_curse_selection")));
        return value;
    }

    private static ObjectNode physicalSelection(Path payload, String content, String source) {
        ObjectNode value = object().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("physicalNullSelection"))
                .put("version", 1).put("source_manifest_sha256", source);
        for (String[] binding : List.of(
                new String[] {"transformed_label_ref", "transformed_label_artifact_sha256"},
                new String[] {"transformed_execution_ref", "transformed_execution_artifact_sha256"},
                new String[] {"recomputed_outcome_ref", "recomputed_outcome_artifact_sha256"},
                new String[] {"selected_outcome_vector_ref", "selected_outcome_vector_sha256"},
                new String[] {"trace_ref", "trace_sha256"})) {
            value.set(binding[0], physicalReference(payload, content));
            value.put(binding[1], content);
        }
        value.set("checkpoint_ref", physicalReference(payload, content));
        return StrategyStatisticalV5.withHash(value);
    }

    private static ObjectNode physicalReference(Path payload, String content) {
        return object().put("path", payload.toString())
                .put("byte_sha256", hash("reference-payload"))
                .put("content_sha256", content);
    }

    private static void assertAuditFailure(ObjectNode value, String message) {
        ObjectNode hashed = StrategyStatisticalV5.withHash(value);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(hashed)).hasMessage(message);
    }

    private static void assertContractFailure(ObjectNode value, String message) {
        ObjectNode hashed = StrategyStatisticalV5.withHash(value);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(hashed)).hasMessage(message);
    }

    private static ObjectNode portfolioDecision() {
        ObjectNode options = object().put("lineage_sha256", hash("portfolio-lineage"))
                .put("sourceArtifactSha256", hash("portfolio-source")).put("pass", false);
        ArrayNode decisions = options.putArray("assetDecisions");
        decisions.addObject().put("asset", "btc").put("pass", false);
        ArrayNode increments = options.putArray("returnIncrements");
        increments.addObject().put("asset", "btc").put("episode_id", "e1").put("net_r", 0);
        return StrategyStatisticalV5.makePortfolioDecision(options);
    }

    private static ObjectNode assetFold(String decisionTime, double net, boolean traded, boolean stressPass) {
        ObjectNode row = object().put("fold_id", "fold-" + decisionTime.substring(0, 4));
        ArrayNode returns = row.putArray("selected_return_vector");
        returns.addObject().put("episode_id", "episode-" + decisionTime.substring(0, 4))
                .put("asset", "btc").put("decision_time", decisionTime)
                .put("resolution_time", decisionTime.replace("01T00:00:00Z", "02T00:00:00Z"))
                .put("net_r", net).put("traded", traded);
        row.putObject("metrics").put("expectancy_r", net).put("traded_count", traded ? 1 : 0)
                .put("cost_r", .1).put("coverage_fraction", 1).put("capacity_pass", true)
                .put("max_drawdown_r", net < 0 ? net : 0).put("profit_factor", net > 0 ? 2 : 0)
                .put("complexity", 1);
        row.putObject("stress").put("pass", stressPass);
        return row;
    }

    private static ObjectNode selectionBudget() {
        ObjectNode value = object().put("population", 2).put("generations", 1);
        value.putArray("seeds").add(11).add(23).add(47);
        return value;
    }

    private static ObjectNode transformation(String method, ObjectNode budget) {
        ObjectNode value = object().put("method", method);
        if ("block_permuted_labels".equals(method)) {
            value.put("block_length", 1).put("permutation_sha256", hash("permutation"))
                    .put("labels_source_sha256", hash("labels"));
        } else if ("timestamp_shifted_outcomes".equals(method)) {
            value.put("shift_ms", 1).put("shift_map_sha256", hash("shift"));
        } else if ("frequency_matched_random_intents".equals(method)) {
            value.put("target_trade_count", 1).put("intent_vector_sha256", hash("intent"));
        } else {
            value.put("selection_budget_sha256", StrategyStatisticalV5.hash(budget))
                    .put("rerun_sha256", hash("rerun"));
        }
        return value;
    }

    private static StrategyStatisticalV5.NullReplaySuite replaySuite() {
        Map<String, StrategyStatisticalV5.NullReplayMethod> methods = new LinkedHashMap<>();
        for (String method : List.of("block_permuted_labels", "timestamp_shifted_outcomes",
                "frequency_matched_random_intents", "winners_curse_selection")) {
            methods.put(method, replayArgs -> ((ObjectNode) replayArgs.path("artifact")).deepCopy());
        }
        return new StrategyStatisticalV5.NullReplaySuite(methods);
    }

    private static ObjectNode validPolicyForAggregate() {
        ObjectNode value = object().put("minEpisodes", 3).put("minExpectancy", 0).put("minProfitFactor", 1)
                .put("maxDrawdownR", 2).put("maxCostR", .2).put("minCoverage", .9)
                .put("requireCapacityPass", true);
        value.putObject("violation_scales")
                .put("episodes", 1).put("expectancy", 1).put("drawdown", 1).put("costs", 1)
                .put("coverage", 1).put("capacity", 1).put("profit_factor", 1);
        return value;
    }

    private static ObjectNode registryJournal() {
        ObjectNode value = object().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("registryJournal"))
                .put("version", 1).put("status", "PREPARED");
        ObjectNode nextHead = value.putObject("next_head").put("content_sha256", hash("next-head"));
        value.put("next_head_sha256", nextHead.path("content_sha256").asText());
        return StrategyStatisticalV5.withHash(value);
    }

    private static ObjectNode checkpointContract() {
        ObjectNode value = object().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("checkpoint"))
                .put("version", 1).put("checkpoint_status", "RUNNING")
                .put("seed_index", 0).put("generation", 1).put("seed", 11).put("rng_state", 7);
        value.putArray("population");
        value.put("history_sha256", hash("history"));
        value.putArray("seed_finalists");
        value.putObject("seed_membership");
        value.put("plateau", 0).put("pareto_signature", "");
        ObjectNode state = object();
        state.put("seedIndex", 0).put("seed", 11).put("generation", 1).put("rngState", 7);
        state.set("population", value.path("population"));
        state.put("historySha256", hash("history"));
        state.set("seedFinalists", value.path("seed_finalists"));
        state.set("seedMembership", value.path("seed_membership"));
        state.put("plateau", 0).put("paretoSignature", "");
        value.put("state_sha256", StrategyStatisticalV5.hash(state));
        return StrategyStatisticalV5.withHash(value);
    }

    private static ObjectNode artifact(ObjectNode head, String alias, String id, boolean eligible, double net) {
        return StrategyStatisticalV5.makeStatisticalArtifactSet(artifactInput(head, alias, id, eligible, net));
    }

    private static ObjectNode artifactInput(ObjectNode head, String alias) {
        return artifactInput(head, alias, "e1", false, 0);
    }

    private static ObjectNode artifactInput(ObjectNode head, String alias, String id,
            boolean eligible, double net) {
        ObjectNode options = lineageOptions(head);
        options.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", alias);
        ObjectNode row = episode(options.putArray("episodes").addObject(), id, "btc", eligible, net);
        row.with("candidate_returns").putObject("candidate-1")
                .put("net_r", net).put("traded", eligible && net != 0);
        return options;
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
