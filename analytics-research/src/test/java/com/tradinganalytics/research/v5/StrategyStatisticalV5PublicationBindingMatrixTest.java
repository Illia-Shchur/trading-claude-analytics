package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Publication inventory and retained-OOS bindings with exact physical stage references. */
final class StrategyStatisticalV5PublicationBindingMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final List<String> STAGES = List.of("genetic", "execution_fills", "selected_trades",
            "stresses", "portfolio", "final_oos_artifact", "final_oos_vector_inventory");
    @TempDir
    Path temporary;

    @Test
    void retainedOosBindingAcceptsExactPhysicalInventoryAndRejectsHashDrift() {
        Fixture fixture = fixture();
        assertThat(StrategyStatisticalV5.assertWfoRetainedOosBinding(fixture.wfo, fixture.artifact, fixture.vector))
                .isTrue();

        ObjectNode wrongArtifactDraft = fixture.artifact.deepCopy();
        ObjectNode wrongEpisode = (ObjectNode) wrongArtifactDraft.withArray("episodes").get(0);
        ObjectNode wrongReturn = (ObjectNode) wrongEpisode.with("candidate_returns").get("behavior:" + fixture.alias);
        wrongReturn.put("net_r", .26);
        final ObjectNode wrongArtifact = StrategyStatisticalV5.withHash(wrongArtifactDraft);
        assertThatThrownBy(() -> StrategyStatisticalV5.assertWfoRetainedOosBinding(
                fixture.wfo, wrongArtifact, fixture.vector)).hasMessageContaining("hashes disagree with the WFO");

        ObjectNode wrongVectorDraft = fixture.vector.deepCopy();
        ObjectNode wrongVectorRow = (ObjectNode) wrongVectorDraft.with("vectors").withArray(fixture.alias).get(0);
        wrongVectorRow.put("net_r", .26);
        final ObjectNode wrongVector = StrategyStatisticalV5.withHash(wrongVectorDraft);
        assertThatThrownBy(() -> StrategyStatisticalV5.assertWfoRetainedOosBinding(
                fixture.wfo, fixture.artifact, wrongVector)).hasMessageContaining("hashes disagree with the WFO");

        ObjectNode wrongEpisodeInventoryDraft = fixture.wfo.deepCopy();
        wrongEpisodeInventoryDraft.withArray("oos_episode_ids").add("wfo-extra");
        final ObjectNode wrongEpisodeInventory = StrategyStatisticalV5.withHash(wrongEpisodeInventoryDraft);
        assertThatThrownBy(() -> StrategyStatisticalV5.assertWfoRetainedOosBinding(
                wrongEpisodeInventory, fixture.artifact, fixture.vector))
                .hasMessageContaining("vector inventory episode binding mismatch");
    }

    @Test
    void authoritativeInventoryRequiresAllSevenStageHashesAndConsistentBindings() {
        Fixture fixture = fixture();
        assertThat(StrategyStatisticalV5.makeStatisticalPublicationTransaction(fixture.options).path("status")
                .asText()).isEqualTo("PREPARED");

        ObjectNode missing = fixture.options.deepCopy();
        ObjectNode run = (ObjectNode) missing.path("run");
        ((ObjectNode) run.path("stage_artifacts")).remove("genetic");
        run = StrategyStatisticalV5.withHash(run); missing.set("run", run); replacePublicationRun(missing, run);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(missing))
                .hasMessageContaining("missing the complete physical stage artifact inventory");

        ObjectNode mismatched = fixture.options.deepCopy();
        run = (ObjectNode) mismatched.path("run");
        ((ObjectNode) run.path("stage_artifacts")).put("portfolio", JsonHashes.sha256("wrong-portfolio"));
        run = StrategyStatisticalV5.withHash(run); mismatched.set("run", run); replacePublicationRun(mismatched, run);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(mismatched))
                .hasMessageContaining("stage artifact inventory disagrees with portfolio_sha256");
    }

    @Test
    void physicalStageReferencesRejectByteAndSemanticStageTampering() throws IOException {
        Fixture fixture = fixture();
        assertThat(StrategyStatisticalV5.makeStatisticalPublicationTransaction(fixture.options).path("status")
                .asText()).isEqualTo("PREPARED");

        Path genetic = fixture.root.resolve("stages/genetic.json");
        Files.write(genetic, new byte[] {' '}, StandardOpenOption.APPEND);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(fixture.options))
                .hasMessageContaining("stage_artifact_refs.genetic bytes are tampered");

        Fixture semantic = fixture();
        ObjectNode wrongStage = stage("EXECUTION_FILLS", semantic.manifest, semantic.wfo);
        writeStage(semantic.root, "stages/genetic.json", wrongStage);
        ObjectNode run = semantic.run.deepCopy();
        ObjectNode ref = (ObjectNode) run.with("stage_artifact_refs").path("genetic");
        ref.put("content_sha256", wrongStage.path("content_sha256").asText());
        byte[] bytes = stageBytes(wrongStage);
        ref.put("byte_sha256", StrategyStatisticalV5.hash(bytes)).put("bytes", bytes.length);
        run.with("stage_artifacts").put("genetic", wrongStage.path("content_sha256").asText());
        run = StrategyStatisticalV5.withHash(run);
        semantic.options.set("run", run);
        replacePublicationRun(semantic.options, run);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(semantic.options))
                .hasMessageContaining("stage_artifact_refs.genetic semantic binding is invalid");
    }

    @Test
    void publicationLineageBindsAccountingToTheCasHead() {
        Fixture fixture = fixture();
        ObjectNode drift = fixture.options.deepCopy();
        ObjectNode run = (ObjectNode) drift.path("run");
        run.with("accounting").put("cumulative_family_k", 2);
        run = StrategyStatisticalV5.withHash(run); drift.set("run", run); replacePublicationRun(drift, run);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(drift))
                .hasMessageContaining("publication research run accounting cumulative family K is not bound");
    }

    @Test
    void physicalFillInventoryRejectsDuplicateEpisodeIdentity() throws IOException {
        Fixture fixture = fixture();
        ObjectNode fills = stage("EXECUTION_FILLS", fixture.manifest, fixture.wfo);
        fills.withArray("rows").addObject().put("episode_id", "wfo-e1").put("asset", "btc").put("net_r", .25);
        fills.withArray("rows").addObject().put("episode_id", "wfo-e1").put("asset", "btc").put("net_r", .25);
        fills = StrategyStatisticalV5.withHash(fills);
        writeStage(fixture.root, "stages/execution_fills.json", fills);
        ObjectNode run = fixture.run.deepCopy();
        updateStageReference(run, "execution_fills", fills, fixture.root);
        run.put("execution_fills_sha256", fills.path("content_sha256").asText());
        run = StrategyStatisticalV5.withHash(run); fixture.options.set("run", run); replacePublicationRun(fixture.options, run);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(fixture.options))
                .hasMessageContaining("retained OOS physical fills has a duplicate episode identity");
    }

    Fixture fixture() {
        return buildFixture(temporaryRoot());
    }

    static Fixture fixtureAt(Path root) {
        return buildFixture(root);
    }

    private static Fixture buildFixture(Path root) {
            Path headPath = root.resolve("control/head.json");
            Path registryPath = root.resolve("control/registry.json");
            Path transactionPath = root.resolve("transactions/run.json");
            String alias = JsonHashes.sha256("publication-binding-alias");
            String dataset = JsonHashes.sha256("publication-binding-data");
            ObjectNode headArgs = object().put("hypothesisFamily", "publication-binding-family")
                    .put("datasetSha256", dataset);
            headArgs.putArray("entries").addObject().put("behavior_sha256", alias).put("dataset_sha256", dataset);
            ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
            StrategyStatisticalV5.initializeExposureHeadFile(object().put("filePath", headPath.toString())
                    .set("head", head));
            ObjectNode definition = object().put("behavior_sha256", alias).put("dataset_sha256", dataset)
                    .put("evaluator_sha256", JsonHashes.sha256("publication-binding-evaluator"));
            definition.putObject("chromosome").put("direction", "long").put("threshold", 2);
            ObjectNode registryArgs = object().put("filePath", registryPath.toString()).set("exposureHead", head);
            registryArgs.putArray("definitions").add(definition);
            ObjectNode registry = StrategyStatisticalV5.appendBehaviorDefinitionRegistryFile(registryArgs);

            ObjectNode artifact = artifact(head, alias, dataset);
            ObjectNode vectorArgs = object().set("exposureHead", head);
            vectorArgs.putArray("episodeIds").add("wfo-e1");
            vectorArgs.putObject("vectors").putArray(alias).addObject().put("episode_id", "wfo-e1")
                    .put("net_r", .25).put("traded", true).put("eligible", true);
            ObjectNode vector = StrategyStatisticalV5.makeVectorInventory(vectorArgs);
            ObjectNode wfo = rejectedWfo(head, artifact, vector, alias, "publication-binding");
            String manifest = JsonHashes.sha256("publication-binding-manifest");
            Map<String, ObjectNode> stages = new LinkedHashMap<>();
            stages.put("genetic", stage("GENETIC", manifest, wfo));
            stages.put("execution_fills", stage("EXECUTION_FILLS", manifest, wfo));
            stages.put("selected_trades", stage("SELECTED_TRADES", manifest, wfo));
            stages.put("stresses", stage("STRESSES", manifest, wfo));
            stages.put("portfolio", stage("PORTFOLIO", manifest, wfo));
            stages.put("final_oos_artifact", artifact);
            stages.put("final_oos_vector_inventory", vector);
            for (String role : STAGES) writeStage(root, "stages/" + role + ".json", stages.get(role));

            ObjectNode run = rejectedResearchRun(head, wfo, "publication-binding");
            run.put("execution_fills_sha256", stages.get("execution_fills").path("content_sha256").asText())
                    .put("selected_trades_sha256", stages.get("selected_trades").path("content_sha256").asText())
                    .put("stresses_sha256", stages.get("stresses").path("content_sha256").asText())
                    .put("portfolio_sha256", stages.get("portfolio").path("content_sha256").asText())
                    .put("oos_artifact_sha256", artifact.path("content_sha256").asText())
                    .put("vector_inventory_sha256", vector.path("content_sha256").asText())
                    .put("oos_validation_exposure_head_sha256", head.path("content_sha256").asText());
            run.putArray("oos_episode_ids").add("wfo-e1");
            ObjectNode hashes = run.putObject("stage_artifacts");
            ObjectNode refs = run.putObject("stage_artifact_refs");
            for (String role : STAGES) {
                ObjectNode value = stages.get(role);
                hashes.put(role, value.path("content_sha256").asText());
                ObjectNode ref = stageReference(root, "stages/" + role + ".json", value);
                refs.set(role, ref);
            }
            run = StrategyStatisticalV5.withHash(run);
            ObjectNode options = object().put("transactionPath", transactionPath.toString())
                    .put("exposureHeadPath", headPath.toString()).put("registryPath", registryPath.toString())
                    .put("recordRoot", root.toString()).put("expectedHeadSha256", head.path("content_sha256").asText())
                    .put("expectedRegistrySha256", registry.path("content_sha256").asText()).set("nextHead", head);
            options.set("wfo", wfo); options.set("run", run);
            ArrayNode artifacts = options.putArray("artifacts");
            artifacts.addObject().put("role", "wfo").put("path", "artifacts/final-wfo.json").set("value", wfo);
            artifacts.addObject().put("role", "research_run").put("path", "artifacts/research-run.json").set("value", run);
            artifacts.addObject().put("role", "final_oos_artifact").put("path", "artifacts/final-oos.json")
                    .set("value", artifact);
            artifacts.addObject().put("role", "final_oos_vector_inventory").put("path", "artifacts/final-vectors.json")
                    .set("value", vector);
        return new Fixture(root, head, registry, artifact, vector, wfo, run, options, alias, manifest);
    }

    private static ObjectNode artifact(ObjectNode head, String alias, String dataset) {
        ObjectNode args = object(); ObjectNode lineage = args.putObject("lineage");
        lineage.put("dataset_sha256", dataset);
        for (String key : List.of("candidate_set_sha256", "feature_set_sha256", "label_set_sha256",
                "execution_set_sha256")) lineage.put(key, JsonHashes.sha256("binding-" + key));
        args.putArray("candidates").addObject().put("candidate_id", "behavior:" + alias)
                .put("behavior_sha256", alias);
        args.set("exposureHead", head);
        args.putArray("episodes").addObject().put("episode_id", "wfo-e1").put("asset", "btc")
                .put("decision_time", "2026-01-01T00:00:00Z").put("resolution_time", "2026-01-02T00:00:00Z")
                .put("eligible", true).putObject("candidate_returns").putObject("behavior:" + alias)
                .put("net_r", .25).put("traded", true);
        return StrategyStatisticalV5.makeStatisticalArtifactSet(args);
    }

    private static ObjectNode rejectedWfo(ObjectNode head, ObjectNode artifact, ObjectNode vector, String alias,
            String prefix) {
        ObjectNode audit = object().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("audit")).put("version", 1)
                .put("fail_closed_missing_inputs", true).put("pass", false).put("decision", "REJECTED")
                .put("independent_opportunity_count", 0).put("independent_trade_count", 0).put("sample_count", 0)
                .put("selected_candidate_id", "none").put("market_cluster_inventory_sha256", JsonHashes.sha256(prefix + "-clusters"))
                .put("exposure_head_sha256", head.path("content_sha256").asText());
        audit.putObject("max_statistic").put("cumulative_k", head.path("cumulative_k").asInt());
        ObjectNode gates = audit.putObject("gates");
        for (String gate : List.of("hard_metrics", "baseline_comparison", "bootstrap_p20_positive",
                "weighted_bootstrap_p20_positive", "max_statistic", "search_adjusted_expectancy_positive", "dsr",
                "pbo", "minimum_independent_episodes", "recent_oos_positive", "earlier_blocks", "positive_years",
                "positive_outer_folds", "plateau", "neighbour_fraction", "seed_stability", "null_controls",
                "stress_ablation", "asset_decisions", "portfolio")) gates.put(gate, false);
        audit = StrategyStatisticalV5.withHash(audit);
        ObjectNode scope = object().put("schema", "strategy-v5-statistical-asset-scope/1").put("version", 1)
                .putNull("source_sha256");
        scope.putArray("trade_assets").add("btc"); scope.putArray("replication_assets"); scope.putArray("context_assets");
        scope = StrategyStatisticalV5.withHash(scope);
        ArrayNode folds = MAPPER.createArrayNode();
        for (int index = 1; index <= 8; index++) {
            ObjectNode fold = object().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("fold"))
                    .put("version", 1).put("fold_id", "outer-" + index).put("status", "REJECTED")
                    .put("purge_ms", 30L * 86_400_000L).put("embargo_ms", 7L * 86_400_000L);
            fold.putArray("train_episode_ids"); fold.putArray("test_episode_ids"); folds.add(StrategyStatisticalV5.withHash(fold));
        }
        ObjectNode refit = object().put("schema", "strategy-v5-statistical-development-refit/1").put("version", 1)
                .put("validation_audit_sha256", audit.path("content_sha256").asText())
                .put("validation_exposure_head_sha256", head.path("content_sha256").asText())
                .put("exposure_head_sha256", head.path("content_sha256").asText())
                .put("selected_from_outer_fold_winners", false).put("excluded_from_retrospective_oos_audit", true)
                .put("status", "REJECTED");
        refit.putArray("asset_refits"); refit = StrategyStatisticalV5.withHash(refit);
        ObjectNode wfo = object().put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("wfo")).put("version", 1)
                .put("fold_count", 8).set("folds", folds);
        wfo.set("asset_scope", scope); wfo.put("validation_exposure_head_sha256", head.path("content_sha256").asText())
                .put("validation_exposure_head_cumulative_k", head.path("cumulative_k").asInt());
        wfo.set("validation_exposure_head", head); wfo.put("exposure_head_sha256", head.path("content_sha256").asText())
                .put("cumulative_k", head.path("cumulative_k").asInt()).put("oos_artifact_sha256", artifact.path("content_sha256").asText())
                .put("vector_inventory_sha256", vector.path("content_sha256").asText()).put("oos_weighting", "UNWEIGHTED");
        wfo.putArray("oos_episode_ids").add("wfo-e1"); wfo.set("audit", audit); wfo.set("development_refit", refit);
        wfo.putArray("asset_decisions"); wfo.putArray("asset_decisions_final");
        ObjectNode portfolioArgs = object().put("lineage_sha256", JsonHashes.sha256(prefix + "-portfolio-lineage"))
                .put("sourceArtifactSha256", JsonHashes.sha256(prefix + "-portfolio-source")).put("pass", false);
        portfolioArgs.putArray("assetDecisions").addObject().put("asset", "btc").put("pass", false);
        portfolioArgs.putArray("returnIncrements").addObject().put("episode_id", "none").put("asset", "btc").put("net_r", 0);
        wfo.set("portfolio_decision", StrategyStatisticalV5.makePortfolioDecision(portfolioArgs));
        return StrategyStatisticalV5.withHash(wfo.put("decision", "REJECTED").put("gate_pass", false));
    }

    private static ObjectNode rejectedResearchRun(ObjectNode head, ObjectNode wfo, String prefix) {
        String manifest = JsonHashes.sha256(prefix + "-manifest");
        ObjectNode run = object().put("schema", "strategy-research-run/5").put("version", 1)
                .put("provenance", "AUTHORITATIVE_RECOMPUTED").put("manifest_sha256", manifest)
                .put("feature_rows_sha256", JsonHashes.sha256(prefix + "-features"))
                .put("label_rows_sha256", JsonHashes.sha256(prefix + "-labels"))
                .put("execution_rows_sha256", JsonHashes.sha256(prefix + "-execution"))
                .put("mark_rows_sha256", JsonHashes.sha256(prefix + "-marks")).put("decision", "REJECTED");
        run.putArray("pipeline").add("features").add("signal_intent").add("labels").add("execution_fills")
                .add("trades").add("metrics").add("stresses").add("portfolio").add("wfo");
        run.putObject("lineage").put("manifest_sha256", manifest)
                .put("feature_rows_sha256", run.path("feature_rows_sha256").asText())
                .put("label_rows_sha256", run.path("label_rows_sha256").asText())
                .put("execution_rows_sha256", run.path("execution_rows_sha256").asText())
                .put("mark_rows_sha256", run.path("mark_rows_sha256").asText())
                .put("wfo_sha256", wfo.path("content_sha256").asText());
        run.putArray("candidate_metrics"); run.putObject("accounting").put("declared_k", 1).put("evaluated_k", 1)
                .put("market_episode_count", 1).put("zero_episode_binding", true)
                .put("cumulative_family_k", head.path("cumulative_k").asInt());
        run.putObject("wfo").put("pass", false).put("status", "REJECTED").put("artifact", wfo.path("content_sha256").asText());
        run.putObject("gate_status").put("wfo", false).put("stress", false).put("portfolio", false)
                .put("all_required_stages", false);
        return run;
    }

    private static ObjectNode stage(String stage, String manifest, ObjectNode wfo) {
        ObjectNode result = object().put("schema", "strategy-v5-authoritative-stage-artifact/1")
                .put("version", 1).put("stage", stage).put("provenance", "AUTHORITATIVE_RECOMPUTED")
                .put("source_manifest_sha256", manifest).put("wfo_sha256", wfo.path("content_sha256").asText())
                .put("marks_bound", true).put("funding_status", "NOT_APPLICABLE");
        result.putArray("rows"); return StrategyStatisticalV5.withHash(result);
    }

    private static ObjectNode stageReference(Path root, String path, ObjectNode value) {
        byte[] bytes = stageBytes(value); return object().put("schema", value.path("schema").asText()).put("version", 1)
                .put("path", path).put("content_sha256", value.path("content_sha256").asText())
                .put("byte_sha256", StrategyStatisticalV5.hash(bytes)).put("bytes", bytes.length);
    }

    private static void replacePublicationRun(ObjectNode options, ObjectNode run) {
        ((ObjectNode) options.withArray("artifacts").get(1)).set("value", run);
    }

    private Path temporaryRoot() {
        try {
            return temporary == null ? Files.createTempDirectory("statistical-publication-bindings")
                    : Files.createTempDirectory(temporary, "case-");
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void updateStageReference(ObjectNode run, String role, ObjectNode value, Path root) {
        run.with("stage_artifacts").put(role, value.path("content_sha256").asText());
        run.with("stage_artifact_refs").set(role, stageReference(root, "stages/" + role + ".json", value));
    }

    static byte[] stageBytes(ObjectNode value) {
        try { return MAPPER.writeValueAsBytes(value); }
        catch (IOException error) { throw new IllegalStateException(error); }
    }

    private static void writeStage(Path root, String relative, ObjectNode value) {
        try {
            Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.write(path, stageBytes(value));
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }

    record Fixture(Path root, ObjectNode head, ObjectNode registry, ObjectNode artifact, ObjectNode vector,
                   ObjectNode wfo, ObjectNode run, ObjectNode options, String alias, String manifest) {}
}
