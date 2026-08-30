package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.MAPPER;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.array;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.write;
import static org.assertj.core.api.Assertions.assertThat;

class LegacyResearchMigrationV3NodeOracleTest {
    @TempDir Path temporary;

    @Test
    void inventoryFileAndCliResultAreDeterministic() throws Exception {
        Path root = fixtureRoot();
        Path javaOutput = temporary.resolve("java-inventory.json");
        Result java = javaMigration(root.toString(), javaOutput.toString());
        assertThat(java.exit()).describedAs(java.stderr()).isZero();
        ObjectNode javaStdout = (ObjectNode) MAPPER.readTree(java.stdout());
        JsonNode report = MAPPER.readTree(Files.readString(javaOutput));
        assertThat(javaStdout.path("path").asText()).isEqualTo(javaOutput.toString());
        assertThat(report.path("runs")).hasSize(1);
        assertThat(report.path("deterministic_index").path("btc")).hasSize(1);
        assertThat(report.path("excluded_assets").toString()).isEqualTo("[\"doge\"]");
    }

    @Test
    void emptyInventoryNeverInventsEvidence() throws Exception {
        Path root = temporary.resolve("empty-root");
        Files.createDirectories(root);
        Path javaOutput = temporary.resolve("java-empty.json");
        Result java = javaMigration(root.toString(), javaOutput.toString());
        assertThat(java.exit()).isZero();
        JsonNode report = MAPPER.readTree(Files.readString(javaOutput));
        assertThat(report.path("runs")).isEmpty();
        assertThat(report.path("omissions")).hasSize(11);
        assertThat(report.path("promotion_policy").asText()).contains("never reconstructed");
    }

    @Test
    void migrationOutputIsImmutable() throws Exception {
        Path root = fixtureRoot();
        Path output = temporary.resolve("immutable.json");
        assertThat(javaMigration(root.toString(), output.toString()).exit()).isZero();
        Result second = javaMigration(root.toString(), output.toString());
        assertThat(second.exit()).isEqualTo(1);
        assertThat(second.stderr()).startsWith("overwrite refused:");
    }

    @Test
    void flatEightAssetMigrationIsDeterministic() throws Exception {
        Path root = temporary.resolve("flat-eight-assets");
        Path run = root.resolve("runs/legacy-run");
        Files.createDirectories(run);
        StringBuilder metrics = new StringBuilder();
        for (String asset : List.of("btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave")) {
            metrics.append(MAPPER.writeValueAsString(object()
                    .put("asset", asset)
                    .put("strategy_id", "legacy-strategy")
                    .put("candidate_id", "candidate-" + asset)
                    .put("completed_trades", 3)
                    .put("expectancy_r", 0.1)
                    .put("status", "REJECTED"))).append('\n');
        }
        Files.writeString(run.resolve("metrics.jsonl"), metrics);
        ObjectNode runValue = object().put("schema", "strategy-run/2")
                .put("evidence_phase", "EXPOSED_CONFIRMATION")
                .put("strategy_id", "legacy-strategy");
        runValue.set("decisions", object().set("portfolio", object().put("status", "REJECTED")));
        runValue.set("artifacts", object().set("metrics",
                object().put("path", "metrics.jsonl").put("sha256", "0".repeat(64))));
        write(run.resolve("run.json"), runValue);

        Path javaOutput = temporary.resolve("flat-java.json");
        Result java = javaMigration(root.toString(), javaOutput.toString());
        assertThat(java.exit()).describedAs(java.stderr()).isZero();
        JsonNode actual = MAPPER.readTree(Files.readString(javaOutput));
        assertThat(actual.path("deterministic_index").size()).isEqualTo(8);
        assertThat(actual.path("deterministic_index").path("btc").path(0)
                .path("metric_sha256").asText()).hasSize(64);
        assertThat(actual.path("excluded_assets").toString()).isEqualTo("[\"doge\"]");
        assertThat(actual.path("promotion_policy").asText()).contains("read-only");
    }

    @Test
    void nestedMetricsPreservePhaseCountsOmissionsAndOriginalRowHash() throws Exception {
        Path root = temporary.resolve("nested-metrics");
        Path run = root.resolve("runs/legacy");
        Files.createDirectories(run);
        ObjectNode link = object().put("asset", "link").put("strategy_id", "legacy")
                .put("candidate_id", "a").put("phase", "DEVELOPMENT")
                .put("status", "REJECTED");
        link.set("metrics", object().put("completed_trades", 11).put("expectancy_r", 0.01));
        link.set("omissions", array().add("TRADES"));
        ArrayNode rows = array()
                .add(object().put("asset", "aave").put("strategy_id", "legacy")
                        .put("candidate_id", "a").put("phase", "EXPOSED_CONFIRMATION")
                        .put("status", "REJECTED").set("metrics",
                                object().put("completed_trades", 29).put("expectancy_r", -0.12)))
                .add(object().put("asset", "ada").put("strategy_id", "legacy")
                        .put("candidate_id", "a").put("phase", "DEVELOPMENT")
                        .put("status", "SHADOW").set("metrics",
                                object().put("completed_trades", 17).put("expectancy_r", 0.03)))
                .add(link);
        StringBuilder metrics = new StringBuilder();
        for (JsonNode row : rows) metrics.append(MAPPER.writeValueAsString(row)).append('\n');
        Files.writeString(run.resolve("metrics.jsonl"), metrics);
        ObjectNode runValue = object().put("schema", "strategy-run/2")
                .put("evidence_phase", "EXPOSED_CONFIRMATION");
        runValue.set("decisions", object().set("portfolio", object().put("status", "REJECTED")));
        runValue.set("artifacts", object().set("metrics", object().put("path", "metrics.jsonl")));
        write(run.resolve("run.json"), runValue);

        Path javaOutput = temporary.resolve("nested-java.json");
        Result java = javaMigration(root.toString(), javaOutput.toString());
        assertThat(java.exit()).describedAs(java.stderr()).isZero();
        JsonNode actual = MAPPER.readTree(Files.readString(javaOutput));
        assertThat(actual.path("deterministic_index").path("aave").path(0)
                .path("completed_trades").asInt()).isEqualTo(29);
        assertThat(actual.path("deterministic_index").path("aave").path(0)
                .path("expectancy_r").asDouble()).isEqualTo(-0.12);
        assertThat(actual.path("deterministic_index").path("aave").path(0)
                .path("phase").asText()).isEqualTo("EXPOSED_CONFIRMATION");
        assertThat(actual.path("deterministic_index").path("link").path(0)
                .path("omissions").toString()).isEqualTo("[\"TRADES\"]");
        assertThat(actual.path("deterministic_index").path("aave").path(0)
                .path("original_row_sha256").asText()).hasSize(64);
    }

    private Path fixtureRoot() throws Exception {
        Path root = temporary.resolve("research");
        Path run = root.resolve("runs/run-1");
        Files.createDirectories(run);
        ArrayNode decisions = array().add(object().put("asset", "btc")
                .put("status", "SHADOW"));
        ObjectNode value = object()
                .put("schema", "strategy-run/1")
                .put("evidence_phase", "EXPOSED_CONFIRMATION")
                .put("strategy_id", "fixture")
                .put("generated_at", "2026-01-01T00:00:00.000Z");
        ObjectNode decision = object();
        decision.set("per_asset", decisions);
        decision.set("portfolio", object().put("status", "SHADOW"));
        value.set("decisions", decision);
        value.set("artifacts", object().set("metrics",
                object().put("path", "metrics.jsonl").put("sha256", "a".repeat(64))));
        write(run.resolve("run.json"), value);
        String metrics = MAPPER.writeValueAsString(object()
                .put("strategy_id", "fixture")
                .put("candidate_id", "candidate-a")
                .put("asset", "btc")
                .put("phase", "EXPOSED_CONFIRMATION")
                .set("metrics", object().put("completed_trades", 12)
                        .put("expectancy_r", 0.25))) + "\n"
                + "not-json\n"
                + MAPPER.writeValueAsString(object().put("asset", "doge")
                .put("candidate_id", "excluded").put("completed_trades", 99)) + "\n";
        Files.writeString(run.resolve("metrics.jsonl"), metrics);
        return root;
    }

    private Result javaMigration(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = LegacyResearchMigrationV3CommandAdapter.run(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exit, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String stdout, String stderr) {}
}
