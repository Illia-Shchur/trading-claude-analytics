package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical opportunity hydration contracts with a complete, deterministic five-year fixture. */
final class StrategyResearchV5OpportunityContractsTest {
    private static final String AS_OF = "2026-08-24T12:30:00.000Z";

    @TempDir Path temporary;

    @Test
    void makeOpportunityEnvelopeHydratesDenseOneMinuteRowsFromAcquiredManifest() throws Exception {
        OpportunityFixture fixture = opportunityFixture();

        ObjectNode result = StrategyResearchV5.makeOpportunityEnvelope(fixture.options());
        assertThat(result.path("schema").asText()).isEqualTo("strategy-opportunity-envelope/1");
        assertThat(result.path("execution_data_required").asBoolean()).isTrue();
        assertThat(result.path("hydrated_before_outcomes").asBoolean()).isTrue();
        assertThat(result.path("window_count").asInt()).isEqualTo(1);
        assertThat(result.path("windows").get(0).path("candidate_ids").get(0).asText())
                .isEqualTo("candidate-1");
        assertThat(result.path("execution_timeframe").asText()).isEqualTo("1m");
        assertThat(result.path("assets")).hasSize(8);

        ObjectNode missingPartitions = fixture.options().deepCopy();
        missingPartitions.set("partitionArtifacts", JsonHashes.mapper().createArrayNode());
        assertThatThrownBy(() -> StrategyResearchV5.makeOpportunityEnvelope(missingPartitions))
                .hasMessageContaining("bound physical 1m execution partitions");

        ObjectNode tampered = fixture.options().deepCopy();
        ObjectNode artifact = (ObjectNode) tampered.path("partitionArtifacts").get(0);
        artifact.put("sha256", hash("wrong-execution-bytes"));
        assertThatThrownBy(() -> StrategyResearchV5.makeOpportunityEnvelope(tampered))
                .hasMessageContaining("missing or tampered");

        ObjectNode noCoreWindow = fixture.options().deepCopy();
        noCoreWindow.set("featureRows", JsonHashes.mapper().createArrayNode()
                .add(object().put("asset", "btc").put("decision_time", 0).put("score", 0)));
        assertThatThrownBy(() -> StrategyResearchV5.makeOpportunityEnvelope(noCoreWindow))
                .hasMessageContaining("no frozen core-predicate windows");
    }

    private OpportunityFixture opportunityFixture() throws Exception {
        ObjectNode plan = StrategyResearchV5.makeFiveYearBackfillPlan(
                object().put("asOf", AS_OF).put("includeDatedFutures", false));
        String planSha = plan.path("content_sha256").asText();
        Path root = temporary.resolve("acquired");
        Files.createDirectories(root);
        ObjectNode coverage = (ObjectNode) plan.path("coverage");
        plan.put("status", "ACQUIRED").put("plan_sha256", planSha);
        coverage.put("status", "ACQUIRED").put("raw_output_root", root.toString());
        coverage.put("base_history_only", true).put("one_minute_hydration", "BOUND");
        coverage.put("unavailable_series_must_be_disclosed", true);
        ((ArrayNode) coverage.path("gaps")).removeAll();

        Map<String, PhysicalBars> bars = new HashMap<>();
        for (JsonNode series : plan.path("series")) {
            String interval = series.path("interval").asText();
            long step = series.path("expected_step_ms").asLong();
            bars.computeIfAbsent(interval, ignored -> writeBaseBars(root, interval,
                    series.path("start_at").asText(), step, series.path("expected_event_count").asLong()));
        }
        ArrayNode partitions = (ArrayNode) coverage.path("partitions");
        for (JsonNode series : plan.path("series")) {
            String interval = series.path("interval").asText();
            PhysicalBars physical = bars.get(interval);
            ObjectNode partition = object().put("asset", series.path("asset").asText())
                    .put("instrument", series.path("instrument").asText()).put("interval", interval)
                    .put("path", physical.relative()).put("sha256", physical.sha256())
                    .put("row_count", physical.rowCount()).put("min_event_time", physical.minEventTime())
                    .put("max_event_time", physical.maxEventTime());
            partition.putObject("coverage").put("complete", true);
            partitions.add(partition);
        }

        String feePath = writeMetadata(root, "fee-schedule.json", planSha,
                metadataRecords(plan, "fee"));
        String contractPath = writeMetadata(root, "contract-specification.json", planSha,
                metadataRecords(plan, "contract"));
        String fundingPath = writeMetadata(root, "funding-identity.json", planSha,
                metadataRecords(plan, "funding"));
        coverage.set("fee_schedules", boundMetadata(feePath, root.resolve(feePath)));
        coverage.set("contract_specs", boundMetadata(contractPath, root.resolve(contractPath)));
        coverage.set("funding_identity", boundMetadata(fundingPath, root.resolve(fundingPath)));
        plan.put("content_sha256", StrategyResearchV5.ownHash(plan));
        assertThat(StrategyResearchV5.validateFiveYearPlan(plan)).isTrue();

        String featureTime = plan.path("window").path("start_at").asText();
        Path execution = root.resolve("execution").resolve("btc-1m.jsonl");
        Files.createDirectories(execution.getParent());
        byte[] executionBytes = oneMinuteRows(featureTime, 1_441);
        Files.write(execution, executionBytes);
        ObjectNode executionArtifact = object().put("asset", "btc").put("interval", "1m")
                .put("path", root.relativize(execution).toString()).put("sha256", hash(executionBytes));

        ObjectNode options = object().set("manifest", plan);
        options.set("candidateSet", candidateSet());
        options.set("partitionArtifacts", array(executionArtifact));
        options.set("featureRows", array(object().put("asset", "btc")
                .put("decision_time", Instant.parse(featureTime).toEpochMilli()).put("score", 1)));
        options.put("executionTimeframe", "1m").put("lifecycleDays", 1);
        return new OpportunityFixture(options);
    }

    private static PhysicalBars writeBaseBars(Path root, String interval, String startText,
            long step, long count) {
        try {
            String relative = "base-" + interval + ".jsonl";
            Path path = root.resolve(relative);
            long start = Instant.parse(startText).toEpochMilli();
            StringBuilder output = new StringBuilder(Math.toIntExact(Math.min(Integer.MAX_VALUE, count * 80)));
            for (long index = 0; index < count; index++) {
                long event = start + index * step;
                ObjectNode row = object().put("event_time", event).put("availability_time", event + step);
                output.append(JsonHashes.mapper().writeValueAsString(row)).append('\n');
            }
            byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            return new PhysicalBars(relative, StrategyResearchV5.hash(bytes), count, start,
                    start + (count - 1) * step);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] oneMinuteRows(String startText, int count) throws Exception {
        long start = Instant.parse(startText).toEpochMilli();
        StringBuilder output = new StringBuilder(count * 80);
        for (int index = 0; index < count; index++) {
            long event = start + index * 60_000L;
            output.append(JsonHashes.mapper().writeValueAsString(object().put("event_time", event)
                    .put("availability_time", event + 59_000L))).append('\n');
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<ObjectNode> metadataRecords(ObjectNode plan, String kind) {
        Map<String, ObjectNode> pairs = new java.util.TreeMap<>();
        for (JsonNode series : plan.path("series")) {
            if ("funding".equals(kind) && !"funding_events".equals(series.path("series_type").asText())) continue;
            String key = series.path("asset").asText() + "|" + series.path("instrument").asText();
            ObjectNode record = object().put("asset", series.path("asset").asText())
                    .put("instrument", series.path("instrument").asText())
                    .put("effective_from", plan.path("window").path("start_at").asText())
                    .put("effective_to", plan.path("window").path("end_at").asText());
            if ("fee".equals(kind)) {
                record.put("maker_fee_rate", 0.0004).put("taker_fee_rate", 0.0006).put("currency", "USDT");
            } else if ("contract".equals(kind)) {
                record.put("symbol", series.path("asset").asText().toUpperCase() + "USDT")
                        .put("contract_multiplier", 1).put("margin_asset", "USDT")
                        .put("maintenance_margin_ratio", 0.005).put("liquidation_policy", "MARK_PRICE");
            } else {
                record.put("event_id", key + ":funding").put("venue", "BINANCE")
                        .put("source", "deterministic-contract-fixture");
            }
            pairs.put(key, record);
        }
        return List.copyOf(pairs.values());
    }

    private static String writeMetadata(Path root, String relative, String planSha,
            List<ObjectNode> records) throws Exception {
        ObjectNode body = object().put("plan_sha256", planSha).set("records", array(records));
        Path path = root.resolve(relative);
        Files.write(path, JsonHashes.mapper().writeValueAsBytes(body));
        return relative;
    }

    private static ObjectNode boundMetadata(String relative, Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return object().put("status", "BOUND").set("artifacts", array(
                object().put("path", relative).put("sha256", hash(bytes))));
    }

    private static ObjectNode candidateSet() {
        ObjectNode options = object();
        ObjectNode space = object();
        space.set("genes", array(object().put("name", "threshold").put("type", "continuous")
                .put("min", 0).put("max", 1).put("step", 0.1).put("default", 0.5)));
        options.set("geneSpace", space);
        ObjectNode candidate = object().put("candidate_id", "candidate-1");
        ObjectNode definition = object().put("threshold", 0.6);
        definition.set("signal_rule", object().put("field", "score").put("op", ">=")
                .put("threshold", 0.6));
        candidate.set("definition", definition);
        candidate.set("behavior_vector", array(object().put("time", "2026-08-24T12:30:00.000Z").put("r", 1)));
        options.set("candidates", array(candidate));
        options.put("precommitSha256", "1".repeat(64)).put("experimentSha256", "2".repeat(64))
                .put("objectiveContractSha256", "3".repeat(64)).put("acceptanceSha256", "4".repeat(64));
        return StrategyResearchV5.makeCandidateSetV5(options);
    }

    private static ArrayNode array(ObjectNode... values) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        for (ObjectNode value : values) result.add(value);
        return result;
    }

    private static ArrayNode array(List<ObjectNode> values) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private static ObjectNode object() {
        return JsonHashes.mapper().createObjectNode();
    }

    private static String hash(String value) {
        return StrategyResearchV5.hash(value);
    }

    private static String hash(byte[] value) {
        return StrategyResearchV5.hash(value);
    }

    private record PhysicalBars(String relative, String sha256, long rowCount,
                                long minEventTime, long maxEventTime) { }

    private record OpportunityFixture(ObjectNode options) { }
}
