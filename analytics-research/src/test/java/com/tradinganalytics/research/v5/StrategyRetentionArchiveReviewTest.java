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
        Files.writeString(marker, "strategy-research-retention-archive/1\n");
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
        ObjectNode summary = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-research-evidence-summary/1")
                .put("version", 1).put("status", "BLOCKED")
                .put("summary_kind", "FIXED_BASELINE_RESULT")
                .put("display_only", true).put("promotion_eligible", false)
                .put("activation_authorized", false);
        summary.putObject("source_result")
                .put("path", "fixture-result.json")
                .put("byte_sha256", "b".repeat(64))
                .put("content_sha256", "c".repeat(64))
                .put("bytes", 1)
                .put("original_schema", "strategy-fixed-baseline-result/1");
        summary.putObject("summary")
                .put("stage", "FIXED_BASELINE")
                .put("evidence_phase", "DEVELOPMENT")
                .put("hypothesis_family", "fixture-family")
                .put("physical_input_sha256", "a".repeat(64));
        summary.put("content_sha256", JsonHashes.ownHash(summary));
        Files.writeString(root.resolve("summary.json"), JsonHashes.mapper().writeValueAsString(summary));
        return root;
    }

    private void mark(Path directory) throws Exception {
        Files.writeString(directory.resolve(".retention-archive"), "strategy-research-retention-archive/1\n");
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
