package com.tradinganalytics.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** High-level custody exports from {@code strategy-v5-workflow-security.mjs}. */
public final class WorkflowSecurityV5 {
    public static final CustodyLimits EVIDENCE_CUSTODY_LIMITS = CustodyLimits.DEFAULT;
    public static final List<String> SOURCE_BUNDLE_ROLES = List.of(
            "reservation", "source_receipt", "bar", "feature_input",
            "candidate_set", "evaluator_code", "signal_decision");

    private static final Pattern SNAPSHOT_PATH = Pattern.compile(
            "^evidence/prospective-v5/([a-f0-9]{64})(?:/|$)");

    private WorkflowSecurityV5() {}

    public record LedgerCandidate(
            String path, int sequence, String head, String lineage, List<String> eventHeads) {
        public LedgerCandidate {
            eventHeads = eventHeads == null ? null : List.copyOf(eventHeads);
        }
    }

    public record ConfinedJson(
            Path absolute,
            String relative,
            BasicFileAttributes attributes,
            byte[] bytes,
            JsonNode value) {
        public ConfinedJson {
            bytes = bytes.clone();
            value = value.deepCopy();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public JsonNode value() { return value.deepCopy(); }
    }

    public record SourceBundleVerification(
            JsonNode bundle,
            ConfinedJson bundlePhysical,
            Map<String, ConfinedJson> references,
            PathConfinement.ResolvedPath ledger) {
        public SourceBundleVerification {
            bundle = bundle.deepCopy();
            references = Map.copyOf(references);
        }
        @Override public JsonNode bundle() { return bundle.deepCopy(); }
    }

    public record ProspectiveSnapshotOptions(
            Path proposedRoot,
            Path trustedBaseRoot,
            Path trustedRegistryPath,
            String pinnedAttestationFingerprint,
            long nowAt) {
        public ProspectiveSnapshotOptions(Path proposedRoot, Path trustedBaseRoot) {
            this(proposedRoot, trustedBaseRoot, null, null, System.currentTimeMillis());
        }
    }

    public record ProspectiveSnapshotVerification(
            boolean verified, int sequence, String head, Integer trustedBaseSequence) {}

    /** Exact pure port of {@code selectProspectiveLedgerCandidateV5}. */
    public static LedgerCandidate selectProspectiveLedgerCandidateV5(List<LedgerCandidate> candidates) {
        if (candidates == null) {
            throw new CustodyException("prospective ledger candidates must be an array");
        }
        List<LedgerCandidate> rows = new ArrayList<>();
        for (LedgerCandidate candidate : candidates) {
            validateHistoricalCandidate(candidate);
            String expectedHead = candidate.sequence() == 0
                    ? prospectiveLedgerGenesis(candidate.lineage())
                    : candidate.eventHeads().get(candidate.eventHeads().size() - 1);
            if (!candidate.head().equals(expectedHead)) {
                throw new CustodyException(
                        "historical prospective ledger candidate head does not match its event prefix: "
                                + candidate.path());
            }
            rows.add(candidate);
        }
        Set<String> lineages = new HashSet<>();
        rows.forEach(row -> lineages.add(row.lineage()));
        if (lineages.size() > 1) {
            throw new CustodyException("historical prospective ledgers have different lineages");
        }
        for (int left = 0; left < rows.size(); left++) {
            for (int right = left + 1; right < rows.size(); right++) {
                LedgerCandidate a = rows.get(left);
                LedgerCandidate b = rows.get(right);
                if (a.sequence() == b.sequence() && !a.head().equals(b.head())) {
                    throw new CustodyException("historical prospective ledgers fork at the same sequence");
                }
                LedgerCandidate older = a.sequence() <= b.sequence() ? a : b;
                LedgerCandidate newer = older == a ? b : a;
                for (int index = 0; index < older.eventHeads().size(); index++) {
                    if (!older.eventHeads().get(index).equals(newer.eventHeads().get(index))) {
                        throw new CustodyException(
                                "historical prospective ledgers are not a strict prefix chain");
                    }
                }
            }
        }
        LedgerCandidate best = null;
        for (LedgerCandidate row : rows) {
            if (best == null || row.sequence() > best.sequence()) best = row;
        }
        return best;
    }

    public static String prospectiveSnapshotRootV5(String path) {
        String value = path == null ? "" : path;
        Matcher match = SNAPSHOT_PATH.matcher(value);
        String root = match.find() ? "evidence/prospective-v5/" + match.group(1) : null;
        if (root == null || !value.startsWith(root + "/")) {
            throw new CustodyException(
                    "prospective evidence path is not beneath one content-addressed snapshot root: " + value);
        }
        return root;
    }

    public static String requireSingleProspectiveSnapshotRootV5(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            throw new CustodyException("prospective evidence PR must add exactly one snapshot root");
        }
        Set<String> roots = new HashSet<>();
        paths.forEach(path -> roots.add(prospectiveSnapshotRootV5(path)));
        if (roots.size() != 1) {
            throw new CustodyException("prospective evidence PR must add exactly one snapshot root");
        }
        return roots.iterator().next();
    }

    /** Exact pure port of {@code assertProspectiveLedgerSuccessorV5}. */
    public static boolean assertProspectiveLedgerSuccessorV5(
            LedgerCandidate base, LedgerCandidate proposed) {
        if (!validCandidate(proposed, false)) {
            throw new CustodyException("proposed prospective ledger candidate is invalid");
        }
        String expectedProposedHead = proposed.sequence() == 0
                ? prospectiveLedgerGenesis(proposed.lineage())
                : proposed.eventHeads().get(proposed.eventHeads().size() - 1);
        if (!proposed.head().equals(expectedProposedHead)) {
            throw new CustodyException(
                    "proposed prospective ledger head does not match its event prefix");
        }
        if (base == null) {
            String genesis = prospectiveLedgerGenesis(proposed.lineage());
            if (proposed.sequence() != 0 || !proposed.head().equals(genesis)) {
                throw new CustodyException(
                        "prospective evidence has no trusted base and is not a valid explicit genesis");
            }
            return true;
        }
        if (!validCandidate(base, false)) {
            throw new CustodyException("trusted base prospective ledger candidate is invalid");
        }
        String expectedBaseHead = base.sequence() == 0
                ? prospectiveLedgerGenesis(base.lineage())
                : base.eventHeads().get(base.eventHeads().size() - 1);
        if (!base.head().equals(expectedBaseHead)) {
            throw new CustodyException(
                    "trusted base prospective ledger head does not match its event prefix");
        }
        if (!proposed.lineage().equals(base.lineage())) {
            throw new CustodyException("prospective ledger successor lineage differs from trusted base");
        }
        if (proposed.sequence() <= base.sequence()) {
            throw new CustodyException("prospective ledger is a rollback or non-successor");
        }
        for (int index = 0; index < base.sequence(); index++) {
            if (!proposed.eventHeads().get(index).equals(base.eventHeads().get(index))) {
                throw new CustodyException("prospective ledger successor forks from trusted base prefix");
            }
        }
        return true;
    }

    public static String prospectiveLedgerGenesis(String lineage) {
        var payload = JsonHashes.mapper().createObjectNode();
        payload.put("schema", "strategy-prospective-ledger-genesis/1");
        payload.put("lineage_sha256", lineage);
        return JsonHashes.canonicalSha256(payload);
    }

    public static String repositoryRelativePath(String value, String label) {
        return PathConfinement.repositoryRelativePath(value, label);
    }

    public static PathConfinement.ResolvedPath confinedPath(
            Path root, String value, String label, boolean directory, boolean file) {
        PathConfinement.ExpectedType type = directory
                ? PathConfinement.ExpectedType.DIRECTORY
                : file ? PathConfinement.ExpectedType.FILE : PathConfinement.ExpectedType.ANY;
        return PathConfinement.resolve(root, value, label, type);
    }

    public static SafeTreeVerifier.TreeSummary verifySafeTree(
            Path root, String label, SafeTreeVerifier.Options options) {
        return SafeTreeVerifier.verify(root, label, options);
    }

    public static SafeTreeVerifier.TreeSummary verifyTarArchive(
            Path archive, String label, SafeTreeVerifier.Options options) {
        return TarArchiveVerifier.verify(archive, label, options);
    }

    /** Confines, bounds, securely reopens and strictly parses one JSON artifact. */
    public static ConfinedJson readConfinedJson(Path root, String value) {
        return readConfinedJson(root, value, "JSON artifact");
    }

    public static ConfinedJson readConfinedJson(Path root, String value, String label) {
        PathConfinement.ResolvedPath path = PathConfinement.resolve(
                root, value, label, PathConfinement.ExpectedType.FILE);
        EvidenceContentValidator.validateFilename(path.relative(), label);
        if (path.attributes().size() > EVIDENCE_CUSTODY_LIMITS.maxFileBytes()) {
            throw new CustodyException(
                    label + " file exceeds the per-file byte ceiling: " + path.absolute());
        }
        if (path.attributes().size() > EVIDENCE_CUSTODY_LIMITS.maxTotalBytes()) {
            throw new CustodyException(label + " tree exceeds the total byte ceiling");
        }
        byte[] bytes = PathConfinement.readSinglyLinkedFile(path.absolute(), label);
        JsonNode parsed = EvidenceContentValidator.validateBytes(bytes, label, path.relative());
        return new ConfinedJson(path.absolute(), path.relative(), path.attributes(), bytes, parsed);
    }

    /** Exact role and byte binding checks from {@code verifyProspectiveSourceBundle}. */
    public static SourceBundleVerification verifyProspectiveSourceBundle(
            Path root, String bundlePath) {
        ConfinedJson bundlePhysical = readConfinedJson(root, bundlePath, "prospective source bundle");
        JsonNode bundle = bundlePhysical.value();
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(bundle);
        if (!"strategy-prospective-source-bundle/1".equals(bundle.path("schema").asText())
                || !"FROZEN".equals(bundle.path("status").asText())
                || !"SHADOW".equals(bundle.path("decision").asText())) {
            throw new CustodyException("prospective source bundle is not a frozen SHADOW bundle");
        }
        if (!JsonHashes.isSha256(bundle.path("content_sha256").asText())
                || !bundle.path("content_sha256").asText().equals(JsonHashes.ownHash(bundle))) {
            throw new CustodyException(
                    "prospective source bundle content hash is missing or tampered");
        }
        if (!JsonHashes.isSha256(bundle.path("expected_head_sha256").asText())
                || !JsonHashes.isSha256(bundle.path("lineage_sha256").asText())) {
            throw new CustodyException("prospective source bundle lineage/head hash is invalid");
        }
        Map<String, ConfinedJson> references = new LinkedHashMap<>();
        for (String role : SOURCE_BUNDLE_ROLES) {
            JsonNode reference = bundle.get(role);
            if (reference == null || !reference.isObject()
                    || !reference.path("path").isTextual()
                    || !JsonHashes.isSha256(reference.path("byte_sha256").asText())) {
                throw new CustodyException(role + " source-bundle reference is incomplete");
            }
            ConfinedJson physical = readConfinedJson(
                    root, reference.path("path").asText(), role + " source-bundle artifact");
            if (!JsonHashes.sha256(physical.bytes()).equals(reference.path("byte_sha256").asText())) {
                throw new CustodyException(
                        role + " source-bundle byte hash does not match the physical artifact");
            }
            references.put(role, physical);
        }
        if (!bundle.path("ledger_path").isTextual()) {
            throw new CustodyException("prospective source bundle ledger_path is missing");
        }
        PathConfinement.ResolvedPath ledger = PathConfinement.resolve(
                root, bundle.path("ledger_path").asText(), "prospective source-bundle ledger",
                PathConfinement.ExpectedType.DIRECTORY);
        SafeTreeVerifier.verify(ledger.absolute(), "prospective source-bundle ledger",
                SafeTreeVerifier.Options.EVIDENCE);
        return new SourceBundleVerification(bundle, bundlePhysical, references, ledger);
    }

    /** Link-rejecting, no-overwrite tree copy followed by a second full verification. */
    public static Path copyConfinedDirectory(Path source, Path target) {
        return copyConfinedDirectory(source, target, "ledger");
    }

    public static Path copyConfinedDirectory(Path source, Path target, String label) {
        Path sourceRoot = PathConfinement.requireRealDirectory(source, label);
        SafeTreeVerifier.verify(sourceRoot, label, SafeTreeVerifier.Options.EVIDENCE);
        Path destination = target.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new CustodyException(label + " destination already exists");
        }
        Path parent = destination.getParent();
        if (parent == null) throw new CustodyException(label + " destination parent is missing");
        try {
            Files.createDirectories(parent);
            Path realParent = PathConfinement.requireRealDirectory(parent, label + " destination parent");
            destination = realParent.resolve(destination.getFileName());
            Files.createDirectory(destination);
            Path finalDestination = destination;
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    if (!directory.equals(sourceRoot)) {
                        Files.createDirectory(finalDestination.resolve(sourceRoot.relativize(directory)));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    String display = label + "/" + sourceRoot.relativize(file);
                    if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                        throw new CustodyException(display + " is not a regular, singly-linked file");
                    }
                    PathConfinement.requireSingleLink(file, display);
                    Files.copy(file, finalDestination.resolve(sourceRoot.relativize(file)),
                            StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (FileAlreadyExistsException error) {
            throw new CustodyException(label + " destination already exists", error);
        } catch (IOException error) {
            throw new CustodyException(label + " cannot be copied", error);
        }
        SafeTreeVerifier.verify(destination, label + " copy", SafeTreeVerifier.Options.EVIDENCE);
        return destination;
    }

    /** Complete trusted-base-only port of {@code verifyProspectiveSnapshotV5}. */
    public static ProspectiveSnapshotVerification verifyProspectiveSnapshotV5(
            ProspectiveSnapshotOptions options) {
        if (options == null) {
            throw new CustodyException("prospective evidence snapshot is required");
        }
        ProspectiveSnapshotVerifierV5.Verification result = ProspectiveSnapshotVerifierV5.verify(
                new ProspectiveSnapshotVerifierV5.Options(
                        options.proposedRoot(), options.trustedBaseRoot(),
                        options.trustedRegistryPath(), options.pinnedAttestationFingerprint(),
                        options.nowAt()));
        return new ProspectiveSnapshotVerification(
                result.verified(), result.sequence(), result.head(), result.trustedBaseSequence());
    }

    private static void validateHistoricalCandidate(LedgerCandidate candidate) {
        if (!validCandidate(candidate, true)) {
            String path = candidate == null || candidate.path() == null
                    ? "<unknown>" : candidate.path();
            throw new CustodyException("invalid historical prospective ledger candidate " + path);
        }
    }

    private static boolean validCandidate(LedgerCandidate candidate, boolean requirePath) {
        if (candidate == null || (requirePath && candidate.path() == null) || candidate.sequence() < 0
                || !JsonHashes.isSha256(candidate.head()) || !JsonHashes.isSha256(candidate.lineage())
                || candidate.eventHeads() == null
                || candidate.eventHeads().size() != candidate.sequence()) return false;
        return candidate.eventHeads().stream().allMatch(JsonHashes::isSha256);
    }
}
