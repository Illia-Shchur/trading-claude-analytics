package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import com.tradinganalytics.marketdata.research.ResearchData;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.tradinganalytics.research.legacy.LegacyResearchSupport.JSON;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.cloneNode;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.objectCopy;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.rows;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.secureParents;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.text;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.walk;

/**
 * Unregistered, side-effect-bounded command boundary for {@code strategy-research-next.mjs}.
 * The authoritative v5 router deliberately remains owned by its separate adapter.
 */
public final class LegacyResearchNextCommandAdapter {
    public static final String USAGE = "usage: strategy-research-next.mjs precommit|generate|"
            + "stack|evaluate|source-receipt|data-validate|snapshot-next|execution-policy|"
            + "portfolio-policy|exposure|stats|wfo|readiness|readiness-audit|"
            + "deployment-audit|activate|verify-activation|record|index|validate|"
            + "prospective-freeze|prospective-append|data-backfill|opportunity-envelope|"
            + "search-genetic|research-run|overfit-audit|prospective-runner\n";

    private static final Set<String> V5_COMMANDS = Set.of(
            "data-backfill", "opportunity-envelope", "search-genetic", "research-run",
            "overfit-audit", "prospective-runner", "readiness-audit", "deployment-audit");

    private LegacyResearchNextCommandAdapter() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        String command = args.length == 0 ? "" : args[0];
        Map<String, String> options = flags(args, 1);
        try {
            if ("generate".equals(command)
                    && "GENETIC".equalsIgnoreCase(options.getOrDefault("method", ""))) {
                throw new IllegalArgumentException("static generate --method GENETIC is rejected; "
                        + "use the authoritative search-genetic command");
            }
            if (V5_COMMANDS.contains(command)) {
                throw new IllegalArgumentException("authoritative v5 command is not part of the "
                        + "strategy-research-next /4 adapter: " + command);
            }
            switch (command) {
                case "wfo" -> print(out, LegacyResearchNext.runAuthoritativeWfo(
                        read(required(options, "input"))));
                case "precommit" -> precommit(options, out);
                case "generate" -> generate(options, out);
                case "stack" -> stack(options, out);
                case "evaluate" -> evaluate(options, out);
                case "source-receipt" -> sourceReceipt(options, out);
                case "data-validate" -> dataValidate(options, out);
                case "snapshot-next" -> snapshotNext(options, out);
                case "execution-policy" -> policy(options, out, true);
                case "portfolio-policy" -> policy(options, out, false);
                case "exposure" -> exposure(options, out);
                case "readiness" -> readiness(options, out);
                case "stats" -> stats(options, out);
                case "activate" -> activate(options, out);
                case "verify-activation" -> verifyActivation(options, out);
                case "record" -> record(options, out);
                case "index" -> index(options, out);
                case "validate" -> validate(options, out);
                case "prospective-freeze" -> prospectiveFreeze(options, out);
                case "prospective-append" -> prospectiveAppend(options, out);
                case "readiness-markdown" -> {
                    ObjectNode result = JSON.objectNode();
                    result.put("markdown", LegacyResearchNext.readinessMarkdown(
                            read(required(options, "input"))));
                    print(out, result);
                }
                default -> out.print(USAGE);
            }
            return 0;
        } catch (RuntimeException error) {
            err.println(message(error));
            return 1;
        }
    }

    private static void precommit(Map<String, String> options, PrintStream out) {
        ObjectNode value = LegacyResearchNext.freezeNextPrecommit(
                read(required(options, "input")));
        String name = value.hasNonNull("precommit_id")
                ? text(value.get("precommit_id")) : text(value.get("content_sha256"));
        Path target = resolve(options.getOrDefault("out",
                "strategy-research/precommits/" + name + ".json"));
        writeImmutable(target, value);
        print(out, JSON.objectNode().put("path", target.toString())
                .put("sha256", text(value.get("content_sha256"))));
    }

    private static void generate(Map<String, String> options, PrintStream out) {
        ObjectNode precommit = LegacyResearchNext.freezeNextPrecommit(
                read(required(options, "precommit")));
        ObjectNode input = JSON.objectNode().set("precommit", precommit);
        input.put("method", options.getOrDefault("method", "GRID"));
        input.put("seed", number(options, "seed", 1));
        input.put("trials", number(options, "trials", 0));
        input.put("population", number(options, "population", 8));
        input.put("generations", number(options, "generations", 2));
        if (options.containsKey("grid")) input.set("grid", read(options.get("grid")));
        if (options.containsKey("model_provenance")) {
            input.set("modelProvenance", read(options.get("model_provenance")));
        }
        ObjectNode candidates = LegacyResearchNext.generateNextCandidates(input);
        LegacyResearchNext.validateCandidateSetNext(candidates, precommit);
        Path target = resolve(options.getOrDefault("out", "strategy-research/candidates/"
                + text(candidates.get("content_sha256")) + ".json"));
        writeImmutable(target, candidates);
        ObjectNode result = JSON.objectNode().put("path", target.toString());
        result.set("candidate_set", candidates);
        print(out, result);
    }

    private static void stack(Map<String, String> options, PrintStream out) {
        ObjectNode precommit = LegacyResearchNext.freezeNextPrecommit(
                read(required(options, "precommit")));
        JsonNode candidates = read(required(options, "candidates"));
        ObjectNode input = JSON.objectNode();
        put(input, "stackId", options.get("id"));
        input.set("precommit", precommit);
        input.set("candidateSet", candidates);
        put(input, "manifestSha256", options.get("manifest_sha256"));
        put(input, "featureSetSha256", options.get("feature_set_sha256"));
        put(input, "labelSetSha256", options.get("label_set_sha256"));
        input.put("evidencePhase", options.getOrDefault("phase", "DEVELOPMENT"));
        ObjectNode contract = LegacyResearchNext.makeStackContract(input);
        Path target = resolve(options.getOrDefault("out", "strategy-research/stacks/"
                + text(contract.get("stack_id")) + ".json"));
        writeImmutable(target, contract);
        ObjectNode result = JSON.objectNode().put("path", target.toString());
        result.set("contract", contract);
        print(out, result);
    }

    private static void evaluate(Map<String, String> options, PrintStream out) {
        ObjectNode input = JSON.objectNode();
        input.set("precommit", LegacyResearchNext.freezeNextPrecommit(
                read(required(options, "precommit"))));
        input.set("candidateSet", read(required(options, "candidates")));
        input.set("stack", read(required(options, "stack")));
        input.set("featureRows", unwrapRows(read(required(options, "features"))));
        input.set("labelRows", options.containsKey("labels")
                ? unwrapRows(read(options.get("labels"))) : JSON.arrayNode());
        setIfFile(input, "featureSet", options, "feature_set");
        setIfFile(input, "labelSet", options, "label_set");
        setIfFile(input, "manifest", options, "manifest");
        input.set("sourceReceipts", options.containsKey("receipts")
                ? read(options.get("receipts")) : JSON.arrayNode());
        setIfFile(input, "exposureLedger", options, "exposure");
        put(input, "hypothesisFamily", options.get("hypothesis_family"));
        ObjectNode evaluated = LegacyResearchNext.evaluateAuthoritativeNext(input);
        JsonNode run = evaluated.get("run");
        JsonNode evidence = evaluated.get("evidence");
        Path runTarget = resolve(options.getOrDefault("out", "strategy-research/runs/"
                + text(run.get("content_sha256")) + ".json"));
        Path evidenceTarget = resolve(options.getOrDefault("evidence",
                "strategy-research/evidence/" + text(evidence.get("content_sha256")) + ".json"));
        writeImmutable(runTarget, run);
        writeImmutable(evidenceTarget, evidence);
        ObjectNode result = JSON.objectNode().put("run", runTarget.toString())
                .put("evidence", evidenceTarget.toString());
        JsonNode ledger = evaluated.get("exposureLedger");
        if (ledger != null && !ledger.isNull()) {
            Path target = resolve(options.getOrDefault("exposure_out",
                    "strategy-research/exposure/" + text(ledger.get("content_sha256")) + ".json"));
            writeImmutable(target, ledger);
            result.put("exposure", target.toString());
        } else result.putNull("exposure");
        result.set("result", evaluated);
        print(out, result);
    }

    private static void sourceReceipt(Map<String, String> options, PrintStream out) {
        ObjectNode input = JSON.objectNode();
        put(input, "source", options.get("source"));
        put(input, "sourceUrl", options.get("source_url"));
        put(input, "requestedPitTier", options.get("pit_tier"));
        put(input, "captureTime", options.get("capture_time"));
        put(input, "archiveChecksum", options.get("archive_checksum"));
        put(input, "adapterSha256", options.get("adapter_sha256"));
        ObjectNode receipt = LegacyResearchNext.makeSourceReceipt(input);
        ObjectNode result = JSON.objectNode();
        if (options.containsKey("out")) {
            Path target = resolve(options.get("out"));
            writeImmutable(target, receipt);
            result.put("path", target.toString());
        } else result.putNull("path");
        result.set("receipt", receipt);
        print(out, result);
    }

    private static void dataValidate(Map<String, String> options, PrintStream out) {
        ObjectNode input = JSON.objectNode();
        input.set("manifest", read(required(options, "manifest")));
        input.set("featureRows", unwrapRows(read(required(options, "features"))));
        input.set("labelRows", options.containsKey("labels")
                ? unwrapRows(read(options.get("labels"))) : JSON.arrayNode());
        input.set("sourceReceipts", options.containsKey("receipts")
                ? read(options.get("receipts")) : JSON.arrayNode());
        input.put("phase", options.getOrDefault("phase", "DEVELOPMENT"));
        if (options.containsKey("assets")) input.set("requiredAssets",
                strings(options.get("assets").split(",")));
        print(out, LegacyResearchNext.validateNextDataSnapshot(input));
    }

    private static void snapshotNext(Map<String, String> options, PrintStream out) {
        String inputPath = required(options, "input");
        String instrument = options.getOrDefault("instrument", "");
        String source = options.getOrDefault("source",
                instrument.toLowerCase(Locale.ROOT).contains("perp")
                        ? "binance:usd-m-ohlcv" : "binance:spot-ohlcv");
        ObjectNode assignmentInput = JSON.objectNode().put("source", source);
        if (options.containsKey("pit_tier")) {
            assignmentInput.put("requestedPitTier", options.get("pit_tier"));
        }
        ObjectNode assignment = LegacyResearchNext.assignPitTier(assignmentInput);
        ObjectNode receiptInput = assignmentInput.deepCopy();
        put(receiptInput, "captureTime", options.get("capture_time"));
        put(receiptInput, "archiveChecksum", options.get("archive_checksum"));
        put(receiptInput, "adapterSha256", options.get("adapter_sha256"));
        ObjectNode receipt = LegacyResearchNext.makeSourceReceipt(receiptInput);
        LegacyResearchNext.validateSourceReceipt(receipt,
                JSON.objectNode().put("phase", options.getOrDefault("phase", "DEVELOPMENT")));
        String tier = switch (text(assignment.get("assigned_pit_tier"))) {
            case "IMMUTABLE_EVENT_ARCHIVE" -> "T0_IMMUTABLE_EVENT";
            case "VINTAGE_REVISION_AWARE" -> "T1_PUBLICATION_VINTAGE";
            case "CAPTURE_FORWARD" -> "T2_CAPTURED_AS_OF";
            default -> "UNVERIFIED";
        };
        String configHash = LegacyResearchNext.hash(JSON.objectNode().put("source", source)
                .put("source_receipt_sha256", text(receipt.get("content_sha256")))
                .put("assigned_pit_tier", text(assignment.get("assigned_pit_tier"))));
        Path outputRoot = resolve(options.getOrDefault("out", "strategy-research/lake"));
        ResearchData.SnapshotResult snapshot = ResearchData.snapshot(new ResearchData.SnapshotOptions(
                resolve(inputPath), outputRoot,
                first(options, "dataset", "dataset_id"), options.get("asset"),
                options.getOrDefault("venue", "BINANCE"), options.get("instrument"), tier,
                options.getOrDefault("role", "FEATURE"), options.getOrDefault("format", "parquet"),
                source, !"false".equals(options.get("public_source")),
                nullableText(receipt.get("adapter_sha256")), null, null, configHash,
                null, null));
        ObjectNode result = JSON.objectNode();
        result.set("snapshot", snapshotJson(snapshot,
                outputRoot.resolve(snapshot.snapshotId()).normalize()));
        result.set("source_receipt", receipt);
        result.put("source_receipt_sha256", text(receipt.get("content_sha256")));
        result.set("pit_assignment", assignment);
        print(out, result);
    }

    private static void policy(Map<String, String> options, PrintStream out, boolean execution) {
        ObjectNode value = execution ? LegacyResearchNext.makeExecutionPolicy()
                : LegacyResearchNext.makePortfolioPolicy();
        ObjectNode result = JSON.objectNode();
        if (options.containsKey("out")) {
            Path target = resolve(options.get("out"));
            writeImmutable(target, value);
            result.put("path", target.toString());
        } else result.putNull("path");
        result.set(execution ? "policy" : "policy", value);
        print(out, result);
    }

    private static void exposure(Map<String, String> options, PrintStream out) {
        JsonNode candidates = read(required(options, "candidates"));
        ObjectNode input = JSON.objectNode();
        if (options.containsKey("prior")) input.set("prior", read(options.get("prior")));
        put(input, "hypothesisFamily", options.get("hypothesis_family"));
        put(input, "datasetRootSha256", options.get("dataset_root_sha256"));
        input.set("candidates", candidates.has("candidates")
                ? candidates.get("candidates") : candidates);
        ObjectNode ledger = LegacyResearchNext.appendExposureLedger(input);
        Path target = resolve(options.getOrDefault("out", "strategy-research/exposure/"
                + text(ledger.get("content_sha256")) + ".json"));
        writeImmutable(target, ledger);
        ObjectNode result = JSON.objectNode().put("path", target.toString());
        result.set("ledger", ledger);
        print(out, result);
    }

    private static void readiness(Map<String, String> options, PrintStream out) {
        ObjectNode audit = LegacyResearchNext.readinessAudit();
        Path jsonPath = resolve(options.getOrDefault("out",
                "strategy-research/readiness-audit.json"));
        String defaultMarkdown = jsonPath.toString().replaceFirst("(?i)\\.json$", ".md");
        Path markdownPath = resolve(options.getOrDefault("markdown", defaultMarkdown));
        writeMutableAtomic(jsonPath, audit);
        writeMutableTextAtomic(markdownPath, LegacyResearchNext.readinessMarkdown(audit));
        ObjectNode result = JSON.objectNode().put("json", jsonPath.toString())
                .put("markdown", markdownPath.toString());
        result.set("audit", audit);
        print(out, result);
    }

    private static void stats(Map<String, String> options, PrintStream out) {
        ObjectNode config = JSON.objectNode()
                .put("iterations", number(options, "iterations", 2000))
                .put("seed", number(options, "seed", 1));
        if (options.containsKey("block_length")) {
            config.put("blockLength", Double.parseDouble(options.get("block_length")));
        } else config.set("blockLength", NullNode.instance);
        print(out, LegacyResearchNext.stationaryBlockMaxStatistic(
                read(required(options, "input")), config));
    }

    private static void activate(Map<String, String> options, PrintStream out) {
        ObjectNode request = objectCopy(read(required(options, "input")), "activation request");
        String privatePath = options.getOrDefault("private_key",
                text(request.get("private_key_path")));
        request.put("privateKeyPem", readText(requiredValue(privatePath, "private_key")));
        if (!request.has("evidenceArtifacts") && options.containsKey("evidence")) {
            request.set("evidenceArtifacts", read(options.get("evidence")));
        }
        ObjectNode artifact = LegacyResearchNext.makeActivationArtifact(request);
        artifact.remove("private_key_path");
        ObjectNode result = JSON.objectNode();
        if (options.containsKey("out")) {
            Path target = resolve(options.get("out"));
            writeImmutable(target, artifact);
            result.put("path", target.toString());
        } else result.putNull("path");
        result.set("artifact", artifact);
        print(out, result);
    }

    private static void verifyActivation(Map<String, String> options, PrintStream out) {
        ObjectNode input = JSON.objectNode();
        input.put("publicKeyPem", readText(required(options, "public_key")));
        put(input, "trustRootKeyId", options.get("trust_root_key_id"));
        input.set("evidenceArtifacts", options.containsKey("evidence")
                ? read(options.get("evidence")) : JSON.objectNode());
        if (options.containsKey("strategy_sha256") || options.containsKey("candidate_sha256")
                || options.containsKey("risk_policy_sha256")) {
            ObjectNode expected = JSON.objectNode();
            put(expected, "strategy_sha256", options.get("strategy_sha256"));
            put(expected, "candidate_sha256", options.get("candidate_sha256"));
            put(expected, "risk_policy_sha256", options.get("risk_policy_sha256"));
            input.set("expected", expected);
        }
        print(out, LegacyResearchNext.verifyActivationArtifact(
                read(required(options, "input")), input));
    }

    private static void record(Map<String, String> options, PrintStream out) {
        JsonNode value = read(required(options, "input"));
        ObjectNode validation = validationOptions(options);
        LegacyResearchNext.validateNextArtifact(value, validation);
        String schemaPath = text(value.get("schema")).replace('/', '-');
        Path target = resolve(options.getOrDefault("out", "strategy-research/next-records/"
                + schemaPath + "/" + text(value.get("content_sha256")) + ".json"));
        writeImmutable(target, value);
        print(out, JSON.objectNode().put("path", target.toString())
                .put("schema", text(value.get("schema")))
                .put("content_sha256", text(value.get("content_sha256"))));
    }

    private static void index(Map<String, String> options, PrintStream out) {
        Path root = resolve(options.getOrDefault("root", "strategy-research/next-records"));
        ArrayNode records = JSON.arrayNode();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            List<Path> files = new ArrayList<>(walk(root));
            files.removeIf(path -> !path.getFileName().toString().endsWith(".json")
                    || root.relativize(path).getNameCount() != 2);
            files.sort(Comparator.comparing(path -> root.relativize(path).toString()));
            for (Path path : files) {
                JsonNode value = read(path);
                LegacyResearchNext.validateNextArtifact(value, validationOptions(options));
                ObjectNode row = JSON.objectNode()
                        .put("schema", text(value.get("schema")))
                        .put("content_sha256", text(value.get("content_sha256")))
                        .put("path", root.relativize(path).toString().replace('\\', '/'));
                JsonNode decision = value.has("decision") ? value.get("decision")
                        : value.has("status") ? value.get("status") : NullNode.instance;
                row.set("decision", cloneNode(decision));
                records.add(row);
            }
        }
        ObjectNode index = JSON.objectNode().put("schema", "strategy-research-index/4");
        index.set("records", records);
        index = LegacyResearchNext.withHash(index);
        LegacyResearchNext.validateNextArtifact(index);
        Path target = resolve(options.getOrDefault("out", "strategy-research/index-v4.json"));
        writeMutableAtomic(target, index);
        ObjectNode result = JSON.objectNode().put("path", target.toString());
        result.set("index", index);
        print(out, result);
    }

    private static void validate(Map<String, String> options, PrintStream out) {
        JsonNode value = read(required(options, "input"));
        LegacyResearchNext.validateNextArtifact(value, validationOptions(options));
        print(out, JSON.objectNode().put("valid", true)
                .put("schema", text(value.get("schema"))));
    }

    private static void prospectiveFreeze(Map<String, String> options, PrintStream out) {
        ObjectNode input = JSON.objectNode();
        put(input, "startAt", options.get("start_at"));
        input.put("frozenAt", options.getOrDefault("frozen_at",
                java.time.Instant.now().toString()));
        input.set("lineage", options.containsKey("lineage")
                ? read(options.get("lineage")) : JSON.objectNode());
        input.set("monitoringContract", options.containsKey("monitoring")
                ? read(options.get("monitoring")) : JSON.objectNode());
        input.set("proposedAssets", options.containsKey("assets")
                ? strings(options.get("assets").split(","))
                : LegacyResearchSupport.strings(LegacyResearchNext.UNIVERSE));
        ObjectNode reservation = LegacyResearchNext.makeProspectiveReservation(input);
        ObjectNode ledger = LegacyResearchNext.makeProspectiveLedger(reservation);
        ObjectNode result = JSON.objectNode();
        if (options.containsKey("out")) {
            Path target = resolve(options.get("out"));
            writeImmutable(target, ledger);
            result.put("path", target.toString());
        } else result.putNull("path");
        result.set("reservation", reservation);
        result.set("ledger", ledger);
        print(out, result);
    }

    private static void prospectiveAppend(Map<String, String> options, PrintStream out) {
        JsonNode ledger = read(required(options, "ledger"));
        ObjectNode input = JSON.objectNode();
        put(input, "kind", options.get("kind"));
        put(input, "decisionTime", options.get("decision_time"));
        put(input, "outcomeTime", options.get("outcome_time"));
        input.set("payload", options.containsKey("payload")
                ? read(options.get("payload")) : JSON.objectNode());
        ObjectNode next = LegacyResearchNext.appendProspectiveEvent(ledger, input);
        ObjectNode result = JSON.objectNode();
        if (options.containsKey("out")) {
            Path target = resolve(options.get("out"));
            writeImmutable(target, next);
            result.put("path", target.toString());
        } else result.putNull("path");
        result.set("ledger", next);
        print(out, result);
    }

    private static ObjectNode validationOptions(Map<String, String> options) {
        ObjectNode result = JSON.objectNode();
        if (options.containsKey("public_key")) {
            result.put("publicKeyPem", readText(options.get("public_key")));
        }
        put(result, "trustRootKeyId", options.get("trust_root_key_id"));
        result.set("evidenceArtifacts", options.containsKey("evidence")
                ? read(options.get("evidence")) : JSON.objectNode());
        return result;
    }

    private static ObjectNode snapshotJson(ResearchData.SnapshotResult snapshot, Path lexicalRoot) {
        ObjectNode value = JSON.objectNode().put("snapshot_id", snapshot.snapshotId())
                .put("root", lexicalRoot.toString())
                .put("manifest", lexicalRoot.resolve("manifests/dataset-manifest.json").toString());
        if (snapshot.featureSet() == null) value.putNull("feature_set");
        else value.put("feature_set", lexicalRoot.resolve("manifests/feature-set.json").toString());
        if (snapshot.labelSet() == null) value.putNull("label_set");
        else value.put("label_set", lexicalRoot.resolve("manifests/label-set.json").toString());
        value.set("feature", snapshotArtifact(snapshot.feature(), lexicalRoot));
        value.set("labels", snapshotArtifact(snapshot.labels(), lexicalRoot));
        return value;
    }

    private static JsonNode snapshotArtifact(ObjectNode artifact, Path lexicalRoot) {
        if (artifact == null) return NullNode.instance;
        String path = text(artifact.get("path"));
        Path absolute = Path.of(path).isAbsolute() ? Path.of(path) : lexicalRoot.resolve(path);
        ObjectNode value = JSON.objectNode().put("path", absolute.normalize().toString());
        String format = text(artifact.get("format"));
        if ("jsonl".equals(format)) {
            value.put("format", format);
            value.put("sha256", text(artifact.get("sha256")));
        } else {
            value.put("sha256", text(artifact.get("sha256")));
            if (artifact.has("bytes")) value.put("bytes", artifact.path("bytes").asLong());
            value.put("format", format);
        }
        value.put("row_count", artifact.path("row_count").asLong());
        return value;
    }

    private static JsonNode unwrapRows(JsonNode value) {
        return value != null && value.isObject() && value.has("rows")
                ? value.get("rows") : value;
    }

    private static void setIfFile(
            ObjectNode target, String key, Map<String, String> options, String option) {
        if (options.containsKey(option)) target.set(key, read(options.get(option)));
    }

    private static ArrayNode strings(String[] values) {
        ArrayNode result = JSON.arrayNode();
        for (String value : values) result.add(value);
        return result;
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
    private static JsonNode read(Path path) {
        return LegacyResearchSupport.readJson(path);
    }

    private static String readText(String path) {
        return new String(PathConfinement.readSinglyLinkedFile(resolve(path), "text input"),
                StandardCharsets.UTF_8);
    }

    private static Path resolve(String value) {
        return Path.of(value == null ? "" : value).toAbsolutePath().normalize();
    }

    private static String required(Map<String, String> options, String key) {
        return requiredValue(options.get(key), key);
    }

    private static String requiredValue(String value, String key) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static double number(Map<String, String> options, String key, double fallback) {
        return options.containsKey(key) ? Double.parseDouble(options.get(key)) : fallback;
    }

    private static String first(Map<String, String> options, String... keys) {
        for (String key : keys) if (options.get(key) != null) return options.get(key);
        return null;
    }

    private static void put(ObjectNode target, String key, String value) {
        if (value != null) target.put(key, value);
    }

    private static String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : text(value);
    }

    private static void print(PrintStream out, JsonNode value) {
        out.print(NodePrettyJson.write(value));
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    static Path writeImmutable(Path target, JsonNode value) {
        Path absolute = target.toAbsolutePath().normalize();
        requireRetainedHash(value, "refusing to write retained-hash-tampered output: " + absolute);
        secureParents(absolute.getParent());
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            JsonNode existing = read(absolute);
            requireRetainedHash(existing, "retained-hash tampering: " + absolute);
            if (!text(existing.get("content_sha256"))
                    .equals(text(value.get("content_sha256")))) {
                throw new IllegalArgumentException("immutable output collision: " + absolute);
            }
            return absolute;
        }
        try {
            Files.write(absolute, LegacyResearchSupport.jsonBytes(value),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException collision) {
            throw new IllegalArgumentException("immutable output collision: " + absolute, collision);
        } catch (IOException error) {
            throw new IllegalArgumentException("immutable output cannot be written: " + absolute, error);
        }
        PathConfinement.validateSinglyLinkedFile(absolute, "immutable output");
        return absolute;
    }

    static Path writeMutableAtomic(Path target, JsonNode value) {
        requireRetainedHash(value, "refusing to write retained-hash-tampered output: "
                + target.toAbsolutePath().normalize());
        return writeAtomic(target, LegacyResearchSupport.jsonBytes(value), true);
    }

    private static void writeMutableTextAtomic(Path target, String value) {
        writeAtomic(target, value.getBytes(StandardCharsets.UTF_8), false);
    }

    private static Path writeAtomic(Path target, byte[] bytes, boolean retainedTarget) {
        Path absolute = target.toAbsolutePath().normalize();
        secureParents(absolute.getParent());
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            PathConfinement.validateSinglyLinkedFile(absolute, "mutable output");
            if (retainedTarget) {
                JsonNode existing = read(absolute);
                requireRetainedHash(existing, "retained-hash tampering: " + absolute);
            }
        }
        Path temporary = Path.of(absolute + ".tmp-" + ProcessHandle.current().pid());
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("stale atomic index temporary exists: " + temporary);
        }
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            PathConfinement.validateSinglyLinkedFile(temporary, "atomic temporary");
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IllegalArgumentException("atomic replacement is not supported: " + absolute,
                        error);
            }
            PathConfinement.validateSinglyLinkedFile(absolute, "mutable output");
            return absolute;
        } catch (IOException error) {
            throw new IllegalArgumentException("atomic output cannot be written: " + absolute, error);
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException ignored) { /* best-effort cleanup; stale temp is fail-closed later */ }
        }
    }

    private static void requireRetainedHash(JsonNode value, String message) {
        if (value == null || !value.isObject() || !value.hasNonNull("content_sha256")
                || !text(value.get("content_sha256")).equals(LegacyResearchNext.ownHash(value))) {
            throw new IllegalArgumentException(message);
        }
    }
}
