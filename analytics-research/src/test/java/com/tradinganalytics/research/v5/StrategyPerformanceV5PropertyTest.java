package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

final class StrategyPerformanceV5PropertyTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @Property(tries = 50)
    void productionWorkloadPreservesFrozenAttemptAlgebra(
            @ForAll @IntRange(min = 1, max = 10) int assets,
            @ForAll @IntRange(min = 1, max = 10) int outer,
            @ForAll @IntRange(min = 0, max = 4) int inner,
            @ForAll @IntRange(min = 1, max = 20) int population,
            @ForAll @IntRange(min = 1, max = 10) int generations,
            @ForAll @IntRange(min = 1, max = 5) int seeds,
            @ForAll @IntRange(min = 1, max = 20) int episodes) {
        ObjectNode options = object().put("assets", Integer.toString(assets)).put("outerFolds", outer)
                .put("innerFolds", inner).put("population", population).put("generations", generations)
                .put("seeds", seeds).put("episodesPerEvaluation", episodes);
        ObjectNode workload = StrategyPerformanceV5.estimateProductionWorkloadV5(options);
        long runs = (long) assets * outer * (inner + 1);
        long attempts = (long) population * generations * seeds;
        long base = runs * attempts;
        long nullGa = base * 4 * 128;
        long confirmation = runs * 4 * 128 * 100;
        long pbo = (long) assets * outer * 4 * 128 * 8 * 8;
        long materialization = (long) outer * 4 * 128 * 48;
        long selected = (long) assets * outer * 4 * 128;
        long total = nullGa + confirmation + pbo + materialization + selected;
        assertThat(workload.path("ga_runs").asLong()).isEqualTo(runs);
        assertThat(workload.path("base_ga_attempts").asLong()).isEqualTo(base);
        assertThat(workload.path("physical_null_total_attempts").asLong()).isEqualTo(total);
        assertThat(workload.path("physical_null_episode_evaluations").asLong())
                .isEqualTo(total * episodes);
        assertThat(StrategyPerformanceV5.estimateProductionComplexityV5(options)
                .path("workload_sha256").asText()).isEqualTo(StrategyPerformanceV5.hashV5Performance(workload));
    }

    @Property(tries = 35)
    void identicalScopesReuseEverySignalAndIntentOutcome(
            @ForAll @IntRange(min = 1, max = 24) int episodeCount) {
        Fixture fixture = fixture(episodeCount);
        StrategyPerformanceV5.ScopeVectorCache cache = StrategyPerformanceV5.makeScopeVectorCacheV5(binding());
        AtomicInteger signalCalls = new AtomicInteger(); AtomicInteger outcomeCalls = new AtomicInteger();
        StrategyPerformanceV5.SignalEvaluator signal = (id, feature, chromosome) -> {
            signalCalls.incrementAndGet(); return object().put("intent", feature.path("intent").asBoolean());
        };
        StrategyPerformanceV5.OutcomeEvaluator outcome = (id, feature, row, chromosome, phase, fold,
                                                             fit, evaluation) -> {
            outcomeCalls.incrementAndGet(); return object().put("net_r", feature.path("return").asDouble());
        };
        StrategyPerformanceV5.EvaluationRequest request = new StrategyPerformanceV5.EvaluationRequest(
                object().put("threshold", 0), null, fixture.ids(), fixture.ids(), "TRAIN_ONLY", "outer-1",
                "2026-01-01T00:00:00.000Z", "2026-02-01T00:00:00.000Z",
                fixture.features(), signal, outcome);
        ObjectNode first = cache.evaluate(request); ObjectNode second = cache.evaluate(request);
        int intents = (episodeCount + 1) / 2;
        assertThat(second).isEqualTo(first);
        assertThat(signalCalls).hasValue(episodeCount);
        assertThat(outcomeCalls).hasValue(intents);
        assertThat(cache.diagnostics().path("signal_hit_count").asInt()).isEqualTo(episodeCount);
        assertThat(cache.diagnostics().path("outcome_hit_count").asInt()).isEqualTo(intents);
    }

    @Property(tries = 35)
    void ordinaryOutcomeVectorsNeverCrossPhaseOrFoldScopes(
            @ForAll @IntRange(min = 1, max = 24) int episodeCount) {
        Fixture fixture = fixture(episodeCount);
        StrategyPerformanceV5.ScopeVectorCache cache = StrategyPerformanceV5.makeScopeVectorCacheV5(binding());
        AtomicInteger outcomes = new AtomicInteger();
        StrategyPerformanceV5.SignalEvaluator signal = (id, feature, chromosome) ->
                object().put("intent", feature.path("intent").asBoolean());
        StrategyPerformanceV5.OutcomeEvaluator outcome = (id, feature, row, chromosome, phase, fold,
                                                             fit, evaluation) -> {
            outcomes.incrementAndGet(); return object().put("net_r", feature.path("return").asDouble()
                    + ("VALID".equals(phase) ? 1 : 0));
        };
        ObjectNode chromosome = object().put("threshold", 0);
        cache.evaluate(new StrategyPerformanceV5.EvaluationRequest(chromosome, null, fixture.ids(), fixture.ids(),
                "TRAIN_ONLY", "outer-1", null, null, fixture.features(), signal, outcome));
        ObjectNode validation = cache.evaluate(new StrategyPerformanceV5.EvaluationRequest(chromosome, null,
                fixture.ids(), fixture.ids(), "VALID", "outer-2", null, null,
                fixture.features(), signal, outcome));
        int intents = (episodeCount + 1) / 2;
        assertThat(outcomes).hasValue(intents * 2);
        assertThat(cache.diagnostics().path("signal_hit_count").asInt()).isEqualTo(episodeCount);
        assertThat(cache.diagnostics().path("outcome_hit_count").asInt()).isZero();
        for (int index = 0; index < episodeCount; index += 2)
            assertThat(validation.path("candidate_returns").path("e" + index).path("net_r").asDouble())
                    .isEqualTo(index / 100D + 1);
    }

    private static Fixture fixture(int count) {
        List<String> ids = new ArrayList<>(); Map<String, JsonNode> features = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String id = "e" + index; ids.add(id);
            features.put(id, object().put("intent", index % 2 == 0).put("return", index / 100D));
        }
        return new Fixture(List.copyOf(ids), Map.copyOf(features));
    }

    private static ObjectNode binding() {
        ObjectNode data = object();
        for (String key : StrategyPerformanceV5.DATA_BINDING_KEYS) data.put(key, hash(key));
        ObjectNode value = object().put("sourceArtifactSha256", hash("source"))
                .put("evaluatorSpecSha256", hash("evaluator")).put("signalCodeSha256", hash("signal"))
                .put("outcomeCodeSha256", hash("outcome")).put("maxMemoryEntries", 100)
                .put("maxMemoryBytes", 1_000_000).put("maxDiskBytes", 0);
        value.set("dataBindings", data); return value;
    }

    private static String hash(String value) { return StrategyPerformanceV5.hashV5Performance(value); }
    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private record Fixture(List<String> ids, Map<String, JsonNode> features) {}
}
