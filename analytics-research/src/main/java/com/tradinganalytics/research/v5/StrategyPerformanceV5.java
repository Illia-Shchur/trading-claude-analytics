package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PhysicalEvaluatorTrustRegistry;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Java port of {@code tools/strategy-research-v5-performance.mjs}.
 *
 * <p>The implementation intentionally keeps the artifact boundary JSON based.
 * This makes hashes, nulls, and field aliases byte-for-byte comparable with
 * the Node implementation while allowing the callback parts of the scope
 * cache to be expressed as typed Java functions.</p>
 */
public final class StrategyPerformanceV5 {
    public static final String SCOPE_VECTOR_BINDING_SCHEMA = "strategy-v5-scope-vector-binding/1";
    public static final String SCOPE_VECTOR_KEY_SCHEMA = "strategy-v5-scope-vector-key/1";
    public static final String SCOPE_VECTOR_CACHE_ENTRY_SCHEMA = "strategy-v5-scope-vector-cache-entry/2";
    public static final String SCOPE_VECTOR_CACHE_DIAGNOSTICS_SCHEMA = "strategy-v5-scope-vector-cache-diagnostics/2";
    public static final String EVALUATION_SCOPE_SCHEMA = "strategy-v5-evaluation-scope/2";
    public static final String LAZY_REFERENCE_SCHEMA = "strategy-v5-lazy-execution-reference/1";
    public static final String PARTITION_CACHE_DIAGNOSTICS_SCHEMA = "strategy-v5-partition-read-cache-diagnostics/1";
    public static final String WORKLOAD_SCHEMA = "strategy-v5-production-workload-estimate/2";
    public static final String COMPLEXITY_SCHEMA = "strategy-v5-production-complexity-estimate/1";
    public static final String DATA_PLANE_SCHEMA = "strategy-v5-performance-data-plane/1";
    public static final String DATA_PLANE_SEMANTIC_SCHEMA = "strategy-v5-performance-data-plane-semantic/1";
    public static final String DATA_PLANE_RUNTIME_SCHEMA = "strategy-v5-performance-data-plane-runtime/1";
    public static final String ACQUISITION_SCHEMA = "strategy-v5-authoritative-acquisition/1";
    public static final String PARQUET_CONVERSION_SCHEMA = "strategy-v5-parquet-conversion/1";
    public static final String SEPARATED_ARTIFACT_SCHEMA = "strategy-v5-separated-artifacts/1";
    public static final String AUTHORITATIVE_COVERAGE_SCHEMA = "strategy-v5-authoritative-coverage/1";
    public static final String PROMOTED_COVERAGE_SCHEMA = "strategy-v5-promoted-coverage/1";
    public static final String PLAN_SCHEMA = "strategy-v5-authoritative-data-plan/1";
    public static final String OUTCOME_PROOF_SCHEMA = "strategy-v5-scope-independent-outcome-proof/1";
    public static final String OUTCOME_CAPABILITY_SCHEMA = "strategy-v5-internal-scope-independent-outcome-capability/1";
    public static final long FOUR_HOURS_MS = 4L * 60L * 60L * 1_000L;
    public static final List<String> DATA_BINDING_KEYS = List.of(
            "feature_artifact_sha256", "label_artifact_sha256", "execution_artifact_sha256",
            "mark_artifact_sha256", "metadata_artifact_sha256");
    public static final List<String> V5_CANONICAL_ASSETS = List.of(
            "aave", "ada", "bnb", "btc", "eth", "link", "sol", "xrp");

    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final ResearchSchemaRegistry SCHEMAS = ResearchSchemaRegistry.defaultRegistry();
    private static final Pattern HASH_RE = Pattern.compile("^[a-f0-9]{64}$");
    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private StrategyPerformanceV5() {}

    static ObjectMapper jsonMapper() { return MAPPER; }

    public static String hashV5Performance(JsonNode value) {
        return JsonHashes.canonicalSha256(value == null ? NullNode.instance : value);
    }

    public static String hashV5Performance(String value) {
        return JsonHashes.sha256(value == null ? "null" : value);
    }

    public static String hashV5Performance(byte[] value) {
        return JsonHashes.sha256(value == null ? new byte[0] : value);
    }

    public static String hash(JsonNode value) { return hashV5Performance(value); }
    public static String hash(String value) { return hashV5Performance(value); }
    public static String hash(byte[] value) { return hashV5Performance(value); }
    public static String ownHash(JsonNode value) { return ownHash(value, "content_sha256"); }
    public static String ownHash(JsonNode value, String field) {
        JsonNode copy = value == null ? NullNode.instance : value.deepCopy();
        if (copy instanceof ObjectNode object) object.remove(field);
        return hashV5Performance(copy);
    }

    /** Java callback equivalent of the one-episode Node signal callback. */
    @FunctionalInterface
    public interface SignalEvaluator {
        ObjectNode evaluate(String episodeId, JsonNode feature, ObjectNode chromosome);
    }

    /** Java callback equivalent of the one-episode Node outcome callback. */
    @FunctionalInterface
    public interface OutcomeEvaluator {
        ObjectNode evaluate(String episodeId, JsonNode feature, ObjectNode signal, ObjectNode chromosome,
                            String phase, String foldId, String fitCutoff, String evaluationCutoff);
    }

    /** Immutable callback request useful to callers that do not use JSON option aliases. */
    public record EvaluationRequest(ObjectNode chromosome, String chromosomeSha256, List<String> episodeIds,
                                    List<String> declaredScopeIds, String phase, String foldId,
                                    String fitCutoff, String evaluationCutoff,
                                    Map<String, JsonNode> featureByEpisode,
                                    SignalEvaluator evaluateSignal, OutcomeEvaluator evaluateOutcome) {
        public EvaluationRequest {
            chromosome = chromosome == null ? object() : chromosome.deepCopy();
            episodeIds = episodeIds == null ? List.of() : List.copyOf(episodeIds);
            declaredScopeIds = declaredScopeIds == null ? episodeIds : List.copyOf(declaredScopeIds);
            featureByEpisode = featureByEpisode == null ? Map.of() : Map.copyOf(featureByEpisode);
        }
    }

    /** Content-addressed binding and bounded cache implementation. */
    public static final class ScopeVectorCache {
        private final ObjectNode binding;
        private final String bindingSha256;
        private final String outcomeProofSha256;
        private final Object authoritativeEvaluator;
        private final PhysicalEvaluatorTrustRegistry.ScopeIndependentOutcomeCapability trustedCapability;
        private final Path cacheRoot;
        private final int maxMemoryEntries;
        private final long maxMemoryBytes;
        private final long maxDiskBytes;
        private final long maxEntryBytes;
        private final long maxResultBytes;
        private final LinkedHashMap<String, MemoryEntry> memory = new LinkedHashMap<>(16, .75f, true);
        private long memoryBytes;
        private long diskBytes;
        private long signalCalls;
        private long outcomeCalls;
        private long signalHits;
        private long outcomeHits;
        private long diskHits;
        private long diskRevalidations;
        private long diskWrites;
        private long diskWriteSkips;

        private ScopeVectorCache(ObjectNode options) {
            this(options, null, null, null);
        }

        private ScopeVectorCache(ObjectNode options, Object authoritativeEvaluator,
                                 PhysicalEvaluatorTrustRegistry trustRegistry,
                                 JsonNode suppliedOutcomeProof) {
            ObjectNode o = options == null ? object() : options;
            if (o.has("scopeIndependentOutcomes"))
                throw failure("scopeIndependentOutcomes is self-asserted and forbidden; provide the loader-owned physical-v2 capability");
            if (o.has("scope_independent_outcomes"))
                throw failure("scopeIndependentOutcomes is self-asserted and forbidden; provide the loader-owned physical-v2 capability");
            ObjectNode baseBinding = normalizeBinding(o, null);
            JsonNode serializedProof = first(o, "scopeIndependentOutcomeProof", "scope_independent_outcome_proof");
            if (suppliedOutcomeProof == null && !nullish(serializedProof)) suppliedOutcomeProof = serializedProof;
            boolean serializedCapability = o.has("scopeIndependentOutcomeCapability")
                    || o.has("scope_independent_outcome_capability");
            PhysicalEvaluatorTrustRegistry.ScopeIndependentOutcomeCapability capability =
                    authoritativeEvaluator == null || trustRegistry == null ? null
                            : trustRegistry.getInternalScopeIndependentOutcomeCapability(authoritativeEvaluator);
            if (serializedCapability && capability == null)
                throw failure("scope-independent outcome capability is not loader-owned");
            if (suppliedOutcomeProof != null && capability == null)
                throw failure("scope-independent outcome proof cannot authorize reuse without the loader-owned in-process capability");
            if (authoritativeEvaluator != null && (trustRegistry == null
                    || !trustRegistry.isVerifiedPhysicalEvaluator(authoritativeEvaluator)))
                throw failure("scope-independent outcome reuse requires a trust-marked authoritative physical-v2 evaluator");
            ObjectNode proof = capability == null ? null
                    : validateOutcomeProof(suppliedOutcomeProof == null ? capability.proof() : suppliedOutcomeProof,
                            baseBinding);
            if (capability != null) validateOutcomeCapability(capability, authoritativeEvaluator,
                    trustRegistry, proof, baseBinding);
            this.binding = normalizeBinding(o, proof);
            this.bindingSha256 = text(binding.get("bindingSha256"));
            this.outcomeProofSha256 = nullableText(binding.get("outcomeProofSha256"));
            this.authoritativeEvaluator = authoritativeEvaluator;
            this.trustedCapability = capability;
            this.maxMemoryEntries = positiveInt(valueOr(o, "maxMemoryEntries", "max_memory_entries", 4_096), "max_memory_entries");
            this.maxMemoryBytes = positiveLong(valueOr(o, "maxMemoryBytes", "max_memory_bytes", 64L * 1024 * 1024), "max_memory_bytes");
            this.maxDiskBytes = nonNegativeLong(valueOr(o, "maxDiskBytes", "max_disk_bytes", 4L * 1024 * 1024 * 1024), "max_disk_bytes");
            this.maxEntryBytes = positiveLong(valueOr(o, "maxEntryBytes", "max_entry_bytes", 8L * 1024 * 1024), "max_entry_bytes");
            this.maxResultBytes = positiveLong(valueOr(o, "maxResultBytes", "max_result_bytes", 4L * 1024 * 1024), "max_result_bytes");
            if (maxResultBytes > maxEntryBytes) throw failure("max_result_bytes must be 1..max_entry_bytes");
            JsonNode root = first(o, "cacheRoot", "cache_root");
            this.cacheRoot = root == null || root.isNull() || text(root).isEmpty() ? null : resolvePath(text(root));
            if (cacheRoot != null) {
                try {
                    Files.createDirectories(cacheRoot);
                    try (var stream = Files.list(cacheRoot)) {
                        diskBytes = stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                                .mapToLong(path -> { try { return Files.size(path); } catch (IOException ignored) { return 0; } }).sum();
                    }
                } catch (IOException error) { throw failure("cannot initialize scope-vector cache: " + error.getMessage()); }
            }
        }

        public ObjectNode binding() { return binding.deepCopy(); }

        public ObjectNode evaluate(ObjectNode options, SignalEvaluator signal, OutcomeEvaluator outcome) {
            ObjectNode o = options == null ? object() : options;
            JsonNode rawChromosome = first(o, "chromosome");
            if (rawChromosome == null || !rawChromosome.isObject()) throw failure("chromosome must be an object");
            ObjectNode chromosome = (ObjectNode) rawChromosome;
            List<String> ids = uniqueIds(first(o, "episodeIds", "episode_ids"), "episode_ids");
            List<String> scopeIds = ids;
            JsonNode scope = first(o, "scope");
            if (scope != null && !scope.isNull()) {
                scopeIds = uniqueIds(first(scope, "episode_ids", "ids"), "scope.episode_ids");
                Set<String> allowed = new HashSet<>(scopeIds);
                for (String id : ids) if (!allowed.contains(id)) throw failure("episode " + id + " is outside the declared evaluation scope");
            }
            String suppliedHash = text(first(o, "chromosomeSha256", "chromosome_sha256"));
            String expectedHash = hashV5Performance(chromosome);
            if (suppliedHash.isEmpty()) suppliedHash = expectedHash;
            requireHash(suppliedHash, "chromosome_sha256");
            if (!suppliedHash.equals(expectedHash)) throw failure("chromosome_sha256 does not match the supplied chromosome");
            Map<String, JsonNode> features = objectMap(first(o, "featureByEpisode", "feature_by_episode"));
            String phase = nullableText(first(o, "phase"));
            String foldId = nullableText(first(o, "foldId", "fold_id"));
            String fitCutoff = nullableText(first(o, "fitCutoff", "fit_cutoff"));
            String evaluationCutoff = nullableText(first(o, "evaluationCutoff", "evaluation_cutoff"));
            return evaluate(new EvaluationRequest(chromosome, suppliedHash, ids, scopeIds, phase, foldId,
                    fitCutoff, evaluationCutoff, features, signal, outcome));
        }

        public ObjectNode evaluate(EvaluationRequest request) {
            Objects.requireNonNull(request, "request");
            if (request.evaluateSignal() == null || trustedCapability == null && request.evaluateOutcome() == null)
                throw failure("scope-vector evaluate requires signal and outcome callbacks");
            String expectedHash = hashV5Performance(request.chromosome());
            String suppliedHash = request.chromosomeSha256() == null || request.chromosomeSha256().isEmpty()
                    ? expectedHash : request.chromosomeSha256();
            requireHash(suppliedHash, "chromosome_sha256");
            if (!suppliedHash.equals(expectedHash)) throw failure("chromosome_sha256 does not match the supplied chromosome");
            List<String> ids = uniqueIds(request.episodeIds(), "episode_ids");
            List<String> scopeIds = uniqueIds(request.declaredScopeIds(), "scope.episode_ids");
            Set<String> allowed = new HashSet<>(scopeIds);
            for (String id : ids) if (!allowed.contains(id)) throw failure("episode " + id + " is outside the declared evaluation scope");
            String scopeSha256 = hashV5Performance(scopeObject(scopeIds, ids, request.phase(), request.foldId(),
                    request.fitCutoff(), request.evaluationCutoff()));
            Map<String, Object> trustContext = trustedCapability == null ? null
                    : trustContext(binding, outcomeProofSha256);
            PhysicalEvaluatorTrustRegistry.TrustEpoch trustEpoch = null;
            try {
                if (trustedCapability != null) trustEpoch = trustedCapability.beginEvaluationScope(trustContext);
                ObjectNode candidateReturns = object();
                ArrayNode intentVector = array();
                for (String episodeId : ids) {
                    JsonNode feature = request.featureByEpisode().get(episodeId);
                    Map<String, Object> episodeTrust = trustedCapability == null ? null
                            : episodeTrustContext(trustContext, trustEpoch, episodeId, feature, null,
                                    request.chromosome(), request.phase(), request.foldId(), request.fitCutoff(),
                                    request.evaluationCutoff());
                    if (trustedCapability != null) trustedCapability.verifyPitBoundary(episodeTrust);
                    // Signal vectors are definition/data-bound but deliberately scope independent.
                    // Ordinary outcomes remain scope-bound; trusted outcomes discard the scope fields.
                    ObjectNode signal = get(new CacheParts("signal", suppliedHash, episodeId, null, null,
                                    null, null, null),
                            () -> request.evaluateSignal().evaluate(episodeId, cloneAny(feature), request.chromosome().deepCopy()),
                            true, null);
                    boolean intent = truthy(signal.get("intent"));
                    ObjectNode vectorRow = object().put("episode_id", episodeId).put("intent", intent);
                    intentVector.add(vectorRow);
                    if (!intent) {
                        candidateReturns.set(episodeId, object().put("net_r", 0).put("traded", false));
                        continue;
                    }
                    Map<String, Object> outcomeTrust = trustedCapability == null ? null
                            : episodeTrustContext(trustContext, trustEpoch, episodeId, feature, signal,
                                    request.chromosome(), request.phase(), request.foldId(), request.fitCutoff(),
                                    request.evaluationCutoff());
                    SupplierResult outcomeComputer = trustedCapability == null
                            ? () -> request.evaluateOutcome().evaluate(episodeId, cloneAny(feature), signal.deepCopy(),
                                    request.chromosome().deepCopy(), request.phase(), request.foldId(),
                                    request.fitCutoff(), request.evaluationCutoff())
                            : () -> {
                                PhysicalEvaluatorTrustRegistry.Outcome owned =
                                        trustedCapability.computeOutcome(outcomeTrust);
                                outcomeTrust.put("expectedOutcome", owned);
                                return outcomeNode(owned);
                            };
                    ObjectNode outcome = get(new CacheParts("outcome", suppliedHash, episodeId, request.phase(),
                                    request.foldId(), request.fitCutoff(), request.evaluationCutoff(), scopeSha256),
                            outcomeComputer, false, outcomeTrust);
                    JsonNode net = outcome.get("net_r");
                    if (net == null || !net.isNumber() || !Double.isFinite(net.doubleValue()))
                        throw failure("outcome for " + episodeId + " must contain finite numeric net_r");
                    candidateReturns.set(episodeId, object().put("net_r", net.doubleValue())
                            .put("traded", !outcome.has("traded") || !outcome.get("traded").isBoolean()
                                    || outcome.get("traded").booleanValue()));
                }
                ObjectNode result = object().put("chromosome_sha256", suppliedHash);
                result.set("episode_ids", strings(ids)); result.set("signal_intent_vector", intentVector);
                result.set("candidate_returns", candidateReturns); result.put("scope_sha256", scopeSha256);
                return result;
            } finally {
                if (trustedCapability != null && trustEpoch != null) trustedCapability.endEvaluationScope(trustEpoch);
            }
        }

        /** Returns the content-addressed key for a canonical cache parts object. */
        public String keyFor(ObjectNode parts) {
            ObjectNode value = object().put("schema", SCOPE_VECTOR_KEY_SCHEMA).put("binding_sha256", bindingSha256);
            if (parts != null) value.setAll(parts.deepCopy());
            return hashV5Performance(value);
        }

        public ObjectNode diagnostics() {
            ObjectNode result = object().put("schema", SCOPE_VECTOR_CACHE_DIAGNOSTICS_SCHEMA)
                    .put("binding_sha256", bindingSha256)
                    .put("scope_independent_outcomes", outcomeProofSha256 != null);
            result.set("outcome_proof_sha256", nullable(outcomeProofSha256));
            return result.put("memory_entry_count", memory.size()).put("memory_bytes", memoryBytes)
                    .put("max_memory_entries", maxMemoryEntries).put("max_memory_bytes", maxMemoryBytes)
                    .put("max_entry_bytes", maxEntryBytes).put("max_result_bytes", maxResultBytes)
                    .put("disk_bytes", diskBytes).put("max_disk_bytes", maxDiskBytes)
                    .put("signal_compute_count", signalCalls).put("outcome_compute_count", outcomeCalls)
                    .put("signal_hit_count", signalHits).put("outcome_hit_count", outcomeHits)
                    .put("disk_hit_count", diskHits).put("disk_revalidation_count", diskRevalidations)
                    .put("disk_write_count", diskWrites).put("disk_write_skip_count", diskWriteSkips);
        }

        private ObjectNode get(CacheParts parts, SupplierResult compute, boolean signal,
                               Map<String, Object> trustedContext) {
            CacheParts effective = !signal && trustedCapability != null
                    ? new CacheParts(parts.kind(), parts.chromosomeSha256(), parts.episodeId(), null, null,
                            null, null, null)
                    : parts;
            String memoryKey = effective.memoryKey();
            MemoryEntry prior = memory.get(memoryKey);
            if (prior != null) {
                if (!signal && trustedCapability != null)
                    trustedCapability.verifyCachedOutcome(withResult(trustedContext, prior.result()));
                if (signal) signalHits++; else outcomeHits++;
                return prior.result.deepCopy();
            }
            String key = cacheRoot == null ? null : keyFor(effective.node());
            ObjectNode disk = readDisk(key, effective);
            if (disk != null) {
                diskRevalidations++;
                ObjectNode recomputed = compute.value();
                if (!same(recomputed, disk)) {
                    throw failure((!signal && trustedCapability != null ? "trusted disk outcome" : "disk "
                            + (signal ? "signal" : "outcome"))
                            + " differs from the canonical recomputation for " + effective.episodeId());
                }
                if (!signal && trustedCapability != null)
                    trustedCapability.verifyCachedOutcome(withResult(trustedContext, disk));
                if (signal) signalHits++; else outcomeHits++;
                touch(memoryKey, disk);
                return disk.deepCopy();
            }
            if (signal) signalCalls++; else outcomeCalls++;
            ObjectNode result = compute.value();
            if (result == null) throw failure((signal ? "signal" : "outcome") + " vector callback must return an object");
            if (!signal && trustedCapability != null) {
                Map<String, Object> verification = withResult(trustedContext, result);
                trustedCapability.verifyOutcome(verification);
            }
            enforceResult(result);
            touch(memoryKey, result);
            writeDisk(key, effective, result);
            return result.deepCopy();
        }

        private void enforceResult(ObjectNode result) {
            long bytes = jsonBytes(result).length;
            if (bytes > maxResultBytes) throw failure("scope-vector cache result exceeds " + maxResultBytes + " bytes");
        }

        private void touch(String key, ObjectNode result) {
            long bytes = jsonBytes(result).length;
            MemoryEntry prior = memory.remove(key);
            if (prior != null) memoryBytes -= prior.bytes();
            memory.put(key, new MemoryEntry(result.deepCopy(), bytes));
            memoryBytes += bytes;
            while (memory.size() > maxMemoryEntries || memoryBytes > maxMemoryBytes) {
                String oldest = memory.keySet().iterator().next();
                MemoryEntry removed = memory.remove(oldest);
                memoryBytes -= removed.bytes();
            }
        }

        private ObjectNode readDisk(String key, CacheParts parts) {
            if (key == null) return null;
            Path path = cacheRoot.resolve(key + ".json");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
            try {
                long bytes = Files.size(path);
                if (bytes > maxEntryBytes) throw failure("scope-vector cache entry exceeds " + maxEntryBytes + " bytes: " + key);
                JsonNode parsed = MAPPER.readTree(Files.readString(path));
                if (!(parsed instanceof ObjectNode value)) throw failure("scope-vector cache entry is invalid: " + key);
                ObjectNode result = verifyCacheRecord(value, key, parts, bytes);
                diskHits++;
                return result;
            } catch (IOException error) { throw failure("scope-vector cache entry is invalid: " + key + ": " + error.getMessage()); }
        }

        private ObjectNode verifyCacheRecord(ObjectNode value, String key, CacheParts parts, long serializedBytes) {
                if (serializedBytes > maxEntryBytes || !SCOPE_VECTOR_CACHE_ENTRY_SCHEMA.equals(text(value.get("schema")))
                    || value.path("version").asInt(-1) != 2 || !text(value.get("content_sha256")).equals(StrategyPerformanceV5.ownHash(value)))
                throw failure("scope-vector cache entry is invalid: " + key);
            if (!text(value.get("key")).equals(key) || !text(value.get("binding_sha256")).equals(bindingSha256)
                    || !sameNullable(value.get("outcome_proof_sha256"), outcomeProofSha256)
                    || !text(value.get("kind")).equals(parts.kind())
                    || !text(value.get("chromosome_sha256")).equals(parts.chromosomeSha256())
                    || !text(value.get("episode_id")).equals(parts.episodeId())
                    || !sameNullable(value.get("phase"), parts.phase()) || !sameNullable(value.get("fold_id"), parts.foldId())
                    || !sameNullable(value.get("fit_cutoff"), parts.fitCutoff()) || !sameNullable(value.get("evaluation_cutoff"), parts.evaluationCutoff())
                    || !sameNullable(value.get("scope_sha256"), parts.scopeSha256())
                    || !text(value.get("result_sha256")).equals(hashV5Performance(value.get("result"))))
                throw failure("scope-vector cache entry binding mismatch: " + key);
            ObjectNode result = objectOrEmpty(value.get("result"));
            enforceResult(result);
            return result;
        }

        private void writeDisk(String key, CacheParts parts, ObjectNode result) {
            if (key == null) return;
            enforceResult(result);
            ObjectNode record = object().put("schema", SCOPE_VECTOR_CACHE_ENTRY_SCHEMA).put("version", 2)
                    .put("key", key).put("binding_sha256", bindingSha256);
            record.set("outcome_proof_sha256", nullable(outcomeProofSha256));
            record
                    .put("kind", parts.kind()).put("chromosome_sha256", parts.chromosomeSha256()).put("episode_id", parts.episodeId());
            record.set("phase", nullable(parts.phase())); record.set("fold_id", nullable(parts.foldId())); record.set("fit_cutoff", nullable(parts.fitCutoff()));
            record.set("evaluation_cutoff", nullable(parts.evaluationCutoff())); record.set("scope_sha256", nullable(parts.scopeSha256()));
            record.set("result", result.deepCopy()); record.put("result_sha256", hashV5Performance(result));
            record.put("content_sha256", StrategyPerformanceV5.ownHash(record));
            byte[] body = appendLf(jsonBytes(record));
            if (body.length > maxEntryBytes) throw failure("scope-vector cache entry exceeds " + maxEntryBytes + " bytes: " + key);
            Path path = cacheRoot.resolve(key + ".json");
            try {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    ObjectNode existing = readDisk(key, parts);
                    if (!same(existing, result)) throw failure("scope-vector cache collision: " + key);
                    return;
                }
                if (diskBytes + body.length > maxDiskBytes) { diskWriteSkips++; return; }
                Path temp = Files.createTempFile(cacheRoot, key, ".tmp");
                try {
                    Files.write(temp, body);
                    try { Files.move(temp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
                    catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, path); }
                    diskBytes += body.length; diskWrites++;
                } finally { Files.deleteIfExists(temp); }
            } catch (IOException error) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    ObjectNode existing = readDisk(key, parts);
                    if (!same(existing, result)) throw failure("scope-vector cache collision: " + key);
                } else throw failure("cannot write scope-vector cache: " + error.getMessage());
            }
        }
    }

    private record MemoryEntry(ObjectNode result, long bytes) {}
    private record CacheParts(String kind, String chromosomeSha256, String episodeId, String phase, String foldId,
                              String fitCutoff, String evaluationCutoff, String scopeSha256) {
        String memoryKey() { return kind + "|" + chromosomeSha256 + "|" + episodeId + "|" + n(phase) + "|" + n(foldId) + "|" + n(fitCutoff) + "|" + n(evaluationCutoff) + "|" + n(scopeSha256); }
        ObjectNode node() { ObjectNode v = object().put("kind", kind).put("chromosomeSha256", chromosomeSha256).put("episodeId", episodeId); v.set("phase", nullable(phase)); v.set("foldId", nullable(foldId)); v.set("fitCutoff", nullable(fitCutoff)); v.set("evaluationCutoff", nullable(evaluationCutoff)); v.set("scope_sha256", nullable(scopeSha256)); return v; }
        private static String n(String v) { return v == null ? "" : v; }
    }
    @FunctionalInterface private interface SupplierResult { ObjectNode value(); }

    public static ScopeVectorCache makeScopeVectorCacheV5(ObjectNode options) { return new ScopeVectorCache(options); }

    /**
     * Trusted counterpart of the JavaScript loader-owned capability path. The registry is
     * required so a serialized proof or lookalike capability can never enable cross-scope reuse.
     */
    public static ScopeVectorCache makeScopeVectorCacheV5(ObjectNode options, Object authoritativeEvaluator,
            PhysicalEvaluatorTrustRegistry trustRegistry, JsonNode scopeIndependentOutcomeProof) {
        return new ScopeVectorCache(options, authoritativeEvaluator, trustRegistry, scopeIndependentOutcomeProof);
    }

    private static ObjectNode normalizeBinding(ObjectNode o, ObjectNode outcomeProof) {
        String source = requireHash(text(first(o, "sourceArtifactSha256", "source_artifact_sha256")), "source_artifact_sha256");
        String evaluator = requireHash(text(first(o, "evaluatorSpecSha256", "evaluator_spec_sha256")), "evaluator_spec_sha256");
        String signal = requireHash(text(first(o, "signalCodeSha256", "signal_code_sha256")), "signal_code_sha256");
        String outcome = requireHash(text(first(o, "outcomeCodeSha256", "outcome_code_sha256")), "outcome_code_sha256");
        String predictor = nullableHash(first(o, "predictorRegistrySha256", "predictor_registry_sha256"), "predictor_registry_sha256");
        String worker = nullableHash(first(o, "workerCodeSha256", "worker_code_sha256"), "worker_code_sha256");
        ObjectNode supplied = objectOrEmpty(first(o, "dataBindings", "data_bindings"));
        List<String> keys = new ArrayList<>(); supplied.fieldNames().forEachRemaining(keys::add); Collections.sort(keys);
        List<String> expected = new ArrayList<>(DATA_BINDING_KEYS); Collections.sort(expected);
        if (!keys.equals(expected)) throw failure("data_bindings must contain exactly " + String.join(", ", DATA_BINDING_KEYS));
        ObjectNode data = object(); for (String key : DATA_BINDING_KEYS) data.put(key, requireHash(text(supplied.get(key)), key));
        ObjectNode binding = object().put("schema", SCOPE_VECTOR_BINDING_SCHEMA).put("source_artifact_sha256", source)
                .put("evaluator_spec_sha256", evaluator);
        binding.set("predictor_registry_sha256", nullable(predictor));
        binding.set("data_bindings", data);
        binding.put("signal_code_sha256", signal).put("outcome_code_sha256", outcome);
        binding.set("worker_code_sha256", nullable(worker));
        String outcomeProofSha256 = outcomeProof == null ? null : text(outcomeProof.get("content_sha256"));
        binding.set("outcome_proof_sha256", nullable(outcomeProofSha256));
        String bindingSha256 = hashV5Performance(binding);
        ObjectNode result = object().put("sourceArtifactSha256", source).put("evaluatorSpecSha256", evaluator);
        result.set("predictorRegistrySha256", nullable(predictor));
        result.set("dataBindings", data);
        result.put("signalCodeSha256", signal).put("outcomeCodeSha256", outcome);
        result.set("workerCodeSha256", nullable(worker));
        result.putNull("authoritativeEvaluator");
        result.putNull("scopeIndependentOutcomeCapability");
        result.putNull("scopeIndependentOutcomeProof");
        result.set("outcomeProofSha256", nullable(outcomeProofSha256));
        result.put("bindingSha256", bindingSha256);
        return result;
    }

    private static ObjectNode validateOutcomeProof(JsonNode rawProof, ObjectNode binding) {
        if (!(rawProof instanceof ObjectNode proof))
            throw failure("scope-independent outcome reuse requires a verified physical-v2 proof");
        if (!OUTCOME_PROOF_SCHEMA.equals(text(proof.get("schema"))) || proof.path("version").asInt(-1) != 1
                || !text(proof.get("content_sha256")).equals(ownHash(proof)))
            throw failure("scope-independent outcome proof is missing or tampered");
        if (!"AUTHORITATIVE_V2_PHYSICAL_EVALUATOR".equals(text(proof.get("authority")))
                || !proof.path("verified").asBoolean(false)
                || !"CHECK_BEFORE_EVALUATION_AND_ON_CACHE_HIT".equals(text(proof.get("pit_boundary_contract")))
                || !"FEATURE_LABEL_EXECUTION_MARK_METADATA_EXACT_BINDINGS".equals(text(proof.get("outcome_role_contract")))
                || !proof.path("one_episode_read_contract").asBoolean(false))
            throw failure("scope-independent outcome proof is not an authoritative physical-v2 contract");
        if (!text(proof.get("source_artifact_sha256")).equals(text(binding.get("sourceArtifactSha256")))
                || !text(proof.get("evaluator_spec_sha256")).equals(text(binding.get("evaluatorSpecSha256")))
                || !text(proof.get("data_bindings_sha256")).equals(hashV5Performance(binding.get("dataBindings"))))
            throw failure("scope-independent outcome proof binding mismatch");
        requireHash(text(proof.get("physical_evaluator_code_sha256")), "physical_evaluator_code_sha256");
        requireHash(text(proof.get("pit_validator_code_sha256")), "pit_validator_code_sha256");
        return proof.deepCopy();
    }

    private static void validateOutcomeCapability(
            PhysicalEvaluatorTrustRegistry.ScopeIndependentOutcomeCapability capability,
            Object authoritativeEvaluator, PhysicalEvaluatorTrustRegistry trustRegistry,
            ObjectNode proof, ObjectNode binding) {
        if (authoritativeEvaluator == null || trustRegistry == null
                || !trustRegistry.isVerifiedPhysicalEvaluator(authoritativeEvaluator))
            throw failure("scope-independent outcome reuse requires a trust-marked authoritative physical-v2 evaluator");
        if (capability == null || capability != trustRegistry.getInternalScopeIndependentOutcomeCapability(authoritativeEvaluator)
                || capability.evaluator() != authoritativeEvaluator)
            throw failure("scope-independent outcome capability is not loader-owned");
        ObjectNode descriptor = capability.descriptor() instanceof ObjectNode object ? object : null;
        if (!OUTCOME_CAPABILITY_SCHEMA.equals(capability.schema()) || capability.version() != 1
                || !"AUTHORITATIVE_V2_PHYSICAL_EVALUATOR".equals(capability.authority()) || !capability.verified()
                || descriptor == null || !text(descriptor.get("content_sha256")).equals(ownHash(descriptor)))
            throw failure("scope-independent outcome capability is invalid");
        if (!text(descriptor.get("source_artifact_sha256")).equals(text(binding.get("sourceArtifactSha256")))
                || !text(descriptor.get("evaluator_spec_sha256")).equals(text(binding.get("evaluatorSpecSha256")))
                || !text(descriptor.get("data_bindings_sha256")).equals(hashV5Performance(binding.get("dataBindings")))
                || !same(descriptor.get("data_bindings"), binding.get("dataBindings"))
                || !text(descriptor.get("outcome_proof_sha256")).equals(text(proof.get("content_sha256")))
                || !same(capability.proof(), proof))
            throw failure("scope-independent outcome capability binding mismatch");
    }

    private static Map<String, Object> trustContext(ObjectNode binding, String outcomeProofSha256) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sourceArtifactSha256", text(binding.get("sourceArtifactSha256")));
        context.put("evaluatorSpecSha256", text(binding.get("evaluatorSpecSha256")));
        context.put("predictorRegistrySha256", nullableText(binding.get("predictorRegistrySha256")));
        context.put("dataBindings", MAPPER.convertValue(binding.get("dataBindings"), Map.class));
        context.put("outcomeProofSha256", outcomeProofSha256);
        return context;
    }

    private static Map<String, Object> episodeTrustContext(Map<String, Object> base,
            PhysicalEvaluatorTrustRegistry.TrustEpoch epoch, String episodeId, JsonNode feature,
            ObjectNode signal, ObjectNode chromosome, String phase, String foldId,
            String fitCutoff, String evaluationCutoff) {
        Map<String, Object> context = new LinkedHashMap<>(base);
        context.put("trustEpoch", epoch); context.put("episodeId", episodeId);
        context.put("feature", cloneAny(feature)); context.put("signal", signal == null ? null : signal.deepCopy());
        context.put("chromosome", chromosome.deepCopy()); context.put("phase", phase);
        context.put("foldId", foldId); context.put("fitCutoff", fitCutoff);
        context.put("evaluationCutoff", evaluationCutoff);
        return context;
    }

    private static Map<String, Object> withResult(Map<String, Object> context, ObjectNode result) {
        Map<String, Object> value = new LinkedHashMap<>(context);
        value.put("result", result.deepCopy());
        return value;
    }

    private static ObjectNode outcomeNode(PhysicalEvaluatorTrustRegistry.Outcome value) {
        return object().put("net_r", value.netR()).put("traded", value.traded());
    }

    private static ObjectNode materializeRange(ObjectNode reference, ObjectNode hydration, List<ObjectNode> partitions,
                                               int batchSize, int maxRows, long maxResidentBytes, long maxOutputBytes,
                                               boolean preentry) {
        ObjectNode input = object(); input.set("hydration", hydration); input.set("partitions", arrayNode(partitions));
        input.put("window_id", text(reference.get("window_id"))).put("start", text(preentry ? reference.get("preentry_start") : reference.get("execution_start")));
        input.put("end", text(preentry ? reference.get("execution_start") : reference.get("execution_end"))).put("batchSize", batchSize).put("maxRows", maxRows).put("maxResidentBytes", maxResidentBytes).put("maxOutputBytes", maxOutputBytes);
        ObjectNode read = OpportunityV5.readHydratedRangeV5(input);
        ArrayNode flat = array(); for (JsonNode batch : array(read.get("batches"))) for (JsonNode row : batch) flat.add(row);
        ObjectNode result = object(); result.set("child_bars", flat); result.put("materialized_row_count", read.path("row_count").asInt()); return result;
    }

    private static ArrayNode normalizePartitionRefs(JsonNode raw) {
        ArrayNode output = array();
        for (JsonNode row : array(raw)) {
            ObjectNode value = object().put("partition_sha256", text(row.get("partition_sha256")));
            JsonNode partitionPath = first(row, "partition_path");
            value.set("partition_path", nullish(partitionPath) || !truthy(partitionPath) ? NullNode.instance : partitionPath.deepCopy());
            value.set("partition_bytes", row.has("partition_bytes") ? jsNumberNode(numberJs(row.get("partition_bytes"))) : NullNode.instance);
            value.set("partition_row_count", row.has("partition_row_count") ? jsNumberNode(numberJs(row.get("partition_row_count"))) : NullNode.instance);
            value.set("row_start", cloneAny(row.get("row_start"))); value.set("row_end_exclusive", cloneAny(row.get("row_end_exclusive")));
            value.set("row_count", jsNumberNode(number(row.get("row_count"))));
            output.add(value);
        }
        return output;
    }

    private static List<String> refHashes(ArrayNode... arrays) {
        List<String> hashes = new ArrayList<>(); for (ArrayNode rows : arrays) for (JsonNode row : rows) hashes.add(text(row.get("partition_sha256"))); return hashes;
    }
    private static String partitionRootSha256(List<String> hashes) {
        TreeMap<String, Boolean> unique = new TreeMap<>(); hashes.forEach(h -> unique.put(String.valueOf(h), true));
        ArrayNode values = array(); unique.keySet().forEach(values::add);
        ObjectNode root = object().put("schema", "strategy-v5-execution-partition-root/1"); root.set("partition_sha256", values);
        return hashV5Performance(root);
    }
    private static String partitionRootSha256(String... hashes) { return partitionRootSha256(Arrays.asList(hashes)); }

    private static ArrayNode arrayNode(List<ObjectNode> values) { ArrayNode a = array(); values.forEach(v -> a.add(v)); return a; }

    private static String rowsBody(ArrayNode rows) {
        StringBuilder body = new StringBuilder(); for (JsonNode row : rows) body.append(jsonString(row)).append('\n'); return body.toString();
    }
    private static List<ObjectNode> parseJsonl(String body, String label) {
        List<ObjectNode> rows = new ArrayList<>(); String[] lines = body.split("\\r?\\n"); int line = 0;
        for (String value : lines) { line++; if (value.isEmpty()) continue; try { JsonNode node = MAPPER.readTree(value); if (!(node instanceof ObjectNode object)) throw failure("JSONL row diagnostic: " + label + ":" + line + " is not an object"); rows.add(object); } catch (IOException error) { throw failure("JSONL malformed line diagnostic: " + label + ":" + line + ": " + error.getMessage()); } }
        return rows;
    }

    /** Immutable bounded byte-stream hash result. */
    public record StreamHashResult(String sha256, long bytes, long chunks, long maxChunkBytes, Long lineCount, Long maxLineBytesObserved) {
        public ObjectNode asJson() {
            ObjectNode result = object().put("sha256", sha256).put("bytes", bytes).put("chunks", chunks).put("max_chunk_bytes", maxChunkBytes);
            result.set("line_count", lineCount == null ? NullNode.instance : numberNode(lineCount));
            result.set("max_line_bytes_observed", maxLineBytesObserved == null ? NullNode.instance : numberNode(maxLineBytesObserved));
            return result;
        }
    }

    public static StreamHashResult streamHashV5ProductionFile(Path path, int chunkBytes, boolean countLines,
                                                               boolean parseJsonLines, int maxLineBytes, long maxPartitionBytes,
                                                               Consumer<ObjectNode> onRow) {
        if (chunkBytes < 1) throw failure("stream chunk bytes must be a positive integer");
        if (maxLineBytes < 1) throw failure("max JSONL line bytes must be a positive integer");
        if (maxPartitionBytes < 1) throw failure("max partition bytes must be a positive integer");
        try (InputStream stream = new BufferedInputStream(Files.newInputStream(path), chunkBytes)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[chunkBytes]; byte[] line = new byte[maxLineBytes];
            long bytes = 0, chunks = 0, maxChunk = 0, lines = 0, maxLine = 0; int offset = 0; int lineNumber = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read == 0) continue; digest.update(buffer, 0, read); bytes += read; chunks++; maxChunk = Math.max(maxChunk, read);
                if (bytes > maxPartitionBytes) throw failure("partition exceeds bounded streaming limit (" + maxPartitionBytes + " bytes): " + path);
                if (!countLines && !parseJsonLines) continue;
                for (int i = 0; i < read; i++) { int b = buffer[i]; if (b == '\n') { int length = offset > 0 && line[offset - 1] == '\r' ? offset - 1 : offset; lineNumber++; if (length == 0) throw failure("JSONL empty line diagnostic: " + path + ":" + lineNumber); String text = new String(line, 0, length, StandardCharsets.UTF_8); lines++; maxLine = Math.max(maxLine, length); if (parseJsonLines && onRow != null) onRow.accept(parseRow(text, path, lineNumber)); offset = 0; } else { if (offset >= maxLineBytes) throw failure("JSONL line exceeds bounded streaming limit (" + maxLineBytes + " bytes): " + path + ":" + (lineNumber + 1)); line[offset++] = (byte) b; } }
            }
            if (offset > 0) { int length = offset > 0 && line[offset - 1] == '\r' ? offset - 1 : offset; lineNumber++; if (length == 0) throw failure("JSONL empty line diagnostic: " + path + ":" + lineNumber); String text = new String(line, 0, length, StandardCharsets.UTF_8); lines++; maxLine = Math.max(maxLine, length); if (parseJsonLines && onRow != null) onRow.accept(parseRow(text, path, lineNumber)); }
            return new StreamHashResult(java.util.HexFormat.of().formatHex(digest.digest()), bytes, chunks, maxChunk, countLines || parseJsonLines ? lines : null, countLines || parseJsonLines ? maxLine : null);
        } catch (IOException error) { throw failure("cannot stream production file: " + error.getMessage()); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static StreamHashResult streamHashV5ProductionFile(Path path) { return streamHashV5ProductionFile(path, 1024 * 1024, false, false, 16 * 1024 * 1024, 8L * 1024 * 1024 * 1024, null); }
    public static StreamHashResult streamHashV5ProductionFile(String path) { return streamHashV5ProductionFile(Path.of(path)); }

    public static ObjectNode productionFundingSegment(ObjectNode capture, long event) {
        return StrategyPerformanceV5Benchmark.productionFundingSegment(capture, event);
    }

    public static ObjectNode runProductionDataPlaneBenchmarkV5(ObjectNode options) {
        return StrategyPerformanceV5Benchmark.runProductionDataPlaneBenchmarkV5(options);
    }
    private static ObjectNode parseRow(String text, Path path, int line) {
        try { JsonNode value = MAPPER.readTree(text); if (!(value instanceof ObjectNode row)) throw failure("JSONL row diagnostic: " + path + ":" + line + " is not an object"); return row; }
        catch (IOException error) { throw failure("JSONL malformed line diagnostic: " + path + ":" + line + ": " + error.getMessage()); }
    }


    /** Convert a hash-bound hydration window into a worker-safe lazy reference. */
    public static ObjectNode makeLazyExecutionReferenceV5(ObjectNode options) {
        ObjectNode o = options == null ? object() : options;
        JsonNode hydration = first(o, "hydration");
        if (hydration == null || !"strategy-v5-opportunity-hydration/2".equals(text(hydration.get("schema")))
                || !text(hydration.get("content_sha256")).equals(ownHash(hydration)))
            throw failure("lazy execution reference requires a hash-bound opportunity hydration/2 artifact");
        String windowId = text(first(o, "windowId", "window_id"));
        JsonNode capture = null;
        for (JsonNode row : array(hydration.get("windows"))) if (text(row.get("window_id")).equals(windowId)) { capture = row; break; }
        if (capture == null) throw failure("unknown hydration window " + windowId);
        if (array(capture.get("partition_refs")).isEmpty()) throw failure("hydration window " + windowId + " has no execution partition references");
        ArrayNode refs = normalizePartitionRefs(capture.get("partition_refs"));
        ArrayNode preentry = normalizePartitionRefs(capture.get("preentry_partition_refs"));
        ObjectNode value = object().put("schema", LAZY_REFERENCE_SCHEMA).put("version", 2)
                .put("hydration_sha256", text(hydration.get("content_sha256")))
                .put("partition_root_sha256", partitionRootSha256(refHashes(preentry, refs)))
                .put("window_id", windowId);
        value.set("asset", nullable(first(o, "asset")));
        value.set("instrument", nullable(first(o, "instrument")));
        value.set("symbol", nullable(first(o, "symbol")));
        value.set("preentry_start", preentry.isEmpty() ? NullNode.instance : nullable(capture.get("preentry_start")));
        value.set("execution_start", cloneAny(capture.get("execution_start")));
        value.set("execution_end", cloneAny(truthy(capture.get("effective_end_exclusive"))
                ? capture.get("effective_end_exclusive") : capture.get("execution_end")));
        value.set("lifecycle_status", cloneAny(capture.get("lifecycle_status")));
        value.set("row_count", jsNumberNode(number(capture.get("row_count"))));
        value.set("preentry_partition_refs", preentry);
        value.set("partition_refs", refs);
        value.put("content_sha256", ownHash(value));
        return value;
    }

    /** Bounded LRU over immutable, content-addressed physical partitions. */
    public static final class BoundedPartitionReadCache {
        private final String partitionRoot;
        private final long maxResidentBytes;
        private final long maxEntryBytes;
        private final LinkedHashMap<String, PartitionEntry> entries = new LinkedHashMap<>(16, .75f, true);
        private long residentBytes;
        private long diskReads;
        private long diskReadBytes;
        private long cacheHits;
        private long evictions;
        private long peakResidentBytes;

        private BoundedPartitionReadCache(ObjectNode options) {
            ObjectNode o = options == null ? object() : options;
            String explicit = nullableText(first(o, "partitionRootSha256", "partition_root_sha256"));
            String legacy = nullableText(first(o, "partitionSetSha256", "partition_set_sha256"));
            if (explicit != null && legacy != null && !explicit.equals(legacy))
                throw failure("partition root and legacy partition-set bindings disagree");
            partitionRoot = requireHash(explicit == null ? legacy : explicit, "partition_root_sha256");
            maxResidentBytes = positiveLong(valueOr(o, "maxResidentBytes", "max_resident_bytes", 192L * 1024 * 1024), "partition cache max_resident_bytes");
            maxEntryBytes = positiveLong(valueOr(o, "maxEntryBytes", "max_entry_bytes", 512L * 1024 * 1024), "partition cache max_entry_bytes");
            if (maxEntryBytes > maxResidentBytes) throw failure("partition cache max_entry_bytes must be 1..max_resident_bytes");
        }

        public List<ObjectNode> resolve(List<ObjectNode> partitions) {
            if (partitions == null || partitions.isEmpty()) throw failure("partition cache requires physical partitions");
            Map<String, ObjectNode> unique = new LinkedHashMap<>();
            for (ObjectNode partition : partitions) {
                String sha256 = text(partition.get("sha256"));
                if (!unique.containsKey(sha256)) unique.put(sha256, load(partition));
            }
            return List.copyOf(unique.values());
        }

        public ObjectNode diagnostics() {
            return object().put("schema", PARTITION_CACHE_DIAGNOSTICS_SCHEMA).put("partition_root_sha256", partitionRoot)
                    .put("partition_set_sha256", partitionRoot).put("resident_entry_count", entries.size())
                    .put("resident_bytes", residentBytes).put("peak_resident_bytes", peakResidentBytes)
                    .put("max_resident_bytes", maxResidentBytes).put("max_entry_bytes", maxEntryBytes)
                    .put("cache_hit_count", cacheHits).put("disk_read_count", diskReads).put("disk_read_bytes", diskReadBytes)
                    .put("eviction_count", evictions);
        }

        private ObjectNode load(ObjectNode partition) {
            String sha = requireHash(text(partition.get("sha256")), "physical partition sha256");
            PartitionEntry prior = entries.get(sha);
            if (prior != null) {
                if (prior.changed(partition)) {
                    entries.remove(sha); residentBytes -= prior.bytes();
                } else { cacheHits++; return prior.partition().deepCopy(); }
            }
            long declared = integral(partition.get("bytes"));
            if (declared < 1 || declared > maxEntryBytes) throw failure("physical partition " + sha + " exceeds bounded cache entry bytes");
            String body = partition.has("body") && partition.get("body").isTextual() ? partition.get("body").textValue()
                    : partition.has("path") && !partition.get("path").isNull() ? readString(resolvePath(text(partition.get("path"))))
                    : array(partition.get("rows")).isEmpty() ? null : rowsBody(array(partition.get("rows")));
            if (body == null) throw failure("physical partition " + sha + " has no readable body");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length != declared || !sha.equals(hashV5Performance(bytes))) throw failure("physical partition " + sha + " bytes/hash are invalid");
            int expectedRows = (int) integralOr(partition.get("row_count"), -1);
            int observedRows = parseJsonl(body, sha).size();
            if (expectedRows >= 0 && observedRows != expectedRows) throw failure("physical partition " + sha + " row count is invalid");
            while (residentBytes + bytes.length > maxResidentBytes) {
                if (entries.isEmpty()) throw failure("physical partition " + sha + " cannot fit the bounded resident cache");
                String oldest = entries.keySet().iterator().next();
                PartitionEntry victim = entries.remove(oldest); residentBytes -= victim.bytes(); evictions++;
            }
            ObjectNode value = partition.deepCopy(); value.put("body", body); value.remove("rows");
            PartitionEntry entry = new PartitionEntry(value, bytes.length, signature(partition)); entries.put(sha, entry);
            residentBytes += bytes.length; peakResidentBytes = Math.max(peakResidentBytes, residentBytes);
            if (!partition.has("body") || partition.has("path")) { diskReads++; diskReadBytes += bytes.length; }
            return value.deepCopy();
        }
    }

    private record PartitionEntry(ObjectNode partition, long bytes, FileSignature signature) {
        boolean changed(ObjectNode candidate) { return signature != null && !signature.equals(signature(candidate)); }
        private static FileSignature signature(ObjectNode partition) { return StrategyPerformanceV5.signature(partition); }
    }
    private record FileSignature(long size, long mtime, long inode) {}

    public static BoundedPartitionReadCache makeBoundedPartitionReadCacheV5(ObjectNode options) {
        return new BoundedPartitionReadCache(options);
    }

    public static ObjectNode materializeLazyExecutionReferenceV5(ObjectNode options) {
        ObjectNode o = options == null ? object() : options;
        JsonNode reference = first(o, "reference"); JsonNode hydration = first(o, "hydration");
        if (reference == null || !LAZY_REFERENCE_SCHEMA.equals(text(reference.get("schema")))
                || !Set.of(1, 2).contains(reference.path("version").asInt(-1)) || !text(reference.get("content_sha256")).equals(ownHash(reference)))
            throw failure("lazy execution reference is invalid");
        if (hydration == null || !text(hydration.get("content_sha256")).equals(text(reference.get("hydration_sha256"))))
            throw failure("lazy execution reference hydration binding mismatch");
        String windowId = text(reference.get("window_id")); JsonNode capture = null;
        for (JsonNode row : array(hydration.get("windows"))) if (text(row.get("window_id")).equals(windowId)) { capture = row; break; }
        if (capture == null) throw failure("lazy execution reference window is not in the bound hydration");
        ArrayNode refs = array(reference.get("partition_refs")); ArrayNode preentry = array(reference.get("preentry_partition_refs"));
        if (!same(refs, normalizePartitionRefs(capture.get("partition_refs")))) throw failure("lazy execution reference execution refs do not match hydration");
        if (!same(preentry, normalizePartitionRefs(capture.get("preentry_partition_refs")))) throw failure("lazy execution reference pre-entry refs do not match hydration");
        String expectedEnd = text(capture.has("effective_end_exclusive") && !capture.get("effective_end_exclusive").isNull() ? capture.get("effective_end_exclusive") : capture.get("execution_end"));
        if (!text(reference.get("execution_start")).equals(text(capture.get("execution_start"))) || !text(reference.get("execution_end")).equals(expectedEnd)
                || number(reference.get("row_count")) != number(capture.get("row_count")) || !text(reference.get("lifecycle_status")).equals(text(capture.get("lifecycle_status"))))
            throw failure("lazy execution reference range metadata does not match hydration");
        JsonNode expectedPreentry = preentry.isEmpty() ? NullNode.instance : nullable(capture.get("preentry_start"));
        if (!sameNullable(reference.get("preentry_start"), expectedPreentry)) throw failure("lazy execution reference pre-entry boundary does not match hydration");
        if (refs.isEmpty()) throw failure("lazy execution reference has no execution partition references");
        if (!preentry.isEmpty() && !truthy(reference.get("preentry_start"))) throw failure("lazy execution reference pre-entry refs lack a boundary");
        if (preentry.isEmpty() && truthy(reference.get("preentry_start"))) throw failure("lazy execution reference pre-entry boundary lacks refs");
        ArrayNode allRefs = array(); preentry.forEach(allRefs::add); refs.forEach(allRefs::add);
        if (!text(reference.get("partition_root_sha256")).equals(partitionRootSha256(refHashes(preentry, refs)))) throw failure("lazy execution reference partition-root binding is invalid");
        Map<String, ObjectNode> supplied = new HashMap<>(); for (JsonNode p : array(first(o, "partitions"))) supplied.put(text(p.get("sha256")), (ObjectNode) p);
        List<ObjectNode> bound = new ArrayList<>();
        for (JsonNode ref : allRefs) {
            ObjectNode partition = supplied.get(text(ref.get("partition_sha256")));
            if (partition == null) throw failure("missing physical partition " + text(ref.get("partition_sha256")));
            requireHash(text(partition.get("sha256")), "physical partition sha256");
            if (!nullish(ref.get("partition_bytes")) && number(partition.get("bytes")) != number(ref.get("partition_bytes"))) throw failure("physical partition bytes do not match reference " + text(ref.get("partition_sha256")));
            if (!nullish(ref.get("partition_row_count")) && number(partition.get("row_count")) != number(ref.get("partition_row_count"))) throw failure("physical partition row count does not match reference " + text(ref.get("partition_sha256")));
            bound.add(partition);
        }
        List<String> boundHashes = bound.stream().map(p -> text(p.get("sha256"))).toList();
        if (!text(reference.get("partition_root_sha256")).equals(partitionRootSha256(boundHashes))) throw failure("lazy execution reference partition-root does not match supplied physical partitions");
        BoundedPartitionReadCache cache = o.has("partitionCache") && o.get("partitionCache") instanceof ObjectNode ? null : null;
        // The typed overload below supplies the cache. This JSON-only path intentionally remains fail-closed.
        return materializeLazyExecutionReferenceV5((ObjectNode) reference, (ObjectNode) hydration, bound, null,
                intOption(o, "batchSize", 4_096), intOption(o, "maxRows", 100_000), longOption(o, "maxResidentBytes", 192L * 1024 * 1024), longOption(o, "maxOutputBytes", 128L * 1024 * 1024));
    }

    public static ObjectNode materializeLazyExecutionReferenceV5(ObjectNode reference, ObjectNode hydration,
            List<ObjectNode> partitions, BoundedPartitionReadCache partitionCache, int batchSize, int maxRows,
            long maxResidentBytes, long maxOutputBytes) {
        Set<String> neededHashes = new HashSet<>(refHashes(array(reference.get("preentry_partition_refs")),
                array(reference.get("partition_refs"))));
        List<ObjectNode> neededPartitions = partitions.stream()
                .filter(partition -> neededHashes.contains(text(partition.get("sha256")))).toList();
        List<ObjectNode> resolved = partitionCache == null ? partitions : partitionCache.resolve(neededPartitions);
        ObjectNode range = materializeRange(reference, hydration, resolved, batchSize, maxRows, maxResidentBytes, maxOutputBytes, false);
        ObjectNode result = reference.deepCopy();
        result.set("child_bars", range.get("child_bars"));
        result.put("materialized_row_count", range.path("materialized_row_count").asInt());
        if (truthy(reference.get("preentry_start"))) {
            ObjectNode copy = hydration.deepCopy(); ArrayNode windows = array(copy.get("windows"));
            for (JsonNode row : windows) if (text(row.get("window_id")).equals(text(reference.get("window_id")))) {
                ((ObjectNode) row).set("preentry_partition_refs", array()); ((ObjectNode) row).set("partition_refs", array(reference.get("preentry_partition_refs"))); break;
            }
            copy.put("content_sha256", ownHash(copy));
            ObjectNode pre = materializeRange(reference, copy, resolved, batchSize, maxRows, maxResidentBytes, maxOutputBytes, true);
            result.set("preentry_bars", array(pre.get("child_bars"))); result.put("preentry_row_count", pre.path("materialized_row_count").asInt());
        } else { result.set("preentry_bars", array()); result.put("preentry_row_count", 0); }
        result.put("physical_partition_count", new HashSet<>(refHashes(array(reference.get("preentry_partition_refs")), array(reference.get("partition_refs")))).size());
        return result;
    }

    /** Deterministic frozen workload accounting used by the executable benchmark. */
    public static ObjectNode estimateProductionWorkloadV5(ObjectNode options) {
        ObjectNode o = options == null ? object() : options;
        long assets = positiveJsInteger(valueOr(o, "assets", null, 8), "assets");
        long outer = positiveJsInteger(valueOr(o, "outerFolds", "outer_folds", 8), "outer_folds");
        long inner = nonNegativeJsInteger(valueOr(o, "innerFolds", "inner_folds", 2), "inner_folds");
        long population = positiveJsInteger(valueOr(o, "population", null, 48), "population");
        long generations = positiveJsInteger(valueOr(o, "generations", null, 20), "generations");
        long seeds = positiveJsInteger(valueOr(o, "seeds", null, 3), "seeds");
        long workers = positiveJsInteger(valueOr(o, "workers", null, 2), "workers");
        long episodes = positiveJsInteger(valueOr(o, "episodesPerEvaluation", "episodes_per_evaluation", 1), "episodes_per_evaluation");
        String sharing = text(valueOr(o, "partitionSharing", "partition_sharing", "PER_WORKER"));
        if (!Set.of("PER_WORKER", "SHARED_READ_ONLY").contains(sharing)) throw failure("partition_sharing must be PER_WORKER or SHARED_READ_ONLY");
        long runs = assets * outer * (inner + 1); long attempts = population * generations * seeds; long base = runs * attempts;
        long families = jsInteger(valueOr(o, "physicalNullFamilies", "physical_null_families", 4));
        long iterations = jsInteger(valueOr(o, "nullIterations", "null_iterations", 128));
        if (families != 4) throw failure("physical null workload must include exactly four frozen families");
        if (iterations != 128) throw failure("physical null workload must retain the fixed 128-iteration budget");
        long confirmationPerRun = nonNegativeJsInteger(valueOr(o, "confirmationAttemptsPerGaRun", "confirmation_attempts_per_ga_run", 100), "confirmation_attempts_per_ga_run");
        long pboPartitions = positiveJsInteger(valueOr(o, "pboPartitions", "pbo_partitions", 8), "pbo_partitions");
        long pboCandidates = positiveJsInteger(valueOr(o, "pboCandidates", "pbo_candidates", 8), "pbo_candidates");
        long aliases = positiveJsInteger(valueOr(o, "vectorAliasesPerOuterFold", "vector_aliases_per_outer_fold", 48), "vector_aliases_per_outer_fold");
        long selectedPerFold = nonNegativeJsInteger(valueOr(o, "outerSelectedAttemptsPerAssetFold", "outer_selected_attempts_per_asset_fold", 1), "outer_selected_attempts_per_asset_fold");
        long nullGa = base * families * iterations; long confirmation = runs * families * iterations * confirmationPerRun;
        long pbo = assets * outer * families * iterations * pboPartitions * pboCandidates;
        long materialization = outer * families * iterations * aliases; long selected = assets * outer * families * iterations * selectedPerFold;
        long total = nullGa + confirmation + pbo + materialization + selected; long episodeEvaluations = total * episodes;
        double sourceWindows = nonNegativeJsNumber(valueOr(o, "physicalSourceWindows", "physical_source_windows", 0), "physical_source_windows");
        double partitionsPerWindow = nonNegativeJsNumber(valueOr(o, "physicalSourcePartitionsPerWindow", "physical_source_partitions_per_window", 0), "physical_source_partitions_per_window");
        double partitionBytes = nonNegativeJsNumber(valueOr(o, "physicalSourcePartitionBytes", "physical_source_partition_bytes", 0), "physical_source_partition_bytes");
        double sourcePaths = sourceWindows * families * iterations; double reads = sourceWindows * partitionsPerWindow * families * iterations;
        double readBytes = partitionBytes * families * iterations; double cold = partitionBytes * ("SHARED_READ_ONLY".equals(sharing) ? 1 : workers); double warm = Math.max(0, readBytes - cold);
        double roleBytes = nonNegativeJsNumber(valueOr(o, "rolePayloadBytes", "role_payload_bytes", 0), "role_payload_bytes"); double lazyBytes = nonNegativeJsNumber(valueOr(o, "lazyReferenceBytes", "lazy_reference_bytes", 0), "lazy_reference_bytes"); double resident = nonNegativeJsNumber(valueOr(o, "residentPartitionBytes", "resident_partition_bytes", 0), "resident_partition_bytes");
        double before = roleBytes * workers; double unshared = resident * workers; double shared = resident; double after = lazyBytes * workers + ("SHARED_READ_ONLY".equals(sharing) ? shared : unshared);
        ObjectNode assumptions = object().put("confirmation_attempts_per_ga_run", confirmationPerRun).put("pbo_partitions", pboPartitions).put("pbo_candidates", pboCandidates).put("vector_aliases_per_outer_fold", aliases).put("outer_selected_attempts_per_asset_fold", selectedPerFold).put("physical_source_windows", sourceWindows).put("physical_source_partitions_per_window", partitionsPerWindow).put("physical_source_partition_bytes", partitionBytes).put("physical_source_partition_bytes_scope", "FULL_SOURCE_PASS_ACROSS_ALL_WINDOWS").put("bounded_cache_warm_bytes_scope", "UPPER_BOUND_IF_LRU_CAPACITY_CANNOT_RETAIN_ALL_REFERENCED_PARTITIONS");
        ObjectNode result = object().put("schema", WORKLOAD_SCHEMA).put("assets", assets).put("outer_folds", outer)
                .put("inner_folds_per_asset", inner).put("ga_runs", runs).put("population", population)
                .put("generations", generations).put("seeds", seeds).put("null_iterations", iterations)
                .put("physical_null_family_count", families).put("attempts_per_ga_run", attempts)
                .put("base_ga_attempts", base).put("physical_null_ga_attempts", nullGa)
                .put("confirmation_attempts", confirmation).put("pbo_evaluation_attempts", pbo)
                .put("vector_materialization_attempts", materialization).put("outer_selected_attempts", selected)
                .put("physical_null_total_attempts", total).put("physical_null_episode_evaluations", episodeEvaluations)
                .put("physical_source_path_materializations", sourcePaths).put("physical_partition_read_count", reads)
                .put("physical_partition_read_bytes", readBytes).put("bounded_partition_cache_cold_read_bytes", cold)
                .put("bounded_partition_cache_warm_read_bytes", warm).put("bounded_partition_cache_warm_read_bytes_upper_bound", warm);
        result.set("workload_assumptions", assumptions);
        result.put("worker_count", workers).put("full_role_worker_payload_bytes", before)
                .put("lazy_reference_worker_payload_bytes", after).put("resident_partition_bytes_per_worker", resident)
                .put("resident_partition_bytes_unshared", unshared).put("resident_partition_bytes_shared_read_only", shared)
                .put("partition_sharing_model", sharing);
        result.set("worker_payload_reduction_fraction", before > 0 ? numberNode(1 - after / before) : NullNode.instance);
        ObjectNode bounds = object().put("fixed_null_budget", 128).put("cumulative_k_unchanged", true)
                .put("oos_not_read_during_selection", true).put("full_role_replication_avoided", true)
                .put("readiness_requires_authoritative_v2_benchmark", true);
        result.set("bounds", bounds);
        return result;
    }

    public static ObjectNode estimateProductionComplexityV5(ObjectNode options) {
        ObjectNode workload = estimateProductionWorkloadV5(options);
        ObjectNode counts = object();
        counts.set("base_ga", workload.path("base_ga_attempts"));
        counts.set("physical_null_ga", workload.path("physical_null_ga_attempts"));
        counts.set("confirmation", workload.path("confirmation_attempts"));
        counts.set("pbo", workload.path("pbo_evaluation_attempts"));
        counts.set("vector_materialization", workload.path("vector_materialization_attempts"));
        counts.set("outer_selected", workload.path("outer_selected_attempts"));
        counts.set("physical_null_total", workload.path("physical_null_total_attempts"));
        counts.set("episode_evaluations", workload.path("physical_null_episode_evaluations"));
        ObjectNode symbolic = object().put("base_ga", "1*assets*outer_folds*(inner_folds+1)*population*generations*seeds").put("physical_null_ga", "1*physical_null_families*null_iterations*base_ga").put("source_path_materializations", "1*physical_source_windows*physical_null_families*null_iterations").put("partition_reads", "1*physical_source_windows*partitions_per_window*physical_null_families*null_iterations");
        ObjectNode preserving = object();
        preserving.set("fixed_null_budget", workload.path("bounds").path("fixed_null_budget"));
        preserving.put("cumulative_k_unchanged", true).put("oos_not_read_during_selection", true)
                .put("reuse_scope", "SIGNAL_VECTORS_ONLY_UNLESS_LOADER_TRUSTED_PHYSICAL_OUTCOME_CAPABILITY")
                .put("cache_hits_remain_attempts", true);
        ObjectNode result = object().put("schema", COMPLEXITY_SCHEMA).put("workload_sha256", hashV5Performance(workload));
        result.set("operation_counts", counts);
        result.set("symbolic_upper_bounds", symbolic);
        result.set("decision_preserving_contract", preserving);
        return result;
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static ArrayNode array() { return MAPPER.createArrayNode(); }
    private static ArrayNode strings(List<String> values) { ArrayNode a = array(); values.forEach(a::add); return a; }
    private static ArrayNode array(JsonNode value) { return value != null && value.isArray() ? (ArrayNode) value : array(); }
    private static JsonNode first(JsonNode object, String... names) { if (object == null || !object.isObject()) return null; for (String name : names) { JsonNode v = object.get(name); if (v != null) return v; } return null; }
    private static JsonNode valueOr(JsonNode object, String camel, String snake, Object fallback) { JsonNode value = camel == null ? null : object.get(camel); if (value == null && snake != null) value = object.get(snake); return value == null ? MAPPER.valueToTree(fallback) : value; }
    private static ObjectNode objectOrEmpty(JsonNode value) { return value != null && value.isObject() ? (ObjectNode) value : object(); }
    private static ObjectNode cloneNode(JsonNode value) { return value != null && value.isObject() ? (ObjectNode) value.deepCopy() : object(); }
    private static JsonNode cloneAny(JsonNode value) { return value == null ? NullNode.instance : value.deepCopy(); }
    private static JsonNode nullable(JsonNode value) { return value == null || value.isNull() ? NullNode.instance : value.deepCopy(); }
    private static JsonNode nullable(String value) { return value == null ? NullNode.instance : MAPPER.getNodeFactory().textNode(value); }
    private static boolean nullish(JsonNode value) { return value == null || value.isNull() || value.isMissingNode(); }
    private static String text(JsonNode value) { return value == null || value.isNull() || value.isMissingNode() ? "" : value.asText(); }
    private static JsonNode numberNode(double value) { return MAPPER.getNodeFactory().numberNode(value); }
    private static JsonNode numberNode(long value) { return MAPPER.getNodeFactory().numberNode(value); }
    private static JsonNode jsNumberNode(double value) { return Double.isFinite(value) && value == Math.rint(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE ? numberNode((long) value) : numberNode(value); }
    private static String nullableText(JsonNode value) { return nullish(value) ? null : text(value); }
    private static boolean truthy(JsonNode value) { if (nullish(value)) return false; if (value.isBoolean()) return value.booleanValue(); if (value.isNumber()) return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue()); if (value.isTextual()) return !value.textValue().isEmpty(); return true; }
    private static boolean same(JsonNode a, JsonNode b) { return JsonHashes.canonicalString(a == null ? NullNode.instance : a).equals(JsonHashes.canonicalString(b == null ? NullNode.instance : b)); }
    private static boolean sameNullable(JsonNode a, String b) { return b == null ? nullish(a) : text(a).equals(b); }
    private static boolean sameNullable(JsonNode a, JsonNode b) { return nullish(a) && nullish(b) || !nullish(a) && !nullish(b) && same(a, b); }
    private static byte[] jsonBytes(JsonNode value) { try { return MAPPER.writeValueAsBytes(value == null ? NullNode.instance : value); } catch (JsonProcessingException e) { throw failure(e.getMessage()); } }
    private static String jsonString(JsonNode value) { return new String(jsonBytes(value), StandardCharsets.UTF_8); }
    private static byte[] appendLf(byte[] value) { byte[] result = Arrays.copyOf(value, value.length + 1); result[value.length] = '\n'; return result; }
    private static String requireHash(String value, String label) { if (!HASH_RE.matcher(value == null ? "" : value).matches()) throw failure(label + " must be a SHA-256 hash"); return value; }
    private static String nullableHash(JsonNode value, String label) { if (nullish(value) || text(value).isEmpty()) return null; return requireHash(text(value), label); }
    private static long integral(JsonNode value) { if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue()) || value.doubleValue() != Math.rint(value.doubleValue()) || value.doubleValue() > Long.MAX_VALUE || value.doubleValue() < Long.MIN_VALUE) throw failure("numeric value must be an integer"); return value.longValue(); }
    private static long integralOr(JsonNode value, long fallback) { try { return integral(value); } catch (RuntimeException ignored) { return fallback; } }
    private static double number(JsonNode value) { if (value == null || value.isNull() || !value.isNumber()) return Double.NaN; return value.doubleValue(); }
    private static double numberJs(JsonNode value) {
        if (value == null || value.isNull()) return 0;
        if (value.isBoolean()) return value.booleanValue() ? 1 : 0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isTextual()) { String raw = value.textValue().trim(); if (raw.isEmpty()) return 0; try { return Double.parseDouble(raw); } catch (NumberFormatException ignored) { return Double.NaN; } }
        return Double.NaN;
    }
    private static int intOption(JsonNode object, String key, int fallback) { JsonNode v = object.get(key); return v == null ? fallback : (int) integral(v); }
    private static long longOption(JsonNode object, String key, long fallback) { JsonNode v = object.get(key); return v == null ? fallback : integral(v); }
    private static int positiveInt(JsonNode value, String label) { long n = integral(value); if (n < 1 || n > Integer.MAX_VALUE) throw failure(label + " must be a positive integer"); return (int) n; }
    private static long positiveLong(JsonNode value, String label) { long n = integral(value); if (n < 1) throw failure(label + " must be a positive integer"); return n; }
    private static long nonNegativeLong(JsonNode value, String label) { long n = integral(value); if (n < 0) throw failure(label + " must be a non-negative integer"); return n; }
    private static double nonNegativeNumber(JsonNode value, String label) { double n = number(value); if (!Double.isFinite(n) || n < 0) throw failure(label + " must be non-negative"); return n; }
    private static long jsInteger(JsonNode value) { double n = numberJs(value); if (!Double.isFinite(n) || n != Math.rint(n) || n > Long.MAX_VALUE || n < Long.MIN_VALUE) throw failure("numeric value must be an integer"); return (long) n; }
    private static long positiveJsInteger(JsonNode value, String label) { long n; try { n = jsInteger(value); } catch (RuntimeException ignored) { throw failure(label + " must be a positive integer"); } if (n < 1) throw failure(label + " must be a positive integer"); return n; }
    private static long nonNegativeJsInteger(JsonNode value, String label) { long n; try { n = jsInteger(value); } catch (RuntimeException ignored) { throw failure(label + " must be a non-negative integer"); } if (n < 0) throw failure(label + " must be a non-negative integer"); return n; }
    private static double nonNegativeJsNumber(JsonNode value, String label) { double n = numberJs(value); if (!Double.isFinite(n) || n < 0) throw failure(label + " must be non-negative"); return n; }
    private static List<String> uniqueIds(JsonNode value, String label) { if (value == null || !value.isArray()) throw failure(label + " must be an array"); List<String> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); for (JsonNode id : value) { String v = text(id); if (!seen.add(v)) throw failure(label + " contains duplicate episode IDs"); result.add(v); } return result; }
    private static List<String> uniqueIds(List<String> value, String label) { if (value == null) throw failure(label + " must be an array"); List<String> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); for (String id : value) { String v = String.valueOf(id); if (!seen.add(v)) throw failure(label + " contains duplicate episode IDs"); result.add(v); } return result; }
    private static Map<String, JsonNode> objectMap(JsonNode value) { if (value == null || value.isNull()) return Map.of(); if (!value.isObject()) throw failure("feature_by_episode must be an object"); Map<String, JsonNode> result = new HashMap<>(); value.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue())); return result; }
    private static ObjectNode scopeObject(List<String> scope, List<String> requested, String phase, String fold, String fit, String eval) {
        ObjectNode result = object().put("schema", EVALUATION_SCOPE_SCHEMA);
        result.set("episode_ids", strings(scope));
        result.set("requested_episode_ids", strings(requested));
        result.set("phase", nullable(phase));
        result.set("fold_id", nullable(fold));
        result.set("fit_cutoff", nullable(fit));
        result.set("evaluation_cutoff", nullable(eval));
        return result;
    }
    private static Path resolvePath(String value) { return Path.of(value).toAbsolutePath().normalize(); }
    private static String readString(Path path) { try { return Files.readString(path, StandardCharsets.UTF_8); } catch (IOException e) { throw failure("physical partition path cannot be read: " + path); } }
    private static FileSignature signature(ObjectNode partition) { JsonNode p = partition.get("path"); if (nullish(p) || text(p).isEmpty()) return null; try { Path path = resolvePath(text(p)); BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return new FileSignature(a.size(), a.lastModifiedTime().toMillis(), a.fileKey() == null ? 0 : a.fileKey().hashCode()); } catch (IOException e) { throw failure("physical partition path cannot be read: " + text(p)); } }
    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }
}
