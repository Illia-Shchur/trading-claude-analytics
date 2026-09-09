package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Corrected, separately versioned evaluator adapter for fixed-baseline V5.
 *
 * <p>{@link StrategyFixedBaselineV5} is a retained historical evaluator.  This
 * class never changes or re-hashes its result.  It runs that evaluator, then
 * sends both of its portfolio books through the V1 accounting correction and
 * rebuilds every portfolio-derived projection from the corrected books.  The
 * adapter is intentionally small: signal generation, matching, lifecycle,
 * and statistical rules remain owned by the frozen evaluator.</p>
 */
public final class StrategyFixedBaselineCorrectedV1 {
    public static final String RESULT_SCHEMA = "strategy-fixed-baseline-corrected-result/1";
    public static final int VERSION = 1;
    public static final String EVALUATOR_ID =
            "com.tradinganalytics.research.v5.StrategyFixedBaselineCorrectedV1";
    public static final String ACCOUNTING_VERSION =
            StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION;
    private static final String FROZEN_RESULT_SCHEMA = "strategy-fixed-baseline-result/1";
    private static final String REFINEMENT_RESULT_SCHEMA = "strategy-fixed-refinement-member-result/1";
    private static final double EPSILON = 1e-8;
    private static final String ALGORITHM_FINGERPRINT = algorithmFingerprint();

    private StrategyFixedBaselineCorrectedV1() { }

    /** Production corrected evaluation using the packaged V5 executor. */
    static ObjectNode evaluate(ObjectNode args, ObjectNode baseline, ObjectNode controls,
            ObjectNode experiment, ObjectNode portfolioPolicy,
            StrategyFixedBaselineV5.PhysicalInput physical, ObjectNode exposure,
            java.nio.file.Path exposurePath, ObjectNode exposureLineage,
            String refinementMember, double refinementThreshold) {
        return correctFrozenResult(StrategyFixedBaselineV5.evaluate(args, baseline, controls,
                experiment, portfolioPolicy, physical, exposure, exposurePath, exposureLineage,
                refinementMember, refinementThreshold));
    }

    /**
     * Test seam matching the frozen evaluator's test seam.  The supplied
     * executable identity is retained in the corrected result and is required
     * to be a SHA-256 value, so tests cannot accidentally exercise an unbound
     * fallback path.
     */
    static ObjectNode evaluateWithExecutorIdentityForTest(ObjectNode args, ObjectNode baseline,
            ObjectNode controls, ObjectNode experiment, ObjectNode portfolioPolicy,
            StrategyFixedBaselineV5.PhysicalInput physical, ObjectNode exposure,
            java.nio.file.Path exposurePath, ObjectNode exposureLineage,
            String refinementMember, double refinementThreshold, String executorIdentity) {
        return correctFrozenResultForTest(StrategyFixedBaselineV5.evaluateWithExecutorIdentityForTest(
                args, baseline, controls, experiment, portfolioPolicy, physical, exposure,
                exposurePath, exposureLineage, refinementMember, refinementThreshold,
                executorIdentity), executorIdentity);
    }

    /**
     * Adapt a completed frozen result.  This is also the seam used by workers
     * that have already produced a V5 result.  The source result is immutable
     * evidence: it must be own-hashed, use a supported V5 schema, and contain
     * both event and control books.  An already corrected result is rejected
     * to prevent accidental double correction or fallback to frozen metrics.
     */
    public static ObjectNode correctFrozenResult(ObjectNode frozen) {
        return correctFrozenResult(frozen, currentCorrectedExecutorIdentity());
    }

    /** Deterministic test seam; production callers must use the packaged path above. */
    static ObjectNode correctFrozenResultForTest(ObjectNode frozen, String correctedExecutorIdentity) {
        if (!JsonHashes.isSha256(correctedExecutorIdentity)) {
            throw new IllegalArgumentException("test corrected executor identity must be a SHA-256 hash");
        }
        return correctFrozenResult(frozen, correctedExecutorIdentity);
    }

    private static ObjectNode correctFrozenResult(ObjectNode frozen, String correctedExecutorIdentity) {
        if (frozen == null || !frozen.isObject()) {
            throw new IllegalArgumentException("corrected evaluator requires an object frozen result");
        }
        String sourceSchema = frozen.path("schema").asText("");
        if (!FROZEN_RESULT_SCHEMA.equals(sourceSchema) && !REFINEMENT_RESULT_SCHEMA.equals(sourceSchema)) {
            throw new IllegalArgumentException("corrected evaluator requires a frozen V5 result schema");
        }
        String sourceHash = frozen.path("content_sha256").asText("");
        if (!JsonHashes.isSha256(sourceHash) || !sourceHash.equals(JsonHashes.ownHash(frozen))) {
            throw new IllegalArgumentException("corrected evaluator source result content hash is invalid");
        }
        if (frozen.has("correction_status") || frozen.has("corrected_evaluator_identity")) {
            throw new IllegalArgumentException("corrected evaluator cannot correct an already corrected result");
        }
        String sourceExecutor = frozen.path("executor_identity_sha256").asText("");
        if (!JsonHashes.isSha256(sourceExecutor)) {
            throw new IllegalArgumentException("corrected evaluator source executable identity is missing");
        }
        if (!frozen.path("evaluator").isObject()
                || !"TradeLifecycleV5".equals(frozen.path("evaluator").path("name").asText(""))) {
            throw new IllegalArgumentException("corrected evaluator source is not the frozen TradeLifecycleV5 evaluator");
        }
        for (String field : List.of("baseline_sha256", "control_spec_sha256", "experiment_sha256",
                "physical_input_sha256")) {
            if (!JsonHashes.isSha256(frozen.path(field).asText(""))) {
                throw new IllegalArgumentException("corrected evaluator source input binding is missing: " + field);
            }
        }
        ObjectNode sourcePortfolio = object(frozen, "portfolio", "frozen result");
        ObjectNode eventBook = object(sourcePortfolio, "event_book", "frozen portfolio");
        ObjectNode controlBook = object(sourcePortfolio, "control_book", "frozen portfolio");
        validateSupportedTradeContract(eventBook, "event_book");
        validateSupportedTradeContract(controlBook, "control_book");

        ObjectNode correctedEvent = StrategyFixedBaselinePortfolioCorrectionV1.correctLegacyBook(eventBook);
        ObjectNode correctedControl = StrategyFixedBaselinePortfolioCorrectionV1.correctLegacyBook(controlBook);
        ObjectNode correctedPortfolio = correctedPortfolio(sourcePortfolio, correctedEvent, correctedControl);

        ObjectNode result = frozen.deepCopy();
        result.put("schema", RESULT_SCHEMA).put("version", VERSION)
                .put("correction_status", "CORRECTED")
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("evaluator_identity", EVALUATOR_ID)
                .put("corrected_evaluator_identity", EVALUATOR_ID)
                .put("source_evaluator_identity", "com.tradinganalytics.research.v5.StrategyFixedBaselineV5")
                .put("source_result_schema", sourceSchema)
                .put("source_result_content_sha256", sourceHash)
                .put("source_executor_identity_sha256", sourceExecutor)
                .put("corrected_executor_identity_sha256", correctedExecutorIdentity)
                .put("executor_identity_sha256", correctedExecutorIdentity)
                .put("algorithm_fingerprint", ALGORITHM_FINGERPRINT);
        if (frozen.path("build_identity").isObject()) {
            result.set("source_build_identity", frozen.path("build_identity").deepCopy());
        }
        result.set("portfolio", correctedPortfolio);
        replaceTradeProjections(result, correctedEvent, correctedControl);
        result.set("corrected_portfolio_metrics", correctedPortfolio.path("corrected_metrics").deepCopy());
        ObjectNode metrics = result.path("metrics").isObject()
                ? (ObjectNode) result.path("metrics").deepCopy()
                : JsonHashes.mapper().createObjectNode();
        addCorrectedMetricProjection(metrics, correctedPortfolio.path("corrected_metrics"));
        result.set("metrics", metrics);
        ObjectNode binding = JsonHashes.mapper().createObjectNode()
                .put("source_result_content_sha256", sourceHash)
                .put("source_evaluator_identity", "com.tradinganalytics.research.v5.StrategyFixedBaselineV5")
                .put("source_executor_identity_sha256", sourceExecutor)
                .put("baseline_sha256", frozen.path("baseline_sha256").asText(""))
                .put("control_spec_sha256", frozen.path("control_spec_sha256").asText(""))
                .put("experiment_sha256", frozen.path("experiment_sha256").asText(""))
                .put("physical_input_sha256", frozen.path("physical_input_sha256").asText(""))
                .put("corrected_evaluator_identity", EVALUATOR_ID)
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("algorithm_fingerprint", ALGORITHM_FINGERPRINT);
        result.set("corrected_input_binding", binding);
        result.put("corrected_input_binding_sha256", JsonHashes.canonicalSha256(binding));
        result.set("evaluator", JsonHashes.mapper().createObjectNode()
                .put("name", EVALUATOR_ID).put("production", true)
                .put("source_evaluator", "StrategyFixedBaselineV5")
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("full_recomputation", true));
        ObjectNode receipt = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-fixed-baseline-corrected-evaluator-receipt/1")
                .put("version", VERSION).put("evaluator_identity", EVALUATOR_ID)
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("source_result_content_sha256", sourceHash)
                .put("source_executor_identity_sha256", sourceExecutor)
                .put("event_book_correction_sha256", correctedEvent.path("content_sha256").asText())
                .put("control_book_correction_sha256", correctedControl.path("content_sha256").asText())
                .put("algorithm_fingerprint", ALGORITHM_FINGERPRINT);
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        result.set("correction_receipt", receipt);
        result.set("build_identity", BuildIdentityService.describe(StrategyFixedBaselineCorrectedV1.class));
        // All source hashes below describe the frozen result and are retained
        // under explicit names.  The outer hash belongs solely to this path.
        result.remove("content_sha256");
        result.remove("economic_semantic_sha256");
        result.remove("semantic_sha256");
        result.put("corrected_economic_semantic_sha256", correctedEconomicHash(result));
        result.put("corrected_semantic_sha256", correctedSemanticHash(result));
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static ObjectNode correctedPortfolio(ObjectNode source, ObjectNode event, ObjectNode control) {
        ObjectNode portfolio = source.deepCopy();
        portfolio.set("event_book", event);
        portfolio.set("control_book", control);
        double net = number(event, "net_pnl_usdt") + number(control, "net_pnl_usdt");
        double gross = number(event, "gross_pnl_usdt") + number(control, "gross_pnl_usdt");
        double fees = number(event, "fees_usdt") + number(control, "fees_usdt");
        double slippage = number(event, "slippage_usdt") + number(control, "slippage_usdt");
        double capacity = number(event, "capacity_debit_usdt") + number(control, "capacity_debit_usdt");
        double realized = number(event, "realized_pnl_usdt") + number(control, "realized_pnl_usdt");
        double starting = number(event, "starting_equity_usdt") + number(control, "starting_equity_usdt");
        double ending = number(event, "ending_equity_usdt") + number(control, "ending_equity_usdt");
        ArrayNode combinedCurve = combinedCurve(event, control, starting);
        double maxDrawdown = combinedCurve.isEmpty() ? 0D
                : combinedCurve.get(combinedCurve.size() - 1).path("max_drawdown_usdt").asDouble(0D);
        // The combined curve rows carry the running peak and current drawdown;
        // use the maximum of all rows rather than just the terminal row.
        for (JsonNode row : combinedCurve) maxDrawdown = Math.max(maxDrawdown,
                row.path("drawdown_usdt").asDouble(0D));
        portfolio.put("gross_pnl_usdt", gross)
                .put("fees_usdt", fees).put("slippage_usdt", slippage)
                .put("capacity_debit_usdt", capacity).put("realized_pnl_usdt", realized)
                .put("net_pnl_usdt", net)
                .put("starting_equity_usdt", starting)
                .put("ending_equity_usdt", ending)
                .put("max_drawdown_usdt", maxDrawdown)
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("evaluator_identity", EVALUATOR_ID)
                .put("corrected", true)
                .put("ending_minus_starting_equals_net", closeEnough(ending, starting + net))
                .put("sum_check", closeEnough(net, gross - fees - slippage - capacity))
                .put("final_marked_holdings_usdt", combinedCurve.isEmpty() ? 0D
                        : combinedCurve.get(combinedCurve.size() - 1).path("marked_holdings_usdt").asDouble(0D))
                .put("final_active_position_count", combinedCurve.isEmpty() ? 0
                        : combinedCurve.get(combinedCurve.size() - 1).path("active_position_count").asInt(0))
                .put("final_curve_equity_usdt", ending)
                .put("final_curve_equity_reconciles_cash_plus_marked_holdings", combinedCurve.isEmpty()
                        ? closeEnough(ending, starting) : closeEnough(ending,
                                combinedCurve.get(combinedCurve.size() - 1).path("equity_usdt").asDouble(Double.NaN)));
        portfolio.set("combined_equity_curve", combinedCurve);
        ObjectNode correctedMetrics = correctedMetrics(event, control, combinedCurve,
                starting, ending, net, maxDrawdown);
        portfolio.set("corrected_metrics", correctedMetrics);
        return portfolio;
    }

    private static ObjectNode correctedMetrics(ObjectNode event, ObjectNode control,
            ArrayNode curve, double starting, double ending, double net, double maxDrawdown) {
        double gross = number(event, "gross_pnl_usdt") + number(control, "gross_pnl_usdt");
        double fees = number(event, "fees_usdt") + number(control, "fees_usdt");
        double slippage = number(event, "slippage_usdt") + number(control, "slippage_usdt");
        double capacity = number(event, "capacity_debit_usdt") + number(control, "capacity_debit_usdt");
        ObjectNode combined = JsonHashes.mapper().createObjectNode()
                .put("starting_equity_usdt", starting).put("ending_equity_usdt", ending)
                .put("gross_pnl_usdt", gross).put("fees_usdt", fees)
                .put("slippage_usdt", slippage).put("capacity_debit_usdt", capacity)
                .put("net_pnl_usdt", net).put("max_drawdown_usdt", maxDrawdown)
                .put("return_fraction", net / starting)
                .put("max_drawdown_fraction", maxDrawdown / starting)
                .put("final_marked_holdings_usdt", curve.isEmpty() ? 0D
                        : curve.get(curve.size() - 1).path("marked_holdings_usdt").asDouble(0D))
                .put("final_active_position_count", curve.isEmpty() ? 0
                        : curve.get(curve.size() - 1).path("active_position_count").asInt(0))
                .put("equity_curve_recomputed", true);
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-fixed-baseline-corrected-portfolio-metrics/1")
                .put("version", VERSION).put("accounting_version", ACCOUNTING_VERSION)
                .put("evaluator_identity", EVALUATOR_ID)
                .put("trade_count", event.path("trade_count").asInt(0) + control.path("trade_count").asInt(0));
        result.set("event_book", bookMetrics(event));
        result.set("control_book", bookMetrics(control));
        result.set("combined", combined);
        // The full combined trace is retained once on the portfolio object.
        // Metric projections carry its immutable digest and row count so worker
        // artifacts do not multiply large curves.
        result.put("equity_curve_sha256", JsonHashes.canonicalSha256(curve))
                .put("equity_curve_source", "portfolio.combined_equity_curve")
                .put("equity_curve_row_count", curve.size());
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static ObjectNode bookMetrics(ObjectNode book) {
        return JsonHashes.mapper().createObjectNode()
                .put("starting_equity_usdt", number(book, "starting_equity_usdt"))
                .put("ending_equity_usdt", number(book, "ending_equity_usdt"))
                .put("net_pnl_usdt", number(book, "net_pnl_usdt"))
                .put("max_drawdown_usdt", number(book, "max_drawdown_usdt"))
                .put("return_fraction", number(book, "net_pnl_usdt")
                        / number(book, "starting_equity_usdt"))
                .put("final_marked_holdings_usdt", number(book, "final_marked_holdings_usdt"))
                .put("final_active_position_count", book.path("final_active_position_count").asInt(0));
    }

    private static void addCorrectedMetricProjection(ObjectNode metrics, JsonNode projection) {
        metrics.put("portfolio_accounting_version", ACCOUNTING_VERSION)
                .put("portfolio_evaluator_identity", EVALUATOR_ID)
                .put("portfolio_equity_recomputed", true);
        metrics.set("corrected_portfolio", projection.deepCopy());
        JsonNode combined = projection.path("combined");
        overwriteIfPresent(metrics, "net_pnl_usdt", combined.path("net_pnl_usdt"));
        overwriteIfPresent(metrics, "ending_equity_usdt", combined.path("ending_equity_usdt"));
        overwriteIfPresent(metrics, "max_drawdown_usdt", combined.path("max_drawdown_usdt"));
        overwriteIfPresent(metrics, "return_fraction", combined.path("return_fraction"));
    }

    private static void overwriteIfPresent(ObjectNode target, String field, JsonNode value) {
        if (target.has(field) && value != null && value.isNumber()) target.set(field, value.deepCopy());
    }

    private static void replaceTradeProjections(ObjectNode result, ObjectNode event, ObjectNode control) {
        Map<String, JsonNode> eventById = new LinkedHashMap<>();
        Map<String, JsonNode> controlById = new LinkedHashMap<>();
        indexTrades(eventById, event.path("trades"));
        indexTrades(controlById, control.path("trades"));
        JsonNode attempts = result.get("attempts");
        if (!(attempts instanceof ArrayNode rows)) return;
        for (JsonNode raw : rows) {
            if (!(raw instanceof ObjectNode attempt)) continue;
            replaceTrade(attempt, "event_trade", eventById);
            replaceTrade(attempt, "control_trade", controlById);
            if (attempt.path("status").asText("").equals("COMPLETE")
                    && attempt.path("event_trade").isObject() && attempt.path("control_trade").isObject()) {
                attempt.put("paired_net_pnl_usdt", number((ObjectNode) attempt.path("event_trade"), "net_pnl_usdt")
                        - number((ObjectNode) attempt.path("control_trade"), "net_pnl_usdt"));
            }
        }
    }

    private static void indexTrades(Map<String, JsonNode> byId, JsonNode trades) {
        if (!trades.isArray()) return;
        for (JsonNode trade : trades) {
            if (trade.isObject()) {
                String id = trade.path("episode_id").asText(trade.path("signal_id").asText(""));
                if (!id.isBlank()) byId.put(id, trade);
            }
        }
    }

    private static void validateSupportedTradeContract(ObjectNode book, String label) {
        JsonNode trades = book.get("trades");
        if (trades == null || !trades.isArray()) throw new IllegalArgumentException(label + " trades must be an array");
        for (JsonNode raw : trades) {
            if (!(raw instanceof ObjectNode trade)) continue;
            for (String field : List.of("direction", "instrument_type", "instrument")) {
                if (trade.has(field) && !trade.path(field).isTextual()) {
                    throw new IllegalArgumentException(label + " corrected accounting has malformed " + field);
                }
            }
            String direction = trade.path("direction").asText("long");
            if (trade.has("direction") && direction.isBlank()) {
                throw new IllegalArgumentException(label + " corrected accounting has malformed direction");
            }
            if (!"long".equalsIgnoreCase(direction)) {
                throw new IllegalArgumentException(label + " corrected accounting supports long spot trades only");
            }
            String instrumentType = trade.path("instrument_type").asText("SPOT");
            if (instrumentType.isBlank() || !"SPOT".equalsIgnoreCase(instrumentType)) {
                throw new IllegalArgumentException(label + " corrected accounting supports spot instruments only");
            }
            String instrument = trade.path("instrument").asText("BINANCE_SPOT");
            if (instrument.isBlank() || (!"BINANCE_SPOT".equalsIgnoreCase(instrument)
                    && !"SPOT".equalsIgnoreCase(instrument))) {
                throw new IllegalArgumentException(label + " corrected accounting supports spot instruments only");
            }
            for (String field : List.of("funding_usdt", "funding_fee_usdt", "borrow_fee_usdt", "margin_usdt")) {
                if (trade.has(field)) {
                    if (!trade.path(field).isNumber() || !Double.isFinite(trade.path(field).asDouble())
                            || Math.abs(trade.path(field).asDouble()) > EPSILON) {
                        throw new IllegalArgumentException(label + " corrected accounting does not support " + field);
                    }
                }
            }
            if (trade.has("leverage")) {
                if (!trade.path("leverage").isNumber() || !Double.isFinite(trade.path("leverage").asDouble())) {
                    throw new IllegalArgumentException(label + " corrected accounting has malformed leverage");
                }
                if (Math.abs(trade.path("leverage").asDouble() - 1D) > EPSILON) {
                    throw new IllegalArgumentException(label + " corrected accounting supports unlevered spot only");
                }
            }
        }
    }

    private static void replaceTrade(ObjectNode attempt, String field, Map<String, JsonNode> byId) {
        JsonNode trade = attempt.get(field);
        if (trade == null || !trade.isObject()) return;
        String id = trade.path("episode_id").asText(trade.path("signal_id").asText(""));
        JsonNode corrected = byId.get(id);
        if (corrected != null) attempt.set(field, corrected.deepCopy());
    }

    private static ArrayNode combinedCurve(ObjectNode event, ObjectNode control, double starting) {
        List<CurvePoint> points = new ArrayList<>();
        addCurvePoints(points, "EVENT", event.path("equity_curve"));
        addCurvePoints(points, "CONTROL", control.path("equity_curve"));
        points.sort(Comparator.comparing(CurvePoint::time)
                .thenComparingInt(point -> eventPriority(point.node().path("event_type").asText()))
                .thenComparingInt(point -> "EVENT".equals(point.book()) ? 0 : 1)
                .thenComparingInt(CurvePoint::ordinal));
        ObjectNode currentEvent = null, currentControl = null;
        double peak = starting;
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        for (CurvePoint point : points) {
            if ("EVENT".equals(point.book())) currentEvent = point.node();
            else currentControl = point.node();
            double eventEquity = currentEvent == null ? number(event, "starting_equity_usdt")
                    : number(currentEvent, "equity_usdt");
            double controlEquity = currentControl == null ? number(control, "starting_equity_usdt")
                    : number(currentControl, "equity_usdt");
            double eventCash = currentEvent == null ? number(event, "starting_equity_usdt")
                    : number(currentEvent, "cash_usdt");
            double controlCash = currentControl == null ? number(control, "starting_equity_usdt")
                    : number(currentControl, "cash_usdt");
            double eventMarked = currentEvent == null ? 0D : number(currentEvent, "marked_holdings_usdt");
            double controlMarked = currentControl == null ? 0D : number(currentControl, "marked_holdings_usdt");
            double equity = eventEquity + controlEquity;
            peak = Math.max(peak, equity);
            ObjectNode row = JsonHashes.mapper().createObjectNode()
                    .put("event_time", point.time().toString()).put("event_type", point.node().path("event_type").asText())
                    .put("source_book", point.book()).put("cash_usdt", eventCash + controlCash)
                    .put("marked_holdings_usdt", eventMarked + controlMarked)
                    .put("holdings_at_entry_cost_usdt", number(currentEvent, "holdings_at_entry_cost_usdt")
                            + number(currentControl, "holdings_at_entry_cost_usdt"))
                    .put("equity_usdt", equity).put("peak_equity_usdt", peak)
                    .put("drawdown_usdt", peak - equity)
                    .put("realized_pnl_usdt", number(point.node(), "realized_pnl_usdt"));
            row.put("active_position_count", (currentEvent == null ? 0 : currentEvent.path("active_position_count").asInt(0))
                    + (currentControl == null ? 0 : currentControl.path("active_position_count").asInt(0)));
            result.add(row);
        }
        return result;
    }

    private static void addCurvePoints(List<CurvePoint> points, String book, JsonNode curve) {
        if (!curve.isArray()) return;
        int ordinal = 0;
        for (JsonNode raw : curve) {
            if (!(raw instanceof ObjectNode node)) throw new IllegalArgumentException("corrected portfolio curve row is not an object");
            String text = node.path("event_time").asText("");
            try { points.add(new CurvePoint(Instant.parse(text), book, ordinal++, node)); }
            catch (RuntimeException error) { throw new IllegalArgumentException("corrected portfolio curve has invalid timestamp", error); }
        }
    }

    private static int eventPriority(String eventType) {
        return switch (eventType) {
            case "EXIT" -> 0;
            case "ENTRY" -> 1;
            case "MARK" -> 2;
            default -> throw new IllegalArgumentException("corrected portfolio curve has unsupported event type: " + eventType);
        };
    }

    private static ObjectNode object(ObjectNode parent, String field, String owner) {
        JsonNode value = parent.get(field);
        if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException(owner + " lacks " + field);
        return object;
    }

    private static double number(JsonNode node, String field) {
        return node != null && node.isObject() && node.path(field).isNumber()
                ? node.path(field).asDouble() : 0D;
    }

    private static boolean closeEnough(double left, double right) {
        return Double.isFinite(left) && Double.isFinite(right)
                && Math.abs(left - right) <= EPSILON * Math.max(1D, Math.max(Math.abs(left), Math.abs(right)));
    }

    private static String correctedEconomicHash(ObjectNode result) {
        ObjectNode copy = result.deepCopy();
        copy.remove("content_sha256"); copy.remove("corrected_economic_semantic_sha256");
        copy.remove("corrected_semantic_sha256"); copy.remove("build_identity");
        removeProvenance(copy);
        return JsonHashes.canonicalSha256(copy);
    }

    private static String correctedSemanticHash(ObjectNode result) {
        ObjectNode copy = result.deepCopy();
        copy.remove("content_sha256"); copy.remove("corrected_economic_semantic_sha256");
        copy.remove("corrected_semantic_sha256"); copy.remove("build_identity");
        return JsonHashes.canonicalSha256(copy);
    }

    /** Package-private custody seam used by serial/parallel equivalence validation. */
    static String correctedEconomicSha256ForValidation(ObjectNode result) {
        if (result == null || !RESULT_SCHEMA.equals(result.path("schema").asText())) {
            throw new IllegalArgumentException("corrected economic digest requires a corrected evaluator result");
        }
        return correctedEconomicHash(result);
    }

    /**
     * Recompute a corrected portfolio aggregate from its two corrected books.
     * The worker custody validator uses this package seam when reopening a
     * result, so a caller supplied aggregate can never certify itself.
     */
    static ObjectNode correctedPortfolioForValidation(JsonNode value) {
        if (!(value instanceof ObjectNode portfolio)) {
            throw new IllegalArgumentException("corrected portfolio replay requires an object");
        }
        ObjectNode event = object(portfolio, "event_book", "corrected portfolio");
        ObjectNode control = object(portfolio, "control_book", "corrected portfolio");
        return correctedPortfolio(portfolio,
                StrategyFixedBaselinePortfolioCorrectionV1.correctLegacyBook(event),
                StrategyFixedBaselinePortfolioCorrectionV1.correctLegacyBook(control));
    }

    private static void removeProvenance(ObjectNode copy) {
        stripProvenance(copy);
    }

    private static void stripProvenance(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String field : names) {
                if (field.equals("content_sha256") || field.endsWith("_path")
                        || field.equals("path") || field.equals("physical_root_reference")
                        || field.equals("source_build_identity") || field.equals("build_identity")
                        || field.equals("source_evaluator_identity") || field.equals("evaluator_identity")
                        || field.equals("corrected_evaluator_identity") || field.equals("source_result_schema")
                        || field.equals("source_result_content_sha256") || field.equals("source_executor_identity_sha256")
                        || field.equals("corrected_executor_identity_sha256")
                        || field.equals("executor_identity_sha256") || field.equals("attempt_identity_sha256")
                        || field.equals("exposure_head_sha256")
                        || field.equals("source_fingerprint") || field.equals("source_fingerprint_provenance")
                        || field.equals("source_input_canonical_sha256")
                        || field.equals("correction_input_canonical_sha256")
                        || field.equals("corrected_input_binding_sha256")
                        || field.equals("event_book_correction_sha256")
                        || field.equals("control_book_correction_sha256")
                        || field.equals("algorithm_fingerprint")
                        || field.equals("accounting_version") || field.equals("correction_receipt")
                        || field.equals("evaluator") || field.equals("legacy_book_schema")
                        || field.equals("legacy_book_content_sha256")) {
                    object.remove(field);
                } else {
                    stripProvenance(object.get(field));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(StrategyFixedBaselineCorrectedV1::stripProvenance);
        }
    }

    private static String currentCorrectedExecutorIdentity() {
        ObjectNode identity = BuildIdentityService.describe(StrategyFixedBaselineCorrectedV1.class);
        String kind = identity.path("executable").path("kind").asText("");
        String value = identity.path("executable").path("sha256").asText("");
        if (!"JAR".equals(kind) || !JsonHashes.isSha256(value)) {
            throw new IllegalArgumentException("corrected evaluator packaged executable identity is unavailable");
        }
        return value;
    }

    private static String algorithmFingerprint() {
        ObjectNode descriptor = JsonHashes.mapper().createObjectNode()
                .put("evaluator_identity", EVALUATOR_ID)
                .put("source_evaluator_identity", "com.tradinganalytics.research.v5.StrategyFixedBaselineV5")
                .put("accounting_version", ACCOUNTING_VERSION)
                .put("both_books", true).put("combined_curve", true)
                .put("metric_policy", "recompute_equity_holdings_returns_drawdown")
                .put("supported_contract", "long_spot_single_full_exit");
        return JsonHashes.canonicalSha256(descriptor);
    }

    private record CurvePoint(Instant time, String book, int ordinal, ObjectNode node) { }
}
