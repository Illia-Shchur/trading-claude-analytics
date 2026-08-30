package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.contracts.json.NodePrettyJson;
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

class SwingResearchCliNodeOracleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-22T01:02:03.004Z"), ZoneOffset.UTC);
    @TempDir Path temporary;

    @Test
    void candidateAndStrategyOneTimeCliArtifactsMatchNode() throws Exception {
        Path storePath = temporary.resolve("store.json"); ObjectNode store = SwingEngine.buildFeatureStore(featureInput(), MAPPER.createObjectNode().put("source", "fixture"), FIXED);
        SwingEngine.writeFeatureStore(storePath, store); ArrayNode candidates = MAPPER.createArrayNode().add(candidate());
        Path candidatesPath = write("candidates.json", candidates);
        ObjectNode candidatePrecommit = MAPPER.createObjectNode().put("schema", "swing-cross-asset-precommit/1").put("validation_asset", "btc")
                .put("candidate_sha256", SwingEngine.sha256(candidates)).put("primary_candidate_id", "fk"); candidatePrecommit.putArray("candidate_ids").add("fk");
        Path candidatePrecommitPath = write("candidate-precommit.json", candidatePrecommit);
        Path javaOut = temporary.resolve("java-candidate.json");
        Result java = javaCross("--cache", storePath.toString(), "--candidates", candidatesPath.toString(),
                "--precommit", candidatePrecommitPath.toString(), "--out", javaOut.toString());
        assertThat(java.exit()).describedAs(java.stderr()).isZero();
        ObjectNode javaArtifact = (ObjectNode) MAPPER.readTree(Files.readString(javaOut));
        assertThat(javaArtifact.path("schema").asText()).isEqualTo("swing-cross-asset-validation/1");
        assertThat(javaArtifact.path("candidate_sha256").asText()).isEqualTo(SwingEngine.sha256(candidates));
        assertThat(javaArtifact.path("generated_at").asText()).isEqualTo(FIXED.instant().toString());
        ObjectNode js = (ObjectNode) MAPPER.readTree(java.stdout());
        assertThat(js.path("out").asText()).isEqualTo(javaOut.toAbsolutePath().normalize().toString());

        ObjectNode strategy = MAPPER.createObjectNode().put("schema", "swing-frozen-strategy/1").put("id", "strategy"); strategy.set("components", candidates);
        Path strategyPath = write("strategy.json", strategy); ObjectNode strategyPrecommit = MAPPER.createObjectNode()
                .put("schema", "swing-strategy-cross-asset-precommit/1").put("validation_asset", "btc").put("strategy_id", "strategy")
                .put("component_sha256", SwingEngine.sha256(candidates)).put("strategy_sha256", SwingEngine.sha256(strategy));
        Path strategyPrecommitPath = write("strategy-precommit.json", strategyPrecommit);
        ObjectNode seal = MAPPER.createObjectNode().put("schema", "swing-feature-seal/1").put("feature_store_sha256", store.path("features_sha256").asText());
        Path sealPath = write("seal.json", seal); Path javaStrategyOut = temporary.resolve("java-strategy.json");
        Result javaStrategy = javaStrategy("--cache", storePath.toString(), "--strategy", strategyPath.toString(),
                "--precommit", strategyPrecommitPath.toString(), "--feature-seal", sealPath.toString(), "--out", javaStrategyOut.toString());
        assertThat(javaStrategy.exit()).describedAs(javaStrategy.stderr()).isZero();
        ObjectNode jsa = (ObjectNode) MAPPER.readTree(Files.readString(javaStrategyOut));
        assertThat(jsa.path("schema").asText()).isEqualTo("swing-strategy-cross-asset-validation/1");
        assertThat(jsa.path("strategy_sha256").asText()).isEqualTo(SwingEngine.sha256(strategy));
        assertThat(jsa.path("generated_at").asText()).isEqualTo(FIXED.instant().toString());
    }

    @Test
    void calibrationInputModeAndLintCliMatchNode() throws Exception {
        ObjectNode input = MAPPER.createObjectNode().put("point_in_time_safe", false); ObjectNode dataset = MAPPER.createObjectNode()
                .put("asset", "btc").put("framework", "fallen_knives").putNull("channel").put("coverage", "HISTORICAL_PROXY");
        dataset.set("bars", MAPPER.createArrayNode()); dataset.set("labels", MAPPER.createArrayNode()); dataset.set("features", MAPPER.createArrayNode());
        input.putArray("datasets").add(dataset); Path inputPath = write("calibration-input.json", input);
        Path javaOut = temporary.resolve("java-calibration.json");
        Result java = javaCalibration("--input", inputPath.toString(), "--out", javaOut.toString());
        assertThat(java.exit()).describedAs(java.stderr()).isZero();
        ObjectNode ja = (ObjectNode) MAPPER.readTree(Files.readString(javaOut));
        assertThat(ja.path("schema").asText()).isEqualTo("swing-calibration/1");
        assertThat(ja.path("generated_at").asText()).isEqualTo(FIXED.instant().toString());
        ObjectNode js = (ObjectNode) MAPPER.readTree(java.stdout());
        assertThat(js.path("out").asText()).isEqualTo(javaOut.toAbsolutePath().normalize().toString());

        Path report = write("valid-calibration.json", ja);
        Result javaLint = javaLint(report.toString()); assertThat(javaLint.exit()).describedAs(javaLint.stderr()).isZero();
        assertThat(javaLint.stdout()).contains("PASS");
        ((ObjectNode) ja.path("model_activation")).put("artifact", "not-allowed"); Files.writeString(report, NodePrettyJson.write(ja));
        Result javaInvalid = javaLint(report.toString());
        assertThat(javaInvalid.exit()).isEqualTo(1);
        assertThat(javaInvalid.stderr()).isEqualTo(
                "FAIL swing calibration lint: SHADOW calibration carries ACTIVE artifact metadata\n");
    }

    @Test
    void commandGuardsPreserveExitAndStableDiagnostics() {
        Result cross = javaCross(); assertThat(cross.exit()).isEqualTo(1); assertThat(cross.stderr()).isEqualTo("FAIL — requires --cache, --candidates, --precommit, and --out\n");
        Result strategy = javaStrategy(); assertThat(strategy.exit()).isEqualTo(1); assertThat(strategy.stderr()).isEqualTo("Error: --cache is required\n");
    }

    private Result javaCross(String... args) { return capture((out, err) -> SwingCrossValidateCommand.run(args, out, err, FIXED)); }
    private Result javaStrategy(String... args) { return capture((out, err) -> SwingStrategyCrossValidateCommand.run(args, out, err, FIXED)); }
    private Result javaCalibration(String... args) { return capture((out, err) -> SwingCalibrationCommand.run(args, out, err, null, FIXED, repositoryRoot())); }
    private Result javaLint(String... args) { return capture((out, err) -> SwingCalibrationLintCommand.run(args, out, err, repositoryRoot())); }
    private Result capture(Runner runner) { ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        int exit = runner.run(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8)); }
    private Path write(String name, JsonNode value) throws Exception { Path path = temporary.resolve(name); Files.writeString(path, NodePrettyJson.write(value)); return path; }
    private static ObjectNode candidate() { return MAPPER.createObjectNode().put("id", "fk").put("framework", "fallen_knives").put("direction", "long")
            .put("phase", "1A").put("setup_family", "FK_HIGHER_LOW").put("stop_pct", 6).put("target_r", 1).put("partial_exit_pct", 0); }
    private static ObjectNode featureInput() { ObjectNode input = MAPPER.createObjectNode().put("point_in_time_safe", true); ObjectNode dataset = MAPPER.createObjectNode()
            .put("asset", "btc").put("timeframe", "4h").put("framework", "fallen_knives").putNull("channel"); ObjectNode row = MAPPER.createObjectNode()
            .put("time", Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()).put("open", 100).put("high", 101).put("low", 99).put("close", 100)
            .put("funding_rate", 0).put("funding_event_time", Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()).put("mechanical_score", 10)
            .put("flow_aligned_rows", 2).put("flow_coverage", "COMPLETE").put("setup_family", "FK_HIGHER_LOW").put("regime", "RANGE");
        row.putArray("setup_families").add("FK_HIGHER_LOW"); row.set("trigger", MAPPER.createObjectNode().put("valid", true).put("completed_bar", true).put("timeframe", "4h").put("age_bars", 0));
        row.set("factors", MAPPER.createObjectNode().set("derivatives", MAPPER.createObjectNode().put("top_vs_global_positioning_z", 0)));
        dataset.putArray("features").add(row); input.putArray("datasets").add(dataset); return input; }
    private static void assertJson(JsonNode actual, JsonNode expected) { assertThat(CanonicalJson.canonicalize(actual)).isEqualTo(CanonicalJson.canonicalize(expected)); }
    private static Path repositoryRoot() { Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath(); while (path != null && !Files.isRegularFile(path.resolve("pom.xml"))) path = path.getParent(); if (path == null) throw new IllegalStateException(); return path; }
    private record Result(int exit, String stdout, String stderr) {}
    @FunctionalInterface private interface Runner { int run(PrintStream stdout, PrintStream stderr); }
}
