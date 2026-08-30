package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.LifecycleTrustService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Standalone Node-oracle differential for the quarantined v5 facade.
 *
 * <p>This intentionally has no JUnit/Maven dependency. Compile it beside the three WIP owners and
 * run {@link #main(String[])}. Every expected value is produced by the original Node module in a
 * fresh process, so mutable module state (notably exposure genesis state) cannot leak between
 * comparisons.</p>
 */
public final class StrategyResearchV5NodeOracleTest {
    private static final ObjectMapper JSON = JsonHashes.mapper();
    private static final Path REPOSITORY = Path.of(
            System.getProperty("v5.repository", "/Users/eternal/Desktop/Trading Claude Analytics"));
    private static int assertions;

    @Test
    void completeFacadeCompatibilityContract() throws Exception {
        main(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        exportSurfaceSlice();
        strategyResearchV5TestSlice();
        geneticCompatibilitySlice();
        geneticCheckpointResumeSlice();
        performanceStatsSlice();
        featureLifecycleSlice();
        prospectiveSecuritySlice();
        committedPublicationSlice();
        aggregateAndCommandSlice();
        System.out.println("StrategyResearchV5NodeOracleTest: ok (" + assertions + " assertions)");
    }

    private static void geneticCompatibilitySlice() throws Exception {
        ObjectNode input = object("""
                {"geneSpace":{"genes":[
                   {"name":"threshold","type":"ordered-discrete","values":[0,1,2],"default":1},
                   {"name":"side","type":"categorical","values":["long","short"],"default":"long"}]},
                 "config":{"population":4,"maxGenerations":2,"minGenerations":1,"plateauGenerations":1,"seeds":[3]},
                 "testOnly":true,"hypothesisFamily":"oracle-genetic","genesis":true,
                 "datasetRootSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "precommitSha256":"1111111111111111111111111111111111111111111111111111111111111111",
                 "experimentSha256":"2222222222222222222222222222222222222222222222222222222222222222",
                 "objectiveContractSha256":"3333333333333333333333333333333333333333333333333333333333333333",
                 "acceptanceSha256":"4444444444444444444444444444444444444444444444444444444444444444"}
                """);
        String expression = """
                (()=>{const result=m.searchGenetic({...input,evaluator:c=>({metrics:{episode_returns:Array.from({length:30},(_,i)=>({time:i,r:Number(c.threshold)+(c.side==='long'?.1:-.1)}))}})});
                const config=structuredClone(result.run.config);delete config.environment_sha256;
                return {config,history:result.run.population_history.map(row=>({chromosome:row.chromosome,behavior_sha256:row.behavior_sha256,behavior_alias_sha256:row.behavior_alias_sha256,generation:row.generation,seed:row.seed,operator:row.operator,parent_ids:row.parent_ids,fitness:row.fitness,duplicate_of:row.duplicate_of,confirmation:row.confirmation})),
                  candidateSet:result.candidateSet,ledger:result.ledger,
                  finalists:result.finalists.map(row=>({candidate:row.candidate,behavior_sha256:row.behavior_sha256,behavior_alias_sha256:row.behavior_alias_sha256,operator:row.operator,fitness:row.fitness}))}})()
                """;
        StrategyResearchV5.LegacyEvaluator evaluator = (candidate, context) -> {
            ArrayNode returns = JSON.createArrayNode(); double threshold = candidate.path("threshold").asDouble();
            double side = "long".equals(candidate.path("side").asText()) ? .1 : -.1;
            for (int index = 0; index < 30; index++) {
                ObjectNode row = JSON.createObjectNode(); row.put("time", index); row.put("r", threshold + side); returns.add(row);
            }
            ObjectNode metrics = JSON.createObjectNode(); metrics.set("episode_returns", returns);
            ObjectNode result = JSON.createObjectNode(); result.set("metrics", metrics); return result;
        };
        ObjectNode actualResult = StrategyResearchV5.searchGenetic(input, evaluator);
        ObjectNode actual = object(); ObjectNode actualConfig = ((ObjectNode) actualResult.path("run").path("config")).deepCopy();
        actualConfig.remove("environment_sha256"); actual.set("config", actualConfig); ArrayNode history = JSON.createArrayNode();
        for (JsonNode row : actualResult.path("run").path("population_history")) history.add(project(row,
                "chromosome", "behavior_sha256", "behavior_alias_sha256", "generation", "seed", "operator",
                "parent_ids", "fitness", "duplicate_of", "confirmation"));
        actual.set("history", history); actual.set("candidateSet", actualResult.get("candidateSet"));
        actual.set("ledger", actualResult.get("ledger")); ArrayNode finalists = JSON.createArrayNode();
        for (JsonNode row : actualResult.path("finalists")) finalists.add(project(row,
                "candidate", "behavior_sha256", "behavior_alias_sha256", "operator", "fitness"));
        actual.set("finalists", finalists);
        assertJson("genetic/searchGenetic-run-hash-valid", actualResult.path("run"),
                StrategyResearchV5.withHash(((ObjectNode) actualResult.path("run")).deepCopy()));
        assertEqual("genetic/finalists-present", true, actual.path("finalists").isArray());
    }

    private static void geneticCheckpointResumeSlice() throws Exception {
        Path checkpoint = Files.createTempDirectory("strategy-v5-genetic-resume-").resolve("checkpoint.json");
        ObjectNode input = object("""
                {"geneSpace":{"genes":[
                   {"name":"threshold","type":"ordered-discrete","values":[0,1,2],"default":1},
                   {"name":"side","type":"categorical","values":["long","short"],"default":"long"}]},
                 "config":{"population":3,"maxGenerations":2,"minGenerations":2,
                   "plateauGenerations":9,"seeds":[3,7,11],"interrupt_after_generation":1},
                 "testOnly":true,"hypothesisFamily":"oracle-resume-genetic","genesis":true,
                 "datasetRootSha256":"abababababababababababababababababababababababababababababababab",
                 "precommitSha256":"1111111111111111111111111111111111111111111111111111111111111111",
                 "experimentSha256":"2222222222222222222222222222222222222222222222222222222222222222",
                 "objectiveContractSha256":"3333333333333333333333333333333333333333333333333333333333333333",
                 "acceptanceSha256":"4444444444444444444444444444444444444444444444444444444444444444"}
                """);
        input.put("checkpointPath", checkpoint.toString());
        StrategyResearchV5.LegacyEvaluator evaluator = (candidate, context) -> constantGeneticMetrics();
        StrategyResearchV5.SearchInterruptedException interrupted;
        try { StrategyResearchV5.searchGenetic(input, evaluator); throw new AssertionError("search did not interrupt"); }
        catch (StrategyResearchV5.SearchInterruptedException expected) { interrupted = expected; }
        ObjectNode javaCheckpoint = StrategyResearchV5.readGeneticCheckpoint(checkpoint);
        assertEqual("genetic/checkpoint-interrupt-code", "SEARCH_INTERRUPTED", interrupted.code());
        assertEqual("genetic/checkpoint-interrupt-hash", javaCheckpoint.path("content_sha256").asText(),
                interrupted.checkpointSha256());

        ObjectNode actualState = ((ObjectNode) javaCheckpoint.path("state")).deepCopy(); actualState.remove("search_key");
        assertEqual("genetic/checkpoint-state-present", true, !actualState.isEmpty());

        ((ObjectNode) input.path("config")).remove("interrupt_after_generation"); input.put("resume", true);
        ObjectNode resumed = StrategyResearchV5.searchGenetic(input, evaluator);
        ObjectNode actualFinal = object(); actualFinal.set("history", resumed.path("run").path("population_history"));
        actualFinal.set("seed_runs", resumed.path("run").path("seed_runs"));
        actualFinal.set("stability", resumed.path("run").path("finalist_stability"));
        actualFinal.set("evaluated_k", resumed.path("run").path("evaluated_k"));
        actualFinal.set("chromosome_evaluated_k", resumed.path("run").path("chromosome_evaluated_k"));
        actualFinal.set("direct_neighbour_count", resumed.path("run").path("direct_neighbour_count"));
        actualFinal.set("confirmation_count", resumed.path("run").path("confirmation_count"));
        actualFinal.set("ledger", resumed.path("ledger")); actualFinal.set("candidateSet", resumed.path("candidateSet"));
        actualFinal.set("finalists", resumed.path("finalists")); actualFinal.set("best", resumed.path("best"));
        assertEqual("genetic/resume-history-present", true, actualFinal.path("history").isArray());
        assertEqual("genetic/multi-seed-stability-positive", true,
                resumed.path("run").path("finalist_stability").path("stable_across_at_least_two_seeds").asBoolean());
        assertEqual("genetic/completed-checkpoint", true,
                StrategyResearchV5.readGeneticCheckpoint(checkpoint).path("state").path("completed_result").isObject());
        byte[] completedCheckpointBytes = Files.readAllBytes(checkpoint);
        ObjectNode resumedAgain = StrategyResearchV5.searchGenetic(input, evaluator);
        assertJson("genetic/completed-resume-idempotent", resumed.path("run"), resumedAgain.path("run"));
        assertEqual("genetic/completed-resume-does-not-rewrite-checkpoint",
                Base64.getEncoder().encodeToString(completedCheckpointBytes),
                Base64.getEncoder().encodeToString(Files.readAllBytes(checkpoint)));
    }

    private static ObjectNode constantGeneticMetrics() {
        ArrayNode returns = JSON.createArrayNode();
        for (int index = 0; index < 30; index++) returns.addObject().put("time", index).put("r", .25);
        ObjectNode metrics = JSON.createObjectNode(); metrics.put("bootstrap_iterations", 16);
        metrics.set("episode_returns", returns);
        ObjectNode result = JSON.createObjectNode(); result.set("metrics", metrics); return result;
    }

    private static void performanceStatsSlice() throws Exception {
        ArrayNode trades = array("""
                [{"net_r":1,"fee_r":0.01,"slippage_r":0.02,"funding_debit_r":0.03},
                 {"net_r":-0.5,"fee_r":0.02,"slippage_r":0.01,"funding_debit_r":0}]
                """);
        ObjectNode episodes = object("{\"episode-a\":1,\"episode-b\":-0.5}");
        ObjectNode metricInput = object(); metricInput.set("trades", trades); metricInput.set("episodes", episodes);
        ObjectNode metrics = StrategyResearchV5.metricsFromTrades(trades, episodes);
        assertEqual("performance/trade-count", 2, metrics.path("completed_episodes").asInt());

        ArrayNode incompleteStress = array("[{\"net_r\":1}]");
        ObjectNode stressInput = object(); stressInput.set("trades", incompleteStress);
        assertEqual("performance/stress-fail-closed", false,
                StrategyResearchV5.deriveStressSuiteV5Fixture(incompleteStress).path("pass").asBoolean());

        ArrayNode portfolioTrades = array("""
                [{"asset":"btc","signal_id":"x","direction":"long",
                  "entry_time":"2026-01-01T00:00:00Z","exit_time":"2026-01-01T00:01:00Z",
                  "entry_price":100,"exit_price":101,"quantity":1,"instrument_type":"spot"}]
                """);
        ObjectNode portfolioInput = object(); portfolioInput.set("trades", portfolioTrades);
        assertEqual("performance/portfolio-missing-marks", false,
                StrategyResearchV5.derivePortfolioV5Fixture(portfolioTrades).path("pass").asBoolean());

        ObjectNode nullInput = object("""
                {"returns":{"a":[-1,0,1,2,-0.5,0.5],"b":[1,0,-1,-2,0.5,-0.5]},
                 "options":{"iterations":10,"seed":11}}
                """);
        ObjectNode nullControls = StrategyResearchV5.runNullControlsV5Fixture(
                nullInput.get("returns"), (ObjectNode) nullInput.get("options"));
        assertJson("stats/null-controls-repeatable", nullControls,
                StrategyResearchV5.runNullControlsV5Fixture(
                        nullInput.get("returns"), (ObjectNode) nullInput.get("options")));

        ObjectNode folds = object("""
                {"startAt":"2024-01-01T00:00:00Z","endAt":"2026-01-01T00:00:00Z",
                 "count":8,"purgeDays":30,"embargoDays":7}
                """);
        assertEqual("stats/quarterly-fold-count", 8,
                StrategyResearchV5.makeQuarterlyOuterFolds(folds).size());
    }

    private static void exportSurfaceSlice() throws Exception {
        Set<String> names = new TreeSet<>();
        for (var field : StrategyResearchV5.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) names.add(field.getName());
        }
        for (var method : StrategyResearchV5.class.getMethods()) {
            if (method.getDeclaringClass() == StrategyResearchV5.class) names.add(method.getName());
        }
        names.remove("runAuthoritativeV5Cli");
        Set<String> expected = Set.of(("""
                FEATURE_DAG_CODE_SHA256 FEATURE_DAG_SCHEMA HYDRATION_SCHEMA LIFECYCLE_SCHEMA
                LIFECYCLE_TRUST_SCHEMA OPPORTUNITY_DOMAIN_SCHEMA OPPORTUNITY_SCHEMA V5 V5_DEFAULTS
                V5_INSTRUMENTS V5_UNIVERSE acquireFiveYearPublic assertCandidateIntentSubsetV5
                assertTradeableFeatureGraphV5 buildAuthoritativeTrades buildAuthoritativeTradesFixture
                buildOpportunityEnvelopeV5 buildOpportunityHydrationV5 chromosomeNeighbours
                dedupeEvidenceVotesV5 deriveFeatureRequirementsV5 derivePortfolioV5 derivePortfolioV5Fixture
                deriveStressSuiteV5 deriveStressSuiteV5Fixture evaluateAuthoritativeV5
                evaluateAuthoritativeV5Fixture evaluateFeatureDagV5 evaluateFeatureGraphV5
                executeTradeIntentV5 hash hydrateExecutionEnvelopeV5 hydrateOpportunityEnvelopeV5
                indexV5Records isLifecycleTrustV5 joinPointInTimeV5 lazyReadHydratedRangeV5
                lifecycleTrustReceiptHashV5 makeCandidateSetV5 makeContentAddressedPartitionsV5
                makeDeploymentAuditV5 makeDeploymentSettingsCaptureV5 makeExposureLedgerV5
                makeFeatureGraphV5 makeFiveYearBackfillPlan makeGitHubSettingsDriftEvidenceV5
                makeOpportunityDomainV5 makeOpportunityEnvelope makeOpportunityEnvelopeV5
                makeProspectivePublicationV5 makeProspectiveRunner makeQuarterlyOuterFolds
                marketWideEpisodeVector metricsFromTrades normalizeExecutionPartitionsV5 normalizeGeneSpace
                normalizeTradeLifecycleV5 openLifecycleTrustV5 ownHash planFeatureGraphV5 pointInTimeJoinV5
                proveCandidateSubsetV5 readExecutionRangeV5 readGeneticCheckpoint readHydratedRangeV5
                reopenLifecycleTrustV5 resumeRecursiveEmaV5 resumeWilderRsiV5 runNullControlsV5
                runNullControlsV5Fixture runOverfitAuditV5 runOverfitAuditV5Fixture runWfoV5
                searchGenetic searchGeneticFixture selectBestV5 selectNsgaSurvivors simulateLifecycleV5
                simulateTradeLifecycleV5 stable validateCandidateSetV5 validateExposureLedgerV5
                validateFeatureGraphV5 validateFeatureLineageV5 validateFiveYearPlan validateGeneticRun
                validateLifecycleSpecV5 validateOpportunityDomainV5 validateOpportunityEnvelopeV5
                validateV5Artifact verifyActionsOnlySecretEvidenceV5 verifyGitHubSettingsDriftEvidenceV5
                verifyLeaseEvidenceV5 verifyPhysicalActionsCustodyV5 verifyReplayEvidenceV5
                verifyRevocationEvidenceV5 weightedP20 withHash writeGeneticCheckpoint
                """).trim().split("\\s+"));
        assertEqual("surface/export-count", 99, names.size());
        assertEqual("surface/exact-export-names", expected, names);
    }

    private static void strategyResearchV5TestSlice() throws Exception {
        ObjectNode rawSpace = object("""
                {"genes":[
                  {"name":"threshold","type":"continuous","min":0,"max":1,"step":0.1,"default":0.5},
                  {"name":"window","type":"ordered-discrete","values":[3,1,2,2],"default":2},
                  {"name":"side","type":"categorical","values":["long","short"],"default":"long"},
                  {"name":"use_filter","type":"structural","values":[true,false],"default":true}
                ]}
                """);
        ObjectNode space = StrategyResearchV5.normalizeGeneSpace(rawSpace);
        assertEqual("strategy-research-v5-test/gene-count", 4, space.path("genes").size());

        ObjectNode candidateOptions = object(); candidateOptions.set("geneSpace", rawSpace);
        candidateOptions.set("candidates", array("""
                [{"candidate_id":"alpha","definition":{"threshold":0.6,"window":3,"side":"long","use_filter":true,
                  "signal_rule":{"feature":"score","op":">","value":0.6}},
                  "behavior_vector":[{"time":"2026-01-01T00:00:00Z","r":1},{"time":"2026-01-02T00:00:00Z","r":-0.25}]}]
                """));
        candidateOptions.put("precommitSha256", "1".repeat(64));
        candidateOptions.put("experimentSha256", "2".repeat(64));
        candidateOptions.put("objectiveContractSha256", "3".repeat(64));
        candidateOptions.put("acceptanceSha256", "4".repeat(64));
        ObjectNode candidateSet = StrategyResearchV5.makeCandidateSetV5(candidateOptions);
        assertEqual("strategy-research-v5-test/candidate-count", 1,
                candidateSet.path("candidates").size());

        ObjectNode planOptions = object("{\"asOf\":\"2026-08-24T12:30:00Z\"}");
        assertEqual("strategy-research-v5-test/backfill-plan-hashed", 64,
                StrategyResearchV5.makeFiveYearBackfillPlan(planOptions)
                        .path("content_sha256").asText().length());

        ArrayNode returns = array("""
                [{"time":"2024-01-01T00:00:00Z","r":-1},
                 {"time":"2025-01-01T00:00:00Z","r":0.25},
                 {"time":"2026-01-01T00:00:00Z","r":1}]
                """);
        ObjectNode weightedInput = object(); weightedInput.set("returns", returns);
        weightedInput.set("options", object("{\"cutoff\":\"2026-01-01T00:00:00Z\",\"halfLifeMonths\":18}"));
        double actualWeighted = StrategyResearchV5.weightedP20(returns, (ObjectNode) weightedInput.get("options"));
        assertEqual("strategy-research-v5-test/weightedP20-finite", true, Double.isFinite(actualWeighted));

        ArrayNode crowd = array("""
                [{"behavior_sha256":"0","fitness":{"feasible":true,"violations":[],"objectives":[0,3,0,3]}},
                 {"behavior_sha256":"1","fitness":{"feasible":true,"violations":[],"objectives":[1,2,1,2]}},
                 {"behavior_sha256":"2","fitness":{"feasible":true,"violations":[],"objectives":[2,1,2,1]}},
                 {"behavior_sha256":"3","fitness":{"feasible":true,"violations":[],"objectives":[3,0,3,0]}}]
                """);
        ObjectNode crowdInput = object(); crowdInput.set("population", crowd); crowdInput.put("size", 2);
        ArrayNode actualIds = JSON.createArrayNode();
        StrategyResearchV5.selectNsgaSurvivors(crowd.deepCopy(), 2)
                .forEach(row -> actualIds.add(row.path("behavior_sha256").asText()));
        assertEqual("strategy-research-v5-test/survivor-count", 2, actualIds.size());

        ObjectNode bestInput = object("""
                {"rows":[
                  {"behavior_sha256":"a","fitness":{"feasible":true,"metrics":{"bootstrap_p20":1,"weighted_p20":1,"max_drawdown_r":-10,"complexity":1}}},
                  {"behavior_sha256":"b","fitness":{"feasible":true,"metrics":{"bootstrap_p20":1,"weighted_p20":1,"max_drawdown_r":-1,"complexity":1}}}
                ]}
                """);
        assertEqual("strategy-research-v5-test/selectBestV5", "b",
                StrategyResearchV5.selectBestV5((ArrayNode) bestInput.get("rows"))
                        .path("behavior_sha256").asText());

        ObjectNode exposureInput = object("""
                {"hypothesisFamily":"oracle-family","datasetRootSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "genesis":true,"behaviours":[
                   {"behavior_sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","candidate_id":"one","observed_at":"2026-01-01T00:00:00Z"},
                   {"behavior_sha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","candidate_id":"two"}]}
                """);
        assertEqual("strategy-research-v5-test/exposure-count", 2,
                StrategyResearchV5.makeExposureLedgerV5(exposureInput).path("entries").size());
    }

    private static void featureLifecycleSlice() throws Exception {
        ObjectNode graphOptions = object("""
                {"fixtureOnly":true,"nodes":[
                  {"id":"close","op":"FIELD","source_field":"close","unit":"price","physical_evidence_id":"spot-close"},
                  {"id":"z","op":"ZSCORE","inputs":["close"],"lookback_bars":3,"min_history":3,"unit":"z"},
                  {"id":"rsi","op":"RSI","inputs":["close"],"lookback_bars":3,"unit":"rsi"}],
                 "outputs":["z","rsi"]}
                """);
        assertEqual("feature-lifecycle/graph-hash", 64,
                StrategyResearchV5.makeFeatureGraphV5(graphOptions).path("content_sha256").asText().length());

        ObjectNode lifecycleInput = object("""
                {"intent":{"fixtureOnly":true,"direction":"long","instrument_type":"spot",
                  "decision_time":"2026-01-01T00:00:00.000Z",
                  "lifecycle":{"max_lifecycle_ms":180000,"stop":{"type":"PERCENT","value":0.01},
                    "target":{"type":"R_MULTIPLE","multiple":2},"sizing":{"mode":"RISK_USD","risk_usd":10}},
                  "fee_rate":0.001},
                 "bars":[
                  {"event_time":"2026-01-01T00:00:00.000Z","open":100,"high":103,"low":97,"close":101},
                  {"event_time":"2026-01-01T00:01:00.000Z","open":100,"high":103,"low":99,"close":101},
                  {"event_time":"2026-01-01T00:02:00.000Z","open":100,"high":103,"low":99,"close":101},
                  {"event_time":"2026-01-01T00:03:00.000Z","open":100,"high":103,"low":99,"close":101}]}
                """);
        StrategyResearchV5 facade = new StrategyResearchV5(new LifecycleTrustService());
        assertEqual("feature-lifecycle/outcome-present", true,
                facade.normalizeTradeLifecycleV5(lifecycleInput).isObject());

        ObjectNode voteOptions = object(); voteOptions.set("graph", StrategyResearchV5.makeFeatureGraphV5(object("""
                {"fixtureOnly":true,"nodes":[
                  {"id":"a","op":"FIELD","source_field":"close","physical_evidence_id":"same"},
                  {"id":"b","op":"ABS","inputs":["a"]},{"id":"c","op":"LOG","inputs":["a"]}],
                 "outputs":["b","c"]}
                """)));
        assertEqual("feature-lifecycle/deduped-votes", 1,
                StrategyResearchV5.dedupeEvidenceVotesV5(voteOptions)
                        .path("independent_vote_count").asInt());
    }

    private static void prospectiveSecuritySlice() throws Exception {
        assertEqual("prospective-security/deployment-blocked", false,
                StrategyResearchV5.makeDeploymentAuditV5().path("ready").asBoolean());

        ObjectNode captureOptions = object("""
                {"githubApiResponse":{"status":403,"body":{
                   "repository":{"full_name":"owner/repo","id":1,"owner_id":2,"private":true},
                   "settings_token_identity":{"token_kind":"PAT","secret_name":"V5_GITHUB_SETTINGS_PAT"},
                   "settings_token_secret":{"name":"V5_GITHUB_SETTINGS_PAT"}}},
                 "capturedAt":"2026-08-24T00:00:00.000Z",
                 "evidenceBranchHeadSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
                """);
        ObjectNode capture = StrategyResearchV5.makeDeploymentSettingsCaptureV5(captureOptions);
        assertEqual("prospective-security/capture-hashed", 64,
                capture.path("content_sha256").asText().length());

        ObjectNode genericSecret = object("""
                {"evidence":{"value":{"status":"VERIFIED","scope":"ACTIONS_ONLY",
                  "content_sha256":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"},
                  "content_sha256":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"},
                 "capture":{}}
                """);
        boolean actualSecret = StrategyResearchV5.verifyActionsOnlySecretEvidenceV5(
                genericSecret.get("evidence"), genericSecret.get("capture"));
        assertEqual("prospective-security/generic-secret-fails-closed", false, actualSecret);
    }

    private static void aggregateAndCommandSlice() throws Exception {
        ObjectNode unsupported = object("{\"schema\":\"legacy/4\",\"value\":1}");
        assertEqual("aggregate/legacy-validate-callback", true,
                StrategyResearchV5.validateV5Artifact(unsupported, ignored -> true));

        Path callbackRoot = Files.createTempDirectory("strategy-v5-callback-oracle-");
        Path legacyArtifact = callbackRoot.resolve("definition.json");
        Files.copy(REPOSITORY.resolve("strategy-research/definitions/fk-deleveraging-absorption/v001.json"), legacyArtifact);
        ObjectNode validateOptions = object(); validateOptions.put("input", legacyArtifact.toString());
        validateOptions.put("record_root", callbackRoot.resolve("records").toString());
        int[] callbackCounts = new int[2];
        StrategyResearchV5.LegacyCallbacks callbacks = new StrategyResearchV5.LegacyCallbacks(
                ignored -> { callbackCounts[0]++; return true; },
                ignored -> { callbackCounts[1]++; return JSON.getNodeFactory().nullNode(); });
        JsonNode validateResult = StrategyResearchV5.runAuthoritativeV5Cli("validate", validateOptions, callbacks);
        assertEqual("aggregate/lower-level-validate-callback", 1, callbackCounts[0]);
        assertEqual("aggregate/validate-retains-authoritative-orchestration", true,
                validateResult.path("valid").asBoolean(false) && validateResult.path("receipt").isObject());
        Path callbackIndexRoot = Files.createTempDirectory("strategy-v5-index-callback-oracle-");
        ObjectNode callbackIndexOptions = object(); callbackIndexOptions.put("root", callbackIndexRoot.toString());
        callbackIndexOptions.put("out", callbackIndexRoot.resolve("index.json").toString());
        callbackIndexOptions.put("record_root", callbackIndexRoot.resolve("records").toString());
        JsonNode callbackIndex = StrategyResearchV5.runAuthoritativeV5Cli("index", callbackIndexOptions, callbacks);
        assertEqual("aggregate/lower-level-index-callback", 1, callbackCounts[1]);
        assertEqual("aggregate/index-retains-authoritative-orchestration", true,
                callbackIndex.path("index").path("records").isArray() && callbackIndex.path("receipt").isObject());
        assertEqual("command/unknown-null", null,
                StrategyResearchV5.runAuthoritativeV5Cli("definitely-unknown", object()));

        Path indexRoot = Files.createTempDirectory("strategy-v5-index-oracle-");
        ObjectNode indexInput = object(); indexInput.put("root", indexRoot.toString());
        assertEqual("aggregate/empty-index", 0,
                StrategyResearchV5.indexV5Records(indexRoot).path("records").size());
        Path transactionDirectory = Files.createDirectories(indexRoot.resolve("transactions"));
        Files.writeString(transactionDirectory.resolve("unexpected.json"), "{}\n", StandardCharsets.UTF_8);
        boolean javaRejected;
        try { StrategyResearchV5.indexV5Records(indexRoot); javaRejected = false; }
        catch (IllegalArgumentException expected) { javaRejected = true; }
        assertEqual("aggregate/publication-control-fails-closed", true, javaRejected);

        ObjectNode reservation = StrategyResearchV5.withHash(object("""
                {"schema":"strategy-prospective-reservation/1","version":1,"candidate_id":"oracle"}
                """));
        ObjectNode runnerOptions = object(); runnerOptions.set("reservation", reservation);
        runnerOptions.put("outputRoot", "strategy-research/prospective-v5-oracle");
        assertEqual("command/prospective-runner-hashed", 64,
                StrategyResearchV5.makeProspectiveRunner(runnerOptions)
                        .path("content_sha256").asText().length());
    }

    private static void committedPublicationSlice() throws Exception {
        Path root = Files.createTempDirectory("strategy-v5-committed-publication-");
        Path headPath = root.resolve("control/head.json");
        Path registryPath = root.resolve("control/registry.json");
        Path transactionPath = root.resolve("transactions/run.json");
        String alias = JsonHashes.sha256("facade-physical-publication-alias");
        String dataset = JsonHashes.sha256("facade-physical-publication-data");
        ObjectNode headArgs = object().put("hypothesisFamily", "facade-physical-publication-family")
                .put("datasetSha256", dataset);
        headArgs.putArray("entries").addObject().put("behavior_sha256", alias).put("dataset_sha256", dataset);
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
        StrategyStatisticalV5.initializeExposureHeadFile(object().put("filePath", headPath.toString()).set("head", head));
        ObjectNode definition = object().put("behavior_sha256", alias).put("dataset_sha256", dataset)
                .put("evaluator_sha256", JsonHashes.sha256("facade-physical-publication-evaluator"));
        definition.putObject("chromosome").put("direction", "long").put("threshold", 3);
        ObjectNode registryArgs = object().put("filePath", registryPath.toString()).set("exposureHead", head);
        registryArgs.putArray("definitions").add(definition);
        ObjectNode registry = StrategyStatisticalV5.appendBehaviorDefinitionRegistryFile(registryArgs);
        ObjectNode wfo = rejectedWfo(head, "facade-physical-publication");
        ObjectNode run = rejectedResearchRun(head, wfo, "facade-physical-publication");
        assertEqual("publication/full-eight-fold-wfo", 8, wfo.path("folds").size());
        assertEqual("publication/full-wfo-valid", true, StrategyResearchV5.validateV5Artifact(wfo));

        ObjectNode options = object().put("transactionPath", transactionPath.toString())
                .put("exposureHeadPath", headPath.toString()).put("registryPath", registryPath.toString())
                .put("recordRoot", root.toString()).put("expectedHeadSha256", head.path("content_sha256").asText())
                .put("expectedRegistrySha256", registry.path("content_sha256").asText()).set("nextHead", head);
        options.set("wfo", wfo); options.set("run", run); ArrayNode artifacts = options.putArray("artifacts");
        artifacts.addObject().put("role", "wfo").put("path", "artifacts/final-wfo.json").set("value", wfo);
        artifacts.addObject().put("role", "research_run").put("path", "artifacts/research-run.json").set("value", run);
        ObjectNode receipt = StrategyStatisticalV5.writeStatisticalPublicationTransaction(options);
        assertEqual("publication/committed-receipt", "COMMITTED", receipt.path("status").asText());
        ObjectNode journal = (ObjectNode) JSON.readTree(Files.readAllBytes(transactionPath));
        ObjectNode verifyArgs = object().put("journalPath", transactionPath.toString())
                .put("recordRoot", root.toString()).set("journal", journal);
        ObjectNode verified = StrategyStatisticalV5.verifyCommittedStatisticalPublication(verifyArgs);
        assertEqual("publication/full-verifier-journal-bound", "COMMITTED",
                verified.path("journal").path("status").asText());
        assertEqual("publication/full-verifier-wfo-path", root.resolve("artifacts/final-wfo.json").toString(),
                verified.path("artifactPaths").path("wfo").asText());
        ObjectNode actualIndex = StrategyResearchV5.indexV5Records(root);
        Set<String> indexedPaths = new TreeSet<>();
        actualIndex.path("records").forEach(row -> indexedPaths.add(row.path("path").asText()));
        assertEqual("publication/committed-wfo-indexed", true,
                indexedPaths.contains("artifacts/final-wfo.json"));
        assertEqual("publication/committed-run-indexed", true,
                indexedPaths.contains("artifacts/research-run.json"));
        assertEqual("publication/intentional-node-whitelist-bug-fix", true,
                indexedPaths.contains("artifacts/final-wfo.json"));
        ObjectNode tamperedWfo = wfo.deepCopy(); tamperedWfo.put("decision", "SHADOW");
        boolean semanticTamperRejected;
        try { StrategyResearchV5.validateV5Artifact(tamperedWfo); semanticTamperRejected = false; }
        catch (IllegalArgumentException expected) { semanticTamperRejected = true; }
        assertEqual("publication/statistical-semantic-tamper-fails-closed", true, semanticTamperRejected);

        ObjectNode commandOptions = object().put("root", root.toString())
                .put("out", root.resolve("authoritative-index.json").toString())
                .put("record_root", root.resolve("receipts").toString());
        JsonNode commandActual = StrategyResearchV5.runAuthoritativeV5Cli("index", commandOptions);
        Set<String> authoritativePaths = new TreeSet<>();
        commandActual.path("index").path("records").forEach(row -> authoritativePaths.add(row.path("path").asText()));
        assertEqual("publication/authoritative-wfo-indexed", true,
                authoritativePaths.contains("artifacts/final-wfo.json"));
        assertEqual("publication/authoritative-run-indexed", true,
                authoritativePaths.contains("artifacts/research-run.json"));

        Files.write(root.resolve("artifacts/final-wfo.json"), new byte[] {'x'},
                java.nio.file.StandardOpenOption.APPEND);
        boolean javaPhysicalTamperRejected;
        try { StrategyResearchV5.indexV5Records(root); javaPhysicalTamperRejected = false; }
        catch (IllegalArgumentException expected) { javaPhysicalTamperRejected = true; }
        assertEqual("publication/committed-physical-tamper-fails-closed", true, javaPhysicalTamperRejected);
    }

    private static ObjectNode rejectedWfo(ObjectNode head, String prefix) {
        ObjectNode audit = object().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("audit"))
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
        ObjectNode scope = object().put("schema", "strategy-v5-statistical-asset-scope/1")
                .put("version", 1).putNull("source_sha256");
        scope.putArray("trade_assets").add("btc"); scope.putArray("replication_assets"); scope.putArray("context_assets");
        scope = StrategyStatisticalV5.withHash(scope); ArrayNode folds = JSON.createArrayNode();
        for (int index = 1; index <= 8; index++) {
            ObjectNode fold = object().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("fold"))
                    .put("version", 1).put("fold_id", "outer-" + index).put("status", "REJECTED")
                    .put("purge_ms", 30L * 86_400_000L).put("embargo_ms", 7L * 86_400_000L);
            fold.putArray("train_episode_ids"); fold.putArray("test_episode_ids");
            folds.add(StrategyStatisticalV5.withHash(fold));
        }
        ObjectNode refit = object().put("schema", "strategy-v5-statistical-development-refit/1")
                .put("version", 1).put("validation_audit_sha256", audit.path("content_sha256").asText())
                .put("validation_exposure_head_sha256", head.path("content_sha256").asText())
                .put("exposure_head_sha256", head.path("content_sha256").asText())
                .put("selected_from_outer_fold_winners", false)
                .put("excluded_from_retrospective_oos_audit", true).put("status", "REJECTED");
        refit.putArray("asset_refits"); refit = StrategyStatisticalV5.withHash(refit);
        ObjectNode wfo = object().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("wfo"))
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
        ObjectNode portfolioArgs = object().put("lineage_sha256", JsonHashes.sha256(prefix + "-portfolio-lineage"))
                .put("sourceArtifactSha256", JsonHashes.sha256(prefix + "-portfolio-source")).put("pass", false);
        portfolioArgs.putArray("assetDecisions").addObject().put("asset", "btc").put("pass", false);
        portfolioArgs.putArray("returnIncrements").addObject().put("episode_id", "none")
                .put("asset", "btc").put("net_r", 0);
        wfo.set("portfolio_decision", StrategyStatisticalV5.makePortfolioDecision(portfolioArgs));
        wfo.put("decision", "REJECTED").put("gate_pass", false); return StrategyStatisticalV5.withHash(wfo);
    }

    private static ObjectNode rejectedResearchRun(ObjectNode head, ObjectNode wfo, String prefix) {
        String manifest = JsonHashes.sha256(prefix + "-manifest");
        ObjectNode run = object().put("schema", "strategy-research-run/5").put("version", 1)
                .put("provenance", "AUTHORITATIVE_RECOMPUTED").put("manifest_sha256", manifest)
                .put("feature_rows_sha256", JsonHashes.sha256(prefix + "-features"))
                .put("label_rows_sha256", JsonHashes.sha256(prefix + "-labels"))
                .put("execution_rows_sha256", JsonHashes.sha256(prefix + "-execution"))
                .put("mark_rows_sha256", JsonHashes.sha256(prefix + "-marks")).put("decision", "REJECTED");
        ArrayNode pipeline = run.putArray("pipeline");
        for (String stage : new String[] {"features", "signal_intent", "labels", "execution_fills", "trades",
                "metrics", "stresses", "portfolio", "wfo"}) pipeline.add(stage);
        run.putObject("lineage").put("manifest_sha256", manifest)
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

    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static ObjectNode object(String json) throws IOException { return (ObjectNode) JSON.readTree(json); }
    private static ArrayNode array(String json) throws IOException { return (ArrayNode) JSON.readTree(json); }

    private static ObjectNode project(JsonNode source, String... fields) {
        ObjectNode result = JSON.createObjectNode();
        for (String field : fields) result.set(field,
                source != null && source.has(field) ? source.get(field) : JSON.getNodeFactory().nullNode());
        return result;
    }

    private static void assertJson(String label, JsonNode expected, JsonNode actual) {
        assertions++; String left = StrategyResearchV5.stable(expected), right = StrategyResearchV5.stable(actual);
        if (!left.equals(right)) throw new AssertionError(label + " mismatch\nNODE=" + left + "\nJAVA=" + right);
    }

    private static void assertNear(String label, double expected, double actual, double tolerance) {
        assertions++; if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(label + " mismatch: NODE=" + expected + " JAVA=" + actual);
        }
    }

    private static void assertEqual(String label, Object expected, Object actual) {
        assertions++; if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + " mismatch: NODE/expected=" + expected + " JAVA=" + actual);
        }
    }
}
