package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.reporting.position.PositionSnapshots;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PositionSnapshotsParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NOW = Instant.parse("2026-07-28T12:00:00Z").toEpochMilli();
    private final PositionSnapshots java = new PositionSnapshots(JSON);
    private int freshnessIndex;
    private int projectionIndex;

    @Test
    void eventDrivenAndStrictFreshnessMatchNodeExactly() throws Exception {
        assertFreshness("2026-07-28T11:00:00Z", "2026-07-21T12:00:00Z", null);
        assertFreshness("2026-07-28T11:00:00Z", "2026-07-21T12:00:00Z", 30);
        assertFreshness(null, "2026-07-28T11:00:00Z", null);
        assertFreshness(Instant.ofEpochMilli(NOW - 721L * 60_000).toString(), null, null);
    }

    @Test
    void assetProjectionMatchesNodeExactlyAcrossSafetyStates() throws Exception {
        JsonNode base = JSON.readTree("""
                {"schema":"position-snapshot/1","coverage":{"assets_not_tracked":["GOLD","SILVER"]},
                 "positions":[{"asset":"BTC","qty":"1.5","avg_cost_usd":"71204.0000"},
                              {"asset":"PAXG","qty":"1.3293894","avg_cost_usd":"4204.5027"}],
                 "deals":{"open":[{"asset":"BTC","tag":"FK-P1A"},{"asset":"BTC","tag":null}],
                          "closed":[{"asset":"ETH","tag":"FR-B-1A"}]},
                 "trades":{"by_asset":[{"asset":"BTC","fill_count_total":9,"fills":[{"price":"69000"}]}]},
                 "futures":{"open_positions":[{"base_asset":"ETH","side":"SHORT"}],
                            "funding_by_asset":[{"asset":"ETH"}]},
                 "performance":{"by_tag":[{"tag":"FK-P1A","performance":{"deal_count":3}}]}}
                """);
        assertProjection(base, "gold");
        assertProjection(base, "silver");
        assertProjection(base, "btc");
        assertProjection(base, "eth");
        assertProjection(base, "sol");

        for (String position : List.of(
                "{\"asset\":\"BTC\",\"qty_reconciliation_status\":\"EXPLAINED_BY_EXTERNAL_TRANSFER\",\"off_venue_qty\":\"0.5\",\"basis_reliable\":false,\"oversold_qty\":\"1\",\"short_qty\":\"4\",\"short_avg_price_usd\":\"70000\"}",
                "{\"asset\":\"BTC\",\"qty_reconciliation_status\":\"EXPLAINED_BY_SYNTHETIC_OPENING_BALANCE\",\"short_qty\":null}",
                "{\"asset\":\"BTC\",\"qty_reconciliation_status\":\"UNEXPLAINED\",\"basis_reliable\":true,\"dust_unbacked_qty\":\"0.00001\"}",
                "{\"asset\":\"BTC\",\"qty_reconciliation_status\":\"RECONCILED\",\"basis_reliable\":false,\"oversold_qty\":\"3\",\"basis_unreliable_note\":\"producer note\"}")) {
            JsonNode variant = base.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) variant)
                    .set("positions", JSON.createArrayNode().add(JSON.readTree(position)));
            assertProjection(variant, "btc");
        }
    }

    @Test
    void assetAwareFuturesFreshnessMatchesNodeExactly() throws Exception {
        JsonNode snapshot = JSON.readTree("""
                {"generated_at":"2026-07-28T11:59:00Z","source":{"holdings_as_of":"2026-07-28T11:58:00Z"},
                 "positions":[],"deals":{"open":[]},"futures":{"account_status":"LIVE","positions_status":"AVAILABLE",
                 "marks_status":"AVAILABLE","orders_status":"AVAILABLE_EMPTY","income_status":"COMPLETE",
                 "account_as_of":"2026-07-28T11:59:00Z","positions_as_of":"2026-07-28T11:59:00Z",
                 "marks_as_of":"2026-07-28T11:59:00Z","orders_as_of":"2026-07-28T11:59:00Z",
                 "income_as_of":"2026-07-28T11:59:00Z","open_positions":[{"base_asset":"ETH","symbol":"ETHUSDT",
                 "position_as_of":"2026-07-28T11:59:00Z","income_coverage_status":"PARTIAL"}]}}
                """);
        JsonNode expected = frozen().path("snapshot_freshness");
        JsonNode actual = java.positionSnapshotFreshness(snapshot, "eth", NOW);
        assertThat(actual.toString()).isEqualTo(expected.toString());
    }

    private void assertFreshness(String generated, String holdings, Integer strictStale) throws Exception {
        JsonNode generatedNode = generated == null ? JSON.nullNode() : JSON.getNodeFactory().textNode(generated);
        JsonNode holdingsNode = holdings == null ? JSON.nullNode() : JSON.getNodeFactory().textNode(holdings);
        JsonNode expected = frozen().path("freshness").path(freshnessIndex++);
        JsonNode actual = java.positionFreshness(
                generatedNode, holdingsNode, NOW, strictStale,
                PositionSnapshots.DEFAULT_EXPIRED_MINUTES);
        assertThat(actual.toString()).isEqualTo(expected.toString());
    }

    private void assertProjection(JsonNode snapshot, String asset) throws Exception {
        assertThat(java.positionForAsset(snapshot, asset))
                .isEqualTo(frozen().path("projections").path(projectionIndex++));
    }

    private JsonNode frozen() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/oracles/position-snapshots-v1.json")) {
            assertThat(stream).isNotNull();
            return JSON.readTree(stream);
        }
    }
}
