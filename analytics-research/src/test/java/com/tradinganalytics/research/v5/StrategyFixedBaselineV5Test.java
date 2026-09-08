package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.LifecycleTrustService;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyFixedBaselineV5Test {
    @Test
    void detachedRoleReopensTheSameVerifiedValueAsAnEagerRole(@org.junit.jupiter.api.io.TempDir Path temporary)
            throws Exception {
        ArrayNode value = JsonHashes.mapper().createArrayNode();
        value.addObject().put("asset", "btc").put("close", 100D);
        byte[] bytes = JsonHashes.mapper().writeValueAsBytes(value);
        Path file = temporary.resolve("bars.json");
        Files.write(file, bytes);
        LifecycleTrustService.ReceiptReference receipt = new LifecycleTrustService.ReceiptReference(
                "bars.json", JsonHashes.ownHash(value), JsonHashes.sha256(bytes), (long) bytes.length,
                JsonHashes.canonicalSha256(value), null);

        StrategyFixedBaselineV5.Role eager = new StrategyFixedBaselineV5.Role(value, receipt);
        StrategyFixedBaselineV5.Role bounded = eager.detached(temporary, "bars:test");

        assertThat(bounded.value()).isNull();
        assertThat(bounded.open()).isEqualTo(eager.value());
    }

    @Test
    void detachedRoleRejectsTamperedAndMissingChildBarsBeforeLifecycleUse(@org.junit.jupiter.api.io.TempDir Path temporary)
            throws Exception {
        ArrayNode value = JsonHashes.mapper().createArrayNode();
        value.addObject().put("asset", "btc").put("close", 100D);
        byte[] bytes = JsonHashes.mapper().writeValueAsBytes(value);
        Path file = temporary.resolve("bars.json");
        Files.write(file, bytes);
        LifecycleTrustService.ReceiptReference receipt = new LifecycleTrustService.ReceiptReference(
                "bars.json", JsonHashes.ownHash(value), JsonHashes.sha256(bytes), (long) bytes.length,
                JsonHashes.canonicalSha256(value), null);
        StrategyFixedBaselineV5.Role bounded = new StrategyFixedBaselineV5.Role(value, receipt)
                .detached(temporary, "bars:test");

        ArrayNode tampered = JsonHashes.mapper().createArrayNode();
        tampered.addObject().put("asset", "btc").put("close", 101D);
        byte[] tamperedBytes = JsonHashes.mapper().writeValueAsBytes(tampered);
        assertThat(tamperedBytes).hasSize(bytes.length);
        Files.write(file, tamperedBytes);
        assertThatThrownBy(bounded::open)
                .hasMessage("bars bytes are missing or tampered");

        Files.delete(file);
        assertThatThrownBy(bounded::open)
                .hasMessage("lifecycle trust bars component bars.json is missing");
    }

    @Test
    void controlSelectionUsesAvailabilityAndLifecycleBoundariesWithoutOutcomeFields() throws Exception {
        ObjectNode event = row("btc", "2021-09-10T00:00:00Z", "2021-09-10T00:00:00Z");
        event.put("prior_30_bar_return", -0.1).put("prior_30_bar_realized_volatility", 0.2)
                .put("prior_30_bar_volume_zscore", 1.0);
        ArrayNode pool = JsonHashes.mapper().createArrayNode();
        ObjectNode unavailable = row("btc", "2021-08-20T00:00:00Z", "2021-09-10T00:01:00Z");
        addMatchFields(unavailable, -0.11);
        pool.add(unavailable);
        ObjectNode available = row("btc", "2021-08-20T00:00:00Z", "2021-08-20T00:00:00Z");
        addMatchFields(available, -0.11);
        pool.add(available);
        ObjectNode calipers = JsonHashes.mapper().createObjectNode()
                .put("minimum_prior_lag_hours", 240).put("maximum_prior_lookback_days", 30)
                .put("maximum_lifecycle_hours", 240).put("downside_return_min", -0.2)
                .put("downside_return_max", -0.01).put("prior_30_bar_return_abs", 1)
                .put("prior_30_bar_realized_volatility_abs", 1).put("prior_30_bar_volume_zscore_abs", 10);
        ObjectNode selected = StrategyResearchImprovementV1.selectOutcomeBlindControl(event, pool, calipers);
        assertThat(selected.path("candidate_count").asInt()).isEqualTo(1);
        assertThat(selected.path("control").path("episode_id").asText()).isEqualTo("btc:2021-08-20T00:00:00Z");
    }

    @Test
    void semanticHashIsStableAfterItsOwnFieldIsPersisted() throws Exception {
        ObjectNode value = JsonHashes.mapper().createObjectNode().put("schema", "test/1").put("n", 3);
        Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("semanticHash", ObjectNode.class);
        method.setAccessible(true);
        String first = (String) method.invoke(null, value);
        value.put("semantic_sha256", first);
        assertThat(method.invoke(null, value)).isEqualTo(first);
    }

    @Test
    void accountLedgerPostsRealizedPnlAtExitAndUsesPhysicalMarks() throws Exception {
        ObjectNode lifecycle = JsonHashes.mapper().createObjectNode().put("entry_time", "2021-09-10T00:00:00Z");
        lifecycle.putArray("exits").addObject().put("time", "2021-09-10T02:00:00Z").put("price", 90)
                .put("fees_usd", 1).put("slippage_usd", 0.5);
        ObjectNode trade = JsonHashes.mapper().createObjectNode().put("episode_id", "btc:e")
                .put("entry_price", 100).put("quantity", 1).put("gross_pnl_usdt", -10)
                .put("fees_usdt", 2).put("slippage_usdt", 1).put("capacity_debit_usdt", 0)
                .put("net_pnl_usdt", -13).set("lifecycle", lifecycle);
        ArrayNode marks = JsonHashes.mapper().createArrayNode();
        marks.addObject().put("time", "2021-09-10T01:00:00Z").put("price", 80);
        trade.set("portfolio_mark_points", marks);
        ObjectNode policy = JsonHashes.mapper().createObjectNode().put("starting_cash_usdt", 10000);
        Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("reconcile", List.class, ObjectNode.class);
        method.setAccessible(true);
        ObjectNode result = (ObjectNode) method.invoke(null, List.of(trade), policy);
        assertThat(result.path("starting_equity_usdt").asDouble()).isEqualTo(10000);
        assertThat(result.path("ending_equity_usdt").asDouble()).isEqualTo(9987);
        assertThat(result.path("realized_pnl_posted_at_exit").asBoolean()).isTrue();
        assertThat(result.path("mark_method").asText()).isEqualTo(
                "PHYSICAL_1M_CLOSES_AT_60M_SAMPLES;MAX_DRAWDOWN_IS_SAMPLED");
        assertThat(result.path("ending_minus_starting_equals_net").asBoolean()).isTrue();
        assertThat(result.path("equity_curve").get(1).path("event_type").asText()).isEqualTo("MARK");
        assertThat(result.path("equity_curve").get(1).path("realized_pnl_usdt").asDouble()).isZero();
    }

    @Test
    void mismatchedOutcomeMetadataIsRejectedBeforeLifecycle() throws Exception {
        Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("receiptMatchesAsset",
                com.fasterxml.jackson.databind.JsonNode.class, ObjectNode.class);
        method.setAccessible(true);
        ObjectNode row = row("btc", "2021-09-10T00:00:00Z", "2021-09-10T00:00:00Z");
        ObjectNode receipt = JsonHashes.mapper().createObjectNode().put("asset", "eth").put("symbol", "ETHUSDT");
        assertThat(method.invoke(null, receipt, row)).isEqualTo(false);
    }

    @Test
    void fixedEvaluatorRejectsRehashedUnsupportedFormulaInsteadOfChangingRules() throws Exception {
        ObjectNode baseline = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repoFile(
                "strategy-research/definitions/fk-deleveraging-absorption/v002.json")));
        ObjectNode controls = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repoFile(
                "strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json")));
        ObjectNode experiment = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repoFile(
                "strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json")));
        baseline.with("shock_rule").with("volume").put("lookback_completed_bars", 29);
        Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("validateSupportedFixedContract",
                ObjectNode.class, ObjectNode.class, ObjectNode.class);
        method.setAccessible(true);
        assertThatThrownBy(() -> method.invoke(null, baseline, controls, experiment))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refinementRouteRejectsARehashedUnsupportedMemberBeforeOpeningPhysicalInputs() throws Exception {
        ObjectNode plan = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repoFile(
                "strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/refinement-plan-v001.json")));
        ((ObjectNode) plan.path("members").get(0)).put("shock_threshold", -0.09);
        plan.put("content_sha256", JsonHashes.ownHash(plan));
        Path file = Files.createTempFile("invalid-refinement-", ".json");
        Files.writeString(file, JsonHashes.mapper().writeValueAsString(plan));
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("refinement", file.toString())
                .put("out_dir", Files.createTempDirectory("refinement-out-").toString());
        assertThatThrownBy(() -> StrategyFixedBaselineV5.runRefinement(options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported frozen dimension");
    }

    @Test
    void refinementMemberThresholdIsTheOnlyAllowedShockDimension() throws Exception {
        ObjectNode baseline = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repoFile(
                "strategy-research/definitions/fk-deleveraging-absorption/v002.json")));
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("signal_eligible", true)
                .put("setup_bar_count", 31).put("shock_return", -0.09)
                .put("volume_multiple", 2.0).put("realized_volatility", 0.02);
        Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("qualifies", ObjectNode.class,
                ObjectNode.class, double.class);
        method.setAccessible(true);
        assertThat(method.invoke(null, row, baseline, -0.08D)).isEqualTo(true);
        assertThat(method.invoke(null, row, baseline, -0.10D)).isEqualTo(false);
    }

    @Test
    void lifecycleClusteringUsesActualPairWindowsWithoutSpanningHistoricalGap() throws Exception {
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        ObjectNode pair = JsonHashes.mapper().createObjectNode().put("episode_id", "event::control");
        pair.putArray("window_intervals");
        pair.withArray("window_intervals").addObject().put("start_ms", 0).put("end_ms", 10);
        pair.withArray("window_intervals").addObject().put("start_ms", 100).put("end_ms", 110);
        rows.add(pair);
        rows.add(JsonHashes.mapper().createObjectNode().put("episode_id", "gap").put("start_ms", 20).put("end_ms", 30));
        rows.add(JsonHashes.mapper().createObjectNode().put("episode_id", "overlap").put("start_ms", 5).put("end_ms", 15));
        Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("collapseLifecycleEpisodes", List.class);
        method.setAccessible(true);
        ArrayNode clusters = (ArrayNode) method.invoke(null, List.of(pair, rows.get(1), rows.get(2)));
        assertThat(clusters).hasSize(2);
        assertThat(clusters.toString()).contains("event::control", "overlap", "gap");
    }

    @Test
    void bootstrapEffectiveCountUsesMergedClusterOnceWhenTradeRowIsDuplicated() throws Exception {
        ObjectNode baseline = JsonHashes.mapper().createObjectNode();
        baseline.putObject("execution").putObject("stop").put("distance", 0.06);
        ArrayNode clusters = JsonHashes.mapper().createArrayNode();
        clusters.addObject().putArray("source_episode_ids").add("e1");
        ObjectNode trade = JsonHashes.mapper().createObjectNode().put("episode_id", "e1")
                .put("entry_price", 100).put("quantity", 1).put("net_pnl_usdt", 5);
        ArrayNode once = JsonHashes.mapper().createArrayNode();
        once.addObject().put("event_id", "e1").put("status", "EVENT_COMPLETE_CONTROL_UNRESOLVED").set("event_trade", trade);
        ArrayNode twice = once.deepCopy();
        twice.add(once.get(0).deepCopy());
        Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("metrics", ArrayNode.class,
                ArrayNode.class, ObjectNode.class);
        method.setAccessible(true);
        ObjectNode oneResult = (ObjectNode) method.invoke(null, once, clusters, baseline);
        ObjectNode twoResult = (ObjectNode) method.invoke(null, twice, clusters, baseline);
        assertThat(twoResult.path("tested_cluster_count").asInt()).isEqualTo(1);
        assertThat(twoResult.path("falsifier").path("statistic_p_value").asDouble())
                .isEqualTo(oneResult.path("falsifier").path("statistic_p_value").asDouble());
        assertThat(twoResult.path("p20_expectancy_r").asDouble())
                .isEqualTo(oneResult.path("p20_expectancy_r").asDouble());
    }

    @Test
    void declaredClosureResumesAtRealReopeningBarAndUsesGapOpenStopFill() {
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("fixtureOnly", true).put("direction", "long").put("instrument_type", "SPOT")
                .put("instrument", "BINANCE_SPOT").put("venue", "BINANCE")
                .put("asset", "btc").put("symbol", "BTCUSDT")
                .put("decision_time", "2021-09-29T06:59:00.000Z");
        intent.putObject("contract").put("asset", "btc").put("symbol", "BTCUSDT")
                .put("venue", "BINANCE").put("instrument", "BINANCE_SPOT");
        intent.putObject("lifecycle").put("max_lifecycle_ms", 3 * 60 * 60 * 1000L)
                .putObject("stop").put("type", "PERCENT").put("value", 0.06);
        intent.with("lifecycle").putObject("sizing").put("mode", "FIXED_NOTIONAL").put("notional_usd", 1000);
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("interval_ms", 60_000);
        request.set("intent", intent);
        ArrayNode bars = request.putArray("bars");
        bars.add(bar("2021-09-29T06:59:00.000Z", 100, 100, 100, 100));
        bars.add(bar("2021-09-29T09:00:00.000Z", 90, 95, 89, 92));
        ArrayNode intervals = request.putObject("execution").putArray("allowed_non_trading_intervals");
        intervals.addObject().put("asset", "btc").put("symbol", "BTCUSDT").put("venue", "BINANCE")
                .put("instrument", "BINANCE_SPOT").put("start_ms", 1632898800000L)
                .put("end_ms", 1632906000000L).put("reason", "NON_TRADING");
        ObjectNode result = new TradeLifecycleV5().normalizeTradeLifecycleV5(request);
        assertThat(result.path("exits").path(0).path("reason").asText()).isEqualTo("STOP");
        assertThat(result.path("exits").path(0).path("fill_type").asText()).isEqualTo("GAP_OPEN");
        assertThat(result.path("exits").path(0).path("price").asDouble()).isEqualTo(90D);

        ObjectNode contradictory = request.deepCopy();
        contradictory.withArray("bars").add(bar("2021-09-29T08:00:00.000Z", 100, 100, 100, 100));
        assertThatThrownBy(() -> new TradeLifecycleV5().normalizeTradeLifecycleV5(contradictory))
                .hasMessageContaining("physical bars contradict");
    }

    @Test
    void undeclaredOrContradictoryClosureBarsRemainRejected() {
        ArrayNode intervals = JsonHashes.mapper().createArrayNode();
        intervals.addObject().put("asset", "btc").put("symbol", "BTCUSDT").put("venue", "BINANCE")
                .put("instrument", "BINANCE_SPOT").put("start_ms", 1632898800000L)
                .put("end_ms", 1632906000000L).put("reason", "NON_TRADING");
        assertThat(TradeLifecycleV5.coveredByNonTradingInterval(
                1632898800000L, 1632906000000L, intervals, 60_000L)).isTrue();
        assertThat(TradeLifecycleV5.coveredByNonTradingInterval(
                1632898860000L, 1632906000000L, intervals, 60_000L)).isFalse();
        ArrayNode wrongScope = intervals.deepCopy();
        ((ObjectNode) wrongScope.get(0)).put("venue", "OTHER");
        assertThat(TradeLifecycleV5.coveredByNonTradingInterval(
                1632898800000L, 1632906000000L, wrongScope, 60_000L)).isFalse();
    }

    private static ObjectNode bar(String time, double open, double high, double low, double close) {
        return JsonHashes.mapper().createObjectNode().put("event_time", time).put("open_time", time)
                .put("asset", "btc").put("symbol", "BTCUSDT").put("venue", "BINANCE")
                .put("instrument", "BINANCE_SPOT").put("open", open).put("high", high)
                .put("low", low).put("close", close).put("volume", 1);
    }

    private static ObjectNode row(String asset, String eventTime, String available) {
        return JsonHashes.mapper().createObjectNode().put("episode_id", asset + ":" + eventTime)
                .put("asset", asset).put("symbol", asset.toUpperCase() + "USDT")
                .put("event_time", eventTime).put("decision_time", eventTime).put("availability_time", available)
                .put("eligible", true).put("position_state", "FLAT").put("qualifying_shock", false)
                .put("hour_of_day", 0).put("day_of_week", 5);
    }

    private static Path repoFile(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("..", relative).normalize();
    }

    private static void addMatchFields(ObjectNode row, double completedReturn) {
        row.put("completed_return", completedReturn).put("prior_30_bar_return", -0.11)
                .put("prior_30_bar_realized_volatility", 0.2).put("prior_30_bar_volume_zscore", 1.1);
    }
}
