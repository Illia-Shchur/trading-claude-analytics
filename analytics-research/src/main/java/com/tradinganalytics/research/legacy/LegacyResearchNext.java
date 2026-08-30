package com.tradinganalytics.research.legacy;

import static com.tradinganalytics.research.legacy.LegacyResearchSupport.JSON;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.arrayOf;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.bool;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.cloneNode;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.hash;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.jsNumber;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.jsTime;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.objectCopy;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.rows;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.stable;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.text;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/**
 * Exact, network-free Java compatibility API for {@code tools/strategy-research-next.mjs}.
 *
 * <p>The API deliberately accepts and returns JSON trees. The source contract is extensible and
 * its immutable hashes cover fields unknown to this version; mapping it into closed Java records
 * would silently discard authoritative data.</p>
 */
public final class LegacyResearchNext {
    public static final String STACK_SCHEMA = "strategy-research-stack/1";
    public static final String SOURCE_RECEIPT_SCHEMA = "strategy-source-receipt/1";
    public static final String EXPOSURE_SCHEMA = "strategy-exposure-ledger/1";
    public static final String EXECUTION_SCHEMA = "strategy-execution-policy/1";
    public static final String PORTFOLIO_SCHEMA = "strategy-portfolio-policy/1";
    public static final String PROSPECTIVE_SCHEMA = "strategy-prospective-ledger/1";
    public static final String ACTIVATION_SCHEMA = "strategy-activation/1";
    public static final String REVOCATION_SCHEMA = "strategy-activation-revocation/1";
    public static final String READINESS_SCHEMA = "strategy-readiness-audit/1";
    public static final String RUN_SCHEMA = "strategy-research-run/4";
    public static final String EVIDENCE_SCHEMA = "strategy-research-evidence/4";
    public static final List<String> UNIVERSE = List.of("btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
    public static final List<String> PIT_TIERS = List.of(
            "IMMUTABLE_EVENT_ARCHIVE", "VINTAGE_REVISION_AWARE", "CAPTURE_FORWARD", "UNVERIFIED_DEVELOPMENT_ONLY");
    public static final List<String> DECISIONS = List.of("REJECTED", "SHADOW", "CANDIDATE_REVIEW");
    public static final List<String> EXECUTABLE_INSTRUMENTS = List.of(
            "SPOT", "USD_M_LINEAR_PERPETUAL", "USD_M_LINEAR_FUTURE");

    private static final Pattern HASH_RE = Pattern.compile("^[a-f0-9]{64}$");
    private static final Set<String> NO_LABEL_FIELDS = Set.of(
            "target", "label", "outcome", "forward_return", "future_return", "forward_pnl", "future_pnl",
            "resolved_at", "resolution_bars");
    static final String DETERMINISTIC_READINESS_TIME = "2026-08-24T00:00:00.000Z";
    private static final DateTimeFormatter ISO_MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final ResearchSchemaRegistry SCHEMAS = ResearchSchemaRegistry.defaultRegistry();
    private static final ObjectNode SOURCE_REGISTRY_DOCUMENT = sourceRegistryDocument();
    private static final ObjectNode SOURCE_REGISTRY_AUTHORITY = sourceRegistryJson();
    public static final Map<String, Map<String, Object>> SOURCE_REGISTRY = sourceRegistryExport();

    private LegacyResearchNext() {}

    /** The canonical JSON serialization used by the Node module. */
    public static String stable(JsonNode value) { return LegacyResearchSupport.stable(value); }

    /** SHA-256 of a raw string. */
    public static String hash(String value) { return LegacyResearchSupport.hash(value); }

    /** SHA-256 of canonical JSON. */
    public static String hash(JsonNode value) { return LegacyResearchSupport.hash(value); }

    public static String ownHash(JsonNode value) { return ownHash(value, "content_sha256"); }

    public static String ownHash(JsonNode value, String field) {
        ObjectNode copy = objectCopy(value, "value");
        copy.remove(field);
        return hash(copy);
    }

    public static ObjectNode withHash(JsonNode value) { return withHash(value, "content_sha256"); }

    public static ObjectNode withHash(JsonNode value, String field) {
        ObjectNode copy = objectCopy(value, "value");
        copy.put(field, ownHash(copy, field));
        return copy;
    }

    public static ObjectNode assignPitTier(JsonNode options) {
        String sourceId = requiredText(options == null ? null : options.get("source"), "source");
        JsonNode registered = SOURCE_REGISTRY_AUTHORITY.get(sourceId);
        String assigned = registered == null
                ? "UNVERIFIED_DEVELOPMENT_ONLY"
                : text(registered.get("maximum_tier"));
        if (!PIT_TIERS.contains(assigned)) throw new IllegalArgumentException("source registry has invalid tier for " + sourceId);
        ObjectNode result = JSON.objectNode();
        result.put("source_id", sourceId);
        setNullable(result, "requested_pit_tier", options == null ? null : options.get("requestedPitTier"));
        result.put("assigned_pit_tier", assigned);
        result.put("public_source", registered != null && registered.path("public").asBoolean(false));
        result.put("authority", registered == null ? "CUSTOM_IMPORT_DEVELOPMENT_ONLY" : "REVIEWED_SOURCE_REGISTRY");
        return result;
    }

    public static ObjectNode makeSourceReceipt(JsonNode options) {
        JsonNode safe = options == null ? JSON.objectNode() : options;
        ObjectNode assignmentOptions = JSON.objectNode();
        setNullable(assignmentOptions, "source", safe.get("source"));
        setNullable(assignmentOptions, "requestedPitTier", first(safe, "requestedPitTier", "requested_pit_tier"));
        ObjectNode assignment = assignPitTier(assignmentOptions);
        String sourceId = text(assignment.get("source_id"));
        JsonNode registered = SOURCE_REGISTRY_AUTHORITY.get(sourceId);
        ObjectNode receipt = JSON.objectNode();
        receipt.put("schema", SOURCE_RECEIPT_SCHEMA);
        receipt.put("source_id", sourceId);
        receipt.put("source", sourceId);
        JsonNode sourceUrl = first(safe, "sourceUrl", "source_url");
        if (!present(sourceUrl) && registered != null) sourceUrl = registered.get("source_url");
        setNullable(receipt, "source_url", sourceUrl);
        setNullable(receipt, "requested_pit_tier", first(safe, "requestedPitTier", "requested_pit_tier"));
        receipt.put("assigned_pit_tier", text(assignment.get("assigned_pit_tier")));
        receipt.put("event_time_policy", optionText(safe, "eventTimePolicy", "event_time_policy", "completed_bar_event_time"));
        receipt.put("availability_time_policy", optionText(safe, "availabilityTimePolicy", "availability_time_policy", "availability_time_required"));
        setNullable(receipt, "capture_time", first(safe, "captureTime", "capture_time"));
        setNullable(receipt, "archive_checksum", first(safe, "archiveChecksum", "archive_checksum"));
        setNullable(receipt, "adapter_sha256", first(safe, "adapterSha256", "adapter_sha256"));
        receipt.put("revision_policy", optionText(safe, "revisionPolicy", "revision_policy", "do_not_rewrite_prior_vintages"));
        receipt.put("authority", text(assignment.get("authority")));
        ObjectNode result = withHash(receipt);
        validateSchema(result);
        return result;
    }

    public static boolean validateSourceReceipt(JsonNode receipt) { return validateSourceReceipt(receipt, JSON.objectNode()); }

    public static boolean validateSourceReceipt(JsonNode receipt, JsonNode options) {
        if (receipt == null || !SOURCE_RECEIPT_SCHEMA.equals(text(receipt.get("schema")))
                || !ownHash(receipt).equals(text(receipt.get("content_sha256")))) {
            throw new IllegalArgumentException("source receipt hash/schema is invalid");
        }
        validateSchema(receipt);
        ObjectNode assignmentOptions = JSON.objectNode();
        assignmentOptions.set("source", cloneNode(receipt.get("source_id")));
        setNullable(assignmentOptions, "requestedPitTier", receipt.get("requested_pit_tier"));
        ObjectNode expected = assignPitTier(assignmentOptions);
        if (!text(receipt.get("assigned_pit_tier")).equals(text(expected.get("assigned_pit_tier")))) {
            throw new IllegalArgumentException("caller-supplied PIT tier cannot override reviewed source registry");
        }
        if (!text(receipt.get("authority")).equals(text(expected.get("authority")))) {
            throw new IllegalArgumentException("source receipt authority is invalid");
        }
        JsonNode registered = SOURCE_REGISTRY_AUTHORITY.get(text(receipt.get("source_id")));
        if (registered != null && present(registered.get("source_url"))
                && !text(registered.get("source_url")).equals(text(receipt.get("source_url")))) {
            throw new IllegalArgumentException("source receipt URL is not bound to source registry");
        }
        String phase = optionText(options, "phase", "phase", "DEVELOPMENT");
        if (!"DEVELOPMENT".equals(phase)) {
            if (!"REVIEWED_SOURCE_REGISTRY".equals(text(expected.get("authority")))) {
                throw new IllegalArgumentException("custom/unknown source receipts are development-only");
            }
            if (expected.path("public_source").asBoolean(false) && !present(receipt.get("source_url"))) {
                throw new IllegalArgumentException("load-bearing source receipt is missing source URL");
            }
            requireSha(receipt.get("archive_checksum"), "load-bearing source receipt is missing archive checksum", true);
            requireSha(receipt.get("adapter_sha256"), "load-bearing source receipt is missing adapter code hash", true);
            if (!validTime(receipt.get("capture_time"))) throw new IllegalArgumentException("load-bearing source receipt is missing capture time");
            if (!present(receipt.get("availability_time_policy")) || !present(receipt.get("event_time_policy"))) {
                throw new IllegalArgumentException("load-bearing source receipt is missing time policies");
            }
        }
        return true;
    }

    public static ArrayNode coverageMatrix(JsonNode rowsNode) { return coverageMatrix(rowsNode, JSON.objectNode()); }

    public static ArrayNode coverageMatrix(JsonNode rowsNode, JsonNode options) {
        List<String> timeframes = stringList(options == null ? null : options.get("timeframes"), List.of("1m", "1h", "4h", "1d"));
        List<String> assets = stringList(options == null ? null : options.get("assets"), UNIVERSE);
        double minimumFraction = optionDouble(options, "minimumFraction", "minimum_fraction", 0.95);
        JsonNode expectedBySeries = first(options, "expectedBySeries", "expected_by_series");
        Map<String, Long> durations = Map.of("1m", 60_000L, "1h", 3_600_000L, "4h", 14_400_000L, "1d", 86_400_000L);
        List<JsonNode> inputRows = rows(rowsNode);
        ArrayNode result = JSON.arrayNode();
        for (String rawAsset : assets) for (String timeframe : timeframes) {
            String a = rawAsset.toLowerCase(Locale.ROOT);
            List<JsonNode> series = inputRows.stream()
                    .filter(row -> a.equals(text(row.get("asset")).toLowerCase(Locale.ROOT)))
                    .filter(row -> timeframe.equals(text(row.get("timeframe")).toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparingLong(row -> timestamp(first(row, "event_time", "time"), "timestamp")))
                    .toList();
            Long observedFirst = series.isEmpty() ? null : timestamp(first(series.get(0), "event_time", "time"), "timestamp");
            Long observedLast = series.isEmpty() ? null : timestamp(first(series.get(series.size() - 1), "event_time", "time"), "timestamp");
            JsonNode bound = expectedBySeries == null ? null : expectedBySeries.get(a + "|" + timeframe);
            Long first = bound != null && finiteNumber(bound.get("min_time"))
                    ? Long.valueOf((long) jsNumber(bound.get("min_time"))) : observedFirst;
            Long last = bound != null && finiteNumber(bound.get("max_time"))
                    ? Long.valueOf((long) jsNumber(bound.get("max_time"))) : observedLast;
            long derivedExpected = first != null && last != null ? Math.floorDiv(last - first, durations.get(timeframe)) + 1 : 0;
            long expected = bound != null && jsNumber(bound.get("expected_rows")) != 0
                    ? (long) jsNumber(bound.get("expected_rows")) : derivedExpected;
            Set<Long> unique = new LinkedHashSet<>();
            for (JsonNode row : series) unique.add(timestamp(first(row, "event_time", "time"), "timestamp"));
            long observedRows = unique.stream().filter(value -> (first == null || value >= first) && (last == null || value <= last)).count();
            ObjectNode row = JSON.objectNode();
            row.put("asset", a); row.put("timeframe", timeframe); row.put("expected_rows", expected); row.put("observed_rows", observedRows);
            row.put("observed_fraction", expected != 0 ? observedRows / (double) expected : 0);
            row.put("gap_count", Math.max(0, unique.size() - observedRows) + Math.max(0, expected - observedRows));
            row.put("minimum_fraction", minimumFraction); row.put("bound", bound != null && bound.isObject() && !bound.isEmpty());
            row.put("complete", expected > 0 && observedRows / (double) expected >= minimumFraction && observedRows >= expected * minimumFraction);
            result.add(row);
        }
        return result;
    }

    public static ObjectNode validateNextDataSnapshot(JsonNode options) {
        JsonNode manifest = options == null ? null : options.get("manifest");
        if (manifest == null || !"strategy-data-manifest/2".equals(text(manifest.get("schema")))) {
            throw new IllegalArgumentException("strategy-data-manifest/2 is required for next-generation data");
        }
        if (!isSha(manifest.get("content_sha256")) || !ownHash(manifest).equals(text(manifest.get("content_sha256")))) {
            throw new IllegalArgumentException("data manifest retained hash tampering or missing content hash");
        }
        validateSchema(manifest);
        List<JsonNode> featureRows = rows(first(options, "featureRows", "feature_rows"));
        List<JsonNode> labelRows = rows(first(options, "labelRows", "label_rows"));
        String phase = optionText(options, "phase", "phase", "DEVELOPMENT");
        List<String> requiredAssets = stringList(first(options, "requiredAssets", "required_assets"), UNIVERSE).stream().map(LegacyResearchNext::asset).toList();
        for (JsonNode row : featureRows) if (futureLabelPath(row, "") != null) throw new IllegalArgumentException("future label entered predictor feature rows");
        for (JsonNode row : labelRows) if (futureLabelPath(row, "") == null) throw new IllegalArgumentException("label rows lack an explicit future outcome field");
        Map<String, JsonNode> receipts = new LinkedHashMap<>();
        for (JsonNode receipt : rows(first(options, "sourceReceipts", "source_receipts"))) {
            ObjectNode validateOptions = JSON.objectNode().put("phase", phase);
            validateSourceReceipt(receipt, validateOptions);
            String sourceId = text(receipt.get("source_id"));
            if (receipts.putIfAbsent(sourceId, receipt) != null) throw new IllegalArgumentException("duplicate source receipt " + sourceId);
        }
        if (receipts.isEmpty()) throw new IllegalArgumentException("authoritative PIT validation requires source receipts at every phase");
        Set<String> seen = new LinkedHashSet<>();
        List<JsonNode> allRows = new ArrayList<>(featureRows); allRows.addAll(labelRows);
        for (JsonNode row : allRows) {
            boolean context = "context".equals(text(row.get("asset_class")).toLowerCase(Locale.ROOT)) || "CONTEXT".equals(text(row.get("role")));
            String a = context ? text(row.get("asset")).toLowerCase(Locale.ROOT) : asset(row.get("asset"));
            long event = timestamp(first(row, "event_time", "time"), "row event_time");
            long available = timestamp(first(row, "availability_time", "available_at"), "row availability_time");
            if (available < event) throw new IllegalArgumentException("row availability precedes event time");
            String key = a + "|" + optionText(row, "timeframe", "timeframe", "4h") + "|" + event + "|" + optionText(row, "role", "role", "feature");
            if (!seen.add(key)) throw new IllegalArgumentException("duplicate PIT row " + key);
            if (!"DEVELOPMENT".equals(phase)) {
                if (!present(row.get("source_id")) || !receipts.containsKey(text(row.get("source_id")))) throw new IllegalArgumentException("row " + key + " is not bound to a source receipt");
                if (!isSha(first(row, "physical_sha256", "row_sha256"))) throw new IllegalArgumentException("row " + key + " is missing physical hash");
                String unsafe = optionText(row, "revision_status", "pit_tier", "").toUpperCase(Locale.ROOT);
                if (Set.of("REVISED", "NON_PIT", "UNKNOWN", "UNVERIFIED_DEVELOPMENT_ONLY").contains(unsafe)) throw new IllegalArgumentException("unsafe PIT row for " + phase);
            }
        }
        List<String> missing = requiredAssets.stream().filter(a -> featureRows.stream().noneMatch(row -> a.equals(text(row.get("asset")).toLowerCase(Locale.ROOT)))).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("snapshot missing required crypto assets in features: " + String.join(", ", missing));
        List<String> missingLabels = requiredAssets.stream().filter(a -> labelRows.stream().noneMatch(row -> a.equals(text(row.get("asset")).toLowerCase(Locale.ROOT)))).toList();
        if (!missingLabels.isEmpty()) throw new IllegalArgumentException("snapshot missing required crypto assets in labels: " + String.join(", ", missingLabels));
        JsonNode featureArtifact = manifest.get("feature_store"); JsonNode labelArtifact = featureArtifact == null ? null : featureArtifact.get("labels");
        if (featureArtifact == null || !isSha(featureArtifact.get("sha256")) || labelArtifact == null || !isSha(labelArtifact.get("sha256"))) {
            throw new IllegalArgumentException("manifest must bind physical feature and label artifacts");
        }
        Map<String, ObjectNode> featureBounds = expectedCoverageBySeries(manifest, "datasets");
        Map<String, ObjectNode> labelBounds = expectedCoverageBySeries(manifest, "label_datasets");
        List<String> timeframes = List.of("1m", "1h", "4h", "1d");
        if (!"DEVELOPMENT".equals(phase)) {
            if (!text(manifest.get("source_registry_sha256")).equals(text(SOURCE_REGISTRY_DOCUMENT.get("content_sha256")))) throw new IllegalArgumentException("manifest is not bound to the canonical hashed source registry");
            List<String> requiredSeries = requiredAssets.stream().flatMap(a -> timeframes.stream().map(tf -> a + "|" + tf)).toList();
            if (requiredSeries.stream().anyMatch(key -> !featureBounds.containsKey(key)) || requiredSeries.stream().anyMatch(key -> !labelBounds.containsKey(key))) throw new IllegalArgumentException("manifest lacks complete required 1m/1h/4h/1d feature/label coverage declarations");
            ObjectNode coverageOptions = JSON.objectNode(); coverageOptions.set("assets", strings(requiredAssets)); coverageOptions.set("timeframes", strings(timeframes)); coverageOptions.set("expectedBySeries", mapNode(featureBounds));
            ArrayNode featureCoverage = coverageMatrix(arrayOf(featureRows), coverageOptions);
            coverageOptions.set("expectedBySeries", mapNode(labelBounds)); ArrayNode labelCoverage = coverageMatrix(arrayOf(labelRows), coverageOptions);
            if (rows(featureCoverage).stream().anyMatch(row -> !row.path("complete").asBoolean(false)) || rows(labelCoverage).stream().anyMatch(row -> !row.path("complete").asBoolean(false))) throw new IllegalArgumentException("required 1m/1h/4h/1d coverage is incomplete");
        }
        ObjectNode coverageOptions = JSON.objectNode(); coverageOptions.set("assets", strings(requiredAssets));
        ObjectNode result = JSON.objectNode().put("valid", true).put("phase", phase);
        result.set("coverage", coverageMatrix(arrayOf(featureRows), coverageOptions)); result.set("label_coverage", coverageMatrix(arrayOf(labelRows), coverageOptions));
        result.put("feature_rows", featureRows.size()).put("label_rows", labelRows.size());
        result.put("derivatives_history", featureRows.stream().anyMatch(row -> text(row.get("instrument_type")).toLowerCase(Locale.ROOT).contains("perp")) ? "DECLARED_ONLY" : "UNAVAILABLE_NOT_FABRICATED");
        return result;
    }

    public static boolean validateNextPrecommit(JsonNode precommit) { return validateNextPrecommit(precommit, JSON.objectNode().put("requireHash", true)); }

    public static boolean validateNextPrecommit(JsonNode precommit, JsonNode options) {
        if (precommit == null || !"strategy-precommit/1".equals(text(precommit.get("schema")))) throw new IllegalArgumentException("strategy-precommit/1 is required before any candidate generation");
        boolean canonical = present(precommit.get("participants")) || present(precommit.get("payoff")) || present(precommit.get("economic_behavioral_mechanism"));
        List<Requirement> requirements = canonical ? canonicalRequirements() : legacyRequirements();
        for (Requirement requirement : requirements) {
            JsonNode value = first(precommit, requirement.paths().toArray(String[]::new));
            if (!"non_crypto_context_only".equals(requirement.name()) && !hasValue(value)) throw new IllegalArgumentException("precommit missing required premise field: " + requirement.name());
            if ("non_crypto_context_only".equals(requirement.name()) && value == null) throw new IllegalArgumentException("precommit missing required premise field: " + requirement.name());
        }
        if (canonical) {
            if (!"CRYPTO_ONLY".equals(text(precommit.path("tradable_instrument_contract").get("universe")))) throw new IllegalArgumentException("tradable instrument universe must be CRYPTO_ONLY");
            Map<String, JsonNode> nested = new LinkedHashMap<>();
            nested.put("feature_contract.series", precommit.path("feature_contract").get("series")); nested.put("experiment.evidence_phase", precommit.path("experiment").get("evidence_phase")); nested.put("experiment.ablation_role", precommit.path("experiment").get("ablation_role")); nested.put("experiment.required_assets", precommit.path("experiment").get("required_assets")); nested.put("experiment.grid", precommit.path("experiment").get("grid")); nested.put("experiment.acceptance.robust_stats", precommit.path("experiment").path("acceptance").get("robust_stats")); nested.put("experiment.acceptance.plateau", precommit.path("experiment").path("acceptance").get("plateau")); nested.put("experiment.acceptance.stress", precommit.path("experiment").path("acceptance").get("stress")); nested.put("experiment.acceptance.portfolio", precommit.path("experiment").path("acceptance").get("portfolio"));
            for (Map.Entry<String, JsonNode> entry : nested.entrySet()) if (!hasValue(entry.getValue())) throw new IllegalArgumentException("precommit missing required Stage-1 field: " + entry.getKey());
            String role = text(precommit.get("role_of_composite_score")).toLowerCase(Locale.ROOT);
            if (role.contains("select") || role.contains("threshold")) throw new IllegalArgumentException("composite score selection is not allowed in CORE_PREMISE");
            rejectTemplatePlaceholder(precommit, "field");
        } else {
            JsonNode deferred = first(precommit, "composite_score_deferred", "composite_deferred");
            if (!(deferred != null && (deferred.isBoolean() && deferred.booleanValue() || "true".equals(text(deferred).toLowerCase(Locale.ROOT))))) throw new IllegalArgumentException("composite score must be explicitly deferred in Stage 1");
        }
        JsonNode instruments = first(precommit, "tradable_instrument_contract.instruments", "instrument_scope");
        if (rows(instruments).isEmpty()) throw new IllegalArgumentException("precommit must declare at least one executable instrument");
        for (JsonNode raw : rows(instruments)) {
            JsonNode spec = raw.isTextual() ? JSON.objectNode().set("asset", raw) : raw;
            String a = text(spec.get("asset")).toLowerCase(Locale.ROOT);
            if (!UNIVERSE.contains(a)) throw new IllegalArgumentException("instrument asset " + (a.isEmpty() ? "?" : a) + " is outside the eight-asset universe");
            String type = optionText(spec, "instrument_type", "type", "").toLowerCase(Locale.ROOT);
            if ("doge".equals(a) || type.contains("doge")) throw new IllegalArgumentException("DOGE is excluded");
            if (List.of("spot", "perpetual", "dated_future", "usd_m_linear_perpetual", "usd_m_linear_future").stream().noneMatch(value -> type.equals(value) || type.contains(value))) throw new IllegalArgumentException("unsupported executable instrument in precommit: " + type);
            if (spec.has("asset_class") && !"crypto".equals(text(spec.get("asset_class")).toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("only crypto instruments are executable");
            if (spec.has("venue") && !"binance".equals(text(spec.get("venue")).toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("only Binance instruments are executable");
        }
        if (canonical) {
            JsonNode template = precommit.path("candidate_template").has("instrument") ? precommit.path("candidate_template").get("instrument") : precommit.get("candidate_template");
            if (template != null && present(template.get("asset")) && !UNIVERSE.contains(text(template.get("asset")).toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("candidate template asset is outside the universe");
            for (JsonNode row : rows(precommit.path("feature_contract").get("series"))) {
                if (present(row.get("asset")) && !UNIVERSE.contains(text(row.get("asset")).toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("feature series asset is outside the universe");
                if (present(row.get("asset_class")) && !"crypto".equals(text(row.get("asset_class")).toLowerCase(Locale.ROOT)) && !row.path("context_only").asBoolean(false)) throw new IllegalArgumentException("tradable feature series must be crypto");
            }
        }
        if (precommit.has("content_sha256") && !ownHash(precommit).equals(text(precommit.get("content_sha256")))) throw new IllegalArgumentException("precommit retained hash tampering");
        boolean requireHash = options == null || !options.has("requireHash") || options.path("requireHash").asBoolean(true);
        if (requireHash && !isSha(precommit.get("content_sha256"))) throw new IllegalArgumentException("frozen precommit must carry content_sha256");
        return true;
    }

    public static ObjectNode freezeNextPrecommit(JsonNode value) {
        ObjectNode precommit = objectCopy(value, "precommit");
        if (precommit.has("content_sha256")) { validateNextPrecommit(precommit); return precommit; }
        if (!precommit.has("schema") || !present(precommit.get("schema"))) precommit.put("schema", "strategy-precommit/1");
        if (!precommit.has("created_at")) precommit.put("created_at", DETERMINISTIC_READINESS_TIME);
        precommit.put("content_sha256", ownHash(precommit));
        validateNextPrecommit(precommit);
        return precommit;
    }

    public static ObjectNode generateNextCandidates(JsonNode options) {
        JsonNode precommit = options == null ? null : options.get("precommit"); validateNextPrecommit(precommit);
        String mode = optionText(options, "method", "method", "GRID").toUpperCase(Locale.ROOT);
        if (!List.of("GRID", "RANDOM", "GENETIC", "ML").contains(mode)) throw new IllegalArgumentException("unsupported candidate generator: " + optionText(options, "method", "method", "GRID"));
        double seedNumber = optionDouble(options, "seed", "seed", 1);
        if (seedNumber != Math.rint(seedNumber)) throw new IllegalArgumentException("candidate generator seed must be a frozen integer");
        long seed = (long) seedNumber; JsonNode provenanceInput = first(options, "modelProvenance", "model_provenance");
        if (!"GRID".equals(mode)) {
            if (provenanceInput == null || !present(provenanceInput.get("frozen_budget")) || !present(provenanceInput.get("frozen_seeds")) || !present(provenanceInput.get("training_cutoff"))) throw new IllegalArgumentException(mode + " requires frozen budget, seeds, and training_cutoff provenance");
            if ("ML".equals(mode) && !present(provenanceInput.get("validation_cutoff"))) throw new IllegalArgumentException("ML requires a frozen validation_cutoff");
            if ("GENETIC".equals(mode)) {
                for (String key : List.of("chromosome", "fitness", "selection", "mutation", "crossover", "population", "generations", "stopping_rule")) if (!present(provenanceInput.get(key))) throw new IllegalArgumentException("GENETIC requires a complete frozen evolutionary contract");
                throw new IllegalArgumentException("GENETIC is fail-closed until a recorded evolutionary fitness/selection implementation is bound; deterministic non-adaptive sampling is not mislabeled as GA");
            }
        }
        JsonNode sourceGrid = first(options, "grid"); if (sourceGrid == null || sourceGrid.isNull()) sourceGrid = first(precommit, "candidate_grid", "experiment.grid"); if (sourceGrid == null) sourceGrid = JSON.objectNode();
        List<ObjectNode> definitions = cartesian(sourceGrid); DoubleSupplier random = xorshift(seed);
        if ("RANDOM".equals(mode) || "ML".equals(mode)) {
            int trials = (int) Math.max(1, optionDouble(options, "trials", "trials", optionDouble(precommit.path("search_budget"), "trials", "trials", definitions.isEmpty() ? 1 : definitions.size())));
            List<String> keys = fieldNames(sourceGrid); keys.sort(String::compareTo); definitions = new ArrayList<>();
            for (int index = 0; index < trials; index++) { ObjectNode definition = JSON.objectNode(); for (String key : keys) { List<JsonNode> values = gridValues(sourceGrid.get(key)); definition.set(key, cloneNode(values.get((int) Math.floor(random.getAsDouble() * values.size())))); } definitions.add(definition); }
        }
        ArrayNode candidates = JSON.arrayNode(); Map<String, List<String>> aliases = new TreeMap<>();
        for (int index = 0; index < definitions.size(); index++) {
            ObjectNode definition = definitions.get(index).deepCopy(); definition.put("stage", optionText(precommit, "stage", "stage", "CORE_PREMISE"));
            String behavior = behaviorFor(definitions.get(index)); String id = "next-" + String.format(Locale.ROOT, "%06d", index + 1);
            ObjectNode candidate = JSON.objectNode().put("candidate_id", id); candidate.set("definition", definition); candidate.put("hypothesis_index", index + 1).put("generator", mode).put("behavior_sha256", behavior); candidate.set("model_provenance", "GRID".equals(mode) ? NullNode.instance : cloneNode(provenanceInput)); candidates.add(candidate);
            aliases.computeIfAbsent(behavior, ignored -> new ArrayList<>()).add(id);
        }
        ArrayNode aliasRows = JSON.arrayNode(); for (Map.Entry<String, List<String>> entry : aliases.entrySet()) { entry.getValue().sort(String::compareTo); ObjectNode row = JSON.objectNode().put("behavior_sha256", entry.getKey()); row.set("candidate_ids", strings(entry.getValue())); aliasRows.add(row); }
        ObjectNode provenance;
        if ("GRID".equals(mode)) provenance = JSON.objectNode().put("frozen", true).put("search_space_sha256", hash(sourceGrid));
        else { provenance = objectCopy(provenanceInput, "modelProvenance"); provenance.put("frozen", true).put("seed", seed); ArrayNode evaluated = JSON.arrayNode(); for (JsonNode row : candidates) evaluated.add(JSON.objectNode().put("candidate_id", text(row.get("candidate_id"))).put("behavior_sha256", text(row.get("behavior_sha256")))); provenance.put("evaluated_candidates_sha256", hash(evaluated)); provenance.set("generation_history", NullNode.instance); }
        ObjectNode generator = JSON.objectNode().put("method", mode).put("seed", seed).put("declared_trials", candidates.size()); generator.set("population", NullNode.instance); generator.set("generations", NullNode.instance); generator.set("provenance", provenance);
        ObjectNode accounting = JSON.objectNode().put("declared_hypotheses", candidates.size()).put("syntactic_k", candidates.size()).put("runtime_behavioral_k", aliasRows.size()).put("aliases_included", true);
        ObjectNode result = JSON.objectNode().put("schema", "strategy-candidate-set/4")
                .put("precommit_sha256", text(precommit.get("content_sha256")));
        result.set("generator", generator);
        result.put("declared_k", candidates.size()).put("effective_k", aliasRows.size());
        result.set("candidates", candidates);
        result.set("aliases", aliasRows);
        result.set("accounting", accounting);
        result = withHash(result);
        validateSchema(result);
        return result;
    }

    public static boolean validateCandidateSetNext(JsonNode candidateSet) { return validateCandidateSetNext(candidateSet, null); }

    public static boolean validateCandidateSetNext(JsonNode candidateSet, JsonNode precommit) {
        if (candidateSet == null || !"strategy-candidate-set/4".equals(text(candidateSet.get("schema"))) || !ownHash(candidateSet).equals(text(candidateSet.get("content_sha256")))) throw new IllegalArgumentException("strategy-candidate-set/4 hash/schema is invalid");
        if (precommit != null && !text(candidateSet.get("precommit_sha256")).equals(text(precommit.get("content_sha256")))) throw new IllegalArgumentException("candidate set is not bound to the frozen precommit");
        if (candidateSet.path("declared_k").asInt() != candidateSet.path("candidates").size() || candidateSet.path("effective_k").asInt() != candidateSet.path("aliases").size()) throw new IllegalArgumentException("candidate accounting K does not reconcile");
        Set<String> ids = new LinkedHashSet<>(); Set<String> behaviors = new LinkedHashSet<>();
        for (JsonNode row : rows(candidateSet.get("candidates"))) {
            String id = text(row.get("candidate_id")); if (!ids.add(id)) throw new IllegalArgumentException("candidate id collision");
            String behavior = text(row.get("behavior_sha256")); if (!isSha(row.get("behavior_sha256")) || !behavior.equals(behaviorFor(row.path("definition")))) throw new IllegalArgumentException("candidate behavior hash does not match its executable definition");
            checkCandidateAssets(row.get("definition"), ""); behaviors.add(behavior);
        }
        if (behaviors.size() != candidateSet.path("effective_k").asInt()) throw new IllegalArgumentException("runtime behavior K mismatch");
        JsonNode provenance = candidateSet.path("generator").get("provenance"); if (provenance == null || !provenance.path("frozen").asBoolean(false)) throw new IllegalArgumentException("candidate generator provenance is not frozen");
        String method = text(candidateSet.path("generator").get("method")); if (!"GRID".equals(method) && (!present(provenance.get("frozen_budget")) || !present(provenance.get("frozen_seeds")) || !present(provenance.get("training_cutoff")))) throw new IllegalArgumentException("non-grid candidate provenance lacks frozen budget/seeds/cutoff");
        if ("ML".equals(method) && !present(provenance.get("validation_cutoff"))) throw new IllegalArgumentException("ML candidate provenance lacks validation cutoff");
        validateSchema(candidateSet); return true;
    }

    public static String behaviorHash(JsonNode intent) { return behaviorFor(intent == null ? JSON.objectNode() : intent); }

    public static ObjectNode appendExposureLedger(JsonNode options) {
        JsonNode prior = options == null ? null : options.get("prior"); String family = requiredText(first(options, "hypothesisFamily", "hypothesis_family"), "hypothesisFamily"); String root = requireSha(first(options, "datasetRootSha256", "dataset_root_sha256"), "datasetRootSha256 must be a SHA-256 hash", false);
        if (prior != null && !prior.isNull()) validateExposureLedger(prior);
        if (prior != null && !prior.isNull() && (!family.equals(text(prior.get("hypothesis_family"))) || !root.equals(text(prior.get("dataset_root_sha256"))))) throw new IllegalArgumentException("exposure ledger family/dataset root cannot change");
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode candidate : LegacyResearchSupport.rows(options == null ? null : options.get("candidates"))) {
            String derived = candidate.has("definition") ? behaviorHash(candidate.get("definition")) : candidate.has("intent") ? behaviorHash(candidate.get("intent")) : null;
            if (present(candidate.get("behavior_sha256")) && derived != null && !text(candidate.get("behavior_sha256")).equals(derived)) throw new IllegalArgumentException("exposure behavior hash mismatch for " + optionText(candidate, "candidate_id", "id", String.valueOf(rows.size() + 1)));
            ObjectNode row = JSON.objectNode().put("candidate_id", optionText(candidate, "candidate_id", "id", "candidate-" + (rows.size() + 1))).put("behavior_sha256", present(candidate.get("behavior_sha256")) ? text(candidate.get("behavior_sha256")) : derived != null ? derived : behaviorHash(candidate)); setNullable(row, "observed_at", candidate.get("observed_at")); row.put("source", optionText(candidate, "source", "source", "generation")); rows.add(row);
        }
        List<ObjectNode> runtimeRows = new ArrayList<>(); int runtimeIndex = 0; for (JsonNode intent : LegacyResearchSupport.rows(first(options, "runtimeBehaviors", "runtime_behaviors"))) { ObjectNode row = JSON.objectNode().put("candidate_id", optionText(intent, "candidate_id", "candidate_id", "runtime-" + (++runtimeIndex))).put("behavior_sha256", present(intent.get("behavior_sha256")) ? text(intent.get("behavior_sha256")) : behaviorHash(intent)); setNullable(row, "observed_at", intent.get("observed_at")); row.put("source", "runtime"); runtimeRows.add(row); }
        List<ObjectNode> all = new ArrayList<>(); if (prior != null && !prior.isNull()) for (JsonNode priorRow : LegacyResearchSupport.rows(prior.get("chain"))) if (!"GENESIS".equals(text(priorRow.get("source")))) { ObjectNode copy = objectCopy(priorRow, "exposure row"); copy.remove(List.of("sequence", "previous_sha256")); all.add(copy); } all.addAll(rows); all.addAll(runtimeRows);
        Map<String, ObjectNode> firstByBehavior = new LinkedHashMap<>(); for (ObjectNode row : all) firstByBehavior.putIfAbsent(text(row.get("behavior_sha256")), row);
        ArrayNode aliases = JSON.arrayNode(); firstByBehavior.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> { List<String> candidateIds = all.stream().filter(row -> entry.getKey().equals(text(row.get("behavior_sha256")))).map(row -> text(row.get("candidate_id"))).distinct().sorted().toList(); ObjectNode alias = JSON.objectNode().put("behavior_sha256", entry.getKey()); alias.set("candidate_ids", strings(candidateIds)); setNullable(alias, "first_observed_at", entry.getValue().get("observed_at")); aliases.add(alias); });
        ArrayNode chain = JSON.arrayNode(); if (all.isEmpty()) { ObjectNode genesis = JSON.objectNode().put("candidate_id", "GENESIS"); genesis.set("behavior_sha256", NullNode.instance); genesis.set("observed_at", NullNode.instance); genesis.put("source", "GENESIS").put("sequence", 1); genesis.set("previous_sha256", NullNode.instance); chain.add(genesis); } else for (int index = 0; index < all.size(); index++) { ObjectNode row = all.get(index).deepCopy(); row.put("sequence", index + 1); if (index == 0) row.set("previous_sha256", NullNode.instance); else row.put("previous_sha256", hash(all.get(index - 1))); chain.add(row); }
        List<ObjectNode> runtimeSource = runtimeRows.isEmpty() ? rows : runtimeRows; long runtimeK = runtimeSource.stream().map(row -> text(row.get("behavior_sha256"))).distinct().count();
        ObjectNode ledger = JSON.objectNode().put("schema", EXPOSURE_SCHEMA).put("hypothesis_family", family).put("dataset_root_sha256", root).put("declared_k", LegacyResearchSupport.rows(options == null ? null : options.get("candidates")).size() + (prior == null ? 0 : prior.path("declared_k").asInt())).put("runtime_k", runtimeK).put("cumulative_k", aliases.size()); ledger.set("behavior_aliases", aliases); ledger.set("chain", chain); ledger = withHash(ledger); validateSchema(ledger); return ledger;
    }

    public static boolean validateExposureLedger(JsonNode ledger) {
        if (ledger == null || !EXPOSURE_SCHEMA.equals(text(ledger.get("schema"))) || !ownHash(ledger).equals(text(ledger.get("content_sha256")))) throw new IllegalArgumentException("exposure ledger hash/schema is invalid");
        if (ledger.path("cumulative_k").asInt() != ledger.path("behavior_aliases").size()) throw new IllegalArgumentException("cumulative K does not equal unique behavior aliases");
        String prior = null; Set<String> chainBehaviors = new LinkedHashSet<>(); int index = 0;
        for (JsonNode raw : LegacyResearchSupport.rows(ledger.get("chain"))) { ObjectNode row = objectCopy(raw, "exposure row"); JsonNode previous = row.get("previous_sha256"); String previousText = previous == null || previous.isNull() ? null : text(previous); if (row.path("sequence").asInt() != ++index || !java.util.Objects.equals(previousText, prior)) throw new IllegalArgumentException("exposure ledger chain is broken"); if (index == 1 && "GENESIS".equals(text(row.get("source")))) { prior = null; continue; } if (!isSha(row.get("behavior_sha256"))) throw new IllegalArgumentException("exposure ledger row behavior hash is missing"); chainBehaviors.add(text(row.get("behavior_sha256"))); row.remove(List.of("sequence", "previous_sha256")); prior = hash(row); }
        Set<String> aliases = new LinkedHashSet<>(); for (JsonNode row : LegacyResearchSupport.rows(ledger.get("behavior_aliases"))) aliases.add(text(row.get("behavior_sha256"))); if (!aliases.equals(chainBehaviors)) throw new IllegalArgumentException("exposure ledger aliases do not reconcile to every attempted behavior"); return true;
    }

    public static ObjectNode makeExecutionPolicy() { return makeExecutionPolicy(JSON.objectNode()); }

    public static ObjectNode makeExecutionPolicy(JsonNode overrides) {
        ObjectNode policy = parseObject("""
                {"schema":"strategy-execution-policy/1","venue":"binance","instrument_scope":["SPOT","USD_M_LINEAR_PERPETUAL","USD_M_LINEAR_FUTURE"],"order_types":["MARKET","STOP_MARKET"],"bar_convention":"completed_signal_bar_then_next_executable_1m_child","same_bar_collision":"ADVERSE","fee_model":{"source":"time_effective_binance_fee_schedule","bound_schedule_required":true,"maker_allowed":false,"taker_required":true},"slippage_model":{"type":"deterministic_public_data","inputs":["realized_volatility","spread_proxy","volume","participation_rate"],"calibration":"local_realized_fills_when_available"},"capacity_model":{"type":"participation_cap","default_max_rate":0.05,"reject_unfilled_residual":true},"funding_model":{"type":"exact_event_identity","unavailable_history":"FAIL_CLOSED"},"outage_model":{"type":"reprice_exit_and_gap","delete_trades":false}}
                """);
        merge(policy, overrides); ObjectNode result = withHash(policy); validateSchema(result); return result;
    }

    public static boolean validateExecutionPolicy(JsonNode policy) {
        if (policy == null || !EXECUTION_SCHEMA.equals(text(policy.get("schema"))) || !ownHash(policy).equals(text(policy.get("content_sha256")))) throw new IllegalArgumentException("execution policy hash/schema is invalid");
        if (!"binance".equals(text(policy.get("venue"))) || !stringList(policy.get("instrument_scope"), List.of()).equals(EXECUTABLE_INSTRUMENTS)) throw new IllegalArgumentException("execution scope must be Binance spot and USD-M linear instruments");
        if (!stringList(policy.get("order_types"), List.of()).equals(List.of("MARKET", "STOP_MARKET"))) throw new IllegalArgumentException("passive limit/HFT/options/multileg orders are unsupported");
        if (!"ADVERSE".equals(text(policy.get("same_bar_collision"))) || !"completed_signal_bar_then_next_executable_1m_child".equals(text(policy.get("bar_convention"))) || !policy.path("capacity_model").isObject() || !policy.path("fee_model").path("bound_schedule_required").asBoolean(false)) throw new IllegalArgumentException("execution ordering/cost binding is not fail-closed"); validateSchema(policy); return true;
    }

    public static ObjectNode makePortfolioPolicy() { return makePortfolioPolicy(JSON.objectNode()); }

    public static ObjectNode makePortfolioPolicy(JsonNode overrides) {
        ObjectNode policy = parseObject("""
                {"schema":"strategy-portfolio-policy/1","initial_risk_per_trade_pct":1.5,"max_total_open_risk_pct":6,"max_asset_open_risk_pct":3,"max_cluster_open_risk_pct":4.5,"max_gross_exposure_x":3,"max_net_exposure_x":2,"max_collateral_pct":70,"max_positions":6,"max_positions_per_asset":2,"max_drawdown_pct":18,"ruin_boundary_pct":30,"max_ruin_probability":0.05,"max_mark_gap_ms":3600000,"full_risk_after_activation":true,"probation_ramp":false}
                """);
        merge(policy, overrides); ObjectNode result = withHash(policy); validateSchema(result); return result;
    }

    public static boolean validatePortfolioPolicy(JsonNode policy) {
        if (policy == null || !PORTFOLIO_SCHEMA.equals(text(policy.get("schema"))) || !ownHash(policy).equals(text(policy.get("content_sha256")))) throw new IllegalArgumentException("portfolio policy hash/schema is invalid");
        ObjectNode exact = parseObject("""
                {"initial_risk_per_trade_pct":1.5,"max_total_open_risk_pct":6,"max_asset_open_risk_pct":3,"max_cluster_open_risk_pct":4.5,"max_gross_exposure_x":3,"max_net_exposure_x":2,"max_collateral_pct":70,"max_positions":6,"max_positions_per_asset":2,"max_drawdown_pct":18,"ruin_boundary_pct":30,"max_ruin_probability":0.05,"full_risk_after_activation":true,"probation_ramp":false}
                """);
        for (String key : fieldNames(exact)) if (!java.util.Objects.equals(policy.get(key), exact.get(key))) throw new IllegalArgumentException("mandatory risk profile mismatch: " + key);
        if (!(jsNumber(policy.get("max_mark_gap_ms")) > 0)) throw new IllegalArgumentException("portfolio max_mark_gap_ms must be positive"); validateSchema(policy); return true;
    }

    public static ObjectNode simulateBinanceExecution(JsonNode options) {
        return LegacyResearchNextRuntime.simulateBinanceExecution(options);
    }

    public static ObjectNode simulateResearchPortfolio(JsonNode options) {
        return LegacyResearchNextRuntime.simulateResearchPortfolio(options);
    }

    public static ObjectNode stationaryBlockMaxStatistic(JsonNode candidateReturns) {
        return LegacyResearchNextRuntime.stationaryBlockMaxStatistic(candidateReturns, JSON.objectNode());
    }

    public static ObjectNode stationaryBlockMaxStatistic(JsonNode candidateReturns, JsonNode options) {
        return LegacyResearchNextRuntime.stationaryBlockMaxStatistic(candidateReturns, options);
    }

    @FunctionalInterface
    public interface FoldEvaluator {
        ObjectNode evaluate(JsonNode candidate, ObjectNode context);
    }

    public static ObjectNode nestedWalkForward(JsonNode options, FoldEvaluator evaluator) {
        return LegacyResearchNextRuntime.nestedWalkForward(options, evaluator);
    }

    public static ObjectNode evaluatePlateau(JsonNode candidateMetrics) {
        return LegacyResearchNextRuntime.evaluatePlateau(candidateMetrics, JSON.objectNode());
    }

    public static ObjectNode evaluatePlateau(JsonNode candidateMetrics, JsonNode options) {
        return LegacyResearchNextRuntime.evaluatePlateau(candidateMetrics, options);
    }

    public static ObjectNode runAblations(JsonNode options) {
        return LegacyResearchNextRuntime.runAblations(options);
    }

    public static ObjectNode makeProspectiveReservation(JsonNode options) {
        return LegacyResearchNextProspective.makeProspectiveReservation(options);
    }

    public static ObjectNode makeProspectiveLedger(JsonNode reservation) {
        return LegacyResearchNextProspective.makeProspectiveLedger(reservation);
    }

    public static ObjectNode appendProspectiveEvent(JsonNode ledger, JsonNode options) {
        return LegacyResearchNextProspective.appendProspectiveEvent(ledger, options);
    }

    public static boolean validateProspectiveLedger(JsonNode ledger) {
        return LegacyResearchNextProspective.validateProspectiveLedger(ledger);
    }

    public static ObjectNode prospectiveEligibility(JsonNode ledger) {
        return LegacyResearchNextProspective.prospectiveEligibility(ledger, JSON.objectNode());
    }

    public static ObjectNode prospectiveEligibility(JsonNode ledger, JsonNode options) {
        return LegacyResearchNextProspective.prospectiveEligibility(ledger, options);
    }

    public static ObjectNode monitorProspective(JsonNode options) {
        return LegacyResearchNextProspective.monitorProspective(options);
    }

    public static ObjectNode makeStackContract(JsonNode options) {
        return LegacyResearchNextArtifacts.makeStackContract(options);
    }

    public static ObjectNode readinessAudit() {
        return LegacyResearchNextArtifacts.readinessAudit(JSON.objectNode());
    }

    public static ObjectNode readinessAudit(JsonNode options) {
        return LegacyResearchNextArtifacts.readinessAudit(options);
    }

    public static String readinessMarkdown(JsonNode audit) {
        return LegacyResearchNextArtifacts.readinessMarkdown(audit);
    }

    public static boolean validateWfoStructure(JsonNode value, JsonNode options) {
        return LegacyResearchNextArtifacts.validateWfoStructure(value, options);
    }

    public static boolean validateAuthoritativeWfoArtifact(JsonNode value, JsonNode options) {
        return LegacyResearchNextArtifacts.validateAuthoritativeWfoArtifact(value, options);
    }

    public static boolean validateStressStructure(JsonNode value) {
        return LegacyResearchNextArtifacts.validateStressStructure(value, JSON.objectNode());
    }

    public static boolean validateStressStructure(JsonNode value, JsonNode options) {
        return LegacyResearchNextArtifacts.validateStressStructure(value, options);
    }

    public static ObjectNode makeProspectiveAttestation(JsonNode options) {
        return LegacyResearchNextTrust.makeProspectiveAttestation(options);
    }

    public static boolean verifyProspectiveAttestation(JsonNode attestation, JsonNode options) {
        return LegacyResearchNextTrust.verifyProspectiveAttestation(attestation, options);
    }

    public static ObjectNode makeActivationArtifact(JsonNode options) {
        return LegacyResearchNextTrust.makeActivationArtifact(options);
    }

    public static ObjectNode makeRevocationArtifact(JsonNode options) {
        return LegacyResearchNextTrust.makeRevocationArtifact(options);
    }

    public static ObjectNode verifyActivationArtifact(JsonNode artifact, JsonNode options) {
        return LegacyResearchNextTrust.verifyActivationArtifact(artifact, options);
    }

    public static String researchDecision(JsonNode options) {
        return LegacyResearchNextTrust.researchDecision(options);
    }

    public static boolean validateNextArtifact(JsonNode value) {
        return LegacyResearchNextArtifacts.validateNextArtifact(value, JSON.objectNode());
    }

    public static boolean validateNextArtifact(JsonNode value, JsonNode options) {
        return LegacyResearchNextArtifacts.validateNextArtifact(value, options);
    }

    public static ObjectNode evaluateAuthoritativeNext(JsonNode options) {
        return LegacyResearchNextAuthoritative.evaluateAuthoritativeNext(options);
    }

    public static ObjectNode runAuthoritativeWfo(JsonNode input) {
        return LegacyResearchNextAuthoritative.runAuthoritativeWfo(input);
    }

    static String instrumentType(JsonNode instrument) {
        String type = optionText(instrument, "instrument_type", "type", "").toUpperCase(Locale.ROOT);
        if ("SPOT".equals(type) || "CASH".equals(type)) return "SPOT";
        if (type.contains("PERP")) return "USD_M_LINEAR_PERPETUAL";
        if (type.contains("FUTURE")) return "USD_M_LINEAR_FUTURE";
        throw new IllegalArgumentException("unsupported instrument: only Binance spot/USD-M linear perp/future are executable");
    }

    static long timestamp(JsonNode value, String name) {
        long time = jsTime(value); if (time == Long.MIN_VALUE) throw new IllegalArgumentException(name + " must be a valid timestamp"); return time;
    }

    static String iso(long milliseconds) { return ISO_MILLIS.format(Instant.ofEpochMilli(milliseconds)); }

    static String asset(JsonNode value) { return asset(text(value)); }

    static String asset(String value) {
        String a = value == null ? "" : value.toLowerCase(Locale.ROOT); if (!UNIVERSE.contains(a)) throw new IllegalArgumentException("asset " + (a.isEmpty() ? "?" : a) + " is outside the eight-asset crypto universe"); return a;
    }

    static boolean isSha(JsonNode value) { return value != null && HASH_RE.matcher(text(value)).matches(); }

    static String requireSha(JsonNode value, String message, boolean exactMessage) {
        if (!isSha(value)) throw new IllegalArgumentException(exactMessage ? message : message);
        return text(value);
    }

    static JsonNode first(JsonNode value, String... paths) { return LegacyResearchSupport.first(value, paths); }

    static boolean present(JsonNode value) { return value != null && !value.isNull() && !value.isMissingNode() && (!(value.isTextual()) || !value.textValue().isEmpty()); }

    static String requiredText(JsonNode value, String name) { String result = text(value).trim(); if (result.isEmpty()) throw new IllegalArgumentException(name + " is required"); return result; }

    static String optionText(JsonNode value, String first, String second, String fallback) { JsonNode found = first(value, first, second); return present(found) ? text(found) : fallback; }

    static double optionDouble(JsonNode value, String first, String second, double fallback) { JsonNode found = first(value, first, second); double number = jsNumber(found); return found != null && Double.isFinite(number) ? number : fallback; }

    static boolean finiteNumber(JsonNode value) { return Double.isFinite(jsNumber(value)); }

    static void setNullable(ObjectNode target, String key, JsonNode value) { target.set(key, value == null || value.isMissingNode() ? NullNode.instance : cloneNode(value)); }

    static void validateSchema(JsonNode value) { SCHEMAS.validateContractSchema(value); }

    static ArrayNode strings(List<String> values) { ArrayNode result = JSON.arrayNode(); values.forEach(result::add); return result; }

    static ObjectNode parseObject(String json) {
        try { return (ObjectNode) LegacyResearchSupport.MAPPER.readTree(json); }
        catch (JsonProcessingException error) { throw new ExceptionInInitializerError(error); }
    }

    static void merge(ObjectNode target, JsonNode overrides) { if (overrides != null && overrides.isObject()) overrides.fields().forEachRemaining(entry -> target.set(entry.getKey(), cloneNode(entry.getValue()))); }

    static List<String> fieldNames(JsonNode value) { List<String> names = new ArrayList<>(); if (value != null && value.isObject()) value.fieldNames().forEachRemaining(names::add); return names; }

    static List<String> stringList(JsonNode value, List<String> fallback) { if (value == null || !value.isArray()) return fallback; List<String> result = new ArrayList<>(); value.forEach(row -> result.add(text(row))); return result; }

    private static boolean validTime(JsonNode value) { if (!present(value)) return false; try { timestamp(value, "timestamp"); return true; } catch (IllegalArgumentException ignored) { return false; } }

    private static String futureLabelPath(JsonNode value, String path) {
        if (value == null || !value.isContainerNode()) return null;
        var fields = value.fields(); while (fields.hasNext()) { var field = fields.next(); String childPath = path.isEmpty() ? field.getKey() : path + "." + field.getKey(); if (NO_LABEL_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT))) return childPath; String nested = futureLabelPath(field.getValue(), childPath); if (nested != null) return nested; }
        return null;
    }

    private static Map<String, ObjectNode> expectedCoverageBySeries(JsonNode manifest, String field) {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        for (JsonNode row : rows(manifest == null ? null : manifest.get(field))) {
            String a = text(row.get("asset")).toLowerCase(Locale.ROOT), timeframe = text(row.get("timeframe")).toLowerCase(Locale.ROOT); if (a.isEmpty() || timeframe.isEmpty()) continue; String key = a + "|" + timeframe; ObjectNode prior = result.getOrDefault(key, JSON.objectNode()); ObjectNode next = JSON.objectNode(); next.put("expected_rows", Math.max(jsNumber(prior.get("expected_rows")), jsNumber(row.path("coverage").get("expected_rows")))); double oldMin = prior.has("min_time") ? jsNumber(prior.get("min_time")) : jsNumber(row.get("min_time")); double oldMax = prior.has("max_time") ? jsNumber(prior.get("max_time")) : jsNumber(row.get("max_time")); next.put("min_time", prior.has("min_time") ? Math.min(oldMin, jsNumber(row.get("min_time"))) : oldMin); next.put("max_time", prior.has("max_time") ? Math.max(oldMax, jsNumber(row.get("max_time"))) : oldMax); result.put(key, next);
        }
        return result;
    }

    private static ObjectNode mapNode(Map<String, ObjectNode> values) { ObjectNode result = JSON.objectNode(); values.forEach(result::set); return result; }

    private record Requirement(String name, List<String> paths) {}

    private static List<Requirement> canonicalRequirements() { return List.of(
            new Requirement("phenomenon", List.of("phenomenon")), new Requirement("mechanism", List.of("economic_behavioral_mechanism")), new Requirement("participants.forced_actor", List.of("participants.forced_actor")), new Requirement("participants.edge_provider", List.of("participants.edge_provider")), new Requirement("participants.edge_consumer", List.of("participants.edge_consumer")), new Requirement("persistence", List.of("persistence")), new Requirement("crowding_decay", List.of("crowding_decay")), new Requirement("direction", List.of("direction")), new Requirement("expression", List.of("expression")), new Requirement("holding_horizon", List.of("holding_horizon")), new Requirement("expected_signal_frequency", List.of("expected_signal_frequency")), new Requirement("expected_win_rate", List.of("expected_win_rate")), new Requirement("payoff", List.of("payoff")), new Requirement("regimes.expected_to_work", List.of("regimes.expected_to_work")), new Requirement("regimes.expected_to_fail", List.of("regimes.expected_to_fail")), new Requirement("failure_invalidation_mechanism", List.of("failure_invalidation_mechanism")), new Requirement("required_inputs", List.of("required_inputs")), new Requirement("falsifier", List.of("falsifier")), new Requirement("tradable_instrument_contract", List.of("tradable_instrument_contract")), new Requirement("non_crypto_context_only", List.of("non_crypto_context_only")), new Requirement("independence_replication_groups", List.of("independence_replication_groups")), new Requirement("role_of_composite_score", List.of("role_of_composite_score")), new Requirement("candidate_template", List.of("candidate_template")), new Requirement("feature_contract", List.of("feature_contract")), new Requirement("experiment", List.of("experiment")));
    }

    private static List<Requirement> legacyRequirements() { return List.of(
            new Requirement("phenomenon", List.of("phenomenon", "observable_phenomenon")), new Requirement("mechanism", List.of("mechanism", "economic_behavioral_mechanism")), new Requirement("forced_actor", List.of("forced_actor", "edge_transfer.forced_actor")), new Requirement("edge_consumer", List.of("edge_consumer", "edge_transfer.edge_consumer")), new Requirement("direction", List.of("direction", "trade_expression.direction")), new Requirement("horizon", List.of("horizon", "holding_horizon")), new Requirement("frequency_expectation", List.of("expected_signal_frequency", "expectations.signal_frequency")), new Requirement("win_rate_expectation", List.of("expected_win_rate", "expectations.win_rate")), new Requirement("payoff_expectation", List.of("expected_payoff", "expectations.payoff")), new Requirement("work_regimes", List.of("work_regimes", "regimes.work")), new Requirement("fail_regimes", List.of("fail_regimes", "regimes.fail")), new Requirement("required_inputs", List.of("required_inputs", "feature_contract.inputs")), new Requirement("falsifier", List.of("falsifier", "simplest_falsifier")), new Requirement("replication_groups", List.of("independence_replication_groups", "replication_groups")), new Requirement("composite_deferred", List.of("composite_score_deferred", "composite_deferred")));
    }

    private static boolean hasValue(JsonNode value) { return value != null && !value.isNull() && (!(value.isTextual()) || !value.textValue().isEmpty()) && (!(value.isArray()) || !value.isEmpty()); }

    private static void rejectTemplatePlaceholder(JsonNode value, String path) { if (value == null) return; if (value.isTextual() && value.textValue().trim().matches("^<.*>$")) throw new IllegalArgumentException("precommit contains an unbound template placeholder: " + path); if (value.isArray()) for (int index = 0; index < value.size(); index++) rejectTemplatePlaceholder(value.get(index), path + "[" + index + "]"); else if (value.isObject()) value.fields().forEachRemaining(entry -> rejectTemplatePlaceholder(entry.getValue(), path + "." + entry.getKey())); }

    private static DoubleSupplier xorshift(long seed) { final int[] state = {(int) seed == 0 ? 1 : (int) seed}; return () -> { int value = state[0]; value ^= value << 13; value ^= value >>> 17; value ^= value << 5; state[0] = value; return Integer.toUnsignedLong(value) / 4_294_967_296d; }; }

    private static List<JsonNode> gridValues(JsonNode value) { return value != null && value.isArray() ? rows(value) : List.of(value == null ? NullNode.instance : value); }

    private static List<ObjectNode> cartesian(JsonNode grid) { List<String> keys = fieldNames(grid); keys.sort(String::compareTo); List<ObjectNode> result = new ArrayList<>(); result.add(JSON.objectNode()); for (String key : keys) { List<ObjectNode> next = new ArrayList<>(); for (ObjectNode row : result) for (JsonNode value : gridValues(grid.get(key))) { ObjectNode copy = row.deepCopy(); copy.set(key, cloneNode(value)); next.add(copy); } result = next; } return result; }

    private static String behaviorFor(JsonNode definition) { Set<String> cosmetic = Set.of("candidate_id", "id", "label", "description", "display_name", "hypothesis_index", "stage"); return hash(stripCosmetic(definition, cosmetic)); }

    private static JsonNode stripCosmetic(JsonNode value, Set<String> cosmetic) { if (value == null) return NullNode.instance; if (value.isArray()) { ArrayNode result = JSON.arrayNode(); value.forEach(row -> result.add(stripCosmetic(row, cosmetic))); return result; } if (value.isObject()) { ObjectNode result = JSON.objectNode(); List<String> keys = fieldNames(value); keys.removeIf(cosmetic::contains); keys.sort(String::compareTo); for (String key : keys) result.set(key, stripCosmetic(value.get(key), cosmetic)); return result; } return cloneNode(value); }

    private static void checkCandidateAssets(JsonNode value, String key) { if (value == null) return; if (value.isArray()) { value.forEach(child -> checkCandidateAssets(child, key)); return; } if (!value.isObject()) { if (Set.of("asset", "symbol", "assets").contains(key)) { String normalized = text(value).toLowerCase(Locale.ROOT); if ("doge".equals(normalized) || !UNIVERSE.contains(normalized)) throw new IllegalArgumentException("candidate asset " + normalized + " is outside the eight-asset universe"); } return; } value.fields().forEachRemaining(entry -> checkCandidateAssets(entry.getValue(), entry.getKey().toLowerCase(Locale.ROOT))); }

    private static ObjectNode sourceRegistryDocument() {
        ObjectNode value = parseObject("""
                {"schema":"strategy-source-registry/1","content_sha256":"27a6b5e47f2f9e65863ae98c366a3a3213bafbce21ed2bd145d39bb40b51a92e","policy":"adapter assigns maximum tier; callers cannot self-upgrade; unknown/custom imports are development-only","sources":[{"source_id":"binance:spot-ohlcv","public":true,"maximum_pit_tier":"IMMUTABLE_EVENT_ARCHIVE","source_url":"https://data.binance.vision/"},{"source_id":"binance:usd-m-ohlcv","public":true,"maximum_pit_tier":"IMMUTABLE_EVENT_ARCHIVE","source_url":"https://data.binance.vision/"},{"source_id":"binance:public-trades","public":true,"maximum_pit_tier":"IMMUTABLE_EVENT_ARCHIVE","source_url":"https://data.binance.vision/"},{"source_id":"binance:funding","public":true,"maximum_pit_tier":"VINTAGE_REVISION_AWARE","source_url":"https://data.binance.vision/"},{"source_id":"binance:open-interest","public":true,"maximum_pit_tier":"VINTAGE_REVISION_AWARE","source_url":"https://data.binance.vision/"},{"source_id":"capture-forward","public":true,"maximum_pit_tier":"CAPTURE_FORWARD","source_url":null}],"unavailable_derivatives_policy":"do not fabricate missing funding, open interest, private fills, or historical queue state"}
                """);
        if (!ownHash(value).equals(text(value.get("content_sha256")))) throw new ExceptionInInitializerError("source registry retained hash tampering"); validateSchema(value); return value;
    }

    static boolean sourceIsPublic(String sourceId) {
        return SOURCE_REGISTRY_AUTHORITY.path(sourceId).path("public").asBoolean(false);
    }

    private static ObjectNode sourceRegistryJson() { ObjectNode result = JSON.objectNode(); for (JsonNode row : rows(SOURCE_REGISTRY_DOCUMENT.get("sources"))) { ObjectNode value = JSON.objectNode().put("public", row.path("public").asBoolean(false)).put("maximum_tier", text(row.get("maximum_pit_tier"))); setNullable(value, "source_url", row.get("source_url")); result.set(text(row.get("source_id")), value); } return result;
    }

    private static Map<String, Map<String, Object>> sourceRegistryExport() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        SOURCE_REGISTRY_AUTHORITY.fields().forEachRemaining(entry -> {
            Map<String, Object> authority = new LinkedHashMap<>();
            authority.put("public", entry.getValue().path("public").asBoolean(false));
            authority.put("maximum_tier", text(entry.getValue().get("maximum_tier")));
            authority.put("source_url", entry.getValue().path("source_url").isNull()
                    ? null : text(entry.getValue().get("source_url")));
            result.put(entry.getKey(), java.util.Collections.unmodifiableMap(authority));
        });
        return java.util.Collections.unmodifiableMap(result);
    }
}
