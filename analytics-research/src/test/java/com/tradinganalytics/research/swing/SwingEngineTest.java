package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwingEngineTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporary;

    @Test
    void constantsRemainLockedToSourceContract() {
        assertThat(SwingEngine.ENGINE_VERSION).isEqualTo("swing-engine/1");
        assertThat(SwingEngine.FEATURE_STORE_SCHEMA).isEqualTo("swing-feature-store/1");
        assertThat(SwingEngine.RUN_SCHEMA).isEqualTo("swing-backtest/1");
        assertThat(SwingEngine.BAR_MS).isEqualTo(14_400_000);
        assertThat(SwingEngine.MAX_HOLD_BARS).isEqualTo(180);
        assertThat(SwingEngine.DEFAULT_INITIAL_EQUITY).isEqualTo(100_000);
    }

    @Test
    void featureStoreRejectsFutureLabelsDeduplicatesAndDetectsTampering() throws Exception {
        ObjectNode leaked = input();
        ((ObjectNode) leaked.path("features").get(0)).putObject("nested").put("future_pnl", 4);
        assertThatThrownBy(() -> SwingEngine.buildFeatureStore(leaked)).hasMessage("feature row contains future-label field nested.future_pnl");

        ObjectNode input = input();
        ((ArrayNode) input.get("features")).add(input.path("features").get(0).deepCopy());
        ObjectNode store = SwingEngine.buildFeatureStore(input, MAPPER.createObjectNode().put("source", "fixture"), FIXED);
        assertThat(store.path("row_count").asInt()).isEqualTo(3);
        assertThat(SwingEngine.verifyFeatureStoreHash(store)).isTrue();
        ((ObjectNode) store.path("datasets").get(0).path("metadata").get(0)).put("regime", "TAMPERED");
        assertThat(SwingEngine.verifyFeatureStoreHash(store)).isFalse();
    }

    @Test
    void plainAndGzipStoreRoundTripAndBadHashFailsClosed() throws Exception {
        ObjectNode store = SwingEngine.buildFeatureStore(input(), MAPPER.createObjectNode(), FIXED);
        for (String name : List.of("store.json", "store.json.gz")) {
            Path path = temporary.resolve(name);
            SwingEngine.FeatureStoreWrite write = SwingEngine.writeFeatureStore(path, store);
            assertThat(write.path()).isEqualTo(path.toAbsolutePath());
            assertThat(write.bytes()).isPositive();
            assertThat(CanonicalJson.canonicalize(SwingEngine.readFeatureStoreArtifact(path)))
                    .isEqualTo(CanonicalJson.canonicalize(store));
            assertThat(SwingEngine.readFeatureStore(path)).hasSize(3);
        }
        ObjectNode tampered = store.deepCopy().put("row_count", 99);
        Path path = temporary.resolve("tampered.json");
        Files.writeString(path, MAPPER.writeValueAsString(tampered));
        assertThatThrownBy(() -> SwingEngine.readFeatureStoreArtifact(path))
                .hasMessage("feature-store hash mismatch; refuse tampered cache");
    }

    @Test
    void candidateValidationEnforcesDirectionPhasesStopsCapsConcurrencyAndFilters() throws Exception {
        assertThatThrownBy(() -> normalize("{\"framework\":\"other\"}"))
                .hasMessage("candidate.framework must be fallen_knives or flying_rocket");
        assertThatThrownBy(() -> normalize("{\"framework\":\"fallen_knives\",\"direction\":\"short\"}"))
                .hasMessage("candidate direction does not match framework");
        assertThatThrownBy(() -> normalize("{\"framework\":\"flying_rocket\",\"channel\":\"B\",\"phase\":\"3\"}"))
                .hasMessageContaining("unsupported phase 3");
        assertThatThrownBy(() -> normalize("{\"framework\":\"fallen_knives\",\"trigger_window_bars\":3}"))
                .hasMessage("trigger freshness must be 1 or 2 completed bars");
        assertThatThrownBy(() -> normalize("{\"framework\":\"fallen_knives\",\"time_stop_bars\":181}"))
                .hasMessage("time stop must be between 1 and 180 bars");
        assertThatThrownBy(() -> normalize("{\"framework\":\"fallen_knives\",\"max_concurrent\":2}"))
                .hasMessageContaining("max_concurrent > 1");
        assertThatThrownBy(() -> normalize("{\"framework\":\"flying_rocket\",\"channel\":\"B\",\"stop_pct\":7}"))
                .hasMessageContaining("<=6%");
        assertThatThrownBy(() -> normalize("{\"framework\":\"fallen_knives\",\"cap_pct\":11}"))
                .hasMessage("cap_pct cannot exceed 10%");
        assertThatThrownBy(() -> normalize("{\"framework\":\"fallen_knives\",\"factor_filters\":[{\"path\":\"macro.dxy\",\"op\":\"gt\",\"value\":1}]}"))
                .hasMessageContaining("path must start with factors.");
    }

    @Test
    void lifecycleReportsNoNextBarNoFillRiskBlockDataGapAndBothCollisionPolicies() throws Exception {
        ArrayNode rows = normalizedRows(); ObjectNode candidate = normalize(candidateJson());
        assertThat(SwingEngine.simulateTrade(rows, 2, candidate).path("status").asText()).isEqualTo("NO_NEXT_BAR");
        ArrayNode noFill = rows.deepCopy(); ((ObjectNode) noFill.get(1)).putNull("open");
        assertThat(SwingEngine.simulateTrade(noFill, 0, candidate).path("status").asText()).isEqualTo("NO_FILL");
        ObjectNode noStop = normalize("{\"framework\":\"fallen_knives\",\"setup_family\":\"FK_HIGHER_LOW\"}");
        assertThat(SwingEngine.simulateTrade(rows, 0, noStop).path("status").asText()).isEqualTo("RISK_BLOCKED");
        ArrayNode gap = rows.deepCopy(); ((ObjectNode) gap.get(2)).put("time", rows.get(2).path("time").asLong() + SwingEngine.BAR_MS);
        ObjectNode longHold = normalize(candidateJson().replace("\"max_hold_bars\":2", "\"max_hold_bars\":3"));
        ((ObjectNode) gap.get(1)).put("high", 101).put("low", 99).put("close", 100);
        assertThat(SwingEngine.simulateTrade(gap, 0, longHold).path("status").asText()).isEqualTo("DATA_GAP");

        ArrayNode collision = rows.deepCopy(); ((ObjectNode) collision.get(1)).put("high", 108).put("low", 92);
        assertThat(SwingEngine.simulateTrade(collision, 0, candidate, MAPPER.createObjectNode().put("same_bar_collision", "stop-first")).path("exit_type").asText()).isEqualTo("STOP");
        assertThat(SwingEngine.simulateTrade(collision, 0, candidate, MAPPER.createObjectNode().put("same_bar_collision", "target-first")).path("exit_type").asText()).isEqualTo("TARGET");
    }

    @Test
    void strategyValidatesComponentsAndMetricsRetainAccountingInvariants() throws Exception {
        ArrayNode rows = normalizedRows();
        assertThatThrownBy(() -> SwingEngine.evaluateStrategy(rows, MAPPER.createArrayNode()))
                .hasMessage("strategy requires at least one component");
        ArrayNode duplicate = MAPPER.createArrayNode().add(MAPPER.readTree(candidateJson())).add(MAPPER.readTree(candidateJson()));
        assertThatThrownBy(() -> SwingEngine.evaluateStrategy(rows, duplicate)).hasMessage("strategy component ids must be unique");
        ObjectNode strategy = SwingEngine.evaluateStrategy(rows, MAPPER.createArrayNode().add(MAPPER.readTree(candidateJson())));
        assertThat(strategy.path("schema").asText()).isEqualTo("swing-strategy-evaluation/1");
        assertThat(strategy.path("completed_trades").asInt()).isEqualTo(1);
        assertThat(strategy.path("metrics").path("opened_trades").asInt()).isEqualTo(1);
    }

    @Test
    void commandAdapterCoversHelpFailuresInjectedBackfillRunInspectAndBenchmark() throws Exception {
        Invocation help = invoke(new String[0], null);
        assertThat(help.exit()).isZero(); assertThat(help.stdout()).isEqualTo(SwingEngineCommand.USAGE + System.lineSeparator());
        Invocation unknown = invoke(new String[]{"wat"}, null);
        assertThat(unknown.exit()).isOne(); assertThat(unknown.stderr()).isEqualTo("FAIL — unknown command wat" + System.lineSeparator());

        Path input = temporary.resolve("features.json"), store = temporary.resolve("store.json");
        Files.writeString(input, MAPPER.writeValueAsString(input()));
        Invocation build = invoke(new String[]{"build-cache", "--input", input.toString(), "--out", store.toString()}, null);
        assertThat(build.exit()).isZero(); assertThat(store).exists();

        Path backfillStore = temporary.resolve("backfill.json");
        Invocation backfill = invoke(new String[]{"build-cache", "--assets", "BTC", "--years", "9", "--out", backfillStore.toString()},
                (asset, years, cache) -> MAPPER.createObjectNode().set("datasets", input().path("datasets")));
        assertThat(backfill.exit()).isZero();

        Path candidates = temporary.resolve("candidates.json"), run = temporary.resolve("run.json"), summary = temporary.resolve("summary.md");
        Files.writeString(candidates, MAPPER.writeValueAsString(MAPPER.createArrayNode().add(MAPPER.readTree(candidateJson()))));
        Invocation execution = invoke(new String[]{"run", "--cache", store.toString(), "--candidates", candidates.toString(), "--out", run.toString(), "--summary", summary.toString(), "--min-trades", "1", "--min-regimes", "1"}, null);
        assertThat(execution.exit()).isZero(); assertThat(run).exists(); assertThat(summary).exists();
        Invocation inspect = invoke(new String[]{"inspect-trades", "--run", run.toString()}, null);
        assertThat(inspect.exit()).isZero(); assertThat(MAPPER.readTree(inspect.stdout()).path("run_sha256").asText()).isNotBlank();
        Invocation benchmark = invoke(new String[]{"benchmark", "--cache", store.toString(), "--candidate-count", "1"}, null);
        assertThat(benchmark.exit()).isZero(); assertThat(MAPPER.readTree(benchmark.stdout()).path("candidates").asInt()).isOne();
    }

    private ObjectNode normalize(String json) throws Exception { return SwingEngine.normalizeCandidate(MAPPER.readTree(json)); }
    private ArrayNode normalizedRows() throws Exception { return SwingEngine.decodeFeatureStore(SwingEngine.buildFeatureStore(input(), MAPPER.createObjectNode(), FIXED)); }
    private static String candidateJson() { return "{\"id\":\"fk\",\"framework\":\"fallen_knives\",\"phase\":\"1A\",\"setup_family\":\"FK_HIGHER_LOW\",\"stop_pct\":6,\"target_r\":1,\"max_hold_bars\":2,\"partial_exit_pct\":0}"; }
    private static ObjectNode input() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"point_in_time_safe":true,"features":[
                  {"asset":"btc","timeframe":"4h","framework":"fallen_knives","time":1700000000000,"open":100,"high":101,"low":99,"close":100,"mechanical_score":10,"flow_aligned_rows":2,"flow_coverage":"COMPLETE","setup_family":"FK_HIGHER_LOW","trigger":{"valid":true,"completed_bar":true,"timeframe":"4h","age_bars":0},"regime":"RANGE"},
                  {"asset":"btc","timeframe":"4h","framework":"fallen_knives","time":1700014400000,"open":100,"high":107,"low":99,"close":106,"mechanical_score":0,"flow_aligned_rows":0,"setup_family":"NONE","trigger":{"valid":false,"completed_bar":true,"timeframe":"4h","age_bars":0},"regime":"RANGE"},
                  {"asset":"btc","timeframe":"4h","framework":"fallen_knives","time":1700028800000,"open":106,"high":108,"low":105,"close":107,"mechanical_score":0,"flow_aligned_rows":0,"setup_family":"NONE","trigger":{"valid":false,"completed_bar":true,"timeframe":"4h","age_bars":0},"regime":"RANGE"}
                ]}
                """);
    }
    private Invocation invoke(String[] args, SwingEngineCommand.BackfillProvider provider) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream(), stderr = new ByteArrayOutputStream();
        int exit = SwingEngineCommand.run(args, new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8), provider, FIXED);
        return new Invocation(exit, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }
    private record Invocation(int exit, String stdout, String stderr) {}
}
