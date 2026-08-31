package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.LifecycleTrustService;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import com.tradinganalytics.marketdata.research.ResearchData;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native Java 21 port of {@code tools/strategy-research-v5-data.mjs}.
 *
 * <p>The class intentionally retains the Node module's public names.  Inputs
 * that correspond to JavaScript destructured option objects are represented
 * by {@link ObjectNode}.  Physical evidence is always reopened beneath an
 * explicit root, with no-link custody, byte/content hashes, and CAS/lock
 * checks at mutable checkpoint boundaries.</p>
 */
public final class StrategyResearchDataV5 {
    public static final Map<String, String> DATA_V5 = Map.ofEntries(
            Map.entry("plan", "strategy-v5-authoritative-data-plan/1"),
            Map.entry("acquisition", "strategy-v5-authoritative-acquisition/1"),
            Map.entry("hydration", "strategy-v5-opportunity-hydration/1"),
            Map.entry("featureSource", "strategy-v5-authoritative-feature-source/1"),
            Map.entry("sourceBundle", "strategy-v5-source-bundle/1"),
            Map.entry("artifacts", "strategy-v5-separated-artifacts/1"),
            Map.entry("metadata", "strategy-v5-metadata-receipt/1"),
            Map.entry("checkpoint", "strategy-v5-data-checkpoint/1"),
            Map.entry("datedCatalog", "strategy-v5-dated-futures-catalog/2"),
            Map.entry("promotedCoverage", "strategy-v5-promoted-coverage/1"));
    public static final List<String> DATA_V5_ASSETS = List.of(
            "btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
    public static final List<String> DATA_V5_STATUSES = List.of(
            "PUBLIC_OBSERVED", "USER_BOUND", "CONSERVATIVE_MODEL", "UNAVAILABLE");
    public static final long FOUR_HOURS = 4L * 60 * 60 * 1_000;
    public static final long ONE_MINUTE = 60_000L;
    public static final long EIGHT_HOURS = 8L * 60 * 60 * 1_000;
    public static final String METRICS_PIT_VINTAGE_BLOCK_REASON =
            "METRICS_PIT_VINTAGE_UNAVAILABLE:LATEST_RETRIEVAL_NOT_HISTORICAL_VINTAGE";
    /** SHA-256 of the exact Node producer bytes at this migration baseline. */
    public static final String DATA_V5_PRODUCER_CODE_SHA256 =
            "f35f511550f7861af3307b8e49c8c2ab7481ce49a3543aef23c4bb8fdd5f1967";
    public static final String DATA_V5_ADAPTER_CODE_SHA256 = PublicDataAdapters.ADAPTER_CODE_SHA256;
    public static final String DATA_V5_COVERAGE_RULES_SHA256 =
            "5adcfb1070f80467d9ea5d6437d68b8607957f752262e57a16cfc4a99f6fe8fb";
    public static final Map<String, String> DATA_V5_PRODUCER_COMMANDS = Map.of(
            "FEATURE", "strategy-v5-feature-producer/1",
            "LABEL", "strategy-v5-label-producer/1",
            "EXECUTION", "strategy-v5-execution-producer/1",
            "MARK", "strategy-v5-mark-producer/1");

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = JsonHashes.mapper();
    private static final ResearchSchemaRegistry SCHEMAS = ResearchSchemaRegistry.defaultRegistry();
    private static final Pattern SHA_256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern PREDICTOR_ID = Pattern.compile("^[a-z][a-z0-9_]{0,127}$");
    private static final Pattern FORBIDDEN_PREDICTOR = Pattern.compile(
            "(^|_)(future|forward|fwd|target|outcome|label|pnl|profit|exit|resolution|realized|unrealized)(_|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTCOME_PROVENANCE = Pattern.compile(
            "(^|[_-])(label|outcome|pnl|profit|realized|unrealized|future|forward|target)([_-]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_ASSET = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,31}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMEFRAME = Pattern.compile("^(\\d+)(m|h|d)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");
    private static final DateTimeFormatter JS_ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> PREDICTOR_RECIPE_KINDS = Set.of(
            "FIELD", "RETURN", "SMA", "STDDEV_ZSCORE", "RSI");
    private static final String PREDICTOR_RECIPE_MODULE = "builtin-pit-transform/1";
    private static final Set<String> PRECOMPUTED_EXECUTION = Set.of(
            "net_r", "gross_r", "fee_r", "slippage_r", "funding_debit_r", "net_pnl",
            "gross_pnl", "net_pnl_usd", "gross_pnl_usd", "cost_r");
    private static final Set<String> RAW_ROLE_DERIVED_FIELDS = Set.of(
            "signal_id", "episode_id", "signal_eligible", "entry_time", "exit_time",
            "resolution_time", "resolution_ceiling_time", "outcome_time", "outcome", "outcome_path",
            "return", "return_r", "net_r", "gross_r", "fee_r", "slippage_r", "funding_debit_r",
            "net_pnl", "gross_pnl", "net_pnl_usd", "gross_pnl_usd", "cost_r", "risk_amount_usd",
            "realized_pnl", "unrealized_pnl", "profit", "loss");
    private static final Set<String> FORBIDDEN_EXECUTION_INPUT_FIELDS = Set.of(
            "direction", "quantity", "risk_amount_usd", "risk_contract", "lifecycle_timeframe",
            "max_lifecycle_ms", "max_lifecycle_bars", "capacity_inputs", "margin_mode", "tier_id",
            "leverage", "collateral", "collateral_usd", "entry_policy",
            "decision_timestamp_convention", "decision_timeframe");
    private static final Set<String> RAW_MARK_FIELDS = Set.of(
            "asset", "venue", "instrument", "symbol", "series_role", "series_id", "cadence_ms",
            "expected_step_ms", "event_time", "open_time", "availability_time", "available_at",
            "close_time", "price", "open", "high", "low", "close", "mark_open", "mark_high",
            "mark_low", "mark_close", "volume");
    private static final Set<String> RAW_BAR_FIELDS = Set.of(
            "asset", "venue", "instrument", "symbol", "timeframe", "interval", "event_time",
            "open_time", "close_time", "availability_time", "available_at", "open", "high", "low",
            "close", "volume", "quote_volume", "trades", "first_trade_id", "last_trade_id", "is_closed",
            "series_role", "series_id", "cadence_ms");
    private static final List<Long> FUNDING_CADENCES = List.of(2L * 60 * 60 * 1_000,
            FOUR_HOURS, EIGHT_HOURS);
    private static final long FUNDING_GAP_TOLERANCE = 60_000L;
    private static final List<String> ACQUISITION_SERIES_IDENTITY_FIELDS = List.of(
            "asset", "venue", "instrument", "symbol", "interval", "series_type");

    private StrategyResearchDataV5() {}

    /* ------------------------------------------------------------------ */
    /* Canonical JSON and public contract primitives                       */
    /* ------------------------------------------------------------------ */

    public static String stable(JsonNode value) {
        return JsonHashes.canonicalString(value == null ? NullNode.instance : value);
    }

    public static String hash(JsonNode value) {
        return JsonHashes.canonicalSha256(value == null ? NullNode.instance : value);
    }

    public static String hash(String value) {
        return JsonHashes.sha256(value);
    }

    public static String hash(byte[] value) {
        return JsonHashes.sha256(value);
    }

    public static String ownHash(JsonNode value) {
        return ownHash(value, "content_sha256");
    }

    public static String ownHash(JsonNode value, String field) {
        if (value == null) return hash(NullNode.instance);
        JsonNode copy = value.deepCopy();
        if (copy instanceof ObjectNode object) object.remove(field);
        return hash(copy);
    }

    public static ObjectNode withHash(ObjectNode value) {
        return withHash(value, "content_sha256");
    }

    public static ObjectNode withHash(ObjectNode value, String field) {
        if (value == null) throw failure("hashable contract is required");
        ObjectNode copy = value.deepCopy();
        if (DATA_V5.get("acquisition").equals(text(copy, "schema")) && copy.path("captures").isArray()) {
            populateAcquisitionCompletionDefaults(copy);
        }
        copy.remove(field);
        copy.put(field, ownHash(copy, field));
        SCHEMAS.validateContractSchema(copy);
        return copy;
    }

    private static void populateAcquisitionCompletionDefaults(ObjectNode value) {
        List<ObjectNode> captures = objects(value.path("captures"));
        List<ObjectNode> required = captures.stream().filter(capture -> !capture.has("required")
                || capture.path("required").asBoolean()).toList();
        List<ObjectNode> optional = captures.stream().filter(capture -> capture.has("required")
                && !capture.path("required").asBoolean()).toList();
        boolean base = !required.isEmpty() && required.stream().allMatch(StrategyResearchDataV5::captureComplete);
        boolean declared = !captures.isEmpty() && captures.stream().allMatch(StrategyResearchDataV5::captureComplete);
        putIfMissing(value, "base_complete", base);
        putIfMissing(value, "declared_complete", declared);
        putIfMissing(value, "full_plan_complete", declared);
        putIfMissing(value, "completion_scope", declared ? "ALL_DECLARED" : base ? "BASE_ONLY" : "NONE");
        putIfMissing(value, "required_series_count", required.size());
        putIfMissing(value, "required_complete_count", required.stream().filter(StrategyResearchDataV5::captureComplete).count());
        putIfMissing(value, "optional_series_count", optional.size());
        putIfMissing(value, "optional_complete_count", optional.stream().filter(StrategyResearchDataV5::captureComplete).count());
        putIfMissing(value, "optional_complete", optional.stream().allMatch(StrategyResearchDataV5::captureComplete));
        if (!value.has("unavailable_required")) value.set("unavailable_required", strings(required.stream()
                .filter(capture -> !captureComplete(capture)).map(StrategyResearchDataV5::completionIdentity).sorted().toList()));
        if (!value.has("unavailable_optional")) value.set("unavailable_optional", strings(optional.stream()
                .filter(capture -> !captureComplete(capture)).map(StrategyResearchDataV5::completionIdentity).sorted().toList()));
    }

    private static String completionIdentity(ObjectNode series) {
        return String.join("|", List.of("asset", "instrument", "symbol", "interval", "series_type").stream()
                .map(field -> textOr(series.get(field), "").toLowerCase(Locale.ROOT)).toList());
    }

    /* ------------------------------------------------------------------ */
    /* Predictor registry and PIT feature production                       */
    /* ------------------------------------------------------------------ */

    public static ObjectNode makePredictorRegistry(ObjectNode options) {
        JsonNode predictors = field(options, "predictors");
        if (!predictors.isArray()) throw failure("predictor registry must be an array");
        List<ObjectNode> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : predictors) {
            if (!node.isObject()) throw failure("predictor registry must contain objects");
            ObjectNode predictor = ((ObjectNode) node).deepCopy();
            String id = text(predictor, "id");
            if (!PREDICTOR_ID.matcher(id).matches() || FORBIDDEN_PREDICTOR.matcher(id).find()) {
                throw failure("predictor ID is not a permitted registry identifier: " + id);
            }
            if (!ids.add(id)) throw failure("predictor registry must contain unique predictors");
            String scalar = text(predictor, "scalar_type");
            if (!Set.of("number", "integer", "boolean").contains(scalar)) {
                throw failure("predictor " + id + " scalar_type is invalid");
            }
            if (text(predictor, "source_field").isEmpty() || text(predictor, "source_family").isEmpty()
                    || text(predictor, "availability_derivation").isEmpty()
                    || !"PREDICTOR".equals(text(predictor, "pit_role"))) {
                throw failure("predictor " + id + " registry provenance is incomplete");
            }
            String provenance = text(predictor, "source_field") + " " + text(predictor, "source_family");
            if (OUTCOME_PROVENANCE.matcher(provenance).find()) {
                throw failure("predictor " + id + " has label/outcome provenance");
            }
            long lookback = integer(predictor.get("lookback_ms"), id + " lookback");
            if (lookback < 0) throw failure("predictor " + id + " lookback is invalid");
            predictor.put("lookback_ms", lookback);
            if (predictor.has("source_timeframe")
                    && !Set.of("1h", "4h", "1d", "event").contains(text(predictor, "source_timeframe"))) {
                throw failure("predictor " + id + " source timeframe is invalid");
            }
            requireSha(text(predictor, "code_sha256"), id + ".code_sha256");
            requireSha(text(predictor, "config_sha256"), id + ".config_sha256");
            if (predictor.has("recipe")) predictor.set("recipe", normalizePredictorRecipe(predictor, id));
            rows.add(predictor);
        }
        if (rows.isEmpty()) throw failure("predictor registry must contain unique predictors");
        rows.sort(Comparator.comparing(row -> text(row, "id")));
        ObjectNode value = object().put("schema", "strategy-v5-predictor-registry/1")
                .put("version", 1).put("status", "FROZEN");
        value.set("predictors", array(rows));
        return withHash(value);
    }

    private static ObjectNode normalizePredictorRecipe(ObjectNode predictor, String id) {
        JsonNode raw = predictor.get("recipe");
        if (raw == null || !raw.isObject()) throw failure("predictor " + id + " recipe must be an object");
        ObjectNode recipe = ((ObjectNode) raw).deepCopy();
        String kind = text(recipe, "kind").toUpperCase(Locale.ROOT);
        if (!PREDICTOR_RECIPE_KINDS.contains(kind)) throw failure("predictor " + id + " recipe kind is unsupported");
        if (!PREDICTOR_RECIPE_MODULE.equals(text(recipe, "module"))) {
            throw failure("predictor " + id + " recipe module is not the registered PIT transform module");
        }
        String sourceField = text(recipe, "source_field");
        if (sourceField.isEmpty()) throw failure("predictor " + id + " recipe source_field is missing");
        if (OUTCOME_PROVENANCE.matcher(sourceField).find()) {
            throw failure("predictor " + id + " recipe source field has label/outcome provenance");
        }
        String sourceSeries = text(recipe, "source_series").trim();
        if (sourceSeries.isEmpty()) throw failure("predictor " + id + " recipe source_series is missing");
        long lookback = recipe.has("lookback_bars") ? integer(recipe.get("lookback_bars"), "lookback_bars")
                : "FIELD".equals(kind) ? 0 : Long.MIN_VALUE;
        long minimum = recipe.has("min_history") ? integer(recipe.get("min_history"), "min_history")
                : "FIELD".equals(kind) ? 1 : Set.of("RETURN", "RSI").contains(kind) ? lookback + 1 : lookback;
        if (lookback < 0 || minimum < 1 || (!"FIELD".equals(kind) && lookback < 1)
                || minimum > lookback + 1 || ("RSI".equals(kind) && minimum != lookback + 1)) {
            throw failure("predictor " + id + " recipe history bounds are invalid");
        }
        String window = text(recipe, "window_policy");
        String availability = text(recipe, "availability_policy");
        String scope = text(recipe, "series_scope");
        if (!"COMPLETED_OBSERVATIONS_ONLY".equals(window) || !"MAX_INPUT_AVAILABILITY".equals(availability)
                || !Set.of("SAME_ASSET_VENUE_INSTRUMENT_SYMBOL", "SAME_ASSET_FUNDING_SERIES",
                        "EXPLICIT_REFERENCE_SERIES").contains(scope)) {
            throw failure("predictor " + id + " recipe PIT policies are incomplete");
        }
        String current = textOr(recipe.get("current_observation_policy"), "INCLUDE_CURRENT_COMPLETED");
        if (!Set.of("INCLUDE_CURRENT_COMPLETED", "EXCLUDE_CURRENT_COMPLETED").contains(current)) {
            throw failure("predictor " + id + " recipe current observation policy is invalid");
        }
        long excluded = recipe.has("excluded_window_bars")
                ? integer(recipe.get("excluded_window_bars"), "excluded_window_bars") : 0;
        if (excluded < 0 || excluded > lookback + 1) {
            throw failure("predictor " + id + " recipe excluded window is invalid");
        }
        String rsi = textOr(recipe.get("rsi_method"), "WILDER_RSI").toUpperCase(Locale.ROOT);
        if ("RSI".equals(kind) && !"WILDER_RSI".equals(rsi)) {
            throw failure("predictor " + id + " RSI method is not the registered Wilder implementation");
        }
        List<String> requiredTypes = null;
        if (recipe.has("required_series_types")) {
            requiredTypes = uniqueSortedTexts(recipe.get("required_series_types"));
            if (requiredTypes.isEmpty() || requiredTypes.stream().anyMatch(value -> !Set.of(
                    "signal_bars", "mark_bars", "funding_events", "metrics_events").contains(value))
                    || requiredTypes.contains("funding_events") && requiredTypes.stream()
                    .anyMatch(value -> !Set.of("funding_events", "metrics_events").contains(value))) {
                throw failure("predictor " + id + " recipe required_series_types are invalid");
            }
        }
        boolean explicit = "EXPLICIT_REFERENCE_SERIES".equals(scope);
        boolean funding = "SAME_ASSET_FUNDING_SERIES".equals(scope);
        ObjectNode reference = explicit && recipe.path("reference_series").isObject()
                ? (ObjectNode) recipe.path("reference_series") : null;
        if (explicit) {
            if (reference == null || text(reference, "asset").isEmpty() || text(reference, "venue").isEmpty()
                    || text(reference, "instrument").isEmpty() || text(reference, "symbol").isEmpty()) {
                throw failure("predictor " + id + " explicit reference series is incomplete");
            }
            String referenceAsset = text(reference, "asset").toLowerCase(Locale.ROOT);
            if (!SAFE_ASSET.matcher(referenceAsset).matches()) {
                throw failure("predictor " + id + " explicit reference asset is invalid");
            }
            if (!DATA_V5_ASSETS.contains(referenceAsset) && !recipe.path("context_only").asBoolean(false)) {
                throw failure("predictor " + id + " non-crypto reference series must be context_only");
            }
            if (!"LATEST_AVAILABLE_NOT_AFTER_DECISION".equals(text(recipe, "asof_policy"))
                    || positiveInteger(recipe.get("max_staleness_ms"), false) < 1
                    || integerOr(recipe.get("lag_bars"), 0) < 0
                    || !Set.of("EXACT_EVENT", "LAST_AVAILABLE", "BAR_CLOSE")
                    .contains(text(recipe, "resample_policy"))) {
                throw failure("predictor " + id + " explicit reference timing contract is invalid");
            }
        }
        if (funding) {
            String family = text(predictor, "source_family").trim().toLowerCase(Locale.ROOT);
            if (!"funding_rate".equals(sourceField.toLowerCase(Locale.ROOT))
                    || !Set.of("funding", "funding_events").contains(family)
                    || !"event".equals(text(predictor, "source_timeframe").toLowerCase(Locale.ROOT))
                    || !"funding_events".equals(sourceSeries.toLowerCase(Locale.ROOT))
                    || !Objects.equals(requiredTypes, List.of("funding_events"))) {
                throw failure("predictor " + id + " funding reference source contract is invalid");
            }
            if (!"LATEST_AVAILABLE_STRICTLY_BEFORE_DECISION".equals(text(recipe, "asof_policy"))
                    || !"LAST_AVAILABLE".equals(text(recipe, "resample_policy"))
                    || !recipe.path("context_only").asBoolean(false)
                    || positiveInteger(recipe.get("max_staleness_ms"), false) < 1
                    || integerOr(recipe.get("lag_bars"), 0) < 0) {
                throw failure("predictor " + id + " funding reference timing contract is invalid");
            }
        }
        if (!text(recipe, "module_code_sha256").equals(text(predictor, "code_sha256"))
                || !text(recipe, "module_config_sha256").equals(text(predictor, "config_sha256"))) {
            throw failure("predictor " + id + " recipe module hashes are not bound to code/config hashes");
        }
        ObjectNode normalized = object().put("module", PREDICTOR_RECIPE_MODULE).put("kind", kind)
                .put("source_field", sourceField).put("source_series", sourceSeries)
                .put("lookback_bars", lookback).put("min_history", minimum)
                .put("window_policy", window).put("availability_policy", availability)
                .put("series_scope", scope);
        if (explicit) {
            ObjectNode ref = object().put("asset", text(reference, "asset").toLowerCase(Locale.ROOT))
                    .put("venue", text(reference, "venue").toUpperCase(Locale.ROOT))
                    .put("instrument", text(reference, "instrument").toUpperCase(Locale.ROOT))
                    .put("symbol", text(reference, "symbol").toUpperCase(Locale.ROOT));
            copyTextIfPresent(reference, ref, "series_id");
            copyTextIfPresent(reference, ref, "series_type");
            normalized.set("reference_series", ref);
        }
        if (explicit || funding) {
            normalized.put("asof_policy", funding ? "LATEST_AVAILABLE_STRICTLY_BEFORE_DECISION"
                    : "LATEST_AVAILABLE_NOT_AFTER_DECISION");
            normalized.put("max_staleness_ms", integer(recipe.get("max_staleness_ms"), "max_staleness_ms"));
            normalized.put("lag_bars", integerOr(recipe.get("lag_bars"), 0));
            normalized.put("resample_policy", text(recipe, "resample_policy").toUpperCase(Locale.ROOT));
            normalized.put("context_only", recipe.path("context_only").asBoolean(false));
        }
        if (requiredTypes != null) normalized.set("required_series_types", strings(requiredTypes));
        if ("RSI".equals(kind)) normalized.put("rsi_method", rsi);
        normalized.put("current_observation_policy", current).put("excluded_window_bars", excluded)
                .put("module_code_sha256", text(recipe, "module_code_sha256"))
                .put("module_config_sha256", text(recipe, "module_config_sha256"));
        return normalized;
    }

    private static LinkedHashMap<String, ObjectNode> validatePredictorRegistry(JsonNode registry) {
        assertOwnHash(registry, "strategy-v5-predictor-registry/1", "predictor registry");
        if (!"FROZEN".equals(text(registry, "status"))) throw failure("predictor registry must be frozen");
        LinkedHashMap<String, ObjectNode> output = new LinkedHashMap<>();
        for (JsonNode node : registry.path("predictors")) {
            ObjectNode predictor = (ObjectNode) node.deepCopy();
            String id = text(predictor, "id");
            if (output.containsKey(id)) throw failure("predictor registry ID is duplicated: " + id);
            if (predictor.has("recipe")) predictor.set("recipe", normalizePredictorRecipe(predictor, id));
            output.put(id, predictor);
        }
        if (output.isEmpty()) throw failure("predictor registry is empty");
        return output;
    }

    public static ObjectNode derivePrecommitTradeScopeV5(ObjectNode precommit) {
        return derivePrecommitTradeScopeV5(precommit, object());
    }

    public static ObjectNode derivePrecommitTradeScopeV5(ObjectNode precommit, ObjectNode options) {
        if (precommit == null) throw failure("trade scope requires a frozen precommit");
        JsonNode contract = precommit.get("tradable_instrument_contract");
        if (contract == null || !contract.isObject() || !"CRYPTO_ONLY".equals(text(contract, "universe"))
                || !contract.path("instruments").isArray() || contract.path("instruments").isEmpty()) {
            throw failure("precommit must freeze at least one crypto instrument declaration");
        }
        Set<String> instruments = new LinkedHashSet<>();
        Set<String> embedded = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode declaration : contract.path("instruments")) {
            instruments.add(normalizedTradeInstrument(declaration, "precommit tradable instrument " + index++));
            if (declaration.isObject() && declaration.has("asset")) {
                embedded.add(text(declaration, "asset").trim().toLowerCase(Locale.ROOT));
            }
        }
        if (instruments.size() != 1) {
            throw failure("precommit must freeze exactly one crypto instrument type per strategy version");
        }
        String instrument = instruments.iterator().next();
        if ("BINANCE_USDM_DATED_FUTURE".equals(instrument)) {
            throw failure("dated-future research requires a frozen contract-selection rule and is not supported by the single-instrument v5 episode model");
        }
        if (embedded.stream().anyMatch(value -> !DATA_V5_ASSETS.contains(value))) {
            throw failure("precommit instrument declarations contain an unsupported crypto asset");
        }
        List<String> declared = uniqueSortedTexts(precommit.path("trade_assets"));
        List<String> embeddedAssets = embedded.stream().sorted().toList();
        if (!declared.isEmpty() && !embeddedAssets.isEmpty() && !declared.equals(embeddedAssets)) {
            throw failure("precommit trade_assets differ from embedded instrument assets");
        }
        List<String> tradeAssets = declared.isEmpty() ? embeddedAssets : declared;
        if (tradeAssets.isEmpty() || tradeAssets.stream().anyMatch(value -> !DATA_V5_ASSETS.contains(value))) {
            throw failure("precommit must freeze a non-empty supported crypto trade_assets scope");
        }
        JsonNode candidate = field(options, "candidateTemplate");
        if (!candidate.isMissingNode() && !candidate.isNull()) {
            if (!candidate.has("instrument_type")) throw failure("evaluator candidate_template must freeze instrument_type");
            if (!instrument.equals(normalizedTradeInstrument(candidate.get("instrument_type"),
                    "evaluator candidate instrument_type"))) {
                throw failure("evaluator candidate instrument_type differs from the precommit trade instrument");
            }
        }
        ObjectNode result = object().put("instrument", instrument);
        result.set("trade_assets", strings(tradeAssets));
        return result;
    }

    private static String normalizedTradeInstrument(JsonNode value, String label) {
        JsonNode raw = value;
        if (raw != null && raw.isObject()) {
            if (raw.has("$gene")) throw failure(label + " cannot be a structural gene; freeze one instrument type per strategy version");
            raw = first(raw, "instrument_type", "type", "instrument");
        }
        String normalized = textValue(raw).trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "spot", "binance_spot" -> "BINANCE_SPOT";
            case "perpetual", "perp", "usdm_perpetual", "binance_usdm_perpetual" -> "BINANCE_USDM_PERPETUAL";
            case "dated_future", "dated_futures", "binance_usdm_dated_future" -> "BINANCE_USDM_DATED_FUTURE";
            default -> throw failure(label + " is not a supported fixed crypto instrument type");
        };
    }

    /* ------------------------------------------------------------------ */
    /* Physical source receipts, confinement, and lineage                  */
    /* ------------------------------------------------------------------ */

    public static ObjectNode verifyNormalizedReceipt(Path root, ObjectNode summary) {
        return verifyNormalizedReceipt(root, summary, "normalized source receipt");
    }

    public static ObjectNode verifyNormalizedReceipt(Path root, ObjectNode summary, String label) {
        if (summary == null || !"strategy-v5-source-receipt/1".equals(text(summary, "schema"))
                || text(summary, "path").isEmpty()
                || !isSha(textOr(first(summary, "sha256", "content_sha256"), ""))) {
            throw failure(label + " reference is incomplete");
        }
        Path path = verifiedRegularPath(root, text(summary, "path"), label);
        ObjectNode receipt = readObject(path, label + " JSON");
        assertOwnHash(receipt, "strategy-v5-source-receipt/1", label);
        String content = textOr(first(summary, "content_sha256", "sha256"), "");
        if (!text(receipt, "content_sha256").equals(content)
                || summary.has("sha256") && !text(summary, "sha256").equals(text(receipt, "content_sha256"))) {
            throw failure(label + " content hash binding is invalid: " + text(summary, "path"));
        }
        if (summary.has("status") && receipt.has("status")
                && !text(summary, "status").equals(text(receipt, "status"))) {
            throw failure(label + " status binding is invalid: " + text(summary, "path"));
        }
        List<ObjectNode> raws = objects(receipt.path("raw_receipts"));
        for (ObjectNode raw : raws) verifyRawReceipt(root, raw, label + " raw response");
        List<String> retained = raws.stream().map(raw -> text(raw, "byte_sha256")).sorted().toList();
        List<String> summaryBytes = hashInventory(summary.get("byte_sha256"));
        List<String> declaredBytes = hashInventory(receipt.get("source_byte_sha256"));
        List<String> responses = hashInventory(receipt.get("response_sha256"));
        if (summary.has("raw_count") && (!summary.get("raw_count").canConvertToInt()
                || summary.path("raw_count").asInt() != raws.size())) {
            throw failure(label + " raw receipt count is not bound: " + text(summary, "path"));
        }
        if (!summaryBytes.isEmpty() && !summaryBytes.equals(retained)) {
            throw failure(label + " summary/raw byte inventory is not bound: " + text(summary, "path"));
        }
        if (!declaredBytes.isEmpty() && !declaredBytes.equals(retained)) {
            throw failure(label + " raw byte inventory is not bound: " + text(summary, "path"));
        }
        if (!responses.isEmpty() && !responses.equals(retained)) {
            throw failure(label + " response-byte inventory is not bound: " + text(summary, "path"));
        }
        List<ObjectNode> pages = objects(receipt.path("pagination"));
        List<String> pageResponses = pages.stream().map(page -> text(page, "response_sha256"))
                .filter(value -> !value.isEmpty()).sorted().toList();
        if (!pageResponses.isEmpty() && !pageResponses.equals(retained)) {
            throw failure(label + " page/response inventory is not bound: " + text(summary, "path"));
        }
        for (ObjectNode page : pages) {
            String digest = text(page, "response_sha256");
            if (digest.isEmpty()) continue;
            ObjectNode raw = raws.stream().filter(candidate -> digest.equals(text(candidate, "byte_sha256")))
                    .findFirst().orElseThrow(() -> failure(label + " page response has no retained raw mapping: "
                            + text(summary, "path")));
            JsonNode request = raw.path("request");
            for (String name : List.of("endpoint", "interval")) {
                if (page.has(name) && request.has(name) && !text(page, name).equals(text(request, name))) {
                    throw failure(label + " page " + name + "/raw response mapping is invalid: "
                            + text(summary, "path"));
                }
            }
            if (page.has("symbol") && request.has("symbol")
                    && !text(page, "symbol").equalsIgnoreCase(text(request, "symbol"))) {
                throw failure(label + " page symbol/raw response mapping is invalid: " + text(summary, "path"));
            }
        }
        return receipt;
    }

    private static boolean verifyRawReceipt(Path root, ObjectNode raw, String label) {
        assertOwnHash(raw, "strategy-v5-source-receipt/1", label);
        if (!"RAW_BYTES".equals(text(raw, "format")) || !"RAW_IGNORED".equals(text(raw, "storage_role"))
                || raw.path("authoritative").asBoolean(true)) {
            throw failure(label + " storage metadata is invalid");
        }
        byte[] bytes = readPhysical(root, text(raw, "path"), label);
        if (bytes.length != raw.path("bytes").asLong(-1) || !hash(bytes).equals(text(raw, "byte_sha256"))) {
            throw failure(label + " bytes are missing or tampered: " + text(raw, "path"));
        }
        if (raw.path("request").has("response_sha256")
                && !text(raw.path("request"), "response_sha256").equals(text(raw, "byte_sha256"))) {
            throw failure(label + " request/byte response mapping is invalid: " + text(raw, "path"));
        }
        return true;
    }

    public static ObjectNode verifyAuthoritativeSourceChain(ObjectNode options) {
        Path root = requiredPath(options, "root");
        ObjectNode reference = requiredObject(options, "reference");
        String expected = text(options, "expectedContentSha256");
        String plan = requireSha(text(options, "planSha256"), "plan SHA");
        String label = textOr(options.get("label"), "source bundle");
        return verifyAuthoritativeSourceChain0(root, reference, emptyToNull(expected), plan, label,
                new HashSet<>());
    }

    private static ObjectNode verifyAuthoritativeSourceChain0(Path root, ObjectNode reference,
            String expected, String planSha, String label, Set<String> seen) {
        ObjectNode value = verifyPhysicalJsonReference(root, reference, expected, label);
        if (!seen.add(text(value, "content_sha256"))) throw failure(label + " contains a cyclic upstream source chain");
        ObjectNode result = object();
        if (DATA_V5.get("acquisition").equals(text(value, "schema"))) {
            if (!planSha.equals(text(value, "plan_sha256"))) throw failure(label + " is bound to a different plan");
            ObjectNode verify = object().set("manifest", value);
            verify.put("root", root.toString()).put("planSha256", planSha).put("requireComplete", true);
            verifyAuthoritativeStaging(verify);
            result.putNull("bundle"); result.set("acquisition", value); result.putNull("hydration");
            return result;
        }
        if (!DATA_V5.get("sourceBundle").equals(text(value, "schema"))) {
            throw failure(label + " must be a verified source bundle terminating in complete acquisition and frozen opportunity hydration; derived stage artifacts cannot be a role-production source");
        }
        if (!planSha.equals(text(value, "plan_sha256"))) throw failure(label + " is bound to a different plan");
        ObjectNode acquisition = verifyPhysicalJsonReference(root, requiredObject(value, "acquisition_reference"),
                text(value, "acquisition_sha256"), label + " acquisition manifest");
        ObjectNode hydration = verifyPhysicalJsonReference(root, requiredObject(value, "hydration_reference"),
                text(value, "hydration_sha256"), label + " opportunity hydration manifest");
        if (!DATA_V5.get("acquisition").equals(text(acquisition, "schema"))
                || !DATA_V5.get("hydration").equals(text(hydration, "schema"))) {
            throw failure(label + " must bind an acquisition and opportunity hydration manifest");
        }
        if (!planSha.equals(text(acquisition, "plan_sha256")) || !planSha.equals(text(hydration, "plan_sha256"))) {
            throw failure(label + " child manifests are bound to a different plan");
        }
        if (!text(hydration, "envelope_sha256").equals(text(value, "envelope_sha256"))
                || !text(hydration, "candidate_set_sha256").equals(text(value, "candidate_set_sha256"))) {
            throw failure(label + " opportunity envelope/candidate-set binding is inconsistent");
        }
        ObjectNode acquireVerify = object().set("manifest", acquisition);
        acquireVerify.put("root", root.toString()).put("planSha256", planSha).put("requireComplete", true);
        verifyAuthoritativeStaging(acquireVerify);
        ObjectNode hydrateVerify = object().set("manifest", hydration);
        hydrateVerify.put("root", root.toString()).put("planSha256", planSha)
                .put("envelopeSha256", text(value, "envelope_sha256"))
                .put("candidateSetSha256", text(value, "candidate_set_sha256")).put("requireComplete", true);
        verifyAuthoritativeStaging(hydrateVerify);
        ObjectNode rootOptions = object(); rootOptions.set("acquisition", acquisition); rootOptions.set("hydration", hydration);
        rootOptions.put("root", root.toString()).put("envelopeSha256", text(value, "envelope_sha256"))
                .put("candidateSetSha256", text(value, "candidate_set_sha256"));
        if (!computeSourceBundleDatasetRootSha256(rootOptions).equals(text(value, "dataset_root_sha256"))) {
            throw failure(label + " physical dataset root is invalid");
        }
        result.set("bundle", value); result.set("acquisition", acquisition); result.set("hydration", hydration);
        return result;
    }

    public static ObjectNode emitRoleDerivationReceipt(ObjectNode options) {
        if (!options.path("fixtureOnly").asBoolean(false)) {
            throw failure("emitRoleDerivationReceipt is FIXTURE_ONLY; use produceAuthoritativeRoleArtifacts for authoritative role production");
        }
        return emitRoleReceipt(options, "FIXTURE_ONLY");
    }

    private static ObjectNode emitRoleReceipt(ObjectNode options, String provenance) {
        Path root = requiredPath(options, "root");
        String role = requireRole(text(options, "role"));
        String artifact = requireSha(text(options, "artifactSha256"), role + ".artifact_sha256");
        String sourceManifest = requireSha(text(options, "sourceManifestSha256"), role + ".source_manifest_sha256");
        String sourceRoot = requireSha(text(options, "sourceDatasetRootSha256"), role + ".source_dataset_root_sha256");
        String transform = requireSha(text(options, "transformationCodeSha256"), "transformation_code_sha256");
        String label = requireSha(text(options, "labelCodeSha256"), "label_code_sha256");
        String execution = requireSha(text(options, "executionCodeSha256"), "execution_code_sha256");
        String configHash = requireSha(text(options, "configSha256"), "config_sha256");
        String precommitHash = requireSha(text(options, "precommitSha256"), "precommit_sha256");
        String envelopeHash = requireSha(text(options, "envelopeSha256"), "envelope_sha256");
        ObjectNode plan = requiredObject(options, "plan");
        ObjectNode registry = requiredObject(options, "predictorRegistry");
        ObjectNode precommit = requiredObject(options, "precommit");
        ObjectNode envelope = requiredObject(options, "envelope");
        ObjectNode config = requiredObject(options, "config");
        assertOwnHash(plan, DATA_V5.get("plan"), role + " role producer plan");
        assertOwnHash(registry, "strategy-v5-predictor-registry/1", role + " role producer registry");
        assertHashBinding(precommit, precommitHash, role + " precommit");
        assertHashBinding(envelope, envelopeHash, role + " opportunity envelope");
        assertHashBinding(config, configHash, role + " configuration");
        String command = DATA_V5_PRODUCER_COMMANDS.get(role);
        if (options.has("producerCommand") && !command.equals(text(options, "producerCommand"))) {
            throw failure(role + " role derivation receipt producer command is not registered");
        }
        byte[] producerBytes = javaProducerBytes();
        ObjectNode producerReference = persistPhysicalBytes(root,
                "lineage/producer-code/" + role.toLowerCase(Locale.ROOT) + "-" + javaProducerCodeSha256() + ".class",
                producerBytes, role + " producer code");
        String codeSha = switch (role) {
            case "LABEL" -> label;
            case "EXECUTION" -> execution;
            default -> transform;
        };
        ObjectNode codeReference;
        if (options.path("codeReference").isObject()) codeReference = (ObjectNode) options.path("codeReference").deepCopy();
        else {
            if (!hash(producerBytes).equals(codeSha)) {
                throw failure(role + " authoritative producer code bytes do not match the declared transformation/label/execution code hash");
            }
            codeReference = persistPhysicalBytes(root,
                    "lineage/role-code/" + role.toLowerCase(Locale.ROOT) + "-" + codeSha + ".class",
                    producerBytes, role + " derivation code");
        }
        ObjectNode value = object().put("schema", "strategy-v5-role-derivation-receipt/1")
                .put("version", 1).put("role", role).put("provenance_mode", provenance)
                .put("producer_command", command).put("producer_code_sha256", javaProducerCodeSha256());
        value.set("producer_code_reference", producerReference);
        value.put("artifact_sha256", artifact).put("source_manifest_sha256", sourceManifest)
                .put("source_dataset_root_sha256", sourceRoot)
                .put("predictor_registry_sha256", text(registry, "content_sha256"))
                .put("code_sha256", codeSha);
        value.set("code_reference", codeReference);
        value.put("precommit_sha256", precommitHash).put("envelope_sha256", envelopeHash).put("config_sha256", configHash);
        value.set("plan_reference", persistPhysicalJsonInput(root, plan, text(plan, "content_sha256"), role + "-plan"));
        value.set("predictor_registry_reference", persistPhysicalJsonInput(root, registry,
                text(registry, "content_sha256"), role + "-predictor-registry"));
        value.set("precommit_reference", persistPhysicalJsonInput(root, precommit, precommitHash, role + "-precommit"));
        value.set("envelope_reference", persistPhysicalJsonInput(root, envelope, envelopeHash, role + "-envelope"));
        value.set("config_reference", persistPhysicalJsonInput(root, config, configHash, role + "-config"));
        ObjectNode receipt = withHash(value);
        byte[] bytes = prettyBytes(receipt);
        String relative = "lineage/role-receipts/" + role.toLowerCase(Locale.ROOT) + "-"
                + text(receipt, "content_sha256") + ".json";
        writeContentAddressed(root, relative, bytes, role + " derivation receipt");
        return object().put("path", relative).put("content_sha256", text(receipt, "content_sha256"))
                .put("byte_sha256", hash(bytes));
    }

    /* ------------------------------------------------------------------ */
    /* Funding event identity/cadence                                      */
    /* ------------------------------------------------------------------ */

    public static ArrayNode discoverFundingCadenceSegments(ObjectNode options) {
        List<ObjectNode> rows = objects(field(options, "rows"));
        List<Long> ordered = rows.stream().map(row -> time(first(row, "raw_event_time", "event_time")))
                .sorted().toList();
        if (ordered.isEmpty()) return array();
        long start = options.has("startAt") ? time(options.get("startAt")) : ordered.get(0);
        long end = options.has("endAt") ? time(options.get("endAt")) : ordered.get(ordered.size() - 1) + 1;
        if (end <= start) throw failure("funding cadence discovery bounds are invalid");
        List<Long> cadences = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            long gap = index > 0 ? ordered.get(index) - ordered.get(index - 1)
                    : ordered.size() > 1 ? ordered.get(1) - ordered.get(0) : EIGHT_HOURS;
            cadences.add(cadenceForGap(gap));
        }
        ArrayNode segments = array();
        int groupStart = 0;
        for (int index = 1; index <= ordered.size(); index++) {
            if (index < ordered.size() && Objects.equals(cadences.get(index), cadences.get(groupStart))) continue;
            long from = groupStart == 0 ? start : ordered.get(groupStart);
            long to = index < ordered.size() ? ordered.get(index) : end;
            long cadence = cadences.get(groupStart);
            if (to <= from || cadence <= 0) throw failure("funding cadence discovery produced an invalid segment");
            long origin = Math.round((double) ordered.get(groupStart) / cadence) * cadence;
            segments.addObject().put("effective_from", iso(from)).put("effective_to", iso(to))
                    .put("cadence_ms", cadence).put("origin_at", iso(origin))
                    .put("discovery", "OBSERVED_EVENT_GAPS");
            groupStart = index;
        }
        return segments;
    }

    private static long cadenceForGap(long gap) {
        if (gap <= 0) throw failure("unsupported funding cadence gap " + gap + "ms; an internal settlement may be missing");
        long nearest = FUNDING_CADENCES.stream().min(Comparator.comparingLong(value -> Math.abs(value - gap)))
                .orElse(EIGHT_HOURS);
        if (Math.abs(nearest - gap) > FUNDING_GAP_TOLERANCE) {
            throw failure("unsupported funding cadence gap " + gap + "ms; an internal settlement may be missing");
        }
        return nearest;
    }

    public static ObjectNode canonicalizeFundingRows(ObjectNode options) {
        List<ObjectNode> rows = objects(field(options, "rows"));
        ObjectNode series = requiredObject(options, "series");
        if (!"funding_events".equals(text(series, "series_type"))) {
            throw failure("funding canonicalization requires a funding series");
        }
        long tolerance = integerOr(series.get("slot_tolerance_ms"), 60_000);
        if (tolerance < 0) throw failure("funding slot tolerance must be a non-negative integer");
        if (series.path("event_sequence_mode").asBoolean(false)) {
            return canonicalizeEventSequenceFunding(rows, series, tolerance);
        }
        List<Long> slots = expectedFundingSlots(series);
        Map<Long, ObjectNode> bySlot = new HashMap<>();
        Set<String> eventIds = new HashSet<>();
        for (ObjectNode input : rows) {
            long raw = time(first(input, "raw_event_time", "event_time"));
            ObjectNode segment = segmentAt(series, raw);
            long origin = time(segment.get("origin_at"));
            long cadence = segment.path("cadence_ms").asLong();
            long slot = origin + Math.round((double) (raw - origin) / cadence) * cadence;
            if (Math.abs(raw - slot) > tolerance) {
                throw failure("funding event " + textOr(input.get("event_id"), "?")
                        + " exceeds settlement-slot tolerance");
            }
            String eventId = text(input, "event_id");
            if (eventId.isEmpty() || !eventIds.add(eventId)) {
                throw failure("funding event identity is missing or duplicated: " + eventId);
            }
            if (!slots.contains(slot)) continue;
            if (bySlot.containsKey(slot)) throw failure("multiple funding events map to settlement slot " + iso(slot));
            double rate = finiteNumber(first(input, "funding_rate", "rate"), "funding event " + eventId + " has no finite rate");
            ObjectNode row = input.deepCopy();
            row.put("raw_event_time", raw).put("event_time", raw).put("settlement_slot", iso(slot))
                    .put("cadence_ms", cadence).put("funding_rate", rate).put("event_id", eventId)
                    .put("availability_time", input.has("availability_time")
                            ? time(input.get("availability_time")) : raw);
            bySlot.put(slot, row);
        }
        List<String> missing = slots.stream().filter(slot -> !bySlot.containsKey(slot)).map(StrategyResearchDataV5::iso).toList();
        List<ObjectNode> canonical = bySlot.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue).toList();
        ObjectNode coverage = object().put("complete", missing.isEmpty() && canonical.size() == slots.size())
                .put("expected_slots", slots.size()).put("observed_events", canonical.size())
                .put("slot_tolerance_ms", tolerance);
        coverage.set("missing_slots", strings(missing));
        coverage.set("cadence_segments", validateCadenceSegments(series));
        ObjectNode result = object(); result.set("rows", array(canonical)); result.set("coverage", coverage); return result;
    }

    private static ObjectNode canonicalizeEventSequenceFunding(List<ObjectNode> inputs, ObjectNode series,
            long tolerance) {
        long start = time(series.get("start_at"));
        long end = time(series.get("end_at"));
        ObjectNode cadenceOptions = object().set("rows", array(inputs));
        cadenceOptions.put("startAt", iso(start)).put("endAt", iso(series.has("availability_cutoff_at")
                ? time(series.get("availability_cutoff_at")) : end));
        ArrayNode discovered = discoverFundingCadenceSegments(cadenceOptions);
        List<ObjectNode> segments = objects(discovered);
        Set<String> eventIds = new HashSet<>();
        Set<Long> settlementSlots = new HashSet<>();
        List<ObjectNode> canonical = new ArrayList<>();
        List<ObjectNode> ordered = new ArrayList<>(inputs);
        ordered.sort(Comparator.comparingLong(row -> time(first(row, "raw_event_time", "event_time"))));
        for (ObjectNode input : ordered) {
            long raw = time(first(input, "raw_event_time", "event_time"));
            if (raw < start - tolerance || raw > end + tolerance) continue;
            String id = text(input, "event_id");
            if (id.isEmpty() || !eventIds.add(id)) throw failure("funding event identity is missing or duplicated: " + id);
            double rate = finiteNumber(first(input, "funding_rate", "rate"), "funding event " + id + " has no finite rate");
            ObjectNode segment = segments.stream().filter(value -> raw >= time(value.get("effective_from")) - tolerance
                    && (value == segments.get(segments.size() - 1) ? raw <= time(value.get("effective_to")) + tolerance
                    : raw < time(value.get("effective_to")))).findFirst()
                    .orElse(segments.isEmpty() ? null : segments.get(segments.size() - 1));
            if (segment == null) throw failure("funding timestamp " + iso(raw) + " is outside declared cadence segments");
            long cadence = segment.path("cadence_ms").asLong();
            long origin = time(segment.get("origin_at"));
            long slot = origin + Math.round((double) (raw - origin) / cadence) * cadence;
            if (Math.abs(raw - slot) > tolerance) throw failure("funding event " + id + " exceeds observed settlement cadence tolerance");
            if (!settlementSlots.add(slot)) throw failure("multiple funding events map to settlement slot " + iso(slot));
            ObjectNode row = input.deepCopy();
            row.put("raw_event_time", raw).put("event_time", raw).put("settlement_slot", iso(slot))
                    .put("cadence_ms", cadence).put("funding_rate", rate).put("event_id", id)
                    .put("availability_time", input.has("availability_time") ? time(input.get("availability_time")) : raw);
            canonical.add(row);
        }
        canonical.sort(Comparator.comparingLong(row -> time(row.get("settlement_slot"))));
        long first = canonical.isEmpty() ? Long.MIN_VALUE : time(canonical.get(0).get("raw_event_time"));
        long last = canonical.isEmpty() ? Long.MIN_VALUE : time(canonical.get(canonical.size() - 1).get("raw_event_time"));
        long maxCadence = EIGHT_HOURS;
        boolean boundaries = !canonical.isEmpty() && first <= start + maxCadence + tolerance
                && last >= end - maxCadence - tolerance;
        boolean sourceComplete = series.path("require_source_coverage").asBoolean(false)
                ? series.path("source_coverage_complete").asBoolean(false)
                : !series.has("source_coverage_complete") || series.path("source_coverage_complete").asBoolean();
        boolean complete = canonical.size() >= 2 && sourceComplete && boundaries;
        ObjectNode coverage = object().put("complete", complete).put("coverage_mode", "EVENT_SEQUENCE")
                .putNull("expected_slots").put("observed_events", canonical.size())
                .put("slot_tolerance_ms", tolerance).put("boundaries_covered", boundaries);
        if (complete) coverage.putNull("missing_slots");
        else coverage.putArray("missing_slots").add("EVENT_SEQUENCE_BOUNDARY_OR_PAGINATION_INCOMPLETE");
        coverage.set("cadence_segments", discovered);
        if (series.has("source_coverage_complete")) coverage.set("source_pagination_complete",
                series.get("source_coverage_complete").deepCopy()); else coverage.putNull("source_pagination_complete");
        if (canonical.isEmpty()) coverage.putNull("first_event_time").putNull("last_event_time");
        else coverage.put("first_event_time", iso(first)).put("last_event_time", iso(last));
        ObjectNode result = object(); result.set("rows", array(canonical)); result.set("coverage", coverage); return result;
    }

    private static ArrayNode validateCadenceSegments(ObjectNode series) {
        JsonNode raw = series.get("cadence_segments");
        if (raw == null || !raw.isArray()) throw failure("funding cadence_segments must be an array");
        List<ObjectNode> segments = new ArrayList<>();
        int index = 0;
        for (JsonNode node : raw) {
            ObjectNode segment = (ObjectNode) node.deepCopy();
            long from = time(segment.get("effective_from"));
            long to = time(segment.get("effective_to"));
            long cadence = integer(segment.get("cadence_ms"), "cadence_ms");
            long origin = segment.has("origin_at") ? time(segment.get("origin_at")) : from;
            if (to <= from || cadence <= 0 || origin > to) throw failure("invalid funding cadence segment " + index);
            segment.put("effective_from", iso(from)).put("effective_to", iso(to))
                    .put("cadence_ms", cadence).put("origin_at", iso(origin));
            segments.add(segment); index++;
        }
        if (segments.isEmpty()) throw failure("funding series requires at least one cadence segment");
        segments.sort(Comparator.comparingLong(row -> time(row.get("effective_from"))));
        for (int i = 1; i < segments.size(); i++) {
            long previous = time(segments.get(i - 1).get("effective_to"));
            long next = time(segments.get(i).get("effective_from"));
            if (next < previous) throw failure("funding cadence segments overlap ambiguously");
            if (next > previous) throw failure("funding cadence segments contain an uncovered interval");
        }
        return array(segments);
    }

    private static ObjectNode segmentAt(ObjectNode series, long time) {
        long tolerance = integerOr(series.get("slot_tolerance_ms"), 0);
        List<ObjectNode> segments = objects(validateCadenceSegments(series));
        for (int index = 0; index < segments.size(); index++) {
            ObjectNode segment = segments.get(index);
            long from = time(segment.get("effective_from"));
            long to = time(segment.get("effective_to"));
            if (time >= from - tolerance && (index == segments.size() - 1 ? time <= to + tolerance : time < to)) {
                return segment;
            }
        }
        throw failure("funding timestamp " + iso(time) + " is outside declared cadence segments");
    }

    private static List<Long> expectedFundingSlots(ObjectNode series) {
        long start = time(series.get("start_at")), end = time(series.get("end_at"));
        List<ObjectNode> segments = objects(validateCadenceSegments(series));
        Set<Long> slots = new LinkedHashSet<>();
        for (int index = 0; index < segments.size(); index++) {
            ObjectNode segment = segments.get(index);
            long from = Math.max(start, time(segment.get("effective_from")));
            long segmentEnd = time(segment.get("effective_to"));
            long to = Math.min(end, segmentEnd);
            if (to < from) continue;
            long cadence = segment.path("cadence_ms").asLong(), origin = time(segment.get("origin_at"));
            long slot = origin + (long) Math.ceil((double) (from - origin) / cadence) * cadence;
            while (index == segments.size() - 1 ? slot <= to : slot < to) {
                slots.add(slot); slot += cadence;
            }
        }
        return slots.stream().sorted().toList();
    }

    public static ArrayNode bindFundingSettlementMarks(ObjectNode options) {
        List<ObjectNode> funding = objects(field(options, "fundingRows"));
        List<ObjectNode> marks = objects(field(options, "markRows"));
        Set<String> retained = new HashSet<>(hashInventory(options.get("markResponseSha256")));
        if (retained.isEmpty()) throw failure("funding settlement mark source has no physically retained response SHA");
        Map<Long, ObjectNode> byEvent = new HashMap<>();
        for (ObjectNode mark : marks) {
            long event = time(mark.get("event_time"));
            long available = time(mark.get("availability_time"));
            if (byEvent.putIfAbsent(event, mark) != null) {
                throw failure("funding settlement mark source has duplicate event identity " + iso(event));
            }
            if (available != event) throw failure("funding settlement mark source availability is not exact at " + iso(event));
            if (!(number(mark.get("mark_open")) > 0)) throw failure("funding settlement mark source has no exact positive mark at " + iso(event));
            if (!retained.contains(text(mark, "response_sha256"))) {
                throw failure("funding settlement mark source response SHA is not physically retained at " + iso(event));
            }
        }
        List<ObjectNode> canonical = funding;
        if (options.path("series").isObject()) {
            ObjectNode canonicalOptions = object(); canonicalOptions.set("rows", array(funding)); canonicalOptions.set("series", options.path("series"));
            canonical = objects(canonicalizeFundingRows(canonicalOptions).path("rows"));
        }
        ArrayNode output = array();
        for (ObjectNode input : canonical) {
            long slot = time(first(input, "settlement_slot", "raw_event_time", "event_time"));
            ObjectNode mark = byEvent.get(slot);
            if (mark == null || time(mark.get("event_time")) != slot || time(mark.get("availability_time")) != slot) {
                throw failure("funding settlement mark source is missing exact event " + iso(slot));
            }
            ObjectNode row = input.deepCopy();
            double value = number(mark.get("mark_open"));
            row.put("settlement_mark", value).put("mark_price", value)
                    .put("settlement_mark_source", "BINANCE_MARK_PRICE_KLINE_OPEN_AT_SETTLEMENT")
                    .put("settlement_mark_event_time", iso(slot)).put("settlement_mark_availability_time", iso(slot))
                    .put("settlement_mark_source_response_sha256", text(mark, "response_sha256"));
            output.add(row);
        }
        return output;
    }

    public static ObjectNode fundingRequestBounds(ObjectNode series) {
        if (series == null || !"funding_events".equals(text(series, "series_type"))) {
            throw failure("funding request bounds require a funding series");
        }
        long start = time(series.get("start_at")), end = time(series.get("end_at"));
        long tolerance = integerOr(series.get("slot_tolerance_ms"), 60_000);
        long cutoff = time(series.get("availability_cutoff_at"));
        if (tolerance < 0 || cutoff < end) throw failure("funding request bounds are invalid");
        return object().put("startTime", Math.max(0, start - tolerance))
                .put("endTime", Math.min(cutoff, end + tolerance)).put("slot_tolerance_ms", tolerance);
    }

    public static double computeFundingPnl(ObjectNode options) {
        double rate = number(options.get("fundingRate"));
        double mark = number(options.get("settlementMark"));
        double quantity = number(options.get("signedQuantity"));
        double multiplier = number(options.get("contractMultiplier"));
        double quote = options.has("quoteMultiplier") ? number(options.get("quoteMultiplier")) : 1;
        if (!Double.isFinite(rate) || !Double.isFinite(mark) || !Double.isFinite(quantity)
                || !Double.isFinite(multiplier) || !Double.isFinite(quote) || mark <= 0 || multiplier <= 0 || quote <= 0) {
            throw failure("funding PnL requires finite rate/mark/position/contract terms");
        }
        return -(quantity * mark * multiplier * quote * rate);
    }

    /* ------------------------------------------------------------------ */
    /* Frozen timeframe requirements and five-year plan                    */
    /* ------------------------------------------------------------------ */

    public static ObjectNode makeTimeframeRequirements(ObjectNode options) {
        List<ObjectNode> declarations = objects(field(options, "declarations"));
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode declaration : declarations) {
            String id = text(declaration, "predictor_id");
            if (!PREDICTOR_ID.matcher(id).matches()) throw failure("timeframe declaration predictor_id is invalid: " + id);
            String interval = text(declaration, "interval").toLowerCase(Locale.ROOT);
            if (!Set.of("5m", "1h", "4h", "1d", "event").contains(interval)) {
                throw failure("timeframe declaration interval is not permitted: " + interval);
            }
            List<String> types = uniqueSortedTexts(declaration.path("series_types"));
            if (types.isEmpty() || types.stream().anyMatch(value -> !Set.of("signal_bars", "mark_bars",
                    "funding_events", "metrics_events").contains(value))) {
                throw failure("timeframe declaration series_types are invalid for " + id);
            }
            if ("event".equals(interval) && types.stream().anyMatch(value -> !Set.of("funding_events", "metrics_events").contains(value))) {
                throw failure("event timeframe declaration cannot request bar series for " + id);
            }
            if (!"event".equals(interval) && types.contains("funding_events")) {
                throw failure("funding events require the event timeframe for " + id);
            }
            ObjectNode row = object().put("predictor_id", id).put("interval", interval)
                    .put("context_only", declaration.path("context_only").asBoolean(false));
            row.set("series_types", strings(types));
            if (types.contains("metrics_events")) {
                List<String> fields = uniqueSortedTexts(declaration.path("required_fields"));
                double minimum = declaration.has("minimum_field_coverage")
                        ? number(declaration.get("minimum_field_coverage")) : 0.95;
                if (!Double.isFinite(minimum) || minimum < 0 || minimum > 1) {
                    throw failure("timeframe declaration minimum_field_coverage is invalid for " + id);
                }
                row.set("required_fields", strings(fields)); row.put("minimum_field_coverage", minimum);
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing((ObjectNode row) -> text(row, "predictor_id"))
                .thenComparing(row -> text(row, "interval")).thenComparing(row -> stable(row.path("series_types"))));
        if (rows.isEmpty()) throw failure("timeframe requirements must contain at least one frozen declaration");
        List<String> intervals = rows.stream().map(row -> text(row, "interval"))
                .filter(value -> !"event".equals(value)).distinct().collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (!intervals.contains("4h")) intervals.add("4h");
        Map<String, Double> hours = Map.of("5m", 0.083333, "1h", 1d, "4h", 4d, "1d", 24d);
        intervals.sort(Comparator.comparingDouble(hours::get));
        JsonNode precommit = options.get("precommitSha256");
        JsonNode registry = options.get("predictorRegistrySha256");
        if (defined(precommit)) requireSha(textValue(precommit), "timeframe requirements precommit_sha256");
        if (defined(registry)) requireSha(textValue(registry), "timeframe requirements predictor_registry_sha256");
        ObjectNode result = object().put("schema", "strategy-v5-timeframe-requirements/1")
                .put("version", 1).put("status", "FROZEN");
        putNullable(result, "precommit_sha256", defined(precommit) ? textValue(precommit) : null);
        putNullable(result, "predictor_registry_sha256", defined(registry) ? textValue(registry) : null);
        result.set("required_intervals", strings(intervals)); result.set("declarations", array(rows));
        return withHash(result);
    }

    public static ObjectNode makeTimeframeRequirementsFromPredictorRegistry(ObjectNode options) {
        ObjectNode registryNode = requiredObject(options, "predictorRegistry");
        LinkedHashMap<String, ObjectNode> registry = validatePredictorRegistry(registryNode);
        Map<String, String> aliases = Map.of(
                "sum_open_interest", "open_interest", "sum_open_interest_value", "open_interest_value",
                "count_toptrader_long_short_ratio", "top_trader_account_long_short_ratio",
                "sum_toptrader_long_short_ratio", "top_trader_position_long_short_ratio",
                "count_long_short_ratio", "global_long_short_ratio",
                "sum_taker_long_short_vol_ratio", "taker_buy_sell_volume_ratio");
        Set<String> metricFields = Set.of("open_interest", "open_interest_value",
                "top_trader_account_long_short_ratio", "top_trader_position_long_short_ratio",
                "global_long_short_ratio", "taker_buy_sell_volume_ratio");
        ArrayNode declarations = array();
        for (ObjectNode predictor : registry.values()) {
            String id = text(predictor, "id");
            String family = text(predictor, "source_family").trim().toLowerCase(Locale.ROOT);
            String source = textOr(first(predictor, "source_field"), text(predictor.path("recipe"), "source_field"))
                    .trim().toLowerCase(Locale.ROOT);
            String canonical = aliases.getOrDefault(source, source);
            List<String> types;
            if (predictor.path("recipe").path("required_series_types").isArray()) {
                types = uniqueSortedTexts(predictor.path("recipe").path("required_series_types"));
            } else if ("oi_weighted_funding".equals(source)) {
                throw failure("predictor " + id + " composite market-flow field requires explicit recipe.required_series_types");
            } else if (metricFields.contains(canonical) || Set.of("metrics", "metrics_events",
                    "open_interest_metrics", "market_flow_metrics").contains(family)) types = List.of("metrics_events");
            else if (Set.of("funding_rate", "funding").contains(source)
                    || Set.of("funding", "funding_events").contains(family)) types = List.of("funding_events");
            else if (Set.of("mark", "mark_bars", "mark_price").contains(family) || source.startsWith("mark_")) {
                types = List.of("mark_bars");
            } else if ("market_flow".equals(family)) {
                throw failure("predictor " + id + " market-flow field lacks an explicit series mapping");
            } else types = List.of("signal_bars");
            boolean event = types.stream().anyMatch(value -> Set.of("funding_events", "metrics_events").contains(value));
            String interval = textOr(predictor.get("source_timeframe"), event ? "event" : "4h");
            if ("event".equals(interval) && types.stream().anyMatch(value -> !Set.of("funding_events", "metrics_events").contains(value))) {
                throw failure("predictor " + id + " mixes event and bar series without a valid timeframe");
            }
            ObjectNode declaration = declarations.addObject().put("predictor_id", id).put("interval", interval)
                    .put("context_only", "CONTEXT_ONLY".equals(text(predictor, "trade_scope"))
                            || predictor.path("recipe").path("context_only").asBoolean(false));
            declaration.set("series_types", strings(types));
            if (types.contains("metrics_events")) {
                declaration.set("required_fields", strings(metricFields.contains(canonical) ? List.of(canonical) : List.of()));
                declaration.put("minimum_field_coverage", 0.95);
            }
        }
        ObjectNode request = object().set("declarations", declarations);
        if (defined(options.get("precommitSha256"))) request.set("precommitSha256", options.get("precommitSha256"));
        request.put("predictorRegistrySha256", text(registryNode, "content_sha256"));
        return makeTimeframeRequirements(request);
    }

    public static ObjectNode makeFiveYearAuthoritativePlan(ObjectNode options) {
        ObjectNode source = options == null ? object() : options;
        double years = source.has("years") ? number(source.get("years")) : 5;
        if (years != 5) throw failure("v5 authoritative plan is frozen to five years");
        List<String> selected = source.path("assets").isArray() ? uniqueSortedTexts(source.path("assets"))
                : DATA_V5_ASSETS.stream().sorted().toList();
        if (selected.size() != DATA_V5_ASSETS.size() || !selected.equals(DATA_V5_ASSETS.stream().sorted().toList())) {
            throw failure("v5 authoritative plan requires exactly the eight crypto assets");
        }
        long rawEnd = source.has("asOf") ? time(source.get("asOf")) : System.currentTimeMillis();
        Bounds top = completedBounds(rawEnd, "4h", 5);
        ObjectNode requirements = null;
        if (source.path("timeframeRequirements").isObject()) {
            requirements = (ObjectNode) source.path("timeframeRequirements").deepCopy();
            assertOwnHash(requirements, "strategy-v5-timeframe-requirements/1", "timeframe requirements");
        } else if (source.path("predictorRegistry").isObject()) {
            ObjectNode request = object().set("predictorRegistry", source.path("predictorRegistry"));
            if (defined(source.get("precommitSha256"))) request.set("precommitSha256", source.get("precommitSha256"));
            requirements = makeTimeframeRequirementsFromPredictorRegistry(request);
        }
        if (requirements != null && !"FROZEN".equals(text(requirements, "status"))) {
            throw failure("timeframe requirements must be frozen");
        }
        List<String> intervals = requirements == null ? List.of("4h") : texts(requirements.path("required_intervals"));
        List<ObjectNode> declarations;
        if (requirements == null) {
            ObjectNode defaults = object().put("interval", "4h").put("minimum_field_coverage", 0.95);
            defaults.set("series_types", strings(List.of("signal_bars", "mark_bars", "funding_events", "metrics_events")));
            defaults.set("required_fields", array());
            declarations = List.of(defaults);
        } else declarations = objects(requirements.path("declarations"));
        boolean metricsRequired = requirements != null && declarations.stream()
                .anyMatch(row -> texts(row.path("series_types")).contains("metrics_events"));
        Set<String> eventTypes = declarations.stream().filter(row -> "event".equals(text(row, "interval")))
                .flatMap(row -> texts(row.path("series_types")).stream()).collect(java.util.stream.Collectors.toSet());
        ArrayNode series = array();
        List<ObjectNode> suppliedContracts = new ArrayList<>(objects(source.path("datedContracts")));
        if (source.path("datedFuturesCatalog").path("contracts").isArray()) {
            suppliedContracts.addAll(objects(source.path("datedFuturesCatalog").path("contracts")));
        }
        for (String asset : selected) {
            for (String interval : intervals) {
                Set<String> types = new LinkedHashSet<>();
                for (ObjectNode declaration : declarations) if (interval.equals(text(declaration, "interval"))) {
                    types.addAll(texts(declaration.path("series_types")));
                }
                if ("4h".equals(interval)) { types.add("signal_bars"); types.add("mark_bars"); }
                Bounds bounds = completedBounds(rawEnd, interval, 5);
                if (types.contains("signal_bars")) {
                    series.add(makeSeries(asset, "BINANCE_SPOT", asset.toUpperCase(Locale.ROOT) + "USDT", interval,
                            "signal_bars", bounds, true, null, scopeFor(declarations, interval, "signal_bars"), null));
                    series.add(makeSeries(asset, "BINANCE_USDM_PERPETUAL", asset.toUpperCase(Locale.ROOT) + "USDT", interval,
                            "signal_bars", bounds, true, null, scopeFor(declarations, interval, "signal_bars"), null));
                }
                if (types.contains("mark_bars")) {
                    series.add(makeSeries(asset, "BINANCE_USDM_PERPETUAL_MARK", asset.toUpperCase(Locale.ROOT) + "USDT", interval,
                            "mark_bars", bounds, true, null, scopeFor(declarations, interval, "mark_bars"), null));
                }
                if (types.contains("metrics_events") && !"event".equals(interval)) {
                    ObjectNode metric = makeSeries(asset, "BINANCE_USDM_PERPETUAL", asset.toUpperCase(Locale.ROOT) + "USDT",
                            interval, "metrics_events", bounds, metricsRequired, null, "CONTEXT_ONLY", null);
                    List<String> fields = declarations.stream().filter(row -> interval.equals(text(row, "interval"))
                                    && texts(row.path("series_types")).contains("metrics_events"))
                            .flatMap(row -> texts(row.path("required_fields")).stream()).distinct().sorted().toList();
                    double minimum = declarations.stream().filter(row -> interval.equals(text(row, "interval"))
                                    && texts(row.path("series_types")).contains("metrics_events"))
                            .mapToDouble(row -> row.has("minimum_field_coverage") ? number(row.get("minimum_field_coverage")) : 0.95)
                            .max().orElse(0.95);
                    metric.set("metric_required_fields", strings(fields)); metric.put("metric_minimum_field_coverage", minimum);
                    series.add(metric);
                }
            }
            Bounds eventBounds = new Bounds(top.start, top.end, top.cutoff);
            ObjectNode funding = makeSeries(asset, "BINANCE_USDM_PERPETUAL", asset.toUpperCase(Locale.ROOT) + "USDT",
                    "event", "funding_events", eventBounds, true, null, "CONTEXT_ONLY", null);
            JsonNode cadences = source.path("fundingCadenceSegments");
            if (cadences.isObject() && cadences.has(asset)) funding.set("cadence_segments", cadences.get(asset).deepCopy());
            else if (cadences.isArray()) funding.set("cadence_segments", cadences.deepCopy());
            series.add(funding);
            if (eventTypes.contains("metrics_events")) {
                ObjectNode metric = makeSeries(asset, "BINANCE_USDM_PERPETUAL", asset.toUpperCase(Locale.ROOT) + "USDT",
                        "event", "metrics_events", eventBounds, true, null, "CONTEXT_ONLY", null);
                List<String> fields = declarations.stream().filter(row -> "event".equals(text(row, "interval"))
                                && texts(row.path("series_types")).contains("metrics_events"))
                        .flatMap(row -> texts(row.path("required_fields")).stream()).distinct().sorted().toList();
                double minimum = declarations.stream().filter(row -> "event".equals(text(row, "interval"))
                                && texts(row.path("series_types")).contains("metrics_events"))
                        .mapToDouble(row -> row.has("minimum_field_coverage") ? number(row.get("minimum_field_coverage")) : 0.95)
                        .max().orElse(0.95);
                metric.set("metric_required_fields", strings(fields)); metric.put("metric_minimum_field_coverage", minimum);
                series.add(metric);
            }
            for (ObjectNode contract : suppliedContracts) {
                if (!asset.equals(text(contract, "asset").toLowerCase(Locale.ROOT))
                        || "UNAVAILABLE".equals(text(contract, "history_status"))) continue;
                long onboard = defined(first(contract, "first_bar_at", "onboard_at"))
                        ? time(first(contract, "first_bar_at", "onboard_at")) : top.start;
                JsonNode exactExpiry = first(contract, "expiry", "expiry_at");
                Long expiry = defined(exactExpiry) ? time(exactExpiry) : null;
                Long observedLast = defined(contract.get("last_bar_at")) ? time(contract.get("last_bar_at")) : null;
                Long contractEnd = observedLast != null ? observedLast : expiry;
                if (contractEnd == null || !(contractEnd > top.start && onboard < top.cutoff)) continue;
                Bounds contractBounds = new Bounds(Math.max(top.start, onboard), Math.min(top.end, contractEnd), top.cutoff);
                ObjectNode dated = makeSeries(asset, "BINANCE_USDM_DATED_FUTURE", text(contract, "symbol"), "4h",
                        "signal_bars", contractBounds, false, expiry, null,
                        contract.path("tradeable").asBoolean(false) && expiry != null);
                putNullable(dated, "expiry_observed_date_utc", defined(contract.get("expiry_observed_date_utc"))
                        ? textValue(contract.get("expiry_observed_date_utc")) : null);
                dated.put("expiry_binding_status", textOr(contract.get("expiry_binding_status"), expiry == null ? "UNAVAILABLE" : "BOUND"));
                series.add(dated);
            }
        }
        ArrayNode limitations = array();
        if (requirements == null) limitations.add("METRICS_CONTEXT_OPTIONAL_UNTIL_FROZEN_REQUIREMENT");
        if (suppliedContracts.isEmpty()) limitations.add("DATED_FUTURES_CATALOG_NOT_BOUND");
        else limitations.add("DATED_FUTURES_HISTORY_COVERAGE_BOUND_TO_SUPPLIED_CATALOG");
        if (suppliedContracts.stream().anyMatch(row -> !DATA_V5_ASSETS.contains(text(row, "asset").toLowerCase(Locale.ROOT)))) {
            limitations.add("DATED_FUTURES_OUTSIDE_UNIVERSE_IGNORED");
        }
        if (source.path("datedFuturesCatalog").path("limitations").isArray()) {
            source.path("datedFuturesCatalog").path("limitations").forEach(limitations::add);
        }
        ObjectNode value = object().put("schema", DATA_V5.get("plan")).put("version", 1)
                .put("status", "PLAN_ONLY").put("as_of", iso(rawEnd));
        value.set("window", object().put("years", 5).put("start_at", iso(top.start)).put("end_at", iso(top.end))
                .put("completed_through_at", iso(top.cutoff)));
        value.set("assets", strings(selected)); value.set("series", series);
        value.put("root_reference", portableReference(textOr(source.get("rootReference"), "strategy-research/v5-data")));
        value.putNull("dated_futures_catalog_sha256");
        value.put("dated_futures_catalog_status", textOr(source.path("datedFuturesCatalog").get("status"), "UNAVAILABLE"));
        if (source.path("datedFuturesCatalog").has("content_sha256")) {
            value.put("dated_futures_catalog_sha256", text(source.path("datedFuturesCatalog"), "content_sha256"));
        }
        putNullable(value, "timeframe_requirements_sha256", requirements == null ? null : text(requirements, "content_sha256"));
        value.set("raw_storage", object().put("format", "JSONL").put("storage_role", "STAGING")
                .put("authoritative", false).put("policy", "JSONL_STAGING_ONLY_NEVER_MISLABELLED_AS_PARQUET"));
        value.set("conversion", object().put("status", "AVAILABLE").put("required_format", "PARQUET")
                .put("dependency", "@duckdb/node-api@1.5.5-r.4").put("threads", 1)
                .put("promotion", "REQUIRES_VERIFIED_BYTES_ROWS_SCHEMA_AND_PARTITION_MANIFEST"));
        value.set("limitations", limitations);
        ObjectNode result = withHash(value);
        validatePlan(result);
        return result;
    }

    private static ObjectNode makeSeries(String asset, String instrument, String symbol, String interval,
            String seriesType, Bounds bounds, boolean required, Long expiry, String tradeScope, Boolean tradeable) {
        boolean event = "event".equals(interval) || "funding_events".equals(seriesType);
        Long step = event ? null : timeframeMilliseconds(interval);
        String role = "mark_bars".equals(seriesType) ? "MARK" : "funding_events".equals(seriesType)
                ? "FUNDING" : "metrics_events".equals(seriesType) ? "METRICS" : "PRICE";
        String scope = tradeScope == null ? "signal_bars".equals(seriesType) ? "TRADEABLE_CRYPTO" : "CONTEXT_ONLY" : tradeScope;
        ObjectNode value = object().put("asset", asset).put("venue", "BINANCE").put("instrument", instrument)
                .put("symbol", symbol).put("interval", interval).put("series_type", seriesType)
                .put("series_role", role).put("trade_scope", scope).put("start_at", iso(bounds.start))
                .put("end_at", iso(bounds.end)).put("availability_cutoff_at", iso(bounds.cutoff))
                .put("required", required).put("completed_bars_only", !event).put("require_availability_time", true)
                .put("fee_schedule_status", "UNAVAILABLE").put("contract_specification_status", "UNAVAILABLE")
                .put("funding_status", "funding_events".equals(seriesType) ? "UNAVAILABLE" : "NOT_APPLICABLE")
                .put("expiry_observed_date_utc", (String) null)
                .put("expiry_binding_status", "BINANCE_USDM_DATED_FUTURE".equals(instrument)
                        ? expiry == null ? "UNAVAILABLE" : "BOUND" : "NOT_APPLICABLE")
                .put("tradeable", tradeable == null ? "TRADEABLE_CRYPTO".equals(scope)
                        && (!"BINANCE_USDM_DATED_FUTURE".equals(instrument) || expiry != null)
                        : tradeable && "TRADEABLE_CRYPTO".equals(scope))
                .put("margin_status", "BINANCE_SPOT".equals(instrument) ? "NOT_APPLICABLE" : "UNAVAILABLE")
                .put("liquidation_status", "BINANCE_SPOT".equals(instrument) ? "NOT_APPLICABLE" : "UNAVAILABLE");
        if (step == null) value.putNull("expected_step_ms"); else value.put("expected_step_ms", step);
        if (expiry == null) value.put("expiry", "BINANCE_USDM_DATED_FUTURE".equals(instrument) ? "UNAVAILABLE" : "NOT_APPLICABLE");
        else value.put("expiry", iso(expiry));
        if ("funding_events".equals(seriesType)) {
            value.put("event_driven", true).put("event_sequence_mode", true).putNull("expected_event_count")
                    .put("slot_tolerance_ms", 60_000).set("cadence_segments", array());
        } else if ("metrics_events".equals(seriesType) && "event".equals(interval)) {
            value.put("event_driven", true).put("event_sequence_mode", false).putNull("expected_event_count");
        } else value.put("expected_event_count", Math.floorDiv(bounds.end - bounds.start, step) + 1);
        return value;
    }

    private static void validatePlan(ObjectNode plan) {
        assertOwnHash(plan, DATA_V5.get("plan"), "authoritative data plan");
        if (!"PLAN_ONLY".equals(text(plan, "status"))) throw failure("data acquisition requires an immutable PLAN_ONLY plan");
        if (plan.path("window").path("years").asInt() != 5
                || !uniqueSortedTexts(plan.path("assets")).equals(DATA_V5_ASSETS.stream().sorted().toList())) {
            throw failure("data plan universe/window is invalid");
        }
        List<ObjectNode> series = objects(plan.path("series"));
        if (series.isEmpty()) throw failure("data plan has no series");
        Map<String, Set<String>> marks = new HashMap<>();
        for (ObjectNode row : series) {
            requireAsset(text(row, "asset"));
            if (!row.has("start_at") || !row.has("end_at") || time(row.get("end_at")) < time(row.get("start_at"))) {
                throw failure("data series bounds are invalid");
            }
            if ("metrics_events".equals(text(row, "series_type")) && row.path("required").asBoolean(false)
                    && !isSha(text(plan, "timeframe_requirements_sha256"))) {
                throw failure("metrics series cannot be required without a frozen timeframe requirement hash");
            }
            if ("funding_events".equals(text(row, "series_type"))) {
                if (!(row.path("event_sequence_mode").asBoolean(false) && row.path("event_driven").asBoolean(false)
                        && (!row.path("cadence_segments").isArray() || row.path("cadence_segments").isEmpty()))) {
                    validateCadenceSegments(row);
                }
            } else if (!(row.path("expected_step_ms").canConvertToLong()
                    && row.path("expected_event_count").canConvertToLong()
                    && row.path("expected_event_count").asLong() >= 1
                    && row.path("expected_step_ms").asLong() == timeframeMilliseconds(text(row, "interval")))) {
                throw failure("bar series cadence/count is invalid");
            }
            if ("mark_bars".equals(text(row, "series_type"))) {
                if (!"BINANCE_USDM_PERPETUAL_MARK".equals(text(row, "instrument"))
                        || !"MARK".equals(text(row, "series_role")) || !row.path("required").asBoolean(true)) {
                    throw failure("perpetual mark series is not bound as a required mark series");
                }
                marks.computeIfAbsent(text(row, "interval"), ignored -> new HashSet<>()).add(text(row, "asset"));
            }
        }
        for (var entry : marks.entrySet()) for (String asset : DATA_V5_ASSETS) if (!entry.getValue().contains(asset)) {
            throw failure("v5 plan is missing perpetual " + entry.getKey() + " mark series for " + asset);
        }
        if (!marks.containsKey("4h")) throw failure("v5 plan is missing the required 4h perpetual mark series");
    }

    /* ------------------------------------------------------------------ */
    /* Coverage, opportunities, and candidate predicate contracts          */
    /* ------------------------------------------------------------------ */

    public static ObjectNode validateDenseBarCoverageV5(ObjectNode options) {
        return validateDenseBarCoverageV5(objects(field(options, "rows")), requiredObject(options, "series"),
                options.path("oneMinute").asBoolean(false));
    }

    public static ObjectNode validateDenseBarCoverageV5(List<ObjectNode> rows, ObjectNode series) {
        return validateDenseBarCoverageV5(rows, series, false);
    }

    private static ObjectNode validateDenseBarCoverageV5(List<ObjectNode> rows, ObjectNode series,
            boolean oneMinute) {
        if (rows.isEmpty()) return object().put("complete", false).put("reason", "NO_ROWS");
        long step = oneMinute ? ONE_MINUTE : integer(series.get("expected_step_ms"), "expected step");
        long start = time(series.get("start_at"));
        long end = time(series.get("end_at"));
        List<ObjectNode> ordered = rows.stream().map(ObjectNode::deepCopy)
                .sorted(Comparator.comparingLong(StrategyResearchDataV5::rowTime)).toList();
        List<Long> times = ordered.stream().map(StrategyResearchDataV5::rowTime).toList();
        boolean gridFailure = new HashSet<>(times).size() != times.size() || times.get(0) != start
                || times.get(times.size() - 1) != end;
        for (int index = 0; !gridFailure && index < times.size(); index++) {
            gridFailure = times.get(index) != start + index * step;
        }
        if (gridFailure) return object().put("complete", false).put("reason", "MISSING_OR_DUPLICATE_BAR")
                .put("first_event_time", times.get(0)).put("last_event_time", times.get(times.size() - 1))
                .put("observed_rows", rows.size());
        long cutoff = time(series.get("availability_cutoff_at"));
        if (ordered.stream().anyMatch(row -> rowAvailability(row) < rowTime(row))) {
            return object().put("complete", false).put("reason", "AVAILABILITY_BEFORE_EVENT");
        }
        if (ordered.stream().anyMatch(row -> rowAvailability(row) > cutoff)) {
            return object().put("complete", false).put("reason", "AVAILABILITY_AFTER_CUTOFF");
        }
        ArrayNode early = array();
        for (int index = 0; index < ordered.size(); index++) {
            ObjectNode row = ordered.get(index); long boundary = times.get(index) + step;
            if (rowAvailability(row) >= boundary - 1_000) continue;
            Long close = row.hasNonNull("close_time") ? time(row.get("close_time")) : null;
            if (close != null && close < boundary - 1_000 && rowAvailability(row) >= close) continue;
            early.add(object().put("event_time", iso(times.get(index)))
                    .put("availability_time", iso(rowAvailability(row)))
                    .put("expected_boundary_time", iso(boundary)).put("reason", "BAR_AVAILABLE_BEFORE_CLOSE"));
        }
        if (!early.isEmpty()) { ObjectNode result = object().put("complete", false).put("reason", "BAR_AVAILABLE_BEFORE_CLOSE");
            result.set("early_bars", early); return result.put("expected_rows", times.size()).put("observed_rows", rows.size()); }
        ArrayNode late = array();
        for (int index = 0; index < ordered.size(); index++) {
            ObjectNode row = ordered.get(index); long boundary = times.get(index) + step;
            if (rowAvailability(row) > boundary) late.add(object().put("event_time", iso(times.get(index)))
                    .put("availability_time", iso(rowAvailability(row)))
                    .put("expected_boundary_time", iso(boundary)).put("reason", "BAR_AVAILABLE_AFTER_BOUNDARY"));
        }
        if (!late.isEmpty()) { ObjectNode result = object().put("complete", false).put("reason", "BAR_AVAILABLE_AFTER_CLOSE");
            result.set("late_bars", late); return result.put("expected_rows", times.size()).put("observed_rows", rows.size())
                    .put("min_event_time", iso(times.get(0))).put("max_event_time", iso(times.get(times.size() - 1))); }
        ArrayNode irregular = array();
        for (int index = 0; index < ordered.size(); index++) {
            ObjectNode row = ordered.get(index); long boundary = times.get(index) + step;
            long close = row.hasNonNull("close_time") ? time(row.get("close_time")) : rowAvailability(row);
            if (close >= boundary - 1_000) continue;
            ObjectNode outage = object().put("event_time", iso(times.get(index)));
            if (row.hasNonNull("close_time")) outage.put("close_time", iso(row.get("close_time")));
            else outage.putNull("close_time");
            outage.put("availability_time", iso(rowAvailability(row)))
                    .put("expected_boundary_time", iso(boundary)).put("expected_duration_ms", step)
                    .put("observed_duration_ms", Math.max(0, close - times.get(index) + 1))
                    .put("classification", "EARLY_CLOSE_OUTAGE");
            irregular.add(outage);
        }
        long minAvailability = ordered.stream().mapToLong(StrategyResearchDataV5::rowAvailability).min().orElseThrow();
        long maxAvailability = ordered.stream().mapToLong(StrategyResearchDataV5::rowAvailability).max().orElseThrow();
        ObjectNode result = object().put("complete", true).put("expected_rows", times.size()).put("observed_rows", rows.size())
                .put("min_event_time", iso(times.get(0))).put("max_event_time", iso(times.get(times.size() - 1)))
                .put("min_availability_time", iso(minAvailability)).put("max_availability_time", iso(maxAvailability));
        result.set("irregular_bars", irregular); return result.put("irregular_bar_count", irregular.size());
    }

    public static ObjectNode makeOpportunityEnvelope(ObjectNode options) {
        String planSha = requireSha(text(options, "planSha256"), "plan_sha256");
        String candidateSha = requireSha(text(options, "candidateSetSha256"), "candidate_set_sha256");
        long lifecycle = integer(options.get("maxLifecycleMs"), "maximum lifecycle");
        if (lifecycle <= 0) throw failure("opportunity envelope requires a positive maximum lifecycle in milliseconds");
        String timeframe = textOr(options.get("lifecycleTimeframe"), "1m");
        ArrayNode normalized = array();
        List<ObjectNode> windows = objects(field(options, "windows"));
        windows.stream().map(window -> {
            String asset = requireAsset(text(window, "asset"));
            String instrument = textOr(window.get("instrument"), "BINANCE_SPOT");
            String symbol = textOr(window.get("symbol"), asset.toUpperCase(Locale.ROOT) + "USDT");
            long start = time(first(window, "execution_start", "start_at"));
            long end = time(first(window, "execution_end", "end_at"));
            if (end < start || end - start > lifecycle) throw failure("opportunity window exceeds frozen maximum lifecycle");
            List<String> ids = window.path("source_window_ids").isArray()
                    ? uniqueSortedTexts(window.path("source_window_ids"))
                    : List.of(textOr(window.get("window_id"), hash(window)));
            ObjectNode result = object().put("asset", asset).put("instrument", instrument).put("symbol", symbol)
                    .put("execution_start", iso(Math.floorDiv(start, ONE_MINUTE) * ONE_MINUTE))
                    .put("execution_end", iso(Math.floorDiv(end, ONE_MINUTE) * ONE_MINUTE))
                    .put("max_lifecycle_ms", lifecycle).put("lifecycle_timeframe", timeframe);
            result.set("source_window_ids", strings(ids));
            return result;
        }).sorted(Comparator.comparing(window -> text(window, "asset") + "|" + text(window, "instrument") + "|"
                + text(window, "symbol") + "|" + text(window, "execution_start") + "|"
                + text(window, "execution_end") + "|" + stable(window.path("source_window_ids"))))
                .forEach(normalized::add);
        if (normalized.isEmpty()) throw failure("opportunity envelope has no windows");
        String precommit = options.hasNonNull("precommitSha256")
                ? requireSha(text(options, "precommitSha256"), "precommit_sha256") : null;
        ObjectNode value = object().put("schema", "strategy-v5-opportunity-envelope/1").put("version", 1)
                .put("status", "FROZEN").put("plan_sha256", planSha).put("candidate_set_sha256", candidateSha);
        if (precommit == null) value.putNull("precommit_sha256"); else value.put("precommit_sha256", precommit);
        value.put("max_lifecycle_ms", lifecycle).put("lifecycle_timeframe", timeframe).set("windows", normalized);
        return withHash(value);
    }

    public static ArrayNode derivePredicatePredictorIds(ObjectNode predicate) {
        Set<String> output = new HashSet<>(); derivePredicatePredictorIds0(predicate, output); return strings(output.stream().sorted().toList());
    }

    private static void derivePredicatePredictorIds0(JsonNode predicate, Set<String> output) {
        if (predicate == null || !predicate.isObject()) throw failure("predicate AST is invalid");
        if (predicate.has("predictor_id")) {
            String id = textValue(predicate.get("predictor_id"));
            if (id.isEmpty()) throw failure("predicate predictor_id is empty");
            output.add(id); return;
        }
        JsonNode children = predicate.has("all") ? predicate.get("all") : predicate.get("any");
        if (children != null) {
            if (!children.isArray() || children.isEmpty()) throw failure("predicate AST conjunction/disjunction is empty");
            children.forEach(child -> derivePredicatePredictorIds0(child, output)); return;
        }
        if (predicate.has("not")) { derivePredicatePredictorIds0(predicate.get("not"), output); return; }
        throw failure("predicate AST is invalid");
    }

    public static boolean validateCandidatePredicates(ObjectNode options) {
        LinkedHashMap<String, ObjectNode> registry = validatePredictorRegistry(requiredObject(options, "predictorRegistry"));
        List<String> ids = predicateInventoryIds(field(options, "predicates"), "candidate predicates");
        for (String id : ids) if (!registry.containsKey(id)) {
            throw failure("candidate predicate references an unregistered predictor: " + id);
        }
        if (ids.size() != field(options, "predicates").size()) {
            throw failure("candidate predicate inventory contains duplicate predictor IDs");
        }
        return true;
    }

    private static List<String> predicateInventoryIds(JsonNode values, String label) {
        if (!values.isArray()) throw failure(label + " must be an array");
        List<String> collected = new ArrayList<>();
        for (JsonNode value : values) {
            String id = value.isTextual() ? value.asText() : value.isObject() ? text(value, "predictor_id") : "";
            if (id.isEmpty()) throw failure(label + " contains an invalid predictor identity");
            collected.add(id);
        }
        return collected.stream().distinct().sorted().toList();
    }

    /* ------------------------------------------------------------------ */
    /* Metadata receipts                                                   */
    /* ------------------------------------------------------------------ */

    public static ObjectNode makeMetadataReceipt(ObjectNode options) {
        String kind = text(options, "kind").toUpperCase(Locale.ROOT);
        ObjectNode value = "SETTLEMENT".equals(kind) ? makeSettlementMetadataReceipt(options)
                : makeMetadataReceiptLegacy(options, kind);
        if (!Set.of("PUBLIC_OBSERVED", "USER_BOUND").contains(text(value, "status"))) return value;
        Path root = requiredPath(options, "sourceRoot");
        String sourcePath = text(options, "sourceReceiptPath");
        if (sourcePath.isEmpty() && options.path("source").isObject()) {
            sourcePath = textOr(first(options.path("source"), "path", "receipt_path"), "");
        }
        if (sourcePath.isEmpty()) throw failure(kind + " public/user-bound metadata requires an explicit physical source root and normalized receipt path");
        ObjectNode summary = object().put("schema", "strategy-v5-source-receipt/1").put("path", sourcePath)
                .put("sha256", text(options, "sourceReceiptSha256"))
                .put("content_sha256", text(options, "sourceReceiptSha256"))
                .put("status", text(value, "status"));
        summary.set("byte_sha256", options.path("sourceByteSha256").deepCopy());
        verifyNormalizedReceipt(root, summary, kind + " metadata source receipt");
        ObjectNode bound = value.deepCopy();
        bound.put("source_root_reference", portableReference(root, text(options, "sourceRootReference")));
        ObjectNode receipt = object().put("path", sourcePath).put("sha256", text(options, "sourceReceiptSha256"))
                .put("content_sha256", text(options, "sourceReceiptSha256"))
                .put("schema", "strategy-v5-source-receipt/1").put("status", text(value, "status"));
        receipt.set("byte_sha256", options.path("sourceByteSha256").deepCopy());
        bound.set("source_receipts", array(List.of(receipt)));
        return withHash(bound);
    }

    private static ObjectNode makeMetadataReceiptLegacy(ObjectNode options, String kind) {
        Set<String> allowed = Set.of("FEE_SCHEDULE", "FUNDING_IDENTITY", "CONTRACT_SPEC", "EXPIRY",
                "MARGIN", "LIQUIDATION", "EXECUTION_MODEL");
        if (!allowed.contains(kind)) throw failure("unsupported metadata kind " + text(options, "kind"));
        String status = text(options, "status");
        if (!DATA_V5_STATUSES.contains(status)) throw failure("unsupported metadata status " + status);
        List<ObjectNode> records = objects(options.path("records"));
        if (!"UNAVAILABLE".equals(status) && records.isEmpty()) throw failure(kind + " metadata requires records unless UNAVAILABLE");
        validateMetadataSourceBinding(options, kind, status);
        if ("CONSERVATIVE_MODEL".equals(status)) {
            requireSha(text(options, "modelSha256"), kind + ".model_sha256");
            requireSha(text(options, "precommitSha256"), kind + ".precommit_sha256");
        }
        if (options.hasNonNull("planSha256")) requireSha(text(options, "planSha256"), "plan_sha256");
        if (options.hasNonNull("evaluatorSpecSha256")) requireSha(text(options, "evaluatorSpecSha256"), "evaluator_spec_sha256");
        ArrayNode normalized = array();
        for (ObjectNode source : records) {
            ObjectNode row = source.deepCopy();
            if (text(row, "asset").isEmpty() || text(row, "instrument").isEmpty() || !row.hasNonNull("effective_from")
                    || !row.hasNonNull("effective_to") || !row.hasNonNull("availability_time")) {
                throw failure(kind + " record lacks effective identity/bounds or availability_time");
            }
            if (time(row.get("effective_to")) < time(row.get("effective_from"))) throw failure(kind + " effective bounds are invalid");
            if (!row.has("venue")) row.put("venue", "BINANCE");
            if (!row.has("symbol")) row.put("symbol", text(row, "asset").toUpperCase(Locale.ROOT) + "USDT");
            switch (kind) {
                case "FEE_SCHEDULE" -> finite(row, "taker_fee_rate", false, kind);
                case "FUNDING_IDENTITY" -> {
                    if (text(row, "event_id").isEmpty()) throw failure("FUNDING_IDENTITY record event_id is missing");
                    finite(row, "funding_rate", false, kind);
                }
                case "CONTRACT_SPEC" -> finite(row, "contract_multiplier", true, kind);
                case "MARGIN" -> finite(row, "maintenance_margin_ratio", true, kind);
                case "LIQUIDATION" -> finite(row, "liquidation_price", true, kind);
                case "EXECUTION_MODEL" -> {
                    finite(row, "slippage_bps", false, kind); finite(row, "impact_bps", false, kind);
                    if (text(row, "outage_policy").isEmpty() || text(row, "gap_policy").isEmpty()) {
                        throw failure("EXECUTION_MODEL outage/gap policy is missing");
                    }
                }
                case "EXPIRY" -> {
                    if (!row.hasNonNull("expiry") && !row.hasNonNull("delivery_date")) throw failure("EXPIRY record expiry is missing");
                }
                default -> { }
            }
            normalized.add(row);
        }
        boolean authoritative = Set.of("PUBLIC_OBSERVED", "USER_BOUND").contains(status)
                || "CONSERVATIVE_MODEL".equals(status) && "EXECUTION_MODEL".equals(kind);
        ObjectNode value = object().put("schema", DATA_V5.get("metadata")).put("version", 1)
                .put("kind", kind).put("status", status).put("captured_at", iso(firstOrNow(options, "capturedAt")))
                .put("provenance_mode", "CONSERVATIVE_MODEL".equals(status) ? "MODEL_BOUND"
                        : "UNAVAILABLE".equals(status) ? "UNAVAILABLE" : "BOUND_SOURCE")
                .put("authoritative", authoritative);
        copyNullable(value, "plan_sha256", options.get("planSha256")); copyNullable(value, "source", options.get("source"));
        copyNullable(value, "source_receipt_sha256", options.get("sourceReceiptSha256"));
        copyNullable(value, "source_byte_sha256", options.get("sourceByteSha256"));
        copyNullable(value, "model_sha256", options.get("modelSha256")); copyNullable(value, "precommit_sha256", options.get("precommitSha256"));
        copyNullable(value, "evaluator_spec_sha256", options.get("evaluatorSpecSha256"));
        value.set("records", normalized); copyNullable(value, "coverage", options.get("coverage"));
        value.set("limitations", strings(uniqueSortedTextsOrEmpty(options.get("limitations"))));
        return withHash(value);
    }

    private static ObjectNode makeSettlementMetadataReceipt(ObjectNode options) {
        String status = text(options, "status");
        if (!DATA_V5_STATUSES.contains(status)) throw failure("unsupported metadata status SETTLEMENT");
        if (!Set.of("PUBLIC_OBSERVED", "USER_BOUND", "UNAVAILABLE").contains(status)) {
            throw failure("SETTLEMENT metadata must be physically observed/user-bound or unavailable");
        }
        List<ObjectNode> records = objects(options.path("records"));
        if (!"UNAVAILABLE".equals(status) && records.isEmpty()) throw failure("SETTLEMENT metadata requires records unless UNAVAILABLE");
        validateMetadataSourceBinding(options, "SETTLEMENT", status);
        long captured = firstOrNow(options, "capturedAt"); ArrayNode normalized = array();
        List<String> sourceHashes = hashInventory(options.get("sourceByteSha256"));
        for (ObjectNode original : records) {
            ObjectNode row = original.deepCopy();
            if (text(row, "asset").isEmpty() || !"BINANCE".equals(text(row, "venue").toUpperCase(Locale.ROOT))
                    || text(row, "symbol").isEmpty() || !"BINANCE_USDM_DATED_FUTURE".equals(text(row, "instrument").toUpperCase(Locale.ROOT))
                    || !row.hasNonNull("effective_from") || !row.hasNonNull("effective_to") || !row.hasNonNull("availability_time")) {
                throw failure("SETTLEMENT record lacks exact dated-futures identity/bounds or availability_time");
            }
            double price = numeric(first(row, "settlement_price", "delivery_price", "settlement_value"));
            if (!(price > 0)) throw failure("SETTLEMENT record settlement_price is invalid");
            long expiry = time(first(row, "expiry", "delivery_date")); long event = time(row.get("event_time"));
            long settlement = time(row.get("settlement_time")); long available = time(row.get("availability_time"));
            if (time(row.get("effective_to")) < time(row.get("effective_from")) || event != settlement || event < expiry
                    || available < event || available > captured) throw failure("SETTLEMENT event/expiry/availability chronology is invalid");
            if (text(row, "settlement_mark_event_id").isEmpty()) throw failure("SETTLEMENT record settlement_mark_event_id is missing");
            String sourceHash = text(row, "settlement_mark_source_sha256");
            if (!isSha(sourceHash) || !sourceHashes.contains(sourceHash)) throw failure("SETTLEMENT record mark source is not in the bound physical source-byte inventory");
            if (row.hasNonNull("source_receipt_sha256") && !text(row, "source_receipt_sha256").equals(text(options, "sourceReceiptSha256"))) {
                throw failure("SETTLEMENT record source receipt identity differs from the bound receipt");
            }
            row.put("venue", "BINANCE").put("instrument", "BINANCE_USDM_DATED_FUTURE")
                    .put("settlement_price", price).put("source_receipt_sha256", text(options, "sourceReceiptSha256"))
                    .put("source_byte_sha256", sourceHash).remove(List.of("delivery_price", "settlement_value"));
            normalized.add(row);
        }
        ObjectNode value = object().put("schema", DATA_V5.get("metadata")).put("version", 1)
                .put("kind", "SETTLEMENT").put("status", status).put("captured_at", iso(captured))
                .put("provenance_mode", "UNAVAILABLE".equals(status) ? "UNAVAILABLE" : "BOUND_SOURCE")
                .put("authoritative", Set.of("PUBLIC_OBSERVED", "USER_BOUND").contains(status));
        copyNullable(value, "plan_sha256", options.get("planSha256")); copyNullable(value, "source", options.get("source"));
        copyNullable(value, "source_receipt_sha256", options.get("sourceReceiptSha256"));
        copyNullable(value, "source_byte_sha256", options.get("sourceByteSha256"));
        value.putNull("model_sha256").putNull("precommit_sha256").set("records", normalized);
        copyNullable(value, "coverage", options.get("coverage"));
        value.set("limitations", strings(uniqueSortedTextsOrEmpty(options.get("limitations"))));
        return withHash(value);
    }

    public static ObjectNode verifyMetadataCoverage(ObjectNode options) {
        ObjectNode receipts = options.path("receipts").isObject() ? (ObjectNode) options.path("receipts") : object();
        ObjectNode coverage = object(); List<String> limitations = new ArrayList<>();
        long start = time(options.get("startAt")); long end = time(options.get("endAt"));
        for (String kind : uniqueTextsOrEmpty(options.get("requiredKinds"))) {
            JsonNode receipt = receipts.has(kind) ? receipts.get(kind) : receipts.get(kind.toLowerCase(Locale.ROOT));
            if (receipt == null || !receipt.isObject() || !DATA_V5.get("metadata").equals(text(receipt, "schema"))
                    || !kind.toUpperCase(Locale.ROOT).equals(text(receipt, "kind"))
                    || !text(receipt, "content_sha256").equals(ownHash(receipt))) {
                limitations.add(kind + ": MISSING_OR_TAMPERED_RECEIPT"); continue;
            }
            if ("UNAVAILABLE".equals(text(receipt, "status"))) {
                List<String> why = uniqueTextsOrEmpty(receipt.get("limitations"));
                limitations.add(kind + ": " + (why.isEmpty() ? "UNAVAILABLE" : String.join(",", why))); continue;
            }
            List<String> missing = new ArrayList<>();
            for (String pair : uniqueTextsOrEmpty(options.get("requiredPairs"))) {
                String[] split = pair.split("\\|", -1); if (split.length != 2) { missing.add(pair); continue; }
                List<ObjectNode> candidates = objects(receipt.path("records")).stream()
                        .filter(row -> split[0].equalsIgnoreCase(text(row, "asset"))
                                && split[1].equalsIgnoreCase(text(row, "instrument")))
                        .sorted(Comparator.comparingLong(row -> time(row.get("effective_from")))).toList();
                long cursor = start;
                for (ObjectNode row : candidates) {
                    long from = time(row.get("effective_from")); if (from > cursor) break;
                    cursor = Math.max(cursor, time(row.get("effective_to")));
                }
                if (cursor < end) missing.add(pair);
            }
            coverage.set(kind, object().put("status", text(receipt, "status")).set("missing_pairs", strings(missing)));
            if (!missing.isEmpty()) limitations.add(kind + ": UNCOVERED_PAIRS:" + String.join(",", missing));
        }
        ObjectNode result = object().put("pass", limitations.isEmpty()); result.set("coverage", coverage);
        result.set("limitations", strings(limitations)); return result;
    }

    /* ------------------------------------------------------------------ */
    /* Staging custody and physical dataset roots                          */
    /* ------------------------------------------------------------------ */

    public static ObjectNode inspectCaptureLineage(ObjectNode options) {
        return inspectCaptureLineage(requiredObject(options, "capture"), requiredPath(options, "root"));
    }

    public static ObjectNode inspectCaptureLineage(ObjectNode capture, Path root) {
        ObjectNode result = object();
        if (capture == null || capture.path("unavailable").asBoolean(false)) {
            copyNullable(result, "producer_code_sha256", capture == null ? null : capture.get("producer_code_sha256"));
            result.put("producer_binding_status", "UNBOUND_LEGACY");
            copyNullable(result, "adapter_code_sha256", capture == null ? null : capture.get("adapter_code_sha256"));
            result.put("adapter_binding_status", "UNBOUND_LEGACY"); return result;
        }
        if (!capture.path("partition").isObject() || text(capture.path("partition"), "path").isEmpty()) {
            throw failure("capture lineage requires a partition");
        }
        List<ObjectNode> rows = readJsonl(verifiedRegularPath(root, text(capture.path("partition"), "path"),
                "capture lineage partition"));
        boolean adapterReferencesBound = true;
        boolean sawNormalizedReceipt = false;
        List<String> rowAdapters = uniqueNonEmpty(rows.stream().map(row -> text(row, "adapter_code_sha256")).toList());
        List<String> rowProducers = uniqueNonEmpty(rows.stream().map(row -> text(row, "producer_code_sha256")).toList());
        if (rowAdapters.size() > 1) throw failure("capture lineage has mixed adapter code hashes: " + text(capture, "asset") + "/" + text(capture, "instrument"));
        if (rowProducers.size() > 1) throw failure("capture lineage has mixed producer code hashes: " + text(capture, "asset") + "/" + text(capture, "instrument"));
        List<String> receiptAdapters = new ArrayList<>(), receiptProducers = new ArrayList<>();
        for (ObjectNode summary : concatNodes(capture.path("source_receipts"), capture.path("mark_source_receipts"))) {
            sawNormalizedReceipt = true;
            ObjectNode receipt = verifyNormalizedReceipt(root, summary, "capture lineage normalized source receipt");
            boolean receiptDeclaresAdapter = receipt.hasNonNull("adapter_code_sha256");
            if (receiptDeclaresAdapter) receiptAdapters.add(text(receipt, "adapter_code_sha256"));
            JsonNode adapterReference = receipt.path("request_metadata").path("adapter_code_reference");
            if (!adapterReference.isObject()) adapterReference = receipt.path("adapter_code_reference");
            if (receiptDeclaresAdapter && adapterReference.isObject()) {
                verifyPhysicalByteReference(root, (ObjectNode) adapterReference, javaAdapterCodeSha256(), "normalized receipt adapter code");
            } else {
                adapterReferencesBound = false;
            }
            if (receipt.hasNonNull("producer_code_sha256")) receiptProducers.add(text(receipt, "producer_code_sha256"));
        }
        receiptAdapters = uniqueNonEmpty(receiptAdapters); receiptProducers = uniqueNonEmpty(receiptProducers);
        if (receiptAdapters.size() > 1) throw failure("capture lineage has mixed normalized receipt adapter hashes: " + text(capture, "asset") + "/" + text(capture, "instrument"));
        if (receiptProducers.size() > 1) throw failure("capture lineage has mixed normalized receipt producer hashes: " + text(capture, "asset") + "/" + text(capture, "instrument"));
        String declaredAdapter = emptyToNull(text(capture, "adapter_code_sha256"));
        String declaredProducer = emptyToNull(text(capture, "producer_code_sha256"));
        List<String> observedAdapter = uniqueNonEmpty(concat(List.of(declaredAdapter), rowAdapters, receiptAdapters));
        List<String> observedProducer = uniqueNonEmpty(concat(List.of(declaredProducer), rowProducers, receiptProducers));
        if (observedAdapter.size() > 1) throw failure("capture lineage adapter hash mismatch: " + text(capture, "asset") + "/" + text(capture, "instrument"));
        if (observedProducer.size() > 1) throw failure("capture lineage producer hash mismatch: " + text(capture, "asset") + "/" + text(capture, "instrument"));
        String adapterStatus = sawNormalizedReceipt && adapterReferencesBound && declaredAdapter != null && rowAdapters.size() == 1 && receiptAdapters.size() == 1
                && declaredAdapter.equals(rowAdapters.get(0)) && declaredAdapter.equals(receiptAdapters.get(0)) ? "BOUND"
                : rowAdapters.size() == 1 && receiptAdapters.isEmpty() ? "ROW_ONLY_LEGACY"
                : rowAdapters.isEmpty() && receiptAdapters.size() == 1 ? "RECEIPT_ONLY_LEGACY" : "UNBOUND_LEGACY";
        String producerStatus = declaredProducer != null && rowProducers.size() == 1 && receiptProducers.size() == 1
                && declaredProducer.equals(rowProducers.get(0)) && declaredProducer.equals(receiptProducers.get(0))
                ? "BOUND" : "UNBOUND_LEGACY";
        if (observedProducer.isEmpty()) result.putNull("producer_code_sha256"); else result.put("producer_code_sha256", observedProducer.get(0));
        result.put("producer_binding_status", producerStatus);
        if (observedAdapter.isEmpty()) result.putNull("adapter_code_sha256"); else result.put("adapter_code_sha256", observedAdapter.get(0));
        return result.put("adapter_binding_status", adapterStatus);
    }

    public static boolean verifyAuthoritativeStaging(ObjectNode options) {
        ObjectNode manifest = requiredObject(options, "manifest"); Path root = requiredPath(options, "root");
        String schema = text(manifest, "schema");
        if (!Set.of(DATA_V5.get("acquisition"), DATA_V5.get("hydration")).contains(schema)) {
            throw failure("authoritative staging verifier requires an acquisition or hydration manifest");
        }
        assertOwnHash(manifest, schema, "authoritative staging manifest");
        if (manifest.path("fixture_only").asBoolean(false) && !options.path("allowFixture").asBoolean(false)) {
            throw failure("fixture-only staging evidence cannot enter an authoritative research boundary");
        }
        String declaredPlan = text(options, "planSha256");
        if (!declaredPlan.isEmpty() && !declaredPlan.equals(text(manifest, "plan_sha256"))) {
            throw failure("staging manifest is bound to a different plan");
        }
        ObjectNode plan = options.path("plan").isObject() ? (ObjectNode) options.path("plan") : null;
        Map<String, ObjectNode> planSeries = new HashMap<>();
        if (plan != null) {
            validatePlan(plan);
            if (!text(plan, "content_sha256").equals(text(manifest, "plan_sha256"))) {
                throw failure("staging manifest is bound to a different physical plan");
            }
            for (ObjectNode series : objects(plan.path("series"))) planSeries.put(seriesKey(series), series);
        }
        if (options.hasNonNull("envelopeSha256") && !text(options, "envelopeSha256").equals(text(manifest, "envelope_sha256"))) {
            throw failure("opportunity hydration is bound to a different envelope");
        }
        if (options.hasNonNull("candidateSetSha256") && !text(options, "candidateSetSha256").equals(text(manifest, "candidate_set_sha256"))) {
            throw failure("opportunity hydration is bound to a different candidate set");
        }
        List<ObjectNode> captures = objects(manifest.path("captures"));
        for (ObjectNode capture : captures) {
            ObjectNode series = plan == null ? null : planSeries.get(seriesKey(capture));
            if (plan != null && series == null) throw failure("acquisition capture is not present in the frozen plan: " + seriesKey(capture));
            verifyCaptureCustody(capture, root, series, text(manifest, "plan_sha256"));
        }
        if (plan != null && planSeries.size() != captures.size()) throw failure("acquisition capture inventory differs from the frozen plan series");
        if (options.path("requireComplete").asBoolean(false)) {
            if (!"STAGING_COMPLETE".equals(text(manifest, "status")) || !"STAGING".equals(text(manifest, "storage_role"))
                    || !"JSONL".equals(text(manifest, "staging_format")) || manifest.path("authoritative").asBoolean(true)) {
                throw failure("authoritative source chains require a complete non-authoritative JSONL acquisition or hydration manifest");
            }
            List<ObjectNode> required = captures.stream().filter(row -> !row.has("required") || row.path("required").asBoolean()).toList();
            if (captures.isEmpty() || required.isEmpty() || required.stream().anyMatch(row -> !captureComplete(row)
                    || !row.path("partition").isObject() || objects(row.path("source_receipts")).isEmpty())) {
                throw failure("completed source chain contains an empty, unavailable, or incomplete required capture");
            }
            if (DATA_V5.get("acquisition").equals(schema)) verifyCompletionTuple(manifest, captures);
            if (DATA_V5.get("hydration").equals(schema) && !manifest.path("hydrated_before_outcomes").asBoolean(false)) {
                throw failure("opportunity hydration is not frozen before outcomes");
            }
        }
        List<String> declaredPaths = uniqueSortedTextsOrEmpty(manifest.get("source_receipts"));
        List<String> discoveredPaths = captures.stream().flatMap(capture -> concatNodes(capture.path("source_receipts"), capture.path("mark_source_receipts")).stream())
                .map(receipt -> text(receipt, "path")).filter(value -> !value.isEmpty()).distinct().sorted().toList();
        if (!declaredPaths.equals(discoveredPaths)) throw failure("staging manifest source receipt inventory is not reconciled with captures");
        List<String> declaredContent = uniqueSortedTextsOrEmpty(manifest.get("source_receipt_sha256"));
        List<String> discoveredContent = captures.stream().flatMap(capture -> concatNodes(capture.path("source_receipts"), capture.path("mark_source_receipts")).stream())
                .map(receipt -> textOr(first(receipt, "content_sha256", "sha256"), "")).filter(value -> !value.isEmpty()).distinct().sorted().toList();
        if (!declaredContent.isEmpty() && !declaredContent.equals(discoveredContent)) throw failure("staging manifest normalized receipt hashes are not reconciled with captures");
        return true;
    }

    private static void verifyCaptureCustody(ObjectNode capture, Path root, ObjectNode series, String planSha) {
        if (capture.path("unavailable").asBoolean(false)) {
            if (series != null && (capture.has("partition") || !objects(capture.path("source_receipts")).isEmpty())) {
                throw failure("unavailable acquisition capture claims physical custody: " + seriesKey(series));
            }
            return;
        }
        ObjectNode partition = requiredObject(capture, "partition");
        if (!"JSONL".equals(text(partition, "format")) || !"STAGING".equals(text(partition, "storage_role"))
                || partition.path("authoritative").asBoolean(true)) throw failure("capture staging partition metadata is invalid");
        byte[] bytes = readPhysical(root, text(partition, "path"), "staging partition");
        if (!hash(bytes).equals(text(partition, "sha256")) || bytes.length != partition.path("bytes").asLong(-1)) {
            throw failure("staging partition is missing or tampered: " + text(partition, "path"));
        }
        List<ObjectNode> rows = readJsonlBytes(bytes, "staging partition");
        if (partition.path("row_count").asLong(-1) != rows.size()) throw failure("staging partition row_count differs from physical rows: " + text(partition, "path"));
        if (capture.hasNonNull("adapter_code_sha256") && !javaAdapterCodeSha256().equals(text(capture, "adapter_code_sha256"))) throw failure("capture adapter provenance is legacy");
        if (series != null) verifyCaptureSeriesBinding(capture, series, planSha);
        verifyAcquisitionPartitionRows(capture, rows, series);
        List<ObjectNode> normalized = new ArrayList<>();
        for (ObjectNode summary : objects(capture.path("source_receipts"))) {
            ObjectNode receipt = verifyNormalizedReceipt(root, summary);
            if (receipt.hasNonNull("adapter_code_sha256")) {
                if (!javaAdapterCodeSha256().equals(text(receipt, "adapter_code_sha256"))) throw failure("normalized receipt adapter provenance is legacy");
                JsonNode adapterReference = receipt.path("request_metadata").path("adapter_code_reference");
                if (!adapterReference.isObject()) adapterReference = receipt.path("adapter_code_reference");
                if (adapterReference.isObject()) verifyPhysicalByteReference(root, (ObjectNode) adapterReference, javaAdapterCodeSha256(), "normalized receipt adapter code");
            }
            normalized.add(receipt);
        }
        if ((series != null || capture.hasNonNull("series_sha256")) && normalized.isEmpty()) {
            throw failure("acquisition capture has no identity-bound normalized receipt: " + seriesKey(series == null ? capture : series));
        }
        if (capture.path("mark_partition").isObject()) {
            ObjectNode mark = (ObjectNode) capture.path("mark_partition"); byte[] markBytes = readPhysical(root, text(mark, "path"), "mark staging partition");
            if (!hash(markBytes).equals(text(mark, "sha256")) || markBytes.length != mark.path("bytes").asLong(-1)) {
                throw failure("mark staging partition is missing or tampered: " + text(mark, "path"));
            }
            for (ObjectNode summary : objects(capture.path("mark_source_receipts"))) {
                ObjectNode receipt = verifyNormalizedReceipt(root, summary, "mark normalized source receipt");
                if (receipt.hasNonNull("adapter_code_sha256")) {
                    if (!javaAdapterCodeSha256().equals(text(receipt, "adapter_code_sha256"))) throw failure("mark receipt adapter provenance is legacy");
                    JsonNode adapterReference = receipt.path("request_metadata").path("adapter_code_reference");
                    if (!adapterReference.isObject()) adapterReference = receipt.path("adapter_code_reference");
                    if (adapterReference.isObject()) verifyPhysicalByteReference(root, (ObjectNode) adapterReference, javaAdapterCodeSha256(), "mark receipt adapter code");
                }
                normalized.add(receipt);
            }
        }
        List<String> summaryBytes = concatNodes(capture.path("source_receipts"), capture.path("mark_source_receipts")).stream()
                .flatMap(summary -> hashInventory(summary.get("byte_sha256")).stream()).sorted().toList();
        List<String> rawBytes = normalized.stream().flatMap(receipt -> objects(receipt.path("raw_receipts")).stream())
                .map(raw -> text(raw, "byte_sha256")).sorted().toList();
        if (summaryBytes.isEmpty() || rawBytes.isEmpty() || !summaryBytes.equals(rawBytes)) {
            throw failure("capture raw receipt inventory is not bound: " + text(capture, "asset") + "/" + text(capture, "instrument"));
        }
        if (series == null && capture.hasNonNull("execution_start") && capture.hasNonNull("execution_end")) {
            verifyHydrationPartitionAgainstRaw(capture, root, planSha, false);
            if (capture.path("mark_partition").isObject()) verifyHydrationPartitionAgainstRaw(capture, root, planSha, true);
        }
    }

    /**
     * Reconstructs a hydrated partition from every retained response page and
     * compares the adapter's normalized rows byte-for-byte with the reopened
     * partition.  Hashing the partition alone is insufficient: a self-
     * consistent forged JSONL file must not survive a later promotion.
     */
    private static void verifyHydrationPartitionAgainstRaw(ObjectNode capture, Path root,
            String planSha, boolean mark) {
        ObjectNode series = object().put("asset", text(capture, "asset"))
                .put("venue", "BINANCE")
                .put("instrument", mark ? "BINANCE_USDM_PERPETUAL_MARK" : text(capture, "instrument"))
                .put("symbol", text(capture, "symbol")).put("interval", "1m")
                .put("series_type", mark ? "mark_bars" : "signal_bars")
                .put("series_role", mark ? "MARK" : "PRICE")
                .put("expected_step_ms", ONE_MINUTE)
                .put("start_at", text(capture, "execution_start"))
                .put("end_at", text(capture, "execution_end"));
        ObjectNode receiptCapture = capture.deepCopy();
        if (mark) receiptCapture.set("source_receipts", array());
        else receiptCapture.set("mark_source_receipts", array());
        ReplayReceiptCustody custody = replaySourceReceipts(receiptCapture, series, root, planSha);
        long capturedMillis = custody.normalized().stream().filter(value -> value.hasNonNull("captured_at"))
                .mapToLong(value -> time(value.get("captured_at"))).max()
                .orElseThrow(() -> failure("hydration replay receipt has no valid captured_at: " + seriesKey(series)));
        String capturedAt = iso(capturedMillis);
        long start = time(series.get("start_at")), end = time(series.get("end_at"));
        List<ObjectNode> replayedRows;
        if ("BINANCE_USDM_DATED_FUTURE".equals(text(series, "instrument"))) {
            Map<String, byte[]> archives = replayArchiveResponses(series, custody, root);
            RawReplayTransport transport = new RawReplayTransport(series, List.of(), archives, start, end, capturedAt);
            PublicDataAdapters.BackfillResult result = PublicDataAdapters.backfillBinanceDatedKlineArchives(
                    new PublicDataAdapters.ArchiveBackfillOptions(text(series, "asset"), text(series, "symbol"),
                            "1m", start, end, 10_000, new PublicDataAdapters.HttpOptions(transport, capturedAt, true, 3, 250),
                            root, null, null, 2, false));
            transport.assertFullyConsumed();
            replayedRows = result.rows();
        } else {
            List<ReplayPage> pages = replayRestPages(series, custody, root, capturedAt);
            RawReplayTransport transport = new RawReplayTransport(series, pages, Map.of(), start, end, capturedAt);
            PublicDataAdapters.BackfillResult result = mark
                    ? PublicDataAdapters.backfillBinanceMarkPriceOhlc(new PublicDataAdapters.OhlcOptions(
                            text(series, "asset"), text(series, "symbol"), start, end, "1m", 1_000, true,
                            new PublicDataAdapters.HttpOptions(transport, capturedAt, true, 3, 250)),
                            start, end, 1_000, 1_000, 50_000_000, 0)
                    : PublicDataAdapters.backfillBinanceOhlc(new PublicDataAdapters.OhlcOptions(
                            text(series, "asset"), text(series, "symbol"), start, end, "1m", 1_000,
                            !"BINANCE_SPOT".equals(text(series, "instrument")),
                            new PublicDataAdapters.HttpOptions(transport, capturedAt, true, 3, 250)),
                            start, end, 1_000, 1_000, 50_000_000, 0);
            transport.assertFullyConsumed();
            replayedRows = result.rows();
        }
        List<ObjectNode> normalized = bindHydrationRowsToSeries(replayedRows.stream()
                .filter(row -> rowTime(row) >= start && rowTime(row) <= end).toList(), series,
                mark ? text(capture, "window_sha256") : null);
        Path partitionPath = verifiedRegularPath(root,
                text((ObjectNode) (mark ? capture.path("mark_partition") : capture.path("partition")), "path"),
                mark ? "hydration mark partition" : "hydration partition");
        List<ObjectNode> physical = readJsonl(partitionPath);
        if (!stable(array(normalized)).equals(stable(array(physical)))) {
            throw failure("hydration partition differs from deterministic raw-response replay: "
                    + text(capture, "asset") + "/" + text(capture, "instrument") + (mark ? "/MARK" : ""));
        }
    }

    public static String computeSourceDatasetRootSha256(ObjectNode options) {
        ObjectNode manifest = requiredObject(options, "manifest"); Path root = requiredPath(options, "root");
        if (!DATA_V5.get("acquisition").equals(text(manifest, "schema"))) throw failure("source dataset root requires an acquisition manifest");
        ObjectNode verify = object().set("manifest", manifest); verify.put("root", root.toString())
                .put("planSha256", text(manifest, "plan_sha256")).put("requireComplete", true)
                .put("allowFixture", manifest.path("fixture_only").asBoolean(false));
        verifyAuthoritativeStaging(verify);
        ArrayNode captures = physicalManifestInventory(manifest, root, false);
        ObjectNode input = object().put("schema", DATA_V5.get("acquisition"))
                .put("plan_sha256", text(manifest, "plan_sha256")).put("content_sha256", text(manifest, "content_sha256"));
        input.set("captures", captures); return hash(input);
    }

    public static String computeSourceBundleDatasetRootSha256(ObjectNode options) {
        ObjectNode acquisition = requiredObject(options, "acquisition"), hydration = requiredObject(options, "hydration");
        Path root = requiredPath(options, "root");
        if (!DATA_V5.get("acquisition").equals(text(acquisition, "schema"))
                || !DATA_V5.get("hydration").equals(text(hydration, "schema"))) {
            throw failure("source bundle dataset root requires acquisition and opportunity hydration manifests");
        }
        if (!text(acquisition, "plan_sha256").equals(text(hydration, "plan_sha256"))) throw failure("source bundle child plans do not match");
        ObjectNode av = object().set("manifest", acquisition); av.put("root", root.toString()).put("planSha256", text(acquisition, "plan_sha256")).put("requireComplete", true);
        ObjectNode hv = object().set("manifest", hydration); hv.put("root", root.toString()).put("planSha256", text(hydration, "plan_sha256"))
                .put("requireComplete", true); copyNullable(hv, "envelopeSha256", options.get("envelopeSha256")); copyNullable(hv, "candidateSetSha256", options.get("candidateSetSha256"));
        verifyAuthoritativeStaging(av); verifyAuthoritativeStaging(hv);
        ObjectNode input = object().put("schema", DATA_V5.get("sourceBundle"))
                .put("plan_sha256", text(acquisition, "plan_sha256"))
                .put("acquisition_sha256", text(acquisition, "content_sha256"))
                .put("hydration_sha256", text(hydration, "content_sha256"))
                .put("envelope_sha256", text(hydration, "envelope_sha256"))
                .put("candidate_set_sha256", text(hydration, "candidate_set_sha256"));
        input.set("acquisition", physicalManifestInventory(acquisition, root, true));
        input.set("hydration", physicalManifestInventory(hydration, root, true));
        return hash(input);
    }

    private static ArrayNode physicalManifestInventory(ObjectNode manifest, Path root, boolean bundleShape) {
        List<ObjectNode> inventory = new ArrayList<>();
        for (ObjectNode capture : objects(manifest.path("captures"))) {
            if (capture.path("unavailable").asBoolean(false) || !capture.path("coverage").path("complete").asBoolean(false)) continue;
            ObjectNode row = object().put("asset", text(capture, "asset"));
            if (bundleShape) row.put("venue", textOr(capture.get("venue"), "BINANCE")); else row.put("venue", text(capture, "venue"));
            row.put("instrument", text(capture, "instrument")).put("symbol", text(capture, "symbol"));
            if (bundleShape) {
                copyNullable(row, "interval", nullIfMissing(capture.get("interval"))); copyNullable(row, "series_type", nullIfMissing(capture.get("series_type")));
                copyNullable(row, "execution_start", nullIfMissing(capture.get("execution_start"))); copyNullable(row, "execution_end", nullIfMissing(capture.get("execution_end")));
                copyNullable(row, "envelope_sha256", nullIfMissing(capture.get("envelope_sha256"))); copyNullable(row, "candidate_set_sha256", nullIfMissing(capture.get("candidate_set_sha256")));
            } else {
                row.put("series_type", text(capture, "series_type")).put("series_role", text(capture, "series_role")).put("interval", text(capture, "interval"));
            }
            row.set("partition", inventoryPartition(capture.get("partition")));
            row.set("mark_partition", capture.path("mark_partition").isObject() ? inventoryPartition(capture.get("mark_partition")) : NullNode.instance);
            row.set("receipts", receiptInventory(capture.path("source_receipts")));
            row.set("mark_receipts", receiptInventory(capture.path("mark_source_receipts")));
            ArrayNode raws = array();
            for (ObjectNode summary : concatNodes(capture.path("source_receipts"), capture.path("mark_source_receipts"))) {
                for (ObjectNode raw : objects(verifyNormalizedReceipt(root, summary, "dataset-root normalized source receipt").path("raw_receipts"))) {
                    raws.add(object().put("path", text(raw, "path")).put("byte_sha256", text(raw, "byte_sha256")).put("bytes", raw.path("bytes").asLong()));
                }
            }
            sortArray(raws, Comparator.comparing(node -> text(node, "path"))); row.set("raw_receipts", raws);
            row.set("coverage", capture.path("coverage").deepCopy());
            row.set("mark_coverage", capture.has("mark_coverage") ? capture.path("mark_coverage").deepCopy() : NullNode.instance);
            inventory.add(row);
        }
        inventory.sort(Comparator.comparing(StrategyResearchDataV5::stable)); return array(inventory);
    }

    public static ObjectNode makeSourceBundleManifest(ObjectNode options) {
        Path root = requiredPath(options, "root"); String planSha = requireSha(text(options, "planSha256"), "source bundle plan_sha256");
        String envelopeSha = requireSha(text(options, "envelopeSha256"), "source bundle envelope_sha256");
        String candidateSha = requireSha(text(options, "candidateSetSha256"), "source bundle candidate_set_sha256");
        ObjectNode acquisition = requiredObject(options, "acquisition"), hydration = requiredObject(options, "hydration");
        ObjectNode acquisitionRef = options.path("acquisitionReference").isObject() ? (ObjectNode) options.path("acquisitionReference").deepCopy()
                : persistPhysicalJsonInput(root, acquisition, text(acquisition, "content_sha256"), "source-bundle-acquisition");
        ObjectNode hydrationRef = options.path("hydrationReference").isObject() ? (ObjectNode) options.path("hydrationReference").deepCopy()
                : persistPhysicalJsonInput(root, hydration, text(hydration, "content_sha256"), "source-bundle-hydration");
        ObjectNode acquisitionValue = verifyPhysicalJsonReference(root, acquisitionRef, text(acquisitionRef, "content_sha256"), "source bundle acquisition manifest");
        ObjectNode hydrationValue = verifyPhysicalJsonReference(root, hydrationRef, text(hydrationRef, "content_sha256"), "source bundle opportunity hydration manifest");
        if (!planSha.equals(text(acquisitionValue, "plan_sha256")) || !planSha.equals(text(hydrationValue, "plan_sha256"))
                || !envelopeSha.equals(text(hydrationValue, "envelope_sha256")) || !candidateSha.equals(text(hydrationValue, "candidate_set_sha256"))) {
            throw failure("source bundle child manifest binding is invalid");
        }
        ObjectNode rootOptions = object().set("acquisition", acquisitionValue); rootOptions.set("hydration", hydrationValue);
        rootOptions.put("root", root.toString()).put("envelopeSha256", envelopeSha).put("candidateSetSha256", candidateSha);
        ObjectNode value = object().put("schema", DATA_V5.get("sourceBundle")).put("version", 1)
                .put("status", "VERIFIED_COMPLETE").put("plan_sha256", planSha).put("candidate_set_sha256", candidateSha)
                .put("envelope_sha256", envelopeSha).put("acquisition_sha256", text(acquisitionValue, "content_sha256"))
                .put("hydration_sha256", text(hydrationValue, "content_sha256"))
                .put("dataset_root_sha256", computeSourceBundleDatasetRootSha256(rootOptions))
                .put("root_reference", portableReference(root, text(options, "rootReference")))
                .put("storage_role", "SOURCE_BUNDLE").put("authoritative", false);
        value.set("acquisition_reference", acquisitionRef); value.set("hydration_reference", hydrationRef);
        value.set("limitations", strings(uniqueSortedTextsOrEmpty(options.get("limitations"))));
        ObjectNode bundle = withHash(value);
        String outputPath = textOr(options.get("outputPath"), "lineage/source-bundles/" + text(bundle, "content_sha256") + ".json");
        writeContentAddressed(root, outputPath, prettyBytes(bundle), "source bundle manifest");
        return bundle;
    }

    public static ObjectNode verifySourceBundleManifest(ObjectNode options) {
        Path root = requiredPath(options, "root"); ObjectNode reference = requiredObject(options, "reference");
        String expected = textOr(options.get("expectedContentSha256"), text(reference, "content_sha256"));
        ObjectNode bundle = verifyPhysicalJsonReference(root, reference, expected, "source bundle manifest");
        if (!DATA_V5.get("sourceBundle").equals(text(bundle, "schema"))) throw failure("source bundle verification received a non-bundle manifest");
        ObjectNode verify = object().put("root", root.toString()).set("reference", reference);
        verify.put("expectedContentSha256", expected).put("planSha256", textOr(options.get("planSha256"), text(bundle, "plan_sha256")))
                .put("label", "source bundle manifest");
        return verifyAuthoritativeSourceChain(verify);
    }

    /* ------------------------------------------------------------------ */
    /* PIT feature production and authoritative outcome adapter            */
    /* ------------------------------------------------------------------ */

    public static ArrayNode deriveFeatureRowsFromRaw(ObjectNode options) {
        return deriveFeatureRowsFromRaw((ArrayNode) field(options, "rawRows"), options);
    }

    public static ArrayNode deriveFeatureRowsFromRaw(ArrayNode rawRows, ObjectNode options) {
        return deriveFeatureRowsFromRaw(rawRows, options, Map.of(), Map.of());
    }

    private static ArrayNode deriveFeatureRowsFromRaw(ArrayNode rawRows, ObjectNode options,
            Map<ObjectNode, ObjectNode> primaryCaptures, Map<ObjectNode, ObjectNode> contextCaptures) {
        if (rawRows == null) throw failure("FEATURE physical producer input must be an array");
        ObjectNode registryNode = requiredObject(options, "predictorRegistry");
        LinkedHashMap<String, ObjectNode> registry = validatePredictorRegistry(registryNode);
        ObjectNode capture = options.path("capture").isObject() ? (ObjectNode) options.path("capture") : object();
        List<ObjectNode> primary = objects(rawRows), context = objects(options.path("contextRows"));
        Map<String, List<FeatureObservation>> histories = new HashMap<>();
        for (ObjectNode raw : concat(primary, context)) {
            boolean isPrimary = primary.contains(raw);
            ObjectNode rowCapture = isPrimary ? primaryCaptures.getOrDefault(raw, capture) : contextCaptures.getOrDefault(raw, capture);
            rejectRawDerivedFields(raw, isPrimary ? "FEATURE" : "CONTEXT", isPrimary ? registry.keySet() : Set.of());
            FeatureObservation observation = featureObservation(raw, rowCapture);
            if (!observation.closed) throw failure("FEATURE raw input contains an uncompleted observation");
            histories.computeIfAbsent(observation.series, ignored -> new ArrayList<>()).add(observation);
        }
        for (var entry : histories.entrySet()) {
            entry.getValue().sort(Comparator.comparingLong(FeatureObservation::event));
            if (entry.getValue().stream().map(FeatureObservation::event).distinct().count() != entry.getValue().size()) {
                throw failure("PIT source series contains duplicate completed observations: " + entry.getKey());
            }
        }
        List<ObjectNode> output = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (ObjectNode raw : primary) {
            ObjectNode rowCapture = primaryCaptures.getOrDefault(raw, capture);
            String identity = rawIdentity(raw, rowCapture, true);
            if (!seen.add(identity)) throw failure("FEATURE raw input has duplicate physical identity: " + identity);
            long event = rowTime(raw), decision = completedDecisionBoundary(raw, rowCapture), available = rowAvailability(raw);
            if (available > decision) throw failure("FEATURE raw input is not available at its completed decision boundary");
            String interval = textOr(first(raw, "timeframe", "interval"), textOr(rowCapture.get("interval"), "4h")).toLowerCase(Locale.ROOT);
            long step = "event".equals(interval) ? 0 : timeframeMilliseconds(interval);
            Long close = raw.hasNonNull("close_time") ? time(raw.get("close_time")) : null;
            boolean irregular = close != null && step > 0 && close < decision - 1_000;
            ObjectNode signalSeed = object().put("producer", "FEATURE").put("identity", identity);
            String signalId = "sig-" + hash(signalSeed).substring(0, 24);
            ObjectNode episodeSeed = object().put("signalId", signalId).put("identity", identity);
            String episodeId = "ep-" + hash(episodeSeed).substring(0, 24);
            ObjectNode feature = object().put("asset", predictorAsset(raw)).put("venue", text(raw, "venue").toUpperCase(Locale.ROOT))
                    .put("instrument", text(raw, "instrument").toUpperCase(Locale.ROOT))
                    .put("symbol", text(raw, "symbol").toUpperCase(Locale.ROOT))
                    .put("timeframe", textOr(first(raw, "timeframe", "interval"), textOr(rowCapture.get("interval"), "4h")))
                    .put("event_time", iso(first(raw, "event_time", "open_time", "decision_time"))).put("decision_time", iso(decision))
                    .put("availability_time", iso(available)).put("signal_eligible", !irregular)
                    .put("signal_id", signalId).put("episode_id", episodeId);
            long featureAvailable = available;
            for (var entry : registry.entrySet()) {
                PredictorValue result = evaluatePredictor(raw, rowCapture, entry.getValue(), histories, decision, event);
                if (result.value == null) feature.putNull(entry.getKey()); else feature.set(entry.getKey(), result.value);
                featureAvailable = Math.max(featureAvailable, result.availability);
                if (!result.sufficient) feature.put("signal_eligible", false);
            }
            if (featureAvailable > decision) throw failure("FEATURE predictor input is not available at its completed decision boundary");
            feature.put("availability_time", iso(featureAvailable)); output.add(feature);
        }
        output.sort(Comparator.comparing(StrategyResearchDataV5::roleIdentityKey)); return array(output);
    }

    private record FeatureObservation(ObjectNode raw, long event, long availability, boolean closed,
            boolean irregular, String series, ObjectNode capture) {}
    private record PredictorValue(JsonNode value, long availability, boolean sufficient) {}

    private static FeatureObservation featureObservation(ObjectNode raw, ObjectNode capture) {
        long event = rowTime(raw), available = rowAvailability(raw);
        String interval = textOr(first(raw, "timeframe", "interval"), text(capture, "interval")).toLowerCase(Locale.ROOT);
        long step = interval.isEmpty() || "event".equals(interval) ? 0 : timeframeMilliseconds(interval);
        long boundary = step > 0 ? event + step : event;
        Long close = raw.hasNonNull("close_time") ? time(raw.get("close_time")) : null;
        return new FeatureObservation(raw, event, available, !raw.has("is_closed") || raw.path("is_closed").asBoolean(),
                close != null && step > 0 && close < boundary - 1_000, predictorSeriesIdentity(raw, capture), capture);
    }

    private static PredictorValue evaluatePredictor(ObjectNode primary, ObjectNode capture, ObjectNode predictor,
            Map<String, List<FeatureObservation>> histories, long decision, long event) {
        ObjectNode recipe = predictor.path("recipe").isObject() ? normalizePredictorRecipe(predictor, text(predictor, "id")) : null;
        if (recipe == null) {
            JsonNode value = predictorSourceValue(primary, text(predictor, "source_field"), "FEATURE");
            return new PredictorValue(coercePredictor(value, predictor, "FEATURE"), rowAvailability(primary), true);
        }
        String scope = text(recipe, "series_scope"); List<FeatureObservation> history = new ArrayList<>();
        long cutoff = event; Long staleness = null;
        if ("SAME_ASSET_FUNDING_SERIES".equals(scope)) {
            for (List<FeatureObservation> values : histories.values()) if (!values.isEmpty()
                    && sameAssetFunding(primary, values.get(0))) history.addAll(values);
            history = history.stream().map(value -> new FeatureObservation(value.raw,
                    time(value.raw.get("settlement_slot")), value.availability, value.closed, value.irregular,
                    value.series, value.capture)).sorted(Comparator.comparingLong(FeatureObservation::event)).toList();
            cutoff = decision; staleness = integer(recipe.get("max_staleness_ms"), "max_staleness_ms");
        } else if ("EXPLICIT_REFERENCE_SERIES".equals(scope)) {
            ObjectNode reference = (ObjectNode) recipe.path("reference_series"); String declared = text(recipe, "source_series").trim().toLowerCase(Locale.ROOT);
            String predictorFamily = text(predictor, "source_family").trim().toLowerCase(Locale.ROOT);
            for (var entry : histories.entrySet()) if (referenceSeriesMatches(reference, entry.getKey())) {
                for (FeatureObservation observation : entry.getValue()) {
                    ObjectNode source = observation.raw;
                    String actualType = textOr(first(source, "series_type", "series_role"), "").toUpperCase(Locale.ROOT);
                    String actualId = text(source, "series_id").toUpperCase(Locale.ROOT);
                    boolean typeMatches = !reference.hasNonNull("series_type") || text(reference, "series_type").toUpperCase(Locale.ROOT).equals(actualType);
                    boolean idMatches = !reference.hasNonNull("series_id") || text(reference, "series_id").toUpperCase(Locale.ROOT).equals(actualId);
                    boolean seriesMatches = declared.isEmpty() || declared.equals("same_series") || declared.equals(predictorFamily)
                            || predictorSourceAliases(source, observation.capture).contains(declared);
                    if (typeMatches && idMatches && seriesMatches) history.add(observation);
                }
            }
            history.sort(Comparator.comparingLong(FeatureObservation::event)); cutoff = decision;
            staleness = integer(recipe.get("max_staleness_ms"), "max_staleness_ms");
        } else {
            if (!predictorSourceSeriesMatches(primary, predictor, recipe, capture)) throw failure("predictor " + text(predictor, "id") + " source series is not bound to the physical source series");
            history.addAll(histories.getOrDefault(predictorSeriesIdentity(primary, capture), List.of()));
        }
        long lookbackMs = predictor.path("lookback_ms").asLong(); final long cutoffValue = cutoff;
        List<FeatureObservation> eligible = history.stream().filter(value -> value.closed && !value.irregular)
                .filter(value -> value.event <= cutoffValue && (lookbackMs <= 0 || value.event >= cutoffValue - lookbackMs))
                .filter(value -> "SAME_ASSET_FUNDING_SERIES".equals(scope) ? value.availability < decision : value.availability <= decision)
                .sorted(Comparator.comparingLong(FeatureObservation::event)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if ("EXCLUDE_CURRENT_COMPLETED".equals(text(recipe, "current_observation_policy"))) eligible.removeIf(value -> value.event == cutoffValue);
        int trim = (int) (recipe.path("lag_bars").asLong(0) + recipe.path("excluded_window_bars").asLong(0));
        while (trim-- > 0 && !eligible.isEmpty()) eligible.remove(eligible.size() - 1);
        int lookbackBars = recipe.path("lookback_bars").asInt(); String kind = text(recipe, "kind");
        int required = Set.of("RETURN", "RSI").contains(kind) ? lookbackBars + 1 : Math.max(1, lookbackBars);
        if (eligible.size() > required) eligible = new ArrayList<>(eligible.subList(eligible.size() - required, eligible.size()));
        boolean sufficient = eligible.size() >= recipe.path("min_history").asInt()
                && ("FIELD".equals(kind) || eligible.size() >= required);
        if (staleness != null && !eligible.isEmpty() && decision - eligible.get(eligible.size() - 1).event > staleness) sufficient = false;
        if (!sufficient) return new PredictorValue(null, rowAvailability(primary), false);
        List<Double> values = eligible.stream().map(value -> number(predictorSourceValue(value.raw,
                text(recipe, "source_field"), "FEATURE"))).toList();
        double result = switch (kind) {
            case "FIELD" -> values.get(values.size() - 1);
            case "RETURN" -> { double base = values.get(0); if (base == 0) throw failure("FEATURE predictor " + text(predictor, "id") + " return denominator is zero"); yield values.get(values.size() - 1) / base - 1; }
            case "SMA" -> values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            case "STDDEV_ZSCORE" -> { double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow(); double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElseThrow(); double deviation = Math.sqrt(variance); yield deviation == 0 ? 0 : (values.get(values.size() - 1) - mean) / deviation; }
            case "RSI" -> wilderRsi(values, lookbackBars, text(predictor, "id"));
            default -> throw failure("predictor " + text(predictor, "id") + " recipe kind is unsupported");
        };
        long available = eligible.stream().mapToLong(FeatureObservation::availability).max().orElse(rowAvailability(primary));
        return new PredictorValue(coercePredictor(JSON.getNodeFactory().numberNode(result), predictor, "FEATURE"), available, true);
    }

    private static double wilderRsi(List<Double> values, int period, String id) {
        if (values.size() - 1 < period) throw failure("FEATURE predictor " + id + " lacks the declared Wilder RSI observation window");
        double gain = 0, loss = 0;
        for (int index = 1; index <= period; index++) { double delta = values.get(index) - values.get(index - 1); gain += Math.max(0, delta); loss += Math.max(0, -delta); }
        gain /= period; loss /= period;
        for (int index = period + 1; index < values.size(); index++) { double delta = values.get(index) - values.get(index - 1); gain = (gain * (period - 1) + Math.max(0, delta)) / period; loss = (loss * (period - 1) + Math.max(0, -delta)) / period; }
        if (gain == 0 && loss == 0) return 50; if (loss == 0) return 100; if (gain == 0) return 0; return 100 - 100 / (1 + gain / loss);
    }

    private static JsonNode predictorSourceValue(ObjectNode raw, String path, String role) {
        JsonNode value = raw;
        for (String component : path.split("\\.")) value = value != null && value.isObject() ? value.get(component) : null;
        if (value == null && raw.has(path) && !RAW_ROLE_DERIVED_FIELDS.contains(path)) value = raw.get(path);
        if (value == null) throw failure(role + " registered predictor source field is missing: " + path); return value;
    }
    private static JsonNode coercePredictor(JsonNode value, ObjectNode predictor, String role) {
        String scalar = text(predictor, "scalar_type"), id = text(predictor, "id");
        if ("boolean".equals(scalar)) { if (!value.isBoolean()) throw failure(role + " predictor " + id + " is not boolean"); return value.deepCopy(); }
        double result = number(value); if ("integer".equals(scalar)) { if (result != Math.rint(result)) throw failure(role + " predictor " + id + " is not an integer"); return JSON.getNodeFactory().numberNode((long) result); }
        return JSON.getNodeFactory().numberNode(result);
    }
    private static String predictorAsset(ObjectNode row) {
        String value = textOr(first(row, "asset", "series_asset"), "").toLowerCase(Locale.ROOT);
        if (!SAFE_ASSET.matcher(value).matches()) throw failure("predictor source series has an invalid asset identity"); return value;
    }
    private static String predictorSeriesIdentity(ObjectNode row, ObjectNode capture) {
        String discriminator = textOr(first(row, "series_id", "series_type", "series_role"),
                textOr(first(capture, "series_id", "series_type", "series_role"),
                        textOr(first(row, "interval", "timeframe"), textOr(first(capture, "interval", "timeframe"), ""))));
        return predictorAsset(row) + "|" + text(row, "venue").toUpperCase(Locale.ROOT) + "|"
                + text(row, "instrument").toUpperCase(Locale.ROOT) + "|" + text(row, "symbol").toUpperCase(Locale.ROOT)
                + "|" + discriminator.toUpperCase(Locale.ROOT);
    }
    private static boolean referenceSeriesMatches(ObjectNode reference, String key) {
        String base = text(reference, "asset").toLowerCase(Locale.ROOT) + "|" + text(reference, "venue").toUpperCase(Locale.ROOT)
                + "|" + text(reference, "instrument").toUpperCase(Locale.ROOT) + "|" + text(reference, "symbol").toUpperCase(Locale.ROOT);
        return reference.hasNonNull("series_id") ? key.equals(base + "|" + text(reference, "series_id").toUpperCase(Locale.ROOT)) : key.startsWith(base + "|");
    }
    private static List<String> predictorSourceAliases(ObjectNode row, ObjectNode capture) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : new JsonNode[]{row.get("series_id"), row.get("series_type"), row.get("series_role"), row.get("interval"), row.get("timeframe"),
                capture == null ? null : capture.get("series_id"), capture == null ? null : capture.get("series_type"), capture == null ? null : capture.get("series_role"),
                capture == null ? null : capture.get("interval"), capture == null ? null : capture.get("timeframe")}) if (defined(value)) values.add(textValue(value).trim().toLowerCase(Locale.ROOT));
        return values;
    }
    private static boolean predictorSourceSeriesMatches(ObjectNode row, ObjectNode predictor, ObjectNode recipe, ObjectNode capture) {
        String declared = text(recipe, "source_series").trim().toLowerCase(Locale.ROOT);
        if ("same_series".equals(declared) || "same_asset_venue_instrument_symbol".equals(declared)) return true;
        return predictorSourceAliases(row, capture).contains(declared);
    }
    private static boolean sameAssetFunding(ObjectNode primary, FeatureObservation observation) {
        ObjectNode row = observation.raw; String asset = predictorAsset(primary);
        return predictorAsset(row).equals(asset) && text(row, "venue").equalsIgnoreCase(text(primary, "venue"))
                && text(row, "symbol").equalsIgnoreCase(asset.toUpperCase(Locale.ROOT) + "USDT")
                && "BINANCE_USDM_PERPETUAL".equals(text(row, "instrument").toUpperCase(Locale.ROOT))
                && Set.of("funding", "funding_events").contains(textOr(first(row, "series_id", "series_type", "series_role"), "").toLowerCase(Locale.ROOT));
    }
    private static void rejectRawDerivedFields(ObjectNode row, String role, Set<String> predictorIds) {
        Iterator<String> names = row.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (RAW_ROLE_DERIVED_FIELDS.contains(field) || PRECOMPUTED_EXECUTION.contains(field)) throw failure(role + " raw input contains a loader-derived field: " + field);
            if (OUTCOME_PROVENANCE.matcher(field).find()) throw failure(role + " raw input contains a future/outcome alias: " + field);
            if ("FEATURE".equals(role) && predictorIds.contains(field)) throw failure("FEATURE raw input contains a loader-derived field/pre-authored predictor field: " + field);
            if ("EXECUTION".equals(role) && FORBIDDEN_EXECUTION_INPUT_FIELDS.contains(field)) throw failure("EXECUTION raw input contains caller-supplied sizing/execution field: " + field);
            if (("LABEL".equals(role) || "EXECUTION".equals(role)) && "mark_bars".equals(field)) throw failure(role + " raw input cannot carry a caller-authored mark path");
        }
        for (ObjectNode child : objects(row.path("child_bars"))) {
            Iterator<String> childNames = child.fieldNames();
            while (childNames.hasNext()) if (Pattern.compile("^(net|gross|fee|funding|return|pnl|profit|loss|outcome|resolution|exit)(_|$)", Pattern.CASE_INSENSITIVE).matcher(childNames.next()).find()) {
                throw failure(role + " raw child path contains a derived field");
            }
        }
    }
    private static long completedDecisionBoundary(ObjectNode row, ObjectNode capture) {
        long event = rowTime(row); String interval = textOr(first(row, "timeframe", "interval"), textOr(capture.get("interval"), "4h")).toLowerCase(Locale.ROOT);
        if (row.hasNonNull("decision_time")) {
            long decision = time(row.get("decision_time"));
            if (row.hasNonNull("close_time") && !"event".equals(interval)) {
                long expected = event + timeframeMilliseconds(interval), close = time(row.get("close_time"));
                if (decision != expected || close != expected - 1 && close != expected) throw failure("explicit decision_time does not match the completed bar boundary");
            }
            return decision;
        }
        if ("event".equals(interval)) return event; long boundary = event + timeframeMilliseconds(interval);
        if (row.hasNonNull("close_time") && time(row.get("close_time")) > boundary) throw failure("completed bar close_time is after the declared timeframe boundary");
        return boundary;
    }
    private static String rawIdentity(ObjectNode row, ObjectNode capture, boolean allowEvent) {
        if (text(row, "asset").isEmpty() || text(row, "venue").isEmpty() || text(row, "instrument").isEmpty() || text(row, "symbol").isEmpty()) throw failure("FEATURE raw input lacks exact asset/venue/instrument/symbol identity");
        JsonNode explicit = first(row, "decision_time", "parent_decision_time", "window_decision_time");
        long decision = defined(explicit) ? time(explicit) : allowEvent ? completedDecisionBoundary(row, capture) : Long.MIN_VALUE;
        if (decision == Long.MIN_VALUE) throw failure("FEATURE raw input lacks an exact decision_time identity");
        return predictorAsset(row) + "|" + text(row, "venue").toUpperCase(Locale.ROOT) + "|" + text(row, "instrument").toUpperCase(Locale.ROOT) + "|" + text(row, "symbol").toUpperCase(Locale.ROOT) + "|" + decision;
    }
    private static String roleIdentityKey(ObjectNode row) {
        return text(row, "asset").toLowerCase(Locale.ROOT) + "|" + text(row, "venue").toUpperCase(Locale.ROOT) + "|"
                + text(row, "instrument").toUpperCase(Locale.ROOT) + "|" + text(row, "symbol").toUpperCase(Locale.ROOT) + "|"
                + time(first(row, "decision_time", "event_time", "open_time")) + "|" + textOr(first(row, "series_id", "signal_id", "episode_id"), "");
    }

    public static ObjectNode deriveBoundExecutionOutcome(ObjectNode options) {
        return deriveBoundExecutionOutcome(options, null, null);
    }

    /* Package-private evaluator bridge: the lifecycle token is deliberately
     * not serializable and therefore cannot be carried in the public JSON
     * boundary.  The authoritative evaluator can pass its identity-bound
     * capability directly, while the exported method remains fail-closed. */
    static ObjectNode deriveBoundExecutionOutcome(ObjectNode options, LifecycleTrustService trustService,
            LifecycleTrustService.Token lifecycleToken) {
        ObjectNode feature = requiredObject(options, "feature"), label = requiredObject(options, "label"), execution = requiredObject(options, "execution");
        ObjectNode candidate = options.path("candidate").isObject() ? (ObjectNode) options.path("candidate") : object();
        validateDirectOutcomeIdentities(feature, label, execution);
        long decision = time(first(feature, "decision_time", "event_time"));
        String convention = textOr(first(candidate, "decision_timestamp_convention"), textOr(first(execution, "decision_timestamp_convention"), text(label, "decision_timestamp_convention"))).toUpperCase(Locale.ROOT);
        if (!"COMPLETED_4H_BOUNDARY".equals(convention)) throw failure("execution decision timestamp convention is not explicitly bound to COMPLETED_4H_BOUNDARY");
        String timeframe = textOr(first(candidate, "decision_timeframe"), textOr(first(execution, "decision_timeframe"), text(label, "decision_timeframe"))).toLowerCase(Locale.ROOT);
        if (!"4h".equals(timeframe) || decision % FOUR_HOURS != 0) throw failure("decision time is not the exact completed 4h boundary");
        if (candidate.path("lifecycle").isObject() || execution.path("lifecycle").isObject()
                || "strategy-v5-trade-lifecycle/1".equals(text(candidate, "lifecycle_engine"))
                || "strategy-v5-trade-lifecycle/1".equals(text(execution, "lifecycle_engine"))) {
            return deriveNormalizedLifecycleOutcome(feature, label, execution, candidate, options, trustService, lifecycleToken);
        }
        return deriveLegacyOutcome(feature, label, execution, candidate, options);
    }

    private static ObjectNode deriveLegacyOutcome(ObjectNode feature, ObjectNode label, ObjectNode execution,
            ObjectNode candidate, ObjectNode options) {
        boolean fixtureOnly = options.path("fixtureOnly").asBoolean(false);
        for (String name : List.of("funding_settlements", "funding_debit", "funding_pnl_usd", "funding_amount")) {
            if (execution.has(name)) throw failure("caller-supplied " + name + " is not an authoritative funding input");
        }
        List<OutcomeBar> bars = new ArrayList<>();
        for (ObjectNode source : objects(execution.path("child_bars"))) {
            ObjectNode row = source.deepCopy(); long event = rowTime(row);
            bars.add(new OutcomeBar(row, event, numeric(row.get("open")), numeric(row.get("high")),
                    numeric(row.get("low")), numeric(row.get("close"))));
        }
        bars.sort(Comparator.comparingLong(OutcomeBar::event));
        Set<Long> barTimes = new HashSet<>();
        for (int index = 0; index < bars.size(); index++) {
            OutcomeBar row = bars.get(index);
            if (!barTimes.add(row.event()) || index > 0 && row.event() != bars.get(index - 1).event() + ONE_MINUTE) {
                throw failure("execution path is not dense one-minute data");
            }
            if (rowAvailability(row.raw()) < row.event() + ONE_MINUTE - 1_000) {
                throw failure("execution path contains a bar available before close");
            }
        }
        long decision = time(first(feature, "decision_time", "event_time"));
        String entryPolicy = textOr(first(candidate, "entry_policy"),
                textOr(first(execution, "entry_policy"), textOr(first(label, "entry_policy"), "NEXT_BAR_OPEN"))).toUpperCase(Locale.ROOT);
        long entryDelayBars = "DELAYED_BAR_OPEN".equals(entryPolicy)
                ? integer(firstDefined(candidate.get("entry_delay_bars"), execution.get("entry_delay_bars"), label.get("entry_delay_bars")), "entry_delay_bars") : 0;
        if ("DELAYED_BAR_OPEN".equals(entryPolicy) && entryDelayBars < 1) throw failure("delayed-bar entry policy requires a positive frozen entry_delay_bars");
        if (!Set.of("NEXT_BAR_OPEN", "DELAYED_BAR_OPEN").contains(entryPolicy)) throw failure("unsupported frozen entry policy " + entryPolicy);
        long expectedEntryTime = decision + entryDelayBars * ONE_MINUTE;
        OutcomeBar firstPostBoundary = bars.stream().filter(row -> row.event() >= expectedEntryTime).findFirst().orElse(null);
        OutcomeBar entry = bars.stream().filter(row -> row.event() == expectedEntryTime).findFirst().orElse(null);
        if (entry == null || firstPostBoundary == null || firstPostBoundary.event() != expectedEntryTime || !(entry.open() > 0)) {
            throw failure("execution path lacks the exact contiguous next-bar entry");
        }
        if (label.has("entry_time") && time(label.get("entry_time")) != entry.event()) throw failure("label entry time does not match frozen next-bar policy");
        long resolutionCeiling = time(first(label, "resolution_ceiling_time", "resolution_time", "outcome_time", "exit_time"));
        if (resolutionCeiling <= entry.event()) throw failure("label outcome ceiling is invalid");
        String lifecycleTimeframe = textOr(first(candidate, "lifecycle_timeframe"),
                textOr(first(execution, "lifecycle_timeframe"), text(label, "lifecycle_timeframe")));
        if (lifecycleTimeframe.isEmpty()) throw failure("lifecycle timeframe is required");
        long lifecycleStep = timeframeMilliseconds(lifecycleTimeframe);
        JsonNode explicitMaxNode = firstDefined(candidate.get("max_lifecycle_ms"), execution.get("max_lifecycle_ms"), label.get("max_lifecycle_ms"));
        JsonNode legacyBarsNode = firstDefined(candidate.get("max_lifecycle_bars"), execution.get("max_lifecycle_bars"), label.get("max_lifecycle_bars"));
        long maxLifecycleMs;
        if (defined(explicitMaxNode) && explicitMaxNode.isNumber() && Double.isFinite(explicitMaxNode.asDouble())) {
            maxLifecycleMs = integer(explicitMaxNode, "maximum lifecycle");
        } else if (defined(legacyBarsNode)) {
            maxLifecycleMs = Math.multiplyExact(integer(legacyBarsNode, "maximum lifecycle bars"), lifecycleStep);
        } else throw failure("maximum lifecycle must be explicitly bound in milliseconds");
        if (maxLifecycleMs <= 0) throw failure("maximum lifecycle must be explicitly bound in milliseconds");
        if (defined(legacyBarsNode) && integer(legacyBarsNode, "maximum lifecycle bars") <= 0) throw failure("maximum lifecycle bars is invalid");
        long lifecycleEnd = Math.min(resolutionCeiling, Math.addExact(entry.event(), maxLifecycleMs));
        if (lifecycleEnd <= entry.event()) throw failure("maximum lifecycle ends before entry");
        String instrument = textOr(first(execution, "instrument"), textOr(first(label, "instrument"),
                "spot".equals(text(execution, "instrument_type")) ? "BINANCE_SPOT" : "BINANCE_USDM_PERPETUAL")).toUpperCase(Locale.ROOT);
        if (!Set.of("BINANCE_SPOT", "BINANCE_USDM_PERPETUAL", "BINANCE_USDM_DATED_FUTURE").contains(instrument)) {
            throw failure("unsupported execution instrument " + instrument);
        }
        String asset = predictorAsset(feature), venue = textOr(first(execution, "venue"),
                textOr(first(label, "venue"), textOr(first(feature, "venue"), "BINANCE"))).toUpperCase(Locale.ROOT);
        String symbol = textOr(first(execution, "symbol"), textOr(first(label, "symbol"),
                textOr(first(feature, "symbol"), asset.toUpperCase(Locale.ROOT) + "USDT"))).toUpperCase(Locale.ROOT);
        ObjectNode metadata = options.path("metadata").isObject() ? (ObjectNode) options.path("metadata") : object();
        String metadataRootText = textOr(first(metadata, "source_root", "sourceRoot"), "");
        if (metadataRootText.isEmpty()) for (String name : List.of("contract_spec", "fee_schedule", "execution_model")) {
            if (metadata.path(name).isObject()) { metadataRootText = textOr(first(metadata.path(name), "source_root_reference"), ""); if (!metadataRootText.isEmpty()) break; }
        }
        Path metadataRoot = metadataRootText.isEmpty() ? null : Path.of(metadataRootText).toAbsolutePath().normalize();
        boolean derivative = !"BINANCE_SPOT".equals(instrument);
        String direction = textOr(first(candidate, "direction"), textOr(first(execution, "direction"),
                textOr(first(label, "direction"), "long"))).toLowerCase(Locale.ROOT);
        if (!Set.of("long", "short").contains(direction)) throw failure("execution direction is invalid");
        if (!derivative && "short".equals(direction)) throw failure("short BINANCE_SPOT execution is not supported; bind a derivative instrument");
        List<OutcomeMark> markBars = new ArrayList<>();
        if (derivative) {
            for (ObjectNode source : objects(execution.path("mark_bars"))) {
                ObjectNode row = source.deepCopy(); markBars.add(new OutcomeMark(row, rowTime(row),
                        numeric(row.get("mark_open")), numeric(row.get("mark_high")), numeric(row.get("mark_low")), numeric(row.get("mark_close"))));
            }
            markBars.sort(Comparator.comparingLong(OutcomeMark::event));
            if (markBars.size() != bars.size()) throw failure("derivative execution requires a separate dense mark-price path aligned to trade bars");
            for (int index = 0; index < markBars.size(); index++) {
                OutcomeMark mark = markBars.get(index);
                if (mark.event() != bars.get(index).event() || !(mark.high() > 0) || !(mark.low() > 0) || mark.low() > mark.high()) {
                    throw failure("derivative execution requires a separate dense mark-price path aligned to trade bars");
                }
                if (rowAvailability(mark.raw()) < mark.event() + ONE_MINUTE - 1_000) throw failure("derivative mark path contains a bar available before close");
            }
        }
        ObjectNode exitPolicy = first(candidate, "exit_policy").isObject() ? (ObjectNode) first(candidate, "exit_policy")
                : first(execution, "exit_policy").isObject() ? (ObjectNode) first(execution, "exit_policy") : object().put("type", "TIME_STOP");
        String policyType = text(exitPolicy, "type").toUpperCase(Locale.ROOT);
        String collisionPolicy = textOr(first(exitPolicy, "collision_policy"), "ADVERSE_STOP_FIRST").toUpperCase(Locale.ROOT);
        if (exitPolicy.has("partial") || exitPolicy.has("partials") || exitPolicy.has("ratchet")
                || candidate.has("partial") || candidate.has("partials") || candidate.has("ratchet")) {
            throw failure("partial and ratchet exits require an explicitly bound execution implementation");
        }
        long selectedExitTime = lifecycleEnd; String exitReason = "TIME_STOP"; Double rawExitPrice = null; boolean gapFill = false;
        ObjectNode contractReceipt = boundOutcomeMetadata(objectOrNull(metadata.get("contract_spec")), "CONTRACT_SPEC", true, metadataRoot, fixtureOnly, false);
        ObjectNode contract = outcomeMetadataRecord(contractReceipt, asset, instrument, venue, symbol, entry.event());
        double multiplier = numeric(contract.get("contract_multiplier")); if (!(multiplier > 0)) throw failure("contract multiplier is invalid");
        JsonNode expiryNode = firstDefined(contract.get("expiry"), contract.get("delivery_date"));
        if (defined(expiryNode) && resolutionCeiling > time(expiryNode)) throw failure("execution path extends beyond contract expiry");
        if ("BINANCE_USDM_DATED_FUTURE".equals(instrument)) {
            ObjectNode expiryReceipt = boundOutcomeMetadata(objectOrNull(metadata.get("expiry")), "EXPIRY", true, metadataRoot, fixtureOnly, false);
            ObjectNode expiry = outcomeMetadataRecord(expiryReceipt, asset, instrument, venue, symbol, entry.event());
            if (resolutionCeiling > time(first(expiry, "expiry", "delivery_date"))) throw failure("dated future execution path extends beyond bound settlement expiry");
        }
        ObjectNode executionModelReceipt = boundOutcomeMetadata(objectOrNull(metadata.get("execution_model")), "EXECUTION_MODEL", true, metadataRoot, fixtureOnly, true);
        ObjectNode executionModel = outcomeMetadataRecord(executionModelReceipt, asset, instrument, venue, symbol, entry.event());
        double slippageBps = numeric(executionModel.get("slippage_bps")), impactBps = numeric(executionModel.get("impact_bps"));
        String outagePolicy = text(executionModel, "outage_policy").toUpperCase(Locale.ROOT), gapPolicy = text(executionModel, "gap_policy").toUpperCase(Locale.ROOT);
        if (!Double.isFinite(slippageBps) || slippageBps < 0 || !Double.isFinite(impactBps) || impactBps < 0) throw failure("execution slippage/impact model is invalid");
        if (!"FAIL".equals(outagePolicy)) throw failure("unsupported outage policy " + (outagePolicy.isEmpty() ? "?" : outagePolicy));
        if (!Set.of("FAIL", "FILL_AT_OPEN").contains(gapPolicy)) throw failure("unsupported gap policy " + (gapPolicy.isEmpty() ? "?" : gapPolicy));
        double modeledEntryPrice = "long".equals(direction) ? entry.open() * (1 + (slippageBps + impactBps) / 10_000)
                : entry.open() * (1 - (slippageBps + impactBps) / 10_000);
        if ("TARGET_STOP".equals(policyType)) {
            double stop = numeric(exitPolicy.get("stop_price")), target = numeric(exitPolicy.get("target_price"));
            if (!(stop > 0) || !(target > 0)) throw failure("target/stop exit policy is invalid");
            if (!"ADVERSE_STOP_FIRST".equals(collisionPolicy)) throw failure("only ADVERSE_STOP_FIRST OHLC collision policy is supported");
            for (OutcomeBar row : bars) if (row.event() >= entry.event() && row.event() <= lifecycleEnd) {
                boolean longDirection = "long".equals(direction), hitStop = longDirection ? row.low() <= stop : row.high() >= stop;
                boolean hitTarget = longDirection ? row.high() >= target : row.low() <= target;
                if (!hitStop && !hitTarget) continue;
                boolean gapStop = longDirection ? row.open() <= stop : row.open() >= stop;
                boolean gapTarget = longDirection ? row.open() >= target : row.open() <= target;
                boolean stopFirst = hitStop && (!hitTarget || "ADVERSE_STOP_FIRST".equals(collisionPolicy));
                if ((gapStop || gapTarget) && "FAIL".equals(gapPolicy)) throw failure("execution path contains a gap through a target/stop under FAIL gap policy");
                selectedExitTime = row.event(); gapFill = gapStop || gapTarget;
                exitReason = gapFill ? (stopFirst ? "STOP_GAP_OPEN" : "TARGET_GAP_OPEN") : (stopFirst ? "STOP" : "TARGET");
                rawExitPrice = gapFill ? row.open() : stopFirst ? stop : target; break;
            }
        } else if (!"TIME_STOP".equals(policyType)) throw failure("unsupported exit policy " + policyType);
        long resolution = selectedExitTime;
        if (options.path("envelopeWindow").isObject()) {
            ObjectNode window = (ObjectNode) options.path("envelopeWindow");
            if (entry.event() < time(window.get("execution_start")) || resolution > time(window.get("execution_end"))) throw failure("outcome path escapes frozen opportunity envelope");
        }
        long expectedPathStart = "DELAYED_BAR_OPEN".equals(entryPolicy) ? decision : entry.event();
        if (bars.isEmpty() || bars.get(0).event() != expectedPathStart || bars.get(bars.size() - 1).event() < resolution) {
            throw failure("execution path is truncated or contains pre-entry bars before the declared lifecycle/resolution");
        }
        OutcomeBar exit = bars.stream().filter(row -> row.event() == resolution).findFirst().orElse(null);
        if (exit == null || !(exit.close() > 0)) throw failure("execution path lacks exact policy resolution bar");
        if (rawExitPrice == null) rawExitPrice = exit.close();
        ObjectNode riskContract = objectOrNull(firstDefined(candidate.get("risk_contract"), execution.get("risk_contract")));
        ObjectNode sizingContract = objectOrNull(firstDefined(candidate.get("sizing_contract"), riskContract == null ? null : riskContract.get("sizing_contract")));
        JsonNode suppliedQuantityNode = firstDefined(execution.get("quantity"), label.get("quantity")); double quantity;
        if (defined(suppliedQuantityNode)) {
            if (!fixtureOnly) throw failure("caller-supplied execution/label quantity is not authoritative");
            quantity = numeric(suppliedQuantityNode); if (!Double.isFinite(quantity) || !(Math.abs(quantity) > 0)) throw failure("execution quantity is invalid");
        } else {
            if (riskContract == null || !isSha(text(riskContract, "precommit_sha256")) || !isSha(text(riskContract, "evaluator_spec_sha256"))) {
                throw failure("authoritative execution requires a hash-bound sizing contract");
            }
            if ("TARGET_STOP".equals(policyType)) {
                if (!"FIXED_RISK_BUDGET_USD".equals(text(riskContract, "mode"))) throw failure("target-stop sizing requires a fixed-risk-budget contract");
                double budget = numeric(riskContract.get("budget_usd")), stopDistance = Math.abs(modeledEntryPrice - numeric(exitPolicy.get("stop_price")));
                if (!(budget > 0) || !Double.isFinite(budget) || !(stopDistance > 0)) throw failure("target-stop sizing contract or stop distance is invalid");
                quantity = budget / (stopDistance * multiplier);
            } else {
                if (sizingContract == null || !"FIXED_NOTIONAL_USD".equals(text(sizingContract, "mode"))
                        || !isSha(text(sizingContract, "precommit_sha256")) || !isSha(text(sizingContract, "evaluator_spec_sha256"))) {
                    throw failure("time-stop sizing requires an explicit fixed-notional contract");
                }
                double notional = numeric(sizingContract.get("notional_usd")); if (!(notional > 0) || !Double.isFinite(notional)) throw failure("fixed-notional sizing contract is invalid");
                quantity = notional / (entry.open() * multiplier);
            }
            JsonNode exchangeStepNode = firstDefined(contract.get("step_size"), contract.get("lot_step"), contract.get("quantity_step"));
            JsonNode candidateStepNode = sizingContract == null ? null : sizingContract.get("quantity_step");
            if (defined(candidateStepNode) && defined(exchangeStepNode)) {
                double exchangeStep = numeric(exchangeStepNode), candidateStep = numeric(candidateStepNode), ratio = candidateStep / exchangeStep;
                if (!(candidateStep >= exchangeStep) || !Double.isFinite(ratio) || Math.abs(ratio - Math.rint(ratio)) > 1e-9) throw failure("sizing quantity_step may not loosen or conflict with the frozen exchange step_size");
            }
            JsonNode stepNode = defined(candidateStepNode) ? candidateStepNode : exchangeStepNode;
            double step = defined(stepNode) ? numeric(stepNode) : Double.NaN;
            if (defined(stepNode) && (!(step > 0) || !Double.isFinite(step))) throw failure("sizing quantity_step is invalid");
            if (defined(stepNode)) quantity = Math.floor(quantity / step) * step;
            Double minQuantity = strictOutcomeBound(sizingContract == null ? null : sizingContract.get("min_quantity"), firstDefined(contract.get("min_qty"), contract.get("min_quantity")), true);
            Double maxQuantity = strictOutcomeBound(sizingContract == null ? null : sizingContract.get("max_quantity"), firstDefined(contract.get("max_qty"), contract.get("max_quantity")), false);
            if (minQuantity != null && (!(minQuantity > 0) || !Double.isFinite(minQuantity) || quantity < minQuantity)) throw failure("derived execution quantity is below the frozen minimum quantity");
            if (maxQuantity != null && (!(maxQuantity > 0) || !Double.isFinite(maxQuantity) || quantity > maxQuantity)) throw failure("derived execution quantity exceeds the frozen maximum quantity");
            Double minNotional = strictOutcomeBound(sizingContract == null ? null : sizingContract.get("min_notional_usd"), firstDefined(contract.get("min_notional"), contract.get("min_notional_usd")), true);
            Double maxNotional = strictOutcomeBound(sizingContract == null ? null : sizingContract.get("max_notional_usd"), firstDefined(contract.get("max_notional"), contract.get("max_notional_usd")), false);
            double sizedNotional = quantity * entry.open() * multiplier, modeledEntryNotional = quantity * modeledEntryPrice * multiplier;
            if (minNotional != null && (!(minNotional > 0) || sizedNotional < minNotional)) throw failure("derived execution quantity is below the frozen minimum notional");
            double tolerance = maxNotional == null ? 0 : Math.ulp(Math.max(1, Math.max(Math.abs(maxNotional), Math.abs(modeledEntryNotional)))) * 8;
            if (maxNotional != null && (!(maxNotional > 0) || modeledEntryNotional - maxNotional > tolerance)) throw failure("derived execution quantity exceeds the frozen maximum notional");
            if (!(quantity > 0)) throw failure("frozen sizing contract rounds execution quantity to zero");
        }
        double signedQuantity = ("short".equals(direction) ? -1 : 1) * quantity;
        if (!(Math.abs(signedQuantity) > 0)) throw failure("execution quantity is missing");
        ObjectNode feeReceipt = boundOutcomeMetadata(objectOrNull(metadata.get("fee_schedule")), "FEE_SCHEDULE", true, metadataRoot, fixtureOnly, false);
        ObjectNode feeEntry = outcomeMetadataRecord(feeReceipt, asset, instrument, venue, symbol, entry.event());
        ObjectNode feeExit = outcomeMetadataRecord(feeReceipt, asset, instrument, venue, symbol, resolution);
        double feeRateEntry = numeric(feeEntry.get("taker_fee_rate")), feeRateExit = numeric(feeExit.get("taker_fee_rate"));
        if (!Double.isFinite(feeRateEntry) || feeRateEntry < 0 || !Double.isFinite(feeRateExit) || feeRateExit < 0) throw failure("effective fee schedule rates are invalid");
        double entryPrice = modeledEntryPrice, exitPrice = "long".equals(direction) ? rawExitPrice * (1 - (slippageBps + impactBps) / 10_000)
                : rawExitPrice * (1 + (slippageBps + impactBps) / 10_000);
        double quantityNotional = Math.abs(signedQuantity) * multiplier, rawEntryNotional = entry.open() * quantityNotional,
                rawExitNotional = rawExitPrice * quantityNotional, entryNotional = entryPrice * quantityNotional, exitNotional = exitPrice * quantityNotional;
        double fees = entryNotional * feeRateEntry + exitNotional * feeRateExit;
        double slippage = (rawEntryNotional + rawExitNotional) * slippageBps / 10_000;
        double capacityDebit = (rawEntryNotional + rawExitNotional) * impactBps / 10_000;
        double gross = ("short".equals(direction) ? entry.open() - rawExitPrice : rawExitPrice - entry.open()) * quantityNotional;
        ObjectNode fundingReceipt = "BINANCE_USDM_PERPETUAL".equals(instrument)
                ? boundOutcomeMetadata(objectOrNull(metadata.get("funding_identity")), "FUNDING_IDENTITY", true, metadataRoot, fixtureOnly, false) : null;
        if ("BINANCE_USDM_DATED_FUTURE".equals(instrument)) {
            ObjectNode datedFunding = objectOrNull(metadata.get("funding_identity"));
            boolean notApplicable = datedFunding != null && ("NOT_APPLICABLE".equals(text(datedFunding, "status"))
                    || "UNAVAILABLE".equals(text(datedFunding, "status")) && uniqueTextsOrEmpty(datedFunding.get("limitations")).stream().anyMatch(value -> value.toUpperCase(Locale.ROOT).contains("NOT_APPLICABLE")));
            if (!notApplicable) throw failure("dated futures must declare funding as NOT_APPLICABLE; periodic funding is not accepted");
        }
        if (fundingReceipt != null && !fundingReceipt.path("coverage").path("complete").asBoolean(false)) throw failure("perpetual derivative funding coverage is incomplete");
        List<ObjectNode> fundingRows = fundingReceipt == null ? List.of() : objects(fundingReceipt.path("records")).stream().filter(row ->
                asset.equals(text(row, "asset").toLowerCase(Locale.ROOT)) && venue.equals(text(row, "venue").toUpperCase(Locale.ROOT))
                        && instrument.equals(text(row, "instrument").toUpperCase(Locale.ROOT)) && symbol.equals(text(row, "symbol").toUpperCase(Locale.ROOT))
                        && time(first(row, "settlement_slot", "event_time")) > entry.event() && time(first(row, "settlement_slot", "event_time")) <= resolution
                        && time(row.get("availability_time")) <= resolution).toList();
        if (fundingReceipt != null) validateOutcomeFundingSlots(fundingReceipt, entry.event(), resolution, fundingRows);
        double funding = 0; ArrayNode fundingSettlements = array();
        for (ObjectNode row : fundingRows) {
            double mark = numeric(first(row, "settlement_mark", "mark_price")); if (!Double.isFinite(mark)) throw failure("funding event " + textOr(first(row, "event_id"), "?") + " has no settlement mark");
            double pnl = -(signedQuantity * mark * multiplier * numeric(row.get("funding_rate"))); funding += pnl;
            ObjectNode settlement = object().put("event_id", text(row, "event_id")); settlement.set("raw_event_time", first(row, "raw_event_time", "event_time").deepCopy());
            if (row.has("settlement_slot")) settlement.set("settlement_slot", row.get("settlement_slot").deepCopy()); else settlement.putNull("settlement_slot");
            settlement.put("funding_rate", numeric(row.get("funding_rate"))).put("settlement_mark", mark).put("pnl_usd", pnl); fundingSettlements.add(settlement);
        }
        ObjectNode liquidationModel = null;
        if (derivative) {
            ObjectNode marginReceipt = boundOutcomeMetadata(objectOrNull(metadata.get("margin")), "MARGIN", true, metadataRoot, fixtureOnly, false);
            ObjectNode liquidationReceipt = metadata.path("liquidation").isObject() ? boundOutcomeMetadata((ObjectNode) metadata.path("liquidation"), "LIQUIDATION", false, metadataRoot, fixtureOnly, false) : null;
            ObjectNode margin = outcomeMetadataRecord(marginReceipt, asset, instrument, venue, symbol, entry.event());
            ObjectNode liquidation = liquidationReceipt == null ? null : outcomeMetadataRecord(liquidationReceipt, asset, instrument, venue, symbol, entry.event());
            ObjectNode derivativePolicy = candidate.path("derivative_policy").isObject() ? (ObjectNode) candidate.path("derivative_policy") : null;
            String rawMarginMode = textOr(first(execution, "margin_mode"), ""), rawTier = textOr(first(execution, "tier_id", "margin_tier_id"), "");
            JsonNode rawLeverage = execution.get("leverage"), rawCollateral = firstDefined(execution.get("collateral_usd"), execution.get("collateral"));
            double maintenanceRate = numeric(margin.get("maintenance_margin_ratio"));
            String marginMode = fixtureOnly ? textOr(firstDefined(rawMarginMode.isEmpty() ? null : JSON.getNodeFactory().textNode(rawMarginMode), derivativePolicy == null ? null : derivativePolicy.get("margin_mode")), "")
                    : derivativePolicy == null ? "" : text(derivativePolicy, "margin_mode");
            double leverage = fixtureOnly && defined(rawLeverage) ? numeric(rawLeverage) : derivativePolicy == null ? Double.NaN : numeric(derivativePolicy.get("leverage"));
            String tierId = fixtureOnly ? !rawTier.isEmpty() ? rawTier : derivativePolicy != null && derivativePolicy.hasNonNull("tier_id") ? text(derivativePolicy, "tier_id") : text(margin, "tier_id")
                    : derivativePolicy != null && derivativePolicy.hasNonNull("tier_id") ? text(derivativePolicy, "tier_id") : text(margin, "tier_id");
            if (marginMode.isEmpty() || text(margin, "margin_mode").isEmpty() || !marginMode.equalsIgnoreCase(text(margin, "margin_mode")) || tierId.isEmpty()) throw failure("derivative margin mode/tier is not bound");
            double maxLeverage = first(contract, "max_leverage").isNumber() ? numeric(contract.get("max_leverage")) : margin.path("max_leverage").isNumber() ? numeric(margin.get("max_leverage")) : leverage;
            if (!(leverage > 0) || !(maxLeverage > 0) || leverage > maxLeverage) throw failure("derivative leverage exceeds the bound contract tier");
            double collateralBuffer = Math.max(0, derivativePolicy == null ? 0 : derivativePolicy.path("collateral_buffer_fraction").asDouble(0));
            double collateral = fixtureOnly && defined(rawCollateral) ? numeric(rawCollateral) : entryNotional / leverage * (1 + collateralBuffer);
            if (!(collateral > 0) || !(maintenanceRate > 0) || collateral < entryNotional / leverage) throw failure("derivative collateral, maintenance margin, leverage, or notional is invalid");
            if (liquidation != null) throw failure("static liquidation metadata is stress-only; base liquidation must be derived from bound entry, margin, fees, funding, and marks");
            liquidationModel = object().put("method", "DYNAMIC_ENTRY_MARGIN_EQUITY").put("static_receipt_ignored", false)
                    .put("maintenance_margin_ratio", maintenanceRate).put("leverage", leverage).put("collateral_usd", collateral)
                    .put("margin_mode", marginMode.toUpperCase(Locale.ROOT)).put("tier_id", tierId);
            for (OutcomeBar row : bars) if (row.event() >= entry.event() && row.event() <= resolution) {
                OutcomeMark boundMark = markBars.stream().filter(mark -> mark.event() == row.event()).findFirst().orElseThrow(() -> failure("derivative execution mark path is missing an aligned bar"));
                double mark = "short".equals(direction) ? boundMark.high() : boundMark.low();
                if (!(mark > 0) || boundMark.low() > boundMark.high()) throw failure("derivative execution bar lacks a positive bound mark range");
                double markPnl = ("short".equals(direction) ? entryPrice - mark : mark - entryPrice) * Math.abs(signedQuantity) * multiplier;
                double settledFunding = 0; for (JsonNode settlement : fundingSettlements) if (time(first(settlement, "settlement_slot", "raw_event_time")) <= row.event()) settledFunding += settlement.path("pnl_usd").asDouble();
                double equity = collateral - entryPrice * Math.abs(signedQuantity) * multiplier * feeRateEntry + markPnl + settledFunding;
                double maintenance = mark * Math.abs(signedQuantity) * multiplier * maintenanceRate;
                if (equity <= maintenance) throw failure("execution path breaches dynamically derived maintenance margin/liquidation boundary");
            }
        }
        double net = gross - fees - slippage - capacityDebit + funding;
        Double suppliedCandidateRisk = candidate.has("risk_amount_usd") ? numeric(candidate.get("risk_amount_usd")) : null;
        Double suppliedExecutionRisk = execution.has("risk_amount_usd") ? numeric(execution.get("risk_amount_usd")) : null;
        double riskAmount;
        if ("TARGET_STOP".equals(policyType)) {
            riskAmount = Math.abs(entryPrice - numeric(exitPolicy.get("stop_price"))) * Math.abs(signedQuantity) * multiplier;
            if (!(riskAmount > 0)) throw failure("derived stop-distance risk amount is invalid");
            for (Double supplied : new Double[]{suppliedCandidateRisk, suppliedExecutionRisk}) if (supplied != null && (!Double.isFinite(supplied) || Math.abs(supplied - riskAmount) > Math.max(1e-9, riskAmount * 1e-9))) throw failure("caller-supplied risk amount does not match the authoritative stop-distance denominator");
        } else {
            if (riskContract == null || !"FIXED_RISK_BUDGET_USD".equals(text(riskContract, "mode")) || !isSha(text(riskContract, "precommit_sha256")) || !isSha(text(riskContract, "evaluator_spec_sha256"))) {
                throw failure("time-stop risk requires a precommitted fixed-risk-budget evaluator contract");
            }
            double budget = numeric(riskContract.get("budget_usd")); if (!(budget > 0) || !Double.isFinite(budget)) throw failure("fixed-risk-budget denominator is invalid");
            ObjectNode evaluatorSpec = objectOrNull(options.get("evaluatorSpec"));
            if (evaluatorSpec != null) {
                ObjectNode frozenRisk = objectOrNull(evaluatorSpec.path("execution_contract").get("risk_convention"));
                if (!text(riskContract, "precommit_sha256").equals(text(evaluatorSpec, "precommit_sha256"))
                        || !text(riskContract, "evaluator_spec_sha256").equals(text(evaluatorSpec, "content_sha256"))
                        || frozenRisk == null || !"FIXED_RISK_BUDGET_USD".equals(text(frozenRisk, "mode")) || numeric(frozenRisk.get("budget_usd")) != budget) {
                    throw failure("fixed-risk-budget contract is not bound to the verified evaluator spec");
                }
            }
            for (Double supplied : new Double[]{suppliedCandidateRisk, suppliedExecutionRisk}) if (supplied != null && (!Double.isFinite(supplied) || Math.abs(supplied - budget) > Math.max(1e-9, budget * 1e-9))) throw failure("caller-supplied risk amount disagrees with the frozen fixed-risk budget");
            riskAmount = budget;
        }
        ObjectNode result = object().put("traded", true).put("asset", asset).put("instrument", instrument).put("direction", direction)
                .put("entry_policy", entryPolicy).put("entry_delay_bars", entryDelayBars).put("entry_time", iso(entry.event())).put("exit_time", iso(resolution))
                .put("entry_price", entryPrice).put("exit_price", exitPrice).put("raw_exit_price", rawExitPrice).put("exit_reason", exitReason).put("gap_fill", gapFill)
                .put("quantity", Math.abs(signedQuantity)).put("signed_quantity", signedQuantity).put("contract_multiplier", multiplier)
                .put("gross_pnl_usd", gross).put("fees_usd", fees).put("funding_pnl_usd", funding).put("slippage_usd", slippage)
                .put("capacity_debit_usd", capacityDebit).put("net_pnl_usd", net).put("risk_amount_usd", riskAmount).put("net_r", net / riskAmount);
        result.set("funding_settlements", fundingSettlements); if (liquidationModel == null) result.putNull("liquidation_model"); else result.set("liquidation_model", liquidationModel);
        if (derivative) result.put("collateral_used", liquidationModel.path("collateral_usd").asDouble()).put("margin_mode", text(liquidationModel, "margin_mode"))
                .put("leverage", liquidationModel.path("leverage").asDouble()).put("tier_id", text(liquidationModel, "tier_id"));
        result.set("exit_policy", object().put("type", policyType).put("collision_policy", collisionPolicy));
        result.set("execution_model", object().put("slippage_bps", slippageBps).put("impact_bps", impactBps).put("outage_policy", outagePolicy)
                .put("gap_policy", gapPolicy).put("provenance", text(executionModelReceipt, "provenance_mode")));
        result.put("risk_denominator", "TARGET_STOP".equals(policyType) ? "DERIVED_STOP_DISTANCE" : "FROZEN_FIXED_RISK_BUDGET")
                .put("provenance", "DERIVED_FROM_BOUND_BARS_AND_METADATA");
        return result;
    }

    private record OutcomeBar(ObjectNode raw, long event, double open, double high, double low, double close) {}
    private record OutcomeMark(ObjectNode raw, long event, double open, double high, double low, double close) {}

    private static JsonNode firstDefined(JsonNode... values) {
        if (values != null) for (JsonNode value : values) if (defined(value) && !value.isNull()) return value;
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static ObjectNode objectOrNull(JsonNode value) { return value != null && value.isObject() ? (ObjectNode) value : null; }

    private static Double strictOutcomeBound(JsonNode candidate, JsonNode exchange, boolean minimum) {
        boolean hasCandidate = defined(candidate) && !candidate.isNull(), hasExchange = defined(exchange) && !exchange.isNull();
        if (!hasCandidate && !hasExchange) return null;
        if (!hasCandidate) return numeric(exchange); if (!hasExchange) return numeric(candidate);
        return minimum ? Math.max(numeric(candidate), numeric(exchange)) : Math.min(numeric(candidate), numeric(exchange));
    }

    private static ObjectNode boundOutcomeMetadata(ObjectNode receipt, String kind, boolean required, Path root,
            boolean fixtureOnly, boolean allowConservativeModel) {
        if (receipt == null) { if (required) throw failure(kind + " metadata is not bound"); return null; }
        assertOwnHash(receipt, DATA_V5.get("metadata"), kind + " metadata");
        if (!kind.equals(text(receipt, "kind")) || "UNAVAILABLE".equals(text(receipt, "status"))) throw failure(kind + " metadata is unavailable or non-authoritative");
        if ("CONSERVATIVE_MODEL".equals(text(receipt, "status")) && !(fixtureOnly || allowConservativeModel && "EXECUTION_MODEL".equals(kind)
                && "MODEL_BOUND".equals(text(receipt, "provenance_mode")) && isSha(text(receipt, "model_sha256")) && isSha(text(receipt, "precommit_sha256")))) {
            throw failure(kind + " modeled metadata is stress-only");
        }
        if (!receipt.path("authoritative").asBoolean(false) && !(fixtureOnly && "CONSERVATIVE_MODEL".equals(text(receipt, "status")))) {
            throw failure(kind + " metadata is unavailable or non-authoritative");
        }
        if (Set.of("PUBLIC_OBSERVED", "USER_BOUND").contains(text(receipt, "status"))) {
            if (root == null || !receipt.path("source_receipts").isArray() || receipt.path("source_receipts").isEmpty() || text(receipt, "source_root_reference").isEmpty()) {
                throw failure(kind + " public/user-bound metadata lacks physical source custody binding");
            }
            for (ObjectNode summary : objects(receipt.path("source_receipts"))) verifyNormalizedReceipt(root, summary, kind + " metadata source receipt");
        }
        return receipt;
    }

    private static ObjectNode outcomeMetadataRecord(ObjectNode receipt, String asset, String instrument, String venue,
            String symbol, long at) {
        List<ObjectNode> matches = objects(receipt.path("records")).stream().filter(row -> asset.equalsIgnoreCase(text(row, "asset"))
                && instrument.equalsIgnoreCase(text(row, "instrument")) && venue.equalsIgnoreCase(text(row, "venue"))
                && symbol.equalsIgnoreCase(text(row, "symbol")) && time(row.get("effective_from")) <= at
                && time(row.get("effective_to")) >= at && time(row.get("availability_time")) <= at).toList();
        if (matches.size() != 1) throw failure("metadata is missing, unavailable, or ambiguous for " + asset + "/" + venue + "/" + instrument + "/" + symbol + " at " + iso(at));
        return matches.get(0);
    }

    private static void validateOutcomeFundingSlots(ObjectNode receipt, long entry, long exit, List<ObjectNode> rows) {
        List<String> ids = rows.stream().map(row -> text(row, "event_id")).toList();
        if (ids.stream().anyMatch(String::isEmpty) || new HashSet<>(ids).size() != ids.size()) throw failure("derivative funding lifecycle has missing or duplicate event identities");
        List<Long> observed = rows.stream().map(row -> time(first(row, "settlement_slot", "raw_event_time", "event_time"))).toList();
        if ("EVENT_SEQUENCE".equals(text(receipt.path("coverage"), "coverage_mode"))) {
            if (new HashSet<>(observed).size() != observed.size() || observed.stream().anyMatch(value -> value <= entry || value > exit)) throw failure("derivative funding lifecycle has missing, extra, or duplicate event identities");
            return;
        }
        if (!receipt.path("coverage").path("cadence_segments").isArray() || receipt.path("coverage").path("cadence_segments").isEmpty()) throw failure("derivative funding receipt lacks its canonical cadence segments");
        ObjectNode series = object().put("series_type", "funding_events").put("start_at", iso(entry)).put("end_at", iso(exit))
                .put("slot_tolerance_ms", receipt.path("coverage").path("slot_tolerance_ms").asLong(60_000));
        series.set("cadence_segments", receipt.path("coverage").path("cadence_segments").deepCopy());
        List<Long> expected = expectedFundingSlots(series).stream().filter(value -> value > entry && value <= exit).toList();
        if (new HashSet<>(observed).size() != observed.size() || expected.stream().anyMatch(value -> !observed.contains(value)) || observed.stream().anyMatch(value -> !expected.contains(value))) {
            throw failure("derivative funding lifecycle has missing, extra, or duplicate settlement slots");
        }
    }

    private static long validateDirectOutcomeIdentities(ObjectNode feature, ObjectNode label, ObjectNode execution) {
        String identity = outcomeIdentity(feature), labelIdentity = outcomeIdentity(label), executionIdentity = outcomeIdentity(execution);
        if (!identity.equals(labelIdentity) || !identity.equals(executionIdentity)) throw failure("feature/label/execution series identities do not match");
        long decision = time(first(feature, "decision_time", "event_time"));
        if (decision != time(first(label, "decision_time", "event_time")) || decision != time(first(execution, "decision_time", "event_time", "entry_time"))) throw failure("feature/label/execution decision times do not match");
        if (text(feature, "signal_id").isEmpty() || !text(feature, "signal_id").equals(text(label, "signal_id")) || !text(feature, "signal_id").equals(text(execution, "signal_id"))
                || text(label, "episode_id").isEmpty() || !text(label, "episode_id").equals(text(execution, "episode_id"))) throw failure("feature/label/execution signal and episode identities do not match");
        return decision;
    }
    private static String outcomeIdentity(ObjectNode row) {
        if (text(row, "asset").isEmpty() || text(row, "venue").isEmpty() || text(row, "instrument").isEmpty() || text(row, "symbol").isEmpty()) throw failure("outcome identity is incomplete");
        return predictorAsset(row) + "|" + text(row, "venue").toLowerCase(Locale.ROOT) + "|" + text(row, "instrument").toUpperCase(Locale.ROOT) + "|" + text(row, "symbol").toUpperCase(Locale.ROOT);
    }

    /* Keep the normalized-lifecycle adapter contract-compatible with the
     * Node boundary.  LifecycleV5 owns the actual bar simulation; this layer
     * owns the evaluator/strategy binding and the stable outcome projection.
     * In particular, sizing and risk contracts are not inferred from a
     * caller-owned execution row in authoritative mode. */
    private static ObjectNode deriveNormalizedLifecycleOutcome(ObjectNode feature, ObjectNode label,
            ObjectNode execution, ObjectNode candidate, ObjectNode options,
            LifecycleTrustService trustService, LifecycleTrustService.Token lifecycleToken) {
        boolean fixtureOnly = options.path("fixtureOnly").asBoolean(false);
        ObjectNode lifecycle = null;
        for (JsonNode value : new JsonNode[]{candidate.get("lifecycle"), candidate.get("lifecycle_spec"),
                execution.get("lifecycle"), execution.get("lifecycle_spec")}) {
            if (value != null && value.isObject()) { lifecycle = (ObjectNode) value.deepCopy(); break; }
        }
        boolean lifecycleEngine = "strategy-v5-trade-lifecycle/1".equals(text(candidate, "lifecycle_engine"))
                || "strategy-v5-trade-lifecycle/1".equals(text(execution, "lifecycle_engine"));
        if (lifecycle == null && lifecycleEngine) {
            lifecycle = object();
            copyFirstDefined(lifecycle, "max_lifecycle_ms", candidate, "max_lifecycle_ms", execution, "max_lifecycle_ms", label, "max_lifecycle_ms");
            copyFirstDefined(lifecycle, "stop", candidate, "stop", candidate, "stop_spec", execution, "stop", execution, "stop_spec");
            copyFirstDefined(lifecycle, "target", candidate, "target", candidate, "target_spec", execution, "target", execution, "target_spec");
            copyFirstDefined(lifecycle, "partial_exits", candidate, "partial_exits", candidate, "partials");
            if (!lifecycle.has("partial_exits") && candidate.path("exit_policy").isObject()) copyFirstDefined(lifecycle, "partial_exits", (ObjectNode) candidate.path("exit_policy"), "partial_exits", (ObjectNode) candidate.path("exit_policy"), "partials");
            if (!lifecycle.has("partial_exits")) copyFirstDefined(lifecycle, "partial_exits", execution, "partial_exits", execution, "partials");
            copyFirstDefined(lifecycle, "trailing", candidate, "trailing", candidate, "ratchet");
            if (!lifecycle.has("trailing") && candidate.path("exit_policy").isObject()) copyFirstDefined(lifecycle, "trailing", (ObjectNode) candidate.path("exit_policy"), "trailing", (ObjectNode) candidate.path("exit_policy"), "ratchet");
            if (!lifecycle.has("trailing")) copyFirstDefined(lifecycle, "trailing", execution, "trailing", execution, "ratchet");
            copyFirstDefined(lifecycle, "gap_policy", candidate, "gap_policy");
            if (!lifecycle.has("gap_policy") && candidate.path("exit_policy").isObject() && defined(candidate.path("exit_policy").get("gap_policy"))) lifecycle.set("gap_policy", candidate.path("exit_policy").get("gap_policy").deepCopy());
            if (!lifecycle.has("gap_policy")) copyFirstDefined(lifecycle, "gap_policy", execution, "gap_policy");
            if (!lifecycle.has("sizing")) {
                JsonNode sizing = firstDefined(candidate.get("sizing"), execution.get("sizing"));
                if (defined(sizing)) lifecycle.set("sizing", sizing.deepCopy());
                else if (candidate.has("risk_amount_usd")) lifecycle.set("sizing", object().put("mode", "RISK_USD").set("risk_usd", candidate.get("risk_amount_usd")));
            }
        }
        if (lifecycle == null) throw failure("normalized lifecycle specification is missing");
        ObjectNode contracts = normalizedLifecycleContracts(lifecycle, candidate, execution,
                objectOrNull(options.get("evaluatorSpec")), fixtureOnly);
        ObjectNode effectiveLifecycle = (ObjectNode) contracts.path("lifecycle");
        ObjectNode intent = candidate.deepCopy(); intent.put("fixtureOnly", fixtureOnly);
        intent.put("direction", textOr(first(candidate, "direction"), textOr(first(execution, "direction"), textOr(first(label, "direction"), "long"))));
        intent.put("instrument_type", textOr(first(candidate, "instrument_type"), textOr(first(execution, "instrument_type"), textOr(first(label, "instrument_type"), textOr(first(execution, "instrument"), "spot")))));
        intent.put("decision_time", textOr(first(candidate, "decision_time"), textOr(first(execution, "decision_time"), text(feature, "decision_time"))));
        intent.set("lifecycle", effectiveLifecycle);
        if (candidate.path("contract").isObject()) intent.set("contract", candidate.path("contract").deepCopy());
        ObjectNode request = object(); request.set("intent", intent); request.set("bars", execution.path("child_bars"));
        request.set("funding", firstDefined(execution.get("funding_rows"), execution.get("funding_events")));
        request.set("marks", execution.path("mark_bars")); request.put("interval_ms", execution.path("interval_ms").asLong(ONE_MINUTE)); request.set("execution", execution);
        ObjectNode lifecycleResult; LifecycleTrustService.ReopenedTrust reopenedTrust = null;
        if (fixtureOnly) lifecycleResult = new TradeLifecycleV5().normalizeTradeLifecycleV5(request);
        else {
            if (trustService == null || lifecycleToken == null) throw failure("authoritative normalized lifecycle requires a physical lifecycle trust token");
            lifecycleResult = new TradeLifecycleV5(trustService).normalizeTradeLifecycleV5(request, lifecycleToken);
            reopenedTrust = trustService.reopenLifecycleTrustV5(lifecycleToken, Map.of());
        }
        ArrayNode exits = (ArrayNode) lifecycleResult.path("exits"); if (exits.isEmpty()) throw failure("normalized lifecycle produced no terminal exit");
        ObjectNode last = (ObjectNode) exits.get(exits.size() - 1);
        if (options.path("envelopeWindow").isObject()) {
            ObjectNode window = (ObjectNode) options.path("envelopeWindow");
            if (time(lifecycleResult.get("entry_time")) < time(window.get("execution_start"))
                    || time(lifecycleResult.get("lifecycle_end_exclusive")) > time(window.get("execution_end"))) {
                throw failure("normalized lifecycle path escapes frozen opportunity envelope");
            }
        }
        ObjectNode sizing = effectiveLifecycle.path("sizing").isObject() ? (ObjectNode) effectiveLifecycle.path("sizing")
                : candidate.path("sizing").isObject() ? (ObjectNode) candidate.path("sizing")
                : execution.path("sizing").isObject() ? (ObjectNode) execution.path("sizing") : object();
        double multiplier = numeric(lifecycleResult.get("contract_multiplier"));
        JsonNode stopNode = lifecycleResult.get("stop_price");
        double stopDistance = defined(stopNode) ? Math.abs(numeric(lifecycleResult.get("entry_price")) - numeric(stopNode)) : Double.NaN;
        double inferredRisk = Double.isFinite(stopDistance) ? stopDistance * numeric(lifecycleResult.get("quantity")) * multiplier
                : numeric(lifecycleResult.get("entry_price")) * numeric(lifecycleResult.get("quantity")) * multiplier;
        ObjectNode riskContract = objectOrNull(contracts.get("riskContract"));
        JsonNode riskNode = riskContract == null ? firstDefined(sizing.get("risk_usd"), sizing.get("budget_usd"), sizing.get("risk_amount_usd")) : riskContract.get("budget_usd");
        double riskAmount = defined(riskNode) ? numeric(riskNode) : inferredRisk;
        if (!(riskAmount > 0) || !Double.isFinite(riskAmount)) throw failure("normalized lifecycle risk denominator is invalid");
        for (JsonNode supplied : new JsonNode[]{candidate.get("risk_amount_usd"), execution.get("risk_amount_usd")}) {
            if (defined(supplied)) { double value = numeric(supplied); if (!Double.isFinite(value) || Math.abs(value - riskAmount) > Math.max(1e-9, riskAmount * 1e-9)) throw failure("normalized lifecycle caller risk amount conflicts with the frozen fixed-risk budget"); }
        }
        ArrayNode fundingSettlements = array();
        for (JsonNode exitNode : exits) for (ObjectNode settlement : objects(exitNode.path("funding_settlements"))) {
            ObjectNode value = object().put("event_id", text(settlement, "event_id")); value.put("raw_event_time", text(settlement, "event_time"));
            value.put("settlement_slot", text(settlement, "event_time")).put("funding_rate", numeric(settlement.get("rate")))
                    .put("settlement_mark", numeric(settlement.get("mark_price"))).put("pnl_usd", numeric(settlement.get("amount_usd"))); fundingSettlements.add(value);
        }
        ObjectNode boundModel = object();
        if (!fixtureOnly && reopenedTrust != null && reopenedTrust.values().get("execution_model") instanceof ObjectNode model) boundModel = model;
        double modelSlippage = boundModel.path("slippage_bps").asDouble(0), modelImpact = boundModel.path("impact_bps").asDouble(0);
        String instrument = switch (text(lifecycleResult, "instrument_type")) {
            case "SPOT" -> "BINANCE_SPOT"; case "DATED_FUTURE" -> "BINANCE_USDM_DATED_FUTURE"; default -> "BINANCE_USDM_PERPETUAL";
        };
        ObjectNode normalizedExitPolicy = object().put("type", "NORMALIZED_LIFECYCLE").put("collision_policy", "ADVERSE_STOP_FIRST");
        JsonNode partials = firstDefined(lifecycle.get("partial_exits"), lifecycle.get("partials")); if (defined(partials)) normalizedExitPolicy.set("partial_exits", partials.deepCopy()); else normalizedExitPolicy.set("partial_exits", array());
        normalizedExitPolicy.set("trailing", lifecycle.has("trailing") ? lifecycle.get("trailing").deepCopy() : NullNode.instance);
        ObjectNode result = object().put("traded", true).put("asset", predictorAsset(feature)).put("instrument", instrument)
                .put("direction", text(lifecycleResult, "direction")).put("entry_policy", "NEXT_BAR_OPEN").put("entry_delay_bars", 0)
                .put("entry_time", text(lifecycleResult, "entry_time")).put("exit_time", text(last, "time"))
                .put("entry_price", numeric(lifecycleResult.get("entry_price"))).put("exit_price", numeric(last.get("price"))).put("raw_exit_price", numeric(last.get("price")))
                .put("exit_reason", text(last, "reason")).put("gap_fill", "GAP_OPEN".equals(text(last, "fill_type")))
                .put("quantity", numeric(lifecycleResult.get("quantity"))).put("signed_quantity", "short".equals(text(lifecycleResult, "direction")) ? -numeric(lifecycleResult.get("quantity")) : numeric(lifecycleResult.get("quantity")))
                .put("contract_multiplier", multiplier).put("gross_pnl_usd", numeric(lifecycleResult.get("gross_pnl_usd"))).put("fees_usd", numeric(lifecycleResult.get("fees_usd")))
                .put("funding_pnl_usd", numeric(lifecycleResult.get("funding_usd"))).put("slippage_usd", numeric(lifecycleResult.get("slippage_usd"))).put("capacity_debit_usd", numeric(lifecycleResult.get("capacity_debit_usd")))
                .put("net_pnl_usd", numeric(lifecycleResult.get("net_pnl_usd"))).put("risk_amount_usd", riskAmount).put("net_r", numeric(lifecycleResult.get("net_pnl_usd")) / riskAmount).putNull("liquidation_model");
        result.set("funding_settlements", fundingSettlements); result.set("exit_policy", normalizedExitPolicy);
        result.set("execution_model", object().put("slippage_bps", modelSlippage).put("impact_bps", modelImpact).put("provenance", "STRATEGY_V5_TRADE_LIFECYCLE_1"));
        result.put("risk_denominator", riskContract != null ? "FROZEN_FIXED_RISK_BUDGET" : Double.isFinite(stopDistance) ? "DERIVED_STOP_DISTANCE" : "FIXED_NOTIONAL_OR_VOLATILITY");
        result.put("provenance", "DERIVED_FROM_CANONICAL_NORMALIZED_LIFECYCLE"); result.set("lifecycle_result", lifecycleResult);
        return result;
    }

    private static ObjectNode normalizedLifecycleContracts(ObjectNode lifecycle, ObjectNode candidate,
            ObjectNode execution, ObjectNode evaluatorSpec, boolean fixtureOnly) {
        ObjectNode candidateRisk = objectOrNull(candidate.get("risk_contract")), executionRisk = objectOrNull(execution.get("risk_contract"));
        if (candidateRisk != null && executionRisk != null && !stable(riskSemantic(candidateRisk)).equals(stable(riskSemantic(executionRisk)))) throw failure("normalized lifecycle candidate/execution risk_contract values conflict");
        ObjectNode risk = candidateRisk != null ? candidateRisk : fixtureOnly ? executionRisk : null;
        if (!fixtureOnly && risk == null) throw failure("authoritative normalized lifecycle requires an evaluator-bound candidate risk_contract");
        if (risk != null && (!"FIXED_RISK_BUDGET_USD".equals(text(risk, "mode")) || !(numeric(risk.get("budget_usd")) > 0) || !isSha(text(risk, "precommit_sha256")) || !isSha(text(risk, "evaluator_spec_sha256")))) throw failure("normalized lifecycle risk_contract is not a hash-bound fixed risk budget");
        ObjectNode candidateSizing = objectOrNull(candidate.get("sizing_contract")), executionSizing = objectOrNull(execution.get("sizing_contract"));
        if (candidateSizing != null && executionSizing != null && !stable(sizingSemantic(candidateSizing, risk)).equals(stable(sizingSemantic(executionSizing, risk)))) throw failure("normalized lifecycle candidate/execution sizing_contract values conflict");
        ObjectNode sizing = candidateSizing != null ? candidateSizing : fixtureOnly ? executionSizing : null;
        if (!fixtureOnly && sizing == null) throw failure("authoritative normalized lifecycle requires an evaluator-bound candidate sizing_contract");
        if (sizing != null && (!Set.of("FIXED_NOTIONAL_USD", "TARGET_STOP_RISK").contains(text(sizing, "mode")) || !isSha(text(sizing, "precommit_sha256")) || !isSha(text(sizing, "evaluator_spec_sha256")))) throw failure("normalized lifecycle sizing_contract is not evaluator-bound");
        if (!fixtureOnly) {
            if (evaluatorSpec == null || !isSha(text(evaluatorSpec, "content_sha256")) || !isSha(text(evaluatorSpec, "precommit_sha256"))) throw failure("authoritative normalized lifecycle requires its verified evaluator spec");
            ObjectNode frozenRisk = objectOrNull(evaluatorSpec.path("execution_contract").get("risk_convention")), frozenSizing = objectOrNull(evaluatorSpec.path("execution_contract").get("sizing_contract"));
            if (frozenRisk == null || risk == null || !text(risk, "precommit_sha256").equals(text(evaluatorSpec, "precommit_sha256")) || !text(risk, "evaluator_spec_sha256").equals(text(evaluatorSpec, "content_sha256")) || !text(risk, "mode").equals(text(frozenRisk, "mode")) || numeric(risk.get("budget_usd")) != numeric(frozenRisk.get("budget_usd"))) throw failure("normalized lifecycle risk_contract differs from the verified evaluator spec");
            if (frozenSizing == null || sizing == null || !text(sizing, "precommit_sha256").equals(text(evaluatorSpec, "precommit_sha256")) || !text(sizing, "evaluator_spec_sha256").equals(text(evaluatorSpec, "content_sha256")) || !stable(sizingValueSemantic(sizing, risk)).equals(stable(sizingValueSemantic(frozenSizing, frozenRisk)))) throw failure("normalized lifecycle sizing_contract differs from the verified evaluator spec");
        }
        ObjectNode effective = lifecycle.deepCopy();
        if (sizing != null) {
            ObjectNode frozen = sizingSemantic(sizing, risk); ObjectNode declared = objectOrNull(lifecycle.get("sizing"));
            if (declared != null && !stable(sizingValueSemantic(declared, risk)).equals(stable(sizingValueSemantic(sizing, risk)))) throw failure("normalized lifecycle sizing disagrees with the evaluator-bound sizing_contract");
            double amount = numeric(frozen.get("amount")); if (!(amount > 0) || !Double.isFinite(amount)) throw failure("normalized lifecycle frozen sizing amount is invalid");
            effective.set("sizing", "FIXED_NOTIONAL_USD".equals(text(frozen, "mode")) ? object().put("mode", "FIXED_NOTIONAL_USD").put("notional_usd", amount) : object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", risk == null ? amount : numeric(risk.get("budget_usd"))));
        }
        ObjectNode result = object(); result.set("lifecycle", effective); result.set("riskContract", risk); return result;
    }

    private static ObjectNode riskSemantic(ObjectNode value) { ObjectNode result = object().put("mode", text(value, "mode")).put("budget_usd", numberOrNaN(value.get("budget_usd"))); result.set("precommit_sha256", nullOrText(value.get("precommit_sha256"))); result.set("evaluator_spec_sha256", nullOrText(value.get("evaluator_spec_sha256"))); return result; }
    private static ObjectNode sizingSemantic(ObjectNode value, ObjectNode risk) { String mode = text(value, "mode"); double amount = switch (mode) { case "FIXED_NOTIONAL_USD", "FIXED_NOTIONAL" -> numberOrNaN(firstDefined(value.get("notional_usd"), value.get("notional"))); case "TARGET_STOP_RISK", "RISK_USD", "FIXED_RISK_BUDGET_USD" -> numberOrNaN(firstDefined(value.get("risk_usd"), value.get("budget_usd"), value.get("risk_amount_usd"), risk == null ? null : risk.get("budget_usd"))); default -> Double.NaN; }; ObjectNode result = object().put("mode", Set.of("FIXED_NOTIONAL", "FIXED_NOTIONAL_USD").contains(mode) ? "FIXED_NOTIONAL_USD" : Set.of("RISK_USD", "FIXED_RISK_BUDGET_USD", "TARGET_STOP_RISK").contains(mode) ? "TARGET_STOP_RISK" : mode).put("amount", amount); result.set("precommit_sha256", nullOrText(value.get("precommit_sha256"))); result.set("evaluator_spec_sha256", nullOrText(value.get("evaluator_spec_sha256"))); return result; }
    private static ObjectNode sizingValueSemantic(ObjectNode value, ObjectNode risk) { ObjectNode result = sizingSemantic(value, risk); result.remove("precommit_sha256"); result.remove("evaluator_spec_sha256"); return result; }
    private static JsonNode nullOrText(JsonNode value) { return defined(value) ? value.deepCopy() : NullNode.instance; }
    private static double numberOrNaN(JsonNode value) { return defined(value) ? parseDouble(value.asText()) : Double.NaN; }
    private static void copyFirstDefined(ObjectNode target, String name, Object... values) { for (int i = 0; i + 1 < values.length; i += 2) { ObjectNode row = values[i] instanceof ObjectNode ? (ObjectNode) values[i] : null; String field = values[i + 1] instanceof String ? (String) values[i + 1] : ""; if (row != null && !field.isEmpty() && defined(row.get(field))) { target.set(name, row.get(field).deepCopy()); return; } } }

    public static ObjectNode validateOutcomeBindings(ObjectNode options) { return deriveBoundExecutionOutcome(options); }

    /* ------------------------------------------------------------------ */
    /* Parquet promotion and separated artifacts                           */
    /* ------------------------------------------------------------------ */

    public static ObjectNode convertToParquet(ObjectNode options) {
        ObjectNode staging = requiredObject(options, "stagingManifest"); Path stagingRoot = requiredPath(options, "stagingRoot");
        Path outputRoot = requiredPath(options, "outputRoot"); String schema = text(staging, "schema");
        if (!Set.of(DATA_V5.get("acquisition"), DATA_V5.get("hydration")).contains(schema)) throw failure("Parquet conversion requires a v5 staging manifest");
        assertOwnHash(staging, schema, "v5 staging manifest"); boolean fixture = options.path("fixtureOnly").asBoolean(false);
        if (!("STAGING_COMPLETE".equals(text(staging, "status")) || fixture && "STAGING_PARTIAL".equals(text(staging, "status")))
                || !"STAGING".equals(text(staging, "storage_role")) || !"JSONL".equals(text(staging, "staging_format"))) {
            throw failure("Parquet conversion requires a complete JSONL STAGING manifest; incomplete data cannot be promoted outside an explicit fixture-only conversion");
        }
        ObjectNode verification = object(); verification.set("manifest", staging); verification.put("root", stagingRoot.toString())
                .put("planSha256", text(staging, "plan_sha256")).put("allowFixture", fixture);
        if (options.path("plan").isObject()) verification.set("plan", options.path("plan")); verifyAuthoritativeStaging(verification);
        try { Files.createDirectories(outputRoot); } catch (IOException error) { throw failure("Parquet output root cannot be created: " + error.getMessage()); }
        ArrayNode converted = array();
        for (ObjectNode capture : objects(staging.path("captures"))) {
            if (capture.path("unavailable").asBoolean(false) || !capture.path("coverage").path("complete").asBoolean(false)
                    || !capture.path("partition").isObject()) continue;
            ObjectNode source = (ObjectNode) capture.path("partition");
            if (!"JSONL".equals(text(source, "format")) || !"STAGING".equals(text(source, "storage_role")) || source.path("authoritative").asBoolean(true)) {
                throw failure("capture " + text(capture, "asset") + "/" + text(capture, "instrument") + " is not explicitly JSONL STAGING");
            }
            Path input = verifiedRegularPath(stagingRoot, text(source, "path"), "staging partition");
            byte[] inputBytes = PathConfinement.readSinglyLinkedFile(input, "staging partition");
            if (!hash(inputBytes).equals(text(source, "sha256")) || inputBytes.length != source.path("bytes").asLong(-1)) throw failure("staging partition is missing or tampered: " + text(source, "path"));
            String role = "funding_events".equals(text(capture, "series_type")) ? "funding" : "metrics_events".equals(text(capture, "series_type")) ? "metrics" : "mark_bars".equals(text(capture, "series_type")) ? "mark" : "bars";
            String base = Path.of(text(source, "path")).getFileName().toString().replaceFirst("\\.jsonl$", "");
            String relative = "parquet/" + role + "/" + base + ".parquet"; Path target = writablePath(outputRoot, relative, "Parquet output");
            ResearchData.ParquetArtifact artifact = ResearchData.writeParquet(input, target);
            List<ObjectNode> reopened = ResearchData.queryParquet(target);
            if (reopened.size() != source.path("row_count").asInt(-1)) throw failure("Parquet row count mismatch for " + text(source, "path"));
            ObjectNode next = capture.deepCopy(); ObjectNode partition = object().put("path", relative)
                    .put("sha256", artifact.sha256()).put("bytes", artifact.bytes()).put("row_count", reopened.size())
                    .put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true)
                    .put("source_jsonl_sha256", text(source, "sha256")).put("schema_sha256", parquetSchemaSha(target));
            next.set("partition", partition); converted.add(next);
        }
        ObjectNode rootInput = object().put("source_manifest_sha256", text(staging, "content_sha256"))
                .put("plan_sha256", text(staging, "plan_sha256"));
        ArrayNode roots = array(); for (JsonNode node : converted) roots.add(object().put("identity", seriesKey((ObjectNode) node)).set("partition", node.path("partition")));
        sortArray(roots, Comparator.comparing(node -> text(node, "identity"))); rootInput.set("captures", roots);
        ObjectNode value = object().put("schema", "strategy-v5-parquet-conversion/1").put("version", 1)
                .put("status", "AUTHORITATIVE_PARQUET").put("source_manifest_sha256", text(staging, "content_sha256"))
                .put("plan_sha256", text(staging, "plan_sha256")).put("output_root_reference", portableReference(outputRoot, text(options, "outputRootReference")))
                .put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true).put("threads", 1)
                .put("dataset_root_sha256", hash(rootInput)); value.set("captures", converted);
        for (String field : List.of("completion_scope", "base_complete", "declared_complete", "required_series_count", "required_complete_count", "optional_series_count", "optional_complete_count", "optional_complete")) {
            if (staging.has(field)) value.set("source_" + field, staging.get(field).deepCopy());
        }
        value.set("limitations", staging.path("limitations").isArray() ? staging.path("limitations").deepCopy() : array()); return withHash(value);
    }

    public static boolean verifyParquetConversionManifest(ObjectNode options) {
        ObjectNode manifest = options.has("manifest") ? requiredObject(options, "manifest") : options;
        Path root = options.has("root") ? requiredPath(options, "root") : requiredPath(manifest, "root");
        assertOwnHash(manifest, "strategy-v5-parquet-conversion/1", "Parquet conversion manifest");
        if (!"AUTHORITATIVE_PARQUET".equals(text(manifest, "status")) || !"PARQUET".equals(text(manifest, "format"))
                || !"AUTHORITATIVE".equals(text(manifest, "storage_role")) || !manifest.path("authoritative").asBoolean(false)
                || manifest.path("threads").asInt() != 1) throw failure("Parquet conversion manifest is not authoritative single-threaded output");
        if (options.hasNonNull("planSha256") && !text(options, "planSha256").equals(text(manifest, "plan_sha256"))) throw failure("Parquet conversion manifest is bound to a different plan");
        Set<String> paths = new HashSet<>();
        for (ObjectNode capture : objects(manifest.path("captures"))) {
            ObjectNode partition = requiredObject(capture, "partition");
            if (!"PARQUET".equals(text(partition, "format")) || !"AUTHORITATIVE".equals(text(partition, "storage_role"))
                    || !partition.path("authoritative").asBoolean(false) || !paths.add(text(partition, "path"))) throw failure("Parquet conversion partition metadata is invalid");
            byte[] bytes = readPhysical(root, text(partition, "path"), "Parquet partition");
            if (!hash(bytes).equals(text(partition, "sha256")) || bytes.length != partition.path("bytes").asLong(-1)
                    || partition.path("row_count").asInt(-1) < 0 || !isSha(text(partition, "schema_sha256"))) throw failure("Parquet partition is missing or tampered: " + text(partition, "path"));
        }
        ObjectNode rootInput = object().put("source_manifest_sha256", text(manifest, "source_manifest_sha256"))
                .put("plan_sha256", text(manifest, "plan_sha256")); ArrayNode roots = array();
        for (ObjectNode capture : objects(manifest.path("captures"))) roots.add(object().put("identity", seriesKey(capture)).set("partition", capture.path("partition")));
        sortArray(roots, Comparator.comparing(node -> text(node, "identity"))); rootInput.set("captures", roots);
        if (!hash(rootInput).equals(text(manifest, "dataset_root_sha256"))) throw failure("Parquet conversion dataset root is invalid"); return true;
    }

    public static boolean verifyParquetConversionManifestAuthoritative(ObjectNode options) {
        verifyParquetConversionManifest(options); ObjectNode manifest = requiredObject(options, "manifest"); Path root = requiredPath(options, "root");
        if (objects(manifest.path("captures")).stream().anyMatch(row -> "funding_events".equals(text(row, "series_type"))) && !options.hasNonNull("stagingRoot")) {
            throw failure("authoritative funding Parquet verification requires the physical acquisition/staging root");
        }
        for (ObjectNode capture : objects(manifest.path("captures"))) {
            ObjectNode partition = requiredObject(capture, "partition"); Path path = verifiedRegularPath(root, text(partition, "path"), "Parquet partition");
            List<ObjectNode> rows = ResearchData.queryParquet(path);
            if (rows.size() != partition.path("row_count").asInt(-1)) throw failure("reopened acquisition Parquet row count differs from the bound count: " + text(partition, "path"));
            if (!parquetSchemaSha(path).equals(text(partition, "schema_sha256"))) throw failure("reopened acquisition Parquet schema differs from the bound schema: " + text(partition, "path"));
            if ("funding_events".equals(text(capture, "series_type"))) for (ObjectNode row : rows) {
                if (!(row.path("settlement_mark").asDouble() > 0) || row.path("settlement_mark").asDouble() != row.path("mark_price").asDouble()
                        || !"BINANCE_MARK_PRICE_KLINE_OPEN_AT_SETTLEMENT".equals(text(row, "settlement_mark_source"))
                        || !isSha(text(row, "settlement_mark_source_response_sha256"))) throw failure("reopened funding Parquet role/provenance validation failed");
            }
        }
        return true;
    }

    public static boolean verifyParquetConversion(ObjectNode options) {
        ObjectNode manifest = requiredObject(options, "manifest");
        if (DATA_V5.get("artifacts").equals(text(manifest, "schema"))) return verifySeparatedArtifactManifest(options);
        if ("strategy-v5-parquet-conversion/1".equals(text(manifest, "schema"))) return verifyParquetConversionManifest(options);
        throw failure("unsupported Parquet conversion manifest schema: " + text(manifest, "schema"));
    }

    public static ObjectNode makeSeparatedArtifactManifest(ObjectNode options) {
        ObjectNode plan = requiredObject(options, "plan"); validatePlan(plan); String planSha = textOr(options.get("planSha256"), text(plan, "content_sha256"));
        if (!planSha.equals(text(plan, "content_sha256"))) throw failure("artifact plan hash mismatch"); Path root = requiredPath(options, "root");
        ObjectNode registry = requiredObject(options, "predictorRegistry"); LinkedHashMap<String, ObjectNode> predictors = validatePredictorRegistry(registry);
        for (String field : List.of("sourceManifestSha256", "sourceDatasetRootSha256", "transformationCodeSha256", "labelCodeSha256", "executionCodeSha256", "configSha256", "precommitSha256", "envelopeSha256")) requireSha(text(options, field), field);
        ArrayNode predicateInventory = options.path("candidatePredicates").isArray() ? (ArrayNode) options.path("candidatePredicates") : array();
        ObjectNode predicateCheck = object(); predicateCheck.set("predictorRegistry", registry); predicateCheck.set("predicates", predicateInventory); validateCandidatePredicates(predicateCheck);
        ObjectNode sourceReference = requiredObject(options, "sourceManifestReference");
        ObjectNode sourceCheck = object().put("root", root.toString()).put("expectedContentSha256", text(options, "sourceManifestSha256"))
                .put("planSha256", planSha).put("label", "separated source manifest"); sourceCheck.set("reference", sourceReference); verifyAuthoritativeSourceChain(sourceCheck);
        ObjectNode lineage = object().put("plan_sha256", planSha).put("source_manifest_sha256", text(options, "sourceManifestSha256"))
                .put("source_dataset_root_sha256", text(options, "sourceDatasetRootSha256")).put("predictor_registry_sha256", text(registry, "content_sha256"))
                .put("transformation_code_sha256", text(options, "transformationCodeSha256")).put("label_code_sha256", text(options, "labelCodeSha256"))
                .put("execution_code_sha256", text(options, "executionCodeSha256")).put("config_sha256", text(options, "configSha256"))
                .put("precommit_sha256", text(options, "precommitSha256")).put("envelope_sha256", text(options, "envelopeSha256"));
        ObjectNode artifacts = object(); Set<String> paths = new HashSet<>(); Map<String, List<ObjectNode>> roleRows = new LinkedHashMap<>();
        for (String role : List.of("FEATURE", "LABEL", "EXECUTION", "MARK")) {
            String key = role.toLowerCase(Locale.ROOT); ObjectNode reference = requiredObject(options, "execution".equals(key) ? "execution" : key + "s");
            if (text(reference, "path").isEmpty() || !paths.add(text(reference, "path"))) throw failure("artifact " + role + " path is missing or reused");
            Path path = verifiedRegularPath(root, text(reference, "path"), role + " staging artifact"); byte[] bytes = PathConfinement.readSinglyLinkedFile(path, role + " staging artifact");
            if (reference.hasNonNull("sha256") && !hash(bytes).equals(text(reference, "sha256"))) throw failure("artifact " + role + " hash mismatch");
            List<ObjectNode> rows = readJsonlBytes(bytes, role + " staging artifact");
            if ("FEATURE".equals(role)) validateSeparatedFeatureRows(rows, registry, plan, predicateInventory);
            else if ("LABEL".equals(role)) validateSeparatedLabelRows(rows, plan);
            else if ("EXECUTION".equals(role)) validateSeparatedExecutionRows(rows, plan);
            else validateSeparatedMarkRows(rows, plan);
            roleRows.put(key, rows);
            ObjectNode artifact = object().put("role", role).put("path", text(reference, "path")).put("sha256", hash(bytes)).put("bytes", bytes.length)
                    .put("row_count", rows.size()).put("format", "JSONL").put("storage_role", "STAGING").put("authoritative", false)
                    .put("rows_sha256", hash(array(rows))).put("field_names", ""); artifact.set("field_names", fieldNames(rows));
            JsonNode receipt = options.path("roleReceipts").path(key); if (!receipt.isObject()) throw failure(role + " role derivation receipt requires a physical path/content/byte binding");
            if (text(receipt, "path").isEmpty() || !isSha(text(receipt, "content_sha256")) || !isSha(text(receipt, "byte_sha256"))) throw failure(role + " role derivation receipt requires a physical path/content/byte binding");
            verifyRoleDerivationReceipt(root, (ObjectNode) receipt, role, hash(bytes), lineage);
            artifact.put("derivation_receipt_path", text(receipt, "path")).put("derivation_receipt_sha256", text(receipt, "content_sha256"))
                    .put("derivation_receipt_byte_sha256", text(receipt, "byte_sha256")); artifacts.set(key, artifact);
        }
        validateSeparatedRoleCrossBindings(roleRows.getOrDefault("feature", List.of()), roleRows.getOrDefault("label", List.of()), roleRows.getOrDefault("execution", List.of()), roleRows.getOrDefault("mark", List.of()));
        ObjectNode rootFields = artifactRootFields(planSha, registry, options, sourceReference, artifacts);
        ObjectNode value = object().put("schema", DATA_V5.get("artifacts")).put("version", 1).put("status", "STAGING_ONLY")
                .put("plan_sha256", planSha).put("predictor_registry_sha256", text(registry, "content_sha256"))
                .put("source_manifest_sha256", text(options, "sourceManifestSha256")).put("source_dataset_root_sha256", text(options, "sourceDatasetRootSha256"))
                .put("transformation_code_sha256", text(options, "transformationCodeSha256")).put("label_code_sha256", text(options, "labelCodeSha256"))
                .put("execution_code_sha256", text(options, "executionCodeSha256")).put("config_sha256", text(options, "configSha256"))
                .put("precommit_sha256", text(options, "precommitSha256")).put("envelope_sha256", text(options, "envelopeSha256"))
                .put("storage_role", "STAGING").put("format", "JSONL").put("authoritative", false)
                .put("dataset_root_sha256", hash(rootFields)).put("conversion_required", "PARQUET").put("conversion_status", "AVAILABLE_LOCAL_DUCKDB");
        value.set("predictor_ids", strings(predictors.keySet().stream().sorted().toList())); value.set("candidate_predicates", predicateInventory.deepCopy());
        value.set("source_manifest_reference", sourceReference.deepCopy()); value.set("artifacts", artifacts); return withHash(value);
    }

    public static boolean verifySeparatedArtifactManifest(ObjectNode options) {
        ObjectNode manifest = requiredObject(options, "manifest"), plan = requiredObject(options, "plan"); Path root = requiredPath(options, "root");
        assertOwnHash(manifest, DATA_V5.get("artifacts"), "separated artifact manifest");
        if (!text(manifest, "plan_sha256").equals(text(plan, "content_sha256"))) throw failure("separated artifact manifest is not bound to the supplied plan");
        boolean requireParquet = options.path("requireParquet").asBoolean(false);
        if (requireParquet && !("PARQUET".equals(text(manifest, "format")) && "AUTHORITATIVE_PARQUET".equals(text(manifest, "status"))
                && "AUTHORITATIVE".equals(text(manifest, "storage_role")) && manifest.path("authoritative").asBoolean(false))) throw failure("authoritative artifacts require verified Parquet conversion");
        if ("PARQUET".equals(text(manifest, "format")) && (!manifest.path("conversion").isObject() || !isSha(text(manifest.path("conversion"), "source_artifact_manifest_sha256")) || !manifest.path("conversion").path("source_artifact_manifest_reference").isObject())) throw failure("Parquet artifact conversion source manifest is not physically bound");
        for (String field : List.of("source_manifest_sha256", "source_dataset_root_sha256", "transformation_code_sha256", "label_code_sha256", "execution_code_sha256", "config_sha256", "precommit_sha256", "envelope_sha256", "predictor_registry_sha256")) requireSha(text(manifest, field), field);
        ObjectNode registry = options.path("predictorRegistry").isObject() ? (ObjectNode) options.path("predictorRegistry") : null;
        JsonNode predicates = manifest.path("candidate_predicates");
        if (registry != null) {
            LinkedHashMap<String, ObjectNode> validated = validatePredictorRegistry(registry); List<String> manifestIds = predicateInventoryIds(manifest.path("predictor_ids"), "manifest predictor inventory");
            if (!manifestIds.equals(validated.keySet().stream().sorted().toList())) throw failure("separated artifact predictor inventory does not exactly match the frozen predictor registry");
            if (!text(manifest, "predictor_registry_sha256").equals(text(registry, "content_sha256"))) throw failure("separated artifact predictor registry is not bound");
        }
        ObjectNode predicateCheck = object().set("predictorRegistry", registry == null ? object() : registry);
        if (registry != null) { predicateCheck.set("predicates", predicates); validateCandidatePredicates(predicateCheck); }
        List<String> declaredPredicateIds = predicateInventoryIds(predicates, "manifest candidate predicate inventory");
        JsonNode supplied = options.has("candidatePredicates") ? options.path("candidatePredicates") : predicates;
        List<String> suppliedPredicateIds = predicateInventoryIds(supplied, "evaluator predicate inventory"); if (!declaredPredicateIds.equals(suppliedPredicateIds)) throw failure("separated artifact predicate inventory does not exactly match the evaluator predicate IDs");
        ObjectNode sourceReference = requiredObject(manifest, "source_manifest_reference");
        ObjectNode sourceCheck = object().put("root", root.toString()).put("expectedContentSha256", text(manifest, "source_manifest_sha256")).put("planSha256", text(manifest, "plan_sha256")).put("label", "separated source manifest"); sourceCheck.set("reference", sourceReference); verifyAuthoritativeSourceChain(sourceCheck);
        if ("PARQUET".equals(text(manifest, "format"))) verifyPhysicalJsonReference(root, (ObjectNode) manifest.path("conversion").path("source_artifact_manifest_reference"), text(manifest.path("conversion"), "source_artifact_manifest_sha256"), "Parquet source staging manifest");
        Set<String> expected = Set.of("feature", "label", "execution", "mark"); Set<String> actual = new HashSet<>(); manifest.path("artifacts").fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw failure("separated artifact roles are incomplete or duplicated");
        Map<String, List<ObjectNode>> roleRows = new LinkedHashMap<>(); ObjectNode lineage = manifest;
        for (String role : expected) {
            ObjectNode artifact = (ObjectNode) manifest.path("artifacts").path(role); String expectedFormat = "PARQUET".equals(text(manifest, "format")) ? "PARQUET" : "JSONL";
            if (!role.toUpperCase(Locale.ROOT).equals(text(artifact, "role").toUpperCase(Locale.ROOT)) || !expectedFormat.equals(text(artifact, "format")) || !("PARQUET".equals(expectedFormat) ? "AUTHORITATIVE" : "STAGING").equals(text(artifact, "storage_role")) || artifact.path("authoritative").asBoolean(false) != "PARQUET".equals(expectedFormat)) throw failure("artifact " + role + " role/storage metadata is invalid");
            byte[] bytes = readPhysical(root, text(artifact, "path"), role + " artifact"); if (!isSha(text(artifact, "sha256")) || !hash(bytes).equals(text(artifact, "sha256")) || bytes.length != artifact.path("bytes").asLong(-1)) throw failure("artifact bytes are missing or tampered: " + text(artifact, "path"));
            if (!artifact.path("row_count").canConvertToLong() || artifact.path("row_count").asLong() < 0) throw failure("artifact " + role + " row count metadata is invalid");
            ObjectNode receiptRef = object().put("path", text(artifact, "derivation_receipt_path")).put("content_sha256", text(artifact, "derivation_receipt_sha256")).put("byte_sha256", text(artifact, "derivation_receipt_byte_sha256")); if (text(artifact, "derivation_receipt_path").isEmpty()) throw failure(role + " artifact lacks a physical derivation receipt reference");
            String receiptArtifactSha = "JSONL".equals(expectedFormat) ? text(artifact, "sha256") : text(artifact, "source_jsonl_sha256"); verifyRoleDerivationReceipt(root, receiptRef, role.toUpperCase(Locale.ROOT), receiptArtifactSha, lineage);
            if ("JSONL".equals(expectedFormat)) { List<ObjectNode> rows = readJsonlBytes(bytes, role + " artifact"); if (rows.size() != artifact.path("row_count").asInt(-1) || !hash(array(rows)).equals(text(artifact, "rows_sha256"))) throw failure("artifact " + role + " rows are missing or tampered"); roleRows.put(role, rows); }
        }
        if ("JSONL".equals(text(manifest, "format"))) {
            if (registry != null) { validateSeparatedFeatureRows(roleRows.get("feature"), registry, plan, predicates); validateSeparatedLabelRows(roleRows.get("label"), plan); validateSeparatedExecutionRows(roleRows.get("execution"), plan); validateSeparatedMarkRows(roleRows.get("mark"), plan); }
            validateSeparatedRoleCrossBindings(roleRows.get("feature"), roleRows.get("label"), roleRows.get("execution"), roleRows.get("mark"));
        }
        ObjectNode rootFields = artifactRootFields(text(manifest, "plan_sha256"), null, manifest, (ObjectNode) manifest.path("source_manifest_reference"), (ObjectNode) manifest.path("artifacts"));
        if (!hash(rootFields).equals(text(manifest, "dataset_root_sha256"))) throw failure("separated artifact dataset root is invalid"); return true;
    }

    public static ObjectNode convertSeparatedArtifactsToParquet(ObjectNode options) {
        ObjectNode staging = requiredObject(options, "stagingManifest"), plan = requiredObject(options, "plan"); Path stagingRoot = requiredPath(options, "stagingRoot"), outputRoot = requiredPath(options, "outputRoot");
        ObjectNode verify = object(); verify.set("manifest", staging); verify.set("plan", plan); verify.put("root", stagingRoot.toString()); verifySeparatedArtifactManifest(verify);
        ObjectNode artifacts = object();
        for (String role : List.of("feature", "label", "execution", "mark")) {
            ObjectNode source = (ObjectNode) staging.path("artifacts").path(role); Path input = verifiedRegularPath(stagingRoot, text(source, "path"), role + " staging artifact");
            String relative = "parquet/roles/" + role + "-" + text(source, "sha256") + ".parquet"; Path target = writablePath(outputRoot, relative, role + " Parquet artifact");
            ResearchData.ParquetArtifact physical = ResearchData.writeParquet(input, target); List<ObjectNode> rows = ResearchData.queryParquet(target);
            if (rows.size() != source.path("row_count").asInt(-1)) throw failure("Parquet row count mismatch for " + role);
            ObjectNode artifact = source.deepCopy(); artifact.put("path", relative).put("sha256", physical.sha256()).put("bytes", physical.bytes())
                    .put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true)
                    .put("source_jsonl_sha256", text(source, "sha256")).put("source_row_count", source.path("row_count").asInt())
                    .put("schema_sha256", parquetSchemaSha(target)); artifacts.set(role, artifact);
        }
        ObjectNode value = staging.deepCopy(); value.remove("content_sha256"); value.put("status", "AUTHORITATIVE_PARQUET").put("storage_role", "AUTHORITATIVE").put("format", "PARQUET").put("authoritative", true);
        value.set("artifacts", artifacts); ObjectNode sourceReference = persistPhysicalJsonInput(outputRoot, staging, text(staging, "content_sha256"), "source-artifact-manifest");
        sourceReference.remove("bytes");
        ObjectNode conversion = object().put("source_artifact_manifest_sha256", text(staging, "content_sha256"))
                .put("runtime", "java-duckdb/research-data").put("dependency", "org.duckdb:duckdb_jdbc:1.5.5.1").put("threads", 1).put("deterministic", true);
        conversion.set("source_artifact_manifest_reference", sourceReference); value.set("conversion", conversion);
        value.put("dataset_root_sha256", hash(artifactRootFields(text(value, "plan_sha256"), null, value,
                (ObjectNode) value.path("source_manifest_reference"), artifacts))); return withHash(value);
    }

    public static boolean verifyParquetArtifactManifest(ObjectNode options) {
        ObjectNode verify = options.deepCopy(); verify.put("requireParquet", true); verifySeparatedArtifactManifest(verify);
        ObjectNode manifest = requiredObject(options, "manifest"); Path root = requiredPath(options, "root");
        for (String role : List.of("feature", "label", "execution", "mark")) {
            ObjectNode artifact = (ObjectNode) manifest.path("artifacts").path(role); Path path = verifiedRegularPath(root, text(artifact, "path"), role + " Parquet artifact"); List<ObjectNode> rows = ResearchData.queryParquet(path);
            if (rows.size() != artifact.path("row_count").asInt(-1) || !parquetSchemaSha(path).equals(text(artifact, "schema_sha256"))) throw failure("reopened Parquet " + role + " differs from bound metadata");
        }
        return true;
    }

    public static ArrayNode readVerifiedFeatureBatches(ObjectNode options) {
        verifyParquetArtifactManifest(options); ObjectNode manifest = requiredObject(options, "manifest"); Path root = requiredPath(options, "root");
        ObjectNode feature = (ObjectNode) manifest.path("artifacts").path("feature"); List<ObjectNode> rows = ResearchData.queryParquet(verifiedRegularPath(root, text(feature, "path"), "FEATURE Parquet artifact"));
        int batchSize = options.path("batchSize").asInt(10_000); if (batchSize <= 0 || batchSize > 100_000) throw failure("feature Parquet batch size is outside the bounded range");
        Set<String> allowed = new HashSet<>(List.of("asset", "symbol", "venue", "instrument", "timeframe", "event_time", "decision_time", "availability_time", "signal_eligible", "signal_id", "episode_id"));
        for (ObjectNode predictor : objects(requiredObject(options, "predictorRegistry").path("predictors"))) allowed.add(text(predictor, "id"));
        List<String> selected = options.path("columns").isArray() ? texts(options.path("columns")) : allowed.stream().sorted().toList();
        if (selected.isEmpty() || selected.stream().anyMatch(name -> !allowed.contains(name))) throw failure("feature reader requested an undeclared, unavailable, or outcome-role column");
        Set<String> episodes = new HashSet<>(uniqueTextsOrEmpty(options.get("episodeIds"))); Long start = defined(options.get("decisionStart")) ? time(options.get("decisionStart")) : null, end = defined(options.get("decisionEnd")) ? time(options.get("decisionEnd")) : null;
        List<ObjectNode> selectedRows = rows.stream().filter(row -> episodes.isEmpty() || episodes.contains(text(row, "episode_id")))
                .filter(row -> start == null || time(row.get("decision_time")) >= start).filter(row -> end == null || time(row.get("decision_time")) <= end)
                .sorted(Comparator.comparing(StrategyResearchDataV5::roleIdentityKey)).map(row -> { ObjectNode result = object(); for (String name : selected) if (row.has(name)) result.set(name, row.get(name).deepCopy()); return result; }).toList();
        ArrayNode batches = array(); for (int index = 0; index < selectedRows.size(); index += batchSize) batches.add(array(selectedRows.subList(index, Math.min(selectedRows.size(), index + batchSize)))); return batches;
    }

    private static String parquetSchemaSha(Path parquet) {
        String path = parquet.toAbsolutePath().normalize().toString().replace("'", "''");
        ArrayNode rows = array();
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:"); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("DESCRIBE SELECT * FROM read_parquet('" + path + "')")) {
            ResultSetMetaData metadata = result.getMetaData();
            while (result.next()) {
                ArrayNode row = array();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    Object value = result.getObject(index); if (value == null) row.addNull(); else row.add(String.valueOf(value));
                }
                rows.add(row);
            }
            return hash(rows);
        } catch (Exception error) {
            throw failure("DuckDB Parquet schema reopen failed for " + parquet + ": " + error.getMessage());
        }
    }
    private static ArrayNode fieldNames(List<ObjectNode> rows) { return strings(rows.stream().flatMap(row -> { List<String> names = new ArrayList<>(); row.fieldNames().forEachRemaining(names::add); return names.stream(); }).distinct().sorted().toList()); }
    private static ObjectNode artifactRootFields(String planSha, ObjectNode registry, ObjectNode source, ObjectNode sourceReference, ObjectNode artifacts) {
        ObjectNode result = object().put("plan_sha256", planSha)
                .put("predictor_registry_sha256", registry == null ? text(source, "predictor_registry_sha256") : text(registry, "content_sha256"))
                .put("source_manifest_sha256", textOr(first(source, "sourceManifestSha256", "source_manifest_sha256"), ""))
                .put("source_dataset_root_sha256", textOr(first(source, "sourceDatasetRootSha256", "source_dataset_root_sha256"), ""))
                .put("transformation_code_sha256", textOr(first(source, "transformationCodeSha256", "transformation_code_sha256"), ""))
                .put("label_code_sha256", textOr(first(source, "labelCodeSha256", "label_code_sha256"), ""))
                .put("execution_code_sha256", textOr(first(source, "executionCodeSha256", "execution_code_sha256"), ""))
                .put("config_sha256", textOr(first(source, "configSha256", "config_sha256"), ""))
                .put("precommit_sha256", textOr(first(source, "precommitSha256", "precommit_sha256"), ""))
                .put("envelope_sha256", textOr(first(source, "envelopeSha256", "envelope_sha256"), ""));
        result.set("source_manifest_reference", sourceReference.deepCopy()); result.set("artifacts", artifacts.deepCopy()); return result;
    }

    private static ObjectNode verifyRoleDerivationReceipt(Path root, ObjectNode reference, String role,
            String artifactSha, ObjectNode lineage) {
        if (reference == null || text(reference, "path").isEmpty() || !isSha(text(reference, "content_sha256"))
                || !isSha(text(reference, "byte_sha256"))) throw failure(role + " role derivation receipt requires a physical path/content/byte binding");
        ObjectNode receipt;
        try { receipt = verifyPhysicalJsonReference(root, reference, text(reference, "content_sha256"), role + " derivation receipt"); }
        catch (RuntimeException error) { throw failure(role + " role derivation receipt is invalid: " + error.getMessage()); }
        if (!"strategy-v5-role-derivation-receipt/1".equals(text(receipt, "schema"))
                || !role.equals(text(receipt, "role")) || !artifactSha.equals(text(receipt, "artifact_sha256"))
                || !text(lineage, "source_manifest_sha256").equals(text(receipt, "source_manifest_sha256"))
                || !text(lineage, "source_dataset_root_sha256").equals(text(receipt, "source_dataset_root_sha256"))
                || !text(lineage, "precommit_sha256").equals(text(receipt, "precommit_sha256"))
                || !text(lineage, "envelope_sha256").equals(text(receipt, "envelope_sha256"))
                || !text(lineage, "predictor_registry_sha256").equals(text(receipt, "predictor_registry_sha256"))) {
            throw failure(role + " derivation receipt is not bound to the physical artifact lineage");
        }
        if (!"AUTHORITATIVE_INTERNAL".equals(text(receipt, "provenance_mode"))) throw failure(role + " derivation receipt is FIXTURE_ONLY or otherwise not authoritative");
        String command = DATA_V5_PRODUCER_COMMANDS.get(role);
        if (command == null || !command.equals(text(receipt, "producer_command")) || !javaProducerCodeSha256().equals(text(receipt, "producer_code_sha256"))) throw failure(role + " role derivation receipt producer code hash is not registered");
        verifyPhysicalByteReference(root, (ObjectNode) receipt.path("producer_code_reference"), javaProducerCodeSha256(), role + " producer code");
        ObjectNode inputs = object();
        inputs.set("plan_reference", object().put("expected", text(lineage, "plan_sha256")));
        inputs.set("predictor_registry_reference", object().put("expected", text(lineage, "predictor_registry_sha256")));
        inputs.set("precommit_reference", object().put("expected", text(lineage, "precommit_sha256")));
        inputs.set("envelope_reference", object().put("expected", text(lineage, "envelope_sha256")));
        inputs.set("config_reference", object().put("expected", text(lineage, "config_sha256")));
        for (String field : List.of("plan_reference", "predictor_registry_reference", "precommit_reference", "envelope_reference", "config_reference")) {
            ObjectNode value = (ObjectNode) receipt.path(field); String expected = text(inputs.path(field), "expected");
            ObjectNode input = verifyPhysicalJsonReference(root, value, expected, role + " " + field.replace("_reference", " input"));
            if ("plan_reference".equals(field) && !DATA_V5.get("plan").equals(text(input, "schema"))) throw failure(role + " plan input is not the authoritative v5 plan");
            if ("predictor_registry_reference".equals(field) && !"strategy-v5-predictor-registry/1".equals(text(input, "schema"))) throw failure(role + " predictor registry input is not the frozen registry");
        }
        String expectedCode = switch (role) { case "FEATURE", "MARK" -> text(lineage, "transformation_code_sha256"); case "LABEL" -> text(lineage, "label_code_sha256"); default -> text(lineage, "execution_code_sha256"); };
        if (!expectedCode.equals(text(receipt, "code_sha256"))) throw failure(role + " derivation receipt code binding is invalid");
        verifyPhysicalByteReference(root, (ObjectNode) receipt.path("code_reference"), expectedCode, role + " derivation code");
        return receipt;
    }

    private static void verifyPhysicalByteReference(Path root, ObjectNode reference, String expected, String label) {
        if (reference == null || text(reference, "path").isEmpty() || !isSha(text(reference, "byte_sha256"))
                || !reference.path("bytes").canConvertToLong() || reference.path("bytes").asLong() < 1) throw failure(label + " must include a path, byte hash, and byte count");
        if (expected != null && !expected.equals(text(reference, "byte_sha256"))) throw failure(label + " is not bound to the registered producer bytes");
        byte[] bytes = readPhysical(root, text(reference, "path"), label);
        if (bytes.length != reference.path("bytes").asLong() || !hash(bytes).equals(text(reference, "byte_sha256"))) throw failure(label + " bytes are missing or tampered: " + text(reference, "path"));
    }

    private static boolean inPlanWindow(long value, ObjectNode plan) {
        return value >= time(plan.path("window").get("start_at")) && value <= time(plan.path("window").get("completed_through_at"));
    }

    private static String artifactRoleIdentity(ObjectNode row, String role) {
        if (row == null || text(row, "asset").isEmpty() || text(row, "venue").isEmpty() || text(row, "instrument").isEmpty() || text(row, "symbol").isEmpty()) throw failure(role + " identity is incomplete");
        return predictorAsset(row) + "|" + text(row, "venue").toLowerCase(Locale.ROOT) + "|" + text(row, "instrument").toUpperCase(Locale.ROOT) + "|" + text(row, "symbol").toUpperCase(Locale.ROOT);
    }

    private static void validateSeparatedFeatureRows(List<ObjectNode> rows, ObjectNode registryNode, ObjectNode plan, JsonNode candidatePredicates) {
        ObjectNode predicateCheck = object(); predicateCheck.set("predictorRegistry", registryNode); predicateCheck.set("predicates", candidatePredicates); validateCandidatePredicates(predicateCheck);
        LinkedHashMap<String, ObjectNode> registry = validatePredictorRegistry(registryNode); Set<String> seen = new HashSet<>();
        Set<String> base = Set.of("asset", "symbol", "venue", "instrument", "timeframe", "event_time", "decision_time", "availability_time", "signal_eligible", "signal_id", "episode_id");
        for (ObjectNode row : rows) {
            String identity = predictorAsset(row) + "|" + text(row, "venue").toLowerCase(Locale.ROOT) + "|" + text(row, "instrument").toUpperCase(Locale.ROOT) + "|" + text(row, "symbol").toUpperCase(Locale.ROOT) + "|" + time(first(row, "decision_time", "event_time"));
            if (!seen.add(identity)) throw failure("feature decision identity is missing or duplicated: " + identity);
            long decision = time(first(row, "decision_time", "event_time")), available = time(row.get("availability_time"));
            if (text(row, "signal_id").isEmpty() || text(row, "episode_id").isEmpty() || text(row, "venue").isEmpty() || text(row, "instrument").isEmpty() || text(row, "symbol").isEmpty() || !inPlanWindow(decision, plan) || available > decision) throw failure("feature row is outside the plan, lacks exact series identity, or is not PIT-available");
            Iterator<String> names = row.fieldNames(); while (names.hasNext()) { String name = names.next(); if (!base.contains(name) && !registry.containsKey(name)) throw failure("feature field is undeclared or not in the frozen predictor registry: " + name); if (registry.containsKey(name) && !row.get(name).isNull() && (!row.get(name).isValueNode() || row.get(name).isFloatingPointNumber() && !Double.isFinite(row.get(name).doubleValue()))) throw failure("predictor " + name + " is non-scalar/non-finite in an evaluated feature row"); }
            for (ObjectNode predictor : registry.values()) { String id = text(predictor, "id"); if (!row.has(id)) throw failure("feature row is missing registered predictor " + id); JsonNode value = row.get(id); if (value.isNull()) { if (row.path("signal_eligible").asBoolean(true)) throw failure("eligible feature row has null registered predictor " + id); continue; } String scalar = text(predictor, "scalar_type"); if ("number".equals(scalar) && !value.isNumber() || "integer".equals(scalar) && !value.isIntegralNumber() || "boolean".equals(scalar) && !value.isBoolean()) throw failure("predictor " + id + " does not match its registered scalar type"); if (text(predictor, "source_family").toUpperCase(Locale.ROOT).contains("LABEL") || !"PREDICTOR".equals(text(predictor, "pit_role").toUpperCase(Locale.ROOT))) throw failure("predictor " + id + " has label-role provenance"); }
        }
    }

    private static void validateSeparatedLabelRows(List<ObjectNode> rows, ObjectNode plan) {
        Set<String> seen = new HashSet<>(); for (ObjectNode row : rows) { String id = text(row, "episode_id") + "|" + text(row, "signal_id"); if (!seen.add(id)) throw failure("label episode/signal identity is missing or duplicated: " + id); artifactRoleIdentity(row, "label"); long decision = time(first(row, "decision_time", "event_time")), entry = time(row.get("entry_time")), ceiling = time(first(row, "resolution_ceiling_time", "resolution_time", "outcome_time", "exit_time")), available = time(row.get("availability_time")); if (text(row, "episode_id").isEmpty() || text(row, "signal_id").isEmpty() || !inPlanWindow(decision, plan) || entry != decision || ceiling <= entry || available < ceiling) throw failure("label outcome path is not chronological/PIT-bound"); }
    }

    private static void validateSeparatedExecutionRows(List<ObjectNode> rows, ObjectNode plan) {
        Set<String> seen = new HashSet<>(); for (ObjectNode row : rows) { String id = text(row, "signal_id") + "|" + text(row, "episode_id"); if (!seen.add(id)) throw failure("execution signal/episode identity is missing or duplicated: " + id); artifactRoleIdentity(row, "execution"); if ("BINANCE_SPOT".equals(text(row, "instrument").toUpperCase(Locale.ROOT)) && "short".equals(text(row, "direction").toLowerCase(Locale.ROOT))) throw failure("short BINANCE_SPOT execution is not supported; bind a derivative instrument"); for (String name : PRECOMPUTED_EXECUTION) if (row.has(name)) throw failure("execution artifact contains caller-computed PnL field: " + name); long decision = time(first(row, "decision_time", "entry_time")); List<ObjectNode> bars = objects(row.path("child_bars")); if (!inPlanWindow(decision, plan) || bars.size() < 2) throw failure("execution row is outside the plan or lacks child bars"); bars = bars.stream().map(ObjectNode::deepCopy).sorted(Comparator.comparingLong(StrategyResearchDataV5::rowTime)).toList(); Set<Long> barTimes = new HashSet<>(); for (int index = 0; index < bars.size(); index++) { long event = rowTime(bars.get(index)); if (!barTimes.add(event) || index == 0 && event != decision || index > 0 && event != rowTime(bars.get(index - 1)) + ONE_MINUTE || rowAvailability(bars.get(index)) < event + ONE_MINUTE - 1_000) throw failure("execution child path is not dense, boundary-aligned, unique, and complete"); }
            if (!"BINANCE_SPOT".equals(text(row, "instrument").toUpperCase(Locale.ROOT))) { List<ObjectNode> marks = objects(row.path("mark_bars")).stream().map(ObjectNode::deepCopy).sorted(Comparator.comparingLong(StrategyResearchDataV5::rowTime)).toList(); if (marks.size() != bars.size()) throw failure("derivative execution artifact lacks a separately bound mark path"); for (int index = 0; index < marks.size(); index++) if (rowTime(marks.get(index)) != rowTime(bars.get(index)) || rowAvailability(marks.get(index)) < rowTime(marks.get(index)) + ONE_MINUTE - 1_000 || !(numeric(marks.get(index).get("mark_high")) > 0) || !(numeric(marks.get(index).get("mark_low")) > 0) || numeric(marks.get(index).get("mark_low")) > numeric(marks.get(index).get("mark_high"))) throw failure("derivative mark path is not aligned, complete, or positive"); }
        }
    }

    private static void validateSeparatedMarkRows(List<ObjectNode> rows, ObjectNode plan) {
        Set<String> seen = new HashSet<>(); Map<String, List<Long>> groups = new HashMap<>(); for (ObjectNode row : rows) { long event = time(first(row, "event_time", "time")); long cadence = integer(first(row, "cadence_ms", "expected_step_ms"), "mark cadence"); String id = predictorAsset(row) + "|" + text(row, "venue").toLowerCase(Locale.ROOT) + "|" + text(row, "instrument").toUpperCase(Locale.ROOT) + "|" + text(row, "symbol").toUpperCase(Locale.ROOT) + "|" + text(row, "series_id") + "|" + event; if (!seen.add(id) || !"MARK".equals(text(row, "series_role")) || text(row, "series_id").isEmpty() || text(row, "venue").isEmpty() || text(row, "instrument").isEmpty() || text(row, "symbol").isEmpty() || cadence <= 0 || !inPlanWindow(event, plan) || !(numeric(first(row, "price", "close")) > 0) || rowAvailability(row) < event + cadence - 1_000) throw failure("mark row lacks explicit series role/cadence/identity or has invalid availability/price"); String group = predictorAsset(row) + "|" + text(row, "venue").toLowerCase(Locale.ROOT) + "|" + text(row, "instrument").toUpperCase(Locale.ROOT) + "|" + text(row, "symbol").toUpperCase(Locale.ROOT) + "|" + text(row, "series_id"); groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(event); }
        Map<String, Long> cadences = new HashMap<>();
        for (ObjectNode row : rows) {
            String group = predictorAsset(row) + "|" + text(row, "venue").toLowerCase(Locale.ROOT) + "|" + text(row, "instrument").toUpperCase(Locale.ROOT) + "|" + text(row, "symbol").toUpperCase(Locale.ROOT) + "|" + text(row, "series_id");
            long cadence = integer(first(row, "cadence_ms", "expected_step_ms"), "mark cadence");
            Long prior = cadences.putIfAbsent(group, cadence);
            if (prior != null && prior.longValue() != cadence) throw failure("mark lifecycle/series cadence changes within a physical series");
        }
        for (Map.Entry<String, List<Long>> entry : groups.entrySet()) { List<Long> events = entry.getValue(); events.sort(Long::compareTo); long cadence = cadences.get(entry.getKey()); for (int index = 1; index < events.size(); index++) if (events.get(index) != events.get(index - 1) + cadence) throw failure("mark lifecycle/series coverage is not dense"); }
    }

    private static void validateSeparatedRoleCrossBindings(List<ObjectNode> features, List<ObjectNode> labels, List<ObjectNode> executions, List<ObjectNode> marks) {
        Map<String, String> feature = new LinkedHashMap<>(); Map<String, Long> decisions = new HashMap<>(); for (ObjectNode row : features) { if (row.path("signal_eligible").asBoolean(true)) { String id = text(row, "signal_id") + "|" + text(row, "episode_id"); if (feature.put(id, artifactRoleIdentity(row, "feature")) != null) throw failure("feature signal/episode identity is duplicated: " + id); decisions.put(id, time(first(row, "decision_time", "event_time"))); } }
        Map<String, String> label = new LinkedHashMap<>(); for (ObjectNode row : labels) { String id = text(row, "signal_id") + "|" + text(row, "episode_id"); if (label.put(id, artifactRoleIdentity(row, "label")) != null) throw failure("label signal/episode identity is duplicated: " + id); if (!feature.containsKey(id) || !feature.get(id).equals(artifactRoleIdentity(row, "label")) || decisions.get(id) != time(first(row, "decision_time", "event_time"))) throw failure("label identity/time does not match feature for " + id); }
        Map<String, String> execution = new LinkedHashMap<>(); for (ObjectNode row : executions) { String id = text(row, "signal_id") + "|" + text(row, "episode_id"); if (execution.put(id, artifactRoleIdentity(row, "execution")) != null) throw failure("execution signal/episode identity is duplicated: " + id); if (!feature.containsKey(id) || !label.containsKey(id) || !feature.get(id).equals(artifactRoleIdentity(row, "execution")) || decisions.get(id) != time(first(row, "decision_time", "event_time", "entry_time"))) throw failure("execution identity/time does not match feature/label for " + id); }
        for (String id : feature.keySet()) if (!label.containsKey(id) || !execution.containsKey(id)) throw failure("eligible feature lacks complete label/execution path: " + id);
        Set<String> series = new HashSet<>(feature.values()), marked = new HashSet<>(); for (ObjectNode row : marks) { String instrument = text(row, "instrument").toUpperCase(Locale.ROOT).replaceFirst("_MARK$", ""); ObjectNode copy = row.deepCopy(); copy.put("instrument", instrument); String identity = artifactRoleIdentity(copy, "mark"); if (!series.contains(identity)) throw failure("mark series does not match an evaluated feature series: " + text(row, "series_id")); marked.add(identity); }
        for (String identity : series) if (!identity.split("\\|", -1)[2].equals("BINANCE_SPOT") && !marked.contains(identity)) throw failure("derivative feature series lacks a bound mark series: " + identity);
    }

    /* ------------------------------------------------------------------ */
    /* Dated futures, acquisition checkpoints, and promoted coverage       */
    /* ------------------------------------------------------------------ */

    public static ObjectNode discoverBinanceDatedFutures(ObjectNode options) {
        return discoverBinanceDatedFutures(options, new PublicDataAdapters.JdkInjectableHttpClient());
    }

    /* Package-private transport seam: production callers use the public
     * one-argument entry point above, while the quarantine oracle can replay
     * an exact response without opening the network.  Rows supplied directly
     * remain fixture-only; a transport-backed capture still goes through the
     * public adapter's response-time/PIT custody path. */
    static ObjectNode discoverBinanceDatedFutures(ObjectNode options, PublicDataAdapters.InjectableHttpClient transport) {
        if (options == null) throw failure("dated-futures discovery options are required");
        List<ObjectNode> rows; String capturedAt, responseSha, adapterId;
        if (options.path("exchangeInfoRows").isArray()) {
            if (!options.path("fixtureOnly").asBoolean(false)) throw failure("injected exchange-info rows are fixture-only");
            rows = objects(options.path("exchangeInfoRows")); capturedAt = textOr(options.get("capturedAt"), iso(System.currentTimeMillis()));
            responseSha = requireSha(text(options, "responseSha256"), "exchange-info response_sha256"); adapterId = textOr(options.get("adapterId"), "BINANCE_LINEAR_EXCHANGE_INFO");
        } else {
            boolean fixture = options.path("fixtureOnly").asBoolean(false);
            if (options.hasNonNull("capturedAt") && !fixture) throw failure("caller-supplied capturedAt is fixture-only for dated-futures discovery");
            PublicDataAdapters.HttpOptions http = new PublicDataAdapters.HttpOptions(
                    Objects.requireNonNull(transport, "transport"), fixture ? text(options, "capturedAt") : null,
                    fixture, 3, 250);
            PublicDataAdapters.Capture capture = PublicDataAdapters.fetchBinanceExchangeInfo(http);
            rows = capture.rows(); capturedAt = capture.capturedAt(); responseSha = capture.responseSha256(); adapterId = capture.adapterId();
        }
        ArrayNode contracts = array();
        for (ObjectNode row : rows) {
            String asset = text(row, "baseAsset").toLowerCase(Locale.ROOT), type = text(row, "contractType").toUpperCase(Locale.ROOT);
            if (!DATA_V5_ASSETS.contains(asset) || !Set.of("CURRENT_QUARTER", "NEXT_QUARTER").contains(type)
                    || !"USDT".equals(text(row, "quoteAsset").toUpperCase(Locale.ROOT))) continue;
            ObjectNode contract = object().put("asset", asset).put("symbol", text(row, "symbol"))
                    .put("contract_type", text(row, "contractType")).put("venue", "BINANCE")
                    .put("instrument", "BINANCE_USDM_DATED_FUTURE").put("source", adapterId)
                    .put("source_sha256", responseSha).put("availability_time", capturedAt);
            if (row.path("onboardDate").asLong(0) != 0) contract.put("onboard_at", iso(row.path("onboardDate").asLong())); else contract.putNull("onboard_at");
            if (row.path("deliveryDate").asLong(0) != 0) contract.put("expiry", iso(row.path("deliveryDate").asLong())); else contract.putNull("expiry");
            contracts.add(contract);
        }
        ArrayNode limitations = array().add("CURRENT_CATALOG_ONLY_HISTORICAL_EXPIRED_DATED_FUTURES_NOT_BOUND");
        if (contracts.isEmpty()) limitations.add("CURRENT_BINANCE_USDM_DATED_FUTURES_CATALOG_EMPTY");
        ObjectNode value = object().put("schema", "strategy-v5-dated-futures-catalog/1").put("version", 1)
                .put("captured_at", capturedAt).put("source_sha256", responseSha).put("status", "PUBLIC_OBSERVED");
        value.set("contracts", contracts); value.set("limitations", limitations); value.put("content_sha256", hash(value)); return value;
    }

    public static ObjectNode discoverBinanceHistoricalDatedFutures(ObjectNode options) {
        return discoverBinanceHistoricalDatedFutures(options, new PublicDataAdapters.JdkInjectableHttpClient());
    }

    /* The Node implementation discovers listing pages and then performs a
     * bounded first/last history probe.  Keep the same sequence here.  The
     * package-private client seam is intentionally not part of the public
     * export surface; it lets the quarantine oracle exercise nonfixture
     * response-time semantics and hostile pagination without network access. */
    static ObjectNode discoverBinanceHistoricalDatedFutures(ObjectNode options,
            PublicDataAdapters.InjectableHttpClient transport) {
        if (options == null) throw failure("historical dated-futures discovery options are required");
        long start = time(options.get("startAt")), end = time(options.get("endAt"));
        if (start >= end) throw failure("historical dated-futures catalog bounds are invalid");
        boolean fixture = options.path("fixtureOnly").asBoolean(false);
        if (options.hasNonNull("capturedAt") && !fixture) throw failure("caller-supplied capturedAt is fixture-only for historical dated-futures discovery");
        List<String> requested = options.path("assets").isArray()
                ? texts(options.path("assets")).stream().map(value -> value.toLowerCase(Locale.ROOT)).distinct().sorted().toList()
                : List.of("btc", "eth");
        Path rawRoot = options.hasNonNull("rawOutputRoot") ? requiredPath(options, "rawOutputRoot") : null;
        String suppliedCapture = options.hasNonNull("capturedAt") ? text(options, "capturedAt") : null;
        String defaultCapture = suppliedCapture == null ? iso(System.currentTimeMillis()) : suppliedCapture;
        List<ObjectNode> listingRows = new ArrayList<>();
        List<byte[]> listingBodies = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        JsonNode retained = options.get("listingResponses");
        if (retained != null && retained.isArray() && !retained.isEmpty()) {
            for (ObjectNode listing : objects(retained)) {
                String endpoint = text(listing, "endpoint");
                byte[] body;
                try {
                    body = listing.hasNonNull("body_base64")
                            ? Base64.getDecoder().decode(text(listing, "body_base64"))
                            : text(listing, "body").getBytes(StandardCharsets.UTF_8);
                } catch (IllegalArgumentException error) { throw failure("Binance Data Vision catalog response body encoding is invalid: " + error.getMessage()); }
                String captured = textOr(listing.get("captured_at"), defaultCapture);
                ObjectNode response = datedListingResponse(endpoint, body, captured, rawRoot, options, prefixes);
                listingRows.add(response); listingBodies.add(body);
            }
        } else {
            Objects.requireNonNull(transport, "transport");
            for (String asset : requested) {
                if (!Set.of("btc", "eth").contains(asset)) continue;
                String continuation = null; int page = 0;
                do {
                    String endpoint = datedListingEndpoint(asset, continuation);
                    PublicDataAdapters.FetchResponse response;
                    try { response = transport.fetch(URI.create(endpoint), Map.of("accept", "application/xml")); }
                    catch (Exception error) { throw failure("Binance Data Vision catalog request failed: " + endpoint + ": " + error.getMessage()); }
                    if (response == null) throw failure("Binance Data Vision catalog transport returned no response");
                    if (response.status() < 200 || response.status() >= 300) throw failure("Binance Data Vision catalog HTTP " + response.status() + ": " + endpoint);
                    byte[] body = response.body();
                    String captured = fixture && suppliedCapture != null ? suppliedCapture : observedResponseTime(response);
                    ObjectNode listing = datedListingResponse(endpoint, body, captured, rawRoot, options, prefixes);
                    listingRows.add(listing); listingBodies.add(body);
                    String xml = new String(body, StandardCharsets.UTF_8);
                    boolean truncated = xml.matches("(?s).*<IsTruncated>\\s*true\\s*</IsTruncated>.*");
                    String next = firstXmlValue(xml, "NextContinuationToken");
                    if (truncated && (next == null || next.isBlank())) throw failure("Binance Data Vision catalog pagination token is missing for " + asset);
                    continuation = truncated ? next : null; page++;
                    if (continuation != null && page >= 100) throw failure("Binance Data Vision catalog exceeded pagination bound for " + asset);
                } while (continuation != null);
            }
        }
        if (listingRows.isEmpty()) throw failure("historical dated-futures catalog has no supported asset listing responses");
        ArrayNode responses = array(); listingRows.forEach(responses::add);
        ArrayNode listingMetadata = array();
        for (ObjectNode response : listingRows) listingMetadata.add(object().put("endpoint", text(response, "endpoint"))
                .put("raw_byte_sha256", text(response, "raw_byte_sha256")).put("bytes", response.path("bytes").asLong()));
        sortArray(listingMetadata, Comparator.comparing((JsonNode node) -> text(node, "endpoint")).thenComparing(node -> text(node, "raw_byte_sha256")));
        String setSha = hash(listingMetadata);
        ArrayNode contracts = array(); Set<String> seen = new HashSet<>(); List<String> probeLimitations = new ArrayList<>();
        Pattern contractPattern = Pattern.compile("monthly/klines/([A-Z]+USDT_(\\d{6}))/");
        for (String prefix : prefixes.stream().distinct().sorted().toList()) {
            Matcher matcher = contractPattern.matcher(prefix); if (!matcher.find()) continue;
            String symbol = matcher.group(1), asset = symbol.replaceFirst("USDT_\\d{6}$", "").toLowerCase(Locale.ROOT);
            if (!requested.contains(asset) || !seen.add(symbol)) continue;
            long expiry = quarterlyExpiry(symbol); if (expiry <= start || expiry > end) continue;
            List<ObjectNode> refs = listingRows.stream().filter(row -> text(row, "endpoint").toUpperCase(Locale.ROOT).contains(asset.toUpperCase(Locale.ROOT) + "USDT_"))
                    .toList();
            ObjectNode contract = object().put("asset", asset).put("symbol", symbol)
                    .put("contract_type", "QUARTERLY_EXPIRED_OR_HISTORICAL").put("venue", "BINANCE")
                    .put("instrument", "BINANCE_USDM_DATED_FUTURE").putNull("first_bar_at").putNull("last_bar_at")
                    .put("expiry_observed_date_utc", iso(expiry).substring(0, 10)).putNull("expiry_at")
                    .put("expiry_binding_status", "UNAVAILABLE").put("contract_spec_status", "UNAVAILABLE")
                    .put("history_status", "UNAVAILABLE").put("tradeable", false).put("source_prefix", prefix)
                    .put("archive_ingestion_status", refs.isEmpty() ? "NOT_APPLICABLE" : "ARCHIVE_DISCOVERED_NOT_INGESTED");
            contract.set("source_listing_response_byte_sha256", strings(refs.stream().map(row -> text(row, "raw_byte_sha256")).sorted().toList()));
            contract.set("source_receipt_sha256", strings(refs.stream().map(row -> text(row, "raw_receipt_sha256")).filter(value -> !value.isEmpty()).sorted().toList()));
            if (refs.isEmpty()) contract.putNull("source_raw_byte_sha256"); else contract.put("source_raw_byte_sha256", text(refs.get(0), "raw_byte_sha256"));
            String probeCaptureAt = fixture && suppliedCapture != null ? suppliedCapture : null;
            try {
                PublicDataAdapters.HttpOptions http = new PublicDataAdapters.HttpOptions(transport, probeCaptureAt, fixture, 3, 250);
                PublicDataAdapters.Capture first = PublicDataAdapters.fetchBinanceOhlc(new PublicDataAdapters.OhlcOptions(
                        asset, symbol, start, Math.min(end, expiry), "4h", 1, true, http));
                if (!first.rows().isEmpty()) contract.put("first_bar_at", iso(rowTime(first.rows().get(0))));
                responses.add(historyProbeResponse(first, symbol, "FIRST"));
                long probeEnd = Math.min(end, expiry), probeStart = Math.max(start, probeEnd - 48L * FOUR_HOURS);
                PublicDataAdapters.Capture last = PublicDataAdapters.fetchBinanceOhlc(new PublicDataAdapters.OhlcOptions(
                        asset, symbol, probeStart, probeEnd, "4h", 1000, true, http));
                if (!last.rows().isEmpty()) contract.put("last_bar_at", iso(rowTime(last.rows().get(last.rows().size() - 1))));
                responses.add(historyProbeResponse(last, symbol, "LAST"));
                if (contract.hasNonNull("first_bar_at") && contract.hasNonNull("last_bar_at")
                        && time(contract.get("first_bar_at")) <= time(contract.get("last_bar_at"))) contract.put("history_status", "SIGNAL_HISTORY_AVAILABLE");
            } catch (RuntimeException error) {
                // A probe is diagnostic only; a malformed/missing history page
                // must never make the symbol tradeable or silently disappear.
                contract.put("history_status", "UNAVAILABLE");
                probeLimitations.add(symbol + ":HISTORY_PROBE_FAILED:" + (error.getMessage() == null ? "PROBE_FAILED" : error.getMessage()));
            }
            contracts.add(contract);
        }
        ArrayNode limitations = array();
        limitations.addAll(strings(probeLimitations));
        for (String asset : requested) if (objects(contracts).stream().noneMatch(row -> asset.equals(text(row, "asset")) && "SIGNAL_HISTORY_AVAILABLE".equals(text(row, "history_status")))) limitations.add(asset + ":HISTORICAL_DATED_FUTURES_UNAVAILABLE_OR_NOT_LISTED");
        if (requested.stream().anyMatch(asset -> !DATA_V5_ASSETS.contains(asset))) limitations.add("REQUESTED_ASSET_OUTSIDE_V5_UNIVERSE_IGNORED");
        if (rawRoot == null) limitations.add("DATED_FUTURES_LISTING_BYTES_HASH_ONLY_UNVERIFIABLE");
        for (ObjectNode contract : objects(contracts)) if ("ARCHIVE_DISCOVERED_NOT_INGESTED".equals(text(contract, "archive_ingestion_status"))) limitations.add(text(contract, "asset") + ":ARCHIVE_DISCOVERED_NOT_INGESTED");
        List<String> observedTimes = new ArrayList<>(listingRows.stream().map(row -> text(row, "captured_at")).toList());
        for (JsonNode response : responses) if ("HISTORY_PROBE".equals(text(response, "kind"))) observedTimes.add(text(response, "captured_at"));
        String catalogCapture = fixture && suppliedCapture != null ? iso(time(com.fasterxml.jackson.databind.node.TextNode.valueOf(suppliedCapture))) : latestTimestamp(observedTimes.toArray(String[]::new));
        ObjectNode source = object().put("endpoint", "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision")
                .put("listing_response_set_sha256", setSha).put("listing_format", "S3_XML_DELIMITER")
                .put("persistence_status", rawRoot == null ? "HASH_ONLY_UNVERIFIABLE" : "RAW_RECEIPTS_BOUND");
        if (rawRoot == null) source.putNull("raw_output_root_reference"); else source.put("raw_output_root_reference", portableReference(rawRoot, text(options, "rawOutputRootReference")));
        ArrayNode rawReceipts = array(); for (ObjectNode listing : listingRows) if (listing.hasNonNull("raw_receipt_path")) {
            ObjectNode receipt = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1)
                    .put("path", text(listing, "raw_receipt_path")).put("content_sha256", text(listing, "raw_receipt_sha256"))
                    .put("source", "BINANCE_DATA_VISION_S3").put("byte_sha256", text(listing, "raw_byte_sha256"))
                    .put("bytes", listing.path("bytes").asLong()).put("format", "RAW_BYTES")
                    .put("storage_role", "RAW_IGNORED").put("authoritative", false);
            receipt.set("request", object().put("endpoint", text(listing, "endpoint")).put("listing_format", "S3_XML_DELIMITER"));
            receipt.remove("content_sha256"); receipt.put("content_sha256", StrategyResearchDataV5.ownHash(receipt));
            rawReceipts.add(receipt);
        }
        source.set("raw_receipts", rawReceipts); source.set("raw_receipt_sha256", strings(objects(rawReceipts).stream().map(row -> text(row, "content_sha256")).sorted().toList()));
        source.set("raw_receipt_byte_sha256", strings(objects(rawReceipts).stream().map(row -> text(row, "byte_sha256")).sorted().toList()));
        ObjectNode value = object().put("schema", DATA_V5.get("datedCatalog")).put("version", 2).put("captured_at", catalogCapture)
                .put("status", objects(contracts).stream().anyMatch(row -> "SIGNAL_HISTORY_AVAILABLE".equals(text(row, "history_status"))) ? "PUBLIC_OBSERVED_PARTIAL" : "PUBLIC_OBSERVED_UNAVAILABLE");
        value.set("source", source); value.set("requested_assets", strings(requested)); value.set("contracts", contracts); value.set("responses", responses); value.set("limitations", strings(uniqueSortedTexts(limitations)));
        ObjectNode result = withHash(value); ObjectNode validate = object().set("catalog", result); if (rawRoot != null) validate.put("root", rawRoot.toString()); validateDatedFuturesCatalog(validate); return result;
    }

    private static String datedListingEndpoint(String asset, String continuation) {
        String endpoint = "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision?delimiter=%2F&prefix=data%2Ffutures%2Fum%2Fmonthly%2Fklines%2F" + asset.toUpperCase(Locale.ROOT) + "USDT_";
        if (continuation == null || continuation.isBlank()) return endpoint;
        return endpoint + "&continuation-token=" + java.net.URLEncoder.encode(continuation, StandardCharsets.UTF_8);
    }

    private static String observedResponseTime(PublicDataAdapters.FetchResponse response) {
        String date = response.firstHeader("date"); Long parsed = parseTimestamp(date); return parsed == null ? iso(System.currentTimeMillis()) : iso(parsed);
    }

    private static Long parseTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value).toEpochMilli(); }
        catch (DateTimeParseException ignored) {
            try { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); }
            catch (DateTimeParseException ignoredAgain) { return null; }
        }
    }

    private static String firstXmlValue(String xml, String element) {
        Matcher matcher = Pattern.compile("<" + Pattern.quote(element) + ">([^<]+)</" + Pattern.quote(element) + ">").matcher(xml); return matcher.find() ? matcher.group(1) : null;
    }

    private static ObjectNode datedListingResponse(String endpoint, byte[] body, String capturedAt, Path rawRoot, ObjectNode options, List<String> prefixes) {
        String xml = new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
        if (!xml.matches("(?s).*<ListBucketResult(?:\\s|>).*")) throw failure("Binance Data Vision catalog response is not XML");
        if (xml.matches("(?s).*<IsTruncated>\\s*true\\s*</IsTruncated>.*") && (firstXmlValue(xml, "NextContinuationToken") == null || firstXmlValue(xml, "NextContinuationToken").isBlank())) throw failure("Binance Data Vision catalog pagination token is missing");
        Matcher matcher = Pattern.compile("<Prefix>([^<]+)</Prefix>").matcher(xml); while (matcher.find()) prefixes.add(matcher.group(1));
        String byteSha = hash(body); ObjectNode response = object().put("endpoint", endpoint).put("kind", "LISTING").put("raw_byte_sha256", byteSha).put("bytes", body.length).put("captured_at", capturedAt);
        if (rawRoot != null) {
            String path = "raw/" + byteSha + ".bin"; writeContentAddressed(rawRoot, path, body, "dated catalog raw response");
            ObjectNode receiptValue = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1).put("path", path).put("source", "BINANCE_DATA_VISION_S3");
            receiptValue.set("request", object().put("endpoint", endpoint).put("listing_format", "S3_XML_DELIMITER")); receiptValue.put("byte_sha256", byteSha).put("bytes", body.length).put("format", "RAW_BYTES").put("storage_role", "RAW_IGNORED").put("authoritative", false);
            ObjectNode receipt = withHash(receiptValue); response.put("raw_receipt_path", path).put("raw_receipt_sha256", text(receipt, "content_sha256"));
        } else response.putNull("raw_receipt_path").putNull("raw_receipt_sha256");
        return response;
    }

    private static ObjectNode historyProbeResponse(PublicDataAdapters.Capture capture, String symbol, String side) {
        return object().put("endpoint", text(capture.request(), "endpoint")).put("kind", "HISTORY_PROBE").put("symbol", symbol).put("side", side)
                .put("response_sha256", capture.responseSha256()).put("captured_at", capture.capturedAt());
    }

    public static boolean validateDatedFuturesCatalog(ObjectNode options) {
        ObjectNode catalog = options.has("catalog") ? requiredObject(options, "catalog") : options; assertOwnHash(catalog, DATA_V5.get("datedCatalog"), "dated-futures catalog");
        ObjectNode source = (ObjectNode) catalog.path("source"); List<ObjectNode> listings = objects(catalog.path("responses")).stream().filter(row -> "LISTING".equals(text(row, "kind"))).toList();
        ArrayNode metadata = array(); for (ObjectNode row : listings) metadata.add(object().put("endpoint", text(row, "endpoint")).put("raw_byte_sha256", text(row, "raw_byte_sha256")).put("bytes", row.path("bytes").asLong()));
        sortArray(metadata, Comparator.comparing((JsonNode node) -> text(node, "endpoint")).thenComparing(node -> text(node, "raw_byte_sha256")));
        if (!hash(metadata).equals(text(source, "listing_response_set_sha256"))) throw failure("dated catalog listing response set hash is invalid");
        String persistence = text(source, "persistence_status");
        if ("HASH_ONLY_UNVERIFIABLE".equals(persistence)) {
            if (!objects(source.path("raw_receipts")).isEmpty() || !uniqueSortedTextsOrEmpty(source.get("raw_receipt_sha256")).isEmpty()) throw failure("hash-only dated catalog cannot claim raw receipts");
            if (!texts(catalog.path("limitations")).contains("DATED_FUTURES_LISTING_BYTES_HASH_ONLY_UNVERIFIABLE")) throw failure("hash-only dated catalog must disclose unverifiable listing bytes");
        } else if ("RAW_RECEIPTS_BOUND".equals(persistence)) {
            Path root = requiredPath(options, "root"); List<ObjectNode> receipts = objects(source.path("raw_receipts")); if (receipts.isEmpty()) throw failure("dated-futures catalog raw receipts are incomplete");
            for (ObjectNode receipt : receipts) { assertOwnHash(receipt, "strategy-v5-source-receipt/1", "dated catalog source receipt"); byte[] bytes = readPhysical(root, text(receipt, "path"), "dated catalog raw receipt"); if (!hash(bytes).equals(text(receipt, "byte_sha256"))) throw failure("dated catalog raw receipt bytes are missing or tampered: " + text(receipt, "path")); }
        } else throw failure("dated-futures catalog persistence status is invalid");
        Set<String> responseBytes = listings.stream().map(row -> text(row, "raw_byte_sha256")).collect(java.util.stream.Collectors.toSet());
        for (ObjectNode contract : objects(catalog.path("contracts"))) {
            List<String> bytes = texts(contract.path("source_listing_response_byte_sha256")); if (bytes.isEmpty() || bytes.stream().anyMatch(value -> !responseBytes.contains(value))) throw failure("dated contract " + text(contract, "symbol") + " is not bound to a listing response byte hash");
            if (contract.path("tradeable").asBoolean(false) && !(contract.hasNonNull("expiry_at") && "BOUND".equals(text(contract, "expiry_binding_status"))
                    && "ARCHIVE_INGESTED".equals(text(contract, "archive_ingestion_status")) && contract.path("archive_coverage_complete").asBoolean(false))) throw failure("dated contract " + text(contract, "symbol") + " is tradeable without exact expiry/spec/margin/liquidation/settlement/archive binding");
            if ("ARCHIVE_INGESTED".equals(text(contract, "archive_ingestion_status"))) {
                Path root = requiredPath(options, "root"); Set<String> kinds = new HashSet<>();
                for (ObjectNode reference : objects(contract.path("archive_raw_references"))) { kinds.add(text(reference, "kind")); byte[] physical = readPhysical(root, text(reference, "path"), "dated archive raw reference"); if (physical.length != reference.path("bytes").asLong(-1) || !hash(physical).equals(text(reference, "sha256"))) throw failure("dated contract archive bytes are missing or tampered: " + text(reference, "path")); }
                if (!kinds.containsAll(Set.of("ARCHIVE_ZIP", "ARCHIVE_CHECKSUM"))) throw failure("dated contract archive custody lacks ZIP and CHECKSUM bytes");
            }
        }
        return true;
    }

    public static ObjectNode recordDatedArchiveIngestion(ObjectNode options) {
        ObjectNode catalog = requiredObject(options, "catalog"), archive = requiredObject(options, "archiveResult"); Path root = requiredPath(options, "root");
        ObjectNode validate = object().set("catalog", catalog); validate.put("root", root.toString()); validateDatedFuturesCatalog(validate);
        if (!archive.path("coverage").path("complete").asBoolean(false)) throw failure("dated archive promotion requires complete coverage and a physical raw root");
        List<ObjectNode> references = new ArrayList<>();
        for (ObjectNode raw : objects(archive.path("raw_responses"))) { String kind = text(raw.path("request"), "kind"); if (kind.isEmpty()) continue; byte[] bytes = readPhysical(root, text(raw, "path"), "dated archive promotion reference"); if (bytes.length != raw.path("bytes").asLong(-1) || !hash(bytes).equals(text(raw, "sha256"))) throw failure("dated archive promotion bytes are missing or tampered: " + text(raw, "path")); references.add(object().put("kind", kind).put("path", text(raw, "path")).put("sha256", text(raw, "sha256")).put("bytes", raw.path("bytes").asLong())); }
        Set<String> kinds = references.stream().map(row -> text(row, "kind")).collect(java.util.stream.Collectors.toSet()); if (!kinds.containsAll(Set.of("ARCHIVE_ZIP", "ARCHIVE_CHECKSUM"))) throw failure("dated archive promotion requires retained ZIP and CHECKSUM references");
        String asset = requireAsset(text(options, "asset")), symbol = text(options, "symbol").toUpperCase(Locale.ROOT); boolean found = false; ArrayNode contracts = array();
        for (ObjectNode original : objects(catalog.path("contracts"))) { ObjectNode row = original.deepCopy(); if (asset.equals(text(row, "asset")) && symbol.equals(text(row, "symbol").toUpperCase(Locale.ROOT))) { found = true; row.put("history_status", "SIGNAL_HISTORY_AVAILABLE").put("archive_ingestion_status", "ARCHIVE_INGESTED").put("archive_coverage_complete", true).set("archive_raw_references", array(references)); List<ObjectNode> data = objects(archive.path("rows")); if (!data.isEmpty()) { row.put("first_bar_at", iso(rowTime(data.get(0)))); row.put("last_bar_at", iso(rowTime(data.get(data.size() - 1)))); } } contracts.add(row); }
        if (!found) throw failure("dated archive promotion target is not in catalog: " + asset + "/" + symbol);
        ObjectNode next = catalog.deepCopy(); next.remove("content_sha256"); next.set("contracts", contracts); next.set("limitations", strings(texts(catalog.path("limitations")).stream().filter(value -> !value.equals(asset + ":ARCHIVE_DISCOVERED_NOT_INGESTED")).distinct().sorted().toList()));
        ObjectNode result = withHash(next); ObjectNode recheck = object().set("catalog", result); recheck.put("root", root.toString()); validateDatedFuturesCatalog(recheck); return result;
    }

    private static boolean requirementMatchesSeries(ObjectNode declaration, ObjectNode series) {
        if (!text(declaration, "interval").equals(text(series, "interval"))
                || !texts(declaration.path("series_types")).contains(text(series, "series_type"))) return false;
        if ("BINANCE_USDM_DATED_FUTURE".equals(text(series, "instrument").toUpperCase(Locale.ROOT))
                && !series.path("tradeable").asBoolean(false)) return false;
        if (declaration.path("context_only").asBoolean(false)) return true;
        return !"CONTEXT_ONLY".equals(text(series, "trade_scope"))
                && (!series.has("tradeable") || series.path("tradeable").asBoolean());
    }

    private static String metricPitVintage(ObjectNode capture, ObjectNode coverage) {
        for (String name : List.of("metrics_pit_vintage_status", "pit_vintage_status",
                "source_pit_vintage_status")) {
            JsonNode value = "metrics_pit_vintage_status".equals(name)
                    && defined(capture.get(name)) ? capture.get(name) : coverage.get(name);
            if (defined(value) && !textValue(value).isEmpty()) return textValue(value);
        }
        return "HISTORICAL_PIT_VINTAGE".equals(text(coverage, "source"))
                ? "HISTORICAL_PIT_VINTAGE" : null;
    }

    private static boolean metricCoverageBelowFrozenMinimum(ObjectNode series, ObjectNode coverage) {
        List<String> fields = uniqueSortedTextsOrEmpty(coverage.has("required_metric_fields")
                ? coverage.get("required_metric_fields") : series.get("metric_required_fields"));
        double minimum = defined(coverage.get("minimum_field_coverage"))
                ? number(coverage.get("minimum_field_coverage"))
                : defined(series.get("metric_minimum_field_coverage"))
                        ? number(series.get("metric_minimum_field_coverage")) : 0.95;
        if (!Double.isFinite(minimum) || minimum < 0 || minimum > 1) return true;
        Map<String, Double> observed = new HashMap<>();
        if (coverage.path("required_field_coverage").isArray()) {
            for (ObjectNode row : objects(coverage.path("required_field_coverage"))) {
                observed.put(text(row, "field"), coverageNumber(row.get("fraction"), 0));
            }
        } else {
            for (String field : fields) {
                observed.put(field, coverageNumber(coverage.path("field_coverage").path(field).get("fraction"), 0));
            }
        }
        return fields.stream().anyMatch(field -> observed.getOrDefault(field, 0d) < minimum);
    }

    private static double coverageNumber(JsonNode value, double fallback) {
        return defined(value) ? number(value) : fallback;
    }

    private static boolean promotedCaptureCoverageComplete(ObjectNode series, ObjectNode capture) {
        if (capture == null || capture.path("unavailable").asBoolean(false)
                || !capture.path("coverage").path("complete").asBoolean(false)) return false;
        ObjectNode coverage = (ObjectNode) capture.path("coverage");
        if ("metrics_events".equals(text(series, "series_type"))) {
            if (!"HISTORICAL_PIT_VINTAGE".equals(metricPitVintage(capture, coverage))
                    || metricCoverageBelowFrozenMinimum(series, coverage)) return false;
        }
        if ("event".equals(text(series, "interval"))
                || "funding_events".equals(text(series, "series_type"))) {
            return coverage.path("boundaries_covered").asBoolean(false)
                    && coverage.path("source_pagination_complete").asBoolean(false);
        }
        JsonNode expectedNode = series.get("expected_event_count");
        if (expectedNode == null || !expectedNode.isIntegralNumber() || expectedNode.asLong() < 1) return false;
        long expected = expectedNode.asLong();
        if (coverageNumber(coverage.get("expected_rows"), Double.NaN) != expected
                || coverageNumber(coverage.get("observed_rows"), Double.NaN) != expected) return false;
        JsonNode first = defined(coverage.get("min_event_time"))
                ? coverage.get("min_event_time") : coverage.get("first_event_time");
        JsonNode last = defined(coverage.get("max_event_time"))
                ? coverage.get("max_event_time") : coverage.get("last_event_time");
        if (!defined(first) || !defined(last) || time(first) != time(series.get("start_at"))
                || time(last) != time(series.get("end_at"))) return false;
        if (defined(coverage.get("expected_first_event_time"))
                && time(coverage.get("expected_first_event_time")) != time(series.get("start_at"))) return false;
        return !defined(coverage.get("expected_last_event_time"))
                || time(coverage.get("expected_last_event_time")) == time(series.get("end_at"));
    }

    public static ObjectNode resolvePromotedCoverage(ObjectNode options) {
        ObjectNode plan = requiredObject(options, "plan"), acquisition = requiredObject(options, "acquisition");
        validatePlan(plan); assertOwnHash(plan, DATA_V5.get("plan"), "authoritative data plan");
        assertOwnHash(acquisition, DATA_V5.get("acquisition"), "acquisition manifest");
        boolean requireParquet = !options.has("requireParquet") || options.path("requireParquet").asBoolean();
        ObjectNode requirements = options.path("timeframeRequirements").isObject()
                ? (ObjectNode) options.path("timeframeRequirements") : null;
        boolean requireFrozen = !options.has("requireFrozenRequirements")
                || options.path("requireFrozenRequirements").asBoolean();
        if (requireFrozen && requirements == null)
            throw failure("strategy coverage resolution requires a frozen timeframe requirement artifact");
        if (requirements != null) {
            assertOwnHash(requirements, "strategy-v5-timeframe-requirements/1", "timeframe requirements");
            if (plan.hasNonNull("timeframe_requirements_sha256")
                    && !text(plan, "timeframe_requirements_sha256").equals(text(requirements, "content_sha256")))
                throw failure("timeframe requirements hash does not match the frozen plan");
        } else if (plan.hasNonNull("timeframe_requirements_sha256")) {
            throw failure("frozen plan requires its bound timeframe requirement artifact");
        }

        ObjectNode parquet = options.path("parquet").isObject() ? (ObjectNode) options.path("parquet") : null;
        if (parquet != null) assertOwnHash(parquet, text(parquet, "schema"), "Parquet manifest");
        if (options.hasNonNull("root")) {
            ObjectNode stagingCheck = object().set("manifest", acquisition);
            stagingCheck.put("root", text(options, "root")).set("plan", plan);
            stagingCheck.put("planSha256", text(plan, "content_sha256"))
                    .put("allowFixture", acquisition.path("fixture_only").asBoolean(false));
            verifyAuthoritativeStaging(stagingCheck);
            if (parquet != null) {
                ObjectNode parquetCheck = object().set("manifest", parquet);
                parquetCheck.put("root", text(options, "root"))
                        .put("planSha256", text(plan, "content_sha256"));
                verifyParquetConversionManifest(parquetCheck);
            }
        }

        Map<String, ObjectNode> acquired = objects(acquisition.path("captures")).stream()
                .collect(java.util.stream.Collectors.toMap(StrategyResearchDataV5::seriesKey,
                        Function.identity(), (a, b) -> { throw failure("duplicate acquisition series identity"); }));
        Map<String, ObjectNode> promoted = parquet == null ? Map.of()
                : objects(parquet.path("captures")).stream().collect(java.util.stream.Collectors.toMap(
                        StrategyResearchDataV5::seriesKey, Function.identity(),
                        (a, b) -> { throw failure("duplicate Parquet series identity"); }));
        List<ObjectNode> planSeries = objects(plan.path("series"));
        Set<String> requiredIdentities = planSeries.stream()
                .filter(series -> series.path("required").asBoolean(true))
                .map(StrategyResearchDataV5::seriesKey).collect(java.util.stream.Collectors.toSet());
        if (requirements != null) {
            for (ObjectNode declaration : objects(requirements.path("declarations"))) {
                List<ObjectNode> matches = planSeries.stream()
                        .filter(series -> requirementMatchesSeries(declaration, series)).toList();
                if (matches.isEmpty()) throw failure("frozen timeframe requirement has no matching plan series: "
                        + text(declaration, "predictor_id") + "/" + text(declaration, "interval"));
                matches.stream().map(StrategyResearchDataV5::seriesKey).forEach(requiredIdentities::add);
            }
        }

        ArrayNode rows = array();
        for (ObjectNode series : planSeries.stream()
                .sorted(Comparator.comparing(series -> requiredIdentities.contains(seriesKey(series)) ? "0|" + seriesKey(series) : "1|" + seriesKey(series)))
                .toList()) {
            String key = seriesKey(series); ObjectNode capture = acquired.get(key), promotedCapture = promoted.get(key);
            boolean required = requiredIdentities.contains(key);
            ObjectNode coverage = capture != null && capture.path("coverage").isObject()
                    ? (ObjectNode) capture.path("coverage") : object();
            boolean acquisitionComplete = capture != null && promotedCaptureCoverageComplete(series, capture)
                    && "STAGING".equals(text(capture.path("partition"), "storage_role"));
            boolean parquetComplete = promotedCapture != null
                    && "AUTHORITATIVE".equals(text(promotedCapture.path("partition"), "storage_role"))
                    && "PARQUET".equals(text(promotedCapture.path("partition"), "format"));
            boolean complete = acquisitionComplete && (!requireParquet || parquetComplete);
            boolean metric = "metrics_events".equals(text(series, "series_type"));
            boolean metricPitBlocked = metric && coverage.path("complete").asBoolean(false)
                    && !"HISTORICAL_PIT_VINTAGE".equals(metricPitVintage(capture, coverage));
            boolean metricCoverageBelow = metric && coverage.path("complete").asBoolean(false)
                    && metricCoverageBelowFrozenMinimum(series, coverage);
            TreeSet<String> gaps = new TreeSet<>();
            if (capture != null) gaps.addAll(uniqueTextsOrEmpty(capture.get("limitations")));
            for (String field : List.of("missing_slots", "missing_days", "missing_months", "gap_starts"))
                gaps.addAll(uniqueTextsOrEmpty(coverage.get(field)));
            if (coverage.hasNonNull("reason")) gaps.add(text(coverage, "reason"));
            if (metricPitBlocked) gaps.add(METRICS_PIT_VINTAGE_BLOCK_REASON);
            if (metricCoverageBelow) gaps.add("METRICS_FIELD_COVERAGE_BELOW_FROZEN_MINIMUM");
            if (capture == null) gaps.add("NOT_ACQUIRED");
            if (capture != null && capture.path("unavailable").asBoolean(false)) gaps.add("UNAVAILABLE");
            if (capture != null && !acquisitionComplete && coverage.path("complete").asBoolean(false))
                gaps.add("BOUNDARY_OR_EXPECTED_COUNT_NOT_VERIFIED");
            if (requireParquet && promotedCapture == null) gaps.add("PARQUET_NOT_PROMOTED");

            int observedRows = coverage.path("observed_rows").isIntegralNumber()
                    ? coverage.path("observed_rows").asInt()
                    : coverage.path("observed_events").isIntegralNumber()
                            ? coverage.path("observed_events").asInt()
                            : capture == null ? 0 : capture.path("partition").path("row_count").asInt(0);
            ObjectNode row = object().put("asset", text(series, "asset")).put("venue", text(series, "venue"))
                    .put("instrument", text(series, "instrument")).put("symbol", text(series, "symbol"))
                    .put("interval", text(series, "interval")).put("series_type", text(series, "series_type"));
            copyNullable(row, "series_role", series.get("series_role"));
            row.put("trade_scope", textOr(series.get("trade_scope"),
                    "signal_bars".equals(text(series, "series_type")) ? "TRADEABLE_CRYPTO" : "CONTEXT_ONLY"));
            row.put("identity", key).put("required", required).put("observed_rows", observedRows);
            copyNullable(row, "expected_rows", series.path("expected_event_count").isIntegralNumber()
                    ? series.get("expected_event_count") : null);
            copyNullable(row, "observed_min_event_time", first(coverage, "min_event_time", "first_event_time"));
            copyNullable(row, "observed_max_event_time", first(coverage, "max_event_time", "last_event_time"));
            copyNullable(row, "observed_min_availability_time", coverage.get("min_availability_time"));
            copyNullable(row, "observed_max_availability_time", coverage.get("max_availability_time"));
            row.set("gaps", strings(new ArrayList<>(gaps)));
            row.put("acquisition_complete", acquisitionComplete).put("parquet_complete", parquetComplete)
                    .put("complete", complete);
            copyNullable(row, "acquisition_partition_sha256",
                    capture == null ? null : capture.path("partition").get("sha256"));
            copyNullable(row, "parquet_partition_sha256",
                    promotedCapture == null ? null : promotedCapture.path("partition").get("sha256"));
            rows.add(row);
        }
        List<ObjectNode> requiredRows = objects(rows).stream().filter(row -> row.path("required").asBoolean()).toList();
        List<ObjectNode> optionalRows = objects(rows).stream().filter(row -> !row.path("required").asBoolean()).toList();
        boolean base = !requiredRows.isEmpty() && requiredRows.stream().allMatch(row -> row.path("complete").asBoolean());
        boolean full = !rows.isEmpty() && objects(rows).stream().allMatch(row -> row.path("complete").asBoolean());
        TreeSet<String> limitations = new TreeSet<>(uniqueTextsOrEmpty(plan.get("limitations")));
        limitations.addAll(uniqueTextsOrEmpty(acquisition.get("limitations")));
        for (ObjectNode row : objects(rows)) if (!row.path("complete").asBoolean())
            for (String gap : texts(row.path("gaps"))) limitations.add(text(row, "identity") + ":" + gap);
        ObjectNode value = object().put("schema", DATA_V5.get("promotedCoverage")).put("version", 1)
                .put("status", base ? "READY" : "BLOCKED").put("plan_sha256", text(plan, "content_sha256"))
                .put("acquisition_sha256", text(acquisition, "content_sha256"))
                .put("base_complete", base).put("declared_requirements_complete", base)
                .put("full_plan_complete", full).put("require_parquet", requireParquet)
                .put("required_series_count", requiredRows.size())
                .put("required_complete_count", requiredRows.stream().filter(row -> row.path("complete").asBoolean()).count())
                .put("optional_series_count", optionalRows.size())
                .put("optional_complete_count", optionalRows.stream().filter(row -> row.path("complete").asBoolean()).count());
        copyNullable(value, "requirements_sha256", requirements == null ? null : requirements.get("content_sha256"));
        copyNullable(value, "parquet_sha256", parquet == null ? null : parquet.get("content_sha256"));
        value.set("optional_unavailable", strings(optionalRows.stream().filter(row -> !row.path("complete").asBoolean())
                .map(row -> text(row, "identity")).sorted().toList()));
        value.set("series", rows); value.set("limitations", strings(new ArrayList<>(limitations)));
        return withHash(value);
    }

    public static ObjectNode rebaseAcquisitionCheckpoint(ObjectNode options) {
        ObjectNode manifest = requiredObject(options, "manifest"); assertOwnHash(manifest, DATA_V5.get("acquisition"), "acquisition rebase manifest");
        if (options.hasNonNull("expectedPlanSha256") && !text(options, "expectedPlanSha256").equals(text(manifest, "plan_sha256"))) throw failure("acquisition rebase manifest is bound to a different frozen plan");
        Path source = requiredPath(options, "sourceRoot"), target = requiredPath(options, "targetRoot"); if (source.equals(target)) throw failure("acquisition rebase requires distinct source and target roots");
        try { Files.createDirectories(target); } catch (IOException error) { throw failure("acquisition rebase target cannot be created: " + error.getMessage()); }
        ObjectNode verify = object().set("manifest", manifest); verify.put("root", source.toString()).put("planSha256", text(manifest, "plan_sha256")).put("allowFixture", manifest.path("fixture_only").asBoolean(false)); verifyAuthoritativeStaging(verify);
        ObjectNode completed = object(), lineage = object(); Set<String> copied = new HashSet<>();
        for (ObjectNode capture : objects(manifest.path("captures"))) {
            if (capture.path("unavailable").asBoolean(false) || !capture.path("partition").isObject()) continue; String identity = seriesKey(capture); lineage.set(identity, inspectCaptureLineage(capture, source));
            List<String> paths = new ArrayList<>(); paths.add(text(capture.path("partition"), "path")); if (capture.path("mark_partition").isObject()) paths.add(text(capture.path("mark_partition"), "path"));
            for (ObjectNode summary : concatNodes(capture.path("source_receipts"), capture.path("mark_source_receipts"))) { paths.add(text(summary, "path")); ObjectNode receipt = verifyNormalizedReceipt(source, summary, "acquisition rebase normalized source receipt"); for (ObjectNode raw : objects(receipt.path("raw_receipts"))) paths.add(text(raw, "path")); }
            for (String path : paths) if (copied.add(path)) copyConfined(source, target, path, "acquisition rebase"); completed.set(identity, capture.deepCopy());
        }
        ObjectNode checkpoint = object().put("schema", DATA_V5.get("checkpoint")).put("version", 1).put("plan_sha256", text(manifest, "plan_sha256"))
                .put("root_reference", portableReference(target, text(options, "targetRootReference"))).putNull("prior_checkpoint_sha256")
                .put("producer_code_sha256", javaProducerCodeSha256()).put("coverage_rules_sha256", DATA_V5_COVERAGE_RULES_SHA256)
                .put("fixture_only", manifest.path("fixture_only").asBoolean(false)).put("provenance", textOr(manifest.get("provenance"), "REBASED_PHYSICAL_CUSTODY"));
        checkpoint.set("completed", completed); checkpoint.set("capture_lineage", lineage); ObjectNode result = withHash(checkpoint);
        String relative = textOr(options.get("checkpointPath"), "checkpoint.json"); writeCheckpointCas(target, relative, result, null); return result;
    }

    public static ObjectNode acquireAuthoritativeStaging(ObjectNode options) {
        if (options != null && options.path("fixtureCaptures").isArray()) return acquireFixtureCaptures(options);
        return acquireAuthoritativeStaging(options, new PublicDataAdapters.JdkInjectableHttpClient());
    }

    public static ObjectNode acquireAuthoritativeStaging(ObjectNode options, PublicDataAdapters.InjectableHttpClient transport) {
        if (options != null && options.path("fixtureCaptures").isArray()) return acquireFixtureCaptures(options);
        return acquireUsingPublicAdapters(options, Objects.requireNonNull(transport, "transport"));
    }

    private static ObjectNode acquireFixtureCaptures(ObjectNode options) {
        ObjectNode plan = requiredObject(options, "plan"); validatePlan(plan); Path root = requiredPath(options, "outputRoot"); boolean fixture = options.path("fixtureOnly").asBoolean(false);
        if (!fixture || !options.path("fixtureCaptures").isArray()) throw failure("native acquisition requires explicit fixture captures here; production acquisition must be supplied through the public adapter orchestration boundary");
        try { Files.createDirectories(root); } catch (IOException error) { throw failure("acquisition output root cannot be created: " + error.getMessage()); }
        String checkpointPath = textOr(options.get("checkpointPath"), "checkpoint.json"), expected = options.has("expectedCheckpointSha256") ? text(options, "expectedCheckpointSha256") : null;
        List<ObjectNode> captures = objects(options.path("fixtureCaptures")); Map<String, ObjectNode> byKey = captures.stream().collect(java.util.stream.Collectors.toMap(StrategyResearchDataV5::seriesKey, Function.identity()));
        ArrayNode ordered = array(); for (ObjectNode series : objects(plan.path("series"))) { ObjectNode capture = byKey.get(seriesKey(series)); if (capture == null) throw failure("fixture acquisition lacks frozen plan series " + seriesKey(series)); verifyCaptureCustody(capture, root, series, text(plan, "content_sha256")); ordered.add(capture); }
        ObjectNode value = object().put("schema", DATA_V5.get("acquisition")).put("version", 1).put("status", objects(ordered).stream().filter(row -> row.path("required").asBoolean(true)).allMatch(StrategyResearchDataV5::captureComplete) ? "STAGING_COMPLETE" : "STAGING_PARTIAL")
                .put("plan_sha256", text(plan, "content_sha256")).put("root_reference", portableReference(root, text(options, "outputRootReference")))
                .put("staging_format", "JSONL").put("storage_role", "STAGING").put("authoritative", false).put("fixture_only", true).put("provenance", "FIXTURE_INJECTED")
                .put("checkpoint_path", checkpointPath); value.set("captures", ordered); value.set("source_receipts", strings(captures.stream().flatMap(row -> objects(row.path("source_receipts")).stream()).map(row -> text(row, "path")).distinct().sorted().toList()));
        value.set("source_receipt_sha256", strings(captures.stream().flatMap(row -> objects(row.path("source_receipts")).stream()).map(row -> textOr(first(row, "content_sha256", "sha256"), "")).distinct().sorted().toList()));
        value.set("source_receipt_byte_sha256", strings(captures.stream().flatMap(row -> objects(row.path("source_receipts")).stream()).flatMap(row -> hashInventory(row.get("byte_sha256")).stream()).distinct().sorted().toList())); value.set("limitations", array());
        ObjectNode manifest = withHash(value); ObjectNode checkpoint = object().put("schema", DATA_V5.get("checkpoint")).put("version", 1).put("plan_sha256", text(plan, "content_sha256"))
                .put("root_reference", text(manifest, "root_reference")).put("producer_code_sha256", javaProducerCodeSha256()).put("coverage_rules_sha256", DATA_V5_COVERAGE_RULES_SHA256)
                .put("fixture_only", true).put("provenance", "FIXTURE_INJECTED"); ObjectNode completed = object(); captures.forEach(row -> completed.set(seriesKey(row), row)); checkpoint.set("completed", completed); checkpoint.set("capture_lineage", object()); checkpoint = withHash(checkpoint); writeCheckpointCas(root, checkpointPath, checkpoint, emptyToNull(expected));
        manifest = manifest.deepCopy(); manifest.remove("content_sha256"); manifest.put("checkpoint_sha256", text(checkpoint, "content_sha256")); return withHash(manifest);
    }

    private static ObjectNode acquireUsingPublicAdapters(ObjectNode options, PublicDataAdapters.InjectableHttpClient transport) {
        ObjectNode plan = requiredObject(options, "plan"); validatePlan(plan); Path root = requiredPath(options, "outputRoot");
        boolean fixture = options.path("fixtureOnly").asBoolean(false);
        if (options.hasNonNull("capturedAt") && !fixture) throw failure("caller-supplied capturedAt is fixture-only for staging acquisition");
        try { Files.createDirectories(root); } catch (IOException error) { throw failure("acquisition output root cannot be created: " + error.getMessage()); }
        String rootReference = portableReference(root, text(options, "outputRootReference"));
        String checkpointPath = textOr(options.get("checkpointPath"), "checkpoint.json");
        ObjectNode checkpoint = loadAcquisitionCheckpoint(root, checkpointPath, plan, rootReference, fixture);
        if (options.has("expectedCheckpointSha256")) {
            String expected = options.path("expectedCheckpointSha256").isNull() ? null : text(options, "expectedCheckpointSha256");
            String actual = checkpoint.hasNonNull("content_sha256") ? text(checkpoint, "content_sha256") : null;
            if (!Objects.equals(expected, actual)) throw failure("checkpoint compare-and-swap predecessor hash mismatch");
        }
        String capturedAt = fixture && options.hasNonNull("capturedAt") ? iso(time(options.get("capturedAt"))) : null;
        int maxPages = positiveInt(options.get("maxPages"), 1_000, "maxPages"), maxRows = positiveInt(options.get("maxRows"), 10_000_000, "maxRows");
        long rateLimit = nonNegativeLong(options.get("rateLimitMs"), 0, "rateLimitMs");
        ArrayNode captures = array(); List<String> limitations = new ArrayList<>(uniqueTextsOrEmpty(plan.get("limitations")));
        ObjectNode completed = checkpoint.path("completed").isObject() ? (ObjectNode) checkpoint.path("completed").deepCopy() : object();
        ObjectNode lineage = checkpoint.path("capture_lineage").isObject() ? (ObjectNode) checkpoint.path("capture_lineage").deepCopy() : object();
        String prior = checkpoint.hasNonNull("content_sha256") ? text(checkpoint, "content_sha256") : null;
        for (ObjectNode series : objects(plan.path("series"))) {
            String identity = seriesKey(series); ObjectNode saved = completed.path(identity).isObject() ? (ObjectNode) completed.path(identity) : null;
            if (saved != null) try {
                verifyCaptureCustody(saved, root, series, text(plan, "content_sha256")); ObjectNode actualLineage = inspectCaptureLineage(saved, root);
                if (!stable(actualLineage).equals(stable(lineage.path(identity))) || !"BOUND".equals(text(actualLineage, "producer_binding_status"))
                        || !"BOUND".equals(text(actualLineage, "adapter_binding_status"))
                        || !javaProducerCodeSha256().equals(text(actualLineage, "producer_code_sha256"))
                        || !javaAdapterCodeSha256().equals(text(actualLineage, "adapter_code_sha256"))) throw failure("checkpoint capture lineage is legacy or stale");
                captures.add(saved.deepCopy()); limitations.addAll(uniqueTextsOrEmpty(saved.get("limitations"))); continue;
            } catch (RuntimeException ignored) { completed.remove(identity); lineage.remove(identity); }
            try {
                ObjectNode capture = acquireSeriesViaAdapter(series, plan, root, transport, maxPages, maxRows, rateLimit, capturedAt, fixture, false);
                verifyCaptureCustody(capture, root, series, text(plan, "content_sha256")); ObjectNode captureLineage = inspectCaptureLineage(capture, root);
                completed.set(identity, capture); lineage.set(identity, captureLineage); captures.add(capture); limitations.addAll(uniqueTextsOrEmpty(capture.get("limitations")));
                ObjectNode next = object().put("schema", DATA_V5.get("checkpoint")).put("version", 1)
                        .put("plan_sha256", text(plan, "content_sha256")).put("root_reference", rootReference);
                putNullable(next, "prior_checkpoint_sha256", prior); next.put("producer_code_sha256", javaProducerCodeSha256())
                        .put("coverage_rules_sha256", DATA_V5_COVERAGE_RULES_SHA256).put("fixture_only", fixture)
                        .put("provenance", fixture ? "FIXTURE_INJECTED" : "PUBLIC_ADAPTER_RECOMPUTED");
                next.set("capture_lineage", lineage.deepCopy()); next.set("completed", completed.deepCopy()); next = withHash(next);
                writeCheckpointCasStrict(root, checkpointPath, next, prior); prior = text(next, "content_sha256"); checkpoint = next;
            } catch (RuntimeException error) {
                ObjectNode unavailable = unavailableCapture(series, error.getMessage()); captures.add(unavailable);
                limitations.addAll(uniqueTextsOrEmpty(unavailable.get("limitations")));
            }
        }
        List<ObjectNode> all = objects(captures), required = all.stream().filter(row -> !row.has("required") || row.path("required").asBoolean()).toList();
        List<ObjectNode> optional = all.stream().filter(row -> row.has("required") && !row.path("required").asBoolean()).toList();
        boolean base = !required.isEmpty() && required.stream().allMatch(StrategyResearchDataV5::captureComplete);
        boolean declared = !all.isEmpty() && all.stream().allMatch(StrategyResearchDataV5::captureComplete);
        List<String> unavailableRequired = required.stream().filter(row -> !captureComplete(row)).map(StrategyResearchDataV5::seriesKey).sorted().toList();
        List<String> unavailableOptional = optional.stream().filter(row -> !captureComplete(row)).map(StrategyResearchDataV5::seriesKey).sorted().toList();
        unavailableRequired.forEach(value -> limitations.add("REQUIRED_SERIES_UNAVAILABLE:" + value));
        unavailableOptional.forEach(value -> limitations.add("OPTIONAL_SERIES_UNAVAILABLE:" + value));
        ObjectNode value = object().put("schema", DATA_V5.get("acquisition")).put("version", 1)
                .put("status", base ? "STAGING_COMPLETE" : "STAGING_PARTIAL").put("plan_sha256", text(plan, "content_sha256"))
                .put("root_reference", rootReference).put("staging_format", "JSONL").put("storage_role", "STAGING")
                .put("authoritative", false).put("fixture_only", fixture).put("provenance", fixture ? "FIXTURE_INJECTED" : "PUBLIC_ADAPTER_RECOMPUTED")
                .put("checkpoint_path", checkpointPath);
        putNullable(value, "checkpoint_sha256", prior); value.set("captures", captures); value.put("base_complete", base).put("declared_complete", declared)
                .put("full_plan_complete", declared).put("completion_scope", declared ? "ALL_DECLARED" : base ? "BASE_ONLY" : "NONE")
                .put("required_series_count", required.size()).put("required_complete_count", required.stream().filter(StrategyResearchDataV5::captureComplete).count())
                .put("optional_series_count", optional.size()).put("optional_complete_count", optional.stream().filter(StrategyResearchDataV5::captureComplete).count())
                .put("optional_complete", optional.stream().allMatch(StrategyResearchDataV5::captureComplete));
        value.set("unavailable_required", strings(unavailableRequired)); value.set("unavailable_optional", strings(unavailableOptional));
        putNullable(value, "declared_requirements_sha256", defined(plan.get("timeframe_requirements_sha256")) ? textValue(plan.get("timeframe_requirements_sha256")) : null);
        value.set("source_receipts", strings(all.stream().flatMap(row -> objects(row.path("source_receipts")).stream()).map(row -> text(row, "path")).distinct().sorted().toList()));
        value.set("source_receipt_sha256", strings(all.stream().flatMap(row -> objects(row.path("source_receipts")).stream()).map(row -> textOr(first(row, "content_sha256", "sha256"), "")).filter(v -> !v.isEmpty()).distinct().sorted().toList()));
        value.set("source_receipt_byte_sha256", strings(all.stream().flatMap(row -> objects(row.path("source_receipts")).stream()).flatMap(row -> hashInventory(row.get("byte_sha256")).stream()).distinct().sorted().toList()));
        value.set("limitations", strings(limitations.stream().distinct().sorted().toList())); value.set("conversion", conversionContract());
        ObjectNode manifest = withHash(value); ObjectNode verify = object().set("manifest", manifest); verify.set("plan", plan); verify.put("root", root.toString())
                .put("planSha256", text(plan, "content_sha256")).put("allowFixture", fixture); verifyAuthoritativeStaging(verify); return manifest;
    }

    private static ObjectNode loadAcquisitionCheckpoint(Path root, String relative, ObjectNode plan, String rootReference, boolean fixture) {
        Path path = writablePath(root, relative, "acquisition checkpoint");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            ObjectNode empty = object().put("schema", DATA_V5.get("checkpoint")).put("version", 1)
                    .put("plan_sha256", text(plan, "content_sha256")).put("root_reference", rootReference); empty.set("completed", object()); empty.set("capture_lineage", object()); return empty;
        }
        ObjectNode value = readObject(verifiedRegularPath(root, relative, "acquisition checkpoint"), "acquisition checkpoint");
        assertOwnHash(value, DATA_V5.get("checkpoint"), "acquisition checkpoint");
        if (!text(value, "plan_sha256").equals(text(plan, "content_sha256")) || !text(value, "root_reference").equals(rootReference)) throw failure("checkpoint is bound to a different plan or portable root");
        if (!javaProducerCodeSha256().equals(text(value, "producer_code_sha256")) || !DATA_V5_COVERAGE_RULES_SHA256.equals(text(value, "coverage_rules_sha256"))) throw failure("checkpoint producer or coverage-rules hash is stale");
        if (value.path("fixture_only").asBoolean(false) != fixture || !text(value, "provenance").equals(fixture ? "FIXTURE_INJECTED" : "PUBLIC_ADAPTER_RECOMPUTED")) throw failure("checkpoint fixture provenance differs from the current acquisition mode");
        List<String> completed = sortedFieldNames(value.path("completed")), lineage = sortedFieldNames(value.path("capture_lineage")); if (!completed.equals(lineage)) throw failure("acquisition checkpoint capture lineage inventory is missing, extra, or mismatched");
        return value;
    }

    private static ObjectNode acquireSeriesViaAdapter(ObjectNode series, ObjectNode plan, Path root,
            PublicDataAdapters.InjectableHttpClient transport, int maxPages, int maxRows, long rateLimit,
            String capturedAt, boolean fixture, boolean forceArchiveReopen) {
        return acquireSeriesViaAdapter(series, plan, root, transport, maxPages, maxRows, rateLimit,
                capturedAt, fixture, forceArchiveReopen, null);
    }

    private static ObjectNode acquireSeriesViaAdapter(ObjectNode series, ObjectNode plan, Path root,
            PublicDataAdapters.InjectableHttpClient transport, int maxPages, int maxRows, long rateLimit,
            String capturedAt, boolean fixture, boolean forceArchiveReopen, Path archiveCheckpointPath) {
        long start = time(series.get("start_at")), end = time(series.get("end_at"));
        PublicDataAdapters.HttpOptions http = new PublicDataAdapters.HttpOptions(transport, capturedAt, fixture, 3, 250);
        PublicDataAdapters.BackfillResult result; List<ObjectNode> rows; ObjectNode coverage; List<PublicDataAdapters.RawResponse> raws;
        List<String> responseHashes; ArrayNode pages; String observedAt;
        if ("funding_events".equals(text(series, "series_type"))) {
            ObjectNode bounds = fundingRequestBounds(series); long queryStart = bounds.path("startTime").asLong(), queryEnd = bounds.path("endTime").asLong();
            PublicDataAdapters.BackfillResult funding = PublicDataAdapters.backfillBinanceFunding(new PublicDataAdapters.FundingOptions(
                    text(series, "asset"), text(series, "symbol"), queryStart, queryEnd, 1_000, http), queryStart, queryEnd, 1_000, maxPages, maxRows, rateLimit);
            List<ObjectNode> observed = bindRowsToSeries(funding.rows(), series, null); ObjectNode canonicalSeries = series.deepCopy().put("require_source_coverage", true)
                    .put("source_coverage_complete", funding.coverage().path("complete").asBoolean(false)); ObjectNode canonicalRequest = object().set("rows", array(observed)); canonicalRequest.set("series", canonicalSeries);
            ObjectNode canonical = canonicalizeFundingRows(canonicalRequest); observed = objects(canonical.path("rows"));
            PublicDataAdapters.BackfillResult marks = PublicDataAdapters.backfillBinanceMarkPriceOhlc(new PublicDataAdapters.OhlcOptions(
                    text(series, "asset"), text(series, "symbol"), queryStart, queryEnd, "1h", 1_000, true, http), queryStart, queryEnd, 1_000, maxPages, maxRows, rateLimit);
            Map<Long, String> responseByEvent = responseHashByKlineEvent(marks.rawResponses(), root); List<ObjectNode> markRows = new ArrayList<>();
            for (ObjectNode source : marks.rows()) { ObjectNode mark = source.deepCopy(); long event = time(mark.get("event_time")); mark.put("availability_time", event); putNullable(mark, "response_sha256", responseByEvent.get(event)); markRows.add(mark); }
            ObjectNode bind = object().set("fundingRows", array(observed)); bind.set("markRows", array(markRows)); bind.set("markResponseSha256", strings(marks.responseSha256())); rows = objects(bindFundingSettlementMarks(bind));
            raws = new ArrayList<>(funding.rawResponses()); raws.addAll(marks.rawResponses()); responseHashes = new ArrayList<>(funding.responseSha256()); responseHashes.addAll(marks.responseSha256());
            pages = combinedPages(funding, marks); observedAt = latestTimestamp(funding.capturedAt(), marks.capturedAt()); coverage = ((ObjectNode) canonical.path("coverage").deepCopy())
                    .put("query_start_at", iso(queryStart)).put("query_end_at", iso(queryEnd))
                    .put("source_pagination_complete", funding.coverage().path("complete").asBoolean(false))
                    .put("settlement_mark_source", "BINANCE_MARK_PRICE_KLINE_OPEN_AT_SETTLEMENT")
                    .put("settlement_mark_source_response_sha256", hash(strings(marks.responseSha256())));
            coverage.set("settlement_mark_events", strings(rows.stream().map(row -> text(row, "settlement_slot")).sorted().toList()));
        } else if ("metrics_events".equals(text(series, "series_type"))) {
            result = PublicDataAdapters.backfillBinanceMetricsArchives(new PublicDataAdapters.ArchiveBackfillOptions(text(series, "asset"), text(series, "symbol"), text(series, "interval"), start, end, maxPages * 31, http, root, archiveCheckpointPath, null, 2, forceArchiveReopen));
            List<String> required = texts(series.path("metric_required_fields")); double minimum = series.path("metric_minimum_field_coverage").asDouble(0.95);
            if ("event".equals(text(series, "interval"))) { rows = bindRowsToSeries(result.rows(), series, null); coverage = result.coverage(); }
            else { PublicDataAdapters.AggregatedMetrics aggregate = PublicDataAdapters.aggregateBinanceMetricsRows(result.rows(), new PublicDataAdapters.MetricsAggregationOptions(text(series, "interval"), start, end, required, minimum)); rows = bindRowsToSeries(aggregate.rows(), series, null); coverage = result.coverage(); coverage.setAll(aggregate.coverage()); }
            coverage.put("complete", false).put("reason", METRICS_PIT_VINTAGE_BLOCK_REASON).put("metrics_pit_vintage_status", "LATEST_RETRIEVAL_NOT_HISTORICAL_VINTAGE"); raws = result.rawResponses(); responseHashes = result.responseSha256(); pages = array(); observedAt = result.capturedAt();
        } else if ("BINANCE_USDM_DATED_FUTURE".equals(text(series, "instrument"))) {
            result = PublicDataAdapters.backfillBinanceDatedKlineArchives(new PublicDataAdapters.ArchiveBackfillOptions(text(series, "asset"), text(series, "symbol"), text(series, "interval"), start, end, maxPages * 31, http, root, archiveCheckpointPath, null, 2, forceArchiveReopen));
            rows = bindRowsToSeries(result.rows(), series, null); coverage = validateDenseBarCoverageV5(rows, series); if (!result.coverage().path("missing_months").isEmpty()) coverage.put("complete", false).put("reason", "MISSING_DATED_ARCHIVE_MONTHS:" + String.join(",", texts(result.coverage().path("missing_months")))); raws = result.rawResponses(); responseHashes = result.responseSha256(); pages = array(); observedAt = result.capturedAt();
        } else {
            boolean mark = "mark_bars".equals(text(series, "series_type")) || "BINANCE_USDM_PERPETUAL_MARK".equals(text(series, "instrument"));
            PublicDataAdapters.OhlcOptions request = new PublicDataAdapters.OhlcOptions(text(series, "asset"), text(series, "symbol"), start, end, text(series, "interval"), 1_000, !"BINANCE_SPOT".equals(text(series, "instrument")), http);
            result = mark ? PublicDataAdapters.backfillBinanceMarkPriceOhlc(request, start, end, 1_000, maxPages, maxRows, rateLimit)
                    : PublicDataAdapters.backfillBinanceOhlc(request, start, end, 1_000, maxPages, maxRows, rateLimit);
            rows = bindRowsToSeries(result.rows(), series, mark ? hash(series) : null); coverage = validateDenseBarCoverageV5(rows, series); raws = result.rawResponses(); responseHashes = result.responseSha256(); pages = result.receipt() == null ? array() : (ArrayNode) result.receipt().path("pages").deepCopy(); observedAt = result.capturedAt();
        }
        coverage = contractCoverage(coverage, start, end); if (coverage.path("irregular_bars").isArray() && !coverage.path("irregular_bars").isEmpty()) coverage.put("reason", "EARLY_CLOSE_OUTAGE:" + compact(coverage.path("irregular_bars")));
        ObjectNode partition = writeJsonlPartition(root, "funding_events".equals(text(series, "series_type")) ? "funding" : "metrics_events".equals(text(series, "series_type")) ? "metrics" : "mark_bars".equals(text(series, "series_type")) ? "mark" : "bars",
                text(series, "asset") + "-" + text(series, "instrument") + "-" + text(series, "symbol") + "-" + text(series, "interval"), rows);
        ArrayNode rawReceipts = array(); for (PublicDataAdapters.RawResponse raw : raws) rawReceipts.add(persistAdapterRaw(root, raw, series));
        ObjectNode receiptPayload = object().put("status", "PUBLIC_OBSERVED").put("plan_sha256", text(plan, "content_sha256"))
                .put("series_sha256", hash(series)).put("producer_code_sha256", javaProducerCodeSha256()).put("adapter_code_sha256", javaAdapterCodeSha256())
                .put("captured_at", observedAt == null ? (capturedAt == null ? iso(System.currentTimeMillis()) : capturedAt) : observedAt);
        ObjectNode adapterReference = persistJavaAdapterReference(root, "public data adapter"); receiptPayload.set("request_metadata", object().set("adapter_code_reference", adapterReference));
        receiptPayload.set("series", object().put("asset", text(series, "asset")).put("instrument", text(series, "instrument")).put("symbol", text(series, "symbol")).put("interval", text(series, "interval")).put("series_type", text(series, "series_type")));
        receiptPayload.set("request", object().put("start_at", text(series, "start_at")).put("end_at", text(series, "end_at"))); receiptPayload.set("response_sha256", strings(responseHashes));
        receiptPayload.set("source_byte_sha256", strings(objects(rawReceipts).stream().map(row -> text(row, "byte_sha256")).toList())); receiptPayload.set("raw_receipts", rawReceipts); receiptPayload.set("pagination", pages); receiptPayload.set("coverage", coverage.deepCopy());
        ObjectNode summary = writeNormalizedSourceReceipt(root, receiptPayload); ObjectNode capture = captureSeries(series); capture.put("series_sha256", hash(series)).put("producer_code_sha256", javaProducerCodeSha256()).put("adapter_code_sha256", javaAdapterCodeSha256()); capture.set("partition", partition); capture.set("source_receipts", array().add(summary)); capture.set("coverage", coverage); capture.set("limitations", strings(coverageLimitations(series, coverage, null))); return capture;
    }

    private static ObjectNode captureSeries(ObjectNode series) { ObjectNode value = series.deepCopy(); value.remove("trade_scope"); return value; }
    private static ObjectNode unavailableCapture(ObjectNode series, String reason) { ObjectNode value = captureSeries(series); value.put("series_sha256", hash(series)).put("unavailable", true); value.set("coverage", object().put("complete", false).put("reason", reason)); value.set("limitations", strings(coverageLimitations(series, (ObjectNode) value.path("coverage"), reason))); return value; }
    private static List<ObjectNode> bindRowsToSeries(List<ObjectNode> input, ObjectNode series, String seriesId) { List<ObjectNode> rows = new ArrayList<>(); for (ObjectNode source : input) { ObjectNode row = source.deepCopy().put("adapter_code_sha256", javaAdapterCodeSha256()).put("producer_code_sha256", javaProducerCodeSha256()).put("asset", text(series, "asset")).put("venue", text(series, "venue")).put("instrument", text(series, "instrument")).put("symbol", text(series, "symbol")).put("interval", text(series, "interval")).put("timeframe", text(series, "interval")).put("series_role", text(series, "series_role")); if ("mark_bars".equals(text(series, "series_type"))) row.put("series_id", seriesId == null ? hash(series) : seriesId).set("cadence_ms", series.get("expected_step_ms")); rows.add(row); } return rows; }
    private static List<ObjectNode> bindHydrationRowsToSeries(List<ObjectNode> input, ObjectNode series, String seriesId) { List<ObjectNode> rows = bindRowsToSeries(input, series, seriesId); rows.forEach(row -> { row.remove("interval"); row.remove("timeframe"); }); return rows; }

    private static ObjectNode contractCoverage(ObjectNode source, long start, long end) { ObjectNode value = source == null ? object() : source.deepCopy(); if (value.has("start_cursor")) { if (!value.has("query_start_at") && value.get("start_cursor").isNumber()) value.put("query_start_at", iso(value.path("start_cursor").asLong())); value.remove("start_cursor"); } if (value.has("end_cursor")) { if (!value.has("query_end_at") && value.get("end_cursor").isNumber()) value.put("query_end_at", iso(value.path("end_cursor").asLong())); value.remove("end_cursor"); } if (value.path("duplicate_events").isBoolean()) value.set("duplicate_events", value.path("duplicate_events").asBoolean() ? array().add("DUPLICATE_EVENTS_PRESENT") : array()); if (!value.has("query_start_at")) value.put("query_start_at", iso(start)); if (!value.has("query_end_at")) value.put("query_end_at", iso(end)); return value; }
    private static List<String> coverageLimitations(ObjectNode series, ObjectNode coverage, String error) { List<String> reasons = new ArrayList<>(); if (error != null && !error.isBlank()) reasons.add(error); for (String field : List.of("reason", "missing_slots", "missing_days", "missing_months", "gap_starts", "duplicate_events", "irregular_bars")) { JsonNode value = coverage == null ? null : coverage.get(field); if (value != null && value.isArray() && !value.isEmpty()) reasons.add(field + "=" + objectsOrScalars(value).stream().map(StrategyResearchDataV5::compact).sorted().reduce((a,b)->a+","+b).orElse("")); else if (value != null && value.isTextual() && !value.asText().isEmpty()) reasons.add(value.asText()); } if (coverage != null && coverage.has("source_pagination_complete") && !coverage.path("source_pagination_complete").asBoolean()) reasons.add("SOURCE_PAGINATION_INCOMPLETE"); if ((coverage == null || !coverage.path("complete").asBoolean()) && reasons.isEmpty()) reasons.add("INCOMPLETE_COVERAGE"); String identity = seriesKey(series); return reasons.stream().map(value -> identity + ":" + value).distinct().sorted().toList(); }

    private static ObjectNode writeJsonlPartition(Path root, String role, String identity, List<ObjectNode> rows) { StringBuilder body = new StringBuilder(); rows.forEach(row -> body.append(stable(row)).append('\n')); byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); String digest = hash(bytes); String path = "staging/" + role + "/" + identity + "-" + digest + ".jsonl"; writeContentAddressed(root, path, bytes, "staging partition"); return object().put("path", path).put("sha256", digest).put("bytes", bytes.length).put("row_count", rows.size()).put("format", "JSONL").put("storage_role", "STAGING").put("authoritative", false); }
    private static ObjectNode persistAdapterRaw(Path root, PublicDataAdapters.RawResponse raw, ObjectNode series) { byte[] bytes = raw.body() != null ? raw.body() : readPhysical(root, raw.path(), "persisted archive response"); String digest = hash(bytes); if (!digest.equals(raw.sha256()) || bytes.length != raw.bytes()) throw failure("persisted archive response bytes are missing or tampered"); String path = raw.path() == null ? "raw/" + digest + ".bin" : raw.path(); writeContentAddressed(root, path, bytes, "raw source response"); ObjectNode request = object().put("symbol", textOr(raw.request().get("symbol"), text(series, "symbol"))).put("interval", textOr(raw.request().get("interval"), text(series, "interval"))).put("response_sha256", digest); copyTextIfPresent(request, raw.request(), "endpoint"); for (String field : List.of("day", "month", "kind")) copyTextIfPresent(request, raw.request(), field); if (raw.request().hasNonNull("period")) request.put("metrics_events".equals(text(series, "series_type")) ? "day" : "month", text(raw.request(), "period")); ObjectNode value = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1).put("path", path).put("source", text(series, "instrument")).put("byte_sha256", digest).put("bytes", bytes.length).put("format", "RAW_BYTES").put("storage_role", "RAW_IGNORED").put("authoritative", false); value.set("request", request); return withHash(value); }
    private static ObjectNode writeNormalizedSourceReceipt(Path root, ObjectNode payload) { ObjectNode value = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1); value.setAll(payload.deepCopy()); value.put("format", "JSON").put("storage_role", "STAGING"); value = withHash(value); String path = "receipts/" + text(value, "content_sha256") + ".json"; writeContentAddressed(root, path, prettyBytes(value), "normalized source receipt"); ObjectNode summary = object().put("path", path).put("sha256", text(value, "content_sha256")).put("content_sha256", text(value, "content_sha256")).put("raw_count", objects(payload.path("raw_receipts")).size()).put("schema", text(value, "schema")).put("status", textOr(payload.get("status"), "PUBLIC_OBSERVED")); summary.set("byte_sha256", payload.path("source_byte_sha256").deepCopy()); return summary; }
    private static Map<Long, String> responseHashByKlineEvent(List<PublicDataAdapters.RawResponse> raws, Path root) { Map<Long, String> result = new HashMap<>(); for (PublicDataAdapters.RawResponse raw : raws) { byte[] bytes = raw.body() != null ? raw.body() : readPhysical(root, raw.path(), "mark raw response"); JsonNode values; try { values = JSON.readTree(bytes); } catch (IOException error) { throw failure("mark raw response JSON is invalid"); } if (!values.isArray()) throw failure("mark raw response is not an array"); for (JsonNode value : values) { long event = value.path(0).asLong(Long.MIN_VALUE); if (event == Long.MIN_VALUE || result.putIfAbsent(event, raw.sha256()) != null) throw failure("funding settlement mark source has ambiguous response page for " + iso(event)); } } return result; }
    private static ArrayNode combinedPages(PublicDataAdapters.BackfillResult first, PublicDataAdapters.BackfillResult second) { ArrayNode pages = array(); if (first.receipt() != null) first.receipt().path("pages").forEach(pages::add); if (second.receipt() != null) second.receipt().path("pages").forEach(pages::add); return pages; }
    private static String latestTimestamp(String... values) { return Arrays.stream(values).filter(Objects::nonNull).max(Comparator.comparingLong(value -> time(com.fasterxml.jackson.databind.node.TextNode.valueOf(value)))).orElse(null); }
    private static ObjectNode conversionContract() { return object().put("status", "AVAILABLE").put("required_format", "PARQUET").put("dependency", "@duckdb/node-api@1.5.5-r.4").put("threads", 1).put("promotion", "REQUIRES_VERIFIED_BYTES_ROWS_SCHEMA_AND_PARTITION_MANIFEST"); }
    private static int positiveInt(JsonNode value, int fallback, String label) { if (!defined(value)) return fallback; int result = value.asInt(-1); if (result < 1) throw failure(label + " must be a positive integer"); return result; }
    private static long nonNegativeLong(JsonNode value, long fallback, String label) { if (!defined(value)) return fallback; long result = value.asLong(-1); if (result < 0) throw failure(label + " must be non-negative"); return result; }
    private static List<String> sortedFieldNames(JsonNode value) { List<String> names = new ArrayList<>(); if (value != null && value.isObject()) value.fieldNames().forEachRemaining(names::add); names.sort(String::compareTo); return names; }
    private static List<JsonNode> objectsOrScalars(JsonNode value) { List<JsonNode> rows = new ArrayList<>(); if (value != null && value.isArray()) value.forEach(rows::add); return rows; }
    private static String compact(JsonNode value) { try { return JSON.writeValueAsString(value); } catch (JsonProcessingException error) { throw failure("JSON serialization failed"); } }

    private record ReplayReceiptCustody(List<ObjectNode> normalized, List<ObjectNode> raws) { }
    private record ReplayParsedPage(ArrayNode values, List<Long> events, List<Long> closes,
            int retainedCount) { }
    private record ReplayPage(ObjectNode page, ObjectNode raw, String endpoint, long cursor,
            String key, byte[] bytes) { }
    private record ReplayCaptureResult(ObjectNode capture, ObjectNode lineage,
            String sourceProducerSha256, String sourceAdapterSha256) { }

    /** A no-network transport whose complete response inventory was proven from source custody. */
    private static final class RawReplayTransport implements PublicDataAdapters.InjectableHttpClient {
        private final ObjectNode series;
        private final Map<String, ReplayPage> rest;
        private final Map<String, byte[]> archives;
        private final Set<String> expectedEndpoints;
        private final Set<String> used = new HashSet<>();
        private final long start;
        private final long end;
        private final String capturedAt;

        RawReplayTransport(ObjectNode series, List<ReplayPage> pages, Map<String, byte[]> archives,
                long start, long end, String capturedAt) {
            this.series = series; this.archives = archives; this.start = start; this.end = end;
            this.capturedAt = capturedAt; this.rest = new LinkedHashMap<>(); this.expectedEndpoints = new HashSet<>();
            for (ReplayPage page : pages) {
                if (rest.put(page.key(), page) != null) throw failure("local raw replay REST page identity is duplicated: " + page.key());
                expectedEndpoints.add(page.endpoint());
            }
        }

        @Override public PublicDataAdapters.FetchResponse fetch(URI uri, Map<String, String> headers) {
            String requestUrl = uri.toString();
            if (!archives.isEmpty()) {
                byte[] bytes = archives.get(requestUrl);
                if (bytes == null) throw failure("local raw replay adapter requested an unretained archive URL: " + requestUrl);
                if (!used.add(requestUrl)) throw failure("local raw replay adapter reused an archive response: " + requestUrl);
                return replayResponse(bytes, capturedAt);
            }
            String endpoint = uri.getScheme() + "://" + uri.getAuthority() + uri.getPath();
            if (!expectedEndpoints.contains(endpoint)) throw failure("local raw replay adapter requested an unretained REST endpoint: " + requestUrl);
            Map<String, String> query = replayQuery(uri);
            if (!text(series, "symbol").equalsIgnoreCase(query.getOrDefault("symbol", ""))) throw failure("local raw replay adapter changed the symbol request: " + seriesKey(series));
            String interval = "https://fapi.binance.com/fapi/v1/markPriceKlines".equals(endpoint)
                    && "funding_events".equals(text(series, "series_type")) ? "1h"
                    : "funding_events".equals(text(series, "series_type")) ? null : text(series, "interval");
            if (interval == null ? query.containsKey("interval") : !interval.equals(query.get("interval"))) throw failure("local raw replay adapter changed the interval request: " + seriesKey(series));
            long cursor = replayLong(query.get("startTime"), "local raw replay adapter startTime");
            long requestedEnd = replayLong(query.get("endTime"), "local raw replay adapter endTime");
            if (cursor < start || requestedEnd != end || !"1000".equals(query.get("limit"))) throw failure("local raw replay adapter changed the bounded request: " + seriesKey(series));
            String key = endpoint + "|" + cursor; ReplayPage page = rest.get(key);
            if (page == null) throw failure("local raw replay adapter requested an unretained REST cursor: " + seriesKey(series) + " " + cursor);
            if (!used.add(key)) throw failure("local raw replay adapter reused a REST cursor: " + seriesKey(series));
            return replayResponse(page.bytes(), capturedAt);
        }

        void assertFullyConsumed() {
            int expected = archives.isEmpty() ? rest.size() : archives.size();
            if (used.size() != expected) throw failure("local raw replay did not reopen every retained response: " + seriesKey(series));
        }
    }

    private static PublicDataAdapters.FetchResponse replayResponse(byte[] bytes, String capturedAt) {
        return new PublicDataAdapters.FetchResponse(200, bytes,
                Map.of("date", List.of(capturedAt)));
    }

    private static Map<String, String> replayQuery(URI uri) {
        Map<String, String> values = new LinkedHashMap<>(); String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return values;
        for (String pair : raw.split("&", -1)) {
            String[] parts = pair.split("=", 2); String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (values.put(name, value) != null) throw failure("local raw replay adapter duplicated query parameter " + name);
        }
        return values;
    }

    private static long replayLong(String value, String label) {
        if (value == null || !value.matches("^-?\\d+$")) throw failure(label + " is invalid");
        try { return Long.parseLong(value); } catch (NumberFormatException error) { throw failure(label + " is invalid"); }
    }

    private static ReplayReceiptCustody replaySourceReceipts(ObjectNode capture, ObjectNode series,
            Path sourceRoot, String planSha256) {
        List<ObjectNode> summaries = concatNodes(capture.path("source_receipts"), capture.path("mark_source_receipts"));
        if (summaries.isEmpty()) throw failure("local raw replay capture has no normalized receipt: " + seriesKey(series));
        Set<String> paths = new HashSet<>(); List<ObjectNode> normalized = new ArrayList<>(), raws = new ArrayList<>();
        for (ObjectNode summary : summaries) {
            String path = text(summary, "path"); if (!paths.add(path)) throw failure("local raw replay normalized receipt path is duplicated: " + seriesKey(series) + " " + path);
            ObjectNode receipt = verifyNormalizedReceipt(sourceRoot, summary, "local raw replay normalized receipt");
            if (receipt.hasNonNull("plan_sha256") && !planSha256.equals(text(receipt, "plan_sha256"))) throw failure("local raw replay receipt plan binding differs: " + seriesKey(series));
            if (receipt.hasNonNull("series_sha256") && !hash(series).equals(text(receipt, "series_sha256"))) throw failure("local raw replay receipt series binding differs: " + seriesKey(series));
            if (receipt.path("series").isObject()) {
                ObjectNode expected = replaySeriesIdentity(series), actual = replaySeriesIdentity((ObjectNode) receipt.path("series"));
                if (!stable(expected).equals(stable(actual))) throw failure("local raw replay receipt identity differs: " + seriesKey(series));
            }
            Set<String> withinReceipt = new HashSet<>();
            for (ObjectNode raw : objects(receipt.path("raw_receipts"))) {
                String identity = text(raw, "path") + "|" + text(raw, "byte_sha256");
                if (!withinReceipt.add(identity)) throw failure("local raw replay raw receipt/path identity is duplicated: " + seriesKey(series));
                verifyRawReceipt(sourceRoot, raw, "local raw replay raw response"); raws.add(raw);
            }
            normalized.add(receipt);
        }
        if (raws.isEmpty()) throw failure("local raw replay capture has no retained raw responses: " + seriesKey(series));
        return new ReplayReceiptCustody(List.copyOf(normalized), List.copyOf(raws));
    }

    private static ObjectNode replaySeriesIdentity(ObjectNode series) {
        return object().put("asset", text(series, "asset")).put("instrument", text(series, "instrument"))
                .put("symbol", text(series, "symbol")).put("interval", text(series, "interval"))
                .put("series_type", text(series, "series_type"));
    }

    private static String replayEndpoint(ObjectNode series) {
        if ("funding_events".equals(text(series, "series_type"))) return "https://fapi.binance.com/fapi/v1/fundingRate";
        if ("mark_bars".equals(text(series, "series_type")) || "BINANCE_USDM_PERPETUAL_MARK".equals(text(series, "instrument"))) return "https://fapi.binance.com/fapi/v1/markPriceKlines";
        if ("BINANCE_USDM_PERPETUAL".equals(text(series, "instrument"))) return "https://fapi.binance.com/fapi/v1/klines";
        if ("BINANCE_SPOT".equals(text(series, "instrument"))) return "https://api.binance.com/api/v3/klines";
        throw failure("local raw replay does not support REST series " + text(series, "instrument") + "/" + text(series, "series_type"));
    }

    private static ReplayParsedPage replayParsePage(byte[] bytes, ObjectNode series, long capturedAt,
            boolean mark) {
        JsonNode parsed; try { parsed = JSON.readTree(bytes); } catch (IOException error) { throw failure("local raw replay REST response is not valid JSON: " + error.getMessage()); }
        if (!parsed.isArray()) throw failure("local raw replay REST response is not an array");
        ArrayNode values = (ArrayNode) parsed; List<Long> events = new ArrayList<>(), closes = new ArrayList<>();
        boolean funding = "funding_events".equals(text(series, "series_type")) && !mark;
        for (int index = 0; index < values.size(); index++) {
            JsonNode row = values.get(index); long event, close;
            try {
                event = funding ? replayJsonLong(row.get("fundingTime")) : replayJsonLong(row.path(0));
                close = funding ? event : replayJsonLong(row.path(6));
            } catch (RuntimeException error) { throw failure("local raw replay REST response row " + index + " has an invalid timestamp"); }
            if (!events.isEmpty() && event <= events.get(events.size() - 1)) throw failure("local raw replay REST response page is unordered, duplicated, or ambiguous");
            events.add(event); closes.add(close);
        }
        int retained = funding ? values.size() : (int) closes.stream().filter(value -> value <= capturedAt).count();
        return new ReplayParsedPage(values, List.copyOf(events), List.copyOf(closes), retained);
    }

    private static long replayJsonLong(JsonNode value) {
        if (value == null || value.isNull()) throw failure("timestamp is absent");
        if (value.isIntegralNumber()) return value.longValue();
        if (value.isTextual() && value.asText().matches("^-?\\d+$")) return Long.parseLong(value.asText());
        if (value.isFloatingPointNumber() && Double.isFinite(value.doubleValue()) && Math.rint(value.doubleValue()) == value.doubleValue()) return (long) value.doubleValue();
        throw failure("timestamp is invalid");
    }

    private static List<ReplayPage> replayRestPages(ObjectNode series, ReplayReceiptCustody custody,
            Path sourceRoot, String capturedAt) {
        List<ObjectNode> pages = custody.normalized().stream().flatMap(value -> objects(value.path("pagination")).stream()).toList();
        if (pages.isEmpty()) throw failure("local raw replay REST capture has no pagination receipt: " + seriesKey(series));
        String primary = replayEndpoint(series), markEndpoint = "https://fapi.binance.com/fapi/v1/markPriceKlines";
        ObjectNode bounds = "funding_events".equals(text(series, "series_type")) ? fundingRequestBounds(series) : null;
        long start = bounds == null ? time(series.get("start_at")) : bounds.path("startTime").asLong();
        long end = bounds == null ? time(series.get("end_at")) : bounds.path("endTime").asLong();
        long step = "funding_events".equals(text(series, "series_type")) ? 1 : series.path("expected_step_ms").asLong(-1);
        if (step <= 0) throw failure("local raw replay series cadence is invalid: " + seriesKey(series));
        Map<String, List<ObjectNode>> rawsByHash = new HashMap<>();
        for (ObjectNode raw : custody.raws()) rawsByHash.computeIfAbsent(text(raw, "byte_sha256"), ignored -> new ArrayList<>()).add(raw);
        Map<String, List<ObjectNode>> groups = new LinkedHashMap<>();
        for (ObjectNode page : pages) {
            String endpoint = text(page, "endpoint");
            if (!endpoint.equals(primary) && !("funding_events".equals(text(series, "series_type")) && endpoint.equals(markEndpoint))) throw failure("local raw replay pagination endpoint differs from the frozen series: " + seriesKey(series));
            groups.computeIfAbsent(endpoint, ignored -> new ArrayList<>()).add(page);
        }
        List<ReplayPage> result = new ArrayList<>(); Set<String> keys = new HashSet<>(), usedRaw = new HashSet<>();
        long captured = time(com.fasterxml.jackson.databind.node.TextNode.valueOf(capturedAt));
        for (var group : groups.entrySet()) {
            String endpoint = group.getKey(); String expectedInterval = endpoint.equals(markEndpoint)
                    && "funding_events".equals(text(series, "series_type")) ? "1h"
                    : "funding_events".equals(text(series, "series_type")) ? null : text(series, "interval");
            long expectedCursor = start; boolean sawEmpty = false; List<ObjectNode> endpointPages = group.getValue();
            for (int index = 0; index < endpointPages.size(); index++) {
                ObjectNode page = endpointPages.get(index); long cursor = page.path("cursor").asLong(Long.MIN_VALUE);
                if (!page.path("page").canConvertToInt() || page.path("page").asInt() != index || cursor == Long.MIN_VALUE || !page.hasNonNull("response_sha256")) throw failure("local raw replay pagination order/index is invalid: " + seriesKey(series));
                if (cursor != expectedCursor) throw failure("local raw replay pagination cursor/order mismatch: " + seriesKey(series));
                boolean intervalMatches = expectedInterval == null ? !defined(page.get("interval")) : expectedInterval.equals(text(page, "interval"));
                if (!text(series, "symbol").equalsIgnoreCase(text(page, "symbol")) || !intervalMatches) throw failure("local raw replay pagination request differs from the frozen series: " + seriesKey(series));
                ObjectNode raw = null;
                for (ObjectNode candidate : rawsByHash.getOrDefault(text(page, "response_sha256"), List.of())) {
                    JsonNode request = candidate.path("request"); boolean rawInterval = expectedInterval == null
                            ? !defined(request.get("interval")) || "event".equals(text(request, "interval"))
                            : expectedInterval.equals(text(request, "interval"));
                    if (endpoint.equals(text(request, "endpoint")) && text(series, "symbol").equalsIgnoreCase(text(request, "symbol")) && rawInterval) { raw = candidate; break; }
                }
                if (raw == null || !text(raw, "byte_sha256").equals(text(raw.path("request"), "response_sha256"))) throw failure("local raw replay pagination response has no retained raw bytes: " + seriesKey(series));
                String key = endpoint + "|" + cursor; if (!keys.add(key)) throw failure("local raw replay pagination cursor is duplicated: " + seriesKey(series));
                String rawIdentity = text(raw, "path") + "|" + text(raw, "byte_sha256"); if (!usedRaw.add(rawIdentity)) throw failure("local raw replay pagination response bytes are reused for ambiguous pages: " + seriesKey(series));
                byte[] bytes = readPhysical(sourceRoot, text(raw, "path"), "local raw replay raw response"); ReplayParsedPage parsed = replayParsePage(bytes, series, captured, endpoint.equals(markEndpoint));
                if (page.path("row_count").asInt(-1) != parsed.retainedCount()) throw failure("local raw replay page row count differs from retained response bytes: " + seriesKey(series));
                long lower = endpoint.equals(markEndpoint) && "funding_events".equals(text(series, "series_type")) ? Long.MIN_VALUE : start;
                long upper = endpoint.equals(markEndpoint) && "funding_events".equals(text(series, "series_type")) ? Long.MAX_VALUE : end;
                if (parsed.events().stream().anyMatch(value -> value < lower || value > upper)) throw failure("local raw replay page event is outside the frozen series bounds: " + seriesKey(series));
                if (!parsed.events().isEmpty()) expectedCursor = parsed.events().get(parsed.events().size() - 1)
                        + (endpoint.equals(markEndpoint) && "funding_events".equals(text(series, "series_type")) ? 3_600_000 : step);
                else { if (sawEmpty || index != endpointPages.size() - 1) throw failure("local raw replay empty page is not the final ordered page: " + seriesKey(series)); sawEmpty = true; }
                result.add(new ReplayPage(page, raw, endpoint, cursor, key, bytes));
            }
        }
        List<String> rawInventory = custody.raws().stream().map(raw -> text(raw, "path") + "|" + text(raw, "byte_sha256")).distinct().sorted().toList();
        List<String> pageInventory = result.stream().map(page -> text(page.raw(), "path") + "|" + text(page.raw(), "byte_sha256")).distinct().sorted().toList();
        if (!rawInventory.equals(pageInventory)) throw failure("local raw replay REST raw/page inventory mismatch: " + seriesKey(series));
        return List.copyOf(result);
    }

    private static Map<String, byte[]> replayArchiveResponses(ObjectNode series,
            ReplayReceiptCustody custody, Path sourceRoot) {
        boolean metrics = "metrics_events".equals(text(series, "series_type"));
        List<String> files = metrics ? replayDayKeys(time(series.get("start_at")), time(series.get("end_at")))
                : replayMonthKeys(time(series.get("start_at")), time(series.get("end_at")));
        String symbol = text(series, "symbol").toUpperCase(Locale.ROOT), interval = text(series, "interval");
        Map<String, String> expected = new LinkedHashMap<>();
        for (String file : files) {
            String token = metrics ? symbol + "-metrics-" + file : symbol + "-" + interval + "-" + file;
            String base = metrics ? "https://data.binance.vision/data/futures/um/daily/metrics/" + symbol + "/" + token
                    : "https://data.binance.vision/data/futures/um/monthly/klines/" + symbol + "/" + interval + "/" + token;
            expected.put(base + ".zip", "ARCHIVE_ZIP"); expected.put(base + ".zip.CHECKSUM", "ARCHIVE_CHECKSUM");
        }
        Map<String, byte[]> actual = new LinkedHashMap<>(); Set<String> seen = new HashSet<>();
        for (ObjectNode raw : custody.raws()) {
            String identity = text(raw, "path") + "|" + text(raw, "byte_sha256"); if (!seen.add(identity)) continue;
            ObjectNode request = (ObjectNode) raw.path("request"); String endpoint = text(request, "endpoint"), kind = expected.get(endpoint);
            String period = text(request, metrics ? "day" : "month");
            if (kind == null || !kind.equals(text(request, "kind")) || !symbol.equals(text(request, "symbol").toUpperCase(Locale.ROOT))
                    || !files.contains(period) || !text(raw, "byte_sha256").equals(text(request, "response_sha256"))) throw failure("local raw replay archive request differs from the frozen series: " + seriesKey(series));
            if (actual.put(endpoint, readPhysical(sourceRoot, text(raw, "path"), "local raw replay archive response")) != null) throw failure("local raw replay archive request is duplicated or ambiguous: " + seriesKey(series));
        }
        if (!actual.keySet().equals(expected.keySet())) throw failure("local raw replay archive file inventory is incomplete or has extra responses: " + seriesKey(series));
        for (String endpoint : expected.keySet()) if (endpoint.endsWith(".zip")) {
            byte[] zip = actual.get(endpoint), checksum = actual.get(endpoint + ".CHECKSUM");
            if (checksum == null || !hash(zip).equals(replayChecksum(checksum))) throw failure("local raw replay archive CHECKSUM binding differs: " + seriesKey(series));
        }
        return Map.copyOf(actual);
    }

    private static String replayChecksum(byte[] bytes) {
        Matcher match = Pattern.compile("\\b([a-fA-F0-9]{64})\\b").matcher(new String(bytes, StandardCharsets.UTF_8));
        if (!match.find()) throw failure("local raw replay checksum response has no SHA-256 digest"); return match.group(1).toLowerCase(Locale.ROOT);
    }

    private static List<String> replayMonthKeys(long start, long end) {
        LocalDate cursor = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        LocalDate finish = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1); List<String> values = new ArrayList<>();
        while (!cursor.isAfter(finish)) { values.add(String.format(Locale.ROOT, "%04d-%02d", cursor.getYear(), cursor.getMonthValue())); cursor = cursor.plusMonths(1); } return values;
    }

    private static List<String> replayDayKeys(long start, long end) {
        LocalDate cursor = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate(), finish = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate(); List<String> values = new ArrayList<>();
        while (!cursor.isAfter(finish)) { values.add(cursor.toString()); cursor = cursor.plusDays(1); } return values;
    }

    private static ArrayNode replayPageSignature(List<ObjectNode> receipts) {
        ArrayNode result = array();
        for (ObjectNode receipt : receipts) for (ObjectNode page : objects(receipt.path("pagination"))) {
            ObjectNode value = object().put("page", page.path("page").asLong()).put("cursor", page.path("cursor").asLong())
                    .put("row_count", page.path("row_count").asLong()); copyNullable(value, "response_sha256", page.get("response_sha256"));
            copyNullable(value, "endpoint", page.get("endpoint")); copyNullable(value, "symbol", page.get("symbol")); copyNullable(value, "interval", page.get("interval")); result.add(value);
        }
        return result;
    }

    private static boolean replayCoverageMatches(ObjectNode source, ObjectNode replayed) {
        ObjectNode left = source.deepCopy(), right = replayed.deepCopy(); JsonNode sourceEvents = left.remove("settlement_mark_events"), replayedEvents = right.remove("settlement_mark_events");
        if (!stable(left).equals(stable(right))) return false;
        if (!defined(sourceEvents)) return !defined(replayedEvents) || replayedEvents.isArray();
        if (!sourceEvents.isArray() || !replayedEvents.isArray()) return false;
        List<String> expected = texts(sourceEvents).stream().sorted().toList(), actual = texts(replayedEvents).stream().sorted().toList();
        return new HashSet<>(expected).size() == expected.size() && new HashSet<>(actual).size() == actual.size() && actual.containsAll(expected);
    }

    static boolean replayCoverageStrengtheningMatches(ObjectNode source, ObjectNode replayed) {
        return replayCoverageMatches(source, replayed);
    }

    private record AuxiliaryMetricsCheckpoint(String relativePath, ObjectNode checkpoint,
            Map<String, byte[]> actual, List<String> missing, int verifiedRawCount,
            List<String> files, List<String> savedKeys) {
        int savedCount() { return savedKeys.size(); }
        int remainingCount() { return files.size() - savedKeys.size(); }
        boolean complete() { return savedKeys.size() == files.size(); }
    }

    private record AuxiliaryReplayResult(boolean partial, ObjectNode capture, ObjectNode lineage,
            String checkpointPath, String sourceCheckpointSha256, int rawVerifiedCount,
            int savedCount, int remainingCount) { }

    private static String auxiliaryMetricsCheckpointPath(ObjectNode series) {
        return "checkpoints/metrics-" + text(series, "asset").toLowerCase(Locale.ROOT) + "-"
                + text(series, "symbol").toLowerCase(Locale.ROOT) + ".json";
    }

    private static AuxiliaryMetricsCheckpoint verifyAuxiliaryMetricsCheckpoint(ObjectNode series,
            Path sourceRoot) {
        if (!"metrics_events".equals(text(series, "series_type"))) throw failure("auxiliary metrics replay requires a metrics series: " + seriesKey(series));
        String relative = auxiliaryMetricsCheckpointPath(series); ObjectNode checkpoint = readObject(
                verifiedRegularPath(sourceRoot, relative, "local raw replay metrics checkpoint"),
                "local raw replay metrics checkpoint");
        if (!text(checkpoint, "content_sha256").equals(ownHash(checkpoint))) throw failure("local raw replay metrics checkpoint hash is invalid: " + seriesKey(series));
        List<String> files = replayDayKeys(time(series.get("start_at")), time(series.get("end_at")));
        String asset = text(series, "asset").toLowerCase(Locale.ROOT), symbol = text(series, "symbol").toUpperCase(Locale.ROOT);
        ObjectNode identity = object().put("kind", "METRICS-" + asset + "-" + symbol).put("asset", asset).put("symbol", symbol)
                .put("start", time(series.get("start_at"))).put("end", time(series.get("end_at"))); identity.set("files", strings(files));
        if (!hash(identity).equals(text(checkpoint, "key"))) throw failure("local raw replay metrics checkpoint request/bounds differ from the frozen series: " + seriesKey(series));
        ObjectNode savedFiles = checkpoint.path("files").isObject() ? (ObjectNode) checkpoint.path("files") : object(); List<String> savedKeys = new ArrayList<>(); savedFiles.fieldNames().forEachRemaining(savedKeys::add);
        if (savedKeys.size() > files.size() || !savedKeys.equals(files.subList(0, savedKeys.size()))) throw failure("local raw replay metrics checkpoint is not an exact chronological prefix of the frozen series: " + seriesKey(series));
        Map<String, byte[]> actual = new LinkedHashMap<>(); List<String> missing = new ArrayList<>(); int verified = 0;
        for (String file : savedKeys) {
            JsonNode value = savedFiles.get(file); if (value == null || !value.isObject()) throw failure("local raw replay metrics checkpoint status is invalid: " + seriesKey(series) + " " + file); ObjectNode saved = (ObjectNode) value;
            int status = saved.path("status").asInt(-1); if (!file.equals(text(saved, "file")) || status != 200 && status != 404) throw failure("local raw replay metrics checkpoint status is invalid: " + seriesKey(series) + " " + file);
            String token = symbol + "-metrics-" + file, base = "https://data.binance.vision/data/futures/um/daily/metrics/" + symbol + "/" + token;
            if (status == 404) {
                missing.add(file); List<ObjectNode> refs = objects(saved.path("raw"));
                boolean checkedAtValid; try { time(saved.get("checked_at")); checkedAtValid = true; } catch (RuntimeException error) { checkedAtValid = false; }
                if (refs.size() != 1 || !"HTTP_ERROR".equals(text(refs.get(0), "kind")) || saved.path("status_code").asInt(-1) != 404 || !checkedAtValid
                        || !saved.path("recheck_after_ms").canConvertToLong() || saved.path("recheck_after_ms").asLong(-1) < 0) throw failure("local raw replay metrics missing-day receipt is ambiguous: " + seriesKey(series) + " " + file);
                ObjectNode reference = refs.get(0), request = (ObjectNode) reference.path("request");
                if (!(base + ".zip").equals(text(request, "endpoint")) || !"HTTP_ERROR".equals(text(request, "kind")) || request.path("status").asInt(-1) != 404 || !reference.hasNonNull("sha256")) throw failure("local raw replay metrics missing-day request differs from the frozen series: " + seriesKey(series) + " " + file);
                byte[] bytes = readPhysical(sourceRoot, text(reference, "path"), "local raw replay metrics missing-day response");
                if (!hash(bytes).equals(text(reference, "sha256")) || bytes.length != reference.path("bytes").asLong(-1)) throw failure("local raw replay metrics missing-day bytes changed: " + seriesKey(series) + " " + file); verified++; continue;
            }
            List<ObjectNode> refs = objects(saved.path("raw")); if (refs.size() != 2 || !saved.hasNonNull("archive_sha256") || !saved.hasNonNull("checksum_sha256")) throw failure("local raw replay metrics archive receipt is incomplete: " + seriesKey(series) + " " + file);
            Map<String, String> expected = Map.of(base + ".zip", "ARCHIVE_ZIP", base + ".zip.CHECKSUM", "ARCHIVE_CHECKSUM"); Map<String, byte[]> bodies = new LinkedHashMap<>();
            for (ObjectNode reference : refs) {
                ObjectNode request = (ObjectNode) reference.path("request"); String endpoint = text(request, "endpoint"), kind = expected.get(endpoint);
                if (kind == null || !kind.equals(text(reference, "kind")) || !kind.equals(text(request, "kind")) || !symbol.equals(text(request, "symbol").toUpperCase(Locale.ROOT)) || !file.equals(text(request, "day")) || !reference.hasNonNull("sha256")) throw failure("local raw replay metrics archive request differs from the frozen series: " + seriesKey(series) + " " + file);
                byte[] bytes = readPhysical(sourceRoot, text(reference, "path"), "local raw replay metrics archive response");
                if (!hash(bytes).equals(text(reference, "sha256")) || bytes.length != reference.path("bytes").asLong(-1)) throw failure("local raw replay metrics archive bytes changed: " + seriesKey(series) + " " + file);
                if (bodies.put(endpoint, bytes) != null) throw failure("local raw replay metrics archive response is duplicated: " + seriesKey(series) + " " + file); verified++;
            }
            byte[] zip = bodies.get(base + ".zip"), checksum = bodies.get(base + ".zip.CHECKSUM");
            if (zip == null || checksum == null || !hash(zip).equals(text(saved, "archive_sha256")) || !hash(checksum).equals(text(saved, "checksum_sha256")) || !hash(zip).equals(replayChecksum(checksum))) throw failure("local raw replay metrics CHECKSUM binding differs from retained bytes: " + seriesKey(series) + " " + file);
            try { PublicDataAdapters.parseBinanceMetricsArchive(zip, new PublicDataAdapters.MetricsArchiveOptions(text(series, "asset"), symbol, time(series.get("start_at")), time(series.get("end_at")))); }
            catch (RuntimeException error) { throw failure("local raw replay metrics archive parser rejected retained bytes for " + seriesKey(series) + " " + file + ": " + error.getMessage()); }
            actual.put(base + ".zip", zip); actual.put(base + ".zip.CHECKSUM", checksum);
        }
        return new AuxiliaryMetricsCheckpoint(relative, checkpoint, Map.copyOf(actual), List.copyOf(missing), verified, List.copyOf(files), List.copyOf(savedKeys));
    }

    private static AuxiliaryReplayResult replayAuxiliaryMetricsFromRaw(ObjectNode series,
            ObjectNode plan, Path sourceRoot, Path targetRoot, ObjectNode existingTarget) {
        if (existingTarget != null) verifyCaptureCustody(existingTarget, targetRoot, series, text(plan, "content_sha256"));
        AuxiliaryMetricsCheckpoint verified = verifyAuxiliaryMetricsCheckpoint(series, sourceRoot);
        if (!verified.complete()) {
            ObjectNode savedFiles = (ObjectNode) verified.checkpoint().path("files");
            for (String file : verified.savedKeys()) for (ObjectNode reference : objects(savedFiles.path(file).path("raw"))) {
                byte[] bytes = readPhysical(sourceRoot, text(reference, "path"), "local raw replay metrics prefix response");
                if (!hash(bytes).equals(text(reference, "sha256"))) throw failure("local raw replay metrics prefix response bytes do not match their retained hash");
                writeContentAddressed(targetRoot, text(reference, "path"), bytes, "local raw replay metrics prefix response");
            }
            ObjectNode targetCheckpoint = object().put("key", text(verified.checkpoint(), "key")); ObjectNode files = object();
            for (String file : verified.savedKeys()) files.set(file, savedFiles.path(file).deepCopy()); targetCheckpoint.set("files", files); targetCheckpoint = withHash(targetCheckpoint);
            replayWriteJson(targetRoot, verified.relativePath(), targetCheckpoint, "local raw replay metrics prefix checkpoint");
            return new AuxiliaryReplayResult(true, null, null, verified.relativePath(), text(verified.checkpoint(), "content_sha256"), verified.verifiedRawCount(), verified.savedCount(), verified.remainingCount());
        }
        if (!verified.missing().isEmpty()) {
            String reason = "AUXILIARY_METRICS_MISSING_DAYS:" + String.join(",", verified.missing()); ObjectNode capture = replayUnavailableCapture(series);
            capture.set("coverage", object().put("complete", false).put("reason", reason)); capture.set("limitations", array().add(seriesKey(series) + ":" + reason)); capture.put("auxiliary_raw_verified_count", verified.verifiedRawCount()).put("auxiliary_checkpoint_path", verified.relativePath());
            return new AuxiliaryReplayResult(false, capture, null, verified.relativePath(), text(verified.checkpoint(), "content_sha256"), verified.verifiedRawCount(), verified.savedCount(), verified.remainingCount());
        }
        long capturedMillis = verified.savedKeys().stream().map(file -> verified.checkpoint().path("files").path(file).get("captured_at"))
                .filter(Objects::nonNull).mapToLong(StrategyResearchDataV5::time).max().orElseThrow(() -> failure("local raw replay auxiliary metrics checkpoint has no captured_at")); String capturedAt = iso(capturedMillis);
        RawReplayTransport transport = new RawReplayTransport(series, List.of(), verified.actual(), time(series.get("start_at")), time(series.get("end_at")), capturedAt);
        String workRelative = "checkpoints/.raw-replay-work-" + hash(series) + ".json"; Path workCheckpoint = writablePath(targetRoot, workRelative, "local raw replay auxiliary work checkpoint");
        if (Files.exists(workCheckpoint, LinkOption.NOFOLLOW_LINKS)) throw failure("local raw replay auxiliary work checkpoint collision: " + workRelative);
        ObjectNode capture;
        try { capture = acquireSeriesViaAdapter(series, plan, targetRoot, transport, 1_000, 10_000_000, 0, capturedAt, true, true, workCheckpoint); transport.assertFullyConsumed(); }
        finally { try { if (Files.exists(workCheckpoint, LinkOption.NOFOLLOW_LINKS)) { PathConfinement.validateSinglyLinkedFile(workCheckpoint, "local raw replay auxiliary work checkpoint"); Files.delete(workCheckpoint); } } catch (IOException error) { throw failure("local raw replay auxiliary work checkpoint cleanup failed: " + error.getMessage()); } }
        capture = normalizeAuxiliaryMetricsCapture(capture, series, targetRoot, verified);
        for (ObjectNode summary : objects(capture.path("source_receipts"))) verifyNormalizedReceipt(targetRoot, summary, "local raw replay auxiliary metrics output receipt");
        ObjectNode lineage = inspectCaptureLineage(capture, targetRoot);
        if (!hash(series).equals(text(capture, "series_sha256")) || !javaProducerCodeSha256().equals(text(capture, "producer_code_sha256")) || !javaAdapterCodeSha256().equals(text(capture, "adapter_code_sha256")) || !"BOUND".equals(text(lineage, "producer_binding_status")) || !"BOUND".equals(text(lineage, "adapter_binding_status"))) throw failure("local raw replay auxiliary metrics output lineage is not current: " + seriesKey(series));
        verifyCaptureCustody(capture, targetRoot, series, text(plan, "content_sha256")); if (existingTarget != null && !stable(existingTarget).equals(stable(capture))) throw failure("local raw replay existing target capture differs from deterministic auxiliary replay: " + seriesKey(series));
        return new AuxiliaryReplayResult(false, capture, lineage, verified.relativePath(), text(verified.checkpoint(), "content_sha256"), verified.verifiedRawCount(), verified.savedCount(), verified.remainingCount());
    }

    private static ObjectNode normalizeAuxiliaryMetricsCapture(ObjectNode capture, ObjectNode series,
            Path targetRoot, AuxiliaryMetricsCheckpoint verified) {
        List<ObjectNode> summaries = objects(capture.path("source_receipts")); if (summaries.size() != 1) throw failure("local raw replay auxiliary metrics output receipt inventory is ambiguous");
        ObjectNode prior = verifyNormalizedReceipt(targetRoot, summaries.get(0), "local raw replay auxiliary metrics pre-normalization receipt"); ArrayNode raws = array();
        for (ObjectNode original : objects(prior.path("raw_receipts"))) {
            ObjectNode raw = original.deepCopy(); raw.remove("content_sha256"); ObjectNode request = (ObjectNode) raw.path("request"); request.put("interval", text(series, "interval")); raws.add(withHash(raw));
        }
        ObjectNode coverage = (ObjectNode) capture.path("coverage").deepCopy(); coverage.put("checkpoint_path", targetRoot.resolve(verified.relativePath()).normalize().toString()).put("checkpoint_sha256", text(verified.checkpoint(), "content_sha256"));
        ObjectNode payload = prior.deepCopy(); payload.remove(List.of("content_sha256", "format", "storage_role")); payload.set("raw_receipts", raws); payload.set("coverage", coverage);
        ObjectNode summary = writeNormalizedSourceReceipt(targetRoot, payload); replayWriteJson(targetRoot, verified.relativePath(), verified.checkpoint(), "local raw replay auxiliary metrics checkpoint");
        ObjectNode normalized = capture.deepCopy(); normalized.set("source_receipts", array().add(summary)); normalized.set("coverage", coverage); normalized.set("limitations", strings(coverageLimitations(series, coverage, null))); return normalized;
    }

    private static ReplayCaptureResult replayCaptureFromRaw(ObjectNode capture, ObjectNode series,
            ObjectNode plan, Path sourceRoot, Path targetRoot, ObjectNode existingTarget) {
        if (existingTarget != null) verifyCaptureCustody(existingTarget, targetRoot, series, text(plan, "content_sha256"));
        ReplayReceiptCustody custody = replaySourceReceipts(capture, series, sourceRoot, text(plan, "content_sha256"));
        long capturedMillis = custody.normalized().stream().filter(value -> value.hasNonNull("captured_at"))
                .mapToLong(value -> time(value.get("captured_at"))).max().orElseThrow(() -> failure("local raw replay receipt has no valid captured_at: " + seriesKey(series)));
        String capturedAt = iso(capturedMillis); boolean archive = "BINANCE_USDM_DATED_FUTURE".equals(text(series, "instrument")) || "metrics_events".equals(text(series, "series_type"));
        ObjectNode bounds = "funding_events".equals(text(series, "series_type")) ? fundingRequestBounds(series) : null;
        long start = bounds == null ? time(series.get("start_at")) : bounds.path("startTime").asLong(); long end = bounds == null ? time(series.get("end_at")) : bounds.path("endTime").asLong();
        List<ReplayPage> pages = archive ? List.of() : replayRestPages(series, custody, sourceRoot, capturedAt);
        Map<String, byte[]> archives = archive ? replayArchiveResponses(series, custody, sourceRoot) : Map.of();
        RawReplayTransport transport = new RawReplayTransport(series, pages, archives, start, end, capturedAt);
        ObjectNode replayed = acquireSeriesViaAdapter(series, plan, targetRoot, transport, 1_000, 10_000_000, 0, capturedAt, true, archive); transport.assertFullyConsumed();
        List<ObjectNode> targetReceipts = new ArrayList<>(); for (ObjectNode summary : objects(replayed.path("source_receipts"))) targetReceipts.add(verifyNormalizedReceipt(targetRoot, summary, "local raw replay output receipt"));
        if (!stable(replayPageSignature(custody.normalized())).equals(stable(replayPageSignature(targetReceipts)))) throw failure("local raw replay page/request/row inventory changed: " + seriesKey(series));
        ObjectNode sourceCoverage = custody.normalized().stream().map(value -> value.path("coverage")).filter(JsonNode::isObject).map(value -> (ObjectNode) value).findFirst().orElse((ObjectNode) capture.path("coverage"));
        if (!replayCoverageMatches(sourceCoverage, (ObjectNode) replayed.path("coverage"))) throw failure("local raw replay coverage changed: " + seriesKey(series));
        if (!hash(series).equals(text(replayed, "series_sha256")) || !javaProducerCodeSha256().equals(text(replayed, "producer_code_sha256")) || !javaAdapterCodeSha256().equals(text(replayed, "adapter_code_sha256"))) throw failure("local raw replay output lineage is not current: " + seriesKey(series));
        verifyCaptureCustody(replayed, targetRoot, series, text(plan, "content_sha256")); ObjectNode lineage = inspectCaptureLineage(replayed, targetRoot);
        if (!"BOUND".equals(text(lineage, "producer_binding_status")) || !"BOUND".equals(text(lineage, "adapter_binding_status")) || !javaProducerCodeSha256().equals(text(lineage, "producer_code_sha256")) || !javaAdapterCodeSha256().equals(text(lineage, "adapter_code_sha256"))) throw failure("local raw replay output capture lineage is not current: " + seriesKey(series));
        if (existingTarget != null && !stable(existingTarget).equals(stable(replayed))) throw failure("local raw replay existing target capture differs from deterministic replay: " + seriesKey(series));
        return new ReplayCaptureResult(replayed, lineage, emptyToNull(text(capture, "producer_code_sha256")), emptyToNull(text(capture, "adapter_code_sha256")));
    }

    private static ObjectNode replayUnavailableCapture(ObjectNode series) {
        ObjectNode capture = captureSeries(series); capture.put("series_sha256", hash(series)).put("unavailable", true);
        capture.set("coverage", object().put("complete", false).put("reason", "SOURCE_CAPTURE_NOT_RETAINED_FOR_LOCAL_REPLAY"));
        capture.set("limitations", array().add(seriesKey(series) + ":SOURCE_CAPTURE_NOT_RETAINED_FOR_LOCAL_REPLAY")); return capture;
    }

    private static Map<String, ObjectNode> replayExistingTargetCaptures(Path targetRoot,
            String checkpointPath, ObjectNode plan) {
        Path path = writablePath(targetRoot, checkpointPath, "local raw replay existing target checkpoint");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        ObjectNode checkpoint = readObject(verifiedRegularPath(targetRoot, checkpointPath, "local raw replay existing target checkpoint"), "local raw replay existing target checkpoint");
        assertOwnHash(checkpoint, DATA_V5.get("checkpoint"), "local raw replay existing target checkpoint");
        if (!text(plan, "content_sha256").equals(text(checkpoint, "plan_sha256"))) throw failure("local raw replay existing target checkpoint is bound to a different frozen plan");
        if (!javaProducerCodeSha256().equals(text(checkpoint, "producer_code_sha256")) || !DATA_V5_COVERAGE_RULES_SHA256.equals(text(checkpoint, "coverage_rules_sha256"))) throw failure("local raw replay existing target checkpoint has stale producer or coverage-rules hashes");
        List<String> completed = sortedFieldNames(checkpoint.path("completed")), lineageKeys = sortedFieldNames(checkpoint.path("capture_lineage"));
        if (!completed.equals(lineageKeys)) throw failure("local raw replay existing target checkpoint capture lineage inventory is missing, extra, or mismatched");
        Map<String, ObjectNode> planByKey = objects(plan.path("series")).stream().collect(java.util.stream.Collectors.toMap(StrategyResearchDataV5::seriesKey, Function.identity())); Map<String, ObjectNode> result = new HashMap<>();
        for (String id : completed) {
            ObjectNode series = planByKey.get(id); JsonNode value = checkpoint.path("completed").get(id);
            if (series == null || value == null || !value.isObject()) throw failure("local raw replay existing target capture is not declared by the frozen plan: " + id);
            ObjectNode capture = (ObjectNode) value; if (capture.path("unavailable").asBoolean(false) || !hash(series).equals(text(capture, "series_sha256"))) throw failure("local raw replay existing target capture is stale: " + id);
            verifyCaptureCustody(capture, targetRoot, series, text(plan, "content_sha256")); ObjectNode actual = inspectCaptureLineage(capture, targetRoot);
            if (!stable(actual).equals(stable(checkpoint.path("capture_lineage").path(id)))) throw failure("local raw replay existing target capture lineage is stale or forged: " + id); result.put(id, capture);
        }
        return Map.copyOf(result);
    }

    private static void replayWriteJson(Path root, String relative, ObjectNode value, String label) {
        writeContentAddressed(root, relative, prettyBytes(value), label);
    }

    public static ObjectNode replayAuthoritativeStagingFromRaw(ObjectNode options) {
        ObjectNode plan = requiredObject(options, "plan"), source = requiredObject(options, "sourceCheckpoint"); validatePlan(plan);
        assertOwnHash(source, DATA_V5.get("checkpoint"), "local raw replay source checkpoint");
        if (options.hasNonNull("expectedSourceCheckpointSha256") && !text(options, "expectedSourceCheckpointSha256").equals(text(source, "content_sha256"))) throw failure("local raw replay source checkpoint predecessor hash mismatch");
        if (!text(source, "plan_sha256").equals(text(plan, "content_sha256"))) throw failure("local raw replay source checkpoint is bound to a different frozen plan");
        if (options.hasNonNull("sourceRootReference") && !text(options, "sourceRootReference").equals(text(source, "root_reference"))) throw failure("local raw replay source root reference differs from the checkpoint");
        Path sourceRoot = requiredPath(options, "sourceRoot"), targetRoot = requiredPath(options, "targetRoot");
        if (sourceRoot.equals(targetRoot)) throw failure("local raw replay requires distinct source and target roots");
        try { Files.createDirectories(targetRoot); } catch (IOException error) { throw failure("local raw replay target root cannot be created: " + error.getMessage()); }
        for (var entry : Map.of("source", sourceRoot, "target", targetRoot).entrySet()) if (Files.isSymbolicLink(entry.getValue()) || !Files.isDirectory(entry.getValue(), LinkOption.NOFOLLOW_LINKS)) throw failure("local raw replay " + entry.getKey() + " root is not a regular directory");
        String checkpointPath = textOr(options.get("checkpointPath"), "checkpoint.json"), manifestPath = textOr(options.get("manifestPath"), "acquisition-replay.json");
        PathConfinement.repositoryRelativePath(checkpointPath, "local raw replay checkpointPath"); PathConfinement.repositoryRelativePath(manifestPath, "local raw replay manifestPath");
        Map<String, ObjectNode> existing = replayExistingTargetCaptures(targetRoot, checkpointPath, plan);
        ObjectNode sourceCompleted = source.path("completed").isObject() ? (ObjectNode) source.path("completed") : object(); ObjectNode sourceLineage = source.path("capture_lineage").isObject() ? (ObjectNode) source.path("capture_lineage") : object();
        List<String> completedKeys = sortedFieldNames(sourceCompleted); if (!completedKeys.equals(sortedFieldNames(sourceLineage))) throw failure("local raw replay source checkpoint capture lineage inventory is missing, extra, or mismatched");
        Map<String, ObjectNode> planByKey = objects(plan.path("series")).stream().collect(java.util.stream.Collectors.toMap(StrategyResearchDataV5::seriesKey, Function.identity()));
        for (String id : completedKeys) if (!planByKey.containsKey(id)) throw failure("local raw replay capture is not declared by the frozen plan: " + id);
        ObjectNode replayedByKey = object(), lineageByKey = object(), sourceLineageSummary = object(); boolean recoverMetrics = !options.has("recoverAuxiliaryMetrics") || options.path("recoverAuxiliaryMetrics").asBoolean();
        for (String id : completedKeys) {
            JsonNode sourceValue = sourceCompleted.get(id); if (sourceValue == null || !sourceValue.isObject()) continue; ObjectNode capture = (ObjectNode) sourceValue, series = planByKey.get(id);
            if (capture.path("unavailable").asBoolean(false)) continue; if (!hash(series).equals(text(capture, "series_sha256"))) throw failure("local raw replay capture series binding is stale: " + id);
            if ("metrics_events".equals(text(series, "series_type")) && (!recoverMetrics || !capture.path("coverage").path("complete").asBoolean(false))) continue;
            verifiedRegularPath(sourceRoot, text(capture.path("partition"), "path"), "local raw replay source partition"); if (capture.path("mark_partition").isObject()) verifiedRegularPath(sourceRoot, text(capture.path("mark_partition"), "path"), "local raw replay source mark partition");
            verifyCaptureCustody(capture, sourceRoot, series, text(plan, "content_sha256")); ObjectNode actualLineage = inspectCaptureLineage(capture, sourceRoot);
            if (!stable(actualLineage).equals(stable(sourceLineage.path(id)))) throw failure("local raw replay source capture lineage is stale or forged: " + id);
            ReplayCaptureResult replayed = replayCaptureFromRaw(capture, series, plan, sourceRoot, targetRoot, existing.get(id)); replayedByKey.set(id, replayed.capture()); lineageByKey.set(id, replayed.lineage());
            ObjectNode sourceHashes = object(); putNullable(sourceHashes, "producer_code_sha256", replayed.sourceProducerSha256()); putNullable(sourceHashes, "adapter_code_sha256", replayed.sourceAdapterSha256()); sourceLineageSummary.set(id, sourceHashes);
        }
        ArrayNode auxiliaryMetrics = array(); List<String> auxiliaryLimitations = new ArrayList<>();
        if (recoverMetrics) for (ObjectNode series : objects(plan.path("series"))) {
            if (!"metrics_events".equals(text(series, "series_type"))) continue; String id = seriesKey(series);
            if (replayedByKey.path(id).isObject() && !replayedByKey.path(id).path("unavailable").asBoolean(false)) continue;
            String auxiliaryPath = auxiliaryMetricsCheckpointPath(series); Path candidate = sourceRoot.resolve(auxiliaryPath).normalize();
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
            AuxiliaryReplayResult replayed = replayAuxiliaryMetricsFromRaw(series, plan, sourceRoot, targetRoot, existing.get(id));
            if (replayed.partial()) {
                String limitation = "PARTIAL_CHECKPOINT_REPLAYED_FOR_NETWORK_RESUME:saved=" + replayed.savedCount() + ":remaining=" + replayed.remainingCount();
                auxiliaryMetrics.add(object().put("series", id).put("checkpoint_path", replayed.checkpointPath()).put("source_checkpoint_sha256", replayed.sourceCheckpointSha256())
                        .put("raw_verified_count", replayed.rawVerifiedCount()).put("saved_count", replayed.savedCount()).put("remaining_count", replayed.remainingCount())
                        .put("status", "PARTIAL_CHECKPOINT_REPLAYED_FOR_NETWORK_RESUME").put("limitation", limitation));
                auxiliaryLimitations.add(limitation + ":" + id + ":source=" + replayed.sourceCheckpointSha256());
            } else {
                boolean unavailable = replayed.capture().path("unavailable").asBoolean(false); ObjectNode row = object().put("series", id).put("checkpoint_path", replayed.checkpointPath())
                        .put("source_checkpoint_sha256", replayed.sourceCheckpointSha256()).put("raw_verified_count", replayed.rawVerifiedCount()).put("status", unavailable ? "UNAVAILABLE" : "REPLAYED");
                putNullable(row, "limitation", unavailable ? text(replayed.capture().path("coverage"), "reason") : null); auxiliaryMetrics.add(row);
                if (!unavailable) { replayedByKey.set(id, replayed.capture()); lineageByKey.set(id, replayed.lineage()); }
            }
        }
        ArrayNode captures = array(); for (ObjectNode series : objects(plan.path("series"))) captures.add(replayedByKey.path(seriesKey(series)).isObject() ? replayedByKey.path(seriesKey(series)).deepCopy() : replayUnavailableCapture(series));
        List<ObjectNode> all = objects(captures), required = all.stream().filter(value -> !value.has("required") || value.path("required").asBoolean()).toList(), optional = all.stream().filter(value -> value.has("required") && !value.path("required").asBoolean()).toList();
        boolean base = !required.isEmpty() && required.stream().allMatch(StrategyResearchDataV5::captureComplete), declared = !all.isEmpty() && all.stream().allMatch(StrategyResearchDataV5::captureComplete);
        String rootReference = portableReference(targetRoot, text(options, "targetRootReference")); ObjectNode checkpoint = object().put("schema", DATA_V5.get("checkpoint")).put("version", 1).put("plan_sha256", text(plan, "content_sha256")).put("root_reference", rootReference).putNull("prior_checkpoint_sha256")
                .put("producer_code_sha256", javaProducerCodeSha256()).put("coverage_rules_sha256", DATA_V5_COVERAGE_RULES_SHA256).put("fixture_only", false).put("provenance", "LOCAL_RAW_REPLAY"); checkpoint.set("capture_lineage", lineageByKey); checkpoint.set("completed", replayedByKey); checkpoint = withHash(checkpoint); replayWriteJson(targetRoot, checkpointPath, checkpoint, "local raw replay checkpoint");
        List<String> unavailableRequired = required.stream().filter(value -> !captureComplete(value)).map(StrategyResearchDataV5::seriesKey).toList(), unavailableOptional = optional.stream().filter(value -> !captureComplete(value)).map(StrategyResearchDataV5::seriesKey).toList(); TreeSet<String> limitations = new TreeSet<>(); unavailableRequired.forEach(value -> limitations.add("REQUIRED_SERIES_UNAVAILABLE:" + value)); unavailableOptional.forEach(value -> limitations.add("OPTIONAL_SERIES_UNAVAILABLE:" + value)); limitations.addAll(auxiliaryLimitations); limitations.add("LOCAL_RAW_REPLAY_NO_NETWORK");
        ObjectNode acquisition = object().put("schema", DATA_V5.get("acquisition")).put("version", 1).put("status", base ? "STAGING_COMPLETE" : "STAGING_PARTIAL").put("plan_sha256", text(plan, "content_sha256")).put("root_reference", rootReference)
                .put("staging_format", "JSONL").put("storage_role", "STAGING").put("authoritative", false).put("checkpoint_path", checkpointPath).put("checkpoint_sha256", text(checkpoint, "content_sha256"))
                .put("base_complete", base).put("declared_complete", declared).put("full_plan_complete", declared).put("completion_scope", declared ? "ALL_DECLARED" : base ? "BASE_ONLY" : "NONE")
                .put("required_series_count", required.size()).put("required_complete_count", required.stream().filter(StrategyResearchDataV5::captureComplete).count()).put("optional_series_count", optional.size()).put("optional_complete_count", optional.stream().filter(StrategyResearchDataV5::captureComplete).count()).put("optional_complete", optional.stream().allMatch(StrategyResearchDataV5::captureComplete));
        putNullable(acquisition, "declared_requirements_sha256", plan.hasNonNull("timeframe_requirements_sha256") ? text(plan, "timeframe_requirements_sha256") : null); acquisition.set("captures", captures);
        acquisition.set("source_receipts", strings(all.stream().flatMap(value -> objects(value.path("source_receipts")).stream()).map(value -> text(value, "path")).distinct().sorted().toList()));
        acquisition.set("source_receipt_sha256", strings(all.stream().flatMap(value -> objects(value.path("source_receipts")).stream()).map(value -> textOr(first(value, "content_sha256", "sha256"), "")).filter(value -> !value.isEmpty()).distinct().sorted().toList()));
        acquisition.set("source_receipt_byte_sha256", strings(all.stream().flatMap(value -> objects(value.path("source_receipts")).stream()).flatMap(value -> hashInventory(value.get("byte_sha256")).stream()).distinct().sorted().toList())); acquisition.set("auxiliary_metrics", auxiliaryMetrics); acquisition.set("limitations", strings(new ArrayList<>(limitations))); acquisition.set("conversion", conversionContract()); acquisition = withHash(acquisition);
        ObjectNode verify = object().set("manifest", acquisition); verify.set("plan", plan); verify.put("root", targetRoot.toString()).put("planSha256", text(plan, "content_sha256")); verifyAuthoritativeStaging(verify); replayWriteJson(targetRoot, manifestPath, acquisition, "local raw replay acquisition manifest");
        ObjectNode result = object(); result.set("checkpoint", checkpoint); result.set("acquisition", acquisition); result.put("replayed_count", sortedFieldNames(replayedByKey).size()).put("retained_count", completedKeys.size()).put("target_root", targetRoot.toString()).put("target_root_reference", rootReference); result.set("source_lineage", sourceLineageSummary); result.set("auxiliary_metrics", auxiliaryMetrics); return result;
    }

    public static ObjectNode hydrateOpportunityWindowsV5(ObjectNode options) {
        if (options != null && options.path("fixtureCaptures").isArray()) return hydrateFixtureOpportunityWindows(options);
        return hydrateOpportunityWindowsV5(options, new PublicDataAdapters.JdkInjectableHttpClient());
    }

    /** Injectable production seam used by the isolated oracle; this overload
     * is package-private so the public 66-binding surface remains unchanged. */
    static ObjectNode hydrateOpportunityWindowsV5(ObjectNode options, PublicDataAdapters.InjectableHttpClient transport) {
        if (options != null && options.path("fixtureCaptures").isArray()) return hydrateFixtureOpportunityWindows(options);
        String planSha = requireSha(text(options, "planSha256"), "plan_sha256"), candidateSha = requireSha(text(options, "candidateSetSha256"), "candidate_set_sha256");
        ObjectNode envelope = requiredObject(options, "opportunityEnvelope"); validateOpportunityEnvelopeV5(envelope, planSha, candidateSha);
        Path root = requiredPath(options, "outputRoot"); if (transport == null) throw failure("opportunity hydration transport is required");
        try { Files.createDirectories(root); } catch (IOException error) { throw failure("hydration output root cannot be created: " + error.getMessage()); }
        boolean fixture = options.path("fixtureOnly").asBoolean(false); String capturedAt = fixture && options.hasNonNull("capturedAt") ? iso(time(options.get("capturedAt"))) : iso(System.currentTimeMillis());
        List<ObjectNode> windows = mergeHydrationWindows(objects(envelope.path("windows"))); if (windows.isEmpty()) throw failure("opportunity envelope has no windows");
        String checkpointPath = textOr(options.get("checkpointPath"), "hydration-checkpoint.json"); String expectedPrior = options.hasNonNull("expectedCheckpointSha256") ? text(options, "expectedCheckpointSha256") : null;
        long lockStaleMs = nonNegativeLong(options.get("lockStaleMs"), 6L * 60 * 60 * 1_000, "lockStaleMs");
        HydrationLock hydrationLock = acquireHydrationLock(writablePath(root, checkpointPath + ".lock", "hydration run lock"), lockStaleMs);
        try {
        ObjectNode existingCheckpoint = loadHydrationCheckpoint(root, checkpointPath, portableReference(root, text(options, "outputRootReference")), planSha, text(envelope, "content_sha256"), candidateSha, envelope.path("max_lifecycle_ms").asLong(), fixture);
        String checkpointPrior = existingCheckpoint == null ? null : text(existingCheckpoint, "content_sha256");
        if (expectedPrior != null && !Objects.equals(expectedPrior, checkpointPrior)) throw failure("checkpoint compare-and-swap predecessor hash mismatch");
        if (expectedPrior == null) expectedPrior = checkpointPrior;
        ObjectNode completed = existingCheckpoint != null && existingCheckpoint.path("completed").isObject() ? (ObjectNode) existingCheckpoint.path("completed").deepCopy() : object(); ObjectNode checkpoint = existingCheckpoint; List<ObjectNode> captures = new ArrayList<>(); long maxPages = positiveInt(options.get("maxPages"), 1_000, "maxPages"); int maxRows = positiveInt(options.get("maxRows"), 50_000_000, "maxRows"); long rateLimit = nonNegativeLong(options.get("rateLimitMs"), 0, "rateLimitMs");
        for (ObjectNode window : windows) {
            String id = hash(object().put("envelope_sha256", text(envelope, "content_sha256")).put("asset", text(window, "asset")).put("instrument", text(window, "instrument")).put("symbol", text(window, "symbol")).put("execution_start", text(window, "execution_start")).put("execution_end", text(window, "execution_end")));
            long start = time(window.get("execution_start")), end = time(window.get("execution_end"));
            ObjectNode saved = completed.path(id).isObject() ? (ObjectNode) completed.path(id).deepCopy() : null;
            if (saved != null && saved.path("coverage").path("complete").asBoolean(false) && text(saved, "envelope_sha256").equals(text(envelope, "content_sha256")) && saved.path("partition").isObject()) {
                try {
                    verifyCaptureCustody(saved, root, null, planSha);
                    List<ObjectNode> savedRows = readJsonl(verifiedRegularPath(root, text(saved.path("partition"), "path"), "hydration checkpoint partition"));
                    ObjectNode savedCoverage = validateHydratedRowsV5(savedRows, window, capturedAt);
                    if (!savedCoverage.path("complete").asBoolean(false)) throw failure("completed hydration checkpoint coverage changed: " + text(savedCoverage, "reason"));
                    if (saved.path("mark_partition").isObject()) {
                        List<ObjectNode> markRows = readJsonl(verifiedRegularPath(root, text(saved.path("mark_partition"), "path"), "hydration checkpoint mark partition"));
                        ObjectNode markCoverage = validateHydratedRowsV5(markRows, window, capturedAt);
                        if (!markCoverage.path("complete").asBoolean(false)) throw failure("completed hydration mark checkpoint coverage changed: " + text(markCoverage, "reason"));
                    }
                    captures.add(saved); continue;
                } catch (RuntimeException ignored) { completed.remove(id); }
            }
            PublicDataAdapters.HttpOptions http = new PublicDataAdapters.HttpOptions(transport, fixture && options.hasNonNull("capturedAt") ? text(options, "capturedAt") : null, fixture, 3, 250);
            PublicDataAdapters.BackfillResult result;
            if ("BINANCE_USDM_DATED_FUTURE".equals(text(window, "instrument"))) {
                result = PublicDataAdapters.backfillBinanceDatedKlineArchives(new PublicDataAdapters.ArchiveBackfillOptions(text(window, "asset"), text(window, "symbol"), "1m", start, end, Math.toIntExact(Math.min(Integer.MAX_VALUE, maxPages * 31)), http, root, null, null, 2, false));
            } else {
                result = PublicDataAdapters.backfillBinanceOhlc(new PublicDataAdapters.OhlcOptions(text(window, "asset"), text(window, "symbol"), start, end, "1m", 1_000, !"BINANCE_SPOT".equals(text(window, "instrument")), http), start, end, 1_000, Math.toIntExact(maxPages), maxRows, rateLimit);
            }
            ObjectNode priceSeries = object().put("asset", text(window, "asset")).put("venue", "BINANCE").put("instrument", text(window, "instrument")).put("symbol", text(window, "symbol")).put("interval", "1m").put("series_type", "signal_bars").put("series_role", "PRICE");
            List<ObjectNode> rows = bindHydrationRowsToSeries(result.rows().stream().filter(row -> rowTime(row) >= start && rowTime(row) <= end).toList(), priceSeries, null); ObjectNode coverage = validateHydratedRowsV5(rows, window, result.capturedAt() == null ? capturedAt : result.capturedAt());
            ObjectNode partition = writeJsonlPartition(root, "opportunity-1m", text(window, "asset") + "-" + text(window, "instrument") + "-" + text(window, "symbol") + "-" + id, rows);
            ArrayNode rawReceipts = array(); for (PublicDataAdapters.RawResponse raw : result.rawResponses()) rawReceipts.add(persistAdapterRaw(root, raw, priceSeries));
            ObjectNode receiptPayload = object().put("status", "PUBLIC_OBSERVED").put("plan_sha256", planSha).put("envelope_sha256", text(envelope, "content_sha256")).put("candidate_set_sha256", candidateSha).put("window_sha256", id).put("captured_at", result.capturedAt() == null ? capturedAt : result.capturedAt()).put("producer_code_sha256", javaProducerCodeSha256()).put("adapter_code_sha256", javaAdapterCodeSha256());
            ObjectNode adapterReference = persistJavaAdapterReference(root, "opportunity hydration public data adapter"); receiptPayload.set("request_metadata", object().set("adapter_code_reference", adapterReference));
            receiptPayload.set("window", object().put("asset", text(window, "asset")).put("instrument", text(window, "instrument")).put("symbol", text(window, "symbol")).put("execution_start", text(window, "execution_start")).put("execution_end", text(window, "execution_end"))); receiptPayload.set("response_sha256", strings(result.responseSha256())); receiptPayload.set("source_byte_sha256", strings(objects(rawReceipts).stream().map(row -> text(row, "byte_sha256")).toList())); receiptPayload.set("raw_receipts", rawReceipts); receiptPayload.set("pagination", result.receipt() == null ? array() : result.receipt().path("pages").deepCopy()); receiptPayload.set("coverage", coverage); ObjectNode receipt = writeNormalizedSourceReceipt(root, receiptPayload);
            ObjectNode capture = object().put("asset", text(window, "asset")).put("instrument", text(window, "instrument")).put("symbol", text(window, "symbol")).put("execution_start", text(window, "execution_start")).put("execution_end", text(window, "execution_end")).put("envelope_sha256", text(envelope, "content_sha256")).put("candidate_set_sha256", candidateSha).put("max_lifecycle_ms", envelope.path("max_lifecycle_ms").asLong()).put("window_sha256", id); capture.set("source_window_ids", window.path("source_window_ids").deepCopy()); capture.set("partition", partition); capture.set("source_receipts", array().add(receipt)); capture.set("source_receipt_sha256", array().add(text(receipt, "sha256"))); capture.set("coverage", coverage); capture.set("mark_source_receipts", array()); capture.putNull("mark_partition").putNull("mark_coverage");
            if ("BINANCE_SPOT".equals(text(window, "instrument"))) { verifyCaptureCustody(capture, root, null, planSha); }
            else {
                PublicDataAdapters.BackfillResult markResult = PublicDataAdapters.backfillBinanceMarkPriceOhlc(new PublicDataAdapters.OhlcOptions(text(window, "asset"), text(window, "symbol"), start, end, "1m", 1_000, true, http), start, end, 1_000, Math.toIntExact(maxPages), maxRows, rateLimit);
                ObjectNode markSeries = object().put("asset", text(window, "asset")).put("venue", "BINANCE").put("instrument", "BINANCE_USDM_PERPETUAL_MARK").put("symbol", text(window, "symbol")).put("interval", "1m").put("series_type", "mark_bars").put("series_role", "MARK").put("expected_step_ms", ONE_MINUTE); List<ObjectNode> marks = bindHydrationRowsToSeries(markResult.rows().stream().filter(row -> rowTime(row) >= start && rowTime(row) <= end).toList(), markSeries, id); ObjectNode markCoverage = validateHydratedRowsV5(marks, window, markResult.capturedAt() == null ? capturedAt : markResult.capturedAt()); if (!markCoverage.path("complete").asBoolean(false)) throw failure("derivative opportunity hydration has incomplete mark-price coverage"); ObjectNode markPartition = writeJsonlPartition(root, "opportunity-1m-mark", text(window, "asset") + "-" + text(window, "instrument") + "-" + text(window, "symbol") + "-" + id, marks); ArrayNode markRaw = array(); for (PublicDataAdapters.RawResponse raw : markResult.rawResponses()) markRaw.add(persistAdapterRaw(root, raw, markSeries)); ObjectNode markPayload = receiptPayload.deepCopy().put("captured_at", markResult.capturedAt() == null ? capturedAt : markResult.capturedAt()); markPayload.put("window_sha256", id); markPayload.set("window", object().put("asset", text(window, "asset")).put("instrument", "BINANCE_USDM_PERPETUAL_MARK").put("symbol", text(window, "symbol")).put("execution_start", text(window, "execution_start")).put("execution_end", text(window, "execution_end"))); markPayload.set("response_sha256", strings(markResult.responseSha256())); markPayload.set("source_byte_sha256", strings(objects(markRaw).stream().map(row -> text(row, "byte_sha256")).toList())); markPayload.set("raw_receipts", markRaw); markPayload.set("pagination", markResult.receipt() == null ? array() : markResult.receipt().path("pages").deepCopy()); markPayload.set("coverage", markCoverage); ObjectNode markReceipt = writeNormalizedSourceReceipt(root, markPayload); capture.set("mark_partition", markPartition); capture.set("mark_source_receipts", array().add(markReceipt)); capture.set("mark_source_receipt_sha256", array().add(text(markReceipt, "sha256"))); capture.set("mark_coverage", markCoverage); verifyCaptureCustody(capture, root, null, planSha);
            }
            completed.set(id, capture); captures.add(capture);
            ObjectNode nextCheckpoint = object().put("schema", DATA_V5.get("checkpoint")).put("version", 1).put("plan_sha256", planSha).put("root_reference", portableReference(root, text(options, "outputRootReference"))).put("envelope_sha256", text(envelope, "content_sha256")).put("candidate_set_sha256", candidateSha).put("max_lifecycle_ms", envelope.path("max_lifecycle_ms").asLong()).put("producer_code_sha256", javaProducerCodeSha256()).put("coverage_rules_sha256", DATA_V5_COVERAGE_RULES_SHA256).put("fixture_only", fixture).put("provenance", fixture ? "FIXTURE_INJECTED" : "PUBLIC_ADAPTER_RECOMPUTED");
            putNullable(nextCheckpoint, "prior_checkpoint_sha256", checkpointPrior); nextCheckpoint.set("completed", completed.deepCopy()); nextCheckpoint = withHash(nextCheckpoint);
            writeCheckpointCasUnderHydrationLock(root, checkpointPath, nextCheckpoint, checkpointPrior); checkpointPrior = text(nextCheckpoint, "content_sha256"); checkpoint = nextCheckpoint;
        }
        if (checkpoint == null) {
            checkpoint = object().put("schema", DATA_V5.get("checkpoint")).put("version", 1).put("plan_sha256", planSha).put("root_reference", portableReference(root, text(options, "outputRootReference"))).put("envelope_sha256", text(envelope, "content_sha256")).put("candidate_set_sha256", candidateSha).put("max_lifecycle_ms", envelope.path("max_lifecycle_ms").asLong()).put("producer_code_sha256", javaProducerCodeSha256()).put("coverage_rules_sha256", DATA_V5_COVERAGE_RULES_SHA256).putNull("prior_checkpoint_sha256").put("fixture_only", fixture).put("provenance", fixture ? "FIXTURE_INJECTED" : "PUBLIC_ADAPTER_RECOMPUTED"); checkpoint.set("completed", completed); checkpoint = withHash(checkpoint); writeCheckpointCasUnderHydrationLock(root, checkpointPath, checkpoint, null);
        }
        boolean complete = captures.size() == windows.size() && captures.stream().allMatch(row -> row.path("coverage").path("complete").asBoolean(false)); ObjectNode result = object().put("schema", DATA_V5.get("hydration")).put("version", 1).put("status", complete ? "STAGING_COMPLETE" : "STAGING_PARTIAL").put("plan_sha256", planSha).put("candidate_set_sha256", candidateSha).put("envelope_sha256", text(envelope, "content_sha256")).put("max_lifecycle_ms", envelope.path("max_lifecycle_ms").asLong()).put("lifecycle_timeframe", text(envelope, "lifecycle_timeframe")).put("root_reference", portableReference(root, text(options, "outputRootReference"))).put("staging_format", "JSONL").put("storage_role", "STAGING").put("authoritative", false).put("fixture_only", fixture).put("provenance", fixture ? "FIXTURE_INJECTED" : "PUBLIC_ADAPTER_RECOMPUTED").put("hydrated_before_outcomes", complete).put("captured_at", fixture && options.hasNonNull("capturedAt") ? iso(time(options.get("capturedAt"))) : (captures.isEmpty() ? capturedAt : latestCaptureFromCaptures(captures, capturedAt))).put("merged_window_count", windows.size()).put("checkpoint_path", checkpointPath).put("checkpoint_sha256", text(checkpoint, "content_sha256"));
        ArrayNode normalizedWindows = array(); for (ObjectNode window : windows) { ObjectNode normalized = object().put("asset", text(window, "asset")).put("instrument", text(window, "instrument")).put("symbol", text(window, "symbol")).put("execution_start", text(window, "execution_start")).put("execution_end", text(window, "execution_end")); normalized.set("source_window_ids", window.path("source_window_ids").deepCopy()); normalized.put("max_lifecycle_ms", envelope.path("max_lifecycle_ms").asLong()).put("lifecycle_timeframe", text(envelope, "lifecycle_timeframe")); normalizedWindows.add(normalized); } result.set("windows", normalizedWindows); result.set("captures", array(captures)); List<ObjectNode> receiptSummaries = captures.stream().flatMap(row -> concatNodes(row.path("source_receipts"), row.path("mark_source_receipts")).stream()).toList(); result.set("source_receipts", strings(receiptSummaries.stream().map(row -> text(row, "path")).distinct().sorted().toList())); result.set("source_receipt_sha256", strings(receiptSummaries.stream().map(row -> textOr(first(row, "content_sha256", "sha256"), "")).filter(value -> !value.isEmpty()).distinct().sorted().toList())); result.set("source_receipt_byte_sha256", strings(receiptSummaries.stream().flatMap(row -> hashInventory(row.get("byte_sha256")).stream()).distinct().sorted().toList())); result.set("limitations", complete ? array() : array().add("ONE_MINUTE_HYDRATION_INCOMPLETE")); ObjectNode verify = object().set("manifest", withHash(result)); verify.put("root", root.toString()).put("planSha256", planSha).put("envelopeSha256", text(envelope, "content_sha256")).put("candidateSetSha256", candidateSha).put("allowFixture", fixture); verifyAuthoritativeStaging(verify); return (ObjectNode) verify.path("manifest");
        } finally { releaseHydrationLock(hydrationLock); }
    }

    private static ObjectNode hydrateFixtureOpportunityWindows(ObjectNode options) {
        String planSha = requireSha(text(options, "planSha256"), "plan_sha256"), candidateSha = requireSha(text(options, "candidateSetSha256"), "candidate_set_sha256"); ObjectNode envelope = requiredObject(options, "opportunityEnvelope");
        validateOpportunityEnvelopeV5(envelope, planSha, candidateSha); Path root = requiredPath(options, "outputRoot");
        try { Files.createDirectories(root); } catch (IOException error) { throw failure("hydration output root cannot be created: " + error.getMessage()); }
        List<ObjectNode> captures = objects(options.path("fixtureCaptures")); for (ObjectNode capture : captures) verifyCaptureCustody(capture, root, null, planSha);
        boolean complete = !captures.isEmpty() && captures.stream().allMatch(StrategyResearchDataV5::captureComplete); ObjectNode value = object().put("schema", DATA_V5.get("hydration")).put("version", 1).put("status", complete ? "STAGING_COMPLETE" : "STAGING_PARTIAL").put("plan_sha256", planSha).put("candidate_set_sha256", candidateSha).put("envelope_sha256", text(envelope, "content_sha256")).put("max_lifecycle_ms", envelope.path("max_lifecycle_ms").asLong()).put("lifecycle_timeframe", text(envelope, "lifecycle_timeframe")).put("root_reference", portableReference(root, text(options, "outputRootReference"))).put("staging_format", "JSONL").put("storage_role", "STAGING").put("authoritative", false).put("fixture_only", true).put("provenance", "FIXTURE_INJECTED").put("hydrated_before_outcomes", complete).put("captured_at", textOr(options.get("capturedAt"), iso(System.currentTimeMillis()))); value.set("windows", envelope.path("windows").deepCopy()); value.set("captures", array(captures)); value.set("source_receipts", strings(captures.stream().flatMap(row -> concatNodes(row.path("source_receipts"), row.path("mark_source_receipts")).stream()).map(row -> text(row, "path")).distinct().sorted().toList())); value.set("source_receipt_sha256", strings(captures.stream().flatMap(row -> concatNodes(row.path("source_receipts"), row.path("mark_source_receipts")).stream()).map(row -> textOr(first(row, "content_sha256", "sha256"), "")).distinct().sorted().toList())); value.set("source_receipt_byte_sha256", strings(captures.stream().flatMap(row -> concatNodes(row.path("source_receipts"), row.path("mark_source_receipts")).stream()).flatMap(row -> hashInventory(row.get("byte_sha256")).stream()).distinct().sorted().toList())); value.set("limitations", complete ? array() : array().add("ONE_MINUTE_HYDRATION_INCOMPLETE")); return withHash(value);
    }

    private record MergedHydrationWindow(ObjectNode value, long start, long end, List<String> sourceIds) { }

    private static void validateOpportunityEnvelopeV5(ObjectNode envelope, String planSha, String candidateSha) {
        assertOwnHash(envelope, "strategy-v5-opportunity-envelope/1", "opportunity envelope");
        if (!"FROZEN".equals(text(envelope, "status")) || !planSha.equals(text(envelope, "plan_sha256")) || !candidateSha.equals(text(envelope, "candidate_set_sha256"))) throw failure("opportunity envelope is not bound to the requested plan/candidate set");
        long max = envelope.path("max_lifecycle_ms").asLong(0); if (max <= 0 || !envelope.path("max_lifecycle_ms").isIntegralNumber()) throw failure("opportunity envelope maximum lifecycle is invalid");
        List<ObjectNode> windows = objects(envelope.path("windows")); if (windows.isEmpty()) throw failure("frozen opportunity envelope has no windows");
        for (ObjectNode window : windows) { requireAsset(text(window, "asset")); if (text(window, "instrument").isEmpty() || text(window, "symbol").isEmpty() || text(window, "execution_start").isEmpty() || text(window, "execution_end").isEmpty()) throw failure("frozen opportunity envelope window identity is incomplete"); long start = time(window.get("execution_start")), end = time(window.get("execution_end")); if (end < start || end - start > max || window.path("max_lifecycle_ms").asLong(0) != max || !text(window, "lifecycle_timeframe").equals(text(envelope, "lifecycle_timeframe"))) throw failure("frozen opportunity envelope window exceeds its maximum lifecycle"); }
    }

    private static List<ObjectNode> mergeHydrationWindows(List<ObjectNode> input) {
        Map<String, List<MergedHydrationWindow>> grouped = new HashMap<>();
        for (ObjectNode source : input) { ObjectNode row = source.deepCopy(); String asset = requireAsset(text(row, "asset")).toLowerCase(Locale.ROOT); String instrument = textOr(row.get("instrument"), "BINANCE_SPOT"); String symbol = textOr(row.get("symbol"), asset.toUpperCase(Locale.ROOT) + "USDT").toUpperCase(Locale.ROOT); long start = time(first(row, "execution_start", "start_at")), end = time(first(row, "execution_end", "end_at")); if (end < start) throw failure("opportunity window end precedes start"); ObjectNode hashInput = row.deepCopy().put("start", start).put("end", end); List<String> ids = List.of(textOr(row.get("window_id"), hash(hashInput))); row.put("asset", asset).put("instrument", instrument).put("symbol", symbol); grouped.computeIfAbsent(asset + "|" + instrument + "|" + symbol, ignored -> new ArrayList<>()).add(new MergedHydrationWindow(row, start, end, ids)); }
        List<MergedHydrationWindow> merged = new ArrayList<>();
        for (List<MergedHydrationWindow> rows : grouped.values()) { rows.sort(Comparator.comparingLong(MergedHydrationWindow::start)); MergedHydrationWindow current = null; for (MergedHydrationWindow row : rows) { if (current == null || row.start() > current.end() + ONE_MINUTE) { if (current != null) merged.add(current); current = row; } else { ObjectNode combined = current.value().deepCopy(); List<String> ids = new ArrayList<>(current.sourceIds()); ids.addAll(row.sourceIds()); current = new MergedHydrationWindow(combined, current.start(), Math.max(current.end(), row.end()), ids.stream().distinct().sorted().toList()); } } if (current != null) merged.add(current); }
        merged.sort(Comparator.comparing((MergedHydrationWindow row) -> text(row.value(), "asset")).thenComparing(row -> text(row.value(), "instrument")).thenComparing(row -> text(row.value(), "symbol")).thenComparingLong(MergedHydrationWindow::start));
        List<ObjectNode> result = new ArrayList<>(); for (MergedHydrationWindow row : merged) result.add(object().put("asset", text(row.value(), "asset")).put("instrument", text(row.value(), "instrument")).put("symbol", text(row.value(), "symbol")).put("execution_start", iso(Math.floorDiv(row.start(), ONE_MINUTE) * ONE_MINUTE)).put("execution_end", iso(Math.floorDiv(row.end(), ONE_MINUTE) * ONE_MINUTE)).set("source_window_ids", strings(row.sourceIds()))); return result;
    }

    private static ObjectNode validateHydratedRowsV5(List<ObjectNode> rows, ObjectNode window, String capturedAt) {
        long start = time(window.get("execution_start")), end = time(window.get("execution_end")); List<ObjectNode> ordered = rows.stream().sorted(Comparator.comparingLong(StrategyResearchDataV5::rowTime)).toList(); List<Long> times = ordered.stream().map(StrategyResearchDataV5::rowTime).toList();
        boolean valid = !times.isEmpty() && times.get(0) == start && times.get(times.size() - 1) == end && new HashSet<>(times).size() == times.size(); for (int index = 0; valid && index < times.size(); index++) if (times.get(index) != start + index * ONE_MINUTE) valid = false; if (!valid) return object().put("complete", false).put("reason", "MISSING_OR_DUPLICATE_ONE_MINUTE_BAR");
        long capture = time(com.fasterxml.jackson.databind.node.TextNode.valueOf(capturedAt)); for (ObjectNode row : ordered) if (rowAvailability(row) > capture || rowAvailability(row) < rowTime(row) + ONE_MINUTE - 1_000) return object().put("complete", false).put("reason", "CURRENT_OR_UNCOMPLETED_ONE_MINUTE_BAR");
        return object().put("complete", true).put("expected_rows", times.size()).put("observed_rows", rows.size()).put("min_event_time", iso(start)).put("max_event_time", iso(end)).put("captured_at", iso(capture));
    }

    private static String latestCaptureFromCaptures(List<ObjectNode> captures, String fallback) { return fallback; }

    private record HydrationLock(Path path, String token) { }

    private static HydrationLock acquireHydrationLock(Path lockPath, long staleMs) {
        if (staleMs < 0) throw failure("hydration lock stale timeout must be non-negative");
        Path target = lockPath.toAbsolutePath().normalize();
        String startedAt = iso(System.currentTimeMillis());
        String token = hash(object().put("path", target.toString()).put("started_at", startedAt)
                .put("thread_id", Thread.currentThread().threadId()));
        ObjectNode body = object().put("schema", "strategy-v5-checkpoint-lock/1")
                .put("pid", ProcessHandle.current().pid()).put("started_at", startedAt).put("token", token);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Path parent = target.getParent();
                if (parent == null) throw failure("hydration lock has no confined parent");
                Files.createDirectories(parent);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
                    throw failure("hydration lock is not a regular file");
                }
                Files.write(target, (JSON.writeValueAsString(body) + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return new HydrationLock(target, token);
            } catch (FileAlreadyExistsException error) {
                if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure("hydration lock is not a regular file");
                }
                boolean stale;
                try {
                    ObjectNode old = readObject(target, "hydration lock");
                    Long oldStarted = parseTimestamp(text(old, "started_at"));
                    long referenceTime = oldStarted == null
                            ? Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS).toMillis()
                            : oldStarted;
                    stale = System.currentTimeMillis() - referenceTime > staleMs;
                } catch (RuntimeException | IOException malformed) {
                    try {
                        stale = System.currentTimeMillis()
                                - Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS).toMillis() > staleMs;
                    } catch (IOException metadataError) {
                        throw failure("hydration lock metadata cannot be verified");
                    }
                }
                if (!stale) throw failure("hydration lock is already held");
                try { Files.delete(target); }
                catch (IOException deleteError) { throw failure("hydration stale-lock recovery raced: " + target); }
            } catch (IOException error) {
                throw failure("hydration lock cannot be acquired: " + error.getMessage());
            }
        }
        throw failure("hydration lock could not be acquired: " + target);
    }

    private static void releaseHydrationLock(HydrationLock lock) {
        if (lock == null || !Files.exists(lock.path(), LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (Files.isSymbolicLink(lock.path()) || !Files.isRegularFile(lock.path(), LinkOption.NOFOLLOW_LINKS)) return;
            ObjectNode value = readObject(lock.path(), "hydration lock");
            if (lock.token().equals(text(value, "token"))) Files.deleteIfExists(lock.path());
        } catch (RuntimeException | IOException ignored) { }
    }

    private static ObjectNode loadHydrationCheckpoint(Path root, String relative, String rootReference,
            String planSha, String envelopeSha, String candidateSha, long maxLifecycle, boolean fixture) {
        Path path = writablePath(root, relative, "hydration checkpoint");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        ObjectNode value = readObject(verifiedRegularPath(root, relative, "hydration checkpoint"), "hydration checkpoint");
        assertOwnHash(value, DATA_V5.get("checkpoint"), "hydration checkpoint");
        if (!planSha.equals(text(value, "plan_sha256")) || !rootReference.equals(text(value, "root_reference"))
                || !envelopeSha.equals(text(value, "envelope_sha256"))
                || !candidateSha.equals(text(value, "candidate_set_sha256"))
                || !javaProducerCodeSha256().equals(text(value, "producer_code_sha256"))
                || !DATA_V5_COVERAGE_RULES_SHA256.equals(text(value, "coverage_rules_sha256"))
                || value.path("max_lifecycle_ms").asLong(-1) != maxLifecycle
                || value.path("fixture_only").asBoolean(false) != fixture
                || !text(value, "provenance").equals(fixture ? "FIXTURE_INJECTED" : "PUBLIC_ADAPTER_RECOMPUTED")) {
            throw failure("hydration checkpoint is bound to a different frozen opportunity envelope or root");
        }
        if (!value.path("completed").isObject()) throw failure("hydration checkpoint completed inventory is invalid");
        return value;
    }

    private static void writeCheckpointCasUnderHydrationLock(Path root, String relative,
            ObjectNode value, String expectedPrior) {
        Path target = writablePath(root, relative, "hydration checkpoint");
        try {
            String actual = null;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                ObjectNode prior = readObject(verifiedRegularPath(root, relative, "hydration checkpoint"), "hydration checkpoint");
                assertOwnHash(prior, DATA_V5.get("checkpoint"), "hydration checkpoint");
                actual = text(prior, "content_sha256");
            }
            if (!Objects.equals(expectedPrior, actual)) throw failure("checkpoint compare-and-swap predecessor hash mismatch");
            byte[] bytes = prettyBytes(value);
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + Thread.currentThread().threadId());
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) { throw failure("hydration checkpoint write failed: " + error.getMessage()); }
    }

    private static long quarterlyExpiry(String symbol) {
        Matcher match = Pattern.compile("^[A-Z]+USDT_(\\d{6})$").matcher(symbol.toUpperCase(Locale.ROOT)); if (!match.matches()) return Long.MIN_VALUE; String value = match.group(1);
        try { int year = 2000 + Integer.parseInt(value.substring(0,2)), month = Integer.parseInt(value.substring(2,4)), day = Integer.parseInt(value.substring(4,6)); return LocalDate.of(year, month, day).atTime(8,0).toInstant(ZoneOffset.UTC).toEpochMilli(); }
        catch (RuntimeException error) { return Long.MIN_VALUE; }
    }
    private static List<String> uniqueSortedTexts(ArrayNode values) { return texts(values).stream().distinct().sorted().toList(); }
    private static void copyConfined(Path source, Path target, String relative, String label) { byte[] bytes = readPhysical(source, relative, label + " source"); writeContentAddressed(target, relative, bytes, label + " target"); }
    private static void writeCheckpointCas(Path root, String relative, ObjectNode value, String expectedPrior) {
        Path target = writablePath(root, relative, "checkpoint"); Path lockPath = writablePath(root, relative + ".lock", "checkpoint lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.tryLock()) {
            if (ignored == null) throw failure("checkpoint lock is already held"); String actual = null;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { ObjectNode prior = readObject(target, "checkpoint"); assertOwnHash(prior, DATA_V5.get("checkpoint"), "checkpoint"); actual = text(prior, "content_sha256"); }
            if (expectedPrior != null && !expectedPrior.equals(actual)) throw failure("checkpoint compare-and-swap mismatch"); byte[] bytes = prettyBytes(value); Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + Thread.currentThread().threadId()); Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException error) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (java.nio.channels.OverlappingFileLockException error) { throw failure("checkpoint lock is already held"); }
        catch (IOException error) { throw failure("checkpoint write failed: " + error.getMessage()); }
        finally { try { Files.deleteIfExists(lockPath); } catch (IOException ignored) { } }
    }

    private static void writeCheckpointCasStrict(Path root, String relative, ObjectNode value, String expectedPrior) {
        Path target = writablePath(root, relative, "checkpoint"), lockPath = writablePath(root, relative + ".lock", "checkpoint lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.tryLock()) {
            if (ignored == null) throw failure("checkpoint lock is already held"); String actual = null;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { ObjectNode prior = readObject(target, "checkpoint"); assertOwnHash(prior, DATA_V5.get("checkpoint"), "checkpoint"); actual = text(prior, "content_sha256"); }
            if (!Objects.equals(expectedPrior, actual)) throw failure("checkpoint compare-and-swap predecessor hash mismatch");
            byte[] bytes = prettyBytes(value); Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + Thread.currentThread().threadId());
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (java.nio.channels.OverlappingFileLockException error) { throw failure("checkpoint lock is already held"); }
        catch (IOException error) { throw failure("checkpoint write failed: " + error.getMessage()); }
        finally { try { Files.deleteIfExists(lockPath); } catch (IOException ignored) { } }
    }

    /* ------------------------------------------------------------------ */
    /* Authoritative feature plane, role production, and spot metadata     */
    /* ------------------------------------------------------------------ */

    public static ObjectNode produceAuthoritativeFeatureSource(ObjectNode options) {
        Path root = requiredPath(options, "root"); ObjectNode plan = requiredObject(options, "plan"), acquisition = requiredObject(options, "acquisition")
                , registry = requiredObject(options, "predictorRegistry"), precommit = requiredObject(options, "precommit")
                , requirements = requiredObject(options, "timeframeRequirements"); boolean fixture = options.path("fixtureOnly").asBoolean(false);
        validatePlan(plan); assertOwnHash(plan, DATA_V5.get("plan"), "feature-build plan"); validatePredictorRegistry(registry);
        assertOwnHash(acquisition, DATA_V5.get("acquisition"), "feature-build acquisition"); assertHashBinding(precommit, text(precommit, "content_sha256"), "feature-build precommit");
        assertOwnHash(requirements, "strategy-v5-timeframe-requirements/1", "feature-build timeframe requirements");
        if (!text(acquisition, "plan_sha256").equals(text(plan, "content_sha256"))) throw failure("feature-build acquisition is bound to a different plan");
        if (!text(requirements, "precommit_sha256").equals(text(precommit, "content_sha256")) || !text(requirements, "predictor_registry_sha256").equals(text(registry, "content_sha256"))) throw failure("feature-build requirements are not bound to the precommit and predictor registry");
        ObjectNode stagingVerify = object().set("manifest", acquisition); stagingVerify.put("root", root.toString()).set("plan", plan); stagingVerify.put("planSha256", text(plan, "content_sha256")).put("requireComplete", true).put("allowFixture", fixture); verifyAuthoritativeStaging(stagingVerify);
        ObjectNode coverageOptions = object().set("plan", plan); coverageOptions.set("acquisition", acquisition); coverageOptions.set("timeframeRequirements", requirements); coverageOptions.put("requireParquet", false).put("requireFrozenRequirements", true);
        ObjectNode promoted = resolvePromotedCoverage(coverageOptions); if (!"READY".equals(text(promoted, "status"))) throw failure("feature-build declared price/funding coverage is blocked: " + String.join(";", texts(promoted.path("limitations"))));
        ObjectNode scope = derivePrecommitTradeScopeV5(precommit); Set<String> tradeAssets = new HashSet<>(texts(scope.path("trade_assets"))); String instrument = text(scope, "instrument");
        List<ObjectNode> primary = new ArrayList<>(), context = new ArrayList<>(); ArrayNode inventory = array();
        for (ObjectNode capture : objects(acquisition.path("captures"))) {
            if (!captureComplete(capture) || !capture.path("partition").isObject()) continue; Path partition = verifiedRegularPath(root, text(capture.path("partition"), "path"), "feature source partition");
            byte[] bytes = PathConfinement.readSinglyLinkedFile(partition, "feature source partition"); if (!hash(bytes).equals(text(capture.path("partition"), "sha256"))) throw failure("feature source partition bytes are tampered");
            List<ObjectNode> rows = readJsonlBytes(bytes, "feature source partition"); boolean isPrimary = "signal_bars".equals(text(capture, "series_type")) && tradeAssets.contains(text(capture, "asset")) && instrument.equals(text(capture, "instrument"));
            if (isPrimary) primary.addAll(rows); else context.addAll(rows); inventory.add(object().put("path", text(capture.path("partition"), "path")).put("sha256", text(capture.path("partition"), "sha256")));
        }
        ObjectNode derive = object().set("rawRows", array(primary)); derive.set("contextRows", array(context)); derive.set("predictorRegistry", registry); ArrayNode featureRows = deriveFeatureRowsFromRaw(derive);
        if (featureRows.isEmpty()) throw failure("feature-build produced no completed tradeable feature rows"); byte[] canonical = jsonlBytes(objects(featureRows)); String digest = hash(canonical), inventorySha = hash(inventory);
        String artifactPath = "derived/feature-source/features-" + inventorySha + "-" + digest + ".jsonl"; writeContentAddressed(root, artifactPath, canonical, "feature-build artifact");
        ObjectNode artifact = object().put("role", "FEATURE").put("path", artifactPath).put("sha256", digest).put("bytes", canonical.length)
                .put("row_count", featureRows.size()).put("rows_sha256", hash(featureRows)).put("format", "JSONL").put("storage_role", "STAGING").put("authoritative", false); artifact.set("field_names", fieldNames(objects(featureRows)));
        ObjectNode value = object().put("schema", DATA_V5.get("featureSource")).put("version", 1).put("status", "VERIFIED_FEATURES")
                .put("fixture_only", fixture).put("provenance", fixture ? "FIXTURE_INJECTED" : textOr(acquisition.get("provenance"), "PUBLIC_ADAPTER_RECOMPUTED"))
                .put("plan_sha256", text(plan, "content_sha256")).put("acquisition_sha256", text(acquisition, "content_sha256"))
                .put("source_dataset_root_sha256", computeSourceDatasetRootSha256(objectWith("manifest", acquisition, "root", root.toString())))
                .put("predictor_registry_sha256", text(registry, "content_sha256")).put("precommit_sha256", text(precommit, "content_sha256"))
                .put("requirements_sha256", text(requirements, "content_sha256")).put("promoted_coverage_sha256", text(promoted, "content_sha256"))
                .put("producer_code_sha256", javaProducerCodeSha256()).put("trade_instrument", instrument)
                .put("source_inventory_sha256", inventorySha).put("root_reference", portableReference(root, text(options, "rootReference")))
                .put("storage_role", "STAGING").put("format", "JSONL").put("authoritative", false);
        value.set("plan_reference", persistPhysicalJsonInput(root, plan, text(plan, "content_sha256"), "feature-source-plan"));
        value.set("acquisition_reference", persistPhysicalJsonInput(root, acquisition, text(acquisition, "content_sha256"), "feature-source-acquisition"));
        value.set("predictor_registry_reference", persistPhysicalJsonInput(root, registry, text(registry, "content_sha256"), "feature-source-predictor-registry"));
        value.set("precommit_reference", persistPhysicalJsonInput(root, precommit, text(precommit, "content_sha256"), "feature-source-precommit"));
        value.set("requirements_reference", persistPhysicalJsonInput(root, requirements, text(requirements, "content_sha256"), "feature-source-timeframe-requirements"));
        value.set("promoted_coverage_reference", persistPhysicalJsonInput(root, promoted, text(promoted, "content_sha256"), "feature-source-promoted-coverage"));
        value.set("producer_code_reference", persistPhysicalBytes(root, "lineage/producer-code/feature-source-" + javaProducerCodeSha256() + ".class", javaProducerBytes(), "feature-source producer code"));
        value.set("trade_assets", scope.path("trade_assets").deepCopy()); value.set("source_inventory", inventory); value.set("artifact", artifact); value.set("limitations", promoted.path("limitations").deepCopy());
        ObjectNode result = object(); result.set("manifest", withHash(value)); result.set("rows", featureRows); result.set("promotedCoverage", promoted); return result;
    }

    public static ObjectNode validateAuthoritativeFeatureSource(ObjectNode options) {
        ObjectNode manifest = requiredObject(options, "manifest"); Path root = requiredPath(options, "root"); assertOwnHash(manifest, DATA_V5.get("featureSource"), "authoritative feature source");
        if (manifest.path("fixture_only").asBoolean(false) && !options.path("allowFixture").asBoolean(false)) throw failure("fixture-only feature source cannot enter an authoritative research boundary");
        ObjectNode plan = reopenBoundInput(root, manifest, "plan", "feature-source plan"), acquisition = reopenBoundInput(root, manifest, "acquisition", "feature-source acquisition")
                , registry = reopenBoundInput(root, manifest, "predictor_registry", "feature-source predictor registry"), precommit = reopenBoundInput(root, manifest, "precommit", "feature-source precommit")
                , requirements = reopenBoundInput(root, manifest, "requirements", "feature-source timeframe requirements");
        for (String expected : List.of("expectedPlanSha256", "expectedAcquisitionSha256", "expectedPredictorRegistrySha256", "expectedPrecommitSha256")) if (options.hasNonNull(expected)) {
            String actual = switch (expected) { case "expectedPlanSha256" -> text(manifest, "plan_sha256"); case "expectedAcquisitionSha256" -> text(manifest, "acquisition_sha256"); case "expectedPredictorRegistrySha256" -> text(manifest, "predictor_registry_sha256"); default -> text(manifest, "precommit_sha256"); };
            if (!text(options, expected).equals(actual)) throw failure("feature source is bound to a different " + expected.substring(8).replace("Sha256", "").toLowerCase(Locale.ROOT));
        }
        ObjectNode rebuildOptions = object().put("root", root.toString()).put("rootReference", text(manifest, "root_reference")).put("fixtureOnly", manifest.path("fixture_only").asBoolean(false));
        rebuildOptions.set("plan", plan); rebuildOptions.set("acquisition", acquisition); rebuildOptions.set("predictorRegistry", registry); rebuildOptions.set("precommit", precommit); rebuildOptions.set("timeframeRequirements", requirements);
        ObjectNode rebuilt = produceAuthoritativeFeatureSource(rebuildOptions); ObjectNode rebuiltManifest = (ObjectNode) rebuilt.path("manifest");
        if (!text(rebuiltManifest.path("artifact"), "sha256").equals(text(manifest.path("artifact"), "sha256"))
                || !text(rebuiltManifest, "source_inventory_sha256").equals(text(manifest, "source_inventory_sha256"))) throw failure("feature-source artifact differs from deterministic physical-source recomputation");
        ObjectNode result = object(); result.set("manifest", manifest); result.set("plan", plan); result.set("acquisition", acquisition); result.set("predictorRegistry", registry); result.set("precommit", precommit); result.set("timeframeRequirements", requirements); result.set("promotedCoverage", rebuilt.path("promotedCoverage")); result.set("rows", rebuilt.path("rows")); return result;
    }

    public static boolean validateFeatureSubsetAgainstSource(ObjectNode options) {
        ObjectNode verify = object().set("manifest", requiredObject(options, "featureSourceManifest")); verify.put("root", text(options, "root")); if (options.path("allowFixture").asBoolean(false)) verify.put("allowFixture", true);
        ObjectNode source = validateAuthoritativeFeatureSource(verify); Path finalRoot = options.hasNonNull("finalRoot") ? requiredPath(options, "finalRoot") : requiredPath(options, "root"); ObjectNode reference = requiredObject(options, "finalFeatureReference");
        byte[] bytes = readPhysical(finalRoot, text(reference, "path"), "final feature subset"); if (!hash(bytes).equals(text(reference, "sha256"))) throw failure("final feature subset bytes are tampered");
        Map<String, ObjectNode> sourceRows = objects(source.path("rows")).stream().collect(java.util.stream.Collectors.toMap(StrategyResearchDataV5::featureIdentity, Function.identity())); Set<String> seen = new HashSet<>();
        for (ObjectNode row : readJsonlBytes(bytes, "final feature subset")) { String identity = featureIdentity(row); if (!seen.add(identity)) throw failure("final feature subset contains a duplicate opportunity identity: " + identity); ObjectNode original = sourceRows.get(identity); if (original == null || !stable(original).equals(stable(row))) throw failure("final feature subset differs from pre-hydration feature source: " + identity); }
        return true;
    }

    public static ObjectNode produceAuthoritativeRoleArtifacts(ObjectNode options) {
        Path root = requiredPath(options, "root"); ObjectNode plan = requiredObject(options, "plan"), registry = requiredObject(options, "predictorRegistry");
        ObjectNode sourceReference = requiredObject(options, "sourceManifestReference"); validatePlan(plan); LinkedHashMap<String, ObjectNode> predictors = validatePredictorRegistry(registry);
        ObjectNode sourceRequest = object().put("root", root.toString()).put("expectedContentSha256", text(options, "sourceManifestSha256"))
                .put("planSha256", text(plan, "content_sha256")).put("label", "authoritative role source bundle"); sourceRequest.set("reference", sourceReference);
        ObjectNode context = verifyAuthoritativeSourceChain(sourceRequest), acquisition = requiredObject(context, "acquisition");
        ObjectNode hydration = context.path("hydration").isObject() ? (ObjectNode) context.path("hydration") : null;
        List<RoleSourcePart> sourceParts = new ArrayList<>(); sourceParts.add(new RoleSourcePart(acquisition, "ACQUISITION"));
        if (hydration != null) sourceParts.add(new RoleSourcePart(hydration, "HYDRATION"));
        ObjectNode rootRequest = object().put("root", root.toString()); String derivedRoot;
        if (context.path("bundle").isObject()) {
            rootRequest.set("acquisition", acquisition); rootRequest.set("hydration", hydration);
            rootRequest.put("envelopeSha256", text(context.path("bundle"), "envelope_sha256")); rootRequest.put("candidateSetSha256", text(context.path("bundle"), "candidate_set_sha256"));
            derivedRoot = computeSourceBundleDatasetRootSha256(rootRequest);
        } else { rootRequest.set("manifest", acquisition); derivedRoot = computeSourceDatasetRootSha256(rootRequest); }
        if (options.hasNonNull("sourceDatasetRootSha256") && !derivedRoot.equals(text(options, "sourceDatasetRootSha256"))) {
            throw failure("source dataset root hash does not match the verified physical source inventory");
        }
        ObjectNode roleSources = options.path("roleSources").isObject() ? (ObjectNode) options.path("roleSources") : object();
        ObjectNode precommit = requiredObject(options, "precommit"), envelope = requiredObject(options, "envelope"), config = requiredObject(options, "config");
        ObjectNode tradeScope = "strategy-precommit/1".equals(text(precommit, "schema")) ? derivePrecommitTradeScopeV5(precommit) : null;
        boolean markNotApplicable = "strategy-v5-opportunity-envelope/2".equals(text(envelope, "schema"))
                && !objects(envelope.path("windows")).isEmpty() && objects(envelope.path("windows")).stream().allMatch(window -> "BINANCE_SPOT".equals(text(window, "instrument").toUpperCase(Locale.ROOT)));

        RoleFeatureInput featureInput = authoritativeFeatureInput(sourceParts, root, registry, predictors, roleSources, tradeScope);
        Map<String, List<ObjectNode>> references = new LinkedHashMap<>(); Map<String, List<RoleBoundSource>> bounds = new LinkedHashMap<>();
        references.put("FEATURE", featureInput.references()); bounds.put("FEATURE", featureInput.bounds());
        Map<String, List<ObjectNode>> rawInputs = new LinkedHashMap<>(); rawInputs.put("FEATURE", new ArrayList<>(featureInput.rows()));
        for (String role : List.of("LABEL", "EXECUTION", "MARK")) {
            if ("MARK".equals(role) && markNotApplicable) {
                JsonNode supplied = firstDefined(roleSources.get("mark"), roleSources.get("marks"), roleSources.get("MARK"));
                if (defined(supplied) && !(supplied.isArray() && supplied.isEmpty())) throw failure("spot-only MARK role must be empty; derivative mark sources are not applicable");
                references.put(role, List.of()); bounds.put(role, List.of()); rawInputs.put(role, new ArrayList<>()); continue;
            }
            List<ObjectNode> selected = roleSourceReferences(roleSources, role, sourceParts, predictors);
            List<RoleBoundSource> selectedBounds = sourceRoleBounds(sourceParts, role, selected, root);
            references.put(role, selected); bounds.put(role, selectedBounds);
            List<ObjectNode> rows = new ArrayList<>();
            if ("MARK".equals(role) || hydration == null) for (RoleBoundSource bound : selectedBounds) rows.addAll(readBoundRoleRows(bound, role));
            rawInputs.put(role, rows);
        }

        List<ObjectNode> featureRows = new ArrayList<>(featureInput.rows());
        if (hydration != null) {
            if ("strategy-v5-opportunity-envelope/2".equals(text(envelope, "schema"))) {
                Map<String, ObjectNode> byIdentity = new HashMap<>();
                for (ObjectNode row : featureRows) { String identity = opportunityIdentity(row); if (byIdentity.put(identity, row) != null) throw failure("derived feature inventory is ambiguous for v2 opportunity identity: " + identity); }
                List<ObjectNode> selected = new ArrayList<>(); Set<String> episodes = new HashSet<>();
                for (ObjectNode window : objects(envelope.path("windows"))) {
                    String identity = opportunityIdentity(window); ObjectNode row = byIdentity.get(identity);
                    if (row == null || !text(row, "episode_id").equals(text(window, "episode_id")) || !text(row, "signal_id").equals(text(window, "signal_id"))
                            || !hash(row).equals(text(window, "source_row_sha256"))) throw failure("v2 opportunity window is not exactly bound to one derived feature row: " + identity);
                    if (!episodes.add(text(row, "signal_id") + "|" + text(row, "episode_id"))) throw failure("v2 opportunity feature inventory is empty or duplicated"); selected.add(row);
                }
                if (selected.isEmpty()) throw failure("v2 opportunity feature inventory is empty or duplicated"); featureRows = selected;
            } else {
                List<HydrationWindow> windows = objects(hydration.path("captures")).stream().map(StrategyResearchDataV5::hydrationWindow).toList();
                featureRows = featureRows.stream().filter(row -> windows.stream().anyMatch(window -> window.matches(row))).toList();
            }
            rawInputs.put("LABEL", hydrationOpportunityRows(bounds.get("LABEL"), featureRows, envelope));
            rawInputs.put("EXECUTION", hydrationOpportunityRows(bounds.get("EXECUTION"), featureRows, envelope));
        }
        Map<String, ObjectNode> featureByIdentity = new HashMap<>();
        for (ObjectNode row : featureRows) { String identity = physicalOpportunityIdentity(row); if (featureByIdentity.put(identity, row) != null) throw failure("FEATURE derived identity is duplicated: " + identity); }
        Map<String, PhysicalOpportunity> executionOpportunities = physicalOpportunityMap(rawInputs.get("EXECUTION"), "EXECUTION");
        Map<String, PhysicalOpportunity> labelOpportunities = physicalOpportunityMap(rawInputs.get("LABEL").stream().filter(row -> !objects(row.path("child_bars")).isEmpty()).toList(), "LABEL");
        for (var entry : labelOpportunities.entrySet()) if (executionOpportunities.containsKey(entry.getKey())
                && !stable(entry.getValue().bars()).equals(stable(executionOpportunities.get(entry.getKey()).bars()))) throw failure("LABEL and EXECUTION physical opportunity paths disagree for " + entry.getKey());
        Map<String, PhysicalOpportunity> fallback = new HashMap<>(executionOpportunities); fallback.putAll(labelOpportunities);
        Map<String, List<ObjectNode>> markPaths = buildOpportunityMarkPaths(bounds.get("MARK"), featureRows, envelope);
        Map<String, List<ObjectNode>> rowsByRole = new LinkedHashMap<>(); rowsByRole.put("FEATURE", roleSort(featureRows));
        rowsByRole.put("LABEL", deriveLabelRoleRows(rawInputs.get("LABEL"), featureByIdentity, fallback, envelope));
        rowsByRole.put("EXECUTION", deriveExecutionRoleRows(rawInputs.get("EXECUTION"), featureByIdentity, fallback, envelope, markPaths, config));
        rowsByRole.put("MARK", deriveMarkRoleRows(rawInputs.get("MARK")));

        ObjectNode result = object();
        for (String role : List.of("FEATURE", "LABEL", "EXECUTION", "MARK")) {
            List<ObjectNode> rows = roleSort(rowsByRole.get(role)); byte[] bytes = jsonlBytes(rows); String digest = hash(bytes);
            List<ObjectNode> inventory = references.get(role); ArrayNode inventoryHashInput = array();
            inventory.stream().map(reference -> object().put("path", text(reference, "path")).put("sha256", text(reference, "sha256")))
                    .sorted(Comparator.comparing(row -> text(row, "path"))).forEach(inventoryHashInput::add);
            String inventoryDigest = hash(inventoryHashInput), outputPath = "derived/" + role.toLowerCase(Locale.ROOT) + "/" + role.toLowerCase(Locale.ROOT) + "-" + inventoryDigest + "-" + digest + ".jsonl";
            writeContentAddressed(root, outputPath, bytes, role + " derived artifact");
            ObjectNode receiptOptions = options.deepCopy(); receiptOptions.put("root", root.toString()).put("role", role).put("artifactSha256", digest)
                    .put("sourceDatasetRootSha256", derivedRoot).put("producerCommand", DATA_V5_PRODUCER_COMMANDS.get(role));
            if (options.path("producerCodeReference").isObject()) receiptOptions.set("codeReference", options.path("producerCodeReference").deepCopy());
            ObjectNode receipt = emitRoleReceipt(receiptOptions, "AUTHORITATIVE_INTERNAL");
            ObjectNode artifact = object().put("path", outputPath).put("format", "JSONL").put("sha256", digest);
            if (inventory.size() == 1) { artifact.put("source_path", text(inventory.get(0), "path")); artifact.put("source_sha256", text(inventory.get(0), "sha256")); }
            else { artifact.set("source_path", strings(inventory.stream().map(row -> text(row, "path")).toList())); artifact.set("source_sha256", strings(inventory.stream().map(row -> text(row, "sha256")).toList())); }
            artifact.set("source_inventory", inventoryHashInput); artifact.set("role_receipt", receipt); artifact.put("source_dataset_root_sha256", derivedRoot);
            result.set(role.toLowerCase(Locale.ROOT), artifact);
        }
        return result;
    }

    public static ObjectNode buildUserBoundSpotMetadataV5(ObjectNode options) {
        Path root = requiredPath(options, "root"); ObjectNode plan = requiredObject(options, "plan"), precommit = requiredObject(options, "precommit"), evaluator = requiredObject(options, "evaluatorSpec"), policy = requiredObject(options, "policy");
        validatePlan(plan); assertOwnHash(plan, DATA_V5.get("plan"), "spot metadata plan"); assertOwnHash(precommit, "strategy-precommit/1", "spot metadata precommit"); assertOwnHash(evaluator, "strategy-v5-evaluator-spec/1", "spot metadata evaluator"); assertOwnHash(policy, "strategy-v5-spot-execution-policy/1", "spot execution policy");
        ObjectNode scopeOptions = object().set("candidateTemplate", evaluator.path("candidate_template")); ObjectNode scope = derivePrecommitTradeScopeV5(precommit, scopeOptions);
        if (!"BINANCE_SPOT".equals(text(scope, "instrument")) || !"BINANCE_SPOT".equals(text(policy, "instrument"))) throw failure("metadata-build currently supports spot execution only");
        if (!text(policy, "plan_sha256").equals(text(plan, "content_sha256")) || !text(policy, "precommit_sha256").equals(text(precommit, "content_sha256")) || !text(policy, "evaluator_spec_sha256").equals(text(evaluator, "content_sha256"))) throw failure("spot execution policy lineage differs from plan/precommit/evaluator");
        byte[] bytes = options.hasNonNull("policyBytesBase64") ? Base64.getDecoder().decode(text(options, "policyBytesBase64")) : options.hasNonNull("policyBytes") ? text(options, "policyBytes").getBytes(StandardCharsets.UTF_8) : prettyBytes(policy);
        try { if (!stable(JSON.readTree(bytes)).equals(stable(policy))) throw failure("spot execution policy source bytes differ from the validated policy object"); } catch (IOException error) { throw failure("spot execution policy source bytes are invalid JSON: " + error.getMessage()); }
        String byteSha = hash(bytes), rawPath = "raw/spot-execution-policy-" + byteSha + ".json"; writeContentAddressed(root, rawPath, bytes, "spot execution policy raw bytes");
        ObjectNode raw = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1).put("path", rawPath).put("source", "USER_BOUND_SPOT_EXECUTION_POLICY")
                .put("byte_sha256", byteSha).put("bytes", bytes.length).put("format", "RAW_BYTES").put("storage_role", "RAW_IGNORED").put("authoritative", false); raw.set("request", object().put("endpoint", "user-bound://spot-execution-policy").put("response_sha256", byteSha)); raw = withHash(raw);
        ObjectNode normalized = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1).put("status", "USER_BOUND").put("captured_at", text(policy, "created_at")); normalized.set("request", object().put("endpoint", "user-bound://spot-execution-policy").put("response_sha256", byteSha)); normalized.set("response_sha256", strings(List.of(byteSha))); normalized.set("source_byte_sha256", strings(List.of(byteSha))); normalized.set("raw_receipts", array(List.of(raw))); normalized.set("coverage", object().put("complete", true)); normalized = withHash(normalized);
        String normalizedPath = "receipts/spot-execution-policy-" + text(normalized, "content_sha256") + ".json"; writeContentAddressed(root, normalizedPath, prettyBytes(normalized), "spot execution policy normalized receipt");
        long lifecycle = evaluator.path("candidate_template").path("max_lifecycle_ms").asLong(); if (lifecycle <= 0 || lifecycle > 30L * 24 * 60 * 60 * 1_000) throw failure("metadata-build requires a fixed positive evaluator max_lifecycle_ms no greater than 30 days");
        String executionEnd = iso(time(plan.path("window").get("end_at")) + lifecycle); ArrayNode limitations = strings(concat(uniqueTextsOrEmpty(policy.get("limitations")), List.of("RETROSPECTIVE_USER_BOUND_RESEARCH_ASSUMPTION", "NOT_ACTIVATION_EVIDENCE")).stream().distinct().sorted().toList());
        ObjectNode source = object().put("provider", "USER").put("kind", "SPOT_EXECUTION_POLICY").put("content_sha256", text(normalized, "content_sha256")).put("byte_sha256", byteSha).put("path", normalizedPath);
        ObjectNode bundle = object(); List<ObjectNode> contracts = objects(policy.path("asset_contracts"));
        for (String kind : List.of("CONTRACT_SPEC", "FEE_SCHEDULE", "EXECUTION_MODEL")) {
            ArrayNode records = array(); for (ObjectNode contract : contracts) { ObjectNode row = object().put("asset", text(contract, "asset")).put("venue", "BINANCE").put("instrument", "BINANCE_SPOT").put("symbol", text(contract, "symbol")).put("effective_from", text(plan.path("window"), "start_at")).put("effective_to", executionEnd).put("availability_time", text(plan.path("window"), "start_at")); if ("CONTRACT_SPEC".equals(kind)) { row.put("contract_multiplier", 1); for (String field : List.of("step_size", "min_qty", "max_qty", "min_notional", "max_notional")) row.put(field, contract.path(field).asDouble()); row.put("lot_step", contract.path("step_size").asDouble()); } else if ("FEE_SCHEDULE".equals(kind)) row.put("taker_fee_rate", policy.path("cost_model").path("taker_fee_rate").asDouble()); else row.put("slippage_bps", policy.path("cost_model").path("slippage_bps").asDouble()).put("impact_bps", policy.path("cost_model").path("impact_bps").asDouble()).put("outage_policy", text(policy, "outage_policy")).put("gap_policy", text(policy, "gap_policy")); records.add(row); }
            ObjectNode receiptOptions = object().put("kind", kind).put("status", "USER_BOUND").put("sourceReceiptSha256", text(normalized, "content_sha256")).put("sourceByteSha256", byteSha).put("sourceRoot", root.toString()).put("sourceRootReference", text(options, "rootReference")).put("sourceReceiptPath", normalizedPath).put("precommitSha256", text(precommit, "content_sha256")).put("evaluatorSpecSha256", text(evaluator, "content_sha256")).put("planSha256", text(plan, "content_sha256")).put("capturedAt", text(policy, "created_at")); receiptOptions.set("source", source); receiptOptions.set("records", records); receiptOptions.set("limitations", limitations); receiptOptions.set("coverage", object().put("complete", true).put("signal_start_at", text(plan.path("window"), "start_at")).put("signal_end_at", text(plan.path("window"), "end_at")).put("execution_end_at", executionEnd).put("max_lifecycle_ms", lifecycle)); bundle.set(kind.toLowerCase(Locale.ROOT), makeMetadataReceipt(receiptOptions));
        }
        ObjectNode result = object(); result.set("bundle", bundle); result.set("source_receipt", normalized); result.put("source_receipt_path", normalizedPath); result.set("raw_receipt", raw); result.put("raw_path", rawPath).put("source_byte_sha256", byteSha).put("bundle_sha256", hash(bundle)); result.set("trade_scope", scope); return result;
    }

    private static ObjectNode reopenBoundInput(Path root, ObjectNode manifest, String prefix, String label) { return verifyPhysicalJsonReference(root, (ObjectNode) manifest.path(prefix + "_reference"), text(manifest, prefix + "_sha256"), label); }
    private static ObjectNode objectWith(String objectName, ObjectNode objectValue, String textName, String textValue) { ObjectNode result = object(); result.set(objectName, objectValue); result.put(textName, textValue); return result; }
    private static byte[] jsonlBytes(List<ObjectNode> rows) { StringBuilder value = new StringBuilder(); for (ObjectNode row : rows) value.append(stable(row)).append('\n'); return value.toString().getBytes(StandardCharsets.UTF_8); }
    private static String featureIdentity(ObjectNode row) { return predictorAsset(row) + "|" + text(row, "venue").toUpperCase(Locale.ROOT) + "|" + text(row, "instrument").toUpperCase(Locale.ROOT) + "|" + text(row, "symbol").toUpperCase(Locale.ROOT) + "|" + time(row.get("decision_time")); }
    private record RoleSourcePart(ObjectNode manifest, String kind) {}
    private record RoleBoundSource(ObjectNode capture, ObjectNode partition, String sourceKind, Path path, ObjectNode reference) {}
    private record RoleFeatureInput(List<ObjectNode> rows, List<ObjectNode> references, List<RoleBoundSource> bounds) {}
    private record PhysicalOpportunity(ObjectNode raw, String identity, ArrayNode bars) {}
    private record HydrationWindow(String asset, String instrument, String symbol, long start, long end) {
        boolean matches(ObjectNode row) { long decision = time(row.get("decision_time")); return asset.equals(predictorAsset(row))
                && instrument.equals(text(row, "instrument").toUpperCase(Locale.ROOT)) && symbol.equals(text(row, "symbol").toUpperCase(Locale.ROOT))
                && decision >= start && decision <= end; }
    }

    private static boolean predictorRegistryRequiresFunding(Map<String, ObjectNode> registry) {
        return registry.values().stream().anyMatch(predictor -> {
            ObjectNode recipe = predictor.path("recipe").isObject() ? (ObjectNode) predictor.path("recipe") : object();
            String field = textOr(first(predictor, "source_field"), text(recipe, "source_field")).trim().toLowerCase(Locale.ROOT);
            String family = text(predictor, "source_family").trim().toLowerCase(Locale.ROOT), sourceSeries = text(recipe, "source_series").trim().toLowerCase(Locale.ROOT);
            return texts(recipe.path("required_series_types")).contains("funding_events") || Set.of("funding_rate", "funding").contains(field)
                    || Set.of("funding", "funding_events").contains(family) || Set.of("funding", "funding_events").contains(sourceSeries);
        });
    }

    private static List<ObjectNode> roleSourceReferences(ObjectNode roleSources, String role,
            List<RoleSourcePart> sourceParts, Map<String, ObjectNode> registry) {
        String lower = role.toLowerCase(Locale.ROOT); JsonNode supplied = firstDefined(roleSources.get(lower), roleSources.get(lower + "s"), roleSources.get(role));
        if (defined(supplied)) {
            List<ObjectNode> values = supplied.isArray() ? objects(supplied) : supplied.isObject() ? List.of((ObjectNode) supplied) : List.of();
            if (values.isEmpty()) throw failure(role + " authoritative role producer requires a non-empty source partition inventory");
            Set<String> seen = new HashSet<>(); List<ObjectNode> result = new ArrayList<>();
            for (ObjectNode reference : values) {
                if (text(reference, "path").isEmpty() || !isSha(text(reference, "sha256"))) throw failure(role + " source inventory reference requires a path and partition hash");
                if (!seen.add(text(reference, "path"))) throw failure(role + " source inventory contains a duplicate partition: " + text(reference, "path")); result.add(reference.deepCopy());
            }
            result.sort(Comparator.comparing((ObjectNode row) -> text(row, "path")).thenComparing(row -> text(row, "sha256"))); return result;
        }
        boolean hasHydration = sourceParts.stream().anyMatch(part -> "HYDRATION".equals(part.kind())); Map<String, ObjectNode> inferred = new TreeMap<>();
        for (RoleSourcePart part : sourceParts) for (ObjectNode capture : objects(part.manifest().path("captures"))) {
            ObjectNode partition = null; String type = text(capture, "series_type").toLowerCase(Locale.ROOT);
            if ("MARK".equals(role)) {
                if ("HYDRATION".equals(part.kind()) && capture.path("mark_partition").isObject()) partition = (ObjectNode) capture.path("mark_partition");
                else if (!hasHydration && "mark_bars".equals(type) && capture.path("partition").isObject()) partition = (ObjectNode) capture.path("partition");
            } else if (capture.path("partition").isObject()) partition = (ObjectNode) capture.path("partition");
            if (partition == null) continue;
            boolean fundingContext = "FEATURE".equals(role) && predictorRegistryRequiresFunding(registry) && "funding_events".equals(type);
            boolean tradeableSignal = Set.of("signal_bars", "raw_signal_bars", "raw_feature_input").contains(type) && !capture.has("tradeable") || Set.of("signal_bars", "raw_signal_bars", "raw_feature_input").contains(type) && capture.path("tradeable").asBoolean(true);
            boolean feature = "ACQUISITION".equals(part.kind()) && (tradeableSignal || Set.of("context_bars", "raw_context_bars", "macro_bars").contains(type) || fundingContext);
            boolean opportunity = "HYDRATION".equals(part.kind()) && !Set.of("FEATURE", "MARK").contains(role);
            boolean mark = "MARK".equals(role) && ("HYDRATION".equals(part.kind()) && capture.path("mark_partition").isObject()
                    || !hasHydration && ("mark_bars".equals(type) || "MARK".equals(text(capture, "series_role").toUpperCase(Locale.ROOT))));
            if ("FEATURE".equals(role) && feature || "LABEL".equals(role) && opportunity || "EXECUTION".equals(role) && opportunity || mark) {
                inferred.put(text(partition, "path"), object().put("path", text(partition, "path")).put("sha256", text(partition, "sha256")));
            }
        }
        if (inferred.isEmpty()) throw failure(role + " authoritative role producer requires a non-empty source partition inventory"); return new ArrayList<>(inferred.values());
    }

    private static List<RoleBoundSource> sourceRoleBounds(List<RoleSourcePart> parts, String role,
            List<ObjectNode> references, Path root) {
        Map<String, Set<String>> expected = Map.of(
                "FEATURE", Set.of("SIGNAL_BARS", "RAW_SIGNAL_BARS", "RAW_FEATURE_INPUT", "CONTEXT_BARS", "RAW_CONTEXT_BARS", "MACRO_BARS", "FUNDING_EVENTS"),
                "LABEL", Set.of("OPPORTUNITY_BARS", "RAW_OPPORTUNITY_BARS"),
                "EXECUTION", Set.of("OPPORTUNITY_BARS", "EXECUTION_BARS", "RAW_OPPORTUNITY_BARS", "RAW_EXECUTION_BARS"),
                "MARK", Set.of("MARK_BARS", "RAW_MARK_BARS"));
        List<RoleBoundSource> matches = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (ObjectNode reference : references) {
            RoleBoundSource found = null;
            for (RoleSourcePart part : parts) for (ObjectNode capture : objects(part.manifest().path("captures"))) for (String name : List.of("partition", "mark_partition")) {
                if (!capture.path(name).isObject() || !text(capture.path(name), "path").equals(text(reference, "path"))) continue;
                if (found != null) throw failure(role + " source partition is ambiguously enumerated by more than one physical capture: " + text(reference, "path"));
                ObjectNode partition = (ObjectNode) capture.path(name); Path path = verifiedRegularPath(root, text(partition, "path"), role + " source partition");
                byte[] bytes = readPhysical(root, text(partition, "path"), role + " source partition"); String digest = hash(bytes);
                if (!digest.equals(text(partition, "sha256")) || !digest.equals(text(reference, "sha256"))) throw failure(role + " source partition bytes are missing or tampered: " + text(reference, "path"));
                found = new RoleBoundSource(capture, partition, part.kind(), path, reference);
            }
            if (found == null) throw failure(role + " source partition is not enumerated by the verified physical source chain: " + text(reference, "path"));
            if (!seen.add(text(reference, "path"))) throw failure(role + " source partition is duplicated in the physical inventory: " + text(reference, "path")); matches.add(found);
        }
        for (RoleBoundSource match : matches) {
            String type = textOr(first(match.capture(), "series_type", "series_role"), "").toUpperCase(Locale.ROOT);
            boolean hydrationOpportunity = "HYDRATION".equals(match.sourceKind()) && !Set.of("FEATURE", "MARK").contains(role)
                    && text(match.partition(), "path").equals(text(match.capture().path("partition"), "path"));
            boolean hydrationMark = "HYDRATION".equals(match.sourceKind()) && "MARK".equals(role)
                    && text(match.partition(), "path").equals(text(match.capture().path("mark_partition"), "path"));
            if (!(hydrationOpportunity || hydrationMark || expected.get(role).contains(type))) throw failure(role + " source partition has no role-bound series type");
        }
        return matches;
    }

    private static List<ObjectNode> readBoundRoleRows(RoleBoundSource bound, String role) {
        byte[] bytes;
        try { bytes = PathConfinement.readSinglyLinkedFile(bound.path(), role + " source partition"); }
        catch (RuntimeException error) { throw failure(role + " source partition cannot be reopened: " + error.getMessage()); }
        List<ObjectNode> rows = readJsonlBytes(bytes, role + " source partition");
        if (!"HYDRATION".equals(bound.sourceKind()) || !("LABEL".equals(role) || "EXECUTION".equals(role))) return rows;
        if (!bound.capture().hasNonNull("execution_start")) throw failure(role + " hydration capture lacks its frozen decision/execution start");
        ArrayNode childBars = array(); for (ObjectNode row : rows) childBars.add(rawHydrationBar(row, role));
        ObjectNode opportunity = object().put("asset", text(bound.capture(), "asset")).put("venue", textOr(first(bound.capture(), "venue"), "BINANCE"))
                .put("instrument", text(bound.capture(), "instrument")).put("symbol", text(bound.capture(), "symbol"))
                .put("decision_time", text(bound.capture(), "execution_start")); opportunity.set("child_bars", childBars); return List.of(opportunity);
    }

    private static ObjectNode rawHydrationBar(ObjectNode row, String role) {
        Iterator<String> fields = row.fieldNames();
        while (fields.hasNext()) { String field = fields.next(); if (RAW_ROLE_DERIVED_FIELDS.contains(field) || PRECOMPUTED_EXECUTION.contains(field) || OUTCOME_PROVENANCE.matcher(field).find()) throw failure(role + " hydration raw bar contains a loader-derived field: " + field); }
        ObjectNode output = object(); for (String field : RAW_BAR_FIELDS) if (row.has(field)) output.set(field, row.get(field).deepCopy());
        output.put("event_time", iso(rowTime(row))).put("availability_time", iso(rowAvailability(row))); return output;
    }

    private static RoleFeatureInput authoritativeFeatureInput(List<RoleSourcePart> parts, Path root, ObjectNode registryNode,
            Map<String, ObjectNode> registry, ObjectNode roleSources, ObjectNode tradeScope) {
        List<ObjectNode> references = roleSourceReferences(roleSources, "FEATURE", parts, registry);
        List<RoleBoundSource> allBounds = sourceRoleBounds(parts, "FEATURE", references, root);
        Set<String> contextTypes = Set.of("CONTEXT_BARS", "RAW_CONTEXT_BARS", "MACRO_BARS", "CONTEXT", "MACRO", "FUNDING_EVENTS", "FUNDING");
        List<RoleBoundSource> featureBounds = new ArrayList<>(allBounds.stream().filter(bound -> !contextTypes.contains(textOr(first(bound.capture(), "series_type", "series_role"), "").toUpperCase(Locale.ROOT))).toList());
        List<RoleBoundSource> contextBounds = new ArrayList<>(allBounds.stream().filter(bound -> contextTypes.contains(textOr(first(bound.capture(), "series_type", "series_role"), "").toUpperCase(Locale.ROOT))).toList());
        if (tradeScope != null) {
            Set<String> assets = new HashSet<>(texts(tradeScope.path("trade_assets"))); String instrument = text(tradeScope, "instrument");
            featureBounds.removeIf(bound -> !assets.contains(text(bound.capture(), "asset").toLowerCase(Locale.ROOT)) || !instrument.equals(text(bound.capture(), "instrument").toUpperCase(Locale.ROOT)));
            Set<String> observed = new HashSet<>(); featureBounds.forEach(bound -> observed.add(text(bound.capture(), "asset").toLowerCase(Locale.ROOT)));
            List<String> missing = assets.stream().filter(asset -> !observed.contains(asset)).sorted().toList(); if (!missing.isEmpty()) throw failure("FEATURE source inventory lacks the frozen trade instrument for asset(s): " + String.join(",", missing));
            contextBounds.removeIf(bound -> "funding_events".equals(text(bound.capture(), "series_type")) && !assets.contains(text(bound.capture(), "asset").toLowerCase(Locale.ROOT)));
        }
        if (featureBounds.stream().anyMatch(bound -> bound.capture().has("tradeable") && !bound.capture().path("tradeable").asBoolean())) throw failure("FEATURE source inventory contains a non-tradeable signal series");
        List<ObjectNode> featureRaw = new ArrayList<>(), contextRaw = new ArrayList<>();
        Map<ObjectNode, ObjectNode> featureCaptures = new IdentityHashMap<>(), contextCaptures = new IdentityHashMap<>();
        for (RoleBoundSource bound : featureBounds) for (ObjectNode row : readBoundRoleRows(bound, "FEATURE")) { featureRaw.add(row); featureCaptures.put(row, bound.capture()); }
        for (RoleBoundSource bound : contextBounds) for (ObjectNode row : readBoundRoleRows(bound, "FEATURE")) { contextRaw.add(row); contextCaptures.put(row, bound.capture()); }
        if (predictorRegistryRequiresFunding(registry)) {
            List<String> funding = contextBounds.stream().filter(bound -> "funding_events".equals(text(bound.capture(), "series_type")))
                    .map(bound -> text(bound.capture(), "asset").toLowerCase(Locale.ROOT) + "|" + textOr(first(bound.capture(), "venue"), "BINANCE").toUpperCase(Locale.ROOT)).toList();
            if (new HashSet<>(funding).size() != funding.size()) throw failure("FEATURE funding source inventory contains an ambiguous same-asset perpetual series");
            Set<String> required = new HashSet<>(); featureBounds.forEach(bound -> required.add(text(bound.capture(), "asset").toLowerCase(Locale.ROOT) + "|" + textOr(first(bound.capture(), "venue"), "BINANCE").toUpperCase(Locale.ROOT)));
            List<String> missing = required.stream().filter(value -> !funding.contains(value)).sorted().toList(); if (!missing.isEmpty()) throw failure("FEATURE funding source inventory is incomplete for tradeable series: " + String.join(",", missing));
        }
        ObjectNode request = object(); request.set("rawRows", array(featureRaw)); request.set("predictorRegistry", registryNode); request.set("contextRows", array(contextRaw));
        if (!featureBounds.isEmpty()) request.set("capture", featureBounds.get(0).capture());
        List<RoleBoundSource> selected = new ArrayList<>(); selected.addAll(featureBounds); selected.addAll(contextBounds);
        Map<String, ObjectNode> selectedRefs = new TreeMap<>(); selected.forEach(bound -> selectedRefs.put(text(bound.reference(), "path"), bound.reference()));
        return new RoleFeatureInput(objects(deriveFeatureRowsFromRaw(array(featureRaw), request, featureCaptures, contextCaptures)), new ArrayList<>(selectedRefs.values()), selected);
    }

    private static String opportunityIdentity(ObjectNode row) { return predictorAsset(row) + "|" + text(row, "instrument").toUpperCase(Locale.ROOT) + "|" + text(row, "symbol").toUpperCase(Locale.ROOT) + "|" + time(row.get("decision_time")); }
    private static String physicalOpportunityIdentity(ObjectNode row) { return predictorAsset(row) + "|" + text(row, "venue").toUpperCase(Locale.ROOT) + "|" + text(row, "instrument").toUpperCase(Locale.ROOT) + "|" + text(row, "symbol").toUpperCase(Locale.ROOT) + "|" + time(first(row, "decision_time", "parent_decision_time", "window_decision_time")); }
    private static HydrationWindow hydrationWindow(ObjectNode capture) { return new HydrationWindow(text(capture, "asset").toLowerCase(Locale.ROOT), text(capture, "instrument").toUpperCase(Locale.ROOT), text(capture, "symbol").toUpperCase(Locale.ROOT), time(capture.get("execution_start")), time(capture.get("execution_end"))); }

    private static List<ObjectNode> hydrationOpportunityRows(List<RoleBoundSource> bounds, List<ObjectNode> features, ObjectNode envelope) {
        List<ObjectNode> result = new ArrayList<>();
        for (RoleBoundSource bound : bounds) {
            if (!"HYDRATION".equals(bound.sourceKind())) { result.addAll(readBoundRoleRows(bound, "LABEL")); continue; }
            List<ObjectNode> rawBars; try { rawBars = readJsonlBytes(PathConfinement.readSinglyLinkedFile(bound.path(), "hydration source partition"), "hydration source partition"); }
            catch (RuntimeException error) { throw failure("hydration source partition cannot be reopened: " + error.getMessage()); }
            List<ObjectNode> bars = rawBars.stream().map(row -> rawHydrationBar(row, "hydration")).sorted(Comparator.comparingLong(StrategyResearchDataV5::rowTime)).toList();
            HydrationWindow window = hydrationWindow(bound.capture()); long maxLifecycle = bound.capture().path("max_lifecycle_ms").asLong(envelope.path("max_lifecycle_ms").asLong());
            for (ObjectNode feature : features) if (window.matches(feature)) {
                long decision = time(feature.get("decision_time")), last = Math.min(window.end(), decision + maxLifecycle);
                List<ObjectNode> child = bars.stream().filter(row -> rowTime(row) >= decision && rowTime(row) <= last).toList();
                if (child.isEmpty() || rowTime(child.get(0)) != decision) throw failure("hydration opportunity path lacks the exact next-bar entry for " + text(feature, "asset") + " " + text(feature, "instrument") + " " + text(feature, "symbol") + " " + text(feature, "decision_time"));
                ObjectNode row = object().put("asset", text(bound.capture(), "asset")).put("venue", textOr(first(bound.capture(), "venue"), "BINANCE"))
                        .put("instrument", text(bound.capture(), "instrument")).put("symbol", text(bound.capture(), "symbol")).put("decision_time", text(feature, "decision_time")); row.set("child_bars", array(child)); result.add(row);
            }
        }
        return result;
    }

    private static ArrayNode rawBarPath(ObjectNode raw, String role) {
        List<ObjectNode> children = objects(raw.path("child_bars")); if (children.isEmpty()) throw failure(role + " raw opportunity input must contain the exact later child_bars path");
        List<ObjectNode> bars = new ArrayList<>();
        for (int index = 0; index < children.size(); index++) {
            ObjectNode row = children.get(index).deepCopy(); long event = rowTime(row), available = rowAvailability(row);
            double open = numeric(row.get("open")), high = numeric(row.get("high")), low = numeric(row.get("low")), close = numeric(row.get("close"));
            if (!Double.isFinite(open) || !Double.isFinite(high) || !Double.isFinite(low) || !Double.isFinite(close) || !(open > 0) || !(high > 0) || !(low > 0) || !(close > 0) || low > high || available < event + ONE_MINUTE - 1_000) {
                throw failure(role + " raw child path contains an invalid or not-yet-complete bar at index " + index);
            }
            row.put("event_time", iso(event)).put("availability_time", iso(available)).put("open", open).put("high", high).put("low", low).put("close", close); bars.add(row);
        }
        bars.sort(Comparator.comparingLong(StrategyResearchDataV5::rowTime)); Set<Long> times = new HashSet<>();
        for (int index = 0; index < bars.size(); index++) if (!times.add(rowTime(bars.get(index))) || index > 0 && rowTime(bars.get(index)) != rowTime(bars.get(index - 1)) + ONE_MINUTE) throw failure(role + " raw child path is not a dense, unique one-minute sequence");
        return array(bars);
    }

    private static Map<String, PhysicalOpportunity> physicalOpportunityMap(List<ObjectNode> rawRows, String role) {
        Map<String, PhysicalOpportunity> result = new LinkedHashMap<>();
        for (ObjectNode raw : rawRows) { rejectRawDerivedFields(raw, role, Set.of()); String identity = physicalOpportunityIdentity(raw);
            if (result.put(identity, new PhysicalOpportunity(raw.deepCopy(), identity, rawBarPath(raw, role))) != null) throw failure(role + " raw input has duplicate physical identity: " + identity); }
        return result;
    }

    private static List<ObjectNode> deriveLabelRoleRows(List<ObjectNode> rawRows, Map<String, ObjectNode> features,
            Map<String, PhysicalOpportunity> fallback, ObjectNode envelope) {
        List<ObjectNode> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (ObjectNode raw : rawRows) {
            rejectRawDerivedFields(raw, "LABEL", Set.of()); String identity = physicalOpportunityIdentity(raw); if (!seen.add(identity)) throw failure("LABEL raw input has duplicate physical identity: " + identity);
            ObjectNode feature = features.get(identity); if (feature == null) throw failure("LABEL raw input has no exact loader-owned feature identity: " + identity);
            ArrayNode own = objects(raw.path("child_bars")).isEmpty() ? null : rawBarPath(raw, "LABEL"); PhysicalOpportunity fallbackValue = fallback.get(identity); ArrayNode bars = own != null ? own : fallbackValue == null ? null : fallbackValue.bars();
            if (bars == null || bars.isEmpty()) throw failure("LABEL raw input has no exact later child-bar path: " + identity);
            if (own != null && fallbackValue != null && !stable(own).equals(stable(fallbackValue.bars()))) throw failure("LABEL raw input child path disagrees with the bound execution child path: " + identity);
            long entry = rowTime((ObjectNode) bars.get(0)); if (entry != time(feature.get("decision_time"))) throw failure("LABEL raw input does not begin at the exact completed-boundary next-bar entry: " + identity);
            long envelopeEnd = envelope.path("max_lifecycle_ms").asLong(0) > 0 ? time(feature.get("decision_time")) + envelope.path("max_lifecycle_ms").asLong() : Long.MAX_VALUE;
            long ceiling = Math.min(rowTime((ObjectNode) bars.get(bars.size() - 1)), envelopeEnd); if (ceiling <= entry) throw failure("LABEL raw input has no usable resolution ceiling: " + identity);
            long available = objects(bars).stream().mapToLong(StrategyResearchDataV5::rowAvailability).max().orElseThrow();
            ObjectNode row = roleIdentityRow(feature).put("entry_time", iso(entry)).put("resolution_ceiling_time", iso(ceiling)).put("availability_time", iso(available))
                    .put("lifecycle_timeframe", textOr(first(envelope, "lifecycle_timeframe"), "1m"))
                    .put("max_lifecycle_ms", envelope.path("max_lifecycle_ms").asLong(Math.max(ONE_MINUTE, ceiling - time(feature.get("decision_time"))))); result.add(row);
        }
        return roleSort(result);
    }

    private static List<ObjectNode> deriveExecutionRoleRows(List<ObjectNode> rawRows, Map<String, ObjectNode> features,
            Map<String, PhysicalOpportunity> fallback, ObjectNode envelope, Map<String, List<ObjectNode>> markPaths, ObjectNode config) {
        List<ObjectNode> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (ObjectNode raw : rawRows) {
            rejectRawDerivedFields(raw, "EXECUTION", Set.of()); String identity = physicalOpportunityIdentity(raw); if (!seen.add(identity)) throw failure("EXECUTION raw input has duplicate physical identity: " + identity);
            ObjectNode feature = features.get(identity); if (feature == null) throw failure("EXECUTION raw input has no exact loader-owned feature identity: " + identity);
            ArrayNode bars = rawBarPath(raw, "EXECUTION"); if (fallback.containsKey(identity) && !stable(bars).equals(stable(fallback.get(identity).bars()))) throw failure("EXECUTION raw input disagrees with the bound opportunity child path: " + identity);
            ObjectNode output = roleIdentityRow(feature).put("availability_time", iso(objects(bars).stream().mapToLong(StrategyResearchDataV5::rowAvailability).max().orElseThrow()))
                    .put("lifecycle_timeframe", textOr(first(envelope, "lifecycle_timeframe"), "1m")).put("max_lifecycle_ms", envelope.path("max_lifecycle_ms").asLong(0)); output.set("child_bars", bars);
            List<Double> quote = objects(bars).stream().map(row -> row.path("quote_volume").asDouble(Double.NaN)).filter(value -> Double.isFinite(value) && value > 0).toList();
            ObjectNode capacity = config.path("execution_capacity_contract").isObject() ? (ObjectNode) config.path("execution_capacity_contract") : config.path("capacity_contract").isObject() ? (ObjectNode) config.path("capacity_contract") : null;
            if (capacity != null) { double cap = capacity.path("participation_cap").asDouble(Double.NaN), notional = capacity.path("order_notional_usd").asDouble(Double.NaN);
                if (Double.isFinite(cap) && cap > 0 && cap <= 1 && Double.isFinite(notional) && notional > 0 && quote.size() == bars.size()) output.set("capacity_inputs", object().put("available_liquidity_usd", quote.stream().mapToDouble(Double::doubleValue).min().orElseThrow()).put("participation_cap", cap).put("order_notional_usd", notional).put("source", "BOUND_COMPLETED_BAR_QUOTE_VOLUME")); }
            ObjectNode liquidity = config.path("execution_liquidity_contract").isObject() ? (ObjectNode) config.path("execution_liquidity_contract") : config.path("liquidity_contract").isObject() ? (ObjectNode) config.path("liquidity_contract") : null;
            if (liquidity != null) { String model = textOr(first(liquidity, "model", "liquidity_model"), "").toUpperCase(Locale.ROOT); double notional = liquidity.path("order_notional_usd").asDouble(Double.NaN), impact = liquidity.path("observed_impact_bps").asDouble(0);
                if ("BOUND_COMPLETED_BAR_QUOTE_VOLUME".equals(model) && Double.isFinite(notional) && notional > 0 && Double.isFinite(impact) && impact >= 0 && quote.size() == bars.size()) output.set("liquidity_inputs", object().put("depth_usd", quote.stream().mapToDouble(Double::doubleValue).min().orElseThrow()).put("order_notional_usd", notional).put("observed_impact_bps", impact).put("source", "BOUND_COMPLETED_BAR_QUOTE_VOLUME")); }
            if (output.path("max_lifecycle_ms").asLong() <= 0) throw failure("EXECUTION raw input has no frozen lifecycle bound: " + identity);
            if (!"BINANCE_SPOT".equals(text(feature, "instrument").toUpperCase(Locale.ROOT))) { List<ObjectNode> marks = markPaths.get(identity); if (marks == null || marks.isEmpty()) throw failure("EXECUTION physical input has no separately bound mark path: " + identity);
                if (marks.size() != bars.size()) throw failure("EXECUTION physical mark path is not aligned: " + identity); for (int index = 0; index < marks.size(); index++) if (rowTime(marks.get(index)) != rowTime((ObjectNode) bars.get(index))) throw failure("EXECUTION physical mark path is not aligned: " + identity); output.set("mark_bars", array(marks)); }
            result.add(output);
        }
        return roleSort(result);
    }

    private static ObjectNode roleIdentityRow(ObjectNode feature) { return object().put("asset", text(feature, "asset")).put("venue", text(feature, "venue"))
            .put("instrument", text(feature, "instrument")).put("symbol", text(feature, "symbol")).put("signal_id", text(feature, "signal_id"))
            .put("episode_id", text(feature, "episode_id")).put("decision_time", text(feature, "decision_time")); }

    private static List<ObjectNode> deriveMarkRoleRows(List<ObjectNode> rows) {
        List<ObjectNode> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (ObjectNode raw : rows) { rejectRawDerivedFields(raw, "MARK", Set.of()); if (text(raw, "series_id").isEmpty() || !raw.hasNonNull("cadence_ms")) throw failure("MARK raw input lacks an explicit series identity/cadence");
            long event = rowTime(raw); String identity = predictorAsset(raw) + "|" + text(raw, "venue").toUpperCase(Locale.ROOT) + "|" + text(raw, "instrument").toUpperCase(Locale.ROOT) + "|" + text(raw, "symbol").toUpperCase(Locale.ROOT) + "|" + text(raw, "series_id") + "|" + event;
            if (!seen.add(identity)) throw failure("MARK raw input has duplicate physical identity: " + identity); ObjectNode output = object(); for (String field : RAW_MARK_FIELDS) if (raw.has(field)) output.set(field, raw.get(field).deepCopy());
            output.put("asset", predictorAsset(raw)).put("venue", text(raw, "venue").toUpperCase(Locale.ROOT)).put("instrument", text(raw, "instrument").toUpperCase(Locale.ROOT)).put("symbol", text(raw, "symbol").toUpperCase(Locale.ROOT))
                    .put("series_role", "MARK").put("event_time", iso(event)).put("availability_time", iso(rowAvailability(raw))); result.add(output); }
        result.sort(Comparator.comparing(row -> text(row, "asset") + "|" + text(row, "venue") + "|" + text(row, "instrument") + "|" + text(row, "symbol") + "|" + text(row, "series_id") + "|" + text(row, "event_time"))); return result;
    }

    private static Map<String, List<ObjectNode>> buildOpportunityMarkPaths(List<RoleBoundSource> bounds, List<ObjectNode> features, ObjectNode envelope) {
        Map<String, List<ObjectNode>> result = new HashMap<>();
        for (RoleBoundSource bound : bounds) {
            if (!"HYDRATION".equals(bound.sourceKind()) || !text(bound.partition(), "path").equals(text(bound.capture().path("mark_partition"), "path"))) continue;
            List<ObjectNode> raw; try { raw = readJsonlBytes(PathConfinement.readSinglyLinkedFile(bound.path(), "MARK source partition"), "MARK source partition"); } catch (RuntimeException error) { throw failure("MARK source partition cannot be reopened: " + error.getMessage()); }
            List<ObjectNode> rows = raw.stream().map(row -> { ObjectNode mark = row.deepCopy(); double price = numeric(first(row, "price", "mark_close", "close")); mark.put("event_time", iso(rowTime(row))).put("availability_time", iso(rowAvailability(row)))
                    .put("mark_open", first(row, "mark_open", "open", "price").asDouble(price)).put("mark_high", first(row, "mark_high", "high", "price").asDouble(price)).put("mark_low", first(row, "mark_low", "low", "price").asDouble(price)).put("mark_close", first(row, "mark_close", "close", "price").asDouble(price)); return mark; }).sorted(Comparator.comparingLong(StrategyResearchDataV5::rowTime)).toList();
            HydrationWindow window = hydrationWindow(bound.capture()); long max = bound.capture().path("max_lifecycle_ms").asLong(envelope.path("max_lifecycle_ms").asLong());
            for (ObjectNode feature : features) if (window.matches(feature)) { long decision = time(feature.get("decision_time")), last = Math.min(window.end(), decision + max); List<ObjectNode> path = rows.stream().filter(row -> rowTime(row) >= decision && rowTime(row) <= last).toList(); String identity = physicalOpportunityIdentity(feature);
                if (result.containsKey(identity)) throw failure("MARK physical input has duplicate opportunity identity: " + identity); if (path.isEmpty() || rowTime(path.get(0)) != decision) throw failure("MARK physical input lacks the exact next-bar entry for " + identity); result.put(identity, path); }
        }
        return result;
    }

    private static List<ObjectNode> roleSort(List<ObjectNode> rows) { return rows.stream().map(ObjectNode::deepCopy).sorted(Comparator
            .comparing((ObjectNode row) -> predictorAsset(row) + "|" + text(row, "venue").toUpperCase(Locale.ROOT) + "|" + text(row, "instrument").toUpperCase(Locale.ROOT) + "|" + text(row, "symbol").toUpperCase(Locale.ROOT) + "|" + time(first(row, "decision_time", "event_time", "open_time")))
            .thenComparing(row -> textOr(first(row, "series_id", "signal_id", "episode_id"), ""))).toList(); }

    /* ------------------------------------------------------------------ */
    /* Shared native helpers                                               */
    /* ------------------------------------------------------------------ */

    private record Bounds(long start, long end, long cutoff) {}

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(message);
    }

    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static ArrayNode array() { return JSON.createArrayNode(); }
    private static ArrayNode array(List<? extends JsonNode> values) {
        ArrayNode result = array(); if (values != null) values.forEach(result::add); return result;
    }
    private static ArrayNode strings(List<String> values) {
        ArrayNode result = array(); if (values != null) values.forEach(value -> {
            if (value == null) result.addNull(); else result.add(value);
        }); return result;
    }

    private static JsonNode field(ObjectNode value, String name) {
        return value == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : value.path(name);
    }

    private static JsonNode first(JsonNode value, String... names) {
        if (value == null || !value.isObject()) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        for (String name : names) if (value.has(name) && !value.get(name).isMissingNode()) return value.get(name);
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static String text(JsonNode value, String name) { return textValue(value == null ? null : value.get(name)); }
    private static String text(Object value, String name) {
        return value instanceof JsonNode node ? text(node, name) : "";
    }
    private static String textValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        return value.isTextual() ? value.textValue() : value.asText();
    }
    private static String textOr(JsonNode value, String fallback) {
        String result = textValue(value); return result.isEmpty() ? fallback : result;
    }
    private static boolean defined(JsonNode value) {
        return value != null && !value.isMissingNode() && !value.isNull();
    }

    private static double number(JsonNode value) {
        double result = value == null || value.isNull() || value.isMissingNode() ? Double.NaN
                : value.isNumber() ? value.doubleValue() : parseDouble(value.asText());
        if (!Double.isFinite(result)) throw failure("number is not finite");
        return result;
    }
    private static double numeric(JsonNode value) { return number(value); }
    private static double finiteNumber(JsonNode value, String message) {
        double result = value == null || value.isNull() || value.isMissingNode() ? Double.NaN
                : value.isNumber() ? value.doubleValue() : parseDouble(value.asText());
        if (!Double.isFinite(result)) throw failure(message); return result;
    }
    private static double parseDouble(String value) {
        try { return Double.parseDouble(value); } catch (RuntimeException error) { return Double.NaN; }
    }
    private static long integer(JsonNode value, String label) {
        if (value == null || value.isNull() || value.isMissingNode()) throw failure(label + " is invalid");
        if (value.isIntegralNumber()) return value.longValue();
        double number = value.isNumber() ? value.doubleValue() : parseDouble(value.asText());
        if (!Double.isFinite(number) || number != Math.rint(number) || number < Long.MIN_VALUE || number > Long.MAX_VALUE) {
            throw failure(label + " is invalid");
        }
        return (long) number;
    }
    private static long integerOr(JsonNode value, long fallback) { return defined(value) ? integer(value, "integer") : fallback; }
    private static long positiveInteger(JsonNode value, boolean allowZero) {
        long result = integer(value, "positive integer");
        if (allowZero ? result < 0 : result <= 0) throw failure("positive integer is invalid");
        return result;
    }
    private static double finite(ObjectNode row, String field, boolean positive, String label) {
        double result = number(row.get(field));
        if (positive && !(result > 0)) throw failure(label + " record " + field + " is invalid");
        row.put(field, result); return result;
    }

    private static long time(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) throw failure("timestamp is invalid");
        if (value.isNumber()) {
            double number = value.doubleValue();
            if (!Double.isFinite(number)) throw failure("timestamp is invalid");
            return (long) number;
        }
        String source = value.asText();
        try { return Instant.parse(source).toEpochMilli(); }
        catch (DateTimeParseException ignored) {
            try { return OffsetDateTime.parse(source).toInstant().toEpochMilli(); }
            catch (DateTimeParseException error) { throw failure("timestamp is invalid: " + source); }
        }
    }
    private static String iso(long value) { return JS_ISO.format(Instant.ofEpochMilli(value)); }
    private static String iso(Long value) { if (value == null) throw failure("timestamp is invalid"); return iso(value.longValue()); }
    private static String iso(JsonNode value) { return iso(time(value)); }
    private static long firstOrNow(ObjectNode options, String field) {
        return options != null && defined(options.get(field)) ? time(options.get(field)) : System.currentTimeMillis();
    }
    private static long rowTime(ObjectNode row) { return time(first(row, "event_time", "time", "open_time", "decision_time")); }
    private static long rowAvailability(ObjectNode row) { return time(first(row, "availability_time", "available_at", "close_time", "event_time", "time")); }

    private static long timeframeMilliseconds(String value) {
        Matcher match = TIMEFRAME.matcher(value == null ? "" : value);
        if (!match.matches()) throw failure("unsupported lifecycle timeframe " + value);
        long count;
        try { count = Long.parseLong(match.group(1)); }
        catch (NumberFormatException error) { throw failure("unsupported lifecycle timeframe " + value); }
        return Math.multiplyExact(count, switch (match.group(2).toLowerCase(Locale.ROOT)) {
            case "m" -> ONE_MINUTE; case "h" -> 60L * ONE_MINUTE; case "d" -> 24L * 60 * ONE_MINUTE;
            default -> throw failure("unsupported lifecycle timeframe " + value);
        });
    }

    private static Bounds completedBounds(long asOf, String interval, int years) {
        long step = timeframeMilliseconds(interval);
        long cutoff = Math.floorDiv(asOf, step) * step;
        long end = cutoff - step;
        Instant endInstant = Instant.ofEpochMilli(end);
        LocalDateTime shifted = LocalDateTime.ofInstant(endInstant, ZoneOffset.UTC).minusYears(years);
        long rawStart = shifted.toInstant(ZoneOffset.UTC).toEpochMilli();
        long start = Math.floorDiv(rawStart, step) * step;
        return new Bounds(start, end, cutoff);
    }

    private static List<ObjectNode> objects(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return List.of();
        if (!value.isArray()) throw failure("value must be an array");
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode node : value) {
            if (!node.isObject()) throw failure("array must contain objects");
            result.add((ObjectNode) node);
        }
        return result;
    }
    private static List<String> texts(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return List.of();
        if (!value.isArray()) throw failure("value must be an array");
        List<String> result = new ArrayList<>(); value.forEach(node -> result.add(textValue(node))); return result;
    }
    private static List<String> uniqueSortedTexts(JsonNode value) {
        List<String> result = texts(value).stream().distinct().sorted().toList();
        if (result.size() != value.size()) throw failure("array values must be unique");
        return result;
    }
    private static List<String> uniqueSortedTextsOrEmpty(JsonNode value) {
        if (!defined(value)) return List.of(); return texts(value).stream().distinct().sorted().toList();
    }
    private static List<String> uniqueTextsOrEmpty(JsonNode value) {
        if (!defined(value)) return List.of(); return texts(value);
    }
    private static List<String> uniqueNonEmpty(List<?> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) for (Object value : values) if (value != null && !String.valueOf(value).isEmpty()) result.add(String.valueOf(value));
        return new ArrayList<>(result);
    }
    @SafeVarargs private static <T> List<T> concat(List<T>... lists) {
        List<T> result = new ArrayList<>(); if (lists != null) for (List<T> list : lists) if (list != null) result.addAll(list); return result;
    }
    private static List<ObjectNode> concatNodes(JsonNode... arrays) {
        List<ObjectNode> result = new ArrayList<>(); if (arrays != null) for (JsonNode value : arrays) result.addAll(objects(value)); return result;
    }

    private static String requireSha(String value, String label) {
        if (!isSha(value)) throw failure(label + " is invalid"); return value;
    }
    private static boolean isSha(String value) { return value != null && SHA_256.matcher(value).matches(); }
    private static String requireAsset(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (!DATA_V5_ASSETS.contains(normalized)) throw failure("unsupported v5 asset " + value); return normalized;
    }
    private static String requireRole(String value) {
        String role = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!Set.of("FEATURE", "LABEL", "EXECUTION", "MARK").contains(role)) throw failure("unsupported artifact role " + value);
        return role;
    }
    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }

    private static ObjectNode requiredObject(ObjectNode options, String name) {
        JsonNode value = options == null ? null : options.get(name);
        if (value == null || !value.isObject()) throw failure(name + " object is required"); return (ObjectNode) value;
    }
    private static Path requiredPath(ObjectNode options, String name) {
        String value = text(options, name); if (value.isEmpty()) throw failure(name + " is required"); return Path.of(value).toAbsolutePath().normalize();
    }

    private static void putIfMissing(ObjectNode target, String name, boolean value) { if (!target.has(name)) target.put(name, value); }
    private static void putIfMissing(ObjectNode target, String name, long value) { if (!target.has(name)) target.put(name, value); }
    private static void putIfMissing(ObjectNode target, String name, String value) { if (!target.has(name)) target.put(name, value); }
    private static void putNullable(ObjectNode target, String name, String value) { if (value == null) target.putNull(name); else target.put(name, value); }
    private static void copyNullable(ObjectNode target, String name, JsonNode value) {
        if (!defined(value)) target.putNull(name); else target.set(name, value.deepCopy());
    }
    private static JsonNode nullIfMissing(JsonNode value) { return defined(value) ? value : NullNode.instance; }
    private static void copyTextIfPresent(ObjectNode target, ObjectNode source, String name) {
        if (source != null && source.has(name)) target.set(name, source.get(name).deepCopy());
    }

    private static boolean captureComplete(ObjectNode capture) {
        return !capture.path("unavailable").asBoolean(false) && capture.path("coverage").path("complete").asBoolean(false)
                && "STAGING".equals(text(capture.path("partition"), "storage_role"));
    }
    private static String seriesKey(ObjectNode series) {
        return text(series, "asset").toLowerCase(Locale.ROOT) + "|"
                + text(series, "instrument").toUpperCase(Locale.ROOT) + "|"
                + text(series, "symbol").toUpperCase(Locale.ROOT) + "|" + text(series, "interval") + "|"
                + textOr(first(series, "series_type", "series_role"), "").toLowerCase(Locale.ROOT);
    }
    private static String scopeFor(List<ObjectNode> declarations, String interval, String type) {
        List<ObjectNode> matches = declarations.stream()
                .filter(row -> interval.equals(text(row, "interval")) && texts(row.path("series_types")).contains(type))
                .toList();
        return !matches.isEmpty() && matches.stream().allMatch(row -> row.path("context_only").asBoolean(false))
                ? "CONTEXT_ONLY" : null;
    }
    private static void verifyCompletionTuple(ObjectNode manifest, List<ObjectNode> captures) {
        ObjectNode copy = manifest.deepCopy(); populateAcquisitionCompletionDefaults(copy);
        for (String field : List.of("base_complete", "declared_complete", "full_plan_complete", "completion_scope",
                "required_series_count", "required_complete_count", "optional_series_count", "optional_complete_count",
                "optional_complete", "unavailable_required", "unavailable_optional")) {
            if (!manifest.has(field) || !stable(manifest.get(field)).equals(stable(copy.get(field)))) {
                throw failure("acquisition completion contract field " + field + " is missing or inconsistent with its physical captures");
            }
        }
    }

    private static void assertOwnHash(JsonNode value, String schema, String label) {
        if (value == null || !value.isObject() || !schema.equals(text(value, "schema"))
                || !text(value, "content_sha256").equals(ownHash(value))) throw failure(label + " is missing or tampered");
        SCHEMAS.validateContractSchema(value);
    }
    private static void assertHashBinding(ObjectNode value, String expected, String label) {
        if (value == null || !expected.equals(text(value, "content_sha256")) || !expected.equals(ownHash(value))) {
            throw failure(label + " is missing or tampered");
        }
    }

    private static String portableReference(String value) {
        return value == null || value.isEmpty() ? "strategy-research/v5-data" : value.replace('\\', '/');
    }
    private static String portableReference(Path root, String explicit) {
        return explicit == null || explicit.isEmpty() ? portableReference(root.toString()) : portableReference(explicit);
    }

    private static Path verifiedRegularPath(Path root, String relative, String label) {
        return PathConfinement.resolve(root, relative, label, PathConfinement.ExpectedType.FILE).absolute();
    }
    private static byte[] readPhysical(Path root, String relative, String label) {
        return PathConfinement.readSinglyLinkedFile(root, relative, label);
    }
    private static ObjectNode readObject(Path path, String label) {
        try {
            JsonNode result = JSON.readTree(PathConfinement.readSinglyLinkedFile(path, label));
            if (!result.isObject()) throw failure(label + " must contain a JSON object"); return (ObjectNode) result;
        } catch (IOException error) { throw failure(label + " is invalid JSON: " + error.getMessage()); }
    }
    private static List<ObjectNode> readJsonl(Path path) { return readJsonlBytes(PathConfinement.readSinglyLinkedFile(path, "JSONL artifact"), "JSONL artifact"); }
    private static List<ObjectNode> readJsonlBytes(byte[] bytes, String label) {
        List<ObjectNode> result = new ArrayList<>(); String source = new String(bytes, StandardCharsets.UTF_8);
        for (String line : source.split("\\R")) {
            if (line.isBlank()) continue;
            try { JsonNode value = JSON.readTree(line); if (!value.isObject()) throw failure(label + " row is not an object"); result.add((ObjectNode) value); }
            catch (IOException error) { throw failure(label + " is invalid JSONL: " + error.getMessage()); }
        }
        return result;
    }
    private static ObjectNode verifyPhysicalJsonReference(Path root, ObjectNode reference, String expected, String label) {
        if (reference == null || text(reference, "path").isEmpty() || !isSha(text(reference, "content_sha256")) || !isSha(text(reference, "byte_sha256"))) throw failure(label + " must include a path, content hash, and byte hash");
        byte[] bytes = readPhysical(root, text(reference, "path"), label);
        if (reference.hasNonNull("byte_sha256") && !hash(bytes).equals(text(reference, "byte_sha256"))) throw failure(label + " bytes are missing or tampered");
        ObjectNode value;
        try { JsonNode parsed = JSON.readTree(bytes); if (!parsed.isObject()) throw failure(label + " JSON is invalid"); value = (ObjectNode) parsed; }
        catch (IOException error) { throw failure(label + " JSON is invalid: " + error.getMessage()); }
        if (!text(value, "content_sha256").equals(ownHash(value))) throw failure(label + " content hash is invalid");
        if (expected != null && !expected.isEmpty() && !expected.equals(text(value, "content_sha256"))) throw failure(label + " content hash binding is invalid");
        if (reference.hasNonNull("content_sha256") && !text(reference, "content_sha256").equals(text(value, "content_sha256"))) throw failure(label + " content hash binding is invalid");
        SCHEMAS.validateContractSchema(value); return value;
    }

    private static byte[] prettyBytes(ObjectNode value) {
        try { return (JSON.writer(new NodePrettyPrinter()).writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8); }
        catch (JsonProcessingException error) { throw failure("JSON serialization failed: " + error.getMessage()); }
    }
    private static final class NodePrettyPrinter extends com.fasterxml.jackson.core.util.DefaultPrettyPrinter {
        NodePrettyPrinter() { super(); indentArraysWith(new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n")); _arrayEmptySeparator = ""; }
        @Override public void writeObjectFieldValueSeparator(com.fasterxml.jackson.core.JsonGenerator generator) throws IOException { generator.writeRaw(": "); }
        @Override public NodePrettyPrinter createInstance() { return new NodePrettyPrinter(); }
    }
    private static Path writablePath(Path root, String relative, String label) {
        String confined = PathConfinement.repositoryRelativePath(relative, label); Path base = root.toAbsolutePath().normalize();
        try { Files.createDirectories(base); base = base.toRealPath(); }
        catch (IOException error) { throw failure(label + " root cannot be created: " + error.getMessage()); }
        Path current = base;
        String[] parts = confined.split("/");
        for (int index = 0; index < parts.length - 1; index++) {
            current = current.resolve(parts[index]);
            try {
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw failure(label + " contains a symlink or non-directory component");
                } else Files.createDirectory(current);
            } catch (IOException error) { throw failure(label + " parent cannot be created: " + error.getMessage()); }
        }
        Path result = current.resolve(parts[parts.length - 1]).normalize();
        if (!result.startsWith(base)) throw failure(label + " escapes its approved root"); return result;
    }
    private static void writeContentAddressed(Path root, String relative, byte[] bytes, String label) {
        Path target = writablePath(root, relative, label);
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                byte[] existing = PathConfinement.readSinglyLinkedFile(target, label);
                if (!Arrays.equals(existing, bytes)) throw failure(label + " content-addressed collision: " + relative);
                return;
            }
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException error) { throw failure(label + " cannot be written: " + error.getMessage()); }
    }
    private static ObjectNode persistPhysicalBytes(Path root, String relative, byte[] bytes, String label) {
        writeContentAddressed(root, relative, bytes, label);
        return object().put("path", relative).put("byte_sha256", hash(bytes)).put("bytes", bytes.length);
    }
    private static ObjectNode persistPhysicalJsonInput(Path root, ObjectNode value, String expected, String label) {
        assertHashBinding(value, expected, label); byte[] bytes = prettyBytes(value);
        String relative = "lineage/inputs/" + label.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(Locale.ROOT) + "-" + expected + ".json";
        writeContentAddressed(root, relative, bytes, label);
        return object().put("path", relative).put("content_sha256", expected).put("byte_sha256", hash(bytes)).put("bytes", bytes.length);
    }
    /**
     * Runtime producer provenance is the loaded Java class bytes.  This keeps
     * authoritative output self-contained after the Node toolchain retires;
     * the public DATA_V5_PRODUCER_CODE_SHA256 remains only the frozen legacy
     * Node compatibility hash and is never used to locate or verify runtime
     * producer bytes.
     */
    private static byte[] javaProducerBytes() {
        String resource = "/" + StrategyResearchDataV5.class.getName().replace('.', '/') + ".class";
        try (InputStream input = StrategyResearchDataV5.class.getResourceAsStream(resource)) {
            if (input == null) throw failure("Java producer class resource is unavailable: " + resource);
            return input.readAllBytes();
        } catch (IOException error) {
            throw failure("Java producer class resource cannot be read: " + error.getMessage());
        }
    }

    static String javaProducerCodeSha256() {
        return hash(javaProducerBytes());
    }

    private static byte[] javaAdapterBytes() {
        String resource = "/" + PublicDataAdapters.class.getName().replace('.', '/') + ".class";
        try (InputStream input = PublicDataAdapters.class.getResourceAsStream(resource)) {
            if (input == null) throw failure("Java adapter class resource is unavailable: " + resource);
            return input.readAllBytes();
        } catch (IOException error) {
            throw failure("Java adapter class resource cannot be read: " + error.getMessage());
        }
    }

    static String javaAdapterCodeSha256() {
        return hash(javaAdapterBytes());
    }

    private static ObjectNode persistJavaAdapterReference(Path root, String label) {
        byte[] bytes = javaAdapterBytes();
        return persistPhysicalBytes(root, "lineage/adapter-code/public-data-adapters-" + javaAdapterCodeSha256() + ".class", bytes, label + " adapter code");
    }

    private static List<String> hashInventory(JsonNode value) {
        if (!defined(value)) return List.of();
        List<String> values = value.isArray() ? texts(value) : List.of(textValue(value));
        List<String> result = values.stream().filter(item -> !item.isEmpty()).sorted().toList();
        if (result.stream().anyMatch(item -> !isSha(item))) throw failure("SHA inventory is invalid"); return result;
    }
    private static ObjectNode inventoryPartition(JsonNode value) {
        if (value == null || !value.isObject()) return null;
        ObjectNode result = object();
        for (String field : List.of("path", "sha256", "bytes", "row_count", "format", "storage_role")) copyNullable(result, field, value.get(field));
        return result;
    }
    private static ArrayNode receiptInventory(JsonNode receipts) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode receipt : objects(receipts)) {
            ObjectNode row = object().put("path", text(receipt, "path"))
                    .put("content_sha256", textOr(first(receipt, "content_sha256", "sha256"), ""));
            copyNullable(row, "byte_sha256", receipt.get("byte_sha256")); result.add(row);
        }
        result.sort(Comparator.comparing(row -> text(row, "path"))); return array(result);
    }
    private static void sortArray(ArrayNode source, Comparator<JsonNode> comparator) {
        List<JsonNode> values = new ArrayList<>(); source.forEach(values::add); values.sort(comparator); source.removeAll(); values.forEach(source::add);
    }

    private static void validateMetadataSourceBinding(ObjectNode options, String kind, String status) {
        if (!Set.of("PUBLIC_OBSERVED", "USER_BOUND").contains(status)) return;
        String receipt = requireSha(text(options, "sourceReceiptSha256"), kind + ".source_receipt_sha256");
        List<String> bytes = hashInventory(options.get("sourceByteSha256"));
        if (bytes.isEmpty()) throw failure(kind + ".source_byte_sha256 is invalid");
        JsonNode source = options.path("source");
        String boundReceipt = textOr(first(source, "content_sha256", "sha256"), "");
        List<String> boundBytes = hashInventory(first(source, "byte_sha256", "source_byte_sha256"));
        if (!receipt.equals(boundReceipt) || !bytes.equals(boundBytes)) throw failure(kind + " metadata source receipt and physical source-byte hashes are not bound");
    }
    private static void verifyCaptureSeriesBinding(ObjectNode capture, ObjectNode series, String planSha) {
        if (!seriesKey(capture).equals(seriesKey(series))) throw failure("acquisition capture identity differs from the frozen plan series: " + seriesKey(capture));
        if (!hash(series).equals(text(capture, "series_sha256"))) throw failure("acquisition capture series SHA differs from the frozen plan series: " + seriesKey(series));
        if (!isSha(planSha)) throw failure("acquisition capture plan SHA is invalid");
    }
    private static void verifyAcquisitionPartitionRows(ObjectNode capture, List<ObjectNode> rows, ObjectNode series) {
        boolean strict = series != null || capture.hasNonNull("series_sha256"); if (!strict) return;
        ObjectNode expected = series == null ? capture : series;
        if (rows.isEmpty()) throw failure("acquisition partition has no rows: " + seriesKey(expected));
        for (int index = 0; index < rows.size(); index++) {
            ObjectNode row = rows.get(index);
            for (String field : ACQUISITION_SERIES_IDENTITY_FIELDS) {
                JsonNode actual = "interval".equals(field) ? first(row, "interval", "timeframe") : row.get(field);
                if ("series_type".equals(field) && !defined(actual)) continue;
                if (!textValue(actual).equalsIgnoreCase(text(expected, field))) throw failure("acquisition partition row " + (index + 1) + " " + field + " differs from its capture/plan series: " + seriesKey(expected));
            }
            long rawEvent = time(first(row, "raw_event_time", "event_time")); long availability = time(row.get("availability_time"));
            if (availability < rawEvent) throw failure("acquisition partition row " + (index + 1) + " has invalid event/availability time: " + seriesKey(expected));
        }
    }
}
