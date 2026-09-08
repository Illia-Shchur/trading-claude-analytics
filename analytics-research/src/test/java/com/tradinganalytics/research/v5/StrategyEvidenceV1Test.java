package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class StrategyEvidenceV1Test {
    @Test
    void lineageInventoryRetainsMalformedBytesAndDoesNotTreatBehaviorHashAsRetry() throws Exception {
        Path root = Files.createTempDirectory("lineage-inventory-");
        String row = "{\"candidate_id\":\"c1\",\"behavior_sha256\":\"" + "a".repeat(64)
                + "\",\"definition\":{\"setup_families\":[\"FK_DELEVERAGING_ABSORPTION\"]}}\n";
        Files.writeString(root.resolve("candidates.jsonl"), row + "{broken\n" + row);
        Files.write(root.resolve("same-bytes.jsonl"), Files.readAllBytes(root.resolve("candidates.jsonl")));
        ObjectNode result = StrategyEvidenceV1.lineageInventory(JsonHashes.mapper().createObjectNode()
                .put("root", root.toString()).put("family", "fk-deleveraging-absorption"));
        assertThat(result.path("status").asText()).isEqualTo("UNRESOLVED_HISTORY");
        assertThat(result.path("matching_row_count").asInt()).isEqualTo(4);
        assertThat(result.path("claimed_behavior_hash_count").asInt()).isEqualTo(1);
        assertThat(result.path("proven_retry_group_count_lower_bound").asInt()).isZero();
        assertThat(result.path("parse_failure_count").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(result.path("duplicate_artifact_count_lower_bound").asInt()).isEqualTo(1);
        assertThat(result.path("promotion_eligible").asBoolean()).isFalse();
        assertThat(ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(result)).isTrue();
        assertThat(result.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(result));
    }

    @Test
    void missingLineageRootFailsClosedInsteadOfInventingGenesis() {
        ObjectNode result = StrategyEvidenceV1.lineageInventory(JsonHashes.mapper().createObjectNode()
                .put("root", "/tmp/definitely-missing-lineage-root-20260907"));
        assertThat(result.path("status").asText()).isEqualTo("UNRESOLVED_ROOT");
        assertThat(result.path("lower_bound").asBoolean()).isTrue();
        assertThat(result.path("unresolved").size()).isEqualTo(1);
    }

    @Test
    void lineageInventoryKeepsRunReceiptMismatchesAndBindsMetricRowsByRunCandidateKey() throws Exception {
        Path root = Files.createTempDirectory("lineage-receipt-");
        String candidate = "{\"candidate_id\":\"c1\",\"behavior_sha256\":\"" + "b".repeat(64)
                + "\",\"strategy_family\":\"fk-deleveraging-absorption\"}\n";
        Path candidates = root.resolve("candidates.jsonl");
        Path metrics = root.resolve("metrics.jsonl");
        Path trades = root.resolve("trades.jsonl");
        Files.writeString(candidates, candidate);
        Files.writeString(metrics, "{\"candidate_id\":\"c1\",\"metrics\":{\"selected\":false}}\n");
        Files.writeString(trades, "{\"candidate_id\":\"c1\",\"trade_id\":\"t1\",\"net_r\":0.1}\n");
        ObjectNode run = JsonHashes.mapper().createObjectNode();
        ObjectNode refs = run.putObject("artifacts");
        refs.putObject("candidates").put("path", "candidates.jsonl").put("sha256", JsonHashes.sha256(Files.readAllBytes(candidates))).put("rows", 1);
        refs.putObject("metrics").put("path", "metrics.jsonl").put("sha256", "0".repeat(64)).put("rows", 1);
        refs.putObject("trades").put("path", "trades.jsonl").put("sha256", JsonHashes.sha256(Files.readAllBytes(trades))).put("rows", 1);
        Files.writeString(root.resolve("run.json"), run.toString());
        ObjectNode result = StrategyEvidenceV1.lineageInventory(JsonHashes.mapper().createObjectNode()
                .put("root", root.toString()).put("family", "fk-deleveraging-absorption"));
        assertThat(result.path("status").asText()).isEqualTo("UNRESOLVED_HISTORY");
        assertThat(result.path("metrics_membership_row_count").asInt()).isEqualTo(1);
        assertThat(result.path("metrics_candidate_id_count").asInt()).isEqualTo(1);
        assertThat(result.path("candidate_ids_without_metric_row_count").asInt()).isZero();
        assertThat(result.path("trade_outcome_join_pending_count").asInt()).isEqualTo(1);
        assertThat(result.path("unresolvable_attempt_count").asInt()).isZero();
        assertThat(result.path("unresolved").toString()).contains("REFERENCED_ARTIFACT_MISMATCH");
    }

    @Test
    void attritionReconcilesSelectionRowsAndScheduledClustersWithoutOutcomeOpening() throws Exception {
        Path file = Files.createTempFile("fixed-result-", ".json");
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("schema", "strategy-fixed-baseline-result/1")
                .put("event_count", 3).put("admitted_event_count", 2)
                .put("matched_control_count", 1).put("skipped_open_position_count", 1);
        result.putArray("setup_events").addObject().put("episode_id", "e1");
        result.withArray("setup_events").addObject().put("episode_id", "e2");
        result.putArray("skipped_events").addObject().put("status", "SKIPPED_OPEN_POSITION");
        ArrayNode selections = result.putArray("control_selections");
        selections.addObject().put("event_id", "e1").put("candidate_count", 0).putNull("control");
        ObjectNode matched = selections.addObject().put("event_id", "e2").put("candidate_count", 2);
        matched.set("control", JsonHashes.mapper().createObjectNode().put("episode_id", "c1"));
        result.putArray("attempts").addObject().put("event_id", "e1").put("status", "EVENT_COMPLETE_CONTROL_UNRESOLVED");
        result.putObject("metrics").put("event_tested_cluster_count", 2).put("paired_tested_cluster_count", 0);
        result.putArray("independent_market_episodes").addObject().put("cluster_id", "c1");
        result.withArray("independent_market_episodes").addObject().put("cluster_id", "c2");
        result.put("content_sha256", JsonHashes.ownHash(result));
        Files.writeString(file, JsonHashes.mapper().writeValueAsString(result));
        ObjectNode output = StrategyEvidenceV1.matchingAttrition(JsonHashes.mapper().createObjectNode().put("result", file.toString()));
        assertThat(output.path("source").path("event_count").asInt()).isEqualTo(3);
        assertThat(output.path("source").path("matched_control_count").asInt()).isEqualTo(1);
        assertThat(output.path("sequential_attrition").path("complete_pairs_to_paired_clusters").asInt()).isZero();
        assertThat(output.path("marginal_attrition").path("selection_rows_with_zero_candidates").asInt()).isEqualTo(1);
        assertThat(output.path("marginal_attrition").path("no_control_selected").asInt()).isEqualTo(1);
        assertThat(output.path("marginal_attrition").path("unresolved_control_execution").asInt()).isZero();
        assertThat(output.path("marginal_attrition").path("unresolved_selected_control_execution").asInt()).isZero();
        assertThat(output.path("source").path("outcome_values_opened").asBoolean()).isFalse();
        assertThat(output.path("outcome_blind").asBoolean()).isTrue();
        assertThat(output.path("promotion_eligible").asBoolean()).isFalse();
        assertThat(ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(output)).isTrue();
    }

    @Test
    void successorControlDesignBindsPredecessorAndRejectsTampering() throws Exception {
        Path predecessorPath = Path.of("strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json");
        if (!Files.isRegularFile(predecessorPath)) predecessorPath = Path.of("..").resolve(predecessorPath).normalize();
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("predecessor", predecessorPath.toString());
        ObjectNode result = StrategyEvidenceV1.freezeSuccessorControlDesign(options);
        assertThat(result.path("schema").asText()).isEqualTo(StrategyEvidenceV1.SUCCESSOR_SCHEMA);
        assertThat(result.path("predecessor_control_spec_sha256").asText()).isEqualTo(
                JsonHashes.mapper().readTree(Files.readString(predecessorPath)).path("content_sha256").asText());
        assertThat(result.path("promotion_eligible").asBoolean()).isFalse();
        assertThat(result.path("outcomes_opened").asBoolean()).isFalse();
        assertThat(result.path("development_only").asBoolean()).isTrue();
        assertThat(result.path("candidate_pool_policy").asText()).isEqualTo("SAME_ASSET_FULL_FIVE_YEAR_PIT_LOOKBACK");
        assertThat(result.path("maximum_prior_lookback_days").asInt()).isEqualTo(1825);
        assertThat(result.path("cross_asset_fallback").asBoolean()).isFalse();
        assertThat(result.path("inherited_predecessor_rules").path("venue").asText()).isEqualTo("BINANCE");
        assertThat(result.path("inherited_predecessor_rules").path("instrument").asText()).isEqualTo("BINANCE_SPOT");
        assertThat(result.path("successor_delta").path("maximum_prior_lookback_days_from").asInt()).isEqualTo(365);
        Path tampered = Files.createTempFile("controls-tampered-", ".json");
        ObjectNode copy = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(predecessorPath));
        copy.put("calipers", "outcome-dependent");
        Files.writeString(tampered, JsonHashes.mapper().writeValueAsString(copy));
        assertThatThrownBy(() -> StrategyEvidenceV1.freezeSuccessorControlDesign(
                JsonHashes.mapper().createObjectNode().put("predecessor", tampered.toString())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hash is invalid");
    }
}
