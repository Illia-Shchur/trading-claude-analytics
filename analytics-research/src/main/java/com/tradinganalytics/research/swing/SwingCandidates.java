package com.tradinganalytics.research.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import java.io.PrintStream;
import java.util.List;

/** Frozen declarative candidate catalog from {@code tools/swing-candidates.mjs}. */
public final class SwingCandidates {
    public static final String SCHEMA = "swing-candidates/1";
    public static final String FROZEN_AT = "2026-08-22";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private record Spec(String slug, String family, double threshold) {}

    private static final List<Spec> FK = List.of(
            new Spec("deleveraging-absorption", "FK_DELEVERAGING_ABSORPTION", 4.5),
            new Spec("funding-flush-reclaim", "FK_FUNDING_FLUSH_RECLAIM", 4.5),
            new Spec("spot-absorption", "FK_SPOT_ABSORPTION", 3.5),
            new Spec("volatility-exhaustion", "FK_VOLATILITY_EXHAUSTION", 5));
    private static final List<Spec> FRA = List.of(
            new Spec("leveraged-rejection", "FR_A_LEVERAGED_REJECTION", 7.5),
            new Spec("cvd-distribution", "FR_A_CVD_DISTRIBUTION", 5.5),
            new Spec("top-crowding", "FR_A_TOP_CROWDING", 7));
    private static final List<Spec> FRB = List.of(
            new Spec("rally-failure", "FR_B_RALLY_FAILURE", 5.5),
            new Spec("breakdown-expansion", "FR_B_BREAKDOWN_EXPANSION", 8.5),
            new Spec("weak-spot-retest", "FR_B_WEAK_SPOT_RETEST", 5.5));

    private SwingCandidates() {}

    /** Returns a fresh mutable JSON tree on every call, exactly like the JS export. */
    public static ArrayNode marketContextCandidates() {
        ArrayNode out = JSON.arrayNode();
        individual(out, "fk", "fallen_knives", null, FK, List.of("RANGE", "TREND_DOWN"));
        individual(out, "fra", "flying_rocket", "A", FRA, List.of("RANGE", "TREND_UP"));
        individual(out, "frb", "flying_rocket", "B", FRB, List.of("RANGE", "TREND_DOWN"));

        List<String> fkFamilies = FK.stream().map(Spec::family).toList();
        List<String> fraFamilies = FRA.stream().map(Spec::family).toList();
        List<String> frbFamilies = FRB.stream().map(Spec::family).toList();
        out.add(candidate("fk-reclaim-anchor", "fallen_knives", null,
                List.of("FK_REVERSAL_RECLAIM", "FK_SUPPORT_RECLAIM"), 4, false,
                List.of("RANGE", "TREND_DOWN"), JSON.arrayNode(), 1));
        out.add(candidate("fk-flow-union-fast", "fallen_knives", null, fkFamilies, 4.5, false,
                List.of("RANGE", "TREND_DOWN"), JSON.arrayNode(), 0));
        out.add(candidate("fk-flow-union-swing", "fallen_knives", null, fkFamilies, 5.5, true,
                List.of("RANGE", "TREND_DOWN"), JSON.arrayNode(), 0));
        out.add(candidate("fra-flow-union-fast", "flying_rocket", "A", fraFamilies, 6, false,
                List.of("RANGE", "TREND_UP"), JSON.arrayNode(), 0));
        out.add(candidate("fra-flow-union-swing", "flying_rocket", "A", fraFamilies, 7, true,
                List.of("RANGE", "TREND_UP"), JSON.arrayNode(), 0));
        out.add(candidate("frb-flow-union-fast", "flying_rocket", "B", frbFamilies, 5.5, false,
                List.of("RANGE", "TREND_DOWN"), JSON.arrayNode(), 0));
        out.add(candidate("frb-flow-union-swing", "flying_rocket", "B", frbFamilies, 6.5, true,
                List.of("RANGE", "TREND_DOWN"), JSON.arrayNode(), 0));

        out.add(context("fk-flow-fear-context", "fallen_knives", null, fkFamilies, 4,
                List.of("RANGE", "TREND_DOWN"), filters(filter("factors.sentiment.fear_greed", "lte", 45))));
        out.add(context("fk-flow-sentiment-turn", "fallen_knives", null, fkFamilies, 4,
                List.of("RANGE", "TREND_DOWN"), filters(filter("factors.sentiment.fear_greed_3d_change", "gt", 0))));
        out.add(context("fk-flow-macro-tailwind", "fallen_knives", null, fkFamilies, 4,
                List.of("RANGE", "TREND_DOWN"), filters(
                        filter("factors.macro.dxy_3d_change_pct", "lte", 0),
                        filter("factors.macro.real_yield_3d_change", "lte", 0))));
        out.add(context("fra-flow-greed-context", "flying_rocket", "A", fraFamilies, 6,
                List.of("RANGE", "TREND_UP"), filters(filter("factors.sentiment.fear_greed", "gte", 55))));
        out.add(context("fra-flow-positioning-crowd", "flying_rocket", "A", fraFamilies, 6,
                List.of("RANGE", "TREND_UP"), filters(filter("factors.derivatives.global_account_z", "gte", 0.25))));
        out.add(context("fra-flow-macro-pressure", "flying_rocket", "A", fraFamilies, 6,
                List.of("RANGE", "TREND_UP"), filters(
                        filter("factors.macro.dxy_3d_change_pct", "gte", 0),
                        filter("factors.macro.real_yield_3d_change", "gte", 0))));
        out.add(context("frb-flow-sentiment-relief", "flying_rocket", "B", frbFamilies, 5,
                List.of("RANGE", "TREND_DOWN"), filters(filter("factors.sentiment.fear_greed_3d_change", "gt", 0))));
        out.add(context("frb-flow-long-reload", "flying_rocket", "B", frbFamilies, 5,
                List.of("RANGE", "TREND_DOWN"), filters(filter("factors.derivatives.taker_long_short_z", "gte", 0))));
        return out;
    }

    public static ObjectNode catalog() {
        return JSON.objectNode().put("schema", SCHEMA).put("frozen_at", FROZEN_AT)
                .set("candidates", marketContextCandidates());
    }

    /** Unregistered direct-command behavior; returns the process exit code. */
    public static int run(String[] arguments, PrintStream stdout, PrintStream stderr) {
        stdout.print(NodePrettyJson.write(catalog()));
        return 0;
    }

    private static ObjectNode context(String id, String framework, String channel, List<String> families,
            double threshold, List<String> regimes, ArrayNode filters) {
        return candidate(id, framework, channel, families, threshold, false, regimes, filters, 0);
    }

    private static void individual(ArrayNode out, String prefix, String framework, String channel,
            List<Spec> specs, List<String> regimes) {
        for (Spec spec : specs) {
            out.add(candidate(prefix + '-' + spec.slug() + "-fast", framework, channel,
                    List.of(spec.family()), spec.threshold(), false, regimes, JSON.arrayNode(), 0));
            out.add(candidate(prefix + '-' + spec.slug() + "-swing", framework, channel,
                    List.of(spec.family()), spec.threshold() + 1, true, regimes, JSON.arrayNode(), 0));
        }
    }

    private static ObjectNode candidate(String id, String framework, String channel, List<String> families,
            double threshold, boolean swing, List<String> regimes, ArrayNode filters, int minFlowAligned) {
        ObjectNode node = JSON.objectNode();
        node.put("id", id).put("framework", framework);
        node.set("channel", channel == null ? JSON.nullNode() : JSON.textNode(channel));
        node.put("direction", framework.equals("fallen_knives") ? "long" : "short").put("phase", "1A");
        node.set("setup_families", strings(families));
        putNumber(node, "score_threshold", threshold);
        node.set("factor_filters", filters.deepCopy());
        node.set("regimes", strings(regimes));
        node.put("min_flow_aligned", minFlowAligned).put("timeframe", "4h")
                .put("trigger_window_bars", 1).put("max_concurrent", 1);
        if (swing) {
            node.put("time_stop_bars", 36).put("stop_pct", 6).put("target_r", 1.5)
                    .put("partial_exit_pct", 0.5).put("partial_target_r", 0.75).put("ratchet_to_entry", true);
        } else {
            node.put("time_stop_bars", 18).put("stop_pct", 6).put("target_r", 1)
                    .put("partial_exit_pct", 0.5).put("partial_target_r", 0.75).put("ratchet_to_entry", false);
        }
        node.put("fee_pct", 0.10).put("slippage_pct", 0.05).put("funding_debit", true);
        return node;
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode out = JSON.arrayNode();
        values.forEach(out::add);
        return out;
    }

    private static ObjectNode filter(String path, String op, double value) {
        ObjectNode node = JSON.objectNode().put("path", path).put("op", op);
        putNumber(node, "value", value);
        return node;
    }

    private static ArrayNode filters(JsonNode... values) {
        ArrayNode out = JSON.arrayNode();
        for (JsonNode value : values) out.add(value);
        return out;
    }

    private static void putNumber(ObjectNode node, String field, double value) {
        if (value == Math.rint(value)) node.put(field, (long) value); else node.put(field, value);
    }
}
