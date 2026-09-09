package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Behavioral vectors carried over from strategy-v5-feature-lifecycle-test.mjs. */
final class OpportunityV5BehaviorTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final long START = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    @Test
    void overlappingWindowsSharePhysicalRowsAndLruOnlyLoadsIntersectingPartitions() {
        ArrayNode features = MAPPER.createArrayNode();
        for (int minute = 0; minute < 24; minute++) {
            features.addObject().put("asset", "btc").put("instrument", "BINANCE_SPOT")
                    .put("symbol", "BTCUSDT").put("decision_time", time(minute)).put("score", minute);
        }
        ObjectNode envelopeOptions = MAPPER.createObjectNode().put("fixtureOnly", true)
                .put("max_lifecycle_ms", 180_000);
        envelopeOptions.set("featureRows", features);
        envelopeOptions.putObject("geneSpace").putArray("genes").addObject()
                .put("name", "threshold").put("type", "continuous").put("min", 10).put("max", 20);
        ObjectNode predicate = MAPPER.createObjectNode().put("predictor_id", "score").put("op", "GTE");
        predicate.putObject("value").put("$gene", "threshold");
        envelopeOptions.set("predicate", predicate);
        ObjectNode envelope = OpportunityV5.makeOpportunityEnvelopeV5(envelopeOptions);
        assertThat(envelope.path("windows")).hasSize(14);

        ArrayNode bars = bars(0, 30);
        ObjectNode daily = OpportunityV5.makeContentAddressedPartitionsV5(
                MAPPER.createObjectNode().set("bars", bars));
        ObjectNode hydration = OpportunityV5.hydrateOpportunityEnvelopeV5(
                hydrate(envelope, daily.path("partitions")));
        assertThat(hydration.path("windows").get(0).path("row_count").asInt()).isEqualTo(3);
        assertThat(hydration.path("materialized_rows").asInt()).isEqualTo(16);
        assertThat(hydration.path("logical_reference_rows").asInt()).isEqualTo(42);

        ObjectNode splitOptions = MAPPER.createObjectNode().set("bars", bars);
        splitOptions.put("partition_ms", 180_000);
        ObjectNode split = OpportunityV5.makeContentAddressedPartitionsV5(splitOptions);
        ObjectNode bounded = hydrate(envelope, split.path("partitions"));
        bounded.put("maxResidentBytes", 500).put("maxTotalBytes", 100_000);
        ObjectNode boundedResult = OpportunityV5.hydrateOpportunityEnvelopeV5(bounded);
        assertThat(boundedResult.path("peak_resident_bytes").asLong()).isLessThanOrEqualTo(500);

        ObjectNode warmOptions = envelopeOptions.deepCopy();
        warmOptions.put("preentry_warmup_bars", 2);
        ObjectNode warmEnvelope = OpportunityV5.makeOpportunityEnvelopeV5(warmOptions);
        String unusedBody = "not-json\n";
        ObjectNode unused = MAPPER.createObjectNode().put("sha256", OpportunityV5.hash(unusedBody))
                .put("bytes", unusedBody.getBytes(StandardCharsets.UTF_8).length).put("row_count", 1)
                .put("body", unusedBody).put("min_event_time", time(1000)).put("max_event_time", time(1000));
        ArrayNode withUnused = (ArrayNode) daily.path("partitions").deepCopy();
        withUnused.add(unused);
        ObjectNode warm = OpportunityV5.hydrateOpportunityEnvelopeV5(hydrate(warmEnvelope, withUnused));
        assertThat(warm.path("windows").get(0).path("preentry_warmup_bars").asInt()).isEqualTo(2);
        assertThat(warm.path("windows").get(0).path("preentry_partition_refs")).hasSize(1);
    }

    @Test
    void eligibilityNotAndLiteralMembershipPreserveConservativeSupersetSemantics() {
        ObjectNode ineligible = feature(9, .5).put("signal_eligible", false).put("funding_signal", .5);
        ObjectNode onlyIneligible = predicateOptions("funding_signal", "GTE", 0, ineligible);
        onlyIneligible.putObject("geneSpace").putArray("genes").addObject()
                .put("name", "floor").put("type", "continuous").put("min", 0).put("max", 1);
        ObjectNode genePredicate = MAPPER.createObjectNode().put("predictor_id", "funding_signal").put("op", "GTE");
        genePredicate.putObject("value").put("$gene", "floor");
        onlyIneligible.set("predicate", genePredicate);
        assertThatThrownBy(() -> OpportunityV5.makeOpportunityEnvelopeV5(onlyIneligible))
                .hasMessageContaining("no physical decision boundaries");

        ObjectNode eligible = feature(10, .5).put("signal_eligible", true).put("funding_signal", .5);
        ObjectNode filtered = onlyIneligible.deepCopy();
        filtered.withArray("featureRows").add(eligible);
        ObjectNode not = MAPPER.createObjectNode();
        not.putObject("not").put("predictor_id", "funding_signal").put("op", "GTE").put("value", 1);
        filtered.putArray("candidates").addObject().put("candidate_id", "not-null")
                .putObject("definition").set("predicate", not);
        ObjectNode filteredEnvelope = OpportunityV5.makeOpportunityEnvelopeV5(filtered);
        assertThat(filteredEnvelope.path("windows")).hasSize(1);
        assertThat(Instant.parse(filteredEnvelope.path("windows").get(0).path("decision_time").asText()))
                .isEqualTo(Instant.ofEpochMilli(START + 10 * 60_000L));

        ObjectNode numeric = predicateOptions("score", "IN", null, feature(2, 2));
        ((ObjectNode) numeric.path("predicate")).set("value", MAPPER.createArrayNode().add(1).add(2));
        assertThat(OpportunityV5.makeOpportunityEnvelopeV5(numeric).path("windows")).hasSize(1);
        ObjectNode bool = predicateOptions("armed", "IN", null, feature(0, 1).put("armed", false));
        ((ObjectNode) bool.path("predicate")).set("value", MAPPER.createArrayNode().add(false));
        assertThat(OpportunityV5.makeOpportunityEnvelopeV5(bool).path("windows")).hasSize(1);

        ObjectNode categorical = predicateOptions("regime", "EQ", null, feature(1, 1).put("regime", "trend"));
        categorical.putObject("geneSpace").putArray("genes").addObject().put("name", "regime_gene")
                .put("type", "categorical").putArray("values").add("risk").add("trend");
        ObjectNode categoricalPredicate = MAPPER.createObjectNode().put("predictor_id", "regime").put("op", "EQ");
        categoricalPredicate.putObject("value").put("$gene", "regime_gene");
        categorical.set("predicate", categoricalPredicate);
        assertThat(OpportunityV5.makeOpportunityEnvelopeV5(categorical).path("windows")).hasSize(1);
    }

    @Test
    void candidateBranchesCannotCreateRowsOutsideFrozenEnvelope() {
        ArrayNode features = MAPPER.createArrayNode();
        for (int minute = 0; minute < 24; minute++) features.add(feature(minute, minute));
        ObjectNode escape = MAPPER.createObjectNode().put("fixtureOnly", true).put("max_lifecycle_ms", 180_000);
        escape.set("featureRows", features);
        escape.putObject("geneSpace").putArray("genes").addObject()
                .put("name", "threshold").put("type", "continuous").put("min", 10).put("max", 20);
        ObjectNode frozen = MAPPER.createObjectNode().put("predictor_id", "score").put("op", "GTE");
        frozen.putObject("value").put("$gene", "threshold");
        escape.set("predicate", frozen);
        escape.putArray("candidates").addObject().putObject("definition")
                .putObject("signal_rule").put("predictor_id", "score").put("op", "LT").put("value", 15);
        assertThatThrownBy(() -> OpportunityV5.makeOpportunityEnvelopeV5(escape))
                .hasMessage("candidate intent is not a subset of the frozen opportunity predicate");

        ObjectNode exact = escape.deepCopy();
        exact.remove("candidates");
        exact.putArray("candidates").addObject().putObject("definition")
                .putObject("signal_rule").put("predictor_id", "score").put("op", "GTE").put("value", 15);
        assertThatCode(() -> OpportunityV5.makeOpportunityEnvelopeV5(exact)).doesNotThrowAnyException();
    }

    @Test
    void incompleteRightEdgeNeedsAnExplicitExpiryBeforeItBecomesEligible() {
        ObjectNode envelope = OpportunityV5.makeOpportunityEnvelopeV5(
                fixtureEnvelopeAt(3, START + 10 * 60_000L));
        ObjectNode partitionSet = OpportunityV5.makeContentAddressedPartitionsV5(
                MAPPER.createObjectNode().set("bars", bars(10, 2)));
        ObjectNode unresolved = OpportunityV5.hydrateOpportunityEnvelopeV5(
                hydrate(envelope, partitionSet.path("partitions")));
        assertThat(unresolved.path("windows").get(0).path("lifecycle_status").asText())
                .isEqualTo("UNRESOLVED_RIGHT_EDGE");
        assertThat(unresolved.path("windows").get(0).path("eligible").asBoolean()).isFalse();

        ObjectNode declared = hydrate(envelope, partitionSet.path("partitions"));
        declared.putArray("expiryTerminals").addObject()
                .put("window_id", envelope.path("windows").get(0).path("window_id").asText())
                .put("terminal_time", time(11));
        ObjectNode complete = OpportunityV5.hydrateOpportunityEnvelopeV5(declared);
        assertThat(complete.path("windows").get(0).path("lifecycle_status").asText()).isEqualTo("COMPLETE");
        ObjectNode read = MAPPER.createObjectNode().set("hydration", complete);
        read.set("partitions", partitionSet.path("partitions"));
        read.put("window_id", envelope.path("windows").get(0).path("window_id").asText());
        assertThat(OpportunityV5.readHydratedRangeV5(read).path("row_count").asInt()).isEqualTo(2);
    }

    private static ObjectNode predicateOptions(
            String predictor, String operator, Integer literal, ObjectNode row) {
        ObjectNode options = MAPPER.createObjectNode().put("fixtureOnly", true).put("max_lifecycle_ms", 180_000);
        options.putObject("geneSpace").putArray("genes");
        ObjectNode predicate = options.putObject("predicate").put("predictor_id", predictor).put("op", operator);
        if (literal != null) predicate.put("value", literal);
        options.putArray("featureRows").add(row);
        return options;
    }

    private static ObjectNode fixtureEnvelopeAt(int lifeMinutes, long timestamp) {
        ObjectNode options = MAPPER.createObjectNode().put("fixtureOnly", true)
                .put("max_lifecycle_ms", lifeMinutes * 60_000L);
        options.putObject("geneSpace").putArray("genes");
        options.putObject("predicate").put("predictor_id", "score").put("op", "GTE").put("value", 1);
        options.putArray("featureRows").add(feature((int) ((timestamp - START) / 60_000L), 10));
        return options;
    }

    private static ObjectNode feature(int minute, double score) {
        return MAPPER.createObjectNode().put("asset", "btc").put("instrument", "BINANCE_SPOT")
                .put("symbol", "BTCUSDT").put("decision_time", time(minute)).put("availability_time", time(minute))
                .put("score", score);
    }

    private static ArrayNode bars(int firstMinute, int count) {
        ArrayNode bars = MAPPER.createArrayNode();
        for (int index = 0; index < count; index++) {
            bars.addObject().put("event_time", time(firstMinute + index))
                    .put("open", 1).put("high", 1).put("low", 1).put("close", 1);
        }
        return bars;
    }

    private static ObjectNode hydrate(ObjectNode envelope, JsonNode partitions) {
        ObjectNode options = MAPPER.createObjectNode().set("envelope", envelope);
        options.set("partitions", partitions);
        return options;
    }

    private static String time(int minute) {
        return Instant.ofEpochMilli(START + minute * 60_000L).toString();
    }
}
