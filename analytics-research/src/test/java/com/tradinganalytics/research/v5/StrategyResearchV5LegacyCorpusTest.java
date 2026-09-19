package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.research.legacy.LegacyResearchNext;
import com.tradinganalytics.research.legacy.LegacyResearchV1;
import com.tradinganalytics.research.legacy.LegacyResearchV2;
import com.tradinganalytics.research.legacy.LegacyResearchV3;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bounded generated v1-v4 artifacts exercise the retained legacy reader after history cleanup. */
final class StrategyResearchV5LegacyCorpusTest {
    private static final ObjectMapper JSON = JsonHashes.mapper();
    private static final ResearchSchemaRegistry SCHEMAS = ResearchSchemaRegistry.defaultRegistry();

    @Test
    void generatedLegacyArtifactsValidateIndexAndLeaveSourceBytesUntouched(@TempDir Path temporary)
            throws Exception {
        Path sourceRoot = temporary.resolve("generated-source");
        Files.createDirectories(sourceRoot);
        List<ObjectNode> artifacts = generatedLegacyArtifacts();
        Map<Path, String> sourceHashes = new HashMap<>();
        for (int index = 0; index < artifacts.size(); index++) {
            ObjectNode artifact = artifacts.get(index);
            String schema = artifact.path("schema").asText();
            assertThat(validateLegacyArtifact(artifact)).as("generated legacy schema %s", schema).isTrue();
            Path path = sourceRoot.resolve("artifact-%d.json".formatted(index + 1));
            Files.write(path, JSON.writeValueAsBytes(artifact));
            sourceHashes.put(path, JsonHashes.sha256(path));
        }

        Path copiedRoot = temporary.resolve("copied-source");
        Files.createDirectories(copiedRoot);
        ArrayNode rows = JSON.createArrayNode();
        Set<String> expectedSchemas = new HashSet<>();
        Map<String, String> contentByPath = new HashMap<>();
        for (Path source : sourceHashes.keySet().stream().sorted().toList()) {
            Path copy = copiedRoot.resolve(sourceRoot.relativize(source));
            Files.copy(source, copy);
            JsonNode value = JSON.readTree(Files.readAllBytes(copy));
            assertThat(StrategyResearchV5.validateV5Artifact(value,
                    StrategyResearchV5LegacyCorpusTest::validateLegacyArtifact))
                    .as("v5 dispatches legacy schema %s", value.path("schema").asText()).isTrue();
            String schema = value.path("schema").asText();
            expectedSchemas.add(schema);
            String relative = copiedRoot.relativize(copy).toString().replace('\\', '/');
            String byteHash = JsonHashes.sha256(copy);
            String contentHash = value.path("content_sha256").asText();
            if (!contentHash.matches("[a-f0-9]{64}")) contentHash = byteHash;
            rows.addObject().put("schema", schema).put("content_sha256", contentHash)
                    .put("byte_sha256", byteHash).put("path", relative);
            contentByPath.put(relative, contentHash);
        }

        List<JsonNode> orderedRows = new ArrayList<>();
        rows.forEach(orderedRows::add);
        orderedRows.sort(Comparator.comparing(row -> row.path("schema").asText() + ":"
                + row.path("content_sha256").asText() + ":" + row.path("path").asText()));
        ObjectNode index = JSON.createObjectNode().put("schema", "strategy-research-index/5").put("version", 1);
        ArrayNode ordered = index.putArray("records");
        orderedRows.forEach(ordered::add);
        index.put("content_sha256", JsonHashes.ownHash(index));
        assertThat(StrategyResearchV5.validateV5Artifact(index)).isTrue();

        Map<String, JsonNode> indexedByPath = new HashMap<>();
        index.path("records").forEach(row -> indexedByPath.put(row.path("path").asText(), row));
        for (Path copy : Files.list(copiedRoot).sorted().toList()) {
            JsonNode value = JSON.readTree(Files.readAllBytes(copy));
            String relative = copiedRoot.relativize(copy).toString().replace('\\', '/');
            JsonNode record = indexedByPath.get(relative);
            assertThat(record).as("index entry %s", relative).isNotNull();
            assertThat(record.path("schema").asText()).isEqualTo(value.path("schema").asText());
            assertThat(record.path("content_sha256").asText()).isEqualTo(contentByPath.get(relative));
            assertThat(record.path("byte_sha256").asText()).isEqualTo(JsonHashes.sha256(copy));
        }
        assertThat(index.path("records")).hasSize(artifacts.size());
        assertThat(index.path("records")).extracting(row -> row.path("schema").asText())
                .containsAll(expectedSchemas);
        assertThat(expectedSchemas).contains("strategy-definition/1", "strategy-definition/2",
                LegacyResearchV3.EXPERIMENT_V3_SCHEMA, "strategy-candidate-set/4");
        sourceHashes.forEach((path, before) -> assertThat(JsonHashes.sha256(path))
                .as("generated source bytes remain immutable: %s", path).isEqualTo(before));
    }

    private static List<ObjectNode> generatedLegacyArtifacts() {
        ObjectNode feature = featureContract();
        ObjectNode template = candidateTemplate();
        ObjectNode v1 = JSON.createObjectNode().put("schema", "strategy-definition/1")
                .put("strategy_id", "generated-v1").put("version", "v001")
                .put("created_at", "2026-09-01T00:00:00.000Z").put("status", "FROZEN");
        v1.set("lineage", JSON.createObjectNode().putNull("parent_version").put("change_summary", "bounded test fixture"));
        v1.set("candidate_template", template.deepCopy());
        v1.set("feature_contract", v1FeatureContract());
        v1.set("evidence_policy", JSON.createObjectNode().put("activation_allowed", false));
        assertThat(LegacyResearchV1.validateDefinition(v1)).isTrue();

        ObjectNode precommit = v2Precommit(feature);
        ObjectNode frozenPrecommit = LegacyResearchV2.freezePrecommit(precommit);
        ObjectNode v2Options = JSON.createObjectNode().put("strategy_id", "generated-v2")
                .put("version", "v001").put("created_at", "2026-09-01T00:00:00.000Z");
        v2Options.set("precommit", frozenPrecommit);
        v2Options.set("candidate_template", template.deepCopy());
        v2Options.set("feature_contract", feature.deepCopy());
        ObjectNode v2 = LegacyResearchV2.makeV2Definition(v2Options);
        assertThat(LegacyResearchV2.validateDefinitionV2(v2, frozenPrecommit)).isTrue();

        ObjectNode v3Options = JSON.createObjectNode().put("experimentId", "generated-v3")
                .put("createdAt", "2026-09-01T00:00:00.000Z")
                .put("precommitSha256", StrategyResearchV5.hash("precommit-v3"))
                .put("definitionSha256", StrategyResearchV5.hash("definition-v3"))
                .put("candidateSetSha256", StrategyResearchV5.hash("candidates-v3"))
                .put("dataManifestSha256", StrategyResearchV5.hash("manifest-v3"));
        v3Options.set("requiredAssets", JSON.createArrayNode().add("btc"));
        ObjectNode v3 = LegacyResearchV3.makeExperimentV3(v3Options,
                java.time.Clock.fixed(java.time.Instant.parse("2026-09-01T00:00:00Z"), java.time.ZoneOffset.UTC));
        assertThat(LegacyResearchV3.validateExperimentV3(v3)).isTrue();

        ObjectNode frozenNextPrecommit = LegacyResearchNext.freezeNextPrecommit(nextPrecommit());
        ObjectNode nextOptions = JSON.createObjectNode().set("precommit", frozenNextPrecommit);
        nextOptions.put("method", "GRID");
        nextOptions.set("grid", JSON.createObjectNode().set("target_r", JSON.createArrayNode().add(1).add(2)));
        ObjectNode v4 = LegacyResearchNext.generateNextCandidates(nextOptions);
        assertThat(LegacyResearchNext.validateCandidateSetNext(v4, nextOptions.path("precommit"))).isTrue();
        return List.of(v1, v2, v3, v4);
    }

    private static ObjectNode candidateTemplate() {
        return JSON.createObjectNode().put("id", "fk").put("framework", "fallen_knives")
                .put("direction", "long").put("phase", "1A")
                .put("setup_family", "FK_DELEVERAGING_ABSORPTION")
                .put("stop_pct", 6).put("target_r", 1).put("max_hold_bars", 18);
    }

    private static ObjectNode featureContract() {
        ObjectNode setup = JSON.createObjectNode().put("input_id", "setup-flow")
                .put("evidence_family", "crypto-flow").put("role", "SETUP")
                .put("availability", "completed 4h bar close");
        setup.set("point_in_time", JSON.createObjectNode().put("status", "VERIFIED")
                .put("completed_bar_only", true));
        ObjectNode context = JSON.createObjectNode().put("input_id", "macro-context")
                .put("evidence_family", "macro-rates").put("role", "CONTEXT")
                .put("availability", "first public release timestamp");
        context.set("point_in_time", JSON.createObjectNode().put("status", "PIT_SAFE"));
        ObjectNode contract = JSON.createObjectNode();
        contract.set("inputs", JSON.createArrayNode().add(setup).add(context));
        contract.set("series", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("series_id", "btc-4h").put("asset", "btc")
                        .put("asset_class", "crypto").put("timeframe", "4h")
                        .put("context_only", false).put("tradable", true)
                        .set("point_in_time", JSON.createObjectNode().put("status", "VERIFIED")
                                .put("completed_bar_only", true)))
                .add(JSON.createObjectNode().put("series_id", "macro-daily").put("asset", "us-real-yield")
                        .put("asset_class", "rate").put("timeframe", "1d")
                        .put("context_only", true).put("tradable", false)
                        .set("point_in_time", JSON.createObjectNode().put("status", "PIT_SAFE"))));
        return contract;
    }

    private static ObjectNode v1FeatureContract() {
        ObjectNode input = JSON.createObjectNode().put("input_id", "close")
                .put("field_path", "ohlc.close").put("minimum_coverage", .95).put("role", "SETUP");
        input.set("source", JSON.createObjectNode().put("provider", "fixture"));
        input.set("transformation", JSON.createObjectNode().put("version", "1").put("method", "identity"));
        input.set("availability", JSON.createObjectNode().put("rule", "completed bar close"));
        input.set("point_in_time", JSON.createObjectNode().put("status", "VERIFIED"));
        ObjectNode series = JSON.createObjectNode().put("series_id", "btc-4h-fk")
                .put("asset", "btc").put("timeframe", "4h");
        series.set("point_in_time", JSON.createObjectNode().put("status", "VERIFIED")
                .put("completed_bar_only", true));
        ObjectNode contract = JSON.createObjectNode();
        contract.set("inputs", JSON.createArrayNode().add(input));
        contract.set("series", JSON.createArrayNode().add(series));
        return contract;
    }

    private static ObjectNode v2Precommit(ObjectNode feature) {
        ObjectNode value = JSON.createObjectNode().put("schema", LegacyResearchV2.PRECOMMIT_SCHEMA)
                .put("precommit_id", "generated-v2").put("created_at", "2026-09-01T00:00:00.000Z")
                .put("stage", "CORE_PREMISE").put("phenomenon", "forced crypto deleveraging")
                .put("economic_behavioral_mechanism", "forced sellers transfer inventory")
                .put("persistence", "clearing takes several completed bars")
                .put("crowding_decay", "copied entries compress returns").put("direction", "long")
                .put("expression", "BTC spot").put("failure_invalidation_mechanism", "episodes stop predicting repair")
                .put("role_of_composite_score", "No score in the core premise.");
        value.set("participants", JSON.createObjectNode().put("forced_actor", "levered trader")
                .put("edge_provider", "liquidity provider").put("edge_consumer", "swing trader"));
        value.set("holding_horizon", JSON.createObjectNode().put("min", 1).put("max", 30).put("unit", "days"));
        value.set("expected_signal_frequency", JSON.createObjectNode().put("min", 1).put("max", 8).put("unit", "per month"));
        value.set("expected_win_rate", JSON.createObjectNode().put("min", .35).put("max", .65));
        ObjectNode payoff = JSON.createObjectNode().put("qualitative_shape", "asymmetric right tail");
        payoff.set("average_win_r", JSON.createObjectNode().put("min", 1).put("max", 3));
        payoff.set("average_loss_r", JSON.createObjectNode().put("min", -1.5).put("max", -.5));
        value.set("payoff", payoff);
        ObjectNode regimes = JSON.createObjectNode();
        regimes.set("expected_to_work", JSON.createArrayNode().add("fear"));
        regimes.set("expected_to_fail", JSON.createArrayNode().add("insolvency"));
        value.set("regimes", regimes);
        value.set("required_inputs", feature.path("inputs").deepCopy());
        value.set("falsifier", JSON.createObjectNode().put("test", "event-block null")
                .put("null", "no positive expectancy").set("rejection_thresholds", JSON.createObjectNode().put("expectancy_r", 0)));
        value.set("tradable_instrument_contract", JSON.createObjectNode().put("universe", "CRYPTO_ONLY")
                .set("instruments", JSON.createArrayNode().add(JSON.createObjectNode().put("asset", "btc")
                        .put("asset_class", "crypto").put("instrument_type", "spot"))));
        value.set("non_crypto_context_only", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("input_id", "macro-context").put("asset", "us-real-yield").put("asset_class", "rate")
                .put("context_only", true).put("tradable", false)));
        value.set("independence_replication_groups", JSON.createArrayNode().add("crypto-flow"));
        return value;
    }

    private static ObjectNode nextPrecommit() {
        ObjectNode value = JSON.createObjectNode().put("schema", "strategy-precommit/1")
                .put("precommit_id", "generated-v4").put("phenomenon", "forced selling")
                .put("mechanism", "inventory transfer").put("forced_actor", "leveraged seller")
                .put("edge_consumer", "patient liquidity").put("direction", "long")
                .put("horizon", "3-30 days").put("composite_score_deferred", true)
                .put("falsifier", "no rebound");
        value.set("expected_signal_frequency", JSON.createObjectNode().put("min", 2).put("max", 20));
        value.set("expected_win_rate", JSON.createObjectNode().put("min", .35).put("max", .65));
        value.set("expected_payoff", JSON.createObjectNode().put("average_win_r", 1.5).put("average_loss_r", 1));
        value.set("work_regimes", JSON.createArrayNode().add("liquidation"));
        value.set("fail_regimes", JSON.createArrayNode().add("thin data"));
        value.set("required_inputs", JSON.createArrayNode().add("bars"));
        value.set("replication_groups", JSON.createArrayNode().add("asset").add("episode"));
        value.set("tradable_instrument_contract", JSON.createObjectNode().set("instruments",
                JSON.createArrayNode().add(JSON.createObjectNode().put("asset", "btc")
                        .put("instrument_type", "spot"))));
        return value;
    }

    private static boolean validateLegacyArtifact(JsonNode value) {
        String schema = value.path("schema").asText();
        if (LegacyResearchV3.EXPERIMENT_V3_SCHEMA.equals(schema)) return LegacyResearchV3.validateExperimentV3(value);
        if ("strategy-candidate-set/4".equals(schema)) return LegacyResearchNext.validateCandidateSetNext(value);
        if (schema.endsWith("/2")) return LegacyResearchV2.validateV2Document(value);
        if ("strategy-definition/1".equals(schema)) return LegacyResearchV1.validateDefinition(value);
        return SCHEMAS.validateKnownContractSchema(value);
    }
}
