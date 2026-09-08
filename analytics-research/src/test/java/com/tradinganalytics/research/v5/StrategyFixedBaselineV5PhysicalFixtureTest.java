package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.LifecycleTrustService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Small, fully local physical inputs for the fixed baseline.  The expected
 * economics in these tests are computed from the fixture bars and cost roles,
 * rather than copied from a golden result.
 */
class StrategyFixedBaselineV5PhysicalFixtureTest {
    private static final double NOTIONAL = 1_000D;
    private static final double FEE_RATE = .001D;
    private static final double SLIPPAGE_RATE = .0005D;
    private static final String TEST_EXECUTOR_IDENTITY = JsonHashes.sha256("physical-fixture-executor");

    @TempDir Path temporary;

    @Test
    void evaluateReopensLocalOutcomesAndReconcilesIndependentTradeEconomics() throws Exception {
        Fixture fixture = fixture();
        ObjectNode result = evaluate(fixture);

        assertThat(result.path("status").asText()).describedAs(result.toString()).isEqualTo("COMPLETE");
        assertThat(result.path("attempts")).hasSize(1);
        ObjectNode attempt = (ObjectNode) result.path("attempts").get(0);
        assertThat(attempt.path("status").asText()).isEqualTo("COMPLETE");

        ObjectNode eventTrade = (ObjectNode) attempt.path("event_trade");
        ObjectNode controlTrade = (ObjectNode) attempt.path("control_trade");
        assertThat(eventTrade.path("entry_price").asDouble()).isEqualTo(100D);
        assertThat(eventTrade.path("exit_price").asDouble()).isEqualTo(93D);
        assertThat(controlTrade.path("entry_price").asDouble()).isEqualTo(100D);
        assertThat(controlTrade.path("exit_price").asDouble()).isEqualTo(94D);

        double stepSize = .00001D;
        double quantity = BigDecimal.valueOf(NOTIONAL).divide(BigDecimal.valueOf(100D))
                .divide(BigDecimal.valueOf(stepSize)).setScale(0, RoundingMode.FLOOR)
                .multiply(BigDecimal.valueOf(stepSize)).doubleValue();
        double eventGross = (93D - 100D) * quantity;
        double controlGross = (94D - 100D) * quantity;
        double eventCosts = (100D + 93D) * quantity * (FEE_RATE + SLIPPAGE_RATE);
        double controlCosts = (100D + 94D) * quantity * (FEE_RATE + SLIPPAGE_RATE);
        double expectedEventNet = eventGross - eventCosts;
        double expectedControlNet = controlGross - controlCosts;

        assertThat(eventTrade.path("gross_pnl_usdt").asDouble()).isCloseTo(eventGross, within(1e-9));
        assertThat(eventTrade.path("fees_usdt").asDouble())
                .isCloseTo((100D + 93D) * quantity * FEE_RATE, within(1e-9));
        assertThat(eventTrade.path("slippage_usdt").asDouble())
                .isCloseTo((100D + 93D) * quantity * SLIPPAGE_RATE, within(1e-9));
        assertThat(eventTrade.path("net_pnl_usdt").asDouble()).isCloseTo(expectedEventNet, within(1e-9));
        assertThat(controlTrade.path("gross_pnl_usdt").asDouble()).isCloseTo(controlGross, within(1e-9));
        assertThat(controlTrade.path("fees_usdt").asDouble())
                .isCloseTo((100D + 94D) * quantity * FEE_RATE, within(1e-9));
        assertThat(controlTrade.path("slippage_usdt").asDouble())
                .isCloseTo((100D + 94D) * quantity * SLIPPAGE_RATE, within(1e-9));
        assertThat(controlTrade.path("net_pnl_usdt").asDouble()).isCloseTo(expectedControlNet, within(1e-9));
        assertThat(result.path("portfolio").path("net_pnl_usdt").asDouble())
                .isCloseTo(expectedEventNet + expectedControlNet, within(1e-9));
        assertThat(attempt.path("paired_net_pnl_usdt").asDouble())
                .isCloseTo(expectedEventNet - expectedControlNet, within(1e-9));
    }

    @Test
    void productionEntryRequiresPackagedExecutorIdentity() throws Exception {
        Fixture fixture = fixture();
        JsonNode executable = BuildIdentityService.describe(StrategyFixedBaselineV5.class).path("executable");
        if ("CLASSES".equals(executable.path("kind").asText())) {
            assertThatThrownBy(() -> evaluateProduction(fixture))
                    .hasMessage("packaged executor identity is unavailable");
        } else {
            assertThat(executable.path("kind").asText()).isEqualTo("JAR");
            assertThat(executable.path("sha256").asText()).matches("[a-f0-9]{64}");
        }
    }

    @Test
    void invalidPhysicalOutcomeEvidenceBlocksAttemptWithoutManufacturingZeroReturn() throws Exception {
        Fixture fixture = fixture();
        ArrayNode labels = (ArrayNode) fixture.roles.get("labels").value;
        ((ObjectNode) labels.get(0)).put("asset", "eth");
        Fixture invalid = fixture.withRoles(replaceRole(fixture, "labels", labels));

        ObjectNode result = evaluate(invalid);
        ObjectNode attempt = (ObjectNode) result.path("attempts").get(0);
        assertThat(attempt.path("status").asText()).isEqualTo("UNRESOLVED");
        assertThat(attempt.path("reason").asText()).isEqualTo("OUTCOME_METADATA_ASSET_OR_EPISODE_MISMATCH");
        assertThat(result.path("disposition").path("primary_reason").asText())
                .isEqualTo("INVALID_EVIDENCE");
        assertThat(result.path("portfolio").path("trade_count").asInt()).isZero();
        assertThat(result.path("portfolio").path("net_pnl_usdt").asDouble()).isZero();
    }

    @Test
    void duplicatePhysicalOutcomeRowsAreRejectedBeforeAnAttemptCanBeCountedTwice() throws Exception {
        Fixture fixture = fixture();
        ArrayNode labels = (ArrayNode) fixture.roles.get("labels").value;
        labels.add(labels.get(0).deepCopy());
        Fixture duplicate = fixture.withRoles(replaceRole(fixture, "labels", labels));

        assertThatThrownBy(() -> evaluate(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate label episode");
    }

    @Test
    void physicalCostValidationRejectsMismatchedAndIncompleteEvidence() throws Exception {
        Fixture fixture = fixture();
        Method validate = StrategyFixedBaselineV5.class.getDeclaredMethod(
                "validatePhysicalCosts", StrategyFixedBaselineV5.PhysicalInput.class, ObjectNode.class);
        validate.setAccessible(true);

        ObjectNode mismatchedModel = fixture.roles.get("execution_model").value.deepCopy();
        mismatchedModel.put("taker_fee_rate", .002D);
        Fixture mismatched = fixture.withRoles(replaceRole(fixture, "execution_model", mismatchedModel));
        assertInvocationFailure(validate, "physical fee/slippage metadata does not equal frozen baseline costs",
                mismatched.physical, fixture.baseline);

        ObjectNode incompleteCapacity = fixture.roles.get("capacity").value.deepCopy();
        incompleteCapacity.remove("available_liquidity_usd");
        Fixture incomplete = fixture.withRoles(replaceRole(fixture, "capacity", incompleteCapacity));
        assertInvocationFailure(validate, "capacity receipt must bind available liquidity and participation cap",
                incomplete.physical, fixture.baseline);
    }

    @Test
    void resolvedAssetCostRolesRejectDifferentEconomicsBeforeLifecycle() throws Exception {
        Fixture fixture = fixture();
        Method resolveCosts = StrategyFixedBaselineV5.class.getDeclaredMethod("validateResolvedPhysicalCosts",
                StrategyFixedBaselineV5.Role.class, StrategyFixedBaselineV5.Role.class,
                StrategyFixedBaselineV5.Role.class, ObjectNode.class);
        resolveCosts.setAccessible(true);

        ObjectNode model = ((ObjectNode) fixture.roles.get("execution_model").value.deepCopy()).put("slippage_bps", 9D);
        StrategyFixedBaselineV5.Role invalidModel = role("invalid-model", model);
        assertInvocationFailure(resolveCosts, "resolved execution model differs from frozen fee/slippage costs",
                fixture.roles.get("contract_spec"), invalidModel, fixture.roles.get("capacity"), fixture.baseline);
    }

    private static void assertInvocationFailure(Method method, String message, Object... arguments) {
        assertThatThrownBy(() -> method.invoke(null, arguments))
                .isInstanceOf(InvocationTargetException.class)
                .hasRootCauseMessage(message);
    }

    private Fixture fixture() throws Exception {
        ObjectNode baseline = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repoFile(
                "strategy-research/definitions/fk-deleveraging-absorption/v002.json")));
        ObjectNode controls = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repoFile(
                "strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json")));
        ObjectNode experiment = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repoFile(
                "strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json")));
        ObjectNode portfolio = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repoFile(
                "strategy-research/experiments/fk-deleveraging-baseline-v002/portfolio-policy-v001.json")));

        Map<String, StrategyFixedBaselineV5.Role> roles = new LinkedHashMap<>();
        roles.put("contract_spec", role("contract-spec", object()
                .put("asset", "btc").put("symbol", "BTCUSDT").put("venue", "BINANCE")
                .put("instrument", "BINANCE_SPOT").put("contract_multiplier", 1D).put("step_size", .00001D)
                .put("min_qty", .00001D).put("max_qty", 100_000D).put("min_notional", 10D)
                .put("max_notional", 1_000_000D)));
        roles.put("execution_model", role("execution-model", object()
                .put("taker_fee_rate", FEE_RATE).put("slippage_bps", 5D)));
        roles.put("capacity", role("capacity", object()
                .put("order_notional_usd", NOTIONAL).put("available_liquidity_usd", 1_000_000D)
                .put("participation_cap", 1D)));
        roles.put("non_trading_intervals", role("non-trading", nonTradingPolicy()));

        ObjectNode event = feature("btc:2021-09-10T00:00:00Z", "2021-09-10T00:00:00Z", true);
        ObjectNode control = feature("btc:2021-08-20T00:00:00Z", "2021-08-20T00:00:00Z", false);
        control.put("completed_return", -.05D).put("shock_return", -.01D)
                .put("volume_multiple", 1D).put("realized_volatility", .01D);
        List<ObjectNode> features = List.of(event, control);
        List<ObjectNode> labels = new ArrayList<>();
        List<ObjectNode> executions = new ArrayList<>();
        addOutcome(labels, executions, roles, event, false);
        addOutcome(labels, executions, roles, control, true);
        roles.put("labels", role("labels", array(labels)));
        roles.put("executions", role("executions", array(executions)));
        StrategyFixedBaselineV5.PhysicalInput physical = new StrategyFixedBaselineV5.PhysicalInput(
                temporary, "portable-fixed-fixture", JsonHashes.sha256("portable-physical"), roles,
                features, labels, executions, null);
        return new Fixture(baseline, controls, experiment, portfolio, physical, roles, features);
    }

    private void addOutcome(List<ObjectNode> labels, List<ObjectNode> executions,
            Map<String, StrategyFixedBaselineV5.Role> roles, ObjectNode feature, boolean intrabarStop) throws Exception {
        String id = feature.path("episode_id").asText();
        String decision = feature.path("decision_time").asText();
        Instant start = Instant.parse(decision);
        String barsName = "bars-" + id.replace(':', '-').replace(':', '-');
        ArrayNode bars = JsonHashes.mapper().createArrayNode();
        bars.add(bar(start, 100D, 100D, 100D, 100D));
        if (intrabarStop) {
            bars.add(bar(start.plusSeconds(60L), 110D, 110D, 93D, 110D));
        } else {
            bars.add(bar(start.plusSeconds(60L), 93D, 93D, 90D, 92D));
        }
        roles.put(barsName, role(barsName, bars));
        labels.add(object().put("episode_id", id).put("asset", "btc").put("symbol", "BTCUSDT")
                .put("decision_time", decision).put("resolution_ceiling_time",
                        start.plusSeconds(240L * 3_600L).toString())
                .put("resolution_time", start.plusSeconds(240L * 3_600L).toString())
                .put("availability_time", start.plusSeconds(240L * 3_600L + 120L).toString()));
        executions.add(object().put("episode_id", id).put("asset", "btc").put("symbol", "BTCUSDT")
                .put("decision_time", decision).put("bars_role", barsName));
    }

    private ObjectNode evaluate(Fixture fixture) {
        ObjectNode exposure = object().put("content_sha256", JsonHashes.sha256("exposure"));
        return StrategyFixedBaselineV5.evaluateWithExecutorIdentityForTest(object(), fixture.baseline, fixture.controls,
                fixture.experiment, fixture.portfolio, fixture.physical, exposure, temporary.resolve("exposure.json"),
                object().put("status", "NO_RECOVERABLE_LEGACY_HISTORY"), "", Double.NaN,
                TEST_EXECUTOR_IDENTITY);
    }

    private ObjectNode evaluateProduction(Fixture fixture) {
        ObjectNode exposure = object().put("content_sha256", JsonHashes.sha256("exposure"));
        return StrategyFixedBaselineV5.evaluate(object(), fixture.baseline, fixture.controls,
                fixture.experiment, fixture.portfolio, fixture.physical, exposure, temporary.resolve("exposure.json"),
                object().put("status", "NO_RECOVERABLE_LEGACY_HISTORY"), "", Double.NaN);
    }

    private ObjectNode nonTradingPolicy() throws Exception {
        ObjectNode policy = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repoFile(
                "strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/non-trading-intervals.json")));
        ArrayNode intervals = policy.putArray("intervals");
        intervals.addObject().put("asset", "btc").put("symbol", "BTCUSDT")
                .put("venue", "BINANCE").put("instrument", "BINANCE_SPOT")
                .put("start_ms", Instant.parse("2021-10-01T00:01:00Z").toEpochMilli())
                .put("end_ms", Instant.parse("2021-10-02T00:01:00Z").toEpochMilli())
                .put("reason", "NON_TRADING").put("notice_url", "https://example.test/closure")
                .put("archive_month", "2021-08")
                .put("archive_checksum_url", "https://example.test/BTCUSDT-1m-2021-08.zip.CHECKSUM")
                .put("archive_zip_sha256", JsonHashes.sha256("portable-closure-archive"));
        policy.put("content_sha256", JsonHashes.ownHash(policy));
        return policy;
    }

    private static ObjectNode feature(String id, String decision, boolean shock) {
        return object().put("episode_id", id).put("signal_id", id).put("asset", "btc")
                .put("venue", "BINANCE").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                .put("decision_time", decision).put("event_time", decision).put("availability_time", decision)
                .put("setup_bar_count", 31).put("signal_eligible", true).put("position_state", "FLAT")
                .put("qualifying_shock", shock).put("shock_return", shock ? -.10D : -.01D)
                .put("volume_multiple", shock ? 2.2D : 1D).put("realized_volatility", shock ? .02D : .01D)
                .put("completed_return", shock ? -.10D : -.05D).put("prior_30_bar_return", -.11D)
                .put("prior_30_bar_realized_volatility", .2D).put("prior_30_bar_volume_zscore", 1D)
                .put("hour_of_day", 0).put("day_of_week", Instant.parse(decision).atZone(java.time.ZoneOffset.UTC)
                        .getDayOfWeek().getValue());
    }

    private static ObjectNode bar(Instant time, double open, double high, double low, double close) {
        return object().put("event_time", time.toString()).put("open_time", time.toString())
                .put("asset", "btc").put("symbol", "BTCUSDT").put("venue", "BINANCE")
                .put("instrument", "BINANCE_SPOT").put("open", open).put("high", high)
                .put("low", low).put("close", close).put("volume", 1D);
    }

    private StrategyFixedBaselineV5.Role role(String name, JsonNode value) throws Exception {
        byte[] bytes = JsonHashes.mapper().writeValueAsBytes(value);
        Path path = temporary.resolve(name + ".json");
        Files.write(path, bytes);
        String content = JsonHashes.ownHash(value);
        String rows = value.isArray() ? JsonHashes.canonicalSha256(value) : null;
        return new StrategyFixedBaselineV5.Role(value, new LifecycleTrustService.ReceiptReference(
                temporary.relativize(path).toString(), content, JsonHashes.sha256(bytes), (long) bytes.length,
                rows, null));
    }

    private Map<String, StrategyFixedBaselineV5.Role> replaceRole(Fixture fixture, String name, JsonNode value)
            throws Exception {
        Map<String, StrategyFixedBaselineV5.Role> result = new LinkedHashMap<>(fixture.roles);
        result.put(name, role(name + "-replacement", value));
        return result;
    }

    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array(List<ObjectNode> values) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private static Path repoFile(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("..", relative).normalize();
    }

    private record Fixture(ObjectNode baseline, ObjectNode controls, ObjectNode experiment, ObjectNode portfolio,
            StrategyFixedBaselineV5.PhysicalInput physical,
            Map<String, StrategyFixedBaselineV5.Role> roles, List<ObjectNode> features) {
        Fixture withRoles(Map<String, StrategyFixedBaselineV5.Role> replacement) {
            ArrayNode labels = (ArrayNode) replacement.get("labels").value;
            ArrayNode executions = (ArrayNode) replacement.get("executions").value;
            StrategyFixedBaselineV5.PhysicalInput next = new StrategyFixedBaselineV5.PhysicalInput(
                    physical.root(), physical.rootReference(), physical.contentSha256(), replacement, features,
                    nodes(labels), nodes(executions), physical.producerReceipt());
            return new Fixture(baseline, controls, experiment, portfolio, next, replacement, features);
        }

        private static List<ObjectNode> nodes(ArrayNode values) {
            List<ObjectNode> result = new ArrayList<>();
            values.elements().forEachRemaining(value -> result.add((ObjectNode) value));
            return result;
        }
    }
}
