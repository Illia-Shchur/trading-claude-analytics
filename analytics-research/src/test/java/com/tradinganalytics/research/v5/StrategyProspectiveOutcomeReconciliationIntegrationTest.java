package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies the typed numeric result is wired through the real completed-cycle custody path. */
class StrategyProspectiveOutcomeReconciliationIntegrationTest {
    @Test
    void completedCycleReopensAndBindsNumericReconciliationToOutcomeSources() throws Exception {
        Class<?> fixtureType = Class.forName("com.tradinganalytics.research.v5.StrategyProspectiveV5NodeOracleTest");
        Method factory = fixtureType.getDeclaredMethod("cycleFixture", boolean.class);
        factory.setAccessible(true);
        Object fixture = factory.invoke(null, true);
        ObjectNode options = (ObjectNode) accessor(fixture, "options");
        String lineage = (String) accessor(fixture, "lineage");
        String barId = "bar-1";
        ObjectNode input = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyProspectiveOutcomeReconciliationV1.INPUT_SCHEMA).put("version", 1)
                .put("account_currency", "USDT").put("instrument_type", "SPOT")
                .put("asset", "btc")
                .put("completed_bar_id", barId).put("decision_lineage_sha256", lineage)
                .put("maturity_as_of", "2026-01-03T00:00:00.000Z");
        input.putObject("source_bindings")
                .put("outcome_resolution_source_sha256", (String) accessor(fixture, "outcomeResolutionSourceSha"))
                .put("label_source_sha256", "pending")
                .put("execution_source_sha256", "pending");
        input.putArray("paper_trades").addObject().put("trade_id", "paper-1").put("direction", "long")
                .put("quantity", 1D).put("entry_price", 100D).put("exit_price", 110D)
                .put("entry_time", "2026-01-01T00:00:00Z").put("exit_time", "2026-01-02T00:00:00Z")
                .put("resolution_time", "2026-01-02T12:00:00Z").put("fee_rate", .001D)
                .put("slippage_rate", .0005D).put("capacity_debit_usdt", 0D);
        ObjectNode paper = (ObjectNode) input.path("paper_trades").get(0);
        Path root = (Path) accessor(fixture, "root");
        ObjectNode execution = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyProspectiveOutcomeReconciliationV1.EXECUTION_SOURCE_SCHEMA)
                .put("version", 1).put("asset", "btc").put("completed_bar_id", barId)
                .put("decision_lineage_sha256", lineage);
        execution.putArray("trades").add(paper.deepCopy());
        Path executionPath = root.resolve("typed-execution-source.json");
        execution.put("content_sha256", JsonHashes.ownHash(execution));
        Files.write(executionPath, NodePrettyJson.write(execution).getBytes(StandardCharsets.UTF_8));
        String executionSha = JsonHashes.sha256(Files.readAllBytes(executionPath));
        ObjectNode label = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyProspectiveOutcomeReconciliationV1.LABEL_SOURCE_SCHEMA)
                .put("version", 1).put("asset", "btc").put("completed_bar_id", barId)
                .put("decision_lineage_sha256", lineage);
        label.putArray("trades").addObject().put("trade_id", "paper-1")
                .put("resolution_time", "2026-01-02T12:00:00Z");
        Path labelPath = root.resolve("typed-label-source.json");
        label.put("content_sha256", JsonHashes.ownHash(label));
        Files.write(labelPath, NodePrettyJson.write(label).getBytes(StandardCharsets.UTF_8));
        String labelSha = JsonHashes.sha256(Files.readAllBytes(labelPath));
        ((ObjectNode) input.path("source_bindings")).put("label_source_sha256", labelSha)
                .put("execution_source_sha256", executionSha);
        ObjectNode resolution = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(
                (Path) accessor(fixture, "outcomeResolutionPath"))).deepCopy();
        resolution.put("label_source_sha256", labelSha).put("execution_source_sha256", executionSha)
                .put("content_sha256", JsonHashes.ownHash(resolution));
        Path resolutionPath = root.resolve("typed-outcome-resolution.json");
        Files.write(resolutionPath, NodePrettyJson.write(resolution).getBytes(StandardCharsets.UTF_8));
        String resolutionSha = JsonHashes.sha256(Files.readAllBytes(resolutionPath));
        options.put("labelSourcePath", labelPath.toString()).put("labelSourceSha256", labelSha)
                .put("executionSourcePath", executionPath.toString()).put("executionSourceSha256", executionSha)
                .put("outcomeResolutionPath", resolutionPath.toString()).put("outcomeResolutionSha256", resolutionSha);
        ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(input);
        Path numericPath = root.resolve("numeric-reconciliation.json");
        byte[] bytes = NodePrettyJson.write(result).getBytes(StandardCharsets.UTF_8);
        Files.write(numericPath, bytes);
        String numericSha = JsonHashes.sha256(bytes);
        Path inputPath = root.resolve("numeric-reconciliation-input.json");
        byte[] inputBytes = NodePrettyJson.write(input).getBytes(StandardCharsets.UTF_8);
        Files.write(inputPath, inputBytes);
        options.put("numericReconciliationPath", numericPath.toString()).put("numericReconciliationSha256", numericSha)
                .put("numericReconciliationInputPath", inputPath.toString())
                .put("numericReconciliationInputSha256", JsonHashes.sha256(inputBytes));

        ObjectNode cycle = StrategyProspectiveV5.appendCompletedBarCycle(options);
        assertThat(cycle.path("activated").asBoolean()).isFalse();
        assertThat(cycle.path("outcome").path("payload").path("numeric_reconciliation_sha256").asText())
                .isEqualTo(numericSha);
        assertThat(cycle.path("outcome").path("payload").path("numeric_reconciliation_input_sha256").asText())
                .isEqualTo(JsonHashes.sha256(inputBytes));
    }

    private static Object accessor(Object value, String name) throws Exception {
        Method method = value.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(value);
    }
}
