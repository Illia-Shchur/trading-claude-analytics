package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent checks that generated OHLC actually realizes the frozen setup contract. */
final class StrategyOperatingCharacteristicsReviewTest {
    private static final long DECISION = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();
    @TempDir Path temporary;

    @Test
    void rehashingADifferentMarketProcessCannotKeepTheFrozenV004Claim() throws Exception {
        Path path = Path.of("strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-plan-v004.json");
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        ObjectNode plan = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
        assertThat(StrategyOperatingCharacteristicsV4.preflight(plan).path("status").asText()).isEqualTo("READY_PRE_OUTCOME");
        ((ObjectNode) plan.path("generator").path("common_market_shocks")).put("factor_weight", .99);
        plan.remove("content_sha256"); plan.put("content_sha256", JsonHashes.ownHash(plan));
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsV4.preflight(plan))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRehashedUnsupportedHorizonCannotBeAcceptedAndThenSilentlyIgnored() throws Exception {
        Path path = Path.of("strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-plan-v004.json");
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        ObjectNode plan = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
        ((ObjectNode) plan.path("generator")).put("holding_horizon_minutes", 60);
        plan.put("content_sha256", JsonHashes.ownHash(plan));
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsV4.preflight(plan))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theGeneratedSetupIsCompletedAtTheDeclaredExecutionBoundary() {
        ArrayNode bars = setup(true);
        ObjectNode feature = feature(bars);
        assertThat(Instant.parse(feature.path("decision_time").asText()).toEpochMilli()).isEqualTo(DECISION);
        JsonNode last = bars.get(bars.size() - 1);
        assertThat(Instant.parse(last.path("close_time").asText()).toEpochMilli()).isEqualTo(DECISION);
        assertThat(Instant.parse(last.path("availability_time").asText()).toEpochMilli()).isLessThanOrEqualTo(DECISION);
    }

    @Test
    void frozenSetupValuesAreDerivedFromBarsRatherThanInsertedAsFeatures() {
        ObjectNode event = feature(setup(true)), control = feature(setup(false));
        assertThat(event.path("shock_return").asDouble()).isCloseTo(-.09, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(control.path("shock_return").asDouble()).isCloseTo(-.04, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(event.path("volume_multiple").asDouble()).isCloseTo(2.4, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(control.path("volume_multiple").asDouble()).isCloseTo(1.2, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(event.path("realized_volatility").asDouble()).isCloseTo(.02, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(control.path("realized_volatility").asDouble()).isCloseTo(.02, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void treatedAndControlSetupsHaveTheSamePriorMatchingState() {
        ObjectNode event = feature(setup(true)), control = feature(setup(false));
        for (String field : List.of("prior_30_bar_return", "prior_30_bar_realized_volatility",
                "prior_30_bar_volume_zscore", "hour_of_day", "day_of_week")) {
            assertThat(event.get(field)).as(field).isEqualTo(control.get(field));
        }
    }

    @Test
    void wilsonBoundsMatchIndependentBinomialAnchors() throws Exception {
        Method method = StrategyOperatingCharacteristicsV4.class.getDeclaredMethod("wilson", int.class, int.class);
        method.setAccessible(true);
        double[][] anchors = {
                {0, 30, 0, .1135133931739688},
                {0, 50, 0, .07134759913335872},
                {1, 50, .00353925927164623, .1049544358963782},
                {40, 50, .6696289406777459, .8875624998422389},
                {45, 50, .7863976856252035, .9565242350681095},
                {50, 50, .9286524008666412, 1}
        };
        for (double[] anchor : anchors) {
            double[] actual = (double[]) method.invoke(null, (int) anchor[0], (int) anchor[1]);
            assertThat(actual[0]).isCloseTo(anchor[2], org.assertj.core.data.Offset.offset(1e-12));
            assertThat(actual[1]).isCloseTo(anchor[3], org.assertj.core.data.Offset.offset(1e-12));
        }
    }

    @Test
    void incompleteReplicationsAreNotReportedAsObservedStatisticalFailures() throws Exception {
        Class<?> cellType = Class.forName(StrategyOperatingCharacteristicsV4.class.getName() + "$Cell");
        var constructor = cellType.getDeclaredConstructor(String.class, double.class); constructor.setAccessible(true);
        Object cell = constructor.newInstance("NO_EDGE", 0D);
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        for (int index = 0; index < 50; index++) {
            rows.addObject().put("cell", "NO_EDGE").put("effect_size", 0D)
                    .put("status", "COMPUTE_INCOMPLETE").put("decision", false);
        }
        Method method = StrategyOperatingCharacteristicsV4.class.getDeclaredMethod("summarize", ArrayNode.class, List.class);
        method.setAccessible(true);
        ObjectNode summary = (ObjectNode) method.invoke(null, rows, List.of(cell));
        JsonNode result = summary.path("NO_EDGE:0.0");
        assertThat(result.path("incomplete").asInt()).isEqualTo(50);
        assertThat(result.path("measured").asBoolean()).isFalse();
        assertThat(result.path("target_met").asBoolean()).isFalse();
        assertThat(result.path("success_rate").isNull()).isTrue();
        assertThat(result.path("wilson95_upper").isNull()).isTrue();
        assertThat(result.path("wilson95_lower").isNull()).isTrue();
    }

    @Test
    void theFullAndPrefixSchedulesHaveTheirDeclaredDependentSampleGeometry() throws Exception {
        Method method = StrategyOperatingCharacteristicsV4.class.getDeclaredMethod("buildEpisodes", int.class, long.class, int.class);
        method.setAccessible(true);
        for (int count : new int[] {50, 10}) {
            List<?> episodes = (List<?>) method.invoke(null, count, 2026090601L, 0);
            assertThat(episodes).hasSize(count);
            Map<Long, Set<String>> clusters = new HashMap<>(); Set<String> assets = new HashSet<>();
            for (Object episode : episodes) {
                long event = (long) accessor(episode, "eventDecision");
                long control = (long) accessor(episode, "controlDecision");
                String asset = (String) accessor(episode, "asset");
                assertThat(event - control).isEqualTo(21L * 86_400_000L);
                assertThat(event % (4L * 3_600_000L)).isZero();
                assertThat(clusters.computeIfAbsent(event, ignored -> new HashSet<>()).add(asset)).isTrue();
                assets.add(asset);
            }
            assertThat(clusters).hasSize(count == 50 ? 32 : 8);
            assertThat(clusters.values().stream().filter(group -> group.size() == 2).count()).isEqualTo(count == 50 ? 18 : 2);
            for (Set<String> group : clusters.values()) if (group.size() == 2) assertThat(group).containsExactlyInAnyOrder("btc", "eth");
            assertThat(assets).containsExactlyInAnyOrder("btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
            List<Long> times = clusters.keySet().stream().sorted().toList();
            for (int index = 1; index < times.size(); index++) {
                assertThat(times.get(index) - times.get(index - 1)).isGreaterThanOrEqualTo(42L * 86_400_000L);
            }
        }
    }

    @Test
    void plantedDriftChangesTheGeneratedFuturePathAtTheFrozenUnitsOnly() throws Exception {
        Class<?> pathType = Class.forName(StrategyOperatingCharacteristicsV4.class.getName() + "$CommonPath");
        Method common = StrategyOperatingCharacteristicsV4.class.getDeclaredMethod("commonPath", SplittableRandom.class, int.class);
        common.setAccessible(true);
        Object path = common.invoke(null, new SplittableRandom(97L), 14_400);
        Method generate = StrategyOperatingCharacteristicsV4.class.getDeclaredMethod("addOutcomeRoles", Path.class,
                Map.class, List.class, List.class, String.class, String.class, String.class, long.class,
                double.class, boolean.class, double.class, pathType, SplittableRandom.class, long.class, long.class);
        generate.setAccessible(true);
        List<JsonNode> generated = new ArrayList<>();
        for (double effect : new double[] {0, .02}) {
            Path root = Files.createDirectory(temporary.resolve(effect == 0 ? "null" : "planted"));
            Map<String, StrategyFixedBaselineV5.Role> roles = new HashMap<>();
            generate.invoke(null, root, roles, new ArrayList<>(), new ArrayList<>(), "btc", "BTCUSDT",
                    "review", DECISION, 30_000D, true, effect, path, new SplittableRandom(101L), Long.MAX_VALUE, Long.MAX_VALUE);
            generated.add(roles.get("bars:review").value());
        }
        JsonNode noEdge = generated.get(0), planted = generated.get(1);
        assertThat(noEdge.size()).isEqualTo(14_400); assertThat(planted.size()).isEqualTo(14_400);
        assertThat(planted.get(0).path("open").asDouble()).isEqualTo(noEdge.get(0).path("open").asDouble());
        for (int index = 0; index < 14_400; index++) {
            double ratio = planted.get(index).path("close").asDouble() / noEdge.get(index).path("close").asDouble();
            assertThat(ratio).isCloseTo(Math.exp(.02D * (index + 1D) / 14_400D), org.assertj.core.data.Offset.offset(1e-10));
            assertThat(planted.get(index).get("volume")).isEqualTo(noEdge.get(index).get("volume"));
        }
    }

    private static Object accessor(Object value, String name) throws Exception {
        Method method = value.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(value);
    }

    private static ObjectNode feature(ArrayNode bars) {
        List<ObjectNode> rows = new ArrayList<>();
        bars.forEach(row -> rows.add((ObjectNode) row));
        List<ObjectNode> features = StrategyFixedBaselineV5.deriveFeatures(rows, List.of());
        assertThat(features).hasSize(1);
        return features.get(0);
    }

    private static ArrayNode setup(boolean treated) {
        try {
            Method method = StrategyOperatingCharacteristicsV4.class.getDeclaredMethod("setupSeries",
                    String.class, String.class, String.class, long.class, boolean.class, SplittableRandom.class);
            method.setAccessible(true);
            return (ArrayNode) method.invoke(null, "btc", "BTCUSDT", "review-event", DECISION,
                    treated, new SplittableRandom(2026090601L));
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Update reviewer adapter after generator extraction", error);
        }
    }
}
