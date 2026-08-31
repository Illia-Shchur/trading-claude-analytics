package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.research.v5.StrategyPortfolioV5;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.array;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyResearchV2OriginalContractTest {
    private static final String CREATED_AT = "2026-08-23T00:00:00.000Z";

    @TempDir Path temporary;

    @Test
    void premiseDefinitionExperimentCandidatesStatisticsAndStressPreserveOriginalGates() {
        ObjectNode precommit = precommit();
        assertThat(LegacyResearchV2.validatePrecommit(precommit)).isTrue();
        ObjectNode frozen = LegacyResearchV2.freezePrecommit(precommit);
        assertThat(frozen.path("status").asText()).isEqualTo("FROZEN");
        assertThat(frozen.path("content_sha256").asText())
                .isEqualTo(LegacyResearchV2.ownHash(frozen));
        ObjectNode noFalsifier = precommit.deepCopy();
        noFalsifier.remove("falsifier");
        assertThatThrownBy(() -> LegacyResearchV2.validatePrecommit(noFalsifier))
                .hasMessageContaining("falsifier");
        assertThatThrownBy(() -> LegacyResearchV2.validatePrecommit(
                precommit.deepCopy().put("score_threshold", 2)))
                .hasMessageContaining("composite score");
        ObjectNode nonCrypto = precommit.deepCopy();
        ((ObjectNode) nonCrypto.path("tradable_instrument_contract"))
                .set("instruments", array().add(object().put("asset", "spx")
                        .put("asset_class", "index").put("instrument_type", "spot")));
        assertThatThrownBy(() -> LegacyResearchV2.validatePrecommit(nonCrypto))
                .hasMessageMatching(".*(non-crypto|crypto).*?");
        ObjectNode missingContextId = precommit.deepCopy();
        ((ObjectNode) missingContextId.path("non_crypto_context_only").get(0))
                .remove("input_id");
        assertThatThrownBy(() -> LegacyResearchV2.validatePrecommit(missingContextId))
                .hasMessageContaining("input_id");
        ObjectNode unsafePit = precommit.deepCopy();
        ((ObjectNode) unsafePit.path("required_inputs").get(1)
                .path("point_in_time")).put("status", "UNKNOWN");
        assertThatThrownBy(() -> LegacyResearchV2.validatePrecommit(unsafePit))
                .hasMessageContaining("PIT-safe");

        ObjectNode featureContract = featureContract();
        ObjectNode definitionOptions = object().put("strategy_id", "v2-fixture");
        definitionOptions.set("precommit", frozen);
        definitionOptions.set("candidate_template", object().put("id_template", "baseline-{n}")
                .put("threshold", 1).set("instrument", cryptoSpot("btc")));
        definitionOptions.set("feature_contract", featureContract);
        ObjectNode definition = LegacyResearchV2.makeV2Definition(definitionOptions);
        assertThat(LegacyResearchV2.validateDefinitionV2(definition, frozen)).isTrue();
        ObjectNode badMarket = definition.deepCopy();
        ArrayNode badSeries = array().add(
                ((ObjectNode) featureContract.path("series").get(1)).deepCopy()
                        .put("context_only", false));
        ((ObjectNode) badMarket.path("feature_contract")).set("series", badSeries);
        assertThatThrownBy(() -> LegacyResearchV2.validateDefinitionV2(badMarket))
                .hasMessageContaining("non-crypto validation markets");
        ObjectNode badStage = definition.deepCopy().put("stage", "RISK_LIFECYCLE");
        badStage.set("parent_evidence", object().put("stage", "CORE_PREMISE")
                .put("run_id", "run").put("sha256", "x"));
        assertThatThrownBy(() -> LegacyResearchV2.validateDefinitionV2(badStage))
                .hasMessageContaining("stage order");

        ArrayNode overlapping = array()
                .add(object().put("input_id", "context-second")
                        .put("evidence_family", "flow").put("role", "CONTEXT"))
                .add(object().put("input_id", "setup-first")
                        .put("evidence_family", "flow").put("role", "SETUP"));
        assertThatThrownBy(() -> LegacyResearchV2.validateFeatureIndependence(overlapping))
                .hasMessageContaining("overlap");
        ((ObjectNode) overlapping.get(0)).set("overlap_disclosure",
                object().put("explicit", true).put("blocks_promotion", true));
        assertThat(LegacyResearchV2.validateFeatureIndependence(overlapping)).isTrue();

        ObjectNode stressSuite = stressSuite();
        ObjectNode acceptance = acceptance(stressSuite);
        ObjectNode experiment = experiment(definition, acceptance);
        assertThat(LegacyResearchV2.validateExperimentV2(experiment, definition)).isTrue();
        ObjectNode noPhase = experiment.deepCopy();
        noPhase.remove("evidence_phase");
        assertThatThrownBy(() -> LegacyResearchV2.validateExperimentV2(noPhase, definition))
                .hasMessageContaining("evidence_phase");
        ObjectNode forbiddenAsset = experiment.deepCopy();
        forbiddenAsset.set("required_assets", array().add("spx"));
        assertThatThrownBy(() -> LegacyResearchV2.validateExperimentV2(
                forbiddenAsset, definition)).hasMessageMatching(".*(not in the crypto|non-crypto).*?");
        ObjectNode noMaxStatistic = experiment.deepCopy();
        ((ObjectNode) noMaxStatistic.path("acceptance")).set("robust_stats", object());
        assertThatThrownBy(() -> LegacyResearchV2.validateExperimentV2(
                noMaxStatistic, definition)).hasMessageContaining("max_statistic");

        ObjectNode designOptions = object();
        designOptions.set("definition", definition);
        designOptions.set("experiment", experiment);
        ObjectNode candidateSet = LegacyResearchV2.designCandidates(designOptions);
        assertThat(candidateSet.path("declared_k").asInt()).isEqualTo(3);
        assertThat(candidateSet.path("effective_k").asInt()).isEqualTo(2);
        assertThat(LegacyResearchV2.validateCandidateSetV2(candidateSet, experiment)).isTrue();
        assertThat(candidateSet.path("candidates")).allSatisfy(candidate ->
                assertThat(candidate.path("definition").has("id_template")).isFalse());
        JsonNode thresholdOne = findBy(candidateSet.path("candidates"), "threshold", 1);

        ObjectNode chainDefinition = definition.deepCopy();
        ((ObjectNode) chainDefinition.path("candidate_template")).put("threshold", 1);
        chainDefinition.remove("content_sha256");
        ObjectNode chainExperiment = experiment.deepCopy();
        ((ObjectNode) chainExperiment.path("definition"))
                .put("sha256", LegacyResearchV2.hash(chainDefinition));
        chainExperiment.set("grid", object().set("threshold", array().add(1).add(2).add(3)));
        chainExperiment.remove("content_sha256");
        ObjectNode chainDesign = object();
        chainDesign.set("definition", chainDefinition);
        chainDesign.set("experiment", chainExperiment);
        ObjectNode chainSet = LegacyResearchV2.designCandidates(chainDesign);
        ArrayNode chainMetrics = array();
        for (JsonNode candidate : chainSet.path("candidates")) {
            chainMetrics.add(object()
                    .put("candidate_id", candidate.path("candidate_id").asText())
                    .put("expectancy_r", 0.1).put("max_drawdown_pct", 4));
        }
        JsonNode chainTarget = findBy(chainSet.path("candidates"), "threshold", 1);
        ObjectNode plateauOptions = object().put("candidate_id",
                chainTarget.path("candidate_id").asText());
        plateauOptions.set("candidates", chainSet.path("candidates"));
        plateauOptions.set("grid", chainExperiment.path("grid"));
        plateauOptions.set("metrics", chainMetrics);
        ObjectNode plateau = LegacyResearchV2.plateauDiagnostics(plateauOptions);
        assertThat(plateau.path("neighbor_count").asInt()).isOne();
        assertThat(plateau.path("connected_profitable_plateau_size").asInt()).isEqualTo(3);
        assertThat(LegacyResearchV2.searchAdjustedExpectancyHeuristic(0.2, 100, 4))
                .isLessThan(0.2);

        ObjectNode ablationOptions = object();
        ablationOptions.set("base_candidate", thresholdOne.path("definition"));
        ablationOptions.set("context_inputs", array().add(macroInput())
                .add(object().put("input_id", "fear-context")
                        .put("evidence_family", "sentiment").put("role", "CONTEXT")));
        ObjectNode ablations = LegacyResearchV2.designContextAblations(ablationOptions);
        assertThat(ablations.path("add_one_context")).hasSize(2);
        assertThat(ablations.path("leave_one_context_out")).hasSize(2);
        assertThat(ablations.path("add_one_context")).extracting(
                row -> row.path("context_input_ids").path(0).asText())
                .containsExactly("fear-context", "real-yield-context");

        ArrayNode positive = episodeRows("btc", 1, 4);
        ArrayNode zeros = episodeRows("eth", 0, 4);
        ArrayNode combined = positive.deepCopy();
        combined.addAll(zeros);
        assertThat(LegacyResearchV2.deterministicBlocks(combined)).hasSize(4);
        ObjectNode bootstrap = LegacyResearchV2.blockBootstrapExpectancy(
                positive, object().put("iterations", 50).put("seed", 7));
        assertThat(bootstrap.path("effective_independent_episode_count").asInt()).isEqualTo(4);
        assertThat(bootstrap.path("p20").asDouble()).isEqualTo(1);
        ArrayNode returnsByCandidate = array()
                .add(object().put("candidate_id", "positive").set("rows", positive))
                .add(object().put("candidate_id", "null").set("rows", zeros));
        ArrayNode reversed = array().add(returnsByCandidate.get(1)).add(returnsByCandidate.get(0));
        ObjectNode reality = LegacyResearchV2.candidateSetMaxStatisticPValue(
                returnsByCandidate, object().put("iterations", 200).put("seed", 4));
        ObjectNode reverseReality = LegacyResearchV2.candidateSetMaxStatisticPValue(
                reversed, object().put("iterations", 200).put("seed", 4));
        assertThat(reality.path("statistic").asText()).isEqualTo("candidate-set-max-statistic");
        assertThat(reality.path("p_value").asDouble()).isLessThan(0.2);
        assertThat(reality.path("p_value").asDouble())
                .isEqualTo(reverseReality.path("p_value").asDouble());
        assertThat(reality.path("aligned_episode_keys_sha256").asText())
                .isEqualTo(reverseReality.path("aligned_episode_keys_sha256").asText());
        assertThat(reality.path("assumptions").asText()).contains("shared sequence");
        ObjectNode robustPass = object().put("max_statistic_p_value", 0.05)
                .put("bootstrap_p20_expectancy_r", 0.1)
                .put("effective_independent_episode_count", 8);
        assertThat(LegacyResearchV2.validateRobustStats(
                robustPass, acceptance.path("robust_stats")).path("pass").asBoolean()).isTrue();
        assertThat(LegacyResearchV2.validateRobustStats(
                object(), acceptance.path("robust_stats")).path("pass").asBoolean()).isFalse();

        ArrayNode stressTrades = stressTrades(positive);
        assertThat(LegacyResearchV2.validateStressSuite(stressSuite)).isTrue();
        ObjectNode stressResult = LegacyResearchV2.runStressSuite(stressTrades, stressSuite);
        assertThat(stressResult.path("pass").asBoolean()).isTrue();
        assertThat(stressResult.path("scenarios")).allSatisfy(row ->
                assertThat(row.path("modeled").asText()).contains("no order-book simulation"));
        ArrayNode missingFees = stressTrades.deepCopy();
        missingFees.forEach(row -> ((ObjectNode) row).remove("fee_r"));
        ObjectNode missingCost = LegacyResearchV2.runStressSuite(missingFees, stressSuite);
        assertThat(missingCost.path("pass").asBoolean()).isFalse();
        assertThat(findScenario(missingCost, "fee_slippage")
                .path("missing_model_inputs")).hasSize(4);
        ObjectNode incompleteSuite = object();
        incompleteSuite.set("required_scenarios", array().add(object()
                .put("id", "fee_slippage").put("multiplier", 2)
                .put("minimum_expectancy_r", 0).put("minimum_observations", 1)));
        assertThatThrownBy(() -> LegacyResearchV2.validateStressSuite(incompleteSuite))
                .hasMessageContaining("missing required scenario");
    }

    @Test
    void portfolioProspectiveAndRunDecisionsRemainFailClosed() {
        ObjectNode portfolioAcceptance = portfolioAcceptance();
        ArrayNode signals = portfolioSignals();
        ObjectNode policy = object().put("initial_equity", 10_000)
                .put("total_concurrency", 3).put("per_asset_concurrency", 1)
                .put("gross_exposure_cap", 2_000).put("net_exposure_cap", 2_000)
                .put("collateral_cap", 2_000).put("leverage_cap", 3);
        policy.set("acceptance", portfolioAcceptance);
        ObjectNode portfolio = StrategyPortfolioV5.simulateCryptoPortfolio(signals, policy);
        assertThat(portfolio.path("accepted_signals")).extracting(
                row -> row.path("signal_id").asText())
                .containsExactly("btc-first", "eth-overlap", "btc-after-release");
        assertThat(portfolio.path("rejected_signals")).anySatisfy(row ->
                assertThat(row.path("reason").asText()).isEqualTo("LONG_SHORT_CONFLICT"));
        assertThat(portfolio.path("rejected_signals")).anySatisfy(row ->
                assertThat(row.path("reason").asText())
                        .isEqualTo("NON_CRYPTO_OR_INVALID_INSTRUMENT"));
        assertThat(portfolio.path("exposure").path("peak").path("concurrency").asInt())
                .isEqualTo(2);
        assertThat(portfolio.path("portfolio_equity").asDouble()).isEqualTo(10_000);
        assertThat(portfolio.path("net_pnl").asDouble()).isZero();
        assertThat(portfolio.path("max_drawdown_pct").asDouble()).isPositive();
        assertThat(portfolio.path("drawdown_basis").asText())
                .contains("realized close-to-close");
        assertThat(portfolio.path("exposure").path("ending").path("gross").asDouble()).isZero();
        assertThat(portfolio.path("pass").asBoolean()).isTrue();
        assertThat(portfolio.path("activation").asText()).isEqualTo("RESEARCH_ONLY");
        assertThat(StrategyPortfolioV5.validatePortfolioInstrument(object()
                .put("asset", "sol").put("asset_class", "crypto")
                .put("instrument_type", "dated_future").put("venue", "venue")
                .put("collateral", "usdt"))).isTrue();
        assertThatThrownBy(() -> StrategyPortfolioV5.validatePortfolioInstrument(object()
                .put("asset", "gold").put("asset_class", "commodity")
                .put("instrument_type", "future").put("venue", "venue")
                .put("collateral", "usd"))).hasMessageContaining("crypto");

        ObjectNode timeframe = object();
        timeframe.set("higher_timeframe", object().put("completed_bar_only", true));
        timeframe.set("setup_timeframe", object().put("completed_bar_only", true));
        timeframe.set("lower_timeframe", object().put("completed_bar_only", true)
                .put("search_enabled", false));
        assertThat(LegacyResearchV2.validateMultiTimeframeContract(timeframe)).isTrue();
        ((ObjectNode) timeframe.path("lower_timeframe")).put("search_enabled", true);
        assertThatThrownBy(() -> LegacyResearchV2.validateMultiTimeframeContract(timeframe))
                .hasMessageContaining("silently enlarge");
        ObjectNode join = object();
        join.set("decisions", array().add(object().put("asset", "btc")
                .put("decision_time", "2026-01-02T00:00:00Z")));
        join.set("higher", array()
                .add(object().put("asset", "btc")
                        .put("availability_time", "2026-01-01T00:00:00Z").put("value", 1))
                .add(object().put("asset", "btc")
                        .put("availability_time", "2026-01-03T00:00:00Z").put("value", 2)));
        join.set("setup", array());
        assertThat(LegacyResearchV2.joinCompletedBarAsOf(join).path(0)
                .path("higher_timeframe").path("value").asInt()).isOne();

        ObjectNode profile = object().put("minimum_trades", 3).put("max_loss_run", 2);
        profile.set("frequency", object().put("min", 1).put("max", 10));
        profile.set("win_rate", object().put("min", 0).put("max", 1));
        profile.set("expectancy_r", object().put("min", -1).put("max", 1));
        assertThat(LegacyResearchV2.compareProspectiveExpectation(profile,
                object().set("trades", prospectiveTrades(1))).path("status").asText())
                .isEqualTo("SHADOW");
        ObjectNode three = LegacyResearchV2.compareProspectiveExpectation(profile,
                object().set("trades", prospectiveTrades(1, -0.5, 0.5)));
        assertThat(three.path("status").asText()).isEqualTo("CANDIDATE_REVIEW");
        assertThat(three.path("activation").asText()).isEqualTo("NEVER_ACTIVE");
        ObjectNode losses = LegacyResearchV2.compareProspectiveExpectation(profile,
                object().set("trades", prospectiveTrades(-1, -1, -1)));
        assertThat(losses.path("status").asText()).isEqualTo("REJECTED");
        assertThat(losses.path("reasons")).extracting(JsonNode::asText)
                .contains("LOSS_RUN_OUT_OF_RANGE");
        ObjectNode monthly = profile.deepCopy().put("minimum_trades", 1);
        monthly.set("frequency", object().put("min", 0.5).put("max", 2)
                .put("unit", "per month"));
        assertThat(LegacyResearchV2.compareProspectiveExpectation(monthly,
                object().set("trades", prospectiveTrades(1))).path("status").asText())
                .isEqualTo("REJECTED");
        ObjectNode monthlyEvidence = object()
                .put("monitoring_start", "2026-01-01T00:00:00Z")
                .put("monitoring_end", "2026-02-01T00:00:00Z");
        monthlyEvidence.set("trades", prospectiveTrades(1));
        ObjectNode normalized = LegacyResearchV2.compareProspectiveExpectation(
                monthly, monthlyEvidence);
        assertThat(normalized.path("status").asText()).isEqualTo("CANDIDATE_REVIEW");
        assertThat(normalized.path("frequency").path("actual").asDouble())
                .isBetween(0.9, 1.1);
        ObjectNode preFreeze = monthlyEvidence.deepCopy()
                .put("prospective_start", "2026-01-16T00:00:00Z");
        ObjectNode preFreezeResult = LegacyResearchV2.compareProspectiveExpectation(
                monthly, preFreeze);
        assertThat(preFreezeResult.path("status").asText()).isEqualTo("REJECTED");
        assertThat(preFreezeResult.path("reasons")).extracting(JsonNode::asText)
                .contains("PROSPECTIVE_PRE_START_EVIDENCE");

        ObjectNode frozen = LegacyResearchV2.freezePrecommit(precommit());
        ObjectNode feature = featureContract();
        ObjectNode definitionOptions = object().put("strategy_id", "v2-fixture");
        definitionOptions.set("precommit", frozen);
        definitionOptions.set("candidate_template", object().put("id_template", "baseline-{n}")
                .put("threshold", 1).set("instrument", cryptoSpot("btc")));
        definitionOptions.set("feature_contract", feature);
        ObjectNode definition = LegacyResearchV2.makeV2Definition(definitionOptions);
        ObjectNode acceptance = acceptance(stressSuite());
        ObjectNode experiment = experiment(definition, acceptance);
        ObjectNode noSearch = experiment.deepCopy().put("ablation_role", "NO_SELECTION_SEARCH");
        noSearch.set("grid", object());
        ((ObjectNode) noSearch.path("acceptance")).set("plateau", object());
        noSearch.remove("content_sha256");
        ObjectNode design = object();
        design.set("definition", definition);
        design.set("experiment", noSearch);
        ObjectNode candidateSet = LegacyResearchV2.designCandidates(design);
        String selected = candidateSet.path("candidates").path(0)
                .path("candidate_id").asText();
        ArrayNode metrics = array().add(object().put("asset", "btc")
                .put("candidate_id", selected).put("selected", true)
                .put("max_statistic_p_value", 0.05)
                .put("bootstrap_p20_expectancy_r", 0.1)
                .put("effective_independent_episode_count", 8));
        ObjectNode stress = LegacyResearchV2.runStressSuite(
                stressTrades(episodeRows("btc", 1, 4)), stressSuite());
        ObjectNode runOptions = object();
        runOptions.set("precommit", frozen);
        runOptions.set("definition", definition);
        runOptions.set("experiment", noSearch);
        runOptions.set("candidateSet", candidateSet);
        runOptions.set("metrics", metrics);
        runOptions.set("trades", stressTrades(episodeRows("btc", 1, 4)));
        runOptions.set("portfolio", portfolio);
        runOptions.set("stress", stress);
        ObjectNode run = LegacyResearchV2.makeV2Run(runOptions);
        assertThat(run.path("decisions").path("per_asset").path(0)
                .path("status").asText()).isEqualTo("SHADOW");
        assertThat(run.path("decisions").path("portfolio").path("status").asText())
                .isEqualTo("SHADOW");
        assertThat(run.path("activation").path("authorized").asBoolean()).isFalse();
        assertThat(LegacyResearchV2.validateV2Document(run)).isTrue();
        assertThatThrownBy(() -> LegacyResearchV2.validateV2Document(
                run.deepCopy().put("evidence_phase", "SEALED_CONFIRMATION")))
                .hasMessageContaining("hash mismatch");

        ObjectNode searchedDesign = object();
        searchedDesign.set("definition", definition);
        searchedDesign.set("experiment", experiment);
        ObjectNode searchedCandidates = LegacyResearchV2.designCandidates(searchedDesign);
        ArrayNode searchedMetrics = array();
        for (JsonNode candidate : searchedCandidates.path("candidates")) {
            boolean chosen = candidate.path("definition").path("threshold").asInt() == 1;
            ObjectNode metric = object().put("asset", "btc")
                    .put("candidate_id", candidate.path("candidate_id").asText())
                    .put("selected", chosen).put("expectancy_r", chosen ? 0.2 : -0.2)
                    .put("max_drawdown_pct", 4).put("max_statistic_p_value", 0.05)
                    .put("bootstrap_p20_expectancy_r", 0.1)
                    .put("effective_independent_episode_count", 8);
            metric.set("plateau", object().put("pass", true).put("neighbor_count", 99)
                    .put("profitable_neighbor_fraction", 1)
                    .put("connected_profitable_plateau_size", 99));
            searchedMetrics.add(metric);
        }
        ObjectNode searchedOptions = runOptions.deepCopy();
        searchedOptions.set("experiment", experiment);
        searchedOptions.set("candidateSet", searchedCandidates);
        searchedOptions.set("metrics", searchedMetrics);
        ObjectNode searchedRun = LegacyResearchV2.makeV2Run(searchedOptions);
        JsonNode decision = searchedRun.path("decisions").path("per_asset").path(0);
        assertThat(decision.path("status").asText()).isEqualTo("REJECTED");
        assertThat(decision.path("reasons")).extracting(JsonNode::asText)
                .contains("PROFITABLE_NEIGHBOR_FRACTION", "PLATEAU_SIZE");

        ResearchSchemaRegistry schemas = ResearchSchemaRegistry.defaultRegistry();
        assertThat(schemas.validateContractSchema(frozen)).isTrue();
        assertThat(schemas.validateContractSchema(definition)).isTrue();
        assertThat(schemas.validateContractSchema(experiment)).isTrue();
        assertThat(schemas.validateContractSchema(searchedCandidates)).isTrue();
        assertThat(schemas.validateContractSchema(run)).isTrue();
    }

    @Test
    void originalV2CliLifecycleRemainsContentAddressedAndFailClosed() throws Exception {
        Path root = temporary.resolve("research");
        ObjectNode premise = precommit();
        premise.set("candidate_template", object().put("id", "base")
                .set("instrument", cryptoSpot("btc")));
        premise.set("feature_contract", featureContract());
        ObjectNode acceptance = acceptance(stressSuite());
        ((ObjectNode) acceptance).set("plateau", object());
        ObjectNode experimentInput = object().put("evidence_phase", "DEVELOPMENT")
                .put("ablation_role", "NO_SELECTION_SEARCH");
        experimentInput.set("required_assets", array().add("btc"));
        experimentInput.set("grid", object());
        experimentInput.set("acceptance", acceptance);
        premise.set("experiment", experimentInput);
        Path premisePath = writeJson(temporary.resolve("premise.json"), premise);

        CliResult precommitResult = cli("precommit", "--root", root.toString(),
                "--input", premisePath.toString());
        assertThat(precommitResult.exit()).describedAs(precommitResult.stderr()).isZero();
        ObjectNode precommitOut = parse(precommitResult.stdout());
        CliResult generateResult = cli("generate", "--root", root.toString(),
                "--precommit", precommitOut.path("precommit").asText());
        assertThat(generateResult.exit()).describedAs(generateResult.stderr()).isZero();
        ObjectNode generated = parse(generateResult.stdout());
        assertThat(generated.path("schema").asText()).isEqualTo("strategy-definition/2");
        assertThat(parse(cli("validate", "--root", root.toString(), "--input",
                precommitOut.path("precommit").asText()).stdout()).path("valid").asBoolean())
                .isTrue();

        ObjectNode generatedCandidates = (ObjectNode) LegacyNodeOracle.MAPPER.readTree(
                Files.readAllBytes(Path.of(generated.path("candidates").asText())));
        String candidateId = generatedCandidates.path("candidates").path(0)
                .path("candidate_id").asText();
        ArrayNode metrics = array().add(object().put("asset", "btc")
                .put("candidate_id", candidateId).put("selected", true)
                .put("status", "ACTIVE").put("run_id", "forged")
                .put("evidence_phase", "SEALED_CONFIRMATION")
                .put("max_statistic_p_value", 0.05)
                .put("bootstrap_p20_expectancy_r", 0.1)
                .put("effective_independent_episode_count", 8));
        ArrayNode trades = stressTrades(episodeRows("btc", 1, 4));
        ObjectNode portfolioPolicy = object().put("initial_equity", 10_000)
                .put("total_concurrency", 3).put("per_asset_concurrency", 1)
                .put("gross_exposure_cap", 2_000).put("net_exposure_cap", 2_000)
                .put("collateral_cap", 2_000).put("leverage_cap", 3);
        portfolioPolicy.set("acceptance", portfolioAcceptance());
        ObjectNode portfolio = StrategyPortfolioV5.simulateCryptoPortfolio(
                portfolioSignals(), portfolioPolicy);
        ObjectNode stress = LegacyResearchV2.runStressSuite(trades, stressSuite());
        Path metricsPath = writeJson(temporary.resolve("metrics.json"), metrics);
        Path tradesPath = writeJson(temporary.resolve("trades.json"), trades);
        Path portfolioPath = writeJson(temporary.resolve("portfolio.json"), portfolio);
        Path stressPath = writeJson(temporary.resolve("stress.json"), stress);

        CliResult first = cli("run", "--root", root.toString(), "--experiment",
                generated.path("experiment").asText(), "--metrics", metricsPath.toString(),
                "--trades", tradesPath.toString(), "--portfolio", portfolioPath.toString(),
                "--stress", stressPath.toString());
        assertThat(first.exit()).describedAs(first.stderr()).isZero();
        ObjectNode firstRun = parse(first.stdout());
        assertThat(firstRun.path("schema").asText()).isEqualTo("strategy-run/2");
        assertThat(firstRun.path("decisions").path("per_asset")).noneSatisfy(row ->
                assertThat(row.path("status").asText())
                        .isIn("ACTIVE", "CANDIDATE_REVIEW"));
        Path firstRunPath = root.resolve("runs").resolve(firstRun.path("run_id").asText())
                .resolve("run.json");
        assertThat(parse(cli("validate", "--root", root.toString(), "--input",
                firstRunPath.toString()).stdout()).path("valid").asBoolean()).isTrue();

        ObjectNode secondRun = parse(cli("run", "--root", root.toString(), "--experiment",
                generated.path("experiment").asText(), "--metrics", metricsPath.toString(),
                "--trades", tradesPath.toString(), "--portfolio", portfolioPath.toString(),
                "--stress", stressPath.toString(), "--generated_at",
                "2026-08-24T00:00:00Z").stdout());
        assertThat(secondRun.path("run_id").asText())
                .isNotEqualTo(firstRun.path("run_id").asText());
        assertThat(parse(cli("show", "--root", root.toString(), "--id",
                firstRun.path("run_id").asText()).stdout()).path("run").path("schema").asText())
                .isEqualTo("strategy-run/2");
        assertThat(parse(cli("compare", "--root", root.toString(), "--left",
                firstRun.path("run_id").asText(), "--right",
                secondRun.path("run_id").asText()).stdout()).has("deltas")).isTrue();

        ArrayNode returnsByCandidate = array()
                .add(object().put("candidate_id", "positive")
                        .set("rows", episodeRows("btc", 1, 4)))
                .add(object().put("candidate_id", "null")
                        .set("rows", episodeRows("eth", 0, 4)));
        assertThat(parse(cli("stats", "--input", writeJson(temporary.resolve("stats.json"),
                returnsByCandidate).toString(), "--candidate", "positive", "--iterations",
                "20").stdout()).has("reality_check")).isTrue();
        ObjectNode ablationInput = object();
        ablationInput.set("base_candidate", generatedCandidates.path("candidates").path(0)
                .path("definition"));
        ablationInput.set("context_inputs", array().add(macroInput()));
        assertThat(parse(cli("ablations", "--input", writeJson(
                temporary.resolve("ablations.json"), ablationInput).toString()).stdout())
                .has("add_one_context")).isTrue();
        assertThat(parse(cli("stress", "--trades", tradesPath.toString(), "--suite",
                writeJson(temporary.resolve("stress-suite.json"), stressSuite()).toString())
                .stdout()).path("pass").asBoolean()).isTrue();

        ArrayNode acceptedSignals = array();
        portfolio.path("accepted_signals").forEach(row -> acceptedSignals.add(row.path("signal")));
        assertThat(parse(cli("portfolio", "--signals", writeJson(
                temporary.resolve("signals.json"), acceptedSignals).toString(), "--policy",
                writeJson(temporary.resolve("policy.json"), portfolioPolicy).toString())
                .stdout()).path("pass").asBoolean()).isTrue();
        ObjectNode monthly = object().put("minimum_trades", 1).put("max_loss_run", 2);
        monthly.set("frequency", object().put("min", 0.5).put("max", 2)
                .put("unit", "per month"));
        monthly.set("win_rate", object().put("min", 0).put("max", 1));
        monthly.set("expectancy_r", object().put("min", -1).put("max", 1));
        ObjectNode evidence = object().put("monitoring_start", "2026-01-01T00:00:00Z")
                .put("monitoring_end", "2026-02-01T00:00:00Z");
        evidence.set("trades", array().add(object()
                .put("signal_time", "2026-01-15T00:00:00Z").put("net_r", 1)));
        assertThat(parse(cli("monitor", "--profile", writeJson(
                temporary.resolve("profile.json"), monthly).toString(), "--evidence",
                writeJson(temporary.resolve("evidence.json"), evidence).toString())
                .stdout()).path("status").asText()).isEqualTo("CANDIDATE_REVIEW");

        Path recordRoot = temporary.resolve("record-only");
        assertThat(parse(cli("record", "--root", recordRoot.toString(), "--input",
                precommitOut.path("precommit").asText()).stdout()).path("schema").asText())
                .isEqualTo("strategy-precommit/1");
        assertThat(cli("rebuild-index", "--root", root.toString()).exit()).isZero();
        assertThat(parse(cli("validate", "--root", root.toString()).stdout())
                .path("valid").asBoolean()).isTrue();
        JsonNode performance = LegacyNodeOracle.MAPPER.readTree(cli("list", "--root",
                root.toString(), "--kind", "performance", "--asset", "btc").stdout());
        assertThat(performance).anySatisfy(row -> {
            assertThat(row.path("candidate_id").asText()).isEqualTo(candidateId);
            assertThat(row.path("run_id").asText()).isEqualTo(firstRun.path("run_id").asText());
            assertThat(row.path("status").asText()).isEqualTo("SHADOW");
            assertThat(row.path("evidence_phase").asText()).isEqualTo("DEVELOPMENT");
        });
    }

    private static ObjectNode precommit() {
        ObjectNode value = object().put("schema", "strategy-precommit/1")
                .put("precommit_id", "v2-fixture").put("created_at", CREATED_AT)
                .put("stage", "CORE_PREMISE")
                .put("phenomenon", "forced crypto deleveraging followed by inventory repair")
                .put("economic_behavioral_mechanism",
                        "forced sellers transfer inventory to patient liquidity providers")
                .put("persistence", "margin clearing and dealer inventory normalization take several completed bars")
                .put("crowding_decay", "faster capital and copied entry rules compress the rebound")
                .put("direction", "long")
                .put("expression", "BTC spot or declared BTC crypto derivative")
                .put("failure_invalidation_mechanism",
                        "completed deleveraging episodes cease to predict inventory repair")
                .put("role_of_composite_score",
                        "A later incremental test only; no composite score or score threshold is part of the core premise.");
        value.set("participants", object().put("forced_actor", "levered crypto trader")
                .put("edge_provider", "liquidity provider")
                .put("edge_consumer", "patient swing trader"));
        value.set("holding_horizon", object().put("min", 1).put("max", 30)
                .put("unit", "days"));
        value.set("expected_signal_frequency", object().put("min", 1).put("max", 8)
                .put("unit", "per month"));
        value.set("expected_win_rate", object().put("min", 0.35).put("max", 0.65));
        ObjectNode payoff = object().put("qualitative_shape", "asymmetric right tail");
        payoff.set("average_win_r", object().put("min", 1).put("max", 3));
        payoff.set("average_loss_r", object().put("min", -1.5).put("max", -0.5));
        value.set("payoff", payoff);
        value.set("regimes", object()
                .set("expected_to_work", array().add("liquid fear and deleveraging")));
        ((ObjectNode) value.path("regimes")).set("expected_to_fail",
                array().add("persistent insolvency or thin liquidity"));
        value.set("required_inputs", array().add(setupInput()).add(macroInput()));
        ObjectNode falsifier = object().put("test",
                "score-free baseline versus aligned event-block null")
                .put("null", "no positive episode-level expectancy");
        falsifier.set("rejection_thresholds", object().put("expectancy_r", 0));
        value.set("falsifier", falsifier);
        ObjectNode contract = object().put("universe", "CRYPTO_ONLY");
        contract.set("instruments", array().add(cryptoSpot("btc"))
                .add(object().put("asset", "eth").put("asset_class", "crypto")
                        .put("instrument_type", "perpetual").put("venue", "binance")
                        .put("collateral", "usdt")
                        .put("funding_contract", "actual settlements")));
        value.set("tradable_instrument_contract", contract);
        value.set("non_crypto_context_only", array().add(object()
                .put("input_id", "real-yield-context").put("asset", "us-real-yield")
                .put("asset_class", "rate").put("context_only", true).put("tradable", false)));
        value.set("independence_replication_groups", array().add("crypto-flow"));
        return value;
    }

    private static ObjectNode setupInput() {
        ObjectNode value = object().put("input_id", "setup-flow")
                .put("evidence_family", "crypto-flow").put("role", "SETUP");
        value.set("availability", object().put("rule", "completed 4h bar close"));
        value.set("point_in_time", object().put("status", "VERIFIED")
                .put("completed_bar_only", true));
        return value;
    }

    private static ObjectNode macroInput() {
        ObjectNode value = object().put("input_id", "real-yield-context")
                .put("evidence_family", "macro-rates").put("role", "CONTEXT");
        value.set("availability", object().put("rule", "first public release timestamp"));
        value.set("point_in_time", object().put("status", "PIT_SAFE"));
        return value;
    }

    private static ObjectNode featureContract() {
        ObjectNode value = object();
        value.set("series", array()
                .add(object().put("series_id", "btc-4h").put("asset", "btc")
                        .put("asset_class", "crypto").put("timeframe", "4h")
                        .put("context_only", false).put("tradable", true)
                        .set("point_in_time", object().put("status", "VERIFIED")
                                .put("completed_bar_only", true)))
                .add(object().put("series_id", "real-yield-daily")
                        .put("asset", "us-real-yield").put("asset_class", "rate")
                        .put("timeframe", "1d").put("context_only", true)
                        .put("tradable", false).set("point_in_time",
                                object().put("status", "PIT_SAFE"))));
        value.set("inputs", array().add(setupInput()).add(macroInput()));
        return value;
    }

    private static ObjectNode stressSuite() {
        ObjectNode value = object();
        value.set("required_scenarios", array()
                .add(object().put("id", "fee_slippage").put("multiplier", 2)
                        .put("minimum_expectancy_r", -1).put("minimum_observations", 1))
                .add(object().put("id", "funding_carry").put("multiplier", 2)
                        .put("minimum_expectancy_r", -1).put("minimum_observations", 1))
                .add(object().put("id", "adverse_execution_gap").put("debit_r", 0.1)
                        .put("minimum_expectancy_r", -1).put("minimum_observations", 1))
                .add(object().put("id", "liquidity_capacity")
                        .put("maximum_participation_rate", 0.05)
                        .put("minimum_expectancy_r", -1).put("minimum_observations", 1))
                .add(object().put("id", "venue_outage_blackout")
                        .set("windows", array().add(object().put("venue", "binance")
                                .put("start", "2027-01-01T00:00:00Z")
                                .put("end", "2027-01-02T00:00:00Z")))));
        ObjectNode outage = (ObjectNode) value.path("required_scenarios").get(4);
        outage.put("minimum_expectancy_r", -1).put("minimum_observations", 1);
        return value;
    }

    private static ObjectNode acceptance(ObjectNode stress) {
        ObjectNode value = object();
        value.set("robust_stats", object().put("max_statistic_p_value", 0.1)
                .put("minimum_bootstrap_p20_expectancy_r", 0)
                .put("minimum_effective_independent_episode_count", 3));
        value.set("plateau", object().put("minimum_neighbor_count", 1)
                .put("minimum_profitable_neighbor_fraction", 0.5)
                .put("minimum_plateau_size", 2));
        value.set("stress", stress);
        value.set("portfolio", portfolioAcceptance());
        return value;
    }

    private static ObjectNode portfolioAcceptance() {
        return object().put("minimum_accepted_trades", 3)
                .put("maximum_drawdown_pct", 2).put("minimum_net_pnl", 0)
                .put("minimum_final_equity", 10_000);
    }

    private static ObjectNode experiment(ObjectNode definition, ObjectNode acceptance) {
        ObjectNode value = object().put("schema", "strategy-experiment/2")
                .put("experiment_id", "v2-experiment").put("created_at", CREATED_AT)
                .put("stage", "CORE_PREMISE").put("evidence_phase", "DEVELOPMENT")
                .put("hypothesis_family", "flow-family")
                .put("ablation_role", "PARAMETER_SEARCH");
        value.set("definition", object().put("path", "definitions/v2-fixture/v001.json")
                .put("sha256", LegacyResearchV2.hash(definition)));
        value.set("evidence_family_ids", array().add("crypto-flow"));
        value.set("required_assets", array().add("btc"));
        value.set("grid", object().set("threshold", array().add(1).add(2).add(2)));
        value.set("acceptance", acceptance);
        value.set("candidate_set", object().put("path", "candidates.json").putNull("sha256"));
        return LegacyResearchV2.withHash(value);
    }

    private static ArrayNode episodeRows(String asset, double netR, int count) {
        ArrayNode values = array();
        for (int index = 0; index < count; index++) {
            values.add(object().put("net_r", netR)
                    .put("event_id", "episode-" + index).put("asset", asset));
        }
        return values;
    }

    private static ArrayNode stressTrades(ArrayNode episodes) {
        ArrayNode values = array();
        int index = 0;
        for (JsonNode episode : episodes) {
            ObjectNode trade = ((ObjectNode) episode).deepCopy()
                    .put("trade_id", "trade-" + index)
                    .put("entry_time", "2026-01-0" + (index + 1) + "T00:00:00Z")
                    .put("venue", "binance").put("fee_r", 0.01)
                    .put("slippage_r", 0.01).put("funding_debit_r", 0.01)
                    .put("notional", 100).put("available_liquidity_notional", 10_000);
            values.add(trade);
            index++;
        }
        return values;
    }

    private static ArrayNode portfolioSignals() {
        ArrayNode values = array();
        values.add(signal("btc-first", "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z", "btc", "long", 500, 100, 1,
                cryptoSpot("btc")));
        values.add(signal("eth-overlap", "2026-01-01T12:00:00Z",
                "2026-01-03T00:00:00Z", "eth", "short", 400, 100, -0.5,
                object().put("asset", "eth").put("asset_class", "crypto")
                        .put("instrument_type", "perpetual").put("venue", "binance")
                        .put("collateral", "usdt")
                        .put("funding_contract", "actual settlements")));
        values.add(signal("btc-conflict", "2026-01-01T18:00:00Z",
                "2026-01-02T06:00:00Z", "btc", "short", 100, 10, 0,
                cryptoSpot("btc")));
        values.add(signal("btc-after-release", "2026-01-02T12:00:00Z",
                "2026-01-04T00:00:00Z", "btc", "long", 300, 50, -1,
                cryptoSpot("btc")));
        ObjectNode forbidden = object().put("signal_id", "spx-forbidden")
                .put("entry_time", "2026-01-04T00:00:00Z")
                .put("exit_time", "2026-01-05T00:00:00Z").put("asset", "spx")
                .put("direction", "long").put("notional", 100).put("net_pnl", 10);
        forbidden.set("instrument", object().put("asset", "spx")
                .put("asset_class", "index").put("instrument_type", "spot"));
        values.add(forbidden);
        return values;
    }

    private static ObjectNode signal(
            String id, String entry, String exit, String asset, String direction,
            double notional, double risk, double netR, ObjectNode instrument) {
        ObjectNode value = object().put("signal_id", id).put("entry_time", entry)
                .put("exit_time", exit).put("asset", asset).put("direction", direction)
                .put("notional", notional).put("risk_amount", risk).put("net_r", netR);
        value.set("instrument", instrument);
        return value;
    }

    private static ObjectNode cryptoSpot(String asset) {
        return object().put("asset", asset).put("asset_class", "crypto")
                .put("instrument_type", "spot");
    }

    private static ArrayNode prospectiveTrades(double... returns) {
        ArrayNode values = array();
        for (int index = 0; index < returns.length; index++) {
            values.add(object().put("signal_time", "2026-01-0" + (index + 1)
                    + "T00:00:00Z").put("net_r", returns[index]));
        }
        return values;
    }

    private static JsonNode findBy(JsonNode candidates, String field, int value) {
        for (JsonNode candidate : candidates) {
            if (candidate.path("definition").path(field).asInt() == value) return candidate;
        }
        throw new AssertionError("missing candidate " + field + '=' + value);
    }

    private static JsonNode findScenario(ObjectNode result, String id) {
        for (JsonNode row : result.path("scenarios")) {
            if (id.equals(row.path("id").asText())) return row;
        }
        throw new AssertionError("missing scenario " + id);
    }

    private static Path writeJson(Path path, JsonNode value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, LegacyNodeOracle.MAPPER.writeValueAsString(value),
                StandardCharsets.UTF_8);
        return path;
    }

    private static ObjectNode parse(String json) throws Exception {
        return (ObjectNode) LegacyNodeOracle.MAPPER.readTree(json);
    }

    private static CliResult cli(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = LegacyResearchCommandAdapter.run(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new CliResult(exit, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record CliResult(int exit, String stdout, String stderr) {}
}
