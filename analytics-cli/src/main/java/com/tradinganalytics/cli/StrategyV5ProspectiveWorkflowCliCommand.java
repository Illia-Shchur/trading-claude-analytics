package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.github.GitHubAttestationSignerV5;
import com.tradinganalytics.infrastructure.github.GitHubSettingsCaptureV5;
import com.tradinganalytics.infrastructure.github.WriterInstallationReceipts;
import com.tradinganalytics.infrastructure.security.ActionsAttestationVerifierV5;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import com.tradinganalytics.infrastructure.security.SafeTreeVerifier;
import com.tradinganalytics.infrastructure.security.WorkflowSecurityV5;
import com.tradinganalytics.research.v5.StrategyProspectiveV5;
import com.tradinganalytics.research.v5.StrategyReadinessV5;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;

/**
 * Single Java process boundary for the v5 prospective workflow.
 *
 * <p>This is intentionally a thin orchestration boundary: custody, hashes,
 * signatures, settings capture, and append-only ledger semantics remain owned
 * by their infrastructure/research classes.  The workflow uses this command
 * for every JSON-producing operation so shell steps never need a second
 * runtime or a caller-controlled JSON parser.</p>
 *
 * <p>The mode-dependent flag tail is retained verbatim by Picocli and parsed
 * inside this boundary so the workflow has one Java runtime and one command
 * grammar.</p>
 */
@Component
@Command(name = "strategy-v5-prospective-workflow",
        description = "Run fail-closed v5 prospective workflow custody operations")
public final class StrategyV5ProspectiveWorkflowCliCommand implements Callable<Integer> {
    public static final String USAGE = "usage: ./bin/analytics strategy-v5-prospective-workflow "
            + "capture-settings|verify-bundle|hydrate|drift|cycle|require-cycle|"
            + "early-audit|final-audit|no-op-audit|sign-attestation|verify-preflight|"
            + "blocked-attestation|hydrate-delta|verify-snapshot|tree|archive|snapshot-root [options]";
    private static final String AUTHORITATIVE_SCHEMA =
            "strategy-v5-authoritative-command-receipt/1";
    private static final String DEPLOYMENT_SCHEMA = "strategy-deployment-audit/1";
    private static final String DRIFT_SCHEMA = "github-settings-drift-evidence/1";
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern SENSITIVE_OPTION = Pattern.compile(
            "(?i)(private-key|private_key|privatekey|secret)");
    private static final Pattern EVENT_FILE = Pattern.compile(
            "^events/\\d{12}-[a-f0-9]{64}\\.json$");
    private static final List<String> EXACT_EXTERNAL_REQUIREMENTS = List.of(
            "GitHub branch/environment protection settings captured by API",
            "OIDC workflow subject restriction",
            "Actions-only secret physical receipt",
            "public approval keys",
            "offline activation trust root",
            "physical replay registry",
            "physical revocation registry",
            "bounded signed lease evidence");
    private static final List<String> SOURCE_ROLES = WorkflowSecurityV5.SOURCE_BUNDLE_ROLES;

    @Unmatched
    private List<String> arguments = new ArrayList<>();

    @Spec
    private CommandSpec spec;

    private final Path workingDirectory;

    public StrategyV5ProspectiveWorkflowCliCommand() {
        this(Path.of(""));
    }

    StrategyV5ProspectiveWorkflowCliCommand(Path workingDirectory) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    }

    @Override
    public Integer call() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            int status;
            try (PrintStream stdout = new PrintStream(output, true, StandardCharsets.UTF_8);
                 PrintStream stderr = new PrintStream(error, true, StandardCharsets.UTF_8)) {
                status = run(arguments.toArray(String[]::new), stdout, stderr,
                        System.getenv(), workingDirectory);
            }
            spec.commandLine().getOut().print(output.toString(StandardCharsets.UTF_8));
            spec.commandLine().getErr().print(error.toString(StandardCharsets.UTF_8));
            return status;
        } catch (RuntimeException error) {
            spec.commandLine().getErr().println(message(error));
            return 1;
        }
    }

    /** Process-boundary entry point used by the Spring command and focused tests. */
    public static int run(String[] args, PrintStream stdout, PrintStream stderr,
                          Map<String, String> environment, Path workingDirectory) {
        Map<String, String> env = environment == null ? Map.of() : Map.copyOf(environment);
        try {
            Path work = PathConfinement.requireRealDirectory(
                    workingDirectory == null ? Path.of("") : workingDirectory,
                    "workflow working directory");
            String mode = args == null || args.length == 0 ? "" : args[0];
            Map<String, String> flags = flags(args, 1);
            if (mode.isBlank() || "--help".equals(mode) || "-h".equals(mode)) {
                stderr.println(USAGE);
                return 1;
            }
            return switch (mode) {
                case "capture-settings" -> captureSettings(env, work);
                case "verify-bundle" -> verifyBundle(flags, work, stdout);
                case "hydrate" -> hydrate(flags, work, stdout);
                case "drift" -> drift(flags, work, stdout);
                case "cycle" -> cycle(flags, env, work, stdout);
                case "require-cycle" -> requireCycle(flags, work, stdout);
                case "early-audit", "final-audit" -> audit(mode, flags, env, work, stdout);
                case "no-op-audit" -> noOpAudit(flags, work, stdout);
                case "sign-attestation" -> signAttestation(env, work, stdout);
                case "blocked-attestation" -> blockedAttestation(flags, work, stdout);
                case "verify-preflight" -> verifyPreflight(flags, work, stdout);
                case "hydrate-delta" -> hydrateDelta(flags, work, stdout);
                case "verify-snapshot" -> verifySnapshot(flags, work, stdout);
                case "tree" -> verifyTree(flags, work, stdout);
                case "archive" -> verifyArchive(flags, work, stdout);
                case "snapshot-root" -> verifySnapshotRoot(flags, work, stdout);
                default -> throw new IllegalArgumentException(USAGE);
            };
        } catch (Exception error) {
            stderr.println(message(error));
            return 1;
        }
    }

    private static int captureSettings(Map<String, String> env, Path work) {
        String apiRoot = env.getOrDefault("GITHUB_API_URL", "https://api.github.com");
        GitHubSettingsCaptureV5.HttpTransport transport = new GitHubSettingsCaptureV5.HttpTransport(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                URI.create(apiRoot));
        GitHubSettingsCaptureV5.Result result = GitHubSettingsCaptureV5.capture(
                env, transport, Clock.systemUTC());
        Map<String, String> writes = new LinkedHashMap<>(env);
        writes.put("V5_SETTINGS_RECEIPT_OUT", outputPath(
                env.get("V5_SETTINGS_RECEIPT_OUT"), work,
                "github-settings-api-receipt.json"));
        writes.put("V5_SETTINGS_OUT", outputPath(
                env.get("V5_SETTINGS_OUT"), work,
                "github-deployment-settings-capture.json"));
        GitHubSettingsCaptureV5.writeArtifacts(result, writes);
        return result.verified() ? 0 : 1;
    }

    private static int verifyBundle(Map<String, String> flags, Path work, PrintStream out) {
        Path root = path(flags.getOrDefault("root", "."), work);
        String bundle = required(flags, "bundle");
        WorkflowSecurityV5.SourceBundleVerification verified =
                WorkflowSecurityV5.verifyProspectiveSourceBundle(root, bundle);
        ObjectNode result = object();
        result.put("verified", true).put("bundle", verified.bundlePhysical().relative())
                .put("ledger", verified.ledger().relative());
        out.println(pretty(result));
        return 0;
    }

    /**
     * Reopens the protected branch archive, selects the highest verified
     * prefix, and hydrates a private complete ledger for the next CAS append.
     */
    private static int hydrate(Map<String, String> flags, Path work, PrintStream out)
            throws IOException {
        Path stateRoot = path(flags.getOrDefault("state-root", ".v5-evidence-state"), work);
        Path privateLedger = path(flags.getOrDefault("ledger", ".v5-ledger"), work);
        Path bundleRoot = path(flags.getOrDefault("root", "."), work);
        if (!Files.exists(stateRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("strategy-v5 evidence checkout is missing");
        WorkflowSecurityV5.verifySafeTree(stateRoot, "strategy-v5 evidence checkout",
                SafeTreeVerifier.Options.REPOSITORY);

        copyLatestSettingsBaseline(stateRoot, work);
        List<WorkflowSecurityV5.LedgerCandidate> candidates = new ArrayList<>();
        try (var stream = Files.walk(stateRoot)) {
            stream.filter(path -> path.getFileName().toString().equals("HEAD.json"))
                    .filter(path -> path.getParent().getFileName().toString().equals("ledger"))
                    .forEach(path -> {
                        try {
                            ObjectNode ledger = StrategyProspectiveV5.readProspectiveLedger(path.getParent(),
                                    object().put("nowAt", System.currentTimeMillis())
                                            .put("allowFuture", true)
                                            .put("snapshotRootBase", stateRoot.toString()));
                            List<String> events = rows(ledger.path("events")).stream()
                                    .map(row -> text(row.get("event_sha256"))).toList();
                            candidates.add(new WorkflowSecurityV5.LedgerCandidate(
                                    path.getParent().toString(), ledger.path("sequence").asInt(),
                                    text(ledger.get("current_head_sha256")),
                                    text(ledger.get("lineage_sha256")), events));
                        } catch (RuntimeException error) {
                            throw new IllegalArgumentException("invalid historical ledger " + path
                                    + ": " + message(error), error);
                        }
                    });
        }
        WorkflowSecurityV5.LedgerCandidate selected =
                WorkflowSecurityV5.selectProspectiveLedgerCandidateV5(candidates);
        if (selected != null) hydrateCompleteLedger(confinedAbsolute(Path.of(selected.path()), work,
                "selected historical ledger"), privateLedger,
                stateRoot);

        String bundle = flags.get("bundle");
        if (bundle != null && !bundle.isBlank()) {
            WorkflowSecurityV5.SourceBundleVerification verified =
                    WorkflowSecurityV5.verifyProspectiveSourceBundle(bundleRoot, bundle);
            if (!Files.exists(privateLedger.resolve("HEAD.json"), LinkOption.NOFOLLOW_LINKS))
                WorkflowSecurityV5.copyConfinedDirectory(verified.ledger().absolute(), privateLedger,
                        "prospective source-bundle ledger");
        }
        ObjectNode result = object().put("candidates", candidates.size());
        if (selected == null) result.putNull("selected");
        else result.putObject("selected").put("path", selected.path())
                .put("sequence", selected.sequence()).put("head", selected.head())
                .put("lineage", selected.lineage());
        out.println(pretty(result));
        return 0;
    }

    private static void copyLatestSettingsBaseline(Path stateRoot, Path work) throws IOException {
        List<SettingsBaseline> rows = new ArrayList<>();
        try (var stream = Files.walk(stateRoot)) {
            stream.filter(path -> path.getFileName().toString()
                            .equals("github-deployment-settings-capture.json"))
                    .forEach(path -> {
                        Path api = path.resolveSibling("github-settings-api-receipt.json");
                        if (!Files.exists(api, LinkOption.NOFOLLOW_LINKS))
                            throw new IllegalArgumentException(
                                    "GitHub settings baseline is missing its API receipt: " + path);
                        ObjectNode capture = readObject(path);
                        ObjectNode receipt = readObject(api);
                        if (!validOwnHash(capture) || !validOwnHash(receipt)
                                || !"github-deployment-settings-capture/1".equals(
                                        text(capture.get("schema")))
                                || !"github-settings-api-receipt/1".equals(
                                        text(receipt.get("schema"))))
                            throw new IllegalArgumentException(
                                    "settings/API baseline hash or schema is invalid");
                        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(capture);
                        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(receipt);
                        Path writer = path.resolveSibling("github-writer-installation-receipt.json");
                        if (Files.exists(writer, LinkOption.NOFOLLOW_LINKS)) {
                            ObjectNode value = readObject(writer);
                            if (!WriterInstallationReceipts.verifyWriterInstallationReceipt(value,
                                    new WriterInstallationReceipts.Verification(
                                            text(capture.get("repository")), capture.get("repository_id"),
                                            WriterInstallationReceipts.WRITER_APP_ID,
                                            WriterInstallationReceipts.WRITER_INSTALLATION_ID,
                                            WriterInstallationReceipts.WRITER_APP_SLUG)))
                                throw new IllegalArgumentException(
                                        "historical writer-App installation receipt is invalid or not capture-bound");
                        }
                        rows.add(new SettingsBaseline(path, api, writer,
                                parseInstant(capture.get("captured_at")),
                                text(capture.get("content_sha256")),
                                text(receipt.get("content_sha256"))));
                    });
        }
        rows.sort(Comparator.comparing(SettingsBaseline::capturedAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SettingsBaseline::captureSha));
        if (rows.isEmpty() || rows.get(0).capturedAt() == null) return;
        SettingsBaseline selected = rows.get(0);
        if (rows.stream().anyMatch(row -> selected.capturedAt().equals(row.capturedAt())
                && !selected.captureSha().equals(row.captureSha())))
            throw new IllegalArgumentException(
                    "competing GitHub settings baselines share a capture timestamp");
        copyExclusive(selected.capture(), work.resolve(".v5-previous-settings-capture.json"));
        copyExclusive(selected.api(), work.resolve(".v5-previous-settings-api-receipt.json"));
        if (selected.writer() != null)
            copyExclusive(selected.writer(), work.resolve(".v5-previous-writer-installation-receipt.json"));
    }

    private static void hydrateCompleteLedger(Path selected, Path target, Path snapshotBase)
            throws IOException {
        ObjectNode ledger = StrategyProspectiveV5.readProspectiveLedger(selected,
                object().put("nowAt", System.currentTimeMillis()).put("allowFuture", true)
                        .put("snapshotRootBase", snapshotBase.toString()));
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("private prospective ledger destination already exists");
        Files.createDirectories(target.resolve("events"));
        for (JsonNode event : rows(ledger.path("events"))) {
            byte[] bytes = (pretty(event) + "\n").getBytes(StandardCharsets.UTF_8);
            String name = String.format(Locale.ROOT, "events/%012d-%s.json",
                    event.path("sequence").asInt(), text(event.get("event_sha256")));
            writeExclusive(target.resolve(name), bytes);
        }
        ObjectNode sourceHead = readObject(selected.resolve("HEAD.json"));
        ObjectNode head = sourceHead.deepCopy();
        head.set("prior_snapshot_root", NullNode.instance);
        head.set("prior_head_sha256", NullNode.instance);
        head.put("sequence", ledger.path("sequence").asInt());
        head.put("head_sha256", text(ledger.get("current_head_sha256")));
        ArrayNode refs = head.putArray("event_refs");
        for (JsonNode event : rows(ledger.path("events"))) {
            byte[] bytes = (pretty(event) + "\n").getBytes(StandardCharsets.UTF_8);
            String name = String.format(Locale.ROOT, "events/%012d-%s.json",
                    event.path("sequence").asInt(), text(event.get("event_sha256")));
            refs.add(object().put("sequence", event.path("sequence").asInt())
                    .put("event_sha256", text(event.get("event_sha256")))
                    .put("byte_sha256", StrategyProspectiveV5.hash(bytes)).put("path", name));
        }
        head.putNull("content_sha256");
        head.put("content_sha256", StrategyProspectiveV5.ownHash(head));
        writeExclusive(target.resolve("HEAD.json"), (pretty(head) + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static int drift(Map<String, String> flags, Path work, PrintStream out) {
        Path currentCapturePath = path(flags.getOrDefault("capture",
                "github-deployment-settings-capture.json"), work);
        Path currentApiPath = path(flags.getOrDefault("api",
                "github-settings-api-receipt.json"), work);
        ObjectNode current = readObject(currentCapturePath);
        ObjectNode api = readObject(currentApiPath);
        requireSchemaAndHash(current, "github-deployment-settings-capture/1",
                "current GitHub settings capture");
        requireSchemaAndHash(api, "github-settings-api-receipt/1",
                "current GitHub API receipt");
        if (!text(current.get("repository")).equals(text(api.get("repository"))))
            throw new IllegalArgumentException(
                    "current GitHub API receipt is not hash-valid or not bound to capture");

        ObjectNode previous = optionalObject(flags.get("previous-capture"), work);
        ObjectNode previousApi = optionalObject(flags.get("previous-api"), work);
        boolean hasPrevious = previous != null || previousApi != null;
        if (hasPrevious && (previous == null || previousApi == null))
            throw new IllegalArgumentException(
                    "partial GitHub settings baseline is invalid; refusing to rebaseline");
        boolean previousValid = !hasPrevious || (validOwnHash(previous) && validOwnHash(previousApi)
                && "github-deployment-settings-capture/1".equals(text(previous.get("schema")))
                && "github-settings-api-receipt/1".equals(text(previousApi.get("schema")))
                && text(previous.get("repository")).equals(text(current.get("repository")))
                && text(previous.get("repository_id")).equals(text(current.get("repository_id")))
                && text(previousApi.get("repository")).equals(text(api.get("repository"))));
        if (hasPrevious && !previousValid)
            throw new IllegalArgumentException("prior GitHub settings baseline is invalid or not repository-bound");

        String currentPolicy = StrategyProspectiveV5.hash(settingsPolicy(current));
        String previousPolicy = previousValid && previous != null
                ? StrategyProspectiveV5.hash(settingsPolicy(previous)) : null;
        String currentApiPolicy = StrategyProspectiveV5.hash(apiPolicy(api));
        String previousApiPolicy = previousValid && previousApi != null
                ? StrategyProspectiveV5.hash(apiPolicy(previousApi)) : null;
        List<String> changed = new ArrayList<>();
        if (!previousValid) changed.add("BASELINE_ESTABLISHED");
        else {
            if (!currentPolicy.equals(previousPolicy)) changed.add("settings_policy");
            if (!currentApiPolicy.equals(previousApiPolicy)) changed.add("api_receipt");
        }
        String status = !previousValid ? "BASELINE_ESTABLISHED" : changed.isEmpty() ? "CLEAR" : "DRIFTED";
        ObjectNode evidence = object().put("schema", DRIFT_SCHEMA).put("version", 1)
                .put("repository", text(current.get("repository")))
                .set("repository_id", current.get("repository_id") == null
                        ? NullNode.instance : current.get("repository_id").deepCopy());
        evidence.put("evidence_branch", text(current.get("evidence_branch")))
                .put("status", status)
                .put("previous_capture_sha256", previousValid && previous != null
                        ? text(previous.get("content_sha256")) : null)
                .put("current_capture_sha256", text(current.get("content_sha256")))
                .put("previous_api_receipt_sha256", previousValid && previousApi != null
                        ? text(previousApi.get("content_sha256")) : null)
                .put("current_api_receipt_sha256", text(api.get("content_sha256")));
        ArrayNode changedNode = evidence.putArray("changed_fields");
        changed.forEach(changedNode::add);
        evidence.put("compared_at", Instant.now().toString());
        evidence.put("content_sha256", StrategyProspectiveV5.ownHash(evidence));
        Path output = path(flags.getOrDefault("out", "github-settings-drift-evidence.json"), work);
        writeExclusive(output, (pretty(evidence) + "\n").getBytes(StandardCharsets.UTF_8));
        out.println(pretty(evidence));
        return "DRIFTED".equals(status) ? 1 : 0;
    }

    private static int cycle(Map<String, String> flags, Map<String, String> env, Path work,
                             PrintStream out) {
        Path receiptPath = path(flags.getOrDefault("receipt",
                "v5-shadow-cycle-receipt.json"), work);
        String bundle = first(flags.get("bundle"), env.get("V5_SOURCE_BUNDLE_INPUT"),
                env.get("V5_PROSPECTIVE_SOURCE_BUNDLE"));
        if (bundle == null || bundle.isBlank()) {
            ObjectNode receipt = commandReceipt("prospective-runner", "BLOCKED", List.of(),
                    List.of("PROSPECTIVE_LIVE_SOURCE_UNCONFIGURED"),
                    object().put("mode", "BLOCKED_LIVE_SOURCE_UNCONFIGURED")
                            .put("reason", "no verified frozen Binance completed-4h acquisition adapter is configured for this environment"));
            writeExclusive(receiptPath, (pretty(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
            ObjectNode output = object().putNull("result");
            output.set("receipt", receipt);
            output.put("status", "BLOCKED");
            out.println(pretty(output));
            return 1;
        }
        try {
            WorkflowSecurityV5.SourceBundleVerification verified =
                    WorkflowSecurityV5.verifyProspectiveSourceBundle(work, bundle);
            Map<String, WorkflowSecurityV5.ConfinedJson> refs = verified.references();
            Path ledger = flags.containsKey("ledger")
                    ? path(flags.get("ledger"), work)
                    : verified.ledger().absolute();
            if (flags.containsKey("ledger")) {
                WorkflowSecurityV5.verifySafeTree(ledger, "prospective ledger",
                        SafeTreeVerifier.Options.EVIDENCE);
            }
            Path reservation = refs.get("reservation").absolute();
            Path source = refs.get("source_receipt").absolute();
            Path barPath = refs.get("bar").absolute();
            Path feature = refs.get("feature_input").absolute();
            Path candidate = refs.get("candidate_set").absolute();
            Path evaluator = refs.get("evaluator_code").absolute();
            Path decision = refs.get("signal_decision").absolute();
            if (flags.containsKey("reservation")
                    && !path(flags.get("reservation"), work).equals(reservation))
                throw new IllegalArgumentException("explicit reservation path conflicts with the frozen source bundle");
            List<String> missing = new ArrayList<>();
            if (!Files.exists(ledger.resolve("HEAD.json"), LinkOption.NOFOLLOW_LINKS))
                missing.add("ledger: HEAD.json path does not exist: " + ledger.resolve("HEAD.json"));
            if (flags.containsKey("expected-head")
                    && !HASH.matcher(flags.get("expected-head")).matches())
                missing.add("expected CAS head: must be a SHA-256 head hash");
            if (!missing.isEmpty()) {
                ObjectNode receipt = commandReceipt("prospective-runner", "BLOCKED",
                        sourceInputs(verified), missing,
                        object().put("mode", "BLOCKED_NO_PRIVATE_KEY_PATH")
                                .put("reason", "one completed-bar SHADOW cycle requires every physical ledger/reservation/source/bar/feature/candidate/evaluator/decision prerequisite"));
                writeExclusive(receiptPath, (pretty(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
                ObjectNode output = object().putNull("result");
                output.set("receipt", receipt);
                output.put("status", "BLOCKED");
                out.println(pretty(output));
                return 1;
            }
            ObjectNode bar = object(readObject(barPath), "completed bar");
            ObjectNode ledgerBefore = StrategyProspectiveV5.readProspectiveLedger(ledger,
                    object().put("nowAt", System.currentTimeMillis()).put("allowFuture", true));
            String bundleLineage = text(verified.bundle().get("lineage_sha256"));
            if (!bundleLineage.equals(text(ledgerBefore.get("lineage_sha256"))))
                throw new IllegalArgumentException(
                        "hydrated prospective ledger lineage differs from frozen source bundle");
            String genesis = StrategyProspectiveV5.hash(object().put(
                    "schema", "strategy-prospective-ledger-genesis/1")
                    .put("lineage_sha256", bundleLineage));
            if (ledgerBefore.path("sequence").asInt() == 0
                    && !text(ledgerBefore.get("current_head_sha256"))
                            .equals(text(verified.bundle().get("expected_head_sha256")))
                    || ledgerBefore.path("sequence").asInt() > 0
                    && !genesis.equals(text(ledgerBefore.path("events").get(0)
                            .get("previous_head_sha256"))))
                throw new IllegalArgumentException(
                        "prospective ledger chain is not anchored to the frozen genesis");
            String expectedHead = first(flags.get("expected-head"),
                    text(ledgerBefore.get("current_head_sha256")));
            if (!expectedHead.equals(text(ledgerBefore.get("current_head_sha256"))))
                throw new IllegalArgumentException(
                        "explicit expected CAS head differs from hydrated prospective ledger");
            ObjectNode noOpOptions = object();
            noOpOptions.set("ledger", ledgerBefore);
            noOpOptions.set("bar", bar);
            noOpOptions.put("sourceReceiptSha256", StrategyProspectiveV5.hash(refs.get("source_receipt").bytes()))
                    .put("signalDecisionSha256", StrategyProspectiveV5.hash(refs.get("signal_decision").bytes()))
                    .put("reservationSha256", StrategyProspectiveV5.hash(refs.get("reservation").bytes()))
                    .put("candidateSetSha256", StrategyProspectiveV5.hash(refs.get("candidate_set").bytes()))
                    .put("evaluatorCodeSha256", StrategyProspectiveV5.hash(refs.get("evaluator_code").bytes()))
                    .put("featureInputSha256", StrategyProspectiveV5.hash(refs.get("feature_input").bytes()));
            if (StrategyProspectiveV5.verifyCompletedBarNoOp(noOpOptions)) {
                ObjectNode receipt = commandReceipt("prospective-runner", "COMPLETE",
                        sourceInputs(verified), List.of("NO_NEW_COMPLETED_BAR: exact latest completed 4h bar and all source/decision bindings already exist; no append or PR created"),
                        object().put("mode", "NO_NEW_COMPLETED_BAR")
                                .put("ledger_head_sha256", text(ledgerBefore.get("current_head_sha256")))
                                .put("ledger_sequence", ledgerBefore.path("sequence").asInt()));
                writeExclusive(receiptPath, (pretty(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
                ObjectNode output = object().putNull("result");
                output.set("receipt", receipt);
                output.put("status", "NO_NEW_COMPLETED_BAR").put("no_op", true);
                out.println(pretty(output));
                return 0;
            }
            ObjectNode append = object().put("path", ledger.toString())
                    .put("reservationPath", reservation.toString())
                    .put("reservationSha256", StrategyProspectiveV5.hash(refs.get("reservation").bytes()))
                    .put("sourceReceiptPath", source.toString())
                    .put("sourceReceiptSha256", StrategyProspectiveV5.hash(refs.get("source_receipt").bytes()))
                    .put("featureInputPath", feature.toString())
                    .put("featureInputSha256", StrategyProspectiveV5.hash(refs.get("feature_input").bytes()))
                    .put("candidateSetPath", candidate.toString())
                    .put("candidateSetSha256", StrategyProspectiveV5.hash(refs.get("candidate_set").bytes()))
                    .put("evaluatorCodePath", evaluator.toString())
                    .put("evaluatorCodeSha256", StrategyProspectiveV5.hash(refs.get("evaluator_code").bytes()))
                    .put("signalDecisionPath", decision.toString())
                    .put("signalDecisionSha256", StrategyProspectiveV5.hash(refs.get("signal_decision").bytes()))
                    .put("expectedHeadSha256", expectedHead)
                    .put("nowAt", System.currentTimeMillis());
            append.set("bar", bar);
            ObjectNode result = StrategyProspectiveV5.appendCompletedBarCycle(append);
            ObjectNode ledgerAfter = StrategyProspectiveV5.readProspectiveLedger(ledger,
                    object().put("nowAt", System.currentTimeMillis()).put("allowFuture", true));
            ObjectNode receipt = commandReceipt("prospective-runner", "COMPLETE",
                    sourceInputs(verified),
                    List.of(reference(ledger.resolve("HEAD.json"), work, "prospective_ledger_head")),
                    List.of("SHADOW only; no activation or private key path is available"),
                    object().put("mode", "ONE_COMPLETED_BAR_SHADOW_CYCLE")
                            .put("ledger_prior_head_sha256", text(ledgerBefore.get("current_head_sha256")))
                            .put("ledger_new_head_sha256", text(ledgerAfter.get("current_head_sha256")))
                            .put("ledger_sequence", ledgerAfter.path("sequence").asInt())
                            .put("activated", false));
            writeExclusive(receiptPath, (pretty(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
            ObjectNode output = object();
            output.set("result", result);
            output.set("receipt", receipt);
            output.put("status", "COMPLETE");
            out.println(pretty(output));
            return 0;
        } catch (Exception error) {
            ObjectNode receipt = commandReceipt("prospective-runner", "BLOCKED",
                    bestEffortSourceBundleInput(bundle, work),
                    List.of("COMPLETED_BAR_CYCLE_BLOCKED: " + message(error)),
                    object().put("mode", "BLOCKED_CYCLE_RECOMPUTATION_OR_CUSTODY")
                            .put("reason", message(error)));
            writeExclusive(receiptPath, (pretty(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
            ObjectNode output = object().putNull("result");
            output.set("receipt", receipt);
            output.put("status", "BLOCKED");
            out.println(pretty(output));
            return 1;
        }
    }

    private static List<ObjectNode> bestEffortSourceBundleInput(String bundle, Path work) {
        if (bundle == null || bundle.isBlank()) return List.of();
        try {
            WorkflowSecurityV5.ConfinedJson physical = WorkflowSecurityV5.readConfinedJson(
                    work, bundle, "prospective source bundle");
            return List.of(reference(physical, "source_bundle"));
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static int requireCycle(Map<String, String> flags, Path work, PrintStream out) {
        ObjectNode receipt = readObject(path(flags.getOrDefault("receipt",
                "v5-shadow-cycle-receipt.json"), work));
        validateCommandReceipt(receipt);
        if ("COMPLETE".equals(text(receipt.get("status")))
                && !receipt.path("details").path("active").asBoolean(true)) return 0;
        if ("BLOCKED".equals(text(receipt.get("status")))
                && rows(receipt.get("limitations")).stream().anyMatch(row ->
                        "PROSPECTIVE_LIVE_SOURCE_UNCONFIGURED".equals(text(row)))) {
            out.println("PROSPECTIVE_LIVE_SOURCE_UNCONFIGURED: no verified frozen Binance adapter is configured");
        }
        throw new IllegalArgumentException("Completed-bar SHADOW cycle is blocked: "
                + String.join("; ", stringRows(receipt.get("limitations"))));
    }

    private static int audit(String mode, Map<String, String> flags, Map<String, String> env,
                             Path work, PrintStream out) {
        ObjectNode audit = makeDeploymentAudit(env, work,
                "early-audit".equals(mode) || "final-audit".equals(mode));
        Path output = path(flags.getOrDefault("out",
                "early-audit".equals(mode) ? "v5-deployment-audit-early.json"
                        : "v5-deployment-audit.json"), work);
        writeExclusive(output, (pretty(audit) + "\n").getBytes(StandardCharsets.UTF_8));
        out.println(pretty(audit));
        return audit.path("blocked").asBoolean(true)
                && !audit.path("shadow_append_eligible").asBoolean(false) ? 1 : 0;
    }

    private static int noOpAudit(Map<String, String> flags, Path work, PrintStream out) {
        Path earlyPath = path(flags.getOrDefault("early", "v5-deployment-audit-early.json"), work);
        ObjectNode early = readObject(earlyPath);
        if (!validOwnHash(early)) throw new IllegalArgumentException("early deployment audit is tampered");
        ObjectNode audit = early.deepCopy();
        audit.with("checks").put("no_new_completed_bar", true);
        audit.put("shadow_append_eligible", false).put("activation_eligible", false)
                .put("blocked", false)
                .put("reason", "NO_NEW_COMPLETED_BAR: no append, attestation, publication, or deployment transition was requested")
                .put("blocked_until_external_prerequisites", false)
                .put("content_sha256", StrategyProspectiveV5.ownHash(audit));
        Path output = path(flags.getOrDefault("out", "v5-deployment-audit.json"), work);
        writeExclusive(output, (pretty(audit) + "\n").getBytes(StandardCharsets.UTF_8));
        out.println(pretty(audit));
        return 0;
    }

    private static int signAttestation(Map<String, String> env, Path work, PrintStream out) {
        GitHubAttestationSignerV5.Result result = GitHubAttestationSignerV5.sign(
                new GitHubAttestationSignerV5.Options(work, env, Clock.systemUTC(), null));
        Path registry = path(env.get("V5_ATTESTATION_KEY_REGISTRY_PATH"), work);
        Path copy = path(env.getOrDefault("V5_ATTESTATION_REGISTRY_OUT",
                "v5-attestation-key-registry.json"), work);
        copyExclusive(registry, copy);
        out.println(pretty(result.summary()));
        return 0;
    }

    private static int blockedAttestation(Map<String, String> flags, Path work, PrintStream out) {
        ObjectNode receipt = commandReceipt("prospective-runner", "BLOCKED", List.of(), List.of(
                "ACTIONS_ATTESTATION_KEY_OR_REGISTRY_UNCONFIGURED"), object()
                        .put("mode", "BLOCKED_MISSING_PROTECTED_KEY_OR_FROZEN_REGISTRY"));
        Path output = path(flags.getOrDefault("out", "v5-actions-attestation-receipt.json"), work);
        writeExclusive(output, (pretty(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
        out.println(pretty(receipt));
        return 1;
    }

    private static int verifyPreflight(Map<String, String> flags, Path work, PrintStream out) {
        Path root = path(flags.getOrDefault("root", ".v5-preflight"), work);
        WorkflowSecurityV5.verifySafeTree(root, "downloaded v5 preflight artifact tree",
                SafeTreeVerifier.Options.EVIDENCE);
        ObjectNode audit = readObject(root.resolve("v5-deployment-audit.json"));
        ObjectNode receipt = readObject(root.resolve("v5-shadow-cycle-receipt.json"));
        ObjectNode capture = readObject(root.resolve("github-deployment-settings-capture.json"));
        ObjectNode api = readObject(root.resolve("github-settings-api-receipt.json"));
        ObjectNode drift = readObject(root.resolve("github-settings-drift-evidence.json"));
        ObjectNode writer = readObject(root.resolve("github-writer-installation-receipt.json"));
        requireSchemaAndHash(audit, DEPLOYMENT_SCHEMA, "deployment audit");
        boolean auditBlocked = !audit.path("blocked").isBoolean()
                || audit.path("blocked").asBoolean();
        boolean auditShadowEligible = audit.path("shadow_append_eligible").isBoolean()
                && audit.path("shadow_append_eligible").asBoolean();
        if (auditBlocked && !auditShadowEligible)
            throw new IllegalArgumentException(
                    "deployment custody is not append-eligible; activation remains blocked");
        validateCommandReceipt(receipt);
        if (!"COMPLETE".equals(text(receipt.get("status")))
                || receipt.path("details").path("active").asBoolean(true))
            throw new IllegalArgumentException("preflight cycle is not a completed inactive SHADOW receipt");
        requireSchemaAndHash(capture, "github-deployment-settings-capture/1", "settings capture");
        requireSchemaAndHash(api, "github-settings-api-receipt/1", "settings API receipt");
        requireSchemaAndHash(drift, DRIFT_SCHEMA, "settings drift evidence");
        String tokenKind = text(capture.path("settings_token_identity").get("token_kind"));
        if ("APP".equals(tokenKind)
                && (!exactSettingsAuditorIdentity(capture.path("settings_token_identity"), tokenKind)
                || !exactSettingsAuditorIdentity(api.path("settings_token_identity"), tokenKind)
                || !auditorSecretExact(capture.get("settings_token_secret"))
                || !auditorSecretExact(api.get("settings_token_secret"))
                || !exactSettingsAuditorInstallation(capture.get("settings_auditor_installation"),
                        text(capture.get("repository")), capture.get("repository_id"), tokenKind)
                || !exactSettingsAuditorInstallation(api.get("settings_auditor_installation"),
                        text(capture.get("repository")), capture.get("repository_id"), tokenKind)
                || !sameCanonical(capture.get("settings_auditor_installation"),
                        api.get("settings_auditor_installation"))))
            throw new IllegalArgumentException("settings auditor installation proof is not exact");
        if (!api.path("verified").asBoolean(false) || api.path("blockers").size() != 0
                || !validDrift(drift, capture, api)
                || !WriterInstallationReceipts.verifyWriterInstallationReceipt(writer,
                        text(capture.get("repository")), capture.get("repository_id")))
            throw new IllegalArgumentException("preflight custody evidence is incomplete");
        ObjectNode attestation = readObject(root.resolve("v5-actions-attestation.json"));
        ObjectNode registry = readObject(root.resolve("v5-attestation-key-registry.json"));
        ObjectNode options = object();
        options.set("attestation", attestation);
        options.set("capture", capture);
        options.set("publication", NullNode.instance);
        options.put("bytesSha256", hashFile(root.resolve("github-deployment-settings-capture.json")))
                .put("nowMs", System.currentTimeMillis())
                .put("apiReceiptSha256", hashFile(root.resolve("github-settings-api-receipt.json")))
                .put("cycleReceiptSha256", hashFile(root.resolve("v5-shadow-cycle-receipt.json")))
                .put("trustedKeyRegistrySha256", text(registry.get("content_sha256")))
                .put("trustedKeyRegistryByteSha256", hashFile(root.resolve("v5-attestation-key-registry.json")));
        options.set("trustedKeyRegistry", registry);
        if (!flags.getOrDefault("fingerprint", "").isBlank())
            options.put("pinnedFingerprint", flags.get("fingerprint"));
        if (!StrategyReadinessV5.verifyActionsAttestation(options))
            throw new IllegalArgumentException("Actions attestation custody verification failed");
        out.println(pretty(object().put("verified", true)));
        return 0;
    }

    private static int hydrateDelta(Map<String, String> flags, Path work, PrintStream out)
            throws IOException {
        Path baseRoot = path(flags.getOrDefault("base", "evidence/prospective-v5"), work);
        Path target = path(required(flags, "target"), work);
        Path preflight = path(flags.getOrDefault("preflight", ".v5-preflight"), work);
        WorkflowSecurityV5.verifySafeTree(preflight, "downloaded v5 preflight artifact tree",
                SafeTreeVerifier.Options.EVIDENCE);
        Path preflightLedger = PathConfinement.resolve(preflight, ".v5-ledger",
                "preflight ledger", PathConfinement.ExpectedType.DIRECTORY).absolute();
        ObjectNode sourceHead = readObject(PathConfinement.resolve(preflightLedger, "HEAD.json",
                "preflight ledger HEAD", PathConfinement.ExpectedType.FILE).absolute());
        requireSchemaAndHash(sourceHead, "strategy-prospective-ledger-index/1",
                "preflight ledger HEAD");
        List<WorkflowSecurityV5.LedgerCandidate> candidates = new ArrayList<>();
        if (Files.exists(baseRoot, LinkOption.NOFOLLOW_LINKS)) {
            WorkflowSecurityV5.verifySafeTree(baseRoot, "trusted prospective evidence snapshots",
                    SafeTreeVerifier.Options.EVIDENCE);
            try (var stream = Files.list(baseRoot)) {
                stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> HASH.matcher(path.getFileName().toString()).matches())
                        .forEach(root -> {
                            Path ledgerPath = root.resolve("ledger");
                            try {
                                ObjectNode ledger = StrategyProspectiveV5.readProspectiveLedger(ledgerPath,
                                        object().put("nowAt", System.currentTimeMillis())
                                                .put("allowFuture", true)
                                                .put("snapshotRootBase", baseRoot.toString()));
                                candidates.add(new WorkflowSecurityV5.LedgerCandidate(
                                        ledgerPath.toString(), ledger.path("sequence").asInt(),
                                        text(ledger.get("current_head_sha256")),
                                        text(ledger.get("lineage_sha256")),
                                        rows(ledger.path("events")).stream()
                                                .map(row -> text(row.get("event_sha256"))).toList()));
                            } catch (RuntimeException error) {
                                throw new IllegalArgumentException("trusted evidence snapshot is invalid: "
                                        + root.getFileName() + ": " + message(error), error);
                            }
                        });
            }
        }
        WorkflowSecurityV5.LedgerCandidate prior =
                WorkflowSecurityV5.selectProspectiveLedgerCandidateV5(candidates);
        int priorSequence = prior == null ? 0 : prior.sequence();
        int sourceSequence = sourceHead.path("sequence").asInt(Integer.MIN_VALUE);
        List<JsonNode> allRefs = rows(sourceHead.path("event_refs"));
        if (!sourceHead.path("sequence").isIntegralNumber() || sourceSequence < 0
                || allRefs.size() != sourceSequence)
            throw new IllegalArgumentException("preflight ledger event inventory is invalid");
        String previousHead = WorkflowSecurityV5.prospectiveLedgerGenesis(
                text(sourceHead.get("lineage_sha256")));
        for (int index = 0; index < allRefs.size(); index++) {
            JsonNode event = allRefs.get(index);
            String eventHead = validateDeltaReference(event, index + 1, preflightLedger);
            if (!text(event.get("previous_head_sha256")).equals(previousHead))
                throw new IllegalArgumentException("preflight ledger event chain is invalid");
            previousHead = eventHead;
        }
        if (!previousHead.equals(text(sourceHead.get("head_sha256"))))
            throw new IllegalArgumentException("preflight ledger head is not bound to its events");
        if (sourceSequence <= priorSequence
                || (prior != null && allRefs.size() <= priorSequence))
            throw new IllegalArgumentException(
                    "preflight ledger is not a strict successor of the trusted evidence snapshot");
        List<JsonNode> refs = allRefs.stream()
                .filter(ref -> ref.path("sequence").asInt() > priorSequence).toList();
        if (refs.size() != sourceSequence - priorSequence)
            throw new IllegalArgumentException("preflight ledger delta inventory is incomplete");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("prospective snapshot target already exists");
        Files.createDirectories(target.resolve("ledger/events"));
        ObjectNode deltaHead = sourceHead.deepCopy();
        if (prior == null) deltaHead.putNull("prior_snapshot_root").putNull("prior_head_sha256");
        else deltaHead.put("prior_snapshot_root", Path.of(prior.path()).getParent().getFileName().toString())
                .put("prior_head_sha256", prior.head());
        ArrayNode deltaRefs = deltaHead.putArray("event_refs");
        for (JsonNode ref : refs) {
            String relative = PathConfinement.repositoryRelativePath(text(ref.get("path")),
                    "preflight delta event");
            Path source = PathConfinement.resolve(preflightLedger, relative,
                    "preflight delta event", PathConfinement.ExpectedType.FILE).absolute();
            Path destination = confinedPath(relative, target.resolve("ledger"),
                    "prospective delta event output");
            deltaRefs.add(ref.deepCopy());
            copyExclusive(source, destination);
        }
        deltaHead.putNull("content_sha256").put("content_sha256", StrategyProspectiveV5.ownHash(deltaHead));
        writeExclusive(target.resolve("ledger/HEAD.json"), (pretty(deltaHead) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        out.println(pretty(object().put("prior_sequence", priorSequence)
                .put("delta_events", refs.size())));
        return 0;
    }

    private static String validateDeltaReference(JsonNode ref, int expectedSequence,
                                                 Path preflightLedger) {
        if (ref == null || !ref.path("sequence").isIntegralNumber()
                || ref.path("sequence").asInt(Integer.MIN_VALUE) != expectedSequence
                || !HASH.matcher(text(ref.get("event_sha256"))).matches()
                || !HASH.matcher(text(ref.get("byte_sha256"))).matches())
            throw new IllegalArgumentException("preflight ledger event reference is invalid");
        String relative = PathConfinement.repositoryRelativePath(text(ref.get("path")),
                "preflight ledger event");
        if (!EVENT_FILE.matcher(relative).matches())
            throw new IllegalArgumentException("preflight ledger event path is invalid");
        Path source = PathConfinement.resolve(preflightLedger, relative,
                "preflight ledger event", PathConfinement.ExpectedType.FILE).absolute();
        byte[] bytes = PathConfinement.readSinglyLinkedFile(source, "preflight ledger event");
        if (!StrategyProspectiveV5.hash(bytes).equals(text(ref.get("byte_sha256"))))
            throw new IllegalArgumentException("preflight ledger event byte hash mismatch");
        JsonNode event = JsonHashes.parse(bytes, source.toString());
        if (!event.isObject() || event.path("sequence").asInt(Integer.MIN_VALUE) != expectedSequence
                || !text(event.get("event_sha256")).equals(text(ref.get("event_sha256")))
                || !text(event.get("event_sha256")).equals(StrategyProspectiveV5.ownHash(event,
                        "event_sha256")))
            throw new IllegalArgumentException("preflight ledger event hash is invalid");
        return text(event.get("event_sha256"));
    }

    private static int verifySnapshot(Map<String, String> flags, Path work, PrintStream out) {
        Path proposed = path(required(flags, "proposed"), work);
        Path trusted = path(flags.getOrDefault("trusted-base", "."), work);
        Path registry = flags.containsKey("registry") ? path(flags.get("registry"), work) : null;
        String fingerprint = flags.getOrDefault("fingerprint", "");
        WorkflowSecurityV5.ProspectiveSnapshotVerification result =
                WorkflowSecurityV5.verifyProspectiveSnapshotV5(
                        new WorkflowSecurityV5.ProspectiveSnapshotOptions(proposed, trusted,
                                registry, fingerprint.isBlank() ? null : fingerprint,
                                System.currentTimeMillis()));
        ObjectNode output = object().put("verified_artifacts", result.verified())
                .put("ledger_snapshots", 1);
        if (result.trustedBaseSequence() == null) output.putNull("trusted_base_latest_sequence");
        else output.put("trusted_base_latest_sequence", result.trustedBaseSequence());
        output.put("proposed_sequence", result.sequence());
        out.println(pretty(output));
        return 0;
    }

    private static int verifyTree(Map<String, String> flags, Path work, PrintStream out) {
        Path root = path(required(flags, "path"), work);
        SafeTreeVerifier.Options options = Boolean.parseBoolean(flags.getOrDefault("repository", "false"))
                ? SafeTreeVerifier.Options.REPOSITORY : SafeTreeVerifier.Options.EVIDENCE;
        SafeTreeVerifier.TreeSummary summary = WorkflowSecurityV5.verifySafeTree(root,
                flags.getOrDefault("label", "evidence tree"), options);
        out.println(pretty(object().put("files", summary.files()).put("total_bytes", summary.totalBytes())));
        return 0;
    }

    private static int verifyArchive(Map<String, String> flags, Path work, PrintStream out) {
        Path archive = path(required(flags, "path"), work);
        SafeTreeVerifier.TreeSummary summary = WorkflowSecurityV5.verifyTarArchive(archive,
                flags.getOrDefault("label", "evidence archive"),
                Boolean.parseBoolean(flags.getOrDefault("repository", "false"))
                        ? SafeTreeVerifier.Options.REPOSITORY : SafeTreeVerifier.Options.EVIDENCE);
        out.println(pretty(object().put("files", summary.files()).put("total_bytes", summary.totalBytes())));
        return 0;
    }

    private static int verifySnapshotRoot(Map<String, String> flags, Path work, PrintStream out)
            throws IOException {
        Path diff = path(required(flags, "diff"), work);
        String expected = required(flags, "expected-root");
        List<String> changed = Files.readAllLines(diff).stream().filter(line -> !line.isBlank())
                .map(line -> line.split("\\t", -1)).filter(parts -> parts.length > 1)
                .map(parts -> parts[1]).toList();
        String actual = WorkflowSecurityV5.requireSingleProspectiveSnapshotRootV5(changed);
        if (!actual.equals(expected))
            throw new IllegalArgumentException("proposed snapshot root does not match the additive diff");
        out.println(actual);
        return 0;
    }

    /* ------------------------------------------------------------------ */
    /* Deployment audit.  This deliberately fails closed when a physical   */
    /* prerequisite is absent.  It does not infer activation from a prose   */
    /* flag, and it never emits active:true.                              */
    /* ------------------------------------------------------------------ */

    private static ObjectNode makeDeploymentAudit(Map<String, String> env, Path work,
                                                  boolean early) {
        ObjectNode capture = optionalObject(work.resolve("github-deployment-settings-capture.json"));
        ObjectNode api = optionalObject(work.resolve("github-settings-api-receipt.json"));
        ObjectNode drift = optionalObject(work.resolve("github-settings-drift-evidence.json"));
        ObjectNode cycle = optionalObject(work.resolve("v5-shadow-cycle-receipt.json"));
        ObjectNode attestation = optionalObject(work.resolve("v5-actions-attestation.json"));
        ObjectNode registry = optionalObject(work.resolve("v5-attestation-key-registry.json"));
        ObjectNode writer = optionalObject(work.resolve("github-writer-installation-receipt.json"));
        boolean captureValid = validSchemaHash(capture, "github-deployment-settings-capture/1")
                && capture.path("verified").asBoolean(false);
        boolean apiValid = validSchemaHash(api, "github-settings-api-receipt/1")
                && api.path("verified").asBoolean(false) && api.path("blockers").size() == 0;
        boolean driftValid = validDrift(drift, capture, api);
        boolean cycleValid = validSchemaHash(cycle, AUTHORITATIVE_SCHEMA)
                && "COMPLETE".equals(text(cycle.get("status")))
                && !cycle.path("details").path("active").asBoolean(true);
        boolean writerValid = writer != null && capture != null
                && WriterInstallationReceipts.verifyWriterInstallationReceipt(writer,
                        text(capture.get("repository")), capture.get("repository_id"));
        boolean actionsCustody = captureValid && apiValid && cycleValid && driftValid
                && attestation != null && registry != null
                && validOwnHash(attestation) && validOwnHash(registry);
        if (actionsCustody) {
            try {
                for (ObjectNode value : List.of(capture, api, cycle, attestation, registry))
                    ResearchSchemaRegistry.defaultRegistry().validateContractSchema(value);
            } catch (RuntimeException invalid) {
                actionsCustody = false;
            }
        }
        if (actionsCustody) {
            String tokenKind = text(capture.path("settings_token_identity").get("token_kind"));
            JsonNode captureSecret = capture.get("settings_token_secret");
            JsonNode apiSecret = api.get("settings_token_secret");
            JsonNode captureActionsSecret = capture.get("actions_secret");
            JsonNode apiActionsSecret = api.get("actions_secret");
            JsonNode capturePermissions = capture.get("actions_permissions");
            JsonNode apiPermissions = api.get("actions_permissions");
            JsonNode captureWriterEnvironment = capture.get("writer_environment_protection");
            JsonNode apiWriterEnvironment = api.get("writer_environment_protection");
            JsonNode captureWriterSecret = capture.get("evidence_writer_secret");
            JsonNode apiWriterSecret = api.get("evidence_writer_secret");
            JsonNode captureRulesets = capture.get("rulesets");
            JsonNode apiRulesets = api.get("rulesets");
            actionsCustody = (!"APP".equals(tokenKind)
                    || (auditorSecretExact(captureSecret) && auditorSecretExact(apiSecret)
                    && exactSettingsAuditorIdentity(capture.path("settings_token_identity"), tokenKind)
                    && exactSettingsAuditorIdentity(api.path("settings_token_identity"), tokenKind)
                    && exactSettingsAuditorInstallation(capture.get("settings_auditor_installation"),
                            text(capture.get("repository")), capture.get("repository_id"), tokenKind)
                    && exactSettingsAuditorInstallation(api.get("settings_auditor_installation"),
                            text(capture.get("repository")), capture.get("repository_id"), tokenKind)
                    && sameCanonical(captureSecret, apiSecret)))
                    && captureActionsSecret != null && apiActionsSecret != null
                    && captureActionsSecret.path("verified").asBoolean(false)
                    && apiActionsSecret.path("verified").asBoolean(false)
                    && sameCanonical(captureActionsSecret, apiActionsSecret)
                    && capturePermissions != null && apiPermissions != null
                    && capturePermissions.path("verified").asBoolean(false)
                    && apiPermissions.path("verified").asBoolean(false)
                    && sameCanonical(capturePermissions, apiPermissions)
                    && captureWriterEnvironment != null && apiWriterEnvironment != null
                    && captureWriterEnvironment.path("verified").asBoolean(false)
                    && apiWriterEnvironment.path("verified").asBoolean(false)
                    && !captureWriterEnvironment.path("can_admins_bypass").asBoolean(true)
                    && !apiWriterEnvironment.path("can_admins_bypass").asBoolean(true)
                    && StrategyReadinessV5.environmentReviewSafe(captureWriterEnvironment)
                    && StrategyReadinessV5.environmentReviewSafe(apiWriterEnvironment)
                    && sameCanonical(captureWriterEnvironment, apiWriterEnvironment)
                    && captureWriterSecret != null && apiWriterSecret != null
                    && captureWriterSecret.path("verified").asBoolean(false)
                    && apiWriterSecret.path("verified").asBoolean(false)
                    && sameCanonical(captureWriterSecret, apiWriterSecret)
                    && captureRulesets != null && apiRulesets != null
                    && captureRulesets.path("layered_policy_verified").asBoolean(false)
                    && apiRulesets.path("layered_policy_verified").asBoolean(false)
                    && captureRulesets.path("actions_bypass_app_ids").isArray()
                    && captureRulesets.path("actions_bypass_app_ids").isEmpty()
                    && apiRulesets.path("actions_bypass_app_ids").isArray()
                    && apiRulesets.path("actions_bypass_app_ids").isEmpty()
                    && sameCanonical(captureRulesets, apiRulesets);
        }
        if (actionsCustody) {
            ObjectNode options = object();
            options.set("attestation", attestation);
            options.set("capture", capture);
            options.set("publication", NullNode.instance);
            options.put("bytesSha256", hashIfPresent(work.resolve("github-deployment-settings-capture.json")))
                    .put("nowMs", System.currentTimeMillis())
                    .put("apiReceiptSha256", hashIfPresent(work.resolve("github-settings-api-receipt.json")))
                    .put("cycleReceiptSha256", hashIfPresent(work.resolve("v5-shadow-cycle-receipt.json")))
                    .put("trustedKeyRegistrySha256", text(registry.get("content_sha256")))
                    .put("trustedKeyRegistryByteSha256", hashIfPresent(work.resolve("v5-attestation-key-registry.json")));
            options.set("trustedKeyRegistry", registry);
            String pinned = env.getOrDefault("V5_ATTESTATION_KEY_FINGERPRINT", "");
            if (!pinned.isBlank()) options.put("pinnedFingerprint", pinned);
            try { actionsCustody = StrategyReadinessV5.verifyActionsAttestation(options); }
            catch (RuntimeException ignored) { actionsCustody = false; }
        }
        boolean shadowEligible = captureValid && actionsCustody && cycleValid && driftValid;
        ObjectNode checks = object();
        checks.put("repository_private", captureValid && capture.path("repository_visibility_verified").asBoolean(false)
                && Set.of("PUBLIC", "PRIVATE").contains(text(capture.get("repository_visibility"))));
        checks.put("append_only_branch_protected", captureValid
                && capture.path("branch_protection").path("verified").asBoolean(false)
                && !capture.path("branch_protection").path("allow_force_pushes").asBoolean(true)
                && !capture.path("branch_protection").path("allow_deletions").asBoolean(true));
        checks.put("prospective_environment_protected", captureValid
                && capture.path("environment_protection").path("verified").asBoolean(false)
                && StrategyReadinessV5.environmentReviewSafe(capture.path("environment_protection")));
        checks.put("oidc_subject_restricted", captureValid
                && capture.path("oidc_subject_restricted").asBoolean(false)
                && "https://token.actions.githubusercontent.com".equals(
                        text(capture.path("oidc_claims").get("iss"))));
        checks.put("actions_only_secret", actionsOnlySecret(env, work, capture, api, attestation, registry));
        checks.put("github_settings_drift", actionsCustody && driftValid);
        boolean trustRoot = verifyTrustRoot(env, work);
        checks.put("offline_trust_root_verified", trustRoot);
        ObjectNode keys = optionalObject(env.get("V5_PUBLIC_KEYS_PATH"), work);
        checks.put("asset_key_present", keys != null
                && ActionsAttestationVerifierV5.publicKeyFingerprint(
                        text(keys.get("asset_public_key_pem"))) != null);
        checks.put("portfolio_key_present", keys != null
                && ActionsAttestationVerifierV5.publicKeyFingerprint(
                        text(keys.get("portfolio_public_key_pem"))) != null);
        checks.put("activation_root_verified", trustRoot);
        ObjectNode trust = optionalObject(first(env.get("V5_TRUST_ROOT_PATH"), env.get("V5_TRUST_ROOT")), work);
        checks.put("distinct_approval_roles", distinctApprovals(env, work, trust, keys));
        checks.put("replay_protection", actionsCustody
                && validEvidencePath(env.get("V5_REPLAY_EVIDENCE_PATH"), work, "replay", env));
        checks.put("revocation_list", actionsCustody
                && validEvidencePath(env.get("V5_REVOCATION_EVIDENCE_PATH"), work, "revocation", env));
        checks.put("lease_enforced", actionsCustody
                && validEvidencePath(env.get("V5_LEASE_EVIDENCE_PATH"), work, "lease", env));
        checks.put("shadow_append_eligible", shadowEligible);
        List<String> failed = new ArrayList<>();
        checks.fields().forEachRemaining(entry -> {
            if (!entry.getValue().asBoolean()) failed.add(entry.getKey());
        });
        ObjectNode result = object().put("schema", DEPLOYMENT_SCHEMA).put("version", 1)
                .put("settings_capture_sha256", captureValid ? text(capture.get("content_sha256")) : null);
        result.set("checks", checks);
        result.put("shadow_append_eligible", shadowEligible)
                .put("activation_eligible", failed.isEmpty()).put("blocked", !failed.isEmpty())
                .put("reason", failed.isEmpty() ? "deployment audit passed; external activation remains separately authorized"
                        : "deployment audit blocked: " + String.join(", ", failed))
                .put("blocked_until_external_prerequisites", !failed.isEmpty());
        result.set("exact_external_verification_required", strings(EXACT_EXTERNAL_REQUIREMENTS));
        result.put("content_sha256", StrategyProspectiveV5.ownHash(result));
        return result;
    }

    private static boolean actionsOnlySecret(Map<String, String> env, Path work, ObjectNode capture, ObjectNode api,
                                              ObjectNode attestation, ObjectNode registry) {
        String configuredPath = env.get("V5_ACTIONS_SECRET_EVIDENCE_PATH");
        ObjectNode value = optionalObject(configuredPath, work);
        if (!validSchemaHash(value, "strategy-actions-only-secret-evidence/1")) return false;
        try { ResearchSchemaRegistry.defaultRegistry().validateContractSchema(value); }
        catch (RuntimeException invalid) { return false; }
        JsonNode capturedSecret = capture == null ? null : capture.get("actions_secret");
        JsonNode apiSecret = api == null ? null : api.get("actions_secret");
        return "BOUND".equals(text(value.get("status")))
                && "ACTIONS_ATTESTATION_ONLY".equals(text(value.get("scope")))
                && capture != null && api != null && attestation != null && registry != null
                && text(value.get("repository")).equals(text(capture.get("repository")))
                && text(value.get("repository_id")).equals(text(capture.get("repository_id")))
                && "prospective-v5".equals(text(value.get("environment")))
                && capturedSecret != null && capturedSecret.path("verified").asBoolean(false)
                && apiSecret != null && apiSecret.path("verified").asBoolean(false)
                && JsonHashes.canonicalSha256(capturedSecret)
                        .equals(JsonHashes.canonicalSha256(apiSecret))
                && text(value.get("secret_name")).equals(text(capturedSecret.get("name")))
                && value.path("environment_secret_status").asInt(-1)
                        == capturedSecret.path("environment_status").asInt(-2)
                && text(value.get("environment_secret_body_sha256"))
                        .equals(text(capturedSecret.get("environment_body_sha256")))
                && value.path("repository_secret_status").asInt(-1)
                        == capturedSecret.path("repository_status").asInt(-2)
                && text(value.get("repository_secret_body_sha256"))
                        .equals(text(capturedSecret.get("repository_body_sha256")))
                && value.path("organization_secret_status").asInt(-1)
                        == capturedSecret.path("organization_status").asInt(-2)
                && text(value.get("organization_secret_body_sha256"))
                        .equals(text(capturedSecret.get("organization_body_sha256")))
                && text(value.get("settings_capture_sha256")).equals(text(capture.get("content_sha256")))
                && text(value.get("api_receipt_sha256")).equals(text(api.get("content_sha256")))
                && text(value.get("attestation_sha256")).equals(text(attestation.get("content_sha256")))
                && text(value.get("registry_sha256")).equals(text(registry.get("content_sha256")))
                && !value.has("secret_value") && !value.has("private_key");
    }

    private static boolean auditorSecretExact(JsonNode value) {
        return value != null
                && "V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM".equals(text(value.get("name")))
                && value.path("environment_status").asInt(0) == 200
                && value.path("repository_status").asInt(0) == 404
                && value.path("organization_status").asInt(0) == 404
                && value.path("verified").asBoolean(false);
    }

    private static boolean exactSettingsAuditorInstallation(JsonNode proof, String repository,
                                                            JsonNode repositoryId,
                                                            String tokenKind) {
        if (!"APP".equals(tokenKind)) return true;
        if (proof == null || !proof.isObject() || repository == null || repository.isBlank()) return false;
        String owner = repository.split("/", -1)[0];
        return proof.path("verified").asBoolean(false)
                && proof.path("expected_app_id").asLong(Long.MIN_VALUE)
                        == GitHubSettingsCaptureV5.SETTINGS_AUDITOR_APP_ID
                && proof.path("expected_installation_id").asLong(Long.MIN_VALUE)
                        == GitHubSettingsCaptureV5.SETTINGS_AUDITOR_INSTALLATION_ID
                && GitHubSettingsCaptureV5.SETTINGS_AUDITOR_APP_SLUG.equals(
                        text(proof.get("expected_app_slug")))
                && proof.path("app_endpoint_status").asInt(-1) == 200
                && proof.path("installation_endpoint_status").asInt(-1) == 200
                && proof.path("repositories_endpoint_status").asInt(-1) == 200
                && proof.path("app_id").asLong(Long.MIN_VALUE)
                        == GitHubSettingsCaptureV5.SETTINGS_AUDITOR_APP_ID
                && GitHubSettingsCaptureV5.SETTINGS_AUDITOR_APP_SLUG.equals(text(proof.get("app_slug")))
                && proof.path("installation_id").asLong(Long.MIN_VALUE)
                        == GitHubSettingsCaptureV5.SETTINGS_AUDITOR_INSTALLATION_ID
                && "selected".equals(text(proof.get("repository_selection")))
                && exactSettingsAuditorPermissions(proof.get("permissions"))
                && exactSettingsAuditorPermissions(proof.get("installation_permissions"))
                && proof.path("events").isArray() && proof.path("events").isEmpty()
                && proof.path("installation_events").isArray()
                && proof.path("installation_events").isEmpty()
                && proof.path("account").path("id").asLong(0) > 0
                && owner.equals(text(proof.path("account").get("login")))
                && proof.path("accessible_repository_count").asLong(-1) == 1
                && proof.path("accessible_repository").path("id").asLong(Long.MIN_VALUE)
                        == numeric(repositoryId)
                && repository.equals(text(proof.path("accessible_repository").get("full_name")));
    }

    private static boolean exactSettingsAuditorIdentity(JsonNode identity, String tokenKind) {
        return "APP".equals(tokenKind) && identity != null
                && identity.path("verified").asBoolean(false)
                && identity.path("app_id").asLong(Long.MIN_VALUE)
                        == GitHubSettingsCaptureV5.SETTINGS_AUDITOR_APP_ID;
    }

    private static boolean exactSettingsAuditorPermissions(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 5) return false;
        return "read".equals(text(value.get("actions")))
                && "read".equals(text(value.get("administration")))
                && "read".equals(text(value.get("environments")))
                && "read".equals(text(value.get("metadata")))
                && "read".equals(text(value.get("secrets")));
    }

    private static long numeric(JsonNode value) {
        if (value == null || value.isNull()) return Long.MIN_VALUE;
        if (value.isIntegralNumber()) return value.asLong(Long.MIN_VALUE);
        try { return Long.parseLong(value.asText()); }
        catch (RuntimeException ignored) { return Long.MIN_VALUE; }
    }

    private static boolean sameCanonical(JsonNode left, JsonNode right) {
        return left != null && right != null
                && JsonHashes.canonicalSha256(left).equals(JsonHashes.canonicalSha256(right));
    }

    private static boolean verifyTrustRoot(Map<String, String> env, Path work) {
        String path = first(env.get("V5_TRUST_ROOT_PATH"), env.get("V5_TRUST_ROOT"));
        if (path == null || env.getOrDefault("V5_TRUST_ROOT_FINGERPRINT", "").isBlank()
                || env.getOrDefault("V5_TRUST_ROOT_GENESIS_FINGERPRINT", "").isBlank()) return false;
        ObjectNode root = optionalObject(path, work);
        if (root == null) return false;
        ObjectNode options = object().put("nowAt", System.currentTimeMillis())
                .put("pinnedFingerprint", env.get("V5_TRUST_ROOT_FINGERPRINT"))
                .put("pinnedGenesisFingerprint", env.get("V5_TRUST_ROOT_GENESIS_FINGERPRINT"));
        try { return StrategyProspectiveV5.verifyTrustRoot(root, options); }
        catch (RuntimeException ignored) { return false; }
    }

    private static boolean distinctApprovals(Map<String, String> env, Path work,
                                             ObjectNode trustRoot, ObjectNode keys) {
        JsonNode root = optionalJson(first(env.get("V5_APPROVALS_PATH"), env.get("V5_APPROVALS")), work);
        if (root == null || trustRoot == null || keys == null) return false;
        List<JsonNode> approvals = root.isArray() ? rows(root) : rows(root.get("approvals"));
        Set<String> roles = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode row : approvals) {
            String role = text(row.get("role"));
            String keyId = text(row.get("key_id"));
            String publicPem = text(row.get("public_key_pem"));
            if (!Set.of("ASSET", "PORTFOLIO").contains(role)) continue;
            if (keyId.isBlank() || ActionsAttestationVerifierV5.publicKeyFingerprint(publicPem) == null
                    || text(row.get("trust_root_signature")).isBlank()) return false;
            JsonNode delegation = null;
            for (JsonNode candidate : rows(trustRoot.get("delegations"))) {
                if (role.toLowerCase(Locale.ROOT).equals(text(candidate.get("role")))
                        && keyId.equals(text(candidate.get("key_id")))
                        && publicPem.equals(text(candidate.get("public_key_pem")))) {
                    delegation = candidate;
                    break;
                }
            }
            if (delegation == null || rows(trustRoot.get("revoked_key_ids")).stream()
                    .anyMatch(revoked -> keyId.equals(text(revoked)))) return false;
            ObjectNode payload = object().put("role", role).put("key_id", keyId)
                    .put("public_key_sha256", StrategyProspectiveV5.hash(publicPem));
            try {
                if (!StrategyProspectiveV5.verifyPayload(payload,
                        text(row.get("trust_root_signature")), text(trustRoot.get("root_public_key_pem"))))
                    return false;
            } catch (RuntimeException invalid) { return false; }
            String expectedKey = "ASSET".equals(role)
                    ? text(keys.get("asset_public_key_pem"))
                    : text(keys.get("portfolio_public_key_pem"));
            if (!expectedKey.equals(publicPem)) return false;
            roles.add(role);
            ids.add(keyId);
        }
        return roles.size() == 2 && ids.size() == approvals.stream()
                .filter(row -> Set.of("ASSET", "PORTFOLIO").contains(text(row.get("role"))))
                .count();
    }

    /**
     * Validate deployment evidence at its physical repository path.  A mere
     * self-hash is not enough for replay custody: the registry's immutable
     * entry files and hash chain must also be reopened.  Lease evidence is
     * delegated to the publication verifier and therefore remains blocked
     * unless the caller supplies all of its physical dependencies.
     */
    private static boolean validEvidencePath(String value, Path work, String kind,
                                             Map<String, String> env) {
        if (value == null || value.isBlank()) return false;
        Path artifact;
        try {
            String relative = WorkflowSecurityV5.repositoryRelativePath(value,
                    kind + " evidence path");
            artifact = PathConfinement.resolve(work, relative, kind + " evidence",
                    PathConfinement.ExpectedType.FILE).absolute();
        } catch (RuntimeException invalidPath) { return false; }
        ObjectNode evidence = optionalObject(artifact);
        if (!validOwnHash(evidence)) return false;
        if ("replay".equals(kind) || "revocation".equals(kind)) {
            if (!verifyReplayRegistryArtifact(artifact, evidence)) return false;
            if ("replay".equals(kind)) return true;
            return verifyRevocationRegistryArtifact(evidence, env, work);
        }
        if (!"lease".equals(kind)
                || !"strategy-prospective-signed-evidence/2".equals(text(evidence.get("schema"))))
            return false;
        Instant expires = parseInstant(firstNode(evidence, "lease_expires_at", "expires_at"));
        Instant now = Instant.now(Clock.systemUTC());
        if (expires == null || !expires.isAfter(now)
                || expires.toEpochMilli() - now.toEpochMilli() > 90L * 86_400_000L) return false;
        String ledgerValue = first(env.get("V5_PROSPECTIVE_LEDGER_PATH"),
                env.get("V5_LEDGER_PATH"));
        String replayValue = first(env.get("V5_PROSPECTIVE_REPLAY_PATH"),
                env.get("V5_REPLAY_PATH"));
        String trustValue = first(env.get("V5_TRUST_ROOT_PATH"), env.get("V5_TRUST_ROOT"));
        if (ledgerValue == null || replayValue == null || trustValue == null
                || env.getOrDefault("V5_TRUST_ROOT_FINGERPRINT", "").isBlank()
                || env.getOrDefault("V5_TRUST_ROOT_GENESIS_FINGERPRINT", "").isBlank()) return false;
        try {
            Path ledger = PathConfinement.resolve(work,
                    WorkflowSecurityV5.repositoryRelativePath(ledgerValue, "ledger path"),
                    "prospective ledger", PathConfinement.ExpectedType.DIRECTORY).absolute();
            Path replay = PathConfinement.resolve(work,
                    WorkflowSecurityV5.repositoryRelativePath(replayValue, "replay path"),
                    "prospective replay registry", PathConfinement.ExpectedType.DIRECTORY).absolute();
            ObjectNode trustRoot = optionalObject(trustValue, work);
            if (trustRoot == null) return false;
            ObjectNode options = object().put("ledgerPath", ledger.toString())
                    .put("replayPath", replay.toString())
                    .put("nowAt", now.toEpochMilli())
                    .put("pinnedTrustRootFingerprint", env.get("V5_TRUST_ROOT_FINGERPRINT"))
                    .put("pinnedTrustRootGenesisFingerprint",
                            env.get("V5_TRUST_ROOT_GENESIS_FINGERPRINT"));
            options.set("trustRoot", trustRoot);
            return StrategyProspectiveV5.verifyProspectivePublication(evidence, options) != null;
        } catch (RuntimeException invalid) { return false; }
    }

    private static boolean verifyReplayRegistryArtifact(Path artifact, ObjectNode value) {
        if (!validSchemaHash(value, "strategy-prospective-replay-registry/1")) return false;
        try { ResearchSchemaRegistry.defaultRegistry().validateContractSchema(value); }
        catch (RuntimeException invalid) { return false; }
        List<JsonNode> entries = rows(value.get("entries"));
        List<JsonNode> refs = rows(value.get("entry_refs"));
        if (!HASH.matcher(text(value.get("lineage_sha256"))).matches()
                || !HASH.matcher(text(value.get("head_sha256"))).matches()
                || !text(value.get("current_head_sha256")).equals(text(value.get("head_sha256")))
                || refs.size() != entries.size() || value.path("sequence").asInt(-1) != entries.size()
                || entries.isEmpty()) return false;
        Path root = artifact.toAbsolutePath().normalize().getParent();
        if (root == null) return false;
        try { PathConfinement.requireRealDirectory(root, "replay registry root"); }
        catch (RuntimeException invalid) { return false; }
        String previous = StrategyProspectiveV5.hash(object()
                .put("schema", "strategy-prospective-replay-genesis/1")
                .put("lineage_sha256", text(value.get("lineage_sha256"))));
        Map<String, List<String>> actions = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            JsonNode entry = entries.get(index), ref = refs.get(index);
            List<String> prior = actions.computeIfAbsent(text(entry.get("nonce")),
                    ignored -> new ArrayList<>());
            String action = text(entry.get("action"));
            boolean lifecycle = "USE".equals(action)
                    ? prior.isEmpty() && HASH.matcher(text(entry.get("publication_payload_sha256"))).matches()
                    : "REVOKE".equals(action) && prior.equals(List.of("USE"))
                    && !text(entry.get("key_id")).isBlank() && !text(entry.get("signature")).isBlank()
                    && HASH.matcher(text(entry.get("trust_root_sha256"))).matches()
                    && entry.path("trust_root_generation").isIntegralNumber();
            if (entry.path("sequence").asInt(-1) != index + 1
                    || !text(entry.get("previous_head_sha256")).equals(previous)
                    || !HASH.matcher(text(entry.get("entry_sha256"))).matches()
                    || !text(entry.get("entry_sha256")).equals(
                            StrategyProspectiveV5.ownHash(entry, "entry_sha256"))
                    || text(entry.get("nonce")).isBlank() || !lifecycle) return false;
            prior.add(action);
            String relative = text(ref.get("path"));
            boolean absolutePath;
            try { absolutePath = Path.of(relative).isAbsolute(); }
            catch (RuntimeException invalidPath) { return false; }
            if (ref.path("sequence").asInt(-1) != entry.path("sequence").asInt()
                    || !text(ref.get("entry_sha256")).equals(text(entry.get("entry_sha256")))
                    || !HASH.matcher(text(ref.get("byte_sha256"))).matches()
                    || relative.isBlank() || absolutePath
                    || relative.contains("..") || relative.contains("\\")) return false;
            Path child;
            byte[] bytes;
            try {
                child = PathConfinement.resolve(root, relative, "replay entry",
                        PathConfinement.ExpectedType.FILE).absolute();
                bytes = PathConfinement.readSinglyLinkedFile(child, "replay entry");
            } catch (RuntimeException invalid) { return false; }
            if (!StrategyProspectiveV5.hash(bytes).equals(text(ref.get("byte_sha256")))) return false;
            ObjectNode reopened;
            try { reopened = object(JsonHashes.parse(bytes, child.toString()), child.toString()); }
            catch (RuntimeException invalid) { return false; }
            if (!sameCanonical(reopened, entry)
                    || !text(reopened.get("entry_sha256")).equals(text(entry.get("entry_sha256")))) return false;
            previous = text(entry.get("entry_sha256"));
        }
        return true;
    }

    private static boolean verifyRevocationRegistryArtifact(ObjectNode registry,
                                                             Map<String, String> env,
                                                             Path work) {
        ObjectNode trustRoot = optionalObject(
                first(env.get("V5_TRUST_ROOT_PATH"), env.get("V5_TRUST_ROOT")), work);
        String pin = env.getOrDefault("V5_TRUST_ROOT_FINGERPRINT", "");
        String genesis = env.getOrDefault("V5_TRUST_ROOT_GENESIS_FINGERPRINT", "");
        if (trustRoot == null || pin.isBlank() || genesis.isBlank()) return false;
        try {
            ObjectNode options = object().put("nowAt", System.currentTimeMillis())
                    .put("pinnedFingerprint", pin).put("pinnedGenesisFingerprint", genesis);
            if (env.containsKey("V5_PREVIOUS_TRUST_ROOT_PATH"))
                options.set("previousRoot", optionalObject(env.get("V5_PREVIOUS_TRUST_ROOT_PATH"), work));
            StrategyProspectiveV5.verifyTrustRoot(trustRoot, options);
            for (JsonNode entry : rows(registry.get("entries"))) {
                if (!"REVOKE".equals(text(entry.get("action")))
                        || !text(entry.get("trust_root_sha256")).equals(text(trustRoot.get("content_sha256")))
                        || entry.path("trust_root_generation").asInt(-1)
                                != trustRoot.path("generation").asInt()) continue;
                for (JsonNode delegated : rows(trustRoot.get("delegations"))) {
                    if (!"revocation".equals(text(delegated.get("role")))
                            || !text(delegated.get("key_id")).equals(text(entry.get("key_id")))
                            || rows(trustRoot.get("revoked_key_ids")).stream()
                                    .anyMatch(revoked -> text(revoked).equals(text(delegated.get("key_id"))))) continue;
                    ObjectNode payload = object();
                    for (String field : List.of("nonce", "action", "reason", "revoked_at",
                            "trust_root_sha256", "trust_root_generation"))
                        payload.set(field, entry.get(field));
                    if (StrategyProspectiveV5.verifyPayload(payload, text(entry.get("signature")),
                            text(delegated.get("public_key_pem")))) return true;
                }
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private static ObjectNode settingsPolicy(ObjectNode value) {
        ObjectNode out = object();
        for (String key : List.of("repository", "repository_id", "evidence_branch",
                "repository_private", "repository_visibility", "repository_visibility_verified",
                "branch_protection", "rulesets", "environment_protection", "actions_permissions",
                "actions_secret", "settings_token_secret", "settings_token_identity",
                "settings_auditor_installation", "oidc_signature_verified", "oidc_subject_restricted"))
            if (value.has(key)) out.set(key, value.get(key).deepCopy());
        if (value.path("oidc_claims").isObject()) {
            ObjectNode oidc = object();
            for (String key : List.of("repository_id", "repository_owner_id", "environment",
                    "workflow_ref", "workflow_sha", "sub", "aud", "iss"))
                if (value.path("oidc_claims").has(key)) oidc.set(key,
                        value.path("oidc_claims").get(key).deepCopy());
            out.set("oidc", oidc);
        }
        return out;
    }

    private static ObjectNode apiPolicy(ObjectNode value) {
        ObjectNode out = object();
        if (!value.path("endpoints").isObject()) return out;
        value.path("endpoints").fields().forEachRemaining(entry -> {
            ObjectNode row = object().put("status", entry.getValue().path("status").asInt(0));
            if (!Set.of("branch_head", "oidc_subject_restriction").contains(entry.getKey()))
                row.put("body_sha256", text(entry.getValue().get("body_sha256")));
            else row.putNull("body_sha256");
            out.set(entry.getKey(), row);
        });
        return out;
    }

    private static boolean validDrift(ObjectNode evidence, ObjectNode capture, ObjectNode api) {
        if (!validSchemaHash(evidence, DRIFT_SCHEMA) || capture == null || api == null
                || !text(evidence.get("repository")).equals(text(capture.get("repository")))
                || !text(evidence.get("repository_id")).equals(text(capture.get("repository_id")))
                || !text(evidence.get("current_capture_sha256"))
                        .equals(text(capture.get("content_sha256")))
                || !text(evidence.get("current_api_receipt_sha256"))
                        .equals(text(api.get("content_sha256")))) return false;
        try { ResearchSchemaRegistry.defaultRegistry().validateContractSchema(evidence); }
        catch (RuntimeException invalid) { return false; }
        String status = text(evidence.get("status"));
        if ("BASELINE_ESTABLISHED".equals(status)) return true;
        return "CLEAR".equals(status)
                && HASH.matcher(text(evidence.get("previous_capture_sha256"))).matches()
                && HASH.matcher(text(evidence.get("previous_api_receipt_sha256"))).matches()
                && evidence.path("changed_fields").isArray()
                && evidence.path("changed_fields").isEmpty();
    }

    private static ObjectNode commandReceipt(String command, String status, List<ObjectNode> inputs,
                                             List<String> limitations, ObjectNode details) {
        return commandReceipt(command, status, inputs, List.of(), limitations, details);
    }

    private static ObjectNode commandReceipt(String command, String status, List<ObjectNode> inputs,
                                             List<ObjectNode> outputs, List<String> limitations,
                                             ObjectNode details) {
        if (!Set.of("PLANNED", "COMPLETE", "BLOCKED", "REJECTED").contains(status))
            throw new IllegalArgumentException("invalid authoritative command status " + status);
        ObjectNode result = object().put("schema", AUTHORITATIVE_SCHEMA).put("version", 1)
                .put("command", command).put("status", status);
        ArrayNode inputRows = result.putArray("inputs"); inputs.forEach(inputRows::add);
        ArrayNode outputRows = result.putArray("outputs"); outputs.forEach(outputRows::add);
        ArrayNode limitRows = result.putArray("limitations");
        limitations.stream().distinct().sorted().forEach(limitRows::add);
        ObjectNode safeDetails = details == null ? object() : details.deepCopy();
        safeDetails.put("active", false);
        result.set("details", safeDetails);
        result.put("content_sha256", StrategyProspectiveV5.ownHash(result));
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(result);
        return result;
    }

    private static void validateCommandReceipt(ObjectNode receipt) {
        requireSchemaAndHash(receipt, AUTHORITATIVE_SCHEMA, "authoritative command receipt");
        if (receipt.path("details").path("active").asBoolean(true)
                || receipt.toString().contains("\"ACTIVE\""))
            throw new IllegalArgumentException("authoritative command receipt may not claim ACTIVE");
    }

    private static List<ObjectNode> sourceInputs(WorkflowSecurityV5.SourceBundleVerification verified) {
        List<ObjectNode> result = new ArrayList<>();
        result.add(reference(verified.bundlePhysical(), "source_bundle"));
        for (String role : SOURCE_ROLES)
            result.add(reference(verified.references().get(role), role));
        return result;
    }

    private static ObjectNode reference(WorkflowSecurityV5.ConfinedJson physical, String role) {
        byte[] bytes = physical.bytes();
        ObjectNode value = physical.value().isObject() ? (ObjectNode) physical.value() : null;
        ObjectNode out = object().put("role", role).put("storage", "PHYSICAL")
                .put("path", physical.relative()).put("byte_sha256", StrategyProspectiveV5.hash(bytes))
                .put("bytes", bytes.length);
        if (value != null && validOwnHash(value)) out.put("content_sha256", text(value.get("content_sha256")));
        else out.putNull("content_sha256");
        return out;
    }

    private static ObjectNode reference(Path path, Path work, String role) {
        Path base = PathConfinement.requireRealDirectory(work, "workflow working directory");
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) throw new IllegalArgumentException(role + " escapes repository");
        String relative = base.relativize(absolute).toString()
                .replace(absolute.getFileSystem().getSeparator(), "/");
        WorkflowSecurityV5.ConfinedJson physical = WorkflowSecurityV5.readConfinedJson(
                work, relative, role);
        return reference(physical, role);
    }

    private record SettingsBaseline(Path capture, Path api, Path writer, Instant capturedAt,
                                    String captureSha, String apiSha) {}

    private static Map<String, String> flags(String[] args, int start) {
        Map<String, String> result = new LinkedHashMap<>();
        if (args == null) return result;
        for (int i = start; i < args.length; i++) {
            String token = args[i];
            if (token == null || !token.startsWith("-")) continue;
            int prefix = token.startsWith("--") ? 2 : 1;
            String key = token.substring(prefix);
            int equals = key.indexOf('=');
            String optionName = equals >= 0 ? key.substring(0, equals) : key;
            if (SENSITIVE_OPTION.matcher(optionName).find())
                throw new IllegalArgumentException("sensitive option names are not accepted: "
                        + "-".repeat(prefix) + optionName);
            if (prefix != 2) continue;
            if (equals >= 0) {
                result.put(key.substring(0, equals), key.substring(equals + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                result.put(key, args[++i]);
            } else result.put(key, "true");
        }
        return result;
    }

    private static Path path(String value, Path work) {
        return confinedPath(value, work, "path");
    }

    private static String outputPath(String value, Path work, String fallback) {
        return confinedPath(value == null || value.isBlank() ? fallback : value, work,
                "output path").toString();
    }

    /** Resolve a caller-supplied path without permitting absolute, traversal, or linked paths. */
    private static Path confinedPath(String value, Path work, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("path is required");
        String relative = PathConfinement.repositoryRelativePath(value, label);
        Path base = PathConfinement.requireRealDirectory(work, "workflow working directory");
        Path candidate = base;
        if (!".".equals(relative)) {
            for (String component : relative.split("/", -1)) candidate = candidate.resolve(component);
        }
        candidate = candidate.toAbsolutePath().normalize();
        if (!candidate.startsWith(base)) throw new IllegalArgumentException(label + " escapes repository");
        Path cursor = base;
        if (!".".equals(relative)) {
            String[] components = relative.split("/", -1);
            for (int index = 0; index < components.length; index++) {
                cursor = cursor.resolve(components[index]);
                if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) break;
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(cursor, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                } catch (IOException error) {
                    throw new IllegalArgumentException(label + " cannot be inspected", error);
                }
                if (attributes.isSymbolicLink()) throw new IllegalArgumentException(label + " contains a symlink");
                boolean last = index == components.length - 1;
                if (!last && !attributes.isDirectory())
                    throw new IllegalArgumentException(label + " contains a non-directory component");
                if (attributes.isRegularFile()) PathConfinement.requireSingleLink(cursor, label);
                else if (!attributes.isDirectory())
                    throw new IllegalArgumentException(label + " contains a special file");
            }
        }
        return candidate;
    }

    private static Path confinedAbsolute(Path candidate, Path work, String label) {
        Path base = PathConfinement.requireRealDirectory(work, "workflow working directory");
        Path absolute = candidate.toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) throw new IllegalArgumentException(label + " escapes repository");
        Path relative = base.relativize(absolute);
        return confinedPath(relative.toString().replace(absolute.getFileSystem().getSeparator(), "/"),
                work, label);
    }

    private static String required(Map<String, String> flags, String key) {
        String value = flags.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + key + " is required");
        return value;
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static JsonNode firstNode(ObjectNode value, String... names) {
        if (value == null) return NullNode.instance;
        for (String name : names) if (value.hasNonNull(name)) return value.get(name);
        return NullNode.instance;
    }

    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }

    private static ObjectNode object(JsonNode value, String label) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException(label + " must be an object");
        return (ObjectNode) value;
    }

    private static ObjectNode readObject(Path path) {
        return object(readJson(path), path.toString());
    }

    private static JsonNode readJson(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("file is missing: " + path);
        try {
            return JsonHashes.parse(
                    com.tradinganalytics.infrastructure.security.PathConfinement
                            .readSinglyLinkedFile(path, "JSON artifact"), path.toString());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("JSON artifact is invalid: " + path, error);
        }
    }

    private static ObjectNode optionalObject(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try { return readObject(path); }
        catch (RuntimeException ignored) { return null; }
    }

    private static ObjectNode optionalObject(String value, Path work) {
        if (value == null || value.isBlank()) return null;
        return optionalObject(confinedPath(value, work, "JSON artifact path"));
    }

    private static JsonNode optionalJson(String value, Path work) {
        if (value == null || value.isBlank()) return null;
        Path path = confinedPath(value, work, "JSON artifact path");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try { return readJson(path); }
        catch (RuntimeException ignored) { return null; }
    }

    private static ObjectNode optionalObject(String value, Path work, boolean ignored) {
        return optionalObject(value, work);
    }

    private static boolean validOwnHash(ObjectNode value) {
        return value != null && HASH.matcher(text(value.get("content_sha256"))).matches()
                && text(value.get("content_sha256")).equals(StrategyProspectiveV5.ownHash(value));
    }

    private static boolean validSchemaHash(ObjectNode value, String schema) {
        return validOwnHash(value) && schema.equals(text(value.get("schema")));
    }

    private static void requireSchemaAndHash(ObjectNode value, String schema, String label) {
        if (!validSchemaHash(value, schema)) throw new IllegalArgumentException(label + " is missing or tampered");
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(value);
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode() ? "" : value.asText();
    }

    private static List<JsonNode> rows(JsonNode value) {
        if (value == null || value.isNull() || !value.isArray()) return List.of();
        List<JsonNode> result = new ArrayList<>(); value.forEach(result::add); return result;
    }

    private static List<String> stringRows(JsonNode value) {
        return rows(value).stream().map(StrategyV5ProspectiveWorkflowCliCommand::text).toList();
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode result = JsonHashes.mapper().createArrayNode(); values.forEach(result::add); return result;
    }

    private static String pretty(JsonNode value) { return NodePrettyJson.write(value); }

    private static String hashFile(Path path) {
        try { return StrategyProspectiveV5.hash(Files.readAllBytes(path)); }
        catch (IOException error) { throw new IllegalArgumentException("cannot hash " + path, error); }
    }

    private static String hashIfPresent(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? hashFile(path) : "";
    }

    private static Instant parseInstant(JsonNode value) {
        if (value == null || value.isNull()) return null;
        try { return Instant.parse(text(value)); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static void copyExclusive(Path source, Path target) {
        try {
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("file is missing: " + source);
            byte[] bytes = com.tradinganalytics.infrastructure.security.PathConfinement.readSinglyLinkedFile(source, "immutable source");
            writeExclusive(target, bytes);
        } catch (RuntimeException error) { throw new IllegalArgumentException("cannot copy " + source, error); }
    }

    private static void writeExclusive(Path target, byte[] bytes) {
        try {
            Path parent = target.toAbsolutePath().normalize().getParent();
            if (parent == null) throw new IllegalArgumentException("output path has no parent");
            rejectLinkedOutputAncestors(parent);
            Files.createDirectories(parent);
            requireRealOutputParent(parent);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                byte[] prior = com.tradinganalytics.infrastructure.security.PathConfinement.readSinglyLinkedFile(target, "immutable output");
                if (!java.util.Arrays.equals(prior, bytes)) throw new IllegalArgumentException("immutable output collision: " + target);
                return;
            }
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            com.tradinganalytics.infrastructure.security.PathConfinement.validateSinglyLinkedFile(target, "immutable output");
        } catch (FileAlreadyExistsException error) {
            throw new IllegalArgumentException("immutable output collision: " + target, error);
        } catch (IOException error) { throw new IllegalArgumentException("immutable output cannot be written: " + target, error); }
    }

    private static void requireRealOutputParent(Path parent) {
        Path absolute = parent.toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(absolute))
                throw new IllegalArgumentException("output parent must be a real directory: " + parent);
            Path real = absolute.toRealPath();
            if (!real.equals(absolute))
                throw new IllegalArgumentException("output parent contains a symlink: " + parent);
        } catch (IOException error) {
            throw new IllegalArgumentException("output parent cannot be inspected: " + parent, error);
        }
    }

    private static void rejectLinkedOutputAncestors(Path parent) {
        Path absolute = parent.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        if (cursor == null) throw new IllegalArgumentException("output parent has no root");
        for (Path component : absolute) {
            cursor = cursor.resolve(component.toString());
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) break;
            if (Files.isSymbolicLink(cursor)
                    || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("output parent is not a real directory: " + parent);
        }
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
    }
}
