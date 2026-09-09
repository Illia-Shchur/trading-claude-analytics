package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises retained physical OOS binding with a complete synthetic SHADOW WFO. */
final class StrategyStatisticalV5PublicationShadowMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final String ALIAS = JsonHashes.sha256("retained-oos-binding-behavior");
    private static final String DATASET = JsonHashes.sha256("retained-oos-binding-dataset");
    @TempDir
    Path temporary;

    @Test
    void shadowPublicationBindsWfoRunAndAllPhysicalStageArtifacts() {
        Publication publication = publication(fixture());
        ObjectNode transaction = StrategyStatisticalV5.makeStatisticalPublicationTransaction(publication.options);
        assertThat(transaction.path("status").asText()).isEqualTo("PREPARED");
        assertThat(publication.run.path("stage_artifact_refs")).hasSize(7);
        assertThat(publication.fillRows).isEqualTo(publication.options.path("run").path("oos_episode_ids").size());
    }

    @Test
    void shadowPublicationRejectsARehashedPhysicalFillThatDisagreesWithRetainedOos() throws IOException {
        Publication publication = publication(fixture());
        ObjectNode fills = (ObjectNode) MAPPER.readTree(Files.readAllBytes(
                publication.root.resolve("stages/execution_fills.json")));
        ((ObjectNode) fills.withArray("rows").get(0)).put("net_r", .987);
        fills = StrategyStatisticalV5.withHash(fills);
        writeStage(publication.root, "stages/execution_fills.json", fills);

        ObjectNode run = publication.run.deepCopy();
        updateStageReference(run, "execution_fills", fills);
        run.put("execution_fills_sha256", fills.path("content_sha256").asText());
        run = StrategyStatisticalV5.withHash(run);
        publication.options.set("run", run);
        ((ObjectNode) publication.options.withArray("artifacts").get(1)).set("value", run);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(publication.options))
                .hasMessageContaining("traded vector").hasMessageContaining("physical fill");
    }

    private static Fixture fixture() {
        return ShadowHolder.VALUE;
    }

    private Publication publication(Fixture fixture) {
        try {
            Path root = Files.createTempDirectory(temporary, "case-");
            Path headPath = root.resolve("control/head.json");
            Path registryPath = root.resolve("control/registry.json");
            Path transactionPath = root.resolve("transactions/run.json");
            ObjectNode head = fixture.exposureHead;
            StrategyStatisticalV5.initializeExposureHeadFile(object().put("filePath", headPath.toString())
                    .set("head", head));
            ObjectNode registryArgs = object().put("filePath", registryPath.toString()).set("exposureHead", head);
            ArrayNode definitions = registryArgs.putArray("definitions");
            for (JsonNode entry : head.path("entries")) {
                String alias = entry.path("behavior_sha256").asText();
                ObjectNode definition = object().put("behavior_sha256", alias)
                        .put("dataset_sha256", entry.path("dataset_sha256").asText())
                        .put("evaluator_sha256", hash("publication-evaluator-" + alias));
                definition.putObject("chromosome").put("holding_period", 2);
                definitions.add(definition);
            }
            ObjectNode registry = StrategyStatisticalV5.appendBehaviorDefinitionRegistryFile(registryArgs);

            String manifest = hash("shadow-publication-manifest");
            Map<String, ObjectNode> stages = new LinkedHashMap<>();
            stages.put("genetic", stage("GENETIC", manifest, fixture.run, emptyRows()));
            stages.put("execution_fills", stage("EXECUTION_FILLS", manifest, fixture.run,
                    physicalFillRows(fixture.run)));
            stages.put("selected_trades", stage("SELECTED_TRADES", manifest, fixture.run,
                    physicalFillRows(fixture.run)));
            stages.put("stresses", stage("STRESSES", manifest, fixture.run, emptyRows()));
            stages.put("portfolio", stage("PORTFOLIO", manifest, fixture.run, emptyRows()));
            stages.put("final_oos_artifact", fixture.artifact);
            stages.put("final_oos_vector_inventory", fixture.vector);
            for (Map.Entry<String, ObjectNode> stage : stages.entrySet()) {
                writeStage(root, "stages/" + stage.getKey() + ".json", stage.getValue());
            }

            ObjectNode run = shadowResearchRun(fixture, stages, manifest);
            ObjectNode options = object().put("transactionPath", transactionPath.toString())
                    .put("exposureHeadPath", headPath.toString()).put("registryPath", registryPath.toString())
                    .put("recordRoot", root.toString()).put("expectedHeadSha256", head.path("content_sha256").asText())
                    .put("expectedRegistrySha256", registry.path("content_sha256").asText()).set("nextHead", head);
            options.set("wfo", fixture.run); options.set("run", run);
            ArrayNode artifacts = options.putArray("artifacts");
            artifacts.addObject().put("role", "wfo").put("path", "artifacts/final-wfo.json").set("value", fixture.run);
            artifacts.addObject().put("role", "research_run").put("path", "artifacts/research-run.json").set("value", run);
            artifacts.addObject().put("role", "final_oos_artifact").put("path", "artifacts/final-oos.json")
                    .set("value", fixture.artifact);
            artifacts.addObject().put("role", "final_oos_vector_inventory").put("path", "artifacts/final-vectors.json")
                    .set("value", fixture.vector);
            return new Publication(root, options, run, physicalFillRows(fixture.run).path("rows").size());
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static ObjectNode shadowResearchRun(Fixture fixture, Map<String, ObjectNode> stages, String manifest) {
        ObjectNode wfo = fixture.run;
        ObjectNode run = object().put("schema", "strategy-research-run/5").put("version", 1)
                .put("provenance", "AUTHORITATIVE_RECOMPUTED").put("manifest_sha256", manifest)
                .put("feature_rows_sha256", hash("shadow-publication-features"))
                .put("label_rows_sha256", hash("shadow-publication-labels"))
                .put("execution_rows_sha256", hash("shadow-publication-execution"))
                .put("mark_rows_sha256", hash("shadow-publication-marks"))
                .put("decision", "SHADOW");
        run.putArray("pipeline").add("features").add("signal_intent").add("labels")
                .add("execution_fills").add("trades").add("metrics").add("stresses").add("portfolio").add("wfo");
        run.putObject("lineage").put("manifest_sha256", manifest)
                .put("feature_rows_sha256", run.path("feature_rows_sha256").asText())
                .put("label_rows_sha256", run.path("label_rows_sha256").asText())
                .put("execution_rows_sha256", run.path("execution_rows_sha256").asText())
                .put("mark_rows_sha256", run.path("mark_rows_sha256").asText())
                .put("wfo_sha256", wfo.path("content_sha256").asText());
        run.putArray("candidate_metrics");
        run.putObject("accounting").put("declared_k", 1).put("evaluated_k", 1)
                .put("market_episode_count", wfo.path("oos_episode_ids").size())
                .put("zero_episode_binding", true)
                .put("cumulative_family_k", wfo.path("cumulative_k").asInt());
        run.putObject("wfo").put("pass", true).put("status", "SHADOW")
                .put("artifact", wfo.path("content_sha256").asText());
        run.putObject("gate_status").put("wfo", true).put("stress", true).put("portfolio", true)
                .put("all_required_stages", true);
        run.put("oos_artifact_sha256", fixture.artifact.path("content_sha256").asText())
                .put("vector_inventory_sha256", fixture.vector.path("content_sha256").asText())
                .put("oos_validation_exposure_head_sha256", wfo.path("validation_exposure_head_sha256").asText())
                .set("oos_episode_ids", wfo.path("oos_episode_ids"));
        run.put("execution_fills_sha256", stages.get("execution_fills").path("content_sha256").asText())
                .put("selected_trades_sha256", stages.get("selected_trades").path("content_sha256").asText())
                .put("stresses_sha256", stages.get("stresses").path("content_sha256").asText())
                .put("portfolio_sha256", stages.get("portfolio").path("content_sha256").asText());
        ObjectNode hashes = run.putObject("stage_artifacts");
        ObjectNode refs = run.putObject("stage_artifact_refs");
        for (Map.Entry<String, ObjectNode> entry : stages.entrySet()) {
            String role = entry.getKey(); ObjectNode value = entry.getValue();
            hashes.put(role, value.path("content_sha256").asText());
            refs.set(role, stageReference("stages/" + role + ".json", value));
        }
        return StrategyStatisticalV5.withHash(run);
    }

    private static ObjectNode physicalFillRows(ObjectNode wfo) {
        ObjectNode result = object(); ArrayNode rows = result.putArray("rows");
        Map<String, ObjectNode> byEpisode = new LinkedHashMap<>();
        for (JsonNode outer : wfo.path("asset_decisions")) {
            JsonNode decisions = outer.path("asset_decisions");
            if (!decisions.isObject()) continue;
            decisions.fields().forEachRemaining(assetEntry -> {
                String asset = assetEntry.getKey(); JsonNode decision = assetEntry.getValue();
                for (JsonNode selected : decision.path("selected_return_vector")) {
                    if (!selected.path("traded").asBoolean(false)) continue;
                    String episode = selected.path("episode_id").asText();
                    byEpisode.putIfAbsent(episode, object().put("episode_id", episode).put("asset", asset)
                            .put("net_r", selected.path("net_r").asDouble()));
                }
            });
        }
        byEpisode.values().forEach(rows::add);
        return result;
    }

    private static ObjectNode emptyRows() {
        ObjectNode value = object(); value.putArray("rows"); return value;
    }

    private static ObjectNode stage(String stage, String manifest, ObjectNode wfo, ObjectNode rows) {
        ObjectNode result = object().put("schema", "strategy-v5-authoritative-stage-artifact/1")
                .put("version", 1).put("stage", stage).put("provenance", "AUTHORITATIVE_RECOMPUTED")
                .put("source_manifest_sha256", manifest).put("wfo_sha256", wfo.path("content_sha256").asText())
                .put("marks_bound", true).put("funding_status", "NOT_APPLICABLE");
        result.set("rows", rows.path("rows"));
        return StrategyStatisticalV5.withHash(result);
    }

    private static ObjectNode stageReference(String path, ObjectNode value) {
        byte[] bytes = stageBytes(value);
        return object().put("schema", value.path("schema").asText()).put("version", 1).put("path", path)
                .put("content_sha256", value.path("content_sha256").asText())
                .put("byte_sha256", StrategyStatisticalV5.hash(bytes)).put("bytes", bytes.length);
    }

    private static void updateStageReference(ObjectNode run, String role, ObjectNode value) {
        run.with("stage_artifacts").put(role, value.path("content_sha256").asText());
        run.with("stage_artifact_refs").set(role, stageReference("stages/" + role + ".json", value));
    }

    private static byte[] stageBytes(ObjectNode value) {
        try { return MAPPER.writeValueAsBytes(value); }
        catch (IOException error) { throw new IllegalStateException(error); }
    }

    private static void writeStage(Path root, String relative, ObjectNode value) {
        try {
            Path path = root.resolve(relative); Files.createDirectories(path.getParent());
            Files.write(path, stageBytes(value));
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    private static final class ShadowHolder {
        private static final Fixture VALUE = buildFixture();
    }

    private static Fixture buildFixture() {
        ObjectNode headOptions = object().put("hypothesisFamily", "retained-oos-binding")
                .put("datasetSha256", DATASET);
        headOptions.putArray("entries").addObject().put("behavior_sha256", ALIAS).put("dataset_sha256", DATASET);
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headOptions);

        ObjectNode artifactOptions = object();
        ObjectNode lineage = artifactOptions.putObject("lineage").put("dataset_sha256", DATASET);
        for (String key : List.of("candidate_set_sha256", "feature_set_sha256", "label_set_sha256",
                "execution_set_sha256")) lineage.put(key, hash("retained-" + key));
        artifactOptions.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", ALIAS);
        artifactOptions.set("exposureHead", head);
        ArrayNode episodes = artifactOptions.putArray("episodes");
        ZonedDateTime start = ZonedDateTime.parse("2022-01-01T00:00:00Z");
        for (int index = 0; index < 48; index++) {
            Instant decision = start.plusMonths(index).toInstant();
            Instant resolution = start.plusMonths(index).plusDays(1).toInstant();
            episodes.addObject().put("episode_id", String.format("w%02d", index + 1)).put("asset", "btc")
                    .put("decision_time", decision.toString()).put("resolution_time", resolution.toString())
                    .put("eligible", true).putObject("candidate_returns").putObject("candidate-1")
                    .put("net_r", valueForIndex(index)).put("traded", true);
        }
        ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactOptions);

        ObjectNode options = object(); options.set("artifact", artifact); options.set("exposureHead", head);
        options.put("mode", "FIXTURE").put("endAt", "2026-01-01T00:00:00Z");
        ObjectNode space = options.putObject("geneSpace");
        space.putArray("genes").addObject().put("name", "holding_period").put("type", "ordered-discrete")
                .put("default", 2).putArray("values").add(1).add(2).add(3);
        ObjectNode constraints = options.putObject("constraints").put("minEpisodes", 1)
                .put("minExpectancy", -10).put("minProfitFactor", 0).put("maxDrawdownR", 100)
                .put("maxCostR", 10).put("minCoverage", 0).put("requireCapacityPass", true);
        ObjectNode config = options.putObject("config").put("population", 2).put("generations", 1)
                .put("minGenerations", 1).put("plateauGenerations", 1).put("crossoverProbability", .9)
                .put("mutationProbability", 0).put("halfLifeMonths", 18).put("bootstrapIterations", 8)
                .put("maxStatIterations", 16).put("seed", 11).put("minPositiveFolds", 1)
                .put("minPositiveYears", 1).put("minTradesPerYear", 1).put("minPlateau", 1)
                .put("minNeighbourFraction", 0).put("minSeedCount", 1).put("minEpisodes", 1)
                .put("prospectiveCutoff", "2026-01-01T00:00:00Z").put("nullIterations", 32)
                .put("nullSequentialBatchSize", 8);
        config.set("constraints", constraints.deepCopy());
        config.putArray("seeds").add(11).add(23).add(47);
        config.set("selectionBudget", selectionBudget());

        ObjectNode output = StrategyStatisticalV5.runNestedWfoV5(options, evaluator(), stressProvider(),
                portfolioProvider(), vectorProvider(), replaySuite(), null);
        return new Fixture(((ObjectNode) output.path("run")).deepCopy(),
                ((ObjectNode) output.path("artifact")).deepCopy(),
                ((ObjectNode) output.path("vectorInventory")).deepCopy(),
                ((ObjectNode) output.path("exposureHead")).deepCopy());
    }

    private static StrategyEvaluatorV5.Evaluator evaluator() {
        return task -> {
            ObjectNode result = object();
            ObjectNode returns = result.putObject("candidate_returns");
            for (JsonNode id : task.path("episode_ids")) returns.putObject(id.asText())
                    .put("net_r", valueForEpisode(id.asText())).put("traded", true);
            result.putObject("metrics").put("cost_r", .01).put("coverage_fraction", 1)
                    .put("capacity_pass", true).put("max_drawdown_r", -.1).put("profit_factor", 2)
                    .put("turnover", 1).put("complexity", 1);
            result.putObject("required").put("bootstrapIterations", 8).put("seed", 11);
            return result;
        };
    }

    private static StrategyStatisticalV5.StatisticalProvider stressProvider() {
        return task -> StrategyStatisticalV5.makeStressDecision(object()
                .put("lineage_sha256", task.path("lineage_sha256").asText()).put("pass", true)
                .put("sourceArtifactSha256", task.path("artifact").path("content_sha256").asText())
                .put("selectedCandidateId", task.path("selected_candidate_id").asText()));
    }

    private static StrategyStatisticalV5.StatisticalProvider portfolioProvider() {
        return task -> {
            ObjectNode args = object().put("lineage_sha256", task.path("lineage_sha256").asText())
                    .put("sourceArtifactSha256", task.path("artifact").path("content_sha256").asText())
                    .put("pass", true);
            args.set("assetDecisions", task.path("asset_decisions"));
            ArrayNode increments = args.putArray("returnIncrements");
            for (JsonNode decision : task.path("asset_decisions")) for (JsonNode row : decision.path("selected_return_vector")) {
                if (row.path("traded").asBoolean(false)) increments.addObject().put("episode_id", row.path("episode_id").asText())
                        .put("asset", row.path("asset").asText()).put("net_r", row.path("net_r").asDouble());
            }
            return StrategyStatisticalV5.makePortfolioDecision(args);
        };
    }

    private static StrategyStatisticalV5.StatisticalProvider vectorProvider() {
        return task -> {
            ObjectNode args = object().set("exposureHead", task.path("exposureHead"));
            args.set("episodeIds", task.path("episode_ids")); ObjectNode vectors = args.putObject("vectors");
            for (JsonNode entry : task.path("exposureHead").path("entries")) {
                ArrayNode rows = vectors.putArray(entry.path("behavior_sha256").asText());
                for (JsonNode id : task.path("episode_ids")) rows.addObject().put("episode_id", id.asText())
                        .put("net_r", valueForEpisode(id.asText())).put("traded", true).put("eligible", true);
            }
            return StrategyStatisticalV5.makeVectorInventory(args);
        };
    }

    private static StrategyStatisticalV5.NullReplaySuite replaySuite() {
        Map<String, StrategyStatisticalV5.NullReplayMethod> methods = new LinkedHashMap<>();
        for (String method : List.of("block_permuted_labels", "timestamp_shifted_outcomes",
                "frequency_matched_random_intents", "winners_curse_selection")) {
            methods.put(method, args -> {
                ObjectNode replayed = ((ObjectNode) args.path("artifact")).deepCopy();
                String selected = args.path("selected_candidate_id").asText();
                for (JsonNode episode : replayed.path("episodes")) {
                    JsonNode row = episode.path("candidate_returns").path(selected);
                    if (row.isObject()) ((ObjectNode) row).put("net_r", 0).put("traded", false);
                }
                return StrategyStatisticalV5.withHash(replayed);
            });
        }
        return new StrategyStatisticalV5.NullReplaySuite(methods);
    }

    private static ObjectNode selectionBudget() {
        ObjectNode value = object().put("population", 2).put("generations", 1);
        value.putArray("seeds").add(11).add(23).add(47);
        return value;
    }

    private static double valueForIndex(int index) {
        return List.of(.12, .42, .19, .33, .15, .37, .24, .29).get(index % 8);
    }

    private static double valueForEpisode(String id) {
        if (id.length() < 2) return .2;
        try { return valueForIndex(Integer.parseInt(id.substring(1)) - 1); }
        catch (NumberFormatException ignored) { return .2; }
    }

    private static String hash(String value) { return JsonHashes.sha256(value); }
    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private record Fixture(ObjectNode run, ObjectNode artifact, ObjectNode vector, ObjectNode exposureHead) {}
    private record Publication(Path root, ObjectNode options, ObjectNode run, int fillRows) {}
}
