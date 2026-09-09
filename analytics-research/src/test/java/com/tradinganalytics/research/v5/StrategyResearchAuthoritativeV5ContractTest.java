package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Small public-contract checks for the authoritative orchestration facade.
 * These fixtures exercise custody and state transitions without opening a
 * physical data or statistical evaluation run.
 */
final class StrategyResearchAuthoritativeV5ContractTest {
    @TempDir Path temporary;

    @Test
    void coverageReportEmitsPlanOnlyStateAndRejectsPlanTampering() {
        ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(
                JsonHashes.mapper().createObjectNode()
                        .put("asOf", "2026-08-24T20:30:00.000Z")
                        .put("rootReference", "contract-fixture"));
        ObjectNode options = JsonHashes.mapper().createObjectNode()
                .put("mode", "PLAN_ONLY")
                .put("capturedAt", "2026-08-24T20:30:00.000Z");
        options.set("plan", plan);

        ObjectNode planned = StrategyResearchAuthoritativeV5.coverageReport(options);

        assertThat(planned.path("status").asText()).isEqualTo("PLANNED");
        assertThat(planned.path("limitations").toString()).contains("NO_DATA_ROWS_ACQUIRED");
        assertThat(planned.path("series").size()).isGreaterThan(0);
        assertThat(planned.path("content_sha256").asText())
                .isEqualTo(StrategyResearchAuthoritativeV5.ownHash(planned));

        ObjectNode tamperedPlan = plan.deepCopy().put("content_sha256", "a".repeat(64));
        ObjectNode tamperedOptions = options.deepCopy();
        tamperedOptions.set("plan", tamperedPlan);
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.coverageReport(tamperedOptions))
                .hasMessageContaining("hash-valid authoritative plan");
    }

    @Test
    void coverageReportDistinguishesObservedAcquisitionAndCatalogOnlyStates() {
        ObjectNode catalog = object().put("schema", "strategy-v5-dated-futures-catalog/2")
                .put("version", 2).put("captured_at", "2026-08-24T20:30:00.000Z")
                .put("status", "PUBLIC_OBSERVED_UNAVAILABLE");
        ObjectNode source = object().put("endpoint", "https://data.binance.com/catalog")
                .put("listing_response_set_sha256", "a".repeat(64))
                .put("listing_format", "S3_XML_DELIMITER")
                .put("persistence_status", "HASH_ONLY_UNVERIFIABLE");
        source.set("raw_receipts", array());
        source.set("raw_receipt_sha256", array());
        source.set("raw_receipt_byte_sha256", array());
        catalog.set("source", source);
        catalog.set("requested_assets", JsonHashes.mapper().valueToTree(
                List.of("ada", "avax", "btc", "doge", "eth", "link", "sol", "xrp")));
        catalog.set("contracts", array());
        catalog.set("responses", array());
        catalog.set("limitations", JsonHashes.mapper().createArrayNode());
        catalog.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(catalog));
        ObjectNode planArgs = object().put("asOf", "2026-08-24T20:30:00.000Z")
                .put("rootReference", "catalog-contract");
        planArgs.set("datedFuturesCatalog", catalog);
        ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(planArgs);
        ObjectNode catalogOptions = object().put("mode", "CATALOG_ONLY_PLAN")
                .put("capturedAt", "2026-08-24T20:30:00.000Z");
        catalogOptions.set("plan", plan);
        catalogOptions.set("catalog", catalog);
        ObjectNode catalogResult = StrategyResearchAuthoritativeV5.coverageReport(catalogOptions);
        assertThat(catalogResult.path("status").asText()).isEqualTo("OBSERVED_PARTIAL");
        assertThat(catalogResult.path("dated_futures").size()).isEqualTo(8);
        assertThat(catalogResult.path("limitations").toString()).contains("NO_DATA_ROWS_ACQUIRED");

        ObjectNode acquisition = acquisitionManifest(plan);
        ObjectNode observedOptions = object().put("mode", "DOWNLOAD_AND_AUTHORITATIVE_REOPEN")
                .put("capturedAt", "2026-08-24T20:30:00.000Z");
        observedOptions.set("plan", plan);
        observedOptions.set("acquisition", acquisition);
        ObjectNode observed = StrategyResearchAuthoritativeV5.coverageReport(observedOptions);
        assertThat(observed.path("status").asText()).isEqualTo("OBSERVED_PARTIAL");
        assertThat(observed.path("series").get(0).path("gaps").toString()).contains("NOT_ACQUIRED");

        ObjectNode capturedAcquisition = acquisitionManifest(plan);
        capturedAcquisition.set("captures", array(capture(plan.path("series").get(0))));
        capturedAcquisition.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(capturedAcquisition));
        ObjectNode capturedOptions = observedOptions.deepCopy();
        capturedOptions.set("acquisition", capturedAcquisition);
        ObjectNode captured = StrategyResearchAuthoritativeV5.coverageReport(capturedOptions);
        assertThat(captured.path("series").get(0).path("observed_rows").asLong()).isZero();
        assertThat(captured.path("series").get(0).path("jsonl_partition").isObject()).isTrue();
        assertThat(captured.path("series").get(0).path("limitations").toString()).contains("PARTIAL_CAPTURE");

        ObjectNode mismatched = acquisition.deepCopy().put("plan_sha256", "f".repeat(64));
        mismatched.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(mismatched));
        ObjectNode mismatchOptions = observedOptions.deepCopy();
        mismatchOptions.set("acquisition", mismatched);
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.coverageReport(mismatchOptions))
                .hasMessageContaining("acquisition manifest is bound to a different plan");
    }

    @Test
    void commandReceiptValidationRejectsTamperingAndActiveStateAfterRehash() {
        ObjectNode request = JsonHashes.mapper().createObjectNode()
                .put("command", "validate").put("status", "COMPLETE");
        request.putArray("inputs");
        request.putArray("outputs");
        request.putArray("limitations");
        request.putObject("details").put("mode", "CONTRACT_TEST");

        ObjectNode receipt = StrategyResearchAuthoritativeV5.makeCommandReceipt(request);
        assertThat(StrategyResearchAuthoritativeV5.validateCommandReceipt(receipt)).isTrue();

        ObjectNode tampered = receipt.deepCopy().put("status", "ACTIVE");
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.validateCommandReceipt(tampered))
                .hasMessageContaining("missing or tampered");

        ObjectNode active = receipt.deepCopy();
        ((ObjectNode) active.path("details")).put("mode", "ACTIVE");
        active.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(active));
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.validateCommandReceipt(active))
                .hasMessageContaining("may not claim ACTIVE");
    }

    @Test
    void behaviorRegistryPathsPreserveMutableHeadImmutableSnapshotAndMigrationGuard() throws Exception {
        Path root = temporary.resolve("registry");
        StrategyResearchAuthoritativeV5.BehaviorRegistryPaths initial =
                StrategyResearchAuthoritativeV5.behaviorRegistryStatePaths(root);
        assertThat(initial.directory()).isEqualTo(root.toAbsolutePath().resolve("behavior-definitions"));
        assertThat(initial.statePath()).isEqualTo(initial.directory().resolve("behavior-definition-registry-head.json"));
        assertThat(initial.seedPath()).isNull();
        ObjectNode encoded = JsonHashes.mapper().createObjectNode().put("record_root", root.toString());
        assertThat(StrategyResearchAuthoritativeV5.behaviorRegistryStatePaths(encoded)
                .path("statePath").asText()).isEqualTo(initial.statePath().toString());

        Path explicitState = root.resolve("custom-head.json");
        StrategyResearchAuthoritativeV5.BehaviorRegistryPaths mutable =
                StrategyResearchAuthoritativeV5.behaviorRegistryStatePaths(root, explicitState);
        assertThat(mutable.statePath()).isEqualTo(explicitState.toAbsolutePath());
        assertThat(mutable.seedPath()).isNull();

        Files.createDirectories(initial.directory());
        Path snapshot = initial.directory().resolve("registry-" + "b".repeat(64) + ".json");
        Files.writeString(snapshot, "{}\n");
        StrategyResearchAuthoritativeV5.BehaviorRegistryPaths immutable =
                StrategyResearchAuthoritativeV5.behaviorRegistryStatePaths(root, snapshot);
        assertThat(immutable.statePath()).isEqualTo(initial.statePath());
        assertThat(immutable.seedPath()).isEqualTo(snapshot.toAbsolutePath());

        Files.writeString(initial.directory().resolve("registry-" + "c".repeat(64) + ".json"), "{}\n");
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.behaviorRegistryStatePaths(root))
                .hasMessageContaining("multiple immutable behavior-registry snapshots");
    }

    @Test
    void behaviorRegistryUsesLegacySeedWhenNoMutableHeadExists() throws Exception {
        Path root = temporary.resolve("legacy-registry");
        Files.createDirectories(root);
        Path legacy = root.resolve("behavior-definition-registry.json");
        Files.writeString(legacy, "{}\n");

        StrategyResearchAuthoritativeV5.BehaviorRegistryPaths paths =
                StrategyResearchAuthoritativeV5.behaviorRegistryStatePaths(root);

        assertThat(paths.statePath()).isEqualTo(root.toAbsolutePath().resolve(
                "behavior-definitions/behavior-definition-registry-head.json"));
        assertThat(paths.seedPath()).isEqualTo(legacy.toAbsolutePath());
    }

    @Test
    void canonicalBindingsAndExecutorIdentityFailClosedAtEveryBoundary() {
        ObjectNode precommit = object().put("hypothesis_family", "fear-reversal-v5");
        assertThat(StrategyResearchAuthoritativeV5.canonicalHypothesisFamilyV5(precommit))
                .isEqualTo("fear-reversal-v5");
        assertThat(StrategyResearchAuthoritativeV5.canonicalHypothesisFamilyV5(
                object().put("precommit_id", "fallback.v5"))).isEqualTo("fallback.v5");
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.canonicalHypothesisFamilyV5(
                object().put("hypothesis_family", "UPPER"))).hasMessageContaining("lowercase");
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.canonicalHypothesisFamilyV5(
                object().put("hypothesis_family", "family"), definition("other")))
                .hasMessageContaining("differs from the canonical precommit family");
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.canonicalHypothesisFamilyV5(
                object().put("hypothesis_family", "family"),
                evaluatorFamily("other")))
                .hasMessageContaining("evaluator strategy_family");

        ObjectNode empty = object();
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.makeAuthoritativeExecutorIdentityV5(empty))
                .hasMessageContaining("evaluator spec");
        ObjectNode evaluator = object().put("content_sha256", "a".repeat(64))
                .put("code_sha256", "b".repeat(64)).put("worker_code_sha256", "c".repeat(64));
        ObjectNode manifest = object().put("transformation_code_sha256", "d".repeat(64))
                .put("label_code_sha256", "e".repeat(64)).put("execution_code_sha256", "f".repeat(64))
                .put("config_sha256", "1".repeat(64));
        ObjectNode valid = object();
        valid.set("evaluatorSpec", evaluator);
        valid.set("manifest", manifest);
        valid.put("metadataBundleSha256", "2".repeat(64));
        ObjectNode identity = StrategyResearchAuthoritativeV5.makeAuthoritativeExecutorIdentityV5(valid);
        assertThat(identity.path("evaluator_spec_sha256").asText()).isEqualTo("a".repeat(64));
        assertThat(identity.path("content_sha256").asText())
                .isEqualTo(StrategyResearchAuthoritativeV5.ownHash(identity));
        ObjectNode badHash = valid.deepCopy();
        ((ObjectNode) badHash.path("evaluatorSpec")).put("code_sha256", "bad");
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.makeAuthoritativeExecutorIdentityV5(badHash))
                .hasMessageContaining("executor evaluator_code_sha256");
    }

    @Test
    void exactEpisodeInventoryChecksDuplicatesAndOptionalPhysicalRoles() {
        ObjectNode envelope = object();
        envelope.set("windows", array(object().put("episode_id", "e-1"), object().put("episode_id", "e-2")));
        ObjectNode artifact = object();
        artifact.set("episodes", array(object().put("episode_id", "e-2"), object().put("episode_id", "e-1")));
        ObjectNode options = object();
        options.set("envelope", envelope);
        options.set("artifact", artifact);
        assertThat(StrategyResearchAuthoritativeV5.validateExactProductionEpisodeInventoriesV5(options))
                .isTrue();
        ObjectNode roleRows = object();
        roleRows.set("feature", array(object().put("episode_id", "e-1"), object().put("episode_id", "e-2")));
        options.set("roleRows", roleRows);
        assertThat(StrategyResearchAuthoritativeV5.validateExactProductionEpisodeInventoriesV5(options))
                .isTrue();
        ObjectNode duplicate = artifact.deepCopy();
        duplicate.set("episodes", array(object().put("episode_id", "e-1"), object().put("episode_id", "e-1")));
        ObjectNode duplicateOptions = object();
        duplicateOptions.set("envelope", envelope);
        duplicateOptions.set("artifact", duplicate);
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.validateExactProductionEpisodeInventoriesV5(duplicateOptions))
                .hasMessageContaining("duplicate");
        ObjectNode missingRole = options.deepCopy();
        ((ObjectNode) missingRole.path("roleRows")).set("label", array(object().put("episode_id", "e-1")));
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.validateExactProductionEpisodeInventoriesV5(missingRole))
                .hasMessageContaining("label");
    }

    @Test
    void portfolioAndSettlementContractsExerciseOrderedAndPitBoundaries() {
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.validateAuthoritativePortfolioPolicy(null))
                .hasMessageContaining("strategy-portfolio-policy/2");
        ObjectNode malformed = object().put("schema", "strategy-portfolio-policy/2");
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.validateAuthoritativePortfolioPolicy(malformed))
                .hasMessageContaining("required");

        ObjectNode execution = object().put("asset", "btc").put("venue", "BINANCE")
                .put("symbol", "BTCUSDT").put("instrument", "BINANCE_USDM_DATED_FUTURE");
        ObjectNode expiry = object().put("content_sha256", "a".repeat(64));
        expiry.set("records", array(object().put("asset", "btc").put("venue", "BINANCE")
                .put("symbol", "BTCUSDT").put("instrument", "BINANCE_USDM_DATED_FUTURE")
                .put("expiry", "2026-01-05T00:00:00Z")));
        ObjectNode settlement = object().put("content_sha256", "b".repeat(64))
                .put("source_receipt_sha256", "c".repeat(64));
        settlement.set("records", array(object().put("asset", "btc").put("venue", "BINANCE")
                .put("symbol", "BTCUSDT").put("instrument", "BINANCE_USDM_DATED_FUTURE")
                .put("expiry", "2026-01-05T00:00:00Z").put("event_time", "2026-01-05T00:00:00Z")
                .put("settlement_time", "2026-01-05T00:00:00Z")
                .put("availability_time", "2026-01-06T00:00:00Z")
                .put("settlement_price", 100).put("settlement_mark_source_sha256", "c".repeat(64))
                .put("source_byte_sha256", "c".repeat(64)).put("settlement_mark_event_id", "settle-1")
                .put("source_receipt_sha256", "c".repeat(64))));
        ObjectNode metadata = object();
        metadata.set("expiry", expiry);
        metadata.set("settlement", settlement);
        ObjectNode settlementOptions = object();
        settlementOptions.set("metadata", metadata);
        settlementOptions.set("execution", execution);
        settlementOptions.set("label", object().put("resolution_time", "2026-01-07T00:00:00Z"));
        ObjectNode result = StrategyResearchAuthoritativeV5.resolveDatedSettlementForStress(settlementOptions);
        assertThat(result.path("settlementPrice").asDouble()).isEqualTo(100);
        assertThat(result.path("settlementAt").asLong()).isGreaterThanOrEqualTo(result.path("expiryAt").asLong());

        ObjectNode wrongInstrument = execution.deepCopy().put("instrument", "BINANCE_SPOT");
        ObjectNode wrongMetadata = object();
        wrongMetadata.set("expiry", expiry);
        wrongMetadata.set("settlement", settlement);
        ObjectNode wrongInstrumentOptions = object();
        wrongInstrumentOptions.set("metadata", wrongMetadata);
        wrongInstrumentOptions.set("execution", wrongInstrument);
        wrongInstrumentOptions.set("label", object());
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.resolveDatedSettlementForStress(wrongInstrumentOptions))
                .hasMessageContaining("dated USD-M future");
        ObjectNode noMatch = settlement.deepCopy();
        noMatch.set("records", array(object().put("asset", "eth")));
        ObjectNode noMatchMetadata = object();
        noMatchMetadata.set("expiry", expiry);
        noMatchMetadata.set("settlement", noMatch);
        ObjectNode noMatchOptions = object();
        noMatchOptions.set("metadata", noMatchMetadata);
        noMatchOptions.set("execution", execution);
        noMatchOptions.set("label", object());
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.resolveDatedSettlementForStress(noMatchOptions))
                .hasMessageContaining("expiry stress lacks one exact physical settlement record");
    }

    @Test
    void dataBackfillPlanAndUnavailableModesRemainDeterministic() {
        Path root = temporary.resolve("backfill");
        ObjectNode base = object().put("as_of", "2026-08-24T20:30:00.000Z")
                .put("captured_at", "2026-08-24T20:30:00.000Z")
                .put("root_reference", "contract-backfill").put("record_root", root.toString());
        ObjectNode planned = StrategyResearchAuthoritativeV5.authoritativeDataBackfill(base);
        assertThat(planned.path("plan").path("content_sha256").asText()).hasSize(64);
        assertThat(planned.path("coverage").path("status").asText()).isEqualTo("PLANNED");
        assertThat(Files.exists(Path.of(planned.path("receipt_path").asText()))).isTrue();
        ObjectNode repeated = StrategyResearchAuthoritativeV5.authoritativeDataBackfill(base);
        assertThat(repeated.path("receipt")).isEqualTo(planned.path("receipt"));

        ObjectNode download = base.deepCopy().put("download", true);
        assertThat(StrategyResearchAuthoritativeV5.authoritativeDataBackfill(download).path("receipt").path("status").asText())
                .isEqualTo("BLOCKED");
        ObjectNode catalogOnly = base.deepCopy().put("catalog_only", true);
        assertThat(StrategyResearchAuthoritativeV5.authoritativeDataBackfill(catalogOnly).path("receipt").path("status").asText())
                .isEqualTo("BLOCKED");
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.authoritativeDataBackfill(object()))
                .hasMessageContaining("explicit --as-of");
        ObjectNode planReuse = base.deepCopy();
        planReuse.set("plan", object());
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.authoritativeDataBackfill(planReuse))
                .hasMessageContaining("only valid with explicit --download");
    }

    @Test
    void publicDispatcherWrappersAndProspectiveGuardsAreFailClosed() {
        Path root = temporary.resolve("dispatcher");
        ObjectNode options = object().put("record_root", root.toString());
        assertThat(StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli(null, options)).isNull();
        assertThat(StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("unknown", options)).isNull();
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.runDataBackfillV5(options))
                .hasMessageContaining("explicit --as-of");
        assertThat(StrategyResearchAuthoritativeV5.runDataRawReplayV5(options).path("status").asText()).isEqualTo("BLOCKED");
        assertThat(StrategyResearchAuthoritativeV5.runFeatureBuildV5(options).path("status").asText()).isEqualTo("BLOCKED");
        assertThat(StrategyResearchAuthoritativeV5.runMetadataBuildV5(options).path("status").asText()).isEqualTo("BLOCKED");
        assertThat(StrategyResearchAuthoritativeV5.runOpportunityEnvelopeV5(options).path("status").asText()).isEqualTo("BLOCKED");
        assertThat(StrategyResearchAuthoritativeV5.runArtifactBuildV5(options).path("status").asText()).isEqualTo("BLOCKED");
        assertThat(StrategyResearchAuthoritativeV5.runExperimentFreezeV5(options).path("status").asText()).isEqualTo("BLOCKED");
        assertThat(StrategyResearchAuthoritativeV5.runSearchGeneticV5(options).path("status").asText()).isEqualTo("BLOCKED");
        assertThat(StrategyResearchAuthoritativeV5.runResearchRunV5(options).path("status").asText()).isEqualTo("BLOCKED");
        assertThat(StrategyResearchAuthoritativeV5.runOverfitAuditV5(options).path("status").asText()).isEqualTo("BLOCKED");
        assertThat(StrategyResearchAuthoritativeV5.runProspectiveRunnerV5(options).path("status").asText()).isEqualTo("BLOCKED");

        ObjectNode unconfigured = options.deepCopy().put("live_source_unconfigured", true);
        ObjectNode blocked = StrategyResearchAuthoritativeV5.authoritativeProspectiveRunner(unconfigured);
        assertThat(blocked.path("receipt").path("details").path("mode").asText())
                .isEqualTo("BLOCKED_LIVE_SOURCE_UNCONFIGURED");
        ObjectNode secret = options.deepCopy().put("api_secret", "must-never-cross-boundary");
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.authoritativeProspectiveRunner(secret))
                .hasMessageContaining("private key material");
    }

    @Test
    void commandInputsCannotInjectDerivedResearchRowsOrConfiguration() {
        Path root = temporary.resolve("input-guards");
        ObjectNode metadata = object().put("record_root", root.resolve("metadata-records").toString());
        metadata.set("metrics", object());
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.authoritativeMetadataBuild(metadata))
                .hasMessageContaining("caller-supplied statistical output");

        ObjectNode opportunity = object().put("record_root", root.resolve("opportunity-records").toString());
        opportunity.set("metrics", object());
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.authoritativeOpportunityEnvelope(opportunity))
                .hasMessageContaining("caller-supplied statistical output");

        ObjectNode artifact = object().put("record_root", root.resolve("artifact-records").toString());
        artifact.set("metrics", object());
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.authoritativeArtifactBuild(artifact))
                .hasMessageContaining("caller-supplied statistical output");

        ObjectNode init = object().put("record_root", root.resolve("init-records").toString());
        init.set("features", array());
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.authoritativeResearchInit(init))
                .hasMessageContaining("caller-supplied features");

        ObjectNode search = object().put("record_root", root.resolve("search-records").toString());
        search.set("features", array());
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.authoritativeSearchGenetic(search))
                .hasMessageContaining("legacy fixture-only");

        ObjectNode freeze = object().put("record_root", root.resolve("freeze-records").toString())
                .put("candidate_set_sha256", "a".repeat(64));
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.authoritativeExperimentFreeze(freeze))
                .hasMessageContaining("lineage is recomputed");
    }

    @Test
    void prospectiveResolutionCopiesEmptyOptionsAndRejectsMissingBundles() {
        ObjectNode empty = object().put("marker", "kept");
        assertThat(StrategyResearchAuthoritativeV5.resolveProspectiveSourceBundle(empty))
                .isEqualTo(empty);
        ObjectNode missing = object().put("source_bundle", temporary.resolve("missing-bundle.json").toString())
                .put("workflow_root", temporary.toString());
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.resolveProspectiveSourceBundle(missing))
                .hasMessageContaining("source bundle");
    }

    @Test
    void geneticCheckpointResumeHonorsMissingAndActiveWriterBoundaries() throws Exception {
        Path missing = temporary.resolve("missing-checkpoint.json");
        ObjectNode absent = object().put("checkpoint_path", missing.toString());
        assertThat(StrategyResearchAuthoritativeV5.reopenAuthoritativeGeneticCheckpoint(absent)).isNull();
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.reopenAuthoritativeGeneticCheckpoint(object()))
                .hasMessageContaining("checkpoint path is missing");

        Path checkpoint = temporary.resolve("checkpoint.json");
        Files.writeString(checkpoint, "{}\n");
        ObjectNode incomplete = object().put("checkpoint_path", checkpoint.toString());
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.reopenAuthoritativeGeneticCheckpoint(incomplete))
                .hasMessageContaining("validation inputs are incomplete");

        ObjectNode completeInputs = incomplete.deepCopy();
        completeInputs.set("artifact", object());
        completeInputs.set("exposure_head", object());
        completeInputs.set("gene_space", object());
        completeInputs.put("fold_id", "fold-1");
        Files.writeString(Path.of(checkpoint + ".lock"), "writer\n");
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.reopenAuthoritativeGeneticCheckpoint(completeInputs))
                .hasMessageContaining("competing active writer");
    }

    @Test
    void readinessAndValidationCommandsPublishDurableReceipts() throws Exception {
        Path root = temporary.resolve("command-outputs");
        ObjectNode readiness = object().put("record_root", root.resolve("records").toString())
                .put("generated_at", "2026-08-24T20:30:00.000Z")
                .put("now_at", "2026-08-24T20:30:00.000Z")
                .put("out", root.resolve("readiness.json").toString())
                .put("markdown", root.resolve("readiness.md").toString())
                .put("receipt", root.resolve("readiness-receipt.json").toString());
        JsonNode readinessResult = StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("readiness-audit", readiness);
        assertThat(readinessResult.path("audit").path("schema").asText())
                .isEqualTo("strategy-readiness-audit/2");
        assertThat(Files.isRegularFile(root.resolve("readiness.json"))).isTrue();
        assertThat(Files.isRegularFile(root.resolve("readiness.md"))).isTrue();
        assertThat(Files.isRegularFile(root.resolve("readiness-receipt.json"))).isTrue();

        ObjectNode receiptOptions = object().put("command", "validate").put("status", "COMPLETE");
        receiptOptions.set("inputs", array());
        receiptOptions.set("outputs", array());
        receiptOptions.set("limitations", array());
        receiptOptions.set("details", object().put("mode", "VALIDATION_FIXTURE"));
        ObjectNode receipt = StrategyResearchAuthoritativeV5.makeCommandReceipt(receiptOptions);
        Path artifact = root.resolve("receipt.json");
        Files.createDirectories(root);
        Files.writeString(artifact, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(receipt));
        ObjectNode validation = object().put("input", artifact.toString())
                .put("record_root", root.resolve("validation-records").toString());
        JsonNode validated = StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("validate", validation);
        assertThat(validated.path("valid").asBoolean()).isTrue();
        assertThat(validated.path("schema").asText()).isEqualTo(
                StrategyResearchAuthoritativeV5.AUTHORITATIVE_SCHEMA);
    }

    private static ObjectNode object() {
        return JsonHashes.mapper().createObjectNode();
    }

    private static ArrayNode array(ObjectNode... values) {
        ArrayNode array = JsonHashes.mapper().createArrayNode();
        for (ObjectNode value : values) array.add(value);
        return array;
    }

    private static ObjectNode acquisitionManifest(ObjectNode plan) {
        ObjectNode acquisition = object().put("schema", "strategy-v5-authoritative-acquisition/1")
                .put("version", 1).put("status", "STAGING_PARTIAL")
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("root_reference", "contract-acquisition")
                .put("staging_format", "JSONL").put("storage_role", "STAGING")
                .put("authoritative", false).put("base_complete", false)
                .put("declared_complete", false).put("full_plan_complete", false)
                .put("completion_scope", "NONE").put("required_series_count", 0)
                .put("required_complete_count", 0).put("optional_series_count", 0)
                .put("optional_complete_count", 0).put("optional_complete", true);
        acquisition.set("captures", array());
        acquisition.set("unavailable_required", JsonHashes.mapper().createArrayNode());
        acquisition.set("unavailable_optional", JsonHashes.mapper().createArrayNode());
        acquisition.set("limitations", JsonHashes.mapper().createArrayNode());
        return StrategyResearchDataV5.withHash(acquisition);
    }

    private static ObjectNode capture(JsonNode plannedSeries) {
        ObjectNode capture = plannedSeries.deepCopy();
        capture.remove("trade_scope");
        capture.put("series_sha256", StrategyResearchAuthoritativeV5.hash(plannedSeries));
        ObjectNode coverage = object().put("complete", false).put("expected_rows", 1)
                .put("observed_rows", 0).put("reason", "PARTIAL_CAPTURE");
        capture.set("coverage", coverage);
        capture.set("partition", object().put("path", "partial.jsonl")
                .put("sha256", "d".repeat(64)).put("bytes", 1).put("row_count", 0)
                .put("format", "JSONL").put("storage_role", "STAGING").put("authoritative", false));
        capture.set("limitations", array());
        capture.set("source_receipts", array());
        return capture;
    }

    private static ObjectNode definition(String family) {
        ObjectNode options = object();
        options.set("definition", object().put("hypothesis_family", family));
        return options;
    }

    private static ObjectNode evaluatorFamily(String family) {
        ObjectNode options = object();
        options.set("evaluatorSpec", object().put("strategy_family", family));
        return options;
    }
}
