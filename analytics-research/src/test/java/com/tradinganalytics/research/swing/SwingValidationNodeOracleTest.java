package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SwingValidationNodeOracleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-22T01:02:03.004Z"), ZoneOffset.UTC);
    @Test
    void frozenCandidateValidationMatchesCapturedNodeOracle() throws Exception {
        ArrayNode rows = rows(); ArrayNode candidates = MAPPER.createArrayNode().add(candidate());
        ObjectNode precommit = MAPPER.createObjectNode().put("schema", "swing-cross-asset-precommit/1").put("validation_asset", "BTC")
                .put("candidate_sha256", SwingEngine.sha256(candidates)).put("primary_candidate_id", "fk");
        precommit.putArray("candidate_ids").add("fk"); precommit.set("acceptance", MAPPER.createObjectNode());
        ObjectNode args = MAPPER.createObjectNode(); args.set("rows", rows); args.set("candidates", candidates); args.set("precommit", precommit);
        args.putNull("featureStoreSha256").putNull("featureSeal");
        ObjectNode node = (ObjectNode) fixture("/oracles/swing-cross-validation-v1.json");
        ObjectNode java = SwingCrossValidator.validateCrossAsset(rows, candidates, precommit, null, null, FIXED);
        assertJson(java, node);
        assertThat(java.path("verdict").asText()).isEqualTo("PRIMARY_FAILED_CROSS_ASSET_CONFIRMATION");
    }

    @Test
    void frozenStrategyValidationMatchesCapturedNodeOracle() throws Exception {
        ArrayNode rows = rows(), components = MAPPER.createArrayNode().add(candidate());
        ObjectNode strategy = MAPPER.createObjectNode().put("schema", "swing-frozen-strategy/1").put("id", "strategy"); strategy.set("components", components);
        ObjectNode precommit = MAPPER.createObjectNode().put("schema", "swing-strategy-cross-asset-precommit/1").put("validation_asset", "btc")
                .put("strategy_id", "strategy").put("component_sha256", SwingEngine.sha256(components)).put("strategy_sha256", SwingEngine.sha256(strategy));
        precommit.set("acceptance", MAPPER.createObjectNode());
        ObjectNode args = MAPPER.createObjectNode(); args.set("rows", rows); args.set("strategy", strategy); args.set("precommit", precommit);
        args.put("featureStoreSha256", "store").putNull("featureSeal");
        ObjectNode node = (ObjectNode) fixture("/oracles/swing-strategy-cross-validation-v1.json");
        ObjectNode java = SwingStrategyCrossValidator.validateFrozenStrategy(rows, strategy, precommit, "store", null, FIXED);
        assertJson(java, node);
        assertThat(java.path("decision").asText()).isEqualTo("FAIL");
    }

    @Test
    void immutableSealsAssetsAndOutagesFailClosed() {
        ObjectNode precommit = MAPPER.createObjectNode().put("schema", "swing-cross-asset-precommit/1").put("validation_asset", "btc")
                .put("candidate_sha256", "wrong").put("primary_candidate_id", "fk"); precommit.putArray("candidate_ids").add("fk");
        assertThatThrownBy(() -> SwingCrossValidator.validateCrossAsset(rows(), MAPPER.createArrayNode().add(candidate()), precommit, null, null))
                .hasMessage("frozen candidate hash mismatch");
        precommit.put("candidate_sha256", SwingEngine.sha256(MAPPER.createArrayNode().add(candidate()))).put("require_feature_store_seal", true);
        assertThatThrownBy(() -> SwingCrossValidator.validateCrossAsset(rows(), MAPPER.createArrayNode().add(candidate()), precommit, null, null))
                .hasMessage("a valid feature-store seal is required");
    }

    private static ArrayNode rows() {
        ObjectNode row = MAPPER.createObjectNode().put("asset", "btc").put("timeframe", "4h").put("framework", "fallen_knives").putNull("channel")
                .put("time", Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()).put("available_at", Instant.parse("2024-01-01T04:00:00Z").toEpochMilli())
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("mechanical_score", 10).put("flow_aligned_rows", 2)
                .put("flow_coverage", "COMPLETE").put("setup_family", "FK_HIGHER_LOW").put("regime", "RANGE");
        row.putArray("setup_families").add("FK_HIGHER_LOW"); row.set("trigger", MAPPER.createObjectNode().put("valid", true).put("completed_bar", true).put("timeframe", "4h").put("age_bars", 0));
        row.set("factors", MAPPER.createObjectNode().set("derivatives", MAPPER.createObjectNode().put("top_vs_global_positioning_z", 0)));
        row.put("funding_rate", 0).put("funding_event_time", row.path("time").asLong()); return MAPPER.createArrayNode().add(row);
    }
    private static ObjectNode candidate() { return MAPPER.createObjectNode().put("id", "fk").put("framework", "fallen_knives").put("direction", "long")
            .put("phase", "1A").put("setup_family", "FK_HIGHER_LOW").put("stop_pct", 6).put("target_r", 1).put("partial_exit_pct", 0); }
    private static JsonNode fixture(String resource) throws Exception {
        try (InputStream input = SwingValidationNodeOracleTest.class.getResourceAsStream(resource)) {
            assertThat(input).as("frozen oracle %s", resource).isNotNull();
            return MAPPER.readTree(input);
        }
    }
    private static void assertJson(JsonNode actual, JsonNode expected) { assertThat(CanonicalJson.canonicalize(actual)).isEqualTo(CanonicalJson.canonicalize(expected)); }
}
