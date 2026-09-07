package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import org.junit.jupiter.api.Test;

/** The bar identifier is retained, while close-derived fills settle at close availability. */
final class TradeLifecycleTimingAvailabilityTest {
    private static final String POLICY = "FINAL_IN_HORIZON_BAR_CLOSE_V001";
    private static final String T0 = "2026-01-01T00:00:00Z";

    @Test
    void barrierFillCannotReleaseAClosedBarAtItsOpen() {
        ObjectNode result = run(false, true);
        ObjectNode exit = (ObjectNode) result.path("exits").get(0);
        assertThat(exit.path("fill_type").asText()).isEqualTo("BARRIER");
        assertThat(exit.path("time").asText()).isEqualTo("2026-01-01T00:01:00.000Z");
        assertThat(exit.path("availability_time").asText()).isEqualTo("2026-01-01T00:02:00.000Z");
    }

    @Test
    void gapOpenRemainsExecutableAtTheBarOpen() {
        ObjectNode result = run(true, true);
        ObjectNode exit = (ObjectNode) result.path("exits").get(0);
        assertThat(exit.path("fill_type").asText()).isEqualTo("GAP_OPEN");
        assertThat(exit.path("time").asText()).isEqualTo("2026-01-01T00:01:00.000Z");
        assertThat(exit.path("availability_time").asText()).isEqualTo("2026-01-01T00:01:00.000Z");
    }

    @Test
    void timeStopCloseUsesTheFinalBarsCloseAvailability() {
        ObjectNode result = run(false, false);
        ObjectNode exit = (ObjectNode) result.path("exits").get(0);
        assertThat(exit.path("fill_type").asText()).isEqualTo("TIME_STOP_CLOSE");
        assertThat(exit.path("time").asText()).isEqualTo("2026-01-01T00:01:00.000Z");
        assertThat(exit.path("availability_time").asText()).isEqualTo("2026-01-01T00:02:00.000Z");
    }

    private static ObjectNode run(boolean gap, boolean hitStop) {
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("interval_ms", 60_000);
        ObjectNode intent = request.putObject("intent").put("fixtureOnly", true)
                .put("direction", "long").put("instrument_type", "SPOT")
                .put("decision_time", T0);
        ObjectNode lifecycle = intent.putObject("lifecycle")
                .put("max_lifecycle_ms", 120_000).put("gap_policy", "OPEN")
                .put("fill_availability_policy", POLICY);
        lifecycle.putObject("stop").put("type", "PERCENT").put("value", .06);
        lifecycle.putObject("sizing").put("mode", "FIXED_NOTIONAL").put("notional_usd", 1000);
        ArrayNode bars = request.putArray("bars");
        bars.addObject().put("event_time", T0).put("close_time", "2026-01-01T00:01:00Z")
                .put("open", 100).put("high", 100).put("low", 100).put("close", 100);
        ObjectNode second = bars.addObject().put("event_time", "2026-01-01T00:01:00Z")
                .put("close_time", "2026-01-01T00:02:00Z").put("open", gap ? 90 : 100)
                .put("high", 100).put("low", hitStop ? 90 : 95).put("close", 95);
        if (gap) second.put("availability_time", "2026-01-01T00:01:00Z");
        else if (hitStop) second.put("availability_time", "2026-01-01T00:01:00Z");
        request.set("intent", intent);
        return new TradeLifecycleV5().normalizeTradeLifecycleV5(request);
    }
}
