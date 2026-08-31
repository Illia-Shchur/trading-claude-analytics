package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact artifact-contract port of {@code strategy-research-v5-statistical.mjs}. */
public final class StrategyStatisticalV5 {
    public static final Map<String, String> STAT_SCHEMA = Map.ofEntries(
            Map.entry("input", "strategy-v5-statistical-input/1"),
            Map.entry("exposure", "strategy-v5-statistical-exposure-head/1"),
            Map.entry("genetic", "strategy-v5-statistical-genetic-run/1"),
            Map.entry("fold", "strategy-v5-statistical-fold/1"),
            Map.entry("evaluation", "strategy-v5-statistical-evaluation/1"),
            Map.entry("wfo", "strategy-v5-statistical-wfo/1"),
            Map.entry("audit", "strategy-v5-statistical-audit/1"),
            Map.entry("nulls", "strategy-v5-statistical-null-controls/1"),
            Map.entry("vectors", "strategy-v5-statistical-vector-inventory/1"),
            Map.entry("nullReplay", "strategy-v5-statistical-null-replay/1"),
            Map.entry("stress", "strategy-v5-statistical-stress-decision/1"),
            Map.entry("portfolio", "strategy-v5-statistical-portfolio-decision/1"),
            Map.entry("checkpoint", "strategy-v5-statistical-genetic-checkpoint/1"),
            Map.entry("calibration", "strategy-v5-statistical-null-calibration/1"),
            Map.entry("behaviorRegistry", "strategy-v5-statistical-behavior-definition-registry/1"),
            Map.entry("physicalNullRunner", "strategy-v5-physical-null-runner/1"),
            Map.entry("physicalNullSelection", "strategy-v5-physical-null-selection/1"),
            Map.entry("registryJournal", "strategy-v5-statistical-registry-journal/1"),
            Map.entry("publicationTransaction", "strategy-v5-statistical-publication-transaction/1"));

    public static final Map<String, Object> STAT_DEFAULTS;
    public static final long MARKET_CLUSTER_MAX_SPAN_MS = 24L * 60 * 60 * 1_000;

    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Pattern HASH_RE = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern ISO_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?Z$");
    private static final DateTimeFormatter JS_ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> ASSETS = Set.of("btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
    private static final List<String> PHYSICAL_NULL_METHODS = List.of("block_permuted_labels",
            "timestamp_shifted_outcomes", "frequency_matched_random_intents", "winners_curse_selection");

    static {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("population", 48);
        defaults.put("generations", 20);
        defaults.put("minGenerations", 10);
        defaults.put("plateauGenerations", 5);
        defaults.put("seeds", List.of(11, 23, 47));
        defaults.put("crossoverProbability", .9);
        defaults.put("mutationProbability", null);
        defaults.put("halfLifeMonths", 18);
        defaults.put("purgeDays", 30);
        defaults.put("embargoDays", 7);
        defaults.put("maxLifecycleDays", 30);
        defaults.put("maxStatPValue", .10);
        defaults.put("maxPbo", .20);
        defaults.put("minEpisodes", 30);
        defaults.put("minPositiveFolds", 3);
        defaults.put("minPositiveYears", 2);
        defaults.put("minTradesPerYear", 6);
        defaults.put("minPlateau", 5);
        defaults.put("minNeighbourFraction", .5);
        defaults.put("minSeedCount", 2);
        defaults.put("minDsrProbability", .95);
        defaults.put("nullIterations", 128);
        defaults.put("nullSequentialBatchSize", 8);
        STAT_DEFAULTS = java.util.Collections.unmodifiableMap(defaults);
    }

    private StrategyStatisticalV5() {}

    @FunctionalInterface
    public interface NullReplayMethod {
        ObjectNode replay(ObjectNode args);
    }

    @FunctionalInterface
    public interface StatisticalProvider {
        ObjectNode provide(ObjectNode args);
    }

    @FunctionalInterface
    public interface CheckpointPathFactory {
        String checkpointPath(ObjectNode args);
    }

    /** Typed counterpart of the four-function Node fixture replay object. */
    public record NullReplaySuite(Map<String, NullReplayMethod> methods) {
        public NullReplaySuite {
            methods = methods == null ? Map.of() : Map.copyOf(methods);
        }

        private ObjectNode replay(String method, ObjectNode args) {
            NullReplayMethod callback = methods.get(method);
            if (callback == null) throw failure("null replay method " + method + " is missing");
            ObjectNode value = callback.replay(args.deepCopy());
            return value == null ? object() : value.deepCopy();
        }
    }

    /** Factory-branded physical runner; callers cannot mint one from contract JSON alone. */
    public static final class PhysicalNullRunner {
        private final ObjectNode contract;
        private final StrategyEvaluatorV5.Evaluator evaluator;
        private final ObjectNode selectionContext;

        private PhysicalNullRunner(ObjectNode contract, StrategyEvaluatorV5.Evaluator evaluator,
                ObjectNode selectionContext) {
            this.contract = contract.deepCopy(); this.evaluator = evaluator;
            this.selectionContext = selectionContext.deepCopy();
        }

        public ObjectNode contract() { return contract.deepCopy(); }

        public ObjectNode run(ObjectNode args) {
            ObjectNode options = args == null ? object() : args;
            String method = text(field(options, "method"));
            if (!PHYSICAL_NULL_METHODS.contains(method)) {
                throw failure("physical null runner received an undeclared transformation");
            }
            long seed = integer(field(options, "seed"), -1), iteration = integer(field(options, "iteration"), -1);
            if (seed < 0 || iteration < 0) throw failure("physical null runner seed/iteration is invalid");
            JsonNode source = field(options, "source_artifact");
            assertOwnHash(source, schema("input"), "physical null source artifact");
            ObjectNode budget = validateSelectionBudget(field(options, "selection_budget"));
            ArrayNode selectedIds = field(options, "selected_episode_ids").isArray()
                    ? array(field(options, "selected_episode_ids")).deepCopy() : null;
            ArrayNode selectedTradeIds = field(options, "selected_trade_episode_ids").isArray()
                    ? array(field(options, "selected_trade_episode_ids")).deepCopy() : null;
            if (selectedIds != null) {
                Set<String> ids = textSet(selectedIds), sourceIds = fieldTextSet(field(source, "episodes"), "episode_id");
                if (ids.size() != selectedIds.size() || !sourceIds.containsAll(ids)) {
                    throw failure("physical null selected episode scope is duplicated or outside the source artifact");
                }
            }
            if (selectedTradeIds != null) {
                Set<String> ids = textSet(selectedTradeIds);
                if (ids.size() != selectedTradeIds.size() || selectedIds == null || !textSet(selectedIds).containsAll(ids)) {
                    throw failure("physical null selected trade-frequency profile is invalid");
                }
            }
            ObjectNode context = options.deepCopy(); context.put("operation", "physical_null_selection");
            context.set("source_artifact", cloneNode(source)); context.set("selection_budget", budget);
            context.set("selection_constraints", cloneNode(field(selectionContext, "selection_constraints")));
            context.set("selection_end_at", cloneNode(field(selectionContext, "selection_end_at")));
            context.set("asset_scope", cloneNode(field(selectionContext, "asset_scope")));
            context.set("exposure_head", cloneNode(field(selectionContext, "exposure_head")));
            context.set("gene_space", cloneNode(field(selectionContext, "gene_space")));
            context.set("behavior_definitions", cloneNode(field(selectionContext, "behavior_definitions")));
            context.set("physical_null_root", cloneNode(field(selectionContext, "physical_null_root")));
            context.set("role_manifest", cloneNode(field(selectionContext, "role_manifest")));
            if (defined(field(options, "selected_candidate_id"))) {
                context.put("selected_candidate_id", jsString(field(options, "selected_candidate_id")));
            } else context.putNull("selected_candidate_id");
            if (selectedIds == null) context.putNull("selected_episode_ids");
            else {
                ArrayNode normalized = array(); selectedIds.forEach(value -> normalized.add(jsString(value)));
                context.set("selected_episode_ids", normalized);
            }
            if (defined(field(options, "selected_trade_count"))) {
                context.put("selected_trade_count", numberJs(field(options, "selected_trade_count")));
            } else context.putNull("selected_trade_count");
            if (selectedTradeIds == null) context.putNull("selected_trade_episode_ids");
            else {
                ArrayNode normalized = array(); selectedTradeIds.forEach(value -> normalized.add(jsString(value)));
                context.set("selected_trade_episode_ids", normalized);
            }
            context.set("physical_runner_contract", contract.deepCopy());
            ObjectNode roleHashes = object(); roleHashes.put("feature_artifact_sha256",
                    text(field(contract, "feature_artifact_sha256")));
            roleHashes.put("label_artifact_sha256", text(field(contract, "label_artifact_sha256")));
            roleHashes.put("execution_artifact_sha256", text(field(contract, "execution_artifact_sha256")));
            context.set("role_hashes", roleHashes); ObjectNode result = evaluator.evaluate(context);
            if (result == null || !result.isObject()) {
                throw failure("physical null adapter did not return a selection artifact");
            }
            return result.deepCopy();
        }
    }

    private static ObjectNode validateSelectionBudget(JsonNode value) {
        if (!value.isObject() || !field(value, "population").isIntegralNumber()
                || integer(field(value, "population"), -1) < 2
                || !field(value, "generations").isIntegralNumber()
                || integer(field(value, "generations"), -1) < 1
                || !field(value, "seeds").isArray() || field(value, "seeds").size() != 3) {
            throw failure("winner’s-curse selection budget is incomplete or non-deterministic");
        }
        for (JsonNode seed : field(value, "seeds")) if (!seed.isIntegralNumber()) {
            throw failure("winner’s-curse selection budget is incomplete or non-deterministic");
        }
        return objectOrEmpty(value).deepCopy();
    }

    public static PhysicalNullRunner makePhysicalNullRunnerV5(ObjectNode args) {
        return makePhysicalNullRunnerV5(args, null);
    }

    public static PhysicalNullRunner makePhysicalNullRunnerV5(ObjectNode args,
            StrategyEvaluatorV5.Evaluator evaluator) {
        ObjectNode options = args == null ? object() : args;
        if (defined(field(options, "transformAndSelect"))
                || defined(field(options, "runPhysicalNullSelection"))) {
            throw failure("authoritative physical null factory does not accept caller transform callbacks; use the verified evaluator implementation");
        }
        ObjectNode provenance = evaluator == null || evaluator.workerProvenance() == null
                ? object() : evaluator.workerProvenance();
        if (evaluator == null || !StrategyEvaluatorV5.isVerifiedPhysicalEvaluator(evaluator)
                || !"strategy-v5-statistical-worker/1".equals(text(field(provenance, "schema")))
                || !field(provenance, "verified").asBoolean(false)
                || !field(provenance, "deterministic").asBoolean(false)
                || !field(provenance, "artifact_paths_bound").asBoolean(false)
                || !field(provenance, "physical_role_binding").asBoolean(false)
                || !field(provenance, "worker_count").isIntegralNumber()
                || integer(field(provenance, "worker_count"), 0) < 1
                || !field(provenance, "memory_budget_mb").isIntegralNumber()
                || integer(field(provenance, "memory_budget_mb"), 0) < 1) {
            throw failure("physical null runner requires the internal trust-marked physical worker evaluator");
        }
        JsonNode manifest = field(options, "roleManifest").isObject()
                ? field(options, "roleManifest") : field(options, "manifest");
        JsonNode roles = field(manifest, "artifacts").isObject()
                ? field(manifest, "artifacts") : field(manifest, "roles");
        String featureHash = requireHash(firstTruthy(field(options, "featureArtifactSha256"),
                field(field(roles, "feature"), "sha256"), field(field(roles, "feature"), "content_sha256"),
                field(provenance, "feature_artifact_sha256")), "physical null feature artifact");
        String labelHash = requireHash(firstTruthy(field(options, "labelArtifactSha256"),
                field(field(roles, "label"), "sha256"), field(field(roles, "label"), "content_sha256"),
                field(provenance, "label_artifact_sha256")), "physical null label artifact");
        String executionHash = requireHash(firstTruthy(field(options, "executionArtifactSha256"),
                field(field(roles, "execution"), "sha256"), field(field(roles, "execution"), "content_sha256"),
                field(provenance, "execution_artifact_sha256")), "physical null execution artifact");
        String codeHash = requireHash(firstTruthy(field(options, "codeSha256"),
                field(provenance, "physical_null_code_sha256"), field(provenance, "code_sha256"),
                field(provenance, "evaluator_code_sha256")), "physical null adapter code");
        for (String[] binding : List.of(new String[] {"feature_artifact_sha256", featureHash, "feature"},
                new String[] {"label_artifact_sha256", labelHash, "label"},
                new String[] {"execution_artifact_sha256", executionHash, "execution"})) {
            JsonNode actual = field(provenance, binding[0]);
            if (defined(actual) && !binding[1].equals(text(actual))) {
                throw failure("physical null " + binding[2] + " artifact is not bound to the verified evaluator");
            }
        }
        if (defined(field(provenance, "physical_null_code_sha256"))) {
            if (!codeHash.equals(text(field(provenance, "physical_null_code_sha256")))) {
                throw failure("physical null adapter code is not bound to the verified evaluator");
            }
        } else if (defined(field(provenance, "code_sha256"))
                && !codeHash.equals(text(field(provenance, "code_sha256")))
                && !codeHash.equals(text(field(provenance, "evaluator_code_sha256")))) {
            throw failure("physical null adapter code is not bound to the verified evaluator");
        }
        if (!evaluator.physicalNullSelectionVerified()) {
            throw failure("PHYSICAL_NULL_SELECTION_ADAPTER_MISSING: verified evaluator does not provide the internal label/execution/nested-selection implementation");
        }
        JsonNode sourceNode = truthy(field(manifest, "content_sha256"))
                ? field(manifest, "content_sha256") : field(provenance, "source_manifest_sha256");
        String source = definedNonNull(sourceNode) && truthy(sourceNode)
                ? requireHash(sourceNode, "physical null source manifest") : null;
        ObjectNode contract = object(); contract.put("schema", schema("physicalNullRunner")); contract.put("version", 1);
        contract.put("factory", "INTERNAL_VERIFIED_PHYSICAL_FACTORY");
        contract.put("integration_status", "WIRED_PRODUCTION");
        if (source == null) contract.putNull("source_manifest_sha256"); else contract.put("source_manifest_sha256", source);
        contract.put("feature_artifact_sha256", featureHash); contract.put("label_artifact_sha256", labelHash);
        contract.put("execution_artifact_sha256", executionHash); contract.put("code_sha256", codeHash);
        contract.put("recomputes_label_execution", true); contract.put("reruns_nested_selection", true);
        contract.put("worker_backed", true); contract.put("physical_feature_label_execution", true);
        contract.set("methods", strings(PHYSICAL_NULL_METHODS)); validatePhysicalNullRunnerContract(contract, null);
        ObjectNode selectionContext = object();
        selectionContext.set("selection_constraints", field(options, "selectionConstraints").isObject()
                ? cloneNode(field(options, "selectionConstraints")) : object());
        selectionContext.set("selection_end_at", defined(field(options, "selectionEndAt"))
                ? cloneNode(field(options, "selectionEndAt")) : NullNode.instance);
        selectionContext.set("asset_scope", field(options, "assetScope").isObject()
                ? cloneNode(field(options, "assetScope")) : NullNode.instance);
        selectionContext.set("exposure_head", field(options, "exposureHead").isObject()
                ? cloneNode(field(options, "exposureHead")) : NullNode.instance);
        selectionContext.set("gene_space", field(options, "geneSpace").isObject()
                ? cloneNode(field(options, "geneSpace")) : NullNode.instance);
        selectionContext.set("behavior_definitions", field(options, "behaviorDefinitions").isArray()
                ? cloneNode(field(options, "behaviorDefinitions")) : array());
        JsonNode physicalRoot = truthy(field(options, "physicalNullRoot"))
                ? field(options, "physicalNullRoot") : field(provenance, "null_artifact_root");
        selectionContext.set("physical_null_root", truthy(physicalRoot)
                ? cloneNode(physicalRoot) : NullNode.instance);
        selectionContext.set("role_manifest", manifest.isObject() ? cloneNode(manifest) : NullNode.instance);
        return new PhysicalNullRunner(contract, evaluator, selectionContext);
    }

    private static JsonNode firstTruthy(JsonNode... values) {
        for (JsonNode value : values) if (truthy(value)) return value;
        return MissingNode.getInstance();
    }

    private static void validatePhysicalNullRunnerContract(JsonNode contract, JsonNode artifact) {
        if (!contract.isObject() || !schema("physicalNullRunner").equals(text(field(contract, "schema")))
                || integer(field(contract, "version"), -1) != 1
                || !"INTERNAL_VERIFIED_PHYSICAL_FACTORY".equals(text(field(contract, "factory")))
                || !"WIRED_PRODUCTION".equals(text(field(contract, "integration_status")))
                || !field(contract, "recomputes_label_execution").asBoolean(false)
                || !field(contract, "reruns_nested_selection").asBoolean(false)
                || !field(contract, "worker_backed").asBoolean(false)
                || !field(contract, "physical_feature_label_execution").asBoolean(false)
                || !HASH_RE.matcher(text(field(contract, "code_sha256"))).matches()
                || !HASH_RE.matcher(text(field(contract, "feature_artifact_sha256"))).matches()
                || !HASH_RE.matcher(text(field(contract, "label_artifact_sha256"))).matches()
                || !HASH_RE.matcher(text(field(contract, "execution_artifact_sha256"))).matches()
                || !stable(field(contract, "methods")).equals(stable(strings(PHYSICAL_NULL_METHODS)))) {
            throw failure("physical null runner contract is incomplete or not factory-bound");
        }
        if (definedNonNull(field(contract, "source_manifest_sha256"))) {
            requireHash(field(contract, "source_manifest_sha256"), "physical null source manifest");
        }
        if (artifact != null && artifact.isObject()
                && (!text(field(contract, "feature_artifact_sha256")).equals(
                text(field(field(artifact, "lineage"), "feature_set_sha256")))
                || !text(field(contract, "label_artifact_sha256")).equals(
                text(field(field(artifact, "lineage"), "label_set_sha256")))
                || !text(field(contract, "execution_artifact_sha256")).equals(
                text(field(field(artifact, "lineage"), "execution_set_sha256"))))) {
            throw failure("physical null runner role hashes do not match the statistical artifact lineage");
        }
    }

    private static void validateReopenablePhysicalReference(JsonNode value, String label,
            String expectedContentSha256) {
        String rawPath = text(field(value, "path"));
        if (!value.isObject() || rawPath.isEmpty() || rawPath.indexOf('\0') >= 0
                || !HASH_RE.matcher(text(field(value, "byte_sha256"))).matches()
                || !HASH_RE.matcher(text(field(value, "content_sha256"))).matches()) {
            throw failure(label + " is not a reopenable physical artifact reference");
        }
        Path target;
        try { target = Path.of(rawPath).toAbsolutePath().normalize(); }
        catch (RuntimeException error) { throw failure(label + " is not a reopenable physical artifact reference"); }
        byte[] bytes;
        try { bytes = Files.readAllBytes(target); }
        catch (IOException error) { throw failure(label + " cannot be reopened: " + error.getMessage()); }
        if (!hash(bytes).equals(text(field(value, "byte_sha256")))) {
            throw failure(label + " bytes are missing or tampered");
        }
        if (expectedContentSha256 != null
                && !expectedContentSha256.equals(text(field(value, "content_sha256")))) {
            throw failure(label + " content lineage differs from the physical null contract");
        }
    }

    private static void validatePhysicalNullSelectionReferences(JsonNode value, String sourceManifestSha256) {
        for (String[] binding : List.of(
                new String[] {"transformed_label_ref", "transformed_label_artifact_sha256", "transformed label"},
                new String[] {"transformed_execution_ref", "transformed_execution_artifact_sha256", "transformed execution"},
                new String[] {"recomputed_outcome_ref", "recomputed_outcome_artifact_sha256", "recomputed outcome"},
                new String[] {"selected_outcome_vector_ref", "selected_outcome_vector_sha256", "selected outcome vector"},
                new String[] {"trace_ref", "trace_sha256", "selection trace"})) {
            JsonNode reference = field(value, binding[0]);
            validateReopenablePhysicalReference(reference, binding[2], null);
            if (!text(field(reference, "content_sha256")).equals(text(field(value, binding[1])))) {
                throw failure(binding[2] + " content commitment differs from its reopenable reference");
            }
        }
        validateReopenablePhysicalReference(field(value, "checkpoint_ref"), "iteration checkpoint", null);
        if (sourceManifestSha256 != null && truthy(field(value, "source_manifest_sha256"))
                && !sourceManifestSha256.equals(text(field(value, "source_manifest_sha256")))) {
            throw failure("physical null references are not bound to the source manifest");
        }
    }

    private record PhysicalNullWorkload(long evaluationAttemptK, long workerEvaluationCount,
            long workerCount, long peakInFlight, long batchCount, long cacheHitCount,
            long diskCacheHitCount, List<Long> workerSlotsUsed) {}

    private static PhysicalNullWorkload readPhysicalNullWorkload(JsonNode chosen, String method) {
        JsonNode reference = field(chosen, "trace_ref");
        if (!reference.isObject() || !field(reference, "path").isTextual()
                || !HASH_RE.matcher(text(field(reference, "byte_sha256"))).matches()
                || !HASH_RE.matcher(text(field(reference, "content_sha256"))).matches()) {
            throw failure(method + " physical null trace is not reopenable");
        }
        byte[] bytes;
        try { bytes = Files.readAllBytes(Path.of(text(field(reference, "path"))).toAbsolutePath().normalize()); }
        catch (IOException | RuntimeException error) {
            throw failure(method + " physical null trace cannot be reopened: " + error.getMessage());
        }
        if (!hash(bytes).equals(text(field(reference, "byte_sha256")))) {
            throw failure(method + " physical null trace bytes are tampered");
        }
        JsonNode trace;
        try { trace = MAPPER.readTree(bytes); }
        catch (IOException error) {
            throw failure(method + " physical null trace is not valid JSON: " + error.getMessage());
        }
        if (!hash(trace).equals(text(field(reference, "content_sha256")))
                || !"strategy-v5-physical-null-selection-trace/1".equals(text(field(trace, "schema")))) {
            throw failure(method + " physical null trace content is tampered or unbound");
        }
        JsonNode diagnostics = field(trace, "evaluator_diagnostics");
        boolean invalid = !diagnostics.isObject();
        for (String key : List.of("evaluation_attempt_k", "worker_count", "batch_count", "peak_in_flight")) {
            JsonNode source = definedNonNull(field(trace, key)) ? field(trace, key) : field(diagnostics, key);
            invalid |= integerFromNumber(source, -1) < 0;
        }
        for (String key : List.of("evaluation_count", "cache_hit_count", "disk_cache_hit_count")) {
            invalid |= integerFromNumber(field(diagnostics, key), -1) < 0;
        }
        invalid |= !field(diagnostics, "worker_slots_used").isArray();
        List<Long> slots = new ArrayList<>();
        for (JsonNode slot : array(field(diagnostics, "worker_slots_used"))) {
            double number = numberJs(slot); slots.add((long) number);
        }
        slots.sort(Long::compareTo);
        if (invalid) throw failure(method + " physical null trace lacks deterministic workload accounting");
        return new PhysicalNullWorkload(integerFromNumber(field(trace, "evaluation_attempt_k"), 0),
                integerFromNumber(field(diagnostics, "evaluation_count"), 0),
                integerFromNumber(definedNonNull(field(trace, "worker_count")) ? field(trace, "worker_count")
                        : field(diagnostics, "worker_count"), 0),
                integerFromNumber(definedNonNull(field(trace, "peak_in_flight")) ? field(trace, "peak_in_flight")
                        : field(diagnostics, "peak_in_flight"), 0),
                integerFromNumber(definedNonNull(field(trace, "batch_count")) ? field(trace, "batch_count")
                        : field(diagnostics, "batch_count"), 0),
                integerFromNumber(field(diagnostics, "cache_hit_count"), 0),
                integerFromNumber(field(diagnostics, "disk_cache_hit_count"), 0), slots);
    }

    public static String stable(JsonNode value) { return CanonicalJson.canonicalize(value); }
    public static String hash(JsonNode value) { return JsonHashes.canonicalSha256(value); }
    public static String hash(String value) { return JsonHashes.sha256(value); }
    public static String hash(byte[] value) { return JsonHashes.sha256(value); }

    public static String ownHash(JsonNode value) { return ownHash(value, "content_sha256"); }

    public static String ownHash(JsonNode value, String fieldName) {
        ObjectNode copy = objectOrEmpty(value).deepCopy();
        copy.remove(fieldName);
        return hash(copy);
    }

    public static ObjectNode withHash(ObjectNode value) { return withHash(value, "content_sha256"); }

    public static ObjectNode withHash(ObjectNode value, String fieldName) {
        ObjectNode copy = value == null ? object() : value.deepCopy();
        copy.put(fieldName, ownHash(copy, fieldName));
        return copy;
    }

    public static ObjectNode validateExposureHead(JsonNode rawHead) {
        ObjectNode head = objectOrEmpty(rawHead);
        assertOwnHash(head, schema("exposure"), "exposure head");
        if (!"HEAD".equals(text(field(head, "status"))) || !truthy(field(head, "hypothesis_family"))) {
            throw failure("exposure head status/family is invalid");
        }
        ArrayNode entries = requireArray(field(head, "entries"), "exposure head entries");
        long cumulative = integer(field(head, "cumulative_k"), Long.MIN_VALUE);
        if (cumulative != entries.size()) throw failure("exposure head cumulative K does not equal entries");
        if (defined(field(head, "exposure_attempt_k"))) {
            long attempts = integer(field(head, "exposure_attempt_k"), Long.MIN_VALUE);
            if (attempts < cumulative) throw failure("exposure head selection-attempt K is invalid");
        }
        Set<String> seen = new HashSet<>();
        String previous = hash("V5-STAT-GENESIS");
        for (int index = 0; index < entries.size(); index++) {
            JsonNode entry = entries.get(index);
            assertKnownKeys(entry, Set.of("behavior_sha256", "dataset_sha256", "observed_at", "source",
                    "sequence", "previous_sha256", "definition_sha256", "vector_commitment_sha256"),
                    "exposure entry " + index);
            if (integer(field(entry, "sequence"), Long.MIN_VALUE) != index + 1
                    || !previous.equals(text(field(entry, "previous_sha256")))) {
                throw failure("exposure head chain is broken");
            }
            String behavior = requireHash(field(entry, "behavior_sha256"),
                    "exposure.entries[" + index + "].behavior_sha256");
            requireHash(field(entry, "dataset_sha256"), "exposure.entries[" + index + "].dataset_sha256");
            if (defined(field(entry, "definition_sha256"))) requireHash(field(entry, "definition_sha256"),
                    "exposure.entries[" + index + "].definition_sha256");
            if (defined(field(entry, "vector_commitment_sha256"))) requireHash(
                    field(entry, "vector_commitment_sha256"),
                    "exposure.entries[" + index + "].vector_commitment_sha256");
            if (!seen.add(behavior)) throw failure("exposure head contains duplicate behavior aliases");
            previous = hash(entry);
        }
        ObjectNode pointerInput = object();
        pointerInput.put("hypothesis_family", text(field(head, "hypothesis_family")));
        pointerInput.put("last_entry_sha256", entries.isEmpty()
                ? hash("V5-STAT-GENESIS") : hash(entries.get(entries.size() - 1)));
        if (!hash(pointerInput).equals(text(field(head, "head_pointer_sha256")))) {
            throw failure("exposure head pointer is invalid");
        }
        return head;
    }

    public static ObjectNode makeExposureHead(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        String family = text(field(options, "hypothesisFamily"));
        if (family.isEmpty()) throw failure("exposure head requires a hypothesis family");
        String dataset = requireHash(field(options, "datasetSha256"), "dataset_sha256");
        ArrayNode inputEntries = defined(field(options, "entries"))
                ? requireArray(field(options, "entries"), "entries") : array();
        Set<String> unique = new HashSet<>();
        ArrayNode rows = array();
        for (int index = 0; index < inputEntries.size(); index++) {
            JsonNode raw = inputEntries.get(index);
            String behavior = requireHash(field(raw, "behavior_sha256"), "exposure entry " + index);
            if (!unique.add(behavior)) throw failure("exposure entries must be behaviorally unique");
            ObjectNode row = object();
            row.put("behavior_sha256", behavior);
            row.put("dataset_sha256", requireHash(truthy(field(raw, "dataset_sha256"))
                    ? field(raw, "dataset_sha256") : JSON.textNode(dataset),
                    "exposure entry " + index + ".dataset_sha256"));
            if (!definedNonNull(field(raw, "observed_at"))) row.set("observed_at", NullNode.instance);
            else row.put("observed_at", iso(field(raw, "observed_at"), "exposure entry " + index + ".observed_at"));
            row.put("source", truthy(field(raw, "source")) ? jsString(field(raw, "source")) : "STATISTICAL_SEARCH");
            row.put("sequence", index + 1);
            row.put("previous_sha256", index == 0 ? hash("V5-STAT-GENESIS") : hash(rows.get(index - 1)));
            if (defined(field(raw, "definition_sha256"))) row.put("definition_sha256",
                    requireHash(field(raw, "definition_sha256"), "exposure entry " + index + ".definition_sha256"));
            if (defined(field(raw, "vector_commitment_sha256"))) row.put("vector_commitment_sha256",
                    requireHash(field(raw, "vector_commitment_sha256"),
                            "exposure entry " + index + ".vector_commitment_sha256"));
            rows.add(row);
        }
        long attempts = definedNonNull(field(options, "exposureAttemptK"))
                ? integer(field(options, "exposureAttemptK"), Long.MIN_VALUE) : rows.size();
        if (attempts < rows.size()) throw failure("exposure attempt K must cover behavioral K");
        ObjectNode pointerInput = object();
        pointerInput.put("hypothesis_family", family);
        pointerInput.put("last_entry_sha256", rows.isEmpty()
                ? hash("V5-STAT-GENESIS") : hash(rows.get(rows.size() - 1)));
        ObjectNode result = object();
        result.put("schema", schema("exposure"));
        result.put("version", 1);
        result.put("status", "HEAD");
        result.put("hypothesis_family", family);
        result.put("dataset_sha256", dataset);
        result.set("entries", rows);
        result.put("cumulative_k", rows.size());
        result.put("exposure_attempt_k", attempts);
        result.put("head_pointer_sha256", hash(pointerInput));
        return finalizeExposureHead(result);
    }

    public static ObjectNode appendExposureHead(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        ObjectNode prior = validateExposureHead(field(options, "prior"));
        String dataset = requireHash(field(options, "datasetSha256"), "dataset_sha256");
        ArrayNode priorEntries = array(field(prior, "entries"));
        ArrayNode rows = priorEntries.deepCopy();
        Set<String> known = new HashSet<>();
        priorEntries.forEach(row -> known.add(text(field(row, "behavior_sha256"))));
        Set<String> distinct = new LinkedHashSet<>();
        array(field(options, "behaviorAliases")).forEach(value -> distinct.add(jsString(value)));
        List<String> aliases = new ArrayList<>(distinct);
        aliases.sort(String::compareTo);
        JsonNode definitions = field(options, "behaviorDefinitions");
        JsonNode commitments = field(options, "vectorCommitments");
        for (String behavior : aliases) {
            requireHash(JSON.textNode(behavior), "behavior_alias_sha256");
            if (!known.add(behavior)) continue;
            ObjectNode row = object();
            row.put("behavior_sha256", behavior);
            row.put("dataset_sha256", dataset);
            if (!definedNonNull(field(options, "observedAt"))) row.set("observed_at", NullNode.instance);
            else row.put("observed_at", iso(field(options, "observedAt"), "observed_at"));
            row.put("source", truthy(field(options, "source")) ? jsString(field(options, "source")) : "STATISTICAL_SEARCH");
            row.put("sequence", rows.size() + 1);
            row.put("previous_sha256", rows.isEmpty() ? hash("V5-STAT-GENESIS") : hash(rows.get(rows.size() - 1)));
            if (defined(field(definitions, behavior))) row.put("definition_sha256",
                    requireHash(field(definitions, behavior), "behaviorDefinitions." + behavior));
            if (defined(field(commitments, behavior))) row.put("vector_commitment_sha256",
                    requireHash(field(commitments, behavior), "vectorCommitments." + behavior));
            rows.add(row);
        }
        long priorAttempts = defined(field(prior, "exposure_attempt_k"))
                ? integer(field(prior, "exposure_attempt_k"), 0) : integer(field(prior, "cumulative_k"), 0);
        long increment = definedNonNull(field(options, "exposureAttemptCount"))
                ? integer(field(options, "exposureAttemptCount"), Long.MIN_VALUE) : array(field(options, "behaviorAliases")).size();
        if (increment < 0) throw failure("exposure attempt increment is invalid");
        ObjectNode next = object();
        next.put("hypothesisFamily", text(field(prior, "hypothesis_family")));
        next.put("datasetSha256", dataset);
        next.set("entries", rows);
        next.put("exposureAttemptK", priorAttempts + increment);
        return makeExposureHead(next);
    }

    public static ObjectNode readExposureHeadFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) throw failure("exposure head path is required");
        JsonNode value;
        try { value = MAPPER.readTree(Files.readString(Path.of(filePath), StandardCharsets.UTF_8)); }
        catch (Exception error) { throw failure("cannot read exposure head: " + error.getMessage()); }
        return validateExposureHead(value);
    }

    public static ObjectNode readExposureHeadFile(Path filePath) {
        return readExposureHeadFile(filePath == null ? null : filePath.toString());
    }

    public static ObjectNode initializeExposureHeadFile(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        ObjectNode head = validateExposureHead(field(options, "head"));
        String rawPath = text(field(options, "filePath"));
        try { writeExclusiveJson(requiredFilePath(rawPath, "exposure head path"), head); }
        catch (Exception error) {
            throw failure("exposure head already exists or cannot be initialized: " + error.getMessage());
        }
        return readExposureHeadFile(rawPath);
    }

    public static ObjectNode appendExposureHeadFile(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        String rawPath = text(field(options, "filePath"));
        Path target = requiredFilePath(rawPath, "exposure head path");
        String expected = requireHash(field(options, "expectedHeadSha256"), "expected_head_sha256");
        String dataset = requireHash(field(options, "datasetSha256"), "dataset_sha256");
        Path lock = Path.of(target.toString() + ".lock");
        FileChannel lockChannel = null;
        try {
            ensureParent(target); lockChannel = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            ObjectNode prior = readExposureHeadFile(target);
            if (!expected.equals(text(field(prior, "content_sha256")))) {
                throw failure("stale or competing exposure head predecessor");
            }
            ObjectNode append = object(); append.set("prior", prior); append.put("datasetSha256", dataset);
            append.set("behaviorAliases", cloneNode(field(options, "behaviorAliases")));
            append.set("behaviorDefinitions", cloneNode(field(options, "behaviorDefinitions")));
            append.set("vectorCommitments", cloneNode(field(options, "vectorCommitments")));
            append.set("observedAt", defined(field(options, "observedAt"))
                    ? cloneNode(field(options, "observedAt")) : NullNode.instance);
            append.put("source", truthy(field(options, "source"))
                    ? jsString(field(options, "source")) : "STATISTICAL_SEARCH");
            if (defined(field(options, "exposureAttemptCount"))) {
                append.set("exposureAttemptCount", cloneNode(field(options, "exposureAttemptCount")));
            }
            ObjectNode next = appendExposureHead(append);
            ObjectNode current = readExposureHeadFile(target);
            if (!expected.equals(text(field(current, "content_sha256")))) {
                throw failure("exposure head changed during append");
            }
            writeAtomicJson(target, next); return readExposureHeadFile(target);
        } catch (java.nio.file.FileAlreadyExistsException error) {
            throw failure("competing exposure head writer is active");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw failure(error.getMessage());
        } finally {
            if (lockChannel != null) try { lockChannel.close(); } catch (IOException ignored) {}
            try { Files.deleteIfExists(lock); } catch (IOException ignored) {}
        }
    }

    public static boolean validateBehaviorDefinitionRegistry(JsonNode registry) {
        return validateBehaviorDefinitionRegistry(registry, object());
    }

    public static boolean validateBehaviorDefinitionRegistry(JsonNode registry, ObjectNode args) {
        assertOwnHash(registry, schema("behaviorRegistry"), "behavior-definition registry");
        assertKnownKeys(registry, Set.of("schema", "version", "status", "hypothesis_family",
                "exposure_head_sha256", "entries", "snapshot_path", "snapshot_content_sha256",
                "snapshot_byte_sha256", "content_sha256"), "behavior-definition registry");
        if (!"HEAD".equals(text(field(registry, "status")))
                || text(field(registry, "hypothesis_family")).isEmpty() || !field(registry, "entries").isArray()) {
            throw failure("behavior-definition registry status/family is invalid");
        }
        boolean anySnapshot = defined(field(registry, "snapshot_path"))
                || defined(field(registry, "snapshot_content_sha256")) || defined(field(registry, "snapshot_byte_sha256"));
        if (anySnapshot) {
            String pointer = text(field(registry, "snapshot_path"));
            if (pointer.isEmpty() || !HASH_RE.matcher(text(field(registry, "snapshot_content_sha256"))).matches()
                    || !HASH_RE.matcher(text(field(registry, "snapshot_byte_sha256"))).matches()) {
                throw failure("behavior-definition registry snapshot binding is incomplete");
            }
            String normalized = pointer.replace('\\', '/');
            if (pointer.startsWith("/") || pointer.startsWith("\\")
                    || List.of(normalized.split("/")).contains("..")) {
                throw failure("behavior-definition registry snapshot path must be portable and relative");
            }
        }
        requireHash(field(registry, "exposure_head_sha256"),
                "behavior-definition registry exposure_head_sha256");
        ObjectNode exposure = field(args, "exposureHead").isObject()
                ? validateExposureHead(field(args, "exposureHead")) : null;
        if (exposure != null && !text(field(registry, "exposure_head_sha256"))
                .equals(text(field(exposure, "content_sha256")))) {
            throw failure("behavior-definition registry/exposure head lineage mismatch");
        }
        Set<String> seen = new HashSet<>();
        String previous = hash("V5-STAT-BEHAVIOR-REGISTRY-GENESIS");
        ArrayNode entries = array(field(registry, "entries"));
        for (int index = 0; index < entries.size(); index++) {
            JsonNode entry = entries.get(index);
            assertKnownKeys(entry, Set.of("behavior_sha256", "definition_sha256", "dataset_sha256",
                    "observed_at", "source", "sequence", "previous_sha256", "chromosome",
                    "evaluator_sha256", "precommit_sha256", "lifecycle_sha256"),
                    "behavior-definition registry entry " + index);
            if (integer(field(entry, "sequence"), Long.MIN_VALUE) != index + 1
                    || !previous.equals(text(field(entry, "previous_sha256")))) {
                throw failure("behavior-definition registry chain is broken");
            }
            String behavior = requireHash(field(entry, "behavior_sha256"),
                    "behavior-definition registry entry " + index + ".behavior_sha256");
            requireHash(field(entry, "definition_sha256"),
                    "behavior-definition registry entry " + index + ".definition_sha256");
            requireHash(field(entry, "dataset_sha256"),
                    "behavior-definition registry entry " + index + ".dataset_sha256");
            requireHash(field(entry, "evaluator_sha256"),
                    "behavior-definition registry entry " + index + ".evaluator_sha256");
            if (!field(entry, "precommit_sha256").isNull()) requireHash(field(entry, "precommit_sha256"),
                    "behavior-definition registry entry " + index + ".precommit_sha256");
            if (!field(entry, "lifecycle_sha256").isNull()) requireHash(field(entry, "lifecycle_sha256"),
                    "behavior-definition registry entry " + index + ".lifecycle_sha256");
            if (!field(entry, "chromosome").isObject()) {
                throw failure("behavior-definition registry entry " + index + " lacks a physical chromosome definition");
            }
            if (!field(entry, "observed_at").isNull()) iso(field(entry, "observed_at"),
                    "behavior-definition registry entry " + index + ".observed_at");
            if (!behaviorDefinitionSha256(entry).equals(text(field(entry, "definition_sha256")))) {
                throw failure("behavior-definition registry entry " + index + " definition hash is invalid");
            }
            if (!seen.add(behavior)) throw failure("behavior-definition registry contains duplicate behavior aliases");
            previous = hash(entry);
        }
        if (exposure != null) {
            Map<String, JsonNode> definitions = new LinkedHashMap<>();
            entries.forEach(entry -> definitions.put(text(field(entry, "behavior_sha256")), entry));
            Set<String> aliases = new HashSet<>();
            array(field(exposure, "entries")).forEach(entry -> aliases.add(text(field(entry, "behavior_sha256"))));
            for (JsonNode entry : entries) if (!aliases.contains(text(field(entry, "behavior_sha256")))) {
                throw failure("behavior-definition registry contains an alias absent from the exposure head: "
                        + text(field(entry, "behavior_sha256")));
            }
            for (JsonNode exposureEntry : array(field(exposure, "entries"))) {
                String behavior = text(field(exposureEntry, "behavior_sha256"));
                JsonNode definition = definitions.get(behavior);
                if (definition == null) throw failure("exposure head behavior " + behavior
                        + " has no durable physical definition");
                if (defined(field(exposureEntry, "definition_sha256"))
                        && !text(field(exposureEntry, "definition_sha256"))
                        .equals(text(field(definition, "definition_sha256")))) {
                    throw failure("exposure head definition commitment differs from durable registry for " + behavior);
                }
            }
        }
        return true;
    }

    public static ObjectNode makeBehaviorDefinitionRegistry(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        ObjectNode exposure = field(options, "exposureHead").isObject()
                ? validateExposureHead(field(options, "exposureHead")) : null;
        JsonNode headNode = exposure == null ? field(options, "exposureHeadSha256")
                : field(exposure, "content_sha256");
        String headSha = requireHash(headNode, "behavior-definition registry exposure_head_sha256");
        ArrayNode input = array(field(options, "entries")); ArrayNode rows = array(); Set<String> seen = new HashSet<>();
        for (int index = 0; index < input.size(); index++) {
            JsonNode raw = input.get(index);
            String behavior = requireHash(field(raw, "behavior_sha256"),
                    "behavior-definition registry entry " + index + ".behavior_sha256");
            if (!seen.add(behavior)) throw failure("behavior-definition registry entries must be unique");
            JsonNode chromosome = cloneNode(field(raw, "chromosome"));
            if (!chromosome.isObject()) throw failure("behavior-definition registry entry " + index + ".chromosome is invalid");
            ObjectNode row = object(); row.put("behavior_sha256", behavior);
            row.put("definition_sha256", behaviorDefinitionSha256(raw));
            row.put("dataset_sha256", requireHash(field(raw, "dataset_sha256"),
                    "behavior-definition registry entry " + index + ".dataset_sha256"));
            if (!definedNonNull(field(raw, "observed_at"))) row.set("observed_at", NullNode.instance);
            else row.put("observed_at", iso(field(raw, "observed_at"),
                    "behavior-definition registry entry " + index + ".observed_at"));
            row.put("source", truthy(field(raw, "source")) ? jsString(field(raw, "source")) : "STATISTICAL_SEARCH");
            row.put("sequence", index + 1); row.put("previous_sha256",
                    index == 0 ? hash("V5-STAT-BEHAVIOR-REGISTRY-GENESIS") : hash(rows.get(index - 1)));
            row.set("chromosome", chromosome);
            row.put("evaluator_sha256", requireHash(field(raw, "evaluator_sha256"),
                    "behavior-definition registry entry " + index + ".evaluator_sha256"));
            if (!defined(field(raw, "precommit_sha256")) || field(raw, "precommit_sha256").isNull()) {
                row.set("precommit_sha256", NullNode.instance);
            } else row.put("precommit_sha256", requireHash(field(raw, "precommit_sha256"),
                    "behavior-definition registry entry " + index + ".precommit_sha256"));
            if (!defined(field(raw, "lifecycle_sha256")) || field(raw, "lifecycle_sha256").isNull()) {
                row.set("lifecycle_sha256", NullNode.instance);
            } else row.put("lifecycle_sha256", requireHash(field(raw, "lifecycle_sha256"),
                    "behavior-definition registry entry " + index + ".lifecycle_sha256"));
            rows.add(row);
        }
        ObjectNode value = object(); value.put("schema", schema("behaviorRegistry")); value.put("version", 1);
        value.put("status", "HEAD"); value.put("hypothesis_family", jsString(field(options, "hypothesisFamily")));
        value.put("exposure_head_sha256", headSha); value.set("entries", rows);
        ObjectNode result = withHash(value); ObjectNode validation = object();
        if (exposure != null) validation.set("exposureHead", exposure);
        validateBehaviorDefinitionRegistry(result, validation); validateRegisteredSchema(result); return result;
    }

    public static ObjectNode appendBehaviorDefinitionRegistry(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        ObjectNode exposure = validateExposureHead(field(options, "exposureHead"));
        String expected = definedNonNull(field(options, "expectedExposureHeadSha256"))
                ? text(field(options, "expectedExposureHeadSha256")) : null;
        if (expected != null && expected.equals(text(field(exposure, "content_sha256")))) {
            throw failure("behavior-definition registry append requires the new exposure head plus its predecessor");
        }
        ObjectNode prior = field(options, "prior").isObject() ? objectOrEmpty(field(options, "prior")) : null;
        if (prior != null) {
            validateBehaviorDefinitionRegistry(prior);
            if (expected != null && !expected.equals(text(field(prior, "exposure_head_sha256")))) {
                throw failure("behavior-definition registry predecessor is stale");
            }
            if (!text(field(prior, "hypothesis_family")).equals(text(field(exposure, "hypothesis_family")))) {
                throw failure("behavior-definition registry family differs from exposure head");
            }
        }
        ArrayNode rows = prior == null ? array() : array(field(prior, "entries")).deepCopy();
        Map<String, JsonNode> known = new LinkedHashMap<>();
        rows.forEach(row -> known.put(text(field(row, "behavior_sha256")), row));
        Set<String> headAliases = new HashSet<>();
        array(field(exposure, "entries")).forEach(row -> headAliases.add(text(field(row, "behavior_sha256"))));
        List<JsonNode> definitions = new ArrayList<>(); array(field(options, "definitions")).forEach(definitions::add);
        definitions.sort(Comparator.comparing(row -> text(field(row, "behavior_sha256"))));
        for (JsonNode raw : definitions) {
            String behavior = requireHash(field(raw, "behavior_sha256"), "behavior definition alias");
            if (!headAliases.contains(behavior)) throw failure("behavior definition " + behavior + " is absent from exposure head");
            JsonNode chromosome = cloneNode(field(raw, "chromosome"));
            ObjectNode normalized = objectOrEmpty(raw).deepCopy(); normalized.put("behavior_sha256", behavior);
            normalized.set("chromosome", chromosome);
            if (!truthy(field(normalized, "dataset_sha256"))) normalized.set("dataset_sha256", field(exposure, "dataset_sha256"));
            if (!truthy(field(normalized, "evaluator_sha256"))) normalized.set("evaluator_sha256", field(raw, "evaluator_spec_sha256"));
            if (!defined(field(normalized, "precommit_sha256"))) normalized.set("precommit_sha256", NullNode.instance);
            if (!defined(field(normalized, "lifecycle_sha256"))) normalized.set("lifecycle_sha256", NullNode.instance);
            String definitionSha = behaviorDefinitionSha256(normalized); JsonNode existing = known.get(behavior);
            if (existing != null) {
                if (!definitionSha.equals(text(field(existing, "definition_sha256")))
                        || !stable(effectiveExecutionBehavior(field(existing, "chromosome")))
                        .equals(stable(effectiveExecutionBehavior(chromosome)))) {
                    throw failure("behavior definition " + behavior + " changed after exposure");
                }
                continue;
            }
            ObjectNode row = object(); row.put("behavior_sha256", behavior); row.put("definition_sha256", definitionSha);
            row.put("dataset_sha256", requireHash(field(normalized, "dataset_sha256"), behavior + ".dataset_sha256"));
            if (!definedNonNull(field(normalized, "observed_at"))) row.set("observed_at", NullNode.instance);
            else row.put("observed_at", iso(field(normalized, "observed_at"), behavior + ".observed_at"));
            row.put("source", truthy(field(normalized, "source")) ? jsString(field(normalized, "source")) : "STATISTICAL_SEARCH");
            row.put("sequence", rows.size() + 1); row.put("previous_sha256",
                    rows.isEmpty() ? hash("V5-STAT-BEHAVIOR-REGISTRY-GENESIS") : hash(rows.get(rows.size() - 1)));
            row.set("chromosome", chromosome); row.put("evaluator_sha256",
                    requireHash(field(normalized, "evaluator_sha256"), behavior + ".evaluator_sha256"));
            if (field(normalized, "precommit_sha256").isNull()) row.set("precommit_sha256", NullNode.instance);
            else row.put("precommit_sha256", requireHash(field(normalized, "precommit_sha256"), behavior + ".precommit_sha256"));
            if (field(normalized, "lifecycle_sha256").isNull()) row.set("lifecycle_sha256", NullNode.instance);
            else row.put("lifecycle_sha256", requireHash(field(normalized, "lifecycle_sha256"), behavior + ".lifecycle_sha256"));
            rows.add(row); known.put(behavior, row);
        }
        ObjectNode make = object(); make.put("hypothesisFamily", truthy(field(options, "hypothesisFamily"))
                ? jsString(field(options, "hypothesisFamily")) : text(field(exposure, "hypothesis_family")));
        make.set("exposureHead", exposure); make.set("entries", rows);
        ObjectNode result = makeBehaviorDefinitionRegistry(make);
        if (array(field(result, "entries")).size() != array(field(exposure, "entries")).size()) {
            throw failure("behavior-definition registry is incomplete for the exposure head");
        }
        return result;
    }

    public static ObjectNode readBehaviorDefinitionRegistryFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) throw failure("behavior-definition registry path is required");
        JsonNode value;
        try { value = MAPPER.readTree(Files.readString(Path.of(filePath), StandardCharsets.UTF_8)); }
        catch (Exception error) { throw failure("cannot read behavior-definition registry: " + error.getMessage()); }
        validateBehaviorDefinitionRegistry(value); return objectOrEmpty(value);
    }

    public static ObjectNode readBehaviorDefinitionRegistryFile(Path filePath) {
        return readBehaviorDefinitionRegistryFile(filePath == null ? null : filePath.toString());
    }

    public static ObjectNode appendBehaviorDefinitionRegistryFile(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        Path target = requiredFilePath(text(field(options, "filePath")), "behavior-definition registry path");
        ObjectNode exposure = validateExposureHead(field(options, "exposureHead"));
        Path lock = Path.of(target + ".lock"); FileChannel lockChannel = null;
        try {
            ensureParent(target); lockChannel = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            ObjectNode existing = Files.exists(target) ? readBehaviorDefinitionRegistryFile(target) : null;
            if (existing != null && truthy(field(options, "expectedRegistrySha256"))
                    && !text(field(options, "expectedRegistrySha256")).equals(text(field(existing, "content_sha256")))) {
                throw failure("stale or competing behavior-definition registry predecessor");
            }
            if (existing != null && truthy(field(options, "priorExposureHeadSha256"))
                    && !text(field(options, "priorExposureHeadSha256"))
                    .equals(text(field(existing, "exposure_head_sha256")))) {
                throw failure("behavior-definition registry is not bound to the exposure predecessor");
            }
            if (existing != null && !truthy(field(options, "priorExposureHeadSha256"))) {
                throw failure("behavior-definition registry append requires an exposure-head predecessor");
            }
            ObjectNode append = object(); if (existing != null) append.set("prior", existing);
            append.set("exposureHead", exposure); append.set("expectedExposureHeadSha256",
                    defined(field(options, "priorExposureHeadSha256"))
                            ? cloneNode(field(options, "priorExposureHeadSha256")) : NullNode.instance);
            append.set("definitions", cloneNode(field(options, "definitions")));
            ObjectNode next = appendBehaviorDefinitionRegistry(append);
            ObjectNode validation = object(); validation.set("exposureHead", exposure);
            validateBehaviorDefinitionRegistry(next, validation);
            if (existing != null) {
                ObjectNode current = readBehaviorDefinitionRegistryFile(target);
                if (!text(field(current, "content_sha256")).equals(text(field(existing, "content_sha256")))) {
                    throw failure("behavior-definition registry changed during append");
                }
            } else if (Files.exists(target)) {
                throw failure("behavior-definition registry appeared during append");
            }
            writeAtomicJson(target, next); return next;
        } catch (java.nio.file.FileAlreadyExistsException error) {
            throw failure("competing behavior-definition registry writer is active");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw failure(error.getMessage());
        } finally {
            if (lockChannel != null) try { lockChannel.close(); } catch (IOException ignored) {}
            try { Files.deleteIfExists(lock); } catch (IOException ignored) {}
        }
    }

    public static ObjectNode bindBehaviorDefinitionRegistrySnapshotFile(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        Path state = requiredFilePath(text(field(options, "filePath")), "behavior-definition registry state path");
        Path snapshotPath = requiredFilePath(text(field(options, "snapshotPath")),
                "behavior-definition registry snapshot path");
        ObjectNode snapshot = objectOrEmpty(field(options, "snapshot")); validateBehaviorDefinitionRegistry(snapshot);
        Path directory = state.getParent();
        assertConfinedPath(state, "behavior-definition registry state", false, directory);
        Path physicalSnapshot = assertConfinedPath(snapshotPath, "behavior-definition registry snapshot", true, directory);
        String relative = directory.relativize(physicalSnapshot).toString().replace('\\', '/');
        if (relative.isEmpty() || relative.equals(".") || relative.equals("..") || relative.startsWith("../")
                || relative.startsWith("/")) {
            throw failure("behavior-definition registry snapshot must remain inside its state directory");
        }
        requireRegularSingleLink(physicalSnapshot, "behavior-definition registry immutable snapshot");
        byte[] snapshotBytes;
        ObjectNode onDisk;
        try {
            snapshotBytes = Files.readAllBytes(physicalSnapshot); onDisk = objectOrEmpty(MAPPER.readTree(snapshotBytes));
        } catch (Exception error) {
            throw failure("behavior-definition registry immutable snapshot is not valid JSON");
        }
        validateBehaviorDefinitionRegistry(onDisk);
        if (!text(field(onDisk, "content_sha256")).equals(text(field(snapshot, "content_sha256")))) {
            throw failure("behavior-definition registry immutable snapshot content changed");
        }
        Path lock = Path.of(state + ".lock"); FileChannel lockChannel = null;
        try {
            lockChannel = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("behavior-definition registry state is missing before snapshot bind");
            }
            assertConfinedPath(state, "behavior-definition registry state", true, directory);
            ObjectNode existing = readBehaviorDefinitionRegistryFile(state);
            if (truthy(field(options, "expectedRegistrySha256"))
                    && !text(field(options, "expectedRegistrySha256")).equals(text(field(existing, "content_sha256")))) {
                throw failure("stale or competing behavior-definition registry state predecessor");
            }
            if (!text(field(existing, "hypothesis_family")).equals(text(field(snapshot, "hypothesis_family")))) {
                throw failure("behavior-definition registry snapshot family differs from state predecessor");
            }
            ArrayNode priorEntries = array(field(existing, "entries")); ArrayNode snapshotEntries = array(field(snapshot, "entries"));
            if (snapshotEntries.size() < priorEntries.size()) {
                throw failure("behavior-definition registry snapshot rolls back the state predecessor");
            }
            for (int index = 0; index < priorEntries.size(); index++) {
                if (!stable(priorEntries.get(index)).equals(stable(snapshotEntries.get(index)))) {
                    throw failure("behavior-definition registry snapshot does not preserve the state predecessor lineage at "
                            + index + ": " + text(field(priorEntries.get(index), "behavior_sha256")) + " != "
                            + (snapshotEntries.size() > index
                                    ? text(field(snapshotEntries.get(index), "behavior_sha256")) : "missing"));
                }
            }
            ObjectNode value = snapshot.deepCopy(); value.put("snapshot_path", relative);
            value.put("snapshot_content_sha256", text(field(snapshot, "content_sha256")));
            value.put("snapshot_byte_sha256", hash(snapshotBytes)); ObjectNode next = withHash(value);
            validateBehaviorDefinitionRegistry(next); validateRegisteredSchema(next); writeAtomicJson(state, next); return next;
        } catch (java.nio.file.FileAlreadyExistsException error) {
            throw failure("competing behavior-definition registry state writer is active");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw failure(error.getMessage());
        } finally {
            if (lockChannel != null) try { lockChannel.close(); } catch (IOException ignored) {}
            try { Files.deleteIfExists(lock); } catch (IOException ignored) {}
        }
    }

    public static ObjectNode resolveBehaviorDefinitionRegistrySnapshotFile(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        Path state = requiredFilePath(text(field(options, "filePath")), "behavior-definition registry state path");
        Path directory = state.getParent(); assertConfinedPath(state, "behavior-definition registry state", true, directory);
        ObjectNode registry = field(options, "registry").isObject()
                ? objectOrEmpty(field(options, "registry")) : readBehaviorDefinitionRegistryFile(state);
        if (!truthy(field(registry, "snapshot_path"))) return null;
        String pointer = text(field(registry, "snapshot_path")); String normalized = pointer.replace('\\', '/');
        if (pointer.startsWith("/") || pointer.startsWith("\\") || List.of(normalized.split("/")).contains("..")) {
            throw failure("behavior-definition registry snapshot pointer is not portable or confined");
        }
        Path target = directory.resolve(pointer).toAbsolutePath().normalize();
        if (target.equals(directory) || !target.startsWith(directory)) {
            throw failure("behavior-definition registry snapshot pointer escapes its state directory");
        }
        assertConfinedPath(target, "behavior-definition registry snapshot pointer", true, directory);
        requireRegularSingleLink(target, "behavior-definition registry snapshot pointer");
        byte[] bytes;
        try { bytes = Files.readAllBytes(target); }
        catch (IOException error) { throw failure("behavior-definition registry snapshot pointer cannot be read"); }
        if (!hash(bytes).equals(text(field(registry, "snapshot_byte_sha256")))) {
            throw failure("behavior-definition registry immutable snapshot bytes are tampered");
        }
        ObjectNode snapshot;
        try { snapshot = objectOrEmpty(MAPPER.readTree(bytes)); }
        catch (IOException error) { throw failure("behavior-definition registry immutable snapshot is not valid JSON"); }
        validateBehaviorDefinitionRegistry(snapshot);
        if (!text(field(snapshot, "content_sha256")).equals(text(field(registry, "snapshot_content_sha256")))) {
            throw failure("behavior-definition registry immutable snapshot content binding differs from state");
        }
        if (!stable(behaviorRegistrySemantic(registry)).equals(stable(behaviorRegistrySemantic(snapshot)))) {
            throw failure("behavior-definition registry state semantic contents differ from its immutable snapshot");
        }
        ObjectNode result = object(); result.put("path", target.toString()); result.set("value", snapshot);
        result.put("byte_sha256", hash(bytes)); return result;
    }

    public static ObjectNode writeExposureRegistryJournal(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        if (!truthy(field(options, "journalPath"))) throw failure("registry journal path is required");
        Path target = Path.of(jsString(field(options, "journalPath"))).toAbsolutePath().normalize();
        ObjectNode value = registryJournalValue(options);
        try {
            ensureParent(target); writeExclusiveJson(target, value); return value;
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            try {
                JsonNode existing = MAPPER.readTree(Files.readString(target, StandardCharsets.UTF_8));
                assertOwnHash(existing, schema("registryJournal"), "registry journal");
                if (text(field(existing, "content_sha256")).equals(text(field(value, "content_sha256")))) {
                    return objectOrEmpty(existing);
                }
            } catch (Exception ignored) {
                // A malformed or different prepared transaction is a competing writer.
            }
            throw failure("registry journal already exists or cannot be prepared: " + exists.getMessage());
        } catch (Exception error) {
            throw failure("registry journal already exists or cannot be prepared: " + error.getMessage());
        }
    }

    public static ObjectNode recoverExposureRegistryTransaction(ObjectNode args) {
        ObjectNode options = args == null ? object() : args; JsonNode rawPath = field(options, "journalPath");
        if (!truthy(rawPath) || !Files.exists(Path.of(jsString(rawPath)), LinkOption.NOFOLLOW_LINKS)) {
            ObjectNode none = object(); none.put("status", "NONE");
            if (truthy(rawPath)) none.set("journal_path", cloneNode(rawPath)); else none.putNull("journal_path");
            return none;
        }
        Path journalPath = Path.of(jsString(rawPath)); ObjectNode journal;
        try { journal = objectOrEmpty(MAPPER.readTree(Files.readString(journalPath, StandardCharsets.UTF_8))); }
        catch (Exception error) { throw failure("registry journal is unreadable: " + error.getMessage()); }
        assertOwnHash(journal, schema("registryJournal"), "registry journal"); validateContractSchema(journal);
        if (!"PREPARED".equals(text(field(journal, "status")))) throw failure("registry journal status is invalid");
        ObjectNode head = readExposureHeadFile(text(field(journal, "exposure_head_path")));
        String headSha = text(field(head, "content_sha256")); String priorHead = text(field(journal, "prior_head_sha256"));
        String nextHead = text(field(journal, "next_head_sha256"));
        if (!headSha.equals(priorHead) && !headSha.equals(nextHead)) {
            throw failure("registry journal exposure head is neither the recorded predecessor nor successor");
        }
        Path registryPath = Path.of(text(field(journal, "registry_path")));
        ObjectNode existing = Files.exists(registryPath, LinkOption.NOFOLLOW_LINKS)
                ? readBehaviorDefinitionRegistryFile(registryPath) : null;
        if (headSha.equals(priorHead)) {
            String expectedRegistry = truthy(field(journal, "prior_registry_sha256"))
                    ? jsString(field(journal, "prior_registry_sha256")) : null;
            if (existing == null || !java.util.Objects.equals(text(field(existing, "content_sha256")), expectedRegistry)) {
                throw failure("registry journal predecessor registry is inconsistent");
            }
            try { Files.delete(journalPath.toAbsolutePath().normalize()); }
            catch (IOException error) { throw failure(error.getMessage()); }
            ObjectNode result = object(); result.put("status", "ABORTED_BEFORE_HEAD_COMMIT");
            result.set("journal_path", cloneNode(rawPath)); result.put("head_sha256", headSha); return result;
        }
        ObjectNode registry;
        if (existing != null && nextHead.equals(text(field(existing, "exposure_head_sha256")))) {
            ObjectNode validate = object(); validate.set("exposureHead", head);
            validateBehaviorDefinitionRegistry(existing, validate); registry = existing;
        } else {
            ObjectNode append = object(); append.put("filePath", registryPath.toString());
            if (existing == null) append.putNull("expectedRegistrySha256");
            else append.put("expectedRegistrySha256", text(field(existing, "content_sha256")));
            append.put("priorExposureHeadSha256", priorHead); append.set("exposureHead", head);
            append.set("definitions", cloneNode(field(journal, "definitions")));
            registry = appendBehaviorDefinitionRegistryFile(append);
        }
        if (!nextHead.equals(text(field(registry, "exposure_head_sha256")))) {
            throw failure("recovered behavior registry does not bind the committed exposure head");
        }
        ObjectNode validate = object(); validate.set("exposureHead", head);
        validateBehaviorDefinitionRegistry(registry, validate);
        try { Files.delete(journalPath.toAbsolutePath().normalize()); }
        catch (IOException error) { throw failure(error.getMessage()); }
        ObjectNode result = object(); result.put("status", "RECOVERED_REGISTRY");
        result.set("journal_path", cloneNode(rawPath)); result.put("head_sha256", headSha);
        result.put("registry_sha256", text(field(registry, "content_sha256"))); return result;
    }

    private static ObjectNode registryJournalValue(ObjectNode options) {
        ObjectNode prior = validateExposureHead(field(options, "priorHead"));
        ObjectNode next = validateExposureHead(field(options, "nextHead"));
        if (text(field(next, "content_sha256")).equals(text(field(prior, "content_sha256")))) {
            throw failure("registry journal requires a new exposure head");
        }
        ObjectNode value = object(); value.put("schema", schema("registryJournal")); value.put("version", 1);
        value.put("status", "PREPARED");
        value.put("exposure_head_path", Path.of(jsString(field(options, "exposureHeadPath")))
                .toAbsolutePath().normalize().toString());
        value.put("registry_path", Path.of(jsString(field(options, "registryPath")))
                .toAbsolutePath().normalize().toString());
        value.put("prior_head_sha256", text(field(prior, "content_sha256")));
        value.put("next_head_sha256", text(field(next, "content_sha256")));
        if (definedNonNull(field(options, "priorRegistrySha256"))) {
            value.set("prior_registry_sha256", cloneNode(field(options, "priorRegistrySha256")));
        } else value.putNull("prior_registry_sha256");
        value.set("next_head", next.deepCopy()); ArrayNode definitions = array();
        for (JsonNode definition : array(field(options, "definitions"))) definitions.add(cloneNode(definition));
        value.set("definitions", definitions);
        if (truthy(field(options, "journalPath"))) value.put("journal_path", Path.of(
                jsString(field(options, "journalPath"))).toAbsolutePath().normalize().toString());
        else value.putNull("journal_path");
        ObjectNode result = withHash(value); validateContractSchema(result); return result;
    }

    public static ObjectNode runNestedWfoV5(ObjectNode args) {
        return runNestedWfoV5(args, null, null, null, null, null, null, null);
    }

    public static ObjectNode runNestedWfoV5(ObjectNode args, StrategyEvaluatorV5.Evaluator evaluator,
            StatisticalProvider stressProvider, StatisticalProvider portfolioProvider,
            StatisticalProvider oosVectorProvider) {
        return runNestedWfoV5(args, evaluator, stressProvider, portfolioProvider, oosVectorProvider,
                null, null, null);
    }

    /** Java binding for the provider-valued nested WFO export without a physical null adapter. */
    public static ObjectNode runNestedWfoV5(ObjectNode args, StrategyEvaluatorV5.Evaluator evaluator,
            StatisticalProvider stressProvider, StatisticalProvider portfolioProvider,
            StatisticalProvider oosVectorProvider, NullReplaySuite replay,
            CheckpointPathFactory checkpointPathFactory) {
        return runNestedWfoV5(args, evaluator, stressProvider, portfolioProvider, oosVectorProvider,
                replay, checkpointPathFactory, null);
    }

    /**
     * Full Java binding for the provider-valued nested WFO export.
     *
     * <p>Node receives {@code config.nullSelectionRunner} as a function-valued property. Jackson
     * trees cannot represent functions, so Java injects the equivalent verified physical runner
     * explicitly. A missing runner retains Node's fail-closed unsupported-null-control result.</p>
     */
    public static ObjectNode runNestedWfoV5(ObjectNode args, StrategyEvaluatorV5.Evaluator evaluator,
            StatisticalProvider stressProvider, StatisticalProvider portfolioProvider,
            StatisticalProvider oosVectorProvider, NullReplaySuite replay,
            CheckpointPathFactory checkpointPathFactory, PhysicalNullRunner nullSelectionRunner) {
        ObjectNode options = args == null ? object() : args; JsonNode artifact = field(options, "artifact");
        if (artifact.isArray()) throw failure("nested WFO requires a canonical artifact, not raw rows");
        ObjectNode head = validateExposureHead(field(options, "exposureHead")); ObjectNode validation = object();
        validation.set("exposureHead", head); validation.put("allowSubset", true);
        validateStatisticalArtifactSet(artifact, validation);
        if (evaluator == null || stressProvider == null || portfolioProvider == null || oosVectorProvider == null) {
            throw failure("nested WFO requires evaluator, stress, portfolio and OOS vector providers");
        }
        String mode = defined(field(options, "mode"))
                ? jsString(field(options, "mode")).toUpperCase(Locale.ROOT) : "AUTHORITATIVE";
        requireGeneticEvaluator(evaluator, mode); ObjectNode config = field(options, "config").isObject()
                ? objectOrEmpty(field(options, "config")) : object();
        if (!"FIXTURE".equals(mode)) {
            requireFrozenHardPolicy(field(config, "constraints"), "authoritative nested hard acceptance policy");
            if ((!truthy(field(config, "checkpointDirectory")) && checkpointPathFactory == null)
                    || !truthy(field(config, "exposureHeadPath"))) {
                throw failure("authoritative nested WFO requires deterministic per-search checkpoints and one canonical exposure HEAD path");
            }
            if (!truthy(field(config, "prospectiveCutoff"))) {
                throw failure("authoritative nested WFO requires a declared prospective cutoff for the post-WFO development refit");
            }
            double nullIterations = definedNonNull(field(config, "nullIterations"))
                    ? numberJs(field(config, "nullIterations")) : ((Number) STAT_DEFAULTS.get("nullIterations")).doubleValue();
            double batch = definedNonNull(field(config, "nullSequentialBatchSize"))
                    ? numberJs(field(config, "nullSequentialBatchSize"))
                    : ((Number) STAT_DEFAULTS.get("nullSequentialBatchSize")).doubleValue();
            if (nullIterations != ((Number) STAT_DEFAULTS.get("nullIterations")).doubleValue()
                    || batch != ((Number) STAT_DEFAULTS.get("nullSequentialBatchSize")).doubleValue()) {
                throw failure("authoritative null Monte Carlo budget and sequential batch schedule are frozen");
            }
        }
        ObjectNode foldArgs = object(); foldArgs.set("episodes", cloneNode(field(artifact, "episodes")));
        if (defined(field(options, "endAt"))) foldArgs.set("endAt", cloneNode(field(options, "endAt")));
        ArrayNode folds = makeQuarterlyFolds(foldArgs);
        if (truthy(field(config, "checkpointDirectory"))) try {
            Files.createDirectories(Path.of(jsString(field(config, "checkpointDirectory"))));
        } catch (IOException error) { throw failure(error.getMessage()); }
        ArrayNode observedAssets = array(); for (JsonNode row : array(field(artifact, "episodes"))) {
            observedAssets.add(cloneNode(field(row, "asset")));
        }
        ObjectNode assetScope = normalizeAssetScope(field(config, "assetScope"), observedAssets, mode);
        List<String> assets = new ArrayList<>(); array(field(assetScope, "trade_assets")).forEach(value -> assets.add(jsString(value)));
        ArrayNode foldArtifacts = array(), outerSelected = array(); Map<String, JsonNode> allEpisodesById = new LinkedHashMap<>();
        for (JsonNode row : array(field(artifact, "episodes"))) allEpisodesById.put(text(field(row, "episode_id")), row);
        for (JsonNode rawFold : folds) {
            ObjectNode fold = objectOrEmpty(rawFold); long trainEnd = strictTime(field(fold, "train_end"), "timestamp");
            long testStart = strictTime(field(fold, "test_start"), "timestamp");
            long testEnd = strictTime(field(fold, "test_end"), "timestamp");
            ArrayNode train = array(), test = array();
            for (JsonNode row : array(field(artifact, "episodes"))) {
                long decision = strictTime(field(row, "decision_time"), "timestamp");
                long resolution = strictTime(field(row, "resolution_time"), "timestamp");
                if (field(row, "eligible").asBoolean(false) && decision < trainEnd && resolution <= trainEnd
                        && availableBy(row, field(fold, "train_end"))) train.add(row);
                if (field(row, "eligible").asBoolean(false) && decision >= testStart && decision < testEnd
                        && resolution <= testEnd && availableBy(row, field(fold, "test_end"))) test.add(row);
            }
            if (train.isEmpty() || test.isEmpty()) {
                ObjectNode rejected = object(); rejected.put("schema", schema("fold")); rejected.put("version", 1);
                rejected.set("fold_id", cloneNode(field(fold, "fold_id"))); rejected.put("status", "REJECTED");
                rejected.put("reason", "MISSING_COMPLETE_TRAIN_OR_TEST_EPISODES");
                rejected.set("train_episode_ids", fieldArray(train, "episode_id"));
                rejected.set("test_episode_ids", fieldArray(test, "episode_id"));
                rejected.set("purge_ms", cloneNode(field(fold, "purge_ms")));
                rejected.set("embargo_ms", cloneNode(field(fold, "embargo_ms")));
                ObjectNode lineage = object(); lineage.set("fold_id", cloneNode(field(fold, "fold_id")));
                lineage.set("train_ids", fieldArray(train, "episode_id")); lineage.set("test_ids", fieldArray(test, "episode_id"));
                lineage.put("exposure_head_sha256", text(field(head, "content_sha256")));
                rejected.put("lineage_sha256", hash(lineage)); foldArtifacts.add(withHash(rejected)); continue;
            }
            ArrayNode assetResults = array(); ObjectNode foldHead = head;
            for (String assetName : assets) {
                ArrayNode assetTrain = filterAsset(train, assetName);
                if (assetTrain.size() < 3) {
                    ObjectNode missing = object(); missing.put("asset", assetName); missing.put("pass", false);
                    missing.put("reason", "INSUFFICIENT_TRAIN_EPISODES"); assetResults.add(makeAssetDecision(missing)); continue;
                }
                List<Long> trainTimes = new ArrayList<>();
                for (JsonNode row : assetTrain) trainTimes.add(strictTime(field(row, "decision_time"), "timestamp"));
                trainTimes.sort(Long::compareTo); ArrayNode innerFolds = makeInnerFolds(fold, assetName, trainTimes, trainEnd);
                boolean invalidChronology = false;
                for (JsonNode inner : innerFolds) if (strictTime(field(inner, "validation_start"), "timestamp")
                        >= strictTime(field(inner, "validation_end"), "timestamp")) invalidChronology = true;
                if (invalidChronology) {
                    ObjectNode missing = object(); missing.put("asset", assetName); missing.put("pass", false);
                    missing.put("reason", "INVALID_INNER_CHRONOLOGY"); assetResults.add(makeAssetDecision(missing)); continue;
                }
                ArrayNode innerRuns = array();
                for (JsonNode rawInner : innerFolds) {
                    ObjectNode inner = objectOrEmpty(rawInner); Set<String> fitIds = new LinkedHashSet<>();
                    Set<String> validationIds = new LinkedHashSet<>();
                    for (JsonNode row : assetTrain) {
                        long decision = strictTime(field(row, "decision_time"), "timestamp");
                        long resolution = strictTime(field(row, "resolution_time"), "timestamp");
                        long fitEnd = strictTime(field(inner, "train_end"), "timestamp");
                        long validationStart = strictTime(field(inner, "validation_start"), "timestamp");
                        long validationEnd = strictTime(field(inner, "validation_end"), "timestamp");
                        if (decision < fitEnd && resolution <= fitEnd && availableBy(row, field(inner, "train_end"))) {
                            fitIds.add(text(field(row, "episode_id")));
                        }
                        if (decision >= validationStart && decision < validationEnd && resolution <= validationEnd
                                && availableBy(row, field(inner, "validation_end"))) {
                            validationIds.add(text(field(row, "episode_id")));
                        }
                    }
                    Set<String> overlap = new HashSet<>(fitIds); overlap.retainAll(validationIds);
                    if (fitIds.isEmpty() || validationIds.isEmpty() || !overlap.isEmpty()) continue;
                    ArrayNode scopedEpisodes = array(); for (JsonNode row : array(field(artifact, "episodes"))) {
                        String id = text(field(row, "episode_id")); if (fitIds.contains(id) || validationIds.contains(id)) scopedEpisodes.add(row);
                    }
                    ObjectNode innerArtifact = subsetArtifact(artifact, foldHead, scopedEpisodes,
                            metadata("INNER_TRAIN_ONLY", field(fold, "fold_id"), assetName,
                                    field(inner, "inner_fold_id"), null, null));
                    ObjectNode pathArgs = checkpointArgs(field(fold, "fold_id"), assetName,
                            field(inner, "inner_fold_id"), "INNER");
                    String checkpointPath = nestedCheckpointPath(config, checkpointPathFactory, pathArgs,
                            text(field(fold, "fold_id")) + "-" + assetName + "-"
                                    + text(field(inner, "inner_fold_id")) + ".json");
                    if (!"FIXTURE".equals(mode) && checkpointPath == null) {
                        throw failure("nested authoritative inner GA checkpoint is missing");
                    }
                    JsonNode resume = checkpointPath != null && Files.exists(Path.of(checkpointPath))
                            ? readGeneticCheckpointFile(checkpointPath) : NullNode.instance;
                    ObjectNode search = geneticCall(innerArtifact, field(options, "geneSpace"), fitIds,
                            foldHead, config, field(config, "constraints"), mode, field(inner, "inner_fold_id"),
                            field(inner, "train_end"), checkpointPath, resume);
                    ObjectNode selectedInner = runGeneticSearchV5(search,
                            scopedInnerEvaluator(evaluator, fitIds, validationIds, inner));
                    foldHead = objectOrEmpty(field(selectedInner, "exposureHead"));
                    JsonNode selectedChromosome = field(field(field(selectedInner, "run"), "selected"), "chromosome");
                    if (!truthy(selectedChromosome)) continue; ArrayNode validationRows = array();
                    for (JsonNode row : array(field(artifact, "episodes"))) {
                        if (validationIds.contains(text(field(row, "episode_id")))) validationRows.add(row);
                    }
                    ObjectNode record = inner.deepCopy(); record.set("lineage", cloneNode(field(artifact, "lineage")));
                    record.set("candidates", cloneNode(field(artifact, "candidates")));
                    List<String> sortedFit = new ArrayList<>(fitIds); sortedFit.sort(String::compareTo);
                    List<String> sortedValidation = new ArrayList<>(validationIds); sortedValidation.sort(String::compareTo);
                    record.set("fit_episode_ids", strings(sortedFit)); record.set("validation_episode_ids", strings(sortedValidation));
                    record.set("validation_rows", validationRows);
                    record.set("selected_behavior_alias_sha256", cloneNode(field(field(selectedInner, "run"), "selected_behavior_alias_sha256")));
                    record.set("selected_chromosome", cloneNode(selectedChromosome));
                    record.set("selected_seed_count", cloneNode(field(field(selectedInner, "run"), "selected_seed_count")));
                    record.set("genetic_run", cloneNode(field(selectedInner, "run")));
                    ObjectNode candidate = object(); candidate.put("chromosome_sha256", hash(selectedChromosome));
                    candidate.set("chromosome", cloneNode(selectedChromosome)); candidate.set("source_inner_fold_ids",
                            strings(List.of(text(field(inner, "inner_fold_id"))))); candidate.set("source_seed_ids", array());
                    ObjectNode forward = aggregateInnerValidationCandidate(candidate, List.of(record), mode,
                            assetName, field(fold, "fold_id"), foldHead, evaluator);
                    if (forward == null || array(field(forward, "validation_runs")).size() != 1) continue;
                    record.set("validation_metrics", cloneNode(field(forward, "metrics")));
                    record.put("validation_evaluation_sha256", hash(array(field(forward, "validation_runs")).get(0)));
                    record.set("validation_candidate_source_inner_fold_id", cloneNode(field(inner, "inner_fold_id")));
                    ArrayNode validationAliases = array(); if (truthy(field(field(forward, "metrics"), "behavior_alias_sha256"))) {
                        validationAliases.add(cloneNode(field(field(forward, "metrics"), "behavior_alias_sha256")));
                    }
                    foldHead = appendObservedExposure(foldHead, textOrNull(field(config, "exposureHeadPath")),
                            text(field(field(artifact, "lineage"), "dataset_sha256")), validationAliases,
                            object(), object(), 1, field(inner, "validation_end"), "FORWARD_INNER_VALIDATION");
                    innerRuns.add(record);
                }
                if (innerRuns.size() != innerFolds.size()) {
                    ObjectNode missing = object(); missing.put("asset", assetName); missing.put("pass", false);
                    missing.put("reason", "INCOMPLETE_FORWARD_INNER_VALIDATION"); missing.set("inner_folds", innerRuns);
                    assetResults.add(makeAssetDecision(missing)); continue;
                }
                String procedureId = selectionProcedureId(field(options, "geneSpace"), config,
                        field(config, "constraints"), mode);
                ObjectNode procedureValidation = aggregateForwardProcedureValidation(innerRuns,
                        truthy(field(config, "bootstrapIterations")) ? numberJs(field(config, "bootstrapIterations")) : 512,
                        truthy(field(config, "seed")) ? numberJs(field(config, "seed")) : 11);
                ObjectNode assetTrainArtifact = subsetArtifact(artifact, foldHead, assetTrain,
                        metadata("OUTER_TRAIN_FRESH_GA_REFIT", field(fold, "fold_id"), assetName,
                                NullNode.instance, procedureId, null));
                String refitFoldId = text(field(fold, "fold_id")) + "-" + assetName + "-FULL-OUTER-TRAIN";
                ObjectNode pathArgs = checkpointArgs(field(fold, "fold_id"), assetName, NullNode.instance,
                        "FULL_OUTER_TRAIN_REFIT");
                String refitPath = nestedCheckpointPath(config, checkpointPathFactory, pathArgs, refitFoldId + ".json");
                if (!"FIXTURE".equals(mode) && refitPath == null) {
                    throw failure("fresh full-outer-train GA checkpoint is missing");
                }
                JsonNode refitResume = refitPath != null && Files.exists(Path.of(refitPath))
                        ? readGeneticCheckpointFile(refitPath) : NullNode.instance;
                ObjectNode refitCall = geneticCall(assetTrainArtifact, field(options, "geneSpace"),
                        textSet(fieldArray(assetTrain, "episode_id")), foldHead, config, field(config, "constraints"),
                        mode, JSON.textNode(refitFoldId), field(fold, "train_end"), refitPath, refitResume);
                ObjectNode refit = runGeneticSearchV5(refitCall, evaluator); foldHead = objectOrEmpty(field(refit, "exposureHead"));
                JsonNode selectedDefinition = field(field(field(refit, "run"), "selected"), "chromosome");
                String selectedAlias = text(field(field(refit, "run"), "selected_behavior_alias_sha256"));
                long selectedSeedCount = integer(field(field(refit, "run"), "selected_seed_count"), 0);
                JsonNode refitMetrics = field(field(field(field(refit, "run"), "selected"), "fitness"), "metrics");
                ArrayNode testAsset = filterAsset(test, assetName);
                if (testAsset.isEmpty() || selectedAlias.isEmpty() || !selectedDefinition.isObject()) {
                    ObjectNode missing = object(); missing.put("asset", assetName); missing.put("pass", false);
                    missing.put("reason", "MISSING_FRESH_OUTER_TRAIN_SELECTION"); missing.set("inner_folds", innerRuns);
                    missing.set("procedure_validation", procedureValidation); assetResults.add(makeAssetDecision(missing)); continue;
                }
                ArrayNode pboCandidates = refitPboDefinitions(field(refit, "run"));
                ObjectNode pbo = fixedTrainingPboPanel(artifact, foldHead, assetTrain, pboCandidates,
                        selectedAlias, evaluator, mode, text(field(fold, "fold_id")) + "-" + assetName,
                        field(fold, "train_end"), config);
                foldHead = appendObservedExposure(foldHead, textOrNull(field(config, "exposureHeadPath")),
                        text(field(field(artifact, "lineage"), "dataset_sha256")),
                        array(field(pbo, "evaluated_behavior_aliases")), object(), object(),
                        integerFromNumber(field(pbo, "evaluation_attempt_count"), 0), field(fold, "train_end"),
                        "OUTER_TRAIN_PBO_PANEL");
                ObjectNode testArtifact = subsetArtifact(artifact, foldHead, testAsset,
                        metadata("OUTER_OOS_UNWEIGHTED", field(fold, "fold_id"), assetName,
                                NullNode.instance, null, null));
                List<String> testAssetIds = new ArrayList<>(); fieldArray(testAsset, "episode_id").forEach(id -> testAssetIds.add(jsString(id)));
                ObjectNode outerTask = object(); outerTask.set("artifact", signalView(testArtifact, testAssetIds,
                        "OUTER_OOS", field(fold, "fold_id"))); outerTask.set("episode_ids", strings(testAssetIds));
                outerTask.set("chromosome", cloneNode(selectedDefinition)); outerTask.putNull("seed"); outerTask.putNull("generation");
                outerTask.put("phase", "OUTER_OOS"); outerTask.set("fold_id", cloneNode(field(fold, "fold_id")));
                outerTask.putNull("cutoff"); outerTask.putNull("fit_cutoff"); outerTask.putNull("evaluation_cutoff");
                outerTask.put("weighting", "UNWEIGHTED_OOS"); outerTask.put("selected_behavior_alias_sha256", selectedAlias);
                ObjectNode outer = evaluator.evaluate(outerTask); ObjectNode testMetrics = validateEvaluatorResult(outer,
                        testArtifact, new LinkedHashSet<>(testAssetIds), "outer OOS " + text(field(fold, "fold_id"))
                                + "/" + assetName, mode, "OUTER_OOS", field(fold, "fold_id"), NullNode.instance,
                        NullNode.instance, NullNode.instance, "UNWEIGHTED_OOS", selectedDefinition);
                ObjectNode foldPolicy = field(config, "constraints").isObject()
                        ? objectOrEmpty(field(config, "constraints")).deepCopy() : config.deepCopy();
                foldPolicy.put("minEpisodes", 0); foldPolicy.put("minExpectancy", Double.NEGATIVE_INFINITY);
                ObjectNode testHard = hardFeasible(testMetrics, foldPolicy); ArrayNode selectedReturns = array();
                for (JsonNode row : testAsset) {
                    String id = text(field(row, "episode_id")); JsonNode ret = field(field(outer, "candidate_returns"), id);
                    ObjectNode value = object(); value.put("episode_id", id); value.set("asset", cloneNode(field(row, "asset")));
                    value.set("decision_time", cloneNode(field(row, "decision_time")));
                    value.set("resolution_time", cloneNode(field(row, "resolution_time")));
                    value.put("net_r", numberJs(field(ret, "net_r"))); value.put("traded", field(ret, "traded").asBoolean(false));
                    selectedReturns.add(value);
                }
                String outerAlias = truthy(field(outer, "behavior_alias_sha256"))
                        ? jsString(field(outer, "behavior_alias_sha256")) : selectedAlias;
                foldHead = appendObservedExposure(foldHead, textOrNull(field(config, "exposureHeadPath")),
                        text(field(field(artifact, "lineage"), "dataset_sha256")), strings(List.of(outerAlias)),
                        object(), object(), 1, field(fold, "test_end"), "OUTER_OOS_SELECTED_EVALUATION");
                ObjectNode plateau = connectedPlateau(field(refit, "run"), selectedAlias,
                        definedNonNull(field(config, "minSize")) ? (int) numberJs(field(config, "minSize"))
                                : ((Number) STAT_DEFAULTS.get("minPlateau")).intValue(),
                        definedNonNull(field(config, "minNeighbourFraction"))
                                ? numberJs(field(config, "minNeighbourFraction"))
                                : ((Number) STAT_DEFAULTS.get("minNeighbourFraction")).doubleValue());
                boolean pboPass = Double.isFinite(numberJs(field(pbo, "pbo")))
                        && numberJs(field(pbo, "valid_combinations")) >= 2
                        && numberJs(field(pbo, "candidate_count")) >= 2
                        && numberJs(field(pbo, "pbo")) <= (definedNonNull(field(config, "maxPbo"))
                        ? numberJs(field(config, "maxPbo")) : ((Number) STAT_DEFAULTS.get("maxPbo")).doubleValue());
                ObjectNode lineageInput = object(); lineageInput.set("fold_id", cloneNode(field(fold, "fold_id")));
                lineageInput.put("asset", assetName); lineageInput.put("selected_alias", outerAlias);
                lineageInput.put("selection_procedure_sha256", procedureId);
                lineageInput.put("fresh_refit_sha256", text(field(field(refit, "run"), "content_sha256")));
                lineageInput.put("exposure_head_sha256", text(field(foldHead, "content_sha256")));
                lineageInput.put("test_artifact_sha256", text(field(testArtifact, "content_sha256")));
                String lineageSha = hash(lineageInput); ObjectNode stressArgs = object();
                stressArgs.set("artifact", testArtifact); stressArgs.put("selected_candidate_id", outerAlias);
                stressArgs.set("fold_id", cloneNode(field(fold, "fold_id"))); stressArgs.put("asset", assetName);
                stressArgs.put("lineage_sha256", lineageSha); ObjectNode stress = stressProvider.provide(stressArgs);
                validateBoundDecision(stress, "stress", lineageSha, text(field(testArtifact, "content_sha256")), outerAlias);
                ObjectNode decision = object(); decision.put("asset", assetName);
                decision.put("pass", field(procedureValidation, "pass").asBoolean(false) && pboPass
                        && field(testHard, "feasible").asBoolean(false) && field(stress, "pass").asBoolean(false));
                decision.put("selected_candidate_id", outerAlias); decision.put("selected_behavior_alias_sha256", outerAlias);
                decision.put("selected_seed_count", selectedSeedCount); decision.set("selected_chromosome", cloneNode(selectedDefinition));
                decision.set("selected_return_vector", selectedReturns);
                decision.set("training_weighted_bootstrap_p20", definedNonNull(field(refitMetrics, "weighted_bootstrap_p20"))
                        ? cloneNode(field(refitMetrics, "weighted_bootstrap_p20")) : NullNode.instance);
                decision.put("selection_procedure_sha256", procedureId); decision.set("procedure_validation", procedureValidation);
                decision.set("pbo", pbo); decision.put("pbo_pass", pboPass); decision.set("inner_folds", innerRuns);
                decision.put("genetic_sha256", text(field(field(refit, "run"), "content_sha256")));
                decision.set("genetic_run", cloneNode(field(refit, "run"))); decision.set("metrics", testMetrics);
                decision.set("refit_metrics", cloneNode(refitMetrics)); decision.set("hard_metric_violations",
                        cloneNode(field(testHard, "violations"))); decision.set("stress", stress);
                decision.put("stress_sha256", text(field(stress, "content_sha256"))); decision.put("lineage_sha256", lineageSha);
                assetResults.add(makeAssetDecision(decision));
            }
            foldHead = appendObservedExposure(foldHead, textOrNull(field(config, "exposureHeadPath")),
                    text(field(field(artifact, "lineage"), "dataset_sha256")), array(), object(), object(),
                    array(field(foldHead, "entries")).size(), field(fold, "test_end"),
                    "OUTER_OOS_CUMULATIVE_VECTOR_MATERIALIZATION");
            ObjectNode foldTestArtifact = subsetArtifact(artifact, foldHead, test,
                    metadata("OUTER_OOS_UNWEIGHTED", field(fold, "fold_id"), null,
                            NullNode.instance, null, null));
            ArrayNode testIds = fieldArray(test, "episode_id"); ObjectNode vectorArgs = object();
            vectorArgs.set("artifact", foldTestArtifact); vectorArgs.set("exposureHead", foldHead);
            vectorArgs.set("episode_ids", testIds); ArrayNode selectedDefinitions = array();
            for (JsonNode row : assetResults) if (truthy(field(row, "selected_candidate_id"))) {
                ObjectNode selected = object(); selected.set("asset", cloneNode(field(row, "asset")));
                selected.set("selected_candidate_id", cloneNode(field(row, "selected_candidate_id")));
                selected.set("chromosome", cloneNode(field(row, "selected_chromosome"))); selectedDefinitions.add(selected);
            }
            vectorArgs.set("selected_definitions", selectedDefinitions); vectorArgs.set("fold_id", cloneNode(field(fold, "fold_id")));
            ObjectNode vector = oosVectorProvider.provide(vectorArgs); validateVectorInventory(vector, foldHead, testIds);
            ObjectNode assetMap = object(); for (JsonNode row : assetResults) assetMap.set(text(field(row, "asset")), cloneNode(row));
            ObjectNode foldLineageInput = object(); foldLineageInput.set("fold_id", cloneNode(field(fold, "fold_id")));
            foldLineageInput.set("train_ids", fieldArray(train, "episode_id")); foldLineageInput.set("test_ids", testIds);
            foldLineageInput.put("head", text(field(foldHead, "content_sha256"))); String foldLineage = hash(foldLineageInput);
            ObjectNode portfolioLineageInput = object(); portfolioLineageInput.set("fold_id", cloneNode(field(fold, "fold_id")));
            portfolioLineageInput.put("test_artifact_sha256", text(field(foldTestArtifact, "content_sha256")));
            portfolioLineageInput.put("asset_decision_sha256", hash(assetResults));
            portfolioLineageInput.put("exposure_head_sha256", text(field(foldHead, "content_sha256")));
            String portfolioLineage = hash(portfolioLineageInput); ObjectNode portfolioArgs = object();
            portfolioArgs.set("artifact", foldTestArtifact); portfolioArgs.set("asset_decisions", assetResults);
            portfolioArgs.set("fold_id", cloneNode(field(fold, "fold_id"))); portfolioArgs.put("lineage_sha256", portfolioLineage);
            ObjectNode portfolio = portfolioProvider.provide(portfolioArgs);
            validateBoundDecision(portfolio, "portfolio", portfolioLineage,
                    text(field(foldTestArtifact, "content_sha256")), null);
            ObjectNode foldResult = object(); foldResult.put("schema", schema("fold")); foldResult.put("version", 1);
            foldResult.set("fold_id", cloneNode(field(fold, "fold_id"))); foldResult.put("status", "EVALUATED");
            foldResult.set("train_episode_ids", fieldArray(train, "episode_id")); foldResult.set("test_episode_ids", testIds);
            foldResult.set("test_start", cloneNode(field(fold, "test_start"))); foldResult.set("test_end", cloneNode(field(fold, "test_end")));
            int censoredTrain = 0, censoredTest = 0; for (JsonNode row : array(field(artifact, "episodes"))) {
                long decision = strictTime(field(row, "decision_time"), "timestamp");
                long resolution = strictTime(field(row, "resolution_time"), "timestamp");
                if (field(row, "eligible").asBoolean(false) && decision < trainEnd && resolution > trainEnd) censoredTrain++;
                if (field(row, "eligible").asBoolean(false) && decision >= testStart && decision < testEnd
                        && resolution > testEnd) censoredTest++;
            }
            foldResult.put("censored_train_count", censoredTrain); foldResult.put("censored_test_count", censoredTest);
            foldResult.set("purge_ms", cloneNode(field(fold, "purge_ms")));
            foldResult.set("embargo_ms", cloneNode(field(fold, "embargo_ms")));
            ObjectNode trainResult = object(); trainResult.set("inner_folds", innerFoldsForAssets(assetResults));
            trainResult.put("selection_phase", "TRAIN_ONLY"); trainResult.put("recency_weighting", "TRAIN_ONLY");
            ArrayNode geneticHashes = array(); for (JsonNode row : assetResults) if (truthy(field(row, "genetic_sha256"))) {
                geneticHashes.add(cloneNode(field(row, "genetic_sha256")));
            }
            trainResult.set("genetic_sha256", geneticHashes); foldResult.set("train", trainResult);
            ObjectNode testResult = object(); testResult.put("weighted_recency", false);
            testResult.put("vector_inventory_sha256", text(field(vector, "content_sha256")));
            testResult.set("asset_decisions", assetResults); ObjectNode portfolioSummary = object();
            portfolioSummary.set("pass", cloneNode(field(portfolio, "pass")));
            portfolioSummary.set("provenance", cloneNode(field(portfolio, "provenance")));
            portfolioSummary.set("lineage_sha256", cloneNode(field(portfolio, "lineage_sha256")));
            portfolioSummary.set("content_sha256", cloneNode(field(portfolio, "content_sha256")));
            testResult.set("portfolio", portfolioSummary); foldResult.set("test", testResult);
            foldResult.put("lineage_sha256", foldLineage); foldArtifacts.add(withHash(foldResult)); head = foldHead;
            ObjectNode outer = object(); outer.set("fold_id", cloneNode(field(fold, "fold_id")));
            outer.set("test_start", cloneNode(field(fold, "test_start"))); outer.set("test_end", cloneNode(field(fold, "test_end")));
            outer.set("asset_decisions", assetMap); outer.set("vector", vector); outer.set("portfolio", portfolio);
            ArrayNode geneticRuns = array(); for (JsonNode row : assetResults) if (truthy(field(row, "genetic_run"))) {
                geneticRuns.add(cloneNode(field(row, "genetic_run")));
            }
            outer.set("genetic_runs", geneticRuns); outerSelected.add(outer);
        }
        return finishNestedWfo(options, artifact, head, assetScope, assets, folds, foldArtifacts, outerSelected,
                allEpisodesById, evaluator, stressProvider, portfolioProvider, replay, checkpointPathFactory,
                nullSelectionRunner, mode, config);
    }

    private static ArrayNode fieldArray(JsonNode rows, String name) {
        ArrayNode output = array(); for (JsonNode row : array(rows)) output.add(cloneNode(field(row, name))); return output;
    }

    private static ArrayNode filterAsset(JsonNode rows, String assetName) {
        ArrayNode output = array(); for (JsonNode row : array(rows)) if (assetName.equals(text(field(row, "asset")))) output.add(row);
        return output;
    }

    private static ArrayNode makeInnerFolds(JsonNode fold, String assetName, List<Long> trainTimes, long trainEnd) {
        ArrayNode output = array(); long purge = ((Number) STAT_DEFAULTS.get("purgeDays")).longValue() * 86_400_000L;
        long embargo = ((Number) STAT_DEFAULTS.get("embargoDays")).longValue() * 86_400_000L;
        for (int innerIndex : new int[] {1, 2}) {
            long rawStart = trainTimes.get((int) Math.floor(trainTimes.size() * innerIndex / 3d));
            long rawEnd = innerIndex == 2 ? trainEnd
                    : trainTimes.get(Math.min(trainTimes.size() - 1,
                    (int) Math.floor(trainTimes.size() * (innerIndex + 1) / 3d)));
            ObjectNode row = object(); row.put("inner_fold_id", text(field(fold, "fold_id")) + "-"
                    + assetName + "-inner-" + innerIndex); row.put("train_end", JS_ISO.format(Instant.ofEpochMilli(rawStart - purge)));
            row.put("validation_start", JS_ISO.format(Instant.ofEpochMilli(rawStart + embargo)));
            row.put("validation_end", JS_ISO.format(Instant.ofEpochMilli(rawEnd)));
            row.put("purge_ms", purge); row.put("embargo_ms", embargo); row.put("recency_weighting", "TRAIN_ONLY");
            output.add(row);
        }
        return output;
    }

    private static ObjectNode metadata(String phase, JsonNode foldId, String assetName,
            JsonNode innerFoldId, String procedureSha, String prospectiveCutoff) {
        ObjectNode value = object(); value.put("phase", phase);
        if (foldId != null && !foldId.isNull() && !foldId.isMissingNode()) value.set("fold_id", cloneNode(foldId));
        if (assetName != null) value.put("asset", assetName);
        if (innerFoldId != null && !innerFoldId.isNull() && !innerFoldId.isMissingNode()) {
            value.set("inner_fold_id", cloneNode(innerFoldId));
        }
        if (procedureSha != null) value.put("selection_procedure_sha256", procedureSha);
        if (prospectiveCutoff != null) value.put("prospective_cutoff", prospectiveCutoff);
        return value;
    }

    private static ObjectNode subsetArtifact(JsonNode source, ObjectNode head, JsonNode episodes, ObjectNode metadata) {
        ObjectNode args = object(); args.set("lineage", cloneNode(field(source, "lineage")));
        args.set("candidates", cloneNode(field(source, "candidates"))); args.set("episodes", cloneNode(episodes));
        args.set("exposureHead", head); args.set("metadata", metadata); args.put("allowSubset", true);
        return makeStatisticalArtifactSet(args);
    }

    private static ObjectNode checkpointArgs(JsonNode foldId, String assetName, JsonNode innerFoldId, String stage) {
        ObjectNode value = object(); value.set("fold_id", cloneNode(foldId)); value.put("asset", assetName);
        value.set("inner_fold_id", innerFoldId == null || innerFoldId.isMissingNode()
                ? NullNode.instance : cloneNode(innerFoldId)); value.put("stage", stage); return value;
    }

    private static String nestedCheckpointPath(ObjectNode config, CheckpointPathFactory factory,
            ObjectNode pathArgs, String defaultFileName) {
        String path = factory == null ? null : factory.checkpointPath(pathArgs.deepCopy());
        if (path == null && truthy(field(config, "checkpointDirectory"))) {
            path = Path.of(jsString(field(config, "checkpointDirectory")), defaultFileName).toString();
        }
        if (path != null) assertGeneticCheckpointPath(JSON.textNode(path), config); return path;
    }

    private static ObjectNode geneticCall(JsonNode artifact, JsonNode geneSpace, Collection<String> ids,
            ObjectNode head, ObjectNode config, JsonNode constraints, String mode, JsonNode foldId,
            JsonNode trainingCutoff, String checkpointPath, JsonNode resume) {
        ObjectNode value = object(); value.set("artifact", cloneNode(artifact)); value.set("geneSpace", cloneNode(geneSpace));
        value.set("trainingEpisodeIds", strings(ids)); value.set("exposureHead", head);
        if (truthy(field(config, "exposureHeadPath"))) value.set("exposureHeadPath", cloneNode(field(config, "exposureHeadPath")));
        value.put("exposureHeadPredecessorSha256", text(field(head, "content_sha256")));
        if (checkpointPath != null) value.put("checkpointPath", checkpointPath);
        if (resume != null && !resume.isNull() && !resume.isMissingNode()) value.set("resumeCheckpoint", cloneNode(resume));
        value.set("constraints", constraints.isObject() ? cloneNode(constraints) : object());
        ObjectNode nestedConfig = config.deepCopy(); nestedConfig.set("trainingCutoff", cloneNode(trainingCutoff));
        value.set("config", nestedConfig); value.put("mode", mode); value.set("foldId", cloneNode(foldId)); return value;
    }

    private static StrategyEvaluatorV5.Evaluator scopedInnerEvaluator(StrategyEvaluatorV5.Evaluator delegate,
            Set<String> fitIds, Set<String> validationIds, ObjectNode inner) {
        List<String> fit = new ArrayList<>(fitIds), validation = new ArrayList<>(validationIds);
        fit.sort(String::compareTo); validation.sort(String::compareTo);
        return new StrategyEvaluatorV5.Evaluator() {
            private ObjectNode bind(ObjectNode raw, boolean batch) {
                for (JsonNode id : array(field(raw, "episode_ids"))) if (!fitIds.contains(jsString(id))) {
                    throw failure(batch ? "inner evaluator batch received future or validation fit IDs"
                            : "inner evaluator received future or validation fit IDs");
                }
                if (!batch) for (JsonNode id : array(field(raw, "fit_episode_ids"))) if (!fitIds.contains(jsString(id))) {
                    throw failure("inner evaluator received future or validation fit IDs");
                }
                ObjectNode bound = raw.deepCopy(); bound.set("fit_episode_ids", strings(fit));
                bound.set("validation_episode_ids", strings(validation)); ArrayNode folds = array(); folds.add(inner.deepCopy());
                bound.set("inner_folds", folds); return bound;
            }
            @Override public ObjectNode evaluate(ObjectNode value) { return delegate.evaluate(bind(value, false)); }
            @Override public List<ObjectNode> evaluateBatch(List<ObjectNode> values) {
                return delegate.evaluateBatch(values.stream().map(value -> bind(value, true)).toList());
            }
            @Override public ObjectNode workerProvenance() {
                ObjectNode value = delegate.workerProvenance(); return value == null ? null : value.deepCopy();
            }
        };
    }

    private static ObjectNode aggregateInnerValidationCandidate(ObjectNode candidate, List<ObjectNode> records,
            String mode, String assetName, JsonNode foldId, ObjectNode foldHead,
            StrategyEvaluatorV5.Evaluator evaluator) {
        ArrayNode validations = array();
        for (ObjectNode record : records) {
            ArrayNode validationRows = array(field(record, "validation_rows")); Set<String> validationIds =
                    new LinkedHashSet<>(textSet(field(record, "validation_episode_ids")));
            ObjectNode validationArtifact = subsetArtifactFromParts(field(record, "lineage"),
                    field(record, "candidates"), validationRows, foldHead,
                    metadata("INNER_VALIDATION", foldId, assetName, field(record, "inner_fold_id"), null, null));
            List<String> ids = new ArrayList<>(validationIds); ObjectNode task = object();
            task.set("artifact", signalView(validationArtifact, ids, "INNER_VALIDATION", field(record, "inner_fold_id")));
            task.set("episode_ids", strings(ids)); task.set("chromosome", cloneNode(field(candidate, "chromosome")));
            task.putNull("seed"); task.putNull("generation"); task.put("phase", "INNER_VALIDATION");
            task.set("fold_id", cloneNode(field(record, "inner_fold_id"))); task.set("cutoff", cloneNode(field(record, "train_end")));
            task.set("fit_cutoff", cloneNode(field(record, "train_end")));
            task.set("evaluation_cutoff", cloneNode(field(record, "validation_end")));
            task.put("weighting", "UNWEIGHTED_VALIDATION"); task.set("fit_episode_ids", cloneNode(field(record, "fit_episode_ids")));
            task.set("validation_episode_ids", strings(ids)); ArrayNode inner = array(); inner.add(record.deepCopy());
            task.set("inner_folds", inner); ObjectNode result = evaluator.evaluate(task);
            ObjectNode metrics = validateEvaluatorResult(result, validationArtifact, validationIds,
                    "inner validation " + text(field(record, "inner_fold_id")) + "/"
                            + text(field(candidate, "chromosome_sha256")), mode, "INNER_VALIDATION",
                    field(record, "inner_fold_id"), field(record, "train_end"), field(record, "train_end"),
                    field(record, "validation_end"), "UNWEIGHTED_VALIDATION", field(candidate, "chromosome"));
            ObjectNode validation = object(); validation.set("inner_fold_id", cloneNode(field(record, "inner_fold_id")));
            validation.set("metrics", metrics); validations.add(validation);
        }
        List<ObjectNode> returns = new ArrayList<>(); for (JsonNode value : validations) {
            returns.addAll(nodeObjects(array(field(field(value, "metrics"), "episode_returns"))));
        }
        returns.sort(Comparator.comparingLong((ObjectNode row) -> strictTime(field(row, "decision_time"), "timestamp"))
                .thenComparing(row -> text(field(row, "episode_id")))); Set<String> seen = new HashSet<>();
        for (ObjectNode row : returns) if (!seen.add(text(field(row, "episode_id")))) {
            throw failure("inner validation candidate " + text(field(candidate, "chromosome_sha256"))
                    + " has overlapping validation episodes");
        }
        if (returns.isEmpty()) return null; ObjectNode supplied = aggregateSuppliedMetrics(validations, false);
        ArrayNode metricRows = array(); for (ObjectNode row : returns) {
            ObjectNode value = object(); value.set("episode_id", cloneNode(field(row, "episode_id")));
            value.set("asset", cloneNode(field(row, "asset"))); value.set("decision_time", cloneNode(field(row, "decision_time")));
            value.set("resolution_time", cloneNode(field(row, "resolution_time")));
            value.put("value", numberJs(field(row, "net_r"))); value.put("traded", field(row, "traded").asBoolean(false));
            metricRows.add(value);
        }
        ObjectNode aggregate = metricsFromRows(metricRows, null, ((Number) STAT_DEFAULTS.get("halfLifeMonths")).doubleValue(),
                object(), supplied); List<String> behaviorAliases = new ArrayList<>(), signalAliases = new ArrayList<>();
        ArrayNode intents = array(); for (JsonNode value : validations) {
            JsonNode metrics = field(value, "metrics"); String behavior = text(field(metrics, "behavior_alias_sha256"));
            String signal = text(field(metrics, "signal_behavior_alias_sha256"));
            if (!behavior.isEmpty() && !behaviorAliases.contains(behavior)) behaviorAliases.add(behavior);
            if (!signal.isEmpty() && !signalAliases.contains(signal)) signalAliases.add(signal);
            for (JsonNode intent : array(field(metrics, "signal_intent_vector"))) intents.add(intent);
        }
        List<JsonNode> sortedIntents = new ArrayList<>(); intents.forEach(sortedIntents::add);
        sortedIntents.sort(Comparator.comparing(row -> text(field(row, "episode_id"))));
        if (behaviorAliases.size() != 1 || signalAliases.size() != 1) {
            throw failure("inner validation candidate " + text(field(candidate, "chromosome_sha256"))
                    + " changed semantic identity across validation folds");
        }
        aggregate.put("behavior_alias_sha256", behaviorAliases.getFirst());
        aggregate.put("signal_behavior_alias_sha256", signalAliases.getFirst());
        if (!sortedIntents.isEmpty()) {
            ArrayNode normalized = array(); for (JsonNode raw : sortedIntents) {
                ObjectNode row = object(); row.set("episode_id", cloneNode(field(raw, "episode_id")));
                row.set("intent", cloneNode(field(raw, "intent"))); normalized.add(row);
            }
            aggregate.set("signal_intent_vector", normalized);
        }
        ObjectNode output = candidate.deepCopy(); output.set("validation_runs", validations); output.set("metrics", aggregate);
        output.put("validation_episode_count", returns.size());
        output.put("validation_trade_count", returns.stream().filter(row -> field(row, "traded").asBoolean(false)).count());
        return output;
    }

    private static ObjectNode subsetArtifactFromParts(JsonNode lineage, JsonNode candidates, JsonNode episodes,
            ObjectNode head, ObjectNode metadata) {
        ObjectNode args = object(); args.set("lineage", cloneNode(lineage)); args.set("candidates", cloneNode(candidates));
        args.set("episodes", cloneNode(episodes)); args.set("exposureHead", head); args.set("metadata", metadata);
        args.put("allowSubset", true); return makeStatisticalArtifactSet(args);
    }

    private static ObjectNode aggregateSuppliedMetrics(JsonNode rows, boolean sumTurnover) {
        double cost = Double.NEGATIVE_INFINITY, coverage = Double.POSITIVE_INFINITY;
        double drawdown = Double.POSITIVE_INFINITY, profit = Double.POSITIVE_INFINITY;
        double turnover = sumTurnover ? 0 : Double.NEGATIVE_INFINITY, complexity = Double.NEGATIVE_INFINITY;
        boolean capacity = true;
        for (JsonNode row : array(rows)) {
            JsonNode metrics = field(row, "metrics"); cost = Math.max(cost, numberJs(field(metrics, "cost_r")));
            coverage = Math.min(coverage, numberJs(field(metrics, "coverage_fraction")));
            capacity &= field(metrics, "capacity_pass").asBoolean(false);
            drawdown = Math.min(drawdown, numberJs(field(metrics, "max_drawdown_r")));
            profit = Math.min(profit, numberJs(field(metrics, "profit_factor")));
            turnover = sumTurnover ? turnover + numberJs(field(metrics, "turnover"))
                    : Math.max(turnover, numberJs(field(metrics, "turnover")));
            complexity = Math.max(complexity, numberJs(field(metrics, "complexity")));
        }
        ObjectNode result = object(); result.put("cost_r", cost); result.put("coverage_fraction", coverage);
        result.put("capacity_pass", capacity); result.put("max_drawdown_r", drawdown);
        result.put("profit_factor", profit); result.put("turnover", turnover); result.put("complexity", complexity);
        return result;
    }

    private static ObjectNode appendObservedExposure(ObjectNode prior, String filePath, String datasetSha,
            JsonNode aliases, JsonNode definitions, JsonNode commitments, long attempts, JsonNode observedAt,
            String source) {
        if (array(aliases).isEmpty() && attempts == 0) return prior;
        ObjectNode args = object(); args.put("datasetSha256", datasetSha); args.set("behaviorAliases", cloneNode(aliases));
        args.set("behaviorDefinitions", definitions.isObject() ? cloneNode(definitions) : object());
        args.set("vectorCommitments", commitments.isObject() ? cloneNode(commitments) : object());
        args.put("exposureAttemptCount", attempts); args.set("observedAt", observedAt == null ? NullNode.instance : cloneNode(observedAt));
        args.put("source", source);
        if (filePath != null) {
            args.put("filePath", filePath); args.put("expectedHeadSha256", text(field(prior, "content_sha256")));
            return appendExposureHeadFile(args);
        }
        args.set("prior", prior); return appendExposureHead(args);
    }

    private static ObjectNode aggregateForwardProcedureValidation(ArrayNode innerRuns,
            double bootstrapIterations, double seed) {
        ArrayNode metrics = array(); for (JsonNode row : innerRuns) if (truthy(field(row, "validation_metrics"))) {
            metrics.add(cloneNode(field(row, "validation_metrics")));
        }
        List<ObjectNode> episodeRows = new ArrayList<>(); for (JsonNode metric : metrics) {
            episodeRows.addAll(nodeObjects(array(field(metric, "episode_returns"))));
        }
        episodeRows.sort(Comparator.comparingLong((ObjectNode row) -> strictTime(field(row, "decision_time"), "timestamp"))
                .thenComparing(row -> text(field(row, "episode_id"))));
        if (episodeRows.isEmpty() || metrics.size() != innerRuns.size()) {
            ObjectNode missing = object(); missing.put("pass", false);
            missing.put("reason", "INCOMPLETE_FORWARD_INNER_VALIDATION"); missing.put("fold_count", innerRuns.size());
            missing.put("completed_fold_count", metrics.size()); return missing;
        }
        Set<String> seen = new HashSet<>(); for (ObjectNode row : episodeRows) if (!seen.add(text(field(row, "episode_id")))) {
            throw failure("procedure validation reuses episode " + text(field(row, "episode_id")));
        }
        ArrayNode wrapped = array(); for (JsonNode metric : metrics) { ObjectNode row = object(); row.set("metrics", metric); wrapped.add(row); }
        ObjectNode supplied = aggregateSuppliedMetrics(wrapped, true); ArrayNode values = array();
        for (ObjectNode row : episodeRows) {
            ObjectNode value = object(); value.set("episode_id", cloneNode(field(row, "episode_id")));
            value.set("asset", cloneNode(field(row, "asset"))); value.set("decision_time", cloneNode(field(row, "decision_time")));
            value.set("resolution_time", cloneNode(field(row, "resolution_time")));
            value.put("value", numberJs(field(row, "net_r"))); value.put("traded", field(row, "traded").asBoolean(false));
            values.add(value);
        }
        ObjectNode required = object(); required.put("bootstrapIterations", bootstrapIterations); required.put("seed", seed);
        ObjectNode aggregate = metricsFromRows(values, null, ((Number) STAT_DEFAULTS.get("halfLifeMonths")).doubleValue(),
                required, supplied); ObjectNode output = object();
        output.put("pass", numberJs(field(aggregate, "expectancy_r")) > 0
                && numberJs(field(aggregate, "bootstrap_p20")) > 0);
        output.put("method", "FORWARD_ONLY_INNER_SELECTION_PROCEDURE"); output.put("fold_count", innerRuns.size());
        output.put("completed_fold_count", metrics.size()); output.set("metrics", aggregate); ArrayNode inventory = array();
        for (JsonNode row : innerRuns) {
            ObjectNode item = object(); item.set("inner_fold_id", cloneNode(field(row, "inner_fold_id")));
            item.set("fit_episode_ids", cloneNode(field(row, "fit_episode_ids")));
            item.set("validation_episode_ids", cloneNode(field(row, "validation_episode_ids")));
            item.set("selected_behavior_alias_sha256", cloneNode(field(row, "selected_behavior_alias_sha256")));
            item.set("evaluation_sha256", cloneNode(field(row, "validation_evaluation_sha256"))); inventory.add(item);
        }
        output.put("fold_inventory_sha256", hash(inventory)); return output;
    }

    private static String selectionProcedureId(JsonNode geneSpace, JsonNode config,
            JsonNode constraints, String mode) {
        ObjectNode value = object(); value.put("schema", "strategy-v5-selection-procedure/1");
        value.put("gene_space_sha256", text(field(normalizeGenes(geneSpace), "content_sha256")));
        value.set("genetic_config", geneticConfig(config, mode));
        value.set("constraints", constraints.isObject() ? cloneNode(constraints) : object());
        value.put("selection_rule", "DEB_NSGA_II_THREE_SEED_TRAIN_ONLY"); return hash(value);
    }

    private static ArrayNode refitPboDefinitions(JsonNode run) {
        Set<String> finalistAliases = new HashSet<>(); for (JsonNode row : array(field(run, "seed_runs"))) {
            array(field(row, "finalists")).forEach(value -> finalistAliases.add(jsString(value)));
        }
        List<JsonNode> rows = new ArrayList<>(); for (JsonNode row : array(field(run, "population_history"))) {
            if (finalistAliases.contains(text(field(row, "behavior_alias_sha256")))
                    || "SIMPLE_BASELINE".equals(text(field(row, "operator")))
                    || "BASELINE_ANCHOR".equals(text(field(row, "operator")))) rows.add(row);
        }
        if (field(field(run, "selected"), "chromosome").isObject()) rows.add(field(run, "selected"));
        Map<String, ObjectNode> unique = new LinkedHashMap<>(); for (JsonNode row : rows) {
            String alias = text(field(row, "behavior_alias_sha256")); JsonNode chromosome = field(row, "chromosome");
            if (!chromosome.isObject() || !HASH_RE.matcher(alias).matches()) continue; ObjectNode item = object();
            item.put("candidate_id", alias); item.set("chromosome", cloneNode(chromosome)); item.put("behavior_alias_sha256", alias);
            unique.put(alias, item);
        }
        List<ObjectNode> result = new ArrayList<>(unique.values()); result.sort(Comparator.comparing(row -> text(field(row, "candidate_id"))));
        return toArray(result);
    }

    private static ObjectNode fixedTrainingPboPanel(JsonNode artifact, ObjectNode exposureHead, ArrayNode rows,
            ArrayNode candidates, String selectedAlias, StrategyEvaluatorV5.Evaluator evaluator, String mode,
            String foldId, JsonNode cutoff, JsonNode config) {
        if (!HASH_RE.matcher(selectedAlias).matches() || candidates.size() < 2
                || !containsFieldValue(candidates, "candidate_id", selectedAlias)) {
            return unsupportedPbo(candidates.size(), candidates.size() < 2
                    ? "PBO_REQUIRES_AT_LEAST_TWO_COMPARABLE_CANDIDATES"
                    : "SELECTED_REFIT_CANDIDATE_ABSENT_FROM_PANEL");
        }
        if (rows.size() < 8) return unsupportedPbo(candidates.size(), "PBO_REQUIRES_AT_LEAST_EIGHT_TRAIN_EPISODES");
        List<ArrayNode> partitions = new ArrayList<>(); for (int index = 0; index < 8; index++) {
            ArrayNode partition = array(); int start = (int) Math.floor(rows.size() * index / 8d);
            int end = (int) Math.floor(rows.size() * (index + 1) / 8d);
            for (int item = start; item < end; item++) partition.add(rows.get(item)); if (!partition.isEmpty()) partitions.add(partition);
        }
        if (partitions.size() != 8) return unsupportedPbo(candidates.size(), "PBO_PARTITION_INVENTORY_INCOMPLETE");
        ArrayNode folds = array(); Set<String> evaluatedAliases = new HashSet<>(); int partitionIndex = 0;
        for (ArrayNode partition : partitions) {
            partitionIndex++; String partitionFold = foldId + "-pbo-" + partitionIndex;
            ArrayNode ids = fieldArray(partition, "episode_id"); ObjectNode scoped = subsetArtifact(artifact, exposureHead,
                    partition, metadata("PBO_OUTER_TRAIN_ONLY", JSON.textNode(partitionFold), null,
                            NullNode.instance, null, null)); List<ObjectNode> tasks = new ArrayList<>();
            for (JsonNode candidate : candidates) {
                ObjectNode task = object(); List<String> idValues = new ArrayList<>(); ids.forEach(id -> idValues.add(jsString(id)));
                task.set("artifact", signalView(scoped, idValues, "TRAIN_ONLY", JSON.textNode(partitionFold)));
                task.set("episode_ids", ids.deepCopy()); task.set("chromosome", cloneNode(field(candidate, "chromosome")));
                task.putNull("seed"); task.putNull("generation"); task.put("phase", "TRAIN_ONLY");
                task.put("fold_id", partitionFold); task.set("cutoff", cloneNode(cutoff)); task.set("fit_cutoff", cloneNode(cutoff));
                task.set("evaluation_cutoff", cloneNode(cutoff)); task.put("weighting", "TRAIN_HALF_LIFE"); tasks.add(task);
            }
            List<ObjectNode> results = evaluator.evaluateBatch(tasks); ObjectNode candidateMeans = object();
            ArrayNode observations = array(), behaviorAliases = array();
            for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
                JsonNode candidate = candidates.get(candidateIndex); ObjectNode result = results.get(candidateIndex);
                Set<String> idSet = textSet(ids); ObjectNode metrics = validateEvaluatorResult(result, scoped, idSet,
                        "PBO " + foldId + "/" + partitionIndex + "/" + text(field(candidate, "candidate_id")),
                        mode, "TRAIN_ONLY", JSON.textNode(partitionFold), cutoff, cutoff, cutoff,
                        "TRAIN_HALF_LIFE", field(candidate, "chromosome"));
                if (!text(field(metrics, "behavior_alias_sha256")).equals(text(field(candidate, "candidate_id")))) {
                    throw failure("fixed PBO panel behavior identity changed across a training partition");
                }
                evaluatedAliases.add(text(field(metrics, "behavior_alias_sha256")));
                behaviorAliases.add(text(field(metrics, "behavior_alias_sha256"))); List<Double> returns = new ArrayList<>();
                for (JsonNode id : ids) returns.add(numberJs(field(field(result, "candidate_returns"), jsString(id)).path("net_r")));
                candidateMeans.put(text(field(candidate, "candidate_id")), mean(returns));
            }
            for (JsonNode episode : partition) {
                ObjectNode observation = object(); observation.set("episode_id", cloneNode(field(episode, "episode_id")));
                observation.set("decision_time", cloneNode(field(episode, "decision_time")));
                observation.set("resolution_time", cloneNode(field(episode, "resolution_time")));
                ObjectNode means = object(); for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
                    String candidateId = text(field(candidates.get(candidateIndex), "candidate_id"));
                    means.put(candidateId, numberJs(field(field(results.get(candidateIndex), "candidate_returns"),
                            text(field(episode, "episode_id"))).path("net_r")));
                }
                observation.set("candidate_means", means); observations.add(observation);
            }
            ObjectNode fold = object(); fold.set("candidate_means", candidateMeans); fold.set("observations", observations);
            fold.set("test_start", cloneNode(field(partition.get(0), "decision_time")));
            fold.set("test_end", cloneNode(field(partition.get(partition.size() - 1), "resolution_time")));
            fold.set("behavior_aliases", behaviorAliases); folds.add(fold);
        }
        ObjectNode pboOptions = object(); pboOptions.put("purgeDays", definedNonNull(field(config, "purgeDays"))
                ? numberJs(field(config, "purgeDays")) : ((Number) STAT_DEFAULTS.get("purgeDays")).doubleValue());
        pboOptions.put("embargoDays", definedNonNull(field(config, "embargoDays"))
                ? numberJs(field(config, "embargoDays")) : ((Number) STAT_DEFAULTS.get("embargoDays")).doubleValue());
        pboOptions.put("requireTimestamps", true); ObjectNode pbo = pboFromFolds(folds, selectedAlias, pboOptions);
        pbo.put("candidate_count", candidates.size()); pbo.put("selected_candidate_id", selectedAlias);
        pbo.put("source_phase", "OUTER_TRAIN_ONLY"); pbo.put("outer_oos_bound", false); ArrayNode panel = array();
        for (JsonNode fold : folds) { ObjectNode row = object(); row.set("candidate_means", cloneNode(field(fold, "candidate_means")));
            row.set("observations", cloneNode(field(fold, "observations"))); panel.add(row); }
        pbo.put("panel_sha256", hash(panel)); pbo.put("evaluation_attempt_count", folds.size() * candidates.size());
        List<String> aliases = new ArrayList<>(evaluatedAliases); aliases.sort(String::compareTo);
        pbo.set("evaluated_behavior_aliases", strings(aliases)); return pbo;
    }

    private static ObjectNode unsupportedPbo(int candidateCount, String reason) {
        ObjectNode value = object(); value.putNull("pbo"); value.put("valid_combinations", 0);
        value.put("combinations_total", 0); value.put("candidate_count", candidateCount);
        value.put("method", "UNSUPPORTED_FIXED_TRAIN_PANEL"); value.put("reason", reason);
        value.put("source_phase", "OUTER_TRAIN_ONLY"); value.put("outer_oos_bound", false); return value;
    }

    private static boolean containsFieldValue(JsonNode rows, String fieldName, String expected) {
        for (JsonNode row : array(rows)) if (expected.equals(text(field(row, fieldName)))) return true; return false;
    }

    private static ArrayNode innerFoldsForAssets(JsonNode assetResults) {
        ArrayNode output = array(); for (JsonNode row : array(assetResults)) {
            for (JsonNode inner : array(field(row, "inner_folds"))) output.add(cloneNode(inner));
        }
        return output;
    }

    private static String textOrNull(JsonNode value) { return truthy(value) ? jsString(value) : null; }

    private static ObjectNode mergeVectorInventories(JsonNode inventories, ObjectNode head,
            List<String> episodeIds, Map<String, JsonNode> episodeTimes) {
        ObjectNode merged = object();
        for (JsonNode entry : array(field(head, "entries"))) {
            String alias = text(field(entry, "behavior_sha256")); JsonNode firstInventory = null;
            for (JsonNode inventory : array(inventories)) if (field(field(inventory, "vectors"), alias).isArray()) {
                firstInventory = inventory; break;
            }
            Long firstInventoryTime = null;
            if (firstInventory != null) for (JsonNode id : array(field(firstInventory, "episode_ids"))) {
                JsonNode time = episodeTimes.get(jsString(id)); if (time == null) continue;
                long parsed = strictTime(time, "timestamp");
                if (firstInventoryTime == null || parsed < firstInventoryTime) firstInventoryTime = parsed;
            }
            Long observedAt = truthy(field(entry, "observed_at"))
                    ? strictTime(field(entry, "observed_at"), "timestamp") : null;
            Long boundary = observedAt == null ? firstInventoryTime : firstInventoryTime == null ? observedAt
                    : Math.max(observedAt, firstInventoryTime); ArrayNode rows = array();
            for (String episodeId : episodeIds) {
                JsonNode found = null;
                for (JsonNode inventory : array(inventories)) for (JsonNode candidate :
                        array(field(field(inventory, "vectors"), alias))) {
                    if (episodeId.equals(text(field(candidate, "episode_id")))) { found = candidate; break; }
                }
                if (found != null) rows.add(cloneNode(found)); else {
                    JsonNode time = episodeTimes.get(episodeId); Long episodeTime = time == null ? null
                            : strictTime(time, "timestamp");
                    if (boundary != null && episodeTime != null && episodeTime < boundary) {
                        ObjectNode unavailable = object(); unavailable.put("episode_id", episodeId);
                        unavailable.put("net_r", 0); unavailable.put("traded", false); unavailable.put("eligible", false);
                        rows.add(unavailable);
                    } else throw failure("cumulative behavior alias " + alias
                            + " has no evaluated vector for episode " + episodeId);
                }
            }
            merged.set(alias, rows);
        }
        ObjectNode args = object(); args.set("exposureHead", head); args.set("episodeIds", strings(episodeIds));
        args.set("vectors", merged); return makeVectorInventory(args);
    }

    private static ObjectNode finishNestedWfo(ObjectNode options, JsonNode artifact, ObjectNode head,
            ObjectNode assetScope, List<String> assets, ArrayNode folds, ArrayNode foldArtifacts,
            ArrayNode outerSelected, Map<String, JsonNode> allEpisodesById,
            StrategyEvaluatorV5.Evaluator evaluator, StatisticalProvider stressProvider,
            StatisticalProvider portfolioProvider, NullReplaySuite replay,
            CheckpointPathFactory checkpointPathFactory, PhysicalNullRunner nullSelectionRunner,
            String mode, ObjectNode config) {
        LinkedHashSet<String> oosSet = new LinkedHashSet<>(); ArrayNode inventories = array();
        for (JsonNode outer : outerSelected) {
            JsonNode vector = field(outer, "vector"); inventories.add(cloneNode(vector));
            for (JsonNode id : array(field(vector, "episode_ids"))) if (allEpisodesById.containsKey(jsString(id))) {
                oosSet.add(jsString(id));
            }
        }
        if (oosSet.isEmpty()) throw failure("nested WFO produced no complete outer OOS episodes");
        List<String> oosEpisodes = new ArrayList<>(oosSet); Map<String, JsonNode> episodeTimes = new LinkedHashMap<>();
        for (JsonNode row : array(field(artifact, "episodes"))) episodeTimes.put(text(field(row, "episode_id")),
                field(row, "decision_time"));
        ObjectNode finalVector = mergeVectorInventories(inventories, head, oosEpisodes, episodeTimes);
        List<String> aliases = new ArrayList<>(); for (JsonNode row : array(field(head, "entries"))) {
            aliases.add(text(field(row, "behavior_sha256")));
        }
        ArrayNode auditCandidates = array(); for (String alias : aliases) {
            ObjectNode candidate = object(); candidate.put("candidate_id", "behavior:" + alias);
            candidate.put("behavior_sha256", alias); auditCandidates.add(candidate);
        }
        Map<String, JsonNode> vectorByEpisode = new HashMap<>();
        for (String alias : aliases) for (JsonNode row : array(field(field(finalVector, "vectors"), alias))) {
            vectorByEpisode.put(alias + ":" + text(field(row, "episode_id")), row);
        }
        ArrayNode auditEpisodes = array(); for (JsonNode source : array(field(artifact, "episodes"))) {
            String episodeId = text(field(source, "episode_id")); if (!oosSet.contains(episodeId)) continue;
            ObjectNode episode = objectOrEmpty(source).deepCopy(); ObjectNode returns = object();
            for (String alias : aliases) {
                JsonNode vector = vectorByEpisode.get(alias + ":" + episodeId); ObjectNode ret = object();
                ret.put("net_r", numberJs(field(vector, "net_r"))); ret.put("traded", field(vector, "traded").asBoolean(false));
                returns.set("behavior:" + alias, ret);
            }
            episode.set("candidate_returns", returns); auditEpisodes.add(episode);
        }
        ObjectNode auditLineage = objectOrEmpty(field(artifact, "lineage")).deepCopy();
        auditLineage.put("candidate_set_sha256", hash(auditCandidates)); ObjectNode labelDigest = object();
        labelDigest.put("source", text(field(field(artifact, "lineage"), "label_set_sha256")));
        labelDigest.put("phase", "OUTER_OOS_UNWEIGHTED"); auditLineage.put("label_set_sha256", hash(labelDigest));
        ObjectNode finalArgs = object(); finalArgs.set("lineage", auditLineage); finalArgs.set("candidates", auditCandidates);
        finalArgs.set("episodes", auditEpisodes); finalArgs.set("exposureHead", head);
        ObjectNode finalMetadata = object(); finalMetadata.put("phase", "OUTER_OOS_UNWEIGHTED");
        finalMetadata.put("source_artifact_sha256", text(field(artifact, "content_sha256")));
        finalArgs.set("metadata", finalMetadata); ObjectNode finalArtifact = makeStatisticalArtifactSet(finalArgs);

        ArrayNode allAssetDecisions = array(); for (JsonNode outer : outerSelected) {
            JsonNode decisions = field(outer, "asset_decisions");
            for (String name : fieldNames(decisions)) allAssetDecisions.add(cloneNode(field(decisions, name)));
        }
        ArrayNode selectedFillRows = array(); for (JsonNode decision : allAssetDecisions) {
            for (JsonNode row : array(field(decision, "selected_return_vector"))) selectedFillRows.add(cloneNode(row));
        }
        LinkedHashMap<String, ObjectNode> selectedByEpisode = new LinkedHashMap<>();
        for (JsonNode raw : selectedFillRows) selectedByEpisode.put(text(field(raw, "episode_id")), objectOrEmpty(raw));
        List<ObjectNode> selectedRows = new ArrayList<>(selectedByEpisode.values());
        selectedRows.sort(Comparator.comparingLong((ObjectNode row) -> strictTime(field(row, "decision_time"), "timestamp"))
                .thenComparing(row -> text(field(row, "episode_id")))); ArrayNode selectedOutcomeRows = toArray(selectedRows);
        String selectedCandidate = "selected:oos"; ObjectNode selectedAliasInput = object();
        selectedAliasInput.put("schema", "strategy-v5-statistical-selected-oos-vector/1"); ArrayNode selectedAliasRows = array();
        for (ObjectNode row : selectedRows) { ObjectNode value = object(); value.set("episode_id", cloneNode(field(row, "episode_id")));
            value.set("net_r", cloneNode(field(row, "net_r"))); value.set("traded", cloneNode(field(row, "traded")));
            selectedAliasRows.add(value); }
        selectedAliasInput.set("rows", selectedAliasRows); hash(selectedAliasInput);
        Map<String, Integer> aliasFrequency = new HashMap<>(); for (JsonNode decision : allAssetDecisions) {
            String alias = text(field(decision, "selected_behavior_alias_sha256"));
            if (!alias.isEmpty()) aliasFrequency.merge(alias, 1, Integer::sum);
        }
        List<String> procedureAliases = new ArrayList<>(aliasFrequency.keySet()); procedureAliases.sort(
                Comparator.comparingInt((String alias) -> -aliasFrequency.get(alias)).thenComparing(String::compareTo));
        String nullSelectedCandidate = !selectedRows.isEmpty() ? selectedCandidate
                : procedureAliases.isEmpty() ? null : "behavior:" + procedureAliases.getFirst();
        List<String> decisionAssets = new ArrayList<>(); for (JsonNode decision : allAssetDecisions) {
            String assetName = text(field(decision, "asset")); if (!decisionAssets.contains(assetName)) decisionAssets.add(assetName);
        }
        decisionAssets.sort(String::compareTo); ArrayNode finalAssetDecisions = array();
        for (String assetName : decisionAssets) {
            ArrayNode rows = array(); for (JsonNode decision : allAssetDecisions) if (assetName.equals(text(field(decision, "asset")))) {
                rows.add(cloneNode(decision));
            }
            ObjectNode required = config.deepCopy(); required.put("minPositiveFolds",
                    definedNonNull(field(config, "minPositiveFolds")) ? numberJs(field(config, "minPositiveFolds"))
                            : ((Number) STAT_DEFAULTS.get("minPositiveFolds")).doubleValue());
            finalAssetDecisions.add(aggregateAssetDecision(rows, required));
        }
        ObjectNode nullArtifact = finalArtifact; JsonNode nullEpisodeScope = NullNode.instance;
        if (truthy(field(config, "nullSourceArtifact"))) {
            JsonNode source = field(config, "nullSourceArtifact"); nullArtifact = subsetArtifact(source, head,
                    field(source, "episodes"), metadata("NULL_PHYSICAL_SOURCE", NullNode.instance,
                            null, NullNode.instance, null, null)); nullEpisodeScope = strings(oosEpisodes);
        }
        long nullIterations = definedNonNull(field(config, "nullIterations"))
                ? (long) numberJs(field(config, "nullIterations"))
                : ((Number) STAT_DEFAULTS.get("nullIterations")).longValue();
        long nullBatch = definedNonNull(field(config, "nullSequentialBatchSize"))
                ? (long) numberJs(field(config, "nullSequentialBatchSize"))
                : ((Number) STAT_DEFAULTS.get("nullSequentialBatchSize")).longValue();
        JsonNode nullControls = NullNode.instance;
        if (nullSelectedCandidate != null && (replay != null || "AUTHORITATIVE".equals(mode))) {
            ObjectNode nullArgs = object(); nullArgs.set("artifact", nullArtifact);
            nullArgs.put("selectedCandidateId", nullSelectedCandidate); nullArgs.set("selectedOutcomeRows", selectedOutcomeRows);
            if (!nullEpisodeScope.isNull()) nullArgs.set("selectedEpisodeIds", nullEpisodeScope);
            nullArgs.put("directionalHypothesis", truthy(field(config, "directionalHypothesis"))
                    ? jsString(field(config, "directionalHypothesis")) : "positive");
            nullArgs.put("iterations", nullIterations); nullArgs.put("sequentialBatchSize", nullBatch);
            if (truthy(field(config, "selectionBudget"))) nullArgs.set("selectionBudget", cloneNode(field(config, "selectionBudget")));
            nullArgs.put("mode", mode);
            nullControls = runNullControlsV5(nullArgs, replay, nullSelectionRunner);
        }
        ArrayNode auditFolds = array(); for (JsonNode outer : outerSelected) {
            List<Double> returns = new ArrayList<>(); JsonNode decisions = field(outer, "asset_decisions");
            for (String name : fieldNames(decisions)) for (JsonNode row :
                    array(field(field(decisions, name), "selected_return_vector"))) returns.add(numberJs(field(row, "net_r")));
            ObjectNode fold = object(); if (returns.isEmpty()) fold.putNull("test_expectancy_r");
            else fold.put("test_expectancy_r", mean(returns)); fold.set("test_start", cloneNode(field(outer, "test_start")));
            fold.set("test_end", cloneNode(field(outer, "test_end"))); auditFolds.add(fold);
        }
        List<ObjectNode> geneticRuns = new ArrayList<>(); for (JsonNode outer : outerSelected) {
            geneticRuns.addAll(nodeObjects(array(field(outer, "genetic_runs"))));
        }
        geneticRuns.sort(Comparator.comparing(row -> text(field(row, "fold_id"))));
        JsonNode genetic = geneticRuns.isEmpty() ? NullNode.instance : geneticRuns.getFirst();
        JsonNode selectedMetrics = aggregateSelectedOosMetrics(nodeObjects(selectedFillRows), allAssetDecisions);
        ObjectNode finalPortfolioLineage = object(); finalPortfolioLineage.put("phase", "FINAL_OOS");
        finalPortfolioLineage.put("artifact", text(field(finalArtifact, "content_sha256")));
        finalPortfolioLineage.put("head", text(field(head, "content_sha256")));
        finalPortfolioLineage.put("asset_decisions", hash(allAssetDecisions)); String finalLineage = hash(finalPortfolioLineage);
        ObjectNode portfolioArgs = object(); portfolioArgs.set("artifact", finalArtifact);
        portfolioArgs.set("asset_decisions", allAssetDecisions); portfolioArgs.put("fold_id", "FINAL_OOS");
        portfolioArgs.put("lineage_sha256", finalLineage); ObjectNode finalPortfolio = portfolioProvider.provide(portfolioArgs);
        validateBoundDecision(finalPortfolio, "final portfolio", finalLineage,
                text(field(finalArtifact, "content_sha256")), null);
        JsonNode trainingP20 = field(field(field(genetic, "selected"), "fitness"), "metrics").path("weighted_bootstrap_p20");
        ArrayNode p20s = array(), pboEvidence = array(), stressDecisions = array();
        for (JsonNode decision : allAssetDecisions) {
            if (definedNonNull(field(decision, "training_weighted_bootstrap_p20"))) {
                p20s.add(cloneNode(field(decision, "training_weighted_bootstrap_p20")));
            }
            if (truthy(field(decision, "pbo"))) pboEvidence.add(cloneNode(field(decision, "pbo")));
            if (truthy(field(decision, "stress"))) stressDecisions.add(cloneNode(field(decision, "stress")));
        }
        ObjectNode auditArgs = object(); auditArgs.set("artifact", finalArtifact); auditArgs.set("exposureHead", head);
        auditArgs.put("selectedCandidateId", selectedCandidate); auditArgs.set("selectedOutcomeRows", selectedOutcomeRows);
        auditArgs.set("vectorInventory", finalVector); auditArgs.set("selectedMetrics", cloneNode(selectedMetrics));
        auditArgs.set("trainingWeightedBootstrapP20", definedNonNull(trainingP20) ? cloneNode(trainingP20) : NullNode.instance);
        auditArgs.set("trainingWeightedBootstrapP20s", p20s); auditArgs.set("folds", auditFolds);
        auditArgs.set("genetic", cloneNode(genetic)); auditArgs.set("geneticRuns", toArray(geneticRuns));
        auditArgs.set("nullControls", cloneNode(nullControls)); auditArgs.set("assetDecisions", finalAssetDecisions);
        auditArgs.set("stressDecisions", stressDecisions); auditArgs.set("portfolioDecision", finalPortfolio);
        ObjectNode auditConfig = config.deepCopy(); auditConfig.set("assetScope", assetScope);
        auditConfig.set("outerTrainingPboEvidence", pboEvidence); auditArgs.set("config", auditConfig);
        ObjectNode audit = runStatisticalAuditV5(auditArgs); ObjectNode validationHead = head;

        String prospectiveRaw;
        if (truthy(field(config, "prospectiveCutoff"))) prospectiveRaw = jsString(field(config, "prospectiveCutoff"));
        else if (truthy(field(options, "endAt"))) prospectiveRaw = jsString(field(options, "endAt"));
        else {
            List<String> resolutions = new ArrayList<>(); for (JsonNode row : array(field(artifact, "episodes"))) {
                resolutions.add(text(field(row, "resolution_time")));
            }
            resolutions.sort(String::compareTo); prospectiveRaw = resolutions.getLast();
        }
        String prospectiveCutoff = iso(JSON.textNode(prospectiveRaw), "prospective cutoff");
        String procedureId = selectionProcedureId(field(options, "geneSpace"), config,
                field(config, "constraints"), mode); ArrayNode developmentAssets = array();
        for (String assetName : assets) {
            ArrayNode rows = array(); long cutoffMillis = strictTime(JSON.textNode(prospectiveCutoff), "timestamp");
            for (JsonNode row : array(field(artifact, "episodes"))) {
                if (assetName.equals(text(field(row, "asset"))) && field(row, "eligible").asBoolean(false)
                        && strictTime(field(row, "decision_time"), "timestamp") < cutoffMillis
                        && strictTime(field(row, "resolution_time"), "timestamp") <= cutoffMillis
                        && availableBy(row, JSON.textNode(prospectiveCutoff))) rows.add(row);
            }
            if (rows.isEmpty()) {
                ObjectNode missing = object(); missing.put("asset", assetName); missing.put("status", "REJECTED");
                missing.put("reason", "NO_COMPLETE_DEVELOPMENT_EPISODES"); developmentAssets.add(missing); continue;
            }
            ObjectNode scoped = subsetArtifact(artifact, head, rows,
                    metadata("POST_WFO_FULL_DEVELOPMENT_REFIT", NullNode.instance, assetName,
                            NullNode.instance, procedureId, prospectiveCutoff));
            String refitFoldId = "prospective-" + assetName + "-FULL-DEVELOPMENT";
            String checkpointPath = nestedCheckpointPath(config, checkpointPathFactory,
                    checkpointArgs(JSON.textNode("POST_WFO"), assetName, NullNode.instance,
                            "FULL_DEVELOPMENT_REFIT"), refitFoldId + ".json");
            JsonNode resume = checkpointPath != null && Files.exists(Path.of(checkpointPath))
                    ? readGeneticCheckpointFile(checkpointPath) : NullNode.instance;
            ObjectNode call = geneticCall(scoped, field(options, "geneSpace"), textSet(fieldArray(rows, "episode_id")),
                    head, config, field(config, "constraints"), mode, JSON.textNode(refitFoldId),
                    JSON.textNode(prospectiveCutoff), checkpointPath, resume);
            ObjectNode refit = runGeneticSearchV5(call, evaluator); head = objectOrEmpty(field(refit, "exposureHead"));
            JsonNode run = field(refit, "run"); ObjectNode development = object(); development.put("asset", assetName);
            development.put("status", truthy(field(run, "selected")) ? "SELECTED_FOR_SHADOW" : "REJECTED");
            development.put("source_phase", "FRESH_FULL_DEVELOPMENT_GA");
            development.put("selected_from_outer_fold_winners", false);
            development.put("outer_fold_winner_inventory_used", false);
            development.put("historical_wfo_rows_reclassified_as_development_at_cutoff", true);
            development.put("historical_labels_available_by_cutoff", true);
            development.put("prospective_cutoff", prospectiveCutoff); development.set("training_episode_ids", fieldArray(rows, "episode_id"));
            ArrayNode trainingInventory = array(); for (JsonNode row : rows) {
                ObjectNode item = object(); item.set("episode_id", cloneNode(field(row, "episode_id")));
                item.set("decision_time", cloneNode(field(row, "decision_time")));
                item.set("resolution_time", cloneNode(field(row, "resolution_time"))); trainingInventory.add(item);
            }
            development.put("training_inventory_sha256", hash(trainingInventory));
            development.put("selection_procedure_sha256", procedureId);
            development.put("gene_space_sha256", text(field(field(run, "gene_space"), "content_sha256")));
            development.set("seeds", cloneNode(field(field(run, "config"), "seeds")));
            development.set("selected_behavior_alias_sha256", cloneNode(field(run, "selected_behavior_alias_sha256")));
            development.set("selected_chromosome", truthy(field(field(run, "selected"), "chromosome"))
                    ? cloneNode(field(field(run, "selected"), "chromosome")) : NullNode.instance);
            development.put("genetic_sha256", text(field(run, "content_sha256")));
            development.put("exposure_head_sha256", text(field(head, "content_sha256"))); developmentAssets.add(development);
        }
        boolean allSelected = true; for (JsonNode row : developmentAssets) if (!"SELECTED_FOR_SHADOW".equals(
                text(field(row, "status")))) allSelected = false;
        ObjectNode development = object(); development.put("schema", "strategy-v5-statistical-development-refit/1");
        development.put("version", 1); development.put("status", field(audit, "pass").asBoolean(false) && allSelected
                ? "SHADOW_PENDING_PROSPECTIVE" : "REJECTED"); development.put("activation_status", "SHADOW_ONLY");
        development.put("prospective_cutoff", prospectiveCutoff);
        development.put("source_artifact_sha256", text(field(artifact, "content_sha256")));
        development.put("validation_audit_sha256", text(field(audit, "content_sha256")));
        development.put("validation_exposure_head_sha256", text(field(validationHead, "content_sha256")));
        development.put("exposure_head_sha256", text(field(head, "content_sha256")));
        development.put("selection_procedure_sha256", procedureId);
        development.put("selected_from_outer_fold_winners", false);
        development.put("excluded_from_retrospective_oos_audit", true); development.set("asset_refits", developmentAssets);
        ObjectNode developmentRefit = withHash(development); String decision = field(audit, "pass").asBoolean(false)
                && "SHADOW_PENDING_PROSPECTIVE".equals(text(field(developmentRefit, "status"))) ? "SHADOW" : "REJECTED";
        ObjectNode wfo = object(); wfo.put("schema", schema("wfo")); wfo.put("version", 1); wfo.set("folds", foldArtifacts);
        wfo.put("fold_count", 8); wfo.set("asset_scope", assetScope);
        wfo.put("validation_exposure_head_sha256", text(field(validationHead, "content_sha256")));
        wfo.put("validation_exposure_head_cumulative_k", integer(field(validationHead, "cumulative_k"), 0));
        wfo.set("validation_exposure_head", validationHead); wfo.put("exposure_head_sha256", text(field(head, "content_sha256")));
        wfo.put("cumulative_k", integer(field(head, "cumulative_k"), 0)); wfo.set("oos_episode_ids", strings(oosEpisodes));
        wfo.put("oos_artifact_sha256", text(field(finalArtifact, "content_sha256")));
        wfo.put("vector_inventory_sha256", text(field(finalVector, "content_sha256")));
        wfo.put("oos_weighting", "UNWEIGHTED"); wfo.set("audit", audit); wfo.set("development_refit", developmentRefit);
        wfo.set("asset_decisions", outerSelected); wfo.set("asset_decisions_final", finalAssetDecisions);
        wfo.set("portfolio_decision", finalPortfolio); wfo.put("decision", decision); wfo.put("gate_pass", "SHADOW".equals(decision));
        ObjectNode run = withHash(wfo); validateNestedWfoArtifact(run); validateContractSchema(run);
        ObjectNode output = object(); output.set("run", run); output.set("exposureHead", head); output.set("audit", audit);
        output.set("artifact", finalArtifact); output.set("vectorInventory", finalVector);
        output.set("developmentRefit", developmentRefit); output.set("assetScope", assetScope); return output;
    }

    public static boolean validateNestedWfoArtifact(JsonNode value) {
        assertKnownKeys(value, Set.of("schema", "version", "folds", "fold_count", "asset_scope",
                "validation_exposure_head_sha256", "validation_exposure_head_cumulative_k",
                "validation_exposure_head", "exposure_head_sha256", "cumulative_k", "oos_episode_ids",
                "oos_artifact_sha256", "vector_inventory_sha256", "oos_weighting", "audit",
                "development_refit", "asset_decisions", "asset_decisions_final", "portfolio_decision",
                "decision", "gate_pass", "content_sha256"), "nested WFO artifact");
        assertOwnHash(value, schema("wfo"), "nested WFO artifact");
        if (integer(field(value, "fold_count"), Long.MIN_VALUE) != 8 || !field(value, "folds").isArray()
                || field(value, "folds").size() != 8) {
            throw failure("nested WFO must contain exactly eight outer folds");
        }
        String decision = text(field(value, "decision"));
        if (!Set.of("REJECTED", "SHADOW").contains(decision) || "ACTIVE".equals(decision)
                || !field(value, "gate_pass").isBoolean()
                || field(value, "gate_pass").asBoolean() != "SHADOW".equals(decision)) {
            throw failure("nested WFO decision is not fail-closed");
        }
        if (!truthy(field(value, "asset_scope"))) throw failure("nested WFO is missing immutable asset scope");
        normalizeAssetScope(field(value, "asset_scope"), field(field(value, "asset_scope"), "trade_assets"), "FIXTURE");
        requireHash(field(value, "exposure_head_sha256"), "nested WFO exposure head");
        requireHash(field(value, "validation_exposure_head_sha256"), "nested WFO validation exposure head");
        long cumulative = integer(field(value, "cumulative_k"), Long.MIN_VALUE);
        long validationCumulative = integer(field(value, "validation_exposure_head_cumulative_k"), Long.MIN_VALUE);
        if (cumulative < 1 || validationCumulative < 1 || validationCumulative > cumulative
                || !"UNWEIGHTED".equals(text(field(value, "oos_weighting")))) {
            throw failure("nested WFO cumulative/search weighting contract is invalid");
        }
        ObjectNode validationHead;
        try { validationHead = validateExposureHead(field(value, "validation_exposure_head")); }
        catch (IllegalArgumentException error) {
            throw failure("nested WFO validation exposure HEAD snapshot is invalid: " + error.getMessage());
        }
        if (!text(field(validationHead, "content_sha256")).equals(
                text(field(value, "validation_exposure_head_sha256")))
                || integer(field(validationHead, "cumulative_k"), Long.MIN_VALUE) != validationCumulative) {
            throw failure("nested WFO validation exposure HEAD snapshot does not match its lineage fields");
        }
        for (JsonNode fold : array(field(value, "folds"))) {
            assertOwnHash(fold, schema("fold"), "fold " + text(field(fold, "fold_id")));
        }
        JsonNode audit = field(value, "audit"); validateStatisticalAudit(audit);
        if (!text(field(audit, "exposure_head_sha256")).equals(
                text(field(value, "validation_exposure_head_sha256")))) {
            throw failure("nested WFO audit is not bound to the validation exposure head");
        }
        JsonNode maxStatistic = field(audit, "max_statistic");
        if (!maxStatistic.isObject() || !field(maxStatistic, "cumulative_k").isIntegralNumber()
                || integer(field(maxStatistic, "cumulative_k"), Long.MIN_VALUE) != validationCumulative) {
            throw failure("nested WFO max-statistic cumulative K is not bound to the validation exposure head");
        }
        JsonNode refit = field(value, "development_refit");
        boolean invalidRefit = !refit.isObject()
                || !"strategy-v5-statistical-development-refit/1".equals(text(field(refit, "schema")))
                || !text(field(refit, "content_sha256")).equals(ownHash(refit))
                || !text(field(refit, "validation_audit_sha256")).equals(text(field(audit, "content_sha256")))
                || !text(field(refit, "validation_exposure_head_sha256")).equals(
                text(field(value, "validation_exposure_head_sha256")))
                || !text(field(refit, "exposure_head_sha256")).equals(text(field(value, "exposure_head_sha256")))
                || !(field(refit, "selected_from_outer_fold_winners").isBoolean()
                && !field(refit, "selected_from_outer_fold_winners").asBoolean())
                || !(field(refit, "excluded_from_retrospective_oos_audit").isBoolean()
                && field(refit, "excluded_from_retrospective_oos_audit").asBoolean())
                || !field(refit, "asset_refits").isArray();
        if (!invalidRefit) for (JsonNode row : array(field(refit, "asset_refits"))) {
            if ("SELECTED_FOR_SHADOW".equals(text(field(row, "status")))
                    && (!"FRESH_FULL_DEVELOPMENT_GA".equals(text(field(row, "source_phase")))
                    || !(field(row, "selected_from_outer_fold_winners").isBoolean()
                    && !field(row, "selected_from_outer_fold_winners").asBoolean())
                    || !(field(row, "outer_fold_winner_inventory_used").isBoolean()
                    && !field(row, "outer_fold_winner_inventory_used").asBoolean())
                    || !(field(row, "historical_wfo_rows_reclassified_as_development_at_cutoff").isBoolean()
                    && field(row, "historical_wfo_rows_reclassified_as_development_at_cutoff").asBoolean())
                    || !stable(field(row, "seeds")).equals(stable(MAPPER.valueToTree(STAT_DEFAULTS.get("seeds")))))) {
                invalidRefit = true; break;
            }
        }
        if (invalidRefit) throw failure("nested WFO development refit is missing or may reuse an outer winner");
        if ("SHADOW".equals(decision)) validateShadowWfo(value);
        return true;
    }

    private static void validateShadowWfo(JsonNode value) {
        requireHash(field(value, "oos_artifact_sha256"), "nested WFO OOS artifact");
        requireHash(field(value, "vector_inventory_sha256"), "nested WFO vector inventory");
        ArrayNode oosIds = array(field(value, "oos_episode_ids"));
        if (!field(value, "oos_episode_ids").isArray() || oosIds.isEmpty()
                || uniqueTextCount(oosIds) != oosIds.size()) {
            throw failure("nested WFO SHADOW OOS episode inventory is empty or duplicated");
        }
        List<String> outerTestIds = new ArrayList<>();
        long purge = ((Number) STAT_DEFAULTS.get("purgeDays")).longValue() * 86_400_000L;
        long embargo = ((Number) STAT_DEFAULTS.get("embargoDays")).longValue() * 86_400_000L;
        for (JsonNode fold : array(field(value, "folds"))) {
            ArrayNode trainIds = array(field(fold, "train_episode_ids"));
            ArrayNode testIds = array(field(fold, "test_episode_ids"));
            Set<String> train = textSet(trainIds); Set<String> test = textSet(testIds);
            boolean overlap = train.stream().anyMatch(test::contains);
            if (!"EVALUATED".equals(text(field(fold, "status"))) || !field(fold, "train_episode_ids").isArray()
                    || trainIds.isEmpty() || !field(fold, "test_episode_ids").isArray() || testIds.isEmpty()
                    || train.size() != trainIds.size() || test.size() != testIds.size() || overlap
                    || integer(field(fold, "purge_ms"), Long.MIN_VALUE) != purge
                    || integer(field(fold, "embargo_ms"), Long.MIN_VALUE) != embargo) {
                throw failure("nested WFO SHADOW fold inventory is incomplete, overlapping, or not purged/embargoed");
            }
            JsonNode testNode = field(fold, "test");
            if (!testNode.isObject() || !(field(testNode, "weighted_recency").isBoolean()
                    && !field(testNode, "weighted_recency").asBoolean())
                    || !HASH_RE.matcher(text(field(testNode, "vector_inventory_sha256"))).matches()) {
                throw failure("nested WFO SHADOW fold test is weighted or lacks its OOS vector binding");
            }
            testIds.forEach(id -> outerTestIds.add(jsString(id)));
        }
        List<String> uniqueOuter = new ArrayList<>(new HashSet<>(outerTestIds)); uniqueOuter.sort(String::compareTo);
        List<String> sortedOos = new ArrayList<>(); oosIds.forEach(id -> sortedOos.add(jsString(id))); sortedOos.sort(String::compareTo);
        if (!stable(MAPPER.valueToTree(uniqueOuter)).equals(stable(MAPPER.valueToTree(sortedOos)))) {
            throw failure("nested WFO SHADOW OOS episode inventory does not equal the retained outer test inventory");
        }
        List<String> tradeAssets = new ArrayList<>(textSet(array(field(field(value, "asset_scope"), "trade_assets"))));
        tradeAssets.sort(String::compareTo); ArrayNode finalRows = array(field(value, "asset_decisions_final"));
        List<String> finalAssets = new ArrayList<>(); finalRows.forEach(row -> finalAssets.add(text(field(row, "asset"))));
        finalAssets.sort(String::compareTo);
        if (!field(value, "asset_decisions_final").isArray() || finalRows.isEmpty()
                || !finalAssets.equals(tradeAssets)) {
            throw failure("nested WFO SHADOW asset decision inventory is empty, incomplete, or not authoritative");
        }
        for (JsonNode row : finalRows) if (!validateAssetDecision(row) || !field(row, "pass").asBoolean(false)) {
            throw failure("nested WFO SHADOW asset decision inventory is empty, incomplete, or not authoritative");
        }

        ArrayNode outerRows = array(field(value, "asset_decisions")); ArrayNode expectedOuter = array();
        for (JsonNode outer : outerRows) {
            JsonNode decisions = field(outer, "asset_decisions");
            if (decisions.isObject()) decisions.forEach(expectedOuter::add);
        }
        if (expectedOuter.isEmpty()) throw failure("nested WFO SHADOW outer asset-decision inventory is missing or tampered");
        for (JsonNode row : expectedOuter) if (!validateAssetDecision(row)) {
            throw failure("nested WFO SHADOW outer asset-decision inventory is missing or tampered");
        }
        if (!field(value, "asset_decisions").isArray() || outerRows.size() != field(value, "folds").size()
                || outerRows.size() != uniqueFieldCount(outerRows, "fold_id")) {
            throw failure("nested WFO SHADOW outer fold decision inventory is incomplete or duplicated");
        }
        for (JsonNode fold : array(field(value, "folds"))) validateShadowFold(value, fold, outerRows, tradeAssets);

        ObjectNode lineage = object(); lineage.put("phase", "FINAL_OOS");
        lineage.set("artifact", cloneNode(field(value, "oos_artifact_sha256")));
        lineage.set("head", cloneNode(field(value, "validation_exposure_head_sha256")));
        lineage.put("asset_decisions", hash(expectedOuter)); String expectedLineage = hash(lineage);
        JsonNode portfolio = field(value, "portfolio_decision");
        try { validateBoundDecision(portfolio, "portfolio", expectedLineage,
                text(field(value, "oos_artifact_sha256")), null); }
        catch (IllegalArgumentException error) {
            throw failure("nested WFO SHADOW portfolio decision is not authoritative: " + error.getMessage());
        }
        List<String> portfolioAssets = new ArrayList<>(textSet(array(field(portfolio, "asset_decisions"))
                .isEmpty() ? array() : assetsFromDecisions(field(portfolio, "asset_decisions"))));
        portfolioAssets.sort(String::compareTo);
        if (!portfolioAssets.equals(tradeAssets)) {
            throw failure("nested WFO SHADOW portfolio asset inventory is incomplete or contradictory");
        }
        for (JsonNode row : array(field(portfolio, "asset_decisions"))) {
            if (!field(row, "asset").isTextual() || !field(row, "pass").isBoolean()
                    || !tradeAssets.contains(text(field(row, "asset"))) || !field(row, "pass").asBoolean()) {
                throw failure("nested WFO SHADOW portfolio asset inventory is incomplete or contradictory");
            }
        }
        if (!field(portfolio, "pass").isBoolean() || !field(portfolio, "pass").asBoolean()) {
            throw failure("nested WFO SHADOW portfolio asset inventory is incomplete or contradictory");
        }

        Map<String, JsonNode> expectedReturns = new LinkedHashMap<>();
        for (JsonNode decision : expectedOuter) for (JsonNode row : array(field(decision, "selected_return_vector"))) {
            expectedReturns.put(text(field(row, "asset")) + "|" + text(field(row, "episode_id")), row);
        }
        Set<String> expectedTraded = new HashSet<>(); expectedReturns.forEach((key, row) -> {
            if (field(row, "traded").isBoolean() && field(row, "traded").asBoolean()) expectedTraded.add(key);
        });
        ArrayNode actualReturns = array(field(portfolio, "return_increments")); Set<String> actualKeys = new HashSet<>();
        boolean invalidReturns = actualReturns.isEmpty();
        for (JsonNode row : actualReturns) {
            String key = text(field(row, "asset")) + "|" + text(field(row, "episode_id"));
            JsonNode expected = expectedReturns.get(key);
            if (!actualKeys.add(key) || expected == null || !field(expected, "traded").asBoolean(false)
                    || Double.compare(numberJs(field(row, "net_r")), numberJs(field(expected, "net_r"))) != 0
                    || !field(row, "asset").isTextual() || !field(row, "episode_id").isTextual()
                    || !Double.isFinite(numberJs(field(row, "net_r")))) invalidReturns = true;
        }
        if (!actualKeys.equals(expectedTraded)) invalidReturns = true;
        if (invalidReturns) throw failure(
                "nested WFO SHADOW portfolio return-increment inventory is not exactly bound to the retained OOS fills");

        JsonNode audit = field(value, "audit"); boolean badGate = !field(audit, "pass").asBoolean(false)
                || !"SHADOW".equals(text(field(audit, "decision"))) || !field(audit, "gates").isObject();
        if (!badGate) for (JsonNode gate : field(audit, "gates")) if (!gate.isBoolean() || !gate.asBoolean()) {
            badGate = true; break;
        }
        if (badGate) throw failure("nested WFO SHADOW audit is not semantically passing");
        JsonNode refit = field(value, "development_refit"); ArrayNode refits = array(field(refit, "asset_refits"));
        List<String> refitAssets = new ArrayList<>(); refits.forEach(row -> refitAssets.add(text(field(row, "asset"))));
        refitAssets.sort(String::compareTo); boolean invalidFinalRefit = !"SHADOW_PENDING_PROSPECTIVE".equals(
                text(field(refit, "status"))) || !field(refit, "asset_refits").isArray()
                || !refitAssets.equals(tradeAssets);
        for (JsonNode row : refits) if (!"SELECTED_FOR_SHADOW".equals(text(field(row, "status")))) invalidFinalRefit = true;
        if (invalidFinalRefit) throw failure(
                "nested WFO SHADOW development refit inventory is incomplete or not selected for prospective shadow");
    }

    private static void validateShadowFold(JsonNode value, JsonNode fold, ArrayNode outerRows,
            List<String> tradeAssets) {
        JsonNode outer = MissingNode.getInstance(); String foldId = text(field(fold, "fold_id"));
        for (JsonNode row : outerRows) if (foldId.equals(text(field(row, "fold_id")))) { outer = row; break; }
        JsonNode decisions = field(outer, "asset_decisions");
        List<String> keys = new ArrayList<>(fieldNames(decisions)); keys.sort(String::compareTo);
        if (!outer.isObject() || !decisions.isObject() || !keys.equals(tradeAssets)) {
            throw failure("nested WFO SHADOW fold " + foldId + " asset inventory is incomplete");
        }
        JsonNode vector = field(outer, "vector");
        if (!vector.isObject() || !text(field(vector, "content_sha256")).equals(
                text(field(field(fold, "test"), "vector_inventory_sha256")))) {
            throw failure("nested WFO SHADOW fold " + foldId + " vector inventory is not exactly bound");
        }
        JsonNode outerPortfolio = field(outer, "portfolio"), foldPortfolio = field(field(fold, "test"), "portfolio");
        if (!outerPortfolio.isObject() || !foldPortfolio.isObject()
                || !text(field(outerPortfolio, "content_sha256")).equals(text(field(foldPortfolio, "content_sha256")))
                || field(outerPortfolio, "pass").asBoolean(false) != field(foldPortfolio, "pass").asBoolean(false)
                || !text(field(outerPortfolio, "provenance")).equals(text(field(foldPortfolio, "provenance")))
                || !text(field(outerPortfolio, "lineage_sha256")).equals(text(field(foldPortfolio, "lineage_sha256")))) {
            throw failure("nested WFO SHADOW fold " + foldId + " portfolio is not exactly bound");
        }
        Set<String> testIds = textSet(array(field(fold, "test_episode_ids"))); List<String> foldReturnIds = new ArrayList<>();
        for (String assetName : keys) {
            JsonNode decision = field(decisions, assetName); ArrayNode returns = array(field(decision, "selected_return_vector"));
            Set<String> returnIds = fieldTextSet(returns, "episode_id"); boolean invalid = !validateAssetDecision(decision)
                    || !assetName.equals(text(field(decision, "asset"))) || !field(decision, "selected_return_vector").isArray()
                    || returnIds.size() != returns.size();
            for (JsonNode row : returns) {
                String id = text(field(row, "episode_id")); foldReturnIds.add(id);
                if (!row.isObject() || !assetName.equals(text(field(row, "asset"))) || !testIds.contains(id)) invalid = true;
            }
            if (invalid) throw failure("nested WFO SHADOW fold " + foldId + "/" + assetName
                    + " return inventory is incomplete or cross-boundary");
            List<String> expectedIds = new ArrayList<>();
            for (String id : testIds) for (JsonNode row : returns) if (id.equals(text(field(row, "episode_id")))
                    && assetName.equals(text(field(row, "asset")))) { expectedIds.add(id); break; }
            expectedIds.sort(String::compareTo); List<String> actualIds = new ArrayList<>(returnIds); actualIds.sort(String::compareTo);
            if (!actualIds.equals(expectedIds)) throw failure("nested WFO SHADOW fold " + foldId + "/" + assetName
                    + " return inventory is not exact");
            String alias = text(field(decision, "selected_behavior_alias_sha256")); JsonNode retainedRows = field(field(vector, "vectors"), alias);
            if (!retainedRows.isArray()) throw failure("nested WFO SHADOW fold " + foldId + "/" + assetName
                    + " selected behavior is absent from its vector inventory");
            Map<String, JsonNode> retained = new HashMap<>(); retainedRows.forEach(row -> retained.put(text(field(row, "episode_id")), row));
            for (JsonNode row : returns) {
                JsonNode kept = retained.get(text(field(row, "episode_id")));
                if (kept == null || Double.compare(numberJs(field(kept, "net_r")), numberJs(field(row, "net_r"))) != 0
                        || !sameBoolean(field(kept, "traded"), field(row, "traded"))) {
                    throw failure("nested WFO SHADOW fold " + foldId + "/" + assetName
                            + " selected returns disagree with its vector inventory");
                }
            }
            JsonNode stress = field(decision, "stress"); String selected = truthy(field(decision, "selected_candidate_id"))
                    ? jsString(field(decision, "selected_candidate_id")) : jsString(field(decision, "selected_behavior_alias_sha256"));
            if (!stress.isObject() || !text(field(stress, "content_sha256")).equals(ownHash(stress))
                    || !schema("stress").equals(text(field(stress, "schema")))
                    || !text(field(stress, "lineage_sha256")).equals(text(field(decision, "lineage_sha256")))
                    || !text(field(stress, "selected_candidate_id")).equals(selected)) {
                throw failure("nested WFO SHADOW fold " + foldId + "/" + assetName
                        + " stress is not bound to its selected decision");
            }
            try { validateBoundDecision(stress, "stress", text(field(decision, "lineage_sha256")), null, selected); }
            catch (IllegalArgumentException error) { throw failure("nested WFO SHADOW fold " + foldId + "/" + assetName
                    + " stress is not authoritative: " + error.getMessage()); }
        }
        if (new HashSet<>(foldReturnIds).size() != foldReturnIds.size()
                || !new HashSet<>(foldReturnIds).equals(testIds)) {
            throw failure("nested WFO SHADOW fold " + foldId
                    + " return inventory does not cover its exact test episode set");
        }
    }

    public static boolean assertWfoRetainedOosBinding(JsonNode wfo, JsonNode artifact, JsonNode vector) {
        return assertWfoRetainedOosBinding(wfo, artifact, vector, "retained OOS evidence");
    }

    public static boolean assertWfoRetainedOosBinding(JsonNode wfo, JsonNode artifact, JsonNode vector,
            String label) {
        if (wfo == null || !wfo.isObject()) throw failure(label + " lacks its WFO artifact");
        validateNestedWfoArtifact(wfo); ObjectNode validation = object();
        validation.set("exposureHead", cloneNode(field(wfo, "validation_exposure_head")));
        validation.put("allowSubset", true); validateStatisticalArtifactSet(artifact, validation);
        validateVectorInventory(vector, field(wfo, "validation_exposure_head"), field(wfo, "oos_episode_ids"));
        if (!text(field(artifact, "content_sha256")).equals(text(field(wfo, "oos_artifact_sha256")))
                || !text(field(vector, "content_sha256")).equals(text(field(wfo, "vector_inventory_sha256")))) {
            throw failure(label + " hashes disagree with the WFO");
        }
        Map<String, JsonNode> episodes = new LinkedHashMap<>();
        for (JsonNode row : array(field(artifact, "episodes"))) episodes.put(text(field(row, "episode_id")), row);
        if (!stable(MAPPER.valueToTree(episodes.keySet())).equals(stable(field(wfo, "oos_episode_ids")))) {
            throw failure(label + " episode inventory disagrees with the WFO");
        }
        JsonNode vectors = field(vector, "vectors");
        for (String alias : fieldNames(vectors)) for (JsonNode row : array(field(vectors, alias))) {
            String id = text(field(row, "episode_id")); JsonNode episode = episodes.get(id);
            JsonNode retained = field(field(episode, "candidate_returns"), "behavior:" + alias);
            if (!retained.isObject() || Double.compare(numberJs(field(retained, "net_r")), numberJs(field(row, "net_r"))) != 0
                    || !sameBoolean(field(retained, "traded"), field(row, "traded"))) {
                throw failure(label + " vector " + alias + "/" + id + " disagrees with the OOS artifact");
            }
        }
        for (JsonNode fold : array(field(wfo, "folds"))) {
            if (!"EVALUATED".equals(text(field(fold, "status")))) continue;
            JsonNode outer = MissingNode.getInstance();
            for (JsonNode row : array(field(wfo, "asset_decisions"))) if (text(field(row, "fold_id"))
                    .equals(text(field(fold, "fold_id")))) { outer = row; break; }
            JsonNode foldVectors = field(field(outer, "vector"), "vectors");
            if (!foldVectors.isObject()) throw failure(label + " fold " + text(field(fold, "fold_id"))
                    + " lacks its retained vector");
            for (String alias : fieldNames(foldVectors)) {
                Map<String, JsonNode> finalRows = new HashMap<>();
                for (JsonNode row : array(field(vectors, alias))) finalRows.put(text(field(row, "episode_id")), row);
                for (JsonNode row : array(field(foldVectors, alias))) {
                    String id = text(field(row, "episode_id")); JsonNode retained = finalRows.get(id);
                    if (retained == null || !stable(retained).equals(stable(row))) throw failure(label + " fold "
                            + text(field(fold, "fold_id")) + " vector " + alias + "/" + id
                            + " is not the retained physical OOS value");
                }
            }
            JsonNode decisions = field(outer, "asset_decisions");
            for (String assetName : fieldNames(decisions)) {
                JsonNode decision = field(decisions, assetName);
                if (!truthy(field(decision, "selected_behavior_alias_sha256"))
                        || !field(decision, "selected_return_vector").isArray()) continue;
                String alias = text(field(decision, "selected_behavior_alias_sha256"));
                Map<String, JsonNode> aliasRows = new HashMap<>();
                for (JsonNode row : array(field(vectors, alias))) aliasRows.put(text(field(row, "episode_id")), row);
                List<String> expectedIds = new ArrayList<>();
                for (JsonNode id : array(field(fold, "test_episode_ids"))) {
                    JsonNode episode = episodes.get(jsString(id));
                    if (episode != null && assetName.equals(text(field(episode, "asset")))) expectedIds.add(jsString(id));
                }
                expectedIds.sort(String::compareTo); List<String> actualIds = new ArrayList<>();
                for (JsonNode row : array(field(decision, "selected_return_vector"))) actualIds.add(text(field(row, "episode_id")));
                actualIds.sort(String::compareTo);
                if (!actualIds.equals(expectedIds)) throw failure(label + " fold " + text(field(fold, "fold_id"))
                        + "/" + assetName + " decision does not cover its exact physical OOS asset inventory");
                for (JsonNode row : array(field(decision, "selected_return_vector"))) {
                    String id = text(field(row, "episode_id")); JsonNode retained = aliasRows.get(id), episode = episodes.get(id);
                    if (retained == null || episode == null || !assetName.equals(text(field(episode, "asset")))
                            || Double.compare(numberJs(field(retained, "net_r")), numberJs(field(row, "net_r"))) != 0
                            || !sameBoolean(field(retained, "traded"), field(row, "traded"))) {
                        throw failure(label + " fold " + text(field(fold, "fold_id")) + "/" + assetName + "/" + id
                                + " decision disagrees with the retained physical vector");
                    }
                }
            }
        }
        return true;
    }

    private static ObjectNode normalizeAssetScope(JsonNode rawScope, JsonNode artifactAssets, String mode) {
        List<String> observed = new ArrayList<>();
        for (JsonNode raw : array(artifactAssets)) observed.add(asset(raw));
        observed = new ArrayList<>(new HashSet<>(observed)); observed.sort(String::compareTo);
        if (!truthy(rawScope)) {
            if (!"FIXTURE".equals(String.valueOf(mode).toUpperCase(Locale.ROOT))) {
                throw failure("authoritative WFO requires an immutable precommitted asset scope");
            }
            ObjectNode fixture = object(); fixture.put("schema", "strategy-v5-statistical-asset-scope/1");
            fixture.put("version", 1); fixture.set("trade_assets", strings(observed)); fixture.set("replication_assets", array());
            fixture.set("context_assets", array()); fixture.putNull("source_sha256"); return withHash(fixture);
        }
        if (!rawScope.isObject()) throw failure("asset scope must be an object");
        assertKnownKeys(rawScope, Set.of("schema", "version", "trade_assets", "replication_assets",
                "context_assets", "source_sha256", "content_sha256"), "asset scope");
        if (!"strategy-v5-statistical-asset-scope/1".equals(text(field(rawScope, "schema")))
                || numberJs(field(rawScope, "version")) != 1) throw failure("asset scope schema/version is invalid");
        List<String> trade = normalizeAssetList(field(rawScope, "trade_assets"), "trade_assets", true);
        List<String> replication = normalizeAssetList(truthy(field(rawScope, "replication_assets"))
                ? field(rawScope, "replication_assets") : array(), "replication_assets", true);
        List<String> context = normalizeAssetList(truthy(field(rawScope, "context_assets"))
                ? field(rawScope, "context_assets") : array(), "context_assets", false);
        if (trade.isEmpty()) throw failure("asset scope trade_assets must be non-empty");
        List<Map.Entry<String, List<String>>> categories = List.of(Map.entry("trade_assets", trade),
                Map.entry("replication_assets", replication), Map.entry("context_assets", context));
        for (int left = 0; left < categories.size(); left++) for (int right = left + 1; right < categories.size(); right++) {
            List<String> overlap = categories.get(left).getValue().stream()
                    .filter(categories.get(right).getValue()::contains).toList();
            if (!overlap.isEmpty()) throw failure("asset scope overlaps " + categories.get(left).getKey() + " and "
                    + categories.get(right).getKey() + ": " + String.join(",", overlap));
        }
        Set<String> observedSet = Set.copyOf(observed);
        if (trade.stream().anyMatch(value -> !observedSet.contains(value))) {
            throw failure("asset scope declares a trade asset absent from the canonical artifact");
        }
        Set<String> declared = new HashSet<>(); declared.addAll(trade); declared.addAll(replication); declared.addAll(context);
        List<String> omitted = observed.stream().filter(value -> !declared.contains(value)).toList();
        if (!omitted.isEmpty()) throw failure("asset scope omits canonical artifact asset(s): " + String.join(",", omitted));
        if (definedNonNull(field(rawScope, "source_sha256"))) requireHash(field(rawScope, "source_sha256"),
                "asset scope source_sha256");
        ObjectNode normalized = object(); normalized.put("schema", "strategy-v5-statistical-asset-scope/1");
        normalized.put("version", 1); normalized.set("trade_assets", strings(trade));
        normalized.set("replication_assets", strings(replication)); normalized.set("context_assets", strings(context));
        normalized.set("source_sha256", definedNonNull(field(rawScope, "source_sha256"))
                ? cloneNode(field(rawScope, "source_sha256")) : NullNode.instance);
        ObjectNode result = withHash(normalized);
        if (defined(field(rawScope, "content_sha256")) && !text(field(rawScope, "content_sha256"))
                .equals(text(field(result, "content_sha256")))) throw failure("asset scope content hash is invalid");
        return result;
    }

    private static List<String> normalizeAssetList(JsonNode values, String label, boolean cryptoOnly) {
        if (!values.isArray()) throw failure("asset scope " + label + " must be an array");
        List<String> raw = new ArrayList<>(); List<String> normalized = new ArrayList<>();
        for (JsonNode value : values) {
            String item = truthy(value) ? jsString(value).toLowerCase(Locale.ROOT).trim() : ""; raw.add(item);
            if (cryptoOnly) normalized.add(asset(JSON.textNode(item)));
            else {
                if (item.isEmpty()) throw failure("asset scope " + label + " contains an empty identifier");
                normalized.add(item);
            }
        }
        normalized = new ArrayList<>(new HashSet<>(normalized)); normalized.sort(String::compareTo);
        if (normalized.size() != raw.size()) throw failure("asset scope " + label + " contains duplicate assets");
        return normalized;
    }

    private static void validateBoundDecision(JsonNode value, String name, String lineageSha256,
            String sourceArtifactSha256, String selectedCandidateId) {
        if (!value.isObject() || !field(value, "pass").isBoolean()
                || !"AUTHORITATIVE_RECOMPUTED".equals(text(field(value, "provenance")))
                || !text(field(value, "lineage_sha256")).equals(lineageSha256)
                || !text(field(value, "content_sha256")).equals(ownHash(value))) {
            throw failure(name + " is missing or not lineage-bound");
        }
        if (name.toLowerCase(Locale.ROOT).contains("stress")) {
            ArrayNode scenarios = array(field(value, "scenarios")); List<String> ids = new ArrayList<>();
            boolean invalid = !schema("stress").equals(text(field(value, "schema")))
                    || !field(value, "scenarios").isArray();
            for (JsonNode row : scenarios) {
                ids.add(text(field(row, "id")));
                if (!field(row, "pass").isBoolean()) invalid = true;
                requireHash(field(row, "digest"), "stress scenario digest");
            }
            ids.sort(String::compareTo); List<String> expected = new ArrayList<>(STRESS_SCENARIOS);
            expected.sort(String::compareTo);
            requireHash(field(value, "source_artifact_sha256"), name + ".source_artifact_sha256");
            if (!ids.equals(expected) || sourceArtifactSha256 != null
                    && !sourceArtifactSha256.equals(text(field(value, "source_artifact_sha256")))
                    || !field(value, "selected_candidate_id").isTextual() || selectedCandidateId != null
                    && !selectedCandidateId.equals(text(field(value, "selected_candidate_id")))) invalid = true;
            if (invalid) throw failure(name + " is missing the authoritative stress scenario inventory");
            if (!text(field(value, "scenario_inventory_sha256")).equals(hash(scenarios))) {
                throw failure(name + " stress inventory hash is invalid");
            }
        } else {
            ArrayNode decisions = array(field(value, "asset_decisions")); ArrayNode increments = array(field(value, "return_increments"));
            boolean invalid = !schema("portfolio").equals(text(field(value, "schema")))
                    || !field(value, "asset_decisions").isArray() || decisions.isEmpty()
                    || !field(value, "return_increments").isArray() || increments.isEmpty()
                    || !text(field(value, "asset_decisions_sha256")).equals(hash(decisions))
                    || !text(field(value, "return_increments_sha256")).equals(hash(increments));
            requireHash(field(value, "risk_digest_sha256"), name + ".risk_digest_sha256");
            requireHash(field(value, "source_artifact_sha256"), name + ".source_artifact_sha256");
            if (invalid) throw failure(name + " is missing the authoritative portfolio recomputation contract");
        }
    }

    private static boolean validateAssetDecision(JsonNode value) {
        if (!value.isObject() || !field(value, "pass").isBoolean()
                || !"ASSET".equals(text(field(value, "decision_type")))
                || !"AUTHORITATIVE_RECOMPUTED".equals(text(field(value, "provenance")))
                || !text(field(value, "content_sha256")).equals(ownHash(value))) return false;
        try { return asset(field(value, "asset")).equals(text(field(value, "asset"))); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static int uniqueTextCount(JsonNode values) { return textSet(array(values)).size(); }
    private static int uniqueFieldCount(JsonNode values, String name) {
        Set<String> unique = new HashSet<>(); values.forEach(value -> unique.add(text(field(value, name)))); return unique.size();
    }
    private static Set<String> textSet(JsonNode values) {
        Set<String> result = new LinkedHashSet<>(); values.forEach(value -> result.add(jsString(value))); return result;
    }
    private static Set<String> fieldTextSet(JsonNode values, String name) {
        Set<String> result = new LinkedHashSet<>(); values.forEach(value -> result.add(text(field(value, name)))); return result;
    }
    private static ArrayNode assetsFromDecisions(JsonNode decisions) {
        ArrayNode result = array(); for (JsonNode row : array(decisions)) result.add(cloneNode(field(row, "asset"))); return result;
    }
    private static boolean sameBoolean(JsonNode left, JsonNode right) {
        return left.isBoolean() && right.isBoolean() && left.asBoolean() == right.asBoolean();
    }

    public static ObjectNode makeStatisticalPublicationTransaction(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        if (!truthy(field(options, "transactionPath")) || !truthy(field(options, "exposureHeadPath"))
                || !truthy(field(options, "registryPath"))) {
            throw failure("publication transaction requires transaction, exposure-head, and registry paths");
        }
        String transactionRaw = jsString(field(options, "transactionPath"));
        String headRaw = jsString(field(options, "exposureHeadPath"));
        String registryRaw = jsString(field(options, "registryPath"));
        Path root = publicationRecordRoot(Path.of(transactionRaw), field(options, "recordRoot"));
        String transactionPath = recordRelativePath(root, Path.of(transactionRaw), "transaction path");
        String headPath = recordRelativePath(root, Path.of(headRaw), "exposure HEAD path");
        String registryPath = recordRelativePath(root, Path.of(registryRaw), "registry path");
        String expectedHead = requireHash(field(options, "expectedHeadSha256"),
                "publication expected_head_sha256");
        String expectedRegistry = requireHash(field(options, "expectedRegistrySha256"),
                "publication expected_registry_sha256");
        ObjectNode next = field(options, "nextHead").isObject()
                ? validateExposureHead(field(options, "nextHead")) : readExposureHeadFile(headRaw);
        if (field(options, "priorHead").isObject()) {
            assertExposurePrefix(field(options, "priorHead"), next, "publication exposure");
        }
        if (!expectedHead.equals(text(field(next, "content_sha256")))) {
            throw failure("publication next HEAD differs from its compare-and-swap expected hash");
        }
        JsonNode wfo = requirePublicationArtifact(field(options, "wfo"), "publication WFO artifact", "wfo");
        JsonNode run = requirePublicationArtifact(field(options, "run"), "publication research run", "research_run");
        ArrayNode rows = publicationArtifactRows(field(options, "artifacts"), root);
        ObjectNode registry = readBehaviorDefinitionRegistryFile(registryRaw);
        if (!expectedRegistry.equals(text(field(registry, "content_sha256")))
                || !expectedHead.equals(text(field(registry, "exposure_head_sha256")))) {
            throw failure("publication registry binding does not match its compare-and-swap predecessor");
        }
        Path stageAbsolute = Path.of(transactionRaw + ".stage").toAbsolutePath().normalize();
        String stageRoot = recordRelativePath(root, stageAbsolute, "publication stage root");
        String transactionId = publicationTransactionId(transactionPath, headPath, registryPath, stageRoot,
                expectedHead, expectedRegistry, text(field(wfo, "content_sha256")),
                text(field(run, "content_sha256")), rows);
        assertPublicationArtifactRefs(transactionPath, headPath, registryPath, stageRoot, rows, run, wfo, root);
        assertPublicationLineage(wfo, run, next);
        ObjectNode value = object(); value.put("schema", schema("publicationTransaction")); value.put("version", 1);
        value.put("status", "PREPARED"); value.put("transaction_id", transactionId);
        value.put("transaction_path", transactionPath); value.put("exposure_head_path", headPath);
        value.put("registry_path", registryPath); value.put("expected_head_sha256", expectedHead);
        value.put("next_head_sha256", text(field(next, "content_sha256")));
        value.put("expected_registry_sha256", expectedRegistry); value.set("bound_head", next.deepCopy());
        value.set("bound_registry", registry.deepCopy()); value.put("wfo_sha256", text(field(wfo, "content_sha256")));
        value.put("run_sha256", text(field(run, "content_sha256"))); value.set("artifact_refs", rows);
        value.put("no_k_mutation", true); value.put("no_rollback", true); value.put("stage_root", stageRoot);
        ObjectNode result = withHash(value); validateContractSchema(result); return result;
    }

    public static ObjectNode verifyCommittedStatisticalPublication(ObjectNode args) {
        ObjectNode options = args == null ? object() : args; JsonNode journal = field(options, "journal");
        if (!journal.isObject() || !"COMMITTED".equals(text(field(journal, "status")))) {
            throw failure("publication inventory requires a COMMITTED journal");
        }
        if (!truthy(field(options, "journalPath"))) throw failure("publication inventory journal path is missing");
        Path target = assertRegularPublicationFile(Path.of(jsString(field(options, "journalPath"))),
                "publication transaction journal path");
        Path root = truthy(field(options, "recordRoot"))
                ? Path.of(jsString(field(options, "recordRoot"))).toAbsolutePath().normalize()
                : publicationRecordRoot(target, MissingNode.getInstance());
        Path expectedJournal = root.resolve(assertRecordRelativePath(text(field(journal, "transaction_path")),
                "publication transaction path")).toAbsolutePath().normalize();
        if (!expectedJournal.equals(target)) throw failure("publication transaction path does not match its physical journal path: "
                + text(field(journal, "transaction_path")));
        validateContractSchema(journal);
        Path headPath = publicationControlPath(root, text(field(journal, "exposure_head_path")),
                "publication exposure HEAD path");
        Path registryPath = publicationControlPath(root, text(field(journal, "registry_path")),
                "publication registry path");
        ObjectNode head = readExposureHeadFile(headPath); ObjectNode registry = readBehaviorDefinitionRegistryFile(registryPath);
        ObjectNode registryValidation = object(); registryValidation.set("exposureHead", head);
        validateBehaviorDefinitionRegistry(registry, registryValidation);
        boolean currentHeadExact = text(field(head, "content_sha256")).equals(text(field(journal, "expected_head_sha256")))
                || text(field(head, "content_sha256")).equals(text(field(journal, "next_head_sha256")));
        if (!currentHeadExact) assertExposurePrefix(field(journal, "bound_head"), head,
                "committed publication exposure successor");
        boolean currentRegistryExact = text(field(registry, "content_sha256")).equals(
                text(field(journal, "expected_registry_sha256")));
        if (!currentRegistryExact) assertRegistryPrefix(field(journal, "bound_registry"), registry,
                "committed publication registry successor");
        if (!text(field(registry, "exposure_head_sha256")).equals(text(field(head, "content_sha256")))) {
            throw failure("publication registry is not bound to physical HEAD");
        }
        ObjectNode artifacts = object(), artifactPaths = object();
        for (JsonNode ref : array(field(journal, "artifact_refs"))) {
            String role = text(field(ref, "role")); String relative = text(field(ref, "path"));
            Path path = publicationControlPath(root, relative, "publication artifact " + role + " path");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw failure("publication artifact is missing: " + relative);
            byte[] bytes; JsonNode value;
            try { bytes = Files.readAllBytes(path); }
            catch (IOException error) { throw failure("publication artifact is missing: " + relative); }
            if (bytes.length != integer(field(ref, "bytes"), -1) || !hash(bytes).equals(text(field(ref, "byte_sha256")))) {
                throw failure("publication artifact bytes are tampered: " + relative);
            }
            try { value = MAPPER.readTree(bytes); }
            catch (IOException error) { throw failure("publication artifact is not JSON: " + relative + ": " + error.getMessage()); }
            requirePublicationArtifact(value, "publication artifact " + role, role);
            if (!text(field(value, "content_sha256")).equals(text(field(ref, "content_sha256")))) {
                throw failure("publication artifact semantic hash is not bound: " + relative);
            }
            artifacts.set(role, value); artifactPaths.put(role, path.toString());
        }
        assertPublicationArtifactRefs(text(field(journal, "transaction_path")),
                text(field(journal, "exposure_head_path")), text(field(journal, "registry_path")),
                text(field(journal, "stage_root")), field(journal, "artifact_refs"),
                field(artifacts, "research_run"), field(artifacts, "wfo"), root);
        assertPublicationLineage(field(artifacts, "wfo"), field(artifacts, "research_run"),
                field(journal, "bound_head"));
        ObjectNode result = object(); result.set("journal", cloneNode(journal)); result.put("root", root.toString());
        result.put("journalPath", target.toString()); result.set("head", head); result.set("registry", registry);
        result.set("artifacts", artifacts); result.set("artifactPaths", artifactPaths); return result;
    }

    public static ObjectNode writeStatisticalPublicationTransaction(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        Path target = Path.of(jsString(field(options, "transactionPath"))).toAbsolutePath().normalize();
        Path root = publicationRecordRoot(target, field(options, "recordRoot"));
        try { ensureParent(target); }
        catch (IOException error) { throw failure("publication transaction cannot be prepared: " + error.getMessage()); }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            ObjectNode existing = readPublicationTransaction(target, null);
            assertPublicationRetryMatchesExisting(existing, options, root);
            ObjectNode recover = object(); recover.put("transactionPath", target.toString());
            recover.put("recordRoot", root.toString());
            return recoverStatisticalPublicationTransaction(recover);
        }
        ObjectNode make = options.deepCopy(); make.put("recordRoot", root.toString());
        ObjectNode transaction = makeStatisticalPublicationTransaction(make);
        Path stageRoot = root.resolve(text(field(transaction, "stage_root"))).toAbsolutePath().normalize();
        try { Files.createDirectories(stageRoot); }
        catch (IOException error) { throw failure("publication transaction cannot be prepared: " + error.getMessage()); }
        for (JsonNode row : array(field(options, "artifacts"))) {
            String role = jsString(field(row, "role"));
            JsonNode value = requirePublicationArtifact(field(row, "value"),
                    "publication artifact " + role, role);
            byte[] bytes = publicationBytes(value); JsonNode ref = findRole(field(transaction, "artifact_refs"), role);
            if (!ref.isObject() || !hash(bytes).equals(text(field(ref, "byte_sha256")))) {
                throw failure("publication artifact " + role + " changed while preparing transaction");
            }
            Path staged = stageRoot.resolve(role + "-" + text(field(ref, "content_sha256")) + ".json")
                    .toAbsolutePath().normalize();
            try {
                if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                    if (!hash(Files.readAllBytes(staged)).equals(text(field(ref, "byte_sha256")))) {
                        throw failure("publication staged artifact collision: " + staged);
                    }
                } else writeExclusiveBytes(staged, bytes);
            } catch (IllegalArgumentException error) {
                throw error;
            } catch (IOException error) {
                throw failure("publication transaction cannot be prepared: " + error.getMessage());
            }
        }
        try {
            writeExclusiveJson(target, transaction);
        } catch (java.nio.file.FileAlreadyExistsException error) {
            ObjectNode existing = readPublicationTransaction(target, null);
            if (samePublicationTransaction(existing, transaction)) {
                ObjectNode recover = object(); recover.put("transactionPath", target.toString());
                recover.put("recordRoot", root.toString());
                return recoverStatisticalPublicationTransaction(recover);
            }
            throw failure("publication transaction cannot be prepared: " + error.getMessage());
        } catch (IOException error) {
            throw failure("publication transaction cannot be prepared: " + error.getMessage());
        }
        ObjectNode recover = object(); recover.put("transactionPath", target.toString());
        recover.put("recordRoot", root.toString());
        return recoverStatisticalPublicationTransaction(recover);
    }

    public static ObjectNode recoverStatisticalPublicationTransaction(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        if (!truthy(field(options, "transactionPath"))) {
            ObjectNode none = object(); none.put("status", "NONE"); none.set("transaction_path", NullNode.instance);
            return none;
        }
        String rawPath = jsString(field(options, "transactionPath"));
        Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.exists(candidate)) {
            if (Files.isSymbolicLink(candidate)) throw failure("publication transaction journal path is a symlink");
            ObjectNode none = object(); none.put("status", "NONE"); none.put("transaction_path", rawPath); return none;
        }
        Path target = assertRegularPublicationFile(candidate, "publication transaction journal path");
        Path root = publicationRecordRoot(target, field(options, "recordRoot"));
        Path lockPath = Path.of(target + ".lock"); String token = acquirePublicationLock(lockPath);
        try {
            ObjectNode transaction = readPublicationTransaction(target, root);
            Path headPath = root.resolve(text(field(transaction, "exposure_head_path"))).toAbsolutePath().normalize();
            Path registryPath = root.resolve(text(field(transaction, "registry_path"))).toAbsolutePath().normalize();
            ObjectNode head = readExposureHeadFile(headPath);
            ObjectNode registry = readBehaviorDefinitionRegistryFile(registryPath);
            ObjectNode validation = object(); validation.set("exposureHead", head);
            validateBehaviorDefinitionRegistry(registry, validation);
            boolean exactControls = text(field(head, "content_sha256")).equals(
                    text(field(transaction, "expected_head_sha256")))
                    && text(field(registry, "content_sha256")).equals(
                    text(field(transaction, "expected_registry_sha256")));
            if ("PREPARED".equals(text(field(transaction, "status"))) && !exactControls) {
                throw failure("prepared publication transaction HEAD/registry compare-and-swap failed; refusing rollback or K reuse");
            }
            if ("COMMITTED".equals(text(field(transaction, "status"))) && !exactControls) {
                try {
                    assertExposurePrefix(field(transaction, "bound_head"), head,
                            "committed publication exposure successor");
                    assertRegistryPrefix(field(transaction, "bound_registry"), registry,
                            "committed publication registry successor");
                } catch (RuntimeException error) {
                    throw failure("committed publication registry compare-and-swap failed; not a proven immutable successor: "
                            + error.getMessage());
                }
            }
            ObjectNode reopened = object();
            for (JsonNode ref : array(field(transaction, "artifact_refs"))) {
                Path path = promotePublicationArtifact(ref, text(field(transaction, "stage_root")), root);
                reopened.set(text(field(ref, "role")), reopenPublicationArtifact(ref, path));
            }
            assertPublicationArtifactRefs(text(field(transaction, "transaction_path")),
                    text(field(transaction, "exposure_head_path")), text(field(transaction, "registry_path")),
                    text(field(transaction, "stage_root")), field(transaction, "artifact_refs"),
                    field(reopened, "research_run"), field(reopened, "wfo"), root);
            assertPublicationLineage(field(reopened, "wfo"), field(reopened, "research_run"),
                    field(transaction, "bound_head"));
            if ("COMMITTED".equals(text(field(transaction, "status")))) {
                return publicationReceipt(target, transaction);
            }
            ObjectNode committed = transaction.deepCopy(); committed.put("status", "COMMITTED");
            if (!truthy(field(committed, "committed_at"))) committed.put("committed_at", JS_ISO.format(Instant.now()));
            committed = withHash(committed); validateContractSchema(committed);
            writeAtomicJson(target, committed); return publicationReceipt(target, committed);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (IOException error) {
            throw failure(error.getMessage());
        } finally {
            releasePublicationLock(lockPath, token);
        }
    }

    private static ObjectNode publicationReceipt(Path target, JsonNode transaction) {
        ObjectNode result = object(); result.put("status", "COMMITTED");
        result.put("transaction_path", target.toString()); result.put("run_sha256", text(field(transaction, "run_sha256")));
        result.put("wfo_sha256", text(field(transaction, "wfo_sha256")));
        result.put("head_sha256", text(field(transaction, "next_head_sha256"))); return result;
    }

    private static ObjectNode publicationImmutableSemantics(JsonNode value) {
        ObjectNode copy = objectOrEmpty(value).deepCopy();
        copy.remove(List.of("status", "committed_at", "content_sha256")); return copy;
    }

    private static boolean samePublicationTransaction(JsonNode left, JsonNode right) {
        return text(field(left, "transaction_id")).equals(text(field(right, "transaction_id")))
                && stable(publicationImmutableSemantics(left)).equals(stable(publicationImmutableSemantics(right)));
    }

    private static void assertPublicationRetryMatchesExisting(ObjectNode existing, ObjectNode options, Path root) {
        String expectedHead = requireHash(field(options, "expectedHeadSha256"),
                "publication expected_head_sha256");
        String expectedRegistry = requireHash(field(options, "expectedRegistrySha256"),
                "publication expected_registry_sha256");
        JsonNode wfo = requirePublicationArtifact(field(options, "wfo"), "publication WFO artifact", "wfo");
        JsonNode run = requirePublicationArtifact(field(options, "run"), "publication research run", "research_run");
        ObjectNode next = field(options, "nextHead").isObject()
                ? validateExposureHead(field(options, "nextHead"))
                : validateExposureHead(field(existing, "bound_head"));
        if (field(options, "priorHead").isObject()) {
            assertExposurePrefix(field(options, "priorHead"), next, "publication exposure");
        }
        ArrayNode rows = publicationArtifactRows(field(options, "artifacts"), root);
        boolean mismatch = !recordRelativePath(root, Path.of(jsString(field(options, "transactionPath"))),
                    "transaction path").equals(text(field(existing, "transaction_path")))
                || !recordRelativePath(root, Path.of(jsString(field(options, "exposureHeadPath"))),
                    "exposure HEAD path").equals(text(field(existing, "exposure_head_path")))
                || !recordRelativePath(root, Path.of(jsString(field(options, "registryPath"))),
                    "registry path").equals(text(field(existing, "registry_path")))
                || !expectedHead.equals(text(field(existing, "expected_head_sha256")))
                || !expectedRegistry.equals(text(field(existing, "expected_registry_sha256")))
                || !text(field(next, "content_sha256")).equals(text(field(existing, "next_head_sha256")))
                || !stable(next).equals(stable(field(existing, "bound_head")))
                || !text(field(wfo, "content_sha256")).equals(text(field(existing, "wfo_sha256")))
                || !text(field(run, "content_sha256")).equals(text(field(existing, "run_sha256")))
                || !stable(rows).equals(stable(field(existing, "artifact_refs")));
        if (mismatch) throw failure("competing publication transaction at the same path");
        assertPublicationArtifactRefs(text(field(existing, "transaction_path")),
                text(field(existing, "exposure_head_path")), text(field(existing, "registry_path")),
                text(field(existing, "stage_root")), rows, run, wfo, root);
        assertPublicationLineage(wfo, run, field(existing, "bound_head"));
        String expectedId = publicationTransactionId(text(field(existing, "transaction_path")),
                text(field(existing, "exposure_head_path")), text(field(existing, "registry_path")),
                text(field(existing, "stage_root")), text(field(existing, "expected_head_sha256")),
                text(field(existing, "expected_registry_sha256")), text(field(existing, "wfo_sha256")),
                text(field(existing, "run_sha256")), rows);
        if (!expectedId.equals(text(field(existing, "transaction_id")))) {
            throw failure("competing publication transaction at the same path");
        }
    }

    private record PublicationLockOwner(String raw, Boolean alive) {}

    private static PublicationLockOwner publicationLockOwner(Path lockPath) {
        try {
            String raw = Files.readString(lockPath, StandardCharsets.UTF_8); JsonNode value = MAPPER.readTree(raw);
            long pid = integer(field(value, "pid"), -1); String token = text(field(value, "token"));
            if (pid < 1 || token.isEmpty()) return new PublicationLockOwner(raw, null);
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            return new PublicationLockOwner(raw, alive);
        } catch (Exception error) {
            return new PublicationLockOwner(null, null);
        }
    }

    private static String acquirePublicationLock(Path lockPath) {
        ObjectNode tokenInput = object(); tokenInput.put("pid", ProcessHandle.current().pid());
        tokenInput.put("started_at", System.currentTimeMillis());
        tokenInput.put("path", lockPath.toAbsolutePath().normalize().toString()); String token = hash(tokenInput);
        ObjectNode body = object(); body.put("schema", "strategy-v5-statistical-publication-lock/1");
        body.put("pid", ProcessHandle.current().pid()); body.put("token", token);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureParent(lockPath); byte[] bytes = jsonLine(body);
                Files.write(lockPath, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE)) { channel.force(true); }
                return token;
            } catch (java.nio.file.FileAlreadyExistsException exists) {
                PublicationLockOwner owner = publicationLockOwner(lockPath);
                if (owner.alive() == null || owner.alive()) {
                    throw failure(Boolean.TRUE.equals(owner.alive())
                            ? "competing publication transaction writer is active"
                            : "publication transaction lock is malformed; refusing unsafe recovery");
                }
                try {
                    if (!Files.readString(lockPath, StandardCharsets.UTF_8).equals(owner.raw())) {
                        throw failure("publication transaction lock owner changed during stale-lock recovery");
                    }
                    Files.delete(lockPath);
                } catch (java.nio.file.NoSuchFileException missing) {
                    continue;
                } catch (IOException error) {
                    throw failure(error.getMessage());
                }
            } catch (IOException error) {
                throw failure(error.getMessage());
            }
        }
        throw failure("publication transaction lock could not be acquired");
    }

    private static void releasePublicationLock(Path lockPath, String token) {
        try {
            PublicationLockOwner owner = publicationLockOwner(lockPath);
            if (owner.raw() != null && owner.raw().contains("\"token\":\"" + token + "\"")) {
                Files.deleteIfExists(lockPath);
            }
        } catch (Exception ignored) {}
    }

    private static JsonNode reopenPublicationArtifact(JsonNode ref, Path path) {
        JsonNode value;
        try { value = MAPPER.readTree(Files.readAllBytes(path)); }
        catch (Exception error) { throw failure("publication artifact is not JSON after reopen: " + path); }
        if (!value.isObject() || !text(field(value, "content_sha256")).equals(text(field(ref, "content_sha256")))
                || !text(field(value, "content_sha256")).equals(ownHash(value))) {
            throw failure("publication artifact semantic hash is tampered after reopen: " + path);
        }
        requirePublicationArtifact(value, "publication artifact " + text(field(ref, "role")),
                text(field(ref, "role"))); return value;
    }

    private static byte[] inspectPublicationArtifact(JsonNode ref, Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(path)) throw failure("publication artifact path is a symlink: " + path);
        byte[] bytes;
        try { bytes = Files.readAllBytes(path); }
        catch (IOException error) { throw failure(error.getMessage()); }
        if (bytes.length != integer(field(ref, "bytes"), -1)) {
            throw failure("publication artifact bytes are tampered or dishonest in length: " + path);
        }
        if (!hash(bytes).equals(text(field(ref, "byte_sha256")))) {
            throw failure("publication artifact bytes are tampered: " + path);
        }
        reopenPublicationArtifact(ref, path); return bytes;
    }

    private static Path promotePublicationArtifact(JsonNode ref, String stageRoot, Path recordRoot) throws IOException {
        Path target = recordRoot.resolve(text(field(ref, "path"))).toAbsolutePath().normalize();
        Path staged = recordRoot.resolve(stageRoot)
                .resolve(text(field(ref, "role")) + "-" + text(field(ref, "content_sha256")) + ".json")
                .toAbsolutePath().normalize();
        assertNoSymlinkPublicationPath(target, "publication artifact path " + target);
        assertNoSymlinkPublicationPath(staged, "publication staged artifact path " + staged);
        byte[] targetBytes = inspectPublicationArtifact(ref, target);
        if (targetBytes != null) {
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                inspectPublicationArtifact(ref, staged); Files.delete(staged);
            }
            return target;
        }
        byte[] stagedBytes = inspectPublicationArtifact(ref, staged);
        if (stagedBytes == null) throw failure("publication staged artifact is missing: " + staged);
        ensureParent(target); assertNoSymlinkPublicationPath(target, "publication artifact path " + target);
        try {
            writeExclusiveBytes(target, stagedBytes); inspectPublicationArtifact(ref, target);
            Files.deleteIfExists(staged);
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            inspectPublicationArtifact(ref, target);
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                inspectPublicationArtifact(ref, staged); Files.delete(staged);
            }
        }
        inspectPublicationArtifact(ref, target); return target;
    }

    private static ObjectNode readPublicationTransaction(Path transactionPath, Path explicitRoot) {
        Path target = assertRegularPublicationFile(transactionPath, "publication transaction journal path");
        Path root = explicitRoot == null ? publicationRecordRoot(target, MissingNode.getInstance())
                : explicitRoot.toAbsolutePath().normalize(); ObjectNode value;
        try { value = objectOrEmpty(MAPPER.readTree(Files.readAllBytes(target))); }
        catch (IOException error) { throw failure("publication transaction journal is unreadable: " + error.getMessage()); }
        assertOwnHash(value, schema("publicationTransaction"), "publication transaction"); validateContractSchema(value);
        if (!Set.of("PREPARED", "COMMITTED").contains(text(field(value, "status")))
                || !text(field(value, "transaction_path")).equals(recordRelativePath(root, target, "transaction path"))
                || !field(value, "no_k_mutation").asBoolean(false) || !field(value, "no_rollback").asBoolean(false)) {
            throw failure("publication transaction state is invalid");
        }
        requireHash(field(value, "expected_head_sha256"), "publication expected head");
        requireHash(field(value, "next_head_sha256"), "publication next head");
        requireHash(field(value, "expected_registry_sha256"), "publication expected registry");
        requireHash(field(value, "wfo_sha256"), "publication WFO"); requireHash(field(value, "run_sha256"), "publication run");
        if (!text(field(value, "expected_head_sha256")).equals(text(field(value, "next_head_sha256")))
                || !field(value, "artifact_refs").isArray() || field(value, "artifact_refs").isEmpty()) {
            throw failure("publication transaction lineage is incomplete");
        }
        if (!field(value, "bound_head").isObject() || !field(value, "bound_registry").isObject()) {
            throw failure("publication transaction immutable control snapshots are missing");
        }
        ObjectNode head = validateExposureHead(field(value, "bound_head"));
        if (!text(field(head, "content_sha256")).equals(text(field(value, "expected_head_sha256")))) {
            throw failure("publication bound HEAD does not match its CAS hash");
        }
        ObjectNode validation = object(); validation.set("exposureHead", head);
        validateBehaviorDefinitionRegistry(field(value, "bound_registry"), validation);
        if (!text(field(field(value, "bound_registry"), "content_sha256")).equals(
                text(field(value, "expected_registry_sha256")))) {
            throw failure("publication bound registry does not match its CAS hash");
        }
        String id = publicationTransactionId(text(field(value, "transaction_path")),
                text(field(value, "exposure_head_path")), text(field(value, "registry_path")),
                text(field(value, "stage_root")), text(field(value, "expected_head_sha256")),
                text(field(value, "expected_registry_sha256")), text(field(value, "wfo_sha256")),
                text(field(value, "run_sha256")), field(value, "artifact_refs"));
        if (!id.equals(text(field(value, "transaction_id")))) {
            throw failure("publication transaction ID does not match its content-addressed semantics");
        }
        ObjectNode dummyRun = object(); dummyRun.put("content_sha256", text(field(value, "run_sha256")));
        dummyRun.putObject("wfo").put("artifact", text(field(value, "wfo_sha256")));
        dummyRun.putObject("lineage").put("wfo_sha256", text(field(value, "wfo_sha256")));
        ObjectNode dummyWfo = object(); dummyWfo.put("content_sha256", text(field(value, "wfo_sha256")));
        assertPublicationArtifactRefs(text(field(value, "transaction_path")),
                text(field(value, "exposure_head_path")), text(field(value, "registry_path")),
                text(field(value, "stage_root")), field(value, "artifact_refs"), dummyRun, dummyWfo, null);
        return value;
    }

    private static Path assertRegularPublicationFile(Path raw, String label) {
        Path absolute = raw.toAbsolutePath().normalize(); assertNoSymlinkPublicationPath(absolute, label);
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) throw failure(label + " is missing: " + absolute);
        try { requireRegularSingleLink(absolute, label); }
        catch (IllegalArgumentException error) {
            throw failure(label + " must be a regular single-link non-symlink file");
        }
        return absolute;
    }

    private static Path publicationControlPath(Path root, String value, String label) {
        String relative = assertRecordRelativePath(value, label); Path path = root.resolve(relative).toAbsolutePath().normalize();
        if (!pathWithin(root, path)) throw failure(label + " escapes the publication record root");
        assertNoSymlinkPublicationPath(path, label); return path;
    }

    private static final List<String> PUBLICATION_ROLES = List.of("wfo", "research_run",
            "final_oos_artifact", "final_oos_vector_inventory");

    private static String publicationSchema(String role) {
        return switch (role) {
            case "wfo" -> schema("wfo");
            case "research_run" -> "strategy-research-run/5";
            case "final_oos_artifact" -> schema("input");
            case "final_oos_vector_inventory" -> schema("vectors");
            default -> null;
        };
    }

    private static JsonNode requireContentArtifact(JsonNode value, String label) {
        if (!value.isObject() || !HASH_RE.matcher(text(field(value, "content_sha256"))).matches()
                || !text(field(value, "content_sha256")).equals(ownHash(value))) {
            throw failure(label + " is not a hash-bound artifact");
        }
        return value;
    }

    private static JsonNode requirePublicationArtifact(JsonNode value, String label, String role) {
        requireContentArtifact(value, label); String expected = publicationSchema(role);
        if (expected == null || !expected.equals(text(field(value, "schema")))
                || numberJs(field(value, "version")) != 1) {
            throw failure(label + " must use the registered " + (expected == null ? "publication" : expected)
                    + " schema/version");
        }
        try { validateRegisteredSchema(value); }
        catch (RuntimeException error) { throw failure(label + " registered schema validation failed: " + error.getMessage()); }
        if ("research_run".equals(role)) validateAuthoritativeRunStageInventory(value, label);
        return value;
    }

    private static void validateAuthoritativeRunStageInventory(JsonNode value, String label) {
        if (!"strategy-research-run/5".equals(text(field(value, "schema")))
                || !"AUTHORITATIVE_RECOMPUTED".equals(text(field(value, "provenance")))) return;
        boolean retainsOos = HASH_RE.matcher(text(field(value, "oos_artifact_sha256"))).matches()
                || HASH_RE.matcher(text(field(value, "vector_inventory_sha256"))).matches()
                || defined(field(value, "stage_artifacts")) || defined(field(value, "stage_artifact_refs"));
        if ("REJECTED".equals(text(field(value, "decision"))) && !retainsOos) return;
        if (!Set.of("REJECTED", "SHADOW", "CANDIDATE_REVIEW").contains(text(field(value, "decision")))) {
            throw failure(label + " has a non-terminal decision");
        }
        if (!"REJECTED".equals(text(field(value, "decision")))
                && !field(field(value, "gate_status"), "all_required_stages").asBoolean(false)) {
            throw failure(label + " claims a non-rejected decision without all required stages passing");
        }
        for (String name : List.of("execution_fills_sha256", "selected_trades_sha256", "stresses_sha256",
                "portfolio_sha256")) requireHash(field(value, name), label + "." + name);
        for (String name : List.of("feature_rows_sha256", "label_rows_sha256", "execution_rows_sha256",
                "mark_rows_sha256", "wfo_sha256")) requireHash(field(field(value, "lineage"), name),
                label + ".lineage." + name);
        requireHash(field(field(value, "wfo"), "artifact"), label + ".wfo.artifact");
        List<String> required = List.of("genetic", "execution_fills", "selected_trades", "stresses",
                "portfolio", "final_oos_artifact", "final_oos_vector_inventory");
        JsonNode inventory = field(value, "stage_artifacts"); List<String> inventoryKeys = new ArrayList<>(fieldNames(inventory));
        List<String> expectedKeys = new ArrayList<>(required); inventoryKeys.sort(String::compareTo); expectedKeys.sort(String::compareTo);
        if (!inventory.isObject() || !inventoryKeys.equals(expectedKeys)) {
            throw failure(label + " is missing the complete physical stage artifact inventory");
        }
        for (String name : required) requireHash(field(inventory, name), label + ".stage_artifacts." + name);
        for (String name : List.of("execution_fills", "selected_trades", "stresses", "portfolio")) {
            if (!text(field(inventory, name)).equals(text(field(value, name + "_sha256")))) {
                throw failure(label + " stage artifact inventory disagrees with " + name + "_sha256");
            }
        }
        for (Map.Entry<String, String> binding : Map.of("final_oos_artifact", "oos_artifact_sha256",
                "final_oos_vector_inventory", "vector_inventory_sha256").entrySet()) {
            requireHash(field(value, binding.getValue()), label + "." + binding.getValue());
            if (!text(field(inventory, binding.getKey())).equals(text(field(value, binding.getValue())))) {
                throw failure(label + " stage artifact inventory disagrees with " + binding.getValue());
            }
        }
        JsonNode refs = field(value, "stage_artifact_refs"); List<String> refKeys = new ArrayList<>(fieldNames(refs));
        refKeys.sort(String::compareTo);
        if (!refs.isObject() || !refKeys.equals(expectedKeys)) {
            throw failure(label + " is missing the complete physical stage artifact reference inventory");
        }
        for (String name : required) {
            JsonNode ref = field(refs, name); String expectedSchema = name.startsWith("final_oos_")
                    ? ("final_oos_artifact".equals(name) ? schema("input") : schema("vectors"))
                    : "strategy-v5-authoritative-stage-artifact/1";
            if (!ref.isObject() || !expectedSchema.equals(text(field(ref, "schema")))
                    || integer(field(ref, "version"), Long.MIN_VALUE) != 1 || !field(ref, "path").isTextual()
                    || text(field(ref, "path")).isEmpty()
                    || !HASH_RE.matcher(text(field(ref, "content_sha256"))).matches()
                    || !HASH_RE.matcher(text(field(ref, "byte_sha256"))).matches()
                    || !field(ref, "bytes").isIntegralNumber() || field(ref, "bytes").asLong() < 1) {
                throw failure(label + ".stage_artifact_refs." + name + " is incomplete");
            }
            if (!text(field(ref, "content_sha256")).equals(text(field(inventory, name)))) {
                throw failure(label + ".stage_artifact_refs." + name + " disagrees with stage inventory");
            }
        }
        if (!text(field(field(value, "wfo"), "artifact")).equals(
                text(field(field(value, "lineage"), "wfo_sha256")))) {
            throw failure(label + " WFO artifact is not bound through lineage");
        }
    }

    private static void assertExposurePrefix(JsonNode prior, JsonNode next, String label) {
        validateExposureHead(prior); validateExposureHead(next);
        if (!text(field(next, "hypothesis_family")).equals(text(field(prior, "hypothesis_family")))) {
            throw failure(label + " family changed");
        }
        if (integer(field(next, "cumulative_k"), -1) < integer(field(prior, "cumulative_k"), -1)
                || integer(field(next, "exposure_attempt_k"), -1) < integer(field(prior, "exposure_attempt_k"), -1)) {
            throw failure(label + " rolls back cumulative K");
        }
        ArrayNode priorEntries = array(field(prior, "entries")); ArrayNode nextEntries = array(field(next, "entries"));
        for (int index = 0; index < priorEntries.size(); index++) if (nextEntries.size() <= index
                || !stable(priorEntries.get(index)).equals(stable(nextEntries.get(index)))) {
            throw failure(label + " does not preserve predecessor entry " + (index + 1));
        }
    }

    private static void assertRegistryPrefix(JsonNode prior, JsonNode next, String label) {
        validateBehaviorDefinitionRegistry(prior); validateBehaviorDefinitionRegistry(next);
        if (!text(field(next, "hypothesis_family")).equals(text(field(prior, "hypothesis_family")))) {
            throw failure(label + " family changed");
        }
        ArrayNode priorEntries = array(field(prior, "entries")); ArrayNode nextEntries = array(field(next, "entries"));
        if (nextEntries.size() < priorEntries.size()) throw failure(label + " rolls back durable behavior definitions");
        for (int index = 0; index < priorEntries.size(); index++) if (!stable(priorEntries.get(index))
                .equals(stable(nextEntries.get(index)))) {
            throw failure(label + " does not preserve predecessor entry " + (index + 1));
        }
    }

    private static byte[] publicationBytes(JsonNode value) {
        return NodePrettyJson.write(value).getBytes(StandardCharsets.UTF_8);
    }

    private static ArrayNode publicationArtifactRows(JsonNode artifacts, Path recordRoot) {
        ArrayNode output = array(); int index = 0;
        for (JsonNode row : array(artifacts)) {
            if (!row.isObject() || !truthy(field(row, "path")) || !field(row, "value").isObject()
                    || !truthy(field(row, "role"))) throw failure("publication artifact " + index + " is incomplete");
            String role = jsString(field(row, "role")); JsonNode value = requirePublicationArtifact(field(row, "value"),
                    "publication artifact " + role, role); byte[] bytes = publicationBytes(value);
            String rawPath = jsString(field(row, "path")); Path absolute = Path.of(rawPath).isAbsolute()
                    ? Path.of(rawPath).toAbsolutePath().normalize() : recordRoot.resolve(rawPath).toAbsolutePath().normalize();
            if (!pathWithin(recordRoot, absolute)) throw failure("publication artifact path is outside record root: " + absolute);
            ObjectNode ref = object(); ref.put("role", role); ref.put("schema", text(field(value, "schema")));
            ref.set("version", cloneNode(field(value, "version"))); ref.put("path",
                    recordRoot.relativize(absolute).toString().replace('\\', '/'));
            ref.put("content_sha256", text(field(value, "content_sha256"))); ref.put("byte_sha256", hash(bytes));
            ref.put("bytes", bytes.length); output.add(ref); index++;
        }
        return output;
    }

    private static String publicationTransactionId(String transactionPath, String exposureHeadPath,
            String registryPath, String stageRoot, String expectedHead, String expectedRegistry,
            String wfoSha, String runSha, JsonNode refs) {
        ObjectNode value = object(); value.put("schema", schema("publicationTransaction"));
        value.put("transaction_path", transactionPath); value.put("exposure_head_path", exposureHeadPath);
        value.put("registry_path", registryPath); value.put("stage_root", stageRoot);
        value.put("expected_head_sha256", expectedHead); value.put("expected_registry_sha256", expectedRegistry);
        value.put("wfo_sha256", wfoSha); value.put("run_sha256", runSha); ArrayNode artifacts = array();
        for (JsonNode ref : array(refs)) {
            ObjectNode row = object();
            for (String name : List.of("role", "schema", "version", "path", "content_sha256", "byte_sha256", "bytes")) {
                row.set(name, cloneNode(field(ref, name)));
            }
            artifacts.add(row);
        }
        value.set("artifacts", artifacts); return hash(value);
    }

    private static Path publicationRecordRoot(Path transactionPath, JsonNode explicit) {
        if (truthy(explicit)) return Path.of(jsString(explicit)).toAbsolutePath().normalize();
        Path target = transactionPath.toAbsolutePath().normalize(); Path parent = target.getParent();
        String name = parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString();
        if (("transactions".equals(name) || ".transactions".equals(name)) && parent.getParent() != null) {
            return parent.getParent();
        }
        return parent;
    }

    private static boolean pathWithin(Path parent, Path child) {
        Path root = parent.toAbsolutePath().normalize(), target = child.toAbsolutePath().normalize();
        return target.equals(root) || target.startsWith(root);
    }

    private static String assertRecordRelativePath(String value, String label) {
        String raw = value == null ? "" : value; String[] parts = raw.split("/", -1);
        boolean control = raw.codePoints().anyMatch(code -> code <= 0x1f || code >= 0x7f && code <= 0x9f);
        boolean drive = raw.matches("^[A-Za-z]:.*");
        if (raw.isEmpty() || raw.startsWith("/") || drive || raw.contains("\\") || control
                || java.util.Arrays.stream(parts).anyMatch(part -> part.isEmpty() || ".".equals(part) || "..".equals(part))
                || !Path.of(raw).normalize().toString().replace('\\', '/').equals(raw)) {
            throw failure(label + " must be a non-empty normalized record-root-relative path");
        }
        return raw;
    }

    private static String recordRelativePath(Path root, Path value, String label) {
        Path absolute = value.toAbsolutePath().normalize(), boundary = root.toAbsolutePath().normalize();
        if (!pathWithin(boundary, absolute)) throw failure(label + " must be inside the publication record root");
        String relative = boundary.relativize(absolute).toString().replace('\\', '/');
        if (relative.isEmpty() || relative.equals("..") || relative.startsWith("../")
                || relative.indexOf('\0') >= 0 || relative.startsWith("/")) {
            throw failure(label + " must be a non-empty record-root-relative path");
        }
        return assertRecordRelativePath(relative, label);
    }

    private static void assertNoSymlinkPublicationPath(Path raw, String label) {
        Path absolute = raw.toAbsolutePath().normalize(); Path cursor = absolute.getRoot();
        for (Path component : absolute) {
            cursor = cursor == null ? component : cursor.resolve(component);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) break;
            if (Files.isSymbolicLink(cursor)) {
                boolean macVar = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                        && "/var".equals(cursor.toString());
                if (!macVar) throw failure(label + " contains a symlink path component: " + cursor);
            }
            if (!cursor.equals(absolute) && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(cursor)) throw failure(label + " parent is not a directory: " + cursor);
        }
    }

    private static void assertPublicationArtifactRefs(String transactionPath, String exposureHeadPath,
            String registryPath, String stageRoot, JsonNode refs, JsonNode run, JsonNode wfo, Path recordRoot) {
        assertRecordRelativePath(transactionPath, "publication transaction path");
        assertRecordRelativePath(exposureHeadPath, "publication exposure HEAD path");
        assertRecordRelativePath(registryPath, "publication registry path");
        assertRecordRelativePath(stageRoot, "publication stage root");
        Set<String> roles = new HashSet<>(); Set<Path> paths = new HashSet<>();
        List<String> forbidden = List.of(transactionPath, transactionPath + ".lock", exposureHeadPath,
                registryPath, stageRoot); int index = 0;
        for (JsonNode ref : array(refs)) {
            String relative = assertRecordRelativePath(text(field(ref, "path")), "publication artifact " + index + " path");
            Path target = recordRoot == null ? Path.of(relative).toAbsolutePath().normalize()
                    : recordRoot.resolve(relative).toAbsolutePath().normalize();
            String role = text(field(ref, "role"));
            if (!roles.add(role)) throw failure("publication artifact role is duplicated: " + role);
            if (!paths.add(target)) throw failure("publication artifact path is duplicated: " + target);
            for (String control : forbidden) {
                Path controlPath = recordRoot == null ? Path.of(control).toAbsolutePath().normalize()
                        : recordRoot.resolve(control).toAbsolutePath().normalize();
                if (target.equals(controlPath) || pathWithin(controlPath, target)) {
                    throw failure("publication artifact path collides with a transaction/control path: " + target);
                }
            }
            if (recordRoot != null) assertNoSymlinkPublicationPath(target, "publication artifact path " + target);
            String expected = publicationSchema(role);
            if (expected == null || !expected.equals(text(field(ref, "schema")))
                    || integer(field(ref, "version"), Long.MIN_VALUE) != 1) {
                throw failure("publication artifact " + index + " schema/version binding is invalid");
            }
            if (!field(ref, "bytes").isIntegralNumber() || field(ref, "bytes").asLong() < 1
                    || !HASH_RE.matcher(text(field(ref, "content_sha256"))).matches()
                    || !HASH_RE.matcher(text(field(ref, "byte_sha256"))).matches()) {
                throw failure("publication artifact " + index + " hash/size binding is invalid");
            }
            index++;
        }
        if (recordRoot != null) assertNoSymlinkPublicationPath(recordRoot.resolve(stageRoot),
                "publication stage root " + stageRoot);
        List<JsonNode> researchRefs = new ArrayList<>(), wfoRefs = new ArrayList<>(), finalRefs = new ArrayList<>();
        for (JsonNode ref : array(refs)) {
            String role = text(field(ref, "role"));
            if ("research_run".equals(role)) researchRefs.add(ref); if ("wfo".equals(role)) wfoRefs.add(ref);
            if (role.startsWith("final_oos_")) finalRefs.add(ref);
        }
        if (researchRefs.size() != 1) throw failure("publication transaction must include exactly one research_run artifact");
        if (wfoRefs.size() != 1) throw failure("publication transaction must include exactly one wfo artifact");
        boolean hydrated = Set.of("REJECTED", "SHADOW", "CANDIDATE_REVIEW").contains(text(field(run, "decision")));
        boolean requiresFinal = hydrated ? !"REJECTED".equals(text(field(run, "decision")))
                || HASH_RE.matcher(text(field(run, "oos_artifact_sha256"))).matches()
                || HASH_RE.matcher(text(field(run, "vector_inventory_sha256"))).matches() : !finalRefs.isEmpty();
        if (requiresFinal && (finalRefs.size() != 2 || !roles.contains("final_oos_artifact")
                || !roles.contains("final_oos_vector_inventory"))) {
            throw failure("publication transaction must include the final OOS artifact and vector inventory");
        }
        if (!requiresFinal && !finalRefs.isEmpty()) throw failure("rejected publication may not carry a partial final OOS inventory");
        if (roles.size() != (requiresFinal ? 4 : 2)) {
            throw failure("publication transaction artifact inventory has an unexpected role set");
        }
        if (!text(field(researchRefs.getFirst(), "content_sha256")).equals(text(field(run, "content_sha256")))) {
            throw failure("publication research_run artifact is not bound to the exact research run");
        }
        if (!text(field(wfoRefs.getFirst(), "content_sha256")).equals(text(field(wfo, "content_sha256")))) {
            throw failure("publication WFO artifact is not bound to the exact final WFO");
        }
        if (!text(field(field(run, "wfo"), "artifact")).equals(text(field(wfo, "content_sha256")))
                || !text(field(field(run, "lineage"), "wfo_sha256")).equals(text(field(wfo, "content_sha256")))) {
            throw failure("publication research run is not bound to the final WFO artifact");
        }
        if (hydrated && requiresFinal) {
            JsonNode artifactRef = findRole(refs, "final_oos_artifact"), vectorRef = findRole(refs, "final_oos_vector_inventory");
            if (!text(field(artifactRef, "content_sha256")).equals(text(field(run, "oos_artifact_sha256")))
                    || !text(field(vectorRef, "content_sha256")).equals(text(field(run, "vector_inventory_sha256")))) {
                throw failure("publication final OOS artifacts are not bound to the research run");
            }
            if (!text(field(run, "oos_artifact_sha256")).equals(text(field(wfo, "oos_artifact_sha256")))
                    || !text(field(run, "vector_inventory_sha256")).equals(text(field(wfo, "vector_inventory_sha256")))
                    || !text(field(run, "oos_validation_exposure_head_sha256")).equals(
                    text(field(wfo, "validation_exposure_head_sha256")))
                    || !stable(field(run, "oos_episode_ids")).equals(stable(field(wfo, "oos_episode_ids")))) {
                throw failure("publication final OOS lineage is inconsistent across the research run and WFO");
            }
        }
        if (recordRoot != null) verifyPhysicalStageArtifactRefs(run, recordRoot, "publication research run", wfo);
    }

    private static JsonNode findRole(JsonNode refs, String role) {
        for (JsonNode ref : array(refs)) if (role.equals(text(field(ref, "role")))) return ref;
        return MissingNode.getInstance();
    }

    private static void verifyPhysicalStageArtifactRefs(JsonNode run, Path recordRoot, String label, JsonNode wfo) {
        if ("REJECTED".equals(text(field(run, "decision")))
                && !HASH_RE.matcher(text(field(run, "oos_artifact_sha256"))).matches()
                && !defined(field(run, "stage_artifact_refs"))) return;
        validateAuthoritativeRunStageInventory(run, label); JsonNode refs = field(run, "stage_artifact_refs");
        Map<String, JsonNode> reopened = new LinkedHashMap<>();
        Map<String, String> expectedStages = Map.of("genetic", "GENETIC", "execution_fills", "EXECUTION_FILLS",
                "selected_trades", "SELECTED_TRADES", "stresses", "STRESSES", "portfolio", "PORTFOLIO");
        for (String name : List.of("genetic", "execution_fills", "selected_trades", "stresses", "portfolio",
                "final_oos_artifact", "final_oos_vector_inventory")) {
            JsonNode ref = field(refs, name); String relative = assertRecordRelativePath(text(field(ref, "path")),
                    label + ".stage_artifact_refs." + name + ".path"); Path path = recordRoot.resolve(relative).normalize();
            if (!pathWithin(recordRoot, path)) throw failure(label + ".stage_artifact_refs." + name + " escapes the record root");
            assertNoSymlinkPublicationPath(path, label + ".stage_artifact_refs." + name); requireRegularSingleLink(path,
                    label + ".stage_artifact_refs." + name);
            byte[] bytes; JsonNode value;
            try { bytes = Files.readAllBytes(path); }
            catch (IOException error) { throw failure(label + ".stage_artifact_refs." + name + " is missing"); }
            if (bytes.length != integer(field(ref, "bytes"), -1) || !hash(bytes).equals(text(field(ref, "byte_sha256")))) {
                throw failure(label + ".stage_artifact_refs." + name + " bytes are tampered");
            }
            try { value = MAPPER.readTree(bytes); }
            catch (IOException error) { throw failure(label + ".stage_artifact_refs." + name + " is not JSON: " + error.getMessage()); }
            requireContentArtifact(value, label + ".stage_artifact_refs." + name);
            String expectedSchema = name.startsWith("final_oos_") ? publicationSchema(name) :
                    "strategy-v5-authoritative-stage-artifact/1";
            if (!text(field(value, "schema")).equals(text(field(ref, "schema")))
                    || !expectedSchema.equals(text(field(ref, "schema")))
                    || integer(field(value, "version"), -1) != integer(field(ref, "version"), -1)
                    || !text(field(value, "content_sha256")).equals(text(field(ref, "content_sha256")))
                    || expectedStages.containsKey(name) && !expectedStages.get(name).equals(text(field(value, "stage")))) {
                throw failure(label + ".stage_artifact_refs." + name + " semantic binding is invalid");
            }
            try { validateRegisteredSchema(value); }
            catch (RuntimeException error) { throw failure(label + ".stage_artifact_refs." + name
                    + " schema validation failed: " + error.getMessage()); }
            reopened.put(name, value);
            if ("final_oos_artifact".equals(name)) {
                ObjectNode validation = object(); validation.put("allowSubset", true);
                validateStatisticalArtifactSet(value, validation);
                List<String> episodeIds = new ArrayList<>(); array(field(value, "episodes"))
                        .forEach(row -> episodeIds.add(text(field(row, "episode_id"))));
                if (!text(field(value, "content_sha256")).equals(text(field(run, "oos_artifact_sha256")))
                        || !text(field(value, "exposure_head_sha256")).equals(
                        text(field(run, "oos_validation_exposure_head_sha256")))
                        || !stable(MAPPER.valueToTree(episodeIds)).equals(stable(field(run, "oos_episode_ids")))) {
                    throw failure(label + ".stage_artifact_refs." + name + " is not bound to the retained OOS artifact");
                }
            }
            if ("final_oos_vector_inventory".equals(name)) {
                boolean incomplete = !field(value, "episode_ids").isArray()
                        || !stable(field(value, "episode_ids")).equals(stable(field(run, "oos_episode_ids")))
                        || !text(field(value, "content_sha256")).equals(text(field(run, "vector_inventory_sha256")))
                        || !text(field(value, "exposure_head_sha256")).equals(
                        text(field(run, "oos_validation_exposure_head_sha256")));
                if (!incomplete) for (JsonNode rows : field(value, "vectors")) if (!rows.isArray()
                        || rows.size() != field(value, "episode_ids").size()) incomplete = true;
                if (incomplete) throw failure(label + ".stage_artifact_refs." + name + " is incomplete");
            }
        }
        if (wfo != null && wfo.isObject()) {
            assertWfoRetainedOosBinding(wfo, reopened.get("final_oos_artifact"),
                    reopened.get("final_oos_vector_inventory"), label + " retained OOS evidence");
            assertRetainedOosPhysicalFills(wfo, reopened.get("final_oos_vector_inventory"),
                    reopened.get("execution_fills"), label + " retained OOS physical fills");
        }
    }

    private static void assertRetainedOosPhysicalFills(JsonNode wfo, JsonNode vector, JsonNode executionFills,
            String label) {
        JsonNode rows = field(executionFills, "rows");
        if (!rows.isArray()) throw failure(label + " artifact lacks physical fill rows");
        Map<String, JsonNode> fills = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String id = truthy(field(row, "episode_id")) ? jsString(field(row, "episode_id")) : "";
            if (id.isEmpty() || fills.putIfAbsent(id, row) != null) {
                throw failure(label + " has a duplicate episode identity: " + (id.isEmpty() ? "?" : id));
            }
        }
        Set<String> episodes = textSet(array(field(wfo, "oos_episode_ids"))); Set<String> referenced = new HashSet<>();
        for (JsonNode outer : array(field(wfo, "asset_decisions"))) {
            JsonNode decisions = field(outer, "asset_decisions");
            for (String assetName : fieldNames(decisions)) {
                JsonNode decision = field(decisions, assetName); String alias = text(field(decision,
                        "selected_behavior_alias_sha256"));
                if (alias.isEmpty() || !field(decision, "selected_return_vector").isArray()) continue;
                JsonNode finalRows = field(field(vector, "vectors"), alias);
                if (!finalRows.isArray()) throw failure(label + " is missing selected alias " + alias);
                Map<String, JsonNode> finalById = new HashMap<>();
                finalRows.forEach(row -> finalById.put(text(field(row, "episode_id")), row));
                for (JsonNode selected : array(field(decision, "selected_return_vector"))) {
                    String id = text(field(selected, "episode_id"));
                    if (!episodes.contains(id)) throw failure(label + " selected episode is outside retained OOS scope: " + id);
                    JsonNode row = finalById.get(id), fill = fills.get(id);
                    if (row == null || Double.compare(numberJs(field(row, "net_r")), numberJs(field(selected, "net_r"))) != 0
                            || !sameBoolean(field(row, "traded"), field(selected, "traded"))) {
                        throw failure(label + " vector " + alias + "/" + id + " disagrees with the retained fold value");
                    }
                    if (field(selected, "traded").asBoolean(false)) {
                        referenced.add(id);
                        if (fill == null || Double.compare(numberJs(field(fill, "net_r")), numberJs(field(row, "net_r"))) != 0
                                || !text(field(fill, "asset")).toLowerCase(Locale.ROOT)
                                .equals(assetName.toLowerCase(Locale.ROOT))) {
                            throw failure(label + " traded vector " + alias + "/" + id
                                    + " disagrees with the physical fill");
                        }
                    } else if (fill != null) throw failure(label + " untraded vector " + alias + "/" + id
                            + " has a physical fill");
                }
            }
        }
        for (String id : fills.keySet()) if (!referenced.contains(id)) {
            throw failure(label + " contains an unreferenced physical fill: " + id);
        }
    }

    private static void assertPublicationLineage(JsonNode wfo, JsonNode run, JsonNode boundHead) {
        validateNestedWfoArtifact(wfo);
        if (!boundHead.isObject() || !HASH_RE.matcher(text(field(boundHead, "content_sha256"))).matches()) {
            throw failure("publication bound exposure HEAD is missing");
        }
        validateExposureHead(field(wfo, "validation_exposure_head"));
        if (!text(field(field(wfo, "validation_exposure_head"), "content_sha256")).equals(
                text(field(wfo, "validation_exposure_head_sha256")))
                || integer(field(field(wfo, "validation_exposure_head"), "cumulative_k"), -1) !=
                integer(field(wfo, "validation_exposure_head_cumulative_k"), -2)) {
            throw failure("publication WFO validation exposure HEAD snapshot does not match its lineage fields");
        }
        assertExposurePrefix(field(wfo, "validation_exposure_head"), boundHead, "publication validation exposure");
        if (!text(field(wfo, "exposure_head_sha256")).equals(text(field(boundHead, "content_sha256")))) {
            throw failure("publication WFO exposure HEAD is not bound to the transaction CAS HEAD");
        }
        if (integer(field(wfo, "cumulative_k"), -1) != integer(field(boundHead, "cumulative_k"), -2)) {
            throw failure("publication WFO cumulative K is not bound to the transaction CAS HEAD");
        }
        if (!HASH_RE.matcher(text(field(wfo, "validation_exposure_head_sha256"))).matches()) {
            throw failure("publication WFO validation exposure HEAD lineage is invalid");
        }
        requirePublicationArtifact(run, "publication research run", "research_run");
        if (!"AUTHORITATIVE_RECOMPUTED".equals(text(field(run, "provenance")))) {
            throw failure("publication research run provenance is not authoritative recomputation");
        }
        String runDecision = text(field(run, "decision"));
        if (!Set.of("REJECTED", "SHADOW", "CANDIDATE_REVIEW").contains(runDecision)) {
            throw failure("publication research run decision is not a publishable terminal decision");
        }
        if (integer(field(field(run, "accounting"), "cumulative_family_k"), -1)
                != integer(field(boundHead, "cumulative_k"), -2)) {
            throw failure("publication research run accounting cumulative family K is not bound to the transaction CAS HEAD");
        }
        if (!sameBoolean(field(field(run, "wfo"), "pass"), field(wfo, "gate_pass"))
                || !text(field(field(run, "wfo"), "status")).equals(text(field(wfo, "decision")))) {
            throw failure("publication research run WFO status/pass does not match the final WFO");
        }
        if (!sameBoolean(field(field(run, "gate_status"), "wfo"), field(wfo, "gate_pass"))) {
            throw failure("publication research run gate_status.wfo does not match the final WFO gate");
        }
        if ("REJECTED".equals(text(field(wfo, "decision"))) && !"REJECTED".equals(runDecision)) {
            throw failure("a rejected final WFO cannot publish a non-rejected research run");
        }
        if (Set.of("SHADOW", "CANDIDATE_REVIEW").contains(runDecision)) {
            JsonNode audit = field(wfo, "audit"); boolean allAuditGates = field(audit, "gates").isObject();
            if (allAuditGates) for (JsonNode gate : field(audit, "gates")) if (!gate.isBoolean() || !gate.asBoolean()) {
                allAuditGates = false; break;
            }
            if (!field(audit, "pass").asBoolean(false) || !"SHADOW".equals(text(field(audit, "decision")))
                    || !allAuditGates) throw failure(
                    "a publishable non-rejected research run requires a passing WFO audit with every required gate true");
            boolean assetsPass = field(wfo, "asset_decisions_final").isArray();
            for (JsonNode row : array(field(wfo, "asset_decisions_final"))) if (!field(row, "pass").asBoolean(false)) assetsPass = false;
            if (!assetsPass || !field(field(wfo, "portfolio_decision"), "pass").asBoolean(false)) {
                throw failure("a publishable non-rejected research run requires passing WFO asset and portfolio decisions");
            }
            JsonNode gates = field(run, "gate_status");
            if (!"SHADOW".equals(text(field(wfo, "decision"))) || !field(wfo, "gate_pass").asBoolean(false)
                    || !field(gates, "wfo").asBoolean(false) || !field(gates, "stress").asBoolean(false)
                    || !field(gates, "portfolio").asBoolean(false) || !field(gates, "all_required_stages").asBoolean(false)) {
                throw failure("a publishable non-rejected research run requires a fully passing WFO, stress, portfolio, and stage gate set");
            }
        } else {
            JsonNode gates = field(run, "gate_status");
            if (field(gates, "all_required_stages").asBoolean(false)
                    || field(gates, "wfo").asBoolean(false) && field(gates, "stress").asBoolean(false)
                    && field(gates, "portfolio").asBoolean(false)) {
                throw failure("a rejected research run must retain a failed required stage gate");
            }
        }
    }

    public static ObjectNode makeVectorInventory(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        ObjectNode exposure = validateExposureHead(field(options, "exposureHead"));
        ArrayNode episodeIds = array(field(options, "episodeIds"));
        Set<String> uniqueIds = new HashSet<>(); episodeIds.forEach(id -> uniqueIds.add(jsString(id)));
        if (episodeIds.isEmpty() || uniqueIds.size() != episodeIds.size()) {
            throw failure("vector inventory requires unique episode IDs");
        }
        List<String> aliases = new ArrayList<>();
        array(field(exposure, "entries")).forEach(row -> aliases.add(text(field(row, "behavior_sha256"))));
        JsonNode vectors = field(options, "vectors"); List<String> vectorKeys = new ArrayList<>();
        if (vectors.isObject()) vectors.fieldNames().forEachRemaining(vectorKeys::add);
        List<String> sortedAliases = new ArrayList<>(aliases); sortedAliases.sort(String::compareTo); vectorKeys.sort(String::compareTo);
        if (!vectors.isObject() || !vectorKeys.equals(sortedAliases)) {
            throw failure("vector inventory aliases must exactly equal the exposure head");
        }
        ObjectNode normalized = object();
        for (String alias : aliases) {
            requireHash(JSON.textNode(alias), "vector alias"); ArrayNode rows = array(field(vectors, alias));
            if (rows.size() != episodeIds.size()) throw failure("vector " + alias + " is incomplete");
            Set<String> rowIds = new HashSet<>(); rows.forEach(row -> rowIds.add(text(field(row, "episode_id"))));
            if (rowIds.size() != rows.size()) throw failure("vector " + alias + " has duplicate episode IDs");
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            rows.forEach(row -> byId.put(text(field(row, "episode_id")), row));
            Set<String> seen = new HashSet<>(); ArrayNode output = array();
            for (JsonNode idNode : episodeIds) {
                String id = jsString(idNode); JsonNode row = byId.get(id);
                if (row == null) throw failure("vector " + alias + " is missing an episode");
                if (!row.isObject() || !uniqueIds.contains(text(field(row, "episode_id"))) || !seen.add(id)) {
                    throw failure("vector " + alias + " has duplicate or unknown episode");
                }
                boolean eligible = !field(row, "eligible").isBoolean() || field(row, "eligible").asBoolean();
                double net = finiteNumber(field(row, "net_r"), alias + ".net_r");
                boolean traded = field(row, "traded").asBoolean(false);
                if (!eligible && (net != 0 || traded)) throw failure("vector " + alias + " has an invalid pre-discovery row");
                ObjectNode item = object(); item.put("episode_id", id); item.put("net_r", net);
                item.put("traded", traded); item.put("eligible", eligible); output.add(item);
            }
            if (seen.size() != episodeIds.size()) throw failure("vector " + alias + " is missing an episode");
            normalized.set(alias, output);
        }
        ObjectNode value = object(); value.put("schema", schema("vectors")); value.put("version", 1);
        value.put("exposure_head_sha256", text(field(exposure, "content_sha256")));
        value.set("episode_ids", episodeIds.deepCopy()); value.set("vectors", normalized);
        ObjectNode result = withHash(value); validateVectorInventory(result, exposure, episodeIds);
        validateRegisteredSchema(result); return result;
    }

    public static boolean validateVectorInventory(JsonNode value, JsonNode rawHead, JsonNode rawEpisodeIds) {
        assertOwnHash(value, schema("vectors"), "vector inventory");
        if (!rawHead.isObject() || !text(field(value, "exposure_head_sha256"))
                .equals(text(field(rawHead, "content_sha256")))) {
            throw failure("vector inventory/exposure head lineage mismatch");
        }
        ArrayNode episodeIds = array(rawEpisodeIds);
        if (!field(value, "episode_ids").isArray() || !stable(field(value, "episode_ids")).equals(stable(episodeIds))) {
            throw failure("vector inventory episode binding mismatch");
        }
        Set<String> aliases = new LinkedHashSet<>();
        array(field(rawHead, "entries")).forEach(row -> aliases.add(text(field(row, "behavior_sha256"))));
        JsonNode vectors = field(value, "vectors"); List<String> keys = new ArrayList<>();
        if (vectors.isObject()) vectors.fieldNames().forEachRemaining(keys::add);
        if (keys.size() != aliases.size() || keys.stream().anyMatch(key -> !aliases.contains(key))) {
            throw failure("vector inventory is a subset or superset of the exposure head");
        }
        List<String> expectedIds = new ArrayList<>(); episodeIds.forEach(id -> expectedIds.add(jsString(id)));
        for (String alias : aliases) {
            ArrayNode rows = array(field(vectors, alias)); List<String> actualIds = new ArrayList<>();
            rows.forEach(row -> actualIds.add(text(field(row, "episode_id"))));
            Set<String> unique = new HashSet<>(actualIds); boolean malformed = rows.size() != expectedIds.size()
                    || !actualIds.equals(expectedIds) || unique.size() != expectedIds.size();
            for (JsonNode row : rows) {
                double net = numberJs(field(row, "net_r")); boolean eligible = field(row, "eligible").asBoolean(false);
                malformed |= !expectedIds.contains(text(field(row, "episode_id"))) || !Double.isFinite(net)
                        || !field(row, "traded").isBoolean() || !field(row, "eligible").isBoolean()
                        || (!eligible && (net != 0 || field(row, "traded").asBoolean()));
            }
            if (malformed) throw failure("vector " + alias + " is incomplete or misaligned");
        }
        return true;
    }

    public static boolean validateStatisticalAudit(JsonNode value) {
        assertOwnHash(value, schema("audit"), "statistical audit");
        if (!field(value, "fail_closed_missing_inputs").asBoolean(false) || !field(value, "gates").isObject()
                || !field(value, "pass").isBoolean() || "ACTIVE".equals(text(field(value, "decision")))) {
            throw failure("statistical audit semantic fields are missing or activation was attempted");
        }
        if (!field(value, "independent_opportunity_count").isIntegralNumber()
                || !field(value, "independent_trade_count").isIntegralNumber()
                || !HASH_RE.matcher(text(field(value, "market_cluster_inventory_sha256"))).matches()) {
            throw failure("statistical audit is missing the canonical independent market-cluster inventory");
        }
        for (String gate : List.of("hard_metrics", "baseline_comparison", "bootstrap_p20_positive",
                "weighted_bootstrap_p20_positive", "max_statistic", "search_adjusted_expectancy_positive",
                "dsr", "pbo", "minimum_independent_episodes", "recent_oos_positive", "earlier_blocks",
                "positive_years", "positive_outer_folds", "plateau", "neighbour_fraction", "seed_stability",
                "null_controls", "stress_ablation", "asset_decisions", "portfolio")) {
            if (!field(field(value, "gates"), gate).isBoolean()) {
                throw failure("statistical audit gate " + gate + " is missing");
            }
        }
        if (field(value, "pass").asBoolean() && !"SHADOW".equals(text(field(value, "decision")))) {
            throw failure("only SHADOW may be emitted by a passing statistical audit");
        }
        return true;
    }

    public static ObjectNode runStatisticalAuditV5(ObjectNode args) {
        ObjectNode options = args == null ? object() : args; JsonNode artifact = field(options, "artifact");
        if (artifact.isArray()) throw failure("statistical audit requires an artifact, not raw arrays");
        ObjectNode config = objectOrEmpty(field(options, "config"));
        String mode = truthy(field(config, "mode")) ? jsString(field(config, "mode")).toUpperCase(Locale.ROOT) : "FIXTURE";
        if (!"FIXTURE".equals(mode)) {
            requireFrozenHardPolicy(field(config, "constraints"),
                    "authoritative statistical audit hard acceptance policy");
        }
        ObjectNode head = validateExposureHead(field(options, "exposureHead"));
        JsonNode vectorInventory = field(options, "vectorInventory");
        ObjectNode validation = object(); validation.set("exposureHead", head);
        validation.put("allowSubset", vectorInventory.isObject());
        validateStatisticalArtifactSet(artifact, validation);
        ArrayNode artifactEpisodeIds = array();
        for (JsonNode row : array(field(artifact, "episodes"))) artifactEpisodeIds.add(text(field(row, "episode_id")));
        if (vectorInventory.isObject()) validateVectorInventory(vectorInventory, head, artifactEpisodeIds);

        String selectedCandidateId = jsString(field(options, "selectedCandidateId")); JsonNode selectedRow = null;
        for (JsonNode row : array(field(artifact, "candidates"))) if (selectedCandidateId.equals(
                text(field(row, "candidate_id")))) { selectedRow = row; break; }
        boolean headAlias = false;
        for (JsonNode row : array(field(head, "entries"))) if (selectedCandidateId.equals(
                text(field(row, "behavior_sha256")))) { headAlias = true; break; }
        JsonNode selectedOutcomeRows = field(options, "selectedOutcomeRows"); String selectedAlias;
        if (selectedRow != null) selectedAlias = text(field(selectedRow, "behavior_sha256"));
        else if (headAlias) selectedAlias = selectedCandidateId;
        else if (selectedOutcomeRows.isArray()) {
            ObjectNode digest = object(); digest.put("schema", "strategy-v5-statistical-selected-oos-vector/1");
            ArrayNode digestRows = array();
            for (JsonNode row : selectedOutcomeRows) {
                ObjectNode item = object(); item.set("episode_id", cloneNode(field(row, "episode_id")));
                item.set("net_r", cloneNode(field(row, "net_r"))); item.set("traded", cloneNode(field(row, "traded")));
                digestRows.add(item);
            }
            digest.set("rows", digestRows); selectedAlias = hash(digest);
        } else selectedAlias = null;
        if (selectedAlias == null) throw failure("selected candidate is not in the verified artifact or exposure head");

        ArrayNode rows;
        if (selectedRow != null) rows = strictValues(artifact, selectedCandidateId, null);
        else if (headAlias) rows = vectorValues(artifact, vectorInventory, selectedAlias, null);
        else {
            if (!selectedOutcomeRows.isArray() || selectedOutcomeRows.isEmpty()) {
                throw failure("selected OOS vector is missing");
            }
            Map<String, JsonNode> byId = new LinkedHashMap<>(); boolean invalid = false;
            for (JsonNode row : selectedOutcomeRows) {
                String id = text(field(row, "episode_id"));
                if (!row.isObject() || !field(row, "episode_id").isTextual() || byId.putIfAbsent(id, row) != null
                        || !Double.isFinite(numberJs(field(row, "net_r"))) || !field(row, "traded").isBoolean()) {
                    invalid = true; continue;
                }
                try {
                    strictTime(field(row, "decision_time"), "selected OOS decision_time");
                    strictTime(field(row, "resolution_time"), "selected OOS resolution_time");
                } catch (RuntimeException error) { invalid = true; }
            }
            if (invalid || byId.size() != selectedOutcomeRows.size()) {
                throw failure("selected OOS vector is not canonical");
            }
            rows = array();
            for (JsonNode episode : array(field(artifact, "episodes"))) {
                String id = text(field(episode, "episode_id")); JsonNode selected = byId.get(id); if (selected == null) continue;
                ObjectNode row = object(); row.put("episode_id", id); row.set("asset", cloneNode(field(episode, "asset")));
                row.set("decision_time", cloneNode(field(episode, "decision_time")));
                row.set("resolution_time", cloneNode(field(episode, "resolution_time")));
                row.put("value", numberJs(field(selected, "net_r")));
                row.put("traded", field(selected, "traded").asBoolean(false)); rows.add(row);
            }
        }
        Map<String, String> marketClusters = marketEpisodeClusters(field(artifact, "episodes"));
        ArrayNode independentRows = collapseMarketEpisodeRows(rows, field(artifact, "episodes"));
        if (independentRows.isEmpty()) throw failure("selected OOS vector has no independent market episodes");
        ArrayNode tradedRows = array(); for (JsonNode row : rows) if (field(row, "traded").asBoolean(false)) tradedRows.add(row);
        ArrayNode independentTrades = collapseMarketEpisodeRows(tradedRows, field(artifact, "episodes"));
        long bootstrapIterations = optionLong(config, "bootstrapIterations", 1024);
        long seed = optionLong(config, "seed", 11);
        List<ObjectNode> opportunities = nodeObjects(independentRows), trades = nodeObjects(independentTrades);
        Double opportunityP20 = p20(blockBootstrap(opportunities, bootstrapIterations, seed, null));
        Double tradeP20 = p20(blockBootstrap(trades, bootstrapIterations, seed, null));
        double expectancy = trades.isEmpty() ? 0 : mean(trades.stream().map(row -> numberJs(field(row, "value"))).toList());
        ObjectNode metrics = object(); metrics.put("expectancy_r", expectancy); putNullable(metrics, "bootstrap_p20", tradeP20);
        JsonNode trainingWeighted = field(options, "trainingWeightedBootstrapP20");
        if (trainingWeighted.isNull() || trainingWeighted.isMissingNode()) metrics.putNull("weighted_bootstrap_p20");
        else metrics.put("weighted_bootstrap_p20", finiteNumber(trainingWeighted, "training_weighted_bootstrap_p20"));
        metrics.put("sample_count", trades.size()); metrics.put("traded_count", trades.size());
        metrics.put("opportunity_count", opportunities.size());
        metrics.put("opportunity_expectancy_r", mean(opportunities.stream().map(row -> numberJs(field(row, "value"))).toList()));
        putNullable(metrics, "opportunity_bootstrap_p20", opportunityP20); metrics.put("outer_weighting", "UNWEIGHTED");
        metrics.put("training_weighting", "18_MONTH_HALF_LIFE_ONLY");
        double sd = 0;
        if (trades.size() > 1) {
            double sum = 0; for (ObjectNode row : trades) sum += Math.pow(numberJs(field(row, "value")) - expectancy, 2);
            sd = Math.sqrt(sum / Math.max(1, trades.size() - 1));
        }
        long searchExposureK = integer(field(head, "cumulative_k"), 0);
        double searchAdjusted = trades.isEmpty() ? 0 : expectancy
                - sd * Math.sqrt(2 * Math.log(Math.max(1, searchExposureK)) / trades.size());

        ArrayNode pboEvidence = array(field(config, "outerTrainingPboEvidence")); boolean comparablePbo = !pboEvidence.isEmpty();
        for (JsonNode row : pboEvidence) if (!row.isObject() || !"OUTER_TRAIN_ONLY".equals(text(field(row, "source_phase")))
                || !field(row, "outer_oos_bound").isBoolean() || field(row, "outer_oos_bound").asBoolean()
                || numberJs(field(row, "candidate_count")) < 2 || numberJs(field(row, "valid_combinations")) < 2
                || !Double.isFinite(numberJs(field(row, "pbo")))) comparablePbo = false;
        ObjectNode pbo = object();
        if (comparablePbo) {
            double worst = -Double.MAX_VALUE, valid = Double.MAX_VALUE, candidates = Double.MAX_VALUE;
            for (JsonNode row : pboEvidence) {
                worst = Math.max(worst, numberJs(field(row, "pbo")));
                valid = Math.min(valid, numberJs(field(row, "valid_combinations")));
                candidates = Math.min(candidates, numberJs(field(row, "candidate_count")));
            }
            pbo.put("pbo", worst); pbo.put("valid_combinations", valid); pbo.put("candidate_count", candidates);
            pbo.put("evidence_count", pboEvidence.size()); pbo.put("evidence_sha256", hash(pboEvidence));
            pbo.put("source_phase", "OUTER_TRAIN_ONLY"); pbo.put("outer_oos_bound", false);
            pbo.put("aggregation", "WORST_OUTER_TRAIN_PANEL");
            pbo.put("method", "FIXED_PANEL_PURGED_CPCV_TRAIN_WINNER_TEST_RANK");
        } else {
            pbo.putNull("pbo"); pbo.put("valid_combinations", 0); pbo.put("candidate_count", 0);
            pbo.put("evidence_count", pboEvidence.size()); pbo.put("evidence_sha256", hash(pboEvidence));
            pbo.put("source_phase", "OUTER_TRAIN_ONLY"); pbo.put("outer_oos_bound", false);
            pbo.put("method", "UNSUPPORTED_FIXED_OUTER_TRAIN_PANELS");
            pbo.put("reason", "PBO_REQUIRES_COMPARABLE_MULTI_CANDIDATE_OUTER_TRAIN_EVIDENCE");
        }
        ArrayNode selectedEpisodeIds = array(); rows.forEach(row -> selectedEpisodeIds.add(text(field(row, "episode_id"))));
        ObjectNode max = centeredMaxStatistic(artifact, head, selectedEpisodeIds,
                optionLong(config, "maxStatIterations", 1024), seed,
                vectorInventory.isObject() ? vectorInventory : null, rows, selectedAlias);
        ObjectNode dsr = deflatedSharpe(independentTrades, searchExposureK);

        Map<String, List<ObjectNode>> years = new LinkedHashMap<>();
        for (ObjectNode row : trades) {
            String decision = text(field(row, "decision_time")); String year = decision.substring(0, Math.min(4, decision.length()));
            years.computeIfAbsent(year, ignored -> new ArrayList<>()).add(row);
        }
        ArrayNode yearMeans = array();
        for (Map.Entry<String, List<ObjectNode>> entry : years.entrySet()) {
            ObjectNode row = object(); row.put("year", entry.getKey());
            row.put("expectancy_r", mean(entry.getValue().stream().map(value -> numberJs(field(value, "value"))).toList()));
            row.put("trade_count", entry.getValue().stream().filter(value -> field(value, "traded").asBoolean(false)).count());
            row.put("opportunity_count", entry.getValue().size());
            putNullable(row, "bootstrap_p20", p20(blockBootstrap(entry.getValue(),
                    optionLong(config, "bootstrapIterations", 512), seed + numberFromString(entry.getKey()), null)));
            yearMeans.add(row);
        }
        double halfLife = optionDouble(config, "halfLifeMonths", ((Number) STAT_DEFAULTS.get("halfLifeMonths")).doubleValue());
        long recentCutoff = strictTime(field(independentRows.get(independentRows.size() - 1), "decision_time"), "timestamp")
                - (long) (halfLife * 30.4375 * 86_400_000d);
        List<ObjectNode> recent = trades.stream().filter(row -> strictTime(field(row, "decision_time"), "timestamp")
                >= recentCutoff).toList();
        Double recentBootstrap = recent.isEmpty() ? null : p20(blockBootstrap(recent,
                optionLong(config, "bootstrapIterations", 512), seed + 101, null));

        ArrayNode geneticEvidence = !array(field(options, "geneticRuns")).isEmpty()
                ? array(field(options, "geneticRuns")) : array();
        if (geneticEvidence.isEmpty() && field(options, "genetic").isObject()) geneticEvidence.add(field(options, "genetic"));
        ObjectNode plateau;
        if (geneticEvidence.isEmpty()) {
            plateau = object(); plateau.put("pass", false); plateau.put("reason", "MISSING_GENETIC_PLATEAU");
        } else {
            ArrayNode plateauRows = array();
            int minPlateau = (int) optionLong(config, "minPlateau", ((Number) STAT_DEFAULTS.get("minPlateau")).longValue());
            double minFraction = optionDouble(config, "minNeighbourFraction",
                    ((Number) STAT_DEFAULTS.get("minNeighbourFraction")).doubleValue());
            for (JsonNode run : geneticEvidence) plateauRows.add(connectedPlateau(run,
                    text(field(run, "selected_behavior_alias_sha256")), minPlateau, minFraction));
            boolean plateauPass = true; int plateauSize = Integer.MAX_VALUE; double plateauFraction = Double.MAX_VALUE;
            ArrayNode aliases = array();
            for (JsonNode row : plateauRows) {
                plateauPass &= field(row, "pass").asBoolean(false);
                plateauSize = Math.min(plateauSize, (int) integer(field(row, "connected_profitable_plateau_size"), 0));
                plateauFraction = Math.min(plateauFraction, numberJs(field(row, "profitable_neighbour_fraction")));
                aliases.add(cloneNode(field(row, "selected_alias")));
            }
            plateau = object(); plateau.put("pass", plateauPass);
            plateau.put("connected_profitable_plateau_size", plateauSize);
            plateau.put("profitable_neighbour_fraction", plateauFraction); plateau.set("selected_aliases", aliases);
        }

        JsonNode selectedMetrics = field(options, "selectedMetrics");
        boolean completeHard = selectedMetrics.isObject();
        for (String key : List.of("cost_r", "coverage_fraction", "capacity_pass", "max_drawdown_r", "profit_factor")) {
            if (!definedNonNull(field(selectedMetrics, key))) completeHard = false;
        }
        boolean hardMetrics = false;
        if (completeHard && Double.isFinite(numberJs(field(selectedMetrics, "cost_r")))
                && Double.isFinite(numberJs(field(selectedMetrics, "coverage_fraction")))
                && field(selectedMetrics, "capacity_pass").asBoolean(false)
                && Double.isFinite(numberJs(field(selectedMetrics, "max_drawdown_r")))
                && Double.isFinite(numberJs(field(selectedMetrics, "profit_factor")))) {
            ObjectNode hardScope = metrics.deepCopy(); hardScope.setAll(objectOrEmpty(selectedMetrics));
            hardScope.put("traded_count", trades.size()); hardScope.put("expectancy_r", expectancy);
            ObjectNode policy = objectOrEmpty(field(config, "constraints")).deepCopy();
            policy.put("minEpisodes", optionDouble(config, "minEpisodes", ((Number) STAT_DEFAULTS.get("minEpisodes")).doubleValue()));
            policy.put("minExpectancy", optionDouble(config, "minExpectancy", 0));
            policy.put("minCoverage", optionDouble(config, "minCoverage", .95));
            policy.put("minProfitFactor", optionDouble(config, "minProfitFactor", 1));
            policy.put("maxDrawdownR", optionDouble(config, "maxDrawdownR", Double.MAX_VALUE));
            policy.put("maxCostR", optionDouble(config, "maxCostR", Double.MAX_VALUE));
            hardMetrics = field(hardFeasible(hardScope, policy), "feasible").asBoolean(false);
        }
        JsonNode portfolioDecision = field(options, "portfolioDecision"); boolean portfolioBound = false;
        if (portfolioDecision.isObject()) try {
            validateBoundDecision(portfolioDecision, "portfolio", text(field(portfolioDecision, "lineage_sha256")), null, null);
            portfolioBound = true;
        } catch (RuntimeException ignored) {}

        List<Double> selectedMeans = new ArrayList<>(), baselineMeans = new ArrayList<>();
        for (JsonNode run : geneticEvidence) {
            double selected = numberJs(field(field(field(run, "selected"), "fitness"), "metrics").path("expectancy_r"));
            double baseline = numberJs(field(field(field(run, "baseline"), "metrics"), "expectancy_r"));
            if (Double.isFinite(selected) && Double.isFinite(baseline)) {
                selectedMeans.add(selected); baselineMeans.add(baseline);
            }
        }
        double baselineTolerance = optionDouble(config, "baselineNonInferiorityR", .001);
        boolean baselineComparison = selectedMeans.size() == geneticEvidence.size() && Double.isFinite(baselineTolerance)
                && baselineTolerance >= 0 && (selectedMeans.isEmpty()
                || mean(selectedMeans) + baselineTolerance >= mean(baselineMeans));
        Set<String> artifactAssets = new LinkedHashSet<>();
        array(field(artifact, "episodes")).forEach(row -> artifactAssets.add(text(field(row, "asset"))));
        Set<String> declaredAssets = new LinkedHashSet<>(); JsonNode scopeAssets = field(field(config, "assetScope"), "trade_assets");
        if (scopeAssets.isArray() && !scopeAssets.isEmpty()) scopeAssets.forEach(value -> declaredAssets.add(jsString(value)));
        else declaredAssets.addAll(artifactAssets);
        ArrayNode assetDecisions = array(field(options, "assetDecisions")); Set<String> decisionAssets = new HashSet<>();
        assetDecisions.forEach(row -> decisionAssets.add(text(field(row, "asset"))));
        boolean assetSeparation = assetDecisions.size() == declaredAssets.size()
                && decisionAssets.size() == assetDecisions.size() && decisionAssets.containsAll(declaredAssets);

        ArrayNode stressInventory = !array(field(options, "stressDecisions")).isEmpty()
                ? array(field(options, "stressDecisions")) : array();
        if (stressInventory.isEmpty()) for (JsonNode row : assetDecisions) if (field(row, "stress").isObject()) {
            stressInventory.add(field(row, "stress"));
        }
        ArrayNode weightedInputs = !array(field(options, "trainingWeightedBootstrapP20s")).isEmpty()
                ? array(field(options, "trainingWeightedBootstrapP20s")) : array().add(cloneNode(trainingWeighted));
        boolean trainingWeightedGate = true;
        for (JsonNode value : weightedInputs) if (!Double.isFinite(numberJs(value)) || numberJs(value) <= 0) {
            trainingWeightedGate = false;
        }
        boolean stressAbstraction = defined(field(config, "requireStressInventory"))
                && !field(config, "requireStressInventory").asBoolean();
        if (!stressAbstraction && !stressInventory.isEmpty()) {
            stressAbstraction = true;
            for (JsonNode stress : stressInventory) try {
                validateBoundDecision(stress, "stress", text(field(stress, "lineage_sha256")), null, null);
                if (!field(stress, "pass").asBoolean(false)) stressAbstraction = false;
                for (JsonNode scenario : array(field(stress, "scenarios"))) if (!field(scenario, "pass").asBoolean(false)) {
                    stressAbstraction = false;
                }
            } catch (RuntimeException error) { stressAbstraction = false; }
        }

        ObjectNode gates = object(); gates.put("hard_metrics", hardMetrics);
        gates.put("baseline_comparison", baselineComparison); gates.put("bootstrap_p20_positive", tradeP20 != null && tradeP20 > 0);
        gates.put("weighted_bootstrap_p20_positive", trainingWeightedGate);
        gates.put("max_statistic", numberJs(field(max, "p_value")) <= optionDouble(config, "maxStatPValue",
                ((Number) STAT_DEFAULTS.get("maxStatPValue")).doubleValue()));
        gates.put("search_adjusted_expectancy_positive", searchAdjusted > 0);
        gates.put("dsr", dsr != null && field(dsr, "supported").asBoolean(false)
                && numberJs(field(dsr, "probability")) >= optionDouble(config, "minDsrProbability",
                ((Number) STAT_DEFAULTS.get("minDsrProbability")).doubleValue()));
        gates.put("pbo", field(pbo, "pbo").isNumber() && field(pbo, "valid_combinations").isNumber()
                && numberJs(field(pbo, "valid_combinations")) >= 2
                && numberJs(field(pbo, "pbo")) <= optionDouble(config, "maxPbo",
                ((Number) STAT_DEFAULTS.get("maxPbo")).doubleValue()));
        gates.put("minimum_independent_episodes", trades.size() >= optionLong(config, "minEpisodes",
                ((Number) STAT_DEFAULTS.get("minEpisodes")).longValue()));
        gates.put("recent_oos_positive", recentBootstrap != null && recentBootstrap > 0);
        boolean earlier = yearMeans.size() > 1;
        for (int index = 0; index < Math.max(0, yearMeans.size() - 1); index++) {
            if (numberJs(field(yearMeans.get(index), "bootstrap_p20")) < -.1) earlier = false;
        }
        gates.put("earlier_blocks", earlier); long positiveYears = 0;
        for (JsonNode row : yearMeans) if (numberJs(field(row, "trade_count")) >= optionLong(config,
                "minTradesPerYear", ((Number) STAT_DEFAULTS.get("minTradesPerYear")).longValue())
                && numberJs(field(row, "expectancy_r")) > 0) positiveYears++;
        gates.put("positive_years", positiveYears >= optionLong(config, "minPositiveYears",
                ((Number) STAT_DEFAULTS.get("minPositiveYears")).longValue()));
        long positiveFolds = 0; for (JsonNode row : array(field(options, "folds"))) {
            JsonNode expectancyNode = defined(field(row, "test_expectancy_r"))
                    ? field(row, "test_expectancy_r") : field(row, "expectancy_r");
            if (numberJs(expectancyNode) > 0) positiveFolds++;
        }
        gates.put("positive_outer_folds", positiveFolds >= optionLong(config, "minPositiveFolds",
                ((Number) STAT_DEFAULTS.get("minPositiveFolds")).longValue()));
        gates.put("plateau", field(plateau, "pass").asBoolean(false));
        gates.put("neighbour_fraction", numberJs(field(plateau, "profitable_neighbour_fraction"))
                >= optionDouble(config, "minNeighbourFraction",
                ((Number) STAT_DEFAULTS.get("minNeighbourFraction")).doubleValue()));
        boolean seedStability = !geneticEvidence.isEmpty();
        for (JsonNode run : geneticEvidence) {
            long count = integer(field(run, "selected_seed_count"), -1);
            Set<Long> seeds = new HashSet<>(); array(field(run, "seed_runs")).forEach(row -> seeds.add(
                    integer(field(row, "seed"), Long.MIN_VALUE)));
            if (count < optionLong(config, "minSeedCount", ((Number) STAT_DEFAULTS.get("minSeedCount")).longValue())
                    || count > 3 || seeds.size() != 3) seedStability = false;
        }
        gates.put("seed_stability", seedStability);
        gates.put("null_controls", field(field(options, "nullControls"), "pass").asBoolean(false));
        gates.put("stress_ablation", stressAbstraction); boolean assetsPass = assetSeparation;
        for (JsonNode decision : assetDecisions) if (!validateAssetDecision(decision)
                || !field(decision, "pass").asBoolean(false)) assetsPass = false;
        gates.put("asset_decisions", assetsPass);
        gates.put("portfolio", portfolioBound && field(portfolioDecision, "pass").asBoolean(false));
        boolean pass = true; for (JsonNode gate : gates) if (!gate.asBoolean(false)) pass = false;

        ArrayNode clusterInventory = array(); List<Map.Entry<String, String>> entries = new ArrayList<>(marketClusters.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, String> entry : entries) clusterInventory.add(array().add(entry.getKey()).add(entry.getValue()));
        ObjectNode recentWindow = object(); recentWindow.put("cutoff", JS_ISO.format(Instant.ofEpochMilli(recentCutoff)));
        recentWindow.put("rows", recent.size()); long recentOpportunityRows = 0;
        for (JsonNode row : independentRows) if (strictTime(field(row, "decision_time"), "timestamp") >= recentCutoff) {
            recentOpportunityRows++;
        }
        recentWindow.put("opportunity_rows", recentOpportunityRows); putNullable(recentWindow, "bootstrap_p20", recentBootstrap);
        recentWindow.put("weighting", "UNWEIGHTED_OUTER_OOS");
        recentWindow.put("sampling_unit", "independent_market_episode_cluster");
        ObjectNode result = object(); result.put("schema", schema("audit")); result.put("version", 1);
        result.put("selected_candidate_id", selectedCandidateId); result.put("selected_behavior_alias_sha256", selectedAlias);
        result.put("exposure_head_sha256", text(field(head, "content_sha256")));
        if (vectorInventory.isObject()) result.put("vector_inventory_sha256", text(field(vectorInventory, "content_sha256")));
        else result.putNull("vector_inventory_sha256");
        result.put("sample_count", trades.size()); result.put("opportunity_count", rows.size());
        result.put("trade_count", tradedRows.size()); result.put("independent_opportunity_count", opportunities.size());
        result.put("independent_trade_count", trades.size()); result.put("market_cluster_inventory_sha256", hash(clusterInventory));
        result.put("completed_episode_count", trades.size()); result.set("metrics", metrics);
        result.set("selected_metrics", selectedMetrics.isMissingNode() ? NullNode.instance : cloneNode(selectedMetrics));
        result.put("search_adjusted_expectancy_r", searchAdjusted); result.set("max_statistic", max);
        if (dsr == null) result.putNull("dsr"); else result.set("dsr", dsr); result.set("pbo", pbo);
        result.set("year_means", yearMeans); result.set("recent_window", recentWindow); result.set("plateau", plateau);
        result.set("gates", gates); result.put("pass", pass); result.put("decision", pass ? "SHADOW" : "REJECTED");
        result.put("fail_closed_missing_inputs", true); result = withHash(result); validateStatisticalAudit(result); return result;
    }

    private static List<ObjectNode> nodeObjects(JsonNode values) {
        List<ObjectNode> result = new ArrayList<>(); for (JsonNode value : values) result.add(objectOrEmpty(value));
        return result;
    }

    private static long optionLong(JsonNode options, String name, long fallback) {
        return definedNonNull(field(options, name)) ? (long) numberJs(field(options, name)) : fallback;
    }

    private static double optionDouble(JsonNode options, String name, double fallback) {
        return definedNonNull(field(options, name)) ? numberJs(field(options, name)) : fallback;
    }

    public static ObjectNode makeStatisticalArtifactSet(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        JsonNode lineage = field(options, "lineage");
        JsonNode candidates = field(options, "candidates");
        JsonNode episodes = field(options, "episodes");
        ObjectNode head = validateExposureHead(field(options, "exposureHead"));
        JsonNode metadata = field(options, "metadata").isObject() ? field(options, "metadata") : object();
        boolean allowSubset = field(options, "allowSubset").asBoolean(false);
        boolean genesis = field(options, "genesis").asBoolean(false);
        ObjectNode loose = object();
        loose.set("lineage", cloneNode(lineage)); loose.set("candidates", cloneNode(candidates));
        loose.set("episodes", cloneNode(episodes)); loose.set("metadata", cloneNode(metadata));
        assertNoLooseReturns(loose, "input");
        assertLineage(lineage, "lineage");
        CandidateInventory inventory = validateCandidateRows(candidates, head, genesis || allowSubset);
        if (genesis && (!inventory.ids().isEmpty() || !array(field(head, "entries")).isEmpty())) {
            throw failure("genesis artifact must start with an empty candidate set and exposure head");
        }
        validateEpisodeRows(episodes, inventory.ids(), true);
        ObjectNode normalizedMetadata = objectOrEmpty(metadata).deepCopy();
        if (genesis) normalizedMetadata.put("artifact_role", "GENESIS");
        ObjectNode value = object();
        value.put("schema", schema("input")); value.put("version", 1);
        value.set("lineage", cloneNode(lineage)); value.set("candidates", cloneNode(candidates));
        value.set("episodes", cloneNode(episodes));
        value.put("exposure_head_sha256", text(field(head, "content_sha256")));
        value.set("metadata", normalizedMetadata);
        value = withHash(value);
        ObjectNode validation = object();
        validation.set("exposureHead", head); validation.put("allowSubset", allowSubset);
        validation.put("allowGenesis", genesis);
        validateStatisticalArtifactSet(value, validation);
        validateRegisteredSchema(value);
        return value;
    }

    public static boolean validateStatisticalArtifactSet(JsonNode artifact) {
        return validateStatisticalArtifactSet(artifact, object());
    }

    public static boolean validateStatisticalArtifactSet(JsonNode artifact, ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        assertOwnHash(artifact, schema("input"), "statistical input");
        assertKnownKeys(artifact, Set.of("schema", "version", "lineage", "candidates", "episodes",
                "exposure_head_sha256", "metadata", "content_sha256"), "statistical input");
        assertLineage(field(artifact, "lineage"), "lineage");
        ObjectNode head = field(options, "exposureHead").isObject()
                ? validateExposureHead(field(options, "exposureHead")) : null;
        if (head != null && !text(field(artifact, "exposure_head_sha256"))
                .equals(text(field(head, "content_sha256")))) {
            throw failure("statistical input/exposure head lineage mismatch");
        }
        boolean allowSubset = field(options, "allowSubset").asBoolean(false);
        boolean genesis = field(options, "allowGenesis").asBoolean(false)
                || "GENESIS".equals(text(field(field(artifact, "metadata"), "artifact_role")));
        CandidateInventory inventory = validateCandidateRows(field(artifact, "candidates"), head,
                genesis || allowSubset);
        if (genesis && (!inventory.ids().isEmpty()
                || (head != null && !array(field(head, "entries")).isEmpty()))) {
            throw failure("genesis artifact must start with an empty candidate set and exposure head");
        }
        validateEpisodeRows(field(artifact, "episodes"), inventory.ids(), true);
        if (!allowSubset && head != null) {
            Set<String> current = inventory.behaviors();
            long missing = 0;
            for (JsonNode row : array(field(head, "entries"))) {
                if (!current.contains(text(field(row, "behavior_sha256")))) missing++;
            }
            if (missing > 0) throw failure("statistical input is a subset of the cumulative exposure head ("
                    + missing + " aliases missing)");
        }
        return true;
    }

    public static String signalIntentAlias(JsonNode vector) {
        ObjectNode value = object();
        value.put("schema", "strategy-v5-statistical-signal-intent-vector/1");
        ArrayNode episodes = value.putArray("episodes");
        for (JsonNode raw : array(vector)) {
            ObjectNode row = object();
            row.set("episode_id", cloneNode(field(raw, "episode_id")));
            row.set("intent", cloneNode(field(raw, "intent")));
            episodes.add(row);
        }
        return hash(value);
    }

    public static JsonNode effectiveExecutionBehavior(JsonNode candidateDefinition) {
        if (candidateDefinition == null || candidateDefinition.isNull() || candidateDefinition.isMissingNode()) {
            return NullNode.instance;
        }
        return stripIneffective(candidateDefinition);
    }

    public static String evaluatedBehaviorAlias(String signalAlias, JsonNode candidateReturns,
            JsonNode orderedEpisodeIds, JsonNode candidateDefinition, JsonNode behaviorContracts) {
        ObjectNode contracts = normalizedBehaviorContracts(candidateDefinition, behaviorContracts);
        ObjectNode value = object();
        value.put("schema", "strategy-v5-statistical-effective-behavior/2");
        value.put("signal_semantics_sha256", text(field(contracts, "signal_semantics_sha256")));
        value.put("evaluator_sha256", text(field(contracts, "evaluator_sha256")));
        value.put("predictor_sha256", text(field(contracts, "predictor_sha256")));
        value.put("lifecycle_sha256", text(field(contracts, "lifecycle_sha256")));
        value.set("precommit_sha256", cloneNode(field(contracts, "precommit_sha256")));
        return hash(value);
    }

    public static ObjectNode makeEvaluationArtifact(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        JsonNode signalArtifact = field(options, "signalArtifact");
        JsonNode episodeIds = field(options, "episodeIds");
        JsonNode candidateReturns = field(options, "candidateReturns");
        JsonNode metrics = field(options, "metrics");
        if (!signalArtifact.isObject()
                || !"strategy-v5-statistical-signal-view/1".equals(text(field(signalArtifact, "schema")))
                || !episodeIds.isArray() || !truthy(candidateReturns) || !truthy(metrics)) {
            throw failure("evaluation artifact requires a signal view, scope, returns, and metrics");
        }
        JsonNode candidateDefinition = defined(field(options, "candidateDefinition"))
                ? field(options, "candidateDefinition") : NullNode.instance;
        if (!candidateDefinition.isNull()) assertNoLooseReturns(candidateDefinition, "candidate_definition");
        Set<String> requested = new LinkedHashSet<>();
        episodeIds.forEach(value -> requested.add(jsString(value)));
        ArrayNode ordered = array();
        for (JsonNode row : array(field(signalArtifact, "episodes"))) {
            if (requested.contains(text(field(row, "episode_id")))) ordered.add(text(field(row, "episode_id")));
        }
        if (ordered.size() != episodeIds.size()) throw failure("evaluation scope is outside the signal view");
        ArrayNode intent = normalizeSignalIntentVector(ordered, field(options, "signalIntentVector"));
        String intentSha = signalIntentAlias(intent);
        JsonNode suppliedContracts = defined(field(options, "behaviorContracts"))
                ? field(options, "behaviorContracts") : NullNode.instance;
        ObjectNode contracts = normalizedBehaviorContracts(candidateDefinition, suppliedContracts);
        String signalAlias = text(field(contracts, "signal_semantics_sha256"));
        String alias = evaluatedBehaviorAlias(signalAlias, candidateReturns, ordered, candidateDefinition, contracts);
        if (truthy(field(options, "behaviorAliasSha256"))
                && !alias.equals(text(field(options, "behaviorAliasSha256")))) {
            throw failure("behavior alias does not match the frozen semantic contracts");
        }
        String phase = jsString(field(options, "phase"));
        JsonNode cutoff = defined(field(options, "cutoff")) ? field(options, "cutoff") : NullNode.instance;
        JsonNode fit = defined(field(options, "fitCutoff")) ? field(options, "fitCutoff")
                : ("OUTER_OOS".equals(phase) ? NullNode.instance : cutoff);
        JsonNode evaluation = defined(field(options, "evaluationCutoff")) ? field(options, "evaluationCutoff")
                : ("INNER_VALIDATION".equals(phase) ? cutoff : fit);
        String weighting = truthy(field(options, "weighting")) ? jsString(field(options, "weighting"))
                : (Set.of("TRAIN_ONLY", "TRAIN_CONFIRMATION").contains(phase) ? "TRAIN_HALF_LIFE"
                : "INNER_VALIDATION".equals(phase) ? "UNWEIGHTED_VALIDATION" : "UNWEIGHTED_OOS");
        if (!fit.isNull()) iso(fit, "fit_cutoff");
        if (!evaluation.isNull()) iso(evaluation, "evaluation_cutoff");
        if ("INNER_VALIDATION".equals(phase) && (fit.isNull() || evaluation.isNull()
                || strictTime(evaluation, "evaluation_cutoff") <= strictTime(fit, "fit_cutoff"))) {
            throw failure("inner validation requires a later evaluation cutoff than its immutable fit cutoff");
        }
        if (("OUTER_OOS".equals(phase) && (!fit.isNull() || !evaluation.isNull()
                || !"UNWEIGHTED_OOS".equals(weighting)))
                || ("INNER_VALIDATION".equals(phase) && !"UNWEIGHTED_VALIDATION".equals(weighting))) {
            throw failure("evaluation phase/cutoff weighting contract is invalid");
        }
        JsonNode fold = defined(field(options, "foldId")) ? field(options, "foldId") : NullNode.instance;
        ObjectNode lineage = object();
        lineage.set("source_artifact_sha256", cloneNode(field(signalArtifact, "source_artifact_sha256")));
        lineage.set("episode_ids", ordered.deepCopy()); lineage.put("phase", phase); lineage.set("fold_id", cloneNode(fold));
        lineage.set("cutoff", cloneNode(cutoff)); lineage.set("fit_cutoff", cloneNode(fit));
        lineage.set("evaluation_cutoff", cloneNode(evaluation)); lineage.put("weighting", weighting);
        ObjectNode vector = object();
        vector.put("schema", "strategy-v5-statistical-evaluation-vector/1");
        vector.set("episode_ids", ordered.deepCopy()); vector.put("signal_intent_vector_sha256", intentSha);
        vector.set("candidate_returns", cloneNode(candidateReturns));
        ObjectNode value = object();
        value.put("schema", schema("evaluation")); value.put("version", 1);
        value.set("source_artifact_sha256", cloneNode(field(signalArtifact, "source_artifact_sha256")));
        value.set("episode_ids", ordered); value.put("phase", phase); value.set("fold_id", cloneNode(fold));
        value.set("cutoff", cloneNode(cutoff)); value.set("fit_cutoff", cloneNode(fit));
        value.set("evaluation_cutoff", cloneNode(evaluation)); value.put("weighting", weighting);
        value.set("signal_intent_vector", intent); value.put("signal_intent_vector_sha256", intentSha);
        value.put("evaluation_vector_sha256", hash(vector));
        value.set("candidate_definition", candidateDefinition.isNull() ? NullNode.instance : cloneNode(candidateDefinition));
        value.set("behavior_contracts", contracts); value.put("signal_behavior_alias_sha256", signalAlias);
        value.put("behavior_alias_sha256", alias); value.set("candidate_returns", cloneNode(candidateReturns));
        value.set("metrics", cloneNode(metrics)); value.put("lineage_sha256", hash(lineage));
        value = withHash(value);
        validateRegisteredSchema(value);
        return value;
    }

    public static double drawdown(Collection<? extends Number> values) {
        double peak = 0, equity = 0, worst = 0;
        for (Number value : values) {
            equity += value.doubleValue(); peak = Math.max(peak, equity); worst = Math.min(worst, equity - peak);
        }
        return worst;
    }

    public static ArrayNode marketEpisodeClusterDiagnostics(JsonNode rawEpisodes) {
        if (!rawEpisodes.isArray() || rawEpisodes.isEmpty()) {
            throw failure("market cluster identity requires canonical episodes");
        }
        List<MarketInterval> intervals = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode row : rawEpisodes) {
            String id = jsString(field(row, "episode_id"));
            if (!ids.add(id)) throw failure("market cluster identity received duplicate episode IDs");
            String asset = asset(field(row, "asset"));
            long start = strictTime(field(row, "decision_time"), "timestamp");
            long end = strictTime(field(row, "resolution_time"), "timestamp");
            if (end <= start) throw failure("market cluster episode " + id + " has invalid interval");
            intervals.add(new MarketInterval(id, asset, start, end));
        }
        Map<String, MarketInterval> remaining = new LinkedHashMap<>();
        intervals.forEach(row -> remaining.put(row.id(), row));
        List<ObjectNode> clusters = new ArrayList<>();
        Comparator<MarketInterval> order = Comparator.comparingLong(MarketInterval::start).thenComparing(MarketInterval::id);
        while (!remaining.isEmpty()) {
            MarketInterval anchor = remaining.values().stream().min(order).orElseThrow();
            List<MarketInterval> members = new ArrayList<>(); members.add(anchor);
            remaining.values().stream().sorted(order).forEach(row -> {
                if (row.id().equals(anchor.id()) || row.asset().equals(anchor.asset())) return;
                if (row.start() - anchor.start() > MARKET_CLUSTER_MAX_SPAN_MS) return;
                if (anchor.start() < row.end() && row.start() < anchor.end()) members.add(row);
            });
            List<String> memberIds = members.stream().map(MarketInterval::id).sorted().toList();
            long start = members.stream().mapToLong(MarketInterval::start).min().orElseThrow();
            long end = members.stream().mapToLong(MarketInterval::start).max().orElseThrow();
            ObjectNode identity = object(); identity.put("schema", "strategy-v5-market-episode-cluster/2");
            identity.put("anchor_episode_id", anchor.id()); identity.set("episode_ids", strings(memberIds));
            identity.put("max_span_ms", MARKET_CLUSTER_MAX_SPAN_MS);
            ObjectNode cluster = object(); cluster.put("cluster_id", hash(identity));
            cluster.put("anchor_episode_id", anchor.id()); cluster.set("episode_ids", strings(memberIds));
            cluster.put("start_time", JS_ISO.format(Instant.ofEpochMilli(start)));
            cluster.put("end_time", JS_ISO.format(Instant.ofEpochMilli(end)));
            cluster.put("decision_span_ms", end - start); cluster.put("max_span_ms", MARKET_CLUSTER_MAX_SPAN_MS);
            cluster.put("direct_overlap_only", true); clusters.add(cluster);
            members.forEach(row -> remaining.remove(row.id()));
        }
        clusters.sort(Comparator.comparingLong((ObjectNode row) -> strictTime(field(row, "start_time"), "timestamp"))
                .thenComparing((ObjectNode row) -> text(field(row, "cluster_id"))));
        ArrayNode output = array(); clusters.forEach(output::add); return output;
    }

    public static Map<String, String> marketEpisodeClusters(JsonNode episodes) {
        ArrayNode diagnostics = marketEpisodeClusterDiagnostics(episodes);
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode cluster : diagnostics) for (JsonNode id : array(field(cluster, "episode_ids"))) {
            result.put(jsString(id), text(field(cluster, "cluster_id")));
        }
        for (JsonNode row : episodes) if (defined(field(row, "market_cluster_id"))
                && !jsString(field(row, "market_cluster_id")).equals(result.get(text(field(row, "episode_id"))))) {
            throw failure("episode " + text(field(row, "episode_id"))
                    + " has a non-canonical market cluster identity");
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public static ArrayNode collapseMarketEpisodeRows(JsonNode rows) {
        return collapseMarketEpisodeRows(rows, rows);
    }

    public static ArrayNode collapseMarketEpisodeRows(JsonNode rows, JsonNode episodes) {
        if (!rows.isArray() || rows.isEmpty()) return array();
        Map<String, String> clusters = marketEpisodeClusters(episodes);
        Map<String, List<ObjectNode>> grouped = new LinkedHashMap<>();
        for (JsonNode raw : rows) {
            String id = text(field(raw, "episode_id"));
            String cluster = clusters.get(id);
            if (cluster == null) throw failure("episode " + id + " has no market cluster identity");
            ObjectNode row = objectOrEmpty(raw).deepCopy(); row.put("market_cluster_id", cluster);
            grouped.computeIfAbsent(cluster, ignored -> new ArrayList<>()).add(row);
        }
        List<ObjectNode> collapsed = new ArrayList<>();
        for (Map.Entry<String, List<ObjectNode>> entry : grouped.entrySet()) {
            List<ObjectNode> group = entry.getValue();
            List<String> decisions = group.stream().map(row -> text(field(row, "decision_time"))).sorted().toList();
            List<String> resolutions = group.stream().map(row -> text(field(row, "resolution_time"))).sorted().toList();
            double value = group.stream().mapToDouble(row -> numberJs(field(row, "value"))).average().orElse(0);
            double net = group.stream().mapToDouble(row -> numberJs(definedNonNull(field(row, "net_r"))
                    ? field(row, "net_r") : field(row, "value"))).average().orElse(0);
            ObjectNode row = object(); row.put("episode_id", entry.getKey()); row.put("market_cluster_id", entry.getKey());
            row.put("asset", "market"); row.put("decision_time", decisions.getFirst());
            row.put("resolution_time", resolutions.getLast()); row.put("value", value); row.put("net_r", net);
            row.put("traded", group.stream().anyMatch(item -> field(item, "traded").asBoolean(false)));
            row.put("eligible", group.stream().allMatch(item -> !field(item, "eligible").isBoolean()
                    || field(item, "eligible").asBoolean()));
            row.set("source_episode_ids", strings(group.stream().map(item -> text(field(item, "episode_id"))).sorted().toList()));
            collapsed.add(row);
        }
        collapsed.sort(Comparator.comparingLong((ObjectNode row) -> strictTime(field(row, "decision_time"), "timestamp"))
                .thenComparing(row -> text(field(row, "episode_id"))));
        ArrayNode output = array(); collapsed.forEach(output::add); return output;
    }

    public static ObjectNode requireFrozenHardPolicy(JsonNode policy) {
        return requireFrozenHardPolicy(policy, "hard acceptance policy");
    }

    public static ObjectNode requireFrozenHardPolicy(JsonNode policy, String label) {
        if (!policy.isObject()) throw failure(label + " is missing");
        for (String key : List.of("minEpisodes", "minExpectancy", "minProfitFactor", "maxDrawdownR",
                "maxCostR", "minCoverage")) {
            if (!defined(field(policy, key)) || !Double.isFinite(numberJs(field(policy, key)))) {
                throw failure(label + " is missing explicit " + key);
            }
        }
        if (numberJs(field(policy, "minEpisodes")) < 1 || numberJs(field(policy, "minProfitFactor")) < 0
                || numberJs(field(policy, "maxDrawdownR")) < 0 || numberJs(field(policy, "maxCostR")) < 0
                || numberJs(field(policy, "minCoverage")) < 0 || numberJs(field(policy, "minCoverage")) > 1) {
            throw failure(label + " contains invalid frozen thresholds");
        }
        if (!field(policy, "requireCapacityPass").asBoolean(false)) {
            throw failure(label + " must explicitly require capacity_pass=true");
        }
        JsonNode scales = truthy(field(policy, "violationScales"))
                ? field(policy, "violationScales") : field(policy, "violation_scales");
        if (!scales.isObject()) throw failure(label + " is missing explicit violation normalization scales");
        for (String key : List.of("episodes", "expectancy", "drawdown", "costs", "coverage", "capacity",
                "profit_factor")) if (!Double.isFinite(numberJs(field(scales, key))) || numberJs(field(scales, key)) <= 0) {
            throw failure(label + " has an invalid " + key + " violation normalization scale");
        }
        return objectOrEmpty(policy);
    }

    public static ObjectNode hardFeasible(JsonNode metrics, JsonNode policy) {
        List<String> required = List.of("traded_count", "expectancy_r", "cost_r", "coverage_fraction",
                "capacity_pass", "max_drawdown_r", "profit_factor");
        List<String> missing = required.stream().filter(key -> !definedNonNull(field(metrics, key))).toList();
        Violation normalized = normalizedViolation(metrics, policy);
        List<String> violations = new ArrayList<>();
        missing.forEach(key -> violations.add("MISSING_" + key.toUpperCase(Locale.ROOT)));
        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("episodes", !missing.contains("traded_count") && numberJs(field(metrics, "traded_count"))
                >= threshold(policy, "minEpisodes", ((Number) STAT_DEFAULTS.get("minEpisodes")).doubleValue()));
        checks.put("expectancy", !missing.contains("expectancy_r") && numberJs(field(metrics, "expectancy_r"))
                > threshold(policy, "minExpectancy", 0));
        checks.put("drawdown", !missing.contains("max_drawdown_r") && Math.abs(numberJs(field(metrics, "max_drawdown_r")))
                <= threshold(policy, "maxDrawdownR", Double.POSITIVE_INFINITY));
        checks.put("costs", !missing.contains("cost_r") && numberJs(field(metrics, "cost_r"))
                <= threshold(policy, "maxCostR", Double.POSITIVE_INFINITY));
        double coverage = numberJs(field(metrics, "coverage_fraction"));
        checks.put("coverage", !missing.contains("coverage_fraction")
                && coverage >= threshold(policy, "minCoverage", .95) && coverage <= 1);
        checks.put("capacity", field(metrics, "capacity_pass").asBoolean(false));
        checks.put("profit_factor", !missing.contains("profit_factor") && numberJs(field(metrics, "profit_factor"))
                >= threshold(policy, "minProfitFactor", 1));
        checks.forEach((name, pass) -> {
            String code = "profit_factor".equals(name) ? "PROFIT_FACTOR" : name.toUpperCase(Locale.ROOT);
            if (!pass && !violations.contains(code)) violations.add(code);
        });
        ObjectNode output = object();
        output.put("feasible", missing.isEmpty() && checks.values().stream().allMatch(Boolean::booleanValue));
        output.set("violations", strings(violations)); output.set("violation_details", normalized.details());
        output.put("total_violation", normalized.total()); return output;
    }

    public static boolean constrainedDominates(JsonNode left, JsonNode right) {
        boolean a = field(left, "feasible").asBoolean(false), b = field(right, "feasible").asBoolean(false);
        if (a && !b) return true;
        if (!a && b) return false;
        if (!a) {
            double leftTotal = definedNonNull(field(left, "total_violation"))
                    ? numberJs(field(left, "total_violation")) : Double.POSITIVE_INFINITY;
            double rightTotal = definedNonNull(field(right, "total_violation"))
                    ? numberJs(field(right, "total_violation")) : Double.POSITIVE_INFINITY;
            if (leftTotal != rightTotal) return leftTotal < rightTotal;
            String leftDetails = stable(field(left, "violation_details").isObject()
                    ? field(left, "violation_details") : object());
            String rightDetails = stable(field(right, "violation_details").isObject()
                    ? field(right, "violation_details") : object());
            if (!leftDetails.equals(rightDetails)) return leftDetails.compareTo(rightDetails) < 0;
            return false;
        }
        ArrayNode leftObjectives = array(field(left, "objectives"));
        ArrayNode rightObjectives = array(field(right, "objectives"));
        boolean better = false;
        for (int index = 0; index < leftObjectives.size(); index++) {
            double lv = numberJs(leftObjectives.get(index)), rv = numberJs(rightObjectives.get(index));
            if (lv < rv) return false; if (lv > rv) better = true;
        }
        return better;
    }

    public static ArrayNode enumerateDirectNeighbours(JsonNode space, JsonNode value) {
        return neighbours(normalizeGenes(space), value);
    }

    private static ObjectNode signalView(JsonNode artifact, Collection<String> episodeIds,
            String phase, JsonNode foldId) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode row : array(field(artifact, "episodes"))) {
            byId.put(text(field(row, "episode_id")), row);
        }
        List<String> ordered = new ArrayList<>(episodeIds);
        if (new HashSet<>(ordered).size() != ordered.size()
                || ordered.stream().anyMatch(id -> !byId.containsKey(id))) {
            throw failure("signal view scope contains a duplicate or episode outside the artifact");
        }
        String viewPhase = phase == null || phase.isEmpty() ? "SEARCH" : phase;
        JsonNode normalizedFold = defined(foldId) ? cloneNode(foldId)
                : defined(field(field(artifact, "metadata"), "fold_id"))
                ? cloneNode(field(field(artifact, "metadata"), "fold_id")) : NullNode.instance;
        ObjectNode value = object(); value.put("schema", "strategy-v5-statistical-signal-view/1");
        value.put("version", 1); value.put("phase", viewPhase); value.set("fold_id", normalizedFold);
        value.set("lineage", cloneNode(field(artifact, "lineage")));
        value.put("source_artifact_sha256", text(field(artifact, "content_sha256")));
        value.set("episode_ids", strings(ordered)); ArrayNode episodes = array();
        for (String id : ordered) {
            JsonNode source = byId.get(id); ObjectNode row = object();
            row.set("episode_id", cloneNode(field(source, "episode_id")));
            row.set("asset", cloneNode(field(source, "asset")));
            row.set("decision_time", cloneNode(field(source, "decision_time")));
            row.put("phase", viewPhase); row.set("fold_id", cloneNode(normalizedFold));
            row.set("eligible", cloneNode(field(source, "eligible"))); episodes.add(row);
        }
        value.set("episodes", episodes); return withHash(value);
    }

    private static List<Double> weightedRows(List<ObjectNode> rows, JsonNode cutoff, double halfLifeMonths) {
        long cutoffMillis = strictTime(cutoff, "cutoff"); List<Double> weights = new ArrayList<>(); double total = 0;
        for (ObjectNode row : rows) {
            double months = Math.max(0, (cutoffMillis - strictTime(field(row, "decision_time"), "timestamp"))
                    / (30.4375 * 86_400_000d)); double weight = Math.pow(2, -months / halfLifeMonths);
            weights.add(weight); total += weight;
        }
        if (total == 0) total = 1; List<Double> result = new ArrayList<>();
        for (double weight : weights) result.add(weight / total); return result;
    }

    private static ObjectNode metricsFromRows(ArrayNode rawRows, JsonNode cutoff, double halfLifeMonths,
            JsonNode required, JsonNode evaluatorMetrics) {
        if (rawRows.isEmpty()) throw failure("metrics require at least one canonical episode");
        List<ObjectNode> rows = nodeObjects(rawRows), tradeRows = new ArrayList<>();
        List<Double> opportunityValues = new ArrayList<>(), tradeValues = new ArrayList<>();
        for (ObjectNode row : rows) {
            opportunityValues.add(numberJs(field(row, "value")));
            if (field(row, "traded").asBoolean(false)) {
                tradeRows.add(row); tradeValues.add(numberJs(field(row, "value")));
            }
        }
        long bootstrapIterations = truthy(field(required, "bootstrapIterations"))
                ? (long) numberJs(field(required, "bootstrapIterations")) : 512;
        double seed = truthy(field(required, "seed")) ? numberJs(field(required, "seed")) : 11;
        List<Double> opportunityBootstrap = blockBootstrap(rows, bootstrapIterations, seed, null);
        List<Double> bootstrap = blockBootstrap(tradeRows, bootstrapIterations, seed, null);
        List<Double> weights = cutoff != null && !cutoff.isNull() && !cutoff.isMissingNode() && !tradeRows.isEmpty()
                ? weightedRows(tradeRows, cutoff, halfLifeMonths) : null;
        List<Double> weighted = blockBootstrap(tradeRows, bootstrapIterations,
                truthy(field(required, "seed")) ? numberJs(field(required, "seed")) : 12, weights);
        JsonNode supplied = evaluatorMetrics == null ? object() : evaluatorMetrics;
        for (String name : List.of("cost_r", "coverage_fraction", "capacity_pass", "max_drawdown_r",
                "profit_factor")) if (!definedNonNull(field(supplied, name))) {
            throw failure("hard metric " + name + " is missing");
        }
        ObjectNode result = object(); result.put("sample_count", tradeRows.size());
        result.put("traded_count", tradeRows.size()); result.put("opportunity_count", rows.size());
        result.put("opportunity_expectancy_r", mean(opportunityValues));
        putNullable(result, "opportunity_bootstrap_p20", p20(opportunityBootstrap));
        result.put("expectancy_r", tradeValues.isEmpty() ? 0 : mean(tradeValues));
        putNullable(result, "bootstrap_p20", p20(bootstrap));
        putNullable(result, "weighted_bootstrap_p20", p20(weighted));
        result.put("cost_r", finiteNumber(field(supplied, "cost_r"), "cost_r"));
        result.put("coverage_fraction", finiteNumber(field(supplied, "coverage_fraction"), "coverage_fraction"));
        result.put("capacity_pass", field(supplied, "capacity_pass").asBoolean(false));
        result.put("max_drawdown_r", finiteNumber(field(supplied, "max_drawdown_r"), "max_drawdown_r"));
        result.put("profit_factor", finiteNumber(field(supplied, "profit_factor"), "profit_factor"));
        result.put("drawdown_r", drawdown(tradeValues));
        result.put("turnover", finiteNumber(definedNonNull(field(supplied, "turnover"))
                ? field(supplied, "turnover") : JSON.numberNode(tradeRows.size()), "turnover"));
        result.put("complexity", finiteNumber(definedNonNull(field(supplied, "complexity"))
                ? field(supplied, "complexity") : JSON.numberNode(0), "complexity"));
        ArrayNode episodeReturns = array();
        for (ObjectNode row : rows) {
            ObjectNode item = object(); item.set("episode_id", cloneNode(field(row, "episode_id")));
            item.set("decision_time", cloneNode(field(row, "decision_time")));
            item.set("asset", cloneNode(field(row, "asset")));
            item.put("net_r", numberJs(field(row, "value")));
            item.put("traded", field(row, "traded").asBoolean(false)); episodeReturns.add(item);
        }
        result.set("episode_returns", episodeReturns); return result;
    }

    private static ObjectNode validateEvaluatorResult(JsonNode result, JsonNode artifact, Set<String> episodeIds,
            String label, String mode, String phase, JsonNode foldId, JsonNode cutoff,
            JsonNode fitCutoff, JsonNode evaluationCutoff, String weighting, JsonNode candidateDefinition) {
        if (!result.isObject() || !field(result, "candidate_returns").isObject()
                || !field(result, "metrics").isObject()) {
            throw failure(label + " evaluator must return candidate_returns and hard metrics");
        }
        if (definedNonNull(field(result, "candidate_definition"))) {
            assertNoLooseReturns(field(result, "candidate_definition"), label + ".candidate_definition");
        }
        boolean fixture = "FIXTURE".equals(mode);
        if (!fixture && (!candidateDefinition.isObject() || !definedNonNull(field(result, "candidate_definition"))
                || !stable(field(result, "candidate_definition")).equals(stable(candidateDefinition)))) {
            throw failure(label + " evaluation is missing an exact candidate definition binding");
        }
        List<String> expected = new ArrayList<>();
        for (JsonNode episode : array(field(artifact, "episodes"))) {
            String id = text(field(episode, "episode_id")); if (episodeIds.contains(id)) expected.add(id);
        }
        if (!fixture) {
            assertOwnHash(result, schema("evaluation"), label + " evaluation artifact");
            ObjectNode lineage = object(); lineage.set("source_artifact_sha256",
                    cloneNode(field(result, "source_artifact_sha256")));
            lineage.set("episode_ids", strings(expected)); lineage.put("phase", phase);
            lineage.set("fold_id", cloneNode(foldId)); lineage.set("cutoff", cloneNode(cutoff));
            lineage.set("fit_cutoff", cloneNode(fitCutoff)); lineage.set("evaluation_cutoff", cloneNode(evaluationCutoff));
            lineage.put("weighting", text(field(result, "weighting")));
            boolean mismatch = !text(field(artifact, "content_sha256")).equals(
                    text(field(result, "source_artifact_sha256")))
                    || !stable(field(result, "episode_ids")).equals(stable(strings(expected)))
                    || !phase.equals(text(field(result, "phase")))
                    || !stable(field(result, "fold_id")).equals(stable(foldId))
                    || !stable(field(result, "cutoff")).equals(stable(cutoff))
                    || !stable(field(result, "fit_cutoff")).equals(stable(fitCutoff))
                    || !stable(field(result, "evaluation_cutoff")).equals(stable(evaluationCutoff))
                    || weighting != null && !weighting.equals(text(field(result, "weighting")))
                    || !hash(lineage).equals(text(field(result, "lineage_sha256")));
            if (mismatch) throw failure(label + " evaluation artifact lineage/scope/cutoff binding mismatch");
            if ("TRAIN_ONLY".equals(phase) && (fitCutoff.isNull() || evaluationCutoff.isNull()
                    || !"TRAIN_HALF_LIFE".equals(text(field(result, "weighting"))))) {
                throw failure(label + " training evaluation is missing its immutable fit/evaluation cutoff or weighting contract");
            }
            if ("INNER_VALIDATION".equals(phase) && (fitCutoff.isNull() || evaluationCutoff.isNull()
                    || strictTime(evaluationCutoff, "evaluation_cutoff") <= strictTime(fitCutoff, "fit_cutoff")
                    || !"UNWEIGHTED_VALIDATION".equals(text(field(result, "weighting"))))) {
                throw failure(label + " inner validation is missing a later evaluation cutoff or is weighted");
            }
            if ("OUTER_OOS".equals(phase) && (!fitCutoff.isNull() || !evaluationCutoff.isNull()
                    || !"UNWEIGHTED_OOS".equals(text(field(result, "weighting"))))) {
                throw failure(label + " outer OOS must have null cutoffs and unweighted metrics");
            }
        }
        List<String> actual = new ArrayList<>(fieldNames(field(result, "candidate_returns")));
        actual.sort(String::compareTo); List<String> sortedExpected = new ArrayList<>(expected);
        sortedExpected.sort(String::compareTo);
        if (!actual.equals(sortedExpected)) throw failure(label + " evaluator returned incomplete episode inventory");
        for (String id : expected) {
            JsonNode row = field(field(result, "candidate_returns"), id);
            if (!row.isObject() || !Double.isFinite(numberJs(field(row, "net_r")))
                    || !field(row, "traded").isBoolean()) {
                throw failure(label + " evaluator returned invalid episode " + id);
            }
        }
        ArrayNode intent = array();
        if (fixture) for (String id : expected) {
            ObjectNode row = object(); row.put("episode_id", id);
            row.put("intent", field(field(field(result, "candidate_returns"), id), "traded").asBoolean());
            intent.add(row);
        } else {
            ObjectNode signal = object(); signal.put("schema", "strategy-v5-statistical-signal-view/1");
            intent = normalizeSignalIntentVector(strings(expected), field(result, "signal_intent_vector"));
        }
        String intentSha = signalIntentAlias(intent);
        JsonNode effectiveDefinition = definedNonNull(field(result, "candidate_definition"))
                ? field(result, "candidate_definition") : candidateDefinition;
        ObjectNode contracts = normalizedBehaviorContracts(effectiveDefinition, field(result, "behavior_contracts"));
        String signalAlias = text(field(contracts, "signal_semantics_sha256"));
        String alias = evaluatedBehaviorAlias(signalAlias, field(result, "candidate_returns"), strings(expected),
                effectiveDefinition, contracts);
        if (!fixture) {
            ObjectNode vector = object(); vector.put("schema", "strategy-v5-statistical-evaluation-vector/1");
            vector.set("episode_ids", strings(expected)); vector.put("signal_intent_vector_sha256", intentSha);
            vector.set("candidate_returns", cloneNode(field(result, "candidate_returns")));
            boolean extra = false;
            for (JsonNode row : intent) for (String key : fieldNames(row)) {
                if (!Set.of("episode_id", "intent").contains(key)) extra = true;
            }
            if (!alias.equals(text(field(result, "behavior_alias_sha256")))
                    || !signalAlias.equals(text(field(result, "signal_behavior_alias_sha256")))
                    || !intentSha.equals(text(field(result, "signal_intent_vector_sha256")))
                    || !hash(vector).equals(text(field(result, "evaluation_vector_sha256"))) || extra) {
                throw failure(label + " semantic behavior/evaluation-vector binding is missing or inconsistent");
            }
        }
        ArrayNode scopedRows = array();
        for (JsonNode episode : array(field(artifact, "episodes"))) {
            String id = text(field(episode, "episode_id")); if (!episodeIds.contains(id)) continue;
            JsonNode ret = field(field(result, "candidate_returns"), id); ObjectNode row = object();
            row.put("episode_id", id); row.set("asset", cloneNode(field(episode, "asset")));
            row.set("decision_time", cloneNode(field(episode, "decision_time")));
            row.set("resolution_time", cloneNode(field(episode, "resolution_time")));
            row.put("value", numberJs(field(ret, "net_r"))); row.put("traded", field(ret, "traded").asBoolean());
            scopedRows.add(row);
        }
        JsonNode metricCutoff = "TRAIN_HALF_LIFE".equals(text(field(result, "weighting")))
                ? truthy(field(result, "fit_cutoff")) ? field(result, "fit_cutoff") : field(result, "cutoff")
                : NullNode.instance;
        ObjectNode metrics = metricsFromRows(scopedRows, metricCutoff,
                ((Number) STAT_DEFAULTS.get("halfLifeMonths")).doubleValue(),
                field(result, "required").isObject() ? field(result, "required") : object(), field(result, "metrics"));
        metrics.put("behavior_alias_sha256", alias); metrics.put("signal_behavior_alias_sha256", signalAlias);
        metrics.set("signal_intent_vector", intent); return metrics;
    }

    private static ObjectNode geneticConfig(JsonNode rawConfig, String mode) {
        JsonNode config = rawConfig != null && rawConfig.isObject() ? rawConfig : object();
        boolean fixture = "FIXTURE".equals(mode); ObjectNode result = object();
        long population = (long) (definedNonNull(field(config, "population")) ? numberJs(field(config, "population"))
                : fixture ? 12 : ((Number) STAT_DEFAULTS.get("population")).doubleValue());
        long generations = (long) (definedNonNull(field(config, "generations")) ? numberJs(field(config, "generations"))
                : fixture ? 12 : ((Number) STAT_DEFAULTS.get("generations")).doubleValue());
        long minimum = (long) (definedNonNull(field(config, "minGenerations")) ? numberJs(field(config, "minGenerations"))
                : fixture ? 3 : ((Number) STAT_DEFAULTS.get("minGenerations")).doubleValue());
        long plateau = (long) (definedNonNull(field(config, "plateauGenerations"))
                ? numberJs(field(config, "plateauGenerations"))
                : fixture ? 3 : ((Number) STAT_DEFAULTS.get("plateauGenerations")).doubleValue());
        result.put("population", population); result.put("generations", generations);
        result.put("minGenerations", minimum); result.put("plateauGenerations", plateau);
        result.put("crossoverProbability", definedNonNull(field(config, "crossoverProbability"))
                ? numberJs(field(config, "crossoverProbability"))
                : ((Number) STAT_DEFAULTS.get("crossoverProbability")).doubleValue());
        if (!defined(field(config, "mutationProbability"))) result.putNull("mutationProbability");
        else result.put("mutationProbability", numberJs(field(config, "mutationProbability")));
        JsonNode seeds = truthy(field(config, "seeds")) ? field(config, "seeds")
                : MAPPER.valueToTree(STAT_DEFAULTS.get("seeds")); ArrayNode normalizedSeeds = array();
        for (JsonNode seed : array(seeds)) {
            double number = numberJs(seed);
            if (number == Math.rint(number)) normalizedSeeds.add((long) number); else normalizedSeeds.add(number);
        }
        result.set("seeds", normalizedSeeds);
        result.put("halfLifeMonths", definedNonNull(field(config, "halfLifeMonths"))
                ? numberJs(field(config, "halfLifeMonths"))
                : ((Number) STAT_DEFAULTS.get("halfLifeMonths")).doubleValue());
        result.put("operator", "ARITHMETIC_CROSSOVER_UNIFORM_MUTATION");
        result.put("scheduler_ordering", "STABLE_SEED_GENERATION_CHROMOSOME_ORDER");
        result.put("mode", fixture ? "FIXTURE" : "AUTHORITATIVE");
        boolean invalid = definedNonNull(field(config, "population"))
                && numberJs(field(config, "population")) != Math.rint(numberJs(field(config, "population")))
                || definedNonNull(field(config, "generations"))
                && numberJs(field(config, "generations")) != Math.rint(numberJs(field(config, "generations")))
                || definedNonNull(field(config, "minGenerations"))
                && numberJs(field(config, "minGenerations")) != Math.rint(numberJs(field(config, "minGenerations")))
                || population < 2 || generations < 1 || minimum > generations || normalizedSeeds.size() != 3
                || !stable(normalizedSeeds).equals(stable(MAPPER.valueToTree(STAT_DEFAULTS.get("seeds"))))
                || !fixture && (population != ((Number) STAT_DEFAULTS.get("population")).longValue()
                || generations != ((Number) STAT_DEFAULTS.get("generations")).longValue()
                || minimum != ((Number) STAT_DEFAULTS.get("minGenerations")).longValue()
                || plateau != ((Number) STAT_DEFAULTS.get("plateauGenerations")).longValue());
        if (invalid) throw failure("genetic configuration is not frozen"); return result;
    }

    private static String evaluationAttemptIdentity(JsonNode artifact, String predecessor, String alias,
            Long seed, Long generation, JsonNode foldId, String phase, long ordinal) {
        ObjectNode value = object(); value.put("schema", "strategy-v5-statistical-evaluation-attempt/1");
        value.put("source_artifact_sha256", text(field(artifact, "content_sha256")));
        value.put("dataset_sha256", text(field(field(artifact, "lineage"), "dataset_sha256")));
        if (predecessor == null) value.putNull("exposure_predecessor_sha256");
        else value.put("exposure_predecessor_sha256", predecessor);
        value.put("behavior_alias_sha256", alias);
        if (seed == null) value.putNull("seed"); else value.put("seed", seed);
        if (generation == null) value.putNull("generation"); else value.put("generation", generation);
        value.set("fold_id", cloneNode(foldId)); value.put("phase", phase);
        value.put("evaluation_ordinal", ordinal); return hash(value);
    }

    private static ArrayNode objective(JsonNode metrics) {
        ArrayNode result = array();
        for (String key : List.of("bootstrap_p20", "weighted_bootstrap_p20")) {
            double value = numberJs(field(metrics, key)); result.add(Double.isFinite(value) ? value : -1e12);
        }
        result.add(-numberJs(field(metrics, "turnover"))); result.add(-numberJs(field(metrics, "complexity")));
        return result;
    }

    private static List<List<ObjectNode>> rankCrowd(List<ObjectNode> population) {
        List<ObjectNode> remaining = new ArrayList<>(population); List<List<ObjectNode>> fronts = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<ObjectNode> front = new ArrayList<>();
            for (ObjectNode candidate : remaining) {
                boolean dominated = false;
                for (ObjectNode other : remaining) if (other != candidate
                        && constrainedDominates(field(other, "fitness"), field(candidate, "fitness"))) {
                    dominated = true; break;
                }
                if (!dominated) front.add(candidate);
            }
            front.sort(Comparator.comparing(row -> text(field(row, "behavior_sha256"))));
            fronts.add(front); remaining.removeAll(front);
        }
        for (int rank = 0; rank < fronts.size(); rank++) {
            List<ObjectNode> front = fronts.get(rank);
            for (ObjectNode row : front) { row.put("rank", rank); row.put("crowding_distance", 0); }
            for (int objective = 0; objective < 4; objective++) {
                final int index = objective; List<ObjectNode> sorted = new ArrayList<>(front);
                sorted.sort(Comparator.comparingDouble((ObjectNode row) -> numberJs(
                                array(field(field(row, "fitness"), "objectives")).get(index)))
                        .thenComparing(row -> text(field(row, "behavior_sha256"))));
                if (sorted.isEmpty()) continue;
                sorted.getFirst().put("crowding_distance", 9_007_199_254_740_991L);
                sorted.getLast().put("crowding_distance", 9_007_199_254_740_991L);
                double low = numberJs(array(field(field(sorted.getFirst(), "fitness"), "objectives")).get(index));
                double high = numberJs(array(field(field(sorted.getLast(), "fitness"), "objectives")).get(index));
                double range = high - low; if (range == 0) range = 1;
                for (int item = 1; item < sorted.size() - 1; item++) {
                    double next = numberJs(array(field(field(sorted.get(item + 1), "fitness"), "objectives")).get(index));
                    double prior = numberJs(array(field(field(sorted.get(item - 1), "fitness"), "objectives")).get(index));
                    sorted.get(item).put("crowding_distance",
                            numberJs(field(sorted.get(item), "crowding_distance")) + (next - prior) / range);
                }
            }
        }
        return fronts;
    }

    private static String paretoSignature(List<ObjectNode> population) {
        List<List<ObjectNode>> fronts = rankCrowd(population); ArrayNode values = array();
        if (!fronts.isEmpty()) {
            List<ObjectNode> front = new ArrayList<>(fronts.getFirst());
            front.sort(Comparator.comparing(row -> text(field(row, "behavior_alias_sha256"))));
            for (ObjectNode row : front) {
                ObjectNode value = object(); value.put("behavior_alias_sha256",
                        text(field(row, "behavior_alias_sha256")));
                value.set("objectives", cloneNode(field(field(row, "fitness"), "objectives"))); values.add(value);
            }
        }
        return hash(values);
    }

    private static List<ObjectNode> survivors(List<ObjectNode> population, int size) {
        List<ObjectNode> ordered = new ArrayList<>(population);
        ordered.sort(Comparator.comparing(row -> text(field(row, "behavior_sha256"))));
        Map<String, ObjectNode> effective = new LinkedHashMap<>();
        for (ObjectNode row : ordered) effective.put(text(field(row, "behavior_alias_sha256")), row);
        List<ObjectNode> output = new ArrayList<>();
        for (List<ObjectNode> front : rankCrowd(new ArrayList<>(effective.values()))) {
            if (output.size() + front.size() <= size) output.addAll(front);
            else {
                front.sort(Comparator.comparingDouble((ObjectNode row) ->
                                -numberJs(field(row, "crowding_distance")))
                        .thenComparing(row -> text(field(row, "behavior_sha256"))));
                output.addAll(front.subList(0, size - output.size())); break;
            }
        }
        return output;
    }

    private static int randomInt(XorShift32 random, int maximum) {
        return Math.min(maximum - 1, (int) Math.floor(random.next() * maximum));
    }

    private static JsonNode randomGene(JsonNode gene, XorShift32 random) {
        if ("continuous".equals(text(field(gene, "type")))) {
            double minimum = numberJs(field(gene, "min")), maximum = numberJs(field(gene, "max"));
            return quantize(JSON.numberNode(minimum + random.next() * (maximum - minimum)), gene);
        }
        ArrayNode values = array(field(gene, "values")); return cloneNode(values.get(randomInt(random, values.size())));
    }

    private record BredCandidate(ObjectNode candidate, ObjectNode details) {}

    private static BredCandidate breed(ObjectNode space, JsonNode left, JsonNode right,
            XorShift32 random, double crossoverProbability, double mutationProbability) {
        boolean crossoverApplied = random.next() <= crossoverProbability; ObjectNode crossed;
        ObjectNode sources = object();
        if (!crossoverApplied) {
            crossed = chromosome(space, left);
            for (JsonNode gene : array(field(space, "genes"))) sources.put(text(field(gene, "name")), "LEFT");
        } else {
            crossed = object();
            for (JsonNode gene : array(field(space, "genes"))) {
                String name = text(field(gene, "name"));
                if ("continuous".equals(text(field(gene, "type")))) {
                    crossed.set(name, quantize(JSON.numberNode((numberJs(field(left, name))
                            + numberJs(field(right, name))) / 2), gene));
                    sources.put(name, "ARITHMETIC_MEAN");
                } else {
                    boolean useLeft = random.next() < .5;
                    crossed.set(name, cloneNode(useLeft ? field(left, name) : field(right, name)));
                    sources.put(name, useLeft ? "LEFT" : "RIGHT");
                }
            }
        }
        ObjectNode mutated = chromosome(space, crossed); List<String> mutatedGenes = new ArrayList<>();
        for (JsonNode gene : array(field(space, "genes"))) if (random.next() < mutationProbability) {
            String name = text(field(gene, "name")); String before = stable(field(mutated, name)); JsonNode next;
            if ("continuous".equals(text(field(gene, "type")))) {
                double delta = definedNonNull(field(gene, "step")) ? numberJs(field(gene, "step"))
                        : (numberJs(field(gene, "max")) - numberJs(field(gene, "min"))) / 10;
                next = quantize(JSON.numberNode(numberJs(field(mutated, name))
                        + (random.next() * 2 - 1) * delta), gene);
            } else {
                ArrayNode values = array(field(gene, "values"));
                next = cloneNode(values.get(randomInt(random, values.size())));
            }
            mutated.set(name, next); if (!stable(next).equals(before)) mutatedGenes.add(name);
        }
        mutatedGenes.sort(String::compareTo); ObjectNode details = object();
        details.put("selection_operator", "TOURNAMENT_DEB_CONSTRAINED_PARETO");
        details.put("crossover_operator", "ARITHMETIC_MEAN_CONTINUOUS_UNIFORM_TYPED");
        details.put("crossover_applied", crossoverApplied); details.set("selected_gene_sources", sources);
        details.put("mutation_operator", "UNIFORM_TYPED_STEP_MUTATION");
        details.put("mutation_probability", mutationProbability); details.set("mutated_genes", strings(mutatedGenes));
        return new BredCandidate(mutated, details);
    }

    private static ArrayNode confirmationDefinitions(ObjectNode space, ObjectNode baseline,
            List<ObjectNode> seedFinalists) {
        ArrayNode output = array(); Set<String> seen = new HashSet<>();
        java.util.function.BiConsumer<JsonNode, String> add = (candidate, provenance) -> {
            String key = provenance + ":" + hash(candidate); if (!seen.add(key)) return;
            ObjectNode row = object(); row.set("candidate", cloneNode(candidate)); row.put("provenance", provenance);
            output.add(row);
        };
        add.accept(baseline, "SIMPLE_BASELINE"); List<JsonNode> finalists = new ArrayList<>();
        for (ObjectNode seed : seedFinalists) for (JsonNode finalist : array(field(seed, "finalists"))) {
            finalists.add(field(finalist, "chromosome"));
        }
        finalists.forEach(candidate -> add.accept(candidate, "FROZEN_FINALIST_CONFIRMATION"));
        for (JsonNode finalist : finalists) for (JsonNode neighbour : neighbours(space, finalist)) {
            add.accept(neighbour, "DIRECT_PARAMETER_NEIGHBOUR");
        }
        return output;
    }

    private static final class GeneticEvaluationFactory {
        private final JsonNode artifact;
        private final Set<String> ids;
        private final StrategyEvaluatorV5.Evaluator evaluator;
        private final JsonNode exposureHead;
        private final JsonNode constraints;
        private final String mode;
        private final JsonNode foldId;
        private final ArrayNode allHistory;
        private final long[] ordinal;
        private final Map<String, ObjectNode> evaluated;
        private final long seed;
        private final JsonNode cutoff;
        private final List<String> scope;

        private GeneticEvaluationFactory(JsonNode artifact, Set<String> ids,
                StrategyEvaluatorV5.Evaluator evaluator, JsonNode exposureHead, JsonNode constraints,
                String mode, JsonNode foldId, ArrayNode allHistory, long[] ordinal,
                Map<String, ObjectNode> evaluated, long seed, JsonNode cutoff) {
            this.artifact = artifact; this.ids = ids; this.evaluator = evaluator; this.exposureHead = exposureHead;
            this.constraints = constraints; this.mode = mode; this.foldId = foldId; this.allHistory = allHistory;
            this.ordinal = ordinal; this.evaluated = evaluated; this.seed = seed;
            this.cutoff = cutoff == null ? NullNode.instance : cutoff;
            this.scope = new ArrayList<>();
            for (JsonNode row : array(field(artifact, "episodes"))) {
                String id = text(field(row, "episode_id")); if (ids.contains(id)) scope.add(id);
            }
        }

        private ObjectNode taskFor(ObjectNode spec) {
            boolean confirmation = field(spec, "confirmation").asBoolean(false);
            String phase = confirmation ? "TRAIN_CONFIRMATION" : "TRAIN_ONLY"; ObjectNode task = object();
            task.set("artifact", signalView(artifact, scope, phase, foldId)); task.set("episode_ids", strings(scope));
            task.set("chromosome", cloneNode(field(spec, "candidate"))); task.put("seed", seed);
            task.set("generation", cloneNode(field(spec, "generation"))); task.put("phase", phase);
            task.set("cutoff", cloneNode(cutoff)); task.set("fit_cutoff", cloneNode(cutoff));
            task.set("evaluation_cutoff", cloneNode(cutoff)); task.put("weighting", "TRAIN_HALF_LIFE");
            task.set("fold_id", cloneNode(foldId)); return task;
        }

        private ObjectNode materialize(ObjectNode spec, JsonNode result, boolean cacheHit) {
            JsonNode candidate = field(spec, "candidate"); String behavior = hash(candidate);
            boolean confirmation = field(spec, "confirmation").asBoolean(false);
            long generation = integerFromNumber(field(spec, "generation"), 0); String phase = confirmation
                    ? "TRAIN_CONFIRMATION" : "TRAIN_ONLY";
            if (cacheHit) {
                ObjectNode prior = evaluated.get(behavior); long nextOrdinal = ++ordinal[0];
                String attempt = evaluationAttemptIdentity(artifact, text(field(exposureHead, "content_sha256")),
                        text(field(prior, "behavior_alias_sha256")), seed, generation, foldId, phase, nextOrdinal);
                ObjectNode duplicate = prior.deepCopy(); duplicate.put("generation", generation);
                String provenance = truthy(field(spec, "confirmationProvenance"))
                        ? jsString(field(spec, "confirmationProvenance")) : "CONFIRMATION";
                duplicate.put("operator", confirmation ? provenance + "_DUPLICATE_RETAINED" : "DUPLICATE_RETAINED");
                if (confirmation) duplicate.set("confirmation_provenance",
                        truthy(field(spec, "confirmationProvenance"))
                                ? cloneNode(field(spec, "confirmationProvenance"))
                                : truthy(field(prior, "confirmation_provenance"))
                                ? cloneNode(field(prior, "confirmation_provenance")) : NullNode.instance);
                else duplicate.putNull("confirmation_provenance");
                duplicate.set("parent_ids", cloneNode(field(spec, "parentIds")));
                duplicate.put("duplicate_of", behavior); duplicate.put("confirmation", confirmation);
                duplicate.put("cache_hit", true); duplicate.put("evaluation_attempt_sha256", attempt);
                duplicate.put("evaluation_ordinal", nextOrdinal); duplicate.put("scheduler_order", nextOrdinal);
                duplicate.put("checkpoint_generation", generation); allHistory.add(duplicate); return prior;
            }
            ObjectNode rawMetrics = validateEvaluatorResult(result, artifact, ids, "seed " + seed, mode,
                    phase, foldId, cutoff, cutoff, cutoff, "TRAIN_HALF_LIFE", candidate);
            ObjectNode metrics = rawMetrics.deepCopy(); metrics.put("episode_returns_sha256",
                    hash(field(rawMetrics, "episode_returns").isArray()
                            ? field(rawMetrics, "episode_returns") : array()));
            metrics.remove("episode_returns"); ObjectNode feasibility = hardFeasible(metrics, constraints);
            boolean priorDefinition = false;
            for (JsonNode row : allHistory) if (text(field(row, "behavior_alias_sha256")).equals(
                    text(field(metrics, "behavior_alias_sha256")))) { priorDefinition = true; break; }
            long nextOrdinal = ++ordinal[0]; String attempt = evaluationAttemptIdentity(artifact,
                    text(field(exposureHead, "content_sha256")), text(field(metrics, "behavior_alias_sha256")),
                    seed, generation, foldId, phase, nextOrdinal);
            ObjectNode row = object(); row.set("chromosome", cloneNode(candidate)); row.put("behavior_sha256", behavior);
            row.put("behavior_alias_sha256", text(field(metrics, "behavior_alias_sha256")));
            row.put("generation", generation); row.put("seed", seed);
            row.put("operator", jsString(field(spec, "operator")));
            row.set("operator_details", truthy(field(spec, "operatorDetails"))
                    ? cloneNode(field(spec, "operatorDetails")) : NullNode.instance);
            row.set("confirmation_provenance", confirmation && truthy(field(spec, "confirmationProvenance"))
                    ? cloneNode(field(spec, "confirmationProvenance")) : NullNode.instance);
            row.set("parent_ids", cloneNode(field(spec, "parentIds"))); row.put("confirmation", confirmation);
            row.put("cache_hit", false); row.put("evaluation_attempt_sha256", attempt);
            row.put("evaluation_ordinal", nextOrdinal); row.put("scheduler_order", nextOrdinal);
            row.put("checkpoint_generation", generation); row.put("canonical_representative", !priorDefinition);
            ObjectNode fitness = object(); fitness.set("metrics", metrics); fitness.set("objectives", objective(metrics));
            fitness.put("feasible", field(feasibility, "feasible").asBoolean(false));
            fitness.set("violations", cloneNode(field(feasibility, "violations")));
            fitness.set("violation_details", cloneNode(field(feasibility, "violation_details")));
            fitness.put("total_violation", numberJs(field(feasibility, "total_violation")));
            fitness.put("tie_breaker", behavior); row.set("fitness", fitness);
            evaluated.put(behavior, row); allHistory.add(row); return row;
        }

        private List<ObjectNode> evaluateBatch(List<ObjectNode> specs) {
            ObjectNode[] output = new ObjectNode[specs.size()]; List<ObjectNode> fresh = new ArrayList<>();
            Set<String> positions = new HashSet<>();
            for (int index = 0; index < specs.size(); index++) {
                ObjectNode spec = specs.get(index); String behavior = hash(field(spec, "candidate"));
                if (evaluated.containsKey(behavior)) output[index] = materialize(spec, null, true);
                else if (positions.add(behavior)) fresh.add(spec);
            }
            List<ObjectNode> ordered = new ArrayList<>(fresh);
            ordered.sort(Comparator.comparing(spec -> hash(field(spec, "candidate"))));
            List<ObjectNode> tasks = ordered.stream().map(this::taskFor).toList();
            List<ObjectNode> raw = evaluator.evaluateBatch(tasks); Map<String, JsonNode> rawByBehavior = new HashMap<>();
            for (int index = 0; index < ordered.size(); index++) {
                rawByBehavior.put(hash(field(ordered.get(index), "candidate")), raw.get(index));
            }
            for (ObjectNode spec : fresh) {
                String behavior = hash(field(spec, "candidate"));
                ObjectNode row = materialize(spec, rawByBehavior.get(behavior), false);
                for (int index = 0; index < specs.size(); index++) if (hash(field(specs.get(index), "candidate"))
                        .equals(behavior)) { output[index] = row; break; }
            }
            for (int index = 0; index < specs.size(); index++) if (output[index] == null) {
                output[index] = materialize(specs.get(index), null, true);
            }
            return new ArrayList<>(List.of(output));
        }
    }

    public static ObjectNode runGeneticSearchV5(ObjectNode args) {
        return runGeneticSearchV5(args, null);
    }

    /** Java binding for the callable-valued Node genetic-search export. */
    public static ObjectNode runGeneticSearchV5(ObjectNode args, StrategyEvaluatorV5.Evaluator evaluator) {
        ObjectNode options = args == null ? object() : args;
        if (field(options, "artifact").isArray()) {
            throw failure("genetic search requires a verified artifact");
        }
        String mode = truthy(field(options, "mode"))
                ? jsString(field(options, "mode")).toUpperCase(Locale.ROOT) : "AUTHORITATIVE";
        String exposureHeadPath = truthy(field(options, "exposureHeadPath"))
                ? jsString(field(options, "exposureHeadPath")) : null;
        boolean physicalRequired = !"FIXTURE".equals(mode);
        if (physicalRequired) requireFrozenHardPolicy(field(options, "constraints"),
                "authoritative GA hard acceptance policy");
        if (physicalRequired && exposureHeadPath == null) {
            throw failure("authoritative GA requires a canonical physical exposure head path");
        }
        if (physicalRequired && !truthy(field(options, "checkpointPath"))
                && !truthy(field(options, "resumeCheckpoint"))) {
            throw failure("authoritative GA requires a content-addressed checkpoint path");
        }
        JsonNode config = field(options, "config").isObject() ? field(options, "config") : object();
        String registryPath = truthy(field(config, "behaviorDefinitionRegistryPath"))
                ? jsString(field(config, "behaviorDefinitionRegistryPath")) : null;
        String journalPath = registryPath == null ? null
                : truthy(field(config, "behaviorDefinitionRegistryJournalPath"))
                ? jsString(field(config, "behaviorDefinitionRegistryJournalPath"))
                : registryPath + ".journal.json";
        if (journalPath != null) {
            ObjectNode recovery = object(); recovery.put("journalPath", journalPath);
            recoverExposureRegistryTransaction(recovery);
        }
        if (truthy(field(config, "trainingCutoff")) && field(options, "trainingEpisodeIds").isArray()) {
            Set<String> selected = textSet(field(options, "trainingEpisodeIds")); List<String> unavailable = new ArrayList<>();
            for (JsonNode row : array(field(field(options, "artifact"), "episodes"))) {
                String id = text(field(row, "episode_id"));
                if (selected.contains(id) && !availableBy(row, field(config, "trainingCutoff"))) unavailable.add(id);
            }
            if (!unavailable.isEmpty()) throw failure("training scope contains label/execution data unavailable at cutoff ("
                    + String.join(",", unavailable) + ")");
        }
        if (exposureHeadPath != null) {
            ObjectNode physical = readExposureHeadFile(exposureHeadPath);
            String expected = truthy(field(options, "exposureHeadPredecessorSha256"))
                    ? jsString(field(options, "exposureHeadPredecessorSha256"))
                    : text(field(field(options, "exposureHead"), "content_sha256"));
            if (!text(field(physical, "content_sha256")).equals(expected)
                    || !text(field(physical, "content_sha256")).equals(
                    text(field(field(options, "exposureHead"), "content_sha256")))) {
                throw failure("authoritative GA exposure head predecessor is stale, missing, or reset");
            }
        }
        ObjectNode result = truthy(field(options, "checkpointPath")) || truthy(field(options, "resumeCheckpoint"))
                ? checkpointedGeneticSearch(options, evaluator, mode)
                : legacyGeneticSearch(options, evaluator, mode);
        if (exposureHeadPath != null) persistGeneticExposure(options, result, exposureHeadPath,
                registryPath, journalPath);
        return result;
    }

    public static ObjectNode resumeGeneticSearchV5(ObjectNode args) {
        return resumeGeneticSearchV5(args, null);
    }

    /** Exact resume wrapper: validate the supplied checkpoint before dispatching. */
    public static ObjectNode resumeGeneticSearchV5(ObjectNode args, StrategyEvaluatorV5.Evaluator evaluator) {
        ObjectNode options = args == null ? object() : args; ObjectNode validation = object();
        validation.set("artifact", cloneNode(field(options, "artifact")));
        validation.set("exposureHead", cloneNode(field(options, "exposureHead")));
        validation.set("geneSpace", cloneNode(field(options, "geneSpace")));
        if (defined(field(options, "foldId"))) validation.set("foldId", cloneNode(field(options, "foldId")));
        if (truthy(field(options, "config"))) {
            String mode = truthy(field(options, "mode"))
                    ? jsString(field(options, "mode")).toUpperCase(Locale.ROOT) : "AUTHORITATIVE";
            validation.set("config", geneticConfig(field(options, "config"), mode));
        }
        validateGeneticCheckpoint(field(options, "checkpoint"), validation);
        ObjectNode forwarded = options.deepCopy(); forwarded.remove("checkpoint");
        forwarded.set("resumeCheckpoint", cloneNode(field(options, "checkpoint")));
        return runGeneticSearchV5(forwarded, evaluator);
    }

    private static ObjectNode legacyGeneticSearch(ObjectNode options, StrategyEvaluatorV5.Evaluator evaluator,
            String mode) {
        assertGeneticCheckpointPath(field(options, "checkpointPath"), field(options, "config"));
        JsonNode artifact = field(options, "artifact"); JsonNode rawIds = field(options, "trainingEpisodeIds");
        if (artifact.isArray() || defined(rawIds) && !rawIds.isArray()) {
            throw failure("genetic search requires a verified artifact and string episode scope");
        }
        if (rawIds.isArray()) for (JsonNode id : rawIds) if (!id.isTextual()) {
            throw failure("genetic search requires a verified artifact and string episode scope");
        }
        ObjectNode exposureHead = validateExposureHead(field(options, "exposureHead"));
        ObjectNode validation = object(); validation.set("exposureHead", exposureHead); validation.put("allowSubset", true);
        validateStatisticalArtifactSet(artifact, validation); ObjectNode space = normalizeGenes(field(options, "geneSpace"));
        Set<String> ids = trainingIds(options, artifact); if (ids.isEmpty()) throw failure("genetic training scope is empty");
        Set<String> artifactIds = fieldTextSet(field(artifact, "episodes"), "episode_id");
        for (String id : ids) if (!artifactIds.contains(id)) throw failure("training episode " + id + " is absent from artifact");
        requireGeneticEvaluator(evaluator, mode); ObjectNode frozen = geneticConfig(field(options, "config"), mode);
        ArrayNode allHistory = array(); List<ObjectNode> seedFinalists = new ArrayList<>();
        Map<String, List<Long>> seedMembership = new LinkedHashMap<>(); long[] ordinal = {0};
        ObjectNode baseline = truthy(field(options, "baseline"))
                ? chromosome(space, field(options, "baseline")) : chromosome(space, object());
        ObjectNode lastCheckpointState = null;
        for (JsonNode seedNode : array(field(frozen, "seeds"))) {
            long seed = (long) numberJs(seedNode); XorShift32 random = new XorShift32(seed);
            GeneticEvaluationFactory factory = geneticFactory(artifact, ids, evaluator, exposureHead,
                    field(options, "constraints"), mode, fieldOrText(options, "foldId", "training"),
                    allHistory, ordinal, new LinkedHashMap<>(), seed, cutoff(options));
            List<ObjectNode> initial = initialPopulation(space, baseline, frozen, random);
            List<ObjectNode> population = factory.evaluateBatch(initial); String previous = "";
            long plateau = 0; String stopping = "MAX_GENERATIONS"; long generation = 1;
            while (generation < integer(field(frozen, "generations"), 0)) {
                rankCrowd(population); List<ObjectNode> offspringSpecs = offspring(space, population, frozen,
                        random, generation); List<ObjectNode> offspring = factory.evaluateBatch(offspringSpecs);
                LinkedHashMap<String, ObjectNode> unique = new LinkedHashMap<>();
                for (ObjectNode row : population) unique.put(text(field(row, "behavior_sha256")), row);
                for (ObjectNode row : offspring) unique.put(text(field(row, "behavior_sha256")), row);
                List<ObjectNode> next = survivors(new ArrayList<>(unique.values()),
                        (int) integer(field(frozen, "population"), 0));
                String signature = paretoSignature(next); plateau = signature.equals(previous) ? plateau + 1 : 0;
                previous = signature; population = next; generation++;
                if (generation >= integer(field(frozen, "minGenerations"), 0)
                        && plateau >= integer(field(frozen, "plateauGenerations"), 0)) {
                    stopping = "NO_NEW_PARETO_SIGNATURE_FOR_PLATEAU"; break;
                }
            }
            rankCrowd(population); List<ObjectNode> finalists = new ArrayList<>();
            for (ObjectNode row : population) if (integer(field(row, "rank"), -1) == 0) finalists.add(row);
            finalists.sort(Comparator.comparing(row -> text(field(row, "behavior_sha256"))));
            ObjectNode seedRun = object(); seedRun.put("seed", seed); seedRun.set("finalists", toArray(finalists));
            seedRun.put("generations_completed", generation); seedRun.put("stopping", stopping);
            seedRun.put("evaluated_k", factory.evaluated.size()); seedFinalists.add(seedRun);
            lastCheckpointState = object(); lastCheckpointState.put("seed", seed);
            lastCheckpointState.put("generation", generation); lastCheckpointState.set("population", toArray(population));
            lastCheckpointState.set("history", allHistory.deepCopy());
            addSeedMembership(seedMembership, finalists, seed);
        }
        if (truthy(field(options, "checkpointPath"))) {
            ObjectNode checkpointArgs = object(); checkpointArgs.set("artifact", cloneNode(artifact));
            checkpointArgs.set("exposureHead", exposureHead); checkpointArgs.set("geneSpace", space);
            checkpointArgs.set("foldId", fieldOrText(options, "foldId", "training"));
            checkpointArgs.set("seed", cloneNode(field(lastCheckpointState, "seed")));
            checkpointArgs.set("generation", cloneNode(field(lastCheckpointState, "generation")));
            checkpointArgs.set("config", frozen); checkpointArgs.set("population", cloneNode(field(lastCheckpointState, "population")));
            checkpointArgs.set("history", cloneNode(field(lastCheckpointState, "history")));
            ObjectNode checkpoint = makeGeneticCheckpoint(checkpointArgs); ObjectNode write = object();
            write.set("filePath", cloneNode(field(options, "checkpointPath"))); write.set("checkpoint", checkpoint);
            write.put("expectedExposureHeadSha256", text(field(exposureHead, "content_sha256")));
            writeGeneticCheckpointFile(write);
        }
        return finalizeGeneticSearch(options, artifact, exposureHead, space, frozen, ids, evaluator, mode,
                allHistory, seedFinalists, seedMembership, ordinal, baseline);
    }

    private static ObjectNode checkpointedGeneticSearch(ObjectNode options,
            StrategyEvaluatorV5.Evaluator evaluator, String mode) {
        Path checkpointTarget = assertGeneticCheckpointPath(field(options, "checkpointPath"), field(options, "config"));
        JsonNode artifact = field(options, "artifact"); ObjectNode exposureHead = validateExposureHead(
                field(options, "exposureHead")); ObjectNode validation = object();
        validation.set("exposureHead", exposureHead); validation.put("allowSubset", true);
        validateStatisticalArtifactSet(artifact, validation); ObjectNode space = normalizeGenes(field(options, "geneSpace"));
        Set<String> ids = trainingIds(options, artifact); if (ids.isEmpty()) throw failure("genetic training scope is empty");
        requireGeneticEvaluator(evaluator, mode); ObjectNode frozen = geneticConfig(field(options, "config"), mode);
        JsonNode resume = field(options, "resumeCheckpoint");
        if (truthy(resume)) {
            ObjectNode checkpointValidation = object(); checkpointValidation.set("artifact", cloneNode(artifact));
            checkpointValidation.set("exposureHead", exposureHead); checkpointValidation.set("geneSpace", space);
            checkpointValidation.set("foldId", fieldOrText(options, "foldId", "training"));
            checkpointValidation.set("config", frozen); validateGeneticCheckpoint(resume, checkpointValidation);
        }
        ArrayNode allHistory = truthy(resume) ? array(field(resume, "history")).deepCopy() : array();
        List<ObjectNode> seedFinalists = truthy(resume) ? nodeObjects(array(field(resume, "seed_finalists")))
                : new ArrayList<>(); Map<String, List<Long>> seedMembership = truthy(resume)
                ? decodeSeedMembership(field(resume, "seed_membership")) : new LinkedHashMap<>();
        String[] checkpointPrevious = {truthy(resume) ? text(field(resume, "content_sha256")) : null};
        int startSeedIndex = truthy(resume) ? (int) integer(field(resume, "seed_index"), 0) : 0;
        long[] ordinal = {0}; for (JsonNode row : allHistory) ordinal[0] = Math.max(ordinal[0],
                integerFromNumber(field(row, "evaluation_ordinal"), 0));
        ObjectNode baseline = truthy(field(options, "baseline"))
                ? chromosome(space, field(options, "baseline")) : chromosome(space, object());
        for (int seedIndex = startSeedIndex; seedIndex < array(field(frozen, "seeds")).size(); seedIndex++) {
            long seed = (long) numberJs(array(field(frozen, "seeds")).get(seedIndex));
            boolean resumingCurrent = truthy(resume) && seedIndex == startSeedIndex
                    && "RUNNING".equals(text(field(resume, "checkpoint_status")));
            Map<String, ObjectNode> evaluated = new LinkedHashMap<>();
            for (JsonNode row : allHistory) if (integerFromNumber(field(row, "seed"), Long.MIN_VALUE) == seed
                    && !field(row, "confirmation").asBoolean(false)) {
                evaluated.put(text(field(row, "behavior_sha256")), (ObjectNode) row);
            }
            XorShift32 random = resumingCurrent
                    ? new XorShift32(seed, integerFromNumber(field(resume, "rng_state"), seed))
                    : new XorShift32(seed);
            GeneticEvaluationFactory factory = geneticFactory(artifact, ids, evaluator, exposureHead,
                    field(options, "constraints"), mode, fieldOrText(options, "foldId", "training"),
                    allHistory, ordinal, evaluated, seed, cutoff(options));
            List<ObjectNode> population; String previous; long plateau; long generation;
            if (resumingCurrent) {
                population = nodeObjects(array(field(resume, "population"))); previous = text(field(resume, "pareto_signature"));
                plateau = integerFromNumber(field(resume, "plateau"), 0);
                generation = integerFromNumber(field(resume, "generation"), 0) + 1;
            } else {
                population = factory.evaluateBatch(initialPopulation(space, baseline, frozen, random));
                previous = ""; plateau = 0; generation = 1;
            }
            String stopping = "MAX_GENERATIONS";
            while (generation < integer(field(frozen, "generations"), 0)) {
                rankCrowd(population); List<ObjectNode> offspringRows = factory.evaluateBatch(
                        offspring(space, population, frozen, random, generation));
                LinkedHashMap<String, ObjectNode> unique = new LinkedHashMap<>();
                for (ObjectNode row : population) unique.put(text(field(row, "behavior_sha256")), row);
                for (ObjectNode row : offspringRows) unique.put(text(field(row, "behavior_sha256")), row);
                List<ObjectNode> next = survivors(new ArrayList<>(unique.values()),
                        (int) integer(field(frozen, "population"), 0));
                String signature = paretoSignature(next); plateau = signature.equals(previous) ? plateau + 1 : 0;
                previous = signature; population = next; generation++;
                checkpointPrevious[0] = persistCheckpoint(checkpointTarget, options, artifact, exposureHead,
                        space, frozen, allHistory, seedFinalists, seedMembership, checkpointPrevious[0],
                        seedIndex, seed, generation - 1, random.state(), population, plateau, signature, "RUNNING");
                if (defined(field(field(options, "config"), "interruptAfterGeneration"))
                        && "FIXTURE".equals(mode) && generation >= numberJs(
                        field(field(options, "config"), "interruptAfterGeneration"))) {
                    throw failure("GENETIC_CHECKPOINT_INTERRUPTED");
                }
                if (generation >= integer(field(frozen, "minGenerations"), 0)
                        && plateau >= integer(field(frozen, "plateauGenerations"), 0)) {
                    stopping = "NO_NEW_PARETO_SIGNATURE_FOR_PLATEAU"; break;
                }
            }
            rankCrowd(population); List<ObjectNode> finalists = new ArrayList<>();
            for (ObjectNode row : population) if (integer(field(row, "rank"), -1) == 0) finalists.add(row);
            finalists.sort(Comparator.comparing(row -> text(field(row, "behavior_sha256"))));
            ObjectNode seedRun = object(); seedRun.put("seed", seed); seedRun.set("finalists", toArray(finalists));
            seedRun.put("generations_completed", generation); seedRun.put("stopping", stopping);
            seedRun.put("evaluated_k", evaluated.size()); seedFinalists.add(seedRun);
            addSeedMembership(seedMembership, finalists, seed);
            checkpointPrevious[0] = persistCheckpoint(checkpointTarget, options, artifact, exposureHead,
                    space, frozen, allHistory, seedFinalists, seedMembership, checkpointPrevious[0],
                    seedIndex + 1, seed, generation - 1, random.state(), population, plateau, previous,
                    "SEED_COMPLETE");
            resume = MissingNode.getInstance();
        }
        if (!"COMPLETE".equals(text(field(resume, "checkpoint_status")))) {
            ObjectNode last = seedFinalists.getLast();
            checkpointPrevious[0] = persistCheckpoint(checkpointTarget, options, artifact, exposureHead,
                    space, frozen, allHistory, seedFinalists, seedMembership, checkpointPrevious[0],
                    array(field(frozen, "seeds")).size(), integer(field(last, "seed"), 0),
                    integer(field(last, "generations_completed"), 0), null,
                    nodeObjects(array(field(last, "finalists"))), 0, "", "COMPLETE");
        }
        return finalizeGeneticSearch(options, artifact, exposureHead, space, frozen, ids, evaluator, mode,
                allHistory, seedFinalists, seedMembership, ordinal, baseline);
    }

    private static ObjectNode finalizeGeneticSearch(ObjectNode options, JsonNode artifact, ObjectNode exposureHead,
            ObjectNode space, ObjectNode frozen, Set<String> ids, StrategyEvaluatorV5.Evaluator evaluator,
            String mode, ArrayNode allHistory, List<ObjectNode> seedFinalists,
            Map<String, List<Long>> seedMembership, long[] ordinal, ObjectNode baseline) {
        ArrayNode definitions = confirmationDefinitions(space, baseline, seedFinalists); List<ObjectNode> specs = new ArrayList<>();
        for (JsonNode item : definitions) {
            ObjectNode spec = object(); spec.set("candidate", cloneNode(field(item, "candidate")));
            spec.put("generation", -1); spec.set("operator", cloneNode(field(item, "provenance")));
            spec.set("confirmationProvenance", cloneNode(field(item, "provenance")));
            spec.set("parentIds", array()); spec.put("confirmation", true); specs.add(spec);
        }
        long confirmSeed = integer(array(field(frozen, "seeds")).get(0), 0);
        GeneticEvaluationFactory confirmFactory = geneticFactory(artifact, ids, evaluator, exposureHead,
                field(options, "constraints"), mode, fieldOrText(options, "foldId", "training"),
                allHistory, ordinal, new LinkedHashMap<>(), confirmSeed, cutoff(options));
        List<ObjectNode> confirmation = confirmFactory.evaluateBatch(specs); Set<String> aliasSet = new HashSet<>();
        for (JsonNode row : allHistory) aliasSet.add(text(field(row, "behavior_alias_sha256")));
        List<String> aliases = new ArrayList<>(aliasSet); aliases.sort(String::compareTo);
        ObjectNode append = object(); append.set("prior", exposureHead);
        append.put("datasetSha256", text(field(field(artifact, "lineage"), "dataset_sha256")));
        append.set("behaviorAliases", strings(aliases)); append.put("exposureAttemptCount", allHistory.size());
        append.set("observedAt", truthy(cutoff(options)) ? cloneNode(cutoff(options)) : NullNode.instance);
        ObjectNode nextHead = appendExposureHead(append); List<String> stableAliases = new ArrayList<>();
        for (String alias : aliases) if (seedMembership.getOrDefault(alias, List.of()).size() >= 2) stableAliases.add(alias);
        List<ObjectNode> stableConfirmation = new ArrayList<>();
        for (ObjectNode row : confirmation) if (field(field(row, "fitness"), "feasible").asBoolean(false)
                && stableAliases.contains(text(field(row, "behavior_alias_sha256")))) stableConfirmation.add(row);
        stableConfirmation.sort((left, right) -> {
            double leftScore = Math.min(selectionScore(field(field(field(left, "fitness"), "metrics"), "bootstrap_p20")),
                    selectionScore(field(field(field(left, "fitness"), "metrics"), "weighted_bootstrap_p20")));
            double rightScore = Math.min(selectionScore(field(field(field(right, "fitness"), "metrics"), "bootstrap_p20")),
                    selectionScore(field(field(field(right, "fitness"), "metrics"), "weighted_bootstrap_p20")));
            int compared = Double.compare(rightScore, leftScore); return compared != 0 ? compared
                    : text(field(left, "behavior_alias_sha256")).compareTo(text(field(right, "behavior_alias_sha256")));
        });
        ObjectNode selected = stableConfirmation.isEmpty() ? null : stableConfirmation.getFirst();
        ObjectNode run = object(); run.put("schema", schema("genetic")); run.put("version", 1);
        run.set("fold_id", fieldOrText(options, "foldId", "training")); run.set("config", frozen);
        run.set("gene_space", space); List<String> sortedIds = new ArrayList<>(ids); sortedIds.sort(String::compareTo);
        run.set("training_episode_ids", strings(sortedIds)); run.set("population_history", allHistory);
        ArrayNode seeds = array(); for (ObjectNode seed : seedFinalists) {
            ObjectNode row = object(); row.set("seed", cloneNode(field(seed, "seed")));
            row.set("generations_completed", cloneNode(field(seed, "generations_completed")));
            row.set("stopping", cloneNode(field(seed, "stopping"))); row.set("evaluated_k", cloneNode(field(seed, "evaluated_k")));
            ArrayNode finalists = array(); for (JsonNode item : array(field(seed, "finalists"))) {
                finalists.add(text(field(item, "behavior_alias_sha256")));
            }
            row.set("finalists", finalists); seeds.add(row);
        }
        run.set("seed_runs", seeds); run.set("evaluated_behavior_aliases", strings(aliases));
        run.put("evaluated_k", aliases.size()); Set<String> attempts = new HashSet<>(), chromosomes = new HashSet<>();
        for (JsonNode row : allHistory) { attempts.add(text(field(row, "evaluation_attempt_sha256")));
            chromosomes.add(text(field(row, "behavior_sha256"))); }
        run.put("evaluation_attempt_k", attempts.size()); run.put("chromosome_evaluated_k", chromosomes.size());
        run.put("cumulative_k", integer(field(nextHead, "cumulative_k"), 0));
        run.put("cumulative_exposure_k", definedNonNull(field(nextHead, "exposure_attempt_k"))
                ? integer(field(nextHead, "exposure_attempt_k"), 0) : integer(field(nextHead, "cumulative_k"), 0));
        run.put("exposure_head_sha256", text(field(nextHead, "content_sha256")));
        if (selected == null) run.putNull("selected_behavior_alias_sha256");
        else run.put("selected_behavior_alias_sha256", text(field(selected, "behavior_alias_sha256")));
        run.put("selected_seed_count", selected == null ? 0
                : seedMembership.getOrDefault(text(field(selected, "behavior_alias_sha256")), List.of()).size());
        ObjectNode stability = object(); stability.put("required", 2); stability.set("stable_aliases", strings(stableAliases));
        run.set("seed_stability", stability); ObjectNode baselineFitness = null; ArrayNode neighbours = array();
        for (ObjectNode row : confirmation) {
            if ("SIMPLE_BASELINE".equals(text(field(row, "operator")))) baselineFitness = objectOrEmpty(field(row, "fitness"));
            if ("DIRECT_PARAMETER_NEIGHBOUR".equals(text(field(row, "operator")))) {
                ObjectNode item = object(); item.set("behavior_sha256", cloneNode(field(row, "behavior_sha256")));
                item.set("chromosome", cloneNode(field(row, "chromosome")));
                item.set("behavior_alias_sha256", cloneNode(field(row, "behavior_alias_sha256")));
                item.put("feasible", field(field(row, "fitness"), "feasible").asBoolean(false));
                item.set("expectancy_r", cloneNode(field(field(field(row, "fitness"), "metrics"), "expectancy_r")));
                neighbours.add(item);
            }
        }
        run.set("baseline", baselineFitness == null ? NullNode.instance : baselineFitness.deepCopy());
        run.set("neighbours", neighbours);
        if (selected == null) run.putNull("selected"); else {
            ObjectNode item = object(); item.set("behavior_sha256", cloneNode(field(selected, "behavior_sha256")));
            item.set("behavior_alias_sha256", cloneNode(field(selected, "behavior_alias_sha256")));
            item.set("chromosome", cloneNode(field(selected, "chromosome")));
            item.set("fitness", cloneNode(field(selected, "fitness"))); run.set("selected", item);
        }
        run = withHash(run); validateGeneticArtifact(run); validateContractSchema(run); ObjectNode result = object();
        result.set("run", run); result.set("exposureHead", nextHead);
        result.set("selected", selected == null ? NullNode.instance : selected.deepCopy());
        result.set("confirmation", toArray(confirmation)); return result;
    }

    private static void persistGeneticExposure(ObjectNode options, ObjectNode result, String exposureHeadPath,
            String registryPath, String journalPath) {
        JsonNode run = field(result, "run"); Map<String, JsonNode> definitionsByAlias = new LinkedHashMap<>();
        for (JsonNode row : array(field(run, "population_history"))) definitionsByAlias.putIfAbsent(
                text(field(row, "behavior_alias_sha256")), row);
        ArrayNode definitionRecords = array(); ObjectNode definitionHashes = object(), vectorCommitments = object();
        JsonNode config = field(options, "config").isObject() ? field(options, "config") : object();
        for (JsonNode aliasNode : array(field(run, "evaluated_behavior_aliases"))) {
            String alias = text(aliasNode); JsonNode definition = definitionsByAlias.get(alias); ObjectNode row = object();
            row.put("behavior_sha256", alias); row.set("chromosome", definition == null
                    ? NullNode.instance : cloneNode(field(definition, "chromosome")));
            row.put("dataset_sha256", text(field(field(field(options, "artifact"), "lineage"), "dataset_sha256")));
            row.set("observed_at", truthy(field(config, "trainingCutoff"))
                    ? cloneNode(field(config, "trainingCutoff")) : NullNode.instance);
            row.put("source", "STATISTICAL_SEARCH"); row.set("evaluator_sha256",
                    firstNonNullOrNull(field(config, "evaluatorSpecSha256"), field(config, "evaluator_sha256")));
            row.set("precommit_sha256", firstNonNullOrNull(field(config, "precommitSha256"),
                    field(field(field(options, "artifact"), "lineage"), "precommit_sha256")));
            row.set("lifecycle_sha256", truthy(field(config, "lifecycleSha256"))
                    ? cloneNode(field(config, "lifecycleSha256")) : NullNode.instance);
            definitionRecords.add(row); definitionHashes.put(alias, behaviorDefinitionSha256(row));
            ObjectNode commitment = object(); commitment.put("schema", "strategy-v5-statistical-vector-commitment/1");
            commitment.set("episode_returns_sha256", definition == null
                    ? NullNode.instance : cloneNode(field(field(definition, "fitness"), "metrics").path("episode_returns_sha256")));
            vectorCommitments.put(alias, hash(commitment));
        }
        ObjectNode priorPhysical = readExposureHeadFile(exposureHeadPath); ObjectNode append = object();
        append.set("prior", priorPhysical); append.put("datasetSha256",
                text(field(field(field(options, "artifact"), "lineage"), "dataset_sha256")));
        append.set("behaviorAliases", cloneNode(field(run, "evaluated_behavior_aliases")));
        append.set("behaviorDefinitions", definitionHashes); append.set("vectorCommitments", vectorCommitments);
        append.set("observedAt", truthy(field(config, "trainingCutoff"))
                ? cloneNode(field(config, "trainingCutoff")) : NullNode.instance);
        append.put("exposureAttemptCount", integer(field(run, "evaluation_attempt_k"), 0));
        ObjectNode anticipated = appendExposureHead(append); ObjectNode priorRegistry = null;
        if (registryPath != null && Files.exists(Path.of(registryPath))) priorRegistry = readBehaviorDefinitionRegistryFile(registryPath);
        if (journalPath != null) {
            ObjectNode journal = object(); journal.put("journalPath", journalPath);
            journal.put("exposureHeadPath", exposureHeadPath); journal.put("registryPath", registryPath);
            journal.set("priorHead", priorPhysical); journal.set("nextHead", anticipated);
            journal.set("priorRegistrySha256", priorRegistry == null ? NullNode.instance
                    : cloneNode(field(priorRegistry, "content_sha256"))); journal.set("definitions", definitionRecords);
            writeExposureRegistryJournal(journal);
        }
        ObjectNode fileAppend = append.deepCopy(); fileAppend.put("filePath", exposureHeadPath);
        fileAppend.put("expectedHeadSha256", text(field(field(options, "exposureHead"), "content_sha256")));
        fileAppend.remove("prior"); ObjectNode persisted = appendExposureHeadFile(fileAppend);
        result.set("exposureHead", persisted); ObjectNode updatedRun = objectOrEmpty(run).deepCopy();
        updatedRun.put("cumulative_k", integer(field(persisted, "cumulative_k"), 0));
        updatedRun.put("cumulative_exposure_k", definedNonNull(field(persisted, "exposure_attempt_k"))
                ? integer(field(persisted, "exposure_attempt_k"), 0) : integer(field(persisted, "cumulative_k"), 0));
        updatedRun.put("exposure_head_sha256", text(field(persisted, "content_sha256")));
        result.set("run", withHash(updatedRun));
        if (registryPath != null) {
            ObjectNode registry = object(); registry.put("filePath", registryPath);
            registry.set("expectedRegistrySha256", priorRegistry == null ? NullNode.instance
                    : cloneNode(field(priorRegistry, "content_sha256")));
            registry.put("priorExposureHeadSha256", text(field(field(options, "exposureHead"), "content_sha256")));
            registry.set("exposureHead", persisted); registry.set("definitions", definitionRecords);
            result.set("behaviorDefinitionRegistry", appendBehaviorDefinitionRegistryFile(registry));
            if (journalPath != null) try { Files.deleteIfExists(Path.of(journalPath)); }
            catch (IOException error) { throw failure(error.getMessage()); }
        }
    }

    private static void requireGeneticEvaluator(StrategyEvaluatorV5.Evaluator evaluator, String mode) {
        if (evaluator == null) throw failure("genetic search requires a deterministic evaluator function");
        if ("FIXTURE".equals(mode)) return; JsonNode provenance = evaluator.workerProvenance();
        if (provenance == null || !"strategy-v5-statistical-worker/1".equals(text(field(provenance, "schema")))
                || !field(provenance, "verified").asBoolean(false)
                || !field(provenance, "deterministic").asBoolean(false)
                || !field(provenance, "artifact_paths_bound").asBoolean(false)
                || !field(provenance, "worker_count").isIntegralNumber()
                || integer(field(provenance, "worker_count"), 0) < 1
                || !field(provenance, "memory_budget_mb").isIntegralNumber()
                || integer(field(provenance, "memory_budget_mb"), 0) < 1) {
            throw failure("authoritative evaluation requires a verified deterministic worker implementation");
        }
    }

    private static GeneticEvaluationFactory geneticFactory(JsonNode artifact, Set<String> ids,
            StrategyEvaluatorV5.Evaluator evaluator, JsonNode exposureHead, JsonNode constraints, String mode,
            JsonNode foldId, ArrayNode history, long[] ordinal, Map<String, ObjectNode> evaluated,
            long seed, JsonNode cutoff) {
        return new GeneticEvaluationFactory(artifact, ids, evaluator, exposureHead,
                constraints.isObject() ? constraints : object(), mode, foldId, history, ordinal, evaluated, seed, cutoff);
    }

    private static Set<String> trainingIds(ObjectNode options, JsonNode artifact) {
        Set<String> ids = new LinkedHashSet<>(); JsonNode supplied = field(options, "trainingEpisodeIds");
        if (supplied.isArray()) supplied.forEach(id -> ids.add(jsString(id))); else for (JsonNode row : array(field(artifact, "episodes"))) {
            if (field(row, "eligible").asBoolean(false)) ids.add(text(field(row, "episode_id")));
        }
        return ids;
    }

    private static List<ObjectNode> initialPopulation(ObjectNode space, ObjectNode baseline, ObjectNode frozen,
            XorShift32 random) {
        List<ObjectNode> specs = new ArrayList<>(); int size = (int) integer(field(frozen, "population"), 0);
        for (int index = 0; index < size; index++) {
            ObjectNode candidate = index == 0 ? baseline.deepCopy() : object();
            if (index != 0) for (JsonNode gene : array(field(space, "genes"))) {
                candidate.set(text(field(gene, "name")), randomGene(gene, random));
            }
            ObjectNode spec = object(); spec.set("candidate", candidate); spec.put("generation", 0);
            spec.put("operator", index == 0 ? "BASELINE_ANCHOR" : "INITIAL"); spec.set("parentIds", array());
            spec.put("confirmation", false); specs.add(spec);
        }
        return specs;
    }

    private static List<ObjectNode> offspring(ObjectNode space, List<ObjectNode> population, ObjectNode frozen,
            XorShift32 random, long generation) {
        List<ObjectNode> specs = new ArrayList<>(); int size = (int) integer(field(frozen, "population"), 0);
        double crossover = numberJs(field(frozen, "crossoverProbability"));
        double mutation = definedNonNull(field(frozen, "mutationProbability"))
                ? numberJs(field(frozen, "mutationProbability")) : 1d / array(field(space, "genes")).size();
        while (specs.size() < size) {
            ObjectNode left = tournament(population, random), right = tournament(population, random);
            BredCandidate bred = breed(space, field(left, "chromosome"), field(right, "chromosome"),
                    random, crossover, mutation); ObjectNode spec = object(); spec.set("candidate", bred.candidate());
            spec.put("generation", generation); spec.put("operator", "TOURNAMENT_ARITHMETIC_CROSSOVER_UNIFORM_MUTATION");
            spec.set("operatorDetails", bred.details()); ArrayNode parents = array();
            parents.add(text(field(left, "behavior_sha256"))); parents.add(text(field(right, "behavior_sha256")));
            spec.set("parentIds", parents); spec.put("confirmation", false); specs.add(spec);
        }
        return specs;
    }

    private static ObjectNode tournament(List<ObjectNode> population, XorShift32 random) {
        ObjectNode left = population.get(randomInt(random, population.size()));
        ObjectNode right = population.get(randomInt(random, population.size()));
        long leftRank = integer(field(left, "rank"), 0), rightRank = integer(field(right, "rank"), 0);
        double leftCrowd = numberJs(field(left, "crowding_distance"));
        double rightCrowd = numberJs(field(right, "crowding_distance"));
        boolean takeLeft = leftRank < rightRank || leftRank == rightRank
                && (leftCrowd > rightCrowd || leftCrowd == rightCrowd
                && text(field(left, "behavior_sha256")).compareTo(text(field(right, "behavior_sha256"))) < 0);
        return takeLeft ? left : right;
    }

    private static void addSeedMembership(Map<String, List<Long>> memberships,
            List<ObjectNode> finalists, long seed) {
        for (ObjectNode row : finalists) {
            String alias = text(field(row, "behavior_alias_sha256"));
            List<Long> members = memberships.computeIfAbsent(alias, ignored -> new ArrayList<>());
            if (!members.contains(seed)) members.add(seed); members.sort(Long::compareTo);
        }
    }

    private static Map<String, List<Long>> decodeSeedMembership(JsonNode raw) {
        Map<String, List<Long>> output = new LinkedHashMap<>();
        for (JsonNode tuple : array(raw)) {
            if (!tuple.isArray() || tuple.size() < 2) continue; List<Long> seeds = new ArrayList<>();
            for (JsonNode seed : array(tuple.get(1))) seeds.add((long) numberJs(seed));
            output.put(jsString(tuple.get(0)), seeds);
        }
        return output;
    }

    private static String persistCheckpoint(Path checkpointTarget, ObjectNode options, JsonNode artifact,
            ObjectNode exposureHead, ObjectNode space, ObjectNode frozen, ArrayNode allHistory,
            List<ObjectNode> seedFinalists, Map<String, List<Long>> seedMembership, String previous,
            int seedIndex, long seed, long generation, Long rngState, List<ObjectNode> population,
            long plateau, String paretoSignature, String status) {
        ObjectNode args = object(); args.set("artifact", cloneNode(artifact)); args.set("exposureHead", exposureHead);
        args.set("geneSpace", space); args.set("foldId", fieldOrText(options, "foldId", "training"));
        args.put("seed", seed); args.put("seedIndex", seedIndex); args.put("generation", generation);
        if (rngState == null) args.putNull("rngState"); else args.put("rngState", rngState);
        args.set("config", frozen); args.set("population", toArray(population)); args.set("history", allHistory.deepCopy());
        args.set("seedFinalists", toArray(seedFinalists)); ArrayNode memberships = array();
        for (Map.Entry<String, List<Long>> entry : seedMembership.entrySet()) {
            ArrayNode tuple = array(); tuple.add(entry.getKey()); ArrayNode seeds = array(); entry.getValue().forEach(seeds::add);
            tuple.add(seeds); memberships.add(tuple);
        }
        args.set("seedMembership", memberships); args.put("plateau", plateau); args.put("paretoSignature", paretoSignature);
        if (previous == null) args.putNull("previousCheckpointSha256"); else args.put("previousCheckpointSha256", previous);
        args.put("checkpointStatus", status); ObjectNode checkpoint = makeGeneticCheckpoint(args); ObjectNode write = object();
        if (checkpointTarget == null) write.putNull("filePath"); else write.put("filePath", checkpointTarget.toString());
        write.set("checkpoint", checkpoint); write.put("expectedExposureHeadSha256", text(field(exposureHead, "content_sha256")));
        if (previous == null) write.putNull("expectedCheckpointSha256"); else write.put("expectedCheckpointSha256", previous);
        return text(field(writeGeneticCheckpointFile(write), "content_sha256"));
    }

    private static Path assertGeneticCheckpointPath(JsonNode rawPath, JsonNode config) {
        if (!truthy(rawPath)) return null; Path target = requiredFilePath(jsString(rawPath), "genetic checkpoint path");
        Path boundary = truthy(field(config, "checkpointDirectory"))
                ? requiredFilePath(jsString(field(config, "checkpointDirectory")), "genetic checkpoint directory")
                : target.getParent();
        if (target.equals(boundary) || !target.startsWith(boundary)) {
            throw failure("genetic checkpoint path escapes its declared checkpoint directory");
        }
        return assertConfinedPath(target, "genetic checkpoint path", false, boundary);
    }

    private static JsonNode cutoff(ObjectNode options) {
        JsonNode value = field(field(options, "config"), "trainingCutoff");
        return truthy(value) ? value : NullNode.instance;
    }

    private static boolean availableBy(JsonNode row, JsonNode cutoff) {
        long boundary = strictTime(cutoff, "availability cutoff");
        JsonNode label = defined(field(row, "label_availability_time"))
                ? field(row, "label_availability_time") : field(row, "resolution_time");
        JsonNode execution = defined(field(row, "execution_availability_time"))
                ? field(row, "execution_availability_time") : field(row, "resolution_time");
        return strictTime(label, text(field(row, "episode_id")) + ".label_availability_time") <= boundary
                && strictTime(execution, text(field(row, "episode_id")) + ".execution_availability_time") <= boundary;
    }

    private static JsonNode fieldOrText(ObjectNode options, String name, String fallback) {
        return defined(field(options, name)) ? cloneNode(field(options, name)) : JSON.textNode(fallback);
    }

    private static ArrayNode toArray(Collection<? extends JsonNode> values) {
        ArrayNode output = array(); values.forEach(value -> output.add(cloneNode(value))); return output;
    }

    private static double selectionScore(JsonNode value) {
        double score = numberJs(value); return Double.isFinite(score) ? score : -1e12;
    }

    private static JsonNode firstNonNullOrNull(JsonNode first, JsonNode second) {
        if (definedNonNull(first)) return cloneNode(first); if (definedNonNull(second)) return cloneNode(second);
        return NullNode.instance;
    }

    public static boolean validateGeneticArtifact(JsonNode run) {
        assertKnownKeys(run, Set.of("schema", "version", "fold_id", "config", "gene_space",
                "training_episode_ids", "population_history", "seed_runs", "evaluated_behavior_aliases",
                "evaluated_k", "evaluation_attempt_k", "chromosome_evaluated_k", "cumulative_k",
                "cumulative_exposure_k", "exposure_head_sha256", "selected_behavior_alias_sha256",
                "selected_seed_count", "seed_stability", "baseline", "neighbours", "selected",
                "content_sha256"), "genetic artifact");
        assertOwnHash(run, schema("genetic"), "genetic artifact");
        if ("AUTHORITATIVE".equals(text(field(field(run, "config"), "mode")))) {
            JsonNode expected = MAPPER.valueToTree(STAT_DEFAULTS.get("seeds"));
            if (!stable(field(field(run, "config"), "seeds")).equals(stable(expected))) {
                throw failure("authoritative GA seed set is not frozen");
            }
        }
        ArrayNode history = array(field(run, "population_history"));
        if (history.isEmpty()) throw failure("genetic population history is missing");
        ArrayNode seedRuns = array(field(run, "seed_runs")); Set<Long> seeds = new HashSet<>();
        seedRuns.forEach(row -> seeds.add(integer(field(row, "seed"), Long.MIN_VALUE)));
        List<Long> orderedSeeds = new ArrayList<>(seeds); orderedSeeds.sort(Long::compareTo);
        if (seeds.size() != 3 || !orderedSeeds.equals(List.of(11L, 23L, 47L))) {
            throw failure("genetic seed inventory must contain exactly the three frozen seeds");
        }
        long selectedSeedCount = integer(field(run, "selected_seed_count"), Long.MIN_VALUE);
        if (selectedSeedCount < 0 || selectedSeedCount > 3) throw failure("genetic selected seed count is invalid");
        boolean selectedNull = field(run, "selected").isNull();
        boolean aliasNull = field(run, "selected_behavior_alias_sha256").isNull();
        if (selectedNull != aliasNull || (!selectedNull && (selectedSeedCount < 2
                || !containsText(field(field(run, "seed_stability"), "stable_aliases"),
                text(field(run, "selected_behavior_alias_sha256")))))) {
            throw failure("genetic selection bypasses the frozen two-seed stability gate");
        }
        if (!selectedNull && !field(field(field(run, "selected"), "fitness"), "feasible").asBoolean(false)) {
            throw failure("genetic selection bypasses the frozen hard-feasibility gate");
        }
        long attemptK = integer(field(run, "evaluation_attempt_k"), Long.MIN_VALUE);
        long evaluatedK = integer(field(run, "evaluated_k"), Long.MIN_VALUE);
        if (attemptK < evaluatedK) throw failure("genetic evaluation-attempt K is invalid");
        if (integer(field(run, "cumulative_exposure_k"), Long.MIN_VALUE) < attemptK) {
            throw failure("genetic cumulative exposure K is invalid");
        }
        ArrayNode aliasesArray = array(field(run, "evaluated_behavior_aliases"));
        if (evaluatedK != aliasesArray.size()) throw failure("genetic evaluated K mismatch");
        if (integer(field(run, "cumulative_k"), Long.MIN_VALUE) < evaluatedK) {
            throw failure("genetic cumulative K is below current K");
        }
        Set<String> aliases = new HashSet<>(); history.forEach(row -> aliases.add(text(field(row, "behavior_alias_sha256"))));
        List<String> actualAliases = new ArrayList<>(aliases); actualAliases.sort(String::compareTo);
        List<String> declaredAliases = new ArrayList<>(); aliasesArray.forEach(value -> declaredAliases.add(jsString(value)));
        declaredAliases.sort(String::compareTo);
        if (!actualAliases.equals(declaredAliases)) {
            throw failure("genetic behavior alias inventory is inconsistent with evaluated history");
        }
        Set<String> attempts = new HashSet<>();
        for (JsonNode row : history) {
            requireHash(field(row, "behavior_sha256"), "population behavior");
            requireHash(field(row, "behavior_alias_sha256"), "population alias");
            String attempt = requireHash(field(row, "evaluation_attempt_sha256"), "population evaluation attempt");
            if (!attempts.add(attempt)) throw failure("genetic evaluation attempt identity is duplicated");
            if (!field(row, "generation").isIntegralNumber() || !field(row, "parent_ids").isArray()) {
                throw failure("population row lineage is incomplete");
            }
        }
        if (attemptK != attempts.size()) throw failure("genetic evaluation-attempt K does not match history");
        return true;
    }

    private static ArrayNode strictValues(JsonNode artifact, String candidateId, Set<String> episodeIds) {
        ArrayNode result = array();
        for (JsonNode episode : array(field(artifact, "episodes"))) {
            if (episodeIds != null && !episodeIds.contains(text(field(episode, "episode_id")))) continue;
            JsonNode value = field(field(episode, "candidate_returns"), candidateId);
            ObjectNode row = object(); row.set("episode_id", cloneNode(field(episode, "episode_id")));
            row.set("asset", cloneNode(field(episode, "asset")));
            row.set("decision_time", cloneNode(field(episode, "decision_time")));
            row.set("resolution_time", cloneNode(field(episode, "resolution_time")));
            row.put("value", finiteNumber(field(value, "net_r"), candidateId + "/" + text(field(episode, "episode_id"))));
            row.put("traded", field(value, "traded").asBoolean(false)); result.add(row);
        }
        if (result.isEmpty()) throw failure("candidate " + candidateId + " has no episodes in scoped artifact");
        return result;
    }

    private static ArrayNode vectorValues(JsonNode artifact, JsonNode inventory, String alias,
            Set<String> episodeIds) {
        Map<String, JsonNode> vectorById = new LinkedHashMap<>();
        for (JsonNode row : array(field(field(inventory, "vectors"), alias))) {
            vectorById.put(text(field(row, "episode_id")), row);
        }
        Set<String> artifactIds = new HashSet<>();
        for (JsonNode episode : array(field(artifact, "episodes"))) artifactIds.add(text(field(episode, "episode_id")));
        ArrayNode result = array();
        for (JsonNode episode : array(field(artifact, "episodes"))) {
            String id = text(field(episode, "episode_id")); if (episodeIds != null && !episodeIds.contains(id)) continue;
            JsonNode value = vectorById.get(id);
            if (value == null) throw failure("vector " + alias + " is missing episode " + id);
            if (!artifactIds.contains(text(field(value, "episode_id")))) {
                throw failure("vector " + alias + " references episode " + text(field(value, "episode_id"))
                        + " outside the artifact");
            }
            ObjectNode row = object(); row.put("episode_id", id); row.set("asset", cloneNode(field(episode, "asset")));
            row.set("decision_time", cloneNode(field(episode, "decision_time")));
            row.set("resolution_time", cloneNode(field(episode, "resolution_time")));
            row.put("value", finiteNumber(field(value, "net_r"), alias + "/" + id));
            row.put("traded", field(value, "traded").asBoolean(false));
            row.put("eligible", !field(value, "eligible").isBoolean() || field(value, "eligible").asBoolean());
            result.add(row);
        }
        if (result.isEmpty()) throw failure("behavior alias " + alias + " has no episodes in scoped artifact");
        return result;
    }

    private static ArrayNode centeredRows(JsonNode sourceRows, Map<String, String> clusters) {
        Map<String, List<JsonNode>> grouped = new LinkedHashMap<>();
        for (JsonNode row : sourceRows) {
            String id = text(field(row, "episode_id")), cluster = clusters.get(id);
            if (cluster == null) throw failure("max-statistic episode " + id + " has no market cluster identity");
            grouped.computeIfAbsent(cluster, ignored -> new ArrayList<>()).add(row);
        }
        List<ObjectNode> output = new ArrayList<>();
        for (Map.Entry<String, List<JsonNode>> entry : grouped.entrySet()) {
            List<JsonNode> values = entry.getValue(); ObjectNode row = object(); row.put("episode_id", entry.getKey());
            row.put("decision_time", values.stream().map(value -> text(field(value, "decision_time"))).sorted()
                    .findFirst().orElse(""));
            row.put("value", values.stream().mapToDouble(value -> numberJs(field(value, "value"))).average().orElse(0));
            row.put("eligible", values.stream().allMatch(value -> !field(value, "eligible").isBoolean()
                    || field(value, "eligible").asBoolean()));
            row.put("traded", values.stream().anyMatch(value -> field(value, "traded").asBoolean(false)));
            output.add(row);
        }
        output.sort(Comparator.comparingLong((ObjectNode row) -> strictTime(field(row, "decision_time"), "timestamp"))
                .thenComparing(row -> text(field(row, "episode_id"))));
        ArrayNode result = array(); output.forEach(result::add); return result;
    }

    private static ObjectNode centeredMaxStatistic(JsonNode artifact, JsonNode head, JsonNode rawEpisodeIds,
            long iterations, long seed, JsonNode vectorInventory, JsonNode selectedRows, String selectedAlias) {
        validateExposureHead(head); Set<String> wanted = textSet(array(rawEpisodeIds));
        Map<String, String> candidateByBehavior = new LinkedHashMap<>();
        for (JsonNode row : array(field(artifact, "candidates"))) {
            candidateByBehavior.put(text(field(row, "behavior_sha256")), text(field(row, "candidate_id")));
        }
        Map<String, String> clusters = marketEpisodeClusters(field(artifact, "episodes"));
        List<String> aliases = new ArrayList<>(); List<ArrayNode> rawVectors = new ArrayList<>(), vectors = new ArrayList<>();
        for (JsonNode entry : array(field(head, "entries"))) {
            String alias = text(field(entry, "behavior_sha256")); aliases.add(alias); ArrayNode values;
            if (vectorInventory != null && vectorInventory.isObject()) {
                values = vectorValues(artifact, vectorInventory, alias, wanted);
            } else {
                String candidateId = candidateByBehavior.get(alias);
                if (candidateId == null) throw failure("max-statistic vector missing for cumulative alias " + alias);
                values = strictValues(artifact, candidateId, wanted);
            }
            rawVectors.add(values); vectors.add(centeredRows(values, clusters));
        }
        ArrayNode canonicalRows = vectors.isEmpty() ? array() : vectors.getFirst();
        List<String> canonicalIds = new ArrayList<>(); canonicalRows.forEach(row -> canonicalIds.add(text(field(row, "episode_id"))));
        for (ArrayNode rows : vectors) {
            List<String> ids = new ArrayList<>(); rows.forEach(row -> ids.add(text(field(row, "episode_id"))));
            if (!ids.equals(canonicalIds)) {
                throw failure("max-statistic candidate vectors are not aligned to canonical market-cluster chronology");
            }
        }
        Map<String, JsonNode> selectedById = null;
        if (selectedRows != null && selectedRows.isArray()) {
            selectedById = new LinkedHashMap<>();
            for (JsonNode row : centeredRows(selectedRows, clusters)) selectedById.put(text(field(row, "episode_id")), row);
            List<String> selectedIds = new ArrayList<>(selectedById.keySet()); selectedIds.sort(String::compareTo);
            List<String> expected = new ArrayList<>(canonicalIds); expected.sort(String::compareTo);
            if (!selectedIds.equals(expected)) {
                throw failure("max-statistic selected procedure vector is incomplete or misaligned");
            }
        }
        ArrayNode selectedVector = null;
        if (selectedById != null) {
            selectedVector = array(); for (String id : canonicalIds) selectedVector.add(cloneNode(selectedById.get(id)));
        } else if (selectedAlias != null) {
            int selectedIndex = aliases.indexOf(selectedAlias); if (selectedIndex >= 0) selectedVector = vectors.get(selectedIndex);
        }
        List<Integer> shared = new ArrayList<>();
        for (int index = 0; index < canonicalRows.size(); index++) {
            boolean eligible = true;
            for (ArrayNode rows : vectors) if (field(rows.get(index), "eligible").isBoolean()
                    && !field(rows.get(index), "eligible").asBoolean()) eligible = false;
            if (selectedVector != null && field(selectedVector.get(index), "eligible").isBoolean()
                    && !field(selectedVector.get(index), "eligible").asBoolean()) eligible = false;
            if (eligible) shared.add(index);
        }
        if (shared.isEmpty()) throw failure("max-statistic candidate vectors have no shared post-discovery eligible episodes");
        List<List<Double>> matrix = new ArrayList<>(), centered = new ArrayList<>();
        for (ArrayNode rows : vectors) {
            List<Double> values = new ArrayList<>(); for (int index : shared) values.add(numberJs(field(rows.get(index), "value")));
            matrix.add(values); double average = mean(values);
            centered.add(values.stream().map(value -> value - average).toList());
        }
        List<Double> observedValues = selectedVector == null ? matrix.getFirst() : new ArrayList<>();
        if (selectedVector != null) for (int index : shared) observedValues.add(numberJs(field(selectedVector.get(index), "value")));
        double observed = mean(observedValues); if (!Double.isFinite(observed)) {
            throw failure("max-statistic selected procedure has no finite observations");
        }
        XorShift32 random = new XorShift32(seed); long exceed = 0; int block = Math.max(1,
                (int) Math.ceil(Math.sqrt(shared.size())));
        for (long iteration = 0; iteration < iterations; iteration++) {
            List<Integer> sampled = new ArrayList<>();
            while (sampled.size() < shared.size()) {
                int start = Math.min(shared.size() - 1, (int) Math.floor(random.next() * shared.size()));
                for (int offset = 0; offset < block && sampled.size() < shared.size(); offset++) {
                    sampled.add((start + offset) % shared.size());
                }
            }
            double statistic = -Double.MAX_VALUE;
            for (List<Double> values : centered) {
                List<Double> sample = sampled.stream().map(values::get).toList(); statistic = Math.max(statistic, mean(sample));
            }
            if (statistic >= observed) exceed++;
        }
        ObjectNode result = object(); result.put("status", "PASS");
        result.put("p_value", (double) (exceed + 1) / (iterations + 1)); result.put("statistic", observed);
        result.put("observed_selected_procedure", true);
        if (selectedAlias == null) result.putNull("selected_alias"); else result.put("selected_alias", selectedAlias);
        result.put("selection_adjustment", "CUMULATIVE_MAX_NULL_AGAINST_SELECTED_PROCEDURE");
        result.put("iterations", iterations); result.put("candidate_count", vectors.size());
        result.put("episode_count", shared.size()); result.set("cumulative_k", cloneNode(field(head, "cumulative_k")));
        result.put("synchronized", true); ArrayNode mask = array();
        for (int index : shared) mask.add(text(field(canonicalRows.get(index), "episode_id")));
        result.put("shared_episode_mask", hash(mask)); result.put("centered", true); ArrayNode inventory = array();
        for (int vectorIndex = 0; vectorIndex < rawVectors.size(); vectorIndex++) {
            ObjectNode item = object(); item.put("alias", aliases.get(vectorIndex)); ArrayNode rows = array();
            for (JsonNode source : rawVectors.get(vectorIndex)) {
                ObjectNode row = object(); row.set("episode_id", cloneNode(field(source, "episode_id")));
                row.put("value", numberJs(field(source, "value"))); row.put("traded", field(source, "traded").asBoolean(false));
                row.put("eligible", !field(source, "eligible").isBoolean() || field(source, "eligible").asBoolean()); rows.add(row);
            }
            item.set("rows", rows); inventory.add(item);
        }
        result.put("vector_inventory_sha256", hash(inventory)); return result;
    }

    /** Published probabilistic/deflated Sharpe calculation, including the fail-closed serial-dependence gate. */
    public static ObjectNode deflatedSharpe(JsonNode rows, double effectiveTrials) {
        ArrayNode input = array(rows);
        if (input.size() < 3) return null;
        List<Double> values = new ArrayList<>();
        input.forEach(row -> values.add(numberJs(field(row, "value"))));
        int n = values.size();
        double autocorrelation = lagOneAutocorrelation(values);
        double autocorrelationThreshold = .2;
        if (n >= 8 && Math.abs(autocorrelation) >= autocorrelationThreshold) {
            ObjectNode unsupported = object();
            unsupported.put("supported", false);
            unsupported.put("method", "PUBLISHED_PSR_REQUIRES_EFFECTIVE_SAMPLE_SIZE");
            unsupported.put("reason", "MATERIAL_AUTOCORRELATION_UNCORRECTED");
            unsupported.put("autocorrelation_lag1", autocorrelation);
            unsupported.put("autocorrelation_threshold", autocorrelationThreshold);
            unsupported.put("sampling_unit", "independent_market_episode");
            return unsupported;
        }
        double mean = mean(values);
        double varianceSum = 0;
        for (double value : values) varianceSum += Math.pow(value - mean, 2);
        double variance = varianceSum / (n - 1);
        double sd = Math.sqrt(variance);
        if (!(sd > 0)) return null;
        double third = 0, fourth = 0;
        for (double value : values) {
            double central = value - mean;
            third += Math.pow(central, 3); fourth += Math.pow(central, 4);
        }
        double skew = (third / n) / Math.pow(sd, 3);
        double excessKurtosis = (fourth / n) / Math.pow(sd, 4) - 3;
        double sharpe = mean / sd;
        double trials = Math.max(1, effectiveTrials);
        double eulerGamma = .5772156649015329;
        double z1 = trials > 1 ? normalQuantile(1 - 1 / trials) : 0;
        double z2 = trials > 1 ? normalQuantile(1 - 1 / (trials * Math.E)) : 0;
        double expectedMax = ((1 - eulerGamma) * z1 + eulerGamma * z2) / Math.sqrt(n);
        double kurtosis = excessKurtosis + 3;
        double denominator = Math.sqrt(Math.max(1e-12,
                1 - skew * sharpe + ((kurtosis - 1) / 4) * Math.pow(sharpe, 2)));
        double psrZ = (sharpe - expectedMax) * Math.sqrt(n - 1) / denominator;
        ObjectNode result = object();
        result.put("supported", true);
        result.put("method", "PUBLISHED_PSR_WITH_EXPECTED_MAXIMUM_SHARPE");
        result.put("probability", normalCdf(psrZ)); result.put("sharpe", sharpe);
        result.put("null_bound_sharpe", expectedMax); result.put("expected_max_sharpe", expectedMax);
        result.put("psr_denominator", denominator); result.put("standard_error", denominator / Math.sqrt(n - 1));
        result.put("skew", skew); result.put("kurtosis", kurtosis); result.put("excess_kurtosis", excessKurtosis);
        result.put("effective_trials", trials); result.put("sampling_unit", "independent_market_episode");
        result.put("bound_distribution", "EXPECTED_MAXIMUM_NORMAL_SHARPE_NULL");
        result.put("finite_sample_denominator", "SQRT_N_MINUS_1");
        result.put("autocorrelation_lag1", autocorrelation);
        result.put("autocorrelation_threshold", autocorrelationThreshold);
        return result;
    }

    public static ObjectNode connectedPlateau(JsonNode ga, String selectedAlias) {
        return connectedPlateau(ga, selectedAlias,
                ((Number) STAT_DEFAULTS.get("minPlateau")).intValue(),
                ((Number) STAT_DEFAULTS.get("minNeighbourFraction")).doubleValue());
    }

    public static ObjectNode connectedPlateau(JsonNode ga, String selectedAlias,
            int minSize, double minNeighbourFraction) {
        if (!ga.isObject() || !schema("genetic").equals(text(field(ga, "schema")))
                || !selectedAlias.equals(text(field(ga, "selected_behavior_alias_sha256")))) {
            ObjectNode mismatch = object(); mismatch.put("pass", false);
            mismatch.put("reason", "SELECTED_GENETIC_ARTIFACT_MISMATCH");
            mismatch.put("size", 0); mismatch.put("neighbour_fraction", 0); return mismatch;
        }
        JsonNode selected = field(field(ga, "selected"), "chromosome");
        String selectedBehavior = selected.isObject() ? hash(selected) : null;
        Map<String, JsonNode> uniqueNeighbours = new LinkedHashMap<>();
        if (selected.isObject()) for (JsonNode row : array(field(ga, "neighbours"))) {
            JsonNode chromosome = field(row, "chromosome");
            if (!chromosome.isObject() || chromosome.size() != selected.size()) continue;
            int differences = 0;
            var names = selected.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!stable(field(selected, name)).equals(stable(field(chromosome, name)))) differences++;
            }
            if (differences == 1) uniqueNeighbours.putIfAbsent(text(field(row, "behavior_alias_sha256")), row);
        }
        Set<String> profitable = new HashSet<>();
        if (selectedBehavior != null && field(field(field(ga, "selected"), "fitness"), "feasible").asBoolean(false)
                && numberJs(field(field(field(field(ga, "selected"), "fitness"), "metrics"), "expectancy_r")) > 0) {
            profitable.add(selectedAlias);
        }
        Set<String> profitableNeighbours = new HashSet<>();
        for (Map.Entry<String, JsonNode> entry : uniqueNeighbours.entrySet()) {
            JsonNode row = entry.getValue();
            if (field(row, "feasible").asBoolean(false) && numberJs(field(row, "expectancy_r")) > 0) {
                profitable.add(entry.getKey()); profitableNeighbours.add(entry.getKey());
            }
        }
        int neighbourCount = uniqueNeighbours.size();
        double fraction = neighbourCount == 0 ? 0 : (double) profitableNeighbours.size() / neighbourCount;
        ObjectNode result = object();
        result.put("pass", selectedBehavior != null && profitable.contains(selectedAlias)
                && profitable.size() >= minSize && neighbourCount > 0 && fraction >= minNeighbourFraction);
        result.put("connected_profitable_plateau_size", profitable.size());
        result.put("profitable_neighbour_fraction", fraction); result.put("selected_alias", selectedAlias);
        return result;
    }

    private static final List<String> STRESS_SCENARIOS = List.of("DOUBLED_COST", "DELAYED_ENTRY",
            "ADVERSE_COLLISION", "GAP", "LIQUIDITY", "CAPACITY", "OUTAGE", "FUNDING", "EXPIRY",
            "LIQUIDATION", "LEAVE_ONE_ASSET", "LEAVE_ONE_REGIME", "LEAVE_ONE_CONTEXT");

    public static ObjectNode makeStressDecision(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        String source = requireHash(field(options, "sourceArtifactSha256"), "stress.source_artifact_sha256");
        String selectedId = text(field(options, "selectedCandidateId"));
        if (selectedId.isEmpty()) throw failure("stress.selected_candidate_id is required");
        boolean pass = field(options, "pass").asBoolean(false);
        ArrayNode inventory;
        if (field(options, "scenarios").isArray()) inventory = array(field(options, "scenarios")).deepCopy();
        else {
            inventory = array();
            for (String id : STRESS_SCENARIOS) {
                ObjectNode digestInput = object(); digestInput.put("id", id); digestInput.put("pass", pass);
                ObjectNode row = object(); row.put("id", id); row.put("pass", pass); row.put("digest", hash(digestInput));
                inventory.add(row);
            }
        }
        List<String> ids = new ArrayList<>(); inventory.forEach(row -> ids.add(text(field(row, "id"))));
        ids.sort(String::compareTo); List<String> expected = new ArrayList<>(STRESS_SCENARIOS); expected.sort(String::compareTo);
        if (!ids.equals(expected)) throw failure("stress scenario inventory is incomplete");
        ObjectNode value = object(); value.put("schema", schema("stress")); value.put("version", 1);
        value.put("pass", pass); value.put("provenance", "AUTHORITATIVE_RECOMPUTED");
        value.set("lineage_sha256", cloneNode(field(options, "lineage_sha256")));
        value.put("source_artifact_sha256", source); value.put("selected_candidate_id", selectedId);
        value.set("scenarios", inventory); value.put("scenario_inventory_sha256", hash(inventory));
        ObjectNode result = withHash(value); validateContractSchema(result); return result;
    }

    public static ObjectNode makePortfolioDecision(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        ArrayNode decisions = array(field(options, "assetDecisions"));
        ArrayNode increments = array(field(options, "returnIncrements"));
        if (decisions.isEmpty() || increments.isEmpty()) {
            throw failure("portfolio decision requires recomputed asset decisions and aligned return increments");
        }
        JsonNode explicitSource = field(options, "sourceArtifactSha256");
        JsonNode sourceNode = truthy(explicitSource) ? explicitSource : field(field(options, "artifact"), "content_sha256");
        String source = requireHash(sourceNode, "portfolio.source_artifact_sha256");
        ObjectNode value = object(); value.put("schema", schema("portfolio")); value.put("version", 1);
        value.put("pass", field(options, "pass").asBoolean(false));
        value.put("provenance", "AUTHORITATIVE_RECOMPUTED");
        value.set("lineage_sha256", cloneNode(field(options, "lineage_sha256")));
        value.put("source_artifact_sha256", source); value.set("asset_decisions", decisions.deepCopy());
        value.set("return_increments", increments.deepCopy()); value.put("asset_decisions_sha256", hash(decisions));
        value.put("return_increments_sha256", hash(increments));
        if (truthy(field(options, "riskDigest"))) value.put("risk_digest_sha256", text(field(options, "riskDigest")));
        else {
            ObjectNode digestInput = object(); digestInput.set("assetDecisions", decisions.deepCopy());
            digestInput.set("returnIncrements", increments.deepCopy()); value.put("risk_digest_sha256", hash(digestInput));
        }
        ObjectNode result = withHash(value); validateContractSchema(result); return result;
    }

    public static ObjectNode aggregateAssetDecision(JsonNode rows) {
        return aggregateAssetDecision(rows, object());
    }

    public static ObjectNode aggregateAssetDecision(JsonNode rawRows, JsonNode rawRequired) {
        ArrayNode rows = array(rawRows);
        ObjectNode required = objectOrEmpty(rawRequired);
        String mode = truthy(field(required, "mode"))
                ? jsString(field(required, "mode")).toUpperCase(Locale.ROOT) : "FIXTURE";
        JsonNode configuredConstraints = field(required, "constraints");
        boolean authoritativePolicy = !"FIXTURE".equals(mode)
                || truthy(field(configuredConstraints, "violationScales"))
                || truthy(field(configuredConstraints, "violation_scales"));
        JsonNode constraints = truthy(configuredConstraints) ? configuredConstraints : required;
        if (authoritativePolicy) {
            requireFrozenHardPolicy(constraints, "authoritative asset hard acceptance policy");
        }
        if (rows.isEmpty()) {
            ObjectNode summary = object(); summary.put("fold_count", 0); summary.put("positive_folds", 0);
            summary.put("failed_folds", 0); summary.set("decision_digests", array());
            ObjectNode missing = object(); missing.put("asset", "btc"); missing.put("pass", false);
            missing.put("reason", "MISSING_ASSET_FOLDS"); missing.set("fold_summary", summary);
            return makeAssetDecision(missing);
        }

        List<ObjectNode> values = new ArrayList<>();
        for (JsonNode row : rows) for (JsonNode rawValue : array(field(row, "selected_return_vector"))) {
            ObjectNode value = objectOrEmpty(rawValue).deepCopy();
            value.put("value", numberJs(field(rawValue, "net_r"))); values.add(value);
        }
        values.sort(Comparator.comparingLong((ObjectNode row) -> strictTime(field(row, "decision_time"), "timestamp"))
                .thenComparing(row -> text(field(row, "episode_id"))));

        int positiveFolds = 0;
        for (JsonNode row : rows) if (truthy(field(row, "metrics"))
                && numberJs(field(field(row, "metrics"), "expectancy_r")) > 0
                && field(field(row, "stress"), "pass").isBoolean()
                && field(field(row, "stress"), "pass").asBoolean()) positiveFolds++;
        JsonNode aggregateMetrics = aggregateSelectedOosMetrics(values, rows);
        List<ObjectNode> tradeValues = values.stream()
                .filter(value -> field(value, "traded").isBoolean() && field(value, "traded").asBoolean()).toList();

        Map<String, List<ObjectNode>> yearMap = new LinkedHashMap<>();
        for (ObjectNode value : tradeValues) {
            String decision = text(field(value, "decision_time"));
            String year = decision.substring(0, Math.min(4, decision.length()));
            yearMap.computeIfAbsent(year, ignored -> new ArrayList<>()).add(value);
        }
        double iterations = truthy(field(required, "bootstrapIterations"))
                ? numberJs(field(required, "bootstrapIterations")) : 256;
        double seed = truthy(field(required, "seed")) ? numberJs(field(required, "seed")) : 11;
        ArrayNode yearStats = array();
        for (Map.Entry<String, List<ObjectNode>> entry : yearMap.entrySet()) {
            String year = entry.getKey(); List<ObjectNode> yearRows = entry.getValue();
            ObjectNode stat = object(); stat.put("year", year);
            Double bootstrap = p20(blockBootstrap(yearRows, iterations, seed + numberFromString(year), null));
            if (bootstrap == null) stat.putNull("bootstrap_p20"); else stat.put("bootstrap_p20", bootstrap);
            stat.put("expectancy_r", mean(yearRows.stream().map(row -> numberJs(field(row, "value"))).toList()));
            stat.put("trade_count", yearRows.size());
            long opportunityCount = values.stream().filter(value -> {
                String decision = text(field(value, "decision_time"));
                return decision.substring(0, Math.min(4, decision.length())).equals(year);
            }).count();
            stat.put("opportunity_count", opportunityCount); yearStats.add(stat);
        }

        double halfLifeMonths = definedNonNull(field(required, "halfLifeMonths"))
                ? numberJs(field(required, "halfLifeMonths"))
                : ((Number) STAT_DEFAULTS.get("halfLifeMonths")).doubleValue();
        double recentCutoff = values.isEmpty() ? Double.POSITIVE_INFINITY
                : strictTime(field(values.getLast(), "decision_time"), "timestamp")
                - halfLifeMonths * 30.4375 * 86_400_000d;
        List<ObjectNode> recent = tradeValues.stream().filter(value ->
                strictTime(field(value, "decision_time"), "timestamp") >= recentCutoff).toList();
        Double recentP20 = recent.isEmpty() ? null : p20(blockBootstrap(recent, iterations, seed + 101, null));

        ObjectNode aggregatePolicy = objectOrEmpty(constraints).deepCopy();
        double minEpisodes = firstNullishNumber(field(constraints, "minEpisodes"),
                field(required, "minEpisodes"), ((Number) STAT_DEFAULTS.get("minEpisodes")).doubleValue());
        double minExpectancy = firstNullishNumber(field(constraints, "minExpectancy"),
                field(required, "minExpectancy"), 0);
        aggregatePolicy.put("minEpisodes", minEpisodes); aggregatePolicy.put("minExpectancy", minExpectancy);
        boolean aggregateHard = !aggregateMetrics.isNull()
                && hardFeasible(aggregateMetrics, aggregatePolicy).path("feasible").asBoolean(false);
        boolean requiresProcedureEvidence = authoritativePolicy;
        for (JsonNode row : rows) if (defined(field(row, "procedure_validation")) || defined(field(row, "pbo"))) {
            requiresProcedureEvidence = true; break;
        }

        boolean procedureValidation = !requiresProcedureEvidence;
        boolean pbo = !requiresProcedureEvidence;
        if (requiresProcedureEvidence) {
            procedureValidation = true; pbo = true;
            for (JsonNode row : rows) {
                if (!(field(field(row, "procedure_validation"), "pass").isBoolean()
                        && field(field(row, "procedure_validation"), "pass").asBoolean())) {
                    procedureValidation = false;
                }
                JsonNode rowPbo = field(row, "pbo");
                if (!(field(row, "pbo_pass").isBoolean() && field(row, "pbo_pass").asBoolean()
                        && "OUTER_TRAIN_ONLY".equals(text(field(rowPbo, "source_phase")))
                        && field(rowPbo, "outer_oos_bound").isBoolean()
                        && !field(rowPbo, "outer_oos_bound").asBoolean()
                        && numberJs(field(rowPbo, "candidate_count")) >= 2)) pbo = false;
            }
        }
        double minPositiveFolds = definedNonNull(field(required, "minPositiveFolds"))
                ? numberJs(field(required, "minPositiveFolds"))
                : ((Number) STAT_DEFAULTS.get("minPositiveFolds")).doubleValue();
        boolean earlierBlocks = true;
        for (int index = 0; index < Math.max(0, yearStats.size() - 1); index++) {
            if (numberJs(field(yearStats.get(index), "bootstrap_p20")) < -.1) earlierBlocks = false;
        }
        double minTradesPerYear = definedNonNull(field(required, "minTradesPerYear"))
                ? numberJs(field(required, "minTradesPerYear"))
                : ((Number) STAT_DEFAULTS.get("minTradesPerYear")).doubleValue();
        double minPositiveYears = defined(field(required, "minPositiveYears"))
                ? numberJs(field(required, "minPositiveYears")) : Double.NaN;
        long positiveYears = 0;
        for (JsonNode stat : yearStats) if (numberJs(field(stat, "trade_count")) >= minTradesPerYear
                && numberJs(field(stat, "expectancy_r")) > 0) positiveYears++;
        boolean stressSurvival = true;
        for (JsonNode row : rows) if (!(field(field(row, "stress"), "pass").isBoolean()
                && field(field(row, "stress"), "pass").asBoolean())) stressSurvival = false;

        ObjectNode gates = object(); gates.put("procedure_validation", procedureValidation); gates.put("pbo", pbo);
        gates.put("positive_outer_folds", positiveFolds >= minPositiveFolds);
        gates.put("recent_oos_positive", recentP20 != null && recentP20 > 0);
        gates.put("earlier_blocks", earlierBlocks); gates.put("positive_years", positiveYears >= minPositiveYears);
        gates.put("stress_survival", stressSurvival); gates.put("hard_metrics", aggregateHard);
        boolean pass = true; for (JsonNode gate : gates) if (!gate.asBoolean(false)) { pass = false; break; }

        ObjectNode result = objectOrEmpty(rows.get(rows.size() - 1)).deepCopy();
        result.set("metrics", cloneNode(aggregateMetrics)); result.put("pass", pass); result.set("asset_gates", gates);
        ObjectNode summary = object(); summary.put("fold_count", rows.size()); summary.put("positive_folds", positiveFolds);
        summary.put("failed_folds", rows.size() - positiveFolds); ArrayNode digests = array();
        rows.forEach(row -> digests.add(cloneNode(field(row, "content_sha256"))));
        summary.set("decision_digests", digests); summary.set("year_stats", yearStats);
        if (recentP20 == null) summary.putNull("recent_bootstrap_p20"); else summary.put("recent_bootstrap_p20", recentP20);
        result.set("fold_summary", summary); return makeAssetDecision(result);
    }

    private static ObjectNode makeAssetDecision(ObjectNode value) {
        ObjectNode result = value.deepCopy(); result.put("decision_type", "ASSET");
        result.put("provenance", "AUTHORITATIVE_RECOMPUTED"); return withHash(result);
    }

    private static JsonNode aggregateSelectedOosMetrics(List<ObjectNode> selectedRows, JsonNode assetDecisions) {
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode raw : selectedRows) {
            ObjectNode row = raw.deepCopy(); double value = numberJs(field(raw, "net_r"));
            if (Double.isFinite(value)) { row.put("value", value); rows.add(row); }
        }
        rows.sort(Comparator.comparingLong((ObjectNode row) -> strictTime(field(row, "decision_time"), "timestamp"))
                .thenComparing(row -> jsString(field(row, "asset")))
                .thenComparing(row -> text(field(row, "episode_id"))));
        if (rows.isEmpty()) return NullNode.instance;
        List<Double> values = rows.stream().map(row -> numberJs(field(row, "value"))).toList();
        List<ObjectNode> tradeRows = rows.stream().filter(row -> field(row, "traded").isBoolean()
                && field(row, "traded").asBoolean()).toList();
        List<Double> tradeValues = tradeRows.stream().map(row -> numberJs(field(row, "value"))).toList();
        double positive = tradeValues.stream().filter(value -> value > 0).mapToDouble(Double::doubleValue).sum();
        double negative = tradeValues.stream().filter(value -> value < 0).mapToDouble(Math::abs).sum();
        List<JsonNode> metricRows = new ArrayList<>();
        for (JsonNode row : assetDecisions) {
            JsonNode metrics = field(row, "metrics"); JsonNode cost = field(metrics, "cost_r");
            if (truthy(metrics) && defined(cost) && Double.isFinite(numberJs(cost))) metricRows.add(row);
        }
        double weightedTradeCount = 0;
        for (JsonNode row : metricRows) weightedTradeCount += Math.max(0, metricTradeCount(row));
        Double cost = null;
        if (!metricRows.isEmpty()) {
            double total = 0;
            for (JsonNode row : metricRows) total += numberJs(field(field(row, "metrics"), "cost_r"))
                    * Math.max(0, metricTradeCount(row));
            cost = total / Math.max(1, weightedTradeCount);
        }
        List<Double> coverageValues = new ArrayList<>(); List<Boolean> capacityValues = new ArrayList<>();
        double complexity = 0;
        for (JsonNode row : metricRows) {
            JsonNode metrics = field(row, "metrics"); JsonNode coverage = field(metrics, "coverage_fraction");
            if (defined(coverage) && Double.isFinite(numberJs(coverage))) coverageValues.add(numberJs(coverage));
            JsonNode capacity = field(metrics, "capacity_pass"); capacityValues.add(capacity.isBoolean() && capacity.asBoolean());
            double candidateComplexity = definedNonNull(field(metrics, "complexity"))
                    ? numberJs(field(metrics, "complexity")) : 0;
            if (Double.isFinite(candidateComplexity)) complexity = Math.max(complexity, candidateComplexity);
        }
        int tradeCount = tradeRows.size(); ObjectNode metrics = object();
        metrics.put("sample_count", tradeCount); metrics.put("traded_count", tradeCount);
        metrics.put("opportunity_count", rows.size()); metrics.put("opportunity_expectancy_r", mean(values));
        putNullable(metrics, "opportunity_bootstrap_p20", p20(blockBootstrap(rows, 512, 11, null)));
        metrics.put("expectancy_r", tradeValues.isEmpty() ? 0 : mean(tradeValues));
        putNullable(metrics, "bootstrap_p20", p20(blockBootstrap(tradeRows, 512, 11, null)));
        metrics.putNull("weighted_bootstrap_p20"); metrics.put("cost_r", cost == null ? 1e12 : cost);
        metrics.put("coverage_fraction", coverageValues.isEmpty() ? 0
                : coverageValues.stream().mapToDouble(Double::doubleValue).min().orElse(0));
        metrics.put("capacity_pass", !capacityValues.isEmpty() && capacityValues.stream().allMatch(Boolean::booleanValue));
        metrics.put("max_drawdown_r", tradeValues.isEmpty() ? 0 : drawdown(tradeValues));
        metrics.put("profit_factor", negative > 0 ? positive / negative : positive > 0 ? Double.MAX_VALUE : 0);
        metrics.put("drawdown_r", drawdown(tradeValues)); metrics.put("turnover", tradeCount);
        metrics.put("complexity", complexity); ArrayNode episodeReturns = array();
        for (ObjectNode row : rows) {
            ObjectNode episode = object(); episode.set("episode_id", cloneNode(field(row, "episode_id")));
            episode.set("decision_time", cloneNode(field(row, "decision_time")));
            episode.set("asset", cloneNode(field(row, "asset"))); episode.put("net_r", numberJs(field(row, "value")));
            episode.put("traded", field(row, "traded").isBoolean() && field(row, "traded").asBoolean());
            episodeReturns.add(episode);
        }
        metrics.set("episode_returns", episodeReturns); return metrics;
    }

    private static double metricTradeCount(JsonNode row) {
        JsonNode declared = field(field(row, "metrics"), "traded_count");
        if (definedNonNull(declared)) return numberJs(declared);
        long count = 0; for (JsonNode value : array(field(row, "selected_return_vector")))
            if (field(value, "traded").isBoolean() && field(value, "traded").asBoolean()) count++;
        return count;
    }

    private static List<Double> blockBootstrap(List<ObjectNode> rows, double iterations, double seed,
            List<Double> weights) {
        if (rows.isEmpty()) return List.of();
        XorShift32 random = new XorShift32(seed); int block = Math.max(1, (int) Math.ceil(Math.sqrt(rows.size())));
        List<Double> normalized = weights == null
                ? java.util.Collections.nCopies(rows.size(), 1d / rows.size()) : weights;
        List<Double> output = new ArrayList<>();
        for (int iteration = 0; iteration < iterations; iteration++) {
            List<Double> sample = new ArrayList<>();
            while (sample.size() < rows.size()) {
                double target = random.next(); int start = normalized.size() - 1; double cumulative = 0;
                for (int index = 0; index < normalized.size(); index++) {
                    cumulative += normalized.get(index);
                    if (target <= cumulative) { start = index; break; }
                }
                for (int offset = 0; offset < block && sample.size() < rows.size(); offset++) {
                    sample.add(numberJs(field(rows.get((start + offset) % rows.size()), "value")));
                }
            }
            output.add(mean(sample));
        }
        return output;
    }

    private static Double p20(List<Double> values) {
        if (values.isEmpty()) return null; List<Double> sorted = new ArrayList<>(values); sorted.sort(Double::compareTo);
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * .2) - 1));
    }

    private static void putNullable(ObjectNode target, String name, Double value) {
        if (value == null) target.putNull(name); else target.put(name, value);
    }

    private static double firstNullishNumber(JsonNode first, JsonNode second, double fallback) {
        if (definedNonNull(first)) return numberJs(first); if (definedNonNull(second)) return numberJs(second);
        return fallback;
    }

    private static double numberFromString(String value) {
        try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return Double.NaN; }
    }

    private static final class XorShift32 {
        private int state;
        private XorShift32(double seed) {
            int normalized = Double.isFinite(seed) ? (int) (long) seed : 0; state = normalized == 0 ? 1 : normalized;
        }
        private XorShift32(double seed, long initialState) {
            long raw = initialState; int normalized = (int) raw;
            state = normalized == 0 ? 1 : normalized;
        }
        private double next() {
            state ^= state << 13; state ^= state >>> 17; state ^= state << 5;
            return Integer.toUnsignedLong(state) / 4294967296d;
        }
        private long state() { return Integer.toUnsignedLong(state); }
    }

    private static boolean validateNullArtifact(JsonNode value) {
        assertOwnHash(value, schema("nulls"), "null control artifact");
        JsonNode tests = field(value, "tests");
        if (!field(value, "pass").isBoolean() || !tests.isArray() || tests.size() < 4) {
            throw failure("null control artifact is incomplete");
        }
        for (JsonNode row : tests) {
            double pValue = numberJs(field(row, "p_value"));
            if (!(pValue >= 0 && pValue <= 1) || !field(row, "pass").isBoolean()
                    || !truthy(field(row, "method"))) {
                throw failure("null control test is malformed");
            }
            for (String name : List.of("iterations", "iterations_planned", "evaluation_attempt_k",
                    "worker_evaluation_count", "worker_count", "peak_in_flight", "batch_count",
                    "cache_hit_count", "disk_cache_hit_count", "checkpointed_iterations")) {
                if (defined(field(row, name)) && integerFromNumber(field(row, name), -1) < 0) {
                    throw failure("null control workload field " + name + " is invalid");
                }
            }
            if (defined(field(row, "iterations_planned"))) {
                long completed = integerFromNumber(field(row, "iterations"), -1);
                long planned = integerFromNumber(field(row, "iterations_planned"), -1);
                double lower = numberJs(field(row, "p_value_lower_bound"));
                double upper = numberJs(field(row, "p_value_upper_bound"));
                double alpha = numberJs(field(value, "alpha"));
                boolean pass = field(row, "pass").asBoolean();
                if (completed > planned || !(lower >= 0 && lower <= upper && upper <= 1)
                        || completed < planned && pass != (upper <= alpha)
                        || completed == planned && pass != (pValue <= alpha)) {
                    throw failure("null control sequential decision envelope is invalid");
                }
            }
            if (defined(field(row, "worker_slots_used"))) {
                JsonNode slots = field(row, "worker_slots_used");
                if (!slots.isArray()) throw failure("null control worker slot accounting is invalid");
                for (JsonNode slot : slots) if (integerFromNumber(slot, -1) < 0) {
                    throw failure("null control worker slot accounting is invalid");
                }
            }
        }
        validateRegisteredSchema(value);
        return true;
    }

    public static ObjectNode makeNullReplayArtifact(ObjectNode args) {
        ObjectNode options = args == null ? object() : args; JsonNode artifact = field(options, "artifact");
        String method = text(field(options, "method"));
        if (!artifact.isObject() || !Set.of("block_permuted_labels", "timestamp_shifted_outcomes",
                "frequency_matched_random_intents", "winners_curse_selection").contains(method)) {
            throw failure("unknown null replay method");
        }
        ObjectNode replayed = objectOrEmpty(artifact).deepCopy(); ArrayNode episodes = array();
        JsonNode returns = field(options, "candidateReturns");
        for (JsonNode raw : array(field(artifact, "episodes"))) {
            ObjectNode row = objectOrEmpty(raw).deepCopy(); JsonNode replacement = field(returns, text(field(raw, "episode_id")));
            if (truthy(replacement)) row.set("candidate_returns", cloneNode(replacement)); episodes.add(row);
        }
        replayed.set("episodes", episodes); ObjectNode metadata = objectOrEmpty(field(artifact, "metadata")).deepCopy();
        metadata.put("null_method", method); replayed.set("metadata", metadata); replayed = withHash(replayed);
        ObjectNode validation = object(); validation.put("allowSubset", true);
        validateStatisticalArtifactSet(replayed, validation);
        ObjectNode transformation = field(options, "transformation").isObject()
                ? objectOrEmpty(field(options, "transformation")).deepCopy() : object();
        JsonNode budget = field(options, "selectionBudget"); validateNullTransformation(method, transformation, budget);
        String source = text(field(artifact, "content_sha256")); String frame = hash(artifactFrame(artifact));
        JsonNode budgetSha = definedNonNull(budget) ? JSON.textNode(hash(budget)) : NullNode.instance;
        ObjectNode proof = object(); proof.put("method", method); proof.put("source_artifact_sha256", source);
        proof.put("frame_sha256", frame); proof.set("transformation", transformation.deepCopy());
        proof.set("selection_budget_sha256", cloneNode(budgetSha));
        ObjectNode value = object(); value.put("schema", schema("nullReplay")); value.put("version", 1);
        value.put("method", method); value.put("source_artifact_sha256", source); value.put("frame_sha256", frame);
        value.set("artifact", replayed); value.set("transformation", transformation);
        value.put("proof_sha256", hash(proof)); value.set("selection_budget_sha256", budgetSha);
        ObjectNode result = withHash(value); validateNullReplay(result); validateRegisteredSchema(result); return result;
    }

    public static ObjectNode runNullControlsV5(ObjectNode args) {
        return runNullControlsV5(args, null, null);
    }

    public static ObjectNode runNullControlsV5(ObjectNode args, NullReplaySuite replay) {
        return runNullControlsV5(args, replay, null);
    }

    public static ObjectNode runNullControlsV5(ObjectNode args, NullReplaySuite replay,
            PhysicalNullRunner selectionRunner) {
        ObjectNode options = args == null ? object() : args;
        JsonNode rawArtifact = field(options, "artifact");
        if (rawArtifact.isArray()) throw failure("null controls require a canonical artifact and replay interface");
        ObjectNode artifact = objectOrEmpty(rawArtifact).deepCopy();
        String mode = truthy(field(options, "mode"))
                ? jsString(field(options, "mode")).toUpperCase(Locale.ROOT) : "AUTHORITATIVE";
        boolean authoritative = !"FIXTURE".equals(mode);
        String selectedCandidateId = jsString(field(options, "selectedCandidateId"));
        JsonNode rawSelectedRows = field(options, "selectedOutcomeRows");
        Map<String, JsonNode> selectedRowsForStatistic = null;
        ArrayNode selectedEpisodeScope = field(options, "selectedEpisodeIds").isArray()
                ? array(field(options, "selectedEpisodeIds")).deepCopy() : null;

        if (rawSelectedRows.isArray()) {
            for (JsonNode candidate : array(field(artifact, "candidates"))) {
                if (selectedCandidateId.equals(text(field(candidate, "candidate_id")))) {
                    selectedCandidateId += ":selected-oos"; break;
                }
            }
            Map<String, JsonNode> byId = new LinkedHashMap<>(); boolean duplicated = false;
            for (JsonNode row : rawSelectedRows) {
                String id = text(field(row, "episode_id"));
                if (byId.putIfAbsent(id, row) != null) duplicated = true;
            }
            ArrayNode scoped = selectedEpisodeScope == null ? array() : selectedEpisodeScope.deepCopy();
            if (selectedEpisodeScope == null) for (JsonNode episode : array(field(artifact, "episodes"))) {
                scoped.add(text(field(episode, "episode_id")));
            }
            Set<String> scopedIds = textSet(scoped);
            boolean incomplete = duplicated || scopedIds.size() != scoped.size();
            for (String id : scopedIds) if (!byId.containsKey(id)) incomplete = true;
            if (selectedEpisodeScope == null && (byId.size() != array(field(artifact, "episodes")).size()
                    || !fieldTextSet(field(artifact, "episodes"), "episode_id").equals(byId.keySet()))) {
                incomplete = true;
            }
            if (incomplete) throw failure("selected OOS null vector is incomplete or duplicated");
            selectedEpisodeScope = scoped; selectedRowsForStatistic = byId;

            if (!authoritative || selectionRunner == null) {
                ObjectNode behaviorInput = object();
                behaviorInput.put("schema", "strategy-v5-statistical-selected-oos-null/1");
                behaviorInput.put("selected_candidate_id", selectedCandidateId); ArrayNode behaviorRows = array();
                for (JsonNode idNode : scoped) {
                    JsonNode selected = byId.get(jsString(idNode)); ObjectNode row = object();
                    row.set("episode_id", cloneNode(idNode)); row.set("net_r", cloneNode(field(selected, "net_r")));
                    row.set("traded", cloneNode(field(selected, "traded"))); behaviorRows.add(row);
                }
                behaviorInput.set("rows", behaviorRows); String syntheticBehavior = hash(behaviorInput);
                ArrayNode episodes = array();
                for (JsonNode raw : array(field(artifact, "episodes"))) {
                    ObjectNode episode = objectOrEmpty(raw).deepCopy();
                    ObjectNode candidateReturns = objectOrEmpty(field(episode, "candidate_returns")).deepCopy();
                    JsonNode selected = byId.get(text(field(episode, "episode_id"))); ObjectNode value = object();
                    if (selected == null) { value.put("net_r", 0); value.put("traded", false); }
                    else {
                        value.put("net_r", finiteNumber(field(selected, "net_r"),
                                selectedCandidateId + "/" + text(field(episode, "episode_id"))));
                        value.put("traded", field(selected, "traded").asBoolean(false));
                    }
                    candidateReturns.set(selectedCandidateId, value); episode.set("candidate_returns", candidateReturns);
                    episodes.add(episode);
                }
                ArrayNode candidates = array(field(artifact, "candidates")).deepCopy(); ObjectNode candidate = object();
                candidate.put("candidate_id", selectedCandidateId); candidate.put("behavior_sha256", syntheticBehavior);
                candidates.add(candidate); artifact.set("candidates", candidates); artifact.set("episodes", episodes);
                artifact = withHash(artifact); ObjectNode validation = object(); validation.put("allowSubset", true);
                validateStatisticalArtifactSet(artifact, validation);
            }
        }

        ObjectNode physicalRunnerContract = null;
        if (authoritative && selectionRunner != null) {
            physicalRunnerContract = selectionRunner.contract();
            validatePhysicalNullRunnerContract(physicalRunnerContract, artifact);
        }
        if (authoritative && replay != null) {
            throw failure("authoritative replay callbacks are not accepted; use the physical role-bound adaptive null runner contract");
        }
        if (!authoritative && replay == null) {
            throw failure("fixture null controls require a replay interface");
        }
        ObjectNode selectionBudget = validateSelectionBudget(field(options, "selectionBudget"));
        double iterationsNumber = defined(field(options, "iterations")) ? numberJs(field(options, "iterations")) : 256;
        double batchNumber = defined(field(options, "sequentialBatchSize"))
                ? numberJs(field(options, "sequentialBatchSize")) : 8;
        if (!Double.isFinite(iterationsNumber) || iterationsNumber != Math.rint(iterationsNumber)
                || iterationsNumber < 1 || !Double.isFinite(batchNumber) || batchNumber != Math.rint(batchNumber)
                || batchNumber < 1) {
            throw failure("null iterations and sequential batch size must be positive integers");
        }
        int iterations = (int) iterationsNumber, sequentialBatchSize = (int) batchNumber;
        ObjectNode validation = object(); validation.put("allowSubset", true);
        validateStatisticalArtifactSet(artifact, validation);
        String directionalHypothesis = truthy(field(options, "directionalHypothesis"))
                ? jsString(field(options, "directionalHypothesis")) : "positive";
        if (!Set.of("positive", "negative").contains(directionalHypothesis)) {
            throw failure("directional hypothesis must be positive or negative");
        }
        if (!authoritative) for (String method : PHYSICAL_NULL_METHODS) {
            if (!replay.methods().containsKey(method)) throw failure("null replay method " + method + " is missing");
        }

        Map<String, JsonNode> episodeById = new LinkedHashMap<>();
        for (JsonNode episode : array(field(artifact, "episodes"))) {
            episodeById.put(text(field(episode, "episode_id")), episode);
        }
        ArrayNode observedRows;
        if (selectedRowsForStatistic != null) {
            observedRows = array();
            for (JsonNode idNode : selectedEpisodeScope) {
                String id = jsString(idNode); JsonNode episode = episodeById.get(id);
                if (episode == null) throw failure("selected OOS null vector is incomplete or duplicated");
                JsonNode selected = selectedRowsForStatistic.get(id); ObjectNode row = object();
                row.set("episode_id", cloneNode(idNode)); row.set("asset", cloneNode(field(episode, "asset")));
                row.set("decision_time", cloneNode(field(episode, "decision_time")));
                row.set("resolution_time", cloneNode(field(episode, "resolution_time")));
                row.put("value", finiteNumber(field(selected, "net_r"), selectedCandidateId + "/" + id));
                row.put("traded", field(selected, "traded").asBoolean(false)); observedRows.add(row);
            }
        } else {
            observedRows = strictValues(artifact, selectedCandidateId,
                    selectedEpisodeScope == null ? null : textSet(selectedEpisodeScope));
        }
        ArrayNode observedTradeRows = array();
        for (JsonNode row : observedRows) if (field(row, "traded").asBoolean(false)) observedTradeRows.add(row);
        ArrayNode observedIndependentTrades = collapseMarketEpisodeRows(observedTradeRows, field(artifact, "episodes"));
        List<Double> observedValues = new ArrayList<>();
        for (JsonNode row : observedIndependentTrades) observedValues.add(numberJs(field(row, "value")));
        double observed = observedValues.isEmpty() ? 0 : mean(observedValues);
        int direction = "positive".equals(directionalHypothesis) ? 1 : -1;
        double seed = defined(field(options, "seed")) ? numberJs(field(options, "seed")) : 11;
        double alpha = defined(field(options, "alpha")) ? numberJs(field(options, "alpha")) : .05;
        ArrayNode tests = array();

        for (String method : PHYSICAL_NULL_METHODS) {
            if (authoritative && selectionRunner == null) {
                ObjectNode digest = object(); digest.put("method", method);
                digest.put("status", "UNSUPPORTED_ADAPTIVE_RERUN"); ObjectNode test = object();
                test.put("name", method.toUpperCase(Locale.ROOT)); test.put("p_value", 1);
                test.put("p_value_lower_bound", 1); test.put("p_value_upper_bound", 1);
                test.put("null_statistics_sha256", hash(digest));
                test.put("unsupported_reason", "PHYSICAL_NULL_SELECTION_ADAPTER_MISSING: no verified evaluator-owned label/execution/nested-selection implementation was supplied");
                test.put("pass", false); test.put("method", "UNSUPPORTED_ADAPTIVE_SELECTION_RERUN");
                test.put("directional_hypothesis", directionalHypothesis); test.put("iterations", 0);
                test.put("iterations_planned", iterations); test.put("sequential_batch_size", sequentialBatchSize);
                test.put("sequential_stopping_reason", "UNSUPPORTED");
                for (String name : List.of("evaluation_attempt_k", "worker_evaluation_count", "worker_count",
                        "peak_in_flight", "batch_count", "cache_hit_count", "disk_cache_hit_count",
                        "checkpointed_iterations")) test.put(name, 0);
                test.put("checkpoint_policy", "UNSUPPORTED_NO_PHYSICAL_ADAPTER"); test.set("worker_slots_used", array());
                tests.add(test); continue;
            }
            long parsedSeed = Long.parseLong(hash(methodRngInput(seed, method)).substring(0, 8), 16);
            XorShift32 methodRandom = new XorShift32(parsedSeed == 0 ? 1 : parsedSeed);
            int exceed = 0, completed = 0; double lowerBound = 1d / (iterations + 1), upperBound = 1;
            String stoppingReason = "MAX_BUDGET_EXHAUSTED"; ArrayNode nullStats = array();
            ArrayNode selectionTrace = array(); long evaluationAttemptK = 0, workerEvaluationCount = 0;
            long workerCount = 0, peakInFlight = 0, batchCount = 0, cacheHitCount = 0;
            long diskCacheHitCount = 0, checkpointedIterations = 0; Set<Long> workerSlotsUsed = new HashSet<>();
            for (int iteration = 0; iteration < iterations; iteration++) {
                long replaySeed = (long) Math.floor(methodRandom.next() * 0x7fffffffL);
                if (authoritative) {
                    ObjectNode runnerArgs = object(); runnerArgs.set("source_artifact", artifact.deepCopy());
                    runnerArgs.put("method", method); runnerArgs.put("seed", replaySeed);
                    runnerArgs.put("iteration", iteration); runnerArgs.set("selection_budget", selectionBudget.deepCopy());
                    runnerArgs.put("selected_candidate_id", selectedCandidateId);
                    if (selectedEpisodeScope == null) runnerArgs.putNull("selected_episode_ids");
                    else runnerArgs.set("selected_episode_ids", selectedEpisodeScope.deepCopy());
                    runnerArgs.put("selected_trade_count", observedTradeRows.size());
                    ArrayNode tradeIds = array();
                    for (JsonNode row : observedTradeRows) tradeIds.add(text(field(row, "episode_id")));
                    runnerArgs.set("selected_trade_episode_ids", tradeIds);
                    runnerArgs.set("physical_runner_contract", physicalRunnerContract.deepCopy());
                    ObjectNode chosen = selectionRunner.run(runnerArgs);
                    boolean invalid = !schema("physicalNullSelection").equals(text(field(chosen, "schema")))
                            || !method.equals(text(field(chosen, "method")))
                            || numberJs(field(chosen, "seed")) != replaySeed
                            || numberJs(field(chosen, "iteration")) != iteration
                            || !text(field(artifact, "content_sha256")).equals(
                                    text(field(chosen, "source_artifact_sha256")))
                            || !text(field(physicalRunnerContract, "feature_artifact_sha256")).equals(
                                    text(field(chosen, "feature_artifact_sha256")))
                            || !text(field(physicalRunnerContract, "label_artifact_sha256")).equals(
                                    text(field(chosen, "label_artifact_sha256")))
                            || !text(field(physicalRunnerContract, "execution_artifact_sha256")).equals(
                                    text(field(chosen, "execution_artifact_sha256")))
                            || !text(field(chosen, "content_sha256")).equals(ownHash(chosen))
                            || !hash(selectionBudget).equals(text(field(chosen, "selection_budget_sha256")))
                            || !HASH_RE.matcher(text(field(chosen, "transformation_sha256"))).matches()
                            || !HASH_RE.matcher(text(field(chosen, "transformed_label_artifact_sha256"))).matches()
                            || !HASH_RE.matcher(text(field(chosen, "transformed_execution_artifact_sha256"))).matches()
                            || !HASH_RE.matcher(text(field(chosen, "recomputed_outcome_artifact_sha256"))).matches()
                            || !HASH_RE.matcher(text(field(chosen, "selected_outcome_vector_sha256"))).matches()
                            || !HASH_RE.matcher(text(field(chosen, "trace_sha256"))).matches()
                            || !field(chosen, "checkpoint_ref").isObject()
                            || !HASH_RE.matcher(text(field(field(chosen, "checkpoint_ref"), "content_sha256"))).matches()
                            || !"COMPLETED".equals(text(field(chosen, "checkpoint_status")))
                            || !Double.isFinite(numberJs(field(chosen, "selected_statistic")))
                            || chosen.has("selected_oos_rows");
                    if (invalid) throw failure(method
                            + " physical null runner returned an unbound or caller-supplied outcome vector");
                    try {
                        validateContractSchema(chosen);
                        validatePhysicalNullSelectionReferences(chosen,
                                truthy(field(physicalRunnerContract, "source_manifest_sha256"))
                                        ? text(field(physicalRunnerContract, "source_manifest_sha256")) : null);
                    } catch (RuntimeException error) {
                        throw failure(method + " physical null runner returned an unbound or caller-supplied outcome vector: "
                                + error.getMessage());
                    }
                    PhysicalNullWorkload accounting = readPhysicalNullWorkload(chosen, method);
                    evaluationAttemptK += accounting.evaluationAttemptK();
                    workerEvaluationCount += accounting.workerEvaluationCount();
                    workerCount = Math.max(workerCount, accounting.workerCount());
                    peakInFlight = Math.max(peakInFlight, accounting.peakInFlight());
                    batchCount += accounting.batchCount(); cacheHitCount += accounting.cacheHitCount();
                    diskCacheHitCount += accounting.diskCacheHitCount(); checkpointedIterations++;
                    workerSlotsUsed.addAll(accounting.workerSlotsUsed());
                    selectionTrace.add(text(field(chosen, "trace_sha256")));
                    double statistic = numberJs(field(chosen, "selected_statistic")); nullStats.add(statistic);
                    if (direction * statistic >= direction * observed) exceed++;
                } else {
                    ObjectNode replayArgs = object(); replayArgs.set("artifact", artifact.deepCopy());
                    replayArgs.put("seed", replaySeed); replayArgs.put("iteration", iteration);
                    replayArgs.set("selection_budget", selectionBudget.deepCopy());
                    replayArgs.put("selected_candidate_id", selectedCandidateId);
                    ObjectNode candidateArtifact = replay.replay(method, replayArgs);
                    ObjectNode replayValidation = object(); replayValidation.put("allowSubset", true);
                    validateStatisticalArtifactSet(candidateArtifact, replayValidation);
                    if (!stable(artifactFrame(candidateArtifact)).equals(stable(artifactFrame(artifact)))) {
                        throw failure(method + " replay changed the canonical episode frame");
                    }
                    ArrayNode rows = strictValues(candidateArtifact, selectedCandidateId,
                            selectedEpisodeScope == null ? null : textSet(selectedEpisodeScope));
                    ArrayNode tradedRows = array();
                    for (JsonNode row : rows) if (field(row, "traded").asBoolean(false)) tradedRows.add(row);
                    ArrayNode independentTrades = collapseMarketEpisodeRows(tradedRows,
                            field(candidateArtifact, "episodes")); List<Double> values = new ArrayList<>();
                    for (JsonNode row : independentTrades) values.add(numberJs(field(row, "value")));
                    double statistic = values.isEmpty() ? 0 : mean(values); nullStats.add(statistic);
                    if (direction * statistic >= direction * observed) exceed++;
                }
                completed = iteration + 1; lowerBound = (double) (exceed + 1) / (iterations + 1);
                upperBound = (double) (exceed + (iterations - completed) + 1) / (iterations + 1);
                if (completed % sequentialBatchSize == 0 || completed == iterations) {
                    if (lowerBound > alpha) {
                        stoppingReason = completed == iterations ? "MAX_BUDGET_EXHAUSTED"
                                : "FAIL_INEVITABLE_AT_FIXED_HORIZON"; break;
                    }
                    if (upperBound <= alpha) {
                        stoppingReason = completed == iterations ? "MAX_BUDGET_EXHAUSTED"
                                : "PASS_INEVITABLE_AT_FIXED_HORIZON"; break;
                    }
                }
            }
            boolean pass = upperBound <= alpha;
            double pValue = completed == iterations ? lowerBound : pass ? upperBound : lowerBound;
            ObjectNode test = object(); test.put("name", method.toUpperCase(Locale.ROOT));
            test.put("p_value", pValue); test.put("p_value_lower_bound", lowerBound);
            test.put("p_value_upper_bound", upperBound); test.put("null_statistics_sha256", hash(nullStats));
            test.put("selection_trace_sha256", hash(selectionTrace)); test.put("pass", pass);
            test.put("method", authoritative ? "PHYSICAL_ROLE_BOUND_ADAPTIVE_SELECTION" : "FIXTURE_REPLAY");
            test.put("sampling_unit", "independent_market_episode_cluster");
            test.put("directional_hypothesis", directionalHypothesis); test.put("iterations", completed);
            test.put("iterations_planned", iterations); test.put("sequential_batch_size", sequentialBatchSize);
            test.put("sequential_stopping_rule", "FIXED_HORIZON_ATTAINABLE_P_VALUE_ENVELOPE");
            test.put("sequential_stopping_reason", stoppingReason);
            test.put("evaluation_attempt_k", evaluationAttemptK);
            test.put("worker_evaluation_count", workerEvaluationCount); test.put("worker_count", workerCount);
            test.put("peak_in_flight", peakInFlight); test.put("batch_count", batchCount);
            test.put("cache_hit_count", cacheHitCount); test.put("disk_cache_hit_count", diskCacheHitCount);
            test.put("checkpointed_iterations", checkpointedIterations);
            test.put("checkpoint_policy", authoritative ? "CONTENT_ADDRESSED_PER_ITERATION_CAS"
                    : "FIXTURE_CALLBACK_NO_PHYSICAL_CHECKPOINT");
            List<Long> sortedSlots = new ArrayList<>(workerSlotsUsed); sortedSlots.sort(Long::compareTo);
            ArrayNode slots = array(); sortedSlots.forEach(slots::add); test.set("worker_slots_used", slots); tests.add(test);
        }

        ObjectNode value = object(); value.put("schema", schema("nulls")); value.put("version", 1);
        value.put("observed_expectancy_r", observed); value.put("selected_candidate_id", selectedCandidateId);
        value.put("directional_hypothesis", directionalHypothesis); value.put("alpha", alpha);
        value.put("iterations", iterations); value.set("tests", tests);
        value.put("pass", nodeObjects(tests).stream().allMatch(row -> field(row, "pass").asBoolean(false)));
        value.put("artifact_sha256", text(field(artifact, "content_sha256")));
        value.put("selection_budget_sha256", hash(selectionBudget));
        ObjectNode result = withHash(value); validateNullArtifact(result); return result;
    }

    private static ObjectNode methodRngInput(double seed, String method) {
        ObjectNode value = object(); value.put("schema", "strategy-v5-null-method-rng/1");
        value.put("seed", seed); value.put("method", method); return value;
    }

    public static ObjectNode calibrateNullControlsV5(ObjectNode args) {
        return calibrateNullControlsV5(args, null);
    }

    public static ObjectNode calibrateNullControlsV5(ObjectNode args, NullReplaySuite replay) {
        ObjectNode options = args == null ? object() : args;
        ArrayNode noEdgeFixtures = array(field(options, "noEdgeFixtures"));
        ArrayNode plantedEdgeFixtures = array(field(options, "plantedEdgeFixtures"));
        if (!field(options, "noEdgeFixtures").isArray() || !field(options, "plantedEdgeFixtures").isArray()
                || noEdgeFixtures.isEmpty() || plantedEdgeFixtures.isEmpty()) {
            throw failure("null calibration requires repeated no-edge and planted-edge fixtures");
        }
        if (replay == null || PHYSICAL_NULL_METHODS.stream().anyMatch(method -> !replay.methods().containsKey(method))) {
            throw failure("null calibration requires all four deterministic fixture replay methods");
        }
        ObjectNode selectionBudget = validateSelectionBudget(field(options, "selectionBudget"));
        JsonNode rawSeeds = field(options, "seeds").isArray() ? field(options, "seeds")
                : MAPPER.valueToTree(List.of(11, 23, 47, 71, 89));
        Set<Long> uniqueSeeds = new HashSet<>();
        for (JsonNode raw : rawSeeds) {
            long seed = integerFromNumber(raw, Long.MIN_VALUE); uniqueSeeds.add(seed);
        }
        List<Long> seeds = new ArrayList<>(uniqueSeeds); seeds.sort(Long::compareTo);
        if (seeds.size() < 3 || seeds.contains(Long.MIN_VALUE)) {
            throw failure("null calibration seed inventory is invalid");
        }
        double iterations = defined(field(options, "iterations")) ? numberJs(field(options, "iterations")) : 64;
        double alpha = defined(field(options, "alpha")) ? numberJs(field(options, "alpha")) : .05;
        double typeICeiling = defined(field(options, "typeICeiling"))
                ? numberJs(field(options, "typeICeiling")) : .10;
        double minimumPower = defined(field(options, "minPower")) ? numberJs(field(options, "minPower")) : .80;
        ArrayNode records = array();
        for (String kind : List.of("NO_EDGE", "PLANTED_EDGE")) {
            ArrayNode fixtures = "NO_EDGE".equals(kind) ? noEdgeFixtures : plantedEdgeFixtures;
            for (JsonNode fixture : fixtures) for (long seed : seeds) {
                if (!fixture.isObject() || !truthy(field(fixture, "artifact"))
                        || !field(fixture, "selectedCandidateId").isTextual()) {
                    throw failure(kind + " calibration fixture is incomplete");
                }
                ObjectNode run = object(); run.set("artifact", cloneNode(field(fixture, "artifact")));
                run.put("selectedCandidateId", text(field(fixture, "selectedCandidateId")));
                run.set("selectionBudget", selectionBudget.deepCopy());
                if (iterations == Math.rint(iterations)) run.put("iterations", (long) iterations);
                else run.put("iterations", iterations);
                run.put("seed", seed); run.put("alpha", alpha); run.put("mode", "FIXTURE");
                ObjectNode result = runNullControlsV5(run, replay); ObjectNode record = object();
                record.put("kind", kind); record.put("fixture_id", truthy(field(fixture, "fixtureId"))
                        ? jsString(field(fixture, "fixtureId"))
                        : text(field(field(fixture, "artifact"), "content_sha256")));
                record.put("seed", seed); record.put("pass", field(result, "pass").asBoolean(false));
                record.put("content_sha256", text(field(result, "content_sha256"))); records.add(record);
            }
        }
        long nullCases = 0, plantedCases = 0, nullRejections = 0, plantedPasses = 0;
        for (JsonNode row : records) {
            if ("NO_EDGE".equals(text(field(row, "kind")))) {
                nullCases++; if (field(row, "pass").asBoolean(false)) nullRejections++;
            } else {
                plantedCases++; if (field(row, "pass").asBoolean(false)) plantedPasses++;
            }
        }
        double nullRejectionRate = nullCases > 0 ? (double) nullRejections / nullCases : 1;
        double power = plantedCases > 0 ? (double) plantedPasses / plantedCases : 0;
        ObjectNode value = object(); value.put("schema", schema("calibration")); value.put("version", 1);
        value.put("selection_budget_sha256", hash(selectionBudget)); ArrayNode seedNodes = array();
        seeds.forEach(seedNodes::add); value.set("seeds", seedNodes);
        if (iterations == Math.rint(iterations)) value.put("iterations", (long) iterations);
        else value.put("iterations", iterations);
        value.put("alpha", alpha); value.put("type_i_ceiling", typeICeiling);
        value.put("minimum_power", minimumPower); value.put("no_edge_fixture_count", noEdgeFixtures.size());
        value.put("planted_edge_fixture_count", plantedEdgeFixtures.size()); value.put("null_case_count", nullCases);
        value.put("planted_case_count", plantedCases); value.put("null_rejections", nullRejections);
        value.put("planted_passes", plantedPasses); value.put("null_rejection_rate", nullRejectionRate);
        value.put("power", power); ObjectNode tolerance = object(); tolerance.put("type_i_ceiling", typeICeiling);
        tolerance.put("minimum_power", minimumPower); value.set("tolerance", tolerance); value.set("records", records);
        value.put("pass", nullRejectionRate <= typeICeiling && power >= minimumPower);
        value.put("mode", "FIXTURE_CALIBRATION"); ObjectNode result = withHash(value);
        validateNullCalibration(result); validateRegisteredSchema(result); return result;
    }

    private static boolean validateNullCalibration(JsonNode value) {
        assertOwnHash(value, schema("calibration"), "null calibration artifact");
        assertKnownKeys(value, Set.of("schema", "version", "selection_budget_sha256", "seeds", "iterations",
                "alpha", "type_i_ceiling", "minimum_power", "no_edge_fixture_count",
                "planted_edge_fixture_count", "null_case_count", "planted_case_count", "null_rejections",
                "planted_passes", "null_rejection_rate", "power", "tolerance", "records", "pass", "mode",
                "content_sha256"), "null calibration artifact");
        double alpha = numberJs(field(value, "alpha")); double ceiling = numberJs(field(value, "type_i_ceiling"));
        double minimum = numberJs(field(value, "minimum_power")); double rejection = numberJs(field(value,
                "null_rejection_rate")); double power = numberJs(field(value, "power"));
        long nullCases = integerFromNumber(field(value, "null_case_count"), -1);
        long plantedCases = integerFromNumber(field(value, "planted_case_count"), -1);
        JsonNode records = field(value, "records");
        if (!"FIXTURE_CALIBRATION".equals(text(field(value, "mode"))) || !field(value, "seeds").isArray()
                || field(value, "seeds").size() < 3 || !records.isArray()
                || records.size() != nullCases + plantedCases || !(alpha > 0 && alpha < 1)
                || !(ceiling >= 0 && ceiling <= 1) || !(minimum >= 0 && minimum <= 1)
                || !(rejection >= 0 && rejection <= 1) || !(power >= 0 && power <= 1)
                || !field(value, "pass").isBoolean()) {
            throw failure("null calibration artifact is incomplete");
        }
        for (JsonNode row : records) {
            if (!Set.of("NO_EDGE", "PLANTED_EDGE").contains(text(field(row, "kind")))
                    || !field(row, "fixture_id").isTextual()
                    || integerFromNumber(field(row, "seed"), Long.MIN_VALUE) == Long.MIN_VALUE
                    || !field(row, "pass").isBoolean()) {
                throw failure("null calibration record is malformed");
            }
            requireHash(field(row, "content_sha256"), "null calibration record content_sha256");
        }
        double expectedRejection = nullCases > 0
                ? numberJs(field(value, "null_rejections")) / nullCases : 1;
        double expectedPower = plantedCases > 0
                ? numberJs(field(value, "planted_passes")) / plantedCases : 0;
        boolean expectedPass = rejection <= ceiling && power >= minimum;
        if (rejection != expectedRejection || power != expectedPower
                || field(value, "pass").asBoolean() != expectedPass) {
            throw failure("null calibration rates or decision are inconsistent");
        }
        return true;
    }

    public static boolean validateContractSchema(JsonNode value) {
        return validateContractSchema(value, object());
    }

    public static boolean validateContractSchema(JsonNode value, ObjectNode args) {
        String valueSchema = text(field(value, "schema"));
        if (schema("input").equals(valueSchema)) return validateStatisticalArtifactSet(value, args);
        if (schema("exposure").equals(valueSchema)) { validateExposureHead(value); return true; }
        if (schema("behaviorRegistry").equals(valueSchema)) return validateBehaviorDefinitionRegistry(value, args);
        if (schema("registryJournal").equals(valueSchema)) {
            assertOwnHash(value, schema("registryJournal"), "registry journal");
            if (!"PREPARED".equals(text(field(value, "status"))) || !field(value, "next_head").isObject()
                    || !text(field(value, "next_head_sha256")).equals(
                    text(field(field(value, "next_head"), "content_sha256")))) {
                throw failure("registry journal contract is invalid");
            }
            return true;
        }
        if (schema("wfo").equals(valueSchema)) return validateNestedWfoArtifact(value);
        if (schema("publicationTransaction").equals(valueSchema)) {
            assertOwnHash(value, schema("publicationTransaction"), "publication transaction");
            if (!Set.of("PREPARED", "COMMITTED").contains(text(field(value, "status")))
                    || !field(value, "no_k_mutation").asBoolean(false)
                    || !field(value, "no_rollback").asBoolean(false)
                    || !text(field(value, "expected_head_sha256")).equals(text(field(value, "next_head_sha256")))
                    || !field(value, "artifact_refs").isArray() || field(value, "artifact_refs").isEmpty()) {
                throw failure("publication transaction contract is invalid");
            }
            if (!field(value, "bound_head").isObject() || !field(value, "bound_registry").isObject()) {
                throw failure("publication transaction immutable control snapshots are missing");
            }
            ObjectNode boundHead = validateExposureHead(field(value, "bound_head"));
            if (!text(field(boundHead, "content_sha256")).equals(text(field(value, "expected_head_sha256")))) {
                throw failure("publication bound HEAD does not match its CAS hash");
            }
            ObjectNode validation = object(); validation.set("exposureHead", boundHead);
            validateBehaviorDefinitionRegistry(field(value, "bound_registry"), validation);
            if (!text(field(field(value, "bound_registry"), "content_sha256")).equals(
                    text(field(value, "expected_registry_sha256")))) {
                throw failure("publication bound registry does not match its CAS hash");
            }
            String expectedId = publicationTransactionId(text(field(value, "transaction_path")),
                    text(field(value, "exposure_head_path")), text(field(value, "registry_path")),
                    text(field(value, "stage_root")), text(field(value, "expected_head_sha256")),
                    text(field(value, "expected_registry_sha256")), text(field(value, "wfo_sha256")),
                    text(field(value, "run_sha256")), field(value, "artifact_refs"));
            if (!expectedId.equals(text(field(value, "transaction_id")))) {
                throw failure("publication transaction ID does not match its content-addressed semantics");
            }
            ObjectNode dummyRun = object(); dummyRun.put("content_sha256", text(field(value, "run_sha256")));
            dummyRun.putObject("wfo").put("artifact", text(field(value, "wfo_sha256")));
            dummyRun.putObject("lineage").put("wfo_sha256", text(field(value, "wfo_sha256")));
            ObjectNode dummyWfo = object(); dummyWfo.put("content_sha256", text(field(value, "wfo_sha256")));
            assertPublicationArtifactRefs(text(field(value, "transaction_path")),
                    text(field(value, "exposure_head_path")), text(field(value, "registry_path")),
                    text(field(value, "stage_root")), field(value, "artifact_refs"), dummyRun, dummyWfo, null);
            return true;
        }
        if (schema("vectors").equals(valueSchema)) {
            JsonNode head = field(args, "exposureHead");
            return validateVectorInventory(value, head, field(value, "episode_ids"));
        }
        if (schema("audit").equals(valueSchema)) return validateStatisticalAudit(value);
        if (schema("nulls").equals(valueSchema)) return validateNullArtifact(value);
        if (schema("checkpoint").equals(valueSchema)) { validateCheckpointContract(value); return true; }
        if (schema("nullReplay").equals(valueSchema)) { validateNullReplay(value); return true; }
        if (schema("calibration").equals(valueSchema)) return validateNullCalibration(value);
        if (schema("physicalNullRunner").equals(valueSchema)) {
            validatePhysicalNullRunnerContract(value, null); return true;
        }
        if (schema("physicalNullSelection").equals(valueSchema)) {
            assertOwnHash(value, schema("physicalNullSelection"), "physical null selection");
            validatePhysicalNullSelectionReferences(value,
                    truthy(field(value, "source_manifest_sha256"))
                            ? text(field(value, "source_manifest_sha256")) : null);
            return true;
        }
        if (schema("genetic").equals(valueSchema)) return validateGeneticArtifact(value);
        if (schema("stress").equals(valueSchema)) {
            assertOwnHash(value, schema("stress"), "stress decision");
            if (!field(value, "pass").isBoolean() || !"AUTHORITATIVE_RECOMPUTED".equals(text(field(value, "provenance"))))
                throw failure("stress decision is missing or not lineage-bound");
            ArrayNode scenarios = requireArray(field(value, "scenarios"), "stress scenarios");
            List<String> ids = new ArrayList<>();
            for (JsonNode row : scenarios) {
                ids.add(text(field(row, "id")));
                if (!field(row, "pass").isBoolean()) throw failure("stress scenario pass is invalid");
                requireHash(field(row, "digest"), "stress scenario digest");
            }
            ids.sort(String::compareTo); List<String> expected = new ArrayList<>(STRESS_SCENARIOS); expected.sort(String::compareTo);
            requireHash(field(value, "source_artifact_sha256"), "stress.source_artifact_sha256");
            if (!ids.equals(expected) || !hash(scenarios).equals(text(field(value, "scenario_inventory_sha256")))
                    || text(field(value, "selected_candidate_id")).isEmpty()) {
                throw failure("stress decision is missing the authoritative stress scenario inventory");
            }
            return true;
        }
        if (schema("portfolio").equals(valueSchema)) {
            assertOwnHash(value, schema("portfolio"), "portfolio decision");
            ArrayNode decisions = requireArray(field(value, "asset_decisions"), "portfolio asset decisions");
            ArrayNode increments = requireArray(field(value, "return_increments"), "portfolio return increments");
            if (!field(value, "pass").isBoolean() || !"AUTHORITATIVE_RECOMPUTED".equals(text(field(value, "provenance")))
                    || decisions.isEmpty() || increments.isEmpty()
                    || !hash(decisions).equals(text(field(value, "asset_decisions_sha256")))
                    || !hash(increments).equals(text(field(value, "return_increments_sha256")))) {
                throw failure("portfolio decision is missing the authoritative portfolio recomputation contract");
            }
            requireHash(field(value, "risk_digest_sha256"), "portfolio.risk_digest_sha256");
            requireHash(field(value, "source_artifact_sha256"), "portfolio.source_artifact_sha256"); return true;
        }
        throw failure("unknown statistical contract schema " + valueSchema);
    }

    private static ObjectNode artifactFrame(JsonNode value) {
        ObjectNode result = object(); result.set("lineage", cloneNode(field(value, "lineage")));
        result.set("exposure_head_sha256", cloneNode(field(value, "exposure_head_sha256")));
        result.set("candidates", cloneNode(field(value, "candidates"))); ArrayNode episodes = array();
        for (JsonNode raw : array(field(value, "episodes"))) {
            ObjectNode row = object();
            for (String key : List.of("episode_id", "asset", "decision_time", "resolution_time", "eligible")) {
                row.set(key, cloneNode(field(raw, key)));
            }
            List<String> ids = new ArrayList<>(fieldNames(field(raw, "candidate_returns"))); ids.sort(String::compareTo);
            row.set("candidate_ids", strings(ids)); episodes.add(row);
        }
        result.set("episodes", episodes); return result;
    }

    private static void validateNullTransformation(String method, JsonNode transformation, JsonNode budget) {
        if (!transformation.isObject() || !method.equals(text(field(transformation, "method")))) {
            throw failure(method + " replay transformation is missing");
        }
        if ("block_permuted_labels".equals(method)
                && (!field(transformation, "block_length").isIntegralNumber()
                || field(transformation, "block_length").asLong() < 1
                || !HASH_RE.matcher(text(field(transformation, "permutation_sha256"))).matches()
                || !HASH_RE.matcher(text(field(transformation, "labels_source_sha256"))).matches())) {
            throw failure("block permutation proof is incomplete");
        }
        if ("timestamp_shifted_outcomes".equals(method)
                && (!Double.isFinite(numberJs(field(transformation, "shift_ms")))
                || numberJs(field(transformation, "shift_ms")) == 0
                || !HASH_RE.matcher(text(field(transformation, "shift_map_sha256"))).matches())) {
            throw failure("timestamp shift proof is incomplete");
        }
        if ("frequency_matched_random_intents".equals(method)
                && (!field(transformation, "target_trade_count").isIntegralNumber()
                || field(transformation, "target_trade_count").asLong() < 0
                || !HASH_RE.matcher(text(field(transformation, "intent_vector_sha256"))).matches())) {
            throw failure("random intent proof is incomplete");
        }
        if ("winners_curse_selection".equals(method)
                && (!definedNonNull(budget) || !hash(budget).equals(text(field(transformation, "selection_budget_sha256")))
                || !HASH_RE.matcher(text(field(transformation, "rerun_sha256"))).matches())) {
            throw failure("winner curse proof is incomplete");
        }
    }

    private static void validateNullReplay(JsonNode value) {
        assertOwnHash(value, schema("nullReplay"), "null replay artifact");
        ObjectNode proof = object(); proof.set("method", cloneNode(field(value, "method")));
        proof.set("source_artifact_sha256", cloneNode(field(value, "source_artifact_sha256")));
        proof.set("frame_sha256", cloneNode(field(value, "frame_sha256")));
        proof.set("transformation", cloneNode(field(value, "transformation")));
        proof.set("selection_budget_sha256", cloneNode(field(value, "selection_budget_sha256")));
        if (!field(value, "artifact").isObject() || !hash(proof).equals(text(field(value, "proof_sha256")))) {
            throw failure("null replay proof is invalid");
        }
        ObjectNode options = object(); options.put("allowSubset", true);
        validateStatisticalArtifactSet(field(value, "artifact"), options);
    }

    private static void validateCheckpointContract(JsonNode checkpoint) {
        assertOwnHash(checkpoint, schema("checkpoint"), "genetic checkpoint");
        if (!Set.of("RUNNING", "SEED_COMPLETE", "COMPLETE").contains(text(field(checkpoint, "checkpoint_status")))) {
            throw failure("genetic checkpoint contract is invalid");
        }
        if (!field(checkpoint, "seed_index").isIntegralNumber() || field(checkpoint, "seed_index").asLong() < 0
                || !field(checkpoint, "generation").isIntegralNumber() || !field(checkpoint, "seed").isIntegralNumber()) {
            throw failure("genetic checkpoint contract is invalid");
        }
        ObjectNode state = object(); state.set("seedIndex", cloneNode(field(checkpoint, "seed_index")));
        state.set("seed", cloneNode(field(checkpoint, "seed"))); state.set("generation", cloneNode(field(checkpoint, "generation")));
        state.set("rngState", cloneNode(field(checkpoint, "rng_state"))); state.set("population", cloneNode(field(checkpoint, "population")));
        state.set("historySha256", cloneNode(field(checkpoint, "history_sha256")));
        state.set("seedFinalists", cloneNode(field(checkpoint, "seed_finalists")));
        state.set("seedMembership", cloneNode(field(checkpoint, "seed_membership")));
        state.set("plateau", cloneNode(field(checkpoint, "plateau")));
        state.set("paretoSignature", cloneNode(field(checkpoint, "pareto_signature")));
        if (!hash(state).equals(text(field(checkpoint, "state_sha256")))) {
            throw failure("genetic checkpoint contract is invalid");
        }
    }

    public static ArrayNode makeQuarterlyFolds(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        ArrayNode episodes = array(field(options, "episodes"));
        List<Long> times = new ArrayList<>();
        episodes.forEach(row -> times.add(strictTime(field(row, "decision_time"), "timestamp")));
        times.sort(Long::compareTo);
        long end = truthy(field(options, "endAt")) ? strictTime(field(options, "endAt"), "timestamp")
                : times.get(times.size() - 1);
        long start = addUtcMonths(end, -24);
        long purge = ((Number) STAT_DEFAULTS.get("purgeDays")).longValue() * 86_400_000L;
        long embargo = ((Number) STAT_DEFAULTS.get("embargoDays")).longValue() * 86_400_000L;
        ArrayNode folds = array();
        for (int index = 0; index < 8; index++) {
            long rawStart = addUtcMonths(start, index * 3L);
            long rawEnd = index == 7 ? end : addUtcMonths(start, (index + 1L) * 3L);
            ObjectNode fold = object(); fold.put("fold_id", "outer-" + (index + 1));
            fold.put("raw_test_start", JS_ISO.format(Instant.ofEpochMilli(rawStart)));
            fold.put("train_end", JS_ISO.format(Instant.ofEpochMilli(rawStart - purge)));
            fold.put("test_start", JS_ISO.format(Instant.ofEpochMilli(rawStart + embargo)));
            fold.put("test_end", JS_ISO.format(Instant.ofEpochMilli(rawEnd)));
            fold.put("purge_ms", purge); fold.put("embargo_ms", embargo); folds.add(fold);
        }
        return folds;
    }

    public static ObjectNode pboFromFolds(JsonNode folds, String selectedCandidateId) {
        ObjectNode options = object(); options.put("purgeDays", ((Number) STAT_DEFAULTS.get("purgeDays")).doubleValue());
        options.put("embargoDays", ((Number) STAT_DEFAULTS.get("embargoDays")).doubleValue());
        options.put("requireTimestamps", false); return pboFromFolds(folds, selectedCandidateId, options);
    }

    public static ObjectNode pboFromFolds(JsonNode rawFolds, String selectedCandidateId, ObjectNode args) {
        ArrayNode folds = array(rawFolds); if (folds.size() < 4) return null;
        double purgeDays = defined(field(args, "purgeDays")) ? numberJs(field(args, "purgeDays"))
                : ((Number) STAT_DEFAULTS.get("purgeDays")).doubleValue();
        double embargoDays = defined(field(args, "embargoDays")) ? numberJs(field(args, "embargoDays"))
                : ((Number) STAT_DEFAULTS.get("embargoDays")).doubleValue();
        boolean requireTimestamps = field(args, "requireTimestamps").asBoolean(false);
        boolean observations = true;
        for (JsonNode fold : folds) observations &= field(fold, "observations").isArray()
                && !field(fold, "observations").isEmpty();
        if (observations) return pboFromEpisodeObservations(folds, selectedCandidateId, purgeDays, embargoDays);
        List<FoldInterval> timestamps = new ArrayList<>();
        for (int index = 0; index < folds.size(); index++) {
            JsonNode fold = folds.get(index); Long start = null, end = null;
            try {
                start = strictTime(field(fold, "test_start"), "pbo[" + index + "].test_start");
                end = strictTime(field(fold, "test_end"), "pbo[" + index + "].test_end");
            } catch (IllegalArgumentException error) {
                if (requireTimestamps) throw error;
            }
            if (requireTimestamps && (start == null || end <= start)) {
                throw failure("PBO fold test interval is not chronological");
            }
            timestamps.add(new FoldInterval(index, start, end));
        }
        if (requireTimestamps) {
            List<FoldInterval> sorted = new ArrayList<>(timestamps);
            sorted.sort(Comparator.comparingLong(row -> row.start));
            for (int index = 1; index < sorted.size(); index++) if (sorted.get(index).start < sorted.get(index - 1).end) {
                throw failure("PBO fold test intervals overlap");
            }
        }
        List<Integer> indices = new ArrayList<>(); for (int index = 0; index < folds.size(); index++) indices.add(index);
        List<List<Integer>> combinations = chooseCombinations(indices, indices.size() / 2);
        long purgeMs = (long) (purgeDays * 86_400_000d), embargoMs = (long) (embargoDays * 86_400_000d);
        int valid = 0, degraded = 0; ArrayNode details = array();
        for (List<Integer> train : combinations) {
            List<Integer> test = indices.stream().filter(index -> !train.contains(index)).toList(); if (test.isEmpty()) continue;
            List<Integer> purged = new ArrayList<>(), embargoed = new ArrayList<>(), retained = new ArrayList<>();
            for (int trainIndex : train) {
                if (!requireTimestamps) { retained.add(trainIndex); continue; }
                long trainStart = timestamps.get(trainIndex).start, trainEnd = timestamps.get(trainIndex).end;
                long lifecycleEnd = Math.max(trainEnd, trainStart + purgeMs); boolean remove = false;
                for (int testIndex : test) if (trainStart < timestamps.get(testIndex).end
                        && lifecycleEnd > timestamps.get(testIndex).start) { remove = true; break; }
                if (remove) { purged.add(trainIndex); continue; }
                for (int testIndex : test) if (trainStart >= timestamps.get(testIndex).end
                        && trainStart < timestamps.get(testIndex).end + embargoMs) { remove = true; break; }
                if (remove) embargoed.add(trainIndex); else retained.add(trainIndex);
            }
            if (retained.isEmpty()) continue;
            Set<String> common = null;
            for (int index : retained) {
                Set<String> keys = field(folds.get(index), "candidate_means").isObject()
                        ? fieldNames(field(folds.get(index), "candidate_means")) : Set.of();
                if (common == null) common = new HashSet<>(keys); else common.retainAll(keys);
            }
            List<String> candidateIds = common == null ? new ArrayList<>() : new ArrayList<>(common);
            candidateIds.removeIf(id -> {
                for (int index : concat(retained, test)) if (!Double.isFinite(numberJs(
                        field(field(folds.get(index), "candidate_means"), id)))) return true;
                return false;
            }); candidateIds.sort(String::compareTo);
            if (candidateIds.size() < 2 || !candidateIds.contains(selectedCandidateId)) continue;
            Map<String, Double> trainScores = scores(folds, retained, candidateIds);
            Map<String, Double> testScores = scores(folds, test, candidateIds);
            String winner = rankedCandidates(candidateIds, trainScores).get(0);
            List<String> testRanked = rankedCandidates(candidateIds, testScores); int rank = testRanked.indexOf(winner) + 1;
            double percentile = testRanked.size() > 1 ? (double) (testRanked.size() - rank) / (testRanked.size() - 1) : 1;
            double logit = Math.log(Math.max(1e-9, percentile) / Math.max(1e-9, 1 - percentile));
            valid++; if (percentile < .5) degraded++;
            ObjectNode detail = object(); detail.set("train", integers(train)); detail.set("retained_train", integers(retained));
            detail.set("test", integers(test)); detail.set("purged_train", integers(purged));
            detail.set("embargoed_train", integers(embargoed)); detail.put("train_winner", winner);
            detail.put("train_winner_train_rank", 1); detail.put("test_rank", rank);
            detail.put("test_expectancy", testScores.get(winner)); detail.put("test_percentile", percentile);
            detail.put("logit_degradation", logit); detail.put("selected_candidate_id", selectedCandidateId); details.add(detail);
        }
        ObjectNode result = object(); if (valid == 0) result.set("pbo", NullNode.instance);
        else result.put("pbo", (double) degraded / valid); result.put("combinations_total", combinations.size());
        result.put("valid_combinations", valid); result.put("degraded_combinations", degraded);
        result.put("purge_days", purgeDays); result.put("embargo_days", embargoDays);
        if (requireTimestamps) { result.put("purge_ms", purgeMs); result.put("embargo_ms", embargoMs); }
        else { result.set("purge_ms", NullNode.instance); result.set("embargo_ms", NullNode.instance); }
        result.put("method", "TIMESTAMP_PURGED_COMBINATORIAL_TRAIN_WINNER_TEST_RANK_LOGIT");
        result.set("details", details); return result;
    }

    private static ObjectNode pboFromEpisodeObservations(ArrayNode folds, String selectedCandidateId,
            double purgeDays, double embargoDays) {
        long purgeMs = (long) (purgeDays * 86_400_000d), embargoMs = (long) (embargoDays * 86_400_000d);
        List<Integer> indices = new ArrayList<>(); for (int index = 0; index < folds.size(); index++) indices.add(index);
        List<List<Integer>> combinations = chooseCombinations(indices, indices.size() / 2);
        int valid = 0, degraded = 0; ArrayNode details = array();
        for (List<Integer> trainFolds : combinations) {
            Set<Integer> trainSet = new HashSet<>(trainFolds); List<JsonNode> test = new ArrayList<>();
            List<JsonNode> sourceTrain = new ArrayList<>();
            for (int foldIndex = 0; foldIndex < folds.size(); foldIndex++) for (JsonNode row : array(field(folds.get(foldIndex), "observations"))) {
                (trainSet.contains(foldIndex) ? sourceTrain : test).add(row);
            }
            List<String> purged = new ArrayList<>(), embargoed = new ArrayList<>(); List<JsonNode> train = new ArrayList<>();
            for (JsonNode trainRow : sourceTrain) {
                long start = strictTime(field(trainRow, "decision_time"), "timestamp");
                long end = strictTime(truthy(field(trainRow, "resolution_time"))
                        ? field(trainRow, "resolution_time") : field(trainRow, "decision_time"), "timestamp");
                long lifecycleEnd = Math.max(end, start + purgeMs); boolean remove = false;
                for (JsonNode testRow : test) {
                    long testStart = strictTime(field(testRow, "decision_time"), "timestamp");
                    long testEnd = strictTime(truthy(field(testRow, "resolution_time"))
                            ? field(testRow, "resolution_time") : field(testRow, "decision_time"), "timestamp");
                    if (start < testEnd && lifecycleEnd > testStart) { remove = true; break; }
                }
                if (remove) { purged.add(text(field(trainRow, "episode_id"))); continue; }
                for (JsonNode testRow : test) {
                    long testEnd = strictTime(truthy(field(testRow, "resolution_time"))
                            ? field(testRow, "resolution_time") : field(testRow, "decision_time"), "timestamp");
                    if (start >= testEnd && start < testEnd + embargoMs) { remove = true; break; }
                }
                if (remove) embargoed.add(text(field(trainRow, "episode_id"))); else train.add(trainRow);
            }
            if (train.isEmpty() || test.isEmpty()) continue;
            List<JsonNode> panel = new ArrayList<>(train); panel.addAll(test); Set<String> union = new HashSet<>();
            panel.forEach(row -> union.addAll(fieldNames(field(row, "candidate_means"))));
            List<String> candidateIds = new ArrayList<>(union);
            candidateIds.removeIf(id -> panel.stream().anyMatch(row -> !Double.isFinite(
                    numberJs(field(field(row, "candidate_means"), id))))); candidateIds.sort(String::compareTo);
            if (candidateIds.size() < 2 || !candidateIds.contains(selectedCandidateId)) continue;
            Map<String, Double> trainScores = observationScores(train, candidateIds);
            Map<String, Double> testScores = observationScores(test, candidateIds);
            String winner = rankedCandidates(candidateIds, trainScores).get(0);
            List<String> ranked = rankedCandidates(candidateIds, testScores); int rank = ranked.indexOf(winner) + 1;
            double percentile = ranked.size() > 1 ? (double) (ranked.size() - rank) / (ranked.size() - 1) : 1;
            double logit = Math.log(Math.max(1e-9, percentile) / Math.max(1e-9, 1 - percentile));
            valid++; if (percentile < .5) degraded++; ObjectNode detail = object();
            detail.set("train_folds", integers(trainFolds)); detail.set("retained_train_episode_ids",
                    strings(train.stream().map(row -> text(field(row, "episode_id"))).toList()));
            detail.set("test_episode_ids", strings(test.stream().map(row -> text(field(row, "episode_id"))).toList()));
            detail.set("purged_train_episode_ids", sortedUniqueStrings(purged));
            detail.set("embargoed_train_episode_ids", sortedUniqueStrings(embargoed));
            detail.put("train_winner", winner); detail.put("train_winner_train_rank", 1); detail.put("test_rank", rank);
            detail.put("test_expectancy", testScores.get(winner)); detail.put("test_percentile", percentile);
            detail.put("logit_degradation", logit); detail.put("selected_candidate_id", selectedCandidateId); details.add(detail);
        }
        ObjectNode result = object(); if (valid == 0) result.set("pbo", NullNode.instance);
        else result.put("pbo", (double) degraded / valid); result.put("combinations_total", combinations.size());
        result.put("valid_combinations", valid); result.put("degraded_combinations", degraded);
        result.put("purge_days", purgeDays); result.put("embargo_days", embargoDays);
        result.put("purge_ms", purgeMs); result.put("embargo_ms", embargoMs);
        result.put("method", "EPISODE_LEVEL_PURGED_CPCV_TRAIN_WINNER_TEST_RANK_LOGIT");
        result.set("details", details); return result;
    }

    public static ObjectNode makeGeneticCheckpoint(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        ObjectNode exposure = validateExposureHead(field(options, "exposureHead"));
        ObjectNode artifactOptions = object(); artifactOptions.set("exposureHead", exposure);
        artifactOptions.put("allowSubset", true); validateStatisticalArtifactSet(field(options, "artifact"), artifactOptions);
        JsonNode artifact = field(options, "artifact"); ObjectNode space = normalizeGenes(field(options, "geneSpace"));
        double rawSeed = numberJs(field(options, "seed")), rawGeneration = numberJs(field(options, "generation"));
        if (!Double.isFinite(rawSeed) || Math.rint(rawSeed) != rawSeed || !Double.isFinite(rawGeneration)
                || Math.rint(rawGeneration) != rawGeneration || rawGeneration < 0) {
            throw failure("checkpoint seed/generation is invalid");
        }
        long seed = (long) rawSeed, generation = (long) rawGeneration;
        long seedIndex = defined(field(options, "seedIndex")) ? (long) numberJs(field(options, "seedIndex")) : 0;
        JsonNode rngState = !definedNonNull(field(options, "rngState")) ? NullNode.instance
                : JSON.numberNode(numberJs(field(options, "rngState")));
        JsonNode population = cloneNode(field(options, "population"));
        JsonNode history = field(options, "history").isArray() ? cloneNode(field(options, "history")) : array();
        JsonNode finalists = field(options, "seedFinalists").isArray()
                ? cloneNode(field(options, "seedFinalists")) : array();
        JsonNode membership = field(options, "seedMembership").isArray()
                ? cloneNode(field(options, "seedMembership")) : array();
        double plateau = defined(field(options, "plateau")) ? numberJs(field(options, "plateau")) : 0;
        String signature = defined(field(options, "paretoSignature")) ? jsString(field(options, "paretoSignature")) : "";
        String historySha = hash(history);
        ObjectNode state = object(); state.put("seedIndex", seedIndex); state.put("seed", seed);
        state.put("generation", generation); state.set("rngState", cloneNode(rngState)); state.set("population", population);
        state.put("historySha256", historySha); state.set("seedFinalists", finalists);
        state.set("seedMembership", membership); state.put("plateau", plateau); state.put("paretoSignature", signature);
        ObjectNode value = object(); value.put("schema", schema("checkpoint")); value.put("version", 1);
        value.put("artifact_lineage_sha256", hash(field(artifact, "lineage")));
        value.put("artifact_sha256", text(field(artifact, "content_sha256")));
        value.put("exposure_head_sha256", text(field(exposure, "content_sha256")));
        value.put("exposure_predecessor_sha256", text(field(exposure, "content_sha256")));
        value.put("gene_space_sha256", text(field(space, "content_sha256")));
        value.put("fold_id", jsString(field(options, "foldId"))); value.put("seed", seed);
        value.put("seed_index", seedIndex); value.put("generation", generation); value.set("rng_state", rngState);
        value.set("config", cloneNode(field(options, "config"))); value.set("population", population);
        value.set("history", history); value.put("history_sha256", historySha); value.set("seed_finalists", finalists);
        value.set("seed_membership", membership); value.put("plateau", plateau);
        value.put("pareto_signature", signature);
        value.set("previous_checkpoint_sha256", defined(field(options, "previousCheckpointSha256"))
                ? cloneNode(field(options, "previousCheckpointSha256")) : NullNode.instance);
        value.put("state_sha256", hash(state)); value.put("checkpoint_status",
                truthy(field(options, "checkpointStatus")) ? jsString(field(options, "checkpointStatus")) : "RUNNING");
        ObjectNode result = withHash(value); validateCheckpointContract(result); validateRegisteredSchema(result); return result;
    }

    public static boolean validateGeneticCheckpoint(JsonNode checkpoint, ObjectNode args) {
        ObjectNode options = args == null ? object() : args; validateCheckpointContract(checkpoint);
        JsonNode artifact = field(options, "artifact");
        if (artifact.isObject() && (!text(field(checkpoint, "artifact_lineage_sha256")).equals(hash(field(artifact, "lineage")))
                || !text(field(checkpoint, "artifact_sha256")).equals(text(field(artifact, "content_sha256"))))) {
            throw failure("checkpoint artifact lineage mismatch");
        }
        JsonNode exposure = field(options, "exposureHead");
        if (exposure.isObject() && !text(field(checkpoint, "exposure_head_sha256"))
                .equals(text(field(exposure, "content_sha256")))) {
            throw failure("checkpoint exposure predecessor is stale");
        }
        if (field(options, "geneSpace").isObject() && !text(field(checkpoint, "gene_space_sha256"))
                .equals(text(field(normalizeGenes(field(options, "geneSpace")), "content_sha256")))) {
            throw failure("checkpoint gene space mismatch");
        }
        if (defined(field(options, "foldId"))
                && !jsString(field(checkpoint, "fold_id")).equals(jsString(field(options, "foldId")))) {
            throw failure("checkpoint fold mismatch");
        }
        if (definedNonNull(field(options, "config"))
                && !stable(field(checkpoint, "config")).equals(stable(field(options, "config")))) {
            throw failure("checkpoint configuration mismatch");
        }
        return true;
    }

    public static boolean recoverStaleCheckpointLock(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        if (!field(options, "force").asBoolean(false)) {
            throw failure("stale checkpoint lock recovery requires explicit force=true");
        }
        Path target = requiredFilePath(text(field(options, "filePath")), "genetic checkpoint path");
        Path lock = Path.of(target + ".lock"); if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) return false;
        assertConfinedPath(lock, "genetic checkpoint lock path", true, target.getParent());
        long maxAge = defined(field(options, "maxAgeMs")) ? (long) numberJs(field(options, "maxAgeMs")) : 86_400_000L;
        try {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(lock, LinkOption.NOFOLLOW_LINKS).toMillis();
            if (age < maxAge) throw failure("checkpoint lock is not old enough for explicit recovery");
            Files.delete(lock); return true;
        } catch (IOException error) { throw failure(error.getMessage()); }
    }

    public static ObjectNode writeGeneticCheckpointFile(ObjectNode args) {
        ObjectNode options = args == null ? object() : args; ObjectNode checkpoint = objectOrEmpty(field(options, "checkpoint"));
        validateCheckpointContract(checkpoint);
        if (truthy(field(options, "expectedExposureHeadSha256"))
                && !text(field(options, "expectedExposureHeadSha256"))
                .equals(text(field(checkpoint, "exposure_predecessor_sha256")))) {
            throw failure("checkpoint expected exposure predecessor mismatch");
        }
        Path target = requiredFilePath(text(field(options, "filePath")), "genetic checkpoint path");
        Path directory = target.getParent(); assertConfinedPath(target, "genetic checkpoint path", false, directory);
        Path lock = Path.of(target + ".lock"); Path journal = Path.of(target + ".jsonl"); FileChannel channel = null;
        try {
            ensureParent(target); assertConfinedPath(lock, "genetic checkpoint lock path", false, directory);
            assertConfinedPath(journal, "genetic checkpoint journal path", false, directory);
            if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                assertConfinedPath(journal, "genetic checkpoint journal path", true, directory);
            }
            channel = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            ObjectNode existing = null;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                assertConfinedPath(target, "genetic checkpoint path", true, directory);
                existing = readGeneticCheckpointFile(target);
                if (text(field(existing, "content_sha256")).equals(text(field(checkpoint, "content_sha256")))) return existing;
                if (truthy(field(options, "expectedCheckpointSha256"))
                        && !text(field(options, "expectedCheckpointSha256"))
                        .equals(text(field(existing, "content_sha256")))) {
                    throw failure("stale or competing checkpoint predecessor");
                }
                if (!truthy(field(options, "expectedCheckpointSha256"))
                        && (!text(field(existing, "exposure_head_sha256"))
                        .equals(text(field(checkpoint, "exposure_head_sha256")))
                        || integer(field(existing, "generation"), -1) >= integer(field(checkpoint, "generation"), -1))) {
                    throw failure("checkpoint append is stale or competing");
                }
                if (!text(field(checkpoint, "previous_checkpoint_sha256"))
                        .equals(text(field(existing, "content_sha256")))) {
                    throw failure("stale checkpoint chain predecessor");
                }
            } else if (!field(checkpoint, "previous_checkpoint_sha256").isNull()) {
                throw failure("checkpoint cannot start from a non-null predecessor");
            }
            writeAtomicJson(target, checkpoint);
            ObjectNode receipt = object(); receipt.put("schema", "strategy-v5-statistical-genetic-checkpoint-receipt/1");
            for (String key : List.of("checkpoint_sha256", "previous_checkpoint_sha256", "state_sha256",
                    "exposure_head_sha256", "fold_id", "seed", "seed_index", "generation", "checkpoint_status")) {
                String source = "checkpoint_sha256".equals(key) ? "content_sha256" : key;
                receipt.set(key, cloneNode(field(checkpoint, source)));
            }
            Files.writeString(journal, MAPPER.writeValueAsString(receipt) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND); return checkpoint;
        } catch (java.nio.file.FileAlreadyExistsException error) {
            throw failure("competing checkpoint writer is active");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) { throw failure(error.getMessage()); }
        finally {
            if (channel != null) try { channel.close(); } catch (IOException ignored) {}
            try { Files.deleteIfExists(lock); } catch (IOException ignored) {}
        }
    }

    public static ObjectNode readGeneticCheckpointFile(String filePath) {
        Path target = requiredFilePath(filePath, "genetic checkpoint path");
        assertConfinedPath(target, "genetic checkpoint path", true, target.getParent());
        try {
            ObjectNode checkpoint = objectOrEmpty(MAPPER.readTree(Files.readAllBytes(target)));
            validateCheckpointContract(checkpoint); return checkpoint;
        } catch (IOException error) { throw failure(error.getMessage()); }
    }

    public static ObjectNode readGeneticCheckpointFile(Path filePath) {
        return readGeneticCheckpointFile(filePath == null ? null : filePath.toString());
    }

    private record FoldInterval(int index, Long start, Long end) {}
    private record MarketInterval(String id, String asset, long start, long end) {}
    private record Violation(ObjectNode details, double total) {}

    private static Violation normalizedViolation(JsonNode metrics, JsonNode policy) {
        double minEpisodes = threshold(policy, "minEpisodes", ((Number) STAT_DEFAULTS.get("minEpisodes")).doubleValue());
        double minExpectancy = threshold(policy, "minExpectancy", 0);
        double minProfitFactor = threshold(policy, "minProfitFactor", 1);
        double maxDrawdown = threshold(policy, "maxDrawdownR", Double.POSITIVE_INFINITY);
        double maxCost = threshold(policy, "maxCostR", Double.POSITIVE_INFINITY);
        double minCoverage = threshold(policy, "minCoverage", .95);
        JsonNode configured = truthy(field(policy, "violationScales"))
                ? field(policy, "violationScales") : field(policy, "violation_scales");
        double episodesScale = scale(configured, "episodes", Math.max(1, minEpisodes));
        double expectancyScale = scale(configured, "expectancy", Math.max(.01, Math.abs(minExpectancy)));
        double drawdownScale = scale(configured, "drawdown", Math.max(.01, Math.abs(maxDrawdown)));
        double costsScale = scale(configured, "costs", Math.max(.01, Math.abs(maxCost)));
        double coverageScale = scale(configured, "coverage", Math.max(.01, Math.abs(minCoverage)));
        double capacityScale = scale(configured, "capacity", 1);
        double profitScale = scale(configured, "profit_factor", Math.max(.01, Math.abs(minProfitFactor)));
        ObjectNode details = object();
        double traded = metric(metrics, "traded_count");
        double expectancy = metric(metrics, "expectancy_r");
        double drawdown = metric(metrics, "max_drawdown_r");
        double cost = metric(metrics, "cost_r");
        double coverage = metric(metrics, "coverage_fraction");
        double profit = metric(metrics, "profit_factor");
        details.put("episodes", Double.isFinite(traded) ? positiveGap(minEpisodes - traded, episodesScale) : 1);
        details.put("expectancy", Double.isFinite(expectancy) ? positiveGap(minExpectancy - expectancy, expectancyScale) : 1);
        details.put("drawdown", Double.isFinite(drawdown) ? positiveGap(Math.abs(drawdown) - maxDrawdown, drawdownScale) : 1);
        details.put("costs", Double.isFinite(cost) ? positiveGap(cost - maxCost, costsScale) : 1);
        details.put("coverage", Double.isFinite(coverage) ? Math.max(positiveGap(minCoverage - coverage, coverageScale),
                positiveGap(coverage - 1, coverageScale)) : 1);
        details.put("capacity", field(metrics, "capacity_pass").asBoolean(false) ? 0 : positiveGap(1, capacityScale));
        details.put("profit_factor", Double.isFinite(profit) ? positiveGap(minProfitFactor - profit, profitScale) : 1);
        double total = 0; for (JsonNode value : details) total += value.asDouble();
        return new Violation(details, total);
    }

    private static double metric(JsonNode metrics, String key) {
        return definedNonNull(field(metrics, key)) ? numberJs(field(metrics, key)) : Double.NaN;
    }
    private static double threshold(JsonNode policy, String key, double fallback) {
        return definedNonNull(field(policy, key)) ? numberJs(field(policy, key)) : fallback;
    }
    private static double scale(JsonNode scales, String key, double fallback) {
        return definedNonNull(field(scales, key)) ? numberJs(field(scales, key)) : fallback;
    }
    private static double positiveGap(double gap, double scale) {
        return Double.isFinite(gap) && gap > 0 ? gap / Math.max(Math.ulp(1d), Math.abs(scale)) : 0;
    }

    private static List<List<Integer>> chooseCombinations(List<Integer> values, int size) {
        List<List<Integer>> output = new ArrayList<>(); chooseCombinations(values, size, 0, new ArrayList<>(), output);
        return output;
    }

    private static void chooseCombinations(List<Integer> values, int size, int start,
            List<Integer> chosen, List<List<Integer>> output) {
        if (chosen.size() == size) { output.add(List.copyOf(chosen)); return; }
        for (int index = start; index < values.size(); index++) {
            chosen.add(values.get(index)); chooseCombinations(values, size, index + 1, chosen, output);
            chosen.remove(chosen.size() - 1);
        }
    }

    private static Set<String> fieldNames(JsonNode value) {
        Set<String> output = new LinkedHashSet<>();
        if (value != null && value.isObject()) value.fieldNames().forEachRemaining(output::add); return output;
    }

    private static List<Integer> concat(List<Integer> left, List<Integer> right) {
        List<Integer> result = new ArrayList<>(left); result.addAll(right); return result;
    }

    private static Map<String, Double> scores(ArrayNode folds, List<Integer> indices, List<String> ids) {
        Map<String, Double> output = new LinkedHashMap<>();
        for (String id : ids) {
            List<Double> values = new ArrayList<>();
            for (int index : indices) values.add(numberJs(field(field(folds.get(index), "candidate_means"), id)));
            output.put(id, mean(values));
        }
        return output;
    }

    private static Map<String, Double> observationScores(List<JsonNode> rows, List<String> ids) {
        Map<String, Double> output = new LinkedHashMap<>();
        for (String id : ids) {
            List<Double> values = new ArrayList<>(); rows.forEach(row -> values.add(
                    numberJs(field(field(row, "candidate_means"), id)))); output.put(id, mean(values));
        }
        return output;
    }

    private static List<String> rankedCandidates(List<String> ids, Map<String, Double> scores) {
        List<String> result = new ArrayList<>(ids);
        result.sort((left, right) -> {
            int score = Double.compare(scores.get(right), scores.get(left)); return score != 0 ? score : left.compareTo(right);
        }); return result;
    }

    private static ArrayNode integers(Collection<Integer> values) {
        ArrayNode result = array(); values.forEach(result::add); return result;
    }

    private static ArrayNode sortedUniqueStrings(Collection<String> values) {
        List<String> result = new ArrayList<>(new HashSet<>(values)); result.sort(String::compareTo); return strings(result);
    }

    private static double mean(Collection<Double> values) {
        if (values.isEmpty()) return 0;
        double total = 0; for (double value : values) total += value; return total / values.size();
    }

    private static double lagOneAutocorrelation(List<Double> values) {
        if (values.size() < 4) return 0;
        double center = mean(values);
        double denominator = 0;
        for (double value : values) denominator += Math.pow(value - center, 2);
        if (!(denominator > 0)) return 0;
        double numerator = 0;
        for (int index = 1; index < values.size(); index++) {
            numerator += (values.get(index) - center) * (values.get(index - 1) - center);
        }
        return numerator / denominator;
    }

    private static double normalCdf(double value) {
        double x = value / Math.sqrt(2); double sign = x < 0 ? -1 : 1; double absolute = Math.abs(x);
        double t = 1 / (1 + .3275911 * absolute);
        double polynomial = (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                - .284496736) * t + .254829592) * t;
        double erf = sign * (1 - polynomial * Math.exp(-absolute * absolute));
        return .5 * (1 + erf);
    }

    private static double normalQuantile(double probability) {
        double p = Math.min(1 - 1e-12, Math.max(1e-12, probability)); double low = -9, high = 9;
        for (int index = 0; index < 80; index++) {
            double mid = (low + high) / 2;
            if (normalCdf(mid) < p) low = mid; else high = mid;
        }
        return (low + high) / 2;
    }

    private static long addUtcMonths(long epochMillis, long months) {
        ZonedDateTime source = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC);
        return source.plusMonths(months).toInstant().toEpochMilli();
    }

    private static ObjectNode normalizeGenes(JsonNode space) {
        ArrayNode rawGenes = array(field(space, "genes"));
        if (!space.isObject() || rawGenes.isEmpty()) throw failure("gene space is required");
        Set<String> names = new HashSet<>(); ArrayNode genes = array();
        for (int index = 0; index < rawGenes.size(); index++) {
            JsonNode raw = rawGenes.get(index); String name = truthy(field(raw, "name"))
                    ? jsString(field(raw, "name")) : "gene_" + (index + 1);
            if (!names.add(name)) throw failure("duplicate gene " + name);
            String type = jsString(field(raw, "type")).toLowerCase(Locale.ROOT);
            if (!Set.of("continuous", "ordered-discrete", "categorical", "structural").contains(type)) {
                throw failure("unsupported gene type " + type);
            }
            ObjectNode gene = object(); gene.put("name", name); gene.put("type", type);
            if ("continuous".equals(type)) {
                double min = finiteNumber(field(raw, "min"), name + ".min");
                double max = finiteNumber(field(raw, "max"), name + ".max");
                if (max <= min) throw failure(name + " range is invalid");
                Double step = defined(field(raw, "step")) ? finiteNumber(field(raw, "step"), name + ".step") : null;
                if (step != null && step <= 0) throw failure(name + ".step must be positive");
                double defaultValue = definedNonNull(field(raw, "default"))
                        ? finiteNumber(field(raw, "default"), name + ".default") : min;
                if (defaultValue < min || defaultValue > max) throw failure(name + ".default is outside range");
                gene.put("min", min); gene.put("max", max);
                if (step == null) gene.set("step", NullNode.instance); else gene.put("step", step);
                gene.put("default", defaultValue);
            } else {
                ArrayNode values = array(field(raw, "values"));
                if (values.isEmpty()) throw failure(name + " has no values");
                Set<String> unique = new HashSet<>();
                for (JsonNode item : values) if (!unique.add(stable(item))) {
                    throw failure(name + " values must be unique");
                }
                if ("ordered-discrete".equals(type)) {
                    double previous = Double.NEGATIVE_INFINITY;
                    for (JsonNode item : values) {
                        double current = numberJs(item);
                        if (!Double.isFinite(current)) throw failure(name + ".values must be finite numbers");
                        if (current <= previous) throw failure(name + ".values must be strictly ordered");
                        previous = current;
                    }
                }
                if ("structural".equals(type)) for (JsonNode item : values) if (!item.isObject()) {
                    throw failure(name + ".structural values must be objects");
                }
                JsonNode defaultValue = defined(field(raw, "default")) ? field(raw, "default") : values.get(0);
                boolean found = false;
                for (JsonNode item : values) if (stable(item).equals(stable(defaultValue))) { found = true; break; }
                if (!found) throw failure(name + ".default must be one of values");
                gene.set("values", values.deepCopy()); gene.set("default", cloneNode(defaultValue));
            }
            gene.put("usage", truthy(field(raw, "usage")) ? jsString(field(raw, "usage")) : ""); genes.add(gene);
        }
        ObjectNode result = object(); result.put("schema", "strategy-v5-statistical-gene-space/1");
        result.set("genes", genes); return withHash(result);
    }

    private static ObjectNode chromosome(ObjectNode space, JsonNode rawValue) {
        ObjectNode value = rawValue != null && rawValue.isObject() ? objectOrEmpty(rawValue) : object();
        ObjectNode output = object();
        for (JsonNode gene : array(field(space, "genes"))) {
            String name = text(field(gene, "name")); JsonNode input = defined(field(value, name))
                    ? field(value, name) : field(gene, "default");
            output.set(name, quantize(input, gene));
        }
        return output;
    }

    private static JsonNode quantize(JsonNode rawValue, JsonNode gene) {
        String type = text(field(gene, "type"));
        if ("continuous".equals(type)) {
            double min = numberJs(field(gene, "min")), max = numberJs(field(gene, "max"));
            double output = Math.min(max, Math.max(min, numberJs(rawValue)));
            if (definedNonNull(field(gene, "step"))) {
                double step = numberJs(field(gene, "step")); output = min + Math.round((output - min) / step) * step;
            }
            output = Math.floor(output * 10_000_000_000d + .5d) / 10_000_000_000d;
            return JSON.numberNode(output);
        }
        ArrayNode values = array(field(gene, "values"));
        if ("ordered-discrete".equals(type)) {
            JsonNode best = values.get(0); double input = numberJs(rawValue);
            for (JsonNode item : values) if (Math.abs(numberJs(item) - input) < Math.abs(numberJs(best) - input)) best = item;
            return cloneNode(best);
        }
        String serialized = stable(rawValue);
        for (JsonNode item : values) if (stable(item).equals(serialized)) return cloneNode(item);
        return cloneNode(values.get(0));
    }

    private static ArrayNode neighbours(ObjectNode space, JsonNode rawValue) {
        ObjectNode base = chromosome(space, rawValue); ArrayNode output = array();
        for (JsonNode gene : array(field(space, "genes"))) {
            String name = text(field(gene, "name")); String type = text(field(gene, "type"));
            if ("continuous".equals(type)) {
                double delta = definedNonNull(field(gene, "step")) ? numberJs(field(gene, "step"))
                        : (numberJs(field(gene, "max")) - numberJs(field(gene, "min"))) / 20;
                for (int sign : new int[] {-1, 1}) {
                    ObjectNode next = base.deepCopy();
                    next.set(name, quantize(JSON.numberNode(numberJs(field(base, name)) + sign * delta), gene));
                    if (!stable(next).equals(stable(base))) output.add(next);
                }
            } else if ("ordered-discrete".equals(type)) {
                ArrayNode values = array(field(gene, "values")); int current = -1;
                for (int index = 0; index < values.size(); index++) if (stable(values.get(index)).equals(stable(field(base, name)))) {
                    current = index; break;
                }
                for (int index : new int[] {current - 1, current + 1}) if (index >= 0 && index < values.size()) {
                    ObjectNode next = base.deepCopy(); next.set(name, cloneNode(values.get(index))); output.add(next);
                }
            }
        }
        return output;
    }

    private record CandidateInventory(Set<String> ids, Set<String> behaviors) {}

    private static CandidateInventory validateCandidateRows(JsonNode candidates, ObjectNode exposureHead,
            boolean allowEmpty) {
        if (!candidates.isArray() || (!allowEmpty && candidates.isEmpty())) {
            throw failure("statistical artifact requires candidates");
        }
        Set<String> ids = new LinkedHashSet<>();
        Set<String> behaviors = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode row : candidates) {
            if (!row.isObject()) throw failure("candidate " + index + " is not an object");
            String id = text(field(row, "candidate_id"));
            if (!field(row, "candidate_id").isTextual() || id.isEmpty() || !ids.add(id)) {
                throw failure("candidate IDs must be unique strings");
            }
            String behavior = requireHash(field(row, "behavior_sha256"),
                    "candidate " + id + ".behavior_sha256");
            if (!behaviors.add(behavior)) {
                throw failure("candidate behavior aliases must be unique in the current candidate set");
            }
            index++;
        }
        if (exposureHead != null) {
            Set<String> exposed = new HashSet<>();
            array(field(exposureHead, "entries")).forEach(row -> exposed.add(text(field(row, "behavior_sha256"))));
            for (String behavior : behaviors) if (!exposed.contains(behavior)) {
                throw failure("candidate " + behavior + " is absent from the verified exposure head");
            }
        }
        return new CandidateInventory(Set.copyOf(ids), Set.copyOf(behaviors));
    }

    private static Set<String> validateEpisodeRows(JsonNode episodes, Set<String> candidateIds,
            boolean requireComplete) {
        if (!episodes.isArray() || episodes.isEmpty()) {
            throw failure("statistical artifact requires canonical episode records");
        }
        List<JsonNode> ordered = new ArrayList<>(); episodes.forEach(ordered::add);
        ordered.sort(Comparator.comparingLong((JsonNode row) -> strictTime(field(row, "decision_time"), "decision_time"))
                .thenComparing((JsonNode row) -> text(field(row, "episode_id"))));
        ArrayNode normalized = array(); ordered.forEach(normalized::add);
        if (!stable(episodes).equals(stable(normalized))) throw failure("episode records must be chronologically ordered");
        Set<String> ids = new LinkedHashSet<>();
        Map<String, Long> lastResolution = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode row : episodes) {
            if (!row.isObject()) throw failure("episode " + index + " is not an object");
            String id = text(field(row, "episode_id"));
            if (id.isEmpty() || !ids.add(id)) throw failure("duplicate episode_id " + id);
            String asset = asset(field(row, "asset"));
            if (!asset.equals(text(field(row, "asset")))) {
                throw failure("episode " + id + ".asset must be lowercase canonical crypto");
            }
            long decision = strictTime(field(row, "decision_time"), "episode " + id + ".decision_time");
            long resolution = strictTime(field(row, "resolution_time"), "episode " + id + ".resolution_time");
            if (defined(field(row, "label_availability_time"))) strictTime(field(row, "label_availability_time"),
                    "episode " + id + ".label_availability_time");
            if (defined(field(row, "execution_availability_time"))) strictTime(
                    field(row, "execution_availability_time"), "episode " + id + ".execution_availability_time");
            if (resolution <= decision) throw failure("episode " + id + " resolution must follow decision");
            if (!field(row, "eligible").isBoolean()) throw failure("episode " + id + ".eligible must be boolean");
            if (field(row, "eligible").asBoolean() && lastResolution.containsKey(asset)
                    && decision < lastResolution.get(asset)) {
                throw failure("overlapping eligible episodes for " + asset);
            }
            if (field(row, "eligible").asBoolean()) lastResolution.put(asset, resolution);
            JsonNode returns = field(row, "candidate_returns");
            if (!returns.isObject()) throw failure("episode " + id + " candidate_returns must be an object");
            List<String> keys = new ArrayList<>(); returns.fieldNames().forEachRemaining(keys::add); keys.sort(String::compareTo);
            List<String> expected = new ArrayList<>(candidateIds); expected.sort(String::compareTo);
            if (!keys.equals(expected)) throw failure("episode " + id + " candidate return inventory is incomplete or has extras");
            for (String candidateId : candidateIds) {
                JsonNode ret = field(returns, candidateId);
                if (!ret.isObject() || !field(ret, "traded").isBoolean()) {
                    throw failure("episode " + id + "/" + candidateId + " return record is incomplete");
                }
                double net = finiteNumber(field(ret, "net_r"), "episode " + id + "/" + candidateId + ".net_r");
                if (!field(row, "eligible").asBoolean() && (field(ret, "traded").asBoolean() || net != 0)) {
                    throw failure("ineligible episode " + id + " must be an internal zero");
                }
                if (requireComplete && field(row, "eligible").asBoolean()
                        && !field(ret, "traded").asBoolean() && net != 0) {
                    throw failure("untraded eligible episode " + id + " must have zero return");
                }
            }
            index++;
        }
        return Set.copyOf(ids);
    }

    private static ArrayNode normalizeSignalIntentVector(ArrayNode ordered, JsonNode rawVector) {
        ArrayNode rows;
        if (rawVector.isArray()) rows = (ArrayNode) rawVector;
        else if (rawVector.isObject()) {
            rows = array();
            for (JsonNode id : ordered) {
                ObjectNode row = object(); row.set("episode_id", cloneNode(id));
                row.set("intent", cloneNode(field(rawVector, jsString(id)))); rows.add(row);
            }
        } else throw failure("signal intent vector must cover the evaluation scope");
        if (rows.size() != ordered.size()) throw failure("signal intent vector must cover the evaluation scope");
        Set<String> allowedIds = new HashSet<>(); ordered.forEach(value -> allowedIds.add(jsString(value)));
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        for (JsonNode raw : rows) {
            String id = text(field(raw, "episode_id"));
            if (!raw.isObject() || byId.containsKey(id) || !allowedIds.contains(id)) {
                throw failure("signal intent vector has duplicate or unknown episode");
            }
            List<String> keys = new ArrayList<>(); raw.fieldNames().forEachRemaining(keys::add);
            if (keys.stream().anyMatch(key -> !Set.of("episode_id", "intent").contains(key))) {
                throw failure("signal intent vector contains outcome fields");
            }
            JsonNode intent = field(raw, "intent");
            double number = numberJs(intent);
            if (!intent.isBoolean() && !Double.isFinite(number)) {
                throw failure("signal intent must be boolean or finite numeric");
            }
            ObjectNode row = object(); row.put("episode_id", id);
            if (intent.isBoolean()) row.put("intent", intent.asBoolean()); else row.put("intent", number);
            byId.put(id, row);
        }
        if (byId.size() != ordered.size()) throw failure("signal intent vector is incomplete");
        ArrayNode output = array(); ordered.forEach(id -> output.add(byId.get(jsString(id))));
        return output;
    }

    private static JsonNode stripIneffective(JsonNode value) {
        if (value.isArray()) {
            ArrayNode output = array(); value.forEach(child -> output.add(stripIneffective(child))); return output;
        }
        if (!value.isObject()) return cloneNode(value);
        if (field(value, "active").isBoolean() && !field(value, "active").asBoolean()
                || field(value, "effective").isBoolean() && !field(value, "effective").asBoolean()
                || field(value, "inactive").asBoolean(false)
                || field(value, "used_for_execution").isBoolean() && !field(value, "used_for_execution").asBoolean()) {
            return MissingNode.getInstance();
        }
        ObjectNode output = object();
        value.fields().forEachRemaining(entry -> {
            if (Pattern.compile("(^|_)(inactive|unused|search_only|diagnostic|non_effective)($|_)",
                    Pattern.CASE_INSENSITIVE).matcher(entry.getKey()).find()) return;
            JsonNode child = stripIneffective(entry.getValue());
            if (!child.isMissingNode()) output.set(entry.getKey(), child);
        });
        return output;
    }

    private static ObjectNode normalizedBehaviorContracts(JsonNode candidateDefinition, JsonNode supplied) {
        if (definedNonNull(supplied)) {
            if (!supplied.isObject()) throw failure("behavior contracts must be an object");
            for (String key : List.of("signal_semantics_sha256", "evaluator_sha256", "predictor_sha256",
                    "lifecycle_sha256")) requireHash(field(supplied, key), "behavior_contracts." + key);
            if (definedNonNull(field(supplied, "precommit_sha256"))) {
                requireHash(field(supplied, "precommit_sha256"), "behavior_contracts.precommit_sha256");
            }
            ObjectNode output = object();
            for (String key : List.of("signal_semantics_sha256", "evaluator_sha256", "predictor_sha256",
                    "lifecycle_sha256")) output.put(key, text(field(supplied, key)));
            output.set("precommit_sha256", definedNonNull(field(supplied, "precommit_sha256"))
                    ? cloneNode(field(supplied, "precommit_sha256")) : NullNode.instance);
            return output;
        }
        ObjectNode semantics = object(); semantics.put("schema", "strategy-v5-fixture-behavior-semantics/1");
        semantics.set("definition", effectiveExecutionBehavior(candidateDefinition));
        ObjectNode output = object();
        output.put("signal_semantics_sha256", hash("FIXTURE_SIGNAL_SEMANTICS"));
        output.put("evaluator_sha256", hash("FIXTURE_EVALUATOR"));
        output.put("predictor_sha256", hash("FIXTURE_PREDICTORS"));
        output.put("lifecycle_sha256", hash(semantics)); output.set("precommit_sha256", NullNode.instance);
        return output;
    }

    private static String behaviorDefinitionSha256(JsonNode value) {
        JsonNode evaluator = truthy(field(value, "evaluatorSha256"))
                ? field(value, "evaluatorSha256") : field(value, "evaluator_sha256");
        JsonNode precommit = defined(field(value, "precommitSha256")) ? field(value, "precommitSha256")
                : defined(field(value, "precommit_sha256")) ? field(value, "precommit_sha256") : NullNode.instance;
        JsonNode lifecycle = defined(field(value, "lifecycleSha256")) ? field(value, "lifecycleSha256")
                : defined(field(value, "lifecycle_sha256")) ? field(value, "lifecycle_sha256") : NullNode.instance;
        ObjectNode definition = object(); definition.put("schema", "strategy-v5-statistical-behavior-definition/1");
        definition.set("chromosome", effectiveExecutionBehavior(field(value, "chromosome")));
        definition.set("evaluator_sha256", cloneNode(evaluator));
        definition.set("precommit_sha256", defined(precommit) ? cloneNode(precommit) : NullNode.instance);
        definition.set("lifecycle_sha256", defined(lifecycle) ? cloneNode(lifecycle) : NullNode.instance);
        return hash(definition);
    }

    private static void assertLineage(JsonNode lineage, String label) {
        if (!lineage.isObject()) throw failure(label + " must be an object");
        for (String key : List.of("dataset_sha256", "candidate_set_sha256", "feature_set_sha256",
                "label_set_sha256", "execution_set_sha256")) requireHash(field(lineage, key), label + "." + key);
    }

    private static final Set<String> LOOSE_RETURN_FIELDS = Set.of("returns", "episode_returns", "metrics", "pnl",
            "net_pnl", "expectancy_r", "bootstrap_p20", "pass", "active", "candidate_pass", "asset_decision",
            "portfolio_decision", "selection", "selected", "trades", "fills", "stress", "portfolio", "wfo",
            "risk", "cumulative_vectors_bound");

    private static void assertNoLooseReturns(JsonNode value, String path) {
        if (value == null || value.isMissingNode() || value.isNull()) return;
        if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) assertNoLooseReturns(value.get(index), path + "[" + index + "]");
            return;
        }
        if (!value.isObject()) return;
        value.fields().forEachRemaining(entry -> {
            if (LOOSE_RETURN_FIELDS.contains(entry.getKey())) {
                throw failure(path + "." + entry.getKey() + " caller-supplied statistical field is not accepted");
            }
            assertNoLooseReturns(entry.getValue(), path + "." + entry.getKey());
        });
    }

    private static ObjectNode finalizeExposureHead(ObjectNode value) {
        ObjectNode result = withHash(value);
        validateExposureHead(result);
        validateRegisteredSchema(result);
        return result;
    }

    private static void validateRegisteredSchema(JsonNode value) {
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(value);
    }

    private static void assertOwnHash(JsonNode value, String schema, String label) {
        if (!value.isObject() || !schema.equals(text(field(value, "schema")))
                || !text(field(value, "content_sha256")).equals(ownHash(value))) {
            throw failure(label + " is missing or hash-tampered");
        }
    }

    private static void assertKnownKeys(JsonNode value, Set<String> allowed, String label) {
        if (!value.isObject()) throw failure(label + " contains unknown caller fields: ?");
        List<String> unknown = new ArrayList<>();
        value.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) unknown.add(key); });
        if (!unknown.isEmpty()) throw failure(label + " contains unknown caller fields: " + String.join(",", unknown));
    }

    private static String requireHash(JsonNode value, String label) {
        String raw = jsString(value);
        if (!HASH_RE.matcher(raw).matches()) throw failure(label + " must be a SHA-256 hash");
        return raw;
    }

    private static String iso(JsonNode value, String label) {
        String raw = jsString(value);
        if (!ISO_RE.matcher(raw).matches()) throw failure(label + " must be an ISO-8601 UTC timestamp");
        try { return JS_ISO.format(Instant.parse(raw)); }
        catch (DateTimeParseException error) { throw failure(label + " is not a valid timestamp"); }
    }

    private static long strictTime(JsonNode value, String label) {
        String raw = jsString(value);
        if (!ISO_RE.matcher(raw).matches()) throw failure(label + " must be an ISO-8601 UTC timestamp");
        try { return Instant.parse(raw).toEpochMilli(); }
        catch (DateTimeParseException error) { throw failure(label + " is not a valid timestamp"); }
    }

    private static double finiteNumber(JsonNode value, String label) {
        double result = numberJs(value);
        if (!Double.isFinite(result)) throw failure(label + " must be finite");
        return result;
    }

    private static double numberJs(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return 0;
        if (value.isBoolean()) return value.asBoolean() ? 1 : 0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isTextual()) {
            String raw = value.asText().trim();
            if (raw.isEmpty()) return 0;
            try { return Double.parseDouble(raw); } catch (NumberFormatException ignored) { return Double.NaN; }
        }
        return Double.NaN;
    }

    private static String asset(JsonNode value) {
        String normalized = jsString(value).toLowerCase(Locale.ROOT);
        if (!ASSETS.contains(normalized)) throw failure("asset " + (normalized.isEmpty() ? "?" : normalized)
                + " is outside the crypto universe");
        return normalized;
    }

    private static String schema(String key) { return STAT_SCHEMA.get(key); }

    private static Path requiredFilePath(String rawPath, String label) {
        if (rawPath == null || rawPath.isEmpty()) throw failure(label + " is required");
        if (rawPath.indexOf('\0') >= 0) throw failure(label + " contains NUL");
        return Path.of(rawPath).toAbsolutePath().normalize();
    }

    private static void ensureParent(Path target) throws IOException {
        Path parent = target.getParent(); if (parent != null) Files.createDirectories(parent);
    }

    private static byte[] jsonLine(JsonNode value) throws JsonProcessingException {
        byte[] json = MAPPER.writeValueAsBytes(value); byte[] bytes = new byte[json.length + 1];
        System.arraycopy(json, 0, bytes, 0, json.length); bytes[json.length] = '\n'; return bytes;
    }

    private static void writeExclusiveJson(Path target, JsonNode value) throws IOException {
        ensureParent(target);
        Path temporary = Path.of(target + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        try {
            Files.write(temporary, jsonLine(value), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.createLink(target, temporary);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void writeExclusiveBytes(Path target, byte[] bytes) throws IOException {
        ensureParent(target);
        Path temporary = Path.of(target + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.createLink(target, temporary);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void writeAtomicJson(Path target, JsonNode value) throws IOException {
        ensureParent(target);
        Path temporary = Path.of(target + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        try {
            Files.write(temporary, jsonLine(value), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Path assertConfinedPath(Path rawTarget, String label, boolean requireFile, Path rawBoundary) {
        Path target = rawTarget.toAbsolutePath().normalize();
        Path boundary = rawBoundary.toAbsolutePath().normalize();
        if (Files.exists(boundary, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(boundary)) throw failure(label + " contains a symlink path component");
            if (!Files.isDirectory(boundary, LinkOption.NOFOLLOW_LINKS)) throw failure(label + " parent is not a directory");
        }
        if (target.equals(boundary) || !target.startsWith(boundary)) throw failure(label + " escapes its confined root");
        Path cursor = boundary;
        for (Path component : boundary.relativize(target)) {
            cursor = cursor.resolve(component);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) break;
            if (Files.isSymbolicLink(cursor)) throw failure(label + " contains a symlink path component");
            if (!cursor.equals(target) && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(label + " parent is not a directory");
            }
            if (cursor.equals(target) && requireFile) requireRegularSingleLink(cursor, label);
        }
        return target;
    }

    private static void requireRegularSingleLink(Path target, String label) {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw failure(label + " must be a regular single-link file");
        }
        try {
            Object count = Files.getAttribute(target, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (!(count instanceof Number number) || number.longValue() != 1) {
                throw failure(label + " must be a regular single-link file");
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems cannot expose a link count; regular/no-follow checks remain binding.
        } catch (IOException error) {
            throw failure(label + " must be a regular single-link file");
        }
    }

    private static ObjectNode behaviorRegistrySemantic(JsonNode registry) {
        ObjectNode result = object();
        for (String key : List.of("schema", "version", "status", "hypothesis_family", "exposure_head_sha256")) {
            result.set(key, cloneNode(field(registry, key)));
        }
        result.set("entries", cloneNode(field(registry, "entries"))); return result;
    }

    private static long integer(JsonNode value, long fallback) {
        if (value == null || !value.isIntegralNumber()) return fallback;
        return value.longValue();
    }

    private static long integerFromNumber(JsonNode value, long fallback) {
        double number = numberJs(value);
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Long.MIN_VALUE || number > Long.MAX_VALUE) return fallback;
        return (long) number;
    }

    private static boolean truthy(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return false;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue());
        if (value.isTextual()) return !value.textValue().isEmpty();
        return true;
    }

    private static boolean containsText(JsonNode value, String expected) {
        if (!value.isArray()) return false;
        for (JsonNode item : value) if (expected.equals(jsString(item))) return true; return false;
    }

    private static boolean defined(JsonNode value) { return value != null && !value.isMissingNode(); }
    private static boolean definedNonNull(JsonNode value) { return defined(value) && !value.isNull(); }

    private static JsonNode field(JsonNode value, String key) {
        if (value == null || !value.isObject()) return MissingNode.getInstance();
        JsonNode result = value.get(key);
        return result == null ? MissingNode.getInstance() : result;
    }

    private static String text(JsonNode value) { return value != null && value.isTextual() ? value.textValue() : ""; }

    private static String jsString(JsonNode value) {
        if (value == null || value.isMissingNode()) return "undefined";
        if (value.isNull()) return "null";
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean() || value.isNumber()) return value.asText();
        return value.isArray() ? joinArray(value) : "[object Object]";
    }

    private static String joinArray(JsonNode value) {
        List<String> rows = new ArrayList<>();
        value.forEach(child -> rows.add(child.isNull() ? "" : jsString(child)));
        return String.join(",", rows);
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static ArrayNode array() { return MAPPER.createArrayNode(); }
    private static ArrayNode strings(Collection<String> values) {
        ArrayNode output = array(); values.forEach(output::add); return output;
    }
    private static ArrayNode array(JsonNode value) { return value != null && value.isArray() ? (ArrayNode) value : array(); }
    private static ObjectNode objectOrEmpty(JsonNode value) { return value != null && value.isObject() ? (ObjectNode) value : object(); }
    private static ArrayNode requireArray(JsonNode value, String label) {
        if (value == null || !value.isArray()) throw failure(label + " is required");
        return (ArrayNode) value;
    }
    private static JsonNode cloneNode(JsonNode value) {
        return value == null || value.isMissingNode() ? NullNode.instance : value.deepCopy();
    }
    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }
}
