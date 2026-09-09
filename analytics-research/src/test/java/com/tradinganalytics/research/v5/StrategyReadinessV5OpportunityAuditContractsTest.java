package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Readiness audit contracts for an authoritative opportunity chain and physical staging boundary. */
final class StrategyReadinessV5OpportunityAuditContractsTest {
    private static final long NOW = Instant.parse("2026-01-01T00:05:00Z").toEpochMilli();
    private static final String GENERATED_AT = "2026-01-01T00:05:00.000Z";

    @Test
    void authoritativeOpportunityPhysicalDependencyReopensItsStagingHydration(@TempDir Path root) throws Exception {
        OpportunityFixture fixture = opportunityFixture(root);
        ObjectNode options = fixed();
        ObjectNode evidence = options.putObject("evidence");
        for (Artifact artifact : fixture.artifacts) {
            byte[] bytes = json(artifact.value);
            Files.write(artifact.path, bytes);
            evidence.set(artifact.id, artifactSpec(artifact.path, bytes, artifact.value.path("schema").asText()));
        }

        ObjectNode audit = StrategyReadinessV5.buildReadinessAuditV5(options);
        assertThat(audit.path("artifact_verification")).hasSize(fixture.artifacts.size());
        for (JsonNode row : audit.path("artifact_verification"))
            assertThat(row.path("verified").asBoolean()).as(row.path("id").asText()).isTrue();
        assertThat(audit.path("limitations")).extracting(JsonNode::asText)
                .contains("lineage:RUN_OPPORTUNITY_ENVELOPE_LINEAGE_MISSING_OR_MISMATCHED")
                .doesNotContain("lineage:FEATURE_GRAPH_PLAN_LINEAGE_NOT_REOPENED",
                        "lineage:OPPORTUNITY_DOMAIN_PHYSICAL_DEPENDENCY_MISSING",
                        "lineage:OPPORTUNITY_ENVELOPE_V2_PHYSICAL_DEPENDENCY_MISSING",
                        "lineage:OPPORTUNITY_HYDRATION_V2_PHYSICAL_DEPENDENCY_MISSING",
                        "lineage:OPPORTUNITY_HYDRATION_1_PHYSICAL_DEPENDENCY_MISSING",
                        "lineage:OPPORTUNITY_PHYSICAL_PARTITION_BYTES_NOT_REOPENED",
                        "lineage:OPPORTUNITY_PARTITION_SET_PHYSICAL_HASH_MISMATCH");
        JsonNode opportunity = dimension(audit, "opportunity");
        assertThat(opportunity.path("capability").path("score").asDouble()).isEqualTo(10.0);
        assertThat(opportunity.path("operational").path("checks").get(0)
                .path("passed").asBoolean()).isFalse();
        assertThat(opportunity.path("operational").path("checks").get(1)
                .path("passed").asBoolean()).isFalse();
    }

    @Test
    void opportunityPhysicalPartitionTamperDemotesOperationalReadiness(@TempDir Path root) throws Exception {
        OpportunityFixture fixture = opportunityFixture(root);
        Files.writeString(fixture.partitionPath, "tampered\n", StandardCharsets.UTF_8);
        ObjectNode options = fixed();
        ObjectNode evidence = options.putObject("evidence");
        for (Artifact artifact : fixture.artifacts) {
            byte[] bytes = json(artifact.value);
            Files.write(artifact.path, bytes);
            evidence.set(artifact.id, artifactSpec(artifact.path, bytes, artifact.value.path("schema").asText()));
        }

        ObjectNode audit = StrategyReadinessV5.buildReadinessAuditV5(options);
        assertThat(audit.path("limitations")).extracting(JsonNode::asText)
                .contains("lineage:OPPORTUNITY_PHYSICAL_PARTITION_BYTES_NOT_REOPENED")
                .doesNotContain("lineage:OPPORTUNITY_PHYSICAL_HYDRATION_COVERAGE_NOT_REOPENED");
        assertThat(dimension(audit, "opportunity").path("operational").path("checks").get(0)
                .path("passed").asBoolean()).isFalse();
    }

    private static ObjectNode fixed() {
        return JsonHashes.mapper().createObjectNode().put("now", NOW).put("generatedAt", GENERATED_AT);
    }

    private static ObjectNode artifactSpec(Path path, byte[] bytes, String schema) {
        return JsonHashes.mapper().createObjectNode().put("path", path.toString())
                .put("sha256", StrategyReadinessV5.hash(bytes)).put("schema", schema);
    }

    private static JsonNode dimension(ObjectNode audit, String id) {
        for (JsonNode row : audit.path("dimensions")) if (id.equals(row.path("id").asText())) return row;
        throw new AssertionError("missing dimension " + id);
    }

    private static OpportunityFixture opportunityFixture(Path root) throws Exception {
        ObjectNode graph = featureGraph();
        ObjectNode plan = featurePlan(graph);
        ObjectNode domain = oracleValue("/oracles/opportunity-v5.json", "strategy-v5-opportunity-domain/1");
        ObjectNode envelope = oracleValue("/oracles/opportunity-v5.json", "strategy-v5-opportunity-envelope/2");
        envelope.put("plan_sha256", plan.path("content_sha256").asText())
                .put("graph_sha256", graph.path("content_sha256").asText())
                .put("opportunity_domain_sha256", domain.path("content_sha256").asText());
        envelope.put("content_sha256", StrategyReadinessV5.ownHash(envelope));

        Path partitionPath = root.resolve("partition.jsonl");
        byte[] partitionBytes = physicalPriceRows();
        Files.write(partitionPath, partitionBytes);
        String partitionSha = StrategyReadinessV5.hash(partitionBytes);
        ObjectNode partitionSet = oracleValue("/oracles/opportunity-v5.json",
                "strategy-v5-execution-partition-set/1");
        ObjectNode partition = (ObjectNode) partitionSet.path("partitions").get(0);
        partition.put("path", root.relativize(partitionPath).toString())
                .put("sha256", partitionSha).put("bytes", partitionBytes.length).put("row_count", 2)
                .put("min_event_time", "2026-01-01T00:01:00.000Z")
                .put("max_event_time", "2026-01-01T00:02:00.000Z");
        partitionSet.put("partition_bytes_root_sha256", partitionSha);
        partitionSet.put("content_sha256", StrategyReadinessV5.ownHash(partitionSet));

        ObjectNode hydration = oracleValue("/oracles/opportunity-v5.json",
                "strategy-v5-opportunity-hydration/2");
        hydration.put("envelope_sha256", envelope.path("content_sha256").asText())
                .put("partition_set_sha256", StrategyReadinessV5.hash(arrayOfStrings(partitionSha)));
        ((ObjectNode) hydration.path("partition_inventory").get(0))
                .put("partition_sha256", partitionSha)
                .put("partition_path", root.relativize(partitionPath).toString())
                .put("bytes", partitionBytes.length).put("row_count", 2)
                .put("min_event_time", "2026-01-01T00:01:00.000Z")
                .put("max_event_time", "2026-01-01T00:02:00.000Z");
        ObjectNode hydrationWindow = (ObjectNode) hydration.path("windows").get(0);
        hydrationWindow.put("execution_end", "2026-01-01T00:03:00.000Z")
                .put("effective_end_exclusive", "2026-01-01T00:03:00.000Z")
                .put("terminal_time", "2026-01-01T00:02:00.000Z")
                .put("row_count", 2);
        ObjectNode priceRef = (ObjectNode) hydrationWindow.path("partition_refs").get(0);
        priceRef.put("partition_sha256", partitionSha)
                .put("partition_path", root.relativize(partitionPath).toString())
                .put("partition_bytes", partitionBytes.length).put("partition_row_count", 2)
                .put("row_start", "2026-01-01T00:01:00.000Z")
                .put("row_end_exclusive", "2026-01-01T00:03:00.000Z").put("row_count", 2);
        hydration.put("materialized_rows", 2).put("logical_reference_rows", 2)
                .put("partition_bytes_root_sha256", partitionSha);
        ObjectNode staging = stagingHydration(root, envelope, partitionSha, partitionBytes.length);
        hydration.put("physical_hydration_sha256", staging.path("content_sha256").asText());
        hydration.put("content_sha256", StrategyReadinessV5.ownHash(hydration));

        List<Artifact> artifacts = new ArrayList<>();
        artifacts.add(new Artifact("graph", root.resolve("graph.json"), graph));
        artifacts.add(new Artifact("plan", root.resolve("plan.json"), plan));
        artifacts.add(new Artifact("domain", root.resolve("domain.json"), domain));
        artifacts.add(new Artifact("envelope", root.resolve("envelope.json"), envelope));
        artifacts.add(new Artifact("hydration", root.resolve("hydration.json"), hydration));
        artifacts.add(new Artifact("physical", root.resolve("staging.json"), staging));
        artifacts.add(new Artifact("partitionSet", root.resolve("partition-set.json"), partitionSet));
        return new OpportunityFixture(artifacts, partitionPath);
    }

    private static ObjectNode featureGraph() throws Exception {
        ObjectNode graph = (ObjectNode) load("/oracles/feature-dag-v5.json").path("make").deepCopy();
        graph.put("fixture_only", false).put("provenance", "AUTHORITATIVE")
                .put("precommit_sha256", "a".repeat(64))
                .put("predictor_registry_sha256", "b".repeat(64))
                .put("config_sha256", "c".repeat(64));
        graph.put("content_sha256", StrategyReadinessV5.ownHash(graph));
        return graph;
    }

    private static ObjectNode featurePlan(ObjectNode graph) throws Exception {
        ObjectNode plan = (ObjectNode) load("/oracles/feature-dag-v5.json").path("plan").deepCopy();
        plan.put("fixture_only", false).put("provenance", "AUTHORITATIVE")
                .put("graph_sha256", graph.path("content_sha256").asText())
                .put("precommit_sha256", "a".repeat(64)).put("config_sha256", "c".repeat(64));
        plan.put("content_sha256", StrategyReadinessV5.ownHash(plan));
        return plan;
    }

    private static ObjectNode stagingHydration(Path root, ObjectNode envelope,
            String partitionSha, int partitionBytes) {
        ObjectNode value = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-v5-opportunity-hydration/1").put("version", 1)
                .put("status", "STAGING_COMPLETE").put("plan_sha256", "a".repeat(64))
                .put("candidate_set_sha256", "b".repeat(64))
                .put("envelope_sha256", envelope.path("content_sha256").asText())
                .put("max_lifecycle_ms", 120_000).put("lifecycle_timeframe", "60s")
                .put("root_reference", root.toString()).put("staging_format", "JSONL")
                .put("storage_role", "STAGING").put("authoritative", false)
                .put("hydrated_before_outcomes", true)
                .put("captured_at", "2026-01-01T00:04:00.000Z").put("merged_window_count", 1);
        value.putArray("windows").addObject().put("asset", "btc").put("instrument", "BINANCE_PERPETUAL")
                .put("symbol", "BTCUSDT").put("execution_start", "2026-01-01T00:01:00.000Z")
                .put("execution_end", "2026-01-01T00:03:00.000Z").put("max_lifecycle_ms", 120_000)
                .put("lifecycle_timeframe", "60s");
        ObjectNode capture = value.putArray("captures").addObject()
                .put("asset", "btc").put("instrument", "BINANCE_PERPETUAL").put("symbol", "BTCUSDT")
                .put("envelope_sha256", envelope.path("content_sha256").asText())
                .put("candidate_set_sha256", "b".repeat(64)).put("window_sha256", "c".repeat(64));
        capture.set("partition", JsonHashes.mapper().createObjectNode().put("path", "partition.jsonl")
                .put("sha256", partitionSha).put("bytes", partitionBytes).put("row_count", 2)
                .put("format", "JSONL").put("storage_role", "STAGING").put("authoritative", false));
        capture.putArray("source_receipts");
        capture.putObject("coverage").put("complete", true).put("expected_rows", 2)
                .put("observed_rows", 2)
                .put("min_event_time", "2026-01-01T00:01:00.000Z")
                .put("max_event_time", "2026-01-01T00:02:00.000Z")
                .put("captured_at", "2026-01-01T00:04:00.000Z");
        return StrategyReadinessV5.withHash(value);
    }

    private static byte[] physicalPriceRows() throws Exception {
        List<ObjectNode> rows = new ArrayList<>();
        rows.add(priceRow(1767225660000L, 100, 102, 99, 101));
        rows.add(priceRow(1767225720000L, 101, 103, 100, 102));
        StringBuilder jsonl = new StringBuilder();
        for (ObjectNode row : rows)
            jsonl.append(JsonHashes.mapper().writeValueAsString(row)).append('\n');
        return jsonl.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ObjectNode priceRow(long eventTime, double open, double high,
            double low, double close) {
        return JsonHashes.mapper().createObjectNode().put("event_time", eventTime)
                .put("asset", "btc").put("instrument", "BINANCE_PERPETUAL")
                .put("symbol", "BTCUSDT").put("open", open).put("high", high)
                .put("low", low).put("close", close).put("series_role", "PRICE");
    }

    private static ObjectNode oracleValue(String resource, String schema) throws Exception {
        JsonNode root = load(resource);
        var fields = root.fields();
        while (fields.hasNext()) {
            JsonNode value = fields.next().getValue().path("value");
            if (schema.equals(value.path("schema").asText())) return (ObjectNode) value.deepCopy();
        }
        throw new AssertionError("missing oracle value " + schema);
    }

    private static JsonNode load(String resource) throws Exception {
        try (InputStream input = Objects.requireNonNull(
                StrategyReadinessV5OpportunityAuditContractsTest.class.getResourceAsStream(resource),
                "missing resource " + resource)) {
            return JsonHashes.mapper().readTree(input);
        }
    }

    private static byte[] json(JsonNode value) throws Exception {
        return (JsonHashes.mapper().writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static ArrayNode arrayOfStrings(String... values) {
        ArrayNode array = JsonHashes.mapper().createArrayNode();
        for (String value : values) array.add(value);
        return array;
    }

    private record Artifact(String id, Path path, ObjectNode value) {}
    private record OpportunityFixture(List<Artifact> artifacts, Path partitionPath) {}
}
