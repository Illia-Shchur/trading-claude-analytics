package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StrategyEvaluatorV5AuthoritativeTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @TempDir Path temporary;

    @Test
    void parquetRolesRunThroughDeterministicConcurrentWorkersAndContentAddressedCache() throws Exception {
        Fixture fixture = physicalFixture();
        Path cacheOne = temporary.resolve("cache-one");
        ObjectNode loadOne = fixture.loadOptions(cacheOne, 2);
        try (StrategyEvaluatorV5.LoadedEvaluator first = StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(loadOne)) {
            assertThat(StrategyEvaluatorV5.isVerifiedPhysicalEvaluator(first.evaluator())).isTrue();
            assertThat(first.provenance().path("role_read_mode").asText()).isEqualTo("FULL_ROLE_BOUNDED");
            assertThat(first.evaluator().workerProvenance().path("physical_role_binding").asBoolean()).isTrue();
            assertThat(first.evaluator().physicalNullSelectionVerified()).isTrue();

            ObjectNode thresholdOne = fixture.task(1);
            ObjectNode thresholdTwo = fixture.task(2);
            List<ObjectNode> batch = first.evaluator().evaluateBatch(List.of(thresholdTwo, thresholdOne));
            assertThat(batch).hasSize(2);
            assertThat(batch).allSatisfy(value -> assertThat(
                    value.path("candidate_returns").path("episode-1").path("traded").asBoolean()).isFalse());
            ObjectNode diagnostics = first.diagnostics();
            assertThat(diagnostics.path("worker_count").asInt()).isEqualTo(2);
            assertThat(diagnostics.path("peak_in_flight").asInt()).isEqualTo(2);
            assertThat(diagnostics.path("concurrent_dispatch").asBoolean()).isTrue();
            assertThat(diagnostics.path("disk_cache_write_count").asInt()).isEqualTo(2);

            ObjectNode repeated = first.evaluator().evaluate(thresholdOne);
            assertThat(repeated).isEqualTo(batch.get(1));
            assertThat(first.diagnostics().path("cache_hit_count").asInt()).isEqualTo(1);
        }

        try (StrategyEvaluatorV5.LoadedEvaluator reopened = StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(
                fixture.loadOptions(cacheOne, 1))) {
            ObjectNode retained = reopened.evaluator().evaluate(fixture.task(1));
            assertThat(retained.path("candidate_returns").path("episode-1").path("traded").asBoolean()).isFalse();
            assertThat(reopened.diagnostics().path("disk_cache_hit_count").asInt()).isEqualTo(1);
            assertThat(reopened.diagnostics().path("cache_hit_count").asInt()).isEqualTo(1);
            assertThat(reopened.diagnostics().path("evaluation_count").asInt()).isZero();
        }
    }

    @Test
    void episodeScopedReadAndEveryDeclaredBoundFailClosedBeforeDispatch() throws Exception {
        Fixture fixture = physicalFixture();
        ObjectNode scoped = fixture.loadOptions(temporary.resolve("scoped"), 1);
        scoped.putArray("episodeIds").add("episode-1");
        try (StrategyEvaluatorV5.LoadedEvaluator loaded = StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(scoped)) {
            assertThat(loaded.provenance().path("role_read_mode").asText()).isEqualTo("EPISODE_SCOPED_BOUNDED");
            assertThat(loaded.provenance().path("episode_inventory_sha256").asText()).matches("[a-f0-9]{64}");
        }

        ObjectNode badWorkers = fixture.loadOptions(temporary.resolve("workers"), 5);
        assertThatThrownBy(() -> StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(badWorkers))
                .hasMessage("authoritative evaluator worker count must be 1..4");

        ObjectNode rowBound = fixture.loadOptions(temporary.resolve("row-bound"), 1);
        rowBound.put("maxRowsPerRole", 0);
        assertThatThrownBy(() -> StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(rowBound))
                .hasMessage("bounded evaluator read configuration is invalid");

        ObjectNode resultBound = fixture.loadOptions(temporary.resolve("result-bound"), 1);
        resultBound.put("maxResultBytes", 1_023);
        assertThatThrownBy(() -> StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(resultBound))
                .hasMessage("authoritative evaluator result bound is invalid");

        ObjectNode memoryBound = fixture.loadOptions(temporary.resolve("memory-bound"), 4);
        memoryBound.put("maxAggregateWorkerBytes", 16 * 1_024 * 1_024);
        // The tiny fixture fits; prove the lower validation boundary itself is inclusive.
        try (StrategyEvaluatorV5.LoadedEvaluator ignored = StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(memoryBound)) {
            assertThat(ignored.diagnostics().path("worker_count").asInt()).isEqualTo(4);
        }
        memoryBound.put("maxAggregateWorkerBytes", 16 * 1_024 * 1_024 - 1);
        assertThatThrownBy(() -> StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(memoryBound))
                .hasMessage("authoritative evaluator aggregate worker memory bound is invalid");

        ObjectNode fractionalRead = fixture.loadOptions(temporary.resolve("fractional-read"), 1);
        fractionalRead.put("batchRows", 1.5);
        assertThatThrownBy(() -> StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(fractionalRead))
                .hasMessage("bounded evaluator read configuration is invalid");

        ObjectNode fractionalResult = fixture.loadOptions(temporary.resolve("fractional-result"), 1);
        fractionalResult.put("maxResultBytes", 1_024.5);
        assertThatThrownBy(() -> StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(fractionalResult))
                .hasMessage("authoritative evaluator result bound is invalid");

        ObjectNode fractionalAggregate = fixture.loadOptions(temporary.resolve("fractional-aggregate"), 1);
        fractionalAggregate.put("maxAggregateWorkerBytes", 16 * 1_024 * 1_024 + .5);
        assertThatThrownBy(() -> StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(fractionalAggregate))
                .hasMessage("authoritative evaluator aggregate worker memory bound is invalid");
    }

    @Test
    void roleAndCacheSymlinkHardlinkAndByteTamperingCannotRedirectCustody() throws Exception {
        Fixture fixture = physicalFixture();
        ObjectNode tampered = fixture.loadOptions(temporary.resolve("tampered-cache"), 1);
        Files.writeString(fixture.root.resolve("feature.parquet"), "tamper",
                java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(tampered))
                .hasMessage("authoritative feature artifact bytes changed");

        fixture = physicalFixture();
        Path symlink = fixture.root.resolve("feature-link.parquet");
        try {
            Files.createSymbolicLink(symlink, Path.of("feature.parquet"));
        } catch (UnsupportedOperationException failure) {
            return;
        }
        ObjectNode symlinkManifest = fixture.manifest.deepCopy();
        ((ObjectNode) symlinkManifest.path("artifacts").path("feature")).put("path", "feature-link.parquet");
        symlinkManifest.put("content_sha256", JsonHashes.ownHash(symlinkManifest));
        ObjectNode linked = fixture.loadOptions(temporary.resolve("linked-cache"), 1);
        linked.set("manifest", symlinkManifest);
        assertThatThrownBy(() -> StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(linked))
                .hasMessage("Parquet role path contains a symlink");

        Path hardlink = fixture.root.resolve("label-hardlink.parquet");
        Files.createLink(hardlink, fixture.root.resolve("label.parquet"));
        ObjectNode hardlinkManifest = fixture.manifest.deepCopy();
        ((ObjectNode) hardlinkManifest.path("artifacts").path("label")).put("path", "label-hardlink.parquet");
        hardlinkManifest.put("content_sha256", JsonHashes.ownHash(hardlinkManifest));
        ObjectNode linkedHard = fixture.loadOptions(temporary.resolve("hard-cache"), 1);
        linkedHard.set("manifest", hardlinkManifest);
        assertThatThrownBy(() -> StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(linkedHard))
                .hasMessage("Parquet role path is not a regular single-link file");
    }

    @Test
    void canonicalLifecycleReopensRoleAndMetadataCustodyBeforeEveryEvaluation() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("lifecycle-custody"));
        ObjectNode options = canonicalLifecycleSpecOptions();
        ObjectNode spec = StrategyEvaluatorV5.makeEvaluatorSpecV5(options);
        ObjectNode binding = MAPPER.createObjectNode()
                .put("sourceArtifactSha256", JsonHashes.sha256("canonical-lifecycle-source"))
                .put("physicalRoot", root.toString());
        binding.set("evaluatorSpec", spec);
        binding.set("geneSpace", options.path("geneSpace"));
        binding.set("predictorRegistry", options.path("predictorRegistry"));

        ObjectNode feature = identity("episode-lifecycle");
        feature.put("event_time", "2026-01-01T00:00:00.000Z")
                .put("availability_time", "2026-01-01T00:00:00.000Z")
                .put("signal_eligible", true).put("edge", 2);
        ObjectNode label = identity("episode-lifecycle");
        label.put("availability_time", "2026-01-01T00:02:59.999Z")
                .put("resolution_ceiling_time", "2026-01-01T00:02:00.000Z");
        ObjectNode execution = identity("episode-lifecycle");
        execution.put("availability_time", "2026-01-01T00:02:59.999Z")
                .put("interval_ms", 60_000);
        execution.putObject("capacity_inputs").put("available_liquidity_usd", 10_000)
                .put("participation_cap", .25).put("order_notional_usd", 100);
        ArrayNode bars = execution.putArray("child_bars");
        lifecycleBar(bars, 0, 100, 101, 99, 100);
        lifecycleBar(bars, 1, 100, 102, 99, 101);
        binding.putArray("features").add(feature);
        binding.putArray("labels").add(label);
        binding.putArray("execution").add(execution);

        ObjectNode artifacts = binding.putObject("roleArtifacts");
        for (String role : List.of("feature", "label", "execution")) {
            Path file = root.resolve(role + ".role");
            Files.writeString(file, role + "\n");
            artifacts.putObject(role).put("path", file.getFileName().toString())
                    .put("sha256", JsonHashes.sha256(Files.readAllBytes(file)))
                    .put("bytes", Files.size(file));
        }
        MetadataFixture metadata = physicalMetadata(root);
        binding.set("metadata", metadata.metadata());

        ObjectNode view = MAPPER.createObjectNode().put("schema", "strategy-v5-statistical-signal-view/1")
                .put("source_artifact_sha256", binding.path("sourceArtifactSha256").asText())
                .put("phase", "TRAIN_ONLY").put("fold_id", "fold-lifecycle");
        view.putArray("episode_ids").add("episode-lifecycle");
        view.putArray("episodes").addObject().put("episode_id", "episode-lifecycle")
                .put("asset", "btc").put("decision_time", "2026-01-01T00:00:00.000Z")
                .put("phase", "TRAIN_ONLY").put("fold_id", "fold-lifecycle");
        ObjectNode task = MAPPER.createObjectNode().set("artifact", view);
        task.putArray("episode_ids").add("episode-lifecycle");
        task.putObject("chromosome").put("threshold", 1);
        task.put("phase", "TRAIN_ONLY").put("fold_id", "fold-lifecycle")
                .put("cutoff", "2026-01-01T00:03:59.999Z");

        try (StrategyEvaluatorV5.Evaluator evaluator =
                StrategyEvaluatorV5.createVerifiedWorkerEvaluatorV5(binding)) {
            ObjectNode result = evaluator.evaluate(task);
            assertThat(result.path("candidate_returns").path("episode-lifecycle").path("traded").asBoolean())
                    .isTrue();
            assertThat(result.path("candidate_returns").path("episode-lifecycle").path("net_r").asDouble())
                    .isFinite();
            Files.writeString(metadata.normalizedReceipt(), "\n", java.nio.file.StandardOpenOption.APPEND);
            assertThatThrownBy(() -> evaluator.evaluate(task))
                    .hasMessageContaining("metadata source receipt bytes changed");
        }
    }

    @Test
    void authoritativeMarkAndMetadataInstallTheOpaqueScopeIndependentOutcomeCapability() throws Exception {
        Fixture fixture = physicalFixture();
        Path mark = fixture.root.resolve("mark.parquet");
        Files.writeString(mark, "physical-mark-role\n");
        ObjectNode manifest = fixture.manifest.deepCopy();
        ObjectNode markArtifact = ((ObjectNode) manifest.path("artifacts")).putObject("mark");
        markArtifact.put("path", mark.getFileName().toString())
                .put("sha256", JsonHashes.sha256(Files.readAllBytes(mark))).put("bytes", Files.size(mark));
        manifest.put("content_sha256", JsonHashes.ownHash(manifest));
        MetadataFixture metadata = physicalMetadata(fixture.root);
        ObjectNode load = fixture.loadOptions(temporary.resolve("scope-independent"), 1);
        load.set("manifest", manifest);
        load.set("metadata", metadata.metadata());

        try (StrategyEvaluatorV5.LoadedEvaluator loaded = StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(load)) {
            var registry = StrategyEvaluatorV5.physicalTrustRegistryV5();
            var capability = registry.getInternalScopeIndependentOutcomeCapability(loaded.evaluator());
            assertThat(capability).isNotNull();
            assertThat(capability.proof().path("content_sha256").asText())
                    .isEqualTo(JsonHashes.ownHash(capability.proof()));
            assertThat(capability.descriptor().path("data_bindings").path("mark_artifact_sha256").asText())
                    .isEqualTo(markArtifact.path("sha256").asText());

            Map<String, Object> context = new LinkedHashMap<>();
            context.put("sourceArtifactSha256", manifest.path("content_sha256").asText());
            context.put("evaluatorSpecSha256", fixture.spec.path("content_sha256").asText());
            context.put("outcomeProofSha256", capability.proof().path("content_sha256").asText());
            context.put("dataBindings", MAPPER.convertValue(
                    capability.descriptor().path("data_bindings"), Map.class));
            Files.writeString(mark, "tamper", java.nio.file.StandardOpenOption.APPEND);
            assertThatThrownBy(() -> capability.beginEvaluationScope(context))
                    .hasMessageContaining("mark bytes are missing or tampered");
        }
    }

    @Test
    void loadedEvaluatorAloneMintsReopenableBaseAndStressLifecycleCapabilities() throws Exception {
        Fixture fixture = physicalFixture(canonicalLifecycleSpecOptions());
        MetadataFixture metadata = physicalMetadata(fixture.root);
        ObjectNode load = fixture.loadOptions(temporary.resolve("lifecycle-capability"), 1);
        load.set("metadata", metadata.metadata());
        ObjectNode execution = identity("episode-lifecycle-capability");
        execution.put("entry_time", execution.path("decision_time").asText());
        execution.put("availability_time", "2026-01-01T00:02:59.999Z")
                .put("interval_ms", 60_000).put("entry_policy", "NEXT_BAR_OPEN")
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("lifecycle_timeframe", "1m")
                .put("max_lifecycle_ms", 120_000);
        execution.putObject("capacity_inputs").put("available_liquidity_usd", 10_000)
                .put("participation_cap", .25).put("order_notional_usd", 100);
        ArrayNode bars = execution.putArray("child_bars");
        lifecycleBar(bars, 0, 100, 101, 99, 100);
        lifecycleBar(bars, 1, 100, 102, 99, 101);

        StrategyEvaluatorV5.LoadedEvaluator loaded = StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(load);
        assertThat(loaded.createLifecycleTrustToken(execution)).isNotNull();

        ObjectNode feature = identity("episode-lifecycle-capability");
        feature.put("event_time", "2026-01-01T00:00:00.000Z")
                .put("availability_time", "2026-01-01T00:00:00.000Z")
                .put("signal_eligible", true).put("edge", 2);
        ObjectNode label = identity("episode-lifecycle-capability");
        label.put("entry_time", "2026-01-01T00:00:00.000Z")
                .put("resolution_time", "2026-01-01T00:01:00.000Z")
                .put("resolution_ceiling_time", "2026-01-01T00:01:00.000Z")
                .put("availability_time", "2026-01-01T00:02:59.999Z");
        ObjectNode candidate = ((ObjectNode) fixture.spec.path("candidate_template")).deepCopy();
        ObjectNode executionContract = (ObjectNode) fixture.spec.path("execution_contract");
        candidate.set("decision_timestamp_convention",
                executionContract.path("decision_timestamp_convention").deepCopy());
        candidate.set("decision_timeframe", executionContract.path("decision_timeframe").deepCopy());
        ObjectNode risk = ((ObjectNode) executionContract.path("risk_convention")).deepCopy();
        risk.put("evaluator_spec_sha256", fixture.spec.path("content_sha256").asText());
        candidate.set("risk_contract", risk);
        ObjectNode sizing = ((ObjectNode) executionContract.path("sizing_contract")).deepCopy();
        sizing.put("evaluator_spec_sha256", fixture.spec.path("content_sha256").asText());
        candidate.set("sizing_contract", sizing);
        ObjectNode outcomeRequest = MAPPER.createObjectNode();
        outcomeRequest.set("feature", feature); outcomeRequest.set("label", label);
        outcomeRequest.set("execution", execution); outcomeRequest.set("candidate", candidate);
        assertThat(loaded.deriveBoundExecutionOutcome(outcomeRequest).path("traded").asBoolean()).isTrue();

        ObjectNode overrides = MAPPER.createObjectNode();
        ObjectNode feeRow = ((ObjectNode) metadata.metadata().path("fee_schedule")
                .path("records").get(0)).deepCopy().put("taker_fee_rate", .002);
        ObjectNode fee = stressMetadata(fixture.root.resolve("stress-fee.json"),
                "FEE_SCHEDULE", feeRow);
        ObjectNode modelRow = ((ObjectNode) metadata.metadata().path("execution_model")
                .path("records").get(0)).deepCopy().put("impact_bps", 3);
        ObjectNode model = stressMetadata(fixture.root.resolve("stress-model.json"),
                "EXECUTION_MODEL", modelRow);
        overrides.set("fee_schedule", fee);
        overrides.set("execution_model", model);
        assertThat(loaded.createLifecycleStressTrustToken(execution, overrides)).isNotNull();
        assertThat(loaded.deriveBoundStressExecutionOutcome(outcomeRequest, overrides)
                .path("traded").asBoolean()).isTrue();

        ((ObjectNode) overrides.path("fee_schedule")).put("authoritative", false);
        assertThatThrownBy(() -> loaded.createLifecycleStressTrustToken(execution, overrides))
                .hasMessageContaining("stress metadata is not loader-bound");
        loaded.close();
        assertThatThrownBy(() -> loaded.createLifecycleTrustToken(execution))
                .hasMessageContaining("evaluator is closed");
    }

    private static ObjectNode stressMetadata(Path path, String kind, ObjectNode row) {
        String sourceHash = JsonHashes.sha256("stress-source-" + kind);
        ObjectNode options = MAPPER.createObjectNode().put("kind", kind)
                .put("capturedAt", "2026-01-01T00:00:00.000Z")
                .put("fixtureOnly", false).put("status", "PUBLIC_OBSERVED")
                .put("sourceReceiptSha256", sourceHash);
        options.set("sourceByteSha256", MAPPER.createArrayNode().add(sourceHash));
        options.set("source", MAPPER.createObjectNode().put("provider", "PHYSICAL_STRESS_TEST")
                .put("kind", kind));
        options.set("records", MAPPER.createArrayNode().add(row));
        return ((ObjectNode) StrategyPortfolioRiskV5.writeMetadataArtifact(path, options)
                .path("artifact")).deepCopy();
    }

    private Fixture physicalFixture() throws Exception {
        return physicalFixture(specOptions());
    }

    private Fixture physicalFixture(ObjectNode specOptions) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("lake-" + System.nanoTime()));
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = connection.createStatement()) {
            String identity = "'btc' AS asset, 'binance' AS venue, 'BINANCE_SPOT' AS instrument, "
                    + "'BTCUSDT' AS symbol, 'signal-1' AS signal_id, 'episode-1' AS episode_id, "
                    + "'2026-01-01T00:00:00.000Z' AS decision_time";
            statement.execute("COPY (SELECT " + identity
                    + ", '2026-01-01T00:00:00.000Z' AS event_time, "
                    + "'2026-01-01T00:00:00.000Z' AS availability_time, true AS signal_eligible, 0.0 AS edge) "
                    + "TO '" + sql(root.resolve("feature.parquet")) + "' (FORMAT PARQUET)");
            statement.execute("COPY (SELECT " + identity
                    + ", '2026-01-01T00:00:00.000Z' AS entry_time, "
                    + "'2026-01-01T00:02:00.000Z' AS resolution_ceiling_time, "
                    + "'2026-01-01T00:02:59.999Z' AS availability_time) "
                    + "TO '" + sql(root.resolve("label.parquet")) + "' (FORMAT PARQUET)");
            statement.execute("COPY (SELECT " + identity
                    + ", '2026-01-01T00:02:59.999Z' AS availability_time) "
                    + "TO '" + sql(root.resolve("execution.parquet")) + "' (FORMAT PARQUET)");
        }
        ObjectNode spec = StrategyEvaluatorV5.makeEvaluatorSpecV5(specOptions);
        ObjectNode manifest = MAPPER.createObjectNode().put("schema", "strategy-v5-separated-parquet/1")
                .put("status", "AUTHORITATIVE_PARQUET").put("authoritative", true)
                .put("predictor_registry_sha256", specOptions.path("predictorRegistry").path("content_sha256").asText())
                .put("precommit_sha256", spec.path("precommit_sha256").asText())
                .put("dataset_root_sha256", JsonHashes.sha256("dataset"));
        ObjectNode artifacts = manifest.putObject("artifacts");
        artifact(artifacts, root, "feature"); artifact(artifacts, root, "label"); artifact(artifacts, root, "execution");
        manifest.put("content_sha256", JsonHashes.ownHash(manifest));
        return new Fixture(root, manifest, specOptions, spec);
    }

    private static void artifact(ObjectNode artifacts, Path root, String role) throws Exception {
        Path file = root.resolve(role + ".parquet");
        artifacts.putObject(role).put("path", file.getFileName().toString())
                .put("sha256", JsonHashes.sha256(Files.readAllBytes(file))).put("bytes", Files.size(file));
    }

    private static ObjectNode specOptions() {
        ObjectNode gene = MAPPER.createObjectNode();
        gene.putArray("genes").addObject().put("name", "threshold").put("type", "continuous")
                .put("min", 0).put("max", 3).put("step", 1).put("default", 1);
        gene.put("content_sha256", JsonHashes.ownHash(gene));
        ObjectNode registry = MAPPER.createObjectNode().put("schema", "strategy-v5-predictor-registry/1")
                .put("version", 1).put("status", "FROZEN");
        registry.putArray("predictors").addObject().put("id", "edge").put("scalar_type", "number")
                .put("source_field", "close").put("source_family", "TEST_PIT_FEATURE")
                .put("availability_derivation", "completed bar close").put("pit_role", "PREDICTOR")
                .put("lookback_ms", 0).put("code_sha256", JsonHashes.sha256("predictor-code"))
                .put("config_sha256", JsonHashes.sha256("predictor-config"));
        registry.put("content_sha256", JsonHashes.ownHash(registry));
        ObjectNode predicate = MAPPER.createObjectNode().put("predictor_id", "edge").put("op", "GTE");
        predicate.putObject("value").put("$gene", "threshold");
        ObjectNode candidate = MAPPER.createObjectNode().put("direction", "long")
                .put("entry_policy", "NEXT_BAR_OPEN").put("lifecycle_timeframe", "1m")
                .put("max_lifecycle_ms", 120_000).put("risk_amount_usd", 10);
        candidate.putObject("exit_policy").put("type", "TIME_STOP");
        ObjectNode contract = MAPPER.createObjectNode();
        contract.putObject("sizing_contract").put("mode", "FIXED_NOTIONAL_USD").put("notional_usd", 10);
        ObjectNode options = MAPPER.createObjectNode().put("strategyFamily", "physical-fixture")
                .put("precommitSha256", JsonHashes.sha256("precommit"));
        options.set("geneSpace", gene); options.set("predictorRegistry", registry); options.set("predicate", predicate);
        options.set("candidateTemplate", candidate); options.set("executionContract", contract);
        return options;
    }

    private static ObjectNode canonicalLifecycleSpecOptions() {
        ObjectNode options = specOptions();
        ObjectNode candidate = (ObjectNode) options.path("candidateTemplate");
        candidate.remove("risk_amount_usd");
        ObjectNode lifecycle = candidate.putObject("lifecycle").put("max_lifecycle_ms", 120_000);
        lifecycle.putObject("stop").put("type", "PERCENT").put("value", .05);
        lifecycle.putObject("sizing").put("mode", "FIXED_NOTIONAL_USD").put("notional_usd", 100);
        ObjectNode contract = (ObjectNode) options.path("executionContract");
        contract.putObject("risk_convention").put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", 10);
        ((ObjectNode) contract.path("sizing_contract")).put("notional_usd", 100);
        return options;
    }

    private static MetadataFixture physicalMetadata(Path root) throws Exception {
        String rootReference = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                .relativize(root.toAbsolutePath().normalize()).toString();
        ObjectNode metadata = MAPPER.createObjectNode().put("source_root", root.toString());
        Path first = null;
        for (String kind : List.of("CONTRACT_SPEC", "FEE_SCHEDULE", "EXECUTION_MODEL")) {
            String stem = kind.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            Path normalizedPath = root.resolve(stem + "-normalized.json");
            ObjectNode normalized = MAPPER.createObjectNode()
                    .put("schema", "strategy-v5-normalized-source/1").put("kind", kind);
            normalized.putArray("raw_receipts");
            normalized.put("content_sha256", JsonHashes.ownHash(normalized));
            MAPPER.writeValue(normalizedPath.toFile(), normalized);
            if (first == null) first = normalizedPath;

            ObjectNode row = MAPPER.createObjectNode().put("asset", "btc").put("venue", "BINANCE")
                    .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                    .put("effective_from", "2025-01-01T00:00:00.000Z")
                    .put("effective_to", "2027-01-01T00:00:00.000Z")
                    .put("availability_time", "2025-01-01T00:00:00.000Z");
            if ("CONTRACT_SPEC".equals(kind)) row.put("contract_multiplier", 1).put("step_size", .001)
                    .put("min_qty", .001).put("min_notional", 1).put("max_notional", 1_000_000)
                    .put("max_qty", 100_000);
            if ("FEE_SCHEDULE".equals(kind)) row.put("taker_fee_rate", .001);
            if ("EXECUTION_MODEL".equals(kind)) row.put("slippage_bps", 1).put("impact_bps", 1)
                    .put("outage_policy", "FAIL").put("gap_policy", "FAIL");
            ObjectNode receipt = MAPPER.createObjectNode().put("schema", "strategy-v5-metadata-receipt/1")
                    .put("version", 1).put("kind", kind).put("status", "PUBLIC_OBSERVED")
                    .put("authoritative", true).put("source_root_reference", rootReference);
            receipt.putArray("records").add(row);
            receipt.putArray("source_receipts").addObject().put("path", normalizedPath.getFileName().toString())
                    .put("content_sha256", normalized.path("content_sha256").asText())
                    .put("byte_sha256", JsonHashes.sha256(Files.readAllBytes(normalizedPath)))
                    .put("bytes", Files.size(normalizedPath));
            receipt.put("content_sha256", JsonHashes.ownHash(receipt));
            metadata.set(kind.equals("CONTRACT_SPEC") ? "contract_spec"
                    : kind.equals("FEE_SCHEDULE") ? "fee_schedule" : "execution_model", receipt);
        }
        return new MetadataFixture(metadata, first);
    }

    private static ObjectNode identity(String episode) {
        return MAPPER.createObjectNode().put("asset", "btc").put("venue", "binance")
                .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                .put("signal_id", "signal-1").put("episode_id", episode)
                .put("decision_time", "2026-01-01T00:00:00.000Z");
    }

    private static void lifecycleBar(ArrayNode bars, int minute, double open, double high, double low, double close) {
        String event = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(minute * 60L).toString();
        bars.addObject().put("event_time", event).put("open", open).put("high", high)
                .put("low", low).put("close", close);
    }

    private static String sql(Path path) { return path.toAbsolutePath().toString().replace("'", "''"); }

    private record Fixture(Path root, ObjectNode manifest, ObjectNode specOptions, ObjectNode spec) {
        ObjectNode loadOptions(Path cache, int workers) {
            ObjectNode value = MAPPER.createObjectNode().put("root", root.toString()).put("cacheRoot", cache.toString())
                    .put("workerCount", workers).put("maxRowsPerRole", 10)
                    .put("maxMaterializedBytesPerRole", 1_000_000);
            value.set("manifest", manifest.deepCopy()); value.set("evaluatorSpec", spec.deepCopy());
            value.set("geneSpace", specOptions.path("geneSpace").deepCopy());
            value.set("predictorRegistry", specOptions.path("predictorRegistry").deepCopy());
            return value;
        }

        ObjectNode task(int threshold) {
            ObjectNode view = MAPPER.createObjectNode().put("schema", "strategy-v5-statistical-signal-view/1")
                    .put("source_artifact_sha256", manifest.path("content_sha256").asText())
                    .put("phase", "TRAIN_ONLY").put("fold_id", "fold-1");
            view.putArray("episode_ids").add("episode-1");
            view.putArray("episodes").addObject().put("episode_id", "episode-1").put("asset", "btc")
                    .put("decision_time", "2026-01-01T00:00:00.000Z").put("phase", "TRAIN_ONLY")
                    .put("fold_id", "fold-1");
            ObjectNode task = MAPPER.createObjectNode(); task.set("artifact", view);
            task.putArray("episode_ids").add("episode-1"); task.putObject("chromosome").put("threshold", threshold);
            task.put("phase", "TRAIN_ONLY").put("fold_id", "fold-1")
                    .put("cutoff", "2026-01-01T00:03:59.999Z");
            return task;
        }
    }

    private record MetadataFixture(ObjectNode metadata, Path normalizedReceipt) {}
}
