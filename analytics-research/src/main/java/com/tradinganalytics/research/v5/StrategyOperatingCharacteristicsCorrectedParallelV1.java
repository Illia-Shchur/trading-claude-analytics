package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.repository.RepositoryLayout;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Versioned command boundary for corrected parallel worker/coordinator runs. */
public final class StrategyOperatingCharacteristicsCorrectedParallelV1 {
    public static final String EVALUATOR_ID = StrategyFixedBaselineCorrectedV1.EVALUATOR_ID;
    public static final String ACCOUNTING_VERSION = StrategyFixedBaselineCorrectedV1.ACCOUNTING_VERSION;
    public static final String PLAN_SCHEMA =
            "strategy-evaluator-operating-characteristics-corrected-parallel-plan/1";
    public static final String PROFILE_SCHEMA =
            StrategyOperatingCharacteristicsParallelV1.PROFILE_SCHEMA;
    public static final String PREFLIGHT_SCHEMA =
            "strategy-evaluator-operating-characteristics-corrected-parallel-preflight/1";

    private StrategyOperatingCharacteristicsCorrectedParallelV1() { }

    /** Build a profile with an explicit corrected accounting binding. */
    public static ObjectNode executionProfile(ObjectNode options) {
        return correctedProfile(StrategyOperatingCharacteristicsParallelV1.executionProfile(options),
                currentExecutorIdentity());
    }

    /** Package seam for deterministic resource-probe tests; production uses the live probe. */
    static ObjectNode executionProfile(ObjectNode options,
            StrategyOperatingCharacteristicsParallelV1.ResourceProbe probe) {
        return correctedProfile(StrategyOperatingCharacteristicsParallelV1.executionProfile(options, probe),
                currentExecutorIdentity());
    }

    /** Trusted package seam for a self-contained qualification pipeline test. */
    static ObjectNode executionProfileForTest(ObjectNode options,
            StrategyOperatingCharacteristicsParallelV1.ResourceProbe probe, ObjectNode identity) {
        return correctedProfile(StrategyOperatingCharacteristicsParallelV1.executionProfile(options, probe), identity);
    }

    private static ObjectNode currentExecutorIdentity() {
        return BuildIdentityService.describe(StrategyOperatingCharacteristicsParallelV1.class);
    }

    private static ObjectNode correctedProfile(ObjectNode profile, ObjectNode identity) {
        profile.put("fixed_evaluator", EVALUATOR_ID)
                .put("corrected_evaluator_identity", EVALUATOR_ID)
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("executor_identity_sha256", identity.path("executable").path("sha256").asText(""))
                .put("executor_source_sha256", identity.path("compiled").path("input_fingerprint").asText(""))
                .put("executor_kind", identity.path("executable").path("kind").asText("UNKNOWN"))
                .put("host_identity_sha256",
                        StrategyOperatingCharacteristicsParallelV1.hostFingerprintForProfile(profile));
        profile.put("profile_envelope_sha256", profileEnvelopeHash(profile));
        profile.put("content_sha256", JsonHashes.ownHash(profile));
        return profile;
    }

    /** Resource-only preflight; it never opens outcomes or asserts qualification. */
    public static ObjectNode preflight(ObjectNode options) {
        ObjectNode input = options == null ? JsonHashes.mapper().createObjectNode() : options.deepCopy();
        ObjectNode plan = readObject(input, "plan");
        if (input.has("profile")) {
            ObjectNode suppliedProfile = readObject(input, "profile");
            int declaredWorkers = plan.path("development_worker_count").asInt(0);
            if (declaredWorkers > 0 && suppliedProfile.path("effective_workers").asInt(0) > 0
                    && declaredWorkers != suppliedProfile.path("effective_workers").asInt(-1)) {
                throw new IllegalArgumentException("corrected development worker wave differs from execution profile");
            }
        }
        validateCorrectedPlan(plan);
        input.put("corrected_accounting", true);
        if (!input.has("profile")) {
            int declaredWorkers = plan.path("development_worker_count").asInt(0);
            if (declaredWorkers > 0) input.put("requested_workers", declaredWorkers);
            input.set("profile", executionProfile(input));
        }
        else validateCorrectedProfile(readObject(input, "profile"));
        ObjectNode boundProfile = readObject(input, "profile");
        int declaredWorkers = plan.path("development_worker_count").asInt(0);
        if (declaredWorkers > 0 && boundProfile.path("effective_workers").asInt(0) > 0
                && declaredWorkers != boundProfile.path("effective_workers").asInt(-1)) {
            throw new IllegalArgumentException("corrected development worker wave differs from execution profile");
        }
        ObjectNode result = StrategyOperatingCharacteristicsParallelV1.preflight(input);
        result.put("schema", PREFLIGHT_SCHEMA)
                .put("fixed_evaluator", EVALUATOR_ID)
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("corrected", true)
                .put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    /** Runs a corrected coordinator with process isolated corrected workers. */
    public static ObjectNode run(ObjectNode options) {
        ObjectNode input = options == null ? JsonHashes.mapper().createObjectNode() : options.deepCopy();
        ObjectNode plan = readObject(input, "plan");
        validateCorrectedPlan(plan);
        input.put("corrected_accounting", true);
        if (input.has("profile")) validateCorrectedProfile(readObject(input, "profile"));
        return StrategyOperatingCharacteristicsParallelV1.runCorrected(input);
    }

    /**
     * Validates two completed corrected FULL development waves (one serial,
     * one parallel), then emits a new profile containing a qualification
     * receipt.  The receipt is never produced from a resource-only profile or
     * from compact caller-authored rows.
     */
    public static ObjectNode qualifyDevelopment(ObjectNode options) {
        return qualifyDevelopmentInternal(options,
                StrategyOperatingCharacteristicsParallelV1.liveResourceProbeForQualification(),
                currentExecutorIdentity(), true, false);
    }

    /**
     * Trusted package seam for deterministic end-to-end qualification tests.
     * Production CLI code cannot provide a probe or identity override.
     */
    static ObjectNode qualifyDevelopmentForTest(ObjectNode options,
            StrategyOperatingCharacteristicsParallelV1.ResourceProbe probe, ObjectNode identity) {
        return qualifyDevelopmentInternal(options, probe, identity, false, true);
    }

    private static ObjectNode qualifyDevelopmentInternal(ObjectNode options,
            StrategyOperatingCharacteristicsParallelV1.ResourceProbe probe, ObjectNode identity,
            boolean requirePackaged, boolean allowExternalEvidence) {
        ObjectNode plan = readObject(options, "plan");
        ObjectNode profile = readObject(options, "profile");
        validateCorrectedPlan(plan);
        validateCorrectedProfile(profile);
        if (!plan.path("development_wave").asBoolean(false)
                || !profile.path("target_qualified").asBoolean(false)
                || profile.path("effective_workers").asInt(0) <= 0) {
            throw new IllegalArgumentException("corrected development qualification requires target-qualified hardware and admitted workers");
        }
        ObjectNode liveOptions = options.deepCopy();
        liveOptions.put("requested_workers", profile.path("requested_workers").asInt(-1));
        ObjectNode live = executionProfileForIdentity(liveOptions, probe, identity);
        if (!live.path("target_qualified").asBoolean(false)
                || live.path("available_cpus").asLong(-1) != profile.path("available_cpus").asLong(-2)
                || live.path("available_memory_bytes").asLong(-1) != profile.path("available_memory_bytes").asLong(-2)
                || live.path("effective_workers").asInt(-1) != profile.path("effective_workers").asInt(-2)
                || !live.path("executor_identity_sha256").asText().equals(profile.path("executor_identity_sha256").asText())
                || !live.path("executor_source_sha256").asText().equals(profile.path("executor_source_sha256").asText())
                || !live.path("executor_kind").asText().equals(profile.path("executor_kind").asText())
                || !live.path("host_identity_sha256").asText().equals(profile.path("host_identity_sha256").asText())) {
            throw new IllegalArgumentException("qualification profile is not bound to the currently probed host");
        }
        requireCurrentProfileIdentity(profile, identity, requirePackaged);
        Path parallelResult = requiredPath(options, "parallel_result");
        Path parallelLedger = requiredPath(options, "parallel_ledger");
        Path serialResult = requiredPath(options, "serial_result");
        Path serialLedger = requiredPath(options, "serial_ledger");
        if (parallelResult.equals(serialResult) || parallelLedger.equals(serialLedger)) {
            throw new IllegalArgumentException("serial and parallel qualification runs must use distinct result and ledger files");
        }
        ArrayNode parallelRefs = developmentArtifacts(parallelResult, parallelLedger, plan, profile,
                allowExternalEvidence);
        ArrayNode serialRefs = developmentArtifacts(serialResult, serialLedger, plan, profile,
                allowExternalEvidence);
        ObjectNode parallelMeasurement = developmentMeasurement(parallelResult, parallelLedger, plan, profile,
                profile.path("effective_workers").asInt(0), allowExternalEvidence);
        ObjectNode serialMeasurement = developmentMeasurement(serialResult, serialLedger, plan, profile, 1,
                allowExternalEvidence);
        if (parallelMeasurement.path("run_id").asText().equals(serialMeasurement.path("run_id").asText())) {
            throw new IllegalArgumentException("serial and parallel qualification runs must have distinct coordinator identities");
        }
        requireEconomicEquivalence(parallelRefs, serialRefs);
        ObjectNode resource = resourceEnvelope(profile, parallelMeasurement, serialMeasurement,
                parallelResult, parallelLedger, serialResult, serialLedger);
        ObjectNode receipt = qualificationReceiptWithEvidence(plan, profile, resource, parallelRefs,
                serialRefs, parallelMeasurement, serialMeasurement,
                parallelResult, parallelLedger, serialResult, serialLedger, allowExternalEvidence);

        ObjectNode qualified = profile.deepCopy();
        qualified.put("qualified_for_confirmation", true)
                .put("claim_scope", "DEVELOPMENT_EXECUTION_AND_CONFIRMATION_GATE");
        String envelope = profileEnvelopeHash(qualified);
        receipt.put("execution_profile_envelope_sha256", envelope);
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        qualified.set("qualification_receipt", receipt.deepCopy());
        qualified.put("profile_envelope_sha256", envelope)
                .put("content_sha256", JsonHashes.ownHash(qualified));
        StrategyOperatingCharacteristicsParallelV1.validateQualificationProfileForTest(qualified);
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evaluator-operating-characteristics-corrected-qualification/1")
                .put("version", 1).put("status", "QUALIFIED").put("evidence_phase", "DEVELOPMENT")
                .put("mode", "FULL").put("measured", true).put("serial_parallel_equivalent", true)
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("profile_sha256", qualified.path("content_sha256").asText())
                .put("accounting_version", ACCOUNTING_VERSION).put("fixed_evaluator", EVALUATOR_ID)
                .put("promotion_eligible", false).put("activation_authorized", false);
        result.set("qualification_receipt", receipt);
        result.set("profile", qualified);
        result.put("content_sha256", JsonHashes.ownHash(result));
        String output = options.path("out").asText("");
        if (!output.isBlank()) writeJson(Path.of(output).toAbsolutePath().normalize(), result);
        return result;
    }

    /** Reopens a qualified profile and validates every receipt binding. */
    public static ObjectNode validateQualification(ObjectNode options) {
        return validateQualificationInternal(options,
                StrategyOperatingCharacteristicsParallelV1.liveResourceProbeForQualification(),
                currentExecutorIdentity(), true, false);
    }

    /** Trusted package seam for end-to-end qualification validation tests. */
    static ObjectNode validateQualificationForTest(ObjectNode options,
            StrategyOperatingCharacteristicsParallelV1.ResourceProbe probe, ObjectNode identity) {
        return validateQualificationInternal(options, probe, identity, false, true);
    }

    private static ObjectNode validateQualificationInternal(ObjectNode options,
            StrategyOperatingCharacteristicsParallelV1.ResourceProbe probe, ObjectNode identity,
            boolean requirePackaged, boolean allowExternalEvidence) {
        ObjectNode profile = readObject(options, "profile");
        validateCorrectedProfile(profile);
        if (!options.has("plan")) {
            throw new IllegalArgumentException("corrected qualification validation requires the development plan");
        }
        StrategyOperatingCharacteristicsParallelV1.validateQualificationProfileForTest(profile);
        ObjectNode plan = readObject(options, "plan");
        validateCorrectedPlan(plan);
        StrategyOperatingCharacteristicsParallelV1.validateQualificationAgainstPlanForTest(profile, plan);
        requireCurrentProfileIdentity(profile, identity, requirePackaged);
        ObjectNode receipt = (ObjectNode) profile.path("qualification_receipt");
        JsonNode originalProfileNode = receipt.path("development_profile");
        if (!originalProfileNode.isObject()) {
            throw new IllegalArgumentException("corrected qualification receipt lacks the original development profile");
        }
        ObjectNode developmentProfile = (ObjectNode) originalProfileNode;
        validateCorrectedProfile(developmentProfile);
        StrategyOperatingCharacteristicsParallelV1.validateQualificationProfileForTest(developmentProfile);
        if (!receipt.path("development_profile_sha256").asText()
                .equals(developmentProfile.path("content_sha256").asText())
                || !developmentProfile.path("content_sha256").asText()
                        .equals(plan.path("execution_profile_sha256").asText())) {
            throw new IllegalArgumentException("corrected qualification receipt original profile is not bound to the plan");
        }
        ObjectNode liveOptions = options.deepCopy()
                .put("requested_workers", profile.path("requested_workers").asInt(-1));
        ObjectNode live = executionProfileForIdentity(liveOptions, probe, identity);
        if (!sameHostProfile(profile, live)) {
            throw new IllegalArgumentException("qualification profile is not bound to the currently probed host");
        }
        Path parallelResult = qualificationPath(options, receipt, "parallel_result", "parallel_result_relative_path",
                allowExternalEvidence);
        Path parallelLedger = qualificationPath(options, receipt, "parallel_ledger", "parallel_ledger_relative_path",
                allowExternalEvidence);
        Path serialResult = qualificationPath(options, receipt, "serial_result", "serial_result_relative_path",
                allowExternalEvidence);
        Path serialLedger = qualificationPath(options, receipt, "serial_ledger", "serial_ledger_relative_path",
                allowExternalEvidence);
        if (parallelResult.equals(serialResult) || parallelLedger.equals(serialLedger)) {
            throw new IllegalArgumentException("serial and parallel qualification runs must use distinct result and ledger files");
        }
        ArrayNode parallelRefs = developmentArtifacts(parallelResult, parallelLedger, plan, developmentProfile,
                allowExternalEvidence);
        ArrayNode serialRefs = developmentArtifacts(serialResult, serialLedger, plan, developmentProfile,
                allowExternalEvidence);
        ObjectNode parallelMeasurement = developmentMeasurement(parallelResult, parallelLedger, plan,
                developmentProfile, profile.path("effective_workers").asInt(0), allowExternalEvidence);
        ObjectNode serialMeasurement = developmentMeasurement(serialResult, serialLedger, plan, developmentProfile,
                1, allowExternalEvidence);
        if (parallelMeasurement.path("run_id").asText().equals(serialMeasurement.path("run_id").asText())) {
            throw new IllegalArgumentException("serial and parallel qualification runs must have distinct coordinator identities");
        }
        requireEconomicEquivalence(parallelRefs, serialRefs);
        validateReceiptEvidence(receipt, profile, plan, parallelRefs, serialRefs,
                parallelMeasurement, serialMeasurement, parallelResult, parallelLedger, serialResult, serialLedger,
                allowExternalEvidence);
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evaluator-operating-characteristics-corrected-qualification-validation/1")
                .put("version", 1).put("status", "VALID")
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("accounting_version", ACCOUNTING_VERSION).put("fixed_evaluator", EVALUATOR_ID)
                .put("content_sha256", "");
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static boolean sameHostProfile(ObjectNode expected, ObjectNode actual) {
        return expected.path("available_cpus").asLong(-1) == actual.path("available_cpus").asLong(-2)
                && expected.path("available_memory_bytes").asLong(-1) == actual.path("available_memory_bytes").asLong(-2)
                && expected.path("effective_workers").asInt(-1) == actual.path("effective_workers").asInt(-2)
                && expected.path("executor_identity_sha256").asText().equals(actual.path("executor_identity_sha256").asText())
                && expected.path("executor_source_sha256").asText().equals(actual.path("executor_source_sha256").asText())
                && expected.path("executor_kind").asText().equals(actual.path("executor_kind").asText())
                && expected.path("host_identity_sha256").asText().equals(actual.path("host_identity_sha256").asText());
    }

    private static ArrayNode developmentArtifacts(Path result, Path ledger, ObjectNode plan,
            ObjectNode profile, boolean allowExternalEvidence) {
        return allowExternalEvidence
                ? StrategyOperatingCharacteristicsParallelV1.validateCorrectedDevelopmentArtifactsForTest(
                        result, ledger, plan, profile)
                : StrategyOperatingCharacteristicsParallelV1.validateCorrectedDevelopmentArtifacts(
                        result, ledger, plan, profile);
    }

    private static ObjectNode developmentMeasurement(Path result, Path ledger, ObjectNode plan,
            ObjectNode profile, int expectedWorkers, boolean allowExternalEvidence) {
        return allowExternalEvidence
                ? StrategyOperatingCharacteristicsParallelV1.validateCorrectedRunMeasurementForTest(
                        result, ledger, plan, profile, expectedWorkers)
                : StrategyOperatingCharacteristicsParallelV1.validateCorrectedRunMeasurement(
                        result, ledger, plan, profile, expectedWorkers);
    }

    private static ObjectNode executionProfileForIdentity(ObjectNode options,
            StrategyOperatingCharacteristicsParallelV1.ResourceProbe probe, ObjectNode identity) {
        return correctedProfile(StrategyOperatingCharacteristicsParallelV1.executionProfile(options, probe), identity);
    }

    private static void requireCurrentProfileIdentity(ObjectNode profile, ObjectNode identity,
            boolean requirePackaged) {
        String kind = identity.path("executable").path("kind").asText("");
        String executable = identity.path("executable").path("sha256").asText("");
        String source = identity.path("compiled").path("input_fingerprint").asText("");
        if ((requirePackaged && !"JAR".equals(kind))
                || !kind.equals(profile.path("executor_kind").asText())
                || !executable.equals(profile.path("executor_identity_sha256").asText())
                || !source.equals(profile.path("executor_source_sha256").asText())
                || !executable.matches("[a-f0-9]{64}")
                || !source.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(requirePackaged
                    ? "corrected qualification requires the currently packaged executable"
                    : "corrected qualification profile executor identity mismatch");
        }
    }

    private static void requirePackagedProfileIdentity(ObjectNode profile) {
        requireCurrentProfileIdentity(profile, currentExecutorIdentity(), true);
    }

    private static Path qualificationPath(ObjectNode options, ObjectNode receipt,
            String optionName, String receiptName, boolean allowExternalEvidence) {
        JsonNode supplied = options.get(optionName);
        String relative;
        if (supplied != null && supplied.isTextual() && !supplied.asText().isBlank()) {
            Path absolute = Path.of(supplied.asText()).toAbsolutePath().normalize();
            Path root = RepositoryLayout.locate().toAbsolutePath().normalize();
            if (!allowExternalEvidence && (!absolute.startsWith(root) || absolute.equals(root))) {
                throw new IllegalArgumentException("qualification evidence must be inside the repository");
            }
            if (allowExternalEvidence) return absolute;
            relative = root.relativize(absolute).toString().replace('\\', '/');
        } else {
            relative = receipt.path(receiptName).asText("");
        }
        if (allowExternalEvidence && Path.of(relative).isAbsolute()) {
            return Path.of(relative).toAbsolutePath().normalize();
        }
        try {
            String safe = PathConfinement.repositoryRelativePath(relative, "qualification evidence path");
            return PathConfinement.resolve(RepositoryLayout.locate(), safe,
                    "qualification evidence", PathConfinement.ExpectedType.FILE).absolute();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("qualification receipt does not bind " + optionName, error);
        }
    }

    private static void validateReceiptEvidence(ObjectNode receipt, ObjectNode profile, ObjectNode plan,
            ArrayNode parallelRefs, ArrayNode serialRefs, ObjectNode parallelMeasurement,
            ObjectNode serialMeasurement, Path parallelResult, Path parallelLedger,
            Path serialResult, Path serialLedger, boolean allowExternalEvidence) {
        ObjectNode expectedResource = resourceEnvelope(profile, parallelMeasurement, serialMeasurement,
                parallelResult, parallelLedger, serialResult, serialLedger);
        if (!receipt.path("resource_envelope").equals(expectedResource)
                || !receipt.path("parallel_resource_measurement_sha256").asText()
                .equals(parallelMeasurement.path("content_sha256").asText())
                || !receipt.path("serial_resource_measurement_sha256").asText()
                        .equals(serialMeasurement.path("content_sha256").asText())
                || receipt.path("completed_slot_count").asInt(-1) != parallelRefs.size()
                || receipt.path("serial_completed_slot_count").asInt(-1) != serialRefs.size()
                || !receipt.path("parallel_result_byte_sha256").asText()
                        .equals(JsonHashes.sha256(parallelResult))
                || !receipt.path("parallel_ledger_byte_sha256").asText()
                        .equals(JsonHashes.sha256(parallelLedger))
                || !receipt.path("serial_result_byte_sha256").asText()
                        .equals(JsonHashes.sha256(serialResult))
                || !receipt.path("serial_ledger_byte_sha256").asText()
                        .equals(JsonHashes.sha256(serialLedger))
                || !receipt.path("parallel_ledger_content_sha256").asText()
                        .equals(parallelMeasurement.path("ledger_content_sha256").asText())
                || !receipt.path("serial_ledger_content_sha256").asText()
                        .equals(serialMeasurement.path("ledger_content_sha256").asText())
                || !receipt.path("resource_envelope").path("parallel_resource_measurement_sha256").asText()
                        .equals(parallelMeasurement.path("content_sha256").asText())
                || !receipt.path("resource_envelope").path("serial_resource_measurement_sha256").asText()
                        .equals(serialMeasurement.path("content_sha256").asText())
                || !receipt.path("parallel_result_relative_path").asText()
                        .equals(evidenceReference(parallelResult, allowExternalEvidence))
                || !receipt.path("parallel_ledger_relative_path").asText()
                        .equals(evidenceReference(parallelLedger, allowExternalEvidence))
                || !receipt.path("serial_result_relative_path").asText()
                        .equals(evidenceReference(serialResult, allowExternalEvidence))
                || !receipt.path("serial_ledger_relative_path").asText()
                        .equals(evidenceReference(serialLedger, allowExternalEvidence))) {
            throw new IllegalArgumentException("corrected qualification receipt evidence bindings do not match reopened runs");
        }
        if (!receipt.path("executor_identity_sha256").asText()
                .equals(plan.path("executor_identity_sha256").asText())
                || !receipt.path("executor_source_sha256").asText()
                        .equals(plan.path("executor_build_input_fingerprint").asText())
                || !receipt.path("development_plan_sha256").asText()
                        .equals(plan.path("content_sha256").asText())
                || !profile.path("qualification_receipt").isObject()) {
            throw new IllegalArgumentException("corrected qualification receipt is not bound to the development plan");
        }
    }

    private static String repoRelative(Path path) {
        Path root = RepositoryLayout.locate().toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || absolute.equals(root)) {
            throw new IllegalArgumentException("qualification evidence must be inside the repository");
        }
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    private static String evidenceReference(Path path, boolean allowExternalEvidence) {
        return allowExternalEvidence ? path.toAbsolutePath().normalize().toString() : repoRelative(path);
    }

    /**
     * Derive a fresh full-geometry DEVELOPMENT declaration from an immutable
     * statistical plan.  Confirmation seeds stay in {@code cells.seeds}; the
     * runner consumes only the separate disjoint development wave.
     */
    public static ObjectNode createDevelopmentPlan(ObjectNode basePlan, ObjectNode profile) {
        ObjectNode identity = BuildIdentityService.describe(StrategyOperatingCharacteristicsParallelV1.class);
        String kind = identity.path("executable").path("kind").asText("");
        String executable = identity.path("executable").path("sha256").asText("");
        String source = identity.path("compiled").path("input_fingerprint").asText("");
        if (!"JAR".equals(kind) || !executable.matches("[a-f0-9]{64}")
                || !source.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("corrected development plan requires packaged executable identity");
        }
        return createDevelopmentPlan(basePlan, profile, identity, currentDependencyReceipts());
    }

    /** Deterministic package seam for plan-shape tests; production always probes the packaged identity. */
    static ObjectNode createDevelopmentPlanForTest(ObjectNode basePlan, ObjectNode profile,
            ObjectNode executorIdentity, ArrayNode dependencyReceipts) {
        return createDevelopmentPlan(basePlan, profile, executorIdentity, dependencyReceipts);
    }

    private static ObjectNode createDevelopmentPlan(ObjectNode basePlan, ObjectNode profile,
            ObjectNode executorIdentity, ArrayNode dependencyReceipts) {
        if (basePlan == null || !basePlan.isObject() || !basePlan.path("cells").isArray()) {
            throw new IllegalArgumentException("base plan must contain frozen statistical cells");
        }
        StrategyOperatingCharacteristicsParallelV1.validateBasePlanForCorrectedBuilder(basePlan);
        if (profile == null || !profile.isObject()
                || !profile.path("content_sha256").asText().matches("[a-f0-9]{64}")
                || !profile.path("content_sha256").asText().equals(JsonHashes.ownHash(profile))) {
            throw new IllegalArgumentException("corrected development profile is not self-bound");
        }
        validateCorrectedProfile(profile);
        int workers = profile.path("effective_workers").asInt(0);
        if (workers <= 0) throw new IllegalArgumentException("corrected development profile admits no workers");
        Set<Long> confirmation = new HashSet<>();
        for (JsonNode cell : basePlan.path("cells")) for (JsonNode seed : cell.path("seeds")) {
            if (!seed.isIntegralNumber() || !confirmation.add(seed.asLong())) {
                throw new IllegalArgumentException("base plan confirmation seeds are invalid or duplicated");
            }
        }
        ObjectNode plan = basePlan.deepCopy();
        plan.put("schema", PLAN_SCHEMA).put("version", 1)
                .put("corrected_evaluator_identity", EVALUATOR_ID)
                .put("fixed_evaluator", EVALUATOR_ID)
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("execution_profile_sha256", profile.path("content_sha256").asText())
                .put("development_wave", true)
                .put("development_worker_count", workers)
                .put("development_seed_namespace", "CORRECTED_DEVELOPMENT_920000000");
        String executable = executorIdentity.path("executable").path("sha256").asText("");
        String source = executorIdentity.path("compiled").path("input_fingerprint").asText("");
        if (!executable.matches("[a-f0-9]{64}") || !source.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("corrected development plan requires packaged executable identity");
        }
        plan.put("executor_identity_sha256", executable)
                .put("executor_build_input_fingerprint", source)
                .put("executor_source_sha256", source);
        if (dependencyReceipts == null) dependencyReceipts = currentDependencyReceipts();
        if (!dependencyReceipts.isArray()) {
            throw new IllegalArgumentException("corrected development plan has no dependency receipts");
        }
        plan.set("supporting_dependency_receipts", dependencyReceipts.deepCopy());
        ArrayNode devCells = plan.putArray("development_seed_cells");
        ArrayNode devPrefixCells = plan.putArray("development_prefix_seed_cells");
        JsonNode retainedPreflight = basePlan.path("resource_preflight");
        if (!retainedPreflight.isObject()) retainedPreflight = basePlan.path("statistical_plan").path("resource_preflight");
        int prefixReplications = retainedPreflight.path("replications").asInt(0);
        if (prefixReplications <= 0) {
            throw new IllegalArgumentException("base plan has no bounded PREFIX replication count");
        }
        plan.set("resource_preflight", retainedPreflight.deepCopy());
        for (int cellIndex = 0; cellIndex < basePlan.path("cells").size(); cellIndex++) {
            JsonNode sourceCell = basePlan.path("cells").get(cellIndex);
            ObjectNode cell = devCells.addObject()
                    .put("scenario", sourceCell.path("scenario").asText())
                    .put("effect_size", sourceCell.path("effect_size").asDouble());
            ArrayNode seeds = cell.putArray("development_seeds");
            long start = 920_000_000L + cellIndex * 100_000L;
            for (int offset = 0; offset < workers; offset++) {
                long seed = start + offset;
                if (!confirmation.add(seed)) throw new IllegalArgumentException("development seed overlaps confirmation seed");
                seeds.add(seed);
            }
            ObjectNode prefixCell = devPrefixCells.addObject()
                    .put("scenario", sourceCell.path("scenario").asText())
                    .put("effect_size", sourceCell.path("effect_size").asDouble());
            ArrayNode prefixSeeds = prefixCell.putArray("prefix_seeds");
            long prefixStart = 921_000_000L + cellIndex * 100_000L;
            for (int offset = 0; offset < prefixReplications; offset++) {
                long seed = prefixStart + offset;
                if (!confirmation.add(seed)) throw new IllegalArgumentException("development PREFIX seed overlaps confirmation seed");
                prefixSeeds.add(seed);
            }
        }
        plan.remove("content_sha256");
        plan.put("content_sha256", JsonHashes.ownHash(plan));
        return plan;
    }

    /**
     * Capture the dependency bytes from this checkout for the new declaration.
     * Retained plans carry historical byte hashes and can differ on Windows
     * because checkout normalization changes the serialized bytes.
     */
    private static ArrayNode currentDependencyReceipts() {
        try {
            Path root = RepositoryLayout.locate();
            ArrayNode result = JsonHashes.mapper().createArrayNode();
            for (String relative : new String[] {
                    "strategy-research/experiments/fk-deleveraging-baseline-v002/portfolio-policy-v001.json",
                    "strategy-research/experiments/fk-deleveraging-baseline-v002/lifecycle-timing-v001.json"}) {
                Path source = PathConfinement.resolve(root, relative, "corrected worker dependency",
                        PathConfinement.ExpectedType.FILE).absolute();
                byte[] bytes = PathConfinement.readSinglyLinkedFile(source, "corrected worker dependency");
                JsonNode document = JsonHashes.mapper().readTree(bytes);
                if (document == null || !document.isObject()) {
                    throw new IllegalArgumentException("corrected worker dependency is not an object: " + relative);
                }
                result.addObject().put("relative_path", relative)
                        .put("byte_sha256", JsonHashes.sha256(bytes))
                        .put("bytes", bytes.length)
                        .put("content_sha256", JsonHashes.ownHash(document))
                        .put("data_base64", Base64.getEncoder().encodeToString(bytes));
            }
            return result;
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot capture current corrected worker dependencies", error);
        }
    }

    private static void validateCorrectedPlan(ObjectNode plan) {
        if (PLAN_SCHEMA.equals(plan.path("schema").asText())
                && plan.path("content_sha256").asText().matches("[a-f0-9]{64}")
                && !plan.path("content_sha256").asText().equals(JsonHashes.ownHash(plan))) {
            throw new IllegalArgumentException("corrected parallel plan is not self-bound to corrected accounting");
        }
        StrategyOperatingCharacteristicsParallelV1.validateCorrectedPlanForPreflight(plan);
        if (!PLAN_SCHEMA.equals(plan.path("schema").asText())
                || plan.path("version").asInt(-1) != 1
                || !EVALUATOR_ID.equals(plan.path("corrected_evaluator_identity").asText(
                        plan.path("fixed_evaluator").asText("")))
                || !ACCOUNTING_VERSION.equals(plan.path("accounting_version").asText())
                || !plan.path("content_sha256").asText().equals(JsonHashes.ownHash(plan))) {
            throw new IllegalArgumentException("corrected parallel plan is not self-bound to corrected accounting");
        }
        if (!plan.path("development_wave").asBoolean(false)
                || !plan.path("development_seed_cells").isArray()
                || plan.path("development_seed_cells").size() != 4
                || !plan.path("development_prefix_seed_cells").isArray()
                || plan.path("development_prefix_seed_cells").size() != 4) {
            throw new IllegalArgumentException("corrected parallel plan lacks its separate development seed wave");
        }
        Set<Long> full = new HashSet<>();
        for (JsonNode cell : plan.path("cells")) for (JsonNode seed : cell.path("seeds")) full.add(seed.asLong());
        Set<Long> development = new HashSet<>();
        int expectedWorkers = plan.path("development_worker_count").asInt(0);
        if (expectedWorkers <= 0 && plan.path("development_seed_cells").isArray()
                && !plan.path("development_seed_cells").isEmpty()) {
            expectedWorkers = plan.path("development_seed_cells").get(0).path("development_seeds").size();
        }
        if (expectedWorkers <= 0) throw new IllegalArgumentException("corrected development plan has no worker wave binding");
        Set<String> expectedCells = Set.of("NO_EDGE:0", "PLANTED_EDGE:0", "PLANTED_EDGE:0.02", "PLANTED_EDGE:0.04");
        Set<String> actualCells = new HashSet<>();
        for (JsonNode cell : plan.path("development_seed_cells")) {
            String key = cell.path("scenario").asText("") + ":"
                    + java.math.BigDecimal.valueOf(cell.path("effect_size").asDouble(Double.NaN))
                            .stripTrailingZeros().toPlainString();
            if (!expectedCells.contains(key) || !actualCells.add(key)
                    || !cell.path("development_seeds").isArray()
                    || cell.path("development_seeds").size() != expectedWorkers) {
                throw new IllegalArgumentException("corrected development cell has no seeds");
            }
            for (JsonNode seed : cell.path("development_seeds")) {
                if (!seed.isIntegralNumber() || !development.add(seed.asLong()) || full.contains(seed.asLong())) {
                    throw new IllegalArgumentException("corrected development seeds overlap or duplicate confirmation seeds");
                }
            }
        }
        if (!actualCells.equals(expectedCells)) {
            throw new IllegalArgumentException("corrected development cells differ from the frozen four-cell inventory");
        }
        JsonNode preflight = plan.path("resource_preflight");
        if (!preflight.isObject()) preflight = plan.path("statistical_plan").path("resource_preflight");
        int prefixCount = preflight.path("replications").asInt(0);
        if (prefixCount <= 0) throw new IllegalArgumentException("corrected development PREFIX replication count is invalid");
        Set<String> actualPrefixCells = new HashSet<>();
        for (JsonNode cell : plan.path("development_prefix_seed_cells")) {
            String key = cell.path("scenario").asText("") + ":"
                    + java.math.BigDecimal.valueOf(cell.path("effect_size").asDouble(Double.NaN))
                            .stripTrailingZeros().toPlainString();
            if (!expectedCells.contains(key) || !actualPrefixCells.add(key)
                    || !cell.path("prefix_seeds").isArray() || cell.path("prefix_seeds").size() != prefixCount) {
                throw new IllegalArgumentException("corrected development PREFIX cell has an invalid seed count");
            }
            for (JsonNode seed : cell.path("prefix_seeds")) {
                if (!seed.isIntegralNumber() || !development.add(seed.asLong()) || full.contains(seed.asLong())) {
                    throw new IllegalArgumentException("corrected development PREFIX seeds overlap or duplicate confirmation seeds");
                }
            }
        }
        if (!actualPrefixCells.equals(expectedCells)) {
            throw new IllegalArgumentException("corrected development PREFIX cells differ from the frozen four-cell inventory");
        }
    }

    private static void validateCorrectedProfile(ObjectNode profile) {
        if (!EVALUATOR_ID.equals(profile.path("fixed_evaluator").asText())
                || !EVALUATOR_ID.equals(profile.path("corrected_evaluator_identity").asText())
                || !ACCOUNTING_VERSION.equals(profile.path("accounting_version").asText())
                || !profile.has("executor_identity_sha256")
                || !profile.has("executor_source_sha256")
                || !profile.has("executor_kind")
                || !profile.path("host_identity_sha256").asText("").matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("corrected parallel profile is not bound to corrected accounting");
        }
        String executable = profile.path("executor_identity_sha256").asText("");
        String source = profile.path("executor_source_sha256").asText("");
        String kind = profile.path("executor_kind").asText("");
        if (!Set.of("JAR", "CLASSES", "UNKNOWN").contains(kind)
                || (!executable.isBlank() && !executable.matches("[a-f0-9]{64}"))
                || (!source.isBlank() && !source.matches("[a-f0-9]{64}"))) {
            throw new IllegalArgumentException("corrected parallel profile has invalid executable identity");
        }
    }

    private static void requireEconomicEquivalence(ArrayNode parallel, ArrayNode serial) {
        Map<String, String> left = new HashMap<>();
        for (JsonNode ref : parallel) left.put(ref.path("slot_id").asText(), ref.path("portable_economic_sha256").asText());
        Map<String, String> right = new HashMap<>();
        for (JsonNode ref : serial) right.put(ref.path("slot_id").asText(), ref.path("portable_economic_sha256").asText());
        if (left.size() != parallel.size() || right.size() != serial.size() || !left.equals(right)) {
            throw new IllegalArgumentException("serial and parallel corrected development economics differ");
        }
    }

    private static ObjectNode resourceEnvelope(ObjectNode profile, ObjectNode parallelMeasurement,
            ObjectNode serialMeasurement, Path parallelResult, Path parallelLedger,
            Path serialResult, Path serialLedger) {
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("max_wall_minutes", profile.path("max_wall_minutes").asLong(-1))
                .put("max_aggregate_rss_bytes", profile.path("max_aggregate_rss_bytes").asLong(-1))
                .put("coordinator_rss_reservation_bytes", profile.path("coordinator_rss_reservation_bytes").asLong(-1))
                .put("worker_rss_bytes", profile.path("worker_rss_bytes").asLong(-1))
                .put("worker_heap_bytes", profile.path("worker_heap_bytes").asLong(-1))
                .put("worker_cpu", profile.path("worker_cpu").asLong(-1))
                .put("max_workers", profile.path("max_workers").asLong(-1))
                .put("max_cpu", profile.path("max_cpu").asLong(-1))
                .put("max_disk_bytes", profile.path("max_disk_bytes").asLong(-1))
                .put("available_cpus", profile.path("available_cpus").asLong(-1))
                .put("available_memory_bytes", profile.path("available_memory_bytes").asLong(-1));
        long wall = Math.max(measurementValue(parallelMeasurement, "measured_wall_millis"),
                measurementValue(serialMeasurement, "measured_wall_millis"));
        long rss = Math.max(measurementValue(parallelMeasurement, "measured_max_aggregate_rss_bytes"),
                measurementValue(serialMeasurement, "measured_max_aggregate_rss_bytes"));
        long disk = Math.max(measurementValue(parallelMeasurement, "measured_max_disk_bytes"),
                measurementValue(serialMeasurement, "measured_max_disk_bytes"));
        if (rss > result.path("max_aggregate_rss_bytes").asLong(0)
                || disk > result.path("max_disk_bytes").asLong(0)
                || wall > result.path("max_wall_minutes").asLong(0) * 60_000L) {
            throw new IllegalArgumentException("corrected development measurements exceed the frozen resource envelope");
        }
        result.put("measured_wall_millis", wall).put("measured_max_aggregate_rss_bytes", rss)
                .put("measured_max_disk_bytes", disk)
                .put("parallel_resource_measurement_sha256",
                        parallelMeasurement.path("content_sha256").asText())
                .put("serial_resource_measurement_sha256",
                        serialMeasurement.path("content_sha256").asText())
                .put("parallel_result_byte_sha256", JsonHashes.sha256(parallelResult))
                .put("parallel_ledger_byte_sha256", JsonHashes.sha256(parallelLedger))
                .put("parallel_ledger_content_sha256", parallelMeasurement.path("ledger_content_sha256").asText())
                .put("serial_result_byte_sha256", JsonHashes.sha256(serialResult))
                .put("serial_ledger_byte_sha256", JsonHashes.sha256(serialLedger))
                .put("serial_ledger_content_sha256", serialMeasurement.path("ledger_content_sha256").asText())
                .put("content_sha256", "");
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static long measurementValue(ObjectNode value, String field) {
        long number = value.path(field).asLong(-1);
        if (number <= 0) throw new IllegalArgumentException("coordinator-owned resource measurement lacks positive " + field);
        return number;
    }

    private static ObjectNode qualificationReceiptWithEvidence(ObjectNode plan, ObjectNode profile,
            ObjectNode resource, ArrayNode parallelRefs, ArrayNode serialRefs,
            ObjectNode parallelMeasurement, ObjectNode serialMeasurement,
            Path parallelResult, Path parallelLedger, Path serialResult, Path serialLedger,
            boolean allowExternalEvidence) {
        ObjectNode geometry = JsonHashes.mapper().createObjectNode()
                .put("cell_count", 4).put("replications", 75).put("episodes_per_replication", 450)
                .put("cluster_count", 288).put("paired_cluster_count", 162)
                .put("lifecycle_series_per_replication", 900).put("horizon_minutes", 14_400)
                .put("content_sha256", "");
        geometry.put("content_sha256", JsonHashes.ownHash(geometry));
        ObjectNode receipt = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evaluator-operating-characteristics-qualification/1")
                .put("version", 1).put("status", "QUALIFIED").put("mode", "FULL")
                .put("evidence_phase", "DEVELOPMENT").put("measured", true)
                .put("corrected_evaluator_identity", EVALUATOR_ID)
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("heldout_seed_disjoint", true).put("development_seed_disjoint", true)
                .put("executor_identity_sha256", plan.path("executor_identity_sha256").asText())
                .put("executor_source_sha256", plan.path("executor_build_input_fingerprint").asText())
                .put("statistical_plan_sha256", plan.path("statistical_plan_sha256").asText())
                .put("development_plan_sha256", plan.path("content_sha256").asText())
                .put("development_profile_sha256", profile.path("content_sha256").asText())
                .put("serial_parallel_equivalent", true)
                .put("completed_slot_count", parallelRefs.size())
                .put("serial_completed_slot_count", serialRefs.size())
                .put("parallel_resource_measurement_sha256", parallelMeasurement.path("content_sha256").asText())
                .put("serial_resource_measurement_sha256", serialMeasurement.path("content_sha256").asText())
                .put("parallel_result_relative_path", evidenceReference(parallelResult, allowExternalEvidence))
                .put("parallel_ledger_relative_path", evidenceReference(parallelLedger, allowExternalEvidence))
                .put("serial_result_relative_path", evidenceReference(serialResult, allowExternalEvidence))
                .put("serial_ledger_relative_path", evidenceReference(serialLedger, allowExternalEvidence))
                .put("parallel_result_byte_sha256", JsonHashes.sha256(parallelResult))
                .put("parallel_ledger_byte_sha256", JsonHashes.sha256(parallelLedger))
                .put("parallel_ledger_content_sha256", parallelMeasurement.path("ledger_content_sha256").asText())
                .put("serial_result_byte_sha256", JsonHashes.sha256(serialResult))
                .put("serial_ledger_byte_sha256", JsonHashes.sha256(serialLedger))
                .put("serial_ledger_content_sha256", serialMeasurement.path("ledger_content_sha256").asText())
                .put("content_sha256", "");
        receipt.set("full_geometry", geometry.deepCopy());
        receipt.set("development_plan_geometry", geometry.deepCopy());
        receipt.set("resource_envelope", resource.deepCopy());
        receipt.set("completed_raw_worker_refs", parallelRefs.deepCopy());
        receipt.set("serial_completed_raw_worker_refs", serialRefs.deepCopy());
        receipt.set("development_profile", profile.deepCopy());
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        return receipt;
    }

    /** Compatibility seam retained for focused receipt-shape tests; production qualification uses evidence paths. */
    private static ObjectNode qualificationReceipt(ObjectNode plan, ObjectNode profile,
            ObjectNode resource, ArrayNode parallelRefs, ArrayNode serialRefs,
            ObjectNode parallelMeasurement, ObjectNode serialMeasurement) {
        ObjectNode geometry = JsonHashes.mapper().createObjectNode()
                .put("cell_count", 4).put("replications", 75).put("episodes_per_replication", 450)
                .put("cluster_count", 288).put("paired_cluster_count", 162)
                .put("lifecycle_series_per_replication", 900).put("horizon_minutes", 14_400)
                .put("content_sha256", "");
        geometry.put("content_sha256", JsonHashes.ownHash(geometry));
        ObjectNode receipt = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evaluator-operating-characteristics-qualification/1")
                .put("version", 1).put("status", "QUALIFIED").put("mode", "FULL")
                .put("evidence_phase", "DEVELOPMENT").put("measured", true)
                .put("corrected_evaluator_identity", EVALUATOR_ID)
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("heldout_seed_disjoint", true).put("development_seed_disjoint", true)
                .put("executor_identity_sha256", plan.path("executor_identity_sha256").asText())
                .put("executor_source_sha256", plan.path("executor_build_input_fingerprint").asText())
                .put("statistical_plan_sha256", plan.path("statistical_plan_sha256").asText())
                .put("development_plan_sha256", plan.path("content_sha256").asText())
                .put("serial_parallel_equivalent", true)
                .put("completed_slot_count", parallelRefs.size())
                .put("serial_completed_slot_count", serialRefs.size())
                .put("parallel_resource_measurement_sha256", parallelMeasurement.path("content_sha256").asText())
                .put("serial_resource_measurement_sha256", serialMeasurement.path("content_sha256").asText())
                .put("content_sha256", "");
        receipt.set("full_geometry", geometry.deepCopy());
        receipt.set("development_plan_geometry", geometry.deepCopy());
        receipt.set("resource_envelope", resource.deepCopy());
        receipt.set("completed_raw_worker_refs", parallelRefs.deepCopy());
        receipt.set("serial_completed_raw_worker_refs", serialRefs.deepCopy());
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        return receipt;
    }

    private static ObjectNode readObject(ObjectNode options, String field) {
        JsonNode value = options.get(field);
        if (value != null && value.isObject()) return (ObjectNode) value;
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("--" + field + " must point to a JSON object file");
        }
        try {
            JsonNode parsed = JsonHashes.mapper().readTree(Files.readString(Path.of(value.asText())));
            if (!parsed.isObject()) throw new IllegalArgumentException("--" + field + " JSON root must be an object");
            return (ObjectNode) parsed;
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot read --" + field + ": " + error.getMessage(), error);
        }
    }

    private static Path requiredPath(ObjectNode options, String field) {
        JsonNode value = options.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("--" + field + " requires a path");
        }
        return Path.of(value.asText()).toAbsolutePath().normalize();
    }

    private static void writeJson(Path target, ObjectNode value) {
        try {
            Files.writeString(target, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value));
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot write corrected qualification output", error);
        }
    }

    private static String profileEnvelopeHash(ObjectNode profile) {
        ObjectNode copy = profile.deepCopy();
        copy.remove("content_sha256");
        copy.remove("profile_envelope_sha256");
        copy.remove("qualification_receipt");
        return JsonHashes.ownHash(copy);
    }
}
