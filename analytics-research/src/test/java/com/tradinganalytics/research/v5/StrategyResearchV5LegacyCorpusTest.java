package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.research.legacy.LegacyResearchNext;
import com.tradinganalytics.research.legacy.LegacyResearchV1;
import com.tradinganalytics.research.legacy.LegacyResearchV2;
import com.tradinganalytics.research.legacy.LegacyResearchV3;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StrategyResearchV5LegacyCorpusTest {
    private static final ObjectMapper JSON = JsonHashes.mapper();

    @Test
    void everyTrackedLegacyArtifactValidatesIndexesAndLeavesSourceBytesUntouched(@TempDir Path temporary)
            throws Exception {
        Path repository = repositoryRoot();
        List<Path> tracked = trackedLegacyJson(repository);
        assertThat(tracked).as("actual tracked v1-v4 corpus").hasSizeGreaterThan(30);

        Map<Path, String> originalHashes = new HashMap<>();
        Path copiedRoot = temporary.resolve("strategy-research");
        for (Path relative : tracked) {
            Path source = repository.resolve(relative); originalHashes.put(relative, JsonHashes.sha256(source));
            Path destination = copiedRoot.resolve(repository.resolve("strategy-research").relativize(source));
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        }

        ArrayNode records = JSON.createArrayNode(); Set<String> expectedSchemas = new HashSet<>();
        for (Path relative : tracked) {
            Path copied = copiedRoot.resolve(repository.resolve("strategy-research").relativize(repository.resolve(relative)));
            JsonNode value = JSON.readTree(Files.readAllBytes(copied));
            assertThat(StrategyResearchV5.validateV5Artifact(value,
                    StrategyResearchV5LegacyCorpusTest::validateLegacyArtifact))
                    .as("legacy validation %s", relative).isTrue();
            if (value.path("schema").asText().startsWith("strategy-research-index/")) continue;
            expectedSchemas.add(value.path("schema").asText());
            String byteSha = JsonHashes.sha256(copied);
            String content = value.path("content_sha256").asText().matches("[a-f0-9]{64}")
                    ? value.path("content_sha256").asText() : byteSha;
            records.addObject().put("schema", value.path("schema").asText()).put("content_sha256", content)
                    .put("byte_sha256", byteSha)
                    .put("path", copiedRoot.relativize(copied).toString().replace('\\', '/'));
        }
        List<JsonNode> sorted = new ArrayList<>(); records.forEach(sorted::add);
        sorted.sort(java.util.Comparator.comparing(row -> row.path("schema").asText() + ":"
                + row.path("content_sha256").asText() + ":" + row.path("path").asText()));
        ObjectNode index = JSON.createObjectNode().put("schema", "strategy-research-index/5").put("version", 1);
        ArrayNode ordered = index.putArray("records"); sorted.forEach(ordered::add); index.put("content_sha256", JsonHashes.ownHash(index));

        Map<String, JsonNode> byPath = new HashMap<>(); index.path("records").forEach(row -> byPath.put(row.path("path").asText(), row));
        for (Path relative : tracked) {
            Path copied = copiedRoot.resolve(repository.resolve("strategy-research").relativize(repository.resolve(relative)));
            JsonNode value = JSON.readTree(Files.readAllBytes(copied));
            if (value.path("schema").asText().startsWith("strategy-research-index/")) continue;
            JsonNode row = byPath.get(copiedRoot.relativize(copied).toString().replace('\\', '/'));
            assertThat(row).as("indexed legacy artifact %s", relative).isNotNull();
            assertThat(row.path("schema").asText()).isEqualTo(value.path("schema").asText());
            assertThat(row.path("byte_sha256").asText()).isEqualTo(JsonHashes.sha256(copied));
        }
        long expectedRecordCount = tracked.stream().filter(relative -> {
            try {
                return !JSON.readTree(Files.readAllBytes(repository.resolve(relative))).path("schema").asText()
                        .startsWith("strategy-research-index/");
            } catch (java.io.IOException error) {
                throw new IllegalStateException(error);
            }
        }).count();
        assertThat(index.path("records")).hasSize((int) expectedRecordCount);
        assertThat(index.path("records")).extracting(row -> row.path("schema").asText())
                .containsAll(expectedSchemas);
        originalHashes.forEach((relative, before) -> assertThat(JsonHashes.sha256(repository.resolve(relative)))
                .as("source bytes remain immutable: %s", relative).isEqualTo(before));
    }

    private static boolean validateLegacyArtifact(JsonNode value) {
        String schema = value.path("schema").asText();
        if (schema.startsWith("strategy-research-index/")) return true;
        if (schema.endsWith("/4")) return LegacyResearchNext.validateNextArtifact(value);
        if (schema.endsWith("/3")) {
            if (LegacyResearchV3.RUN_V3_SCHEMA.equals(schema)) return LegacyResearchV3.validateRunV3(value);
            if (LegacyResearchV3.EVIDENCE_BUNDLE_V2_SCHEMA.equals(schema)) return LegacyResearchV3.validateEvidenceBundleV2(value);
        }
        if (schema.endsWith("/2")) return LegacyResearchV2.validateV2Document(value);
        if (schema.endsWith("/1")) {
            if (LegacyResearchV1.RUN_SCHEMA.equals(schema)) return true;
            if (LegacyResearchV1.DEFINITION_SCHEMA.equals(schema)) return LegacyResearchV1.validateDefinition(value);
            if (LegacyResearchV1.EXPERIMENT_SCHEMA.equals(schema)) return LegacyResearchV1.validateExperiment(value);
            if (LegacyResearchV1.CANDIDATE_SET_SCHEMA.equals(schema)) return LegacyResearchV1.validateCandidateSet(value);
            return LegacyResearchNext.validateNextArtifact(value);
        }
        throw new IllegalArgumentException("unsupported legacy schema " + schema);
    }

    private static List<Path> trackedLegacyJson(Path repository) throws Exception {
        Process process = new ProcessBuilder("git", "ls-files", "-z", "strategy-research")
                .directory(repository.toFile()).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
        List<Path> result = new ArrayList<>();
        for (String value : new String(output, java.nio.charset.StandardCharsets.UTF_8).split("\u0000")) {
            if (value.matches(".*(?:^|/)(?:definitions|experiments|runs)/.*\\.json$")
                    || value.matches(".*(?:^|/)index\\.json$")) result.add(Path.of(value));
        }
        return result;
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null && (!Files.isRegularFile(cursor.resolve("pom.xml"))
                || !Files.isDirectory(cursor.resolve("analytics-research")))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("repository root not found");
        return cursor;
    }
}
