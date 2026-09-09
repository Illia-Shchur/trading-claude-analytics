package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public role-production integration with a valid file-backed source chain. */
final class StrategyResearchDataV5AuthoritativeRoleProductionBoundaryTest {
    private static final String H = "a".repeat(64);
    private static final long ONE_MINUTE = 60_000L;

    @Test
    void publicProducerInfersFeatureSourcesAndRejectsDatasetRootTampering(@TempDir Path root) throws Exception {
        long decision = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(object().put("asOf", "2026-08-24T20:30:00.000Z"));
        ObjectNode registryInput = object();
        registryInput.putArray("predictors").add(object().put("id", "momentum_1").put("scalar_type", "number")
                .put("source_field", "close").put("source_family", "price").put("lookback_ms", 14_400_000L)
                .put("availability_derivation", "completed_4h_close").put("code_sha256", H).put("config_sha256", H)
                .put("pit_role", "PREDICTOR"));
        ObjectNode registry = StrategyResearchDataV5.makePredictorRegistry(registryInput);
        ObjectNode envelopeInput = object().put("planSha256", plan.path("content_sha256").asText())
                .put("candidateSetSha256", H).put("maxLifecycleMs", 5 * 60_000).put("lifecycleTimeframe", "1m");
        ArrayNode windows = envelopeInput.putArray("windows");
        windows.add(object().put("asset", "btc").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                .put("execution_start", iso(decision)).put("execution_end", iso(decision + 4 * ONE_MINUTE)).put("window_id", "w1"));
        ObjectNode envelope = StrategyResearchDataV5.makeOpportunityEnvelope(envelopeInput);

        ObjectNode feature = identity("sig-role", "ep-role", decision).put("timeframe", "4h")
                .put("event_time", iso(decision)).put("availability_time", iso(decision)).put("open", 100)
                .put("high", 102).put("low", 99).put("close", 101).put("signal_eligible", true);
        feature.remove(List.of("signal_id", "episode_id", "signal_eligible"));
        ObjectNode label = identity("sig-role", "ep-role", decision);
        label.remove(List.of("signal_id", "episode_id"));
        ObjectNode execution = identity("sig-role", "ep-role", decision);
        execution.remove(List.of("signal_id", "episode_id"));
        ArrayNode children = execution.putArray("child_bars");
        for (int index = 0; index < 3; index++) children.add(roleChildBar(decision + index * ONE_MINUTE, 100 + index));
        ObjectNode mark = identity("sig-role", "ep-role", decision).put("series_role", "MARK")
                .put("series_id", "btc-spot-mark-1m").put("cadence_ms", ONE_MINUTE).put("event_time", iso(decision))
                .put("availability_time", iso(decision + ONE_MINUTE)).put("price", 100);
        mark.remove(List.of("signal_id", "episode_id"));

        ObjectNode featureRef = writeRoleInput(root, "raw/features.jsonl", feature);
        ObjectNode labelRef = writeRoleInput(root, "raw/labels.jsonl", label);
        ObjectNode executionRef = writeRoleInput(root, "raw/execution.jsonl", execution);
        ObjectNode markRef = writeRoleInput(root, "raw/marks.jsonl", mark);
        ArrayNode captures = array();
        List<ObjectNode> summaries = new ArrayList<>();
        summaries.add(addRoleCapture(root, captures, "features", featureRef, "4h", "raw_signal_bars", feature));
        summaries.add(addRoleCapture(root, captures, "labels", labelRef, "labels", "raw_opportunity_bars", label));
        summaries.add(addRoleCapture(root, captures, "execution", executionRef, "execution", "raw_execution_bars", execution));
        summaries.add(addRoleCapture(root, captures, "marks", markRef, "1m", "raw_mark_bars", mark));
        ObjectNode source = object().put("schema", "strategy-v5-authoritative-acquisition/1").put("version", 1)
                .put("status", "STAGING_COMPLETE").put("plan_sha256", plan.path("content_sha256").asText())
                .put("root_reference", "role-quarantine").put("staging_format", "JSONL").put("storage_role", "STAGING")
                .put("authoritative", false).put("base_complete", true).put("declared_complete", true)
                .put("full_plan_complete", true).put("completion_scope", "ALL_DECLARED").put("required_series_count", 4)
                .put("required_complete_count", 4).put("optional_series_count", 0).put("optional_complete_count", 0)
                .put("optional_complete", true);
        source.set("captures", captures);
        source.set("source_receipts", strings(summaries.stream().map(row -> text(row, "path")).sorted().toList()));
        source.set("source_receipt_sha256", strings(summaries.stream().map(row -> text(row, "content_sha256")).sorted().toList()));
        source.set("source_receipt_byte_sha256", strings(summaries.stream().map(row -> text(row, "byte_sha256")).sorted().toList()));
        source.set("unavailable_required", array());
        source.set("unavailable_optional", array());
        source.set("limitations", array());
        source = StrategyResearchDataV5.withHash(source);
        Path sourcePath = root.resolve("lineage/source-manifest.json");
        Files.createDirectories(sourcePath.getParent());
        byte[] sourceBytes = pretty(source);
        Files.write(sourcePath, sourceBytes);
        ObjectNode sourceReference = object().put("path", "lineage/source-manifest.json")
                .put("content_sha256", text(source, "content_sha256"))
                .put("byte_sha256", StrategyResearchDataV5.hash(sourceBytes));

        ObjectNode precommit = fixtureInput("strategy-v5-precommit-fixture/1", "role-precommit");
        ObjectNode config = fixtureInput("strategy-v5-config-fixture/1", "role-config");
        ObjectNode roles = object();
        roles.set("labels", array().add(labelRef));
        roles.set("execution", array().add(executionRef));
        roles.set("marks", array().add(markRef));
        ObjectNode common = object().put("root", root.toString());
        common.set("plan", plan);
        common.set("predictorRegistry", registry);
        common.set("sourceManifestReference", sourceReference);
        common.put("sourceManifestSha256", text(source, "content_sha256"));
        common.putNull("sourceDatasetRootSha256");
        common.put("transformationCodeSha256", StrategyResearchDataV5.javaProducerCodeSha256())
                .put("labelCodeSha256", StrategyResearchDataV5.javaProducerCodeSha256())
                .put("executionCodeSha256", StrategyResearchDataV5.javaProducerCodeSha256())
                .put("configSha256", text(config, "content_sha256"))
                .put("precommitSha256", text(precommit, "content_sha256"))
                .put("envelopeSha256", text(envelope, "content_sha256"));
        common.set("precommit", precommit);
        common.set("envelope", envelope);
        common.set("config", config);
        common.set("roleSources", roles);

        ObjectNode produced = StrategyResearchDataV5.produceAuthoritativeRoleArtifacts(common);
        assertThat(produced.path("feature").path("source_inventory")).hasSize(1);
        assertThat(produced.path("feature").path("source_path").asText()).isEqualTo("raw/features.jsonl");
        assertThat(produced.path("label").path("path").asText()).startsWith("derived/label/");
        assertThat(produced.path("execution").path("path").asText()).startsWith("derived/execution/");
        assertThat(produced.path("mark").path("path").asText()).startsWith("derived/mark/");
        for (String role : List.of("feature", "label", "execution", "mark")) {
            ObjectNode artifact = (ObjectNode) produced.path(role);
            Path artifactPath = root.resolve(artifact.path("path").asText());
            byte[] artifactBytes = Files.readAllBytes(artifactPath);
            List<ObjectNode> rows = readJsonl(artifactBytes);
            assertThat(StrategyResearchDataV5.hash(artifactBytes)).isEqualTo(artifact.path("sha256").asText());
            assertThat(artifact.path("format").asText()).isEqualTo("JSONL");
            assertThat(rows).hasSize(1);
            ObjectNode receiptRef = (ObjectNode) artifact.path("role_receipt");
            ObjectNode receipt = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(root.resolve(receiptRef.path("path").asText())));
            assertThat(StrategyResearchDataV5.ownHash(receipt)).isEqualTo(receipt.path("content_sha256").asText());
            assertThat(receiptRef.path("content_sha256").asText()).isEqualTo(receipt.path("content_sha256").asText());
            assertThat(receipt.path("artifact_sha256").asText()).isEqualTo(artifact.path("sha256").asText());
            assertThat(receipt.path("role").asText()).isEqualTo(role.toUpperCase());
        }
        List<ObjectNode> featureRows = readJsonl(Files.readAllBytes(root.resolve(produced.path("feature").path("path").asText())));
        assertThat(featureRows).hasSize(1);
        assertThat(featureRows.get(0).path("decision_time").asText()).isEqualTo(iso(decision));
        assertThat(featureRows.get(0).path("momentum_1").asDouble()).isEqualTo(101.0);
        List<ObjectNode> labelRows = readJsonl(Files.readAllBytes(root.resolve(produced.path("label").path("path").asText())));
        assertThat(labelRows.get(0).path("entry_time").asText()).isEqualTo(iso(decision));
        assertThat(labelRows.get(0).path("resolution_ceiling_time").asText()).isEqualTo(iso(decision + 2 * ONE_MINUTE));
        List<ObjectNode> executionRows = readJsonl(Files.readAllBytes(root.resolve(produced.path("execution").path("path").asText())));
        assertThat(executionRows.get(0).path("child_bars")).hasSize(3);
        assertThat(executionRows.get(0).path("child_bars").get(2).path("close").asDouble()).isEqualTo(102.0);
        List<ObjectNode> markRows = readJsonl(Files.readAllBytes(root.resolve(produced.path("mark").path("path").asText())));
        assertThat(markRows.get(0).path("price").asDouble()).isEqualTo(100.0);

        ObjectNode forged = common.deepCopy();
        forged.put("sourceDatasetRootSha256", "b".repeat(64));
        assertThatThrownBy(() -> StrategyResearchDataV5.produceAuthoritativeRoleArtifacts(forged))
                .hasMessageContaining("source dataset root hash does not match the verified physical source inventory");
    }

    private static ObjectNode addRoleCapture(Path root, ArrayNode captures, String role, ObjectNode reference,
            String interval, String seriesType, ObjectNode row) throws Exception {
        String rawBodyValue = "raw-role:" + role + ":" + text(row, "asset") + ":" + text(row, "instrument");
        byte[] rawBody = rawBodyValue.getBytes(StandardCharsets.UTF_8);
        String rawSha = StrategyResearchDataV5.hash(rawBody);
        String rawPath = "lineage/raw/" + rawSha + ".bin";
        Path rawFile = root.resolve(rawPath);
        Files.createDirectories(rawFile.getParent());
        Files.write(rawFile, rawBody);
        ObjectNode raw = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1)
                .put("path", rawPath).put("source", "FIXTURE_ROLE").put("byte_sha256", rawSha)
                .put("bytes", rawBody.length).put("format", "RAW_BYTES").put("storage_role", "RAW_IGNORED")
                .put("authoritative", false);
        raw.set("request", object().put("endpoint", "fixture://role/" + role).put("response_sha256", rawSha));
        raw = StrategyResearchDataV5.withHash(raw);
        ObjectNode normalized = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1)
                .put("status", "PUBLIC_OBSERVED").put("captured_at", "2026-08-24T12:00:00.000Z");
        normalized.set("request", object().put("endpoint", "fixture://role/" + role));
        normalized.set("response_sha256", array().add(rawSha));
        normalized.set("source_byte_sha256", array().add(rawSha));
        normalized.set("raw_receipts", array().add(raw));
        normalized.set("coverage", object().put("complete", true));
        normalized = StrategyResearchDataV5.withHash(normalized);
        String receiptPath = "lineage/receipts/" + text(normalized, "content_sha256") + ".json";
        Path receiptFile = root.resolve(receiptPath);
        Files.createDirectories(receiptFile.getParent());
        Files.write(receiptFile, pretty(normalized));
        ObjectNode summary = object().put("path", receiptPath).put("sha256", text(normalized, "content_sha256"))
                .put("content_sha256", text(normalized, "content_sha256")).put("byte_sha256", rawSha)
                .put("raw_count", 1).put("schema", "strategy-v5-source-receipt/1").put("status", "PUBLIC_OBSERVED");
        ObjectNode capture = object().put("asset", text(row, "asset")).put("venue", "BINANCE")
                .put("instrument", text(row, "instrument")).put("symbol", text(row, "symbol"))
                .put("interval", interval).put("series_type", seriesType)
                .put("series_role", "raw_mark_bars".equals(seriesType) ? "MARK" : "PRICE").put("required", true);
        ObjectNode partition = reference.deepCopy().put("bytes", Files.size(root.resolve(text(reference, "path"))))
                .put("row_count", 1).put("format", "JSONL").put("storage_role", "STAGING").put("authoritative", false);
        capture.set("partition", partition);
        capture.set("source_receipts", array().add(summary));
        capture.set("coverage", object().put("complete", true).put("expected_rows", 1).put("observed_rows", 1));
        captures.add(capture);
        return summary;
    }

    private static ObjectNode writeRoleInput(Path root, String relative, ObjectNode row) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        byte[] bytes = (JsonHashes.mapper().writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(path, bytes);
        return object().put("path", relative).put("sha256", StrategyResearchDataV5.hash(bytes));
    }

    private static ObjectNode fixtureInput(String schema, String name) {
        return StrategyResearchDataV5.withHash(object().put("schema", schema).put("version", 1).put("name", name));
    }

    private static ObjectNode identity(String signal, String episode, long decision) {
        return object().put("asset", "btc").put("venue", "BINANCE").put("instrument", "BINANCE_SPOT")
                .put("symbol", "BTCUSDT").put("decision_time", iso(decision)).put("signal_id", signal).put("episode_id", episode);
    }

    private static ObjectNode roleChildBar(long event, double close) {
        return object().put("event_time", iso(event)).put("availability_time", iso(event + ONE_MINUTE - 1))
                .put("open", close - 1).put("high", close + 1).put("low", close - 2).put("close", close);
    }

    private static String iso(long millis) {
        String value = Instant.ofEpochMilli(millis).toString();
        return value.contains(".") ? value : value.replace("Z", ".000Z");
    }

    private static String text(JsonNode value, String field) {
        return value == null || !value.hasNonNull(field) ? "" : value.path(field).asText();
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode result = array();
        values.forEach(result::add);
        return result;
    }

    private static byte[] pretty(JsonNode value) throws Exception {
        return (JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static List<ObjectNode> readJsonl(byte[] bytes) throws Exception {
        List<ObjectNode> rows = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isBlank()) rows.add((ObjectNode) JsonHashes.mapper().readTree(line));
        }
        return rows;
    }

    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
}
