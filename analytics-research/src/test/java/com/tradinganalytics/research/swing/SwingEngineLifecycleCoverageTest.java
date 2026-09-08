package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Deterministic public-contract coverage for SwingEngine lifecycle decisions. */
class SwingEngineLifecycleCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long BAR = SwingEngine.BAR_MS;

    @Test
    void normalizeCandidateAcceptsDynamicStopsAliasesAndFactorContracts() {
        ObjectNode input = JSON.createObjectNode().put("id", "fr-b-boundary")
                .put("framework", "flying_rocket").put("channel", "B").put("phase", "2")
                .put("direction", "short").put("trigger_freshness_bars", 1).put("time_stop_bars", 7)
                .put("stop_atr_multiple", 1.5).put("stop_min_pct", 2).put("stop_max_pct", 8)
                .put("active_from", "2026-01-01").put("active_to", "2026-03-01")
                .put("target_r", 2).put("cap_pct", 12).put("partial_exit_pct", .4)
                .put("partial_target_r", .75).put("ratchet", "entry").put("funding_debit", false)
                .put("fee_pct_one_way", .12).put("slippage_pct_one_way", .03);
        input.set("excluded_score_legs", JSON.createArrayNode().add("macro"));
        input.set("min_state", JSON.createObjectNode().put("technical", 1));
        input.set("min_impulse", JSON.createObjectNode().put("flow", 1));
        input.set("factor_filters", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("path", "factors.macro.dxy").put("op", "gte").put("value", 100))
                .add(JSON.createObjectNode().put("path", "factors.sentiment.state").put("op", "neq").put("value", "euphoria"))
                .add(JSON.createObjectNode().put("path", "factors.regime").put("op", "in").set("value", JSON.createArrayNode().add("RANGE").add("TREND_DOWN")))
                .add(JSON.createObjectNode().put("path", "factors.volatility.atr").put("op", "between")
                        .set("value", JSON.createArrayNode().add(1).add(4))));

        ObjectNode candidate = SwingEngine.normalizeCandidate(input);

        assertThat(candidate.path("direction").asText()).isEqualTo("short");
        assertThat(candidate.path("threshold").asInt()).isEqualTo(17);
        assertThat(candidate.path("trigger_window_bars").asInt()).isOne();
        assertThat(candidate.path("max_hold_bars").asInt()).isEqualTo(7);
        assertThat(candidate.path("stop_atr_multiple").asDouble()).isEqualTo(1.5);
        assertThat(candidate.path("stop_pct").isNull()).isTrue();
        assertThat(candidate.path("stop_ceiling_pct").asInt()).isEqualTo(8);
        assertThat(candidate.path("ratchet_to_entry").asBoolean()).isTrue();
        assertThat(candidate.path("require_protective_controls").asBoolean()).isTrue();
        assertThat(candidate.path("funding_debit").asBoolean()).isFalse();
        assertThat(candidate.path("excluded_score_legs").get(0).asText()).isEqualTo("macro");
        assertThat(candidate.path("score_normalization").asText()).isEqualTo("included_max_to_20");
        assertThat(candidate.path("factor_filters")).hasSize(4);

        assertThatThrownBy(() -> SwingEngine.normalizeCandidate(JSON.createObjectNode()
                .put("framework", "fallen_knives").put("active_from", "2026-03-01").put("active_to", "2026-03-01")))
                .hasMessage("active_to must be later than active_from");
        assertThatThrownBy(() -> SwingEngine.normalizeCandidate(JSON.createObjectNode()
                .put("framework", "fallen_knives").put("stop_pct", 5).put("stop_atr_multiple", 1)))
                .hasMessage("declare either stop_pct or stop_atr_multiple, not both");
        assertThatThrownBy(() -> SwingEngine.normalizeCandidate(JSON.createObjectNode()
                .put("framework", "fallen_knives").put("excluded_score_legs", "unknown")))
                .hasMessage("excluded_score_legs contains an unsupported leg");
    }

    @Test
    void candidateMatchesFailsClosedAcrossTimeSeriesSetupAndShortControls() {
        ObjectNode fkInput = JSON.createObjectNode().put("id", "fk-match").put("framework", "fallen_knives")
                .put("phase", "1A").put("score_threshold", 8).put("min_flow_aligned", 2);
        fkInput.set("assets", JSON.createArrayNode().add("btc"));
        fkInput.set("timeframes", JSON.createArrayNode().add("4h"));
        fkInput.set("setup_families", JSON.createArrayNode().add("FK_SUPPORT_RECLAIM"));
        fkInput.set("factor_filters", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("path", "factors.macro.dxy").put("op", "gte").put("value", 100)));
        ObjectNode candidate = SwingEngine.normalizeCandidate(fkInput);
        ObjectNode row = matchRow("fallen_knives", null, 10, "FK_SUPPORT_RECLAIM");
        ((ObjectNode) row.path("factors").path("macro")).put("dxy", 101);

        assertThat(SwingEngine.candidateMatches(row, candidate)).isTrue();

        ObjectNode stale = row.deepCopy().put("timestamp_safe", false);
        assertThat(SwingEngine.candidateMatches(stale, candidate)).isFalse();
        ObjectNode unsafe = row.deepCopy().put("no_lookahead", false);
        unsafe.set("source_coverage", JSON.createObjectNode().put("point_in_time_safe", false));
        assertThat(SwingEngine.candidateMatches(unsafe, candidate)).isFalse();
        assertThat(SwingEngine.candidateMatches(row.deepCopy().put("asset", "eth"), candidate)).isFalse();
        assertThat(SwingEngine.candidateMatches(row.deepCopy().put("timeframe", "1h"), candidate)).isFalse();
        assertThat(SwingEngine.candidateMatches(row.deepCopy().put("mechanical_score", 7), candidate)).isFalse();
        assertThat(SwingEngine.candidateMatches(row.deepCopy().put("flow_aligned_rows", 1), candidate)).isFalse();
        assertThat(SwingEngine.candidateMatches(row.deepCopy().putNull("close"), candidate)).isFalse();
        assertThat(SwingEngine.candidateMatches(row.deepCopy().put("regime", "TREND_UP"), candidate)).isTrue();
        ObjectNode failedFactor = row.deepCopy(); ((ObjectNode) failedFactor.path("factors").path("macro")).put("dxy", 99);
        assertThat(SwingEngine.candidateMatches(failedFactor, candidate)).isFalse();

        assertThat(matchesFactor(fkInput, row, factorFilter("gt", JSON.getNodeFactory().numberNode(100)))).isTrue();
        assertThat(matchesFactor(fkInput, row, factorFilter("gte", JSON.getNodeFactory().numberNode(101.0)))).isTrue();
        assertThat(matchesFactor(fkInput, row, factorFilter("lt", JSON.getNodeFactory().numberNode(102)))).isTrue();
        assertThat(matchesFactor(fkInput, row, factorFilter("lte", JSON.getNodeFactory().numberNode(101)))).isTrue();
        assertThat(matchesFactor(fkInput, row, factorFilter("eq", JSON.getNodeFactory().numberNode(101.0)))).isTrue();
        assertThat(matchesFactor(fkInput, row, factorFilter("neq", JSON.getNodeFactory().numberNode(100.0)))).isTrue();
        assertThat(matchesFactor(fkInput, row, factorFilter("in", JSON.createArrayNode().add(100).add(101)))).isTrue();
        assertThat(matchesFactor(fkInput, row, factorFilter("between", JSON.createArrayNode().add(100).add(102)))).isTrue();
        assertThat(matchesFactor(fkInput, row, factorFilter("neq", JSON.getNodeFactory().numberNode(101.0)))).isFalse();
        assertThat(matchesFactor(fkInput, row, factorFilter("gte", JSON.getNodeFactory().numberNode(102)))).isFalse();
        assertThat(matchesFactor(fkInput, row, factorFilter("eq", JSON.getNodeFactory().numberNode(99)))).isFalse();
        assertThat(matchesFactor(fkInput, row, factorFilter("in", JSON.createArrayNode().add(99).add(100)))).isFalse();
        assertThat(matchesFactor(fkInput, row, factorFilter("between", JSON.createArrayNode().add(102).add(103)))).isFalse();
        assertThat(matchesFactor(fkInput, row, factorFilter("gte", JSON.getNodeFactory().numberNode(100)), "factors.macro.missing")).isFalse();

        ObjectNode frCandidate = SwingEngine.normalizeCandidate(JSON.createObjectNode().put("id", "fr-controls")
                .put("framework", "flying_rocket").put("channel", "A").put("phase", "1A").put("score_threshold", 11));
        ObjectNode frRow = matchRow("flying_rocket", "A", 12, "FR_A_DISTRIBUTION");
        frRow.remove("protective_controls");
        assertThat(SwingEngine.candidateMatches(frRow, frCandidate)).isFalse();
        frRow.set("protective_controls", controls());
        assertThat(SwingEngine.candidateMatches(frRow, frCandidate)).isTrue();
        ((ObjectNode) frRow.path("protective_controls")).put("carry_veto", true);
        assertThat(SwingEngine.candidateMatches(frRow, frCandidate)).isFalse();
    }

    @Test
    void simulateTradeCoversEntryRiskGatesAndLongExitLifecycle() {
        ObjectNode targetCandidate = longCandidate("fk-target").put("target_r", 1).put("max_hold_bars", 2);
        ArrayNode targetRows = tradeRows(100, 106, 99, 105);
        ObjectNode target = SwingEngine.simulateTrade(targetRows, 0, targetCandidate);
        assertThat(target.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(target.path("exit_type").asText()).isEqualTo("TARGET");
        assertThat(target.path("entry_price").asDouble()).isEqualTo(100);
        assertThat(target.path("target_price").asDouble()).isEqualTo(105);
        assertThat(target.path("net_r").asDouble()).isEqualTo(1);
        assertThat(target.path("risk_budget").path("phase_notional").asDouble()).isEqualTo(10_000);

        ObjectNode stop = SwingEngine.simulateTrade(tradeRows(100, 101, 94, 95), 0, targetCandidate);
        assertThat(stop.path("exit_type").asText()).isEqualTo("STOP");
        assertThat(stop.path("net_r").asDouble()).isEqualTo(-1);

        ArrayNode timeRows = JSON.createArrayNode().add(signal(100, 2)).add(bar(T + BAR, 100, 101, 99, 101))
                .add(bar(T + 2 * BAR, 101, 104, 100, 103));
        ObjectNode timeStop = SwingEngine.simulateTrade(timeRows, 0, longCandidate("fk-time").put("max_hold_bars", 2));
        assertThat(timeStop.path("exit_type").asText()).isEqualTo("TIME_STOP");
        assertThat(timeStop.path("hold_bars").asInt()).isEqualTo(2);

        ObjectNode noEquity = SwingEngine.simulateTrade(targetRows, 0, targetCandidate,
                JSON.createObjectNode().put("equity", 0));
        assertThat(noEquity.path("status").asText()).isEqualTo("RISK_BLOCKED");
        assertThat(noEquity.path("reason").asText()).isEqualTo("no_equity");

        ObjectNode missingStop = longCandidate("fk-missing-stop");
        missingStop.remove("stop_pct");
        ArrayNode noSignalStopRows = targetRows.deepCopy();
        ((ObjectNode) noSignalStopRows.get(0)).remove("stop_distance_pct");
        ObjectNode blocked = SwingEngine.simulateTrade(noSignalStopRows, 0, missingStop);
        assertThat(blocked.path("status").asText()).isEqualTo("RISK_BLOCKED");
        assertThat(blocked.path("reason").asText()).isEqualTo("missing_or_invalid_stop_or_ceiling");

        ArrayNode noFillRows = targetRows.deepCopy(); ((ObjectNode) noFillRows.get(1)).putNull("open");
        assertThat(SwingEngine.simulateTrade(noFillRows, 0, targetCandidate).path("status").asText()).isEqualTo("NO_FILL");
        assertThat(SwingEngine.simulateTrade(JSON.createArrayNode().add(signal(100, 2)), 0, targetCandidate)
                .path("status").asText()).isEqualTo("NO_NEXT_BAR");

        ArrayNode noExitRows = JSON.createArrayNode().add(signal(100, 2)).add(JSON.createObjectNode()
                .put("time", T + BAR).put("open", 100).putNull("high").putNull("low").putNull("close"));
        assertThat(SwingEngine.simulateTrade(noExitRows, 0, targetCandidate).path("status").asText()).isEqualTo("NO_EXIT");

        ArrayNode gapRows = JSON.createArrayNode().add(signal(100, 2)).add(bar(T + BAR, 100, 101, 99, 100))
                .add(bar(T + 3 * BAR, 100, 101, 99, 100));
        ObjectNode gap = SwingEngine.simulateTrade(gapRows, 0, longCandidate("fk-gap").put("max_hold_bars", 3));
        assertThat(gap.path("status").asText()).isEqualTo("DATA_GAP");
        assertThat(gap.path("opened").asBoolean()).isTrue();
    }

    @Test
    void partialTargetsRatchetAndSameBarCollisionFollowDeclaredLifecycle() {
        ObjectNode sameBarCandidate = longCandidate("fk-partial-same").put("target_r", 1.5)
                .put("partial_exit_pct", .5).put("partial_target_r", .5).put("ratchet_to_entry", true);
        ObjectNode sameBar = SwingEngine.simulateTrade(tradeRows(100, 110, 99, 108), 0, sameBarCandidate);
        assertThat(sameBar.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(sameBar.path("exit_type").asText()).isEqualTo("TARGET");
        assertThat(sameBar.path("partial_exit").asBoolean()).isTrue();
        assertThat(sameBar.path("partial_exit_pct").asDouble()).isEqualTo(.5);

        ArrayNode ratchetRows = JSON.createArrayNode().add(signal(100, 2)).add(bar(T + BAR, 100, 103, 99, 102))
                .add(bar(T + 2 * BAR, 102, 103, 99, 100));
        ObjectNode ratchet = SwingEngine.simulateTrade(ratchetRows, 0, longCandidate("fk-ratchet").put("target_r", 2)
                .put("max_hold_bars", 3).put("partial_exit_pct", .5).put("partial_target_r", .5).put("ratchet_to_entry", true));
        assertThat(ratchet.path("exit_type").asText()).isEqualTo("STOP");
        assertThat(ratchet.path("partial_exit").asBoolean()).isTrue();
        assertThat(ratchet.path("exit_price").asDouble()).isEqualTo(100);

        ObjectNode stopFirst = SwingEngine.simulateTrade(tradeRows(100, 110, 90, 105), 0,
                longCandidate("fk-stop-first").put("target_r", 1), JSON.createObjectNode().put("same_bar_collision", "stop-first"));
        ObjectNode targetFirst = SwingEngine.simulateTrade(tradeRows(100, 110, 90, 105), 0,
                longCandidate("fk-target-first").put("target_r", 1), JSON.createObjectNode().put("same_bar_collision", "target-first"));
        assertThat(stopFirst.path("exit_type").asText()).isEqualTo("STOP");
        assertThat(targetFirst.path("exit_type").asText()).isEqualTo("TARGET");
    }

    @Test
    void shortDynamicStopFundingAndInferredSettlementRemainAuditable() {
        ObjectNode shortCandidate = JSON.createObjectNode().put("id", "fr-funding").put("framework", "flying_rocket")
                .put("channel", "B").put("phase", "1A").put("direction", "short").put("stop_atr_multiple", 1.5)
                .put("stop_min_pct", 2).put("stop_max_pct", 6).put("target_r", 1).put("max_hold_bars", 4)
                .put("fee_pct", 0).put("slippage_pct", 0).put("funding_debit", true);
        ArrayNode rows = JSON.createArrayNode().add(signal(100, 2)).add(fundingBar(T + BAR, 100, 101, 99, 100, .001, "fund-1"))
                .add(fundingBar(T + 2 * BAR, 100, 101, 99, 100, .001, "fund-duplicate"))
                .add(fundingBar(T + 3 * BAR, 100, 101, 96, 98, .002, "fund-2"));
        ((ObjectNode) rows.get(2)).put("funding_event_time", T + BAR);
        ObjectNode trade = SwingEngine.simulateTrade(rows, 0, shortCandidate);
        assertThat(trade.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(trade.path("direction").asText()).isEqualTo("short");
        assertThat(trade.path("exit_type").asText()).isEqualTo("TARGET");
        assertThat(trade.path("stop_price").asDouble()).isEqualTo(103);
        assertThat(trade.path("target_price").asDouble()).isEqualTo(97);
        assertThat(trade.path("funding_settlements")).hasSize(2);
        assertThat(trade.path("funding_settlements").get(0).path("identity_status").asText()).isEqualTo("AUTHORITATIVE");
        assertThat(trade.path("funding_pnl").asDouble()).isGreaterThan(0);

        ArrayNode inferredRows = rows.deepCopy();
        for (int index : List.of(1, 2)) ((ObjectNode) inferredRows.get(index)).remove("funding_event_id");
        ObjectNode inferred = SwingEngine.simulateTrade(inferredRows, 0, shortCandidate);
        assertThat(inferred.path("funding_settlements").get(0).path("identity_status").asText()).isEqualTo("INFERRED");

        ObjectNode noFundingDebit = shortCandidate.deepCopy().put("funding_debit", false);
        ObjectNode disabled = SwingEngine.simulateTrade(rows, 0, noFundingDebit);
        assertThat(disabled.path("funding_settlements")).hasSize(2);
        assertThat(disabled.path("funding_pnl").asDouble()).isZero();
    }

    @Test
    void tradeMetricsAndStrategyEventsPreserveAccountingAndSingleEpisodePriority() {
        ArrayNode trades = JSON.createArrayNode()
                .add(trade("win", 1, 100, 2, 10, 1, .5, .01, .1, .2, .3, true))
                .add(trade("loss", -.5, -50, 3, 5, 1, .5, .02, -.2, .3, .4, false))
                .add(trade("flat", 0, 0, 4, 2, 1, .5, .03, 0, .1, .1, false))
                .add(trade("loss-2", -.25, -25, 5, 3, 1, .5, .04, -.1, .15, .25, false))
                .add(JSON.createObjectNode().put("status", "NO_FILL"))
                .add(JSON.createObjectNode().put("status", "COMPLETED").putNull("net_r"));
        ObjectNode metrics = SwingEngine.tradeMetrics(trades, JSON.createObjectNode().put("raw_setup_bars", 7)
                .put("unique_signals", 5).put("attempted_signals", 6).put("opened_trades", 4)
                .put("candidate_count", 3).put("bootstrap_rounds", 10).put("initial_equity", 1_000)
                .put("period_ms", 10 * BAR));
        assertThat(metrics.path("completed_trades").asInt()).isEqualTo(4);
        assertThat(metrics.path("wins").asInt()).isOne();
        assertThat(metrics.path("losses").asInt()).isEqualTo(2);
        assertThat(metrics.path("breakeven").asInt()).isOne();
        assertThat(metrics.path("profit_factor").asDouble()).isCloseTo(100d / 75, within(.000001));
        assertThat(metrics.path("sortino_r").isNull()).isFalse();
        assertThat(metrics.path("funding_debit").asDouble()).isCloseTo(.3, within(.000001));
        assertThat(metrics.path("funding_credit").asDouble()).isCloseTo(.1, within(.000001));
        assertThat(metrics.path("median_hold_bars").asDouble()).isEqualTo(5);
        assertThat(metrics.path("expectancy_bootstrap_20").isNull()).isFalse();

        ArrayNode rows = strategyRows();
        ObjectNode fk = longCandidate("component-fk").put("instrument_contract", "BTC-SPOT");
        ObjectNode fr = JSON.createObjectNode().put("id", "component-fr").put("framework", "flying_rocket")
                .put("channel", "A").put("phase", "1A").put("direction", "short").put("stop_pct", 5)
                .put("target_r", 1).put("max_hold_bars", 2).put("fee_pct", 0).put("slippage_pct", 0);
        ObjectNode strategy = SwingEngine.evaluateStrategy(rows, JSON.createArrayNode().add(fk).add(fr));
        assertThat(strategy.path("schema").asText()).isEqualTo("swing-strategy-evaluation/1");
        assertThat(strategy.path("completed_trades").asInt()).isOne();
        assertThat(strategy.path("blocked_attempts")).hasSize(1);
        assertThat(strategy.path("blocked_attempts").get(0).path("status").asText()).isEqualTo("OVERLAP_BLOCKED");
        assertThat(strategy.path("blocked_attempts").get(0).path("component_id").asText()).isEqualTo("component-fr");
        assertThat(SwingEngine.candidateSignalIntent(rows, fk)).hasSize(1);
        assertThat(SwingEngine.candidateSignalIntent(rows, fk).get(0).path("instrument").asText()).isEqualTo("BTC-SPOT");
    }

    private static ObjectNode longCandidate(String id) {
        return JSON.createObjectNode().put("id", id).put("framework", "fallen_knives").put("phase", "1A")
                .put("direction", "long").put("stop_pct", 5).put("target_r", 1).put("max_hold_bars", 2)
                .put("fee_pct", 0).put("slippage_pct", 0);
    }

    private static ObjectNode signal(double close, double atr) {
        return matchRow("fallen_knives", null, 10, "FK_SUPPORT_RECLAIM")
                .put("time", T).put("close", close).put("atr_20d", atr).put("stop_distance_pct", 5)
                .put("signal_id", "signal").put("setup_family_id", "setup");
    }

    private static ArrayNode tradeRows(double open, double high, double low, double close) {
        return JSON.createArrayNode().add(signal(100, 2)).add(bar(T + BAR, open, high, low, close));
    }

    private static ObjectNode bar(long time, double open, double high, double low, double close) {
        assertThat(low).isLessThanOrEqualTo(Math.min(open, close));
        assertThat(high).isGreaterThanOrEqualTo(Math.max(open, close));
        return JSON.createObjectNode().put("time", time).put("open", open).put("high", high).put("low", low).put("close", close)
                .put("volume", 100);
    }

    private static ObjectNode fundingBar(long time, double open, double high, double low, double close,
            double rate, String eventId) {
        return bar(time, open, high, low, close).put("funding_event_time", time).put("funding_rate", rate)
                .put("funding_event_id", eventId).put("funding_source", "venue-api").put("funding_venue", "binance")
                .put("funding_instrument", "BTCUSDT_PERP");
    }

    private static ObjectNode matchRow(String framework, String channel, double score, String family) {
        ObjectNode row = JSON.createObjectNode().put("asset", "btc").put("timeframe", "4h").put("framework", framework)
                .put("time", T).put("open", 100).put("high", 101).put("low", 99).put("close", 100)
                .put("mechanical_score", score).put("flow_aligned_rows", 2).put("flow_coverage", "COMPLETE")
                .put("setup_family", family).put("regime", "RANGE").put("timestamp_safe", true).put("no_lookahead", true)
                .put("completed_bar", true);
        if (channel == null) row.putNull("channel"); else row.put("channel", channel);
        row.set("setup_families", JSON.createArrayNode().add(family));
        row.set("trigger", JSON.createObjectNode().put("valid", true).put("completed_bar", true)
                .put("timeframe", "4h").put("age_bars", 0));
        row.set("protective_controls", controls());
        row.set("legs", JSON.createObjectNode().put("flow", 4).put("technical", 4).put("macro", 3)
                .put("sentiment", 3).put("valuation", 3).put("structure", 2));
        row.set("state_legs", JSON.createObjectNode().put("flow", 2).put("technical", 2));
        row.set("impulse_legs", JSON.createObjectNode().put("flow", 2).put("technical", 2));
        row.set("factors", JSON.createObjectNode().set("macro", JSON.createObjectNode().put("dxy", 100)));
        return row;
    }

    private static ObjectNode controls() {
        return JSON.createObjectNode().put("stop_valid", true).put("time_stop_valid", true)
                .put("ratchet_valid", true).put("carry_veto", false);
    }

    private static boolean matchesFactor(ObjectNode candidateInput, ObjectNode row, ObjectNode filter) {
        return matchesFactor(candidateInput, row, filter, filter.path("path").asText());
    }

    private static boolean matchesFactor(ObjectNode candidateInput, ObjectNode row, ObjectNode filter, String path) {
        ObjectNode adjusted = candidateInput.deepCopy();
        filter.put("path", path);
        adjusted.set("factor_filters", JSON.createArrayNode().add(filter));
        return SwingEngine.candidateMatches(row, SwingEngine.normalizeCandidate(adjusted));
    }

    private static ObjectNode factorFilter(String op, JsonNode value) {
        ObjectNode filter = JSON.createObjectNode().put("path", "factors.macro.dxy").put("op", op);
        filter.set("value", value);
        return filter;
    }

    private static ObjectNode trade(String id, double netR, double pnl, long exitTime, int hold, double notional,
            double fees, double slippage, double funding, double mae, double mfe, boolean early) {
        return JSON.createObjectNode().put("status", "COMPLETED").put("trade_id", id).put("net_r", netR)
                .put("net_pnl", pnl).put("entry_time", T).put("exit_time", T + exitTime * BAR).put("hold_bars", hold)
                .put("notional", notional).put("fees", fees).put("slippage_debit", slippage).put("funding_pnl", funding)
                .put("mae_pct", -mae).put("mfe_pct", mfe).put("early_capture", early);
    }

    private static ArrayNode strategyRows() {
        ObjectNode fkSignal = matchRow("fallen_knives", null, 10, "FK_SUPPORT_RECLAIM").put("time", T);
        ObjectNode fkEntry = bar(T + BAR, 100, 106, 99, 105).put("asset", "btc").put("timeframe", "4h")
                .put("framework", "fallen_knives").putNull("channel").put("mechanical_score", 0);
        ObjectNode frSignal = matchRow("flying_rocket", "A", 12, "FR_A_DISTRIBUTION").put("time", T);
        ObjectNode frEntry = bar(T + BAR, 100, 101, 94, 96).put("asset", "btc").put("timeframe", "4h")
                .put("framework", "flying_rocket").put("channel", "A").put("mechanical_score", 0);
        return JSON.createArrayNode().add(fkSignal).add(fkEntry).add(frSignal).add(frEntry);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
