package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Frozen baseline, control, and experiment lineage matrix for fixed admission. */
final class StrategyFixedBaselineV5FrozenContractMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final Method VALIDATOR = validator();
    private static final Path BASELINE_PATH = Path.of(
            "strategy-research/definitions/fk-deleveraging-absorption/v002.json");
    private static final Path CONTROLS_PATH = Path.of(
            "strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json");
    private static final Path EXPERIMENT_PATH = Path.of(
            "strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json");

    @Test
    void realFrozenDefinitionsPassTheCompleteLineageContract() {
        assertThat(invoke(fixture())).isNull();
    }

    @Test
    void baselineAndExperimentMustRemainFrozen() {
        reject(fixture -> fixture.baseline.put("status", "DRAFT"),
                "fixed baseline requires FROZEN baseline and experiment");
        reject(fixture -> fixture.experiment.put("status", "DRAFT"),
                "fixed baseline requires FROZEN baseline and experiment");
        reject(fixture -> fixture.baseline.with("activation").put("authorized", true),
                "baseline activation contract is not fail-closed");
        reject(fixture -> fixture.baseline.with("activation").put("promotion_eligibility", "SHADOW"),
                "baseline activation contract is not fail-closed");
    }

    @Test
    void controlsMustIdentifyAnOutcomeBlindDecisionTimeOnlyBaseline() {
        reject(fixture -> fixture.controls.put("baseline_id", "other-baseline"),
                "control lineage does not identify the frozen baseline");
        reject(fixture -> fixture.controls.put("outcome_blind", false),
                "control spec must be outcome-blind and decision-time-only");
        reject(fixture -> fixture.controls.put("decision_time_only", false),
                "control spec must be outcome-blind and decision-time-only");
    }

    @Test
    void experimentMustBindDefinitionHashesAndTypedPaths() {
        reject(fixture -> fixture.experiment.put("baseline_sha256", hash("wrong-baseline")),
                "experiment is not hash-bound to the supplied frozen definitions");
        reject(fixture -> fixture.experiment.put("controls_sha256", hash("wrong-controls")),
                "experiment is not hash-bound to the supplied frozen definitions");
        reject(fixture -> fixture.experiment.put("baseline_path", "other-baseline.json"),
                "experiment is not linked to the supplied typed definitions");
        reject(fixture -> fixture.experiment.put("controls_path", "other.json"),
                "experiment is not linked to the supplied typed definitions");
    }

    private static void reject(Consumer<Fixture> mutation, String message) {
        Fixture fixture = fixture();
        mutation.accept(fixture);
        assertThatThrownBy(() -> VALIDATOR.invoke(null, fixture.baseline, fixture.controls, fixture.experiment,
                BASELINE_PATH, CONTROLS_PATH))
                .isInstanceOf(InvocationTargetException.class).hasRootCauseMessage(message);
    }

    private static ObjectNode invoke(Fixture fixture) {
        try {
            return (ObjectNode) VALIDATOR.invoke(null, fixture.baseline, fixture.controls, fixture.experiment,
                    BASELINE_PATH, CONTROLS_PATH);
        } catch (InvocationTargetException error) {
            throw new IllegalArgumentException("unexpected frozen-contract rejection", error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Method validator() {
        try {
            Method method = StrategyFixedBaselineV5.class.getDeclaredMethod("validateFrozenContracts",
                    ObjectNode.class, ObjectNode.class, ObjectNode.class, Path.class, Path.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static Fixture fixture() {
        return new Fixture(read(BASELINE_PATH), read(CONTROLS_PATH), read(EXPERIMENT_PATH));
    }

    private static ObjectNode read(Path relative) {
        Path path = Files.exists(relative) ? relative : Path.of("..", relative.toString()).normalize();
        try {
            return (ObjectNode) MAPPER.readTree(Files.readString(path));
        } catch (IOException error) {
            throw new IllegalStateException("cannot read frozen fixture " + path, error);
        }
    }

    private static String hash(String value) { return JsonHashes.sha256(value); }
    private record Fixture(ObjectNode baseline, ObjectNode controls, ObjectNode experiment) {}
}
