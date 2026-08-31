package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyResearchUiExportV5Test {

    @Test
    void fixtureIsCanonicalDeterministicAndExplicitlyNotEvidence() throws Exception {
        JsonNode first = StrategyResearchUiExportV5.fixture();
        JsonNode second = StrategyResearchUiExportV5.fixture();

        assertThat(NodePrettyJson.write(first)).isEqualTo(NodePrettyJson.write(second));
        assertThat(first.path("schema").asText()).isEqualTo("strategy-research-ui/1");
        assertThat(first.path("presentation_only").asBoolean()).isTrue();
        assertThat(first.path("fixture_only").asBoolean()).isTrue();
        assertThat(first.path("manifest").path("limitations").toString()).contains("NOT EVIDENCE");
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(first);
        assertThat(first.path("content_sha256").asText()).matches("[a-f0-9]{64}");
        JsonNode oracle = com.tradinganalytics.infrastructure.security.JsonHashes.mapper().readTree(
                getClass().getResourceAsStream("/oracles/strategy-research-ui-v5-fixture.json"));
        assertThat(first).isEqualTo(oracle);
    }

    @Test
    void legacyIndexIsHonestBlockedStateAndNeverProjected() throws Exception {
        Path root = Files.createTempDirectory("strategy-research-ui-legacy-");
        Files.writeString(root.resolve("index.json"), "{\"schema\":\"strategy-research-index/1\"}\n",
                StandardCharsets.UTF_8);

        JsonNode result = StrategyResearchUiExportV5.export(root);

        assertThat(result.path("manifest").path("status").asText()).isEqualTo("BLOCKED");
        assertThat(result.path("readiness").path("blockers").toString()).contains("LEGACY_INDEX");
        assertThat(result.path("strategies")).isEmpty();
    }

    @Test
    void readinessOnlyIndexProjectsNewestReadinessAndStaysEmpty() throws Exception {
        Path sourceRoot = Path.of("strategy-research", "v5-records");
        assertThat(Files.isRegularFile(sourceRoot.resolve("index.json"))).isTrue();

        JsonNode result = StrategyResearchUiExportV5.export(sourceRoot);

        assertThat(result.path("manifest").path("status").asText()).isEqualTo("BLOCKED");
        assertThat(result.path("strategies")).isEmpty();
        assertThat(result.path("readiness").path("status").asText()).isEqualTo("BLOCKED");
        assertThat(result.path("readiness").path("source").path("schema").asText())
                .isEqualTo("strategy-readiness-audit/2");
        assertThat(result.path("readiness").path("source").path("content_sha256").asText())
                .matches("[a-f0-9]{64}");
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(result);
    }

    @Test
    void relocationAndIndexedContentOrByteTamperingAreHandledOnPublicPath() throws Exception {
        Path sourceRoot = Path.of("strategy-research", "v5-records");
        Path first = copyIndexedRoot(sourceRoot);
        Path second = copyIndexedRoot(sourceRoot);
        assertThat(NodePrettyJson.write(StrategyResearchUiExportV5.export(first)))
                .isEqualTo(NodePrettyJson.write(StrategyResearchUiExportV5.export(second)));

        Path badContent = copyIndexedRoot(sourceRoot);
        ObjectNode contentIndex = readIndex(badContent);
        ((ObjectNode) contentIndex.path("records").get(0)).put("content_sha256", "0".repeat(64));
        contentIndex.put("content_sha256", StrategyResearchV5.ownHash(contentIndex));
        writeIndex(badContent, contentIndex);
        assertThatThrownBy(() -> StrategyResearchUiExportV5.export(badContent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content hash mismatch");

        Path badBytes = copyIndexedRoot(sourceRoot);
        ObjectNode byteIndex = readIndex(badBytes);
        ((ObjectNode) byteIndex.path("records").get(0)).put("byte_sha256", "0".repeat(64));
        byteIndex.put("content_sha256", StrategyResearchV5.ownHash(byteIndex));
        writeIndex(badBytes, byteIndex);
        assertThatThrownBy(() -> StrategyResearchUiExportV5.export(badBytes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byte hash mismatch");
    }

    @Test
    void missingRootAndUndeclaredPredictorFailClosed() {
        assertThatThrownBy(() -> StrategyResearchUiExportV5.export(Path.of("/definitely/not/a/root")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void publicExportDoesNotExposeSignalIntentWithoutADeclaredPredictorRegistry() throws Exception {
        Path root = Files.createTempDirectory("strategy-research-ui-predictor-");
        ObjectNode run = minimalRun();
        byte[] runBytes = NodePrettyJson.write(run).getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("run.json"), runBytes);
        ObjectNode index = com.tradinganalytics.infrastructure.security.JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-research-index/5").put("version", 1);
        ArrayNode records = index.putArray("records");
        records.addObject().put("schema", "strategy-research-run/5")
                .put("content_sha256", run.path("content_sha256").asText())
                .put("byte_sha256", com.tradinganalytics.infrastructure.security.JsonHashes.sha256(runBytes))
                .put("path", "run.json").put("strategy_family_id", "demo-family").put("strategy_version", "v1");
        index.put("content_sha256", StrategyResearchV5.ownHash(index));
        writeIndex(root, index);

        JsonNode result = StrategyResearchUiExportV5.export(root);
        JsonNode projected = result.path("strategies").get(0).path("runs").get(0);
        assertThat(projected.path("identity").path("family_id").asText()).isEqualTo("demo-family");
        assertThat(projected.path("identity").path("run_hash").asText()).isEqualTo(projected.path("run_hash").asText());
        assertThat(projected.get("market")).isNull();
        assertThat(projected.get("signals")).isNull();
        assertThat(projected.path("candidates").get(0).get("signals")).isNull();
        assertThat(projected.path("candidates").get(0).path("performance").path("expectancy_r").asInt())
                .isZero();
    }

    @Test
    void skipsRunsWithoutVersionAndFallsBackToDecisionAndEvidencePhase() throws Exception {
        Path root = Files.createTempDirectory("strategy-research-ui-identity-");
        ObjectNode indexed = minimalRun().put("evidence_phase", "DEVELOPMENT");
        indexed.put("content_sha256", StrategyResearchV5.ownHash(indexed));
        ObjectNode missingVersion = minimalRun().put("strategy_family_id", "missing-version-family");
        missingVersion.remove("strategy_version");
        missingVersion.put("content_sha256", StrategyResearchV5.ownHash(missingVersion));
        writeRun(root, "indexed.json", indexed, "demo-family", "v1");
        writeRun(root, "missing-version.json", missingVersion, "missing-version-family", "");

        ObjectNode index = com.tradinganalytics.infrastructure.security.JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-research-index/5").put("version", 1);
        ArrayNode records = index.putArray("records");
        addIndexRecord(records, "indexed.json", indexed, "demo-family", "v1");
        addIndexRecord(records, "missing-version.json", missingVersion, "missing-version-family", null);
        index.put("content_sha256", StrategyResearchV5.ownHash(index));
        writeIndex(root, index);

        JsonNode result = StrategyResearchUiExportV5.export(root);

        assertThat(result.path("strategies")).hasSize(1);
        JsonNode run = result.path("strategies").get(0).path("runs").get(0);
        assertThat(run.path("status").asText()).isEqualTo("REJECTED");
        assertThat(run.path("stage").asText()).isEqualTo("DEVELOPMENT");
        assertThat(result.path("manifest").path("limitations").toString()).contains("missing strategy version");
    }

    private static ObjectNode minimalRun() {
        var mapper = com.tradinganalytics.infrastructure.security.JsonHashes.mapper();
        String manifest = StrategyResearchV5.hash("manifest");
        String features = StrategyResearchV5.hash("features");
        String labels = StrategyResearchV5.hash("labels");
        String execution = StrategyResearchV5.hash("execution");
        String marks = StrategyResearchV5.hash("marks");
        ObjectNode run = mapper.createObjectNode().put("schema", "strategy-research-run/5").put("version", 1)
                .put("provenance", "AUTHORITATIVE_BLOCKED").put("strategy_family_id", "demo-family")
                .put("strategy_version", "v1").put("manifest_sha256", manifest)
                .put("feature_rows_sha256", features).put("label_rows_sha256", labels)
                .put("execution_rows_sha256", execution).put("mark_rows_sha256", marks)
                .put("decision", "REJECTED");
        run.putArray("pipeline").add("features").add("signal_intent").add("labels").add("execution_fills")
                .add("trades").add("metrics").add("stresses").add("portfolio").add("wfo");
        run.putObject("lineage").put("manifest_sha256", manifest).put("feature_rows_sha256", features)
                .put("label_rows_sha256", labels).put("execution_rows_sha256", execution)
                .put("mark_rows_sha256", marks);
        ObjectNode metric = run.putArray("candidate_metrics").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", StrategyResearchV5.hash("behavior"));
        metric.putArray("signal_intent").addObject().put("predictor", "future_return");
        metric.putArray("trades");
        metric.putObject("metrics").put("expectancy_r", 0).putArray("episode_returns");
        metric.putObject("stresses"); metric.putObject("portfolio");
        run.putObject("accounting").put("declared_k", 1).put("evaluated_k", 1)
                .put("market_episode_count", 0).put("zero_episode_binding", true);
        run.putObject("gate_status").put("wfo", false).put("stress", false).put("portfolio", false)
                .put("all_required_stages", false);
        run.put("content_sha256", StrategyResearchV5.ownHash(run));
        return run;
    }

    private static void writeRun(Path root, String name, ObjectNode run, String family, String version) throws IOException {
        byte[] bytes = NodePrettyJson.write(run).getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve(name), bytes);
    }

    private static void addIndexRecord(ArrayNode records, String name, ObjectNode run, String family, String version) throws IOException {
        byte[] bytes = NodePrettyJson.write(run).getBytes(StandardCharsets.UTF_8);
        ObjectNode record = records.addObject().put("schema", "strategy-research-run/5")
                .put("content_sha256", run.path("content_sha256").asText())
                .put("byte_sha256", com.tradinganalytics.infrastructure.security.JsonHashes.sha256(bytes))
                .put("path", name).put("strategy_family_id", family);
        if (version == null) record.putNull("strategy_version");
        else record.put("strategy_version", version);
    }

    private static Path copyIndexedRoot(Path sourceRoot) throws IOException {
        Path target = Files.createTempDirectory("strategy-research-ui-index-");
        JsonNode index = com.tradinganalytics.infrastructure.security.JsonHashes.mapper()
                .readTree(Files.readString(sourceRoot.resolve("index.json"), StandardCharsets.UTF_8));
        Files.copy(sourceRoot.resolve("index.json"), target.resolve("index.json"));
        for (JsonNode record : index.path("records")) {
            Path source = sourceRoot.resolve(record.path("path").asText());
            Path targetPath = target.resolve(record.path("path").asText());
            Files.createDirectories(Objects.requireNonNull(targetPath.getParent()));
            Files.copy(source, targetPath);
        }
        return target;
    }

    private static ObjectNode readIndex(Path root) throws IOException {
        return (ObjectNode) com.tradinganalytics.infrastructure.security.JsonHashes.mapper()
                .readTree(Files.readString(root.resolve("index.json"), StandardCharsets.UTF_8));
    }

    private static void writeIndex(Path root, ObjectNode index) throws IOException {
        Files.writeString(root.resolve("index.json"), NodePrettyJson.write(index), StandardCharsets.UTF_8);
    }
}
