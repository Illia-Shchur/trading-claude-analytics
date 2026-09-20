package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.marketdata.CoinalyzeDailyData;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Additive daily liquidation-stress diagnostics for the frozen v002 research precommit.
 * This computes only a daily gate feature; it does not create trade candidates or outcomes.
 */
public final class DailyStressPreflightV1 {
    public static final String INPUT_SCHEMA = "daily-stress-preflight-input/1";
    public static final String RESULT_SCHEMA = "daily-stress-preflight-result/1";
    public static final String ACQUISITION_SCHEMA = "coinalyze-daily-acquisition/1";
    public static final String PRECOMMIT_SCHEMA = "strategy-precommit/1";

    public static final LocalDate SOURCE_START = LocalDate.parse("2022-08-11");
    public static final LocalDate SOURCE_END_EXCLUSIVE = LocalDate.parse("2026-09-20");
    public static final Instant DECISION_START = Instant.parse("2022-11-11T00:00:00Z");
    public static final Instant DECISION_END_EXCLUSIVE = Instant.parse("2026-07-15T00:00:00Z");
    public static final Instant DATA_AS_OF = Instant.parse("2026-09-20T00:00:00Z");
    public static final int PRIOR_DAYS = 90;
    public static final int NEAREST_RANK_95_INDEX = 85;

    private static final List<String> ASSETS = List.of("BTC", "ETH", "SOL", "AAVE");
    private static final long MAX_INPUT_BYTES = 16L * 1024 * 1024;
    private static final long MAX_RAW_RESPONSE_BYTES = 16L * 1024 * 1024;
    private static final long MAX_TOTAL_RAW_RESPONSE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_RAW_RESPONSES = 64;
    private static final int MAX_ROWS = 12_000;
    private static final Set<String> HASH_KEYS = Set.of("path", "byte_sha256");

    private DailyStressPreflightV1() {}

    /** Reads physically bound references, recomputes the acquisition from its raw responses, and writes --out. */
    public static ObjectNode run(ObjectNode options) {
        Path inputPath = Path.of(requiredText(options, "input")).toAbsolutePath().normalize();
        Path outputPath = Path.of(requiredText(options, "out")).toAbsolutePath().normalize();
        if (inputPath.equals(outputPath)) throw new IllegalArgumentException("--out must differ from --input");
        byte[] inputBytes = readBounded(inputPath, "input", MAX_INPUT_BYTES);
        ObjectNode input = readObject(inputBytes, "daily stress input");
        ObjectNode precommitRef = object(input, "precommit_ref");
        ObjectNode acquisitionRef = object(input, "acquisition_manifest_ref");
        Path precommitPath = resolveRefPath(precommitRef, inputPath.getParent(), "precommit");
        Path acquisitionPath = resolveRefPath(acquisitionRef, inputPath.getParent(), "Coinalyze acquisition manifest");
        if (outputPath.equals(precommitPath) || outputPath.equals(acquisitionPath)) {
            throw new IllegalArgumentException("--out must not overwrite a bound input artifact");
        }

        byte[] precommitBytes = readBounded(precommitPath, "precommit", MAX_INPUT_BYTES);
        byte[] acquisitionBytes = readBounded(acquisitionPath, "Coinalyze acquisition manifest", MAX_INPUT_BYTES);
        ObjectNode precommit = readObject(precommitBytes, "precommit");
        ObjectNode rawManifest = readObject(acquisitionBytes, "Coinalyze acquisition manifest");
        validateRawReferenceBounds(rawManifest, acquisitionPath);
        String precommitByteSha = JsonHashes.sha256(precommitBytes);
        String acquisitionByteSha = JsonHashes.sha256(acquisitionBytes);
        if (!precommitRef.path("byte_sha256").asText().equals(precommitByteSha)) {
            throw new IllegalArgumentException("precommit byte SHA-256 mismatch");
        }
        if (!acquisitionRef.path("byte_sha256").asText().equals(acquisitionByteSha)) {
            throw new IllegalArgumentException("Coinalyze manifest byte SHA-256 mismatch");
        }

        final ObjectNode qualified;
        try {
            qualified = CoinalyzeDailyData.reopenAndQualify(acquisitionPath);
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot re-open Coinalyze daily acquisition: " + error.getMessage(), error);
        }
        ArrayNode rows = array(qualified, "rows");
        input.put("input_path", inputPath.toString());
        ObjectNode result = calculate(input, JsonHashes.sha256(inputBytes), precommit, precommitByteSha,
                rawManifest, acquisitionByteSha, rowsAsList(rows));
        writeOutputNew(outputPath, result);
        return result;
    }

    /**
     * Direct normalized-list entry point used by focused tests. Production callers use {@link #run(ObjectNode)}.
     */
    static ObjectNode calculate(ObjectNode input, String inputByteSha256, ObjectNode precommit,
            String verifiedPrecommitByteSha256, ObjectNode acquisition, String verifiedAcquisitionByteSha256,
            List<JsonNode> normalizedRows) {
        validateInput(input, inputByteSha256, verifiedPrecommitByteSha256, verifiedAcquisitionByteSha256);
        validatePrecommit(precommit);
        validateAcquisition(acquisition);
        ObjectNode precommitRef = object(input, "precommit_ref");
        ObjectNode acquisitionRef = object(input, "acquisition_manifest_ref");
        if (!validHash(precommit.path("content_sha256").asText())
                || !precommit.path("content_sha256").asText().equals(JsonHashes.ownHash(precommit))) {
            throw new IllegalArgumentException("precommit content SHA-256 mismatch");
        }
        if (!validHash(acquisition.path("content_sha256").asText())
                || !acquisition.path("content_sha256").asText().equals(JsonHashes.ownHash(acquisition))) {
            throw new IllegalArgumentException("Coinalyze manifest content SHA-256 mismatch");
        }

        Map<String, TreeMap<LocalDate, DailyRow>> byAsset = readRows(normalizedRows);
        ArrayNode eventRows = JsonHashes.mapper().createArrayNode();
        ArrayNode summaries = JsonHashes.mapper().createArrayNode();
        ArrayNode missingness = JsonHashes.mapper().createArrayNode();
        int totalEligible = 0;
        int totalBlocked = 0;
        for (String asset : ASSETS) {
            TreeMap<LocalDate, DailyRow> series = byAsset.get(asset);
            AssetSummary summary = summarize(asset, series, eventRows, missingness);
            summaries.add(summary.node());
            totalEligible += summary.eligibleDecisionDays;
            totalBlocked += summary.blockedDecisionDays;
        }

        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", RESULT_SCHEMA)
                .put("version", 1)
                .put("status", "BLOCKED")
                .put("stage", "DEVELOPMENT")
                .put("scope", "DAILY_LIQUIDATION_STRESS_GATE_ONLY")
                .put("input_path", requiredText(input, "input_path"))
                .put("input_byte_sha256", inputByteSha256)
                .put("precommit_path", precommitRef.path("path").asText())
                .put("precommit_byte_sha256", verifiedPrecommitByteSha256)
                .put("precommit_content_sha256", precommit.path("content_sha256").asText())
                .put("precommit_id", precommit.path("precommit_id").asText())
                .put("acquisition_manifest_path", acquisitionRef.path("path").asText())
                .put("acquisition_manifest_byte_sha256", verifiedAcquisitionByteSha256)
                .put("acquisition_manifest_content_sha256", acquisition.path("content_sha256").asText())
                .put("candidate_decision_days_across_assets", totalEligible + totalBlocked)
                .put("eligible_candidate_decision_days_across_assets", totalEligible)
                .put("blocked_candidate_days_across_assets", totalBlocked)
                .put("price_pnl_calculated", false)
                .put("trading_signals_generated", false)
                .put("candidate_generation_performed", false)
                .put("historical_publication_status", "UNKNOWN")
                .put("historical_revision_status", "UNKNOWN")
                .put("sixty_day_staged_executor_status", "NOT_QUALIFIED");

        ObjectNode policy = result.putObject("frozen_policy");
        policy.put("assets", result.putArray("allowed_assets").addAll(assetNodes()));
        policy.put("source_start", SOURCE_START.toString());
        policy.put("source_end_exclusive", SOURCE_END_EXCLUSIVE.toString());
        policy.put("decision_start", DECISION_START.toString());
        policy.put("decision_end_exclusive", DECISION_END_EXCLUSIVE.toString());
        policy.put("data_as_of", DATA_AS_OF.toString());
        policy.put("bucket_seconds", 86_400);
        policy.put("assumed_available_after_bucket_start_seconds", 172_800);
        policy.put("prior_contiguous_days", PRIOR_DAYS);
        policy.put("nearest_rank_percentile", 0.95);
        policy.put("nearest_rank_95_one_based_rank", 86);
        policy.put("side_comparison", "CURRENT_VALUE_POSITIVE_AND_STRICTLY_GREATER_THAN_PRIOR_P95");
        policy.put("missing_policy", "BLOCK_AFFECTED_WINDOW; NEVER_ZERO_FILL_OR_FORWARD_FILL");
        policy.put("publication_revision_provenance", "UNKNOWN");
        policy.put("availability_lag", "Assumed t+48h (completed UTC daily bucket plus 24h conservative lag); not verified historical publication evidence.");
        policy.put("structure_and_entry_timeframes", "Retained precommit context: completed 4h structure and later 1h entries; this preflight computes neither.");
        policy.put("holding_horizon_days", 60);
        policy.put("executor_qualification", "The 60-day staged executor and required physical execution inputs are not qualified.");
        policy.put("content_sha256", JsonHashes.ownHash(policy));
        result.set("asset_counts", summaries);
        result.set("missingness_by_asset", missingness);
        result.set("stress_events", eventRows);
        ArrayNode rawRefs = result.putArray("raw_response_references");
        copyRawReferences(acquisition, rawRefs);
        ArrayNode limitations = result.putArray("limitations");
        limitations.add("Historical Coinalyze publication time, capture completeness, and revision vintages are UNKNOWN; t+48h is a disclosed development assumption.");
        limitations.add("This is a liquidation gate diagnostic only; no price path, PnL, strategy signal, candidate, win rate, or deployment claim is produced.");
        limitations.add("The later completed-4h structure, 1h entry path, and 60-day staged execution lifecycle remain unqualified.");
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static AssetSummary summarize(String asset, TreeMap<LocalDate, DailyRow> series,
            ArrayNode events, ArrayNode missingness) {
        int expectedSourceDays = Math.toIntExact(ChronoUnit.DAYS.between(SOURCE_START, SOURCE_END_EXCLUSIVE));
        List<String> missingDates = new ArrayList<>();
        for (LocalDate date = SOURCE_START; date.isBefore(SOURCE_END_EXCLUSIVE); date = date.plusDays(1)) {
            if (!series.containsKey(date)) missingDates.add(date.toString());
        }

        int eligible = 0;
        int blockedCurrent = 0;
        int blockedPrior = 0;
        int longEvents = 0;
        int shortEvents = 0;
        Instant firstUsable = null;
        Instant lastUsable = null;
        for (Instant decisionAt = DECISION_START; decisionAt.isBefore(DECISION_END_EXCLUSIVE);
                decisionAt = decisionAt.plus(1, ChronoUnit.DAYS)) {
            LocalDate candidateBucket = LocalDate.ofInstant(decisionAt, ZoneOffset.UTC).minusDays(2);
            DailyRow current = series.get(candidateBucket);
            if (current == null) {
                blockedCurrent++;
                continue;
            }
            List<Double> priorLong = new ArrayList<>(PRIOR_DAYS);
            List<Double> priorShort = new ArrayList<>(PRIOR_DAYS);
            boolean complete = true;
            for (int lag = PRIOR_DAYS; lag >= 1; lag--) {
                DailyRow prior = series.get(candidateBucket.minusDays(lag));
                if (prior == null) {
                    complete = false;
                    break;
                }
                priorLong.add(prior.longLiquidationsUsd());
                priorShort.add(prior.shortLiquidationsUsd());
            }
            if (!complete) {
                blockedPrior++;
                continue;
            }

            Instant availableAt = candidateBucket.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (decisionAt.isBefore(availableAt)) {
                blockedPrior++;
                continue;
            }
            eligible++;
            if (firstUsable == null) firstUsable = decisionAt;
            lastUsable = decisionAt;
            double longP95 = nearestRank95(priorLong);
            double shortP95 = nearestRank95(priorShort);
            if (current.longLiquidationsUsd() > 0.0 && current.longLiquidationsUsd() > longP95) {
                events.add(event(asset, current, "LONG_LIQUIDATION_STRESS", current.longLiquidationsUsd(), longP95, availableAt));
                longEvents++;
            }
            if (current.shortLiquidationsUsd() > 0.0 && current.shortLiquidationsUsd() > shortP95) {
                events.add(event(asset, current, "SHORT_LIQUIDATION_STRESS", current.shortLiquidationsUsd(), shortP95, availableAt));
                shortEvents++;
            }
        }
        int candidateDays = Math.toIntExact(ChronoUnit.DAYS.between(DECISION_START, DECISION_END_EXCLUSIVE));
        int blocked = candidateDays - eligible;
        ObjectNode missing = JsonHashes.mapper().createObjectNode()
                .put("asset", asset)
                .put("expected_source_days", expectedSourceDays)
                .put("observed_source_records", series.size())
                .put("missing_source_records", missingDates.size())
                .put("candidate_decision_days", candidateDays)
                .put("eligible_decision_days", eligible)
                .put("blocked_decision_days", blocked)
                .put("blocked_because_current_record_missing", blockedCurrent)
                .put("blocked_because_prior_90_day_window_incomplete", blockedPrior);
        if (!missingDates.isEmpty()) {
            missing.put("first_missing_source_day", missingDates.get(0));
            missing.put("last_missing_source_day", missingDates.get(missingDates.size() - 1));
        } else {
            missing.putNull("first_missing_source_day");
            missing.putNull("last_missing_source_day");
        }
        ArrayNode missingList = missing.putArray("missing_source_days");
        missingDates.forEach(missingList::add);
        missingness.add(missing);

        return new AssetSummary(asset, series.size(), eligible, blocked, longEvents, shortEvents,
                firstUsable, lastUsable);
    }

    private static ObjectNode event(String asset, DailyRow row, String direction, double value, double p95,
            Instant availableAt) {
        return JsonHashes.mapper().createObjectNode()
                .put("asset", asset)
                .put("symbol", row.symbol())
                .put("bucket_start_utc", row.dayStartUtc())
                .put("stress_event_timestamp", availableAt.toString())
                .put("assumed_available_at", availableAt.toString())
                .put("decision_eligibility_check", "decision_at >= assumed_available_at")
                .put("direction", direction)
                .put("current_side_liquidations_usd", value)
                .put("prior_90_same_side_nearest_rank_p95_usd", p95);
    }

    private static double nearestRank95(List<Double> values) {
        if (values.size() != PRIOR_DAYS) throw new IllegalArgumentException("nearest-rank p95 requires exactly 90 prior observations");
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return sorted.get(NEAREST_RANK_95_INDEX);
    }

    private static Map<String, TreeMap<LocalDate, DailyRow>> readRows(List<JsonNode> rows) {
        if (rows == null || rows.size() > MAX_ROWS) throw new IllegalArgumentException("normalized daily row count exceeds bound");
        Map<String, TreeMap<LocalDate, DailyRow>> result = new LinkedHashMap<>();
        for (String asset : ASSETS) result.put(asset, new TreeMap<>());
        for (int i = 0; i < rows.size(); i++) {
            JsonNode raw = rows.get(i);
            String asset = requiredText(raw, "asset").toUpperCase(Locale.ROOT);
            if (!ASSETS.contains(asset)) throw new IllegalArgumentException("normalized row has an out-of-scope asset: " + asset);
            String symbol = requiredText(raw, "symbol").toUpperCase(Locale.ROOT);
            if (!symbol.startsWith(asset)) throw new IllegalArgumentException("normalized row symbol does not match asset: " + symbol);
            String dayStart = requiredText(raw, "day_start_utc");
            LocalDate date = parseUtcMidnight(dayStart);
            if (date.isBefore(SOURCE_START) || !date.isBefore(SOURCE_END_EXCLUSIVE)) {
                throw new IllegalArgumentException("normalized daily row is outside the frozen source window: " + date);
            }
            double longValue = numeric(raw, "long_liquidations_usd");
            double shortValue = numeric(raw, "short_liquidations_usd");
            DailyRow row = new DailyRow(asset, symbol, dayStart, longValue, shortValue);
            if (result.get(asset).putIfAbsent(date, row) != null) {
                throw new IllegalArgumentException("duplicate normalized daily row: " + asset + " " + date);
            }
        }
        return result;
    }

    private static void validateInput(ObjectNode input, String inputByteSha256,
            String verifiedPrecommitByteSha256, String verifiedAcquisitionByteSha256) {
        if (!INPUT_SCHEMA.equals(input.path("schema").asText())) throw new IllegalArgumentException("unsupported daily stress input schema");
        if (!validHash(inputByteSha256)) throw new IllegalArgumentException("daily stress input byte SHA-256 is invalid");
        ObjectNode precommit = object(input, "precommit_ref");
        ObjectNode acquisition = object(input, "acquisition_manifest_ref");
        validateHashReference(precommit, "precommit_ref", verifiedPrecommitByteSha256);
        validateHashReference(acquisition, "acquisition_manifest_ref", verifiedAcquisitionByteSha256);
    }

    private static void validateHashReference(ObjectNode ref, String label, String verifiedSha) {
        if (!HASH_KEYS.equals(fieldNames(ref))) throw new IllegalArgumentException(label + " must contain exactly path and byte_sha256");
        if (requiredText(ref, "path").isBlank() || !validHash(ref.path("byte_sha256").asText())) {
            throw new IllegalArgumentException(label + " path or byte SHA-256 is invalid");
        }
        if (!ref.path("byte_sha256").asText().equals(verifiedSha)) {
            throw new IllegalArgumentException(label + " byte SHA-256 mismatch");
        }
    }

    private static void validatePrecommit(ObjectNode precommit) {
        if (!PRECOMMIT_SCHEMA.equals(precommit.path("schema").asText())
                || !"FROZEN".equals(precommit.path("status").asText())) {
            throw new IllegalArgumentException("daily stress requires a FROZEN strategy-precommit/1");
        }
        Set<String> declared = textSet(precommit.path("trade_assets"), true);
        if (!declared.equals(Set.copyOf(ASSETS.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()))) {
            throw new IllegalArgumentException("precommit trade_assets differ from the frozen BTC/ETH/SOL/AAVE scope");
        }
        Set<String> required = textSet(precommit.path("experiment").path("required_assets"), true);
        if (!required.equals(declared)) throw new IllegalArgumentException("precommit required assets differ from trade_assets");
        ObjectNode stress = object(precommit, "daily_stress_contract");
        if (!"liquidation-daily-stress-policy/1".equals(stress.path("schema").asText())
                || !textSet(stress.path("assets"), false).equals(Set.copyOf(ASSETS))
                || !"BINANCE".equals(stress.path("venue").asText())
                || !"USDT_PERPETUAL".equals(stress.path("market_type").asText())
                || stress.path("bucket_seconds").asInt(-1) != 86_400
                || stress.path("publication_delay_after_bucket_close_seconds").asInt(-1) != 86_400
                || stress.path("modeled_available_after_bucket_start_seconds").asInt(-1) != 172_800
                || stress.path("prior_contiguous_days").asInt(-1) != PRIOR_DAYS
                || stress.path("percentile").asDouble(-1) != 0.95
                || !"nearest_rank".equals(stress.path("quantile_method").asText())
                || !"STRICT_GREATER_THAN_AND_POSITIVE".equals(stress.path("stress_comparison").asText())
                || !"BLOCK_WINDOW_NOT_ZERO".equals(stress.path("missing_policy").asText())
                || !"BINANCE_SPECIFIC_NOT_MARKET_WIDE".equals(stress.path("data_scope").asText())
                || !"RETROSPECTIVE_PROXY_DISCLOSED".equals(stress.path("source_authority").asText())
                || !stress.path("no_intraday_reconstruction").asBoolean(false)) {
            throw new IllegalArgumentException("precommit daily_stress_contract differs from the implemented frozen policy");
        }
        ObjectNode window = object(precommit, "research_window");
        if (!"2022-08-11T00:00:00Z".equals(window.path("source_start").asText())
                || !"2026-09-20T00:00:00Z".equals(window.path("source_end_exclusive").asText())
                || !DECISION_START.toString().equals(window.path("decision_start").asText())
                || !DECISION_END_EXCLUSIVE.toString().equals(window.path("decision_end_exclusive").asText())
                || !DATA_AS_OF.toString().equals(window.path("execution_end_exclusive").asText())
                || window.path("minimum_purge_days").asInt(-1) != 67
                || window.path("embargo_days").asInt(-1) != 7) {
            throw new IllegalArgumentException("precommit research_window differs from the frozen daily-stress dates");
        }
        if (precommit.path("holding_horizon").path("max").asInt(-1) != 60
                || precommit.path("user_constraints").path("max_holding_days").asInt(-1) != 60) {
            throw new IllegalArgumentException("precommit must preserve the 60-day maximum holding horizon");
        }
        if (!containsTimeframeForEveryAsset(precommit.path("feature_contract").path("series"), "4h")
                || !containsTimeframeForEveryAsset(precommit.path("feature_contract").path("series"), "1h")) {
            throw new IllegalArgumentException("precommit must retain completed 4h structure and 1h entry series for all four assets");
        }
        boolean proxyUnknown = false;
        for (JsonNode input : precommit.path("required_inputs")) {
            if ("liquidations".equals(input.path("input_id").asText())) {
                JsonNode pit = input.path("point_in_time");
                proxyUnknown = "PROXY_DISCLOSED".equals(pit.path("status").asText())
                        && !pit.path("historical_publication_verified").asBoolean(true)
                        && !pit.path("revision_vintages_verified").asBoolean(true);
            }
        }
        if (!proxyUnknown) throw new IllegalArgumentException("precommit must disclose UNKNOWN liquidation publication and revision provenance");
    }

    private static boolean containsTimeframeForEveryAsset(JsonNode series, String timeframe) {
        Set<String> found = new HashSet<>();
        if (!series.isArray()) return false;
        for (JsonNode row : series) {
            if (timeframe.equals(row.path("timeframe").asText()) && !row.path("context_only").asBoolean(true)
                    && row.path("tradable").asBoolean(false)) {
                found.add(row.path("asset").asText().toLowerCase(Locale.ROOT));
            }
        }
        return found.containsAll(Set.of("btc", "eth", "sol", "aave"));
    }

    private static void validateAcquisition(ObjectNode acquisition) {
        if (!ACQUISITION_SCHEMA.equals(acquisition.path("schema").asText())) {
            throw new IllegalArgumentException("unsupported Coinalyze daily acquisition schema");
        }
        if (!SOURCE_START.toString().equals(datePart(acquisition.path("from_inclusive").asText()))
                || !SOURCE_END_EXCLUSIVE.toString().equals(datePart(acquisition.path("to_exclusive").asText()))
                || !DATA_AS_OF.toString().equals(Instant.parse(acquisition.path("as_of").asText()).toString())) {
            throw new IllegalArgumentException("Coinalyze acquisition window or as_of does not match the frozen precommit");
        }
        ArrayNode raw = array(acquisition, "raw_responses");
        if (raw.isEmpty() || raw.size() > MAX_RAW_RESPONSES) {
            throw new IllegalArgumentException("Coinalyze acquisition raw response count is missing or exceeds the bound");
        }
        for (JsonNode ref : raw) {
            requiredText(ref, "path");
            if (!validHash(ref.path("sha256").asText())) {
                throw new IllegalArgumentException("Coinalyze raw response has an invalid byte SHA-256");
            }
        }
    }

    private static String datePart(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing Coinalyze acquisition date");
        return value.length() >= 10 ? value.substring(0, 10) : value;
    }

    private static Set<String> textSet(JsonNode value, boolean lowercase) {
        if (!value.isArray()) throw new IllegalArgumentException("precommit asset scope must be an array");
        Set<String> result = new HashSet<>();
        for (JsonNode node : value) {
            if (!node.isTextual()) throw new IllegalArgumentException("precommit asset scope contains a non-text value");
            String text = lowercase ? node.asText().toLowerCase(Locale.ROOT) : node.asText().toUpperCase(Locale.ROOT);
            if (!result.add(text)) throw new IllegalArgumentException("precommit asset scope contains duplicates");
        }
        return result;
    }

    private static List<JsonNode> rowsAsList(JsonNode rows) {
        if (!rows.isArray() || rows.size() > MAX_ROWS) throw new IllegalArgumentException("normalized daily rows are missing or exceed the bound");
        List<JsonNode> result = new ArrayList<>(rows.size());
        rows.forEach(result::add);
        return result;
    }

    private static LocalDate parseUtcMidnight(String value) {
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value);
            if (!ZoneOffset.UTC.equals(parsed.getOffset()) || !parsed.toLocalTime().toString().equals("00:00")) {
                throw new IllegalArgumentException("daily bucket timestamp must be aligned to UTC midnight: " + value);
            }
            LocalDate date = parsed.toLocalDate();
            if (!(value.equals(date + "T00:00Z") || value.equals(date + "T00:00:00Z"))) {
                throw new IllegalArgumentException("daily bucket timestamp must use canonical UTC midnight: " + value);
            }
            return date;
        } catch (java.time.DateTimeException error) {
            throw new IllegalArgumentException("invalid daily bucket timestamp: " + value, error);
        }
    }

    private static double numeric(JsonNode value, String key) {
        JsonNode raw = value.get(key);
        if (raw == null || !raw.isNumber()) throw new IllegalArgumentException("daily row " + key + " must be numeric");
        double number = raw.doubleValue();
        if (!Double.isFinite(number) || number < 0.0) throw new IllegalArgumentException("daily row " + key + " must be finite and nonnegative");
        return number;
    }

    private static Path resolveRefPath(ObjectNode ref, Path relativeTo, String label) {
        Path raw = Path.of(requiredText(ref, "path"));
        Path resolved = raw.isAbsolute() ? raw : relativeTo.resolve(raw);
        return resolved.toAbsolutePath().normalize();
    }

    private static byte[] readBounded(Path path, String label, long maxBytes) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(label + " reference is not a regular non-symlink file: " + path);
            }
            long size = Files.size(path);
            if (size < 0 || size > maxBytes) throw new IllegalArgumentException(label + " file exceeds the configured size bound");
            return Files.readAllBytes(path);
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot read " + label + ": " + error.getMessage(), error);
        }
    }

    private static ObjectNode readObject(byte[] bytes, String label) {
        try {
            JsonNode value = JsonHashes.mapper().readTree(bytes);
            if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException(label + " must be a JSON object");
            return object;
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot parse " + label + ": " + error.getMessage(), error);
        }
    }

    private static void copyRawReferences(ObjectNode acquisition, ArrayNode target) {
        for (JsonNode raw : acquisition.path("raw_responses")) {
            ObjectNode ref = JsonHashes.mapper().createObjectNode();
            for (String key : List.of("kind", "endpoint", "path", "status", "captured_at", "bytes")) {
                if (raw.has(key)) ref.set(key, raw.get(key).deepCopy());
            }
            ref.put("byte_sha256", raw.path("sha256").asText());
            target.add(ref);
        }
    }

    static void validateRawReferenceBounds(ObjectNode manifest, Path manifestPath) {
        ArrayNode refs = array(manifest, "raw_responses");
        if (refs.isEmpty() || refs.size() > MAX_RAW_RESPONSES) {
            throw new IllegalArgumentException("Coinalyze raw response reference count is missing or exceeds the bound");
        }
        Path root = manifestPath.getParent();
        if (root == null || Files.isSymbolicLink(root)) throw new IllegalArgumentException("Coinalyze acquisition root is unsafe");
        Path rawDirectory = root.resolve("raw");
        if (Files.isSymbolicLink(rawDirectory) || !Files.isDirectory(rawDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Coinalyze raw response directory is missing or unsafe");
        }
        long totalBytes = 0;
        for (JsonNode ref : refs) {
            Path relative = Path.of(requiredText(ref, "path"));
            if (relative.isAbsolute() || relative.getNameCount() != 2 || !"raw".equals(relative.getName(0).toString())
                    || !relative.equals(relative.normalize())) {
                throw new IllegalArgumentException("Coinalyze raw response path escapes acquisition root");
            }
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Coinalyze raw response file is missing or unsafe");
            }
            final long size;
            try {
                size = Files.size(file);
            } catch (IOException error) {
                throw new IllegalArgumentException("cannot inspect Coinalyze raw response size", error);
            }
            if (size < 0 || size > MAX_RAW_RESPONSE_BYTES) {
                throw new IllegalArgumentException("Coinalyze raw response exceeds the per-file size bound");
            }
            totalBytes += size;
            if (totalBytes > MAX_TOTAL_RAW_RESPONSE_BYTES) {
                throw new IllegalArgumentException("Coinalyze raw responses exceed the aggregate size bound");
            }
        }
    }

    static void writeOutputNew(Path outputPath, ObjectNode result) {
        try {
            rejectSymlinkParents(outputPath);
            Path parent = outputPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            rejectSymlinkParents(outputPath);
            Files.writeString(outputPath, JsonHashes.mapper().writerWithDefaultPrettyPrinter()
                            .writeValueAsString(result) + "\n",
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException error) {
            throw new IllegalArgumentException("--out already exists; choose a new immutable output path", error);
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot write --out: " + error.getMessage(), error);
        }
    }

    private static void rejectSymlinkParents(Path outputPath) throws IOException {
        Path cursor = outputPath.getRoot();
        if (cursor == null) throw new IllegalArgumentException("--out must resolve to an absolute path");
        Set<Path> systemTempPrefixes = pathPrefixes(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize());
        for (Path component : outputPath) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor) && !systemTempPrefixes.contains(cursor)) {
                throw new IllegalArgumentException("--out path must not contain symlink components");
            }
        }
    }

    private static Set<Path> pathPrefixes(Path path) {
        Set<Path> result = new HashSet<>();
        Path cursor = path.getRoot();
        if (cursor == null) return result;
        for (Path component : path) {
            cursor = cursor.resolve(component);
            result.add(cursor);
        }
        return result;
    }

    private static ArrayNode assetNodes() {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        ASSETS.forEach(result::add);
        return result;
    }

    private static ArrayNode array(ObjectNode value, String field) {
        JsonNode node = value.get(field);
        if (!(node instanceof ArrayNode array)) throw new IllegalArgumentException("missing or invalid " + field + " array");
        return array;
    }

    private static ObjectNode object(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException("missing or invalid " + field + " object");
        return object;
    }

    private static String requiredText(JsonNode value, String field) {
        JsonNode raw = value.get(field);
        if (raw == null || !raw.isTextual() || raw.asText().isBlank()) throw new IllegalArgumentException("missing or invalid " + field);
        return raw.asText();
    }

    private static Set<String> fieldNames(ObjectNode value) {
        Set<String> result = new HashSet<>();
        value.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private record DailyRow(String asset, String symbol, String dayStartUtc, double longLiquidationsUsd,
                            double shortLiquidationsUsd) {}

    private record AssetSummary(String asset, int observedRows, int eligibleDecisionDays, int blockedDecisionDays,
                                int longStressCount, int shortStressCount, Instant firstUsableDecisionAt,
                                Instant lastUsableDecisionAt) {
        ObjectNode node() {
            ObjectNode result = JsonHashes.mapper().createObjectNode()
                    .put("asset", asset)
                    .put("observed_daily_records", observedRows)
                    .put("eligible_decision_days", eligibleDecisionDays)
                    .put("blocked_decision_days", blockedDecisionDays)
                    .put("long_liquidation_stress_count", longStressCount)
                    .put("short_liquidation_stress_count", shortStressCount);
            if (firstUsableDecisionAt == null) result.putNull("earliest_usable_decision_at");
            else result.put("earliest_usable_decision_at", firstUsableDecisionAt.toString());
            if (lastUsableDecisionAt == null) result.putNull("latest_usable_decision_at");
            else result.put("latest_usable_decision_at", lastUsableDecisionAt.toString());
            return result;
        }
    }
}
