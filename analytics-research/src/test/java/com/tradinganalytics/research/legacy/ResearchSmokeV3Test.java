package com.tradinganalytics.research.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ResearchSmokeV3Test {
    @Test
    void validatesFrozenUniverseAndRejectsTheExplicitZeroTradeCandidate() throws Exception {
        JsonNode result = ResearchSmokeV3.run(repositoryRoot(),
                Clock.fixed(Instant.parse("2025-01-02T03:04:05Z"), ZoneOffset.UTC));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("assets")).hasSize(8);
        assertThat(result.path("assets").get(0).asText()).isEqualTo("btc");
        assertThat(result.path("assets").get(7).asText()).isEqualTo("aave");
        assertThat(result.path("doge").asText()).isEqualTo("excluded");
        assertThat(result.path("zero_trade").path("decision").asText()).isEqualTo("REJECTED");
        assertThat(result.path("experiment").asText()).matches("[a-f0-9]{64}");
    }

    @Test
    void repositoryRootCanBeDiscoveredFromNestedDirectories() {
        Path root = repositoryRoot();
        assertThat(ResearchSmokeV3.repositoryRoot(root.resolve("analytics-research/src/test"))).isEqualTo(root);
    }

    private static Path repositoryRoot() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null
                && !Files.isRegularFile(path.resolve("strategy-research/config/research-universe-v3.json"))) {
            path = path.getParent();
        }
        if (path == null) throw new IllegalStateException("repository root not found");
        return path;
    }
}
