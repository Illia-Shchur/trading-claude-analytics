package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.array;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static org.assertj.core.api.Assertions.assertThat;

class LegacyResearchV1NodeOracleTest {
    @Test
    void publicApiCoversEveryStrategyResearchLibExport() {
        Set<String> methods = Arrays.stream(LegacyResearchV1.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertThat(methods).contains(
                "stable", "hash", "readJSON", "jsonBytes", "jsonlBytes", "readJSONL",
                "validateFeatureContract", "validateDefinition", "validateExperiment",
                "validateCandidateSet", "validateRun", "expandGrid", "accountCandidates",
                "buildCandidateSet", "compactMetrics", "makeRunBundle", "runExperiment",
                "writeImmutable", "writeRunBundle", "validateRunDirectory", "rebuildIndex",
                "validateRegistry", "compactLegacy");
        assertThat(LegacyResearchV1.REGISTRY_SCHEMA).isEqualTo("strategy-research-index/1");
        assertThat(LegacyResearchV1.DEFINITION_SCHEMA).isEqualTo("strategy-definition/1");
        assertThat(LegacyResearchV1.EXPERIMENT_SCHEMA).isEqualTo("strategy-experiment/1");
        assertThat(LegacyResearchV1.CANDIDATE_SET_SCHEMA)
                .isEqualTo("strategy-candidate-set/1");
        assertThat(LegacyResearchV1.RUN_SCHEMA).isEqualTo("strategy-run/1");
        assertThat(LegacyResearchV1.LEGACY_SOURCES).hasSize(17);
    }

    @Test
    void stableHashGridAndMetricCompactionMatchCapturedContract() {
        ObjectNode value = object().put("z", -0.0).put("unicode", "é");
        value.set("a", array().add(3).addNull().add(true));
        assertThat(LegacyResearchV1.stable(value))
                .isEqualTo("{\"a\":[3,null,true],\"unicode\":\"é\",\"z\":0}");
        assertThat(LegacyResearchV1.hash(value)).matches("[0-9a-f]{64}");

        ObjectNode template = object().put("id_template", "candidate-{n}")
                .put("framework", "fallen_knives");
        ObjectNode grid = object();
        grid.set("risk.stop_pct", array().add(4).add(6));
        grid.set("score", array().add(8).add(10));
        ArrayNode expanded = LegacyResearchV1.expandGrid(template, grid);
        assertThat(expanded).hasSize(4);
        assertThat(expanded.get(0).path("id").asText()).isEqualTo("candidate-0001");
        assertThat(expanded.get(0).path("risk").path("stop_pct").asInt()).isEqualTo(4);
        assertThat(expanded.get(3).path("score").asInt()).isEqualTo(10);

        ObjectNode metrics = object()
                .put("attempted", 13).put("trades", 8).put("mean_r", 0.25)
                .put("profit_factor", 1.4).put("net_return_pct", 9.5)
                .put("expectancy_bootstrap_p20", -0.1);
        ObjectNode compact = LegacyResearchV1.compactMetrics(metrics);
        assertThat(compact.path("attempted_trades").asInt()).isEqualTo(13);
        assertThat(compact.path("completed_trades").asInt()).isEqualTo(8);
        assertThat(compact.path("expectancy_r").asDouble()).isEqualTo(.25);
        assertThat(compact.path("bootstrap_p20_expectancy_r").asDouble()).isEqualTo(-.1);
    }

    @Test
    void candidateAccountingDeduplicatesEquivalentBehavior() {
        ObjectNode first = object().put("id", "a").put("framework", "fallen_knives")
                .put("phase", "1A").put("setup_family", "FK_HIGHER_LOW")
                .put("stop_pct", 5).put("target_r", 1).put("partial_exit_pct", 0);
        ObjectNode alias = first.deepCopy().put("id", "b");
        ArrayNode candidates = array().add(first).add(alias);
        ArrayNode series = array().add(object().put("series_id", "btc|4h")
                .put("asset", "btc").put("timeframe", "4h")
                .put("framework", "fallen_knives").putNull("channel"));
        ObjectNode accounting = LegacyResearchV1.accountCandidates(candidates, series);
        assertThat(accounting.path("declared_k").asInt()).isEqualTo(2);
        assertThat(accounting.path("effective_k").asInt()).isEqualTo(1);
        assertThat(accounting.path("candidates")).hasSize(1);
        assertThat(accounting.path("declared_sha256").asText()).matches("[0-9a-f]{64}");
        assertThat(accounting.path("per_series").get(0).path("effective_k").asInt())
                .isEqualTo(1);
    }

    @Test
    void serializedBytesAreExactlyNodeStyle() {
        ObjectNode value = object().put("a", 1).put("b", "x");
        assertThat(new String(LegacyResearchV1.jsonBytes(value), StandardCharsets.UTF_8))
                .isEqualTo("{\n  \"a\": 1,\n  \"b\": \"x\"\n}\n");
        ArrayNode rows = array().add(value).add(object().put("c", true));
        assertThat(new String(LegacyResearchV1.jsonlBytes(rows), StandardCharsets.UTF_8))
                .isEqualTo("{\"a\":1,\"b\":\"x\"}\n{\"c\":true}\n");
    }
}
