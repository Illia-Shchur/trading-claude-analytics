package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Deterministic public-API coverage for funding cadence and mark custody. */
final class StrategyResearchDataV5FundingBoundaryMatrixTest {
    private static final String H = "a".repeat(64);
    private static final long START = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long HOUR = 3_600_000L;
    private static final long EIGHT_HOURS = 8 * HOUR;

    @Test
    void cadenceDiscoverySegmentsObservedGapsAndRejectsInvalidBounds() {
        ObjectNode empty = object();
        empty.set("rows", array());
        assertThat(StrategyResearchDataV5.discoverFundingCadenceSegments(empty)).isEmpty();

        ObjectNode single = object();
        single.set("rows", array().add(funding(START, "one", .001)));
        ArrayNode oneSegment = StrategyResearchDataV5.discoverFundingCadenceSegments(single);
        assertThat(oneSegment).hasSize(1);
        assertThat(oneSegment.get(0).path("cadence_ms").asLong()).isEqualTo(EIGHT_HOURS);
        assertThat(oneSegment.get(0).path("discovery").asText()).isEqualTo("OBSERVED_EVENT_GAPS");

        ObjectNode mixed = object();
        mixed.set("rows", array().add(funding(START, "a", .001))
                .add(funding(START + EIGHT_HOURS, "b", .001))
                .add(funding(START + EIGHT_HOURS + 2 * HOUR, "c", .001)));
        mixed.put("startAt", iso(START)).put("endAt", iso(START + 12 * HOUR));
        ArrayNode segments = StrategyResearchDataV5.discoverFundingCadenceSegments(mixed);
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).path("cadence_ms").asLong()).isEqualTo(EIGHT_HOURS);
        assertThat(segments.get(1).path("cadence_ms").asLong()).isEqualTo(2 * HOUR);

        ObjectNode badBounds = object();
        badBounds.set("rows", array().add(funding(START, "one", .001)));
        badBounds.put("startAt", iso(START + HOUR)).put("endAt", iso(START));
        expectCadence(badBounds, "funding cadence discovery bounds are invalid");

        ObjectNode badGap = object();
        badGap.set("rows", array().add(funding(START, "a", .001))
                .add(funding(START + 5 * HOUR, "b", .001)));
        expectCadence(badGap, "unsupported funding cadence gap");
    }

    @Test
    void fixedCadenceCanonicalizationBindsSlotsAndCoverage() {
        ObjectNode complete = canonicalRequest(false);
        complete.set("rows", array().add(funding(START, "a", .001))
                .add(funding(START + EIGHT_HOURS, "b", -.002))
                .add(funding(START + 2 * EIGHT_HOURS, "c", .003)));
        ObjectNode result = StrategyResearchDataV5.canonicalizeFundingRows(complete);
        assertThat(result.path("coverage").path("complete").asBoolean()).isTrue();
        assertThat(result.path("rows")).hasSize(3);
        assertThat(result.path("rows").get(1).path("settlement_slot").asText()).isEqualTo(iso(START + EIGHT_HOURS));
        assertThat(result.path("rows").get(2).path("availability_time").asLong()).isEqualTo(START + 2 * EIGHT_HOURS);

        ObjectNode missing = canonicalRequest(false);
        missing.set("rows", array().add(funding(START, "a", .001))
                .add(funding(START + EIGHT_HOURS, "b", .001)));
        ObjectNode incomplete = StrategyResearchDataV5.canonicalizeFundingRows(missing);
        assertThat(incomplete.path("coverage").path("complete").asBoolean()).isFalse();
        assertThat(incomplete.path("coverage").path("missing_slots")).hasSize(1);

        ObjectNode nearDuplicate = canonicalRequest(false);
        nearDuplicate.set("rows", array().add(funding(START, "a", .001))
                .add(funding(START + 30_000, "b", .001))
                .add(funding(START + EIGHT_HOURS, "c", .001))
                .add(funding(START + 2 * EIGHT_HOURS, "d", .001)));
        expect(nearDuplicate, "multiple funding events map to settlement slot");

        ObjectNode outsideTolerance = canonicalRequest(false);
        outsideTolerance.set("rows", array().add(funding(START + 120_000, "a", .001)));
        expect(outsideTolerance, "exceeds settlement-slot tolerance");

        ObjectNode duplicateIdentity = canonicalRequest(false);
        duplicateIdentity.set("rows", array().add(funding(START, "same", .001))
                .add(funding(START + EIGHT_HOURS, "same", .001)));
        expect(duplicateIdentity, "funding event identity is missing or duplicated");

        ObjectNode badRate = canonicalRequest(false);
        badRate.set("rows", array().add(funding(START, "bad", Double.NaN)));
        expect(badRate, "has no finite rate");
    }

    @Test
    void eventSequenceCanonicalizationTracksBoundariesAndSourceCompleteness() {
        ObjectNode complete = canonicalRequest(true);
        complete.set("rows", array().add(funding(START, "a", .001))
                .add(funding(START + EIGHT_HOURS, "b", .001))
                .add(funding(START + 2 * EIGHT_HOURS, "c", .001)));
        ObjectNode result = StrategyResearchDataV5.canonicalizeFundingRows(complete);
        assertThat(result.path("coverage").path("coverage_mode").asText()).isEqualTo("EVENT_SEQUENCE");
        assertThat(result.path("coverage").path("complete").asBoolean()).isTrue();
        assertThat(result.path("coverage").path("boundaries_covered").asBoolean()).isTrue();
        assertThat(result.path("coverage").path("first_event_time").asText()).isEqualTo(iso(START));
        assertThat(result.path("coverage").path("last_event_time").asText()).isEqualTo(iso(START + 2 * EIGHT_HOURS));

        ObjectNode sourceRequired = canonicalRequest(true);
        sourceRequired.path("series");
        ((ObjectNode) sourceRequired.path("series")).put("require_source_coverage", true).put("source_coverage_complete", false);
        sourceRequired.set("rows", array().add(funding(START, "a", .001))
                .add(funding(START + EIGHT_HOURS, "b", .001))
                .add(funding(START + 2 * EIGHT_HOURS, "c", .001)));
        ObjectNode sourceIncomplete = StrategyResearchDataV5.canonicalizeFundingRows(sourceRequired);
        assertThat(sourceIncomplete.path("coverage").path("complete").asBoolean()).isFalse();
        assertThat(sourceIncomplete.path("coverage").path("missing_slots").get(0).asText()).contains("BOUNDARY_OR_PAGINATION");

        ObjectNode duplicate = canonicalRequest(true);
        duplicate.set("rows", array().add(funding(START, "same", .001))
                .add(funding(START + EIGHT_HOURS, "same", .001)));
        expect(duplicate, "funding event identity is missing or duplicated");

        ObjectNode outside = canonicalRequest(true);
        outside.set("rows", array().add(funding(START - EIGHT_HOURS, "before", .001))
                .add(funding(START, "a", .001)).add(funding(START + EIGHT_HOURS, "b", .001)));
        ObjectNode outsideResult = StrategyResearchDataV5.canonicalizeFundingRows(outside);
        assertThat(outsideResult.path("rows")).hasSize(2);
    }

    @Test
    void settlementMarksRequireExactPITIdentityAndRetainedResponses() {
        ObjectNode request = object();
        request.set("fundingRows", array().add(funding(START + EIGHT_HOURS, "funding", .001)));
        request.set("markRows", array().add(mark(START + EIGHT_HOURS, 101)));
        request.set("markResponseSha256", array().add(H));
        ArrayNode bound = StrategyResearchDataV5.bindFundingSettlementMarks(request);
        assertThat(bound).hasSize(1);
        assertThat(bound.get(0).path("settlement_mark").asDouble()).isEqualTo(101);
        assertThat(bound.get(0).path("settlement_mark_source").asText()).isEqualTo("BINANCE_MARK_PRICE_KLINE_OPEN_AT_SETTLEMENT");
        assertThat(bound.get(0).path("settlement_mark_availability_time").asText()).isEqualTo(iso(START + EIGHT_HOURS));

        ObjectNode canonical = object();
        canonical.set("fundingRows", array().add(funding(START, "a", .001)).add(funding(START + EIGHT_HOURS, "b", .001)));
        canonical.set("markRows", array().add(mark(START, 100)).add(mark(START + EIGHT_HOURS, 101)));
        canonical.set("markResponseSha256", array().add(H));
        canonical.set("series", canonicalRequest(false).path("series").deepCopy());
        ArrayNode canonicalBound = StrategyResearchDataV5.bindFundingSettlementMarks(canonical);
        assertThat(canonicalBound).hasSize(2);

        ObjectNode noInventory = request.deepCopy();
        noInventory.set("markResponseSha256", array());
        expectMarks(noInventory, "mark source has no physically retained response SHA");

        ObjectNode missing = request.deepCopy();
        missing.set("markRows", array());
        expectMarks(missing, "mark source is missing exact event");

        ObjectNode duplicate = request.deepCopy();
        duplicate.set("markRows", array().add(mark(START + EIGHT_HOURS, 101)).add(mark(START + EIGHT_HOURS, 102)));
        expectMarks(duplicate, "duplicate event identity");

        ObjectNode early = request.deepCopy();
        ((ObjectNode) early.path("markRows").get(0)).put("availability_time", START + EIGHT_HOURS - 1);
        expectMarks(early, "availability is not exact");

        ObjectNode unretained = request.deepCopy();
        ((ObjectNode) unretained.path("markRows").get(0)).put("response_sha256", "b".repeat(64));
        expectMarks(unretained, "response SHA is not physically retained");

        ObjectNode nonpositive = request.deepCopy();
        ((ObjectNode) nonpositive.path("markRows").get(0)).put("mark_open", 0);
        expectMarks(nonpositive, "no exact positive mark");
    }

    @Test
    void fundingRequestBoundsAndPnlEnforceFinitePositiveTerms() {
        ObjectNode series = canonicalRequest(false).path("series").deepCopy();
        series.put("availability_cutoff_at", iso(START + 3 * EIGHT_HOURS));
        ObjectNode bounds = StrategyResearchDataV5.fundingRequestBounds(series);
        assertThat(bounds.path("startTime").asLong()).isEqualTo(START - 60_000);
        assertThat(bounds.path("endTime").asLong()).isEqualTo(START + 2 * EIGHT_HOURS + 60_000);
        assertThat(bounds.path("slot_tolerance_ms").asLong()).isEqualTo(60_000);

        assertThat(StrategyResearchDataV5.computeFundingPnl(object().put("fundingRate", .01)
                .put("settlementMark", 101).put("signedQuantity", -2).put("contractMultiplier", 1))).isEqualTo(2.02);
        assertThat(StrategyResearchDataV5.computeFundingPnl(object().put("fundingRate", .01)
                .put("settlementMark", 101).put("signedQuantity", -2).put("contractMultiplier", 1).put("quoteMultiplier", 10))).isEqualTo(20.2);

        ObjectNode badSeries = series.deepCopy();
        badSeries.put("availability_cutoff_at", iso(START + EIGHT_HOURS));
        expectBounds(badSeries, "funding request bounds are invalid");
        ObjectNode wrongSeries = series.deepCopy().put("series_type", "signal_bars");
        expectBounds(wrongSeries, "funding request bounds require a funding series");

        ObjectNode invalid = object().put("fundingRate", .01).put("settlementMark", 0)
                .put("signedQuantity", 1).put("contractMultiplier", 1);
        assertThatThrownBy(() -> StrategyResearchDataV5.computeFundingPnl(invalid))
                .hasMessageContaining("funding PnL requires finite rate/mark/position/contract terms");
        ObjectNode badQuote = object().put("fundingRate", .01).put("settlementMark", 100)
                .put("signedQuantity", 1).put("contractMultiplier", 1).put("quoteMultiplier", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.computeFundingPnl(badQuote))
                .hasMessageContaining("funding PnL requires finite rate/mark/position/contract terms");
    }

    private static ObjectNode canonicalRequest(boolean eventSequence) {
        ObjectNode series = object().put("series_type", "funding_events").put("start_at", iso(START))
                .put("end_at", iso(START + 2 * EIGHT_HOURS)).put("slot_tolerance_ms", 60_000)
                .put("event_sequence_mode", eventSequence);
        series.set("cadence_segments", array().add(object().put("effective_from", iso(START))
                .put("effective_to", iso(START + 2 * EIGHT_HOURS)).put("cadence_ms", EIGHT_HOURS).put("origin_at", iso(START))));
        ObjectNode request = object(); request.set("series", series); request.set("rows", array()); return request;
    }

    private static ObjectNode funding(long event, String id, double rate) {
        return object().put("raw_event_time", event).put("event_id", id).put("funding_rate", rate);
    }

    private static ObjectNode mark(long event, double price) {
        return object().put("event_time", event).put("availability_time", event)
                .put("mark_open", price).put("response_sha256", H);
    }

    private static void expect(ObjectNode request, String message) {
        assertThatThrownBy(() -> StrategyResearchDataV5.canonicalizeFundingRows(request))
                .hasMessageContaining(message);
    }

    private static void expectCadence(ObjectNode request, String message) {
        assertThatThrownBy(() -> StrategyResearchDataV5.discoverFundingCadenceSegments(request))
                .hasMessageContaining(message);
    }

    private static void expectMarks(ObjectNode request, String message) {
        assertThatThrownBy(() -> StrategyResearchDataV5.bindFundingSettlementMarks(request))
                .hasMessageContaining(message);
    }

    private static void expectBounds(ObjectNode series, String message) {
        assertThatThrownBy(() -> StrategyResearchDataV5.fundingRequestBounds(series))
                .hasMessageContaining(message);
    }

    private static String iso(long epochMillis) { return Instant.ofEpochMilli(epochMillis).toString().replace("Z", ".000Z"); }
    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
}
