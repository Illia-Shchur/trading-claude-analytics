package com.tradinganalytics.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Identity-only custody registry for physical evaluators and their outcome capability. */
public final class PhysicalEvaluatorTrustRegistry {
    public static final String OUTCOME_PROOF_SCHEMA =
            "strategy-v5-scope-independent-outcome-proof/1";
    public static final String OUTCOME_CAPABILITY_SCHEMA =
            "strategy-v5-internal-scope-independent-outcome-capability/1";
    public static final List<String> DATA_BINDING_KEYS = List.of(
            "feature_artifact_sha256",
            "label_artifact_sha256",
            "execution_artifact_sha256",
            "mark_artifact_sha256",
            "metadata_artifact_sha256");

    public record Artifact(String path, String sha256, Long bytes) {
        public Artifact {
            if (path == null || path.isBlank()) {
                throw new CustodyException("manifest artifact path is required");
            }
            sha256 = JsonHashes.requireSha256(sha256, "manifest artifact sha256");
            if (bytes != null && bytes < 0) {
                throw new CustodyException("manifest artifact bytes is invalid");
            }
        }
    }

    public record Manifest(String contentSha256, Map<String, Artifact> artifacts) {
        public Manifest {
            contentSha256 = JsonHashes.requireSha256(contentSha256, "source manifest content_sha256");
            artifacts = Map.copyOf(artifacts);
        }
    }

    @FunctionalInterface
    public interface PitBoundaryVerifier {
        Object verify(Map<String, Object> context);
    }

    @FunctionalInterface
    public interface OutcomeVerifier {
        Object verify(Map<String, Object> context);
    }

    @FunctionalInterface
    public interface OutcomeComputer {
        Object compute(Map<String, Object> context);
    }

    public record ScopeIndependentOutcomeConfiguration(
            String evaluatorSpecSha256,
            Map<String, String> dataBindings,
            JsonNode proof,
            JsonNode metadataSourceBinding,
            PitBoundaryVerifier verifyPitBoundary,
            OutcomeVerifier verifyOutcome,
            OutcomeComputer computeOutcome) {
        public ScopeIndependentOutcomeConfiguration {
            dataBindings = dataBindings == null ? null : Map.copyOf(dataBindings);
            proof = proof == null ? null : proof.deepCopy();
            metadataSourceBinding = metadataSourceBinding == null
                    ? null : metadataSourceBinding.deepCopy();
        }

        @Override public JsonNode proof() { return proof == null ? null : proof.deepCopy(); }
        @Override public JsonNode metadataSourceBinding() {
            return metadataSourceBinding == null ? null : metadataSourceBinding.deepCopy();
        }
    }

    public record Outcome(double netR, boolean traded) {}

    public record Diagnostics(
            String schema,
            long bindingReopenCount,
            long roleBytesReopenCount,
            long metadataReopenCount) {}

    /** Opaque identity token; only a capability can mint a registered instance. */
    public static final class TrustEpoch {
        private TrustEpoch() {}
        public String schema() { return "strategy-v5-evaluation-scope-trust-epoch/1"; }
        public int version() { return 1; }
    }

    public final class ScopeIndependentOutcomeCapability {
        private final Object evaluator;
        private final JsonNode proof;
        private final JsonNode descriptor;
        private final Map<String, String> dataBindings;
        private final Map<String, RoleReader> roleReaders;
        private final Runnable metadataReopener;
        private final PitBoundaryVerifier pitVerifier;
        private final OutcomeVerifier outcomeVerifier;
        private final OutcomeComputer outcomeComputer;
        private final AtomicLong bindingReopenCount = new AtomicLong();
        private final AtomicLong roleBytesReopenCount = new AtomicLong();
        private final AtomicLong metadataReopenCount = new AtomicLong();

        private ScopeIndependentOutcomeCapability(
                Object evaluator,
                JsonNode proof,
                JsonNode descriptor,
                Map<String, String> dataBindings,
                Map<String, RoleReader> roleReaders,
                Runnable metadataReopener,
                PitBoundaryVerifier pitVerifier,
                OutcomeVerifier outcomeVerifier,
                OutcomeComputer outcomeComputer) {
            this.evaluator = evaluator;
            this.proof = proof.deepCopy();
            this.descriptor = descriptor.deepCopy();
            this.dataBindings = Map.copyOf(dataBindings);
            this.roleReaders = Map.copyOf(roleReaders);
            this.metadataReopener = metadataReopener;
            this.pitVerifier = pitVerifier;
            this.outcomeVerifier = outcomeVerifier;
            this.outcomeComputer = outcomeComputer;
        }

        public String schema() { return OUTCOME_CAPABILITY_SCHEMA; }
        public int version() { return 1; }
        public String authority() { return "AUTHORITATIVE_V2_PHYSICAL_EVALUATOR"; }
        public boolean verified() { return true; }
        public Object evaluator() { return evaluator; }
        public JsonNode proof() { return proof.deepCopy(); }
        public JsonNode descriptor() { return descriptor.deepCopy(); }

        public TrustEpoch beginEvaluationScope(Map<String, Object> context) {
            validateContextBinding(context);
            reopenExactBindings();
            TrustEpoch epoch = new TrustEpoch();
            synchronized (trustEpochs) {
                trustEpochs.put(epoch, new EpochState(this, true));
            }
            return epoch;
        }

        public boolean endEvaluationScope(TrustEpoch epoch) {
            EpochState state;
            synchronized (trustEpochs) {
                state = trustEpochs.get(epoch);
                if (state == null || state.capability() != this || !state.active()) {
                    throw new CustodyException(
                            "scope-independent outcome evaluation trust epoch is invalid or already closed");
                }
            }
            try {
                reopenExactBindings();
            } finally {
                synchronized (trustEpochs) {
                    trustEpochs.put(epoch, new EpochState(this, false));
                }
            }
            return true;
        }

        public Outcome computeOutcome(Map<String, Object> context) {
            TrustEpoch epoch = ensureBindings(context);
            Map<String, Object> owned = callbackContext(context, epoch);
            Outcome result = canonicalOutcome(outcomeComputer.compute(owned));
            synchronized (internalOutcomeResults) {
                internalOutcomeResults.put(result, new OutcomeMarker(this, epoch));
            }
            return result;
        }

        public Object verifyOutcome(Map<String, Object> context) {
            TrustEpoch epoch = ensureBindings(context);
            Object expectedCandidate = context == null ? null : context.get("expectedOutcome");
            OutcomeMarker marker;
            synchronized (internalOutcomeResults) {
                marker = expectedCandidate == null ? null : internalOutcomeResults.get(expectedCandidate);
            }
            boolean expectedOwned = marker != null && marker.capability() == this
                    && epoch != null && marker.epoch() == epoch;
            Outcome expected = expectedOwned
                    ? canonicalOutcome(expectedCandidate)
                    : canonicalOutcome(outcomeComputer.compute(callbackContext(context, epoch)));
            Outcome supplied = canonicalOutcome(context == null ? null : context.get("result"));
            if (!expected.equals(supplied)) {
                throw new CustodyException(
                        "loader-owned outcome differs from the canonical physical recomputation for "
                                + stringValue(context == null ? null : context.get("episodeId")));
            }
            Map<String, Object> verifierContext = mutableCopy(callbackContext(context, epoch));
            verifierContext.put("recomputedOutcome", expected);
            Object result = outcomeVerifier.verify(Collections.unmodifiableMap(verifierContext));
            requireProofResult(result,
                    "loader-owned outcome verifier did not prove the physical outcome");
            return defensiveResult(result);
        }

        public Object verifyCachedOutcome(Map<String, Object> context) {
            TrustEpoch epoch = ensureBindings(context);
            Outcome supplied = canonicalOutcome(context == null ? null : context.get("result"));
            Map<String, Object> verifierContext = mutableCopy(callbackContext(context, epoch));
            verifierContext.put("recomputedOutcome", null);
            verifierContext.put("cached", true);
            verifierContext.put("result", supplied);
            Object result = outcomeVerifier.verify(Collections.unmodifiableMap(verifierContext));
            requireProofResult(result,
                    "loader-owned cached outcome verifier did not prove the physical outcome");
            return defensiveResult(result);
        }

        public Object verifyPitBoundary(Map<String, Object> context) {
            TrustEpoch epoch = ensureBindings(context);
            Object result = pitVerifier.verify(callbackContext(context, epoch));
            requireProofResult(result,
                    "loader-owned PIT verifier did not prove the physical episode boundary");
            return defensiveResult(result);
        }

        public Diagnostics diagnostics() {
            return new Diagnostics(
                    "strategy-v5-physical-trust-diagnostics/1",
                    bindingReopenCount.get(), roleBytesReopenCount.get(), metadataReopenCount.get());
        }

        private TrustEpoch ensureBindings(Map<String, Object> context) {
            TrustEpoch epoch = epochState(context);
            if (epoch == null) reopenExactBindings();
            return epoch;
        }

        private TrustEpoch epochState(Map<String, Object> context) {
            Object raw = context == null ? null : context.get("trustEpoch");
            if (raw == null) return null;
            if (!(raw instanceof TrustEpoch epoch)) {
                throw new CustodyException(
                        "scope-independent outcome evaluation trust epoch is invalid or closed");
            }
            EpochState state;
            synchronized (trustEpochs) {
                state = trustEpochs.get(epoch);
            }
            if (state == null || state.capability() != this || !state.active()) {
                throw new CustodyException(
                        "scope-independent outcome evaluation trust epoch is invalid or closed");
            }
            validateContextBinding(context);
            return epoch;
        }

        private void validateContextBinding(Map<String, Object> context) {
            if (context == null
                    || !Objects.equals(context.get("sourceArtifactSha256"),
                            proof.path("source_artifact_sha256").asText())
                    || !Objects.equals(context.get("evaluatorSpecSha256"),
                            proof.path("evaluator_spec_sha256").asText())
                    || !Objects.equals(context.get("outcomeProofSha256"),
                            proof.path("content_sha256").asText())
                    || !JsonHashes.canonicalString(context.get("dataBindings"))
                            .equals(JsonHashes.canonicalString(dataBindings))) {
                throw new CustodyException(
                        "scope-independent outcome capability context binding mismatch");
            }
        }

        private void reopenExactBindings() {
            bindingReopenCount.incrementAndGet();
            for (Map.Entry<String, RoleReader> entry : roleReaders.entrySet()) {
                roleBytesReopenCount.incrementAndGet();
                RoleReader declared = entry.getValue();
                byte[] bytes = PathConfinement.readSinglyLinkedFile(
                        declared.path(), "scope-independent outcome " + entry.getKey() + " role");
                if (!JsonHashes.sha256(bytes).equals(declared.sha256())
                        || (declared.bytes() != null && declared.bytes() != bytes.length)) {
                    throw new CustodyException("scope-independent outcome capability "
                            + entry.getKey() + " bytes are missing or tampered");
                }
            }
            metadataReopenCount.incrementAndGet();
            metadataReopener.run();
        }

        private Map<String, Object> callbackContext(Map<String, Object> context, TrustEpoch epoch) {
            Map<String, Object> owned = mutableCopy(context);
            owned.put("role_bindings_reopened", true);
            owned.put("trust_epoch", epoch);
            return Collections.unmodifiableMap(owned);
        }
    }

    private record RoleReader(Path path, String sha256, Long bytes) {}
    private record EpochState(ScopeIndependentOutcomeCapability capability, boolean active) {}
    private record OutcomeMarker(ScopeIndependentOutcomeCapability capability, TrustEpoch epoch) {}

    private final Set<Object> verified = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Object, ScopeIndependentOutcomeCapability> outcomeCapabilities =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<Object, OutcomeMarker> internalOutcomeResults =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<TrustEpoch, EpochState> trustEpochs =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public synchronized <T> T register(
            T evaluator, Manifest manifest, Path root, Map<String, ?> workerProvenance) {
        return register(evaluator, manifest, root, workerProvenance, null);
    }

    public synchronized <T> T registerInternalVerifiedPhysicalEvaluator(
            T evaluator, Manifest manifest, Path root, Map<String, ?> workerProvenance) {
        return register(evaluator, manifest, root, workerProvenance);
    }

    /** Optional configuration installs the identity-addressed capability during registration. */
    public synchronized <T> T register(
            T evaluator,
            Manifest manifest,
            Path root,
            Map<String, ?> workerProvenance,
            ScopeIndependentOutcomeConfiguration scopeIndependentOutcome) {
        if (evaluator == null) {
            throw new CustodyException("physical evaluator trust marker requires an evaluator object");
        }
        if (manifest == null || root == null || workerProvenance == null) {
            throw new CustodyException(
                    "physical evaluator trust registration requires the verified role manifest and root");
        }
        Path base = PathConfinement.requireRealDirectory(root, "physical evaluator authoritative root");
        for (String role : List.of("feature", "label", "execution")) {
            Artifact artifact = manifest.artifacts().get(role);
            if (artifact == null) {
                throw new CustodyException(
                        "physical evaluator trust manifest lacks the " + role + " role hash");
            }
            Path path = safeArtifactPath(base, artifact.path(), role);
            byte[] bytes = PathConfinement.readSinglyLinkedFile(path, "physical evaluator " + role);
            if (!JsonHashes.sha256(bytes).equals(artifact.sha256())
                    || (artifact.bytes() != null && artifact.bytes() != bytes.length)) {
                throw new CustodyException(
                        "physical evaluator trust " + role + " bytes are missing or tampered");
            }
            if (!artifact.sha256().equals(String.valueOf(
                    workerProvenance.get(role + "_artifact_sha256")))) {
                throw new CustodyException(
                        "physical evaluator trust " + role + " provenance is not manifest-bound");
            }
        }
        if (!manifest.contentSha256().equals(String.valueOf(workerProvenance.get("source_manifest_sha256")))
                || !Boolean.TRUE.equals(workerProvenance.get("physical_role_binding"))
                || !Boolean.TRUE.equals(workerProvenance.get("artifact_paths_bound"))
                || !Boolean.TRUE.equals(workerProvenance.get("deterministic"))) {
            throw new CustodyException(
                    "physical evaluator trust provenance is not bound to the authoritative role manifest");
        }
        verified.add(evaluator);
        if (scopeIndependentOutcome != null) {
            installOutcomeCapability(evaluator, manifest, base, scopeIndependentOutcome);
        }
        return evaluator;
    }

    public synchronized <T> T registerInternalVerifiedPhysicalEvaluator(
            T evaluator,
            Manifest manifest,
            Path root,
            Map<String, ?> workerProvenance,
            ScopeIndependentOutcomeConfiguration scopeIndependentOutcome) {
        return register(evaluator, manifest, root, workerProvenance, scopeIndependentOutcome);
    }

    public synchronized boolean isVerified(Object evaluator) {
        return evaluator != null && verified.contains(evaluator);
    }

    public synchronized boolean isVerifiedPhysicalEvaluator(Object evaluator) {
        return isVerified(evaluator);
    }

    /** Exact Java counterpart of {@code getInternalScopeIndependentOutcomeCapability}. */
    public ScopeIndependentOutcomeCapability getInternalScopeIndependentOutcomeCapability(
            Object evaluator) {
        return evaluator == null ? null : outcomeCapabilities.get(evaluator);
    }

    private void installOutcomeCapability(
            Object evaluator,
            Manifest manifest,
            Path root,
            ScopeIndependentOutcomeConfiguration configuration) {
        if (!isVerified(evaluator)) {
            throw new CustodyException(
                    "scope-independent outcome capability requires a registered physical evaluator");
        }
        String evaluatorSpec = JsonHashes.requireSha256(
                configuration.evaluatorSpecSha256(),
                "scope-independent outcome capability evaluator_spec_sha256");
        Map<String, String> bindings = normalizeDataBindings(configuration.dataBindings());
        Map<String, RoleReader> roleReaders = new LinkedHashMap<>();
        for (String role : List.of("feature", "label", "execution", "mark")) {
            Artifact declared = manifest.artifacts().get(role);
            if (declared == null) {
                throw new CustodyException(
                        "scope-independent outcome capability manifest lacks the " + role + " role hash");
            }
            Path path = safeArtifactPath(root, declared.path(), role);
            if (!bindings.get(role + "_artifact_sha256").equals(declared.sha256())) {
                throw new CustodyException("scope-independent outcome capability " + role
                        + " binding differs from the manifest");
            }
            roleReaders.put(role, new RoleReader(path,
                    JsonHashes.requireSha256(declared.sha256(), role + "_artifact_sha256"),
                    declared.bytes()));
        }
        Runnable metadataReopener = makeMetadataReopener(
                configuration.metadataSourceBinding(), bindings.get("metadata_artifact_sha256"));
        JsonNode proof = configuration.proof();
        if (proof == null || !proof.isObject()
                || !OUTCOME_PROOF_SCHEMA.equals(proof.path("schema").asText())
                || proof.path("version").asInt(Integer.MIN_VALUE) != 1
                || !proof.path("content_sha256").asText().equals(JsonHashes.ownHash(proof))
                || !"AUTHORITATIVE_V2_PHYSICAL_EVALUATOR".equals(proof.path("authority").asText())
                || !proof.path("verified").asBoolean(false)
                || !manifest.contentSha256().equals(proof.path("source_artifact_sha256").asText())
                || !evaluatorSpec.equals(proof.path("evaluator_spec_sha256").asText())
                || !JsonHashes.canonicalSha256(bindings)
                        .equals(proof.path("data_bindings_sha256").asText())
                || !"CHECK_BEFORE_EVALUATION_AND_ON_CACHE_HIT"
                        .equals(proof.path("pit_boundary_contract").asText())
                || !"FEATURE_LABEL_EXECUTION_MARK_METADATA_EXACT_BINDINGS"
                        .equals(proof.path("outcome_role_contract").asText())
                || !proof.path("one_episode_read_contract").asBoolean(false)) {
            throw new CustodyException(
                    "scope-independent outcome capability proof is invalid or not loader-bound");
        }
        JsonHashes.requireSha256(proof.path("physical_evaluator_code_sha256").asText(),
                "physical_evaluator_code_sha256");
        JsonHashes.requireSha256(proof.path("pit_validator_code_sha256").asText(),
                "pit_validator_code_sha256");
        if (configuration.verifyPitBoundary() == null || configuration.verifyOutcome() == null
                || configuration.computeOutcome() == null) {
            throw new CustodyException(
                    "scope-independent outcome capability requires loader-owned PIT and outcome verifiers");
        }

        ObjectNode descriptorBody = JsonHashes.mapper().createObjectNode();
        descriptorBody.put("schema",
                "strategy-v5-internal-scope-independent-outcome-capability-descriptor/1");
        descriptorBody.put("version", 1);
        descriptorBody.put("source_artifact_sha256", manifest.contentSha256());
        descriptorBody.put("evaluator_spec_sha256", evaluatorSpec);
        descriptorBody.set("data_bindings", JsonHashes.mapper().valueToTree(bindings));
        descriptorBody.put("data_bindings_sha256", JsonHashes.canonicalSha256(bindings));
        descriptorBody.put("outcome_proof_sha256", proof.path("content_sha256").asText());
        descriptorBody.put("role_bytes_reopened_at_evaluation_scope_boundaries", true);
        ObjectNode descriptor = descriptorBody.deepCopy();
        descriptor.put("content_sha256", JsonHashes.ownHash(descriptorBody));
        ScopeIndependentOutcomeCapability capability = new ScopeIndependentOutcomeCapability(
                evaluator, proof, descriptor, bindings, roleReaders, metadataReopener,
                configuration.verifyPitBoundary(), configuration.verifyOutcome(),
                configuration.computeOutcome());
        outcomeCapabilities.put(evaluator, capability);
    }

    private static Map<String, String> normalizeDataBindings(Map<String, String> value) {
        if (value == null || !value.keySet().equals(new LinkedHashSet<>(DATA_BINDING_KEYS))) {
            throw new CustodyException(
                    "scope-independent outcome capability data bindings must contain exactly "
                            + String.join(", ", DATA_BINDING_KEYS));
        }
        Map<String, String> output = new LinkedHashMap<>();
        for (String key : DATA_BINDING_KEYS) {
            output.put(key, JsonHashes.requireSha256(value.get(key), key));
        }
        return Collections.unmodifiableMap(output);
    }

    private static Runnable makeMetadataReopener(JsonNode binding, String expectedDigest) {
        if (binding == null || !binding.isObject() || !binding.path("root").isTextual()
                || !binding.path("receipts").isObject()
                || !expectedDigest.equals(binding.path("digest").asText())) {
            throw new CustodyException(
                    "scope-independent outcome capability lacks the physically reopened metadata binding");
        }
        Path root;
        try {
            root = PathConfinement.requireRealDirectory(
                    Path.of(binding.path("root").asText()), "scope-independent outcome metadata root");
        } catch (RuntimeException error) {
            throw new CustodyException(
                    "scope-independent outcome capability lacks the physically reopened metadata binding",
                    error);
        }
        JsonNode receipts = binding.path("receipts").deepCopy();
        ObjectNode digestPayload = JsonHashes.mapper().createObjectNode();
        receipts.fields().forEachRemaining(entry -> {
            JsonNode source = entry.getValue();
            ObjectNode target = digestPayload.putObject(entry.getKey());
            copyNullable(target, "receipt_content_sha256", source.get("receipt_content_sha256"));
            copyNullable(target, "receipt_byte_sha256", source.get("receipt_byte_sha256"));
            ArrayNode normalized = target.putArray("normalized");
            JsonNode rows = source.path("normalized");
            if (rows.isArray()) {
                rows.forEach(row -> {
                    ObjectNode copy = normalized.addObject();
                    copyNullable(copy, "summary", row.get("summary"));
                    copyNullable(copy, "content_sha256", row.get("content_sha256"));
                    copyNullable(copy, "byte_sha256", row.get("byte_sha256"));
                    copyNullable(copy, "raw_byte_sha256", row.get("raw_byte_sha256"));
                    copy.set("raw_receipts", row.path("raw_receipts").isArray()
                            ? row.path("raw_receipts").deepCopy()
                            : JsonHashes.mapper().createArrayNode());
                });
            }
        });
        if (!JsonHashes.canonicalSha256(digestPayload).equals(expectedDigest)) {
            throw new CustodyException("scope-independent outcome metadata binding digest is invalid");
        }
        return () -> reopenMetadata(root, receipts);
    }

    private static void reopenMetadata(Path root, JsonNode receipts) {
        var kinds = receipts.fields();
        while (kinds.hasNext()) {
            Map.Entry<String, JsonNode> kindEntry = kinds.next();
            String kind = kindEntry.getKey();
            JsonNode normalizedRows = kindEntry.getValue().path("normalized");
            if (!normalizedRows.isArray() || normalizedRows.isEmpty()) {
                throw new CustodyException(
                        "scope-independent outcome metadata " + kind + " normalized custody is missing");
            }
            for (JsonNode normalized : normalizedRows) {
                JsonNode summary = normalized.path("summary");
                Path path = safeArtifactPath(root, summary.path("path").asText(null),
                        kind + " metadata");
                byte[] bytes = PathConfinement.readSinglyLinkedFile(
                        path, "scope-independent outcome " + kind + " normalized metadata");
                if (!JsonHashes.sha256(bytes).equals(normalized.path("byte_sha256").asText())
                        || (summary.has("bytes") && summary.path("bytes").asLong(Long.MIN_VALUE) != bytes.length)) {
                    throw new CustodyException("scope-independent outcome metadata " + kind
                            + " bytes are missing or tampered");
                }
                JsonNode parsed;
                try {
                    parsed = JsonHashes.parse(bytes,
                            "scope-independent outcome metadata " + kind + " normalized receipt");
                } catch (CustodyException error) {
                    throw new CustodyException("scope-independent outcome metadata " + kind
                            + " normalized receipt is invalid: " + rootCauseMessage(error), error);
                }
                if (!parsed.isObject()
                        || !parsed.path("content_sha256").asText()
                                .equals(normalized.path("content_sha256").asText())
                        || !parsed.path("content_sha256").asText().equals(JsonHashes.ownHash(parsed))) {
                    throw new CustodyException("scope-independent outcome metadata " + kind
                            + " normalized receipt content binding is invalid");
                }
                JsonNode rawReceipts = parsed.path("raw_receipts").isArray()
                        ? parsed.path("raw_receipts") : JsonHashes.mapper().createArrayNode();
                JsonNode boundRaw = normalized.path("raw_receipts").isArray()
                        ? normalized.path("raw_receipts") : JsonHashes.mapper().createArrayNode();
                if (!rawInventory(rawReceipts).equals(rawInventory(boundRaw))) {
                    throw new CustodyException("scope-independent outcome metadata " + kind
                            + " raw receipt inventory binding is invalid");
                }
                List<String> actualHashes = new ArrayList<>();
                for (JsonNode raw : rawReceipts) {
                    if (!raw.isObject() || !raw.path("path").isTextual()
                            || !JsonHashes.isSha256(raw.path("byte_sha256").asText())
                            || !raw.path("bytes").canConvertToLong()
                            || raw.path("bytes").asLong() < 0) {
                        throw new CustodyException("scope-independent outcome metadata " + kind
                                + " raw receipt binding is invalid");
                    }
                    Path rawPath = safeArtifactPath(root, raw.path("path").asText(),
                            kind + " raw metadata bytes");
                    byte[] rawBytes = PathConfinement.readSinglyLinkedFile(
                            rawPath, "scope-independent outcome " + kind + " raw metadata");
                    if (rawBytes.length != raw.path("bytes").asLong()
                            || !JsonHashes.sha256(rawBytes).equals(raw.path("byte_sha256").asText())) {
                        throw new CustodyException(
                                "scope-independent outcome raw metadata bytes are missing or tampered: "
                                        + kind);
                    }
                    if (raw.hasNonNull("content_sha256")
                            && !raw.path("content_sha256").asText().equals(JsonHashes.ownHash(raw))) {
                        throw new CustodyException("scope-independent outcome metadata " + kind
                                + " raw receipt content binding is invalid");
                    }
                    actualHashes.add(raw.path("byte_sha256").asText());
                }
                actualHashes.sort(String::compareTo);
                List<String> expectedHashes = new ArrayList<>();
                JsonNode rawHashes = normalized.path("raw_byte_sha256");
                if (rawHashes.isArray()) rawHashes.forEach(row -> expectedHashes.add(row.asText()));
                expectedHashes.sort(String::compareTo);
                if (!actualHashes.equals(expectedHashes)) {
                    throw new CustodyException("scope-independent outcome metadata " + kind
                            + " raw byte inventory binding is invalid");
                }
            }
        }
    }

    private static List<String> rawInventory(JsonNode rows) {
        List<JsonNode> normalized = new ArrayList<>();
        rows.forEach(value -> {
            ObjectNode row = JsonHashes.mapper().createObjectNode();
            if (value.has("path")) row.set("path", value.get("path")); else row.putNull("path");
            if (value.path("bytes").isNumber()) row.set("bytes", value.path("bytes"));
            else row.putNull("bytes");
            if (value.has("byte_sha256")) row.set("byte_sha256", value.get("byte_sha256"));
            else row.putNull("byte_sha256");
            if (value.hasNonNull("content_sha256")) row.set("content_sha256", value.get("content_sha256"));
            else row.putNull("content_sha256");
            normalized.add(row);
        });
        normalized.sort(Comparator.comparing(row -> row.path("path").asText()));
        return normalized.stream().map(JsonHashes::canonicalString).toList();
    }

    private static Path safeArtifactPath(Path root, String child, String label) {
        if (child == null) {
            throw new CustodyException(
                    "physical evaluator trust " + label + " path escapes the authoritative root");
        }
        try {
            return PathConfinement.resolve(root, child, "physical evaluator trust " + label,
                    PathConfinement.ExpectedType.FILE).absolute();
        } catch (CustodyException error) {
            String message = error.getMessage();
            if (message != null && (message.contains("relative") || message.contains("traversal")
                    || message.contains("outside") || message.contains("escapes"))) {
                throw new CustodyException("physical evaluator trust " + label
                        + " path escapes the authoritative root", error);
            }
            throw error;
        }
    }

    private static Outcome canonicalOutcome(Object result) {
        if (result instanceof Outcome outcome) return outcome;
        JsonNode node;
        try {
            node = result instanceof JsonNode json ? json : JsonHashes.mapper().valueToTree(result);
        } catch (RuntimeException error) {
            throw new CustodyException("loader-owned outcome recomputation returned an invalid result");
        }
        if (node == null || !node.isObject() || !node.path("net_r").isNumber()) {
            throw new CustodyException("loader-owned outcome recomputation returned an invalid result");
        }
        double netR = node.path("net_r").doubleValue();
        if (!Double.isFinite(netR)) {
            throw new CustodyException("loader-owned outcome recomputation returned an invalid result");
        }
        boolean traded = !(node.has("traded") && node.path("traded").isBoolean()
                && !node.path("traded").asBoolean());
        return new Outcome(netR, traded);
    }

    private static void requireProofResult(Object result, String message) {
        if (Boolean.TRUE.equals(result)) return;
        JsonNode value = result instanceof JsonNode node ? node : JsonHashes.mapper().valueToTree(result);
        if (value == null || !value.isObject() || !value.path("verified").asBoolean(false)
                || !value.path("content_sha256").asText().equals(JsonHashes.ownHash(value))) {
            throw new CustodyException(message);
        }
    }

    private static Object defensiveResult(Object result) {
        return result instanceof JsonNode node ? node.deepCopy() : result;
    }

    private static Map<String, Object> mutableCopy(Map<String, Object> context) {
        return context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context);
    }

    private static void copyNullable(ObjectNode target, String name, JsonNode value) {
        if (value == null) target.putNull(name); else target.set(name, value.deepCopy());
    }

    private static String stringValue(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage();
    }
}
