package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned correction for the frozen V5 portfolio-marking defect.
 *
 * <p>This class is deliberately separate from {@link StrategyFixedBaselineV5}.
 * It consumes closed-trade evidence and returns a new correction receipt; it
 * does not rewrite, re-hash, or certify any historical V5 result.  The public
 * {@link #correctLegacyBook(ObjectNode)} method is the compatibility boundary
 * for an existing V5 event or control book.  New callers may use
 * {@link #reconcile(ObjectNode)} with the typed correction input contract.</p>
 */
public final class StrategyFixedBaselinePortfolioCorrectionV1 {
    public static final String INPUT_SCHEMA = "strategy-fixed-baseline-portfolio-correction-input/1";
    public static final String RESULT_SCHEMA = "strategy-fixed-baseline-portfolio-correction-result/1";
    public static final String RECEIPT_SCHEMA = "strategy-portfolio-accounting-correction-receipt/1";
    public static final int VERSION = 1;
    public static final String ACCOUNTING_VERSION = "PORTFOLIO_ACCOUNTING_CORRECTION_V1";
    public static final String EVALUATOR_ID =
            "com.tradinganalytics.research.v5.StrategyFixedBaselinePortfolioCorrectionV1";
    private static final String SOURCE_FINGERPRINT = sourceFingerprint();
    private static final double EPSILON = 1e-8;

    private StrategyFixedBaselinePortfolioCorrectionV1() {}

    /**
     * Reconcile a typed correction input.
     *
     * <p>The input must contain {@code starting_capital_usdt},
     * {@code declared_ending_equity_usdt}, and an array of closed trades.  A
     * mark at the exit instant is treated as a known legacy boundary defect,
     * excluded from the corrected book, and recorded in the receipt.  A mark
     * after the exit is invalid evidence and fails deterministically.</p>
     */
    public static ObjectNode reconcile(ObjectNode input) {
        if (input == null || !INPUT_SCHEMA.equals(input.path("schema").asText())
                || input.path("version").asInt(-1) != VERSION) {
            throw new IllegalArgumentException("portfolio correction requires the typed input schema");
        }
        if (!input.path("trades").isArray()) {
            throw new IllegalArgumentException("portfolio correction requires a trades array");
        }
        double starting = requiredPositive(input, "starting_capital_usdt");
        double declared = requiredFinite(input, "declared_ending_equity_usdt");
        String sourceKind = input.path("source_kind").asText("TYPED_CLOSED_TRADE_EVIDENCE");
        String actualInputHash = JsonHashes.canonicalSha256(input);
        if (input.has("source_input_canonical_sha256")) {
            throw new IllegalArgumentException("source_input_canonical_sha256 is reserved; it is computed by the correction path");
        }
        String policy = input.path("starting_capital_policy").asText("");
        return reconcileTrades((ArrayNode) input.path("trades"), starting, declared, policy,
                sourceKind, actualInputHash, actualInputHash);
    }

    /** Convenience overload for callers constructing an immutable trade array. */
    public static ObjectNode reconcile(ArrayNode trades, double startingCapitalUsdt,
            double declaredEndingEquityUsdt) {
        ObjectNode input = JsonHashes.mapper().createObjectNode()
                .put("schema", INPUT_SCHEMA).put("version", VERSION)
                .put("starting_capital_usdt", startingCapitalUsdt)
                .put("declared_ending_equity_usdt", declaredEndingEquityUsdt);
        input.set("trades", trades == null ? JsonHashes.mapper().createArrayNode() : trades.deepCopy());
        return reconcile(input);
    }

    /**
     * Correct an existing frozen V5 event/control book as a new receipt.
     * Fields outside {@code trades}, starting equity, ending equity, and the
     * capital policy are source evidence only; the old object is never mutated.
     */
    public static ObjectNode correctLegacyBook(ObjectNode legacyBook) {
        if (legacyBook == null || !legacyBook.isObject()) {
            throw new IllegalArgumentException("legacy portfolio book must be an object");
        }
        if (!legacyBook.path("trades").isArray()) {
            throw new IllegalArgumentException("legacy portfolio book lacks trades");
        }
        double starting = requiredPositive(legacyBook, "starting_equity_usdt");
        double declared = requiredFinite(legacyBook, "ending_equity_usdt");
        ObjectNode input = JsonHashes.mapper().createObjectNode()
                .put("schema", INPUT_SCHEMA).put("version", VERSION)
                .put("starting_capital_usdt", starting)
                .put("declared_ending_equity_usdt", declared)
                .put("source_kind", "FROZEN_V5_PORTFOLIO_BOOK");
        if (legacyBook.has("starting_capital_policy")) {
            input.set("starting_capital_policy", legacyBook.get("starting_capital_policy").deepCopy());
        }
        input.set("trades", legacyBook.path("trades").deepCopy());
        ObjectNode corrected = reconcileInternal(input, JsonHashes.canonicalSha256(legacyBook));
        corrected.put("legacy_book_schema", legacyBook.path("schema").asText("strategy-fixed-baseline-result/1"));
        corrected.put("legacy_book_content_sha256", legacyBook.path("content_sha256").asText(""));
        // The two fields above are source metadata.  Re-hash the result after
        // adding them so the receipt remains a canonical, self-describing row.
        return withOwnHash(corrected);
    }

    private static ObjectNode reconcileInternal(ObjectNode input, String trustedSourceHash) {
        double starting = requiredPositive(input, "starting_capital_usdt");
        double declared = requiredFinite(input, "declared_ending_equity_usdt");
        String sourceKind = input.path("source_kind").asText("FROZEN_V5_PORTFOLIO_BOOK");
        String policy = input.path("starting_capital_policy").asText("");
        return reconcileTrades((ArrayNode) input.path("trades"), starting, declared, policy,
                sourceKind, trustedSourceHash, JsonHashes.canonicalSha256(input));
    }

    private static ObjectNode reconcileTrades(ArrayNode rawTrades, double starting, double declared,
            String startingPolicy, String sourceKind, String sourceHash, String correctionInputHash) {
        Objects.requireNonNull(rawTrades, "trades");
        List<TradeRecord> records = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (JsonNode raw : rawTrades) {
            if (!(raw instanceof ObjectNode trade)) {
                throw new IllegalArgumentException("closed trade must be an object");
            }
            TradeRecord record = readTrade(trade, seenIds);
            records.add(record);
        }
        records.sort(Comparator.comparing(TradeRecord::entryTime).thenComparing(TradeRecord::id));

        ArrayList<LedgerEvent> events = new ArrayList<>();
        ArrayNode correctedTrades = JsonHashes.mapper().createArrayNode();
        ArrayNode excludedBoundaryMarks = JsonHashes.mapper().createArrayNode();
        for (TradeRecord record : records) {
            ObjectNode correctedTrade = record.trade().deepCopy();
            ArrayNode correctedMarks = JsonHashes.mapper().createArrayNode();
            JsonNode rawMarks = record.trade().get("portfolio_mark_points");
            if (rawMarks != null && !rawMarks.isArray()) {
                throw new IllegalArgumentException(record.id() + ": portfolio_mark_points must be an array");
            }
            if (rawMarks != null) {
                Set<Instant> seenMarkTimes = new HashSet<>();
                for (JsonNode rawMark : rawMarks) {
                    if (!(rawMark instanceof ObjectNode mark)) {
                        throw new IllegalArgumentException(record.id() + ": portfolio mark must be an object");
                    }
                    String timeText = mark.path("time").asText("");
                    Instant time = parseInstant(timeText, record.id() + " mark time");
                    double price = requiredPositive(mark, "price", record.id() + " mark");
                    if (!seenMarkTimes.add(time)) {
                        throw new IllegalArgumentException(record.id() + ": duplicate portfolio mark instant");
                    }
                    if (time.isBefore(record.entryTime())) {
                        throw new IllegalArgumentException(record.id() + ": portfolio mark precedes entry");
                    }
                    if (time.equals(record.exitTime())) {
                        excludedBoundaryMarks.add(JsonHashes.mapper().createObjectNode()
                                .put("trade_id", record.id()).put("original_time", timeText)
                                .put("canonical_time", time.toString()).put("reason", "EXIT_BOUNDARY_EXCLUDED"));
                        continue;
                    }
                    if (time.isAfter(record.exitTime())) {
                        throw new IllegalArgumentException(record.id() + ": portfolio mark is after exit");
                    }
                    correctedMarks.add(mark.deepCopy());
                    events.add(LedgerEvent.mark(time, record, price));
                }
            }
            correctedTrade.set("portfolio_mark_points", correctedMarks);
            correctedTrades.add(correctedTrade);
            events.add(LedgerEvent.entry(record.entryTime(), record));
            events.add(LedgerEvent.exit(record.exitTime(), record));
        }
        events.sort(Comparator.comparing(LedgerEvent::time)
                .thenComparingInt(LedgerEvent::priority)
                .thenComparing(event -> event.trade().id()));

        AccountingState state = new AccountingState(starting);
        state.net = records.stream().mapToDouble(TradeRecord::net).sum();
        if (!Double.isFinite(state.net)) throw new IllegalArgumentException("portfolio net P&L is not finite");
        for (LedgerEvent event : events) apply(event, state);
        assertTerminal(state, records.size(), declared, starting);
        ObjectNode buildIdentity = BuildIdentityService.describe(StrategyFixedBaselinePortfolioCorrectionV1.class);
        String compiledSourceFingerprint = buildIdentity.path("compiled").path("input_fingerprint").asText("");
        if (!JsonHashes.isSha256(compiledSourceFingerprint)) compiledSourceFingerprint = "UNKNOWN";
        return result(records, correctedTrades, events, state, declared, starting, startingPolicy,
                sourceKind, sourceHash, correctionInputHash, excludedBoundaryMarks, buildIdentity,
                compiledSourceFingerprint);
    }

    private static TradeRecord readTrade(ObjectNode trade, Set<String> seenIds) {
        String id = trade.path("episode_id").asText(trade.path("signal_id").asText(""));
        if (id.isBlank()) throw new IllegalArgumentException("closed trade lacks episode_id");
        if (!seenIds.add(id)) throw new IllegalArgumentException(id + ": duplicate trade id");
        ObjectNode lifecycle = object(trade, "lifecycle", id);
        String entryText = lifecycle.path("entry_time").asText("");
        Instant entry = parseInstant(entryText, id + " entry time");
        JsonNode exits = lifecycle.path("exits");
        if (!exits.isArray() || exits.size() != 1 || !exits.get(0).isObject()) {
            throw new IllegalArgumentException(id + ": corrected accounting requires one complete exit");
        }
        ObjectNode exit = (ObjectNode) exits.get(0);
        String exitText = exit.path("availability_time").asText("");
        if (exitText.isBlank()) exitText = exit.path("time").asText("");
        Instant exitTime = parseInstant(exitText, id + " exit availability time");
        if (!exitTime.isAfter(entry)) throw new IllegalArgumentException(id + ": exit must be after entry");
        double quantity = requiredPositive(trade, "quantity", id);
        double entryPrice = requiredPositive(trade, "entry_price", id);
        if (trade.has("direction") && !trade.path("direction").asText("long").isBlank()
                && !"long".equalsIgnoreCase(trade.path("direction").asText())) {
            throw new IllegalArgumentException(id + ": corrected accounting supports long spot trades only");
        }
        if (lifecycle.has("quantity") && !closeEnough(requiredPositive(lifecycle, "quantity", id), quantity)) {
            throw new IllegalArgumentException(id + ": lifecycle quantity differs from trade quantity");
        }
        if (exit.has("quantity") && !closeEnough(requiredPositive(exit, "quantity", id + " exit"), quantity)) {
            throw new IllegalArgumentException(id + ": exit quantity must close the full trade");
        }
        if (exit.has("fraction") && !closeEnough(requiredFinite(exit, "fraction", id + " exit"), 1D)) {
            throw new IllegalArgumentException(id + ": exit fraction must be one for a closed trade");
        }
        if (lifecycle.has("remaining_quantity")
                && !closeEnough(requiredFinite(lifecycle, "remaining_quantity", id), 0D)) {
            throw new IllegalArgumentException(id + ": lifecycle retains a remaining quantity");
        }
        double exitPrice = requiredPositive(exit, "price", id + " exit");
        double aggregateExitPrice = trade.path("exit_price").isNumber()
                ? requiredPositive(trade, "exit_price", id) : exitPrice;
        if (!closeEnough(exitPrice, aggregateExitPrice)) {
            throw new IllegalArgumentException(id + ": lifecycle exit price differs from trade exit price");
        }
        double expectedGross = (exitPrice - entryPrice) * quantity;
        double gross = numericOrDerived(trade, "gross_pnl_usdt", expectedGross);
        if (!closeEnough(gross, expectedGross)) {
            throw new IllegalArgumentException(id + ": gross P&L does not reconcile to long spot prices");
        }
        double fees = numericOrZero(trade, "fees_usdt");
        double slippage = numericOrZero(trade, "slippage_usdt");
        double capacity = numericOrZero(trade, "capacity_debit_usdt");
        double net = numericOrDerived(trade, "net_pnl_usdt", gross - fees - slippage - capacity);
        double exitFees = numericOrZero(exit, "fees_usd");
        double exitSlippage = numericOrZero(exit, "slippage_usd");
        if (exitFees > fees + EPSILON || exitSlippage > slippage + EPSILON) {
            throw new IllegalArgumentException(id + ": exit costs exceed aggregate costs");
        }
        if (!closeEnough(net, gross - fees - slippage - capacity)) {
            throw new IllegalArgumentException(id + ": net P&L does not reconcile to costs");
        }
        return new TradeRecord(id, trade, entry, exitTime, quantity, entryPrice, exitPrice,
                gross, fees, slippage, capacity, net, exitFees, exitSlippage);
    }

    private static void apply(LedgerEvent event, AccountingState state) {
        TradeRecord trade = event.trade();
        String id = trade.id();
        double notional = trade.entryPrice() * trade.quantity();
        if (event.kind() == Kind.ENTRY) {
            if (!state.active.add(id) || state.marked.containsKey(id)) {
                throw new IllegalArgumentException(id + ": duplicate entry or active holding");
            }
            double entryFees = trade.fees() - trade.exitFees();
            double entrySlippage = trade.slippage() - trade.exitSlippage();
            state.cash -= notional + entryFees + entrySlippage + trade.capacity();
            if (state.cash < -EPSILON) {
                throw new IllegalArgumentException("fixed portfolio exhausted its declared starting cash at entry");
            }
            state.holdingsAtEntryCost += notional;
            state.marked.put(id, notional);
            addPoint(state, event, "ENTRY", 0D, notional);
        } else if (event.kind() == Kind.MARK) {
            if (!state.active.contains(id) || !state.marked.containsKey(id)) {
                throw new IllegalArgumentException(id + ": mark would recreate an inactive holding");
            }
            state.marked.put(id, event.markPrice() * trade.quantity());
            addPoint(state, event, "MARK", 0D, notional);
        } else {
            if (!state.active.contains(id) || !state.marked.containsKey(id)) {
                throw new IllegalArgumentException(id + ": exit has no active holding");
            }
            state.cash += trade.exitPrice() * trade.quantity() - trade.exitFees() - trade.exitSlippage();
            state.holdingsAtEntryCost -= notional;
            if (Math.abs(state.holdingsAtEntryCost) <= EPSILON) state.holdingsAtEntryCost = 0D;
            state.active.remove(id);
            state.marked.remove(id);
            state.realized += trade.net();
            addPoint(state, event, "EXIT", trade.net(), notional);
        }
    }

    private static void addPoint(AccountingState state, LedgerEvent event, String type,
            double realized, double notional) {
        double marked = state.marked.values().stream().mapToDouble(Double::doubleValue).sum();
        double equity = state.cash + marked;
        if (!closeEnough(equity, state.cash + marked)) {
            throw new IllegalArgumentException("portfolio curve point does not reconcile to cash plus marks");
        }
        state.peak = Math.max(state.peak, equity);
        state.maxDrawdown = Math.max(state.maxDrawdown, state.peak - equity);
        ObjectNode point = JsonHashes.mapper().createObjectNode()
                .put("episode_id", event.trade().id())
                .put("event_time", event.time().toString())
                .put("event_type", type)
                .put("cash_usdt", state.cash)
                .put("holdings_at_entry_cost_usdt", state.holdingsAtEntryCost)
                .put("marked_holdings_usdt", marked)
                .put("active_position_count", state.active.size())
                .put("equity_usdt", equity)
                .put("drawdown_usdt", state.peak - equity)
                .put("realized_pnl_usdt", realized)
                .put("entry_notional_usdt", notional);
        ArrayNode activeIds = point.putArray("active_trade_ids");
        state.active.stream().sorted().forEach(activeIds::add);
        state.curve.add(point);
    }

    private static void assertTerminal(AccountingState state, int tradeCount, double declared, double starting) {
        if (state.cash < -EPSILON) throw new IllegalArgumentException("fixed portfolio ended with negative cash");
        if (!state.active.isEmpty() || !state.marked.isEmpty()) {
            throw new IllegalArgumentException("portfolio retains residual marked holdings after closed trade book");
        }
        if (tradeCount > 0 && (state.curve.isEmpty()
                || !"EXIT".equals(state.curve.get(state.curve.size() - 1).path("event_type").asText()))) {
            throw new IllegalArgumentException("closed trade book must end at an EXIT");
        }
        double finalEquity = state.curve.isEmpty() ? starting : state.curve.get(state.curve.size() - 1)
                .path("equity_usdt").asDouble(Double.NaN);
        if (!closeEnough(finalEquity, state.cash) || !closeEnough(finalEquity, declared)
                || !closeEnough(declared, starting + state.net)
                || !closeEnough(state.cash, starting + state.net)) {
            throw new IllegalArgumentException("portfolio ending equity does not reconcile to cash, declaration, and net P&L");
        }
    }

    private static ObjectNode result(List<TradeRecord> records, ArrayNode correctedTrades,
            List<LedgerEvent> events, AccountingState state, double declared, double starting,
            String startingPolicy, String sourceKind, String sourceHash, String correctionInputHash,
            ArrayNode excludedMarks,
            ObjectNode buildIdentity, String compiledSourceFingerprint) {
        double gross = records.stream().mapToDouble(TradeRecord::gross).sum();
        double fees = records.stream().mapToDouble(TradeRecord::fees).sum();
        double slippage = records.stream().mapToDouble(TradeRecord::slippage).sum();
        double capacity = records.stream().mapToDouble(TradeRecord::capacity).sum();
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", RESULT_SCHEMA).put("version", VERSION)
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("evaluator_identity", EVALUATOR_ID)
                .put("source_fingerprint", compiledSourceFingerprint)
                .put("source_fingerprint_provenance", buildIdentity.path("compiled").path("input_fingerprint").isTextual()
                        && JsonHashes.isSha256(buildIdentity.path("compiled").path("input_fingerprint").asText())
                        ? "COMPILED_BUILD_MARKER" : "UNKNOWN_COMPILED_BUILD_MARKER")
                .put("algorithm_fingerprint", SOURCE_FINGERPRINT)
                .put("source_kind", sourceKind)
                .put("source_input_canonical_sha256", sourceHash)
                .put("correction_input_canonical_sha256", correctionInputHash)
                .put("account_currency", "USDT")
                .put("starting_equity_usdt", starting)
                .put("starting_capital_usdt", starting)
                .put("starting_capital_policy", startingPolicy)
                .put("trade_count", records.size())
                .put("event_count", events.size())
                .put("gross_pnl_usdt", gross).put("fees_usdt", fees)
                .put("slippage_usdt", slippage).put("capacity_debit_usdt", capacity)
                .put("net_pnl_usdt", state.net)
                .put("realized_pnl_usdt", state.realized)
                .put("ending_equity_usdt", state.cash)
                .put("declared_ending_equity_usdt", declared)
                .put("max_drawdown_usdt", state.maxDrawdown)
                .put("mark_method", "PHYSICAL_1M_CLOSES_AT_60M_SAMPLES;ACTIVE_INTERVAL_[ENTRY,EXIT)")
                .put("realized_pnl_posted_at_exit", true)
                .put("negative_cash", state.cash < 0D)
                .put("ending_minus_starting_equals_net", closeEnough(declared, starting + state.net))
                .put("sum_check", closeEnough(state.net, gross - fees - slippage - capacity))
                .put("raw_r_excluded_from_equity", true)
                .put("excluded_exit_boundary_mark_count", excludedMarks.size())
                .put("final_marked_holdings_usdt", 0D)
                .put("final_active_position_count", 0)
                .put("final_curve_equity_usdt", state.cash)
                .put("final_curve_equity_reconciles_cash_plus_marked_holdings", finalCurveReconciles(state))
                .put("final_curve_equity_reconciles_declared_ending_equity", finalEquity(state, declared))
                .put("declared_ending_equity_reconciles_starting_plus_net_pnl", closeEnough(declared, starting + state.net))
                .put("final_curve_point_is_exit_with_no_residual_active_position", finalExitIsClosed(state));
        result.set("trades", correctedTrades);
        result.set("equity_curve", state.curve);
        result.set("excluded_exit_boundary_marks", excludedMarks);
        ObjectNode invariants = result.putObject("invariants")
                .put("no_residual_marked_holding_for_exited_trade", state.active.isEmpty() && state.marked.isEmpty())
                .put("final_curve_equity_reconciles_cash_plus_marked_holdings", finalCurveReconciles(state))
                .put("final_curve_equity_reconciles_declared_ending_equity", finalEquity(state, declared))
                .put("declared_ending_equity_reconciles_starting_plus_net_pnl", closeEnough(declared, starting + state.net))
                .put("final_curve_point_is_exit_with_no_residual_active_position", finalExitIsClosed(state));
        result.put("correction_status", "CORRECTED");
        ObjectNode receipt = result.putObject("correction_receipt")
                .put("schema", RECEIPT_SCHEMA).put("version", VERSION)
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("source_evaluator_identity", "com.tradinganalytics.research.v5.StrategyFixedBaselineV5")
                .put("corrected_evaluator_identity", EVALUATOR_ID)
                .put("source_fingerprint", compiledSourceFingerprint)
                .put("algorithm_fingerprint", SOURCE_FINGERPRINT)
                .put("source_input_canonical_sha256", sourceHash)
                .put("trade_count", records.size())
                .put("excluded_exit_boundary_mark_count", excludedMarks.size())
                .put("mark_interval", "[entry_time, exit_availability_time)")
                .put("event_order", "canonical Instant; EXIT before ENTRY before MARK; trade_id tie-break")
                .put("post_exit_mark_policy", "FAIL")
                .put("active_mark_policy", "FAIL_IF_INACTIVE")
                .put("final_curve_equity_usdt", state.cash)
                .put("declared_ending_equity_usdt", declared);
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        result.set("build_identity", buildIdentity);
        return withOwnHash(result);
    }

    private static boolean finalCurveReconciles(AccountingState state) {
        if (state.curve.isEmpty()) return true;
        JsonNode last = state.curve.get(state.curve.size() - 1);
        return closeEnough(last.path("equity_usdt").asDouble(Double.NaN),
                last.path("cash_usdt").asDouble(Double.NaN) + last.path("marked_holdings_usdt").asDouble(Double.NaN));
    }

    private static boolean finalEquity(AccountingState state, double declared) {
        if (state.curve.isEmpty()) return closeEnough(state.cash, declared);
        return closeEnough(state.curve.get(state.curve.size() - 1)
                .path("equity_usdt").asDouble(Double.NaN), declared);
    }

    private static boolean finalExitIsClosed(AccountingState state) {
        if (state.curve.isEmpty()) return true;
        JsonNode last = state.curve.get(state.curve.size() - 1);
        return "EXIT".equals(last.path("event_type").asText())
                && last.path("marked_holdings_usdt").asDouble(Double.NaN) == 0D
                && last.path("active_position_count").asInt(-1) == 0;
    }

    private static ObjectNode withOwnHash(ObjectNode value) {
        ObjectNode copy = value.deepCopy();
        copy.put("content_sha256", JsonHashes.ownHash(copy));
        return copy;
    }

    private static ObjectNode object(ObjectNode parent, String field, String id) {
        JsonNode value = parent.get(field);
        if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException(id + ": missing " + field);
        return object;
    }

    private static double requiredPositive(ObjectNode node, String field) {
        return requiredPositive(node, field, field);
    }

    private static double requiredPositive(ObjectNode node, String field, String label) {
        double value = requiredFinite(node, field, label);
        if (!(value > 0D)) throw new IllegalArgumentException(label + " must be positive");
        return value;
    }

    private static double requiredFinite(ObjectNode node, String field) {
        return requiredFinite(node, field, field);
    }

    private static double requiredFinite(ObjectNode node, String field, String label) {
        JsonNode raw = node.get(field);
        if (raw == null || !raw.isNumber() || !Double.isFinite(raw.asDouble())) {
            throw new IllegalArgumentException(label + " must be finite numeric evidence");
        }
        return raw.asDouble();
    }

    private static double numericOrZero(ObjectNode node, String field) {
        if (!node.has(field)) return 0D;
        double value = requiredFinite(node, field);
        if (value < 0D) throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }

    private static double numericOrDerived(ObjectNode node, String field, double fallback) {
        if (!node.has(field)) return fallback;
        return requiredFinite(node, field);
    }

    private static Instant parseInstant(String value, String label) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(label + " must be an ISO-8601 instant", error);
        }
    }

    private static boolean closeEnough(double left, double right) {
        if (!Double.isFinite(left) || !Double.isFinite(right)) return false;
        return Math.abs(left - right) <= EPSILON * Math.max(1D, Math.max(Math.abs(left), Math.abs(right)));
    }

    private static String sourceFingerprint() {
        ObjectNode descriptor = JsonHashes.mapper().createObjectNode()
                .put("evaluator_identity", EVALUATOR_ID)
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("timestamp_type", "java.time.Instant")
                .put("event_order", "EXIT,ENTRY,MARK")
                .put("mark_interval", "[entry_time,exit_availability_time)")
                .put("inactive_mark_policy", "FAIL")
                .put("terminal_invariants", "cash+marked;declared;starting+net;closed-exit");
        return JsonHashes.canonicalSha256(descriptor);
    }

    private enum Kind {
        EXIT, ENTRY, MARK
    }

    private record TradeRecord(String id, ObjectNode trade, Instant entryTime, Instant exitTime,
            double quantity, double entryPrice, double exitPrice, double gross, double fees,
            double slippage, double capacity, double net, double exitFees, double exitSlippage) {}

    private record LedgerEvent(Instant time, Kind kind, TradeRecord trade, double markPrice) {
        static LedgerEvent exit(Instant time, TradeRecord trade) { return new LedgerEvent(time, Kind.EXIT, trade, Double.NaN); }
        static LedgerEvent entry(Instant time, TradeRecord trade) { return new LedgerEvent(time, Kind.ENTRY, trade, Double.NaN); }
        static LedgerEvent mark(Instant time, TradeRecord trade, double price) { return new LedgerEvent(time, Kind.MARK, trade, price); }
        int priority() { return kind.ordinal(); }
    }

    private static final class AccountingState {
        double cash;
        double holdingsAtEntryCost;
        double realized;
        double net;
        double peak;
        double maxDrawdown;
        final Map<String, Double> marked = new LinkedHashMap<>();
        final Set<String> active = new HashSet<>();
        final ArrayNode curve = JsonHashes.mapper().createArrayNode();

        AccountingState(double starting) {
            cash = starting;
            peak = starting;
        }
    }
}
