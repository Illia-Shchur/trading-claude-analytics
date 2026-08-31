package com.tradinganalytics.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.github.WriterInstallationReceipts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Complete trusted-base-only verifier behind WorkflowSecurityV5.verifyProspectiveSnapshotV5. */
final class ProspectiveSnapshotVerifierV5 {
    private static final List<String> SNAPSHOT_FILES = List.of(
            "v5-shadow-cycle.json",
            "v5-shadow-cycle-receipt.json",
            "github-settings-api-receipt.json",
            "github-deployment-settings-capture.json",
            "github-settings-drift-evidence.json",
            "v5-deployment-audit.json",
            "v5-actions-attestation.json",
            "v5-attestation-key-registry.json",
            "github-writer-installation-receipt.json");
    private static final Pattern EVENT_FILE = Pattern.compile(
            "^events/\\d{12}-[a-f0-9]{64}\\.json$");
    private static final Pattern EVENT_NAME = Pattern.compile(
            "^\\d{12}-[a-f0-9]{64}\\.json$");
    private static final Pattern EXACT_PUBLIC_PEM = Pattern.compile(
            "^-----BEGIN PUBLIC KEY-----\\n(?:[A-Za-z0-9+/=]{1,64}\\n)+"
                    + "-----END PUBLIC KEY-----\\n?$");
    private static final long SETTINGS_AUDITOR_APP_ID = 4_716_635L;
    private static final long SETTINGS_AUDITOR_INSTALLATION_ID = 156_531_963L;
    private static final String SETTINGS_AUDITOR_APP_SLUG = "strategy-v5-settings-auditor";

    record Options(
            Path proposedRoot,
            Path trustedBaseRoot,
            Path trustedRegistryPath,
            String pinnedAttestationFingerprint,
            long nowAt) {}

    record Verification(boolean verified, int sequence, String head, Integer trustedBaseSequence) {}

    private record SnapshotFile(Path path, byte[] bytes, JsonNode value) {}
    private record LedgerIndex(Path path, JsonNode value) {}
    private record EventSource(Path ledger, JsonNode refs) {}
    private record LedgerSnapshot(
            int sequence, String head, String currentHead, String lineage,
            List<String> eventHeads, List<JsonNode> events) {}
    private record TrustedDelta(
            String root, Path path, JsonNode index, List<String> eventHeads) {}

    private ProspectiveSnapshotVerifierV5() {}

    static Verification verify(Options options) {
        if (options == null || options.proposedRoot() == null) {
            throw new CustodyException("prospective evidence snapshot is required");
        }
        Path root = PathConfinement.requireRealDirectory(
                options.proposedRoot(), "prospective evidence snapshot");
        SafeTreeVerifier.verify(root, "prospective evidence snapshot", SafeTreeVerifier.Options.EVIDENCE);
        Path cyclePath = root.resolve("v5-shadow-cycle.json");
        byte[] cycleBytes = PathConfinement.readSinglyLinkedFile(
                cyclePath, "prospective snapshot completed cycle");
        if (!root.getFileName().toString().equals(JsonHashes.sha256(cycleBytes))) {
            throw new CustodyException(
                    "prospective snapshot root is not the exact completed-cycle byte hash");
        }
        exactTopLevelInventory(root);

        Map<String, SnapshotFile> files = new LinkedHashMap<>();
        for (String name : SNAPSHOT_FILES) files.put(name, snapshotJson(root, name));
        Path ledgerRoot = root.resolve("ledger");
        exactLedgerInventory(ledgerRoot);

        JsonNode cycle = files.get("v5-shadow-cycle-receipt.json").value();
        JsonNode capture = files.get("github-deployment-settings-capture.json").value();
        JsonNode api = files.get("github-settings-api-receipt.json").value();
        JsonNode drift = files.get("github-settings-drift-evidence.json").value();
        JsonNode writer = files.get("github-writer-installation-receipt.json").value();
        JsonNode attestation = files.get("v5-actions-attestation.json").value();
        JsonNode registry = files.get("v5-attestation-key-registry.json").value();
        if (!"strategy-v5-authoritative-command-receipt/1".equals(cycle.path("schema").asText())
                || !"COMPLETE".equals(cycle.path("status").asText())
                || !cycle.path("details").has("active")
                || cycle.path("details").path("active").asBoolean(true)
                || "ACTIVE".equals(cycle.path("decision").asText())
                || "ACTIVE".equals(cycle.path("details").path("decision").asText())) {
            throw new CustodyException(
                "prospective cycle receipt is not a completed inactive non-active receipt");
        }
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(cycle);
        if (!"github-settings-api-receipt/1".equals(api.path("schema").asText())
                || !api.path("repository").asText().equals(capture.path("repository").asText())
                || !string(api.get("repository_id")).equals(string(capture.get("repository_id")))
                || !api.path("verified").asBoolean(false)
                || (api.path("blockers").isArray() && !api.path("blockers").isEmpty())) {
            throw new CustodyException("settings API receipt is not bound to the capture");
        }
        if (!WriterInstallationReceipts.verifyWriterInstallationReceipt(
                    writer, capture.path("repository").asText(), capture.get("repository_id"))
                || writer.path("app_id").asLong(Long.MIN_VALUE)
                        != WriterInstallationReceipts.WRITER_APP_ID
                || writer.path("installation_id").asLong(Long.MIN_VALUE)
                        != WriterInstallationReceipts.WRITER_INSTALLATION_ID) {
            throw new CustodyException(
                    "writer installation receipt is not bound to the exact capture repository/App installation");
        }
        if (capture.path("rulesets").has("evidence_writer_app_id")
                && capture.path("rulesets").path("evidence_writer_app_id").asLong(Long.MIN_VALUE)
                        != WriterInstallationReceipts.WRITER_APP_ID) {
            throw new CustodyException(
                    "capture writer App identity is not the frozen deployment identity");
        }
        if (!"github-settings-drift-evidence/1".equals(drift.path("schema").asText())
                || !drift.path("repository").asText().equals(capture.path("repository").asText())
                || !string(drift.get("repository_id")).equals(string(capture.get("repository_id")))
                || !drift.path("current_capture_sha256").asText()
                        .equals(capture.path("content_sha256").asText())
                || !drift.path("current_api_receipt_sha256").asText()
                        .equals(api.path("content_sha256").asText())
                || !Set.of("BASELINE_ESTABLISHED", "CLEAR").contains(drift.path("status").asText())) {
            throw new CustodyException(
                "settings drift evidence is not bound to the capture/API bytes");
        }

        String tokenKind = capture.path("settings_token_identity").path("token_kind").asText();
        if ("APP".equals(tokenKind)
                && (!exactAuditorIdentity(capture.path("settings_token_identity"), tokenKind)
                || !exactAuditorIdentity(api.path("settings_token_identity"), tokenKind)
                || !exactAuditorSecret(capture.get("settings_token_secret"))
                || !exactAuditorSecret(api.get("settings_token_secret"))
                || !exactAuditorInstallation(capture.get("settings_auditor_installation"),
                        capture.path("repository").asText(), capture.get("repository_id"), tokenKind)
                || !exactAuditorInstallation(api.get("settings_auditor_installation"),
                        capture.path("repository").asText(), capture.get("repository_id"), tokenKind)
                || !sameCanonical(capture.get("settings_auditor_installation"),
                        api.get("settings_auditor_installation"))))
            throw new CustodyException("settings auditor installation proof is not exact");

        Path trustedBaseCandidate = options.trustedBaseRoot() == null
                ? Path.of("").toAbsolutePath() : options.trustedBaseRoot().toAbsolutePath().normalize();
        Path trustedBase = PathConfinement.requireRealDirectory(
                trustedBaseCandidate, "trusted verifier base");
        Path registryCandidate = options.trustedRegistryPath() == null
                ? trustedBase.resolve("strategy-research/config/v5-attestation-key-registry.json")
                : options.trustedRegistryPath().toAbsolutePath().normalize();
        if (!registryCandidate.startsWith(trustedBase))
            throw new CustodyException("trusted attestation registry escapes the trusted verifier base");
        String registryRelative = trustedBase.relativize(registryCandidate).toString()
                .replace(registryCandidate.getFileSystem().getSeparator(), "/");
        Path trustedRegistry = PathConfinement.resolve(trustedBase, registryRelative,
                "trusted-base attestation registry", PathConfinement.ExpectedType.FILE).absolute();
        byte[] trustedRegistryBytes = PathConfinement.readSinglyLinkedFile(
                trustedRegistry, "trusted-base attestation registry");
        requireGitHeadRegistry(trustedBase, registryRelative, trustedRegistryBytes);
        exactSnapshotRegistry(registry,
                files.get("v5-attestation-key-registry.json").bytes(), trustedRegistryBytes);
        ActionsAttestationVerifierV5.verify(new ActionsAttestationVerifierV5.Request(
                attestation,
                capture,
                null,
                JsonHashes.sha256(files.get("github-deployment-settings-capture.json").bytes()),
                options.nowAt(),
                options.pinnedAttestationFingerprint(),
                JsonHashes.sha256(files.get("github-settings-api-receipt.json").bytes()),
                JsonHashes.sha256(files.get("v5-shadow-cycle-receipt.json").bytes()),
                null,
                null,
                null,
                registry,
                registry.path("content_sha256").asText(),
                JsonHashes.sha256(files.get("v5-attestation-key-registry.json").bytes())));

        Path snapshotBase = trustedBase.resolve("evidence/prospective-v5").toAbsolutePath().normalize();
        LedgerSnapshot proposedLedger = readProspectiveLedger(
                ledgerRoot, options.nowAt(), true, snapshotBase, new HashSet<>());
        List<TrustedDelta> candidates = readTrustedDeltas(snapshotBase);
        validateTrustedSnapshotGraph(candidates);
        TrustedDelta tip = candidates.stream()
                .max(Comparator.comparingInt(row -> row.index().path("sequence").asInt())).orElse(null);
        WorkflowSecurityV5.LedgerCandidate base = null;
        if (tip != null) {
            LedgerSnapshot ledger = readProspectiveLedger(
                    tip.path(), options.nowAt(), true, snapshotBase, new HashSet<>());
            base = new WorkflowSecurityV5.LedgerCandidate(
                    tip.path().toString(), ledger.sequence(), ledger.currentHead(), ledger.lineage(),
                    ledger.eventHeads());
        }
        WorkflowSecurityV5.LedgerCandidate proposed = new WorkflowSecurityV5.LedgerCandidate(
                ledgerRoot.toString(), proposedLedger.sequence(), proposedLedger.currentHead(),
                proposedLedger.lineage(), proposedLedger.eventHeads());
        WorkflowSecurityV5.assertProspectiveLedgerSuccessorV5(base, proposed);
        return new Verification(true, proposed.sequence(), proposed.head(),
                base == null ? null : base.sequence());
    }

    private static void exactTopLevelInventory(Path root) {
        Set<String> expected = new HashSet<>(SNAPSHOT_FILES);
        expected.add("ledger");
        Set<String> actual = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) actual.add(entry.getFileName().toString());
        } catch (IOException error) {
            throw new CustodyException("prospective snapshot root inventory cannot be read", error);
        }
        if (!actual.equals(expected)) {
            throw new CustodyException("prospective snapshot root inventory is not exact");
        }
    }

    private static SnapshotFile snapshotJson(Path root, String name) {
        Path path = root.resolve(name);
        byte[] bytes = PathConfinement.readSinglyLinkedFile(
                path, "prospective snapshot " + name);
        JsonNode value;
        try {
            value = JsonHashes.parse(bytes, "prospective snapshot " + name);
        } catch (CustodyException error) {
            throw new CustodyException("prospective snapshot " + name + " is not JSON: "
                    + rootCauseMessage(error), error);
        }
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(value);
        if ("ACTIVE".equals(value.path("status").asText()))
            throw new CustodyException("prospective snapshot artifact may not be ACTIVE: " + name);
        if (!JsonHashes.isSha256(value.path("content_sha256").asText())
                || !value.path("content_sha256").asText().equals(JsonHashes.ownHash(value))) {
            throw new CustodyException(
                    "prospective snapshot " + name + " content hash is invalid");
        }
        return new SnapshotFile(path, bytes.clone(), value.deepCopy());
    }

    private static void exactLedgerInventory(Path ledger) {
        BasicFileAttributes attributes = attributes(ledger, "prospective ledger");
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new CustodyException(
                    "prospective ledger inventory must contain only HEAD.json and events");
        }
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(ledger)) {
            for (Path entry : entries) names.add(entry.getFileName().toString());
        } catch (IOException error) {
            throw new CustodyException(
                    "prospective ledger inventory must contain only HEAD.json and events", error);
        }
        Path events = ledger.resolve("events");
        BasicFileAttributes eventAttributes = attributes(events, "prospective ledger events");
        if (!names.equals(Set.of("HEAD.json", "events"))
                || eventAttributes.isSymbolicLink() || !eventAttributes.isDirectory()) {
            throw new CustodyException(
                    "prospective ledger inventory must contain only HEAD.json and events");
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(events)) {
            for (Path entry : entries) {
                BasicFileAttributes row = attributes(entry, "prospective ledger event");
                if (row.isSymbolicLink() || !row.isRegularFile()
                        || !EVENT_NAME.matcher(entry.getFileName().toString()).matches()) {
                    throw new CustodyException("prospective ledger event inventory is invalid: "
                            + entry.getFileName());
                }
            }
        } catch (IOException error) {
            throw new CustodyException("prospective ledger event inventory cannot be read", error);
        }
    }

    private static void exactSnapshotRegistry(
            JsonNode value, byte[] bytes, byte[] trustedBytes) {
        if (!JsonHashes.sha256(bytes).equals(JsonHashes.sha256(trustedBytes))
                || !"strategy-github-attestation-key-registry/1".equals(
                        value.path("schema").asText())
                || !"FROZEN".equals(value.path("status").asText())
                || !value.path("keys").isArray() || value.path("keys").isEmpty()) {
            throw new CustodyException(
                    "proposed attestation registry differs from the trusted-base committed registry bytes");
        }
        for (JsonNode key : value.path("keys")) {
            String pem = key.path("public_key_pem").asText();
            try {
                if (!EXACT_PUBLIC_PEM.matcher(pem).matches()
                        || !JsonHashes.sha256(pem).equals(key.path("fingerprint").asText())) {
                    throw new IllegalArgumentException();
                }
                String body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
                var publicKey = KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
                if (!(publicKey.getAlgorithm().equalsIgnoreCase("EdDSA")
                        || publicKey.getAlgorithm().equalsIgnoreCase("Ed25519"))) {
                    throw new IllegalArgumentException();
                }
            } catch (Exception error) {
                throw new CustodyException(
                        EXACT_PUBLIC_PEM.matcher(pem).matches()
                                ? "attestation registry key is not an exact Ed25519 public SPKI"
                                : "attestation registry key is not a valid public key",
                        error);
            }
        }
    }

    private static boolean exactAuditorSecret(JsonNode value) {
        return value != null
                && "V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM".equals(value.path("name").asText())
                && value.path("environment_status").asInt(-1) == 200
                && value.path("repository_status").asInt(-1) == 404
                && value.path("organization_status").asInt(-1) == 404
                && value.path("verified").asBoolean(false);
    }

    private static boolean exactAuditorIdentity(JsonNode identity, String tokenKind) {
        return "APP".equals(tokenKind) && identity != null
                && identity.path("verified").asBoolean(false)
                && identity.path("app_id").asLong(Long.MIN_VALUE) == SETTINGS_AUDITOR_APP_ID;
    }

    private static boolean exactAuditorInstallation(JsonNode proof, String repository,
                                                    JsonNode repositoryId, String tokenKind) {
        if (!"APP".equals(tokenKind)) return true;
        if (proof == null || !proof.isObject() || repository == null || repository.isBlank()) return false;
        String owner = repository.split("/", -1)[0];
        return proof.path("verified").asBoolean(false)
                && proof.path("expected_app_id").asLong(Long.MIN_VALUE) == SETTINGS_AUDITOR_APP_ID
                && proof.path("expected_installation_id").asLong(Long.MIN_VALUE)
                        == SETTINGS_AUDITOR_INSTALLATION_ID
                && SETTINGS_AUDITOR_APP_SLUG.equals(proof.path("expected_app_slug").asText())
                && proof.path("app_endpoint_status").asInt(-1) == 200
                && proof.path("installation_endpoint_status").asInt(-1) == 200
                && proof.path("repositories_endpoint_status").asInt(-1) == 200
                && proof.path("app_id").asLong(Long.MIN_VALUE) == SETTINGS_AUDITOR_APP_ID
                && SETTINGS_AUDITOR_APP_SLUG.equals(proof.path("app_slug").asText())
                && proof.path("installation_id").asLong(Long.MIN_VALUE)
                        == SETTINGS_AUDITOR_INSTALLATION_ID
                && "selected".equals(proof.path("repository_selection").asText())
                && exactAuditorPermissions(proof.get("permissions"))
                && exactAuditorPermissions(proof.get("installation_permissions"))
                && proof.path("events").isArray() && proof.path("events").isEmpty()
                && proof.path("installation_events").isArray()
                && proof.path("installation_events").isEmpty()
                && proof.path("account").path("id").asLong(0) > 0
                && owner.equals(proof.path("account").path("login").asText())
                && proof.path("accessible_repository_count").asLong(-1) == 1
                && numeric(proof.path("accessible_repository").get("id")) == numeric(repositoryId)
                && repository.equals(proof.path("accessible_repository").path("full_name").asText());
    }

    private static boolean exactAuditorPermissions(JsonNode value) {
        return value != null && value.isObject() && value.size() == 5
                && "read".equals(value.path("actions").asText())
                && "read".equals(value.path("administration").asText())
                && "read".equals(value.path("environments").asText())
                && "read".equals(value.path("metadata").asText())
                && "read".equals(value.path("secrets").asText());
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

    /** In a checkout, bind the trusted registry to the committed Git HEAD bytes. */
    static void requireGitHeadRegistry(Path trustedBase, String relative, byte[] bytes) {
        Path git = trustedBase.resolve(".git");
        if (!Files.exists(git, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(git))
            throw new CustodyException("trusted verifier .git path is a symlink");
        try {
            Process process = new ProcessBuilder("git", "-C", trustedBase.toString(),
                    "show", "HEAD:" + relative)
                    .redirectErrorStream(false).start();
            byte[] headBytes;
            try (InputStream stream = process.getInputStream()) {
                headBytes = stream.readAllBytes();
            }
            int status = process.waitFor();
            if (status != 0 || !java.util.Arrays.equals(headBytes, bytes))
                throw new CustodyException(
                        "trusted attestation registry is not anchored to Git HEAD");
        } catch (IOException error) {
            throw new CustodyException("trusted attestation registry Git HEAD cannot be read", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CustodyException("trusted attestation registry Git HEAD read was interrupted", error);
        }
    }

    private static List<TrustedDelta> readTrustedDeltas(Path snapshotBase) {
        if (!Files.exists(snapshotBase, LinkOption.NOFOLLOW_LINKS)) return List.of();
        Path base = PathConfinement.requireRealDirectory(snapshotBase,
                "trusted prospective snapshot root");
        List<TrustedDelta> rows = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(base)) {
            for (Path entry : entries) {
                BasicFileAttributes attributes = Files.readAttributes(
                        entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String root = entry.getFileName().toString();
                if (attributes.isSymbolicLink() || !attributes.isDirectory()
                        || !JsonHashes.isSha256(root)) continue;
                Path ledger = PathConfinement.requireRealDirectory(
                        entry.resolve("ledger"), "trusted prospective ledger");
                rows.add(readTrustedDelta(root, ledger));
            }
        } catch (IOException error) {
            throw new CustodyException("trusted prospective snapshot roots cannot be read", error);
        }
        return rows;
    }

    private static TrustedDelta readTrustedDelta(String root, Path ledger) {
        JsonNode index;
        try {
            index = parseSingleFile(ledger.resolve("HEAD.json"),
                    "trusted prospective ledger HEAD");
        } catch (CustodyException error) {
            throw new CustodyException("trusted prospective ledger HEAD is invalid: " + root
                    + ": " + rootCauseMessage(error), error);
        }
        if (!validIndex(index)) {
            throw new CustodyException("trusted prospective ledger HEAD is invalid: " + root);
        }
        boolean rootMissing = !index.hasNonNull("prior_snapshot_root");
        boolean headMissing = !index.hasNonNull("prior_head_sha256");
        if (rootMissing != headMissing
                || (!rootMissing && (!JsonHashes.isSha256(index.path("prior_snapshot_root").asText())
                    || !JsonHashes.isSha256(index.path("prior_head_sha256").asText())))) {
            throw new CustodyException(
                    "trusted prospective ledger predecessor binding is invalid: " + root);
        }
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(index);
        List<String> eventHeads = new ArrayList<>();
        String previous = rootMissing
                ? WorkflowSecurityV5.prospectiveLedgerGenesis(index.path("lineage_sha256").asText())
                : index.path("prior_head_sha256").asText();
        Integer previousSequence = rootMissing ? 0 : null;
        for (JsonNode reference : index.path("event_refs")) {
            int sequence = reference.path("sequence").asInt(Integer.MIN_VALUE);
            if (!reference.path("sequence").isIntegralNumber() || sequence < 1
                    || !JsonHashes.isSha256(reference.path("event_sha256").asText())
                    || !JsonHashes.isSha256(reference.path("byte_sha256").asText())) {
                throw new CustodyException(
                        "trusted prospective ledger event reference is invalid: " + root);
            }
            if (previousSequence != null && sequence != previousSequence + 1) {
                throw new CustodyException(
                        "trusted prospective ledger event sequence is not dense: " + root);
            }
            previousSequence = sequence;
            String relative = PathConfinement.repositoryRelativePath(
                    reference.path("path").asText(),
                    "trusted prospective ledger event " + root);
            if (!EVENT_FILE.matcher(relative).matches()) {
                throw new CustodyException(
                        "trusted prospective ledger event path is invalid: " + root);
            }
            JsonNode event;
            byte[] bytes;
            try {
                Path path = PathConfinement.resolve(ledger, relative,
                        "trusted prospective ledger event " + root,
                        PathConfinement.ExpectedType.FILE).absolute();
                bytes = PathConfinement.readSinglyLinkedFile(path,
                        "trusted prospective ledger event " + root);
                event = JsonHashes.parse(bytes, "trusted prospective ledger event " + root);
            } catch (CustodyException error) {
                throw new CustodyException(
                        "trusted prospective ledger event is missing or invalid: " + root
                                + ": " + rootCauseMessage(error), error);
            }
            if (!JsonHashes.sha256(bytes).equals(reference.path("byte_sha256").asText())
                    || event.path("sequence").asInt(Integer.MIN_VALUE) != sequence
                    || !event.path("event_sha256").asText()
                            .equals(reference.path("event_sha256").asText())
                    || !event.path("event_sha256").asText()
                            .equals(JsonHashes.ownHash(event, "event_sha256"))
                    || !event.path("previous_head_sha256").asText().equals(previous)) {
                throw new CustodyException(
                        "trusted prospective ledger event chain is invalid: " + root);
            }
            previous = event.path("event_sha256").asText();
            eventHeads.add(previous);
        }
        if (rootMissing && index.path("sequence").asInt() != index.path("event_refs").size()) {
            throw new CustodyException(
                    "trusted prospective ledger delta sequence is invalid: " + root);
        }
        return new TrustedDelta(root, ledger.toAbsolutePath().normalize(), index, List.copyOf(eventHeads));
    }

    private static void validateTrustedSnapshotGraph(List<TrustedDelta> candidates) {
        Map<String, TrustedDelta> byRoot = new HashMap<>();
        candidates.forEach(row -> byRoot.put(row.root(), row));
        for (TrustedDelta row : candidates) {
            Set<String> seen = new HashSet<>();
            seen.add(row.root());
            TrustedDelta current = row;
            while (current.index().hasNonNull("prior_snapshot_root")) {
                String priorName = current.index().path("prior_snapshot_root").asText();
                if (!JsonHashes.isSha256(priorName) || !seen.add(priorName)) {
                    throw new CustodyException(
                            "trusted prospective ledger snapshot chain is invalid: " + row.root());
                }
                TrustedDelta prior = byRoot.get(priorName);
                JsonNode firstRef = current.index().path("event_refs").isEmpty()
                        ? null : current.index().path("event_refs").get(0);
                if (prior == null
                        || !prior.index().path("lineage_sha256").asText()
                                .equals(current.index().path("lineage_sha256").asText())
                        || !prior.index().path("head_sha256").asText()
                                .equals(current.index().path("prior_head_sha256").asText())
                        || prior.index().path("sequence").asInt()
                                >= current.index().path("sequence").asInt()
                        || current.index().path("sequence").asInt()
                                != prior.index().path("sequence").asInt()
                                    + current.index().path("event_refs").size()
                        || firstRef == null
                        || firstRef.path("sequence").asInt()
                                != prior.index().path("sequence").asInt() + 1) {
                    throw new CustodyException(
                            "trusted prospective ledger snapshot predecessor is invalid: " + row.root());
                }
                current = prior;
            }
        }
        Map<Integer, String> sequenceHeads = new HashMap<>();
        for (TrustedDelta row : candidates) {
            int sequence = row.index().path("sequence").asInt();
            String prior = sequenceHeads.putIfAbsent(
                    sequence, row.index().path("head_sha256").asText());
            if (prior != null && !prior.equals(row.index().path("head_sha256").asText())) {
                throw new CustodyException(
                        "trusted prospective ledgers fork at sequence " + sequence);
            }
        }
    }

    private static LedgerSnapshot readProspectiveLedger(
            Path ledger,
            long nowAt,
            boolean allowFuture,
            Path snapshotBase,
            Set<Path> seen) {
        Path root = PathConfinement.requireRealDirectory(ledger, "prospective ledger");
        if (!seen.add(root)) {
            throw new CustodyException("prospective ledger snapshot chain contains a cycle");
        }
        LedgerIndex index = readLedgerIndex(root);
        int requested = index.value().path("sequence").asInt(Integer.MIN_VALUE);
        if (requested < 0) throw new CustodyException("invalid requested ledger sequence");
        List<EventSource> sources = ledgerSources(root, index.value(), snapshotBase, seen);
        List<JsonNode> events = new ArrayList<>();
        List<String> eventHeads = new ArrayList<>();
        String previous = WorkflowSecurityV5.prospectiveLedgerGenesis(
                index.value().path("lineage_sha256").asText());
        for (EventSource source : sources) {
            for (JsonNode reference : source.refs()) {
                int sequence = reference.path("sequence").asInt(Integer.MIN_VALUE);
                if (sequence > requested) continue;
                String relative = PathConfinement.repositoryRelativePath(
                        reference.path("path").asText(), "prospective ledger event");
                if (!EVENT_FILE.matcher(relative).matches()
                        || !JsonHashes.isSha256(reference.path("byte_sha256").asText())) {
                    throw new CustodyException("immutable prospective event chain is invalid");
                }
                Path eventPath = PathConfinement.resolve(source.ledger(), relative,
                        "prospective ledger event", PathConfinement.ExpectedType.FILE).absolute();
                byte[] bytes = PathConfinement.readSinglyLinkedFile(
                        eventPath, "prospective ledger event");
                if (!JsonHashes.sha256(bytes).equals(reference.path("byte_sha256").asText())) {
                    throw new CustodyException("physical source byte hash mismatch");
                }
                JsonNode event = JsonHashes.parse(bytes, "physical source artifact");
                if (!event.path("sequence").isIntegralNumber()
                        || event.path("sequence").asInt(Integer.MIN_VALUE) != sequence
                        || !event.path("event_sha256").asText()
                                .equals(reference.path("event_sha256").asText())
                        || !event.path("previous_head_sha256").asText().equals(previous)
                        || !event.path("event_sha256").asText()
                                .equals(JsonHashes.ownHash(event, "event_sha256"))) {
                    throw new CustodyException("immutable prospective event chain is invalid");
                }
                previous = event.path("event_sha256").asText();
                eventHeads.add(previous);
                events.add(event.deepCopy());
            }
        }
        if (events.size() != requested && requested != 0) {
            throw new CustodyException("requested ledger sequence is unavailable");
        }
        for (JsonNode event : events) {
            long availability = instant(event.path("availability_time").asText());
            long decision = instant(event.path("decision_time").asText());
            if (!allowFuture && (availability > nowAt || decision > nowAt)) {
                throw new CustodyException("future prospective evidence is not admissible");
            }
            if (availability == Long.MIN_VALUE || decision == Long.MIN_VALUE) {
                throw new CustodyException("invalid timestamp: "
                        + event.path("availability_time").asText());
            }
            if (availability > decision) {
                throw new CustodyException("event availability is after decision time");
            }
        }
        String loadedHead = eventHeads.isEmpty()
                ? WorkflowSecurityV5.prospectiveLedgerGenesis(
                        index.value().path("lineage_sha256").asText())
                : eventHeads.get(eventHeads.size() - 1);
        return new LedgerSnapshot(events.size(), loadedHead,
                index.value().path("head_sha256").asText(),
                index.value().path("lineage_sha256").asText(),
                List.copyOf(eventHeads), List.copyOf(events));
    }

    private static List<EventSource> ledgerSources(
            Path ledger, JsonNode index, Path snapshotBase, Set<Path> seen) {
        if (!index.hasNonNull("prior_snapshot_root")) {
            return List.of(new EventSource(ledger, index.path("event_refs")));
        }
        String priorRoot = index.path("prior_snapshot_root").asText();
        if (!JsonHashes.isSha256(priorRoot)
                || !JsonHashes.isSha256(index.path("prior_head_sha256").asText())) {
            throw new CustodyException("prospective ledger snapshot predecessor binding is invalid");
        }
        Path base = PathConfinement.requireRealDirectory(
                snapshotBase, "trusted prospective snapshot root");
        Path priorPath = PathConfinement.resolve(base, priorRoot + "/ledger",
                "prospective ledger snapshot predecessor", PathConfinement.ExpectedType.DIRECTORY).absolute();
        if (!seen.add(priorPath)) {
            throw new CustodyException("prospective ledger snapshot chain contains a cycle");
        }
        LedgerIndex prior = readLedgerIndex(priorPath);
        if (!prior.value().path("lineage_sha256").asText()
                    .equals(index.path("lineage_sha256").asText())
                || !prior.value().path("head_sha256").asText()
                    .equals(index.path("prior_head_sha256").asText())
                || prior.value().path("sequence").asInt()
                    >= index.path("sequence").asInt()) {
            throw new CustodyException(
                    "prospective ledger snapshot predecessor is not the exact immutable prefix");
        }
        List<EventSource> sources = new ArrayList<>(
                ledgerSources(priorPath, prior.value(), snapshotBase, seen));
        sources.add(new EventSource(ledger, index.path("event_refs")));
        return sources;
    }

    private static LedgerIndex readLedgerIndex(Path ledger) {
        JsonNode index = parseSingleFile(ledger.resolve("HEAD.json"), "prospective ledger HEAD");
        if (!validIndex(index)) throw new CustodyException("prospective ledger HEAD is invalid");
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(index);
        return new LedgerIndex(ledger, index);
    }

    private static boolean validIndex(JsonNode index) {
        return index != null && index.isObject()
                && "strategy-prospective-ledger-index/1".equals(index.path("schema").asText())
                && index.path("content_sha256").asText().equals(JsonHashes.ownHash(index))
                && JsonHashes.isSha256(index.path("lineage_sha256").asText())
                && index.path("sequence").isIntegralNumber()
                && index.path("sequence").asInt(Integer.MIN_VALUE) >= 0
                && index.path("event_refs").isArray();
    }

    private static JsonNode parseSingleFile(Path path, String label) {
        byte[] bytes = PathConfinement.readSinglyLinkedFile(path, label);
        return JsonHashes.parse(bytes, label);
    }

    private static BasicFileAttributes attributes(Path path, String label) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException error) {
            throw new CustodyException(label + " is missing", error);
        }
    }

    private static long instant(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException error) {
            return Long.MIN_VALUE;
        }
    }

    private static String string(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        return value.isTextual() ? value.textValue() : value.asText();
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage();
    }
}
