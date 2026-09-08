package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Real frozen FK v002 contract boundary matrix for the fixed-baseline validator. */
final class StrategyFixedBaselineV5SupportedContractMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final Method VALIDATOR = validator();
    private static final String BASELINE = "strategy-research/definitions/fk-deleveraging-absorption/v002.json";
    private static final String CONTROLS = "strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json";
    private static final String EXPERIMENT = "strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json";

    @Test
    void frozenFkV002ContractIsAcceptedAsTheOnlySupportedShape() {
        Fixture fixture = fixture();
        assertThat(invoke(fixture)).isNull();
    }

    @Test
    void identityAndDataContractMutationsFailClosed() {
        reject(fixture -> fixture.baseline.put("baseline_id", "other-baseline"),
                "fixed evaluator only supports the frozen FK v002 contract");
        reject(fixture -> fixture.baseline.put("hypothesis_family", "other-family"),
                "fixed evaluator only supports the frozen FK v002 contract");
        reject(fixture -> fixture.experiment.put("stage", "SEARCH"),
                "fixed evaluator only supports the frozen FK v002 contract");
        reject(fixture -> fixture.experiment.put("selection", "GENETIC"),
                "fixed evaluator only supports the frozen FK v002 contract");

        reject(fixture -> fixture.baseline.remove("data_contract"), "unsupported fixed evaluator data contract");
        reject(fixture -> fixture.baseline.with("data_contract").put("signal_timeframe", "1h"),
                "unsupported fixed evaluator data contract");
        reject(fixture -> fixture.baseline.with("data_contract").put("execution_timeframe", "5m"),
                "unsupported fixed evaluator data contract");
        reject(fixture -> fixture.baseline.with("data_contract").put("completed_bar_only", false),
                "unsupported fixed evaluator data contract");
        reject(fixture -> fixture.baseline.with("data_contract").put("warmup_completed_signal_bars", 30),
                "unsupported fixed evaluator data contract");
    }

    @Test
    void shockFormulaAndThresholdMutationsFailClosed() {
        reject(fixture -> fixture.baseline.with("shock_rule").remove("return"),
                "unsupported fixed evaluator shock formula or threshold");
        reject(fixture -> fixture.baseline.with("shock_rule").with("return").put("name", "forward_return"),
                "unsupported fixed evaluator shock formula or threshold");
        reject(fixture -> fixture.baseline.with("shock_rule").with("return").put("formula", "close_t / close_t-1"),
                "unsupported fixed evaluator shock formula or threshold");
        reject(fixture -> fixture.baseline.with("shock_rule").with("return").put("threshold", -.09),
                "unsupported fixed evaluator shock formula or threshold");

        reject(fixture -> fixture.baseline.with("shock_rule").remove("volume"),
                "unsupported fixed evaluator shock formula or threshold");
        reject(fixture -> fixture.baseline.with("shock_rule").with("volume").put("name", "volume_multiple"),
                "unsupported fixed evaluator shock formula or threshold");
        reject(fixture -> fixture.baseline.with("shock_rule").with("volume").put("threshold", 2.1),
                "unsupported fixed evaluator shock formula or threshold");
        reject(fixture -> fixture.baseline.with("shock_rule").with("volume").put("lookback_completed_bars", 29),
                "unsupported fixed evaluator shock formula or threshold");
        reject(fixture -> fixture.baseline.with("shock_rule").with("volume").put("current_bar_excluded", false),
                "unsupported fixed evaluator shock formula or threshold");

        reject(fixture -> fixture.baseline.with("shock_rule").remove("volatility"),
                "unsupported fixed evaluator shock formula or threshold");
        reject(fixture -> fixture.baseline.with("shock_rule").with("volatility").put("name", "realized_volatility"),
                "unsupported fixed evaluator shock formula or threshold");
        reject(fixture -> fixture.baseline.with("shock_rule").with("volatility").put("threshold", .02),
                "unsupported fixed evaluator shock formula or threshold");
        reject(fixture -> fixture.baseline.with("shock_rule").with("volatility").put("lookback_completed_bars", 29),
                "unsupported fixed evaluator shock formula or threshold");
        reject(fixture -> fixture.baseline.with("shock_rule").with("volatility").put("current_bar_excluded", false),
                "unsupported fixed evaluator shock formula or threshold");
    }

    @Test
    void executionContractMutationsFailClosed() {
        reject(fixture -> fixture.baseline.remove("execution"), "unsupported fixed evaluator execution contract");
        reject(fixture -> fixture.baseline.with("execution").with("timeout").put("bars", 14_399),
                "unsupported fixed evaluator execution contract");
        reject(fixture -> fixture.baseline.with("execution").with("timeout").put("horizon_hours", 239),
                "unsupported fixed evaluator execution contract");
        reject(fixture -> fixture.baseline.with("execution").with("fees").put("entry_rate", .002),
                "unsupported fixed evaluator execution contract");
        reject(fixture -> fixture.baseline.with("execution").with("fees").put("exit_rate", .002),
                "unsupported fixed evaluator execution contract");
        reject(fixture -> fixture.baseline.with("execution").with("slippage").put("entry_rate", .0006),
                "unsupported fixed evaluator execution contract");
        reject(fixture -> fixture.baseline.with("execution").with("slippage").put("exit_rate", .0006),
                "unsupported fixed evaluator execution contract");
        reject(fixture -> fixture.baseline.with("execution").with("sizing").put("notional_usdt", 999),
                "unsupported fixed evaluator execution contract");
        reject(fixture -> fixture.baseline.with("execution").with("sizing").put("mode", "VARIABLE"),
                "unsupported fixed evaluator execution contract");
        reject(fixture -> fixture.baseline.with("execution").with("stop").put("distance", .05),
                "unsupported fixed evaluator execution contract");
        reject(fixture -> fixture.baseline.with("execution").with("overlap").put("per_asset", "many"),
                "unsupported fixed evaluator execution contract");
    }

    @Test
    void matchingVariableAndChronologyMutationsFailClosed() {
        reject(fixture -> fixture.controls.remove("matching_variables"),
                "unsupported fixed evaluator control matching variables");
        reject(fixture -> ((ArrayNode) fixture.controls.path("matching_variables")).set(0, MAPPER.getNodeFactory()
                        .textNode("decision_time")), "unsupported fixed evaluator control matching variables");
        reject(fixture -> fixture.controls.with("calipers").put("minimum_prior_lag_hours", 479),
                "unsupported fixed evaluator control chronology");
        reject(fixture -> fixture.controls.with("calipers").put("maximum_prior_lookback_days", 0),
                "unsupported fixed evaluator control chronology");
        reject(fixture -> fixture.controls.with("calipers").put("maximum_lifecycle_hours", 239),
                "unsupported fixed evaluator control chronology");
        reject(fixture -> fixture.controls.remove("calipers"), "unsupported fixed evaluator control chronology");
    }

    private static void reject(Consumer<Fixture> mutation, String message) {
        Fixture fixture = fixture();
        mutation.accept(fixture);
        assertThatThrownBy(() -> VALIDATOR.invoke(null, fixture.baseline, fixture.controls, fixture.experiment))
                .isInstanceOf(InvocationTargetException.class)
                .hasRootCauseMessage(message);
    }

    private static ObjectNode invoke(Fixture fixture) {
        try {
            return (ObjectNode) VALIDATOR.invoke(null, fixture.baseline, fixture.controls, fixture.experiment);
        } catch (InvocationTargetException error) {
            throw new IllegalArgumentException("unexpected supported-contract rejection", error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Method validator() {
        try {
            Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("validateSupportedFixedContract",
                    ObjectNode.class, ObjectNode.class, ObjectNode.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static Fixture fixture() {
        return new Fixture(read(BASELINE), read(CONTROLS), read(EXPERIMENT));
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

    private record Fixture(ObjectNode baseline, ObjectNode controls, ObjectNode experiment) {}
}
