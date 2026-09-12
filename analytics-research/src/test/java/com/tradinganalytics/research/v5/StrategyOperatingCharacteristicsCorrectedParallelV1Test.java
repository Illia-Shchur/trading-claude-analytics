package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for the additive corrected coordinator boundary.
 *
 * <p>The fixtures deliberately use the retained statistical geometry but tiny
 * development seed waves.  The fake executor is only used through the
 * coordinator's package test seam; no private physical inputs, caches, or
 * process artifacts are required.</p>
 */
final class StrategyOperatingCharacteristicsCorrectedParallelV1Test {
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final String EVENT = "strategy-research/experiments/fk-deleveraging-baseline-v002/portfolio-policy-v001.json";
    private static final String LIFECYCLE = "strategy-research/experiments/fk-deleveraging-baseline-v002/lifecycle-timing-v001.json";

    @TempDir Path temporary;

    @Test
    void correctedProfileIsExplicitlyBoundAndFrozenProfileRemainsLegacy() {
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("requested_workers", 1);
        ObjectNode frozen = StrategyOperatingCharacteristicsParallelV1.executionProfile(options,
                resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode corrected = StrategyOperatingCharacteristicsCorrectedParallelV1.executionProfile(options);

        assertThat(frozen.path("fixed_evaluator").asText()).doesNotContain("Corrected");
        assertThat(frozen.has("accounting_version")).isFalse();
        assertThat(corrected.path("fixed_evaluator").asText())
                .isEqualTo(StrategyFixedBaselineCorrectedV1.EVALUATOR_ID);
        assertThat(corrected.path("corrected_evaluator_identity").asText())
                .isEqualTo(StrategyFixedBaselineCorrectedV1.EVALUATOR_ID);
        assertThat(corrected.path("accounting_version").asText())
                .isEqualTo(StrategyFixedBaselineCorrectedV1.ACCOUNTING_VERSION);
        assertThat(corrected.path("profile_envelope_sha256").asText())
                .isEqualTo(profileEnvelopeHash(corrected));
        assertThat(corrected.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(corrected));
    }

    @Test
    void correctedWorkerRowOwnershipPreservesArtifactAndDetachedHelperContract() throws Exception {
        String runId = "owned-row-parity-test";
        StrategyOperatingCharacteristicsParallelV1.Slot slot =
                new StrategyOperatingCharacteristicsParallelV1.Slot(
                        "a".repeat(64), "FULL", "PLANTED_EDGE", 0.02, 0, 920200000L, 0);
        ObjectNode raw = correctedWorkerRawResult(slot, 10);
        ObjectNode expected = correctedWorkerArtifact(slot, raw, runId);
        ObjectNode identity = (ObjectNode) expected.path("executor_identity").deepCopy();
        ObjectNode detachedRow = (ObjectNode) expected.path("row").deepCopy();
        ObjectNode detached = StrategyOperatingCharacteristicsParallelV1.correctedSlotArtifactForTest(
                slot, 1, detachedRow, identity, runId, false);
        ObjectNode ownedRow = (ObjectNode) expected.path("row").deepCopy();
        ObjectNode owned = StrategyOperatingCharacteristicsParallelV1.correctedSlotArtifactForTest(
                slot, 1, ownedRow, identity, runId, true);

        byte[] detachedBytes = JsonHashes.mapper().writeValueAsBytes(detached);
        byte[] ownedBytes = JsonHashes.mapper().writeValueAsBytes(owned);
        assertThat(ownedBytes).containsExactly(detachedBytes);
        assertThat(detached).isEqualTo(expected);
        assertThat(owned).isEqualTo(expected);
        assertThat(owned.get("row")).isSameAs(ownedRow);
        assertThat(detached.path("portable_economic_sha256").asText())
                .isEqualTo(expected.path("portable_economic_sha256").asText());
        assertThat(detached.path("row").path("economic_semantic_sha256").asText())
                .isEqualTo(expected.path("row").path("economic_semantic_sha256").asText());
        assertThat(detached.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHashStreaming(detached));
        assertThat(owned.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHashStreaming(owned));

        ((ObjectNode) detachedRow.path("raw_evaluator_result").path("metrics")).put("mutated_after_build", true);
        assertThat(JsonHashes.mapper().writeValueAsBytes(detached)).containsExactly(detachedBytes);
        assertThat(detached.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHashStreaming(detached));
    }

    @Test
    void correctedPreflightPreservesExactGeometryAndUsesCorrectedBinding() {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode plan = correctedPlan(1, profile);
        ObjectNode options = JsonHashes.mapper().createObjectNode();
        options.set("plan", plan);
        options.set("profile", profile);
        options.put("internal_test_seam", true);

        ObjectNode result = StrategyOperatingCharacteristicsCorrectedParallelV1.preflight(options);

        assertThat(result.path("schema").asText())
                .isEqualTo(StrategyOperatingCharacteristicsCorrectedParallelV1.PREFLIGHT_SCHEMA);
        assertThat(result.path("fixed_evaluator").asText())
                .isEqualTo(StrategyFixedBaselineCorrectedV1.EVALUATOR_ID);
        assertThat(result.path("accounting_version").asText())
                .isEqualTo(StrategyFixedBaselineCorrectedV1.ACCOUNTING_VERSION);
        assertThat(result.path("corrected").asBoolean()).isTrue();
        assertThat(result.path("mode").asText()).isEqualTo("FULL");
        assertThat(result.path("planned_slots").asInt()).isEqualTo(4);
        assertThat(result.path("status").asText()).isEqualTo("READY_PRE_OUTCOME");
        assertThat(result.path("outcomes_opened").asBoolean()).isFalse();
        assertThat(result.path("promotion_eligible").asBoolean()).isFalse();
    }

    @Test
    void correctedPreflightAcceptsAFilePlanAndConstructsAProfileWhenOmitted() throws Exception {
        ObjectNode actualProfile = StrategyOperatingCharacteristicsCorrectedParallelV1.executionProfile(
                JsonHashes.mapper().createObjectNode().put("requested_workers", 1));
        int workers = actualProfile.path("effective_workers").asInt(0);
        org.junit.jupiter.api.Assumptions.assumeTrue(workers > 0,
                "the live host cannot exercise the admitted omitted-profile branch");
        ObjectNode plan = correctedPlan(workers, actualProfile);
        Path planFile = temporary.resolve("corrected-plan.json");
        Files.writeString(planFile, JsonHashes.mapper().writeValueAsString(plan));
        ObjectNode options = JsonHashes.mapper().createObjectNode()
                .put("plan", planFile.toString()).put("internal_test_seam", true);

        ObjectNode result = StrategyOperatingCharacteristicsCorrectedParallelV1.preflight(options);

        assertThat(result.path("fixed_evaluator").asText())
                .isEqualTo(StrategyFixedBaselineCorrectedV1.EVALUATOR_ID);
        assertThat(result.path("profile").path("corrected_evaluator_identity").asText())
                .isEqualTo(StrategyFixedBaselineCorrectedV1.EVALUATOR_ID);
        assertThat(result.path("outcomes_opened").asBoolean()).isFalse();
    }

    @Test
    void correctedPreflightBlocksAHostWithoutTargetResources() {
        ObjectNode profile = profile(1, resourceProbe(0, 0L, 256L * GIB));
        ObjectNode plan = correctedPlan(1, profile);
        ObjectNode options = JsonHashes.mapper().createObjectNode();
        options.set("plan", plan);
        options.set("profile", profile);
        options.put("internal_test_seam", true);

        ObjectNode result = StrategyOperatingCharacteristicsCorrectedParallelV1.preflight(options);

        assertThat(profile.path("target_qualified").asBoolean()).isFalse();
        assertThat(profile.path("effective_workers").asInt()).isZero();
        assertThat(result.path("status").asText()).isEqualTo("BLOCKED_RESOURCE");
        assertThat(result.path("measured").asBoolean()).isFalse();
        assertThat(result.path("outcomes_opened").asBoolean()).isFalse();
    }

    @Test
    void correctedPlanRejectsFrozenIdentityAccountingAndTamperedDigest() {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode valid = correctedPlan(1, profile);

        ObjectNode frozenSchema = valid.deepCopy().put("schema", StrategyOperatingCharacteristicsParallelV1.PLAN_SCHEMA);
        rehash(frozenSchema);
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.preflight(planOptions(frozenSchema, profile)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("corrected parallel plan accounting binding");

        ObjectNode wrongAccounting = valid.deepCopy().put("accounting_version", "FROZEN_V5");
        rehash(wrongAccounting);
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.preflight(planOptions(wrongAccounting, profile)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("accounting binding");

        ObjectNode wrongAlgorithm = valid.deepCopy().put("corrected_evaluator_identity", "StrategyFixedBaselineV5");
        rehash(wrongAlgorithm);
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.preflight(planOptions(wrongAlgorithm, profile)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("accounting binding");

        ObjectNode tamperedDigest = valid.deepCopy().put("content_sha256", "0".repeat(64));
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.preflight(planOptions(tamperedDigest, profile)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("self-bound");
    }

    @Test
    void correctedPlanRejectsADevelopmentSeedOverlappingHeldOutConfirmation() {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode overlap = correctedPlan(1, profile);
        ((ArrayNode) overlap.path("development_seed_cells").get(0).path("development_seeds"))
                .set(0, overlap.path("cells").get(0).path("seeds").get(0));
        rehash(overlap);

        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.preflight(planOptions(overlap, profile)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("overlap");
    }

    @Test
    void correctedPlanRejectsMalformedDevelopmentAndPrefixWaves() {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode valid = correctedPlan(1, profile);
        ObjectNode noDevelopment = valid.deepCopy().put("development_wave", false);
        ObjectNode noFullCells = valid.deepCopy();
        noFullCells.remove("development_seed_cells");
        ObjectNode shortFullCells = valid.deepCopy();
        ((ArrayNode) shortFullCells.path("development_seed_cells")).remove(0);
        ObjectNode missingFullSeeds = valid.deepCopy();
        ((ObjectNode) missingFullSeeds.path("development_seed_cells").get(0)).remove("development_seeds");
        ObjectNode wrongFullCell = valid.deepCopy();
        ((ObjectNode) wrongFullCell.path("development_seed_cells").get(0)).put("scenario", "UNKNOWN");
        ObjectNode duplicateFullCell = valid.deepCopy();
        ((ObjectNode) duplicateFullCell.path("development_seed_cells").get(1)).put("scenario", "NO_EDGE");
        ObjectNode nonIntegralFullSeed = valid.deepCopy();
        ((ArrayNode) nonIntegralFullSeed.path("development_seed_cells").get(0).path("development_seeds"))
                .set(0, "not-a-seed");
        ObjectNode duplicateFullSeed = valid.deepCopy();
        ((ArrayNode) duplicateFullSeed.path("development_seed_cells").get(0).path("development_seeds"))
                .add(920_000_000L);
        ObjectNode shortPrefixCells = valid.deepCopy();
        ((ArrayNode) shortPrefixCells.path("development_prefix_seed_cells").get(0).path("prefix_seeds"))
                .remove(0);
        ObjectNode wrongPrefixCell = valid.deepCopy();
        ((ObjectNode) wrongPrefixCell.path("development_prefix_seed_cells").get(0)).put("scenario", "UNKNOWN");
        ObjectNode nonIntegralPrefixSeed = valid.deepCopy();
        ((ArrayNode) nonIntegralPrefixSeed.path("development_prefix_seed_cells").get(0).path("prefix_seeds"))
                .set(0, "not-a-seed");
        ObjectNode duplicatePrefixSeed = valid.deepCopy();
        ((ArrayNode) duplicatePrefixSeed.path("development_prefix_seed_cells").get(0).path("prefix_seeds"))
                .add(921_000_000L);
        ObjectNode profileMismatch = correctedPlan(2, profile);

        for (ObjectNode candidate : new ObjectNode[] {noDevelopment, noFullCells, shortFullCells,
                missingFullSeeds, wrongFullCell, duplicateFullCell, nonIntegralFullSeed, duplicateFullSeed,
                shortPrefixCells, wrongPrefixCell, nonIntegralPrefixSeed, duplicatePrefixSeed}) {
            rehash(candidate);
            assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.preflight(
                    planOptions(candidate, profile))).isInstanceOf(IllegalArgumentException.class);
        }
        rehash(profileMismatch);
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.preflight(
                planOptions(profileMismatch, profile)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("execution profile");
    }

    @Test
    void correctedDevelopmentPlanRequiresPackagedExecutorAndNeverMintsAClassesReceipt() {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));

        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.createDevelopmentPlan(
                basePlan(), profile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("packaged executable identity");
    }

    @Test
    void correctedPlanBuilderRejectsMissingInputsAndAnEmptyDevelopmentAdmission() {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode identity = JsonHashes.mapper().createObjectNode();
        identity.putObject("executable").put("kind", "JAR").put("sha256", "7".repeat(64));
        identity.putObject("compiled").put("input_fingerprint", "8".repeat(64));
        ArrayNode dependencies = (ArrayNode) basePlan().path("supporting_dependency_receipts");
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.createDevelopmentPlanForTest(
                null, profile, identity, dependencies)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base plan");
        ObjectNode invalidProfile = profile.deepCopy().put("content_sha256", "0".repeat(64));
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.createDevelopmentPlanForTest(
                basePlan(), invalidProfile, identity, dependencies)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("self-bound");
        ObjectNode noWorkers = profile(1, resourceProbe(0, 0L, 256L * GIB));
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.createDevelopmentPlanForTest(
                basePlan(), noWorkers, identity, dependencies)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no workers");
        ObjectNode noDependencies = basePlan();
        noDependencies.remove("supporting_dependency_receipts");
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.createDevelopmentPlanForTest(
                noDependencies, profile, identity, dependencies)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testPlanBuilderBindsExecutorDependenciesAndDisjointDevelopmentSeeds() {
        ObjectNode profile = profile(2, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode identity = JsonHashes.mapper().createObjectNode();
        identity.putObject("executable").put("kind", "JAR").put("sha256", "7".repeat(64));
        identity.putObject("compiled").put("input_fingerprint", "8".repeat(64));
        ObjectNode base = basePlan();
        ObjectNode plan = StrategyOperatingCharacteristicsCorrectedParallelV1.createDevelopmentPlanForTest(
                base, profile, identity,
                (ArrayNode) base.path("supporting_dependency_receipts"));

        assertThat(plan.path("schema").asText())
                .isEqualTo(StrategyOperatingCharacteristicsCorrectedParallelV1.PLAN_SCHEMA);
        assertThat(plan.path("development_worker_count").asInt()).isEqualTo(2);
        assertThat(plan.path("executor_identity_sha256").asText()).isEqualTo("7".repeat(64));
        assertThat(plan.path("executor_source_sha256").asText()).isEqualTo("8".repeat(64));
        assertThat(plan.path("development_seed_cells")).hasSize(4);
        assertThat(plan.path("development_prefix_seed_cells")).hasSize(4);
        assertThat(plan.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(plan));
        ObjectNode preflight = StrategyOperatingCharacteristicsCorrectedParallelV1.preflight(
                planOptions(plan, profile));
        assertThat(preflight.path("status").asText()).isEqualTo("READY_PRE_OUTCOME");
        assertThat(preflight.path("planned_slots").asInt()).isEqualTo(8);
    }

    @Test
    void internalReceiptHelpersBindMeasurementsAndRejectEconomicMismatch() throws Exception {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode measurement = measurement(profile, 10, 20, 30);
        Path parallelResult = temporary.resolve("parallel-result.json");
        Path parallelLedger = temporary.resolve("parallel-ledger.json");
        Path serialResult = temporary.resolve("serial-result.json");
        Path serialLedger = temporary.resolve("serial-ledger.json");
        Files.writeString(parallelResult, "parallel");
        Files.writeString(parallelLedger, "parallel-ledger");
        Files.writeString(serialResult, "serial");
        Files.writeString(serialLedger, "serial-ledger");
        Files.writeString(temporary.resolve("parallel-result.json"), "parallel");
        Files.writeString(temporary.resolve("parallel-ledger.json"), "parallel-ledger");
        Files.writeString(temporary.resolve("serial-result.json"), "serial");
        Files.writeString(temporary.resolve("serial-ledger.json"), "serial-ledger");
        ObjectNode resource = (ObjectNode) invokePrivate("resourceEnvelope",
                new Class<?>[] {ObjectNode.class, ObjectNode.class, ObjectNode.class,
                        Path.class, Path.class, Path.class, Path.class},
                profile, measurement, measurement,
                parallelResult, parallelLedger, serialResult, serialLedger);
        assertThat(resource.path("measured_wall_millis").asLong()).isEqualTo(10);
        assertThat(resource.path("measured_max_aggregate_rss_bytes").asLong()).isEqualTo(20);
        assertThat(resource.path("measured_max_disk_bytes").asLong()).isEqualTo(30);
        assertThat(resource.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(resource));

        ArrayNode equivalent = refs("parallel");
        ArrayNode serial = equivalent.deepCopy();
        for (JsonNode ref : equivalent) ((ObjectNode) ref).put("portable_economic_sha256", "e".repeat(64));
        for (JsonNode ref : serial) ((ObjectNode) ref).put("portable_economic_sha256", "e".repeat(64));
        invokePrivate("requireEconomicEquivalence", new Class<?>[] {ArrayNode.class, ArrayNode.class},
                equivalent, serial);
        ((ObjectNode) serial.get(0)).put("portable_economic_sha256", "f".repeat(64));
        assertThatThrownBy(() -> invokePrivate("requireEconomicEquivalence",
                new Class<?>[] {ArrayNode.class, ArrayNode.class}, equivalent, serial))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("economics differ");

        ObjectNode receipt = (ObjectNode) invokePrivate("qualificationReceipt",
                new Class<?>[] {ObjectNode.class, ObjectNode.class, ObjectNode.class,
                        ArrayNode.class, ArrayNode.class, ObjectNode.class, ObjectNode.class},
                correctedPlan(1, profile), profile, resource, equivalent, serial, measurement, measurement);
        assertThat(receipt.path("full_geometry").path("replications").asInt()).isEqualTo(75);
        assertThat(receipt.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(receipt));

        assertThat(invokePrivate("measurementValue", new Class<?>[] {ObjectNode.class, String.class},
                measurement, "measured_wall_millis")).isEqualTo(10L);
        ObjectNode missing = measurement.deepCopy().put("measured_wall_millis", 0);
        assertThatThrownBy(() -> invokePrivate("measurementValue", new Class<?>[] {ObjectNode.class, String.class},
                missing, "measured_wall_millis"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");

        ObjectNode tooLarge = measurement(profile, 10, 20, profile.path("max_disk_bytes").asLong() + 1);
        assertThatThrownBy(() -> invokePrivate("resourceEnvelope",
                new Class<?>[] {ObjectNode.class, ObjectNode.class, ObjectNode.class,
                        Path.class, Path.class, Path.class, Path.class},
                profile, tooLarge, measurement(profile, 10, 20, 30),
                parallelResult, parallelLedger, serialResult, serialLedger))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("resource envelope");
    }

    @Test
    void internalFileBoundariesUsePortablePathsAndAtomicJsonContent() throws Exception {
        Path input = temporary.resolve("profile.json");
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        Files.writeString(input, JsonHashes.mapper().writeValueAsString(profile));
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("profile", input.toString());
        ObjectNode read = (ObjectNode) invokePrivate("readObject",
                new Class<?>[] {ObjectNode.class, String.class}, options, "profile");
        assertThat(read.path("content_sha256").asText()).isEqualTo(profile.path("content_sha256").asText());

        ObjectNode pathOptions = JsonHashes.mapper().createObjectNode().put("result", "result.json");
        assertThat(invokePrivate("requiredPath", new Class<?>[] {ObjectNode.class, String.class}, pathOptions, "result"))
                .isEqualTo(Path.of("result.json").toAbsolutePath().normalize());
        assertThatThrownBy(() -> invokePrivate("requiredPath", new Class<?>[] {ObjectNode.class, String.class},
                JsonHashes.mapper().createObjectNode(), "result"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires a path");

        Path output = temporary.resolve("written.json");
        invokePrivate("writeJson", new Class<?>[] {Path.class, ObjectNode.class}, output, profile);
        assertThat(JsonHashes.mapper().readTree(Files.readString(output)).path("content_sha256").asText())
                .isEqualTo(profile.path("content_sha256").asText());
        assertThatThrownBy(() -> invokePrivate("writeJson", new Class<?>[] {Path.class, ObjectNode.class},
                temporary.resolve("missing-parent").resolve("output.json"), profile))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("write corrected");

        Assumptions.assumeTrue(!System.getProperty("os.name").toLowerCase().contains("windows"),
                "PathConfinement nlink receipts require the Unix test environment");
        ArrayNode dependencies = (ArrayNode) invokePrivate("currentDependencyReceipts", new Class<?>[0]);
        assertThat(dependencies).hasSize(2);
        assertThat(dependencies.get(0).path("data_base64").asText()).isNotBlank();
    }

    @Test
    void qualificationRefusesUnqualifiedHardwareBeforeReadingOutcomePaths() {
        ObjectNode profile = profile(1, resourceProbe(0, 0L, 256L * GIB));
        ObjectNode plan = correctedPlan(1, profile);
        ObjectNode options = planOptions(plan, profile)
                .put("parallel_result", temporary.resolve("missing-parallel.json").toString())
                .put("parallel_ledger", temporary.resolve("missing-parallel-ledger.json").toString())
                .put("serial_result", temporary.resolve("missing-serial.json").toString())
                .put("serial_ledger", temporary.resolve("missing-serial-ledger.json").toString());

        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.qualifyDevelopment(options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target-qualified hardware");
    }

    @Test
    void qualificationCannotValidateAProfileWithAChangedContentDigest() {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode plan = correctedPlan(1, profile);
        ObjectNode tampered = profile.deepCopy().put("effective_workers", 2);

        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.validateQualification(
                planOptions(plan, tampered)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("self-bound");
    }

    @Test
    void qualificationValidationRequiresTheOriginalPlanAndRunEvidence() {
        ObjectNode profile = qualifiedProfile();
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.validateQualification(
                profileOptions(profile)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires the development plan");
    }

    @Test
    void reopensSelfContainedCorrectedArtifactsAndMeasurementAndRejectsTampering() throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name").toLowerCase().contains("windows"),
                "singly-linked artifact custody requires the Unix test environment");
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode plan = correctedPlan(1, profile);
        Path ledgerPath = temporary.resolve("qualification-ledger.json");
        Path resultPath = writeCorrectedQualificationEvidence(plan, profile, ledgerPath);

        ArrayNode refs = StrategyOperatingCharacteristicsParallelV1.validateCorrectedDevelopmentArtifacts(
                resultPath, ledgerPath, plan, profile);
        assertThat(refs).hasSize(4);
        ObjectNode measurement = StrategyOperatingCharacteristicsParallelV1.validateCorrectedRunMeasurement(
                resultPath, ledgerPath, plan, profile, 1);
        assertThat(measurement.path("fresh_full_wave").asBoolean()).isTrue();
        assertThat(measurement.path("resource_probe_complete").asBoolean()).isTrue();

        Path artifact = temporary.resolve("artifacts").resolve("slot-0.json");
        ObjectNode tampered = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(artifact));
        ((ObjectNode) tampered.path("row")).put("event_count", 451);
        rehash((ObjectNode) tampered.path("row"));
        rehash(tampered);
        Files.writeString(artifact, JsonHashes.mapper().writeValueAsString(tampered));
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsParallelV1.validateCorrectedDevelopmentArtifacts(
                resultPath, ledgerPath, plan, profile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byte hash mismatch");

        rewriteEvidenceReferences(resultPath, ledgerPath, artifact);
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsParallelV1.validateCorrectedDevelopmentArtifacts(
                resultPath, ledgerPath, plan, profile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compact worker projection differs");

        ObjectNode accountingTamper = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(artifact));
        ObjectNode eventBook = (ObjectNode) accountingTamper.path("row").path("portfolio_summary").path("event_book");
        eventBook.put("ending_equity_usdt", 1011D);
        rehash((ObjectNode) accountingTamper.path("row"));
        rehash(accountingTamper);
        Files.writeString(artifact, JsonHashes.mapper().writeValueAsString(accountingTamper));
        rewriteEvidenceReferences(resultPath, ledgerPath, artifact);
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsParallelV1.validateCorrectedDevelopmentArtifacts(
                resultPath, ledgerPath, plan, profile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("equity does not reconcile");
    }

    @Test
    void qualificationValidationRejectsAReceiptRetaggedAsFrozenAccounting() {
        ObjectNode profile = qualifiedProfile();
        ObjectNode plan = correctedPlan(1, profile);
        ObjectNode receipt = (ObjectNode) profile.path("qualification_receipt");
        receipt.put("accounting_version", "FROZEN_V5");
        rehash(receipt);
        rehash(profile);

        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.validateQualification(
                planOptions(plan, profile)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("corrected qualification receipt");
    }

    private Path writeCorrectedQualificationEvidence(ObjectNode plan, ObjectNode profile,
            Path ledgerPath) throws Exception {
        return writeCorrectedQualificationEvidence(plan, profile, ledgerPath,
                profile.path("effective_workers").asInt(1));
    }

    /**
     * Build one self-contained run.  The profile describes the admitted
     * parallel capacity; the serial run deliberately requests one worker
     * while retaining the same plan/profile binding so the coordinator checks
     * the serial/parallel equivalence contract instead of comparing two
     * unrelated plans.
     */
    private Path writeCorrectedQualificationEvidence(ObjectNode plan, ObjectNode profile,
            Path ledgerPath, int requestedWorkers) throws Exception {
        return writeCorrectedQualificationEvidence(plan, profile, ledgerPath, requestedWorkers, null);
    }

    private Path writeCorrectedQualificationEvidence(ObjectNode plan, ObjectNode profile,
            Path ledgerPath, int requestedWorkers, String runIdOverride) throws Exception {
        if (requestedWorkers <= 0 || requestedWorkers > profile.path("effective_workers").asInt(0)) {
            throw new IllegalArgumentException("fixture requested worker count is outside the profile admission");
        }
        String runId = runIdOverride == null
                ? UUID.nameUUIDFromBytes(ledgerPath.toAbsolutePath().toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()
                : runIdOverride;
        Path root = ledgerPath.toAbsolutePath().normalize().getParent();
        Path artifactRoot = root.resolve("artifacts");
        Files.createDirectories(artifactRoot);
        ArrayNode refs = JsonHashes.mapper().createArrayNode();
        List<StrategyOperatingCharacteristicsParallelV1.Slot> slots = correctedDevelopmentSlots(plan);
        for (int index = 0; index < slots.size(); index++) {
            StrategyOperatingCharacteristicsParallelV1.Slot slot = slots.get(index);
            ObjectNode raw = correctedWorkerRawResult(slot, 450);
            ObjectNode artifact = correctedWorkerArtifact(slot, raw, runId);
            Path path = artifactRoot.resolve("slot-" + index + ".json");
            Files.writeString(path, JsonHashes.mapper().writeValueAsString(artifact));
            refs.addObject().put("slot_id", slot.id()).put("status", "COMPLETE")
                    .put("attempt", 1).put("relative_path", "artifacts/slot-" + index + ".json")
                    .put("content_sha256", artifact.path("content_sha256").asText())
                    .put("byte_sha256", JsonHashes.sha256(path)).put("bytes", Files.size(path));
        }

        ObjectNode ledger = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyOperatingCharacteristicsParallelV1.LEDGER_SCHEMA)
                .put("version", 1).put("status", "OPEN")
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("profile_sha256", profile.path("content_sha256").asText()).put("mode", "FULL")
                .put("fixed_evaluator", StrategyFixedBaselineCorrectedV1.EVALUATOR_ID)
                .put("compact_references_only", true).put("retain_abandoned_reservations", true)
                .put("retry_limit_per_slot", 1)
                .put("accounting_version", StrategyFixedBaselineCorrectedV1.ACCOUNTING_VERSION)
                .put("run_id", runId);
        ArrayNode inventory = ledger.putArray("slot_inventory");
        for (StrategyOperatingCharacteristicsParallelV1.Slot slot : slots) inventory.add(slot.toJson());
        ledger.putArray("attempts");
        ledger.set("terminal", refs.deepCopy());
        ledger.set("execution_profile", profile.deepCopy());
        rehash(ledger);
        Files.writeString(ledgerPath, JsonHashes.mapper().writeValueAsString(ledger));

        ObjectNode measurement = measurement(profile, 1, 1, 1)
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("ledger_content_sha256", ledger.path("content_sha256").asText())
                .put("executor_identity_sha256", plan.path("executor_identity_sha256").asText())
                .put("executor_source_sha256", plan.path("executor_build_input_fingerprint").asText())
                .put("host_identity_sha256", StrategyOperatingCharacteristicsParallelV1.hostFingerprintForProfile(profile))
                .put("run_id", runId).put("workers", requestedWorkers).put("launched_slot_count", slots.size())
                .put("completed_slot_count", slots.size()).put("fresh_full_wave", true)
                .put("resource_probe_complete", true);
        rehash(measurement);

        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyOperatingCharacteristicsParallelV1.CORRECTED_RESULT_SCHEMA)
                .put("version", 1).put("status", "COMPLETE").put("mode", "FULL")
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("fixed_evaluator", StrategyFixedBaselineCorrectedV1.EVALUATOR_ID)
                .put("shared_physical_evaluator", true).put("toy_statistic", false)
                .put("outcomes_opened", true).put("promotion_eligible", false)
                .put("activation_authorized", false).put("evidence_phase", "DEVELOPMENT")
                .put("measured", false).put("performance_claim", "NONE")
                .put("planned_slots", slots.size()).put("completed_slots", slots.size()).put("missing_slots", 0)
                .put("requested_workers", requestedWorkers)
                .put("effective_workers", profile.path("effective_workers").asInt())
                .put("ledger_content_sha256", ledger.path("content_sha256").asText())
                .put("retry_limit_per_slot", 1).put("stop_reason", "")
                .put("accounting_version", StrategyFixedBaselineCorrectedV1.ACCOUNTING_VERSION);
        result.set("result_refs", refs.deepCopy());
        result.putArray("missing");
        result.set("resource_measurement", measurement);
        rehash(result);
        Path resultPath = root.resolve("qualification-result.json");
        Files.writeString(resultPath, JsonHashes.mapper().writeValueAsString(result));
        return resultPath;
    }

    private static void rewriteEvidenceReferences(Path resultPath, Path ledgerPath,
            Path artifactPath) throws Exception {
        ObjectNode ledger = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(ledgerPath));
        ObjectNode artifact = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(artifactPath));
        String relative = "artifacts/" + artifactPath.getFileName();
        ObjectNode artifactReference = null;
        for (JsonNode node : ledger.path("terminal")) {
            if (relative.equals(node.path("relative_path").asText())) artifactReference = (ObjectNode) node;
        }
        if (artifactReference == null) throw new IllegalStateException("fixture terminal reference missing");
        artifactReference.put("content_sha256", artifact.path("content_sha256").asText())
                .put("byte_sha256", JsonHashes.sha256(artifactPath)).put("bytes", Files.size(artifactPath));
        rehash(ledger);
        Files.writeString(ledgerPath, JsonHashes.mapper().writeValueAsString(ledger));

        ObjectNode result = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(resultPath));
        for (JsonNode node : result.path("result_refs")) {
            if (relative.equals(node.path("relative_path").asText())) {
                ((ObjectNode) node).put("content_sha256", artifact.path("content_sha256").asText())
                        .put("byte_sha256", JsonHashes.sha256(artifactPath)).put("bytes", Files.size(artifactPath));
            }
        }
        rehash(result);
        Files.writeString(resultPath, JsonHashes.mapper().writeValueAsString(result));
    }

    private static List<StrategyOperatingCharacteristicsParallelV1.Slot> correctedDevelopmentSlots(ObjectNode plan) {
        List<StrategyOperatingCharacteristicsParallelV1.Slot> slots = new ArrayList<>();
        for (JsonNode cell : plan.path("development_seed_cells")) {
            int replication = 0;
            for (JsonNode seed : cell.path("development_seeds")) {
                slots.add(new StrategyOperatingCharacteristicsParallelV1.Slot(
                        plan.path("content_sha256").asText(), "FULL", cell.path("scenario").asText(),
                        cell.path("effect_size").asDouble(), replication++, seed.asLong(), slots.size()));
            }
        }
        return slots;
    }

    private static StrategyOperatingCharacteristicsParallelV1.Slot correctedPrefixSlot(ObjectNode plan, int cellIndex) {
        JsonNode cell = plan.path("development_prefix_seed_cells").get(cellIndex);
        return new StrategyOperatingCharacteristicsParallelV1.Slot(
                plan.path("content_sha256").asText(), "PREFIX", cell.path("scenario").asText(),
                cell.path("effect_size").asDouble(), 0, cell.path("prefix_seeds").get(0).asLong(), cellIndex);
    }

    private static ObjectNode correctedWorkerArtifact(StrategyOperatingCharacteristicsParallelV1.Slot slot,
            ObjectNode raw, String runId) throws Exception {
        return correctedWorkerArtifact(slot, raw, runId, 450, 450, 288);
    }

    private static ObjectNode correctedWorkerArtifact(StrategyOperatingCharacteristicsParallelV1.Slot slot,
            ObjectNode raw, String runId, int eventCount, int pairedCount, int independentUnits) throws Exception {
        ObjectNode row = JsonHashes.mapper().createObjectNode()
                .put("cell", slot.scenario()).put("effect_size", slot.effectSize())
                .put("replication", slot.replication()).put("seed", slot.seed())
                .put("status", "COMPLETE").put("decision", false)
                .put("event_count", eventCount).put("paired_count", pairedCount)
                .put("independent_units", independentUnits)
                .put("generator_input_sha256", raw.path("physical_input_sha256").asText())
                .put("raw_evaluator_result_content_sha256", raw.path("content_sha256").asText())
                .put("raw_evaluator_result_canonical_sha256", JsonHashes.canonicalSha256(raw))
                .put("raw_evaluator_result_byte_sha256", JsonHashes.sha256(JsonHashes.mapper().writeValueAsBytes(raw)))
                .put("economic_semantic_sha256", raw.path("corrected_economic_semantic_sha256").asText());
        row.set("metrics", raw.path("metrics").deepCopy());
        row.set("portfolio_summary", raw.path("portfolio").deepCopy());
        row.set("disposition", raw.path("disposition").deepCopy());
        ObjectNode receipt = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyOperatingCharacteristicsSuccessorV1.CORRECTED_RECEIPT_SCHEMA)
                .put("version", 1).put("origin", "SHARED_FIXED_EVALUATOR_IN_PROCESS")
                .put("result_content_sha256", raw.path("content_sha256").asText())
                .put("economic_semantic_sha256", raw.path("corrected_economic_semantic_sha256").asText())
                .put("executor_identity_sha256", raw.path("executor_identity_sha256").asText())
                .put("source_input_sha256", raw.path("physical_input_sha256").asText())
                .put("result_schema", raw.path("schema").asText()).put("status", "COMPLETE");
        receipt.set("metrics", raw.path("metrics").deepCopy());
        rehash(receipt);
        row.set("evaluator_receipt", receipt);
        row.set("raw_evaluator_result", raw.deepCopy());
        rehash(row);

        ObjectNode identity = JsonHashes.mapper().createObjectNode();
        identity.putObject("executable").put("kind", "JAR").put("sha256", "1".repeat(64));
        identity.putObject("compiled").put("input_fingerprint", "2".repeat(64));
        ObjectNode artifact = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evaluator-operating-characteristics-corrected-parallel-slot-result/1")
                .put("version", 1).put("status", "COMPLETE").put("slot_id", slot.id())
                .put("plan_sha256", slot.planSha256()).put("mode", slot.mode())
                .put("scenario", slot.scenario()).put("effect_size", slot.effectSize())
                .put("replication", slot.replication()).put("seed", slot.seed()).put("attempt", 1)
                .put("fixed_evaluator", StrategyFixedBaselineCorrectedV1.EVALUATOR_ID)
                .put("promotion_eligible", false).put("activation_authorized", false)
                .put("accounting_version", StrategyFixedBaselineCorrectedV1.ACCOUNTING_VERSION)
                .put("run_id", runId)
                .put("portable_economic_sha256", StrategyOperatingCharacteristicsParallelV1
                        .portableEconomicSha256ForTest(raw));
        artifact.set("row", row);
        artifact.set("executor_identity", identity);
        rehash(artifact);
        return artifact;
    }

    private static ObjectNode correctedWorkerRawResult() throws Exception {
        return correctedWorkerRawResult(450);
    }

    private static ObjectNode correctedWorkerRawResult(StrategyOperatingCharacteristicsParallelV1.Slot slot,
            int tradeCount) throws Exception {
        if (slot == null) return correctedWorkerRawResult(tradeCount);
        ObjectNode syntheticInput = JsonHashes.mapper().createObjectNode()
                .put("plan_sha256", slot.planSha256()).put("mode", slot.mode())
                .put("replication", slot.replication()).put("cell", slot.scenario())
                .put("effect_size", slot.effectSize()).put("seed", slot.seed())
                .put("episodes", tradeCount);
        return correctedWorkerRawResult(tradeCount, JsonHashes.canonicalSha256(syntheticInput));
    }

    private static ObjectNode correctedWorkerRawResult(int tradeCount) throws Exception {
        return correctedWorkerRawResult(tradeCount, "d".repeat(64));
    }

    private static ObjectNode correctedWorkerRawResult(int tradeCount, String physicalInputHash) throws Exception {
        int independentUnits = tradeCount == 450 ? 288 : 1;
        int pairedClusters = tradeCount == 450 ? 288 : 1;
        ObjectNode eventBook = qualificationBookWithGeometry("event", 100D, 101D, tradeCount);
        ObjectNode controlBook = qualificationBookWithGeometry("control", 100D, 100D, tradeCount);
        ObjectNode frozen = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-fixed-baseline-result/1").put("version", 1)
                .put("stage", "FIXED_BASELINE").put("executor_identity_sha256", "1".repeat(64))
                .put("baseline_sha256", "a".repeat(64)).put("control_spec_sha256", "b".repeat(64))
                .put("experiment_sha256", "c".repeat(64)).put("physical_input_sha256", physicalInputHash);
        frozen.putObject("evaluator").put("name", "TradeLifecycleV5");
        ObjectNode sourceMetrics = frozen.putObject("metrics").put("max_drawdown_usdt", 0D)
                .put("net_pnl_usdt", (double) tradeCount)
                .put("paired_count", tradeCount)
                .put("event_tested_cluster_count", independentUnits)
                .put("paired_tested_cluster_count", pairedClusters)
                .put("independent_market_episode_count", independentUnits);
        frozen.set("setup_events", evidenceInventory("event", "event_id", tradeCount));
        frozen.set("control_selections", evidenceInventory("control", "event_id", tradeCount));
        frozen.set("independent_market_episodes", independentClusterInventory(tradeCount, independentUnits));
        frozen.set("attempts", pairedAttemptInventory(eventBook, controlBook, tradeCount));
        ObjectNode attrition = frozen.putObject("matching_attrition")
                .put("schema", "strategy-matching-attrition/1").put("version", 1).put("outcome_blind", true);
        attrition.putObject("sequential_attrition")
                .put("feature_rows_to_setup_events", tradeCount)
                .put("setup_events_to_admitted_events", tradeCount)
                .put("admitted_events_to_control_selections", tradeCount)
                .put("control_selections_to_matched_controls", tradeCount)
                .put("complete_pairs_to_paired_clusters", pairedClusters)
                .put("admitted_events_to_event_clusters", independentUnits);
        attrition.putObject("marginal_attrition").put("merged_scheduled_lifecycle_clusters", independentUnits);
        ObjectNode portfolio = frozen.putObject("portfolio");
        portfolio.set("event_book", eventBook);
        portfolio.set("control_book", controlBook);
        rehash(frozen);
        ObjectNode raw = StrategyFixedBaselineCorrectedV1.correctFrozenResultForTest(frozen, "1".repeat(64));
        raw.put("status", "COMPLETE").put("event_count", tradeCount)
                .put("matched_control_count", tradeCount)
                .put("admitted_event_count", tradeCount)
                .put("trade_count", tradeCount * 2)
                .put("market_episode_count", tradeCount)
                .put("independent_market_episode_count", independentUnits)
                .putObject("disposition").put("primary_reason", "NOT_ELIGIBLE");
        raw.put("corrected_economic_semantic_sha256",
                StrategyFixedBaselineCorrectedV1.correctedEconomicSha256ForValidation(raw));
        raw.put("corrected_semantic_sha256", correctedSemanticHashForTest(raw));
        rehash(raw);
        return raw;
    }

    private static ArrayNode evidenceInventory(String prefix, String idField, int count) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        for (int index = 0; index < count; index++) {
            result.addObject().put(idField, prefix + "-" + index);
        }
        return result;
    }

    private static ArrayNode independentClusterInventory(int tradeCount, int clusterCount) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        int doubleSourceClusters = tradeCount == 450 ? 162 : 1;
        int source = 0;
        for (int index = 0; index < clusterCount; index++) {
            ObjectNode cluster = result.addObject().put("episode_id", "cluster-" + index)
                    .put("cluster_id", "cluster-hash-" + index);
            ArrayNode sourceIds = cluster.putArray("source_episode_ids");
            sourceIds.add("event-" + source++);
            if (index < doubleSourceClusters) sourceIds.add("event-" + source++);
        }
        return result;
    }

    private static ArrayNode pairedAttemptInventory(ObjectNode eventBook, ObjectNode controlBook, int count) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        for (int index = 0; index < count; index++) {
            JsonNode eventTrade = eventBook.path("trades").get(index);
            JsonNode controlTrade = controlBook.path("trades").get(index);
            ObjectNode attempt = result.addObject().put("event_id", eventTrade.path("episode_id").asText())
                    .put("status", "COMPLETE");
            attempt.set("event_trade", eventTrade.deepCopy());
            attempt.set("control_trade", controlTrade.deepCopy());
            attempt.put("paired_net_pnl_usdt", eventTrade.path("net_pnl_usdt").asDouble()
                    - controlTrade.path("net_pnl_usdt").asDouble());
        }
        return result;
    }

    private static String correctedSemanticHashForTest(ObjectNode value) throws Exception {
        Method method = StrategyFixedBaselineCorrectedV1.class
                .getDeclaredMethod("correctedSemanticHash", ObjectNode.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }

    private static ObjectNode qualificationBook(double starting, double ending, ObjectNode... trades) {
        ObjectNode book = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-fixed-baseline-portfolio-book/legacy")
                .put("starting_equity_usdt", starting).put("ending_equity_usdt", ending);
        ArrayNode rows = book.putArray("trades");
        for (ObjectNode trade : trades) rows.add(trade);
        return book;
    }

    private static ObjectNode qualificationBookWithGeometry(String prefix, double entryPrice,
            double exitPrice) {
        return qualificationBookWithGeometry(prefix, entryPrice, exitPrice, 450);
    }

    private static ObjectNode qualificationBookWithGeometry(String prefix, double entryPrice,
            double exitPrice, int tradeCount) {
        ObjectNode book = qualificationBook(1000D,
                1000D + tradeCount * (exitPrice - entryPrice));
        ArrayNode trades = (ArrayNode) book.path("trades");
        for (int index = 0; index < tradeCount; index++) {
            long minute = index * 2L;
            String entry = String.format("2021-01-01T%02d:%02d:00Z", minute / 60, minute % 60);
            String exit = String.format("2021-01-01T%02d:%02d:00Z", (minute + 1L) / 60,
                    (minute + 1L) % 60);
            trades.add(qualificationTrade(prefix + "-" + index, entry, exit, entryPrice, exitPrice));
        }
        return book;
    }

    private static ObjectNode qualificationTrade(String id, String entry, String exit,
            double entryPrice, double exitPrice) {
        ObjectNode trade = JsonHashes.mapper().createObjectNode()
                .put("episode_id", id).put("entry_price", entryPrice).put("exit_price", exitPrice)
                .put("quantity", 1D).put("gross_pnl_usdt", exitPrice - entryPrice)
                .put("fees_usdt", 0D).put("slippage_usdt", 0D).put("capacity_debit_usdt", 0D)
                .put("net_pnl_usdt", exitPrice - entryPrice)
                .put("account_currency", "USDT").put("direction", "long")
                .put("instrument_type", "SPOT").put("instrument", "BINANCE_SPOT");
        trade.putArray("portfolio_mark_points");
        ObjectNode lifecycle = trade.putObject("lifecycle")
                .put("schema", "strategy-v5-trade-lifecycle/1").put("version", 1)
                .put("status", "COMPLETE").put("fixture_only", true)
                .put("provenance", "SELF_CONTAINED_TEST_FIXTURE")
                .put("decision_time", entry).put("entry_time", entry)
                .put("entry_price", entryPrice).put("direction", "long")
                .put("instrument_type", "SPOT").put("quantity", 1D)
                .put("contract_multiplier", 1D).put("max_lifecycle_ms", 60_000L);
        lifecycle.putArray("exits").addObject().put("time", exit).put("availability_time", exit)
                .put("price", exitPrice).put("quantity", 1D).put("fraction", 1D)
                .put("fees_usd", 0D).put("slippage_usd", 0D).put("funding_usd", 0D)
                .put("capacity_debit_usd", 0D).put("net_pnl_usd", exitPrice - entryPrice);
        return trade;
    }

    @Test
    void correctedRunRefusesAClassesOnlyExecutorBeforeOpeningInputs() {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode plan = correctedPlan(1, profile);
        ObjectNode options = planOptions(plan, profile)
                .put("ledger", temporary.resolve("run-ledger.json").toString());

        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.run(options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("packaged");
    }

    @Test
    void qualificationRefusesAProfileBoundToDifferentLiveHostBeforeOutcomeFiles() {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode plan = correctedPlan(1, profile);
        ObjectNode options = planOptions(plan, profile)
                .put("parallel_result", temporary.resolve("missing-parallel.json").toString())
                .put("parallel_ledger", temporary.resolve("missing-parallel-ledger.json").toString())
                .put("serial_result", temporary.resolve("missing-serial.json").toString())
                .put("serial_ledger", temporary.resolve("missing-serial-ledger.json").toString());

        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.qualifyDevelopment(options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currently probed host");
    }

    @Test
    void correctedSchedulerKeepsSerialAndParallelWorkerCountsAndCorrectedResultIdentity() throws Exception {
        AtomicInteger serialCalls = new AtomicInteger();
        ObjectNode serialProfile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode serialPlan = correctedPlan(1, serialProfile);
        ObjectNode serial = runCorrectedScheduler(serialPlan, serialProfile,
                temporary.resolve("serial-ledger.json"), serialCalls);

        AtomicInteger parallelCalls = new AtomicInteger();
        ObjectNode parallelProfile = profile(2, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode parallelPlan = correctedPlan(2, parallelProfile);
        ObjectNode parallel = runCorrectedScheduler(parallelPlan, parallelProfile,
                temporary.resolve("parallel-ledger.json"), parallelCalls);

        assertThat(serial.path("schema").asText())
                .isEqualTo("strategy-evaluator-operating-characteristics-corrected-parallel-result/1");
        assertThat(parallel.path("schema").asText())
                .isEqualTo("strategy-evaluator-operating-characteristics-corrected-parallel-result/1");
        assertThat(serial.path("accounting_version").asText())
                .isEqualTo(StrategyFixedBaselineCorrectedV1.ACCOUNTING_VERSION);
        assertThat(parallel.path("accounting_version").asText())
                .isEqualTo(StrategyFixedBaselineCorrectedV1.ACCOUNTING_VERSION);
        assertThat(serial.path("requested_workers").asInt()).isOne();
        assertThat(serial.path("effective_workers").asInt()).isOne();
        assertThat(serial.path("planned_slots").asInt()).isEqualTo(4);
        assertThat(serial.path("completed_slots").asInt()).isEqualTo(4);
        assertThat(serialCalls).hasValue(4);
        assertThat(parallel.path("requested_workers").asInt()).isEqualTo(2);
        assertThat(parallel.path("effective_workers").asInt()).isEqualTo(2);
        assertThat(parallel.path("planned_slots").asInt()).isEqualTo(8);
        assertThat(parallel.path("completed_slots").asInt()).withFailMessage("parallel result: %s", parallel).isEqualTo(8);
        assertThat(parallelCalls).hasValue(8);
    }

    @Test
    void correctedPrefixRunCompletesWithoutClaimingAFullWaveMeasurement() throws Exception {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode plan = correctedPlan(1, profile);
        ObjectNode options = planOptions(plan, profile).put("mode", "PREFIX")
                .put("ledger", temporary.resolve("prefix-ledger.json").toString());
        ObjectNode result = StrategyOperatingCharacteristicsParallelV1.runCorrectedWithExecutorForTest(
                options, (slot, scratch) -> workerRow(slot),
                resourceProbe(28, 32L * GIB, 256L * GIB));

        assertThat(result.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(result.path("mode").asText()).isEqualTo("PREFIX");
        assertThat(result.path("resource_measurement").path("fresh_full_wave").asBoolean()).isFalse();
        assertThat(result.path("measured").asBoolean()).isFalse();
        assertThat(result.path("performance_claim").asText()).isEqualTo("NONE");
    }

    @Test
    void correctedPrefixArtifactsReplaySmallBooksButCannotCertifyFullGeometry() throws Exception {
        ObjectNode profile = profile(2, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode plan = correctedPlan(2, profile);
        // Two prefix replications per cell produce eight PREFIX slots.  The
        // raw books intentionally contain ten trades: PREFIX accounting is
        // fully replayed, while the FULL 450-event guard remains separate.
        for (JsonNode node : plan.path("development_prefix_seed_cells")) {
            ObjectNode cell = (ObjectNode) node;
            long first = cell.path("prefix_seeds").get(0).asLong();
            cell.withArray("prefix_seeds").add(first + 1L);
        }
        rehash(plan);
        Path ledger = temporary.resolve("prefix-corrected-ledger").resolve("ledger.json");
        ObjectNode result = StrategyOperatingCharacteristicsParallelV1.runCorrectedWithExecutorForTest(
                planOptions(plan, profile).put("mode", "PREFIX").put("ledger", ledger.toString()),
                (slot, scratch) -> {
                    ObjectNode raw = correctedWorkerRawResult(slot, 10);
                    return (ObjectNode) correctedWorkerArtifact(slot, raw, UUID.randomUUID().toString(),
                            10, 10, 1).path("row").deepCopy();
                }, resourceProbe(28, 32L * GIB, 256L * GIB));

        assertThat(result.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(result.path("mode").asText()).isEqualTo("PREFIX");
        assertThat(result.path("planned_slots").asInt()).isEqualTo(8);
        assertThat(result.path("completed_slots").asInt()).isEqualTo(8);
        assertThat(result.path("measured").asBoolean()).isFalse();
        String runId = JsonHashes.mapper().readTree(Files.readString(ledger)).path("run_id").asText();
        assertThat(runId).isNotBlank();
        for (JsonNode reference : result.path("result_refs")) {
            Path artifactPath = ledger.getParent().resolve(reference.path("relative_path").asText()).normalize();
            ObjectNode artifact = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(artifactPath));
            ObjectNode raw = (ObjectNode) artifact.path("row").path("raw_evaluator_result");
            assertThat(raw.path("event_count").asInt()).isEqualTo(10);
            assertThat(raw.path("matched_control_count").asInt()).isEqualTo(10);
            assertThat(artifact.path("portable_economic_sha256").asText())
                    .isEqualTo(StrategyOperatingCharacteristicsParallelV1.portableEconomicSha256ForTest(raw));
        }
    }

    @Test
    void correctedPrefixArtifactSemanticValidatorReplaysTheCorrectedBooks() throws Exception {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode plan = correctedPlan(1, profile);
        JsonNode cell = plan.path("development_prefix_seed_cells").get(0);
        StrategyOperatingCharacteristicsParallelV1.Slot slot = new StrategyOperatingCharacteristicsParallelV1.Slot(
                plan.path("content_sha256").asText(), "PREFIX", cell.path("scenario").asText(),
                cell.path("effect_size").asDouble(), 0, cell.path("prefix_seeds").get(0).asLong(), 0);
        ObjectNode artifact = correctedWorkerArtifact(slot, correctedWorkerRawResult(slot, 10),
                UUID.randomUUID().toString(), 10, 10, 1);
        Path path = temporary.resolve("prefix-semantic-artifact.json");
        Files.writeString(path, JsonHashes.mapper().writeValueAsString(artifact));
        StrategyOperatingCharacteristicsParallelV1.validateCorrectedArtifactForTest(
                artifact, path, slot, plan, artifact.path("run_id").asText());
    }

    @Test
    void strictCorrectedArtifactRejectsARehashedRawResultTransplantedAcrossSlots() throws Exception {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode plan = correctedPlan(1, profile);
        StrategyOperatingCharacteristicsParallelV1.Slot target = correctedPrefixSlot(plan, 0);
        StrategyOperatingCharacteristicsParallelV1.Slot source = correctedPrefixSlot(plan, 1);
        ObjectNode sourceRaw = correctedWorkerRawResult(source, 10);
        ObjectNode artifact = correctedWorkerArtifact(target, correctedWorkerRawResult(target, 10),
                UUID.randomUUID().toString(), 10, 10, 1);
        Path validPath = temporary.resolve("valid-prefix-artifact.json");
        Files.writeString(validPath, JsonHashes.mapper().writeValueAsString(artifact));
        StrategyOperatingCharacteristicsParallelV1.validateCorrectedArtifactForTest(
                artifact, validPath, target, plan, artifact.path("run_id").asText());
        ObjectNode row = (ObjectNode) artifact.path("row");
        row.set("raw_evaluator_result", sourceRaw);
        row.put("generator_input_sha256", sourceRaw.path("physical_input_sha256").asText())
                .put("raw_evaluator_result_content_sha256", sourceRaw.path("content_sha256").asText())
                .put("raw_evaluator_result_canonical_sha256", JsonHashes.canonicalSha256(sourceRaw))
                .put("raw_evaluator_result_byte_sha256", JsonHashes.sha256(JsonHashes.mapper().writeValueAsBytes(sourceRaw)))
                .put("economic_semantic_sha256", sourceRaw.path("corrected_economic_semantic_sha256").asText());
        ObjectNode receipt = (ObjectNode) row.path("evaluator_receipt");
        receipt.put("result_content_sha256", sourceRaw.path("content_sha256").asText())
                .put("economic_semantic_sha256", sourceRaw.path("corrected_economic_semantic_sha256").asText())
                .put("source_input_sha256", sourceRaw.path("physical_input_sha256").asText());
        rehash(receipt);
        rehash(row);
        artifact.put("portable_economic_sha256",
                StrategyOperatingCharacteristicsParallelV1.portableEconomicSha256ForTest(sourceRaw));
        rehash(artifact);
        Path path = temporary.resolve("transplanted-slot-artifact.json");
        Files.writeString(path, JsonHashes.mapper().writeValueAsString(artifact));
        ObjectNode transplantedArtifact = artifact;

        assertThatThrownBy(() -> StrategyOperatingCharacteristicsParallelV1.validateCorrectedArtifactForTest(
                transplantedArtifact, path, target, plan, transplantedArtifact.path("run_id").asText()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("physical input descriptor");
    }

    @Test
    void strictCorrectedArtifactRequiresCurrentAlgorithmAndCorrectionBindings() throws Exception {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode plan = correctedPlan(1, profile);
        StrategyOperatingCharacteristicsParallelV1.Slot slot = correctedPrefixSlot(plan, 0);
        ObjectNode wrongAlgorithmArtifact = correctedWorkerArtifact(slot, correctedWorkerRawResult(slot, 10),
                UUID.randomUUID().toString(), 10, 10, 1);
        ObjectNode raw = (ObjectNode) wrongAlgorithmArtifact.path("row").path("raw_evaluator_result").deepCopy();
        raw.put("algorithm_fingerprint", "e".repeat(64));
        rehash(raw);
        replaceArtifactRaw(wrongAlgorithmArtifact, raw);
        Path path = temporary.resolve("wrong-algorithm-artifact.json");

        assertThatThrownBy(() -> StrategyOperatingCharacteristicsParallelV1.validateCorrectedArtifactForTest(
                wrongAlgorithmArtifact, path, slot, plan, wrongAlgorithmArtifact.path("run_id").asText()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("corrected raw evaluator");

        ObjectNode wrongBindingArtifact = correctedWorkerArtifact(slot, correctedWorkerRawResult(slot, 10),
                UUID.randomUUID().toString(), 10, 10, 1);
        raw = (ObjectNode) wrongBindingArtifact.path("row").path("raw_evaluator_result").deepCopy();
        ObjectNode binding = (ObjectNode) raw.path("corrected_input_binding");
        binding.put("physical_input_sha256", "f".repeat(64));
        raw.put("corrected_economic_semantic_sha256",
                StrategyFixedBaselineCorrectedV1.correctedEconomicSha256ForValidation(raw));
        rehash(raw);
        replaceArtifactRaw(wrongBindingArtifact, raw);
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsParallelV1.validateCorrectedArtifactForTest(
                wrongBindingArtifact, path, slot, plan, wrongBindingArtifact.path("run_id").asText()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input binding");
    }

    private static void replaceArtifactRaw(ObjectNode artifact, ObjectNode raw) throws Exception {
        ObjectNode row = (ObjectNode) artifact.path("row");
        row.set("raw_evaluator_result", raw.deepCopy());
        row.put("generator_input_sha256", raw.path("physical_input_sha256").asText())
                .put("raw_evaluator_result_content_sha256", raw.path("content_sha256").asText())
                .put("raw_evaluator_result_canonical_sha256", JsonHashes.canonicalSha256(raw))
                .put("raw_evaluator_result_byte_sha256",
                        JsonHashes.sha256(JsonHashes.mapper().writeValueAsBytes(raw)))
                .put("economic_semantic_sha256", raw.path("corrected_economic_semantic_sha256").asText());
        ObjectNode receipt = (ObjectNode) row.path("evaluator_receipt");
        receipt.put("result_content_sha256", raw.path("content_sha256").asText())
                .put("economic_semantic_sha256", raw.path("corrected_economic_semantic_sha256").asText())
                .put("source_input_sha256", raw.path("physical_input_sha256").asText());
        rehash(receipt);
        rehash(row);
        artifact.put("portable_economic_sha256",
                StrategyOperatingCharacteristicsParallelV1.portableEconomicSha256ForTest(raw));
        rehash(artifact);
    }

    @Test
    void correctedResumeRejectsARehashedTerminalRawResultWithStaleAlgorithmBinding() throws Exception {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode plan = correctedPlan(1, profile);
        plan.put("schema", StrategyOperatingCharacteristicsSuccessorV1.CORRECTED_PLAN_SCHEMA)
                .put("binding_fixed_evaluator", StrategyFixedBaselineCorrectedV1.EVALUATOR_ID)
                .put("development_exposure", true)
                .put("predecessor_diagnosis_sha256", "a".repeat(64))
                .put("lineage_inventory_sha256", "b".repeat(64));
        for (int index = 0; index < plan.path("cells").size(); index++) {
            ObjectNode cellNode = (ObjectNode) plan.path("cells").get(index);
            cellNode.set("prefix_seeds", plan.path("development_prefix_seed_cells").get(index)
                    .path("prefix_seeds").deepCopy());
        }
        rehash(plan);
        JsonNode cell = plan.path("cells").get(0);
        StrategyOperatingCharacteristicsParallelV1.Slot slot = new StrategyOperatingCharacteristicsParallelV1.Slot(
                plan.path("content_sha256").asText(), "PREFIX", cell.path("scenario").asText(),
                cell.path("effect_size").asDouble(), 0, cell.path("prefix_seeds").get(0).asLong(), 0);
        ObjectNode artifact = correctedWorkerArtifact(slot, correctedWorkerRawResult(slot, 10),
                UUID.randomUUID().toString(), 10, 10, 1);
        ObjectNode validLedger = terminalLedger(plan, slot, artifact);
        StrategyOperatingCharacteristicsSuccessorV1.validateLedgerForTest(plan, validLedger);
        ObjectNode raw = (ObjectNode) artifact.path("row").path("raw_evaluator_result").deepCopy();
        raw.put("algorithm_fingerprint", "e".repeat(64));
        rehash(raw);
        replaceArtifactRaw(artifact, raw);
        ObjectNode ledger = terminalLedger(plan, slot, artifact);

        assertThatThrownBy(() -> StrategyOperatingCharacteristicsSuccessorV1.validateLedgerForTest(plan, ledger))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("corrected raw evaluator");
    }

    private static ObjectNode terminalLedger(ObjectNode plan,
            StrategyOperatingCharacteristicsParallelV1.Slot slot, ObjectNode artifact) {
        ObjectNode row = (ObjectNode) artifact.path("row");
        ObjectNode output = (ObjectNode) row.path("evaluator_receipt");
        ObjectNode attempt = JsonHashes.mapper().createObjectNode()
                .put("attempt_id", "PREFIX|NO_EDGE|0:0")
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("mode", slot.mode()).put("scenario", slot.scenario())
                .put("effect_size", slot.effectSize()).put("replication", slot.replication())
                .put("seed", slot.seed()).put("status", "COMPLETE")
                .put("outcomes_opened", true).put("promotion_eligible", false)
                .put("internal_evaluator", true)
                .put("executor_identity_sha256", plan.path("executor_identity_sha256").asText())
                .put("input_sha256", syntheticInputSha(plan, slot, 10))
                .put("evaluator_output_sha256", output.path("content_sha256").asText());
        attempt.set("result_row", row.deepCopy());
        attempt.set("evaluator_output", output.deepCopy());
        ArrayNode attempts = JsonHashes.mapper().createArrayNode().add(attempt);
        ObjectNode ledger = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyOperatingCharacteristicsSuccessorV1.LEDGER_SCHEMA)
                .put("version", 1).put("append_only", true)
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("mode", slot.mode());
        ledger.set("attempts", attempts);
        ledger.put("content_sha256", JsonHashes.ownHash(ledger));
        return ledger;
    }

    private static String syntheticInputSha(ObjectNode plan,
            StrategyOperatingCharacteristicsParallelV1.Slot slot, int episodes) {
        ObjectNode input = JsonHashes.mapper().createObjectNode()
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("mode", slot.mode()).put("replication", slot.replication())
                .put("cell", slot.scenario()).put("effect_size", slot.effectSize())
                .put("seed", slot.seed()).put("episodes", episodes);
        return JsonHashes.canonicalSha256(input);
    }

    @Test
    void qualificationRejectsSerialAndParallelEvidenceWithTheSameRunIdentity() throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name").toLowerCase().contains("windows"),
                "singly-linked qualification custody requires the Unix test environment");
        ObjectNode identity = executorIdentity("1", "2");
        StrategyOperatingCharacteristicsParallelV1.ResourceProbe probe = resourceProbe(
                28, 32L * GIB, 256L * GIB);
        ObjectNode profile = StrategyOperatingCharacteristicsCorrectedParallelV1.executionProfileForTest(
                JsonHashes.mapper().createObjectNode().put("requested_workers", 1), probe, identity);
        ObjectNode plan = correctedPlan(1, profile);
        String runId = "11111111-1111-4111-8111-111111111111";
        Path parallelLedger = temporary.resolve("same-run-parallel").resolve("ledger.json");
        Path serialLedger = temporary.resolve("same-run-serial").resolve("ledger.json");
        Path parallelResult = writeCorrectedQualificationEvidence(plan, profile, parallelLedger, 1, runId);
        Path serialResult = writeCorrectedQualificationEvidence(plan, profile, serialLedger, 1, runId);
        ObjectNode options = planOptions(plan, profile)
                .put("parallel_result", parallelResult.toString())
                .put("parallel_ledger", parallelLedger.toString())
                .put("serial_result", serialResult.toString())
                .put("serial_ledger", serialLedger.toString());

        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.qualifyDevelopmentForTest(
                options, probe, identity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct coordinator identities");
    }

    @Test
    void correctedRouteCannotBeSelectedByCallingFrozenCoordinator() throws Exception {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        ObjectNode corrected = correctedPlan(1, profile);
        ObjectNode options = planOptions(corrected, profile)
                .put("ledger", temporary.resolve("legacy-route-ledger.json").toString());

        assertThatThrownBy(() -> StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options,
                (slot, scratch) -> workerRow(slot), resourceProbe(28, 32L * GIB, 256L * GIB)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("corrupt slot artifact");
    }

    @Test
    void trustedQualificationPipelineProducesAndReopensAQualifiedProfile() throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name").toLowerCase().contains("windows"),
                "singly-linked qualification custody requires the Unix test environment");
        ObjectNode identity = executorIdentity("1", "2");
        StrategyOperatingCharacteristicsParallelV1.ResourceProbe probe = resourceProbe(
                28, 32L * GIB, 256L * GIB);
        ObjectNode profile = StrategyOperatingCharacteristicsCorrectedParallelV1.executionProfileForTest(
                JsonHashes.mapper().createObjectNode().put("requested_workers", 2), probe, identity);
        ObjectNode plan = correctedPlan(2, profile);
        Path parallelLedger = temporary.resolve("qualification-parallel").resolve("ledger.json");
        Path serialLedger = temporary.resolve("qualification-serial").resolve("ledger.json");
        Path parallelResult = writeCorrectedQualificationEvidence(plan, profile, parallelLedger, 2);
        Path serialResult = writeCorrectedQualificationEvidence(plan, profile, serialLedger, 1);
        String parallelRunId = JsonHashes.mapper().readTree(Files.readString(parallelLedger)).path("run_id").asText();
        String serialRunId = JsonHashes.mapper().readTree(Files.readString(serialLedger)).path("run_id").asText();
        assertThat(parallelRunId).isNotBlank().isNotEqualTo(serialRunId);
        ObjectNode qualifyOptions = planOptions(plan, profile)
                .put("parallel_result", parallelResult.toString())
                .put("parallel_ledger", parallelLedger.toString())
                .put("serial_result", serialResult.toString())
                .put("serial_ledger", serialLedger.toString());

        ObjectNode qualified = StrategyOperatingCharacteristicsCorrectedParallelV1.qualifyDevelopmentForTest(
                qualifyOptions, probe, identity);
        assertThat(qualified.path("status").asText()).isEqualTo("QUALIFIED");
        ObjectNode qualifiedProfile = (ObjectNode) qualified.path("profile");
        ObjectNode validateOptions = planOptions(plan, qualifiedProfile)
                .put("parallel_result", parallelResult.toString())
                .put("parallel_ledger", parallelLedger.toString())
                .put("serial_result", serialResult.toString())
                .put("serial_ledger", serialLedger.toString());
        ObjectNode validation = StrategyOperatingCharacteristicsCorrectedParallelV1.validateQualificationForTest(
                validateOptions, probe, identity);
        assertThat(validation.path("status").asText()).isEqualTo("VALID");

        for (String measuredField : List.of("measured_wall_millis",
                "measured_max_aggregate_rss_bytes", "measured_max_disk_bytes")) {
            ObjectNode tamperedProfile = qualifiedProfile.deepCopy();
            ObjectNode tamperedReceipt = (ObjectNode) tamperedProfile.path("qualification_receipt");
            ObjectNode tamperedResource = (ObjectNode) tamperedReceipt.path("resource_envelope");
            tamperedResource.put(measuredField, tamperedResource.path(measuredField).asLong() + 1L);
            rehash(tamperedResource);
            rehash(tamperedReceipt);
            rehash(tamperedProfile);
            ObjectNode tamperedOptions = planOptions(plan, tamperedProfile)
                    .put("parallel_result", parallelResult.toString())
                    .put("parallel_ledger", parallelLedger.toString())
                    .put("serial_result", serialResult.toString())
                    .put("serial_ledger", serialLedger.toString());
            assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedParallelV1.validateQualificationForTest(
                    tamperedOptions, probe, identity))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("evidence bindings");
        }
    }

    @Test
    void correctedSuccessorPreflightHasItsOwnSchemaAndRejectsFrozenSuccessorPlan() {
        ObjectNode frozen = successorPlan(false);
        ObjectNode frozenResult = StrategyOperatingCharacteristicsSuccessorV1.preflight(frozen);
        assertThat(frozenResult.path("schema").asText())
                .isEqualTo("strategy-evaluator-operating-characteristics-successor-preflight/1");
        assertThat(frozenResult.path("binding_fixed_evaluator").asText()).doesNotContain("Corrected");

        ObjectNode corrected = successorPlan(true);
        ObjectNode correctedResult = StrategyOperatingCharacteristicsCorrectedSuccessorV1.preflight(corrected);
        assertThat(correctedResult.path("schema").asText())
                .isEqualTo(StrategyOperatingCharacteristicsCorrectedSuccessorV1.PREFLIGHT_SCHEMA);
        assertThat(correctedResult.path("binding_fixed_evaluator").asText())
                .isEqualTo(StrategyOperatingCharacteristicsCorrectedSuccessorV1.EVALUATOR_ID);
        assertThat(correctedResult.path("accounting_version").asText())
                .isEqualTo(StrategyOperatingCharacteristicsCorrectedSuccessorV1.ACCOUNTING_VERSION);

        ObjectNode tampered = corrected.deepCopy().put("accounting_version", "FROZEN_V5");
        rehash(tampered);
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsCorrectedSuccessorV1.preflight(tampered))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("corrected");
    }

    private static ObjectNode planOptions(ObjectNode plan, ObjectNode profile) {
        ObjectNode options = JsonHashes.mapper().createObjectNode();
        options.set("plan", plan);
        options.set("profile", profile);
        options.put("internal_test_seam", true);
        return options;
    }

    private static ObjectNode profileOptions(ObjectNode profile) {
        ObjectNode options = JsonHashes.mapper().createObjectNode();
        options.set("profile", profile);
        return options;
    }

    private static ObjectNode profile(int workers, StrategyOperatingCharacteristicsParallelV1.ResourceProbe probe) {
        return StrategyOperatingCharacteristicsCorrectedParallelV1.executionProfile(
                JsonHashes.mapper().createObjectNode().put("requested_workers", workers), probe);
    }

    private static ObjectNode executorIdentity(String executable, String source) {
        ObjectNode identity = JsonHashes.mapper().createObjectNode();
        identity.putObject("executable").put("kind", "JAR").put("sha256", executable.repeat(64));
        identity.putObject("compiled").put("input_fingerprint", source.repeat(64));
        return identity;
    }

    private static ObjectNode correctedPlan(int developmentWorkers, ObjectNode profile) {
        ObjectNode plan = basePlan();
        plan.put("schema", StrategyOperatingCharacteristicsCorrectedParallelV1.PLAN_SCHEMA)
                .put("corrected_evaluator_identity", StrategyFixedBaselineCorrectedV1.EVALUATOR_ID)
                .put("fixed_evaluator", StrategyFixedBaselineCorrectedV1.EVALUATOR_ID)
                .put("accounting_version", StrategyFixedBaselineCorrectedV1.ACCOUNTING_VERSION)
                .put("execution_profile_sha256", profile.path("content_sha256").asText())
                .put("development_wave", true)
                .put("development_worker_count", developmentWorkers)
                .put("development_seed_namespace", "CORRECTED_DEVELOPMENT_TEST");
        plan.put("executor_identity_sha256", "1".repeat(64))
                .put("executor_build_input_fingerprint", "2".repeat(64))
                .put("executor_source_sha256", "2".repeat(64));
        ArrayNode development = plan.putArray("development_seed_cells");
        ArrayNode prefix = plan.putArray("development_prefix_seed_cells");
        for (int c = 0; c < 4; c++) {
            ObjectNode source = (ObjectNode) plan.path("cells").get(c);
            ObjectNode full = development.addObject().put("scenario", source.path("scenario").asText())
                    .put("effect_size", source.path("effect_size").asDouble());
            ArrayNode seeds = full.putArray("development_seeds");
            for (int i = 0; i < developmentWorkers; i++) seeds.add(920_000_000L + c * 100_000L + i);
            ObjectNode shortWave = prefix.addObject().put("scenario", source.path("scenario").asText())
                    .put("effect_size", source.path("effect_size").asDouble());
            shortWave.putArray("prefix_seeds").add(921_000_000L + c * 100_000L);
        }
        rehash(plan);
        return plan;
    }

    private static ObjectNode qualifiedProfile() {
        ObjectNode profile = profile(1, resourceProbe(28, 32L * GIB, 256L * GIB));
        profile.put("qualified_for_confirmation", true)
                .put("claim_scope", "DEVELOPMENT_EXECUTION_AND_CONFIRMATION_GATE");
        String envelope = profileEnvelopeHash(profile);
        ObjectNode receipt = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evaluator-operating-characteristics-qualification/1")
                .put("version", 1).put("status", "QUALIFIED").put("mode", "FULL")
                .put("evidence_phase", "DEVELOPMENT").put("measured", true)
                .put("heldout_seed_disjoint", true).put("development_seed_disjoint", true)
                .put("execution_profile_envelope_sha256", envelope)
                .put("corrected_evaluator_identity", StrategyFixedBaselineCorrectedV1.EVALUATOR_ID)
                .put("accounting_version", StrategyFixedBaselineCorrectedV1.ACCOUNTING_VERSION)
                .put("serial_parallel_equivalent", true)
                .put("executor_identity_sha256", "1".repeat(64))
                .put("executor_source_sha256", "2".repeat(64))
                .put("statistical_plan_sha256", "3".repeat(64))
                .put("development_plan_sha256", "4".repeat(64))
                 .put("parallel_resource_measurement_sha256", "5".repeat(64))
                 .put("serial_resource_measurement_sha256", "6".repeat(64))
                 .put("parallel_result_byte_sha256", "7".repeat(64))
                 .put("parallel_ledger_byte_sha256", "8".repeat(64))
                 .put("parallel_ledger_content_sha256", "9".repeat(64))
                 .put("serial_result_byte_sha256", "a".repeat(64))
                 .put("serial_ledger_byte_sha256", "b".repeat(64))
                 .put("serial_ledger_content_sha256", "c".repeat(64))
                 .put("parallel_result_relative_path", "artifacts/parallel-result.json")
                 .put("parallel_ledger_relative_path", "artifacts/parallel-ledger.json")
                 .put("serial_result_relative_path", "artifacts/serial-result.json")
                 .put("serial_ledger_relative_path", "artifacts/serial-ledger.json")
                 .put("completed_slot_count", 4).put("serial_completed_slot_count", 4);
        ObjectNode geometry = geometry();
        receipt.set("full_geometry", geometry.deepCopy());
        receipt.set("development_plan_geometry", geometry.deepCopy());
        receipt.set("resource_envelope", resourceEnvelope(profile));
        receipt.set("completed_raw_worker_refs", refs("parallel"));
        receipt.set("serial_completed_raw_worker_refs", refs("serial"));
        rehash(receipt);
        profile.set("qualification_receipt", receipt);
        profile.put("profile_envelope_sha256", envelope);
        rehash(profile);
        return profile;
    }

    private static ObjectNode geometry() {
        return rehash(JsonHashes.mapper().createObjectNode()
                .put("cell_count", 4).put("replications", 75).put("episodes_per_replication", 450)
                .put("cluster_count", 288).put("paired_cluster_count", 162)
                .put("lifecycle_series_per_replication", 900).put("horizon_minutes", 14_400));
    }

    private static ObjectNode resourceEnvelope(ObjectNode profile) {
        return rehash(JsonHashes.mapper().createObjectNode()
                .put("max_wall_minutes", profile.path("max_wall_minutes").asLong())
                .put("max_aggregate_rss_bytes", profile.path("max_aggregate_rss_bytes").asLong())
                .put("coordinator_rss_reservation_bytes", profile.path("coordinator_rss_reservation_bytes").asLong())
                .put("worker_rss_bytes", profile.path("worker_rss_bytes").asLong())
                .put("worker_heap_bytes", profile.path("worker_heap_bytes").asLong())
                .put("worker_cpu", profile.path("worker_cpu").asLong())
                .put("max_workers", profile.path("max_workers").asLong())
                .put("max_cpu", profile.path("max_cpu").asLong())
                .put("max_disk_bytes", profile.path("max_disk_bytes").asLong())
                .put("available_cpus", profile.path("available_cpus").asLong())
                .put("available_memory_bytes", profile.path("available_memory_bytes").asLong())
                .put("measured_wall_millis", 1)
                .put("measured_max_aggregate_rss_bytes", 1)
                .put("measured_max_disk_bytes", 1)
                .put("parallel_result_byte_sha256", "7".repeat(64))
                .put("parallel_ledger_byte_sha256", "b".repeat(64))
                .put("parallel_ledger_content_sha256", "8".repeat(64))
                .put("serial_result_byte_sha256", "9".repeat(64))
                .put("serial_ledger_byte_sha256", "c".repeat(64))
                .put("serial_ledger_content_sha256", "a".repeat(64)));
    }

    private static ArrayNode refs(String prefix) {
        ArrayNode refs = JsonHashes.mapper().createArrayNode();
        String plan = "b".repeat(64);
        for (int i = 0; i < 4; i++) {
            refs.addObject().put("slot_id", prefix + "-FULL-cell-" + i).put("mode", "FULL")
                    .put("plan_sha256", plan).put("scenario", i == 0 ? "NO_EDGE" : "PLANTED_EDGE")
                    .put("effect_size", i < 2 ? 0D : i == 2 ? .02D : .04D)
                    .put("replication", 0).put("seed", 920_000_000L + i)
                    .put("relative_path", "artifacts/" + prefix + "-" + i + ".json")
                    .put("bytes", 1).put("content_sha256", "c".repeat(64))
                    .put("byte_sha256", "d".repeat(64));
        }
        return refs;
    }

    private static ObjectNode basePlan() {
        ObjectNode plan = JsonHashes.mapper().createObjectNode();
        plan.put("schema", StrategyOperatingCharacteristicsParallelV1.PLAN_SCHEMA)
                .put("version", 1).put("mode", "FULL")
                .put("frozen_before_outcomes", true).put("outcomes_opened", false)
                .put("promotion_eligible", false).put("activation_authorized", false)
                .put("episodes_per_replication", 450).put("cell_count", 4).put("replications", 75)
                .put("cluster_count", 288).put("paired_cluster_count", 162)
                .put("lifecycle_series_per_replication", 900).put("horizon_minutes", 14_400)
                .put("baseline_sha256", "a".repeat(64)).put("control_spec_sha256", "b".repeat(64))
                .put("experiment_sha256", "c".repeat(64))
                .put("executor_identity_sha256", "1".repeat(64))
                .put("executor_source_sha256", "2".repeat(64))
                .put("executor_build_input_fingerprint", "2".repeat(64));
        plan.putObject("resource_preflight").put("replications", 1);
        ArrayNode cells = plan.putArray("cells");
        addCell(cells, "NO_EDGE", 0D, 10_000L);
        addCell(cells, "PLANTED_EDGE", 0D, 20_000L);
        addCell(cells, "PLANTED_EDGE", .02D, 30_000L);
        addCell(cells, "PLANTED_EDGE", .04D, 40_000L);
        ObjectNode statistical = plan.deepCopy();
        statistical.remove("content_sha256");
        statistical.remove("statistical_plan");
        statistical.remove("statistical_plan_sha256");
        statistical.put("content_sha256", JsonHashes.ownHash(statistical));
        plan.set("statistical_plan", statistical);
        plan.put("statistical_plan_sha256", statistical.path("content_sha256").asText());
        ArrayNode dependencies = plan.putArray("supporting_dependency_receipts");
        dependencies.addObject().put("relative_path", EVENT).put("bytes", 1)
                .put("byte_sha256", "3".repeat(64)).put("content_sha256", "4".repeat(64));
        dependencies.addObject().put("relative_path", LIFECYCLE).put("bytes", 1)
                .put("byte_sha256", "5".repeat(64)).put("content_sha256", "6".repeat(64));
        rehash(plan);
        return plan;
    }

    private static void addCell(ArrayNode cells, String scenario, double effect, long seedStart) {
        ObjectNode cell = cells.addObject().put("scenario", scenario).put("effect_size", effect);
        ArrayNode seeds = cell.putArray("seeds");
        for (int i = 0; i < 75; i++) seeds.add(seedStart + i);
    }

    private ObjectNode runCorrectedScheduler(ObjectNode plan, ObjectNode profile, Path ledger,
            AtomicInteger calls) throws Exception {
        ObjectNode options = planOptions(plan, profile).put("mode", "FULL").put("ledger", ledger.toString());
        return StrategyOperatingCharacteristicsParallelV1.runCorrectedWithExecutorForTest(options,
                (slot, scratch) -> {
                    calls.incrementAndGet();
                    return workerRow(slot);
                }, resourceProbe(28, 32L * GIB, 256L * GIB));
    }

    private static ObjectNode workerRow(StrategyOperatingCharacteristicsParallelV1.Slot slot) {
        ObjectNode row = JsonHashes.mapper().createObjectNode()
                .put("cell", slot.scenario()).put("effect_size", slot.effectSize())
                .put("replication", slot.replication()).put("seed", slot.seed())
                .put("status", "COMPLETE").put("decision", slot.replication() % 2 == 0)
                .put("event_count", 450).put("paired_count", 450).put("independent_units", 288);
        row.putObject("metrics").put("paired_tested_cluster_count", 162)
                .put("paired_analysis_insufficient", false);
        return row;
    }

    private static ObjectNode successorPlan(boolean corrected) {
        ObjectNode p = JsonHashes.mapper().createObjectNode()
                .put("schema", corrected ? StrategyOperatingCharacteristicsCorrectedSuccessorV1.PLAN_SCHEMA
                        : StrategyOperatingCharacteristicsSuccessorV1.PLAN_SCHEMA)
                .put("version", 1).put("evidence_phase", "DEVELOPMENT")
                .put("decision", "DIAGNOSTIC_ONLY").put("development_exposure", true)
                .put("promotion_eligible", false).put("activation_authorized", false)
                .put("outcomes_opened", false).put("frozen_before_outcomes", true)
                .put("binding_fixed_evaluator", corrected ? StrategyOperatingCharacteristicsCorrectedSuccessorV1.EVALUATOR_ID
                        : "StrategyFixedBaselineV5+TradeLifecycleV5+StrategyResearchImprovementV1.disposition")
                .put("predecessor_diagnosis_sha256", "a".repeat(64))
                .put("baseline_sha256", "b".repeat(64)).put("control_spec_sha256", "c".repeat(64))
                .put("experiment_sha256", "d".repeat(64)).put("lineage_inventory_sha256", "e".repeat(64))
                .put("executor_source_sha256", "f".repeat(64)).put("executor_identity_sha256", "1".repeat(64))
                .put("cell_count", 4).put("replications", 75).put("episodes_per_replication", 450)
                .put("lifecycle_series_per_replication", 900).put("horizon_minutes", 14_400)
                .put("cluster_count", 288).put("paired_cluster_count", 162);
        if (corrected) {
            p.put("corrected_evaluator_identity", StrategyOperatingCharacteristicsCorrectedSuccessorV1.EVALUATOR_ID)
                    .put("accounting_version", StrategyOperatingCharacteristicsCorrectedSuccessorV1.ACCOUNTING_VERSION);
        }
        ArrayNode cells = p.putArray("cells");
        addSuccessorCell(cells, "NO_EDGE", 0D, 10_000L);
        addSuccessorCell(cells, "PLANTED_EDGE", 0D, 20_000L);
        addSuccessorCell(cells, "PLANTED_EDGE", .02D, 30_000L);
        addSuccessorCell(cells, "PLANTED_EDGE", .04D, 40_000L);
        p.putObject("resource_budget").put("max_wall_minutes", 720).put("max_rss_bytes", 8L * GIB)
                .put("max_disk_bytes", 20L * GIB).put("expected_generated_minute_bars", 3_888_000_000L);
        p.putObject("acceptance").put("preserve_v004_standards", true)
                .put("confidence_interval", "WILSON_95_PERCENT")
                .put("false_positive_upper_bound", .10D).put("power_lower_bound", .80D);
        p.putObject("stopping").put("fixed_no_optional_stopping", true);
        p.putObject("incomplete_run_policy").put("count_in_denominator", true).put("retain_all_attempts", true);
        p.putObject("resource_preflight").put("replications", 2).put("episodes_per_replication", 10);
        return rehash(p);
    }

    private static void addSuccessorCell(ArrayNode cells, String scenario, double effect, long start) {
        ObjectNode cell = cells.addObject().put("scenario", scenario).put("effect_size", effect);
        ArrayNode seeds = cell.putArray("seeds");
        for (int i = 0; i < 75; i++) seeds.add(start + i);
        cell.putArray("prefix_seeds").add(start + 1_000_000L).add(start + 1_000_001L);
    }

    private static ObjectNode rehash(ObjectNode node) {
        node.put("content_sha256", JsonHashes.ownHash(node));
        return node;
    }

    private static String profileEnvelopeHash(ObjectNode profile) {
        ObjectNode copy = profile.deepCopy();
        copy.remove("content_sha256");
        copy.remove("profile_envelope_sha256");
        copy.remove("qualification_receipt");
        return JsonHashes.ownHash(copy);
    }

    private static ObjectNode measurement(ObjectNode profile, long wall, long rss, long disk) {
        return rehash(JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evaluator-resource-measurement/1").put("version", 1)
                .put("plan_sha256", "a".repeat(64)).put("profile_sha256", profile.path("content_sha256").asText())
                .put("ledger_content_sha256", "b".repeat(64)).put("executor_identity_sha256", "c".repeat(64))
                .put("executor_source_sha256", "d".repeat(64)).put("host_identity_sha256", "e".repeat(64))
                .put("workers", profile.path("effective_workers").asInt())
                .put("measured_wall_millis", wall).put("measured_max_aggregate_rss_bytes", rss)
                .put("measured_max_coordinator_rss_bytes", 1).put("measured_max_disk_bytes", disk)
                .put("resource_probe_complete", true));
    }

    private static Object invokePrivate(String name, Class<?>[] parameterTypes, Object... arguments)
            throws Exception {
        Method method = StrategyOperatingCharacteristicsCorrectedParallelV1.class
                .getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw error;
        }
    }

    private static StrategyOperatingCharacteristicsParallelV1.ResourceProbe resourceProbe(
            long cpus, long memory, long free) {
        return ignored -> new StrategyOperatingCharacteristicsParallelV1.ResourceObservation(
                cpus, memory, free, 0L, 0L);
    }
}
