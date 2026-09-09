package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.LifecycleTrustService;
import com.tradinganalytics.infrastructure.security.PhysicalEvaluatorTrustRegistry;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact JSON-contract port of {@code strategy-evaluator-v5.mjs}. */
public final class StrategyEvaluatorV5 {
    /** SHA-256 of the frozen Node evaluator source at the migration baseline. */
    public static final String STRATEGY_EVALUATOR_V5_CODE_SHA256 =
            "1c9dcb45fe7966247c67faa013bf4cc08722ce17d90bc5a95d03f50f5acc9cd7";
    /** SHA-256 of the frozen Node worker source at the migration baseline. */
    public static final String STRATEGY_EVALUATOR_V5_WORKER_CODE_SHA256 =
            "e64a6bb66cb5db4dd6ea801eb56ad5288e705ece864f3bafa955e1703c71eec0";
    public static final String STRATEGY_STATISTICAL_V5_CODE_SHA256 =
            "8d789b2febe8db90284b4719d9f389c2b4b00e862ed340ad050ced4c2c3fdfa8";
    public static final String STRATEGY_PHYSICAL_NULL_V5_CODE_SHA256;

    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Pattern HASH_RE = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern TIMEFRAME_RE = Pattern.compile("^(\\d+)(m|h|d)$", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter JS_ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final long ONE_MINUTE = 60_000L;
    private static final long FOUR_HOURS = 4L * 60L * ONE_MINUTE;
    private static final Set<String> PHYSICAL_NULL_IDENTITY_FIELDS = Set.of(
            "asset", "venue", "instrument", "symbol", "signal_id", "episode_id", "event_time",
            "decision_time", "availability_time", "label_availability_time", "execution_availability_time");
    private static final List<String> SIGNAL_IDENTITY_FIELDS = List.of(
            "asset", "venue", "instrument", "symbol", "signal_id", "episode_id", "event_time",
            "decision_time", "signal_eligible");
    private static final PhysicalEvaluatorTrustRegistry PHYSICAL_TRUST = new PhysicalEvaluatorTrustRegistry();
    private static final LifecycleTrustService LIFECYCLE_TRUST = new LifecycleTrustService();
    private static final TradeLifecycleV5 LIFECYCLE_ENGINE = new TradeLifecycleV5(LIFECYCLE_TRUST);

    static {
        ObjectNode value = object();
        value.put("schema", "strategy-v5-physical-null-code/1");
        value.put("evaluator_code_sha256", STRATEGY_EVALUATOR_V5_CODE_SHA256);
        value.put("worker_code_sha256", STRATEGY_EVALUATOR_V5_WORKER_CODE_SHA256);
        value.put("statistical_code_sha256", STRATEGY_STATISTICAL_V5_CODE_SHA256);
        STRATEGY_PHYSICAL_NULL_V5_CODE_SHA256 = hash(value);
    }

    private StrategyEvaluatorV5() {}

    /** Callable evaluator plus the worker/cache capabilities carried by the Node function object. */
    public interface Evaluator extends AutoCloseable {
        ObjectNode evaluate(ObjectNode args);

        default List<ObjectNode> evaluateBatch(List<ObjectNode> args) {
            List<ObjectNode> output = new ArrayList<>(args.size());
            for (ObjectNode value : args) output.add(evaluate(value));
            return List.copyOf(output);
        }

        default ObjectNode diagnostics() { return object(); }
        default List<String> publicPredictorIds() { return List.of(); }
        default ObjectNode workerProvenance() { return null; }
        default boolean physicalNullSelectionVerified() { return false; }
        @Override default void close() {}
    }

    /** Java counterpart of the object returned by {@code loadAuthoritativeEvaluatorV5}. */
    public record LoadedEvaluator(Evaluator evaluator, ObjectNode provenance) implements AutoCloseable {
        public LoadedEvaluator {
            Objects.requireNonNull(evaluator, "evaluator");
            provenance = provenance == null ? object() : provenance.deepCopy();
        }

        @Override public ObjectNode provenance() { return provenance.deepCopy(); }
        public ObjectNode diagnostics() { return evaluator.diagnostics(); }
        /**
         * Mint the opaque lifecycle capability owned by a physically verified loader.
         * The capability is deliberately absent from {@link Evaluator}, which remains
         * user-implementable for fixture and differential tests.
         */
        public LifecycleTrustService.Token createLifecycleTrustToken(ObjectNode execution) {
            return verifiedWorker().createLifecycleTrustToken(execution, null);
        }

        /** Mint a scenario token using only hash-valid authoritative fee/model overrides. */
        public LifecycleTrustService.Token createLifecycleStressTrustToken(
                ObjectNode execution, ObjectNode metadataOverrides) {
            return verifiedWorker().createLifecycleTrustToken(execution, metadataOverrides);
        }

        /**
         * Recompute one normalized physical outcome inside the loader owner so
         * the opaque token is consumed by the same trust registry that minted it.
         */
        public ObjectNode deriveBoundExecutionOutcome(ObjectNode options) {
            return verifiedWorker().deriveBoundExecutionOutcome(options, null);
        }

        /** Recompute a stress outcome with scenario metadata under the same loader custody. */
        public ObjectNode deriveBoundStressExecutionOutcome(
                ObjectNode options, ObjectNode metadataOverrides) {
            return verifiedWorker().deriveBoundExecutionOutcome(options, metadataOverrides);
        }

        private WorkerBackedEvaluator verifiedWorker() {
            if (!(evaluator instanceof WorkerBackedEvaluator worker)
                    || !PHYSICAL_TRUST.isVerifiedPhysicalEvaluator(evaluator)) {
                throw failure("lifecycle trust capability requires the physically verified evaluator loader");
            }
            if (worker.closed) throw failure("physically verified evaluator is closed");
            return worker;
        }
        @Override public void close() { evaluator.close(); }
    }

    public static String hash(JsonNode value) { return JsonHashes.canonicalSha256(value); }
    public static String hash(String value) { return JsonHashes.sha256(value); }
    public static String hash(byte[] value) { return JsonHashes.sha256(value); }

    /** Rebase an already materialized physical-null execution onto its target episode. */
    public static ObjectNode rebasePhysicalNullExecutionV5(ObjectNode options) {
        ObjectNode sourceOptions = options == null ? object() : options;
        return rebasePhysicalNullExecutionV5(objectOrEmpty(field(sourceOptions, "target")),
                objectOrEmpty(field(sourceOptions, "source")));
    }

    public static ObjectNode rebasePhysicalNullExecutionV5(ObjectNode target, ObjectNode source) {
        ObjectNode actualTarget = target == null ? object() : target;
        ObjectNode actualSource = source == null ? object() : source;
        ObjectNode result = actualTarget.deepCopy();
        Iterator<Map.Entry<String, JsonNode>> fields = actualSource.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (PHYSICAL_NULL_IDENTITY_FIELDS.contains(key) || "execution_reference".equals(key)) continue;
            JsonNode rebased = physicalNullTemporalKey(key)
                    ? physicalNullRebaseTime(entry.getValue(), field(actualSource, "decision_time"),
                            field(actualTarget, "decision_time"))
                    : physicalNullRebaseNested(entry.getValue(), field(actualSource, "decision_time"),
                            field(actualTarget, "decision_time"), key);
            result.set(key, rebased);
        }
        result.remove("execution_reference");
        return result;
    }

    public static ObjectNode makeEvaluatorSpecV5(ObjectNode options) {
        ObjectNode args = options == null ? object() : options;
        String strategyFamily = truthyText(field(args, "strategyFamily"));
        String precommitSha = requireHash(field(args, "precommitSha256"), "precommit_sha256");
        ObjectNode geneSpace = objectOrEmpty(field(args, "geneSpace"));
        ObjectNode predictorRegistry = objectOrEmpty(field(args, "predictorRegistry"));
        requireHash(field(geneSpace, "content_sha256"), "gene_space_sha256");
        requireHash(field(predictorRegistry, "content_sha256"), "predictor_registry_sha256");
        JsonNode predicate = field(args, "predicate");
        JsonNode candidateTemplate = field(args, "candidateTemplate");
        ObjectNode executionContract = objectOrEmpty(field(args, "executionContract"));
        if (strategyFamily == null || !truthy(predicate) || !truthy(candidateTemplate)) {
            throw failure("evaluator spec requires family, predicate, and candidate template");
        }
        validateCandidatePredicates(predictorRegistry, predicatePredictors(predicate));
        Set<String> geneNames = new HashSet<>();
        for (JsonNode gene : array(field(geneSpace, "genes"))) geneNames.add(jsString(field(gene, "name")));
        ObjectNode references = object();
        references.set("predicate", cloneNode(predicate));
        references.set("candidateTemplate", cloneNode(candidateTemplate));
        for (String name : geneReferences(references)) {
            if (!geneNames.contains(name)) throw failure("evaluator spec references undeclared gene " + name);
        }

        JsonNode templateRiskNode = field(candidateTemplate, "risk_amount_usd");
        Double templateRisk = defined(templateRiskNode) ? numberJs(templateRiskNode) : null;
        if (templateRisk != null && (!Double.isFinite(templateRisk) || !(templateRisk > 0))) {
            throw failure("candidate template fixed risk budget is invalid");
        }
        ObjectNode suppliedRisk = null;
        if (truthy(field(executionContract, "risk_convention"))) {
            suppliedRisk = objectOrEmpty(field(executionContract, "risk_convention")).deepCopy();
        } else if (templateRisk != null) {
            suppliedRisk = object();
            suppliedRisk.put("mode", "FIXED_RISK_BUDGET_USD");
            suppliedRisk.put("budget_usd", templateRisk);
        }
        if (suppliedRisk != null) {
            double budget = numberJs(field(suppliedRisk, "budget_usd"));
            if (!"FIXED_RISK_BUDGET_USD".equals(text(field(suppliedRisk, "mode")))
                    || !Double.isFinite(budget) || !(budget > 0)) {
                throw failure("risk_convention must be a positive FIXED_RISK_BUDGET_USD contract");
            }
            if (templateRisk != null && budget != templateRisk) {
                throw failure("candidate template risk budget disagrees with the frozen execution risk convention");
            }
            suppliedRisk.put("budget_usd", budget);
            suppliedRisk.put("precommit_sha256", precommitSha);
        }

        ObjectNode suppliedSizing = truthy(field(executionContract, "sizing_contract"))
                ? objectOrEmpty(field(executionContract, "sizing_contract")).deepCopy() : null;
        if (suppliedSizing != null) {
            String mode = text(field(suppliedSizing, "mode"));
            if (!Set.of("FIXED_NOTIONAL_USD", "TARGET_STOP_RISK").contains(mode)) {
                throw failure("sizing_contract mode is unsupported");
            }
            if ("FIXED_NOTIONAL_USD".equals(mode)) {
                double notional = numberJs(field(suppliedSizing, "notional_usd"));
                if (!Double.isFinite(notional) || !(notional > 0)) {
                    throw failure("FIXED_NOTIONAL_USD sizing_contract requires a positive notional_usd");
                }
                suppliedSizing.put("notional_usd", notional);
            }
            if ("TARGET_STOP_RISK".equals(mode) && defined(field(suppliedSizing, "notional_usd"))) {
                throw failure("TARGET_STOP_RISK sizing_contract cannot contain a fixed notional");
            }
            suppliedSizing.put("precommit_sha256", precommitSha);
        }

        ObjectNode normalizedContracts = object();
        normalizedContracts.set("risk_convention", suppliedRisk == null ? NullNode.instance : suppliedRisk);
        normalizedContracts.set("sizing_contract", suppliedSizing == null ? NullNode.instance : suppliedSizing);
        validateNormalizedLifecycleExecutionContracts(candidateTemplate, normalizedContracts, "candidate template");
        validateLifecycleSizingBoundary(candidateTemplate, normalizedContracts, "candidate template");

        ObjectNode suppliedDerivative = truthy(field(executionContract, "derivative_policy"))
                ? objectOrEmpty(field(executionContract, "derivative_policy")).deepCopy() : null;
        if (suppliedDerivative != null) {
            double leverage = numberJs(field(suppliedDerivative, "leverage"));
            if (!"ISOLATED".equals(text(field(suppliedDerivative, "margin_mode")))
                    || !Double.isFinite(leverage) || !(leverage > 0)) {
                throw failure("derivative_policy requires positive ISOLATED leverage");
            }
            suppliedDerivative.put("leverage", leverage);
            suppliedDerivative.put("precommit_sha256", precommitSha);
        }

        ObjectNode value = object();
        value.put("schema", "strategy-v5-evaluator-spec/1");
        value.put("version", 1);
        value.put("status", "FROZEN");
        value.put("strategy_family", strategyFamily);
        value.put("precommit_sha256", precommitSha);
        value.put("gene_space_sha256", text(field(geneSpace, "content_sha256")));
        value.put("predictor_registry_sha256", text(field(predictorRegistry, "content_sha256")));
        value.set("predicate", cloneNode(predicate));
        value.set("candidate_template", cloneNode(candidateTemplate));
        ObjectNode contract = object();
        contract.put("entry_policy", "NEXT_BAR_OPEN");
        contract.put("completed_bar_only", true);
        contract.put("child_interval_ms", 60_000);
        contract.put("collision_policy", "ADVERSE_STOP_FIRST");
        contract.put("outage_policy", "FAIL");
        contract.put("gap_policy", truthy(field(executionContract, "gap_policy"))
                ? jsString(field(executionContract, "gap_policy")) : "FAIL");
        contract.put("capacity_input_contract", "NOTIONAL_LE_AVAILABLE_LIQUIDITY_X_PARTICIPATION_CAP");
        contract.put("decision_timestamp_convention", truthy(field(executionContract, "decision_timestamp_convention"))
                ? jsString(field(executionContract, "decision_timestamp_convention")) : "COMPLETED_4H_BOUNDARY");
        contract.put("decision_timeframe", truthy(field(executionContract, "decision_timeframe"))
                ? jsString(field(executionContract, "decision_timeframe")) : "4h");
        contract.set("risk_convention", suppliedRisk == null ? NullNode.instance : suppliedRisk);
        contract.set("sizing_contract", suppliedSizing == null ? NullNode.instance : suppliedSizing);
        contract.set("derivative_policy", suppliedDerivative == null ? NullNode.instance : suppliedDerivative);
        value.set("execution_contract", contract);
        value.put("code_sha256", STRATEGY_EVALUATOR_V5_CODE_SHA256);
        value.put("worker_code_sha256", STRATEGY_EVALUATOR_V5_WORKER_CODE_SHA256);
        value.put("content_sha256", ownHash(value));
        validateEvaluatorSpecV5(value, geneSpace, predictorRegistry);
        return value;
    }

    public static boolean validateEvaluatorSpecV5(JsonNode value) {
        return validateEvaluatorSpecV5(value, null, null);
    }

    public static boolean validateEvaluatorSpecV5(JsonNode value, ObjectNode bindings) {
        ObjectNode options = bindings == null ? object() : bindings;
        JsonNode geneSpace = truthy(field(options, "geneSpace")) ? field(options, "geneSpace") : null;
        JsonNode predictorRegistry = truthy(field(options, "predictorRegistry"))
                ? field(options, "predictorRegistry") : null;
        return validateEvaluatorSpecV5(value, geneSpace, predictorRegistry);
    }

    public static boolean validateEvaluatorSpecV5(
            JsonNode value, JsonNode geneSpace, JsonNode predictorRegistry) {
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(value);
        if (!text(field(value, "content_sha256")).equals(ownHash(value))
                || !STRATEGY_EVALUATOR_V5_CODE_SHA256.equals(text(field(value, "code_sha256")))
                || !STRATEGY_EVALUATOR_V5_WORKER_CODE_SHA256.equals(text(field(value, "worker_code_sha256")))) {
            throw failure("evaluator spec hash/code binding is invalid");
        }
        if (geneSpace != null && !text(field(value, "gene_space_sha256"))
                .equals(text(field(geneSpace, "content_sha256")))) {
            throw failure("evaluator spec gene-space binding is invalid");
        }
        if (predictorRegistry != null) {
            if (!text(field(value, "predictor_registry_sha256"))
                    .equals(text(field(predictorRegistry, "content_sha256")))) {
                throw failure("evaluator spec predictor binding is invalid");
            }
            validateCandidatePredicates(predictorRegistry, predicatePredictors(field(value, "predicate")));
        }
        validateNormalizedLifecycleExecutionContracts(
                field(value, "candidate_template"), field(value, "execution_contract"), "evaluator spec");
        validateLifecycleSizingBoundary(
                field(value, "candidate_template"), field(value, "execution_contract"), "evaluator spec");
        return true;
    }

    public static boolean evaluateSignalPredicateV5(
            JsonNode predicate, JsonNode feature, JsonNode chromosome) {
        if (!missingPredicatePredictors(predicate, feature).isEmpty()) return false;
        return evaluateSignalPredicateNodeV5(predicate, feature, chromosome);
    }

    public static Evaluator createFixtureEvaluatorV5(ObjectNode args) {
        ObjectNode binding = args == null ? object() : args;
        if (!"FIXTURE".equals(text(field(binding, "mode")))) {
            throw failure("in-memory evaluator rows are fixture-only; use loadAuthoritativeEvaluatorV5 for research evidence");
        }
        return createBoundEvaluator(binding);
    }

    /** Worker-only constructor; callers receive no authoritative trust marker from this method. */
    public static Evaluator createVerifiedWorkerEvaluatorV5(ObjectNode args) {
        return createBoundEvaluator(args == null ? object() : args);
    }

    /**
     * Production constructor. It verifies the physical role bytes, reads Parquet in bounded batches,
     * then installs the deterministic worker/cache facade and identity-only trust marker.
     */
    public static LoadedEvaluator loadAuthoritativeEvaluatorV5(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        ObjectNode evaluatorSpec = objectOrEmpty(field(options, "evaluatorSpec"));
        ObjectNode geneSpace = objectOrEmpty(field(options, "geneSpace"));
        ObjectNode predictorRegistry = objectOrEmpty(field(options, "predictorRegistry"));
        validateEvaluatorSpecV5(evaluatorSpec, geneSpace, predictorRegistry);
        ObjectNode manifest = objectOrEmpty(field(options, "manifest"));
        Path root = requiredPath(field(options, "root"), "authoritative evaluator root");
        verifyAuthoritativeManifest(manifest, root, evaluatorSpec, predictorRegistry);

        int batchRows = strictIntOption(options, "batchRows", 4_096);
        int maxRows = strictIntOption(options, "maxRowsPerRole", 2_000_000);
        long maxBytes = strictLongOption(options, "maxMaterializedBytesPerRole", 1_073_741_824L);
        if (batchRows < 1 || batchRows > 65_536 || maxRows < 1 || maxBytes < 1) {
            throw failure("bounded evaluator read configuration is invalid");
        }
        List<String> episodeIds = optionalStringInventory(field(options, "episodeIds"));
        if (episodeIds != null && episodeIds.size() > 100_000) {
            throw failure("authoritative evaluator episode inventory exceeds the bounded 100000-ID contract");
        }

        ArrayNode features = readParquetRole(root, manifest, "feature", batchRows, maxRows, maxBytes, episodeIds);
        ArrayNode labels = readParquetRole(root, manifest, "label", batchRows, maxRows, maxBytes, episodeIds);
        ArrayNode executions = readParquetRole(root, manifest, "execution", batchRows, maxRows, maxBytes, episodeIds);
        ObjectNode executionLazy = makeExecutionLazyBinding(options, features, executions, maxRows, maxBytes);

        ObjectNode workerBinding = object();
        workerBinding.set("evaluatorSpec", evaluatorSpec.deepCopy());
        workerBinding.set("geneSpace", geneSpace.deepCopy());
        workerBinding.set("predictorRegistry", predictorRegistry.deepCopy());
        workerBinding.set("features", features);
        workerBinding.set("labels", labels);
        workerBinding.set("execution", executions);
        if (executionLazy != null) workerBinding.set("executionLazy", executionLazy);
        workerBinding.set("metadata", cloneNode(field(options, "metadata")));
        JsonNode explicitMetadataRoot = firstDefined(field(options, "metadataRoot"), field(options, "metadata_root"));
        if (definedNonNull(explicitMetadataRoot)) {
            workerBinding.set("metadataRoot", cloneNode(explicitMetadataRoot));
        }
        workerBinding.set("envelopeByEpisode", cloneOrEmptyObject(field(options, "envelopeByEpisode")));
        workerBinding.put("sourceArtifactSha256", text(field(manifest, "content_sha256")));
        workerBinding.put("sourceDatasetRootSha256", text(field(manifest, "dataset_root_sha256")));
        workerBinding.put("executionArtifactSha256", artifactSha(field(field(manifest, "artifacts"), "execution")));
        workerBinding.put("physicalRoot", root.toString());
        workerBinding.set("roleArtifacts", objectOrEmpty(field(manifest, "artifacts")).deepCopy());

        int workerCount = coercedIntOption(options, "workerCount", 2);
        int maxResultBytes = strictIntOption(options, "maxResultBytes", 64 * 1_024 * 1_024);
        long timeoutMs = longOption(options, "timeoutMs", 120_000L);
        long maxAggregateBytes = strictLongOption(options, "maxAggregateWorkerBytes", 512L * 1_024 * 1_024);
        if (!truthy(field(options, "cacheRoot"))) {
            throw failure("authoritative evaluator requires an explicit ignored content-addressed cache root");
        }
        Path cacheRoot = requiredPath(field(options, "cacheRoot"), "authoritative evaluator cache root");
        WorkerBackedEvaluator evaluator = new WorkerBackedEvaluator(workerCount, workerBinding, cacheRoot,
                maxResultBytes, timeoutMs, maxAggregateBytes);

        ObjectNode artifacts = objectOrEmpty(field(manifest, "artifacts"));
        ObjectNode workerProvenance = object();
        workerProvenance.put("schema", "strategy-v5-statistical-worker/1");
        workerProvenance.put("verified", true);
        workerProvenance.put("deterministic", true);
        workerProvenance.put("artifact_paths_bound", true);
        workerProvenance.put("physical_role_binding", true);
        workerProvenance.set("execution_hydration_sha256", executionLazy == null ? NullNode.instance
                : cloneNode(field(field(executionLazy, "hydration"), "content_sha256")));
        workerProvenance.put("worker_count", workerCount);
        workerProvenance.put("memory_budget_mb", Math.max(1L, maxAggregateBytes / 1_048_576));
        workerProvenance.put("source_manifest_sha256", text(field(manifest, "content_sha256")));
        for (String role : List.of("feature", "label", "execution")) {
            workerProvenance.put(role + "_artifact_sha256", artifactSha(field(artifacts, role)));
        }
        workerProvenance.put("code_sha256", STRATEGY_EVALUATOR_V5_CODE_SHA256);
        workerProvenance.put("evaluator_code_sha256", STRATEGY_EVALUATOR_V5_CODE_SHA256);
        workerProvenance.put("worker_code_sha256", STRATEGY_EVALUATOR_V5_WORKER_CODE_SHA256);
        workerProvenance.put("statistical_code_sha256", STRATEGY_STATISTICAL_V5_CODE_SHA256);
        workerProvenance.put("physical_null_code_sha256", STRATEGY_PHYSICAL_NULL_V5_CODE_SHA256);
        workerProvenance.put("null_artifact_root", cacheRoot.toAbsolutePath().normalize().toString());
        evaluator.workerProvenance = workerProvenance.deepCopy();

        Map<String, PhysicalEvaluatorTrustRegistry.Artifact> physicalArtifacts = new LinkedHashMap<>();
        for (String role : List.of("feature", "label", "execution")) {
            JsonNode artifact = field(artifacts, role);
            physicalArtifacts.put(role, new PhysicalEvaluatorTrustRegistry.Artifact(
                    text(field(artifact, "path")), artifactSha(artifact), optionalLong(field(artifact, "bytes"))));
        }
        JsonNode markArtifact = field(artifacts, "mark");
        if (markArtifact.isObject()) {
            physicalArtifacts.put("mark", new PhysicalEvaluatorTrustRegistry.Artifact(
                    text(field(markArtifact, "path")), artifactSha(markArtifact),
                    optionalLong(field(markArtifact, "bytes"))));
        }
        PhysicalEvaluatorTrustRegistry.Manifest trustManifest = new PhysicalEvaluatorTrustRegistry.Manifest(
                text(field(manifest, "content_sha256")), physicalArtifacts);
        PhysicalEvaluatorTrustRegistry.ScopeIndependentOutcomeConfiguration outcomeConfiguration =
                makeScopeIndependentOutcomeConfiguration(evaluator, workerBinding, manifest, artifacts,
                        features, labels, executions);
        PHYSICAL_TRUST.registerInternalVerifiedPhysicalEvaluator(evaluator, trustManifest,
                root, MAPPER.convertValue(workerProvenance, Map.class), outcomeConfiguration);

        ObjectNode provenance = object();
        provenance.put("mode", "AUTHORITATIVE_PARQUET");
        provenance.put("role_read_mode", episodeIds == null ? "FULL_ROLE_BOUNDED" : "EPISODE_SCOPED_BOUNDED");
        if (episodeIds == null) provenance.set("episode_inventory_sha256", NullNode.instance);
        else {
            ArrayNode sorted = array();
            episodeIds.stream().sorted().forEach(sorted::add);
            provenance.put("episode_inventory_sha256", hash(sorted));
        }
        provenance.put("manifest_sha256", text(field(manifest, "content_sha256")));
        putTextOrNull(provenance, "dataset_root_sha256", field(manifest, "dataset_root_sha256"));
        provenance.put("predictor_registry_sha256", text(field(predictorRegistry, "content_sha256")));
        provenance.put("evaluator_spec_sha256", text(field(evaluatorSpec, "content_sha256")));
        provenance.put("evaluator_code_sha256", STRATEGY_EVALUATOR_V5_CODE_SHA256);
        provenance.put("evaluator_worker_code_sha256", STRATEGY_EVALUATOR_V5_WORKER_CODE_SHA256);
        provenance.put("batch_rows", batchRows);
        provenance.put("max_rows_per_role", maxRows);
        provenance.put("max_materialized_bytes_per_role", maxBytes);
        provenance.put("scheduler", "DETERMINISTIC_CONCURRENT_BATCH_WORKER_THREADS");
        provenance.put("worker_count", workerCount);
        provenance.put("max_result_bytes", maxResultBytes);
        provenance.put("timeout_ms", timeoutMs);
        provenance.put("max_aggregate_worker_bytes", maxAggregateBytes);
        return new LoadedEvaluator(evaluator, provenance);
    }

    public static boolean isVerifiedPhysicalEvaluator(Evaluator evaluator) {
        return PHYSICAL_TRUST.isVerifiedPhysicalEvaluator(evaluator);
    }

    static PhysicalEvaluatorTrustRegistry physicalTrustRegistryV5() { return PHYSICAL_TRUST; }

    private static PhysicalEvaluatorTrustRegistry.ScopeIndependentOutcomeConfiguration
            makeScopeIndependentOutcomeConfiguration(WorkerBackedEvaluator evaluator, ObjectNode workerBinding,
                    ObjectNode manifest, ObjectNode artifacts, ArrayNode featureRows, ArrayNode labelRows,
                    ArrayNode executionRows) {
        JsonNode markArtifact = field(artifacts, "mark");
        ObjectNode metadataBinding = evaluator.metadataCustody.binding();
        if (!markArtifact.isObject() || metadataBinding == null) return null;

        Map<String, String> dataBindings = new LinkedHashMap<>();
        dataBindings.put("feature_artifact_sha256", artifactSha(field(artifacts, "feature")));
        dataBindings.put("label_artifact_sha256", artifactSha(field(artifacts, "label")));
        dataBindings.put("execution_artifact_sha256", artifactSha(field(artifacts, "execution")));
        dataBindings.put("mark_artifact_sha256", artifactSha(markArtifact));
        dataBindings.put("metadata_artifact_sha256", text(field(metadataBinding, "digest")));

        ObjectNode proof = object();
        proof.put("schema", PhysicalEvaluatorTrustRegistry.OUTCOME_PROOF_SCHEMA);
        proof.put("version", 1);
        proof.put("authority", "AUTHORITATIVE_V2_PHYSICAL_EVALUATOR");
        proof.put("verified", true);
        proof.put("source_artifact_sha256", text(field(manifest, "content_sha256")));
        proof.put("evaluator_spec_sha256",
                text(field(field(workerBinding, "evaluatorSpec"), "content_sha256")));
        proof.put("data_bindings_sha256", hash(MAPPER.valueToTree(dataBindings)));
        proof.put("pit_boundary_contract", "CHECK_BEFORE_EVALUATION_AND_ON_CACHE_HIT");
        proof.put("outcome_role_contract", "FEATURE_LABEL_EXECUTION_MARK_METADATA_EXACT_BINDINGS");
        proof.put("one_episode_read_contract", true);
        proof.put("physical_evaluator_code_sha256", STRATEGY_EVALUATOR_V5_CODE_SHA256);
        proof.put("pit_validator_code_sha256", STRATEGY_EVALUATOR_V5_CODE_SHA256);
        proof.put("content_sha256", ownHash(proof));

        Map<String, ObjectNode> featuresByEpisode = rowsByEpisode(featureRows, "feature");
        Map<String, ObjectNode> labelsByEpisode = rowsByEpisode(labelRows, "label");
        Map<String, ObjectNode> executionsByEpisode = rowsByEpisode(executionRows, "execution");
        Evaluator outcomeEvaluator = createBoundEvaluator(workerBinding.deepCopy());
        String sourceSha = text(field(manifest, "content_sha256"));
        String evaluatorSpecSha = text(field(field(workerBinding, "evaluatorSpec"), "content_sha256"));

        PhysicalEvaluatorTrustRegistry.PitBoundaryVerifier pitVerifier = context -> {
            String episodeId = contextString(context, "episodeId");
            ObjectNode feature = featuresByEpisode.get(episodeId);
            ObjectNode label = labelsByEpisode.get(episodeId);
            ObjectNode execution = executionsByEpisode.get(episodeId);
            if (feature == null || label == null || execution == null) {
                throw failure("loader-owned PIT verifier lacks an exact physical episode " + episodeId);
            }
            if (!identity(feature).equals(identity(label)) || !identity(feature).equals(identity(execution))) {
                throw failure("loader-owned PIT verifier found mismatched physical role identity for " + episodeId);
            }
            enforcePitBoundary(feature, label, execution, contextString(context, "phase"),
                    NullNode.instance, contextNode(context, "fitCutoff"),
                    contextNode(context, "evaluationCutoff"));
            return true;
        };
        PhysicalEvaluatorTrustRegistry.OutcomeVerifier outcomeVerifier = context -> {
            Object result = context == null ? null : context.get("result");
            if (!(result instanceof PhysicalEvaluatorTrustRegistry.Outcome outcome)
                    || !Double.isFinite(outcome.netR())) {
                throw failure("loader-owned outcome verifier found an invalid result for "
                        + contextString(context, "episodeId"));
            }
            return true;
        };
        PhysicalEvaluatorTrustRegistry.OutcomeComputer outcomeComputer = context -> {
            String episodeId = contextString(context, "episodeId");
            ObjectNode feature = featuresByEpisode.get(episodeId);
            ObjectNode label = labelsByEpisode.get(episodeId);
            if (feature == null || label == null) {
                throw failure("loader-owned outcome recomputation lacks physical episode " + episodeId);
            }
            String phase = contextString(context, "phase");
            JsonNode foldId = contextNode(context, "foldId");
            ObjectNode signalView = object();
            signalView.put("schema", "strategy-v5-statistical-signal-view/1");
            signalView.put("version", 1);
            signalView.put("source_artifact_sha256", sourceSha);
            signalView.put("phase", phase);
            signalView.set("fold_id", cloneNode(foldId));
            ObjectNode lineage = signalView.putObject("lineage");
            putTextOrNull(lineage, "dataset_sha256", field(manifest, "dataset_root_sha256"));
            lineage.putNull("candidate_set_sha256");
            lineage.put("feature_set_sha256", dataBindings.get("feature_artifact_sha256"));
            lineage.put("label_set_sha256", dataBindings.get("label_artifact_sha256"));
            lineage.put("execution_set_sha256", dataBindings.get("execution_artifact_sha256"));
            signalView.putArray("episode_ids").add(episodeId);
            ObjectNode episode = signalView.putArray("episodes").addObject();
            episode.put("episode_id", episodeId);
            episode.set("asset", cloneNode(field(feature, "asset")));
            episode.set("decision_time", cloneNode(field(feature, "decision_time")));
            episode.set("resolution_time", cloneNode(firstDefinedNonNull(
                    field(label, "resolution_time"), field(label, "resolution_ceiling_time"))));
            episode.put("phase", phase);
            episode.set("fold_id", cloneNode(foldId));
            episode.put("eligible", !exactFalse(field(feature, "signal_eligible")));

            ObjectNode task = object();
            task.set("artifact", signalView);
            task.putArray("episode_ids").add(episodeId);
            task.set("chromosome", objectOrEmpty(contextNode(context, "chromosome")));
            task.put("phase", phase);
            task.set("fold_id", cloneNode(foldId));
            task.putNull("cutoff");
            task.set("fit_cutoff", contextNode(context, "fitCutoff"));
            task.set("evaluation_cutoff", contextNode(context, "evaluationCutoff"));
            task.put("weighting", "OUTER_OOS".equals(phase)
                    ? "UNWEIGHTED_OOS" : "UNWEIGHTED_VALIDATION");
            ObjectNode evaluated = outcomeEvaluator.evaluate(task);
            JsonNode result = field(field(evaluated, "candidate_returns"), episodeId);
            double netR = numberJs(field(result, "net_r"));
            if (!Double.isFinite(netR)) {
                throw failure("loader-owned outcome recomputation returned no finite episode " + episodeId);
            }
            return new PhysicalEvaluatorTrustRegistry.Outcome(
                    netR, !exactFalse(field(result, "traded")));
        };
        return new PhysicalEvaluatorTrustRegistry.ScopeIndependentOutcomeConfiguration(
                evaluatorSpecSha, dataBindings, proof, metadataBinding,
                pitVerifier, outcomeVerifier, outcomeComputer);
    }

    private static String contextString(Map<String, Object> context, String key) {
        Object value = context == null ? null : context.get(key);
        return value == null ? "null" : value instanceof JsonNode node ? jsString(node) : String.valueOf(value);
    }

    private static JsonNode contextNode(Map<String, Object> context, String key) {
        Object value = context == null ? null : context.get(key);
        if (value == null) return NullNode.instance;
        return value instanceof JsonNode node ? cloneNode(node) : MAPPER.valueToTree(value);
    }

    private static ObjectNode makeExecutionLazyBinding(ObjectNode options, ArrayNode features,
            ArrayNode executions, int maxRows, long maxBytes) {
        JsonNode rawHydration = field(options, "executionHydration");
        if (!truthy(rawHydration)) return null;
        ObjectNode hydration = objectOrEmpty(rawHydration).deepCopy();
        JsonNode envelope = field(options, "opportunityEnvelope");
        if (!"strategy-v5-opportunity-hydration/2".equals(text(field(hydration, "schema")))
                || !text(field(hydration, "content_sha256")).equals(ownHash(hydration))
                || field(hydration, "fixture_only").asBoolean(false) || !truthy(envelope)) {
            throw failure("authoritative evaluator lazy execution requires the verified v2 opportunity envelope/hydration pair");
        }
        JsonNode rawPartitions = field(options, "executionPartitions");
        if (!rawPartitions.isArray() || rawPartitions.isEmpty() || !truthy(field(options, "executionHydrationRoot"))) {
            throw failure("authoritative evaluator lazy execution requires physical partition custody");
        }
        Path hydrationRoot = requiredPath(field(options, "executionHydrationRoot"), "execution hydration root");
        requireRealDirectory(hydrationRoot, "authoritative execution hydration root");
        ArrayNode partitions = array();
        Map<String, ObjectNode> inventory = new LinkedHashMap<>();
        for (JsonNode raw : rawPartitions) {
            ObjectNode partition = objectOrEmpty(raw).deepCopy();
            String sha = text(firstTruthy(field(partition, "sha256"), field(partition, "partition_sha256")));
            requireHash(JSON.textNode(sha), "execution partition sha256");
            String reference = text(firstTruthy(field(partition, "path"), field(partition, "partition_path")));
            Path path = securePhysicalReference(hydrationRoot, reference, "authoritative execution partition");
            byte[] bytes = readBytes(path, "authoritative execution partition");
            if (!hash(bytes).equals(sha)) throw failure("authoritative execution partition bytes changed");
            Long declaredBytes = optionalLong(field(partition, "bytes"));
            if (declaredBytes != null && declaredBytes != bytes.length) {
                throw failure("authoritative execution partition bytes changed");
            }
            partition.put("sha256", sha);
            partition.put("path", path.toString());
            partition.put("bytes", bytes.length);
            ObjectNode previous = inventory.putIfAbsent(sha, partition);
            if (previous != null && !jsEquivalent(previous, partition)) {
                throw failure("authoritative execution partition inventory is ambiguous");
            }
            if (previous == null) partitions.add(partition);
        }

        Map<String, ObjectNode> featuresByEpisode = rowsByEpisode(features, "feature");
        Set<String> executionIds = new HashSet<>();
        ArrayNode windows = array(field(envelope, "windows"));
        ArrayNode captures = array(field(hydration, "windows"));
        for (int index = 0; index < executions.size(); index++) {
            ObjectNode execution = objectOrEmpty(executions.get(index)).deepCopy();
            String episodeId = text(field(execution, "episode_id"));
            if (episodeId.isBlank() || !executionIds.add(episodeId)) {
                throw failure("authoritative execution role has a duplicate episode identity " + episodeId);
            }
            ObjectNode feature = featuresByEpisode.get(episodeId);
            if (feature == null) {
                throw failure("authoritative feature role lacks an exact execution episode " + episodeId);
            }
            for (String identityField : List.of("asset", "instrument", "symbol")) {
                if (!text(field(feature, identityField)).equalsIgnoreCase(text(field(execution, identityField)))) {
                    throw failure("feature/execution " + identityField + " identity differs for episode " + episodeId);
                }
            }
            if (time(field(feature, "decision_time")) != time(field(execution, "decision_time"))) {
                throw failure("feature/execution decision_time identity differs for episode " + episodeId);
            }
            if (!text(field(feature, "signal_id")).equals(text(field(execution, "signal_id")))) {
                throw failure("feature/execution signal_id identity differs for episode " + episodeId);
            }
            List<JsonNode> matchingWindows = new ArrayList<>();
            for (JsonNode window : windows) if (lazyWindowMatches(window, execution)) matchingWindows.add(window);
            if (matchingWindows.size() != 1) {
                throw failure("v2 opportunity hydration lacks an exact execution window for episode " + episodeId);
            }
            JsonNode window = matchingWindows.getFirst();
            JsonNode capture = null;
            for (JsonNode candidate : captures) {
                if (text(field(candidate, "window_id")).equals(text(field(window, "window_id")))) {
                    if (capture != null) throw failure("v2 opportunity hydration has an ambiguous capture for episode " + episodeId);
                    capture = candidate;
                }
            }
            if (capture == null) {
                throw failure("v2 opportunity hydration lacks an exact capture for episode " + episodeId);
            }
            ArrayNode references = array();
            array(field(capture, "preentry_partition_refs")).forEach(references::add);
            array(field(capture, "partition_refs")).forEach(references::add);
            for (JsonNode reference : references) {
                if (!inventory.containsKey(text(field(reference, "partition_sha256")))) {
                    throw failure("v2 opportunity hydration has an unbound execution partition for episode " + episodeId);
                }
            }
            if (!"BINANCE_SPOT".equalsIgnoreCase(text(field(execution, "instrument")))) {
                for (JsonNode reference : array(field(capture, "mark_partition_refs"))) {
                    if (!inventory.containsKey(text(field(reference, "partition_sha256")))) {
                        throw failure("v2 opportunity hydration has an unbound mark partition for episode " + episodeId);
                    }
                }
            }
            copyTruthy(execution, "entry_time", field(window, "entry_time"));
            copyTruthy(execution, "execution_start", field(window, "execution_start"));
            copyTruthy(execution, "execution_end", field(window, "execution_end"));
            if (!truthy(field(execution, "availability_time"))) {
                copyTruthy(execution, "availability_time", field(feature, "availability_time"));
            }
            putDefault(execution, "entry_policy", "NEXT_BAR_OPEN");
            putDefault(execution, "decision_timestamp_convention", "COMPLETED_4H_BOUNDARY");
            putDefault(execution, "decision_timeframe", "4h");
            if (!truthy(field(execution, "lifecycle_timeframe"))) {
                copyTruthy(execution, "lifecycle_timeframe", field(window, "lifecycle_timeframe"));
            }
            if (!truthy(field(execution, "max_lifecycle_ms"))) {
                copyTruthy(execution, "max_lifecycle_ms", field(window, "max_lifecycle_ms"));
            }
            ObjectNode reference = object();
            reference.put("window_id", text(field(window, "window_id")));
            reference.set("preentry_start", truthy(field(window, "preentry_start"))
                    ? cloneNode(field(window, "preentry_start")) : NullNode.instance);
            reference.set("execution_start", cloneNode(field(window, "entry_time")));
            reference.set("execution_end", cloneNode(field(window, "execution_end")));
            execution.set("execution_reference", reference);
            executions.set(index, execution);
        }
        if (executions.size() != features.size() || featuresByEpisode.keySet().stream()
                .anyMatch(episodeId -> !executionIds.contains(episodeId))) {
            throw failure("authoritative feature/execution role inventories do not reconcile");
        }
        ObjectNode binding = object();
        binding.set("hydration", hydration);
        binding.set("partitions", partitions);
        binding.put("root", hydrationRoot.toAbsolutePath().normalize().toString());
        binding.put("batch_size", (int) numberOr(field(hydration, "batch_size"), 4_096));
        binding.put("max_rows", (long) numberOr(field(hydration, "max_rows"), maxRows));
        binding.put("max_resident_bytes", (long) numberOr(field(hydration, "max_resident_bytes"), 192L * 1_024 * 1_024));
        binding.put("max_output_bytes", Math.min(maxBytes, 128 * 1_024 * 1_024));
        return binding;
    }

    private static Map<String, ObjectNode> rowsByEpisode(ArrayNode rows, String role) {
        Map<String, ObjectNode> output = new LinkedHashMap<>();
        for (JsonNode raw : rows) {
            ObjectNode row = objectOrEmpty(raw);
            String id = text(field(row, "episode_id"));
            if (id.isBlank() || output.putIfAbsent(id, row) != null) {
                throw failure("duplicate " + role + " episode " + id);
            }
        }
        return output;
    }

    private static boolean lazyWindowMatches(JsonNode window, JsonNode execution) {
        if (!text(field(window, "asset")).equalsIgnoreCase(text(field(execution, "asset")))
                || !text(field(window, "instrument")).equalsIgnoreCase(text(field(execution, "instrument")))
                || !text(field(window, "symbol")).equalsIgnoreCase(text(field(execution, "symbol")))) return false;
        Long left = tryTime(field(window, "decision_time"));
        Long right = tryTime(field(execution, "decision_time"));
        if (left == null || !left.equals(right)) return false;
        if (definedNonNull(field(window, "episode_id"))
                && !text(field(window, "episode_id")).equals(text(field(execution, "episode_id")))) return false;
        return !definedNonNull(field(window, "signal_id"))
                || text(field(window, "signal_id")).equals(text(field(execution, "signal_id")));
    }

    private static void copyTruthy(ObjectNode target, String key, JsonNode value) {
        if (truthy(value)) target.set(key, cloneNode(value));
    }

    private static void putDefault(ObjectNode target, String key, String value) {
        if (!truthy(field(target, key))) target.put(key, value);
    }

    private static double numberOr(JsonNode value, double fallback) {
        double parsed = numberJs(value);
        return definedNonNull(value) && Double.isFinite(parsed) ? parsed : fallback;
    }

    private static Evaluator createBoundEvaluator(ObjectNode args) {
        return new BoundEvaluator(args == null ? object() : args);
    }

    private static final class BoundEvaluator implements Evaluator {
        private final ObjectNode evaluatorSpec;
        private final ObjectNode geneSpace;
        private final ObjectNode predictorRegistry;
        private final JsonNode metadata;
        private final JsonNode envelopeByEpisode;
        private final String sourceArtifactSha256;
        private final Map<String, ObjectNode> features = new LinkedHashMap<>();
        private final Map<String, ObjectNode> labels = new LinkedHashMap<>();
        private final Map<String, ObjectNode> executions = new LinkedHashMap<>();
        private final List<String> predictorIds;
        private final boolean fixtureOnly;
        private final MetadataCustody metadataCustody;
        private final RoleCustody roleCustody;
        private final ExecutionLazyCustody executionLazy;

        BoundEvaluator(ObjectNode args) {
            evaluatorSpec = objectOrEmpty(field(args, "evaluatorSpec")).deepCopy();
            geneSpace = objectOrEmpty(field(args, "geneSpace")).deepCopy();
            predictorRegistry = objectOrEmpty(field(args, "predictorRegistry")).deepCopy();
            validateEvaluatorSpecV5(evaluatorSpec, geneSpace, predictorRegistry);
            sourceArtifactSha256 = requireHash(field(args, "sourceArtifactSha256"), "source_artifact_sha256");
            fixtureOnly = "FIXTURE".equalsIgnoreCase(text(field(args, "mode")));
            metadata = cloneOrEmptyObject(field(args, "metadata"));
            envelopeByEpisode = cloneOrEmptyObject(field(args, "envelopeByEpisode"));
            roleCustody = RoleCustody.capture(field(args, "physicalRoot"), field(args, "roleArtifacts"));
            metadataCustody = MetadataCustody.capture(
                    metadata, roleCustody.enabled, field(args, "metadataRoot"));
            executionLazy = ExecutionLazyCustody.capture(field(args, "executionLazy"));
            if (!field(args, "features").isArray() || !field(args, "labels").isArray()
                    || !field(args, "execution").isArray()) {
                throw failure("bound evaluator requires physically loaded feature, label, and execution rows");
            }
            loadRows(array(field(args, "features")), features, "feature");
            loadRows(array(field(args, "labels")), labels, "label");
            loadRows(array(field(args, "execution")), executions, "execution");
            for (Map.Entry<String, ObjectNode> entry : features.entrySet()) {
                ObjectNode feature = entry.getValue();
                if (time(field(feature, "availability_time")) > time(field(feature, "decision_time"))) {
                    throw failure("feature " + entry.getKey() + " was unavailable at decision");
                }
                if (!exactFalse(field(feature, "signal_eligible"))) {
                    ObjectNode label = labels.get(entry.getKey());
                    ObjectNode execution = executions.get(entry.getKey());
                    if (label == null || execution == null || !identity(feature).equals(identity(label))
                            || !identity(feature).equals(identity(execution))) {
                        throw failure("episode " + entry.getKey() + " lacks exact separated bindings");
                    }
                }
            }
            predictorIds = requiredPredicatePredictorIds(field(evaluatorSpec, "predicate"));
        }

        private static void loadRows(ArrayNode rows, Map<String, ObjectNode> destination, String role) {
            for (JsonNode raw : rows) {
                ObjectNode row = objectOrEmpty(raw).deepCopy();
                String episodeId = jsString(field(row, "episode_id"));
                if (destination.putIfAbsent(episodeId, row) != null) {
                    throw failure("duplicate " + role + " episode " + episodeId);
                }
            }
        }

        @Override public List<String> publicPredictorIds() { return predictorIds; }

        @Override public ObjectNode evaluate(ObjectNode args) {
            roleCustody.verify();
            metadataCustody.verify();
            executionLazy.verify();
            ObjectNode task = args == null ? object() : args;
            ObjectNode artifact = objectOrEmpty(field(task, "artifact"));
            if (!sourceArtifactSha256.equals(text(field(artifact, "source_artifact_sha256")))) {
                throw failure("evaluator signal view is not bound to the separated source artifact");
            }
            List<String> episodeIds = stringInventory(field(task, "episode_ids"));
            String phase = jsString(field(task, "phase"));
            JsonNode foldId = defined(field(task, "fold_id")) ? field(task, "fold_id") : NullNode.instance;
            exactSignalInventory(artifact, episodeIds, phase, foldId, features, labels, executions);
            JsonNode cutoff = defined(field(task, "cutoff")) ? field(task, "cutoff") : NullNode.instance;
            JsonNode fitCutoff = defined(field(task, "fit_cutoff")) ? field(task, "fit_cutoff") : NullNode.instance;
            JsonNode evaluationCutoff = defined(field(task, "evaluation_cutoff"))
                    ? field(task, "evaluation_cutoff") : NullNode.instance;
            for (String episodeId : episodeIds) {
                enforcePitBoundary(features.get(episodeId), labels.get(episodeId), executions.get(episodeId),
                        phase, cutoff, fitCutoff, evaluationCutoff);
            }

            ObjectNode chromosome = objectOrEmpty(field(task, "chromosome"));
            ObjectNode candidate = objectOrEmpty(resolveTemplate(field(evaluatorSpec, "candidate_template"), chromosome));
            ObjectNode executionContract = objectOrEmpty(field(evaluatorSpec, "execution_contract"));
            candidate.set("decision_timestamp_convention", cloneNode(field(executionContract, "decision_timestamp_convention")));
            candidate.set("decision_timeframe", cloneNode(field(executionContract, "decision_timeframe")));
            bindFrozenContract(candidate, executionContract, evaluatorSpec, "risk_convention", "risk_contract");
            bindFrozenContract(candidate, executionContract, evaluatorSpec, "sizing_contract", "sizing_contract");
            bindFrozenContract(candidate, executionContract, evaluatorSpec, "derivative_policy", "derivative_policy");

            ObjectNode candidateReturns = object();
            ArrayNode signalIntentVector = array();
            List<Outcome> outcomes = new ArrayList<>();
            JsonNode forcedIntents = field(task, "forced_intents");
            for (String episodeId : episodeIds) {
                ObjectNode feature = features.get(episodeId);
                if (feature == null) throw failure("feature episode " + episodeId + " is missing");
                List<String> missing = missingPredicatePredictors(field(evaluatorSpec, "predicate"), feature);
                if (!exactFalse(field(feature, "signal_eligible")) && !missing.isEmpty()) {
                    throw failure("eligible feature episode " + episodeId
                            + " is missing required predictor fields: " + String.join(", ", missing));
                }
                boolean forced = forcedIntents.isObject() && present(forcedIntents, episodeId);
                boolean intent = !exactFalse(field(feature, "signal_eligible"))
                        && (forced ? truthy(field(forcedIntents, episodeId))
                        : evaluateSignalPredicateV5(field(evaluatorSpec, "predicate"),
                                publicFeatureRow(feature, predictorIds), chromosome));
                ObjectNode intentRow = object();
                intentRow.put("episode_id", episodeId);
                intentRow.put("intent", intent);
                signalIntentVector.add(intentRow);
                if (!intent) {
                    ObjectNode noTrade = object();
                    noTrade.put("net_r", 0);
                    noTrade.put("traded", false);
                    candidateReturns.set(episodeId, noTrade);
                    outcomes.add(null);
                    continue;
                }
                Outcome outcome;
                try {
                    ObjectNode execution = executionLazy.materialize(executions.get(episodeId));
                    LifecycleTrustService.Token lifecycleToken = canonicalLifecycle(candidate, execution)
                            ? createLifecycleTrustToken(execution, metadata, metadataCustody,
                            roleCustody, evaluatorSpec) : null;
                    outcome = deriveBoundExecutionOutcome(feature, labels.get(episodeId), execution,
                            candidate, field(envelopeByEpisode, episodeId), metadata, evaluatorSpec, fixtureOnly,
                            lifecycleToken);
                } catch (IllegalArgumentException error) {
                    throw failure("canonical lifecycle episode " + episodeId + ": " + error.getMessage());
                }
                ObjectNode traded = object();
                traded.put("net_r", outcome.netR);
                traded.put("traded", true);
                candidateReturns.set(episodeId, traded);
                outcomes.add(outcome.withEpisodeId(episodeId));
            }

            ObjectNode metrics = derivedHardMetrics(outcomes, episodeIds, candidateReturns, chromosome, features, executions);
            ObjectNode behaviorDefinition = candidate.deepCopy();
            behaviorDefinition.set("signal_parameters", chromosome.deepCopy());
            JsonNode resolvedPredicate = resolveTemplate(field(evaluatorSpec, "predicate"), chromosome);
            ObjectNode behaviorContracts = object();
            ObjectNode signalSemantics = object();
            signalSemantics.put("schema", "strategy-v5-signal-semantics/1");
            signalSemantics.set("predicate", resolvedPredicate);
            signalSemantics.set("direction", firstDefined(field(candidate, "direction"), field(candidate, "side"), NullNode.instance));
            behaviorContracts.put("signal_semantics_sha256", hash(signalSemantics));
            behaviorContracts.put("evaluator_sha256", text(field(evaluatorSpec, "content_sha256")));
            behaviorContracts.put("predictor_sha256", text(field(predictorRegistry, "content_sha256")));
            ObjectNode lifecycle = object();
            lifecycle.put("schema", "strategy-v5-lifecycle-semantics/1");
            lifecycle.set("candidate", candidate.deepCopy());
            lifecycle.set("execution_contract", executionContract.deepCopy());
            behaviorContracts.put("lifecycle_sha256", hash(lifecycle));
            behaviorContracts.put("precommit_sha256", text(field(evaluatorSpec, "precommit_sha256")));

            JsonNode effectiveFit = defined(field(task, "fit_cutoff")) ? field(task, "fit_cutoff")
                    : ("OUTER_OOS".equals(phase) ? NullNode.instance : cutoff);
            JsonNode effectiveEvaluation = defined(field(task, "evaluation_cutoff"))
                    ? field(task, "evaluation_cutoff")
                    : ("INNER_VALIDATION".equals(phase) ? cutoff
                    : ("OUTER_OOS".equals(phase) ? NullNode.instance : effectiveFit));
            String weighting = truthy(field(task, "weighting")) ? jsString(field(task, "weighting"))
                    : defaultWeighting(phase);
            return makeEvaluationArtifact(artifact, episodeIds, phase, foldId, cutoff, effectiveFit,
                    effectiveEvaluation, weighting, candidateReturns, metrics, signalIntentVector,
                    behaviorDefinition, behaviorContracts);
        }
    }

    private static final class WorkerBackedEvaluator implements Evaluator {
        private final int workerCount;
        private final List<StrategyEvaluatorV5Worker> workers;
        private final ExecutorService executor;
        private final Path cacheRoot;
        private final int maxResultBytes;
        private final long timeoutMs;
        private final long maxAggregateWorkerBytes;
        private final int workerRolePayloadBytes;
        private final String bindingSha256;
        private final String sourceArtifactSha256;
        private final String sourceDatasetRootSha256;
        private final String executionArtifactSha256;
        private final String evaluatorSpecSha256;
        private final ObjectNode evaluatorSpec;
        private final ObjectNode metadata;
        private final MetadataCustody metadataCustody;
        private final RoleCustody roleCustody;
        private final ExecutionLazyCustody executionLazy;
        private final PhysicalNullSelection physicalNullSelection;
        private final Map<String, ObjectNode> memoryCache = new HashMap<>();
        private final Set<Integer> workerSlotsUsed = new LinkedHashSet<>();
        private long ordinal;
        private long cacheHits;
        private long diskCacheHits;
        private long diskCacheWrites;
        private long batchCount;
        private int peakInFlight;
        private ObjectNode workerProvenance;
        private boolean closed;

        WorkerBackedEvaluator(int workerCount, ObjectNode binding, Path cacheRoot, int maxResultBytes,
                long timeoutMs, long maxAggregateWorkerBytes) {
            if (workerCount < 1 || workerCount > 4) {
                throw failure("authoritative evaluator worker count must be 1..4");
            }
            if (maxResultBytes < 1_024 || maxResultBytes > 256 * 1_024 * 1_024) {
                throw failure("authoritative evaluator result bound is invalid");
            }
            if (maxAggregateWorkerBytes < 16 * 1_024 * 1_024) {
                throw failure("authoritative evaluator aggregate worker memory bound is invalid");
            }
            ObjectNode roles = object();
            roles.set("features", cloneNode(field(binding, "features")));
            roles.set("labels", cloneNode(field(binding, "labels")));
            roles.set("execution", cloneNode(field(binding, "execution")));
            workerRolePayloadBytes = jsonBytes(roles).length;
            if ((long) workerRolePayloadBytes * workerCount > maxAggregateWorkerBytes) {
                throw failure("worker role payload would exceed the aggregate memory bound ("
                        + ((long) workerRolePayloadBytes * workerCount) + " > " + maxAggregateWorkerBytes
                        + "); lower workerCount or use bounded physical reads");
            }
            this.workerCount = workerCount;
            this.maxResultBytes = maxResultBytes;
            this.timeoutMs = timeoutMs;
            this.maxAggregateWorkerBytes = maxAggregateWorkerBytes;
            this.cacheRoot = secureCacheRoot(cacheRoot);
            this.roleCustody = RoleCustody.capture(field(binding, "physicalRoot"), field(binding, "roleArtifacts"));
            this.evaluatorSpec = objectOrEmpty(field(binding, "evaluatorSpec")).deepCopy();
            this.metadata = cloneOrEmptyObject(field(binding, "metadata"));
            this.metadataCustody = MetadataCustody.capture(
                    metadata, roleCustody.enabled, field(binding, "metadataRoot"));
            this.executionLazy = ExecutionLazyCustody.capture(field(binding, "executionLazy"));
            this.sourceArtifactSha256 = text(field(binding, "sourceArtifactSha256"));
            this.sourceDatasetRootSha256 = text(field(binding, "sourceDatasetRootSha256"));
            String boundExecutionArtifact = text(field(binding, "executionArtifactSha256"));
            this.executionArtifactSha256 = HASH_RE.matcher(boundExecutionArtifact).matches()
                    ? boundExecutionArtifact : artifactSha(field(roleCustody.artifacts, "execution"));
            this.evaluatorSpecSha256 = text(field(field(binding, "evaluatorSpec"), "content_sha256"));
            ObjectNode bindingValue = object();
            bindingValue.put("source_artifact_sha256", sourceArtifactSha256);
            putTextOrNull(bindingValue, "source_dataset_root_sha256", field(binding, "sourceDatasetRootSha256"));
            putTextOrNull(bindingValue, "execution_artifact_sha256", field(binding, "executionArtifactSha256"));
            bindingValue.put("evaluator_spec_sha256", evaluatorSpecSha256);
            bindingValue.put("gene_space_sha256", text(field(field(binding, "geneSpace"), "content_sha256")));
            bindingValue.put("predictor_registry_sha256", text(field(field(binding, "predictorRegistry"), "content_sha256")));
            bindingValue.set("metadata", cloneNode(field(binding, "metadata")));
            bindingValue.set("envelope_by_episode", cloneOrEmptyObject(field(binding, "envelopeByEpisode")));
            bindingSha256 = hash(bindingValue);
            executor = Executors.newFixedThreadPool(workerCount, runnable -> {
                Thread thread = new Thread(runnable, "strategy-evaluator-v5-worker");
                thread.setDaemon(true);
                return thread;
            });
            workers = new ArrayList<>(workerCount);
            for (int index = 0; index < workerCount; index++) {
                StrategyEvaluatorV5Worker worker = new StrategyEvaluatorV5Worker(
                        binding.deepCopy(), maxResultBytes, index);
                if (!worker.initialized()) {
                    workers.forEach(StrategyEvaluatorV5Worker::close);
                    worker.close();
                    executor.shutdownNow();
                    throw failure(worker.initializationError().isBlank()
                            ? "authoritative evaluator worker initialization failed"
                            : worker.initializationError());
                }
                workers.add(worker);
            }
            // Only the production loader supplies a physically verified role binding. Internal
            // transformed-role workers deliberately omit it and therefore cannot advertise or
            // recursively invoke the authoritative physical-null capability.
            physicalNullSelection = roleCustody.enabled
                    && !field(binding, "physicalNullInternalWorker").asBoolean(false)
                    ? new PhysicalNullSelection(this, binding.deepCopy(), this.cacheRoot,
                    text(field(binding, "sourceArtifactSha256"))) : null;
        }

        private LifecycleTrustService.Token createLifecycleTrustToken(
                ObjectNode execution, ObjectNode metadataOverrides) {
            if (closed) throw failure("physically verified evaluator is closed");
            return StrategyEvaluatorV5.createLifecycleTrustToken(execution, metadata, metadataCustody,
                    roleCustody, evaluatorSpec, metadataOverrides, sourceArtifactSha256,
                    sourceDatasetRootSha256, executionArtifactSha256);
        }

        private ObjectNode deriveBoundExecutionOutcome(
                ObjectNode options, ObjectNode metadataOverrides) {
            ObjectNode request = options == null ? object() : options.deepCopy();
            ObjectNode effectiveMetadata = metadata.deepCopy();
            if (metadataOverrides != null) metadataOverrides.fields().forEachRemaining(entry ->
                    effectiveMetadata.set(entry.getKey(), entry.getValue().deepCopy()));
            request.set("metadata", effectiveMetadata);
            request.set("evaluatorSpec", evaluatorSpec.deepCopy());
            ObjectNode execution = objectOrEmpty(field(request, "execution"));
            LifecycleTrustService.Token token = createLifecycleTrustToken(execution, metadataOverrides);
            return StrategyResearchDataV5.deriveBoundExecutionOutcome(
                    request, LIFECYCLE_TRUST, token);
        }

        @Override public synchronized ObjectNode evaluate(ObjectNode args) {
            ObjectNode task = args == null ? object() : args;
            if ("physical_null_selection".equals(text(field(task, "operation")))) {
                if (physicalNullSelection == null) {
                    throw failure("PHYSICAL_NULL_SELECTION_ADAPTER_MISSING: evaluator lacks verified physical roles");
                }
                if (closed) throw failure("authoritative evaluator is closed");
                roleCustody.verify();
                metadataCustody.verify();
                executionLazy.verify();
                return physicalNullSelection.run(task);
            }
            return evaluateBatch(List.of(task)).get(0);
        }

        @Override public synchronized List<ObjectNode> evaluateBatch(List<ObjectNode> argsList) {
            if (closed) throw failure("authoritative evaluator is closed");
            if (argsList == null) throw failure("evaluateBatch requires an array of tasks");
            // Cache hits are evidence too: reopen the same physical bindings before trusting them.
            roleCustody.verify();
            metadataCustody.verify();
            executionLazy.verify();
            List<ObjectNode> results = new ArrayList<>(java.util.Collections.nCopies(argsList.size(), null));
            List<PendingTask> pending = new ArrayList<>();
            for (int index = 0; index < argsList.size(); index++) {
                ObjectNode args = argsList.get(index) == null ? object() : argsList.get(index);
                ObjectNode keyValue = object();
                keyValue.put("binding_sha256", bindingSha256);
                keyValue.set("args", args.deepCopy());
                String key = hash(keyValue);
                ObjectNode retained = memoryCache.get(key);
                if (retained != null) {
                    cacheHits++;
                    results.set(index, retained.deepCopy());
                    continue;
                }
                retained = readCache(key);
                if (retained != null) {
                    memoryCache.put(key, retained.deepCopy());
                    cacheHits++;
                    results.set(index, retained.deepCopy());
                    continue;
                }
                pending.add(new PendingTask(args.deepCopy(), key, index, -1));
            }
            pending.sort(Comparator.comparing(PendingTask::key).thenComparingInt(PendingTask::index));
            List<PendingTask> numbered = new ArrayList<>(pending.size());
            for (PendingTask task : pending) numbered.add(task.withOrdinal(ordinal++));
            int localPeak = 0;
            for (int offset = 0; offset < numbered.size(); offset += workerCount) {
                List<PendingTask> chunk = numbered.subList(offset, Math.min(numbered.size(), offset + workerCount));
                localPeak = Math.max(localPeak, chunk.size());
                List<Future<StrategyEvaluatorV5Worker.Response>> futures = new ArrayList<>();
                for (int slot = 0; slot < chunk.size(); slot++) {
                    PendingTask task = chunk.get(slot);
                    int workerSlot = slot;
                    workerSlotsUsed.add(workerSlot);
                    futures.add(executor.submit(() -> workers.get(workerSlot)
                            .evaluate(task.args, task.key, task.ordinal)));
                }
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                for (int slot = 0; slot < futures.size(); slot++) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw failure("authoritative evaluator worker batch timed out");
                    StrategyEvaluatorV5Worker.Response response;
                    try {
                        response = futures.get(slot).get(remaining, TimeUnit.NANOSECONDS);
                    } catch (TimeoutException error) {
                        throw failure("authoritative evaluator worker batch timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw failure("authoritative evaluator worker batch timed out");
                    } catch (ExecutionException error) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        throw failure(cause.getMessage());
                    }
                    if (response.error() != null) throw failure(response.error());
                    PendingTask task = chunk.get(slot);
                    ObjectNode result = response.result();
                    memoryCache.put(task.key, result.deepCopy());
                    writeCache(task.key, result);
                    results.set(task.index, result.deepCopy());
                }
            }
            peakInFlight = Math.max(peakInFlight, localPeak);
            batchCount++;
            if (results.stream().anyMatch(Objects::isNull)) {
                throw failure("authoritative evaluator batch returned an incomplete result");
            }
            return List.copyOf(results);
        }

        private ObjectNode readCache(String key) {
            Path path = cachePath(key);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
            requireSingleLinkFile(path, "content-addressed evaluator cache");
            ObjectNode value;
            try {
                value = objectOrEmpty(MAPPER.readTree(Files.readAllBytes(path)));
            } catch (IOException error) {
                throw failure("content-addressed evaluator cache is tampered or stale: " + key);
            }
            ObjectNode copy = value.deepCopy();
            copy.remove("content_sha256");
            if (!"strategy-v5-evaluation-cache/1".equals(text(field(value, "schema")))
                    || !key.equals(text(field(value, "key")))
                    || !bindingSha256.equals(text(field(value, "binding_sha256")))
                    || !text(field(value, "result_sha256")).equals(hash(field(value, "result")))
                    || !text(field(value, "content_sha256")).equals(hash(copy))) {
                throw failure("content-addressed evaluator cache is tampered or stale: " + key);
            }
            diskCacheHits++;
            return objectOrEmpty(field(value, "result")).deepCopy();
        }

        private void writeCache(String key, ObjectNode result) {
            ObjectNode value = object();
            value.put("schema", "strategy-v5-evaluation-cache/1");
            value.put("version", 1);
            value.put("key", key);
            value.put("binding_sha256", bindingSha256);
            value.put("source_artifact_sha256", sourceArtifactSha256);
            value.put("evaluator_spec_sha256", evaluatorSpecSha256);
            value.put("result_sha256", hash(result));
            value.set("result", result.deepCopy());
            value.put("content_sha256", hash(value));
            Path target = cachePath(key);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                readCache(key);
                return;
            }
            Path temporary = cacheRoot.resolve(key + ".json.tmp-" + Thread.currentThread().getId()
                    + "-" + System.nanoTime());
            try {
                Files.write(temporary, appendLf(jsonBytes(value)), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(temporary, target);
                }
                diskCacheWrites++;
            } catch (java.nio.file.FileAlreadyExistsException error) {
                readCache(key);
            } catch (IOException error) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    readCache(key);
                    return;
                }
                throw failure(error.getMessage());
            }
        }

        private Path cachePath(String key) {
            if (!HASH_RE.matcher(key).matches()) throw failure("content-addressed evaluator cache key is invalid");
            Path value = cacheRoot.resolve(key + ".json").normalize();
            if (!value.getParent().equals(cacheRoot)) throw failure("content-addressed evaluator cache path escapes its root");
            return value;
        }

        @Override public synchronized ObjectNode diagnostics() {
            ObjectNode value = object();
            value.put("scheduler", "DETERMINISTIC_CONCURRENT_BATCH_WORKER_THREADS");
            value.put("worker_count", workerCount);
            ArrayNode slots = array();
            workerSlotsUsed.stream().sorted().forEach(slots::add);
            value.set("worker_slots_used", slots);
            value.put("evaluation_count", ordinal);
            value.put("cache_hit_count", cacheHits);
            value.put("disk_cache_hit_count", diskCacheHits);
            value.put("disk_cache_write_count", diskCacheWrites);
            value.put("cache_entry_count", memoryCache.size());
            value.put("binding_sha256", bindingSha256);
            value.put("cache_root_reference_sha256", hash(cacheRoot.toString()));
            value.put("max_result_bytes", maxResultBytes);
            value.put("timeout_ms", timeoutMs);
            value.put("batch_count", batchCount);
            value.put("peak_in_flight", peakInFlight);
            value.put("concurrent_dispatch", workerCount > 1);
            value.put("worker_role_payload_bytes", workerRolePayloadBytes);
            value.put("aggregate_worker_memory_bound_bytes", maxAggregateWorkerBytes);
            return value;
        }

        @Override public ObjectNode workerProvenance() {
            return workerProvenance == null ? null : workerProvenance.deepCopy();
        }

        @Override public boolean physicalNullSelectionVerified() { return physicalNullSelection != null; }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            executor.shutdownNow();
            for (StrategyEvaluatorV5Worker worker : workers) worker.close();
        }
    }

    /** Loader-owned transformed-role, nested-selection, and iteration-CAS implementation. */
    private static final class PhysicalNullSelection {
        private static final Set<String> METHODS = Set.of("block_permuted_labels",
                "timestamp_shifted_outcomes", "frequency_matched_random_intents",
                "winners_curse_selection");

        private final WorkerBackedEvaluator owner;
        private final ObjectNode binding;
        private final Path cacheRoot;
        private final String sourceManifestSha256;
        private final Map<String, ObjectNode> featuresById;
        private final Map<String, ObjectNode> labelsById;
        private final Map<String, ObjectNode> executionById;
        private final ExecutionLazyCustody executionLazy;

        PhysicalNullSelection(WorkerBackedEvaluator owner, ObjectNode binding, Path cacheRoot,
                String sourceManifestSha256) {
            this.owner = owner;
            this.binding = binding.deepCopy();
            this.cacheRoot = cacheRoot;
            this.sourceManifestSha256 = requireHash(JSON.textNode(sourceManifestSha256),
                    "physical null source manifest");
            if (!field(binding, "features").isArray() || !field(binding, "labels").isArray()
                    || !field(binding, "execution").isArray()) {
                throw failure("physical null loader roles are incomplete");
            }
            featuresById = rowsByEpisode(array(field(binding, "features")), "feature");
            labelsById = rowsByEpisode(array(field(binding, "labels")), "label");
            executionById = rowsByEpisode(array(field(binding, "execution")), "execution");
            executionLazy = ExecutionLazyCustody.capture(field(binding, "executionLazy"));
        }

        ObjectNode run(ObjectNode context) {
            String method = text(field(context, "method"));
            if (!METHODS.contains(method)) {
                throw failure("physical null runner received an undeclared transformation");
            }
            ObjectNode source = objectOrEmpty(field(context, "source_artifact"));
            String sourceSha = requireHash(field(source, "content_sha256"),
                    "physical null source artifact");
            if (!sourceSha.equals(ownHash(source))) {
                throw failure("physical null source artifact hash is invalid");
            }
            long seed = exactNonNegativeLong(field(context, "seed"),
                    "physical null runner seed/iteration is invalid");
            long iteration = exactNonNegativeLong(field(context, "iteration"),
                    "physical null runner seed/iteration is invalid");
            ObjectNode budget = objectOrEmpty(field(context, "selection_budget"));
            List<String> ids = new ArrayList<>();
            for (JsonNode row : array(field(source, "episodes"))) {
                ids.add(jsString(field(row, "episode_id")));
            }
            List<ObjectNode> sourceLabels = new ArrayList<>();
            List<ObjectNode> sourceExecutions = new ArrayList<>();
            for (String id : ids) {
                sourceLabels.add(labelsById.get(id));
                sourceExecutions.add(executionById.get(id));
            }
            if (sourceLabels.stream().anyMatch(Objects::isNull)
                    || sourceExecutions.stream().anyMatch(Objects::isNull)) {
                throw failure("physical null source artifact is missing a bound label or execution row");
            }

            List<ObjectNode> labelsForRun = copyRows(sourceLabels);
            List<ObjectNode> executionForRun = copyRows(sourceExecutions);
            ObjectNode forcedIntents = null;
            ObjectNode transformation = object();
            transformation.put("method", method); transformation.put("seed", seed);
            transformation.put("iteration", iteration);

            if (Set.of("block_permuted_labels", "timestamp_shifted_outcomes",
                    "winners_curse_selection").contains(method)) {
                int blockLength = Math.max(1, (int) Math.ceil(Math.sqrt(Math.max(1, ids.size()))));
                Mapping shifted = stratifiedMapping(ids, method, seed + iteration, blockLength, source);
                List<ObjectNode> materialized = new ArrayList<>();
                for (ObjectNode row : sourceExecutions) {
                    ObjectNode value = executionLazy.materialize(row);
                    value.remove("execution_reference");
                    materialized.add(value);
                }
                labelsForRun = new ArrayList<>(); executionForRun = new ArrayList<>();
                ArrayNode executionLineage = array(), mappingRows = array();
                for (int targetIndex = 0; targetIndex < ids.size(); targetIndex++) {
                    int sourceIndex = shifted.mapping[targetIndex];
                    labelsForRun.add(transformedLabel(sourceLabels.get(targetIndex),
                            sourceLabels.get(sourceIndex)));
                    ObjectNode transformed = rebasePhysicalNullExecutionV5(
                            sourceExecutions.get(targetIndex), materialized.get(sourceIndex));
                    if (transformed.has("execution_reference")) {
                        throw failure("physical null transformed execution retained a source lazy reference");
                    }
                    executionForRun.add(transformed);
                    ObjectNode lineage = object();
                    lineage.put("source_episode_id", ids.get(sourceIndex));
                    lineage.put("target_episode_id", ids.get(targetIndex));
                    JsonNode reference = field(sourceExecutions.get(sourceIndex), "execution_reference");
                    lineage.put("source_execution_reference_sha256",
                            hash(defined(reference) ? reference : NullNode.instance));
                    lineage.put("source_execution_sha256", hash(materialized.get(sourceIndex)));
                    lineage.put("transformed_execution_sha256", hash(transformed));
                    executionLineage.add(lineage);
                    ObjectNode mapping = object(); mapping.put("source_episode_id", ids.get(sourceIndex));
                    mapping.put("target_episode_id", ids.get(targetIndex)); mappingRows.add(mapping);
                }
                transformation.put("execution_materialization",
                        "PHYSICAL_REFERENCE_REOPENED_AND_REBASED");
                transformation.put("execution_lineage_sha256", hash(executionLineage));
                transformation.put("mapping_sha256", hash(mappingRows));
                transformation.set("strata", shifted.metadata.deepCopy());
                transformation.put("strata_inventory_sha256", hash(shifted.metadata));
                if ("timestamp_shifted_outcomes".equals(method)) transformation.putNull("block_length");
                else transformation.put("block_length", blockLength);
                if ("timestamp_shifted_outcomes".equals(method)) {
                    ArrayNode offsets = array();
                    for (JsonNode row : shifted.metadata) offsets.add(cloneNode(field(row, "offset")));
                    transformation.set("shift_episodes", offsets);
                } else transformation.putNull("shift_episodes");
                transformation.putNull("shift_ms");
                transformation.put("selection_budget_rerun", "winners_curse_selection".equals(method));
            }

            if ("frequency_matched_random_intents".equals(method)) {
                FrequencyResult frequency = frequencyMatched(context, source, ids, seed, iteration);
                forcedIntents = frequency.forcedIntents;
                transformation.setAll(frequency.transformation);
            }

            transformation.put("transformation_sha256", hash(transformation));
            Path root = physicalRoot(context);
            String selectionBudgetSha = hash(budget);
            ObjectNode checkpointKeyValue = object();
            checkpointKeyValue.put("schema", "strategy-v5-physical-null-checkpoint-key/2");
            checkpointKeyValue.put("source_artifact_sha256", sourceSha);
            JsonNode runnerCode = field(field(context, "physical_runner_contract"), "code_sha256");
            if (definedNonNull(runnerCode)) checkpointKeyValue.set("physical_runner_code_sha256", cloneNode(runnerCode));
            else checkpointKeyValue.putNull("physical_runner_code_sha256");
            checkpointKeyValue.put("method", method); checkpointKeyValue.put("seed", seed);
            checkpointKeyValue.put("iteration", iteration);
            checkpointKeyValue.put("selection_budget_sha256", selectionBudgetSha);
            checkpointKeyValue.put("transformation_sha256", text(field(transformation, "transformation_sha256")));
            String checkpointKey = hash(checkpointKeyValue);
            Path checkpointPath = root.resolve("null-checkpoint-" + checkpointKey + ".json");
            Path selectionPath = root.resolve("null-selection-" + checkpointKey + ".json");
            if (Files.exists(checkpointPath, LinkOption.NOFOLLOW_LINKS)) {
                return resume(context, sourceSha, method, seed, iteration, selectionBudgetSha,
                        transformation, root, checkpointKey, checkpointPath, selectionPath);
            }

            SelectionResult result = runSelection(context, source, budget, labelsForRun,
                    executionForRun, forcedIntents, root);
            ObjectNode trace = result.trace.deepCopy();
            trace.set("transformation", transformation.deepCopy());
            trace.put("transformation_sha256", text(field(transformation, "transformation_sha256")));
            trace.put("checkpoint_key_sha256", checkpointKey);
            trace.put("checkpoint_resume", "CONTENT_ADDRESSED_PER_ITERATION_CAS");

            PhysicalRef labelRef = persist(root, "transformed-label", roleArtifact(
                    "strategy-v5-physical-null-label-role/1", sourceSha, method, labelsForRun));
            PhysicalRef executionRef = persist(root, "transformed-execution", roleArtifact(
                    "strategy-v5-physical-null-execution-role/1", sourceSha, method, executionForRun));
            PhysicalRef outcomeRef = persist(root, "recomputed-outcome", roleArtifact(
                    "strategy-v5-physical-null-outcome/1", sourceSha, method, result.vector));
            PhysicalRef vectorRef = persist(root, "selected-outcome-vector", roleArtifact(
                    "strategy-v5-physical-null-vector/1", sourceSha, method, result.vector));

            Map<String, ObjectNode> sourceEpisodes = rowsByEpisode(array(field(source, "episodes")), "source");
            ArrayNode physicalRows = array();
            for (JsonNode raw : result.vector) {
                ObjectNode episode = sourceEpisodes.get(text(field(raw, "episode_id")));
                if (episode == null) throw failure("physical null selected vector references unknown episode "
                        + text(field(raw, "episode_id")));
                ObjectNode row = objectOrEmpty(raw).deepCopy();
                row.set("asset", cloneNode(field(episode, "asset")));
                row.set("decision_time", cloneNode(field(episode, "decision_time")));
                row.set("resolution_time", cloneNode(field(episode, "resolution_time")));
                row.put("value", numberJs(field(raw, "net_r"))); physicalRows.add(row);
            }
            ArrayNode traded = array();
            for (JsonNode row : physicalRows) if (field(row, "traded").asBoolean(false)) traded.add(row);
            ArrayNode independent = StrategyStatisticalV5.collapseMarketEpisodeRows(
                    traded, field(source, "episodes"));
            double selectedStatistic = 0;
            if (!independent.isEmpty()) {
                for (JsonNode row : independent) selectedStatistic += numberJs(field(row, "value"));
                selectedStatistic /= independent.size();
            }
            trace.put("sampling_unit", "independent_market_episode_cluster");
            PhysicalRef traceRef = persist(root, "selection-trace", trace);

            ObjectNode checkpoint = object();
            checkpoint.put("schema", "strategy-v5-physical-null-checkpoint/1");
            checkpoint.put("version", 1); checkpoint.put("status", "COMPLETED");
            checkpoint.put("checkpoint_key_sha256", checkpointKey);
            checkpoint.put("source_artifact_sha256", sourceSha);
            checkpoint.put("source_manifest_sha256", sourceManifestSha256);
            checkpoint.put("method", method); checkpoint.put("seed", seed);
            checkpoint.put("iteration", iteration);
            checkpoint.put("selection_budget_sha256", selectionBudgetSha);
            checkpoint.put("transformation_sha256", text(field(transformation, "transformation_sha256")));
            checkpoint.put("selection_path", selectionPath.toString());
            checkpoint.put("selected_statistic", selectedStatistic);
            checkpoint.put("transformed_label_artifact_sha256", labelRef.contentSha256);
            checkpoint.put("transformed_execution_artifact_sha256", executionRef.contentSha256);
            checkpoint.put("recomputed_outcome_artifact_sha256", outcomeRef.contentSha256);
            checkpoint.put("selected_outcome_vector_sha256", vectorRef.contentSha256);
            checkpoint.put("trace_sha256", traceRef.contentSha256);
            PhysicalRef checkpointRef = writePhysicalFile(checkpointPath, checkpoint, "null-checkpoint", root);

            ObjectNode roleHashes = objectOrEmpty(field(context, "role_hashes"));
            ObjectNode selected = object(); selected.put("schema", "strategy-v5-physical-null-selection/1");
            selected.put("version", 1); selected.put("method", method); selected.put("seed", seed);
            selected.put("iteration", iteration); selected.put("source_artifact_sha256", sourceSha);
            selected.put("source_manifest_sha256", sourceManifestSha256);
            selected.put("feature_artifact_sha256", text(field(roleHashes, "feature_artifact_sha256")));
            selected.put("label_artifact_sha256", text(field(roleHashes, "label_artifact_sha256")));
            selected.put("execution_artifact_sha256", text(field(roleHashes, "execution_artifact_sha256")));
            selected.put("selection_budget_sha256", selectionBudgetSha);
            selected.put("transformation_sha256", text(field(transformation, "transformation_sha256")));
            selected.put("transformed_label_artifact_sha256", labelRef.contentSha256);
            selected.put("transformed_execution_artifact_sha256", executionRef.contentSha256);
            selected.put("recomputed_outcome_artifact_sha256", outcomeRef.contentSha256);
            selected.put("selected_outcome_vector_sha256", vectorRef.contentSha256);
            selected.put("trace_sha256", traceRef.contentSha256);
            selected.set("transformed_label_ref", labelRef.toJson());
            selected.set("transformed_execution_ref", executionRef.toJson());
            selected.set("recomputed_outcome_ref", outcomeRef.toJson());
            selected.set("selected_outcome_vector_ref", vectorRef.toJson());
            selected.set("trace_ref", traceRef.toJson());
            selected.put("selected_candidate_id", text(field(trace, "selected_behavior_alias_sha256")));
            selected.put("selected_statistic", selectedStatistic);
            selected.set("checkpoint_ref", checkpointRef.toJson());
            selected.put("checkpoint_status", "COMPLETED");
            ObjectNode finalSelection = StrategyStatisticalV5.withHash(selected);
            writePhysicalFile(selectionPath, finalSelection, "null-selection", root);
            return finalSelection;
        }

        private SelectionResult runSelection(ObjectNode context, ObjectNode source, ObjectNode budget,
                List<ObjectNode> labelsForRun, List<ObjectNode> executionForRun,
                ObjectNode forcedIntents, Path root) {
            ObjectNode head = objectOrEmpty(field(context, "exposure_head"));
            ObjectNode space = field(context, "gene_space").isObject()
                    ? objectOrEmpty(field(context, "gene_space"))
                    : objectOrEmpty(field(binding, "geneSpace"));
            if (!field(head, "entries").isArray() || !field(space, "genes").isArray()) {
                throw failure("physical null selection lacks the frozen exposure head or gene space");
            }
            Map<String, ObjectNode> definitions = new LinkedHashMap<>();
            for (JsonNode row : array(field(context, "behavior_definitions"))) {
                definitions.put(text(field(row, "behavior_sha256")),
                        objectOrEmpty(field(row, "chromosome")).deepCopy());
            }

            ObjectNode localBinding = binding.deepCopy();
            localBinding.set("labels", arrayOfNodes(labelsForRun));
            localBinding.set("execution", arrayOfNodes(executionForRun));
            localBinding.put("sourceArtifactSha256", text(field(source, "content_sha256")));
            localBinding.put("physicalNullInternalWorker", true);
            Path workerRoot = root.resolve("worker-cache").resolve(text(field(context, "method")))
                    .resolve(numberText(field(context, "seed")) + "-" + numberText(field(context, "iteration")));
            int localWorkers = Math.max(1, Math.min(4, owner.workerCount));
            WorkerBackedEvaluator raw = new WorkerBackedEvaluator(localWorkers, localBinding, workerRoot,
                    64 * 1_024 * 1_024, 120_000,
                    Math.max(16L * 1_024 * 1_024, owner.maxAggregateWorkerBytes));
            Evaluator local = definitionBindingEvaluator(raw, definitions, forcedIntents,
                    text(field(source, "content_sha256")));
            ObjectNode config = object();
            config.put("population", (long) numberJs(field(budget, "population")));
            config.put("generations", (long) numberJs(field(budget, "generations")));
            config.put("minGenerations", (long) numberJs(definedNonNull(field(budget, "minGenerations"))
                    ? field(budget, "minGenerations") : field(budget, "generations")));
            config.put("plateauGenerations", (long) numberJs(definedNonNull(field(budget, "plateauGenerations"))
                    ? field(budget, "plateauGenerations") : JSON.numberNode(5)));
            config.put("crossoverProbability", numberOr(field(budget, "crossoverProbability"), .9));
            config.put("halfLifeMonths", numberOr(field(budget, "halfLifeMonths"), 18));
            config.set("seeds", cloneNode(field(budget, "seeds")));
            config.set("constraints", cloneOrEmptyObject(field(context, "selection_constraints")));
            if (field(context, "asset_scope").isObject()) {
                config.set("assetScope", cloneNode(field(context, "asset_scope")));
            } else config.putNull("assetScope");
            String end = truthy(field(context, "selection_end_at"))
                    ? jsString(field(context, "selection_end_at")) : latestTime(source, "resolution_time");
            config.put("trainingCutoff", end); config.put("prospectiveCutoff", end);
            config.put("checkpointDirectory", workerRoot.resolve("nested-ga-checkpoints").toString());
            ObjectNode definitionContext = config.putObject("behaviorDefinitionContext");
            definitionContext.put("evaluator_sha256", text(field(field(context, "role_hashes"),
                    "feature_artifact_sha256")));
            definitionContext.putNull("precommit_sha256"); definitionContext.putNull("lifecycle_sha256");

            StrategyStatisticalV5.StatisticalProvider stress = task -> {
                ObjectNode args = object(); args.set("lineage_sha256", cloneNode(field(task, "lineage_sha256")));
                args.put("sourceArtifactSha256", text(field(field(task, "artifact"), "content_sha256")));
                args.put("selectedCandidateId", text(field(task, "selected_candidate_id")));
                args.put("pass", true); return StrategyStatisticalV5.makeStressDecision(args);
            };
            StrategyStatisticalV5.StatisticalProvider portfolio = task -> {
                ObjectNode scoped = objectOrEmpty(field(task, "artifact"));
                ArrayNode decisions = array();
                for (JsonNode row : array(field(task, "asset_decisions"))) {
                    ObjectNode decision = object(); decision.set("asset", cloneNode(field(row, "asset")));
                    decision.put("pass", true); decisions.add(decision);
                }
                ArrayNode increments = array();
                for (JsonNode row : array(field(scoped, "episodes"))) {
                    ObjectNode increment = object(); increment.set("episode_id", cloneNode(field(row, "episode_id")));
                    increment.set("asset", cloneNode(field(row, "asset"))); increment.put("net_r", 0);
                    increments.add(increment);
                }
                ObjectNode digest = object(); digest.put("null", true);
                digest.put("lineageSha", text(field(task, "lineage_sha256")));
                ObjectNode args = object(); args.set("lineage_sha256", cloneNode(field(task, "lineage_sha256")));
                args.set("artifact", scoped); args.put("sourceArtifactSha256", text(field(scoped, "content_sha256")));
                args.put("pass", true); args.set("assetDecisions", decisions);
                args.set("returnIncrements", increments); args.put("riskDigest", hash(digest));
                return StrategyStatisticalV5.makePortfolioDecision(args);
            };
            StrategyStatisticalV5.StatisticalProvider vectors = task -> vectorInventory(task, source,
                    head, definitions, local);
            ObjectNode nestedArgs = object(); nestedArgs.set("artifact", source.deepCopy());
            nestedArgs.set("geneSpace", space.deepCopy()); nestedArgs.set("exposureHead", head.deepCopy());
            nestedArgs.set("config", config); nestedArgs.put("mode", "FIXTURE");
            nestedArgs.put("endAt", truthy(field(context, "selection_end_at"))
                    ? jsString(field(context, "selection_end_at")) : latestTime(source, "decision_time"));
            ObjectNode nested; ObjectNode diagnostics;
            try {
                nested = StrategyStatisticalV5.runNestedWfoV5(nestedArgs, local, stress, portfolio, vectors);
            } finally {
                diagnostics = raw.diagnostics(); local.close();
            }
            ArrayNode selectedRows = array(); List<String> aliases = new ArrayList<>();
            for (JsonNode fold : array(field(field(nested, "run"), "asset_decisions"))) {
                JsonNode decisions = field(fold, "asset_decisions");
                for (String asset : fieldNamesList(decisions)) {
                    JsonNode decision = field(decisions, asset);
                    for (JsonNode row : array(field(decision, "selected_return_vector"))) {
                        selectedRows.add(cloneNode(row));
                    }
                    if (truthy(field(decision, "selected_behavior_alias_sha256"))) {
                        aliases.add(text(field(decision, "selected_behavior_alias_sha256")));
                    }
                }
            }
            Map<String, JsonNode> vectorById = new LinkedHashMap<>();
            for (JsonNode row : selectedRows) vectorById.put(text(field(row, "episode_id")), row);
            List<String> selectedIds = field(context, "selected_episode_ids").isArray()
                    ? stringInventory(field(context, "selected_episode_ids"))
                    : stringInventory(fieldArray(source, "episodes", "episode_id"));
            ArrayNode vector = array();
            for (String id : selectedIds) {
                JsonNode retained = vectorById.get(id);
                if (retained != null) vector.add(cloneNode(retained));
                else vector.addObject().put("episode_id", id).put("net_r", 0).put("traded", false);
            }
            aliases.sort(String::compareTo); String selectedAlias = aliases.isEmpty() ? "UNSELECTED" : aliases.getFirst();
            long attemptK = integerValue(field(field(nested, "exposureHead"), "exposure_attempt_k"), 0)
                    - integerValue(field(head, "exposure_attempt_k"), 0);
            if (attemptK < 0) throw failure("physical null nested selection exposure-attempt accounting regressed");
            ObjectNode trace = object(); trace.put("schema", "strategy-v5-physical-null-selection-trace/1");
            trace.set("method", cloneNode(field(context, "method"))); trace.set("seed", cloneNode(field(context, "seed")));
            trace.set("iteration", cloneNode(field(context, "iteration")));
            trace.put("selection_budget_sha256", hash(budget));
            trace.put("nested_wfo_sha256", text(field(field(nested, "run"), "content_sha256")));
            trace.put("selected_behavior_alias_sha256", selectedAlias); trace.put("evaluation_attempt_k", attemptK);
            trace.put("cumulative_behavior_k", integerValue(field(field(nested, "run"), "cumulative_k"), 0));
            LinkedHashSet<String> oosIds = new LinkedHashSet<>();
            for (JsonNode row : selectedRows) oosIds.add(text(field(row, "episode_id")));
            List<String> sortedOos = new ArrayList<>(oosIds); sortedOos.sort(String::compareTo);
            trace.set("oos_episode_ids", strings(sortedOos)); trace.put("train_validation_bound", true);
            trace.put("worker_backed", true); trace.put("worker_count", integerValue(field(diagnostics, "worker_count"), localWorkers));
            trace.put("worker_scheduler", truthy(field(diagnostics, "scheduler"))
                    ? text(field(diagnostics, "scheduler")) : "DETERMINISTIC_CONCURRENT_BATCH_WORKER_THREADS");
            trace.set("evaluator_diagnostics", diagnostics); trace.put("checkpoint_resume",
                    "CONTENT_ADDRESSED_PER_GA_PLUS_PER_ITERATION_CAS"); trace.put("vector_sha256", hash(vector));
            return new SelectionResult(vector, trace);
        }

        private Evaluator definitionBindingEvaluator(WorkerBackedEvaluator raw,
                Map<String, ObjectNode> definitions, ObjectNode forcedIntents, String sourceSha) {
            return new Evaluator() {
                private ObjectNode task(ObjectNode source) {
                    ObjectNode value = source.deepCopy(); ObjectNode artifact = objectOrEmpty(field(value, "artifact")).deepCopy();
                    artifact.put("source_artifact_sha256", sourceSha); value.set("artifact", artifact);
                    if (forcedIntents != null) value.set("forced_intents", forcedIntents.deepCopy()); return value;
                }
                private ObjectNode bind(ObjectNode result, ObjectNode args) {
                    if (field(args, "chromosome").isObject()) {
                        ObjectNode chromosome = objectOrEmpty(field(args, "chromosome")).deepCopy();
                        LinkedHashSet<String> aliases = new LinkedHashSet<>();
                        if (truthy(field(result, "behavior_alias_sha256"))) aliases.add(text(field(result, "behavior_alias_sha256")));
                        if (field(result, "signal_intent_vector").isArray()
                                && field(result, "candidate_returns").isObject()) {
                            aliases.add(StrategyStatisticalV5.evaluatedBehaviorAlias(
                                    text(field(result, "signal_behavior_alias_sha256")),
                                    field(result, "candidate_returns"),
                                    field(result, "episode_ids").isArray() ? field(result, "episode_ids")
                                            : field(args, "episode_ids"),
                                    defined(field(result, "candidate_definition"))
                                            ? field(result, "candidate_definition") : NullNode.instance,
                                    field(result, "behavior_contracts")));
                        }
                        aliases.forEach(alias -> definitions.put(alias, chromosome.deepCopy()));
                    }
                    return result;
                }
                @Override public ObjectNode evaluate(ObjectNode args) { return bind(raw.evaluate(task(args)), args); }
                @Override public List<ObjectNode> evaluateBatch(List<ObjectNode> args) {
                    List<ObjectNode> tasks = args.stream().map(this::task).toList();
                    List<ObjectNode> results = raw.evaluateBatch(tasks); List<ObjectNode> output = new ArrayList<>();
                    for (int index = 0; index < results.size(); index++) output.add(bind(results.get(index), args.get(index)));
                    return List.copyOf(output);
                }
                @Override public ObjectNode diagnostics() { return raw.diagnostics(); }
                @Override public void close() { raw.close(); }
            };
        }

        private ObjectNode vectorInventory(ObjectNode task, ObjectNode source, ObjectNode initialHead,
                Map<String, ObjectNode> definitions, Evaluator local) {
            ObjectNode currentHead = field(task, "exposureHead").isObject()
                    ? objectOrEmpty(field(task, "exposureHead")) : initialHead;
            List<String> episodeIds = stringInventory(field(task, "episode_ids"));
            if (episodeIds.isEmpty()) episodeIds = stringInventory(fieldArray(field(task, "artifact"), "episodes", "episode_id"));
            ObjectNode vectors = object();
            for (JsonNode entry : array(field(currentHead, "entries"))) {
                String alias = text(field(entry, "behavior_sha256")); ObjectNode chromosome = definitions.get(alias);
                if (chromosome == null) {
                    throw failure("physical null nested selection lacks a durable definition for " + alias);
                }
                ObjectNode scoped = objectOrEmpty(field(task, "artifact")); ObjectNode view = object();
                view.put("schema", "strategy-v5-statistical-signal-view/1"); view.put("version", 1);
                view.put("phase", "OUTER_OOS");
                JsonNode fold = field(field(scoped, "metadata"), "fold_id");
                if (defined(fold)) view.set("fold_id", cloneNode(fold)); else view.putNull("fold_id");
                view.set("lineage", cloneNode(field(scoped, "lineage")));
                view.put("source_artifact_sha256", text(field(source, "content_sha256")));
                view.set("episode_ids", strings(episodeIds)); ArrayNode episodes = array();
                Set<String> selected = new HashSet<>(episodeIds);
                for (JsonNode row : array(field(scoped, "episodes"))) if (selected.contains(text(field(row, "episode_id")))) {
                    ObjectNode item = object();
                    ObjectNode physicalFeature = featuresById.get(text(field(row, "episode_id")));
                    item.set("episode_id", cloneNode(field(row, "episode_id")));
                    item.set("asset", cloneNode(field(physicalFeature, "asset")));
                    item.set("decision_time", cloneNode(field(physicalFeature, "decision_time")));
                    item.set("resolution_time", cloneNode(field(row, "resolution_time")));
                    item.set("eligible", cloneNode(field(row, "eligible")));
                    episodes.add(item);
                }
                view.set("episodes", episodes); ObjectNode args = object(); args.set("artifact", view);
                args.set("episode_ids", strings(episodeIds)); args.set("chromosome", chromosome.deepCopy());
                args.put("phase", "OUTER_OOS"); if (defined(fold)) args.set("fold_id", cloneNode(fold)); else args.putNull("fold_id");
                args.putNull("cutoff"); args.putNull("fit_cutoff"); args.putNull("evaluation_cutoff");
                args.put("weighting", "UNWEIGHTED_OOS"); ObjectNode evaluated = local.evaluate(args);
                ArrayNode rows = array();
                for (String episodeId : episodeIds) {
                    ObjectNode row = object(); row.put("episode_id", episodeId);
                    row.setAll(objectOrEmpty(field(field(evaluated, "candidate_returns"), episodeId)));
                    row.put("eligible", true); rows.add(row);
                }
                vectors.set(alias, rows);
            }
            ObjectNode args = object(); args.set("exposureHead", currentHead.deepCopy());
            args.set("episodeIds", strings(episodeIds)); args.set("vectors", vectors);
            return StrategyStatisticalV5.makeVectorInventory(args);
        }

        private FrequencyResult frequencyMatched(ObjectNode context, ObjectNode source, List<String> ids,
                long seed, long iteration) {
            String selectedId = truthy(field(context, "selected_candidate_id"))
                    ? jsString(field(context, "selected_candidate_id"))
                    : (array(field(source, "candidates")).isEmpty() ? ""
                    : text(field(array(field(source, "candidates")).get(0), "candidate_id")));
            List<Stratum> groups = strataFor(ids, source);
            Set<String> selectedScope = field(context, "selected_episode_ids").isArray()
                    ? new LinkedHashSet<>(stringInventory(field(context, "selected_episode_ids")))
                    : new LinkedHashSet<>(ids);
            Set<String> selectedTradeIds = field(context, "selected_trade_episode_ids").isArray()
                    ? new LinkedHashSet<>(stringInventory(field(context, "selected_trade_episode_ids"))) : Set.of();
            double suppliedCount = numberJs(field(context, "selected_trade_count"));
            boolean suppliedProfile = !selectedTradeIds.isEmpty() || suppliedCount == 0;
            int observedTotal = 0;
            if (suppliedProfile) observedTotal = selectedTradeIds.size();
            else for (String id : selectedScope) {
                int index = ids.indexOf(id); if (index >= 0 && field(field(field(source, "episodes").get(index),
                        "candidate_returns"), selectedId).path("traded").asBoolean(false)) observedTotal++;
            }
            if (Double.isFinite(suppliedCount) && suppliedCount == Math.rint(suppliedCount)
                    && (long) suppliedCount != observedTotal) {
                throw failure("frequency-matched null trade profile/count mismatch");
            }
            double globalRate = selectedScope.isEmpty() ? 0 : (double) observedTotal / selectedScope.size();
            Set<Integer> chosen = new HashSet<>(); ArrayNode metadata = array();
            for (Stratum group : groups) {
                List<StratumItem> scoped = group.items.stream().filter(item -> selectedScope.contains(item.id)).toList();
                int observed = 0;
                for (StratumItem item : scoped) {
                    if (suppliedProfile ? selectedTradeIds.contains(item.id)
                            : field(field(field(source, "episodes").get(item.index), "candidate_returns"),
                            selectedId).path("traded").asBoolean(false)) observed++;
                }
                double rate = scoped.isEmpty() ? globalRate : (double) observed / scoped.size();
                int allocation = Math.min(group.items.size(), Math.max(0, (int) Math.floor(rate * group.items.size() + .5)));
                int[] order = shuffleIndices(group.items.size(), stratumSeed(seed + iteration, group.key), null);
                for (int position = 0; position < allocation; position++) chosen.add(group.items.get(order[position]).index);
                ObjectNode row = object(); row.put("key", group.key);
                row.set("episode_ids", strings(group.items.stream().map(StratumItem::id).toList()));
                row.put("singleton", group.items.size() == 1); row.put("target_trade_count", allocation);
                row.put("observed_trade_count", observed); row.put("observed_scope_count", scoped.size());
                metadata.add(row);
            }
            ObjectNode forced = object(); ArrayNode intentVector = array();
            for (int index = 0; index < ids.size(); index++) {
                boolean intent = chosen.contains(index); forced.put(ids.get(index), intent);
                ObjectNode row = object(); row.put("episode_id", ids.get(index)); row.put("intent", intent);
                intentVector.add(row);
            }
            ObjectNode result = object(); result.put("target_trade_count", chosen.size());
            result.put("observed_trade_count", observedTotal); result.put("observed_scope_count", selectedScope.size());
            result.put("observed_trade_rate", globalRate);
            List<String> sortedTrades = new ArrayList<>(selectedTradeIds); sortedTrades.sort(String::compareTo);
            result.put("observed_trade_episode_ids_sha256", hash(strings(sortedTrades)));
            result.set("strata", metadata); result.put("strata_inventory_sha256", hash(metadata));
            result.put("intent_vector_sha256", hash(intentVector)); return new FrequencyResult(forced, result);
        }

        private Mapping stratifiedMapping(List<String> ids, String method, long seed,
                int blockLength, ObjectNode source) {
            int[] mapping = new int[ids.size()]; java.util.Arrays.fill(mapping, -1); ArrayNode metadata = array();
            for (Stratum group : strataFor(ids, source)) {
                int size = group.items.size();
                if ("timestamp_shifted_outcomes".equals(method) && size < 2) {
                    throw failure("timestamp shift null cannot preserve a single-episode asset/instrument stratum ("
                            + group.key + ")");
                }
                int[] local; Integer offset = null;
                if ("timestamp_shifted_outcomes".equals(method)) {
                    offset = 1 + new XorShift(stratumSeed(seed, group.key)).nextInt(size - 1);
                    local = new int[size]; for (int index = 0; index < size; index++) local[index] = (index + offset) % size;
                } else local = shuffleIndices(size, stratumSeed(seed, group.key), blockLength);
                ArrayNode mappingRows = array();
                for (int target = 0; target < size; target++) {
                    int sourcePosition = local[target];
                    mapping[group.items.get(target).index] = group.items.get(sourcePosition).index;
                    ObjectNode row = object(); row.put("target_episode_id", group.items.get(target).id);
                    row.put("source_episode_id", group.items.get(sourcePosition).id); mappingRows.add(row);
                }
                ObjectNode row = object(); row.put("key", group.key);
                row.set("episode_ids", strings(group.items.stream().map(StratumItem::id).toList()));
                row.put("singleton", size == 1);
                if ("timestamp_shifted_outcomes".equals(method)) row.putNull("block_length");
                else row.put("block_length", blockLength);
                if (offset == null) row.putNull("offset"); else row.put("offset", offset);
                row.put("mapping_sha256", hash(mappingRows)); metadata.add(row);
            }
            for (int value : mapping) if (value < 0) throw failure("physical null stratified mapping is incomplete");
            return new Mapping(mapping, metadata);
        }

        private List<Stratum> strataFor(List<String> ids, ObjectNode source) {
            Map<String, List<StratumItem>> groups = new TreeMap<>();
            for (int index = 0; index < ids.size(); index++) {
                String id = ids.get(index); ObjectNode episode = objectOrEmpty(field(source, "episodes").get(index));
                ObjectNode feature = featuresById.getOrDefault(id, object()); StringBuilder key = new StringBuilder();
                for (String name : List.of("asset", "venue", "instrument", "symbol", "direction", "side")) {
                    JsonNode value = definedNonNull(field(feature, name)) ? field(feature, name) : field(episode, name);
                    if (!key.isEmpty()) key.append('|'); key.append(name).append('=').append(jsString(value));
                }
                groups.computeIfAbsent(key.toString(), ignored -> new ArrayList<>()).add(new StratumItem(id, index));
            }
            List<Stratum> output = new ArrayList<>();
            groups.forEach((key, items) -> {
                items.sort(Comparator.comparingLong((StratumItem item) ->
                        time(field(field(source, "episodes").get(item.index), "decision_time")))
                        .thenComparing(StratumItem::id));
                output.add(new Stratum(key, List.copyOf(items)));
            });
            return output;
        }

        private ObjectNode transformedLabel(ObjectNode target, ObjectNode source) {
            ObjectNode result = target.deepCopy(); source.fields().forEachRemaining(entry -> {
                if (!PHYSICAL_NULL_IDENTITY_FIELDS.contains(entry.getKey())) {
                    result.set(entry.getKey(), physicalNullTemporalKey(entry.getKey())
                            ? physicalNullRebaseTime(entry.getValue(), field(source, "decision_time"),
                            field(target, "decision_time")) : cloneNode(entry.getValue()));
                }
            }); return result;
        }

        private ObjectNode resume(ObjectNode context, String sourceSha, String method, long seed,
                long iteration, String budgetSha, ObjectNode transformation, Path root,
                String checkpointKey, Path checkpointPath, Path selectionPath) {
            byte[] bytes = physicalBytes(checkpointPath, root, "physical null checkpoint/reference");
            PhysicalRef checkpointRef = new PhysicalRef(checkpointPath, hash(bytes),
                    hash(parseBytes(bytes, "physical null checkpoint/reference")), "null-checkpoint");
            ObjectNode checkpoint = objectOrEmpty(readPhysicalFile(checkpointRef, root));
            boolean invalid = !"strategy-v5-physical-null-checkpoint/1".equals(text(field(checkpoint, "schema")))
                    || integerValue(field(checkpoint, "version"), -1) != 1
                    || !"COMPLETED".equals(text(field(checkpoint, "status")))
                    || !checkpointKey.equals(text(field(checkpoint, "checkpoint_key_sha256")))
                    || !sourceSha.equals(text(field(checkpoint, "source_artifact_sha256")))
                    || !method.equals(text(field(checkpoint, "method")))
                    || numberJs(field(checkpoint, "seed")) != seed
                    || numberJs(field(checkpoint, "iteration")) != iteration
                    || !budgetSha.equals(text(field(checkpoint, "selection_budget_sha256")))
                    || !text(field(transformation, "transformation_sha256")).equals(
                    text(field(checkpoint, "transformation_sha256")))
                    || !selectionPath.toString().equals(text(field(checkpoint, "selection_path")));
            if (invalid) throw failure("physical null iteration checkpoint is stale, competing, or tampered");
            if (!Files.exists(selectionPath, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("physical null iteration checkpoint selection is missing");
            }
            ObjectNode selected = objectOrEmpty(parseBytes(physicalBytes(selectionPath, root,
                    "physical null iteration selection"), "physical null iteration selection"));
            invalid = !"strategy-v5-physical-null-selection/1".equals(text(field(selected, "schema")))
                    || !text(field(selected, "content_sha256")).equals(ownHash(selected))
                    || !checkpointRef.contentSha256.equals(text(field(field(selected, "checkpoint_ref"), "content_sha256")))
                    || !sourceSha.equals(text(field(selected, "source_artifact_sha256")))
                    || !method.equals(text(field(selected, "method")))
                    || numberJs(field(selected, "seed")) != seed
                    || numberJs(field(selected, "iteration")) != iteration
                    || !text(field(transformation, "transformation_sha256")).equals(
                    text(field(selected, "transformation_sha256")));
            if (invalid) throw failure("physical null iteration checkpoint selection is stale or tampered");
            for (String name : List.of("transformed_label_ref", "transformed_execution_ref",
                    "recomputed_outcome_ref", "selected_outcome_vector_ref", "trace_ref")) {
                readPhysicalFile(PhysicalRef.from(field(selected, name)), root);
            }
            return selected;
        }

        private PhysicalRef persist(Path root, String role, JsonNode value) {
            String contentSha = hash(value);
            return writePhysicalFile(root.resolve(role + "-" + contentSha + ".json"), value, role, root);
        }

        private PhysicalRef writePhysicalFile(Path target, JsonNode value, String role, Path root) {
            Path normalized = confinedPhysicalPath(root, target, false); byte[] bytes = appendLf(jsonBytes(value));
            String byteSha = hash(bytes), contentSha = hash(value);
            try {
                Files.createDirectories(normalized.getParent());
                try {
                    Files.write(normalized, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (java.nio.file.FileAlreadyExistsException collision) {
                    byte[] retained = physicalBytes(normalized, root, "physical null artifact");
                    if (!byteSha.equals(hash(retained))) {
                        throw failure("physical null artifact collision: " + role);
                    }
                }
            } catch (IOException error) { throw failure(error.getMessage()); }
            return new PhysicalRef(normalized, byteSha, contentSha, role);
        }

        private JsonNode readPhysicalFile(PhysicalRef reference, Path root) {
            if (reference == null || reference.path == null) {
                throw failure("physical null checkpoint/reference path is missing");
            }
            byte[] bytes = physicalBytes(reference.path, root, "physical null checkpoint/reference");
            if (!reference.byteSha256.equals(hash(bytes))) {
                throw failure("physical null checkpoint/reference bytes are tampered");
            }
            JsonNode value = parseBytes(bytes, "physical null checkpoint/reference");
            if (!reference.contentSha256.equals(hash(value))) {
                throw failure("physical null checkpoint/reference content is tampered");
            }
            return value;
        }

        private byte[] physicalBytes(Path path, Path root, String label) {
            Path normalized = confinedPhysicalPath(root, path, true); requireSingleLinkFile(normalized, label);
            return readBytes(normalized, label);
        }

        private Path confinedPhysicalPath(Path root, Path path, boolean requireFile) {
            Path base = secureCacheRoot(root); Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(base) || normalized.equals(base)) {
                throw failure("physical null checkpoint/reference path escapes its root");
            }
            if (requireFile && !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("physical null checkpoint/reference path is missing");
            }
            return normalized;
        }

        private Path physicalRoot(ObjectNode context) {
            JsonNode requested = field(context, "physical_null_root");
            return secureCacheRoot(truthy(requested) ? Path.of(jsString(requested)) : cacheRoot);
        }

        private ObjectNode roleArtifact(String schema, String sourceSha, String method,
                Iterable<? extends JsonNode> rows) {
            ObjectNode value = object(); value.put("schema", schema);
            value.put("source_artifact_sha256", sourceSha); value.put("method", method);
            ArrayNode physicalRows = array(); rows.forEach(row -> physicalRows.add(cloneNode(row)));
            value.set("rows", physicalRows); return value;
        }

        private static List<ObjectNode> copyRows(List<ObjectNode> rows) {
            return rows.stream().map(ObjectNode::deepCopy).toList();
        }

        private static long exactNonNegativeLong(JsonNode value, String message) {
            if (!value.isNumber()) throw failure(message); double number = value.doubleValue();
            if (!Double.isFinite(number) || number != Math.rint(number) || number < 0 || number > Long.MAX_VALUE) {
                throw failure(message);
            }
            return (long) number;
        }

        private static long integerValue(JsonNode value, long fallback) {
            double number = numberJs(value); return definedNonNull(value) && Double.isFinite(number)
                    && number == Math.rint(number) ? (long) number : fallback;
        }

        private static String numberText(JsonNode value) {
            double number = numberJs(value); return number == Math.rint(number)
                    ? Long.toString((long) number) : Double.toString(number);
        }

        private static String latestTime(ObjectNode source, String key) {
            List<String> values = new ArrayList<>();
            for (JsonNode row : array(field(source, "episodes"))) values.add(jsString(field(row, key)));
            values.sort(String::compareTo); return values.isEmpty() ? "" : values.getLast();
        }

        private static ArrayNode fieldArray(JsonNode parent, String arrayName, String fieldName) {
            ArrayNode values = array();
            for (JsonNode row : array(field(parent, arrayName))) values.add(cloneNode(field(row, fieldName)));
            return values;
        }

        private static List<String> fieldNamesList(JsonNode value) {
            List<String> output = new ArrayList<>();
            if (value != null && value.isObject()) value.fieldNames().forEachRemaining(output::add);
            return output;
        }

        private long stratumSeed(long seed, String key) {
            String digest = hash(numberText(JSON.numberNode(seed)) + ":" + key);
            long parsed = Long.parseUnsignedLong(digest.substring(0, 8), 16); return parsed == 0 ? 1 : parsed;
        }

        private static int[] shuffleIndices(int length, long seed, Integer blockLength) {
            XorShift random = new XorShift(seed); int[] values = new int[length];
            for (int index = 0; index < length; index++) values[index] = index;
            if (blockLength == null) {
                for (int index = length - 1; index > 0; index--) {
                    int swap = random.nextInt(index + 1); int retained = values[index];
                    values[index] = values[swap]; values[swap] = retained;
                }
                return values;
            }
            List<int[]> blocks = new ArrayList<>();
            for (int index = 0; index < length; index += blockLength) {
                blocks.add(java.util.Arrays.copyOfRange(values, index, Math.min(length, index + blockLength)));
            }
            for (int index = blocks.size() - 1; index > 0; index--) {
                int swap = random.nextInt(index + 1); int[] retained = blocks.get(index);
                blocks.set(index, blocks.get(swap)); blocks.set(swap, retained);
            }
            int cursor = 0; for (int[] block : blocks) for (int value : block) values[cursor++] = value;
            return values;
        }

        private static final class XorShift {
            private int state;
            XorShift(long seed) { state = (int) seed; if (state == 0) state = 1; }
            double next() { state ^= state << 13; state ^= state >>> 17; state ^= state << 5;
                return Integer.toUnsignedLong(state) / 4_294_967_296d; }
            int nextInt(int max) { return Math.min(max - 1, (int) Math.floor(next() * max)); }
        }

        private record Mapping(int[] mapping, ArrayNode metadata) {}
        private record StratumItem(String id, int index) {}
        private record Stratum(String key, List<StratumItem> items) {}
        private record FrequencyResult(ObjectNode forcedIntents, ObjectNode transformation) {}
        private record SelectionResult(ArrayNode vector, ObjectNode trace) {}
        private record PhysicalRef(Path path, String byteSha256, String contentSha256, String role) {
            ObjectNode toJson() { ObjectNode value = object(); value.put("path", path.toString());
                value.put("byte_sha256", byteSha256); value.put("content_sha256", contentSha256);
                value.put("role", role); return value; }
            static PhysicalRef from(JsonNode value) {
                if (!value.isObject() || !truthy(field(value, "path"))) {
                    throw failure("physical null checkpoint/reference path is missing");
                }
                return new PhysicalRef(Path.of(text(field(value, "path"))), text(field(value, "byte_sha256")),
                        text(field(value, "content_sha256")), text(field(value, "role")));
            }
        }
    }

    private record PendingTask(ObjectNode args, String key, int index, long ordinal) {
        PendingTask withOrdinal(long value) { return new PendingTask(args, key, index, value); }
    }

    private record Outcome(String episodeId, double entryPrice, double quantity, double contractMultiplier,
            double feesUsd, double slippageUsd, double capacityDebitUsd, double fundingPnlUsd,
            double riskAmountUsd, double netR) {
        Outcome withEpisodeId(String value) {
            return new Outcome(value, entryPrice, quantity, contractMultiplier, feesUsd, slippageUsd,
                    capacityDebitUsd, fundingPnlUsd, riskAmountUsd, netR);
        }
    }

    private record FundingSettlement(String eventId, long settlementTime, double fundingRate,
            double settlementMark, double pnlUsd) {}

    private static Outcome deriveBoundExecutionOutcome(ObjectNode feature, ObjectNode label,
            ObjectNode execution, ObjectNode candidate, JsonNode envelopeWindow, JsonNode metadata,
            ObjectNode evaluatorSpec, boolean fixtureOnly, LifecycleTrustService.Token lifecycleToken) {
        long decision = validateDirectOutcomeIdentities(feature, label, execution);
        String convention = jsString(firstTruthy(field(candidate, "decision_timestamp_convention"),
                field(execution, "decision_timestamp_convention"), field(label, "decision_timestamp_convention")))
                .toUpperCase(Locale.ROOT);
        if (!"COMPLETED_4H_BOUNDARY".equals(convention)) {
            throw failure("execution decision timestamp convention is not explicitly bound to COMPLETED_4H_BOUNDARY");
        }
        String timeframe = jsString(firstTruthy(field(candidate, "decision_timeframe"),
                field(execution, "decision_timeframe"), field(label, "decision_timeframe")))
                .toLowerCase(Locale.ROOT);
        if (!"4h".equals(timeframe) || decision % FOUR_HOURS != 0) {
            throw failure("decision time is not the exact completed 4h boundary");
        }
        if (canonicalLifecycle(candidate, execution)) {
            return deriveNormalizedLifecycleOutcome(feature, label, execution, candidate, envelopeWindow,
                    evaluatorSpec, fixtureOnly, lifecycleToken);
        }
        for (String name : List.of("funding_settlements", "funding_debit", "funding_pnl_usd", "funding_amount")) {
            if (present(execution, name)) throw failure("caller-supplied " + name + " is not an authoritative funding input");
        }
        List<ObjectNode> bars = new ArrayList<>();
        for (JsonNode raw : requireArray(field(execution, "child_bars"), "execution child bars")) {
            ObjectNode bar = objectOrEmpty(raw).deepCopy();
            bar.put("_t", time(firstDefined(field(bar, "event_time"), field(bar, "time"), field(bar, "open_time"))));
            for (String key : List.of("open", "high", "low", "close")) bar.put(key, numberJs(field(bar, key)));
            bars.add(bar);
        }
        bars.sort(Comparator.comparingLong(row -> row.path("_t").asLong()));
        Set<Long> times = new HashSet<>();
        for (int index = 0; index < bars.size(); index++) {
            long current = bars.get(index).path("_t").asLong();
            if (!times.add(current) || (index > 0 && current != bars.get(index - 1).path("_t").asLong() + ONE_MINUTE)) {
                throw failure("execution path is not dense one-minute data");
            }
            if (rowAvailability(bars.get(index)) < current + ONE_MINUTE - 1_000) {
                throw failure("execution path contains a bar available before close");
            }
        }
        String entryPolicy = jsString(firstTruthy(field(candidate, "entry_policy"), field(execution, "entry_policy"),
                field(label, "entry_policy"), JSON.textNode("NEXT_BAR_OPEN"))).toUpperCase(Locale.ROOT);
        double rawEntryDelay = "DELAYED_BAR_OPEN".equals(entryPolicy)
                ? numberJs(firstDefinedNonNull(field(candidate, "entry_delay_bars"),
                field(execution, "entry_delay_bars"), field(label, "entry_delay_bars"))) : 0;
        if ("DELAYED_BAR_OPEN".equals(entryPolicy)
                && (!Double.isFinite(rawEntryDelay) || rawEntryDelay != Math.rint(rawEntryDelay)
                || rawEntryDelay < 1 || rawEntryDelay > Integer.MAX_VALUE)) {
            throw failure("delayed-bar entry policy requires a positive frozen entry_delay_bars");
        }
        int entryDelay = (int) rawEntryDelay;
        if (!Set.of("NEXT_BAR_OPEN", "DELAYED_BAR_OPEN").contains(entryPolicy)) {
            throw failure("unsupported frozen entry policy " + entryPolicy);
        }
        long entryTime = decision + entryDelay * ONE_MINUTE;
        ObjectNode entry = bars.stream().filter(row -> row.path("_t").asLong() >= entryTime).findFirst().orElse(null);
        if (entry == null || entry.path("_t").asLong() != entryTime || !(entry.path("open").asDouble() > 0)) {
            throw failure("execution path lacks the exact contiguous next-bar entry");
        }
        if (defined(field(label, "entry_time")) && time(field(label, "entry_time")) != entryTime) {
            throw failure("label entry time does not match frozen next-bar policy");
        }
        long ceiling = time(firstDefinedNonNull(field(label, "resolution_ceiling_time"), field(label, "resolution_time"),
                field(label, "outcome_time"), field(label, "exit_time")));
        if (!(ceiling > entryTime)) throw failure("label outcome ceiling is invalid");
        JsonNode lifecycleTimeframe = firstTruthy(field(candidate, "lifecycle_timeframe"),
                field(execution, "lifecycle_timeframe"), field(label, "lifecycle_timeframe"));
        if (!truthy(lifecycleTimeframe)) throw failure("lifecycle timeframe is required");
        long lifecycleStep = timeframeMilliseconds(jsString(lifecycleTimeframe));
        JsonNode explicitMax = firstDefinedNonNull(field(candidate, "max_lifecycle_ms"), field(execution, "max_lifecycle_ms"),
                field(label, "max_lifecycle_ms"));
        JsonNode legacyBars = firstDefinedNonNull(field(candidate, "max_lifecycle_bars"), field(execution, "max_lifecycle_bars"),
                field(label, "max_lifecycle_bars"));
        double rawMax = definedNonNull(explicitMax) ? numberJs(explicitMax)
                : (definedNonNull(legacyBars) ? numberJs(legacyBars) * lifecycleStep : Double.NaN);
        if (!Double.isFinite(rawMax) || rawMax != Math.rint(rawMax) || rawMax <= 0) {
            throw failure("maximum lifecycle must be explicitly bound in milliseconds");
        }
        if (definedNonNull(legacyBars)) {
            double rawLegacyBars = numberJs(legacyBars);
            if (!Double.isFinite(rawLegacyBars) || rawLegacyBars != Math.rint(rawLegacyBars)
                    || rawLegacyBars <= 0) throw failure("maximum lifecycle bars is invalid");
        }
        long lifecycleEnd = Math.min(ceiling, entryTime + (long) rawMax);
        if (!(lifecycleEnd > entryTime)) throw failure("maximum lifecycle ends before entry");
        String instrument = jsString(firstTruthy(field(execution, "instrument"), field(label, "instrument"),
                "spot".equalsIgnoreCase(text(field(execution, "instrument_type")))
                        ? JSON.textNode("BINANCE_SPOT") : JSON.textNode("BINANCE_USDM_PERPETUAL")))
                .toUpperCase(Locale.ROOT);
        if (!Set.of("BINANCE_SPOT", "BINANCE_USDM_PERPETUAL", "BINANCE_USDM_DATED_FUTURE").contains(instrument)) {
            throw failure("unsupported execution instrument " + instrument);
        }
        String asset = text(field(feature, "asset")).toLowerCase(Locale.ROOT);
        String venue = jsString(firstTruthy(field(execution, "venue"), field(label, "venue"), field(feature, "venue"),
                JSON.textNode("BINANCE"))).toUpperCase(Locale.ROOT);
        String symbol = jsString(firstTruthy(field(execution, "symbol"), field(label, "symbol"), field(feature, "symbol"),
                JSON.textNode(asset.toUpperCase(Locale.ROOT) + "USDT"))).toUpperCase(Locale.ROOT);
        String direction = jsString(firstTruthy(field(candidate, "direction"), field(execution, "direction"),
                field(label, "direction"), JSON.textNode("long"))).toLowerCase(Locale.ROOT);
        if (!Set.of("long", "short").contains(direction)) throw failure("execution direction is invalid");
        if ("BINANCE_SPOT".equals(instrument) && "short".equals(direction)) {
            throw failure("short BINANCE_SPOT execution is not supported; bind a derivative instrument");
        }
        boolean derivative = !"BINANCE_SPOT".equals(instrument);
        List<ObjectNode> markBars = new ArrayList<>();
        if (derivative) {
            for (JsonNode raw : requireArray(
                    field(execution, "mark_bars"), "separately bound derivative mark bars")) {
                ObjectNode mark = objectOrEmpty(raw).deepCopy();
                mark.put("_t", time(firstDefinedNonNull(
                        field(mark, "event_time"), field(mark, "time"), field(mark, "open_time"))));
                for (String key : List.of("mark_open", "mark_high", "mark_low", "mark_close")) {
                    mark.put(key, numberJs(field(mark, key)));
                }
                markBars.add(mark);
            }
            markBars.sort(Comparator.comparingLong(row -> row.path("_t").asLong()));
            if (markBars.size() != bars.size()) {
                throw failure("derivative execution requires a separate dense mark-price path aligned to trade bars");
            }
            for (int index = 0; index < markBars.size(); index++) {
                ObjectNode mark = markBars.get(index);
                if (mark.path("_t").asLong() != bars.get(index).path("_t").asLong()
                        || !(mark.path("mark_high").asDouble() > 0)
                        || !(mark.path("mark_low").asDouble() > 0)
                        || mark.path("mark_low").asDouble() > mark.path("mark_high").asDouble()) {
                    throw failure("derivative execution requires a separate dense mark-price path aligned to trade bars");
                }
                if (rowAvailability(mark) < mark.path("_t").asLong() + ONE_MINUTE - 1_000) {
                    throw failure("derivative mark path contains a bar available before close");
                }
            }
        }
        JsonNode exitPolicy = firstTruthy(field(candidate, "exit_policy"), field(execution, "exit_policy"),
                MAPPER.valueToTree(Map.of("type", "TIME_STOP")));
        String policyType = text(field(exitPolicy, "type")).toUpperCase(Locale.ROOT);
        String collision = truthy(field(exitPolicy, "collision_policy"))
                ? jsString(field(exitPolicy, "collision_policy")).toUpperCase(Locale.ROOT) : "ADVERSE_STOP_FIRST";
        if (present(exitPolicy, "partial") || present(exitPolicy, "partials") || present(exitPolicy, "ratchet")
                || present(candidate, "partial") || present(candidate, "partials") || present(candidate, "ratchet")) {
            throw failure("partial and ratchet exits require an explicitly bound execution implementation");
        }
        ObjectNode contractReceipt = boundMetadata(field(metadata, "contract_spec"), "CONTRACT_SPEC", fixtureOnly);
        ObjectNode contract = metadataRecord(contractReceipt, asset, instrument, venue, symbol, entryTime);
        double multiplier = numberJs(field(contract, "contract_multiplier"));
        if (!(multiplier > 0)) throw failure("contract multiplier is invalid");
        JsonNode contractExpiry = firstDefinedNonNull(field(contract, "expiry"), field(contract, "delivery_date"));
        if (definedNonNull(contractExpiry) && ceiling > time(contractExpiry)) {
            throw failure("execution path extends beyond contract expiry");
        }
        if ("BINANCE_USDM_DATED_FUTURE".equals(instrument)) {
            ObjectNode expiryReceipt = boundMetadata(field(metadata, "expiry"), "EXPIRY", fixtureOnly);
            ObjectNode expiry = metadataRecord(expiryReceipt, asset, instrument, venue, symbol, entryTime);
            long expiryTime = time(firstDefinedNonNull(field(expiry, "expiry"), field(expiry, "delivery_date")));
            if (ceiling > expiryTime) {
                throw failure("dated future execution path extends beyond bound settlement expiry");
            }
        }
        ObjectNode modelReceipt = boundMetadata(
                field(metadata, "execution_model"), "EXECUTION_MODEL", fixtureOnly, true);
        ObjectNode model = metadataRecord(modelReceipt, asset, instrument, venue, symbol, entryTime);
        double slippageBps = numberJs(field(model, "slippage_bps"));
        double impactBps = numberJs(field(model, "impact_bps"));
        String outagePolicy = text(field(model, "outage_policy")).toUpperCase(Locale.ROOT);
        String gapPolicy = text(field(model, "gap_policy")).toUpperCase(Locale.ROOT);
        if (!Double.isFinite(slippageBps) || !Double.isFinite(impactBps) || slippageBps < 0 || impactBps < 0) {
            throw failure("execution slippage/impact model is invalid");
        }
        if (!"FAIL".equals(outagePolicy)) throw failure("unsupported outage policy " + (outagePolicy.isEmpty() ? "?" : outagePolicy));
        if (!Set.of("FAIL", "FILL_AT_OPEN").contains(gapPolicy)) {
            throw failure("unsupported gap policy " + (gapPolicy.isEmpty() ? "?" : gapPolicy));
        }
        double rawExitPrice = Double.NaN;
        long resolution = lifecycleEnd;
        if ("TARGET_STOP".equals(policyType)) {
            double stop = numberJs(field(exitPolicy, "stop_price"));
            double target = numberJs(field(exitPolicy, "target_price"));
            if (!(stop > 0) || !(target > 0)) throw failure("target/stop exit policy is invalid");
            if (!"ADVERSE_STOP_FIRST".equals(collision)) {
                throw failure("only ADVERSE_STOP_FIRST OHLC collision policy is supported");
            }
            for (ObjectNode bar : bars) {
                long t = bar.path("_t").asLong();
                if (t < entryTime || t > lifecycleEnd) continue;
                boolean isLong = "long".equals(direction);
                boolean hitStop = isLong ? bar.path("low").asDouble() <= stop : bar.path("high").asDouble() >= stop;
                boolean hitTarget = isLong ? bar.path("high").asDouble() >= target : bar.path("low").asDouble() <= target;
                if (!hitStop && !hitTarget) continue;
                boolean gapStop = isLong ? bar.path("open").asDouble() <= stop : bar.path("open").asDouble() >= stop;
                boolean gapTarget = isLong ? bar.path("open").asDouble() >= target : bar.path("open").asDouble() <= target;
                boolean stopFirst = hitStop;
                if ((gapStop || gapTarget) && "FAIL".equals(gapPolicy)) {
                    throw failure("execution path contains a gap through a target/stop under FAIL gap policy");
                }
                resolution = t;
                rawExitPrice = gapStop || gapTarget ? bar.path("open").asDouble() : (stopFirst ? stop : target);
                break;
            }
        } else if (!"TIME_STOP".equals(policyType)) {
            throw failure("unsupported exit policy " + policyType);
        }
        if (truthy(envelopeWindow)) {
            if (entryTime < time(field(envelopeWindow, "execution_start"))
                    || resolution > time(field(envelopeWindow, "execution_end"))) {
                throw failure("outcome path escapes frozen opportunity envelope");
            }
        }
        long expectedStart = "DELAYED_BAR_OPEN".equals(entryPolicy) ? decision : entryTime;
        if (bars.isEmpty() || bars.get(0).path("_t").asLong() != expectedStart
                || bars.get(bars.size() - 1).path("_t").asLong() < resolution) {
            throw failure("execution path is truncated or contains pre-entry bars before the declared lifecycle/resolution");
        }
        long resolvedExitTime = resolution;
        ObjectNode exit = bars.stream().filter(row -> row.path("_t").asLong() == resolvedExitTime)
                .findFirst().orElse(null);
        if (exit == null || !(exit.path("close").asDouble() > 0)) {
            throw failure("execution path lacks exact policy resolution bar");
        }
        if (!Double.isFinite(rawExitPrice)) rawExitPrice = exit.path("close").asDouble();
        double modeledEntryPrice = "long".equals(direction)
                ? entry.path("open").asDouble() * (1 + (slippageBps + impactBps) / 10_000)
                : entry.path("open").asDouble() * (1 - (slippageBps + impactBps) / 10_000);
        double quantity;
        JsonNode suppliedQuantity = firstDefinedNonNull(field(execution, "quantity"), field(label, "quantity"));
        JsonNode riskContract = firstTruthy(field(candidate, "risk_contract"), field(execution, "risk_contract"));
        JsonNode sizingContract = firstTruthy(field(candidate, "sizing_contract"), field(riskContract, "sizing_contract"));
        if (definedNonNull(suppliedQuantity)) {
            if (!fixtureOnly) throw failure("caller-supplied execution/label quantity is not authoritative");
            quantity = numberJs(suppliedQuantity);
            if (!Double.isFinite(quantity) || !(Math.abs(quantity) > 0)) throw failure("execution quantity is invalid");
        } else {
            if (!truthy(riskContract) || !HASH_RE.matcher(text(field(riskContract, "precommit_sha256"))).matches()
                    || !HASH_RE.matcher(text(field(riskContract, "evaluator_spec_sha256"))).matches()) {
                throw failure("authoritative execution requires a hash-bound sizing contract");
            }
            if ("TARGET_STOP".equals(policyType)) {
                if (!"FIXED_RISK_BUDGET_USD".equals(text(field(riskContract, "mode")))) {
                    throw failure("target-stop sizing requires a fixed-risk-budget contract");
                }
                double budget = numberJs(field(riskContract, "budget_usd"));
                double stop = numberJs(field(exitPolicy, "stop_price"));
                double stopDistance = Math.abs(modeledEntryPrice - stop);
                if (!(budget > 0) || !Double.isFinite(budget) || !(stopDistance > 0)) {
                    throw failure("target-stop sizing contract or stop distance is invalid");
                }
                quantity = budget / (stopDistance * multiplier);
            } else {
                if (!"FIXED_NOTIONAL_USD".equals(text(field(sizingContract, "mode")))
                        || !HASH_RE.matcher(text(field(sizingContract, "precommit_sha256"))).matches()
                        || !HASH_RE.matcher(text(field(sizingContract, "evaluator_spec_sha256"))).matches()) {
                    throw failure("time-stop sizing requires an explicit fixed-notional contract");
                }
                double notional = numberJs(field(sizingContract, "notional_usd"));
                if (!(notional > 0) || !Double.isFinite(notional)) {
                    throw failure("fixed-notional sizing contract is invalid");
                }
                quantity = notional / (entry.path("open").asDouble() * multiplier);
            }
            quantity = enforceQuantityTerms(quantity, modeledEntryPrice, entry.path("open").asDouble(), multiplier,
                    sizingContract, contract);
        }
        double signedQuantity = "short".equals(direction) ? -Math.abs(quantity) : Math.abs(quantity);
        ObjectNode feeReceipt = boundMetadata(field(metadata, "fee_schedule"), "FEE_SCHEDULE", fixtureOnly);
        ObjectNode feeEntry = metadataRecord(feeReceipt, asset, instrument, venue, symbol, entryTime);
        ObjectNode feeExit = metadataRecord(feeReceipt, asset, instrument, venue, symbol, resolution);
        double feeRateEntry = numberJs(field(feeEntry, "taker_fee_rate"));
        double feeRateExit = numberJs(field(feeExit, "taker_fee_rate"));
        if (feeRateEntry < 0 || feeRateExit < 0 || !Double.isFinite(feeRateEntry) || !Double.isFinite(feeRateExit)) {
            throw failure("effective fee schedule rates are invalid");
        }
        double exitPrice = "long".equals(direction) ? rawExitPrice * (1 - (slippageBps + impactBps) / 10_000)
                : rawExitPrice * (1 + (slippageBps + impactBps) / 10_000);
        double quantityNotional = Math.abs(signedQuantity) * multiplier;
        double rawEntryNotional = entry.path("open").asDouble() * quantityNotional;
        double rawExitNotional = rawExitPrice * quantityNotional;
        double entryNotional = modeledEntryPrice * quantityNotional;
        double exitNotional = exitPrice * quantityNotional;
        double fees = entryNotional * feeRateEntry + exitNotional * feeRateExit;
        double slippage = (rawEntryNotional + rawExitNotional) * slippageBps / 10_000;
        double capacityDebit = (rawEntryNotional + rawExitNotional) * impactBps / 10_000;
        double gross = ("short".equals(direction) ? entry.path("open").asDouble() - rawExitPrice
                : rawExitPrice - entry.path("open").asDouble()) * quantityNotional;
        List<FundingSettlement> fundingSettlements = new ArrayList<>();
        double funding = 0;
        if ("BINANCE_USDM_PERPETUAL".equals(instrument)) {
            ObjectNode fundingReceipt = boundMetadata(
                    field(metadata, "funding_identity"), "FUNDING_IDENTITY", fixtureOnly);
            if (!field(field(fundingReceipt, "coverage"), "complete").asBoolean(false)) {
                throw failure("perpetual derivative funding coverage is incomplete");
            }
            for (JsonNode row : array(field(fundingReceipt, "records"))) {
                long settlement = time(firstDefinedNonNull(
                        field(row, "settlement_slot"), field(row, "event_time")));
                if (!asset.equalsIgnoreCase(text(field(row, "asset")))
                        || !venue.equalsIgnoreCase(text(field(row, "venue")))
                        || !instrument.equalsIgnoreCase(text(field(row, "instrument")))
                        || !symbol.equalsIgnoreCase(text(field(row, "symbol")))
                        || settlement <= entryTime || settlement > resolution
                        || time(field(row, "availability_time")) > resolution) continue;
                double mark = numberJs(firstDefinedNonNull(
                        field(row, "settlement_mark"), field(row, "mark_price")));
                if (!Double.isFinite(mark)) {
                    String eventId = truthy(field(row, "event_id")) ? text(field(row, "event_id")) : "?";
                    throw failure("funding event " + eventId + " has no settlement mark");
                }
                double rate = numberJs(field(row, "funding_rate"));
                double pnl = computeFundingPnl(rate, mark, signedQuantity, multiplier);
                funding += pnl;
                fundingSettlements.add(new FundingSettlement(
                        text(field(row, "event_id")), settlement, rate, mark, pnl));
            }
            validateFundingLifecycleSlots(fundingReceipt, entryTime, resolution, fundingSettlements);
        } else if ("BINANCE_USDM_DATED_FUTURE".equals(instrument)) {
            JsonNode fundingReceipt = field(metadata, "funding_identity");
            String status = text(field(fundingReceipt, "status"));
            boolean notApplicable = "NOT_APPLICABLE".equals(status);
            if (!notApplicable && "UNAVAILABLE".equals(status)) {
                for (JsonNode limitation : array(field(fundingReceipt, "limitations"))) {
                    if (jsString(limitation).toUpperCase(Locale.ROOT).contains("NOT_APPLICABLE")) {
                        notApplicable = true;
                        break;
                    }
                }
            }
            if (!notApplicable) {
                throw failure("dated futures must declare funding as NOT_APPLICABLE; periodic funding is not accepted");
            }
        }

        if (derivative) {
            ObjectNode marginReceipt = boundMetadata(field(metadata, "margin"), "MARGIN", fixtureOnly);
            ObjectNode margin = metadataRecord(marginReceipt, asset, instrument, venue, symbol, entryTime);
            if (truthy(field(metadata, "liquidation"))) {
                ObjectNode liquidationReceipt = boundMetadata(
                        field(metadata, "liquidation"), "LIQUIDATION", fixtureOnly);
                metadataRecord(liquidationReceipt, asset, instrument, venue, symbol, entryTime);
                throw failure("static liquidation metadata is stress-only; base liquidation must be derived from bound entry, margin, fees, funding, and marks");
            }
            JsonNode derivativePolicy = field(candidate, "derivative_policy");
            String marginMode = fixtureOnly
                    ? jsString(firstTruthy(field(execution, "margin_mode"), field(derivativePolicy, "margin_mode")))
                    : text(field(derivativePolicy, "margin_mode"));
            double leverage = fixtureOnly
                    ? numberJs(firstDefinedNonNull(field(execution, "leverage"), field(derivativePolicy, "leverage")))
                    : numberJs(field(derivativePolicy, "leverage"));
            String tierId = fixtureOnly
                    ? jsString(firstTruthy(field(execution, "tier_id"), field(execution, "margin_tier_id"),
                    field(derivativePolicy, "tier_id"), field(margin, "tier_id")))
                    : jsString(firstTruthy(field(derivativePolicy, "tier_id"), field(margin, "tier_id")));
            double maintenanceRate = numberJs(field(margin, "maintenance_margin_ratio"));
            if (!truthy(JSON.textNode(marginMode)) || !truthy(field(margin, "margin_mode"))
                    || !marginMode.equalsIgnoreCase(text(field(margin, "margin_mode"))) || tierId.isBlank()) {
                throw failure("derivative margin mode/tier is not bound");
            }
            double maxLeverage = numberJs(firstDefinedNonNull(
                    field(contract, "max_leverage"), field(margin, "max_leverage"), JSON.numberNode(leverage)));
            if (!(leverage > 0) || !(maxLeverage > 0) || leverage > maxLeverage) {
                throw failure("derivative leverage exceeds the bound contract tier");
            }
            double collateralBuffer = Math.max(0, numberJs(firstDefinedNonNull(
                    field(derivativePolicy, "collateral_buffer_fraction"), JSON.numberNode(0))));
            JsonNode rawCollateral = firstDefinedNonNull(
                    field(execution, "collateral_usd"), field(execution, "collateral"));
            double collateral = fixtureOnly && definedNonNull(rawCollateral)
                    ? numberJs(rawCollateral) : entryNotional / leverage * (1 + collateralBuffer);
            if (!(collateral > 0) || !(maintenanceRate > 0) || !(leverage > 0)
                    || collateral < entryNotional / leverage) {
                throw failure("derivative collateral, maintenance margin, leverage, or notional is invalid");
            }
            for (int index = 0; index < bars.size(); index++) {
                ObjectNode bar = bars.get(index);
                long at = bar.path("_t").asLong();
                if (at < entryTime || at > resolution) continue;
                ObjectNode mark = markBars.get(index);
                double markHigh = mark.path("mark_high").asDouble();
                double markLow = mark.path("mark_low").asDouble();
                double adverseMark = "short".equals(direction) ? markHigh : markLow;
                if (!(adverseMark > 0) || !(markHigh > 0) || !(markLow > 0) || markLow > markHigh) {
                    throw failure("derivative execution bar lacks a positive bound mark range");
                }
                double markPnl = ("short".equals(direction)
                        ? modeledEntryPrice - adverseMark : adverseMark - modeledEntryPrice)
                        * Math.abs(signedQuantity) * multiplier;
                double settledFunding = fundingSettlements.stream()
                        .filter(row -> row.settlementTime <= at).mapToDouble(FundingSettlement::pnlUsd).sum();
                double equity = collateral - modeledEntryPrice * Math.abs(signedQuantity)
                        * multiplier * feeRateEntry + markPnl + settledFunding;
                double maintenance = adverseMark * Math.abs(signedQuantity) * multiplier * maintenanceRate;
                if (equity <= maintenance) {
                    throw failure("execution path breaches dynamically derived maintenance margin/liquidation boundary");
                }
            }
        }

        double net = gross - fees - slippage - capacityDebit + funding;
        double risk;
        Double suppliedCandidateRisk = definedNonNull(field(candidate, "risk_amount_usd"))
                ? numberJs(field(candidate, "risk_amount_usd")) : null;
        Double suppliedExecutionRisk = definedNonNull(field(execution, "risk_amount_usd"))
                ? numberJs(field(execution, "risk_amount_usd")) : null;
        if ("TARGET_STOP".equals(policyType)) {
            risk = Math.abs(modeledEntryPrice - numberJs(field(exitPolicy, "stop_price")))
                    * Math.abs(signedQuantity) * multiplier;
            if (!(risk > 0)) throw failure("derived stop-distance risk amount is invalid");
            validateSuppliedRisk(suppliedCandidateRisk, risk,
                    "caller-supplied risk amount does not match the authoritative stop-distance denominator");
            validateSuppliedRisk(suppliedExecutionRisk, risk,
                    "caller-supplied risk amount does not match the authoritative stop-distance denominator");
        } else {
            if (!"FIXED_RISK_BUDGET_USD".equals(text(field(riskContract, "mode")))
                    || !HASH_RE.matcher(text(field(riskContract, "precommit_sha256"))).matches()
                    || !HASH_RE.matcher(text(field(riskContract, "evaluator_spec_sha256"))).matches()) {
                throw failure("time-stop risk requires a precommitted fixed-risk-budget evaluator contract");
            }
            risk = numberJs(field(riskContract, "budget_usd"));
            if (!(risk > 0) || !Double.isFinite(risk)) {
                throw failure("fixed-risk-budget denominator is invalid");
            }
            JsonNode frozenRisk = field(field(evaluatorSpec, "execution_contract"), "risk_convention");
            if (truthy(evaluatorSpec) && (!text(field(riskContract, "precommit_sha256"))
                    .equals(text(field(evaluatorSpec, "precommit_sha256")))
                    || !text(field(riskContract, "evaluator_spec_sha256"))
                    .equals(text(field(evaluatorSpec, "content_sha256")))
                    || !"FIXED_RISK_BUDGET_USD".equals(text(field(frozenRisk, "mode")))
                    || numberJs(field(frozenRisk, "budget_usd")) != risk)) {
                throw failure("fixed-risk-budget contract is not bound to the verified evaluator spec");
            }
            validateSuppliedRisk(suppliedCandidateRisk, risk,
                    "caller-supplied risk amount disagrees with the frozen fixed-risk budget");
            validateSuppliedRisk(suppliedExecutionRisk, risk,
                    "caller-supplied risk amount disagrees with the frozen fixed-risk budget");
        }
        return new Outcome(null, modeledEntryPrice, Math.abs(quantity), multiplier, fees, slippage,
                capacityDebit, funding, risk, net / risk);
    }

    private static Outcome deriveNormalizedLifecycleOutcome(ObjectNode feature, ObjectNode label,
            ObjectNode execution, ObjectNode candidate, JsonNode envelopeWindow, ObjectNode evaluatorSpec,
            boolean fixtureOnly, LifecycleTrustService.Token lifecycleToken) {
        JsonNode lifecycle = firstTruthy(field(candidate, "lifecycle"), field(candidate, "lifecycle_spec"),
                field(execution, "lifecycle"), field(execution, "lifecycle_spec"));
        if (!truthy(lifecycle)) throw failure("normalized lifecycle specification is missing");
        ObjectNode intent = candidate.deepCopy();
        intent.put("fixtureOnly", fixtureOnly);
        intent.set("direction", firstTruthy(field(candidate, "direction"), field(execution, "direction"),
                field(label, "direction"), JSON.textNode("long")));
        intent.set("instrument_type", firstTruthy(field(candidate, "instrument_type"), field(execution, "instrument_type"),
                field(label, "instrument_type"), field(execution, "instrument"), JSON.textNode("spot")));
        intent.set("decision_time", firstTruthy(field(candidate, "decision_time"), field(execution, "decision_time"),
                field(feature, "decision_time")));
        intent.set("lifecycle", cloneNode(lifecycle));
        ObjectNode request = object();
        request.set("intent", intent);
        request.set("bars", cloneNode(field(execution, "child_bars")));
        request.set("funding", cloneNode(firstTruthy(field(execution, "funding_rows"), field(execution, "funding_events"), array())));
        request.set("marks", cloneNode(firstTruthy(field(execution, "mark_bars"), array())));
        request.put("interval_ms", defined(field(execution, "interval_ms")) ? numberJs(field(execution, "interval_ms")) : ONE_MINUTE);
        request.set("execution", execution.deepCopy());
        if (!fixtureOnly && lifecycleToken == null) {
            throw failure("authoritative normalized lifecycle lacks the verified evaluator binding");
        }
        ObjectNode result = fixtureOnly
                ? LIFECYCLE_ENGINE.normalizeTradeLifecycleV5(request)
                : LIFECYCLE_ENGINE.normalizeTradeLifecycleV5(request, lifecycleToken);
        if (truthy(envelopeWindow) && (time(field(result, "entry_time")) < time(field(envelopeWindow, "execution_start"))
                || time(field(result, "lifecycle_end_exclusive")) > time(field(envelopeWindow, "execution_end")))) {
            throw failure("normalized lifecycle path escapes frozen opportunity envelope");
        }
        JsonNode riskContract = field(candidate, "risk_contract");
        double multiplier = numberJs(field(result, "contract_multiplier"));
        double risk = truthy(riskContract) ? numberJs(field(riskContract, "budget_usd"))
                : numberJs(firstTruthy(field(field(lifecycle, "sizing"), "risk_usd"),
                field(field(lifecycle, "sizing"), "budget_usd"), field(candidate, "risk_amount_usd")));
        if (!(risk > 0) || !Double.isFinite(risk)) throw failure("normalized lifecycle risk denominator is invalid");
        return new Outcome(null, numberJs(field(result, "entry_price")), numberJs(field(result, "quantity")),
                multiplier, numberJs(field(result, "fees_usd")), numberJs(field(result, "slippage_usd")),
                numberJs(field(result, "capacity_debit_usd")), numberJs(field(result, "funding_usd")), risk,
                numberJs(field(result, "net_pnl_usd")) / risk);
    }

    private static void validateSuppliedRisk(Double supplied, double expected, String message) {
        if (supplied == null) return;
        if (!Double.isFinite(supplied)
                || Math.abs(supplied - expected) > Math.max(1e-9, expected * 1e-9)) {
            throw failure(message);
        }
    }

    private static double computeFundingPnl(
            double fundingRate, double settlementMark, double signedQuantity, double contractMultiplier) {
        if (!Double.isFinite(fundingRate) || !Double.isFinite(settlementMark)
                || !Double.isFinite(signedQuantity) || !Double.isFinite(contractMultiplier)
                || !(settlementMark > 0) || !(contractMultiplier > 0)) {
            throw failure("funding PnL requires finite rate/mark/position/contract terms");
        }
        return -(signedQuantity * settlementMark * contractMultiplier * fundingRate);
    }

    private static void validateFundingLifecycleSlots(ObjectNode receipt, long entryTime, long exitTime,
            List<FundingSettlement> rows) {
        Set<String> eventIds = new HashSet<>();
        for (FundingSettlement row : rows) {
            if (row.eventId == null || row.eventId.isBlank() || !eventIds.add(row.eventId)) {
                throw failure("derivative funding lifecycle has missing or duplicate event identities");
            }
        }
        JsonNode coverage = field(receipt, "coverage");
        if ("EVENT_SEQUENCE".equals(text(field(coverage, "coverage_mode")))) {
            Set<Long> observed = new HashSet<>();
            for (FundingSettlement row : rows) {
                if (!observed.add(row.settlementTime)
                        || row.settlementTime <= entryTime || row.settlementTime > exitTime) {
                    throw failure("derivative funding lifecycle has missing, extra, or duplicate event identities");
                }
            }
            return;
        }
        JsonNode rawSegments = field(coverage, "cadence_segments");
        if (!rawSegments.isArray() || rawSegments.isEmpty()) {
            throw failure("derivative funding receipt lacks its canonical cadence segments");
        }
        List<Long> expected = expectedFundingSlots(rawSegments, entryTime, exitTime);
        List<Long> observed = rows.stream().map(FundingSettlement::settlementTime).toList();
        if (new HashSet<>(observed).size() != observed.size()
                || expected.stream().anyMatch(slot -> !observed.contains(slot))
                || observed.stream().anyMatch(slot -> !expected.contains(slot))) {
            throw failure("derivative funding lifecycle has missing, extra, or duplicate settlement slots");
        }
    }

    private record FundingCadence(long from, long to, long cadence, long origin) {}

    private static List<Long> expectedFundingSlots(JsonNode rawSegments, long start, long end) {
        List<FundingCadence> segments = new ArrayList<>();
        int index = 0;
        for (JsonNode segment : rawSegments) {
            long from = time(field(segment, "effective_from"));
            long to = time(field(segment, "effective_to"));
            double rawCadence = numberJs(field(segment, "cadence_ms"));
            long origin = time(firstDefinedNonNull(
                    field(segment, "origin_at"), field(segment, "effective_from")));
            if (!(to > from) || !Double.isFinite(rawCadence) || rawCadence != Math.rint(rawCadence)
                    || rawCadence <= 0 || origin > to) {
                throw failure(origin > to ? "funding cadence origin is after segment " + index
                        : "invalid funding cadence segment " + index);
            }
            segments.add(new FundingCadence(from, to, (long) rawCadence, origin));
            index++;
        }
        segments.sort(Comparator.comparingLong(FundingCadence::from));
        if (segments.isEmpty()) throw failure("funding series requires at least one cadence segment");
        for (int current = 1; current < segments.size(); current++) {
            if (segments.get(current).from < segments.get(current - 1).to) {
                throw failure("funding cadence segments overlap ambiguously");
            }
            if (segments.get(current).from > segments.get(current - 1).to) {
                throw failure("funding cadence segments contain an uncovered interval");
            }
        }
        LinkedHashSet<Long> slots = new LinkedHashSet<>();
        for (int current = 0; current < segments.size(); current++) {
            FundingCadence segment = segments.get(current);
            long from = Math.max(start, segment.from);
            long to = Math.min(end, segment.to);
            if (to < from) continue;
            long distance = from - segment.origin;
            long quotient = Math.floorDiv(distance, segment.cadence);
            if (distance % segment.cadence != 0) quotient++;
            long slot = segment.origin + quotient * segment.cadence;
            boolean last = current == segments.size() - 1;
            while (last ? slot <= to : slot < to) {
                slots.add(slot);
                slot += segment.cadence;
            }
        }
        return slots.stream().sorted().toList();
    }

    private static double enforceQuantityTerms(double quantity, double modeledEntryPrice, double rawEntryPrice,
            double multiplier, JsonNode sizing, JsonNode contract) {
        JsonNode candidateStep = field(sizing, "quantity_step");
        JsonNode exchangeStep = firstDefined(field(contract, "step_size"), field(contract, "lot_step"),
                field(contract, "quantity_step"));
        if (defined(candidateStep) && defined(exchangeStep)) {
            double left = numberJs(candidateStep);
            double right = numberJs(exchangeStep);
            double ratio = left / right;
            if (!(left >= right) || !Double.isFinite(ratio) || Math.abs(ratio - Math.rint(ratio)) > 1e-9) {
                throw failure("sizing quantity_step may not loosen or conflict with the frozen exchange step_size");
            }
        }
        JsonNode stepNode = defined(candidateStep) ? candidateStep : exchangeStep;
        if (defined(stepNode)) {
            double step = numberJs(stepNode);
            if (!(step > 0)) throw failure("sizing quantity_step is invalid");
            quantity = Math.floor(quantity / step) * step;
        }
        double minQty = strictMinimum(field(sizing, "min_quantity"),
                firstDefined(field(contract, "min_qty"), field(contract, "min_quantity")));
        double maxQty = strictMaximum(field(sizing, "max_quantity"),
                firstDefined(field(contract, "max_qty"), field(contract, "max_quantity")));
        if (Double.isFinite(minQty) && (!(minQty > 0) || quantity < minQty)) {
            throw failure("derived execution quantity is below the frozen minimum quantity");
        }
        if (Double.isFinite(maxQty) && (!(maxQty > 0) || quantity > maxQty)) {
            throw failure("derived execution quantity exceeds the frozen maximum quantity");
        }
        double minNotional = strictMinimum(field(sizing, "min_notional_usd"),
                firstDefined(field(contract, "min_notional"), field(contract, "min_notional_usd")));
        double maxNotional = strictMaximum(field(sizing, "max_notional_usd"),
                firstDefined(field(contract, "max_notional"), field(contract, "max_notional_usd")));
        if (Double.isFinite(minNotional) && (!(minNotional > 0) || quantity * rawEntryPrice * multiplier < minNotional)) {
            throw failure("derived execution quantity is below the frozen minimum notional");
        }
        double modeledNotional = quantity * modeledEntryPrice * multiplier;
        double tolerance = Double.isFinite(maxNotional)
                ? Math.ulp(Math.max(1, Math.max(Math.abs(maxNotional), Math.abs(modeledNotional)))) * 8 : 0;
        if (Double.isFinite(maxNotional) && (!(maxNotional > 0) || modeledNotional - maxNotional > tolerance)) {
            throw failure("derived execution quantity exceeds the frozen maximum notional");
        }
        if (!(quantity > 0)) throw failure("frozen sizing contract rounds execution quantity to zero");
        return quantity;
    }

    private static double strictMinimum(JsonNode candidate, JsonNode exchange) {
        if (!defined(candidate)) return defined(exchange) ? numberJs(exchange) : Double.NaN;
        if (!defined(exchange)) return numberJs(candidate);
        return Math.max(numberJs(candidate), numberJs(exchange));
    }

    private static double strictMaximum(JsonNode candidate, JsonNode exchange) {
        if (!defined(candidate)) return defined(exchange) ? numberJs(exchange) : Double.NaN;
        if (!defined(exchange)) return numberJs(candidate);
        return Math.min(numberJs(candidate), numberJs(exchange));
    }

    private static ObjectNode derivedHardMetrics(List<Outcome> outcomes, List<String> episodeIds,
            ObjectNode candidateReturns, JsonNode chromosome, Map<String, ObjectNode> features,
            Map<String, ObjectNode> executions) {
        double wins = 0;
        double losses = 0;
        List<Double> values = new ArrayList<>();
        for (String id : episodeIds) {
            double value = numberJs(field(field(candidateReturns, id), "net_r"));
            values.add(value);
            if (value > 0) wins += value;
            if (value < 0) losses += Math.abs(value);
        }
        int traded = 0;
        double totalCostR = 0;
        boolean capacityPass = true;
        for (Outcome outcome : outcomes) {
            if (outcome == null) continue;
            traded++;
            if (!(outcome.riskAmountUsd > 0) || outcome.feesUsd < 0 || outcome.slippageUsd < 0
                    || outcome.capacityDebitUsd < 0) {
                throw failure("outcome " + (outcome.episodeId == null ? "?" : outcome.episodeId)
                        + " lacks exact nonnegative round-trip cost accounting");
            }
            totalCostR += (outcome.feesUsd + outcome.slippageUsd + outcome.capacityDebitUsd
                    + Math.max(0, -outcome.fundingPnlUsd)) / outcome.riskAmountUsd;
            JsonNode capacity = field(executions.get(outcome.episodeId), "capacity_inputs");
            double available = numberJs(field(capacity, "available_liquidity_usd"));
            double participation = numberJs(field(capacity, "participation_cap"));
            double notional = outcome.entryPrice * outcome.quantity * outcome.contractMultiplier;
            double order = defined(field(capacity, "order_notional_usd"))
                    ? numberJs(field(capacity, "order_notional_usd")) : notional;
            if (!(available > 0) || !(participation > 0 && participation <= 1) || !(order > 0)
                    || order > available * participation) capacityPass = false;
        }
        ObjectNode metrics = object();
        metrics.put("cost_r", traded == 0 ? 0 : totalCostR / traded);
        long covered = episodeIds.stream().filter(id -> features.containsKey(id) && executions.containsKey(id)).count();
        metrics.put("coverage_fraction", episodeIds.isEmpty() ? 0 : (double) covered / episodeIds.size());
        metrics.put("capacity_pass", traded > 0 && capacityPass);
        metrics.put("max_drawdown_r", drawdown(values));
        metrics.put("profit_factor", losses > 0 ? wins / losses : (wins > 0 ? 1e9 : 0));
        metrics.put("turnover", traded);
        metrics.put("complexity", complexity(chromosome));
        return metrics;
    }

    private static ObjectNode makeEvaluationArtifact(ObjectNode signalArtifact, List<String> episodeIds,
            String phase, JsonNode foldId, JsonNode cutoff, JsonNode fitCutoff, JsonNode evaluationCutoff,
            String weighting, ObjectNode candidateReturns, ObjectNode metrics, ArrayNode rawIntent,
            ObjectNode candidateDefinition, ObjectNode behaviorContracts) {
        List<String> ordered = new ArrayList<>();
        for (JsonNode row : array(field(signalArtifact, "episodes"))) {
            String id = jsString(field(row, "episode_id"));
            if (episodeIds.contains(id)) ordered.add(id);
        }
        if (ordered.size() != episodeIds.size()) throw failure("evaluation scope is outside the signal view");
        ArrayNode intents = normalizeSignalIntentVector(ordered, rawIntent);
        ObjectNode intentBody = object();
        intentBody.put("schema", "strategy-v5-statistical-signal-intent-vector/1");
        ArrayNode intentEpisodes = array();
        for (JsonNode row : intents) {
            ObjectNode item = object();
            item.put("episode_id", text(field(row, "episode_id")));
            item.set("intent", cloneNode(field(row, "intent")));
            intentEpisodes.add(item);
        }
        intentBody.set("episodes", intentEpisodes);
        String intentSha = hash(intentBody);
        ObjectNode aliasBody = object();
        aliasBody.put("schema", "strategy-v5-statistical-effective-behavior/2");
        for (String key : List.of("signal_semantics_sha256", "evaluator_sha256", "predictor_sha256",
                "lifecycle_sha256", "precommit_sha256")) {
            aliasBody.set(key, cloneNode(field(behaviorContracts, key)));
        }
        String alias = hash(aliasBody);
        ObjectNode lineageBody = object();
        lineageBody.put("source_artifact_sha256", text(field(signalArtifact, "source_artifact_sha256")));
        lineageBody.set("episode_ids", strings(ordered));
        lineageBody.put("phase", phase);
        lineageBody.set("fold_id", cloneNode(foldId));
        lineageBody.set("cutoff", cloneNode(cutoff));
        lineageBody.set("fit_cutoff", cloneNode(fitCutoff));
        lineageBody.set("evaluation_cutoff", cloneNode(evaluationCutoff));
        lineageBody.put("weighting", weighting);
        ObjectNode vectorBody = object();
        vectorBody.put("schema", "strategy-v5-statistical-evaluation-vector/1");
        vectorBody.set("episode_ids", strings(ordered));
        vectorBody.put("signal_intent_vector_sha256", intentSha);
        vectorBody.set("candidate_returns", candidateReturns.deepCopy());

        ObjectNode value = object();
        value.put("schema", "strategy-v5-statistical-evaluation/1");
        value.put("version", 1);
        value.put("source_artifact_sha256", text(field(signalArtifact, "source_artifact_sha256")));
        value.set("episode_ids", strings(ordered));
        value.put("phase", phase);
        value.set("fold_id", cloneNode(foldId));
        value.set("cutoff", cloneNode(cutoff));
        value.set("fit_cutoff", cloneNode(fitCutoff));
        value.set("evaluation_cutoff", cloneNode(evaluationCutoff));
        value.put("weighting", weighting);
        value.set("signal_intent_vector", intents);
        value.put("signal_intent_vector_sha256", intentSha);
        value.put("evaluation_vector_sha256", hash(vectorBody));
        value.set("candidate_definition", candidateDefinition.deepCopy());
        value.set("behavior_contracts", behaviorContracts.deepCopy());
        value.put("signal_behavior_alias_sha256", text(field(behaviorContracts, "signal_semantics_sha256")));
        value.put("behavior_alias_sha256", alias);
        value.set("candidate_returns", candidateReturns.deepCopy());
        value.set("metrics", metrics.deepCopy());
        value.put("lineage_sha256", hash(lineageBody));
        value.put("content_sha256", ownHash(value));
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(value);
        return value;
    }

    private static ArrayNode normalizeSignalIntentVector(List<String> ordered, ArrayNode raw) {
        if (raw == null || raw.size() != ordered.size()) {
            throw failure("signal intent vector must cover the evaluation scope");
        }
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        for (JsonNode row : raw) {
            String id = text(field(row, "episode_id"));
            if (byId.containsKey(id) || !ordered.contains(id)) {
                throw failure("signal intent vector has duplicate or unknown episode");
            }
            ObjectNode normalized = object();
            normalized.put("episode_id", id);
            normalized.set("intent", cloneNode(field(row, "intent")));
            byId.put(id, normalized);
        }
        ArrayNode result = array();
        for (String id : ordered) result.add(byId.get(id));
        return result;
    }

    private static void exactSignalInventory(ObjectNode artifact, List<String> requested, String phase,
            JsonNode foldId, Map<String, ObjectNode> features, Map<String, ObjectNode> labels,
            Map<String, ObjectNode> executions) {
        if (!"strategy-v5-statistical-signal-view/1".equals(text(field(artifact, "schema")))) {
            throw failure("evaluator requires a canonical signal-view artifact");
        }
        if (!field(artifact, "episode_ids").isArray() || !field(artifact, "episodes").isArray()) {
            throw failure("signal view phase inventory is missing");
        }
        List<String> declared = stringInventory(field(artifact, "episode_ids"));
        List<String> episodeRows = new ArrayList<>();
        for (JsonNode row : array(field(artifact, "episodes"))) episodeRows.add(jsString(field(row, "episode_id")));
        if (new HashSet<>(requested).size() != requested.size() || new HashSet<>(declared).size() != declared.size()) {
            throw failure("signal-view episode inventory contains duplicate IDs");
        }
        if (!requested.equals(declared) || !requested.equals(episodeRows)) {
            throw failure("episode_ids do not exactly equal the declared signal-view phase inventory");
        }
        if (!phase.equals(text(field(artifact, "phase")))) {
            throw failure("signal-view phase mismatch: expected " + phase);
        }
        if (!jsEquivalent(firstDefined(field(artifact, "fold_id"), NullNode.instance), foldId)) {
            throw failure("signal-view fold inventory mismatch");
        }
        for (JsonNode row : array(field(artifact, "episodes"))) {
            String id = jsString(field(row, "episode_id"));
            if (present(row, "phase") && !phase.equals(text(field(row, "phase")))) {
                throw failure("signal-view episode " + id + " has an altered phase");
            }
            if (present(row, "fold_id") && !jsEquivalent(firstDefined(field(row, "fold_id"), NullNode.instance), foldId)) {
                throw failure("signal-view episode " + id + " has an altered fold");
            }
            ObjectNode feature = features.get(id);
            if (feature == null) throw failure("feature episode " + id + " is missing from the declared phase inventory");
            for (String key : List.of("asset", "episode_id")) {
                if (present(row, key) && !jsString(field(row, key)).equals(jsString(field(feature, key)))) {
                    throw failure("signal-view episode " + id + " identity does not match feature role");
                }
            }
            if (present(row, "decision_time") && time(field(row, "decision_time")) != time(field(feature, "decision_time"))) {
                throw failure("signal-view episode " + id + " decision time was altered");
            }
            if (!exactFalse(field(feature, "signal_eligible"))) {
                ObjectNode label = labels.get(id);
                ObjectNode execution = executions.get(id);
                if (label == null || execution == null) {
                    throw failure("episode " + id + " lacks exact label/execution phase bindings");
                }
                if (!identity(feature).equals(identity(label)) || !identity(feature).equals(identity(execution))) {
                    throw failure("episode " + id + " has mismatched signal/label/execution identity");
                }
            }
        }
    }

    private static void enforcePitBoundary(ObjectNode feature, ObjectNode label, ObjectNode execution,
            String phase, JsonNode cutoff, JsonNode fitCutoff, JsonNode evaluationCutoff) {
        if ("OUTER_OOS".equals(phase)) {
            if (definedNonNull(cutoff) || definedNonNull(fitCutoff) || definedNonNull(evaluationCutoff)) {
                throw failure("OUTER_OOS evaluation must use a null cutoff, null fit/evaluation cutoffs, and remain unweighted");
            }
            return;
        }
        JsonNode fit = definedNonNull(fitCutoff) ? fitCutoff : cutoff;
        JsonNode evaluation = definedNonNull(evaluationCutoff) ? evaluationCutoff : cutoff;
        if (!definedNonNull(fit) || !definedNonNull(evaluation)) {
            throw failure(phase + " evaluation requires explicit fit and evaluation cutoffs");
        }
        long fitTime = time(fit);
        long evaluationTime = time(evaluation);
        if (evaluationTime < fitTime) throw failure(phase + " evaluation cutoff precedes its fit cutoff");
        long decision = time(field(feature, "decision_time"));
        long available = time(field(feature, "availability_time"));
        if ("INNER_VALIDATION".equals(phase)) {
            if (!(decision > fitTime && decision < evaluationTime)) {
                throw failure("validation feature " + text(field(feature, "episode_id"))
                        + " is outside the frozen fit/evaluation window");
            }
            if (available > decision) throw failure("validation feature " + text(field(feature, "episode_id")) + " was unavailable at its decision");
        } else if (decision > fitTime || available > fitTime) {
            throw failure("feature " + text(field(feature, "episode_id")) + " is post-cutoff or unavailable at the training cutoff");
        }
        if (exactFalse(field(feature, "signal_eligible"))) return;
        JsonNode labelAvailable = firstDefined(field(label, "availability_time"), field(label, "label_availability_time"));
        JsonNode executionAvailable = firstDefined(field(execution, "availability_time"), field(execution, "execution_availability_time"));
        if (!truthy(labelAvailable) || !truthy(executionAvailable) || time(labelAvailable) > evaluationTime
                || time(executionAvailable) > evaluationTime) {
            throw failure("episode " + text(field(feature, "episode_id")) + " label/execution is unavailable at the "
                    + ("INNER_VALIDATION".equals(phase) ? "evaluation" : "training") + " cutoff");
        }
        JsonNode resolution = firstDefined(field(label, "resolution_time"), field(label, "resolution_ceiling_time"));
        if (truthy(resolution) && time(resolution) > evaluationTime) {
            throw failure("episode " + text(field(feature, "episode_id")) + " outcome resolves after the evaluation cutoff");
        }
    }

    private static void bindFrozenContract(ObjectNode candidate, ObjectNode executionContract,
            ObjectNode evaluatorSpec, String sourceKey, String destinationKey) {
        JsonNode source = field(executionContract, sourceKey);
        if (!truthy(source)) return;
        ObjectNode bound = objectOrEmpty(source).deepCopy();
        bound.put("evaluator_spec_sha256", text(field(evaluatorSpec, "content_sha256")));
        candidate.set(destinationKey, bound);
    }

    private static ObjectNode publicFeatureRow(ObjectNode feature, List<String> predictorIds) {
        ObjectNode result = object();
        for (String key : SIGNAL_IDENTITY_FIELDS) if (present(feature, key)) result.set(key, cloneNode(field(feature, key)));
        for (String key : predictorIds) if (present(feature, key)) result.set(key, cloneNode(field(feature, key)));
        return result;
    }

    private static JsonNode resolveTemplate(JsonNode value, JsonNode chromosome) {
        if (value == null || value.isMissingNode()) return NullNode.instance;
        if (value.isArray()) {
            ArrayNode output = array();
            for (JsonNode child : value) output.add(resolveTemplate(child, chromosome));
            return output;
        }
        if (!value.isObject()) return cloneNode(value);
        if (value.size() == 1 && field(value, "$gene").isTextual()) {
            String name = text(field(value, "$gene"));
            if (!present(chromosome, name)) throw failure("chromosome is missing gene " + name);
            return cloneNode(field(chromosome, name));
        }
        ObjectNode output = object();
        value.fields().forEachRemaining(entry -> output.set(entry.getKey(), resolveTemplate(entry.getValue(), chromosome)));
        return output;
    }

    private static boolean evaluateSignalPredicateNodeV5(JsonNode predicate, JsonNode feature, JsonNode chromosome) {
        if (truthy(field(predicate, "predictor_id"))) {
            return compare(field(feature, text(field(predicate, "predictor_id"))), text(field(predicate, "op")),
                    resolveTemplate(field(predicate, "value"), chromosome));
        }
        if (field(predicate, "all").isArray()) {
            for (JsonNode child : field(predicate, "all")) if (!evaluateSignalPredicateNodeV5(child, feature, chromosome)) return false;
            return true;
        }
        if (field(predicate, "any").isArray()) {
            for (JsonNode child : field(predicate, "any")) if (evaluateSignalPredicateNodeV5(child, feature, chromosome)) return true;
            return false;
        }
        if (truthy(field(predicate, "not"))) return !evaluateSignalPredicateNodeV5(field(predicate, "not"), feature, chromosome);
        throw failure("predicate AST is invalid");
    }

    private static boolean compare(JsonNode actual, String op, JsonNode expected) {
        if (!definedNonNull(actual)) return false;
        if ("IN".equals(op)) {
            if (!expected.isArray()) return false;
            for (JsonNode value : expected) if (jsEquivalent(value, actual)) return true;
            return false;
        }
        if ("EQ".equals(op) || "NE".equals(op)) {
            boolean equal = jsEquivalent(actual, expected);
            return "EQ".equals(op) ? equal : !equal;
        }
        double left = numberJs(actual);
        double right = numberJs(expected);
        if (!Double.isFinite(left) || !Double.isFinite(right)) return false;
        return switch (op) {
            case "GT" -> left > right;
            case "GTE" -> left >= right;
            case "LT" -> left < right;
            case "LTE" -> left <= right;
            default -> throw failure("unsupported predicate operator " + op);
        };
    }

    private static List<ObjectNode> predicatePredictors(JsonNode predicate) {
        List<ObjectNode> output = new ArrayList<>();
        predicatePredictors(predicate, output);
        return output;
    }

    private static void predicatePredictors(JsonNode predicate, List<ObjectNode> output) {
        if (truthy(field(predicate, "predictor_id"))) {
            ObjectNode row = object();
            row.put("predictor_id", text(field(predicate, "predictor_id")));
            output.add(row);
        }
        JsonNode children = field(predicate, "all").isArray() ? field(predicate, "all") : field(predicate, "any");
        if (children.isArray()) for (JsonNode child : children) predicatePredictors(child, output);
        if (truthy(field(predicate, "not"))) predicatePredictors(field(predicate, "not"), output);
    }

    private static List<String> requiredPredicatePredictorIds(JsonNode predicate) {
        return predicatePredictors(predicate).stream().map(row -> text(field(row, "predictor_id"))).distinct().sorted().toList();
    }

    private static List<String> missingPredicatePredictors(JsonNode predicate, JsonNode feature) {
        return requiredPredicatePredictorIds(predicate).stream()
                .filter(id -> feature == null || !present(feature, id) || field(feature, id).isNull()).toList();
    }

    private static Set<String> geneReferences(JsonNode value) {
        Set<String> output = new LinkedHashSet<>();
        geneReferences(value, output);
        return output;
    }

    private static void geneReferences(JsonNode value, Set<String> output) {
        if (value == null || value.isMissingNode() || value.isNull()) return;
        if (value.isArray()) { value.forEach(child -> geneReferences(child, output)); return; }
        if (!value.isObject()) return;
        if (value.size() == 1 && field(value, "$gene").isTextual()) output.add(text(field(value, "$gene")));
        else value.forEach(child -> geneReferences(child, output));
    }

    private static void validateCandidatePredicates(JsonNode registry, List<ObjectNode> predicates) {
        Set<String> registered = new HashSet<>();
        for (JsonNode row : array(field(registry, "predictors"))) registered.add(text(field(row, "id")));
        List<String> ids = predicates.stream().map(row -> text(field(row, "predictor_id"))).toList();
        for (String id : ids) if (!registered.contains(id)) {
            throw failure("candidate predicate references an unregistered predictor: " + id);
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw failure("candidate predicate inventory contains duplicate predictor IDs");
        }
    }

    private static void validateNormalizedLifecycleExecutionContracts(JsonNode candidate, JsonNode contract, String label) {
        boolean normalized = truthy(field(candidate, "lifecycle")) || truthy(field(candidate, "lifecycle_spec"))
                || "strategy-v5-trade-lifecycle/1".equals(text(field(candidate, "lifecycle_engine")));
        if (normalized && (!truthy(field(contract, "risk_convention")) || !truthy(field(contract, "sizing_contract")))) {
            throw failure(label + " normalized lifecycle requires both a frozen risk_convention and sizing_contract");
        }
    }

    private static void validateLifecycleSizingBoundary(JsonNode candidate, JsonNode contract, String label) {
        JsonNode lifecycle = firstTruthy(field(candidate, "lifecycle"), field(candidate, "lifecycle_spec"));
        JsonNode lifecycleSizing = truthy(lifecycle) ? field(lifecycle, "sizing") : field(candidate, "sizing");
        JsonNode frozenSizing = field(contract, "sizing_contract");
        if (!truthy(lifecycleSizing) || !truthy(frozenSizing)) return;
        if (!geneReferences(lifecycleSizing).isEmpty()) {
            throw failure(label + " lifecycle sizing cannot be gene-controlled when an execution sizing contract is frozen");
        }
        Sizing left = sizingSemantics(lifecycleSizing, field(contract, "risk_convention"));
        Sizing right = sizingSemantics(frozenSizing, field(contract, "risk_convention"));
        if (left == null || !left.mode.equals(right.mode) || !Double.isFinite(left.amount)
                || !Double.isFinite(right.amount) || left.amount != right.amount) {
            throw failure(label + " lifecycle sizing disagrees with the frozen execution sizing contract");
        }
    }

    private record Sizing(String mode, double amount) {}

    private static LifecycleTrustService.Token createLifecycleTrustToken(ObjectNode execution,
            JsonNode metadata, MetadataCustody metadataCustody, RoleCustody roleCustody,
            ObjectNode evaluatorSpec) {
        return createLifecycleTrustToken(execution, metadata, metadataCustody, roleCustody,
                evaluatorSpec, null, roleCustody.manifestDigest, null,
                artifactSha(field(roleCustody.artifacts, "execution")));
    }

    private static LifecycleTrustService.Token createLifecycleTrustToken(ObjectNode execution,
            JsonNode metadata, MetadataCustody metadataCustody, RoleCustody roleCustody,
            ObjectNode evaluatorSpec, JsonNode metadataOverrides, String manifestSha256,
            String sourceDatasetRootSha256, String executionArtifactSha256) {
        if (!metadataCustody.enabled || !roleCustody.enabled) {
            throw failure("authoritative normalized lifecycle lacks physically reopened metadata custody");
        }
        metadataCustody.verify();
        roleCustody.verify();
        ObjectNode effectiveMetadata = cloneOrEmptyObject(metadata);
        ObjectNode stressLineage = object();
        if (metadataOverrides != null && metadataOverrides.isObject()) {
            for (String key : List.of("fee_schedule", "execution_model")) {
                JsonNode override = field(metadataOverrides, key);
                if (!definedNonNull(override)) continue;
                if (!override.isObject() || !field(override, "authoritative").asBoolean(false)
                        || !text(field(override, "content_sha256")).equals(ownHash(override))) {
                    throw failure("authoritative lifecycle " + key
                            + " stress metadata is not loader-bound");
                }
                ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(override);
                effectiveMetadata.set(key, override.deepCopy());
                stressLineage.put("stress_" + key + "_sha256",
                        text(field(override, "content_sha256")));
            }
        }
        long at = time(firstDefined(field(execution, "decision_time"), field(execution, "entry_time")));
        String asset = text(field(execution, "asset")).toLowerCase(Locale.ROOT);
        String instrument = text(field(execution, "instrument")).toUpperCase(Locale.ROOT);
        String venue = text(field(execution, "venue")).toUpperCase(Locale.ROOT);
        String symbol = text(field(execution, "symbol")).toUpperCase(Locale.ROOT);
        ObjectNode contract = metadataRecord(objectOrEmpty(field(effectiveMetadata, "contract_spec")),
                asset, instrument, venue, symbol, at).deepCopy();
        ObjectNode fee = metadataRecord(objectOrEmpty(field(effectiveMetadata, "fee_schedule")),
                asset, instrument, venue, symbol, at);
        ObjectNode model = metadataRecord(objectOrEmpty(field(effectiveMetadata, "execution_model")),
                asset, instrument, venue, symbol, at).deepCopy();
        model.put("taker_fee_rate", numberJs(field(fee, "taker_fee_rate")));
        JsonNode capacityNode = firstTruthy(field(execution, "capacity_inputs"), field(execution, "liquidity_inputs"));
        if (!truthy(capacityNode) || !(numberJs(field(capacityNode, "available_liquidity_usd")) > 0)
                || !(numberJs(field(capacityNode, "participation_cap")) > 0
                && numberJs(field(capacityNode, "participation_cap")) <= 1)
                || !(numberJs(field(capacityNode, "order_notional_usd")) > 0)) {
            throw failure("authoritative normalized lifecycle lacks loader-bound capacity inputs");
        }
        ArrayNode bars = array(field(execution, "child_bars")).deepCopy();
        if (bars.isEmpty()) throw failure("authoritative normalized lifecycle lacks physical child bars");
        ArrayNode funding = array(firstTruthy(field(execution, "funding_rows"), field(execution, "funding_events")));
        ArrayNode marks = array(field(execution, "mark_bars"));

        Map<String, JsonNode> values = new LinkedHashMap<>();
        values.put("contract_spec", contract);
        values.put("execution_model", model);
        values.put("capacity", cloneNode(capacityNode));
        values.put("bars", rowsValue(bars));
        if (!funding.isEmpty()) values.put("funding", rowsValue(funding));
        if (!marks.isEmpty()) values.put("marks", rowsValue(marks));
        Map<String, LifecycleTrustService.ReceiptReference> receipts = new LinkedHashMap<>();
        receipts.put("contract_spec", lifecycleReceipt("contract-spec", contract, false));
        receipts.put("execution_model", lifecycleReceipt("execution-model", model, false));
        receipts.put("capacity", lifecycleReceipt("capacity", capacityNode, false));
        receipts.put("bars", lifecycleReceipt("bars", bars, true));
        if (!funding.isEmpty()) receipts.put("funding", lifecycleReceipt("funding", funding, true));
        if (!marks.isEmpty()) receipts.put("marks", lifecycleReceipt("marks", marks, true));
        ObjectNode lineage = object();
        String boundManifestSha256 = HASH_RE.matcher(manifestSha256 == null ? "" : manifestSha256).matches()
                ? manifestSha256 : roleCustody.manifestDigest;
        lineage.put("manifest_sha256", boundManifestSha256);
        if (HASH_RE.matcher(sourceDatasetRootSha256 == null ? "" : sourceDatasetRootSha256).matches()) {
            lineage.put("source_dataset_root_sha256", sourceDatasetRootSha256);
        } else lineage.putNull("source_dataset_root_sha256");
        lineage.put("evaluator_spec_sha256", text(field(evaluatorSpec, "content_sha256")));
        lineage.put("precommit_sha256", text(field(evaluatorSpec, "precommit_sha256")));
        if (HASH_RE.matcher(executionArtifactSha256 == null ? "" : executionArtifactSha256).matches()) {
            lineage.put("execution_artifact_sha256", executionArtifactSha256);
        } else lineage.putNull("execution_artifact_sha256");
        lineage.put("metadata_source_binding_sha256", metadataCustody.digest);
        lineage.put("lifecycle_loader", "AUTHORITATIVE_EVALUATOR_REOPEN");
        stressLineage.fields().forEachRemaining(entry -> lineage.set(entry.getKey(), entry.getValue().deepCopy()));
        Map<String, JsonNode> frozenValues = immutableJsonMap(values);
        Map<String, LifecycleTrustService.ReceiptReference> frozenReceipts = Map.copyOf(receipts);
        return LIFECYCLE_TRUST.createVerifiedLoaderLifecycleTrustV5(
                "authoritative-parquet:" + boundManifestSha256, frozenReceipts, frozenValues, lineage,
                () -> {
                    roleCustody.verify();
                    metadataCustody.verify();
                    return new LifecycleTrustService.LoaderReopen(frozenReceipts, frozenValues);
                });
    }

    private static ObjectNode rowsValue(ArrayNode rows) {
        ObjectNode value = object();
        value.set("rows", rows.deepCopy());
        return value;
    }

    private static LifecycleTrustService.ReceiptReference lifecycleReceipt(
            String role, JsonNode value, boolean rows) {
        JsonNode payload = rows ? rowsValue((ArrayNode) value) : cloneNode(value);
        byte[] bytes = jsonBytes(payload);
        return new LifecycleTrustService.ReceiptReference(
                "loader://" + role + "/" + hash(payload),
                ownHash(payload), hash(bytes), (long) bytes.length,
                rows ? hash(value) : null, null);
    }

    private static Map<String, JsonNode> immutableJsonMap(Map<String, JsonNode> source) {
        Map<String, JsonNode> output = new LinkedHashMap<>();
        source.forEach((key, value) -> output.put(key, cloneNode(value)));
        return Map.copyOf(output);
    }

    /** Loader-owned lazy partition custody and materializer. */
    private static final class ExecutionLazyCustody {
        private final boolean enabled;
        private final ObjectNode binding;
        private final Path root;

        private ExecutionLazyCustody(boolean enabled, ObjectNode binding, Path root) {
            this.enabled = enabled;
            this.binding = binding;
            this.root = root;
        }

        static ExecutionLazyCustody capture(JsonNode rawBinding) {
            if (!rawBinding.isObject()) return new ExecutionLazyCustody(false, object(), null);
            ObjectNode binding = objectOrEmpty(rawBinding).deepCopy();
            Path root = requiredPath(field(binding, "root"), "execution hydration root");
            requireRealDirectory(root, "authoritative execution hydration root");
            ExecutionLazyCustody custody = new ExecutionLazyCustody(true, binding, root);
            custody.verify();
            return custody;
        }

        void verify() {
            if (!enabled) return;
            for (JsonNode partition : array(field(binding, "partitions"))) {
                Path path = securePhysicalReference(root, text(field(partition, "path")),
                        "authoritative execution partition");
                byte[] bytes = readBytes(path, "authoritative execution partition");
                if (!hash(bytes).equals(text(field(partition, "sha256")))
                        || (defined(field(partition, "bytes")) && bytes.length != field(partition, "bytes").asLong(-1))) {
                    throw failure("authoritative execution partition bytes changed");
                }
            }
        }

        ObjectNode materialize(ObjectNode rawExecution) {
            ObjectNode execution = rawExecution == null ? object() : rawExecution.deepCopy();
            JsonNode reference = field(execution, "execution_reference");
            if (!enabled || !reference.isObject()) return execution;
            verify();
            JsonNode lower = truthy(field(reference, "preentry_start"))
                    ? field(reference, "preentry_start") : field(reference, "execution_start");
            ObjectNode request = rangeRequest(reference, lower, field(reference, "execution_end"), null);
            ObjectNode result = OpportunityV5.readHydratedRangeV5(request);
            ArrayNode rows = flattenBatches(field(result, "batches"));
            long entry = time(field(reference, "execution_start"));
            ArrayNode preentry = array();
            ArrayNode child = array();
            for (JsonNode row : rows) {
                long at = time(firstDefined(field(row, "event_time"), field(row, "time"), field(row, "open_time")));
                (at < entry ? preentry : child).add(cloneNode(row));
            }
            execution.set("preentry_start", cloneNode(lower));
            execution.set("preentry_bars", preentry);
            execution.set("child_bars", child);
            JsonNode capture = capture(text(field(reference, "window_id")));
            if (capture != null && !array(field(capture, "mark_partition_refs")).isEmpty()) {
                ObjectNode markRequest = rangeRequest(reference, field(reference, "execution_start"),
                        field(reference, "execution_end"), "MARK");
                execution.set("mark_bars", flattenBatches(field(
                        OpportunityV5.readHydratedRangeV5(markRequest), "batches")));
            }
            return execution;
        }

        private ObjectNode rangeRequest(JsonNode reference, JsonNode start, JsonNode end, String role) {
            ObjectNode request = object();
            request.set("hydration", cloneNode(field(binding, "hydration")));
            request.set("partitions", cloneNode(field(binding, "partitions")));
            request.put("window_id", text(field(reference, "window_id")));
            request.set("start", cloneNode(start));
            request.set("end", cloneNode(end));
            if (role != null) request.put("role", role);
            request.put("batchSize", field(binding, "batch_size").asInt(4_096));
            request.put("maxRows", field(binding, "max_rows").asLong(100_000));
            request.put("maxResidentBytes", field(binding, "max_resident_bytes").asLong(192L * 1_024 * 1_024));
            request.put("maxOutputBytes", field(binding, "max_output_bytes").asLong(128L * 1_024 * 1_024));
            return request;
        }

        private JsonNode capture(String windowId) {
            JsonNode match = null;
            for (JsonNode row : array(field(field(binding, "hydration"), "windows"))) {
                if (!windowId.equals(text(field(row, "window_id")))) continue;
                if (match != null) throw failure("lazy execution hydration has duplicate window custody " + windowId);
                match = row;
            }
            return match;
        }
    }

    private static ArrayNode flattenBatches(JsonNode rawBatches) {
        ArrayNode output = array();
        for (JsonNode batch : array(rawBatches)) for (JsonNode row : array(batch)) output.add(cloneNode(row));
        return output;
    }

    /** Reopenable custody snapshot for the three lifecycle metadata receipts. */
    private static final class MetadataCustody {
        private final boolean enabled;
        private final ObjectNode metadata;
        private final Path root;
        private final String digest;
        private final ObjectNode physicalBinding;

        private MetadataCustody(boolean enabled, ObjectNode metadata, Path root, String digest,
                ObjectNode physicalBinding) {
            this.enabled = enabled;
            this.metadata = metadata;
            this.root = root;
            this.digest = digest;
            this.physicalBinding = physicalBinding;
        }

        static MetadataCustody capture(
                JsonNode rawMetadata, boolean productionBoundary, JsonNode explicitRoot) {
            ObjectNode metadata = cloneOrEmptyObject(rawMetadata);
            boolean physical = productionBoundary && List.of("contract_spec", "fee_schedule", "execution_model").stream()
                    .map(key -> field(metadata, key))
                    .allMatch(receipt -> receipt.isObject()
                            && text(field(receipt, "content_sha256")).equals(ownHash(receipt)));
            if (!physical) return new MetadataCustody(false, metadata, null, hash(object()), null);
            JsonNode explicit = truthy(explicitRoot) ? explicitRoot
                    : firstTruthy(field(metadata, "source_root"), field(metadata, "sourceRoot"));
            List<String> references = new ArrayList<>();
            for (String key : List.of("contract_spec", "fee_schedule", "execution_model")) {
                JsonNode receipt = field(metadata, key);
                if (!text(field(receipt, "content_sha256")).equals(ownHash(receipt))
                        || !field(receipt, "authoritative").asBoolean(false)) {
                    throw failure("authoritative normalized lifecycle metadata receipts are incomplete");
                }
                String reference = text(field(receipt, "source_root_reference"));
                if (reference.isBlank()) {
                    throw failure("authoritative normalized lifecycle metadata source root reference is missing");
                }
                references.add(reference);
            }
            Path root;
            if (truthy(explicit)) root = Path.of(text(explicit)).toAbsolutePath().normalize();
            else {
                String reference = references.get(0);
                if (Path.of(reference).isAbsolute() || reference.contains("\\")
                        || List.of(reference.split("/")).contains("..")) {
                    throw failure("authoritative normalized lifecycle metadata source root reference escapes its portable root");
                }
                root = Path.of(System.getProperty("user.dir")).resolve(reference).toAbsolutePath().normalize();
            }
            for (String reference : references) {
                if (Path.of(reference).isAbsolute() || reference.contains("\\")) {
                    throw failure("authoritative normalized lifecycle metadata source root reference is not portable");
                }
                Path declared = Path.of(System.getProperty("user.dir")).resolve(reference).toAbsolutePath().normalize();
                if (!declared.equals(root)) {
                    throw failure("authoritative normalized lifecycle metadata source roots disagree");
                }
            }
            requireRealDirectory(root, "authoritative lifecycle metadata root");
            MetadataCustody pending = new MetadataCustody(true, metadata, root, null, null);
            ObjectNode binding = pending.reopenBinding();
            return new MetadataCustody(true, metadata, root,
                    text(field(binding, "digest")), binding.deepCopy());
        }

        void verify() {
            if (enabled && !digest.equals(text(field(reopenBinding(), "digest")))) {
                throw failure("authoritative lifecycle metadata source receipt bytes changed before evaluation");
            }
        }

        ObjectNode binding() {
            if (!enabled || physicalBinding == null) return null;
            verify();
            return physicalBinding.deepCopy();
        }

        private ObjectNode reopenBinding() {
            ObjectNode receipts = object();
            for (String key : List.of("contract_spec", "fee_schedule", "execution_model")) {
                JsonNode receipt = field(metadata, key);
                ArrayNode summaries = array(field(receipt, "source_receipts"));
                if (summaries.isEmpty()) {
                    throw failure("authoritative lifecycle " + key + " metadata lacks physical source receipts");
                }
                ObjectNode kindBinding = object();
                kindBinding.put("receipt_content_sha256", text(field(receipt, "content_sha256")));
                kindBinding.put("receipt_byte_sha256", hash(jsonBytes(receipt)));
                ArrayNode normalized = kindBinding.putArray("normalized");
                for (JsonNode summary : summaries) {
                    Path path = securePhysicalFile(root, text(field(summary, "path")), key + " normalized source receipt");
                    byte[] bytes = readBytes(path, key + " normalized source receipt");
                    JsonNode parsed = parseBytes(bytes, key + " normalized source receipt");
                    String content = text(field(parsed, "content_sha256"));
                    String expected = truthy(field(summary, "content_sha256"))
                            ? text(field(summary, "content_sha256")) : text(field(summary, "sha256"));
                    if (!content.equals(expected) || !content.equals(ownHash(parsed))) {
                        throw failure(key + " normalized source receipt content is tampered");
                    }
                    ObjectNode row = object();
                    row.set("summary", cloneNode(summary));
                    row.put("content_sha256", content);
                    row.put("byte_sha256", hash(bytes));
                    ArrayNode rawInventory = array();
                    ArrayNode rawHashes = array();
                    for (JsonNode raw : array(field(parsed, "raw_receipts"))) {
                        Path rawPath = securePhysicalFile(root, text(field(raw, "path")), key + " raw metadata bytes");
                        byte[] rawBytes = readBytes(rawPath, key + " raw metadata bytes");
                        if (rawBytes.length != field(raw, "bytes").asLong(-1)
                                || !hash(rawBytes).equals(text(field(raw, "byte_sha256")))) {
                            throw failure("authoritative lifecycle raw metadata bytes are missing or tampered: " + key);
                        }
                        ObjectNode rawRow = object();
                        rawRow.put("path", text(field(raw, "path")));
                        rawRow.put("bytes", rawBytes.length);
                        rawRow.put("byte_sha256", hash(rawBytes));
                        if (definedNonNull(field(raw, "content_sha256"))) {
                            rawRow.set("content_sha256", cloneNode(field(raw, "content_sha256")));
                        } else rawRow.putNull("content_sha256");
                        rawInventory.add(rawRow);
                        rawHashes.add(hash(rawBytes));
                    }
                    List<JsonNode> sortedRaw = new ArrayList<>();
                    rawInventory.forEach(sortedRaw::add);
                    sortedRaw.sort(Comparator.comparing(value -> text(field(value, "path"))));
                    row.set("raw_receipts", arrayOfNodes(sortedRaw));
                    List<String> sortedHashes = new ArrayList<>();
                    rawHashes.forEach(value -> sortedHashes.add(text(value)));
                    sortedHashes.sort(String::compareTo);
                    row.set("raw_byte_sha256", strings(sortedHashes));
                    normalized.add(row);
                }
                String kind = key.equals("contract_spec") ? "CONTRACT_SPEC"
                        : key.equals("fee_schedule") ? "FEE_SCHEDULE" : "EXECUTION_MODEL";
                receipts.set(kind, kindBinding);
            }
            ObjectNode digestPayload = object();
            receipts.fields().forEachRemaining(entry -> digestPayload.set(entry.getKey(), entry.getValue().deepCopy()));
            ObjectNode binding = object();
            binding.put("root", root.toString());
            binding.set("receipts", receipts);
            binding.put("digest", hash(digestPayload));
            return binding;
        }
    }

    /** Reopenable custody snapshot for authoritative Parquet role bytes. */
    private static final class RoleCustody {
        private final boolean enabled;
        private final Path root;
        private final ObjectNode artifacts;
        private final String manifestDigest;

        private RoleCustody(boolean enabled, Path root, ObjectNode artifacts, String manifestDigest) {
            this.enabled = enabled;
            this.root = root;
            this.artifacts = artifacts;
            this.manifestDigest = manifestDigest;
        }

        static RoleCustody capture(JsonNode rootNode, JsonNode artifactNode) {
            if (!truthy(rootNode) || !artifactNode.isObject()) {
                return new RoleCustody(false, null, object(), hash(object()));
            }
            Path root = Path.of(text(rootNode)).toAbsolutePath().normalize();
            requireRealDirectory(root, "authoritative evaluator physical root");
            ObjectNode artifacts = objectOrEmpty(artifactNode).deepCopy();
            ObjectNode digestInput = object();
            for (String role : List.of("feature", "label", "execution")) {
                JsonNode artifact = field(artifacts, role);
                ObjectNode row = object();
                row.put("path", text(field(artifact, "path")));
                row.put("sha256", artifactSha(artifact));
                digestInput.set(role, row);
            }
            RoleCustody custody = new RoleCustody(true, root, artifacts, hash(digestInput));
            custody.verify();
            return custody;
        }

        void verify() {
            if (!enabled) return;
            for (String role : List.of("feature", "label", "execution")) {
                JsonNode artifact = field(artifacts, role);
                Path path = securePhysicalFile(root, text(field(artifact, "path")), "authoritative " + role + " artifact");
                byte[] bytes = readBytes(path, "authoritative " + role + " artifact");
                if (!hash(bytes).equals(artifactSha(artifact))) {
                    throw failure("authoritative " + role + " artifact bytes changed");
                }
            }
        }
    }

    private static Sizing sizingSemantics(JsonNode sizing, JsonNode risk) {
        if (!truthy(sizing)) return null;
        String mode = jsString(firstTruthy(field(sizing, "mode"), field(sizing, "type"), JSON.textNode("")))
                .toUpperCase(Locale.ROOT);
        if (Set.of("FIXED_NOTIONAL", "FIXED_NOTIONAL_USD").contains(mode)) {
            return new Sizing("FIXED_NOTIONAL_USD", numberJs(firstDefined(field(sizing, "notional_usd"), field(sizing, "notional"))));
        }
        if (Set.of("RISK_USD", "FIXED_RISK_BUDGET_USD", "TARGET_STOP_RISK").contains(mode)) {
            return new Sizing("TARGET_STOP_RISK", numberJs(firstDefined(field(sizing, "risk_usd"),
                    field(sizing, "budget_usd"), field(sizing, "risk_amount_usd"), field(risk, "budget_usd"))));
        }
        return new Sizing(mode, Double.NaN);
    }

    private static void verifyAuthoritativeManifest(ObjectNode manifest, Path root, ObjectNode evaluatorSpec,
            ObjectNode predictorRegistry) {
        if (!text(field(manifest, "content_sha256")).equals(ownHash(manifest))) {
            throw failure("evaluator source manifest is not authoritative or lineage-bound");
        }
        if (!"AUTHORITATIVE_PARQUET".equals(text(field(manifest, "status")))
                || !field(manifest, "authoritative").asBoolean(false)
                || !text(field(manifest, "predictor_registry_sha256"))
                .equals(text(field(predictorRegistry, "content_sha256")))
                || !text(field(manifest, "precommit_sha256")).equals(text(field(evaluatorSpec, "precommit_sha256")))) {
            throw failure("evaluator source manifest is not authoritative or lineage-bound");
        }
        List<String> roles = new ArrayList<>(List.of("feature", "label", "execution"));
        if (field(field(manifest, "artifacts"), "mark").isObject()) roles.add("mark");
        for (String role : roles) {
            JsonNode artifact = field(field(manifest, "artifacts"), role);
            Path path = safeArtifactPath(root, text(field(artifact, "path")));
            try {
                byte[] bytes = Files.readAllBytes(path);
                if (!artifactSha(artifact).equals(hash(bytes))) {
                    throw failure("authoritative " + role + " artifact bytes changed");
                }
                Long declared = optionalLong(field(artifact, "bytes"));
                if (declared != null && declared != bytes.length) {
                    throw failure("authoritative " + role + " artifact bytes changed");
                }
            } catch (IOException error) {
                throw failure("authoritative " + role + " artifact bytes changed");
            }
        }
    }

    private static ArrayNode readParquetRole(Path root, ObjectNode manifest, String role, int batchRows,
            int maxRows, long maxBytes, List<String> episodeIds) {
        JsonNode artifact = field(field(manifest, "artifacts"), role);
        Path path = safeArtifactPath(root, text(field(artifact, "path")));
        String escaped = path.toString().replace("'", "''");
        String where = "";
        if (episodeIds != null && !episodeIds.isEmpty()) {
            where = " WHERE CAST(episode_id AS VARCHAR) IN (" + episodeIds.stream()
                    .map(value -> "'" + value.replace("'", "''") + "'")
                    .reduce((left, right) -> left + "," + right).orElse("") + ")";
        }
        ArrayNode output = array();
        long observedBytes = 0;
        try {
            Class.forName("org.duckdb.DuckDBDriver");
            try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
                    Statement statement = connection.createStatement()) {
                long count;
                try (ResultSet result = statement.executeQuery(
                        "SELECT count(*)::BIGINT FROM read_parquet('" + escaped + "')" + where)) {
                    result.next();
                    count = result.getLong(1);
                }
                if (count < 0 || count > maxRows) {
                    throw failure("Parquet role row count " + count + " exceeds the bounded evaluator limit " + maxRows);
                }
                for (long offset = 0; offset < count; offset += batchRows) {
                    String sql = "SELECT * FROM read_parquet('" + escaped + "')" + where
                            + " ORDER BY decision_time, episode_id LIMIT " + batchRows + " OFFSET " + offset;
                    ArrayNode batch = array();
                    try (ResultSet rows = statement.executeQuery(sql)) {
                        ResultSetMetaData metadata = rows.getMetaData();
                        while (rows.next()) {
                            ObjectNode row = object();
                            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                                row.set(metadata.getColumnLabel(column), normalizeSqlValue(rows.getObject(column)));
                            }
                            batch.add(row);
                        }
                    }
                    observedBytes += jsonBytes(batch).length;
                    if (observedBytes > maxBytes) {
                        throw failure("Parquet role materialization exceeds the bounded evaluator memory contract " + maxBytes);
                    }
                    output.addAll(batch);
                }
                if (output.size() != count) throw failure("Parquet role bounded read count mismatch");
                return output;
            }
        } catch (ClassNotFoundException | SQLException error) {
            throw failure("authoritative Parquet role read failed: " + error.getMessage());
        }
    }

    private static JsonNode normalizeSqlValue(Object value) throws SQLException {
        if (value == null) return NullNode.instance;
        if (value instanceof JsonNode node) return cloneNode(node);
        // DuckDB maps Parquet TIMESTAMP (without a time zone) to java.sql.Timestamp.  Its
        // calendar fields are the stored UTC wall time; Timestamp#toInstant would instead
        // apply the host default zone (and shift every value on non-UTC machines).
        if (value instanceof Timestamp timestamp) {
            return JSON.textNode(iso(timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC).toEpochMilli()));
        }
        if (value instanceof java.sql.Date date) return JSON.textNode(date.toLocalDate().toString());
        if (value instanceof Instant instant) return JSON.textNode(iso(instant.toEpochMilli()));
        if (value instanceof OffsetDateTime time) return JSON.textNode(iso(time.toInstant().toEpochMilli()));
        if (value instanceof LocalDateTime time) return JSON.textNode(iso(time.toInstant(ZoneOffset.UTC).toEpochMilli()));
        if (value instanceof LocalDate date) return JSON.textNode(date.toString());
        if (value instanceof Struct struct) {
            // DuckDB exposes named STRUCT values through getMap(); the generic JDBC
            // getAttributes() loses the names and would turn capacity/lifecycle inputs into an
            // unusable positional array.
            try {
                Object mapped = value.getClass().getMethod("getMap").invoke(value);
                if (mapped instanceof Map<?, ?>) return normalizeSqlValue(mapped);
            } catch (NoSuchMethodException ignored) {
                // Fall through for JDBC drivers that expose only positional structs.
            } catch (ReflectiveOperationException error) {
                throw failure("authoritative Parquet struct normalization failed: " + error.getMessage());
            }
            return normalizeSqlValue(struct.getAttributes());
        }
        if (value instanceof java.sql.Array sqlArray) return normalizeSqlValue(sqlArray.getArray());
        if (value instanceof Map<?, ?> map) {
            ObjectNode output = object();
            for (Map.Entry<?, ?> entry : map.entrySet()) output.set(String.valueOf(entry.getKey()), normalizeSqlValue(entry.getValue()));
            return output;
        }
        if (value instanceof Collection<?> collection) {
            ArrayNode output = array();
            for (Object child : collection) output.add(normalizeSqlValue(child));
            return output;
        }
        if (value.getClass().isArray()) {
            ArrayNode output = array();
            for (int index = 0; index < Array.getLength(value); index++) output.add(normalizeSqlValue(Array.get(value, index)));
            return output;
        }
        return MAPPER.valueToTree(value);
    }

    private static Path safeArtifactPath(Path root, String candidate) {
        try {
            Path base = root.toAbsolutePath().normalize();
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(base)) {
                throw failure("Parquet authoritative root is not a regular directory");
            }
            if (candidate == null || candidate.isBlank()) throw failure("Parquet role path escapes or aliases its authoritative root");
            Path target = base.resolve(candidate).normalize();
            if (target.equals(base) || !target.startsWith(base)) {
                throw failure("Parquet role path escapes or aliases its authoritative root");
            }
            Path cursor = base;
            for (Path component : base.relativize(target)) {
                cursor = cursor.resolve(component);
                if (Files.isSymbolicLink(cursor)) throw failure("Parquet role path contains a symlink");
                if (!cursor.equals(target) && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure("Parquet role path parent is not a directory");
                }
            }
            requireSingleLinkFile(target, "Parquet role path");
            if (!target.toRealPath().startsWith(base.toRealPath())) {
                throw failure("Parquet role path physically escapes its authoritative root");
            }
            return target;
        } catch (IOException error) {
            throw failure("Parquet role path escapes or aliases its authoritative root");
        }
    }

    private static void requireRealDirectory(Path root, String label) {
        try {
            Path normalized = root.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(label + " is not a regular directory");
            }
            normalized.toRealPath();
        } catch (IOException error) {
            throw failure(label + " is not a regular directory");
        }
    }

    private static Path securePhysicalFile(Path root, String reference, String label) {
        try {
            requireRealDirectory(root, label + " root");
            Path base = root.toAbsolutePath().normalize();
            if (reference == null || reference.isBlank()) {
                throw failure(label + " path is required");
            }
            Path relative = Path.of(reference);
            if (relative.isAbsolute()) throw failure(label + " path escapes its authoritative root");
            Path target = base.resolve(relative).normalize();
            if (target.equals(base) || !target.startsWith(base)) {
                throw failure(label + " path escapes its authoritative root");
            }
            Path cursor = base;
            for (Path component : base.relativize(target)) {
                cursor = cursor.resolve(component);
                if (Files.isSymbolicLink(cursor)) throw failure(label + " path contains a symlink");
                if (!cursor.equals(target) && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure(label + " path parent is not a directory");
                }
            }
            requireSingleLinkFile(target, label);
            if (!target.toRealPath().startsWith(base.toRealPath())) {
                throw failure(label + " path physically escapes its authoritative root");
            }
            return target;
        } catch (IOException | java.nio.file.InvalidPathException error) {
            throw failure(label + " path escapes or aliases its authoritative root");
        }
    }

    private static Path securePhysicalReference(Path root, String reference, String label) {
        try {
            Path candidate = Path.of(reference == null ? "" : reference);
            if (candidate.isAbsolute()) {
                Path base = root.toAbsolutePath().normalize();
                Path normalized = candidate.toAbsolutePath().normalize();
                if (!normalized.startsWith(base) || normalized.equals(base)) {
                    throw failure(label + " path escapes its authoritative root");
                }
                return securePhysicalFile(base, base.relativize(normalized).toString(), label);
            }
            return securePhysicalFile(root, reference, label);
        } catch (java.nio.file.InvalidPathException error) {
            throw failure(label + " path escapes or aliases its authoritative root");
        }
    }

    private static byte[] readBytes(Path path, String label) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException error) {
            throw failure(label + " cannot be reopened");
        }
    }

    private static JsonNode parseBytes(byte[] bytes, String label) {
        try {
            return MAPPER.readTree(bytes);
        } catch (IOException error) {
            throw failure(label + " is not valid JSON");
        }
    }

    private static ArrayNode arrayOfNodes(Collection<? extends JsonNode> values) {
        ArrayNode output = array();
        values.forEach(value -> output.add(cloneNode(value)));
        return output;
    }

    private static Path secureCacheRoot(Path root) {
        try {
            Path value = root.toAbsolutePath().normalize();
            Files.createDirectories(value);
            if (Files.isSymbolicLink(value) || !Files.isDirectory(value, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("authoritative evaluator cache root is not a regular directory");
            }
            return value.toRealPath();
        } catch (IOException error) {
            throw failure("authoritative evaluator cache root is invalid");
        }
    }

    private static void requireSingleLinkFile(Path path, String label) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(label + " is not a regular single-link file");
            }
            Object count = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (count instanceof Number number && number.longValue() != 1) {
                throw failure(label + " is not a regular single-link file");
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms still receive the regular-file and symlink checks.
        } catch (IOException error) {
            throw failure(label + " is not a regular single-link file");
        }
    }

    private static ObjectNode boundMetadata(JsonNode raw, String kind, boolean fixtureOnly) {
        return boundMetadata(raw, kind, fixtureOnly, false);
    }

    private static ObjectNode boundMetadata(
            JsonNode raw, String kind, boolean fixtureOnly, boolean allowConservativeModel) {
        ObjectNode receipt = objectOrEmpty(raw);
        if (receipt.isEmpty()) throw failure(kind + " metadata is not bound");
        if (!text(field(receipt, "content_sha256")).equals(ownHash(receipt))) {
            throw failure(kind + " metadata hash is invalid");
        }
        String status = text(field(receipt, "status"));
        if (!kind.equals(text(field(receipt, "kind"))) || "UNAVAILABLE".equals(status)) {
            throw failure(kind + " metadata is unavailable or non-authoritative");
        }
        boolean productionModel = allowConservativeModel && "EXECUTION_MODEL".equals(kind)
                && "MODEL_BOUND".equals(text(field(receipt, "provenance_mode")))
                && HASH_RE.matcher(text(field(receipt, "model_sha256"))).matches()
                && HASH_RE.matcher(text(field(receipt, "precommit_sha256"))).matches();
        if ("CONSERVATIVE_MODEL".equals(status) && !(fixtureOnly || productionModel)) {
            throw failure(kind + " modeled metadata is stress-only");
        }
        if (!field(receipt, "authoritative").asBoolean(false)
                && !(fixtureOnly && "CONSERVATIVE_MODEL".equals(status)) && !productionModel) {
            throw failure(kind + " metadata is unavailable or non-authoritative");
        }
        return receipt;
    }

    private static ObjectNode metadataRecord(ObjectNode receipt, String asset, String instrument,
            String venue, String symbol, long at) {
        List<ObjectNode> matches = new ArrayList<>();
        for (JsonNode raw : array(field(receipt, "records"))) {
            ObjectNode row = objectOrEmpty(raw);
            if (asset.equalsIgnoreCase(text(field(row, "asset")))
                    && instrument.equalsIgnoreCase(text(field(row, "instrument")))
                    && venue.equalsIgnoreCase(text(field(row, "venue")))
                    && symbol.equalsIgnoreCase(text(field(row, "symbol")))
                    && time(field(row, "effective_from")) <= at && time(field(row, "effective_to")) >= at
                    && time(field(row, "availability_time")) <= at) matches.add(row);
        }
        if (matches.size() != 1) {
            throw failure("metadata is missing, unavailable, or ambiguous for " + asset + "/" + venue + "/"
                    + instrument + "/" + symbol + " at " + iso(at));
        }
        return matches.get(0);
    }

    private static long validateDirectOutcomeIdentities(ObjectNode feature, ObjectNode label, ObjectNode execution) {
        String left = roleIdentity(feature, "feature");
        if (!left.equals(roleIdentity(label, "label")) || !left.equals(roleIdentity(execution, "execution"))) {
            throw failure("feature/label/execution series identities do not match");
        }
        long decision = time(firstDefined(field(feature, "decision_time"), field(feature, "event_time")));
        if (decision != time(firstDefined(field(label, "decision_time"), field(label, "event_time")))
                || decision != time(firstDefined(field(execution, "decision_time"), field(execution, "event_time"),
                field(execution, "entry_time")))) {
            throw failure("feature/label/execution decision times do not match");
        }
        if (!truthy(field(feature, "signal_id")) || !text(field(feature, "signal_id")).equals(text(field(label, "signal_id")))
                || !text(field(feature, "signal_id")).equals(text(field(execution, "signal_id")))
                || !truthy(field(label, "episode_id"))
                || !text(field(label, "episode_id")).equals(text(field(execution, "episode_id")))) {
            throw failure("feature/label/execution signal and episode identities do not match");
        }
        return decision;
    }

    private static String roleIdentity(JsonNode row, String role) {
        for (String key : List.of("asset", "venue", "instrument", "symbol")) {
            if (!truthy(field(row, key))) throw failure(role + " identity is incomplete");
        }
        return text(field(row, "asset")).toLowerCase(Locale.ROOT) + '|'
                + text(field(row, "venue")).toLowerCase(Locale.ROOT) + '|'
                + text(field(row, "instrument")).toUpperCase(Locale.ROOT) + '|'
                + text(field(row, "symbol")).toUpperCase(Locale.ROOT);
    }

    private static String identity(JsonNode row) {
        return jsString(field(row, "signal_id")) + '|' + jsString(field(row, "episode_id"));
    }

    private static boolean canonicalLifecycle(JsonNode candidate, JsonNode execution) {
        return truthy(field(candidate, "lifecycle")) || truthy(field(candidate, "lifecycle_spec"))
                || truthy(field(execution, "lifecycle")) || truthy(field(execution, "lifecycle_spec"))
                || "strategy-v5-trade-lifecycle/1".equals(text(field(candidate, "lifecycle_engine")))
                || "strategy-v5-trade-lifecycle/1".equals(text(field(execution, "lifecycle_engine")));
    }

    private static long rowAvailability(JsonNode row) {
        return time(firstDefinedNonNull(field(row, "availability_time"), field(row, "available_at"),
                field(row, "close_time"), field(row, "event_time"), field(row, "time")));
    }

    private static long timeframeMilliseconds(String value) {
        Matcher matcher = TIMEFRAME_RE.matcher(value == null ? "" : value);
        if (!matcher.matches()) throw failure("unsupported lifecycle timeframe " + value);
        long base = Long.parseLong(matcher.group(1));
        return base * switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "m" -> ONE_MINUTE;
            case "h" -> 60 * ONE_MINUTE;
            default -> 24 * 60 * ONE_MINUTE;
        };
    }

    private static double drawdown(List<Double> values) {
        double peak = 0;
        double equity = 0;
        double worst = 0;
        for (double value : values) {
            equity += value;
            peak = Math.max(peak, equity);
            worst = Math.min(worst, equity - peak);
        }
        return worst;
    }

    private static int complexity(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return 0;
        if (value.isArray()) {
            int total = 0;
            for (JsonNode child : value) total += complexity(child);
            return total;
        }
        if (!value.isObject()) return 0;
        int total = 1;
        for (JsonNode child : value) total += complexity(child);
        return total;
    }

    private static boolean physicalNullTemporalKey(String key) {
        return "time".equals(key) || "timestamp".equals(key) || "ts".equals(key)
                || key.endsWith("_time") || key.endsWith("_timestamp") || key.endsWith("_start")
                || key.endsWith("_end");
    }

    private static JsonNode physicalNullRebaseTime(JsonNode value, JsonNode fromDecision, JsonNode toDecision) {
        Long source = tryTime(fromDecision);
        Long target = tryTime(toDecision);
        if (source == null || target == null) return cloneNode(value);
        long delta = target - source;
        if (value != null && value.isNumber()) {
            double raw = value.doubleValue();
            if (!Double.isFinite(raw)) return cloneNode(value);
            return JSON.numberNode(raw + delta / (Math.abs(raw) < 100_000_000_000d ? 1_000d : 1d));
        }
        if (value == null || !value.isTextual()) return cloneNode(value);
        Long parsed = tryTime(value);
        return parsed == null ? cloneNode(value) : JSON.textNode(iso(parsed + delta));
    }

    private static JsonNode physicalNullRebaseNested(JsonNode value, JsonNode fromDecision,
            JsonNode toDecision, String key) {
        if (value == null || value.isMissingNode()) return NullNode.instance;
        if (value.isArray()) {
            ArrayNode output = array();
            for (JsonNode child : value) output.add(physicalNullRebaseNested(child, fromDecision, toDecision, key));
            return output;
        }
        if (!value.isObject()) return physicalNullTemporalKey(key)
                ? physicalNullRebaseTime(value, fromDecision, toDecision) : cloneNode(value);
        ObjectNode output = object();
        value.fields().forEachRemaining(entry -> output.set(entry.getKey(), physicalNullTemporalKey(entry.getKey())
                ? physicalNullRebaseTime(entry.getValue(), fromDecision, toDecision)
                : physicalNullRebaseNested(entry.getValue(), fromDecision, toDecision, entry.getKey())));
        return output;
    }

    private static String ownHash(JsonNode value) {
        ObjectNode copy = objectOrEmpty(value).deepCopy();
        copy.remove("content_sha256");
        return hash(copy);
    }

    private static String artifactSha(JsonNode artifact) {
        String value = truthy(field(artifact, "sha256")) ? text(field(artifact, "sha256"))
                : text(field(artifact, "byte_sha256"));
        return requireHash(JSON.textNode(value), "artifact sha256");
    }

    private static String requireHash(JsonNode value, String label) {
        String text = StrategyEvaluatorV5.text(value);
        if (!HASH_RE.matcher(text).matches()) throw failure(label + " must be a SHA-256 hash");
        return text;
    }

    private static long time(JsonNode value) {
        Long result = tryTime(value);
        if (result == null) throw failure("invalid timestamp " + jsString(value));
        return result;
    }

    private static Long tryTime(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (value.isNumber()) return value.longValue();
        String raw = value.asText();
        try { return Instant.parse(raw).toEpochMilli(); } catch (DateTimeParseException ignored) {}
        try { return OffsetDateTime.parse(raw).toInstant().toEpochMilli(); } catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli(); } catch (DateTimeParseException ignored) {}
        try { return LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static String iso(long millis) { return JS_ISO.format(Instant.ofEpochMilli(millis)); }

    private static String defaultWeighting(String phase) {
        if ("TRAIN_ONLY".equals(phase) || "TRAIN_CONFIRMATION".equals(phase)) return "TRAIN_HALF_LIFE";
        if ("INNER_VALIDATION".equals(phase)) return "UNWEIGHTED_VALIDATION";
        return "UNWEIGHTED_OOS";
    }

    private static Path requiredPath(JsonNode node, String label) {
        if (!truthy(node) || !node.isTextual()) throw failure(label + " is required");
        return Path.of(node.textValue()).toAbsolutePath().normalize();
    }

    private static int strictIntOption(JsonNode options, String key, int fallback) {
        if (!defined(field(options, key))) return fallback;
        JsonNode value = field(options, key);
        if (!value.isNumber()) return Integer.MIN_VALUE;
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) return Integer.MIN_VALUE;
        return (int) number;
    }

    private static long strictLongOption(JsonNode options, String key, long fallback) {
        if (!defined(field(options, key))) return fallback;
        JsonNode value = field(options, key);
        if (!value.isNumber()) return Long.MIN_VALUE;
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Long.MIN_VALUE || number > Long.MAX_VALUE) return Long.MIN_VALUE;
        return (long) number;
    }

    private static int coercedIntOption(JsonNode options, String key, int fallback) {
        if (!defined(field(options, key))) return fallback;
        double number = numberJs(field(options, key));
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) return Integer.MIN_VALUE;
        return (int) number;
    }

    private static long longOption(JsonNode options, String key, long fallback) {
        return defined(field(options, key)) ? (long) numberJs(field(options, key)) : fallback;
    }

    private static Long optionalLong(JsonNode value) {
        return definedNonNull(value) ? value.longValue() : null;
    }

    private static List<String> optionalStringInventory(JsonNode value) {
        if (!defined(value) || value.isNull()) return null;
        if (!value.isArray()) return List.of();
        return valueToDistinctStrings(value);
    }

    private static List<String> valueToDistinctStrings(JsonNode value) {
        LinkedHashSet<String> output = new LinkedHashSet<>();
        for (JsonNode child : value) output.add(jsString(child));
        return List.copyOf(output);
    }

    private static List<String> stringInventory(JsonNode value) {
        if (!value.isArray()) return List.of();
        List<String> output = new ArrayList<>();
        value.forEach(child -> output.add(jsString(child)));
        return output;
    }

    private static ArrayNode strings(Collection<String> values) {
        ArrayNode output = array();
        values.forEach(output::add);
        return output;
    }

    private static byte[] jsonBytes(JsonNode value) {
        try { return MAPPER.writeValueAsBytes(value); }
        catch (JsonProcessingException error) { throw failure(error.getMessage()); }
    }

    private static byte[] appendLf(byte[] bytes) {
        byte[] output = java.util.Arrays.copyOf(bytes, bytes.length + 1);
        output[output.length - 1] = '\n';
        return output;
    }

    private static boolean jsEquivalent(JsonNode left, JsonNode right) {
        if (left == null || left.isMissingNode()) left = NullNode.instance;
        if (right == null || right.isMissingNode()) right = NullNode.instance;
        return hash(left).equals(hash(right));
    }

    private static double numberJs(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return 0;
        if (value.isBoolean()) return value.asBoolean() ? 1 : 0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isTextual()) {
            String raw = value.textValue().trim();
            if (raw.isEmpty()) return 0;
            try { return Double.parseDouble(raw); } catch (NumberFormatException ignored) { return Double.NaN; }
        }
        return Double.NaN;
    }

    private static String jsString(JsonNode value) {
        if (value == null || value.isMissingNode()) return "undefined";
        if (value.isNull()) return "null";
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean() || value.isNumber()) return value.asText();
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            value.forEach(child -> values.add(child.isNull() ? "" : jsString(child)));
            return String.join(",", values);
        }
        return "[object Object]";
    }

    private static boolean truthy(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return false;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue());
        if (value.isTextual()) return !value.textValue().isEmpty();
        return true;
    }

    private static boolean exactFalse(JsonNode value) { return value != null && value.isBoolean() && !value.booleanValue(); }
    private static boolean defined(JsonNode value) { return value != null && !value.isMissingNode(); }
    private static boolean definedNonNull(JsonNode value) { return defined(value) && !value.isNull(); }
    private static boolean present(JsonNode value, String key) { return value != null && value.isObject() && value.has(key); }

    private static JsonNode firstDefined(JsonNode... values) {
        for (JsonNode value : values) if (defined(value)) return value;
        return NullNode.instance;
    }

    private static JsonNode firstDefinedNonNull(JsonNode... values) {
        for (JsonNode value : values) if (definedNonNull(value)) return value;
        return NullNode.instance;
    }

    private static JsonNode firstTruthy(JsonNode... values) {
        for (JsonNode value : values) if (truthy(value)) return value;
        return NullNode.instance;
    }

    private static String text(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private static String truthyText(JsonNode value) { return truthy(value) ? jsString(value) : null; }

    private static JsonNode field(JsonNode value, String key) {
        if (value == null || !value.isObject()) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        JsonNode result = value.get(key);
        return result == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : result;
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static ArrayNode array() { return MAPPER.createArrayNode(); }

    private static ArrayNode array(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : array();
    }

    private static ArrayNode requireArray(JsonNode value, String label) {
        if (value == null || !value.isArray()) throw failure(label + " is required");
        return (ArrayNode) value;
    }

    private static ObjectNode objectOrEmpty(JsonNode value) {
        return value != null && value.isObject() ? (ObjectNode) value : object();
    }

    private static JsonNode cloneNode(JsonNode value) {
        return value == null || value.isMissingNode() ? NullNode.instance : value.deepCopy();
    }

    private static ObjectNode cloneOrEmptyObject(JsonNode value) {
        return value != null && value.isObject() ? ((ObjectNode) value).deepCopy() : object();
    }

    private static void putTextOrNull(ObjectNode target, String key, JsonNode value) {
        if (definedNonNull(value)) target.put(key, jsString(value)); else target.set(key, NullNode.instance);
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(message == null ? "null" : message);
    }
}
