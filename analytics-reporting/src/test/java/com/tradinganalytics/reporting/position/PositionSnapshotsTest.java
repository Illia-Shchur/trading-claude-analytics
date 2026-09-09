package com.tradinganalytics.reporting.position;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PositionSnapshotsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NOW = Instant.parse("2026-07-28T12:00:00Z").toEpochMilli();
    private final PositionSnapshots positions = new PositionSnapshots(JSON);

    @Test
    void eventDrivenAndStrictTimeFreshnessMatchTheContract() {
        JsonNode oneHourAgo = JSON.getNodeFactory().textNode("2026-07-28T11:00:00Z");
        JsonNode sevenDaysAgo = JSON.getNodeFactory().textNode("2026-07-21T12:00:00Z");

        JsonNode eventDriven = positions.positionFreshness(oneHourAgo, sevenDaysAgo, NOW);
        assertThat(eventDriven.path("band").asText()).isEqualTo("FRESH");
        assertThat(eventDriven.path("policy").asText()).isEqualTo("EVENT_DRIVEN");
        assertThat(eventDriven.path("holdings_age_min").asLong()).isEqualTo(10_080);
        assertThat(eventDriven.path("stale_after_min").isNull()).isTrue();

        JsonNode strict = positions.positionFreshness(oneHourAgo, sevenDaysAgo, NOW, 30, 4_320);
        assertThat(strict.path("band").asText()).isEqualTo("STALE");
        assertThat(strict.path("policy").asText()).isEqualTo("STRICT_TIME");
        assertThat(strict.path("driver").asText()).isEqualTo("generated_at");

        JsonNode missing = positions.positionFreshness(NullNode.instance, oneHourAgo, NOW);
        assertThat(missing.path("band").asText()).isEqualTo("EXPIRED");
        assertThat(missing.path("driver").asText()).isEqualTo("generated_at");
    }

    @Test
    void structuralCheckRejectsWrongAndTruncatedSnapshots() throws Exception {
        PositionSnapshots.Check scalar = positions.positionSnapshotCheck(JSON.readTree("[]"));
        assertThat(scalar.ok()).isFalse();
        assertThat(scalar.errors()).containsExactly("not a JSON object");

        PositionSnapshots.Check wrong = positions.positionSnapshotCheck(
                JSON.readTree("{\"schema\":\"position-snapshot/2\",\"generated_at\":\"x\"}"));
        assertThat(wrong.ok()).isFalse();
        assertThat(wrong.errors()).anyMatch(message -> message.contains("position-snapshot/2"));
        assertThat(wrong.errors()).contains("missing top-level \"positions\"");
    }

    @Test
    void aliasesAttributionAndFuturesAreProjectedWithoutInventingPositions() throws Exception {
        JsonNode snapshot = baseSnapshot();

        JsonNode gold = positions.positionForAsset(snapshot, "gold");
        assertThat(gold.path("covered").asBoolean()).isTrue();
        assertThat(gold.path("asset").asText()).isEqualTo("PAXG");
        assertThat(gold.path("requested_asset").asText()).isEqualTo("GOLD");
        assertThat(gold.path("alias_note").asText()).contains("PROXY", "Hard Rule 1");
        assertThat(gold.path("position").path("qty").asText()).isEqualTo("1.3293894");

        JsonNode silver = positions.positionForAsset(snapshot, "silver");
        assertThat(silver.path("covered").asBoolean()).isFalse();
        assertThat(silver.path("reason").asText()).isEqualTo("not_tracked");
        assertThat(silver.has("position")).isFalse();

        JsonNode btc = positions.positionForAsset(snapshot, "btc");
        assertThat(btc.path("attribution").path("tags")).containsExactly(JSON.getNodeFactory().textNode("FK-P1A"));
        assertThat(btc.path("attribution").path("untagged_open_deals").asInt()).isOne();
        assertThat(btc.path("performance_by_tag")).hasSize(1);

        JsonNode eth = positions.positionForAsset(snapshot, "eth");
        assertThat(eth.path("covered").asBoolean()).isTrue();
        assertThat(eth.path("position").isNull()).isTrue();
        assertThat(eth.path("custody").path("status").asText()).isEqualTo("NO_POSITION_ROW");
        assertThat(eth.path("basis").path("reliable").isNull()).isTrue();
        assertThat(eth.path("futures_positions").get(0).path("side").asText()).isEqualTo("SHORT");
    }

    @Test
    void custodyBasisAndShortAreIndependentDimensions() throws Exception {
        JsonNode external = JSON.readTree("""
                {"asset":"BTC","qty":"0.00000184","trade_derived_qty":"0.50385839",
                 "qty_reconciliation_status":"EXPLAINED_BY_EXTERNAL_TRANSFER",
                 "off_venue_qty":"0.50385655","custody_adjusted_unrealized_pnl_usd":"4821.30",
                 "basis_reliable":false,"oversold_qty":"0.5112","short_qty":"4","short_avg_price_usd":"70000"}
                """);
        JsonNode projected = positions.positionForAsset(withSinglePosition(external), "btc");
        assertThat(projected.path("custody").path("on_venue").asBoolean()).isFalse();
        assertThat(projected.path("custody").path("off_venue_qty").asText()).isEqualTo("0.50385655");
        assertThat(projected.path("basis").path("reliable").asBoolean()).isFalse();
        assertThat(projected.path("basis").path("note").asText()).contains("UPPER BOUND");
        assertThat(projected.path("short").path("short").asBoolean()).isTrue();
        assertThat(projected.path("short").path("short_qty").asText()).isEqualTo("4");

        JsonNode seed = JSON.readTree("""
                {"asset":"BTC","qty":"0.00000184","trade_derived_qty":"0.50385839",
                 "qty_reconciliation_status":"EXPLAINED_BY_SYNTHETIC_OPENING_BALANCE","short_qty":null}
                """);
        JsonNode seeded = positions.positionForAsset(withSinglePosition(seed), "btc");
        assertThat(seeded.path("custody").path("cost_basis_contaminated").asBoolean()).isTrue();
        assertThat(seeded.path("custody").path("off_venue_qty").isNull()).isTrue();
        assertThat(seeded.path("short").path("short").asBoolean()).isFalse();

        JsonNode unexplained = JSON.readTree("""
                {"asset":"BTC","qty":"1","qty_reconciliation_status":"UNEXPLAINED"}
                """);
        assertThat(positions.positionForAsset(withSinglePosition(unexplained), "btc")
                .path("custody").path("on_venue").isNull()).isTrue();
    }

    @Test
    void dustAndProducerNotesRetainTheirMeaning() throws Exception {
        JsonNode dusty = JSON.readTree("""
                {"asset":"BTC","basis_reliable":true,"dust_unbacked_qty":"0.00000005",
                 "qty_reconciliation_status":"RECONCILED","short_qty":0}
                """);
        JsonNode dustResult = positions.positionForAsset(withSinglePosition(dusty), "btc");
        assertThat(dustResult.path("basis").path("reliable").asBoolean()).isTrue();
        assertThat(dustResult.path("basis").path("note").asText()).contains("sub-dollar dust");

        JsonNode producer = JSON.readTree("""
                {"asset":"BTC","basis_reliable":false,"oversold_qty":"3",
                 "basis_unreliable_note":"producer owns this diagnosis",
                 "qty_reconciliation_status":"RECONCILED"}
                """);
        JsonNode producerResult = positions.positionForAsset(withSinglePosition(producer), "btc");
        assertThat(producerResult.path("basis").path("note").asText())
                .isEqualTo("producer owns this diagnosis");
        assertThat(producerResult.path("short").path("short").isNull()).isTrue();
    }

    @Test
    void incompleteFuturesCoverageDowngradesFreshSnapshot() throws Exception {
        JsonNode snapshot = JSON.readTree("""
                {"generated_at":"2026-07-28T11:59:00Z","source":{"holdings_as_of":"2026-07-28T11:58:00Z"},
                 "positions":[],"deals":{"open":[]},"futures":{"account_status":"LIVE","positions_status":"AVAILABLE",
                 "marks_status":"AVAILABLE","orders_status":"AVAILABLE_EMPTY","income_status":"COMPLETE",
                 "account_as_of":"2026-07-28T11:59:00Z","positions_as_of":"2026-07-28T11:59:00Z",
                 "marks_as_of":"2026-07-28T11:59:00Z","orders_as_of":"2026-07-28T11:59:00Z",
                 "income_as_of":"2026-07-28T11:59:00Z","open_positions":[{"base_asset":"ETH","symbol":"ETHUSDT",
                 "position_as_of":"2026-07-28T11:59:00Z","income_coverage_status":"PARTIAL"}]}}
                """);
        JsonNode result = positions.positionSnapshotFreshness(snapshot, "eth", NOW);
        assertThat(result.path("band").asText()).isEqualTo("STALE");
        assertThat(result.path("relevant_scope").asText()).isEqualTo("FUTURES_ONLY");
        assertThat(result.path("limitations").toString()).contains("income_coverage:ETHUSDT=PARTIAL");
    }

    private static JsonNode baseSnapshot() throws Exception {
        return JSON.readTree("""
                {"schema":"position-snapshot/1","coverage":{"assets_not_tracked":["GOLD","SILVER"]},
                 "positions":[{"asset":"BTC","qty":"1.5","avg_cost_usd":"71204.0000"},
                              {"asset":"PAXG","qty":"1.3293894","avg_cost_usd":"4204.5027"}],
                 "deals":{"open":[{"asset":"BTC","deal_key":"SPOT:BTC:a","tag":"FK-P1A"},
                                    {"asset":"BTC","deal_key":"SPOT:BTC:b","tag":null}],
                          "closed":[{"asset":"ETH","tag":"FR-B-1A"}]},
                 "trades":{"by_asset":[{"asset":"BTC","fill_count_total":9,"fills":[{"price":"69000"}]}]},
                 "futures":{"open_positions":[{"base_asset":"ETH","side":"SHORT"}],
                            "funding_by_asset":[{"asset":"ETH"}]},
                 "performance":{"by_tag":[{"tag":"FK-P1A","performance":{"deal_count":3}}]}}
                """);
    }

    private static JsonNode withSinglePosition(JsonNode position) throws Exception {
        JsonNode snapshot = baseSnapshot().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot).set("positions", JSON.createArrayNode().add(position));
        return snapshot;
    }
}
