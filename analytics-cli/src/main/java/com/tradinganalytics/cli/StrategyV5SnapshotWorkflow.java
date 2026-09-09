package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.github.WriterInstallationReceipts;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import com.tradinganalytics.infrastructure.security.SafeTreeVerifier;
import com.tradinganalytics.infrastructure.security.WorkflowSecurityV5;
import com.tradinganalytics.research.v5.StrategyProspectiveV5;
import com.tradinganalytics.research.v5.StrategyReadinessV5;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.tradinganalytics.cli.StrategyV5WorkflowJson.*;
import static com.tradinganalytics.cli.StrategyV5WorkflowPaths.*;
import static com.tradinganalytics.cli.StrategyV5WorkflowReceipts.*;

/** Snapshot, preflight, archive, and additive-diff verification modes. */
final class StrategyV5SnapshotWorkflow {
    private static final String DRIFT_SCHEMA = "github-settings-drift-evidence/1";
    private static final String DEPLOYMENT_SCHEMA = "strategy-deployment-audit/1";
    private static final Pattern EVENT_FILE = Pattern.compile(
            "^events/\\d{12}-[a-f0-9]{64}\\.json$");

    private StrategyV5SnapshotWorkflow() {}

    static int verifyPreflight(Map<String, String> flags, Path work, PrintStream out) {
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
                && (!StrategyV5WorkflowDeployment.exactSettingsAuditorIdentity(
                        capture.path("settings_token_identity"), tokenKind)
                || !StrategyV5WorkflowDeployment.exactSettingsAuditorIdentity(
                        api.path("settings_token_identity"), tokenKind)
                || !StrategyV5WorkflowDeployment.auditorSecretExact(capture.get("settings_token_secret"))
                || !StrategyV5WorkflowDeployment.auditorSecretExact(api.get("settings_token_secret"))
                || !StrategyV5WorkflowDeployment.exactSettingsAuditorInstallation(
                        capture.get("settings_auditor_installation"),
                        text(capture.get("repository")), capture.get("repository_id"), tokenKind)
                || !StrategyV5WorkflowDeployment.exactSettingsAuditorInstallation(
                        api.get("settings_auditor_installation"),
                        text(capture.get("repository")), capture.get("repository_id"), tokenKind)
                || !sameCanonical(capture.get("settings_auditor_installation"),
                        api.get("settings_auditor_installation"))))
            throw new IllegalArgumentException("settings auditor installation proof is not exact");
        if (!api.path("verified").asBoolean(false) || api.path("blockers").size() != 0
                || !StrategyV5WorkflowDeployment.validDrift(drift, capture, api)
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

    static int hydrateDelta(Map<String, String> flags, Path work, PrintStream out)
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
                stream.filter(candidate -> Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS))
                        .filter(candidate -> HASH.matcher(candidate.getFileName().toString()).matches())
                        .forEach(snapshotRoot -> {
                            Path ledgerPath = snapshotRoot.resolve("ledger");
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
                                        + snapshotRoot.getFileName() + ": "
                                        + CliMessages.rootCauseMessage(error), error);
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

    static int verifySnapshot(Map<String, String> flags, Path work, PrintStream out) {
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

    static int verifyTree(Map<String, String> flags, Path work, PrintStream out) {
        Path root = path(required(flags, "path"), work);
        SafeTreeVerifier.Options options = Boolean.parseBoolean(flags.getOrDefault("repository", "false"))
                ? SafeTreeVerifier.Options.REPOSITORY : SafeTreeVerifier.Options.EVIDENCE;
        SafeTreeVerifier.TreeSummary summary = WorkflowSecurityV5.verifySafeTree(root,
                flags.getOrDefault("label", "evidence tree"), options);
        out.println(pretty(object().put("files", summary.files()).put("total_bytes", summary.totalBytes())));
        return 0;
    }

    static int verifyArchive(Map<String, String> flags, Path work, PrintStream out) {
        Path archive = path(required(flags, "path"), work);
        SafeTreeVerifier.TreeSummary summary = WorkflowSecurityV5.verifyTarArchive(archive,
                flags.getOrDefault("label", "evidence archive"),
                Boolean.parseBoolean(flags.getOrDefault("repository", "false"))
                        ? SafeTreeVerifier.Options.REPOSITORY : SafeTreeVerifier.Options.EVIDENCE);
        out.println(pretty(object().put("files", summary.files()).put("total_bytes", summary.totalBytes())));
        return 0;
    }

    static int verifySnapshotRoot(Map<String, String> flags, Path work, PrintStream out)
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
}
