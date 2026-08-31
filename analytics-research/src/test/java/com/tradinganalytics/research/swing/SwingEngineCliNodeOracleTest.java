package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Java-only CLI parity against the captured swing-engine.mjs contract. */
class SwingEngineCliNodeOracleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectNode ORACLE = loadOracle();
    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2026-08-22T01:02:03.004Z"), ZoneOffset.UTC);

    @TempDir Path temporary;

    @Test
    void helpAndTerminalErrorsMatchFrozenNodeContract() {
        assertThat(java()).isEqualTo(frozenResult("help"));
        assertThat(java("unknown")).isEqualTo(frozenResult("unknown"));
        assertThat(java("run")).isEqualTo(frozenResult("missing_run"));
    }

    @Test
    void buildRunInspectAndBenchmarkModesMatchStableNodeContracts() throws Exception {
        Path features = temporary.resolve("features.json");
        Files.writeString(features, featureJson());
        Path storePath = temporary.resolve("java-store.json");
        Result build = java("build-cache", "--input", features.toString(),
                "--out", storePath.toString());
        assertThat(build.exit()).describedAs(build.stderr()).isZero();
        JsonNode buildJson = MAPPER.readTree(build.stdout());
        assertThat(buildJson.path("rows").asInt())
                .isEqualTo(ORACLE.path("build").path("rows").asInt());
        ObjectNode store = (ObjectNode) MAPPER.readTree(Files.readString(storePath));
        store.put("created_at", "$TIME");
        store.put("source", "$INPUT");
        ObjectNode featureHashPayload = store.deepCopy();
        featureHashPayload.putNull("created_at");
        featureHashPayload.putNull("features_sha256");
        store.put("features_sha256", SwingEngine.sha256(featureHashPayload));
        assertThat(store.path("features_sha256").asText())
                .isEqualTo(ORACLE.path("build").path("features_sha256").asText());
        assertThat(SwingEngine.sha256(store))
                .isEqualTo(ORACLE.path("build").path("normalized_store_sha256").asText());
        Path normalizedStorePath = temporary.resolve("normalized-store.json");
        SwingEngine.writeFeatureStore(normalizedStorePath, store);

        Path candidates = temporary.resolve("candidates.json");
        Files.writeString(candidates, "[" + candidateJson() + "]");
        Path runPath = temporary.resolve("java-run.json");
        Result execution = java("run", "--cache", normalizedStorePath.toString(),
                "--candidates", candidates.toString(), "--out", runPath.toString(),
                "--min-trades", "1", "--min-regimes", "1");
        assertThat(execution.exit()).describedAs(execution.stderr()).isZero();
        ObjectNode run = (ObjectNode) MAPPER.readTree(Files.readString(runPath));
        assertThat(run.path("run_sha256").asText())
                .isEqualTo(ORACLE.path("run").path("run_sha256").asText());
        run.put("generated_at", "$TIME");
        assertThat(SwingEngine.sha256(run))
                .isEqualTo(ORACLE.path("run").path("normalized_artifact_sha256").asText());

        Result inspect = java("inspect-trades", "--run", runPath.toString());
        assertThat(inspect.exit()).describedAs(inspect.stderr()).isZero();
        assertThat(MAPPER.readTree(inspect.stdout())).isEqualTo(ORACLE.path("inspect"));

        Result benchmark = java("benchmark", "--cache", normalizedStorePath.toString(),
                "--candidate-count", "1");
        assertThat(benchmark.exit()).describedAs(benchmark.stderr()).isZero();
        ObjectNode benchmarkJson = (ObjectNode) MAPPER.readTree(benchmark.stdout());
        assertThat(benchmarkJson.path("elapsed_ms").asDouble()).isGreaterThanOrEqualTo(0);
        assertThat(benchmarkJson.path("memory_mb").asDouble()).isPositive();
        benchmarkJson.remove("elapsed_ms");
        benchmarkJson.remove("memory_mb");
        assertThat(benchmarkJson).isEqualTo(ORACLE.path("benchmark"));
    }

    private Result java(String... arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = SwingEngineCommand.run(arguments,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8), null, FIXED);
        return new Result(exit, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static Result frozenResult(String name) {
        JsonNode value = ORACLE.path(name);
        return new Result(value.path("exit").asInt(), value.path("stdout").asText(),
                value.path("stderr").asText());
    }

    private static ObjectNode loadOracle() {
        try (var input = SwingEngineCliNodeOracleTest.class.getResourceAsStream(
                "/oracles/swing-engine-cli-v1.json")) {
            if (input == null) throw new IllegalStateException("frozen swing CLI oracle is missing");
            return (ObjectNode) MAPPER.readTree(input);
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private record Result(int exit, String stdout, String stderr) {}

    private static String candidateJson() {
        return "{\"id\":\"fk\",\"framework\":\"fallen_knives\",\"phase\":\"1A\","
                + "\"setup_family\":\"FK_HIGHER_LOW\",\"stop_pct\":6,\"target_r\":1,"
                + "\"max_hold_bars\":2,\"partial_exit_pct\":0}";
    }

    private static String featureJson() {
        return """
                {"point_in_time_safe":true,"features":[
                  {"asset":"btc","timeframe":"4h","framework":"fallen_knives","time":1700000000000,"open":100,"high":101,"low":99,"close":100,"mechanical_score":10,"flow_aligned_rows":2,"flow_coverage":"COMPLETE","setup_family":"FK_HIGHER_LOW","trigger":{"valid":true,"completed_bar":true,"timeframe":"4h","age_bars":0},"regime":"RANGE"},
                  {"asset":"btc","timeframe":"4h","framework":"fallen_knives","time":1700014400000,"open":100,"high":107,"low":99,"close":106,"mechanical_score":0,"flow_aligned_rows":0,"setup_family":"NONE","trigger":{"valid":false,"completed_bar":true,"timeframe":"4h","age_bars":0},"regime":"RANGE"},
                  {"asset":"btc","timeframe":"4h","framework":"fallen_knives","time":1700028800000,"open":106,"high":108,"low":105,"close":107,"mechanical_score":0,"flow_aligned_rows":0,"setup_family":"NONE","trigger":{"valid":false,"completed_bar":true,"timeframe":"4h","age_bars":0},"regime":"RANGE"}
                ]}
                """;
    }
}
