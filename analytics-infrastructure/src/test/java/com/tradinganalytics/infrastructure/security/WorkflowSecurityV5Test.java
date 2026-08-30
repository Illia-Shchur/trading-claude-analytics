package com.tradinganalytics.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkflowSecurityV5Test {
    @TempDir Path temporary;

    @Test
    void pureLedgerAndSnapshotExportsSelectTheVerifiedTip() {
        String lineage = JsonHashes.sha256("lineage");
        List<String> heads = List.of(
                JsonHashes.sha256("event-1"), JsonHashes.sha256("event-2"),
                JsonHashes.sha256("event-3"));
        List<WorkflowSecurityV5.LedgerCandidate> candidates = new ArrayList<>();
        for (int sequence = 1; sequence <= 3; sequence++) {
            List<String> prefix = heads.subList(0, sequence);
            candidates.add(new WorkflowSecurityV5.LedgerCandidate(
                    "ledger-" + sequence, sequence, heads.get(sequence - 1), lineage, prefix));
        }
        assertThat(WorkflowSecurityV5.selectProspectiveLedgerCandidateV5(candidates).sequence())
                .isEqualTo(3);

        String first = "evidence/prospective-v5/" + "a".repeat(64);
        assertThat(WorkflowSecurityV5.prospectiveSnapshotRootV5(first + "/ledger/HEAD.json"))
                .isEqualTo(first);
        assertThat(WorkflowSecurityV5.requireSingleProspectiveSnapshotRootV5(List.of(
                first + "/ledger/HEAD.json", first + "/events/one.json"))).isEqualTo(first);
        assertThatThrownBy(() -> WorkflowSecurityV5.prospectiveSnapshotRootV5(
                "evidence/prospective-v5/not-a-hash/ledger/HEAD.json"))
                .hasMessage("prospective evidence path is not beneath one content-addressed snapshot root: "
                        + "evidence/prospective-v5/not-a-hash/ledger/HEAD.json");

        var base = new WorkflowSecurityV5.LedgerCandidate(
                null, 2, heads.get(1), lineage, heads.subList(0, 2));
        var proposed = new WorkflowSecurityV5.LedgerCandidate(
                null, 3, heads.get(2), lineage, heads);
        assertThat(WorkflowSecurityV5.assertProspectiveLedgerSuccessorV5(base, proposed)).isTrue();
        String genesis = WorkflowSecurityV5.prospectiveLedgerGenesis(lineage);
        assertThat(WorkflowSecurityV5.assertProspectiveLedgerSuccessorV5(null,
                new WorkflowSecurityV5.LedgerCandidate(null, 0, genesis, lineage, List.of())))
                .isTrue();
    }

    @Test
    void rejectsForkRollbackCorruptionAndMultipleSnapshotRootsWithOracleMessages() throws Exception {
        String lineage = JsonHashes.sha256("fork-lineage");
        List<String> heads = List.of(
                JsonHashes.sha256("one"), JsonHashes.sha256("two"), JsonHashes.sha256("three"));
        var first = new WorkflowSecurityV5.LedgerCandidate(
                "first", 2, heads.get(1), lineage, heads.subList(0, 2));
        var fork = new WorkflowSecurityV5.LedgerCandidate(
                "fork", 2, JsonHashes.sha256("fork"), lineage,
                List.of(heads.get(0), JsonHashes.sha256("fork")));
        assertThatThrownBy(() -> WorkflowSecurityV5.selectProspectiveLedgerCandidateV5(
                List.of(first, fork))).hasMessage("historical prospective ledgers fork at the same sequence");
        var corrupt = new WorkflowSecurityV5.LedgerCandidate(
                "corrupt", 3, heads.get(2), lineage,
                List.of(heads.get(0), JsonHashes.sha256("corrupt"), heads.get(2)));
        assertThatThrownBy(() -> WorkflowSecurityV5.selectProspectiveLedgerCandidateV5(
                List.of(first, corrupt))).hasMessageContaining("strict prefix chain");

        var rollback = new WorkflowSecurityV5.LedgerCandidate(
                null, 1, heads.get(0), lineage, heads.subList(0, 1));
        assertThatThrownBy(() -> WorkflowSecurityV5.assertProspectiveLedgerSuccessorV5(
                first, rollback)).hasMessage("prospective ledger is a rollback or non-successor");
        var otherLineage = new WorkflowSecurityV5.LedgerCandidate(
                null, 3, heads.get(2), JsonHashes.sha256("other"), heads);
        assertThatThrownBy(() -> WorkflowSecurityV5.assertProspectiveLedgerSuccessorV5(
                first, otherLineage)).hasMessageContaining("lineage differs");
        var forkSuccessor = new WorkflowSecurityV5.LedgerCandidate(
                null, 3, heads.get(2), lineage,
                List.of(JsonHashes.sha256("replacement"), heads.get(1), heads.get(2)));
        assertThatThrownBy(() -> WorkflowSecurityV5.assertProspectiveLedgerSuccessorV5(
                first, forkSuccessor)).hasMessageContaining("forks from trusted base prefix");
        assertThatThrownBy(() -> WorkflowSecurityV5.assertProspectiveLedgerSuccessorV5(
                null, rollback)).hasMessageContaining("explicit genesis");

        String a = "evidence/prospective-v5/" + "a".repeat(64) + "/a.json";
        String b = "evidence/prospective-v5/" + "b".repeat(64) + "/b.json";
        assertThatThrownBy(() -> WorkflowSecurityV5.requireSingleProspectiveSnapshotRootV5(
                List.of(a, b))).hasMessage("prospective evidence PR must add exactly one snapshot root");
        assertThatThrownBy(() -> WorkflowSecurityV5.requireSingleProspectiveSnapshotRootV5(List.of()))
                .hasMessage("prospective evidence PR must add exactly one snapshot root");
    }

    @Test
    void sourceBundleReopensEveryRoleAndRejectsTraversalLinksHardlinksAndMutation()
            throws Exception {
        Map<String, ObjectNode> references = new LinkedHashMap<>();
        for (String role : WorkflowSecurityV5.SOURCE_BUNDLE_ROLES) {
            byte[] bytes = ("{\"role\":\"" + role + "\"}\n").getBytes(StandardCharsets.UTF_8);
            Files.write(temporary.resolve(role + ".json"), bytes);
            ObjectNode reference = JsonHashes.mapper().createObjectNode();
            reference.put("path", role + ".json");
            reference.put("byte_sha256", JsonHashes.sha256(bytes));
            references.put(role, reference);
        }
        Path ledger = Files.createDirectory(temporary.resolve("ledger"));
        Files.writeString(ledger.resolve("HEAD.json"), "{}\n");
        writeBundle("bundle.json", "ledger", references);
        var verified = WorkflowSecurityV5.verifyProspectiveSourceBundle(temporary, "bundle.json");
        assertThat(verified.references()).containsOnlyKeys(WorkflowSecurityV5.SOURCE_BUNDLE_ROLES);
        assertThat(verified.ledger().relative()).isEqualTo("ledger");

        assertThatThrownBy(() -> WorkflowSecurityV5.verifyProspectiveSourceBundle(
                temporary, temporary.resolve("bundle.json").toString()))
                .hasMessageContaining("repository-relative");
        assertThatThrownBy(() -> WorkflowSecurityV5.verifyProspectiveSourceBundle(
                temporary, "../outside.json")).hasMessageMatching(".*(relative|traversal).*" );

        Files.createSymbolicLink(temporary.resolve("reservation-link.json"),
                temporary.resolve("reservation.json"));
        Map<String, ObjectNode> symlinkReferences = deepReferences(references);
        symlinkReferences.get("reservation").put("path", "reservation-link.json");
        writeBundle("bundle-symlink.json", "ledger", symlinkReferences);
        assertThatThrownBy(() -> WorkflowSecurityV5.verifyProspectiveSourceBundle(
                temporary, "bundle-symlink.json")).hasMessageContaining("symlink");

        Files.createSymbolicLink(temporary.resolve("ledger-link"), ledger);
        writeBundle("bundle-ledger-link.json", "ledger-link", references);
        assertThatThrownBy(() -> WorkflowSecurityV5.verifyProspectiveSourceBundle(
                temporary, "bundle-ledger-link.json")).hasMessageContaining("symlink");

        Files.createLink(temporary.resolve("bar-hardlink.json"), temporary.resolve("bar.json"));
        Map<String, ObjectNode> hardlinkReferences = deepReferences(references);
        hardlinkReferences.get("bar").put("path", "bar-hardlink.json");
        writeBundle("bundle-hardlink.json", "ledger", hardlinkReferences);
        assertThatThrownBy(() -> WorkflowSecurityV5.verifyProspectiveSourceBundle(
                temporary, "bundle-hardlink.json")).hasMessageContaining("singly-linked");

        Files.writeString(temporary.resolve("source_receipt.json"), "{\"mutated\":true}\n");
        assertThatThrownBy(() -> WorkflowSecurityV5.verifyProspectiveSourceBundle(
                temporary, "bundle.json")).hasMessageContaining("byte hash does not match");
    }

    @Test
    void confinedCopyIsNoOverwriteAndRejectsHostileTrees() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("a.json"), "{}\n");
        Path nested = Files.createDirectory(source.resolve("nested"));
        Files.writeString(nested.resolve("b.json"), "[]\n");
        Path destination = temporary.resolve("copy");
        Path copied = WorkflowSecurityV5.copyConfinedDirectory(source, destination);
        assertThat(Files.isSameFile(copied, destination)).isTrue();
        assertThat(Files.readString(destination.resolve("nested/b.json"))).isEqualTo("[]\n");
        assertThatThrownBy(() -> WorkflowSecurityV5.copyConfinedDirectory(source, destination))
                .hasMessage("ledger destination already exists");

        Path outside = temporary.resolve("outside.json");
        Files.writeString(outside, "{}\n");
        Files.createSymbolicLink(source.resolve("escape.json"), outside);
        assertThatThrownBy(() -> WorkflowSecurityV5.copyConfinedDirectory(
                source, temporary.resolve("copy-2"))).hasMessageContaining("symlink");
        Files.delete(source.resolve("escape.json"));
        Files.createLink(source.resolve("alias.json"), source.resolve("a.json"));
        assertThatThrownBy(() -> WorkflowSecurityV5.copyConfinedDirectory(
                source, temporary.resolve("copy-3"))).hasMessageContaining("singly-linked");
    }

    @Test
    void fullSnapshotVerifierFailsClosedBeforeSemanticReadsForHostileCustody() throws Exception {
        byte[] cycle = "{}\n".getBytes(StandardCharsets.UTF_8);
        Path root = Files.createDirectory(temporary.resolve(JsonHashes.sha256(cycle)));
        Files.write(root.resolve("v5-shadow-cycle.json"), cycle);
        assertThatThrownBy(() -> WorkflowSecurityV5.verifyProspectiveSnapshotV5(
                new WorkflowSecurityV5.ProspectiveSnapshotOptions(root, temporary)))
                .hasMessage("prospective snapshot root inventory is not exact");

        Path wrongRoot = Files.createDirectory(temporary.resolve("wrong-root"));
        Files.write(wrongRoot.resolve("v5-shadow-cycle.json"), cycle);
        assertThatThrownBy(() -> WorkflowSecurityV5.verifyProspectiveSnapshotV5(
                new WorkflowSecurityV5.ProspectiveSnapshotOptions(wrongRoot, temporary)))
                .hasMessage("prospective snapshot root is not the exact completed-cycle byte hash");

        Path symlinkRoot = Files.createDirectory(temporary.resolve("symlink-snapshot"));
        Files.write(symlinkRoot.resolve("v5-shadow-cycle.json"), cycle);
        Files.createSymbolicLink(symlinkRoot.resolve("escape.json"), root.resolve("v5-shadow-cycle.json"));
        assertThatThrownBy(() -> WorkflowSecurityV5.verifyProspectiveSnapshotV5(
                new WorkflowSecurityV5.ProspectiveSnapshotOptions(symlinkRoot, temporary)))
                .hasMessageContaining("symlink");

        Path hardlinkRoot = Files.createDirectory(temporary.resolve("hardlink-snapshot"));
        Files.write(hardlinkRoot.resolve("v5-shadow-cycle.json"), cycle);
        Files.createLink(hardlinkRoot.resolve("alias.json"), hardlinkRoot.resolve("v5-shadow-cycle.json"));
        assertThatThrownBy(() -> WorkflowSecurityV5.verifyProspectiveSnapshotV5(
                new WorkflowSecurityV5.ProspectiveSnapshotOptions(hardlinkRoot, temporary)))
                .hasMessageContaining("singly-linked");
    }

    @Test
    void fullSnapshotVerifierRejectsPublicPemBeforeSemanticReads() throws Exception {
        Path fixtureRoot = Files.createDirectory(temporary.resolve("public-pem-snapshot"));
        ObjectNode attestation = JsonHashes.mapper().createObjectNode();
        attestation.put("public_key_pem",
                "-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEA" + "A".repeat(24)
                        + "\n-----END PUBLIC KEY-----\n");
        Files.writeString(fixtureRoot.resolve("v5-actions-attestation.json"),
                JsonHashes.mapper().writeValueAsString(attestation) + "\n");

        assertThatThrownBy(() -> WorkflowSecurityV5.verifyProspectiveSnapshotV5(
                new WorkflowSecurityV5.ProspectiveSnapshotOptions(
                        fixtureRoot, temporary, null, "a".repeat(64),
                        System.currentTimeMillis())))
                .hasMessage("prospective evidence snapshot contains key/PEM material: "
                        + "v5-actions-attestation.json");
    }

    @Test
    void trustedRegistryMustMatchTheExactGitHeadBytes() throws Exception {
        Path checkout = Files.createDirectory(temporary.resolve("registry-checkout"));
        runGit(checkout, "init");
        runGit(checkout, "config", "user.name", "Migration Test");
        runGit(checkout, "config", "user.email", "migration-test@example.invalid");

        String relative = "strategy-research/config/v5-attestation-key-registry.json";
        Path registry = checkout.resolve(relative);
        Files.createDirectories(registry.getParent());
        byte[] committed = "{\"schema\":\"strategy-github-attestation-key-registry/1\"}\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(registry, committed);
        runGit(checkout, "add", relative);
        runGit(checkout, "commit", "-m", "freeze registry");

        ProspectiveSnapshotVerifierV5.requireGitHeadRegistry(checkout, relative, committed);

        byte[] tampered = "{\"schema\":\"tampered\"}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(registry, tampered);
        assertThatThrownBy(() -> ProspectiveSnapshotVerifierV5.requireGitHeadRegistry(
                checkout, relative, tampered))
                .hasMessage("trusted attestation registry is not anchored to Git HEAD");

        assertThatThrownBy(() -> ProspectiveSnapshotVerifierV5.requireGitHeadRegistry(
                checkout, "strategy-research/config/untracked.json", tampered))
                .hasMessage("trusted attestation registry is not anchored to Git HEAD");
    }

    private void writeBundle(
            String filename, String ledgerPath, Map<String, ObjectNode> references) throws IOException {
        ObjectNode bundle = JsonHashes.mapper().createObjectNode();
        bundle.put("schema", "strategy-prospective-source-bundle/1");
        bundle.put("version", 1);
        bundle.put("status", "FROZEN");
        bundle.put("decision", "SHADOW");
        bundle.put("lineage_sha256", "a".repeat(64));
        bundle.put("ledger_path", ledgerPath);
        bundle.put("expected_head_sha256", "b".repeat(64));
        references.forEach((role, reference) -> bundle.set(role, reference.deepCopy()));
        bundle.put("content_sha256", JsonHashes.ownHash(bundle));
        Files.write(temporary.resolve(filename), JsonHashes.mapper().writeValueAsBytes(bundle));
    }

    private static Map<String, ObjectNode> deepReferences(Map<String, ObjectNode> references) {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        references.forEach((role, value) -> result.put(role, value.deepCopy()));
        return result;
    }

    private static void runGit(Path checkout, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(checkout.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("git output: %s", output).isZero();
    }

}
