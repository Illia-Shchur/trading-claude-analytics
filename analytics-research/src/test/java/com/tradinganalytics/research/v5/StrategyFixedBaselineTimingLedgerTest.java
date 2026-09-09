package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Close-available exits must fund a same-instant next entry before admission. */
final class StrategyFixedBaselineTimingLedgerTest {
    @Test
    void closeAvailableExitPrecedesSameInstantNextEntry() throws Exception {
        ObjectNode policy = JsonHashes.mapper().createObjectNode().put("starting_cash_usdt", 1000);
        ObjectNode first = trade("first", "2026-01-01T00:00:00Z", "2026-01-01T00:01:00Z",
                "2026-01-01T00:02:00Z");
        ObjectNode second = trade("second", "2026-01-01T00:02:00Z", "2026-01-01T00:03:00Z",
                "2026-01-01T00:04:00Z");
        Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("reconcile", List.class, ObjectNode.class);
        method.setAccessible(true);
        ObjectNode result = (ObjectNode) method.invoke(null, List.of(first, second), policy);
        assertThat(result.path("ending_minus_starting_equals_net").asBoolean()).isTrue();
        assertThat(result.path("negative_cash").asBoolean()).isFalse();
    }

    private static ObjectNode trade(String id, String entry, String exit, String available) {
        ObjectNode trade = JsonHashes.mapper().createObjectNode().put("episode_id", id)
                .put("entry_price", 100).put("quantity", 10).put("gross_pnl_usdt", 0)
                .put("fees_usdt", 0).put("slippage_usdt", 0).put("capacity_debit_usdt", 0)
                .put("net_pnl_usdt", 0);
        ObjectNode lifecycle = trade.putObject("lifecycle").put("entry_time", entry);
        ArrayNode exits = lifecycle.putArray("exits");
        exits.addObject().put("time", exit).put("availability_time", available).put("price", 100)
                .put("fees_usd", 0).put("slippage_usd", 0);
        return trade;
    }
}
