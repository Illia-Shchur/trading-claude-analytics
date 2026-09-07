package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Independent physical tests: rehashing an input is not a substitute for source custody. */
final class StrategyProspectiveTypedBoundaryReviewTest {
    @Test
    void causalTypedFixtureAppendsAndRetainsInputCustody() throws Exception {
        Fixture f = fixture();
        ObjectNode cycle = StrategyProspectiveV5.appendCompletedBarCycle(f.options);
        assertThat(cycle.path("outcome").path("payload").path("numeric_reconciliation_input_sha256").asText())
                .isEqualTo(f.options.path("numericReconciliationInputSha256").asText());
    }

    @Test
    void rehashedResultCannotInventEconomicsAgainstUnchangedTypedInputs() throws Exception {
        Fixture f = fixture();
        ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(f.input);
        for (ObjectNode row : new ObjectNode[] {result, (ObjectNode) result.path("trades").get(0)}) {
            row.put("expected_gross_pnl_usdt", 999D).put("expected_fees_usdt", 0D)
                    .put("expected_slippage_usdt", 0D).put("expected_capacity_debit_usdt", 0D)
                    .put("expected_net_pnl_usdt", 999D);
        }
        result.put("content_sha256", JsonHashes.ownHash(result));
        f.options.put("numericReconciliationSha256", write(Path.of(f.options.path("numericReconciliationPath").asText()), result));
        reject(f);
    }

    @Test
    void rehashedInputCannotChangeExecutionPricesWithoutChangingTheSource() throws Exception {
        Fixture f = fixture();
        paper(f).put("exit_price", 999D);
        persist(f);
        reject(f);
    }

    @Test
    void observedFillClaimRequiresAnObservedSourceInsteadOfOnlyInputNumbers() throws Exception {
        Fixture f = fixture();
        f.input.put("observed_price_convention", "ACTUAL_FILL_PRICES");
        f.input.putArray("observed_fills").addObject().put("trade_id", "paper-1")
                .put("direction", "long").put("quantity", 1D).put("entry_price", 100D)
                .put("exit_price", 120D).put("fees_usdt", .22D).put("slippage_usdt", 0D)
                .put("capacity_debit_usdt", 0D).put("net_pnl_usdt", 19.78D);
        persist(f);
        reject(f);
    }

    @Test
    void sourcedActualFillPricesAppendWithoutDoubleChargingExecution() throws Exception {
        Fixture f = fixture();
        f.input.put("observed_price_convention", "ACTUAL_FILL_PRICES");
        f.input.putArray("observed_fills").addObject().put("trade_id", "paper-1")
                .put("direction", "long").put("quantity", 1D).put("entry_price", 101D)
                .put("exit_price", 109D).put("fees_usdt", .21D).put("slippage_usdt", 0D)
                .put("capacity_debit_usdt", 0D).put("net_pnl_usdt", 7.79D);
        f.execution.put("observed_price_convention", "ACTUAL_FILL_PRICES");
        f.execution.set("observed_fills", f.input.path("observed_fills").deepCopy());
        persist(f);
        ObjectNode cycle = StrategyProspectiveV5.appendCompletedBarCycle(f.options);
        assertThat(cycle.path("outcome").isObject()).isTrue();
        ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(f.input);
        assertThat(result.path("observed_fill_status").asText()).isEqualTo("AVAILABLE");
        assertThat(result.path("trades").get(0).path("observed_net_pnl_usdt").asDouble())
                .isCloseTo(7.79D, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void anotherAssetsTypedSourcesCannotResolveThisBarsSignal() throws Exception {
        Fixture f = fixture();
        f.input.put("asset", "eth");
        f.label.put("asset", "eth");
        f.execution.put("asset", "eth");
        persist(f);
        reject(f);
    }

    @Test
    void matchingRehashedSourcesCannotPutEntryBeforeTheFrozenDecision() throws Exception {
        Fixture f = fixture();
        paper(f).put("entry_time", "2025-12-31T23:00:00Z");
        ((ObjectNode) f.execution.path("trades").get(0)).put("entry_time", "2025-12-31T23:00:00Z");
        persist(f);
        reject(f);
    }

    @Test
    void numericResolutionCannotPostBeforeItsTradeHasResolved() throws Exception {
        Fixture f = fixture();
        String later = "2026-01-02T18:00:00Z";
        paper(f).put("resolution_time", later);
        ((ObjectNode) f.execution.path("trades").get(0)).put("resolution_time", later);
        ((ObjectNode) f.label.path("trades").get(0)).put("resolution_time", later);
        persist(f);
        reject(f);
    }

    private static ObjectNode paper(Fixture f) { return (ObjectNode) f.input.path("paper_trades").get(0); }
    private static void reject(Fixture f) {
        assertThatThrownBy(() -> StrategyProspectiveV5.appendCompletedBarCycle(f.options))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private record Fixture(ObjectNode options, ObjectNode input, ObjectNode execution,
            ObjectNode label, ObjectNode resolution, Path root) {}

    private static void persist(Fixture f) throws Exception {
        f.execution.put("content_sha256", JsonHashes.ownHash(f.execution));
        f.label.put("content_sha256", JsonHashes.ownHash(f.label));
        String e = write(Path.of(f.options.path("executionSourcePath").asText()), f.execution);
        String l = write(Path.of(f.options.path("labelSourcePath").asText()), f.label);
        f.options.put("executionSourceSha256", e).put("labelSourceSha256", l);
        ((ObjectNode) f.input.path("source_bindings")).put("execution_source_sha256", e).put("label_source_sha256", l);
        f.resolution.put("execution_source_sha256", e).put("label_source_sha256", l);
        f.resolution.put("content_sha256", JsonHashes.ownHash(f.resolution));
        f.options.put("outcomeResolutionSha256", write(Path.of(f.options.path("outcomeResolutionPath").asText()), f.resolution));
        f.options.put("numericReconciliationInputSha256", write(Path.of(f.options.path("numericReconciliationInputPath").asText()), f.input));
        f.options.put("numericReconciliationSha256", write(Path.of(f.options.path("numericReconciliationPath").asText()),
                StrategyProspectiveOutcomeReconciliationV1.reconcile(f.input)));
    }
    private static String write(Path path, ObjectNode value) throws Exception {
        byte[] bytes = NodePrettyJson.write(value).getBytes(StandardCharsets.UTF_8);
        Files.write(path, bytes); return JsonHashes.sha256(bytes);
    }

    private static Fixture fixture() throws Exception {
        Class<?> fixtureType = Class.forName("com.tradinganalytics.research.v5.StrategyProspectiveV5NodeOracleTest");
        Method factory = fixtureType.getDeclaredMethod("cycleFixture", boolean.class);
        factory.setAccessible(true);
        Object fixture = factory.invoke(null, true);
        ObjectNode options = (ObjectNode) accessor(fixture, "options");
        String lineage = (String) accessor(fixture, "lineage");
        String barId = "bar-1";
        ObjectNode input = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyProspectiveOutcomeReconciliationV1.INPUT_SCHEMA).put("version", 1)
                .put("account_currency", "USDT").put("instrument_type", "SPOT").put("asset", "btc")
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
                .put("version", 1).put("asset", "btc").put("completed_bar_id", barId).put("decision_lineage_sha256", lineage);
        execution.putArray("trades").add(paper.deepCopy());
        Path executionPath = root.resolve("typed-execution-source.json");
        execution.put("content_sha256", JsonHashes.ownHash(execution));
        Files.write(executionPath, NodePrettyJson.write(execution).getBytes(StandardCharsets.UTF_8));
        String executionSha = JsonHashes.sha256(Files.readAllBytes(executionPath));
        ObjectNode label = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyProspectiveOutcomeReconciliationV1.LABEL_SOURCE_SCHEMA)
                .put("version", 1).put("asset", "btc").put("completed_bar_id", barId).put("decision_lineage_sha256", lineage);
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

        return new Fixture(options, input, execution, label, resolution, root);
    }
    private static Object accessor(Object value, String name) throws Exception {
        Method method = value.getClass().getDeclaredMethod(name);
        method.setAccessible(true); return method.invoke(value);
    }
}
