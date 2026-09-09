package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.github.GitHubSettingsCaptureV5;
import com.tradinganalytics.infrastructure.github.WriterInstallationReceipts;
import com.tradinganalytics.infrastructure.security.SafeTreeVerifier;
import com.tradinganalytics.infrastructure.security.WorkflowSecurityV5;
import com.tradinganalytics.research.v5.StrategyProspectiveV5;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tradinganalytics.cli.StrategyV5WorkflowJson.*;
import static com.tradinganalytics.cli.StrategyV5WorkflowPaths.*;

/** Settings capture, source-bundle verification, and ledger hydration modes. */
final class StrategyV5SettingsWorkflow {
    private static final String DRIFT_SCHEMA = "github-settings-drift-evidence/1";

    private StrategyV5SettingsWorkflow() {}

    static int captureSettings(Map<String, String> env, Path work) {
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

    static int verifyBundle(Map<String, String> flags, Path work, java.io.PrintStream out) {
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

    /** Reopens the protected branch archive and hydrates a private complete ledger. */
    static int hydrate(Map<String, String> flags, Path work, java.io.PrintStream out)
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
            stream.filter(candidate -> candidate.getFileName().toString().equals("HEAD.json"))
                    .filter(candidate -> candidate.getParent().getFileName().toString().equals("ledger"))
                    .forEach(candidate -> {
                        try {
                            ObjectNode ledger = StrategyProspectiveV5.readProspectiveLedger(
                                    candidate.getParent(), object().put("nowAt", System.currentTimeMillis())
                                            .put("allowFuture", true)
                                            .put("snapshotRootBase", stateRoot.toString()));
                            List<String> events = rows(ledger.path("events")).stream()
                                    .map(row -> text(row.get("event_sha256"))).toList();
                            candidates.add(new WorkflowSecurityV5.LedgerCandidate(
                                    candidate.getParent().toString(), ledger.path("sequence").asInt(),
                                    text(ledger.get("current_head_sha256")),
                                    text(ledger.get("lineage_sha256")), events));
                        } catch (RuntimeException error) {
                            throw new IllegalArgumentException("invalid historical ledger " + candidate
                                    + ": " + CliMessages.rootCauseMessage(error), error);
                        }
                    });
        }
        WorkflowSecurityV5.LedgerCandidate selected =
                WorkflowSecurityV5.selectProspectiveLedgerCandidateV5(candidates);
        if (selected != null) {
            hydrateCompleteLedger(confinedAbsolute(Path.of(selected.path()), work,
                    "selected historical ledger"), privateLedger, stateRoot);
        }

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
        List<SettingsBaseline> baselines = new ArrayList<>();
        try (var stream = Files.walk(stateRoot)) {
            stream.filter(candidate -> candidate.getFileName().toString()
                            .equals("github-deployment-settings-capture.json"))
                    .forEach(candidate -> {
                        Path api = candidate.resolveSibling("github-settings-api-receipt.json");
                        if (!Files.exists(api, LinkOption.NOFOLLOW_LINKS))
                            throw new IllegalArgumentException(
                                    "GitHub settings baseline is missing its API receipt: " + candidate);
                        ObjectNode capture = readObject(candidate);
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
                        Path writer = verifiedOptionalWriterReceipt(candidate, capture);
                        baselines.add(new SettingsBaseline(candidate, api, writer,
                                parseInstant(capture.get("captured_at")),
                                text(capture.get("content_sha256")),
                                text(receipt.get("content_sha256"))));
                    });
        }
        baselines.sort(Comparator.comparing(SettingsBaseline::capturedAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SettingsBaseline::captureSha));
        if (baselines.isEmpty() || baselines.get(0).capturedAt() == null) return;
        SettingsBaseline selected = baselines.get(0);
        if (baselines.stream().anyMatch(row -> selected.capturedAt().equals(row.capturedAt())
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
            String name = String.format(java.util.Locale.ROOT, "events/%012d-%s.json",
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
            String name = String.format(java.util.Locale.ROOT, "events/%012d-%s.json",
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

    static int drift(Map<String, String> flags, Path work, java.io.PrintStream out) {
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

        String currentPolicy = StrategyV5WorkflowDeployment.settingsPolicyHash(current);
        String previousPolicy = previousValid && previous != null
                ? StrategyV5WorkflowDeployment.settingsPolicyHash(previous) : null;
        String currentApiPolicy = StrategyV5WorkflowDeployment.settingsApiPolicyHash(api);
        String previousApiPolicy = previousValid && previousApi != null
                ? StrategyV5WorkflowDeployment.settingsApiPolicyHash(previousApi) : null;
        DriftComparison comparison = comparePolicies(
                hasPrevious, currentPolicy, previousPolicy, currentApiPolicy, previousApiPolicy);
        List<String> changed = comparison.changedFields();
        String status = comparison.status();
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

    static Path verifiedOptionalWriterReceipt(Path capture, ObjectNode captureValue) {
        Path writer = capture.resolveSibling("github-writer-installation-receipt.json");
        if (!Files.exists(writer, LinkOption.NOFOLLOW_LINKS)) return null;
        ObjectNode value = readObject(writer);
        if (!WriterInstallationReceipts.verifyWriterInstallationReceipt(value,
                new WriterInstallationReceipts.Verification(
                        text(captureValue.get("repository")), captureValue.get("repository_id"),
                        WriterInstallationReceipts.WRITER_APP_ID,
                        WriterInstallationReceipts.WRITER_INSTALLATION_ID,
                        WriterInstallationReceipts.WRITER_APP_SLUG))) {
            throw new IllegalArgumentException(
                    "historical writer-App installation receipt is invalid or not capture-bound");
        }
        return writer;
    }

    static DriftComparison comparePolicies(
            boolean hasPrevious,
            String currentPolicy,
            String previousPolicy,
            String currentApiPolicy,
            String previousApiPolicy) {
        if (!hasPrevious) {
            return new DriftComparison("BASELINE_ESTABLISHED", List.of("BASELINE_ESTABLISHED"));
        }
        List<String> changed = new ArrayList<>();
        if (!currentPolicy.equals(previousPolicy)) changed.add("settings_policy");
        if (!currentApiPolicy.equals(previousApiPolicy)) changed.add("api_receipt");
        return new DriftComparison(changed.isEmpty() ? "CLEAR" : "DRIFTED", List.copyOf(changed));
    }

    record DriftComparison(String status, List<String> changedFields) {}

    private record SettingsBaseline(Path capture, Path api, Path writer, Instant capturedAt,
                                    String captureSha, String apiSha) {}
}
