package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.HashSet;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/** Algebraic checks for canonical hashing, exposure accounting, and optimizer ordering. */
final class StrategyStatisticalV5PropertyTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @Property(tries = 80)
    void canonicalHashIgnoresObjectInsertionOrder(@ForAll @IntRange(min = -100_000, max = 100_000) int value) {
        ObjectNode left = object().put("z", value).put("a", value * 2L);
        ObjectNode right = object().put("a", value * 2L).put("z", value);
        assertThat(StrategyStatisticalV5.hash(left)).isEqualTo(StrategyStatisticalV5.hash(right));
    }

    @Property(tries = 80)
    void withHashIsNonMutatingAndAlwaysRecomputesOwnHash(
            @ForAll @IntRange(min = -100_000, max = 100_000) int value) {
        ObjectNode input = object().put("value", value).put("content_sha256", "stale");
        ObjectNode result = StrategyStatisticalV5.withHash(input);
        assertThat(result.path("content_sha256").asText()).isEqualTo(StrategyStatisticalV5.ownHash(result));
        assertThat(input.path("content_sha256").asText()).isEqualTo("stale");
    }

    @Property(tries = 60)
    void exposureAppendDeduplicatesBehaviorWhileChargingEveryAttempt(
            @ForAll @IntRange(min = 0, max = 10_000) int value) {
        String dataset = JsonHashes.sha256("dataset-" + value);
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(object()
                .put("hypothesisFamily", "family-" + value).put("datasetSha256", dataset));
        String alias = JsonHashes.sha256("alias-" + value); ObjectNode append = object();
        append.set("prior", head); append.put("datasetSha256", dataset);
        append.putArray("behaviorAliases").add(alias).add(alias); append.put("exposureAttemptCount", 2);
        ObjectNode result = StrategyStatisticalV5.appendExposureHead(append);
        assertThat(result.path("entries")).hasSize(1);
        assertThat(result.path("cumulative_k").asInt()).isEqualTo(1);
        assertThat(result.path("exposure_attempt_k").asInt()).isEqualTo(2);
        assertThat(StrategyStatisticalV5.validateExposureHead(result)).isEqualTo(result);
    }

    @Property(tries = 80)
    void constrainedDominanceIsAsymmetricForDistinctFeasibleObjectives(
            @ForAll @IntRange(min = -1000, max = 1000) int value) {
        ObjectNode stronger = feasible(value + 1, value + 2);
        ObjectNode weaker = feasible(value, value + 1);
        assertThat(StrategyStatisticalV5.constrainedDominates(stronger, weaker)).isTrue();
        assertThat(StrategyStatisticalV5.constrainedDominates(weaker, stronger)).isFalse();
    }

    @Property(tries = 60)
    void directNeighboursRemainUniqueAndInsideTheFrozenGeneSpace(
            @ForAll @IntRange(min = 0, max = 10) int value) {
        ObjectNode space = object(); ObjectNode gene = space.putArray("genes").addObject()
                .put("name", "threshold").put("type", "continuous").put("min", 0).put("max", 10)
                .put("step", 1).put("default", 5);
        ObjectNode chromosome = object().put("threshold", value);
        ArrayNode neighbours = StrategyStatisticalV5.enumerateDirectNeighbours(space, chromosome);
        HashSet<String> canonical = new HashSet<>();
        for (var row : neighbours) {
            assertThat(row.path("threshold").asDouble()).isBetween(0d, 10d);
            canonical.add(StrategyStatisticalV5.stable(row));
        }
        assertThat(canonical).hasSize(neighbours.size());
    }

    private static ObjectNode feasible(double first, double second) {
        ObjectNode value = object().put("feasible", true).put("total_violation", 0);
        value.set("objectives", MAPPER.valueToTree(List.of(first, second, 0, 0)));
        value.set("violation_details", object()); return value;
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
}
