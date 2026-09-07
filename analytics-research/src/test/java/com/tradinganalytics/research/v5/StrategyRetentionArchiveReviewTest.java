package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent current-command checks for the explicitly noncanonical archive. */
final class StrategyRetentionArchiveReviewTest {
    @TempDir Path temporary;

    @Test
    void markedArchiveMayContainPartialJsonWithoutBreakingCanonicalIndex() throws Exception {
        Path root = seed();
        Path archive = Files.createDirectories(root.resolve("evidence"));
        mark(archive);
        Path raw = archive.resolve("in-progress.json");
        Files.writeString(raw, "{partial raw output");
        byte[] before = Files.readAllBytes(raw);
        JsonNode result = index(root);
        assertThat(result.path("index").path("records").size()).isEqualTo(1);
        assertThat(result.path("index").path("records").get(0).path("schema").asText())
                .isEqualTo("strategy-research-evidence-summary/1");
        assertThat(Files.readAllBytes(raw)).isEqualTo(before);
    }

    @Test
    void unmarkedArchiveDoesNotSilentlySuppressInvalidEvidence() throws Exception {
        Path root = seed();
        Path archive = Files.createDirectories(root.resolve("evidence"));
        Files.writeString(archive.resolve("raw.json"), "[]");
        assertThatThrownBy(() -> index(root)).isInstanceOf(IllegalArgumentException.class);
        assertThat(output()).doesNotExist();
    }

    @Test
    void invalidOutsideArtifactCannotBeHiddenByAnArchiveMarker() throws Exception {
        Path root = seed();
        mark(Files.createDirectories(root.resolve("evidence")));
        index(root);
        byte[] priorIndex = Files.readAllBytes(output());
        Files.writeString(root.resolve("unknown.json"), "{\"schema\":\"unknown-format/1\"}");
        assertThatThrownBy(() -> index(root)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readAllBytes(output())).isEqualTo(priorIndex);
    }

    @Test
    void unknownMarkerVersionDoesNotEnableExclusion() throws Exception {
        Path root = seed();
        Path archive = Files.createDirectories(root.resolve("evidence"));
        Files.writeString(archive.resolve(".retention-archive"), "UNKNOWN_RETENTION_V999\n");
        assertThatThrownBy(() -> index(root)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("marker");
        assertThat(output()).doesNotExist();
    }

    @Test
    void symlinkArchiveCannotEnableAnExclusion() throws Exception {
        Path root = seed();
        Path external = Files.createDirectories(temporary.resolve("external-archive"));
        mark(external);
        Files.createSymbolicLink(root.resolve("evidence"), external);
        assertThatThrownBy(() -> index(root)).isInstanceOf(IllegalArgumentException.class);
        assertThat(output()).doesNotExist();
    }

    @Test
    void symlinkMarkerCannotEnableAnExclusion() throws Exception {
        Path root = seed();
        Path archive = Files.createDirectories(root.resolve("evidence"));
        Path marker = temporary.resolve("external-marker");
        Files.copy(repoFile("strategy-research/v5-records/evidence/.retention-archive"), marker);
        Files.createSymbolicLink(archive.resolve(".retention-archive"), marker);
        assertThatThrownBy(() -> index(root)).isInstanceOf(IllegalArgumentException.class);
        assertThat(output()).doesNotExist();
    }

    @Test
    void explicitlyIndexingTheArchiveItselfRequiresACuratedRoot() throws Exception {
        Path root = seed();
        mark(root);
        assertThatThrownBy(() -> index(root)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archive");
        assertThat(output()).doesNotExist();
    }

    @Test
    void curatedTypedSummaryRootStillIndexesNormally() throws Exception {
        JsonNode result = index(seed());
        assertThat(result.path("index").path("records").size()).isEqualTo(1);
    }

    @Test
    void ownedCanonicalArtifactCannotBeExcludedAsRetentionData() {
        Path archive = temporary.resolve("evidence");
        assertThatThrownBy(() -> checkPublicationOverlap(archive,
                Set.of(archive.resolve("canonical.json")), Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("canonical publication");
    }

    @Test
    void committedCanonicalArtifactCannotBeExcludedAsRetentionData() {
        Path archive = temporary.resolve("evidence");
        assertThatThrownBy(() -> checkPublicationOverlap(archive,
                Set.of(), Set.of(archive.resolve("committed.json"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("committed canonical");
    }

    @Test
    void similarlyNamedSiblingIsOutsideTheArchiveBoundary() {
        checkPublicationOverlap(temporary.resolve("evidence"),
                Set.of(temporary.resolve("evidence-canonical/result.json")), Set.of());
    }

    private static void checkPublicationOverlap(Path archive, Set<Path> owned, Set<Path> committed) {
        try {
            Class<?> inventory = Class.forName(StrategyResearchAuthoritativeV5.class.getName()
                    + "$PublicationInventory");
            var constructor = inventory.getDeclaredConstructor(Set.class, Set.class);
            constructor.setAccessible(true);
            var method = StrategyResearchAuthoritativeV5.class.getDeclaredMethod(
                    "rejectRetentionPublicationOverlap", Path.class, inventory);
            method.setAccessible(true);
            method.invoke(null, archive, constructor.newInstance(owned, committed));
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Update archive review adapter after extraction", error);
        }
    }

    private Path seed() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("records"));
        Files.copy(repoFile("strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/"
                + "fixed-baseline-e11-result-summary.json"), root.resolve("summary.json"));
        return root;
    }

    private void mark(Path directory) throws Exception {
        Files.copy(repoFile("strategy-research/v5-records/evidence/.retention-archive"),
                directory.resolve(".retention-archive"));
    }

    private JsonNode index(Path root) {
        ObjectNode options = JsonHashes.mapper().createObjectNode()
                .put("root", root.toString()).put("out", output().toString())
                .put("record_root", temporary.resolve("custody").toString());
        return StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("index", options);
    }

    private Path output() { return temporary.resolve("result-index.json"); }

    private static Path repoFile(String relative) {
        Path path = Path.of(relative);
        return (Files.exists(path) ? path : Path.of("..", relative)).toAbsolutePath().normalize();
    }
}
