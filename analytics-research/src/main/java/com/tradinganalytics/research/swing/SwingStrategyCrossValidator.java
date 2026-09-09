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
import java.util.LinkedHashSet;
import java.util.List;

/** Pure Java port of {@code tools/swing-strategy-cross-validate.mjs}. */
public final class SwingStrategyCrossValidator {
    public static final String PRECOMMIT_SCHEMA = "swing-strategy-cross-asset-precommit/1";
    public static final String STRATEGY_SCHEMA = "swing-frozen-strategy/1";
    public static final String VALIDATION_SCHEMA = "swing-strategy-cross-asset-validation/1";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final DateTimeFormatter JS_ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final long BAR_MS = 4L * 3_600_000;

    private SwingStrategyCrossValidator() {}

    public static ObjectNode validateFrozenStrategy(JsonNode rows, JsonNode strategy, JsonNode precommit,
            String featureStoreSha256, JsonNode featureSeal) {
        return validateFrozenStrategy(rows, strategy, precommit, featureStoreSha256, featureSeal, Clock.systemUTC());
    }

    public static ObjectNode validateFrozenStrategy(JsonNode rowsNode, JsonNode strategy, JsonNode precommit,
            String featureStoreSha256, JsonNode featureSeal, Clock clock) {
        if (precommit == null || !PRECOMMIT_SCHEMA.equals(SwingCrossValidator.text(precommit.get("schema")))) {
            throw new IllegalArgumentException("unsupported strategy precommit");
        }
        if (strategy == null || !STRATEGY_SCHEMA.equals(SwingCrossValidator.text(strategy.get("schema")))) {
            throw new IllegalArgumentException("unsupported frozen strategy");
        }
        String asset = SwingCrossValidator.text(precommit.get("validation_asset"));
        asset = asset == null ? "" : asset.toLowerCase();
        ArrayNode rows = SwingCrossValidator.array(rowsNode);
        boolean wrongAsset = asset.isEmpty();
        for (JsonNode row : rows) wrongAsset |= !asset.equals(SwingCrossValidator.text(row.get("asset")));
        if (wrongAsset) throw new IllegalArgumentException("feature store must contain only frozen asset " + asset);

        ArrayNode components = SwingCrossValidator.array(strategy.get("components"));
        if (!java.util.Objects.equals(SwingCrossValidator.text(strategy.get("id")), SwingCrossValidator.text(precommit.get("strategy_id")))
                || !SwingEngine.sha256(components).equals(SwingCrossValidator.text(precommit.get("component_sha256")))
                || !SwingEngine.sha256(strategy).equals(SwingCrossValidator.text(precommit.get("strategy_sha256")))) {
            throw new IllegalArgumentException("frozen strategy hash mismatch");
        }
        if (precommit.path("require_feature_store_seal").asBoolean(false)) {
            if (featureSeal == null || !"swing-feature-seal/1".equals(SwingCrossValidator.text(featureSeal.get("schema")))) {
                throw new IllegalArgumentException("feature-store seal is required");
            }
            if (!SwingEngine.sha256(precommit).equals(SwingCrossValidator.text(featureSeal.get("precommit_sha256")))) {
                throw new IllegalArgumentException("feature-store seal precommit hash mismatch");
            }
            if (!java.util.Objects.equals(featureStoreSha256, SwingCrossValidator.text(featureSeal.get("feature_store_sha256")))) {
                throw new IllegalArgumentException("feature-store seal data hash mismatch");
            }
        }

        int hypothesisCount = Math.max(components.size(), (int) number(precommit.get("selection_hypothesis_count"), components.size()));
        ObjectNode report = SwingEngine.evaluateStrategy(rows, components,
                JSON.objectNode().put("bootstrap_rounds", 5000).put("candidate_count", hypothesisCount));
        ArrayNode stressedComponents = JSON.arrayNode();
        for (JsonNode component : components) {
            ObjectNode stressed = component.deepCopy();
            stressed.put("fee_pct", number(component.get("fee_pct"), 0.1) * 2);
            stressed.put("slippage_pct", number(component.get("slippage_pct"), 0.05) * 2);
            stressedComponents.add(stressed);
        }
        ObjectNode stressed = SwingEngine.evaluateStrategy(rows, stressedComponents,
                JSON.objectNode().put("bootstrap_rounds", 2000).put("candidate_count", hypothesisCount));
        ArrayNode trades = SwingCrossValidator.array(report.get("trades"));
        ObjectNode calendar = calendarBreakdown(trades);
        ObjectNode blocks = equalCountBlocks(trades, 3);
        JsonNode criteria = SwingCrossValidator.objectOrEmpty(precommit.get("acceptance"));
        int positiveYears = 0;
        for (JsonNode metrics : calendar) {
            if (SwingCrossValidator.number(metrics.get("completed_trades")) >= number(criteria.get("minimum_trades_per_positive_year"), 1)
                    && SwingCrossValidator.nullableNumber(metrics.get("expectancy_r"), Double.NEGATIVE_INFINITY) > 0) positiveYears++;
        }
        int positiveBlocks = 0;
        double worstBlock = Double.POSITIVE_INFINITY;
        for (JsonNode metrics : blocks) {
            double expectancy = SwingCrossValidator.nullableNumber(metrics.get("expectancy_r"), Double.NEGATIVE_INFINITY);
            if (expectancy > 0) positiveBlocks++;
            worstBlock = Math.min(worstBlock, expectancy);
        }
        ArrayNode coverageRows = JSON.arrayNode();
        for (JsonNode row : rows) if ("fallen_knives".equals(SwingCrossValidator.text(row.get("framework")))) coverageRows.add(row.deepCopy());
        ObjectNode coverage = coverageMetrics(coverageRows, declaredOutages(precommit));
        JsonNode metrics = report.get("metrics"), stressedMetrics = stressed.get("metrics");
        boolean fundingObserved = SwingCrossValidator.number(metrics.get("funding_debit"))
                + SwingCrossValidator.number(metrics.get("funding_credit")) > 0;
        ObjectNode checks = JSON.objectNode();
        checks.put("minimum_completed_trades", SwingCrossValidator.number(metrics.get("completed_trades")) >= number(criteria.get("minimum_completed_trades"), 0));
        checks.put("positive_expectancy", SwingCrossValidator.nullableNumber(metrics.get("expectancy_r"), Double.NEGATIVE_INFINITY)
                > number(criteria.get("after_cost_expectancy_r_must_exceed"), 0));
        checks.put("positive_search_adjusted_expectancy", SwingCrossValidator.nullableNumber(metrics.get("search_adjusted_expectancy_r"), Double.NEGATIVE_INFINITY)
                > number(criteria.get("search_adjusted_expectancy_r_must_exceed"), 0));
        checks.put("positive_profit_factor_r", metrics.path("profit_factor_r_unbounded").asBoolean(false)
                || SwingCrossValidator.nullableNumber(metrics.get("profit_factor_r"), Double.NEGATIVE_INFINITY) > number(criteria.get("profit_factor_r_must_exceed"), 1));
        checks.put("positive_profit_factor_dollars", metrics.path("profit_factor_unbounded").asBoolean(false)
                || SwingCrossValidator.nullableNumber(metrics.get("profit_factor"), Double.NEGATIVE_INFINITY) > number(criteria.get("profit_factor_dollars_must_exceed"), 1));
        checks.put("positive_total_return", SwingCrossValidator.nullableNumber(metrics.get("total_return"), Double.NEGATIVE_INFINITY)
                > number(criteria.get("total_return_must_exceed"), 0));
        checks.put("positive_bootstrap_p20", SwingCrossValidator.nullableNumber(metrics.get("expectancy_bootstrap_20"), Double.NEGATIVE_INFINITY)
                > number(criteria.get("bootstrap_20th_percentile_expectancy_r_must_exceed"), 0));
        checks.put("drawdown_within_limit", SwingCrossValidator.number(metrics.get("max_drawdown")) <= number(criteria.get("maximum_drawdown"), 1));
        checks.put("positive_calendar_years", positiveYears >= number(criteria.get("minimum_positive_calendar_years"), 0));
        checks.put("chronological_blocks", positiveBlocks >= number(criteria.get("minimum_positive_chronological_blocks"), 0)
                && worstBlock > number(criteria.get("minimum_chronological_block_expectancy_r"), Double.NEGATIVE_INFINITY));
        checks.put("doubled_cost_expectancy", SwingCrossValidator.nullableNumber(stressedMetrics.get("expectancy_r"), Double.NEGATIVE_INFINITY)
                > number(criteria.get("doubled_cost_expectancy_must_exceed"), 0));
        checks.put("doubled_cost_profit_factor_dollars", stressedMetrics.path("profit_factor_unbounded").asBoolean(false)
                || SwingCrossValidator.nullableNumber(stressedMetrics.get("profit_factor"), Double.NEGATIVE_INFINITY)
                > number(criteria.get("doubled_cost_profit_factor_dollars_must_exceed"), 1));
        checks.put("funding_charged", !criteria.path("funding_must_be_charged").asBoolean(false) || fundingObserved);
        checks.put("feature_coverage", SwingCrossValidator.number(coverage.get("coverage_4h")) >= number(criteria.get("minimum_4h_coverage"), 0)
                && SwingCrossValidator.number(coverage.get("derivatives_coverage")) >= number(criteria.get("minimum_derivatives_coverage"), 0)
                && SwingCrossValidator.number(coverage.get("router_feature_coverage")) >= number(criteria.get("minimum_4h_coverage"), 0)
                && SwingCrossValidator.number(coverage.get("max_gap_bars")) <= number(criteria.get("maximum_gap_bars"), Double.POSITIVE_INFINITY));

        ObjectNode output = JSON.objectNode();
        output.put("schema", VALIDATION_SCHEMA).put("generated_at", JS_ISO.format(clock.instant())).put("validation_asset", asset);
        output.set("strategy_id", SwingCrossValidator.copyOrNull(strategy.get("id")));
        output.put("component_sha256", SwingEngine.sha256(components)).put("strategy_sha256", SwingEngine.sha256(strategy))
                .put("precommit_sha256", SwingEngine.sha256(precommit));
        output.set("feature_store_sha256", featureStoreSha256 == null ? NullNode.instance : JSON.textNode(featureStoreSha256));
        output.put("seal_verified", !precommit.path("require_feature_store_seal").asBoolean(false)
                || java.util.Objects.equals(SwingCrossValidator.text(featureSeal.get("feature_store_sha256")), featureStoreSha256));
        output.put("decision", SwingCrossValidator.allTrue(checks) ? "PASS" : "FAIL");
        output.set("checks", checks);
        output.set("metrics", metrics.deepCopy());
        output.set("calendar_years", calendar);
        output.put("positive_calendar_years", positiveYears);
        output.set("chronological_blocks", blocks);
        output.put("positive_chronological_blocks", positiveBlocks);
        SwingCrossValidator.putFiniteOrNull(output, "worst_chronological_block_expectancy_r", worstBlock);
        output.set("stressed_metrics", stressedMetrics.deepCopy());
        output.set("coverage", coverage);
        output.set("component_breakdown", SwingCrossValidator.copyOrNull(report.get("component_breakdown")));
        output.set("direction_breakdown", SwingCrossValidator.copyOrNull(report.get("direction_breakdown")));
        output.set("trades", trades.deepCopy());
        return output;
    }

    private static ObjectNode calendarBreakdown(ArrayNode trades) {
        LinkedHashSet<Integer> years = new LinkedHashSet<>();
        for (JsonNode trade : trades) years.add(Instant.ofEpochMilli((long) SwingCrossValidator.number(trade.get("entry_time"))).atZone(ZoneOffset.UTC).getYear());
        List<Integer> sorted = new ArrayList<>(years); sorted.sort(Integer::compareTo);
        ObjectNode output = JSON.objectNode();
        for (int year : sorted) {
            ArrayNode subset = JSON.arrayNode();
            for (JsonNode trade : trades) if (Instant.ofEpochMilli((long) SwingCrossValidator.number(trade.get("entry_time"))).atZone(ZoneOffset.UTC).getYear() == year) subset.add(trade.deepCopy());
            output.set(Integer.toString(year), SwingEngine.tradeMetrics(subset, JSON.objectNode().put("bootstrapRounds", 500)));
        }
        return output;
    }

    private static ObjectNode equalCountBlocks(ArrayNode trades, int count) {
        List<JsonNode> sorted = new ArrayList<>(); trades.forEach(sorted::add);
        sorted.sort(Comparator.comparingDouble((JsonNode trade) -> SwingCrossValidator.number(trade.get("entry_time")))
                .thenComparingDouble(trade -> SwingCrossValidator.number(trade.get("exit_time"))));
        ObjectNode output = JSON.objectNode();
        for (int index = 0; index < count; index++) {
            ArrayNode subset = JSON.arrayNode();
            for (int cursor = index * sorted.size() / count; cursor < (index + 1) * sorted.size() / count; cursor++) subset.add(sorted.get(cursor).deepCopy());
            output.set("block_" + (index + 1), SwingEngine.tradeMetrics(subset, JSON.objectNode().put("bootstrapRounds", 500)));
        }
        return output;
    }

    private static List<Outage> declaredOutages(JsonNode precommit) {
        List<Outage> output = new ArrayList<>();
        ArrayNode outages = SwingCrossValidator.array(precommit.get("known_data_outages"));
        for (int index = 0; index < outages.size(); index++) {
            JsonNode outage = outages.get(index);
            long from = SwingCrossValidator.parseDate(outage.get("from")), to = SwingCrossValidator.parseDate(outage.get("to"));
            if (from == Long.MIN_VALUE || to == Long.MIN_VALUE || to <= from) throw new IllegalArgumentException("known_data_outages[" + index + "] is invalid");
            output.add(new Outage(from, to, SwingCrossValidator.truthy(outage.get("reason")) ? outage.get("reason").asText() : "predeclared outage"));
        }
        return output;
    }

    private static ObjectNode coverageMetrics(ArrayNode rows, List<Outage> outages) {
        List<JsonNode> sorted = new ArrayList<>(); rows.forEach(sorted::add);
        sorted.sort(Comparator.comparingDouble(row -> SwingCrossValidator.number(row.get("time"))));
        long expected = sorted.isEmpty() ? 0 : (long) Math.floor((SwingCrossValidator.number(sorted.getLast().get("time"))
                - SwingCrossValidator.number(sorted.getFirst().get("time"))) / BAR_MS) + 1;
        ArrayNode gaps = JSON.arrayNode(); int maxGap = 0, rawMax = 0, derivatives = 0, router = 0;
        for (int index = 1; index < sorted.size(); index++) {
            int missing = Math.max(0, (int) Math.round((SwingCrossValidator.number(sorted.get(index).get("time"))
                    - SwingCrossValidator.number(sorted.get(index - 1).get("time"))) / BAR_MS) - 1);
            if (missing == 0) continue;
            long from = (long) SwingCrossValidator.number(sorted.get(index - 1).get("time")) + BAR_MS;
            long to = (long) SwingCrossValidator.number(sorted.get(index).get("time"));
            Outage declared = outages.stream().filter(outage -> from >= outage.from && to <= outage.to).findFirst().orElse(null);
            ObjectNode gap = JSON.objectNode().put("missing_bars", missing).put("from", from).put("to", to).put("predeclared", declared != null);
            gap.set("reason", declared == null ? NullNode.instance : JSON.textNode(declared.reason)); gaps.add(gap);
            rawMax = Math.max(rawMax, missing); if (declared == null) maxGap = Math.max(maxGap, missing);
        }
        for (JsonNode row : sorted) {
            if (SwingCrossValidator.strictFinite(row.get("funding_rate")) && SwingCrossValidator.strictFinite(row.get("funding_event_time"))) derivatives++;
            if (SwingCrossValidator.strictFinite(row.path("factors").path("structure").get("ema50d_vs_ema200d_pct"))) router++;
        }
        ObjectNode output = JSON.objectNode().put("observed_bars", sorted.size()).put("expected_bars", expected)
                .put("coverage_4h", expected == 0 ? 0 : (double) sorted.size() / expected)
                .put("derivatives_coverage", sorted.isEmpty() ? 0 : (double) derivatives / sorted.size())
                .put("router_feature_coverage", sorted.isEmpty() ? 0 : (double) router / sorted.size())
                .put("max_gap_bars", maxGap).put("raw_max_gap_bars", rawMax).set("gaps", gaps);
        output.set("first_time", sorted.isEmpty() ? NullNode.instance : SwingCrossValidator.copyOrNull(sorted.getFirst().get("time")));
        output.set("last_time", sorted.isEmpty() ? NullNode.instance : SwingCrossValidator.copyOrNull(sorted.getLast().get("time")));
        return output;
    }

    private static double number(JsonNode node, double fallback) {
        if (node == null || node.isMissingNode()) return fallback;
        double value = SwingCrossValidator.jsNumber(node, Double.NaN);
        return Double.isFinite(value) ? value : fallback;
    }

    private record Outage(long from, long to, String reason) {}
}
