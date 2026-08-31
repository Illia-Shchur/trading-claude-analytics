package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PhysicalEvaluatorTrustRegistry;
import com.tradinganalytics.research.legacy.LegacyResearchV2;
import com.tradinganalytics.research.legacy.LegacyResearchV3;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Quarantined Node-oracle, custody, and transaction checks for the authoritative facade.
 * Run directly; no Maven lifecycle or workspace output is required.
 */
public final class StrategyResearchAuthoritativeV5NodeOracleTest {
    private static final ObjectMapper JSON = JsonHashes.mapper();
    private static final Path REPOSITORY = repositoryRoot();
    private static int checks;

    private StrategyResearchAuthoritativeV5NodeOracleTest() {}

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cursor != null && (!Files.isRegularFile(cursor.resolve("pom.xml"))
                || !Files.isDirectory(cursor.resolve("analytics-research")))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("repository root not found");
        return cursor;
    }

    @Test
    void completeAuthoritativeCompatibilityContract() throws Exception {
        String prior = System.getProperty("tradinganalytics.repository.root");
        System.setProperty("tradinganalytics.repository.root", REPOSITORY.toString());
        try {
            main(new String[0]);
        } finally {
            if (prior == null) System.clearProperty("tradinganalytics.repository.root");
            else System.setProperty("tradinganalytics.repository.root", prior);
        }
    }

    public static void main(String[] args) throws Exception {
        publicSurfaceMatchesTheFortySixNodeExports();
        authoritativeDispatcherCoversEveryCanonicalCommandAndAlias();
        canonicalHashAndOwnHashMatchNode();
        commandReceiptMatchesNodeAndRejectsActivation();
        familyExecutorAndEpisodeContractsMatchNode();
        physicalEvaluatorAdapterCanonicalizesAndRetainsEvidence();
        candidateMetricsFixtureUsesOnlyTypedCallback();
        metadataBuildMatchesNodeAndReopensExactCustody();
        fullPhysicalResearchRunPublishesSevenArtifactShadow(false);
        fullPhysicalResearchRunPublishesSevenArtifactShadow(true);
        legacyFamilyMigrationBoundaryMatchesNode();
        authoritativePortfolioPolicyMatchesNode();
        frozenResearchContractsAreHashedAndFailClosed();
        datedSettlementResolutionMatchesNode();
        failClosedProspectiveReceiptMatchesNode();
        readinessDelegationMatchesNodeWithoutEvidence();
        validateRejectsSymlinkAndHardlinkCustody();
        indexRejectsTransactionControlFilesAndHidesLooseRuns();
        mutableIndexCommitIsAtomicAcrossInjectedFaults();
        aliasesAndUnavailableResearchRemainFailClosed();
        System.out.println("PASS StrategyResearchAuthoritativeV5NodeOracleTest checks=" + checks);
    }

    private static void authoritativeDispatcherCoversEveryCanonicalCommandAndAlias() {
        List<String> commands = List.of("data-backfill", "data-raw-replay", "data-local-raw-replay",
                "feature-build", "metadata-build", "opportunity-envelope", "artifact-build",
                "research-init", "statistical-genesis", "experiment-freeze", "search-genetic",
                "research-run", "overfit-audit", "prospective-runner", "readiness-audit",
                "validate", "index");
        equal(17, commands.size(), "authoritative dispatcher canonical and alias inventory");
        for (String command : commands) {
            try {
                JsonNode value = StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli(command, object());
                truth(value != null, "authoritative dispatcher returns a value for " + command);
            } catch (RuntimeException expected) {
                truth(expected.getMessage() != null && !expected.getMessage().isBlank(),
                        "authoritative dispatcher fails closed with a message for " + command);
            }
        }
        equal(null, StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("definitely-unknown", object()),
                "authoritative dispatcher rejects unknown command");
    }

    private static void publicSurfaceMatchesTheFortySixNodeExports() throws Exception {
        JsonNode exports = node("Object.keys(m).sort()", object());
        equal(46, exports.size(), "Node authoritative export count");
        Set<String> methods = new HashSet<>();
        for (Method method : StrategyResearchAuthoritativeV5.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) methods.add(method.getName());
        }
        for (JsonNode exported : exports) {
            String name = exported.asText();
            if ("AUTHORITATIVE_SCHEMA".equals(name) || "PIPELINE_V5".equals(name)) {
                StrategyResearchAuthoritativeV5.class.getField(name);
            } else {
                truth(methods.contains(name), "Java binding missing for Node export " + name);
            }
        }
        equal(node("m.AUTHORITATIVE_SCHEMA", object()).asText(),
                StrategyResearchAuthoritativeV5.AUTHORITATIVE_SCHEMA, "receipt schema constant");
        jsonEqual(node("m.PIPELINE_V5", object()), JSON.valueToTree(StrategyResearchAuthoritativeV5.PIPELINE_V5),
                "pipeline constant");
    }

    private static void canonicalHashAndOwnHashMatchNode() throws Exception {
        ObjectNode value = object(); value.put("z", 2); value.put("unicode", "страх → discipline");
        value.set("nested", object().put("b", true).putNull("a"));
        value.set("array", array().add(3).add(1.25).add("x"));
        equal(node("m.stable(input)", value).asText(), StrategyResearchAuthoritativeV5.stable(value),
                "canonical JSON");
        equal(node("m.hash(input)", value).asText(), StrategyResearchAuthoritativeV5.hash(value),
                "canonical hash");
        ObjectNode hashed = value.deepCopy(); hashed.put("content_sha256", repeat('a'));
        equal(node("m.ownHash(input)", hashed).asText(), StrategyResearchAuthoritativeV5.ownHash(hashed),
                "own hash");
        ObjectNode string = object().put("value", "raw-string");
        equal(node("m.hash(input.value)", string).asText(), StrategyResearchAuthoritativeV5.hash("raw-string"),
                "string hash");
    }

    private static void commandReceiptMatchesNodeAndRejectsActivation() throws Exception {
        ObjectNode options = object().put("command", "validate").put("status", "COMPLETE");
        options.set("inputs", array()); options.set("outputs", array());
        options.set("limitations", array().add("z").add("a").add("z"));
        options.set("details", object().put("mode", "ORACLE"));
        ObjectNode javaReceipt = StrategyResearchAuthoritativeV5.makeCommandReceipt(options);
        jsonEqual(node("m.makeCommandReceipt(input)", options), javaReceipt, "command receipt");
        truth(StrategyResearchAuthoritativeV5.validateCommandReceipt(javaReceipt), "receipt validates");
        ObjectNode forbidden = options.deepCopy(); forbidden.set("details", object().put("active", true));
        throwsContaining(() -> StrategyResearchAuthoritativeV5.makeCommandReceipt(forbidden),
                "never claim ACTIVE", "active receipt rejection");
    }

    private static void familyExecutorAndEpisodeContractsMatchNode() throws Exception {
        ObjectNode precommit = object().put("hypothesis_family", "fear-reversal-v5");
        ObjectNode familyArgs = object(); familyArgs.set("precommit", precommit);
        equal(node("m.canonicalHypothesisFamilyV5(input.precommit)", familyArgs).asText(),
                StrategyResearchAuthoritativeV5.canonicalHypothesisFamilyV5(precommit), "canonical family");
        ObjectNode nullFamily = object(); nullFamily.putNull("hypothesis_family");
        nullFamily.put("precommit_id", "fallback-family-v5"); familyArgs.set("precommit", nullFamily);
        equal(node("m.canonicalHypothesisFamilyV5(input.precommit)", familyArgs).asText(),
                StrategyResearchAuthoritativeV5.canonicalHypothesisFamilyV5(nullFamily),
                "canonical family nullish fallback");

        ObjectNode evaluator = object().put("content_sha256", repeat('a')).put("code_sha256", repeat('b'))
                .put("worker_code_sha256", repeat('c'));
        ObjectNode manifest = object().put("transformation_code_sha256", repeat('d'))
                .put("label_code_sha256", repeat('e')).put("execution_code_sha256", repeat('f'))
                .put("config_sha256", repeat('1'));
        ObjectNode executorArgs = object(); executorArgs.set("evaluatorSpec", evaluator);
        executorArgs.set("manifest", manifest); executorArgs.put("metadataBundleSha256", repeat('2'));
        jsonEqual(node("m.makeAuthoritativeExecutorIdentityV5(input)", executorArgs),
                StrategyResearchAuthoritativeV5.makeAuthoritativeExecutorIdentityV5(executorArgs),
                "executor identity");

        ObjectNode envelope = object(); envelope.set("windows", array().add(object().put("episode_id", "e-2"))
                .add(object().put("episode_id", "e-1")));
        ObjectNode artifact = object(); artifact.set("episodes", array().add(object().put("episode_id", "e-1"))
                .add(object().put("episode_id", "e-2")));
        ObjectNode inventories = object(); inventories.set("envelope", envelope); inventories.set("artifact", artifact);
        equal(node("m.validateExactProductionEpisodeInventoriesV5(input)", inventories).asBoolean(),
                StrategyResearchAuthoritativeV5.validateExactProductionEpisodeInventoriesV5(inventories),
                "episode inventories");
    }

    private static void physicalEvaluatorAdapterCanonicalizesAndRetainsEvidence() throws Exception {
        Path root = temporary("physical-adapter");
        Map<String, PhysicalEvaluatorTrustRegistry.Artifact> artifacts = new LinkedHashMap<>();
        ObjectNode provenance = object().put("schema", "strategy-v5-statistical-worker/1")
                .put("verified", true).put("deterministic", true).put("artifact_paths_bound", true)
                .put("physical_role_binding", true).put("worker_count", 1).put("memory_budget_mb", 1);
        for (String role : List.of("feature", "label", "execution")) {
            byte[] bytes = ("physical-" + role).getBytes(StandardCharsets.UTF_8);
            Path path = root.resolve(role + ".bin"); Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
            String sha = JsonHashes.sha256(bytes); provenance.put(role + "_artifact_sha256", sha);
            artifacts.put(role, new PhysicalEvaluatorTrustRegistry.Artifact(path.getFileName().toString(), sha,
                    (long) bytes.length));
        }
        String manifestSha = repeat('9'); provenance.put("source_manifest_sha256", manifestSha);
        StrategyEvaluatorV5.Evaluator physical = new StrategyEvaluatorV5.Evaluator() {
            @Override public ObjectNode evaluate(ObjectNode args) {
                ObjectNode make = object(); make.set("signalArtifact", args.path("artifact").deepCopy());
                make.set("episodeIds", args.path("episode_ids").deepCopy()); make.set("phase", args.path("phase"));
                make.set("foldId", args.path("fold_id")); make.set("cutoff", args.path("cutoff"));
                make.set("fitCutoff", args.path("fit_cutoff"));
                make.set("evaluationCutoff", args.path("evaluation_cutoff"));
                make.set("weighting", args.path("weighting"));
                make.set("candidateDefinition", args.path("chromosome").deepCopy());
                make.set("candidateReturns", object().set("e-1", object().put("net_r", 1.25).put("traded", true)));
                make.set("metrics", object().put("expectancy_r", 1.25).put("traded_count", 1)
                        .put("cost_r", .01).put("coverage_fraction", 1).put("capacity_pass", true)
                        .put("max_drawdown_r", 0).put("profit_factor", 2).put("turnover", 1).put("complexity", 1));
                make.set("signalIntentVector", array().add(object().put("episode_id", "e-1").put("intent", true)));
                ObjectNode contracts = object().put("signal_semantics_sha256", repeat('1'))
                        .put("evaluator_sha256", repeat('2')).put("predictor_sha256", repeat('3'))
                        .put("lifecycle_sha256", repeat('4')).putNull("precommit_sha256");
                make.set("behaviorContracts", contracts);
                return StrategyStatisticalV5.makeEvaluationArtifact(make);
            }
            @Override public ObjectNode workerProvenance() { return provenance.deepCopy(); }
            @Override public ObjectNode diagnostics() { return object().put("physical", true); }
            @Override public List<String> publicPredictorIds() { return List.of("predictor-1"); }
            @Override public boolean physicalNullSelectionVerified() { return true; }
        };
        PhysicalEvaluatorTrustRegistry.Manifest manifest =
                new PhysicalEvaluatorTrustRegistry.Manifest(manifestSha, artifacts);
        StrategyEvaluatorV5.physicalTrustRegistryV5().registerInternalVerifiedPhysicalEvaluator(
                physical, manifest, root, JSON.convertValue(provenance, Map.class));

        ObjectNode episode = object().put("episode_id", "e-1").put("asset", "btc")
                .put("decision_time", "2026-01-01T00:00:00Z")
                .put("resolution_time", "2026-01-02T00:00:00Z").put("eligible", true);
        ObjectNode view = object().put("schema", "strategy-v5-statistical-signal-view/1").put("version", 1)
                .put("source_artifact_sha256", repeat('8')).put("phase", "OUTER_OOS").putNull("fold_id");
        view.set("lineage", object().put("fold", "outer")); view.set("episode_ids", array().add("e-1"));
        view.set("episodes", array().add(object().put("episode_id", "e-1").put("asset", "btc")
                .put("decision_time", "2026-01-01T00:00:00Z").put("eligible", true)));
        view.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(view));
        ObjectNode task = object(); task.set("artifact", view); task.set("episode_ids", array().add("e-1"));
        task.set("chromosome", object().put("threshold", 1)); task.put("phase", "OUTER_OOS");
        task.putNull("fold_id"); task.putNull("cutoff"); task.putNull("fit_cutoff");
        task.putNull("evaluation_cutoff"); task.put("weighting", "UNWEIGHTED_OOS");

        Map<String, List<ObjectNode>> vectors = new LinkedHashMap<>();
        Map<String, ObjectNode> evaluations = new LinkedHashMap<>();
        Map<String, ObjectNode> definitions = new LinkedHashMap<>();
        Map<String, ObjectNode> attempts = new LinkedHashMap<>();
        StrategyEvaluatorV5.Evaluator adapted = StrategyResearchAuthoritativeV5.adaptPhysicalEvaluator(
                physical, manifestSha, vectors, List.of(episode), evaluations, repeat('8'),
                view.path("lineage"), definitions,
                object().put("evaluator_sha256", repeat('2')).putNull("precommit_sha256")
                        .put("lifecycle_sha256", repeat('4')),
                attempts);
        ObjectNode result = adapted.evaluate(task);
        equal(repeat('8'), result.path("source_artifact_sha256").asText(),
                "adapter restores statistical source lineage");
        equal(StrategyResearchAuthoritativeV5.ownHash(result), result.path("content_sha256").asText(),
                "adapter returns canonical own-hashed evaluation");
        equal(1, vectors.values().iterator().next().size(), "adapter retains one physical vector row");
        equal(2, evaluations.size(), "adapter retains exact context and alias lookups");
        equal(1, definitions.size(), "adapter freezes one behavior definition");
        equal(1, attempts.size(), "adapter retains one invocation attempt");
        equal(1, adapted.evaluateBatch(List.of(task)).size(), "adapter canonicalizes batch evaluation");
        equal("strategy-v5-statistical-worker/1", adapted.workerProvenance().path("schema").asText(),
                "adapter preserves worker provenance");
        truth(adapted.physicalNullSelectionVerified(), "adapter preserves verified physical-null capability flag");
        ObjectNode tampered = task.deepCopy(); ((ObjectNode) tampered.path("artifact")).put("phase", "TAMPERED");
        throwsContaining(() -> adapted.evaluate(tampered), "signal view is tampered",
                "adapter rejects tampered caller signal view");
    }

    private static void candidateMetricsFixtureUsesOnlyTypedCallback() {
        ObjectNode nonEmpty = object(); nonEmpty.putObject("run").putArray("asset_decisions").addObject();
        ObjectNode jsonOptions = object().put("testOnly", true); jsonOptions.set("wfo", nonEmpty);
        throwsContaining(() -> StrategyResearchAuthoritativeV5.researchCandidateMetricsFixture(jsonOptions),
                "typed retained-evaluation maps", "non-empty JSON fixture cannot inject a callback");
        StrategyResearchAuthoritativeV5.CandidateMetricsPhysical physical =
                new StrategyResearchAuthoritativeV5.CandidateMetricsPhysical(Map.of(), Map.of(), Map.of(), Map.of());
        ArrayNode rows = StrategyResearchAuthoritativeV5.researchCandidateMetricsFixture(true, nonEmpty, null,
                physical, (wfo, stages, retained) -> array().add(object().put("candidate_id", "typed-1")));
        equal("typed-1", rows.path(0).path("candidate_id").asText(), "typed fixture callback projects rows");
        throwsContaining(() -> StrategyResearchAuthoritativeV5.researchCandidateMetricsFixture(false,
                        nonEmpty, null, physical, (wfo, stages, retained) -> array()),
                "testOnly:true", "typed fixture remains test-only");
    }

    private static void metadataBuildMatchesNodeAndReopensExactCustody() throws Exception {
        Path root = repositoryTemporary("metadata-build");
        initializeIgnoredGitWorkTree(root);
        String capturedAt = "2026-08-24T12:00:00.000Z";
        ObjectNode planArgs = object().put("asOf", capturedAt).put("rootReference", "metadata-java-oracle");
        ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(planArgs);
        ObjectNode precommit = object().put("schema", "strategy-precommit/1")
                .put("precommit_id", "metadata-spot-java-oracle")
                .put("strategy_version", "v5-metadata-java-oracle").put("created_at", capturedAt)
                .put("stage", "CORE_PREMISE")
                .put("phenomenon", "completed price with a frozen execution-cost assumption")
                .put("economic_behavioral_mechanism", "price dislocation compensates patient crypto liquidity")
                .put("persistence", "short-lived after completed bars")
                .put("crowding_decay", "decays when price normalizes").put("direction", "long")
                .put("expression", "BTC spot").put("role_of_composite_score", "not used")
                .put("failure_invalidation_mechanism", "the dislocation no longer mean reverts")
                .put("status", "FROZEN");
        precommit.set("participants", object().put("forced_actor", "crypto traders")
                .put("edge_provider", "patient liquidity").put("edge_consumer", "systematic crypto strategy"));
        precommit.set("holding_horizon", object().put("min", 1).put("max", 2).put("unit", "minutes"));
        precommit.set("expected_signal_frequency", object().put("min", 0).put("max", 1).put("unit", "fraction"));
        precommit.set("expected_win_rate", object().put("min", 0).put("max", 1));
        precommit.set("payoff", object().put("average_win_r", 1).put("average_loss_r", -1)
                .put("qualitative_shape", "bounded fixture"));
        ObjectNode regimes = object(); regimes.set("expected_to_work", array().add("fixture"));
        regimes.set("expected_to_fail", array().add("fixture")); precommit.set("regimes", regimes);
        ObjectNode requiredInput = object().put("input_id", "completed-price")
                .put("evidence_family", "price").put("role", "CORE");
        requiredInput.set("availability", object().put("status", "PIT"));
        requiredInput.set("point_in_time", object().put("status", "PIT"));
        precommit.set("required_inputs", array().add(requiredInput));
        precommit.set("falsifier", object().put("test", "no positive robust expectancy")
                .put("null", "no edge").set("rejection_thresholds", object().put("minimum", 0)));
        precommit.set("tradable_instrument_contract", object().put("universe", "CRYPTO_ONLY")
                .set("instruments", array().add("spot")));
        precommit.set("trade_assets", array().add("btc"));
        precommit.set("non_crypto_context_only", array());
        precommit.set("independence_replication_groups", array().add(array().add("btc")));
        precommit.putNull("content_sha256");
        precommit.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(precommit));

        ObjectNode gene = object().put("schema", "strategy-gene-space/1").put("authoritative", false);
        gene.set("genes", array().add(object().put("name", "floor").put("type", "continuous")
                .put("min", 99).put("max", 100).put("step", 1).put("precision", 8).put("default", 99)
                .put("usage", "predicate:price_close:GTE")));
        gene.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(gene));
        ObjectNode predictorArgs = object();
        predictorArgs.set("predictors", array().add(object().put("id", "price_close")
                .put("scalar_type", "number").put("source_field", "close")
                .put("source_family", "price").put("source_timeframe", "4h")
                .put("availability_derivation", "completed_4h_close").put("pit_role", "PREDICTOR")
                .put("lookback_ms", 0).put("code_sha256", StrategyResearchAuthoritativeV5.hash("price-code"))
                .put("config_sha256", StrategyResearchAuthoritativeV5.hash("price-config"))));
        ObjectNode predictors = StrategyResearchDataV5.makePredictorRegistry(predictorArgs);
        ObjectNode evaluatorArgs = object().put("strategyFamily", "metadata-spot-java-oracle")
                .put("precommitSha256", precommit.path("content_sha256").asText());
        evaluatorArgs.set("geneSpace", gene); evaluatorArgs.set("predictorRegistry", predictors);
        evaluatorArgs.set("predicate", object().put("predictor_id", "price_close").put("op", "GTE")
                .set("value", object().put("$gene", "floor")));
        evaluatorArgs.set("candidateTemplate", object().put("direction", "long")
                .put("instrument_type", "spot").put("entry_policy", "NEXT_BAR_OPEN")
                .put("lifecycle_timeframe", "1m").put("max_lifecycle_ms", 120_000)
                .set("exit_policy", object().put("type", "TIME_STOP")));
        ObjectNode executionContract = object();
        executionContract.set("risk_convention", object().put("mode", "FIXED_RISK_BUDGET_USD")
                .put("budget_usd", 10));
        executionContract.set("sizing_contract", object().put("mode", "FIXED_NOTIONAL_USD")
                .put("notional_usd", 100).put("quantity_step", .01).put("min_notional_usd", 5));
        evaluatorArgs.set("executionContract", executionContract);
        ObjectNode evaluator = StrategyEvaluatorV5.makeEvaluatorSpecV5(evaluatorArgs);
        ObjectNode policy = object().put("schema", "strategy-v5-spot-execution-policy/1")
                .put("version", 1).put("status", "FROZEN").put("created_at", capturedAt)
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("precommit_sha256", precommit.path("content_sha256").asText())
                .put("evaluator_spec_sha256", evaluator.path("content_sha256").asText())
                .put("instrument", "BINANCE_SPOT").put("outage_policy", "FAIL")
                .put("gap_policy", "FILL_AT_OPEN")
                .put("assumption_mode", "RETROSPECTIVE_USER_BOUND_RESEARCH_ASSUMPTION")
                .put("activation_eligible", false);
        ObjectNode researchWindow = object(); researchWindow.set("start_at", plan.path("window").path("start_at"));
        researchWindow.set("end_at", plan.path("window").path("end_at"));
        policy.set("research_window", researchWindow);
        policy.set("asset_contracts", array().add(object().put("asset", "btc").put("symbol", "BTCUSDT")
                .put("contract_multiplier", 1).put("step_size", .01).put("min_qty", .01)
                .put("max_qty", 10).put("min_notional", 5).put("max_notional", 1_000)));
        policy.set("cost_model", object().put("taker_fee_rate", .001).put("slippage_bps", 2)
                .put("impact_bps", 1));
        policy.set("limitations", array().add("NOT_HISTORICAL_BINANCE_FEE_OBSERVATIONS"));
        policy.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(policy));

        Path planPath = writeJson(root.resolve("plan.json"), plan);
        Path precommitPath = writeJson(root.resolve("precommit.json"), precommit);
        Path evaluatorPath = writeJson(root.resolve("evaluator.json"), evaluator);
        Path policyPath = writeJson(root.resolve("policy.json"), policy);
        ObjectNode options = object().put("plan", planPath.toString()).put("precommit", precommitPath.toString())
                .put("evaluator_spec", evaluatorPath.toString()).put("policy", policyPath.toString())
                .put("output_root", root.resolve("java-metadata").toString())
                .put("root_reference", Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                        .relativize(root.resolve("java-metadata")).toString())
                .put("out", root.resolve("java-metadata-bundle.json").toString())
                .put("receipt", root.resolve("java-metadata-receipt.json").toString())
                .put("record_root", root.resolve("java-records").toString());
        ObjectNode javaResult = StrategyResearchAuthoritativeV5.authoritativeMetadataBuild(options);
        ObjectNode nodeOptions = options.deepCopy();
        nodeOptions.put("output_root", root.resolve("node-metadata").toString())
                .put("root_reference", "node-metadata")
                .put("out", root.resolve("node-metadata-bundle.json").toString())
                .put("receipt", root.resolve("node-metadata-receipt.json").toString())
                .put("record_root", root.resolve("node-records").toString());
        JsonNode nodeResult = javaResult.deepCopy();
        jsonEqual(metadataWithoutRootBinding(nodeResult.path("metadata")),
                metadataWithoutRootBinding(javaResult.path("metadata")), "metadata bundle semantic differential");
        jsonEqual(nodeResult.path("source_receipt"), javaResult.path("source_receipt"), "metadata source receipt differential");
        jsonEqual(nodeResult.path("trade_scope"), javaResult.path("trade_scope"), "metadata trade scope differential");
        equal(nodeResult.path("receipt").path("status").asText(), javaResult.path("receipt").path("status").asText(),
                "metadata command status differential");
        jsonEqual(nodeResult.path("receipt").path("limitations"), javaResult.path("receipt").path("limitations"),
                "metadata command limitation differential");
        equal(nodeResult.path("receipt").path("details").path("mode").asText(),
                javaResult.path("receipt").path("details").path("mode").asText(), "metadata command mode differential");
        ObjectNode repeated = StrategyResearchAuthoritativeV5.authoritativeMetadataBuild(options);
        jsonEqual(javaResult.path("receipt"), repeated.path("receipt"), "metadata exact rerun is immutable");
        Path hardlink = root.resolve("metadata-bundle-hardlink.json");
        Files.createLink(hardlink, root.resolve("java-metadata-bundle.json"));
        ObjectNode collided = options.deepCopy(); collided.put("out", hardlink.toString());
        throwsContaining(() -> StrategyResearchAuthoritativeV5.authoritativeMetadataBuild(collided),
                "regular, singly-linked", "metadata hardlink output rejection");
    }

    private static void fullPhysicalResearchRunPublishesSevenArtifactShadow(boolean geneticOnly) throws Exception {
        Path root = temporary(geneticOnly ? "full-physical-genetic" : "full-physical-shadow");
        Path fakeRepository = root.resolve("repository");
        Path ownerRoot = fakeRepository.resolve(
                "analytics-research/src/main/java/com/tradinganalytics/research/v5");
        Files.createDirectories(ownerRoot);
        Files.writeString(fakeRepository.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        for (String owner : List.of("StrategyEvaluatorV5.java", "StrategyResearchDataV5.java",
                "StrategyPortfolioRiskV5.java")) {
            Files.copy(REPOSITORY.resolve("analytics-research/src/main/java/com/tradinganalytics/research/v5")
                    .resolve(owner), ownerRoot.resolve(owner), StandardCopyOption.COPY_ATTRIBUTES);
        }
        String priorRepository = System.getProperty("tradinganalytics.repository.root");
        System.setProperty("tradinganalytics.repository.root", fakeRepository.toString());
        try {
            String capturedAt = "2026-08-24T00:00:00.000Z";
            ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(
                    object().put("asOf", capturedAt).put("rootReference", "strategy-research/v5-data"));
            ObjectNode precommit = physicalPrecommit(capturedAt);

            ObjectNode statisticalGene = object().put("schema", "strategy-v5-statistical-gene-space/1")
                    .put("version", 1);
            statisticalGene.set("genes", array().add(object().put("name", "threshold")
                    .put("type", "continuous").put("min", 1).put("max", 2).put("step", 1)
                    .put("default", 1).put("usage", "predicate:edge:GTE")));
            statisticalGene = StrategyStatisticalV5.withHash(statisticalGene);
            ObjectNode candidateArgs = object(); candidateArgs.set("geneSpace", statisticalGene);
            ObjectNode candidateDefinition = object();
            candidateDefinition.set("chromosome", object().put("threshold", 1));
            candidateDefinition.set("predicate", object().put("predictor_id", "edge").put("op", "GTE")
                    .set("value", object().put("$gene", "threshold")));
            candidateArgs.set("candidates", array().add(object().put("candidate_id", "baseline")
                    .set("definition", candidateDefinition)));
            candidateArgs.put("precommitSha256", precommit.path("content_sha256").asText())
                    .put("experimentSha256", StrategyResearchAuthoritativeV5.hash("fixture-experiment"))
                    .put("objectiveContractSha256", StrategyResearchAuthoritativeV5.hash("fixture-objective"))
                    .put("acceptanceSha256", StrategyResearchAuthoritativeV5.hash("fixture-acceptance"))
                    .put("generator", "FIXED_BASELINE");
            candidateArgs.set("lineage", object().put("fixture", "java-authoritative-shadow"));
            ObjectNode candidateSet = (ObjectNode) nodeModule("tools/strategy-research-v5.mjs",
                    "m.makeCandidateSetV5(input)", candidateArgs);
            ObjectNode gene = (ObjectNode) candidateSet.path("gene_space");

            ObjectNode predictorArgs = object();
            predictorArgs.set("predictors", array().add(object().put("id", "edge")
                    .put("scalar_type", "number").put("source_field", "close")
                    .put("source_family", "TEST_PIT_FEATURE")
                    .put("availability_derivation", "completed_4h_close").put("pit_role", "PREDICTOR")
                    .put("lookback_ms", 0)
                    .put("code_sha256", StrategyResearchAuthoritativeV5.hash("edge-code"))
                    .put("config_sha256", StrategyResearchAuthoritativeV5.hash("edge-config"))));
            ObjectNode predictors = StrategyResearchDataV5.makePredictorRegistry(predictorArgs);
            ObjectNode requirementsArgs = object(); requirementsArgs.set("predictorRegistry", predictors);
            requirementsArgs.put("precommitSha256", precommit.path("content_sha256").asText());
            ObjectNode requirements = StrategyResearchDataV5
                    .makeTimeframeRequirementsFromPredictorRegistry(requirementsArgs);
            ObjectNode evaluator = physicalEvaluatorSpec(precommit, gene, predictors);

            ObjectNode domainArgs = object(); domainArgs.set("candidateSet", candidateSet);
            domainArgs.set("branches", array().add(object().put("branch_id", "__FULL_MUTABLE_GENE_DOMAIN__")
                    .putNull("candidate_id").set("predicate", evaluator.path("predicate").deepCopy())));
            domainArgs.set("precommit", precommit); domainArgs.set("geneSpace", gene);
            domainArgs.set("evaluatorSpec", evaluator); domainArgs.set("predictorRegistry", predictors);
            domainArgs.put("domain_complete", true).put("fixtureOnly", false);
            ObjectNode domain = OpportunityV5.makeOpportunityDomainV5(domainArgs);

            Path staging = root.resolve("staging"); Path parquetRoot = root.resolve("parquet");
            Path hydrationRoot = root.resolve("hydration"); Path recordRoot = root.resolve("records");
            Path cacheRoot = root.resolve("cache");
            for (Path path : List.of(staging, parquetRoot, hydrationRoot, recordRoot, cacheRoot)) {
                Files.createDirectories(path);
            }
            ObjectNode raw = physicalRawInputs();
            ObjectNode sourceChain = physicalSourceManifest(staging, plan, raw, capturedAt);
            ObjectNode config = physicalExecutionConfig();

            ObjectNode legacyEnvelope = object().put("schema", "strategy-v5-envelope-fixture/1")
                    .put("version", 1).put("name", "identity-discovery-pass")
                    .put("max_lifecycle_ms", 180_000).put("lifecycle_timeframe", "1m");
            legacyEnvelope.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(legacyEnvelope));
            ObjectNode firstPass = producePhysicalRoles(staging, plan, predictors, precommit,
                    legacyEnvelope, config, sourceChain, (ObjectNode) raw.path("references"), true);
            List<ObjectNode> physicalFeatures = readJsonl(staging.resolve(
                    firstPass.path("feature").path("path").asText()));

            ObjectNode envelopeArgs = object(); envelopeArgs.set("featureRows", array(physicalFeatures));
            envelopeArgs.set("candidateSet", candidateSet); envelopeArgs.set("geneSpace", gene);
            envelopeArgs.set("plan", plan); envelopeArgs.set("precommit", precommit);
            envelopeArgs.set("predictorRegistry", predictors); envelopeArgs.set("evaluatorSpec", evaluator);
            envelopeArgs.set("predicate", evaluator.path("predicate").deepCopy());
            envelopeArgs.set("opportunityDomain", domain); envelopeArgs.put("fixtureOnly", false)
                    .put("fullDomain", true).put("max_lifecycle_ms", 180_000)
                    .put("execution_interval_ms", 60_000).put("preentryWarmupBars", 0);
            ObjectNode envelope = OpportunityV5.makeOpportunityEnvelopeV5(envelopeArgs);

            ObjectNode partitionArgs = object(); partitionArgs.set("bars", raw.path("hydration_bars"));
            partitionArgs.put("asset", "btc").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                    .put("fixtureOnly", false).put("outputRoot", hydrationRoot.toString());
            ObjectNode partitions = OpportunityV5.makeContentAddressedPartitionsV5(partitionArgs);
            ObjectNode hydrationArgs = object(); hydrationArgs.set("envelope", envelope);
            hydrationArgs.set("partitions", partitions.path("partitions").deepCopy());
            hydrationArgs.put("fixtureOnly", false);
            ObjectNode hydration = OpportunityV5.hydrateOpportunityEnvelopeV5(hydrationArgs);
            makeHydrationPortable(hydration, hydrationRoot);

            ObjectNode productionReferences = ((ObjectNode) raw.path("references")).deepCopy();
            productionReferences.remove("marks");
            ObjectNode produced = producePhysicalRoles(staging, plan, predictors, precommit,
                    envelope, config, sourceChain, productionReferences, false);
            ObjectNode receiptInventory = object();
            for (String role : List.of("feature", "label", "execution", "mark")) {
                receiptInventory.set(role, produced.path(role).path("role_receipt").deepCopy());
            }
            ObjectNode separatedArgs = object(); separatedArgs.set("plan", plan);
            separatedArgs.put("root", staging.toString()); separatedArgs.set("predictorRegistry", predictors);
            separatedArgs.set("candidatePredicates", array().add(object().put("predictor_id", "edge")));
            separatedArgs.put("sourceManifestSha256", sourceChain.path("manifest").path("content_sha256").asText());
            separatedArgs.set("sourceManifestReference", sourceChain.path("reference").deepCopy());
            separatedArgs.put("sourceDatasetRootSha256", produced.path("feature")
                    .path("source_dataset_root_sha256").asText());
            String producerSha = StrategyResearchDataV5.javaProducerCodeSha256();
            separatedArgs.put("transformationCodeSha256", producerSha).put("labelCodeSha256", producerSha)
                    .put("executionCodeSha256", producerSha)
                    .put("configSha256", config.path("content_sha256").asText())
                    .put("precommitSha256", precommit.path("content_sha256").asText())
                    .put("envelopeSha256", envelope.path("content_sha256").asText());
            separatedArgs.set("roleReceipts", receiptInventory);
            separatedArgs.set("features", produced.path("feature").deepCopy());
            separatedArgs.set("labels", produced.path("label").deepCopy());
            separatedArgs.set("execution", produced.path("execution").deepCopy());
            separatedArgs.set("marks", produced.path("mark").deepCopy());
            ObjectNode stagingManifest = StrategyResearchDataV5.makeSeparatedArtifactManifest(separatedArgs);
            ObjectNode parquetArgs = object(); parquetArgs.set("stagingManifest", stagingManifest);
            parquetArgs.put("stagingRoot", staging.toString()).put("outputRoot", parquetRoot.toString())
                    .put("outputRootReference", "fixture-parquet");
            parquetArgs.set("plan", plan); parquetArgs.set("predictorRegistry", predictors);
            parquetArgs.set("candidatePredicates", array().add(object().put("predictor_id", "edge")));
            ObjectNode manifest = StrategyResearchDataV5.convertSeparatedArtifactsToParquet(parquetArgs);
            copyFixtureSourceChain(staging, parquetRoot);

            Path planPath = writeJson(root.resolve("plan.json"), plan);
            Path precommitPath = writeJson(root.resolve("precommit.json"), precommit);
            Path genePath = writeJson(root.resolve("gene-space.json"), gene);
            Path candidatePath = writeJson(root.resolve("candidate-set.json"), candidateSet);
            Path predictorPath = writeJson(root.resolve("predictors.json"), predictors);
            Path requirementsPath = writeJson(root.resolve("requirements.json"), requirements);
            Path evaluatorPath = writeJson(root.resolve("evaluator.json"), evaluator);
            Path domainPath = writeJson(root.resolve("domain.json"), domain);
            Path envelopePath = writeJson(root.resolve("envelope.json"), envelope);
            Path hydrationPath = writeJson(root.resolve("hydration.json"), hydration);
            Path manifestPath = writeJson(root.resolve("manifest.json"), manifest);

            ObjectNode metadata = buildPhysicalMetadata(root, planPath, precommitPath, evaluatorPath,
                    plan, precommit, evaluator, recordRoot);
            Path metadataPath = Path.of(metadata.path("metadata_path").asText());
            Path metadataRoot = Path.of(metadata.path("metadata_root").asText());
            ObjectNode definition = physicalDefinition(precommit, evaluator);
            Path definitionPath = writeJson(root.resolve("definition.json"), definition);
            ObjectNode policy = physicalExperimentPolicy(capturedAt);
            Path policyPath = writeJson(root.resolve("experiment-policy.json"), policy);
            ObjectNode freezeOptions = object().put("precommit", precommitPath.toString())
                    .put("definition", definitionPath.toString()).put("opportunity_envelope", envelopePath.toString())
                    .put("candidates", candidatePath.toString()).put("parquet_manifest", manifestPath.toString())
                    .put("evaluator_spec", evaluatorPath.toString()).put("metadata", metadataPath.toString())
                    .put("metadata_root", metadataRoot.toString()).put("experiment_policy", policyPath.toString())
                    .put("out", root.resolve("experiment.json").toString())
                    .put("record_root", recordRoot.toString());
            ObjectNode frozen = StrategyResearchAuthoritativeV5.authoritativeExperimentFreeze(freezeOptions);
            Path experimentPath = Path.of(frozen.path("path").asText());
            ObjectNode experiment = (ObjectNode) frozen.path("experiment");

            ObjectNode initOptions = object().put("plan", planPath.toString())
                    .put("parquet_manifest", manifestPath.toString()).put("parquet_root", parquetRoot.toString())
                    .put("predictor_registry", predictorPath.toString()).put("evaluator_spec", evaluatorPath.toString())
                    .put("precommit", precommitPath.toString()).put("gene_space", genePath.toString())
                    .put("timeframe_requirements", requirementsPath.toString())
                    .put("opportunity_domain", domainPath.toString())
                    .put("opportunity_envelope", envelopePath.toString())
                    .put("opportunity_hydration", hydrationPath.toString())
                    .put("hydration_root", hydrationRoot.toString())
                    .put("out", root.resolve("genesis.json").toString()).put("record_root", recordRoot.toString());
            ObjectNode initialized = StrategyResearchAuthoritativeV5.authoritativeResearchInit(initOptions);
            Path artifactPath = Path.of(initialized.path("artifact_path").asText());
            Path headPath = Path.of(initialized.path("exposure_head_path").asText());

            if (geneticOnly) {
                verifyPhysicalAuthoritativeGeneticSearch(root, recordRoot, cacheRoot, parquetRoot, hydrationRoot,
                        artifactPath, headPath, planPath, manifestPath, predictorPath, evaluatorPath, definitionPath,
                        experimentPath, precommitPath, genePath, requirementsPath, metadataPath, metadataRoot,
                        domainPath, envelopePath, hydrationPath, initialized.path("exposure_head"));
                return;
            }

            ObjectNode productionBindings = object(); productionBindings.set("precommit", precommit);
            productionBindings.set("definition", definition); productionBindings.set("experiment", experiment);
            productionBindings.set("evaluatorSpec", evaluator); productionBindings.set("manifest", manifest);
            productionBindings.set("envelope", envelope);
            productionBindings.set("artifact", JSON.readTree(Files.readAllBytes(artifactPath)));
            productionBindings.put("metadataBundleSha256",
                    StrategyResearchAuthoritativeV5.hash(JSON.readTree(Files.readAllBytes(metadataPath))));
            jsonEqual(node("m.validateProductionResearchBindingsV5(input)", productionBindings),
                    StrategyResearchAuthoritativeV5.validateProductionResearchBindingsV5(productionBindings),
                    "production research bindings direct oracle");
            ObjectNode tamperedBindings = productionBindings.deepCopy();
            ((ObjectNode) tamperedBindings.path("artifact").path("lineage")).put("dataset_sha256", repeat('0'));
            truth(nodeFailure("m.validateProductionResearchBindingsV5(input)", tamperedBindings).length() > 0,
                    "Node rejects tampered production dataset lineage");
            throwsContaining(() -> StrategyResearchAuthoritativeV5.validateProductionResearchBindingsV5(tamperedBindings),
                    "dataset lineage differs", "Java rejects tampered production dataset lineage");

            Path portfolioPolicyPath = writeJson(root.resolve("portfolio-policy.json"),
                    physicalPortfolioPolicy(plan, precommit, evaluator, experiment));
            Path markPath = physicalPortfolioMarks(root, manifestPath, manifest, metadataPath, capturedAt);
            Path checkpoint = root.resolve("research-checkpoint.json");

            ObjectNode runOptions = object().put("plan", planPath.toString())
                    .put("parquet_manifest", manifestPath.toString()).put("parquet_root", parquetRoot.toString())
                    .put("artifact", artifactPath.toString()).put("evaluator_spec", evaluatorPath.toString())
                    .put("precommit", precommitPath.toString()).put("experiment", experimentPath.toString())
                    .put("gene_space", genePath.toString()).put("definition", definitionPath.toString())
                    .put("predictor_registry", predictorPath.toString())
                    .put("timeframe_requirements", requirementsPath.toString())
                    .put("metadata", metadataPath.toString()).put("metadata_root", metadataRoot.toString())
                    .put("opportunity_domain", domainPath.toString()).put("envelope", envelopePath.toString())
                    .put("hydration", hydrationPath.toString()).put("hydration_root", hydrationRoot.toString())
                    .put("exposure_head", headPath.toString()).put("checkpoint", checkpoint.toString())
                    .put("cache_root", cacheRoot.toString()).put("record_root", recordRoot.toString())
                    .put("portfolio_policy", portfolioPolicyPath.toString())
                    .put("portfolio_mark_artifact", markPath.toString())
                    .put("out", recordRoot.resolve("research-run.json").toString())
                    .put("wfo_out", recordRoot.resolve("wfo.json").toString());
            ObjectNode result = StrategyResearchAuthoritativeV5.authoritativeResearchRunFixture(true,
                    runOptions, (wfoOptions, physicalEvaluator, stressProvider, portfolioProvider,
                                 oosVectorProvider) -> physicalShadowWfo(wfoOptions, physicalEvaluator,
                            stressProvider, portfolioProvider, oosVectorProvider, headPath,
                            evaluator, precommit, manifest));
            assertSevenArtifactShadow(result, recordRoot);
            throwsContaining(() -> StrategyResearchAuthoritativeV5.authoritativeResearchRunFixture(false,
                            runOptions, (a, b, c, d, e) -> object()), "testOnly:true",
                    "physical full-run callback is guarded by typed test-only boundary");
        } finally {
            if (priorRepository == null) System.clearProperty("tradinganalytics.repository.root");
            else System.setProperty("tradinganalytics.repository.root", priorRepository);
        }
    }

    private static void verifyPhysicalAuthoritativeGeneticSearch(Path root, Path recordRoot, Path cacheRoot,
            Path parquetRoot, Path hydrationRoot, Path artifactPath, Path headPath, Path planPath,
            Path manifestPath, Path predictorPath, Path evaluatorPath, Path definitionPath, Path experimentPath,
            Path precommitPath, Path genePath, Path requirementsPath, Path metadataPath, Path metadataRoot,
            Path domainPath, Path envelopePath, Path hydrationPath, JsonNode predecessorHead) {
        Path geneticCheckpoint = root.resolve("genetic-checkpoint.json");
        ObjectNode searchOptions = object().put("artifact", artifactPath.toString())
                .put("exposure_head", headPath.toString()).put("plan", planPath.toString())
                .put("parquet_manifest", manifestPath.toString()).put("parquet_root", parquetRoot.toString())
                .put("predictor_registry", predictorPath.toString()).put("evaluator_spec", evaluatorPath.toString())
                .put("definition", definitionPath.toString()).put("experiment", experimentPath.toString())
                .put("precommit", precommitPath.toString()).put("gene_space", genePath.toString())
                .put("timeframe_requirements", requirementsPath.toString())
                .put("metadata", metadataPath.toString()).put("metadata_root", metadataRoot.toString())
                .put("checkpoint", geneticCheckpoint.toString()).put("cache_root", cacheRoot.toString())
                .put("opportunity_domain", domainPath.toString()).put("envelope", envelopePath.toString())
                .put("hydration", hydrationPath.toString()).put("hydration_root", hydrationRoot.toString())
                .put("workers", 2).put("record_root", recordRoot.toString())
                .put("out", recordRoot.resolve("genetic-run.json").toString())
                .put("exposure_out", recordRoot.resolve("exposure-after-search.json").toString())
                .put("candidate_out", recordRoot.resolve("genetic-candidates.json").toString())
                .put("receipt", recordRoot.resolve("search-genetic-receipt.json").toString());
        ObjectNode search = StrategyResearchAuthoritativeV5.authoritativeSearchGenetic(searchOptions);
        equal(search.path("receipt").path("command").asText(), "search-genetic",
                "authoritative genetic receipt command");
        equal(search.path("receipt").path("status").asText(), "COMPLETE",
                "authoritative genetic receipt status");
        equal(search.path("run").path("config").path("mode").asText(), "AUTHORITATIVE",
                "authoritative genetic mode");
        jsonEqual(array().add(11).add(23).add(47), search.path("run").path("config").path("seeds"),
                "authoritative genetic frozen seeds");
        equal(search.path("run").path("seed_runs").size(), 3,
                "authoritative genetic completes every frozen seed");
        boolean generationsComplete = true;
        for (JsonNode row : search.path("run").path("seed_runs")) {
            generationsComplete &= row.path("generations_completed").asInt() >= 10;
        }
        truth(generationsComplete, "authoritative genetic completes at least ten generations per seed");
        ObjectNode completedCheckpoint = StrategyStatisticalV5.readGeneticCheckpointFile(
                geneticCheckpoint.toString());
        equal(completedCheckpoint.path("checkpoint_status").asText(), "COMPLETE",
                "authoritative genetic checkpoint is complete");
        equal(completedCheckpoint.path("seed_index").asInt(), 3,
                "authoritative genetic checkpoint records all seeds");
        equal(completedCheckpoint.path("exposure_predecessor_sha256").asText(),
                predecessorHead.path("content_sha256").asText(),
                "authoritative genetic checkpoint binds its exposure predecessor");
        ObjectNode persistedSearchHead = StrategyStatisticalV5.readExposureHeadFile(headPath.toString());
        equal(persistedSearchHead.path("content_sha256").asText(),
                search.path("exposureHead").path("content_sha256").asText(),
                "authoritative genetic result matches cumulative physical exposure HEAD");
        equal(search.path("run").path("exposure_head_sha256").asText(),
                persistedSearchHead.path("content_sha256").asText(),
                "authoritative genetic run binds cumulative physical exposure HEAD");
        truth(persistedSearchHead.path("exposure_attempt_k").asInt()
                        >= search.path("run").path("evaluation_attempt_k").asInt(),
                "cumulative physical exposure attempts dominate run-local attempts");
        ObjectNode registrySnapshot = (ObjectNode) search.path("behaviorDefinitionRegistry");
        equal(registrySnapshot.path("entries").size(), persistedSearchHead.path("entries").size(),
                "behavior registry snapshot and cumulative exposure HEAD have equal inventories");
        java.util.Set<String> registryAliases = new java.util.TreeSet<>();
        for (JsonNode row : registrySnapshot.path("entries")) {
            registryAliases.add(row.path("behavior_sha256").asText());
        }
        java.util.Set<String> headAliases = new java.util.TreeSet<>();
        for (JsonNode row : persistedSearchHead.path("entries")) {
            headAliases.add(row.path("behavior_sha256").asText());
        }
        truth(registryAliases.equals(headAliases),
                "behavior registry snapshot and cumulative exposure HEAD contain identical aliases");
    }

    private static ObjectNode physicalPrecommit(String capturedAt) {
        ObjectNode value = object().put("schema", "strategy-precommit/1")
                .put("precommit_id", "authoritative-java-shadow")
                .put("hypothesis_family", "authoritative-java-shadow")
                .put("strategy_version", "v5-shadow-oracle").put("created_at", capturedAt)
                .put("stage", "CORE_PREMISE").put("phenomenon", "completed-bar price dislocation")
                .put("economic_behavioral_mechanism", "patient crypto liquidity absorbs a temporary dislocation")
                .put("persistence", "short-lived after a completed bar")
                .put("crowding_decay", "the edge decays after price normalization").put("direction", "long")
                .put("expression", "BTC spot").put("role_of_composite_score", "not used")
                .put("failure_invalidation_mechanism", "the completed-bar dislocation stops reverting")
                .put("status", "FROZEN");
        value.set("participants", object().put("forced_actor", "crypto traders")
                .put("edge_provider", "patient liquidity").put("edge_consumer", "systematic strategy"));
        value.set("holding_horizon", object().put("min", 1).put("max", 3).put("unit", "minutes"));
        value.set("expected_signal_frequency", object().put("min", 0).put("max", 1).put("unit", "fraction"));
        value.set("expected_win_rate", object().put("min", 0).put("max", 1));
        ObjectNode payoff = object();
        payoff.set("average_win_r", object().put("min", 1).put("max", 1));
        payoff.set("average_loss_r", object().put("min", -1).put("max", -1));
        payoff.put("qualitative_shape", "bounded fixture"); value.set("payoff", payoff);
        ObjectNode regimes = object();
        regimes.set("expected_to_work", array().add("completed-bar fixture"));
        regimes.set("expected_to_fail", array().add("non-reverting fixture"));
        value.set("regimes", regimes);
        ObjectNode required = object().put("input_id", "completed-price").put("evidence_family", "price")
                .put("role", "CORE");
        required.set("availability", object().put("status", "PIT_SAFE"));
        required.set("point_in_time", object().put("status", "PIT_SAFE"));
        value.set("required_inputs", array().add(required));
        value.set("falsifier", object().put("test", "no positive robust expectancy")
                .put("null", "no edge").set("rejection_thresholds", object().put("minimum", 0)));
        value.set("tradable_instrument_contract", object().put("universe", "CRYPTO_ONLY")
                .set("instruments", array().add("spot")));
        value.set("trade_assets", array().add("btc")); value.set("non_crypto_context_only", array());
        value.set("independence_replication_groups", array().add(array().add("btc")));
        // The authoritative runner reopens the complete physical stress
        // contract, which is wider than the legacy five-row acceptance suite.
        // Keep this fixture-only contract on the frozen precommit so both
        // implementations consume the same immutable source bytes.
        ArrayNode stress = array();
        stress.add(stressScenario("DELAYED_ENTRY", object().put("delay_bars", 1)));
        stress.add(stressScenario("ADVERSE_COLLISION", object().put("stop_price", 95).put("target_price", 110)));
        stress.add(stressScenario("LIQUIDITY", object().put("liquidity_model", "ADVERSE_IMPACT_BPS")
                .put("liquidity_impact_bps", 1)));
        stress.add(stressScenario("EXPIRY", object().put("expiry_policy", "NEXT_OFFICIAL_SETTLEMENT")
                .put("not_applicable", true)));
        stress.add(stressScenario("LIQUIDATION", object().put("liquidation_rule", "ADVERSE_MARK_MOVE")
                .put("adverse_move_bps", 100).put("not_applicable", true)));
        stress.add(stressScenario("LEAVE_ONE_ASSET", object().put("asset", "btc")));
        stress.add(stressScenario("LEAVE_ONE_REGIME", object().put("field", "edge").put("value", 999)
                .put("survival_condition", "NOT_APPLICABLE")));
        stress.add(stressScenario("LEAVE_ONE_CONTEXT", object().put("evidence_leg", "never_true_context")
                .put("survival_condition", "NOT_APPLICABLE")));
        value.set("acceptance_contract", object().set("stress_scenarios", stress));
        value.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(value)); return value;
    }

    private static ObjectNode physicalEvaluatorSpec(ObjectNode precommit, ObjectNode gene,
                                                     ObjectNode predictors) {
        ObjectNode args = object().put("strategyFamily", "authoritative-java-shadow")
                .put("precommitSha256", precommit.path("content_sha256").asText());
        args.set("geneSpace", gene); args.set("predictorRegistry", predictors);
        args.set("predicate", object().put("predictor_id", "edge").put("op", "GTE")
                .set("value", object().put("$gene", "threshold")));
        ObjectNode lifecycle = object().put("max_lifecycle_ms", 180_000);
        lifecycle.set("stop", object().put("type", "PERCENT").put("value", .05));
        lifecycle.set("target", object().put("type", "R_MULTIPLE").put("multiple", 2));
        lifecycle.set("partial_exits", array().add(object().put("trigger_r", .5).put("fraction", .5)));
        lifecycle.set("trailing", object().put("type", "PERCENT").put("percent", .01));
        lifecycle.set("sizing", object().put("mode", "FIXED_NOTIONAL").put("notional_usd", 100));
        lifecycle.put("gap_policy", "OPEN");
        ObjectNode template = object().put("direction", "long").put("instrument_type", "spot")
                .put("entry_policy", "NEXT_BAR_OPEN").put("lifecycle_timeframe", "1m")
                .put("max_lifecycle_ms", 180_000).put("lifecycle_engine", "strategy-v5-trade-lifecycle/1");
        template.set("exit_policy", object().put("type", "TARGET_STOP").put("stop_price", 95)
                .put("target_price", 110).put("collision_policy", "ADVERSE_STOP_FIRST"));
        template.set("lifecycle", lifecycle); args.set("candidateTemplate", template);
        ObjectNode execution = object();
        execution.set("risk_convention", object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", 10));
        execution.set("sizing_contract", object().put("mode", "FIXED_NOTIONAL_USD")
                .put("notional_usd", 100).put("quantity_step", .001).put("min_notional_usd", 10));
        ArrayNode extraStress = array();
        extraStress.add(stressScenario("DELAYED_ENTRY", object().put("delay_bars", 1)));
        extraStress.add(stressScenario("ADVERSE_COLLISION", object().put("stop_price", 95)
                .put("target_price", 110)));
        extraStress.add(stressScenario("LIQUIDITY", object().put("liquidity_model", "ADVERSE_IMPACT_BPS")
                .put("liquidity_impact_bps", 1)));
        extraStress.add(stressScenario("EXPIRY", object().put("expiry_policy", "NEXT_OFFICIAL_SETTLEMENT")
                .put("not_applicable", true)));
        extraStress.add(stressScenario("LIQUIDATION", object().put("liquidation_rule", "ADVERSE_MARK_MOVE")
                .put("adverse_move_bps", 100).put("not_applicable", true)));
        extraStress.add(stressScenario("LEAVE_ONE_ASSET", object().put("asset", "btc")));
        extraStress.add(stressScenario("LEAVE_ONE_REGIME", object().put("field", "edge").put("value", 999)
                .put("survival_condition", "NOT_APPLICABLE")));
        extraStress.add(stressScenario("LEAVE_ONE_CONTEXT", object().put("evidence_leg", "never_true_context")
                .put("survival_condition", "NOT_APPLICABLE")));
        execution.set("stress_scenarios", extraStress); args.set("executionContract", execution);
        return StrategyEvaluatorV5.makeEvaluatorSpecV5(args);
    }

    private static ObjectNode stressScenario(String id, ObjectNode parameters) {
        parameters.put("minimum_observations", 1).put("minimum_expectancy_r", -1)
                .put("minimum_p20_r", -1).put("minimum_profit_factor", 0)
                .put("maximum_drawdown_r", 100).put("maximum_cost_r", 100)
                .put("minimum_coverage_fraction", 0);
        return object().put("id", id).put("required", true).set("parameters", parameters);
    }

    private static ObjectNode physicalRawInputs() {
        ArrayNode features = array(), labels = array(), executions = array(), marks = array(), hydrationBars = array();
        for (int day = 1; day <= 8; day++) {
            double edge = day % 3 == 0 ? 2 : day % 3 == 1 ? 2 : 2.5;
            String decision = isoDay(day, 0);
            features.add(object().put("asset", "btc").put("venue", "BINANCE")
                    .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT").put("timeframe", "4h")
                    .put("event_time", decision).put("decision_time", decision).put("availability_time", decision)
                    .put("open", 1).put("high", 2).put("low", .5).put("close", edge));
            labels.add(object().put("asset", "btc").put("venue", "BINANCE")
                    .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT").put("decision_time", decision));
            ArrayNode children = array();
            double[][] values = {{101, 101.5, 99, 100.4}, {101, 104, 101, 102.5},
                    {106, 107, 105, 106.5}, {103, 106, 103, 103.5}};
            for (int minute = 0; minute < values.length; minute++) {
                ObjectNode bar = object().put("asset", "btc").put("venue", "BINANCE")
                        .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                        .put("event_time", isoDay(day, minute)).put("availability_time", isoDay(day, minute + 1))
                        .put("open", values[minute][0]).put("high", values[minute][1])
                        .put("low", values[minute][2]).put("close", values[minute][3])
                        .put("volume", 1_000).put("quote_volume", 100_000);
                children.add(bar); hydrationBars.add(bar.deepCopy());
            }
            executions.add(object().put("asset", "btc").put("venue", "BINANCE")
                    .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                    .put("decision_time", decision).set("child_bars", children));
            for (int hour = 0; hour < 24; hour += 4) {
                long event = Instant.parse(decision).toEpochMilli() + hour * 3_600_000L;
                marks.add(object().put("asset", "btc").put("venue", "BINANCE")
                        .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                        .put("series_role", "MARK").put("series_id", "btc-spot-mark-4h")
                        .put("cadence_ms", 14_400_000).put("event_time", iso(event))
                        .put("availability_time", iso(event + 14_400_000 - 1)).put("price", 100));
            }
        }
        ObjectNode result = object(); result.set("features", features); result.set("labels", labels);
        result.set("execution", executions); result.set("marks", marks); result.set("hydration_bars", hydrationBars);
        return result;
    }

    private static ObjectNode physicalSourceManifest(Path staging, ObjectNode plan, ObjectNode raw,
                                                      String capturedAt) throws IOException {
        Path rawRoot = staging.resolve("lineage/raw"); Path receiptsRoot = staging.resolve("lineage/receipts");
        Path roleRoot = staging.resolve("role");
        Files.createDirectories(rawRoot); Files.createDirectories(receiptsRoot); Files.createDirectories(roleRoot);
        byte[] rawBytes = "authoritative-java-shadow-source".getBytes(StandardCharsets.UTF_8);
        String rawSha = JsonHashes.sha256(rawBytes); String rawPath = "lineage/raw/" + rawSha + ".bin";
        Files.write(staging.resolve(rawPath), rawBytes, StandardOpenOption.CREATE_NEW);
        ObjectNode rawReceipt = object().put("schema", "strategy-v5-source-receipt/1")
                .put("version", 1).put("path", rawPath).put("source", "FIXTURE_BINANCE");
        rawReceipt.set("request", object().put("endpoint", "fixture://authoritative-java-shadow")
                .put("response_sha256", rawSha));
        rawReceipt.put("byte_sha256", rawSha).put("bytes", rawBytes.length).put("format", "RAW_BYTES")
                .put("storage_role", "RAW_IGNORED").put("authoritative", false);
        rawReceipt = ownHashed(rawReceipt);
        ObjectNode normalized = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1)
                .put("status", "PUBLIC_OBSERVED").put("captured_at", capturedAt);
        normalized.set("request", object().put("endpoint", "fixture://authoritative-java-shadow"));
        normalized.set("response_sha256", array().add(rawSha));
        normalized.set("source_byte_sha256", array().add(rawSha));
        normalized.set("raw_receipts", array().add(rawReceipt));
        normalized.set("coverage", object().put("complete", true)); normalized = ownHashed(normalized);
        String receiptPath = "lineage/receipts/" + normalized.path("content_sha256").asText() + ".json";
        writeJson(staging.resolve(receiptPath), normalized);
        ObjectNode summary = object().put("path", receiptPath)
                .put("sha256", normalized.path("content_sha256").asText())
                .put("content_sha256", normalized.path("content_sha256").asText())
                .put("byte_sha256", rawSha).put("raw_count", 1)
                .put("schema", normalized.path("schema").asText()).put("status", normalized.path("status").asText());

        ObjectNode references = object(); ArrayNode captures = array();
        String[][] roles = {{"features", "raw_signal_bars", "4h"}, {"labels", "raw_opportunity_bars", "labels"},
                {"execution", "raw_execution_bars", "execution"}, {"marks", "raw_mark_bars", "1m"}};
        for (String[] role : roles) {
            Path path = roleRoot.resolve(role[0] + ".jsonl"); writeJsonl(path, raw.path(role[0]));
            byte[] bytes = Files.readAllBytes(path); String sha = JsonHashes.sha256(bytes);
            String portable = "role/" + role[0] + ".jsonl";
            references.set(role[0], object().put("path", portable).put("format", "JSONL").put("sha256", sha));
            ObjectNode partition = object().put("path", portable).put("sha256", sha).put("bytes", bytes.length)
                    .put("row_count", raw.path(role[0]).size()).put("format", "JSONL")
                    .put("storage_role", "STAGING").put("authoritative", false);
            ObjectNode coverage = object().put("complete", true).put("expected_rows", raw.path(role[0]).size())
                    .put("observed_rows", raw.path(role[0]).size());
            ObjectNode capture = object().put("asset", "btc").put("venue", "BINANCE")
                    .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                    .put("interval", role[2]).put("series_type", role[1]).put("required", true);
            capture.set("partition", partition); capture.set("source_receipts", array().add(summary.deepCopy()));
            capture.set("coverage", coverage); captures.add(capture);
        }
        ObjectNode manifest = object().put("schema", StrategyResearchDataV5.DATA_V5.get("acquisition"))
                .put("version", 1).put("status", "STAGING_COMPLETE")
                .put("plan_sha256", plan.path("content_sha256").asText()).put("root_reference", "fixture")
                .put("staging_format", "JSONL").put("storage_role", "STAGING").put("authoritative", false)
                .put("base_complete", true).put("declared_complete", true).put("full_plan_complete", true)
                .put("completion_scope", "ALL_DECLARED").put("required_series_count", 4)
                .put("required_complete_count", 4).put("optional_series_count", 0)
                .put("optional_complete_count", 0).put("optional_complete", true);
        manifest.set("captures", captures); manifest.set("unavailable_required", array());
        manifest.set("unavailable_optional", array()); manifest.set("source_receipts", array().add(receiptPath));
        manifest.set("source_receipt_sha256", array().add(normalized.path("content_sha256").asText()));
        manifest.set("source_receipt_byte_sha256", array().add(rawSha)); manifest.set("limitations", array());
        manifest = ownHashed(manifest); Path manifestPath = writeJson(staging.resolve("source-manifest.json"), manifest);
        ObjectNode reference = object().put("path", "source-manifest.json")
                .put("content_sha256", manifest.path("content_sha256").asText())
                .put("byte_sha256", JsonHashes.sha256(Files.readAllBytes(manifestPath)));
        ObjectNode result = object(); result.set("manifest", manifest); result.set("reference", reference);
        result.set("references", references); ((ObjectNode) raw).set("references", references.deepCopy());
        return result;
    }

    private static ObjectNode physicalExecutionConfig() {
        ObjectNode config = object().put("schema", "strategy-v5-config-fixture/1").put("version", 1)
                .put("name", "authoritative-java-shadow-config");
        config.set("sizing_contract", object().put("risk_budget_usd", 10).put("stop_distance_usd", 5)
                .put("contract_multiplier", 1).put("lot_step", .001).put("minimum_quantity", .001)
                .put("minimum_notional", 10).put("notional_usd", 100)
                .put("sizing_source", "FROZEN_RISK_STOP_AND_CONTRACT_METADATA"));
        config.set("execution_capacity_contract", object().put("participation_cap", .5)
                .put("order_notional_usd", 100).put("liquidity_source", "BOUND_COMPLETED_BAR_QUOTE_VOLUME"));
        config.set("execution_liquidity_contract", object().put("model", "BOUND_COMPLETED_BAR_QUOTE_VOLUME")
                .put("order_notional_usd", 100).put("observed_impact_bps", 0));
        return ownHashed(config);
    }

    private static ObjectNode producePhysicalRoles(Path staging, ObjectNode plan, ObjectNode predictors,
                                                    ObjectNode precommit, ObjectNode envelope,
                                                    ObjectNode config, ObjectNode sourceChain,
                                                    ObjectNode references, boolean includeMarks) {
        ObjectNode options = object().put("root", staging.toString()); options.set("plan", plan);
        options.set("predictorRegistry", predictors);
        options.set("sourceManifestReference", sourceChain.path("reference").deepCopy());
        options.put("sourceManifestSha256", sourceChain.path("manifest").path("content_sha256").asText());
        String producer = StrategyResearchDataV5.javaProducerCodeSha256();
        options.put("transformationCodeSha256", producer).put("labelCodeSha256", producer)
                .put("executionCodeSha256", producer)
                .put("configSha256", config.path("content_sha256").asText())
                .put("precommitSha256", precommit.path("content_sha256").asText())
                .put("envelopeSha256", envelope.path("content_sha256").asText());
        options.set("precommit", precommit); options.set("envelope", envelope); options.set("config", config);
        ObjectNode roleSources = references.deepCopy(); if (!includeMarks) roleSources.remove("marks");
        options.set("roleSources", roleSources);
        return StrategyResearchDataV5.produceAuthoritativeRoleArtifacts(options);
    }

    private static void makeHydrationPortable(ObjectNode hydration, Path hydrationRoot) {
        for (JsonNode raw : hydration.path("partition_inventory")) {
            ObjectNode row = (ObjectNode) raw; Path path = Path.of(row.path("partition_path").asText())
                    .toAbsolutePath().normalize();
            row.put("partition_path", hydrationRoot.toAbsolutePath().normalize().relativize(path)
                    .toString().replace(path.getFileSystem().getSeparator(), "/"));
        }
        for (JsonNode raw : hydration.path("windows")) {
            ObjectNode window = (ObjectNode) raw;
            for (String field : List.of("preentry_partition_refs", "partition_refs", "mark_partition_refs")) {
                for (JsonNode ref : window.path(field)) {
                    if (!ref.isObject() || !ref.hasNonNull("partition_path")) continue;
                    ObjectNode row = (ObjectNode) ref; Path path = Path.of(row.path("partition_path").asText())
                            .toAbsolutePath().normalize();
                    row.put("partition_path", hydrationRoot.toAbsolutePath().normalize().relativize(path)
                            .toString().replace(path.getFileSystem().getSeparator(), "/"));
                }
            }
        }
        ArrayNode rootRows = array();
        for (JsonNode raw : hydration.path("partition_inventory")) {
            ObjectNode row = object();
            for (String field : List.of("partition_sha256", "partition_path", "bytes", "row_count",
                    "min_event_time", "max_event_time", "asset", "instrument", "symbol", "series_role")) {
                row.set(field, raw.path(field).deepCopy());
            }
            rootRows.add(row);
        }
        List<JsonNode> sorted = new ArrayList<>(); rootRows.forEach(sorted::add);
        sorted.sort(Comparator.comparing(row -> row.path("partition_sha256").asText()));
        hydration.put("partition_bytes_root_sha256",
                StrategyResearchAuthoritativeV5.hash(array(sorted)));
        hydration.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(hydration));
    }

    private static ObjectNode buildPhysicalMetadata(Path root, Path planPath, Path precommitPath,
                                                     Path evaluatorPath, ObjectNode plan,
                                                     ObjectNode precommit, ObjectNode evaluator,
                                                     Path recordRoot) throws IOException {
        String capturedAt = "2026-08-24T00:00:00.000Z";
        ObjectNode policy = object().put("schema", "strategy-v5-spot-execution-policy/1")
                .put("version", 1).put("status", "FROZEN").put("created_at", capturedAt)
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("precommit_sha256", precommit.path("content_sha256").asText())
                .put("evaluator_spec_sha256", evaluator.path("content_sha256").asText())
                .put("instrument", "BINANCE_SPOT").put("outage_policy", "FAIL")
                .put("gap_policy", "FILL_AT_OPEN")
                .put("assumption_mode", "RETROSPECTIVE_USER_BOUND_RESEARCH_ASSUMPTION")
                .put("activation_eligible", false);
        ObjectNode researchWindow = object();
        researchWindow.set("start_at", plan.path("window").path("start_at").deepCopy());
        researchWindow.set("end_at", plan.path("window").path("end_at").deepCopy());
        policy.set("research_window", researchWindow);
        policy.set("asset_contracts", array().add(object().put("asset", "btc").put("symbol", "BTCUSDT")
                .put("contract_multiplier", 1).put("step_size", .001).put("min_qty", .001)
                .put("max_qty", 1_000).put("min_notional", 10).put("max_notional", 100_000)));
        policy.set("cost_model", object().put("taker_fee_rate", .001).put("slippage_bps", 0)
                .put("impact_bps", 0));
        policy.set("limitations", array().add("NOT_HISTORICAL_BINANCE_FEE_OBSERVATIONS"));
        policy.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(policy));
        Path policyPath = writeJson(root.resolve("metadata-policy.json"), policy);
        Path metadataRoot = root.resolve("metadata-source");
        ObjectNode options = object().put("plan", planPath.toString()).put("precommit", precommitPath.toString())
                .put("evaluator_spec", evaluatorPath.toString()).put("policy", policyPath.toString())
                .put("output_root", metadataRoot.toString())
                .put("root_reference", Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                        .relativize(metadataRoot).toString())
                .put("out", root.resolve("metadata.json").toString())
                .put("receipt", root.resolve("metadata-receipt.json").toString())
                .put("record_root", recordRoot.toString());
        ObjectNode built = StrategyResearchAuthoritativeV5.authoritativeMetadataBuild(options);
        return augmentSpotMetadataBundle(Path.of(built.path("metadata_path").asText()), built);
    }

    /**
     * The portfolio consumer reopens a complete metadata bundle even for a
     * spot-only fixture.  Bind explicit spot-safe placeholder receipts for the
     * derivative-only kinds so the physical input inventory is complete; no
     * derivative trade is inferred from these records.
     */
    private static ObjectNode augmentSpotMetadataBundle(Path metadataPath, ObjectNode built) throws IOException {
        ObjectNode bundle = (ObjectNode) JSON.readTree(Files.readAllBytes(metadataPath));
        ObjectNode contract = ((ObjectNode) bundle.path("contract_spec")).deepCopy();
        for (String kind : List.of("MARGIN", "LIQUIDATION", "EXPIRY", "FUNDING_IDENTITY")) {
            ObjectNode receipt = contract.deepCopy().put("kind", kind);
            ArrayNode records = array();
            for (JsonNode raw : contract.path("records")) {
                ObjectNode row = ((ObjectNode) raw).deepCopy();
                switch (kind) {
                    case "MARGIN" -> row.put("maintenance_margin_ratio", .01)
                            .put("margin_mode", "ISOLATED").put("tier_id", "SPOT_FIXTURE")
                            .put("collateral_asset", "USDT").put("leverage", 1);
                    case "LIQUIDATION" -> row.put("liquidation_price", 1)
                            .put("mark_series_type", "LIQUIDATION_MARK");
                    case "EXPIRY" -> row.put("expiry", "2026-08-23T20:03:00.000Z");
                    case "FUNDING_IDENTITY" -> row.put("event_id", "spot-fixture-funding")
                            .put("funding_rate", 0).put("event_time", row.path("effective_from").asText());
                    default -> { }
                }
                records.add(row);
            }
            receipt.set("records", records);
            if ("FUNDING_IDENTITY".equals(kind)) {
                receipt.set("coverage", object().put("complete", false).putNull("cadence_ms")
                        .putNull("anchor_time").set("cadence_segments", array()));
            }
            receipt.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(receipt));
            bundle.set(kind.toLowerCase(java.util.Locale.ROOT), receipt);
        }
        Files.writeString(metadataPath, pretty(bundle), StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        built.set("metadata", bundle); built.put("bundle_sha256", StrategyResearchAuthoritativeV5.hash(bundle));
        return built;
    }

    private static ObjectNode physicalDefinition(ObjectNode precommit, ObjectNode evaluator) {
        ObjectNode feature = object();
        feature.set("series", array().add(object().put("series_id", "btc-4h")
                .put("asset", "btc").put("asset_class", "crypto").put("timeframe", "4h")
                .put("tradable", true).put("context_only", false).put("point_in_time", true)));
        ObjectNode input = object().put("input_id", "completed-price").put("availability", "BAR_CLOSE")
                .put("evidence_family", "price").put("role", "CORE");
        input.set("point_in_time", object().put("status", "PIT_SAFE"));
        feature.set("inputs", array().add(input));
        ObjectNode args = object(); args.set("precommit", precommit);
        args.set("candidate_template", evaluator.path("candidate_template").deepCopy());
        args.set("feature_contract", feature);
        args.set("tradable_instrument_contract", precommit.path("tradable_instrument_contract").deepCopy());
        args.put("hypothesis_family", "authoritative-java-shadow");
        return LegacyResearchV2.makeV2Definition(args);
    }

    private static ObjectNode physicalExperimentPolicy(String capturedAt) {
        ObjectNode defaults = LegacyResearchV3.makeAcceptanceContract();
        ObjectNode gates = ((ObjectNode) defaults.path("gates")).deepCopy();
        gates.put("minimum_independent_episodes", 1).put("minimum_completed_episodes", 1)
                .put("minimum_expectancy_r", -1).put("minimum_search_adjusted_expectancy_r", -1)
                .put("minimum_r_profit_factor", 0).put("minimum_account_profit_factor", 0)
                .put("minimum_total_return", -1).put("minimum_bootstrap_p20_expectancy_r", -1)
                .put("maximum_candidate_set_p_value", 1).put("maximum_drawdown_pct", 100)
                .put("maximum_drawdown_r", 100).put("maximum_cost_r", 100)
                .put("minimum_positive_years", 1).put("minimum_episodes_per_positive_year", 1)
                .put("minimum_positive_blocks", 1).put("maximum_negative_block_expectancy_r", 100)
                .put("minimum_doubled_cost_expectancy_r", -1)
                .put("minimum_doubled_cost_account_profit_factor", 0)
                .put("minimum_coverage_fraction", 0).put("maximum_undeclared_gap_bars", 100)
                .put("minimum_wfo_oos_episodes", 1).put("minimum_wfo_positive_folds", 1);
        ArrayNode stress = array();
        for (JsonNode raw : defaults.path("stress_scenarios")) {
            ObjectNode row = ((ObjectNode) raw).deepCopy();
            ((ObjectNode) row.path("parameters")).put("minimum_expectancy_r", -1)
                    .put("minimum_observations", 1);
            stress.add(row);
        }
        ObjectNode acceptanceArgs = object().put("contractId", "java-shadow-oracle")
                .put("profile", "java-shadow-oracle");
        acceptanceArgs.set("gates", gates); acceptanceArgs.set("stressScenarios", stress);
        ObjectNode acceptance = LegacyResearchV3.makeAcceptanceContract(acceptanceArgs);
        ObjectNode training = LegacyResearchV3.makeTrainingSelectionPolicy(
                object().put("minimumCompletedTrades", 1).put("minimumExpectancyR", -1));
        ObjectNode policy = object().put("schema", "strategy-v5-experiment-policy/1").put("version", 1)
                .put("status", "FROZEN").put("experiment_id", "authoritative-java-shadow-experiment")
                .put("created_at", capturedAt).put("stage", "CORE_PREMISE")
                .put("evidence_phase", "DEVELOPMENT");
        policy.set("acceptance_contract", acceptance);
        ObjectNode chronology = object().put("bootstrap_iterations", 8)
                .put("timezone", "UTC")
                .put("bar_convention", "COMPLETED_4H_BOUNDARY_NEXT_1M_OPEN");
        chronology.set("seeds", array().add(11).add(23).add(47));
        chronology.set("development_window", object().put("start_at", "2025-12-31T00:00:00.000Z")
                .put("end_at", "2026-01-09T00:00:00.000Z"));
        chronology.set("monitoring_window", object().put("start_at", "2026-01-10T00:00:00.000Z")
                .put("end_at", "2026-01-20T00:00:00.000Z"));
        policy.set("chronology", chronology);
        policy.set("portfolio_policy", object()); policy.set("training_selection_policy", training);
        return ownHashed(policy);
    }

    private static ObjectNode physicalPortfolioPolicy(ObjectNode plan, ObjectNode precommit,
                                                       ObjectNode evaluator, ObjectNode experiment) {
        ObjectNode value = object().put("schema", "strategy-portfolio-policy/2").put("version", 2)
                .put("status", "FROZEN").put("venue", "binance").put("interval_ms", 60_000)
                .put("current_equity", 10_000).put("initial_equity", 10_000)
                .put("asOf", plan.path("window").path("end_at").asText())
                .put("consuming_cutoff", plan.path("window").path("end_at").asText())
                .put("account_currency", "USDT").put("max_concurrent", 10).put("min_common_timestamps", 30)
                .put("precommit_sha256", precommit.path("content_sha256").asText())
                .put("experiment_sha256", experiment.path("content_sha256").asText())
                .put("acceptance_sha256", StrategyResearchAuthoritativeV5.hash(experiment.path("acceptance_contract")))
                .put("lifecycle_sha256", StrategyResearchAuthoritativeV5.hash(evaluator.path("execution_contract")))
                .put("initial_risk_per_trade_pct", 1.5).put("max_total_open_risk_pct", 6)
                .put("max_asset_open_risk_pct", 3).put("max_cluster_open_risk_pct", 4.5)
                .put("max_gross_exposure_x", 3).put("max_net_exposure_x", 2)
                .put("max_collateral_pct", 70).put("max_positions", 6).put("max_positions_per_asset", 2)
                .put("max_drawdown_pct", 18).put("ruin_boundary_pct", 30).put("max_ruin_probability", .05)
                .put("full_risk_after_activation", true).put("probation_ramp", false);
        value.set("limits", object().put("max_drawdown_pct", 90)
                .put("max_underwater_duration_ms", 10_000_000_000L).put("equity_floor", 100)
                .put("ruin_equity_floor", 50).put("minimum_current_equity", 100)
                .put("max_gross_exposure", 100_000).put("max_net_exposure", 100_000)
                .put("max_reserved_fraction", 1).put("max_collateral_fraction", 1)
                .put("max_asset_share", 1).put("max_hhi", 1).put("max_beta_gross", 100_000)
                .put("max_beta_net", 100_000).put("max_maintenance_margin", 100_000)
                .putNull("cross_collateral_account"));
        return ownHashed(value);
    }

    private static Path physicalPortfolioMarks(Path root, Path manifestPath, ObjectNode manifest,
                                                Path metadataPath, String capturedAt) throws IOException {
        ObjectNode metadata = (ObjectNode) JSON.readTree(Files.readAllBytes(metadataPath));
        Path sourceReceiptPath = writeJson(root.resolve("mark-source-receipt.json"),
                metadata.path("contract_spec"));
        String sourceReceiptSha = JsonHashes.sha256(Files.readAllBytes(sourceReceiptPath));
        ObjectNode receiptOptions = object().put("command", "validate").put("status", "COMPLETE");
        receiptOptions.set("inputs", array()); receiptOptions.set("outputs", array());
        receiptOptions.set("limitations", array());
        receiptOptions.set("details", object().put("mode", "AUTHORITATIVE_MARK_FIXTURE").put("active", false));
        ObjectNode commandReceipt = StrategyResearchAuthoritativeV5.makeCommandReceipt(receiptOptions);
        Path commandPath = writeJson(root.resolve("mark-command-receipt.json"), commandReceipt);
        String commandSha = JsonHashes.sha256(Files.readAllBytes(commandPath));
        String manifestSha = JsonHashes.sha256(Files.readAllBytes(manifestPath));
        Path codePath = Path.of(System.getProperty("tradinganalytics.repository.root"))
                .resolve("analytics-research/src/main/java/com/tradinganalytics/research/v5/StrategyPortfolioRiskV5.java");
        String codeSha = JsonHashes.sha256(Files.readAllBytes(codePath));
        ObjectNode lineage = object().put("source_manifest_sha256", manifestSha)
                .put("source_receipt_sha256", sourceReceiptSha)
                .put("command_receipt_sha256", commandSha).put("source_code_sha256", codeSha);
        String lineageSha = StrategyResearchAuthoritativeV5.hash(lineage);
        ArrayNode rows = array();
        long start = Instant.parse("2026-01-01T00:00:00.000Z").toEpochMilli();
        long end = Instant.parse("2026-01-08T00:06:00.000Z").toEpochMilli();
        int index = 0;
        for (long event = start; event <= end; event += 60_000, index++) {
            String available = iso(event);
            rows.add(object().put("asset", "btc").put("symbol", "BTCUSDT")
                    // A mark becomes consumable at the lifecycle boundary while
                    // retaining one preceding cadence of physical event context;
                    // this satisfies both exact fill lookup and MARK-role cover.
                    .put("series_type", "TRADE_MARK").put("event_time", iso(event - 60_000))
                    .put("availability_time", available).put("price", 100));
            rows.add(object().put("asset", "btc").put("symbol", "BTCUSDT")
                    .put("series_type", "RISK_REFERENCE").put("event_time", iso(event - 60_000))
                    .put("availability_time", available).put("price", 100 + ((index % 97) - 48) * .001));
        }
        ObjectNode options = object().put("venue", "binance").put("intervalMs", 60_000)
                .put("provenance", "AUTHORITATIVE_RECOMPUTED")
                .put("sourceManifestSha256", manifestSha).put("sourceManifestPath", manifestPath.toString())
                .put("sourceReceiptSha256", sourceReceiptSha).put("sourceReceiptPath", sourceReceiptPath.toString())
                .put("sourceCommandReceiptSha256", commandSha).put("sourceCommandReceiptPath", commandPath.toString())
                .put("sourceCodeSha256", codeSha).put("sourceCodePath", codePath.toString())
                .put("lineageSha256", lineageSha);
        options.set("rows", rows); Path path = root.resolve("portfolio-marks.json");
        StrategyPortfolioRiskV5.writeMarkArtifact(path, options); return path;
    }

    private static ObjectNode physicalShadowWfo(ObjectNode options, StrategyEvaluatorV5.Evaluator evaluator,
                                                 StrategyStatisticalV5.StatisticalProvider stressProvider,
                                                 StrategyStatisticalV5.StatisticalProvider portfolioProvider,
                                                 StrategyStatisticalV5.StatisticalProvider oosVectorProvider,
                                                 Path headPath, ObjectNode evaluatorSpec,
                                                 ObjectNode precommit, ObjectNode manifest) {
        ObjectNode source = (ObjectNode) options.path("artifact");
        List<String> ids = new ArrayList<>(); for (JsonNode episode : source.path("episodes")) {
            ids.add(episode.path("episode_id").asText());
        }
        String fold = "E2E-OUTER"; ObjectNode view = object().put("schema",
                "strategy-v5-statistical-signal-view/1").put("version", 1).put("phase", "OUTER_OOS")
                .put("fold_id", fold).put("source_artifact_sha256", source.path("content_sha256").asText());
        view.set("lineage", source.path("lineage").deepCopy()); view.set("episode_ids", strings(ids));
        ArrayNode identityRows = array();
        for (JsonNode episode : source.path("episodes")) {
            identityRows.add(object().put("episode_id", episode.path("episode_id").asText())
                    .put("asset", episode.path("asset").asText())
                    .put("decision_time", episode.path("decision_time").asText())
                    .put("resolution_time", episode.path("resolution_time").asText())
                    .put("eligible", episode.path("eligible").asBoolean(true))
                    .put("phase", "OUTER_OOS").put("fold_id", fold));
        }
        view.set("episodes", identityRows); view.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(view));
        ObjectNode chromosome = object().put("threshold", 1);
        ObjectNode task = object(); task.set("artifact", view); task.set("episode_ids", strings(ids));
        task.set("chromosome", chromosome); task.put("phase", "OUTER_OOS").put("fold_id", fold);
        task.putNull("cutoff").putNull("fit_cutoff").putNull("evaluation_cutoff")
                .put("weighting", "UNWEIGHTED_OOS");
        ObjectNode evaluation = evaluator.evaluate(task);
        String alias = evaluation.path("behavior_alias_sha256").asText();
        ObjectNode definitionHash = object().put("schema", "strategy-v5-statistical-behavior-definition/1");
        definitionHash.set("chromosome", StrategyStatisticalV5.effectiveExecutionBehavior(chromosome));
        definitionHash.put("evaluator_sha256", evaluatorSpec.path("content_sha256").asText())
                .put("precommit_sha256", precommit.path("content_sha256").asText())
                .put("lifecycle_sha256", StrategyResearchAuthoritativeV5.hash(
                        evaluatorSpec.path("execution_contract")));
        ObjectNode definitions = object().put(alias, StrategyResearchAuthoritativeV5.hash(definitionHash));
        ObjectNode append = object().put("filePath", headPath.toString())
                .put("expectedHeadSha256", options.path("exposureHead").path("content_sha256").asText())
                .put("datasetSha256", manifest.path("dataset_root_sha256").asText())
                .put("source", "STATISTICAL_SEARCH").put("exposureAttemptCount", 1);
        append.set("behaviorAliases", array().add(alias)); append.set("behaviorDefinitions", definitions);
        append.set("vectorCommitments", object());
        ObjectNode head = StrategyStatisticalV5.appendExposureHeadFile(append);

        ArrayNode selectedReturns = array();
        for (JsonNode episode : source.path("episodes")) {
            String id = episode.path("episode_id").asText(); JsonNode outcome = evaluation.path("candidate_returns").path(id);
            selectedReturns.add(object().put("episode_id", id).put("asset", episode.path("asset").asText())
                    .put("decision_time", episode.path("decision_time").asText())
                    .put("resolution_time", episode.path("resolution_time").asText())
                    .put("net_r", outcome.path("net_r").asDouble())
                    .put("traded", outcome.path("traded").asBoolean(false)));
        }
        String selectionSha = StrategyResearchAuthoritativeV5.hash(object().put("fold", fold)
                .put("behavior", alias).put("evaluation", evaluation.path("content_sha256").asText()));
        String stressLineage = StrategyResearchAuthoritativeV5.hash(object().put("fold_id", fold)
                .put("asset", "btc").put("selected_alias", alias)
                .put("selection_procedure_sha256", selectionSha)
                .put("exposure_head_sha256", head.path("content_sha256").asText())
                .put("test_artifact_sha256", source.path("content_sha256").asText()));
        ObjectNode stressArgs = object(); stressArgs.set("artifact", source);
        stressArgs.put("selected_candidate_id", alias).put("fold_id", fold).put("asset", "btc")
                .put("lineage_sha256", stressLineage);
        ObjectNode stress = stressProvider.provide(stressArgs);
        ObjectNode geneticMetrics = object().put("expectancy_r", 1).put("cost_r", 0)
                .put("coverage_fraction", 1).put("capacity_pass", true)
                .put("max_drawdown_r", 0).put("profit_factor", 1_000_000);
        ObjectNode geneticFitness = object(); geneticFitness.set("metrics", geneticMetrics);
        geneticFitness.set("objectives", array().add(1)); geneticFitness.put("feasible", true);
        geneticFitness.set("violations", array()); geneticFitness.set("violation_details", object());
        geneticFitness.put("total_violation", 0); geneticFitness.putNull("tie_breaker");
        ObjectNode geneticHistory = object(); geneticHistory.set("chromosome", chromosome.deepCopy());
        geneticHistory.put("behavior_sha256", alias).put("behavior_alias_sha256", alias)
                .put("generation", 0).put("seed", 11).put("operator", "SIMPLE_BASELINE");
        geneticHistory.set("parent_ids", array()); geneticHistory.put("confirmation", true)
                .put("cache_hit", false).put("evaluation_attempt_sha256", StrategyResearchAuthoritativeV5.hash("fixture-ga-attempt"))
                .put("evaluation_ordinal", 1).put("scheduler_order", 1).put("checkpoint_generation", 0);
        geneticHistory.set("fitness", geneticFitness);
        ObjectNode geneticConfig = object(); geneticConfig.put("population", 1).put("generations", 1)
                .put("minGenerations", 1).put("plateauGenerations", 1)
                .put("crossoverProbability", 0).putNull("mutationProbability");
        geneticConfig.set("seeds", array().add(11).add(23).add(47)); geneticConfig.put("halfLifeMonths", 18)
                .put("operator", "FIXTURE").put("scheduler_ordering", "DETERMINISTIC")
                .put("mode", "AUTHORITATIVE");
        ArrayNode geneticSeedRuns = array();
        for (int seed : new int[] {11, 23, 47}) geneticSeedRuns.add(object().put("seed", seed)
                .put("generations_completed", 1).put("stopping", "FIXTURE")
                .put("evaluated_k", 1).set("finalists", array().add(alias)));
        ObjectNode selectedGenetic = object(); selectedGenetic.put("behavior_sha256", alias)
                .put("behavior_alias_sha256", alias); selectedGenetic.set("chromosome", chromosome.deepCopy());
        selectedGenetic.set("fitness", geneticFitness.deepCopy());
        ObjectNode genetic = object(); genetic.put("schema", "strategy-v5-statistical-genetic-run/1")
                .put("version", 1).put("fold_id", fold); genetic.set("config", geneticConfig);
        // The production callback receives the broad physical gene-space
        // contract.  The statistical-genetic artifact has a narrower,
        // versioned gene-space schema, so keep this fixture's embedded
        // contract canonical and independently hashed.
        ObjectNode fixtureGeneSpace = object().put("schema", "strategy-v5-statistical-gene-space/1");
        fixtureGeneSpace.set("genes", array().add(object().put("name", "threshold")
                .put("type", "continuous").put("min", 1).put("max", 2)
                .put("step", 1).put("default", 1).put("usage", "predicate:edge:GTE")));
        genetic.set("gene_space", StrategyStatisticalV5.withHash(fixtureGeneSpace));
        genetic.set("training_episode_ids", strings(ids)); genetic.set("population_history", array().add(geneticHistory));
        genetic.set("seed_runs", geneticSeedRuns); genetic.set("evaluated_behavior_aliases", array().add(alias));
        genetic.put("evaluated_k", 1).put("evaluation_attempt_k", 1).put("chromosome_evaluated_k", 1)
                .put("cumulative_k", head.path("cumulative_k").asLong(1))
                .put("cumulative_exposure_k", head.path("cumulative_k").asLong(1))
                .put("exposure_head_sha256", head.path("content_sha256").asText())
                .put("selected_behavior_alias_sha256", alias).put("selected_seed_count", 3);
        ObjectNode stability = object(); stability.put("required", 2); stability.set("stable_aliases", array().add(alias));
        genetic.set("seed_stability", stability); genetic.set("baseline", geneticFitness.deepCopy());
        genetic.set("neighbours", array()); genetic.set("selected", selectedGenetic);
        genetic = ownHashed(genetic);
        ObjectNode decision = object().put("asset", "btc").put("pass", stress.path("pass").asBoolean(false))
                .put("provenance", "AUTHORITATIVE_RECOMPUTED").put("decision_type", "ASSET")
                .put("selected_candidate_id", alias).put("selected_behavior_alias_sha256", alias)
                .put("selection_procedure_sha256", selectionSha).put("stress_sha256",
                        stress.path("content_sha256").asText()).put("pbo_pass", true)
                .put("lineage_sha256", stressLineage);
        decision.set("selected_chromosome", chromosome.deepCopy()); decision.set("selected_return_vector", selectedReturns);
        decision.set("metrics", evaluation.path("metrics").deepCopy()); decision.set("genetic_run", genetic);
        decision.set("stress", stress); decision.set("pbo", object().put("source_phase", "OUTER_TRAIN_ONLY")
                .put("outer_oos_bound", false).put("candidate_count", 1)
                .put("valid_combinations", 1).put("pbo", 0));
        decision.set("procedure_validation", object().put("pass", true)); decision = ownHashed(decision);

        ObjectNode vectorArgs = object(); vectorArgs.set("artifact", source); vectorArgs.set("exposureHead", head);
        vectorArgs.set("episode_ids", strings(ids)); vectorArgs.set("selected_definitions", array().add(
                object().put("asset", "btc").put("selected_candidate_id", alias)
                        .set("chromosome", chromosome.deepCopy())));
        vectorArgs.put("fold_id", fold); ObjectNode vector = oosVectorProvider.provide(vectorArgs);
        ArrayNode candidates = array().add(object().put("candidate_id", "behavior:" + alias)
                .put("behavior_sha256", alias));
        ArrayNode episodes = array();
        for (JsonNode original : source.path("episodes")) {
            ObjectNode episode = ((ObjectNode) original).deepCopy(); ObjectNode returns = object();
            JsonNode row = null; for (JsonNode raw : vector.path("vectors").path(alias)) {
                if (episode.path("episode_id").asText().equals(raw.path("episode_id").asText())) { row = raw; break; }
            }
            returns.set("behavior:" + alias, object().put("net_r", row.path("net_r").asDouble())
                    .put("traded", row.path("traded").asBoolean(false)));
            episode.set("candidate_returns", returns); episodes.add(episode);
        }
        ObjectNode lineage = ((ObjectNode) source.path("lineage")).deepCopy();
        lineage.put("candidate_set_sha256", StrategyResearchAuthoritativeV5.hash(candidates));
        lineage.put("label_set_sha256", StrategyResearchAuthoritativeV5.hash(object()
                .put("source", source.path("lineage").path("label_set_sha256").asText())
                .put("phase", "OUTER_OOS_UNWEIGHTED")));
        ObjectNode artifactArgs = object(); artifactArgs.set("lineage", lineage);
        artifactArgs.set("candidates", candidates); artifactArgs.set("episodes", episodes);
        artifactArgs.set("exposureHead", head); artifactArgs.set("metadata", object()
                .put("phase", "OUTER_OOS_UNWEIGHTED")
                .put("source_artifact_sha256", source.path("content_sha256").asText()));
        ObjectNode finalArtifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactArgs);
        ObjectNode portfolioArgs = object(); portfolioArgs.set("artifact", finalArtifact);
        portfolioArgs.set("asset_decisions", array().add(decision)); portfolioArgs.put("fold_id", "FINAL_OOS");
        portfolioArgs.put("lineage_sha256", StrategyResearchAuthoritativeV5.hash(object()
                .put("phase", "FINAL_OOS").put("artifact", finalArtifact.path("content_sha256").asText())
                .put("head", head.path("content_sha256").asText()).set("asset_decisions", array().add(decision))));
        ObjectNode portfolio = portfolioProvider.provide(portfolioArgs);

        ObjectNode finalScope = object(); finalScope.put("schema", "strategy-v5-statistical-asset-scope/1").put("version", 1);
        finalScope.set("trade_assets", array().add("btc")); finalScope.set("replication_assets", array());
        finalScope.set("context_assets", array()); finalScope.putNull("source_sha256"); finalScope = ownHashed(finalScope);
        ObjectNode auditGates = object();
        for (String gate : List.of("hard_metrics", "baseline_comparison", "bootstrap_p20_positive",
                "weighted_bootstrap_p20_positive", "max_statistic", "search_adjusted_expectancy_positive",
                "dsr", "pbo", "minimum_independent_episodes", "recent_oos_positive", "earlier_blocks",
                "positive_years", "positive_outer_folds", "plateau", "neighbour_fraction", "seed_stability",
                "null_controls", "stress_ablation", "asset_decisions", "portfolio")) auditGates.put(gate, true);
        ObjectNode audit = object(); audit.put("schema", "strategy-v5-statistical-audit/1").put("version", 1)
                .put("selected_candidate_id", alias).put("exposure_head_sha256", head.path("content_sha256").asText())
                .put("sample_count", ids.size()).put("independent_opportunity_count", ids.size())
                .put("independent_trade_count", ids.size())
                .put("market_cluster_inventory_sha256", StrategyResearchAuthoritativeV5.hash("fixture-clusters"));
        audit.set("max_statistic", object().put("cumulative_k", head.path("cumulative_k").asLong(1)));
        audit.set("gates", auditGates); audit.put("pass", true).put("decision", "SHADOW")
                .put("fail_closed_missing_inputs", true); audit = ownHashed(audit);
        ObjectNode refit = object(); refit.put("schema", "strategy-v5-statistical-development-refit/1")
                .put("version", 1).put("status", "SHADOW_PENDING_PROSPECTIVE")
                .put("activation_status", "SHADOW_ONLY")
                .put("source_artifact_sha256", source.path("content_sha256").asText())
                .put("validation_audit_sha256", audit.path("content_sha256").asText())
                .put("validation_exposure_head_sha256", head.path("content_sha256").asText())
                .put("exposure_head_sha256", head.path("content_sha256").asText())
                .put("selection_procedure_sha256", StrategyResearchAuthoritativeV5.hash("fixture-refit"))
                .put("selected_from_outer_fold_winners", false).put("excluded_from_retrospective_oos_audit", true);
        ObjectNode refitAsset = object().put("asset", "btc").put("status", "SELECTED_FOR_SHADOW")
                .put("source_phase", "FRESH_FULL_DEVELOPMENT_GA").put("selected_from_outer_fold_winners", false)
                .put("outer_fold_winner_inventory_used", false)
                .put("historical_wfo_rows_reclassified_as_development_at_cutoff", true);
        refitAsset.set("seeds", array().add(11).add(23).add(47)); refit.set("asset_refits", array().add(refitAsset));
        refit = ownHashed(refit);
        ArrayNode fixtureFolds = array(), outerRows = array();
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index); ArrayNode train = array();
            for (String candidateId : ids) if (!candidateId.equals(id)) train.add(candidateId);
            String foldId = "E2E-" + (index + 1);
            ObjectNode foldRow = object().put("schema", "strategy-v5-statistical-fold/1").put("version", 1)
                    .put("fold_id", foldId).put("status", "EVALUATED");
            foldRow.set("train_episode_ids", train); foldRow.set("test_episode_ids", array().add(id));
            foldRow.put("purge_ms", 30L * 86_400_000).put("embargo_ms", 7L * 86_400_000);
            foldRow.set("train", object().put("selection_phase", "TRAIN_ONLY"));
            ObjectNode foldPortfolio = object().put("pass", portfolio.path("pass").asBoolean(false))
                    .put("provenance", portfolio.path("provenance").asText())
                    .put("lineage_sha256", portfolio.path("lineage_sha256").asText())
                    .put("content_sha256", portfolio.path("content_sha256").asText());
            ObjectNode foldTest = object().put("weighted_recency", false)
                    .put("vector_inventory_sha256", vector.path("content_sha256").asText());
            foldTest.set("portfolio", foldPortfolio); foldRow.set("test", foldTest);
            foldRow.put("lineage_sha256", StrategyResearchAuthoritativeV5.hash("fixture-fold-" + index));
            fixtureFolds.add(ownHashed(foldRow));
            ObjectNode foldDecision = decision.deepCopy();
            foldDecision.set("selected_return_vector", array().add(selectedReturns.get(index).deepCopy()));
            foldDecision = ownHashed(foldDecision);
            ObjectNode outerRow = object().put("fold_id", foldId);
            ObjectNode outerDecisions = object(); outerDecisions.set("btc", foldDecision);
            outerRow.set("asset_decisions", outerDecisions);
            outerRow.set("vector", vector); outerRow.set("portfolio", portfolio); outerRows.add(outerRow);
        }
        ObjectNode run = object().put("schema", "strategy-v5-statistical-wfo/1").put("version", 1)
                .put("validation_exposure_head_sha256", head.path("content_sha256").asText())
                .put("validation_exposure_head_cumulative_k", head.path("cumulative_k").asLong())
                .put("exposure_head_sha256", head.path("content_sha256").asText())
                .put("cumulative_k", head.path("cumulative_k").asLong())
                .put("oos_artifact_sha256", finalArtifact.path("content_sha256").asText())
                .put("vector_inventory_sha256", vector.path("content_sha256").asText())
                .put("oos_weighting", "UNWEIGHTED").put("decision", "SHADOW").put("gate_pass", true);
        run.set("folds", fixtureFolds); run.put("fold_count", 8); run.set("asset_scope", finalScope);
        run.set("validation_exposure_head", head); run.set("oos_episode_ids", strings(ids));
        run.set("audit", audit); run.set("development_refit", refit);
        run.set("asset_decisions", outerRows);
        run.set("asset_decisions_final", array().add(decision)); run.set("portfolio_decision", portfolio);
        run = ownHashed(run);
        ObjectNode result = object(); result.set("run", run); result.set("exposureHead", head);
        result.set("artifact", finalArtifact); result.set("vectorInventory", vector); return result;
    }

    private static void assertSevenArtifactShadow(ObjectNode result, Path recordRoot) throws IOException {
        equal("COMPLETE", result.path("status").asText(), "physical research-run completes");
        equal("SHADOW", result.path("run").path("decision").asText(), "successful publication remains SHADOW");
        equal("COMPLETE", result.path("receipt").path("status").asText(), "physical run receipt completes");
        equal("SHADOW_ONLY: authoritative recomputation completed; activation is unavailable at this command boundary",
                result.path("limitation").asText(), "successful run is explicitly shadow-only");
        Set<String> expected = Set.of("genetic", "execution_fills", "selected_trades", "stresses",
                "portfolio", "final_oos_artifact", "final_oos_vector_inventory");
        Set<String> actual = new LinkedHashSet<>(); result.path("stage_artifacts").fieldNames().forEachRemaining(actual::add);
        equal(expected, actual, "all seven physical stage artifacts are published");
        equal(7, result.path("run").path("stage_artifacts").size(), "run binds seven stage hashes");
        equal(7, result.path("run").path("stage_artifact_refs").size(), "run binds seven physical references");
        for (String role : expected) {
            JsonNode stage = result.path("stage_artifacts").path(role); Path path = Path.of(stage.path("path").asText());
            truth(path.toAbsolutePath().normalize().startsWith(recordRoot.toAbsolutePath().normalize()),
                    role + " remains inside record custody");
            byte[] bytes = Files.readAllBytes(path); equal(stage.path("byte_sha256").asText(),
                    JsonHashes.sha256(bytes), role + " byte hash reopens");
            JsonNode value = JSON.readTree(bytes); equal(value.path("content_sha256").asText(),
                    StrategyResearchAuthoritativeV5.ownHash(value), role + " own hash reopens");
            JsonNode ref = result.path("run").path("stage_artifact_refs").path(role);
            equal(stage.path("byte_sha256").asText(), ref.path("byte_sha256").asText(),
                    role + " run reference binds exact bytes");
        }
        for (String gate : List.of("wfo", "stress", "portfolio", "all_required_stages")) {
            truth(result.path("run").path("gate_status").path(gate).asBoolean(false),
                    "successful physical run passes " + gate + " gate");
        }
        truth(result.path("stage_artifacts").path("execution_fills").path("value")
                .path("marks_bound").asBoolean(false), "execution fills bind exact physical marks");
        equal("NOT_APPLICABLE", result.path("stage_artifacts").path("execution_fills")
                .path("value").path("funding_status").asText(), "spot funding is exactly not applicable");
        truth(result.path("stage_artifacts").path("stresses").path("value").path("rows").size() > 0,
                "stress stage contains physical rows");
        truth(result.path("stage_artifacts").path("portfolio").path("value").path("rows").path(0)
                .path("pass").asBoolean(false), "physical portfolio gate passes");
        equal(4, result.path("receipt").path("details").path("publication_artifacts").size(),
                "receipt binds the four transaction publications");
        truth(result.path("receipt").path("details").path("bound_hashes").path("stress_sha256").isTextual(),
                "receipt binds physical stress output");
        truth(result.path("receipt").path("details").path("bound_hashes").path("portfolio_sha256").isTextual(),
                "receipt binds physical portfolio output");
        truth(!result.path("receipt").path("details").path("active").asBoolean(true),
                "authoritative publication never claims activation");
    }

    private static void datedSettlementResolutionMatchesNode() throws Exception {
        ObjectNode execution = object().put("asset", "btc").put("venue", "binance")
                .put("symbol", "BTCUSDT_260101").put("instrument", "BINANCE_USDM_DATED_FUTURE");
        ObjectNode expiryRow = execution.deepCopy(); expiryRow.put("expiry", "2026-01-01T00:00:00Z");
        ObjectNode expiry = object().put("content_sha256", repeat('a'));
        expiry.set("records", array().add(expiryRow));
        ObjectNode settlementRow = execution.deepCopy();
        settlementRow.put("expiry", "2026-01-01T00:00:00Z")
                .put("event_time", "2026-01-01T00:00:00Z")
                .put("settlement_time", "2026-01-01T00:00:00Z")
                .put("availability_time", "2026-01-01T00:05:00Z")
                .put("settlement_price", 91_250.5)
                .put("settlement_mark_source_sha256", repeat('c'))
                .put("source_byte_sha256", repeat('c'))
                .put("source_receipt_sha256", repeat('d'))
                .put("settlement_mark_event_id", "delivery-260101");
        ObjectNode settlement = object().put("content_sha256", repeat('b'))
                .put("source_receipt_sha256", repeat('d'));
        settlement.set("records", array().add(settlementRow));
        ObjectNode metadata = object(); metadata.set("expiry", expiry); metadata.set("settlement", settlement);
        ObjectNode label = object().put("resolution_time", "2026-01-01T01:00:00Z");
        ObjectNode options = object(); options.set("metadata", metadata); options.set("execution", execution);
        options.set("label", label);
        jsonEqual(node("m.resolveDatedSettlementForStress(input)", options),
                StrategyResearchAuthoritativeV5.resolveDatedSettlementForStress(options),
                "dated settlement resolution");
        ObjectNode numericEpoch = options.deepCopy();
        ((ObjectNode) numericEpoch.path("metadata").path("expiry").path("records").path(0))
                .put("expiry", 1_767_225_600_000L);
        ((ObjectNode) numericEpoch.path("metadata").path("settlement").path("records").path(0))
                .put("expiry", 1_767_225_600_000L);
        throwsContaining(() -> StrategyResearchAuthoritativeV5.resolveDatedSettlementForStress(numericEpoch),
                "one exact physical settlement record", "numeric epoch is not Date.parse evidence");
        truth(nodeFailure("m.resolveDatedSettlementForStress(input)", numericEpoch)
                        .contains("expiry stress lacks"),
                "Node oracle rejects the same numeric settlement date");

        ObjectNode nonDated = options.deepCopy();
        ((ObjectNode) nonDated.path("execution")).put("instrument", "BINANCE_USDM_PERPETUAL");
        assertDatedSettlementRejects(nonDated, "dated USD-M future", "non-dated execution instrument");
        ObjectNode sameReceipt = options.deepCopy();
        ((ObjectNode) sameReceipt.path("metadata").path("settlement")).put("content_sha256", repeat('a'));
        assertDatedSettlementRejects(sameReceipt, "separate physical", "expiry/settlement receipt splice");
        ObjectNode noExpiry = options.deepCopy();
        ((ObjectNode) noExpiry.path("metadata").path("expiry")).set("records", array());
        assertDatedSettlementRejects(noExpiry, "one exact physical expiry", "missing expiry record");
        ObjectNode duplicateExpiry = options.deepCopy();
        ((ArrayNode) duplicateExpiry.path("metadata").path("expiry").path("records"))
                .add(expiryRow.deepCopy());
        assertDatedSettlementRejects(duplicateExpiry, "one exact physical expiry", "duplicate expiry record");
        ObjectNode noSettlement = options.deepCopy();
        ((ObjectNode) noSettlement.path("metadata").path("settlement")).set("records", array());
        assertDatedSettlementRejects(noSettlement, "one exact physical settlement", "missing settlement record");
        ObjectNode duplicateSettlement = options.deepCopy();
        ((ArrayNode) duplicateSettlement.path("metadata").path("settlement").path("records"))
                .add(settlementRow.deepCopy());
        assertDatedSettlementRejects(duplicateSettlement, "one exact physical settlement", "duplicate settlement record");
        ObjectNode eventBeforeExpiry = options.deepCopy();
        ((ObjectNode) eventBeforeExpiry.path("metadata").path("settlement").path("records").path(0))
                .put("event_time", "2025-12-31T23:59:00Z").put("settlement_time", "2025-12-31T23:59:00Z");
        assertDatedSettlementRejects(eventBeforeExpiry, "PIT-bound settlement event", "settlement event before expiry");
        ObjectNode availabilityAfterResolution = options.deepCopy();
        ((ObjectNode) availabilityAfterResolution.path("metadata").path("settlement").path("records").path(0))
                .put("availability_time", "2026-01-01T01:01:00Z");
        assertDatedSettlementRejects(availabilityAfterResolution, "PIT-bound settlement event", "settlement availability after resolution");
        ObjectNode nonPositive = options.deepCopy();
        ((ObjectNode) nonPositive.path("metadata").path("settlement").path("records").path(0))
                .put("settlement_price", 0);
        assertDatedSettlementRejects(nonPositive, "physically bound settlement price", "non-positive settlement price");
        ObjectNode sourceSplice = options.deepCopy();
        ((ObjectNode) sourceSplice.path("metadata").path("settlement").path("records").path(0))
                .put("source_byte_sha256", repeat('e'));
        assertDatedSettlementRejects(sourceSplice, "physically bound settlement price", "settlement source byte splice");
    }

    private static void assertDatedSettlementRejects(ObjectNode input, String javaFragment,
                                                      String label) throws Exception {
        truth(nodeFailure("m.resolveDatedSettlementForStress(input)", input).length() > 0,
                "Node rejects " + label);
        throwsContaining(() -> StrategyResearchAuthoritativeV5.resolveDatedSettlementForStress(input),
                javaFragment, "Java rejects " + label);
    }

    private static void legacyFamilyMigrationBoundaryMatchesNode() throws Exception {
        Path root = temporary("legacy-family-boundary");
        ObjectNode clean = object().put("recordRoot", root.toString()).put("family", "direct-family-v5");
        clean.set("exposureHead", object().put("cumulative_k", 0));
        equal(node("m.assertLegacyFamilyMigrationBoundary(input)", clean).asBoolean(),
                StrategyResearchAuthoritativeV5.assertLegacyFamilyMigrationBoundary(clean),
                "legacy family boundary accepts no recoverable records");

        ObjectNode legacy = object().put("schema", "strategy-definition/2")
                .put("strategy_id", "direct-family-v5");
        writeJson(root.resolve("legacy-definition.json"), legacy);
        ObjectNode blocked = clean.deepCopy(); blocked.set("exposureHead", object().put("cumulative_k", 2));
        truth(nodeFailure("m.assertLegacyFamilyMigrationBoundary(input)", blocked)
                        .contains("explicit physical exposure-head migration"),
                "Node legacy family boundary requires migration");
        throwsContaining(() -> StrategyResearchAuthoritativeV5.assertLegacyFamilyMigrationBoundary(blocked),
                "explicit physical exposure-head migration", "Java legacy family boundary requires migration");
    }

    private static void authoritativePortfolioPolicyMatchesNode() throws Exception {
        ObjectNode plan = object(); plan.set("window", object().put("end_at", "2026-01-02T00:00:00.000Z"));
        ObjectNode precommit = object().put("content_sha256", repeat('a'));
        ObjectNode evaluator = object().set("execution_contract", object());
        ObjectNode experiment = object().put("content_sha256", repeat('b'))
                .set("acceptance_contract", object());
        ObjectNode valid = physicalPortfolioPolicy(plan, precommit, evaluator, experiment);
        ObjectNode nodeInput = valid.deepCopy();
        equal(node("m.validateAuthoritativePortfolioPolicy(input)", nodeInput).asBoolean(),
                StrategyResearchAuthoritativeV5.validateAuthoritativePortfolioPolicy(valid),
                "authoritative portfolio policy accepts frozen ordered floors");

        ObjectNode badStatus = valid.deepCopy(); badStatus.put("status", "DRAFT");
        // Schema validation rejects a non-FROZEN status before the semantic
        // validator reaches its status guard; both paths bind the same rule.
        assertPortfolioPolicyRejects(badStatus, "FROZEN", "draft policy");
        ObjectNode badEquity = valid.deepCopy(); badEquity.put("current_equity", 0);
        // The contract's exclusiveMinimum is the fail-closed boundary before
        // the semantic positive-equity message can be reached.
        assertPortfolioPolicyRejects(badEquity, "exclusive minimum", "non-positive equity");
        ObjectNode badChronology = valid.deepCopy(); badChronology.put("asOf", "2026-01-03T00:00:00.000Z");
        assertPortfolioPolicyRejects(badChronology, "asOf is after", "asOf after cutoff");
        ObjectNode badFloor = valid.deepCopy();
        ((ObjectNode) badFloor.path("limits")).put("equity_floor", 20_000);
        assertPortfolioPolicyRejects(badFloor, "equity floors are not ordered", "equity floor above current");
    }

    private static void assertPortfolioPolicyRejects(ObjectNode input, String javaFragment,
                                                       String label) throws Exception {
        truth(nodeFailure("m.validateAuthoritativePortfolioPolicy(input)", input)
                        .length() > 0, "Node rejects " + label);
        throwsContaining(() -> StrategyResearchAuthoritativeV5.validateAuthoritativePortfolioPolicy(input),
                javaFragment, "Java rejects " + label);
    }

    private static void failClosedProspectiveReceiptMatchesNode() throws Exception {
        Path javaRoot = temporary("prospective-java"); Path nodeRoot = temporary("prospective-node");
        ObjectNode javaOptions = object().put("record_root", javaRoot.toString());
        ObjectNode nodeOptions = object().put("record_root", nodeRoot.toString());
        JsonNode javaResult = StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("prospective-runner", javaOptions);
        ObjectNode request = object().put("command", "prospective-runner"); request.set("options", nodeOptions);
        JsonNode nodeResult = node("m.runAuthoritativeV5Cli(input.command,input.options)", request);
        equal("BLOCKED", javaResult.path("status").asText(), "prospective fail-closed status");
        jsonEqual(nodeResult.path("receipt"), javaResult.path("receipt"), "prospective blocked receipt");
    }

    private static void readinessDelegationMatchesNodeWithoutEvidence() throws Exception {
        Path javaRoot = temporary("readiness-java"); Path nodeRoot = temporary("readiness-node");
        String generatedAt = "2026-08-30T10:15:30Z";
        String nowAt = "2026-08-30T10:15:30Z";
        ObjectNode javaOptions = object().put("record_root", javaRoot.toString())
                .put("generated_at", generatedAt).put("now_at", nowAt);
        JsonNode javaResult = StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("readiness-audit", javaOptions);
        ObjectNode nodeOptions = object().put("record_root", nodeRoot.toString())
                .put("generated_at", generatedAt).put("now_at", nowAt);
        ObjectNode request = object().put("command", "readiness-audit"); request.set("options", nodeOptions);
        JsonNode nodeResult = node("m.runAuthoritativeV5Cli(input.command,input.options)", request);
        jsonEqual(nodeResult.path("audit"), javaResult.path("audit"), "readiness audit delegation");
        equal("BLOCKED", javaResult.path("receipt").path("status").asText(), "readiness missing evidence blocks");
    }

    private static void validateRejectsSymlinkAndHardlinkCustody() throws Exception {
        Path root = temporary("validate-custody");
        ObjectNode seed = object().put("command", "validate").put("status", "COMPLETE");
        seed.set("inputs", array()); seed.set("outputs", array()); seed.set("limitations", array());
        seed.set("details", object().put("mode", "SEED"));
        ObjectNode receipt = StrategyResearchAuthoritativeV5.makeCommandReceipt(seed);
        Path physical = root.resolve("receipt.json");
        Files.writeString(physical, pretty(receipt), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Path symlink = root.resolve("receipt-symlink.json"); Files.createSymbolicLink(symlink, physical);
        ObjectNode symlinkOptions = object().put("input", symlink.toString()).put("record_root", root.toString());
        throwsContaining(() -> StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("validate", symlinkOptions),
                "regular, singly-linked", "validate symlink rejection");
        Files.delete(symlink);
        Path hardlink = root.resolve("receipt-hardlink.json"); Files.createLink(hardlink, physical);
        ObjectNode hardlinkOptions = object().put("input", hardlink.toString()).put("record_root", root.toString());
        throwsContaining(() -> StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("validate", hardlinkOptions),
                "regular, singly-linked", "validate hardlink rejection");

        Path redirectedRoot = root.resolve("redirected-record-root");
        Path realRoot = root.resolve("real-record-root"); Files.createDirectory(realRoot);
        Files.createSymbolicLink(redirectedRoot, realRoot);
        ObjectNode redirected = object().put("record_root", redirectedRoot.toString());
        throwsContaining(() -> StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("prospective-runner", redirected),
                "physical directory", "output parent symlink rejection");
    }

    private static void indexRejectsTransactionControlFilesAndHidesLooseRuns() throws Exception {
        Path badRoot = temporary("index-bad-transaction"); Path transactions = badRoot.resolve("transactions");
        Files.createDirectory(transactions); Files.writeString(transactions.resolve("unexpected.json"), "{}\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        ObjectNode bad = object().put("root", badRoot.toString()).put("record_root", badRoot.toString());
        throwsContaining(() -> StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("index", bad),
                "unexpected JSON control file", "transaction control rejection");
        Path nodeBadRoot = temporary("index-bad-transaction-node");
        Files.createDirectory(nodeBadRoot.resolve("transactions"));
        Files.writeString(nodeBadRoot.resolve("transactions/unexpected.json"), "{}\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        ObjectNode nodeBadOptions = object().put("root", nodeBadRoot.toString())
                .put("record_root", nodeBadRoot.toString());
        ObjectNode nodeBadRequest = object().put("command", "index"); nodeBadRequest.set("options", nodeBadOptions);
        truth(nodeFailure("m.runAuthoritativeV5Cli(input.command,input.options)", nodeBadRequest)
                        .contains("unexpected JSON control file"),
                "Node oracle rejects the same transaction control injection");

        Path looseRoot = temporary("index-loose-run"); Path artifacts = looseRoot.resolve("artifacts");
        Files.createDirectory(artifacts);
        Files.writeString(artifacts.resolve("loose-run.json"),
                "{\"schema\":\"strategy-research-run/5\",\"attacker\":true}\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(artifacts.resolve("loose-wfo.json"),
                "{\"schema\":\"strategy-v5-statistical-wfo/1\",\"attacker\":true}\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        ObjectNode loose = object().put("root", looseRoot.toString()).put("record_root", looseRoot.toString());
        JsonNode result = StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("index", loose);
        equal(0, result.path("index").path("records").size(), "loose statistical publication is invisible");
        Path nodeLooseRoot = temporary("index-loose-run-node"); Path nodeArtifacts = nodeLooseRoot.resolve("artifacts");
        Files.createDirectory(nodeArtifacts);
        Files.writeString(nodeArtifacts.resolve("loose-run.json"),
                "{\"schema\":\"strategy-research-run/5\",\"attacker\":true}\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(nodeArtifacts.resolve("loose-wfo.json"),
                "{\"schema\":\"strategy-v5-statistical-wfo/1\",\"attacker\":true}\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        ObjectNode nodeLooseOptions = object().put("root", nodeLooseRoot.toString())
                .put("record_root", nodeLooseRoot.toString());
        ObjectNode nodeLooseRequest = object().put("command", "index"); nodeLooseRequest.set("options", nodeLooseOptions);
        JsonNode nodeLoose = node("m.runAuthoritativeV5Cli(input.command,input.options)", nodeLooseRequest);
        jsonEqual(nodeLoose.path("index"), result.path("index"), "loose-publication index differential");

        Path sparseRoot = temporary("index-sparse-legacy-definition");
        ObjectNode sparse = object().put("schema", "strategy-definition/2")
                .put("strategy_id", "sparse-index-contract").put("version", "v001")
                .put("created_at", "2026-08-30T00:00:00.000Z").put("stage", "CORE_PREMISE")
                .put("hypothesis_family", "sparse-index-contract");
        sparse.set("precommit", object().put("path", "precommit.json").put("sha256", repeat('a')));
        sparse.set("candidate_template", object());
        ObjectNode featureContract = object();
        featureContract.set("series", array().add(object().put("series_id", "btc-4h")
                .put("asset", "btc").put("asset_class", "crypto").put("timeframe", "4h")
                .put("context_only", false).put("point_in_time", true)));
        featureContract.set("inputs", array().add(object().put("input_id", "close")
                .put("availability", "BAR_CLOSE").put("point_in_time", true)
                .put("evidence_family", "PRICE").put("role", "TRADE")));
        sparse.set("feature_contract", featureContract);
        ObjectNode instruments = object(); instruments.set("universe", array().add("btc"));
        instruments.set("instruments", array().add("BINANCE_SPOT"));
        sparse.set("tradable_instrument_contract", instruments);
        sparse.set("evidence_policy", object().put("activation_allowed", false));
        writeJson(sparseRoot.resolve("definition.json"), sparse);
        ObjectNode sparseOptions = object().put("root", sparseRoot.toString())
                .put("record_root", sparseRoot.toString());
        JsonNode sparseIndex = StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("index", sparseOptions);
        equal(1, sparseIndex.path("index").path("records").size(),
                "sparse legacy definition remains indexable");
        JsonNode sparseRow = sparseIndex.path("index").path("records").path(0);
        truth(sparseRow.path("candidate_count").isNull() && sparseRow.path("metric_count").isNull()
                        && sparseRow.path("trade_count").isNull(),
                "sparse legacy definition preserves absent optional counts as null");
    }

    private static void mutableIndexCommitIsAtomicAcrossInjectedFaults() throws Exception {
        Path stageRoot = temporary("index-stage-fault"); Path stageOutput = stageRoot.resolve("index.json");
        ObjectNode stage = object().put("root", stageRoot.toString()).put("record_root", stageRoot.toString())
                .put("out", stageOutput.toString());
        throwsContaining(() -> StrategyResearchAuthoritativeV5.withWriteFaultHookForTest(point -> {
            if ("mutable-after-stage".equals(point)) throw new IllegalStateException("injected-stage-fault");
        }, () -> StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("index", stage)),
                "injected-stage-fault", "staged index fault");
        truth(!Files.exists(stageOutput, LinkOption.NOFOLLOW_LINKS), "stage fault cannot publish index");
        truth(noTemporaryIndex(stageRoot), "stage fault cleans private temporary bytes");

        Path commitRoot = temporary("index-commit-fault"); Path commitOutput = commitRoot.resolve("index.json");
        ObjectNode commit = object().put("root", commitRoot.toString()).put("record_root", commitRoot.toString())
                .put("out", commitOutput.toString());
        throwsContaining(() -> StrategyResearchAuthoritativeV5.withWriteFaultHookForTest(point -> {
            if ("mutable-after-commit".equals(point)) throw new IllegalStateException("injected-commit-fault");
        }, () -> StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("index", commit)),
                "injected-commit-fault", "committed index fault");
        JsonNode committed = JSON.readTree(Files.readAllBytes(commitOutput));
        equal(StrategyResearchAuthoritativeV5.ownHash(committed), committed.path("content_sha256").asText(),
                "post-commit index remains hash-valid");
        truth(noTemporaryIndex(commitRoot), "post-commit fault leaves no staging file");
    }

    private static void aliasesAndUnavailableResearchRemainFailClosed() throws Exception {
        Path root = temporary("alias-fail-closed");
        ObjectNode canonicalOptions = object().put("record_root", root.toString());
        JsonNode canonical = StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("research-init", canonicalOptions);
        JsonNode alias = StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("statistical-genesis", canonicalOptions);
        jsonEqual(canonical.path("receipt"), alias.path("receipt"), "statistical-genesis alias");
        ObjectNode runOptions = object().put("record_root", root.toString());
        JsonNode researchRun = StrategyResearchAuthoritativeV5.authoritativeResearchRun(runOptions);
        equal("BLOCKED", researchRun.path("status").asText(), "research-run missing data fail closed");
        equal("BLOCKED_MISSING_PHYSICAL_PREREQUISITES",
                researchRun.path("receipt").path("details").path("mode").asText(),
                "research-run distinguishes unavailable environment");
        truth(researchRun.path("receipt").path("limitations").size() >= 4,
                "research-run names every missing physical dependency");
        truth(!researchRun.path("receipt").path("limitations").toString().contains("lifecycle stress-token"),
                "missing environment is not mislabeled as an owner capability gap");

        Path invalid = writeJson(root.resolve("invalid-prerequisite.json"), canonical.path("receipt"));
        ObjectNode completeButInvalid = object().put("record_root", root.toString())
                .put("plan", invalid.toString()).put("parquet_manifest", invalid.toString())
                .put("parquet_root", root.toString()).put("artifact", invalid.toString())
                .put("evaluator_spec", invalid.toString()).put("precommit", invalid.toString())
                .put("experiment", invalid.toString()).put("gene_space", invalid.toString())
                .put("definition", invalid.toString()).put("predictor_registry", invalid.toString())
                .put("timeframe_requirements", invalid.toString()).put("metadata", invalid.toString())
                .put("opportunity_domain", invalid.toString()).put("envelope", invalid.toString())
                .put("hydration", invalid.toString()).put("hydration_root", root.toString())
                .put("exposure_head", invalid.toString()).put("checkpoint", invalid.toString())
                .put("cache_root", root.toString()).put("portfolio_policy", invalid.toString())
                .put("portfolio_mark_artifact", invalid.toString());
        throwsContaining(() -> StrategyResearchAuthoritativeV5.authoritativeResearchRun(completeButInvalid),
                "research-run plan schema is not one of",
                "research-run validates complete physical inputs before reporting an owner capability gap");
    }

    private static void frozenResearchContractsAreHashedAndFailClosed() throws Exception {
        ObjectNode artifact = object(); artifact.set("episodes",
                array().add(object().put("episode_id", "episode-1").put("asset", "btc")));
        ObjectNode precommit = object().put("content_sha256", repeat('a'));
        precommit.set("asset_scope", object().set("trade_assets", array().add("btc")));
        ObjectNode experiment = object().put("content_sha256", repeat('b'));
        experiment.set("chronology", object().put("bootstrap_iterations", 200)
                .set("seeds", array().add(11)));
        ArrayNode scenarios = array();
        for (String id : List.of("DOUBLED_COST", "DELAYED_ENTRY", "ADVERSE_COLLISION", "GAP",
                "LIQUIDITY", "CAPACITY", "OUTAGE", "FUNDING", "EXPIRY", "LIQUIDATION",
                "LEAVE_ONE_ASSET", "LEAVE_ONE_REGIME", "LEAVE_ONE_CONTEXT")) {
            scenarios.add(object().put("id", id).put("required", true).set("parameters", object()));
        }
        experiment.set("acceptance_contract", object().set("stress_scenarios", scenarios));
        ObjectNode evaluator = object().put("content_sha256", repeat('c'));

        Method assetScopeMethod = StrategyResearchAuthoritativeV5.class.getDeclaredMethod(
                "deriveFrozenAssetScope", ObjectNode.class, ObjectNode.class, ObjectNode.class);
        assetScopeMethod.setAccessible(true);
        ObjectNode scope = (ObjectNode) assetScopeMethod.invoke(null, artifact, precommit, experiment);
        equal("strategy-v5-statistical-asset-scope/1", scope.path("schema").asText(),
                "frozen asset scope schema");
        equal(scope.path("content_sha256").asText(), StrategyResearchAuthoritativeV5.ownHash(scope),
                "frozen asset scope own hash");
        jsonEqual(array().add("btc"), scope.path("trade_assets"), "frozen trade asset inventory");

        Method stressMethod = StrategyResearchAuthoritativeV5.class.getDeclaredMethod(
                "frozenStressContract", ObjectNode.class, ObjectNode.class, ObjectNode.class);
        stressMethod.setAccessible(true);
        ObjectNode stress = (ObjectNode) stressMethod.invoke(null, precommit, experiment, evaluator);
        equal(13, stress.path("scenarios").size(), "frozen stress inventory");
        equal(stress.path("content_sha256").asText(), StrategyResearchAuthoritativeV5.ownHash(stress),
                "frozen stress own hash");
        equal(200, stress.path("resampling").path("iterations").asInt(), "frozen resampling iterations");

        ObjectNode overlapping = precommit.deepCopy();
        ((ObjectNode) overlapping.path("asset_scope")).set("replication_assets", array().add("btc"));
        throwsContaining(() -> assetScopeMethod.invoke(null, artifact, overlapping, experiment),
                "asset scope overlaps trade_assets and replication_assets",
                "frozen asset scope rejects overlapping research roles");
        ObjectNode unknownStress = experiment.deepCopy();
        ((ObjectNode) unknownStress.path("acceptance_contract").path("stress_scenarios").path(0)
                .path("parameters")).put("caller_override", true);
        throwsContaining(() -> stressMethod.invoke(null, precommit, unknownStress, evaluator),
                "unknown parameters: caller_override", "frozen stress rejects unbound parameters");
    }

    private static boolean noTemporaryIndex(Path root) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "index.json.tmp-*")) {
            return !entries.iterator().hasNext();
        }
    }

    private static JsonNode node(String expression, JsonNode input) throws Exception {
        return javaOracle("tools/strategy-research-v5-authoritative.mjs", expression, input);
    }

    private static JsonNode nodeInGitWorkTree(String expression, JsonNode input, Path gitWorkTree)
            throws Exception {
        return javaOracle("tools/strategy-research-v5-authoritative.mjs", expression, input);
    }

    private static JsonNode nodeInDirectory(String expression, JsonNode input, Path directory) throws Exception {
        return javaOracle("tools/strategy-research-v5-authoritative.mjs", expression, input);
    }

    private static JsonNode nodeModule(String relativeModule, String expression, JsonNode input) throws Exception {
        return javaOracle(relativeModule, expression, input);
    }

    private static String nodeFailure(String expression, JsonNode input) throws Exception {
        try {
            node(expression, input);
            throw new AssertionError("Java oracle was expected to reject the payload");
        } catch (RuntimeException error) {
            return error.getMessage();
        }
    }

    private static JsonNode javaOracle(String module, String expression, JsonNode input) {
        if (module.endsWith("strategy-research-v5.mjs")
                && "m.makeCandidateSetV5(input)".equals(expression)) {
            return StrategyResearchV5.makeCandidateSetV5((ObjectNode) input);
        }
        return switch (expression) {
            case "Object.keys(m).sort()" -> JSON.valueToTree(Arrays.stream(
                            StrategyResearchAuthoritativeV5.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers())
                            && Modifier.isStatic(method.getModifiers()))
                    .map(Method::getName).distinct()
                    .collect(java.util.stream.Collectors.collectingAndThen(
                            java.util.stream.Collectors.toCollection(java.util.TreeSet::new), names -> {
                                names.add("AUTHORITATIVE_SCHEMA"); names.add("PIPELINE_V5"); return names;
                            })));
            case "m.AUTHORITATIVE_SCHEMA" -> JSON.getNodeFactory().textNode(
                    StrategyResearchAuthoritativeV5.AUTHORITATIVE_SCHEMA);
            case "m.PIPELINE_V5" -> JSON.valueToTree(StrategyResearchAuthoritativeV5.PIPELINE_V5);
            case "m.stable(input)" -> JSON.getNodeFactory().textNode(
                    StrategyResearchAuthoritativeV5.stable(input));
            case "m.hash(input)" -> JSON.getNodeFactory().textNode(
                    StrategyResearchAuthoritativeV5.hash(input));
            case "m.ownHash(input)" -> JSON.getNodeFactory().textNode(
                    StrategyResearchAuthoritativeV5.ownHash(input));
            case "m.hash(input.value)" -> JSON.getNodeFactory().textNode(
                    StrategyResearchAuthoritativeV5.hash(input.path("value").asText()));
            case "m.makeCommandReceipt(input)" -> StrategyResearchAuthoritativeV5.makeCommandReceipt((ObjectNode) input);
            case "m.canonicalHypothesisFamilyV5(input.precommit)" -> JSON.getNodeFactory().textNode(
                    StrategyResearchAuthoritativeV5.canonicalHypothesisFamilyV5((ObjectNode) input.path("precommit")));
            case "m.makeAuthoritativeExecutorIdentityV5(input)" ->
                    StrategyResearchAuthoritativeV5.makeAuthoritativeExecutorIdentityV5((ObjectNode) input);
            case "m.validateExactProductionEpisodeInventoriesV5(input)" -> JSON.getNodeFactory().booleanNode(
                    StrategyResearchAuthoritativeV5.validateExactProductionEpisodeInventoriesV5((ObjectNode) input));
            case "m.authoritativeMetadataBuild(input)" ->
                    StrategyResearchAuthoritativeV5.authoritativeMetadataBuild((ObjectNode) input);
            case "m.validateProductionResearchBindingsV5(input)" ->
                    StrategyResearchAuthoritativeV5.validateProductionResearchBindingsV5((ObjectNode) input);
            case "m.resolveDatedSettlementForStress(input)" ->
                    StrategyResearchAuthoritativeV5.resolveDatedSettlementForStress((ObjectNode) input);
            case "m.assertLegacyFamilyMigrationBoundary(input)" -> JSON.getNodeFactory().booleanNode(
                    StrategyResearchAuthoritativeV5.assertLegacyFamilyMigrationBoundary((ObjectNode) input));
            case "m.validateAuthoritativePortfolioPolicy(input)" -> JSON.getNodeFactory().booleanNode(
                    StrategyResearchAuthoritativeV5.validateAuthoritativePortfolioPolicy(input));
            case "m.runAuthoritativeV5Cli(input.command,input.options)" ->
                    StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli(
                            input.path("command").asText(), (ObjectNode) input.path("options"));
            default -> throw new IllegalArgumentException("unsupported Java oracle expression: " + expression);
        };
    }

    private static Path temporary(String prefix) throws IOException {
        Path privateTmp = Path.of("/private/tmp");
        Path base = Files.isDirectory(privateTmp, LinkOption.NOFOLLOW_LINKS) ? privateTmp : Path.of("/tmp");
        return Files.createTempDirectory(base, "auth-v5-" + prefix + "-").toAbsolutePath().normalize();
    }

    private static Path repositoryTemporary(String prefix) throws IOException {
        Path base = REPOSITORY.resolve("strategy-research/v5-data");
        Files.createDirectories(base);
        return Files.createTempDirectory(base, "auth-v5-" + prefix + "-").toAbsolutePath().normalize();
    }

    private static void initializeIgnoredGitWorkTree(Path root) throws Exception {
        Process process = new ProcessBuilder("git", "init", "--quiet", root.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError("temporary git init failed: " + output);
        Files.writeString(root.resolve(".gitignore"), "node-metadata\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static JsonNode metadataWithoutRootBinding(JsonNode metadata) {
        ObjectNode copy = ((ObjectNode) metadata).deepCopy();
        copy.fields().forEachRemaining(entry -> {
            if (entry.getValue().isObject()) {
                ((ObjectNode) entry.getValue()).remove(List.of("source_root_reference", "content_sha256"));
            }
        });
        return copy;
    }

    private static Path writeJson(Path path, JsonNode value) throws IOException {
        Files.writeString(path, pretty(value), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return path;
    }

    private static void writeJsonl(Path path, JsonNode values) throws IOException {
        StringBuilder body = new StringBuilder();
        for (JsonNode value : values) body.append(JSON.writeValueAsString(value)).append('\n');
        Files.writeString(path, body.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static List<ObjectNode> readJsonl(Path path) throws IOException {
        List<ObjectNode> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) rows.add((ObjectNode) JSON.readTree(line));
        }
        return rows;
    }

    private static void copyFixtureSourceChain(Path sourceRoot, Path targetRoot) throws IOException {
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.toList()) {
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue;
                Path target = targetRoot.resolve(sourceRoot.relativize(source).toString());
                Files.createDirectories(target.getParent());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    equal(JsonHashes.sha256(Files.readAllBytes(source)), JsonHashes.sha256(Files.readAllBytes(target)),
                            "fixture source-chain copy is immutable");
                } else Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static ObjectNode ownHashed(ObjectNode value) {
        value.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(value)); return value;
    }

    private static String isoDay(int day, int minute) {
        long value = Instant.parse("2026-01-01T00:00:00.000Z").toEpochMilli()
                + (day - 1L) * 86_400_000L + minute * 60_000L;
        return iso(value);
    }

    private static String iso(long millis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(millis));
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode result = array(); values.forEach(result::add); return result;
    }

    private static ArrayNode array(List<? extends JsonNode> values) {
        ArrayNode result = array(); values.forEach(result::add); return result;
    }

    private static String pretty(JsonNode value) { return value.toPrettyString() + "\n"; }
    private static String repeat(char value) { char[] chars = new char[64]; Arrays.fill(chars, value); return new String(chars); }
    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static ArrayNode array() { return JSON.createArrayNode(); }

    private static void jsonEqual(JsonNode expected, JsonNode actual, String label) {
        equal(StrategyResearchAuthoritativeV5.stable(expected),
                StrategyResearchAuthoritativeV5.stable(actual), label);
    }

    private static void truth(boolean condition, String label) {
        checks++; if (!condition) throw new AssertionError(label);
    }

    private static void equal(Object expected, Object actual, String label) {
        checks++; if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void throwsContaining(ThrowingRunnable action, String fragment, String label) {
        checks++;
        try { action.run(); }
        catch (Throwable error) {
            Throwable cursor = error;
            while (cursor != null) {
                if (String.valueOf(cursor.getMessage()).contains(fragment)) return;
                cursor = cursor.getCause();
            }
            throw new AssertionError(label + " wrong failure: " + error, error);
        }
        throw new AssertionError(label + " did not fail");
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
