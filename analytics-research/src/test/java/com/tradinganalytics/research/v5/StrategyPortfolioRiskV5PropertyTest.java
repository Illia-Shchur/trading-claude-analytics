package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/** Algebraic checks for the custody/hash and monotonic-risk invariants. */
final class StrategyPortfolioRiskV5PropertyTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @Property(tries = 80)
    void canonicalHashIgnoresObjectInsertionOrder(@ForAll @IntRange(min = 0, max = 10_000) int value) {
        ObjectNode left = object().put("z", value).put("a", value * 2L);
        ObjectNode right = object().put("a", value * 2L).put("z", value);
        assertThat(StrategyPortfolioRiskV5.hash(left)).isEqualTo(StrategyPortfolioRiskV5.hash(right));
    }

    @Property(tries = 80)
    void withHashAlwaysRecomputesOwnHash(@ForAll @IntRange(min = -10_000, max = 10_000) int value) {
        ObjectNode original = object().put("value", value).put("content_sha256", "stale");
        ObjectNode result = StrategyPortfolioRiskV5.withHash(original);
        assertThat(result.path("content_sha256").asText()).isEqualTo(StrategyPortfolioRiskV5.ownHash(result));
        assertThat(original.path("content_sha256").asText()).isEqualTo("stale");
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
}
