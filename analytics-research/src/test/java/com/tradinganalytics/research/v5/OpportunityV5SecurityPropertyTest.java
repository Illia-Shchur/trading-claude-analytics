package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OpportunityV5SecurityPropertyTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @TempDir Path temporary;

    @Test
    void publicApiContainsEveryJavaScriptFunctionAndAlias() {
        Set<String> publicMethods = Arrays.stream(OpportunityV5.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertThat(publicMethods).contains(
                "hash", "makeOpportunityDomainV5", "validateOpportunityDomainV5",
                "makeOpportunityEnvelopeV5", "buildOpportunityEnvelopeV5", "validateOpportunityEnvelopeV5",
                "assertCandidateIntentSubsetV5", "proveCandidateSubsetV5", "makeContentAddressedPartitionsV5",
                "normalizeExecutionPartitionsV5", "hydrateOpportunityEnvelopeV5", "buildOpportunityHydrationV5",
                "hydrateExecutionEnvelopeV5", "readHydratedRangeV5", "lazyReadHydratedRangeV5",
                "readExecutionRangeV5");
        assertThat(OpportunityV5.OPPORTUNITY_SCHEMA).isEqualTo("strategy-v5-opportunity-envelope/2");
        assertThat(OpportunityV5.HYDRATION_SCHEMA).isEqualTo("strategy-v5-opportunity-hydration/2");
        assertThat(OpportunityV5.OPPORTUNITY_DOMAIN_SCHEMA).isEqualTo("strategy-v5-opportunity-domain/1");
    }

    @Test
    void labelsAndOutcomeAliasesAreRejectedAtEveryNestedDepth() {
        for (String key : Set.of("label", "future_return", "trade_pnl", "settled_value", "exit_time")) {
            ObjectNode options = fixtureEnvelopeOptions(2, 1, 0);
            ((ObjectNode) options.path("featureRows").get(0)).putObject("nested").put(key, 1);
            assertThatThrownBy(() -> OpportunityV5.makeOpportunityEnvelopeV5(options))
                    .hasMessageContaining("cannot depend on label/outcome");
        }
    }

    @Test
    void malformedGeneDomainsAndCandidateEscapesFailClosed() {
        ObjectNode undeclared = fixtureEnvelopeOptions(2, 1, 0);
        ObjectNode predicate = MAPPER.createObjectNode().put("predictor_id", "score").put("op", "GTE");
        predicate.putObject("value").put("$gene", "missing");
        undeclared.set("predicate", predicate);
        assertThatThrownBy(() -> OpportunityV5.makeOpportunityEnvelopeV5(undeclared))
                .hasMessage("opportunity predicate references undeclared gene missing");

        ObjectNode membership = fixtureEnvelopeOptions(2, 1, 0);
        membership.putObject("geneSpace").putArray("genes").addObject()
                .put("name", "threshold").putArray("values").add(1).add(2);
        ObjectNode in = MAPPER.createObjectNode().put("predictor_id", "score").put("op", "IN");
        in.putObject("value").put("$gene", "threshold");
        membership.set("predicate", in);
        assertThatThrownBy(() -> OpportunityV5.makeOpportunityEnvelopeV5(membership))
                .hasMessage("gene-controlled IN predicates are unsupported; freeze an explicit literal membership set");

        ObjectNode envelope = OpportunityV5.makeOpportunityEnvelopeV5(fixtureEnvelopeOptions(2, 1, 0));
        ObjectNode subset = MAPPER.createObjectNode().set("envelope", envelope);
        subset.set("intent", featureRow(Instant.parse("2026-01-01T00:09:00Z").toEpochMilli(), 10));
        assertThatThrownBy(() -> OpportunityV5.assertCandidateIntentSubsetV5(subset))
                .hasMessage("candidate intent is outside frozen opportunity superset");
    }

    @Test
    void everyDeclaredHydrationBoundIsEnforcedBeforeUnboundedWork() {
        ObjectNode envelope = OpportunityV5.makeOpportunityEnvelopeV5(fixtureEnvelopeOptions(3, 1, 0));
        ObjectNode partitionSet = OpportunityV5.makeContentAddressedPartitionsV5(
                MAPPER.createObjectNode().set("bars", bars(3, 1, 100)));
        long bytes = partitionSet.path("partitions").get(0).path("bytes").asLong();

        ObjectNode resident = hydrationOptions(envelope, partitionSet);
        resident.put("maxResidentBytes", bytes - 1);
        assertThatThrownBy(() -> OpportunityV5.hydrateOpportunityEnvelopeV5(resident))
                .hasMessage("physical partition exceeds resident memory ceiling");

        ObjectNode aggregate = hydrationOptions(envelope, partitionSet);
        aggregate.put("maxTotalBytes", bytes - 1);
        assertThatThrownBy(() -> OpportunityV5.hydrateOpportunityEnvelopeV5(aggregate))
                .hasMessage("hydration exceeds bounded aggregate partition bytes");

        ObjectNode declaredRows = hydrationOptions(envelope, partitionSet);
        declaredRows.put("maxRows", 2);
        assertThatThrownBy(() -> OpportunityV5.hydrateOpportunityEnvelopeV5(declaredRows))
                .hasMessage("hydration exceeds bounded declared physical rows");

        ObjectNode uniqueRows = hydrationOptions(envelope, partitionSet);
        uniqueRows.put("maxUniqueRows", 2);
        assertThatThrownBy(() -> OpportunityV5.hydrateOpportunityEnvelopeV5(uniqueRows))
                .hasMessage("hydration exceeds bounded unique physical row count");

        ObjectNode metadata = hydrationOptions(envelope, partitionSet);
        metadata.put("maxIndexedPartitions", 0);
        assertThatThrownBy(() -> OpportunityV5.hydrateOpportunityEnvelopeV5(metadata))
                .hasMessage("hydration resident/aggregate/index byte and row bounds must be positive");
    }

    @Test
    void physicalPartitionMetadataAndBytesCannotRedirectTrust() throws Exception {
        ObjectNode envelope = OpportunityV5.makeOpportunityEnvelopeV5(fixtureEnvelopeOptions(2, 1, 0));
        ObjectNode partitionSet = OpportunityV5.makeContentAddressedPartitionsV5(
                MAPPER.createObjectNode().set("bars", bars(2, 1, 100)));
        ArrayNode partitions = (ArrayNode) partitionSet.path("partitions").deepCopy();

        ((ObjectNode) partitions.get(0)).put("sha256", "0".repeat(64));
        ObjectNode hydration = MAPPER.createObjectNode().set("envelope", envelope);
        hydration.set("partitions", partitions);
        assertThatThrownBy(() -> OpportunityV5.hydrateOpportunityEnvelopeV5(hydration))
                .hasMessage("physical partition SHA mismatch " + "0".repeat(64));

        Path oversized = temporary.resolve("oversized.jsonl");
        Files.writeString(oversized, "{}\n".repeat(100));
        ObjectNode physical = (ObjectNode) partitionSet.path("partitions").get(0).deepCopy();
        physical.remove("body");
        physical.put("path", oversized.toString()).put("bytes", Files.size(oversized));
        ObjectNode bounded = MAPPER.createObjectNode().set("envelope", envelope);
        bounded.putArray("partitions").add(physical);
        bounded.put("maxPartitionBytes", Files.size(oversized) - 1);
        assertThatThrownBy(() -> OpportunityV5.hydrateOpportunityEnvelopeV5(bounded))
                .hasMessage("physical partition exceeds bounded byte ceiling");

        String malformedBody = "not-json\n";
        long decision = Instant.parse("2026-01-01T00:01:00Z").toEpochMilli();
        ObjectNode malformed = MAPPER.createObjectNode().put("sha256", OpportunityV5.hash(malformedBody))
                .put("bytes", malformedBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .put("row_count", 1).put("body", malformedBody)
                .put("min_event_time", decision).put("max_event_time", decision);
        ObjectNode malformedOptions = MAPPER.createObjectNode().set("envelope", envelope);
        malformedOptions.putArray("partitions").add(malformed);
        assertThatThrownBy(() -> OpportunityV5.hydrateOpportunityEnvelopeV5(malformedOptions))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conflictingOverlapAndInteriorGapAreRejected() {
        ObjectNode envelope = OpportunityV5.makeOpportunityEnvelopeV5(fixtureEnvelopeOptions(3, 1, 0));
        ObjectNode first = OpportunityV5.makeContentAddressedPartitionsV5(
                MAPPER.createObjectNode().set("bars", bars(3, 1, 100)));
        ArrayNode changedBars = bars(3, 1, 100);
        ((ObjectNode) changedBars.get(1)).put("close", 999);
        ObjectNode second = OpportunityV5.makeContentAddressedPartitionsV5(
                MAPPER.createObjectNode().set("bars", changedBars));
        ObjectNode overlap = MAPPER.createObjectNode().set("envelope", envelope);
        ArrayNode all = overlap.putArray("partitions");
        first.path("partitions").forEach(all::add);
        second.path("partitions").forEach(all::add);
        assertThatThrownBy(() -> OpportunityV5.hydrateOpportunityEnvelopeV5(overlap))
                .hasMessage("overlapping physical partitions disagree at a timestamp");

        ArrayNode gapBars = bars(3, 1, 100);
        gapBars.remove(1);
        ObjectNode gapSet = OpportunityV5.makeContentAddressedPartitionsV5(
                MAPPER.createObjectNode().set("bars", gapBars));
        ObjectNode gap = hydrationOptions(envelope, gapSet);
        assertThatThrownBy(() -> OpportunityV5.hydrateOpportunityEnvelopeV5(gap))
                .hasMessage("hydrated execution range has an interior gap or wrong start");
    }

    @Property(tries = 80)
    void densePartitionHydrationRoundTripsWithoutNestedCopies(
            @ForAll @IntRange(min = 1, max = 24) int bars,
            @ForAll @IntRange(min = 1, max = 10_000) int price,
            @ForAll @LongRange(min = 0, max = 10_000) long minuteOffset) {
        long start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() + minuteOffset * 60_000L;
        ObjectNode envelope = OpportunityV5.makeOpportunityEnvelopeV5(fixtureEnvelopeOptions(bars, start, 0));
        ArrayNode source = bars(bars, start, price);
        ObjectNode partitionOptions = MAPPER.createObjectNode().set("bars", source);
        partitionOptions.put("partitionMs", 5 * 60_000L);
        ObjectNode partitionSet = OpportunityV5.makeContentAddressedPartitionsV5(partitionOptions);
        ObjectNode hydration = OpportunityV5.hydrateOpportunityEnvelopeV5(hydrationOptions(envelope, partitionSet));
        assertThat(hydration.path("materialized_rows").asInt()).isEqualTo(bars);
        assertThat(hydration.path("duplicate_nested_child_arrays").asBoolean()).isFalse();

        ObjectNode read = MAPPER.createObjectNode().set("hydration", hydration);
        read.set("partitions", partitionSet.path("partitions"));
        read.put("window_id", envelope.path("windows").get(0).path("window_id").asText());
        ObjectNode roundTrip = OpportunityV5.readHydratedRangeV5(read);
        assertThat(roundTrip.path("row_count").asInt()).isEqualTo(bars);
        ArrayNode flattened = MAPPER.createArrayNode();
        roundTrip.path("batches").forEach(batch -> batch.forEach(flattened::add));
        assertThat(flattened).isEqualTo(source);
    }

    @Property(tries = 100)
    void anyContentHashMutationInvalidatesFrozenEnvelope(
            @ForAll @IntRange(min = 1, max = 20) int score,
            @ForAll @IntRange(min = 1, max = 30) int lifecycleMinutes) {
        ObjectNode envelope = OpportunityV5.makeOpportunityEnvelopeV5(
                fixtureEnvelopeOptions(lifecycleMinutes, 1, 0));
        ((ObjectNode) envelope.path("windows").get(0)).put("source_row_sha256", "f".repeat(64 - score % 2));
        assertThatThrownBy(() -> OpportunityV5.validateOpportunityEnvelopeV5(envelope))
                .hasMessage("opportunity envelope hash is invalid");
    }

    private static ObjectNode fixtureEnvelopeOptions(int lifeMinutes, long startOrScore, int warmup) {
        long start = startOrScore > 1_000_000_000_000L
                ? startOrScore : Instant.parse("2026-01-01T00:01:00Z").toEpochMilli();
        ObjectNode options = MAPPER.createObjectNode().put("fixtureOnly", true)
                .put("max_lifecycle_ms", lifeMinutes * 60_000L).put("execution_interval_ms", 60_000)
                .put("preentry_warmup_bars", warmup);
        options.set("predicate", MAPPER.createObjectNode().put("predictor_id", "score").put("op", "GTE").put("value", 1));
        options.putArray("featureRows").add(featureRow(start, 10));
        return options;
    }

    private static ObjectNode featureRow(long timestamp, int score) {
        return MAPPER.createObjectNode().put("decision_time", timestamp).put("availability_time", timestamp)
                .put("asset", "btc").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                .put("episode_id", "episode-" + timestamp).put("signal_id", "signal-" + timestamp).put("score", score);
    }

    private static ArrayNode bars(int count, long startOrMinute, int base) {
        long start = startOrMinute > 1_000_000_000_000L
                ? startOrMinute : Instant.parse("2026-01-01T00:01:00Z").toEpochMilli();
        ArrayNode rows = MAPPER.createArrayNode();
        for (int index = 0; index < count; index++) {
            rows.addObject().put("event_time", start + index * 60_000L).put("open", base + index)
                    .put("high", base + index + 2).put("low", base + index - 1).put("close", base + index + 1);
        }
        return rows;
    }

    private static ObjectNode hydrationOptions(ObjectNode envelope, ObjectNode partitionSet) {
        ObjectNode options = MAPPER.createObjectNode().set("envelope", envelope);
        options.set("partitions", partitionSet.path("partitions"));
        return options;
    }
}
