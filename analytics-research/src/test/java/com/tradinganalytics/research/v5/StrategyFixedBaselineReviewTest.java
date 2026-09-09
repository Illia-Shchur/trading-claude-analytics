package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Independent reviewer tests: these fixtures contain no retained market outcomes. */
final class StrategyFixedBaselineReviewTest {
    private static final long STEP = 14_400_000L;
    private static final long START = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();

    @Test
    void frozenTrailingFormulaExcludesShockFromVolumeAndVolatility() {
        List<ObjectNode> bars = bars();
        bars.get(31).put("close", bars.get(30).path("close").asDouble() * 0.9)
                .put("volume", 300D);
        ObjectNode first = derive(bars).getFirst();
        assertThat(first.path("shock_return").asDouble()).isCloseTo(-0.1,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(first.path("volume_multiple").asDouble()).isEqualTo(3D);
        assertThat(first.path("realized_volatility").asDouble()).isCloseTo(0.001 * Math.sqrt(6),
                org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void futurePricesAndVolumesCannotAlterAnEarlierSetup() {
        List<ObjectNode> original = bars();
        ObjectNode earlier = derive(original).getFirst();
        List<ObjectNode> changed = original.stream().map(ObjectNode::deepCopy).toList();
        for (int i = 32; i < changed.size(); i++) {
            changed.get(i).put("open", 900D).put("high", 1100D).put("low", 800D)
                    .put("close", 1000D).put("volume", 1_000_000D);
        }
        assertThat(derive(changed).getFirst()).isEqualTo(earlier);
    }

    @Test
    void missingWarmupBarCannotBecomeAnApparentlyCompleteWindow() {
        List<ObjectNode> rows = bars();
        rows.remove(5);
        assertThatThrownBy(() -> derive(rows)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void duplicatedWarmupBarCannotInflateTheSample() {
        List<ObjectNode> rows = bars();
        rows.add(5, rows.get(5).deepCopy());
        assertThatThrownBy(() -> derive(rows)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aLatePriorDependencyCannotBeUsedAtDecisionTime() {
        List<ObjectNode> rows = bars();
        rows.get(5).put("availability_time", "2027-01-01T00:00:00Z");
        assertThatThrownBy(() -> derive(rows)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void oldestReturnDenominatorAndMatchingVolumeMustAlsoBeAvailable() {
        List<ObjectNode> rows = bars();
        rows.get(0).put("availability_time", "2027-01-01T00:00:00Z");
        assertThatThrownBy(() -> derive(rows)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aClaimedDecisionBeforeTheSourceBarClosesIsRejected() {
        List<ObjectNode> rows = bars();
        String early = Instant.ofEpochMilli(START + 31 * STEP).toString();
        rows.get(31).put("decision_time", early).put("availability_time", early);
        assertThatThrownBy(() -> derive(rows)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void positiveFinalEquityCannotConcealBorrowingAtAnEarlierEntry() {
        ObjectNode first = trade("btc:a", "2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z", 10, 110, 0, 0, 0);
        ObjectNode second = trade("eth:b", "2025-01-01T00:30:00Z", "2025-01-01T02:00:00Z", 10, 110, 0, 0, 0);
        assertThatThrownBy(() -> reconcile(List.of(first, second), 1000))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void capacityCostsReduceCashAsWellAsReportedProfit() {
        ObjectNode trade = trade("btc:a", "2025-01-01T00:00:00Z", "2025-01-01T02:00:00Z", 10, 110, 0, 0, 2);
        ObjectNode result = reconcile(List.of(trade), 10000);
        assertThat(result.path("ending_equity_usdt").asDouble()).isCloseTo(10098,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.path("ending_minus_starting_equals_net").asBoolean()).isTrue();
    }

    @Test
    void interimMarketLossAppearsInDrawdownBeforeRealization() {
        ObjectNode trade = trade("btc:a", "2025-01-01T00:00:00Z", "2025-01-01T02:00:00Z", 1, 90, 2, 1, 0);
        trade.putArray("portfolio_mark_points").addObject()
                .put("time", "2025-01-01T01:00:00Z").put("price", 80);
        ObjectNode result = reconcile(List.of(trade), 10000);
        assertThat(result.path("max_drawdown_usdt").asDouble()).isCloseTo(21.5,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.path("equity_curve").get(1).path("realized_pnl_usdt").asDouble()).isZero();
    }

    @Test
    void entryCostsCountTowardDrawdownEvenWhenTheTradeLaterWins() {
        ObjectNode trade = trade("btc:a", "2025-01-01T00:00:00Z", "2025-01-01T02:00:00Z", 1, 110, 2, 0, 0);
        ObjectNode result = reconcile(List.of(trade), 10000);
        assertThat(result.path("max_drawdown_usdt").asDouble()).isCloseTo(1,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.path("equity_curve").get(0).path("drawdown_usdt").asDouble()).isCloseTo(1,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void expectancyQuantileIsNotTheQuantileOfIndividualTrades() {
        double[] returnsR = new double[100];
        for (int i = 0; i < returnsR.length; i++) returnsR[i] = i < 20 ? -1 : 2;
        ObjectNode metrics = statistics(returnsR, 1);
        // Trade p20 is negative, but a mean of 100 independent observations
        // has a strongly positive lower expectancy estimate in this fixture.
        assertThat(metrics.path("p20_expectancy_r").asDouble()).isGreaterThan(0.5);
    }

    @Test
    void duplicatingTradesInsideExistingClustersDoesNotManufactureConfidence() {
        double[] returnsR = new double[40];
        for (int i = 0; i < returnsR.length; i++) returnsR[i] = i < 20 ? -1 : 1.2;
        ObjectNode once = statistics(returnsR, 1), twice = statistics(returnsR, 2);
        assertThat(once.path("p20_expectancy_r").isNumber()).isTrue();
        assertThat(once.path("falsifier").path("statistic_p_value").isNumber()).isTrue();
        assertThat(twice.path("falsifier").path("statistic_p_value").isNumber()).isTrue();
        assertThat(twice.path("independent_market_episode_count").asInt()).isEqualTo(40);
        assertThat(twice.path("p20_expectancy_r").asDouble()).isCloseTo(
                once.path("p20_expectancy_r").asDouble(), org.assertj.core.data.Offset.offset(1e-12));
        assertThat(twice.path("falsifier").path("statistic_p_value").asDouble()).isCloseTo(
                once.path("falsifier").path("statistic_p_value").asDouble(),
                org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void improvementOverWorseControlsCannotPassAMoneyLosingStrategy() {
        ArrayNode attempts = JsonHashes.mapper().createArrayNode(), clusters = JsonHashes.mapper().createArrayNode();
        for (int i = 0; i < 40; i++) {
            String eventId = "event" + i, controlId = "control" + i;
            ObjectNode event = JsonHashes.mapper().createObjectNode().put("episode_id", eventId)
                    .put("entry_price", 100).put("quantity", 1).put("net_pnl_usdt", -6);
            ObjectNode control = event.deepCopy().put("episode_id", controlId).put("net_pnl_usdt", -12);
            ObjectNode attempt = attempts.addObject().put("event_id", eventId).put("status", "COMPLETE")
                    .put("paired_net_pnl_usdt", 6);
            attempt.set("event_trade", event); attempt.set("control_trade", control);
            clusters.addObject().put("cluster_id", "c" + i).putArray("source_episode_ids")
                    .add(eventId + "::" + controlId);
        }
        ObjectNode result = metrics(attempts, clusters);
        assertThat(result.path("unconditional_event_mean_net_pnl_usdt").asDouble()).isEqualTo(-6);
        assertThat(result.path("falsifier").path("p20_pass").asBoolean()
                && result.path("falsifier").path("p_value_pass").asBoolean()).isFalse();
    }

    @Test
    void retainedLegacySetupAliasIsRecognizedAsExistingFamilyExposure() throws Exception {
        ObjectNode candidate = JsonHashes.mapper().createObjectNode();
        candidate.putObject("definition").putArray("setup_families").add("FK_DELEVERAGING_ABSORPTION");
        Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("containsFamilyIdentity", JsonNode.class, String.class);
        method.setAccessible(true);
        assertThat((boolean) method.invoke(null, candidate, "fk-deleveraging-absorption")).isTrue();
        assertThat((boolean) method.invoke(null, candidate, "unrelated-family")).isFalse();
    }

    private static ObjectNode statistics(double[] returnsR, int copiesPerCluster) {
        ArrayNode attempts = JsonHashes.mapper().createArrayNode();
        ArrayNode clusters = JsonHashes.mapper().createArrayNode();
        for (int i = 0; i < returnsR.length; i++) {
            ObjectNode cluster = clusters.addObject().put("cluster_id", "c" + i).put("episode_id", "c" + i);
            ArrayNode ids = cluster.putArray("source_episode_ids");
            for (int j = 0; j < copiesPerCluster; j++) {
                String id = "e" + i + ":" + j;
                ids.add(id);
                ObjectNode trade = JsonHashes.mapper().createObjectNode().put("episode_id", id)
                        .put("entry_price", 100).put("quantity", 1).put("net_pnl_usdt", returnsR[i] * 6);
                attempts.addObject().put("event_id", id).put("status", "EVENT_COMPLETE_CONTROL_UNRESOLVED")
                        .set("event_trade", trade);
            }
        }
        return metrics(attempts, clusters);
    }

    private static ObjectNode metrics(ArrayNode attempts, ArrayNode clusters) {
        ObjectNode baseline = JsonHashes.mapper().createObjectNode();
        baseline.putObject("execution").putObject("stop").put("distance", 0.06);
        baseline.putObject("market_episode_clustering").put("minimum_independent_episodes", 30);
        try {
            Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("metrics", ArrayNode.class, ArrayNode.class, ObjectNode.class);
            method.setAccessible(true);
            return (ObjectNode) method.invoke(null, attempts, clusters, baseline);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Update reviewer adapter after statistics extraction", error);
        }
    }

    private static ObjectNode trade(String id, String entry, String exit, double quantity,
            double exitPrice, double fees, double slippage, double capacity) {
        ObjectNode lifecycle = JsonHashes.mapper().createObjectNode().put("entry_time", entry);
        lifecycle.putObject("entry_costs").put("fees_usd", fees / 2)
                .put("slippage_usd", slippage / 2).put("capacity_debit_usd", capacity / 2);
        lifecycle.putArray("exits").addObject().put("time", exit).put("price", exitPrice)
                .put("quantity", quantity).put("fees_usd", fees / 2).put("slippage_usd", slippage / 2)
                .put("capacity_debit_usd", capacity / 2);
        double gross = quantity * (exitPrice - 100);
        return JsonHashes.mapper().createObjectNode().put("episode_id", id)
                .put("entry_price", 100).put("exit_price", exitPrice).put("quantity", quantity)
                .put("gross_pnl_usdt", gross).put("fees_usdt", fees).put("slippage_usdt", slippage)
                .put("capacity_debit_usdt", capacity).put("net_pnl_usdt", gross - fees - slippage - capacity)
                .set("lifecycle", lifecycle);
    }

    private static ObjectNode reconcile(List<ObjectNode> trades, double cash) {
        try {
            Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("reconcile", List.class, ObjectNode.class);
            method.setAccessible(true);
            return (ObjectNode) method.invoke(null, trades,
                    JsonHashes.mapper().createObjectNode().put("starting_cash_usdt", cash));
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Update reviewer adapter after portfolio extraction", error);
        }
    }

    private static List<ObjectNode> bars() {
        List<ObjectNode> rows = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            double price = 100 * Math.exp(0.001 * i);
            long open = START + i * STEP, end = open + STEP;
            rows.add(JsonHashes.mapper().createObjectNode().put("asset", "btc")
                    .put("symbol", "BTCUSDT").put("event_time", Instant.ofEpochMilli(open).toString())
                    .put("close_time", Instant.ofEpochMilli(end - 1).toString())
                    .put("availability_time", Instant.ofEpochMilli(end).toString())
                    .put("open", price).put("high", price * 1.02).put("low", price * 0.85)
                    .put("close", price).put("volume", 100D));
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static List<ObjectNode> derive(List<ObjectNode> bars) {
        try {
            Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("deriveFeatures", List.class, List.class);
            method.setAccessible(true);
            return (List<ObjectNode>) method.invoke(null, bars, List.of());
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Update reviewer adapter after feature-builder extraction", error);
        }
    }
}
