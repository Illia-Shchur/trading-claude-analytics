package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, read-only projection for the local Strategy Research Cockpit.
 *
 * <p>This is deliberately a projection boundary: it consumes only small, validated v5 JSON
 * records and never reads Parquet/raw evidence. The resulting bytes are presentation data, not
 * an evidence source and cannot authorize, activate, size, or place a trade.</p>
 */
public final class StrategyResearchUiExportV5 {
    public static final String SCHEMA = "strategy-research-ui/1";
    /** v5's existing index contract is strategy-research-index/5. */
    private static final String INDEX_SCHEMA = "strategy-research-index/5";
    private static final String READINESS_SCHEMA = "strategy-readiness-audit/2";
    private static final String RUN_SCHEMA = "strategy-research-run/5";
    /** Logical contract identity; physical paths must never enter the presentation hash. */
    private static final String SOURCE_ROOT_ID = "strategy-research/v5-records";
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ResearchSchemaRegistry SCHEMAS = ResearchSchemaRegistry.defaultRegistry();

    private StrategyResearchUiExportV5() { }

    /** Produce the canonical development fixture. It is never read from production records. */
    public static ObjectNode fixture() {
        ObjectNode export = base(true, "FIXTURE — NOT EVIDENCE", "fixture://strategy-research-ui/1");
        export.with("manifest").put("status", "READY").put("record_count", 1)
                .putArray("source_hashes").add(StrategyResearchV5.hash(JSON.objectNode().put("fixture", true)));
        export.with("readiness").put("status", "READY").putArray("blockers");
        ArrayNode dimensions = export.with("readiness").putArray("dimensions");
        dimensions.add(dimension("records", "READY", "Fixture record is complete for UI development only."));
        dimensions.add(dimension("evidence", "BLOCKED", "Fixture data is explicitly not evidence."));
        export.putArray("strategies").add(fixtureStrategy());
        return withRootHash(export);
    }

    /**
     * Export v5 records under {@code --root}. A missing or unsupported root emits an honest BLOCKED
     * projection; malformed v5 records fail closed rather than being partially projected.
     */
    public static ObjectNode export(Path root) {
        Path normalized = root == null ? Path.of("strategy-research", "v5-records")
                : root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("v5 presentation root is not a directory: " + normalized);
        }
        return export(root, null);
    }

    /**
     * Export with an optional caller-declared freshness timestamp. When omitted, the newest
     * timestamp in the validated source artifacts is used. The physical root is deliberately
     * absent from the hash-bound projection so relocating a record root does not change bytes.
     */
    public static ObjectNode export(Path root, Instant asOf) {
        Path normalized = root == null ? Path.of("strategy-research", "v5-records")
                : root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("v5 presentation root is not a directory: " + normalized);
        }
        Path physicalRoot = realPath(normalized);
        ObjectNode result = base(false, "PRODUCTION", SOURCE_ROOT_ID);
        Path indexPath = safeFile(physicalRoot, physicalRoot.resolve("index.json"));
        if (!Files.isRegularFile(indexPath)) {
            return blocked(result, "MISSING_V5_INDEX", "v5 index is not present");
        }
        JsonNode index = read(indexPath);
        if (!INDEX_SCHEMA.equals(index.path("schema").asText())) {
            return blocked(result, "LEGACY_INDEX", "only strategy-research-index/5 is accepted");
        }
        validateIndexedRecord(index, indexPath);

        ArrayNode strategies = result.putArray("strategies");
        // strategy-research-index/5 is intentionally a compact record index. It points at
        // validated family/run records; the exporter never walks a data lake or infers records
        // from directory names.
        ArrayNode records = array(index, "records");
        List<String> sourceHashes = new ArrayList<>();
        sourceHashes.add(index.path("content_sha256").asText());
        Map<String, ObjectNode> byFamily = new LinkedHashMap<>();
        JsonNode latestReadiness = null;
        Instant latestReadinessAt = null;
        String latestReadinessHash = "";
        Instant latestSourceAt = artifactTimestamp(index);
        List<String> skipped = new ArrayList<>();
        for (JsonNode record : records) {
            if (!record.isObject() || !record.hasNonNull("path")) {
                throw new IllegalArgumentException("v5 index contains a record without a physical path");
            }
            Path sourcePath = pathFor(physicalRoot, record);
            JsonNode source = read(sourcePath);
            validateIndexedRecord(source, sourcePath);
            String indexedContent = record.path("content_sha256").asText();
            String indexedBytes = record.path("byte_sha256").asText();
            if (!indexedContent.equals(source.path("content_sha256").asText())) {
                throw new IllegalArgumentException("v5 index content hash mismatch at " + sourcePath);
            }
            if (!SHA256.matcher(indexedBytes).matches()
                    || !indexedBytes.equals(JsonHashes.sha256(readBytes(sourcePath)))) {
                throw new IllegalArgumentException("v5 index byte hash mismatch at " + sourcePath);
            }
            sourceHashes.add(indexedContent);
            Instant sourceAt = artifactTimestamp(source);
            if (sourceAt != null && (latestSourceAt == null || sourceAt.isAfter(latestSourceAt))) latestSourceAt = sourceAt;
            String sourceSchema = source.path("schema").asText();
            if (READINESS_SCHEMA.equals(sourceSchema)) {
                if (latestReadiness == null || isNewer(sourceAt, indexedContent, latestReadinessAt, latestReadinessHash)) {
                    latestReadiness = source;
                    latestReadinessAt = sourceAt;
                    latestReadinessHash = indexedContent;
                }
                continue;
            }
            // The index also contains plans, catalogs, manifests, and other v5 dependencies.
            // They are validated above but are not strategy families/runs and must not become
            // invented UI strategies.
            if (!RUN_SCHEMA.equals(sourceSchema)) {
                skipped.add(sourceSchema);
                continue;
            }
            validateIdentity(record, source, sourcePath);
            validatePredictors(source);
            String familyKey = first(record, "strategy_family_id");
            if (familyKey.isBlank()) familyKey = first(source, "family_id", "hypothesis_family", "precommit_id");
            if (familyKey.isBlank()) {
                skipped.add("strategy-research-run/5 (missing family identity)");
                continue;
            }
            String familyVersion = first(record, "strategy_version");
            if (familyVersion.isBlank()) familyVersion = first(source, "strategy_version");
            if (familyVersion.isBlank()) {
                skipped.add("strategy-research-run/5 (missing strategy version)");
                continue;
            }
            ObjectNode projected = byFamily.get(familyKey);
            if (projected == null) {
                projected = projectFamily(source);
                projected.put("family_id", familyKey);
                if (!familyVersion.isBlank()) projected.put("strategy_version", familyVersion);
                String familyStatus = first(record, "status");
                if (familyStatus.isBlank()) familyStatus = first(source, "decision");
                if (!familyStatus.isBlank()) projected.put("status", familyStatus);
                String familyStage = first(record, "evidence_phase");
                if (familyStage.isBlank()) familyStage = first(source, "evidence_phase");
                if (!familyStage.isBlank()) projected.put("stage", familyStage);
                JsonNode assets = record.get("asset_set");
                if (assets != null && assets.isArray()) projected.set("assets", assets.deepCopy());
                projected.putArray("runs");
                byFamily.put(familyKey, projected);
            } else if (!familyVersion.equals(projected.path("strategy_version").asText())) {
                throw new IllegalArgumentException("strategy family has multiple versions: " + familyKey);
            }
            String runHash = source.path("content_sha256").asText();
            for (JsonNode existing : projected.path("runs")) {
                if (runHash.equals(existing.path("run_hash").asText())) {
                    throw new IllegalArgumentException("strategy family has duplicate run hash: " + runHash);
                }
            }
            projected.withArray("runs").add(projectRun(source, familyKey, familyVersion));
        }
        byFamily.values().forEach(family -> {
            ArrayNode familyRuns = (ArrayNode) family.path("runs");
            family.set("last_run", familyRuns.isEmpty() ? JSON.nullNode() : familyRuns.get(familyRuns.size() - 1));
            strategies.add(family);
        });
        ArrayNode hashes = result.with("manifest").putArray("source_hashes");
        sourceHashes.stream().filter(value -> !value.isBlank()).distinct().sorted().forEach(hashes::add);
        if (!skipped.isEmpty()) {
            skipped.stream().distinct().sorted().forEach(value -> result.with("manifest").withArray("limitations")
                    .add("Validated non-strategy record skipped: " + value));
        }
        String readinessStatus = "EMPTY";
        if (latestReadiness != null) {
            applyReadiness(result.with("readiness"), latestReadiness, latestReadinessHash);
            readinessStatus = result.path("readiness").path("status").asText("EMPTY");
        }
        int count = strategies.size();
        String status = count == 0 ? readinessStatus : "READY";
        result.with("manifest").put("status", status).put("record_count", count);
        if (latestReadiness == null) {
            result.with("readiness").put("status", count == 0 ? "EMPTY" : "READY");
            result.with("readiness").putArray("blockers");
            result.with("readiness").putArray("dimensions").add(dimension("records", count == 0 ? "EMPTY" : "READY",
                    count == 0 ? "No validated v5 family records are available." : "Validated v5 family records loaded."));
        }
        if (asOf != null) result.put("generated_at", asOf.toString());
        else if (latestSourceAt != null) result.put("generated_at", latestSourceAt.toString());
        else result.with("manifest").withArray("limitations").add("Freshness unavailable: validated source artifacts have no timestamp.");
        return withRootHash(result);
    }

    /** Write exactly the canonical pretty JSON bytes used by existing analytics contracts. */
    public static ObjectNode write(Path root, Path output, boolean fixture) {
        return write(root, output, fixture, null);
    }

    /** Write a production export with an optional explicit as-of timestamp. */
    public static ObjectNode write(Path root, Path output, boolean fixture, Instant asOf) {
        ObjectNode value = fixture ? fixture() : export(root, asOf);
        if (output == null) return value;
        Path target = output.toAbsolutePath().normalize();
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(target, NodePrettyJson.write(value), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot write strategy research presentation export: " + target, error);
        }
        return value;
    }

    private static ObjectNode base(boolean fixture, String sourceKind, String sourceRoot) {
        ObjectNode value = JSON.objectNode();
        value.put("schema", SCHEMA).put("version", 1).put("presentation_only", true)
                .put("fixture_only", fixture);
        if (fixture) value.put("generated_at", Instant.parse("2026-01-01T00:00:00Z").toString());
        ObjectNode manifest = value.putObject("manifest").put("status", "EMPTY")
                .put("source_root", sourceRoot).put("record_count", 0);
        manifest.putArray("source_hashes").add(StrategyResearchV5.hash(JSON.objectNode().put("source", sourceKind)));
        manifest.putArray("limitations").add(fixture ? "FIXTURE — NOT EVIDENCE" : "Projection only; never query this export as evidence.");
        ObjectNode readiness = value.putObject("readiness").put("status", "EMPTY");
        readiness.putArray("dimensions");
        readiness.putArray("blockers");
        return value;
    }

    private static ObjectNode blocked(ObjectNode value, String code, String detail) {
        value.with("manifest").put("status", "BLOCKED").putArray("limitations").add(detail);
        value.with("readiness").put("status", "BLOCKED").putArray("blockers").add(code + ": " + detail);
        value.with("readiness").putArray("dimensions").add(dimension("records", "BLOCKED", detail));
        value.putArray("strategies");
        return withRootHash(value);
    }

    private static ObjectNode fixtureStrategy() {
        ObjectNode strategy = JSON.objectNode().put("family_id", "fixture-mean-reversion")
                .put("strategy_version", "v001").put("status", "SHADOW").put("stage", "CORE_PREMISE")
                .put("phase", "DEVELOPMENT").put("premise", "Forced deleveraging creates a temporary liquidity imbalance.")
                .put("expected_vs_realized", "Expected: short-lived mean reversion; realized: fixture only.");
        strategy.putArray("assets").add("btc").add("eth");
        ArrayNode predictors = strategy.putArray("declared_predictors");
        predictors.addObject().put("id", "close").put("label", "Completed-bar close").put("source", "BINANCE_SPOT");
        predictors.addObject().put("id", "funding_rate").put("label", "Same-asset funding").put("source", "BINANCE_USDM_PERPETUAL");
        ObjectNode run = strategy.putArray("runs").addObject().put("run_hash", StrategyResearchV5.hash(JSON.objectNode().put("fixture_run", true)))
                .put("status", "SHADOW").put("stage", "CORE_PREMISE").put("evidence_phase", "DEVELOPMENT")
                .put("k", 1).put("gate_failures", 0);
        run.putObject("identity").put("family_id", "fixture-mean-reversion").put("strategy_version", "v001")
                .put("run_hash", run.path("run_hash").asText());
        ObjectNode candidate = run.putArray("candidates").addObject().put("candidate_id", "fixture-candidate")
                .put("selected", true).put("finalist", true).put("evidence_phase", "DEVELOPMENT");
        ArrayNode bars = candidate.putObject("market").putArray("completed_bars");
        bars.addObject().put("time", "2026-01-01T00:00:00Z").put("open", 100).put("high", 102).put("low", 99).put("close", 101)
                .put("funding_rate", 0.0001);
        candidate.putArray("signals").addObject().put("id", "fixture-signal").put("time", "2026-01-01T00:00:00Z")
                .put("predictor", "close").put("value", 101).put("intent", "LONG");
        candidate.putArray("trades").addObject().put("id", "fixture-trade-1").put("asset", "btc")
                .put("entry_time", "2026-01-01T00:00:00Z").put("net_r", 0.3);
        candidate.putObject("performance").put("expectancy_r", 0.3).put("win_rate", 1.0).put("max_drawdown_r", 0.0);
        ObjectNode robustness = candidate.putObject("robustness");
        robustness.set("wfo", JSON.objectNode().put("status", "UNAVAILABLE"));
        robustness.set("bootstrap", JSON.objectNode().put("status", "UNAVAILABLE"));
        ObjectNode shadow = run.putObject("shadow");
        shadow.set("frozen_signal_lane", JSON.objectNode().put("status", "MONITORING").put("trades", 1));
        shadow.set("real_deal_lane", JSON.objectNode().put("status", "NO_LINKED_DEALS").put("trades", 0));
        run.putObject("lineage").put("precommit_sha256", StrategyResearchV5.hash(JSON.objectNode().put("fixture", "precommit")))
                .put("dataset_sha256", StrategyResearchV5.hash(JSON.objectNode().put("fixture", "dataset")));
        return strategy;
    }

    private static ObjectNode projectFamily(JsonNode source) {
        ObjectNode output = JSON.objectNode();
        copy(source, output, "family_id", "strategy_version", "status", "stage", "phase", "assets", "premise",
                "causal_premise", "expected_vs_realized", "declared_predictors", "lineage");
        if (!output.has("family_id")) output.put("family_id", first(source, "hypothesis_family", "precommit_id"));
        if (!output.has("strategy_version")) output.put("strategy_version", first(source, "strategy_version"));
        if (!output.has("declared_predictors")) output.putArray("declared_predictors");
        return output;
    }

    private static ObjectNode projectRun(JsonNode source, String familyId, String strategyVersion) {
        ObjectNode output = JSON.objectNode();
        output.put("run_hash", source.path("content_sha256").asText());
        copy(source, output, "status", "stage", "phase", "evidence_phase", "k", "gate_failures",
                "decision", "gate_status", "wfo", "shadow", "lineage", "stage_artifacts", "stage_artifact_refs",
                "accounting");
        if (!output.has("status")) {
            String decision = first(source, "decision");
            if (!decision.isBlank()) output.put("status", decision);
        }
        if (!output.has("stage")) {
            String evidencePhase = first(source, "evidence_phase");
            if (!evidencePhase.isBlank()) output.put("stage", evidencePhase);
        }
        output.putObject("identity").put("family_id", familyId).put("strategy_version", strategyVersion)
                .put("run_hash", output.path("run_hash").asText());
        ArrayNode candidates = output.putArray("candidates");
        for (JsonNode sourceCandidate : source.path("candidate_metrics")) {
            ObjectNode candidate = candidates.addObject();
            copy(sourceCandidate, candidate, "candidate_id", "asset", "fold_id", "selected", "finalist",
                    "evidence_phase", "metric_phase", "weighting", "scope_episode_count", "trade_scope");
            copyObject(sourceCandidate, candidate, "metrics", "performance");
            copyObject(sourceCandidate, candidate, "stresses", "robustness");
            copyObject(sourceCandidate, candidate, "portfolio", "portfolio");
            copyArray(sourceCandidate, candidate, "trades", "trades");
            // strategy-research-run/5 contains signal_intent but no predictor registry. Do not expose
            // inferred signal/market data; a later registry-aware projection may add declared signals.
        }
        return output;
    }

    private static void validatePredictors(JsonNode run) {
        Set<String> declared = declaredPredictors(run);
        JsonNode undeclared = run.get("undeclared_predictors");
        if (undeclared != null && undeclared.isArray() && !undeclared.isEmpty()) {
            throw new IllegalArgumentException("undeclared predictors are not allowed in presentation export");
        }
        if (declared.isEmpty()) return;
        JsonNode candidates = run.get("candidate_metrics");
        if (candidates != null && candidates.isArray()) for (JsonNode candidate : candidates) {
            validateSignalPredictors(candidate.get("signal_intent"), declared);
        }
    }

    private static void validateSignalPredictors(JsonNode signals, Set<String> declared) {
        if (signals != null && signals.isArray()) for (JsonNode signal : signals) {
            String predictor = first(signal, "predictor", "predictor_id");
            if (!predictor.isBlank() && !declared.contains(predictor)) {
                throw new IllegalArgumentException("signal references undeclared predictor: " + predictor);
            }
        }
    }

    private static Set<String> declaredPredictors(JsonNode value) {
        Set<String> declared = new HashSet<>();
        JsonNode values = value == null ? null : value.get("declared_predictors");
        if (values == null && value != null) values = value.get("predictors");
        if (values == null && value != null) values = value.path("predictor_registry").get("predictors");
        if (values != null && values.isArray()) {
            for (JsonNode predictor : values) {
                String id = predictor.isTextual() ? predictor.asText() : first(predictor, "id", "name", "predictor");
                if (!id.isBlank()) declared.add(id);
            }
        }
        return Set.copyOf(declared);
    }

    private static void validateIdentity(JsonNode indexEntry, JsonNode source, Path path) {
        String indexedFamily = first(indexEntry, "strategy_family_id");
        String sourceFamily = first(source, "family_id", "hypothesis_family", "strategy_family", "strategy_family_id");
        if (!indexedFamily.isBlank() && !sourceFamily.isBlank() && !indexedFamily.equals(sourceFamily)) {
            throw new IllegalArgumentException("v5 family identity mismatch at " + path);
        }
        String indexedVersion = first(indexEntry, "strategy_version");
        String sourceVersion = first(source, "strategy_version", "version");
        if (!indexedVersion.isBlank() && !sourceVersion.isBlank() && !indexedVersion.equals(sourceVersion)) {
            throw new IllegalArgumentException("v5 strategy version mismatch at " + path);
        }
    }

    private static Path pathFor(Path root, JsonNode entry) {
        String raw = entry == null ? "" : first(entry, "path", "file");
        if (raw.isBlank()) return root.resolve("index.json");
        Path path = root.resolve(raw).normalize();
        path = safeFile(root, path);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("v5 record path is missing or outside root: " + raw);
        }
        return path;
    }

    private static JsonNode read(Path path) {
        try { return JsonHashes.mapper().readTree(Files.readString(path, StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalArgumentException("cannot read v5 record: " + path, error); }
    }

    private static byte[] readBytes(Path path) {
        try { return Files.readAllBytes(path); }
        catch (IOException error) { throw new IllegalArgumentException("cannot read v5 record bytes: " + path, error); }
    }

    private static void validateIndexedRecord(JsonNode value, Path path) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException("v5 record is not an object: " + path);
        String schema = value.path("schema").asText();
        if (!SCHEMAS.hasContractSchema(schema)) throw new IllegalArgumentException("unknown v5 record schema at " + path + ": " + schema);
        SCHEMAS.validateKnownContractSchema(value);
        String actual = value.path("content_sha256").asText();
        if (!SHA256.matcher(actual).matches()) throw new IllegalArgumentException("missing content hash at " + path);
        if (!actual.equals(StrategyResearchV5.ownHash(value))) throw new IllegalArgumentException("content hash mismatch at " + path);
    }

    private static Path realPath(Path value) {
        try { return value.toRealPath(); }
        catch (IOException error) { throw new IllegalArgumentException("cannot resolve v5 presentation root: " + value, error); }
    }

    private static Path safeFile(Path root, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IllegalArgumentException("v5 record path is outside root: " + candidate);
        try {
            Path physical = normalized.toRealPath();
            if (!physical.startsWith(root)) throw new IllegalArgumentException("v5 record path is outside root: " + candidate);
            return physical;
        } catch (IOException error) {
            return normalized;
        }
    }

    private static Instant artifactTimestamp(JsonNode value) {
        for (String field : List.of("generated_at", "captured_at", "created_at", "cutoff")) {
            String raw = first(value, field);
            if (!raw.isBlank()) {
                try { return Instant.parse(raw); }
                catch (RuntimeException ignored) { }
            }
        }
        return null;
    }

    private static boolean isNewer(Instant candidateAt, String candidateHash, Instant currentAt, String currentHash) {
        if (currentAt == null) return candidateAt != null || candidateHash.compareTo(currentHash) > 0;
        if (candidateAt == null) return false;
        int time = candidateAt.compareTo(currentAt);
        return time > 0 || time == 0 && candidateHash.compareTo(currentHash) > 0;
    }

    private static void applyReadiness(ObjectNode readiness, JsonNode audit, String auditHash) {
        JsonNode dimensions = audit.get("dimensions");
        readiness.set("dimensions", dimensions != null && dimensions.isArray() ? dimensions.deepCopy() : JSON.arrayNode());
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        if (dimensions != null && dimensions.isArray()) for (JsonNode dimension : dimensions) {
            JsonNode values = dimension.get("blockers");
            if (values != null && values.isArray()) values.forEach(value -> blockers.add(value.asText()));
        }
        JsonNode testing = audit.get("strategy_testing_readiness");
        if (testing != null && testing.path("blockers").isArray()) testing.path("blockers").forEach(value -> blockers.add(value.asText()));
        JsonNode activation = audit.get("activation");
        if (activation != null && activation.path("status").asText().equals("BLOCKED")) blockers.add("activation: BLOCKED");
        boolean blocked = "BLOCKED".equals(testing == null ? "" : testing.path("status").asText())
                || "BLOCKED".equals(activation == null ? "" : activation.path("status").asText())
                || dimensions != null && dimensions.toString().contains("\"status\":\"BLOCKED\"");
        readiness.put("status", blocked ? "BLOCKED" : "READY");
        ArrayNode blockerArray = readiness.putArray("blockers");
        blockers.forEach(blockerArray::add);
        ObjectNode source = readiness.putObject("source").put("schema", READINESS_SCHEMA).put("content_sha256", auditHash);
        String generated = first(audit, "generated_at");
        if (!generated.isBlank()) source.put("generated_at", generated);
        if (testing != null) readiness.set("strategy_testing_readiness", testing.deepCopy());
        if (activation != null) readiness.set("activation", activation.deepCopy());
        if (audit.has("artifact_verification")) readiness.set("artifact_verification", audit.get("artifact_verification").deepCopy());
        if (audit.has("limitations")) readiness.set("limitations", audit.get("limitations").deepCopy());
    }

    private static ObjectNode dimension(String id, String status, String detail) {
        return JSON.objectNode().put("id", id).put("status", status).put("detail", detail);
    }

    private static ArrayNode array(JsonNode value, String field) {
        return value != null && value.path(field).isArray() ? (ArrayNode) value.path(field) : JSON.arrayNode();
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) if (from.has(field)) to.set(field, from.get(field).deepCopy());
    }

    private static void copyObject(JsonNode from, ObjectNode to, String sourceField, String targetField) {
        JsonNode value = from.get(sourceField);
        if (value != null && value.isObject()) to.set(targetField, value.deepCopy());
    }

    private static void copyArray(JsonNode from, ObjectNode to, String sourceField, String targetField) {
        JsonNode value = from.get(sourceField);
        if (value != null && value.isArray()) to.set(targetField, value.deepCopy());
    }

    private static String first(JsonNode value, String... fields) {
        if (value == null) return "";
        for (String field : fields) if (value.has(field) && value.get(field).isValueNode() && !value.get(field).isNull()) return value.get(field).asText();
        return "";
    }

    private static ObjectNode withRootHash(ObjectNode value) {
        value.put("content_sha256", StrategyResearchV5.ownHash(value));
        return value;
    }
}
