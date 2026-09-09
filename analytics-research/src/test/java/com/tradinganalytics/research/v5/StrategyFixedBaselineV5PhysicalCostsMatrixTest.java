package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Internal fixed-baseline economics validation using in-memory role values, not receipt custody. */
final class StrategyFixedBaselineV5PhysicalCostsMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final Method VALIDATOR = validator();
    private static final String BASELINE = "strategy-research/definitions/fk-deleveraging-absorption/v002.json";
    private static final double FEE = .001D;
    private static final double SLIPPAGE_BPS = 5D;
    private static final double NOTIONAL = 1_000D;

    @Test
    void completeCommonPhysicalReceiptsMatchFrozenBaselineCosts() {
        assertThat(invoke(fixture())).isNull();
    }

    @Test
    void requiredRolesAndFrozenFeeSlippageReceiptsAreFailClosed() {
        reject(fixture -> fixture.roles.remove("execution_model"),
                "contract_spec, execution_model, and capacity receipts are required");
        reject(fixture -> fixture.roles.put("capacity", newRole(MAPPER.createArrayNode())),
                "contract_spec, execution_model, and capacity receipts are required");
        reject(fixture -> fixture.roles.put("contract_spec", newRole(MAPPER.createArrayNode())),
                "contract_spec, execution_model, and capacity receipts are required");

        reject(fixture -> fixture.baseline.with("execution").with("fees").put("entry_rate", .002),
                "physical fee/slippage metadata does not equal frozen baseline costs");
        reject(fixture -> fixture.baseline.with("execution").with("fees").put("exit_rate", .002),
                "physical fee/slippage metadata does not equal frozen baseline costs");
        reject(fixture -> fixture.baseline.with("execution").with("slippage").put("entry_rate", .0006),
                "physical fee/slippage metadata does not equal frozen baseline costs");
        reject(fixture -> roleObject(fixture, "execution_model").put("taker_fee_rate", .002),
                "physical fee/slippage metadata does not equal frozen baseline costs");
        reject(fixture -> roleObject(fixture, "execution_model").put("slippage_bps", 6),
                "physical fee/slippage metadata does not equal frozen baseline costs");
    }

    @Test
    void notionalAndExchangeFilterReceiptsAreRequiredAndPositive() {
        reject(fixture -> fixture.baseline.with("execution").with("sizing").put("notional_usdt", 999),
                "physical capacity order notional does not equal frozen baseline sizing");
        reject(fixture -> roleObject(fixture, "capacity").put("order_notional_usd", 999),
                "physical capacity order notional does not equal frozen baseline sizing");

        for (String field : List.of("step_size", "min_qty", "min_notional", "max_notional")) {
            reject(fixture -> roleObject(fixture, "contract_spec").put(field, 0),
                    "decision-time exchange quantity, min/max quantity and notional filters are required");
        }
        reject(fixture -> roleObject(fixture, "capacity").put("available_liquidity_usd", 0),
                "capacity receipt must bind available liquidity and participation cap");
        reject(fixture -> roleObject(fixture, "capacity").put("participation_cap", 0),
                "capacity receipt must bind available liquidity and participation cap");
        reject(fixture -> roleObject(fixture, "capacity").put("participation_cap", 1.01),
                "capacity receipt must bind available liquidity and participation cap");
    }

    @Test
    void assetSpecificReceiptsMustPreserveCommonEconomicsAndFilters() {
        Fixture valid = fixture();
        valid.roles.put("execution_model:btc", newRole(object()
                .put("taker_fee_rate", FEE).put("slippage_bps", SLIPPAGE_BPS)));
        valid.roles.put("capacity:btc", newRole(object().put("order_notional_usd", NOTIONAL)
                .put("available_liquidity_usd", 1_000_000).put("participation_cap", .5)));
        valid.roles.put("contract_spec:btc", newRole(object().put("step_size", .00001)
                .put("min_qty", .00001).put("min_notional", 10).put("max_notional", 1_000_000)));
        assertThat(invoke(valid)).isNull();

        reject(fixture -> fixture.roles.put("execution_model:btc", newRole(object()
                        .put("taker_fee_rate", .002).put("slippage_bps", SLIPPAGE_BPS))),
                "execution_model:btc does not equal the frozen baseline costs");
        reject(fixture -> fixture.roles.put("execution_model:btc", newRole(object()
                        .put("taker_fee_rate", FEE).put("slippage_bps", Double.NaN))),
                "execution_model:btc does not equal the frozen baseline costs");

        reject(fixture -> fixture.roles.put("capacity:btc", newRole(object()
                        .put("order_notional_usd", NOTIONAL).put("available_liquidity_usd", 0)
                        .put("participation_cap", .5))),
                "capacity:btc has invalid or non-finite capacity metadata");
        reject(fixture -> fixture.roles.put("capacity:btc", newRole(object()
                        .put("order_notional_usd", 999).put("available_liquidity_usd", 1_000_000)
                        .put("participation_cap", .5))),
                "capacity:btc has invalid or non-finite capacity metadata");

        reject(fixture -> fixture.roles.put("contract_spec:btc", newRole(object()
                        .put("step_size", 0).put("min_qty", .00001).put("min_notional", 10)
                        .put("max_notional", 1_000_000))),
                "contract_spec:btc has invalid or non-finite exchange filters");
        reject(fixture -> fixture.roles.put("contract_spec:btc", newRole(object()
                        .put("step_size", .00001).put("min_qty", .00001).put("min_notional", 10)
                        .put("max_notional", Double.NaN))),
                "contract_spec:btc has invalid or non-finite exchange filters");
    }

    @Test
    void unrelatedRoleValuesAreIgnoredByEconomicsValidation() {
        Fixture fixture = fixture();
        fixture.roles.put("metadata", newRole(MAPPER.createArrayNode()));
        assertThat(invoke(fixture)).isNull();
    }

    private static void reject(Consumer<Fixture> mutation, String message) {
        Fixture fixture = fixture();
        mutation.accept(fixture);
        assertThatThrownBy(() -> VALIDATOR.invoke(null, fixture.physical(), fixture.baseline))
                .isInstanceOf(InvocationTargetException.class).hasRootCauseMessage(message);
    }

    private static ObjectNode invoke(Fixture fixture) {
        try {
            return (ObjectNode) VALIDATOR.invoke(null, fixture.physical(), fixture.baseline);
        } catch (InvocationTargetException error) {
            throw new IllegalArgumentException("unexpected physical-cost rejection", error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Method validator() {
        try {
            Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("validatePhysicalCosts",
                    StrategyFixedBaselineV5.PhysicalInput.class, ObjectNode.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static Fixture fixture() {
        ObjectNode baseline = read(BASELINE);
        Map<String, StrategyFixedBaselineV5.Role> roles = new LinkedHashMap<>();
        roles.put("contract_spec", newRole(object().put("step_size", .00001).put("min_qty", .00001)
                .put("min_notional", 10).put("max_notional", 1_000_000)));
        roles.put("execution_model", newRole(object().put("taker_fee_rate", FEE)
                .put("slippage_bps", SLIPPAGE_BPS)));
        roles.put("capacity", newRole(object().put("order_notional_usd", NOTIONAL)
                .put("available_liquidity_usd", 1_000_000).put("participation_cap", 1)));
        StrategyFixedBaselineV5.PhysicalInput physical = new StrategyFixedBaselineV5.PhysicalInput(
                Path.of("."), "in-memory", hash("physical"), roles, List.of(), List.of(), List.of(), null);
        return new Fixture(baseline, roles, physical);
    }

    private static StrategyFixedBaselineV5.Role newRole(JsonNode value) {
        return new StrategyFixedBaselineV5.Role(value, null);
    }

    private static ObjectNode roleObject(Fixture fixture, String name) {
        return (ObjectNode) fixture.roles.get(name).value;
    }

    private static ObjectNode read(String relative) {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("..", relative).normalize();
        try {
            return (ObjectNode) MAPPER.readTree(Files.readString(path));
        } catch (IOException error) {
            throw new IllegalStateException("cannot read frozen fixture " + path, error);
        }
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static String hash(String value) { return JsonHashes.sha256(value); }

    private record Fixture(ObjectNode baseline, Map<String, StrategyFixedBaselineV5.Role> roles,
            StrategyFixedBaselineV5.PhysicalInput physical) {}
}
