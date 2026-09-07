package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Numeric reconciliation for new prospective outcome evidence.
 *
 * <p>This is deliberately additive.  The existing outcome-resolution artifact
 * remains a custody and lifecycle input; this contract is the place where a
 * producer may make a numeric paper outcome claim.  Observed exchange fills
 * are optional and are never replaced with zero when unavailable.</p>
 */
public final class StrategyProspectiveOutcomeReconciliationV1 {
    public static final String INPUT_SCHEMA = "strategy-prospective-outcome-reconciliation-input/1";
    public static final String RESULT_SCHEMA = "strategy-prospective-outcome-reconciliation/1";
    public static final String LABEL_SOURCE_SCHEMA = "strategy-prospective-numeric-label-source/1";
    public static final String EXECUTION_SOURCE_SCHEMA = "strategy-prospective-numeric-execution-source/1";
    private static final double EPSILON = 1e-9;

    private StrategyProspectiveOutcomeReconciliationV1() {}

    /** Reconcile typed paper lifecycle rows and optional observed fills. */
    public static ObjectNode reconcile(ObjectNode input) {
        if (input == null || !INPUT_SCHEMA.equals(input.path("schema").asText())
                || input.path("version").asInt(-1) != 1) {
            throw new IllegalArgumentException("numeric reconciliation requires the typed input schema");
        }
        if (input.hasNonNull("account_currency") && !"USDT".equals(input.path("account_currency").asText())
                || input.hasNonNull("instrument_type") && !"SPOT".equals(input.path("instrument_type").asText())) {
            throw new IllegalArgumentException("numeric reconciliation supports only USDT SPOT inputs");
        }
        String maturityAsOf = input.path("maturity_as_of").asText("");
        try { Instant.parse(maturityAsOf); }
        catch (RuntimeException error) { throw new IllegalArgumentException("maturity_as_of must be an ISO-8601 instant"); }
        String completedBarId = input.path("completed_bar_id").asText("");
        String decisionLineage = input.path("decision_lineage_sha256").asText("");
        String asset = input.path("asset").asText("").toLowerCase();
        if (!completedBarId.isEmpty() && !decisionLineage.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("decision_lineage_sha256 must be a SHA-256 hash when supplied");
        }
        ObjectNode bindings = object(input.get("source_bindings"), "source_bindings");
        requireHash(bindings, "outcome_resolution_source_sha256");
        requireHash(bindings, "label_source_sha256");
        requireHash(bindings, "execution_source_sha256");
        if (!input.path("paper_trades").isArray() || input.path("paper_trades").isEmpty()) {
            throw new IllegalArgumentException("numeric reconciliation requires paper_trades");
        }
        ObjectNode observedById = observedById(input.path("observed_fills"));
        String observedConvention = input.path("observed_price_convention").asText("");
        if (!observedConvention.isBlank() && !Set.of("REFERENCE_PRICES_PLUS_EXECUTION_DEBITS", "ACTUAL_FILL_PRICES")
                .contains(observedConvention)) {
            throw new IllegalArgumentException("observed_price_convention is unsupported");
        }
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        double gross = 0D, fees = 0D, slippage = 0D, capacity = 0D, net = 0D;
        boolean unavailableObserved = false;
        List<String> errors = new ArrayList<>();
        Set<String> seenTradeIds = new HashSet<>();
        for (JsonNode raw : input.path("paper_trades")) {
            if (!(raw instanceof ObjectNode trade)) {
                errors.add("paper trade must be an object");
                continue;
            }
            String tradeId = text(trade, "trade_id");
            if (!seenTradeIds.add(tradeId)) {
                errors.add(tradeId + ": duplicate paper trade_id");
                continue;
            }
            if (trade.hasNonNull("maturity_as_of") && !maturityAsOf.equals(trade.path("maturity_as_of").asText())) {
                errors.add(tradeId + ": per-trade maturity_as_of cannot override root maturity_as_of");
            }
            Set<String> allowed = Set.of("trade_id", "direction", "quantity", "entry_price", "exit_price",
                    "entry_time", "exit_time", "resolution_time", "maturity_as_of", "fee_rate",
                    "slippage_rate", "capacity_debit_usdt", "gross_pnl_usdt", "fees_usdt",
                    "slippage_usdt", "net_pnl_usdt");
            var fields = trade.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!allowed.contains(field)) errors.add(tradeId + ": unsupported paper field " + field);
            }
            ObjectNode tradeWithMaturity = trade.deepCopy();
            tradeWithMaturity.put("maturity_as_of", maturityAsOf);
            ObjectNode row = reconcileTrade(tradeWithMaturity, observedById, observedConvention,
                    errors);
            rows.add(row);
            gross += row.path("expected_gross_pnl_usdt").asDouble();
            fees += row.path("expected_fees_usdt").asDouble();
            slippage += row.path("expected_slippage_usdt").asDouble();
            capacity += row.path("expected_capacity_debit_usdt").asDouble();
            net += row.path("expected_net_pnl_usdt").asDouble();
            unavailableObserved |= "UNAVAILABLE".equals(row.path("observed_status").asText());
        }
        var observedIds = observedById.fieldNames();
        while (observedIds.hasNext()) {
            String observedId = observedIds.next();
            if (!seenTradeIds.contains(observedId)) errors.add(observedId + ": observed fill has no paper trade");
        }
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", RESULT_SCHEMA)
                .put("version", 1)
                .put("paper_status", errors.isEmpty() ? "NUMERICALLY_RECONCILED" : "INVALID")
                .put("observed_fill_status", unavailableObserved ? "UNAVAILABLE" : "AVAILABLE")
                .put("account_currency", "USDT")
                .put("trade_count", rows.size())
                .put("expected_gross_pnl_usdt", gross)
                .put("expected_fees_usdt", fees)
                .put("expected_slippage_usdt", slippage)
                .put("expected_capacity_debit_usdt", capacity)
                .put("expected_net_pnl_usdt", net)
                .put("maturity_as_of", maturityAsOf)
                .put("paper_price_convention", "REFERENCE_PRICES_PLUS_EXECUTION_DEBITS")
                .put("observed_price_convention", observedConvention.isBlank()
                        ? "REFERENCE_PRICES_PLUS_EXECUTION_DEBITS" : observedConvention);
        if (completedBarId.isEmpty()) result.putNull("completed_bar_id"); else result.put("completed_bar_id", completedBarId);
        if (decisionLineage.isEmpty()) result.putNull("decision_lineage_sha256"); else result.put("decision_lineage_sha256", decisionLineage);
        result.put("paper_claim_is_observed_fill", false);
        // Asset is optional for the pure calculator API.  The governed
        // physical route requires it and validates it against the completed
        // bar; omitting it here keeps the standalone contract schema-valid
        // without inventing an asset for a synthetic/unit calculation.
        if (!asset.isBlank()) result.put("asset", asset);
        result.set("source_bindings", bindings.deepCopy());
        result.set("trades", rows);
        result.set("errors", strings(errors));
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    /** Validate that the typed input rows came from the reopened numeric source roles. */
    public static void validateTypedSources(ObjectNode input, ObjectNode labelSource,
            ObjectNode executionSource) {
        if (!LABEL_SOURCE_SCHEMA.equals(labelSource.path("schema").asText())
                || !EXECUTION_SOURCE_SCHEMA.equals(executionSource.path("schema").asText())) {
            throw new IllegalArgumentException("numeric reconciliation requires typed label and execution sources");
        }
        String bar = input.path("completed_bar_id").asText("");
        String lineage = input.path("decision_lineage_sha256").asText("");
        String asset = text(input, "asset").toLowerCase();
        for (ObjectNode source : List.of(labelSource, executionSource)) {
            if (!bar.equals(source.path("completed_bar_id").asText())
                    || !lineage.equals(source.path("decision_lineage_sha256").asText())) {
                throw new IllegalArgumentException("numeric source role is not bound to the completed bar/lineage");
            }
            if (!asset.isBlank() && !asset.equals(text(source, "asset").toLowerCase())) {
                throw new IllegalArgumentException("numeric source role is bound to a different asset");
            }
            if (!source.path("trades").isArray() || source.path("trades").isEmpty()) {
                throw new IllegalArgumentException("numeric source role has no typed trades");
            }
        }
        List<JsonNode> paper = rows(input.path("paper_trades"));
        List<JsonNode> execution = rows(executionSource.path("trades"));
        List<JsonNode> labels = rows(labelSource.path("trades"));
        if (paper.size() != execution.size() || paper.size() != labels.size()) {
            throw new IllegalArgumentException("paper trades do not cover the typed execution source");
        }
        boolean inputHasObserved = input.path("observed_fills").isArray()
                && !input.path("observed_fills").isEmpty();
        boolean executionHasObserved = executionSource.path("observed_fills").isArray()
                && !executionSource.path("observed_fills").isEmpty();
        if (inputHasObserved != executionHasObserved) {
            throw new IllegalArgumentException("observed fill claim is not bound to the typed execution source");
        }
        String inputObservedConvention = text(input, "observed_price_convention");
        String executionObservedConvention = text(executionSource, "observed_price_convention");
        if (inputHasObserved && (inputObservedConvention.isBlank()
                || !inputObservedConvention.equals(executionObservedConvention))) {
            throw new IllegalArgumentException("observed price convention is not bound to the typed execution source");
        }
        if (!inputHasObserved && !inputObservedConvention.isBlank()
                && !inputObservedConvention.equals(executionObservedConvention)) {
            throw new IllegalArgumentException("observed price convention differs from typed execution source");
        }
        if (inputHasObserved
                && !JsonHashes.canonicalSha256(input.path("observed_fills")).equals(
                        JsonHashes.canonicalSha256(executionSource.path("observed_fills")))) {
            throw new IllegalArgumentException("observed fills differ from the typed execution source");
        }
        for (int i = 0; i < paper.size(); i++) {
            JsonNode expected = paper.get(i), actual = execution.get(i);
            for (String field : List.of("trade_id", "direction", "quantity", "entry_price", "exit_price",
                    "entry_time", "exit_time", "resolution_time", "fee_rate", "slippage_rate",
                    "capacity_debit_usdt")) {
                if (!sameTypedValue(expected.get(field), actual.get(field))) {
                    throw new IllegalArgumentException("typed execution source differs for " + field);
                }
            }
            JsonNode label = labels.get(i);
            if (label == null || !text(expected, "trade_id").equals(text(label, "trade_id"))
                    || !text(expected, "resolution_time").equals(text(label, "resolution_time"))) {
                throw new IllegalArgumentException("typed label source differs from paper maturity binding");
            }
        }
    }

    private static boolean sameTypedValue(JsonNode left, JsonNode right) {
        if (left == null || right == null) return left == right;
        if (left.isNumber() && right.isNumber()) return Math.abs(left.asDouble() - right.asDouble()) <= EPSILON;
        return left.asText().equals(right.asText());
    }

    private static List<JsonNode> rows(JsonNode value) {
        List<JsonNode> rows = new ArrayList<>();
        if (value != null && value.isArray()) value.forEach(rows::add);
        return rows;
    }

    private static String text(JsonNode row, String field) {
        return row == null || !row.isObject() ? "" : row.path(field).asText("");
    }

    public static ObjectNode reconcile(JsonNode input) {
        return reconcile((ObjectNode) input);
    }

    private static ObjectNode reconcileTrade(ObjectNode trade, ObjectNode observedById,
            String observedConvention, List<String> errors) {
        String id = text(trade, "trade_id");
        if (id.isEmpty()) errors.add("trade_id is required");
        String direction = text(trade, "direction").toLowerCase();
        if (!("long".equals(direction) || "short".equals(direction))) {
            errors.add(id + ": direction must be long or short");
        }
        double quantity = finite(trade, "quantity", id, errors);
        double entry = finite(trade, "entry_price", id, errors);
        double exit = finite(trade, "exit_price", id, errors);
        double feeRate = finite(trade, "fee_rate", id, errors);
        double slippageRate = finite(trade, "slippage_rate", id, errors);
        double capacity = finite(trade, "capacity_debit_usdt", id, errors);
        if (quantity <= 0 || entry <= 0 || exit <= 0 || feeRate < 0 || slippageRate < 0 || capacity < 0) {
            errors.add(id + ": numeric fields are outside their allowed domain");
        }
        Instant entryTime = instant(trade, "entry_time", id, errors);
        Instant exitTime = instant(trade, "exit_time", id, errors);
        Instant resolutionTime = instant(trade, "resolution_time", id, errors);
        Instant asOf = instant(trade, "maturity_as_of", id, errors);
        if (entryTime != null && exitTime != null && !exitTime.isAfter(entryTime)) {
            errors.add(id + ": exit_time must be after entry_time");
        }
        if (exitTime != null && resolutionTime != null && !resolutionTime.isAfter(exitTime)) {
            errors.add(id + ": resolution_time must be after exit_time");
        }
        if (asOf != null && resolutionTime != null && asOf.isBefore(resolutionTime)) {
            errors.add(id + ": maturity_as_of precedes resolution_time");
        }
        double gross = "short".equals(direction) ? (entry - exit) * quantity : (exit - entry) * quantity;
        double fees = (entry + exit) * quantity * feeRate;
        double slippage = (entry + exit) * quantity * slippageRate;
        double expectedNet = gross - fees - slippage - capacity;
        checkReported(trade, "gross_pnl_usdt", gross, id, errors);
        checkReported(trade, "fees_usdt", fees, id, errors);
        checkReported(trade, "slippage_usdt", slippage, id, errors);
        checkReported(trade, "net_pnl_usdt", expectedNet, id, errors);

        ObjectNode row = JsonHashes.mapper().createObjectNode().put("trade_id", id)
                .put("expected_gross_pnl_usdt", gross).put("expected_fees_usdt", fees)
                .put("expected_slippage_usdt", slippage).put("expected_capacity_debit_usdt", capacity)
                .put("expected_net_pnl_usdt", expectedNet);
        ObjectNode observed = observedById.get(id) instanceof ObjectNode value ? value : null;
        if (observed == null) {
            row.put("observed_status", "UNAVAILABLE");
        } else {
            String observedDirection = text(observed, "direction").toLowerCase();
            Set<String> observedAllowed = Set.of("trade_id", "direction", "quantity", "entry_price", "exit_price",
                    "fees_usdt", "slippage_usdt", "capacity_debit_usdt", "net_pnl_usdt");
            var observedFields = observed.fieldNames();
            while (observedFields.hasNext()) {
                String field = observedFields.next();
                if (!observedAllowed.contains(field)) errors.add(id + ": unsupported observed field " + field);
            }
            boolean complete = Set.of("direction", "quantity", "entry_price", "exit_price", "fees_usdt",
                    "slippage_usdt", "capacity_debit_usdt").stream().allMatch(observed::hasNonNull);
            if (!complete) {
                row.put("observed_status", "UNAVAILABLE").put("observed_unavailable_reason", "OBSERVED_FILL_COMPONENT_MISSING");
            } else {
                double oq = finite(observed, "quantity", id + ": observed", errors);
                double oe = finite(observed, "entry_price", id + ": observed", errors);
                double ox = finite(observed, "exit_price", id + ": observed", errors);
                double of = finite(observed, "fees_usdt", id + ": observed", errors);
                double os = finite(observed, "slippage_usdt", id + ": observed", errors);
                double oc = finite(observed, "capacity_debit_usdt", id + ": observed", errors);
                double og = "short".equals(observedDirection) ? (oe - ox) * oq : (ox - oe) * oq;
                if ("ACTUAL_FILL_PRICES".equals(observedConvention) && (os > EPSILON || oc > EPSILON)) {
                    errors.add(id + ": actual fill prices cannot also claim modeled slippage/capacity debits");
                }
                double on = "ACTUAL_FILL_PRICES".equals(observedConvention) ? og - of : og - of - os - oc;
                if (!direction.equals(observedDirection) || oq <= 0 || oe <= 0 || ox <= 0 || of < 0 || os < 0 || oc < 0) {
                    errors.add(id + ": observed fill identity or numeric domain is invalid");
                }
                if (observed.hasNonNull("net_pnl_usdt")) {
                    double claimed = finite(observed, "net_pnl_usdt", id + ": observed", errors);
                    if (Math.abs(claimed - on) > EPSILON) errors.add(id + ": observed net_pnl_usdt does not reconcile");
                }
                row.put("observed_status", "AVAILABLE").put("observed_gross_pnl_usdt", og)
                        .put("observed_fees_usdt", of).put("observed_slippage_usdt", os)
                        .put("observed_capacity_debit_usdt", oc).put("observed_net_pnl_usdt", on)
                        .put("observed_minus_expected_net_pnl_usdt", on - expectedNet);
            }
        }
        return row;
    }

    private static void checkReported(ObjectNode row, String field, double expected, String id, List<String> errors) {
        if (!row.has(field)) return;
        double actual = row.path(field).asDouble(Double.NaN);
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > EPSILON) {
            errors.add(id + ": " + field + " does not reconcile to typed inputs");
        }
    }

    private static ObjectNode observedById(JsonNode raw) {
        ObjectNode index = JsonHashes.mapper().createObjectNode();
        if (raw == null || raw.isNull() || raw.isMissingNode()) return index;
        if (!raw.isArray()) throw new IllegalArgumentException("observed_fills must be an array when supplied");
        for (JsonNode value : raw) {
            if (!(value instanceof ObjectNode row) || text(row, "trade_id").isEmpty()) {
                throw new IllegalArgumentException("observed fill requires trade_id");
            }
            String id = text(row, "trade_id");
            if (index.has(id)) throw new IllegalArgumentException("duplicate observed fill trade_id: " + id);
            index.set(id, row);
        }
        return index;
    }

    private static ObjectNode object(JsonNode value, String label) {
        if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException(label + " must be an object");
        return object;
    }

    private static void requireHash(ObjectNode object, String field) {
        if (!object.path(field).asText().matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 hash");
        }
    }

    private static double finite(ObjectNode row, String field, String id, List<String> errors) {
        double value = row.has(field) && row.get(field).isNumber() ? row.get(field).asDouble() : Double.NaN;
        if (!Double.isFinite(value)) { errors.add(id + ": " + field + " must be finite"); return 0D; }
        return value;
    }

    private static Instant instant(ObjectNode row, String field, String id, List<String> errors) {
        String value = text(row, field);
        if (value.isEmpty()) { errors.add(id + ": " + field + " is required"); return null; }
        try { return Instant.parse(value); }
        catch (RuntimeException error) { errors.add(id + ": " + field + " is not an ISO-8601 instant"); return null; }
    }

    private static String text(ObjectNode row, String field) { return row.path(field).asText(""); }

    private static ArrayNode strings(List<String> values) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        values.forEach(result::add);
        return result;
    }
}
