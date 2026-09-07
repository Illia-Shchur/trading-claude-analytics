package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Hash integrity over opaque source bytes cannot establish numeric provenance. */
final class StrategyProspectiveNumericCustodyReviewTest {
    @Test
    void selfConsistentRehashedNumbersCannotTurnOpaqueSourcesIntoVerifiedEconomics() throws Exception {
        Method factory = StrategyProspectiveV5NodeOracleTest.class.getDeclaredMethod("cycleFixture", boolean.class);
        factory.setAccessible(true); Object fixture = factory.invoke(null, true);
        ObjectNode options = (ObjectNode) accessor(fixture, "options");
        ObjectNode input = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyProspectiveOutcomeReconciliationV1.INPUT_SCHEMA).put("version", 1)
                .put("completed_bar_id", "bar-1").put("decision_lineage_sha256", (String) accessor(fixture, "lineage"))
                .put("maturity_as_of", "2026-01-03T00:00:00Z");
        input.putObject("source_bindings")
                .put("outcome_resolution_source_sha256", (String) accessor(fixture, "outcomeResolutionSourceSha"))
                .put("label_source_sha256", (String) accessor(fixture, "labelSourceSha"))
                .put("execution_source_sha256", (String) accessor(fixture, "executionSourceSha"));
        input.putArray("paper_trades").addObject().put("trade_id", "invented-claim").put("direction", "long")
                .put("quantity", 1D).put("entry_price", 100D).put("exit_price", 110D)
                .put("entry_time", "2026-01-01T00:00:00Z").put("exit_time", "2026-01-02T00:00:00Z")
                .put("resolution_time", "2026-01-02T12:00:00Z")
                .put("fee_rate", .001).put("slippage_rate", .0005).put("capacity_debit_usdt", 0D);
        ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(input);
        // These invented numbers are internally consistent, and every file/hash binding is valid.
        for (ObjectNode row : new ObjectNode[] {result, (ObjectNode) result.path("trades").get(0)}) {
            row.put("expected_gross_pnl_usdt", 999D).put("expected_fees_usdt", 0D)
                    .put("expected_slippage_usdt", 0D).put("expected_capacity_debit_usdt", 0D)
                    .put("expected_net_pnl_usdt", 999D);
        }
        result.remove("content_sha256"); result.put("content_sha256", JsonHashes.ownHash(result));
        Path path = ((Path) accessor(fixture, "root")).resolve("invented-numerics.json");
        byte[] bytes = NodePrettyJson.write(result).getBytes(StandardCharsets.UTF_8); Files.write(path, bytes);
        options.put("numericReconciliationPath", path.toString()).put("numericReconciliationSha256", JsonHashes.sha256(bytes));
        assertThatThrownBy(() -> StrategyProspectiveV5.appendCompletedBarCycle(options))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Object accessor(Object value, String name) throws Exception {
        Method method = value.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(value);
    }
}
