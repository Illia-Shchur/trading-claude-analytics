package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.marketdata.research.ResearchData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.MAPPER;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.array;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static org.assertj.core.api.Assertions.assertThat;

class LegacyResearchCommandAdapterNodeOracleTest {
    @TempDir Path temporary;

    @Test
    void usageAndStaticGeneticGuardAreExact() {
        Result usage = javaCli();
        assertThat(usage.exit()).isZero();
        assertThat(usage.stderr()).isEmpty();
        assertThat(usage.stdout()).startsWith("usage: strategy-research.mjs ")
                .contains("evaluate-v3", "acceptance-contract", "monitor");
        Result java = javaCli("generate", "--method", "GENETIC");
        assertThat(java.exit()).isEqualTo(1);
        assertThat(java.stdout()).isEmpty();
        String message = "static generate --method GENETIC is rejected; use the "
                + "authoritative search-genetic command";
        // Node throws this one guard before its CLI try/catch, so its stderr
        // additionally contains runtime-version/path-dependent stack text.
        assertThat(java.stderr()).isEqualTo(message + "\n");
    }

    @Test
    void acceptanceContractCliEmitsCanonicalContract() throws Exception {
        Result result = javaCli("acceptance-contract", "--id", "fixture", "--profile", "balanced-swing-v1");
        assertThat(result.exit()).describedAs(result.stderr()).isZero();
        ObjectNode contract = (ObjectNode) MAPPER.readTree(result.stdout());
        assertThat(contract.path("contract_id").asText()).isEqualTo("fixture");
        assertThat(contract.path("profile").asText()).isEqualTo("balanced-swing-v1");
        assertThat(LegacyResearchV3.validateAcceptanceContract(contract)).isTrue();
    }

    @Test
    void v3MetricsAndAcceptanceCliRoundTrip() throws Exception {
        ArrayNode trades = array()
                .add(trade("a", 1, 2, 0.8, 80))
                .add(trade("b", 3, 4, -0.2, -20))
                .add(trade("c", 5, 6, 0.4, 40));
        Path tradesPath = write(temporary.resolve("trades.json"), trades);
        Result javaMetrics = javaCli("v3-metrics", "--trades", tradesPath.toString(),
                "--candidate", "fixture", "--asset", "btc", "--candidate-count", "1",
                "--initial-equity", "1000", "--seed", "7", "--iterations", "32");
        assertThat(javaMetrics.exit()).describedAs(javaMetrics.stderr()).isZero();
        assertThat(MAPPER.readTree(javaMetrics.stdout()).path("candidate_id").asText())
                .isEqualTo("fixture");

        Path metricsPath = temporary.resolve("metrics.json");
        java.nio.file.Files.writeString(metricsPath, javaMetrics.stdout());
        Result accepted = javaCli("v3-accept", "--metrics", metricsPath.toString(),
                "--phase", "DEVELOPMENT");
        assertThat(accepted.exit()).describedAs(accepted.stderr()).isZero();
        assertThat(MAPPER.readTree(accepted.stdout()).path("phase").asText())
                .isEqualTo("DEVELOPMENT");
    }

    @Test
    void statsAndMonitorCliProduceDeterministicJson() throws Exception {
        ArrayNode candidates = array()
                .add(object().put("candidate_id", "a").set("rows", array()
                        .add(object().put("episode_id", "e1").put("net_r", 0.5))
                        .add(object().put("episode_id", "e2").put("net_r", -0.1))))
                .add(object().put("candidate_id", "b").set("rows", array()
                        .add(object().put("episode_id", "e1").put("net_r", -0.2))
                        .add(object().put("episode_id", "e2").put("net_r", 0.3))));
        Path input = write(temporary.resolve("candidate-returns.json"), candidates);
        Result stats = javaCli("stats", "--input", input.toString(), "--candidate", "a",
                "--iterations", "40", "--seed", "4", "--block-size", "1");
        assertThat(stats.exit()).describedAs(stats.stderr()).isZero();
        assertThat(MAPPER.readTree(stats.stdout()).isObject()).isTrue();

        ObjectNode profile = object();
        profile.set("frequency", object().put("min", 0).put("max", 10));
        profile.set("win_rate", object().put("min", 0).put("max", 1));
        profile.set("expectancy_r", object().put("min", -1).put("max", 2));
        profile.put("minimum_trades", 1);
        ObjectNode evidence = object()
                .put("monitoring_start", "2026-01-01T00:00:00Z")
                .put("monitoring_end", "2026-02-01T00:00:00Z");
        evidence.set("trades", array().add(object().put("signal_id", "one")
                .put("signal_time", "2026-01-15T00:00:00Z").put("net_r", 0.4)));
        Path profilePath = write(temporary.resolve("profile.json"), profile);
        Path evidencePath = write(temporary.resolve("evidence.json"), evidence);
        Result monitor = javaCli("monitor", "--profile", profilePath.toString(),
                "--evidence", evidencePath.toString());
        assertThat(monitor.exit()).describedAs(monitor.stderr()).isZero();
        assertThat(MAPPER.readTree(monitor.stdout()).path("status").asText()).isNotBlank();
    }

    @Test
    void commonFailureBoundariesFailClosed() throws Exception {
        Result precommit = javaCli("precommit");
        assertThat(precommit.exit()).isEqualTo(1);
        assertThat(precommit.stderr()).isNotBlank();
        Path unsupported = write(temporary.resolve("unsupported.json"),
                object().put("schema", "unsupported/1"));
        Result record = javaCli("record", "--input", unsupported.toString(),
                "--root", temporary.resolve("root").toString());
        assertThat(record.exit()).isEqualTo(1);
        assertThat(record.stderr()).contains("unsupported");
        Result generate = javaCli("generate", "--definition", unsupported.toString());
        assertThat(generate.exit()).isEqualTo(1);
        assertThat(generate.stderr()).isNotBlank();
    }

    @Test
    void nextAndV5CommandsFailClosedAtTheLegacyBoundary() {
        Result next = javaCli("next-stats", "--input", "missing.json");
        assertThat(next.exit()).isEqualTo(1);
        assertThat(next.stderr()).isEqualTo(
                "strategy-research-next command is not part of the legacy adapter: next-stats\n");
        Result v5 = javaCli("research-run");
        assertThat(v5.exit()).isEqualTo(1);
        assertThat(v5.stderr()).isEqualTo(
                "authoritative v5 command is not part of the legacy adapter: research-run\n");
    }

    @Test
    void evaluateV3CliBuildsTheOriginalBoundEvidenceAndZeroEpisodeDigest() throws Exception {
        EvaluationFixture fixture = evaluationFixture("evaluate", true);
        Result evaluated = javaCli("evaluate-v3",
                "--experiment", fixture.experiment().toString(),
                "--manifest", fixture.snapshot().manifest().toString(),
                "--features", fixture.snapshot().featureSet().toString(),
                "--labels", fixture.snapshot().labelSet().toString(),
                "--candidates", fixture.candidates().toString(),
                "--record-root", fixture.records().toString());
        assertThat(evaluated.exit()).describedAs(evaluated.stderr()).isZero();
        ObjectNode output = (ObjectNode) MAPPER.readTree(evaluated.stdout());
        assertThat(output.path("run_id").asText()).hasSize(64);
        assertThat(MAPPER.readTree(Files.readAllBytes(
                fixture.records().resolve("index-v3.json"))).path("runs")).hasSize(1);
        ObjectNode bundle = (ObjectNode) MAPPER.readTree(Files.readAllBytes(
                Path.of(output.path("evidence_bundle").asText())));
        assertThat(bundle.path("trades").size()).isLessThanOrEqualTo(8);
        assertThat(bundle.path("trades")).allSatisfy(trade ->
                assertThat(trade.path("zero_episode").asBoolean(false)).isFalse());
        assertThat(bundle.path("candidate_accounting")
                .path("zero_episode_digest_sha256").asText()).hasSize(64);
    }

    @Test
    void evaluateV3RegistryListShowCompareRebuildAndValidateMatchOriginalContract()
            throws Exception {
        EvaluationFixture fixture = evaluationFixture("registry", false);
        Result evaluated = javaCli("evaluate-v3",
                "--experiment", fixture.experiment().toString(),
                "--manifest", fixture.snapshot().manifest().toString(),
                "--features", fixture.snapshot().featureSet().toString(),
                "--labels", fixture.snapshot().labelSet().toString(),
                "--candidates", fixture.candidates().toString(),
                "--record-root", fixture.records().toString());
        assertThat(evaluated.exit()).describedAs(evaluated.stderr()).isZero();
        String runId = MAPPER.readTree(evaluated.stdout()).path("run_id").asText();

        Result listed = javaCli("list", "--root", fixture.records().toString());
        assertThat(listed.exit()).describedAs(listed.stderr()).isZero();
        assertThat(MAPPER.readTree(listed.stdout())).hasSize(1);
        Result shown = javaCli("show", "--root", fixture.records().toString(),
                "--id", runId);
        assertThat(shown.exit()).describedAs(shown.stderr()).isZero();
        assertThat(MAPPER.readTree(shown.stdout()).path("run").path("run_id").asText())
                .isEqualTo(runId);
        Result compared = javaCli("compare", "--root", fixture.records().toString(),
                "--left", runId, "--right", runId);
        assertThat(compared.exit()).describedAs(compared.stderr()).isZero();
        assertThat(MAPPER.readTree(compared.stdout()).path("left").asText()).isEqualTo(runId);
        Result rebuilt = javaCli("rebuild-index", "--root", fixture.records().toString());
        assertThat(rebuilt.exit()).describedAs(rebuilt.stderr()).isZero();
        Result validated = javaCli("validate", "--root", fixture.records().toString());
        assertThat(validated.exit()).describedAs(validated.stderr()).isZero();
        assertThat(MAPPER.readTree(validated.stdout()).path("valid").asBoolean()).isTrue();
    }

    private EvaluationFixture evaluationFixture(String id, boolean enriched) throws Exception {
        long barMillis = 14_400_000L;
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < 8; index++) {
            ObjectNode row = object().put("asset", "btc")
                    .put("event_time", index * barMillis)
                    .put("availability_time", (index + 1L) * barMillis)
                    .put("open", 100).put("high", 101).put("low", 99).put("close", 100)
                    .put("timeframe", "4h").put("resolution_bars", 1);
            if (enriched) {
                row.put("framework", "fallen_knives")
                        .put("setup_family", "FK_REVERSAL_RECLAIM")
                        .put("flow_aligned_rows", 5).put("stop_distance_pct", 1)
                        .put("equity_usd", 100_000).put("target", 0.1);
                row.set("setup_families", array().add("FK_REVERSAL_RECLAIM"));
                row.set("trigger", object().put("valid", true).put("timeframe", "4h")
                        .put("completed_bar", true).put("age_bars", 0));
                row.set("legs", object().put("flow", 5).put("technical", 4)
                        .put("macro", 3).put("sentiment", 3).put("valuation", 3)
                        .put("structure", 2));
            } else {
                row.put("target", 0.1);
            }
            rows.append(MAPPER.writeValueAsString(row)).append('\n');
        }
        Path input = temporary.resolve(id + "-rows.jsonl");
        Files.writeString(input, rows, StandardCharsets.UTF_8);
        ResearchData.SnapshotResult snapshot = ResearchData.snapshot(
                new ResearchData.SnapshotOptions(input, temporary.resolve(id + "-lake"),
                        id, null, null, null, "T0_IMMUTABLE_EVENT", "FEATURE", "jsonl",
                        "public", true, null, null, null, null, null, null));
        ObjectNode manifest = (ObjectNode) MAPPER.readTree(Files.readAllBytes(snapshot.manifest()));
        ObjectNode featureSet = (ObjectNode) MAPPER.readTree(
                Files.readAllBytes(snapshot.featureSet()));
        ObjectNode labelSet = (ObjectNode) MAPPER.readTree(Files.readAllBytes(snapshot.labelSet()));
        ObjectNode candidates = object().put("schema", "strategy-candidate-set/2")
                .put("declared_k", 1).put("effective_k", 1);
        ObjectNode definition = object().put("framework", "fallen_knives")
                .put("direction", "long").put("phase", "1A")
                .put("setup_family", "FK_REVERSAL_RECLAIM")
                .put("trigger_window_bars", 2).put("stop_pct", 1).put("target_r", 1.5);
        ObjectNode candidate = object().put("candidate_id", "candidate-1");
        candidate.set("definition", definition);
        candidates.set("candidates", array().add(candidate));
        Path candidatePath = write(temporary.resolve(id + "-candidates.json"), candidates);

        ObjectNode options = object().put("experimentId", id + "-cli")
                .put("precommitSha256", "a".repeat(64))
                .put("definitionSha256", "b".repeat(64))
                .put("candidateSetSha256", LegacyResearchV3.hash(candidates))
                .put("dataManifestSha256", manifest.path("content_sha256").asText())
                .put("featureSetSha256", featureSet.path("content_sha256").asText())
                .put("labelSetSha256", labelSet.path("content_sha256").asText());
        options.set("requiredAssets", array().add("btc"));
        options.set("acceptanceContract", LegacyResearchV3.makeAcceptanceContract());
        options.set("chronology", object().put("timezone", "UTC")
                .put("bar_convention", "completed-bar-next-open")
                .set("seeds", array().add(enriched ? 11 : 3)));
        Path experimentPath = write(temporary.resolve(id + "-experiment.json"),
                LegacyResearchV3.makeExperimentV3(options));
        return new EvaluationFixture(snapshot, experimentPath, candidatePath,
                temporary.resolve(id + "-records"));
    }

    private static ObjectNode trade(
            String id, long entry, long exit, double netR, double pnl) {
        return object().put("trade_id", id).put("candidate_id", "fixture")
                .put("asset", "btc").put("episode_id", "episode-" + id)
                .put("entry_time", entry).put("exit_time", exit)
                .put("net_r", netR).put("net_pnl", pnl)
                .put("fee_r", 0.01).put("slippage_r", 0.01)
                .put("funding_debit_r", 0).put("regime", "RANGE");
    }

    private static Result javaCli(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = LegacyResearchCommandAdapter.run(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exit, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static Path write(Path path, com.fasterxml.jackson.databind.JsonNode value)
            throws Exception {
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        return path;
    }

    private record Result(int exit, String stdout, String stderr) {}

    private record EvaluationFixture(
            ResearchData.SnapshotResult snapshot, Path experiment, Path candidates,
            Path records) {}
}
