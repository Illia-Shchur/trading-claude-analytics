package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.LifecycleTrustService;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import com.tradinganalytics.infrastructure.security.SafeTreeVerifier;
import com.tradinganalytics.infrastructure.security.WorkflowSecurityV5;
import com.tradinganalytics.marketdata.research.ResearchData;
import com.tradinganalytics.research.legacy.LegacyResearchV2;
import com.tradinganalytics.research.legacy.LegacyResearchV3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Native owner of {@code tools/strategy-research-v5-authoritative.mjs}.
 *
 * <p>The Node module is an orchestration and custody boundary.  This port keeps
 * that boundary JSON-shaped so hashes and nulls remain differentially
 * testable, delegates domain work to the existing v5 Java owners, and refuses
 * to manufacture evidence when a physical producer is unavailable.</p>
 */
public final class StrategyResearchAuthoritativeV5 {
    public static final String AUTHORITATIVE_SCHEMA =
            "strategy-v5-authoritative-command-receipt/1";
    public static final List<String> PIPELINE_V5 = List.of(
            "features", "signal_intent", "labels", "execution_fills", "trades",
            "metrics", "stresses", "portfolio", "wfo");

    private static final ObjectMapper JSON = JsonHashes.mapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ResearchSchemaRegistry SCHEMAS = ResearchSchemaRegistry.defaultRegistry();
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern FAMILY = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");
    private static final Pattern SNAPSHOT = Pattern.compile("^registry-[a-f0-9]{64}\\.json$");
    private static final java.time.format.DateTimeFormatter ISO_MILLIS =
            new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> RECEIPT_STATUSES =
            Set.of("PLANNED", "COMPLETE", "BLOCKED", "REJECTED");
    private static final Set<String> PRODUCTION_INSTRUMENTS = Set.of(
            "BINANCE_SPOT", "BINANCE_USDM_PERPETUAL");
    private static final Map<String, String> PRODUCTION_INSTRUMENT_ALIASES = Map.of(
            "spot", "BINANCE_SPOT",
            "binance_spot", "BINANCE_SPOT",
            "perpetual", "BINANCE_USDM_PERPETUAL",
            "perp", "BINANCE_USDM_PERPETUAL",
            "usdm_perpetual", "BINANCE_USDM_PERPETUAL",
            "binance_usdm_perpetual", "BINANCE_USDM_PERPETUAL");
    private static final Set<String> LOOSE_KEYS = Set.of(
            "returns", "episode_returns", "fitness", "trades", "fills", "metrics", "stress",
            "stresses", "portfolio", "wfo", "genetic", "ga", "evaluation", "evaluations",
            "vector", "vectors", "candidate_returns", "execution_results", "execution_result",
            "selected_fills", "selected_trades", "risk", "pnl", "net_pnl", "gross_pnl", "pass",
            "active", "candidate_pass", "asset_decision", "portfolio_decision", "selection",
            "selected", "constraints", "acceptance", "thresholds", "config");
    private static final Set<String> INDEX_PHASES =
            Set.of("DEVELOPMENT", "INNER", "OOS", "EXPOSED", "SEALED", "PROSPECTIVE");
    private static final Set<String> EXPERIMENT_LINEAGE_OPTION_KEYS = Set.of(
            "precommit_sha256", "definition_sha256", "candidate_set_sha256", "data_manifest_sha256",
            "feature_set_sha256", "label_set_sha256", "executor_sha256", "acceptance_contract_sha256",
            "training_selection_policy_sha256", "container_sha256", "parent_evidence_sha256",
            "predecessor_sha256", "required_assets", "required_instruments", "asset_scope", "instrument_scope");
    private static final Map<String, String> METADATA_KINDS = Map.ofEntries(
            Map.entry("contract_spec", "CONTRACT_SPEC"),
            Map.entry("fee_schedule", "FEE_SCHEDULE"),
            Map.entry("execution_model", "EXECUTION_MODEL"),
            Map.entry("funding_identity", "FUNDING_IDENTITY"),
            Map.entry("expiry", "EXPIRY"),
            Map.entry("settlement", "SETTLEMENT"),
            Map.entry("margin", "MARGIN"),
            Map.entry("liquidation", "LIQUIDATION"));
    private static final ThreadLocal<Consumer<String>> WRITE_FAULT_HOOK = new ThreadLocal<>();

    private StrategyResearchAuthoritativeV5() {}

    /* ------------------------------------------------------------------ */
    /* Canonical helpers and frozen binding exports                        */
    /* ------------------------------------------------------------------ */

    public static String stable(JsonNode value) {
        return CanonicalJson.canonicalize(value == null ? NullNode.instance : value);
    }

    public static String hash(JsonNode value) {
        return JsonHashes.canonicalSha256(value == null ? NullNode.instance : value);
    }

    public static String hash(String value) { return JsonHashes.sha256(value); }
    public static String hash(byte[] value) { return JsonHashes.sha256(value); }

    public static String ownHash(JsonNode value) { return ownHash(value, "content_sha256"); }

    public static String ownHash(JsonNode value, String field) {
        if (value == null) return hash(NullNode.instance);
        JsonNode copy = value.deepCopy();
        if (copy instanceof ObjectNode object) object.remove(field);
        return hash(copy);
    }

    private static ObjectNode withHash(ObjectNode value) { return withHash(value, "content_sha256"); }

    private static ObjectNode withHash(ObjectNode value, String field) {
        ObjectNode copy = requireObject(value, "hashable value").deepCopy();
        copy.remove(field);
        copy.put(field, ownHash(copy, field));
        return copy;
    }

    public static String canonicalHypothesisFamilyV5(ObjectNode precommit) {
        return canonicalHypothesisFamilyV5(precommit, object());
    }

    public static String canonicalHypothesisFamilyV5(ObjectNode precommit, ObjectNode options) {
        String declared = (defined(precommit == null ? null : precommit.get("hypothesis_family"))
                ? text(precommit, "hypothesis_family") : text(precommit, "precommit_id")).trim();
        if (declared.isEmpty() || !FAMILY.matcher(declared).matches()) {
            throw failure("production v5 hypothesis family must be the precommit hypothesis_family/precommit_id in canonical lowercase form");
        }
        JsonNode definition = field(options, "definition");
        if (defined(definition) && !declared.equals(text(definition, "hypothesis_family").trim())) {
            throw failure("strategy definition hypothesis_family differs from the canonical precommit family");
        }
        JsonNode evaluator = first(options, "evaluatorSpec", "evaluator_spec");
        if (defined(evaluator) && !declared.equals(text(evaluator, "strategy_family").trim())) {
            throw failure("evaluator strategy_family differs from the canonical precommit family");
        }
        return declared;
    }

    public static boolean validateExactProductionEpisodeInventoriesV5(ObjectNode options) {
        JsonNode envelope = field(options, "envelope");
        JsonNode artifact = field(options, "artifact");
        List<String> expected = exactUniqueStrings(mapText(envelope.path("windows"), "episode_id"),
                "v2 opportunity envelope episode inventory");
        List<String> actual = exactUniqueStrings(mapText(artifact.path("episodes"), "episode_id"),
                "statistical artifact episode inventory");
        assertExactSet(actual, expected, "statistical and v2 opportunity episode inventories");
        JsonNode roleRows = first(options, "roleRows", "role_rows");
        for (String role : List.of("feature", "label", "execution")) {
            if (!roleRows.isObject() || !roleRows.has(role)) continue;
            List<String> ids = exactUniqueStrings(mapText(roleRows.path(role), "episode_id"),
                    "physical " + role + " episode inventory");
            assertExactSet(ids, expected,
                    "physical " + role + " and v2 opportunity episode inventories");
        }
        return true;
    }

    public static ObjectNode makeAuthoritativeExecutorIdentityV5(ObjectNode options) {
        JsonNode evaluator = first(options, "evaluatorSpec", "evaluator_spec");
        JsonNode manifest = field(options, "manifest");
        if (!evaluator.isObject() || !manifest.isObject()) {
            throw failure("authoritative executor identity requires the evaluator spec and Parquet manifest");
        }
        ObjectNode value = object();
        value.put("schema", "strategy-v5-authoritative-executor-identity/1").put("version", 1);
        value.put("evaluator_spec_sha256", requireSha(text(evaluator, "content_sha256"),
                "executor evaluator_spec_sha256"));
        value.put("evaluator_code_sha256", requireSha(text(evaluator, "code_sha256"),
                "executor evaluator_code_sha256"));
        value.put("evaluator_worker_code_sha256", requireSha(text(evaluator, "worker_code_sha256"),
                "executor evaluator_worker_code_sha256"));
        value.put("transformation_code_sha256", requireSha(text(manifest, "transformation_code_sha256"),
                "executor transformation_code_sha256"));
        value.put("label_code_sha256", requireSha(text(manifest, "label_code_sha256"),
                "executor label_code_sha256"));
        value.put("execution_code_sha256", requireSha(text(manifest, "execution_code_sha256"),
                "executor execution_code_sha256"));
        value.put("config_sha256", requireSha(text(manifest, "config_sha256"),
                "executor config_sha256"));
        value.put("metadata_bundle_sha256", requireSha(
                firstText(options, "metadataBundleSha256", "metadata_bundle_sha256"),
                "executor metadata_bundle_sha256"));
        return withHash(value);
    }

    public static ObjectNode validateProductionResearchBindingsV5(ObjectNode options) {
        ObjectNode precommit = requiredObject(options, "precommit");
        ObjectNode definition = requiredObject(options, "definition");
        ObjectNode experiment = requiredObject(options, "experiment");
        ObjectNode evaluator = requiredObject(options, "evaluatorSpec", "evaluator_spec");
        ObjectNode manifest = requiredObject(options, "manifest");
        ObjectNode envelope = requiredObject(options, "envelope");
        ObjectNode artifact = requiredObject(options, "artifact");
        if (!"strategy-experiment/3".equals(text(experiment, "schema"))) {
            throw failure("production research requires strategy-experiment/3");
        }
        LegacyResearchV3.validateExperimentV3(experiment, experiment.path("acceptance_contract"), null);
        LegacyResearchV2.validateDefinitionV2(definition, precommit);
        ObjectNode familyOptions = object();
        familyOptions.set("definition", definition); familyOptions.set("evaluatorSpec", evaluator);
        String family = canonicalHypothesisFamilyV5(precommit, familyOptions);

        ObjectNode scopeOptions = object(); scopeOptions.set("candidateTemplate", evaluator.path("candidate_template"));
        ObjectNode scope = StrategyResearchDataV5.derivePrecommitTradeScopeV5(precommit, scopeOptions);
        if (!stable(definition.path("tradable_instrument_contract"))
                .equals(stable(precommit.path("tradable_instrument_contract")))) {
            throw failure("physical definition trade contract differs from the frozen precommit");
        }
        OpportunityV5.validateOpportunityEnvelopeV5(envelope);
        if (!"strategy-v5-opportunity-envelope/2".equals(text(envelope, "schema"))
                || envelope.path("fixture_only").asBoolean(true)
                || !"AUTHORITATIVE".equals(text(envelope, "provenance"))) {
            throw failure("production research requires an authoritative non-fixture opportunity-envelope/2");
        }

        List<String> scopeAssets = sortedTexts(scope.path("trade_assets"), true);
        String scopeInstrument = text(scope, "instrument");
        List<String> windowAssets = uniqueSortedMapped(envelope.path("windows"), row -> lower(text(row, "asset")));
        List<String> windowInstruments = uniqueSortedMapped(envelope.path("windows"), row ->
                normalizedProductionInstrument(text(row, "instrument"), "opportunity window instrument"));
        if (windowAssets.isEmpty()) throw failure("opportunity window assets are empty");
        if (windowInstruments.size() != 1) {
            throw failure("production opportunity envelope must freeze exactly one instrument type");
        }
        assertExactSet(windowAssets, scopeAssets, "opportunity window assets");
        assertExactSet(windowInstruments, List.of(scopeInstrument), "opportunity window instruments");
        List<String> declaredAssets = exactUniqueStrings(
                map(envelope.path("assets"), row -> lower(row.asText())),
                "opportunity envelope assets");
        List<String> declaredInstruments = exactUniqueStrings(
                map(envelope.path("instruments"), row -> normalizedProductionInstrument(row.asText(),
                        "opportunity envelope instrument")), "opportunity envelope instruments");
        assertExactSet(declaredAssets, scopeAssets, "opportunity envelope declared assets");
        assertExactSet(declaredInstruments, List.of(scopeInstrument),
                "opportunity envelope declared instruments");

        Map<String, JsonNode> windows = new HashMap<>();
        for (JsonNode window : rows(envelope.path("windows"))) {
            String id = text(window, "episode_id");
            if (id.isEmpty() || windows.putIfAbsent(id, window) != null) {
                throw failure("production opportunity envelope contains a missing or duplicate episode identity");
            }
            String asset = lower(text(window, "asset"));
            if (!(asset.toUpperCase(Locale.ROOT) + "USDT").equals(text(window, "symbol").toUpperCase(Locale.ROOT))) {
                throw failure("opportunity episode " + id + " does not use the canonical same-asset USDT symbol");
            }
        }
        Set<String> episodes = new HashSet<>();
        for (JsonNode episode : rows(artifact.path("episodes"))) {
            String id = text(episode, "episode_id");
            if (id.isEmpty() || !episodes.add(id)) {
                throw failure("statistical artifact contains a missing or duplicate episode identity");
            }
            JsonNode window = windows.get(id);
            if (window == null) throw failure("statistical artifact episode " + id + " is outside the frozen opportunity envelope");
            if (!lower(text(episode, "asset")).equals(lower(text(window, "asset")))
                    || timestamp(episode.get("decision_time"), "statistical episode decision_time")
                    != timestamp(window.get("decision_time"), "opportunity decision_time")) {
                throw failure("statistical episode " + id + " identity differs from its frozen opportunity window");
            }
        }
        ObjectNode inventories = object(); inventories.set("envelope", envelope); inventories.set("artifact", artifact);
        validateExactProductionEpisodeInventoriesV5(inventories);

        String candidateSha = requireSha(text(envelope, "candidate_set_sha256"),
                "opportunity candidate_set_sha256");
        if (!text(artifact.path("lineage"), "dataset_sha256").equals(text(manifest, "dataset_root_sha256"))) {
            throw failure("statistical artifact dataset lineage differs from the frozen Parquet manifest");
        }
        if (!text(artifact.path("lineage"), "candidate_set_sha256").equals(candidateSha)) {
            throw failure("statistical artifact candidate-set lineage differs from the frozen opportunity envelope");
        }
        for (Map.Entry<String, String> binding : Map.of(
                "feature_set_sha256", "feature", "label_set_sha256", "label",
                "execution_set_sha256", "execution").entrySet()) {
            String roleSha = requireSha(text(manifest.path("artifacts").path(binding.getValue()), "sha256"),
                    "Parquet " + binding.getValue() + " artifact SHA-256");
            if (!text(artifact.path("lineage"), binding.getKey()).equals(roleSha)) {
                throw failure("statistical artifact " + binding.getKey() + " differs from the frozen Parquet role");
            }
        }

        requireEqual(experiment, "precommit_sha256", text(precommit, "content_sha256"),
                "experiment precommit lineage differs from the physical precommit");
        requireEqual(experiment, "definition_sha256", text(definition, "content_sha256"),
                "experiment definition lineage differs from the physical definition");
        requireEqual(experiment, "candidate_set_sha256", candidateSha,
                "experiment candidate-set lineage differs from the frozen opportunity envelope");
        requireEqual(experiment, "data_manifest_sha256", text(manifest, "content_sha256"),
                "experiment data-manifest lineage differs from the physical Parquet manifest");
        requireEqual(experiment, "feature_set_sha256", text(manifest.path("artifacts").path("feature"), "sha256"),
                "experiment feature-set lineage differs from the physical feature role");
        requireEqual(experiment, "label_set_sha256", text(manifest.path("artifacts").path("label"), "sha256"),
                "experiment label-set lineage differs from the physical label role");
        JsonNode acceptance = experiment.path("acceptance_contract");
        if (!acceptance.isObject()
                || !text(experiment, "acceptance_contract_sha256").equals(text(acceptance, "content_sha256"))
                || !text(acceptance, "content_sha256").equals(ownHash(acceptance))) {
            throw failure("experiment acceptance contract lineage is missing or tampered");
        }
        for (String key : List.of("maximum_drawdown_r", "maximum_cost_r")) {
            double value = number(acceptance.path("gates").get(key));
            if (!Double.isFinite(value) || value < 0) {
                throw failure("production v5 acceptance must freeze a non-negative " + key
                        + "; portfolio percentages cannot be converted to R");
            }
        }
        ObjectNode executorOptions = object(); executorOptions.set("evaluatorSpec", evaluator);
        executorOptions.set("manifest", manifest); executorOptions.set("metadataBundleSha256",
                first(options, "metadataBundleSha256", "metadata_bundle_sha256"));
        ObjectNode executor = makeAuthoritativeExecutorIdentityV5(executorOptions);
        if (!text(experiment, "executor_sha256").equals(text(executor, "content_sha256"))) {
            throw failure("experiment executor lineage differs from the deterministic authoritative executor identity");
        }
        List<String> required = new ArrayList<>();
        int index = 0;
        for (JsonNode row : rows(experiment.path("required_assets"))) {
            if (!row.isObject() || !"crypto".equals(text(row, "asset_class"))) {
                throw failure("experiment required_assets[" + index + "] must be a crypto instrument object");
            }
            String asset = lower(text(row, "asset"));
            String instrument = normalizedProductionInstrument(text(row, "instrument"),
                    "experiment required_assets[" + index + "].instrument");
            required.add(asset + "|" + instrument); index++;
        }
        required = exactUniqueStrings(required, "experiment required_assets");
        List<String> expectedRequired = scopeAssets.stream().map(asset -> asset + "|" + scopeInstrument).sorted().toList();
        assertExactSet(required, expectedRequired, "experiment required_assets");
        ObjectNode result = object(); result.set("scope", scope); result.set("executorIdentity", executor);
        result.put("hypothesisFamily", family); return result;
    }

    private record PhysicalMetadataBundle(
            ObjectNode value, Path path, String byteSha256, long bytes, String contentSha256) {}

    /** Reopens the keyed execution-metadata bundle and every public source byte it claims. */
    private static PhysicalMetadataBundle physicalMetadataBundle(Path rawPath, Path explicitSourceRoot) {
        Path path = absolute(rawPath);
        byte[] bytes = readSinglyLinked(path, "metadata receipt bundle");
        JsonNode parsed = parse(bytes, "metadata receipt bundle");
        if (!(parsed instanceof ObjectNode bundle)
                || StrategyResearchDataV5.DATA_V5.get("metadata").equals(text(bundle, "schema"))) {
            throw failure("metadata receipt bundle must be a keyed physical bundle, not a generic receipt or array");
        }
        Set<String> keys = new LinkedHashSet<>();
        bundle.fieldNames().forEachRemaining(keys::add);
        if (!METADATA_KINDS.keySet().containsAll(keys)
                || !keys.containsAll(List.of("contract_spec", "fee_schedule", "execution_model"))) {
            throw failure("metadata receipt bundle has unknown keys or lacks contract_spec, fee_schedule, and execution_model");
        }
        for (String key : keys) {
            JsonNode receipt = bundle.get(key);
            if (!receipt.isObject()
                    || !StrategyResearchDataV5.DATA_V5.get("metadata").equals(text(receipt, "schema"))
                    || !METADATA_KINDS.get(key).equals(text(receipt, "kind"))) {
                throw failure("metadata receipt bundle contains a receipt under the wrong kind key");
            }
        }
        JsonNode expiry = bundle.get("expiry"), settlement = bundle.get("settlement");
        if (defined(expiry) && defined(settlement)) {
            if (text(expiry, "content_sha256").equals(text(settlement, "content_sha256"))) {
                throw failure("expiry and settlement metadata must be separate physical receipts");
            }
            Set<String> expiryPaths = sourceReceiptPaths(expiry);
            if (sourceReceiptPaths(settlement).stream().anyMatch(expiryPaths::contains)) {
                throw failure("expiry and settlement metadata may not reuse the same physical source receipt");
            }
            Set<String> expiryBytes = sourceReceiptByteHashes(expiry);
            if (sourceReceiptByteHashes(settlement).stream().anyMatch(expiryBytes::contains)) {
                throw failure("expiry and settlement metadata may not reuse the same underlying source bytes");
            }
        }

        for (String key : keys) {
            ObjectNode receipt = (ObjectNode) bundle.get(key);
            if (!text(receipt, "content_sha256").equals(ownHash(receipt))) {
                throw failure("metadata receipt bundle contains a tampered receipt");
            }
            SCHEMAS.validateKnownContractSchema(receipt);
            String status = text(receipt, "status");
            if (!"UNAVAILABLE".equals(status) && !receipt.path("authoritative").asBoolean(false)) {
                throw failure(text(receipt, "kind") + " metadata is not authoritative");
            }
            if ("CONSERVATIVE_MODEL".equals(status)
                    && (!HASH.matcher(text(receipt, "model_sha256")).matches()
                    || !HASH.matcher(text(receipt, "precommit_sha256")).matches())) {
                throw failure(text(receipt, "kind") + " conservative metadata lacks model/precommit lineage");
            }
            if (Set.of("PUBLIC_OBSERVED", "USER_BOUND").contains(status)) {
                verifyMetadataSourceCustody(receipt, explicitSourceRoot);
            }
        }
        return new PhysicalMetadataBundle(bundle, path, hash(bytes), bytes.length, hash(bundle));
    }

    private static Set<String> sourceReceiptPaths(JsonNode receipt) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode row : rows(receipt.path("source_receipts"))) result.add(text(row, "path"));
        return result;
    }

    private static Set<String> sourceReceiptByteHashes(JsonNode receipt) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode row : rows(receipt.path("source_receipts"))) result.addAll(hashInventory(row.get("byte_sha256")));
        return result;
    }

    private static List<String> hashInventory(JsonNode value) {
        List<String> result = new ArrayList<>();
        if (value != null && value.isArray()) value.forEach(row -> {
            if (HASH.matcher(row.asText()).matches()) result.add(row.asText());
        });
        else if (defined(value) && HASH.matcher(value.asText()).matches()) result.add(value.asText());
        return result;
    }

    private static void verifyMetadataSourceCustody(ObjectNode receipt, Path explicitSourceRoot) {
        String kind = text(receipt, "kind");
        String declared = text(receipt, "source_root_reference");
        if (declared.isEmpty() || !receipt.path("source_receipts").isArray()
                || receipt.path("source_receipts").isEmpty()) {
            throw failure(kind + " metadata lacks physical source receipt custody");
        }
        if (Path.of(declared).isAbsolute() || declared.indexOf('\\') >= 0) {
            throw failure(kind + " metadata source root reference is not portable");
        }
        Path declaredRoot = absolute(Path.of(declared));
        Path root = explicitSourceRoot == null ? declaredRoot : absolute(explicitSourceRoot);
        if (!declaredRoot.equals(root)) throw failure(kind + " metadata source root does not match the bound physical root");
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) throw failure(kind + " metadata source root is missing");
        PathConfinement.requireRealDirectory(root, kind + " metadata source root");
        Set<String> verifiedSourceBytes = new LinkedHashSet<>();
        for (JsonNode source : receipt.path("source_receipts")) {
            String sourcePathText = text(source, "path");
            String sourceContent = firstText(source, "content_sha256", "sha256");
            List<String> byteHashes = hashInventory(source.get("byte_sha256"));
            if (sourcePathText.isEmpty() || !HASH.matcher(sourceContent).matches() || byteHashes.isEmpty()) {
                throw failure(kind + " metadata source receipt is incomplete");
            }
            Path sourcePath = LifecycleTrustService.resolveLifecyclePhysicalPathV5(
                    root, sourcePathText, kind + " metadata source receipt");
            byte[] sourceBytes = readSinglyLinked(sourcePath, kind + " metadata source receipt");
            JsonNode normalized = null;
            try { normalized = JSON.readTree(sourceBytes); } catch (IOException ignored) { }
            if (normalized != null && "strategy-v5-source-receipt/1".equals(text(normalized, "schema"))) {
                if (!text(normalized, "content_sha256").equals(ownHash(normalized))
                        || !text(normalized, "content_sha256").equals(sourceContent)) {
                    throw failure(kind + " normalized source receipt content is tampered");
                }
                List<JsonNode> rawReceipts = rows(normalized.path("raw_receipts"));
                List<String> rawHashes = hashInventory(normalized.get("source_byte_sha256"));
                if (rawReceipts.isEmpty() || rawHashes.isEmpty() || !rawHashes.containsAll(byteHashes)) {
                    throw failure(kind + " normalized source receipt raw lineage is incomplete");
                }
                for (JsonNode raw : rawReceipts) {
                    String rawPathText = text(raw, "path");
                    String rawByte = text(raw, "byte_sha256");
                    String rawContent = text(raw, "content_sha256");
                    if (rawPathText.isEmpty() || !HASH.matcher(rawByte).matches()
                            || !HASH.matcher(rawContent).matches() || !rawContent.equals(ownHash(raw))) {
                        throw failure(kind + " raw source receipt is incomplete or tampered");
                    }
                    Path rawPath = LifecycleTrustService.resolveLifecyclePhysicalPathV5(
                            root, rawPathText, kind + " raw source receipt");
                    if (!hash(readSinglyLinked(rawPath, kind + " raw source receipt")).equals(rawByte)) {
                        throw failure(kind + " raw source receipt bytes are tampered");
                    }
                    verifiedSourceBytes.add(rawByte);
                }
            } else {
                String direct = hash(sourceBytes);
                if (!byteHashes.contains(direct)) throw failure(kind + " metadata source receipt bytes are tampered");
                verifiedSourceBytes.add(direct);
            }
        }
        if ("SETTLEMENT".equals(kind)) verifySettlementMetadata(receipt, verifiedSourceBytes);
    }

    private static void verifySettlementMetadata(ObjectNode receipt, Set<String> verifiedSourceBytes) {
        long captured = timestamp(receipt.get("captured_at"), "SETTLEMENT captured_at");
        for (JsonNode row : rows(receipt.path("records"))) {
            long expiry = timestampOrMin(first(row, "expiry", "delivery_date"));
            long event = timestampOrMin(row.get("event_time"));
            long settlement = timestampOrMin(row.get("settlement_time"));
            long available = timestampOrMin(row.get("availability_time"));
            String source = text(row, "settlement_mark_source_sha256");
            if (!"BINANCE".equals(text(row, "venue").toUpperCase(Locale.ROOT))
                    || !"BINANCE_USDM_DATED_FUTURE".equals(text(row, "instrument").toUpperCase(Locale.ROOT))
                    || text(row, "symbol").isEmpty()) {
                throw failure("SETTLEMENT metadata lacks exact dated-futures identity");
            }
            if (expiry == Long.MIN_VALUE || event == Long.MIN_VALUE || settlement == Long.MIN_VALUE
                    || available == Long.MIN_VALUE || event != settlement || event < expiry
                    || available < event || available > captured) {
                throw failure("SETTLEMENT metadata chronology is invalid");
            }
            if (!(number(row.get("settlement_price")) > 0) || text(row, "settlement_mark_event_id").isEmpty()
                    || !HASH.matcher(source).matches() || !verifiedSourceBytes.contains(source)
                    || !source.equals(text(row, "source_byte_sha256"))
                    || !text(row, "source_receipt_sha256").equals(text(receipt, "source_receipt_sha256"))) {
                throw failure("SETTLEMENT metadata source/mark identity is not physically bound");
            }
        }
    }

    private static boolean validateMetadataLineage(
            ObjectNode metadata, ObjectNode evaluatorSpec, ObjectNode plan, boolean requireEvaluator) {
        if (metadata == null || evaluatorSpec == null || text(evaluatorSpec, "precommit_sha256").isEmpty()) {
            throw failure("metadata/evaluator lineage requires a frozen evaluator precommit");
        }
        Iterator<JsonNode> receipts = metadata.elements();
        while (receipts.hasNext()) {
            JsonNode receipt = receipts.next();
            if (!"UNAVAILABLE".equals(text(receipt, "status"))
                    && !text(receipt, "precommit_sha256").equals(text(evaluatorSpec, "precommit_sha256"))) {
                throw failure(text(receipt, "kind") + " metadata is bound to a different evaluator precommit");
            }
            if (!"UNAVAILABLE".equals(text(receipt, "status")) && plan != null
                    && !text(receipt, "plan_sha256").equals(text(plan, "content_sha256"))) {
                throw failure(text(receipt, "kind") + " metadata is bound to a different data plan");
            }
            if (!"UNAVAILABLE".equals(text(receipt, "status")) && requireEvaluator
                    && !text(receipt, "evaluator_spec_sha256").equals(text(evaluatorSpec, "content_sha256"))) {
                throw failure(text(receipt, "kind") + " metadata is bound to a different evaluator spec");
            }
        }
        return true;
    }

    public static boolean validateAuthoritativePortfolioPolicy(JsonNode value) {
        if (value == null || !"strategy-portfolio-policy/2".equals(text(value, "schema"))) {
            throw failure("portfolio policy must be strategy-portfolio-policy/2");
        }
        SCHEMAS.validateKnownContractSchema(value);
        if (!"FROZEN".equals(text(value, "status"))) throw failure("portfolio policy must have status FROZEN");
        double equity = number(value.get("current_equity"));
        if (!(equity > 0)) throw failure("portfolio policy lacks a positive frozen current_equity");
        long asOf = timestamp(value.get("asOf"), "portfolio policy asOf");
        long cutoff = timestamp(value.get("consuming_cutoff"), "portfolio policy consuming cutoff");
        if (asOf > cutoff) throw failure("portfolio policy asOf is after its consuming cutoff");
        JsonNode limits = value.path("limits");
        if (number(limits.get("ruin_equity_floor")) > number(limits.get("equity_floor"))
                || number(limits.get("equity_floor")) > equity) {
            throw failure("portfolio policy equity floors are not ordered below current_equity");
        }
        if (defined(limits.get("minimum_current_equity"))
                && number(limits.get("minimum_current_equity")) > equity) {
            throw failure("portfolio policy minimum_current_equity exceeds current_equity");
        }
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Receipt and physical-custody exports                                */
    /* ------------------------------------------------------------------ */

    public static ObjectNode makeCommandReceipt(ObjectNode options) {
        String status = text(options, "status");
        if (!RECEIPT_STATUSES.contains(status)) {
            throw failure("invalid authoritative command status " + status);
        }
        JsonNode detailsNode = field(options, "details");
        ObjectNode details = detailsNode.isObject() ? ((ObjectNode) detailsNode).deepCopy() : object();
        if (details.path("active").asBoolean(false) || containsActiveString(details)) {
            throw failure("authoritative command receipts may never claim ACTIVE");
        }
        details.put("active", false);
        ObjectNode value = object().put("schema", AUTHORITATIVE_SCHEMA).put("version", 1)
                .put("command", text(options, "command")).put("status", status);
        value.set("inputs", commandArray(options, "inputs"));
        value.set("outputs", commandArray(options, "outputs"));
        JsonNode limitationNode = field(options, "limitations");
        if (defined(limitationNode) && !limitationNode.isArray()) {
            throw failure("authoritative command limitations must be an array");
        }
        List<String> limitations = rows(limitationNode).stream().map(StrategyResearchAuthoritativeV5::jsString)
                .distinct().sorted().toList();
        value.set("limitations", strings(limitations)); value.set("details", details);
        ObjectNode result = withHash(value);
        SCHEMAS.validateKnownContractSchema(result);
        return result;
    }

    public static boolean validateCommandReceipt(JsonNode value) {
        if (value == null || !AUTHORITATIVE_SCHEMA.equals(text(value, "schema"))
                || !text(value, "content_sha256").equals(ownHash(value))) {
            throw failure("authoritative command receipt is missing or tampered");
        }
        SCHEMAS.validateKnownContractSchema(value);
        if (value.path("details").path("active").asBoolean(false) || containsActiveString(value)) {
            throw failure("authoritative command receipt may not claim ACTIVE");
        }
        return true;
    }

    public record BehaviorRegistryPaths(Path directory, Path statePath, Path seedPath) {}

    public static BehaviorRegistryPaths behaviorRegistryStatePaths(Path recordRoot) {
        return behaviorRegistryStatePaths(recordRoot, null);
    }

    public static BehaviorRegistryPaths behaviorRegistryStatePaths(Path recordRoot, Path explicitPath) {
        Path root = absolute(recordRoot); Path directory = root.resolve("behavior-definitions");
        Path canonical = directory.resolve("behavior-definition-registry-head.json");
        Path requested = explicitPath == null ? null : absolute(explicitPath);
        boolean immutable = requested != null && SNAPSHOT.matcher(requested.getFileName().toString()).matches();
        Path state = requested != null && !immutable ? requested : canonical;
        Path legacy = root.resolve("behavior-definition-registry.json");
        List<Path> snapshots = new ArrayList<>();
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "registry-*.json")) {
                for (Path path : entries) if (SNAPSHOT.matcher(path.getFileName().toString()).matches()) snapshots.add(path.toAbsolutePath().normalize());
            } catch (IOException error) { throw failure(error.getMessage()); }
            snapshots.sort(Comparator.comparing(Path::toString));
        }
        if (!immutable && !Files.exists(state, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(legacy, LinkOption.NOFOLLOW_LINKS) && snapshots.size() > 1) {
            throw failure("multiple immutable behavior-registry snapshots exist without a canonical HEAD predecessor; explicit migration is required");
        }
        Path seed = immutable ? requested : !Files.exists(state, LinkOption.NOFOLLOW_LINKS)
                ? requested != null ? null : Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)
                ? legacy : snapshots.isEmpty() ? null : snapshots.get(0) : null;
        return new BehaviorRegistryPaths(directory, state, seed);
    }

    public static ObjectNode behaviorRegistryStatePaths(ObjectNode options) {
        BehaviorRegistryPaths paths = behaviorRegistryStatePaths(
                Path.of(firstText(options, "recordRoot", "record_root")),
                defined(first(options, "explicitPath", "explicit_path"))
                        ? Path.of(firstText(options, "explicitPath", "explicit_path")) : null);
        ObjectNode result = object().put("directory", paths.directory().toString())
                .put("statePath", paths.statePath().toString());
        if (paths.seedPath() == null) result.putNull("seedPath"); else result.put("seedPath", paths.seedPath().toString());
        return result;
    }

    public static Path canonicalFamilyCustodyRoot(String hypothesisFamily) {
        String family = hypothesisFamily == null ? "" : hypothesisFamily.trim();
        if (family.isEmpty()) throw failure("canonical family custody requires a stable hypothesis family");
        ObjectNode identity = object().put("schema", "strategy-v5-family-custody/1")
                .put("hypothesis_family", family);
        return repositoryRoot().resolve("strategy-research/v5-records/families").resolve(hash(identity));
    }

    public static Path canonicalExposureHeadPath(String hypothesisFamily) {
        return canonicalFamilyCustodyRoot(hypothesisFamily).resolve("exposure-head.json");
    }

    public static ObjectNode reopenAuthoritativeGeneticCheckpoint(ObjectNode options) {
        String path = firstText(options, "checkpointPath", "checkpoint_path");
        if (path.isEmpty()) throw failure("authoritative genetic checkpoint path is missing");
        Path target = absolute(Path.of(path));
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null;
        JsonNode artifact = field(options, "artifact"), exposure = first(options, "exposureHead", "exposure_head");
        JsonNode space = first(options, "geneSpace", "gene_space"), fold = first(options, "foldId", "fold_id");
        if (!artifact.isObject() || !exposure.isObject() || !space.isObject() || !defined(fold)) {
            throw failure("authoritative genetic checkpoint validation inputs are incomplete");
        }
        if (Files.exists(Path.of(target + ".lock"), LinkOption.NOFOLLOW_LINKS)) {
            throw failure("authoritative genetic checkpoint has a competing active writer");
        }
        try {
            ObjectNode checkpoint = StrategyStatisticalV5.readGeneticCheckpointFile(target);
            ObjectNode args = object(); args.set("artifact", artifact); args.set("exposureHead", exposure);
            args.set("geneSpace", space); args.set("foldId", fold);
            args.set("config", defined(field(options, "config")) ? field(options, "config") : geneticCheckpointConfig());
            StrategyStatisticalV5.validateGeneticCheckpoint(checkpoint, args);
            if (!text(checkpoint, "exposure_predecessor_sha256").equals(text(exposure, "content_sha256"))) {
                throw failure("checkpoint exposure predecessor is stale");
            }
            return checkpoint;
        } catch (RuntimeException error) {
            throw failure("authoritative genetic checkpoint cannot be resumed: " + error.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Data coverage and command orchestration                             */
    /* ------------------------------------------------------------------ */

    public static ObjectNode coverageReport(ObjectNode options) {
        ObjectNode plan = requiredObject(options, "plan");
        requireBoundContract(plan, StrategyResearchDataV5.DATA_V5.get("plan"), "authoritative plan");
        ObjectNode catalog = optionalObject(field(options, "catalog"));
        ObjectNode acquisition = optionalObject(field(options, "acquisition"));
        ObjectNode parquet = optionalObject(field(options, "parquet"));
        if (catalog != null) {
            requireBoundContract(catalog, StrategyResearchDataV5.DATA_V5.get("datedCatalog"), "dated-futures catalog");
            if (!text(plan, "dated_futures_catalog_sha256").equals(text(catalog, "content_sha256"))
                    || !text(plan, "dated_futures_catalog_status").equals(text(catalog, "status"))) {
                throw failure("coverage report plan/catalog hash or status binding is invalid");
            }
            if (rows(catalog.path("contracts")).stream().anyMatch(row -> row.path("tradeable").asBoolean(false))
                    && firstText(options, "catalogRoot", "catalog_root").isEmpty()) {
                throw failure("coverage report cannot project dated tradeability without a physical catalog/metadata root");
            }
        }
        if (acquisition != null) {
            requireBoundContract(acquisition, StrategyResearchDataV5.DATA_V5.get("acquisition"), "acquisition manifest");
            if (!text(acquisition, "plan_sha256").equals(text(plan, "content_sha256"))) {
                throw failure("coverage report acquisition manifest is bound to a different plan");
            }
        }
        if (parquet != null) {
            if (acquisition == null) throw failure("coverage report cannot accept Parquet without its acquisition manifest");
            requireBoundContract(parquet, "strategy-v5-parquet-conversion/1", "Parquet conversion manifest");
            if (!text(parquet, "plan_sha256").equals(text(plan, "content_sha256"))) {
                throw failure("coverage report Parquet manifest is bound to a different plan");
            }
            if (!text(parquet, "source_manifest_sha256").equals(text(acquisition, "content_sha256"))) {
                throw failure("coverage report Parquet manifest is not bound to the acquisition manifest");
            }
        }
        validateCaptureBindings(plan, acquisition, "acquisition");
        validateCaptureBindings(plan, parquet, "Parquet");

        Map<String, JsonNode> acquired = byCoverageIdentity(acquisition == null ? null : acquisition.path("captures"));
        Map<String, JsonNode> promoted = byCoverageIdentity(parquet == null ? null : parquet.path("captures"));
        boolean physical = false;
        String acquisitionRoot = firstText(options, "acquisitionRoot", "acquisition_root");
        String parquetRoot = firstText(options, "parquetRoot", "parquet_root");
        if (acquisition != null && parquet != null && !acquisitionRoot.isEmpty() && !parquetRoot.isEmpty()) {
            ObjectNode verify = object(); verify.set("manifest", acquisition); verify.set("plan", plan);
            verify.put("root", acquisitionRoot).put("planSha256", text(plan, "content_sha256"));
            StrategyResearchDataV5.verifyAuthoritativeStaging(verify);
            ObjectNode verifyParquet = object(); verifyParquet.set("manifest", parquet);
            verifyParquet.put("root", parquetRoot).put("stagingRoot", acquisitionRoot)
                    .put("planSha256", text(plan, "content_sha256"));
            StrategyResearchDataV5.verifyParquetConversionManifestAuthoritative(verifyParquet);
            physical = true;
        }

        ArrayNode seriesRows = array();
        for (JsonNode series : rows(plan.path("series"))) {
            JsonNode capture = acquired.get(coverageIdentity(series));
            JsonNode parquetCapture = promoted.get(coverageIdentity(series));
            JsonNode observed = capture == null ? NODES.objectNode() : capture.path("coverage");
            long observedRows = firstIntegral(observed, "observed_rows", "observed_events");
            if (observedRows < 0 && capture != null) observedRows = capture.path("partition").path("row_count").asLong(0);
            boolean complete = physical && capture != null && capture.path("coverage").path("complete").asBoolean(false)
                    && capture.path("partition").isObject() && parquetCapture != null
                    && parquetCapture.path("partition").isObject();
            List<String> gaps = new ArrayList<>(sortedTexts(observed.path("missing_slots"), false));
            if (defined(observed.get("reason"))) gaps.add(observed.get("reason").asText());
            if (capture == null) gaps.add("NOT_ACQUIRED");
            if (capture != null && capture.path("unavailable").asBoolean(false)) gaps.add("UNAVAILABLE");
            gaps = gaps.stream().distinct().sorted().toList();
            ObjectNode row = object();
            for (String key : List.of("asset", "venue", "instrument", "symbol", "interval", "series_type", "series_role")) row.put(key, text(series, key));
            row.put("requested_start_at", text(series, "start_at")); row.put("requested_end_at", text(series, "end_at"));
            row.put("availability_cutoff_at", text(series, "availability_cutoff_at"));
            row.put("required", !series.has("required") || series.path("required").asBoolean());
            row.put("tradeable", series.path("tradeable").asBoolean(false)); row.put("observed_rows", Math.max(0, observedRows));
            if (series.path("expected_event_count").isIntegralNumber()) row.put("expected_rows", series.path("expected_event_count").asLong()); else row.putNull("expected_rows");
            putTimeOrNull(row, "observed_min_event_time", first(observed, "min_event_time", "first_event_time"));
            putTimeOrNull(row, "observed_max_event_time", first(observed, "max_event_time", "last_event_time"));
            putTimeOrNull(row, "observed_min_availability_time", observed.get("min_availability_time"));
            putTimeOrNull(row, "observed_max_availability_time", observed.get("max_availability_time"));
            row.set("gaps", strings(gaps)); row.put("complete", complete);
            row.set("raw_receipt_sha256", receiptHashes(capture, "raw_receipts", false));
            row.set("raw_receipt_byte_sha256", receiptHashes(capture, "raw_receipts", true));
            row.set("source_receipt_sha256", receiptHashes(capture, "source_receipts", false));
            row.set("source_receipt_byte_sha256", receiptHashes(capture, "source_receipts", true));
            row.set("jsonl_partition", coveragePartition(capture == null ? null : capture.get("partition")));
            row.set("parquet_partition", coveragePartition(parquetCapture == null ? null : parquetCapture.get("partition")));
            List<String> limitations = new ArrayList<>();
            if (capture != null) limitations.addAll(sortedTexts(capture.path("limitations"), false));
            if (parquetCapture != null) limitations.addAll(sortedTexts(parquetCapture.path("limitations"), false));
            limitations.addAll(gaps); row.set("limitations", strings(normalizeLimitations(limitations, capture, parquetCapture)));
            seriesRows.add(row);
        }

        Map<String, List<JsonNode>> catalogByAsset = new HashMap<>();
        for (String asset : StrategyResearchDataV5.DATA_V5_ASSETS) catalogByAsset.put(asset, new ArrayList<>());
        if (catalog != null) for (JsonNode contract : rows(catalog.path("contracts"))) {
            catalogByAsset.computeIfAbsent(lower(text(contract, "asset")), ignored -> new ArrayList<>()).add(contract);
        }
        ArrayNode dated = array();
        for (String asset : StrategyResearchDataV5.DATA_V5_ASSETS) {
            List<JsonNode> contracts = catalogByAsset.getOrDefault(asset, List.of());
            boolean discovered = contracts.stream().anyMatch(row -> "SIGNAL_HISTORY_AVAILABLE".equals(text(row, "history_status")));
            ObjectNode row = object().put("asset", asset).put("instrument", "BINANCE_USDM_DATED_FUTURE");
            if (contracts.size() == 1 && defined(contracts.get(0).get("symbol"))) row.put("symbol", text(contracts.get(0), "symbol")); else row.putNull("symbol");
            row.put("history_status", "UNAVAILABLE").put("tradeable", false);
            ArrayNode projected = array();
            for (JsonNode contract : contracts) {
                ObjectNode copy = ((ObjectNode) contract).deepCopy(); copy.put("tradeable", false);
                if ("ARCHIVE_INGESTED".equals(text(copy, "archive_ingestion_status"))) copy.put("archive_ingestion_status", "ARCHIVE_DISCOVERED_NOT_INGESTED");
                copy.put("archive_coverage_complete", false); projected.add(copy);
            }
            row.set("contracts", projected);
            row.set("limitations", strings(List.of(discovered
                    ? asset + ":DATED_FUTURES_DISCOVERED_NOT_INGESTED"
                    : asset + ":HISTORICAL_DATED_FUTURES_UNAVAILABLE_OR_NOT_LISTED")));
            dated.add(row);
        }
        boolean allComplete = physical && !seriesRows.isEmpty();
        for (JsonNode row : seriesRows) if (row.path("required").asBoolean(false) && !row.path("complete").asBoolean(false)) allComplete = false;
        boolean observedAny = acquisition != null || catalog != null;
        String mode = text(options, "mode");
        List<String> limitations = new ArrayList<>();
        limitations.addAll(sortedTexts(plan.path("limitations"), false));
        if (catalog != null) limitations.addAll(sortedTexts(catalog.path("limitations"), false));
        if (acquisition != null) limitations.addAll(sortedTexts(acquisition.path("limitations"), false));
        if (parquet != null) limitations.addAll(sortedTexts(parquet.path("limitations"), false));
        if (acquisition != null && parquet != null && !physical) limitations.add("PHYSICAL_REOPEN_REQUIRED_FOR_COMPLETE_COVERAGE");
        if ("PLAN_ONLY".equals(mode) || "CATALOG_ONLY_PLAN".equals(mode)) limitations.add("NO_DATA_ROWS_ACQUIRED");
        ObjectNode result = object().put("schema", "strategy-v5-authoritative-coverage/1").put("version", 1)
                .put("status", allComplete ? "OBSERVED_COMPLETE" : observedAny ? "OBSERVED_PARTIAL" : "PLANNED")
                .put("mode", mode).put("captured_at", text(options, "capturedAt"))
                .put("plan_sha256", text(plan, "content_sha256"));
        putNullable(result, "catalog_sha256", catalog == null ? null : text(catalog, "content_sha256"));
        putNullable(result, "acquisition_sha256", acquisition == null ? null : text(acquisition, "content_sha256"));
        putNullable(result, "parquet_sha256", parquet == null ? null : text(parquet, "content_sha256"));
        putNullable(result, "dataset_root_sha256", physical && parquet != null ? text(parquet, "dataset_root_sha256") : null);
        ObjectNode window = object().put("years", plan.path("window").path("years").asInt())
                .put("start_at", text(plan.path("window"), "start_at"))
                .put("end_at", text(plan.path("window"), "end_at"))
                .put("completed_through_at", text(plan.path("window"), "completed_through_at"));
        result.set("window", window); result.set("assets", plan.path("assets").deepCopy()); result.set("series", seriesRows); result.set("dated_futures", dated);
        result.set("source_receipt_sha256", aggregateReceiptHashes(acquisition, "source_receipts", false));
        result.set("source_receipt_byte_sha256", aggregateReceiptHashes(acquisition, "source_receipts", true));
        result.set("raw_receipt_sha256", aggregateReceiptHashes(acquisition, "raw_receipts", false));
        result.set("raw_receipt_byte_sha256", aggregateReceiptHashes(acquisition, "raw_receipts", true));
        result.set("limitations", strings(normalizeLimitations(limitations, acquisition, parquet)));
        result = withHash(result); SCHEMAS.validateKnownContractSchema(result); return result;
    }

    public static ObjectNode authoritativeDataBackfill(ObjectNode options) {
        if (bool(first(options, "download"))) {
            return unavailableCommand("data-backfill", options,
                    "AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: native public download orchestration is not configured; provide frozen physical acquisition artifacts");
        }
        if (defined(first(options, "plan", "data_plan"))) {
            throw failure("data-backfill --plan/--catalog reuse is only valid with explicit --download");
        }
        String asOf = firstText(options, "as_of", "asOf");
        if (asOf.isEmpty()) throw failure("data-backfill requires an explicit --as-of timestamp so five-year bounds are reproducible");
        if (bool(first(options, "catalog_only", "catalogOnly"))) {
            return unavailableCommand("data-backfill", options,
                    "AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: dated-futures public catalog discovery adapter is not configured");
        }
        ObjectNode planArgs = object().put("asOf", asOf).put("years", 5)
                .put("rootReference", firstTextOr(options, "strategy-research/v5-data", "root_reference", "rootReference"));
        planArgs.set("assets", strings(StrategyResearchDataV5.DATA_V5_ASSETS));
        ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(planArgs);
        String capturedAt = firstTextOr(options, Instant.now().toString(), "captured_at", "capturedAt");
        ObjectNode coverageArgs = object().put("capturedAt", capturedAt).put("mode", "PLAN_ONLY"); coverageArgs.set("plan", plan);
        ObjectNode coverage = coverageReport(coverageArgs);
        Path recordRoot = recordRoot(options); Path directory = recordRoot.resolve("data-backfill");
        Path planPath = requestedOr(options, directory.resolve("plan-" + text(plan, "content_sha256") + ".json"), "out", "plan_out");
        Path coveragePath = requestedOr(options, directory.resolve("coverage-" + text(coverage, "content_sha256") + ".json"), "coverage_out");
        writeImmutable(planPath, plan); writeImmutable(coveragePath, coverage);
        ArrayNode outputs = array().add(reference(planPath, "plan")).add(reference(coveragePath, "coverage"));
        List<String> limitations = new ArrayList<>(sortedTexts(plan.path("limitations"), false));
        limitations.addAll(sortedTexts(coverage.path("limitations"), false));
        limitations.add("PLAN_ONLY: no public rows were downloaded");
        limitations.add("JSONL_STAGING_ONLY: no Parquet is authoritative until explicit --download");
        ObjectNode receiptOptions = object().put("command", "data-backfill").put("status", "PLANNED");
        receiptOptions.set("inputs", array()); receiptOptions.set("outputs", outputs); receiptOptions.set("limitations", strings(limitations));
        receiptOptions.set("details", object().put("mode", "PLAN_ONLY").put("plan_sha256", text(plan, "content_sha256"))
                .putNull("catalog_sha256").put("coverage_sha256", text(coverage, "content_sha256")));
        ObjectNode receipt = makeCommandReceipt(receiptOptions); Path receiptPath = writeDurableReceipt(receipt, options);
        ObjectNode result = object(); result.set("plan", plan); result.putNull("catalog"); result.set("coverage", coverage);
        result.set("receipt", receipt); result.put("receipt_path", receiptPath.toString()); return result;
    }

    public static ObjectNode authoritativeDataRawReplay(ObjectNode options) {
        ObjectNode blocked = blockedPrerequisites("data-raw-replay", options, List.of(
                prerequisite("plan", "frozen authoritative plan"),
                prerequisite(firstPresentKey(options, "source_checkpoint", "checkpoint"), "source acquisition checkpoint"),
                prerequisite(firstPresentKey(options, "source_root", "resume_staging_root", "resume_root"), "source root"),
                prerequisite(firstPresentKey(options, "target_root", "staging_root", "output_root"), "local replay target root")));
        if (blocked != null) return blocked;
        PhysicalJson plan = physicalJson(Path.of(firstText(options, "plan", "data_plan")), "frozen authoritative plan", Set.of(StrategyResearchDataV5.DATA_V5.get("plan")));
        PhysicalJson checkpoint = physicalJson(Path.of(firstText(options, "source_checkpoint", "checkpoint")), "source acquisition checkpoint", Set.of(StrategyResearchDataV5.DATA_V5.get("checkpoint")));
        ObjectNode args = object(); args.set("plan", plan.value()); args.set("sourceCheckpoint", checkpoint.value());
        args.put("sourceRoot", firstText(options, "source_root", "resume_staging_root", "resume_root"));
        args.put("targetRoot", firstText(options, "target_root", "staging_root", "output_root"));
        args.put("targetRootReference", firstText(options, "target_root_reference", "staging_root_reference"));
        args.put("checkpointPath", firstTextOr(options, "checkpoint.json", "target_checkpoint", "checkpoint_path"));
        ObjectNode replay = StrategyResearchDataV5.replayAuthoritativeStagingFromRaw(args);
        ObjectNode receipt = receipt("data-raw-replay", "COMPLETE", array(), array(), array(),
                object().put("mode", "LOCAL_RAW_REPLAY_NO_NETWORK")
                        .put("source_checkpoint_sha256", text(checkpoint.value(), "content_sha256"))
                        .put("replayed_count", replay.path("replayed_count").asInt())
                        .put("retained_count", replay.path("retained_count").asInt())
                        .put("acquisition_sha256", text(replay.path("acquisition"), "content_sha256")));
        Path receiptPath = writeDurableReceipt(receipt, options); ObjectNode result = replay.deepCopy();
        result.set("receipt", receipt); result.put("receipt_path", receiptPath.toString()); return result;
    }

    public static ObjectNode authoritativeFeatureBuild(ObjectNode options) {
        ObjectNode blocked = blockedPrerequisites("feature-build", options, List.of(
                prerequisite("plan", "plan"), prerequisite("acquisition", "acquisition"),
                prerequisite("staging_root", "staging root"), prerequisite("predictor_registry", "predictor registry"),
                prerequisite("precommit", "precommit")));
        if (blocked != null) return blocked;
        rejectLooseOptions(options, Set.of());
        for (String key : List.of("features", "feature_rows", "labels", "outcomes")) if (options.has(key)) {
            throw failure("feature-build derives features internally and rejects caller-authored feature/label/outcome rows");
        }
        PhysicalJson plan = physicalJson(Path.of(text(options, "plan")), "feature-build plan", Set.of(StrategyResearchDataV5.DATA_V5.get("plan")));
        PhysicalJson acquisition = physicalJson(Path.of(text(options, "acquisition")), "feature-build acquisition", Set.of(StrategyResearchDataV5.DATA_V5.get("acquisition")));
        PhysicalJson registry = physicalJson(Path.of(text(options, "predictor_registry")), "feature-build predictor registry", Set.of("strategy-v5-predictor-registry/1"));
        PhysicalJson precommit = frozenPrecommit(Path.of(text(options, "precommit")), "feature-build precommit");
        ObjectNode requirementArgs = object(); requirementArgs.set("predictorRegistry", registry.value());
        requirementArgs.put("precommitSha256", text(precommit.value(), "content_sha256"));
        ObjectNode requirements = StrategyResearchDataV5.makeTimeframeRequirementsFromPredictorRegistry(requirementArgs);
        ObjectNode build = object().put("root", text(options, "staging_root"))
                .put("rootReference", firstText(options, "root_reference"));
        build.set("plan", plan.value()); build.set("acquisition", acquisition.value()); build.set("predictorRegistry", registry.value());
        build.set("precommit", precommit.value()); build.set("timeframeRequirements", requirements);
        ObjectNode produced = StrategyResearchDataV5.produceAuthoritativeFeatureSource(build);
        ObjectNode manifest = (ObjectNode) produced.path("manifest"); ObjectNode promoted = (ObjectNode) produced.path("promotedCoverage");
        Path requirementsPath = requestedOr(options, durableArtifactPath(options, requirements, "timeframe-requirements"), "requirements_out");
        Path coveragePath = requestedOr(options, durableArtifactPath(options, promoted, "promoted-coverage"), "coverage_out");
        Path featurePath = requestedOr(options, durableArtifactPath(options, manifest, "feature-source"), "out", "feature_source_out");
        writeImmutable(requirementsPath, requirements); writeImmutable(coveragePath, promoted); writeImmutable(featurePath, manifest);
        ArrayNode inputs = array().add(reference(plan.path(), "plan")).add(reference(acquisition.path(), "acquisition"))
                .add(reference(registry.path(), "predictor_registry")).add(reference(precommit.path(), "precommit"));
        ArrayNode outputs = array().add(reference(requirementsPath, "timeframe_requirements"))
                .add(reference(coveragePath, "promoted_coverage")).add(reference(featurePath, "feature_source"));
        ObjectNode details = object().put("mode", "PHYSICAL_ACQUISITION_TO_DETERMINISTIC_FEATURE_SOURCE")
                .put("feature_source_sha256", text(manifest, "content_sha256"))
                .put("feature_rows", manifest.path("artifact").path("row_count").asInt())
                .put("requirements_sha256", text(requirements, "content_sha256"))
                .put("promoted_coverage_sha256", text(promoted, "content_sha256"))
                .put("coverage_status", text(promoted, "status")).put("base_only_compatible", true);
        ObjectNode receipt = receipt("feature-build", "COMPLETE", inputs, outputs, manifest.path("limitations"), details);
        Path receiptPath = writeDurableReceipt(receipt, options); ObjectNode result = object();
        result.set("feature_source", manifest); result.set("requirements", requirements); result.set("promoted_coverage", promoted);
        result.put("feature_source_path", featurePath.toString()).put("feature_root", absolute(Path.of(text(options, "staging_root"))).toString());
        result.set("receipt", receipt); result.put("receipt_path", receiptPath.toString()); return result;
    }

    public static ObjectNode authoritativeMetadataBuild(ObjectNode options) {
        rejectLooseOptions(options, Set.of());
        String outputRootText = firstText(options, "output_root", "metadata_root");
        if (!outputRootText.isEmpty()) createDirectoryCustody(absolute(Path.of(outputRootText)));
        ObjectNode effective = options.deepCopy();
        if (!outputRootText.isEmpty()) effective.put("output_root", outputRootText);
        ObjectNode blocked = blockedPrerequisites("metadata-build", effective, List.of(
                prerequisite("plan", "plan"), prerequisite("precommit", "precommit"),
                prerequisite("evaluator_spec", "evaluator spec"),
                prerequisite("policy", "spot execution policy"),
                prerequisite("output_root", "metadata output root")));
        if (blocked != null) return blocked;
        Path root = absolute(Path.of(outputRootText));
        PhysicalJson plan = physicalJson(Path.of(text(options, "plan")), "metadata-build plan",
                Set.of(StrategyResearchDataV5.DATA_V5.get("plan")));
        PhysicalJson precommit = frozenPrecommit(Path.of(text(options, "precommit")),
                "metadata-build precommit");
        PhysicalJson evaluator = physicalJson(Path.of(text(options, "evaluator_spec")),
                "metadata-build evaluator spec", Set.of("strategy-v5-evaluator-spec/1"));
        PhysicalJson policy = physicalJson(Path.of(text(options, "policy")),
                "metadata-build spot execution policy", Set.of("strategy-v5-spot-execution-policy/1"));
        ObjectNode build = object().put("root", root.toString());
        if (!firstText(options, "root_reference").isEmpty()) {
            build.put("rootReference", firstText(options, "root_reference"));
        }
        build.set("plan", plan.value()); build.set("precommit", precommit.value());
        build.set("evaluatorSpec", evaluator.value()); build.set("policy", policy.value());
        build.put("policyBytes", new String(policy.rawBytes(), StandardCharsets.UTF_8));
        ObjectNode built = StrategyResearchDataV5.buildUserBoundSpotMetadataV5(build);
        ObjectNode bundle = (ObjectNode) built.path("bundle");
        Path bundlePath = firstText(options, "out").isEmpty()
                ? root.resolve("bundles").resolve("spot-metadata-" + text(built, "bundle_sha256") + ".json")
                : absolute(Path.of(text(options, "out")));
        writeImmutableBundle(bundlePath, bundle);
        PhysicalMetadataBundle reopened = physicalMetadataBundle(bundlePath, root);
        if (!reopened.contentSha256().equals(text(built, "bundle_sha256"))) {
            throw failure("metadata-build bundle changed during physical reopen");
        }
        ArrayNode inputs = array().add(physicalReference(plan, "plan"))
                .add(physicalReference(precommit, "precommit"))
                .add(physicalReference(evaluator, "evaluator_spec"))
                .add(physicalReference(policy, "spot_execution_policy"));
        ArrayNode outputs = array().add(metadataReference(reopened, "metadata"));
        Set<String> limitationSet = new java.util.TreeSet<>();
        rows(policy.value().path("limitations")).forEach(value -> limitationSet.add(value.asText()));
        limitationSet.addAll(List.of("RETROSPECTIVE_USER_BOUND_RESEARCH_ASSUMPTION",
                "NOT_ACTIVATION_EVIDENCE", "SPOT_EXECUTION_ONLY"));
        ObjectNode details = object().put("mode", "LOCAL_USER_BOUND_SPOT_EXECUTION_METADATA")
                .put("spot_execution_policy_sha256", text(policy.value(), "content_sha256"))
                .put("metadata_bundle_sha256", text(built, "bundle_sha256"))
                .put("evaluator_spec_sha256", text(evaluator.value(), "content_sha256"))
                .put("plan_sha256", text(plan.value(), "content_sha256"))
                .put("source_root", root.toString().replace(root.getFileSystem().getSeparator(), "/"))
                .put("record_count", built.path("trade_scope").path("trade_assets").size());
        ObjectNode receipt = receipt("metadata-build", "COMPLETE", inputs, outputs,
                strings(limitationSet), details);
        Path receiptPath = writeDurableReceipt(receipt, options);
        ObjectNode result = object(); result.set("metadata", bundle); result.put("metadata_path", bundlePath.toString());
        result.put("metadata_root", root.toString()); result.set("source_receipt", built.path("source_receipt"));
        result.set("trade_scope", built.path("trade_scope")); result.set("receipt", receipt);
        result.put("receipt_path", receiptPath.toString()); return result;
    }

    public static ObjectNode authoritativeOpportunityEnvelope(ObjectNode options) {
        rejectLooseOptions(options, Set.of());
        ObjectNode blocked = blockedPrerequisites("opportunity-envelope", options, List.of(
                prerequisite("plan", "plan"), prerequisite("acquisition", "acquisition"),
                prerequisite("staging_root", "staging root"), prerequisite("candidates", "candidate set"),
                prerequisite("precommit", "precommit"), prerequisite("gene_space", "gene space"),
                prerequisite("predictor_registry", "predictor registry"),
                prerequisite("evaluator_spec", "evaluator spec"),
                prerequisite("feature_source", "authoritative feature source")));
        if (blocked != null) return blocked;
        for (String key : List.of("features", "feature_set", "feature_rows")) if (options.has(key)) {
            throw failure("opportunity-envelope rejects caller-authored feature rows; run feature-build and pass --feature-source");
        }
        for (String key : List.of("labels", "label_set", "outcomes")) if (options.has(key)) {
            throw failure("opportunity-envelope never accepts labels/outcomes");
        }
        Path stagingRoot = absolute(Path.of(text(options, "staging_root")));
        PhysicalJson planPhysical = physicalJson(Path.of(text(options, "plan")), "authoritative plan",
                Set.of(StrategyResearchDataV5.DATA_V5.get("plan")));
        PhysicalJson acquisitionPhysical = physicalJson(Path.of(text(options, "acquisition")),
                "acquired staging manifest", Set.of(StrategyResearchDataV5.DATA_V5.get("acquisition")));
        ObjectNode plan = planPhysical.value(), acquisition = acquisitionPhysical.value();
        if (!text(acquisition, "plan_sha256").equals(text(plan, "content_sha256"))
                || !"STAGING_COMPLETE".equals(text(acquisition, "status"))
                || acquisition.path("authoritative").asBoolean(true)
                || !"STAGING".equals(text(acquisition, "storage_role"))) {
            throw failure("opportunity-envelope requires a complete acquired staging manifest bound to the plan");
        }
        ObjectNode verifyStaging = object().set("manifest", acquisition); verifyStaging.put("root", stagingRoot.toString());
        verifyStaging.set("plan", plan); verifyStaging.put("planSha256", text(plan, "content_sha256"));
        StrategyResearchDataV5.verifyAuthoritativeStaging(verifyStaging);
        PhysicalJson candidatePhysical = physicalJson(Path.of(text(options, "candidates")),
                "frozen candidate set", Set.of("strategy-candidate-set/5"));
        ObjectNode candidate = candidatePhysical.value(); String candidateSha = firstText(options, "candidate_set_sha256");
        if (candidateSha.isEmpty()) candidateSha = text(candidate, "content_sha256");
        requireSha(candidateSha, "candidate_set_sha256");
        if (!candidateSha.equals(text(candidate, "content_sha256"))) {
            throw failure("candidate set hash does not match frozen --candidate-set-sha256");
        }
        PhysicalJson precommitPhysical = frozenPrecommit(Path.of(text(options, "precommit")),
                "physical precommit artifact");
        PhysicalJson genePhysical = physicalJson(Path.of(text(options, "gene_space")), "frozen gene space", Set.of());
        PhysicalJson predictorPhysical = physicalJson(Path.of(text(options, "predictor_registry")),
                "frozen predictor registry", Set.of("strategy-v5-predictor-registry/1"));
        PhysicalJson evaluatorPhysical = physicalJson(Path.of(text(options, "evaluator_spec")),
                "frozen evaluator spec", Set.of("strategy-v5-evaluator-spec/1"));
        ObjectNode nestedGene = optionalObject(candidate.path("gene_space"));
        if (nestedGene == null || !text(nestedGene, "content_sha256").equals(text(genePhysical.value(), "content_sha256"))
                || !text(nestedGene, "content_sha256").equals(ownHash(nestedGene))) {
            throw failure("candidate set nested gene space does not exactly match the supplied physical gene space");
        }
        ObjectNode specBindings = object(); specBindings.set("geneSpace", genePhysical.value());
        specBindings.set("predictorRegistry", predictorPhysical.value());
        StrategyEvaluatorV5.validateEvaluatorSpecV5(evaluatorPhysical.value(), specBindings);
        ObjectNode scopeOptions = object(); scopeOptions.set("candidateTemplate", evaluatorPhysical.value().path("candidate_template"));
        StrategyResearchDataV5.derivePrecommitTradeScopeV5(precommitPhysical.value(), scopeOptions);
        if (!text(evaluatorPhysical.value(), "precommit_sha256")
                .equals(text(precommitPhysical.value(), "content_sha256"))) {
            throw failure("precommit/evaluator spec lineage differs");
        }
        PhysicalJson featurePhysical = physicalJson(Path.of(text(options, "feature_source")),
                "authoritative feature source", Set.of(StrategyResearchDataV5.DATA_V5.get("featureSource")));
        ObjectNode validateFeature = object().set("manifest", featurePhysical.value());
        validateFeature.put("root", stagingRoot.toString());
        validateFeature.put("expectedPlanSha256", text(plan, "content_sha256"));
        validateFeature.put("expectedAcquisitionSha256", text(acquisition, "content_sha256"));
        validateFeature.put("expectedPredictorRegistrySha256", text(predictorPhysical.value(), "content_sha256"));
        validateFeature.put("expectedPrecommitSha256", text(precommitPhysical.value(), "content_sha256"));
        ObjectNode verifiedFeature = StrategyResearchDataV5.validateAuthoritativeFeatureSource(validateFeature);
        long lifecycleMs = first(options, "max_lifecycle_ms", "lifecycle_ms").isNumber()
                ? first(options, "max_lifecycle_ms", "lifecycle_ms").asLong() : 30L * 86_400_000L;
        if (lifecycleMs <= 0 || lifecycleMs > 30L * 86_400_000L) {
            throw failure("opportunity envelope lifecycle is frozen to at most 30 days");
        }
        rejectFeatureOutcomeFields(verifiedFeature.path("rows"), "features");
        ObjectNode branch = object().put("branch_id", "__FULL_MUTABLE_GENE_DOMAIN__").putNull("candidate_id");
        branch.set("predicate", evaluatorPhysical.value().path("predicate").deepCopy());
        ObjectNode domainOptions = object(); domainOptions.set("candidateSet", candidate);
        domainOptions.set("branches", array().add(branch)); domainOptions.set("precommit", precommitPhysical.value());
        domainOptions.set("geneSpace", genePhysical.value()); domainOptions.set("evaluatorSpec", evaluatorPhysical.value());
        domainOptions.set("predictorRegistry", predictorPhysical.value()); domainOptions.put("fixtureOnly", false);
        ObjectNode domain = OpportunityV5.makeOpportunityDomainV5(domainOptions);
        ObjectNode envelopeOptions = object(); envelopeOptions.set("featureRows", verifiedFeature.path("rows"));
        envelopeOptions.set("plan", plan); envelopeOptions.set("candidateSet", candidate);
        envelopeOptions.set("opportunityDomain", domain); envelopeOptions.set("geneSpace", genePhysical.value());
        envelopeOptions.put("gene_space_sha256", text(genePhysical.value(), "content_sha256"));
        envelopeOptions.set("predicate", evaluatorPhysical.value().path("predicate"));
        envelopeOptions.set("precommit", precommitPhysical.value());
        envelopeOptions.set("predictorRegistry", predictorPhysical.value());
        envelopeOptions.set("evaluatorSpec", evaluatorPhysical.value());
        envelopeOptions.put("max_lifecycle_ms", lifecycleMs).put("execution_interval_ms", 60_000);
        envelopeOptions.put("fullDomain", true).put("fixtureOnly", false);
        ObjectNode envelope = OpportunityV5.makeOpportunityEnvelopeV5(envelopeOptions);
        OpportunityV5.validateOpportunityEnvelopeV5(envelope);
        ArrayNode inputs = array().add(physicalReference(planPhysical, "plan"))
                .add(physicalReference(acquisitionPhysical, "acquisition"))
                .add(physicalReference(candidatePhysical, "candidate_set"))
                .add(physicalReference(precommitPhysical, "precommit"))
                .add(physicalReference(genePhysical, "gene_space"))
                .add(physicalReference(predictorPhysical, "predictor_registry"))
                .add(physicalReference(evaluatorPhysical, "evaluator_spec"))
                .add(physicalReference(featurePhysical, "feature_source"));
        Path domainPath = requestedOr(options, durableArtifactPath(options, domain, "opportunity-domain"), "domain_out");
        Path envelopePath = requestedOr(options, durableArtifactPath(options, envelope, "opportunity-envelope"), "out");
        writeImmutable(domainPath, domain); writeImmutable(envelopePath, envelope);
        ArrayNode outputs = array().add(reference(domainPath, "opportunity_domain"))
                .add(reference(envelopePath, "opportunity_envelope"));
        if (!options.path("hydrate").asBoolean(false)) {
            List<String> limitations = List.of("PHYSICAL_1M_HYDRATION_REQUIRED",
                    "AUTHORITATIVE_ENVELOPE_NOT_EXECUTABLE_WITHOUT_HYDRATION");
            ObjectNode details = object().put("mode", "V2_CONSERVATIVE_FULL_DOMAIN_ENVELOPE_ONLY")
                    .put("envelope_schema", text(envelope, "schema")).putNull("hydration_schema")
                    .put("envelope_sha256", text(envelope, "content_sha256")).putNull("hydration_sha256")
                    .putNull("physical_hydration_sha256");
            ObjectNode receipt = receipt("opportunity-envelope", "BLOCKED", inputs, outputs,
                    strings(limitations), details); Path receiptPath = writeDurableReceipt(receipt, options);
            ObjectNode result = object().put("status", "BLOCKED"); result.set("envelope", envelope);
            result.putNull("hydration"); result.set("candidate", candidate); result.set("receipt", receipt);
            result.put("receipt_path", receiptPath.toString()); return result;
        }
        ObjectNode reason = unavailableCommand("opportunity-envelope", options,
                "AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: production public-adapter 1m hydration transport is not exposed by the current StrategyResearchDataV5 API");
        reason.set("envelope", envelope); reason.set("candidate", candidate); reason.putNull("hydration");
        return reason;
    }

    public static ObjectNode authoritativeArtifactBuild(ObjectNode options) {
        rejectLooseOptions(options, Set.of("config"));
        ObjectNode blocked = blockedPrerequisites("artifact-build", options, List.of(
                prerequisite("plan", "plan"), prerequisite("acquisition", "acquisition"),
                prerequisite("physical_hydration", "physical v1 hydration"),
                prerequisite("physical_envelope", "physical v1 opportunity envelope"),
                prerequisite("opportunity_domain", "authoritative v2 opportunity domain"),
                prerequisite("opportunity_envelope", "authoritative v2 opportunity envelope"),
                prerequisite("opportunity_hydration", "authoritative v2 opportunity hydration"),
                prerequisite("feature_source", "feature source"), prerequisite("staging_root", "staging root"),
                prerequisite("predictor_registry", "predictor registry"), prerequisite("precommit", "precommit"),
                prerequisite("gene_space", "gene space"), prerequisite("evaluator_spec", "evaluator spec"),
                prerequisite("config", "execution/config contract")));
        if (blocked != null) return blocked;
        if (firstText(options, "parquet_root").isEmpty()) throw failure("artifact-build requires --parquet-root");
        for (String key : List.of("features", "labels", "execution", "marks", "role_receipts", "source_manifest")) {
            if (options.has(key)) throw failure("artifact-build derives every role and source receipt internally; caller-authored roles are rejected");
        }
        Path stagingRoot = requirePhysicalDirectory(Path.of(text(options, "staging_root")), "artifact-build staging root");
        Path parquetRoot = ensurePhysicalDirectory(Path.of(text(options, "parquet_root")), "artifact-build Parquet root");
        Path acquisitionRoot = requirePhysicalDirectory(Path.of(firstTextOr(options, stagingRoot.toString(),
                "acquisition_root", "source_root")), "artifact-build acquisition root");
        Path hydrationRoot = requirePhysicalDirectory(Path.of(firstTextOr(options, stagingRoot.toString(),
                "hydration_root", "opportunity_root")), "artifact-build hydration root");
        Path featureRoot = requirePhysicalDirectory(Path.of(firstTextOr(options, acquisitionRoot.toString(),
                "feature_source_root")), "artifact-build feature-source root");
        PhysicalJson planPhysical = physicalJson(Path.of(text(options, "plan")), "artifact-build plan",
                Set.of(StrategyResearchDataV5.DATA_V5.get("plan")));
        PhysicalJson acquisitionPhysical = physicalJson(Path.of(text(options, "acquisition")),
                "artifact-build acquisition", Set.of(StrategyResearchDataV5.DATA_V5.get("acquisition")));
        PhysicalJson hydrationPhysical = physicalJson(Path.of(text(options, "physical_hydration")),
                "artifact-build physical hydration", Set.of(StrategyResearchDataV5.DATA_V5.get("hydration")));
        PhysicalJson physicalEnvelope = physicalJson(Path.of(text(options, "physical_envelope")),
                "artifact-build physical opportunity envelope", Set.of("strategy-v5-opportunity-envelope/1"));
        PhysicalJson domainPhysical = physicalJson(Path.of(text(options, "opportunity_domain")),
                "artifact-build v2 opportunity domain", Set.of("strategy-v5-opportunity-domain/1"));
        PhysicalJson envelopePhysical = physicalJson(Path.of(text(options, "opportunity_envelope")),
                "artifact-build v2 opportunity envelope", Set.of("strategy-v5-opportunity-envelope/2"));
        PhysicalJson v2HydrationPhysical = physicalJson(Path.of(text(options, "opportunity_hydration")),
                "artifact-build v2 opportunity hydration", Set.of("strategy-v5-opportunity-hydration/2"));
        PhysicalJson featurePhysical = physicalJson(Path.of(text(options, "feature_source")),
                "artifact-build feature source", Set.of(StrategyResearchDataV5.DATA_V5.get("featureSource")));
        PhysicalJson predictorPhysical = physicalJson(Path.of(text(options, "predictor_registry")),
                "artifact-build predictor registry", Set.of("strategy-v5-predictor-registry/1"));
        PhysicalJson precommitPhysical = frozenPrecommit(Path.of(text(options, "precommit")),
                "artifact-build precommit");
        PhysicalJson genePhysical = physicalJson(Path.of(text(options, "gene_space")),
                "artifact-build gene space", Set.of());
        PhysicalJson evaluatorPhysical = physicalJson(Path.of(text(options, "evaluator_spec")),
                "artifact-build evaluator spec", Set.of("strategy-v5-evaluator-spec/1"));
        PhysicalJson configPhysical = physicalJson(Path.of(text(options, "config")),
                "artifact-build execution/config contract", Set.of());
        ObjectNode plan = planPhysical.value(), acquisition = acquisitionPhysical.value();
        ObjectNode hydration = hydrationPhysical.value(), v1Envelope = physicalEnvelope.value();
        ObjectNode domain = domainPhysical.value(), envelope = envelopePhysical.value();
        ObjectNode v2Hydration = v2HydrationPhysical.value(); ObjectNode precommit = precommitPhysical.value();
        ObjectNode evaluator = evaluatorPhysical.value();
        if (!text(hydration, "plan_sha256").equals(text(plan, "content_sha256"))
                || !text(hydration, "envelope_sha256").equals(text(v1Envelope, "content_sha256"))
                || !text(hydration, "candidate_set_sha256").equals(text(v1Envelope, "candidate_set_sha256"))) {
            throw failure("artifact-build physical hydration/envelope/plan lineage differs");
        }
        if (!text(v1Envelope, "precommit_sha256").equals(text(precommit, "content_sha256"))) {
            throw failure("artifact-build physical envelope is bound to a different precommit");
        }
        ObjectNode specBindings = object(); specBindings.set("geneSpace", genePhysical.value());
        specBindings.set("predictorRegistry", predictorPhysical.value());
        StrategyEvaluatorV5.validateEvaluatorSpecV5(evaluator, specBindings);
        ObjectNode scopeOptions = object(); scopeOptions.set("candidateTemplate", evaluator.path("candidate_template"));
        StrategyResearchDataV5.derivePrecommitTradeScopeV5(precommit, scopeOptions);
        ObjectNode familyOptions = object(); familyOptions.set("evaluatorSpec", evaluator);
        canonicalHypothesisFamilyV5(precommit, familyOptions);
        if (!text(evaluator, "precommit_sha256").equals(text(precommit, "content_sha256"))) {
            throw failure("artifact-build evaluator is bound to a different precommit");
        }
        verifyV2OpportunityHydration(domain, envelope, v2Hydration, hydrationRoot,
                text(plan, "content_sha256"));
        if (!text(envelope, "precommit_sha256").equals(text(precommit, "content_sha256"))
                || !text(envelope, "predictor_registry_sha256").equals(text(predictorPhysical.value(), "content_sha256"))
                || !text(envelope, "evaluator_spec_sha256").equals(text(evaluator, "content_sha256"))
                || !text(envelope, "gene_space_sha256").equals(text(genePhysical.value(), "content_sha256"))
                || !text(envelope, "candidate_set_sha256").equals(text(v1Envelope, "candidate_set_sha256"))) {
            throw failure("artifact-build v2 opportunity lineage differs from frozen strategy inputs");
        }
        ObjectNode projectedOptions = object().put("planSha256", text(plan, "content_sha256"))
                .put("candidateSetSha256", text(envelope, "candidate_set_sha256"))
                .put("maxLifecycleMs", envelope.path("max_lifecycle_ms").asLong())
                .put("lifecycleTimeframe", "1m").put("precommitSha256", text(precommit, "content_sha256"));
        ArrayNode projectedWindows = array();
        for (JsonNode window : envelope.path("windows")) {
            ObjectNode projected = object();
            for (String field : List.of("asset", "instrument", "symbol")) projected.set(field, window.path(field).deepCopy());
            projected.set("execution_start", window.path("entry_time").deepCopy());
            projected.set("execution_end", window.path("execution_end").deepCopy());
            projected.set("source_window_ids", array().add(text(window, "window_id"))); projectedWindows.add(projected);
        }
        projectedOptions.set("windows", projectedWindows);
        ObjectNode expectedV1 = StrategyResearchDataV5.makeOpportunityEnvelope(projectedOptions);
        if (!text(expectedV1, "content_sha256").equals(text(v1Envelope, "content_sha256"))) {
            throw failure("artifact-build physical v1 envelope is not the exact deterministic projection of the complete v2 opportunity inventory");
        }
        if (!text(v2Hydration, "physical_hydration_sha256").equals(text(hydration, "content_sha256"))
                || !text(v2Hydration, "physical_root_reference").equals(text(hydration, "root_reference"))) {
            throw failure("artifact-build v2 hydration is not bound to the supplied physical v1 hydration custody");
        }
        ObjectNode featureVerify = object().set("manifest", featurePhysical.value());
        featureVerify.put("root", featureRoot.toString());
        featureVerify.put("expectedPlanSha256", text(plan, "content_sha256"));
        featureVerify.put("expectedAcquisitionSha256", text(acquisition, "content_sha256"));
        featureVerify.put("expectedPredictorRegistrySha256", text(predictorPhysical.value(), "content_sha256"));
        featureVerify.put("expectedPrecommitSha256", text(precommit, "content_sha256"));
        ObjectNode featureSource = StrategyResearchDataV5.validateAuthoritativeFeatureSource(featureVerify);
        ObjectNode sourceOptions = object().put("root", stagingRoot.toString())
                .put("planSha256", text(plan, "content_sha256"))
                .put("candidateSetSha256", text(hydration, "candidate_set_sha256"))
                .put("envelopeSha256", text(v1Envelope, "content_sha256"));
        if (!firstText(options, "root_reference").isEmpty()) sourceOptions.put("rootReference", text(options, "root_reference"));
        sourceOptions.set("acquisition", acquisition); sourceOptions.set("hydration", hydration);
        sourceOptions.put("acquisitionRoot", acquisitionRoot.toString()).put("hydrationRoot", hydrationRoot.toString());
        ObjectNode sourceBundle = StrategyResearchDataV5.makeSourceBundleManifest(sourceOptions);
        ArrayNode candidatePredicates = array();
        for (JsonNode predictorId : StrategyResearchDataV5.derivePredicatePredictorIds((ObjectNode) evaluator.path("predicate"))) {
            candidatePredicates.add(object().put("predictor_id", predictorId.asText()));
        }
        ObjectNode produce = object().put("root", stagingRoot.toString())
                .put("sourceManifestSha256", text(sourceBundle, "content_sha256"))
                .put("sourceDatasetRootSha256", text(sourceBundle, "dataset_root_sha256"))
                .put("transformationCodeSha256", StrategyResearchDataV5.DATA_V5_PRODUCER_CODE_SHA256)
                .put("labelCodeSha256", StrategyResearchDataV5.DATA_V5_PRODUCER_CODE_SHA256)
                .put("executionCodeSha256", StrategyResearchDataV5.DATA_V5_PRODUCER_CODE_SHA256)
                .put("configSha256", text(configPhysical.value(), "content_sha256"))
                .put("precommitSha256", text(precommit, "content_sha256"))
                .put("envelopeSha256", text(envelope, "content_sha256"));
        produce.set("plan", plan); produce.set("predictorRegistry", predictorPhysical.value());
        produce.set("sourceManifestReference", sourceBundle.path("physical_reference"));
        produce.set("precommit", precommit); produce.set("envelope", envelope); produce.set("config", configPhysical.value());
        ObjectNode produced = StrategyResearchDataV5.produceAuthoritativeRoleArtifacts(produce);
        ObjectNode subset = object().set("featureSourceManifest", featureSource.path("manifest"));
        subset.put("root", featureRoot.toString()).set("finalFeatureReference", produced.path("feature"));
        subset.put("finalRoot", stagingRoot.toString()).set("opportunityEnvelope", envelope);
        StrategyResearchDataV5.validateFeatureSubsetAgainstSource(subset);
        ObjectNode roleReceipts = object();
        for (String role : List.of("feature", "label", "execution", "mark")) {
            roleReceipts.set(role, produced.path(role).path("role_receipt"));
        }
        ObjectNode stagingOptions = object().put("root", stagingRoot.toString())
                .put("sourceManifestSha256", text(sourceBundle, "content_sha256"))
                .put("sourceDatasetRootSha256", text(sourceBundle, "dataset_root_sha256"))
                .put("transformationCodeSha256", StrategyResearchDataV5.DATA_V5_PRODUCER_CODE_SHA256)
                .put("labelCodeSha256", StrategyResearchDataV5.DATA_V5_PRODUCER_CODE_SHA256)
                .put("executionCodeSha256", StrategyResearchDataV5.DATA_V5_PRODUCER_CODE_SHA256)
                .put("configSha256", text(configPhysical.value(), "content_sha256"))
                .put("precommitSha256", text(precommit, "content_sha256"))
                .put("envelopeSha256", text(envelope, "content_sha256"));
        stagingOptions.set("plan", plan); stagingOptions.set("predictorRegistry", predictorPhysical.value());
        stagingOptions.set("candidatePredicates", candidatePredicates); stagingOptions.set("sourceManifestReference", sourceBundle.path("physical_reference"));
        stagingOptions.set("roleReceipts", roleReceipts); stagingOptions.set("features", produced.path("feature"));
        stagingOptions.set("labels", produced.path("label")); stagingOptions.set("execution", produced.path("execution"));
        stagingOptions.set("marks", produced.path("mark"));
        ObjectNode stagingManifest = StrategyResearchDataV5.makeSeparatedArtifactManifest(stagingOptions);
        ObjectNode parquetOptions = object().put("stagingRoot", stagingRoot.toString())
                .put("outputRoot", parquetRoot.toString());
        if (!firstText(options, "parquet_root_reference").isEmpty()) {
            parquetOptions.put("outputRootReference", text(options, "parquet_root_reference"));
        }
        parquetOptions.set("stagingManifest", stagingManifest); parquetOptions.set("plan", plan);
        parquetOptions.set("predictorRegistry", predictorPhysical.value()); parquetOptions.set("candidatePredicates", candidatePredicates);
        ObjectNode parquet = StrategyResearchDataV5.convertSeparatedArtifactsToParquet(parquetOptions);
        ObjectNode parquetVerify = object().set("manifest", parquet); parquetVerify.put("root", parquetRoot.toString());
        parquetVerify.set("plan", plan); parquetVerify.set("predictorRegistry", predictorPhysical.value());
        parquetVerify.set("candidatePredicates", candidatePredicates);
        StrategyResearchDataV5.verifyParquetArtifactManifest(parquetVerify);
        Path sourcePath = requestedOr(options, durableArtifactPath(options, sourceBundle, "source-bundle"), "source_bundle_out");
        Path stagingPath = requestedOr(options, durableArtifactPath(options, stagingManifest, "separated-staging"), "staging_out");
        Path parquetPath = requestedOr(options, durableArtifactPath(options, parquet, "separated-parquet"), "out", "parquet_manifest_out");
        writeImmutable(sourcePath, sourceBundle); writeImmutable(stagingPath, stagingManifest); writeImmutable(parquetPath, parquet);
        ArrayNode inputs = array().add(physicalReference(planPhysical, "plan"))
                .add(physicalReference(acquisitionPhysical, "acquisition"))
                .add(physicalReference(hydrationPhysical, "physical_hydration"))
                .add(physicalReference(physicalEnvelope, "physical_envelope"))
                .add(physicalReference(domainPhysical, "opportunity_domain"))
                .add(physicalReference(envelopePhysical, "opportunity_envelope"))
                .add(physicalReference(v2HydrationPhysical, "opportunity_hydration"))
                .add(physicalReference(featurePhysical, "feature_source"))
                .add(physicalReference(predictorPhysical, "predictor_registry"))
                .add(physicalReference(precommitPhysical, "precommit"))
                .add(physicalReference(genePhysical, "gene_space"))
                .add(physicalReference(evaluatorPhysical, "evaluator_spec"))
                .add(physicalReference(configPhysical, "config"));
        ArrayNode outputs = array().add(reference(sourcePath, "source_bundle"))
                .add(reference(stagingPath, "separated_staging")).add(reference(parquetPath, "separated_parquet"));
        ObjectNode details = object().put("mode", "FEATURE_SOURCE_PLUS_PHYSICAL_HYDRATION_TO_SEPARATED_PARQUET")
                .put("source_bundle_sha256", text(sourceBundle, "content_sha256"))
                .put("feature_source_sha256", text(featureSource.path("manifest"), "content_sha256"))
                .put("staging_manifest_sha256", text(stagingManifest, "content_sha256"))
                .put("parquet_manifest_sha256", text(parquet, "content_sha256"))
                .put("feature_rows", parquet.path("artifacts").path("feature").path("row_count").asLong())
                .put("label_rows", parquet.path("artifacts").path("label").path("row_count").asLong())
                .put("execution_rows", parquet.path("artifacts").path("execution").path("row_count").asLong())
                .put("mark_rows", parquet.path("artifacts").path("mark").path("row_count").asLong());
        ObjectNode receipt = receipt("artifact-build", "COMPLETE", inputs, outputs,
                cloneArray(parquet.path("limitations")), details);
        Path receiptPath = writeDurableReceipt(receipt, options); ObjectNode result = object();
        result.set("source_bundle", sourceBundle); result.set("staging_manifest", stagingManifest);
        result.set("parquet_manifest", parquet); result.put("staging_root", stagingRoot.toString());
        result.put("parquet_root", parquetRoot.toString()); result.set("receipt", receipt);
        result.put("receipt_path", receiptPath.toString()); return result;
    }

    public static ObjectNode authoritativeResearchInit(ObjectNode options) {
        rejectLooseOptions(options, Set.of());
        for (String key : List.of("features", "feature_rows", "labels", "label_rows", "execution",
                "execution_rows", "episodes", "candidate_returns", "returns", "metrics", "hypothesis_family")) {
            if (options.has(key)) throw failure("research-init rejects caller-supplied " + key
                    + "; episodes, lineage, and family are derived from physical artifacts");
        }
        ObjectNode blocked = blockedPrerequisites("research-init", options, List.of(
                prerequisite("plan", "plan"), prerequisite("parquet_manifest", "Parquet manifest"),
                prerequisite("parquet_root", "Parquet root"), prerequisite("predictor_registry", "predictor registry"),
                prerequisite("evaluator_spec", "evaluator spec"), prerequisite("precommit", "precommit"),
                prerequisite("gene_space", "gene space"), prerequisite("timeframe_requirements", "timeframe requirements"),
                prerequisite("opportunity_domain", "opportunity domain"),
                prerequisite("opportunity_envelope", "opportunity envelope"),
                prerequisite("opportunity_hydration", "opportunity hydration"),
                prerequisite("hydration_root", "hydration root")));
        if (blocked != null) return blocked;
        Path parquetRoot = requirePhysicalDirectory(Path.of(text(options, "parquet_root")), "research-init Parquet root");
        Path hydrationRoot = requirePhysicalDirectory(Path.of(text(options, "hydration_root")), "research-init hydration root");
        PhysicalJson planPhysical = physicalJson(Path.of(text(options, "plan")), "research-init plan",
                Set.of(StrategyResearchDataV5.DATA_V5.get("plan")));
        PhysicalJson manifestPhysical = physicalJson(Path.of(text(options, "parquet_manifest")),
                "research-init separated Parquet manifest", Set.of(StrategyResearchDataV5.DATA_V5.get("artifacts")));
        PhysicalJson predictorPhysical = physicalJson(Path.of(text(options, "predictor_registry")),
                "research-init predictor registry", Set.of("strategy-v5-predictor-registry/1"));
        PhysicalJson evaluatorPhysical = physicalJson(Path.of(text(options, "evaluator_spec")),
                "research-init evaluator spec", Set.of("strategy-v5-evaluator-spec/1"));
        PhysicalJson precommitPhysical = frozenPrecommit(Path.of(text(options, "precommit")),
                "research-init precommit");
        PhysicalJson genePhysical = physicalJson(Path.of(text(options, "gene_space")),
                "research-init gene space", Set.of("strategy-gene-space/1"));
        PhysicalJson requirementsPhysical = physicalJson(Path.of(text(options, "timeframe_requirements")),
                "research-init timeframe requirements", Set.of("strategy-v5-timeframe-requirements/1"));
        PhysicalJson domainPhysical = physicalJson(Path.of(text(options, "opportunity_domain")),
                "research-init opportunity domain", Set.of("strategy-v5-opportunity-domain/1"));
        PhysicalJson envelopePhysical = physicalJson(Path.of(text(options, "opportunity_envelope")),
                "research-init opportunity envelope", Set.of("strategy-v5-opportunity-envelope/2"));
        PhysicalJson hydrationPhysical = physicalJson(Path.of(text(options, "opportunity_hydration")),
                "research-init opportunity hydration", Set.of("strategy-v5-opportunity-hydration/2"));
        ObjectNode plan = planPhysical.value(), manifest = manifestPhysical.value();
        ObjectNode evaluator = evaluatorPhysical.value(), precommit = precommitPhysical.value();
        ObjectNode specBindings = object(); specBindings.set("geneSpace", genePhysical.value());
        specBindings.set("predictorRegistry", predictorPhysical.value());
        StrategyEvaluatorV5.validateEvaluatorSpecV5(evaluator, specBindings);
        ObjectNode scopeOptions = object(); scopeOptions.set("candidateTemplate", evaluator.path("candidate_template"));
        StrategyResearchDataV5.derivePrecommitTradeScopeV5(precommit, scopeOptions);
        ObjectNode familyOptions = object(); familyOptions.set("evaluatorSpec", evaluator);
        String hypothesisFamily = canonicalHypothesisFamilyV5(precommit, familyOptions);
        if (!text(evaluator, "precommit_sha256").equals(text(precommit, "content_sha256"))
                || !text(manifest, "precommit_sha256").equals(text(precommit, "content_sha256"))) {
            throw failure("research-init precommit lineage differs across evaluator and Parquet manifest");
        }
        if (!text(manifest, "envelope_sha256").equals(text(envelopePhysical.value(), "content_sha256"))) {
            throw failure("research-init Parquet manifest is bound to a different v2 opportunity envelope");
        }
        ObjectNode envelope = envelopePhysical.value();
        if (!text(envelope, "plan_sha256").equals(text(plan, "content_sha256"))
                || !text(envelope, "precommit_sha256").equals(text(precommit, "content_sha256"))
                || !text(envelope, "evaluator_spec_sha256").equals(text(evaluator, "content_sha256"))
                || !text(envelope, "predictor_registry_sha256").equals(text(predictorPhysical.value(), "content_sha256"))
                || !text(envelope, "gene_space_sha256").equals(text(genePhysical.value(), "content_sha256"))) {
            throw failure("research-init v2 opportunity lineage differs from frozen inputs");
        }
        if (!text(requirementsPhysical.value(), "precommit_sha256").equals(text(precommit, "content_sha256"))
                || !text(requirementsPhysical.value(), "predictor_registry_sha256")
                .equals(text(predictorPhysical.value(), "content_sha256"))) {
            throw failure("research-init timeframe requirements differ from frozen strategy inputs");
        }
        ArrayNode predicates = array();
        for (JsonNode id : StrategyResearchDataV5.derivePredicatePredictorIds((ObjectNode) evaluator.path("predicate"))) {
            predicates.add(object().put("predictor_id", id.asText()));
        }
        ObjectNode verify = object().set("manifest", manifest); verify.put("root", parquetRoot.toString());
        verify.set("plan", plan); verify.set("predictorRegistry", predictorPhysical.value());
        verify.set("candidatePredicates", predicates);
        StrategyResearchDataV5.verifyParquetArtifactManifest(verify);
        verifyV2OpportunityHydration(domainPhysical.value(), envelope, hydrationPhysical.value(),
                hydrationRoot, text(plan, "content_sha256"));
        List<ObjectNode> features = readPhysicalParquetRoleRows(manifest, parquetRoot, "feature");
        List<ObjectNode> labels = readPhysicalParquetRoleRows(manifest, parquetRoot, "label");
        List<ObjectNode> executions = readPhysicalParquetRoleRows(manifest, parquetRoot, "execution");
        ArrayNode episodes = makeGenesisEpisodes(envelope, features, labels, executions);
        ExposureCustody custody = reopenOrAdvanceCanonicalExposureHead(hypothesisFamily,
                requireSha(text(manifest, "dataset_root_sha256"), "research-init dataset root SHA-256"));
        String requestedHead = firstText(options, "exposure_head_out", "exposure_head");
        if (!requestedHead.isEmpty() && !absolute(Path.of(requestedHead)).equals(custody.headPath())) {
            throw failure("research-init exposure HEAD path is not canonical for this family/dataset: "
                    + custody.headPath());
        }
        ObjectNode head = custody.head(); boolean genesis = head.path("cumulative_k").asLong() == 0
                && head.path("exposure_attempt_k").asLong() == 0;
        ObjectNode lineage = object().put("dataset_sha256", text(manifest, "dataset_root_sha256"))
                .put("candidate_set_sha256", text(envelope, "candidate_set_sha256"))
                .put("feature_set_sha256", text(manifest.path("artifacts").path("feature"), "sha256"))
                .put("label_set_sha256", text(manifest.path("artifacts").path("label"), "sha256"))
                .put("execution_set_sha256", text(manifest.path("artifacts").path("execution"), "sha256"));
        ObjectNode make = object(); make.set("lineage", lineage); make.set("candidates", array());
        make.set("episodes", episodes); make.set("exposureHead", head);
        make.set("metadata", genesis ? object().put("artifact_role", "GENESIS")
                : object().put("phase", "ROLLING_DATASET_INIT"));
        make.put("allowSubset", !genesis).put("genesis", genesis);
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(make);
        Path artifactPath = requestedOr(options, durableArtifactPath(options, artifact, "statistical-genesis"),
                "out", "artifact_out"); writeImmutable(artifactPath, artifact);
        ArrayNode inputs = array().add(physicalReference(planPhysical, "plan"))
                .add(physicalReference(manifestPhysical, "parquet_manifest"))
                .add(physicalReference(predictorPhysical, "predictor_registry"))
                .add(physicalReference(evaluatorPhysical, "evaluator_spec"))
                .add(physicalReference(precommitPhysical, "precommit"))
                .add(physicalReference(genePhysical, "gene_space"))
                .add(physicalReference(requirementsPhysical, "timeframe_requirements"))
                .add(physicalReference(domainPhysical, "opportunity_domain"))
                .add(physicalReference(envelopePhysical, "opportunity_envelope"))
                .add(physicalReference(hydrationPhysical, "opportunity_hydration"));
        ArrayNode outputs = array().add(reference(artifactPath, "statistical_artifact"))
                .add(reference(custody.headPath(), "exposure_head"));
        long eligible = rows(episodes).stream().filter(row -> row.path("eligible").asBoolean(false)).count();
        ObjectNode details = object().put("mode", genesis ? "AUTHORITATIVE_PHYSICAL_STATISTICAL_GENESIS"
                        : "AUTHORITATIVE_PHYSICAL_ROLLING_DATASET_INIT")
                .put("statistical_artifact_sha256", text(artifact, "content_sha256"))
                .put("exposure_head_sha256", text(head, "content_sha256"))
                .put("dataset_root_sha256", text(manifest, "dataset_root_sha256"))
                .put("hypothesis_family", hypothesisFamily).put("episode_count", episodes.size())
                .put("eligible_episode_count", eligible);
        ObjectNode receipt = receipt("research-init", "COMPLETE", inputs, outputs, array(), details);
        Path receiptPath = writeDurableReceipt(receipt, options); ObjectNode result = object();
        result.set("artifact", artifact); result.set("exposure_head", head);
        result.put("artifact_path", artifactPath.toString()); result.put("exposure_head_path", custody.headPath().toString());
        result.set("receipt", receipt); result.put("receipt_path", receiptPath.toString()); return result;
    }

    public static ObjectNode authoritativeExperimentFreeze(ObjectNode options) {
        rejectLooseOptions(options, Set.of("experiment_policy"));
        rejectExperimentLineageOverrides(options);
        ObjectNode effective = options.deepCopy();
        String candidateSource = firstText(options, "opportunity_envelope", "envelope", "candidates", "candidate_set");
        if (!candidateSource.isEmpty()) effective.put("candidate_source", candidateSource);
        ObjectNode blocked = blockedPrerequisites("experiment-freeze", effective, List.of(
                prerequisite("precommit", "precommit"),
                prerequisite("definition", "strategy definition"),
                prerequisite("candidate_source", "opportunity envelope or candidate set"),
                prerequisite("parquet_manifest", "separated Parquet manifest"),
                prerequisite("evaluator_spec", "evaluator spec"),
                prerequisite("metadata", "execution metadata bundle"),
                prerequisite("experiment_policy", "frozen experiment policy")));
        if (blocked != null) return blocked;

        PhysicalJson precommitPhysical = frozenPrecommit(Path.of(text(options, "precommit")),
                "experiment-freeze precommit");
        PhysicalJson definitionPhysical = physicalJson(Path.of(text(options, "definition")),
                "experiment-freeze strategy definition", Set.of("strategy-definition/2"));
        PhysicalJson manifestPhysical = physicalJson(Path.of(text(options, "parquet_manifest")),
                "experiment-freeze separated Parquet manifest",
                Set.of(StrategyResearchDataV5.DATA_V5.get("artifacts")));
        PhysicalJson evaluatorPhysical = physicalJson(Path.of(text(options, "evaluator_spec")),
                "experiment-freeze evaluator spec", Set.of("strategy-v5-evaluator-spec/1"));
        String metadataRootText = firstText(options, "metadata_root", "metadata_source_root");
        PhysicalMetadataBundle metadataPhysical = physicalMetadataBundle(Path.of(text(options, "metadata")),
                metadataRootText.isEmpty() ? null : Path.of(metadataRootText));
        PhysicalJson policyPhysical = physicalJson(Path.of(text(options, "experiment_policy")),
                "experiment-freeze policy", Set.of("strategy-v5-experiment-policy/1"));
        ObjectNode precommit = precommitPhysical.value();
        ObjectNode definition = definitionPhysical.value();
        ObjectNode manifest = manifestPhysical.value();
        ObjectNode evaluator = evaluatorPhysical.value();
        ObjectNode policy = policyPhysical.value();

        LegacyResearchV2.validateDefinitionV2(definition, precommit);
        StrategyEvaluatorV5.validateEvaluatorSpecV5(evaluator);
        ObjectNode familyOptions = object(); familyOptions.set("definition", definition);
        familyOptions.set("evaluatorSpec", evaluator); canonicalHypothesisFamilyV5(precommit, familyOptions);
        LegacyResearchV3.validateAcceptanceContract(policy.path("acceptance_contract"));
        validateExperimentPolicyWindow(policy);
        if (!text(definition, "stage").equals(text(policy, "stage"))
                || !text(precommit, "stage").equals(text(policy, "stage"))) {
            throw failure("experiment policy stage differs from the physical precommit or definition");
        }
        if (!stable(definition.path("tradable_instrument_contract"))
                .equals(stable(precommit.path("tradable_instrument_contract")))) {
            throw failure("experiment-freeze definition trade contract differs from the precommit");
        }
        if (!text(evaluator, "precommit_sha256").equals(text(precommit, "content_sha256"))
                || !text(manifest, "precommit_sha256").equals(text(precommit, "content_sha256"))) {
            throw failure("experiment-freeze precommit lineage differs across evaluator and Parquet manifest");
        }
        if (!"AUTHORITATIVE".equals(text(manifest, "storage_role"))) {
            throw failure("experiment-freeze requires an authoritative separated Parquet manifest");
        }
        ObjectNode planIdentity = object().put("content_sha256", text(manifest, "plan_sha256"));
        validateMetadataLineage(metadataPhysical.value(), evaluator, planIdentity, true);
        String featureSha = requireSha(text(manifest.path("artifacts").path("feature"), "sha256"),
                "Parquet feature artifact SHA-256");
        String labelSha = requireSha(text(manifest.path("artifacts").path("label"), "sha256"),
                "Parquet label artifact SHA-256");
        for (String key : List.of("maximum_drawdown_r", "maximum_cost_r")) {
            double value = number(policy.path("acceptance_contract").path("gates").get(key));
            if (!Double.isFinite(value) || value < 0) {
                throw failure("experiment-freeze acceptance must freeze a non-negative " + key);
            }
        }

        ObjectNode scopeOptions = object(); scopeOptions.set("candidateTemplate", evaluator.path("candidate_template"));
        ObjectNode scope = StrategyResearchDataV5.derivePrecommitTradeScopeV5(precommit, scopeOptions);
        String candidateSha = null; ArrayNode candidateInputs = array();
        String envelopePathText = firstText(options, "opportunity_envelope", "envelope");
        String candidatePathText = firstText(options, "candidates", "candidate_set");
        if (!envelopePathText.isEmpty()) {
            PhysicalJson envelopePhysical = physicalJson(Path.of(envelopePathText),
                    "experiment-freeze opportunity envelope", Set.of("strategy-v5-opportunity-envelope/2"));
            ObjectNode envelope = envelopePhysical.value(); OpportunityV5.validateOpportunityEnvelopeV5(envelope);
            if (envelope.path("fixture_only").asBoolean(true)
                    || !"AUTHORITATIVE".equals(text(envelope, "provenance"))) {
                throw failure("experiment-freeze requires an authoritative non-fixture opportunity envelope");
            }
            if (!text(envelope, "precommit_sha256").equals(text(precommit, "content_sha256"))
                    || !text(envelope, "evaluator_spec_sha256").equals(text(evaluator, "content_sha256"))
                    || !text(manifest, "envelope_sha256").equals(text(envelope, "content_sha256"))) {
                throw failure("experiment-freeze opportunity/data lineage differs from the frozen inputs");
            }
            List<String> assets = exactUniqueStrings(map(envelope.path("assets"), row -> lower(row.asText().trim())),
                    "experiment-freeze envelope assets");
            List<String> instruments = exactUniqueStrings(map(envelope.path("instruments"), row ->
                    normalizedProductionInstrument(row.asText(), "experiment-freeze envelope instrument")),
                    "experiment-freeze envelope instruments");
            assertExactSet(assets, sortedTexts(scope.path("trade_assets"), false),
                    "experiment-freeze envelope assets");
            assertExactSet(instruments, List.of(text(scope, "instrument")),
                    "experiment-freeze envelope instruments");
            candidateSha = requireSha(text(envelope, "candidate_set_sha256"),
                    "opportunity candidate_set_sha256");
            candidateInputs.add(physicalReference(envelopePhysical, "opportunity_envelope"));
        }
        if (!candidatePathText.isEmpty()) {
            PhysicalJson candidatePhysical = physicalJson(Path.of(candidatePathText),
                    "experiment-freeze candidate set", Set.of("strategy-candidate-set/5"));
            String explicit = validateExplicitCandidateSetForExperimentFreeze(candidatePhysical.value(), precommit, evaluator);
            if (candidateSha != null && !candidateSha.equals(explicit)) {
                throw failure("explicit candidate set differs from the opportunity envelope candidate set");
            }
            candidateSha = explicit; candidateInputs.add(physicalReference(candidatePhysical, "candidate_set"));
        }
        candidateSha = requireSha(candidateSha, "experiment-freeze candidate-set SHA-256");

        ObjectNode executorOptions = object(); executorOptions.set("evaluatorSpec", evaluator);
        executorOptions.set("manifest", manifest); executorOptions.put("metadataBundleSha256", metadataPhysical.contentSha256());
        ObjectNode executor = makeAuthoritativeExecutorIdentityV5(executorOptions);
        ArrayNode requiredAssets = array();
        for (String asset : sortedTexts(scope.path("trade_assets"), false)) {
            requiredAssets.add(object().put("asset", asset).put("asset_class", "crypto")
                    .put("instrument", text(scope, "instrument")));
        }
        ObjectNode make = object();
        make.set("experimentId", policy.get("experiment_id")); make.set("createdAt", policy.get("created_at"));
        make.set("stage", policy.get("stage")); make.set("evidencePhase", policy.get("evidence_phase"));
        make.put("precommitSha256", text(precommit, "content_sha256"));
        make.put("definitionSha256", text(definition, "content_sha256"));
        make.put("candidateSetSha256", candidateSha); make.put("dataManifestSha256", text(manifest, "content_sha256"));
        make.put("featureSetSha256", featureSha); make.put("labelSetSha256", labelSha);
        make.put("executorSha256", text(executor, "content_sha256"));
        make.set("acceptanceContract", policy.path("acceptance_contract")); make.set("requiredAssets", requiredAssets);
        make.set("chronology", policy.path("chronology")); make.set("portfolioPolicy", policy.path("portfolio_policy"));
        make.set("trainingSelectionPolicy", policy.path("training_selection_policy"));
        ObjectNode experiment = LegacyResearchV3.makeExperimentV3(make);
        LegacyResearchV3.validateExperimentV3(experiment, policy.path("acceptance_contract"), requiredAssets);
        SCHEMAS.validateKnownContractSchema(experiment);
        Path experimentPath = requestedOr(options, durableArtifactPath(options, experiment, "experiment"), "out");
        writeImmutable(experimentPath, experiment);
        ArrayNode inputs = array().add(physicalReference(precommitPhysical, "precommit"))
                .add(physicalReference(definitionPhysical, "strategy_definition"));
        candidateInputs.forEach(inputs::add);
        inputs.add(physicalReference(manifestPhysical, "parquet_manifest"));
        inputs.add(physicalReference(evaluatorPhysical, "evaluator_spec"));
        inputs.add(metadataReference(metadataPhysical, "metadata"));
        inputs.add(physicalReference(policyPhysical, "experiment_policy"));
        ArrayNode outputs = array().add(reference(experimentPath, "experiment"));
        ObjectNode details = object().put("mode", "DETERMINISTIC_PHYSICAL_LINEAGE_FREEZE")
                .put("evaluator_manifest_sha256", text(manifest, "content_sha256"))
                .put("evaluator_spec_sha256", text(evaluator, "content_sha256"))
                .put("metadata_bundle_sha256", metadataPhysical.contentSha256())
                .put("executor_identity_sha256", text(executor, "content_sha256"))
                .put("definition_sha256", text(definition, "content_sha256"))
                .put("experiment_sha256", text(experiment, "content_sha256"));
        if (envelopePathText.isEmpty()) details.putNull("envelope_sha256");
        else details.put("envelope_sha256", text(manifest, "envelope_sha256"));
        ObjectNode receipt = receipt("experiment-freeze", "COMPLETE", inputs, outputs, array(), details);
        Path receiptPath = writeDurableReceipt(receipt, options);
        ObjectNode result = object(); result.set("experiment", experiment); result.set("executor_identity", executor);
        result.put("path", experimentPath.toString()); result.set("receipt", receipt);
        result.put("receipt_path", receiptPath.toString()); return result;
    }

    public static ObjectNode authoritativeSearchGenetic(ObjectNode options) {
        rejectLooseOptions(options, Set.of("precommit", "behavior_registry", "behavior_definition_registry",
                "metadata_root", "metadata_source_root"));
        if ((defined(field(options, "features")) || defined(field(options, "labels"))
                || defined(field(options, "execution"))) && !defined(field(options, "artifact"))) {
            throw failure("strategy-research-next search-genetic is legacy fixture-only; authoritative search requires physical manifests and a frozen statistical artifact");
        }
        if (defined(field(options, "precommit"))) {
            frozenPrecommit(Path.of(text(options, "precommit")), "frozen precommit");
        }
        if (defined(field(options, "config"))) {
            throw failure("search-genetic uses the frozen authoritative genetic configuration; caller config overrides are rejected");
        }
        ObjectNode blocked = blockedPrerequisites("search-genetic", options, List.of(
                prerequisite("artifact", "statistical artifact"), prerequisite("exposure_head", "exposure head"),
                prerequisite("plan", "plan"), prerequisite("parquet_manifest", "Parquet manifest"),
                prerequisiteDirectory("parquet_root", "Parquet root"),
                prerequisite("predictor_registry", "predictor registry"),
                prerequisite("evaluator_spec", "evaluator spec"), prerequisite("experiment", "experiment"),
                prerequisite("precommit", "precommit"), prerequisite("gene_space", "gene space"),
                prerequisite("definition", "strategy definition"),
                prerequisite("timeframe_requirements", "timeframe requirements"),
                prerequisite("metadata", "metadata"), prerequisite("opportunity_domain", "opportunity domain"),
                prerequisite("envelope", "opportunity envelope"), prerequisite("hydration", "opportunity hydration"),
                prerequisiteDirectory("hydration_root", "hydration root"),
                prerequisiteTarget("checkpoint", "checkpoint", false),
                prerequisiteTarget("cache_root", "cache root", true)));
        if (blocked != null) return blocked;
        Path parquetRoot = requirePhysicalDirectory(Path.of(text(options, "parquet_root")),
                "authoritative search Parquet root");
        Path cacheRoot = requirePhysicalDirectory(Path.of(text(options, "cache_root")),
                "authoritative search cache root");
        Path checkpointPath = absolute(Path.of(text(options, "checkpoint")));
        PhysicalJson artifactPhysical = physicalJson(Path.of(text(options, "artifact")),
                "frozen statistical artifact", Set.of(StrategyStatisticalV5.STAT_SCHEMA.get("input")));
        ObjectNode artifact = artifactPhysical.value(); Path headPath = absolute(Path.of(text(options, "exposure_head")));
        ObjectNode head = StrategyStatisticalV5.readExposureHeadFile(headPath);
        ObjectNode artifactValidation = object(); artifactValidation.set("exposureHead", head);
        artifactValidation.put("allowSubset", true);
        StrategyStatisticalV5.validateStatisticalArtifactSet(artifact, artifactValidation);
        PhysicalJson planPhysical = physicalJson(Path.of(text(options, "plan")), "authoritative plan",
                Set.of(StrategyResearchDataV5.DATA_V5.get("plan")));
        PhysicalJson manifestPhysical = physicalJson(Path.of(text(options, "parquet_manifest")),
                "authoritative separated Parquet manifest", Set.of(StrategyResearchDataV5.DATA_V5.get("artifacts")));
        PhysicalJson predictorPhysical = physicalJson(Path.of(text(options, "predictor_registry")),
                "frozen predictor registry", Set.of("strategy-v5-predictor-registry/1"));
        PhysicalJson evaluatorPhysical = physicalJson(Path.of(text(options, "evaluator_spec")),
                "frozen evaluator spec", Set.of("strategy-v5-evaluator-spec/1"));
        PhysicalJson experimentPhysical = physicalJson(Path.of(text(options, "experiment")),
                "frozen experiment acceptance contract", Set.of("strategy-experiment/3"));
        assertFrozenExperiment(experimentPhysical.value(), "frozen experiment acceptance contract");
        PhysicalJson precommitPhysical = frozenPrecommit(Path.of(text(options, "precommit")), "frozen precommit");
        PhysicalJson definitionPhysical = physicalJson(Path.of(text(options, "definition")),
                "frozen strategy definition", Set.of("strategy-definition/2"));
        PhysicalJson genePhysical = physicalJson(Path.of(text(options, "gene_space")), "frozen gene space", Set.of());
        PhysicalJson requirementsPhysical = physicalJson(Path.of(text(options, "timeframe_requirements")),
                "frozen timeframe requirements", Set.of("strategy-v5-timeframe-requirements/1"));
        PhysicalJson domainPhysical = physicalJson(Path.of(text(options, "opportunity_domain")),
                "frozen opportunity domain", Set.of("strategy-v5-opportunity-domain/1"));
        PhysicalJson envelopePhysical = physicalJson(Path.of(text(options, "envelope")),
                "frozen v2 opportunity envelope", Set.of("strategy-v5-opportunity-envelope/2"));
        PhysicalJson hydrationPhysical = physicalJson(Path.of(text(options, "hydration")),
                "frozen v2 opportunity hydration", Set.of("strategy-v5-opportunity-hydration/2"));
        String metadataRootText = firstText(options, "metadata_root", "metadata_source_root");
        PhysicalMetadataBundle metadataPhysical = physicalMetadataBundle(Path.of(text(options, "metadata")),
                metadataRootText.isEmpty() ? null : Path.of(metadataRootText));
        ObjectNode plan = planPhysical.value(), manifest = manifestPhysical.value();
        ObjectNode evaluatorSpec = evaluatorPhysical.value(), precommit = precommitPhysical.value();
        ObjectNode envelope = envelopePhysical.value(); ObjectNode domain = domainPhysical.value();
        ObjectNode familyOptions = object(); familyOptions.set("definition", definitionPhysical.value());
        familyOptions.set("evaluatorSpec", evaluatorSpec); canonicalHypothesisFamilyV5(precommit, familyOptions);
        String family = text(evaluatorSpec, "strategy_family");
        Path canonicalHead = canonicalExposureHeadPath(family);
        if (!headPath.equals(canonicalHead)) {
            throw failure("authoritative search exposure HEAD path is not canonical for this family/dataset: "
                    + canonicalHead);
        }
        if (!text(requirementsPhysical.value(), "precommit_sha256").equals(text(precommit, "content_sha256"))
                || !text(requirementsPhysical.value(), "predictor_registry_sha256")
                .equals(text(predictorPhysical.value(), "content_sha256"))) {
            throw failure("search-genetic timeframe requirements differ from frozen strategy inputs");
        }
        V2HydrationCustody v2 = verifyV2OpportunityHydration(domain, envelope,
                hydrationPhysical.value(), Path.of(text(options, "hydration_root")), text(plan, "content_sha256"));
        if (!text(manifest, "envelope_sha256").equals(text(envelope, "content_sha256"))) {
            throw failure("authoritative search Parquet manifest is bound to a different v2 opportunity envelope");
        }
        if (!text(envelope, "candidate_set_sha256").equals(text(artifact.path("lineage"), "candidate_set_sha256"))) {
            throw failure("v2 opportunity envelope candidate-set lineage differs from the statistical artifact");
        }
        if (!text(envelope, "plan_sha256").equals(text(plan, "content_sha256"))
                || !text(envelope, "precommit_sha256").equals(text(precommit, "content_sha256"))
                || !text(envelope, "evaluator_spec_sha256").equals(text(evaluatorSpec, "content_sha256"))
                || !text(envelope, "predictor_registry_sha256").equals(text(predictorPhysical.value(), "content_sha256"))
                || !text(envelope, "gene_space_sha256").equals(text(genePhysical.value(), "content_sha256"))) {
            throw failure("v2 opportunity envelope lineage differs from search inputs");
        }
        ObjectNode specBindings = object(); specBindings.set("geneSpace", genePhysical.value());
        specBindings.set("predictorRegistry", predictorPhysical.value());
        StrategyEvaluatorV5.validateEvaluatorSpecV5(evaluatorSpec, specBindings);
        ObjectNode scopeOptions = object(); scopeOptions.set("candidateTemplate", evaluatorSpec.path("candidate_template"));
        StrategyResearchDataV5.derivePrecommitTradeScopeV5(precommit, scopeOptions);
        ArrayNode predicates = array();
        for (JsonNode id : StrategyResearchDataV5.derivePredicatePredictorIds((ObjectNode) evaluatorSpec.path("predicate"))) {
            predicates.add(object().put("predictor_id", id.asText()));
        }
        ObjectNode verify = object().set("manifest", manifest); verify.put("root", parquetRoot.toString());
        verify.set("plan", plan); verify.set("predictorRegistry", predictorPhysical.value());
        verify.set("candidatePredicates", predicates);
        StrategyResearchDataV5.verifyParquetArtifactManifest(verify);
        validateMetadataLineage(metadataPhysical.value(), evaluatorSpec, plan, true);
        if (!text(precommit, "content_sha256").equals(text(evaluatorSpec, "precommit_sha256"))
                || !text(manifest, "precommit_sha256").equals(text(evaluatorSpec, "precommit_sha256"))
                || !text(manifest, "dataset_root_sha256").equals(text(artifact.path("lineage"), "dataset_sha256"))) {
            throw failure("evaluator, manifest, precommit, and statistical dataset lineage differs");
        }
        ObjectNode production = object(); production.set("precommit", precommit);
        production.set("definition", definitionPhysical.value()); production.set("experiment", experimentPhysical.value());
        production.set("evaluatorSpec", evaluatorSpec); production.set("manifest", manifest);
        production.set("envelope", envelope); production.set("artifact", artifact);
        production.put("metadataBundleSha256", metadataPhysical.contentSha256());
        ObjectNode productionBindings = validateProductionResearchBindingsV5(production);
        ObjectNode envelopeByEpisode = exactEnvelopeByEpisode(envelope, artifact, manifest, parquetRoot);
        ObjectNode load = object(); load.set("evaluatorSpec", evaluatorSpec); load.set("geneSpace", genePhysical.value());
        load.set("predictorRegistry", predictorPhysical.value()); load.set("manifest", manifest);
        load.set("plan", plan); load.put("root", parquetRoot.toString()); load.set("metadata", metadataPhysical.value());
        if (!metadataRootText.isEmpty()) load.put("metadataRoot", absolute(Path.of(metadataRootText)).toString());
        load.set("envelopeByEpisode", envelopeByEpisode); load.set("opportunityEnvelope", envelope);
        load.set("executionHydration", hydrationPhysical.value()); load.set("executionPartitions", v2.partitions());
        load.put("executionHydrationRoot", v2.root().toString()); load.set("episodeIds", artifact.path("episodes").findValues("episode_id").isEmpty()
                ? array() : strings(rows(artifact.path("episodes")).stream().map(row -> text(row, "episode_id")).toList()));
        load.put("cacheRoot", cacheRoot.toString()).put("workerCount", options.path("workers").asInt(2))
                .put("timeoutMs", options.path("timeout_ms").asLong(120_000));
        StrategyEvaluatorV5.LoadedEvaluator loaded = StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(load);
        try {
            BehaviorRegistryPaths registryPaths = behaviorRegistryStatePaths(canonicalFamilyCustodyRoot(family));
            String requestedRegistry = firstText(options, "behavior_registry", "behavior_definition_registry");
            if (!requestedRegistry.isEmpty() && !absolute(Path.of(requestedRegistry)).equals(registryPaths.statePath())) {
                throw failure("authoritative search behavior registry path is not canonical for this family: "
                        + registryPaths.statePath());
            }
            ObjectNode durableRegistry = ensureBehaviorRegistryState(registryPaths, head);
            Map<String, ObjectNode> definitions = durableBehaviorMap(durableRegistry, head,
                    text(evaluatorSpec, "content_sha256"), text(precommit, "content_sha256"),
                    hash(evaluatorSpec.path("execution_contract")));
            Map<String, List<ObjectNode>> observedVectors = new LinkedHashMap<>();
            Map<String, ObjectNode> observedEvaluations = new LinkedHashMap<>();
            Map<String, ObjectNode> attempts = new LinkedHashMap<>();
            ObjectNode definitionContext = object().put("evaluator_sha256", text(evaluatorSpec, "content_sha256"))
                    .put("precommit_sha256", text(precommit, "content_sha256"))
                    .put("lifecycle_sha256", hash(evaluatorSpec.path("execution_contract")));
            StrategyEvaluatorV5.Evaluator adapted = adaptPhysicalEvaluator(loaded.evaluator(),
                    text(manifest, "content_sha256"), observedVectors, objects(artifact.path("episodes")),
                    observedEvaluations, text(artifact, "content_sha256"), artifact.path("lineage"),
                    definitions, definitionContext, attempts);
            if (options.has("training_cutoff")) {
                throw failure("search-genetic rejects caller-supplied training_cutoff; the physical experiment boundary is authoritative");
            }
            TrainingBoundary training = deriveFrozenTrainingBoundary(experimentPhysical.value(), plan);
            List<String> trainingIds = eligibleEpisodesAt(artifact.path("episodes"), training.at());
            if (trainingIds.isEmpty()) throw failure("authoritative training episode inventory is empty at the frozen cutoff");
            ObjectNode constraints = deriveFrozenHardConstraints(precommit, experimentPhysical.value());
            String foldId = firstTextOr(options, "GENETIC_TRAIN", "fold_id");
            ObjectNode checkpointOptions = object().put("checkpointPath", checkpointPath.toString());
            checkpointOptions.set("artifact", artifact); checkpointOptions.set("exposureHead", head);
            checkpointOptions.set("geneSpace", genePhysical.value()); checkpointOptions.put("foldId", foldId);
            checkpointOptions.set("config", geneticCheckpointConfig());
            ObjectNode resume = reopenAuthoritativeGeneticCheckpoint(checkpointOptions);
            ObjectNode config = geneticCheckpointConfig(); config.put("trainingCutoff", training.at());
            config.put("trainingPhase", training.phase());
            if (training.reservedTestStart() == null) config.putNull("reservedTestStart");
            else config.put("reservedTestStart", training.reservedTestStart());
            config.put("evaluatorSpecSha256", text(evaluatorSpec, "content_sha256"));
            config.put("precommitSha256", text(precommit, "content_sha256"));
            config.put("experimentSha256", text(experimentPhysical.value(), "content_sha256"));
            config.put("lifecycleSha256", hash(evaluatorSpec.path("execution_contract")));
            config.put("behaviorDefinitionRegistryPath", registryPaths.statePath().toString());
            config.put("behaviorDefinitionRegistryJournalPath", registryPaths.statePath() + ".journal.json");
            config.set("constraints", constraints);
            ObjectNode run = object(); run.set("artifact", artifact); run.set("geneSpace", genePhysical.value());
            run.set("trainingEpisodeIds", strings(trainingIds)); run.set("evaluator", NullNode.instance);
            run.set("exposureHead", head); run.put("exposureHeadPath", headPath.toString());
            run.put("checkpointPath", checkpointPath.toString()); if (resume != null) run.set("resumeCheckpoint", resume);
            run.put("mode", "AUTHORITATIVE").put("foldId", foldId); run.set("constraints", constraints); run.set("config", config);
            ObjectNode result = StrategyStatisticalV5.runGeneticSearchV5(run, adapted);
            assertExactTrainingInventory(result.path("run"), trainingIds, artifact, training.at());
            ArrayNode outputs = array();
            for (String[] output : List.of(new String[] {"out", "run", "genetic_run"},
                    new String[] {"exposure_out", "exposureHead", "exposure_head"},
                    new String[] {"candidate_out", "candidateSet", "candidate_set"})) {
                if (!result.path(output[1]).isObject()) continue; ObjectNode value = (ObjectNode) result.path(output[1]);
                Path path = firstText(options, output[0]).isEmpty() ? durableArtifactPath(options, value, output[2])
                        : absolute(Path.of(text(options, output[0]))); writeImmutable(path, value); outputs.add(reference(path, output[2]));
            }
            if (result.path("behaviorDefinitionRegistry").isObject()) {
                ObjectNode registry = (ObjectNode) result.path("behaviorDefinitionRegistry");
                Path snapshot = registryPaths.directory().resolve("registry-" + text(registry, "content_sha256") + ".json");
                writeImmutable(snapshot, registry); outputs.add(reference(snapshot, "behavior_definition_registry"));
            }
            ArrayNode inputs = array().add(physicalReference(artifactPhysical, "statistical_artifact"))
                    .add(physicalReference(planPhysical, "plan")).add(physicalReference(manifestPhysical, "parquet_manifest"))
                    .add(physicalReference(predictorPhysical, "predictor_registry"))
                    .add(physicalReference(evaluatorPhysical, "evaluator_spec"))
                    .add(physicalReference(precommitPhysical, "precommit"))
                    .add(physicalReference(definitionPhysical, "strategy_definition"))
                    .add(physicalReference(experimentPhysical, "experiment"))
                    .add(physicalReference(genePhysical, "gene_space"))
                    .add(metadataReference(metadataPhysical, "metadata")).add(reference(headPath, "exposure_head"))
                    .add(physicalReference(domainPhysical, "opportunity_domain"))
                    .add(physicalReference(envelopePhysical, "opportunity_envelope"))
                    .add(physicalReference(hydrationPhysical, "opportunity_hydration"));
            ObjectNode details = object().put("mode", "AUTHORITATIVE_EVALUATOR")
                    .put("evaluator_manifest_sha256", text(manifest, "content_sha256"))
                    .put("evaluator_spec_sha256", text(evaluatorSpec, "content_sha256"))
                    .put("executor_identity_sha256", text(productionBindings.path("executorIdentity"), "content_sha256"))
                    .put("definition_sha256", text(definitionPhysical.value(), "content_sha256"))
                    .put("experiment_sha256", text(experimentPhysical.value(), "content_sha256"))
                    .put("constraints_sha256", hash(constraints)).put("training_cutoff", training.at())
                    .put("training_phase", training.phase()).put("training_episode_ids_sha256", hash(strings(trainingIds)))
                    .put("exposure_head_sha256", text(result.path("exposureHead"), "content_sha256"))
                    .put("genetic_sha256", text(result.path("run"), "content_sha256"))
                    .put("opportunity_domain_sha256", text(domain, "content_sha256"))
                    .put("envelope_sha256", text(envelope, "content_sha256"))
                    .put("hydration_sha256", text(hydrationPhysical.value(), "content_sha256"))
                    .put("physical_partition_root_sha256", v2.descriptorRoot());
            if (training.reservedTestStart() == null) details.putNull("reserved_test_start");
            else details.put("reserved_test_start", training.reservedTestStart());
            ObjectNode receipt = receipt("search-genetic", "COMPLETE", inputs, outputs, array(), details);
            Path receiptPath = writeDurableReceipt(receipt, options); result.set("receipt", receipt);
            result.put("receipt_path", receiptPath.toString()); return result;
        } finally { loaded.close(); }
    }

    public static ObjectNode authoritativeResearchRun(ObjectNode options) {
        return authoritativeResearchRunInternal(options, null);
    }

    /**
     * Typed fixture seam for exercising the physical research publication boundary without
     * weakening the public command's fixed production WFO implementation.  JSON callers cannot
     * name or serialize this callback, and the explicit test-only guard is mandatory.
     */
    @FunctionalInterface
    interface PhysicalResearchWfoFixture {
        ObjectNode run(ObjectNode options, StrategyEvaluatorV5.Evaluator evaluator,
                       StrategyStatisticalV5.StatisticalProvider stressProvider,
                       StrategyStatisticalV5.StatisticalProvider portfolioProvider,
                       StrategyStatisticalV5.StatisticalProvider oosVectorProvider);
    }

    static ObjectNode authoritativeResearchRunFixture(boolean testOnly, ObjectNode options,
                                                       PhysicalResearchWfoFixture fixture) {
        if (!testOnly || fixture == null) {
            throw failure("physical research WFO fixture requires testOnly:true and a typed callback");
        }
        return authoritativeResearchRunInternal(options, fixture);
    }

    private static ObjectNode authoritativeResearchRunInternal(ObjectNode options,
                                                                PhysicalResearchWfoFixture fixture) {
        rejectLooseOptions(options, Set.of("mark_artifact", "mark_artifact_path", "portfolio_mark_artifact",
                "portfolio_policy", "portfolio_policy_path", "precommit", "behavior_registry",
                "behavior_definition_registry", "metadata_root", "metadata_source_root"));
        for (String key : List.of("config", "constraints", "acceptance", "thresholds",
                "selected_metrics", "null_controls")) if (options.has(key)) {
            throw failure("research-run " + key
                    + " must come from frozen physical artifacts, not caller options");
        }
        if (defined(field(options, "input"))) {
            PhysicalJson supplied = readJsonBytes(absolute(Path.of(text(options, "input"))), "research-run input");
            rejectLoose(supplied.value(), "input");
        }
        ObjectNode source = object();
        if (defined(field(options, "input"))) {
            source.setAll(readJsonBytes(absolute(Path.of(text(options, "input"))), "research-run input").value());
        }
        source.setAll(options);
        copyAlias(source, source, "plan", "data_plan"); copyAlias(source, source, "plan", "manifest");
        copyAlias(source, source, "parquet_manifest", "artifact_manifest");
        copyAlias(source, source, "parquet_manifest", "separated_manifest");
        copyAlias(source, source, "parquet_root", "dataset_root");
        copyAlias(source, source, "artifact", "statistical_artifact");
        copyAlias(source, source, "envelope", "opportunity_envelope");
        copyAlias(source, source, "hydration", "opportunity_hydration");
        copyAlias(source, source, "hydration_root", "opportunity_root");
        copyAlias(source, source, "hydration_root", "execution_hydration_root");
        copyAlias(source, source, "opportunity_domain", "domain");
        copyAlias(source, source, "metadata", "metadata_receipts");
        copyAlias(source, source, "exposure_head", "exposure_head_artifact");
        copyAlias(source, source, "checkpoint", "checkpoint_dir");
        copyAlias(source, source, "cache_root", "cache"); copyAlias(source, source, "cache_root", "cache_dir");
        copyAlias(source, source, "portfolio_policy", "portfolio_policy_path");
        copyAlias(source, source, "portfolio_mark_artifact", "mark_artifact");
        copyAlias(source, source, "portfolio_mark_artifact", "mark_artifact_path");
        ObjectNode blocked = blockedPrerequisites("research-run", source, List.of(
                prerequisite("plan", "plan"), prerequisite("parquet_manifest", "Parquet manifest"),
                prerequisiteDirectory("parquet_root", "Parquet root"),
                prerequisite("artifact", "statistical artifact"),
                prerequisite("evaluator_spec", "evaluator spec"), prerequisite("precommit", "precommit"),
                prerequisite("experiment", "experiment"), prerequisite("gene_space", "gene space"),
                prerequisite("definition", "strategy definition"),
                prerequisite("predictor_registry", "predictor registry"),
                prerequisite("timeframe_requirements", "timeframe requirements"),
                prerequisite("metadata", "metadata"), prerequisite("opportunity_domain", "opportunity domain"),
                prerequisite("envelope", "opportunity envelope"), prerequisite("hydration", "opportunity hydration"),
                prerequisiteDirectory("hydration_root", "hydration root"),
                prerequisite("exposure_head", "exposure head"),
                prerequisiteTarget("checkpoint", "checkpoint", false),
                prerequisiteTarget("cache_root", "cache root", true),
                prerequisite("portfolio_policy", "portfolio policy"),
                prerequisite("portfolio_mark_artifact", "portfolio mark artifact")));
        if (blocked != null) return blocked;

        Path parquetRoot = requirePhysicalDirectory(Path.of(text(source, "parquet_root")),
                "research-run Parquet root");
        Path hydrationRoot = requirePhysicalDirectory(Path.of(text(source, "hydration_root")),
                "research-run hydration root");
        Path cacheRoot = requirePhysicalDirectory(Path.of(text(source, "cache_root")),
                "research-run cache root");
        PhysicalJson planPhysical = physicalJson(Path.of(text(source, "plan")), "research-run plan",
                Set.of(StrategyResearchDataV5.DATA_V5.get("plan")));
        PhysicalJson manifestPhysical = physicalJson(Path.of(text(source, "parquet_manifest")),
                "research-run authoritative Parquet manifest",
                Set.of(StrategyResearchDataV5.DATA_V5.get("artifacts")));
        PhysicalJson artifactPhysical = physicalJson(Path.of(text(source, "artifact")),
                "research-run statistical artifact", Set.of(StrategyStatisticalV5.STAT_SCHEMA.get("input")));
        PhysicalJson evaluatorPhysical = physicalJson(Path.of(text(source, "evaluator_spec")),
                "research-run evaluator spec", Set.of("strategy-v5-evaluator-spec/1"));
        PhysicalJson precommitPhysical = frozenPrecommit(Path.of(text(source, "precommit")),
                "research-run physical precommit");
        PhysicalJson definitionPhysical = physicalJson(Path.of(text(source, "definition")),
                "research-run strategy definition", Set.of("strategy-definition/2"));
        PhysicalJson experimentPhysical = physicalJson(Path.of(text(source, "experiment")),
                "research-run physical experiment", Set.of("strategy-experiment/3"));
        assertFrozenExperiment(experimentPhysical.value(), "research-run physical experiment");
        PhysicalJson genePhysical = physicalJson(Path.of(text(source, "gene_space")),
                "research-run gene space", Set.of());
        PhysicalJson predictorPhysical = physicalJson(Path.of(text(source, "predictor_registry")),
                "research-run predictor registry", Set.of("strategy-v5-predictor-registry/1"));
        PhysicalJson requirementsPhysical = physicalJson(Path.of(text(source, "timeframe_requirements")),
                "research-run timeframe requirements", Set.of("strategy-v5-timeframe-requirements/1"));
        PhysicalJson domainPhysical = physicalJson(Path.of(text(source, "opportunity_domain")),
                "research-run opportunity domain", Set.of("strategy-v5-opportunity-domain/1"));
        PhysicalJson envelopePhysical = physicalJson(Path.of(text(source, "envelope")),
                "research-run opportunity envelope", Set.of("strategy-v5-opportunity-envelope/2"));
        PhysicalJson hydrationPhysical = physicalJson(Path.of(text(source, "hydration")),
                "research-run opportunity hydration", Set.of("strategy-v5-opportunity-hydration/2"));
        PhysicalJson portfolioPolicyPhysical = physicalJson(Path.of(text(source, "portfolio_policy")),
                "research-run portfolio policy", Set.of("strategy-portfolio-policy/2"));
        PhysicalJson markPhysical = physicalJson(Path.of(text(source, "portfolio_mark_artifact")),
                "research-run portfolio mark artifact", Set.of("strategy-mark-artifact/1"));
        String metadataRootText = firstText(source, "metadata_root", "metadata_source_root");
        PhysicalMetadataBundle metadataPhysical = physicalMetadataBundle(Path.of(text(source, "metadata")),
                metadataRootText.isEmpty() ? null : Path.of(metadataRootText));
        ObjectNode plan = planPhysical.value(), manifest = manifestPhysical.value();
        ObjectNode artifact = artifactPhysical.value(), evaluator = evaluatorPhysical.value();
        ObjectNode precommit = precommitPhysical.value(), experiment = experimentPhysical.value();
        ObjectNode envelope = envelopePhysical.value(), domain = domainPhysical.value();

        ObjectNode familyOptions = object(); familyOptions.set("definition", definitionPhysical.value());
        familyOptions.set("evaluatorSpec", evaluator);
        String family = canonicalHypothesisFamilyV5(precommit, familyOptions);
        Path headPath = absolute(Path.of(text(source, "exposure_head")));
        Path canonicalHead = canonicalExposureHeadPath(family);
        if (!headPath.equals(canonicalHead)) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: research-run exposure HEAD path is not canonical for this family/dataset: "
                    + canonicalHead);
        }
        ObjectNode head = StrategyStatisticalV5.readExposureHeadFile(headPath);
        StrategyStatisticalV5.validateExposureHead(head);
        ObjectNode migration = object().put("recordRoot", repositoryRoot().resolve("strategy-research/v5-records").toString())
                .put("family", family); migration.set("exposureHead", head);
        assertLegacyFamilyMigrationBoundary(migration);
        ObjectNode artifactValidation = object(); artifactValidation.set("exposureHead", head);
        artifactValidation.put("allowSubset", true);
        StrategyStatisticalV5.validateStatisticalArtifactSet(artifact, artifactValidation);
        ObjectNode specBindings = object(); specBindings.set("geneSpace", genePhysical.value());
        specBindings.set("predictorRegistry", predictorPhysical.value());
        StrategyEvaluatorV5.validateEvaluatorSpecV5(evaluator, specBindings);
        ObjectNode scopeOptions = object(); scopeOptions.set("candidateTemplate", evaluator.path("candidate_template"));
        StrategyResearchDataV5.derivePrecommitTradeScopeV5(precommit, scopeOptions);
        if (!text(requirementsPhysical.value(), "precommit_sha256").equals(text(precommit, "content_sha256"))
                || !text(requirementsPhysical.value(), "predictor_registry_sha256")
                .equals(text(predictorPhysical.value(), "content_sha256"))) {
            throw failure("research-run timeframe requirements differ from frozen strategy inputs");
        }
        ArrayNode predicates = array();
        for (JsonNode id : StrategyResearchDataV5.derivePredicatePredictorIds((ObjectNode) evaluator.path("predicate"))) {
            predicates.add(object().put("predictor_id", id.asText()));
        }
        ObjectNode verify = object().set("manifest", manifest); verify.put("root", parquetRoot.toString());
        verify.set("plan", plan); verify.set("predictorRegistry", predictorPhysical.value());
        verify.set("candidatePredicates", predicates);
        StrategyResearchDataV5.verifyParquetArtifactManifest(verify);
        validateMetadataLineage(metadataPhysical.value(), evaluator, plan, true);
        if (!text(precommit, "content_sha256").equals(text(evaluator, "precommit_sha256"))
                || !text(manifest, "precommit_sha256").equals(text(evaluator, "precommit_sha256"))
                || !text(manifest, "dataset_root_sha256").equals(text(artifact.path("lineage"), "dataset_sha256"))) {
            throw failure("research-run evaluator, manifest, precommit, and statistical dataset lineage differs");
        }
        V2HydrationCustody v2 = verifyV2OpportunityHydration(domain, envelope, hydrationPhysical.value(),
                hydrationRoot, text(plan, "content_sha256"));
        if (!text(manifest, "envelope_sha256").equals(text(envelope, "content_sha256"))) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: research-run Parquet manifest is bound to a different v2 opportunity envelope");
        }
        ObjectNode production = object(); production.set("precommit", precommit);
        production.set("definition", definitionPhysical.value()); production.set("experiment", experiment);
        production.set("evaluatorSpec", evaluator); production.set("manifest", manifest);
        production.set("envelope", envelope); production.set("artifact", artifact);
        production.put("metadataBundleSha256", metadataPhysical.contentSha256());
        ObjectNode productionBindings = validateProductionResearchBindingsV5(production);
        ObjectNode envelopeByEpisode = exactEnvelopeByEpisode(envelope, artifact, manifest, parquetRoot);
        List<ObjectNode> featureRows = readPhysicalParquetRoleRows(manifest, parquetRoot, "feature");
        List<ObjectNode> labelRows = readPhysicalParquetRoleRows(manifest, parquetRoot, "label");
        List<ObjectNode> executionRows = readPhysicalParquetRoleRows(manifest, parquetRoot, "execution");
        List<ObjectNode> roleMarkRows = readPhysicalParquetRoleRows(manifest, parquetRoot, "mark");
        ObjectNode exact = object(); exact.set("envelope", envelope); exact.set("artifact", artifact);
        ObjectNode roles = object(); roles.set("feature", array(featureRows)); roles.set("label", array(labelRows));
        roles.set("execution", array(executionRows)); exact.set("roleRows", roles);
        validateExactProductionEpisodeInventoriesV5(exact);
        validateAuthoritativePortfolioPolicy(portfolioPolicyPhysical.value());
        ObjectNode policy = portfolioPolicyPhysical.value();
        if (!text(policy, "precommit_sha256").equals(text(precommit, "content_sha256"))
                || !text(policy, "experiment_sha256").equals(text(experiment, "content_sha256"))) {
            throw failure("portfolio policy is bound to a different physical precommit or experiment");
        }
        JsonNode acceptance = experiment.path("acceptance_contract").isObject()
                ? experiment.path("acceptance_contract") : experiment.path("acceptance");
        if (!text(policy, "acceptance_sha256").equals(hash(acceptance))
                || !text(policy, "lifecycle_sha256").equals(hash(evaluator.path("execution_contract")))) {
            throw failure("portfolio policy acceptance/lifecycle lineage differs from the frozen experiment/evaluator");
        }
        ObjectNode mark = markPhysical.value();
        if (!"AUTHORITATIVE_RECOMPUTED".equals(text(mark, "provenance"))
                || !text(mark, "source_manifest_sha256").equals(manifestPhysical.byteSha256())) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio mark artifact is not bound to the physical Parquet manifest");
        }
        if (!firstText(mark, "source_manifest_path").isEmpty()) {
            Path sourceManifest = absolute(Path.of(text(mark, "source_manifest_path")));
            if (!manifestPhysical.byteSha256().equals(hash(readSinglyLinked(sourceManifest,
                    "portfolio mark source manifest")))) {
                throw failure("portfolio mark artifact source manifest is missing or tampered");
            }
        }
        List<ObjectNode> markRows = authoritativeResearchMarkRows(roleMarkRows, mark);
        ObjectNode constraints = deriveFrozenHardConstraints(precommit, experiment);
        ObjectNode assetScope = deriveFrozenAssetScope(artifact, precommit, experiment);
        ObjectNode stressContract = frozenStressContract(precommit, experiment, evaluator);
        String physicalBoundary = firstText(experiment.path("window"), "end_at");
        if (physicalBoundary.isEmpty()) physicalBoundary = firstText(experiment.path("boundary"), "end_at");
        if (physicalBoundary.isEmpty()) physicalBoundary = firstText(experiment.path("oos_boundary"), "end_at");
        if (physicalBoundary.isEmpty()) physicalBoundary = firstText(plan.path("window"), "end_at", "completed_through_at");
        long boundary = timestampOrMin(NODES.textNode(physicalBoundary));
        if (boundary == Long.MIN_VALUE) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: research-run lacks an exact physical plan/experiment boundary");
        }
        for (JsonNode episode : artifact.path("episodes")) if (timestampOrMin(field(episode, "decision_time")) > boundary) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: statistical artifact contains episodes beyond the frozen physical experiment boundary");
        }
        ObjectNode load = object(); load.set("evaluatorSpec", evaluator); load.set("geneSpace", genePhysical.value());
        load.set("predictorRegistry", predictorPhysical.value()); load.set("manifest", manifest); load.set("plan", plan);
        load.put("root", parquetRoot.toString()); load.set("metadata", metadataPhysical.value());
        if (!metadataRootText.isEmpty()) load.put("metadataRoot", absolute(Path.of(metadataRootText)).toString());
        load.set("envelopeByEpisode", envelopeByEpisode); load.set("opportunityEnvelope", envelope);
        load.set("executionHydration", hydrationPhysical.value()); load.set("executionPartitions", v2.partitions());
        load.put("executionHydrationRoot", v2.root().toString());
        load.set("episodeIds", strings(rows(artifact.path("episodes")).stream()
                .map(row -> text(row, "episode_id")).toList()));
        load.put("cacheRoot", cacheRoot.toString()).put("workerCount", source.path("workers").asInt(2))
                .put("timeoutMs", source.path("timeout_ms").asLong(120_000));
        StrategyEvaluatorV5.LoadedEvaluator loaded = StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(load);
        try {
            if (!StrategyEvaluatorV5.isVerifiedPhysicalEvaluator(loaded.evaluator())) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: research-run evaluator loader did not return verified physical custody");
            }
            ResearchRunInputs runInputs = new ResearchRunInputs(source, planPhysical, manifestPhysical,
                    artifactPhysical, evaluatorPhysical, precommitPhysical, definitionPhysical,
                    experimentPhysical, genePhysical, predictorPhysical, requirementsPhysical,
                    metadataPhysical, domainPhysical, envelopePhysical, hydrationPhysical,
                    portfolioPolicyPhysical, markPhysical, parquetRoot, hydrationRoot, cacheRoot,
                    headPath, head, family, v2, productionBindings, envelopeByEpisode, featureRows,
                    labelRows, executionRows, markRows, constraints, assetScope, stressContract,
                    ISO_MILLIS.format(Instant.ofEpochMilli(boundary)), loaded);
            return completePhysicalResearchRun(runInputs, fixture);
        } catch (RuntimeException error) {
            String reason = String.valueOf(error.getMessage());
            if (!reason.startsWith("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE:")) throw error;
            ObjectNode blockedRun = rejectedResearchRun(plan, manifest, envelope, domain,
                    hydrationPhysical.value(), v2.descriptorRoot(), artifact, reason,
                    text(evaluator, "content_sha256"), null, null, null, null, null, null);
            Path runPath = requestedOr(source, durableArtifactPath(source,
                    (ObjectNode) blockedRun.path("run"), "research-run"), "out");
            writeImmutable(runPath, blockedRun.path("run"));
            ArrayNode inputs = researchRunPhysicalReferences(planPhysical, manifestPhysical, artifactPhysical,
                    evaluatorPhysical, precommitPhysical, definitionPhysical, experimentPhysical,
                    genePhysical, predictorPhysical, requirementsPhysical, metadataPhysical,
                    domainPhysical, envelopePhysical, hydrationPhysical, headPath,
                    portfolioPolicyPhysical, markPhysical);
            ObjectNode details = object().put("mode", "FAIL_CLOSED_RECOMPUTATION")
                    .put("reason", reason).put("active", false);
            ObjectNode receipt = receipt("research-run", "BLOCKED", inputs,
                    array().add(reference(runPath, "research_run")), strings(List.of(reason)), details);
            Path receiptPath = writeDurableReceipt(receipt, source);
            blockedRun.put("status", "BLOCKED"); blockedRun.set("receipt", receipt);
            blockedRun.put("receipt_path", receiptPath.toString()); return blockedRun;
        } finally { loaded.close(); }
    }

    private record ResearchRunInputs(
            ObjectNode source,
            PhysicalJson planPhysical,
            PhysicalJson manifestPhysical,
            PhysicalJson artifactPhysical,
            PhysicalJson evaluatorPhysical,
            PhysicalJson precommitPhysical,
            PhysicalJson definitionPhysical,
            PhysicalJson experimentPhysical,
            PhysicalJson genePhysical,
            PhysicalJson predictorPhysical,
            PhysicalJson requirementsPhysical,
            PhysicalMetadataBundle metadataPhysical,
            PhysicalJson domainPhysical,
            PhysicalJson envelopePhysical,
            PhysicalJson hydrationPhysical,
            PhysicalJson portfolioPolicyPhysical,
            PhysicalJson markPhysical,
            Path parquetRoot,
            Path hydrationRoot,
            Path cacheRoot,
            Path headPath,
            ObjectNode head,
            String family,
            V2HydrationCustody hydrationCustody,
            ObjectNode productionBindings,
            ObjectNode envelopeByEpisode,
            List<ObjectNode> featureRows,
            List<ObjectNode> labelRows,
            List<ObjectNode> executionRows,
            List<ObjectNode> markRows,
            ObjectNode constraints,
            ObjectNode assetScope,
            ObjectNode stressContract,
            String boundary,
            StrategyEvaluatorV5.LoadedEvaluator loaded) {}

    private record PhysicalOutcomeRow(String episodeId, String asset, boolean traded, double netR,
                                      ObjectNode outcome, ObjectNode feature, ObjectNode label,
                                      ObjectNode execution) {}

    private record PersistedResearchArtifact(ObjectNode value, Path path, String byteSha256) {}

    private record ResearchStageArtifacts(
            Map<String, PersistedResearchArtifact> outputs,
            List<ObjectNode> selected,
            boolean marksBound,
            String fundingStatus) {}

    private static ArrayNode researchRunPhysicalReferences(
            PhysicalJson plan, PhysicalJson manifest, PhysicalJson artifact, PhysicalJson evaluator,
            PhysicalJson precommit, PhysicalJson definition, PhysicalJson experiment, PhysicalJson gene,
            PhysicalJson predictor, PhysicalJson requirements, PhysicalMetadataBundle metadata,
            PhysicalJson domain, PhysicalJson envelope, PhysicalJson hydration, Path headPath,
            PhysicalJson portfolioPolicy, PhysicalJson mark) {
        return array().add(physicalReference(plan, "plan"))
                .add(physicalReference(manifest, "parquet_manifest"))
                .add(physicalReference(artifact, "statistical_artifact"))
                .add(physicalReference(evaluator, "evaluator_spec"))
                .add(physicalReference(precommit, "precommit"))
                .add(physicalReference(definition, "strategy_definition"))
                .add(physicalReference(experiment, "experiment"))
                .add(physicalReference(gene, "gene_space"))
                .add(physicalReference(predictor, "predictor_registry"))
                .add(physicalReference(requirements, "timeframe_requirements"))
                .add(metadataReference(metadata, "metadata"))
                .add(physicalReference(domain, "opportunity_domain"))
                .add(physicalReference(envelope, "opportunity_envelope"))
                .add(physicalReference(hydration, "opportunity_hydration"))
                .add(reference(headPath, "exposure_head"))
                .add(physicalReference(portfolioPolicy, "portfolio_policy"))
                .add(physicalReference(mark, "portfolio_mark_artifact"));
    }

    private static ObjectNode completePhysicalResearchRun(ResearchRunInputs in,
                                                           PhysicalResearchWfoFixture fixture) {
        ObjectNode source = in.source(), plan = in.planPhysical().value();
        ObjectNode manifest = in.manifestPhysical().value(), artifact = in.artifactPhysical().value();
        ObjectNode evaluatorSpec = in.evaluatorPhysical().value();
        ObjectNode precommit = in.precommitPhysical().value(), experiment = in.experimentPhysical().value();
        BehaviorRegistryPaths registryPaths = behaviorRegistryStatePaths(canonicalFamilyCustodyRoot(in.family()));
        String requestedRegistry = firstText(source, "behavior_registry", "behavior_definition_registry");
        if (!requestedRegistry.isEmpty() && !absolute(Path.of(requestedRegistry)).equals(registryPaths.statePath())) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: research-run behavior registry path is not canonical for this family: "
                    + registryPaths.statePath());
        }
        ObjectNode durableRegistry = ensureBehaviorRegistryState(registryPaths, in.head());
        String evaluatorSha = text(evaluatorSpec, "content_sha256");
        String precommitSha = text(precommit, "content_sha256");
        String lifecycleSha = hash(evaluatorSpec.path("execution_contract"));
        Map<String, ObjectNode> definitions = durableBehaviorMap(durableRegistry, in.head(),
                evaluatorSha, precommitSha, lifecycleSha);
        Map<String, List<ObjectNode>> observedVectors = new LinkedHashMap<>();
        Map<String, ObjectNode> observedEvaluations = new LinkedHashMap<>();
        Map<String, ObjectNode> observedAttempts = new LinkedHashMap<>();
        ObjectNode definitionContext = object().put("evaluator_sha256", evaluatorSha)
                .put("precommit_sha256", precommitSha).put("lifecycle_sha256", lifecycleSha);
        StrategyEvaluatorV5.Evaluator adapted = adaptPhysicalEvaluator(in.loaded().evaluator(),
                text(manifest, "content_sha256"), observedVectors, objects(artifact.path("episodes")),
                observedEvaluations, text(artifact, "content_sha256"), artifact.path("lineage"),
                definitions, definitionContext, observedAttempts);

        Map<String, ObjectNode> featureByEpisode = uniquePhysicalRoleMap(in.featureRows(), "feature");
        Map<String, ObjectNode> labelByEpisode = uniquePhysicalRoleMap(in.labelRows(), "label");
        Map<String, ObjectNode> executionByEpisode = uniquePhysicalRoleMap(in.executionRows(), "execution");
        Function<String, ObjectNode> resolveExecution = v2ExecutionResolver(in, executionByEpisode);
        Path stageRoot = recordRoot(source).resolve("stages");
        createDirectoryCustody(stageRoot);
        Map<String, PersistedResearchArtifact> stressExecutions = new LinkedHashMap<>();
        Map<String, PersistedResearchArtifact> portfolioRisks = new LinkedHashMap<>();

        StrategyStatisticalV5.StatisticalProvider stressProvider = args -> researchStressDecision(
                args, in, definitions, featureByEpisode, labelByEpisode, resolveExecution,
                stressExecutions, stageRoot);
        StrategyStatisticalV5.StatisticalProvider portfolioProvider = args -> researchPortfolioDecision(
                args, in, observedEvaluations, observedVectors, featureByEpisode, labelByEpisode,
                resolveExecution, portfolioRisks, stageRoot);
        StrategyStatisticalV5.StatisticalProvider oosVectorProvider = args -> researchOosVector(
                args, artifact, adapted, definitions, observedVectors);

        ObjectNode nullOptions = object(); nullOptions.set("roleManifest", manifest);
        nullOptions.set("exposureHead", in.head()); nullOptions.set("geneSpace", in.genePhysical().value());
        nullOptions.set("behaviorDefinitions", array(definitions.values()));
        nullOptions.set("selectionConstraints", in.constraints()); nullOptions.put("selectionEndAt", in.boundary());
        nullOptions.set("assetScope", in.assetScope()); nullOptions.put("physicalNullRoot", in.cacheRoot().toString());
        StrategyStatisticalV5.PhysicalNullRunner nullRunner = StrategyStatisticalV5.makePhysicalNullRunnerV5(
                nullOptions, in.loaded().evaluator());

        Path checkpoint = absolute(Path.of(text(source, "checkpoint")));
        Path checkpointDirectory = checkpoint.getParent() == null ? checkpoint : checkpoint.getParent();
        ObjectNode config = geneticCheckpointConfig(); config.put("trainingCutoff", in.boundary());
        config.put("evaluatorSpecSha256", evaluatorSha).put("precommitSha256", precommitSha)
                .put("lifecycleSha256", lifecycleSha);
        config.set("constraints", in.constraints()); config.set("assetScope", in.assetScope());
        config.put("checkpointDirectory", checkpointDirectory.toString());
        config.put("exposureHeadPath", in.headPath().toString()); config.put("prospectiveCutoff", in.boundary());
        config.put("behaviorDefinitionRegistryPath", registryPaths.statePath().toString());
        config.put("behaviorDefinitionRegistryJournalPath", registryPaths.statePath() + ".journal.json");
        config.set("behaviorDefinitionContext", definitionContext); config.put("nullIterations", 128);
        config.put("nullSequentialBatchSize", 8); config.set("selectionBudget", config.deepCopy());
        config.set("nullSourceArtifact", artifact);
        ObjectNode wfoOptions = object(); wfoOptions.set("artifact", artifact);
        wfoOptions.set("geneSpace", in.genePhysical().value()); wfoOptions.set("exposureHead", in.head());
        wfoOptions.set("config", config); wfoOptions.put("mode", "AUTHORITATIVE");
        wfoOptions.put("endAt", in.boundary());
        ObjectNode wfo = fixture == null
                ? StrategyStatisticalV5.runNestedWfoV5(wfoOptions, adapted, stressProvider,
                portfolioProvider, oosVectorProvider, null, null, nullRunner)
                : fixture.run(wfoOptions.deepCopy(), adapted, stressProvider, portfolioProvider,
                oosVectorProvider);
        if (!wfo.path("run").isObject() || !text(wfo.path("run"), "content_sha256")
                .equals(ownHash(wfo.path("run")))) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: nested WFO did not return a hash-bound run");
        }

        ResearchStageArtifacts stages = deriveResearchStageArtifacts(in, wfo, resolveExecution,
                stressExecutions, portfolioRisks, stageRoot);
        ObjectNode run = makeCompletedResearchRun(in, wfo, observedVectors, observedEvaluations,
                observedAttempts, stages);
        Path wfoPath = firstText(source, "wfo_out", "wfo").isEmpty()
                ? durableArtifactPath(source, (ObjectNode) wfo.path("run"), "wfo")
                : absolute(Path.of(firstText(source, "wfo_out", "wfo")));
        Path runPath = requestedOr(source, durableArtifactPath(source, run, "research-run"), "out");
        writeImmutable(wfoPath, wfo.path("run")); writeImmutable(runPath, run);
        ArrayNode inputs = researchRunPhysicalReferences(in.planPhysical(), in.manifestPhysical(),
                in.artifactPhysical(), in.evaluatorPhysical(), in.precommitPhysical(),
                in.definitionPhysical(), in.experimentPhysical(), in.genePhysical(),
                in.predictorPhysical(), in.requirementsPhysical(), in.metadataPhysical(),
                in.domainPhysical(), in.envelopePhysical(), in.hydrationPhysical(), in.headPath(),
                in.portfolioPolicyPhysical(), in.markPhysical());
        ArrayNode outputs = array().add(reference(wfoPath, "wfo")).add(reference(runPath, "research_run"));
        for (String role : List.of("final_oos_artifact", "final_oos_vector_inventory")) {
            PersistedResearchArtifact stage = stages.outputs().get(role);
            if (stage != null) outputs.add(reference(stage.path(), role));
        }
        boolean shadow = "SHADOW".equals(text(run, "decision"));
        String limitation = shadow
                ? "SHADOW_ONLY: authoritative recomputation completed; activation is unavailable at this command boundary"
                : "AUTHORITATIVE_RECOMPUTATION_COMPLETED_BUT_GATES_REJECTED";
        ObjectNode boundHashes = object()
                .put("evaluator_sha256", text(evaluatorSpec, "content_sha256"))
                .put("data_sha256", text(manifest, "content_sha256"))
                .put("plan_sha256", text(plan, "content_sha256"))
                .put("wfo_sha256", text(wfo.path("run"), "content_sha256"))
                .put("genetic_sha256", text(stages.outputs().get("genetic").value(), "content_sha256"))
                .put("selected_fills_sha256", text(stages.outputs().get("execution_fills").value(), "content_sha256"))
                .put("stress_sha256", text(stages.outputs().get("stresses").value(), "content_sha256"))
                .put("portfolio_sha256", text(stages.outputs().get("portfolio").value(), "content_sha256"));
        ObjectNode details = object().put("mode", "AUTHORITATIVE_PHYSICAL_RECOMPUTATION")
                .put("executor_identity_sha256", text(in.productionBindings().path("executorIdentity"), "content_sha256"))
                .put("definition_sha256", text(in.definitionPhysical().value(), "content_sha256"))
                .putNull("publication_transaction_path").put("active", false);
        details.set("pipeline", strings(PIPELINE_V5));
        details.set("bound_hashes", boundHashes);
        details.set("publication_artifacts", array().add("wfo").add("research_run")
                .add("final_oos_artifact").add("final_oos_vector_inventory"));
        ObjectNode receipt = receipt("research-run", shadow ? "COMPLETE" : "REJECTED", inputs,
                outputs, strings(List.of(limitation)), details);
        Path receiptPath = writeDurableReceipt(receipt, source);
        ObjectNode result = object().put("status", shadow ? "COMPLETE" : "REJECTED")
                .put("limitation", limitation).put("wfo_path", wfoPath.toString());
        result.set("run", run); result.set("lineage", run.path("lineage").deepCopy()); result.set("wfo", wfo);
        ObjectNode stageResult = object(); stages.outputs().forEach((role, value) -> {
            ObjectNode row = object().put("path", value.path().toString()).put("byte_sha256", value.byteSha256());
            row.set("value", value.value().deepCopy()); stageResult.set(role, row);
        }); result.set("stage_artifacts", stageResult);
        result.set("receipt", receipt); result.put("receipt_path", receiptPath.toString()); return result;
    }

    private static Map<String, ObjectNode> uniquePhysicalRoleMap(List<ObjectNode> rows, String role) {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        for (ObjectNode row : rows) {
            String id = text(row, "episode_id");
            if (id.isEmpty() || result.putIfAbsent(id, row.deepCopy()) != null) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: authoritative " + role
                        + " physical rows contain an empty or duplicate episode identity");
            }
        }
        return result;
    }

    private static Function<String, ObjectNode> v2ExecutionResolver(
            ResearchRunInputs in, Map<String, ObjectNode> executionByEpisode) {
        Map<String, ObjectNode> cache = new LinkedHashMap<>();
        return episodeId -> {
            ObjectNode cached = cache.get(episodeId);
            if (cached != null) return cached.deepCopy();
            ObjectNode base = executionByEpisode.get(episodeId);
            JsonNode rawWindow = in.envelopeByEpisode().get(episodeId);
            if (base == null || !(rawWindow instanceof ObjectNode window)) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: v2 execution range lacks physical role/envelope window for "
                        + episodeId);
            }
            String windowId = text(window, "window_id");
            String entry = firstText(window, "entry_time", "execution_start");
            String start = firstText(window, "preentry_start"); if (start.isEmpty()) start = entry;
            String end = firstText(window, "execution_end", "end_at");
            ObjectNode request = object(); request.set("hydration", in.hydrationPhysical().value());
            request.set("partitions", in.hydrationCustody().partitions()); request.put("window_id", windowId);
            request.put("start", start).put("end", end).put("batchSize",
                    in.hydrationPhysical().value().path("batch_size").asLong(4_096));
            request.put("maxRows", in.hydrationPhysical().value().path("max_rows").asLong(100_000));
            request.put("maxResidentBytes", in.hydrationPhysical().value().path("max_resident_bytes")
                    .asLong(192L * 1024 * 1024));
            request.put("maxOutputBytes", in.hydrationPhysical().value().path("max_output_bytes")
                    .asLong(128L * 1024 * 1024));
            ObjectNode range = OpportunityV5.readHydratedRangeV5(request); ArrayNode flat = array();
            for (JsonNode batch : range.path("batches")) for (JsonNode row : batch) flat.add(row.deepCopy());
            long entryAt = timestampOrMin(NODES.textNode(entry));
            ArrayNode preentry = array(), children = array();
            for (JsonNode row : flat) {
                long at = timestampOrMin(first(row, "event_time", "time", "open_time"));
                if (at < entryAt) preentry.add(row.deepCopy()); else children.add(row.deepCopy());
            }
            if (children.isEmpty()) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: v2 execution range is empty for " + episodeId);
            }
            ObjectNode resolved = base.deepCopy(); resolved.put("entry_time", entry);
            resolved.put("execution_start", entry).put("execution_end", end);
            if (defined(window.get("max_lifecycle_ms"))) resolved.set("max_lifecycle_ms",
                    window.get("max_lifecycle_ms").deepCopy());
            if (defined(window.get("lifecycle_timeframe"))) resolved.set("lifecycle_timeframe",
                    window.get("lifecycle_timeframe").deepCopy());
            resolved.put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                    .put("decision_timeframe", "4h");
            resolved.set("preentry_bars", preentry); resolved.set("child_bars", children);
            JsonNode capture = null;
            for (JsonNode row : in.hydrationPhysical().value().path("windows")) {
                if (windowId.equals(text(row, "window_id"))) { capture = row; break; }
            }
            if (capture != null && !capture.path("mark_partition_refs").isEmpty()) {
                ObjectNode markRequest = request.deepCopy(); markRequest.put("role", "MARK");
                markRequest.put("start", entry); ObjectNode marks = OpportunityV5.readHydratedRangeV5(markRequest);
                ArrayNode markRows = array();
                for (JsonNode batch : marks.path("batches")) for (JsonNode row : batch) markRows.add(row.deepCopy());
                resolved.set("mark_bars", markRows);
            }
            ObjectNode reference = object().put("window_id", windowId).put("execution_start", entry)
                    .put("execution_end", end);
            if (start.equals(entry)) reference.putNull("preentry_start"); else reference.put("preentry_start", start);
            resolved.set("execution_reference", reference); cache.put(episodeId, resolved.deepCopy());
            while (cache.size() > 8) cache.remove(cache.keySet().iterator().next());
            return resolved;
        };
    }

    private static ObjectNode researchOosVector(ObjectNode args, ObjectNode sourceArtifact,
                                                 StrategyEvaluatorV5.Evaluator evaluator,
                                                 Map<String, ObjectNode> definitions,
                                                 Map<String, List<ObjectNode>> observedVectors) {
        ObjectNode scoped = requiredObject(args, "artifact");
        ObjectNode head = requiredObject(args, "exposureHead");
        List<String> episodeIds = rows(args.path("episode_ids")).stream().map(JsonNode::asText).toList();
        if (episodeIds.isEmpty() || new HashSet<>(episodeIds).size() != episodeIds.size()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: OOS vector episode inventory is empty or duplicated");
        }
        Map<String, ObjectNode> episodes = uniquePhysicalRoleMap(objects(scoped.path("episodes")), "OOS episode");
        ObjectNode vectors = object();
        for (JsonNode entry : head.path("entries")) {
            String alias = text(entry, "behavior_sha256"); ObjectNode definition = definitions.get(alias);
            if (definition == null || !definition.path("chromosome").isObject()
                    || !text(entry, "definition_sha256").equals(text(definition, "definition_sha256"))) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: immutable physical behavior definition is unavailable for cumulative alias "
                        + alias);
            }
            Map<String, ObjectNode> observed = new LinkedHashMap<>();
            for (ObjectNode row : observedVectors.getOrDefault(alias, List.of())) {
                if (episodeIds.contains(text(row, "episode_id"))) observed.put(text(row, "episode_id"), row);
            }
            if (observed.size() != episodeIds.size()) {
                ObjectNode view = object().put("schema", "strategy-v5-statistical-signal-view/1")
                        .put("version", 1).put("phase", "OUTER_OOS");
                view.set("fold_id", args.has("fold_id") ? args.get("fold_id").deepCopy() : NullNode.instance);
                view.set("lineage", sourceArtifact.path("lineage").deepCopy());
                view.put("source_artifact_sha256", text(sourceArtifact, "content_sha256"));
                view.set("episode_ids", strings(episodeIds)); ArrayNode identityRows = array();
                for (String id : episodeIds) {
                    ObjectNode episode = episodes.get(id);
                    if (episode == null) throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: OOS vector lacks episode " + id);
                    ObjectNode identity = object().put("episode_id", id).put("asset", text(episode, "asset"))
                            .put("decision_time", text(episode, "decision_time"))
                            .put("resolution_time", text(episode, "resolution_time"))
                            .put("eligible", episode.path("eligible").asBoolean(true)).put("phase", "OUTER_OOS");
                    identity.set("fold_id", view.path("fold_id").deepCopy()); identityRows.add(identity);
                }
                view.set("episodes", identityRows); view.put("content_sha256", ownHash(view));
                ObjectNode task = object(); task.set("artifact", view); task.set("episode_ids", strings(episodeIds));
                task.set("chromosome", definition.path("chromosome").deepCopy()); task.put("phase", "OUTER_OOS");
                task.set("fold_id", view.path("fold_id").deepCopy()); task.putNull("cutoff");
                task.putNull("fit_cutoff"); task.putNull("evaluation_cutoff"); task.put("weighting", "UNWEIGHTED_OOS");
                ObjectNode evaluation = evaluator.evaluate(task); observed.clear();
                for (String id : episodeIds) {
                    JsonNode raw = evaluation.path("candidate_returns").path(id);
                    if (!raw.isObject() || !raw.path("net_r").isNumber() || !raw.path("traded").isBoolean()) {
                        throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: authoritative OOS evaluator omitted physical outcome " + id);
                    }
                    ObjectNode row = object().put("episode_id", id).put("net_r", raw.path("net_r").asDouble())
                            .put("traded", raw.path("traded").asBoolean());
                    observed.put(id, row);
                }
            }
            long discoveredAt = timestampOrMin(entry.path("observed_at")); ArrayNode rows = array();
            for (String id : episodeIds) {
                ObjectNode episode = episodes.get(id), raw = observed.get(id); ObjectNode row = object().put("episode_id", id);
                long decisionAt = episode == null ? Long.MIN_VALUE : timestampOrMin(episode.path("decision_time"));
                if (discoveredAt != Long.MIN_VALUE && decisionAt != Long.MIN_VALUE && decisionAt < discoveredAt) {
                    row.put("net_r", 0).put("traded", false).put("eligible", false);
                } else {
                    row.put("net_r", raw.path("net_r").asDouble()).put("traded", raw.path("traded").asBoolean())
                            .put("eligible", episode != null && episode.path("eligible").asBoolean(true));
                }
                rows.add(row);
            }
            vectors.set(alias, rows);
        }
        ObjectNode make = object(); make.set("exposureHead", head); make.set("episodeIds", strings(episodeIds));
        make.set("vectors", vectors); return StrategyStatisticalV5.makeVectorInventory(make);
    }

    private static ResearchStageArtifacts deriveResearchStageArtifacts(
            ResearchRunInputs in, ObjectNode wfo, Function<String, ObjectNode> resolveExecution,
            Map<String, PersistedResearchArtifact> stressExecutions,
            Map<String, PersistedResearchArtifact> portfolioRisks, Path stageRoot) {
        ObjectNode wfoRun = requiredObject(wfo, "run");
        Map<String, ObjectNode> featureByEpisode = uniquePhysicalRoleMap(in.featureRows(), "feature");
        Map<String, ObjectNode> labelByEpisode = uniquePhysicalRoleMap(in.labelRows(), "label");
        List<ObjectNode> selected = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>();
        ArrayNode geneticRows = array(), stressRows = array();
        for (JsonNode rawOuter : wfoRun.path("asset_decisions")) {
            JsonNode decisions = rawOuter.path("asset_decisions");
            if (!decisions.isObject()) continue;
            var fields = decisions.fields();
            while (fields.hasNext()) {
                JsonNode rawDecision = fields.next().getValue();
                if (!(rawDecision instanceof ObjectNode decision)) continue;
                if (decision.path("genetic_run").isObject()) {
                    ObjectNode genetic = ((ObjectNode) decision.path("genetic_run")).deepCopy();
                    if (!text(genetic, "content_sha256").equals(ownHash(genetic))) {
                        throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: nested physical genetic outputs are incomplete");
                    }
                    geneticRows.add(genetic);
                }
                if (decision.path("stress").isObject()) {
                    ObjectNode stress = ((ObjectNode) decision.path("stress")).deepCopy();
                    if (!text(stress, "content_sha256").equals(ownHash(stress))) {
                        throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical stress outputs are incomplete");
                    }
                    stressRows.add(stress);
                }
                if (!decision.path("selected_chromosome").isObject()
                        || !decision.path("selected_return_vector").isArray()) continue;
                ObjectNode candidate = bindResearchEvaluatorCandidate(in.evaluatorPhysical().value(),
                        (ObjectNode) decision.path("selected_chromosome"));
                for (JsonNode expected : decision.path("selected_return_vector")) {
                    String episodeId = text(expected, "episode_id");
                    if (episodeId.isEmpty() || !seen.add(episodeId)) {
                        throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: authoritative WFO selected episode is empty or duplicated: "
                                + (episodeId.isEmpty() ? "?" : episodeId));
                    }
                    if (!expected.path("traded").asBoolean(false)) {
                        if (Double.compare(expected.path("net_r").asDouble(Double.NaN), 0d) != 0) {
                            throw failure("authoritative untraded episode " + episodeId + " is not an internal zero");
                        }
                        continue;
                    }
                    ObjectNode feature = featureByEpisode.get(episodeId), label = labelByEpisode.get(episodeId);
                    ObjectNode execution = resolveExecution.apply(episodeId);
                    if (feature == null || label == null || execution == null) {
                        throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: authoritative selected episode "
                                + episodeId + " lacks exact physical feature/label/execution rows");
                    }
                    ObjectNode outcome = deriveResearchOutcome(in.loaded(), in.envelopeByEpisode(), episodeId,
                            feature, label, execution, candidate, null);
                    if (Double.compare(outcome.path("net_r").asDouble(Double.NaN),
                            expected.path("net_r").asDouble(Double.NaN)) != 0) {
                        throw failure("physical selected-fill recomputation differs from evaluator output for " + episodeId);
                    }
                    if (!physicalMarksCover(outcome, in.markRows())) {
                        throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: selected fills are not covered by exact physical mark rows");
                    }
                    selected.add(outcome);
                }
            }
        }
        if (selected.isEmpty()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: nested WFO produced no selected physical fills");
        }
        boolean marksBound = selected.stream().allMatch(row -> physicalMarksCover(row, in.markRows()));
        boolean derivative = selected.stream().anyMatch(row ->
                !"BINANCE_SPOT".equals(normalizedResearchInstrument(text(row, "instrument"))));
        String fundingStatus = derivative ? selected.stream().allMatch(row -> row.path("funding_settlements").isArray())
                ? "PHYSICAL_SETTLEMENTS" : "UNAVAILABLE" : "NOT_APPLICABLE";
        if (!marksBound || "UNAVAILABLE".equals(fundingStatus)) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: selected fills lack exact mark/funding custody");
        }
        String manifestSha = text(in.manifestPhysical().value(), "content_sha256");
        String wfoSha = text(wfoRun, "content_sha256");
        ArrayNode selectedValues = array(); selected.forEach(row -> selectedValues.add(row.deepCopy()));
        ObjectNode fills = makeResearchStageArtifact("EXECUTION_FILLS", selectedValues,
                manifestSha, wfoSha, marksBound, fundingStatus);
        ArrayNode compactFills = array(), tradesRows = array();
        for (ObjectNode outcome : selected) {
            ObjectNode compact = compactResearchOutcome(outcome); compactFills.add(compact.deepCopy());
            ObjectNode trade = compact.deepCopy(); trade.put("signal_id", text(outcome, "signal_id"));
            trade.put("reason", firstText(outcome, "reason", "exit_reason")); tradesRows.add(trade);
        }
        ObjectNode trades = makeResearchStageArtifact("SELECTED_TRADES", tradesRows,
                manifestSha, wfoSha, marksBound, fundingStatus);
        if (geneticRows.isEmpty() || stressRows.isEmpty()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: nested physical genetic/stress outputs are incomplete");
        }
        ObjectNode genetic = makeResearchStageArtifact("GENETIC", geneticRows,
                manifestSha, wfoSha, marksBound, fundingStatus);
        ArrayNode stressRefs = array(); stressExecutions.values().stream()
                .sorted(Comparator.comparing(value -> text(value.value(), "content_sha256"))).forEach(value -> {
                    ObjectNode reference = object().put("content_sha256", text(value.value(), "content_sha256"))
                            .put("byte_sha256", value.byteSha256()).put("path", portablePath(value.path()));
                    stressRefs.add(reference);
                });
        ArrayNode boundStressRows = array();
        for (JsonNode raw : stressRows) {
            ObjectNode row = ((ObjectNode) raw).deepCopy(); row.set("selected_fills", compactFills.deepCopy());
            row.put("selected_fills_sha256", text(fills, "content_sha256"))
                    .put("physical_fill_digest", hash(compactFills));
            row.set("stress_execution_artifacts", stressRefs.deepCopy()); boundStressRows.add(row);
        }
        ObjectNode stresses = makeResearchStageArtifact("STRESSES", boundStressRows,
                manifestSha, wfoSha, marksBound, fundingStatus);
        if (!wfoRun.path("portfolio_decision").isObject()
                || !text(wfoRun.path("portfolio_decision"), "content_sha256")
                .equals(ownHash(wfoRun.path("portfolio_decision")))) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical portfolio output is incomplete");
        }
        PersistedResearchArtifact finalRisk = portfolioRisks.get("FINAL_OOS");
        if (finalRisk == null && !portfolioRisks.isEmpty()) {
            finalRisk = new ArrayList<>(portfolioRisks.values()).get(portfolioRisks.size() - 1);
        }
        ObjectNode portfolioRow = ((ObjectNode) wfoRun.path("portfolio_decision")).deepCopy();
        portfolioRow.set("selected_fills", compactFills.deepCopy()); portfolioRow.set("selected_trades", tradesRows.deepCopy());
        portfolioRow.put("selected_fills_sha256", text(fills, "content_sha256"))
                .put("selected_trades_sha256", text(trades, "content_sha256"))
                .put("physical_fill_digest", hash(compactFills)).put("marks_bound", marksBound)
                .put("funding_status", fundingStatus);
        if (finalRisk == null) {
            portfolioRow.putNull("portfolio_engine_schema").putNull("portfolio_engine_sha256")
                    .putNull("portfolio_engine_byte_sha256").putNull("portfolio_engine_path");
        } else {
            portfolioRow.put("portfolio_engine_schema", text(finalRisk.value(), "schema"))
                    .put("portfolio_engine_sha256", text(finalRisk.value(), "content_sha256"))
                    .put("portfolio_engine_byte_sha256", finalRisk.byteSha256())
                    .put("portfolio_engine_path", portablePath(finalRisk.path()));
        }
        ObjectNode portfolio = makeResearchStageArtifact("PORTFOLIO", array().add(portfolioRow),
                manifestSha, wfoSha, marksBound, fundingStatus);
        Map<String, PersistedResearchArtifact> outputs = new LinkedHashMap<>();
        persistResearchStageOutput(outputs, "genetic", genetic, in.source(), stageRoot);
        persistResearchStageOutput(outputs, "execution_fills", fills, in.source(), stageRoot);
        persistResearchStageOutput(outputs, "selected_trades", trades, in.source(), stageRoot);
        persistResearchStageOutput(outputs, "stresses", stresses, in.source(), stageRoot);
        persistResearchStageOutput(outputs, "portfolio", portfolio, in.source(), stageRoot);
        persistResearchStageOutput(outputs, "final_oos_artifact", requiredObject(wfo, "artifact"), in.source(), stageRoot);
        persistResearchStageOutput(outputs, "final_oos_vector_inventory", requiredObject(wfo, "vectorInventory"),
                in.source(), stageRoot);
        return new ResearchStageArtifacts(Map.copyOf(outputs), List.copyOf(selected), marksBound, fundingStatus);
    }

    private static ObjectNode makeResearchStageArtifact(String stage, ArrayNode rows, String manifestSha,
                                                         String wfoSha, boolean marksBound,
                                                         String fundingStatus) {
        ObjectNode value = object().put("schema", "strategy-v5-authoritative-stage-artifact/1")
                .put("version", 1).put("stage", stage).put("provenance", "AUTHORITATIVE_RECOMPUTED")
                .put("source_manifest_sha256", manifestSha).put("wfo_sha256", wfoSha)
                .put("marks_bound", marksBound).put("funding_status", fundingStatus);
        value.set("rows", rows.deepCopy()); value = withHash(value); SCHEMAS.validateKnownContractSchema(value); return value;
    }

    private static void persistResearchStageOutput(Map<String, PersistedResearchArtifact> outputs,
                                                   String role, ObjectNode value, ObjectNode source,
                                                   Path stageRoot) {
        if (!text(value, "content_sha256").equals(ownHash(value))) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: " + role + " is missing or not hash-bound");
        }
        String dashed = role.replace('_', '-'); String requested = firstText(source, role + "_out", dashed + "_out");
        Path path = requested.isEmpty() ? stageRoot.resolve(role + "-" + text(value, "content_sha256") + ".json")
                : absolute(Path.of(requested));
        writeImmutable(path, value); byte[] bytes = readSinglyLinked(path, "research stage " + role);
        outputs.put(role, new PersistedResearchArtifact(value.deepCopy(), absolute(path), hash(bytes)));
    }

    private static ObjectNode makeCompletedResearchRun(ResearchRunInputs in, ObjectNode wfo,
                                                        Map<String, List<ObjectNode>> observedVectors,
                                                        Map<String, ObjectNode> observedEvaluations,
                                                        Map<String, ObjectNode> observedAttempts,
                                                        ResearchStageArtifacts stages) {
        ObjectNode manifest = in.manifestPhysical().value(), artifact = in.artifactPhysical().value();
        ObjectNode evaluator = in.evaluatorPhysical().value(), precommit = in.precommitPhysical().value();
        ObjectNode experiment = in.experimentPhysical().value(), wfoRun = (ObjectNode) wfo.path("run");
        List<String> assets = objects(artifact.path("episodes")).stream().map(row -> lower(text(row, "asset")))
                .filter(value -> !value.isEmpty()).distinct().sorted().toList();
        ObjectNode lineage = object().put("manifest_sha256", text(manifest, "content_sha256"))
                .put("envelope_sha256", text(in.envelopePhysical().value(), "content_sha256"))
                .put("opportunity_domain_sha256", text(in.domainPhysical().value(), "content_sha256"))
                .put("opportunity_hydration_sha256", text(in.hydrationPhysical().value(), "content_sha256"))
                .put("opportunity_partition_root_sha256", in.hydrationCustody().descriptorRoot())
                .put("candidate_set_sha256", text(artifact.path("lineage"), "candidate_set_sha256"))
                .put("feature_rows_sha256", text(manifest.path("artifacts").path("feature"), "sha256"))
                .put("label_rows_sha256", text(manifest.path("artifacts").path("label"), "sha256"))
                .put("execution_rows_sha256", text(manifest.path("artifacts").path("execution"), "sha256"))
                .put("mark_rows_sha256", text(manifest.path("artifacts").path("mark"), "sha256"))
                .put("wfo_sha256", text(wfoRun, "content_sha256"));
        ArrayNode candidateMetrics = researchRunCandidateMetrics(wfo, stages, observedEvaluations);
        PersistedResearchArtifact stressStage = stages.outputs().get("stresses");
        PersistedResearchArtifact portfolioStage = stages.outputs().get("portfolio");
        boolean wfoPass = wfoRun.path("gate_pass").asBoolean(false);
        boolean stressPass = stressStage != null && !stressStage.value().path("rows").isEmpty()
                && rows(stressStage.value().path("rows")).stream()
                .allMatch(row -> row.path("pass").asBoolean(false));
        boolean portfolioPass = portfolioStage != null && stages.marksBound()
                && portfolioStage.value().path("rows").path(0).path("pass").asBoolean(false);
        Set<String> requiredStages = Set.of("genetic", "execution_fills", "selected_trades", "stresses",
                "portfolio", "final_oos_artifact", "final_oos_vector_inventory");
        boolean allStages = stages.outputs().keySet().equals(requiredStages);
        String decision = "SHADOW".equals(text(wfoRun, "decision")) && stressPass && portfolioPass && allStages
                ? "SHADOW" : "REJECTED";
        String strategyFamily = firstText(evaluator, "strategy_family");
        String strategyVersion = firstText(precommit, "strategy_version", "version_id");
        if (strategyVersion.isEmpty()) strategyVersion = firstText(evaluator, "strategy_version");
        if (strategyVersion.isEmpty()) strategyVersion = firstText(artifact, "strategy_version");
        String experimentId = firstText(experiment, "experiment_id");
        if (experimentId.isEmpty()) experimentId = firstText(evaluator, "experiment_id");
        if (experimentId.isEmpty()) experimentId = firstText(artifact, "experiment_id");
        if (experimentId.isEmpty()) experimentId = firstText(artifact.path("metadata"), "experiment_id");
        if (strategyFamily.isEmpty() || strategyVersion.isEmpty() || experimentId.isEmpty()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical evaluator/precommit/experiment contract lacks exact strategy_family_id, strategy_version, or experiment_id");
        }
        ObjectNode run = object().put("schema", "strategy-research-run/5").put("version", 1)
                .put("provenance", "AUTHORITATIVE_RECOMPUTED")
                .put("strategy_family_id", strategyFamily).put("strategy_version", strategyVersion)
                .put("experiment_id", experimentId);
        String evidencePhase = firstText(experiment, "evidence_phase");
        if (evidencePhase.isEmpty()) evidencePhase = firstText(artifact, "evidence_phase");
        if (evidencePhase.isEmpty()) evidencePhase = firstText(artifact.path("metadata"), "evidence_phase", "phase");
        putNullable(run, "evidence_phase", indexEvidencePhase(evidencePhase));
        run.set("asset_set", strings(assets)); run.set("pipeline", strings(PIPELINE_V5)); run.set("lineage", lineage);
        run.put("manifest_sha256", text(manifest, "content_sha256"))
                .put("envelope_sha256", text(in.envelopePhysical().value(), "content_sha256"))
                .put("opportunity_domain_sha256", text(in.domainPhysical().value(), "content_sha256"))
                .put("opportunity_hydration_sha256", text(in.hydrationPhysical().value(), "content_sha256"))
                .put("opportunity_partition_root_sha256", in.hydrationCustody().descriptorRoot())
                .put("cutoff", in.boundary())
                .put("feature_rows_sha256", text(manifest.path("artifacts").path("feature"), "sha256"))
                .put("label_rows_sha256", text(manifest.path("artifacts").path("label"), "sha256"))
                .put("execution_rows_sha256", text(manifest.path("artifacts").path("execution"), "sha256"))
                .put("mark_rows_sha256", text(manifest.path("artifacts").path("mark"), "sha256"));
        run.set("candidate_metrics", candidateMetrics); ArrayNode metricInventory = array();
        for (JsonNode metric : candidateMetrics) metricInventory.add(object()
                .put("candidate_id", text(metric, "candidate_id"))
                .put("behavior_sha256", text(metric, "behavior_sha256"))
                .put("asset", text(metric, "asset")).put("fold_id", text(metric, "fold_id"))
                .put("evidence_phase", text(metric, "evidence_phase"))
                .put("scope_episode_ids_sha256", text(metric, "scope_episode_ids_sha256")));
        List<JsonNode> sortedMetricInventory = rows(metricInventory).stream()
                .sorted(Comparator.comparing(row -> text(row, "candidate_id") + "|"
                        + text(row, "scope_episode_ids_sha256"))).toList();
        Set<String> evaluatedBehaviors = new LinkedHashSet<>();
        candidateMetrics.forEach(row -> evaluatedBehaviors.add(text(row, "behavior_sha256")));
        ObjectNode accounting = object()
                .put("declared_k", artifact.path("candidates").size()).put("evaluated_k", evaluatedBehaviors.size())
                .put("current_evaluation_attempt_k", observedAttempts.size())
                .put("current_evaluation_attempt_inventory_sha256", hash(strings(observedAttempts.keySet().stream().sorted().toList())))
                .put("cumulative_family_k", wfoRun.path("cumulative_k").asLong(0))
                .put("candidate_metric_count", candidateMetrics.size())
                .put("candidate_metric_inventory_sha256", hash(array(sortedMetricInventory)))
                .put("market_episode_count", artifact.path("episodes").size()).put("zero_episode_binding", true);
        run.set("accounting", accounting); ObjectNode summary = object().put("pass", wfoPass)
                .put("status", text(wfoRun, "decision")).put("artifact", text(wfoRun, "content_sha256"));
        run.set("wfo", summary);
        PersistedResearchArtifact fills = stages.outputs().get("execution_fills");
        PersistedResearchArtifact trades = stages.outputs().get("selected_trades");
        PersistedResearchArtifact finalArtifact = stages.outputs().get("final_oos_artifact");
        PersistedResearchArtifact finalVectors = stages.outputs().get("final_oos_vector_inventory");
        run.put("execution_fills_sha256", text(fills.value(), "content_sha256"))
                .put("selected_trades_sha256", text(trades.value(), "content_sha256"))
                .put("stresses_sha256", text(stressStage.value(), "content_sha256"))
                .put("portfolio_sha256", text(portfolioStage.value(), "content_sha256"))
                .put("oos_artifact_sha256", text(finalArtifact.value(), "content_sha256"))
                .put("vector_inventory_sha256", text(finalVectors.value(), "content_sha256"))
                .put("oos_validation_exposure_head_sha256", text(wfoRun, "validation_exposure_head_sha256"));
        run.set("oos_episode_ids", wfoRun.path("oos_episode_ids").deepCopy());
        ObjectNode stageHashes = object(), stageRefs = object(); Path publicationRoot = recordRoot(in.source());
        for (String role : List.of("genetic", "execution_fills", "selected_trades", "stresses", "portfolio",
                "final_oos_artifact", "final_oos_vector_inventory")) {
            PersistedResearchArtifact value = stages.outputs().get(role);
            stageHashes.put(role, text(value.value(), "content_sha256"));
            stageRefs.set(role, researchStageReference(publicationRoot, value));
        }
        run.set("stage_artifacts", stageHashes); run.set("stage_artifact_refs", stageRefs);
        run.put("decision", decision);
        run.set("gate_status", object().put("wfo", wfoPass).put("stress", stressPass)
                .put("portfolio", portfolioPass).put("all_required_stages",
                        "SHADOW".equals(decision) && stressPass && portfolioPass && allStages));
        ObjectNode result = withHash(run); SCHEMAS.validateKnownContractSchema(result); return result;
    }

    private static ArrayNode researchRunCandidateMetrics(ObjectNode wfo, ResearchStageArtifacts stages,
                                                          Map<String, ObjectNode> observedEvaluations) {
        ArrayNode result = array(); Set<String> seen = new LinkedHashSet<>();
        ArrayNode selectedTrades = stages.outputs().get("selected_trades").value().withArray("rows").deepCopy();
        for (JsonNode rawOuter : wfo.path("run").path("asset_decisions")) {
            String fold = firstTextOr((ObjectNode) rawOuter, "outer", "fold_id");
            JsonNode decisions = rawOuter.path("asset_decisions"); if (!decisions.isObject()) continue;
            var fields = decisions.fields();
            while (fields.hasNext()) {
                JsonNode raw = fields.next().getValue(); if (!(raw instanceof ObjectNode decision)) continue;
                String alias = firstText(decision, "selected_behavior_alias_sha256", "selected_candidate_id");
                if (!HASH.matcher(alias).matches() || !decision.path("metrics").isObject()) continue;
                String asset = lower(text(decision, "asset")); List<String> episodeIds = objects(
                        decision.path("selected_return_vector")).stream().map(row -> text(row, "episode_id"))
                        .filter(value -> !value.isEmpty()).distinct().sorted().toList();
                if (episodeIds.isEmpty()) continue;
                ObjectNode attemptInput = object().put("schema", "strategy-v5-authoritative-evaluation-attempt/1")
                        .put("phase", "OUTER_OOS").put("fold_id", fold).put("behavior_sha256", alias);
                attemptInput.set("scope_episode_ids", strings(episodeIds));
                attemptInput.set("metrics", decision.path("metrics").deepCopy()); String attemptSha = hash(attemptInput);
                String dedupe = attemptSha + "|" + alias + "|" + hash(strings(episodeIds)); if (!seen.add(dedupe)) continue;
                ObjectNode evaluation = observedEvaluations.get(alias + "|OUTER_OOS|" + fold);
                ObjectNode metrics = ((ObjectNode) decision.path("metrics")).deepCopy();
                if (!metrics.path("expectancy_r").isNumber()) {
                    double expectancy = objects(decision.path("selected_return_vector")).stream()
                            .mapToDouble(row -> row.path("net_r").asDouble()).average().orElse(0);
                    metrics.put("expectancy_r", expectancy);
                }
                if (!defined(metrics.get("episode_returns")) && !defined(metrics.get("episode_returns_sha256"))) {
                    metrics.put("episode_returns_sha256", hash(decision.path("selected_return_vector")));
                }
                ArrayNode trades = array(); for (JsonNode trade : selectedTrades) if (asset.equals(lower(text(trade, "asset")))
                        && episodeIds.contains(text(trade, "episode_id"))) trades.add(trade.deepCopy());
                ObjectNode row = object().put("candidate_id", fold + ":" + asset + ":OUTER_OOS:"
                        + fold + ":" + alias + ":" + attemptSha).put("asset", asset).put("fold_id", fold)
                        .put("behavior_sha256", alias).put("selected", true).put("finalist", true)
                        .put("evidence_phase", "OOS").put("metric_phase", "OOS")
                        .put("weighting", "UNWEIGHTED_OOS").put("scope_episode_ids_sha256", hash(strings(episodeIds)))
                        .put("scope_episode_count", episodeIds.size()).put("trade_scope", "OUTER_OOS_SELECTED")
                        .put("evaluation_attempt_sha256", attemptSha)
                        .put("evaluation_context_sha256", hash(object().put("phase", "OUTER_OOS")
                                .put("fold_id", fold).set("scope_episode_ids", strings(episodeIds))));
                row.putNull("seed").putNull("generation").putNull("operator");
                row.set("chromosome", decision.path("selected_chromosome").deepCopy());
                row.set("signal_intent", evaluation != null && evaluation.path("signal_intent_vector").isArray()
                        ? evaluation.path("signal_intent_vector").deepCopy() : array());
                row.set("trades", trades); row.set("metrics", metrics); row.set("stresses", decision.path("stress").deepCopy());
                row.set("portfolio", object().put("selected_trades_sha256", text(stages.outputs()
                        .get("selected_trades").value(), "content_sha256"))); result.add(row);
            }
        }
        List<JsonNode> sorted = rows(result).stream().sorted(Comparator.comparing(row -> text(row, "asset") + "|"
                + text(row, "fold_id") + "|" + text(row, "behavior_sha256") + "|"
                + text(row, "evaluation_attempt_sha256"))).toList();
        return array(sorted);
    }

    private static ObjectNode researchStageReference(Path recordRoot, PersistedResearchArtifact value) {
        Path root = absolute(recordRoot), path = absolute(value.path());
        if (!path.startsWith(root)) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical stage artifact is outside the publication record root");
        }
        byte[] bytes = readSinglyLinked(path, "physical publication stage artifact");
        byte[] expected = NodePrettyJson.write(value.value()).getBytes(StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, expected) || !value.byteSha256().equals(hash(bytes))
                || !text(value.value(), "content_sha256").equals(ownHash(value.value()))) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical stage artifact bytes are not bound");
        }
        String relative = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
        return object().put("schema", text(value.value(), "schema")).put("version", value.value().path("version").asInt())
                .put("path", relative).put("content_sha256", text(value.value(), "content_sha256"))
                .put("byte_sha256", value.byteSha256()).put("bytes", bytes.length);
    }

    private static ObjectNode researchPortfolioDecision(
            ObjectNode args, ResearchRunInputs in, Map<String, ObjectNode> observedEvaluations,
            Map<String, List<ObjectNode>> observedVectors,
            Map<String, ObjectNode> featureByEpisode, Map<String, ObjectNode> labelByEpisode,
            Function<String, ObjectNode> resolveExecution,
            Map<String, PersistedResearchArtifact> retained, Path stageRoot) {
        ObjectNode scoped = requiredObject(args, "artifact");
        ArrayNode assetDecisions = args.path("asset_decisions") instanceof ArrayNode rows
                ? rows.deepCopy() : array();
        String lineageSha = requireSha(text(args, "lineage_sha256"), "portfolio lineage");
        JsonNode foldNode = defined(args.get("fold_id")) ? args.get("fold_id") : NullNode.instance;
        String foldId = foldNode.isNull() ? "FINAL" : jsString(foldNode);
        if (assetDecisions.isEmpty() || foldId.isEmpty()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio requires physical asset decisions and a fold identity");
        }

        ArrayNode increments = array(); List<PhysicalOutcomeRow> selectedPhysical = new ArrayList<>();
        Set<String> seenEpisodes = new LinkedHashSet<>();
        for (JsonNode rawDecision : assetDecisions) {
            if (!(rawDecision instanceof ObjectNode decision)
                    || !decision.path("selected_chromosome").isObject()
                    || !decision.path("selected_return_vector").isArray()) continue;
            String alias = firstText(decision, "selected_behavior_alias_sha256", "selected_candidate_id");
            requireSha(alias, "portfolio selected behavior alias");
            Map<String, ObjectNode> expectedById = new LinkedHashMap<>();
            for (ObjectNode row : objects(decision.path("selected_return_vector"))) {
                String id = text(row, "episode_id");
                if (id.isEmpty() || expectedById.putIfAbsent(id, row) != null) {
                    throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio selected return vector has an empty or duplicate episode identity");
                }
            }
            List<PhysicalOutcomeRow> physical = researchPhysicalOutcomeInventory(scoped, alias,
                    expectedById.keySet(), observedEvaluations, observedVectors,
                    in, featureByEpisode, labelByEpisode, resolveExecution);
            for (PhysicalOutcomeRow row : physical) {
                ObjectNode expected = expectedById.get(row.episodeId());
                if (expected == null || Double.compare(expected.path("net_r").asDouble(Double.NaN), row.netR()) != 0
                        || expected.path("traded").asBoolean(false) != row.traded()) {
                    throw failure("physical portfolio selected-fill substitution detected for " + row.episodeId());
                }
                if (!seenEpisodes.add(row.episodeId())) {
                    throw failure("physical portfolio selected-fill episode is duplicated: " + row.episodeId());
                }
                if (row.traded() && row.outcome() != null) {
                    selectedPhysical.add(row); increments.add(object().put("episode_id", row.episodeId())
                            .put("asset", row.asset()).put("net_r", row.netR()));
                }
            }
        }
        if (increments.isEmpty() || selectedPhysical.isEmpty()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio recomputation has no complete physical selected-fill inventory");
        }

        ArrayNode selectedRows = array(), executionRows = array();
        for (PhysicalOutcomeRow row : selectedPhysical) {
            ObjectNode outcome = row.outcome(), execution = row.execution();
            String instrument = normalizedResearchInstrument(text(outcome, "instrument"));
            boolean derivative = !"BINANCE_SPOT".equals(instrument);
            JsonNode liquidation = outcome.path("liquidation_model");
            JsonNode collateralNode = firstDefinedResearch(outcome.get("collateral_used"),
                    liquidation.get("collateral_usd"), execution.get("collateral_usd"), execution.get("collateral"));
            JsonNode tierNode = firstDefinedResearch(outcome.get("tier_id"), liquidation.get("tier_id"),
                    execution.get("tier_id"), execution.get("margin_tier_id"));
            JsonNode marginNode = firstDefinedResearch(outcome.get("margin_mode"), liquidation.get("margin_mode"),
                    execution.get("margin_mode"));
            JsonNode leverageNode = firstDefinedResearch(outcome.get("leverage"), liquidation.get("leverage"),
                    execution.get("leverage"));
            double collateral = number(collateralNode), leverage = number(leverageNode);
            String tier = textValue(tierNode), margin = textValue(marginNode);
            if (derivative && (!(collateral > 0) || margin.isEmpty() || !(leverage > 0) || tier.isEmpty())) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: derivative selected fill "
                        + row.episodeId() + " lacks exact derived collateral, margin mode, leverage, or margin tier");
            }
            double quantity = outcome.path("quantity").asDouble(Double.NaN);
            double multiplier = outcome.path("contract_multiplier").asDouble(Double.NaN);
            double entryPrice = outcome.path("entry_price").asDouble(Double.NaN);
            double risk = outcome.path("risk_amount_usd").asDouble(Double.NaN);
            double stopDistance = risk / (quantity * multiplier);
            String direction = lower(text(outcome, "direction"));
            double stopPrice = "long".equals(direction) ? entryPrice - stopDistance : entryPrice + stopDistance;
            if (!(quantity > 0) || !(multiplier > 0) || !(entryPrice > 0) || !(risk > 0)
                    || !(stopPrice > 0) || !("long".equals(direction) || "short".equals(direction))) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: selected fill " + row.episodeId()
                        + " has no positive frozen stop/risk reservation");
            }
            JsonNode executionCollateral = firstDefinedResearch(execution.get("collateral_usd"), execution.get("collateral"));
            if (defined(executionCollateral) && !(number(executionCollateral) > 0)) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: selected derivative " + row.episodeId()
                        + " lacks a positive physical collateral reservation");
            }
            String signalId = text(outcome, "signal_id");
            String venue = lower(text(outcome, "venue")), symbol = text(outcome, "symbol").toUpperCase(Locale.ROOT);
            String instrumentType = switch (instrument) {
                case "BINANCE_SPOT" -> "spot";
                case "BINANCE_USDM_DATED_FUTURE" -> "dated_future";
                default -> "perpetual";
            };
            ObjectNode selected = object().put("signal_id", signalId).put("asset", lower(text(outcome, "asset")))
                    .put("venue", venue).put("symbol", symbol).put("instrument_type", instrumentType)
                    .put("direction", direction).put("quantity", quantity)
                    .put("entry_time", text(outcome, "entry_time")).put("exit_time", text(outcome, "exit_time"))
                    .put("stop_price", stopPrice);
            ObjectNode executionFill = object().put("signal_id", signalId).put("asset", lower(text(outcome, "asset")))
                    .put("symbol", symbol).put("instrument_type", instrumentType).put("direction", direction)
                    .put("quantity", quantity).put("entry_time", text(outcome, "entry_time"))
                    .put("exit_time", text(outcome, "exit_time")).put("entry_price", entryPrice)
                    .put("exit_price", outcome.path("exit_price").asDouble(Double.NaN));
            if (defined(collateralNode)) { selected.put("collateral_used", collateral); executionFill.put("collateral_used", collateral); }
            if (derivative) {
                selected.put("leverage", leverage).put("margin_mode", margin).put("margin_tier_id", tier);
                executionFill.put("leverage", leverage).put("margin_mode", margin).put("margin_tier_id", tier);
            }
            selectedRows.add(selected); executionRows.add(executionFill);
        }

        ObjectNode selectedLineageInput = object().put("source_manifest_sha256",
                text(in.manifestPhysical().value(), "content_sha256"))
                .put("source_artifact_sha256", text(scoped, "content_sha256"));
        selectedLineageInput.set("fold_id", foldNode.deepCopy());
        selectedLineageInput.set("selected_episode_ids", strings(selectedPhysical.stream()
                .map(PhysicalOutcomeRow::episodeId).sorted().toList()));
        selectedLineageInput.put("evaluator_sha256", text(in.evaluatorPhysical().value(), "content_sha256"));
        String selectedLineage = hash(selectedLineageInput), selectedRowsHash = hash(selectedRows);
        ObjectNode placeholderInput = object().put("source", text(scoped, "content_sha256"));
        placeholderInput.set("fold_id", foldNode.deepCopy()); placeholderInput.put("selectedRowsHash", selectedRowsHash);
        ObjectNode selectedValue = object().put("schema", "strategy-selected-trades/1").put("version", 1)
                .put("status", "SELECTED").put("lineage_sha256", selectedLineage)
                .put("evaluation_sha256", hash(placeholderInput)); selectedValue.set("rows", selectedRows);
        selectedValue = withHash(selectedValue);
        ObjectNode outerInput = object(); outerInput.set("fold_id", foldNode.deepCopy());
        outerInput.put("artifact", text(scoped, "content_sha256"));
        ObjectNode evaluationLineage = object().put("selected_lineage_sha256", selectedLineage)
                .put("source_artifact_sha256", text(scoped, "content_sha256"));
        evaluationLineage.set("fold_id", foldNode.deepCopy());
        ObjectNode evaluationValue = object().put("schema", "strategy-selected-evaluation/1").put("version", 1)
                .put("status", "AUTHORITATIVE").put("selected_trades_sha256", selectedRowsHash)
                .put("outer_fold_sha256", hash(outerInput)).put("lineage_sha256", hash(evaluationLineage));
        evaluationValue = withHash(evaluationValue); selectedValue.put("evaluation_sha256",
                text(evaluationValue, "content_sha256")); selectedValue = withHash(selectedValue);
        SCHEMAS.validateKnownContractSchema(selectedValue); SCHEMAS.validateKnownContractSchema(evaluationValue);

        Path lineageRoot = stageRoot.resolve("portfolio-lineage").resolve("fold-" + hash(foldId));
        createDirectoryCustody(lineageRoot);
        Path selectedPath = persistResearchJson(lineageRoot,
                "selected-" + text(selectedValue, "content_sha256") + ".json", selectedValue);
        Path evaluationPath = persistResearchJson(lineageRoot,
                "evaluation-" + text(evaluationValue, "content_sha256") + ".json", evaluationValue);
        byte[] selectedBytes = readSinglyLinked(selectedPath, "portfolio selected trades");
        byte[] evaluationBytes = readSinglyLinked(evaluationPath, "portfolio selected evaluation");

        Map<String, String> metadataNames = new LinkedHashMap<>();
        metadataNames.put("fee", "fee_schedule"); metadataNames.put("contract", "contract_spec");
        metadataNames.put("margin", "margin"); metadataNames.put("liquidation", "liquidation");
        metadataNames.put("expiry", "expiry"); metadataNames.put("funding", "funding_identity");
        metadataNames.put("execution_model", "execution_model");
        Map<String, Path> metadataPaths = new LinkedHashMap<>();
        Map<String, String> metadataByteHashes = new LinkedHashMap<>();
        for (Map.Entry<String, String> binding : metadataNames.entrySet()) {
            JsonNode raw = in.metadataPhysical().value().get(binding.getValue());
            if (!(raw instanceof ObjectNode receipt)) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: metadata bundle lacks " + binding.getValue());
            }
            Path path = persistResearchJson(lineageRoot, binding.getValue() + "-"
                    + text(receipt, "content_sha256") + ".json", receipt);
            metadataPaths.put(binding.getKey(), path);
            metadataByteHashes.put(binding.getKey(), hash(readSinglyLinked(path,
                    "portfolio " + binding.getValue() + " metadata")));
        }

        JsonNode executionDescriptor = in.manifestPhysical().value().path("artifacts").path("execution");
        PathConfinement.ResolvedPath executionResolved;
        try {
            executionResolved = PathConfinement.resolve(in.parquetRoot(), text(executionDescriptor, "path"),
                    "authoritative execution Parquet source", PathConfinement.ExpectedType.FILE);
        } catch (RuntimeException error) { throw failure(error.getMessage()); }
        byte[] executionSourceBytes = readSinglyLinked(executionResolved.absolute(),
                "authoritative execution Parquet source");
        String executionSourceSha = requireSha(text(executionDescriptor, "sha256"),
                "authoritative execution Parquet source byte hash");
        if (!executionSourceSha.equals(hash(executionSourceBytes))) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: authoritative execution Parquet source is missing or tampered");
        }
        Path executionSourcePath = persistResearchBytes(lineageRoot,
                "execution-source-" + executionSourceSha + ".parquet", executionSourceBytes);
        Path evaluatorSource = repositoryRoot().resolve(
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/StrategyEvaluatorV5.java");
        byte[] evaluatorCodeBytes = readSinglyLinked(evaluatorSource, "Java authoritative evaluator source");
        String evaluatorCodeSha = hash(evaluatorCodeBytes);
        Path evaluatorCodePath = persistResearchBytes(lineageRoot,
                "evaluator-code-" + evaluatorCodeSha + ".java", evaluatorCodeBytes);
        byte[] metadataBundleBytes = readSinglyLinked(in.metadataPhysical().path(), "portfolio metadata bundle");
        if (!in.metadataPhysical().byteSha256().equals(hash(metadataBundleBytes))) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio metadata bundle bytes changed");
        }
        Path metadataBundlePath = persistResearchBytes(lineageRoot,
                "metadata-bundle-" + in.metadataPhysical().byteSha256() + ".json", metadataBundleBytes);

        ObjectNode scenarioPolicy = object().put("schema", "strategy-portfolio-policy/1").put("version", 1)
                .put("fold_id", foldId).put("source_artifact_sha256", text(scoped, "content_sha256"))
                .put("selected_trades_sha256", text(selectedValue, "content_sha256"))
                .put("evaluation_sha256", text(evaluationValue, "content_sha256"));
        ArrayNode policyScenarios = array(); Set<String> supported = Set.of("DOUBLED_COST", "DELAYED_ENTRY",
                "ADVERSE_COLLISION", "GAP", "LIQUIDITY", "CAPACITY", "OUTAGE", "FUNDING", "EXPIRY", "LIQUIDATION");
        for (JsonNode decision : assetDecisions) for (JsonNode scenario : decision.path("stress").path("scenarios")) {
            String kind = text(scenario, "id"); if (!supported.contains(kind)) continue;
            ObjectNode row = object().put("scenario_id", foldId + ":" + text(decision, "asset") + ":" + kind)
                    .put("kind", kind).put("pass", scenario.path("pass").asBoolean(false));
            row.set("parameters", object()); policyScenarios.add(row);
        }
        scenarioPolicy.set("scenarios", policyScenarios);
        byte[] scenarioPolicyBytes = NodePrettyJson.write(scenarioPolicy).getBytes(StandardCharsets.UTF_8);
        Path scenarioPolicyPath = persistResearchBytes(lineageRoot,
                "scenario-policy-" + hash(scenarioPolicy) + ".json", scenarioPolicyBytes);
        String scenarioPolicySha = hash(scenarioPolicyBytes);

        ObjectNode executionLineage = object().put("provenance", "AUTHORITATIVE")
                .put("execution_source_sha256", executionSourceSha)
                .put("execution_source_path", portablePath(executionSourcePath))
                .put("selected_trades_sha256", text(selectedValue, "content_sha256"))
                .put("evaluation_sha256", text(evaluationValue, "content_sha256"))
                .put("evaluator_code_sha256", evaluatorCodeSha)
                .put("evaluator_code_path", portablePath(evaluatorCodePath))
                .put("metadata_sha256", in.metadataPhysical().byteSha256())
                .put("metadata_path", portablePath(metadataBundlePath))
                .put("scenario_policy_sha256", scenarioPolicySha)
                .put("scenario_policy_path", portablePath(scenarioPolicyPath))
                .put("child_input_sha256", hash(selectedBytes)).put("child_input_path", portablePath(selectedPath))
                .put("price_model_sha256", in.markPhysical().byteSha256())
                .put("price_model_path", portablePath(in.markPhysical().path()));
        ObjectNode executionValue = object().put("schema", "strategy-execution-fill-artifact/1")
                .put("version", 1).put("venue", "binance"); executionValue.set("rows", executionRows);
        executionValue.set("lineage", executionLineage); executionValue = withHash(executionValue);
        SCHEMAS.validateKnownContractSchema(executionValue);
        Path executionPath = persistResearchJson(lineageRoot,
                "execution-" + text(executionValue, "content_sha256") + ".json", executionValue);
        byte[] executionBytes = readSinglyLinked(executionPath, "portfolio execution artifact");
        String executionByteSha = hash(executionBytes);
        ArrayNode stressScenarios = policyScenarios.deepCopy();
        if (stressScenarios.isEmpty()) {
            ObjectNode fallbackScenario = object().put("scenario_id", foldId + ":BASE")
                    .put("kind", "DOUBLED_COST").put("pass", false);
            fallbackScenario.set("parameters", object());
            fallbackScenario.set("limitations", strings(List.of("NO_PHYSICAL_STRESS_SCENARIO")));
            stressScenarios.add(fallbackScenario);
        }
        ObjectNode stressResult = object().put("schema", "strategy-portfolio-stress-result/1")
                .put("version", 1).put("provenance", "AUTHORITATIVE_RECOMPUTED")
                .put("selected_trades_sha256", text(selectedValue, "content_sha256"))
                .put("evaluation_sha256", text(evaluationValue, "content_sha256"))
                .put("execution_fills_sha256", executionByteSha).put("policy_sha256", scenarioPolicySha);
        stressResult.set("scenarios", stressScenarios); stressResult = withHash(stressResult);
        SCHEMAS.validateKnownContractSchema(stressResult);
        Path stressPath = persistResearchJson(lineageRoot,
                "stress-" + text(stressResult, "content_sha256") + ".json", stressResult);
        byte[] stressBytes = readSinglyLinked(stressPath, "portfolio stress artifact");

        ObjectNode policy = in.portfolioPolicyPhysical().value().deepCopy();
        if (policy.path("limits").isObject()) {
            ObjectNode flattened = ((ObjectNode) policy.path("limits")).deepCopy();
            policy.fields().forEachRemaining(entry -> {
                if (!"limits".equals(entry.getKey()) && !flattened.has(entry.getKey())) {
                    flattened.set(entry.getKey(), entry.getValue().deepCopy());
                }
            });
            policy = flattened;
        }
        if (!defined(policy.get("current_equity"))) policy.put("current_equity",
                policy.path("initial_equity").asDouble(Double.NaN));
        String defaultCutoff = firstText(in.planPhysical().value().path("window"), "end_at");
        String consuming = firstText(policy, "consuming_cutoff", "asOf");
        String asOf = firstText(policy, "asOf");
        policy.put("consuming_cutoff", consuming.isEmpty() ? defaultCutoff : consuming)
                .put("asOf", asOf.isEmpty() ? defaultCutoff : asOf)
                .put("venue", firstTextOr(policy, "binance", "venue"))
                .put("interval_ms", policy.path("interval_ms").asLong(3_600_000))
                .put("account_currency", firstTextOr(policy, "USDT", "account_currency"));
        if (policy.path("execution_fixture").asBoolean(false)
                || policy.path("allow_fixture_metadata").asBoolean(false)) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio policy enables fixture execution or metadata");
        }
        ObjectNode metadataRequest = object();
        putPortfolioMetadataBinding(metadataRequest, "fee", metadataPaths, metadataByteHashes);
        putPortfolioMetadataBinding(metadataRequest, "contract", metadataPaths, metadataByteHashes);
        putPortfolioMetadataBinding(metadataRequest, "margin", metadataPaths, metadataByteHashes);
        putPortfolioMetadataBinding(metadataRequest, "liquidation", metadataPaths, metadataByteHashes);
        putPortfolioMetadataBinding(metadataRequest, "expiry", metadataPaths, metadataByteHashes);
        putPortfolioMetadataBinding(metadataRequest, "funding", metadataPaths, metadataByteHashes);
        putPortfolioMetadataBinding(metadataRequest, "execution_model", metadataPaths, metadataByteHashes);
        ObjectNode request = object().put("markPath", in.markPhysical().path().toString())
                .put("markSha256", in.markPhysical().byteSha256())
                .put("selectedTradeArtifactPath", selectedPath.toString())
                .put("selectedTradeArtifactSha256", hash(selectedBytes))
                .put("evaluationArtifactPath", evaluationPath.toString())
                .put("evaluationArtifactSha256", hash(evaluationBytes))
                .put("executionArtifactPath", executionPath.toString())
                .put("executionArtifactSha256", executionByteSha)
                .put("stressArtifactPath", stressPath.toString())
                .put("stressArtifactSha256", hash(stressBytes));
        request.set("metadata", metadataRequest); request.set("requiredAssets", strings(selectedPhysical.stream()
                .map(PhysicalOutcomeRow::asset).distinct().sorted().toList())); request.set("policy", policy);
        ObjectNode risk;
        try { risk = StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(request); }
        catch (RuntimeException error) {
            String message = String.valueOf(error.getMessage()), lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("unavailable") || lower.contains("requires") || lower.contains("metadata")
                    || lower.contains("missing") || lower.contains("collateral") || lower.contains("artifact")) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio engine prerequisites are incomplete: " + message);
            }
            throw error;
        }
        if (!"AUTHORITATIVE_RECOMPUTED".equals(text(risk, "provenance"))
                || !text(risk, "content_sha256").equals(ownHash(risk))) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio engine did not return a physical authoritative artifact");
        }
        SCHEMAS.validateKnownContractSchema(risk);
        Path riskPath = persistResearchJson(lineageRoot,
                "portfolio-risk-" + text(risk, "content_sha256") + ".json", risk);
        PersistedResearchArtifact persisted = new PersistedResearchArtifact(risk, riskPath,
                hash(readSinglyLinked(riskPath, "portfolio risk artifact")));
        retained.put(foldId, persisted);
        ArrayNode riskAssets = array();
        for (JsonNode row : risk.path("asset_decisions")) {
            String status = text(row, "status"); ObjectNode decision = object().put("asset", text(row, "asset"))
                    .put("pass", Set.of("PASS", "NOT_SELECTED").contains(status));
            if (!status.isEmpty()) decision.put("reason", status); riskAssets.add(decision);
        }
        if (riskAssets.isEmpty()) for (JsonNode row : assetDecisions) riskAssets.add(object()
                .put("asset", text(row, "asset")).put("pass", false));
        ObjectNode decision = object().put("lineage_sha256", lineageSha)
                .put("sourceArtifactSha256", text(scoped, "content_sha256"))
                .put("riskDigest", text(risk, "content_sha256")).put("pass", risk.path("pass").asBoolean(false));
        decision.set("artifact", scoped); decision.set("assetDecisions", riskAssets);
        decision.set("returnIncrements", increments);
        return StrategyStatisticalV5.makePortfolioDecision(decision);
    }

    private static List<PhysicalOutcomeRow> researchPhysicalOutcomeInventory(
            ObjectNode scoped, String alias, Set<String> selectedEpisodeIds,
            Map<String, ObjectNode> observedEvaluations, Map<String, List<ObjectNode>> observedVectors,
            ResearchRunInputs in, Map<String, ObjectNode> featureByEpisode,
            Map<String, ObjectNode> labelByEpisode, Function<String, ObjectNode> resolveExecution) {
        ObjectNode evaluation = observedEvaluations.get(alias); Map<String, ObjectNode> observed = new LinkedHashMap<>();
        for (ObjectNode row : observedVectors.getOrDefault(alias, List.of())) {
            observed.put(text(row, "episode_id"), row);
        }
        if (evaluation == null || observed.isEmpty() || !evaluation.path("candidate_definition").isObject()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: selected behavior " + alias
                    + " has no physical evaluator evidence or frozen candidate definition");
        }
        ObjectNode candidate = bindResearchEvaluatorCandidate(in.evaluatorPhysical().value(),
                (ObjectNode) evaluation.path("candidate_definition"));
        List<PhysicalOutcomeRow> result = new ArrayList<>();
        for (JsonNode rawEpisode : scoped.path("episodes")) {
            String id = text(rawEpisode, "episode_id"); if (!selectedEpisodeIds.contains(id)) continue;
            ObjectNode expected = observed.get(id), feature = featureByEpisode.get(id), label = labelByEpisode.get(id);
            if (expected == null) throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical evaluator vector is missing selected episode " + id);
            if (!expected.path("traded").asBoolean(false)) {
                if (Double.compare(expected.path("net_r").asDouble(Double.NaN), 0d) != 0) {
                    throw failure("physical evaluator internal zero is non-zero for " + id);
                }
                result.add(new PhysicalOutcomeRow(id, lower(text(rawEpisode, "asset")), false, 0,
                        null, feature, label, null)); continue;
            }
            ObjectNode execution = resolveExecution.apply(id);
            if (feature == null || label == null || execution == null) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical selected episode " + id
                        + " lacks feature/label/execution rows");
            }
            ObjectNode outcome = deriveResearchOutcome(in.loaded(), in.envelopeByEpisode(), id,
                    feature, label, execution, candidate, null);
            if (!physicalMarksCover(outcome, in.markRows())) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical selected episode " + id
                        + " lacks exact MARK role entry/exit coverage");
            }
            double netR = outcome.path("net_r").asDouble(Double.NaN);
            if (!Double.isFinite(netR) || Double.compare(netR, expected.path("net_r").asDouble(Double.NaN)) != 0
                    || !outcome.path("traded").asBoolean(true)) {
                throw failure("physical selected-fill recomputation differs from evaluator output for " + id);
            }
            result.add(new PhysicalOutcomeRow(id, lower(text(rawEpisode, "asset")), true,
                    netR, outcome, feature, label, execution));
        }
        if (result.size() != selectedEpisodeIds.size()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: scoped portfolio artifact omits a selected return episode");
        }
        return result;
    }

    private static JsonNode firstDefinedResearch(JsonNode... values) {
        if (values != null) for (JsonNode value : values) if (defined(value)) return value;
        return NODES.missingNode();
    }

    private static Path persistResearchJson(Path root, String name, ObjectNode value) {
        Path path = root.resolve(name); writeImmutable(path, value); return absolute(path);
    }

    private static Path persistResearchBytes(Path root, String name, byte[] value) {
        Path path = root.resolve(name); writeTextImmutable(path, value); return absolute(path);
    }

    private static void putPortfolioMetadataBinding(ObjectNode request, String key,
                                                    Map<String, Path> paths,
                                                    Map<String, String> byteHashes) {
        String prefix = switch (key) {
            case "execution_model" -> "executionModel";
            default -> key;
        };
        request.put(prefix + "ArtifactPath", paths.get(key).toString());
        request.put(prefix + "ArtifactSha256", byteHashes.get(key));
    }

    private static ObjectNode researchStressDecision(
            ObjectNode args, ResearchRunInputs in, Map<String, ObjectNode> definitions,
            Map<String, ObjectNode> featureByEpisode, Map<String, ObjectNode> labelByEpisode,
            Function<String, ObjectNode> resolveExecution,
            Map<String, PersistedResearchArtifact> retained, Path stageRoot) {
        ObjectNode scoped = requiredObject(args, "artifact");
        String alias = text(args, "selected_candidate_id"), lineageSha = text(args, "lineage_sha256");
        requireSha(alias, "stress selected behavior alias"); requireSha(lineageSha, "stress lineage");
        ObjectNode registered = definitions.get(alias);
        if (registered == null || !registered.path("chromosome").isObject()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: stress behavior definition is not physically registered: "
                    + alias);
        }
        ObjectNode evaluator = in.evaluatorPhysical().value();
        ObjectNode expectedDefinition = object().put("schema", "strategy-v5-statistical-behavior-definition/1");
        expectedDefinition.set("chromosome", StrategyStatisticalV5.effectiveExecutionBehavior(
                registered.path("chromosome")));
        expectedDefinition.set("evaluator_sha256", nullableResearchField(registered, "evaluator_sha256"));
        expectedDefinition.set("precommit_sha256", nullableResearchField(registered, "precommit_sha256"));
        expectedDefinition.set("lifecycle_sha256", nullableResearchField(registered, "lifecycle_sha256"));
        if (!hash(expectedDefinition).equals(text(registered, "definition_sha256"))) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: stress behavior definition registry hash mismatch for "
                    + alias);
        }
        ObjectNode candidate = bindResearchEvaluatorCandidate(evaluator, (ObjectNode) registered.path("chromosome"));
        ObjectNode roleHashes = object();
        for (String role : List.of("feature", "label", "execution", "mark")) {
            roleHashes.put(role, requireSha(text(in.manifestPhysical().value().path("artifacts").path(role), "sha256"),
                    "manifest " + role + " byte hash"));
        }
        ObjectNode roleIdentity = object(); roleIdentity.set("roleHashes", roleHashes);
        roleIdentity.put("feature_rows", hash(array(in.featureRows())))
                .put("label_rows", hash(array(in.labelRows())))
                .put("execution_rows", hash(array(in.executionRows())))
                .put("mark_rows", hash(array(in.markRows())));
        String sourceRoleIdentity = hash(roleIdentity);
        Path executionSource = repositoryRoot().resolve(
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/StrategyResearchDataV5.java");
        byte[] executionCodeBytes = readSinglyLinked(executionSource, "Java physical execution engine source");
        String executionCodeSha = hash(executionCodeBytes);
        List<String> episodeIds = objects(scoped.path("episodes")).stream()
                .map(row -> text(row, "episode_id")).toList();
        if (episodeIds.isEmpty() || new HashSet<>(episodeIds).size() != episodeIds.size()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: stress episode inventory is empty or duplicated");
        }
        Map<String, PhysicalOutcomeRow> baseline = new LinkedHashMap<>();
        for (String id : episodeIds) {
            ObjectNode feature = featureByEpisode.get(id), label = labelByEpisode.get(id);
            ObjectNode execution = resolveExecution.apply(id); ObjectNode episode = findEpisode(scoped, id);
            if (feature == null || label == null || execution == null || episode == null) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: baseline stress episode " + id
                        + " lacks an exact physical role inventory");
            }
            boolean intent = episode.path("eligible").asBoolean(true)
                    && feature.path("signal_eligible").asBoolean(true)
                    && StrategyEvaluatorV5.evaluateSignalPredicateV5(evaluator.path("predicate"), feature,
                    registered.path("chromosome"));
            if (!intent) {
                baseline.put(id, new PhysicalOutcomeRow(id, lower(text(feature, "asset")), false, 0,
                        null, feature, label, execution));
                continue;
            }
            ObjectNode outcome = deriveResearchOutcome(in.loaded(), in.envelopeByEpisode(), id,
                    feature, label, execution, candidate, null);
            if (!physicalMarksCover(outcome, in.markRows())) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: baseline stress fill " + id
                        + " lacks exact MARK role coverage");
            }
            baseline.put(id, new PhysicalOutcomeRow(id, lower(text(feature, "asset")), true,
                    outcome.path("net_r").asDouble(), outcome, feature, label, execution));
        }

        ArrayNode scenarioRows = array(); int scenarioOrdinal = 0;
        for (JsonNode rawScenario : in.stressContract().path("scenarios")) {
            ObjectNode frozenScenario = (ObjectNode) rawScenario; String id = text(frozenScenario, "id");
            ObjectNode parameters = frozenScenario.path("parameters").isObject()
                    ? (ObjectNode) frozenScenario.path("parameters") : object();
            requireStressScenarioParameters(id, parameters);
            ArrayNode fills = array(), limitations = array(); boolean derivativeApplicable = false;
            for (String episodeId : episodeIds) {
                PhysicalOutcomeRow base = baseline.get(episodeId); ObjectNode execution = base.execution();
                String instrument = normalizedResearchInstrument(firstText(execution, "instrument", "instrument_type"));
                boolean applies = switch (id) {
                    case "FUNDING" -> "BINANCE_USDM_PERPETUAL".equals(instrument);
                    case "EXPIRY" -> "BINANCE_USDM_DATED_FUTURE".equals(instrument);
                    case "LIQUIDATION" -> !"BINANCE_SPOT".equals(instrument);
                    default -> true;
                };
                if (Set.of("FUNDING", "EXPIRY", "LIQUIDATION").contains(id) && applies) {
                    derivativeApplicable = true;
                }
                if (!applies || !base.traded() || ablatedStressEpisode(id, parameters, base)) {
                    fills.add(stressFill(base, false, 0, null,
                            !applies ? id + "_NOT_APPLICABLE" : !base.traded() ? "NO_SIGNAL" : "ABLATION"));
                    continue;
                }
                ObjectNode scenarioFeature = base.feature().deepCopy();
                ObjectNode scenarioLabel = base.label().deepCopy();
                ObjectNode scenarioExecution = base.execution().deepCopy();
                ObjectNode scenarioCandidate = candidate.deepCopy(); ObjectNode overrides = null;
                switch (id) {
                    case "DOUBLED_COST" -> overrides = doubledCostMetadata(in.metadataPhysical().value(), parameters);
                    case "LIQUIDITY" -> overrides = liquidityStressMetadata(in.metadataPhysical().value(), parameters,
                            scenarioExecution);
                    case "DELAYED_ENTRY" -> applyDelayedEntryScenario(scenarioLabel, scenarioCandidate,
                            scenarioExecution, parameters);
                    case "ADVERSE_COLLISION" -> applyTargetStopScenario(scenarioCandidate, scenarioExecution,
                            parameters, false);
                    case "GAP" -> {
                        applyTargetStopScenario(scenarioCandidate, scenarioExecution, parameters, true);
                        overrides = gapStressMetadata(in.metadataPhysical().value());
                    }
                    case "CAPACITY" -> { /* exact penalty is applied after the base lifecycle rerun below */ }
                    case "OUTAGE" -> {
                        if (outageSuppresses(parameters, scenarioFeature, scenarioExecution)) {
                            fills.add(stressFill(base, false, 0, null, "BOUND_OUTAGE_BLACKOUT"));
                            continue;
                        }
                    }
                    case "FUNDING" -> throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: derivative FUNDING stress requires a loader-owned funding-metadata override capability");
                    case "EXPIRY" -> applyExpiryStressScenario(in.metadataPhysical().value(), scenarioLabel,
                            scenarioExecution, limitations);
                    case "LIQUIDATION" -> applyLiquidationStressScenario(scenarioExecution, parameters);
                    default -> { }
                }
                ObjectNode outcome = deriveResearchOutcome(in.loaded(), in.envelopeByEpisode(), episodeId,
                        scenarioFeature, scenarioLabel, scenarioExecution, scenarioCandidate, overrides);
                double netR = outcome.path("net_r").asDouble();
                if ("CAPACITY".equals(id)) netR = capacityStressReturn(scenarioExecution, parameters, netR);
                fills.add(stressFill(base, true, netR, outcome, null));
            }
            boolean inapplicable = Set.of("FUNDING", "EXPIRY", "LIQUIDATION").contains(id)
                    && !derivativeApplicable;
            if (inapplicable) limitations.add("NOT_APPLICABLE_SPOT_ONLY_INVENTORY");
            if ("LEAVE_ONE_ASSET".equals(id) && baseline.values().stream().map(PhysicalOutcomeRow::asset)
                    .distinct().count() <= 1) {
                limitations.add("NOT_APPLICABLE_SINGLE_TRADE_ASSET"); inapplicable = true;
            }
            ObjectNode metrics = researchStressMetrics(fills, parameters, in.constraints(),
                    in.stressContract().path("resampling"), scenarioOrdinal++, inapplicable);
            ObjectNode scenario = object().put("id", id).put("selected_candidate_id", alias)
                    .put("definition_sha256", text(registered, "definition_sha256"));
            scenario.set("source_role_hashes", roleHashes.deepCopy());
            scenario.put("source_role_identity_sha256", sourceRoleIdentity)
                    .put("execution_code_sha256", executionCodeSha)
                    .put("stress_contract_sha256", text(in.stressContract(), "content_sha256"))
                    .put("parameters_sha256", hash(parameters));
            scenario.set("fills", fills); scenario.put("fill_vector_sha256", hash(fills));
            scenario.set("metrics", metrics); scenario.put("pass", metrics.path("pass").asBoolean(false));
            scenario.set("limitations", limitations); scenario.put("digest", hash(scenario)); scenarioRows.add(scenario);
        }
        ObjectNode executionArtifact = object().put("schema", "strategy-v5-authoritative-stress-execution/1")
                .put("version", 1).put("status", "COMPLETE").put("provenance", "AUTHORITATIVE_RECOMPUTED")
                .put("source_manifest_sha256", text(in.manifestPhysical().value(), "content_sha256"));
        executionArtifact.set("source_role_hashes", roleHashes); executionArtifact
                .put("source_role_identity_sha256", sourceRoleIdentity)
                .put("execution_code_sha256", executionCodeSha)
                .put("stress_contract_sha256", text(in.stressContract(), "content_sha256"))
                .put("selected_candidate_id", alias).put("definition_sha256", text(registered, "definition_sha256"))
                .put("lineage_sha256", lineageSha).set("scenarios", scenarioRows);
        executionArtifact = withHash(executionArtifact); SCHEMAS.validateKnownContractSchema(executionArtifact);
        Path path = stageRoot.resolve("stress-execution").resolve("stress-"
                + text(executionArtifact, "content_sha256") + ".json"); writeImmutable(path, executionArtifact);
        PersistedResearchArtifact persisted = new PersistedResearchArtifact(executionArtifact, path,
                hash(readSinglyLinked(path, "persisted stress execution")));
        retained.put(lineageSha + "|" + alias, persisted);
        ArrayNode compactScenarios = array(); boolean pass = true;
        for (JsonNode row : scenarioRows) {
            compactScenarios.add(object().put("id", text(row, "id")).put("pass", row.path("pass").asBoolean(false))
                    .put("digest", text(row, "digest")));
            pass &= row.path("pass").asBoolean(false);
        }
        ObjectNode decision = object().put("lineage_sha256", lineageSha).put("pass", pass)
                .put("sourceArtifactSha256", text(scoped, "content_sha256"))
                .put("selectedCandidateId", alias); decision.set("scenarios", compactScenarios);
        return StrategyStatisticalV5.makeStressDecision(decision);
    }

    private static ObjectNode findEpisode(ObjectNode artifact, String id) {
        for (JsonNode row : artifact.path("episodes")) if (id.equals(text(row, "episode_id"))) return (ObjectNode) row;
        return null;
    }

    private static ObjectNode bindResearchEvaluatorCandidate(ObjectNode evaluator, ObjectNode chromosome) {
        ObjectNode candidate = (ObjectNode) resolveResearchTemplate(evaluator.path("candidate_template"), chromosome);
        ObjectNode contract = evaluator.path("execution_contract").isObject()
                ? (ObjectNode) evaluator.path("execution_contract") : object();
        candidate.put("decision_timestamp_convention", firstTextOr(contract,
                "COMPLETED_4H_BOUNDARY", "decision_timestamp_convention"));
        candidate.put("decision_timeframe", firstTextOr(contract, "4h", "decision_timeframe"));
        for (String[] binding : List.of(new String[] {"risk_convention", "risk_contract"},
                new String[] {"sizing_contract", "sizing_contract"},
                new String[] {"derivative_policy", "derivative_policy"})) {
            if (!contract.path(binding[0]).isObject()) continue;
            ObjectNode value = ((ObjectNode) contract.path(binding[0])).deepCopy();
            value.put("evaluator_spec_sha256", text(evaluator, "content_sha256")); candidate.set(binding[1], value);
        }
        return candidate;
    }

    private static JsonNode resolveResearchTemplate(JsonNode value, JsonNode chromosome) {
        if (value == null || value.isMissingNode()) return NullNode.instance;
        if (value.isArray()) { ArrayNode result = array(); for (JsonNode row : value) result.add(resolveResearchTemplate(row, chromosome)); return result; }
        if (!value.isObject()) return value.deepCopy();
        if (value.size() == 1 && value.path("$gene").isTextual()) {
            String name = value.path("$gene").asText();
            if (!defined(chromosome.get(name))) throw failure("chromosome is missing gene " + name);
            return chromosome.get(name).deepCopy();
        }
        ObjectNode result = object(); value.fields().forEachRemaining(entry ->
                result.set(entry.getKey(), resolveResearchTemplate(entry.getValue(), chromosome))); return result;
    }

    private static ObjectNode deriveResearchOutcome(StrategyEvaluatorV5.LoadedEvaluator loaded,
                                                     ObjectNode envelopeByEpisode, String episodeId,
                                                     ObjectNode feature, ObjectNode label, ObjectNode execution,
                                                     ObjectNode candidate, ObjectNode metadataOverrides) {
        ObjectNode request = object(); request.set("feature", feature); request.set("label", label);
        request.set("execution", execution); request.set("candidate", candidate);
        JsonNode window = envelopeByEpisode.get(episodeId);
        if (window != null) request.set("envelopeWindow", window.deepCopy());
        ObjectNode outcome = metadataOverrides == null
                ? loaded.deriveBoundExecutionOutcome(request)
                : loaded.deriveBoundStressExecutionOutcome(request, metadataOverrides);
        outcome.put("episode_id", episodeId).put("signal_id", text(feature, "signal_id"))
                .put("symbol", firstTextOr(feature, firstTextOr(execution,
                        firstTextOr(label, text(feature, "asset").toUpperCase(Locale.ROOT) + "USDT", "symbol"), "symbol"), "symbol").toUpperCase(Locale.ROOT))
                .put("venue", firstTextOr(feature, firstTextOr(execution,
                        firstTextOr(label, "BINANCE", "venue"), "venue"), "venue").toUpperCase(Locale.ROOT))
                .put("reason", text(outcome, "exit_reason"));
        return outcome;
    }

    private static ObjectNode stressFill(PhysicalOutcomeRow base, boolean traded, double netR,
                                         ObjectNode outcome, String reason) {
        ObjectNode row = object().put("episode_id", base.episodeId()).put("asset", base.asset())
                .put("traded", traded).put("net_r", netR).put("physical_coverage", true);
        if (outcome == null) { row.putNull("outcome"); row.putNull("outcome_sha256"); }
        else { ObjectNode compact = compactResearchOutcome(outcome); row.set("outcome", compact); row.put("outcome_sha256", hash(compact)); }
        if (reason == null) row.putNull("reason"); else row.put("reason", reason); return row;
    }

    private static ObjectNode compactResearchOutcome(ObjectNode outcome) {
        List<String> fields = List.of("episode_id", "asset", "instrument", "venue", "symbol", "direction",
                "quantity", "entry_time", "entry_price", "exit_time", "exit_price", "gross_pnl_usd",
                "fees_usd", "slippage_usd", "capacity_debit_usd", "funding_pnl_usd", "net_pnl_usd",
                "risk_amount_usd", "net_r", "exit_reason", "gap_fill", "funding_settlements",
                "execution_model", "liquidation_model", "collateral_used", "margin_mode", "leverage", "tier_id");
        ObjectNode compact = object(); for (String field : fields) if (outcome.has(field)) compact.set(field, outcome.get(field).deepCopy());
        return compact;
    }

    private static JsonNode nullableResearchField(JsonNode value, String name) {
        return defined(value.get(name)) ? value.get(name).deepCopy() : NullNode.instance;
    }

    private static void requireStressScenarioParameters(String id, ObjectNode parameters) {
        switch (id) {
            case "DOUBLED_COST" -> requireStressParameter(parameters, List.of("multiplier"),
                    "DOUBLED_COST stress lacks a frozen cost multiplier");
            case "DELAYED_ENTRY" -> requireStressParameter(parameters, List.of("delay_bars", "entry_delay_bars"),
                    "DELAYED_ENTRY stress lacks a frozen delay bar count");
            case "ADVERSE_COLLISION" -> {
                requireStressParameter(parameters, List.of("stop_price"),
                        "ADVERSE_COLLISION stress lacks a frozen stop price");
                requireStressParameter(parameters, List.of("target_price"),
                        "ADVERSE_COLLISION stress lacks a frozen target price");
            }
            case "OUTAGE" -> requireStressParameter(parameters, List.of("outage_rule"),
                    "OUTAGE stress lacks a frozen outage rule");
            case "CAPACITY" -> requireStressParameter(parameters, List.of("maximum_participation_rate"),
                    "CAPACITY stress lacks a frozen participation cap");
            case "LIQUIDITY" -> {
                requireStressParameter(parameters, List.of("liquidity_model"),
                        "LIQUIDITY stress lacks a frozen liquidity model");
                requireStressParameter(parameters, List.of("liquidity_impact_bps"),
                        "LIQUIDITY stress lacks a frozen liquidity impact");
                if (!(parameters.path("liquidity_impact_bps").asDouble(Double.NaN) > 0)) {
                    throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen liquidity impact must be positive");
                }
            }
            case "FUNDING" -> requireStressParameter(parameters, List.of("funding_multiplier", "multiplier"),
                    "FUNDING stress lacks a frozen funding multiplier");
            case "EXPIRY" -> requireStressParameter(parameters, List.of("expiry_policy"),
                    "EXPIRY stress lacks a frozen expiry policy");
            case "LIQUIDATION" -> {
                requireStressParameter(parameters, List.of("liquidation_rule"),
                        "LIQUIDATION stress lacks a frozen liquidation rule");
                requireStressParameter(parameters, List.of("adverse_move_bps"),
                        "LIQUIDATION stress lacks a frozen adverse move");
            }
            case "LEAVE_ONE_ASSET" -> requireStressParameter(parameters, List.of("exclude_asset", "asset", "exclude_value"),
                    "LEAVE_ONE_ASSET stress lacks a frozen excluded asset");
            case "LEAVE_ONE_REGIME" -> requireStressParameter(parameters, List.of("field"),
                    "LEAVE_ONE_REGIME stress lacks a frozen field");
            case "LEAVE_ONE_CONTEXT" -> requireStressParameter(parameters, List.of("evidence_leg", "field"),
                    "LEAVE_ONE_CONTEXT stress lacks a frozen evidence leg");
            default -> { }
        }
    }

    private static void requireStressParameter(ObjectNode parameters, List<String> names, String message) {
        for (String name : names) if (defined(parameters.get(name))) return;
        throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: " + message);
    }

    private static String normalizedResearchInstrument(String raw) {
        String value = raw == null ? "" : raw.toUpperCase(Locale.ROOT);
        return switch (value) {
            case "SPOT", "BINANCE_SPOT" -> "BINANCE_SPOT";
            case "PERPETUAL", "PERP", "BINANCE_USDM_PERPETUAL" -> "BINANCE_USDM_PERPETUAL";
            case "DATED_FUTURE", "FUTURE", "FUTURES", "BINANCE_USDM_DATED_FUTURE" -> "BINANCE_USDM_DATED_FUTURE";
            default -> value;
        };
    }

    private static boolean ablatedStressEpisode(String id, ObjectNode parameters, PhysicalOutcomeRow row) {
        if ("LEAVE_ONE_ASSET".equals(id)) {
            String excluded = firstTruthyText(parameters, "exclude_asset", "asset", "exclude_value");
            return row.asset().equals(lower(excluded));
        }
        if ("LEAVE_ONE_REGIME".equals(id)) {
            String field = text(parameters, "field"), excluded = firstTruthyText(parameters, "exclude_value", "value");
            if (field.isEmpty() || excluded.isEmpty()) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: LEAVE_ONE_REGIME lacks a frozen field/value pair");
            }
            return excluded.equals(row.feature().path(field).asText());
        }
        if ("LEAVE_ONE_CONTEXT".equals(id)) {
            String field = firstTruthyText(parameters, "evidence_leg", "field");
            if (field.isEmpty()) throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: LEAVE_ONE_CONTEXT lacks a frozen evidence leg");
            return row.feature().path(field).asBoolean(false);
        }
        return false;
    }

    private static ObjectNode doubledCostMetadata(ObjectNode metadata, ObjectNode parameters) {
        double multiplier = parameters.path("multiplier").asDouble(Double.NaN);
        if (!(multiplier >= 1)) throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen doubled-cost multiplier is invalid");
        ObjectNode overrides = object(); ObjectNode fees = requireMetadataReceipt(metadata, "fee_schedule");
        ObjectNode model = requireMetadataReceipt(metadata, "execution_model");
        scaleMetadataFields(fees, List.of("taker_fee_rate"), parameters.path("fee_multiplier").asDouble(multiplier));
        scaleMetadataFields(model, List.of("slippage_bps", "impact_bps"),
                parameters.path("slippage_multiplier").asDouble(multiplier));
        if (defined(parameters.get("impact_multiplier"))) {
            scaleMetadataFields(model, List.of("impact_bps"), parameters.path("impact_multiplier").asDouble());
        }
        overrides.set("fee_schedule", fees); overrides.set("execution_model", model); return overrides;
    }

    private static ObjectNode liquidityStressMetadata(ObjectNode metadata, ObjectNode parameters,
                                                       ObjectNode execution) {
        ObjectNode model = requireMetadataReceipt(metadata, "execution_model");
        double impact = parameters.path("liquidity_impact_bps").asDouble(Double.NaN);
        JsonNode liquidity = defined(execution.get("liquidity_inputs"))
                ? execution.get("liquidity_inputs") : execution.get("capacity_inputs");
        if (liquidity != null) impact += firstDefinedNumber(liquidity, "observed_impact_bps", "impact_bps");
        if (!(impact > 0)) throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: LIQUIDITY stress has no physically bound impact perturbation");
        String asset = lower(text(execution, "asset")), venue = text(execution, "venue").toUpperCase(Locale.ROOT);
        String instrument = normalizedResearchInstrument(text(execution, "instrument"));
        String symbol = text(execution, "symbol").toUpperCase(Locale.ROOT); int matches = 0;
        for (JsonNode raw : model.path("records")) {
            ObjectNode row = (ObjectNode) raw;
            if (asset.equals(lower(text(row, "asset"))) && venue.equals(text(row, "venue").toUpperCase(Locale.ROOT))
                    && instrument.equals(normalizedResearchInstrument(text(row, "instrument")))
                    && symbol.equals(text(row, "symbol").toUpperCase(Locale.ROOT))) {
                row.put("impact_bps", row.path("impact_bps").asDouble() + impact); matches++;
            }
        }
        if (matches != 1) throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: LIQUIDITY stress execution-model identity is ambiguous");
        model.put("content_sha256", ownHash(model)); ObjectNode overrides = object();
        overrides.set("execution_model", model); return overrides;
    }

    private static ObjectNode gapStressMetadata(ObjectNode metadata) {
        ObjectNode model = requireMetadataReceipt(metadata, "execution_model");
        for (JsonNode raw : model.path("records")) ((ObjectNode) raw).put("gap_policy", "FILL_AT_OPEN");
        model.put("content_sha256", ownHash(model)); ObjectNode overrides = object();
        overrides.set("execution_model", model); return overrides;
    }

    private static ObjectNode requireMetadataReceipt(ObjectNode metadata, String name) {
        if (!(metadata.path(name) instanceof ObjectNode receipt) || !receipt.path("records").isArray()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: stress requires physical " + name + " records");
        }
        return receipt.deepCopy();
    }

    private static void scaleMetadataFields(ObjectNode receipt, List<String> fields, double factor) {
        if (!Double.isFinite(factor) || factor < 0) throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: stress metadata multiplier is invalid");
        for (JsonNode raw : receipt.path("records")) {
            ObjectNode row = (ObjectNode) raw;
            for (String field : fields) if (defined(row.get(field))) row.put(field, row.path(field).asDouble() * factor);
        }
        receipt.put("content_sha256", ownHash(receipt));
    }

    private static double firstDefinedNumber(JsonNode value, String... names) {
        for (String name : names) if (defined(value.get(name))) return value.get(name).asDouble(); return 0;
    }

    private static void applyDelayedEntryScenario(ObjectNode label, ObjectNode candidate,
                                                  ObjectNode execution, ObjectNode parameters) {
        int delay = parameters.has("delay_bars") ? parameters.path("delay_bars").asInt()
                : parameters.path("entry_delay_bars").asInt();
        if (delay < 1) throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen delayed-entry bar count is invalid");
        long decision = timestampOrMin(first(execution, "decision_time", "entry_time"));
        long expected = decision + delay * 60_000L; JsonNode entryBar = null;
        for (JsonNode row : execution.path("child_bars")) {
            if (timestampOrMin(first(row, "event_time", "time", "open_time")) == expected) { entryBar = row; break; }
        }
        if (entryBar == null) throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: delayed-entry scenario lacks the exact later contiguous child-bar timestamp");
        label.put("entry_time", ISO_MILLIS.format(Instant.ofEpochMilli(expected)));
        candidate.put("entry_policy", "DELAYED_BAR_OPEN").put("entry_delay_bars", delay);
        if (candidate.path("lifecycle").isObject()) {
            ObjectNode lifecycle = ((ObjectNode) candidate.path("lifecycle")).deepCopy();
            lifecycle.put("max_lifecycle_ms", Math.max(60_000,
                    lifecycle.path("max_lifecycle_ms").asLong(60_000) - delay * 60_000L));
            candidate.set("lifecycle", lifecycle);
        }
    }

    private static void applyTargetStopScenario(ObjectNode candidate, ObjectNode execution,
                                                ObjectNode parameters, boolean gap) {
        double stop = parameters.path("stop_price").asDouble(Double.NaN);
        double target = parameters.path("target_price").asDouble(Double.NaN);
        JsonNode existing = candidate.path("exit_policy");
        if (!(stop > 0)) stop = existing.path("stop_price").asDouble(Double.NaN);
        if (!(target > 0)) target = existing.path("target_price").asDouble(Double.NaN);
        if (!(stop > 0) || !(target > 0)) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen collision/gap stress lacks exact stop and target prices");
        }
        ObjectNode exit = object().put("type", "TARGET_STOP").put("stop_price", stop)
                .put("target_price", target).put("collision_policy",
                        firstTextOr(parameters, "ADVERSE_STOP_FIRST", "collision_policy").toUpperCase(Locale.ROOT));
        candidate.remove("risk_amount_usd"); candidate.set("exit_policy", exit);
        JsonNode lifeNode = candidate.path("lifecycle").isObject() ? candidate.path("lifecycle")
                : candidate.path("lifecycle_spec");
        if (lifeNode.isObject()) {
            double entry = execution.path("child_bars").path(0).path("open").asDouble(Double.NaN);
            if (!(entry > stop)) throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: adverse collision stop is not below the physical entry");
            ObjectNode life = ((ObjectNode) lifeNode).deepCopy();
            life.set("stop", object().put("type", "PERCENT").put("value", 1 - stop / entry));
            life.set("target", object().put("type", "R_MULTIPLE").put("multiple", 1));
            life.set("partial_exits", array()); life.putNull("trailing"); candidate.set("lifecycle", life);
        }
        if (gap && parameters.path("historical_gap_set").isArray()) {
            // The exact gap remains in the bound child bars; no synthetic bar is introduced.
            execution.put("gap_stress_bound", true);
        }
    }

    private static boolean outageSuppresses(ObjectNode parameters, ObjectNode feature, ObjectNode execution) {
        JsonNode windows = parameters.path("blackout_windows");
        if (!windows.isArray() || windows.isEmpty()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: outage stress lacks frozen blackout windows");
        }
        long start = timestampOrMin(first(execution, "decision_time", "entry_time"));
        JsonNode last = execution.path("child_bars").path(execution.path("child_bars").size() - 1);
        long end = timestampOrMin(first(last, "event_time", "time", "open_time"));
        for (JsonNode window : windows) {
            long from = timestampOrMin(first(window, "start_time")), to = timestampOrMin(first(window, "end_time"));
            if (from == Long.MIN_VALUE || to == Long.MIN_VALUE || to <= from) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: outage blackout window is invalid");
            }
            String venue = text(window, "venue").toUpperCase(Locale.ROOT);
            if ((venue.isEmpty() || venue.equals(firstTextOr(execution,
                    firstTextOr(feature, "BINANCE", "venue"), "venue").toUpperCase(Locale.ROOT)))
                    && start < to && end >= from) return true;
        }
        return false;
    }

    private static double capacityStressReturn(ObjectNode execution, ObjectNode parameters, double netR) {
        JsonNode capacity = defined(execution.get("capacity_inputs"))
                ? execution.get("capacity_inputs") : execution.get("liquidity_inputs");
        if (capacity == null || !capacity.isObject()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: CAPACITY stress lacks physical capacity inputs");
        }
        double liquidity = capacity.path("available_liquidity_usd").asDouble(Double.NaN);
        double order = capacity.path("order_notional_usd").asDouble(Double.NaN);
        double cap = parameters.path("maximum_participation_rate").asDouble(Double.NaN);
        if (!(liquidity > 0) || !(order > 0) || !(cap > 0 && cap <= 1)) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: CAPACITY stress physical inputs are invalid");
        }
        return order <= liquidity * cap ? netR : netR - Math.abs(netR);
    }

    private static void applyLiquidationStressScenario(ObjectNode execution, ObjectNode parameters) {
        double bps = parameters.path("adverse_move_bps").asDouble(Double.NaN);
        if (!(bps > 0) || !execution.path("mark_bars").isArray() || execution.path("mark_bars").isEmpty()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: dynamic liquidation requires a frozen adverse move and physical mark bars");
        }
        double factor = bps / 10_000; ArrayNode marks = array();
        for (JsonNode raw : execution.path("mark_bars")) {
            ObjectNode row = ((ObjectNode) raw).deepCopy();
            double low = firstDefinedNumber(row, "mark_low", "low"), high = firstDefinedNumber(row, "mark_high", "high");
            double close = firstDefinedNumber(row, "mark_close", "close");
            if (!(low > 0) || !(high > 0) || !(close > 0)) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: dynamic liquidation mark schema is incomplete");
            }
            row.put("mark_low", low * (1 - factor)).put("mark_high", high * (1 + factor))
                    .put("mark_close", close * (1 - factor)); marks.add(row);
        }
        execution.set("mark_bars", marks);
    }

    private static void applyExpiryStressScenario(ObjectNode metadata, ObjectNode label,
                                                  ObjectNode execution, ArrayNode limitations) {
        ObjectNode options = object(); options.set("metadata", metadata); options.set("execution", execution);
        options.set("label", label); ObjectNode settlement = resolveDatedSettlementForStress(options);
        long settlementAt = settlement.path("settlementAt").asLong();
        long availableAt = settlement.path("settlementAvailableAt").asLong();
        double price = settlement.path("settlementPrice").asDouble(); String source = text(settlement, "settlementSource");
        ArrayNode bars = array(); long lastAt = Long.MIN_VALUE;
        for (JsonNode raw : execution.path("child_bars")) {
            long at = timestampOrMin(first(raw, "event_time", "time", "open_time"));
            if (at <= settlement.path("expiryAt").asLong()) { bars.add(raw.deepCopy()); lastAt = at; }
        }
        if (lastAt == Long.MIN_VALUE || settlementAt != lastAt + 60_000L) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: official settlement is not the next contiguous lifecycle observation; no synthetic bars may be inserted");
        }
        ObjectNode official = object().put("event_time", ISO_MILLIS.format(Instant.ofEpochMilli(settlementAt)))
                .put("open", price).put("high", price).put("low", price).put("close", price)
                .put("availability_time", ISO_MILLIS.format(Instant.ofEpochMilli(availableAt)))
                .put("settlement_event_id", text(settlement.path("settlementRecord"), "settlement_mark_event_id"))
                .put("settlement_source_sha256", source).put("physical_settlement", true);
        bars.add(official); execution.set("child_bars", bars);
        label.put("resolution_time", ISO_MILLIS.format(Instant.ofEpochMilli(settlementAt)))
                .put("resolution_ceiling_time", ISO_MILLIS.format(Instant.ofEpochMilli(settlementAt)));
        limitations.add("SETTLEMENT_SOURCE:" + source);
    }

    private static boolean physicalMarksCover(ObjectNode outcome, List<ObjectNode> marks) {
        String asset = lower(text(outcome, "asset")), venue = text(outcome, "venue").toUpperCase(Locale.ROOT);
        String instrument = normalizedResearchInstrument(text(outcome, "instrument"));
        String symbol = text(outcome, "symbol").toUpperCase(Locale.ROOT);
        for (String field : List.of("entry_time", "exit_time")) {
            long target = timestampOrMin(outcome.get(field)); boolean covered = false;
            for (ObjectNode mark : marks) {
                if (!asset.equals(lower(text(mark, "asset"))) || !venue.equals(text(mark, "venue").toUpperCase(Locale.ROOT))
                        || !instrument.equals(normalizedResearchInstrument(text(mark, "instrument").replace("_MARK", "")))
                        || !symbol.equals(text(mark, "symbol").toUpperCase(Locale.ROOT))) continue;
                long event = timestampOrMin(first(mark, "event_time", "time", "open_time"));
                long available = timestampOrMin(first(mark, "availability_time", "close_time", "event_time"));
                long cadence = mark.path("cadence_ms").asLong();
                if (cadence > 0 && event <= target && target <= event + cadence
                        && available >= event + cadence - 1_000) { covered = true; break; }
            }
            if (!covered) return false;
        }
        return true;
    }

    /**
     * The separated-data contract deliberately makes the MARK role empty for a spot-only
     * opportunity domain.  Research-run nevertheless requires the independently bound
     * authoritative portfolio mark artifact.  Normalize that artifact's physical rows into
     * the execution-custody identity used by the lifecycle checks; never synthesize prices or
     * timestamps, and retain any derivative MARK-role rows when they are physically present.
     */
    private static List<ObjectNode> authoritativeResearchMarkRows(List<ObjectNode> roleRows,
                                                                  ObjectNode markArtifact) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode row : roleRows) result.add(row.deepCopy());
        String venue = firstTextOr(markArtifact, "BINANCE", "venue").toUpperCase(Locale.ROOT);
        long cadence = markArtifact.path("interval_ms").asLong(0);
        if (cadence <= 0 || !markArtifact.path("rows").isArray()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: authoritative mark artifact lacks a physical cadence/row inventory");
        }
        for (JsonNode raw : markArtifact.path("rows")) {
            if (!(raw instanceof ObjectNode row)) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: authoritative mark artifact contains a non-object row");
            }
            String seriesType = text(row, "series_type").toUpperCase(Locale.ROOT);
            if (!Set.of("TRADE_MARK", "MARK").contains(seriesType)) continue;
            ObjectNode normalized = row.deepCopy();
            normalized.put("venue", firstTextOr(normalized, venue, "venue"));
            normalized.put("instrument", firstTextOr(normalized, "BINANCE_SPOT", "instrument"));
            normalized.put("cadence_ms", cadence);
            result.add(normalized);
        }
        if (result.isEmpty()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: no exact physical trade marks are available");
        }
        return result;
    }

    private static ObjectNode researchStressMetrics(ArrayNode fills, ObjectNode parameters,
                                                    ObjectNode constraints, JsonNode resampling,
                                                    int ordinal, boolean notApplicable) {
        int episodeCount = fills.size(), tradedCount = 0; List<Double> values = new ArrayList<>();
        double positive = 0, negative = 0, equity = 0, peak = 0, drawdown = 0, cost = 0;
        for (JsonNode row : fills) {
            double value = row.path("net_r").asDouble(); values.add(value); equity += value;
            peak = Math.max(peak, equity); drawdown = Math.max(drawdown, peak - equity);
            if (value > 0) positive += value; else if (value < 0) negative += -value;
            if (row.path("traded").asBoolean(false)) {
                tradedCount++; JsonNode outcome = row.path("outcome");
                double risk = outcome.path("risk_amount_usd").asDouble(Double.NaN);
                double fees = outcome.path("fees_usd").asDouble(Double.NaN);
                double slippage = outcome.path("slippage_usd").asDouble(Double.NaN);
                double capacity = outcome.path("capacity_debit_usd").asDouble(Double.NaN);
                double funding = outcome.path("funding_pnl_usd").asDouble(Double.NaN);
                if (!(risk > 0) || !Double.isFinite(fees) || !Double.isFinite(slippage)
                        || !Double.isFinite(capacity) || !Double.isFinite(funding)) {
                    throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: stress outcome lacks exact round-trip cost accounting");
                }
                cost += (fees + slippage + capacity + Math.max(0, -funding)) / risk;
            }
        }
        double minEpisodes = parameters.path("minimum_observations").asDouble(constraints.path("minEpisodes").asDouble());
        double minExpectancy = parameters.path("minimum_expectancy_r").asDouble(constraints.path("minExpectancy").asDouble());
        double minP20 = parameters.path("minimum_p20_r").asDouble(minExpectancy);
        double minProfit = parameters.path("minimum_profit_factor").asDouble(constraints.path("minProfitFactor").asDouble());
        double maxDrawdown = parameters.path("maximum_drawdown_r").asDouble(constraints.path("maxDrawdownR").asDouble());
        double maxCost = parameters.path("maximum_cost_r").asDouble(constraints.path("maxCostR").asDouble());
        double minCoverage = parameters.path("minimum_coverage_fraction").asDouble(constraints.path("minCoverage").asDouble());
        long iterations = resampling.path("iterations").asLong(), seed = resampling.path("seed").asLong() + ordinal;
        JsonNode blockNode = resampling.get("block_length"); Integer block = defined(blockNode) ? blockNode.asInt() : null;
        double coverage = episodeCount == 0 ? 0 : (double) rows(fills).stream()
                .filter(row -> row.path("physical_coverage").asBoolean(false)).count() / episodeCount;
        ObjectNode metrics = object().put("episode_count", episodeCount).put("traded_count", notApplicable ? 0 : tradedCount);
        if (notApplicable) {
            metrics.putNull("expectancy_r").putNull("bootstrap_p20_r").putNull("p20_r").putNull("profit_factor");
            metrics.put("max_drawdown_r", 0).put("cost_r", 0).put("coverage_fraction", coverage);
        } else {
            double expectancy = values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            List<Double> bootstrap = stressBlockBootstrap(values, iterations, seed, block);
            bootstrap.sort(Double::compareTo); Double p20 = bootstrap.isEmpty() ? null
                    : bootstrap.get(Math.max(0, Math.min(bootstrap.size() - 1, (int) Math.ceil(bootstrap.size() * .2) - 1)));
            Double profit;
            if (negative > 0) profit = positive / negative;
            else if (positive > 0) profit = null;
            else profit = 0d;
            metrics.put("expectancy_r", expectancy); if (p20 == null) metrics.putNull("bootstrap_p20_r"); else metrics.put("bootstrap_p20_r", p20);
            if (p20 == null) metrics.putNull("p20_r"); else metrics.put("p20_r", p20);
            if (profit == null) metrics.putNull("profit_factor"); else metrics.put("profit_factor", profit);
            metrics.put("max_drawdown_r", drawdown).put("cost_r", tradedCount == 0 ? 0 : cost / tradedCount)
                    .put("coverage_fraction", coverage);
        }
        metrics.put("minimum_observations", minEpisodes).put("minimum_expectancy_r", minExpectancy)
                .put("minimum_p20_r", minP20).put("minimum_profit_factor", minProfit)
                .put("maximum_drawdown_r", maxDrawdown).put("maximum_cost_r", maxCost)
                .put("minimum_coverage_fraction", minCoverage)
                .put("survival_condition", notApplicable ? "NOT_APPLICABLE"
                        : firstTextOr(parameters, "POSITIVE_EXPECTANCY_AND_P20", "survival_condition"))
                .put("resampling_iterations", iterations).put("resampling_seed", seed);
        if (block == null || block < 1) metrics.putNull("resampling_block_length"); else metrics.put("resampling_block_length", block);
        boolean pass = notApplicable;
        if (!notApplicable) {
            double expectancy = metrics.path("expectancy_r").asDouble(Double.NaN);
            double p20 = metrics.path("p20_r").asDouble(Double.NaN);
            JsonNode profitNode = metrics.get("profit_factor");
            boolean profitPass = profitNode == null || profitNode.isNull() ? positive > 0 : profitNode.asDouble() >= minProfit;
            pass = tradedCount >= minEpisodes && expectancy > 0 && expectancy >= minExpectancy
                    && p20 > 0 && p20 >= minP20 && profitPass
                    && metrics.path("max_drawdown_r").asDouble() <= maxDrawdown
                    && metrics.path("cost_r").asDouble() <= maxCost && coverage >= minCoverage;
        }
        metrics.put("pass", pass); return metrics;
    }

    private static List<Double> stressBlockBootstrap(List<Double> values, long iterations,
                                                     long seed, Integer rawBlock) {
        if (values.isEmpty()) return List.of(); int block = rawBlock == null || rawBlock < 1
                ? Math.max(1, (int) Math.ceil(Math.sqrt(values.size()))) : rawBlock;
        long state = (seed & 0xffff_ffffL) == 0 ? 1 : seed & 0xffff_ffffL; List<Double> output = new ArrayList<>();
        for (long iteration = 0; iteration < iterations; iteration++) {
            List<Double> sample = new ArrayList<>();
            while (sample.size() < values.size()) {
                state ^= state << 13; state ^= state >>> 17; state ^= state << 5; state &= 0xffff_ffffL;
                double random = (double) state / 4_294_967_296d;
                int start = Math.min(values.size() - 1, (int) Math.floor(random * values.size()));
                for (int offset = 0; offset < block && sample.size() < values.size(); offset++) {
                    sample.add(values.get((start + offset) % values.size()));
                }
            }
            output.add(sample.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        }
        return output;
    }

    public static ObjectNode authoritativeOverfitAudit(ObjectNode options) {
        rejectLooseOptions(options, Set.of("artifact", "statistical_artifact", "exposure_head_artifact", "head",
                "vector", "vector_inventory", "folds", "wfo", "genetic", "ga", "null_artifact",
                "null_controls_artifact"));
        if (defined(field(options, "input"))) {
            PhysicalJson supplied = readJsonBytes(absolute(Path.of(text(options, "input"))), "overfit input");
            rejectLoose(supplied.value(), "input");
        }
        ObjectNode effective = options.deepCopy();
        copyAlias(effective, options, "artifact", "statistical_artifact");
        copyAlias(effective, options, "exposure_head_artifact", "head");
        copyAlias(effective, options, "vector", "vector_inventory");
        copyAlias(effective, options, "folds", "wfo");
        copyAlias(effective, options, "genetic", "ga");
        ObjectNode blocked = blockedPrerequisites("overfit-audit", effective, List.of(
                prerequisite("artifact", "statistical artifact"),
                prerequisite("exposure_head_artifact", "exposure head"),
                prerequisite("vector", "vector inventory"),
                prerequisite("folds", "WFO artifact"),
                prerequisite("genetic", "genetic artifact")));
        if (blocked != null) return blocked;
        PhysicalJson artifactPhysical = physicalJson(Path.of(text(effective, "artifact")),
                "statistical artifact", Set.of(StrategyStatisticalV5.STAT_SCHEMA.get("input")));
        PhysicalJson headPhysical = physicalJson(Path.of(text(effective, "exposure_head_artifact")),
                "exposure head artifact", Set.of(StrategyStatisticalV5.STAT_SCHEMA.get("exposure")));
        ObjectNode head = headPhysical.value();
        PhysicalJson vectorPhysical = physicalJson(Path.of(text(effective, "vector")),
                "vector inventory", Set.of(StrategyStatisticalV5.STAT_SCHEMA.get("vectors")));
        ObjectNode vector = vectorPhysical.value();
        StrategyStatisticalV5.validateExposureHead(head);
        ObjectNode artifactValidation = object(); artifactValidation.set("exposureHead", head);
        artifactValidation.put("allowSubset", true);
        StrategyStatisticalV5.validateStatisticalArtifactSet(artifactPhysical.value(), artifactValidation);
        ArrayNode episodeIds = strings(mapText(artifactPhysical.value().path("episodes"), "episode_id"));
        StrategyStatisticalV5.validateVectorInventory(vector, head, episodeIds);
        PhysicalJson foldPhysical = physicalJson(Path.of(text(effective, "folds")),
                "WFO artifact", Set.of(StrategyStatisticalV5.STAT_SCHEMA.get("wfo")));
        ObjectNode wfo = foldPhysical.value(); StrategyStatisticalV5.validateNestedWfoArtifact(wfo);
        StrategyStatisticalV5.assertWfoRetainedOosBinding(
                wfo, artifactPhysical.value(), vector, "overfit-audit retained OOS evidence");
        if (!text(wfo, "validation_exposure_head_sha256").equals(text(head, "content_sha256"))
                || !text(wfo, "vector_inventory_sha256").equals(text(vector, "content_sha256"))
                || !stable(wfo.path("oos_episode_ids")).equals(stable(vector.path("episode_ids")))) {
            throw failure("overfit WFO/exposure/vector lineage is not exact");
        }
        PhysicalJson geneticPhysical = physicalJson(Path.of(text(effective, "genetic")),
                "genetic artifact", Set.of(StrategyStatisticalV5.STAT_SCHEMA.get("genetic")));
        ObjectNode genetic = geneticPhysical.value();
        String selectedValue = firstText(genetic, "selected_behavior_alias_sha256");
        if (selectedValue.isEmpty()) {
            selectedValue = text(genetic.path("selected"), "behavior_alias_sha256");
        }
        final String selected = selectedValue;
        boolean inHead = rows(head.path("entries")).stream()
                .anyMatch(row -> selected.equals(text(row, "behavior_sha256")));
        JsonNode selectedVector = vector.path("vectors").get(selected);
        if (!HASH.matcher(selected).matches() || !inHead || selectedVector == null || !selectedVector.isArray()) {
            throw failure("overfit-audit requires a selected behavior alias present in the physical exposure head and vector inventory");
        }
        if (defined(field(options, "config")) || defined(field(options, "selected_candidate"))
                || defined(field(options, "selected_candidate_id")) || defined(field(options, "null_controls"))) {
            throw failure("overfit-audit thresholds, selected metrics, and null controls must come from frozen physical artifacts, not caller flags");
        }
        Map<String, JsonNode> vectorByEpisode = new HashMap<>();
        for (JsonNode row : selectedVector) vectorByEpisode.put(text(row, "episode_id"), row);
        for (JsonNode episode : artifactPhysical.value().path("episodes")) {
            String id = text(episode, "episode_id"); JsonNode row = vectorByEpisode.get(id);
            boolean rowEligible = row != null && (!row.has("eligible") || row.path("eligible").asBoolean());
            boolean episodeEligible = !episode.has("eligible") || episode.path("eligible").asBoolean();
            if (row == null || rowEligible != episodeEligible || !Double.isFinite(number(row.get("net_r")))
                    || !row.path("traded").isBoolean()) {
                throw failure("overfit-audit physical vector omits or mismatches episode " + id);
            }
        }

        ObjectNode nullControls = null; PhysicalJson nullPhysical = null;
        String nullLimitation = "PHYSICAL_NULL_SELECTION_ADAPTER_MISSING: overfit-audit requires an exact authoritative null-controls artifact or evaluator-owned physical rerun";
        String nullPath = firstText(options, "null_artifact", "null_controls_artifact");
        if (!nullPath.isEmpty()) {
            nullPhysical = physicalJson(Path.of(nullPath), "authoritative null-controls artifact",
                    Set.of(StrategyStatisticalV5.STAT_SCHEMA.get("nulls")));
            validateNullControlBinding(nullPhysical.value(), selected, artifactPhysical.value(), vector, wfo,
                    "authoritative null-controls artifact");
            nullControls = nullPhysical.value();
            nullLimitation = nullControls.path("pass").asBoolean(false)
                    ? null : "authoritative null-controls artifact did not pass";
        } else if (wfo.path("audit").path("null_controls").isObject()) {
            ObjectNode embedded = (ObjectNode) wfo.path("audit").path("null_controls");
            validateNullControlBinding(embedded, selected, artifactPhysical.value(), vector, wfo,
                    "WFO null-controls artifact");
            nullControls = embedded.deepCopy();
            nullLimitation = nullControls.path("pass").asBoolean(false)
                    ? null : "WFO authoritative null-controls artifact did not pass";
        }
        ObjectNode auditArgs = object(); auditArgs.set("artifact", artifactPhysical.value());
        auditArgs.set("exposureHead", head); auditArgs.put("selectedCandidateId", selected);
        auditArgs.set("vectorInventory", vector); auditArgs.set("folds", wfo.path("folds"));
        auditArgs.set("genetic", genetic);
        auditArgs.set("selectedMetrics", genetic.path("selected").path("fitness").path("metrics").isMissingNode()
                ? NullNode.instance : genetic.path("selected").path("fitness").path("metrics"));
        auditArgs.set("nullControls", nullControls == null ? NullNode.instance : nullControls);
        auditArgs.set("assetDecisions", wfo.path("asset_decisions_final"));
        auditArgs.set("portfolioDecision", wfo.path("portfolio_decision").isMissingNode()
                ? NullNode.instance : wfo.path("portfolio_decision"));
        auditArgs.set("config", genetic.path("config").isObject() ? genetic.path("config") : object());
        ObjectNode audit = StrategyStatisticalV5.runStatisticalAuditV5(auditArgs);
        Path auditPath = requestedOr(options, durableArtifactPath(options, audit, "statistical-audit"), "out");
        writeImmutable(auditPath, audit);
        ArrayNode receiptInputs = array().add(physicalReference(artifactPhysical, "statistical_artifact"))
                .add(physicalReference(headPhysical, "exposure_head"))
                .add(physicalReference(vectorPhysical, "vector_inventory"))
                .add(physicalReference(foldPhysical, "folds"))
                .add(physicalReference(geneticPhysical, "genetic"));
        if (nullPhysical != null) receiptInputs.add(physicalReference(nullPhysical, "null_controls"));
        boolean shadow = "SHADOW".equals(text(audit, "decision"));
        ArrayNode limitations = array();
        if (!shadow) {
            limitations.add("statistical audit gates did not pass");
            if (nullLimitation == null) limitations.addNull(); else limitations.add(nullLimitation);
        }
        ObjectNode details = object().put("mode", "PHYSICAL_VECTOR_FOLD_GA_AUDIT")
                .put("audit_sha256", text(audit, "content_sha256"));
        if (nullControls == null) details.putNull("null_controls_sha256");
        else details.put("null_controls_sha256", text(nullControls, "content_sha256"));
        ObjectNode commandReceipt = receipt("overfit-audit", shadow ? "COMPLETE" : "REJECTED",
                receiptInputs, array().add(reference(auditPath, "statistical_audit")), limitations, details);
        Path receiptPath = writeDurableReceipt(commandReceipt, options);
        ObjectNode result = object(); result.set("audit", audit); result.set("receipt", commandReceipt);
        result.put("receipt_path", receiptPath.toString()); return result;
    }

    private static ObjectNode rejectedResearchRun(
            ObjectNode plan, ObjectNode manifest, ObjectNode envelope, ObjectNode domain,
            ObjectNode hydration, String opportunityPartitionRootSha256, ObjectNode artifact,
            String reason, String evaluatorSha256, String geneticSha256, String wfoSha256,
            String fillsSha256, String stressSha256, String portfolioSha256,
            String behaviorRegistrySha256) {
        ObjectNode artifacts = (ObjectNode) manifest.path("artifacts");
        String featureSha = requireSha(text(artifacts.path("feature"), "sha256"),
                "authoritative manifest feature artifact");
        String labelSha = requireSha(text(artifacts.path("label"), "sha256"),
                "authoritative manifest label artifact");
        String executionSha = requireSha(text(artifacts.path("execution"), "sha256"),
                "authoritative manifest execution artifact");
        String markSha = requireSha(text(artifacts.path("mark"), "sha256"),
                "authoritative manifest mark artifact");
        ObjectNode lineage = object().put("manifest_sha256", text(manifest, "content_sha256"));
        putNullable(lineage, "envelope_sha256", nullableHash(envelope));
        putNullable(lineage, "opportunity_domain_sha256", nullableHash(domain));
        putNullable(lineage, "opportunity_hydration_sha256", nullableHash(hydration));
        putNullable(lineage, "opportunity_partition_root_sha256", opportunityPartitionRootSha256);
        putNullable(lineage, "candidate_set_sha256", nullIfEmpty(text(artifact.path("lineage"), "candidate_set_sha256")));
        lineage.put("feature_rows_sha256", featureSha).put("label_rows_sha256", labelSha)
                .put("execution_rows_sha256", executionSha).put("mark_rows_sha256", markSha);
        putNullable(lineage, "wfo_sha256", wfoSha256);
        List<String> assets = rows(artifact.path("episodes")).stream()
                .map(row -> lower(text(row, "asset"))).filter(value -> !value.isEmpty()).distinct().sorted().toList();
        ObjectNode run = object().put("schema", "strategy-research-run/5").put("version", 1)
                .put("provenance", "AUTHORITATIVE_BLOCKED");
        run.putNull("strategy_family_id").putNull("strategy_version").putNull("experiment_id")
                .putNull("evidence_phase");
        run.set("asset_set", strings(assets)); run.set("pipeline", strings(PIPELINE_V5));
        run.set("lineage", lineage.deepCopy()); run.put("manifest_sha256", text(manifest, "content_sha256"));
        putNullable(run, "envelope_sha256", nullableHash(envelope));
        putNullable(run, "opportunity_domain_sha256", nullableHash(domain));
        putNullable(run, "opportunity_hydration_sha256", nullableHash(hydration));
        putNullable(run, "opportunity_partition_root_sha256", opportunityPartitionRootSha256);
        run.putNull("cutoff").put("feature_rows_sha256", featureSha).put("label_rows_sha256", labelSha)
                .put("execution_rows_sha256", executionSha).put("mark_rows_sha256", markSha);
        run.set("candidate_metrics", array());
        ObjectNode accounting = object().put("declared_k", artifact.path("candidates").size())
                .put("evaluated_k", 0).put("current_evaluation_attempt_k", 0)
                .put("current_evaluation_attempt_inventory_sha256", hash(array()))
                .put("cumulative_family_k", 0).put("candidate_metric_count", 0)
                .put("candidate_metric_inventory_sha256", hash(array()))
                .put("market_episode_count", artifact.path("episodes").size()).put("zero_episode_binding", true);
        run.set("accounting", accounting);
        run.set("wfo", object().put("pass", false).put("status", "BLOCKED").put("reason", reason));
        run.put("decision", "REJECTED");
        run.set("gate_status", object().put("wfo", false).put("stress", false)
                .put("portfolio", false).put("all_required_stages", false));
        run = withHash(run); SCHEMAS.validateKnownContractSchema(run);
        ObjectNode bound = object(); putNullable(bound, "evaluator_sha256", evaluatorSha256);
        bound.put("data_sha256", text(manifest, "content_sha256"));
        bound.put("plan_sha256", text(plan, "content_sha256"));
        putNullable(bound, "genetic_sha256", geneticSha256); putNullable(bound, "wfo_sha256", wfoSha256);
        putNullable(bound, "selected_fills_sha256", fillsSha256); putNullable(bound, "stress_sha256", stressSha256);
        putNullable(bound, "portfolio_sha256", portfolioSha256);
        putNullable(bound, "behavior_registry_sha256", behaviorRegistrySha256);
        ObjectNode result = object(); result.set("run", run); result.set("lineage", lineage);
        result.put("limitation", reason); result.set("bound_hashes", bound); return result;
    }

    private static String nullableHash(JsonNode value) {
        return value != null && value.isObject() && HASH.matcher(text(value, "content_sha256")).matches()
                ? text(value, "content_sha256") : null;
    }

    private static void rejectExperimentLineageOverrides(ObjectNode options) {
        Iterator<String> names = options.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            String normalized = key.replace('-', '_').toLowerCase(Locale.ROOT);
            if (EXPERIMENT_LINEAGE_OPTION_KEYS.contains(normalized)) {
                throw failure("experiment-freeze rejects caller-supplied --" + key
                        + "; lineage is recomputed from frozen physical inputs");
            }
        }
    }

    private static void validateExperimentPolicyWindow(ObjectNode policy) {
        JsonNode chronology = policy.path("chronology");
        long developmentStart = timestampOrMin(chronology.path("development_window").get("start_at"));
        long developmentEnd = timestampOrMin(chronology.path("development_window").get("end_at"));
        long monitoringStart = timestampOrMin(chronology.path("monitoring_window").get("start_at"));
        long monitoringEnd = timestampOrMin(chronology.path("monitoring_window").get("end_at"));
        if (developmentStart == Long.MIN_VALUE || developmentEnd == Long.MIN_VALUE
                || monitoringStart == Long.MIN_VALUE || monitoringEnd == Long.MIN_VALUE) {
            throw failure("experiment policy chronology contains an invalid timestamp");
        }
        if (!(developmentStart < developmentEnd && monitoringStart < monitoringEnd
                && developmentEnd <= monitoringStart)) {
            throw failure("experiment policy must freeze non-overlapping ordered development and monitoring windows");
        }
    }

    private static String validateExplicitCandidateSetForExperimentFreeze(
            ObjectNode candidate, ObjectNode precommit, ObjectNode evaluator) {
        if (!text(candidate, "precommit_sha256").equals(text(precommit, "content_sha256"))
                || !text(candidate.path("lineage"), "precommit_sha256").equals(text(precommit, "content_sha256"))) {
            throw failure("explicit candidate set is bound to a different precommit");
        }
        JsonNode geneSpace = candidate.path("gene_space");
        if (!geneSpace.isObject() || !text(geneSpace, "content_sha256").equals(ownHash(geneSpace))
                || !text(geneSpace, "content_sha256").equals(text(evaluator, "gene_space_sha256"))) {
            throw failure("explicit candidate set gene space differs from the evaluator spec");
        }
        JsonNode candidates = candidate.path("candidates"), aliases = candidate.path("aliases");
        if (!candidates.isArray() || candidates.isEmpty()
                || candidate.path("declared_k").asInt(-1) != candidates.size()
                || !aliases.isArray() || candidate.path("effective_k").asInt(-1) != aliases.size()) {
            throw failure("explicit candidate-set K accounting does not reconcile");
        }
        List<String> ids = mapText(candidates, "candidate_id");
        if (ids.stream().anyMatch(String::isEmpty) || new HashSet<>(ids).size() != ids.size()) {
            throw failure("explicit candidate set contains a missing or duplicate candidate id");
        }
        List<String> candidateAliases = new ArrayList<>();
        for (JsonNode row : candidates) {
            candidateAliases.add(requireSha(text(row, "behavior_alias_sha256"),
                    "candidate behavior alias"));
        }
        candidateAliases = candidateAliases.stream().distinct().sorted().toList();
        List<String> registryAliases = rows(aliases).stream().map(row ->
                requireSha(text(row, "behavior_sha256"), "candidate alias registry behavior")).sorted().toList();
        if (candidateAliases.size() != candidate.path("effective_k").asInt()
                || !stable(strings(candidateAliases)).equals(stable(strings(registryAliases)))) {
            throw failure("explicit candidate-set behavioral aliases do not reconcile");
        }
        return text(candidate, "content_sha256");
    }

    private static void copyAlias(ObjectNode target, ObjectNode source, String canonical, String alias) {
        if (!defined(target.get(canonical)) && defined(source.get(alias))) target.set(canonical, source.get(alias));
    }

    private static void rejectLoose(JsonNode value, String path) {
        if (value == null || value.isNull() || value.isValueNode()) return;
        if (value.isArray()) {
            int index = 0; for (JsonNode child : value) rejectLoose(child, path + "[" + index++ + "]");
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (LOOSE_KEYS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
                throw failure(path + "." + field.getKey()
                        + " caller-supplied statistical field is rejected; provide a physical hash-bound artifact");
            }
            rejectLoose(field.getValue(), path + "." + field.getKey());
        }
    }

    private static void rejectFeatureOutcomeFields(JsonNode value, String path) {
        if (value == null || value.isNull() || value.isValueNode()) return;
        if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                rejectFeatureOutcomeFields(value.get(index), path + "[" + index + "]");
            }
            return;
        }
        Set<String> forbidden = Set.of("label", "labels", "outcome", "outcomes", "target",
                "forward_return", "future_return", "forward_pnl", "future_pnl", "net_r",
                "exit_price", "exit_time", "resolution_time");
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next(); String lowered = lower(field.getKey());
            if (forbidden.contains(lowered)) {
                throw failure(path + "." + field.getKey()
                        + " is a label/outcome field and is forbidden in an opportunity feature input");
            }
            rejectFeatureOutcomeFields(field.getValue(), path + "." + field.getKey());
        }
    }

    private static void validateNullControlBinding(ObjectNode nullControls, String selected,
                                                   ObjectNode artifact, ObjectNode vector, ObjectNode wfo,
                                                   String label) {
        Set<String> sources = new HashSet<>();
        for (String value : List.of(text(artifact, "content_sha256"),
                text(vector, "source_artifact_sha256"), text(wfo, "oos_artifact_sha256"))) {
            if (HASH.matcher(value).matches()) sources.add(value);
        }
        if (!sources.contains(text(nullControls, "artifact_sha256"))
                || !selected.equals(text(nullControls, "selected_candidate_id"))) {
            throw failure(label + " is not bound to the exact selected vector/artifact lineage");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Evaluator projection, settlement, prospective, migration exports   */
    /* ------------------------------------------------------------------ */

    public static StrategyEvaluatorV5.Evaluator adaptPhysicalEvaluator(
            StrategyEvaluatorV5.Evaluator evaluator, String manifestSha256) {
        return adaptPhysicalEvaluator(evaluator, manifestSha256, new LinkedHashMap<>(), List.of(),
                new LinkedHashMap<>(), null, null, new LinkedHashMap<>(), null,
                new LinkedHashMap<>());
    }

    /**
     * Typed counterpart of the Node adapter's retained-evidence arguments.
     * The maps are deliberately caller-owned: the adapter appends only
     * canonical artifacts after independently checking the physical result.
     */
    public static StrategyEvaluatorV5.Evaluator adaptPhysicalEvaluator(
            StrategyEvaluatorV5.Evaluator evaluator,
            String manifestSha256,
            Map<String, List<ObjectNode>> observedVectors,
            List<ObjectNode> episodeInventory,
            Map<String, ObjectNode> observedEvaluations,
            String signalViewSourceSha256,
            JsonNode signalViewLineage,
            Map<String, ObjectNode> behaviorDefinitionRegistry,
            ObjectNode behaviorDefinitionContext,
            Map<String, ObjectNode> observedEvaluationAttempts) {
        if (evaluator == null || !StrategyEvaluatorV5.isVerifiedPhysicalEvaluator(evaluator)) {
            throw failure("adaptPhysicalEvaluator requires a loader-owned verified physical evaluator");
        }
        requireSha(manifestSha256, "physical evaluator manifest SHA-256");
        if (signalViewSourceSha256 != null) {
            requireSha(signalViewSourceSha256, "statistical signal-view source SHA-256");
        }
        if (observedVectors == null || episodeInventory == null || observedEvaluations == null
                || behaviorDefinitionRegistry == null || observedEvaluationAttempts == null) {
            throw failure("adaptPhysicalEvaluator retained-evidence collections are required");
        }
        return new PhysicalEvaluatorAdapter(evaluator, manifestSha256, observedVectors,
                episodeInventory, observedEvaluations, signalViewSourceSha256, signalViewLineage,
                behaviorDefinitionRegistry, behaviorDefinitionContext, observedEvaluationAttempts);
    }

    public static StrategyEvaluatorV5.Evaluator adaptPhysicalEvaluator(ObjectNode options) {
        throw failure("adaptPhysicalEvaluator requires the typed loader-owned evaluator capability; JSON dependency injection is rejected");
    }

    private static final class PhysicalEvaluatorAdapter implements StrategyEvaluatorV5.Evaluator {
        private final StrategyEvaluatorV5.Evaluator physical;
        private final String manifestSha256;
        private final Map<String, List<ObjectNode>> observedVectors;
        private final Map<String, ObjectNode> inventory;
        private final Map<String, ObjectNode> observedEvaluations;
        private final String signalViewSourceSha256;
        private final JsonNode signalViewLineage;
        private final Map<String, ObjectNode> behaviorDefinitionRegistry;
        private final ObjectNode behaviorDefinitionContext;
        private final Map<String, ObjectNode> observedEvaluationAttempts;
        private long observedEvaluationOrdinal;

        private PhysicalEvaluatorAdapter(
                StrategyEvaluatorV5.Evaluator physical,
                String manifestSha256,
                Map<String, List<ObjectNode>> observedVectors,
                List<ObjectNode> episodeInventory,
                Map<String, ObjectNode> observedEvaluations,
                String signalViewSourceSha256,
                JsonNode signalViewLineage,
                Map<String, ObjectNode> behaviorDefinitionRegistry,
                ObjectNode behaviorDefinitionContext,
                Map<String, ObjectNode> observedEvaluationAttempts) {
            this.physical = physical;
            this.manifestSha256 = manifestSha256;
            this.observedVectors = observedVectors;
            this.observedEvaluations = observedEvaluations;
            this.signalViewSourceSha256 = signalViewSourceSha256;
            this.signalViewLineage = signalViewLineage == null ? null : signalViewLineage.deepCopy();
            this.behaviorDefinitionRegistry = behaviorDefinitionRegistry;
            this.behaviorDefinitionContext = behaviorDefinitionContext == null
                    ? null : behaviorDefinitionContext.deepCopy();
            this.observedEvaluationAttempts = observedEvaluationAttempts;
            this.inventory = new LinkedHashMap<>();
            for (ObjectNode row : episodeInventory) {
                if (row == null || text(row, "episode_id").isEmpty()
                        || this.inventory.putIfAbsent(text(row, "episode_id"), row.deepCopy()) != null) {
                    throw failure("authoritative evaluator episode inventory is omitted or duplicated");
                }
            }
        }

        @Override public ObjectNode evaluate(ObjectNode args) {
            ObjectNode original = requireObject(args, "authoritative evaluator arguments").deepCopy();
            ObjectNode physicalView = makePhysicalView(original);
            ObjectNode physicalArgs = original.deepCopy(); physicalArgs.set("artifact", physicalView);
            return canonicalizePhysicalResult(original, physical.evaluate(physicalArgs), physicalView);
        }

        @Override public List<ObjectNode> evaluateBatch(List<ObjectNode> argsList) {
            if (argsList == null) throw failure("authoritative evaluator batch arguments are required");
            List<ObjectNode> originals = new ArrayList<>(argsList.size());
            List<ObjectNode> physicalArgs = new ArrayList<>(argsList.size());
            List<ObjectNode> views = new ArrayList<>(argsList.size());
            for (ObjectNode raw : argsList) {
                ObjectNode original = requireObject(raw, "authoritative evaluator batch argument").deepCopy();
                ObjectNode view = makePhysicalView(original); ObjectNode adapted = original.deepCopy();
                adapted.set("artifact", view); originals.add(original); physicalArgs.add(adapted); views.add(view);
            }
            List<ObjectNode> results = physical.evaluateBatch(List.copyOf(physicalArgs));
            if (results == null || results.size() != originals.size()) {
                throw failure("physical evaluator batch result count differs from its invocation count");
            }
            List<ObjectNode> canonical = new ArrayList<>(results.size());
            for (int index = 0; index < results.size(); index++) {
                canonical.add(canonicalizePhysicalResult(originals.get(index), results.get(index), views.get(index)));
            }
            return List.copyOf(canonical);
        }

        private ObjectNode makePhysicalView(ObjectNode args) {
            JsonNode rawView = field(args, "artifact");
            if (!(rawView instanceof ObjectNode callerView)
                    || !"strategy-v5-statistical-signal-view/1".equals(text(callerView, "schema"))) {
                throw failure("authoritative evaluator received an unverified signal view");
            }
            if (!text(callerView, "content_sha256").equals(ownHash(callerView))) {
                throw failure("authoritative evaluator signal view is tampered");
            }
            if (signalViewSourceSha256 != null
                    && !signalViewSourceSha256.equals(text(callerView, "source_artifact_sha256"))
                    && (signalViewLineage == null
                    || !stable(field(callerView, "lineage")).equals(stable(signalViewLineage)))) {
                throw failure("authoritative evaluator signal view is not bound to the exact statistical fold artifact lineage");
            }
            validateViewInventory(args, callerView);
            ObjectNode physicalView = callerView.deepCopy();
            physicalView.put("source_artifact_sha256", manifestSha256);
            physicalView.put("content_sha256", ownHash(physicalView));
            return physicalView;
        }

        private List<String> validateViewInventory(ObjectNode args, ObjectNode view) {
            JsonNode requestedNode = field(args, "episode_ids");
            JsonNode viewedNode = field(view, "episode_ids");
            if (!requestedNode.isArray() || !viewedNode.isArray()
                    || !stable(requestedNode).equals(stable(viewedNode))) {
                throw failure("authoritative evaluator episode inventory is omitted, duplicated, or outside the verified artifact");
            }
            List<String> requested = new ArrayList<>(); Set<String> unique = new HashSet<>();
            for (JsonNode id : requestedNode) {
                String value = id.asText();
                if (!unique.add(value) || !inventory.containsKey(value)) {
                    throw failure("authoritative evaluator episode inventory is omitted, duplicated, or outside the verified artifact");
                }
                requested.add(value);
            }
            for (JsonNode rawRow : rows(field(view, "episodes"))) {
                ObjectNode row = rawRow instanceof ObjectNode object ? object : null;
                ObjectNode expected = row == null ? null : inventory.get(text(row, "episode_id"));
                long actualAt = row == null ? Long.MIN_VALUE : nodeDateParseOrMin(field(row, "decision_time"));
                long expectedAt = expected == null ? Long.MIN_VALUE
                        : nodeDateParseOrMin(field(expected, "decision_time"));
                if (expected == null || !lower(text(row, "asset")).equals(lower(text(expected, "asset")))
                        || actualAt == Long.MIN_VALUE || actualAt != expectedAt
                        || row.path("eligible").asBoolean(true) != expected.path("eligible").asBoolean(true)) {
                    throw failure("authoritative evaluator signal-view identity differs for "
                            + (row == null ? "?" : text(row, "episode_id")));
                }
            }
            return requested;
        }

        private ObjectNode canonicalizePhysicalResult(ObjectNode args, ObjectNode result, ObjectNode physicalView) {
            List<String> requested = validateViewInventory(args, physicalView);
            String phase = text(args, "phase");
            JsonNode cutoff = args.has("cutoff") ? args.get("cutoff") : NullNode.instance;
            JsonNode fitCutoff = args.has("fit_cutoff") ? args.get("fit_cutoff")
                    : ("OUTER_OOS".equals(phase) ? NullNode.instance : cutoff);
            JsonNode evaluationCutoff = args.has("evaluation_cutoff") ? args.get("evaluation_cutoff")
                    : ("INNER_VALIDATION".equals(phase) ? cutoff
                    : ("OUTER_OOS".equals(phase) ? NullNode.instance : fitCutoff));
            String weighting = jsonTruthy(field(args, "weighting")) ? text(args, "weighting")
                    : (Set.of("TRAIN_ONLY", "TRAIN_CONFIRMATION").contains(phase) ? "TRAIN_HALF_LIFE"
                    : ("INNER_VALIDATION".equals(phase) ? "UNWEIGHTED_VALIDATION" : "UNWEIGHTED_OOS"));
            long fitAt = fitCutoff == null || fitCutoff.isNull() ? Long.MIN_VALUE : nodeDateParseOrMin(fitCutoff);
            long evaluationAt = evaluationCutoff == null || evaluationCutoff.isNull()
                    ? Long.MIN_VALUE : nodeDateParseOrMin(evaluationCutoff);
            if (fitCutoff != null && !fitCutoff.isNull() && fitAt == Long.MIN_VALUE) {
                throw failure("authoritative evaluator fit cutoff is not a valid timestamp");
            }
            if (evaluationCutoff != null && !evaluationCutoff.isNull() && evaluationAt == Long.MIN_VALUE) {
                throw failure("authoritative evaluator evaluation cutoff is not a valid timestamp");
            }
            if ("INNER_VALIDATION".equals(phase)
                    && (fitAt == Long.MIN_VALUE || evaluationAt == Long.MIN_VALUE || evaluationAt <= fitAt)) {
                throw failure("authoritative inner validation requires a later evaluation cutoff than its fit cutoff");
            }
            if ("OUTER_OOS".equals(phase) && ((fitCutoff != null && !fitCutoff.isNull())
                    || (evaluationCutoff != null && !evaluationCutoff.isNull())
                    || !"UNWEIGHTED_OOS".equals(weighting))) {
                throw failure("authoritative outer OOS must remain null-cutoff and unweighted");
            }
            long cutoffAt = cutoff == null || cutoff.isNull() ? Long.MIN_VALUE : nodeDateParseOrMin(cutoff);
            if (cutoffAt != Long.MIN_VALUE && Set.of("TRAIN_ONLY", "TRAIN_CONFIRMATION").contains(phase)) {
                for (String id : requested) {
                    ObjectNode episode = inventory.get(id);
                    long decisionAt = nodeDateParseOrMin(field(episode, "decision_time"));
                    long resolutionAt = nodeDateParseOrMin(field(episode, "resolution_time"));
                    JsonNode label = jsonTruthy(field(episode, "label_availability_time"))
                            ? field(episode, "label_availability_time") : field(episode, "resolution_time");
                    JsonNode execution = jsonTruthy(field(episode, "execution_availability_time"))
                            ? field(episode, "execution_availability_time") : field(episode, "resolution_time");
                    long labelAt = nodeDateParseOrMin(label), executionAt = nodeDateParseOrMin(execution);
                    if (decisionAt == Long.MIN_VALUE || resolutionAt == Long.MIN_VALUE
                            || decisionAt >= cutoffAt || resolutionAt > cutoffAt
                            || labelAt == Long.MIN_VALUE || executionAt == Long.MIN_VALUE
                            || labelAt > cutoffAt || executionAt > cutoffAt) {
                        throw failure("authoritative training evaluator received a future, censored, or unavailable-label/execution episode");
                    }
                }
            }
            JsonNode foldId = args.has("fold_id") ? args.get("fold_id") : NullNode.instance;
            ObjectNode physicalLineage = object(); physicalLineage.put("source_artifact_sha256", manifestSha256);
            physicalLineage.set("episode_ids", field(physicalView, "episode_ids").deepCopy());
            physicalLineage.put("phase", phase); physicalLineage.set("fold_id", foldId.deepCopy());
            physicalLineage.set("cutoff", cutoff.deepCopy()); physicalLineage.set("fit_cutoff", fitCutoff.deepCopy());
            physicalLineage.set("evaluation_cutoff", evaluationCutoff.deepCopy()); physicalLineage.put("weighting", weighting);
            if (result == null || !"strategy-v5-statistical-evaluation/1".equals(text(result, "schema"))
                    || !manifestSha256.equals(text(result, "source_artifact_sha256"))
                    || !stable(field(result, "episode_ids")).equals(stable(field(physicalView, "episode_ids")))
                    || !phase.equals(text(result, "phase"))
                    || !stable(field(result, "fold_id")).equals(stable(foldId))
                    || !stable(field(result, "cutoff")).equals(stable(cutoff))
                    || !stable(field(result, "fit_cutoff")).equals(stable(fitCutoff))
                    || !stable(field(result, "evaluation_cutoff")).equals(stable(evaluationCutoff))
                    || !weighting.equals(text(result, "weighting"))
                    || !hash(physicalLineage).equals(text(result, "lineage_sha256"))
                    || !text(result, "content_sha256").equals(ownHash(result))) {
                throw failure("physical evaluator result hash/lineage does not match the exact source manifest and fold inventory");
            }
            ObjectNode statisticalView = (ObjectNode) field(args, "artifact");
            if (!text(statisticalView, "content_sha256").equals(ownHash(statisticalView))) {
                throw failure("authoritative statistical signal view is tampered");
            }
            if (!field(result, "candidate_definition").isObject()) {
                throw failure("physical evaluator omitted its resolved candidate definition");
            }
            if (!field(args, "chromosome").isObject()) {
                throw failure("authoritative evaluator invocation lacks a frozen chromosome definition");
            }
            ObjectNode make = object(); make.set("signalArtifact", statisticalView.deepCopy());
            make.set("episodeIds", field(physicalView, "episode_ids").deepCopy()); make.put("phase", phase);
            make.set("foldId", foldId.deepCopy()); make.set("cutoff", cutoff.deepCopy());
            make.set("fitCutoff", fitCutoff.deepCopy()); make.set("evaluationCutoff", evaluationCutoff.deepCopy());
            make.put("weighting", weighting); make.set("candidateReturns", field(result, "candidate_returns").deepCopy());
            make.set("metrics", field(result, "metrics").deepCopy());
            make.set("signalIntentVector", field(result, "signal_intent_vector").deepCopy());
            make.set("candidateDefinition", field(args, "chromosome").deepCopy());
            make.set("behaviorContracts", field(result, "behavior_contracts").deepCopy());
            ObjectNode canonical = StrategyStatisticalV5.makeEvaluationArtifact(make);
            recordCanonicalEvaluation(args, canonical);
            return canonical;
        }

        private void recordCanonicalEvaluation(ObjectNode args, ObjectNode canonical) {
            String alias = text(canonical, "behavior_alias_sha256");
            Map<String, ObjectNode> merged = new TreeMap<>();
            for (ObjectNode row : observedVectors.getOrDefault(alias, List.of())) {
                merged.put(text(row, "episode_id"), row.deepCopy());
            }
            for (JsonNode idNode : field(canonical, "episode_ids")) {
                String id = idNode.asText(); JsonNode outcome = field(canonical, "candidate_returns").path(id);
                ObjectNode row = object().put("episode_id", id);
                if (outcome.isObject()) outcome.fields().forEachRemaining(entry -> row.set(entry.getKey(), entry.getValue().deepCopy()));
                row.put("eligible", inventory.get(id).path("eligible").asBoolean(true));
                ObjectNode prior = merged.get(id);
                if (prior != null && (Double.compare(number(field(prior, "net_r")), number(field(row, "net_r"))) != 0
                        || prior.path("traded").asBoolean(false) != row.path("traded").asBoolean(false))) {
                    throw failure("physical evaluator returned conflicting outcomes for " + id);
                }
                merged.put(id, row);
            }
            observedVectors.put(alias, List.copyOf(merged.values()));
            String fold = field(canonical, "fold_id").isNull() ? "" : jsString(field(canonical, "fold_id"));
            String contextKey = alias + "|" + firstTextOr(canonical, "TRAIN_ONLY", "phase") + "|" + fold;
            observedEvaluations.put(contextKey, canonical.deepCopy());
            observedEvaluations.put(alias, canonical.deepCopy());

            ObjectNode invocation = object();
            copyNumberOrNull(invocation, "seed", field(args, "seed"));
            copyNumberOrNull(invocation, "generation", field(args, "generation"));
            if (!defined(field(args, "operator"))) invocation.putNull("operator");
            else invocation.put("operator", jsString(field(args, "operator")));
            invocation.put("confirmation", field(args, "confirmation").asBoolean(false));
            invocation.set("phase", field(canonical, "phase").deepCopy());
            invocation.set("fold_id", field(canonical, "fold_id").deepCopy());
            invocation.set("episode_ids", field(canonical, "episode_ids").deepCopy());
            invocation.set("candidate_definition", field(canonical, "candidate_definition").deepCopy());
            ObjectNode context = object().put("schema", "strategy-v5-authoritative-evaluation-context/1")
                    .put("evaluation_sha256", text(canonical, "content_sha256"));
            context.set("phase", field(canonical, "phase").deepCopy());
            context.set("fold_id", field(canonical, "fold_id").deepCopy());
            context.set("episode_ids", field(canonical, "episode_ids").deepCopy());
            context.set("seed", field(invocation, "seed").deepCopy());
            context.set("generation", field(invocation, "generation").deepCopy());
            context.set("operator", field(invocation, "operator").deepCopy());
            context.set("confirmation", field(invocation, "confirmation").deepCopy());
            ObjectNode attempt = object(); attempt.set("evaluation", canonical.deepCopy());
            attempt.set("invocation", invocation); attempt.put("evaluation_context_sha256", hash(context));
            observedEvaluationAttempts.put(text(canonical, "content_sha256") + "|" + (++observedEvaluationOrdinal), attempt);

            if (!behaviorDefinitionRegistry.containsKey(alias)) {
                ObjectNode definition = (ObjectNode) field(args, "chromosome").deepCopy(); ObjectNode definitionHash = object();
                if (behaviorDefinitionContext != null) {
                    definitionHash.put("schema", "strategy-v5-statistical-behavior-definition/1");
                    definitionHash.set("chromosome", StrategyStatisticalV5.effectiveExecutionBehavior(definition));
                    definitionHash.set("evaluator_sha256", nullableField(behaviorDefinitionContext, "evaluator_sha256"));
                    definitionHash.set("precommit_sha256", nullableField(behaviorDefinitionContext, "precommit_sha256"));
                    definitionHash.set("lifecycle_sha256", nullableField(behaviorDefinitionContext, "lifecycle_sha256"));
                } else {
                    definitionHash.put("schema", "strategy-v5-statistical-definition/1");
                    definitionHash.set("chromosome", StrategyStatisticalV5.effectiveExecutionBehavior(definition));
                }
                ObjectNode record = object().put("behavior_sha256", alias)
                        .put("definition_sha256", hash(definitionHash));
                record.set("chromosome", definition);
                record.put("evaluator_sha256", behaviorDefinitionContext == null
                        ? manifestSha256 : firstTextOr(behaviorDefinitionContext, manifestSha256, "evaluator_sha256"));
                record.set("precommit_sha256", behaviorDefinitionContext == null
                        ? NullNode.instance : nullableField(behaviorDefinitionContext, "precommit_sha256"));
                record.set("lifecycle_sha256", behaviorDefinitionContext == null
                        ? NullNode.instance : nullableField(behaviorDefinitionContext, "lifecycle_sha256"));
                record.set("source_artifact_sha256", field((ObjectNode) field(args, "artifact"), "source_artifact_sha256").deepCopy());
                behaviorDefinitionRegistry.put(alias, record);
            }
        }

        private static void copyNumberOrNull(ObjectNode target, String name, JsonNode value) {
            if (!defined(value)) target.putNull(name); else target.put(name, jsNumber(value));
        }

        private static JsonNode nullableField(JsonNode value, String name) {
            return defined(field(value, name)) ? field(value, name).deepCopy() : NullNode.instance;
        }

        @Override public ObjectNode diagnostics() { return physical.diagnostics(); }
        @Override public List<String> publicPredictorIds() { return physical.publicPredictorIds(); }
        @Override public ObjectNode workerProvenance() {
            ObjectNode value = physical.workerProvenance(); return value == null ? null : value.deepCopy();
        }
        @Override public boolean physicalNullSelectionVerified() {
            return physical.physicalNullSelectionVerified();
        }
        @Override public void close() { physical.close(); }
    }

    /** Typed, non-serializable seam for fixture-only candidate-metric projections. */
    @FunctionalInterface
    public interface CandidateMetricsProjector {
        ArrayNode project(ObjectNode wfo, ObjectNode stageArtifacts, CandidateMetricsPhysical physical);
    }

    /** Typed retained physical evidence passed to a fixture projector. */
    public record CandidateMetricsPhysical(
            Map<String, List<ObjectNode>> observedVectors,
            Map<String, ObjectNode> observedEvaluations,
            Map<String, ObjectNode> observedEvaluationAttempts,
            Map<String, ObjectNode> behaviorDefinitionRegistry) {
        public CandidateMetricsPhysical {
            observedVectors = immutableVectorMap(observedVectors);
            observedEvaluations = immutableObjectMap(observedEvaluations);
            observedEvaluationAttempts = immutableObjectMap(observedEvaluationAttempts);
            behaviorDefinitionRegistry = immutableObjectMap(behaviorDefinitionRegistry);
        }

        private static Map<String, List<ObjectNode>> immutableVectorMap(Map<String, List<ObjectNode>> source) {
            Map<String, List<ObjectNode>> copy = new LinkedHashMap<>();
            if (source != null) for (Map.Entry<String, List<ObjectNode>> entry : source.entrySet()) {
                List<ObjectNode> rows = new ArrayList<>();
                if (entry.getValue() != null) for (ObjectNode row : entry.getValue()) rows.add(row.deepCopy());
                copy.put(entry.getKey(), List.copyOf(rows));
            }
            return Map.copyOf(copy);
        }

        private static Map<String, ObjectNode> immutableObjectMap(Map<String, ObjectNode> source) {
            Map<String, ObjectNode> copy = new LinkedHashMap<>();
            if (source != null) source.forEach((key, value) -> copy.put(key, value.deepCopy()));
            return Map.copyOf(copy);
        }
    }

    public static ArrayNode researchCandidateMetricsFixture(
            boolean testOnly,
            ObjectNode wfo,
            ObjectNode stageArtifacts,
            CandidateMetricsPhysical physical,
            CandidateMetricsProjector projector) {
        if (!testOnly) throw failure("researchCandidateMetricsFixture requires testOnly:true");
        if (projector == null) {
            throw failure("researchCandidateMetricsFixture requires a typed candidate-metrics projector");
        }
        ObjectNode safeWfo = wfo == null ? object() : wfo.deepCopy();
        ObjectNode safeStages = stageArtifacts == null ? null : stageArtifacts.deepCopy();
        CandidateMetricsPhysical safePhysical = physical == null
                ? new CandidateMetricsPhysical(Map.of(), Map.of(), Map.of(), Map.of()) : physical;
        ArrayNode rows = projector.project(safeWfo, safeStages, safePhysical);
        if (rows == null) throw failure("researchCandidateMetricsFixture projector returned null");
        return rows.deepCopy();
    }

    public static ArrayNode researchCandidateMetricsFixture(ObjectNode options) {
        if (!options.path("testOnly").asBoolean(false)) {
            throw failure("researchCandidateMetricsFixture requires testOnly:true");
        }
        // JSON cannot carry the typed projector capability.  Preserve the
        // exact empty identity while rejecting non-empty executable injection.
        JsonNode wfo = field(options, "wfo");
        if (!wfo.isObject() || wfo.path("run").path("asset_decisions").isEmpty()) return array();
        throw failure("researchCandidateMetricsFixture requires typed retained-evaluation maps for a non-empty WFO projection");
    }

    public static ObjectNode resolveDatedSettlementForStress(ObjectNode options) {
        JsonNode metadata = field(options, "metadata"), execution = field(options, "execution"), label = field(options, "label");
        if (!"BINANCE_USDM_DATED_FUTURE".equals(text(execution, "instrument").toUpperCase(Locale.ROOT))) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: settlement resolver requires a dated USD-M future");
        }
        JsonNode expiryReceipt = metadata.path("expiry"), settlementReceipt = metadata.path("settlement");
        if (!expiryReceipt.isObject() || !settlementReceipt.isObject()
                || text(expiryReceipt, "content_sha256").equals(text(settlementReceipt, "content_sha256"))) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: dated expiry stress requires separate physical EXPIRY and SETTLEMENT receipts");
        }
        List<JsonNode> expiryRows = rows(expiryReceipt.path("records")).stream()
                .filter(row -> exactSettlementIdentity(row, execution)).toList();
        if (expiryRows.size() != 1) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: expiry stress lacks one exact physical expiry record");
        }
        JsonNode expiry = expiryRows.get(0);
        long expiryAt = nodeDateParseOrMin(firstTruthy(expiry, "expiry", "delivery_date"));
        List<JsonNode> settlements = rows(settlementReceipt.path("records")).stream()
                .filter(row -> exactSettlementIdentity(row, execution))
                .filter(row -> expiryAt != Long.MIN_VALUE
                        && nodeDateParseOrMin(firstTruthy(row, "expiry", "delivery_date")) == expiryAt).toList();
        if (settlements.size() != 1) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: expiry stress lacks one exact physical settlement record");
        }
        JsonNode settlement = settlements.get(0);
        long eventAt = nodeDateParseOrMin(settlement.get("event_time"));
        long settlementAt = nodeDateParseOrMin(settlement.get("settlement_time"));
        long availableAt = nodeDateParseOrMin(settlement.get("availability_time"));
        long resolution = nodeDateParseOrMin(firstNullish(label,
                "resolution_time", "resolution_ceiling_time"));
        if (expiryAt == Long.MIN_VALUE || eventAt == Long.MIN_VALUE
                || settlementAt == Long.MIN_VALUE || availableAt == Long.MIN_VALUE
                || resolution == Long.MIN_VALUE || eventAt != settlementAt || settlementAt < expiryAt
                || availableAt < settlementAt || availableAt > resolution) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: expiry stress lacks a PIT-bound settlement event/availability within the frozen resolution");
        }
        double price = jsNumber(firstNullish(settlement,
                "settlement_price", "settlement_mark", "mark_price"));
        String source = firstTruthyText(settlement,
                "settlement_mark_source_sha256", "source_byte_sha256", "settlement_mark_sha256");
        if (!(price > 0) || !HASH.matcher(source).matches() || text(settlement, "settlement_mark_event_id").isEmpty()
                || !source.equals(text(settlement, "source_byte_sha256"))
                || !text(settlement, "source_receipt_sha256").equals(text(settlementReceipt, "source_receipt_sha256"))) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: expiry stress lacks a physically bound settlement price/mark identity");
        }
        ObjectNode result = object(); result.set("expiryRecord", expiry.deepCopy()); result.set("settlementRecord", settlement.deepCopy());
        result.put("expiryAt", expiryAt).put("settlementAt", settlementAt).put("settlementAvailableAt", availableAt)
                .put("settlementPrice", price).put("settlementSource", source).put("originalResolution", resolution);
        return result;
    }

    public static ObjectNode resolveProspectiveSourceBundle(ObjectNode options) {
        if (text(options, "source_bundle").isEmpty()) return options.deepCopy();
        Path root = absolute(Path.of(firstTextOr(options, System.getProperty("user.dir"), "workflow_root", "root")));
        WorkflowSecurityV5.SourceBundleVerification verified = WorkflowSecurityV5.verifyProspectiveSourceBundle(
                root, text(options, "source_bundle"));
        ObjectNode bundle = (ObjectNode) verified.bundle(); ObjectNode merged = options.deepCopy();
        merged.put("source_bundle", verified.bundlePhysical().absolute().toString());
        ObjectNode bundlePhysical = object(); bundlePhysical.set("value", bundle.deepCopy());
        bundlePhysical.put("path", verified.bundlePhysical().absolute().toString())
                .put("byte_sha256", hash(verified.bundlePhysical().bytes()))
                .put("content_sha256", text(bundle, "content_sha256"))
                .put("bytes", verified.bundlePhysical().bytes().length);
        merged.set("source_bundle_physical", bundlePhysical);
        String explicitLedger = firstText(options, "ledger", "ledger_path");
        Path ledger = explicitLedger.isEmpty() ? verified.ledger().absolute()
                : WorkflowSecurityV5.confinedPath(root, explicitLedger, "prospective ledger", true, false).absolute();
        if (!explicitLedger.isEmpty()) {
            WorkflowSecurityV5.verifySafeTree(ledger, "prospective ledger", SafeTreeVerifier.Options.EVIDENCE);
        }
        merged.put("ledger", ledger.toString());
        if (text(options, "expected_head_sha256").isEmpty()) {
            merged.put("expected_head_sha256", text(bundle, "expected_head_sha256"));
        }
        Map<String, String> names = Map.of(
                "reservation", "reservation", "source_receipt", "source receipt", "bar", "completed bar",
                "feature_input", "feature input", "candidate_set", "candidate set",
                "evaluator_code", "evaluator code", "signal_decision", "signal decision");
        for (Map.Entry<String, String> entry : names.entrySet()) {
            JsonNode reference = bundle.path(entry.getKey());
            WorkflowSecurityV5.ConfinedJson physical = verified.references().get(entry.getKey());
            if (physical == null || !HASH.matcher(text(reference, "byte_sha256")).matches()
                    || !hash(physical.bytes()).equals(text(reference, "byte_sha256"))) {
                throw failure(entry.getValue() + " source-bundle byte hash does not match the physical artifact");
            }
            merged.put(entry.getKey(), physical.absolute().toString());
        }
        Path headPath = ledger.resolve("HEAD.json");
        String reopenedHead = null;
        if (Files.exists(headPath, LinkOption.NOFOLLOW_LINKS)) {
            ObjectNode readOptions = object().put("nowAt", System.currentTimeMillis()).put("allowFuture", true);
            ObjectNode snapshot = StrategyProspectiveV5.readProspectiveLedger(ledger, readOptions);
            reopenedHead = text(snapshot, "current_head_sha256");
            if (!text(snapshot, "lineage_sha256").equals(text(bundle, "lineage_sha256"))) {
                throw failure("hydrated prospective ledger lineage differs from frozen source bundle");
            }
            String genesis = hash(object().put("schema", "strategy-prospective-ledger-genesis/1")
                    .put("lineage_sha256", text(bundle, "lineage_sha256")));
            if (snapshot.path("sequence").asInt() == 0
                    && !text(snapshot, "current_head_sha256").equals(text(bundle, "expected_head_sha256"))) {
                throw failure("prospective ledger genesis head differs from frozen source bundle");
            }
            if (snapshot.path("sequence").asInt() > 0
                    && !text(snapshot.path("events").path(0), "previous_head_sha256").equals(genesis)) {
                throw failure("prospective ledger chain is not anchored to the frozen genesis");
            }
            String explicit = text(options, "expected_head_sha256");
            if (!explicit.isEmpty() && !explicit.equals(text(snapshot, "current_head_sha256"))) {
                throw failure("explicit expected CAS head differs from hydrated prospective ledger");
            }
            merged.put("expected_head_sha256", text(snapshot, "current_head_sha256"));
        }
        if (!text(options, "reservation").isEmpty()
                && !absolute(Path.of(text(options, "reservation"))).equals(absolute(Path.of(text(merged, "reservation"))))) {
            throw failure("explicit reservation path conflicts with the frozen source bundle");
        }
        if (!text(options, "expected_head_sha256").isEmpty() && reopenedHead == null
                && !text(options, "expected_head_sha256").equals(text(bundle, "expected_head_sha256"))) {
            throw failure("explicit expected CAS head conflicts with the frozen source bundle genesis");
        }
        return merged;
    }

    public static ObjectNode authoritativeProspectiveRunner(ObjectNode options) {
        Iterator<String> names = options.fieldNames();
        while (names.hasNext()) if (names.next().matches("(?i).*private.?key.*|.*secret.*")) {
            throw failure("prospective-runner never accepts private key material on the CLI");
        }
        ObjectNode effective = options;
        if (!text(options, "source_bundle").isEmpty()) {
            try { effective = resolveProspectiveSourceBundle(options); }
            catch (RuntimeException error) {
                ArrayNode inputs = array();
                ObjectNode source = bestEffortPhysicalReference(
                        absolute(Path.of(text(options, "source_bundle"))), "source_bundle");
                if (source != null) inputs.add(source);
                ObjectNode receipt = receipt("prospective-runner", "BLOCKED", inputs, array(),
                        strings(List.of("PROSPECTIVE_SOURCE_BUNDLE_BLOCKED: " + error.getMessage())),
                        object().put("mode", "BLOCKED_SOURCE_BUNDLE_VALIDATION")
                                .put("reason", error.getMessage()));
                Path path = writeDurableReceipt(receipt, options); ObjectNode result = object().put("status", "BLOCKED");
                result.set("receipt", receipt); result.put("receipt_path", path.toString()); return result;
            }
        }
        if (text(options, "source_bundle").isEmpty()
                && (effective.path("live_source_unconfigured").asBoolean(false)
                || "UNCONFIGURED".equals(text(effective, "live_source")))) {
            return blockedResult("prospective-runner", options, List.of("PROSPECTIVE_LIVE_SOURCE_UNCONFIGURED"),
                    "BLOCKED_LIVE_SOURCE_UNCONFIGURED",
                    "no verified frozen Binance completed-4h acquisition adapter is configured for this environment");
        }
        List<String[]> required = List.of(
                new String[]{"ledger", "ledger"}, new String[]{"reservation", "reservation"},
                new String[]{"source_receipt", "source receipt"}, new String[]{"bar", "bar"},
                new String[]{"feature_input", "feature input"}, new String[]{"candidate_set", "candidate set"},
                new String[]{"evaluator_code", "evaluator code"}, new String[]{"signal_decision", "signal decision"});
        List<String> missing = new ArrayList<>(); ArrayNode existingInputs = array();
        if (!text(effective, "source_bundle").isEmpty()) {
            ObjectNode source = bestEffortPhysicalReference(Path.of(text(effective, "source_bundle")), "source_bundle");
            if (source != null) existingInputs.add(source);
        }
        for (String[] row : required) {
            String value = text(effective, row[0]);
            if (value.isEmpty()) { missing.add(row[1] + ": missing physical prerequisite"); continue; }
            Path candidate = absolute(Path.of(value));
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                missing.add(row[1] + ": path does not exist: " + value); continue;
            }
            if ("ledger".equals(row[0])) {
                Path headPath = candidate.resolve("HEAD.json");
                if (!Files.exists(headPath, LinkOption.NOFOLLOW_LINKS)) {
                    missing.add("ledger: HEAD.json path does not exist: " + headPath); continue;
                }
                ObjectNode reference = bestEffortPhysicalReference(headPath, "prospective_ledger_head");
                if (reference != null) existingInputs.add(reference);
            } else {
                ObjectNode reference = bestEffortPhysicalReference(candidate, row[0]);
                if (reference != null) existingInputs.add(reference);
            }
        }
        String head = text(effective, "expected_head_sha256");
        if (head.isEmpty()) missing.add("expected CAS head: missing physical prerequisite");
        else if (!HASH.matcher(head).matches()) missing.add("expected CAS head: must be a SHA-256 head hash");
        if (!missing.isEmpty()) {
            ObjectNode commandReceipt = receipt("prospective-runner", "BLOCKED", existingInputs, array(), strings(missing),
                    object().put("mode", "BLOCKED_NO_PRIVATE_KEY_PATH")
                            .put("reason", "one completed-bar SHADOW cycle requires every physical ledger/reservation/source/bar/feature/candidate/evaluator/decision prerequisite"));
            Path path = writeDurableReceipt(commandReceipt, effective); ObjectNode result = object().put("status", "BLOCKED");
            result.set("receipt", commandReceipt); result.put("receipt_path", path.toString()); return result;
        }

        PhysicalJson reservation = physicalJson(Path.of(text(effective, "reservation")),
                "prospective reservation", Set.of("strategy-prospective-reservation/1"));
        PhysicalJson source = physicalJson(Path.of(text(effective, "source_receipt")),
                "prospective source receipt", Set.of("strategy-prospective-source-receipt/1"));
        PhysicalJson bar = physicalJson(Path.of(text(effective, "bar")), "completed bar", Set.of());
        PhysicalJson signal = physicalJson(Path.of(text(effective, "signal_decision")),
                "signal decision", Set.of("strategy-prospective-signal-decision/1"));
        PhysicalJson feature = physicalJson(Path.of(text(effective, "feature_input")), "feature input", Set.of());
        PhysicalJson candidate = physicalJson(Path.of(text(effective, "candidate_set")), "candidate set", Set.of());
        PhysicalJson evaluator = physicalJson(Path.of(text(effective, "evaluator_code")), "evaluator code", Set.of());
        Path ledgerPath = absolute(Path.of(text(effective, "ledger")));
        PhysicalJson ledgerHead = physicalJson(ledgerPath.resolve("HEAD.json"), "prospective ledger CAS head", Set.of());
        if (!text(ledgerHead.value(), "head_sha256").equals(head)) {
            throw failure("prospective ledger CAS head differs from --expected-head-sha256");
        }
        ArrayNode inputs = array();
        if (!text(effective, "source_bundle").isEmpty()) {
            inputs.add(reference(Path.of(text(effective, "source_bundle")), "source_bundle"));
        }
        inputs.add(physicalReference(reservation, "reservation")); inputs.add(physicalReference(source, "source_receipt"));
        inputs.add(physicalReference(bar, "completed_bar")); inputs.add(physicalReference(feature, "feature_input"));
        inputs.add(physicalReference(candidate, "candidate_set")); inputs.add(physicalReference(evaluator, "evaluator_code"));
        inputs.add(physicalReference(signal, "signal_decision"));
        long nowAt = defined(field(effective, "now_at"))
                ? timestamp(field(effective, "now_at"), "prospective now_at") : System.currentTimeMillis();
        try {
            ObjectNode read = object().put("nowAt", nowAt).put("allowFuture", true);
            ObjectNode before = StrategyProspectiveV5.readProspectiveLedger(ledgerPath, read);
            ObjectNode noOp = object(); noOp.set("ledger", before); noOp.set("bar", bar.value());
            noOp.put("sourceReceiptSha256", source.byteSha256());
            noOp.put("signalDecisionSha256", signal.byteSha256());
            noOp.put("reservationSha256", reservation.byteSha256());
            noOp.put("candidateSetSha256", candidate.byteSha256());
            noOp.put("evaluatorCodeSha256", evaluator.byteSha256());
            noOp.put("featureInputSha256", feature.byteSha256());
            if (StrategyProspectiveV5.verifyCompletedBarNoOp(noOp)) {
                ObjectNode details = object().put("mode", "NO_NEW_COMPLETED_BAR")
                        .put("no_new_completed_bar", true)
                        .put("ledger_head_sha256", text(before, "current_head_sha256"))
                        .put("ledger_sequence", before.path("sequence").asInt());
                ObjectNode commandReceipt = receipt("prospective-runner", "COMPLETE", inputs, array(),
                        strings(List.of("NO_NEW_COMPLETED_BAR: exact latest completed 4h bar and all source/decision bindings already exist; no append or PR created")), details);
                Path receiptPath = writeDurableReceipt(commandReceipt, effective); ObjectNode result = object();
                result.putNull("result"); result.set("receipt", commandReceipt); result.put("status", "NO_NEW_COMPLETED_BAR");
                result.put("no_op", true); result.put("receipt_path", receiptPath.toString()); return result;
            }
            ObjectNode append = object().put("path", ledgerPath.toString())
                    .put("reservationPath", reservation.path().toString()).put("reservationSha256", reservation.byteSha256())
                    .put("sourceReceiptPath", source.path().toString()).put("sourceReceiptSha256", source.byteSha256())
                    .put("featureInputPath", feature.path().toString()).put("featureInputSha256", feature.byteSha256())
                    .put("candidateSetPath", candidate.path().toString()).put("candidateSetSha256", candidate.byteSha256())
                    .put("evaluatorCodePath", evaluator.path().toString()).put("evaluatorCodeSha256", evaluator.byteSha256())
                    .put("signalDecisionPath", signal.path().toString()).put("signalDecisionSha256", signal.byteSha256())
                    .put("expectedHeadSha256", head).put("nowAt", nowAt);
            append.set("bar", bar.value()); ObjectNode cycle = StrategyProspectiveV5.appendCompletedBarCycle(append);
            ObjectNode after = StrategyProspectiveV5.readProspectiveLedger(ledgerPath, read);
            Path headPath = ledgerPath.resolve("HEAD.json");
            ArrayNode outputs = array(); if (Files.exists(headPath, LinkOption.NOFOLLOW_LINKS)) outputs.add(reference(headPath, "prospective_ledger_head"));
            ObjectNode details = object().put("mode", "ONE_COMPLETED_BAR_SHADOW_CYCLE")
                    .put("ledger_prior_head_sha256", head)
                    .put("ledger_new_head_sha256", text(after, "current_head_sha256"))
                    .put("ledger_sequence", after.path("sequence").asInt()).put("activated", false);
            ObjectNode commandReceipt = receipt("prospective-runner", "COMPLETE", inputs, outputs,
                    strings(List.of("SHADOW only; no activation or private key path is available")), details);
            Path receiptPath = writeDurableReceipt(commandReceipt, effective); ObjectNode result = object();
            result.set("result", cycle); result.set("receipt", commandReceipt); result.put("status", "COMPLETE");
            result.put("receipt_path", receiptPath.toString()); return result;
        } catch (RuntimeException error) {
            ObjectNode details = object().put("mode", "BLOCKED_CYCLE_RECOMPUTATION_OR_CUSTODY")
                    .put("reason", error.getMessage()).put("activated", false);
            ObjectNode commandReceipt = receipt("prospective-runner", "BLOCKED", inputs, array(),
                    strings(List.of("COMPLETED_BAR_CYCLE_BLOCKED: " + error.getMessage())), details);
            writeDurableReceipt(commandReceipt, effective); throw error;
        }
    }

    /** Builds readiness strictly from reopened physical evidence, never from caller-supplied scores. */
    private static ObjectNode authoritativeReadinessAudit(ObjectNode options) {
        String manifestPath = firstText(options, "evidence_manifest", "evidenceManifest", "manifest");
        ObjectNode manifestSpec = null;
        ObjectNode manifestReference = null;
        if (!manifestPath.isEmpty()) {
            PhysicalJson physical = readJsonBytes(absolute(Path.of(manifestPath)), "evidence manifest");
            manifestSpec = object().put("path", physical.path().toString())
                    .put("sha256", physical.byteSha256());
            String content = text(physical.value(), "content_sha256");
            if (content.isEmpty()) manifestSpec.putNull("content_sha256");
            else manifestSpec.put("content_sha256", content);
            manifestReference = reference(physical.path(), "evidence_manifest");
        }

        ObjectNode readinessOptions = object();
        readinessOptions.set("evidence", object());
        if (manifestSpec == null) readinessOptions.putNull("evidenceManifest");
        else readinessOptions.set("evidenceManifest", manifestSpec);
        readinessOptions.put("generatedAt", firstTextOr(options, Instant.now().toString(),
                "generated_at", "generatedAt"));
        readinessOptions.put("now", defined(field(options, "now_at"))
                ? timestamp(field(options, "now_at"), "readiness now_at") : System.currentTimeMillis());
        ObjectNode audit = StrategyReadinessV5.buildReadinessAuditV5(readinessOptions);

        Path jsonPath = requestedOr(options,
                recordRoot(options).resolve("readiness").resolve(
                        "readiness-" + text(audit, "content_sha256") + ".json"), "out");
        String markdownOption = firstText(options, "markdown", "markdown_out");
        String jsonName = jsonPath.getFileName().toString();
        Path markdownPath = markdownOption.isEmpty()
                ? jsonPath.resolveSibling(jsonName.toLowerCase(Locale.ROOT).endsWith(".json")
                        ? jsonName.substring(0, jsonName.length() - 5) + ".md" : jsonName + ".md")
                : absolute(Path.of(markdownOption));
        writeImmutable(jsonPath, audit);
        byte[] markdownBytes = StrategyReadinessV5.renderReadinessMarkdown(audit)
                .getBytes(StandardCharsets.UTF_8);
        writeTextImmutable(markdownPath, markdownBytes);

        ArrayNode outputs = array().add(reference(jsonPath, "readiness_audit"));
        outputs.add(object().put("role", "readiness_markdown").put("storage", "PHYSICAL")
                .put("path", portablePath(markdownPath)).put("byte_sha256", hash(markdownBytes))
                .put("content_sha256", text(audit, "content_sha256"))
                .put("bytes", markdownBytes.length));
        ArrayNode limitations = array();
        if (manifestReference == null) limitations.add("PHYSICAL_EVIDENCE_MANIFEST_MISSING");
        for (JsonNode limitation : rows(audit.path("limitations"))) limitations.add(limitation.asText());
        ArrayNode inputs = array(); if (manifestReference != null) inputs.add(manifestReference);
        ObjectNode commandReceipt = receipt("readiness-audit",
                "BLOCKED".equals(text(audit.path("strategy_testing_readiness"), "status"))
                        ? "BLOCKED" : "COMPLETE",
                inputs, outputs, limitations,
                object().put("mode", "PHYSICAL_EVIDENCE_MANIFEST_REOPENED")
                        .put("record_count", audit.path("artifact_verification").size()));
        Path receiptPath = writeDurableReceipt(commandReceipt, options);
        ObjectNode result = object(); result.set("audit", audit); result.put("path", jsonPath.toString());
        result.put("markdown_path", markdownPath.toString()); result.set("receipt", commandReceipt);
        result.put("receipt_path", receiptPath.toString()); return result;
    }

    public static boolean assertLegacyFamilyMigrationBoundary(ObjectNode options) {
        Path root = absolute(Path.of(firstTextOr(options, "", "recordRoot", "record_root")));
        String family = text(options, "family"); List<Path> matches = new ArrayList<>();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) walkJson(root, path -> {
            if (path.toString().contains(Path.of("receipts").toString())) return;
            try {
                JsonNode value = readJsonBytes(path, "legacy family record").value();
                String schema = text(value, "schema");
                if (isAuthoritativeV5Schema(schema) || !isLegacySchema(schema)) return;
                String candidate = firstText(value, "strategy_family_id", "strategy_family", "strategy_id",
                        "hypothesis_family_id", "hypothesis_family", "family_id");
                if (candidate.isEmpty()) candidate = firstText(value.path("lineage"),
                        "strategy_family_id", "strategy_id", "hypothesis_family_id");
                boolean nested = false;
                for (String key : List.of("candidates", "declared_candidates", "candidate_metrics")) {
                    for (JsonNode row : rows(value.path(key))) if (family.equals(firstText(row,
                            "strategy_family_id", "strategy_family", "strategy_id", "hypothesis_family_id", "hypothesis_family", "family_id"))) nested = true;
                }
                if (family.equals(candidate) || nested) matches.add(path);
            } catch (RuntimeException ignored) { }
        });
        if (matches.isEmpty()) return true;
        long k = options.path("exposureHead").path("cumulative_k").asLong(
                options.path("exposure_head").path("cumulative_k").asLong(0));
        throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: legacy family " + family + " has "
                + matches.size() + " recoverable v1-v4 record(s); explicit physical exposure-head migration is required before v5 (current head K=" + k + ")");
    }

    /* ------------------------------------------------------------------ */
    /* Dispatcher and exact named aliases                                  */
    /* ------------------------------------------------------------------ */

    public static JsonNode runAuthoritativeV5Cli(String command, ObjectNode options) {
        ObjectNode value = options == null ? object() : options;
        return switch (command == null ? "" : command) {
            case "data-backfill" -> authoritativeDataBackfill(value);
            case "data-raw-replay", "data-local-raw-replay" -> authoritativeDataRawReplay(value);
            case "feature-build" -> authoritativeFeatureBuild(value);
            case "metadata-build" -> authoritativeMetadataBuild(value);
            case "opportunity-envelope" -> authoritativeOpportunityEnvelope(value);
            case "artifact-build" -> authoritativeArtifactBuild(value);
            case "research-init", "statistical-genesis" -> authoritativeResearchInit(value);
            case "experiment-freeze" -> authoritativeExperimentFreeze(value);
            case "search-genetic" -> authoritativeSearchGenetic(value);
            case "research-run" -> authoritativeResearchRun(value);
            case "overfit-audit" -> authoritativeOverfitAudit(value);
            case "prospective-runner" -> authoritativeProspectiveRunner(value);
            case "readiness-audit" -> authoritativeReadinessAudit(value);
            case "validate" -> validateCommand(value);
            case "index" -> indexCommand(value);
            default -> null;
        };
    }

    public static ObjectNode runDataBackfillV5(ObjectNode options) { return authoritativeDataBackfill(options); }
    public static ObjectNode runDataRawReplayV5(ObjectNode options) { return authoritativeDataRawReplay(options); }
    public static ObjectNode runFeatureBuildV5(ObjectNode options) { return authoritativeFeatureBuild(options); }
    public static ObjectNode runMetadataBuildV5(ObjectNode options) { return authoritativeMetadataBuild(options); }
    public static ObjectNode runOpportunityEnvelopeV5(ObjectNode options) { return authoritativeOpportunityEnvelope(options); }
    public static ObjectNode runArtifactBuildV5(ObjectNode options) { return authoritativeArtifactBuild(options); }
    public static ObjectNode runExperimentFreezeV5(ObjectNode options) { return authoritativeExperimentFreeze(options); }
    public static ObjectNode runSearchGeneticV5(ObjectNode options) { return authoritativeSearchGenetic(options); }
    public static ObjectNode runResearchRunV5(ObjectNode options) { return authoritativeResearchRun(options); }
    public static ObjectNode runOverfitAuditV5(ObjectNode options) { return authoritativeOverfitAudit(options); }
    public static ObjectNode runProspectiveRunnerV5(ObjectNode options) { return authoritativeProspectiveRunner(options); }

    /* ------------------------------------------------------------------ */
    /* Validation/index transaction implementation                         */
    /* ------------------------------------------------------------------ */

    private static ObjectNode validateCommand(ObjectNode options) {
        String input = text(options, "input");
        if (input.isEmpty()) throw failure("artifact to validate path is required");
        PhysicalJson physical = readJsonBytes(absolute(Path.of(input)), "artifact to validate");
        strictValidate(physical.value());
        ArrayNode inputs = array().add(physicalReference(physical, "artifact"));
        ObjectNode receipt = receipt("validate", "COMPLETE", inputs, array(), array(),
                object().put("mode", "STRICT_SCHEMA_AND_SEMANTIC"));
        Path receiptPath = writeDurableReceipt(receipt, options);
        ObjectNode result = object().put("valid", true)
                .put("schema", text(physical.value(), "schema"));
        result.set("receipt", receipt);
        result.put("receipt_path", receiptPath.toString());
        return result;
    }

    private static ObjectNode indexCommand(ObjectNode options) {
        Path root = absolute(Path.of(firstTextOr(options, "strategy-research/v5-records", "root")));
        Path output = requestedOr(options, root.resolve("index.json"), "out");
        byte[] prior = null;
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            prior = readSinglyLinked(output, "existing index"); JsonNode value = parse(prior, "existing index");
            if (!"strategy-research-index/5".equals(text(value, "schema"))
                    || !text(value, "content_sha256").equals(ownHash(value))) {
                throw failure("index retained-hash tampering: " + output);
            }
        }
        ObjectNode index = deterministicIndex(root, output);
        byte[] body = NodePrettyJson.write(index).getBytes(StandardCharsets.UTF_8);
        if (prior != null && parse(prior, "existing index").path("content_sha256").asText()
                .equals(text(index, "content_sha256")) && !hash(prior).equals(hash(body))) {
            throw failure("index physical bytes are tampered: " + output);
        }
        writeMutable(output, index);
        ArrayNode outputs = array().add(reference(output, "index"));
        ObjectNode receipt = receipt("index", "COMPLETE", array(), outputs, array(),
                object().put("mode", "DETERMINISTIC_SORTED_INDEX").put("record_count", index.path("records").size()));
        Path receiptPath = writeDurableReceipt(receipt, options);
        ObjectNode result = object().put("path", output.toString());
        result.set("index", index);
        result.set("receipt", receipt);
        result.put("receipt_path", receiptPath.toString());
        return result;
    }

    private static ObjectNode deterministicIndex(Path root, Path output) {
        ArrayNode records = array(); Map<String, String> byteByContent = new HashMap<>();
        PublicationInventory publication = publicationIndexInventory(root);
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) walkJson(root, path -> {
            Path relative = root.relativize(path); List<String> components = components(relative);
            if (components.contains("receipts") || components.stream().anyMatch(part -> part.equals("transactions")
                    || part.equals(".transactions") || part.equals("stage") || part.endsWith(".stage") || part.endsWith(".lock"))
                    || path.equals(output)) return;
            PhysicalJson physical = readJsonBytes(path, "indexed artifact"); JsonNode value = physical.value();
            String schema = text(value, "schema");
            Path normalized = absolute(path);
            if ((publication.owned().contains(normalized) && !publication.committed().contains(normalized))
                    || (publicationArtifact(value) && !publication.committed().contains(normalized))) return;
            strictValidate(value);
            if (schema.startsWith("strategy-research-index/")) {
                if (defined(value.get("content_sha256"))
                        && !hash(physical.rawBytes()).equals(hash(NodePrettyJson.write(value).getBytes(StandardCharsets.UTF_8)))) {
                    throw failure("index physical bytes are tampered: " + relative);
                }
                return;
            }
            String content = HASH.matcher(text(value, "content_sha256")).matches()
                    ? text(value, "content_sha256") : physical.byteSha256();
            String prior = byteByContent.putIfAbsent(content, physical.byteSha256());
            if (prior != null && !prior.equals(physical.byteSha256())) {
                throw failure("content collision: " + content + " has different physical bytes");
            }
            ObjectNode row = object().put("schema", schema).put("content_sha256", content)
                    .put("byte_sha256", physical.byteSha256())
                    .put("path", relative.toString().replace(relative.getFileSystem().getSeparator(), "/"));
            row.setAll(indexMetadata(value)); records.add(row);
        });
        List<JsonNode> sorted = rows(records).stream().sorted(Comparator.comparing(row ->
                text(row, "schema") + ":" + text(row, "content_sha256") + ":" + text(row, "path"))).toList();
        ObjectNode index = object().put("schema", "strategy-research-index/5").put("version", 1);
        index.set("records", array(sorted)); index = withHash(index); SCHEMAS.validateKnownContractSchema(index); return index;
    }

    private record PublicationInventory(Set<Path> owned, Set<Path> committed) {}
    private record PublicationJournal(ObjectNode value, Path path) {}

    /** Reopens publication journals before making WFO/run bytes visible to the index. */
    private static PublicationInventory publicationIndexInventory(Path rawRoot) {
        Path root = absolute(rawRoot); Set<Path> owned = new HashSet<>(); Set<Path> committed = new HashSet<>();
        List<PublicationJournal> journals = new ArrayList<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return new PublicationInventory(owned, committed);
        walkJson(root, path -> {
            Path relative = root.relativize(path); List<String> components = components(relative);
            if (components.stream().anyMatch(part -> part.equals("stage")
                    || part.endsWith(".stage") || part.endsWith(".lock"))) return;
            boolean transactionDirectory = components.contains("transactions")
                    || components.contains(".transactions");
            PhysicalJson physical;
            try { physical = readJsonBytes(path, "publication transaction journal"); }
            catch (RuntimeException error) {
                if (transactionDirectory) throw failure("publication transaction journal is unreadable: "
                        + path + ": " + error.getMessage());
                return;
            }
            ObjectNode journal = physical.value();
            String publicationSchema = StrategyStatisticalV5.STAT_SCHEMA.get("publicationTransaction");
            if (transactionDirectory && !publicationSchema.equals(text(journal, "schema"))) {
                throw failure("unexpected JSON control file under publication transaction directory: " + path);
            }
            if (!publicationSchema.equals(text(journal, "schema"))) return;
            try {
                StrategyStatisticalV5.validateContractSchema(journal);
                String portable = relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
                if (!portable.equals(text(journal, "transaction_path"))) {
                    throw failure("transaction_path does not match the physical record-root-relative journal path ("
                            + portable + ")");
                }
                journals.add(new PublicationJournal(journal, absolute(path)));
            } catch (RuntimeException error) {
                throw failure("publication transaction journal is not verifiable: " + path + ": "
                        + error.getMessage());
            }
        });
        for (PublicationJournal row : journals) {
            ObjectNode verified = null;
            if ("COMMITTED".equals(text(row.value(), "status"))) {
                try {
                    ObjectNode args = object(); args.set("journal", row.value());
                    args.put("journalPath", row.path().toString()); args.put("recordRoot", root.toString());
                    verified = StrategyStatisticalV5.verifyCommittedStatisticalPublication(args);
                } catch (RuntimeException error) {
                    throw failure("publication committed inventory is not verifiable: "
                            + text(row.value(), "transaction_path") + ": " + error.getMessage());
                }
            }
            for (JsonNode ref : rows(row.value().path("artifact_refs"))) {
                String relative = text(ref, "path"); Path target = absolute(root.resolve(relative));
                if (!target.startsWith(root)) {
                    throw failure("publication artifact path escapes the record root: " + relative);
                }
                owned.add(target);
                if (verified != null && target.toString().equals(
                        text(verified.path("artifactPaths"), text(ref, "role")))) committed.add(target);
            }
        }
        return new PublicationInventory(Set.copyOf(owned), Set.copyOf(committed));
    }

    private static boolean publicationArtifact(JsonNode value) {
        String schema = text(value, "schema");
        return "strategy-research-run/5".equals(schema)
                || StrategyStatisticalV5.STAT_SCHEMA.get("wfo").equals(schema);
    }

    private static ObjectNode indexMetadata(JsonNode value) {
        List<JsonNode> metrics = value.path("candidate_metrics").isArray()
                ? rows(value.path("candidate_metrics")) : List.of();
        List<JsonNode> candidates = value.path("candidates").isArray()
                ? rows(value.path("candidates")) : List.of();
        List<JsonNode> trades = value.path("trades").isArray() ? rows(value.path("trades")) : List.of();
        List<JsonNode> decisions = value.path("asset_decisions").isArray()
                ? rows(value.path("asset_decisions"))
                : value.path("asset_decisions_final").isArray()
                ? rows(value.path("asset_decisions_final")) : List.of();
        List<String> discovered = new ArrayList<>();
        for (JsonNode row : metrics) if (!text(row, "asset").isEmpty()) discovered.add(lower(text(row, "asset")));
        for (JsonNode row : decisions) if (!text(row, "asset").isEmpty()) discovered.add(lower(text(row, "asset")));
        for (JsonNode row : rows(value.path("episodes"))) if (!text(row, "asset").isEmpty()) discovered.add(lower(text(row, "asset")));
        if (!text(value, "asset").isEmpty()) discovered.add(lower(text(value, "asset")));
        JsonNode declaredAssets = defined(value.get("asset_set")) ? value.get("asset_set")
                : defined(value.get("assets")) ? value.get("assets") : strings(discovered);
        List<String> assetSet = rows(declaredAssets).stream().map(JsonNode::asText).map(StrategyResearchAuthoritativeV5::lower)
                .filter(asset -> !asset.isEmpty()).distinct().sorted().toList();
        Map<String, ObjectNode> perAssetMap = new TreeMap<>();
        for (String asset : assetSet) perAssetMap.put(asset, perAssetRow(asset));
        for (JsonNode metric : metrics) {
            String asset = lower(text(metric, "asset")); if (asset.isEmpty()) continue;
            ObjectNode row = perAssetMap.computeIfAbsent(asset, StrategyResearchAuthoritativeV5::perAssetRow);
            row.put("candidate_count", row.path("candidate_count").asLong() + 1);
            if (metric.path("metrics").isObject()) row.put("metric_count", row.path("metric_count").asLong() + 1);
            row.put("trade_count", row.path("trade_count").asLong() + rows(metric.path("trades")).size());
        }
        for (JsonNode decision : decisions) {
            String asset = lower(text(decision, "asset")); if (asset.isEmpty()) continue;
            ObjectNode row = perAssetMap.computeIfAbsent(asset, StrategyResearchAuthoritativeV5::perAssetRow);
            String status = firstText(decision, "status", "status_name");
            if (!status.isEmpty()) row.put("status", status);
            String decisionValue = text(decision, "decision");
            if (!decisionValue.isEmpty()) row.put("decision", decisionValue);
            else if (decision.path("pass").isBoolean()) row.put("decision",
                    decision.path("pass").asBoolean() ? "SHADOW" : "REJECTED");
        }
        JsonNode counts = value.path("counts").isObject() ? value.path("counts") : object();
        Long candidateCount = null;
        if (value.path("candidate_count").isIntegralNumber()) {
            candidateCount = Long.valueOf(value.path("candidate_count").asLong());
        } else if (counts.path("candidate_count").isIntegralNumber()) {
            candidateCount = Long.valueOf(counts.path("candidate_count").asLong());
        } else if (!metrics.isEmpty()) {
            candidateCount = Long.valueOf(metrics.size());
        } else if (!candidates.isEmpty()) {
            candidateCount = Long.valueOf(candidates.size());
        }
        Long metricCount = null;
        if (value.path("metric_count").isIntegralNumber()) {
            metricCount = Long.valueOf(value.path("metric_count").asLong());
        } else if (counts.path("metric_count").isIntegralNumber()) {
            metricCount = Long.valueOf(counts.path("metric_count").asLong());
        } else if (!metrics.isEmpty()) {
            metricCount = Long.valueOf(metrics.size());
        } else if (value.path("metrics").isArray()) {
            metricCount = Long.valueOf(value.path("metrics").size());
        }
        long nestedTrades = metrics.stream().mapToLong(row -> rows(row.path("trades")).size()).sum();
        Long tradeCount = null;
        if (value.path("trade_count").isIntegralNumber()) {
            tradeCount = Long.valueOf(value.path("trade_count").asLong());
        } else if (counts.path("trade_count").isIntegralNumber()) {
            tradeCount = Long.valueOf(counts.path("trade_count").asLong());
        } else if (!trades.isEmpty()) {
            tradeCount = Long.valueOf(trades.size());
        } else if (!metrics.isEmpty()) {
            tradeCount = Long.valueOf(nestedTrades);
        }

        String family = firstText(value, "strategy_family_id", "strategy_family", "hypothesis_family");
        if (family.isEmpty()) family = text(value.path("lineage"), "strategy_family_id");
        String experiment = text(value, "experiment_id");
        if (experiment.isEmpty()) experiment = text(value.path("lineage"), "experiment_id");
        if (experiment.isEmpty()) experiment = text(value.path("details"), "experiment_id");
        String phase = firstText(value, "evidence_phase", "phase");
        if (phase.isEmpty()) phase = firstText(value.path("metadata"), "evidence_phase", "phase");
        String status = text(value, "status"); if (status.isEmpty()) status = text(value.path("wfo"), "status");
        String decision = text(value, "decision"); if (decision.isEmpty()) decision = text(value.path("wfo"), "decision");
        ObjectNode result = object(); putNullable(result, "strategy_family_id", nullIfEmpty(family));
        putNullable(result, "strategy_version", nullIfEmpty(text(value, "strategy_version")));
        putNullable(result, "experiment_id", nullIfEmpty(experiment)); putNullable(result, "run_id", nullIfEmpty(text(value, "run_id")));
        putNullable(result, "asset", assetSet.size() == 1 ? assetSet.get(0) : null); result.set("asset_set", strings(assetSet));
        putNullable(result, "evidence_phase", indexEvidencePhase(phase)); putNullable(result, "status", nullIfEmpty(status));
        putNullable(result, "decision", nullIfEmpty(decision)); putNullable(result, "candidate_count", candidateCount);
        putNullable(result, "metric_count", metricCount); putNullable(result, "trade_count", tradeCount);
        result.set("per_asset", array(perAssetMap.values()));
        String sourceRun = firstText(value, "run_sha256", "source_run_sha256");
        if (sourceRun.isEmpty() && "strategy-research-run/5".equals(text(value, "schema"))) {
            sourceRun = text(value, "content_sha256");
        }
        putNullable(result, "source_run_sha256", nullIfEmpty(sourceRun)); return result;
    }

    private static ObjectNode perAssetRow(String asset) {
        return object().put("asset", asset).putNull("status").putNull("decision")
                .put("candidate_count", 0).put("metric_count", 0).put("trade_count", 0);
    }

    /* ------------------------------------------------------------------ */
    /* Internal custody and JSON utilities                                 */
    /* ------------------------------------------------------------------ */

    private record PhysicalJson(ObjectNode value, Path path, String byteSha256, long bytes, byte[] rawBytes) {
        private PhysicalJson { rawBytes = rawBytes.clone(); }
        @Override public byte[] rawBytes() { return rawBytes.clone(); }
    }

    private record Prerequisite(String key, String label, boolean directory, boolean createTarget) {}

    private static ObjectNode blockedOrUnavailable(String command, ObjectNode options, List<String> keys) {
        ObjectNode blocked = blockedPrerequisites(command, options,
                keys.stream().map(key -> prerequisite(key, key.replace('_', ' '))).toList());
        if (blocked != null) return blocked;
        return unavailableCommand(command, options,
                "AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: required native physical orchestration dependency is not configured");
    }

    private static ObjectNode unavailableCommand(String command, ObjectNode options, String limitation) {
        return blockedResult(command, options, List.of(limitation), "FAIL_CLOSED_RECOMPUTATION", limitation);
    }

    private static ObjectNode blockedResult(String command, ObjectNode options, List<String> limitations,
                                            String mode, String reason) {
        ObjectNode receipt = receipt(command, "BLOCKED", array(), array(), strings(limitations),
                object().put("mode", mode).put("reason", reason));
        Path receiptPath = writeDurableReceipt(receipt, options);
        ObjectNode result = object().put("status", "BLOCKED");
        result.set("receipt", receipt);
        result.put("receipt_path", receiptPath.toString());
        return result;
    }

    private static ObjectNode blockedPrerequisites(String command, ObjectNode options,
                                                    List<Prerequisite> required) {
        List<String> missing = new ArrayList<>(); ArrayNode inputs = array();
        for (Prerequisite row : required) {
            String value = text(options, row.key());
            if (value.isEmpty()) { missing.add(row.label() + ": missing physical prerequisite"); continue; }
            Path path;
            try { path = absolute(Path.of(value)); } catch (RuntimeException error) {
                missing.add(row.label() + ": path does not exist: " + value); continue;
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && row.createTarget()) {
                try {
                    if (row.directory()) createDirectoryCustody(path);
                    else if (path.getParent() != null) createDirectoryCustody(path.getParent());
                } catch (RuntimeException error) {
                    missing.add(row.label() + ": target parent is unavailable: " + value); continue;
                }
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !row.createTarget()) missing.add(row.label() + ": path does not exist: " + value);
            else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && row.directory()
                    && (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))) {
                missing.add(row.label() + ": path is not a physical directory: " + value);
            }
            else {
                ObjectNode reference = bestEffortPhysicalReference(path, row.key()); if (reference != null) inputs.add(reference);
            }
        }
        if (missing.isEmpty()) return null;
        ObjectNode receipt = receipt(command, "BLOCKED", inputs, array(), strings(missing),
                object().put("mode", "BLOCKED_MISSING_PHYSICAL_PREREQUISITES")
                        .put("reason", "authoritative command requires every listed physical input before recomputation"));
        Path path = writeDurableReceipt(receipt, options);
        ObjectNode result = object().put("status", "BLOCKED");
        result.set("receipt", receipt);
        result.put("receipt_path", path.toString());
        return result;
    }

    private static ObjectNode receipt(String command, String status, JsonNode inputs, JsonNode outputs,
                                      JsonNode limitations, ObjectNode details) {
        ObjectNode options = object().put("command", command).put("status", status);
        options.set("inputs", inputs == null ? array() : inputs); options.set("outputs", outputs == null ? array() : outputs);
        options.set("limitations", limitations == null ? array() : limitations); options.set("details", details == null ? object() : details);
        return makeCommandReceipt(options);
    }

    private static Path writeDurableReceipt(ObjectNode receipt, ObjectNode options) {
        Path path = defined(first(options, "receipt", "receipt_out"))
                ? absolute(Path.of(firstText(options, "receipt", "receipt_out")))
                : recordRoot(options).resolve("receipts").resolve(text(receipt, "content_sha256") + ".json");
        writeImmutable(path, receipt); return path;
    }

    private static Path durableArtifactPath(ObjectNode options, ObjectNode value, String role) {
        return recordRoot(options).resolve("artifacts").resolve(role + "-" + text(value, "content_sha256") + ".json");
    }

    private static Path recordRoot(ObjectNode options) {
        return absolute(Path.of(firstTextOr(options, "strategy-research/v5-records", "record_root", "recordRoot")));
    }

    private static void writeImmutable(Path rawPath, JsonNode value) {
        Path path = absolute(rawPath); createParents(path);
        String schema = text(value, "schema");
        if (!schema.isEmpty()) {
            if (SCHEMAS.hasContractSchema(schema)) SCHEMAS.validateKnownContractSchema(value);
            else if (StrategyStatisticalV5.STAT_SCHEMA.containsValue(schema)) {
                StrategyStatisticalV5.validateContractSchema(value);
            }
        }
        byte[] body = NodePrettyJson.write(value).getBytes(StandardCharsets.UTF_8);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] existing = readSinglyLinked(path, "existing immutable artifact"); JsonNode parsed = parse(existing, "existing immutable artifact");
            if (!HASH.matcher(text(parsed, "content_sha256")).matches() || !text(parsed, "content_sha256").equals(ownHash(parsed))) {
                throw failure("immutable artifact tampering detected: " + path);
            }
            if (!text(parsed, "content_sha256").equals(text(value, "content_sha256"))) {
                throw failure("immutable output collision: " + path);
            }
            if (!hash(existing).equals(hash(body))) throw failure("immutable artifact bytes tampered: " + path);
            return;
        }
        try {
            Files.write(path, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            byte[] reopened = readSinglyLinked(path, "new immutable artifact");
            if (!java.util.Arrays.equals(reopened, body)) {
                throw failure("immutable artifact changed while it was being committed: " + path);
            }
        }
        catch (FileAlreadyExistsException error) { writeImmutable(path, value); }
        catch (IOException error) { throw failure("immutable output write failed: " + error.getMessage()); }
    }

    /** Exact immutable JSON bytes for keyed bundles that intentionally have no top-level schema/hash. */
    private static void writeImmutableBundle(Path rawPath, ObjectNode value) {
        Path path = absolute(rawPath); createParents(path);
        byte[] body = NodePrettyJson.write(value).getBytes(StandardCharsets.UTF_8);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] existing = readSinglyLinked(path, "existing immutable metadata bundle");
            if (!java.util.Arrays.equals(existing, body)) {
                throw failure("metadata bundle output is tampered or collides: " + path);
            }
            return;
        }
        try {
            Files.write(path, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException error) {
            writeImmutableBundle(path, value);
        } catch (IOException error) {
            throw failure("metadata bundle output cannot be written: " + path + ": " + error.getMessage());
        }
        byte[] reopened = readSinglyLinked(path, "written immutable metadata bundle");
        if (!java.util.Arrays.equals(reopened, body)) {
            throw failure("metadata bundle output changed after write: " + path);
        }
    }

    private static void writeTextImmutable(Path rawPath, byte[] rawBytes) {
        Path path = absolute(rawPath); createParents(path); byte[] body = rawBytes.clone();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] existing = readSinglyLinked(path, "existing immutable text artifact");
            if (!java.util.Arrays.equals(existing, body)) {
                throw failure("immutable text output collision: " + path);
            }
            return;
        }
        try {
            Files.write(path, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            byte[] reopened = readSinglyLinked(path, "new immutable text artifact");
            if (!java.util.Arrays.equals(reopened, body)) {
                throw failure("immutable text artifact changed while it was being committed: " + path);
            }
        } catch (FileAlreadyExistsException error) {
            writeTextImmutable(path, body);
        } catch (IOException error) {
            throw failure("immutable text output write failed: " + error.getMessage());
        }
    }

    private static void writeMutable(Path rawPath, JsonNode value) {
        Path path = absolute(rawPath); createParents(path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            readSinglyLinked(path, "existing mutable artifact");
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        byte[] body = NodePrettyJson.write(value).getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(temporary, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            byte[] staged = readSinglyLinked(temporary, "staged mutable artifact");
            if (!java.util.Arrays.equals(staged, body)) {
                throw failure("mutable staged bytes changed before commit: " + temporary);
            }
            fault("mutable-after-stage");
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) {
                throw failure("mutable output requires an atomic same-directory commit: " + path);
            }
            byte[] committed = readSinglyLinked(path, "committed mutable artifact");
            if (!java.util.Arrays.equals(committed, body)) {
                throw failure("mutable committed bytes differ from the staged bytes: " + path);
            }
            fault("mutable-after-commit");
        } catch (RuntimeException error) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw error;
        } catch (IOException error) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw failure("mutable output write failed: " + error.getMessage());
        }
    }

    static void withWriteFaultHookForTest(Consumer<String> hook, Runnable action) {
        Consumer<String> previous = WRITE_FAULT_HOOK.get(); WRITE_FAULT_HOOK.set(hook);
        try { action.run(); } finally { if (previous == null) WRITE_FAULT_HOOK.remove(); else WRITE_FAULT_HOOK.set(previous); }
    }

    private static void fault(String point) { Consumer<String> hook = WRITE_FAULT_HOOK.get(); if (hook != null) hook.accept(point); }

    private static ObjectNode reference(Path path, String role) {
        PhysicalJson physical = physicalJson(path, role, Set.of()); return physicalReference(physical, role);
    }

    private static ObjectNode physicalReference(PhysicalJson physical, String role) {
        String content = text(physical.value(), "content_sha256");
        ObjectNode result = object().put("role", role).put("storage", "PHYSICAL")
                .put("path", portablePath(physical.path())).put("byte_sha256", physical.byteSha256());
        putNullable(result, "content_sha256", HASH.matcher(content).matches() ? content : null);
        result.put("bytes", physical.bytes()); return result;
    }

    private static ObjectNode metadataReference(PhysicalMetadataBundle physical, String role) {
        return object().put("role", role).put("storage", "PHYSICAL")
                .put("path", portablePath(physical.path())).put("byte_sha256", physical.byteSha256())
                .put("content_sha256", physical.contentSha256()).put("bytes", physical.bytes());
    }

    private static ObjectNode bestEffortPhysicalReference(Path path, String role) {
        try {
            byte[] bytes = readSinglyLinked(path, role); ObjectNode result = object().put("role", role)
                    .put("storage", "PHYSICAL").put("path", portablePath(path)).put("byte_sha256", hash(bytes)).put("bytes", bytes.length);
            try { JsonNode value = parse(bytes, role); putNullable(result, "content_sha256",
                    text(value, "content_sha256").equals(ownHash(value)) ? text(value, "content_sha256") : null); }
            catch (RuntimeException ignored) { result.putNull("content_sha256"); }
            return result;
        } catch (RuntimeException error) { return null; }
    }

    private static PhysicalJson frozenPrecommit(Path path, String label) {
        PhysicalJson physical = physicalJson(path, label, Set.of("strategy-precommit/1"));
        if (!"FROZEN".equals(text(physical.value(), "status"))) throw failure(label + " must have status FROZEN");
        return physical;
    }

    private static PhysicalJson physicalJson(Path path, String label, Set<String> schemas) {
        PhysicalJson physical = readJsonBytes(absolute(path), label);
        if (!schemas.isEmpty() && !schemas.contains(text(physical.value(), "schema"))) {
            throw failure(label + " schema is not one of " + String.join(", ", schemas));
        }
        String content = text(physical.value(), "content_sha256");
        if (!HASH.matcher(content).matches() || !content.equals(ownHash(physical.value()))) {
            throw failure(label + " content hash is missing or tampered");
        }
        String schema = text(physical.value(), "schema");
        if (SCHEMAS.hasContractSchema(schema)) SCHEMAS.validateKnownContractSchema(physical.value());
        else if (StrategyStatisticalV5.STAT_SCHEMA.containsValue(schema)) {
            StrategyStatisticalV5.validateContractSchema(physical.value());
        }
        return physical;
    }

    private static PhysicalJson readJsonBytes(Path path, String label) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw failure(label + " is missing: " + path);
        byte[] bytes = readSinglyLinked(path, label); JsonNode value = parse(bytes, label);
        if (!(value instanceof ObjectNode object)) throw failure(label + " is not a JSON object");
        return new PhysicalJson(object, path, hash(bytes), bytes.length, bytes);
    }

    private static byte[] readSinglyLinked(Path path, String label) {
        try { PathConfinement.validateSinglyLinkedFile(path, label); return PathConfinement.readSinglyLinkedFile(path, label); }
        catch (RuntimeException error) { throw failure(error.getMessage()); }
    }

    private static JsonNode parse(byte[] bytes, String label) {
        try { return JSON.readTree(bytes); }
        catch (IOException error) { throw failure(label + " is not valid JSON: " + error.getMessage()); }
    }

    private static void strictValidate(JsonNode value) {
        String schema = text(value, "schema"); if (schema.isEmpty()) throw failure("schema registry does not recognize ?");
        if ("strategy-research-index/1".equals(schema)) {
            if (!value.path("definitions").isArray() || !value.path("experiments").isArray() || !value.path("runs").isArray()) {
                throw failure("legacy strategy-research-index/1 records are invalid");
            }
            return;
        }
        if (!isAuthoritativeV5Schema(schema)) {
            if (!isLegacySchema(schema)) throw failure("legacy schema is not allowed at the v5 boundary: " + schema);
            SCHEMAS.validateKnownContractSchema(value); return;
        }
        if (isAuthoritativeV5Schema(schema) && defined(value.get("content_sha256"))
                && !text(value, "content_sha256").equals(ownHash(value))) throw failure("artifact content hash is tampered");
        if (SCHEMAS.hasContractSchema(schema)) SCHEMAS.validateKnownContractSchema(value);
        else if (StrategyStatisticalV5.STAT_SCHEMA.containsValue(schema)) {
            StrategyStatisticalV5.validateContractSchema(value);
        } else throw failure("schema registry does not recognize " + schema);
        if ("strategy-portfolio-policy/2".equals(schema)) validateAuthoritativePortfolioPolicy(value);
        if (StrategyStatisticalV5.STAT_SCHEMA.get("exposure").equals(schema)) {
            StrategyStatisticalV5.validateExposureHead(value);
        }
        if (StrategyStatisticalV5.STAT_SCHEMA.get("input").equals(schema)) {
            ObjectNode args = object().put("allowSubset", true);
            StrategyStatisticalV5.validateStatisticalArtifactSet(value, args);
        }
        if (StrategyResearchDataV5.DATA_V5.get("plan").equals(schema)) {
            if (!"PLAN_ONLY".equals(text(value, "status")) || value.path("window").path("years").asInt() != 5
                    || !sortedTexts(value.path("assets"), true).equals(List.of("aave", "ada", "bnb", "btc", "eth", "link", "sol", "xrp"))) {
                throw failure("authoritative data plan semantic contract is invalid");
            }
        }
        if ("strategy-v5-opportunity-envelope/1".equals(schema)) {
            long lifecycle = value.path("max_lifecycle_ms").asLong(Long.MIN_VALUE);
            if (!"FROZEN".equals(text(value, "status")) || !value.path("windows").isArray()
                    || value.path("windows").isEmpty() || lifecycle == Long.MIN_VALUE
                    || rows(value.path("windows")).stream().anyMatch(window ->
                    window.path("max_lifecycle_ms").asLong(Long.MIN_VALUE) != lifecycle)) {
                throw failure("opportunity envelope semantic contract is invalid");
            }
        }
        if ("strategy-v5-evaluator-spec/1".equals(schema)) StrategyEvaluatorV5.validateEvaluatorSpecV5(value);
    }

    private static boolean isAuthoritativeV5Schema(String schema) {
        return schema.startsWith("strategy-v5-") || schema.endsWith("/5") || "strategy-portfolio-policy/2".equals(schema);
    }

    private static boolean isLegacySchema(String schema) {
        if (!SCHEMAS.hasContractSchema(schema) || isAuthoritativeV5Schema(schema)) return false;
        return schema.matches(".*/[1-4]$");
    }

    private static void requireBoundContract(ObjectNode value, String schema, String label) {
        if (!schema.equals(text(value, "schema")) || !HASH.matcher(text(value, "content_sha256")).matches()
                || !text(value, "content_sha256").equals(ownHash(value))) {
            throw failure("coverage report requires a hash-valid " + label);
        }
        SCHEMAS.validateKnownContractSchema(value);
    }

    private static void validateCaptureBindings(ObjectNode plan, ObjectNode manifest, String label) {
        if (manifest == null) return;
        Map<String, JsonNode> planRows = new HashMap<>();
        for (JsonNode series : rows(plan.path("series"))) planRows.put(captureKey(series), series);
        Set<String> seen = new HashSet<>();
        for (JsonNode capture : rows(manifest.path("captures"))) {
            String key = captureKey(capture); JsonNode series = planRows.get(key);
            if (series == null) throw failure(label + " capture is not declared by the frozen plan: " + key);
            if (!seen.add(key)) throw failure(label + " contains duplicate capture identity: " + key);
            if (!text(capture, "series_sha256").equals(hash(series))) throw failure(label + " capture series binding is stale: " + key);
            for (String field : List.of("asset", "venue", "instrument", "symbol", "interval", "series_type", "series_role", "start_at", "end_at", "availability_cutoff_at", "expected_step_ms", "expected_event_count", "event_driven", "event_sequence_mode")) {
                if (series.has(field) && !series.get(field).asText().equals(capture.path(field).asText())) {
                    throw failure(label + " capture " + key + " does not match frozen plan field " + field);
                }
            }
        }
    }

    private static ObjectNode coveragePartition(JsonNode partition) {
        if (partition == null || !partition.isObject() || !HASH.matcher(text(partition, "sha256")).matches()
                || !partition.path("bytes").isIntegralNumber() || partition.path("bytes").asLong() < 1
                || !partition.path("row_count").isIntegralNumber() || partition.path("row_count").asLong() < 0) return null;
        ObjectNode result = object().put("path", text(partition, "path")).put("byte_sha256", text(partition, "sha256"))
                .put("bytes", partition.path("bytes").asLong()).put("row_count", partition.path("row_count").asLong())
                .put("format", text(partition, "format")).put("authoritative", partition.path("authoritative").asBoolean(false));
        if (defined(partition.get("storage_role"))) result.put("storage_role", text(partition, "storage_role")); return result;
    }

    private static ArrayNode receiptHashes(JsonNode capture, String field, boolean bytes) {
        List<String> result = new ArrayList<>();
        if (capture != null) for (JsonNode receipt : rows(capture.path(field))) {
            JsonNode value = bytes ? receipt.get("byte_sha256") : first(receipt, "content_sha256", "sha256");
            if (value != null && value.isArray()) for (JsonNode item : value) if (HASH.matcher(item.asText()).matches()) result.add(item.asText());
            else if (value != null && HASH.matcher(value.asText()).matches()) result.add(value.asText());
        }
        return strings(result.stream().distinct().sorted().toList());
    }

    private static ArrayNode aggregateReceiptHashes(ObjectNode acquisition, String field, boolean bytes) {
        List<String> result = new ArrayList<>(); if (acquisition != null) for (JsonNode capture : rows(acquisition.path("captures"))) {
            for (JsonNode value : receiptHashes(capture, field, bytes)) result.add(value.asText());
        }
        return strings(result.stream().distinct().sorted().toList());
    }

    private static List<String> normalizeLimitations(Collection<String> values, JsonNode... captures) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) if (value != null && !value.contains("[object Object]")) result.add(value);
        for (JsonNode capture : captures) if (capture != null) for (JsonNode irregular : rows(capture.path("coverage").path("irregular_bars"))) {
            result.add(coverageIdentity(capture) + ":irregular_bars=" + stable(irregular));
        }
        return result.stream().sorted().toList();
    }

    private static Map<String, JsonNode> byCoverageIdentity(JsonNode values) {
        Map<String, JsonNode> result = new HashMap<>(); for (JsonNode value : rows(values)) result.put(coverageIdentity(value), value); return result;
    }

    private static String coverageIdentity(JsonNode value) {
        return String.join("|", List.of("asset", "instrument", "symbol", "series_type", "interval").stream()
                .map(key -> lower(text(value, key))).toList());
    }

    private static String captureKey(JsonNode value) {
        return String.join("|", List.of("asset", "instrument", "symbol", "interval", "series_type", "series_role").stream()
                .map(key -> lower(text(value, key))).toList());
    }

    private static ObjectNode geneticCheckpointConfig() {
        Map<String, Object> defaults = StrategyStatisticalV5.STAT_DEFAULTS; ObjectNode config = object();
        config.put("population", ((Number) defaults.get("population")).intValue());
        config.put("generations", ((Number) defaults.get("generations")).intValue());
        config.put("minGenerations", ((Number) defaults.get("minGenerations")).intValue());
        config.put("plateauGenerations", ((Number) defaults.get("plateauGenerations")).intValue());
        config.put("crossoverProbability", ((Number) defaults.get("crossoverProbability")).doubleValue());
        if (defaults.get("mutationProbability") == null) config.putNull("mutationProbability");
        else config.put("mutationProbability", ((Number) defaults.get("mutationProbability")).doubleValue());
        config.set("seeds", JSON.valueToTree(defaults.get("seeds")));
        config.put("halfLifeMonths", ((Number) defaults.get("halfLifeMonths")).intValue());
        config.put("operator", "ARITHMETIC_CROSSOVER_UNIFORM_MUTATION")
                .put("scheduler_ordering", "STABLE_SEED_GENERATION_CHROMOSOME_ORDER").put("mode", "AUTHORITATIVE");
        return config;
    }

    private static boolean exactSettlementIdentity(JsonNode row, JsonNode execution) {
        return lower(text(row, "asset")).equals(lower(text(execution, "asset")))
                && text(row, "venue").equalsIgnoreCase(text(execution, "venue"))
                && text(row, "symbol").equalsIgnoreCase(text(execution, "symbol"))
                && text(row, "instrument").equalsIgnoreCase(text(execution, "instrument"));
    }

    private static void rejectLooseOptions(ObjectNode options, Set<String> allowed) {
        Iterator<String> names = options.fieldNames(); while (names.hasNext()) {
            String key = names.next(), lower = key.toLowerCase(Locale.ROOT);
            if (lower.endsWith("_out") || allowed.contains(lower)) continue;
            if (LOOSE_KEYS.contains(lower) || List.of("fitness", "returns", "trades", "fills", "metrics", "stress", "portfolio", "wfo").stream().anyMatch(lower::contains)) {
                throw failure("--" + key + " caller-supplied statistical output is rejected; use physical artifacts");
            }
        }
    }

    private static String normalizedProductionInstrument(String value, String label) {
        String key = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        String result = PRODUCTION_INSTRUMENT_ALIASES.get(key);
        if (result == null || !PRODUCTION_INSTRUMENTS.contains(result)) throw failure(label + " is not a supported frozen v5 instrument");
        return result;
    }

    private static List<String> exactUniqueStrings(List<String> values, String label) {
        if (values == null || values.isEmpty()) throw failure(label + " must be a non-empty array");
        List<String> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); int index = 0;
        for (String raw : values) {
            String value = raw == null ? "" : raw;
            if (value.isEmpty()) throw failure(label + "[" + index + "] is empty");
            if (!seen.add(value)) throw failure(label + " contains a duplicate identity"); result.add(value); index++;
        }
        result.sort(String::compareTo); return result;
    }

    private static void assertExactSet(List<String> actual, List<String> expected, String label) {
        if (!stable(strings(actual)).equals(stable(strings(expected)))) throw failure(label + " differs from the frozen research scope");
    }

    private static String requireSha(String value, String label) {
        if (!HASH.matcher(value == null ? "" : value).matches()) throw failure(label + " must be a SHA-256 hash"); return value;
    }

    private static void requireEqual(JsonNode object, String field, String expected, String message) {
        if (!text(object, field).equals(expected)) throw failure(message);
    }

    private static String portablePath(Path path) {
        Path cwd = absolute(Path.of(".")); Path value = absolute(path);
        try { String result = cwd.relativize(value).toString().replace(value.getFileSystem().getSeparator(), "/"); return result.isEmpty() ? "." : result; }
        catch (IllegalArgumentException error) { return value.toString().replace(value.getFileSystem().getSeparator(), "/"); }
    }

    private static Path repositoryRoot() {
        String explicit = System.getProperty("tradinganalytics.repository.root", "").trim();
        if (!explicit.isEmpty()) {
            Path root = absolute(Path.of(explicit));
            if (!isJavaRepositoryRoot(root)) {
                throw failure("explicit tradinganalytics.repository.root is not the Java/Maven repository root: "
                        + root);
            }
            return root;
        }
        Path cursor = absolute(Path.of(System.getProperty("user.dir")));
        while (cursor != null && !isJavaRepositoryRoot(cursor)) cursor = cursor.getParent();
        if (cursor == null) {
            throw failure("Java/Maven repository root not found; set -Dtradinganalytics.repository.root=<path>");
        }
        return cursor;
    }

    private static boolean isJavaRepositoryRoot(Path root) {
        if (root == null || !Files.isRegularFile(root.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) return false;
        Path owners = root.resolve("analytics-research/src/main/java/com/tradinganalytics/research/v5");
        return Files.isDirectory(owners, LinkOption.NOFOLLOW_LINKS)
                && (Files.isRegularFile(owners.resolve("StrategyEvaluatorV5.java"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(owners.resolve("StrategyResearchDataV5.java"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(owners.resolve("StrategyResearchAuthoritativeV5.java"), LinkOption.NOFOLLOW_LINKS));
    }

    private static void walkJson(Path root, Consumer<Path> action) {
        try (var stream = Files.walk(root)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().forEach(action);
        } catch (IOException error) { throw failure(error.getMessage()); }
    }

    private static List<String> components(Path path) {
        List<String> result = new ArrayList<>(); path.forEach(part -> result.add(part.toString())); return result;
    }

    private static void createParents(Path path) {
        Path parent = absolute(path).getParent();
        if (parent == null) throw failure("output path has no parent: " + path);
        createDirectoryCustody(parent);
    }

    private static void createDirectoryCustody(Path directory) {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    throw failure("output parent must be a physical directory, not a link: " + directory);
                }
                return;
            } catch (IOException error) {
                throw failure("output parent cannot be verified: " + directory + ": " + error.getMessage());
            }
        }
        Path parent = directory.getParent();
        if (parent == null) throw failure("output parent cannot be created: " + directory);
        createDirectoryCustody(parent);
        try { Files.createDirectory(directory); }
        catch (FileAlreadyExistsException ignored) { }
        catch (IOException error) { throw failure("output parent cannot be created: " + directory + ": " + error.getMessage()); }
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw failure("output parent creation was redirected: " + directory);
            }
        } catch (IOException error) {
            throw failure("output parent cannot be verified after creation: " + directory + ": " + error.getMessage());
        }
    }

    private static Path absolute(Path value) { return value.toAbsolutePath().normalize(); }
    private static Path requirePhysicalDirectory(Path value, String label) {
        try { return PathConfinement.requireRealDirectory(absolute(value), label); }
        catch (RuntimeException error) { throw failure(error.getMessage()); }
    }

    private static Path ensurePhysicalDirectory(Path value, String label) {
        Path path = absolute(value); createDirectoryCustody(path); return requirePhysicalDirectory(path, label);
    }

    /** Reopens every lazy v2 partition and recomputes the OpportunityV5 hydration core. */
    private record V2HydrationCustody(ArrayNode partitions, Path root, String descriptorRoot) {}

    private static V2HydrationCustody verifyV2OpportunityHydration(ObjectNode domain, ObjectNode envelope,
                                                                    ObjectNode hydration, Path rawRoot,
                                                                    String planSha256) {
        OpportunityV5.validateOpportunityDomainV5(domain); OpportunityV5.validateOpportunityEnvelopeV5(envelope);
        if (!text(envelope, "plan_sha256").equals(planSha256)) {
            throw failure("v2 opportunity envelope is bound to a different plan");
        }
        if (domain.path("fixture_only").asBoolean(true) || !"AUTHORITATIVE".equals(text(domain, "provenance"))
                || !text(domain, "content_sha256").equals(text(envelope, "opportunity_domain_sha256"))) {
            throw failure("authoritative search requires a hash-bound opportunity-domain/1 artifact");
        }
        for (String key : List.of("candidate_set_sha256", "gene_space_sha256", "evaluator_spec_sha256",
                "predictor_registry_sha256", "precommit_sha256")) {
            if (!text(domain, key).equals(text(envelope, key))) {
                throw failure("v2 opportunity domain lineage differs from envelope");
            }
        }
        if (!"strategy-v5-opportunity-hydration/2".equals(text(hydration, "schema"))
                || !text(hydration, "content_sha256").equals(ownHash(hydration))) {
            throw failure("authoritative search requires a hash-bound opportunity-hydration/2 artifact");
        }
        if (!text(hydration, "envelope_sha256").equals(text(envelope, "content_sha256"))
                || hydration.path("fixture_only").asBoolean(true)
                || !"AUTHORITATIVE".equals(text(hydration, "provenance"))) {
            throw failure("v2 opportunity hydration is not bound to the authoritative v2 envelope");
        }
        Path root = requirePhysicalDirectory(rawRoot, "authoritative v2 hydration partition root");
        Map<String, ObjectNode> inventory = new LinkedHashMap<>(); ArrayNode partitions = array(), marks = array();
        for (JsonNode raw : hydration.path("partition_inventory")) {
            if (!(raw instanceof ObjectNode descriptor)) throw failure("v2 hydration partition inventory is incomplete or duplicated");
            String sha = text(descriptor, "partition_sha256"); String relative = text(descriptor, "partition_path");
            if (!HASH.matcher(sha).matches() || relative.isEmpty() || inventory.putIfAbsent(sha, descriptor) != null) {
                throw failure("v2 hydration partition inventory is incomplete or duplicated");
            }
            PathConfinement.ResolvedPath resolved;
            try { resolved = PathConfinement.resolve(root, relative, "v2 hydration partition",
                    PathConfinement.ExpectedType.FILE); }
            catch (RuntimeException error) { throw failure(error.getMessage()); }
            byte[] bytes = readSinglyLinked(resolved.absolute(), "v2 hydration partition");
            if (!sha.equals(hash(bytes)) || descriptor.path("bytes").asLong(-1) != bytes.length) {
                throw failure("v2 hydration partition bytes are tampered: " + relative);
            }
            ObjectNode partition = descriptor.deepCopy(); partition.put("sha256", sha);
            partition.put("path", resolved.absolute().toString());
            if ("MARK".equals(text(partition, "series_role").toUpperCase(Locale.ROOT))) marks.add(partition);
            else partitions.add(partition);
        }
        if (inventory.isEmpty()) throw failure("v2 hydration has no physical partition inventory");
        List<ObjectNode> descriptorRows = inventory.values().stream().map(row -> {
            ObjectNode item = object().put("partition_sha256", text(row, "partition_sha256"))
                    .put("partition_path", text(row, "partition_path"))
                    .put("bytes", row.path("bytes").asLong()).put("row_count", row.path("row_count").asLong());
            for (String key : List.of("min_event_time", "max_event_time", "asset", "instrument", "symbol")) {
                item.set(key, defined(row.get(key)) ? row.get(key).deepCopy() : NullNode.instance);
            }
            item.put("series_role", firstTextOr(row, "PRICE", "series_role")); return item;
        }).sorted(Comparator.comparing(row -> text(row, "partition_sha256"))).toList();
        String descriptorRoot = hash(array(descriptorRows));
        List<String> hashes = inventory.keySet().stream().sorted().toList();
        if (!hash(strings(hashes)).equals(text(hydration, "partition_set_sha256"))) {
            throw failure("v2 hydration partition-set digest differs from the reopened inventory");
        }
        if (defined(hydration.get("partition_bytes_root_sha256"))
                && !descriptorRoot.equals(text(hydration, "partition_bytes_root_sha256"))) {
            throw failure("v2 hydration partition byte/root digest differs from the reopened inventory");
        }
        ObjectNode recompute = object(); recompute.set("envelope", envelope); recompute.set("partitions", partitions);
        recompute.set("markPartitions", marks); recompute.put("fixtureOnly", false);
        recompute.put("maxRows", 50_000_000L).put("maxTotalBytes", 2L * 1024 * 1024 * 1024)
                .put("maxResidentBytes", 192L * 1024 * 1024);
        ObjectNode core = OpportunityV5.hydrateOpportunityEnvelopeV5(recompute);
        if (!stable(core.path("windows")).equals(stable(hydration.path("windows")))
                || core.path("windows").size() != envelope.path("windows").size()) {
            throw failure("v2 hydration/envelope window inventory does not reconcile");
        }
        for (JsonNode window : hydration.path("windows")) {
            if (!"COMPLETE".equals(text(window, "lifecycle_status")) || !window.path("eligible").asBoolean(false)) {
                throw failure("v2 hydration window " + text(window, "window_id") + " is incomplete or not in the envelope");
            }
        }
        ArrayNode all = array(); partitions.forEach(all::add); marks.forEach(all::add);
        return new V2HydrationCustody(all, root, descriptorRoot);
    }

    private static List<ObjectNode> readPhysicalParquetRoleRows(ObjectNode manifest, Path root, String role) {
        JsonNode descriptor = manifest.path("artifacts").path(role);
        String relative = text(descriptor, "path"); String expected = text(descriptor, "sha256");
        if (relative.isEmpty() || !HASH.matcher(expected).matches()) {
            throw failure("authoritative " + role + " Parquet role is missing");
        }
        PathConfinement.ResolvedPath resolved;
        try { resolved = PathConfinement.resolve(root, relative, "authoritative " + role + " Parquet role",
                PathConfinement.ExpectedType.FILE); }
        catch (RuntimeException error) { throw failure(error.getMessage()); }
        byte[] bytes = readSinglyLinked(resolved.absolute(), "authoritative " + role + " Parquet role");
        if (!expected.equals(hash(bytes))
                || descriptor.path("bytes").canConvertToLong() && descriptor.path("bytes").asLong() != bytes.length) {
            throw failure("authoritative " + role + " Parquet role is missing or tampered");
        }
        try {
            return ResearchData.queryParquet(resolved.absolute()).stream()
                    .map(StrategyResearchAuthoritativeV5::normalizePhysicalParquetRow).toList();
        }
        catch (RuntimeException error) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: Parquet " + role
                    + " reader is unavailable: " + error.getMessage());
        }
    }

    /**
     * DuckDB exposes TIMESTAMP columns through JDBC as SQL-style text.  Keep
     * the reopened role rows in the same ISO-millisecond JSON representation
     * used by the producers and the evaluator, without changing the bytes
     * whose hash was verified above.
     */
    private static ObjectNode normalizePhysicalParquetRow(ObjectNode row) {
        ObjectNode normalized = row.deepCopy();
        Iterator<Map.Entry<String, JsonNode>> fields = normalized.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            normalized.set(field.getKey(), normalizePhysicalParquetValue(
                    field.getKey(), field.getValue()));
        }
        return normalized;
    }

    private static JsonNode normalizePhysicalParquetValue(String name, JsonNode source) {
        JsonNode value = decodeDuckDbComposite(source);
        if (value.isObject()) {
            ObjectNode result = (ObjectNode) value.deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = result.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), normalizePhysicalParquetValue(field.getKey(), field.getValue()));
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = array();
            for (JsonNode child : value) result.add(normalizePhysicalParquetValue(name, child));
            return result;
        }
        String lowered = name.toLowerCase(Locale.ROOT);
        if (value.isTextual() && (lowered.equals("expiry") || lowered.endsWith("_time")
                || lowered.endsWith("_at") || lowered.equals("time"))) {
            long epoch = timestampOrMin(value);
            if (epoch != Long.MIN_VALUE) return NODES.textNode(ISO_MILLIS.format(Instant.ofEpochMilli(epoch)));
        }
        return value;
    }

    /** ResearchData's DuckDB adapter exposes LIST/STRUCT columns as toString(). */
    private static JsonNode decodeDuckDbComposite(JsonNode source) {
        if (source == null || !source.isTextual()) return source;
        String raw = source.asText().trim();
        if (raw.startsWith("{") && raw.endsWith("}")) return decodeDuckDbMap(raw);
        if (raw.startsWith("[") && raw.endsWith("]")) return decodeDuckDbArray(raw);
        return source;
    }

    private static ObjectNode decodeDuckDbMap(String raw) {
        ObjectNode result = object();
        String body = raw.substring(1, raw.length() - 1).trim();
        if (body.isEmpty()) return result;
        for (String token : splitDuckDbTopLevel(body, ',')) {
            int equals = duckDbTopLevelIndex(token, '=');
            if (equals <= 0) continue;
            String key = token.substring(0, equals).trim();
            result.set(key, decodeDuckDbScalarOrComposite(token.substring(equals + 1).trim()));
        }
        return result;
    }

    private static ArrayNode decodeDuckDbArray(String raw) {
        ArrayNode result = array();
        String body = raw.substring(1, raw.length() - 1).trim();
        if (!body.isEmpty()) for (String token : splitDuckDbTopLevel(body, ',')) {
            result.add(decodeDuckDbScalarOrComposite(token.trim()));
        }
        return result;
    }

    private static JsonNode decodeDuckDbScalarOrComposite(String raw) {
        if (raw.startsWith("{") && raw.endsWith("}")) return decodeDuckDbMap(raw);
        if (raw.startsWith("[") && raw.endsWith("]")) return decodeDuckDbArray(raw);
        if ("NULL".equalsIgnoreCase(raw)) return NullNode.instance;
        if ("TRUE".equalsIgnoreCase(raw)) return NODES.booleanNode(true);
        if ("FALSE".equalsIgnoreCase(raw)) return NODES.booleanNode(false);
        try {
            if (raw.matches("[-+]?\\d+")) return NODES.numberNode(Long.parseLong(raw));
            if (raw.matches("[-+]?(?:\\d+\\.\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")) {
                return NODES.numberNode(Double.parseDouble(raw));
            }
        } catch (NumberFormatException ignored) { }
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return NODES.textNode(raw.substring(1, raw.length() - 1));
        }
        return NODES.textNode(raw);
    }

    private static List<String> splitDuckDbTopLevel(String raw, char delimiter) {
        List<String> result = new ArrayList<>(); int depth = 0; boolean quoted = false; int start = 0;
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            if (ch == '"' && (index == 0 || raw.charAt(index - 1) != '\\')) quoted = !quoted;
            if (quoted) continue;
            if (ch == '{' || ch == '[') depth++; else if (ch == '}' || ch == ']') depth--;
            else if (ch == delimiter && depth == 0) { result.add(raw.substring(start, index)); start = index + 1; }
        }
        result.add(raw.substring(start)); return result;
    }

    private static int duckDbTopLevelIndex(String raw, char target) {
        int depth = 0; boolean quoted = false;
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            if (ch == '"' && (index == 0 || raw.charAt(index - 1) != '\\')) quoted = !quoted;
            if (quoted) continue;
            if (ch == '{' || ch == '[') depth++; else if (ch == '}' || ch == ']') depth--;
            else if (ch == target && depth == 0) return index;
        }
        return -1;
    }

    private static ArrayNode makeGenesisEpisodes(ObjectNode envelope, List<ObjectNode> features,
                                                  List<ObjectNode> labels, List<ObjectNode> executions) {
        Map<String, ObjectNode> featureById = uniqueRoleRows(features, "feature");
        Map<String, ObjectNode> labelById = uniqueRoleRows(labels, "label");
        Map<String, ObjectNode> executionById = uniqueRoleRows(executions, "execution");
        List<JsonNode> windows = rows(envelope.path("windows"));
        if (windows.isEmpty() || featureById.size() != windows.size() || labelById.size() != windows.size()
                || executionById.size() != windows.size()) {
            throw failure("research-init physical role inventory does not exactly equal the v2 opportunity envelope");
        }
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode window : windows) {
            String id = text(window, "episode_id"); ObjectNode feature = featureById.get(id);
            ObjectNode label = labelById.get(id), execution = executionById.get(id);
            if (id.isEmpty() || feature == null || label == null || execution == null) {
                throw failure("research-init episode " + (id.isEmpty() ? "?" : id)
                        + " lacks exact feature/label/execution custody");
            }
            long decision = timestampOrMin(firstTruthy(feature, "decision_time", "event_time"));
            long resolution = timestampOrMin(firstTruthy(label, "resolution_ceiling_time", "resolution_time",
                    "outcome_time", "exit_time"));
            JsonNode labelAvailableNode = jsonTruthy(field(label, "availability_time"))
                    ? field(label, "availability_time") : NODES.numberNode(resolution);
            JsonNode executionAvailableNode = jsonTruthy(field(execution, "availability_time"))
                    ? field(execution, "availability_time") : NODES.numberNode(resolution);
            long labelAvailable = timestampOrMin(labelAvailableNode), executionAvailable = timestampOrMin(executionAvailableNode);
            String venue = firstTextOr(feature, "BINANCE", "venue").toUpperCase(Locale.ROOT);
            String expectedIdentity = lower(text(window, "asset")) + "|" + venue + "|"
                    + text(window, "instrument").toUpperCase(Locale.ROOT) + "|"
                    + text(window, "symbol").toUpperCase(Locale.ROOT);
            if (!text(window, "signal_id").equals(text(feature, "signal_id"))
                    || !text(feature, "signal_id").equals(text(label, "signal_id"))
                    || !text(feature, "signal_id").equals(text(execution, "signal_id"))
                    || !roleIdentity(feature).equals(expectedIdentity) || !roleIdentity(label).equals(expectedIdentity)
                    || !roleIdentity(execution).equals(expectedIdentity)
                    || decision != timestampOrMin(field(window, "decision_time"))
                    || decision != timestampOrMin(field(label, "decision_time"))
                    || decision != timestampOrMin(field(execution, "decision_time"))) {
                throw failure("research-init episode " + id
                        + " lineage differs from the v2 envelope or physical roles");
            }
            if (decision == Long.MIN_VALUE || resolution == Long.MIN_VALUE || labelAvailable == Long.MIN_VALUE
                    || executionAvailable == Long.MIN_VALUE || resolution <= decision
                    || labelAvailable < resolution || executionAvailable < resolution) {
                throw failure("research-init episode " + id + " chronology is invalid");
            }
            ObjectNode row = object().put("episode_id", id).put("asset", lower(text(window, "asset")))
                    .put("decision_time", ISO_MILLIS.format(Instant.ofEpochMilli(decision)))
                    .put("resolution_time", ISO_MILLIS.format(Instant.ofEpochMilli(resolution)))
                    .put("label_availability_time", ISO_MILLIS.format(Instant.ofEpochMilli(labelAvailable)))
                    .put("execution_availability_time", ISO_MILLIS.format(Instant.ofEpochMilli(executionAvailable)))
                    .put("eligible", feature.path("signal_eligible").asBoolean(true));
            row.set("candidate_returns", object()); result.add(row);
        }
        result.sort(Comparator.comparingLong((ObjectNode row) -> timestampOrMin(field(row, "decision_time")))
                .thenComparing(row -> text(row, "episode_id")));
        Map<String, Long> lastResolution = new HashMap<>();
        for (ObjectNode row : result) {
            long decision = timestampOrMin(field(row, "decision_time")); Long prior = lastResolution.get(text(row, "asset"));
            if (row.path("eligible").asBoolean(false) && prior != null && decision < prior) row.put("eligible", false);
            if (row.path("eligible").asBoolean(false)) {
                lastResolution.put(text(row, "asset"), timestampOrMin(field(row, "resolution_time")));
            }
        }
        return array(result);
    }

    private static Map<String, ObjectNode> uniqueRoleRows(List<ObjectNode> rows, String role) {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        for (ObjectNode row : rows) {
            String id = text(row, "episode_id");
            if (id.isEmpty() || result.putIfAbsent(id, row) != null) {
                throw failure("research-init " + role + " role has a missing or duplicate episode identity");
            }
        }
        return result;
    }

    private static String roleIdentity(JsonNode value) {
        return lower(text(value, "asset")) + "|" + text(value, "venue").toUpperCase(Locale.ROOT) + "|"
                + text(value, "instrument").toUpperCase(Locale.ROOT) + "|"
                + text(value, "symbol").toUpperCase(Locale.ROOT);
    }

    private record ExposureCustody(ObjectNode head, Path headPath) {}

    private static ExposureCustody reopenOrAdvanceCanonicalExposureHead(String family, String datasetSha256) {
        requireSha(datasetSha256, "canonical exposure HEAD dataset SHA-256");
        Path familyRoot = canonicalFamilyCustodyRoot(family), headPath = canonicalExposureHeadPath(family);
        BehaviorRegistryPaths registryPaths = behaviorRegistryStatePaths(familyRoot);
        Path journalPath = Path.of(registryPaths.statePath() + ".journal.json");
        if (Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            ObjectNode recover = object().put("journalPath", journalPath.toString());
            StrategyStatisticalV5.recoverExposureRegistryTransaction(recover);
        }
        if (!Files.exists(headPath, LinkOption.NOFOLLOW_LINKS)) {
            ObjectNode make = object().put("hypothesisFamily", family).put("datasetSha256", datasetSha256);
            make.set("entries", array()); ObjectNode head = StrategyStatisticalV5.makeExposureHead(make);
            ObjectNode initialize = object().put("filePath", headPath.toString()); initialize.set("head", head);
            return new ExposureCustody(StrategyStatisticalV5.initializeExposureHeadFile(initialize), headPath);
        }
        ObjectNode prior = StrategyStatisticalV5.readExposureHeadFile(headPath);
        if (!family.equals(text(prior, "hypothesis_family"))) {
            throw failure("canonical exposure HEAD family differs from the frozen evaluator family");
        }
        if (datasetSha256.equals(text(prior, "dataset_sha256"))) return new ExposureCustody(prior, headPath);
        ObjectNode registry = null;
        if (Files.exists(registryPaths.statePath(), LinkOption.NOFOLLOW_LINKS)) {
            registry = StrategyStatisticalV5.readBehaviorDefinitionRegistryFile(registryPaths.statePath());
            ObjectNode validation = object(); validation.set("exposureHead", prior);
            StrategyStatisticalV5.validateBehaviorDefinitionRegistry(registry, validation);
        } else if (!prior.path("entries").isEmpty()) {
            throw failure("canonical exposure HEAD has historical K but no durable behavior-definition registry");
        }
        ObjectNode append = object(); append.set("prior", prior); append.put("datasetSha256", datasetSha256);
        append.set("behaviorAliases", array()); append.put("exposureAttemptCount", 0);
        append.put("source", "ROLLING_DATASET_TRANSITION");
        ObjectNode next = StrategyStatisticalV5.appendExposureHead(append);
        if (registry != null) {
            ObjectNode journal = object().put("journalPath", journalPath.toString())
                    .put("exposureHeadPath", headPath.toString())
                    .put("registryPath", registryPaths.statePath().toString())
                    .put("priorRegistrySha256", text(registry, "content_sha256"));
            journal.set("priorHead", prior); journal.set("nextHead", next); journal.set("definitions", array());
            StrategyStatisticalV5.writeExposureRegistryJournal(journal);
        }
        ObjectNode fileAppend = object().put("filePath", headPath.toString())
                .put("expectedHeadSha256", text(prior, "content_sha256"))
                .put("datasetSha256", datasetSha256).put("exposureAttemptCount", 0)
                .put("source", "ROLLING_DATASET_TRANSITION");
        fileAppend.set("behaviorAliases", array()); StrategyStatisticalV5.appendExposureHeadFile(fileAppend);
        if (registry != null) {
            ObjectNode recover = object().put("journalPath", journalPath.toString());
            StrategyStatisticalV5.recoverExposureRegistryTransaction(recover);
        }
        ObjectNode head = StrategyStatisticalV5.readExposureHeadFile(headPath);
        if (head.path("cumulative_k").asLong() != prior.path("cumulative_k").asLong()
                || head.path("exposure_attempt_k").asLong() != prior.path("exposure_attempt_k").asLong()) {
            throw failure("rolling dataset transition changed cumulative family exposure");
        }
        return new ExposureCustody(head, headPath);
    }

    private static void assertFrozenExperiment(ObjectNode value, String label) {
        if (value == null) throw failure(label + " is not an object");
        if (value.has("status") && !"FROZEN".equals(text(value, "status"))) {
            throw failure(label + " must have status FROZEN when status is declared");
        }
        if (value.has("immutable") && !value.path("immutable").asBoolean(false)) {
            throw failure(label + " is not marked immutable");
        }
    }

    private static ObjectNode exactEnvelopeByEpisode(ObjectNode envelope, ObjectNode artifact,
                                                      ObjectNode manifest, Path parquetRoot) {
        List<ObjectNode> features = readPhysicalParquetRoleRows(manifest, parquetRoot, "feature");
        List<ObjectNode> labels = readPhysicalParquetRoleRows(manifest, parquetRoot, "label");
        List<ObjectNode> executions = readPhysicalParquetRoleRows(manifest, parquetRoot, "execution");
        ObjectNode roleRows = object(); roleRows.set("feature", array(features));
        roleRows.set("label", array(labels)); roleRows.set("execution", array(executions));
        ObjectNode exact = object(); exact.set("envelope", envelope); exact.set("artifact", artifact);
        exact.set("roleRows", roleRows); validateExactProductionEpisodeInventoriesV5(exact);
        Map<String, ObjectNode> featureById = uniqueRoleRows(features, "feature"); ObjectNode result = object();
        for (JsonNode episode : artifact.path("episodes")) {
            String id = text(episode, "episode_id"); ObjectNode feature = featureById.get(id);
            List<JsonNode> matches = rows(envelope.path("windows")).stream().filter(window ->
                    lower(text(window, "asset")).equals(lower(text(feature, "asset")))
                    && text(window, "instrument").equalsIgnoreCase(text(feature, "instrument"))
                    && text(window, "symbol").equalsIgnoreCase(text(feature, "symbol"))
                    && (text(window, "episode_id").isEmpty() || text(window, "episode_id").equals(id))
                    && (text(window, "signal_id").isEmpty()
                    || text(window, "signal_id").equals(text(feature, "signal_id")))
                    && timestampOrMin(field(window, "decision_time"))
                    == timestampOrMin(field(feature, "decision_time"))).toList();
            if (matches.size() != 1) {
                throw failure("v2 opportunity envelope does not have exactly one window for episode " + id);
            }
            String expected = firstText(matches.get(0), "source_row_sha256", "source_feature_sha256");
            // DuckDB's JDBC driver materializes TIMESTAMP columns as SQL-style
            // text ("yyyy-MM-dd HH:mm:ss.SSS"), while the Node producer's
            // canonical JSON row uses ISO-8601 UTC text.  The physical bytes
            // are identical; normalize only the temporal feature fields before
            // comparing the producer's source-row commitment.
            if (!expected.isEmpty() && !expected.equals(hash(feature))
                    && !expected.equals(normalizedPhysicalFeatureHash(feature))) {
                throw failure("v2 opportunity envelope feature bytes differ for " + id);
            }
            result.set(id, matches.get(0).deepCopy());
        }
        return result;
    }

    private static String normalizedPhysicalFeatureHash(ObjectNode feature) {
        ObjectNode normalized = feature.deepCopy();
        for (String name : List.of("event_time", "decision_time", "availability_time")) {
            JsonNode value = normalized.get(name);
            if (value == null || !value.isTextual()) continue;
            long epoch = timestampOrMin(value);
            if (epoch != Long.MIN_VALUE) normalized.put(name, ISO_MILLIS.format(Instant.ofEpochMilli(epoch)));
        }
        return hash(normalized);
    }

    private static ObjectNode ensureBehaviorRegistryState(BehaviorRegistryPaths paths, ObjectNode head) {
        if (Files.exists(paths.statePath(), LinkOption.NOFOLLOW_LINKS)) {
            ObjectNode value = StrategyStatisticalV5.readBehaviorDefinitionRegistryFile(paths.statePath());
            ObjectNode validation = object(); validation.set("exposureHead", head);
            StrategyStatisticalV5.validateBehaviorDefinitionRegistry(value, validation); return value;
        }
        ObjectNode seed = null;
        if (paths.seedPath() != null && Files.exists(paths.seedPath(), LinkOption.NOFOLLOW_LINKS)) {
            seed = StrategyStatisticalV5.readBehaviorDefinitionRegistryFile(paths.seedPath());
            ObjectNode validation = object(); validation.set("exposureHead", head);
            StrategyStatisticalV5.validateBehaviorDefinitionRegistry(seed, validation);
        }
        if (seed == null) {
            if (!head.path("entries").isEmpty()) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: durable behavior-definition registry is missing: "
                        + paths.statePath());
            }
            ObjectNode make = object().put("hypothesisFamily", text(head, "hypothesis_family"));
            make.set("exposureHead", head); make.set("entries", array());
            seed = StrategyStatisticalV5.makeBehaviorDefinitionRegistry(make);
        }
        writeImmutable(paths.statePath(), seed); return seed;
    }

    private static Map<String, ObjectNode> durableBehaviorMap(ObjectNode registry, ObjectNode head,
                                                              String evaluatorSha, String precommitSha,
                                                              String lifecycleSha) {
        ObjectNode validation = object(); validation.set("exposureHead", head);
        StrategyStatisticalV5.validateBehaviorDefinitionRegistry(registry, validation);
        if (registry.path("entries").size() > head.path("entries").size()) {
            throw failure("behavior definition registry contains definitions beyond the physical exposure head");
        }
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        for (int index = 0; index < registry.path("entries").size(); index++) {
            ObjectNode row = (ObjectNode) registry.path("entries").path(index);
            JsonNode exposure = head.path("entries").path(index);
            if (!text(row, "behavior_sha256").equals(text(exposure, "behavior_sha256"))
                    || defined(exposure.get("definition_sha256"))
                    && !text(row, "definition_sha256").equals(text(exposure, "definition_sha256"))) {
                throw failure("behavior definition registry is not the exact exposure-head prefix at sequence "
                        + (index + 1));
            }
            if (!evaluatorSha.equals(text(row, "evaluator_sha256"))
                    || defined(row.get("precommit_sha256")) && !row.path("precommit_sha256").isNull()
                    && !precommitSha.equals(text(row, "precommit_sha256"))
                    || !lifecycleSha.equals(text(row, "lifecycle_sha256"))) {
                throw failure("behavior definition registry lineage differs for " + text(row, "behavior_sha256"));
            }
            result.put(text(row, "behavior_sha256"), row.deepCopy());
        }
        for (JsonNode exposure : head.path("entries")) {
            String alias = text(exposure, "behavior_sha256");
            if (!defined(exposure.get("definition_sha256")) || !result.containsKey(alias)
                    || !text(exposure, "definition_sha256").equals(text(result.get(alias), "definition_sha256"))) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: durable behavior definition is missing for cumulative alias "
                        + alias);
            }
        }
        return result;
    }

    private static ObjectNode deriveFrozenHardConstraints(ObjectNode precommit, ObjectNode experiment) {
        List<JsonNode> sources = new ArrayList<>();
        for (JsonNode source : List.of(experiment.path("acceptance_contract").path("gates"),
                experiment.path("acceptance_contract"), experiment.path("acceptance").path("gates"),
                experiment.path("acceptance").path("minimums"), experiment.path("acceptance").path("robust_stats"),
                experiment.path("acceptance").path("stress"), experiment.path("acceptance").path("portfolio"),
                experiment.path("acceptance"), precommit.path("experiment").path("acceptance_contract").path("gates"),
                precommit.path("experiment").path("acceptance_contract"), precommit.path("acceptance_contract").path("gates"),
                precommit.path("acceptance_contract"), precommit.path("acceptance").path("gates"),
                precommit.path("acceptance"))) if (source.isObject()) sources.add(source);
        double minEpisodes = frozenNumber(sources, List.of("minEpisodes", "minimum_episodes",
                "minimum_completed_episodes", "minimum_completed_trades", "completed_trades",
                "minimum_independent_episodes", "minimum_effective_independent_episode_count",
                "minimum_accepted_trades"), "minimum sample size", 1, Double.POSITIVE_INFINITY);
        double minExpectancy = frozenNumber(sources, List.of("minExpectancy", "minimum_expectancy_r",
                "minimum_search_adjusted_expectancy_r", "search_adjusted_expectancy_r",
                "after_cost_expectancy_r_must_exceed", "minimum_bootstrap_p20_expectancy_r"),
                "minimum expectancy", Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        double minProfitFactor = frozenNumber(sources, List.of("minProfitFactor", "minimum_profit_factor",
                "minimum_r_profit_factor", "profit_factor", "minimum_account_profit_factor",
                "profit_factor_must_exceed"), "minimum profit factor", 0, Double.POSITIVE_INFINITY);
        double maxDrawdown = frozenNumber(sources, List.of("maxDrawdownR", "maximum_drawdown_r",
                "maximum_drawdown"), "maximum drawdown", 0, Double.POSITIVE_INFINITY);
        double maxCost = frozenNumber(sources, List.of("maxCostR", "maximum_cost_r", "maximum_after_cost_r"),
                "maximum cost", 0, Double.POSITIVE_INFINITY);
        double minCoverage = frozenNumber(sources, List.of("minCoverage", "minimum_coverage_fraction"),
                "minimum coverage", 0, 1);
        JsonNode scales = firstDefinedFrom(sources, List.of("violationScales", "violation_scales",
                "normalization_scales"));
        ObjectNode violation = object().put("episodes", positiveScale(scales, "episodes", Math.max(1, minEpisodes)))
                .put("expectancy", positiveScale(scales, "expectancy", Math.max(.01, Math.abs(minExpectancy))))
                .put("drawdown", positiveScale(scales, "drawdown", Math.max(.01, Math.abs(maxDrawdown))))
                .put("costs", positiveScale(scales, "costs", Math.max(.01, Math.abs(maxCost))))
                .put("coverage", positiveScale(scales, "coverage", Math.max(.01, Math.abs(minCoverage))))
                .put("capacity", positiveScale(scales, "capacity", 1))
                .put("profit_factor", positiveScale(scales, "profit_factor", Math.max(.01, Math.abs(minProfitFactor))));
        ObjectNode value = object().put("minEpisodes", (long) minEpisodes).put("minExpectancy", minExpectancy)
                .put("minProfitFactor", minProfitFactor).put("maxDrawdownR", maxDrawdown)
                .put("maxCostR", maxCost).put("minCoverage", minCoverage).put("requireCapacityPass", true);
        value.set("violationScales", violation);
        JsonNode capacity = firstDefinedFrom(sources, List.of("capacity_pass", "require_capacity_pass",
                "minimum_capacity_pass"));
        if (defined(capacity) && !(capacity.isBoolean() && capacity.asBoolean()
                || capacity.isNumber() && capacity.asDouble() == 1)) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen acceptance capacity gate is not enabled");
        }
        return value;
    }

    private static final Set<String> AUTHORITATIVE_CRYPTO_ASSETS = Set.of(
            "btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
    private static final List<String> AUTHORITATIVE_STRESS_SCENARIOS = List.of(
            "DOUBLED_COST", "DELAYED_ENTRY", "ADVERSE_COLLISION", "GAP",
            "LIQUIDITY", "CAPACITY", "OUTAGE", "FUNDING", "EXPIRY", "LIQUIDATION",
            "LEAVE_ONE_ASSET", "LEAVE_ONE_REGIME", "LEAVE_ONE_CONTEXT");
    private static final Map<String, String> STRESS_NAME_ALIASES = Map.ofEntries(
            Map.entry("DOUBLED_FEES_SLIPPAGE", "DOUBLED_COST"),
            Map.entry("DOUBLED_COST", "DOUBLED_COST"),
            Map.entry("DELAYED_ENTRY", "DELAYED_ENTRY"),
            Map.entry("ADVERSE_COLLISION", "ADVERSE_COLLISION"),
            Map.entry("ADVERSE_GAP", "GAP"), Map.entry("GAP", "GAP"),
            Map.entry("LIQUIDITY_CAPACITY", "CAPACITY"), Map.entry("LIQUIDITY", "LIQUIDITY"),
            Map.entry("CAPACITY", "CAPACITY"), Map.entry("VENUE_OUTAGE", "OUTAGE"),
            Map.entry("OUTAGE", "OUTAGE"), Map.entry("DOUBLED_FUNDING", "FUNDING"),
            Map.entry("FUNDING", "FUNDING"), Map.entry("EXPIRY", "EXPIRY"),
            Map.entry("LIQUIDATION", "LIQUIDATION"),
            Map.entry("LEAVE_ONE_ASSET", "LEAVE_ONE_ASSET"),
            Map.entry("LEAVE_ONE_REGIME", "LEAVE_ONE_REGIME"),
            Map.entry("LEAVE_ONE_CONTEXT", "LEAVE_ONE_CONTEXT"));
    private static final Set<String> STRESS_PARAMETER_KEYS = Set.of(
            "multiplier", "fee_multiplier", "slippage_multiplier", "impact_multiplier",
            "entry_delay_bars", "delay_bars", "bootstrap_iterations", "block_length", "seed",
            "minimum_observations", "minimum_expectancy_r", "minimum_p20_r",
            "minimum_profit_factor", "maximum_drawdown_r", "maximum_cost_r",
            "minimum_coverage_fraction", "debit_r", "gap_model", "capacity_model",
            "maximum_participation_rate", "liquidity_model", "liquidity_impact_bps",
            "outage_rule", "blackout_windows", "funding_multiplier", "expiry_policy",
            "liquidation_rule", "adverse_move_bps", "stop_price", "target_price",
            "collision_policy", "field", "value", "exclude_asset", "asset", "exclude_value",
            "survival_condition", "not_applicable", "required_fields", "declared_field",
            "declared_value", "historical_gap_set", "gap_bars", "gap_fill_price",
            "combined_scenarios", "applies_to", "evidence_leg");

    private static ObjectNode deriveFrozenAssetScope(ObjectNode artifact, ObjectNode precommit,
                                                     ObjectNode experiment) {
        List<String> observed = rows(artifact.path("episodes")).stream()
                .map(row -> lower(text(row, "asset"))).distinct().sorted().toList();
        JsonNode explicit = firstTruthy(experiment, "asset_scope");
        if (!jsonTruthy(explicit)) explicit = firstTruthy(precommit, "asset_scope");
        if (!jsonTruthy(explicit)) explicit = firstTruthy(experiment, "trade_scope");
        if (!jsonTruthy(explicit)) explicit = firstTruthy(precommit, "trade_scope");
        if (!jsonTruthy(explicit)) explicit = firstTruthy(experiment.path("acceptance_contract"), "asset_scope");
        if (!jsonTruthy(explicit)) explicit = firstTruthy(precommit.path("acceptance_contract"), "asset_scope");

        List<JsonNode> sources = List.of(defined(explicit) ? explicit : NODES.missingNode(), experiment, precommit);
        List<JsonNode> tradeRows = firstArrayFrom(sources,
                List.of("trade_assets", "tradable_assets", "assets_to_trade", "proposed_trade_assets"));
        List<JsonNode> replicationRows = firstArrayFrom(sources,
                List.of("replication_assets", "replication_only_assets", "diagnostic_assets"));
        List<JsonNode> contextRows = firstArrayFrom(sources,
                List.of("context_assets", "context_only_assets", "side_data_assets"));
        if (tradeRows.isEmpty()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: precommit/experiment lacks non-empty trade_assets scope");
        }
        List<String> trade = normalizeCryptoAssets(tradeRows, "trade_assets");
        List<String> replication = normalizeCryptoAssets(replicationRows, "replication_assets");
        List<String> context = contextRows.stream().map(row -> lower(row.asText()).trim())
                .filter(value -> !value.isEmpty()).distinct().sorted().toList();
        List<Map.Entry<String, List<String>>> categories = List.of(
                Map.entry("trade_assets", trade), Map.entry("replication_assets", replication),
                Map.entry("context_assets", context));
        for (int left = 0; left < categories.size(); left++) {
            for (int right = left + 1; right < categories.size(); right++) {
                List<String> overlap = categories.get(left).getValue().stream()
                        .filter(categories.get(right).getValue()::contains).toList();
                if (!overlap.isEmpty()) {
                    throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: asset scope overlaps "
                            + categories.get(left).getKey() + " and " + categories.get(right).getKey()
                            + ": " + String.join(",", overlap));
                }
            }
        }
        Set<String> declared = new HashSet<>(); declared.addAll(trade); declared.addAll(replication);
        declared.addAll(context);
        List<String> missing = observed.stream().filter(value -> !declared.contains(value)).toList();
        if (!missing.isEmpty()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: asset scope omits canonical artifact asset(s): "
                    + String.join(",", missing));
        }
        List<String> unavailable = trade.stream().filter(value -> !observed.contains(value)).toList();
        if (!unavailable.isEmpty()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: trade_assets are absent from canonical artifact: "
                    + String.join(",", unavailable));
        }
        ObjectNode value = object().put("schema", "strategy-v5-statistical-asset-scope/1").put("version", 1);
        value.set("trade_assets", strings(trade)); value.set("replication_assets", strings(replication));
        value.set("context_assets", strings(context));
        if (text(precommit, "content_sha256").isEmpty()) value.putNull("source_sha256");
        else value.put("source_sha256", text(precommit, "content_sha256"));
        return withHash(value);
    }

    private static List<JsonNode> firstArrayFrom(List<JsonNode> sources, List<String> names) {
        for (JsonNode source : sources) for (String name : names) {
            JsonNode value = source.path(name); if (value.isArray()) return rows(value);
        }
        return List.of();
    }

    private static List<String> normalizeCryptoAssets(List<JsonNode> values, String label) {
        List<String> normalized = values.stream().map(value -> lower(value.asText()).trim())
                .distinct().sorted().toList();
        for (String value : normalized) if (!AUTHORITATIVE_CRYPTO_ASSETS.contains(value)) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: " + label
                    + " contains unsupported or non-crypto trade asset " + value);
        }
        return normalized;
    }

    private record StressSource(String source, List<JsonNode> rows) {}

    private static ObjectNode frozenStressContract(ObjectNode precommit, ObjectNode experiment,
                                                   ObjectNode evaluator) {
        List<StressSource> sources = stressSourceArrays(precommit, experiment, evaluator);
        if (sources.isEmpty()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen physical stress contract is missing");
        }
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        for (StressSource source : sources) for (JsonNode raw : source.rows()) {
            if (!(raw instanceof ObjectNode row)) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: " + source.source()
                        + " contains an invalid stress definition");
            }
            String rawName = firstTruthyText(row, "id", "name", "scenario").toUpperCase(Locale.ROOT);
            String id = STRESS_NAME_ALIASES.get(rawName);
            if (id == null) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: unknown frozen stress scenario "
                        + (rawName.isEmpty() ? "?" : rawName));
            }
            if (!row.path("required").isBoolean() || !row.path("required").asBoolean()) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen stress scenario " + id
                        + " is not explicitly required");
            }
            JsonNode rawParameters = row.get("parameters");
            JsonNode parameters = jsonTruthy(rawParameters) && (rawParameters.isObject() || rawParameters.isArray())
                    ? rawParameters.deepCopy() : object();
            List<String> unknown = new ArrayList<>();
            parameters.fieldNames().forEachRemaining(key -> { if (!STRESS_PARAMETER_KEYS.contains(key)) unknown.add(key); });
            if (!unknown.isEmpty()) {
                throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen stress scenario " + id
                        + " has unknown parameters: " + String.join(", ", unknown));
            }
            ObjectNode normalized = object().put("id", id).put("required", true);
            normalized.set("parameters", parameters); normalized.put("source", source.source());
            ObjectNode prior = byId.get(id);
            if (prior != null) {
                ObjectNode priorComparable = prior.deepCopy(); priorComparable.putNull("source");
                ObjectNode currentComparable = normalized.deepCopy(); currentComparable.putNull("source");
                if (!stable(priorComparable).equals(stable(currentComparable))) {
                    throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen stress scenario " + id
                            + " has conflicting physical definitions");
                }
            }
            byId.put(id, normalized);
            if ("CAPACITY".equals(id) && "LIQUIDITY_CAPACITY".equals(rawName)
                    && combinedStressScenarios(parameters)) {
                ObjectNode liquidity = normalized.deepCopy(); liquidity.put("id", "LIQUIDITY");
                byId.put("LIQUIDITY", liquidity);
            }
        }
        List<String> missing = AUTHORITATIVE_STRESS_SCENARIOS.stream()
                .filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen stress contract lacks scenarios: "
                    + String.join(", ", missing));
        }
        ArrayNode scenarios = array();
        for (String id : AUTHORITATIVE_STRESS_SCENARIOS) {
            ObjectNode scenario = byId.get(id).deepCopy(); scenario.put("id", id); scenarios.add(scenario);
        }
        JsonNode resampling = experiment.path("chronology");
        if (!jsonTruthy(resampling)) resampling = precommit.path("chronology");
        if (!jsonTruthy(resampling)) resampling = evaluator.path("execution_contract").path("resampling_contract");
        double iterations = jsNumber(firstNullish(resampling, "bootstrap_iterations", "iterations"));
        JsonNode seeds = resampling.path("seeds");
        JsonNode seedNode = seeds.isArray() ? seeds.path(0) : firstNullish(resampling, "seed");
        double seed = defined(seedNode) ? jsNumber(seedNode) : 11;
        JsonNode blockNode = firstNullish(resampling, "block_length", "blockLength");
        double blockLength = defined(blockNode) ? jsNumber(blockNode) : 0;
        if (!isInteger(iterations) || iterations < 1 || !isInteger(seed) || seed < 0
                || blockLength != 0 && (!isInteger(blockLength) || blockLength < 1)) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen statistical resampling contract is missing or invalid");
        }
        ObjectNode value = object().put("schema", "strategy-v5-authoritative-stress-contract/1")
                .put("version", 1);
        putNullishText(value, "evaluator_sha256", text(evaluator, "content_sha256"));
        putNullishText(value, "precommit_sha256", text(precommit, "content_sha256"));
        putNullishText(value, "experiment_sha256", text(experiment, "content_sha256"));
        ObjectNode resamplingValue = object().put("iterations", (long) iterations).put("seed", (long) seed);
        if (blockLength == 0) resamplingValue.putNull("block_length");
        else resamplingValue.put("block_length", (long) blockLength);
        value.set("resampling", resamplingValue); value.set("scenarios", scenarios);
        ObjectNode result = withHash(value);
        if (SCHEMAS.hasContractSchema(text(result, "schema"))) SCHEMAS.validateKnownContractSchema(result);
        return result;
    }

    private static List<StressSource> stressSourceArrays(ObjectNode precommit, ObjectNode experiment,
                                                         ObjectNode evaluator) {
        List<Map.Entry<String, JsonNode>> candidates = List.of(
                Map.entry("experiment.acceptance_contract.stress_scenarios", experiment.path("acceptance_contract").path("stress_scenarios")),
                Map.entry("experiment.acceptance.stress_scenarios", experiment.path("acceptance").path("stress_scenarios")),
                Map.entry("precommit.acceptance_contract.stress_scenarios", precommit.path("acceptance_contract").path("stress_scenarios")),
                Map.entry("precommit.acceptance.stress_scenarios", precommit.path("acceptance").path("stress_scenarios")),
                Map.entry("evaluator.execution_contract.stress_scenarios", evaluator.path("execution_contract").path("stress_scenarios")),
                Map.entry("evaluator.execution_contract.stress_contract", evaluator.path("execution_contract").path("stress_contract")),
                Map.entry("evaluator.execution_contract.stress", evaluator.path("execution_contract").path("stress")));
        List<StressSource> result = new ArrayList<>();
        for (Map.Entry<String, JsonNode> candidate : candidates) {
            JsonNode value = candidate.getValue();
            if (value.isArray()) { result.add(new StressSource(candidate.getKey(), rows(value))); continue; }
            if (!value.isObject()) continue;
            JsonNode nested = value.path("scenarios").isArray() ? value.path("scenarios")
                    : value.path("stress_scenarios").isArray() ? value.path("stress_scenarios") : null;
            if (nested != null) { result.add(new StressSource(candidate.getKey(), rows(nested))); continue; }
            List<JsonNode> mapped = new ArrayList<>(); boolean allObjects = value.size() > 0;
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!(field.getValue() instanceof ObjectNode child)) { allObjects = false; break; }
                ObjectNode row = object().put("name", field.getKey()); row.setAll(child); mapped.add(row);
            }
            if (allObjects) result.add(new StressSource(candidate.getKey(), mapped));
        }
        return result;
    }

    private static boolean combinedStressScenarios(JsonNode parameters) {
        if (parameters.path("combined_scenarios").isBoolean()
                && parameters.path("combined_scenarios").asBoolean()) return true;
        JsonNode applies = parameters.path("applies_to");
        if (!applies.isArray()) return false;
        List<String> values = rows(applies).stream().map(JsonNode::asText).toList();
        return values.contains("LIQUIDITY") && values.contains("CAPACITY");
    }

    private static boolean isInteger(double value) {
        return Double.isFinite(value) && Math.rint(value) == value;
    }

    private static void putNullishText(ObjectNode target, String name, String value) {
        if (value == null || value.isEmpty()) target.putNull(name); else target.put(name, value);
    }

    private static JsonNode firstDefinedFrom(List<JsonNode> sources, List<String> names) {
        for (JsonNode source : sources) for (String name : names) if (defined(source.get(name))) return source.get(name);
        return NODES.missingNode();
    }

    private static double frozenNumber(List<JsonNode> sources, List<String> names, String label,
                                       double min, double max) {
        JsonNode raw = firstDefinedFrom(sources, names); double value = jsNumber(raw);
        if (!Double.isFinite(value) || value < min || value > max) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen acceptance is missing valid " + label);
        }
        return value;
    }

    private static double positiveScale(JsonNode scales, String key, double fallback) {
        double value = scales.isObject() ? jsNumber(scales.get(key)) : Double.NaN;
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }

    private record TrainingBoundary(String at, String phase, String reservedTestStart) {}

    private static TrainingBoundary deriveFrozenTrainingBoundary(ObjectNode experiment, ObjectNode plan) {
        JsonNode chronology = experiment.path("chronology").isObject() ? experiment.path("chronology")
                : experiment.path("evaluation_chronology");
        List<JsonNode> windows = List.of(experiment.path("training_window"), experiment.path("development_window"),
                experiment.path("selection_window"), experiment.path("training"), chronology.path("training_window"),
                chronology.path("development_window"), chronology.path("selection_window"), chronology.path("training"));
        long boundary = Long.MIN_VALUE;
        for (JsonNode window : windows) {
            if (!window.isObject()) continue;
            for (String name : List.of("end_at", "end", "end_time", "train_end", "training_end",
                    "development_end", "selection_end_at", "selection_end", "completed_through_at")) {
                if (!defined(window.get(name))) continue; boundary = timestampOrMin(window.get(name));
                if (boundary == Long.MIN_VALUE) throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen experiment training boundary " + name + " is invalid");
                break;
            }
            if (boundary != Long.MIN_VALUE) break;
        }
        if (boundary == Long.MIN_VALUE) {
            for (String name : List.of("training_end", "development_end", "selection_end_at", "selection_end", "train_end")) {
                if (!defined(experiment.get(name))) continue; boundary = timestampOrMin(experiment.get(name)); break;
            }
        }
        if (boundary == Long.MIN_VALUE) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen experiment lacks an exact development/training boundary");
        }
        List<Long> reserved = new ArrayList<>();
        for (JsonNode window : List.of(experiment.path("validation_window"), experiment.path("test_window"),
                experiment.path("holdout_window"), experiment.path("oos_window"), chronology.path("validation_window"),
                chronology.path("test_window"), chronology.path("holdout_window"), chronology.path("oos_window"),
                chronology.path("evaluation_window"), chronology.path("monitoring_window"))) {
            if (!window.isObject()) continue;
            for (String name : List.of("start_at", "start", "start_time", "test_start", "oos_start", "evaluation_start")) {
                if (!defined(window.get(name))) continue; long value = timestampOrMin(window.get(name));
                if (value == Long.MIN_VALUE) throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: reserved experiment boundary " + name + " is invalid");
                reserved.add(value); break;
            }
        }
        long earliest = reserved.stream().mapToLong(Long::longValue).min().orElse(Long.MIN_VALUE);
        if (earliest != Long.MIN_VALUE && boundary >= earliest) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: training boundary is not strictly before the reserved validation/OOS window");
        }
        long planEnd = timestampOrMin(firstTruthy(plan.path("window"), "end_at", "completed_through_at"));
        if (planEnd != Long.MIN_VALUE && boundary > planEnd) {
            throw failure("AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: experiment training boundary lies beyond the physical plan");
        }
        return new TrainingBoundary(ISO_MILLIS.format(Instant.ofEpochMilli(boundary)),
                "DEVELOPMENT_TRAINING", earliest == Long.MIN_VALUE ? null
                : ISO_MILLIS.format(Instant.ofEpochMilli(earliest)));
    }

    private static List<String> eligibleEpisodesAt(JsonNode episodes, String cutoff) {
        long boundary = timestampOrMin(NODES.textNode(cutoff)); List<String> result = new ArrayList<>();
        for (JsonNode row : episodes) {
            if (!row.path("eligible").asBoolean(false)) continue;
            long decision = timestampOrMin(field(row, "decision_time"));
            long resolution = timestampOrMin(field(row, "resolution_time"));
            long label = timestampOrMin(jsonTruthy(field(row, "label_availability_time"))
                    ? field(row, "label_availability_time") : field(row, "resolution_time"));
            long execution = timestampOrMin(jsonTruthy(field(row, "execution_availability_time"))
                    ? field(row, "execution_availability_time") : field(row, "resolution_time"));
            if (decision < boundary && resolution <= boundary && label <= boundary && execution <= boundary) {
                result.add(text(row, "episode_id"));
            }
        }
        return result;
    }

    private static void assertExactTrainingInventory(JsonNode run, List<String> expected, ObjectNode artifact,
                                                     String cutoff) {
        List<String> actual = rows(run.path("training_episode_ids")).stream().map(JsonNode::asText).toList();
        if (actual.size() != new HashSet<>(actual).size()
                || !new java.util.TreeSet<>(actual).equals(new java.util.TreeSet<>(expected))) {
            throw failure("authoritative genetic artifact training episode inventory is omitted, duplicated, or differs from the frozen cutoff scope");
        }
        Set<String> allowed = new HashSet<>(eligibleEpisodesAt(artifact.path("episodes"), cutoff));
        if (!allowed.containsAll(actual)) {
            throw failure("authoritative genetic artifact training episode inventory violates cutoff");
        }
    }
    private static Prerequisite prerequisite(String key, String label) {
        return new Prerequisite(key, label, false, false);
    }
    private static Prerequisite prerequisiteDirectory(String key, String label) {
        return new Prerequisite(key, label, true, false);
    }
    private static Prerequisite prerequisiteTarget(String key, String label, boolean directory) {
        return new Prerequisite(key, label, directory, true);
    }

    private static String firstPresentKey(ObjectNode options, String... keys) {
        for (String key : keys) if (defined(options.get(key))) return key; return keys[0];
    }

    private static Path requestedOr(ObjectNode options, Path fallback, String... names) {
        String value = firstText(options, names); return value.isEmpty() ? fallback : absolute(Path.of(value));
    }

    private static ObjectNode object() { return NODES.objectNode(); }
    private static ArrayNode array() { return NODES.arrayNode(); }
    private static ArrayNode array(Collection<? extends JsonNode> values) { ArrayNode result = array(); if (values != null) values.forEach(result::add); return result; }
    private static ArrayNode strings(Collection<String> values) { ArrayNode result = array(); if (values != null) values.forEach(result::add); return result; }
    private static ArrayNode cloneArray(JsonNode value) { return value != null && value.isArray() ? ((ArrayNode) value).deepCopy() : array(); }

    private static ArrayNode commandArray(ObjectNode options, String name) {
        JsonNode value = field(options, name);
        if (!defined(value)) return array();
        if (!value.isArray()) throw failure("authoritative command " + name + " must be an array");
        return ((ArrayNode) value).deepCopy();
    }

    private static String jsString(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "null";
        if (value.isTextual() || value.isNumber() || value.isBoolean()) return value.asText();
        if (value.isArray()) {
            List<String> parts = new ArrayList<>(); for (JsonNode child : value) parts.add(jsString(child));
            return String.join(",", parts);
        }
        return "[object Object]";
    }

    private static ObjectNode requireObject(ObjectNode value, String label) {
        if (value == null) throw failure(label + " is required"); return value;
    }

    private static ObjectNode requiredObject(ObjectNode options, String... names) {
        JsonNode value = first(options, names); if (!(value instanceof ObjectNode object)) throw failure(names[0] + " is required"); return object;
    }

    private static ObjectNode optionalObject(JsonNode value) { return value instanceof ObjectNode object ? object : null; }
    private static JsonNode field(JsonNode value, String name) { return value == null ? NODES.missingNode() : value.path(name); }

    private static JsonNode first(JsonNode value, String... names) {
        if (value == null || !value.isObject()) return NODES.missingNode();
        for (String name : names) if (value.has(name)) return value.get(name); return NODES.missingNode();
    }

    private static JsonNode firstNullish(JsonNode value, String... names) {
        if (value == null || !value.isObject()) return NODES.missingNode();
        for (String name : names) {
            JsonNode candidate = value.get(name);
            if (candidate != null && !candidate.isNull() && !candidate.isMissingNode()) return candidate;
        }
        return NODES.missingNode();
    }

    private static JsonNode firstTruthy(JsonNode value, String... names) {
        if (value == null || !value.isObject()) return NODES.missingNode();
        for (String name : names) {
            JsonNode candidate = value.get(name); if (jsonTruthy(candidate)) return candidate;
        }
        return NODES.missingNode();
    }

    private static String text(JsonNode value, String name) { return value == null ? "" : textValue(value.get(name)); }
    private static String textValue(JsonNode value) { return value == null || value.isNull() || value.isMissingNode() ? "" : value.asText(); }
    private static String firstText(JsonNode value, String... names) { return textValue(first(value, names)); }
    private static String firstTruthyText(JsonNode value, String... names) {
        return textValue(firstTruthy(value, names));
    }
    private static String firstTextOr(ObjectNode value, String fallback, String... names) { String result = firstText(value, names); return result.isEmpty() ? fallback : result; }

    private static String firstTextOrNull(JsonNode value, String... names) {
        String direct = firstText(value, names); if (!direct.isEmpty()) return direct;
        JsonNode lineage = value == null ? NODES.missingNode() : value.path("lineage");
        direct = firstText(lineage, names); return nullIfEmpty(direct);
    }

    private static boolean defined(JsonNode value) { return value != null && !value.isNull() && !value.isMissingNode(); }
    private static boolean jsonTruthy(JsonNode value) {
        if (!defined(value)) return false;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) { double number = value.asDouble(); return number != 0 && !Double.isNaN(number); }
        if (value.isTextual()) return !value.asText().isEmpty();
        return true;
    }
    private static boolean bool(JsonNode value) { return value != null && (value.isBoolean() ? value.asBoolean() : "true".equals(value.asText())); }
    private static double number(JsonNode value) { if (value == null || value.isNull() || value.isMissingNode()) return Double.NaN; return value.asDouble(Double.NaN); }
    private static double jsNumber(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return 0;
        if (value.isBoolean()) return value.asBoolean() ? 1 : 0;
        if (value.isNumber()) return value.asDouble();
        if (value.isTextual()) {
            String raw = value.asText().trim(); if (raw.isEmpty()) return 0;
            try { return Double.parseDouble(raw); } catch (NumberFormatException ignored) { return Double.NaN; }
        }
        return Double.NaN;
    }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private static String nullIfEmpty(String value) { return value == null || value.isEmpty() ? null : value; }

    private static List<JsonNode> rows(JsonNode value) {
        List<JsonNode> result = new ArrayList<>(); if (value != null && value.isArray()) value.forEach(result::add); return result;
    }

    private static List<ObjectNode> objects(JsonNode value) {
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode row : rows(value)) {
            if (!(row instanceof ObjectNode object)) throw failure("expected an object inventory row");
            result.add(object.deepCopy());
        }
        return result;
    }

    private static List<String> mapText(JsonNode values, String field) { return map(values, row -> text(row, field)); }
    private static <T> List<T> map(JsonNode values, java.util.function.Function<JsonNode, T> mapper) { return rows(values).stream().map(mapper).toList(); }

    private static List<String> sortedTexts(JsonNode values, boolean lower) {
        List<String> result = rows(values).stream().map(JsonNode::asText)
                .map(value -> lower ? lower(value) : value).filter(value -> !value.isEmpty()).sorted().toList(); return result;
    }

    private static List<String> uniqueSortedMapped(JsonNode values, java.util.function.Function<JsonNode, String> mapper) {
        return rows(values).stream().map(mapper).filter(value -> value != null && !value.isEmpty()).distinct().sorted().toList();
    }

    private static long timestamp(JsonNode value, String label) {
        long result = timestampOrMin(value); if (result == Long.MIN_VALUE) throw failure(label + " is invalid"); return result;
    }

    private static long timestampOrMin(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return Long.MIN_VALUE;
        if (value.isNumber()) return value.asLong();
        String text = value.asText();
        try { return Instant.parse(text).toEpochMilli(); }
        catch (DateTimeParseException ignored) {
            try { return OffsetDateTime.parse(text).toInstant().toEpochMilli(); }
            catch (DateTimeParseException error) {
                try { return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli(); }
                catch (DateTimeParseException localError) { return Long.MIN_VALUE; }
            }
        }
    }

    /** Conservative Date.parse-compatible subset used by the dated-settlement public contract. */
    private static long nodeDateParseOrMin(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return Long.MIN_VALUE;
        String raw = value.isTextual() ? value.asText() : value.toString();
        if (raw.isEmpty()) return Long.MIN_VALUE;
        try { return Instant.parse(raw).toEpochMilli(); }
        catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(raw).toInstant().toEpochMilli(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); }
        catch (DateTimeParseException ignored) { return Long.MIN_VALUE; }
    }

    private static double firstFinite(JsonNode value, String... names) {
        for (String name : names) { double number = number(value.get(name)); if (Double.isFinite(number)) return number; } return Double.NaN;
    }

    private static void putTimeOrNull(ObjectNode target, String name, JsonNode value) {
        long time = timestampOrMin(value); if (time == Long.MIN_VALUE) target.putNull(name); else target.put(name, Instant.ofEpochMilli(time).toString());
    }

    private static long firstIntegral(JsonNode value, String... names) {
        for (String name : names) if (value.path(name).isIntegralNumber()) return value.path(name).asLong(); return -1;
    }

    private static Object integralOrNull(JsonNode value, String name, int fallback) {
        if (value.path(name).isIntegralNumber()) return value.path(name).asLong(); return fallback == 0 ? null : fallback;
    }

    private static void putNullable(ObjectNode target, String name, Object value) {
        if (value == null) target.putNull(name); else if (value instanceof Long number) target.put(name, number);
        else if (value instanceof Integer number) target.put(name, number); else target.put(name, String.valueOf(value));
    }

    private static boolean containsActiveString(JsonNode value) {
        try { return JSON.writeValueAsString(value).contains("\"ACTIVE\""); }
        catch (JsonProcessingException error) { throw failure(error.getMessage()); }
    }

    private static String indexEvidencePhase(String value) {
        String raw = value == null ? "" : value.trim().toUpperCase(Locale.ROOT); if (raw.isEmpty()) return null;
        if (INDEX_PHASES.contains(raw)) return raw; if (raw.contains("OUTER") || raw.contains("OOS")) return "OOS";
        if (raw.contains("INNER") || raw.contains("TRAIN")) return "INNER"; if (raw.contains("EXPOSE")) return "EXPOSED";
        if (raw.contains("SEALED")) return "SEALED"; if (raw.contains("PROSPECT")) return "PROSPECTIVE"; return null;
    }

    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }
}
