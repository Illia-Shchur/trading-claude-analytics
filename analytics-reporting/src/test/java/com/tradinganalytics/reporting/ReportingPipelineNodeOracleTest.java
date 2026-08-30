package com.tradinganalytics.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradinganalytics.core.lib.ToolchainSupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportingPipelineNodeOracleTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    @TempDir Path temporaryDirectory;

    @Test
    void signalFeedProjectionAndDryRunCliMatchNodeExactly() throws Exception {
        OracleResult nodeDry = oracle("signal-feed-dry-run");
        ReportingCommandResult javaDry = ExportSignalsCommand.run(List.of("--dry-run"), ROOT, Instant.parse("2026-08-28T00:00:00Z"));
        assertThat(javaDry.exitCode()).isEqualTo(nodeDry.exitCode());
        assertThat(normalize(javaDry.stdout(), ROOT)).isEqualTo(normalize(nodeDry.stdout(), ROOT));
        assertThat(normalize(javaDry.stderr(), ROOT)).isEqualTo(normalize(nodeDry.stderr(), ROOT));

        JsonNode parsed = frozenJson("/oracles/reporting-signal-feed-v1.json");
        Instant generatedAt = Instant.parse(parsed.path("generated_at").asText());
        String oracle = ToolchainSupport.canonicalJSON(parsed);
        Path output = ROOT.resolve("exports/.codex-reporting-oracle-write-current.json");
        try {
            ReportingCommandResult javaWrite = ExportSignalsCommand.run(
                    List.of("--out", "exports/.codex-reporting-oracle-write-current.json"), ROOT, generatedAt);
            assertEquivalent(oracle("signal-feed-write"), javaWrite, ROOT);
        } finally {
            Files.deleteIfExists(output);
        }
        String actual = ToolchainSupport.canonicalJSON(ExportSignalsCommand.project(ROOT.resolve("reports"), generatedAt).feed());
        assertThat(actual.equals(oracle)).as(mismatch(oracle, actual)).isTrue();
    }

    @Test
    void swingV3SignalProjectionMatchesNodeExactly() throws Exception {
        Path reports = temporaryDirectory.resolve("swing-reports");
        Files.createDirectories(reports);
        JsonNode report = ReportContract.parseStrictJSON(
                Files.readString(ROOT.resolve("tools/fixtures/report-machine-3.sample.json")), "report-machine-3.sample.json");
        String filename = report.path("identity").path("filename").asText();
        Files.writeString(reports.resolve(filename), ReportContract.canonicalReportJSON(report));
        Files.writeString(reports.resolve(filename.replace(".json", ".md")), ReportRenderer.renderSwingFull(report));

        JsonNode parsed = frozenJson("/oracles/reporting-swing-feed-v1.json");
        Instant generatedAt = Instant.parse(parsed.path("generated_at").asText());
        String expected = ToolchainSupport.canonicalJSON(parsed);
        Path output = ROOT.resolve("exports/.codex-reporting-oracle-swing-feed-current.json");
        try {
            ReportingCommandResult javaWrite = ExportSignalsCommand.run(
                    List.of("--reports", reports.toString(), "--out", "exports/.codex-reporting-oracle-swing-feed-current.json"),
                    ROOT, generatedAt);
            assertEquivalent(oracle("signal-swing-feed-write"), javaWrite, reports, ROOT, temporaryDirectory);
        } finally {
            Files.deleteIfExists(output);
        }
        String actual = ToolchainSupport.canonicalJSON(ExportSignalsCommand.project(reports, generatedAt).feed());
        assertThat(actual.equals(expected)).as(mismatch(expected, actual)).isTrue();
    }

    @Test
    void signalExportStrictFailuresAndWriteBoundaryMatchNodeExactly() throws Exception {
        OracleResult nodeBoundary = oracle("signal-feed-boundary");
        ReportingCommandResult javaBoundary = ExportSignalsCommand.run(
                List.of("--dry-run", "--out", "reports/not-a-feed.json"), ROOT, Instant.EPOCH);
        assertThat(javaBoundary.exitCode()).isEqualTo(nodeBoundary.exitCode());
        assertThat(normalize(javaBoundary.stdout(), ROOT)).isEqualTo(normalize(nodeBoundary.stdout(), ROOT));
        assertThat(normalize(javaBoundary.stderr(), ROOT)).isEqualTo(normalize(nodeBoundary.stderr(), ROOT));

        Path missing = temporaryDirectory.resolve("strict-missing");
        Files.createDirectories(missing);
        Files.writeString(missing.resolve("btc_fallen_knives_20260828_0101.md"), "# prose-only\n");
        assertExportFailureMatchesOracle(missing, "signal-strict-missing");

        Path malformed = temporaryDirectory.resolve("strict-malformed");
        Files.createDirectories(malformed);
        Files.writeString(malformed.resolve("btc_fallen_knives_20260828_0102.md"),
                "```json machine\n{\n```\n");
        assertExportFailureMatchesOracle(malformed, "signal-strict-malformed");

        Path mismatched = temporaryDirectory.resolve("strict-mismatch");
        Files.createDirectories(mismatched);
        Path source = ROOT.resolve("reports/btc_fallen_knives_20260822_0346.json");
        JsonNode canonical = ReportContract.parseStrictJSON(Files.readString(source), source.getFileName().toString());
        String filename = canonical.path("identity").path("filename").asText();
        Files.writeString(mismatched.resolve(filename), ReportContract.canonicalReportJSON(canonical));
        JsonNode altered = canonical.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) altered.path("narrative")).put("summary", "pair deliberately changed");
        Files.writeString(mismatched.resolve(filename.replace(".json", ".md")), ReportRenderer.renderFull(altered));
        assertExportFailureMatchesOracle(mismatched, "signal-strict-mismatch");
    }

    @Test
    void historicalBackfillCheckMatchesCurrentFailClosedNodeBehavior() throws Exception {
        OracleResult node = oracle("backfill-check");
        ReportingCommandResult actual = BackfillReportPhaseRegistryCommand.run(List.of("--check"), ROOT);
        assertThat(actual.exitCode()).isEqualTo(node.exitCode());
        assertThat(normalize(actual.stdout(), ROOT)).isEqualTo(normalize(node.stdout(), ROOT));
        assertThat(normalize(actual.stderr(), ROOT)).isEqualTo(normalize(node.stderr(), ROOT));
    }

    @Test
    void backfillWritesTheSameMarkdownBytesAsNode() throws Exception {
        Path nodeRoot = temporaryDirectory.resolve("node"), javaRoot = temporaryDirectory.resolve("java");
        for (Path root : List.of(nodeRoot, javaRoot)) {
            Files.createDirectories(root.resolve("reports"));
            Files.copy(ROOT.resolve("reports/btc_fallen_knives_20260822_0346.md"), root.resolve("reports/btc_fallen_knives_20260822_0346.md"));
        }
        OracleResult node = oracle("backfill-write");
        ReportingCommandResult actual = BackfillReportPhaseRegistryCommand.run(List.of(), javaRoot);
        assertThat(actual.exitCode()).isEqualTo(node.exitCode());
        assertThat(normalize(actual.stdout(), nodeRoot, javaRoot, temporaryDirectory))
                .isEqualTo(normalize(node.stdout(), nodeRoot, javaRoot, temporaryDirectory));
        assertThat(normalize(actual.stderr(), nodeRoot, javaRoot, temporaryDirectory))
                .isEqualTo(normalize(node.stderr(), nodeRoot, javaRoot, temporaryDirectory));
        String expectedBytes = frozenText("/oracles/backfill-write.md");
        String actualBytes = Files.readString(javaRoot.resolve("reports/btc_fallen_knives_20260822_0346.md"));
        assertThat(actualBytes.equals(expectedBytes)).as(mismatch(expectedBytes, actualBytes)).isTrue();
    }

    @Test
    void historicalBackfillSuccessCorpusMatchesNodeExactly() throws Exception {
        Path nodeRoot = temporaryDirectory.resolve("backfill-success-node");
        Path javaRoot = temporaryDirectory.resolve("backfill-success-java");
        for (Path root : List.of(nodeRoot, javaRoot)) {
            Files.createDirectories(root.resolve("reports"));
            for (int index = 0; index < 67; index++) {
                String time = String.format("%02d%02d", index / 60, index % 60);
                String file = "btc_fallen_knives_20260801_" + time + ".md";
                String machine = "# report\n\n```json machine\n{\n"
                        + "  \"schema\": \"report-machine/1\",\n"
                        + "  \"framework\": \"fallen_knives\",\n"
                        + "  \"asset\": \"BTC\",\n"
                        + "  \"date\": \"2026-08-01\",\n"
                        + "  \"deployment\": {\"tranches\": []},\n"
                        + "  \"verdict\": \"HOLD\"\n}\n```\n";
                Files.writeString(root.resolve("reports").resolve(file), machine);
            }
            for (int index = 0; index < 66; index++) {
                String time = String.format("%02d%02d", index / 60, index % 60);
                Files.writeString(root.resolve("reports/eth_flying_rocket_20260802_" + time + ".md"), "# prose only\n");
            }
        }

        OracleResult node = oracle("backfill-success");
        ReportingCommandResult actual = BackfillReportPhaseRegistryCommand.run(List.of(), javaRoot);
        assertThat(actual.exitCode()).isEqualTo(node.exitCode()).isZero();
        assertThat(actual.stdout()).isEqualTo(node.stdout());
        assertThat(actual.stderr()).isEqualTo(node.stderr());
        Map<String, byte[]> expectedFiles = frozenZip("/oracles/backfill-success-v1.zip.b64");
        assertThat(expectedFiles).hasSize(133);
        for (Map.Entry<String, byte[]> expected : expectedFiles.entrySet()) {
            assertThat(Files.readAllBytes(javaRoot.resolve("reports").resolve(expected.getKey())))
                    .as(expected.getKey()).containsExactly(expected.getValue());
        }
    }

    @Test
    void finalizeAndRenderSuccessBytesAndCliStreamsMatchNode() throws Exception {
        Path nodeRoot = temporaryDirectory.resolve("finalize-node"), javaRoot = temporaryDirectory.resolve("finalize-java");
        prepareReportToolRoot(nodeRoot); prepareReportToolRoot(javaRoot);
        String filename = "btc_fallen_knives_20260822_0346.json";
        Files.copy(ROOT.resolve("reports").resolve(filename), nodeRoot.resolve("draft.json"));
        Files.copy(ROOT.resolve("reports").resolve(filename), javaRoot.resolve("draft.json"));

        OracleResult nodeFinalize = oracle("finalize-v2");
        ReportingCommandResult javaFinalize = FinalizeReportCommand.run(List.of("draft.json"), javaRoot, javaRoot);
        assertEquivalent(nodeFinalize, javaFinalize, nodeRoot, javaRoot);
        assertThat(Files.readAllBytes(javaRoot.resolve("reports").resolve(filename)))
                .containsExactly(frozenBytes("/oracles/finalize-v2.json"));

        OracleResult nodeRender = oracle("render-v2-full");
        ReportingCommandResult javaRender = RenderReportCommand.run(List.of("reports/" + filename, "--mode", "full", "--out", "reports/" + filename.replace(".json", ".md")), javaRoot, javaRoot);
        assertEquivalent(nodeRender, javaRender, nodeRoot, javaRoot);
        assertThat(Files.readAllBytes(javaRoot.resolve("reports").resolve(filename.replace(".json", ".md"))))
                .containsExactly(frozenBytes("/oracles/render-v2-full.md"));

        OracleResult nodeSummary = oracle("render-v2-summary");
        ReportingCommandResult javaSummary = RenderReportCommand.run(List.of("reports/" + filename, "--mode", "summary"), javaRoot, javaRoot);
        assertEquivalent(nodeSummary, javaSummary, nodeRoot, javaRoot);
    }

    @Test
    void swingV3FinalizeRenderAndLintCliMatchNodeExactly() throws Exception {
        Path nodeRoot = temporaryDirectory.resolve("swing-pipeline-node");
        Path javaRoot = temporaryDirectory.resolve("swing-pipeline-java");
        prepareReportToolRoot(nodeRoot); prepareReportToolRoot(javaRoot);
        for (Path root : List.of(nodeRoot, javaRoot))
            Files.copy(ROOT.resolve("tools/fixtures/report-machine-3.sample.json"), root.resolve("draft.json"));
        String filename = "btc_fallen_knives_20260822_1200.json";
        String markdown = filename.replace(".json", ".md");

        OracleResult nodeFinalize = oracle("finalize-v3");
        ReportingCommandResult javaFinalize = FinalizeReportCommand.run(List.of("draft.json"), javaRoot, javaRoot);
        assertEquivalent(nodeFinalize, javaFinalize, nodeRoot, javaRoot);
        assertThat(Files.readAllBytes(javaRoot.resolve("reports").resolve(filename)))
                .containsExactly(frozenBytes("/oracles/finalize-v3.json"));

        List<String> renderArgs = List.of("reports/" + filename, "--mode", "full", "--out", "reports/" + markdown);
        OracleResult nodeRender = oracle("render-v3-full");
        ReportingCommandResult javaRender = RenderReportCommand.run(renderArgs, javaRoot, javaRoot);
        assertEquivalent(nodeRender, javaRender, nodeRoot, javaRoot);
        assertThat(Files.readAllBytes(javaRoot.resolve("reports").resolve(markdown)))
                .containsExactly(frozenBytes("/oracles/render-v3-full.md"));

        OracleResult nodeLint = oracle("lint-v3");
        ReportingCommandResult javaLint = LintReportCommand.run(List.of("reports/" + markdown), javaRoot, javaRoot);
        assertEquivalent(nodeLint, javaLint, nodeRoot, javaRoot);
    }

    @Test
    void finalizeAndRenderFailClosedCliVectorsMatchNode() throws Exception {
        Path nodeRoot = temporaryDirectory.resolve("fail-node"), javaRoot = temporaryDirectory.resolve("fail-java");
        prepareReportToolRoot(nodeRoot); prepareReportToolRoot(javaRoot);
        for (List<String> args : List.of(List.<String>of(), List.of("missing.json"))) {
            OracleResult node = oracle(args.isEmpty() ? "finalize-usage" : "finalize-missing");
            ReportingCommandResult actual = FinalizeReportCommand.run(args, javaRoot, javaRoot);
            assertEquivalent(node, actual, nodeRoot, javaRoot);
        }
        OracleResult nodeRender = oracle("render-usage");
        ReportingCommandResult javaRender = RenderReportCommand.run(List.of(), javaRoot, javaRoot);
        assertEquivalent(nodeRender, javaRender, nodeRoot, javaRoot);
    }

    @Test
    void finalizeAndRenderPublicationGuardsMatchNodeExactly() throws Exception {
        Path nodeRoot = temporaryDirectory.resolve("guard-node"), javaRoot = temporaryDirectory.resolve("guard-java");
        prepareReportToolRoot(nodeRoot); prepareReportToolRoot(javaRoot);
        String filename = "btc_fallen_knives_20260822_0346.json";
        for (Path root : List.of(nodeRoot, javaRoot)) {
            Files.copy(ROOT.resolve("reports").resolve(filename), root.resolve("draft.json"));
            Files.writeString(root.resolve("duplicate.json"), "{\"a\":1,\"a\":2}\n");
        }
        List<List<String>> finalizeGuards = List.of(
                List.of("draft.json", "--out", "../outside.json"),
                List.of("draft.json", "--out", "reports/wrong_name.json"),
                List.of("draft.json", "--out", "reports/" + filename.replace(".json", ".md")),
                List.of("duplicate.json"));
        for (int index = 0; index < finalizeGuards.size(); index++) {
            List<String> args = finalizeGuards.get(index);
            OracleResult node = oracle("finalize-guard-" + index);
            ReportingCommandResult actual = FinalizeReportCommand.run(args, javaRoot, javaRoot);
            assertEquivalent(node, actual, nodeRoot, javaRoot);
        }

        List<List<String>> renderGuards = List.of(
                List.of("draft.json", "--mode", "invalid"),
                List.of("draft.json", "--mode", "summary", "--out", "reports/" + filename.replace(".json", ".md")),
                List.of("draft.json", "--mode", "full", "--out", "../outside.md"),
                List.of("draft.json", "--mode", "full", "--out", "reports/wrong_name.md"),
                List.of("duplicate.json", "--mode", "full"));
        for (int index = 0; index < renderGuards.size(); index++) {
            List<String> args = renderGuards.get(index);
            OracleResult node = oracle("render-guard-" + index);
            ReportingCommandResult actual = RenderReportCommand.run(args, javaRoot, javaRoot);
            assertEquivalent(node, actual, nodeRoot, javaRoot);
        }
    }

    @Test
    void lintCliMatchesNodeForEveryPublishedFrameworkReport() throws Exception {
        List<Path> reports;
        try (var stream = Files.list(ROOT.resolve("reports"))) {
            reports = stream.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> ToolchainSupport.reportFileMeta(path.getFileName().toString()).path("ok").asBoolean())
                    .sorted().toList();
        }
        Map<String, OracleResult> expected = publishedLintOracles();
        assertThat(expected).hasSize(reports.size());
        for (Path report : reports) {
            String relative = "reports/" + report.getFileName();
            OracleResult node = expected.get(report.getFileName().toString());
            ReportingCommandResult actual = LintReportCommand.run(List.of(relative), ROOT, ROOT);
            assertThat(actual.exitCode()).as(relative).isEqualTo(node.exitCode());
            assertThat(actual.stderr()).as(relative + " stderr").isEqualTo(node.stderr());
            assertThat(actual.stdout().equals(node.stdout())).as(relative + " " + mismatch(node.stdout(), actual.stdout())).isTrue();
        }
    }

    @Test
    void lintUsageLegacyAndSidecarFailuresMatchNodeExactly() throws Exception {
        OracleResult nodeUsage = oracle("lint-usage");
        ReportingCommandResult javaUsage = LintReportCommand.run(List.of(), ROOT, ROOT);
        assertThat(javaUsage.exitCode()).isEqualTo(nodeUsage.exitCode());
        assertThat(javaUsage.stdout()).isEqualTo(nodeUsage.stdout());
        assertThat(normalize(javaUsage.stderr(), ROOT)).isEqualTo(normalize(nodeUsage.stderr(), ROOT));

        Path legacy = temporaryDirectory.resolve("btc_fallen_knives_20260828_0210.md");
        Files.writeString(legacy, "# no machine payload\n");
        for (List<String> args : List.of(List.of(legacy.toString()), List.of(legacy.toString(), "--legacy"))) {
            OracleResult node = oracle(args.contains("--legacy") ? "lint-legacy-flag" : "lint-legacy");
            ReportingCommandResult actual = LintReportCommand.run(args, ROOT, ROOT);
            assertEquivalent(node, actual, temporaryDirectory);
        }

        Path malformed = temporaryDirectory.resolve("btc_fallen_knives_20260828_0211.md");
        Files.writeString(malformed, "```json machine\n{\n```\n");
        OracleResult nodeMalformed = oracle("lint-malformed");
        ReportingCommandResult javaMalformed = LintReportCommand.run(List.of(malformed.toString()), ROOT, ROOT);
        assertEquivalent(nodeMalformed, javaMalformed, temporaryDirectory);

        Path pairDirectory = temporaryDirectory.resolve("lint-pair");
        Files.createDirectories(pairDirectory);
        Path source = ROOT.resolve("reports/btc_fallen_knives_20260822_0346.json");
        JsonNode report = ReportContract.parseStrictJSON(Files.readString(source), source.getFileName().toString());
        String filename = report.path("identity").path("filename").asText();
        Path json = pairDirectory.resolve(filename), markdown = pairDirectory.resolve(filename.replace(".json", ".md"));
        Files.writeString(json, ReportContract.canonicalReportJSON(report));
        JsonNode altered = report.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) altered.path("narrative")).put("summary", "not the canonical sidecar");
        Files.writeString(markdown, ReportRenderer.renderFull(altered));
        OracleResult nodeMismatch = oracle("lint-mismatch");
        ReportingCommandResult javaMismatch = LintReportCommand.run(List.of(markdown.toString()), ROOT, ROOT);
        assertEquivalent(nodeMismatch, javaMismatch, pairDirectory);
    }

    private void assertExportFailureMatchesOracle(Path reports, String oracleName) throws Exception {
        OracleResult node = oracle(oracleName);
        ReportingCommandResult actual = ExportSignalsCommand.run(
                List.of("--reports", reports.toString(), "--dry-run", "--strict"), ROOT, Instant.EPOCH);
        assertEquivalent(node, actual, reports, temporaryDirectory);
    }

    private static OracleResult oracle(String name) throws IOException {
        JsonNode result = frozenJson("/oracles/reporting-pipeline-process-v1.json").path(name);
        assertThat(result.isObject()).as(name).isTrue();
        return new OracleResult(result.path("exitCode").asInt(), result.path("stdout").asText(), result.path("stderr").asText());
    }

    private static void assertEquivalent(OracleResult expected, ReportingCommandResult actual, Path... roots) {
        assertThat(actual.exitCode()).isEqualTo(expected.exitCode());
        assertThat(normalize(actual.stdout(), roots)).isEqualTo(normalize(expected.stdout(), roots));
        assertThat(normalize(actual.stderr(), roots)).isEqualTo(normalize(expected.stderr(), roots));
    }

    private static String normalize(String value, Path... roots) {
        for (Path root : roots) {
            try { value = value.replace(root.toRealPath().toString(), "<ROOT>"); } catch (Exception ignored) { }
            value = value.replace(root.toString(), "<ROOT>");
        }
        return value.replaceAll("<ROOT>/exports/\\.codex-reporting-(?:oracle-)?swing-feed-current\\.json",
                        "<ROOT>/exports/.codex-reporting-swing-feed-current.json")
                .replaceAll("(?:/private)?/var/folders/[^\\s:]*/outside\\.(json|md)", "<ROOT>/outside.$1")
                .replace("/private<ROOT>", "<ROOT>")
                .replace("/private/var/", "/var/")
                .replace("node tools/finalize-report.mjs", "./bin/analytics finalize-report")
                .replace("node tools/render-report.mjs", "./bin/analytics render-report")
                .replace("node tools/lint-report.mjs", "./bin/analytics lint-report");
    }

    private static JsonNode frozenJson(String resource) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                ReportingPipelineNodeOracleTest.class.getResourceAsStream(resource),
                "frozen reporting oracle is missing: " + resource)) {
            return ReportContract.parseStrictJSON(new String(input.readAllBytes(), StandardCharsets.UTF_8), resource);
        }
    }

    private static String frozenText(String resource) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                ReportingPipelineNodeOracleTest.class.getResourceAsStream(resource),
                "frozen reporting oracle is missing: " + resource)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] frozenBytes(String resource) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                ReportingPipelineNodeOracleTest.class.getResourceAsStream(resource),
                "frozen reporting oracle is missing: " + resource)) {
            return input.readAllBytes();
        }
    }

    private static Map<String, OracleResult> publishedLintOracles() throws IOException {
        Map<String, OracleResult> output = new LinkedHashMap<>();
        for (JsonNode entry : frozenJson("/oracles/reporting-lint-published-v1.json")) {
            output.put(entry.path("file").asText(), new OracleResult(
                    entry.path("exitCode").asInt(), entry.path("stdout").asText(), entry.path("stderr").asText()));
        }
        return output;
    }

    private static Map<String, byte[]> frozenZip(String resource) throws IOException {
        byte[] encoded = frozenText(resource).replaceAll("\\s+", "").getBytes(StandardCharsets.US_ASCII);
        Map<String, byte[]> output = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    zip.transferTo(bytes);
                    output.put(entry.getName(), bytes.toByteArray());
                }
            }
        }
        return output;
    }

    private static void prepareReportToolRoot(Path root) throws Exception {
        Files.createDirectories(root.resolve("schemas")); Files.createDirectories(root.resolve("reports"));
        for (String file : List.of("report-machine-2.schema.json", "report-machine-3.schema.json"))
            Files.copy(ROOT.resolve("schemas").resolve(file), root.resolve("schemas").resolve(file));
    }

    private static String mismatch(String expected, String actual) {
        int at = 0, limit = Math.min(expected.length(), actual.length()); while (at < limit && expected.charAt(at) == actual.charAt(at)) at++;
        int from = Math.max(0, at - 100), eTo = Math.min(expected.length(), at + 240), aTo = Math.min(actual.length(), at + 240);
        return "first mismatch " + at + " expected<" + expected.substring(from, eTo).replace("\n", "\\n")
                + "> actual<" + actual.substring(from, aTo).replace("\n", "\\n") + ">";
    }

    private record OracleResult(int exitCode, String stdout, String stderr) {}
}
