package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import com.tradinganalytics.marketdata.research.ResearchData;
import com.tradinganalytics.research.swing.SwingEngine;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.tradinganalytics.research.legacy.LegacyResearchSupport.*;

/** Unregistered command adapter for the legacy branches of {@code strategy-research.mjs}. */
public final class LegacyResearchCommandAdapter {
    public static final String USAGE = "usage: strategy-research.mjs precommit|generate|evaluate|"
            + "evaluate-v3|run|v3-validate|v3-metrics|v3-accept|wfo-v3|"
            + "acceptance-contract|freeze-confirmation|burn-confirmation|verify-attestation|"
            + "import-attestation|stats|plateau|ablations|portfolio|stress|monitor|record|"
            + "validate|rebuild-index|list|show|compare|import-legacy|readiness|"
            + "readiness-audit|deployment-audit|next-stats|next-validate|data-backfill|"
            + "opportunity-envelope|search-genetic|research-run|overfit-audit|"
            + "prospective-runner|index\n";

    private static final Set<String> DEFERRED_NEXT_COMMANDS = Set.of(
            "readiness", "next-stats", "next-validate");
    private static final Set<String> DEFERRED_V5_COMMANDS = Set.of(
            "data-backfill", "opportunity-envelope", "search-genetic", "research-run",
            "overfit-audit", "prospective-runner", "readiness-audit", "deployment-audit",
            "index");
    private static final ResearchSchemaRegistry SCHEMAS = ResearchSchemaRegistry.defaultRegistry();

    private LegacyResearchCommandAdapter() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        String command = args.length == 0 ? null : args[0];
        Map<String, String> options = flags(args, 1);
        Path root = resolve(options.getOrDefault("root", "strategy-research"));
        try {
            if ("generate".equals(command)
                    && "GENETIC".equalsIgnoreCase(options.getOrDefault("method", ""))) {
                throw new IllegalArgumentException("static generate --method GENETIC is rejected; "
                        + "use the authoritative search-genetic command");
            }
            if (command != null && DEFERRED_NEXT_COMMANDS.contains(command)) {
                throw new IllegalArgumentException("strategy-research-next command is not part of "
                        + "the legacy adapter: " + command);
            }
            if ((command != null && DEFERRED_V5_COMMANDS.contains(command))
                    || ("validate".equals(command) && inputIsV5(options.get("input")))) {
                throw new IllegalArgumentException("authoritative v5 command is not part of the "
                        + "legacy adapter: " + command);
            }

            switch (command == null ? "" : command) {
                case "acceptance-contract" -> acceptanceContract(options, out);
                case "freeze-confirmation" -> freezeConfirmation(root, options, out);
                case "verify-attestation" -> verifyAttestation(options, out, false);
                case "import-attestation" -> verifyAttestation(options, out, true);
                case "v3-validate" -> validateV3(options, out);
                case "evaluate-v3" -> evaluateV3(options, out);
                case "v3-metrics" -> metricsV3(options, out);
                case "v3-accept" -> acceptV3(options, out);
                case "wfo-v3" -> print(out, LegacyResearchV3.walkForwardV3(
                        read(required(options, "input"))));
                case "burn-confirmation" -> burnConfirmation(options, out);
                case "precommit" -> precommit(root, options, out);
                case "generate" -> generate(root, options, out);
                case "evaluate" -> evaluateV2(root, options, out);
                case "run" -> runExperiment(root, options, out);
                case "record" -> record(root, options, out);
                case "stats" -> stats(options, out);
                case "plateau" -> plateau(options, out);
                case "ablations" -> ablations(options, out);
                case "portfolio" -> portfolio(options, out);
                case "stress" -> print(out, LegacyResearchV2.runStressSuite(
                        read(required(options, "trades")), read(required(options, "suite"))));
                case "monitor" -> print(out, LegacyResearchV2.compareProspectiveExpectation(
                        read(required(options, "profile")), read(required(options, "evidence"))));
                case "validate" -> validate(root, options, out);
                case "rebuild-index" -> rebuildIndex(root, out);
                case "list" -> list(root, options, out);
                case "show" -> show(root, options, out);
                case "compare" -> compare(root, options, out);
                case "import-legacy" -> importLegacy(root, options, out);
                default -> out.print(USAGE);
            }
            return 0;
        } catch (RuntimeException error) {
            err.println(message(error));
            return 1;
        }
    }

    private static void acceptanceContract(Map<String, String> options, PrintStream out) {
        ObjectNode input = JSON.objectNode()
                .put("contractId", options.getOrDefault("id", "balanced-swing-v1"))
                .put("profile", options.getOrDefault("profile", "balanced-swing-v1"));
        ObjectNode contract = LegacyResearchV3.makeAcceptanceContract(input);
        if (options.containsKey("out")) {
            LegacyResearchV1.writeImmutable(resolve(options.get("out")), contract);
        }
        print(out, contract);
    }

    private static void freezeConfirmation(
            Path root, Map<String, String> options, PrintStream out) {
        JsonNode contract = options.containsKey("acceptance")
                ? read(options.get("acceptance")) : LegacyResearchV3.makeAcceptanceContract();
        LegacyResearchV3.validateAcceptanceContract(contract);
        ObjectNode input = JSON.objectNode();
        put(input, "sealId", first(options, "seal_id", "seal"));
        put(input, "repository", options.get("repository"));
        put(input, "commitSha", first(options, "commit_sha", "commit"));
        put(input, "workflowSha256", first(options, "workflow_sha256", "workflow"));
        for (String key : List.of("precommit_sha256", "definition_sha256",
                "experiment_sha256", "candidate_set_sha256", "data_root_sha256",
                "container_sha256", "executor_sha256")) {
            put(input, camel(key), options.get(key));
        }
        input.put("acceptanceContractSha256", text(contract.get("content_sha256")));
        put(input, "experimentPath", options.get("experiment_path"));
        put(input, "dataPath", options.get("data_path"));
        put(input, "output", options.get("output"));
        ObjectNode reservation = LegacyResearchV3.makeConfirmationReservation(input);
        Path destination = resolve(options.getOrDefault("out",
                root.resolve("confirmations").resolve(
                        text(reservation.get("seal_id")) + ".json").toString()));
        ObjectNode validation = JSON.objectNode();
        put(validation, "repository", options.get("repository"));
        validation.put("currentCommit", currentCommit());
        validation.put("workflowPath", options.getOrDefault(
                "workflow_path", ".github/workflows/strategy-confirmation.yml"));
        validation.put("reservationPath", destination.toString());
        LegacyResearchV3.validateConfirmationReservation(reservation, validation);
        LegacyResearchV1.writeImmutable(destination, reservation);
        ObjectNode result = JSON.objectNode().put("path", destination.toString());
        result.set("reservation", reservation);
        print(out, result);
    }

    private static void verifyAttestation(
            Map<String, String> options, PrintStream out, boolean importRecord) {
        JsonNode attestation = read(required(options, "attestation"));
        JsonNode reservation = options.containsKey("reservation")
                ? read(options.get("reservation")) : NullNode.instance;
        ObjectNode input = JSON.objectNode();
        input.put("publicKeyPem", readText(required(options, "public_key")));
        input.set("reservation", reservation);
        put(input, "expectedRepository", options.get("repository"));
        put(input, "expectedCommitSha", options.get("commit_sha"));
        put(input, "expectedRunId", options.get("run_id"));
        put(input, "reservationPath", options.get("reservation"));
        input.put("workflowPath", options.getOrDefault(
                "workflow_path", ".github/workflows/strategy-confirmation.yml"));
        input.put("burnRoot", options.getOrDefault("burn_root", ".research-run/burn"));
        put(input, "out", options.get("out"));
        print(out, importRecord
                ? LegacyResearchV3.importAttestation(attestation, input)
                : LegacyResearchV3.verifyAttestation(attestation, input));
    }

    private static void validateV3(Map<String, String> options, PrintStream out) {
        JsonNode experiment = read(required(options, "experiment"));
        JsonNode acceptance = options.containsKey("acceptance")
                ? read(options.get("acceptance")) : experiment.get("acceptance_contract");
        SCHEMAS.validateContractSchema(experiment);
        LegacyResearchV3.validateExperimentV3(experiment, acceptance, null);
        ObjectNode result = JSON.objectNode().put("valid", true)
                .put("schema", text(experiment.get("schema")));
        putNullable(result, "acceptance_contract_sha256",
                acceptance == null ? null : acceptance.get("content_sha256"));
        print(out, result);
    }

    private static void evaluateV3(Map<String, String> options, PrintStream out) {
        if (options.containsKey("metrics") || options.containsKey("trades")) {
            throw new IllegalArgumentException(
                    "evaluate-v3 never accepts caller-authored metrics or trades");
        }
        for (String key : List.of("experiment", "manifest", "features", "labels", "candidates")) {
            if (!options.containsKey(key)) {
                throw new IllegalArgumentException("evaluate-v3 requires frozen experiment, "
                        + "manifest, feature set, label set and candidate set");
            }
        }
        Path experimentPath = resolve(options.get("experiment"));
        Path manifestPath = resolve(options.get("manifest"));
        Path featurePath = resolve(options.get("features"));
        Path featureSetPath = featurePath.toString().endsWith(".json")
                ? featurePath : Path.of(featurePath.toString()
                .replaceFirst("\\.(parquet|jsonl)$", ".json"));
        JsonNode experiment = read(experimentPath);
        JsonNode manifest = read(manifestPath);
        JsonNode featureSet = read(featureSetPath);
        JsonNode labelSet = read(options.get("labels"));
        JsonNode candidates = read(options.get("candidates"));
        for (JsonNode contract : List.of(experiment, manifest, featureSet, labelSet)) {
            SCHEMAS.validateContractSchema(contract);
        }
        LegacyResearchV3.validateExperimentV3(
                experiment, experiment.get("acceptance_contract"), null);
        if (!text(featureSet.get("content_sha256")).equals(LegacyResearchV3.ownHash(featureSet))
                || !text(labelSet.get("content_sha256")).equals(
                LegacyResearchV3.ownHash(labelSet))) {
            throw new IllegalArgumentException("feature/label set retained-hash tampering");
        }
        if (!text(featureSet.get("content_sha256")).equals(
                text(experiment.get("feature_set_sha256")))
                || !text(labelSet.get("content_sha256")).equals(
                text(experiment.get("label_set_sha256")))) {
            throw new IllegalArgumentException("experiment feature/label set lineage mismatch");
        }
        if (present(candidates, "content_sha256")
                && !text(candidates.get("content_sha256")).equals(
                LegacyResearchV3.ownHash(candidates))) {
            throw new IllegalArgumentException("candidate set retained-hash tampering");
        }
        if (!LegacyResearchV3.DATA_MANIFEST_V2_SCHEMA.equals(text(manifest.get("schema")))) {
            throw new IllegalArgumentException(
                    "evaluate-v3 requires strategy-data-manifest/2");
        }
        List<String> assets = requiredAssets(experiment);
        ResearchData.validateManifest((ObjectNode) manifest,
                new ResearchData.ValidationOptions(text(experiment.get("evidence_phase")),
                        assets, manifestPath.getParent().getParent()));
        if (!text(experiment.get("data_manifest_sha256")).equals(
                text(manifest.get("content_sha256")))) {
            throw new IllegalArgumentException("experiment/data manifest lineage mismatch");
        }
        if (!text(featureSet.get("data_manifest_sha256")).equals(
                text(manifest.get("content_sha256")))
                || featureSet.path("labels_allowed").asBoolean()) {
            throw new IllegalArgumentException("feature set is not bound to the frozen data "
                    + "manifest or permits labels");
        }
        if (!text(labelSet.get("data_manifest_sha256")).equals(
                text(manifest.get("content_sha256")))
                || labelSet.path("predictor_eligible").asBoolean(true)) {
            throw new IllegalArgumentException("label set is not bound to the frozen data "
                    + "manifest or is predictor-eligible");
        }
        String candidateHash = present(candidates, "content_sha256")
                ? LegacyResearchV3.ownHash(candidates) : LegacyResearchV3.hash(candidates);
        ObjectNode withoutHash = objectCopy(candidates, "candidate set");
        withoutHash.remove("content_sha256");
        if (!text(experiment.get("candidate_set_sha256")).equals(candidateHash)
                && !text(experiment.get("candidate_set_sha256")).equals(
                LegacyResearchV3.hash(withoutHash))) {
            throw new IllegalArgumentException("experiment/candidate set lineage mismatch");
        }
        ArrayNode featureRows = readFeatureRows(featurePath, featureSet, manifestPath);
        ObjectNode evaluation = JSON.objectNode();
        evaluation.set("experiment", experiment);
        evaluation.set("manifest", manifest);
        evaluation.set("featureSet", featureSet);
        evaluation.set("labelSet", labelSet);
        evaluation.set("candidates", candidates);
        evaluation.set("featureRows", featureRows);
        if (options.containsKey("parent_evidence")) {
            evaluation.set("parentEvidence", read(options.get("parent_evidence")));
        }
        if (options.containsKey("precommit")) {
            evaluation.set("precommit", read(options.get("precommit")));
        }
        if (options.containsKey("definition")) {
            evaluation.set("definition", read(options.get("definition")));
        }
        ObjectNode result = LegacyStrategyResearch.evaluateLocalV3(evaluation);
        SCHEMAS.validateContractSchema(result.get("bundle"));
        SCHEMAS.validateContractSchema(result.get("run"));
        ObjectNode output = JSON.objectNode()
                .put("schema", text(result.path("bundle").get("schema")))
                .put("content_sha256", text(result.path("bundle").get("content_sha256")))
                .put("run_id", text(result.path("run").get("run_id")));
        output.set("decisions", cloneNode(result.path("run").get("decisions")));
        output.set("acceptance", cloneNode(result.get("acceptance")));
        output.set("selected_by_asset", cloneNode(result.get("selected_by_asset")));
        if (options.containsKey("out")) {
            Path destination = resolve(options.get("out"));
            writeContentAddressed(destination, result.get("bundle"));
            output.put("out", destination.toString());
        }
        if (options.containsKey("record_root")) {
            Path recordRoot = resolve(options.get("record_root"));
            recordV3(recordRoot, result, output);
        }
        print(out, output);
    }

    private static void metricsV3(Map<String, String> options, PrintStream out) {
        if (options.containsKey("phase")
                && !"DEVELOPMENT".equals(options.get("phase"))) {
            throw new IllegalArgumentException("v3-metrics caller-trade diagnostic is "
                    + "DEVELOPMENT-only; authoritative phases require evaluate-v3");
        }
        JsonNode trades = read(required(options, "trades"));
        ObjectNode metricOptions = JSON.objectNode()
                .put("candidateId", options.getOrDefault("candidate", ""))
                .put("asset", options.getOrDefault("asset", ""))
                .put("candidateCount", integer(options, "candidate_count", 1))
                .put("initialEquity", number(options, "initial_equity", 100_000))
                .put("seed", integer(options, "seed", 1))
                .put("bootstrapIterations", integer(options, "iterations", 2_000));
        ObjectNode metrics = LegacyResearchV3.computeCandidateMetrics(trades, metricOptions);
        if (options.containsKey("acceptance")) {
            metrics.set("acceptance", LegacyResearchV3.evaluateAcceptance(
                    metrics, read(options.get("acceptance"))));
        }
        print(out, metrics);
    }

    private static void acceptV3(Map<String, String> options, PrintStream out) {
        JsonNode metrics = read(required(options, "metrics"));
        JsonNode contract = options.containsKey("acceptance")
                ? read(options.get("acceptance")) : LegacyResearchV3.makeAcceptanceContract();
        ObjectNode acceptOptions = JSON.objectNode().put(
                "phase", options.getOrDefault("phase", "DEVELOPMENT"));
        if (options.containsKey("stress")) acceptOptions.set("stress", read(options.get("stress")));
        if (options.containsKey("portfolio")) {
            acceptOptions.set("portfolio", read(options.get("portfolio")));
        }
        print(out, LegacyResearchV3.evaluateAcceptance(metrics, contract, acceptOptions));
    }

    private static void burnConfirmation(Map<String, String> options, PrintStream out) {
        JsonNode reservation = read(required(options, "reservation"));
        Path burned = LegacyResearchV3.burnReservation(reservation,
                resolve(options.getOrDefault("burn_root", ".research-run/burn")));
        print(out, JSON.objectNode().put("burned", burned.toString()));
    }

    private static void precommit(Path root, Map<String, String> options, PrintStream out) {
        String input = options.get("input");
        if (input == null || input.isEmpty()) {
            // The source resolves an absent flag to the current directory and
            // exposes Node's read-directory failure before its dead guard.
            throw new IllegalArgumentException("EISDIR: illegal operation on a directory, read");
        }
        ObjectNode frozen = LegacyResearchV2.freezePrecommit(read(input));
        Path destination = resolve(options.getOrDefault("out",
                root.resolve("precommits").resolve(
                        text(frozen.get("precommit_id")) + ".json").toString()));
        LegacyResearchV1.writeImmutable(destination, frozen);
        Path markdown = resolve(options.getOrDefault("markdown",
                destination.toString().replaceFirst("(?i)\\.json$", ".md")));
        writeTextImmutable(markdown, LegacyResearchV2.renderPremiseMarkdown(frozen));
        print(out, JSON.objectNode()
                .put("precommit", destination.toString())
                .put("markdown", markdown.toString())
                .put("sha256", text(frozen.get("content_sha256")))
                .put("immutable", true));
    }

    private static void generate(Path root, Map<String, String> options, PrintStream out) {
        if (!options.containsKey("precommit")) {
            JsonNode definition = read(required(options, "definition"));
            throw new IllegalArgumentException("new generation requires strategy-precommit/1 "
                    + "and strategy-definition/2; " + fallback(text(definition.get("schema")),
                    "missing schema") + " is legacy/read-only for generation");
        }
        ObjectNode precommit = LegacyResearchV2.freezePrecommit(read(options.get("precommit")));
        LegacyResearchV2.validatePrecommit(precommit);
        JsonNode source;
        if (options.containsKey("definition")) source = read(options.get("definition"));
        else if (precommit.path("definition").isObject()) source = precommit.get("definition");
        else {
            ObjectNode inline = JSON.objectNode();
            putNullable(inline, "candidate_template", precommit.get("candidate_template"));
            putNullable(inline, "feature_contract", precommit.get("feature_contract"));
            putNullable(inline, "tradable_instrument_contract",
                    precommit.get("tradable_instrument_contract"));
            source = inline;
        }
        if (source == null || !source.path("candidate_template").isObject()
                || !source.path("feature_contract").isObject()) {
            throw new IllegalArgumentException("generate requires explicit precommit.definition "
                    + "(or candidate_template and feature_contract); it will not invent a hypothesis");
        }
        ObjectNode definition;
        if (LegacyResearchV2.DEFINITION_V2_SCHEMA.equals(text(source.get("schema")))) {
            definition = objectCopy(source, "definition");
        } else {
            ObjectNode input = JSON.objectNode();
            input.set("precommit", precommit);
            input.put("strategy_id", truthyText(source.get("strategy_id"),
                    truthyText(precommit.get("strategy_id"), text(precommit.get("precommit_id")))));
            input.put("version", truthyText(source.get("version"),
                    options.getOrDefault("version", "v001")));
            input.put("created_at", truthyText(source.get("created_at"),
                    text(precommit.get("created_at"))));
            input.put("stage", truthyText(source.get("stage"), text(precommit.get("stage"))));
            input.set("candidate_template", cloneNode(source.get("candidate_template")));
            input.set("feature_contract", cloneNode(source.get("feature_contract")));
            input.set("tradable_instrument_contract",
                    present(source, "tradable_instrument_contract")
                            ? cloneNode(source.get("tradable_instrument_contract"))
                            : cloneNode(precommit.get("tradable_instrument_contract")));
            input.put("hypothesis_family", truthyText(source.get("hypothesis_family"),
                    truthyText(precommit.get("hypothesis_family"),
                            text(precommit.get("precommit_id")))));
            putNullable(input, "parent_evidence", source.get("parent_evidence"));
            putNullable(input, "score_free_baseline_sha256",
                    source.get("score_free_baseline_sha256"));
            definition = LegacyResearchV2.makeV2Definition(input);
        }
        LegacyResearchV2.validateDefinitionV2(definition, precommit);
        JsonNode experimentSource = options.containsKey("experiment")
                ? read(options.get("experiment"))
                : precommit.path("experiment").isObject()
                ? precommit.get("experiment") : JSON.objectNode();
        ArrayNode contractAssets = JSON.arrayNode();
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode item : rows(precommit.path("tradable_instrument_contract")
                .get("instruments"))) {
            String asset = item.isTextual() ? text(item) : text(item.get("asset"));
            if (!asset.isEmpty() && unique.add(asset)) contractAssets.add(asset);
        }
        ObjectNode experiment = JSON.objectNode()
                .put("schema", LegacyResearchV2.EXPERIMENT_V2_SCHEMA)
                .put("experiment_id", truthyText(experimentSource.get("experiment_id"),
                        options.getOrDefault("id", text(precommit.get("precommit_id")) + "-core")))
                .put("created_at", truthyText(experimentSource.get("created_at"),
                        text(precommit.get("created_at"))))
                .put("stage", truthyText(experimentSource.get("stage"),
                        text(definition.get("stage"))))
                .put("evidence_phase", truthyText(experimentSource.get("evidence_phase"),
                        options.getOrDefault("phase", "DEVELOPMENT")))
                .put("hypothesis_family", truthyText(experimentSource.get("hypothesis_family"),
                        text(definition.get("hypothesis_family"))))
                .put("ablation_role", truthyText(experimentSource.get("ablation_role"),
                        experimentSource.path("grid").isObject()
                                && !experimentSource.path("grid").isEmpty()
                                ? "PARAMETER_SEARCH" : "NO_SELECTION_SEARCH"));
        experiment.set("definition", JSON.objectNode()
                .put("path", "definitions/" + text(definition.get("strategy_id")) + "/"
                        + text(definition.get("version")) + ".json")
                .put("sha256", LegacyResearchV2.hash(definition)));
        putNullable(experiment, "parent_evidence",
                present(experimentSource, "parent_evidence")
                        ? experimentSource.get("parent_evidence") : definition.get("parent_evidence"));
        experiment.set("evidence_family_ids",
                present(experimentSource, "evidence_family_ids")
                        ? cloneNode(experimentSource.get("evidence_family_ids"))
                        : cloneNode(precommit.path("independence_replication_groups")));
        experiment.set("grid", experimentSource.path("grid").isObject()
                ? cloneNode(experimentSource.get("grid")) : JSON.objectNode());
        experiment.set("parameter_topology",
                experimentSource.path("parameter_topology").isObject()
                        ? cloneNode(experimentSource.get("parameter_topology")) : JSON.objectNode());
        if (present(experimentSource, "evaluation_chronology")) {
            experiment.set("evaluation_chronology",
                    cloneNode(experimentSource.get("evaluation_chronology")));
        }
        if (present(experimentSource, "portfolio_policy")) {
            experiment.set("portfolio_policy", cloneNode(experimentSource.get("portfolio_policy")));
        }
        experiment.set("required_assets", present(experimentSource, "required_assets")
                ? cloneNode(experimentSource.get("required_assets")) : contractAssets);
        putNullable(experiment, "acceptance", experimentSource.get("acceptance"));
        experiment.set("candidate_set", JSON.objectNode()
                .put("path", "candidates.json").putNull("sha256"));
        JsonNode baseline = present(experimentSource, "score_free_baseline_sha256")
                ? experimentSource.get("score_free_baseline_sha256")
                : definition.get("score_free_baseline_sha256");
        if (baseline != null && !baseline.isNull()) {
            experiment.set("score_free_baseline_sha256", cloneNode(baseline));
        }
        experiment = LegacyResearchV2.withHash(experiment);
        LegacyResearchV2.validateExperimentV2(experiment, definition);
        ObjectNode candidateInput = JSON.objectNode();
        candidateInput.set("definition", definition);
        candidateInput.set("experiment", experiment);
        ObjectNode candidateSet = LegacyResearchV2.designCandidates(candidateInput);
        LegacyResearchV2.validateCandidateSetV2(candidateSet, experiment);
        ObjectNode finalSource = experiment.deepCopy();
        finalSource.set("candidate_set", JSON.objectNode()
                .put("path", "candidates.json")
                .put("sha256", LegacyResearchV2.hash(candidateSet)));
        finalSource.remove("content_sha256");
        ObjectNode finalExperiment = LegacyResearchV2.withHash(finalSource);
        LegacyResearchV2.validateExperimentV2(finalExperiment, definition);

        Path definitionPath = root.resolve("definitions")
                .resolve(text(definition.get("strategy_id")))
                .resolve(text(definition.get("version")) + ".json").toAbsolutePath().normalize();
        Path experimentDir = root.resolve("experiments")
                .resolve(text(finalExperiment.get("experiment_id"))).toAbsolutePath().normalize();
        Path precommitPath = root.resolve("precommits")
                .resolve(text(precommit.get("precommit_id")) + ".json").toAbsolutePath().normalize();
        if (!Files.exists(precommitPath, LinkOption.NOFOLLOW_LINKS)) {
            LegacyResearchV1.writeImmutable(precommitPath, precommit);
        }
        LegacyResearchV1.writeImmutable(definitionPath, definition);
        LegacyResearchV1.writeImmutable(experimentDir.resolve("candidates.json"), candidateSet);
        LegacyResearchV1.writeImmutable(experimentDir.resolve("experiment.json"), finalExperiment);
        ObjectNode result = JSON.objectNode()
                .put("schema", LegacyResearchV2.DEFINITION_V2_SCHEMA)
                .put("precommit", precommitPath.toString())
                .put("definition", definitionPath.toString())
                .put("experiment", experimentDir.resolve("experiment.json").toString())
                .put("candidates", experimentDir.resolve("candidates.json").toString())
                .put("declared_k", candidateSet.path("declared_k").asInt())
                .put("effective_k", candidateSet.path("effective_k").asInt());
        result.set("hashes", JSON.objectNode()
                .put("precommit", text(precommit.get("content_sha256")))
                .put("definition", LegacyResearchV2.hash(definition))
                .put("experiment", LegacyResearchV2.hash(finalExperiment))
                .put("candidate_set", LegacyResearchV2.hash(candidateSet)));
        print(out, result);
    }

    private static void evaluateV2(Path root, Map<String, String> options, PrintStream out) {
        String features = first(options, "features", "feature_store");
        String manifestPath = first(options, "manifest", "data_manifest");
        if (!options.containsKey("experiment") || manifestPath == null || features == null
                || (!options.containsKey("out") && !options.containsKey("record_root"))) {
            throw new IllegalArgumentException("evaluate requires --experiment, --manifest, "
                    + "--features, and --out or --record-root");
        }
        Path experimentPath = resolve(options.get("experiment"));
        JsonNode experiment = read(experimentPath);
        if (!LegacyResearchV2.EXPERIMENT_V2_SCHEMA.equals(text(experiment.get("schema")))) {
            throw new IllegalArgumentException("evaluate requires strategy-experiment/2");
        }
        JsonNode definition = read(root.resolve(text(experiment.path("definition").get("path"))));
        JsonNode candidateSet = read(experimentPath.getParent()
                .resolve(text(experiment.path("candidate_set").get("path"))));
        JsonNode precommit = read(root.resolve(text(definition.path("precommit").get("path"))));
        JsonNode manifest = read(manifestPath);
        JsonNode featureStore;
        if (features.endsWith(".gz")) {
            try {
                featureStore = SwingEngine.readFeatureStoreArtifact(resolve(features));
            } catch (IOException error) {
                throw new IllegalArgumentException(error.getMessage(), error);
            }
        } else featureStore = read(features);
        ObjectNode input = JSON.objectNode();
        input.set("experiment", experiment);
        input.set("definition", definition);
        input.set("candidateSet", candidateSet);
        input.set("precommit", precommit);
        input.set("featureStore", featureStore);
        input.set("dataManifest", manifest);
        input.put("adapter", options.getOrDefault("adapter", "swing-engine/1"));
        input.put("featureStorePath", features);
        input.put("dataManifestPath", manifestPath);
        input.set("executorConfig", JSON.objectNode()
                .put("same_bar_collision", options.getOrDefault("same_bar_collision", "stop-first"))
                .put("timezone", options.getOrDefault("timezone", "UTC"))
                .put("bar_convention", options.getOrDefault(
                        "bar_convention", "completed-bar-next-open")));
        ObjectNode bundle = LegacyResearchV2.evaluateAuthoritative(input);
        ObjectNode validate = JSON.objectNode();
        validate.set("experiment", experiment);
        validate.set("candidateSet", candidateSet);
        validate.set("dataManifest", manifest);
        validate.set("featureStore", featureStore);
        LegacyResearchV2.validateEvidenceBundle(bundle, validate);
        ObjectNode result = JSON.objectNode();
        if (options.containsKey("out")) {
            Path destination = resolve(options.get("out"));
            writeContentAddressed(destination, bundle);
            result.put("out", destination.toString());
        }
        if (options.containsKey("record_root")) {
            Path recordRoot = resolve(options.get("record_root"));
            Path evidencePath = recordRoot.resolve("evidence-bundles")
                    .resolve(text(bundle.get("content_sha256")) + ".json");
            writeContentAddressed(evidencePath, bundle);
            ObjectNode runInput = JSON.objectNode();
            runInput.set("bundle", bundle);
            runInput.set("precommit", precommit);
            runInput.set("definition", definition);
            runInput.set("experiment", experiment);
            runInput.set("candidateSet", candidateSet);
            put(runInput, "generated_at", options.get("generated_at"));
            ObjectNode run = LegacyResearchV2.makeAuthoritativeRun(runInput);
            Path runPath = recordRoot.resolve("runs").resolve(text(run.get("run_id")))
                    .resolve("run.json");
            writeContentAddressed(runPath, run);
            result.put("record_root", recordRoot.toString());
            result.put("evidence_bundle", evidencePath.toString());
            result.put("run", runPath.toString());
            result.put("run_id", text(run.get("run_id")));
        }
        result.put("schema", text(bundle.get("schema")));
        result.put("content_sha256", text(bundle.get("content_sha256")));
        result.put("runtime_behavioral_k",
                bundle.path("candidate_accounting").path("runtime_behavioral_k").asInt());
        result.set("decisions", cloneNode(bundle.get("decisions")));
        result.set("reconciliation", cloneNode(bundle.get("reconciliation")));
        print(out, result);
    }

    private static void runExperiment(Path root, Map<String, String> options, PrintStream out) {
        Path experimentPath = resolve(required(options, "experiment"));
        JsonNode experiment = read(experimentPath);
        JsonNode definition = read(root.resolve(text(experiment.path("definition").get("path"))));
        if (LegacyResearchV2.EXPERIMENT_V2_SCHEMA.equals(text(experiment.get("schema")))) {
            JsonNode candidateSet = read(experimentPath.getParent()
                    .resolve(text(experiment.path("candidate_set").get("path"))));
            JsonNode precommit = read(root.resolve(text(definition.path("precommit").get("path"))));
            ObjectNode input = JSON.objectNode();
            input.set("precommit", precommit);
            input.set("definition", definition);
            input.set("experiment", experiment);
            input.set("candidateSet", candidateSet);
            for (String field : List.of("metrics", "trades", "portfolio", "stress", "prospective")) {
                if (options.containsKey(field)) input.set(field, read(options.get(field)));
            }
            put(input, "generated_at", options.get("generated_at"));
            ObjectNode run = LegacyResearchV2.makeV2Run(input);
            Path runPath = root.resolve("runs").resolve(text(run.get("run_id")))
                    .resolve("run.json").toAbsolutePath().normalize();
            LegacyResearchV1.writeImmutable(runPath, run);
            ObjectNode result = JSON.objectNode()
                    .put("run", runPath.toString())
                    .put("run_id", text(run.get("run_id")))
                    .put("schema", text(run.get("schema")));
            result.set("decisions", cloneNode(run.get("decisions")));
            result.set("hashes", JSON.objectNode()
                    .put("precommit", text(run.get("precommit_sha256")))
                    .put("definition", text(run.get("definition_sha256")))
                    .put("experiment", text(run.get("experiment_sha256")))
                    .put("candidate_set", text(run.get("candidate_set_sha256"))));
            print(out, result);
            return;
        }
        JsonNode featureStore = read(required(options, "features"));
        Path runRoot = LegacyResearchV1.writeRunBundle(root,
                LegacyResearchV1.runExperiment(experiment, definition, featureStore));
        print(out, JSON.objectNode().put("run", runRoot.toString()));
    }

    private static void record(Path root, Map<String, String> options, PrintStream out) {
        Path inputPath = resolve(required(options, "input"));
        JsonNode value = read(inputPath);
        String schema = text(value.get("schema"));
        Path path;
        ObjectNode result = JSON.objectNode();
        switch (schema) {
            case LegacyResearchV2.DEFINITION_V2_SCHEMA -> {
                LegacyResearchV2.validateDefinitionV2(value);
                path = root.resolve("definitions").resolve(text(value.get("strategy_id")))
                        .resolve(text(value.get("version")) + ".json").toAbsolutePath().normalize();
                LegacyResearchV1.writeImmutable(path, value);
                result.put("path", path.toString()).put("schema", schema);
            }
            case LegacyResearchV2.PRECOMMIT_SCHEMA -> {
                ObjectNode frozen = LegacyResearchV2.freezePrecommit(value);
                path = root.resolve("precommits").resolve(
                        text(frozen.get("precommit_id")) + ".json").toAbsolutePath().normalize();
                LegacyResearchV1.writeImmutable(path, frozen);
                result.put("path", path.toString()).put("schema", schema)
                        .put("sha256", text(frozen.get("content_sha256")));
            }
            case LegacyResearchV1.DEFINITION_SCHEMA -> {
                LegacyResearchV1.validateDefinition(value);
                path = root.resolve("definitions").resolve(text(value.get("strategy_id")))
                        .resolve(text(value.get("version")) + ".json").toAbsolutePath().normalize();
                LegacyResearchV1.writeImmutable(path, value);
                result.put("path", path.toString());
            }
            case LegacyResearchV2.EXPERIMENT_V2_SCHEMA,
                    LegacyResearchV1.EXPERIMENT_SCHEMA -> {
                if (LegacyResearchV2.EXPERIMENT_V2_SCHEMA.equals(schema)) {
                    LegacyResearchV2.validateExperimentV2(value);
                } else LegacyResearchV1.validateExperiment(value);
                JsonNode candidateSet = read(inputPath.getParent()
                        .resolve(text(value.path("candidate_set").get("path"))));
                if (LegacyResearchV2.EXPERIMENT_V2_SCHEMA.equals(schema)) {
                    LegacyResearchV2.validateCandidateSetV2(candidateSet, value);
                }
                Path directory = root.resolve("experiments")
                        .resolve(text(value.get("experiment_id"))).toAbsolutePath().normalize();
                LegacyResearchV1.writeImmutable(directory.resolve("candidates.json"), candidateSet);
                path = directory.resolve("experiment.json");
                LegacyResearchV1.writeImmutable(path, value);
                result.put("path", path.toString());
                if (LegacyResearchV2.EXPERIMENT_V2_SCHEMA.equals(schema)) result.put("schema", schema);
            }
            default -> throw new IllegalArgumentException("unsupported record schema " + schema);
        }
        print(out, result);
    }

    private static void stats(Map<String, String> options, PrintStream out) {
        JsonNode candidates = read(required(options, "input"));
        ObjectNode statisticOptions = JSON.objectNode()
                .put("iterations", integer(options, "iterations", 1_000))
                .put("seed", integer(options, "seed", 1))
                .put("blockSize", integer(options, "block_size", 1));
        JsonNode bootstrap = NullNode.instance;
        if (options.containsKey("candidate")) {
            JsonNode candidate = rows(candidates).stream()
                    .filter(row -> options.get("candidate").equals(text(row.get("candidate_id"))))
                    .findFirst().orElse(NullNode.instance);
            bootstrap = LegacyResearchV2.blockBootstrapExpectancy(
                    candidate.path("rows"), statisticOptions);
        }
        ObjectNode result = JSON.objectNode();
        result.set("reality_check",
                LegacyResearchV2.candidateSetMaxStatisticPValue(candidates, statisticOptions));
        result.set("bootstrap", bootstrap);
        print(out, result);
    }

    private static void plateau(Map<String, String> options, PrintStream out) {
        JsonNode experiment = read(required(options, "experiment"));
        JsonNode candidates = read(required(options, "candidates"));
        ObjectNode input = JSON.objectNode();
        input.set("candidates", candidates.has("candidates")
                ? cloneNode(candidates.get("candidates")) : cloneNode(candidates));
        input.set("grid", cloneNode(experiment.get("grid")));
        input.set("metrics", read(required(options, "metrics")));
        input.put("candidate_id", required(options, "candidate"));
        print(out, LegacyResearchV2.plateauDiagnostics(input));
    }

    private static void ablations(Map<String, String> options, PrintStream out) {
        print(out, LegacyResearchV2.designContextAblations(read(required(options, "input"))));
    }

    private static void portfolio(Map<String, String> options, PrintStream out) {
        JsonNode signals = read(required(options, "signals"));
        JsonNode policy = read(required(options, "policy"));
        print(out, invokePortfolio(signals, policy));
    }

    private static void validate(Path root, Map<String, String> options, PrintStream out) {
        if (options.containsKey("input")) {
            JsonNode value = read(options.get("input"));
            print(out, JSON.objectNode()
                    .put("valid", LegacyResearchV2.validateV2Document(value))
                    .put("schema", text(value.get("schema"))));
        } else print(out, LegacyResearchV1.validateRegistry(root));
    }

    private static void rebuildIndex(Path root, PrintStream out) {
        ObjectNode result = JSON.objectNode();
        result.set("legacy", LegacyResearchV1.rebuildIndex(root));
        V3Index v3 = rebuildV3Index(root);
        result.set("v3", v3.index());
        result.put("v3_index", v3.path().toString());
        print(out, result);
    }

    private static void list(Path root, Map<String, String> options, PrintStream out) {
        Path v3Path = root.resolve("index-v3.json");
        JsonNode v3 = Files.exists(v3Path, LinkOption.NOFOLLOW_LINKS) ? read(v3Path) : null;
        if (v3 != null && LegacyResearchV3.ownHash(v3).equals(
                text(v3.get("content_sha256"))) == false) {
            throw new IllegalArgumentException("v3 index retained-hash tampering");
        }
        boolean useV3 = v3 != null && v3.path("runs").isArray() && !v3.path("runs").isEmpty()
                && (!options.containsKey("kind") || "runs".equals(options.get("kind")));
        JsonNode index = useV3 ? v3 : read(root.resolve("index.json"));
        String kind = useV3 ? "runs" : options.getOrDefault("kind", "performance");
        ArrayNode rows = JSON.arrayNode();
        for (JsonNode row : LegacyResearchSupport.rows(index.get(kind))) {
            boolean keep = true;
            for (String field : List.of("asset", "evidence_phase", "status",
                    "candidate_id", "experiment_id")) {
                if (options.containsKey(field)
                        && !options.get(field).equalsIgnoreCase(text(row.get(field)))) keep = false;
            }
            if (keep && options.containsKey("strategy")) {
                String strategy = text(row.get("strategy_id")) + "@" + text(row.get("version"));
                keep = options.get("strategy").equals(strategy)
                        || options.get("strategy").equals(text(row.get("candidate_id")));
            }
            if (keep) rows.add(cloneNode(row));
        }
        print(out, rows);
    }

    private static void show(Path root, Map<String, String> options, PrintStream out) {
        if (options.containsKey("strategy")) {
            String[] parts = options.get("strategy").split("@", -1);
            print(out, read(root.resolve("definitions").resolve(parts[0])
                    .resolve(parts.length > 1 ? parts[1] + ".json" : "undefined.json")));
        } else print(out, readRunView(root, findRun(root, required(options, "id"))));
    }

    private static void compare(Path root, Map<String, String> options, PrintStream out) {
        ObjectNode left = readRunView(root, findRun(root, required(options, "left")));
        ObjectNode right = readRunView(root, findRun(root, required(options, "right")));
        Map<String, JsonNode> rightMetrics = new LinkedHashMap<>();
        for (JsonNode row : LegacyResearchSupport.rows(right.get("metrics"))) {
            rightMetrics.put(metricKey(row), metricValue(row));
        }
        ArrayNode deltas = JSON.arrayNode();
        for (JsonNode row : LegacyResearchSupport.rows(left.get("metrics"))) {
            String key = metricKey(row);
            if (!rightMetrics.containsKey(key)) continue;
            ObjectNode values = JSON.objectNode();
            JsonNode leftValue = metricValue(row);
            JsonNode rightValue = rightMetrics.get(key);
            for (String metric : List.of("completed_trades", "expectancy_r",
                    "search_adjusted_expectancy_r", "profit_factor", "max_drawdown_pct")) {
                values.put(metric, jsNumber(rightValue.get(metric)) - jsNumber(leftValue.get(metric)));
            }
            ObjectNode delta = JSON.objectNode().put("key", key);
            delta.set("deltas", values);
            deltas.add(delta);
        }
        ObjectNode result = JSON.objectNode()
                .put("left", text(left.path("run").get("run_id")))
                .put("right", text(right.path("run").get("run_id")));
        result.set("deltas", deltas);
        print(out, result);
    }

    private static void importLegacy(
            Path root, Map<String, String> options, PrintStream out) {
        String sourceOption = options.getOrDefault("source", ".report-run/strategy-v2");
        Path sourceRoot = resolve(sourceOption);
        JsonNode definition = read(options.getOrDefault("definition",
                root.resolve("definitions/fk-deleveraging-absorption/v001.json").toString()));
        ArrayNode imported = JSON.arrayNode();
        for (LegacyResearchV1.LegacySource source : LegacyResearchV1.LEGACY_SOURCES) {
            Path sourcePath = sourceRoot.resolve(source.path());
            if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                imported.add(JSON.objectNode().put("source", source.path()).put("missing", true));
                continue;
            }
            for (String asset : source.assets()) {
                ObjectNode legacy = LegacyResearchV1.compactLegacy(
                        sourcePath, asset, source.evidencePhase());
                legacy.put("source_path", Path.of(sourceOption).resolve(source.path()).toString());
                if (legacy.path("metrics").isEmpty() && legacy.path("trades").isEmpty()) {
                    imported.add(JSON.objectNode().put("source", source.path())
                            .put("asset", asset).put("recoverable", false));
                    continue;
                }
                String experimentId = "legacy-" + LegacyResearchV1.hash(
                        source.path() + "|" + asset).substring(0, 16);
                ObjectNode candidateSet = JSON.objectNode()
                        .put("schema", LegacyResearchV1.CANDIDATE_SET_SCHEMA)
                        .put("experiment_id", experimentId)
                        .put("declared_k", legacy.path("candidates").size())
                        .put("effective_k", legacy.path("candidates").size());
                ArrayNode declared = JSON.arrayNode();
                for (JsonNode candidate : legacy.path("candidates")) {
                    declared.add(JSON.objectNode()
                            .put("id", text(candidate.get("candidate_id")))
                            .put("behavior_sha256", text(candidate.get("behavior_sha256"))));
                }
                candidateSet.put("declared_sha256", LegacyResearchV1.hash(declared));
                candidateSet.put("effective_sha256",
                        LegacyResearchV1.hash(legacy.get("candidates")));
                candidateSet.set("per_series", JSON.arrayNode());
                candidateSet.set("candidates", cloneNode(legacy.get("candidates")));
                ObjectNode experiment = JSON.objectNode()
                        .put("schema", LegacyResearchV1.EXPERIMENT_SCHEMA)
                        .put("experiment_id", experimentId)
                        .put("created_at", truthyText(legacy.get("source_generated_at"),
                                "2026-08-23T00:00:00.000Z"))
                        .put("evidence_phase", source.evidencePhase());
                experiment.set("definition", JSON.objectNode()
                        .put("path", "definitions/fk-deleveraging-absorption/v001.json")
                        .put("sha256", LegacyResearchV1.hash(definition)));
                experiment.set("required_assets", strings(List.of(asset)));
                experiment.set("grid", JSON.objectNode());
                experiment.set("candidate_set", JSON.objectNode()
                        .put("path", "embedded-legacy-candidate-set")
                        .put("sha256", LegacyResearchV1.hash(candidateSet)));
                experiment.set("acceptance", JSON.objectNode().set("minimums",
                        JSON.objectNode().put("completed_trades", 20).put("profit_factor", 1.1)));
                ObjectNode bundleInput = JSON.objectNode();
                bundleInput.set("experiment", experiment);
                bundleInput.set("definition", definition);
                bundleInput.set("candidateSet", candidateSet);
                bundleInput.set("metrics", cloneNode(legacy.get("metrics")));
                bundleInput.set("trades", cloneNode(legacy.get("trades")));
                ObjectNode legacySummary = legacy.deepCopy();
                legacySummary.remove(List.of("candidates", "metrics", "trades"));
                bundleInput.set("legacy", legacySummary);
                if (present(legacy, "source_generated_at")) {
                    bundleInput.set("generatedAt", cloneNode(legacy.get("source_generated_at")));
                }
                ObjectNode bundle = LegacyResearchV1.makeRunBundle(bundleInput);
                Path runRoot = root.resolve("runs").resolve(
                        text(bundle.path("run").get("run_id")));
                if (!Files.exists(runRoot, LinkOption.NOFOLLOW_LINKS)) {
                    LegacyResearchV1.writeRunBundle(root, bundle);
                }
                imported.add(JSON.objectNode()
                        .put("source", source.path()).put("asset", asset)
                        .put("run_id", text(bundle.path("run").get("run_id")))
                        .put("candidates", bundle.path("candidates").size())
                        .put("metrics", bundle.path("metrics").size())
                        .put("trades", bundle.path("trades").size())
                        .put("evidence_phase", source.evidencePhase())
                        .set("omissions", cloneNode(legacy.get("explicit_omissions"))));
            }
        }
        print(out, imported);
    }

    private static V3Index rebuildV3Index(Path root) {
        ArrayNode runs = JSON.arrayNode();
        Path runsRoot = root.resolve("runs");
        if (Files.exists(runsRoot, LinkOption.NOFOLLOW_LINKS)) {
            assertRealDirectory(runsRoot, "runs root");
            List<Path> directories;
            try (var stream = Files.list(runsRoot)) {
                directories = stream.sorted(Comparator.comparing(
                        path -> path.getFileName().toString())).toList();
            } catch (IOException error) {
                throw new IllegalArgumentException("runs root cannot be listed", error);
            }
            for (Path directory : directories) {
                assertRealDirectory(directory, "run directory");
                Path path = directory.resolve("run.json");
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
                JsonNode run = read(path);
                if (!LegacyResearchV3.RUN_V3_SCHEMA.equals(text(run.get("schema")))) continue;
                LegacyResearchV3.validateRunV3(run);
                if (!directory.getFileName().toString().equals(text(run.get("run_id")))) {
                    throw new IllegalArgumentException(
                            "v3 run directory name does not match run_id: "
                                    + directory.getFileName());
                }
                if (present(run, "evidence_bundle_sha256")) {
                    JsonNode evidence = read(root.resolve("evidence-bundles")
                            .resolve(text(run.get("evidence_bundle_sha256")) + ".json"));
                    LegacyResearchV3.validateEvidenceBundleV2(evidence);
                    if (!text(evidence.get("content_sha256")).equals(
                            text(run.get("evidence_bundle_sha256")))
                            || !text(evidence.get("experiment_sha256")).equals(
                            text(run.get("experiment_sha256")))
                            || !text(evidence.get("evidence_phase")).equals(
                            text(run.get("evidence_phase")))
                            || !LegacyResearchV3.hash(evidence.get("decisions")).equals(
                            LegacyResearchV3.hash(run.get("decisions")))) {
                        throw new IllegalArgumentException(
                                "v3 run/evidence mismatch: " + text(run.get("run_id")));
                    }
                }
                ObjectNode row = JSON.objectNode()
                        .put("run_id", text(run.get("run_id")));
                putNullable(row, "evidence_bundle_sha256",
                        run.get("evidence_bundle_sha256"));
                row.put("evidence_phase", text(run.get("evidence_phase")));
                row.set("decisions", cloneNode(run.get("decisions")));
                runs.add(row);
            }
        }
        List<JsonNode> sorted = new ArrayList<>(LegacyResearchSupport.rows(runs));
        sorted.sort(Comparator.comparing(row -> text(row.get("run_id"))));
        ObjectNode index = JSON.objectNode().put("schema", "strategy-research-index/3");
        index.set("runs", arrayOf(sorted));
        index.put("content_sha256", LegacyResearchV3.ownHash(index));
        Path path = root.resolve("index-v3.json").toAbsolutePath().normalize();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            JsonNode prior = read(path);
            if (!text(prior.get("content_sha256")).equals(LegacyResearchV3.ownHash(prior))) {
                throw new IllegalArgumentException("v3 index retained-hash tampering");
            }
        }
        atomicWrite(root, path, index);
        return new V3Index(path, index);
    }

    private static void recordV3(Path root, ObjectNode result, ObjectNode output) {
        JsonNode bundle = result.get("bundle");
        JsonNode run = result.get("run");
        Path evidence = root.resolve("evidence-bundles")
                .resolve(text(bundle.get("content_sha256")) + ".json");
        Path runPath = root.resolve("runs").resolve(text(run.get("run_id"))).resolve("run.json");
        writeContentAddressed(evidence, bundle);
        writeContentAddressed(runPath, run);
        Path indexPath = root.resolve("index-v3.json");
        ObjectNode index = Files.exists(indexPath, LinkOption.NOFOLLOW_LINKS)
                ? objectCopy(read(indexPath), "v3 index")
                : JSON.objectNode().put("schema", "strategy-research-index/3")
                .set("runs", JSON.arrayNode());
        if (present(index, "content_sha256")
                && !text(index.get("content_sha256")).equals(LegacyResearchV3.ownHash(index))) {
            throw new IllegalArgumentException("v3 index retained-hash tampering");
        }
        if (LegacyResearchSupport.rows(index.get("runs")).stream()
                .anyMatch(row -> text(row.get("run_id")).equals(text(run.get("run_id"))))) {
            throw new IllegalArgumentException(
                    "duplicate v3 run recording: " + text(run.get("run_id")));
        }
        ArrayNode rows = (ArrayNode) index.get("runs");
        ObjectNode row = JSON.objectNode().put("run_id", text(run.get("run_id")))
                .put("evidence_bundle_sha256", text(bundle.get("content_sha256")))
                .put("evidence_phase", text(run.get("evidence_phase")));
        row.set("decisions", cloneNode(run.get("decisions")));
        rows.add(row);
        List<JsonNode> sorted = new ArrayList<>(LegacyResearchSupport.rows(rows));
        sorted.sort(Comparator.comparing(item -> text(item.get("run_id"))));
        index.set("runs", arrayOf(sorted));
        index.put("content_sha256", LegacyResearchV3.ownHash(index));
        atomicWrite(root, indexPath, index);
        output.put("record_root", root.toString());
        output.put("evidence_bundle", evidence.toString());
        output.put("run", runPath.toString());
    }

    private static Path findRun(Path root, String id) {
        Path exact = root.resolve("runs").resolve(id);
        if (Files.exists(exact.resolve("run.json"), LinkOption.NOFOLLOW_LINKS)) return exact;
        Path runs = root.resolve("runs");
        if (Files.exists(runs, LinkOption.NOFOLLOW_LINKS)) {
            assertRealDirectory(runs, "runs root");
            try (var stream = Files.list(runs)) {
                for (Path directory : stream.sorted().toList()) {
                    Path run = directory.resolve("run.json");
                    if (!Files.exists(run, LinkOption.NOFOLLOW_LINKS)) continue;
                    JsonNode value = read(run);
                    if (text(value.get("run_id")).equals(id)
                            || text(value.get("run_id")).startsWith(id)) return directory;
                }
            } catch (IOException error) {
                throw new IllegalArgumentException("runs root cannot be listed", error);
            }
        }
        JsonNode index = read(root.resolve("index.json"));
        for (JsonNode row : LegacyResearchSupport.rows(index.get("runs"))) {
            if (text(row.get("run_id")).equals(id)
                    || text(row.get("run_id")).startsWith(id)) {
                return root.resolve(text(row.get("path"))).getParent();
            }
        }
        throw new IllegalArgumentException("run not found: " + id);
    }

    private static ObjectNode readRunView(Path root, Path runRoot) {
        JsonNode raw = read(runRoot.resolve("run.json"));
        String schema = text(raw.get("schema"));
        ObjectNode result = JSON.objectNode();
        result.set("run", cloneNode(raw));
        if (LegacyResearchV2.RUN_V2_SCHEMA.equals(schema)) {
            LegacyResearchV2.validateV2Document(raw);
            JsonNode experiment = read(root.resolve("experiments")
                    .resolve(text(raw.get("experiment_id"))).resolve("experiment.json"));
            result.set("candidates", read(root.resolve("experiments")
                    .resolve(text(raw.get("experiment_id")))
                    .resolve(text(experiment.path("candidate_set").get("path"))))
                    .path("candidates"));
            result.set("metrics", cloneNode(raw.get("metrics")));
            result.set("trades", cloneNode(raw.get("trades")));
            return result;
        }
        if (LegacyResearchV3.RUN_V3_SCHEMA.equals(schema)) {
            LegacyResearchV3.validateRunV3(raw);
            JsonNode bundle = present(raw, "evidence_bundle_sha256")
                    ? read(root.resolve("evidence-bundles")
                    .resolve(text(raw.get("evidence_bundle_sha256")) + ".json")) : null;
            if (bundle != null) LegacyResearchV3.validateEvidenceBundleV2(bundle);
            result.set("candidates", JSON.arrayNode());
            result.set("metrics", bundle == null ? JSON.arrayNode()
                    : cloneNode(bundle.get("metrics")));
            result.set("trades", bundle == null ? JSON.arrayNode()
                    : cloneNode(bundle.get("trades")));
            putNullable(result, "evidence", bundle);
            return result;
        }
        ObjectNode run = LegacyResearchV1.validateRunDirectory(runRoot);
        result.set("run", run);
        result.set("candidates", LegacyResearchV1.readJSONL(
                runRoot.resolve(text(run.path("artifacts").path("candidates").get("path")))));
        result.set("metrics", LegacyResearchV1.readJSONL(
                runRoot.resolve(text(run.path("artifacts").path("metrics").get("path")))));
        result.set("trades", LegacyResearchV1.readJSONL(
                runRoot.resolve(text(run.path("artifacts").path("trades").get("path")))));
        return result;
    }

    private static ArrayNode readFeatureRows(
            Path featurePath, JsonNode featureSet, Path manifestPath) {
        String name = featurePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".parquet")) {
            return arrayOf(ResearchData.queryParquet(featurePath));
        }
        JsonNode value = read(featurePath);
        if ("swing-feature-store/1".equals(text(value.get("schema")))) {
            if (!SwingEngine.verifyFeatureStoreHash(value)) {
                throw new IllegalArgumentException(
                        "feature store content hash verification failed");
            }
            return SwingEngine.decodeFeatureStore(value);
        }
        if ("research-feature-set/1".equals(text(value.get("schema")))) {
            Path lakeRoot = manifestPath.getParent().getParent();
            ArrayNode result = JSON.arrayNode();
            if (value.path("partitions").isEmpty()) {
                throw new IllegalArgumentException(
                        "feature set has no physical feature partitions");
            }
            for (JsonNode partition : value.path("partitions")) {
                Path target = lakeRoot.resolve(text(partition.get("path"))).normalize();
                List<ObjectNode> rows = target.toString().endsWith(".parquet")
                        ? ResearchData.queryParquet(target) : ResearchData.readRows(target);
                rows.forEach(result::add);
            }
            return result;
        }
        if (value.isArray()) return (ArrayNode) value;
        if (value.path("rows").isArray()) return (ArrayNode) value.get("rows");
        if (value.path("data").isArray()) return (ArrayNode) value.get("data");
        throw new IllegalArgumentException("v3 features must be a swing feature store, row "
                + "array, or authoritative Parquet");
    }

    private static void writeContentAddressed(Path path, JsonNode value) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            JsonNode existing = read(path);
            String actual;
            if (LegacyResearchV3.RUN_V3_SCHEMA.equals(text(existing.get("schema")))) {
                ObjectNode copy = objectCopy(existing, "content-addressed artifact");
                copy.remove(List.of("run_id", "content_sha256"));
                actual = LegacyResearchV3.hash(copy);
            } else if (LegacyResearchV2.RUN_V2_SCHEMA.equals(text(existing.get("schema")))) {
                ObjectNode copy = objectCopy(existing, "content-addressed artifact");
                copy.remove(List.of("run_id", "content_sha256"));
                actual = LegacyResearchV2.hash(copy);
            } else actual = LegacyResearchV3.ownHash(existing);
            if (!text(existing.get("content_sha256")).equals(actual)) {
                throw new IllegalArgumentException(
                        "content-addressed retained-hash tampering: " + path);
            }
            if (text(existing.get("content_sha256"))
                    .equals(text(value.get("content_sha256")))) return;
            throw new IllegalArgumentException("content-addressed artifact collision: " + path);
        }
        LegacyResearchV1.writeImmutable(path, value);
    }

    private static void atomicWrite(Path root, Path path, JsonNode value) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        secureParents(absoluteRoot);
        Path relative = absoluteRoot.relativize(path.toAbsolutePath().normalize());
        if (relative.startsWith("..")) {
            throw new IllegalArgumentException("index path escaped research root");
        }
        com.tradinganalytics.infrastructure.security.SecureFileOperations.atomicReplace(
                absoluteRoot, relative.toString(), jsonBytes(value));
    }

    private static ObjectNode invokePortfolio(JsonNode signals, JsonNode policy) {
        try {
            Class<?> type = Class.forName("com.tradinganalytics.research.v5.StrategyPortfolioV5");
            Method method = type.getMethod("simulateCryptoPortfolio", JsonNode.class, JsonNode.class);
            return (ObjectNode) method.invoke(null, signals, policy);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException error) {
            throw new IllegalArgumentException(
                    "Java strategy portfolio simulator is unavailable", error);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalArgumentException(cause.getMessage(), cause);
        }
    }

    private static boolean inputIsV5(String input) {
        if (input == null) return false;
        try {
            String schema = text(read(input).get("schema"));
            return Set.of("strategy-opportunity-envelope/1", "strategy-prospective-runner/2",
                    "strategy-overfit-audit/1", "strategy-v5-opportunity-envelope/2")
                    .contains(schema) || schema.startsWith("strategy-v5-");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Map<String, String> flags(String[] args, int start) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = start; index < args.length; index++) {
            if (!args[index].startsWith("--")) continue;
            String raw = args[index].substring(2);
            String value = index + 1 >= args.length || args[index + 1].startsWith("--")
                    ? "true" : args[++index];
            result.put(raw, value);
            result.put(raw.replace('-', '_'), value);
        }
        return result;
    }

    private static JsonNode read(String path) { return read(resolve(path)); }
    private static JsonNode read(Path path) { return LegacyResearchV1.readJSON(path); }

    private static String readText(String path) {
        Path target = resolve(path);
        return new String(PathConfinement.readSinglyLinkedFile(target, "text input"),
                StandardCharsets.UTF_8);
    }

    private static Path resolve(String value) {
        return Path.of(value == null ? "" : value).toAbsolutePath().normalize();
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static int integer(Map<String, String> options, String key, int fallback) {
        return options.containsKey(key) ? Integer.parseInt(options.get(key)) : fallback;
    }

    private static double number(Map<String, String> options, String key, double fallback) {
        return options.containsKey(key) ? Double.parseDouble(options.get(key)) : fallback;
    }

    private static String first(Map<String, String> options, String... keys) {
        for (String key : keys) {
            String value = options.get(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String first(JsonNode value, String... keys) {
        for (String key : keys) if (value != null && value.has(key)) return text(value.get(key));
        return null;
    }

    private static String currentCommit() {
        String environment = System.getenv("GITHUB_SHA");
        if (environment != null && !environment.isBlank()) return environment.trim();
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            int status = process.waitFor();
            if (status != 0) throw new IllegalArgumentException(
                    new String(output, StandardCharsets.UTF_8).trim());
            return new String(output, StandardCharsets.UTF_8).trim();
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalArgumentException("current git commit cannot be resolved", error);
        }
    }

    private static String camel(String snake) {
        StringBuilder output = new StringBuilder();
        boolean upper = false;
        for (char character : snake.toCharArray()) {
            if (character == '_') upper = true;
            else if (upper) {
                output.append(Character.toUpperCase(character));
                upper = false;
            } else output.append(character);
        }
        return output.toString();
    }

    private static String truthyText(JsonNode value, String fallback) {
        return bool(value) ? text(value) : fallback;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static void put(ObjectNode target, String key, String value) {
        if (value != null) target.put(key, value);
    }

    private static void putNullable(ObjectNode target, String key, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) target.putNull(key);
        else target.set(key, cloneNode(value));
    }

    private static boolean present(JsonNode value, String key) {
        return value != null && value.isObject() && value.has(key)
                && value.get(key) != null && !value.get(key).isNull();
    }

    private static List<String> requiredAssets(JsonNode experiment) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : LegacyResearchSupport.rows(experiment.get("required_assets"))) {
            result.add(text(item.isTextual() ? item : item.get("asset")).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static String metricKey(JsonNode row) {
        return (bool(row.get("scope")) ? text(row.get("scope"))
                : bool(row.get("asset")) ? "ASSET" : "PORTFOLIO")
                + "|" + text(row.get("asset")) + "|" + text(row.get("candidate_id"));
    }

    private static JsonNode metricValue(JsonNode row) {
        return row.path("metrics").isObject() ? row.get("metrics") : row;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void writeTextImmutable(Path path, String value) {
        writeExclusive(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void print(PrintStream output, JsonNode value) {
        output.print(NodePrettyJson.write(value));
    }

    private record V3Index(Path path, ObjectNode index) {}
}
