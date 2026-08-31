package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StrategyStatisticalV5NodeOracleTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    /* Legacy differential oracle retired after the Java facade reached parity.
    private static final String ORACLE = """
            import fs from 'node:fs';
            import {pathToFileURL} from 'node:url';
            const api = await import(pathToFileURL(process.argv[2]).href);
            const r = JSON.parse(fs.readFileSync(0, 'utf8'));
            try {
              let value;
              if (r.action === 'constants') value = {schema:api.STAT_SCHEMA, defaults:api.STAT_DEFAULTS};
              if (r.action === 'exports') value = Object.keys(api).sort();
              if (r.action === 'hash') value = {stable:api.stable(r.value), hash:api.hash(r.value), own:api.ownHash(r.value), with:api.withHash(r.value)};
              if (r.action === 'makeExposure') value = api.makeExposureHead(r.options);
              if (r.action === 'appendExposure') value = api.appendExposureHead(r.options);
              if (r.action === 'validateExposure') value = api.validateExposureHead(r.value);
              if (r.action === 'artifact') value = api.makeStatisticalArtifactSet(r.options);
              if (r.action === 'evaluation') value = api.makeEvaluationArtifact(r.options);
              if (r.action === 'behavior') value = {
                effective: api.effectiveExecutionBehavior(r.definition),
                intent: api.signalIntentAlias(r.vector),
                alias: api.evaluatedBehaviorAlias('ignored', {}, [], r.definition, r.contracts)
              };
              if (r.action === 'drawdown') value = api.drawdown(r.values);
              if (r.action === 'clusters') value = {
                diagnostics: api.marketEpisodeClusterDiagnostics(r.episodes),
                collapsed: api.collapseMarketEpisodeRows(r.rows, r.episodes)
              };
              if (r.action === 'hard') value = {
                policy: api.requireFrozenHardPolicy(r.policy),
                result: api.hardFeasible(r.metrics, r.policy),
                dominates: api.constrainedDominates(r.left, r.right)
              };
              if (r.action === 'dsr') value = api.deflatedSharpe(r.rows, r.trials);
              if (r.action === 'plateau') value = api.connectedPlateau(r.ga, r.alias, r.options);
              if (r.action === 'stress') value = api.makeStressDecision(r.options);
              if (r.action === 'portfolio') value = api.makePortfolioDecision(r.options);
              if (r.action === 'makeRegistry') value = api.makeBehaviorDefinitionRegistry(r.options);
              if (r.action === 'appendRegistry') value = api.appendBehaviorDefinitionRegistry(r.options);
              if (r.action === 'validateRegistry') value = api.validateBehaviorDefinitionRegistry(r.value, r.options);
              if (r.action === 'folds') value = api.makeQuarterlyFolds(r.options);
              if (r.action === 'initExposureFile') value = api.initializeExposureHeadFile(r.options);
              if (r.action === 'appendExposureFile') value = api.appendExposureHeadFile(r.options);
              if (r.action === 'appendRegistryFile') value = api.appendBehaviorDefinitionRegistryFile(r.options);
              if (r.action === 'bindRegistrySnapshot') value = api.bindBehaviorDefinitionRegistrySnapshotFile(r.options);
              if (r.action === 'resolveRegistrySnapshot') value = api.resolveBehaviorDefinitionRegistrySnapshotFile(r.options);
              if (r.action === 'makeVectors') value = api.makeVectorInventory(r.options);
              if (r.action === 'validateVectors') value = api.validateVectorInventory(r.value, r.head, r.episodeIds);
              if (r.action === 'validateAudit') value = api.validateStatisticalAudit(r.value);
              if (r.action === 'neighbours') value = api.enumerateDirectNeighbours(r.space, r.value);
              if (r.action === 'makeCheckpoint') value = api.makeGeneticCheckpoint(r.options);
              if (r.action === 'validateCheckpoint') value = api.validateGeneticCheckpoint(r.value, r.options);
              if (r.action === 'writeCheckpoint') value = api.writeGeneticCheckpointFile(r.options);
              if (r.action === 'readCheckpoint') value = api.readGeneticCheckpointFile(r.filePath);
              if (r.action === 'recoverCheckpointLock') value = api.recoverStaleCheckpointLock(r.options);
              if (r.action === 'pbo') value = api.pboFromFolds(r.folds, r.selected, r.options);
              if (r.action === 'nullReplay') value = api.makeNullReplayArtifact(r.options);
              if (r.action === 'runNullFixture') {
                const methods = ['block_permuted_labels', 'timestamp_shifted_outcomes', 'frequency_matched_random_intents', 'winners_curse_selection'];
                const replay = Object.fromEntries(methods.map(method => [method, ({artifact}) => structuredClone(artifact)]));
                value = api.runNullControlsV5({...r.options, replay});
              }
              if (r.action === 'runNullUnsupported') value = api.runNullControlsV5(r.options);
              if (r.action === 'physicalNullRunner') {
                const evaluator = () => ({});
                evaluator.worker_provenance = r.provenance;
                evaluator.physical_null_selection_verified = true;
                evaluator.physical_null_selection = () => ({});
                const runner = api.makePhysicalNullRunnerV5({...r.options, evaluator});
                value = r.runOptions ? runner.run(r.runOptions) : runner.contract;
              }
              if (r.action === 'calibrateNull') {
                const methods = ['block_permuted_labels', 'timestamp_shifted_outcomes', 'frequency_matched_random_intents', 'winners_curse_selection'];
                const replay = Object.fromEntries(methods.map(method => [method, ({artifact}) => structuredClone(artifact)]));
                value = api.calibrateNullControlsV5({...r.options, replay});
              }
              if (r.action === 'validateGenetic') value = api.validateGeneticArtifact(r.value);
              if (r.action === 'aggregateAsset') value = api.aggregateAssetDecision(r.rows, r.required);
              if (r.action === 'writeRegistryJournal') value = api.writeExposureRegistryJournal(r.options);
              if (r.action === 'recoverRegistryJournal') value = api.recoverExposureRegistryTransaction(r.options);
              if (r.action === 'validateWfo') value = api.validateNestedWfoArtifact(r.value);
              if (r.action === 'assertWfoBinding') value = api.assertWfoRetainedOosBinding(r.wfo, r.artifact, r.vector, r.label);
              if (r.action === 'makePublication') value = api.makeStatisticalPublicationTransaction(r.options);
              if (r.action === 'writePublication') value = api.writeStatisticalPublicationTransaction(r.options);
              if (r.action === 'recoverPublication') value = api.recoverStatisticalPublicationTransaction(r.options);
              if (r.action === 'verifyPublication') value = api.verifyCommittedStatisticalPublication(r.options);
              if (r.action === 'runAudit') value = api.runStatisticalAuditV5(r.options);
              if (r.action === 'runGenetic') {
                const evaluate = task => {
                  const x = Number(task.chromosome.alpha || 0);
                  const side = task.chromosome.side === 'long' ? 0.2 : -0.2;
                  const candidate_returns = Object.fromEntries(task.episode_ids.map((id, index) =>
                    [id, {net_r: x + side + index * 0.05, traded: true}]));
                  return {candidate_returns, metrics: {cost_r: 0.01, coverage_fraction: 1,
                    capacity_pass: true, max_drawdown_r: -0.1, profit_factor: 2,
                    turnover: 1 + x, complexity: 2}, required: {bootstrapIterations: 32, seed: 11}};
                };
                evaluate.evaluateBatch = tasks => tasks.map(evaluate);
                value = api.runGeneticSearchV5({...r.options, evaluator:evaluate});
              }
              if (r.action === 'runGeneticNoEvaluator') value = api.runGeneticSearchV5(r.options);
              if (r.action === 'resumeGenetic') {
                const evaluate = task => {
                  const x = Number(task.chromosome.alpha || 0);
                  const side = task.chromosome.side === 'long' ? 0.2 : -0.2;
                  const candidate_returns = Object.fromEntries(task.episode_ids.map((id, index) =>
                    [id, {net_r: x + side + index * 0.05, traded: true}]));
                  return {candidate_returns, metrics: {cost_r: 0.01, coverage_fraction: 1,
                    capacity_pass: true, max_drawdown_r: -0.1, profit_factor: 2,
                    turnover: 1 + x, complexity: 2}, required: {bootstrapIterations: 32, seed: 11}};
                };
                evaluate.evaluateBatch = tasks => tasks.map(evaluate);
                value = api.resumeGeneticSearchV5({...r.options, evaluator:evaluate});
              }
              if (r.action === 'runNested') {
                const evaluate = task => {
                  const candidate_returns = Object.fromEntries(task.episode_ids.map(id =>
                    [id, {net_r: 0.2, traded: true}]));
                  return {candidate_returns, metrics: {cost_r: 0.01, coverage_fraction: 1,
                    capacity_pass: true, max_drawdown_r: -0.1, profit_factor: 2,
                    turnover: 1, complexity: 1}, required: {bootstrapIterations: 8, seed: 11}};
                };
                evaluate.evaluateBatch = tasks => tasks.map(evaluate);
                const stressProvider = task => api.makeStressDecision({lineage_sha256:task.lineage_sha256,
                  pass:false, sourceArtifactSha256:task.artifact.content_sha256,
                  selectedCandidateId:task.selected_candidate_id});
                const portfolioProvider = task => {
                  let returnIncrements = task.asset_decisions.flatMap(decision =>
                    (decision.selected_return_vector || []).filter(row => row.traded)
                      .map(row => ({episode_id:row.episode_id, asset:row.asset, net_r:row.net_r})));
                  if (!returnIncrements.length) returnIncrements = [{episode_id:task.artifact.episodes[0].episode_id,
                    asset:task.artifact.episodes[0].asset, net_r:0}];
                  return api.makePortfolioDecision({lineage_sha256:task.lineage_sha256, pass:false,
                    assetDecisions:task.asset_decisions, returnIncrements,
                    sourceArtifactSha256:task.artifact.content_sha256});
                };
                const oosVectorProvider = task => {
                  const vectors = Object.fromEntries(task.exposureHead.entries.map(entry => [entry.behavior_sha256,
                    task.episode_ids.map(episode_id => ({episode_id, net_r:0.2, traded:true, eligible:true}))]));
                  return api.makeVectorInventory({exposureHead:task.exposureHead,
                    episodeIds:task.episode_ids, vectors});
                };
                value = api.runNestedWfoV5({...r.options, evaluator:evaluate, stressProvider,
                  portfolioProvider, oosVectorProvider});
              }
              if (r.action === 'runNestedNoProviders') value = api.runNestedWfoV5(r.options);
              process.stdout.write(JSON.stringify({ok:true,value}));
            } catch (error) { process.stdout.write(JSON.stringify({ok:false,error:String(error?.message || error)})); }
            """;
    */

    @Test
    void constantsHashAndExposureArtifactsMatchNodeExactly() throws Exception {
        JsonNode constants = oracle(request("constants")).path("value");
        assertJson(MAPPER.valueToTree(StrategyStatisticalV5.STAT_SCHEMA), constants.path("schema"));
        assertJson(MAPPER.valueToTree(StrategyStatisticalV5.STAT_DEFAULTS), constants.path("defaults"));

        ObjectNode hashValue = MAPPER.createObjectNode().put("z", 1).put("a", "x");
        hashValue.put("content_sha256", JsonHashes.sha256("old"));
        JsonNode hashExpected = oracle(request("hash").set("value", hashValue)).path("value");
        assertThat(StrategyStatisticalV5.stable(hashValue)).isEqualTo(hashExpected.path("stable").asText());
        assertThat(StrategyStatisticalV5.hash(hashValue)).isEqualTo(hashExpected.path("hash").asText());
        assertThat(StrategyStatisticalV5.ownHash(hashValue)).isEqualTo(hashExpected.path("own").asText());
        assertJson(StrategyStatisticalV5.withHash(hashValue), hashExpected.path("with"));

        ObjectNode options = MAPPER.createObjectNode().put("hypothesisFamily", "fear-reversal")
                .put("datasetSha256", JsonHashes.sha256("dataset")).put("exposureAttemptK", 3);
        options.putArray("entries").addObject().put("behavior_sha256", JsonHashes.sha256("behavior-a"))
                .put("dataset_sha256", JsonHashes.sha256("dataset"))
                .put("observed_at", "2026-01-01T00:00:00Z").put("source", "TEST")
                .put("definition_sha256", JsonHashes.sha256("definition-a"))
                .put("vector_commitment_sha256", JsonHashes.sha256("vector-a"));
        ObjectNode expected = (ObjectNode) oracle(request("makeExposure").set("options", options)).path("value");
        ObjectNode actual = StrategyStatisticalV5.makeExposureHead(options);
        assertJson(actual, expected);
        assertJson(StrategyStatisticalV5.validateExposureHead(actual), expected);

        ObjectNode append = MAPPER.createObjectNode().set("prior", actual);
        append.put("datasetSha256", JsonHashes.sha256("dataset-2"));
        append.putArray("behaviorAliases").add(JsonHashes.sha256("behavior-c"))
                .add(JsonHashes.sha256("behavior-b")).add(JsonHashes.sha256("behavior-b"));
        append.putObject("behaviorDefinitions").put(JsonHashes.sha256("behavior-b"), JsonHashes.sha256("definition-b"));
        append.putObject("vectorCommitments").put(JsonHashes.sha256("behavior-c"), JsonHashes.sha256("vector-c"));
        append.put("observedAt", "2026-02-01T00:00:00.000Z").put("source", "SEARCH")
                .put("exposureAttemptCount", 4);
        assertJson(StrategyStatisticalV5.appendExposureHead(append),
                oracle(request("appendExposure").set("options", append)).path("value"));
    }

    @Test
    void exposureFailuresMatchNodeMessages() throws Exception {
        ObjectNode bad = MAPPER.createObjectNode().put("hypothesisFamily", "x").put("datasetSha256", "bad");
        assertSameFailure(request("makeExposure").set("options", bad),
                () -> StrategyStatisticalV5.makeExposureHead(bad));

        ObjectNode valid = StrategyStatisticalV5.makeExposureHead(MAPPER.createObjectNode()
                .put("hypothesisFamily", "x").put("datasetSha256", JsonHashes.sha256("dataset")));
        valid.put("cumulative_k", 1);
        assertSameFailure(request("validateExposure").set("value", valid),
                () -> StrategyStatisticalV5.validateExposureHead(valid));
    }

    @Test
    void statisticalInputEvaluationAndBehaviorAliasesMatchNodeExactly() throws Exception {
        String behavior = JsonHashes.sha256("candidate-behavior");
        ObjectNode headOptions = MAPPER.createObjectNode().put("hypothesisFamily", "family")
                .put("datasetSha256", JsonHashes.sha256("dataset"));
        headOptions.putArray("entries").addObject().put("behavior_sha256", behavior)
                .put("dataset_sha256", JsonHashes.sha256("dataset"));
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headOptions);
        ObjectNode artifactOptions = MAPPER.createObjectNode();
        ObjectNode lineage = artifactOptions.putObject("lineage");
        for (String key : new String[] {"dataset_sha256", "candidate_set_sha256", "feature_set_sha256",
                "label_set_sha256", "execution_set_sha256"}) lineage.put(key, JsonHashes.sha256(key));
        artifactOptions.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", behavior);
        artifactOptions.set("exposureHead", head);
        var episodes = artifactOptions.putArray("episodes");
        episode(episodes.addObject(), "e1", "btc", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", 1.25, true);
        episode(episodes.addObject(), "e2", "eth", "2026-01-03T00:00:00Z", "2026-01-04T00:00:00Z", 0, false);
        ObjectNode expectedArtifact = (ObjectNode) oracle(request("artifact").set("options", artifactOptions)).path("value");
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactOptions);
        assertJson(artifact, expectedArtifact);
        ObjectNode validateOptions = MAPPER.createObjectNode().set("exposureHead", head);
        assertThat(StrategyStatisticalV5.validateStatisticalArtifactSet(artifact, validateOptions)).isTrue();

        ObjectNode signal = MAPPER.createObjectNode().put("schema", "strategy-v5-statistical-signal-view/1")
                .put("source_artifact_sha256", artifact.path("content_sha256").asText());
        signal.putArray("episodes").addObject().put("episode_id", "e1").put("asset", "btc");
        ObjectNode evaluation = MAPPER.createObjectNode().set("signalArtifact", signal);
        evaluation.putArray("episodeIds").add("e1");
        evaluation.put("phase", "OUTER_OOS").putNull("foldId").putNull("cutoff");
        evaluation.putObject("candidateReturns").putObject("e1").put("net_r", 1.25).put("traded", true);
        evaluation.putObject("metrics").put("cost_r", .01).put("coverage_fraction", 1)
                .put("capacity_pass", true).put("max_drawdown_r", 0).put("profit_factor", 2)
                .put("turnover", 1).put("complexity", 1);
        evaluation.putArray("signalIntentVector").addObject().put("episode_id", "e1").put("intent", true);
        evaluation.putObject("candidateDefinition").put("direction", "long")
                .putObject("unused_diagnostic").put("x", 1);
        ObjectNode contracts = evaluation.putObject("behaviorContracts");
        contracts.put("signal_semantics_sha256", JsonHashes.sha256("signal"))
                .put("evaluator_sha256", JsonHashes.sha256("eval"))
                .put("predictor_sha256", JsonHashes.sha256("predictor"))
                .put("lifecycle_sha256", JsonHashes.sha256("lifecycle"))
                .putNull("precommit_sha256");
        assertJson(StrategyStatisticalV5.makeEvaluationArtifact(evaluation),
                oracle(request("evaluation").set("options", evaluation)).path("value"));

        ObjectNode behaviorCall = request("behavior");
        behaviorCall.set("definition", evaluation.path("candidateDefinition"));
        behaviorCall.set("vector", evaluation.path("signalIntentVector"));
        behaviorCall.set("contracts", contracts);
        JsonNode expectedBehavior = oracle(behaviorCall).path("value");
        assertJson(StrategyStatisticalV5.effectiveExecutionBehavior(evaluation.path("candidateDefinition")),
                expectedBehavior.path("effective"));
        assertThat(StrategyStatisticalV5.signalIntentAlias(evaluation.path("signalIntentVector")))
                .isEqualTo(expectedBehavior.path("intent").asText());
        assertThat(StrategyStatisticalV5.evaluatedBehaviorAlias("ignored", MAPPER.createObjectNode(),
                MAPPER.createArrayNode(), evaluation.path("candidateDefinition"), contracts))
                .isEqualTo(expectedBehavior.path("alias").asText());

        var values = MAPPER.createArrayNode().add(1).add(-3).add(2).add(-1);
        assertThat(StrategyStatisticalV5.drawdown(java.util.List.of(1, -3, 2, -1)))
                .isEqualTo(oracle(request("drawdown").set("values", values)).path("value").asDouble());
    }

    @Test
    void fixtureGeneticSearchMatchesNodeExactlyAcrossSeedsSchedulerAndConfirmation() throws Exception {
        ObjectNode options = geneticFixture();
        JsonNode expected = oracle(request("runGenetic").set("options", options));
        assertThat(expected.path("ok").asBoolean()).isTrue();
        ObjectNode actual = StrategyStatisticalV5.runGeneticSearchV5(options, fixtureGeneticEvaluator());
        assertJson(actual, expected.path("value"));
        assertThat(StrategyStatisticalV5.validateGeneticArtifact(actual.path("run"))).isTrue();
        assertThat(actual.path("run").path("seed_runs")).hasSize(3);
        assertThat(actual.path("run").path("evaluation_attempt_k").asInt())
                .isEqualTo(actual.path("run").path("population_history").size());
    }

    @Test
    void checkpointInterruptionAndResumeAreByteCanonicalWithNode(@TempDir Path temporary) throws Exception {
        ObjectNode javaOptions = geneticFixture(); ObjectNode nodeOptions = javaOptions.deepCopy();
        Path javaDirectory = temporary.resolve("java-checkpoints"); Path nodeDirectory = temporary.resolve("node-checkpoints");
        Files.createDirectories(javaDirectory); Files.createDirectories(nodeDirectory);
        javaOptions.put("checkpointPath", javaDirectory.resolve("run.json").toString());
        javaOptions.path("config").deepCopy(); ((ObjectNode) javaOptions.path("config"))
                .put("checkpointDirectory", javaDirectory.toString()).put("interruptAfterGeneration", 2);
        nodeOptions.put("checkpointPath", nodeDirectory.resolve("run.json").toString());
        ((ObjectNode) nodeOptions.path("config")).put("checkpointDirectory", nodeDirectory.toString())
                .put("interruptAfterGeneration", 2);
        JsonNode interrupted = oracle(request("runGenetic").set("options", nodeOptions));
        assertThat(interrupted.path("ok").asBoolean()).isFalse();
        assertThatThrownBy(() -> StrategyStatisticalV5.runGeneticSearchV5(javaOptions, fixtureGeneticEvaluator()))
                .hasMessage(interrupted.path("error").asText());
        ObjectNode javaCheckpoint = StrategyStatisticalV5.readGeneticCheckpointFile(
                javaOptions.path("checkpointPath").asText());
        ObjectNode nodeCheckpoint = StrategyStatisticalV5.readGeneticCheckpointFile(
                nodeOptions.path("checkpointPath").asText());
        assertJson(javaCheckpoint, nodeCheckpoint);

        ((ObjectNode) javaOptions.path("config")).remove("interruptAfterGeneration");
        ((ObjectNode) nodeOptions.path("config")).remove("interruptAfterGeneration");
        javaOptions.set("checkpoint", javaCheckpoint); nodeOptions.set("checkpoint", nodeCheckpoint);
        ObjectNode actual = StrategyStatisticalV5.resumeGeneticSearchV5(javaOptions, fixtureGeneticEvaluator());
        JsonNode expected = oracle(request("resumeGenetic").set("options", nodeOptions));
        assertThat(expected.path("ok").asBoolean()).isTrue();
        assertJson(actual, expected.path("value"));
        assertThat(StrategyStatisticalV5.readGeneticCheckpointFile(javaOptions.path("checkpointPath").asText())
                .path("checkpoint_status").asText()).isEqualTo("COMPLETE");
    }

    @Test
    void everyNodeStatisticalExportHasAPublicJavaBinding() throws Exception {
        java.util.Set<String> methods = new java.util.HashSet<>();
        for (var method : StrategyStatisticalV5.class.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) methods.add(method.getName());
        }
        java.util.Set<String> fields = new java.util.HashSet<>();
        for (var field : StrategyStatisticalV5.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) fields.add(field.getName());
        }
        JsonNode exports = oracle(request("exports")).path("value");
        assertThat(exports).isNotEmpty();
        for (JsonNode name : exports) assertThat(methods.contains(name.asText()) || fields.contains(name.asText()))
                .describedAs("public Java binding for Node export %s", name.asText()).isTrue();
    }

    @Test
    void fixtureNestedWfoMatchesNodeExactlyEndToEnd() throws Exception {
        ObjectNode options = nestedWfoFixture(); JsonNode expected = oracle(request("runNested").set("options", options));
        assertThat(expected.path("ok").asBoolean()).describedAs(expected.path("error").asText()).isTrue();
        ObjectNode actual = StrategyStatisticalV5.runNestedWfoV5(options, nestedEvaluator(),
                nestedStressProvider(), nestedPortfolioProvider(), nestedVectorProvider());
        assertJson(actual, expected.path("value"));
        assertThat(StrategyStatisticalV5.validateNestedWfoArtifact(actual.path("run"))).isTrue();
        assertThat(actual.path("run").path("folds")).hasSize(8);
    }

    @Test
    void geneticAndNestedOrchestratorsFailClosedOnMalformedOrMissingBindings() throws Exception {
        ObjectNode missingEvaluator = geneticFixture();
        assertSameFailure(request("runGeneticNoEvaluator").set("options", missingEvaluator),
                () -> StrategyStatisticalV5.runGeneticSearchV5(missingEvaluator));

        ObjectNode badScope = geneticFixture(); badScope.set("trainingEpisodeIds", MAPPER.createArrayNode().add("g1").add(7));
        assertSameFailure(request("runGenetic").set("options", badScope),
                () -> StrategyStatisticalV5.runGeneticSearchV5(badScope, fixtureGeneticEvaluator()));

        ObjectNode unavailable = geneticFixture(); ObjectNode altered = ((ObjectNode) unavailable.path("artifact")).deepCopy();
        ((ObjectNode) altered.path("episodes").path(0)).put("label_availability_time", "2025-03-01T00:00:00Z");
        unavailable.set("artifact", StrategyStatisticalV5.withHash(altered));
        assertSameFailure(request("runGenetic").set("options", unavailable),
                () -> StrategyStatisticalV5.runGeneticSearchV5(unavailable, fixtureGeneticEvaluator()));

        ObjectNode rawNested = MAPPER.createObjectNode().putArray("artifact").addObject().put("x", 1);
        assertSameFailure(request("runNestedNoProviders").set("options", rawNested),
                () -> StrategyStatisticalV5.runNestedWfoV5(rawNested));
        ObjectNode missingProviders = nestedWfoFixture();
        assertSameFailure(request("runNestedNoProviders").set("options", missingProviders),
                () -> StrategyStatisticalV5.runNestedWfoV5(missingProviders));
    }

    private static ObjectNode nestedWfoFixture() {
        String behavior = JsonHashes.sha256("nested-input-behavior");
        ObjectNode headArgs = MAPPER.createObjectNode().put("hypothesisFamily", "nested-family")
                .put("datasetSha256", JsonHashes.sha256("nested-dataset"));
        headArgs.putArray("entries").addObject().put("behavior_sha256", behavior)
                .put("dataset_sha256", JsonHashes.sha256("nested-dataset"));
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs); ObjectNode artifactArgs = MAPPER.createObjectNode();
        ObjectNode lineage = artifactArgs.putObject("lineage").put("dataset_sha256", JsonHashes.sha256("nested-dataset"));
        for (String key : new String[] {"candidate_set_sha256", "feature_set_sha256", "label_set_sha256",
                "execution_set_sha256"}) lineage.put(key, JsonHashes.sha256("nested-" + key));
        artifactArgs.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", behavior); artifactArgs.set("exposureHead", head);
        var episodes = artifactArgs.putArray("episodes"); java.time.ZonedDateTime start =
                java.time.ZonedDateTime.parse("2022-01-01T00:00:00Z");
        for (int index = 0; index < 48; index++) {
            var decision = start.plusMonths(index); var resolution = decision.plusDays(1);
            episode(episodes.addObject(), "w" + String.format("%02d", index + 1), "btc",
                    decision.toInstant().toString(), resolution.toInstant().toString(), 0, false);
        }
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactArgs);
        ObjectNode options = MAPPER.createObjectNode().set("artifact", artifact); options.set("exposureHead", head);
        options.put("mode", "FIXTURE").put("endAt", "2026-01-01T00:00:00Z");
        ObjectNode space = options.putObject("geneSpace"); ObjectNode gene = space.putArray("genes").addObject()
                .put("name", "side").put("type", "categorical").put("default", "long");
        gene.putArray("values").add("long"); ObjectNode constraints = options.putObject("constraints")
                .put("minEpisodes", 0).put("minExpectancy", -10).put("minProfitFactor", 0)
                .put("maxDrawdownR", 100).put("maxCostR", 10).put("minCoverage", 0)
                .put("requireCapacityPass", true);
        ObjectNode config = options.putObject("config").put("population", 2).put("generations", 1)
                .put("minGenerations", 1).put("plateauGenerations", 1).put("crossoverProbability", .9)
                .put("mutationProbability", 0).put("halfLifeMonths", 18).put("bootstrapIterations", 8)
                .put("seed", 11).put("prospectiveCutoff", "2026-01-01T00:00:00Z");
        config.set("constraints", constraints.deepCopy());
        config.putArray("seeds").add(11).add(23).add(47); return options;
    }

    private static StrategyEvaluatorV5.Evaluator nestedEvaluator() {
        return task -> {
            ObjectNode result = MAPPER.createObjectNode(); ObjectNode returns = result.putObject("candidate_returns");
            for (JsonNode id : task.path("episode_ids")) returns.putObject(id.asText()).put("net_r", .2).put("traded", true);
            result.putObject("metrics").put("cost_r", .01).put("coverage_fraction", 1)
                    .put("capacity_pass", true).put("max_drawdown_r", -.1).put("profit_factor", 2)
                    .put("turnover", 1).put("complexity", 1);
            result.putObject("required").put("bootstrapIterations", 8).put("seed", 11); return result;
        };
    }

    private static StrategyStatisticalV5.StatisticalProvider nestedStressProvider() {
        return task -> StrategyStatisticalV5.makeStressDecision(MAPPER.createObjectNode()
                .put("lineage_sha256", task.path("lineage_sha256").asText()).put("pass", false)
                .put("sourceArtifactSha256", task.path("artifact").path("content_sha256").asText())
                .put("selectedCandidateId", task.path("selected_candidate_id").asText()));
    }

    private static StrategyStatisticalV5.StatisticalProvider nestedPortfolioProvider() {
        return task -> {
            ObjectNode args = MAPPER.createObjectNode().put("lineage_sha256", task.path("lineage_sha256").asText())
                    .put("pass", false).put("sourceArtifactSha256",
                            task.path("artifact").path("content_sha256").asText());
            args.set("assetDecisions", task.path("asset_decisions")); var increments = args.putArray("returnIncrements");
            for (JsonNode decision : task.path("asset_decisions")) for (JsonNode row :
                    decision.path("selected_return_vector")) if (row.path("traded").asBoolean()) {
                increments.addObject().put("episode_id", row.path("episode_id").asText())
                        .put("asset", row.path("asset").asText()).put("net_r", row.path("net_r").asDouble());
            }
            if (increments.isEmpty()) increments.addObject()
                    .put("episode_id", task.path("artifact").path("episodes").path(0).path("episode_id").asText())
                    .put("asset", task.path("artifact").path("episodes").path(0).path("asset").asText())
                    .put("net_r", 0);
            return StrategyStatisticalV5.makePortfolioDecision(args);
        };
    }

    private static StrategyStatisticalV5.StatisticalProvider nestedVectorProvider() {
        return task -> {
            ObjectNode args = MAPPER.createObjectNode().set("exposureHead", task.path("exposureHead"));
            args.set("episodeIds", task.path("episode_ids")); ObjectNode vectors = args.putObject("vectors");
            for (JsonNode entry : task.path("exposureHead").path("entries")) {
                var rows = vectors.putArray(entry.path("behavior_sha256").asText());
                for (JsonNode id : task.path("episode_ids")) rows.addObject().put("episode_id", id.asText())
                        .put("net_r", .2).put("traded", true).put("eligible", true);
            }
            return StrategyStatisticalV5.makeVectorInventory(args);
        };
    }

    private static ObjectNode geneticFixture() {
        String behavior = JsonHashes.sha256("genetic-input-behavior");
        ObjectNode headArgs = MAPPER.createObjectNode().put("hypothesisFamily", "genetic-family")
                .put("datasetSha256", JsonHashes.sha256("genetic-dataset"));
        headArgs.putArray("entries").addObject().put("behavior_sha256", behavior)
                .put("dataset_sha256", JsonHashes.sha256("genetic-dataset"));
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
        ObjectNode artifactArgs = MAPPER.createObjectNode(); ObjectNode lineage = artifactArgs.putObject("lineage");
        lineage.put("dataset_sha256", JsonHashes.sha256("genetic-dataset"));
        for (String key : new String[] {"candidate_set_sha256", "feature_set_sha256", "label_set_sha256",
                "execution_set_sha256"}) lineage.put(key, JsonHashes.sha256("genetic-" + key));
        artifactArgs.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", behavior); artifactArgs.set("exposureHead", head);
        var episodes = artifactArgs.putArray("episodes");
        episode(episodes.addObject(), "g1", "btc", "2025-01-01T00:00:00Z", "2025-01-02T00:00:00Z", 0, false);
        episode(episodes.addObject(), "g2", "eth", "2025-01-03T00:00:00Z", "2025-01-04T00:00:00Z", 0, false);
        episode(episodes.addObject(), "g3", "sol", "2025-01-05T00:00:00Z", "2025-01-06T00:00:00Z", 0, false);
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactArgs);
        ObjectNode options = MAPPER.createObjectNode().set("artifact", artifact); options.set("exposureHead", head);
        options.put("mode", "FIXTURE").put("foldId", "fixture-training");
        options.putArray("trainingEpisodeIds").add("g1").add("g2").add("g3");
        ObjectNode space = options.putObject("geneSpace"); var genes = space.putArray("genes");
        genes.addObject().put("name", "alpha").put("type", "continuous").put("min", 0).put("max", 1)
                .put("step", .25).put("default", .5).put("usage", "signal");
        ObjectNode side = genes.addObject().put("name", "side").put("type", "categorical").put("default", "long");
        side.putArray("values").add("long").add("short");
        ObjectNode constraints = options.putObject("constraints").put("minEpisodes", 0)
                .put("minExpectancy", -10).put("minProfitFactor", 0).put("maxDrawdownR", 100)
                .put("maxCostR", 10).put("minCoverage", 0).put("requireCapacityPass", true);
        ObjectNode config = options.putObject("config").put("population", 4).put("generations", 3)
                .put("minGenerations", 2).put("plateauGenerations", 2).put("crossoverProbability", .9)
                .put("mutationProbability", .5).put("halfLifeMonths", 18)
                .put("trainingCutoff", "2025-02-01T00:00:00Z");
        config.putArray("seeds").add(11).add(23).add(47); return options;
    }

    private static StrategyEvaluatorV5.Evaluator fixtureGeneticEvaluator() {
        return task -> {
            double alpha = task.path("chromosome").path("alpha").asDouble();
            double side = "long".equals(task.path("chromosome").path("side").asText()) ? .2 : -.2;
            ObjectNode result = MAPPER.createObjectNode(); ObjectNode returns = result.putObject("candidate_returns");
            int index = 0; for (JsonNode id : task.path("episode_ids")) {
                returns.putObject(id.asText()).put("net_r", alpha + side + index * .05).put("traded", true); index++;
            }
            result.putObject("metrics").put("cost_r", .01).put("coverage_fraction", 1)
                    .put("capacity_pass", true).put("max_drawdown_r", -.1).put("profit_factor", 2)
                    .put("turnover", 1 + alpha).put("complexity", 2);
            result.putObject("required").put("bootstrapIterations", 32).put("seed", 11); return result;
        };
    }

    private static void episode(ObjectNode row, String id, String asset, String decision, String resolution,
            double netR, boolean traded) {
        row.put("episode_id", id).put("asset", asset).put("decision_time", decision)
                .put("resolution_time", resolution).put("eligible", true);
        row.putObject("candidate_returns").putObject("candidate-1").put("net_r", netR).put("traded", traded);
    }

    @Test
    void marketClustersHardPolicyAndConstrainedDominanceMatchNodeExactly() throws Exception {
        var episodes = MAPPER.createArrayNode();
        clusterEpisode(episodes.addObject(), "a", "btc", "2026-01-01T00:00:00Z", "2026-01-03T00:00:00Z");
        clusterEpisode(episodes.addObject(), "b", "eth", "2026-01-01T12:00:00Z", "2026-01-02T12:00:00Z");
        clusterEpisode(episodes.addObject(), "c", "sol", "2026-01-02T01:00:00Z", "2026-01-03T01:00:00Z");
        var rows = MAPPER.createArrayNode();
        ObjectNode rowA = rows.addObject(); rowA.setAll((ObjectNode) episodes.get(0));
        rowA.put("value", 2).put("net_r", 2).put("traded", true);
        ObjectNode rowB = rows.addObject(); rowB.setAll((ObjectNode) episodes.get(1));
        rowB.put("value", -1).put("net_r", -1).put("traded", true);
        ObjectNode rowC = rows.addObject(); rowC.setAll((ObjectNode) episodes.get(2));
        rowC.put("value", 3).put("net_r", 3).put("traded", false);
        ObjectNode clusterCall = request("clusters"); clusterCall.set("episodes", episodes); clusterCall.set("rows", rows);
        JsonNode expected = oracle(clusterCall).path("value");
        assertJson(StrategyStatisticalV5.marketEpisodeClusterDiagnostics(episodes), expected.path("diagnostics"));
        assertJson(StrategyStatisticalV5.collapseMarketEpisodeRows(rows, episodes), expected.path("collapsed"));

        ObjectNode policy = MAPPER.createObjectNode().put("minEpisodes", 3).put("minExpectancy", .1)
                .put("minProfitFactor", 1.1).put("maxDrawdownR", 3).put("maxCostR", .2)
                .put("minCoverage", .9).put("requireCapacityPass", true);
        ObjectNode scales = policy.putObject("violationScales");
        for (String key : new String[] {"episodes", "expectancy", "drawdown", "costs", "coverage",
                "capacity", "profit_factor"}) scales.put(key, 1);
        ObjectNode metrics = MAPPER.createObjectNode().put("traded_count", 2).put("expectancy_r", .05)
                .put("cost_r", .3).put("coverage_fraction", .8).put("capacity_pass", false)
                .put("max_drawdown_r", -4).put("profit_factor", 1);
        ObjectNode left = MAPPER.createObjectNode().put("feasible", false).put("total_violation", 1);
        left.putObject("violation_details").put("episodes", 1);
        ObjectNode right = MAPPER.createObjectNode().put("feasible", false).put("total_violation", 2);
        right.putObject("violation_details").put("episodes", 2);
        ObjectNode hardCall = request("hard"); hardCall.set("policy", policy); hardCall.set("metrics", metrics);
        hardCall.set("left", left); hardCall.set("right", right);
        JsonNode hardExpected = oracle(hardCall).path("value");
        assertJson(StrategyStatisticalV5.requireFrozenHardPolicy(policy), hardExpected.path("policy"));
        assertJson(StrategyStatisticalV5.hardFeasible(metrics, policy), hardExpected.path("result"));
        assertThat(StrategyStatisticalV5.constrainedDominates(left, right))
                .isEqualTo(hardExpected.path("dominates").asBoolean());
    }

    @Test
    void deflatedSharpePlateauStressAndPortfolioMatchNodeExactly() throws Exception {
        var returns = MAPPER.createArrayNode();
        for (double value : new double[] {.2, -.1, .4, .05, -.2, .3, .1}) returns.addObject().put("value", value);
        ObjectNode dsrCall = request("dsr"); dsrCall.set("rows", returns); dsrCall.put("trials", 12);
        assertJson(StrategyStatisticalV5.deflatedSharpe(returns, 12), oracle(dsrCall).path("value"));

        String selectedAlias = JsonHashes.sha256("selected-alias");
        ObjectNode ga = MAPPER.createObjectNode().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("genetic"))
                .put("selected_behavior_alias_sha256", selectedAlias);
        ObjectNode selected = ga.putObject("selected");
        selected.putObject("chromosome").put("a", 1).put("b", 2);
        selected.putObject("fitness").put("feasible", true).putObject("metrics").put("expectancy_r", .2);
        var neighbours = ga.putArray("neighbours");
        neighbours.addObject().put("behavior_alias_sha256", JsonHashes.sha256("n1"))
                .put("feasible", true).put("expectancy_r", .1).putObject("chromosome").put("a", 2).put("b", 2);
        neighbours.addObject().put("behavior_alias_sha256", JsonHashes.sha256("n2"))
                .put("feasible", false).put("expectancy_r", .3).putObject("chromosome").put("a", 1).put("b", 3);
        ObjectNode plateauCall = request("plateau"); plateauCall.set("ga", ga); plateauCall.put("alias", selectedAlias);
        plateauCall.putObject("options").put("minSize", 2).put("minNeighbourFraction", .5);
        assertJson(StrategyStatisticalV5.connectedPlateau(ga, selectedAlias, 2, .5),
                oracle(plateauCall).path("value"));

        ObjectNode stress = MAPPER.createObjectNode().put("lineage_sha256", JsonHashes.sha256("lineage"))
                .put("sourceArtifactSha256", JsonHashes.sha256("source")).put("selectedCandidateId", "candidate-1")
                .put("pass", true);
        assertJson(StrategyStatisticalV5.makeStressDecision(stress),
                oracle(request("stress").set("options", stress)).path("value"));

        ObjectNode portfolio = MAPPER.createObjectNode().put("lineage_sha256", JsonHashes.sha256("lineage"))
                .put("sourceArtifactSha256", JsonHashes.sha256("source")).put("pass", true);
        portfolio.putArray("assetDecisions").addObject().put("asset", "btc").put("pass", true);
        portfolio.putArray("returnIncrements").addObject().put("episode_id", "e1").put("asset", "btc").put("net_r", .2);
        assertJson(StrategyStatisticalV5.makePortfolioDecision(portfolio),
                oracle(request("portfolio").set("options", portfolio)).path("value"));
    }

    @Test
    void behaviorDefinitionRegistryAndAppendMatchNodeExactly() throws Exception {
        String first = JsonHashes.sha256("registry-first");
        ObjectNode headArgs = MAPPER.createObjectNode().put("hypothesisFamily", "family")
                .put("datasetSha256", JsonHashes.sha256("dataset"));
        headArgs.putArray("entries").addObject().put("behavior_sha256", first)
                .put("dataset_sha256", JsonHashes.sha256("dataset"));
        ObjectNode firstHead = StrategyStatisticalV5.makeExposureHead(headArgs);
        ObjectNode registryArgs = MAPPER.createObjectNode().put("hypothesisFamily", "family").set("exposureHead", firstHead);
        ObjectNode firstDefinition = registryArgs.putArray("entries").addObject();
        firstDefinition.put("behavior_sha256", first).put("dataset_sha256", JsonHashes.sha256("dataset"))
                .put("observed_at", "2026-01-01T00:00:00Z").put("source", "SEARCH")
                .put("evaluator_sha256", JsonHashes.sha256("evaluator"))
                .put("precommit_sha256", JsonHashes.sha256("precommit")).putNull("lifecycle_sha256");
        firstDefinition.putObject("chromosome").put("direction", "long").put("threshold", 2);
        ObjectNode expected = (ObjectNode) oracle(request("makeRegistry").set("options", registryArgs)).path("value");
        ObjectNode registry = StrategyStatisticalV5.makeBehaviorDefinitionRegistry(registryArgs);
        assertJson(registry, expected);
        ObjectNode validateCall = request("validateRegistry"); validateCall.set("value", registry);
        validateCall.putObject("options").set("exposureHead", firstHead);
        assertThat(StrategyStatisticalV5.validateBehaviorDefinitionRegistry(registry,
                (ObjectNode) validateCall.path("options"))).isEqualTo(oracle(validateCall).path("value").asBoolean());

        String second = JsonHashes.sha256("registry-second");
        ObjectNode appendHead = MAPPER.createObjectNode().set("prior", firstHead);
        appendHead.put("datasetSha256", JsonHashes.sha256("dataset"));
        appendHead.putArray("behaviorAliases").add(second); appendHead.put("exposureAttemptCount", 1);
        ObjectNode nextHead = StrategyStatisticalV5.appendExposureHead(appendHead);
        ObjectNode append = MAPPER.createObjectNode().set("prior", registry); append.set("exposureHead", nextHead);
        append.put("expectedExposureHeadSha256", firstHead.path("content_sha256").asText());
        ObjectNode secondDefinition = append.putArray("definitions").addObject();
        secondDefinition.put("behavior_sha256", second).put("dataset_sha256", JsonHashes.sha256("dataset"))
                .put("evaluator_sha256", JsonHashes.sha256("evaluator-2"));
        secondDefinition.putObject("chromosome").put("direction", "short").put("threshold", 3);
        assertJson(StrategyStatisticalV5.appendBehaviorDefinitionRegistry(append),
                oracle(request("appendRegistry").set("options", append)).path("value"));

        ObjectNode foldArgs = MAPPER.createObjectNode().put("endAt", "2026-08-31T23:59:59.000Z");
        foldArgs.putArray("episodes").addObject().put("decision_time", "2024-09-01T00:00:00Z");
        assertJson(StrategyStatisticalV5.makeQuarterlyFolds(foldArgs),
                oracle(request("folds").set("options", foldArgs)).path("value"));
    }

    @Test
    void physicalExposureHeadInitializationAndCasAppendMatchNode(@TempDir Path temporary) throws Exception {
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(MAPPER.createObjectNode()
                .put("hypothesisFamily", "physical-family").put("datasetSha256", JsonHashes.sha256("physical-data")));
        Path javaPath = temporary.resolve("java/head.json"); Path nodePath = temporary.resolve("node/head.json");
        ObjectNode javaInit = MAPPER.createObjectNode().put("filePath", javaPath.toString()).set("head", head);
        ObjectNode nodeInit = MAPPER.createObjectNode().put("filePath", nodePath.toString()).set("head", head);
        ObjectNode actual = StrategyStatisticalV5.initializeExposureHeadFile(javaInit);
        assertJson(actual, oracle(request("initExposureFile").set("options", nodeInit)).path("value"));
        assertJson(StrategyStatisticalV5.readExposureHeadFile(javaPath), head);

        String behavior = JsonHashes.sha256("physical-behavior");
        ObjectNode javaAppend = MAPPER.createObjectNode().put("filePath", javaPath.toString())
                .put("expectedHeadSha256", head.path("content_sha256").asText())
                .put("datasetSha256", JsonHashes.sha256("physical-data-2")).put("exposureAttemptCount", 2);
        javaAppend.putArray("behaviorAliases").add(behavior); javaAppend.put("observedAt", "2026-06-01T00:00:00Z");
        ObjectNode nodeAppend = javaAppend.deepCopy(); nodeAppend.put("filePath", nodePath.toString());
        assertJson(StrategyStatisticalV5.appendExposureHeadFile(javaAppend),
                oracle(request("appendExposureFile").set("options", nodeAppend)).path("value"));
        assertThatThrownBy(() -> StrategyStatisticalV5.appendExposureHeadFile(javaAppend))
                .hasMessage("stale or competing exposure head predecessor");
        assertThatThrownBy(() -> StrategyStatisticalV5.initializeExposureHeadFile(javaInit))
                .hasMessageContaining("exposure head already exists");
    }

    @Test
    void physicalBehaviorRegistryCasSnapshotAndReopenMatchNode(@TempDir Path temporary) throws Exception {
        String behavior = JsonHashes.sha256("physical-registry-behavior");
        ObjectNode headArgs = MAPPER.createObjectNode().put("hypothesisFamily", "physical-registry")
                .put("datasetSha256", JsonHashes.sha256("physical-registry-data"));
        headArgs.putArray("entries").addObject().put("behavior_sha256", behavior)
                .put("dataset_sha256", JsonHashes.sha256("physical-registry-data"));
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
        ObjectNode definition = MAPPER.createObjectNode().put("behavior_sha256", behavior)
                .put("dataset_sha256", JsonHashes.sha256("physical-registry-data"))
                .put("evaluator_sha256", JsonHashes.sha256("physical-registry-evaluator"));
        definition.putObject("chromosome").put("direction", "long").put("threshold", 4);
        Path javaState = temporary.resolve("java-registry/state.json");
        Path nodeState = temporary.resolve("node-registry/state.json");
        Files.createDirectories(javaState.getParent()); Files.createDirectories(nodeState.getParent());
        ObjectNode javaAppend = MAPPER.createObjectNode().put("filePath", javaState.toString()).set("exposureHead", head);
        javaAppend.putArray("definitions").add(definition);
        ObjectNode nodeAppend = javaAppend.deepCopy(); nodeAppend.put("filePath", nodeState.toString());
        ObjectNode registry = StrategyStatisticalV5.appendBehaviorDefinitionRegistryFile(javaAppend);
        assertJson(registry, oracle(request("appendRegistryFile").set("options", nodeAppend)).path("value"));
        assertJson(StrategyStatisticalV5.readBehaviorDefinitionRegistryFile(javaState), registry);

        Path javaSnapshot = javaState.getParent().resolve("snapshot.json");
        Path nodeSnapshot = nodeState.getParent().resolve("snapshot.json");
        Files.writeString(javaSnapshot, MAPPER.writeValueAsString(registry) + "\n", StandardCharsets.UTF_8);
        Files.writeString(nodeSnapshot, MAPPER.writeValueAsString(registry) + "\n", StandardCharsets.UTF_8);
        ObjectNode javaBind = MAPPER.createObjectNode().put("filePath", javaState.toString())
                .put("expectedRegistrySha256", registry.path("content_sha256").asText())
                .put("snapshotPath", javaSnapshot.toString()).set("snapshot", registry);
        ObjectNode nodeBind = javaBind.deepCopy().put("filePath", nodeState.toString())
                .put("snapshotPath", nodeSnapshot.toString());
        ObjectNode bound = StrategyStatisticalV5.bindBehaviorDefinitionRegistrySnapshotFile(javaBind);
        assertJson(bound, oracle(request("bindRegistrySnapshot").set("options", nodeBind)).path("value"));
        ObjectNode javaResolve = MAPPER.createObjectNode().put("filePath", javaState.toString());
        ObjectNode nodeResolve = MAPPER.createObjectNode().put("filePath", nodeState.toString());
        JsonNode actual = StrategyStatisticalV5.resolveBehaviorDefinitionRegistrySnapshotFile(javaResolve);
        JsonNode expected = oracle(request("resolveRegistrySnapshot").set("options", nodeResolve)).path("value");
        assertJson(actual.path("value"), expected.path("value"));
        assertThat(actual.path("byte_sha256").asText()).isEqualTo(expected.path("byte_sha256").asText());

        Path extraLink = javaState.getParent().resolve("snapshot-hardlink.json"); Files.createLink(extraLink, javaSnapshot);
        assertThatThrownBy(() -> StrategyStatisticalV5.resolveBehaviorDefinitionRegistrySnapshotFile(javaResolve))
                .hasMessageContaining("regular single-link");
    }

    @Test
    void cumulativeVectorInventoryAndAuditValidationMatchNode() throws Exception {
        String alias = JsonHashes.sha256("vector-alias");
        ObjectNode headArgs = MAPPER.createObjectNode().put("hypothesisFamily", "vectors")
                .put("datasetSha256", JsonHashes.sha256("vector-data"));
        headArgs.putArray("entries").addObject().put("behavior_sha256", alias)
                .put("dataset_sha256", JsonHashes.sha256("vector-data"));
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
        ObjectNode vectorArgs = MAPPER.createObjectNode().set("exposureHead", head);
        vectorArgs.putArray("episodeIds").add("e1").add("e2");
        var rows = vectorArgs.putObject("vectors").putArray(alias);
        rows.addObject().put("episode_id", "e2").put("net_r", 0).put("traded", false).put("eligible", false);
        rows.addObject().put("episode_id", "e1").put("net_r", .3).put("traded", true).put("eligible", true);
        ObjectNode vectors = StrategyStatisticalV5.makeVectorInventory(vectorArgs);
        assertJson(vectors, oracle(request("makeVectors").set("options", vectorArgs)).path("value"));
        ObjectNode vectorCall = request("validateVectors"); vectorCall.set("value", vectors); vectorCall.set("head", head);
        vectorCall.set("episodeIds", vectorArgs.path("episodeIds"));
        assertThat(StrategyStatisticalV5.validateVectorInventory(vectors, head, vectorArgs.path("episodeIds")))
                .isEqualTo(oracle(vectorCall).path("value").asBoolean());

        ObjectNode audit = MAPPER.createObjectNode().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("audit"))
                .put("version", 1).put("fail_closed_missing_inputs", true).put("pass", false)
                .put("decision", "REJECTED").put("independent_opportunity_count", 2)
                .put("independent_trade_count", 1)
                .put("market_cluster_inventory_sha256", JsonHashes.sha256("clusters"));
        ObjectNode gates = audit.putObject("gates");
        for (String gate : new String[] {"hard_metrics", "baseline_comparison", "bootstrap_p20_positive",
                "weighted_bootstrap_p20_positive", "max_statistic", "search_adjusted_expectancy_positive",
                "dsr", "pbo", "minimum_independent_episodes", "recent_oos_positive", "earlier_blocks",
                "positive_years", "positive_outer_folds", "plateau", "neighbour_fraction", "seed_stability",
                "null_controls", "stress_ablation", "asset_decisions", "portfolio"}) gates.put(gate, false);
        audit = StrategyStatisticalV5.withHash(audit);
        assertThat(StrategyStatisticalV5.validateStatisticalAudit(audit)).isTrue();
        assertThat(oracle(request("validateAudit").set("value", audit)).path("value").asBoolean()).isTrue();

        ObjectNode space = MAPPER.createObjectNode(); var genes = space.putArray("genes");
        genes.addObject().put("name", "threshold").put("type", "continuous").put("min", 0)
                .put("max", 1).put("step", .1).put("default", .5).put("usage", "execution");
        genes.addObject().put("name", "window").put("type", "ordered-discrete")
                .putArray("values").add(5).add(10).add(20);
        ObjectNode chromosome = MAPPER.createObjectNode().put("threshold", .5).put("window", 10);
        ObjectNode neighbourCall = request("neighbours"); neighbourCall.set("space", space); neighbourCall.set("value", chromosome);
        assertJson(StrategyStatisticalV5.enumerateDirectNeighbours(space, chromosome),
                oracle(neighbourCall).path("value"));
    }

    @Test
    void geneticCheckpointContentCasJournalAndLockRecoveryMatchNode(@TempDir Path temporary) throws Exception {
        String behavior = JsonHashes.sha256("checkpoint-behavior");
        ObjectNode headArgs = MAPPER.createObjectNode().put("hypothesisFamily", "checkpoint-family")
                .put("datasetSha256", JsonHashes.sha256("checkpoint-data"));
        headArgs.putArray("entries").addObject().put("behavior_sha256", behavior)
                .put("dataset_sha256", JsonHashes.sha256("checkpoint-data"));
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
        ObjectNode artifactArgs = MAPPER.createObjectNode(); ObjectNode lineage = artifactArgs.putObject("lineage");
        for (String key : new String[] {"dataset_sha256", "candidate_set_sha256", "feature_set_sha256",
                "label_set_sha256", "execution_set_sha256"}) lineage.put(key, JsonHashes.sha256("checkpoint-" + key));
        artifactArgs.putArray("candidates").addObject().put("candidate_id", "candidate-1").put("behavior_sha256", behavior);
        artifactArgs.set("exposureHead", head); episode(artifactArgs.putArray("episodes").addObject(), "cp-e1", "btc",
                "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", .2, true);
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactArgs);
        ObjectNode space = MAPPER.createObjectNode(); space.putArray("genes").addObject().put("name", "threshold")
                .put("type", "continuous").put("min", 0).put("max", 1).put("step", .1).put("default", .5);
        ObjectNode checkpointArgs = MAPPER.createObjectNode().set("artifact", artifact);
        checkpointArgs.set("exposureHead", head); checkpointArgs.set("geneSpace", space);
        checkpointArgs.put("foldId", "outer-1").put("seed", 11).put("generation", 0).put("seedIndex", 0)
                .put("rngState", 123).put("plateau", 0).put("paretoSignature", "p0").put("checkpointStatus", "RUNNING");
        ObjectNode config = checkpointArgs.putObject("config").put("population", 4).put("generations", 2)
                .put("minGenerations", 1).put("plateauGenerations", 1).put("crossoverProbability", .9)
                .putNull("mutationProbability").put("halfLifeMonths", 18)
                .put("operator", "NSGA_II_TYPED").put("scheduler_ordering", "SEED_GENERATION_ORDINAL")
                .put("mode", "FIXTURE");
        config.putArray("seeds").add(11).add(23).add(47);
        checkpointArgs.putArray("population");
        checkpointArgs.putArray("history"); checkpointArgs.putArray("seedFinalists"); checkpointArgs.putArray("seedMembership");
        ObjectNode checkpoint = StrategyStatisticalV5.makeGeneticCheckpoint(checkpointArgs);
        assertJson(checkpoint, oracle(request("makeCheckpoint").set("options", checkpointArgs)).path("value"));
        ObjectNode validate = request("validateCheckpoint"); validate.set("value", checkpoint);
        validate.set("options", checkpointArgs);
        assertThat(StrategyStatisticalV5.validateGeneticCheckpoint(checkpoint, checkpointArgs))
                .isEqualTo(oracle(validate).path("value").asBoolean());

        Path javaPath = temporary.resolve("java-checkpoint/checkpoint.json");
        Path nodePath = temporary.resolve("node-checkpoint/checkpoint.json");
        Files.createDirectories(javaPath.getParent()); Files.createDirectories(nodePath.getParent());
        ObjectNode javaWrite = MAPPER.createObjectNode().put("filePath", javaPath.toString())
                .put("expectedExposureHeadSha256", head.path("content_sha256").asText()).set("checkpoint", checkpoint);
        ObjectNode nodeWrite = javaWrite.deepCopy().put("filePath", nodePath.toString());
        assertJson(StrategyStatisticalV5.writeGeneticCheckpointFile(javaWrite),
                oracle(request("writeCheckpoint").set("options", nodeWrite)).path("value"));
        assertJson(StrategyStatisticalV5.readGeneticCheckpointFile(javaPath),
                oracle(request("readCheckpoint").put("filePath", nodePath.toString())).path("value"));
        assertThat(Files.readAllLines(Path.of(javaPath + ".jsonl"))).hasSize(1);

        Path javaLock = Path.of(javaPath + ".lock"); Path nodeLock = Path.of(nodePath + ".lock");
        Files.writeString(javaLock, "lock"); Files.writeString(nodeLock, "lock");
        ObjectNode javaRecover = MAPPER.createObjectNode().put("filePath", javaPath.toString())
                .put("force", true).put("maxAgeMs", -1);
        ObjectNode nodeRecover = javaRecover.deepCopy().put("filePath", nodePath.toString());
        assertThat(StrategyStatisticalV5.recoverStaleCheckpointLock(javaRecover))
                .isEqualTo(oracle(request("recoverCheckpointLock").set("options", nodeRecover)).path("value").asBoolean());
    }

    @Test
    void combinatorialPboFoldAndEpisodePanelsMatchNodeExactly() throws Exception {
        var folds = MAPPER.createArrayNode();
        for (int index = 0; index < 4; index++) {
            ObjectNode fold = folds.addObject(); fold.putObject("candidate_means")
                    .put("a", index % 2 == 0 ? .3 : -.1).put("b", index % 2 == 0 ? .1 : .2);
        }
        ObjectNode options = MAPPER.createObjectNode().put("purgeDays", 30).put("embargoDays", 7)
                .put("requireTimestamps", false);
        ObjectNode call = request("pbo"); call.set("folds", folds); call.put("selected", "a"); call.set("options", options);
        assertJson(StrategyStatisticalV5.pboFromFolds(folds, "a", options), oracle(call).path("value"));

        var observedFolds = MAPPER.createArrayNode();
        for (int index = 0; index < 4; index++) {
            ObjectNode observation = observedFolds.addObject().putArray("observations").addObject();
            observation.put("episode_id", "obs-" + index)
                    .put("decision_time", "2026-0" + (index + 1) + "-01T00:00:00Z")
                    .put("resolution_time", "2026-0" + (index + 1) + "-02T00:00:00Z");
            observation.putObject("candidate_means").put("a", index < 2 ? .3 : -.1)
                    .put("b", index < 2 ? .1 : .2);
        }
        ObjectNode observedOptions = MAPPER.createObjectNode().put("purgeDays", 0).put("embargoDays", 0);
        ObjectNode observedCall = request("pbo"); observedCall.set("folds", observedFolds);
        observedCall.put("selected", "a"); observedCall.set("options", observedOptions);
        assertJson(StrategyStatisticalV5.pboFromFolds(observedFolds, "a", observedOptions),
                oracle(observedCall).path("value"));
    }

    @Test
    void nullReplayArtifactAndTransformationProofMatchNodeExactly() throws Exception {
        String behavior = JsonHashes.sha256("null-replay-behavior");
        ObjectNode headArgs = MAPPER.createObjectNode().put("hypothesisFamily", "null-replay")
                .put("datasetSha256", JsonHashes.sha256("null-replay-data"));
        headArgs.putArray("entries").addObject().put("behavior_sha256", behavior)
                .put("dataset_sha256", JsonHashes.sha256("null-replay-data"));
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
        ObjectNode artifactArgs = MAPPER.createObjectNode(); ObjectNode lineage = artifactArgs.putObject("lineage");
        for (String key : new String[] {"dataset_sha256", "candidate_set_sha256", "feature_set_sha256",
                "label_set_sha256", "execution_set_sha256"}) lineage.put(key, JsonHashes.sha256("null-" + key));
        artifactArgs.putArray("candidates").addObject().put("candidate_id", "candidate-1").put("behavior_sha256", behavior);
        artifactArgs.set("exposureHead", head); episode(artifactArgs.putArray("episodes").addObject(), "nr-e1", "btc",
                "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z", .4, true);
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactArgs);
        ObjectNode options = MAPPER.createObjectNode().set("artifact", artifact);
        options.put("method", "block_permuted_labels");
        options.putObject("candidateReturns").putObject("nr-e1").putObject("candidate-1")
                .put("net_r", -.2).put("traded", true);
        options.putObject("transformation").put("method", "block_permuted_labels").put("block_length", 1)
                .put("permutation_sha256", JsonHashes.sha256("permutation"))
                .put("labels_source_sha256", JsonHashes.sha256("labels"))
                .put("block_order_sha256", JsonHashes.sha256("blocks"));
        assertJson(StrategyStatisticalV5.makeNullReplayArtifact(options),
                oracle(request("nullReplay").set("options", options)).path("value"));
    }

    @Test
    void nullControlsFixtureAndAuthoritativeFailClosedMatchNodeExactly() throws Exception {
        String behavior = JsonHashes.sha256("null-control-behavior");
        ObjectNode headArgs = MAPPER.createObjectNode().put("hypothesisFamily", "null-controls")
                .put("datasetSha256", JsonHashes.sha256("null-control-data"));
        headArgs.putArray("entries").addObject().put("behavior_sha256", behavior)
                .put("dataset_sha256", JsonHashes.sha256("null-control-data"));
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
        ObjectNode artifactArgs = MAPPER.createObjectNode(); ObjectNode lineage = artifactArgs.putObject("lineage");
        for (String key : new String[] {"dataset_sha256", "candidate_set_sha256", "feature_set_sha256",
                "label_set_sha256", "execution_set_sha256"}) lineage.put(key, JsonHashes.sha256("control-" + key));
        artifactArgs.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", behavior);
        artifactArgs.set("exposureHead", head); var episodes = artifactArgs.putArray("episodes");
        episode(episodes.addObject(), "nc-e1", "btc", "2026-03-01T00:00:00Z",
                "2026-03-02T00:00:00Z", .4, true);
        episode(episodes.addObject(), "nc-e2", "eth", "2026-04-01T00:00:00Z",
                "2026-04-02T00:00:00Z", -.1, true);
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactArgs);

        ObjectNode options = MAPPER.createObjectNode().set("artifact", artifact);
        options.put("selectedCandidateId", "candidate-1").put("mode", "FIXTURE")
                .put("iterations", 16).put("sequentialBatchSize", 4).put("seed", 23).put("alpha", .05);
        options.putObject("selectionBudget").put("population", 2).put("generations", 1)
                .putArray("seeds").add(11).add(23).add(47);
        var selectedRows = options.putArray("selectedOutcomeRows");
        selectedRows.addObject().put("episode_id", "nc-e1").put("net_r", .3).put("traded", true);
        selectedRows.addObject().put("episode_id", "nc-e2").put("net_r", -.2).put("traded", true);

        java.util.Map<String, StrategyStatisticalV5.NullReplayMethod> methods = new java.util.LinkedHashMap<>();
        for (String method : java.util.List.of("block_permuted_labels", "timestamp_shifted_outcomes",
                "frequency_matched_random_intents", "winners_curse_selection")) {
            methods.put(method, replayArgs -> (ObjectNode) replayArgs.path("artifact").deepCopy());
        }
        StrategyStatisticalV5.NullReplaySuite replay = new StrategyStatisticalV5.NullReplaySuite(methods);
        assertJson(StrategyStatisticalV5.runNullControlsV5(options, replay),
                oracle(request("runNullFixture").set("options", options)).path("value"));

        ObjectNode authoritative = options.deepCopy(); authoritative.put("mode", "AUTHORITATIVE");
        authoritative.remove("selectedOutcomeRows");
        assertJson(StrategyStatisticalV5.runNullControlsV5(authoritative),
                oracle(request("runNullUnsupported").set("options", authoritative)).path("value"));

        ObjectNode missingReplay = options.deepCopy();
        assertSameFailure(request("runNullUnsupported").set("options", missingReplay),
                () -> StrategyStatisticalV5.runNullControlsV5(missingReplay));
    }

    @Test
    void physicalNullRunnerRejectsCallerForgedTrustAndNestedBindingIsExplicit() throws Exception {
        ObjectNode provenance = MAPPER.createObjectNode()
                .put("schema", "strategy-v5-statistical-worker/1")
                .put("verified", true).put("deterministic", true)
                .put("artifact_paths_bound", true).put("physical_role_binding", true)
                .put("worker_count", 2).put("memory_budget_mb", 256)
                .put("source_manifest_sha256", JsonHashes.sha256("physical-manifest"))
                .put("feature_artifact_sha256", JsonHashes.sha256("physical-features"))
                .put("label_artifact_sha256", JsonHashes.sha256("physical-labels"))
                .put("execution_artifact_sha256", JsonHashes.sha256("physical-execution"))
                .put("physical_null_code_sha256", JsonHashes.sha256("physical-code"));
        StrategyEvaluatorV5.Evaluator evaluator = new StrategyEvaluatorV5.Evaluator() {
            @Override public ObjectNode evaluate(ObjectNode args) { return MAPPER.createObjectNode(); }
            @Override public ObjectNode workerProvenance() { return provenance.deepCopy(); }
            @Override public boolean physicalNullSelectionVerified() { return true; }
        };
        ObjectNode options = MAPPER.createObjectNode();
        ObjectNode request = request("physicalNullRunner").set("options", options);
        request.set("provenance", provenance);
        assertSameFailure(request,
                () -> StrategyStatisticalV5.makePhysicalNullRunnerV5(options, evaluator));

        assertThat(StrategyStatisticalV5.class.getMethod("runNestedWfoV5", ObjectNode.class,
                StrategyEvaluatorV5.Evaluator.class, StrategyStatisticalV5.StatisticalProvider.class,
                StrategyStatisticalV5.StatisticalProvider.class, StrategyStatisticalV5.StatisticalProvider.class,
                StrategyStatisticalV5.NullReplaySuite.class, StrategyStatisticalV5.CheckpointPathFactory.class,
                StrategyStatisticalV5.PhysicalNullRunner.class)).isNotNull();
    }

    @Test
    void nullCalibrationMatchesNodeExactly() throws Exception {
        String behavior = JsonHashes.sha256("null-calibration-behavior");
        ObjectNode headArgs = MAPPER.createObjectNode().put("hypothesisFamily", "null-calibration")
                .put("datasetSha256", JsonHashes.sha256("null-calibration-data"));
        headArgs.putArray("entries").addObject().put("behavior_sha256", behavior)
                .put("dataset_sha256", JsonHashes.sha256("null-calibration-data"));
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
        ObjectNode artifactArgs = MAPPER.createObjectNode(); ObjectNode lineage = artifactArgs.putObject("lineage");
        for (String key : new String[] {"dataset_sha256", "candidate_set_sha256", "feature_set_sha256",
                "label_set_sha256", "execution_set_sha256"}) lineage.put(key, JsonHashes.sha256("cal-" + key));
        artifactArgs.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", behavior);
        artifactArgs.set("exposureHead", head); var episodes = artifactArgs.putArray("episodes");
        episode(episodes.addObject(), "cal-e1", "btc", "2026-05-01T00:00:00Z",
                "2026-05-02T00:00:00Z", .4, true);
        episode(episodes.addObject(), "cal-e2", "eth", "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z", -.1, true);
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactArgs);
        ObjectNode options = MAPPER.createObjectNode(); ObjectNode noEdge = MAPPER.createObjectNode();
        noEdge.set("artifact", artifact); noEdge.put("selectedCandidateId", "candidate-1").put("fixtureId", "no-edge");
        ObjectNode planted = MAPPER.createObjectNode(); planted.set("artifact", artifact);
        planted.put("selectedCandidateId", "candidate-1").put("fixtureId", "planted");
        options.putArray("noEdgeFixtures").add(noEdge); options.putArray("plantedEdgeFixtures").add(planted);
        options.putArray("seeds").add(47).add(11).add(23).add(11); options.put("iterations", 8)
                .put("alpha", .05).put("typeICeiling", .10).put("minPower", .80);
        options.putObject("selectionBudget").put("population", 2).put("generations", 1)
                .putArray("seeds").add(11).add(23).add(47);
        java.util.Map<String, StrategyStatisticalV5.NullReplayMethod> methods = new java.util.LinkedHashMap<>();
        for (String method : java.util.List.of("block_permuted_labels", "timestamp_shifted_outcomes",
                "frequency_matched_random_intents", "winners_curse_selection")) {
            methods.put(method, replayArgs -> (ObjectNode) replayArgs.path("artifact").deepCopy());
        }
        assertJson(StrategyStatisticalV5.calibrateNullControlsV5(options,
                        new StrategyStatisticalV5.NullReplaySuite(methods)),
                oracle(request("calibrateNull").set("options", options)).path("value"));
    }

    @Test
    void geneticArtifactValidationMatchesNodeExactly() throws Exception {
        String behavior = JsonHashes.sha256("ga-behavior"); String alias = JsonHashes.sha256("ga-alias");
        ObjectNode run = MAPPER.createObjectNode().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("genetic"))
                .put("version", 1).put("fold_id", "outer-1").put("evaluated_k", 1)
                .put("evaluation_attempt_k", 1).put("chromosome_evaluated_k", 1)
                .put("cumulative_k", 1).put("cumulative_exposure_k", 1)
                .put("exposure_head_sha256", JsonHashes.sha256("ga-head"))
                .putNull("selected_behavior_alias_sha256").put("selected_seed_count", 0).putNull("baseline")
                .putNull("selected");
        run.putObject("config").put("mode", "FIXTURE"); run.putObject("gene_space");
        run.putArray("training_episode_ids");
        run.putArray("population_history").addObject().put("behavior_sha256", behavior)
                .put("behavior_alias_sha256", alias).put("evaluation_attempt_sha256", JsonHashes.sha256("ga-attempt"))
                .put("generation", 0).putArray("parent_ids");
        var seeds = run.putArray("seed_runs");
        for (int seed : new int[] {11, 23, 47}) seeds.addObject().put("seed", seed);
        run.putArray("evaluated_behavior_aliases").add(alias);
        run.putObject("seed_stability").putArray("stable_aliases"); run.putArray("neighbours");
        run = StrategyStatisticalV5.withHash(run);
        assertThat(StrategyStatisticalV5.validateGeneticArtifact(run)).isTrue();
        assertThat(oracle(request("validateGenetic").set("value", run)).path("value").asBoolean()).isTrue();
    }

    @Test
    void aggregateAssetDecisionAndBootstrapMatchNodeExactly() throws Exception {
        var rows = MAPPER.createArrayNode();
        for (int index = 0; index < 2; index++) {
            String year = index == 0 ? "2025" : "2026";
            ObjectNode fold = rows.addObject().put("asset", "btc").put("pass", true)
                    .put("content_sha256", JsonHashes.sha256("asset-fold-" + index));
            fold.putObject("metrics").put("traded_count", 2).put("expectancy_r", .4 + index * .1)
                    .put("cost_r", .02).put("coverage_fraction", .99).put("capacity_pass", true)
                    .put("max_drawdown_r", 0).put("profit_factor", 4).put("complexity", 2 + index);
            fold.putObject("stress").put("pass", true);
            var vector = fold.putArray("selected_return_vector");
            vector.addObject().put("episode_id", "asset-" + index + "-a").put("asset", "btc")
                    .put("decision_time", year + "-01-01T00:00:00Z").put("net_r", .3 + index * .1)
                    .put("traded", true);
            vector.addObject().put("episode_id", "asset-" + index + "-b").put("asset", "btc")
                    .put("decision_time", year + "-02-01T00:00:00Z").put("net_r", .5 + index * .1)
                    .put("traded", true);
        }
        ObjectNode required = MAPPER.createObjectNode().put("mode", "FIXTURE")
                .put("bootstrapIterations", 32).put("seed", 23).put("halfLifeMonths", 36)
                .put("minPositiveFolds", 1).put("minPositiveYears", 1).put("minTradesPerYear", 1)
                .put("minEpisodes", 2).put("minExpectancy", 0).put("minProfitFactor", 1)
                .put("maxDrawdownR", 2).put("maxCostR", .2).put("minCoverage", .9)
                .put("requireCapacityPass", true);
        ObjectNode call = request("aggregateAsset"); call.set("rows", rows); call.set("required", required);
        assertJson(StrategyStatisticalV5.aggregateAssetDecision(rows, required), oracle(call).path("value"));

        var empty = MAPPER.createArrayNode(); ObjectNode emptyCall = request("aggregateAsset");
        emptyCall.set("rows", empty); emptyCall.set("required", MAPPER.createObjectNode());
        assertJson(StrategyStatisticalV5.aggregateAssetDecision(empty), oracle(emptyCall).path("value"));
    }

    @Test
    void statisticalAuditRunnerMatchesNodeExactly() throws Exception {
        String alias = JsonHashes.sha256("audit-runner-alias"); String dataset = JsonHashes.sha256("audit-runner-data");
        ObjectNode headArgs = MAPPER.createObjectNode().put("hypothesisFamily", "audit-runner")
                .put("datasetSha256", dataset);
        headArgs.putArray("entries").addObject().put("behavior_sha256", alias).put("dataset_sha256", dataset);
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
        ObjectNode artifactArgs = MAPPER.createObjectNode(); ObjectNode lineage = artifactArgs.putObject("lineage");
        for (String key : new String[] {"dataset_sha256", "candidate_set_sha256", "feature_set_sha256",
                "label_set_sha256", "execution_set_sha256"}) lineage.put(key, JsonHashes.sha256("audit-" + key));
        artifactArgs.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", alias);
        artifactArgs.set("exposureHead", head); var episodes = artifactArgs.putArray("episodes");
        episode(episodes.addObject(), "audit-e1", "btc", "2025-01-01T00:00:00Z", "2025-01-02T00:00:00Z", .4, true);
        episode(episodes.addObject(), "audit-e2", "btc", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z", -.1, true);
        episode(episodes.addObject(), "audit-e3", "btc", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", .3, true);
        episode(episodes.addObject(), "audit-e4", "btc", "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z", 0, false);
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactArgs);
        ObjectNode options = MAPPER.createObjectNode().set("artifact", artifact);
        options.set("exposureHead", head); options.put("selectedCandidateId", "candidate-1");
        options.putNull("selectedOutcomeRows"); options.putNull("vectorInventory"); options.putNull("selectedMetrics");
        options.putNull("trainingWeightedBootstrapP20"); options.putArray("trainingWeightedBootstrapP20s");
        options.putArray("folds"); options.putNull("genetic"); options.putArray("geneticRuns");
        options.putNull("nullControls"); options.putArray("assetDecisions"); options.putArray("stressDecisions");
        options.putNull("portfolioDecision"); options.putObject("config").put("mode", "FIXTURE")
                .put("bootstrapIterations", 16).put("maxStatIterations", 16).put("seed", 23);
        assertJson(StrategyStatisticalV5.runStatisticalAuditV5(options),
                oracle(request("runAudit").set("options", options)).path("value"));
    }

    @Test
    void exposureRegistryJournalIsIdempotentAndRecoversBothCrashBoundaries(@TempDir Path temporary)
            throws Exception {
        String first = JsonHashes.sha256("journal-first"); String second = JsonHashes.sha256("journal-second");
        String dataset = JsonHashes.sha256("journal-dataset");
        ObjectNode priorArgs = MAPPER.createObjectNode().put("hypothesisFamily", "journal-family")
                .put("datasetSha256", dataset);
        priorArgs.putArray("entries").addObject().put("behavior_sha256", first).put("dataset_sha256", dataset);
        ObjectNode prior = StrategyStatisticalV5.makeExposureHead(priorArgs);
        ObjectNode nextArgs = MAPPER.createObjectNode().set("prior", prior);
        nextArgs.put("datasetSha256", dataset).put("exposureAttemptCount", 1);
        nextArgs.putArray("behaviorAliases").add(second);
        ObjectNode next = StrategyStatisticalV5.appendExposureHead(nextArgs);

        Path headPath = temporary.resolve("journal/head.json"); Path registryPath = temporary.resolve("journal/registry.json");
        Path journalPath = temporary.resolve("journal/transaction.json");
        StrategyStatisticalV5.initializeExposureHeadFile(MAPPER.createObjectNode().put("filePath", headPath.toString())
                .set("head", prior));
        ObjectNode firstDefinition = MAPPER.createObjectNode().put("behavior_sha256", first)
                .put("dataset_sha256", dataset).put("evaluator_sha256", JsonHashes.sha256("journal-evaluator-1"));
        firstDefinition.putObject("chromosome").put("direction", "long").put("threshold", 2);
        ObjectNode registryArgs = MAPPER.createObjectNode().put("filePath", registryPath.toString())
                .set("exposureHead", prior);
        registryArgs.putArray("definitions").add(firstDefinition);
        ObjectNode priorRegistry = StrategyStatisticalV5.appendBehaviorDefinitionRegistryFile(registryArgs);

        ObjectNode secondDefinition = MAPPER.createObjectNode().put("behavior_sha256", second)
                .put("dataset_sha256", dataset).put("evaluator_sha256", JsonHashes.sha256("journal-evaluator-2"));
        secondDefinition.putObject("chromosome").put("direction", "short").put("threshold", 3);
        ObjectNode prepare = MAPPER.createObjectNode().put("journalPath", journalPath.toString())
                .put("exposureHeadPath", headPath.toString()).put("registryPath", registryPath.toString())
                .put("priorRegistrySha256", priorRegistry.path("content_sha256").asText())
                .set("priorHead", prior);
        prepare.set("nextHead", next); prepare.putArray("definitions").add(secondDefinition);
        ObjectNode prepared = StrategyStatisticalV5.writeExposureRegistryJournal(prepare);
        assertJson(prepared, oracle(request("writeRegistryJournal").set("options", prepare)).path("value"));

        ObjectNode recover = MAPPER.createObjectNode().put("journalPath", journalPath.toString());
        ObjectNode aborted = StrategyStatisticalV5.recoverExposureRegistryTransaction(recover);
        oracle(request("writeRegistryJournal").set("options", prepare));
        assertJson(aborted, oracle(request("recoverRegistryJournal").set("options", recover)).path("value"));
        assertThat(aborted.path("status").asText()).isEqualTo("ABORTED_BEFORE_HEAD_COMMIT");

        StrategyStatisticalV5.writeExposureRegistryJournal(prepare);
        Files.writeString(headPath, MAPPER.writeValueAsString(next) + "\n", StandardCharsets.UTF_8);
        ObjectNode recovered = StrategyStatisticalV5.recoverExposureRegistryTransaction(recover);
        oracle(request("writeRegistryJournal").set("options", prepare));
        assertJson(recovered, oracle(request("recoverRegistryJournal").set("options", recover)).path("value"));
        assertThat(recovered.path("status").asText()).isEqualTo("RECOVERED_REGISTRY");
        assertThat(StrategyStatisticalV5.readBehaviorDefinitionRegistryFile(registryPath)
                .path("exposure_head_sha256").asText()).isEqualTo(next.path("content_sha256").asText());
    }

    @Test
    void rejectedWfoAndRetainedOosEvidenceBindingMatchNodeExactly() throws Exception {
        String alias = JsonHashes.sha256("wfo-retained-alias"); String dataset = JsonHashes.sha256("wfo-data");
        ObjectNode headArgs = MAPPER.createObjectNode().put("hypothesisFamily", "wfo-family")
                .put("datasetSha256", dataset);
        headArgs.putArray("entries").addObject().put("behavior_sha256", alias).put("dataset_sha256", dataset);
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);

        ObjectNode artifactArgs = MAPPER.createObjectNode(); ObjectNode lineage = artifactArgs.putObject("lineage");
        for (String key : new String[] {"dataset_sha256", "candidate_set_sha256", "feature_set_sha256",
                "label_set_sha256", "execution_set_sha256"}) lineage.put(key, JsonHashes.sha256("wfo-" + key));
        String candidateId = "behavior:" + alias;
        artifactArgs.putArray("candidates").addObject().put("candidate_id", candidateId).put("behavior_sha256", alias);
        artifactArgs.set("exposureHead", head); ObjectNode episode = artifactArgs.putArray("episodes").addObject();
        episode.put("episode_id", "wfo-e1").put("asset", "btc")
                .put("decision_time", "2026-01-01T00:00:00Z")
                .put("resolution_time", "2026-01-02T00:00:00Z").put("eligible", true);
        episode.putObject("candidate_returns").putObject(candidateId).put("net_r", .25).put("traded", true);
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactArgs);
        ObjectNode vectorArgs = MAPPER.createObjectNode().set("exposureHead", head);
        vectorArgs.putArray("episodeIds").add("wfo-e1");
        vectorArgs.putObject("vectors").putArray(alias).addObject().put("episode_id", "wfo-e1")
                .put("net_r", .25).put("traded", true).put("eligible", true);
        ObjectNode vector = StrategyStatisticalV5.makeVectorInventory(vectorArgs);

        ObjectNode audit = MAPPER.createObjectNode().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("audit"))
                .put("version", 1).put("fail_closed_missing_inputs", true).put("pass", false)
                .put("decision", "REJECTED").put("independent_opportunity_count", 1)
                .put("independent_trade_count", 1).put("market_cluster_inventory_sha256", JsonHashes.sha256("wfo-clusters"))
                .put("exposure_head_sha256", head.path("content_sha256").asText());
        audit.putObject("max_statistic").put("cumulative_k", 1);
        ObjectNode gates = audit.putObject("gates");
        for (String gate : new String[] {"hard_metrics", "baseline_comparison", "bootstrap_p20_positive",
                "weighted_bootstrap_p20_positive", "max_statistic", "search_adjusted_expectancy_positive",
                "dsr", "pbo", "minimum_independent_episodes", "recent_oos_positive", "earlier_blocks",
                "positive_years", "positive_outer_folds", "plateau", "neighbour_fraction", "seed_stability",
                "null_controls", "stress_ablation", "asset_decisions", "portfolio"}) gates.put(gate, false);
        audit = StrategyStatisticalV5.withHash(audit);

        ObjectNode scope = MAPPER.createObjectNode().put("schema", "strategy-v5-statistical-asset-scope/1")
                .put("version", 1).putNull("source_sha256");
        scope.putArray("trade_assets").add("btc"); scope.putArray("replication_assets"); scope.putArray("context_assets");
        scope = StrategyStatisticalV5.withHash(scope);
        var folds = MAPPER.createArrayNode();
        for (int index = 1; index <= 8; index++) {
            ObjectNode fold = MAPPER.createObjectNode().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("fold"))
                    .put("version", 1).put("fold_id", "outer-" + index).put("status", "SKIPPED");
            folds.add(StrategyStatisticalV5.withHash(fold));
        }
        ObjectNode refit = MAPPER.createObjectNode().put("schema", "strategy-v5-statistical-development-refit/1")
                .put("version", 1).put("validation_audit_sha256", audit.path("content_sha256").asText())
                .put("validation_exposure_head_sha256", head.path("content_sha256").asText())
                .put("exposure_head_sha256", head.path("content_sha256").asText())
                .put("selected_from_outer_fold_winners", false)
                .put("excluded_from_retrospective_oos_audit", true).put("status", "REJECTED");
        refit.putArray("asset_refits"); refit = StrategyStatisticalV5.withHash(refit);
        ObjectNode wfo = MAPPER.createObjectNode().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("wfo"))
                .put("version", 1).put("fold_count", 8).set("folds", folds);
        wfo.set("asset_scope", scope); wfo.put("validation_exposure_head_sha256", head.path("content_sha256").asText())
                .put("validation_exposure_head_cumulative_k", 1).set("validation_exposure_head", head);
        wfo.put("exposure_head_sha256", head.path("content_sha256").asText()).put("cumulative_k", 1)
                .put("oos_artifact_sha256", artifact.path("content_sha256").asText())
                .put("vector_inventory_sha256", vector.path("content_sha256").asText()).put("oos_weighting", "UNWEIGHTED");
        wfo.putArray("oos_episode_ids").add("wfo-e1"); wfo.set("audit", audit); wfo.set("development_refit", refit);
        wfo.putArray("asset_decisions"); wfo.putArray("asset_decisions_final"); wfo.putNull("portfolio_decision");
        wfo.put("decision", "REJECTED").put("gate_pass", false); wfo = StrategyStatisticalV5.withHash(wfo);

        assertThat(StrategyStatisticalV5.validateNestedWfoArtifact(wfo)).isTrue();
        assertThat(oracle(request("validateWfo").set("value", wfo)).path("value").asBoolean()).isTrue();
        String label = "retained oracle"; ObjectNode binding = request("assertWfoBinding");
        binding.set("wfo", wfo); binding.set("artifact", artifact); binding.set("vector", vector); binding.put("label", label);
        assertThat(StrategyStatisticalV5.assertWfoRetainedOosBinding(wfo, artifact, vector, label))
                .isEqualTo(oracle(binding).path("value").asBoolean());
    }

    @Test
    void publicationTransactionPreparationMatchesNodeByteAndPathSemantics(@TempDir Path temporary)
            throws Exception {
        Path root = temporary.resolve("record"); Path headPath = root.resolve("control/head.json");
        Path registryPath = root.resolve("control/registry.json"); Path transactionPath = root.resolve("transactions/run.json");
        String alias = JsonHashes.sha256("publication-alias"); String dataset = JsonHashes.sha256("publication-data");
        ObjectNode headArgs = MAPPER.createObjectNode().put("hypothesisFamily", "publication-family")
                .put("datasetSha256", dataset);
        headArgs.putArray("entries").addObject().put("behavior_sha256", alias).put("dataset_sha256", dataset);
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
        StrategyStatisticalV5.initializeExposureHeadFile(MAPPER.createObjectNode().put("filePath", headPath.toString())
                .set("head", head));
        ObjectNode definition = MAPPER.createObjectNode().put("behavior_sha256", alias)
                .put("dataset_sha256", dataset).put("evaluator_sha256", JsonHashes.sha256("publication-evaluator"));
        definition.putObject("chromosome").put("direction", "long").put("threshold", 2);
        ObjectNode registryArgs = MAPPER.createObjectNode().put("filePath", registryPath.toString())
                .set("exposureHead", head);
        registryArgs.putArray("definitions").add(definition);
        ObjectNode registry = StrategyStatisticalV5.appendBehaviorDefinitionRegistryFile(registryArgs);
        ObjectNode wfo = rejectedWfo(head, "publication");
        ObjectNode run = rejectedResearchRun(head, wfo, "publication");
        ObjectNode options = MAPPER.createObjectNode().put("transactionPath", transactionPath.toString())
                .put("exposureHeadPath", headPath.toString()).put("registryPath", registryPath.toString())
                .put("recordRoot", root.toString()).put("expectedHeadSha256", head.path("content_sha256").asText())
                .put("expectedRegistrySha256", registry.path("content_sha256").asText()).set("nextHead", head);
        options.set("wfo", wfo); options.set("run", run); var artifacts = options.putArray("artifacts");
        artifacts.addObject().put("role", "wfo").put("path", "artifacts/final-wfo.json").set("value", wfo);
        artifacts.addObject().put("role", "research_run").put("path", "artifacts/research-run.json").set("value", run);
        assertJson(StrategyStatisticalV5.makeStatisticalPublicationTransaction(options),
                oracle(request("makePublication").set("options", options)).path("value"));

        ObjectNode escaped = options.deepCopy(); escaped.withArray("artifacts").get(0).deepCopy();
        ((ObjectNode) escaped.withArray("artifacts").get(0)).put("path", "../escape.json");
        assertSameFailure(request("makePublication").set("options", escaped),
                () -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(escaped));
    }

    @Test
    void publicationCommitRecoveryVerificationAndSecurityMatchNodeExactly(@TempDir Path temporary)
            throws Exception {
        Path root = temporary.resolve("physical-record"); Path headPath = root.resolve("control/head.json");
        Path registryPath = root.resolve("control/registry.json");
        Path transactionPath = root.resolve("transactions/run.json");
        String alias = JsonHashes.sha256("physical-publication-alias");
        String dataset = JsonHashes.sha256("physical-publication-data");
        ObjectNode headArgs = MAPPER.createObjectNode().put("hypothesisFamily", "physical-publication-family")
                .put("datasetSha256", dataset);
        headArgs.putArray("entries").addObject().put("behavior_sha256", alias).put("dataset_sha256", dataset);
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
        StrategyStatisticalV5.initializeExposureHeadFile(MAPPER.createObjectNode().put("filePath", headPath.toString())
                .set("head", head));
        ObjectNode definition = MAPPER.createObjectNode().put("behavior_sha256", alias)
                .put("dataset_sha256", dataset)
                .put("evaluator_sha256", JsonHashes.sha256("physical-publication-evaluator"));
        definition.putObject("chromosome").put("direction", "long").put("threshold", 3);
        ObjectNode registryArgs = MAPPER.createObjectNode().put("filePath", registryPath.toString())
                .set("exposureHead", head);
        registryArgs.putArray("definitions").add(definition);
        ObjectNode registry = StrategyStatisticalV5.appendBehaviorDefinitionRegistryFile(registryArgs);
        ObjectNode wfo = rejectedWfo(head, "physical-publication");
        ObjectNode run = rejectedResearchRun(head, wfo, "physical-publication");
        ObjectNode options = MAPPER.createObjectNode().put("transactionPath", transactionPath.toString())
                .put("exposureHeadPath", headPath.toString()).put("registryPath", registryPath.toString())
                .put("recordRoot", root.toString()).put("expectedHeadSha256", head.path("content_sha256").asText())
                .put("expectedRegistrySha256", registry.path("content_sha256").asText()).set("nextHead", head);
        options.set("wfo", wfo); options.set("run", run); var artifacts = options.putArray("artifacts");
        artifacts.addObject().put("role", "wfo").put("path", "artifacts/final-wfo.json").set("value", wfo);
        artifacts.addObject().put("role", "research_run").put("path", "artifacts/research-run.json").set("value", run);

        ObjectNode receipt = StrategyStatisticalV5.writeStatisticalPublicationTransaction(options);
        assertJson(receipt, oracle(request("writePublication").set("options", options)).path("value"));
        ObjectNode journal = (ObjectNode) MAPPER.readTree(Files.readAllBytes(transactionPath));
        assertThat(journal.path("status").asText()).isEqualTo("COMMITTED");
        assertThat(journal.path("content_sha256").asText()).isEqualTo(StrategyStatisticalV5.ownHash(journal));

        ObjectNode verify = MAPPER.createObjectNode().put("journalPath", transactionPath.toString())
                .put("recordRoot", root.toString()).set("journal", journal);
        assertJson(StrategyStatisticalV5.verifyCommittedStatisticalPublication(verify),
                oracle(request("verifyPublication").set("options", verify)).path("value"));
        ObjectNode recover = MAPPER.createObjectNode().put("transactionPath", transactionPath.toString())
                .put("recordRoot", root.toString());
        assertJson(StrategyStatisticalV5.recoverStatisticalPublicationTransaction(recover),
                oracle(request("recoverPublication").set("options", recover)).path("value"));

        Path lock = Path.of(transactionPath + ".lock"); Files.writeString(lock, "malformed\n", StandardCharsets.UTF_8);
        assertSameFailure(request("recoverPublication").set("options", recover),
                () -> StrategyStatisticalV5.recoverStatisticalPublicationTransaction(recover));
        Files.delete(lock);

        Path wfoPath = root.resolve("artifacts/final-wfo.json");
        Files.write(wfoPath, new byte[] {'x'}, java.nio.file.StandardOpenOption.APPEND);
        assertSameFailure(request("recoverPublication").set("options", recover),
                () -> StrategyStatisticalV5.recoverStatisticalPublicationTransaction(recover));

        Path missing = root.resolve("transactions/missing.json");
        ObjectNode missingOptions = MAPPER.createObjectNode().put("transactionPath", missing.toString())
                .put("recordRoot", root.toString());
        assertJson(StrategyStatisticalV5.recoverStatisticalPublicationTransaction(missingOptions),
                oracle(request("recoverPublication").set("options", missingOptions)).path("value"));
        Path linked = root.resolve("transactions/linked.json"); Files.createSymbolicLink(linked, transactionPath);
        ObjectNode linkedOptions = MAPPER.createObjectNode().put("transactionPath", linked.toString())
                .put("recordRoot", root.toString());
        assertSameFailure(request("recoverPublication").set("options", linkedOptions),
                () -> StrategyStatisticalV5.recoverStatisticalPublicationTransaction(linkedOptions));
    }

    private static ObjectNode rejectedWfo(ObjectNode head, String prefix) {
        ObjectNode audit = MAPPER.createObjectNode().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("audit"))
                .put("version", 1).put("fail_closed_missing_inputs", true).put("pass", false)
                .put("decision", "REJECTED").put("independent_opportunity_count", 0)
                .put("independent_trade_count", 0).put("sample_count", 0).put("selected_candidate_id", "none")
                .put("market_cluster_inventory_sha256", JsonHashes.sha256(prefix + "-clusters"))
                .put("exposure_head_sha256", head.path("content_sha256").asText());
        audit.putObject("max_statistic").put("cumulative_k", head.path("cumulative_k").asInt());
        ObjectNode gates = audit.putObject("gates");
        for (String gate : new String[] {"hard_metrics", "baseline_comparison", "bootstrap_p20_positive",
                "weighted_bootstrap_p20_positive", "max_statistic", "search_adjusted_expectancy_positive",
                "dsr", "pbo", "minimum_independent_episodes", "recent_oos_positive", "earlier_blocks",
                "positive_years", "positive_outer_folds", "plateau", "neighbour_fraction", "seed_stability",
                "null_controls", "stress_ablation", "asset_decisions", "portfolio"}) gates.put(gate, false);
        audit = StrategyStatisticalV5.withHash(audit);
        ObjectNode scope = MAPPER.createObjectNode().put("schema", "strategy-v5-statistical-asset-scope/1")
                .put("version", 1).putNull("source_sha256");
        scope.putArray("trade_assets").add("btc"); scope.putArray("replication_assets"); scope.putArray("context_assets");
        scope = StrategyStatisticalV5.withHash(scope); var folds = MAPPER.createArrayNode();
        for (int index = 1; index <= 8; index++) {
            ObjectNode fold = MAPPER.createObjectNode().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("fold"))
                    .put("version", 1).put("fold_id", "outer-" + index).put("status", "REJECTED")
                    .put("purge_ms", 30L * 86_400_000L).put("embargo_ms", 7L * 86_400_000L);
            fold.putArray("train_episode_ids"); fold.putArray("test_episode_ids");
            folds.add(StrategyStatisticalV5.withHash(fold));
        }
        ObjectNode refit = MAPPER.createObjectNode().put("schema", "strategy-v5-statistical-development-refit/1")
                .put("version", 1).put("validation_audit_sha256", audit.path("content_sha256").asText())
                .put("validation_exposure_head_sha256", head.path("content_sha256").asText())
                .put("exposure_head_sha256", head.path("content_sha256").asText())
                .put("selected_from_outer_fold_winners", false)
                .put("excluded_from_retrospective_oos_audit", true).put("status", "REJECTED");
        refit.putArray("asset_refits"); refit = StrategyStatisticalV5.withHash(refit);
        ObjectNode wfo = MAPPER.createObjectNode().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("wfo"))
                .put("version", 1).put("fold_count", 8).set("folds", folds);
        wfo.set("asset_scope", scope); wfo.put("validation_exposure_head_sha256", head.path("content_sha256").asText())
                .put("validation_exposure_head_cumulative_k", head.path("cumulative_k").asInt())
                .set("validation_exposure_head", head);
        wfo.put("exposure_head_sha256", head.path("content_sha256").asText())
                .put("cumulative_k", head.path("cumulative_k").asInt())
                .put("oos_artifact_sha256", JsonHashes.sha256(prefix + "-empty-oos"))
                .put("vector_inventory_sha256", JsonHashes.sha256(prefix + "-empty-vector"))
                .put("oos_weighting", "UNWEIGHTED");
        wfo.putArray("oos_episode_ids"); wfo.set("audit", audit); wfo.set("development_refit", refit);
        wfo.putArray("asset_decisions"); wfo.putArray("asset_decisions_final");
        ObjectNode portfolioArgs = MAPPER.createObjectNode().put("lineage_sha256", JsonHashes.sha256(prefix + "-portfolio-lineage"))
                .put("sourceArtifactSha256", JsonHashes.sha256(prefix + "-portfolio-source")).put("pass", false);
        portfolioArgs.putArray("assetDecisions").addObject().put("asset", "btc").put("pass", false);
        portfolioArgs.putArray("returnIncrements").addObject().put("episode_id", "none")
                .put("asset", "btc").put("net_r", 0);
        wfo.set("portfolio_decision", StrategyStatisticalV5.makePortfolioDecision(portfolioArgs));
        wfo.put("decision", "REJECTED").put("gate_pass", false); return StrategyStatisticalV5.withHash(wfo);
    }

    private static ObjectNode rejectedResearchRun(ObjectNode head, ObjectNode wfo, String prefix) {
        String manifest = JsonHashes.sha256(prefix + "-manifest");
        ObjectNode run = MAPPER.createObjectNode().put("schema", "strategy-research-run/5").put("version", 1)
                .put("provenance", "AUTHORITATIVE_RECOMPUTED").put("manifest_sha256", manifest)
                .put("feature_rows_sha256", JsonHashes.sha256(prefix + "-features"))
                .put("label_rows_sha256", JsonHashes.sha256(prefix + "-labels"))
                .put("execution_rows_sha256", JsonHashes.sha256(prefix + "-execution"))
                .put("mark_rows_sha256", JsonHashes.sha256(prefix + "-marks")).put("decision", "REJECTED");
        var pipeline = run.putArray("pipeline");
        for (String stage : new String[] {"features", "signal_intent", "labels", "execution_fills", "trades",
                "metrics", "stresses", "portfolio", "wfo"}) pipeline.add(stage);
        ObjectNode lineage = run.putObject("lineage").put("manifest_sha256", manifest)
                .put("feature_rows_sha256", run.path("feature_rows_sha256").asText())
                .put("label_rows_sha256", run.path("label_rows_sha256").asText())
                .put("execution_rows_sha256", run.path("execution_rows_sha256").asText())
                .put("mark_rows_sha256", run.path("mark_rows_sha256").asText())
                .put("wfo_sha256", wfo.path("content_sha256").asText());
        run.putArray("candidate_metrics"); run.putObject("accounting").put("declared_k", 1).put("evaluated_k", 1)
                .put("market_episode_count", 0).put("zero_episode_binding", true)
                .put("cumulative_family_k", head.path("cumulative_k").asInt());
        run.putObject("wfo").put("pass", false).put("status", "REJECTED")
                .put("artifact", wfo.path("content_sha256").asText());
        run.putObject("gate_status").put("wfo", false).put("stress", false).put("portfolio", false)
                .put("all_required_stages", false);
        return StrategyStatisticalV5.withHash(run);
    }

    private static void clusterEpisode(ObjectNode row, String id, String asset, String decision, String resolution) {
        row.put("episode_id", id).put("asset", asset).put("decision_time", decision)
                .put("resolution_time", resolution).put("eligible", true);
    }

    private static ObjectNode request(String action) { return MAPPER.createObjectNode().put("action", action); }

    private static JsonNode oracle(ObjectNode request) throws Exception {
        ObjectNode response = MAPPER.createObjectNode();
        try {
            String action = request.path("action").asText();
            JsonNode value = switch (action) {
                case "constants" -> constantsValue();
                case "exports" -> MAPPER.valueToTree(java.util.Arrays.stream(
                                StrategyStatisticalV5.class.getDeclaredMethods())
                        .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())
                                && java.lang.reflect.Modifier.isStatic(method.getModifiers()))
                        .map(java.lang.reflect.Method::getName).distinct().sorted().toList());
                case "hash" -> hashValue(request.get("value"));
                case "makeExposure" -> StrategyStatisticalV5.makeExposureHead((ObjectNode) request.get("options"));
                case "appendExposure" -> StrategyStatisticalV5.appendExposureHead((ObjectNode) request.get("options"));
                case "validateExposure" -> StrategyStatisticalV5.validateExposureHead(request.get("value"));
                case "artifact" -> StrategyStatisticalV5.makeStatisticalArtifactSet((ObjectNode) request.get("options"));
                case "evaluation" -> StrategyStatisticalV5.makeEvaluationArtifact((ObjectNode) request.get("options"));
                case "behavior" -> behaviorValue(request);
                case "drawdown" -> MAPPER.getNodeFactory().numberNode(
                        StrategyStatisticalV5.drawdown(MAPPER.convertValue(
                                request.get("values"), java.util.List.class)));
                case "clusters" -> clustersValue(request);
                case "hard" -> hardValue(request);
                case "dsr" -> StrategyStatisticalV5.deflatedSharpe(
                        request.get("rows"), request.path("trials").asInt());
                case "plateau" -> StrategyStatisticalV5.connectedPlateau(
                        request.get("ga"), request.path("alias").asText(),
                        request.path("options").path("minSize").asInt(),
                        request.path("options").path("minNeighbourFraction").asDouble());
                case "stress" -> StrategyStatisticalV5.makeStressDecision((ObjectNode) request.get("options"));
                case "portfolio" -> StrategyStatisticalV5.makePortfolioDecision((ObjectNode) request.get("options"));
                case "makeRegistry" -> StrategyStatisticalV5.makeBehaviorDefinitionRegistry((ObjectNode) request.get("options"));
                case "appendRegistry" -> StrategyStatisticalV5.appendBehaviorDefinitionRegistry((ObjectNode) request.get("options"));
                case "validateRegistry" -> MAPPER.getNodeFactory().booleanNode(
                        StrategyStatisticalV5.validateBehaviorDefinitionRegistry(
                                request.get("value"), (ObjectNode) request.get("options")));
                case "folds" -> StrategyStatisticalV5.makeQuarterlyFolds((ObjectNode) request.get("options"));
                case "initExposureFile" -> StrategyStatisticalV5.initializeExposureHeadFile((ObjectNode) request.get("options"));
                case "appendExposureFile" -> StrategyStatisticalV5.appendExposureHeadFile((ObjectNode) request.get("options"));
                case "appendRegistryFile" -> StrategyStatisticalV5.appendBehaviorDefinitionRegistryFile((ObjectNode) request.get("options"));
                case "bindRegistrySnapshot" -> StrategyStatisticalV5.bindBehaviorDefinitionRegistrySnapshotFile((ObjectNode) request.get("options"));
                case "resolveRegistrySnapshot" -> StrategyStatisticalV5.resolveBehaviorDefinitionRegistrySnapshotFile((ObjectNode) request.get("options"));
                case "makeVectors" -> StrategyStatisticalV5.makeVectorInventory((ObjectNode) request.get("options"));
                case "validateVectors" -> MAPPER.getNodeFactory().booleanNode(
                        StrategyStatisticalV5.validateVectorInventory(request.get("value"),
                                request.get("head"), request.get("episodeIds")));
                case "validateAudit" -> MAPPER.getNodeFactory().booleanNode(
                        StrategyStatisticalV5.validateStatisticalAudit(request.get("value")));
                case "neighbours" -> StrategyStatisticalV5.enumerateDirectNeighbours(
                        request.get("space"), request.get("value"));
                case "makeCheckpoint" -> StrategyStatisticalV5.makeGeneticCheckpoint((ObjectNode) request.get("options"));
                case "validateCheckpoint" -> MAPPER.getNodeFactory().booleanNode(
                        StrategyStatisticalV5.validateGeneticCheckpoint(request.get("value"),
                                (ObjectNode) request.get("options")));
                case "writeCheckpoint" -> StrategyStatisticalV5.writeGeneticCheckpointFile((ObjectNode) request.get("options"));
                case "readCheckpoint" -> StrategyStatisticalV5.readGeneticCheckpointFile(request.path("filePath").asText());
                case "recoverCheckpointLock" -> MAPPER.getNodeFactory().booleanNode(
                        StrategyStatisticalV5.recoverStaleCheckpointLock((ObjectNode) request.get("options")));
                case "pbo" -> StrategyStatisticalV5.pboFromFolds(
                        request.get("folds"), request.path("selected").asText(),
                        (ObjectNode) request.get("options"));
                case "nullReplay" -> StrategyStatisticalV5.makeNullReplayArtifact((ObjectNode) request.get("options"));
                case "validateGenetic" -> MAPPER.getNodeFactory().booleanNode(
                        StrategyStatisticalV5.validateGeneticArtifact(request.get("value")));
                case "aggregateAsset" -> StrategyStatisticalV5.aggregateAssetDecision(
                        request.get("rows"), request.get("required"));
                case "writeRegistryJournal" -> StrategyStatisticalV5.writeExposureRegistryJournal((ObjectNode) request.get("options"));
                case "recoverRegistryJournal" -> StrategyStatisticalV5.recoverExposureRegistryTransaction((ObjectNode) request.get("options"));
                case "validateWfo" -> MAPPER.getNodeFactory().booleanNode(
                        StrategyStatisticalV5.validateNestedWfoArtifact(request.get("value")));
                case "assertWfoBinding" -> MAPPER.getNodeFactory().booleanNode(
                        StrategyStatisticalV5.assertWfoRetainedOosBinding(request.get("wfo"),
                                request.get("artifact"), request.get("vector"),
                                request.path("label").asText()));
                case "makePublication" -> StrategyStatisticalV5.makeStatisticalPublicationTransaction((ObjectNode) request.get("options"));
                case "writePublication" -> StrategyStatisticalV5.writeStatisticalPublicationTransaction((ObjectNode) request.get("options"));
                case "recoverPublication" -> StrategyStatisticalV5.recoverStatisticalPublicationTransaction((ObjectNode) request.get("options"));
                case "verifyPublication" -> StrategyStatisticalV5.verifyCommittedStatisticalPublication((ObjectNode) request.get("options"));
                case "runAudit" -> StrategyStatisticalV5.runStatisticalAuditV5((ObjectNode) request.get("options"));
                case "runGenetic" -> StrategyStatisticalV5.runGeneticSearchV5(
                        (ObjectNode) request.get("options"), geneticEvaluator());
                case "resumeGenetic" -> StrategyStatisticalV5.resumeGeneticSearchV5(
                        (ObjectNode) request.get("options"), geneticEvaluator());
                case "runGeneticNoEvaluator" -> StrategyStatisticalV5.runGeneticSearchV5(
                        (ObjectNode) request.get("options"));
                case "runNested" -> StrategyStatisticalV5.runNestedWfoV5(
                        (ObjectNode) request.get("options"), nestedEvaluator(),
                        nestedStressProvider(), nestedPortfolioProvider(), nestedVectorProvider());
                case "runNestedNoProviders" -> StrategyStatisticalV5.runNestedWfoV5(
                        (ObjectNode) request.get("options"));
                case "runNullFixture" -> StrategyStatisticalV5.runNullControlsV5(
                        (ObjectNode) request.get("options"), fixtureNullReplay());
                case "calibrateNull" -> StrategyStatisticalV5.calibrateNullControlsV5(
                        (ObjectNode) request.get("options"), fixtureNullReplay());
                case "physicalNullRunner" -> physicalNullRunnerValue(request);
                case "runNullUnsupported" -> StrategyStatisticalV5.runNullControlsV5(
                        (ObjectNode) request.get("options"));
                default -> throw new IllegalArgumentException("unsupported Java oracle action: " + action);
            };
            response.put("ok", true); response.set("value", value); return response;
        } catch (RuntimeException error) {
            response.put("ok", false); response.put("error", error.getMessage()); return response;
        }
    }

    private static ObjectNode constantsValue() {
        ObjectNode value = MAPPER.createObjectNode();
        value.set("schema", MAPPER.valueToTree(StrategyStatisticalV5.STAT_SCHEMA));
        value.set("defaults", MAPPER.valueToTree(StrategyStatisticalV5.STAT_DEFAULTS));
        return value;
    }

    private static ObjectNode hashValue(JsonNode input) {
        ObjectNode value = MAPPER.createObjectNode();
        value.put("stable", StrategyStatisticalV5.stable(input));
        value.put("hash", StrategyStatisticalV5.hash(input));
        value.put("own", StrategyStatisticalV5.ownHash(input));
        value.set("with", StrategyStatisticalV5.withHash((ObjectNode) input));
        return value;
    }

    private static ObjectNode behaviorValue(ObjectNode request) {
        ObjectNode value = MAPPER.createObjectNode();
        value.set("effective", StrategyStatisticalV5.effectiveExecutionBehavior(request.get("definition")));
        value.put("intent", StrategyStatisticalV5.signalIntentAlias(request.get("vector")));
        value.put("alias", StrategyStatisticalV5.evaluatedBehaviorAlias("ignored",
                MAPPER.createObjectNode(), MAPPER.createArrayNode(), request.get("definition"),
                request.get("contracts")));
        return value;
    }

    private static ObjectNode clustersValue(ObjectNode request) {
        ObjectNode value = MAPPER.createObjectNode();
        value.set("diagnostics", StrategyStatisticalV5.marketEpisodeClusterDiagnostics(
                request.get("episodes")));
        value.set("collapsed", StrategyStatisticalV5.collapseMarketEpisodeRows(
                request.get("rows"), request.get("episodes")));
        return value;
    }

    private static ObjectNode hardValue(ObjectNode request) {
        ObjectNode value = MAPPER.createObjectNode();
        value.set("policy", StrategyStatisticalV5.requireFrozenHardPolicy(request.get("policy")));
        value.set("result", StrategyStatisticalV5.hardFeasible(request.get("metrics"), request.get("policy")));
        value.put("dominates", StrategyStatisticalV5.constrainedDominates(
                request.get("left"), request.get("right")));
        return value;
    }

    private static StrategyEvaluatorV5.Evaluator geneticEvaluator() {
        return task -> {
            double x = task.path("chromosome").path("alpha").asDouble();
            double side = "long".equals(task.path("chromosome").path("side").asText()) ? .2 : -.2;
            ObjectNode candidateReturns = MAPPER.createObjectNode();
            int index = 0;
            for (JsonNode id : task.path("episode_ids")) {
                candidateReturns.putObject(id.asText()).put("net_r", x + side + index++ * .05)
                        .put("traded", true);
            }
            ObjectNode metrics = MAPPER.createObjectNode().put("cost_r", .01)
                    .put("coverage_fraction", 1).put("capacity_pass", true)
                    .put("max_drawdown_r", -.1).put("profit_factor", 2)
                    .put("turnover", 1 + x).put("complexity", 2);
            ObjectNode required = MAPPER.createObjectNode().put("bootstrapIterations", 32)
                    .put("seed", 11);
            ObjectNode result = MAPPER.createObjectNode(); result.set("candidate_returns", candidateReturns);
            result.set("metrics", metrics); result.set("required", required); return result;
        };
    }

    private static StrategyStatisticalV5.NullReplaySuite fixtureNullReplay() {
        java.util.Map<String, StrategyStatisticalV5.NullReplayMethod> methods = new java.util.LinkedHashMap<>();
        for (String method : java.util.List.of("block_permuted_labels", "timestamp_shifted_outcomes",
                "frequency_matched_random_intents", "winners_curse_selection")) {
            methods.put(method, replayArgs -> (ObjectNode) replayArgs.path("artifact").deepCopy());
        }
        return new StrategyStatisticalV5.NullReplaySuite(methods);
    }

    private static JsonNode physicalNullRunnerValue(ObjectNode request) {
        ObjectNode provenance = (ObjectNode) request.path("provenance");
        StrategyEvaluatorV5.Evaluator evaluator = new StrategyEvaluatorV5.Evaluator() {
            @Override public ObjectNode evaluate(ObjectNode args) { return MAPPER.createObjectNode(); }
            @Override public ObjectNode workerProvenance() { return provenance.deepCopy(); }
            @Override public boolean physicalNullSelectionVerified() { return true; }
        };
        StrategyStatisticalV5.PhysicalNullRunner runner = StrategyStatisticalV5.makePhysicalNullRunnerV5(
                (ObjectNode) request.get("options"), evaluator);
        return request.path("runOptions").isObject()
                ? runner.run((ObjectNode) request.get("runOptions")) : runner.contract();
    }

    private static void assertSameFailure(ObjectNode request, Runnable javaCall) throws Exception {
        JsonNode expected = oracle(request);
        assertThat(expected.path("ok").asBoolean()).isFalse();
        assertThatThrownBy(javaCall::run).hasMessage(expected.path("error").asText());
    }

    private static void assertJson(JsonNode actual, JsonNode expected) {
        assertThat(CanonicalJson.canonicalize(actual)).isEqualTo(CanonicalJson.canonicalize(expected));
    }

}
