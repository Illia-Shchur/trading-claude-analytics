package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

/** Null/default entry points retain the deterministic fixture benchmark contract. */
class StrategyPerformanceV5BenchmarkEntryDefaultsTest {
    @Test
    void fixtureEntryUsesDocumentedDefaultsWhenArgumentsAreNull() {
        var result = StrategyPerformanceV5Benchmark.runFixtureBenchmarkV5(null);
        assertThat(result.path("schema").asText()).isEqualTo(StrategyPerformanceV5Benchmark.BENCHMARK_SCHEMA);
        assertThat(result.path("shape").path("assets").asInt()).isEqualTo(8);
        assertThat(result.path("shape").path("outer_folds").asInt()).isEqualTo(8);
        assertThat(result.path("shape").path("inner_folds_per_asset").asInt()).isEqualTo(2);
        assertThat(result.path("shape").path("sample_chromosomes").asInt()).isEqualTo(8);
        assertThat(result.path("shape").path("episodes_per_asset").asInt()).isEqualTo(96);
    }

    @Test
    void commandEntryTreatsNullVarargsAsAnEmptyFixtureCommand() {
        var result = StrategyPerformanceV5Benchmark.runBenchmarkV5((String[]) null);
        assertThat(result.path("schema").asText()).isEqualTo(StrategyPerformanceV5Benchmark.BENCHMARK_SCHEMA);
        assertThat(result.path("production_readiness").path("ready").asBoolean()).isFalse();
    }

    @Test
    void streamEntryTreatsNullArgumentsAsAnEmptyFixtureCommand() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status = StrategyPerformanceV5Benchmark.run(null, new PrintStream(stdout), new PrintStream(stderr));

        assertThat(status).isZero();
        assertThat(stdout.toString()).contains(StrategyPerformanceV5Benchmark.BENCHMARK_SCHEMA);
        assertThat(stderr.toString()).isEmpty();
    }
}
