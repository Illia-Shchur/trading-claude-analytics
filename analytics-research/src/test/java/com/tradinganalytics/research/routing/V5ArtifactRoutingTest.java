package com.tradinganalytics.research.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class V5ArtifactRoutingTest {
    @Test
    void routesPrefixSuffixAndAdditiveAllowlist() {
        assertThat(V5ArtifactRouting.isV5ArtifactSchema("strategy-v5-custom/1")).isTrue();
        assertThat(V5ArtifactRouting.isV5ArtifactSchema("strategy-research-run/5")).isTrue();
        assertThat(V5ArtifactRouting.isV5ArtifactSchema("strategy-portfolio-policy/2")).isTrue();
        assertThat(V5ArtifactRouting.isV5ArtifactSchema("strategy-precommit/1")).isFalse();
        assertThat(V5ArtifactRouting.isV5ArtifactSchema(null)).isFalse();
    }

    @Test
    void disablingPrefixStillAllowsVersionFiveAndExplicitSchemas() {
        assertThat(V5ArtifactRouting.isV5ArtifactSchema(
                "strategy-v5-custom/1", false, V5ArtifactRouting.VALIDATE_SCHEMA_ALLOWLIST)).isFalse();
        assertThat(V5ArtifactRouting.isV5ArtifactSchema(
                "strategy-research-run/5", false, List.of())).isTrue();
        assertThat(V5ArtifactRouting.isV5ArtifactSchema(
                "strategy-data-manifest/3", false, V5ArtifactRouting.VALIDATE_SCHEMA_ALLOWLIST)).isTrue();
    }

    @Test
    void allowlistsRemainFrozenAndExact() {
        assertThat(V5ArtifactRouting.INDEX_SCHEMA_ALLOWLIST).containsExactly(
                "strategy-portfolio-policy/2",
                "strategy-readiness-evidence-manifest/1",
                "strategy-readiness-audit/2");
        assertThat(V5ArtifactRouting.VALIDATE_SCHEMA_ALLOWLIST).hasSize(7);
        assertThat(V5ArtifactRouting.ARTIFACT_SCHEMA_ALLOWLIST).hasSize(10);
    }
}
