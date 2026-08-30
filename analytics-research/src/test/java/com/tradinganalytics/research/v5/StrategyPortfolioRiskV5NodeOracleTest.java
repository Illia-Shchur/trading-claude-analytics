package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Differential/contract coverage for the ten public risk-tool bindings. */
final class StrategyPortfolioRiskV5NodeOracleTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final ObjectNode ORACLE = loadOracle();
    private static final long T0 = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
    private static final long HOUR = 3_600_000L;

    @Test
    void hashOwnHashAndWithHashMatchNodeOracle() throws Exception {
        ObjectNode input = object().put("z", 2).put("a", 1).put("content_sha256", "stale");
        JsonNode oracle = ORACLE.path("hash_binding");
        assertThat(StrategyPortfolioRiskV5.hash(input)).isEqualTo(oracle.path("hash").asText());
        assertThat(StrategyPortfolioRiskV5.ownHash(input)).isEqualTo(oracle.path("own_hash").asText());
        assertThat(StrategyPortfolioRiskV5.withHash(input)).isEqualTo(oracle.path("with_hash"));
    }

    @Test
    void scalarObjectAndNonFiniteHashOverloadsRemainCanonical() {
        ObjectNode finite = object().put("a", 1);
        assertThat(StrategyPortfolioRiskV5.hash((Object) finite)).isEqualTo(StrategyPortfolioRiskV5.hash(finite));
        assertThat(StrategyPortfolioRiskV5.hash((Object) "risk")).isEqualTo(StrategyPortfolioRiskV5.hash("risk"));
        assertThat(StrategyPortfolioRiskV5.hash((Object) "risk".getBytes(StandardCharsets.UTF_8))).isEqualTo(StrategyPortfolioRiskV5.hash("risk".getBytes(StandardCharsets.UTF_8)));
        ObjectNode nonFinite = object().put("nan", Double.NaN).put("infinity", Double.POSITIVE_INFINITY);
        ObjectNode nulls = object().putNull("nan").putNull("infinity");
        assertThat(StrategyPortfolioRiskV5.hash(nonFinite)).isEqualTo(StrategyPortfolioRiskV5.hash(nulls));
        assertThat(StrategyPortfolioRiskV5.ownHash((JsonNode) null)).isEqualTo(StrategyPortfolioRiskV5.hash(MAPPER.nullNode()));
        assertThat(StrategyPortfolioRiskV5.hash(MAPPER.createArrayNode().add(1).add(2))).isEqualTo(StrategyPortfolioRiskV5.hash(MAPPER.createArrayNode().add(1).add(2)));
        assertThat(StrategyPortfolioRiskV5.hash(MAPPER.getNodeFactory().binaryNode(new byte[]{1, 2}))).isEqualTo(StrategyPortfolioRiskV5.hash(MAPPER.nullNode()));
        JsonNode custom = StrategyPortfolioRiskV5.withHash((JsonNode) finite, "custom_hash");
        assertThat(custom.path("custom_hash").asText()).isEqualTo(StrategyPortfolioRiskV5.ownHash(custom, "custom_hash"));
    }

    @Test
    void fixtureRiskPathMeasuresPnlAndBindsPhysicalMetadata(@TempDir Path dir) {
        ObjectNode marks = StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("marks.json"), object().put("intervalMs", HOUR).set("rows", markRows()));
        ObjectNode fees = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("fees.json"), object().put("kind", "FEE_SCHEDULE").set("records", feeRows()));
        ObjectNode result = StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(request(marks, fees));
        assertThat(result.path("provenance").asText()).isEqualTo("FIXTURE");
        assertThat(result.path("marginal_risk_contribution").path("status").asText()).isEqualTo("MEASURED");
        assertThat(result.path("marginal_risk_contribution").path("common_timestamps").asInt()).isGreaterThanOrEqualTo(30);
        assertThat(result.path("market_diagnostics").path("common_count").asInt()).isGreaterThanOrEqualTo(30);
        assertThat(result.path("accepted_trades")).hasSize(2);
        assertThat(result.path("asset_decisions")).allMatch(n -> n.path("status").asText().equals("FIXTURE"));
        assertThat(result.path("aligned_pnl").path("vectors").path("btc").get(0).asDouble()).isBetween(-0.101, -0.099);
        assertThat(result.path("aligned_pnl").path("vectors").path("btc").get(31).asDouble()).isBetween(239.80, 239.82);
        assertThat(result.path("aligned_pnl").path("vectors").path("eth").get(31).asDouble()).isBetween(239.80, 239.82);
        assertThat(result.path("event_risk_path").path("path").get(0).path("gross").asDouble()).isBetween(199.9, 200.1);
        assertThat(result.path("event_risk_path").path("path").get(0).path("reserved_risk").asDouble()).isBetween(19.9, 20.1);
        assertThat(result.path("event_risk_path").path("path").get(0).path("concentration").path("hhi").asDouble()).isBetween(.49, .51);
        assertThat(result.path("content_sha256").asText()).isEqualTo(StrategyPortfolioRiskV5.ownHash(result));
    }

    @Test
    void hashesAndAllArtifactWritersMatchContractShape(@TempDir Path dir) {
        ObjectNode value = object().put("b", 2).put("a", 1);
        assertThat(StrategyPortfolioRiskV5.hash(value)).isEqualTo(StrategyPortfolioRiskV5.hash(object().put("a", 1).put("b", 2)));
        ObjectNode with = StrategyPortfolioRiskV5.withHash(value);
        assertThat(with.path("content_sha256").asText()).isEqualTo(StrategyPortfolioRiskV5.ownHash(with));
        String h = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        ArrayNode executionRows = MAPPER.createArrayNode(); executionRows.add(object().put("signal_id", "x").put("asset", "btc").put("symbol", "BTCUSDT").put("direction", "long").put("quantity", 1).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0 + HOUR).toString()).put("entry_price", 100).put("exit_price", 101));
        ObjectNode execution = StrategyPortfolioRiskV5.writeExecutionFillArtifact(dir.resolve("execution.json"), object().set("rows", executionRows));
        ObjectNode selected = StrategyPortfolioRiskV5.writeSelectedTradeArtifact(dir.resolve("selected.json"), object().put("lineageSha256", h).put("evaluationSha256", h).set("rows", MAPPER.createArrayNode()));
        ObjectNode evaluation = StrategyPortfolioRiskV5.writeEvaluationArtifact(dir.resolve("evaluation.json"), object().put("selectedTradesSha256", h).put("outerFoldSha256", h).put("lineageSha256", h));
        ObjectNode metadata = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("metadata.json"), object().put("kind", "FEE_SCHEDULE"));
        assertThat(execution.path("artifact").path("schema").asText()).isEqualTo("strategy-execution-fill-artifact/1");
        assertThat(selected.path("artifact").path("schema").asText()).isEqualTo("strategy-selected-trades/1");
        assertThat(evaluation.path("artifact").path("schema").asText()).isEqualTo("strategy-selected-evaluation/1");
        assertThat(metadata.path("artifact").path("schema").asText()).isEqualTo("strategy-v5-metadata-receipt/1");
    }

    @Test
    void stringWriterOverloadsAndHashBindingsAreCovered(@TempDir Path dir) {
        String marksPath = dir.resolve("marks.json").toString();
        ObjectNode marks = StrategyPortfolioRiskV5.writeMarkArtifact(marksPath, object().put("intervalMs", HOUR).set("rows", markRows()));
        assertThat(StrategyPortfolioRiskV5.hash("risk")).isEqualTo(StrategyPortfolioRiskV5.hash("risk"));
        assertThat(StrategyPortfolioRiskV5.hash("risk".getBytes(StandardCharsets.UTF_8))).isEqualTo(StrategyPortfolioRiskV5.hash("risk"));
        assertThat(StrategyPortfolioRiskV5.ownHash(marks.path("artifact"), "other_hash")).isEqualTo(StrategyPortfolioRiskV5.hash(marks.path("artifact")));
        ObjectNode custom = StrategyPortfolioRiskV5.withHash(object().put("x", 1), "custom_hash");
        assertThat(custom.path("custom_hash").asText()).isEqualTo(StrategyPortfolioRiskV5.ownHash(custom, "custom_hash"));
        String h = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        ObjectNode fill = object().put("signal_id", "x").put("asset", "btc").put("symbol", "BTCUSDT").put("direction", "long").put("quantity", 1).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0 + HOUR).toString()).put("entry_price", 100).put("exit_price", 101);
        ObjectNode execution = StrategyPortfolioRiskV5.writeExecutionFillArtifact(dir.resolve("execution.json").toString(), object().set("rows", MAPPER.createArrayNode().add(fill)));
        ObjectNode selectedFill = object().put("signal_id", "x").put("asset", "btc").put("venue", "binance").put("symbol", "BTCUSDT").put("instrument_type", "spot").put("direction", "long").put("quantity", 1).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0 + HOUR).toString()).put("stop_price", 90);
        ObjectNode selected = StrategyPortfolioRiskV5.writeSelectedTradeArtifact(dir.resolve("selected.json").toString(), object().put("lineageSha256", h).put("evaluationSha256", h).set("rows", MAPPER.createArrayNode().add(selectedFill)));
        ObjectNode evaluation = StrategyPortfolioRiskV5.writeEvaluationArtifact(dir.resolve("evaluation.json").toString(), object().put("selectedTradesSha256", h).put("outerFoldSha256", h).put("lineageSha256", h));
        ObjectNode metadata = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("metadata.json").toString(), object().put("kind", "FEE_SCHEDULE"));
        assertThat(execution.path("artifact").path("schema").asText()).isEqualTo("strategy-execution-fill-artifact/1");
        assertThat(selected.path("artifact").path("schema").asText()).isEqualTo("strategy-selected-trades/1");
        assertThat(evaluation.path("artifact").path("schema").asText()).isEqualTo("strategy-selected-evaluation/1");
        assertThat(metadata.path("artifact").path("schema").asText()).isEqualTo("strategy-v5-metadata-receipt/1");
    }

    @Test
    void markAndMetadataWritersMatchNodeArtifacts(@TempDir Path dir) throws Exception {
        ObjectNode markOptions = object().put("intervalMs", HOUR).set("rows", markRows());
        ObjectNode javaMark = StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("java-mark.json"), markOptions);
        assertThat(javaMark.path("artifact").path("content_sha256").asText())
                .isEqualTo(ORACLE.path("writers").path("mark_content_sha256").asText());
        ObjectNode metadataOptions = object().put("kind", "FEE_SCHEDULE").put("capturedAt", Instant.ofEpochMilli(T0).toString()).set("records", feeRows());
        ObjectNode javaMetadata = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("java-fees.json"), metadataOptions);
        assertThat(javaMetadata.path("artifact").path("content_sha256").asText())
                .isEqualTo(ORACLE.path("writers").path("fees_content_sha256").asText());
    }

    @Test
    void metadataModelConfigAliasesAndEvaluationLineageMatchNode(@TempDir Path dir) throws Exception {
        String modelHash = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
        String modelConfigPath = dir.resolve("model-config.json").toAbsolutePath().normalize().toString();
        ObjectNode metadataOptions = object().put("kind", "EXECUTION_MODEL").put("capturedAt", Instant.ofEpochMilli(T0).toString())
                .put("modelConfigSha256", modelHash).put("modelConfigPath", modelConfigPath);
        ObjectNode javaMetadata = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("java-model.json"), metadataOptions);
        ObjectNode normalizedMetadata = ((ObjectNode) javaMetadata.path("artifact")).deepCopy();
        normalizedMetadata.put("model_config_path", "$MODEL_CONFIG_PATH");
        assertThat(StrategyPortfolioRiskV5.ownHash(normalizedMetadata))
                .isEqualTo(ORACLE.path("writers").path("model_normalized_own_hash").asText());
        ObjectNode snakeOptions = metadataOptions.deepCopy().put("model_config_sha256", modelHash).put("model_config_path", modelConfigPath);
        assertThat(StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("snake-model.json"), snakeOptions).path("artifact").path("model_config_sha256").asText())
                .isEqualTo(modelHash);

        ObjectNode marks = StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("lineage-marks.json"), object().put("intervalMs", HOUR).set("rows", markRows()));
        ObjectNode fees = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("lineage-fees.json"), object().put("kind", "FEE_SCHEDULE").set("records", feeRows()));
        ArrayNode selectedRows = trades();
        String selectedRowsHash = StrategyPortfolioRiskV5.hash(selectedRows);
        String binding = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
        ObjectNode evaluation = StrategyPortfolioRiskV5.writeEvaluationArtifact(dir.resolve("lineage-evaluation.json"), object().put("selectedTradesSha256", selectedRowsHash).put("outerFoldSha256", binding).put("lineageSha256", binding));
        ObjectNode selected = StrategyPortfolioRiskV5.writeSelectedTradeArtifact(dir.resolve("lineage-selected.json"), object().put("lineageSha256", binding).put("evaluationSha256", evaluation.path("artifact").path("content_sha256").asText()).set("rows", selectedRows));
        ArrayNode executionRows = MAPPER.createArrayNode();
        executionRows.add(object().put("signal_id", "btc-1").put("asset", "btc").put("symbol", "BTCUSDT").put("direction", "long").put("quantity", 1).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0 + 31 * HOUR).toString()).put("entry_price", 100).put("exit_price", 340.25));
        executionRows.add(object().put("signal_id", "eth-1").put("asset", "eth").put("symbol", "ETHUSDT").put("direction", "long").put("quantity", .5).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0 + 31 * HOUR).toString()).put("entry_price", 200).put("exit_price", 680.5));
        ObjectNode execution = StrategyPortfolioRiskV5.writeExecutionFillArtifact(dir.resolve("lineage-execution.json"), object().set("rows", executionRows));
        ObjectNode request = request(marks, fees);
        request.set("selectedTradeArtifactPath", selected.path("path").isTextual() ? selected.path("path") : selected.path("artifact").path("path"));
        request.put("selectedTradeArtifactSha256", selected.path("sha256").asText());
        request.set("evaluationArtifactPath", evaluation.path("path").isTextual() ? evaluation.path("path") : evaluation.path("artifact").path("path"));
        request.put("evaluationArtifactSha256", evaluation.path("sha256").asText());
        request.set("executionArtifactPath", execution.path("path").isTextual() ? execution.path("path") : execution.path("artifact").path("path"));
        request.put("executionArtifactSha256", execution.path("sha256").asText());
        ObjectNode javaResult = StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(request);
        JsonNode nodeLineage = ORACLE.path("lineage");
        assertThat(javaResult.path("selected_trades_sha256").asText()).isEqualTo(selected.path("artifact").path("content_sha256").asText());
        assertThat(javaResult.path("evaluation_sha256").asText()).isEqualTo(evaluation.path("artifact").path("content_sha256").asText());
        assertThat(javaResult.path("execution_fills_sha256").asText()).isEqualTo(execution.path("sha256").asText());
        assertThat(javaResult.path("lineage").path("selected_trades_sha256").asText()).isEqualTo(nodeLineage.path("selected_trades_sha256").asText());
        assertThat(javaResult.path("lineage").path("evaluation_sha256").asText()).isEqualTo(nodeLineage.path("evaluation_sha256").asText());
        assertThat(javaResult.path("lineage").path("execution_fills_sha256").asText())
                .isEqualTo(execution.path("sha256").asText());
    }

    @Test
    void markReaderIsPITAndExclusive(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("marks.json"); ObjectNode artifact = StrategyPortfolioRiskV5.writeMarkArtifact(path, object().put("intervalMs", HOUR).set("rows", markRows()));
        ObjectNode read = StrategyPortfolioRiskV5.readBoundMarkArtifact(object().put("path", path.toString()).put("sha256", artifact.path("sha256").asText()).put("allowFixture", true).put("expectedIntervalMs", HOUR));
        assertThat(read.path("rows")).hasSize(128);
        assertThat(read.path("rows").get(0).path("event_time").asText()).isEqualTo(Instant.ofEpochMilli(T0).toString().replace("Z", ".000Z"));
        assertThat(read.path("rows").get(0).path("asset").asText()).isEqualTo("btc");
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.readBoundMarkArtifact(object().put("path", path.toString()).put("sha256", artifact.path("sha256").asText()).put("allowFixture", true).put("consumingCutoff", Instant.ofEpochMilli(T0 + 10 * HOUR - 1).toString())))
                .hasMessageContaining("as-of cutoff");
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.writeMarkArtifact(path, object().put("intervalMs", HOUR).set("rows", markRows())))
                .isInstanceOf(IllegalArgumentException.class);
        Files.writeString(path, Files.readString(path) + "\n");
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.readBoundMarkArtifact(object().put("path", path.toString()).put("sha256", artifact.path("sha256").asText()).put("allowFixture", true)))
                .hasMessageContaining("hash mismatch");
    }

    @Test
    void markReaderAndWriterFailClosedAtSecurityBoundaries(@TempDir Path dir) {
        Path path = dir.resolve("marks.json");
        ObjectNode artifact = StrategyPortfolioRiskV5.writeMarkArtifact(path, object().put("intervalMs", HOUR).set("rows", markRows()));
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.readBoundMarkArtifact(object().put("path", path.toString()).put("sha256", artifact.path("sha256").asText())))
                .hasMessageContaining("fixture mark artifact");
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.readBoundMarkArtifact(object().put("path", path.toString()).put("sha256", artifact.path("sha256").asText()).put("allowFixture", true).put("expectedIntervalMs", HOUR * 2)))
                .hasMessageContaining("cadence mismatch");
        assertThat(StrategyPortfolioRiskV5.readBoundMarkArtifact(object().put("path", path.toString()).put("sha256", artifact.path("sha256").asText()).put("allowFixture", "yes").set("schemas", MAPPER.createArrayNode().add("strategy-mark-artifact/1"))).path("rows")).hasSize(128);
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.readBoundMarkArtifact(object().put("path", path.toString()).put("sha256", artifact.path("sha256").asText()).put("allowFixture", true).set("schemas", MAPPER.createArrayNode().add("other-schema/1"))))
                .hasMessageContaining("unsupported physical artifact schema");
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("bad-asset.json"), object().set("rows", MAPPER.createArrayNode().add(mark("doge", "DOGEUSDT", "TRADE_MARK", T0, 1)))))
                .hasMessageContaining("outside the crypto universe");
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("bad-price.json"), object().set("rows", MAPPER.createArrayNode().add(mark("btc", "BTCUSDT", "TRADE_MARK", T0, 0)))))
                .hasMessageContaining("invalid price");
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("bad-series.json"), object().set("rows", MAPPER.createArrayNode().add(mark("btc", "BTCUSDT", "UNKNOWN", T0, 100)))))
                .hasMessageContaining("unsupported series_type");
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("bad-range.json"), object().set("rows", MAPPER.createArrayNode().add(mark("btc", "BTCUSDT", "TRADE_MARK", T0, 100).put("low", 90).put("high", 99)))))
                .hasMessageContaining("inconsistent intrabar range");
    }

    @Test
    void selectedAndEvaluationWritersRejectUnboundHashes(@TempDir Path dir) {
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.writeSelectedTradeArtifact(dir.resolve("selected.json"), object().put("lineageSha256", "bad").put("evaluationSha256", "bad")))
                .hasMessageContaining("selected-trade lineage");
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.writeEvaluationArtifact(dir.resolve("evaluation.json"), object().put("selectedTradesSha256", "bad").put("outerFoldSha256", "bad").put("lineageSha256", "bad")))
                .hasMessageContaining("selected trades");
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.writeExecutionFillArtifact(dir.resolve("invalid-execution.json"), object().set("rows", MAPPER.createArrayNode().add(object().put("signal_id", "x").put("asset", "btc").put("symbol", "BTCUSDT").put("direction", "long").put("quantity", 0).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0 + HOUR).toString()).put("entry_price", 100).put("exit_price", 101)))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.writeSelectedTradeArtifact(dir.resolve("invalid-selected.json"), object().put("lineageSha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").put("evaluationSha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").set("rows", MAPPER.createArrayNode().add(object().put("signal_id", "x")))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("invalid-metadata.json"), object().put("kind", "NOT_A_METADATA_KIND")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void directMarkBindingAndMissingPhysicalInputsFailClosed(@TempDir Path dir) {
        ObjectNode marks = StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("marks.json"), object().put("intervalMs", HOUR).set("rows", markRows()));
        ObjectNode fees = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("fees.json"), object().put("kind", "FEE_SCHEDULE").set("records", feeRows()));
        ObjectNode directRequest = request(marks, fees);
        directRequest.remove("markPath");
        directRequest.remove("markSha256");
        directRequest.set("markArtifact", object().put("path", marks.path("path").asText()).put("byte_sha256", marks.path("sha256").asText()));
        assertThat(StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(directRequest).path("accepted_trades")).hasSize(2);
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(object()))
                .hasMessageContaining("physical mark path and byte hash");
        ObjectNode lineage = object().putNull("source_manifest_sha256").putNull("source_receipt_sha256").putNull("command_receipt_sha256").putNull("source_code_sha256");
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("authoritative.json"), object().put("provenance", "AUTHORITATIVE_RECOMPUTED").put("lineageSha256", StrategyPortfolioRiskV5.hash(lineage)).set("rows", markRows())))
                .hasMessageContaining("physical binding is incomplete");
        Path nonObject = dir.resolve("array.json");
        try {
            byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8);
            Files.write(nonObject, bytes);
            assertThatThrownBy(() -> StrategyPortfolioRiskV5.readBoundMarkArtifact(object().put("path", nonObject.toString()).put("sha256", StrategyPortfolioRiskV5.hash(bytes)).put("allowFixture", true)))
                    .hasMessageContaining("is not JSON");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void boundMetadataWriterEmitsNonFixtureFundingCoverage(@TempDir Path dir) {
        ObjectNode coverage = object();
        coverage.put("complete", true).put("cadence_ms", 8 * HOUR);
        coverage.set("cadence_segments", MAPPER.createArrayNode());
        ObjectNode options = object().put("kind", "FUNDING_IDENTITY").put("fixtureOnly", false).put("status", "PUBLIC_OBSERVED");
        options.set("coverage", coverage);
        options.set("records", feeRows());
        ObjectNode artifact = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("bound-funding.json"), options);
        assertThat(artifact.path("artifact").path("authoritative").asBoolean()).isTrue();
        assertThat(artifact.path("artifact").path("provenance_mode").asText()).isEqualTo("BOUND_SOURCE");
        assertThat(artifact.path("artifact").path("coverage").path("complete").asBoolean()).isTrue();
        assertThat(artifact.path("artifact").path("limitations")).isEmpty();
        ObjectNode modeledOptions = object().put("kind", "FEE_SCHEDULE").put("fixtureOnly", false).put("status", "CONSERVATIVE_MODEL");
        modeledOptions.set("source", object().put("provider", "model-provider").put("kind", "modeled-fees"));
        ObjectNode modeled = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("modeled-fees.json"), modeledOptions);
        assertThat(modeled.path("artifact").path("provenance_mode").asText()).isEqualTo("MODEL_BOUND");
        assertThat(modeled.path("artifact").path("source").path("provider").asText()).isEqualTo("model-provider");
        ObjectNode defaultFunding = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("default-funding.json"), object().put("kind", "FUNDING_IDENTITY"));
        assertThat(defaultFunding.path("artifact").path("coverage").path("complete").asBoolean()).isFalse();
    }

    @Test
    void malformedTradesAndDuplicateIdsAreRejectedBeforeRiskAggregation(@TempDir Path dir) {
        ObjectNode marks = StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("marks.json"), object().put("intervalMs", HOUR).set("rows", markRows()));
        ObjectNode fees = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("fees.json"), object().put("kind", "FEE_SCHEDULE").set("records", feeRows()));
        ObjectNode lifecycle = request(marks, fees);
        lifecycle.set("trades", MAPPER.createArrayNode().add(object().put("signal_id", "bad-lifecycle").put("asset", "btc").put("venue", "binance").put("symbol", "BTCUSDT").put("instrument_type", "spot").put("direction", "long").put("quantity", 1).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0).toString()).put("stop_price", 90)));
        String lifecycleReasons = StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(lifecycle).path("rejected_trades").get(0).path("reasons").toString();
        assertThat(lifecycleReasons).contains("INVALID_LIFECYCLE");
        ObjectNode shape = request(marks, fees);
        shape.set("trades", MAPPER.createArrayNode().add(object().put("signal_id", "bad-shape").put("asset", "btc").put("venue", "binance").put("symbol", "BTCUSDT").put("instrument_type", "spot").put("direction", "sideways").put("quantity", 0).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0 + HOUR).toString()).put("stop_price", 90)));
        String shapeReasons = StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(shape).path("rejected_trades").get(0).path("reasons").toString();
        assertThat(shapeReasons).contains("INVALID_DIRECTION", "INVALID_QUANTITY");
        ObjectNode precomputed = request(marks, fees);
        ObjectNode forbidden = (ObjectNode) precomputed.path("trades").get(0).deepCopy();
        forbidden.set("risk", object().put("value", 1));
        precomputed.set("trades", MAPPER.createArrayNode().add(forbidden));
        assertThat(StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(precomputed).path("rejected_trades").get(0).path("reasons").toString()).contains("CALLER_PRECOMPUTED_RISK_REJECTED");
        ObjectNode duplicate = request(marks, fees);
        JsonNode firstTrade = duplicate.path("trades").get(0).deepCopy();
        duplicate.withArray("trades").add(firstTrade);
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(duplicate)).hasMessageContaining("duplicate trade id");
    }

    @Test
    void physicalExecutionFillsAreConsumedByRiskEvaluation(@TempDir Path dir) {
        ObjectNode marks = StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("marks.json"), object().put("intervalMs", HOUR).set("rows", markRows()));
        ObjectNode fees = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("fees.json"), object().put("kind", "FEE_SCHEDULE").set("records", feeRows()));
        ArrayNode fills = MAPPER.createArrayNode().add(object().put("signal_id", "btc-1").put("asset", "btc").put("symbol", "BTCUSDT").put("direction", "long").put("quantity", 1).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0 + 31 * HOUR).toString()).put("entry_price", 100).put("exit_price", 120));
        ObjectNode execution = StrategyPortfolioRiskV5.writeExecutionFillArtifact(dir.resolve("execution.json"), object().put("venue", "binance").set("rows", fills));
        ObjectNode request = request(marks, fees).put("executionArtifactPath", execution.path("path").asText()).put("executionArtifactSha256", execution.path("sha256").asText());
        ObjectNode result = StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(request);
        assertThat(result.path("accepted_trades")).hasSize(2);
        assertThat(result.path("accepted_trades").get(0).path("entry_fill_price").asDouble()).isEqualTo(100);
        assertThat(result.path("execution_fills_sha256").asText()).isEqualTo(execution.path("sha256").asText());
    }

    @Test
    void riskCapsAndEquityGatesAreEvaluatedAcrossTheTimeline(@TempDir Path dir) {
        ObjectNode marks = StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("marks.json"), object().put("intervalMs", HOUR).set("rows", markRows()));
        ObjectNode fees = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("fees.json"), object().put("kind", "FEE_SCHEDULE").set("records", feeRows()));
        ObjectNode capped = request(marks, fees);
        capped.with("policy").put("max_concurrent", 1).put("max_gross_exposure", 1).put("max_net_exposure", 1).put("max_reserved_fraction", .00001).put("max_collateral_fraction", .00001).put("max_maintenance_margin", .00001).put("max_asset_share", .1).put("max_hhi", .1).put("max_beta_gross", .1).put("max_beta_net", .1);
        ObjectNode cappedResult = StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(capped);
        String capFailures = cappedResult.path("portfolio_decision").path("failures").toString();
        assertThat(capFailures).contains("CONCURRENCY_CAP", "MAX_GROSS_EXPOSURE_EXCEEDED", "MAX_NET_EXPOSURE_EXCEEDED", "CURRENT_EQUITY_RISK_RESERVATION_EXCEEDED", "MAX_ASSET_SHARE_EXCEEDED", "MAX_HHI_EXCEEDED");
        ObjectNode equity = request(marks, fees);
        equity.with("policy").put("max_drawdown_amount", -1).put("max_drawdown_pct", 101).put("max_underwater_duration_ms", -1).put("equity_floor", 20_000).put("ruin_equity_floor", 30_000).put("minimum_current_equity", 20_000);
        ObjectNode equityResult = StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(equity);
        String equityFailures = equityResult.path("portfolio_decision").path("failures").toString();
        assertThat(equityFailures).contains("EQUITY_POLICY_LIMITS_INVALID", "EQUITY_FLOOR_BREACHED", "RUIN_EQUITY_THRESHOLD_BREACHED", "CURRENT_EQUITY_BELOW_FLOOR");
        assertThat(equityResult.path("event_risk_path").path("equity_diagnostics").path("minimum_equity").asDouble()).isLessThan(10_000).isGreaterThan(9_999);
        ObjectNode aliases = request(marks, fees);
        ObjectNode aliasPolicy = aliases.with("policy");
        aliasPolicy.remove(List.of("max_drawdown_pct", "max_underwater_duration_ms", "equity_floor", "ruin_equity_floor", "minimum_current_equity"));
        aliasPolicy.put("max_drawdown_fraction", .01).put("max_time_underwater_ms", 1).put("minimum_equity", 20_000).put("ruin_floor", 30_000).put("min_current_equity", 20_000);
        assertThat(StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(aliases).path("event_risk_path").path("policy_limits").path("max_drawdown_pct").asDouble()).isEqualTo(1.0);
    }

    @Test
    void markReaderRejectsDuplicateAndSparseSeries(@TempDir Path dir) {
        ArrayNode duplicateRows = markRows();
        duplicateRows.add(duplicateRows.get(0).deepCopy());
        ObjectNode duplicate = StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("duplicate.json"), object().put("intervalMs", HOUR).set("rows", duplicateRows));
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.readBoundMarkArtifact(object().put("path", duplicate.path("path").asText()).put("sha256", duplicate.path("sha256").asText()).put("allowFixture", true)))
                .hasMessageContaining("duplicate mark");
        ArrayNode sparseRows = markRows();
        sparseRows.remove(2);
        ObjectNode sparse = StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("sparse.json"), object().put("intervalMs", HOUR).set("rows", sparseRows));
        assertThatThrownBy(() -> StrategyPortfolioRiskV5.readBoundMarkArtifact(object().put("path", sparse.path("path").asText()).put("sha256", sparse.path("sha256").asText()).put("allowFixture", true)))
                .hasMessageContaining("not dense");
    }

    @Test
    void nonPerpetualFundingRecordsAreAClosedRiskVeto(@TempDir Path dir) {
        ObjectNode marks = StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("marks.json"), object().put("intervalMs", HOUR).set("rows", markRows()));
        ObjectNode metadata = object();
        metadata.set("fee", object().set("records", feeRows()));
        metadata.set("contract", object().set("records", MAPPER.createArrayNode().add(object().put("venue", "binance").put("symbol", "BTCUSDT").put("effective_from", Instant.ofEpochMilli(T0 - HOUR).toString()).put("effective_to", Instant.ofEpochMilli(T0 + 40 * HOUR).toString()).put("contract_multiplier", 1).put("margin_mode", "ISOLATED").put("collateral_asset", "usdt").put("leverage", 2))));
        metadata.set("margin", object().set("records", MAPPER.createArrayNode().add(object().put("venue", "binance").put("symbol", "BTCUSDT").put("effective_from", Instant.ofEpochMilli(T0 - HOUR).toString()).put("effective_to", Instant.ofEpochMilli(T0 + 40 * HOUR).toString()).put("maintenance_margin_ratio", .1).put("margin_mode", "ISOLATED").put("collateral_asset", "usdt").put("leverage", 2))));
        metadata.set("liquidation", object().set("records", MAPPER.createArrayNode().add(object().put("venue", "binance").put("symbol", "BTCUSDT").put("effective_from", Instant.ofEpochMilli(T0 - HOUR).toString()).put("effective_to", Instant.ofEpochMilli(T0 + 40 * HOUR).toString()).put("mark_series_type", "LIQUIDATION_MARK"))));
        metadata.set("execution_model", object().set("records", MAPPER.createArrayNode()));
        metadata.set("funding", object().set("records", MAPPER.createArrayNode().add(object().put("venue", "binance").put("symbol", "BTCUSDT").put("event_id", "funding-future-veto").put("settlement_time", Instant.ofEpochMilli(T0 + HOUR).toString()).put("rate", .0001))));
        ObjectNode req = request(marks, StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("unused-fees.json"), object().put("kind", "FEE_SCHEDULE")));
        req.set("metadata", metadata);
        req.set("trades", MAPPER.createArrayNode().add(object().put("signal_id", "btc-future").put("asset", "btc").put("venue", "binance").put("symbol", "BTCUSDT").put("instrument_type", "future").put("direction", "long").put("quantity", 1).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0 + 2 * HOUR).toString()).put("stop_price", 90).put("collateral_used", 500)));
        ObjectNode result = StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(req);
        assertThat(result.path("accepted_trades")).isEmpty();
        assertThat(result.path("rejected_trades").get(0).path("reasons").toString()).contains("FUNDING_FORBIDDEN_FOR_NONPERPETUAL");
    }

    @Test
    void markToMarketEquityTracksDrawdownAndRecovery(@TempDir Path dir) {
        ObjectNode marks = StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("sawtooth-marks.json"), object().put("intervalMs", HOUR).set("rows", sawtoothRows()));
        ObjectNode fees = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("fees.json"), object().put("kind", "FEE_SCHEDULE").set("records", feeRows()));
        ObjectNode result = StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(request(marks, fees));
        JsonNode diagnostics = result.path("event_risk_path").path("equity_diagnostics");
        assertThat(diagnostics.path("maximum_drawdown").asDouble()).isGreaterThan(0);
        assertThat(diagnostics.path("maximum_underwater_duration_ms").asLong()).isGreaterThan(0);
        assertThat(diagnostics.path("last_recovery_at").isTextual()).isTrue();
        assertThat(diagnostics.path("current_underwater").asBoolean()).isFalse();
        assertThat(result.path("event_risk_path").path("equity_curve")).anyMatch(row -> row.path("underwater").asBoolean());
    }

    @Test
    void derivativeWithoutBoundRiskMetadataFailsClosed(@TempDir Path dir) {
        ObjectNode marks = StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("marks.json"), object().put("intervalMs", HOUR).set("rows", markRows()));
        ObjectNode fees = StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("fees.json"), object().put("kind", "FEE_SCHEDULE").set("records", feeRows()));
        ObjectNode request = request(marks, fees);
        request.set("trades", MAPPER.createArrayNode().add(object().put("signal_id", "btc-perp").put("asset", "btc").put("venue", "binance").put("symbol", "BTCUSDT").put("instrument_type", "perpetual").put("direction", "long").put("quantity", 1).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0 + 31 * HOUR).toString()).put("stop_price", 90).put("collateral_used", 500)));
        ObjectNode result = StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(request);
        assertThat(result.path("accepted_trades")).isEmpty();
        assertThat(result.path("rejected_trades").get(0).path("reasons").toString()).contains("CONTRACT_SPEC", "EXECUTION_MODEL_METADATA_MISSING", "MARGIN", "LIQUIDATION");
    }

    @Test
    void derivativeFixturePathBindsFundingAndLiquidationLifecycle(@TempDir Path dir) {
        ArrayNode rows = markRows();
        for (int i = 0; i < 32; i++) {
            long time = T0 + i * HOUR;
            double price = 100 + i * i * .25;
            rows.add(mark("btc", "BTCUSDT", "LIQUIDATION_MARK", time, price).put("low", price - 2).put("high", price + 2));
        }
        ObjectNode marks = StrategyPortfolioRiskV5.writeMarkArtifact(dir.resolve("derivative-marks.json"), object().put("intervalMs", HOUR).set("rows", rows));
        String h = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        ObjectNode metadata = object();
        metadata.set("fee", object().set("records", feeRows()));
        metadata.set("contract", object().set("records", MAPPER.createArrayNode().add(object().put("venue", "binance").put("symbol", "BTCUSDT").put("effective_from", Instant.ofEpochMilli(T0 - HOUR).toString()).put("effective_to", Instant.ofEpochMilli(T0 + 32 * HOUR).toString()).put("contract_multiplier", 1).put("margin_mode", "ISOLATED").put("collateral_asset", "usdt").put("leverage", 2).put("funding_interval_ms", 8 * HOUR))));
        metadata.set("margin", object().set("records", MAPPER.createArrayNode().add(object().put("venue", "binance").put("symbol", "BTCUSDT").put("effective_from", Instant.ofEpochMilli(T0 - HOUR).toString()).put("effective_to", Instant.ofEpochMilli(T0 + 32 * HOUR).toString()).put("maintenance_margin_ratio", .1).put("margin_mode", "ISOLATED").put("collateral_asset", "usdt").put("leverage", 2))));
        metadata.set("liquidation", object().set("records", MAPPER.createArrayNode().add(object().put("venue", "binance").put("symbol", "BTCUSDT").put("effective_from", Instant.ofEpochMilli(T0 - HOUR).toString()).put("effective_to", Instant.ofEpochMilli(T0 + 32 * HOUR).toString()).put("mark_series_type", "LIQUIDATION_MARK"))));
        metadata.set("execution_model", object().set("records", MAPPER.createArrayNode()));
        ArrayNode fundingRows = MAPPER.createArrayNode(); ArrayNode slots = MAPPER.createArrayNode();
        slots.add(object().put("effective_from", Instant.ofEpochMilli(T0 - HOUR).toString()).put("effective_to", Instant.ofEpochMilli(T0 + 32 * HOUR).toString()).put("cadence_ms", 8 * HOUR).put("origin_at", Instant.ofEpochMilli(T0).toString()));
        for (int i = 1; i <= 3; i++) { long time = T0 + i * 8 * HOUR; JsonNode canonical = null; for (JsonNode candidate : marks.path("artifact").path("rows")) if ("TRADE_MARK".equals(candidate.path("series_type").asText()) && "btc".equals(candidate.path("asset").asText()) && candidate.path("availability_time").asText().equals(Instant.ofEpochMilli(time).toString().replace("Z", ".000Z"))) canonical = candidate; if (canonical == null) throw new IllegalStateException("funding mark missing"); fundingRows.add(object().put("venue", "binance").put("symbol", "BTCUSDT").put("event_id", "funding-" + i).put("settlement_time", Instant.ofEpochMilli(time).toString()).put("rate", .0001).put("source_receipt_sha256", h).put("source_byte_sha256", h).put("settlement_mark_price", canonical.path("price").asDouble()).put("settlement_mark_sha256", StrategyPortfolioRiskV5.hash(canonical))); }
        ObjectNode funding = object(); funding.set("records", fundingRows); ObjectNode coverage = object().put("complete", true).put("slot_tolerance_ms", 60_000); coverage.set("cadence_segments", slots); funding.set("coverage", coverage); metadata.set("funding", funding);
        ObjectNode req = request(marks, StrategyPortfolioRiskV5.writeMetadataArtifact(dir.resolve("unused-fees.json"), object().put("kind", "FEE_SCHEDULE")));
        req.set("metadata", metadata); req.set("requiredAssets", MAPPER.createArrayNode().add("btc"));
        ObjectNode derivativeTrade = object().put("signal_id", "btc-perp").put("asset", "btc").put("venue", "binance").put("symbol", "BTCUSDT").put("instrument_type", "perpetual").put("direction", "long").put("quantity", 1).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0 + 31 * HOUR).toString()).put("stop_price", 90).put("collateral_used", 500);
        ArrayNode suppliedFunding = MAPPER.createArrayNode();
        for (int i = 1; i <= 3; i++) suppliedFunding.add(object().put("event_id", "funding-" + i).put("amount", -(100 + (i * 8) * (i * 8) * .25) * .0001).put("source_receipt_sha256", h));
        derivativeTrade.set("funding_settlements", suppliedFunding);
        req.set("trades", MAPPER.createArrayNode().add(derivativeTrade));
        ObjectNode result = StrategyPortfolioRiskV5.evaluatePortfolioRiskV5(req);
        assertThat(result.path("accepted_trades")).hasSize(1);
        JsonNode accepted = result.path("accepted_trades").get(0);
        assertThat(accepted.path("funding_rows")).hasSize(3);
        assertThat(accepted.path("funding_rows").get(0).path("event_id").asText()).isEqualTo("funding-1");
        assertThat(accepted.path("funding_rows").get(0).path("mark_price").asDouble()).isBetween(115.9, 116.1);
        assertThat(accepted.path("funding_rows").get(0).path("amount").asDouble()).isBetween(-.0117, -.0115);
        assertThat(accepted.path("liquidation_path")).hasSize(32);
        assertThat(accepted.path("liquidation_path").get(0).path("equity").asDouble()).isBetween(497.8, 498.0);
        assertThat(accepted.path("liquidation_path").get(0).path("maintenance").asDouble()).isBetween(9.7, 9.9);
        assertThat(accepted.path("liquidation_path").get(0).path("margin_excess").asDouble()).isBetween(487.9, 488.2);
    }

    private static ObjectNode request(ObjectNode marks, ObjectNode fees) {
        ObjectNode request = object().put("markPath", marks.path("path").asText()).put("markSha256", marks.path("sha256").asText()).set("metadata", object().put("feeArtifactPath", fees.path("path").asText()).put("feeArtifactSha256", fees.path("sha256").asText()));
        request.set("requiredAssets", MAPPER.createArrayNode().add("btc").add("eth")); request.set("trades", trades()); request.set("policy", object().put("venue", "binance").put("interval_ms", HOUR).put("current_equity", 10_000).put("max_concurrent", 2).put("max_reserved_fraction", .01).put("allow_fixture_metadata", true).put("execution_fixture", true).put("asOf", Instant.ofEpochMilli(T0 + 40 * HOUR).toString())); return request;
    }

    private static ArrayNode sawtoothRows() {
        ArrayNode rows = MAPPER.createArrayNode();
        for (String asset : List.of("btc", "eth")) for (int i = 0; i < 32; i++) {
            long t = T0 + i * HOUR;
            double base = asset.equals("btc") ? 100 : 200;
            double price = i < 4 ? base - i : base + (i - 4) * 2;
            rows.add(mark(asset, asset.toUpperCase() + "USDT", "TRADE_MARK", t, price));
            rows.add(mark(asset, asset.toUpperCase() + "USDT", "RISK_REFERENCE", t, base + i * .5));
        }
        return rows;
    }

    private static ArrayNode markRows() {
        ArrayNode rows = MAPPER.createArrayNode();
        for (String asset : List.of("btc", "eth")) for (int i = 0; i < 32; i++) { long t = T0 + i * HOUR; double price = asset.equals("btc") ? 100 + i * i * .25 : 200 + i * i * .5; rows.add(mark(asset, asset.toUpperCase() + "USDT", "TRADE_MARK", t, price)); rows.add(mark(asset, asset.toUpperCase() + "USDT", "RISK_REFERENCE", t, asset.equals("btc") ? 100 + i * .5 : 200 + i)); } return rows;
    }

    private static ArrayNode feeRows() { ArrayNode rows = MAPPER.createArrayNode(); for (String symbol : List.of("BTCUSDT", "ETHUSDT")) rows.add(object().put("venue", "binance").put("symbol", symbol).put("effective_from", Instant.ofEpochMilli(T0 - HOUR).toString()).put("effective_to", Instant.ofEpochMilli(T0 + 40 * HOUR).toString()).put("taker_rate", .001).put("availability_time", Instant.ofEpochMilli(T0).toString())); return rows; }

    private static ObjectNode mark(String asset, String symbol, String type, long time, double price) { return object().put("asset", asset).put("symbol", symbol).put("series_type", type).put("event_time", Instant.ofEpochMilli(time).toString()).put("availability_time", Instant.ofEpochMilli(time).toString()).put("price", price); }
    private static ArrayNode trades() { ArrayNode rows = MAPPER.createArrayNode(); rows.add(object().put("signal_id", "btc-1").put("asset", "btc").put("venue", "binance").put("symbol", "BTCUSDT").put("instrument_type", "spot").put("direction", "long").put("quantity", 1).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0 + 31 * HOUR).toString()).put("stop_price", 90).put("notional", 100)); rows.add(object().put("signal_id", "eth-1").put("asset", "eth").put("venue", "binance").put("symbol", "ETHUSDT").put("instrument_type", "spot").put("direction", "long").put("quantity", .5).put("entry_time", Instant.ofEpochMilli(T0).toString()).put("exit_time", Instant.ofEpochMilli(T0 + 31 * HOUR).toString()).put("stop_price", 180).put("notional", 100)); return rows; }
    private static ObjectNode object() { return MAPPER.createObjectNode(); }

    private static ObjectNode loadOracle() {
        try (var input = StrategyPortfolioRiskV5NodeOracleTest.class.getResourceAsStream(
                "/oracles/strategy-portfolio-risk-v5.json")) {
            if (input == null) throw new IllegalStateException("frozen risk oracle is missing");
            return (ObjectNode) MAPPER.readTree(input);
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
