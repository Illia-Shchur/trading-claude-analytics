package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

final class StrategyEvaluatorV5SecurityPropertyTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @Test
    void publicApiCoversEveryFrozenExportAndWorkerCapability() throws Exception {
        Set<String> functionExports = Set.of(
                "rebasePhysicalNullExecutionV5", "makeEvaluatorSpecV5", "validateEvaluatorSpecV5",
                "evaluateSignalPredicateV5", "createFixtureEvaluatorV5", "loadAuthoritativeEvaluatorV5",
                "createVerifiedWorkerEvaluatorV5");
        Set<String> constantExports = Set.of(
                "STRATEGY_EVALUATOR_V5_CODE_SHA256", "STRATEGY_EVALUATOR_V5_WORKER_CODE_SHA256");

        Set<String> publicMethods = Arrays.stream(StrategyEvaluatorV5.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertThat(publicMethods).containsAll(functionExports);
        for (String constant : constantExports) {
            assertThat(Modifier.isPublic(StrategyEvaluatorV5.class.getDeclaredField(constant).getModifiers()))
                    .isTrue();
            assertThat(Modifier.isStatic(StrategyEvaluatorV5.class.getDeclaredField(constant).getModifiers()))
                    .isTrue();
        }
        assertThat(StrategyEvaluatorV5.STRATEGY_EVALUATOR_V5_CODE_SHA256).matches("[a-f0-9]{64}");
        assertThat(StrategyEvaluatorV5.STRATEGY_EVALUATOR_V5_WORKER_CODE_SHA256).matches("[a-f0-9]{64}");
        assertThat(StrategyEvaluatorV5Worker.class.getDeclaredMethods()).extracting(Method::getName)
                .contains("evaluate", "initialized", "initializationError", "close");
        Set<String> workerMethods = Arrays.stream(StrategyEvaluatorV5Worker.class.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet());
        assertThat(workerMethods).contains("evaluate", "initialized", "initializationError", "close");
    }

    @Test
    void malformedWorkerInitializationAndResultBoundsFailClosed() {
        StrategyEvaluatorV5Worker invalid = new StrategyEvaluatorV5Worker(MAPPER.createObjectNode(), 1024, 0);
        assertThat(invalid.initialized()).isFalse();
        assertThat(invalid.initializationError()).isNotBlank();
        StrategyEvaluatorV5Worker.Response response = invalid.evaluate(MAPPER.createObjectNode(), "k", 0);
        assertThat(response.result()).isNull();
        assertThat(response.error()).isEqualTo(invalid.initializationError());
        invalid.close();

        assertThatThrownBy(() -> StrategyEvaluatorV5.createFixtureEvaluatorV5(MAPPER.createObjectNode()))
                .hasMessage("in-memory evaluator rows are fixture-only; use loadAuthoritativeEvaluatorV5 for research evidence");
    }

    @Test
    void duplicatePredictorsUndeclaredGenesAndHashMutationAreRejected() {
        ObjectNode options = minimalSpecOptions();
        ObjectNode duplicate = MAPPER.createObjectNode();
        duplicate.putArray("all").add(options.path("predicate")).add(options.path("predicate"));
        options.set("predicate", duplicate);
        assertThatThrownBy(() -> StrategyEvaluatorV5.makeEvaluatorSpecV5(options))
                .hasMessage("candidate predicate inventory contains duplicate predictor IDs");

        ObjectNode validOptions = minimalSpecOptions();
        ObjectNode spec = StrategyEvaluatorV5.makeEvaluatorSpecV5(validOptions);
        spec.put("strategy_family", "tampered");
        assertThatThrownBy(() -> StrategyEvaluatorV5.validateEvaluatorSpecV5(spec))
                .hasMessage("evaluator spec hash/code binding is invalid");

        ObjectNode targetRisk = minimalSpecOptions();
        ((ObjectNode) targetRisk.path("executionContract")).putObject("sizing_contract")
                .put("mode", "TARGET_STOP_RISK").put("notional_usd", 10);
        assertThatThrownBy(() -> StrategyEvaluatorV5.makeEvaluatorSpecV5(targetRisk))
                .hasMessage("TARGET_STOP_RISK sizing_contract cannot contain a fixed notional");
    }

    @Property(tries = 120)
    void numericPredicateOperatorsPreserveTheirMathematicalBoundary(
            @ForAll @IntRange(min = -1_000, max = 1_000) int left,
            @ForAll @IntRange(min = -1_000, max = 1_000) int right) {
        ObjectNode feature = MAPPER.createObjectNode().put("edge", left);
        ObjectNode chromosome = MAPPER.createObjectNode();
        assertThat(evaluate("GT", right, feature, chromosome)).isEqualTo(left > right);
        assertThat(evaluate("GTE", right, feature, chromosome)).isEqualTo(left >= right);
        assertThat(evaluate("LT", right, feature, chromosome)).isEqualTo(left < right);
        assertThat(evaluate("LTE", right, feature, chromosome)).isEqualTo(left <= right);
    }

    @Property(tries = 100)
    void physicalNullRebasePreservesIdentityAndTimeDelta(
            @ForAll @IntRange(min = -30, max = 30) int dayDelta,
            @ForAll @IntRange(min = 0, max = 1_000) int price) {
        long sourceDecision = Instant.parse("2026-01-15T00:00:00Z").toEpochMilli();
        long targetDecision = sourceDecision + dayDelta * 86_400_000L;
        ObjectNode target = identity("target", targetDecision);
        target.put("price", price + 1).putObject("execution_reference").put("window_id", "target");
        ObjectNode source = identity("source", sourceDecision);
        source.put("price", price).put("exit_time", sourceDecision + 120_000)
                .putObject("execution_reference").put("window_id", "source");
        ObjectNode rebased = StrategyEvaluatorV5.rebasePhysicalNullExecutionV5(target, source);
        assertThat(rebased.path("episode_id").asText()).isEqualTo("target");
        assertThat(rebased.path("decision_time").asLong()).isEqualTo(targetDecision);
        assertThat(rebased.path("exit_time").asDouble()).isEqualTo(targetDecision + 120_000d);
        assertThat(rebased.path("price").asInt()).isEqualTo(price);
        assertThat(rebased.has("execution_reference")).isFalse();
    }

    private static boolean evaluate(String operator, double right, ObjectNode feature, ObjectNode chromosome) {
        ObjectNode predicate = MAPPER.createObjectNode().put("predictor_id", "edge").put("op", operator).put("value", right);
        return StrategyEvaluatorV5.evaluateSignalPredicateV5(predicate, feature, chromosome);
    }

    private static ObjectNode minimalSpecOptions() {
        ObjectNode gene = MAPPER.createObjectNode();
        gene.putArray("genes").addObject().put("name", "threshold");
        gene.put("content_sha256", JsonHashes.ownHash(gene));
        ObjectNode registry = MAPPER.createObjectNode();
        registry.putArray("predictors").addObject().put("id", "edge");
        registry.put("content_sha256", JsonHashes.ownHash(registry));
        ObjectNode predicate = MAPPER.createObjectNode().put("predictor_id", "edge").put("op", "GTE");
        predicate.putObject("value").put("$gene", "threshold");
        ObjectNode candidate = MAPPER.createObjectNode().put("direction", "long")
                .put("entry_policy", "NEXT_BAR_OPEN").put("lifecycle_timeframe", "1m")
                .put("max_lifecycle_ms", 60_000).put("risk_amount_usd", 10);
        candidate.putObject("exit_policy").put("type", "TIME_STOP");
        ObjectNode contract = MAPPER.createObjectNode();
        contract.putObject("sizing_contract").put("mode", "FIXED_NOTIONAL_USD").put("notional_usd", 10);
        ObjectNode options = MAPPER.createObjectNode().put("strategyFamily", "family")
                .put("precommitSha256", JsonHashes.sha256("precommit"));
        options.set("geneSpace", gene); options.set("predictorRegistry", registry);
        options.set("predicate", predicate); options.set("candidateTemplate", candidate);
        options.set("executionContract", contract);
        return options;
    }

    private static ObjectNode identity(String episode, long decision) {
        return MAPPER.createObjectNode().put("asset", "btc").put("venue", "binance")
                .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                .put("signal_id", "signal").put("episode_id", episode).put("decision_time", decision);
    }

}
