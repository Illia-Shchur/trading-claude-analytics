package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LegacyResearchSecurityTest {
    @TempDir Path temporary;

    @Test
    void immutableWritesRejectOverwriteSymlinkTargetAndSymlinkParent() throws Exception {
        Path target = temporary.resolve("immutable.json");
        LegacyResearchV1.writeImmutable(target, object().put("first", true));
        assertThatThrownBy(() -> LegacyResearchV1.writeImmutable(
                target, object().put("second", true)))
                .hasMessage("overwrite refused: " + target);
        assertThat(Files.readString(target)).contains("\"first\": true");

        Path outside = write(temporary.resolve("outside.json"), object().put("outside", true));
        Path symlink = temporary.resolve("link.json");
        createSymlink(symlink, outside);
        assertThatThrownBy(() -> LegacyResearchV1.writeImmutable(
                symlink, object().put("tampered", true)))
                .hasMessage("overwrite refused: " + symlink);
        assertThat(Files.readString(outside)).contains("outside");

        Path realDirectory = temporary.resolve("real");
        Files.createDirectory(realDirectory);
        Path linkedDirectory = temporary.resolve("linked-directory");
        createSymlink(linkedDirectory, realDirectory);
        assertThatThrownBy(() -> LegacyResearchV1.writeImmutable(
                linkedDirectory.resolve("value.json"), object().put("x", 1)))
                .hasMessageContaining("output parent contains a symlink");
        assertThat(realDirectory).isEmptyDirectory();
    }

    @Test
    void everyJsonTrustBoundaryRejectsSymlinkAndHardlinkFiles() throws Exception {
        Path source = write(temporary.resolve("source.json"), object().put("x", 1));
        Path symbolic = temporary.resolve("symbolic.json");
        createSymlink(symbolic, source);
        assertThatThrownBy(() -> LegacyResearchV1.readJSON(symbolic))
                .hasMessageContaining("regular, singly-linked file");

        Path hard = temporary.resolve("hard.json");
        try {
            Files.createLink(hard, source);
        } catch (UnsupportedOperationException | IOException unsupported) {
            assumeTrue(false, "hard links are unavailable");
        }
        assertThatThrownBy(() -> LegacyResearchV1.readJSON(hard))
                .hasMessageContaining("regular, singly-linked file");
    }

    @Test
    void migrationRejectsSymlinkRunDirectoriesAndHardlinkedRunFiles() throws Exception {
        Path root = temporary.resolve("research");
        Path runs = root.resolve("runs");
        Files.createDirectories(runs);
        Path external = temporary.resolve("external-run");
        Files.createDirectory(external);
        createSymlink(runs.resolve("linked"), external);
        assertThatThrownBy(() -> LegacyResearchMigrationV3.migrate(
                root, temporary.resolve("symlink-report.json")))
                .hasMessageContaining("migration run entry must be a real directory");

        Files.delete(runs.resolve("linked"));
        Path run = runs.resolve("hardlinked");
        Files.createDirectory(run);
        Path source = write(temporary.resolve("run-source.json"),
                object().put("schema", "strategy-run/1"));
        try {
            Files.createLink(run.resolve("run.json"), source);
        } catch (UnsupportedOperationException | IOException unsupported) {
            assumeTrue(false, "hard links are unavailable");
        }
        assertThatThrownBy(() -> LegacyResearchMigrationV3.migrate(
                root, temporary.resolve("hardlink-report.json")))
                .hasMessageContaining("regular, singly-linked file");
    }

    @Test
    void migrationRejectsMetricTraversalBeforeReadingOutsideFile() throws Exception {
        Path root = temporary.resolve("research-traversal");
        Path run = root.resolve("runs/run-1");
        Files.createDirectories(run);
        write(temporary.resolve("secret.jsonl"), object().put("asset", "btc"));
        ObjectNode value = object().put("schema", "strategy-run/1");
        value.set("artifacts", object().set("metrics",
                object().put("path", "../../../secret.jsonl")));
        write(run.resolve("run.json"), value);
        assertThatThrownBy(() -> LegacyResearchMigrationV3.migrate(
                root, temporary.resolve("traversal-report.json")))
                .hasMessageContaining("invalid traversal component");
    }

    @Test
    void registryWalkRejectsSymlinksHardlinksAndOversizedArtifacts() throws Exception {
        Path root = temporary.resolve("registry");
        Files.createDirectories(root.resolve("definitions"));
        Path outside = write(temporary.resolve("outside-registry.json"), object().put("x", 1));
        createSymlink(root.resolve("definitions/link.json"), outside);
        assertThatThrownBy(() -> LegacyResearchV1.validateRegistry(root))
                .hasMessageContaining("tree contains a symlink");

        Files.delete(root.resolve("definitions/link.json"));
        byte[] oversized = new byte[(int) LegacyResearchV1.MAX_TRACKED_ARTIFACT_BYTES + 1];
        Files.write(root.resolve("oversized.bin"), oversized);
        assertThatThrownBy(() -> LegacyResearchV1.validateRegistry(root))
                .hasMessageContaining("exceeds 10MiB");
    }

    @Test
    void burnedConfirmationCannotBeReplayed() {
        ObjectNode reservation = object()
                .put("schema", LegacyResearchV3.RESERVATION_SCHEMA)
                .put("status", "RESERVED").put("seal_id", "fixture");
        reservation.put("content_sha256", LegacyResearchV3.ownHash(reservation));
        Path burnRoot = temporary.resolve("burn");
        LegacyResearchV3.burnReservation(reservation, burnRoot);
        assertThatThrownBy(() -> LegacyResearchV3.burnReservation(reservation, burnRoot))
                .hasMessage("confirmation seal already burned: fixture");
    }

    @Test
    void confirmationWorkflowPreservesPreflightBeforeAnyDeprecatedBurnPath() throws Exception {
        String workflow = Files.readString(repositoryRoot()
                .resolve(".github/workflows/strategy-confirmation.yml"));
        assertThat(workflow).contains("--preflight");
        assertThat(workflow).contains("./bin/analytics ci-confirmation");
        assertThat(workflow).doesNotContain(
                "burn-confirmation",
                "ci-burn-tag",
                "strategy-attestation.mjs sign",
                "node tools/");
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null
                && !Files.isRegularFile(current.resolve(".github/workflows/strategy-confirmation.yml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("repository root not found");
        }
        return current;
    }

    private static void createSymlink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException unsupported) {
            assumeTrue(false, "symbolic links are unavailable");
        }
    }
}
