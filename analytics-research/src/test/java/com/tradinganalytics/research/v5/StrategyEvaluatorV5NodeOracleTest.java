package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StrategyEvaluatorV5NodeOracleTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    @Test
    void constantsSpecValidationAndEveryPredicateOperatorMatchNodeExactly() throws Exception {
        JsonNode constants = oracle(request("constants")).path("value");
        assertThat(StrategyEvaluatorV5.STRATEGY_EVALUATOR_V5_CODE_SHA256)
                .isEqualTo(constants.path("code").asText());
        assertThat(StrategyEvaluatorV5.STRATEGY_EVALUATOR_V5_WORKER_CODE_SHA256)
                .isEqualTo(constants.path("worker").asText());

        ObjectNode options = specOptions();
        ObjectNode expected = nodeValue(request("spec").set("options", options));
        ObjectNode actual = StrategyEvaluatorV5.makeEvaluatorSpecV5(options);
        assertJson(actual, expected);
        ObjectNode bindings = MAPPER.createObjectNode();
        bindings.set("geneSpace", options.path("geneSpace"));
        bindings.set("predictorRegistry", options.path("predictorRegistry"));
        assertThat(StrategyEvaluatorV5.validateEvaluatorSpecV5(actual, bindings)).isTrue();

        for (String operator : new String[] {"GT", "GTE", "LT", "LTE", "EQ", "NE"}) {
            ObjectNode predicate = predicate(operator, MAPPER.getNodeFactory().numberNode(2));
            for (double actualValue : new double[] {-1, 2, 3.5}) {
                ObjectNode feature = MAPPER.createObjectNode().put("edge", actualValue);
                ObjectNode chromosome = MAPPER.createObjectNode();
                ObjectNode call = request("predicate");
                call.set("predicate", predicate); call.set("feature", feature); call.set("chromosome", chromosome);
                assertThat(StrategyEvaluatorV5.evaluateSignalPredicateV5(predicate, feature, chromosome))
                        .isEqualTo(oracle(call).path("value").asBoolean());
            }
        }
        ObjectNode membership = predicate("IN", MAPPER.createArrayNode().add("a").add(2).addObject().put("x", 1));
        for (JsonNode value : MAPPER.createArrayNode().add("a").add(2).addObject().put("x", 1)) {
            ObjectNode feature = MAPPER.createObjectNode().set("edge", value);
            ObjectNode call = request("predicate");
            call.set("predicate", membership); call.set("feature", feature);
            call.set("chromosome", MAPPER.createObjectNode());
            assertThat(StrategyEvaluatorV5.evaluateSignalPredicateV5(membership, feature, MAPPER.createObjectNode()))
                    .isEqualTo(oracle(call).path("value").asBoolean());
        }
    }

    @Test
    void nestedMissingPredictorsAndPhysicalNullRebasingMatchNodeExactly() throws Exception {
        ObjectNode leaf = predicate("GTE", MAPPER.createObjectNode().put("$gene", "threshold"));
        ObjectNode all = MAPPER.createObjectNode();
        all.putArray("all").add(leaf).add(predicate("LT", MAPPER.getNodeFactory().numberNode(9)));
        ObjectNode not = MAPPER.createObjectNode().set("not", all);
        for (JsonNode feature : MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode())
                .add(MAPPER.createObjectNode().putNull("edge"))
                .add(MAPPER.createObjectNode().put("edge", 3))) {
            ObjectNode chromosome = MAPPER.createObjectNode().put("threshold", 2);
            ObjectNode call = request("predicate");
            call.set("predicate", not); call.set("feature", feature); call.set("chromosome", chromosome);
            assertThat(StrategyEvaluatorV5.evaluateSignalPredicateV5(not, feature, chromosome))
                    .isEqualTo(oracle(call).path("value").asBoolean());
        }

        ObjectNode target = identityRow("episode-target", "2026-02-01T00:00:00.000Z");
        target.putObject("execution_reference").put("window_id", "target-window");
        ObjectNode source = identityRow("episode-source", "2026-01-01T00:00:00.000Z");
        source.put("exit_time", "2026-01-01T00:02:00.000Z");
        source.put("numeric_time", Instant.parse("2026-01-01T00:03:00Z").toEpochMilli());
        source.put("epoch_seconds_time", Instant.parse("2026-01-01T00:04:00Z").getEpochSecond());
        source.put("price", 123.45);
        source.putObject("nested").put("availability_time", "2026-01-01T00:00:59.999Z")
                .put("untouched", "x");
        source.putArray("child_bars")
                .addObject().put("event_time", "2026-01-01T00:01:00.000Z").put("close", 100);
        source.putObject("execution_reference").put("window_id", "source-window");
        ObjectNode options = MAPPER.createObjectNode();
        options.set("target", target); options.set("source", source);
        ObjectNode call = request("rebase").set("options", options);
        assertJson(StrategyEvaluatorV5.rebasePhysicalNullExecutionV5(options), nodeValue(call));
    }

    @Test
    void fixtureAndVerifiedWorkerEvaluationArtifactsMatchNodeByteForByteCanonically() throws Exception {
        ObjectNode fixture = fixture(false);
        ObjectNode call = request("fixture");
        call.set("binding", fixture.path("binding")); call.set("task", fixture.path("task"));
        ObjectNode expected = nodeValue(call);
        try (StrategyEvaluatorV5.Evaluator evaluator = StrategyEvaluatorV5.createFixtureEvaluatorV5(
                (ObjectNode) fixture.path("binding"))) {
            ObjectNode actual = evaluator.evaluate((ObjectNode) fixture.path("task"));
            assertJson(actual, expected);
            assertThat(actual.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(actual));
            assertThat(evaluator.publicPredictorIds()).containsExactly("edge");
        }

        ObjectNode workerCall = request("verified-worker");
        ObjectNode authoritativeBinding = ((ObjectNode) fixture.path("binding")).deepCopy();
        authoritativeBinding.remove("mode");
        workerCall.set("binding", authoritativeBinding); workerCall.set("task", fixture.path("task"));
        ObjectNode workerExpected = nodeValue(workerCall);
        try (StrategyEvaluatorV5.Evaluator evaluator =
                StrategyEvaluatorV5.createVerifiedWorkerEvaluatorV5(authoritativeBinding)) {
            assertJson(evaluator.evaluate((ObjectNode) fixture.path("task")), workerExpected);
        }
    }

    @Test
    void tradedFixtureCostsAndReturnsMatchNodeExactly() throws Exception {
        ObjectNode fixture = fixture(true);
        ObjectNode call = request("fixture");
        call.set("binding", fixture.path("binding")); call.set("task", fixture.path("task"));
        JsonNode response = oracle(call);
        assertThat(response.path("ok").asBoolean()).describedAs(response.toString()).isTrue();
        ObjectNode actual;
        try (StrategyEvaluatorV5.Evaluator evaluator = StrategyEvaluatorV5.createFixtureEvaluatorV5(
                (ObjectNode) fixture.path("binding"))) {
            actual = evaluator.evaluate((ObjectNode) fixture.path("task"));
        }
        assertJson(actual, response.path("value"));
        assertThat(actual.path("candidate_returns").path("episode-1").path("net_r").asDouble()).isPositive();
        assertThat(actual.path("metrics").path("capacity_pass").asBoolean()).isTrue();
    }

    @Test
    void derivativeFundingMarginAndLiquidationPathMatchesNodeExactly() throws Exception {
        ObjectNode fixture = derivativeFixture();
        ObjectNode call = request("fixture");
        call.set("binding", fixture.path("binding"));
        call.set("task", fixture.path("task"));
        ObjectNode expected = nodeValue(call);
        try (StrategyEvaluatorV5.Evaluator evaluator = StrategyEvaluatorV5.createFixtureEvaluatorV5(
                (ObjectNode) fixture.path("binding"))) {
            ObjectNode actual = evaluator.evaluate((ObjectNode) fixture.path("task"));
            assertJson(actual, expected);
            assertThat(actual.path("candidate_returns").path("episode-1").path("traded").asBoolean())
                    .isTrue();
            assertThat(actual.path("candidate_returns").path("episode-1").path("net_r").asDouble())
                    .isFinite().isNegative();
        }
    }

    @Test
    void nullishQuantityDerivedNotionalAndFractionalDelayMatchNodeExactly() throws Exception {
        ObjectNode nullish = fixture(true);
        ObjectNode nullishBinding = (ObjectNode) nullish.path("binding");
        ((ObjectNode) nullishBinding.path("execution").path(0)).putNull("quantity");
        ((ObjectNode) nullishBinding.path("labels").path(0)).put("quantity", 1);
        ObjectNode nullishCall = request("fixture");
        nullishCall.set("binding", nullishBinding);
        nullishCall.set("task", nullish.path("task"));
        try (StrategyEvaluatorV5.Evaluator evaluator = StrategyEvaluatorV5.createFixtureEvaluatorV5(nullishBinding)) {
            assertJson(evaluator.evaluate((ObjectNode) nullish.path("task")), nodeValue(nullishCall));
        }

        ObjectNode derived = fixture(true);
        ObjectNode derivedBinding = (ObjectNode) derived.path("binding");
        ((ObjectNode) derivedBinding.path("execution").path(0)).remove("quantity");
        ObjectNode contract = (ObjectNode) derivedBinding.path("metadata")
                .path("contract_spec").path("records").path(0);
        contract.put("step_size", .001).put("min_qty", .001).put("max_qty", 100_000)
                .put("min_notional", 1).put("max_notional", 10);
        ObjectNode contractReceipt = (ObjectNode) derivedBinding.path("metadata").path("contract_spec");
        contractReceipt.put("content_sha256", JsonHashes.ownHash(contractReceipt));
        ObjectNode derivedCall = request("fixture");
        derivedCall.set("binding", derivedBinding);
        derivedCall.set("task", derived.path("task"));
        try (StrategyEvaluatorV5.Evaluator evaluator = StrategyEvaluatorV5.createFixtureEvaluatorV5(derivedBinding)) {
            assertJson(evaluator.evaluate((ObjectNode) derived.path("task")), nodeValue(derivedCall));
        }
        contract.put("max_notional", 9.99);
        contractReceipt.put("content_sha256", JsonHashes.ownHash(contractReceipt));
        assertSameFailure(derivedCall,
                () -> StrategyEvaluatorV5.createFixtureEvaluatorV5(derivedBinding)
                        .evaluate((ObjectNode) derived.path("task")));

        ObjectNode delayed = fixture(true);
        ObjectNode options = specOptions();
        ((ObjectNode) options.path("candidateTemplate")).put("entry_policy", "DELAYED_BAR_OPEN");
        ObjectNode delayedBinding = (ObjectNode) delayed.path("binding");
        delayedBinding.set("evaluatorSpec", StrategyEvaluatorV5.makeEvaluatorSpecV5(options));
        delayedBinding.set("geneSpace", options.path("geneSpace"));
        delayedBinding.set("predictorRegistry", options.path("predictorRegistry"));
        ((ObjectNode) delayedBinding.path("execution").path(0)).put("entry_delay_bars", 1.5);
        ObjectNode delayedCall = request("fixture");
        delayedCall.set("binding", delayedBinding);
        delayedCall.set("task", delayed.path("task"));
        assertSameFailure(delayedCall,
                () -> StrategyEvaluatorV5.createFixtureEvaluatorV5(delayedBinding)
                        .evaluate((ObjectNode) delayed.path("task")));
    }

    @Test
    void verifiedPhysicalNullSelectionMatchesTheOriginalNodeEndToEndFixture(@TempDir Path temporary)
            throws Exception {
        ObjectNode oracle = frozenPhysicalNullFixture();
        ObjectNode fixture = (ObjectNode) oracle.path("fixture");
        Path parquetRoot = Path.of(fixture.path("root").asText()).toAbsolutePath().normalize();
        // The frozen oracle was produced from a local, temporary Node parquet lake. The lake is
        // intentionally not checked in, so retain the differential when it is available locally
        // and skip it on clean CI checkouts instead of resolving a stale machine-specific path.
        assumeTrue(Files.isDirectory(parquetRoot),
                "frozen physical-null parquet lake is not available: " + parquetRoot);
        ObjectNode load = MAPPER.createObjectNode()
                .put("root", parquetRoot.toString())
                .put("cacheRoot", temporary.resolve("java-worker-cache").toString())
                .put("workerCount", 2).put("maxRowsPerRole", 100)
                .put("maxMaterializedBytesPerRole", 8_000_000);
        load.set("manifest", fixture.path("manifest"));
        load.set("evaluatorSpec", fixture.path("evaluatorSpec"));
        load.set("geneSpace", fixture.path("geneSpace"));
        load.set("predictorRegistry", fixture.path("predictorRegistry"));
        ObjectNode metadata = ((ObjectNode) fixture.path("metadata")).deepCopy();
        Path metadataRoot = Path.of(metadata.path("source_root").asText()).toAbsolutePath().normalize();
        String javaRootReference = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                .relativize(metadataRoot).toString().replace(java.io.File.separatorChar, '/');
        for (String key : new String[] {"contract_spec", "fee_schedule", "execution_model"}) {
            ObjectNode receipt = (ObjectNode) metadata.path(key);
            receipt.put("source_root_reference", javaRootReference);
            receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        }
        load.set("metadata", metadata);
        load.put("metadataRoot", metadataRoot.toString());

        try (StrategyEvaluatorV5.LoadedEvaluator loaded =
                StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(load)) {
            assertThat(loaded.evaluator().physicalNullSelectionVerified()).isTrue();
            ObjectNode runnerOptions = MAPPER.createObjectNode();
            runnerOptions.set("roleManifest", fixture.path("manifest"));
            runnerOptions.set("exposureHead", fixture.path("exposureHead"));
            runnerOptions.set("geneSpace", fixture.path("geneSpace"));
            runnerOptions.set("behaviorDefinitions", fixture.path("behaviorDefinitions"));
            runnerOptions.set("selectionConstraints", fixture.path("selectionConstraints"));
            runnerOptions.set("selectionEndAt", fixture.path("selectionEndAt"));
            runnerOptions.put("physicalNullRoot", temporary.resolve("java-physical-null").toString());
            StrategyStatisticalV5.PhysicalNullRunner runner =
                    StrategyStatisticalV5.makePhysicalNullRunnerV5(runnerOptions, loaded.evaluator());

            ObjectNode nullOptions = MAPPER.createObjectNode();
            nullOptions.set("artifact", fixture.path("artifact"));
            nullOptions.set("selectedEpisodeIds", fixture.path("episodeIds"));
            nullOptions.put("selectedCandidateId", "c");
            nullOptions.set("selectionBudget", fixture.path("selectionBudget"));
            nullOptions.put("iterations", 1).put("mode", "AUTHORITATIVE");
            ObjectNode actual = StrategyStatisticalV5.runNullControlsV5(nullOptions, null, runner);
            JsonNode expected = oracle.path("result");
            assertThat(actual.path("tests")).hasSize(4);
            for (int index = 0; index < 4; index++) {
                JsonNode javaTest = actual.path("tests").path(index);
                JsonNode nodeTest = expected.path("tests").path(index);
                for (String key : new String[] {"name", "method", "p_value", "p_value_lower_bound",
                        "p_value_upper_bound", "null_statistics_sha256", "pass", "iterations",
                        "iterations_planned", "sequential_stopping_reason", "evaluation_attempt_k",
                        "worker_evaluation_count", "worker_count", "checkpointed_iterations",
                        "checkpoint_policy"}) {
                    assertJson(javaTest.path(key), nodeTest.path(key));
                }
                assertThat(javaTest.path("method").asText())
                        .isEqualTo("PHYSICAL_ROLE_BOUND_ADAPTIVE_SELECTION");
            }

            ObjectNode direct = MAPPER.createObjectNode();
            direct.set("source_artifact", fixture.path("artifact"));
            direct.put("method", "block_permuted_labels").put("seed", 7).put("iteration", 0);
            direct.set("selection_budget", fixture.path("selectionBudget"));
            direct.put("selected_candidate_id", "c");
            direct.set("selected_episode_ids", fixture.path("episodeIds"));
            direct.put("selected_trade_count", 0); direct.putArray("selected_trade_episode_ids");
            ObjectNode selected = runner.run(direct);
            assertThat(selected.path("schema").asText())
                    .isEqualTo("strategy-v5-physical-null-selection/1");
            assertThat(selected.path("checkpoint_status").asText()).isEqualTo("COMPLETED");
            assertJson(runner.run(direct), selected);
            Path transformedLabel = Path.of(selected.path("transformed_label_ref").path("path").asText());
            Files.writeString(transformedLabel, "tampered", java.nio.file.StandardOpenOption.APPEND);
            assertThatThrownBy(() -> runner.run(direct))
                    .hasMessage("physical null checkpoint/reference bytes are tampered");
        }
    }

    @Test
    void criticalFailuresHaveTheSameFailClosedMessagesAsNode() throws Exception {
        ObjectNode options = specOptions();
        options.put("precommitSha256", "bad");
        assertSameFailure(request("spec").set("options", options),
                () -> StrategyEvaluatorV5.makeEvaluatorSpecV5(options));

        ObjectNode undeclared = specOptions();
        ((ObjectNode) undeclared.path("predicate").path("value")).put("$gene", "missing");
        assertSameFailure(request("spec").set("options", undeclared),
                () -> StrategyEvaluatorV5.makeEvaluatorSpecV5(undeclared));

        ObjectNode normalized = specOptions();
        ObjectNode candidate = (ObjectNode) normalized.path("candidateTemplate");
        candidate.putObject("lifecycle").put("max_lifecycle_ms", 60_000)
                .putObject("sizing").put("mode", "FIXED_NOTIONAL_USD").put("notional_usd", 10);
        ((ObjectNode) normalized.path("executionContract")).remove("risk_convention");
        candidate.remove("risk_amount_usd");
        assertSameFailure(request("spec").set("options", normalized),
                () -> StrategyEvaluatorV5.makeEvaluatorSpecV5(normalized));

        ObjectNode fixture = fixture(false);
        ObjectNode binding = (ObjectNode) fixture.path("binding");
        binding.remove("mode");
        assertThatThrownBy(() -> StrategyEvaluatorV5.createFixtureEvaluatorV5(binding))
                .hasMessageContaining("fixture-only");
    }

    private static ObjectNode fixture(boolean traded) {
        ObjectNode options = specOptions();
        ObjectNode spec = StrategyEvaluatorV5.makeEvaluatorSpecV5(options);
        String source = JsonHashes.sha256("separated-artifacts");
        ObjectNode feature = identityRow("episode-1", "2026-01-01T00:00:00.000Z");
        feature.put("event_time", "2026-01-01T00:00:00.000Z").put("availability_time", "2026-01-01T00:00:00.000Z")
                .put("signal_eligible", true).put("edge", traded ? 2 : 0);
        ObjectNode label = identityRow("episode-1", "2026-01-01T00:00:00.000Z");
        label.put("entry_time", "2026-01-01T00:00:00.000Z")
                .put("resolution_ceiling_time", "2026-01-01T00:02:00.000Z")
                .put("availability_time", "2026-01-01T00:02:59.999Z");
        ObjectNode execution = identityRow("episode-1", "2026-01-01T00:00:00.000Z");
        execution.put("availability_time", "2026-01-01T00:02:59.999Z").put("quantity", 1)
                .put("risk_amount_usd", 10);
        execution.putObject("capacity_inputs").put("available_liquidity_usd", 10_000)
                .put("participation_cap", .1).put("order_notional_usd", 100);
        ArrayNode bars = execution.putArray("child_bars");
        bar(bars, 0, 100, 101, 99, 100);
        bar(bars, 1, 100, 102, 100, 101);
        bar(bars, 2, 101, 103, 101, 102);

        ObjectNode binding = MAPPER.createObjectNode().put("mode", "FIXTURE")
                .put("sourceArtifactSha256", source);
        binding.set("evaluatorSpec", spec);
        binding.set("geneSpace", options.path("geneSpace"));
        binding.set("predictorRegistry", options.path("predictorRegistry"));
        binding.putArray("features").add(feature);
        binding.putArray("labels").add(label);
        binding.putArray("execution").add(execution);
        binding.set("metadata", metadata());

        ObjectNode signalView = MAPPER.createObjectNode()
                .put("schema", "strategy-v5-statistical-signal-view/1")
                .put("source_artifact_sha256", source).put("phase", "TRAIN_ONLY").put("fold_id", "fold-1");
        signalView.putArray("episode_ids").add("episode-1");
        signalView.putArray("episodes").addObject().put("episode_id", "episode-1")
                .put("phase", "TRAIN_ONLY").put("fold_id", "fold-1").put("asset", "btc")
                .put("decision_time", "2026-01-01T00:00:00.000Z");
        ObjectNode task = MAPPER.createObjectNode().set("artifact", signalView);
        task.putArray("episode_ids").add("episode-1");
        task.putObject("chromosome").put("threshold", 1);
        task.put("phase", "TRAIN_ONLY").put("fold_id", "fold-1")
                .put("cutoff", "2026-01-01T00:03:59.999Z");
        ObjectNode fixture = MAPPER.createObjectNode();
        fixture.set("binding", binding); fixture.set("task", task);
        return fixture;
    }

    private static ObjectNode derivativeFixture() {
        ObjectNode fixture = fixture(true);
        ObjectNode options = specOptions();
        ((ObjectNode) options.path("candidateTemplate")).put("direction", "short");
        ((ObjectNode) options.path("executionContract")).putObject("derivative_policy")
                .put("margin_mode", "ISOLATED").put("leverage", 2).put("tier_id", "TIER_1");
        ObjectNode spec = StrategyEvaluatorV5.makeEvaluatorSpecV5(options);
        ObjectNode binding = (ObjectNode) fixture.path("binding");
        binding.set("evaluatorSpec", spec);
        binding.set("geneSpace", options.path("geneSpace"));
        binding.set("predictorRegistry", options.path("predictorRegistry"));
        for (String role : new String[] {"features", "labels", "execution"}) {
            ((ObjectNode) binding.path(role).path(0)).put("instrument", "BINANCE_USDM_PERPETUAL");
        }
        ObjectNode execution = (ObjectNode) binding.path("execution").path(0);
        ArrayNode marks = execution.putArray("mark_bars");
        for (JsonNode raw : execution.path("child_bars")) {
            marks.addObject().put("event_time", raw.path("event_time").asText())
                    .put("availability_time", raw.path("availability_time").asText())
                    .put("mark_open", raw.path("open").asDouble())
                    .put("mark_high", raw.path("high").asDouble())
                    .put("mark_low", raw.path("low").asDouble())
                    .put("mark_close", raw.path("close").asDouble());
        }

        ObjectNode metadata = (ObjectNode) binding.path("metadata");
        for (String key : new String[] {"contract_spec", "fee_schedule", "execution_model"}) {
            ObjectNode receipt = (ObjectNode) metadata.path(key);
            ((ObjectNode) receipt.path("records").path(0)).put("instrument", "BINANCE_USDM_PERPETUAL");
            if ("contract_spec".equals(key)) {
                ((ObjectNode) receipt.path("records").path(0)).put("max_leverage", 10);
            }
            receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        }
        ObjectNode funding = receipt("FUNDING_IDENTITY", MAPPER.createObjectNode());
        funding.putArray("records");
        funding.putObject("coverage").put("complete", true).put("coverage_mode", "EVENT_SEQUENCE");
        funding.put("content_sha256", JsonHashes.ownHash(funding));
        metadata.set("funding_identity", funding);
        ObjectNode marginTerms = MAPPER.createObjectNode().put("maintenance_margin_ratio", .005)
                .put("margin_mode", "ISOLATED").put("tier_id", "TIER_1").put("max_leverage", 10);
        ObjectNode margin = receipt("MARGIN", marginTerms);
        ((ObjectNode) margin.path("records").path(0)).put("instrument", "BINANCE_USDM_PERPETUAL");
        margin.put("content_sha256", JsonHashes.ownHash(margin));
        metadata.set("margin", margin);
        return fixture;
    }

    private static ObjectNode specOptions() {
        ObjectNode gene = MAPPER.createObjectNode().put("schema", "strategy-v5-statistical-gene-space/1");
        gene.putArray("genes").addObject().put("name", "threshold").put("type", "continuous")
                .put("min", 0).put("max", 3).put("step", 1).put("default", 1)
                .put("usage", "predicate:edge:GTE");
        gene.put("content_sha256", JsonHashes.ownHash(gene));
        ObjectNode registry = MAPPER.createObjectNode().put("schema", "strategy-v5-predictor-registry/1")
                .put("version", 1).put("status", "FROZEN");
        registry.putArray("predictors").addObject().put("id", "edge").put("scalar_type", "number")
                .put("source_field", "close").put("source_family", "TEST_PIT_FEATURE")
                .put("availability_derivation", "completed bar close").put("pit_role", "PREDICTOR")
                .put("lookback_ms", 0).put("code_sha256", JsonHashes.sha256("predictor-code"))
                .put("config_sha256", JsonHashes.sha256("predictor-config"));
        registry.put("content_sha256", JsonHashes.ownHash(registry));
        ObjectNode candidate = MAPPER.createObjectNode().put("direction", "long")
                .put("entry_policy", "NEXT_BAR_OPEN").put("lifecycle_timeframe", "1m")
                .put("max_lifecycle_ms", 120_000).put("risk_amount_usd", 10);
        candidate.putObject("exit_policy").put("type", "TIME_STOP");
        ObjectNode contract = MAPPER.createObjectNode();
        contract.putObject("sizing_contract").put("mode", "FIXED_NOTIONAL_USD").put("notional_usd", 10);
        ObjectNode options = MAPPER.createObjectNode().put("strategyFamily", "test-family")
                .put("precommitSha256", JsonHashes.sha256("precommit"));
        options.set("geneSpace", gene); options.set("predictorRegistry", registry);
        options.set("predicate", predicate("GTE", MAPPER.createObjectNode().put("$gene", "threshold")));
        options.set("candidateTemplate", candidate); options.set("executionContract", contract);
        return options;
    }

    private static ObjectNode metadata() {
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.set("contract_spec", receipt("CONTRACT_SPEC", MAPPER.createObjectNode().put("contract_multiplier", 1)));
        metadata.set("fee_schedule", receipt("FEE_SCHEDULE", MAPPER.createObjectNode().put("taker_fee_rate", .001)));
        ObjectNode model = MAPPER.createObjectNode().put("slippage_bps", 0).put("impact_bps", 0)
                .put("outage_policy", "FAIL").put("gap_policy", "FAIL");
        metadata.set("execution_model", receipt("EXECUTION_MODEL", model));
        return metadata;
    }

    private static ObjectNode receipt(String kind, ObjectNode terms) {
        ObjectNode row = MAPPER.createObjectNode().put("asset", "btc").put("venue", "BINANCE")
                .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                .put("effective_from", "2025-01-01T00:00:00.000Z")
                .put("effective_to", "2027-01-01T00:00:00.000Z")
                .put("availability_time", "2025-01-01T00:00:00.000Z");
        terms.fields().forEachRemaining(entry -> row.set(entry.getKey(), entry.getValue()));
        ObjectNode receipt = MAPPER.createObjectNode().put("schema", "strategy-v5-metadata-receipt/1")
                .put("version", 1).put("kind", kind).put("status", "CONSERVATIVE_MODEL")
                .putNull("plan_sha256").put("captured_at", "2025-01-01T00:00:00.000Z")
                .putNull("source").putNull("source_receipt_sha256").putNull("source_byte_sha256")
                .put("model_sha256", JsonHashes.sha256(kind + ":model"))
                .put("precommit_sha256", JsonHashes.sha256("precommit"))
                .put("provenance_mode", "MODEL_BOUND");
        receipt.putArray("records").add(row); receipt.putNull("coverage"); receipt.putArray("limitations");
        receipt.put("authoritative", false).put("content_sha256", JsonHashes.ownHash(receipt));
        return receipt;
    }

    private static void bar(ArrayNode bars, int minute, double open, double high, double low, double close) {
        String event = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(minute * 60L).toString();
        String available = Instant.parse("2026-01-01T00:00:59.999Z").plusSeconds(minute * 60L).toString();
        bars.addObject().put("event_time", event).put("availability_time", available)
                .put("open", open).put("high", high).put("low", low).put("close", close);
    }

    private static ObjectNode identityRow(String episode, String decision) {
        return MAPPER.createObjectNode().put("asset", "btc").put("venue", "binance")
                .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT").put("signal_id", "signal-1")
                .put("episode_id", episode).put("decision_time", decision);
    }

    private static ObjectNode predicate(String op, JsonNode expected) {
        return MAPPER.createObjectNode().put("predictor_id", "edge").put("op", op).set("value", expected);
    }

    private static ObjectNode request(String action) { return MAPPER.createObjectNode().put("action", action); }

    private static ObjectNode nodeValue(ObjectNode request) throws Exception {
        JsonNode response = oracle(request);
        assertThat(response.path("ok").asBoolean()).describedAs(response.toString()).isTrue();
        return (ObjectNode) response.path("value");
    }

    private static JsonNode oracle(ObjectNode request) throws Exception {
        String key = JsonHashes.canonicalSha256(request);
        JsonNode response = frozenJson("/oracles/strategy-evaluator-v5.json").get(key);
        assertThat(response).as("missing frozen evaluator oracle for " + key).isNotNull();
        return response.deepCopy();
    }

    private static ObjectNode frozenPhysicalNullFixture() throws IOException {
        return (ObjectNode) frozenJson(
                "/oracles/strategy-evaluator-v5-physical-null.json");
    }

    private static JsonNode frozenJson(String resource) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                StrategyEvaluatorV5NodeOracleTest.class.getResourceAsStream(resource),
                "frozen evaluator oracle is missing: " + resource)) {
            return MAPPER.readTree(input);
        }
    }

    private static void assertSameFailure(ObjectNode request, Runnable javaCall) throws Exception {
        JsonNode expected = oracle(request);
        assertThat(expected.path("ok").asBoolean()).describedAs(expected.toString()).isFalse();
        assertThatThrownBy(javaCall::run).hasMessage(expected.path("error").asText());
    }

    private static void assertJson(JsonNode actual, JsonNode expected) {
        assertThat(CanonicalJson.canonicalize(actual)).isEqualTo(CanonicalJson.canonicalize(expected));
    }

}
