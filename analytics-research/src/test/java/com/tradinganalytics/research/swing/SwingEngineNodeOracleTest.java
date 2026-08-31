package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SwingEngineNodeOracleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-22T12:34:56.789Z"), ZoneOffset.UTC);
    static Stream<String> candidates() {
        return Stream.of(
                "{\"framework\":\"fallen_knives\",\"phase\":\"1A\",\"setup_family\":\"FK_HIGHER_LOW\"}",
                "{\"id\":\"fr\",\"framework\":\"flying_rocket\",\"channel\":\"B\",\"phase\":\"2\",\"stop_atr_multiple\":1.5,\"stop_min_pct\":2,\"stop_max_pct\":8,\"factor_filters\":[{\"path\":\"factors.macro.dxy\",\"op\":\"between\",\"value\":[99,102]}],\"active_from\":\"2026-01-01\",\"excluded_score_legs\":[\"macro\"]}",
                "{\"id\":\"fk-custom\",\"framework\":\"fallen_knives\",\"score_threshold\":6.5,\"min_state\":1,\"assets\":[\"BTC\"],\"timeframe\":\"4H\",\"max_hold_bars\":7,\"partial_exit_pct\":0.4,\"partial_target_r\":0.75}"
        );
    }

    @ParameterizedTest
    @MethodSource("candidates")
    void candidateNormalizationMatchesNode(String json) throws Exception {
        JsonNode candidate = MAPPER.readTree(json);
        JsonNode oracle = oracle(object("op", "normalize").set("candidate", candidate));
        assertCanonicalEquals(SwingEngine.normalizeCandidate(candidate), oracle);
    }

    @Test
    void featureStoreBuildDecodeAndHashMatchNode() throws Exception {
        ObjectNode request = object("op", "build").set("input", featureInput());
        request.set("options", object("source", "fixture").put("pointInTimeSafe", true));
        ObjectNode oracle = (ObjectNode) oracle(request);
        ObjectNode java = SwingEngine.buildFeatureStore(featureInput(), request.get("options"), FIXED);
        oracle.put("created_at", java.path("created_at").asText());
        assertCanonicalEquals(java, oracle);
        assertCanonicalEquals(SwingEngine.decodeFeatureStore(java), oracle(request("decode", "store", java)));
        assertThat(SwingEngine.verifyFeatureStoreHash(java)).isEqualTo(oracle(request("verify-store", "store", java)).asBoolean());
    }

    @Test
    void matchingSimulationMetricsEvaluationAndIntentMatchNode() throws Exception {
        ArrayNode rows = SwingEngine.decodeFeatureStore(SwingEngine.buildFeatureStore(featureInput(), object("source", "fixture"), FIXED));
        JsonNode candidate = MAPPER.readTree("{\"id\":\"fk\",\"framework\":\"fallen_knives\",\"phase\":\"1A\",\"setup_family\":\"FK_HIGHER_LOW\",\"stop_pct\":6,\"target_r\":1,\"max_hold_bars\":2,\"partial_exit_pct\":0,\"fee_pct\":0.1,\"slippage_pct\":0.05}");
        assertCanonicalEquals(MAPPER.valueToTree(SwingEngine.candidateMatches(rows.get(0), candidate)),
                oracle(request("matches", "row", rows.get(0), "candidate", candidate)));

        ObjectNode normalized = SwingEngine.normalizeCandidate(candidate);
        ObjectNode signal = ((ObjectNode) rows.get(0)).deepCopy().put("signal_id", "s").put("setup_family_id", "setup").put("setup_family", "FK_HIGHER_LOW");
        ObjectNode options = object("same_bar_collision", "stop-first"); options.set("signal", signal);
        assertCanonicalEquals(SwingEngine.simulateTrade(rows, 0, normalized, options),
                oracle(request("simulate", "rows", rows, "signalIndex", JSON_NUMBER_ZERO, "candidate", normalized, "options", options)));

        ObjectNode metricOptions = object("rawSetupBars", 3).put("uniqueSignals", 2).put("candidateCount", 7).put("periodMs", 30L * 86_400_000);
        ArrayNode trades = MAPPER.createArrayNode().add(SwingEngine.simulateTrade(rows, 0, normalized, options));
        assertCanonicalEquals(SwingEngine.tradeMetrics(trades, metricOptions),
                oracle(request("metrics", "trades", trades, "options", metricOptions)));
        ObjectNode evaluationOptions = object("candidate_count", 1).put("bootstrap_rounds", 25);
        assertCanonicalEquals(SwingEngine.evaluateCandidate(rows, candidate, evaluationOptions),
                oracle(request("evaluate", "rows", rows, "candidate", candidate, "options", evaluationOptions)));
        assertCanonicalEquals(SwingEngine.candidateSignalIntent(rows, candidate),
                oracle(request("intent", "rows", rows, "candidate", candidate)));
    }

    @Test
    void insufficientWalkForwardAndShadowRunMatchNode() throws Exception {
        ArrayNode rows = SwingEngine.decodeFeatureStore(SwingEngine.buildFeatureStore(featureInput(), object("source", "fixture"), FIXED));
        ArrayNode candidates = MAPPER.createArrayNode().add(MAPPER.readTree("{\"id\":\"fk\",\"framework\":\"fallen_knives\",\"phase\":\"1A\",\"setup_family\":\"FK_HIGHER_LOW\",\"stop_pct\":6,\"target_r\":1,\"max_hold_bars\":2,\"partial_exit_pct\":0}"));
        ObjectNode walkOptions = object("minMonths", 12);
        assertCanonicalEquals(SwingEngine.walkForward(rows, candidates, walkOptions),
                oracle(request("walk", "rows", rows, "candidates", candidates, "options", walkOptions)));
        ObjectNode runOptions = MAPPER.createObjectNode().put("skip_validation", true).put("bootstrap_rounds", 10).put("minTrades", 1).put("minRegimes", 1);
        ObjectNode java = SwingEngine.runResearch(rows, candidates, runOptions, FIXED);
        ObjectNode node = (ObjectNode) oracle(request("run", "rows", rows, "candidates", candidates, "options", runOptions));
        node.put("generated_at", java.path("generated_at").asText());
        assertCanonicalEquals(java, node);
        assertThat(SwingEngine.verifyRunHash(java)).isTrue();
    }

    @Test
    void strategyRankingRenderingRunVerificationAndHashingMatchNode() throws Exception {
        ArrayNode rows = SwingEngine.decodeFeatureStore(SwingEngine.buildFeatureStore(featureInput(), object("source", "fixture"), FIXED));
        JsonNode candidate = MAPPER.readTree("{\"id\":\"fk\",\"framework\":\"fallen_knives\",\"phase\":\"1A\",\"setup_family\":\"FK_HIGHER_LOW\",\"stop_pct\":6,\"target_r\":1,\"max_hold_bars\":2,\"partial_exit_pct\":0}");
        ArrayNode components = MAPPER.createArrayNode().add(candidate);
        ObjectNode options = MAPPER.createObjectNode().put("candidate_count", 1).put("bootstrap_rounds", 20);
        assertCanonicalEquals(SwingEngine.evaluateStrategy(rows, components, options),
                oracle(request("strategy", "rows", rows, "components", components, "options", options)));
        ObjectNode report = SwingEngine.evaluateCandidate(rows, candidate, options);
        ArrayNode reports = MAPPER.createArrayNode().add(report);
        ObjectNode rankOptions = MAPPER.createObjectNode().put("minTrades", 1).put("minRegimes", 1);
        assertCanonicalEquals(SwingEngine.rankCandidates(reports, rankOptions),
                oracle(request("rank", "reports", reports, "options", rankOptions)));

        ArrayNode candidates = MAPPER.createArrayNode().add(candidate);
        ObjectNode runOptions = MAPPER.createObjectNode().put("skip_validation", true).put("bootstrap_rounds", 10);
        ObjectNode run = SwingEngine.runResearch(rows, candidates, runOptions, FIXED);
        assertCanonicalEquals(MAPPER.valueToTree(SwingEngine.renderSummary(run)), oracle(request("render", "result", run)));
        assertCanonicalEquals(MAPPER.valueToTree(SwingEngine.verifyRunHash(run)), oracle(request("verify-run", "result", run)));
        assertCanonicalEquals(MAPPER.valueToTree(SwingEngine.sha256("abc")), oracle(request("sha256", "value", MAPPER.getNodeFactory().textNode("abc"))));
        ObjectNode hashObject = MAPPER.createObjectNode().put("b", 2).put("a", 1);
        assertCanonicalEquals(MAPPER.valueToTree(SwingEngine.sha256(hashObject)), oracle(request("sha256", "value", hashObject)));
    }

    @Test
    void completeWalkForwardFoldsHoldoutGateAndSealMatchNode() throws Exception {
        ArrayNode rows = monthlyRows(15);
        JsonNode candidate = MAPPER.readTree("{\"id\":\"fk\",\"framework\":\"fallen_knives\",\"phase\":\"1A\",\"setup_family\":\"FK_HIGHER_LOW\",\"stop_pct\":6,\"target_r\":1,\"max_hold_bars\":2,\"partial_exit_pct\":0}");
        ArrayNode candidates = MAPPER.createArrayNode().add(candidate);
        ObjectNode options = MAPPER.createObjectNode().put("minMonths", 12).put("holdoutMonths", 3).put("foldMonths", 3)
                .put("developmentMonths", 6).put("minTrades", 1).put("minRegimes", 1).put("holdoutMinOosTrades", 1)
                .put("holdoutMinPositiveFolds", 1).put("bootstrap_rounds", 20).put("sealed_holdout_token", "token")
                .put("sealed_holdout_hash", "wrong");
        ObjectNode java = SwingEngine.walkForward(rows, candidates, options);
        ObjectNode node = (ObjectNode) oracle(request("walk", "rows", rows, "candidates", candidates, "options", options));
        assertCanonicalEquals(java, node);
        assertThat(java.path("status").asText()).isEqualTo("OK");
        assertThat(java.path("holdout").path("seal").path("error").asText()).isEqualTo("HASH_MISMATCH");
    }

    private static ObjectNode featureInput() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"point_in_time_safe":true,"datasets":[{"asset":"btc","timeframe":"4h","framework":"fallen_knives","channel":null,"features":[
                  {"time":1700000000000,"open":100,"high":101,"low":99,"close":100,"volume":5,"mechanical_score":10,"flow_aligned_rows":2,"flow_coverage":"COMPLETE","setup_family":"FK_HIGHER_LOW","trigger":{"valid":true,"completed_bar":true,"timeframe":"4h","age_bars":0},"regime":"RANGE"},
                  {"time":1700014400000,"open":100,"high":107,"low":99,"close":106,"volume":7,"mechanical_score":0,"flow_aligned_rows":0,"setup_family":"NONE","trigger":{"valid":false,"completed_bar":true,"timeframe":"4h","age_bars":0},"regime":"RANGE"},
                  {"time":1700028800000,"open":106,"high":108,"low":105,"close":107,"volume":8,"mechanical_score":0,"flow_aligned_rows":0,"setup_family":"NONE","trigger":{"valid":false,"completed_bar":true,"timeframe":"4h","age_bars":0},"regime":"RANGE"}
                ]}]}
                """);
    }

    private static ArrayNode monthlyRows(int months) {
        ArrayNode rows = MAPPER.createArrayNode();
        LocalDate month = LocalDate.of(2024, 1, 1);
        for (int index = 0; index < months; index++) {
            long time = month.plusMonths(index).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            ObjectNode signal = MAPPER.createObjectNode().put("asset", "btc").put("timeframe", "4h").put("framework", "fallen_knives")
                    .putNull("channel").put("time", time).put("available_at", time + SwingEngine.BAR_MS).put("open", 100).put("high", 101)
                    .put("low", 99).put("close", 100).put("mechanical_score", 10).put("flow_aligned_rows", 2).put("flow_coverage", "COMPLETE")
                    .put("setup_family", "FK_HIGHER_LOW").put("regime", index % 2 == 0 ? "RANGE" : "TREND_DOWN");
            signal.putArray("setup_families").add("FK_HIGHER_LOW");
            signal.set("trigger", MAPPER.createObjectNode().put("valid", true).put("completed_bar", true).put("timeframe", "4h").put("age_bars", 0));
            signal.set("legs", MAPPER.createObjectNode()); signal.set("state_legs", MAPPER.createObjectNode()); signal.set("impulse_legs", MAPPER.createObjectNode());
            ObjectNode entry = signal.deepCopy().put("time", time + SwingEngine.BAR_MS).put("available_at", time + 2 * SwingEngine.BAR_MS)
                    .put("open", 100).put("high", 107).put("low", 99).put("close", 106).put("mechanical_score", 0).put("flow_aligned_rows", 0)
                    .put("setup_family", "NONE");
            entry.putArray("setup_families").add("NONE"); entry.set("trigger", MAPPER.createObjectNode().put("valid", false).put("completed_bar", true).put("timeframe", "4h").put("age_bars", 0));
            rows.add(signal).add(entry);
        }
        return rows;
    }

    private static JsonNode oracle(ObjectNode request) throws Exception {
        String key = JsonHashes.canonicalSha256(request);
        try (InputStream input = Objects.requireNonNull(
                SwingEngineNodeOracleTest.class.getResourceAsStream(
                        "/oracles/swing-engine-v1.json"),
                "frozen swing-engine oracle is missing")) {
            JsonNode response = MAPPER.readTree(input).get(key);
            assertThat(response).as("missing frozen swing-engine oracle for " + key)
                    .isNotNull();
            return response.deepCopy();
        }
    }

    private static void assertCanonicalEquals(JsonNode actual, JsonNode expected) {
        String difference = firstDifference(actual, expected, "$");
        assertThat(difference).isNull();
    }

    private static String firstDifference(JsonNode actual, JsonNode expected, String path) {
        if (actual == null || expected == null) return actual == expected ? null : path + ": one value is absent";
        if (actual.isNumber() && expected.isNumber()) return CanonicalJson.canonicalize(actual).equals(CanonicalJson.canonicalize(expected))
                ? null : path + ": " + actual + " != " + expected;
        if (actual.getNodeType() != expected.getNodeType()) return path + ": " + actual.getNodeType() + " != " + expected.getNodeType();
        if (actual.isObject()) {
            java.util.Set<String> names = new java.util.LinkedHashSet<>(); actual.fieldNames().forEachRemaining(names::add); expected.fieldNames().forEachRemaining(names::add);
            for (String name : names) { String difference = firstDifference(actual.get(name), expected.get(name), path + '.' + name); if (difference != null) return difference; }
            return null;
        }
        if (actual.isArray()) {
            if (actual.size() != expected.size()) return path + ": size " + actual.size() + " != " + expected.size();
            for (int index = 0; index < actual.size(); index++) { String difference = firstDifference(actual.get(index), expected.get(index), path + '[' + index + ']'); if (difference != null) return difference; }
            return null;
        }
        return actual.equals(expected) ? null : path + ": " + actual + " != " + expected;
    }

    private static ObjectNode object(String key, String value) { return MAPPER.createObjectNode().put(key, value); }
    private static ObjectNode object(String key, int value) { return MAPPER.createObjectNode().put(key, value); }
    private static final JsonNode JSON_NUMBER_ZERO = MAPPER.getNodeFactory().numberNode(0);
    private static ObjectNode request(String op, Object... fields) {
        ObjectNode out = MAPPER.createObjectNode().put("op", op);
        for (int index = 0; index < fields.length; index += 2) out.set((String) fields[index], ((JsonNode) fields[index + 1]).deepCopy());
        return out;
    }
}
