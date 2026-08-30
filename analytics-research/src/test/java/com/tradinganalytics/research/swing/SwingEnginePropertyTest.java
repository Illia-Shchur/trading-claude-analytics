package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

class SwingEnginePropertyTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Property(tries = 100)
    void allLegalFkStaticStopsRoundTrip(@ForAll @DoubleRange(min = 0.01, max = 15.0) double stop) {
        ObjectNode input = MAPPER.createObjectNode().put("framework", "fallen_knives").put("stop_pct", stop);
        ObjectNode normalized = SwingEngine.normalizeCandidate(input);
        assertThat(normalized.path("stop_pct").asDouble()).isEqualTo(stop);
        assertThat(normalized.path("stop_ceiling_pct").asDouble()).isEqualTo(15);
    }

    @Property(tries = 50)
    void stopsAboveHardCeilingAlwaysFail(@ForAll @DoubleRange(min = 15.01, max = 1000) double stop) {
        ObjectNode input = MAPPER.createObjectNode().put("framework", "fallen_knives").put("stop_pct", stop);
        assertThatThrownBy(() -> SwingEngine.normalizeCandidate(input)).hasMessageContaining("stop_pct must be >0 and <=15%");
    }

    @Property(tries = 100)
    void tradeMetricCountersAndRatesRemainCoherent(
            @ForAll List<@DoubleRange(min = -5, max = 5) Double> returns,
            @ForAll @IntRange(min = 1, max = 50) int candidateCount) {
        ArrayNode trades = MAPPER.createArrayNode(); long time = 1_000_000;
        for (double value : returns) {
            ObjectNode trade = MAPPER.createObjectNode().put("status", "COMPLETED").put("net_r", value).put("net_pnl", value * 100)
                    .put("entry_time", time).put("exit_time", time + SwingEngine.BAR_MS).put("hold_bars", 1).put("notional", 1000)
                    .put("fees", 1).put("slippage_debit", 1).put("funding_pnl", 0).put("mae_pct", -1).put("mfe_pct", 1).put("early_capture", false);
            trades.add(trade); time += SwingEngine.BAR_MS;
        }
        ObjectNode metrics = SwingEngine.tradeMetrics(trades, MAPPER.createObjectNode().put("candidateCount", candidateCount).put("bootstrapRounds", 20));
        assertThat(metrics.path("completed_trades").asInt()).isEqualTo(returns.size());
        assertThat(metrics.path("wins").asInt() + metrics.path("losses").asInt() + metrics.path("breakeven").asInt()).isEqualTo(returns.size());
        if (!returns.isEmpty()) assertThat(metrics.path("win_rate").asDouble()).isBetween(0d, 1d);
        assertThat(metrics.path("max_drawdown").asDouble()).isGreaterThanOrEqualTo(0);
    }
}
