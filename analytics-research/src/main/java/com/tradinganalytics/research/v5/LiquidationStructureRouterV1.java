package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Causal feature-only event router for the frozen liquidation-structure v002 rules.
 *
 * <p>It consumes only completed feature observations. It never reads labels, minute execution,
 * marks, funding, or account outcomes. The accounting/execution owner must execute a returned
 * intent strictly after {@code requestedExecutionAfter}, then acknowledge the realized fill or
 * non-fill. Daily availability is the later of the source's observation time and the frozen
 * bucket-start plus 48-hour research proxy.</p>
 */
public final class LiquidationStructureRouterV1 {
    public static final int DAILY_LOOKBACK_DAYS = 90;
    public static final int DAILY_P95_NEAREST_RANK_INDEX = 85;
    public static final int H4_ATR_PERIODS = 14;
    public static final int H4_ATR_WARMUP_BARS = 140;
    public static final int FAST_WINDOW_BARS = 6;
    public static final int SLOW_WINDOW_BARS = 18;
    public static final int PIVOT_LEFT_BARS = 2;
    public static final int PIVOT_RIGHT_BARS = 2;
    public static final int MAX_HOLDING_DAYS = 60;
    public static final double MAX_POSITION_RISK_FRACTION = 0.05;
    public static final double MAX_PORTFOLIO_RISK_FRACTION = 0.10;
    public static final Duration BRANCH_WAIT = Duration.ofHours(72);
    public static final Duration INITIAL_ZONE_WAIT = Duration.ofHours(72);
    public static final Duration OI_MINIMUM_LAG = Duration.ofMinutes(5);
    public static final Duration OI_MAXIMUM_STALENESS = Duration.ofMinutes(10);
    public static final Duration MAX_MACRO_AGE = Duration.ofDays(4);
    public static final Duration MAX_LIFECYCLE = Duration.ofDays(MAX_HOLDING_DAYS);
    public static final double STRESS_PERCENTILE = 0.95;
    public static final double OI_MINIMUM_DECLINE = 0.05;
    public static final double FAST_ATR_MULTIPLE = 2.0;
    public static final double SLOW_ATR_MULTIPLE = 4.0;
    public static final double INITIAL_ZONE_HALF_WIDTH_ATR = 0.25;
    public static final double STOP_BUFFER_ATR = 0.10;
    public static final double MAX_CHASE_ATR = 0.50;
    public static final double MACRO_THRESHOLD = 0.005;

    public static final List<String> SUPPORTED_ASSET_ORDER = List.of("BTC", "ETH", "SOL", "AAVE", "UNI", "BNB", "LINK", "ZEC", "TRX");
    private static final Set<String> ASSETS = Set.copyOf(SUPPORTED_ASSET_ORDER);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime NYSE_REGULAR_CLOSE = LocalTime.of(16, 0);
    private static final LocalTime NYSE_EARLY_CLOSE = LocalTime.of(13, 0);
    // Bounded exchange calendar verified against NYSE/ICE's published 2022-2026 schedules.
    // Sources: https://ir.theice.com/press/news-details/2021/NYSE-Group-Announces-2022-2023-and-2024-Holiday-and-Early-Closings-Calendar/default.aspx
    // https://ir.theice.com/press/news-details/2022/NYSE-Group-Announces-2023-2024-and-2025-Holiday-and-Early-Closings-Calendar/default.aspx
    // https://ir.theice.com/press/news-details/2023/NYSE-Group-Announces-2024-2025-and-2026-Holiday-and-Early-Closings-Calendar/default.aspx
    // https://ir.theice.com/press/news-details/2024/The-New-York-Stock-Exchange-Will-Close-Markets-on-January-9-to-Honor-the-Passing-of-Former-President-Jimmy-Carter-on-National-Day-of-Mourning/default.aspx
    private static final Map<Integer, Set<LocalDate>> NYSE_HOLIDAYS = Map.of(
            2022, dates("2022-01-17", "2022-02-21", "2022-04-15", "2022-05-30", "2022-06-20",
                    "2022-07-04", "2022-09-05", "2022-11-24", "2022-12-26"),
            2023, dates("2023-01-02", "2023-01-16", "2023-02-20", "2023-04-07", "2023-05-29",
                    "2023-06-19", "2023-07-04", "2023-09-04", "2023-11-23", "2023-12-25"),
            2024, dates("2024-01-01", "2024-01-15", "2024-02-19", "2024-03-29", "2024-05-27",
                    "2024-06-19", "2024-07-04", "2024-09-02", "2024-11-28", "2024-12-25"),
            2025, dates("2025-01-01", "2025-01-09", "2025-01-20", "2025-02-17", "2025-04-18",
                    "2025-05-26", "2025-06-19", "2025-07-04", "2025-09-01", "2025-11-27", "2025-12-25"),
            2026, dates("2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25",
                    "2026-06-19", "2026-07-03", "2026-09-07", "2026-11-26", "2026-12-25"));
    private static final Map<Integer, Set<LocalDate>> NYSE_EARLY_CLOSES = Map.of(
            2022, dates("2022-11-25"),
            2023, dates("2023-07-03", "2023-11-24"),
            2024, dates("2024-07-03", "2024-11-29", "2024-12-24"),
            2025, dates("2025-07-03", "2025-11-28", "2025-12-24"),
            2026, dates("2026-11-27", "2026-12-24"));
    private LiquidationStructureRouterV1() {}

    static ObjectNode bindSetupSeed(ObjectNode intentProjection, StageOneAnchorContext anchor) {
        Objects.requireNonNull(intentProjection); Objects.requireNonNull(anchor);
        if (!anchor.intent().intentId().equals(intentProjection.path("intent_id").asText())
                || !anchor.intent().setupId().equals(intentProjection.path("setup_id").asText())
                || !anchor.intent().pairId().equals(intentProjection.path("pair_id").asText())
                || intentProjection.path("stage").asInt(-1) != 1
                || !"ROUTED_REVERSAL_CONTINUATION".equals(intentProjection.path("variant").asText())) {
            throw new IllegalArgumentException("setup seed can only bind its exact routed stage-one intent projection");
        }
        ObjectNode result = intentProjection.deepCopy();
        result.remove("content_sha256");
        result.set("setup_seed", setupSeedJson(anchor.setupSeed()));
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    static ObjectNode stageOneAnchorJson(StageOneAnchorContext anchor) {
        ConfirmedIntent intent = anchor.intent();
        ObjectNode out = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-confirmed-entry-intent/1")
                .put("version", 1).put("intent_id", intent.intentId()).put("setup_id", intent.setupId())
                .put("pair_id", intent.pairId()).put("asset", intent.asset()).put("variant", intent.variant().name())
                .put("branch", intent.branch().name()).put("routed_branch", intent.routedBranch().name())
                .put("direction", intent.direction().name()).put("shock_direction", intent.shockDirection().name())
                .put("stage", intent.stage()).put("decision_time", intent.decisionTime().toString())
                .put("requested_execution_after", intent.requestedExecutionAfter().toString())
                .put("confirmation_bar_start", intent.confirmationBarStart().toString())
                .put("confirmation_close", intent.confirmationClose()).put("zone_center", intent.zoneCenter())
                .put("zone_lower", intent.zoneLower()).put("zone_upper", intent.zoneUpper())
                .put("pre_event_atr", intent.preEventAtr()).put("initial_stop", intent.initialStop())
                .put("max_chase_distance", intent.maxChaseDistance()).put("tranche_risk_fraction", intent.trancheRiskFraction())
                .put("proposed_leverage", intent.proposedLeverage()).put("maximum_holding_days", intent.maximumHoldingDays())
                .put("macro_state", intent.macroState().name()).put("macro_eligible", intent.macroEligible())
                .put("diagnostic_only", intent.diagnosticOnly());
        if (intent.pivotPrice() == null) out.putNull("pivot_price"); else out.put("pivot_price", intent.pivotPrice());
        if (intent.pivotTime() == null) out.putNull("pivot_time"); else out.put("pivot_time", intent.pivotTime().toString());
        if (intent.pivotConfirmedAt() == null) out.putNull("pivot_confirmed_at"); else out.put("pivot_confirmed_at", intent.pivotConfirmedAt().toString());
        if (intent.reversalTarget() == null) out.putNull("reversal_target"); else out.put("reversal_target", intent.reversalTarget());
        ArrayNode reasons = out.putArray("rejection_reasons"); intent.rejectionReasons().forEach(reasons::add);
        ArrayNode sources = out.putArray("source_evidence");
        for (SourceEvidence source : intent.sourceEvidence()) sources.add(sourceJson(source));
        out.put("content_sha256", JsonHashes.ownHash(out));
        return bindSetupSeed(out, anchor);
    }

    private static ObjectNode sourceJson(SourceEvidence source) {
        return JsonHashes.mapper().createObjectNode().put("role", source.role()).put("asset", source.asset())
                .put("series_id", source.seriesId()).put("event_time", source.eventTime().toString())
                .put("available_at", source.availableAt().toString());
    }

    private static ObjectNode setupSeedJson(AnchorSetupSeed seed) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("schema", AnchorSetupSeed.SCHEMA)
                .put("version", 1).put("setup_id", seed.setupId()).put("asset", seed.asset())
                .put("shock_direction", seed.shockDirection().name()).put("event_start", seed.eventStart().toString())
                .put("event_end", seed.eventEnd().toString()).put("available_at", seed.availableAt().toString())
                .put("pre_event_atr", seed.preEventAtr()).put("prior_high", seed.priorHigh())
                .put("prior_low", seed.priorLow()).put("boundary", seed.boundary())
                .put("recovery_target", seed.recoveryTarget()).put("zone_lower", seed.zoneLower())
                .put("zone_upper", seed.zoneUpper()).put("fast_qualifies", seed.fastQualifies())
                .put("slow_qualifies", seed.slowQualifies()).put("selected_geometry", seed.selectedGeometry());
        ArrayNode evidence = row.putArray("geometry_source_evidence");
        for (SourceEvidence source : seed.geometryEvidence()) {
            evidence.addObject().put("role", source.role()).put("asset", source.asset())
                    .put("series_id", source.seriesId()).put("event_time", source.eventTime().toString())
                    .put("available_at", source.availableAt().toString());
        }
        row.put("content_sha256", JsonHashes.ownHash(row));
        return row;
    }

    private static StageOneAnchorContext anchorContextFromJson(JsonNode raw) {
        if (raw == null || !raw.isObject() || !"liquidation-v2-confirmed-entry-intent/1".equals(raw.path("schema").asText())
                || raw.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(raw).equals(raw.path("content_sha256").asText())) {
            throw new IllegalArgumentException("frozen initial intent is not a valid self-hashed intent projection");
        }
        JsonNode seedNode = raw.path("setup_seed");
        if (!seedNode.isObject() || !AnchorSetupSeed.SCHEMA.equals(seedNode.path("schema").asText())
                || seedNode.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(seedNode).equals(seedNode.path("content_sha256").asText())) {
            throw new IllegalArgumentException("frozen initial intent lacks a valid immutable setup seed");
        }
        AnchorSetupSeed seed = new AnchorSetupSeed(text(seedNode, "setup_id"), text(seedNode, "asset"),
                Direction.valueOf(text(seedNode, "shock_direction")), instant(seedNode, "event_start"),
                instant(seedNode, "event_end"), instant(seedNode, "available_at"), number(seedNode, "pre_event_atr"),
                number(seedNode, "prior_high"), number(seedNode, "prior_low"), number(seedNode, "boundary"),
                number(seedNode, "recovery_target"), number(seedNode, "zone_lower"), number(seedNode, "zone_upper"),
                seedNode.path("fast_qualifies").asBoolean(false), seedNode.path("slow_qualifies").asBoolean(false),
                text(seedNode, "selected_geometry"), sourceEvidence(seedNode.path("geometry_source_evidence")));
        ConfirmedIntent intent = new ConfirmedIntent(text(raw, "intent_id"), text(raw, "setup_id"), text(raw, "pair_id"),
                text(raw, "asset"), Variant.valueOf(text(raw, "variant")), Branch.valueOf(text(raw, "branch")),
                Branch.valueOf(text(raw, "routed_branch")), Direction.valueOf(text(raw, "direction")),
                Direction.valueOf(text(raw, "shock_direction")), raw.path("stage").asInt(-1),
                instant(raw, "decision_time"), instant(raw, "requested_execution_after"),
                instant(raw, "confirmation_bar_start"), number(raw, "confirmation_close"), number(raw, "zone_center"),
                number(raw, "zone_lower"), number(raw, "zone_upper"), optionalNumber(raw, "pivot_price"),
                optionalInstant(raw, "pivot_time"), optionalInstant(raw, "pivot_confirmed_at"),
                number(raw, "pre_event_atr"), number(raw, "initial_stop"), optionalNumber(raw, "reversal_target"),
                number(raw, "max_chase_distance"), number(raw, "tranche_risk_fraction"),
                raw.path("proposed_leverage").asInt(-1), raw.path("maximum_holding_days").asInt(-1),
                MacroState.valueOf(text(raw, "macro_state")), raw.path("macro_eligible").asBoolean(false),
                raw.path("diagnostic_only").asBoolean(true), textArray(raw.path("rejection_reasons")),
                sourceEvidence(raw.path("source_evidence")));
        return new StageOneAnchorContext(intent, seed);
    }

    private static List<SourceEvidence> sourceEvidence(JsonNode rows) {
        if (rows == null || !rows.isArray()) throw new IllegalArgumentException("anchor source evidence must be an array");
        List<SourceEvidence> result = new ArrayList<>();
        for (JsonNode row : rows) result.add(new SourceEvidence(text(row, "role"), text(row, "asset"),
                text(row, "series_id"), instant(row, "event_time"), instant(row, "available_at")));
        return List.copyOf(result);
    }

    private static List<String> textArray(JsonNode rows) {
        if (rows == null || !rows.isArray()) throw new IllegalArgumentException("anchor text list must be an array");
        List<String> result = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isTextual()) throw new IllegalArgumentException("anchor text list contains a nontext value");
            result.add(row.asText());
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("anchor intent requires " + field);
        return value.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) throw new IllegalArgumentException("anchor intent requires ISO timestamp " + field);
        try { return Instant.parse(value.asText()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("anchor intent has invalid timestamp " + field); }
    }

    private static Instant optionalInstant(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("anchor optional timestamp must be ISO text " + field);
        try { return Instant.parse(value.asText()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("anchor has invalid optional timestamp " + field); }
    }

    private static double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) throw new IllegalArgumentException("anchor requires finite number " + field);
        return value.asDouble();
    }

    private static Double optionalNumber(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) throw new IllegalArgumentException("anchor optional number is invalid " + field);
        return value.asDouble();
    }

    public enum Variant {
        ROUTED_REVERSAL_CONTINUATION,
        ALWAYS_CONTINUATION_CONTROL,
        ALWAYS_REVERSAL_CONTROL,
        PRICE_OI_ONLY_EVENT_DIAGNOSTIC
    }

    public enum Timeframe {
        FOUR_HOUR(Duration.ofHours(4)),
        ONE_HOUR(Duration.ofHours(1));

        private final Duration duration;
        Timeframe(Duration duration) { this.duration = duration; }
        public Duration duration() { return duration; }
    }

    public enum Branch { CONTINUATION, REVERSAL }
    public enum Direction {
        LONG(1), SHORT(-1);
        private final int sign;
        Direction(int sign) { this.sign = sign; }
        public int sign() { return sign; }
        public Direction opposite() { return this == LONG ? SHORT : LONG; }
        static Direction fromMove(double move) {
            if (move > 0.0) return LONG;
            if (move < 0.0) return SHORT;
            return null;
        }
    }
    public enum MacroState { SUPPORTIVE, NEUTRAL, OPPOSING, UNKNOWN, NOT_REQUIRED }
    /** Candidate-level policy for the stage-2/3 macro gate; structural routing remains unchanged. */
    public enum MacroGatePolicy { REQUIRE_MACRO_CONFIRMATION, STRUCTURE_ONLY }
    public enum CloseReason { STOP, TARGET, TIMEOUT, LIQUIDATION, DATA_FAILURE, OTHER }

    public enum InitialEntryRule { PRE_SHOCK_BOUNDARY, POST_SHOCK_CONFIRMED_H4_SWING }
    public enum InitialStopRule { LEGACY_H1_THREE_BAR, H4_THREE_BAR }
    public enum PivotRefreshRule { FROZEN_INVALIDATION, REFRESH_AFTER_STOP_INVALIDATION }

    /** Immutable switchboard for the seven frozen V005 profiles. */
    public record RuleConfig(InitialEntryRule initialEntryRule, InitialStopRule initialStopRule,
            PivotRefreshRule pivotRefreshRule, boolean dailyRsiAdditionGate,
            boolean dailySma200AdditionGate, boolean logDailyContext) {
        public RuleConfig {
            Objects.requireNonNull(initialEntryRule, "initialEntryRule");
            Objects.requireNonNull(initialStopRule, "initialStopRule");
            Objects.requireNonNull(pivotRefreshRule, "pivotRefreshRule");
        }

        public static final RuleConfig LEGACY_V004 = new RuleConfig(InitialEntryRule.PRE_SHOCK_BOUNDARY,
                InitialStopRule.LEGACY_H1_THREE_BAR, PivotRefreshRule.FROZEN_INVALIDATION,
                false, false, false);
        public static final RuleConfig BASELINE_V004 = new RuleConfig(InitialEntryRule.PRE_SHOCK_BOUNDARY,
                InitialStopRule.LEGACY_H1_THREE_BAR, PivotRefreshRule.FROZEN_INVALIDATION,
                false, false, true);
        public static final RuleConfig POST_SHOCK_ENTRY = new RuleConfig(InitialEntryRule.POST_SHOCK_CONFIRMED_H4_SWING,
                InitialStopRule.LEGACY_H1_THREE_BAR, PivotRefreshRule.FROZEN_INVALIDATION,
                false, false, true);
        public static final RuleConfig H4_STRUCTURAL_STOP = new RuleConfig(InitialEntryRule.POST_SHOCK_CONFIRMED_H4_SWING,
                InitialStopRule.H4_THREE_BAR, PivotRefreshRule.FROZEN_INVALIDATION,
                false, false, true);
        public static final RuleConfig REFRESHED_STAGING = new RuleConfig(InitialEntryRule.POST_SHOCK_CONFIRMED_H4_SWING,
                InitialStopRule.H4_THREE_BAR, PivotRefreshRule.REFRESH_AFTER_STOP_INVALIDATION,
                false, false, true);
        public static final RuleConfig DAILY_RSI_CONTEXT = new RuleConfig(InitialEntryRule.POST_SHOCK_CONFIRMED_H4_SWING,
                InitialStopRule.H4_THREE_BAR, PivotRefreshRule.REFRESH_AFTER_STOP_INVALIDATION,
                true, false, true);
        public static final RuleConfig DAILY_MA_CONTEXT = new RuleConfig(InitialEntryRule.POST_SHOCK_CONFIRMED_H4_SWING,
                InitialStopRule.H4_THREE_BAR, PivotRefreshRule.REFRESH_AFTER_STOP_INVALIDATION,
                false, true, true);
        public static final RuleConfig DAILY_BOTH_CONTEXT = new RuleConfig(InitialEntryRule.POST_SHOCK_CONFIRMED_H4_SWING,
                InitialStopRule.H4_THREE_BAR, PivotRefreshRule.REFRESH_AFTER_STOP_INVALIDATION,
                true, true, true);

        public static RuleConfig forProfile(String profileId) {
            return switch (Objects.requireNonNull(profileId, "profileId")) {
                case "BASELINE_V004" -> BASELINE_V004;
                case "POST_SHOCK_ENTRY" -> POST_SHOCK_ENTRY;
                case "H4_STRUCTURAL_STOP" -> H4_STRUCTURAL_STOP;
                case "REFRESHED_STAGING" -> REFRESHED_STAGING;
                case "DAILY_RSI_CONTEXT" -> DAILY_RSI_CONTEXT;
                case "DAILY_MA_CONTEXT" -> DAILY_MA_CONTEXT;
                case "DAILY_BOTH_CONTEXT" -> DAILY_BOTH_CONTEXT;
                default -> throw new IllegalArgumentException("unsupported V005 router profile: " + profileId);
            };
        }
    }

    /** One complete UTC daily close and its optional, causally available indicator values. */
    public record DailyPriceContext(String asset, LocalDate utcDay, Instant availableAt,
            double dailyClose, Double rsi14, Double sma200, String seriesId) {
        public DailyPriceContext {
            asset = canonicalAsset(asset);
            Objects.requireNonNull(utcDay, "utcDay");
            Objects.requireNonNull(availableAt, "availableAt");
            seriesId = requiredSeriesId(seriesId);
            Instant dayClose = utcDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (availableAt.isBefore(dayClose) || !finitePositive(dailyClose)
                    || (rsi14 != null && (!Double.isFinite(rsi14) || rsi14 < 0.0 || rsi14 > 100.0))
                    || (sma200 != null && !finitePositive(sma200))) {
                throw new IllegalArgumentException("daily context must be finite and available no earlier than its completed UTC day");
            }
        }
    }

    /** A typed raw daily row: long/short USD totals are observed values, never precomputed gates. */
    public record DailyLiquidation(String asset, LocalDate bucketStart, double longLiquidationsUsd,
            double shortLiquidationsUsd, Instant availableAt, String seriesId) implements Observation {
        public DailyLiquidation {
            asset = canonicalAsset(asset);
            Objects.requireNonNull(bucketStart, "bucketStart");
            Objects.requireNonNull(availableAt, "availableAt");
            seriesId = requiredSeriesId(seriesId);
            if (!Double.isFinite(longLiquidationsUsd) || longLiquidationsUsd < 0.0
                    || !Double.isFinite(shortLiquidationsUsd) || shortLiquidationsUsd < 0.0) {
                throw new IllegalArgumentException("daily liquidation values must be finite and nonnegative");
            }
        }
        @Override public Instant eventTime() { return bucketStart.atStartOfDay(ZoneOffset.UTC).toInstant(); }
        public Instant modeledAvailableAt() {
            Instant proxy = bucketStart.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant();
            return availableAt.isAfter(proxy) ? availableAt : proxy;
        }
    }

    /** A completed OHLC feature bar. startTime is its half-open interval start. */
    public record Bar(String asset, Timeframe timeframe, Instant startTime, Instant availableAt,
            double open, double high, double low, double close, String seriesId) implements Observation {
        public Bar {
            asset = canonicalAsset(asset);
            Objects.requireNonNull(timeframe, "timeframe");
            Objects.requireNonNull(startTime, "startTime");
            Objects.requireNonNull(availableAt, "availableAt");
            seriesId = requiredSeriesId(seriesId);
            if (!finitePositive(open) || !finitePositive(high) || !finitePositive(low) || !finitePositive(close)
                    || high < Math.max(open, close) || low > Math.min(open, close) || high < low) {
                throw new IllegalArgumentException("bar OHLC must be finite, positive, and internally consistent");
            }
            long seconds = timeframe.duration().toSeconds();
            if (startTime.getEpochSecond() % seconds != 0 || startTime.getNano() != 0) {
                throw new IllegalArgumentException("bar start must align to its UTC timeframe");
            }
            if (availableAt.isBefore(startTime.plus(timeframe.duration()))) {
                throw new IllegalArgumentException("completed bar cannot be available before its close");
            }
        }
        @Override public Instant eventTime() { return startTime.plus(timeframe.duration()); }
    }

    /** Base-quantity open-interest observation with its measured and available timestamps. */
    public record OpenInterest(String asset, Instant observedAt, Instant availableAt,
            double baseQuantity, String seriesId) implements Observation {
        public OpenInterest {
            asset = canonicalAsset(asset);
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(availableAt, "availableAt");
            seriesId = requiredSeriesId(seriesId);
            if (!finitePositive(baseQuantity)) throw new IllegalArgumentException("base-quantity OI must be finite and positive");
            if (availableAt.isBefore(observedAt)) throw new IllegalArgumentException("OI cannot be available before it was observed");
        }
        @Override public Instant eventTime() { return observedAt; }
    }

    /**
     * S&P 500 session close. {@code provenanceAvailable} is true only after the upstream
     * qualification layer has receipt-bound the source and its declared availability policy;
     * a receipt-bound modeled publication time supports retrospective diagnostics only and is
     * never proof of point-in-time observation. False provenance forces the macro state to
     * UNKNOWN. {@code availableAt} must still include the full qualified extra-session lag.
     */
    public record MacroClose(Instant closeTime, Instant availableAt, double close,
            boolean provenanceAvailable, String seriesId) implements Observation {
        public MacroClose {
            Objects.requireNonNull(closeTime, "closeTime");
            Objects.requireNonNull(availableAt, "availableAt");
            seriesId = requiredSeriesId(seriesId);
            if (!finitePositive(close)) throw new IllegalArgumentException("macro close must be finite and positive");
            if (availableAt.isBefore(closeTime)) throw new IllegalArgumentException("macro close cannot be available before session close");
            validateMacroClose(closeTime, availableAt);
        }
        @Override public String asset() { return "SP500"; }
        @Override public Instant eventTime() { return closeTime; }
    }

    public sealed interface Observation permits DailyLiquidation, Bar, OpenInterest, MacroClose {
        String asset();
        Instant eventTime();
        Instant availableAt();
        String seriesId();
    }

    public record SourceEvidence(String role, String asset, String seriesId,
            Instant eventTime, Instant availableAt) {
        public SourceEvidence {
            role = Objects.requireNonNull(role, "role");
            asset = Objects.requireNonNull(asset, "asset");
            seriesId = requiredSeriesId(seriesId);
            Objects.requireNonNull(eventTime, "eventTime");
            Objects.requireNonNull(availableAt, "availableAt");
        }
    }

    /** A signal request only. A caller must wait for a strictly later eligible 1m open. */
    public record ConfirmedIntent(String intentId, String setupId, String pairId, String asset,
            Variant variant, Branch branch, Branch routedBranch, Direction direction, Direction shockDirection,
            int stage, Instant decisionTime, Instant requestedExecutionAfter,
            Instant confirmationBarStart, double confirmationClose,
            double zoneCenter, double zoneLower, double zoneUpper,
            Double pivotPrice, Instant pivotTime, Instant pivotConfirmedAt,
            double preEventAtr, double initialStop, Double reversalTarget,
            double maxChaseDistance, double trancheRiskFraction, int proposedLeverage,
            int maximumHoldingDays, MacroState macroState, boolean macroEligible,
            boolean diagnosticOnly, List<String> rejectionReasons, List<SourceEvidence> sourceEvidence) {
        public ConfirmedIntent {
            Objects.requireNonNull(intentId); Objects.requireNonNull(setupId); Objects.requireNonNull(pairId);
            asset = canonicalAsset(asset); Objects.requireNonNull(variant); Objects.requireNonNull(branch);
            Objects.requireNonNull(routedBranch); Objects.requireNonNull(direction);
            Objects.requireNonNull(shockDirection); Objects.requireNonNull(decisionTime);
            Objects.requireNonNull(requestedExecutionAfter); Objects.requireNonNull(confirmationBarStart);
            Objects.requireNonNull(macroState);
            rejectionReasons = List.copyOf(rejectionReasons); sourceEvidence = List.copyOf(sourceEvidence);
            if (stage < 1 || stage > 3 || requestedExecutionAfter.isBefore(decisionTime)
                    || !requestedExecutionAfter.isAfter(decisionTime)) {
                throw new IllegalArgumentException("intent stage or strictly-later execution boundary is invalid");
            }
        }
    }

    /** Immutable market geometry captured when a routed stage-one anchor is confirmed. */
    public record AnchorSetupSeed(String setupId, String asset, Direction shockDirection,
            Instant eventStart, Instant eventEnd, Instant availableAt, double preEventAtr,
            double priorHigh, double priorLow, double boundary, double recoveryTarget,
            double zoneLower, double zoneUpper, boolean fastQualifies, boolean slowQualifies,
            String selectedGeometry, List<SourceEvidence> geometryEvidence) {
        public static final String SCHEMA = "liquidation-v2-anchor-setup-seed/1";
        public AnchorSetupSeed {
            Objects.requireNonNull(setupId); asset = canonicalAsset(asset);
            Objects.requireNonNull(shockDirection); Objects.requireNonNull(eventStart);
            Objects.requireNonNull(eventEnd); Objects.requireNonNull(availableAt);
            Objects.requireNonNull(selectedGeometry); geometryEvidence = List.copyOf(geometryEvidence);
            if (setupId.isBlank() || !eventStart.isBefore(eventEnd) || availableAt.isBefore(eventEnd)
                    || !finitePositive(preEventAtr) || !finitePositive(priorHigh) || !finitePositive(priorLow)
                    || priorHigh < priorLow || !finitePositive(boundary) || !finitePositive(recoveryTarget)
                    || !finitePositive(zoneLower) || !finitePositive(zoneUpper) || zoneLower > zoneUpper
                    || !(fastQualifies || slowQualifies) || (fastQualifies && !"FAST".equals(selectedGeometry))
                    || (!fastQualifies && !"SLOW".equals(selectedGeometry)) || geometryEvidence.isEmpty()
                    || !Set.of("FAST", "SLOW").contains(selectedGeometry)) {
                throw new IllegalArgumentException("anchor setup seed geometry is invalid");
            }
        }
    }

    /** The exact stage-one decision plus the pre-event setup state needed for a later staged replay. */
    public record StageOneAnchorContext(ConfirmedIntent intent, AnchorSetupSeed setupSeed) {
        public StageOneAnchorContext {
            Objects.requireNonNull(intent); Objects.requireNonNull(setupSeed);
            if (intent.variant() != Variant.ROUTED_REVERSAL_CONTINUATION || intent.stage() != 1
                    || intent.diagnosticOnly() || !intent.setupId().equals(setupSeed.setupId())
                    || !intent.asset().equals(setupSeed.asset()) || intent.shockDirection() != setupSeed.shockDirection()
                    || (intent.branch() == Branch.CONTINUATION && intent.direction() != intent.shockDirection())
                    || (intent.branch() == Branch.REVERSAL && intent.direction() != intent.shockDirection().opposite())
                    || (intent.branch() == Branch.CONTINUATION && intent.reversalTarget() != null)
                    || (intent.branch() == Branch.REVERSAL && (intent.reversalTarget() == null
                        || Double.compare(intent.reversalTarget(), setupSeed.recoveryTarget()) != 0))
                    || !intent.decisionTime().isAfter(setupSeed.availableAt())
                    || !intent.decisionTime().equals(intent.sourceEvidence().stream()
                            .filter(item -> item.role().equals("CONFIRMATION"))
                            .map(SourceEvidence::availableAt).findFirst().orElse(null))
                    || Double.compare(intent.preEventAtr(), setupSeed.preEventAtr()) != 0
                    || Double.compare(intent.zoneLower(), setupSeed.zoneLower()) != 0
                    || Double.compare(intent.zoneUpper(), setupSeed.zoneUpper()) != 0
                    || Double.compare(intent.trancheRiskFraction(), 0.01) != 0
                    || intent.proposedLeverage() != 2 || intent.maximumHoldingDays() != MAX_HOLDING_DAYS
                    || intent.macroState() != MacroState.NOT_REQUIRED || !intent.macroEligible()) {
                throw new IllegalArgumentException("stage-one anchor and immutable setup seed disagree");
            }
            if (intent.sourceEvidence().stream().anyMatch(item -> item.availableAt().isAfter(intent.decisionTime()))
                    || setupSeed.geometryEvidence().stream().anyMatch(item -> item.availableAt().isAfter(intent.decisionTime()))) {
                throw new IllegalArgumentException("stage-one anchor evidence cannot become available after its decision");
            }
        }
    }

    public record RejectedOpportunity(String opportunityId, String setupId, String pairId, String asset,
            Variant variant, Branch routedBranch, Branch forcedBranch, Direction direction, int stage,
            Instant decisionTime, String reasonCode, MacroState macroState,
            boolean diagnosticOnly, List<SourceEvidence> sourceEvidence) {
        public RejectedOpportunity {
            Objects.requireNonNull(opportunityId); Objects.requireNonNull(setupId); Objects.requireNonNull(pairId);
            asset = canonicalAsset(asset); Objects.requireNonNull(variant); Objects.requireNonNull(routedBranch);
            Objects.requireNonNull(forcedBranch); Objects.requireNonNull(direction); Objects.requireNonNull(decisionTime);
            Objects.requireNonNull(reasonCode); Objects.requireNonNull(macroState);
            sourceEvidence = List.copyOf(sourceEvidence);
        }
    }

    /** A tightening-only continuation stop update. It is active only after activationTime. */
    public record StopUpdateIntent(String updateId, String setupId, String asset,
            Instant activationTime, double activeStopBefore, double proposedStop,
            Direction direction, boolean tightenOnly, List<SourceEvidence> sourceEvidence) {
        public StopUpdateIntent {
            Objects.requireNonNull(updateId); Objects.requireNonNull(setupId); asset = canonicalAsset(asset);
            Objects.requireNonNull(activationTime); Objects.requireNonNull(direction);
            sourceEvidence = List.copyOf(sourceEvidence);
            if (!finitePositive(activeStopBefore) || !finitePositive(proposedStop) || !tightenOnly) {
                throw new IllegalArgumentException("stop update must contain positive levels and tighten-only policy");
            }
        }
    }

    /** A confirmed order canceled by newly available pre-fill structure. */
    public record PendingCancellation(String intentId, String setupId, String asset, int stage,
            Instant cancellationTime, String reasonCode, List<SourceEvidence> sourceEvidence) {
        public PendingCancellation {
            Objects.requireNonNull(intentId); Objects.requireNonNull(setupId); asset = canonicalAsset(asset);
            Objects.requireNonNull(cancellationTime); Objects.requireNonNull(reasonCode);
            sourceEvidence = List.copyOf(sourceEvidence);
            if (stage < 1 || reasonCode.isBlank()) throw new IllegalArgumentException("pending cancellation identity is invalid");
        }
    }

    /**
     * Market-qualified daily liquidation/price/OI event, emitted independently of position
     * occupancy or whether the event later produces an entry intent. Use {@code availableAt}
     * as the outage-clock anchor; {@code bucketEventTime} is the original daily bucket timestamp.
     */
    public record QualifiedDailyStressEvent(String eventId, String asset, LocalDate bucketStart,
            Instant bucketEventTime, Instant availableAt, Direction shockDirection,
            List<SourceEvidence> sourceEvidence) {
        public QualifiedDailyStressEvent {
            Objects.requireNonNull(eventId); asset = canonicalAsset(asset);
            Objects.requireNonNull(bucketStart); Objects.requireNonNull(bucketEventTime);
            Objects.requireNonNull(availableAt); Objects.requireNonNull(shockDirection);
            sourceEvidence = List.copyOf(sourceEvidence);
            if (eventId.isBlank() || availableAt.isBefore(bucketEventTime)) {
                throw new IllegalArgumentException("qualified daily event identity or availability is invalid");
            }
        }
    }

    public record RouteResult(List<ConfirmedIntent> intents, List<RejectedOpportunity> rejections,
            List<StopUpdateIntent> stopUpdates, List<PairedDecisionContext> pairedDecisionContexts,
            List<PendingCancellation> pendingCancellations,
            List<QualifiedDailyStressEvent> qualifiedDailyStressEvents,
            List<StageOneAnchorContext> stageOneAnchorContexts) {
        public RouteResult {
            intents = List.copyOf(intents); rejections = List.copyOf(rejections);
            stopUpdates = List.copyOf(stopUpdates); pairedDecisionContexts = List.copyOf(pairedDecisionContexts);
            pendingCancellations = List.copyOf(pendingCancellations);
            qualifiedDailyStressEvents = List.copyOf(qualifiedDailyStressEvents);
            stageOneAnchorContexts = List.copyOf(stageOneAnchorContexts);
        }
        public RouteResult(List<ConfirmedIntent> intents, List<RejectedOpportunity> rejections,
                List<StopUpdateIntent> stopUpdates, List<PairedDecisionContext> pairedDecisionContexts,
                List<PendingCancellation> pendingCancellations,
                List<QualifiedDailyStressEvent> qualifiedDailyStressEvents) {
            this(intents, rejections, stopUpdates, pairedDecisionContexts, pendingCancellations,
                    qualifiedDailyStressEvents, List.of());
        }
        public RouteResult(List<ConfirmedIntent> intents, List<RejectedOpportunity> rejections,
                List<StopUpdateIntent> stopUpdates, List<PairedDecisionContext> pairedDecisionContexts,
                List<PendingCancellation> pendingCancellations) {
            this(intents, rejections, stopUpdates, pairedDecisionContexts, pendingCancellations, List.of());
        }
        public RouteResult(List<ConfirmedIntent> intents, List<RejectedOpportunity> rejections,
                List<StopUpdateIntent> stopUpdates, List<PairedDecisionContext> pairedDecisionContexts) {
            this(intents, rejections, stopUpdates, pairedDecisionContexts, List.of(), List.of());
        }
        static RouteResult empty() { return new RouteResult(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()); }
    }

    /** Frozen routed stage-one decision geometry used to derive its paired branch controls. */
    public record PairedDecisionContext(ConfirmedIntent routedAnchor, Double immutableRecoveryTarget,
            List<Bar> lastThreeCompletedHours) {
        public PairedDecisionContext {
            Objects.requireNonNull(routedAnchor, "routedAnchor");
            lastThreeCompletedHours = List.copyOf(lastThreeCompletedHours);
            if (routedAnchor.variant() != Variant.ROUTED_REVERSAL_CONTINUATION
                    || routedAnchor.stage() != 1 || routedAnchor.diagnosticOnly()) {
                throw new IllegalArgumentException("paired-control context requires a routed stage-one anchor");
            }
            if (immutableRecoveryTarget != null && !finitePositive(immutableRecoveryTarget)) {
                throw new IllegalArgumentException("shared reversal midpoint must be finite and positive when present");
            }
        }
    }

    /** One forced control branch at the routed anchor's exact decision time. */
    public record ControlArmOpportunity(Variant variant, Branch forcedBranch, Direction direction,
            ConfirmedIntent intent, RejectedOpportunity rejection) {
        public ControlArmOpportunity {
            Objects.requireNonNull(variant); Objects.requireNonNull(forcedBranch); Objects.requireNonNull(direction);
            if (variant != Variant.ALWAYS_CONTINUATION_CONTROL && variant != Variant.ALWAYS_REVERSAL_CONTROL) {
                throw new IllegalArgumentException("paired arms must be one of the two frozen branch controls");
            }
            if ((intent == null) == (rejection == null)) {
                throw new IllegalArgumentException("a control arm must contain exactly one intent or rejection");
            }
            if (intent != null && (intent.variant() != variant || intent.branch() != forcedBranch
                    || intent.direction() != direction)) {
                throw new IllegalArgumentException("control intent does not match its forced-arm identity");
            }
            if (rejection != null && (rejection.variant() != variant || rejection.forcedBranch() != forcedBranch
                    || rejection.direction() != direction)) {
                throw new IllegalArgumentException("control rejection does not match its forced-arm identity");
            }
        }
    }

    /** Retains both forced control opportunities, including structurally invalid arms. */
    public record PairedControlResult(String pairId, Instant decisionTime,
            List<ControlArmOpportunity> arms) {
        public PairedControlResult {
            Objects.requireNonNull(pairId); Objects.requireNonNull(decisionTime);
            arms = List.copyOf(arms);
            if (arms.size() != 2 || arms.get(0).variant() != Variant.ALWAYS_CONTINUATION_CONTROL
                    || arms.get(0).forcedBranch() != Branch.CONTINUATION
                    || arms.get(1).variant() != Variant.ALWAYS_REVERSAL_CONTROL
                    || arms.get(1).forcedBranch() != Branch.REVERSAL) {
                throw new IllegalArgumentException("paired result must retain continuation then reversal control arms");
            }
        }
    }

    /**
     * Purely derives both frozen branch-control intents from a routed decision. It does not
     * consult or mutate router state, and always retains both arms as either an intent or a
     * structural rejection at the routed decision timestamp.
     */
    public static PairedControlResult derivePairedControlArms(PairedDecisionContext context) {
        Objects.requireNonNull(context, "context");
        ConfirmedIntent anchor = context.routedAnchor();
        List<Bar> bars = context.lastThreeCompletedHours();
        String geometryFailure = sharedControlGeometryFailure(anchor, bars);
        List<SourceEvidence> evidence = new ArrayList<>(anchor.sourceEvidence());
        for (Bar bar : bars) {
            if (bar.asset().equals(anchor.asset()) && bar.timeframe() == Timeframe.ONE_HOUR
                    && !bar.availableAt().isAfter(anchor.decisionTime())) {
                evidence.add(evidence("PAIRED_CONTROL_STOP_SOURCE", bar));
            }
        }

        List<ControlArmOpportunity> arms = new ArrayList<>(2);
        for (Branch forcedBranch : List.of(Branch.CONTINUATION, Branch.REVERSAL)) {
            Variant controlVariant = forcedBranch == Branch.CONTINUATION
                    ? Variant.ALWAYS_CONTINUATION_CONTROL : Variant.ALWAYS_REVERSAL_CONTROL;
            Direction direction = forcedBranch == Branch.CONTINUATION
                    ? anchor.shockDirection() : anchor.shockDirection().opposite();
            List<String> reasons = new ArrayList<>();
            double stop = Double.NaN;
            Double target = forcedBranch == Branch.REVERSAL ? context.immutableRecoveryTarget() : null;

            if (geometryFailure != null) {
                reasons.add(geometryFailure);
            } else {
                stop = structuralStop(bars, direction, anchor.preEventAtr());
                if (!stopAdverse(direction, stop, anchor.confirmationClose())) {
                    reasons.add("CONTROL_STOP_NOT_ADVERSE_AT_SHARED_DECISION");
                }
                if (forcedBranch == Branch.REVERSAL) {
                    if (target == null) {
                        reasons.add("MISSING_SHARED_RECOVERY_TARGET");
                    } else {
                        if (!targetAhead(direction, target, anchor.confirmationClose())) {
                            reasons.add("REVERSAL_TARGET_AT_OR_BEHIND_SHARED_DECISION");
                        }
                        if (targetReachedByBar(target, direction, bars.get(2))) {
                            reasons.add("REVERSAL_TARGET_REACHED_DURING_SHARED_CONFIRMATION_BAR");
                        }
                    }
                }
            }

            String pairId = anchor.pairId();
            if (reasons.isEmpty()) {
                ConfirmedIntent control = new ConfirmedIntent(
                        pairId + "|" + controlVariant.name() + "|STAGE1",
                        anchor.setupId(), pairId, anchor.asset(), controlVariant,
                        forcedBranch, anchor.routedBranch(), direction, anchor.shockDirection(), 1,
                        anchor.decisionTime(), anchor.requestedExecutionAfter(),
                        anchor.confirmationBarStart(), anchor.confirmationClose(),
                        anchor.zoneCenter(), anchor.zoneLower(), anchor.zoneUpper(),
                        anchor.pivotPrice(), anchor.pivotTime(), anchor.pivotConfirmedAt(),
                        anchor.preEventAtr(), stop, target, anchor.maxChaseDistance(),
                        anchor.trancheRiskFraction(), anchor.proposedLeverage(),
                        anchor.maximumHoldingDays(), MacroState.NOT_REQUIRED, true, false,
                        List.of(), evidence);
                arms.add(new ControlArmOpportunity(controlVariant, forcedBranch, direction, control, null));
            } else {
                String reason = String.join(";", reasons);
                RejectedOpportunity rejection = new RejectedOpportunity(
                        pairId + "|" + controlVariant.name() + "|STAGE1|REJECT|" + reason,
                        anchor.setupId(), pairId, anchor.asset(), controlVariant,
                        anchor.routedBranch(), forcedBranch, direction, 1,
                        anchor.decisionTime(), reason, MacroState.NOT_REQUIRED, false, evidence);
                arms.add(new ControlArmOpportunity(controlVariant, forcedBranch, direction, null, rejection));
            }
        }
        return new PairedControlResult(anchor.pairId(), anchor.decisionTime(), arms);
    }

    private static String sharedControlGeometryFailure(ConfirmedIntent anchor, List<Bar> bars) {
        if (bars.size() != 3) return "SHARED_CONTROL_REQUIRES_THREE_COMPLETED_HOURLY_BARS";
        for (int i = 0; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            if (bar.timeframe() != Timeframe.ONE_HOUR || !bar.asset().equals(anchor.asset())
                    || !bar.availableAt().equals(anchor.decisionTime())
                    && bar.availableAt().isAfter(anchor.decisionTime())) {
                return "SHARED_CONTROL_HOURLY_GEOMETRY_NOT_KNOWN_AT_DECISION";
            }
            if (i > 0 && (!bar.startTime().equals(bars.get(i - 1).eventTime())
                    || !bar.seriesId().equals(bars.get(i - 1).seriesId()))) {
                return "SHARED_CONTROL_HOURLY_GEOMETRY_NOT_CONTIGUOUS";
            }
        }
        Bar finalBar = bars.get(2);
        if (!finalBar.startTime().equals(anchor.confirmationBarStart())
                || !finalBar.seriesId().equals(bars.get(0).seriesId())
                || finalBar.eventTime().isAfter(anchor.decisionTime())
                || Double.compare(finalBar.close(), anchor.confirmationClose()) != 0) {
            return "SHARED_CONTROL_HOURLY_GEOMETRY_DOES_NOT_MATCH_ROUTED_CONFIRMATION";
        }
        return null;
    }

    public record FillAck(String intentId, String setupId, String asset, int stage,
            Instant fillTime, double fillPrice, double activeStop) {
        public FillAck {
            Objects.requireNonNull(intentId); Objects.requireNonNull(setupId); asset = canonicalAsset(asset);
            Objects.requireNonNull(fillTime);
            if (stage < 1 || stage > 3 || !finitePositive(fillPrice) || !finitePositive(activeStop)) {
                throw new IllegalArgumentException("fill acknowledgement has invalid stage or price");
            }
        }
    }

    public record NoFillAck(String intentId, String setupId, String asset, int stage,
            Instant resolvedAt, String reasonCode) {
        public NoFillAck {
            Objects.requireNonNull(intentId); Objects.requireNonNull(setupId); asset = canonicalAsset(asset);
            Objects.requireNonNull(resolvedAt); Objects.requireNonNull(reasonCode);
            if (stage < 1 || stage > 3) throw new IllegalArgumentException("no-fill stage must be 1..3");
        }
    }

    public record CloseAck(String setupId, String asset, Instant closeTime, CloseReason reason) {
        public CloseAck {
            Objects.requireNonNull(setupId); asset = canonicalAsset(asset);
            Objects.requireNonNull(closeTime); Objects.requireNonNull(reason);
        }
    }

    public record StopAck(String setupId, String asset, Instant activationTime, double activeStop) {
        public StopAck {
            Objects.requireNonNull(setupId); asset = canonicalAsset(asset); Objects.requireNonNull(activationTime);
            if (!finitePositive(activeStop)) throw new IllegalArgumentException("active stop must be finite and positive");
        }
    }

    /** State is per replay and variant; use one instance for each of the four frozen candidates. */
    public static final class Router {
        private final Variant variant;
        private final MacroGatePolicy macroGatePolicy;
        private final RuleConfig ruleConfig;
        private final Map<String, NavigableMap<LocalDate, DailyPriceContext>> dailyPriceContexts;
        private final boolean anchoredStageOneOnly;
        private final Map<String, AssetState> assets = new LinkedHashMap<>();
        private final TreeMap<Instant, MacroClose> macroCloses = new TreeMap<>();
        private final Map<String, ConfirmedIntent> pendingIntents = new HashMap<>();
        private final Set<String> frozenAnchorIntentIds = new HashSet<>();
        private final Set<String> frozenAnchorPairIds = new HashSet<>();
        private final Map<String, ObjectNode> dailyRuleAudit = new TreeMap<>();
        private final Map<String, ObjectNode> setupRuleAudit = new HashMap<>();
        private Instant lastAcceptedAvailability;
        private String macroSeriesId;

        public Router(Variant variant) {
            this(variant, MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION, RuleConfig.LEGACY_V004, List.of(), false);
        }

        public Router(Variant variant, MacroGatePolicy macroGatePolicy) {
            this(variant, macroGatePolicy, RuleConfig.LEGACY_V004, List.of(), false);
        }

        public Router(Variant variant, MacroGatePolicy macroGatePolicy, RuleConfig ruleConfig) {
            this(variant, macroGatePolicy, ruleConfig, List.of(), false);
        }

        public Router(Variant variant, MacroGatePolicy macroGatePolicy, RuleConfig ruleConfig,
                Collection<DailyPriceContext> dailyPriceContexts) {
            this(variant, macroGatePolicy, ruleConfig, dailyPriceContexts, false);
        }

        private Router(Variant variant, MacroGatePolicy macroGatePolicy, RuleConfig ruleConfig,
                Collection<DailyPriceContext> dailyPriceContexts, boolean anchoredStageOneOnly) {
            this.variant = Objects.requireNonNull(variant, "variant");
            this.macroGatePolicy = Objects.requireNonNull(macroGatePolicy, "macroGatePolicy");
            this.ruleConfig = Objects.requireNonNull(ruleConfig, "ruleConfig");
            this.dailyPriceContexts = indexDailyPriceContexts(dailyPriceContexts);
            this.anchoredStageOneOnly = anchoredStageOneOnly;
            if (anchoredStageOneOnly && variant != Variant.ROUTED_REVERSAL_CONTINUATION) {
                throw new IllegalArgumentException("anchored staged replay requires the routed candidate variant");
            }
        }

        /** Package-scoped public-runner entry point; ordinary callers cannot self-certify anchors. */
        static Router anchoredStaged(MacroGatePolicy macroGatePolicy) {
            return new Router(Variant.ROUTED_REVERSAL_CONTINUATION, macroGatePolicy,
                    RuleConfig.LEGACY_V004, List.of(), true);
        }

        public Variant variant() { return variant; }
        public MacroGatePolicy macroGatePolicy() { return macroGatePolicy; }
        public RuleConfig ruleConfig() { return ruleConfig; }

        private static Map<String, NavigableMap<LocalDate, DailyPriceContext>> indexDailyPriceContexts(
                Collection<DailyPriceContext> rows) {
            Objects.requireNonNull(rows, "dailyPriceContexts");
            TreeMap<String, TreeMap<LocalDate, DailyPriceContext>> byAsset = new TreeMap<>();
            for (DailyPriceContext row : rows) {
                Objects.requireNonNull(row, "daily price context row");
                DailyPriceContext previous = byAsset.computeIfAbsent(row.asset(), ignored -> new TreeMap<>())
                        .putIfAbsent(row.utcDay(), row);
                if (previous != null) throw new IllegalArgumentException("duplicate daily context for " + row.asset() + " " + row.utcDay());
            }
            TreeMap<String, NavigableMap<LocalDate, DailyPriceContext>> frozen = new TreeMap<>();
            byAsset.forEach((asset, values) -> frozen.put(asset, Collections.unmodifiableNavigableMap(values)));
            return Map.copyOf(frozen);
        }

        /**
         * Diagnostic-only event funnel over daily liquidation rows. The requested window is
         * applied to the modeled availability time, matching entry decisions; this method
         * never feeds values back into routing or accounting.
         */
        public ObjectNode entryRuleAuditSnapshot(Instant decisionStart, Instant decisionEnd) {
            Objects.requireNonNull(decisionStart, "decisionStart");
            Objects.requireNonNull(decisionEnd, "decisionEnd");
            if (!decisionStart.isBefore(decisionEnd)) throw new IllegalArgumentException("audit decision window must be positive");
            ObjectNode result = JsonHashes.mapper().createObjectNode()
                    .put("schema", "liquidation-entry-rule-audit/1")
                    .put("diagnostic_only", true)
                    .put("rule_changes", !ruleConfig.equals(RuleConfig.LEGACY_V004)
                            && !ruleConfig.equals(RuleConfig.BASELINE_V004))
                    .put("decision_window_start_inclusive", decisionStart.toString())
                    .put("decision_window_end_exclusive", decisionEnd.toString())
                    .put("window_anchor", "MODELED_DAILY_AVAILABILITY");
            ObjectNode byAsset = result.putObject("daily_gate_counts_by_asset");
            ArrayNode events = result.putArray("liquidation_stress_event_trace");
            List<String> auditAssets = dailyRuleAudit.values().stream().map(row -> row.path("asset").asText())
                    .distinct().sorted(Comparator.comparingInt(SUPPORTED_ASSET_ORDER::indexOf)).toList();
            for (String asset : auditAssets) {
                ObjectNode counts = JsonHashes.mapper().createObjectNode().put("daily_rows", 0)
                        .put("window_unavailable", 0).put("no_liquidation_stress", 0)
                        .put("valid_90_day_windows", 0)
                        .put("long_stress_days", 0).put("short_stress_days", 0)
                        .put("unique_liquidation_stress_days", 0).put("geometry_rejected", 0)
                        .put("setup_admitted", 0).put("setup_suppressed", 0);
                TreeSet<LocalDate> allObservedDays = new TreeSet<>(), decisionWindowObservedDays = new TreeSet<>();
                for (ObjectNode row : dailyRuleAudit.values()) {
                    if (!asset.equals(row.path("asset").asText())) continue;
                    LocalDate bucketDay = LocalDate.parse(row.path("bucket_start").asText());
                    allObservedDays.add(bucketDay);
                    Instant availableAt = Instant.parse(row.path("available_at").asText());
                    if (availableAt.isBefore(decisionStart) || !availableAt.isBefore(decisionEnd)) continue;
                    decisionWindowObservedDays.add(bucketDay);
                    counts.put("daily_rows", counts.path("daily_rows").asInt() + 1);
                    String status = row.path("status").asText();
                    switch (status) {
                        case "DAILY_WINDOW_UNAVAILABLE" -> counts.put("window_unavailable", counts.path("window_unavailable").asInt() + 1);
                        case "NO_90D_P95_LIQUIDATION_STRESS" -> counts.put("no_liquidation_stress", counts.path("no_liquidation_stress").asInt() + 1);
                        default -> { }
                    }
                    if (!"DAILY_WINDOW_UNAVAILABLE".equals(status)) counts.put("valid_90_day_windows", counts.path("valid_90_day_windows").asInt() + 1);
                    if (row.path("long_stress").asBoolean(false)) counts.put("long_stress_days", counts.path("long_stress_days").asInt() + 1);
                    if (row.path("short_stress").asBoolean(false)) counts.put("short_stress_days", counts.path("short_stress_days").asInt() + 1);
                    if (!row.path("liquidation_stress").asBoolean(false)) continue;
                    counts.put("unique_liquidation_stress_days", counts.path("unique_liquidation_stress_days").asInt() + 1);
                    String disposition = row.path("disposition").asText("");
                    if (disposition.startsWith("GEOMETRY_REJECTED")) counts.put("geometry_rejected", counts.path("geometry_rejected").asInt() + 1);
                    else if ("SETUP_ADMITTED".equals(disposition)) counts.put("setup_admitted", counts.path("setup_admitted").asInt() + 1);
                    else if (disposition.startsWith("SETUP_SUPPRESSED")) counts.put("setup_suppressed", counts.path("setup_suppressed").asInt() + 1);
                    events.add(row.deepCopy());
                }
                if (!allObservedDays.isEmpty()) {
                    LocalDate decisionBucketStart = utcDayCeiling(decisionStart.minus(Duration.ofHours(48)));
                    LocalDate decisionBucketEnd = utcDayCeiling(decisionEnd.minus(Duration.ofHours(48)));
                    LocalDate coveredStart = allObservedDays.first().isAfter(decisionBucketStart) ? allObservedDays.first() : decisionBucketStart;
                    LocalDate coveredEnd = allObservedDays.last().plusDays(1).isBefore(decisionBucketEnd)
                            ? allObservedDays.last().plusDays(1) : decisionBucketEnd;
                    long coveredDays = Math.max(0, ChronoUnit.DAYS.between(coveredStart, coveredEnd));
                    long observedCoveredDays = decisionWindowObservedDays.stream()
                            .filter(day -> !day.isBefore(coveredStart) && day.isBefore(coveredEnd)).count();
                    counts.put("first_observed_daily_bucket", allObservedDays.first().toString())
                            .put("last_observed_daily_bucket", allObservedDays.last().toString())
                            .put("observed_coverage_days_in_decision_window", coveredDays)
                            .put("missing_daily_days_inside_observed_asset_span", Math.max(0, coveredDays - observedCoveredDays));
                } else {
                    counts.putNull("first_observed_daily_bucket").putNull("last_observed_daily_bucket")
                            .put("observed_coverage_days_in_decision_window", 0)
                            .put("missing_daily_days_inside_observed_asset_span", 0);
                }
                byAsset.set(asset, counts);
            }
            result.put("stress_event_trace_count", events.size());
            result.put("source_asset_tie_order", JsonHashes.mapper().valueToTree(auditAssets));
            result.put("content_sha256", JsonHashes.ownHash(result));
            return result;
        }

        private static LocalDate utcDayCeiling(Instant instant) {
            LocalDate date = instant.atZone(ZoneOffset.UTC).toLocalDate();
            return instant.equals(date.atStartOfDay(ZoneOffset.UTC).toInstant()) ? date : date.plusDays(1);
        }

        /**
         * Injects a predecessor-frozen stage-one decision after the same-time market history has
         * been loaded. This entry point is package scoped and accepts only the hash-bound JSON
         * projection written by the runner; later stage intents still require actual onFill.
         */
        RouteResult acceptFrozenInitialAnchor(JsonNode rawIntent) {
            if (!anchoredStageOneOnly) throw new IllegalStateException("router is not in anchored staged mode");
            StageOneAnchorContext anchor = anchorContextFromJson(rawIntent);
            ConfirmedIntent intent = anchor.intent();
            if (lastAcceptedAvailability != null && intent.decisionTime().isBefore(lastAcceptedAvailability)) {
                throw new IllegalArgumentException("frozen anchor cannot be injected before the loaded market clock");
            }
            if (intent.decisionTime().isBefore(anchor.setupSeed().availableAt())
                    || intent.requestedExecutionAfter().isBefore(intent.decisionTime())
                    || !intent.requestedExecutionAfter().isAfter(intent.decisionTime())) {
                throw new IllegalArgumentException("frozen anchor execution chronology is invalid");
            }
            AssetState state = assets.computeIfAbsent(intent.asset(), AssetState::new);
            String rejection = null;
            if (frozenAnchorIntentIds.contains(intent.intentId()) || frozenAnchorPairIds.contains(intent.pairId())) {
                rejection = "FROZEN_ANCHOR_DECISION_ALREADY_SEEN";
            } else if (state.filledSetupIds.contains(intent.setupId())) {
                rejection = "FROZEN_ANCHOR_SETUP_ALREADY_FILLED";
            } else if (state.setup != null && state.setup.filledStage == 0 && !state.setup.activePending
                    && !pendingFor(state.setup.id)) {
                // A no-fill does not consume later frozen H1 decisions from the same daily setup.
                state.setup = null;
            } else if (state.busy()) rejection = "FROZEN_ANCHOR_ASSET_OCCUPIED";
            // The public scheduler resolves exits before admissions at the same timestamp;
            // a distinct anchored setup at the close instant is therefore eligible.
            else if (state.lastCloseTime != null && intent.decisionTime().isBefore(state.lastCloseTime)) {
                rejection = "FROZEN_ANCHOR_NOT_AFTER_PRIOR_CLOSE";
            }
            if (rejection != null) {
                RejectedOpportunity row = new RejectedOpportunity(intent.pairId() + "|" + rejection,
                        intent.setupId(), intent.pairId(), intent.asset(), intent.variant(), intent.routedBranch(),
                        intent.branch(), intent.direction(), 1, intent.decisionTime(), rejection,
                        intent.macroState(), false, intent.sourceEvidence());
                return new RouteResult(List.of(), List.of(row), List.of(), List.of(), List.of(), List.of(), List.of());
            }
            AnchorSetupSeed seed = anchor.setupSeed();
            Geometry geometry = new Geometry(seed.asset(), seed.eventStart(), seed.eventEnd(), seed.shockDirection(),
                    seed.preEventAtr(), seed.priorHigh(), seed.priorLow(), seed.boundary(), seed.recoveryTarget(),
                    seed.zoneLower(), seed.zoneUpper(), seed.fastQualifies(), seed.slowQualifies(),
                    GeometryKind.valueOf(seed.selectedGeometry()), seed.availableAt(), seed.geometryEvidence());
            Setup setup = new Setup(intent.setupId(), geometry, seed.availableAt(), true, false);
            setup.branch = intent.branch();
            setup.direction = intent.direction();
            setup.armTime = intent.decisionTime();
            setup.activePending = true;
            state.setupIds.add(intent.setupId());
            state.setup = setup;
            pendingIntents.put(intent.intentId(), intent);
            frozenAnchorIntentIds.add(intent.intentId());
            frozenAnchorPairIds.add(intent.pairId());
            return new RouteResult(List.of(intent), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        /**
         * Accepts one observation in nondecreasing availability-time order. For order-independent
         * batch replay use {@link #replay(Variant, Collection)} which applies a stable causal sort.
         */
        public RouteResult accept(Observation observation) {
            Objects.requireNonNull(observation, "observation");
            Instant processAt = processingTime(observation);
            if (lastAcceptedAvailability != null && processAt.isBefore(lastAcceptedAvailability)) {
                throw new IllegalArgumentException("router observations must be ordered by availability time");
            }
            lastAcceptedAvailability = processAt;
            if (observation instanceof MacroClose macro) {
                addMacro(macro);
                return RouteResult.empty();
            }
            AssetState state = assets.computeIfAbsent(observation.asset(), ignored -> new AssetState(observation.asset()));
            expireSetup(state, processAt);
            if (observation instanceof DailyLiquidation daily) {
                return acceptDaily(state, daily);
            }
            if (observation instanceof OpenInterest oi) {
                state.addOi(oi);
                return RouteResult.empty();
            }
            Bar bar = (Bar) observation;
            state.addBar(bar);
            if (!state.markFreshForSignals(bar)) {
                Setup setup = state.setup;
                if (bar.timeframe() == Timeframe.FOUR_HOUR && setup != null && setup.branch != null
                        && (setup.activePending || pendingFor(setup.id))) {
                    RouteAccumulator lateEvidence = new RouteAccumulator();
                    processBranchBar(state, setup, bar, lateEvidence);
                    return lateEvidence.result();
                }
                return RouteResult.empty();
            }
            if (bar.timeframe() == Timeframe.FOUR_HOUR) {
                RouteAccumulator result = new RouteAccumulator();
                processFourHour(state, bar, result);
                if (variant == Variant.PRICE_OI_ONLY_EVENT_DIAGNOSTIC && !state.busy()) {
                    createPriceOiDiagnosticSetup(state, bar);
                }
                return result.result();
            }
            RouteAccumulator result = new RouteAccumulator();
            processOneHour(state, bar, result);
            return result.result();
        }

        /**
         * Stable batch replay over feature observations only. Ties are resolved by event time,
         * event type, asset, and source-series ID so row/file order never changes emitted routes.
         */
        public static List<RouteResult> replay(Variant variant, Collection<? extends Observation> observations) {
            return replay(variant, MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION, observations);
        }

        public static List<RouteResult> replay(Variant variant, MacroGatePolicy macroGatePolicy,
                Collection<? extends Observation> observations) {
            Objects.requireNonNull(observations, "observations");
            List<Observation> ordered = new ArrayList<>(observations);
            ordered.sort(OBSERVATION_ORDER);
            Router router = new Router(variant, macroGatePolicy);
            List<RouteResult> result = new ArrayList<>(ordered.size());
            for (Observation observation : ordered) result.add(router.accept(observation));
            return List.copyOf(result);
        }

        /** Realized accounting callback. A requested minute open remains outside this class. */
        public void onFill(FillAck fill) {
            Objects.requireNonNull(fill, "fill");
            ConfirmedIntent intent = pendingIntents.get(fill.intentId());
            if (intent == null || !intent.setupId().equals(fill.setupId()) || !intent.asset().equals(fill.asset())
                    || intent.stage() != fill.stage()) throw new IllegalArgumentException("fill does not match a pending router intent");
            if (!fill.fillTime().isAfter(intent.requestedExecutionAfter())) {
                throw new IllegalArgumentException("fill must occur at a strictly later instant than the requested execution boundary");
            }
            AssetState state = requiredState(fill.asset());
            Setup setup = state.setup;
            if (setup == null || !setup.id.equals(fill.setupId())) throw new IllegalArgumentException("fill belongs to an inactive setup");
            if (setup.pendingCancellationReason != null) throw new IllegalArgumentException("fill belongs to a structurally canceled pending intent");
            int expectedStage = setup.filledStage == 0 ? 1 : setup.filledStage + 1;
            if (fill.stage() != expectedStage) throw new IllegalArgumentException("fills must advance sequentially without skipped stages");
            if (setup.filledStage > 0 && !tightensOrEquals(setup.direction, setup.activeStop, fill.activeStop())) {
                throw new IllegalArgumentException("an addition cannot widen the common position stop");
            }
            pendingIntents.remove(fill.intentId());
            // Paired controls inherit the routed decision timestamp but own their forced branch
            // lifecycle from the moment their corresponding fill is acknowledged.
            setup.branch = intent.branch();
            setup.direction = intent.direction();
            setup.filledStage = fill.stage();
            if (fill.stage() == 1) state.filledSetupIds.add(fill.setupId());
            setup.lastFillTime = fill.fillTime();
            setup.lastFillPrice = fill.fillPrice();
            setup.activeStop = fill.activeStop();
            setup.favorableH4Seen = false;
            setup.favorableH4AvailableAt = null;
            setup.favorableH4Evidence = null;
            setup.pivotCandidate = null;
            setup.pivotZone = null;
            setup.pivotZoneInvalid = false;
            setup.pivotInvalidatedAt = null;
            setup.pivotInvalidatedCenterTime = null;
            setup.pivotReplacementCount = 0;
            setup.lastAddDecisionHour = null;
            setup.activePending = false;
            ObjectNode audit = auditForSetup(setup.id);
            if (audit != null) {
                ObjectNode staging = (ObjectNode) audit.path("staging");
                if (fill.stage() > 1) {
                    ObjectNode completedCycle = staging.deepCopy();
                    completedCycle.put("cycle_end_filled_stage", fill.stage())
                            .put("cycle_end_fill_time", fill.fillTime().toString());
                    ((ArrayNode) audit.path("completed_addition_cycles")).add(completedCycle);
                }
                ArrayNode fills = ((ObjectNode) audit.with("execution")).withArray("fills");
                fills.addObject().put("stage", fill.stage()).put("fill_time", fill.fillTime().toString())
                        .put("fill_price", fill.fillPrice()).put("active_stop", fill.activeStop());
                if (fill.stage() == 1) ((ObjectNode) audit.path("initial_entry")).put("status", "STAGE_ONE_FILLED");
                resetStagingCycle(staging, fill.stage(), fill.fillTime(), ruleConfig);
            }
        }

        public void onNoFill(NoFillAck noFill) {
            Objects.requireNonNull(noFill, "noFill");
            ConfirmedIntent intent = pendingIntents.remove(noFill.intentId());
            if (intent == null || !intent.setupId().equals(noFill.setupId()) || !intent.asset().equals(noFill.asset())
                    || intent.stage() != noFill.stage()) throw new IllegalArgumentException("no-fill does not match a pending router intent");
            AssetState state = requiredState(noFill.asset());
            if (state.setup == null || !state.setup.id.equals(noFill.setupId())) throw new IllegalArgumentException("no-fill belongs to an inactive setup");
            ObjectNode audit = auditForSetup(noFill.setupId());
            if (audit != null) {
                ((ObjectNode) audit.with("execution")).withArray("no_fills").addObject()
                        .put("stage", noFill.stage()).put("time", noFill.resolvedAt().toString())
                        .put("reason", noFill.reasonCode());
                if (noFill.stage() == 1) ((ObjectNode) audit.path("initial_entry")).put("status", "STAGE_ONE_FILL_REJECTED")
                        .put("fill_rejection_reason", noFill.reasonCode());
                else {
                    ObjectNode staging = (ObjectNode) audit.path("staging");
                    staging.put("stage_" + noFill.stage() + "_last_rejection", noFill.reasonCode());
                    incrementCount((ObjectNode) staging.with("fill_rejection_counts"), noFill.reasonCode());
                }
            }
            state.setup.activePending = false;
            if (state.setup.pendingCancellationReason != null
                    || anchoredStageOneOnly && noFill.stage() == 1 && state.setup.filledStage == 0) {
                markSetupTerminal(state.setup, noFill.reasonCode(), noFill.resolvedAt());
                state.setup = null;
            }
        }

        public void onClose(CloseAck close) {
            Objects.requireNonNull(close, "close");
            AssetState state = requiredState(close.asset());
            if (state.setup == null || !state.setup.id.equals(close.setupId()) || state.setup.filledStage == 0) {
                throw new IllegalArgumentException("close does not match an open routed position");
            }
            ObjectNode audit = auditForSetup(close.setupId());
            if (audit != null) {
                ObjectNode staging = (ObjectNode) audit.path("staging");
                int nextStage = state.setup.filledStage + 1;
                if (nextStage <= 3) {
                    String blocker;
                    boolean refreshEnabled = ruleConfig.pivotRefreshRule() == PivotRefreshRule.REFRESH_AFTER_STOP_INVALIDATION;
                    if (refreshEnabled && staging.path("pivot_refresh_pending").asBoolean(false)) {
                        blocker = "STOP_INVALIDATED_PIVOT_AWAITING_SAFE_REPLACEMENT";
                    } else if (!refreshEnabled && staging.path("pivot_zone_invalidated_by_stop").asBoolean(false)) {
                        blocker = "FROZEN_PIVOT_ZONE_INVALIDATED_BY_TRAILING_STOP_NO_REPLACEMENT";
                    } else if (!staging.path("pivot_zone_formed").asBoolean(false)) {
                        blocker = !staging.path("favorable_h4_seen").asBoolean(false)
                                ? "NO_FAVORABLE_H4_AFTER_FILL"
                                : staging.path("pivot_zone_invalidated_by_stop").asBoolean(false)
                                    ? "FROZEN_PIVOT_ZONE_INVALIDATED_BY_TRAILING_STOP_NO_REPLACEMENT"
                                    : staging.path("pivot_candidate_count").asInt() == 0
                                        ? staging.path("strict_pivots_unsafe_vs_active_stop").asInt() > 0
                                            ? "ONLY_STRICT_POSTFILL_PIVOTS_UNSAFE_VS_ACTIVE_STOP"
                                            : "NO_STRICT_POSTFILL_FIVE_BAR_H4_PIVOT_BEFORE_CLOSE"
                                        : "PIVOT_ZONE_NOT_COMPLETED_BEFORE_CLOSE";
                    } else if (staging.path("stage_" + nextStage + "_intent_count").asInt() > 0) {
                        blocker = "CONFIRMED_STAGE_" + nextStage + "_INTENT_NOT_FILLED_BEFORE_POSITION_CLOSE";
                    } else if (staging.path("stage_" + nextStage + "_daily_context_rejection_count").asInt() > 0) {
                        blocker = "STAGE_" + nextStage + "_CONFIRMATIONS_BLOCKED_BY_DAILY_CONTEXT";
                    } else if (staging.path("stage_" + nextStage + "_first_macro_rejection_time").isTextual()) {
                        blocker = "STAGE_" + nextStage + "_CONFIRMATIONS_BLOCKED_BY_MACRO";
                    } else {
                        blocker = "PIVOT_ZONE_WITHOUT_STAGE_" + nextStage + "_ENTRY_CONFIRMATION_BEFORE_CLOSE";
                    }
                    staging.put("terminal_addition_blocker_stage", nextStage)
                            .put("terminal_addition_blocker", blocker);
                }
                audit.put("position_close_reason", close.reason().name())
                        .put("position_closed_at", close.closeTime().toString())
                        .put("terminal_reason", "POSITION_CLOSED_" + close.reason().name())
                        .put("terminal_time", close.closeTime().toString());
            }
            pendingIntents.values().removeIf(intent -> intent.asset().equals(close.asset()) && intent.setupId().equals(close.setupId()));
            state.setup = null;
            state.lastCloseTime = close.closeTime();
        }

        /** Acknowledges the stop actually made active by the accounting owner. */
        public void onStopUpdate(StopAck stop) {
            Objects.requireNonNull(stop, "stop");
            AssetState state = requiredState(stop.asset());
            Setup setup = state.setup;
            if (setup == null || !setup.id.equals(stop.setupId()) || setup.filledStage == 0) {
                throw new IllegalArgumentException("stop acknowledgement does not match an open routed position");
            }
            if (!tightensOrEquals(setup.direction, setup.activeStop, stop.activeStop())) {
                throw new IllegalArgumentException("active stop acknowledgement cannot loosen the common stop");
            }
            setup.activeStop = stop.activeStop();
            if (setup.pivotCandidate != null
                    && !pivotSafe(setup.direction, setup.pivotCandidate.price(), setup.activeStop)) {
                ObjectNode audit = auditForSetup(setup.id);
                if (audit != null) ((ObjectNode) audit.path("staging")).put("pivot_candidate_invalidated_by_stop", true)
                        .put("pivot_candidate_invalidated_at", stop.activationTime().toString());
                setup.pivotCandidate = null;
            }
            if (setup.pivotZone != null && !setup.pivotZoneInvalid
                    && !pivotSafe(setup.direction, setup.pivotZone.price(), setup.activeStop)) {
                FrozenZone invalidatedZone = setup.pivotZone;
                setup.pivotZoneInvalid = true;
                boolean refreshEnabled = ruleConfig.pivotRefreshRule() == PivotRefreshRule.REFRESH_AFTER_STOP_INVALIDATION;
                if (refreshEnabled) {
                    setup.pivotInvalidatedAt = stop.activationTime();
                    setup.pivotInvalidatedCenterTime = invalidatedZone.pivotTime();
                    setup.pivotCandidate = null;
                    setup.pivotZone = null;
                }
                ObjectNode audit = auditForSetup(setup.id);
                if (audit != null) {
                    ObjectNode staging = (ObjectNode) audit.path("staging");
                    staging.put("pivot_zone_invalidated", true).put("pivot_zone_invalidated_by_stop", true)
                            .put("pivot_zone_invalidated_at", stop.activationTime().toString())
                            .put("later_pivot_replacement_suppressed", !refreshEnabled);
                    if (refreshEnabled) {
                        staging.put("pivot_zone_currently_invalidated_by_stop", true);
                        ArrayNode invalidations = staging.withArray("pivot_refresh_invalidations");
                        invalidations.addObject().put("invalidated_at", stop.activationTime().toString())
                                .put("invalidated_center_time", invalidatedZone.pivotTime().toString())
                                .put("invalidated_price", invalidatedZone.price());
                        staging.put("pivot_refresh_pending", true).put("pivot_refresh_deferred_for_pending_intent",
                                setup.activePending || pendingFor(setup.id));
                    }
                }
            }
        }

        private AssetState requiredState(String asset) {
            AssetState state = assets.get(canonicalAsset(asset));
            if (state == null) throw new IllegalArgumentException("no router state exists for asset " + asset);
            return state;
        }

        private RouteResult acceptDaily(AssetState state, DailyLiquidation daily) {
            state.addDaily(daily);
            RouteAccumulator result = new RouteAccumulator();
            if (variant == Variant.PRICE_OI_ONLY_EVENT_DIAGNOSTIC) return result.result();
            LocalDate day = daily.bucketStart();
            state.dailyEvaluated.add(day);
            Instant availableAt = daily.modeledAvailableAt();
            DailyGate gate = state.dailyGate(day, availableAt);
            String dayKey = state.asset + "|" + day;
            ObjectNode audit = JsonHashes.mapper().createObjectNode().put("audit_id", dayKey)
                    .put("asset", state.asset).put("bucket_start", day.toString())
                    .put("bucket_event_time", daily.eventTime().toString()).put("available_at", availableAt.toString())
                    .put("long_liquidations_usd", daily.longLiquidationsUsd())
                    .put("short_liquidations_usd", daily.shortLiquidationsUsd());
            dailyRuleAudit.put(dayKey, audit);
            if (gate == null) {
                audit.put("status", "DAILY_WINDOW_UNAVAILABLE").put("liquidation_stress", false)
                        .put("long_stress", false).put("short_stress", false);
                return result.result();
            }
            audit.put("long_p95_90d_usd", gate.longP95).put("short_p95_90d_usd", gate.shortP95)
                    .put("long_stress", gate.downStress).put("short_stress", gate.upStress)
                    .put("liquidation_stress", gate.downStress || gate.upStress);
            if (!gate.downStress && !gate.upStress) {
                audit.put("status", "NO_90D_P95_LIQUIDATION_STRESS");
                return result.result();
            }
            audit.put("status", "LIQUIDATION_STRESS_QUALIFIED");
            GeometryEvaluation evaluation = state.geometryForStressDay(day, availableAt, gate);
            ObjectNode geometryAudit = audit.putObject("price_oi_geometry").put("fast_qualifies", evaluation.fastQualifies)
                    .put("slow_qualifies", evaluation.slowQualifies);
            addTextArray(geometryAudit, "fast_failure_reasons", evaluation.fastFailures);
            addTextArray(geometryAudit, "slow_failure_reasons", evaluation.slowFailures);
            addTextArray(geometryAudit, "selected_geometry_failure_reasons", evaluation.selectedFailures);
            Geometry geometry = evaluation.geometry;
            if (geometry == null) {
                audit.put("disposition", evaluation.fastQualifies || evaluation.slowQualifies
                        ? "GEOMETRY_REJECTED_SELECTED_GEOMETRY" : "GEOMETRY_REJECTED_NO_FAST_OR_SLOW_MATCH");
                return result.result();
            }
            String setupId = geometry.asset + "|" + day + "|" + geometry.shockDirection + "|" + geometry.eventEnd;
            audit.put("event_id", setupId).put("selected_geometry", geometry.selectedKind.name())
                    .put("shock_direction", geometry.shockDirection.name())
                    .put("geometry_start", geometry.eventStart.toString()).put("geometry_end", geometry.eventEnd.toString())
                    .put("pre_event_atr", geometry.preEventAtr).put("prior_high", geometry.priorHigh)
                    .put("prior_low", geometry.priorLow).put("boundary", geometry.boundary)
                    .put("recovery_target", geometry.recoveryTarget).put("zone_lower", geometry.zoneLower)
                    .put("zone_upper", geometry.zoneUpper);
            result.qualifiedDailyStressEvents.add(new QualifiedDailyStressEvent(setupId, geometry.asset, day,
                    daily.eventTime(), availableAt, geometry.shockDirection, geometry.evidence));
            if (state.busy()) {
                audit.put("disposition", "SETUP_SUPPRESSED_ASSET_OCCUPIED");
                return result.result();
            }
            if (state.lastCloseTime != null && !availableAt.isAfter(state.lastCloseTime)) {
                audit.put("disposition", "SETUP_SUPPRESSED_NOT_AFTER_PRIOR_CLOSE");
                audit.put("prior_close_time", state.lastCloseTime.toString());
                return result.result();
            }
            if (anchoredStageOneOnly) {
                audit.put("disposition", "SETUP_SUPPRESSED_ANCHORED_STAGE_ONE_ONLY");
                return result.result();
            }
            createSetup(state, geometry, day, availableAt, true);
            audit.put("disposition", "SETUP_ADMITTED").put("setup_id", setupId);
            setupRuleAudit.put(setupId, audit);
            ObjectNode route = audit.putObject("route").put("branch_status", "WAITING_FOR_TWO_H4_CONFIRMATIONS")
                    .put("branch_test_count", 0).put("qualifying_h4_close_count", 0)
                    .put("branch_candidate_reset_bar_count", 0);
            if (ruleConfig.initialEntryRule() == InitialEntryRule.POST_SHOCK_CONFIRMED_H4_SWING) {
                route.put("branch_status", "WAITING_FOR_TWO_H4_SWING_BREAK_CONFIRMATIONS")
                        .put("ambiguous_post_shock_break_bar_count", 0);
            }
            ObjectNode initialEntry = audit.putObject("initial_entry").put("status", "WAITING_FOR_BRANCH")
                    .put("h1_test_count", 0);
            if (!ruleConfig.equals(RuleConfig.LEGACY_V004)) {
                initialEntry.put("entry_level", geometry.boundary).put("entry_zone_lower", geometry.zoneLower)
                        .put("entry_zone_upper", geometry.zoneUpper);
            }
            initialEntry.putObject("first_failure_counts");
            initialEntry.putObject("all_failed_gate_counts");
            ObjectNode staging = audit.putObject("staging").put("favorable_h4_seen", false).put("pivot_candidate_count", 0)
                    .put("pivot_zone_formed", false).put("pivot_zone_invalidated", false)
                    .put("h1_add_test_count", 0);
            staging.put("cycle_after_filled_stage", 0);
            staging.putObject("first_failure_counts");
            staging.putObject("all_failed_gate_counts");
            if (!ruleConfig.equals(RuleConfig.LEGACY_V004)) {
                staging.putObject("daily_context_rejection_counts");
                staging.putArray("pivot_refresh_invalidations");
                staging.putArray("pivot_refresh_replacements");
                staging.put("pivot_refresh_pending", false).put("pivot_refresh_deferred_for_pending_intent", false);
            }
            audit.putArray("completed_addition_cycles");
            audit.putObject("execution").putArray("fills");
            ((ObjectNode) audit.path("execution")).putArray("no_fills");
            return result.result();
        }

        private void addMacro(MacroClose macro) {
            if (macroSeriesId == null) macroSeriesId = macro.seriesId();
            if (!macroSeriesId.equals(macro.seriesId())) throw new IllegalArgumentException("macro series identity changed within one router replay");
            if (macroCloses.putIfAbsent(macro.closeTime(), macro) != null) {
                throw new IllegalArgumentException("duplicate macro close timestamp");
            }
        }

        private void processFourHour(AssetState state, Bar bar, RouteAccumulator result) {
            Setup setup = state.setup;
            if (setup != null && setup.filledStage == 0) {
                processBranchBar(state, setup, bar, result);
            } else if (setup != null && setup.filledStage > 0) {
                processOpenPositionFourHour(state, setup, bar, result);
            }
        }

        private void processBranchBar(AssetState state, Setup setup, Bar bar, RouteAccumulator result) {
            if (ruleConfig.initialEntryRule() == InitialEntryRule.POST_SHOCK_CONFIRMED_H4_SWING) {
                processPostShockBranchBar(state, setup, bar, result);
                return;
            }
            if (bar.eventTime().isBefore(setup.availableAt) || bar.eventTime().equals(setup.availableAt)) return;
            if (setup.pendingCancellationReason != null) return;
            if (setup.branch != null) {
                boolean invalidated = setup.branch == Branch.CONTINUATION
                        ? insideRange(bar.close(), setup.priorLow, setup.priorHigh)
                        : beyondBrokenBoundary(bar.close(), setup.shockDirection, setup.boundary);
                if (invalidated) {
                    ObjectNode audit = auditForSetup(setup.id);
                    if (audit != null) {
                        ObjectNode route = (ObjectNode) audit.path("route");
                        route.put("branch_status", "SELECTED_BRANCH_INVALIDATED_BY_OPPOSITE_STRUCTURE")
                                .put("branch_invalidated_at", bar.availableAt().toString())
                                .put("branch_invalidation_close", bar.close())
                                .put("opposite_structure_no_reroute", true);
                        audit.put("terminal_reason", setup.activePending || pendingFor(setup.id)
                                ? "BRANCH_INVALIDATED_BEFORE_FILL" : "BRANCH_INVALIDATED_NO_REROUTE")
                                .put("terminal_time", bar.availableAt().toString());
                    }
                    if (setup.activePending || pendingFor(setup.id)) {
                        cancelPending(state, setup, bar, result, "BRANCH_INVALIDATED_BEFORE_FILL");
                    } else {
                        state.setup = null;
                    }
                }
                return;
            }
            Instant branchDeadline = setup.availableAt.plus(BRANCH_WAIT);
            if (bar.eventTime().isAfter(branchDeadline) || bar.availableAt().isAfter(branchDeadline)) {
                markSetupTerminal(setup, "BRANCH_WAIT_EXPIRED_WITHOUT_TWO_CONSECUTIVE_CONFIRMATIONS", bar.availableAt());
                state.setup = null;
                return;
            }
            ObjectNode audit = auditForSetup(setup.id);
            ObjectNode routeAudit = audit == null ? null : (ObjectNode) audit.path("route");
            if (routeAudit != null) routeAudit.put("branch_test_count", routeAudit.path("branch_test_count").asInt() + 1);
            boolean resetOnThisBar = false;
            if (setup.lastBranchBarEnd != null && !bar.startTime().equals(setup.lastBranchBarEnd)) {
                setup.branchCandidate = null;
                setup.branchCandidateCount = 0;
                resetOnThisBar = true;
            }
            Branch qualifies = null;
            if (beyondBrokenBoundary(bar.close(), setup.shockDirection, setup.boundary)) qualifies = Branch.CONTINUATION;
            else if (insideRange(bar.close(), setup.priorLow, setup.priorHigh)) qualifies = Branch.REVERSAL;
            if (qualifies == null) {
                setup.branchCandidate = null;
                setup.branchCandidateCount = 0;
                if (routeAudit != null && !resetOnThisBar) routeAudit.put("branch_candidate_reset_bar_count",
                        routeAudit.path("branch_candidate_reset_bar_count").asInt() + 1);
            } else if (qualifies == setup.branchCandidate) {
                setup.branchCandidateCount++;
                if (routeAudit != null) routeAudit.put("qualifying_h4_close_count", routeAudit.path("qualifying_h4_close_count").asInt() + 1);
                if (setup.branchCandidateCount >= 2) {
                    setup.branch = qualifies;
                    setup.direction = qualifies == Branch.CONTINUATION ? setup.shockDirection : setup.shockDirection.opposite();
                    setup.armTime = bar.availableAt();
                    setup.lastBranchBarEnd = bar.eventTime();
                    if (routeAudit != null) routeAudit.put("branch_status", "BRANCH_CONFIRMED")
                            .put("selected_branch", qualifies.name()).put("trade_direction", setup.direction.name())
                            .put("branch_confirmed_at", bar.availableAt().toString())
                            .put("second_confirmation_close", bar.close());
                    if (audit != null) ((ObjectNode) audit.path("initial_entry")).put("status", "WAITING_FOR_H1_RETEST_CONFIRMATION");
                    return;
                }
            } else {
                setup.branchCandidate = qualifies;
                setup.branchCandidateCount = 1;
                if (routeAudit != null) routeAudit.put("qualifying_h4_close_count", routeAudit.path("qualifying_h4_close_count").asInt() + 1)
                        .put("latest_branch_candidate", qualifies.name()).put("latest_candidate_time", bar.availableAt().toString());
            }
            setup.lastBranchBarEnd = bar.eventTime();
        }

        private void processPostShockBranchBar(AssetState state, Setup setup, Bar bar, RouteAccumulator result) {
            if (setup.pendingCancellationReason != null) return;
            if (setup.branch != null) {
                if (setup.lastBranchBarEnd == null || !bar.eventTime().isAfter(setup.lastBranchBarEnd)
                        || bar.availableAt().isBefore(setup.armTime)) return;
                boolean invalidated = setup.direction == Direction.LONG
                        ? bar.close() < setup.entryZoneLower : bar.close() > setup.entryZoneUpper;
                if (invalidated) {
                    ObjectNode audit = auditForSetup(setup.id);
                    if (audit != null) {
                        ObjectNode route = (ObjectNode) audit.path("route");
                        route.put("branch_status", "POST_SHOCK_ENTRY_ZONE_INVALIDATED_BEFORE_FILL")
                                .put("entry_zone_invalidated_at", bar.availableAt().toString())
                                .put("entry_zone_invalidation_close", bar.close())
                                .put("entry_zone_lower", setup.entryZoneLower)
                                .put("entry_zone_upper", setup.entryZoneUpper)
                                .put("opposite_structure_no_reroute", true);
                        audit.put("terminal_reason", setup.activePending || pendingFor(setup.id)
                                ? "POST_SHOCK_ENTRY_ZONE_INVALIDATED_BEFORE_FILL"
                                : "POST_SHOCK_ENTRY_ZONE_INVALIDATED_NO_REROUTE")
                                .put("terminal_time", bar.availableAt().toString());
                    }
                    if (setup.activePending || pendingFor(setup.id)) {
                        cancelPending(state, setup, bar, result, "POST_SHOCK_ENTRY_ZONE_INVALIDATED_BEFORE_FILL");
                    } else {
                        state.setup = null;
                    }
                }
                return;
            }
            Instant branchDeadline = setup.availableAt.plus(BRANCH_WAIT);
            if (bar.startTime().isBefore(setup.availableAt)) return;
            if (bar.eventTime().isAfter(branchDeadline) || bar.availableAt().isAfter(branchDeadline)) {
                markSetupTerminal(setup, "BRANCH_WAIT_EXPIRED_WITHOUT_TWO_H4_SWING_BREAK_CONFIRMATIONS", bar.availableAt());
                state.setup = null;
                return;
            }
            ObjectNode audit = auditForSetup(setup.id);
            ObjectNode routeAudit = audit == null ? null : (ObjectNode) audit.path("route");
            if (routeAudit != null) routeAudit.put("branch_test_count", routeAudit.path("branch_test_count").asInt() + 1);

            if (setup.postShockBreakPivot != null) {
                boolean consecutive = setup.firstPostShockBreakBar != null
                        && bar.startTime().equals(setup.firstPostShockBreakBar.eventTime());
                boolean stillBeyond = consecutive && (setup.postShockBreakDirection == Direction.LONG
                        ? bar.close() > setup.postShockBreakPivot.price()
                        : bar.close() < setup.postShockBreakPivot.price());
                if (stillBeyond) {
                    confirmPostShockBranch(setup, bar, routeAudit, audit);
                    return;
                }
                setup.postShockBreakPivot = null;
                setup.postShockBreakDirection = null;
                setup.firstPostShockBreakBar = null;
                if (routeAudit != null) {
                    routeAudit.put("branch_candidate_reset_bar_count",
                            routeAudit.path("branch_candidate_reset_bar_count").asInt() + 1)
                            .put("latest_post_shock_candidate_reset_at", bar.availableAt().toString())
                            .put("latest_post_shock_candidate_reset_reason",
                                    consecutive ? "SECOND_CLOSE_FAILED_SAME_LEVEL" : "H4_GAP_BEFORE_SECOND_CLOSE");
                }
            }
            evaluatePostShockFirstBreak(state, setup, bar, routeAudit, audit);
        }

        private void evaluatePostShockFirstBreak(AssetState state, Setup setup, Bar bar,
                ObjectNode routeAudit, ObjectNode audit) {
            Pivot latestHigh = state.latestConfirmedPivot(state.h4ConfirmedPivotHighs,
                    setup.eventEnd, bar.startTime());
            Pivot latestLow = state.latestConfirmedPivot(state.h4ConfirmedPivotLows,
                    setup.eventEnd, bar.startTime());
            boolean longBreak = latestHigh != null && bar.close() > latestHigh.price();
            boolean shortBreak = latestLow != null && bar.close() < latestLow.price();
            if (longBreak && shortBreak) {
                if (routeAudit != null) routeAudit.put("ambiguous_post_shock_break_bar_count",
                        routeAudit.path("ambiguous_post_shock_break_bar_count").asInt() + 1)
                        .put("latest_post_shock_candidate_status", "AMBIGUOUS_BOTH_DIRECTIONS")
                        .put("latest_post_shock_candidate_bar_start", bar.startTime().toString())
                        .put("latest_confirmed_pivot_high", latestHigh.price())
                        .put("latest_confirmed_pivot_low", latestLow.price());
                return;
            }
            if (!longBreak && !shortBreak) {
                if (routeAudit != null) routeAudit.put("branch_candidate_reset_bar_count",
                        routeAudit.path("branch_candidate_reset_bar_count").asInt() + 1);
                return;
            }
            setup.postShockBreakDirection = longBreak ? Direction.LONG : Direction.SHORT;
            setup.postShockBreakPivot = longBreak ? latestHigh : latestLow;
            setup.firstPostShockBreakBar = bar;
            setup.lastBranchBarEnd = bar.eventTime();
            if (routeAudit != null) {
                routeAudit.put("qualifying_h4_close_count", routeAudit.path("qualifying_h4_close_count").asInt() + 1)
                        .put("latest_post_shock_candidate_status", "WAITING_FOR_SECOND_CLOSE")
                        .put("latest_post_shock_candidate_direction", setup.postShockBreakDirection.name())
                        .put("latest_post_shock_candidate_bar_start", bar.startTime().toString())
                        .put("latest_post_shock_candidate_available_at", bar.availableAt().toString())
                        .put("latest_post_shock_candidate_level", setup.postShockBreakPivot.price())
                        .put("latest_post_shock_candidate_pivot_center", setup.postShockBreakPivot.centerTime().toString())
                        .put("latest_post_shock_candidate_pivot_confirmed_at", setup.postShockBreakPivot.confirmedAt().toString());
            }
            if (audit != null) ((ObjectNode) audit.path("initial_entry")).put("status", "WAITING_FOR_SECOND_POST_SHOCK_BREAK_CLOSE");
        }

        private void confirmPostShockBranch(Setup setup, Bar secondBar, ObjectNode routeAudit, ObjectNode audit) {
            setup.direction = setup.postShockBreakDirection;
            setup.branch = setup.direction == setup.shockDirection ? Branch.CONTINUATION : Branch.REVERSAL;
            setup.selectedEntryPivot = setup.postShockBreakPivot;
            setup.entryLevel = setup.selectedEntryPivot.price();
            setup.entryZoneLower = setup.entryLevel - INITIAL_ZONE_HALF_WIDTH_ATR * setup.preEventAtr;
            setup.entryZoneUpper = setup.entryLevel + INITIAL_ZONE_HALF_WIDTH_ATR * setup.preEventAtr;
            setup.armTime = secondBar.availableAt();
            setup.lastBranchBarEnd = secondBar.eventTime();
            setup.entryStructureEvidence = new ArrayList<>();
            for (SourceEvidence source : setup.selectedEntryPivot.evidence()) {
                setup.entryStructureEvidence.add(new SourceEvidence("POST_SHOCK_ENTRY_PIVOT_SOURCE", source.asset(),
                        source.seriesId(), source.eventTime(), source.availableAt()));
            }
            setup.entryStructureEvidence.add(evidence("POST_SHOCK_BREAK_FIRST_CONFIRMATION", setup.firstPostShockBreakBar));
            setup.entryStructureEvidence.add(evidence("POST_SHOCK_BREAK_SECOND_CONFIRMATION", secondBar));
            if (routeAudit != null) {
                routeAudit.put("branch_status", "POST_SHOCK_SWING_BREAK_CONFIRMED")
                        .put("selected_branch", setup.branch.name()).put("trade_direction", setup.direction.name())
                        .put("branch_confirmed_at", secondBar.availableAt().toString())
                        .put("first_confirmation_bar_start", setup.firstPostShockBreakBar.startTime().toString())
                        .put("first_confirmation_available_at", setup.firstPostShockBreakBar.availableAt().toString())
                        .put("first_confirmation_close", setup.firstPostShockBreakBar.close())
                        .put("second_confirmation_bar_start", secondBar.startTime().toString())
                        .put("second_confirmation_available_at", secondBar.availableAt().toString())
                        .put("second_confirmation_close", secondBar.close())
                        .put("selected_pivot_level", setup.entryLevel)
                        .put("selected_pivot_center_time", setup.selectedEntryPivot.centerTime().toString())
                        .put("selected_pivot_confirmed_at", setup.selectedEntryPivot.confirmedAt().toString())
                        .put("entry_zone_lower", setup.entryZoneLower).put("entry_zone_upper", setup.entryZoneUpper)
                        .put("legacy_boundary_role", "SHOCK_GEOMETRY_AND_RECOVERY_METADATA_ONLY");
            }
            if (audit != null) {
                ObjectNode entry = (ObjectNode) audit.path("initial_entry");
                entry.put("status", "WAITING_FOR_H1_RETEST_CONFIRMATION").put("entry_level", setup.entryLevel)
                        .put("entry_zone_lower", setup.entryZoneLower).put("entry_zone_upper", setup.entryZoneUpper)
                        .put("pivot_center_time", setup.selectedEntryPivot.centerTime().toString())
                        .put("pivot_confirmed_at", setup.selectedEntryPivot.confirmedAt().toString());
            }
            setup.postShockBreakPivot = null;
            setup.postShockBreakDirection = null;
            setup.firstPostShockBreakBar = null;
        }

        private void processOneHour(AssetState state, Bar bar, RouteAccumulator result) {
            Setup setup = state.setup;
            if (setup == null) return;
            if (setup.pendingCancellationReason != null) return;
            if (setup.filledStage > 0) {
                processOpenPositionOneHour(state, setup, bar, result);
                return;
            }
            if (anchoredStageOneOnly) return;
            if (setup.branch == null) return;
            Instant deadline = setup.armTime.plus(INITIAL_ZONE_WAIT);
            if (bar.eventTime().isAfter(deadline) || bar.availableAt().isAfter(deadline)) {
                if (!setup.activePending && !pendingFor(setup.id)) {
                    markSetupTerminal(setup, initialEntryExpiryReason(setup.id), bar.availableAt());
                    state.setup = null;
                }
                return;
            }
            if (bar.startTime().isBefore(setup.armTime)) return;
            Bar previous = state.barEndingAt(Timeframe.ONE_HOUR, bar.startTime());
            ObjectNode audit = auditForSetup(setup.id);
            ObjectNode entryAudit = audit == null ? null : (ObjectNode) audit.path("initial_entry");
            List<String> failCodes = entryFailureCodes(bar, previous, setup.direction,
                    setup.entryLevel, setup.entryZoneLower, setup.entryZoneUpper);
            String failCode = failCodes.isEmpty() ? null : failCodes.get(0);
            if (entryAudit != null) {
                entryAudit.put("h1_test_count", entryAudit.path("h1_test_count").asInt() + 1);
                if (failCode != null) incrementCount((ObjectNode) entryAudit.path("first_failure_counts"), failCode);
                for (String code : failCodes) incrementCount((ObjectNode) entryAudit.path("all_failed_gate_counts"), code);
            }
            if (previous == null || !entryBarQualifies(bar, previous, setup.direction,
                    setup.entryLevel, setup.entryZoneLower, setup.entryZoneUpper)) return;
            emitStageOne(state, setup, bar, previous, result);
        }

        private void emitStageOne(AssetState state, Setup setup, Bar bar, Bar previous, RouteAccumulator result) {
            if (setup.activePending || pendingFor(setup.id)) {
                ObjectNode audit = auditForSetup(setup.id);
                if (audit != null) {
                    ObjectNode entryAudit = (ObjectNode) audit.path("initial_entry");
                    incrementCount((ObjectNode) entryAudit.path("first_failure_counts"), "STAGE_ONE_INTENT_ALREADY_PENDING");
                    incrementCount((ObjectNode) entryAudit.path("all_failed_gate_counts"), "STAGE_ONE_INTENT_ALREADY_PENDING");
                }
                return;
            }
            ObjectNode audit = auditForSetup(setup.id);
            List<Bar> h1LastThree = state.lastConsecutiveBars(Timeframe.ONE_HOUR, bar.eventTime(), 3);
            List<Bar> stopBars;
            String missingStopWindowReason;
            if (ruleConfig.initialStopRule() == InitialStopRule.H4_THREE_BAR) {
                stopBars = state.lastExpectedCompletedBars(Timeframe.FOUR_HOUR, bar.availableAt(), 3);
                missingStopWindowReason = "THREE_CONSECUTIVE_H4_STOP_BARS_UNAVAILABLE_AT_DECISION";
            } else {
                stopBars = h1LastThree;
                missingStopWindowReason = "THREE_CONSECUTIVE_H1_CONFIRMATION_WINDOW_UNAVAILABLE";
            }
            if (stopBars.size() != 3) {
                if (audit != null) {
                    ObjectNode entryAudit = (ObjectNode) audit.path("initial_entry");
                    incrementCount((ObjectNode) entryAudit.path("first_failure_counts"), missingStopWindowReason);
                    incrementCount((ObjectNode) entryAudit.path("all_failed_gate_counts"), missingStopWindowReason);
                }
                return;
            }
            List<SourceEvidence> common = new ArrayList<>(setup.geometryEvidence);
            common.addAll(setup.entryStructureEvidence);
            common.add(evidence("CONFIRMATION", bar));
            common.add(evidence("PREVIOUS_HOUR", previous));
            if (setup.branch == Branch.REVERSAL && targetReachedByBar(setup.recoveryTarget, setup.direction, bar)) {
                if (audit != null) {
                    ObjectNode entryAudit = (ObjectNode) audit.path("initial_entry");
                    entryAudit.put("reversal_target_reached_on_confirmation", true)
                            .put("reversal_target_reached_at", bar.availableAt().toString());
                    incrementCount((ObjectNode) entryAudit.with("first_failure_counts"), "REVERSAL_TARGET_REACHED_BEFORE_ENTRY");
                    incrementCount((ObjectNode) entryAudit.with("all_failed_gate_counts"), "REVERSAL_TARGET_REACHED_BEFORE_ENTRY");
                }
                return;
            }
            Instant decisionTime = bar.availableAt();
            String pairId = setup.id + "|" + decisionTime + "|" + bar.startTime();
            List<Variant> variants = switch (variant) {
                case ROUTED_REVERSAL_CONTINUATION, PRICE_OI_ONLY_EVENT_DIAGNOSTIC -> List.of(variant);
                case ALWAYS_CONTINUATION_CONTROL, ALWAYS_REVERSAL_CONTROL -> List.of(variant);
            };
            for (Variant candidate : variants) {
                Branch forcedBranch = switch (candidate) {
                    case ALWAYS_CONTINUATION_CONTROL -> Branch.CONTINUATION;
                    case ALWAYS_REVERSAL_CONTROL -> Branch.REVERSAL;
                    default -> setup.branch;
                };
                Direction direction = forcedBranch == Branch.CONTINUATION ? setup.shockDirection : setup.shockDirection.opposite();
                double stop = structuralStop(stopBars, direction, setup.preEventAtr);
                List<SourceEvidence> evidence = new ArrayList<>(common);
                String stopRole = ruleConfig.initialStopRule() == InitialStopRule.H4_THREE_BAR
                        ? "INITIAL_H4_STOP_SOURCE" : "INITIAL_H1_STOP_SOURCE";
                if (ruleConfig.initialStopRule() == InitialStopRule.H4_THREE_BAR) {
                    stopBars.stream().map(source -> evidence(stopRole, source)).forEach(evidence::add);
                }
                DailyContextAssessment entryContext = dailyContextAssessment(setup.asset, direction, decisionTime);
                List<String> reasons = new ArrayList<>();
                if (!stopAdverse(direction, stop, bar.close())) reasons.add(
                        ruleConfig.initialStopRule() == InitialStopRule.H4_THREE_BAR
                                ? "INITIAL_H4_STOP_NOT_ADVERSE_AT_ENTRY_DECISION"
                                : "CONTROL_STOP_NOT_ADVERSE_AT_SHARED_DECISION");
                Double target = forcedBranch == Branch.REVERSAL ? setup.recoveryTarget : null;
                if (target != null && !targetAhead(direction, target, bar.close())) {
                    reasons.add("REVERSAL_TARGET_AT_OR_BEHIND_SHARED_DECISION");
                }
                if (target != null && targetReachedByBar(target, direction, bar)) {
                    reasons.add("REVERSAL_TARGET_REACHED_DURING_CONFIRMATION_BAR");
                }
                if (reasons.isEmpty()) {
                    ConfirmedIntent intent = makeIntent(setup, pairId, candidate, forcedBranch, setup.branch,
                            direction, 1, bar, decisionTime, setup.entryLevel, setup.entryZoneLower, setup.entryZoneUpper,
                            setup.selectedEntryPivot == null ? null : setup.selectedEntryPivot.price(),
                            setup.selectedEntryPivot == null ? null : setup.selectedEntryPivot.centerTime(),
                            setup.selectedEntryPivot == null ? null : setup.selectedEntryPivot.confirmedAt(),
                            stop, target, MacroState.NOT_REQUIRED, true, evidence, List.of());
                    result.intents.add(intent);
                    if (audit != null) {
                        ObjectNode entryAudit = (ObjectNode) audit.path("initial_entry");
                        entryAudit.put("status", "STAGE_ONE_INTENT_EMITTED").put("branch", intent.branch().name())
                                .put("intent_count", entryAudit.path("intent_count").asInt() + 1)
                                .put("direction", intent.direction().name()).put("decision_time", decisionTime.toString())
                                .put("confirmation_bar_start", bar.startTime().toString()).put("confirmation_close", bar.close())
                                .put("boundary", setup.boundary).put("zone_lower", setup.zoneLower).put("zone_upper", setup.zoneUpper)
                                .put("structural_stop", stop);
                        if (!ruleConfig.equals(RuleConfig.LEGACY_V004)) {
                            entryAudit.put("entry_level", setup.entryLevel).put("entry_zone_lower", setup.entryZoneLower)
                                    .put("entry_zone_upper", setup.entryZoneUpper)
                                    .put("initial_stop_rule", ruleConfig.initialStopRule().name());
                        }
                        if (ruleConfig.initialStopRule() == InitialStopRule.H4_THREE_BAR) {
                            ArrayNode stopSourceAudit = entryAudit.putArray("initial_stop_source_bars");
                            stopBars.forEach(source -> stopSourceAudit.addObject().put("start_time", source.startTime().toString())
                                    .put("event_time", source.eventTime().toString()).put("available_at", source.availableAt().toString())
                                    .put("low", source.low()).put("high", source.high()));
                        }
                        if (ruleConfig.logDailyContext()) entryAudit.set("daily_context", entryContext.toJson());
                        if (target == null) entryAudit.putNull("recovery_target"); else entryAudit.put("recovery_target", target);
                    }
                    if (candidate == Variant.ROUTED_REVERSAL_CONTINUATION
                            && ruleConfig.initialEntryRule() == InitialEntryRule.PRE_SHOCK_BOUNDARY
                            && ruleConfig.initialStopRule() == InitialStopRule.LEGACY_H1_THREE_BAR
                            && h1LastThree.size() == 3) {
                        result.pairedDecisionContexts.add(new PairedDecisionContext(
                                intent, setup.recoveryTarget, h1LastThree));
                        result.stageOneAnchorContexts.add(new StageOneAnchorContext(intent,
                                new AnchorSetupSeed(setup.id, setup.asset, setup.shockDirection,
                                        setup.eventStart, setup.eventEnd, setup.availableAt,
                                        setup.preEventAtr, setup.priorHigh, setup.priorLow, setup.boundary,
                                        setup.recoveryTarget, setup.zoneLower, setup.zoneUpper,
                                        setup.fastGeometry, setup.slowGeometry, setup.selectedGeometry.name(),
                                        setup.geometryEvidence)));
                    }
                    state.setup.activePending = true;
                    pendingIntents.put(intent.intentId(), intent);
                } else {
                    if (audit != null) {
                        ObjectNode entryAudit = (ObjectNode) audit.path("initial_entry");
                        entryAudit.put("last_structural_rejection_reason", String.join(";", reasons))
                                .put("last_structural_rejection_time", decisionTime.toString());
                        String firstFailure = ruleConfig.equals(RuleConfig.LEGACY_V004)
                                ? "STAGE_ONE_STRUCTURAL_REJECTION" : reasons.get(0);
                        incrementCount((ObjectNode) entryAudit.with("first_failure_counts"), firstFailure);
                        for (String reason : reasons) incrementCount((ObjectNode) entryAudit.with("all_failed_gate_counts"), reason);
                    }
                    result.rejections.add(rejected(setup, pairId, candidate, forcedBranch, direction, 1,
                            decisionTime, String.join(";", reasons), MacroState.NOT_REQUIRED, evidence));
                }
            }
        }

        private void processOpenPositionFourHour(AssetState state, Setup setup, Bar bar, RouteAccumulator result) {
            if (setup.lastFillTime == null || !bar.eventTime().isAfter(setup.lastFillTime)) return;
            if (setup.branch == Branch.REVERSAL && targetReachedByBar(setup.recoveryTarget, setup.direction, bar)) {
                setup.recoveryTargetReached = true;
            }
            if (setup.branch == Branch.CONTINUATION && setup.filledStage >= 1) {
                List<Bar> lastThree = state.lastConsecutiveBars(Timeframe.FOUR_HOUR, bar.eventTime(), 3);
                if (lastThree.size() == 3) {
                    double candidate = setup.direction == Direction.LONG
                            ? minLow(lastThree) - STOP_BUFFER_ATR * setup.preEventAtr
                            : maxHigh(lastThree) + STOP_BUFFER_ATR * setup.preEventAtr;
                    if (tightens(setup.direction, setup.activeStop, candidate)) {
                        List<SourceEvidence> evidence = lastThree.stream().map(item -> evidence("TRAIL_SOURCE", item)).toList();
                        String updateId = setup.id + "|TRAIL|" + bar.eventTime();
                        result.stopUpdates.add(new StopUpdateIntent(updateId, setup.id, setup.asset,
                                bar.availableAt(), setup.activeStop, candidate, setup.direction, true, evidence));
                    }
                }
            }
            if (setup.filledStage < 3 && variant == Variant.ROUTED_REVERSAL_CONTINUATION
                    && !setup.recoveryTargetReached) {
                updatePivotAndFavorability(state, setup, bar);
            }
        }

        private void updatePivotAndFavorability(AssetState state, Setup setup, Bar bar) {
            if (setup.lastFillTime == null || !bar.eventTime().isAfter(setup.lastFillTime)) return;
            ObjectNode audit = auditForSetup(setup.id);
            ObjectNode stagingAudit = audit == null ? null : (ObjectNode) audit.path("staging");
            boolean favorable = setup.direction == Direction.LONG ? bar.close() > bar.open() : bar.close() < bar.open();
            if (favorable && setup.favorableH4AvailableAt == null) {
                setup.favorableH4Seen = true;
                setup.favorableH4AvailableAt = bar.availableAt();
                setup.favorableH4Evidence = evidence("FAVORABLE_AFTER_FILL", bar);
                if (stagingAudit != null) stagingAudit.put("favorable_h4_seen", true)
                        .put("first_favorable_h4_available_at", bar.availableAt().toString());
            }
            boolean refreshEnabled = ruleConfig.pivotRefreshRule() == PivotRefreshRule.REFRESH_AFTER_STOP_INVALIDATION;
            if (setup.pivotZoneInvalid && !refreshEnabled) {
                if (stagingAudit != null) stagingAudit.put("h4_bars_skipped_after_frozen_pivot_invalidation",
                        stagingAudit.path("h4_bars_skipped_after_frozen_pivot_invalidation").asInt() + 1);
                return;
            }
            if (setup.pivotZone != null && !setup.pivotZoneInvalid) return;
            if (setup.pivotZoneInvalid && (setup.activePending || pendingFor(setup.id))) {
                if (stagingAudit != null) {
                    stagingAudit.put("pivot_refresh_deferred_for_pending_intent", true);
                    stagingAudit.put("h4_bars_skipped_while_refresh_deferred",
                            stagingAudit.path("h4_bars_skipped_while_refresh_deferred").asInt() + 1);
                }
                return;
            }
            List<Bar> lastFive = state.lastConsecutiveBars(Timeframe.FOUR_HOUR, bar.eventTime(), 5);
            if (lastFive.size() != 5 || refreshEnabled
                    && lastFive.stream().anyMatch(source -> source.availableAt().isAfter(bar.availableAt()))) {
                if (stagingAudit != null) stagingAudit.put("h4_windows_without_five_consecutive_bars",
                        stagingAudit.path("h4_windows_without_five_consecutive_bars").asInt() + 1);
                return;
            }
            Bar center = lastFive.get(2);
            if (!center.startTime().isAfter(setup.lastFillTime)) {
                if (stagingAudit != null) stagingAudit.put("pivot_centers_not_strictly_after_fill",
                        stagingAudit.path("pivot_centers_not_strictly_after_fill").asInt() + 1);
                return;
            }
            if (setup.pivotZoneInvalid && setup.pivotInvalidatedCenterTime != null
                    && !center.startTime().isAfter(setup.pivotInvalidatedCenterTime)) {
                if (stagingAudit != null) stagingAudit.put("replacement_pivots_not_later_than_invalidated_center",
                        stagingAudit.path("replacement_pivots_not_later_than_invalidated_center").asInt() + 1);
                return;
            }
            if (setup.pivotZoneInvalid && (setup.pivotInvalidatedAt == null
                    || !bar.eventTime().isAfter(setup.pivotInvalidatedAt)
                    || !bar.availableAt().isAfter(setup.pivotInvalidatedAt))) {
                if (stagingAudit != null) stagingAudit.put("replacement_pivots_confirmed_not_after_invalidation",
                        stagingAudit.path("replacement_pivots_confirmed_not_after_invalidation").asInt() + 1);
                return;
            }
            boolean pivot = setup.direction == Direction.LONG ? pivotLow(lastFive) : pivotHigh(lastFive);
            if (!pivot && stagingAudit != null) stagingAudit.put("five_bar_windows_without_strict_pivot",
                    stagingAudit.path("five_bar_windows_without_strict_pivot").asInt() + 1);
            if (pivot) {
                double price = setup.direction == Direction.LONG ? center.low() : center.high();
                if (pivotSafe(setup.direction, price, setup.activeStop)) {
                    setup.pivotCandidate = new Pivot(price, center.startTime(), bar.availableAt(),
                            lastFive.stream().map(item -> evidence("PIVOT_SOURCE", item)).toList());
                    if (stagingAudit != null) stagingAudit.put("pivot_candidate_count", stagingAudit.path("pivot_candidate_count").asInt() + 1)
                            .put("latest_pivot_center_time", center.startTime().toString())
                            .put("latest_pivot_confirmed_at", bar.availableAt().toString())
                            .put("latest_pivot_price", price);
                    if (stagingAudit != null && !stagingAudit.path("first_pivot_confirmed_at").isTextual()) {
                        stagingAudit.put("first_pivot_center_time", center.startTime().toString())
                                .put("first_pivot_confirmed_at", bar.availableAt().toString());
                    }
                } else if (stagingAudit != null) {
                    stagingAudit.put("strict_pivots_unsafe_vs_active_stop",
                            stagingAudit.path("strict_pivots_unsafe_vs_active_stop").asInt() + 1);
                }
            }
            if (setup.pivotCandidate == null) return;
            if (!setup.favorableH4Seen) return;
            setup.pivotZone = new FrozenZone(setup.pivotCandidate.price,
                    setup.pivotCandidate.price - INITIAL_ZONE_HALF_WIDTH_ATR * setup.preEventAtr,
                    setup.pivotCandidate.price + INITIAL_ZONE_HALF_WIDTH_ATR * setup.preEventAtr,
                    setup.pivotCandidate.price, setup.pivotCandidate.centerTime,
                    setup.pivotCandidate.confirmedAt,
                    later(setup.pivotCandidate.confirmedAt, setup.favorableH4AvailableAt),
                    combined(setup.pivotCandidate.evidence, setup.favorableH4Evidence));
            if (setup.pivotZoneInvalid) {
                setup.pivotReplacementCount++;
                setup.pivotZoneInvalid = false;
                setup.pivotInvalidatedAt = null;
                setup.pivotInvalidatedCenterTime = null;
            }
            if (stagingAudit != null) stagingAudit.put("pivot_zone_formed", true)
                    .put("pivot_zone_eligible_at", setup.pivotZone.eligibleAt.toString())
                    .put("pivot_zone_center", setup.pivotZone.center)
                    .put("pivot_zone_lower", setup.pivotZone.lower).put("pivot_zone_upper", setup.pivotZone.upper);
            if (stagingAudit != null && refreshEnabled && setup.pivotReplacementCount > 0) {
                stagingAudit.put("pivot_zone_invalidated", false).put("pivot_zone_currently_invalidated_by_stop", false);
                ObjectNode replacement = stagingAudit.withArray("pivot_refresh_replacements").addObject();
                replacement.put("replacement_number", setup.pivotReplacementCount)
                        .put("replacement_center_time", setup.pivotZone.pivotTime.toString())
                        .put("replacement_confirmed_at", setup.pivotZone.confirmedAt.toString())
                        .put("replacement_eligible_at", setup.pivotZone.eligibleAt.toString())
                        .put("replacement_price", setup.pivotZone.price);
                stagingAudit.put("pivot_refresh_pending", false);
            }
        }

        private void processOpenPositionOneHour(AssetState state, Setup setup, Bar bar, RouteAccumulator result) {
            if (setup.lastFillTime == null || !bar.startTime().isAfter(setup.lastFillTime)) return;
            ObjectNode audit = auditForSetup(setup.id);
            ObjectNode stagingAudit = audit == null ? null : (ObjectNode) audit.path("staging");
            if (setup.pivotZoneInvalid) {
                if (stagingAudit != null) stagingAudit.put("h1_bars_skipped_after_pivot_zone_invalidation",
                        stagingAudit.path("h1_bars_skipped_after_pivot_zone_invalidation").asInt() + 1);
                return;
            }
            if (setup.pivotZone == null) {
                if (stagingAudit != null) stagingAudit.put("h1_bars_before_pivot_zone",
                        stagingAudit.path("h1_bars_before_pivot_zone").asInt() + 1);
                return;
            }
            if (setup.filledStage >= 3 || setup.recoveryTargetReached || setup.activePending || pendingFor(setup.id)) {
                if (stagingAudit != null) {
                    String reason = setup.filledStage >= 3 ? "MAX_THREE_TRANCHES_FILLED"
                            : setup.recoveryTargetReached ? "REVERSAL_RECOVERY_TARGET_REACHED"
                            : "ADDITION_INTENT_ALREADY_PENDING";
                    incrementCount((ObjectNode) stagingAudit.with("h1_additional_suppressions"), reason);
                }
                return;
            }
            if (bar.startTime().isBefore(setup.pivotZone.eligibleAt)) return;
            Instant entryHour = bar.startTime().truncatedTo(ChronoUnit.HOURS);
            if (setup.lastAddDecisionHour != null && !entryHour.isAfter(setup.lastAddDecisionHour)) return;
            Bar previous = state.barEndingAt(Timeframe.ONE_HOUR, bar.startTime());
            List<String> failCodes = entryFailureCodes(bar, previous, setup.direction, setup.pivotZone.center,
                    setup.pivotZone.lower, setup.pivotZone.upper);
            String failCode = failCodes.isEmpty() ? null : failCodes.get(0);
            if (stagingAudit != null) {
                stagingAudit.put("h1_add_test_count", stagingAudit.path("h1_add_test_count").asInt() + 1);
                if (failCode != null) incrementCount((ObjectNode) stagingAudit.path("first_failure_counts"), failCode);
                for (String code : failCodes) incrementCount((ObjectNode) stagingAudit.path("all_failed_gate_counts"), code);
            }
            if (previous == null || !entryBarQualifies(bar, previous, setup.direction, setup.pivotZone.center,
                    setup.pivotZone.lower, setup.pivotZone.upper)) return;
            int stage = setup.filledStage + 1;
            List<SourceEvidence> evidence = new ArrayList<>(setup.pivotZone.evidence);
            evidence.add(evidence("ADD_CONFIRMATION", bar));
            evidence.add(evidence("PREVIOUS_HOUR", previous));
            Instant decisionTime = bar.availableAt();
            MacroAssessment macro = macroGatePolicy == MacroGatePolicy.STRUCTURE_ONLY
                    ? new MacroAssessment(MacroState.NOT_REQUIRED, List.of())
                    : macroAssessment(decisionTime, setup.direction);
            MacroState macroState = macro.state;
            evidence.addAll(macro.evidence);
            DailyContextAssessment dailyContext = dailyContextAssessment(setup.asset, setup.direction, decisionTime);
            List<String> contextFailures = dailyContextFailures(dailyContext, stage);
            if (ruleConfig.logDailyContext() || ruleConfig.dailyRsiAdditionGate() || ruleConfig.dailySma200AdditionGate()) {
                if (ruleConfig.dailyRsiAdditionGate() || ruleConfig.dailySma200AdditionGate()) {
                    evidence.addAll(dailyContextEvidence(dailyContext));
                }
                if (stagingAudit != null) stagingAudit.set("stage_" + stage + "_daily_context", dailyContext.toJson());
            }
            boolean macroEligible = macroGatePolicy == MacroGatePolicy.STRUCTURE_ONLY || (stage == 2
                    ? macroState == MacroState.SUPPORTIVE || macroState == MacroState.NEUTRAL
                    : macroState == MacroState.SUPPORTIVE);
            boolean targetFails = setup.branch == Branch.REVERSAL && !targetAhead(setup.direction, setup.recoveryTarget, bar.close());
            boolean stopFails = !stopAdverse(setup.direction, setup.activeStop, bar.close());
            List<String> allStageFailures = new ArrayList<>();
            if (!macroEligible) allStageFailures.add("MACRO_" + macroState + "_BLOCKS_STAGE_" + stage);
            allStageFailures.addAll(contextFailures);
            if (targetFails) allStageFailures.add("REVERSAL_TARGET_AT_OR_BEHIND_ADDITION_DECISION");
            if (stopFails) allStageFailures.add("COMMON_STOP_NOT_ADVERSE_AT_ADDITION_DECISION");
            if (stagingAudit != null) for (String reason : allStageFailures) {
                incrementCount((ObjectNode) stagingAudit.path("all_failed_gate_counts"), reason);
            }
            if (stagingAudit != null && !contextFailures.isEmpty()) {
                String dailyCountKey = "stage_" + stage + "_daily_context_rejection_count";
                stagingAudit.put(dailyCountKey, stagingAudit.path(dailyCountKey).asInt() + 1)
                        .put("stage_" + stage + "_last_daily_context_rejection_time", decisionTime.toString());
                if (!stagingAudit.path("stage_" + stage + "_first_daily_context_rejection_time").isTextual()) {
                    stagingAudit.put("stage_" + stage + "_first_daily_context_rejection_time", decisionTime.toString());
                }
                ObjectNode contextReasons = (ObjectNode) stagingAudit.with("daily_context_rejection_counts");
                for (String failure : contextFailures) incrementCount(contextReasons, failure);
            }
            String pairId = setup.id + "|ADD" + stage + "|" + decisionTime + "|" + bar.startTime();
            if (!macroEligible || !contextFailures.isEmpty()) {
                if (stagingAudit != null) {
                    String rejection = String.join(";", allStageFailures.stream()
                            .filter(reason -> reason.startsWith("MACRO_") || reason.startsWith("DAILY_"))
                            .toList());
                    stagingAudit.put("stage_" + stage + "_router_rejection", rejection);
                    if (!macroEligible) {
                        String macroRejection = "MACRO_" + macroState + "_BLOCKS_STAGE_" + stage;
                        stagingAudit.put("stage_" + stage + "_last_macro_rejection_time", decisionTime.toString());
                        if (!stagingAudit.path("stage_" + stage + "_first_macro_rejection_time").isTextual()) {
                            stagingAudit.put("stage_" + stage + "_first_macro_rejection_time", decisionTime.toString());
                        }
                    }
                    if (!contextFailures.isEmpty()) {
                        stagingAudit.put("stage_" + stage + "_last_daily_context_rejection_time", decisionTime.toString());
                        if (!stagingAudit.path("stage_" + stage + "_first_daily_context_rejection_time").isTextual()) {
                            stagingAudit.put("stage_" + stage + "_first_daily_context_rejection_time", decisionTime.toString());
                        }
                    }
                    String firstGateFailure = !macroEligible ? "MACRO_" + macroState + "_BLOCKS_STAGE_" + stage
                            : contextFailures.get(0);
                    incrementCount((ObjectNode) stagingAudit.with("first_failure_counts"), firstGateFailure);
                }
                result.rejections.add(rejected(setup, pairId, variant, setup.branch, setup.direction, stage,
                        decisionTime, String.join(";", allStageFailures.stream()
                                .filter(reason -> reason.startsWith("MACRO_") || reason.startsWith("DAILY_"))
                                .toList()),
                        macroState, evidence));
                setup.lastAddDecisionHour = entryHour;
                return;
            }
            if (targetFails) {
                if (stagingAudit != null) stagingAudit.put("stage_" + stage + "_router_rejection", "REVERSAL_TARGET_AT_OR_BEHIND_ADDITION_DECISION");
                if (stagingAudit != null) incrementCount((ObjectNode) stagingAudit.path("first_failure_counts"),
                        "REVERSAL_TARGET_AT_OR_BEHIND_ADDITION_DECISION");
                result.rejections.add(rejected(setup, pairId, variant, setup.branch, setup.direction, stage,
                        decisionTime, "REVERSAL_TARGET_AT_OR_BEHIND_ADDITION_DECISION",
                        macroState, evidence));
                setup.recoveryTargetReached = true;
                return;
            }
            double stop = setup.activeStop;
            if (stopFails) {
                if (stagingAudit != null) stagingAudit.put("stage_" + stage + "_router_rejection", "COMMON_STOP_NOT_ADVERSE_AT_ADDITION_DECISION");
                if (stagingAudit != null) incrementCount((ObjectNode) stagingAudit.path("first_failure_counts"),
                        "COMMON_STOP_NOT_ADVERSE_AT_ADDITION_DECISION");
                result.rejections.add(rejected(setup, pairId, variant, setup.branch, setup.direction, stage,
                        decisionTime, "COMMON_STOP_NOT_ADVERSE_AT_ADDITION_DECISION", macroState, evidence));
                return;
            }
            ConfirmedIntent intent = makeIntent(setup, pairId, variant, setup.branch, setup.branch,
                    setup.direction, stage, bar, decisionTime, setup.pivotZone.center,
                    setup.pivotZone.lower, setup.pivotZone.upper,
                    setup.pivotZone.price, setup.pivotZone.pivotTime, setup.pivotZone.confirmedAt,
                    stop, setup.branch == Branch.REVERSAL ? setup.recoveryTarget : null,
                    macroState, true, evidence, List.of());
            result.intents.add(intent);
            if (stagingAudit != null) {
                String stagePrefix = "stage_" + stage + "_";
                stagingAudit.put(stagePrefix + "intent_time", decisionTime.toString())
                        .put(stagePrefix + "branch", intent.branch().name())
                        .put(stagePrefix + "macro_state", macroState.name())
                        .put(stagePrefix + "confirmation_close", bar.close())
                        .put(stagePrefix + "intent_count", stagingAudit.path(stagePrefix + "intent_count").asInt() + 1);
            }
            pendingIntents.put(intent.intentId(), intent);
            setup.activePending = true;
            setup.lastAddDecisionHour = entryHour;
        }

        private boolean pendingFor(String setupId) {
            return pendingIntents.values().stream().anyMatch(intent -> intent.setupId().equals(setupId));
        }

        private ObjectNode auditForSetup(String setupId) {
            return setupRuleAudit.get(setupId);
        }

        private void markSetupTerminal(Setup setup, String reason, Instant at) {
            ObjectNode audit = auditForSetup(setup.id);
            if (audit == null) return;
            if (!audit.path("terminal_reason").isTextual()) audit.put("terminal_reason", reason);
            if (!audit.path("terminal_time").isTextual()) audit.put("terminal_time", at.toString());
            ObjectNode entry = (ObjectNode) audit.path("initial_entry");
            if ("WAITING_FOR_BRANCH".equals(entry.path("status").asText())
                    || "WAITING_FOR_H1_RETEST_CONFIRMATION".equals(entry.path("status").asText())) entry.put("status", reason);
            ObjectNode route = (ObjectNode) audit.path("route");
            if ("WAITING_FOR_TWO_H4_CONFIRMATIONS".equals(route.path("branch_status").asText())) {
                route.put("branch_status", "BRANCH_WAIT_EXPIRED_WITHOUT_TWO_CONSECUTIVE_CONFIRMATIONS")
                        .put("branch_expiry_at", at.toString());
            }
        }

        private String initialEntryExpiryReason(String setupId) {
            ObjectNode audit = auditForSetup(setupId);
            int intents = audit == null ? 0 : audit.path("initial_entry").path("intent_count").asInt();
            return intents > 0
                    ? "CONFIRMED_STAGE_ONE_INTENTS_NOT_FILLED_BEFORE_ENTRY_WINDOW_END"
                    : "INITIAL_ENTRY_WINDOW_EXPIRED_WITHOUT_CONFIRMATION";
        }

        private static void resetStagingCycle(ObjectNode staging, int afterFilledStage, Instant fillTime, RuleConfig config) {
            staging.removeAll();
            staging.put("cycle_after_filled_stage", afterFilledStage).put("cycle_started_at", fillTime.toString())
                    .put("favorable_h4_seen", false).put("pivot_candidate_count", 0)
                    .put("pivot_zone_formed", false).put("pivot_zone_invalidated", false)
                    .put("h1_add_test_count", 0).put("h4_bars_skipped_after_frozen_pivot_invalidation", 0)
                    .put("h4_windows_without_five_consecutive_bars", 0)
                    .put("pivot_centers_not_strictly_after_fill", 0).put("five_bar_windows_without_strict_pivot", 0)
                    .put("strict_pivots_unsafe_vs_active_stop", 0).put("h1_bars_before_pivot_zone", 0)
                    .put("h1_bars_skipped_after_pivot_zone_invalidation", 0);
            staging.putObject("first_failure_counts");
            staging.putObject("all_failed_gate_counts");
            staging.putObject("h1_additional_suppressions");
            staging.putObject("fill_rejection_counts");
            if (config.dailyRsiAdditionGate() || config.dailySma200AdditionGate()) {
                staging.putObject("daily_context_rejection_counts");
            }
            if (config.pivotRefreshRule() == PivotRefreshRule.REFRESH_AFTER_STOP_INVALIDATION) {
                staging.putArray("pivot_refresh_invalidations");
                staging.putArray("pivot_refresh_replacements");
                staging.put("pivot_refresh_pending", false).put("pivot_refresh_deferred_for_pending_intent", false)
                        .put("pivot_zone_currently_invalidated_by_stop", false)
                        .put("h4_bars_skipped_while_refresh_deferred", 0)
                        .put("replacement_pivots_not_later_than_invalidated_center", 0)
                        .put("replacement_pivots_confirmed_not_after_invalidation", 0);
            }
        }

        private static void incrementCount(ObjectNode counts, String key) {
            counts.put(key, counts.path(key).asInt() + 1);
        }

        private void cancelPending(AssetState state, Setup setup, Bar bar, RouteAccumulator result, String reason) {
            if (setup.pendingCancellationReason != null) return;
            setup.pendingCancellationReason = reason;
            List<SourceEvidence> source = List.of(evidence("PRE_FILL_STRUCTURAL_INVALIDATION", bar));
            boolean matched = false;
            for (ConfirmedIntent intent : pendingIntents.values().stream()
                    .filter(value -> value.setupId().equals(setup.id)).toList()) {
                matched = true;
                result.pendingCancellations.add(new PendingCancellation(intent.intentId(), intent.setupId(),
                        intent.asset(), intent.stage(), bar.availableAt(), reason, source));
            }
            if (!matched) state.setup = null;
        }

        private MacroAssessment macroAssessment(Instant decisionTime, Direction direction) {
            List<MacroClose> available = macroCloses.values().stream()
                    .filter(value -> !value.availableAt().isAfter(decisionTime) && value.closeTime().isBefore(decisionTime))
                    .sorted(Comparator.comparing(MacroClose::closeTime)).toList();
            if (available.size() < 6) return new MacroAssessment(MacroState.UNKNOWN, List.of());
            List<MacroClose> lastSix = available.subList(available.size() - 6, available.size());
            List<SourceEvidence> evidence = lastSix.stream().map(value -> new SourceEvidence("SP500_MACRO",
                    "SP500", value.seriesId(), value.closeTime(), value.availableAt())).toList();
            if (!consecutiveSessions(lastSix) || lastSix.stream().anyMatch(value -> !value.provenanceAvailable())) {
                return new MacroAssessment(MacroState.UNKNOWN, evidence);
            }
            Instant latestClose = lastSix.get(5).closeTime();
            long calendarAge = ChronoUnit.DAYS.between(latestClose.atZone(NEW_YORK).toLocalDate(),
                    decisionTime.atZone(NEW_YORK).toLocalDate());
            if (calendarAge > MAX_MACRO_AGE.toDays()) return new MacroAssessment(MacroState.UNKNOWN, evidence);
            BigDecimal firstClose = BigDecimal.valueOf(lastSix.get(0).close());
            BigDecimal finalClose = BigDecimal.valueOf(lastSix.get(5).close());
            BigDecimal upperNeutralBoundary = firstClose.multiply(BigDecimal.valueOf(1.0 + MACRO_THRESHOLD));
            BigDecimal lowerNeutralBoundary = firstClose.multiply(BigDecimal.valueOf(1.0 - MACRO_THRESHOLD));
            if (finalClose.compareTo(upperNeutralBoundary) > 0) return new MacroAssessment(
                    direction == Direction.LONG ? MacroState.SUPPORTIVE : MacroState.OPPOSING, evidence);
            if (finalClose.compareTo(lowerNeutralBoundary) < 0) return new MacroAssessment(
                    direction == Direction.SHORT ? MacroState.SUPPORTIVE : MacroState.OPPOSING, evidence);
            return new MacroAssessment(MacroState.NEUTRAL, evidence);
        }

        private DailyContextAssessment dailyContextAssessment(String asset, Direction direction, Instant decisionTime) {
            LocalDate expectedDay = decisionTime.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
            DailyPriceContext row = dailyPriceContexts.getOrDefault(asset, Collections.emptyNavigableMap()).get(expectedDay);
            if (row != null && row.availableAt().isAfter(decisionTime)) row = null;
            ContextState rsi = row == null ? ContextState.UNKNOWN : rsiContext(row.rsi14(), direction);
            ContextState sma = row == null ? ContextState.UNKNOWN : smaContext(row.dailyClose(), row.sma200(), direction);
            return new DailyContextAssessment(row, expectedDay, rsi, sma);
        }

        private static ContextState rsiContext(Double value, Direction direction) {
            if (value == null) return ContextState.UNKNOWN;
            if (Double.compare(value, 50.0) == 0) return ContextState.NEUTRAL;
            boolean supportive = direction == Direction.LONG ? value > 50.0 : value < 50.0;
            return supportive ? ContextState.SUPPORTIVE : ContextState.OPPOSING;
        }

        private static ContextState smaContext(double close, Double sma, Direction direction) {
            if (sma == null) return ContextState.UNKNOWN;
            int comparison = Double.compare(close, sma);
            if (comparison == 0) return ContextState.NEUTRAL;
            boolean supportive = direction == Direction.LONG ? comparison > 0 : comparison < 0;
            return supportive ? ContextState.SUPPORTIVE : ContextState.OPPOSING;
        }

        private List<String> dailyContextFailures(DailyContextAssessment context, int stage) {
            ArrayList<String> failures = new ArrayList<>(2);
            if (ruleConfig.dailyRsiAdditionGate()) {
                String reason = dailyGateFailure("DAILY_RSI", context.rsiState(), stage);
                if (reason != null) failures.add(reason);
            }
            if (ruleConfig.dailySma200AdditionGate()) {
                String reason = dailyGateFailure("DAILY_SMA200", context.smaState(), stage);
                if (reason != null) failures.add(reason);
            }
            return List.copyOf(failures);
        }

        private static String dailyGateFailure(String name, ContextState state, int stage) {
            if (stage == 2 && (state == ContextState.SUPPORTIVE || state == ContextState.NEUTRAL)) return null;
            if (stage == 3 && state == ContextState.SUPPORTIVE) return null;
            if (state == ContextState.UNKNOWN) return name + "_UNKNOWN_BLOCKS_STAGE_" + stage;
            if (state == ContextState.OPPOSING) return name + "_OPPOSING_BLOCKS_STAGE_" + stage;
            return name + "_NEUTRAL_BLOCKS_STAGE_" + stage;
        }

        private static List<SourceEvidence> dailyContextEvidence(DailyContextAssessment context) {
            DailyPriceContext row = context.row();
            if (row == null) return List.of();
            Instant closeTime = row.utcDay().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            ArrayList<SourceEvidence> evidence = new ArrayList<>(2);
            if (row.rsi14() != null) evidence.add(new SourceEvidence("DAILY_RSI14_CONTEXT", row.asset(),
                    row.seriesId(), closeTime, row.availableAt()));
            if (row.sma200() != null) evidence.add(new SourceEvidence("DAILY_SMA200_CONTEXT", row.asset(),
                    row.seriesId(), closeTime, row.availableAt()));
            return List.copyOf(evidence);
        }

        private ConfirmedIntent makeIntent(Setup setup, String pairId, Variant outputVariant,
                Branch branch, Branch routedBranch, Direction direction, int stage, Bar confirmation,
                Instant decisionTime, double zoneCenter, double zoneLower, double zoneUpper,
                Double pivotPrice, Instant pivotTime, Instant pivotConfirmedAt,
                double stop, Double target, MacroState macroState, boolean macroEligible,
                List<SourceEvidence> sourceEvidence, List<String> reasons) {
            double riskFraction = switch (stage) { case 1 -> 0.01; case 2 -> 0.015; case 3 -> 0.025; default -> throw new IllegalArgumentException("invalid stage"); };
            int leverage = stage == 3 ? 3 : 2;
            String intentId = pairId + "|" + outputVariant.name() + "|STAGE" + stage;
            return new ConfirmedIntent(intentId, setup.id, pairId, setup.asset, outputVariant,
                    branch, routedBranch, direction, setup.shockDirection, stage, decisionTime,
                    decisionTime.plusNanos(1), confirmation.startTime(), confirmation.close(),
                    zoneCenter, zoneLower, zoneUpper, pivotPrice, pivotTime, pivotConfirmedAt,
                    setup.preEventAtr, stop, target, MAX_CHASE_ATR * setup.preEventAtr,
                    riskFraction, leverage, MAX_HOLDING_DAYS, macroState, macroEligible,
                    setup.diagnosticOnly, reasons, sourceEvidence);
        }

        private RejectedOpportunity rejected(Setup setup, String pairId, Variant outputVariant,
                Branch branch, Direction direction, int stage, Instant decisionTime,
                String reason, MacroState macroState, List<SourceEvidence> evidence) {
            String id = pairId + "|" + outputVariant.name() + "|STAGE" + stage + "|REJECT|" + reason;
            return new RejectedOpportunity(id, setup.id, pairId, setup.asset, outputVariant,
                    setup.branch == null ? branch : setup.branch, branch, direction, stage,
                    decisionTime, reason, macroState, setup.diagnosticOnly, evidence);
        }

        private void createSetup(AssetState state, Geometry geometry, LocalDate dailyDay, Instant availableAt,
                boolean dailyGate) {
            String id = geometry.asset + "|" + (dailyDay == null ? "PRICE_OI" : dailyDay)
                    + "|" + geometry.shockDirection + "|" + geometry.eventEnd;
            if (state.lastCloseTime != null && !availableAt.isAfter(state.lastCloseTime)) return;
            if (state.busy() || state.setupIds.contains(id)) return;
            state.setupIds.add(id);
            state.setup = new Setup(id, geometry, availableAt, dailyGate,
                    variant == Variant.PRICE_OI_ONLY_EVENT_DIAGNOSTIC);
        }

        private void createPriceOiDiagnosticSetup(AssetState state, Bar latestBar) {
            Geometry geometry = state.priceOiGeometryEndingAt(latestBar.eventTime());
            if (geometry == null) return;
            if (state.lastCloseTime != null && !geometry.eventAvailableAt.isAfter(state.lastCloseTime)) return;
            createSetup(state, geometry, null, geometry.eventAvailableAt, false);
        }

        private void expireSetup(AssetState state, Instant now) {
            Setup setup = state.setup;
            if (setup == null || setup.filledStage > 0) return;
            Instant deadline = setup.armTime == null ? setup.availableAt.plus(BRANCH_WAIT)
                    : setup.armTime.plus(INITIAL_ZONE_WAIT);
            // Keep a confirmed request's setup alive until the accounting owner acknowledges
            // fill or no-fill. A later-arriving observation cannot invalidate an outstanding
            // intent while its next-minute execution decision is still unresolved.
            if (now.isAfter(deadline) && !setup.activePending && !pendingFor(setup.id)) {
                String reason = setup.branch == null ? "BRANCH_WAIT_EXPIRED_WITHOUT_TWO_CONSECUTIVE_CONFIRMATIONS"
                        : initialEntryExpiryReason(setup.id);
                markSetupTerminal(setup, reason, now);
                state.setup = null;
            }
        }

        private static Instant processingTime(Observation observation) {
            return observation instanceof DailyLiquidation daily ? daily.modeledAvailableAt() : observation.availableAt();
        }
    }

    private static final Comparator<Observation> OBSERVATION_ORDER = Comparator
            .comparing(Router::processingTime)
            .thenComparing(Observation::eventTime)
            .thenComparingInt(LiquidationStructureRouterV1::observationPriority)
            .thenComparing(Observation::asset)
            .thenComparing(Observation::seriesId);

    private static int observationPriority(Observation observation) {
        if (observation instanceof DailyLiquidation) return 0;
        if (observation instanceof MacroClose) return 1;
        if (observation instanceof OpenInterest) return 2;
        Bar bar = (Bar) observation;
        return bar.timeframe() == Timeframe.FOUR_HOUR ? 3 : 4;
    }

    private static final class RouteAccumulator {
        private final List<ConfirmedIntent> intents = new ArrayList<>();
        private final List<RejectedOpportunity> rejections = new ArrayList<>();
        private final List<StopUpdateIntent> stopUpdates = new ArrayList<>();
        private final List<PairedDecisionContext> pairedDecisionContexts = new ArrayList<>();
        private final List<PendingCancellation> pendingCancellations = new ArrayList<>();
        private final List<QualifiedDailyStressEvent> qualifiedDailyStressEvents = new ArrayList<>();
        private final List<StageOneAnchorContext> stageOneAnchorContexts = new ArrayList<>();
        private RouteResult result() { return new RouteResult(intents, rejections, stopUpdates,
                pairedDecisionContexts, pendingCancellations, qualifiedDailyStressEvents, stageOneAnchorContexts); }
    }

    private static final class AssetState {
        private final String asset;
        private final TreeMap<Instant, Bar> h4 = new TreeMap<>();
        private final TreeMap<Instant, Bar> h1 = new TreeMap<>();
        private final TreeMap<Instant, Pivot> h4ConfirmedPivotHighs = new TreeMap<>();
        private final TreeMap<Instant, Pivot> h4ConfirmedPivotLows = new TreeMap<>();
        private final TreeMap<Instant, OpenInterest> oi = new TreeMap<>();
        private final TreeMap<LocalDate, DailyLiquidation> daily = new TreeMap<>();
        private final Set<LocalDate> dailyEvaluated = new HashSet<>();
        private final Set<String> setupIds = new HashSet<>();
        private final Set<String> filledSetupIds = new HashSet<>();
        private String h4SeriesId;
        private String h1SeriesId;
        private String oiSeriesId;
        private String dailySeriesId;
        private Setup setup;
        private Instant lastCloseTime;
        private Instant lastSignalH4End;
        private Instant lastSignalH1End;

        private AssetState(String asset) { this.asset = asset; }
        private boolean busy() { return setup != null; }

        private void addDaily(DailyLiquidation value) {
            if (dailySeriesId == null) dailySeriesId = value.seriesId();
            if (!dailySeriesId.equals(value.seriesId())) throw new IllegalArgumentException("daily liquidation series identity changed for " + asset);
            if (daily.putIfAbsent(value.bucketStart(), value) != null) throw new IllegalArgumentException("duplicate daily liquidation bucket for " + asset);
        }

        private void addOi(OpenInterest value) {
            if (oiSeriesId == null) oiSeriesId = value.seriesId();
            if (!oiSeriesId.equals(value.seriesId())) throw new IllegalArgumentException("OI series identity changed for " + asset);
            if (oi.putIfAbsent(value.observedAt(), value) != null) throw new IllegalArgumentException("duplicate OI observation timestamp for " + asset);
        }

        private void addBar(Bar value) {
            TreeMap<Instant, Bar> target = value.timeframe() == Timeframe.FOUR_HOUR ? h4 : h1;
            String existing = value.timeframe() == Timeframe.FOUR_HOUR ? h4SeriesId : h1SeriesId;
            if (existing == null) {
                if (value.timeframe() == Timeframe.FOUR_HOUR) h4SeriesId = value.seriesId();
                else h1SeriesId = value.seriesId();
            } else if (!existing.equals(value.seriesId())) {
                throw new IllegalArgumentException("price series identity changed for " + asset + " " + value.timeframe());
            }
            if (target.putIfAbsent(value.eventTime(), value) != null) throw new IllegalArgumentException("duplicate price bar close timestamp for " + asset);
            if (value.timeframe() == Timeframe.FOUR_HOUR) {
                List<Bar> five = lastConsecutiveBars(Timeframe.FOUR_HOUR, value.eventTime(), 5);
                if (five.size() == 5) {
                    Bar center = five.get(2);
                    List<SourceEvidence> source = five.stream().map(item -> evidence("PIVOT_SOURCE", item)).toList();
                    if (pivotHigh(five)) h4ConfirmedPivotHighs.put(center.startTime(),
                            new Pivot(center.high(), center.startTime(), value.availableAt(), source));
                    if (pivotLow(five)) h4ConfirmedPivotLows.put(center.startTime(),
                            new Pivot(center.low(), center.startTime(), value.availableAt(), source));
                }
            }
        }

        /** Delayed bars cannot rewind a signal path after later event bars have been processed. */
        private boolean markFreshForSignals(Bar value) {
            if (value.timeframe() == Timeframe.FOUR_HOUR) {
                if (lastSignalH4End != null && !value.eventTime().isAfter(lastSignalH4End)) return false;
                lastSignalH4End = value.eventTime();
            } else {
                if (lastSignalH1End != null && !value.eventTime().isAfter(lastSignalH1End)) return false;
                lastSignalH1End = value.eventTime();
            }
            return true;
        }

        private DailyGate dailyGate(LocalDate currentDay, Instant availableAt) {
            DailyLiquidation current = daily.get(currentDay);
            if (current == null || current.modeledAvailableAt().isAfter(availableAt)) return null;
            List<Double> priorLong = new ArrayList<>(DAILY_LOOKBACK_DAYS);
            List<Double> priorShort = new ArrayList<>(DAILY_LOOKBACK_DAYS);
            for (int lag = DAILY_LOOKBACK_DAYS; lag >= 1; lag--) {
                DailyLiquidation prior = daily.get(currentDay.minusDays(lag));
                if (prior == null || prior.modeledAvailableAt().isAfter(availableAt)
                        || !prior.seriesId().equals(current.seriesId())) return null;
                priorLong.add(prior.longLiquidationsUsd());
                priorShort.add(prior.shortLiquidationsUsd());
            }
            double longP95 = nearestRank95(priorLong);
            double shortP95 = nearestRank95(priorShort);
            boolean downStress = current.longLiquidationsUsd() > 0.0 && current.longLiquidationsUsd() > longP95;
            boolean upStress = current.shortLiquidationsUsd() > 0.0 && current.shortLiquidationsUsd() > shortP95;
            return new DailyGate(downStress, upStress, longP95, shortP95, current);
        }

        private GeometryEvaluation geometryForStressDay(LocalDate day, Instant decisionAvailableAt, DailyGate gate) {
            Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant dayEnd = dayStart.plus(Duration.ofDays(1));
            List<Bar> stressBars = exactBars(h4, Timeframe.FOUR_HOUR, dayStart, 6);
            if (stressBars.size() != 6 || stressBars.stream().anyMatch(bar -> bar.availableAt().isAfter(decisionAvailableAt))) {
                return new GeometryEvaluation(null, false, false, List.of("STRESS_DAY_24H_H4_WINDOW_UNAVAILABLE"),
                        List.of("STRESS_DAY_24H_H4_WINDOW_UNAVAILABLE"), List.of());
            }
            Bar fastBar = stressBars.get(0);
            for (Bar bar : stressBars.subList(1, stressBars.size())) {
                // Strict comparison preserves the earliest bar when absolute moves tie.
                if (Math.abs(bar.close() - bar.open()) > Math.abs(fastBar.close() - fastBar.open())) fastBar = bar;
            }
            Double fastAtrValue = atrBefore(fastBar.startTime(), decisionAvailableAt);
            double fastAtr = fastAtrValue == null ? Double.NaN : fastAtrValue;
            Direction fastDirection = Direction.fromMove(fastBar.close() - fastBar.open());
            OiPair fastOi = oiPair(fastBar.startTime(), fastBar.eventTime());
            boolean fastStressMatches = fastDirection == Direction.SHORT ? gate.downStress : gate.upStress;
            boolean fastQualifies = fastDirection != null && fastAtr > 0.0 && fastOi != null
                    && Math.abs(fastBar.close() - fastBar.open()) >= FAST_ATR_MULTIPLE * fastAtr
                    && fastOi.declinedByAtLeast(OI_MINIMUM_DECLINE) && fastStressMatches;
            List<String> fastFailures = new ArrayList<>();
            if (fastDirection == null) fastFailures.add("FAST_SHOCK_DIRECTION_UNDEFINED");
            if (!(fastAtr > 0.0)) fastFailures.add("FAST_PRE_EVENT_ATR_UNAVAILABLE");
            if (fastOi == null) fastFailures.add("FAST_OI_ENDPOINT_PAIR_MISSING");
            else if (!fastOi.declinedByAtLeast(OI_MINIMUM_DECLINE)) fastFailures.add("FAST_OI_DECLINE_BELOW_GATE");
            if (fastAtr > 0.0 && Math.abs(fastBar.close() - fastBar.open()) < FAST_ATR_MULTIPLE * fastAtr) fastFailures.add("FAST_MOVE_BELOW_ATR_GATE");
            if (!fastStressMatches) fastFailures.add("FAST_DIRECTION_MISMATCHES_LIQUIDATION_SIDE");

            List<Bar> slowBars = exactBars(h4, Timeframe.FOUR_HOUR, dayEnd.minus(Duration.ofHours(72)), SLOW_WINDOW_BARS);
            boolean slowQualifies = false;
            Direction slowDirection = null;
            double slowAtr = Double.NaN;
            Bar slowEnd = null;
            List<String> slowFailures = new ArrayList<>();
            if (slowBars.size() == SLOW_WINDOW_BARS
                    && slowBars.stream().noneMatch(bar -> bar.availableAt().isAfter(decisionAvailableAt))) {
                Bar first = slowBars.get(0), last = slowBars.get(slowBars.size() - 1);
                Double slowAtrValue = atrBefore(first.startTime(), decisionAvailableAt);
                slowAtr = slowAtrValue == null ? Double.NaN : slowAtrValue;
                slowDirection = Direction.fromMove(last.close() - first.open());
                OiPair slowOi = oiPair(first.startTime(), last.eventTime());
                slowEnd = last;
                boolean slowStressMatches = slowDirection == Direction.SHORT ? gate.downStress : gate.upStress;
                slowQualifies = slowAtr > 0.0 && slowDirection != null
                        && Math.abs(last.close() - first.open()) >= SLOW_ATR_MULTIPLE * slowAtr
                        && slowOi != null && slowOi.declinedByAtLeast(OI_MINIMUM_DECLINE)
                        && slowStressMatches;
                if (slowDirection == null) slowFailures.add("SLOW_SHOCK_DIRECTION_UNDEFINED");
                if (!(slowAtr > 0.0)) slowFailures.add("SLOW_PRE_EVENT_ATR_UNAVAILABLE");
                if (slowOi == null) slowFailures.add("SLOW_OI_ENDPOINT_PAIR_MISSING");
                else if (!slowOi.declinedByAtLeast(OI_MINIMUM_DECLINE)) slowFailures.add("SLOW_OI_DECLINE_BELOW_GATE");
                if (slowAtr > 0.0 && Math.abs(last.close() - first.open()) < SLOW_ATR_MULTIPLE * slowAtr) slowFailures.add("SLOW_MOVE_BELOW_ATR_GATE");
                if (!slowStressMatches) slowFailures.add("SLOW_DIRECTION_MISMATCHES_LIQUIDATION_SIDE");
            } else {
                slowFailures.add("SLOW_72H_H4_WINDOW_UNAVAILABLE");
            }
            if (!fastQualifies && !slowQualifies) return new GeometryEvaluation(null, false, false, fastFailures, slowFailures, List.of());
            boolean useFast = fastQualifies;
            List<Bar> geometryBars = useFast ? List.of(fastBar) : slowBars;
            Instant eventStart = useFast ? fastBar.startTime() : slowBars.get(0).startTime();
            Instant eventEnd = useFast ? fastBar.eventTime() : slowEnd.eventTime();
            double preAtr = useFast ? fastAtr : slowAtr;
            double eventOpen = useFast ? fastBar.open() : slowBars.get(0).open();
            double eventClose = useFast ? fastBar.close() : slowEnd.close();
            Direction shockDirection = useFast ? fastDirection : slowDirection;
            List<String> selectedFailures = selectedGeometryFailures(eventStart, eventEnd, eventClose,
                    preAtr, shockDirection, decisionAvailableAt);
            Geometry geometry = freezeGeometry(eventStart, eventEnd, eventOpen, eventClose, preAtr,
                    fastQualifies, slowQualifies, shockDirection, decisionAvailableAt, geometryBars, dailyEvidence(day));
            return new GeometryEvaluation(geometry, fastQualifies, slowQualifies, fastFailures, slowFailures,
                    geometry == null ? selectedFailures : List.of());
        }

        private List<String> selectedGeometryFailures(Instant eventStart, Instant eventEnd, double eventClose,
                double preAtr, Direction shock, Instant availableAt) {
            List<String> failures = new ArrayList<>();
            if (shock == null) failures.add("SELECTED_SHOCK_DIRECTION_UNDEFINED");
            if (!finitePositive(preAtr)) failures.add("SELECTED_PRE_EVENT_ATR_UNAVAILABLE");
            List<Bar> prior = exactBars(h4, Timeframe.FOUR_HOUR, eventStart.minus(Duration.ofHours(24)), FAST_WINDOW_BARS);
            if (prior.size() != FAST_WINDOW_BARS || prior.stream().anyMatch(bar -> bar.availableAt().isAfter(availableAt))) {
                failures.add("PRE_EVENT_24H_RANGE_UNAVAILABLE");
            } else if (shock != null) {
                double priorHigh = maxHigh(prior), priorLow = minLow(prior);
                double boundary = shock == Direction.LONG ? priorHigh : priorLow;
                if (!beyondBrokenBoundary(eventClose, shock, boundary)) failures.add("SELECTED_SHOCK_FAILED_PRIOR_24H_RANGE_BREAK");
            }
            if (failures.isEmpty()) failures.add("SELECTED_GEOMETRY_REJECTED_UNCLASSIFIED");
            return List.copyOf(failures);
        }

        private Geometry priceOiGeometryEndingAt(Instant eventEnd) {
            Bar latest = h4.get(eventEnd);
            if (latest == null) return null;
            Double atr = atrBefore(latest.startTime(), latest.availableAt());
            OiPair fastOi = oiPair(latest.startTime(), latest.eventTime());
            boolean fast = atr != null && Math.abs(latest.close() - latest.open()) >= FAST_ATR_MULTIPLE * atr
                    && fastOi != null && fastOi.declinedByAtLeast(OI_MINIMUM_DECLINE);
            List<Bar> slowBars = exactBars(h4, Timeframe.FOUR_HOUR, eventEnd.minus(Duration.ofHours(72)), SLOW_WINDOW_BARS);
            double slowAtr = Double.NaN;
            boolean slow = false;
            Direction slowDirection = null;
            if (slowBars.size() == SLOW_WINDOW_BARS) {
                Bar first = slowBars.get(0);
                Double value = atrBefore(first.startTime(), latest.availableAt());
                if (value != null) {
                    slowAtr = value;
                    OiPair pair = oiPair(first.startTime(), latest.eventTime());
                    slowDirection = Direction.fromMove(latest.close() - first.open());
                    slow = pair != null && pair.declinedByAtLeast(OI_MINIMUM_DECLINE)
                            && Math.abs(latest.close() - first.open()) >= SLOW_ATR_MULTIPLE * slowAtr
                            && slowDirection != null;
                }
            }
            if (!fast && !slow) return null;
            Direction fastDirection = fast ? Direction.fromMove(latest.close() - latest.open()) : null;
            boolean useFast = fast;
            Instant start = useFast ? latest.startTime() : slowBars.get(0).startTime();
            Instant end = useFast ? latest.eventTime() : latest.eventTime();
            double open = useFast ? latest.open() : slowBars.get(0).open();
            double close = latest.close();
            double preAtr = useFast ? atr : slowAtr;
            Direction shock = useFast ? fastDirection : slowDirection;
            List<Bar> sourceBars = useFast ? List.of(latest) : slowBars;
            return freezeGeometry(start, end, open, close, preAtr, fast, slow, shock,
                    latest.availableAt(), sourceBars, List.of());
        }

        private Geometry freezeGeometry(Instant eventStart, Instant eventEnd, double eventOpen,
                double eventClose, double preAtr, boolean fast, boolean slow, Direction shock,
                Instant availableAt, List<Bar> eventBars, List<SourceEvidence> initialEvidence) {
            if (shock == null || !finitePositive(preAtr)) return null;
            List<Bar> prior = exactBars(h4, Timeframe.FOUR_HOUR, eventStart.minus(Duration.ofHours(24)), FAST_WINDOW_BARS);
            if (prior.size() != FAST_WINDOW_BARS || prior.stream().anyMatch(bar -> bar.availableAt().isAfter(availableAt))) return null;
            double priorHigh = maxHigh(prior), priorLow = minLow(prior);
            if (!beyondBrokenBoundary(eventClose, shock, shock == Direction.LONG ? priorHigh : priorLow)) return null;
            double boundary = shock == Direction.LONG ? priorHigh : priorLow;
            double recoveryTarget = (priorHigh + priorLow) / 2.0;
            double zoneLower = boundary - INITIAL_ZONE_HALF_WIDTH_ATR * preAtr;
            double zoneUpper = boundary + INITIAL_ZONE_HALF_WIDTH_ATR * preAtr;
            List<SourceEvidence> evidence = new ArrayList<>(initialEvidence);
            for (Bar bar : prior) evidence.add(evidence("PRE_EVENT_RANGE", bar));
            for (Bar bar : eventBars) evidence.add(evidence("PRICE_EVENT", bar));
            OiPair oiPair = oiPair(eventStart, eventEnd);
            if (oiPair != null) {
                evidence.add(evidence("OI_START", oiPair.start));
                evidence.add(evidence("OI_END", oiPair.end));
            }
            // Both qualifying flags are preserved; fast geometry controls levels whenever present.
            return new Geometry(asset, eventStart, eventEnd, shock, preAtr, priorHigh, priorLow,
                    boundary, recoveryTarget, zoneLower, zoneUpper, fast, slow,
                    fast ? GeometryKind.FAST : GeometryKind.SLOW,
                    availableAt, List.copyOf(evidence));
        }

        private List<SourceEvidence> dailyEvidence(LocalDate day) {
            DailyLiquidation current = daily.get(day);
            if (current == null) return List.of();
            List<SourceEvidence> result = new ArrayList<>();
            result.add(new SourceEvidence("DAILY_STRESS_CURRENT", asset, current.seriesId(),
                    current.eventTime(), current.availableAt()));
            for (int lag = DAILY_LOOKBACK_DAYS; lag >= 1; lag--) {
                DailyLiquidation prior = daily.get(day.minusDays(lag));
                if (prior != null) result.add(new SourceEvidence("DAILY_STRESS_LOOKBACK", asset,
                        prior.seriesId(), prior.eventTime(), prior.availableAt()));
            }
            return List.copyOf(result);
        }

        private OiPair oiPair(Instant startBoundary, Instant endBoundary) {
            OpenInterest start = oiAtEndpoint(startBoundary);
            OpenInterest end = oiAtEndpoint(endBoundary);
            return start == null || end == null ? null : new OiPair(start, end);
        }

        private OpenInterest oiAtEndpoint(Instant endpoint) {
            Instant earliest = endpoint.minus(OI_MAXIMUM_STALENESS);
            Instant latest = endpoint.minus(OI_MINIMUM_LAG);
            for (OpenInterest value : oi.subMap(earliest, true, latest, true).descendingMap().values()) {
                if (value.availableAt().isBefore(endpoint)) return value;
            }
            return null;
        }

        private Double atrBefore(Instant exclusiveEnd, Instant decisionAvailableAt) {
            // Bars are keyed by close-time; include the bar ending exactly at the event start,
            // which is the last fully completed bar strictly before the event interval.
            List<Bar> bars = h4.headMap(exclusiveEnd, true).values().stream()
                    .filter(bar -> !bar.availableAt().isAfter(decisionAvailableAt)).toList();
            if (bars.isEmpty() || !bars.get(bars.size() - 1).eventTime().equals(exclusiveEnd)) return null;
            int first = bars.size() - 1;
            while (first > 0 && bars.get(first).startTime().equals(bars.get(first - 1).eventTime())) first--;
            List<Bar> window = bars.subList(first, bars.size());
            if (window.size() < H4_ATR_WARMUP_BARS) return null;
            // Seed once from the first 14 bars in the contiguous completed run, then carry the
            // Wilder recursion through every known bar rather than reseeding a rolling 140-bar slice.
            double previousClose = window.get(0).close();
            double seed = 0.0;
            for (int i = 0; i < H4_ATR_PERIODS; i++) {
                seed += trueRange(window.get(i), previousClose);
                previousClose = window.get(i).close();
            }
            double atr = seed / H4_ATR_PERIODS;
            for (int i = H4_ATR_PERIODS; i < window.size(); i++) {
                Bar bar = window.get(i);
                atr = ((H4_ATR_PERIODS - 1.0) * atr + trueRange(bar, previousClose)) / H4_ATR_PERIODS;
                previousClose = bar.close();
            }
            return finitePositive(atr) ? atr : null;
        }

        private Bar barEndingAt(Timeframe timeframe, Instant closeTime) {
            return (timeframe == Timeframe.FOUR_HOUR ? h4 : h1).get(closeTime);
        }

        private List<Bar> lastConsecutiveBars(Timeframe timeframe, Instant endingAt, int count) {
            TreeMap<Instant, Bar> source = timeframe == Timeframe.FOUR_HOUR ? h4 : h1;
            List<Bar> reversed = new ArrayList<>(count);
            Instant cursor = endingAt;
            for (int i = 0; i < count; i++) {
                Bar bar = source.get(cursor);
                if (bar == null) return List.of();
                reversed.add(bar);
                cursor = cursor.minus(timeframe.duration());
            }
            List<Bar> result = new ArrayList<>(count);
            for (int i = reversed.size() - 1; i >= 0; i--) result.add(reversed.get(i));
            return List.copyOf(result);
        }

        private List<Bar> lastConsecutiveAvailableBars(Timeframe timeframe, Instant availableAt, int count) {
            TreeMap<Instant, Bar> source = timeframe == Timeframe.FOUR_HOUR ? h4 : h1;
            Map.Entry<Instant, Bar> ending = source.floorEntry(availableAt);
            while (ending != null && ending.getValue().availableAt().isAfter(availableAt)) {
                ending = source.lowerEntry(ending.getKey());
            }
            if (ending == null) return List.of();
            List<Bar> bars = lastConsecutiveBars(timeframe, ending.getKey(), count);
            if (bars.size() != count || bars.stream().anyMatch(bar -> bar.availableAt().isAfter(availableAt))) return List.of();
            return bars;
        }

        private List<Bar> lastExpectedCompletedBars(Timeframe timeframe, Instant availableAt, int count) {
            long seconds = timeframe.duration().toSeconds();
            long completedEndEpoch = Math.floorDiv(availableAt.getEpochSecond(), seconds) * seconds;
            Instant expectedEnd = Instant.ofEpochSecond(completedEndEpoch);
            List<Bar> bars = lastConsecutiveBars(timeframe, expectedEnd, count);
            if (bars.size() != count || bars.stream().anyMatch(bar -> bar.availableAt().isAfter(availableAt))) return List.of();
            return bars;
        }

        private Pivot latestConfirmedPivot(TreeMap<Instant, Pivot> pivots, Instant minimumCenter,
                Instant availableAt) {
            for (Pivot pivot : pivots.descendingMap().values()) {
                if (pivot.centerTime().isBefore(minimumCenter)) break;
                if (!pivot.confirmedAt().isAfter(availableAt)) return pivot;
            }
            return null;
        }

        private static List<Bar> exactBars(TreeMap<Instant, Bar> source, Timeframe timeframe,
                Instant firstStart, int count) {
            List<Bar> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Instant start = firstStart.plus(timeframe.duration().multipliedBy(i));
                Bar bar = source.get(start.plus(timeframe.duration()));
                if (bar == null || !bar.startTime().equals(start)) return List.of();
                result.add(bar);
            }
            return List.copyOf(result);
        }
    }

    private record DailyGate(boolean downStress, boolean upStress, double longP95,
            double shortP95, DailyLiquidation current) {}
    private record GeometryEvaluation(Geometry geometry, boolean fastQualifies, boolean slowQualifies,
            List<String> fastFailures, List<String> slowFailures, List<String> selectedFailures) {
        private GeometryEvaluation {
            fastFailures = List.copyOf(fastFailures);
            slowFailures = List.copyOf(slowFailures);
            selectedFailures = List.copyOf(selectedFailures);
        }
    }
    private record MacroAssessment(MacroState state, List<SourceEvidence> evidence) {
        private MacroAssessment { evidence = List.copyOf(evidence); }
    }
    private enum ContextState { SUPPORTIVE, NEUTRAL, OPPOSING, UNKNOWN }
    private record DailyContextAssessment(DailyPriceContext row, LocalDate expectedDay,
            ContextState rsiState, ContextState smaState) {
        private ObjectNode toJson() {
            ObjectNode result = JsonHashes.mapper().createObjectNode().put("expected_utc_day", expectedDay.toString());
            if (row == null) {
                result.put("status", "UNKNOWN").put("reason", "NO_COMPLETE_PRIOR_UTC_DAY_CONTEXT");
                result.putNull("available_at").putNull("daily_close").putNull("rsi14").putNull("sma200");
            } else {
                result.put("status", "AVAILABLE").put("utc_day", row.utcDay().toString())
                        .put("available_at", row.availableAt().toString()).put("daily_close", row.dailyClose())
                        .put("series_id", row.seriesId());
                if (row.rsi14() == null) result.putNull("rsi14"); else result.put("rsi14", row.rsi14());
                if (row.sma200() == null) result.putNull("sma200"); else result.put("sma200", row.sma200());
            }
            result.put("rsi_state", rsiState.name()).put("sma200_state", smaState.name());
            return result;
        }
    }

    private enum GeometryKind { FAST, SLOW }
    private record OiPair(OpenInterest start, OpenInterest end) {
        private boolean declinedByAtLeast(double fraction) {
            return start.baseQuantity() > 0.0 && end.baseQuantity() <= start.baseQuantity() * (1.0 - fraction);
        }
    }

    private static void addTextArray(ObjectNode target, String field, List<String> values) {
        ArrayNode rows = target.putArray(field);
        values.forEach(rows::add);
    }
    private record Geometry(String asset, Instant eventStart, Instant eventEnd, Direction shockDirection,
            double preEventAtr, double priorHigh, double priorLow, double boundary, double recoveryTarget,
            double zoneLower, double zoneUpper, boolean fastQualifies, boolean slowQualifies,
            GeometryKind selectedKind, Instant eventAvailableAt, List<SourceEvidence> evidence) {}
    private record Pivot(double price, Instant centerTime, Instant confirmedAt, List<SourceEvidence> evidence) {}
    private record FrozenZone(double center, double lower, double upper, double price,
            Instant pivotTime, Instant confirmedAt, Instant eligibleAt, List<SourceEvidence> evidence) {}

    private static final class Setup {
        private final String id;
        private final String asset;
        private final Direction shockDirection;
        private final Instant eventStart;
        private final Instant eventEnd;
        private final double preEventAtr;
        private final double priorHigh;
        private final double priorLow;
        private final double boundary;
        private final double recoveryTarget;
        private final double zoneLower;
        private final double zoneUpper;
        private double entryLevel;
        private double entryZoneLower;
        private double entryZoneUpper;
        private Pivot selectedEntryPivot;
        private List<SourceEvidence> entryStructureEvidence = List.of();
        private final boolean fastGeometry;
        private final boolean slowGeometry;
        private final GeometryKind selectedGeometry;
        private final Instant availableAt;
        private final List<SourceEvidence> geometryEvidence;
        private final boolean dailyGate;
        private final boolean diagnosticOnly;
        private Branch branchCandidate;
        private int branchCandidateCount;
        private Instant lastBranchBarEnd;
        private Pivot postShockBreakPivot;
        private Direction postShockBreakDirection;
        private Bar firstPostShockBreakBar;
        private Branch branch;
        private Direction direction;
        private Instant armTime;
        private int filledStage;
        private Instant lastFillTime;
        private double lastFillPrice;
        private double activeStop;
        private boolean favorableH4Seen;
        private Pivot pivotCandidate;
        private FrozenZone pivotZone;
        private boolean pivotZoneInvalid;
        private Instant pivotInvalidatedAt;
        private Instant pivotInvalidatedCenterTime;
        private int pivotReplacementCount;
        private boolean recoveryTargetReached;
        private boolean activePending;
        private String pendingCancellationReason;
        private Instant lastAddDecisionHour;
        private Instant favorableH4AvailableAt;
        private SourceEvidence favorableH4Evidence;

        private Setup(String id, Geometry geometry, Instant availableAt, boolean dailyGate, boolean diagnosticOnly) {
            this.id = id; this.asset = geometry.asset; this.shockDirection = geometry.shockDirection;
            this.eventStart = geometry.eventStart; this.eventEnd = geometry.eventEnd;
            this.preEventAtr = geometry.preEventAtr; this.priorHigh = geometry.priorHigh; this.priorLow = geometry.priorLow;
            this.boundary = geometry.boundary; this.recoveryTarget = geometry.recoveryTarget;
            this.zoneLower = geometry.zoneLower; this.zoneUpper = geometry.zoneUpper;
            this.entryLevel = geometry.boundary; this.entryZoneLower = geometry.zoneLower; this.entryZoneUpper = geometry.zoneUpper;
            this.fastGeometry = geometry.fastQualifies; this.slowGeometry = geometry.slowQualifies;
            this.selectedGeometry = geometry.selectedKind; this.availableAt = availableAt;
            this.geometryEvidence = geometry.evidence; this.dailyGate = dailyGate; this.diagnosticOnly = diagnosticOnly;
        }
    }

    private static List<String> entryFailureCodes(Bar bar, Bar previous, Direction direction,
            double level, double zoneLower, double zoneUpper) {
        List<String> failures = new ArrayList<>();
        if (previous == null) failures.add("NO_PREVIOUS_COMPLETED_H1_BAR");
        boolean touchesZone = bar.low() <= zoneUpper && bar.high() >= zoneLower;
        if (!touchesZone) failures.add("NO_H1_TOUCH_OF_ENTRY_ZONE");
        boolean closesTradeSide = direction == Direction.LONG ? bar.close() > level : bar.close() < level;
        if (!closesTradeSide) failures.add("H1_CLOSE_NOT_ON_TRADE_SIDE_OF_BOUNDARY");
        if (previous != null) {
            boolean breaksPrevious = direction == Direction.LONG ? bar.close() > previous.high() : bar.close() < previous.low();
            if (!breaksPrevious) failures.add("H1_CLOSE_DID_NOT_BREAK_PRIOR_H1_EXTREME");
        }
        return List.copyOf(failures);
    }

    private static boolean entryBarQualifies(Bar bar, Bar previous, Direction direction,
            double level, double zoneLower, double zoneUpper) {
        boolean touchesZone = bar.low() <= zoneUpper && bar.high() >= zoneLower;
        boolean closesTradeSide = direction == Direction.LONG ? bar.close() > level : bar.close() < level;
        boolean breaksPrevious = direction == Direction.LONG ? bar.close() > previous.high() : bar.close() < previous.low();
        return touchesZone && closesTradeSide && breaksPrevious;
    }

    private static double structuralStop(List<Bar> bars, Direction direction, double preEventAtr) {
        return direction == Direction.LONG
                ? minLow(bars) - STOP_BUFFER_ATR * preEventAtr
                : maxHigh(bars) + STOP_BUFFER_ATR * preEventAtr;
    }

    private static boolean stopAdverse(Direction direction, double stop, double referencePrice) {
        return direction == Direction.LONG ? stop < referencePrice : stop > referencePrice;
    }

    private static boolean targetAhead(Direction direction, double target, double referencePrice) {
        return direction == Direction.LONG ? target > referencePrice : target < referencePrice;
    }

    private static boolean targetReachedByBar(double target, Direction direction, Bar bar) {
        return direction == Direction.LONG ? bar.high() >= target : bar.low() <= target;
    }

    private static boolean pivotLow(List<Bar> bars) {
        double value = bars.get(2).low();
        return value < bars.get(0).low() && value < bars.get(1).low()
                && value < bars.get(3).low() && value < bars.get(4).low();
    }

    private static boolean pivotHigh(List<Bar> bars) {
        double value = bars.get(2).high();
        return value > bars.get(0).high() && value > bars.get(1).high()
                && value > bars.get(3).high() && value > bars.get(4).high();
    }

    private static boolean pivotSafe(Direction direction, double pivot, double stop) {
        return direction == Direction.LONG ? pivot > stop : pivot < stop;
    }

    private static boolean tightens(Direction direction, double current, double candidate) {
        return direction == Direction.LONG ? candidate > current : candidate < current;
    }

    private static boolean tightensOrEquals(Direction direction, double current, double candidate) {
        return direction == Direction.LONG ? candidate >= current : candidate <= current;
    }

    private static boolean insideRange(double close, double low, double high) {
        return close > low && close < high;
    }

    private static boolean beyondBrokenBoundary(double close, Direction shockDirection, double boundary) {
        return shockDirection == Direction.LONG ? close > boundary : close < boundary;
    }

    private static double maxHigh(List<Bar> bars) { return bars.stream().mapToDouble(Bar::high).max().orElseThrow(); }
    private static double minLow(List<Bar> bars) { return bars.stream().mapToDouble(Bar::low).min().orElseThrow(); }

    private static double trueRange(Bar bar, double previousClose) {
        return Math.max(bar.high() - bar.low(), Math.max(Math.abs(bar.high() - previousClose), Math.abs(bar.low() - previousClose)));
    }

    private static double nearestRank95(List<Double> values) {
        if (values.size() != DAILY_LOOKBACK_DAYS) throw new IllegalArgumentException("daily p95 requires exactly 90 prior values");
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(DAILY_P95_NEAREST_RANK_INDEX);
    }

    private static boolean consecutiveSessions(List<MacroClose> closes) {
        for (int i = 1; i < closes.size(); i++) {
            LocalDate previous = closes.get(i - 1).closeTime().atZone(NEW_YORK).toLocalDate();
            LocalDate current = closes.get(i).closeTime().atZone(NEW_YORK).toLocalDate();
            if (!nextTradingSession(previous).equals(current)) return false;
        }
        return true;
    }

    /** Shared physical-feature validator for the bounded verified NYSE macro calendar. */
    static void validateMacroClose(Instant closeTime, Instant availableAt) {
        ZonedDateTime local = closeTime.atZone(NEW_YORK);
        LocalDate sessionDate = local.toLocalDate();
        if (!isTradingSession(sessionDate) || !local.toLocalTime().equals(sessionClose(sessionDate))) {
            throw new IllegalArgumentException("macro input must be a completed S&P 500 session close, never a partial/future close");
        }
        Instant nextSessionClose = modeledNextSessionClose(closeTime);
        if (availableAt.isBefore(nextSessionClose)) {
            throw new IllegalArgumentException("macro close availability must be at or after the next completed US equity session");
        }
    }

    /** Retrospective availability proxy shared by input adapters, using the frozen NYSE calendar. */
    static Instant modeledNextSessionClose(Instant closeTime) {
        ZonedDateTime local = closeTime.atZone(NEW_YORK);
        LocalDate sessionDate = local.toLocalDate();
        if (!isTradingSession(sessionDate) || !local.toLocalTime().equals(sessionClose(sessionDate))) {
            throw new IllegalArgumentException("macro input must be a completed S&P 500 session close");
        }
        LocalDate nextSession = nextTradingSession(sessionDate);
        return nextSession.atTime(sessionClose(nextSession)).atZone(NEW_YORK).toInstant();
    }

    static LocalDate nextTradingSession(LocalDate date) {
        LocalDate candidate = date.plusDays(1);
        while (!isTradingSession(candidate)) candidate = candidate.plusDays(1);
        return candidate;
    }

    private static boolean isTradingSession(LocalDate date) {
        requireCalendarYear(date.getYear());
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
                && !NYSE_HOLIDAYS.get(date.getYear()).contains(date);
    }

    static LocalTime sessionClose(LocalDate date) {
        if (isEarlyClose(date)) return NYSE_EARLY_CLOSE;
        return NYSE_REGULAR_CLOSE;
    }

    private static boolean isEarlyClose(LocalDate date) {
        requireCalendarYear(date.getYear());
        return NYSE_EARLY_CLOSES.get(date.getYear()).contains(date);
    }

    private static void requireCalendarYear(int year) {
        if (!NYSE_HOLIDAYS.containsKey(year)) {
            throw new IllegalArgumentException("NYSE macro calendar is only verified for 2022-2026");
        }
    }

    private static Set<LocalDate> dates(String... isoDates) {
        Set<LocalDate> dates = new HashSet<>();
        for (String isoDate : isoDates) dates.add(LocalDate.parse(isoDate));
        return Set.copyOf(dates);
    }

    private static Instant later(Instant left, Instant right) {
        Objects.requireNonNull(left); Objects.requireNonNull(right);
        return left.isAfter(right) ? left : right;
    }

    private static List<SourceEvidence> combined(List<SourceEvidence> first, SourceEvidence second) {
        List<SourceEvidence> result = new ArrayList<>(first);
        result.add(Objects.requireNonNull(second));
        return List.copyOf(result);
    }

    private static SourceEvidence evidence(String role, Bar bar) {
        return new SourceEvidence(role, bar.asset(), bar.seriesId(), bar.eventTime(), bar.availableAt());
    }

    private static SourceEvidence evidence(String role, OpenInterest oi) {
        return new SourceEvidence(role, oi.asset(), oi.seriesId(), oi.eventTime(), oi.availableAt());
    }

    private static boolean finitePositive(double value) { return Double.isFinite(value) && value > 0.0; }

    private static String canonicalAsset(String asset) {
        if (asset == null) throw new IllegalArgumentException("asset is required");
        String canonical = asset.toUpperCase(Locale.ROOT);
        if (!ASSETS.contains(canonical)) throw new IllegalArgumentException("unsupported v002 asset: " + asset);
        return canonical;
    }

    private static String requiredSeriesId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("source series ID is required");
        return value;
    }
}
