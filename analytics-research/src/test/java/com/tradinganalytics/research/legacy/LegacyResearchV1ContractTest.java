package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.array;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyResearchV1ContractTest {
    @TempDir Path temporary;

    @Test
    void originalDefinitionCandidateRunRegistryAndCliContractIsPreserved() throws Exception {
        ObjectNode definition = definition();
        assertThat(LegacyResearchV1.validateDefinition(definition)).isTrue();
        ObjectNode concurrent = definition.deepCopy();
        ((ObjectNode) concurrent.path("candidate_template")).put("max_concurrent", 2);
        assertThatThrownBy(() -> LegacyResearchV1.validateDefinition(concurrent))
                .hasMessageMatching(".*(max_concurrent|portfolio concurrency).*?");
        assertThat(LegacyResearchV1.EVIDENCE_PHASES).containsExactly(
                "DEVELOPMENT", "WALK_FORWARD_OOS", "EXPOSED_CONFIRMATION",
                "SEALED_CONFIRMATION", "PROSPECTIVE_LIVE");
        ObjectNode missingLineage = definition.deepCopy();
        missingLineage.remove("lineage");
        assertThatThrownBy(() -> LegacyResearchV1.validateDefinition(missingLineage))
                .hasMessageContaining("lineage");
        ObjectNode missingInputs = definition.deepCopy();
        ((ObjectNode) missingInputs.path("feature_contract")).set("inputs", array());
        assertThatThrownBy(() -> LegacyResearchV1.validateDefinition(missingInputs))
                .hasMessageContaining("inputs");

        ObjectNode gridA = object();
        gridA.set("target_r", array().add(1).add(2));
        gridA.set("threshold_offset", array().add(0).add(1));
        ObjectNode gridB = object();
        gridB.set("threshold_offset", array().add(0).add(1));
        gridB.set("target_r", array().add(1).add(2));
        assertThat(LegacyResearchV1.stable(LegacyResearchV1.expandGrid(
                definition.path("candidate_template"), gridA)))
                .isEqualTo(LegacyResearchV1.stable(LegacyResearchV1.expandGrid(
                        definition.path("candidate_template"), gridB)));
        ObjectNode first = ((ObjectNode) definition.path("candidate_template")).deepCopy()
                .put("id", "a");
        ObjectNode alias = ((ObjectNode) definition.path("candidate_template")).deepCopy()
                .put("id", "b");
        ObjectNode duplicate = LegacyResearchV1.accountCandidates(
                array().add(first).add(alias), definition.path("feature_contract").path("series"));
        assertThat(duplicate.path("declared_k").asInt()).isEqualTo(2);
        assertThat(duplicate.path("effective_k").asInt()).isOne();
        assertThat(duplicate.path("per_series").path(0).path("effective_k").asInt()).isOne();
        ObjectNode conflict = alias.deepCopy().put("id", "same").put("target_r", 2);
        assertThatThrownBy(() -> LegacyResearchV1.accountCandidates(array()
                .add(first.deepCopy().put("id", "same")).add(conflict)))
                .hasMessageContaining("id conflict");

        ObjectNode experimentBase = experimentBase(definition);
        ObjectNode candidateSet = LegacyResearchV1.buildCandidateSet(experimentBase, definition);
        assertThat(LegacyResearchV1.validateCandidateSet(candidateSet)).isTrue();
        ObjectNode experiment = experimentBase.deepCopy();
        ((ObjectNode) experiment.path("candidate_set"))
                .put("sha256", LegacyResearchV1.hash(candidateSet));
        ArrayNode metrics = metrics();
        ArrayNode trades = array().add(object().put("trade_id", "t1")
                .put("candidate_id", "fk").put("asset", "btc").put("net_r", 1));
        ObjectNode firstOptions = bundleOptions(
                experiment, definition, candidateSet, metrics, trades,
                "2026-08-23T01:00:00.000Z");
        ObjectNode secondOptions = bundleOptions(
                experiment, definition, candidateSet, metrics, trades,
                "2030-01-01T00:00:00.000Z");
        ObjectNode bundle = LegacyResearchV1.makeRunBundle(firstOptions);
        ObjectNode later = LegacyResearchV1.makeRunBundle(secondOptions);
        assertThat(bundle.path("run").path("run_id").asText())
                .isEqualTo(later.path("run").path("run_id").asText());
        assertThat(LegacyResearchV1.validateRun(bundle.path("run"))).isTrue();
        assertThat(bundle.path("run").path("activation").path("authorized").asBoolean())
                .isFalse();
        assertThat(assetDecision(bundle.path("run"), "btc").path("status").asText())
                .isEqualTo("CANDIDATE_REVIEW");
        assertThat(assetDecision(bundle.path("run"), "eth").path("status").asText())
                .isEqualTo("REJECTED");
        assertThat(bundle.path("run").path("decisions").path("portfolio")
                .path("status").asText()).isNotEqualTo("ACTIVE");

        Path root = temporary.resolve("registry");
        Path definitionPath = root.resolve("definitions/unit/v001.json");
        LegacyResearchV1.writeImmutable(definitionPath, definition);
        assertThatThrownBy(() -> LegacyResearchV1.writeImmutable(definitionPath, definition))
                .hasMessageContaining("overwrite refused");
        LegacyResearchV1.writeImmutable(
                root.resolve("experiments/unit/candidates.json"), candidateSet);
        LegacyResearchV1.writeImmutable(
                root.resolve("experiments/unit/experiment.json"), experiment);
        Path runRoot = LegacyResearchV1.writeRunBundle(root, bundle);
        assertThat(LegacyResearchV1.validateRunDirectory(runRoot).path("run_id").asText())
                .isEqualTo(bundle.path("run").path("run_id").asText());
        LegacyResearchV1.rebuildIndex(root);
        assertThat(LegacyResearchV1.validateRegistry(root).path("valid").asBoolean()).isTrue();
        byte[] index = Files.readAllBytes(root.resolve("index.json"));
        LegacyResearchV1.rebuildIndex(root);
        assertThat(Files.readAllBytes(root.resolve("index.json"))).isEqualTo(index);

        ResearchSchemaRegistry schemas = ResearchSchemaRegistry.defaultRegistry();
        for (JsonNode value : new JsonNode[]{definition, experiment, candidateSet,
                bundle.path("run")}) {
            assertThat(schemas.validateContractSchema(value)).isTrue();
        }

        Invocation listed = invoke("list", "--root", root.toString(), "--kind",
                "performance", "--asset", "btc", "--status", "CANDIDATE_REVIEW");
        assertThat(listed.exit()).as(listed.stderr()).isZero();
        assertThat(listed.stdout()).contains("\"asset\": \"btc\"");
        Invocation shownDefinition = invoke("show", "--root", root.toString(),
                "--strategy", "unit@v001");
        assertThat(shownDefinition.exit()).as(shownDefinition.stderr()).isZero();
        assertThat(shownDefinition.stdout()).contains("strategy-definition/1");
        String runId = bundle.path("run").path("run_id").asText();
        Invocation shownRun = invoke("show", "--root", root.toString(),
                "--id", runId.substring(0, 12));
        assertThat(shownRun.exit()).as(shownRun.stderr()).isZero();
        assertThat(shownRun.stdout()).contains("strategy-run/1");
        Invocation compared = invoke("compare", "--root", root.toString(),
                "--left", runId, "--right", runId);
        assertThat(compared.exit()).as(compared.stderr()).isZero();
        assertThat(compared.stdout()).contains("\"deltas\"");
        Path recordRoot = temporary.resolve("record");
        Invocation recorded = invoke("record", "--root", recordRoot.toString(),
                "--input", definitionPath.toString());
        assertThat(recorded.exit()).as(recorded.stderr()).isZero();
        assertThat(recorded.stdout()).contains("v001.json");
        assertThat(invoke("record", "--root", recordRoot.toString(),
                "--input", definitionPath.toString()).exit()).isOne();

        Files.writeString(runRoot.resolve("metrics.jsonl"), "{}\n");
        assertThatThrownBy(() -> LegacyResearchV1.validateRegistry(root))
                .hasMessageContaining("artifact hash mismatch");
    }

    private static ObjectNode definition() {
        ObjectNode input = object().put("input_id", "close")
                .put("field_path", "ohlc.close").put("minimum_coverage", 0.95)
                .put("role", "SETUP");
        input.set("source", object().put("provider", "fixture"));
        input.set("transformation", object().put("version", "1")
                .put("method", "identity"));
        input.set("availability", object().put("rule", "bar close"));
        input.set("point_in_time", object().put("status", "VERIFIED"));
        ObjectNode template = object().put("id", "fk")
                .put("framework", "fallen_knives").put("direction", "long")
                .put("phase", "1A").put("setup_family", "FK_DELEVERAGING_ABSORPTION")
                .put("stop_pct", 6).put("target_r", 1).put("max_hold_bars", 18);
        ObjectNode feature = object();
        feature.set("inputs", array().add(input));
        feature.set("series", array()
                .add(series("btc", "VERIFIED"))
                .add(series("eth", "UNKNOWN")));
        ObjectNode value = object().put("schema", "strategy-definition/1")
                .put("strategy_id", "unit").put("version", "v001")
                .put("created_at", "2026-08-23T00:00:00.000Z")
                .put("status", "FROZEN");
        value.set("lineage", object().putNull("parent_version")
                .put("change_summary", "fixture"));
        value.set("candidate_template", template);
        value.set("feature_contract", feature);
        value.set("evidence_policy", object().put("activation_allowed", false));
        return value;
    }

    private static ObjectNode series(String asset, String status) {
        ObjectNode value = object().put("series_id", asset + "-4h-fk")
                .put("asset", asset).put("timeframe", "4h");
        value.set("point_in_time", object().put("status", status)
                .put("completed_bar_only", true));
        return value;
    }

    private static ObjectNode experimentBase(ObjectNode definition) {
        ObjectNode experiment = object().put("schema", "strategy-experiment/1")
                .put("experiment_id", "unit")
                .put("created_at", "2026-08-23T00:00:00.000Z")
                .put("evidence_phase", "PROSPECTIVE_LIVE");
        experiment.set("definition", object().put("path", "definitions/unit/v001.json")
                .put("sha256", LegacyResearchV1.hash(definition)));
        experiment.set("required_assets", array().add("btc").add("eth"));
        experiment.set("grid", object());
        experiment.set("candidate_set", object().put("path", "candidates.json").putNull("sha256"));
        experiment.set("acceptance", object().set("minimums",
                object().put("completed_trades", 20).put("profit_factor", 1.1)));
        experiment.set("finalist_candidate_ids", array().add("fk"));
        return experiment;
    }

    private static ArrayNode metrics() {
        ArrayNode values = array();
        for (String asset : new String[]{"btc", "eth"}) {
            ObjectNode row = object().put("scope", "ASSET").put("asset", asset)
                    .put("candidate_id", "fk").put("selected", true);
            row.set("metrics", object().put("completed_trades", 30)
                    .put("profit_factor", 1.2)
                    .put("search_adjusted_expectancy_r", 0.1));
            values.add(row);
        }
        return values;
    }

    private static ObjectNode bundleOptions(
            ObjectNode experiment, ObjectNode definition, ObjectNode candidates,
            ArrayNode metrics, ArrayNode trades, String generatedAt) {
        ObjectNode value = object().put("generatedAt", generatedAt);
        value.set("experiment", experiment);
        value.set("definition", definition);
        value.set("candidateSet", candidates);
        value.set("metrics", metrics);
        value.set("trades", trades);
        return value;
    }

    private static JsonNode assetDecision(JsonNode run, String asset) {
        for (JsonNode row : run.path("decisions").path("per_asset")) {
            if (asset.equals(row.path("asset").asText())) return row;
        }
        throw new AssertionError("missing decision for " + asset);
    }

    private static Invocation invoke(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = LegacyResearchCommandAdapter.run(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Invocation(exit, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record Invocation(int exit, String stdout, String stderr) {}
}
