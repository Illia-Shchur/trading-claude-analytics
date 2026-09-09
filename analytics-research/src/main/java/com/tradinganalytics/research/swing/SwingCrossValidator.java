package com.tradinganalytics.research.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Pure Java port of {@code tools/swing-cross-validate.mjs}. */
public final class SwingCrossValidator {
    public static final String PRECOMMIT_SCHEMA = "swing-cross-asset-precommit/1";
    public static final String VALIDATION_SCHEMA = "swing-cross-asset-validation/1";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final DateTimeFormatter JS_ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private SwingCrossValidator() {}

    public static ObjectNode validateCrossAsset(JsonNode rows, JsonNode candidates, JsonNode precommit,
            String featureStoreSha256, JsonNode featureSeal) {
        return validateCrossAsset(rows, candidates, precommit, featureStoreSha256, featureSeal, Clock.systemUTC());
    }

    public static ObjectNode validateCrossAsset(JsonNode rowsNode, JsonNode candidatesNode, JsonNode precommit,
            String featureStoreSha256, JsonNode featureSeal, Clock clock) {
        if (precommit == null || !PRECOMMIT_SCHEMA.equals(text(precommit.get("schema")))) {
            throw new IllegalArgumentException("unsupported cross-asset precommit");
        }
        String asset = text(precommit.get("validation_asset"));
        asset = asset == null ? "" : asset.toLowerCase();
        if (asset.isEmpty()) throw new IllegalArgumentException("precommit validation_asset is required");
        ArrayNode rows = array(rowsNode);
        for (JsonNode row : rows) {
            if (!asset.equals(text(row.get("asset")))) {
                throw new IllegalArgumentException("cache contains assets other than frozen validation asset " + asset);
            }
        }
        if (precommit.path("require_feature_store_seal").asBoolean(false)) {
            if (featureSeal == null || !"swing-feature-seal/1".equals(text(featureSeal.get("schema")))) {
                throw new IllegalArgumentException("a valid feature-store seal is required");
            }
            if (!SwingEngine.sha256(precommit).equals(text(featureSeal.get("precommit_sha256")))) {
                throw new IllegalArgumentException("feature-store seal precommit hash mismatch");
            }
            if (featureStoreSha256 == null || !featureStoreSha256.equals(text(featureSeal.get("feature_store_sha256")))) {
                throw new IllegalArgumentException("feature-store seal data hash mismatch");
            }
        }

        ArrayNode candidates = array(candidatesNode);
        LinkedHashMap<String, JsonNode> byId = new LinkedHashMap<>();
        candidates.forEach(candidate -> byId.put(String.valueOf(text(candidate.get("id"))), candidate));
        List<String> ids = new ArrayList<>();
        array(precommit.get("candidate_ids")).forEach(id -> ids.add(id.asText()));
        List<String> missing = ids.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("frozen candidates missing: " + String.join(",", missing));
        ArrayNode frozen = JSON.arrayNode();
        ids.forEach(id -> frozen.add(byId.get(id).deepCopy()));
        if (!SwingEngine.sha256(frozen).equals(text(precommit.get("candidate_sha256")))) {
            throw new IllegalArgumentException("frozen candidate hash mismatch");
        }

        JsonNode criteria = objectOrEmpty(precommit.get("acceptance"));
        List<Outage> outages = declaredOutages(precommit);
        ArrayNode reports = JSON.arrayNode();
        for (JsonNode raw : frozen) {
            ObjectNode candidate = SwingEngine.normalizeCandidate(raw);
            ArrayNode seriesRows = relevantRows(rows, candidate, asset);
            if (seriesRows.isEmpty()) throw new IllegalArgumentException("no validation rows for " + candidate.path("id").asText());
            ObjectNode report = SwingEngine.evaluateCandidate(seriesRows, candidate,
                    JSON.objectNode().put("candidate_count", 1).put("bootstrap_rounds", 2000));
            ObjectNode calendar = calendarBreakdown(array(report.get("trades")));
            ObjectNode blocks = equalCountBlocks(array(report.get("trades")), 3);
            int positiveBlocks = 0;
            double worstBlock = Double.POSITIVE_INFINITY;
            for (JsonNode metrics : blocks) {
                double expectancy = nullableNumber(metrics.get("expectancy_r"), Double.NEGATIVE_INFINITY);
                if (expectancy > 0) positiveBlocks++;
                worstBlock = Math.min(worstBlock, expectancy);
            }

            ObjectNode stressedCandidate = raw.deepCopy();
            stressedCandidate.put("fee_pct", jsNumber(raw.get("fee_pct"), 0.1) * 2);
            stressedCandidate.put("slippage_pct", jsNumber(raw.get("slippage_pct"), 0.05) * 2);
            ObjectNode stressed = SwingEngine.evaluateCandidate(seriesRows, stressedCandidate,
                    JSON.objectNode().put("candidate_count", 1).put("bootstrap_rounds", 1000));
            ObjectNode coverage = coverageMetrics(seriesRows, outages, true);
            double minYearTrades = jsOr(criteria.get("minimum_trades_per_positive_year"), 1);
            int positiveYears = 0;
            for (JsonNode metrics : calendar) {
                if (number(metrics.get("completed_trades")) >= minYearTrades
                        && nullableNumber(metrics.get("expectancy_r"), Double.NEGATIVE_INFINITY) > 0) positiveYears++;
            }
            JsonNode metrics = report.get("metrics");
            JsonNode stressedMetrics = stressed.get("metrics");
            boolean fundingObserved = number(metrics.get("funding_debit")) + number(metrics.get("funding_credit")) > 0;
            ObjectNode checks = JSON.objectNode();
            checks.put("minimum_completed_trades", number(metrics.get("completed_trades")) >= jsOr(criteria.get("minimum_completed_trades"), 0));
            checks.put("positive_calendar_years", positiveYears >= jsOr(criteria.get("minimum_positive_calendar_years"), 0));
            checks.put("positive_expectancy", nullableNumber(metrics.get("expectancy_r"), Double.NEGATIVE_INFINITY)
                    > jsOr(criteria.get("after_cost_expectancy_r_must_exceed"), 0));
            checks.put("positive_profit_factor", metrics.path("profit_factor_unbounded").asBoolean(false)
                    || nullableNumber(metrics.get("profit_factor"), Double.NEGATIVE_INFINITY) > jsOr(criteria.get("profit_factor_must_exceed"), 1));
            checks.put("positive_bootstrap_p20", nullableNumber(metrics.get("expectancy_bootstrap_20"), Double.NEGATIVE_INFINITY)
                    > jsOr(criteria.get("bootstrap_20th_percentile_expectancy_r_must_exceed"), 0));
            checks.put("drawdown_within_limit", number(metrics.get("max_drawdown")) <= jsOr(criteria.get("maximum_drawdown"), 1));
            checks.put("funding_charged", !criteria.path("funding_must_be_charged").asBoolean(false) || fundingObserved);
            checks.put("chronological_blocks", positiveBlocks >= jsOr(criteria.get("minimum_positive_chronological_blocks"), 0)
                    && worstBlock > jsOr(criteria.get("minimum_chronological_block_expectancy_r"), Double.NEGATIVE_INFINITY));
            checks.put("doubled_cost_expectancy", !criteria.has("doubled_cost_expectancy_must_exceed")
                    || nullableNumber(stressedMetrics.get("expectancy_r"), Double.NEGATIVE_INFINITY)
                    > number(criteria.get("doubled_cost_expectancy_must_exceed")));
            checks.put("feature_coverage", number(coverage.get("coverage_4h")) >= jsOr(criteria.get("minimum_4h_coverage"), 0)
                    && number(coverage.get("derivatives_coverage")) >= jsOr(criteria.get("minimum_derivatives_coverage"), 0)
                    && number(coverage.get("positioning_coverage")) >= jsOr(criteria.get("minimum_positioning_coverage"), 0)
                    && number(coverage.get("max_gap_bars")) <= jsOr(criteria.get("maximum_gap_bars"), Double.POSITIVE_INFINITY));
            boolean accepted = allTrue(checks);

            ObjectNode result = JSON.objectNode();
            result.set("candidate", report.get("candidate").deepCopy());
            result.set("metrics", metrics.deepCopy());
            result.set("calendar_years", calendar);
            result.put("positive_calendar_years", positiveYears);
            result.set("chronological_blocks", blocks);
            result.put("positive_chronological_blocks", positiveBlocks);
            putFiniteOrNull(result, "worst_chronological_block_expectancy_r", worstBlock);
            result.set("doubled_cost_metrics", stressedMetrics.deepCopy());
            result.set("coverage", coverage);
            result.put("funding_observed", fundingObserved);
            result.set("checks", checks);
            result.put("accepted", accepted);
            reports.add(result);
        }

        String primaryId = text(precommit.get("primary_candidate_id"));
        JsonNode primary = null;
        for (JsonNode report : reports) if (primaryId != null && primaryId.equals(report.path("candidate").path("id").asText())) { primary = report; break; }
        if (primary == null) throw new IllegalArgumentException("primary candidate is not in frozen candidate set");
        boolean primaryAccepted = primary.path("accepted").asBoolean(false);
        ObjectNode output = JSON.objectNode();
        output.put("schema", VALIDATION_SCHEMA);
        output.put("generated_at", JS_ISO.format(clock.instant()));
        output.put("activation", "SHADOW");
        output.put("validation_asset", asset);
        output.set("feature_store_sha256", featureStoreSha256 == null ? NullNode.instance : JSON.textNode(featureStoreSha256));
        output.put("precommit_sha256", SwingEngine.sha256(precommit));
        output.set("candidate_sha256", copyOrNull(precommit.get("candidate_sha256")));
        output.set("primary_candidate_id", copyOrNull(precommit.get("primary_candidate_id")));
        output.put("primary_accepted", primaryAccepted);
        output.put("verdict", primaryAccepted ? "PRIMARY_PASSED_CROSS_ASSET_CONFIRMATION" : "PRIMARY_FAILED_CROSS_ASSET_CONFIRMATION");
        output.put("secondary_candidates_are_diagnostic_only", true);
        output.set("reports", reports);
        output.set("limitations", precommit.has("limitations_declared_before_open")
                ? precommit.get("limitations_declared_before_open").deepCopy() : JSON.arrayNode());
        return output;
    }

    static ObjectNode calendarBreakdown(ArrayNode trades) {
        LinkedHashSet<Integer> years = new LinkedHashSet<>();
        List<Integer> sortedYears = new ArrayList<>();
        for (JsonNode trade : trades) {
            int year = Instant.ofEpochMilli((long) number(trade.get("entry_time"))).atZone(ZoneOffset.UTC).getYear();
            if (years.add(year)) sortedYears.add(year);
        }
        sortedYears.sort(Integer::compareTo);
        ObjectNode output = JSON.objectNode();
        for (int year : sortedYears) {
            ArrayNode subset = JSON.arrayNode();
            for (JsonNode trade : trades) {
                if (Instant.ofEpochMilli((long) number(trade.get("entry_time"))).atZone(ZoneOffset.UTC).getYear() == year) subset.add(trade.deepCopy());
            }
            output.set(Integer.toString(year), SwingEngine.tradeMetrics(subset,
                    JSON.objectNode().put("rawSetupBars", subset.size()).put("uniqueSignals", subset.size()).put("bootstrapRounds", 500)));
        }
        return output;
    }

    static ObjectNode equalCountBlocks(ArrayNode trades, int count) {
        List<JsonNode> sorted = new ArrayList<>();
        trades.forEach(sorted::add);
        sorted.sort(Comparator.comparingDouble((JsonNode trade) -> number(trade.get("entry_time")))
                .thenComparingDouble(trade -> number(trade.get("exit_time"))));
        ObjectNode blocks = JSON.objectNode();
        for (int index = 0; index < count; index++) {
            int start = index * sorted.size() / count;
            int end = (index + 1) * sorted.size() / count;
            ArrayNode subset = JSON.arrayNode();
            for (int cursor = start; cursor < end; cursor++) subset.add(sorted.get(cursor).deepCopy());
            blocks.set("block_" + (index + 1), SwingEngine.tradeMetrics(subset,
                    JSON.objectNode().put("rawSetupBars", subset.size()).put("uniqueSignals", subset.size()).put("bootstrapRounds", 500)));
        }
        return blocks;
    }

    static List<Outage> declaredOutages(JsonNode precommit) {
        List<Outage> outages = new ArrayList<>();
        ArrayNode source = array(precommit.get("known_data_outages"));
        for (int index = 0; index < source.size(); index++) {
            JsonNode outage = source.get(index);
            long from = parseDate(outage.get("from"));
            long to = parseDate(outage.get("to"));
            if (from == Long.MIN_VALUE || to == Long.MIN_VALUE || to <= from) {
                throw new IllegalArgumentException("known_data_outages[" + index + "] has an invalid UTC range");
            }
            String reason = truthy(outage.get("reason")) ? outage.get("reason").asText() : "predeclared shared source outage";
            outages.add(new Outage(from, to, reason));
        }
        return outages;
    }

    static ObjectNode coverageMetrics(ArrayNode rows, List<Outage> outages, boolean positioning) {
        List<JsonNode> sorted = new ArrayList<>();
        rows.forEach(sorted::add);
        sorted.sort(Comparator.comparingDouble(row -> number(row.get("time"))));
        long barMs = 4L * 3_600_000;
        if (!sorted.isEmpty()) {
            String timeframe = text(sorted.getFirst().get("timeframe"));
            if (timeframe != null && timeframe.matches("^\\d+h$")) barMs = Long.parseLong(timeframe.substring(0, timeframe.length() - 1)) * 3_600_000;
        }
        long expected = sorted.isEmpty() ? 0 : (long) Math.floor((number(sorted.getLast().get("time")) - number(sorted.getFirst().get("time"))) / barMs) + 1;
        ArrayNode gaps = JSON.arrayNode();
        int undeclaredMax = 0, rawMax = 0, predeclaredCount = 0;
        for (int index = 1; index < sorted.size(); index++) {
            int missing = Math.max(0, (int) Math.round((number(sorted.get(index).get("time")) - number(sorted.get(index - 1).get("time"))) / barMs) - 1);
            if (missing == 0) continue;
            long from = (long) number(sorted.get(index - 1).get("time")) + barMs;
            long to = (long) number(sorted.get(index).get("time"));
            Outage declared = outages.stream().filter(outage -> from >= outage.from() && to <= outage.to()).findFirst().orElse(null);
            ObjectNode gap = JSON.objectNode().put("missing_bars", missing).put("from", from).put("to", to)
                    .put("predeclared_outage", declared != null);
            gap.set("outage_reason", declared == null ? NullNode.instance : JSON.textNode(declared.reason()));
            gaps.add(gap);
            rawMax = Math.max(rawMax, missing);
            if (declared == null) undeclaredMax = Math.max(undeclaredMax, missing); else predeclaredCount++;
        }
        int derivativeRows = 0, positioningRows = 0;
        for (JsonNode row : sorted) {
            if (strictFinite(row.get("funding_rate")) && strictFinite(row.get("funding_event_time"))) derivativeRows++;
            if (strictFinite(row.path("factors").path("derivatives").get("top_vs_global_positioning_z"))) positioningRows++;
        }
        ObjectNode output = JSON.objectNode().put("observed_bars", sorted.size()).put("expected_bars", expected);
        output.put("coverage_4h", expected == 0 ? 0 : (double) sorted.size() / expected);
        output.put("derivatives_coverage", sorted.isEmpty() ? 0 : (double) derivativeRows / sorted.size());
        if (positioning) output.put("positioning_coverage", sorted.isEmpty() ? 0 : (double) positioningRows / sorted.size());
        output.put("max_gap_bars", undeclaredMax).put("raw_max_gap_bars", rawMax);
        if (positioning) output.put("predeclared_outage_count", predeclaredCount);
        output.set("gaps", gaps);
        output.set("first_time", sorted.isEmpty() ? NullNode.instance : copyOrNull(sorted.getFirst().get("time")));
        output.set("last_time", sorted.isEmpty() ? NullNode.instance : copyOrNull(sorted.getLast().get("time")));
        return output;
    }

    static ArrayNode relevantRows(ArrayNode rows, JsonNode candidate, String asset) {
        ArrayNode out = JSON.arrayNode();
        for (JsonNode row : rows) {
            boolean same = asset.equals(text(row.get("asset"))) && text(candidate.get("framework")).equals(text(row.get("framework")));
            if (same && (!"flying_rocket".equals(text(candidate.get("framework")))
                    || java.util.Objects.equals(text(row.get("channel")), text(candidate.get("channel"))))) out.add(row.deepCopy());
        }
        return out;
    }

    record Outage(long from, long to, String reason) {}

    static ArrayNode array(JsonNode node) { return node != null && node.isArray() ? (ArrayNode) node : JSON.arrayNode(); }
    static JsonNode objectOrEmpty(JsonNode node) { return node != null && node.isObject() ? node : JSON.objectNode(); }
    static String text(JsonNode node) { return node == null || node.isNull() || node.isMissingNode() ? null : node.asText(); }
    static boolean strictFinite(JsonNode node) { return node != null && node.isNumber() && Double.isFinite(node.doubleValue()); }
    static double number(JsonNode node) { return node == null || node.isNull() ? 0 : node.asDouble(); }
    static double nullableNumber(JsonNode node, double fallback) { return node == null || node.isNull() ? fallback : node.asDouble(); }
    static double jsNumber(JsonNode node, double fallback) {
        if (node == null || node.isNull() || node.isMissingNode()) return fallback;
        if (node.isBoolean()) return node.asBoolean() ? 1 : 0;
        if (node.isTextual() && node.asText().isBlank()) return 0;
        try { return Double.parseDouble(node.asText()); } catch (RuntimeException ignored) { return fallback; }
    }
    static double jsOr(JsonNode node, double fallback) { double value = jsNumber(node, Double.NaN); return !Double.isFinite(value) || value == 0 ? fallback : value; }
    static boolean truthy(JsonNode node) { return node != null && !node.isNull() && (!node.isBoolean() || node.asBoolean()) && (!node.isTextual() || !node.asText().isEmpty()) && (!node.isNumber() || node.doubleValue() != 0); }
    static boolean allTrue(ObjectNode checks) { for (JsonNode value : checks) if (!value.asBoolean(false)) return false; return true; }
    static long parseDate(JsonNode node) { try { return Instant.parse(text(node)).toEpochMilli(); } catch (RuntimeException ignored) { return Long.MIN_VALUE; } }
    static JsonNode copyOrNull(JsonNode node) { return node == null || node.isMissingNode() ? NullNode.instance : node.deepCopy(); }
    static void putFiniteOrNull(ObjectNode object, String name, double value) { if (Double.isFinite(value)) object.put(name, value); else object.set(name, NullNode.instance); }
}
