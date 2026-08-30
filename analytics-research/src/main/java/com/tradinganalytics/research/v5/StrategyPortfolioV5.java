package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Java 21 port of {@code tools/strategy-portfolio.mjs}.
 *
 * <p>The module deliberately retains both portfolio contracts: the historical
 * realized-only simulator and the authoritative mark-to-market simulator. JSON
 * is used at this boundary because the Node module accepts arbitrary signal and
 * policy extensions. All public methods return the same JSON shape as Node's
 * JSON.stringify (non-finite numbers are represented as null).</p>
 */
public final class StrategyPortfolioV5 {
    public static final String PORTFOLIO_SCHEMA = "strategy-portfolio-mark-path/1";
    public static final List<String> CRYPTO_INSTRUMENT_TYPES = List.of(
            "spot", "perpetual", "perp", "dated_future", "future", "futures",
            "option", "options", "basis", "funding", "carry", "derivative");
    public static final Set<String> NON_CRYPTO_ASSET_CLASSES = Set.of(
            "equity", "etf", "rate", "rates", "fx", "currency", "commodity",
            "index", "bond", "cash");
    public static final Set<String> CRYPTO_ASSET_CLASSES = Set.of(
            "crypto", "cryptocurrency", "digital_asset", "digital-asset");

    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final Set<String> LINEAR_TYPES = Set.of(
            "spot", "perpetual", "perp", "dated_future", "future", "futures");

    private StrategyPortfolioV5() {}

    /* Public binding 1, and the target of public binding 6. */
    public static boolean validatePortfolioInstrument(JsonNode instrument) {
        return validatePortfolioInstrument(instrument, "instrument");
    }

    public static boolean validatePortfolioInstrument(JsonNode instrument, String name) {
        if (instrument == null || !instrument.isObject()) {
            throw error(name + " must be an object");
        }
        String assetClass = lower(firstTruthy(instrument, "asset_class", "assetClass"));
        String type = lower(firstTruthy(instrument, "instrument_type", "type"));
        if (NON_CRYPTO_ASSET_CLASSES.contains(assetClass) || !CRYPTO_ASSET_CLASSES.contains(assetClass)) {
            throw error(name + " must be crypto");
        }
        if (!CRYPTO_INSTRUMENT_TYPES.contains(type)) {
            throw error(name + " must be a crypto spot or derivative");
        }
        if (!truthy(instrument.get("asset")) && !truthy(instrument.get("symbol"))) {
            throw error(name + ".asset is required");
        }
        if (!"spot".equals(type)) {
            if (!truthy(instrument.get("venue")) && !truthy(instrument.get("exchange"))) {
                throw error(name + ".venue is required for derivatives");
            }
            if (!truthy(instrument.get("collateral")) && !truthy(instrument.get("collateral_asset"))) {
                throw error(name + ".collateral is required for derivatives");
            }
            if (Set.of("perpetual", "perp", "basis", "funding", "carry", "derivative").contains(type)
                    && !hasAny(instrument, "funding", "funding_rate", "carry", "carry_rate",
                    "funding_contract", "carry_contract")) {
                throw error(name + ".funding/carry metadata is required for derivatives");
            }
        }
        return true;
    }

    /* Public binding 2. */
    public static ObjectNode simulateLinearMarkToMarketPortfolio(ArrayNode signals, ObjectNode policy) {
        return simulateLinearMarkToMarketPortfolio((JsonNode) signals, policy);
    }

    public static ObjectNode simulateLinearMarkToMarketPortfolio(JsonNode signals, JsonNode policy) {
        ObjectNode p = objectOrEmpty(policy);
        ArrayNode rows = arrayOrEmpty(signals);
        double initialEquity = number(firstNullish(p, "initial_equity", "capital"));
        if (!(initialEquity > 0)) throw error("authoritative portfolio initial_equity must be positive");
        ArrayList<Mark> marks = normalizeMarks(p, true);
        if (marks.isEmpty()) throw error("authoritative portfolio requires a mark path");

        LinkedHashMap<String, List<Mark>> marksByKey = new LinkedHashMap<>();
        for (Mark mark : marks) marksByKey.computeIfAbsent(mark.key, ignored -> new ArrayList<>()).add(mark);
        double maxMarkGap = numberOrInfinity(firstNullish(p, "max_mark_gap_ms", "mark_gap_max_ms"));
        if (!(maxMarkGap > 0 || Double.isInfinite(maxMarkGap))) {
            throw error("authoritative portfolio max_mark_gap_ms must be positive or Infinity");
        }
        ObjectNode acceptance = objectOrEmpty(p.get("acceptance"));
        Limits limits = Limits.authoritative(p);
        List<String> failures = new ArrayList<>();
        ArrayNode rejected = MAPPER.createArrayNode();
        List<Position> positions = new ArrayList<>();
        List<Position> entries = new ArrayList<>();
        Map<Long, List<Position>> exits = new HashMap<>();
        Map<Long, List<FundingEvent>> fundingEvents = new HashMap<>();

        for (JsonNode signal : sortedSignals(rows)) {
            String id = text(firstTruthy(signal, "signal_id", "trade_id"));
            if (id.isEmpty()) id = keyFor(signal);
            JsonNode instrument = objectOrSelf(signal.get("instrument"), signal);
            String type;
            try {
                type = linearInstrument(instrument, "signal " + id + ".instrument");
            } catch (RuntimeException ex) {
                rejected.add(object().put("signal_id", id).put("reason", "UNSUPPORTED_LINEAR_INSTRUMENT")
                        .put("detail", ex.getMessage()));
                failures.add("UNSUPPORTED_INSTRUMENT");
                continue;
            }

            long entryTime;
            long exitTime;
            try {
                entryTime = markTime(signal.get("entry_time"), "signal " + id + ".entry_time");
                exitTime = markTime(signal.get("exit_time"), "signal " + id + ".exit_time");
            } catch (RuntimeException ex) {
                rejected.add(object().put("signal_id", id).put("reason", "INVALID_TRADE_FIELDS")
                        .put("detail", ex.getMessage()));
                failures.add("INVALID_TRADE");
                continue;
            }
            String asset = lower(firstTruthy(signal, "asset", from(instrument, "asset"), from(instrument, "symbol")));
            String key = markKey(asset, instrument);
            Mark entryMark = exactMark(marksByKey, key, entryTime);
            Mark exitMark = exactMark(marksByKey, key, exitTime);
            double entry = number(signal.get("entry_price"));
            double exit = number(signal.get("exit_price"));
            String direction = lower(firstTruthy(signal, "direction", "side"));
            double multiplier = number(firstTruthy(signal, "contract_multiplier",
                    from(instrument, "contract_multiplier"), from(instrument, "multiplier"), numberNode(1)));
            double quantity = Math.abs(number(firstTruthy(signal, "quantity", "contracts",
                    numberNode(number(signal.get("notional")) / Math.max(entry, 1)))));
            String marginMode = lower(firstTruthy(signal, "margin_mode", from(instrument, "margin_mode")));
            double leverage = number(firstTruthy(signal, "leverage", from(instrument, "leverage"), numberNode(1)));
            String collateralCurrency = text(firstTruthy(signal, "collateral_currency",
                    from(instrument, "collateral_currency"), from(instrument, "collateral_asset"),
                    from(instrument, "collateral")));
            double collateral = number(firstNullish(signal, "collateral_used", "margin", "collateral"));
            double maintenanceRatio = number(firstNullish(signal, "maintenance_margin_ratio", from(instrument, "maintenance_margin_ratio")));
            if (!(entryTime < exitTime) || !(entry > 0) || !(exit > 0)
                    || !(direction.equals("long") || direction.equals("short"))
                    || !(quantity > 0) || !(multiplier > 0)) {
                rejected.add(object().put("signal_id", id).put("reason", "INVALID_TRADE_FIELDS"));
                failures.add("INVALID_TRADE");
                continue;
            }
            JsonNode declaredMultiplier = firstNullish(signal, "contract_multiplier");
            if (declaredMultiplier == null) declaredMultiplier = firstNullish(instrument, "contract_multiplier", "multiplier");
            if (!"spot".equals(type)
                    && (!(marginMode.equals("cross") || marginMode.equals("isolated"))
                    || !(leverage >= 1) || collateralCurrency.isEmpty() || !(collateral > 0)
                    || !(maintenanceRatio >= 0) || !signal.has("funding_settlements")
                    || (!truthy(instrument.get("symbol")) && !truthy(instrument.get("instrument_id")))
                    || !(number(declaredMultiplier) > 0))) {
                String reason = signal.has("funding_settlements") ? "INCOMPLETE_DERIVATIVE_TERMS" : "MISSING_FUNDING_DATA";
                rejected.add(object().put("signal_id", id).put("reason", "INCOMPLETE_DERIVATIVE_TERMS"));
                failures.add(reason);
                continue;
            }
            if (entryMark == null || exitMark == null) {
                rejected.add(object().put("signal_id", id).put("reason", "MISSING_EXACT_MARK"));
                failures.add("MISSING_MARK_PATH");
                continue;
            }
            double entrySlip = finiteOrZero(firstNullish(signal, "entry_slippage_pct", from(instrument, "entry_slippage_pct"), p.get("slippage_pct")));
            double exitSlip = finiteOrZero(firstNullish(signal, "exit_slippage_pct", from(instrument, "exit_slippage_pct"), p.get("slippage_pct")));
            double expectedEntry = direction.equals("long") ? entryMark.price * (1 + entrySlip / 100) : entryMark.price * (1 - entrySlip / 100);
            double expectedExit = direction.equals("long") ? exitMark.price * (1 - exitSlip / 100) : exitMark.price * (1 + exitSlip / 100);
            double tolerance = Math.max(1e-9, Math.max(expectedEntry, expectedExit) * 1e-9);
            if (Math.abs(entry - expectedEntry) > tolerance || Math.abs(exit - expectedExit) > tolerance) {
                rejected.add(object().put("signal_id", id).put("reason", "FILL_PRICE_MARK_RECONCILIATION_FAILED"));
                failures.add("FORGED_FILL_PRICE");
                continue;
            }
            ArrayNode funding = arrayOrEmpty(signal.get("funding_settlements"));
            boolean invalidFunding = false;
            String fundingError = null;
            for (JsonNode settlement : funding) {
                long fundingTime;
                try {
                    fundingTime = markTime(firstTruthy(settlement, "time", "timestamp"), "signal " + id + ".funding.time");
                } catch (RuntimeException ex) {
                    fundingError = ex.getMessage(); invalidFunding = true; break;
                }
                String venue = text(firstTruthy(settlement, "venue", from(instrument, "venue"), from(instrument, "exchange")));
                String settlementInstrument = text(firstTruthy(settlement, "instrument", from(instrument, "symbol"), from(instrument, "instrument_id")));
                String source = text(firstTruthy(settlement, "source"));
                if (source.isEmpty() && !venue.isEmpty() && !settlementInstrument.isEmpty()) source = "legacy-signal-settlement";
                String eventId = text(firstTruthy(settlement, "event_id"));
                if (eventId.isEmpty() && !venue.isEmpty() && !settlementInstrument.isEmpty()) eventId = venue + "|" + settlementInstrument + "|" + fundingTime;
                boolean authoritativeIdentityRequired = strictTrue(p.get("require_authoritative_funding_identity")) && !"spot".equals(type);
                JsonNode amountNode = firstNullish(settlement, "amount", "pnl");
                if (fundingTime < entryTime || fundingTime > exitTime || !Double.isFinite(number(amountNode))
                        || eventId.isEmpty() || source.isEmpty() || venue.isEmpty() || settlementInstrument.isEmpty()
                        || (authoritativeIdentityRequired && (!truthy(settlement.get("event_id")) || !truthy(settlement.get("source"))))) {
                    fundingError = authoritativeIdentityRequired
                            ? "authoritative funding settlement requires source and stable event_id"
                            : "funding settlement requires time, amount (including zero), stable event_id, source, venue and instrument";
                    invalidFunding = true; break;
                }
                FundingEvent event = new FundingEvent(id, number(amountNode), eventId, source, venue, settlementInstrument,
                        truthy(settlement.get("event_id")) && truthy(settlement.get("source")) ? "AUTHORITATIVE" : "LEGACY_DERIVED");
                fundingEvents.computeIfAbsent(fundingTime, ignored -> new ArrayList<>()).add(event);
            }
            if (invalidFunding) {
                rejected.add(object().put("signal_id", id).put("reason", "INVALID_FUNDING_SETTLEMENT")
                        .put("detail", fundingError));
                failures.add("INVALID_FUNDING_DATA");
                continue;
            }
            long coveredMarks = marksByKey.getOrDefault(key, List.of()).stream()
                    .filter(mark -> mark.time >= entryTime && mark.time <= exitTime).count();
            if (!"spot".equals(type) && coveredMarks < 3) {
                rejected.add(object().put("signal_id", id).put("reason", "MISSING_LEVERAGED_MARK_PATH"));
                failures.add("MISSING_MARK_PATH");
                continue;
            }
            double fees = Math.abs(number(firstNullish(signal, "fees", "fee")));
            double notional = Math.abs(quantity * entry * multiplier);
            String cluster = text(firstTruthy(signal, "risk_cluster", "cluster", from(instrument, "risk_cluster"), textNode(asset)));
            String venue = text(firstTruthy(instrument, "venue", "exchange"));
            String symbol = text(firstTruthy(instrument, "symbol", "instrument_id", textNode(asset)));
            ObjectNode normalizedInstrument = (ObjectNode) instrument.deepCopy();
            normalizedInstrument.put("asset", asset).put("symbol", symbol).put("venue", venue);
            Position position = new Position(signal.deepCopy(), id, asset, key, direction, type, venue, symbol,
                    normalizedInstrument, entry, exit, entryMark.price, exitMark.price, quantity, multiplier, notional,
                    "spot".equals(type) ? (number(firstNullish(signal, "collateral_used", "margin", "notional")) == 0
                            ? notional : number(firstNullish(signal, "collateral_used", "margin", "notional"))) : collateral,
                    leverage, "spot".equals(type) ? "spot" : marginMode,
                    "spot".equals(type) ? (collateralCurrency.isEmpty() ? asset : collateralCurrency) : collateralCurrency,
                    maintenanceRatio, entryTime, exitTime, fees, cluster, funding.deepCopy());
            positions.add(position); entries.add(position); exits.computeIfAbsent(exitTime, ignored -> new ArrayList<>()).add(position);
        }

        return finishAuthoritative(positions, entries, exits, fundingEvents, marks, marksByKey, initialEquity,
                maxMarkGap, limits, acceptance, failures, rejected, p);
    }

    /* Public binding 3. */
    public static ObjectNode simulateLinearMarkToMarketPortfolioLegacy(ArrayNode signals, ObjectNode policy) {
        return simulateLinearMarkToMarketPortfolioLegacy((JsonNode) signals, policy);
    }

    public static ObjectNode simulateLinearMarkToMarketPortfolioLegacy(JsonNode signals, JsonNode policy) {
        ObjectNode p = objectOrEmpty(policy);
        ArrayNode rows = arrayOrEmpty(signals);
        double initialEquity = number(firstNullish(p, "initial_equity", "capital"));
        if (!(initialEquity > 0)) throw error("authoritative portfolio initial_equity must be positive");
        ArrayList<Mark> marks = normalizeMarks(p, false);
        if (marks.isEmpty()) throw error("authoritative portfolio requires a mark path");
        if (marks.stream().anyMatch(mark -> !(mark.price > 0) || mark.asset.isEmpty())) {
            throw error("authoritative mark path contains invalid asset/price");
        }
        Map<String, List<Mark>> byAsset = new HashMap<>();
        for (Mark mark : marks) byAsset.computeIfAbsent(mark.asset, ignored -> new ArrayList<>()).add(mark);
        ArrayNode accepted = MAPPER.createArrayNode();
        ArrayNode rejected = MAPPER.createArrayNode();
        ArrayNode closed = MAPPER.createArrayNode();
        ArrayNode equityCurve = MAPPER.createArrayNode();
        equityCurve.add(object().putNull("time").put("equity", initialEquity).put("drawdown_pct", 0));
        List<String> failures = new ArrayList<>();
        ObjectNode acceptance = objectOrEmpty(p.get("acceptance"));
        double peak = initialEquity;
        double currentEquity = initialEquity;
        boolean liquidation = false;
        List<ObjectNode> acceptedObjects = new ArrayList<>();

        for (JsonNode signal : sortedSignals(rows)) {
            String id = text(firstTruthy(signal, "signal_id", "trade_id", "candidate_id"));
            if (id.isEmpty()) id = keyFor(signal);
            JsonNode instrument = objectOrSelf(signal.get("instrument"), signal);
            String type;
            try {
                type = linearInstrument(instrument, "signal " + id + ".instrument");
            } catch (RuntimeException ex) {
                rejected.add(object().put("signal_id", id).put("reason", "UNSUPPORTED_LINEAR_INSTRUMENT")
                        .put("detail", ex.getMessage())); failures.add("UNSUPPORTED_INSTRUMENT"); continue;
            }
            String asset = lower(firstTruthy(signal, "asset", from(instrument, "asset"), from(instrument, "symbol")));
            double entry = number(signal.get("entry_price"));
            double exit = number(signal.get("exit_price"));
            long entryTime = markTime(signal.get("entry_time"), "signal " + id + ".entry_time");
            long exitTime = markTime(signal.get("exit_time"), "signal " + id + ".exit_time");
            if (!(entry > 0) || !(exit > 0) || !(exitTime > entryTime)) {
                rejected.add(object().put("signal_id", id).put("reason", "INVALID_PRICE_OR_LIFECYCLE")); failures.add("INVALID_TRADE"); continue;
            }
            Double entryMark = priceAt(byAsset, asset, entryTime, false);
            Double exitMark = priceAt(byAsset, asset, exitTime, false);
            if (entryMark == null || exitMark == null) {
                rejected.add(object().put("signal_id", id).put("reason", "MISSING_ENTRY_OR_EXIT_MARK")); failures.add("MISSING_MARK_PATH"); continue;
            }
            double quantity = Math.abs(number(firstTruthy(signal, "quantity", "contracts",
                    numberNode(number(signal.get("notional")) / entry))));
            if (!(quantity > 0)) { rejected.add(object().put("signal_id", id).put("reason", "INVALID_QUANTITY")); failures.add("INVALID_TRADE"); continue; }
            String direction = lower(firstTruthy(signal, "direction", "side"));
            if (!(direction.equals("long") || direction.equals("short"))) { rejected.add(object().put("signal_id", id).put("reason", "INVALID_DIRECTION")); failures.add("INVALID_TRADE"); continue; }
            double leverage = number(firstTruthy(signal, "leverage", from(instrument, "leverage"), numberNode(1)));
            double notional = number(signal.get("notional")); if (notional == 0) notional = quantity * entry;
            double collateral = number(firstTruthy(signal, "collateral_used", "margin", "collateral",
                    numberNode(notional / Math.max(1, leverage))));
            double maintenanceRatio = number(firstTruthy(signal, "maintenance_margin_ratio",
                    from(instrument, "maintenance_margin_ratio"), numberNode(0)));
            if (!(collateral > 0)) { rejected.add(object().put("signal_id", id).put("reason", "INVALID_COLLATERAL")); failures.add("INVALID_TRADE"); continue; }
            if (!"spot".equals(type) && !(leverage >= 1)) { rejected.add(object().put("signal_id", id).put("reason", "INVALID_LEVERAGE")); failures.add("INVALID_TRADE"); continue; }
            if (!"spot".equals(type) && !signal.has("funding_settlements")) { rejected.add(object().put("signal_id", id).put("reason", "MISSING_ACTUAL_FUNDING_SETTLEMENTS")); failures.add("MISSING_FUNDING_DATA"); continue; }
            double fees = number(firstNullish(signal, "fees", "fee"));
            double funding = 0;
            for (JsonNode settlement : arrayOrEmpty(signal.get("funding_settlements"))) funding += number(firstNullish(settlement, "amount", "pnl"));
            double gross = direction.equals("long") ? (exit - entry) * quantity : (entry - exit) * quantity;
            double realized = gross - Math.abs(fees) + funding;
            ObjectNode position = object();
            position.set("signal", signal.deepCopy());
            position.put("signal_id", id).put("asset", asset).put("direction", direction)
                    .put("type", type).put("entry", entry).put("exit", exit).put("quantity", quantity)
                    .put("collateral", collateral).put("leverage", leverage).put("maintenanceRatio", maintenanceRatio)
                    .put("entryTime", entryTime).put("exitTime", exitTime).put("realized", realized);
            List<Mark> timeline = marks.stream().filter(mark -> mark.asset.equals(asset) && mark.time >= entryTime && mark.time <= exitTime).toList();
            if (timeline.size() < 2) { rejected.add(object().put("signal_id", id).put("reason", "INSUFFICIENT_MARK_POINTS")); failures.add("MISSING_MARK_PATH"); continue; }
            double tradeMinEquity = currentEquity; ObjectNode breach = null;
            for (Mark mark : timeline) {
                double unrealized = direction.equals("long") ? (mark.price - entry) * quantity : (entry - mark.price) * quantity;
                double marked = currentEquity + unrealized;
                tradeMinEquity = Math.min(tradeMinEquity, marked); peak = Math.max(peak, marked);
                double dd = peak > 0 ? (peak - marked) / peak : 0;
                ObjectNode curve = object().put("time", mark.time).put("equity", marked).put("drawdown_pct", dd * 100)
                        .put("asset", asset).put("signal_id", id); equityCurve.add(curve);
                if (maintenanceRatio > 0 && marked <= collateral * maintenanceRatio) {
                    breach = object().put("signal_id", id).put("time", mark.time).put("equity", marked)
                            .put("maintenance_requirement", collateral * maintenanceRatio); break;
                }
            }
            if (breach != null) {
                rejected.add(object().put("signal_id", id).put("reason", "MARGIN_BREACH_LIQUIDATION").set("event", breach));
                failures.add("MARGIN_BREACH_LIQUIDATION"); liquidation = true; continue;
            }
            position.put("mark_to_market_min_equity", tradeMinEquity); accepted.add(position); acceptedObjects.add(position);
            currentEquity += realized;
            closed.add(object().put("signal_id", id).put("asset", asset).put("exit_time", exitTime)
                    .put("realized_pnl", realized).put("gross_pnl", gross).put("fees", Math.abs(fees))
                    .put("funding_pnl", funding).put("intratrade_min_equity", tradeMinEquity));
            peak = Math.max(peak, currentEquity);
            equityCurve.add(object().put("time", exitTime).put("equity", currentEquity)
                    .put("drawdown_pct", peak > 0 ? (peak - currentEquity) / peak * 100 : 0)
                    .put("asset", asset).put("signal_id", id).put("realized", true));
        }
        ArrayNode sortedCurve = sortCurve(equityCurve);
        double maxDrawdown = 0; for (JsonNode row : sortedCurve) maxDrawdown = Math.max(maxDrawdown, number(row.get("drawdown_pct")));
        Map<String, List<Double>> returns = new HashMap<>();
        for (JsonNode row : closed) returns.computeIfAbsent(text(row.get("asset")), ignored -> new ArrayList<>()).add(number(row.get("realized_pnl")));
        ArrayNode correlations = correlationRows(returns);
        if (finiteNumber(acceptance.get("minimum_accepted_trades")) && accepted.size() < number(acceptance.get("minimum_accepted_trades"))) failures.add("MINIMUM_ACCEPTED_TRADES");
        if (finiteNumber(acceptance.get("maximum_drawdown_pct")) && maxDrawdown > number(acceptance.get("maximum_drawdown_pct"))) failures.add("MAXIMUM_DRAWDOWN");
        if (finiteNumber(acceptance.get("minimum_net_pnl")) && currentEquity - initialEquity < number(acceptance.get("minimum_net_pnl"))) failures.add("MINIMUM_NET_PNL");
        if (liquidation) failures.add("LIQUIDATION_OR_MARGIN_BREACH");
        int overlap = overlapCount(rows);
        ArrayNode liquidationEvents = MAPPER.createArrayNode(); for (JsonNode row : rejected) if ("MARGIN_BREACH_LIQUIDATION".equals(text(row.get("reason")))) liquidationEvents.add(row.get("event"));
        ArrayNode assets = MAPPER.createArrayNode(); returns.keySet().stream().sorted().forEach(assets::add);
        ObjectNode result = object().put("schema", PORTFOLIO_SCHEMA);
        ObjectNode policyOut = object().put("initial_equity", initialEquity); policyOut.set("acceptance", acceptance.deepCopy()); policyOut.putNull("max_mark_gap_ms").put("event_order", "mark -> funding -> exits -> entries -> mark");
        result.set("policy", policyOut); result.set("accepted_signals", accepted); result.set("rejected_signals", rejected); result.set("closed_trades", closed); result.set("equity_curve", sortedCurve);
        result.put("portfolio_equity", currentEquity).put("net_pnl", currentEquity - initialEquity).put("max_drawdown_pct", maxDrawdown)
                .put("drawdown_basis", "chronological mark-to-market union timeline").put("intratrade_drawdown", intratradeLegacy(accepted, byAsset));
        result.set("liquidation_events", liquidationEvents); result.put("margin_breach", liquidation);
        result.set("exposure", object().put("peak_gross_notional", maxNotional(acceptedObjects)).put("peak_collateral", maxCollateral(acceptedObjects)).put("leverage", maxLeverage(acceptedObjects)));
        result.set("signal_overlap", object().put("overlapping_entry_count", overlap));
        ObjectNode legacyCorrelations = object(); legacyCorrelations.set("contemporaneous", correlations); legacyCorrelations.set("stressed_worst_window", copyWithCorrelation(correlations)); result.set("correlations", legacyCorrelations);
        ObjectNode beta = object().put("method", "asset return correlation proxy"); beta.set("assets", assets); result.set("crypto_beta_risk_cluster", beta);
        result.set("standalone_pnl_volatility_share", object().put("status", "UNAVAILABLE_DIAGNOSTIC").put("reason", "LEGACY_REALIZED_ONLY_PATH_HAS_NO_ALIGNED_PNL_SERIES"));
        result.set("marginal_risk_contribution", object().put("status", "UNAVAILABLE_DIAGNOSTIC").put("reason", "TIMESTAMP_ALIGNED_COVARIANCE_MRC_NOT_IMPLEMENTED"));
        result.put("acceptance_contract_sha256", JsonHashes.canonicalSha256(acceptance)).put("pass", failures.isEmpty()); result.set("failures", uniqueStrings(failures)); result.put("activation", "RESEARCH_ONLY");
        return result;
    }

    /* Public binding 4. */
    public static ObjectNode simulateCryptoPortfolio(ArrayNode signals, ObjectNode policy) {
        return simulateCryptoPortfolio((JsonNode) signals, policy);
    }

    public static ObjectNode simulateCryptoPortfolio(JsonNode signals, JsonNode policy) {
        ObjectNode p = objectOrEmpty(policy);
        if (strictTrue(p.get("authoritative")) || strictTrue(p.get("mark_path_required"))) return simulateLinearMarkToMarketPortfolio(signals, p);
        ArrayNode rows = arrayOrEmpty(signals);
        double initialEquity = number(firstNullish(p, "initial_equity", "capital"));
        if (!(initialEquity > 0)) throw error("portfolio policy initial_equity/capital must be positive");
        Limits limits = Limits.legacy(p, initialEquity);
        ObjectNode acceptance = objectOrEmpty(p.get("acceptance"));
        List<PositionSimple> open = new ArrayList<>();
        ArrayNode accepted = MAPPER.createArrayNode(), rejected = MAPPER.createArrayNode(), closed = MAPPER.createArrayNode();
        ArrayNode equity = MAPPER.createArrayNode(); equity.add(object().putNull("time").put("equity", initialEquity).put("drawdown_pct", 0));
        double currentEquity = initialEquity, peakEquity = initialEquity, peakGross = 0, peakNet = 0, peakCollateral = 0; int peakConcurrency = 0;
        for (JsonNode signal : sortedSignals(rows)) {
            JsonNode instrument = objectOrSelf(signal.get("instrument"), signal);
            try { validatePortfolioInstrument(instrument, "signal " + text(firstTruthy(signal, "signal_id", "candidate_id")) + ".instrument"); }
            catch (RuntimeException ex) { rejected.add(object().put("signal_id", rejectId(signal)).put("reason", "NON_CRYPTO_OR_INVALID_INSTRUMENT").put("detail", ex.getMessage())); continue; }
            long opened = signalTime(signal), closedAt = closeTime(signal); String signalId = text(firstTruthy(signal, "signal_id", "candidate_id")); if (signalId.isEmpty()) signalId = keyFor(signal);
            if (!(opened > 0) || !(closedAt > opened)) { rejected.add(object().put("signal_id", signalId).put("reason", "INVALID_OR_MISSING_TRADE_LIFECYCLE")); continue; }
            // release all trades whose close precedes this signal, preserving Node's grouped close order
            currentEquity = releaseLegacy(open, opened, closed, equity, currentEquity);
            String asset = lower(firstTruthy(signal, "asset", from(instrument, "asset"), from(instrument, "symbol")));
            String direction = lower(firstTruthy(signal, "direction", "side"));
            String cluster = text(firstTruthy(signal, "risk_cluster", "strategy_cluster", "strategy_id", "strategy", textNode("default")));
            String venue = lower(firstTruthy(instrument, "venue", "exchange", textNode("spot")));
            final String clusterKey = cluster, venueKey = venue;
            double notional = Math.abs(number(firstNullish(signal, "notional", "position_notional", "size")));
            String idForReject = rejectId(signal);
            if (!(direction.equals("long") || direction.equals("short"))) { rejected.add(rejectSimple(idForReject, asset, "INVALID_DIRECTION")); continue; }
            if (open.size() >= limits.totalConcurrency) { rejected.add(rejectSimple(idForReject, asset, "TOTAL_CONCURRENCY_CAP")); continue; }
            if (!bool(p.get("allow_long_short_conflict")) && open.stream().anyMatch(x -> x.asset.equals(asset) && !x.direction.equals(direction))) { rejected.add(rejectSimple(idForReject, asset, "LONG_SHORT_CONFLICT")); continue; }
            if (open.stream().filter(x -> x.asset.equals(asset)).count() >= limits.perAssetConcurrency) { rejected.add(rejectSimple(idForReject, asset, "PER_ASSET_CONCURRENCY_CAP")); continue; }
            if (limits.clusterCaps.containsKey(clusterKey) && open.stream().filter(x -> x.cluster.equals(clusterKey)).count() >= number(limits.clusterCaps.get(clusterKey))) { rejected.add(rejectSimple(idForReject, asset, "RISK_STRATEGY_CLUSTER_CAP")); continue; }
            if (limits.venueCaps.containsKey(venueKey) && open.stream().filter(x -> x.venue.equals(venueKey)).count() >= number(limits.venueCaps.get(venueKey))) { rejected.add(rejectSimple(idForReject, asset, "VENUE_CAP")); continue; }
            ExposureSimple current = exposure(open);
            double newGross = current.gross + notional, newNet = current.net + (direction.equals("short") ? -notional : notional);
            JsonNode collateralNode = firstNullish(signal, "collateral_used", "margin");
            double requiredCollateral = collateralNode == null ? notional / Math.max(1, number(firstNullish(signal, "leverage"))) : number(collateralNode);
            double newCollateral = current.collateral + requiredCollateral;
            if (!(notional > 0) || !(requiredCollateral > 0)) { rejected.add(rejectSimple(idForReject, asset, "INVALID_NOTIONAL_OR_COLLATERAL")); continue; }
            boolean hasNetPnl = signal.has("net_pnl") && finiteNumber(signal.get("net_pnl"));
            if (!hasNetPnl && (!finiteNumberOrMissing(firstNullish(signal, "net_r", "return_r", "r")) || !(number(signal.get("risk_amount")) > 0))) { rejected.add(rejectSimple(idForReject, asset, "MISSING_PNL_OR_RISK_AMOUNT")); continue; }
            if (newGross > limits.grossExposureCap) { rejected.add(rejectSimple(idForReject, asset, "GROSS_EXPOSURE_CAP")); continue; }
            if (Math.abs(newNet) > limits.netExposureCap) { rejected.add(rejectSimple(idForReject, asset, "NET_DIRECTIONAL_EXPOSURE_CAP")); continue; }
            if (newCollateral > limits.collateralCap || newCollateral > currentEquity) { rejected.add(rejectSimple(idForReject, asset, newCollateral > currentEquity ? "AVAILABLE_EQUITY_CAP" : "COLLATERAL_CAP")); continue; }
            double effectiveLeverage = newCollateral == 0 ? Double.POSITIVE_INFINITY : newGross / newCollateral;
            if (effectiveLeverage > limits.leverageCap) { rejected.add(rejectSimple(idForReject, asset, "LEVERAGE_CAP")); continue; }
            PositionSimple position = new PositionSimple((ObjectNode) signal.deepCopy(), signalId, asset, direction, cluster, venue, notional, requiredCollateral, opened, closedAt, (ObjectNode) instrument.deepCopy());
            open.add(position); accepted.add(position.toJson()); peakGross = Math.max(peakGross, newGross); peakNet = Math.max(peakNet, Math.abs(newNet)); peakCollateral = Math.max(peakCollateral, newCollateral); peakConcurrency = Math.max(peakConcurrency, open.size());
        }
        currentEquity = releaseLegacy(open, Long.MAX_VALUE, closed, equity, currentEquity);
        double maxDrawdown = 0; for (JsonNode row : equity) maxDrawdown = Math.max(maxDrawdown, number(row.get("drawdown_pct")));
        double netPnl = currentEquity - initialEquity; List<String> failures = new ArrayList<>();
        if (finiteNumber(acceptance.get("minimum_accepted_trades")) && accepted.size() < number(acceptance.get("minimum_accepted_trades"))) failures.add("MINIMUM_ACCEPTED_TRADES");
        if (finiteNumber(acceptance.get("maximum_drawdown_pct")) && maxDrawdown > number(acceptance.get("maximum_drawdown_pct"))) failures.add("MAXIMUM_DRAWDOWN");
        if (finiteNumber(acceptance.get("minimum_final_equity")) && currentEquity < number(acceptance.get("minimum_final_equity"))) failures.add("MINIMUM_FINAL_EQUITY");
        if (finiteNumber(acceptance.get("minimum_net_pnl")) && netPnl < number(acceptance.get("minimum_net_pnl"))) failures.add("MINIMUM_NET_PNL");
        Map<String, Integer> rejectedByReason = new HashMap<>(); for (JsonNode row : rejected) rejectedByReason.merge(text(row.get("reason")), 1, Integer::sum);
        ObjectNode contention = object().put("accepted", accepted.size()).put("rejected", rejected.size()); ObjectNode byReason = contention.putObject("rejected_by_reason"); rejectedByReason.keySet().stream().sorted().forEach(k -> byReason.put(k, rejectedByReason.get(k)));
        ObjectNode result = object(); result.set("policy", limits.legacyJson()); result.set("acceptance_policy", acceptance.deepCopy());
        result.put("acceptance_contract_sha256", JsonHashes.canonicalSha256(acceptance)); result.set("accepted_signals", accepted); result.set("rejected_signals", rejected);
        result.set("closed_trades", sortClosed(closed)); result.set("equity_curve", sortCurve(equity)); result.put("portfolio_equity", currentEquity).put("net_pnl", netPnl)
                .put("max_drawdown_pct", maxDrawdown).put("drawdown_basis", "realized close-to-close equity; intratrade mark-to-market drawdown requires a separately supplied equity path");
        ObjectNode exposureOut = object(); exposureOut.set("ending", object().put("gross", 0).put("net", 0).put("collateral", 0).put("leverage", 0)); exposureOut.set("peak", object().put("gross", peakGross).put("absolute_net", peakNet).put("collateral", peakCollateral).put("concurrency", peakConcurrency)); result.set("exposure", exposureOut);
        result.set("contention", contention); result.set("stress_metrics", object().put("funding_pnl", fundingTotal(closed)).put("open_positions", 0));
        result.put("accounting_assumptions", "net_pnl or net_r*risk_amount is already net of base fees, slippage, and funding; funding_pnl is attribution only and is not subtracted twice")
                .put("pass", failures.isEmpty()); result.set("failures", strings(failures)); result.put("activation", "RESEARCH_ONLY");
        return result;
    }

    /* Public bindings 5 and 6 (Node aliases). */
    public static ObjectNode runCryptoPortfolio(ArrayNode signals, ObjectNode policy) { return simulateCryptoPortfolio(signals, policy); }
    public static ObjectNode runCryptoPortfolio(JsonNode signals, JsonNode policy) { return simulateCryptoPortfolio(signals, policy); }
    public static boolean validateCryptoPortfolioInstrument(JsonNode instrument) { return validatePortfolioInstrument(instrument); }

    private static ObjectNode finishAuthoritative(
            List<Position> positions, List<Position> entries, Map<Long, List<Position>> exits,
            Map<Long, List<FundingEvent>> fundingEvents, List<Mark> marks,
            Map<String, List<Mark>> marksByKey, double initialEquity, double maxMarkGap,
            Limits limits, ObjectNode acceptance, List<String> failures, ArrayNode rejected,
            ObjectNode policy) {
        Set<Long> timelineSet = new LinkedHashSet<>();
        for (Mark mark : marks) timelineSet.add(mark.time);
        timelineSet.addAll(fundingEvents.keySet());
        for (Position position : entries) { timelineSet.add(position.entryTime); timelineSet.add(position.exitTime); }
        List<Long> timeline = new ArrayList<>(timelineSet); timeline.sort(Long::compareTo);
        List<Position> open = new ArrayList<>();
        ArrayNode closed = MAPPER.createArrayNode();
        ArrayNode equityCurve = MAPPER.createArrayNode();
        equityCurve.add(object().putNull("time").put("equity", initialEquity).put("drawdown_pct", 0)
                .put("realized", initialEquity).put("gross_exposure", 0).put("net_exposure", 0)
                .put("collateral", 0).put("leverage", 0));
        double realizedCash = initialEquity, peak = initialEquity, maxUnderWater = 0, maxDrawdown = 0;
        long underWaterStart = Long.MIN_VALUE;
        double peakGross = 0, peakNet = 0, peakCollateral = 0;
        int peakConcurrency = 0;
        boolean liquidation = false;
        Map<String, List<ObjectNode>> positionSeries = new HashMap<>();
        for (Position position : positions) positionSeries.put(position.signalId, new ArrayList<>());
        Map<String, Mark> lastMarkByKey = new HashMap<>();
        Set<String> coverageFailures = new HashSet<>();

        for (long time : timeline) {
            for (Mark mark : marks) if (mark.time == time) lastMarkByKey.put(mark.key, mark);
            MarkResult preMark = processMarkedEquity(time, open, marksByKey, lastMarkByKey, maxMarkGap, positionSeries,
                    realizedCash, rejected, failures, coverageFailures);
            liquidation |= preMark.liquidation;
            for (FundingEvent event : fundingEvents.getOrDefault(time, List.of())) {
                Position position = firstPosition(positions, event.signalId);
                String eventKey = event.eventId.isEmpty() ? event.signalId + "|" + time : event.eventId;
                if (position != null && open.contains(position) && position.fundingEventIds.add(eventKey)) {
                    realizedCash += event.amount; position.fundingPnl += event.amount;
                }
            }
            for (Position position : exits.getOrDefault(time, List.of()).stream().toList()) {
                if (!open.remove(position)) continue;
                double gross = position.direction.equals("long")
                        ? (position.exit - position.entry) * position.quantity * position.multiplier
                        : (position.entry - position.exit) * position.quantity * position.multiplier;
                double fundingPnl = position.fundingPnl;
                double realized = gross - position.fees;
                realizedCash += realized; position.realized = realized; position.closed = true;
                closed.add(object().put("signal_id", position.signalId).put("asset", position.asset)
                        .put("exit_time", time).put("realized_pnl", realized + fundingPnl)
                        .put("gross_pnl", gross).put("fees", position.fees).put("funding_pnl", fundingPnl));
            }
            double availableEntryEquity = realizedCash + open.stream().mapToDouble(item -> item.unrealized).sum();
            List<Position> atEntry = entries.stream().filter(item -> item.entryTime == time)
                    .sorted(Comparator.comparing(item -> item.signalId)).toList();
            for (Position position : atEntry) {
                String capReason = capReason(position, open, limits, availableEntryEquity);
                if (capReason != null) {
                    rejected.add(object().put("signal_id", position.signalId).put("reason", "PORTFOLIO_CAP_REJECTED")
                            .put("cap_reason", capReason).set("detail", object().put("available_equity", availableEntryEquity)
                                    .put("required_collateral", position.collateral)));
                    failures.add("PORTFOLIO_CAP_REJECTED");
                } else open.add(position);
            }
            for (FundingEvent event : fundingEvents.getOrDefault(time, List.of())) {
                Position position = firstPosition(positions, event.signalId);
                String eventKey = event.eventId.isEmpty() ? event.signalId + "|" + time : event.eventId;
                if (position != null && open.contains(position) && position.fundingEventIds.add(eventKey)) {
                    realizedCash += event.amount; position.fundingPnl += event.amount;
                }
            }
            MarkResult current = processMarkedEquity(time, open, marksByKey, lastMarkByKey, maxMarkGap,
                    positionSeries, realizedCash, rejected, failures, coverageFailures);
            Exposure exposure = authoritativeExposure(open);
            peakGross = Math.max(peakGross, exposure.gross); peakNet = Math.max(peakNet, Math.abs(exposure.net));
            peakCollateral = Math.max(peakCollateral, exposure.collateral); peakConcurrency = Math.max(peakConcurrency, open.size());
            double equityValue = current.equity;
            peak = Math.max(peak, equityValue);
            if (equityValue < peak && underWaterStart == Long.MIN_VALUE) underWaterStart = time;
            else if (equityValue >= peak && underWaterStart != Long.MIN_VALUE) {
                maxUnderWater = Math.max(maxUnderWater, time - underWaterStart); underWaterStart = Long.MIN_VALUE;
            }
            double drawdown = peak > 0 ? (peak - equityValue) / peak : 0;
            maxDrawdown = Math.max(maxDrawdown, drawdown);
            equityCurve.add(object().put("time", time).put("equity", equityValue).put("drawdown_pct", drawdown * 100)
                    .put("realized", realizedCash).put("unrealized", current.unrealized)
                    .put("gross_exposure", exposure.gross).put("net_exposure", exposure.net)
                    .put("collateral", exposure.collateral).put("leverage", exposure.collateral > 0 ? exposure.gross / exposure.collateral : 0)
                    .set("open_signals", strings(open.stream().map(item -> item.signalId).toList())));
            liquidation |= current.liquidation;
        }
        if (underWaterStart != Long.MIN_VALUE && !timeline.isEmpty()) maxUnderWater = Math.max(maxUnderWater, timeline.get(timeline.size() - 1) - underWaterStart);

        Set<String> closedIds = new HashSet<>(); for (JsonNode row : closed) closedIds.add(text(row.get("signal_id")));
        ArrayNode accepted = MAPPER.createArrayNode();
        for (Position position : positions) if (closedIds.contains(position.signalId)) accepted.add(position.toJson());
        double maxIntratrade = 0;
        for (Position position : positions) {
            List<ObjectNode> series = positionSeries.getOrDefault(position.signalId, List.of());
            double min = 0; for (ObjectNode row : series) min = Math.min(min, number(row.get("pnl")));
            maxIntratrade = Math.max(maxIntratrade, Math.max(0, -min));
        }
        ArrayNode assets = MAPPER.createArrayNode(); marks.stream().map(mark -> mark.asset).distinct().sorted().forEach(assets::add);
        ArrayNode correlations = authoritativeCorrelations(marks);
        int stressWindowBars = Math.max(3, trunc(number(firstTruthy(policy, "stress_window_bars", "correlation_stress_window_bars", numberNode(12)))));
        String stressSelection = upper(text(firstTruthy(policy, "stress_correlation_selection", textNode("MAX_ABSOLUTE"))));
        if (!(stressSelection.equals("MAX_ABSOLUTE") || stressSelection.equals("MAX_POSITIVE"))) throw error("stress_correlation_selection must be MAX_ABSOLUTE or MAX_POSITIVE");
        applyStressWindows(correlations, stressWindowBars, stressSelection, marks);

        JsonNode standalone = standaloneVolatilityShare(accepted, positionSeries);
        JsonNode covariance = covarianceMrc(accepted, positionSeries, policy);
        if (!positions.isEmpty() && Double.isInfinite(maxMarkGap)) failures.add("UNDECLARED_MARK_GAP_CONTRACT");
        if (finiteNumber(acceptance.get("minimum_accepted_trades")) && accepted.size() < number(acceptance.get("minimum_accepted_trades"))) failures.add("MINIMUM_ACCEPTED_TRADES");
        if (finiteNumber(acceptance.get("maximum_drawdown_pct")) && maxDrawdown * 100 > number(acceptance.get("maximum_drawdown_pct"))) failures.add("MAXIMUM_DRAWDOWN");
        if (finiteNumber(acceptance.get("minimum_net_pnl")) && realizedCash - initialEquity < number(acceptance.get("minimum_net_pnl"))) failures.add("MINIMUM_NET_PNL");
        if (liquidation) failures.add("LIQUIDATION_OR_MARGIN_BREACH");
        int overlap = overlapPositions(positions);
        ObjectNode measured = covariance instanceof ObjectNode cov && "MEASURED".equals(text(cov.get("status")))
                ? measuredCovariance(cov) : (ObjectNode) covariance;
        ObjectNode result = object().put("schema", PORTFOLIO_SCHEMA);
        ObjectNode policyOut = object().put("initial_equity", initialEquity);
        policyOut.set("acceptance", acceptance.deepCopy()); policyOut.put("advanced_risk", strictTrue(policy.get("advanced_risk")));
        putFiniteOrNull(policyOut, "max_mark_gap_ms", maxMarkGap);
        putFiniteOrNull(policyOut, "stress_window_bars", stressWindowBars);
        policyOut.put("stress_correlation_selection", stressSelection).put("event_order", "mark -> funding -> exits -> entries -> mark");
        ArrayNode liquidationEvents = MAPPER.createArrayNode(); for (JsonNode row : rejected) if ("MARGIN_BREACH_LIQUIDATION".equals(text(row.get("reason")))) liquidationEvents.add(row.get("event"));
        result.set("policy", policyOut); result.set("accepted_signals", accepted); result.set("rejected_signals", rejected); result.set("closed_trades", closed);
        result.set("equity_curve", equityCurve); result.put("portfolio_equity", realizedCash).put("net_pnl", realizedCash - initialEquity)
                .put("max_drawdown_pct", maxDrawdown * 100).put("drawdown_basis", "chronological mark-to-market union timeline")
                .put("intratrade_drawdown", maxIntratrade).put("drawdown_duration_ms", maxUnderWater).put("time_under_water_ms", maxUnderWater);
        result.set("liquidation_events", liquidationEvents); result.put("margin_breach", liquidation);
        ObjectNode exposureOut = object().put("peak_gross_notional", peakGross).put("peak_net_notional", peakNet)
                .put("peak_collateral", peakCollateral).put("peak_concurrency", peakConcurrency)
                .put("leverage", accepted.isEmpty() ? 0 : acceptedLeverage(accepted)); result.set("exposure", exposureOut);
        result.set("signal_overlap", object().put("overlapping_entry_count", overlap));
        ObjectNode correlationsOut = object(); correlationsOut.set("contemporaneous", correlations); result.set("correlations", correlationsOut);
        ObjectNode betaOut = object().put("method", "timestamp-aligned return increments"); betaOut.set("assets", assets); result.set("crypto_beta_risk_cluster", betaOut);
        result.set("standalone_pnl_volatility_share", standalone);
        result.set("marginal_risk_contribution", strictTrue(policy.get("advanced_risk")) ? measured
                : object().put("status", "UNAVAILABLE_DIAGNOSTIC").put("reason", "TIMESTAMP_ALIGNED_COVARIANCE_MRC_NOT_IMPLEMENTED"));
        result.put("acceptance_contract_sha256", JsonHashes.canonicalSha256(acceptance)).put("pass", uniqueStrings(failures).isEmpty());
        result.set("failures", uniqueStrings(failures)); result.put("activation", "RESEARCH_ONLY");
        return result;
    }

    private static MarkResult processMarkedEquity(long time, List<Position> open, Map<String, List<Mark>> marksByKey,
            Map<String, Mark> lastMarks, double maxGap, Map<String, List<ObjectNode>> series,
            double realizedCash, ArrayNode rejected, List<String> failures, Set<String> coverageFailures) {
        double unrealized = 0; List<Position> cross = new ArrayList<>(); double crossMaintenance = 0; boolean liquidation = false;
        for (Position position : open) {
            Mark mark = exactMark(marksByKey, position.key, time);
            if (mark == null) mark = lastMarks.get(position.key);
            if (mark == null || time - mark.time > maxGap) {
                String code = position.signalId + "|" + time;
                if (coverageFailures.add(code)) {
                    rejected.add(object().put("signal_id", position.signalId).put("reason", "MARK_GAP_EXCEEDED")
                            .set("event", object().put("signal_id", position.signalId).put("time", time).set("max_mark_gap_ms", finiteOrNull(maxGap))));
                    failures.add("MISSING_MARK_PATH");
                }
                continue;
            }
            double markedNotional = Math.abs(mark.price * position.quantity * position.multiplier);
            double pnl = position.direction.equals("long")
                    ? (mark.price - position.entry) * position.quantity * position.multiplier
                    : (position.entry - mark.price) * position.quantity * position.multiplier;
            position.markPrice = mark.price; position.markedNotional = markedNotional; position.marked = true; position.unrealized = pnl;
            List<ObjectNode> points = series.computeIfAbsent(position.signalId, ignored -> new ArrayList<>());
            if (points.isEmpty() || number(points.get(points.size() - 1).get("time")) != time) points.add(object().put("time", time).put("pnl", pnl));
            unrealized += pnl; position.maintenanceRequirement = markedNotional * position.maintenanceRatio;
            if (!position.type.equals("spot") && position.marginMode.equals("cross")) { cross.add(position); crossMaintenance += position.maintenanceRequirement; }
            else if (!position.type.equals("spot")) {
                double marginEquity = position.collateral + pnl + position.fundingPnl;
                if (marginEquity <= position.maintenanceRequirement) {
                    rejected.add(object().put("signal_id", position.signalId).put("reason", "MARGIN_BREACH_LIQUIDATION")
                            .set("event", object().put("signal_id", position.signalId).put("time", time).put("equity", marginEquity)
                                    .put("maintenance_requirement", position.maintenanceRequirement).put("marked_notional", markedNotional)
                                    .put("mark_price", mark.price).put("margin_mode", position.marginMode)));
                    failures.add("MARGIN_BREACH_LIQUIDATION"); liquidation = true;
                }
            }
        }
        double crossEquity = realizedCash + unrealized;
        if (!cross.isEmpty() && crossEquity <= crossMaintenance) {
            List<String> ids = cross.stream().map(p -> p.signalId).sorted().toList();
            for (Position position : cross) if (!position.crossLiquidated) {
                position.crossLiquidated = true;
                rejected.add(object().put("signal_id", position.signalId).put("reason", "MARGIN_BREACH_LIQUIDATION")
                        .set("event", object().put("signal_id", position.signalId).put("time", time).put("equity", crossEquity)
                                .put("maintenance_requirement", crossMaintenance).put("marked_notional", position.markedNotional)
                                .put("mark_price", position.markPrice).put("margin_mode", "cross").set("aggregate_cross_positions", strings(ids))));
                failures.add("MARGIN_BREACH_LIQUIDATION");
            }
            liquidation = true;
        }
        return new MarkResult(unrealized, realizedCash + unrealized, liquidation);
    }

    private static String capReason(Position position, List<Position> open, Limits limits, double availableEquity) {
        Exposure next = authoritativeExposure(open); int sameAsset = (int) open.stream().filter(item -> item.asset.equals(position.asset)).count();
        Double venueCap = numericOrNull(limits.venueCaps.get(position.venue)); Double clusterCap = numericOrNull(limits.clusterCaps.get(position.cluster));
        if (open.size() + 1 > limits.totalConcurrency) return "TOTAL_CONCURRENCY_CAP";
        if (sameAsset + 1 > limits.perAssetConcurrency) return "PER_ASSET_CONCURRENCY_CAP";
        if (next.gross + position.notional > limits.grossExposureCap) return "GROSS_EXPOSURE_CAP";
        if (Math.abs(next.net + (position.direction.equals("short") ? -position.notional : position.notional)) > limits.netExposureCap) return "NET_EXPOSURE_CAP";
        if (next.collateral + position.collateral > limits.collateralCap) return "COLLATERAL_CAP";
        if (next.collateral + position.collateral > availableEquity) return "AVAILABLE_EQUITY_CAP";
        if (position.leverage > limits.leverageCap) return "LEVERAGE_CAP";
        if (venueCap != null && open.stream().filter(item -> item.venue.equals(position.venue)).count() + 1 > venueCap) return "VENUE_CAP";
        if (clusterCap != null && open.stream().filter(item -> item.cluster.equals(position.cluster)).count() + 1 > clusterCap) return "CLUSTER_CAP";
        return null;
    }

    private static Position firstPosition(List<Position> positions, String id) {
        return positions.stream().filter(position -> position.signalId.equals(id)).findFirst().orElse(null);
    }

    private static ArrayList<Mark> normalizeMarks(ObjectNode policy, boolean strictMessage) {
        ArrayList<Mark> result = new ArrayList<>();
        JsonNode raw = policy.get("marks");
        if (raw == null || !raw.isArray()) return result;
        int index = 0;
        for (JsonNode row : raw) {
            long time = markTime(firstTruthy(row, "time", "timestamp"), "marks[" + index + "].time");
            double price = number(firstNullish(row, "price", "close"));
            String asset = lower(firstTruthy(row, "asset", "symbol"));
            if (strictMessage && (asset.isEmpty() || !(price > 0))) {
                throw error("marks[" + index + "] requires asset and positive price");
            }
            result.add(new Mark(asset, time, price, markKey(asset, row), (ObjectNode) row.deepCopy()));
            index++;
        }
        result.sort(Comparator.comparingLong((Mark mark) -> mark.time).thenComparing(mark -> mark.asset)
                .thenComparing(mark -> text(mark.raw.get("symbol"))));
        return result;
    }

    private static String linearInstrument(JsonNode instrument, String name) {
        validatePortfolioInstrument(instrument, name);
        String type = lower(firstTruthy(instrument, "instrument_type", "type"));
        if (!LINEAR_TYPES.contains(type)) throw error(name + " is unsupported by the linear mark-to-market adapter: " + type);
        return type;
    }

    private static Mark exactMark(Map<String, ? extends List<Mark>> marksByKey, String key, long time) {
        List<Mark> exact = marksByKey.get(key);
        if (exact != null) for (Mark mark : exact) if (mark.time == time) return mark;
        String[] parts = key.split("\\|", -1);
        if (parts.length > 1 && parts[1].isEmpty()) {
            for (List<Mark> marks : marksByKey.values()) for (Mark mark : marks) {
                if (mark.time == time && mark.asset.equals(parts[0]) && !truthy(mark.raw.get("venue"))
                        && !truthy(mark.raw.get("exchange"))) return mark;
            }
        }
        return null;
    }

    private static String markKey(String asset, JsonNode instrument) {
        String a = asset;
        if (!truthyText(a)) a = text(firstTruthy(instrument, "asset", "symbol"));
        String venue = text(firstTruthy(instrument, "venue", "exchange"));
        String symbol = text(firstTruthy(instrument, "symbol", "instrument_id", textNode(a)));
        return lower(a) + "|" + venue + "|" + lower(symbol);
    }

    private static long markTime(JsonNode value, String name) {
        if (value == null || value.isNull() || value.isMissingNode()) throw error(name + " must be a valid timestamp");
        if (value.isNumber()) {
            double millis = number(value);
            if (!Double.isFinite(millis)) throw error(name + " must be a valid timestamp");
            return (long) millis;
        }
        String raw = text(value);
        if (raw.isEmpty()) throw error(name + " must be a valid timestamp");
        try { return Instant.parse(raw).toEpochMilli(); }
        catch (DateTimeParseException ignored) {}
        try { return OffsetDateTime.parse(raw).toInstant().toEpochMilli(); }
        catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli(); }
        catch (DateTimeParseException ignored) {}
        try { return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); }
        catch (DateTimeParseException ignored) {}
        throw error(name + " must be a valid timestamp");
    }

    private static long signalTime(JsonNode signal) {
        JsonNode value = firstTruthy(signal, "entry_time", "signal_time", "time", numberNode(0));
        try { return markTime(value, "signal time"); } catch (RuntimeException ignored) { return 0; }
    }

    private static long closeTime(JsonNode signal) {
        JsonNode value = firstTruthy(signal, "exit_time", "close_time", numberNode(0));
        try { return markTime(value, "signal close time"); } catch (RuntimeException ignored) { return 0; }
    }

    private static List<JsonNode> sortedSignals(ArrayNode values) {
        List<JsonNode> result = new ArrayList<>(); values.forEach(value -> result.add(value));
        result.sort(Comparator.comparingLong(StrategyPortfolioV5::signalTime)
                .thenComparing((left, right) -> Double.compare(number(right.get("priority")), number(left.get("priority"))))
                .thenComparing(StrategyPortfolioV5::keyFor)
                .thenComparing(value -> text(value.get("candidate_id"))));
        return result;
    }

    private static String keyFor(JsonNode signal) {
        JsonNode instrument = objectOrSelf(signal.get("instrument"), signal);
        return text(firstTruthy(signal, "strategy_id", "strategy")) + "|"
                + lower(firstTruthy(signal, "asset", from(instrument, "asset"), from(instrument, "symbol"))) + "|"
                + text(firstTruthy(instrument, "instrument_id", "symbol", "type")) + "|"
                + text(firstTruthy(instrument, "venue", "exchange"));
    }

    private static String rejectId(JsonNode signal) {
        String id = text(firstTruthy(signal, "signal_id", "candidate_id")); return id.isEmpty() ? keyFor(signal) : id;
    }

    private static ObjectNode rejectSimple(String id, String asset, String reason) {
        return object().put("signal_id", id).put("asset", asset).put("reason", reason);
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static JsonNode numberNode(double value) { return MAPPER.getNodeFactory().numberNode(value); }
    private static JsonNode textNode(String value) { return MAPPER.getNodeFactory().textNode(value); }

    private static ObjectNode objectOrEmpty(JsonNode value) {
        return value != null && value.isObject() ? (ObjectNode) value : object();
    }

    private static JsonNode objectOrSelf(JsonNode preferred, JsonNode fallback) { return truthy(preferred) ? preferred : fallback; }

    private static ArrayNode arrayOrEmpty(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : MAPPER.createArrayNode();
    }

    /** JavaScript's nullish coalescing over object fields and literal nodes. */
    private static JsonNode firstNullish(JsonNode source, Object... candidates) {
        for (Object candidate : candidates) {
            JsonNode value = candidate instanceof String name ? (source == null ? null : source.get(name)) : (JsonNode) candidate;
            if (value != null && !value.isNull() && !value.isMissingNode()) return value;
        }
        return null;
    }

    /** JavaScript's || over object fields and literal nodes. */
    private static JsonNode firstTruthy(JsonNode source, Object... candidates) {
        for (Object candidate : candidates) {
            JsonNode value = candidate instanceof String name ? (source == null ? null : source.get(name)) : (JsonNode) candidate;
            if (truthy(value)) return value;
        }
        return null;
    }

    private static JsonNode from(JsonNode source, String field) { return source == null ? null : source.get(field); }

    private static boolean hasAny(JsonNode source, String... fields) { for (String field : fields) if (source.has(field)) return true; return false; }

    private static boolean truthy(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return false;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue());
        if (value.isTextual()) return !value.textValue().isEmpty();
        return true;
    }

    private static boolean bool(JsonNode value) { return truthy(value); }
    private static boolean strictTrue(JsonNode value) { return value != null && value.isBoolean() && value.booleanValue(); }

    private static boolean truthyText(String value) { return value != null && !value.isEmpty(); }

    private static String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "";
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean()) return value.booleanValue() ? "true" : "false";
        if (value.isNumber()) return value.asText();
        return value.toString();
    }

    private static String lower(JsonNode value) { return text(value).toLowerCase(Locale.ROOT); }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private static String upper(String value) { return value.toUpperCase(Locale.ROOT); }

    /** A close approximation of Number(value) for JSON values, including empty-string zero. */
    private static double number(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return 0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isBoolean()) return value.booleanValue() ? 1 : 0;
        if (value.isTextual()) {
            String raw = value.textValue().trim(); if (raw.isEmpty()) return 0;
            try { return Double.parseDouble(raw); } catch (NumberFormatException ignored) {
                try { if (raw.startsWith("0x") || raw.startsWith("0X")) return Long.parseLong(raw.substring(2), 16); }
                catch (NumberFormatException ignoredAgain) {}
                return Double.NaN;
            }
        }
        if (value.isArray() && value.size() == 0) return 0;
        if (value.isArray() && value.size() == 1) return number(value.get(0));
        return Double.NaN;
    }

    private static double numberOrInfinity(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return Double.POSITIVE_INFINITY;
        return number(value);
    }

    private static double finiteOrZero(JsonNode value) { double result = number(value); return Double.isFinite(result) ? result : 0; }
    private static boolean finiteNumber(JsonNode value) {
        // A missing Java field represents JavaScript `undefined` (Number(undefined) is NaN),
        // while an explicit JSON null still follows Number(null) === 0.
        return value != null && !value.isMissingNode() && Double.isFinite(number(value));
    }
    private static Double numericOrNull(JsonNode value) { if (value == null || value.isNull() || value.isMissingNode()) return null; double result = number(value); return Double.isFinite(result) ? result : null; }
    private static int trunc(double value) { return Double.isFinite(value) ? (int) value : 0; }
    private static IllegalArgumentException error(String message) { return new IllegalArgumentException(message); }

    private static JsonNode finiteOrNull(double value) { return Double.isFinite(value) ? numberNode(value) : NullNode.instance; }
    private static void putFiniteOrNull(ObjectNode target, String key, double value) { target.set(key, finiteOrNull(value)); }

    private static ArrayNode strings(List<String> values) { ArrayNode result = MAPPER.createArrayNode(); values.forEach(result::add); return result; }
    private static ArrayNode uniqueStrings(List<String> values) { return strings(new ArrayList<>(new LinkedHashSet<>(values))); }

    private static ArrayNode sortCurve(ArrayNode curve) {
        List<JsonNode> rows = new ArrayList<>(); curve.forEach(rows::add);
        rows.sort(Comparator.comparingLong(row -> row.has("time") && !row.get("time").isNull() ? row.get("time").asLong() : Long.MIN_VALUE));
        ArrayNode result = MAPPER.createArrayNode(); rows.forEach(result::add); return result;
    }

    private static ArrayNode sortClosed(ArrayNode rows) {
        List<JsonNode> values = new ArrayList<>(); rows.forEach(values::add);
        values.sort(Comparator.comparingLong((JsonNode row) -> row.get("exit_time").asLong()).thenComparing((JsonNode row) -> text(row.get("signal_id"))));
        ArrayNode result = MAPPER.createArrayNode(); values.forEach(result::add); return result;
    }

    private static Double priceAt(Map<String, List<Mark>> byAsset, String asset, long time, boolean exact) {
        List<Mark> series = byAsset.getOrDefault(asset, List.of());
        Mark result = null;
        if (exact) {
            for (Mark mark : series) if (mark.time == time) { result = mark; break; }
        } else {
            for (int index = series.size() - 1; index >= 0; index--) if (series.get(index).time <= time) { result = series.get(index); break; }
        }
        return result == null ? null : result.price;
    }

    private static double intratradeLegacy(ArrayNode accepted, Map<String, List<Mark>> byAsset) {
        double result = 0;
        for (JsonNode position : accepted) {
            Double mark = priceAt(byAsset, text(position.get("asset")), position.path("entryTime").asLong(), false);
            double basis = mark == null ? number(position.get("entry")) : mark;
            double adverse = text(position.get("direction")).equals("long")
                    ? number(position.get("entry")) - basis : basis - number(position.get("entry"));
            result = Math.max(result, Math.max(0, number(position.get("collateral")) + adverse * number(position.get("quantity"))));
        }
        return result;
    }

    private static double maxNotional(List<ObjectNode> positions) { double result = 0; for (JsonNode p : positions) result = Math.max(result, number(p.get("quantity")) * number(p.get("entry"))); return result; }
    private static double maxCollateral(List<ObjectNode> positions) { double result = 0; for (JsonNode p : positions) result = Math.max(result, number(p.get("collateral"))); return result; }
    private static double maxLeverage(List<ObjectNode> positions) { double result = 0; for (JsonNode p : positions) result = Math.max(result, number(p.get("leverage"))); return result; }
    private static double fundingTotal(ArrayNode closed) { double result = 0; for (JsonNode row : closed) result += number(row.get("funding_pnl")); return result; }

    private static ArrayNode copyWithCorrelation(ArrayNode rows) {
        ArrayNode result = MAPPER.createArrayNode();
        for (JsonNode row : rows) { ObjectNode copy = row.deepCopy(); copy.set("correlation", row.get("contemporaneous_return_correlation").deepCopy()); result.add(copy); }
        return result;
    }

    private static int overlapCount(ArrayNode signals) {
        int count = 0;
        for (int i = 0; i < signals.size(); i++) for (int j = i + 1; j < signals.size(); j++) {
            JsonNode left = signals.get(i), right = signals.get(j);
            String la = lower(firstTruthy(left, "asset", from(objectOrSelf(left.get("instrument"), left), "asset")));
            String ra = lower(firstTruthy(right, "asset", from(objectOrSelf(right.get("instrument"), right), "asset")));
            if (la.equals(ra) && signalTime(left) < closeTime(right) && signalTime(right) < closeTime(left)) count++;
        }
        return count;
    }

    private static int overlapPositions(List<Position> positions) {
        int count = 0;
        for (int i = 0; i < positions.size(); i++) for (int j = i + 1; j < positions.size(); j++) {
            Position left = positions.get(i), right = positions.get(j);
            if (left.asset.equals(right.asset) && left.entryTime < right.exitTime && right.entryTime < left.exitTime) count++;
        }
        return count;
    }

    private static double acceptedLeverage(ArrayNode accepted) {
        double result = 0; for (JsonNode row : accepted) result = Math.max(result, number(row.get("leverage"))); return result;
    }

    private static ArrayNode correlationRows(Map<String, List<Double>> values) {
        List<String> assets = values.keySet().stream().sorted().toList(); ArrayNode result = MAPPER.createArrayNode();
        for (int i = 0; i < assets.size(); i++) for (int j = i + 1; j < assets.size(); j++) {
            ObjectNode row = object().put("left", assets.get(i)).put("right", assets.get(j));
            Double correlation = pearson(values.get(assets.get(i)), values.get(assets.get(j)));
            row.set("contemporaneous_return_correlation", correlation == null ? NullNode.instance : numberNode(correlation)); result.add(row);
        }
        return result;
    }

    private static Double pearson(List<Double> left, List<Double> right) {
        if (left.size() < 3 || left.size() != right.size()) return null;
        double lm = left.stream().mapToDouble(Double::doubleValue).average().orElse(0), rm = right.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double lsd = 0, rsd = 0, cross = 0;
        for (int i = 0; i < left.size(); i++) { lsd += Math.pow(left.get(i) - lm, 2); rsd += Math.pow(right.get(i) - rm, 2); cross += (left.get(i) - lm) * (right.get(i) - rm); }
        return lsd > 0 && rsd > 0 ? cross / Math.sqrt(lsd) / Math.sqrt(rsd) : null;
    }

    private static ArrayNode authoritativeCorrelations(List<Mark> marks) {
        Map<String, List<Mark>> byAsset = new HashMap<>(); for (Mark mark : marks) byAsset.computeIfAbsent(mark.asset, ignored -> new ArrayList<>()).add(mark);
        List<String> assets = byAsset.keySet().stream().sorted().toList(); ArrayNode result = MAPPER.createArrayNode();
        for (int i = 0; i < assets.size(); i++) for (int j = i + 1; j < assets.size(); j++) {
            Map<Long, Double> left = returns(byAsset.get(assets.get(i))), right = returns(byAsset.get(assets.get(j)));
            List<Long> common = left.keySet().stream().filter(right::containsKey).sorted().toList();
            List<Double> lv = common.stream().map(left::get).toList(), rv = common.stream().map(right::get).toList();
            Double correlation = pearson(lv, rv);
            result.add(object().put("left", assets.get(i)).put("right", assets.get(j)).put("common_timestamps", common.size())
                    .set("contemporaneous_return_correlation", correlation == null ? NullNode.instance : numberNode(correlation)));
        }
        return result;
    }

    private static Map<Long, Double> returns(List<Mark> series) {
        Map<Long, Double> result = new HashMap<>();
        for (int i = 1; i < series.size(); i++) result.put(series.get(i).time, series.get(i).price / series.get(i - 1).price - 1);
        return result;
    }

    private static void applyStressWindows(ArrayNode rows, int window, String selection, List<Mark> marks) {
        Map<String, List<Mark>> byAsset = new HashMap<>(); for (Mark mark : marks) byAsset.computeIfAbsent(mark.asset, ignored -> new ArrayList<>()).add(mark);
        for (JsonNode item : rows) {
            ObjectNode row = (ObjectNode) item; String leftName = text(row.get("left")), rightName = text(row.get("right"));
            Map<Long, Double> left = returns(byAsset.getOrDefault(leftName, List.of())), right = returns(byAsset.getOrDefault(rightName, List.of()));
            List<Long> common = left.keySet().stream().filter(right::containsKey).sorted().toList();
            List<Double> lv = common.stream().map(left::get).toList(), rv = common.stream().map(right::get).toList();
            ObjectNode worst = null;
            for (int start = 0; start + window <= common.size(); start++) {
                int end = start + window; Double correlation = pearson(lv.subList(start, end), rv.subList(start, end)); if (correlation == null) continue;
                ObjectNode candidate = object().put("correlation", correlation).put("start_time", common.get(start)).put("end_time", common.get(end - 1)).put("bars", window);
                if (worst == null || (selection.equals("MAX_POSITIVE") ? correlation > number(worst.get("correlation")) : Math.abs(correlation) > Math.abs(number(worst.get("correlation"))))) worst = candidate;
            }
            if (worst == null) worst = object().put("status", "UNAVAILABLE_DIAGNOSTIC").put("reason", "INSUFFICIENT_ALIGNED_STRESS_WINDOW").put("required_bars", window);
            row.set("stressed_worst_window", worst); row.put("stressed_worst_window_selection", selection).put("status", row.get("contemporaneous_return_correlation").isNull() ? "UNAVAILABLE_DIAGNOSTIC" : "MEASURED");
        }
    }

    private static JsonNode standaloneVolatilityShare(ArrayNode accepted, Map<String, List<ObjectNode>> positionSeries) {
        List<RiskWeight> weights = new ArrayList<>();
        for (JsonNode row : accepted) {
            List<ObjectNode> series = new ArrayList<>(positionSeries.getOrDefault(text(row.get("signal_id")), List.of())); series.sort(Comparator.comparingLong(point -> point.get("time").asLong()));
            List<Double> values = new ArrayList<>(); double previous = 0; for (ObjectNode point : series) { values.add(number(point.get("pnl")) - previous); previous = number(point.get("pnl")); }
            Double contribution = null;
            if (values.size() > 1) { double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0), sum = 0; for (double value : values) sum += Math.pow(value - mean, 2); contribution = Math.abs(Math.sqrt(sum / (values.size() - 1))); }
            weights.add(new RiskWeight((ObjectNode) row, contribution));
        }
        double total = weights.stream().mapToDouble(item -> item.contribution == null ? 0 : item.contribution).sum();
        if (!(total > 0)) return object().put("status", "UNAVAILABLE_DIAGNOSTIC").put("reason", "INSUFFICIENT_COMMON_MARKS_FOR_MARGINAL_RISK");
        ArrayNode result = MAPPER.createArrayNode(); for (RiskWeight weight : weights) result.add(object().put("signal_id", text(weight.position.get("signal_id"))).put("asset", text(weight.position.get("asset"))).put("approximate_share", weight.contribution / total).put("method", "timestamp-aligned-pnl-increment-volatility")); return result;
    }

    private static JsonNode covarianceMrc(ArrayNode accepted, Map<String, List<ObjectNode>> positionSeries, ObjectNode policy) {
        ObjectNode unavailable = object().put("status", "UNAVAILABLE_DIAGNOSTIC").put("reason", "INSUFFICIENT_ALIGNED_MARKS");
        if (!strictTrue(policy.get("advanced_risk")) || accepted.isEmpty()) return unavailable;
        List<Map<Long, Double>> maps = new ArrayList<>();
        for (JsonNode position : accepted) {
            List<ObjectNode> series = new ArrayList<>(positionSeries.getOrDefault(text(position.get("signal_id")), List.of())); series.sort(Comparator.comparingLong(row -> row.get("time").asLong()));
            Map<Long, Double> increments = new HashMap<>(); double previous = 0, scale = Math.max(number(position.get("notional")), 1e-12);
            for (ObjectNode row : series) { double pnl = number(row.get("pnl")); increments.put(row.get("time").asLong(), (pnl - previous) / scale); previous = pnl; }
            maps.add(increments);
        }
        Set<Long> commonSet = new HashSet<>(maps.get(0).keySet()); for (Map<Long, Double> map : maps.subList(1, maps.size())) commonSet.retainAll(map.keySet());
        List<Long> common = commonSet.stream().sorted().toList(); if (common.size() < 3) return unavailable;
        double[][] matrix = new double[maps.size()][common.size()]; double[] means = new double[maps.size()];
        for (int i = 0; i < maps.size(); i++) { for (int j = 0; j < common.size(); j++) matrix[i][j] = maps.get(i).getOrDefault(common.get(j), 0d); means[i] = average(matrix[i]); }
        ArrayNode covariance = MAPPER.createArrayNode(); double[][] cov = new double[maps.size()][maps.size()];
        for (int i = 0; i < maps.size(); i++) { ArrayNode row = covariance.addArray(); for (int j = 0; j < maps.size(); j++) { double sum = 0; for (int k = 0; k < common.size(); k++) sum += (matrix[i][k] - means[i]) * (matrix[j][k] - means[j]); cov[i][j] = sum / (common.size() - 1); row.add(cov[i][j]); } }
        double[] weights = new double[accepted.size()]; double total = 0; for (int i = 0; i < weights.length; i++) { weights[i] = Math.abs(number(accepted.get(i).get("notional"))); total += weights[i]; } if (!(total > 0)) total = 1;
        for (int i = 0; i < weights.length; i++) weights[i] /= total;
        double[] sigmaW = new double[weights.length]; for (int i = 0; i < weights.length; i++) for (int j = 0; j < weights.length; j++) sigmaW[i] += cov[i][j] * weights[j];
        double variance = 0; for (int i = 0; i < weights.length; i++) variance += weights[i] * sigmaW[i]; double portfolioVol = Math.sqrt(Math.max(0, variance));
        double[] contributions = new double[weights.length]; double sumAbs = 0; for (int i = 0; i < weights.length; i++) { contributions[i] = weights[i] * sigmaW[i]; sumAbs += Math.abs(contributions[i]); } if (!(sumAbs > 0)) sumAbs = 1;
        ArrayNode contributionRows = MAPPER.createArrayNode();
        for (int i = 0; i < accepted.size(); i++) contributionRows.add(object().put("signal_id", text(accepted.get(i).get("signal_id"))).put("asset", text(accepted.get(i).get("asset"))).put("weight", weights[i]).put("marginal_contribution", sigmaW[i] / Math.max(portfolioVol, 1e-12)).put("risk_contribution", contributions[i] / Math.max(portfolioVol, 1e-12)).put("absolute_share", Math.abs(contributions[i]) / sumAbs));
        ObjectNode result = object().put("status", "MEASURED").put("method", "timestamp-aligned-asset-return-sample-covariance-with-capital-weights").put("common_timestamps", common.size()); result.set("covariance", covariance); result.put("portfolio_volatility", portfolioVol); result.set("contributions", contributionRows); return result;
    }

    private static ObjectNode measuredCovariance(ObjectNode covariance) {
        ObjectNode result = covariance.deepCopy(); ArrayNode marginal = result.putArray("marginal_contributions"), components = result.putArray("component_contributions"); double sum = 0;
        for (JsonNode row : covariance.path("contributions")) { ObjectNode m = row.deepCopy(); m.set("value", row.get("marginal_contribution").deepCopy()); marginal.add(m); ObjectNode c = row.deepCopy(); c.set("value", row.get("risk_contribution").deepCopy()); components.add(c); sum += number(row.get("risk_contribution")); }
        result.put("component_sum", sum).put("component_sum_matches_portfolio", Math.abs(sum - number(covariance.get("portfolio_volatility"))) < 1e-9); return result;
    }

    private static double average(double[] values) { double result = 0; for (double value : values) result += value; return values.length == 0 ? 0 : result / values.length; }

    private static double releaseLegacy(List<PositionSimple> open, long time, ArrayNode closed, ArrayNode equity, double current) {
        List<PositionSimple> closing = open.stream().filter(position -> position.closed <= time)
                .sorted(Comparator.comparingLong((PositionSimple position) -> position.closed).thenComparing(position -> position.signalId)).toList();
        int index = 0; double peak = current;
        for (JsonNode row : equity) peak = Math.max(peak, number(row.get("equity")));
        while (index < closing.size()) {
            long closedAt = closing.get(index).closed; List<PositionSimple> group = new ArrayList<>();
            while (index < closing.size() && closing.get(index).closed == closedAt) group.add(closing.get(index++));
            double groupPnl = 0;
            for (PositionSimple position : group) {
                double pnl = position.pnl(); groupPnl += pnl;
                ObjectNode row = object().put("signal_id", position.signalId).put("asset", position.asset).put("exit_time", position.closed);
                Double netR = position.netR(), risk = position.riskAmount(); if (netR == null) row.putNull("net_r"); else row.put("net_r", netR); if (risk == null) row.putNull("risk_amount"); else row.put("risk_amount", risk);
                row.put("net_pnl", pnl).put("funding_pnl", number(firstNullish(position.signal, "funding_pnl"))); closed.add(row);
            }
            current += groupPnl; peak = Math.max(peak, current); double drawdown = peak > 0 ? (peak - current) / peak : 0; equity.add(object().put("time", closedAt).put("equity", current).put("drawdown_pct", drawdown * 100));
        }
        Set<String> ids = new HashSet<>(); closing.forEach(position -> ids.add(position.signalId)); open.removeIf(position -> ids.contains(position.signalId));
        return current;
    }

    private static Exposure authoritativeExposure(List<Position> positions) { double gross = 0, net = 0, collateral = 0; for (Position p : positions) { double value = Double.isFinite(p.markedNotional) ? p.markedNotional : p.notional; gross += value; net += p.direction.equals("short") ? -value : value; collateral += p.collateral; } return new Exposure(gross, net, collateral); }
    private static ExposureSimple exposure(List<PositionSimple> positions) { double gross = 0, net = 0, collateral = 0; for (PositionSimple p : positions) { gross += p.notional; net += p.direction.equals("short") ? -p.notional : p.notional; collateral += p.collateral; } return new ExposureSimple(gross, net, collateral); }

    private static double numberOrDefault(JsonNode value, double fallback) { double result = number(value); return Double.isFinite(result) ? result : fallback; }

    private static final class Mark {
        final String asset; final long time; final double price; final String key; final ObjectNode raw;
        Mark(String asset, long time, double price, String key, ObjectNode raw) { this.asset = asset; this.time = time; this.price = price; this.key = key; this.raw = raw; }
    }

    private static final class FundingEvent {
        final String signalId, eventId, source, venue, instrument, identityStatus; final double amount;
        FundingEvent(String signalId, double amount, String eventId, String source, String venue, String instrument, String identityStatus) { this.signalId = signalId; this.amount = amount; this.eventId = eventId; this.source = source; this.venue = venue; this.instrument = instrument; this.identityStatus = identityStatus; }
    }

    private static final class MarkResult {
        final double unrealized, equity; final boolean liquidation;
        MarkResult(double unrealized, double equity, boolean liquidation) { this.unrealized = unrealized; this.equity = equity; this.liquidation = liquidation; }
    }

    private static final class Exposure { final double gross, net, collateral; Exposure(double gross, double net, double collateral) { this.gross = gross; this.net = net; this.collateral = collateral; } }

    private static final class RiskWeight { final ObjectNode position; final Double contribution; RiskWeight(ObjectNode position, Double contribution) { this.position = position; this.contribution = contribution; } }

    private static final class Position {
        final ObjectNode signal, instrument; final ArrayNode funding; final String signalId, asset, key, direction, type, venue, symbol, marginMode, collateralCurrency, cluster; final double entry, exit, entryMark, exitMark, quantity, multiplier, notional, collateral, leverage, maintenanceRatio, fees; final long entryTime, exitTime; final Set<String> fundingEventIds = new HashSet<>();
        double realized, unrealized, fundingPnl, markPrice, markedNotional, maintenanceRequirement; boolean crossLiquidated, closed, marked;
        Position(ObjectNode signal, String signalId, String asset, String key, String direction, String type, String venue, String symbol, ObjectNode instrument, double entry, double exit, double entryMark, double exitMark, double quantity, double multiplier, double notional, double collateral, double leverage, String marginMode, String collateralCurrency, double maintenanceRatio, long entryTime, long exitTime, double fees, String cluster, ArrayNode funding) {
            this.signal = signal; this.signalId = signalId; this.asset = asset; this.key = key; this.direction = direction; this.type = type; this.venue = venue; this.symbol = symbol; this.instrument = instrument; this.entry = entry; this.exit = exit; this.entryMark = entryMark; this.exitMark = exitMark; this.quantity = quantity; this.multiplier = multiplier; this.notional = notional; this.collateral = collateral; this.leverage = leverage; this.marginMode = marginMode; this.collateralCurrency = collateralCurrency; this.maintenanceRatio = maintenanceRatio; this.entryTime = entryTime; this.exitTime = exitTime; this.fees = fees; this.cluster = cluster; this.funding = funding; }
        ObjectNode toJson() {
            ObjectNode result = object(); result.set("signal", signal.deepCopy()); result.put("signal_id", signalId).put("asset", asset).put("key", key).put("direction", direction).put("type", type).put("venue", venue).put("symbol", symbol); result.set("instrument", instrument.deepCopy());
            result.put("entry", entry).put("exit", exit).put("entryMark", entryMark).put("exitMark", exitMark).put("quantity", quantity).put("multiplier", multiplier).put("notional", notional).put("collateral", collateral).put("leverage", leverage).put("marginMode", marginMode).put("collateralCurrency", collateralCurrency).put("maintenanceRatio", maintenanceRatio).put("entryTime", entryTime).put("exitTime", exitTime).put("fees", fees).put("cluster", cluster); result.set("funding", funding.deepCopy()); result.putNull("realized").put("unrealized", unrealized).put("fundingPnl", fundingPnl).set("fundingEventIds", object());
            if (closed) result.put("realized", realized);
            if (marked) { result.put("mark_price", markPrice); result.put("marked_notional", markedNotional); result.put("maintenance_requirement", maintenanceRequirement); } if (crossLiquidated) result.put("crossLiquidated", true);
            return result;
        }
    }

    private static final class PositionSimple {
        final ObjectNode signal, instrument; final String signalId, asset, direction, cluster, venue; final double notional, collateral; final long opened, closed;
        PositionSimple(ObjectNode signal, String signalId, String asset, String direction, String cluster, String venue, double notional, double collateral, long opened, long closed, ObjectNode instrument) {
            this.signal = signal; this.signalId = signalId; this.asset = asset; this.direction = direction; this.cluster = cluster; this.venue = venue; this.notional = notional; this.collateral = collateral; this.opened = opened; this.closed = closed; this.instrument = instrument;
        }
        Double netR() { JsonNode value = firstNullish(signal, "net_r", "return_r", "r"); return finiteNumberOrMissing(value) ? number(value) : null; }
        Double riskAmount() { JsonNode value = signal.get("risk_amount"); return finiteNumberOrMissing(value) ? number(value) : null; }
        double pnl() { JsonNode explicit = signal.get("net_pnl"); return finiteNumber(explicit) ? number(explicit) : netR() * riskAmount(); }
        ObjectNode toJson() { ObjectNode result = object().put("signal_id", signalId); result.set("signal", signal.deepCopy()); result.put("asset", asset).put("direction", direction).put("cluster", cluster).put("venue", venue).put("notional", notional).put("collateral", collateral).put("opened", opened).put("closed", closed); result.set("instrument", instrument.deepCopy()); return result; }
    }

    private static final class ExposureSimple { final double gross, net, collateral; ExposureSimple(double gross, double net, double collateral) { this.gross = gross; this.net = net; this.collateral = collateral; } }

    private static final class Limits {
        final double totalConcurrency, perAssetConcurrency, grossExposureCap, netExposureCap, collateralCap, leverageCap;
        final Map<String, JsonNode> clusterCaps, venueCaps; final ObjectNode raw;
        Limits(double totalConcurrency, double perAssetConcurrency, double grossExposureCap, double netExposureCap, double collateralCap, double leverageCap, Map<String, JsonNode> clusterCaps, Map<String, JsonNode> venueCaps, ObjectNode raw) {
            this.totalConcurrency = totalConcurrency; this.perAssetConcurrency = perAssetConcurrency; this.grossExposureCap = grossExposureCap; this.netExposureCap = netExposureCap; this.collateralCap = collateralCap; this.leverageCap = leverageCap; this.clusterCaps = clusterCaps; this.venueCaps = venueCaps; this.raw = raw;
        }
        static Limits authoritative(ObjectNode policy) {
            return new Limits(numberOrInfinity(firstNullish(policy, "total_concurrency", "max_concurrent")), numberOrInfinity(policy.get("per_asset_concurrency")), numberOrInfinity(policy.get("gross_exposure_cap")), numberOrInfinity(policy.get("net_exposure_cap")), numberOrInfinity(policy.get("collateral_cap")), numberOrInfinity(policy.get("leverage_cap")), map(policy.get("cluster_caps") != null && truthy(policy.get("cluster_caps")) ? policy.get("cluster_caps") : policy.get("risk_strategy_cluster_caps")), map(policy.get("venue_caps")), object());
        }
        static Limits legacy(ObjectNode policy, double initialEquity) {
            JsonNode total = firstNullish(policy, "total_concurrency", "max_concurrent"); if (total == null) total = numberNode(Double.POSITIVE_INFINITY);
            JsonNode per = policy.get("per_asset_concurrency"); if (per == null || per.isNull()) per = numberNode(Double.POSITIVE_INFINITY);
            JsonNode cluster = truthy(policy.get("risk_strategy_cluster_caps")) ? policy.get("risk_strategy_cluster_caps") : (truthy(policy.get("cluster_caps")) ? policy.get("cluster_caps") : object());
            JsonNode venue = truthy(policy.get("venue_caps")) ? policy.get("venue_caps") : object();
            JsonNode gross = firstNullish(policy, "gross_exposure_cap"); if (gross == null) gross = numberNode(Double.POSITIVE_INFINITY);
            JsonNode net = firstNullish(policy, "net_exposure_cap"); if (net == null) net = numberNode(Double.POSITIVE_INFINITY);
            JsonNode capital = firstNullish(policy, "capital"); if (capital == null) capital = numberNode(initialEquity);
            JsonNode collateral = firstNullish(policy, "collateral_cap", "capital"); if (collateral == null) collateral = numberNode(initialEquity);
            JsonNode leverage = firstNullish(policy, "leverage_cap"); if (leverage == null) leverage = numberNode(Double.POSITIVE_INFINITY);
            ObjectNode raw = object(); raw.set("total_concurrency", jsonFiniteOrNull(total)); raw.set("per_asset_concurrency", jsonFiniteOrNull(per)); raw.set("cluster_caps", cluster.deepCopy()); raw.set("venue_caps", venue.deepCopy()); raw.set("gross_exposure_cap", jsonFiniteOrNull(gross)); raw.set("net_exposure_cap", jsonFiniteOrNull(net)); raw.set("capital", capital.deepCopy()); raw.set("collateral_cap", collateral.deepCopy()); raw.set("leverage_cap", jsonFiniteOrNull(leverage));
            return new Limits(numberOrInfinity(total), numberOrInfinity(per), numberOrInfinity(gross), numberOrInfinity(net), numberOrInfinity(collateral), numberOrInfinity(leverage), map(cluster), map(venue), raw);
        }
        ObjectNode legacyJson() { return raw.deepCopy(); }
        static Map<String, JsonNode> map(JsonNode value) { Map<String, JsonNode> result = new HashMap<>(); if (value != null && value.isObject()) value.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue())); return result; }
    }

    private static JsonNode jsonFiniteOrNull(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return NullNode.instance;
        return value.isNumber() && !Double.isFinite(value.doubleValue()) ? NullNode.instance : value.deepCopy();
    }

    private static boolean finiteNumberOrMissing(JsonNode value) { return value != null && !value.isNull() && !value.isMissingNode() && Double.isFinite(number(value)); }

}
