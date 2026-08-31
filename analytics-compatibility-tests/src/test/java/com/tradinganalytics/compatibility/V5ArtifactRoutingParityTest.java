package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradinganalytics.research.routing.V5ArtifactRouting;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class V5ArtifactRoutingParityTest {
    record Case(String schema, boolean allowPrefix, List<String> allowlist, boolean expected) {
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case(null, true, V5ArtifactRouting.ARTIFACT_SCHEMA_ALLOWLIST, false),
                new Case("strategy-v5-custom/1", true, V5ArtifactRouting.ARTIFACT_SCHEMA_ALLOWLIST, true),
                new Case("strategy-v5-custom/1", false, V5ArtifactRouting.VALIDATE_SCHEMA_ALLOWLIST, false),
                new Case("strategy-research-run/5", false, List.of(), true),
                new Case("strategy-data-manifest/3", false, V5ArtifactRouting.VALIDATE_SCHEMA_ALLOWLIST, true),
                new Case("strategy-precommit/1", true, V5ArtifactRouting.ARTIFACT_SCHEMA_ALLOWLIST, false));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void javaRoutingMatchesFrozenCompatibilityVector(Case testCase) {
        assertThat(V5ArtifactRouting.isV5ArtifactSchema(
                testCase.schema(), testCase.allowPrefix(), testCase.allowlist())).isEqualTo(testCase.expected());
    }
}
