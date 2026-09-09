package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.research.swing.SwingEngine;
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

class LegacyResearchV2AuthoritativeContractTest {
    private static final long BAR_MS = 4L * 60 * 60 * 1_000;
    private static final long START = 1_770_000_000_000L;
    private static final String CREATED_AT = "2026-08-23T00:00:00.000Z";

    @TempDir Path temporary;

    @Test
    void walkForwardFreezesTrainWinnerAndRecomputesEveryBoundArtifact() {
        Fixture fixture = fixture();
        ObjectNode evidence = evaluate(fixture, fixture.experiment());

        JsonNode metricSource = null;
        for (JsonNode metric : evidence.path("metrics")) {
            if (metric.path("metrics").path("completed_trades").asInt() > 0) {
                metricSource = metric;
                break;
            }
        }
        assertThat(metricSource).as("fixture must exercise metric recomputation").isNotNull();

        ObjectNode validation = validation(fixture);
        ObjectNode forgedMetrics = evidence.deepCopy();
        for (JsonNode metric : forgedMetrics.path("metrics")) {
            if (metric.path("candidate_id").asText().equals(
                    metricSource.path("candidate_id").asText())
                    && metric.path("asset").asText().equals(metricSource.path("asset").asText())) {
                ((ObjectNode) metric.path("metrics")).put("expectancy_r",
                        metric.path("metrics").path("expectancy_r").asDouble() + 1);
                break;
            }
        }
        ((ObjectNode) forgedMetrics.path("reconciliation")).put("derived_metrics_sha256",
                LegacyResearchV2.hash(forgedMetrics.path("metrics")));
        forgedMetrics.remove("content_sha256");
        ObjectNode hashedForgedMetrics = LegacyResearchV2.withHash(forgedMetrics);
        assertThatThrownBy(() -> LegacyResearchV2.validateEvidenceBundle(
                hashedForgedMetrics, validation))
                .hasMessageMatching(".*(authoritative metric mismatch|derived metric mismatch).*?");

        ObjectNode forgedPortfolio = evidence.deepCopy();
        ((ObjectNode) forgedPortfolio.path("portfolio")).put("net_pnl",
                forgedPortfolio.path("portfolio").path("net_pnl").asDouble() + 1);
        ((ObjectNode) forgedPortfolio.path("reconciliation")).put("portfolio_result_sha256",
                LegacyResearchV2.hash(forgedPortfolio.path("portfolio")));
        forgedPortfolio.remove("content_sha256");
        ObjectNode hashedForgedPortfolio = LegacyResearchV2.withHash(forgedPortfolio);
        assertThatThrownBy(() -> LegacyResearchV2.validateEvidenceBundle(
                hashedForgedPortfolio, validation))
                .hasMessageContaining("portfolio is not recomputed");

        ObjectNode staleStore = fixture.store().deepCopy();
        ((ObjectNode) staleStore.path("datasets").path(0).path("metadata").path(0))
                .put("setup_family", "FK_SUPPORT_RECLAIM");
        ObjectNode staleInput = input(fixture, fixture.experiment());
        staleInput.set("featureStore", staleStore);
        assertThatThrownBy(() -> LegacyResearchV2.evaluateAuthoritative(staleInput))
                .hasMessageContaining("feature store content hash verification failed");

        ObjectNode unsafeManifest = fixture.manifest().deepCopy();
        ((ObjectNode) unsafeManifest.path("datasets").path(0))
                .put("revision_status", "PROXY_DISCLOSED");
        assertThatThrownBy(() -> LegacyResearchV2.validateDataManifest(
                unsafeManifest, object().put("phase", "WALK_FORWARD_OOS")))
                .hasMessageContaining("unsafe PIT");

        ObjectNode tamperedFold = fixture.experiment().deepCopy();
        ObjectNode tamperedArtifact = (ObjectNode) tamperedFold.path("evaluation_chronology")
                .path("folds").path(0).path("artifact");
        ((ObjectNode) tamperedArtifact.path("test")).put("end",
                tamperedArtifact.path("test").path("end").asLong() + 1);
        assertThatThrownBy(() -> LegacyResearchV2.evaluateAuthoritative(
                input(fixture, tamperedFold))).hasMessageContaining("artifact hash mismatch");

        ObjectNode missingBinding = fixture.experiment().deepCopy();
        ObjectNode missingArtifact = (ObjectNode) missingBinding.path("evaluation_chronology")
                .path("folds").path(0).path("artifact");
        missingArtifact.remove("data_manifest_sha256");
        ((ObjectNode) missingBinding.path("evaluation_chronology").path("folds").path(0))
                .put("artifact_sha256", LegacyResearchV2.hash(missingArtifact));
        assertThatThrownBy(() -> LegacyResearchV2.evaluateAuthoritative(
                input(fixture, missingBinding)))
                .hasMessageContaining("artifact data_manifest_sha256 binding mismatch");

        assertThat(evidence.path("fold_artifacts")).hasSize(1);
        JsonNode fold = evidence.path("fold_artifacts").path(0);
        assertThat(fold.path("effective_train_end").asLong())
                .isEqualTo(fixture.trainEnd());
        assertThat(fold.path("train_selection").path("btc").path("candidate_id").asText())
                .isEqualTo(fixture.reversalCandidate());
        assertThat(fold.path("test_candidates")).extracting(
                row -> row.path("candidate_id").asText())
                .containsExactly(fixture.reversalCandidate());
        assertThat(fold.path("train_selection").path("btc").path("metrics"))
                .anySatisfy(metric -> {
                    if (!metric.path("candidate_id").asText()
                            .equals(fixture.reversalCandidate())) {
                        assertThat(metric.path("metrics").path("completed_trades").asInt())
                                .isOne();
                    }
                });
    }

    @Test
    void exposedAndProspectivePhasesEvaluateOnlyTheFrozenSelection() {
        Fixture fixture = fixture();
        ObjectNode exposedBase = fixture.experiment().deepCopy();
        exposedBase.put("evidence_phase", "EXPOSED_CONFIRMATION");
        ObjectNode exposedChronology = (ObjectNode) exposedBase.path("evaluation_chronology");
        exposedChronology.remove("folds");
        exposedChronology.set("development_window", window(START, START + 4 * BAR_MS));
        exposedChronology.set("confirmation_window", window(START + 8 * BAR_MS,
                START + 12 * BAR_MS));
        ObjectNode exposedExperiment = addFrozenSelection(exposedBase, fixture,
                array().add(object().put("asset", "btc")
                        .put("candidate_id", fixture.reversalCandidate())));
        ObjectNode exposed = evaluate(fixture, exposedExperiment);
        assertThat(exposed.path("decisions").path("per_asset").path(0)
                .path("candidate_id").asText()).isEqualTo(fixture.reversalCandidate());
        assertThat(exposed.path("trades")).allSatisfy(trade ->
                assertThat(trade.path("candidate_id").asText())
                        .isEqualTo(fixture.reversalCandidate()));
        assertThat(metric(exposed, fixture.reversalCandidate(), "btc")
                .path("execution").path("status").asText()).isEqualTo("EVALUATED");
        assertThat(metric(exposed, fixture.supportCandidate(), "btc")
                .path("execution").path("status").asText()).isEqualTo("REJECTED");

        ArrayNode selection = array().add(object().put("asset", "btc")
                .put("candidate_id", fixture.reversalCandidate()));
        ObjectNode prospectiveBase = fixture.experiment().deepCopy();
        prospectiveBase.put("evidence_phase", "PROSPECTIVE_LIVE");
        ObjectNode prospectiveChronology = (ObjectNode) prospectiveBase
                .path("evaluation_chronology");
        prospectiveChronology.remove("folds");
        prospectiveChronology.put("frozen_start_time", START + 8 * BAR_MS);
        prospectiveChronology.set("monitoring_window", window(START + 8 * BAR_MS,
                START + 12 * BAR_MS));
        ObjectNode frozenHashes = object()
                .put("candidate_set_sha256", LegacyResearchV2.hash(fixture.candidates()))
                .put("definition_sha256", LegacyResearchV2.hash(fixture.definition()))
                .put("data_manifest_sha256", LegacyResearchV2.hash(fixture.manifest()))
                .put("feature_store_sha256", fixture.store().path("features_sha256").asText())
                .put("executor_sha256", exposed.path("executor").path("identity_sha256").asText())
                .put("frozen_selection_sha256", LegacyResearchV2.hash(selection));
        prospectiveChronology.set("frozen_hashes", frozenHashes);
        ObjectNode prospectiveExperiment = addFrozenSelection(
                prospectiveBase, fixture, selection);
        ((ObjectNode) prospectiveExperiment.path("evaluation_chronology")
                .path("frozen_hashes")).put("experiment_sha256",
                prospectiveExperiment.path("evaluation_chronology")
                        .path("frozen_selection").path("experiment_sha256").asText());
        ObjectNode prospective = evaluate(fixture, prospectiveExperiment);
        assertThat(prospective.path("decisions").path("per_asset").path(0)
                .path("candidate_id").asText()).isEqualTo(fixture.reversalCandidate());
        assertThat(prospective.path("trades")).allSatisfy(trade ->
                assertThat(trade.path("candidate_id").asText())
                        .isEqualTo(fixture.reversalCandidate()));

        ObjectNode badProspective = prospectiveExperiment.deepCopy();
        ((ObjectNode) badProspective.path("evaluation_chronology").path("frozen_hashes"))
                .put("frozen_selection_sha256", "f".repeat(64));
        assertThatThrownBy(() -> LegacyResearchV2.evaluateAuthoritative(
                input(fixture, badProspective)))
                .hasMessageContaining("prospective frozen hash binding mismatch");
    }

    @Test
    void frozenSelectionIsAssetScopedAcrossTheGlobalCandidateUnion() {
        Fixture base = fixture();
        ArrayNode rows = wfoRows("btc");
        rows.addAll(wfoRows("eth"));
        ObjectNode storeInput = object().put("point_in_time_safe", true);
        storeInput.set("features", rows);
        ObjectNode store = SwingEngine.buildFeatureStore(storeInput);

        ObjectNode definitionInput = object().put("strategy_id", "multi-frozen-fixture")
                .put("stage", "ENTRY_TIMING");
        definitionInput.set("precommit", base.precommit());
        definitionInput.set("parent_evidence", object().put("stage", "CORE_PREMISE")
                .put("run_id", "fixture").put("sha256", "d".repeat(64)));
        ObjectNode template = ((ObjectNode) base.definition().path("candidate_template"))
                .deepCopy();
        template.remove("instrument");
        template.set("instruments", array().add(cryptoSpot("btc")).add(cryptoSpot("eth")));
        definitionInput.set("candidate_template", template);
        definitionInput.set("tradable_instrument_contract", object()
                .put("universe", "CRYPTO_ONLY").set("instruments",
                        array().add(cryptoSpot("btc")).add(cryptoSpot("eth"))));
        ObjectNode featureContract = featureContract();
        ((ArrayNode) featureContract.path("series")).add(object()
                .put("series_id", "eth-4h").put("asset", "eth")
                .put("asset_class", "crypto").put("timeframe", "4h")
                .put("context_only", false).put("tradable", true)
                .set("point_in_time", object().put("status", "VERIFIED")
                        .put("completed_bar_only", true)));
        definitionInput.set("feature_contract", featureContract);
        ObjectNode definition = LegacyResearchV2.makeV2Definition(definitionInput);

        ObjectNode experiment = base.experiment().deepCopy();
        experiment.put("experiment_id", "multi-frozen-fixture")
                .put("evidence_phase", "EXPOSED_CONFIRMATION");
        experiment.set("definition", object().put("path", "multi-definition.json")
                .put("sha256", LegacyResearchV2.hash(definition)));
        experiment.set("required_assets", array().add("btc").add("eth"));
        experiment.set("candidate_set", object().put("path", "multi-candidates.json")
                .putNull("sha256"));
        ObjectNode chronology = (ObjectNode) experiment.path("evaluation_chronology");
        chronology.remove("folds");
        chronology.set("development_window", window(START, START + 4 * BAR_MS));
        chronology.set("confirmation_window", window(START + 8 * BAR_MS,
                START + 12 * BAR_MS));
        experiment.remove("content_sha256");
        experiment = LegacyResearchV2.withHash(experiment);
        ObjectNode design = object();
        design.set("definition", definition);
        design.set("experiment", experiment);
        ObjectNode candidates = LegacyResearchV2.designCandidates(design);
        ((ObjectNode) experiment.path("candidate_set")).put("sha256",
                LegacyResearchV2.hash(candidates));
        experiment.remove("content_sha256");

        ObjectNode manifest = object().put("schema", "strategy-data-manifest/1")
                .put("manifest_id", "multi-frozen-fixture");
        manifest.set("feature_store", object()
                .put("sha256", store.path("features_sha256").asText())
                .put("row_count", store.path("row_count").asInt()));
        ArrayNode datasets = array();
        datasets.add(manifestDataset("btc", "b"));
        datasets.add(manifestDataset("eth", "c"));
        manifest.set("datasets", datasets);

        String reversal = candidateFor(candidates, "FK_REVERSAL_RECLAIM");
        String support = candidateFor(candidates, "FK_SUPPORT_RECLAIM");
        Fixture multi = new Fixture(base.precommit(), definition, experiment, candidates,
                store, manifest, START + 4 * BAR_MS, reversal, support);
        ArrayNode selections = array()
                .add(object().put("asset", "btc").put("candidate_id", reversal))
                .add(object().put("asset", "eth").put("candidate_id", support));
        ObjectNode frozen = addFrozenSelection(experiment, multi, selections);
        ObjectNode evidence = evaluate(multi, frozen);

        assertNotFrozenForAsset(evidence, reversal, "eth");
        assertNotFrozenForAsset(evidence, support, "btc");
        assertThat(decision(evidence, "btc").path("candidate_id").asText())
                .isEqualTo(reversal);
        assertThat(decision(evidence, "eth").path("candidate_id").asText())
                .isEqualTo(support);
    }

    @Test
    void authoritativeCliRecordsIdempotentEvidenceAndDetectsStoredTampering() throws Exception {
        Fixture fixture = fixture();
        ObjectNode cliExperiment = fixture.experiment().deepCopy();
        cliExperiment.put("evidence_phase", "DEVELOPMENT");
        ((ObjectNode) cliExperiment.path("candidate_set")).put("path", "candidates.json");
        ((ObjectNode) cliExperiment.path("evaluation_chronology")).remove("folds");
        cliExperiment.remove("content_sha256");
        cliExperiment = LegacyResearchV2.withHash(cliExperiment);
        Fixture cliFixture = new Fixture(fixture.precommit(), fixture.definition(),
                cliExperiment, fixture.candidates(), fixture.store(), fixture.manifest(),
                fixture.trainEnd(), fixture.reversalCandidate(), fixture.supportCandidate());
        ObjectNode direct = evaluate(cliFixture, cliExperiment);
        for (JsonNode metric : direct.path("metrics")) {
            long scoped = 0;
            double total = 0;
            for (JsonNode trade : direct.path("trades")) {
                if (trade.path("candidate_id").asText()
                        .equals(metric.path("candidate_id").asText())
                        && trade.path("asset").asText().equals(metric.path("asset").asText())) {
                    scoped++;
                    total += trade.path("net_r").asDouble();
                }
            }
            assertThat(metric.path("metrics").path("completed_trades").asLong())
                    .describedAs(metric.path("candidate_id").asText())
                    .isEqualTo(scoped);
            if (scoped > 0) assertThat(metric.path("metrics").path("expectancy_r").asDouble())
                    .describedAs(metric.path("candidate_id").asText())
                    .isEqualTo(total / scoped);
        }
        Path root = temporary.resolve("records");
        Path experimentDirectory = root.resolve("experiments")
                .resolve(cliExperiment.path("experiment_id").asText());
        Path experimentPath = writeJson(experimentDirectory.resolve("experiment.json"),
                cliExperiment);
        writeJson(experimentDirectory.resolve("candidates.json"), fixture.candidates());
        writeJson(root.resolve("wfo-definition.json"), fixture.definition());
        writeJson(root.resolve(fixture.definition().path("precommit").path("path").asText()),
                fixture.precommit());
        Path storePath = writeJson(temporary.resolve("store.json"), fixture.store());
        Path manifestPath = writeJson(temporary.resolve("manifest.json"), fixture.manifest());

        CliResult first = cli("evaluate", "--root", root.toString(), "--experiment",
                experimentPath.toString(), "--features", storePath.toString(), "--manifest",
                manifestPath.toString(), "--record-root", root.toString());
        assertThat(first.exit()).describedAs(first.stderr()).isZero();
        ObjectNode output = parse(first.stdout());
        assertThat(output.path("schema").asText())
                .isEqualTo("strategy-evidence-bundle/1");
        Path evidencePath = root.resolve("evidence-bundles")
                .resolve(output.path("content_sha256").asText() + ".json");
        assertThat(Files.isRegularFile(evidencePath)).isTrue();
        ObjectNode storedEvidence = (ObjectNode) LegacyNodeOracle.MAPPER.readTree(
                Files.readAllBytes(evidencePath));
        assertThat(LegacyResearchV2.validateEvidenceBundle(storedEvidence,
                validation(cliFixture))).isTrue();

        CliResult repeat = cli("evaluate", "--root", root.toString(), "--experiment",
                experimentPath.toString(), "--features", storePath.toString(), "--manifest",
                manifestPath.toString(), "--record-root", root.toString());
        assertThat(repeat.exit()).describedAs(repeat.stderr()).isZero();
        assertThat(parse(repeat.stdout()).path("content_sha256").asText())
                .isEqualTo(output.path("content_sha256").asText());
        Path runPath = root.resolve("runs").resolve(output.path("run_id").asText())
                .resolve("run.json");
        assertThat(Files.readString(runPath)).contains("AUTHORITATIVE_RECOMPUTED");
        assertThat(cli("rebuild-index", "--root", root.toString()).exit()).isZero();
        CliResult validated = cli("validate", "--root", root.toString());
        assertThat(validated.exit()).describedAs(validated.stderr()).isZero();
        assertThat(parse(validated.stdout())
                .path("valid").asBoolean()).isTrue();
        assertThat(cli("show", "--root", root.toString(), "--id",
                output.path("run_id").asText()).stdout())
                .contains("AUTHORITATIVE_RECOMPUTED");
        assertThat(parse(cli("compare", "--root", root.toString(), "--left",
                output.path("run_id").asText(), "--right",
                output.path("run_id").asText()).stdout()).has("deltas")).isTrue();

        byte[] original = Files.readAllBytes(evidencePath);
        ObjectNode tampered = (ObjectNode) LegacyNodeOracle.MAPPER.readTree(original);
        tampered.put("tampered", true);
        Files.writeString(evidencePath, LegacyNodeOracle.MAPPER.writeValueAsString(tampered));
        CliResult invalid = cli("validate", "--root", root.toString());
        assertThat(invalid.exit()).isOne();
        assertThat(invalid.stderr()).containsAnyOf("content hash", "reconciliation", "tampered");
        Files.write(evidencePath, original);
    }

    private static Fixture fixture() {
        ObjectNode precommit = LegacyResearchV2.freezePrecommit(precommit());
        ArrayNode rows = wfoRows("btc");
        ObjectNode storeInput = object().put("point_in_time_safe", true);
        storeInput.set("features", rows);
        ObjectNode store = SwingEngine.buildFeatureStore(storeInput);

        ObjectNode definitionInput = object().put("strategy_id", "wfo-fixture")
                .put("stage", "ENTRY_TIMING");
        definitionInput.set("precommit", precommit);
        definitionInput.set("parent_evidence", object().put("stage", "CORE_PREMISE")
                .put("run_id", "fixture").put("sha256", "d".repeat(64)));
        ObjectNode candidateTemplate = object().put("id_template", "wfo-{n}")
                .put("framework", "fallen_knives").put("direction", "long")
                .put("phase", "1A").put("setup_family", "FK_REVERSAL_RECLAIM")
                .put("stop_pct", 1).put("target_r", 1).put("max_hold_bars", 1)
                .put("trigger_window_bars", 1).put("initial_equity", 10_000);
        candidateTemplate.set("instrument", cryptoSpot("btc"));
        definitionInput.set("candidate_template", candidateTemplate);
        definitionInput.set("feature_contract", featureContract());
        ObjectNode definition = LegacyResearchV2.makeV2Definition(definitionInput);

        ObjectNode chronology = object().put("timezone", "UTC")
                .put("bar_convention", "completed-bar-next-open")
                .put("selection_objective", "expectancy")
                .put("bar_duration_ms", BAR_MS).put("purge_bars", 1)
                .put("embargo_bars", 1);
        chronology.set("tie_breaker", array().add("trades").add("id"));
        chronology.set("seeds", array().add(7));
        chronology.set("development_window", window(START, START + 13 * BAR_MS));
        chronology.set("selection_gate", object().put("require_finite", true));
        ObjectNode experiment = object().put("schema", "strategy-experiment/2")
                .put("experiment_id", "wfo-fixture").put("created_at", CREATED_AT)
                .put("stage", "ENTRY_TIMING").put("evidence_phase", "WALK_FORWARD_OOS")
                .put("hypothesis_family", "flow-family")
                .put("ablation_role", "PARAMETER_SEARCH");
        experiment.set("definition", object().put("path", "wfo-definition.json")
                .put("sha256", LegacyResearchV2.hash(definition)));
        experiment.set("evidence_family_ids", array().add("crypto-flow"));
        experiment.set("required_assets", array().add("btc"));
        experiment.set("grid", object().set("setup_family",
                array().add("FK_REVERSAL_RECLAIM").add("FK_SUPPORT_RECLAIM")));
        experiment.set("parameter_topology", object().set("setup_family",
                object().put("type", "categorical")));
        experiment.set("acceptance", acceptance());
        experiment.set("parent_evidence", object().put("stage", "CORE_PREMISE")
                .put("run_id", "fixture").put("sha256", "d".repeat(64)));
        experiment.set("candidate_set", object().put("path", "wfo-candidates.json")
                .putNull("sha256"));
        experiment.set("evaluation_chronology", chronology);
        experiment = LegacyResearchV2.withHash(experiment);
        ObjectNode design = object();
        design.set("definition", definition);
        design.set("experiment", experiment);
        ObjectNode candidates = LegacyResearchV2.designCandidates(design);
        ((ObjectNode) experiment.path("candidate_set")).put("sha256",
                LegacyResearchV2.hash(candidates));
        experiment.remove("content_sha256");

        ObjectNode manifest = object().put("schema", "strategy-data-manifest/1")
                .put("manifest_id", "wfo-fixture");
        manifest.set("feature_store", object()
                .put("sha256", store.path("features_sha256").asText())
                .put("row_count", store.path("row_count").asInt()));
        manifest.set("datasets", array().add(object().put("dataset_id", "btc")
                .put("asset", "btc").put("venue", "fixture").put("row_count", rows.size())
                .put("min_time", START).put("max_time", START + 12 * BAR_MS)
                .put("source_sha256", "e".repeat(64))
                .put("availability_time_policy", "completed-bar")
                .put("point_in_time_status", "VERIFIED")
                .put("revision_status", "ORIGINAL")));

        long trainEnd = START + 4 * BAR_MS;
        ObjectNode train = window(START, trainEnd);
        ObjectNode test = window(START + 8 * BAR_MS, START + 12 * BAR_MS);
        ObjectNode artifact = object().put("fold_id", "wfo-0")
                .put("experiment_sha256", LegacyResearchV2.hash(experiment))
                .put("candidate_set_sha256", LegacyResearchV2.hash(candidates))
                .put("data_manifest_sha256", LegacyResearchV2.hash(manifest));
        artifact.set("train", train);
        artifact.set("test", test);
        ObjectNode fold = object().put("purge_bars", 1).put("embargo_bars", 1);
        fold.set("train", train);
        fold.set("test", test);
        fold.set("artifact", artifact);
        fold.put("artifact_sha256", LegacyResearchV2.hash(artifact));
        ((ObjectNode) experiment.path("evaluation_chronology"))
                .set("folds", array().add(fold));

        String reversal = candidateFor(candidates, "FK_REVERSAL_RECLAIM");
        String support = candidateFor(candidates, "FK_SUPPORT_RECLAIM");
        return new Fixture(precommit, definition, experiment, candidates, store, manifest,
                trainEnd, reversal, support);
    }

    private static ObjectNode evaluate(Fixture fixture, ObjectNode experiment) {
        return LegacyResearchV2.evaluateAuthoritative(input(fixture, experiment));
    }

    private static ObjectNode input(Fixture fixture, ObjectNode experiment) {
        ObjectNode input = object().put("adapter", "swing-engine/1");
        input.set("experiment", experiment);
        input.set("definition", fixture.definition());
        input.set("candidateSet", fixture.candidates());
        input.set("precommit", fixture.precommit());
        input.set("featureStore", fixture.store());
        input.set("dataManifest", fixture.manifest());
        input.set("executorConfig", object().put("same_bar_collision", "stop-first")
                .put("timezone", "UTC")
                .put("bar_convention", "completed-bar-next-open"));
        return input;
    }

    private static ObjectNode validation(Fixture fixture) {
        ObjectNode input = object();
        input.set("experiment", fixture.experiment());
        input.set("candidateSet", fixture.candidates());
        input.set("dataManifest", fixture.manifest());
        input.set("featureStore", fixture.store());
        return input;
    }

    private static ObjectNode addFrozenSelection(
            ObjectNode source, Fixture fixture, ArrayNode selections) {
        ObjectNode next = source.deepCopy();
        ArrayNode aliases = array();
        for (JsonNode candidate : fixture.candidates().path("candidates")) {
            aliases.add(object().put("behavior_sha256",
                    candidate.path("behavior_sha256").asText()).set("candidate_ids",
                    array().add(candidate.path("candidate_id").asText())));
        }
        ObjectNode frozen = object()
                .put("selection_sha256", LegacyResearchV2.hash(selections))
                .put("candidate_set_sha256", LegacyResearchV2.hash(fixture.candidates()))
                .put("definition_sha256", LegacyResearchV2.hash(fixture.definition()))
                .put("behavioral_k", aliases.size());
        frozen.set("selections", selections);
        frozen.set("aliases", aliases);
        ObjectNode behavioral = object().put("runtime_behavioral_k", aliases.size());
        behavioral.set("aliases", aliases);
        frozen.put("behavioral_contract_sha256", LegacyResearchV2.hash(behavioral));
        ((ObjectNode) next.path("evaluation_chronology")).set("frozen_selection", frozen);
        next.remove("content_sha256");
        ObjectNode binding = next.deepCopy();
        ObjectNode bindingFrozen = (ObjectNode) binding.path("evaluation_chronology")
                .path("frozen_selection");
        bindingFrozen.remove("experiment_sha256");
        bindingFrozen.remove("selection_sha256");
        frozen.put("experiment_sha256", LegacyResearchV2.hash(binding));
        return next;
    }

    private static JsonNode metric(ObjectNode evidence, String candidate, String asset) {
        for (JsonNode metric : evidence.path("metrics")) {
            if (candidate.equals(metric.path("candidate_id").asText())
                    && asset.equals(metric.path("asset").asText())) return metric;
        }
        throw new AssertionError("missing metric " + candidate + '/' + asset);
    }

    private static JsonNode decision(ObjectNode evidence, String asset) {
        for (JsonNode decision : evidence.path("decisions").path("per_asset")) {
            if (asset.equals(decision.path("asset").asText())) return decision;
        }
        throw new AssertionError("missing decision " + asset);
    }

    private static void assertNotFrozenForAsset(
            ObjectNode evidence, String candidate, String asset) {
        JsonNode row = metric(evidence, candidate, asset);
        assertThat(row.path("execution").path("status").asText())
                .isEqualTo("NOT_FROZEN_FOR_ASSET");
        assertThat(row.path("metrics").path("completed_trades").asInt()).isZero();
        assertThat(evidence.path("trades")).noneSatisfy(trade -> {
            assertThat(trade.path("candidate_id").asText()).isEqualTo(candidate);
            assertThat(trade.path("asset").asText()).isEqualTo(asset);
        });
    }

    private static ObjectNode manifestDataset(String asset, String hashCharacter) {
        return object().put("dataset_id", asset).put("asset", asset)
                .put("venue", "fixture").put("row_count", 13)
                .put("min_time", START).put("max_time", START + 12 * BAR_MS)
                .put("source_sha256", hashCharacter.repeat(64))
                .put("availability_time_policy", "completed-bar")
                .put("point_in_time_status", "VERIFIED")
                .put("revision_status", "ORIGINAL");
    }

    private static String candidateFor(ObjectNode candidates, String family) {
        for (JsonNode candidate : candidates.path("candidates")) {
            if (family.equals(candidate.path("definition").path("setup_family").asText())) {
                return candidate.path("candidate_id").asText();
            }
        }
        throw new AssertionError("missing candidate " + family);
    }

    private static ArrayNode wfoRows(String asset) {
        ArrayNode rows = array();
        for (int index = 0; index < 13; index++) {
            String family = index % 2 == 0
                    ? "FK_REVERSAL_RECLAIM" : "FK_SUPPORT_RECLAIM";
            int signalIndex = index - 1;
            String signalFamily = signalIndex < 0 ? null : signalIndex % 2 == 0
                    ? "FK_REVERSAL_RECLAIM" : "FK_SUPPORT_RECLAIM";
            boolean trainSignal = signalIndex >= 0 && signalIndex < 4;
            boolean testSignal = signalIndex >= 8 && signalIndex < 12;
            boolean positive = trainSignal && "FK_REVERSAL_RECLAIM".equals(signalFamily)
                    || signalIndex == 3
                    || testSignal && "FK_SUPPORT_RECLAIM".equals(signalFamily);
            boolean negative = trainSignal && "FK_SUPPORT_RECLAIM".equals(signalFamily)
                    && signalIndex != 3
                    || testSignal && "FK_REVERSAL_RECLAIM".equals(signalFamily);
            ObjectNode row = object().put("time", START + index * BAR_MS)
                    .put("asset", asset).put("timeframe", "4h")
                    .put("framework", "fallen_knives").put("open", 100)
                    .put("high", positive ? 103 : 100.5)
                    .put("low", negative ? 97 : 99.5)
                    .put("close", positive ? 102 : 99)
                    .put("mechanical_score", 20).put("flow_aligned_rows", 5)
                    .put("setup_family", family);
            row.set("trigger", object().put("valid", true).put("timeframe", "4h")
                    .put("completed_bar", true).put("age_bars", 0));
            row.set("protective_controls", object().put("stop_valid", true)
                    .put("time_stop_valid", true).put("ratchet_valid", true)
                    .put("carry_veto", false));
            rows.add(row);
        }
        return rows;
    }

    private static ObjectNode window(long start, long end) {
        return object().put("start", start).put("end", end);
    }

    private static ObjectNode acceptance() {
        ObjectNode value = object();
        value.set("robust_stats", object().put("max_statistic_p_value", 0.1)
                .put("minimum_bootstrap_p20_expectancy_r", 0)
                .put("minimum_effective_independent_episode_count", 3));
        value.set("plateau", object().put("minimum_neighbor_count", 1)
                .put("minimum_profitable_neighbor_fraction", 0.5)
                .put("minimum_plateau_size", 2));
        ObjectNode stress = object();
        stress.set("required_scenarios", array()
                .add(scenario("fee_slippage").put("multiplier", 2))
                .add(scenario("funding_carry").put("multiplier", 2))
                .add(scenario("adverse_execution_gap").put("debit_r", 0.1))
                .add(scenario("liquidity_capacity").put("maximum_participation_rate", 0.05))
                .add(scenario("venue_outage_blackout").set("windows", array().add(
                        object().put("venue", "binance")
                                .put("start", "2027-01-01T00:00:00Z")
                                .put("end", "2027-01-02T00:00:00Z")))));
        value.set("stress", stress);
        value.set("portfolio", object().put("minimum_accepted_trades", 3)
                .put("maximum_drawdown_pct", 2).put("minimum_net_pnl", 0)
                .put("minimum_final_equity", 10_000));
        return value;
    }

    private static ObjectNode scenario(String id) {
        return object().put("id", id).put("minimum_expectancy_r", -1)
                .put("minimum_observations", 1);
    }

    private static ObjectNode precommit() {
        ObjectNode value = object().put("schema", "strategy-precommit/1")
                .put("precommit_id", "v2-fixture").put("created_at", CREATED_AT)
                .put("stage", "CORE_PREMISE")
                .put("phenomenon", "forced crypto deleveraging followed by inventory repair")
                .put("economic_behavioral_mechanism",
                        "forced sellers transfer inventory to patient liquidity providers")
                .put("persistence", "margin clearing takes several completed bars")
                .put("crowding_decay", "copied entries compress the rebound")
                .put("direction", "long").put("expression", "BTC spot")
                .put("failure_invalidation_mechanism", "episodes cease to predict repair")
                .put("role_of_composite_score", "No composite score or score threshold.");
        value.set("participants", object().put("forced_actor", "levered trader")
                .put("edge_provider", "liquidity provider")
                .put("edge_consumer", "swing trader"));
        value.set("holding_horizon", object().put("min", 1).put("max", 30)
                .put("unit", "days"));
        value.set("expected_signal_frequency", object().put("min", 1).put("max", 8)
                .put("unit", "per month"));
        value.set("expected_win_rate", object().put("min", 0.35).put("max", 0.65));
        ObjectNode payoff = object().put("qualitative_shape", "asymmetric right tail");
        payoff.set("average_win_r", object().put("min", 1).put("max", 3));
        payoff.set("average_loss_r", object().put("min", -1.5).put("max", -0.5));
        value.set("payoff", payoff);
        value.set("regimes", object().set("expected_to_work", array().add("fear")));
        ((ObjectNode) value.path("regimes")).set("expected_to_fail", array().add("insolvency"));
        value.set("required_inputs", featureContract().path("inputs"));
        ObjectNode falsifier = object().put("test", "event-block null")
                .put("null", "no positive expectancy");
        falsifier.set("rejection_thresholds", object().put("expectancy_r", 0));
        value.set("falsifier", falsifier);
        value.set("tradable_instrument_contract", object().put("universe", "CRYPTO_ONLY")
                .set("instruments", array().add(cryptoSpot("btc"))));
        value.set("non_crypto_context_only", array().add(object()
                .put("input_id", "real-yield-context").put("asset", "us-real-yield")
                .put("asset_class", "rate").put("context_only", true)
                .put("tradable", false)));
        value.set("independence_replication_groups", array().add("crypto-flow"));
        return value;
    }

    private static ObjectNode featureContract() {
        ObjectNode setup = object().put("input_id", "setup-flow")
                .put("evidence_family", "crypto-flow").put("role", "SETUP");
        setup.set("availability", object().put("rule", "completed 4h bar close"));
        setup.set("point_in_time", object().put("status", "VERIFIED")
                .put("completed_bar_only", true));
        ObjectNode context = object().put("input_id", "real-yield-context")
                .put("evidence_family", "macro-rates").put("role", "CONTEXT");
        context.set("availability", object().put("rule", "first public release timestamp"));
        context.set("point_in_time", object().put("status", "PIT_SAFE"));
        ObjectNode value = object();
        value.set("series", array()
                .add(object().put("series_id", "btc-4h").put("asset", "btc")
                        .put("asset_class", "crypto").put("timeframe", "4h")
                        .put("context_only", false).put("tradable", true)
                        .set("point_in_time", object().put("status", "VERIFIED")
                                .put("completed_bar_only", true)))
                .add(object().put("series_id", "real-yield-daily")
                        .put("asset", "us-real-yield").put("asset_class", "rate")
                        .put("timeframe", "1d").put("context_only", true)
                        .put("tradable", false).set("point_in_time",
                                object().put("status", "PIT_SAFE"))));
        value.set("inputs", array().add(setup).add(context));
        return value;
    }

    private static ObjectNode cryptoSpot(String asset) {
        return object().put("asset", asset).put("asset_class", "crypto")
                .put("instrument_type", "spot");
    }

    private static Path writeJson(Path path, JsonNode value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, LegacyNodeOracle.MAPPER.writeValueAsString(value),
                StandardCharsets.UTF_8);
        return path;
    }

    private static ObjectNode parse(String json) throws Exception {
        return (ObjectNode) LegacyNodeOracle.MAPPER.readTree(json);
    }

    private static CliResult cli(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = LegacyResearchCommandAdapter.run(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new CliResult(exit, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record Fixture(ObjectNode precommit, ObjectNode definition,
                           ObjectNode experiment, ObjectNode candidates,
                           ObjectNode store, ObjectNode manifest, long trainEnd,
                           String reversalCandidate, String supportCandidate) {}

    private record CliResult(int exit, String stdout, String stderr) {}
}
