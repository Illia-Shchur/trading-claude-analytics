package com.tradinganalytics.core.lib;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolchainSupportTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesReportIdentityAndRejectsNonReportsAndImpossibleDates() {
        assertThat(ToolchainSupport.reportFileMeta("eth_flying_rocket_20260728_0540.md"))
                .isEqualTo(JSON.valueToTree(java.util.Map.of(
                        "ok", true, "file", "eth_flying_rocket_20260728_0540.md", "asset", "ETH",
                        "framework", "flying_rocket", "date", "2026-07-28", "local_time", "05:40",
                        "zone", "America/New_York", "at_utc", "2026-07-28T09:40:00Z",
                        "schema_epoch", "discretion_and_two_channel")));
        assertThat(ToolchainSupport.reportFileMeta("calibration_ledger.md").path("ok").asBoolean()).isFalse();
        assertThat(ToolchainSupport.reportFileMeta("btc_fallen_knives_20260230_1030.md").path("ok").asBoolean()).isFalse();
        assertThat(ToolchainSupport.reportFileMeta("btc_fallen_knives_20260711_2599.md").path("ok").asBoolean()).isFalse();
    }

    @Test
    void convertsNewYorkDatesWithDstAndAssignsEpochs() {
        assertThat(ToolchainSupport.localToUtcISO("2026-07-11", "10:30")).isEqualTo("2026-07-11T14:30:00Z");
        assertThat(ToolchainSupport.localToUtcISO("2026-01-15", "10:30")).isEqualTo("2026-01-15T15:30:00Z");
        assertThat(ToolchainSupport.localToUtcISO("2026-07-11", "1030")).isNull();
        assertThat(ToolchainSupport.schemaEpochOf("2026-07-10")).isEqualTo("pre_machine_block");
        assertThat(ToolchainSupport.schemaEpochOf("2026-07-11")).isEqualTo("machine_block");
        assertThat(ToolchainSupport.schemaEpochOf("2026-07-27")).isEqualTo("discretion_and_two_channel");
    }

    @Test
    void exposesChannelAwareRubricsInferenceAndUnlocks() throws Exception {
        assertThat(ToolchainSupport.signalRubric("fallen_knives", null)).isEqualTo("FK/1");
        assertThat(ToolchainSupport.signalRubric("flying_rocket", "B")).isEqualTo("FR-B/1");
        assertThat(ToolchainSupport.legSpec("FR-B/1")).hasSize(5);
        assertThat(ToolchainSupport.legSpec("nope")).isEmpty();
        assertThat(ToolchainSupport.inferChannel("flying_rocket", null, "2026-07-14").path("channel").asText())
                .isEqualTo("A");
        assertThat(ToolchainSupport.inferChannel("flying_rocket", null, "2026-07-28").path("channel").isNull())
                .isTrue();
        var inferred = ToolchainSupport.inferDiscretion(
                JSON.readTree("{\"raw\":12,\"adjusted\":12}"), "2026-07-14");
        assertThat(inferred.path("mechanical").asInt()).isEqualTo(12);
        assertThat(inferred.path("discretionary").asInt()).isZero();
        assertThat(ToolchainSupport.unlockFor("flying_rocket", "B", 17.0, 17.0)
                .path("highest_phase_unlocked_by_score").asText()).isEqualTo("p2");
        assertThat(ToolchainSupport.unlockFor("fallen_knives", null, 17.0, 15.0)
                .path("highest_phase_unlocked_by_score").asText()).isEqualTo("p2");
    }

    @Test
    void packsGateBitsAndIgnoresInvalidValues() throws Exception {
        assertThat(ToolchainSupport.gateMask(JSON.readTree("[1,2,3,4,6,7,8]"))).isEqualTo(239);
        assertThat(ToolchainSupport.gateMask(JSON.readTree("[0,1,10,\"x\"]"))).isEqualTo(1);
        assertThat(ToolchainSupport.gateMask(JSON.readTree("[]"))).isZero();
    }

    @Test
    void feedComparisonAndSnapshotDigestStripOnlyTheirDeclaredVolatileFields() throws Exception {
        var first = JSON.readTree("{\"schema\":\"feed/1\",\"generated_at\":\"a\",\"rows\":[1]}");
        var rerun = JSON.readTree("{\"schema\":\"feed/1\",\"generated_at\":\"b\",\"rows\":[1]}");
        var changed = JSON.readTree("{\"schema\":\"feed/1\",\"generated_at\":\"b\",\"rows\":[2]}");
        assertThat(ToolchainSupport.feedChanged(ToolchainSupport.canonicalJSON(first), rerun).path("changed").asBoolean())
                .isFalse();
        assertThat(ToolchainSupport.feedChanged(ToolchainSupport.canonicalJSON(first), changed).path("changed").asBoolean())
                .isTrue();
        assertThat(ToolchainSupport.feedChanged(null, first).path("reason").asText()).isEqualTo("no existing feed");
        assertThat(ToolchainSupport.feedChanged("{not json", first).path("reason").asText())
                .isEqualTo("existing feed is not valid JSON");

        var left = JSON.readTree("{\"btc\":{\"fetched_at\":\"a\",\"errors\":[\"timeout\"],\"spot\":1}}");
        var right = JSON.readTree("{\"btc\":{\"fetched_at\":\"b\",\"errors\":[],\"spot\":1}}");
        assertThat(ToolchainSupport.snapshotDigestPayload(left)).isEqualTo(ToolchainSupport.snapshotDigestPayload(right));
    }

    @Test
    void implementsEquityAndCryptoTradingDayCalendars() {
        assertThat(ToolchainSupport.weekdayOf("2026-01-19")).isEqualTo("Monday");
        assertThat(ToolchainSupport.isTradingDay("2026-07-03", "equity")).isFalse();
        assertThat(ToolchainSupport.isTradingDay("2026-07-03", "crypto")).isTrue();
        assertThat(ToolchainSupport.nextNTradingDays("2026-12-30", 3, "equity"))
                .containsExactly("2026-12-31", "2027-01-04", "2027-01-05");
        assertThat(ToolchainSupport.nextNTradingDays("2026-07-02", 3, "crypto"))
                .isEqualTo(List.of("2026-07-03", "2026-07-04", "2026-07-05"));
        assertThat(ToolchainSupport.tradingDaysBetween("2026-07-01", "2026-07-06", "equity")).isEqualTo(1);
        assertThat(ToolchainSupport.tradingDaysBetween("2026-07-10", "2026-07-01", "equity")).isZero();
    }
}
