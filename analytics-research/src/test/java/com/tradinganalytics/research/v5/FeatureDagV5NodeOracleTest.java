package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class FeatureDagV5NodeOracleTest {
    @Test
    void graphConstructionEvaluationPlanningAndEvidenceMatchNodeExactly() throws Exception {
        ObjectNode options = graphOptions();
        ObjectNode graph = FeatureDagV5.makeFeatureGraphV5(options);
        assertJson(graph, oracle(request("make").set("options", options)));
        assertThat(FeatureDagV5.validateFeatureGraphV5(graph)).isTrue();
        assertThat(FeatureDagV5.validateFeatureLineageV5(graph)).isTrue();
        assertThat(FeatureDagV5.FEATURE_DAG_SCHEMA).isEqualTo("strategy-v5-feature-dag/1");
        assertThat(FeatureDagV5.FEATURE_DAG_CODE_SHA256)
                .isEqualTo("f14c3dc00b7fd8b2fc46b3938127e90b231c8e1ffa123c4ec6714936ed53b428");

        ObjectNode evaluation = JsonHashes.mapper().createObjectNode();
        evaluation.set("rows", rows());
        ObjectNode evaluateRequest = request("evaluate"); evaluateRequest.set("graph", graph); evaluateRequest.set("options", evaluation);
        ObjectNode actualEvaluation = FeatureDagV5.evaluateFeatureGraphV5(graph, evaluation);
        assertJson(actualEvaluation, oracle(evaluateRequest));
        assertJson(FeatureDagV5.evaluateFeatureDagV5(graph, evaluation), actualEvaluation);

        ObjectNode registry = JsonHashes.mapper().createObjectNode();
        registry.putObject("primary").put("timeframe", "1m");
        ObjectNode planOptions = JsonHashes.mapper().createObjectNode(); planOptions.set("graph", graph);
        planOptions.set("sourceRegistry", registry); planOptions.putNull("precommit_sha256").putNull("config_sha256");
        ObjectNode planRequest = request("plan"); planRequest.set("options", planOptions);
        ObjectNode actualPlan = FeatureDagV5.planFeatureGraphV5(planOptions);
        assertJson(actualPlan, oracle(planRequest));
        assertJson(FeatureDagV5.deriveFeatureRequirementsV5(planOptions), actualPlan);

        ObjectNode dedupeOptions = JsonHashes.mapper().createObjectNode(); dedupeOptions.set("graph", graph);
        dedupeOptions.putObject("scores").put("z", 2).put("ema", 1).put("atr", 3);
        ObjectNode dedupeRequest = request("dedupe"); dedupeRequest.set("options", dedupeOptions);
        assertJson(FeatureDagV5.dedupeEvidenceVotesV5(dedupeOptions), oracle(dedupeRequest));
        assertThat(FeatureDagV5.assertTradeableFeatureGraphV5(graph)).isTrue();
    }

    @Test
    void joinsAndPortableIndicatorCheckpointsMatchNodeExactly() throws Exception {
        ObjectNode joinOptions = JsonHashes.mapper().createObjectNode();
        joinOptions.set("series", rows());
        joinOptions.putArray("decisions").add(instant(2)).add(instant(5)).add(instant(20));
        joinOptions.put("includeCurrent", false).put("maxStalenessMs", 180_000).put("gapPolicy", "NULL");
        ObjectNode joinRequest = request("join"); joinRequest.set("options", joinOptions);
        assertJson(FeatureDagV5.pointInTimeJoinV5(joinOptions), oracle(joinRequest));
        assertJson(FeatureDagV5.joinPointInTimeV5(joinOptions), FeatureDagV5.pointInTimeJoinV5(joinOptions));

        ArrayNode values = JsonHashes.mapper().createArrayNode();
        for (int index = 0; index < 20; index++) values.add(100 + Math.sin(index / 3d) * 4 + index / 10d);
        ObjectNode emaOptions = JsonHashes.mapper().createObjectNode().put("period", 5).put("minHistory", 5);
        ObjectNode emaRequest = request("ema"); emaRequest.set("values", values); emaRequest.set("options", emaOptions);
        ObjectNode fullEma = FeatureDagV5.resumeRecursiveEmaV5(values, emaOptions);
        assertJson(fullEma, oracle(emaRequest));
        assertSplitResume(values, 11, emaOptions, true, fullEma);

        ObjectNode rsiOptions = JsonHashes.mapper().createObjectNode().put("period", 5);
        ObjectNode rsiRequest = request("rsi"); rsiRequest.set("values", values); rsiRequest.set("options", rsiOptions);
        ObjectNode fullRsi = FeatureDagV5.resumeWilderRsiV5(values, rsiOptions);
        assertJson(fullRsi, oracle(rsiRequest));
        assertSplitResume(values, 11, rsiOptions, false, fullRsi);
    }

    @Test
    void labelCyclesContextOnlyAndCheckpointMismatchesFailClosed() {
        ObjectNode label = JsonHashes.mapper().createObjectNode().put("fixtureOnly", true);
        label.putArray("nodes").addObject().put("id", "bad").put("op", "FIELD").put("source_field", "future_return");
        assertThatThrownBy(() -> FeatureDagV5.makeFeatureGraphV5(label)).hasMessageContaining("label/outcome");

        ObjectNode cycle = JsonHashes.mapper().createObjectNode().put("fixtureOnly", true);
        ArrayNode nodes = cycle.putArray("nodes");
        nodes.addObject().put("id", "a").put("op", "ABS").putArray("inputs").add("b");
        nodes.addObject().put("id", "b").put("op", "ABS").putArray("inputs").add("a");
        assertThatThrownBy(() -> FeatureDagV5.makeFeatureGraphV5(cycle)).hasMessageContaining("cycle");

        ObjectNode context = JsonHashes.mapper().createObjectNode().put("fixtureOnly", true);
        context.putArray("nodes").addObject().put("id", "macro").put("op", "FIELD")
                .put("source_field", "close").put("context_only", true);
        ObjectNode contextGraph = FeatureDagV5.makeFeatureGraphV5(context);
        assertThatThrownBy(() -> FeatureDagV5.assertTradeableFeatureGraphV5(contextGraph)).hasMessageContaining("CONTEXT_ONLY");

        ObjectNode wrongState = JsonHashes.mapper().createObjectNode().put("period", 3).put("min_history", 3);
        ObjectNode ema = JsonHashes.mapper().createObjectNode().put("period", 5).put("minHistory", 5); ema.set("state", wrongState);
        assertThatThrownBy(() -> FeatureDagV5.resumeRecursiveEmaV5(JsonHashes.mapper().createArrayNode().add(1), ema))
                .hasMessageContaining("checkpoint period mismatch");
        ObjectNode rsi = JsonHashes.mapper().createObjectNode().put("period", 5); rsi.set("state", wrongState);
        assertThatThrownBy(() -> FeatureDagV5.resumeWilderRsiV5(JsonHashes.mapper().createArrayNode().add(1), rsi))
                .hasMessageContaining("checkpoint period mismatch");
    }

    private static ObjectNode graphOptions() {
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("fixtureOnly", true).put("graph_id", "all-ops");
        ArrayNode nodes = options.putArray("nodes");
        field(nodes, "a", "a", "x", "physical-a"); field(nodes, "b", "b", "x", "physical-b");
        field(nodes, "high", "high", "price", "physical-high"); field(nodes, "low", "low", "price", "physical-low");
        field(nodes, "close", "close", "price", "physical-close");
        unary(nodes, "lag", "LAG", "a").put("lag_bars", 2);
        binary(nodes, "diff", "DIFF", "a", "b"); binary(nodes, "pct", "PCT_RETURN", "a", "b");
        binary(nodes, "logret", "LOG_RETURN", "a", "b"); binary(nodes, "add", "ADD", "a", "b");
        binary(nodes, "sub", "SUB", "a", "b"); binary(nodes, "mul", "MUL", "a", "b");
        binary(nodes, "div", "DIV", "a", "b"); unary(nodes, "abs", "ABS", "diff"); unary(nodes, "log", "LOG", "a");
        ObjectNode clamp = nodes.addObject().put("id", "clamp").put("op", "CLAMP").put("min", 0).put("max", 200);
        clamp.putArray("inputs").add("a").add(0).add(200);
        for (String op : List.of("SMA", "EMA", "SUM", "MIN", "MAX", "MEDIAN", "QUANTILE", "PERCENTILE_RANK",
                "STDDEV", "VOL", "ZSCORE", "ROBUST_ZSCORE", "WINSORIZE", "SLOPE")) {
            ObjectNode node = unary(nodes, op.toLowerCase(), op, "a").put("lookback_bars", 4).put("min_history", 2);
            if (op.equals("QUANTILE")) node.put("quantile", .75);
        }
        ObjectNode tr = nodes.addObject().put("id", "tr").put("op", "TRUE_RANGE");
        tr.putArray("inputs").add("high").add("low").add("close");
        unary(nodes, "atr", "ATR", "tr").put("lookback_bars", 3).put("min_history", 2);
        for (String op : List.of("COVARIANCE", "CORRELATION", "BETA")) {
            binary(nodes, op.toLowerCase(), op, "a", "b").put("lookback_bars", 4).put("min_history", 2);
        }
        for (String op : List.of("RATIO", "SPREAD", "RELATIVE_RETURN", "BASIS")) binary(nodes, op.toLowerCase(), op, "a", "b");
        for (String op : List.of("EQ", "NE", "GT", "GTE", "LT", "LTE")) binary(nodes, op.toLowerCase(), op, "a", "b");
        binary(nodes, "and", "AND", "gt", "lt"); binary(nodes, "or", "OR", "gt", "lt"); unary(nodes, "not", "NOT", "gt");
        unary(nodes, "isnull", "IS_NULL", "lag");
        ObjectNode conditional = nodes.addObject().put("id", "conditional").put("op", "IF");
        conditional.putArray("inputs").add("gt").add("a").add("b");
        binary(nodes, "crossup", "CROSS_ABOVE", "a", "b"); binary(nodes, "crossdown", "CROSS_BELOW", "a", "b");
        unary(nodes, "rsi", "RSI", "a").put("lookback_bars", 4);
        ArrayNode outputs = options.putArray("outputs");
        for (JsonNode node : nodes) if (!node.path("op").asText().equals("FIELD")) outputs.add(node.path("id").asText());
        return options;
    }

    private static void field(ArrayNode nodes, String id, String source, String unit, String physical) {
        nodes.addObject().put("id", id).put("op", "FIELD").put("source_field", source).put("unit", unit)
                .put("physical_evidence_id", physical);
    }

    private static ObjectNode unary(ArrayNode nodes, String id, String op, String input) {
        ObjectNode node = nodes.addObject().put("id", id).put("op", op); node.putArray("inputs").add(input); return node;
    }

    private static ObjectNode binary(ArrayNode nodes, String id, String op, String first, String second) {
        ObjectNode node = nodes.addObject().put("id", id).put("op", op); node.putArray("inputs").add(first).add(second); return node;
    }

    private static ArrayNode rows() {
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        for (int index = 0; index < 16; index++) {
            double a = 100 + Math.sin(index / 2d) * 3 + index / 5d, b = 101 + Math.cos(index / 3d) * 2;
            rows.addObject().put("event_time", instant(index)).put("availability_time", instant(index) )
                    .put("a", a).put("b", b).put("high", a + 2).put("low", a - 2).put("close", a + .5);
        }
        return rows;
    }

    private static void assertSplitResume(ArrayNode values, int split, ObjectNode options, boolean ema, ObjectNode full) {
        ArrayNode firstValues = JsonHashes.mapper().createArrayNode(), secondValues = JsonHashes.mapper().createArrayNode();
        for (int index = 0; index < values.size(); index++) (index < split ? firstValues : secondValues).add(values.get(index));
        ObjectNode first = ema ? FeatureDagV5.resumeRecursiveEmaV5(firstValues, options)
                : FeatureDagV5.resumeWilderRsiV5(firstValues, options);
        ObjectNode resumedOptions = options.deepCopy(); resumedOptions.set("state", first.path("state"));
        ObjectNode second = ema ? FeatureDagV5.resumeRecursiveEmaV5(secondValues, resumedOptions)
                : FeatureDagV5.resumeWilderRsiV5(secondValues, resumedOptions);
        ArrayNode combined = JsonHashes.mapper().createArrayNode(); first.path("values").forEach(combined::add); second.path("values").forEach(combined::add);
        assertJson(combined, full.path("values"));
    }

    private static ObjectNode request(String op) { return JsonHashes.mapper().createObjectNode().put("op", op); }
    private static String instant(int minute) { return Instant.parse("2026-01-01T00:00:00Z").plusSeconds(minute * 60L).toString(); }

    private static JsonNode oracle(ObjectNode request) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                FeatureDagV5NodeOracleTest.class.getResourceAsStream(
                        "/oracles/feature-dag-v5.json"),
                "frozen feature-DAG oracle is missing")) {
            JsonNode value = JsonHashes.mapper().readTree(input)
                    .get(request.path("op").asText());
            return Objects.requireNonNull(value,
                    "frozen feature-DAG operation is missing: "
                            + request.path("op").asText()).deepCopy();
        }
    }

    private static void assertJson(JsonNode actual, JsonNode expected) {
        assertThat(CanonicalJson.canonicalize(actual)).isEqualTo(CanonicalJson.canonicalize(expected));
    }

}
