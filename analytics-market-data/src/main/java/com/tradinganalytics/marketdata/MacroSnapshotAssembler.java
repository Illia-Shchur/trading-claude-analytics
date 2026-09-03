package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.compute.ComputeMath;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Map;

/** Turns the ordered macro fetch results into the public macro snapshot. */
final class MacroSnapshotAssembler {
    private static final DateTimeFormatter ISO_MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final ObjectMapper json;

    MacroSnapshotAssembler(ObjectMapper json) {
        this.json = json;
    }

    ObjectNode assemble(Map<String, JsonNode> fetched, long now, List<String> errors) {
        ObjectNode output = json.createObjectNode();
        output.put("scope", "macro");
        output.put("fetched_at", ISO_MILLIS.format(Instant.ofEpochMilli(now)));
        ArrayNode errorArray = output.putArray("errors");

        appendFredBlocks(output, fetched);
        appendLiquidityBlocks(output, fetched);
        appendBreadthBlock(output, fetched);
        appendYahooSeries(output, fetched);
        appendDryPowderBenchmark(output, fetched);

        ObjectNode coverage = output.putObject("gap_coverage");
        coverage.put("equities_breadth_pct_above_200dma",
                output.path("equities_breadth_200dma").path("available").asBoolean() ? "AVAILABLE" : "UNKNOWN");
        coverage.put("report_rule", "inspect this block before labeling equities breadth UNKNOWN");
        errors.forEach(errorArray::add);
        return output;
    }

    private void appendFredBlocks(ObjectNode output, Map<String, JsonNode> fetched) {
        ArrayNode fred = array(fetched.get("fred"));
        if (nonEmpty(fred)) {
            ObjectNode block = output.putObject("real_yield_10y_tips");
            block.put("source", "FRED DFII10 (daily, %)");
            block.set("last", fred.get(fred.size() - 1).deepCopy());
            int prior = Math.max(0, fred.size() - 6);
            putNumber(block, "delta_5_prints", ComputeMath.round2(number(fred.get(fred.size() - 1).get("value"))
                    - number(fred.get(prior).get("value"))));
            block.set("last_10", fred.deepCopy());
        }

        ArrayNode hyOas = array(fetched.get("hyOas"));
        if (nonEmpty(hyOas)) {
            ObjectNode block = output.putObject("hy_oas");
            block.put("source", "FRED BAMLH0A0HYM2 (ICE BofA US High Yield OAS, daily, %)");
            block.set("last", hyOas.get(hyOas.size() - 1).deepCopy());
            int prior = Math.max(0, hyOas.size() - 6);
            putNumber(block, "delta_5_prints", ComputeMath.round2(number(hyOas.get(hyOas.size() - 1).get("value"))
                    - number(hyOas.get(prior).get("value"))));
            block.put("note", "DISCLOSED CONTEXT ONLY — credit stress, not a scored input");
        }

        ArrayNode nfci = array(fetched.get("nfci"));
        if (nonEmpty(nfci)) {
            ObjectNode block = output.putObject("nfci");
            block.put("source", "FRED NFCI (Chicago Fed National Financial Conditions Index, weekly)");
            block.set("last", nfci.get(nfci.size() - 1).deepCopy());
            block.put("note", "DISCLOSED CONTEXT ONLY — 0 = historical average; positive = tighter-than-average conditions");
        }
    }

    private void appendLiquidityBlocks(ObjectNode output, Map<String, JsonNode> fetched) {
        ArrayNode walcl = array(fetched.get("walcl"));
        ArrayNode rrp = array(fetched.get("rrpontsyd"));
        ArrayNode tga = array(fetched.get("wtregen"));
        if (nonEmpty(walcl) && nonEmpty(rrp) && nonEmpty(tga)) {
            ObjectNode block = output.putObject("net_liquidity");
            block.put("source", "FRED WALCL + RRPONTSYD + WTREGEN (weekly, Thursdays)");
            block.put("as_of", walcl.get(walcl.size() - 1).path("date").asText());
            block.setAll(ComputeMath.netLiquidity(
                    nullableNumber(walcl.get(walcl.size() - 1).get("value")),
                    nullableNumber(rrp.get(rrp.size() - 1).get("value")),
                    nullableNumber(tga.get(tga.size() - 1).get("value"))));
        }

        ArrayNode stablecoinRows = array(fetched.get("stablecoinRows"));
        if (nonEmpty(stablecoinRows)) {
            ObjectNode block = output.putObject("stablecoin_supply");
            block.put("source", "DefiLlama stablecoincharts/all (aggregate across all tracked stablecoins/chains)");
            block.setAll(ComputeMath.stablecoinBlock(stablecoinRows));
        }
    }

    private void appendBreadthBlock(ObjectNode output, Map<String, JsonNode> fetched) {
        ObjectNode breadthData = object(fetched.get("breadthData"));
        if (breadthData == null) {
            return;
        }
        ObjectNode block = output.putObject("equities_breadth_200dma");
        block.put("source", "State Street SPY daily holdings universe + TradingView America scanner close/SMA200");
        block.setAll(MarketContextAnalytics.breadth200Block(
                arrayOrEmpty(breadthData.get("rows")), nullableNumber(breadthData.get("universeSize")),
                breadthData.path("universeAsOf").isNull() ? null : breadthData.path("universeAsOf").asText(), 95.0));
        block.put("scope_note", "SPY constituent universe; descriptive macro breadth, not a scored Channel B leg or gate");
    }

    private void appendYahooSeries(ObjectNode output, Map<String, JsonNode> fetched) {
        for (MacroFetchPlan.Series item : MacroFetchPlan.SERIES) {
            ArrayNode chart = array(fetched.get("chart:" + item.key()));
            if (!nonEmpty(chart)) {
                continue;
            }
            JsonNode last = chart.get(chart.size() - 1);
            JsonNode prior = chart.get(Math.max(0, chart.size() - 6));
            ObjectNode block = output.putObject(item.key());
            block.put("source", "Yahoo " + item.symbol() + " (" + item.label() + ")");
            putNumber(block, "last_close", ComputeMath.round2(number(last.get("close"))));
            block.put("date", last.path("date").asText());
            putNumber(block, "delta_5_sessions_pct", ComputeMath.round2(
                    (number(last.get("close")) / number(prior.get("close")) - 1.0) * 100.0));
            if ("spx".equals(item.key())) {
                appendSpxSeries(block, chart);
            }
        }
    }

    private void appendSpxSeries(ObjectNode block, ArrayNode chart) {
        ArrayNode values = block.putArray("series");
        for (JsonNode source : chart) {
            ObjectNode row = values.addObject();
            row.put("date", source.path("date").asText());
            putNumber(row, "close", ComputeMath.round2(number(source.get("close"))));
        }
    }

    private void appendDryPowderBenchmark(ObjectNode output, Map<String, JsonNode> fetched) {
        Double irx = nullableNumber(at(output, "irx", "last_close"));
        ArrayNode fred3mo = array(fetched.get("fred3mo"));
        Double dgs3mo = nonEmpty(fred3mo) ? nullableNumber(fred3mo.get(fred3mo.size() - 1).get("value")) : null;
        if (irx == null && dgs3mo == null) {
            return;
        }
        ObjectNode block = output.putObject("dry_powder_benchmark");
        putNumber(block, "annualized_pct", irx != null ? irx : dgs3mo);
        if (irx != null) {
            block.put("source", "Yahoo ^IRX (" + output.path("irx").path("date").asText() + ")");
        } else {
            block.put("source", "FRED DGS3MO (" + fred3mo.get(fred3mo.size() - 1).path("date").asText() + ")");
        }
        if (irx != null && dgs3mo != null) {
            ObjectNode cross = block.putObject("cross_check");
            putNumber(cross, "irx", irx);
            putNumber(cross, "dgs3mo", dgs3mo);
            putNumber(cross, "delta_pct_pts", ComputeMath.round2(irx - dgs3mo));
        } else {
            block.putNull("cross_check");
        }
        block.put("note", "idle-cash opportunity cost — what dry powder earns risk-free while unallocated");
    }

    private ArrayNode arrayOrEmpty(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : json.createArrayNode();
    }

    private static ArrayNode array(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : null;
    }

    private static ObjectNode object(JsonNode value) {
        return value != null && value.isObject() ? (ObjectNode) value : null;
    }

    private static boolean nonEmpty(ArrayNode value) {
        return value != null && !value.isEmpty();
    }

    private static JsonNode at(JsonNode value, String... path) {
        JsonNode current = value;
        for (String key : path) {
            if (current == null || current.isNull() || !current.isContainerNode()) {
                return null;
            }
            current = current.get(key);
        }
        return current;
    }

    private static Double nullableNumber(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        double number = ComputeMath.jsNumber(value);
        return Double.isFinite(number) ? number : null;
    }

    private static double number(JsonNode value) {
        return ComputeMath.jsNumber(value);
    }

    private static void putNumber(ObjectNode target, String key, double value) {
        target.set(key, ComputeMath.normalizedNumberNode(value));
    }
}
